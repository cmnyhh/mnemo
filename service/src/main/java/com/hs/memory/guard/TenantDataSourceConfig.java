package com.hs.memory.guard;

import com.hs.memory.Settings;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

/**
 * 把容器里的 {@link DataSource} 包一层，让业务请求能自动落租户上下文。
 *
 * <p>用 BeanPostProcessor 而不是自己 `@Bean` 一个 Hikari：连接池参数（上限、超时、
 * 存活时间）都在 `spring.datasource.hikari` 里，自己建一遍等于把它们抄一遍，
 * 以后改配置就会两处不同步。
 *
 * <p>启动期 schema 初始化拿到的也是包装后的对象，但那时没有租户上下文，
 * 包装是透明的——建表仍然以表 owner 身份执行。
 */
@Component
public class TenantDataSourceConfig implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(TenantDataSourceConfig.class);

    private final Settings settings;

    public TenantDataSourceConfig(Settings settings) {
        this.settings = settings;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!(bean instanceof DataSource ds) || bean instanceof TenantAwareDataSource) {
            return bean;
        }
        log.info("数据源 {} 已接入租户上下文（rls={} role={}）",
                beanName, settings.rlsEnabled(), settings.rlsRole());
        return new TenantAwareDataSource(ds, settings.rlsRole(), settings.rlsEnabled());
    }
}
