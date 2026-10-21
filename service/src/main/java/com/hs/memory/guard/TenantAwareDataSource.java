package com.hs.memory.guard;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.logging.Logger;
import java.io.PrintWriter;
import javax.sql.DataSource;

/**
 * 让每个借出的连接在「有租户上下文」时自动降权到受限角色并设置会话变量。
 *
 * <p>做法不是另开一个数据源，而是 {@code SET ROLE}：连接仍用主账号（表 owner，
 * 负责建表与跨租户运维），但一旦请求声明了租户，就在这条连接上切到
 * {@code memory_app}——owner 在启用了 RLS 的表上会**绕过**策略，受限角色不会。
 * 这样 DDL 与治理不受影响，业务面却真的被 RLS 兜住了。
 *
 * <p>三个必须守住的点：
 * <ul>
 *   <li>绑定只做一次（同一个连接代理内），否则每条语句都多两个来回；</li>
 *   <li>归还前必须 {@code RESET ROLE}——连接是复用的，残留会让下一个无上下文的
 *       请求（启动期 DDL、控制台）被误当成租户会话；</li>
 *   <li>没有上下文时完全透明：不加角色、不设变量，行为与包装前一致。</li>
 * </ul>
 */
public class TenantAwareDataSource implements DataSource {

    private final DataSource delegate;
    private final String role;
    private final boolean enabled;

    public TenantAwareDataSource(DataSource delegate, String role, boolean enabled) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.role = role;
        this.enabled = enabled;
    }

    /** 角色名会拼进 SET ROLE，虽然来自配置，仍然只允许标识符字符。 */
    static String checkedRole(String role) {
        if (role == null || !role.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("RLS 角色名不合法: " + role);
        }
        return role;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrap(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrap(delegate.getConnection(username, password));
    }

    private Connection wrap(Connection raw) {
        if (!enabled) {
            return raw;
        }
        return (Connection) Proxy.newProxyInstance(
                TenantAwareDataSource.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new Binder(raw, checkedRole(role)));
    }

    /** 连接代理：拦「执行语句」与「关闭」两个时机。 */
    private static final class Binder implements java.lang.reflect.InvocationHandler {

        private final Connection raw;
        private final String role;
        private boolean bound;

        Binder(Connection raw, String role) {
            this.raw = raw;
            this.role = role;
        }

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
            String name = method.getName();
            switch (name) {
                case "prepareStatement", "prepareCall", "createStatement" -> bind();
                case "close" -> {
                    unbind();
                    raw.close();
                    return null;
                }
                case "equals" -> {
                    return proxy == args[0];
                }
                case "hashCode" -> {
                    return System.identityHashCode(proxy);
                }
                case "toString" -> {
                    return "TenantAwareConnection[" + raw + "]";
                }
                default -> {
                    // 其余直接透传
                }
            }
            try {
                return method.invoke(raw, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        private void bind() throws SQLException {
            String tenant = TenantContext.get();
            if (bound || tenant == null || tenant.isBlank()) {
                return;
            }
            boolean roleSet = false;
            try (Statement st = raw.createStatement()) {
                st.execute("set role " + role);
                roleSet = true;
            }
            try (PreparedStatement ps = raw.prepareStatement("select set_config('app.tenant_id', ?, false)")) {
                ps.setString(1, tenant);
                ps.execute();
                bound = true;
            } catch (SQLException e) {
                // 变量没设成，角色就不能留着——否则这条连接会以受限身份被下一个请求借走。
                if (roleSet) {
                    resetQuietly();
                }
                throw e;
            }
        }

        private void unbind() {
            if (!bound) {
                return;
            }
            resetQuietly();
            bound = false;
        }

        private void resetQuietly() {
            try (Statement st = raw.createStatement()) {
                st.execute("reset role");
                st.execute("select set_config('app.tenant_id', '', false)");
            } catch (SQLException ignored) {
                // 连接已经坏了就让它坏：Hikari 归还时会做自己的校验并丢弃。
            }
        }
    }

    // ---- 其余 DataSource 方法透传 ----

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() {
        try {
            return delegate.getParentLogger();
        } catch (SQLException e) {
            return Logger.getLogger("com.hs.memory");
        }
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || delegate.isWrapperFor(iface);
    }
}
