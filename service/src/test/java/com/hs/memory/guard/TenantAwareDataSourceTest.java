package com.hs.memory.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 连接代理的三条底线：没上下文时透明、有上下文时降权、归还前复位。
 * 这三条任何一条破了，症状都不是报错，而是「悄悄读到别人的数据」或「启动建表失败」。
 */
class TenantAwareDataSourceTest {

    @AfterEach
    void clear() {
        TenantContext.run("", () -> {
        });
    }

    @Test
    void 没有租户上下文时完全不碰连接() throws Exception {
        Recorder rec = new Recorder();
        try (Connection c = ds(rec).getConnection()) {
            try (Statement st = c.createStatement()) {
                st.execute("select 1");
            }
        }
        assertThat(rec.sql).containsExactly("select 1", "<close>");
    }

    @Test
    void 有上下文时先降权再设会话变量() throws Exception {
        Recorder rec = new Recorder();
        TenantContext.run("acme", () -> {
            try (Connection c = ds(rec).getConnection()) {
                try (PreparedStatement ps = c.prepareStatement("select 1")) {
                    ps.execute();
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(rec.sql).containsSubsequence(
                "set role memory_app",
                "set_config:app.tenant_id=acme",
                "select 1",
                "reset role",
                "set_config:app.tenant_id=",
                "<close>");
    }

    @Test
    void 同一连接内只绑定一次() throws Exception {
        Recorder rec = new Recorder();
        TenantContext.run("acme", () -> {
            try (Connection c = ds(rec).getConnection()) {
                for (int i = 0; i < 3; i++) {
                    try (PreparedStatement ps = c.prepareStatement("select " + i)) {
                        ps.execute();
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(rec.sql.stream().filter("set role memory_app"::equals)).hasSize(1);
    }

    @Test
    void 嵌套上下文恢复外层租户() {
        String inner = TenantContext.call("outer", () -> TenantContext.call("inner", TenantContext::get));
        assertThat(inner).isEqualTo("inner");
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void 角色名只接受标识符() {
        TenantAwareDataSource bad = new TenantAwareDataSource(new Recorder().dataSource(), "memory_app; drop table x", true);
        assertThatThrownBy(bad::getConnection).isInstanceOf(IllegalArgumentException.class);
    }

    private static TenantAwareDataSource ds(Recorder rec) {
        return new TenantAwareDataSource(rec.dataSource(), "memory_app", true);
    }

    /** 记录执行过的语句，不连真库。 */
    private static final class Recorder {

        final List<String> sql = new ArrayList<>();

        DataSource dataSource() {
            Connection connection = connection();
            return (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{DataSource.class}, (p, m, a) -> switch (m.getName()) {
                        case "getConnection" -> connection;
                        case "toString" -> "recorder-ds";
                        default -> null;
                    });
        }

        private Connection connection() {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Connection.class}, (p, m, a) -> switch (m.getName()) {
                        case "createStatement" -> statement();
                        case "prepareStatement" -> prepared((String) a[0]);
                        case "close" -> {
                            sql.add("<close>");
                            yield null;
                        }
                        case "toString" -> "recorder-conn";
                        case "hashCode" -> System.identityHashCode(p);
                        case "equals" -> p == a[0];
                        default -> null;
                    });
        }

        private Statement statement() {
            return (Statement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Statement.class}, (p, m, a) -> {
                        if ("execute".equals(m.getName())) {
                            String s = ((String) a[0]).trim();
                            java.util.regex.Matcher matcher =
                                    java.util.regex.Pattern.compile("set_config\\('app\\.tenant_id',\\s*'([^']*)'")
                                            .matcher(s);
                            if (s.toLowerCase().startsWith("set role ")) {
                                sql.add(normalizeRole(s));
                            } else if (matcher.find()) {
                                sql.add("set_config:app.tenant_id=" + matcher.group(1));
                            } else {
                                sql.add(s);
                            }
                        }
                        return true;
                    });
        }

        private PreparedStatement prepared(String query) {
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (p, m, a) -> {
                        if ("setString".equals(m.getName())) {
                            pending.set(String.valueOf(a[1]));
                        }
                        if ("execute".equals(m.getName())) {
                            if (query.contains("set_config")) {
                                sql.add("set_config:app.tenant_id=" + pending.get());
                            } else {
                                sql.add(query);
                            }
                        }
                        return true;
                    });
        }

        private final ThreadLocal<String> pending = ThreadLocal.withInitial(() -> "");

        private static String normalizeRole(String s) {
            return s.toLowerCase().startsWith("set role") ? "set role memory_app" : s;
        }
    }
}
