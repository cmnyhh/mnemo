package com.hs.memory;

import com.hs.memory.admin.AdminAuthService;
import com.hs.memory.config.RuntimeConfig;
import com.hs.memory.repo.MemoryRepository;
import com.hs.memory.vector.QdrantClient;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

/**
 * 启动时跑幂等 schema（容器化要求之一：迁移在启动时执行，不靠手工 SQL）。
 *
 * <p>顺序是有依赖的：schema 必须先建表，控制台配置与口令才能从库里读；
 * 配置读完才知道向量空间是什么，Qdrant 集合才能按正确维度检查。
 * 早一步会读到不存在的表，晚一步会按环境变量的旧维度建集合。
 */
@Component
public class SchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);

    private final DataSource dataSource;
    private final RuntimeConfig config;
    private final AdminAuthService auth;
    private final MemoryRepository repo;
    private final QdrantClient qdrant;

    public SchemaInitializer(DataSource dataSource, RuntimeConfig config, AdminAuthService auth,
                             MemoryRepository repo, QdrantClient qdrant) {
        this.dataSource = dataSource;
        this.config = config;
        this.auth = auth;
        this.repo = repo;
        this.qdrant = qdrant;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 角色必须先于 schema：schema 里的 grant ... to memory_app 要求角色已存在。
        ensureRlsRole();

        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(new ClassPathResource("schema.sql"));
        populator.setContinueOnError(false);
        DatabasePopulatorUtils.execute(populator, dataSource);
        log.info("schema 已就绪");
        grantRlsRole();
        // 租户来自配置（MEMORY_TENANT），不是令牌。改了配置就会"看不见"旧租户的数据：
        // 不报错、不丢数据，只是搜不到——看起来像记忆服务坏了。这里至少喊一声。
        warnForeignTenants(config.settings().tenant());

        config.reload();
        auth.bootstrap();

        RuntimeConfig.Snapshot snap = config.snapshot();
        Settings settings = config.settings();
        repo.seedSpace(snap.spaceId(), snap.embeddingProvider(), snap.embeddingModel(),
                settings.qdrantCollection(), snap.embeddingDims());
        log.info("vector space: {} dims={} collection={} provider={}",
                snap.spaceId(), snap.embeddingDims(), settings.qdrantCollection(), snap.embeddingProvider());

        qdrant.setDims(snap.embeddingDims());
        try {
            qdrant.ensureCollection();
            log.info("qdrant 集合就绪: {}", qdrant.describe());
        } catch (Exception e) {
            log.error("qdrant 集合不可用，服务继续启动（仅 BM25 可用）: {}", e.getMessage());
        }

        // 空库没有历史向量，当前指纹天然就是「已重建」；否则新库一上来就报脏。
        if (config.requiresReindex() && repo.countActive(settings.tenant()) == 0) {
            config.markReindexed();
        }
    }

    /**
     * 建受限角色。PostgreSQL 没有 {@code create role if not exists}，
     * 而 schema.sql 按分号切分、不能写 DO 块，所以只能在这里吞掉「已存在」。
     */
    private void ensureRlsRole() {
        Settings current = config.settings();
        if (!current.rlsEnabled()) {
            log.info("RLS 已关闭，跳过受限角色检查");
            return;
        }
        String role = current.rlsRole();
        if (role == null || !role.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalStateException("memory.rls.role 不是合法标识符: " + role);
        }
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("create role " + role + " nologin");
            log.info("已创建受限角色 {}", role);
        } catch (SQLException e) {
            // 42710 = duplicate_object，也就是已经建过了，这是常态
            if (!"42710".equals(e.getSQLState())) {
                throw new IllegalStateException("创建 RLS 角色失败: " + e.getMessage(), e);
            }
        }
    }

    /**
     * 库里存在「不是当前配置租户」的数据时报一条警告。
     *
     * <p>单租户部署下这不会发生；一旦发生，说明有人改过 {@code MEMORY_TENANT}，或者把库
     * 指到了另一个实例的数据上。两种情况下旧记录都完好但不可见，而检索侧只会表现成
     * 「没找到」——所以要在启动时把人叫醒，而不是等用户来查为什么记忆空了。
     */
    private void warnForeignTenants(String configured) {
        String sql = "select distinct tenant_id from memory_item where tenant_id <> ? order by 1";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, configured);
            List<String> others = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    others.add(rs.getString(1));
                }
            }
            if (!others.isEmpty()) {
                log.warn("配置租户是「{}」，但库里还有 {} 个其他租户的数据：{}。"
                                + "它们对当前实例不可见（数据没丢）；要访问请把 MEMORY_TENANT 改回对应值，"
                                + "或另起一个实例。", configured, others.size(), others);
            }
        } catch (SQLException e) {
            // 表还没建（首次启动）或权限不足都可能走到这里，不影响启动
            log.debug("租户一致性检查跳过: {}", e.getMessage());
        }
    }

    /** 自定义角色名时补一次授权；默认角色 memory_app 的授权在 schema.sql 里。 */
    private void grantRlsRole() {
        Settings current = config.settings();
        if (!current.rlsEnabled() || "memory_app".equals(current.rlsRole())) {
            return;
        }
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            String role = current.rlsRole();
            st.execute("grant usage on schema public to " + role);
            st.execute("grant select on embedding_space to " + role);
            st.execute("grant select, insert, update, delete on memory_item, memory_asset, memory_vector to " + role);
        } catch (SQLException e) {
            throw new IllegalStateException("RLS 角色授权失败: " + e.getMessage(), e);
        }
    }
}
