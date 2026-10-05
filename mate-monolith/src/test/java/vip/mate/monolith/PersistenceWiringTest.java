package vip.mate.monolith;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusInnerInterceptorAutoConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import vip.mate.starter.datascope.DataScopeAutoConfiguration;
import vip.mate.starter.datascope.DataScopeInterceptor;
import vip.mate.starter.ds.config.DataSourceAutoConfiguration;
import vip.mate.starter.tenant.TenantAutoConfiguration;
import vip.mate.starter.tenant.TenantPropertiesAutoConfiguration;
import vip.mate.starter.tenant.core.TenantContext;
import vip.mate.system.infrastructure.dao.UserDao;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import vip.mate.starter.security.encrypt.EncryptTypeHandler;
import org.springframework.mock.env.MockEnvironment;
import org.apache.ibatis.type.StringTypeHandler;

import static org.assertj.core.api.Assertions.assertThat;

/** Boots the real monolith mapper set without external infrastructure or migrations. */
class PersistenceWiringTest {
    @Test
    void sharedFactoryScansAllServicesAndRetainsPluginOrder() {
        new ApplicationContextRunner()
                .withBean(EncryptTypeHandler.class, () -> new EncryptTypeHandler(new MockEnvironment()
                        .withProperty("mate.security.encrypt.key", "0123456789abcdef")))
                .withInitializer(context -> {
                    try {
                        new YamlPropertySourceLoader().load("defaults", new ClassPathResource("mate-defaults.yml"))
                                .forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class, MybatisPlusAutoConfiguration.class,
                        MybatisPlusInnerInterceptorAutoConfiguration.class,
                        TenantPropertiesAutoConfiguration.class, TenantAutoConfiguration.class,
                        DataScopeAutoConfiguration.class))
                .withPropertyValues("spring.datasource.dynamic.enabled=false",
                        "spring.datasource.url=jdbc:h2:mem:monolith_orm;MODE=MySQL",
                        "spring.datasource.driver-class-name=org.h2.Driver",
                        "spring.datasource.username=sa", "spring.datasource.password=",
                        "spring.datasource.druid.initial-size=0", "mate.tenant.enabled=true",
                        "mate.tenant.include-tables[0]=mate_user")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(SqlSessionFactory.class);
                    assertThat(context).hasBean("vip.mate.auth.infrastructure.dao.LoginLogDao");
                    assertThat(context).hasBean("vip.mate.system.admin.infrastructure.dao.LoginLogDao");
                    assertThat(context).hasBean("vip.mate.notice.infrastructure.dao.NoticeDao");
                    var configuration = context.getBean(SqlSessionFactory.class).getConfiguration();
                    assertThat(configuration.getTypeHandlerRegistry().getTypeHandler(String.class))
                            .isInstanceOf(StringTypeHandler.class);
                    assertThat(configuration.getTypeHandlerRegistry().getMappingTypeHandler(EncryptTypeHandler.class))
                            .isSameAs(context.getBean(EncryptTypeHandler.class));
                    assertThat(configuration.hasStatement(
                            "vip.mate.system.infrastructure.dao.UserDao.selectById")).isTrue();
                    assertThat(configuration.hasStatement(
                            "vip.mate.auth.infrastructure.dao.LoginLogDao.insert")).isTrue();
                    var plugins = context.getBean(MybatisPlusInterceptor.class).getInterceptors();
                    assertThat(plugins.stream().map(Object::getClass).toList()).containsExactly(
                            TenantLineInnerInterceptor.class, DataScopeInterceptor.class,
                            BlockAttackInnerInterceptor.class, PaginationInnerInterceptor.class);
                    assertThat(configuration.getInterceptors())
                            .containsExactly(context.getBean(MybatisPlusInterceptor.class));
                    JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
                    jdbc.execute("CREATE TABLE encrypted_probe (secret VARCHAR(512))");
                    EncryptTypeHandler encryption = context.getBean(EncryptTypeHandler.class);
                    jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
                        try (var insert = connection.prepareStatement("INSERT INTO encrypted_probe VALUES (?)")) {
                            encryption.setParameter(insert, 1, "private-value", org.apache.ibatis.type.JdbcType.VARCHAR);
                            insert.executeUpdate();
                        }
                        try (var select = connection.prepareStatement("SELECT secret FROM encrypted_probe");
                             var result = select.executeQuery()) {
                            assertThat(result.next()).isTrue();
                            assertThat(result.getString(1)).isNotEqualTo("private-value");
                            assertThat(encryption.getResult(result, 1)).isEqualTo("private-value");
                        }
                        return null;
                    });
                    jdbc.execute("CREATE TABLE mate_user (id VARCHAR(64), tenant_id VARCHAR(64), deleted INT)");
                    jdbc.execute("INSERT INTO mate_user VALUES ('u1', '2001', 0), ('u2', '2002', 0)");
                    UserDao users = context.getBean(UserDao.class);
                    TenantContext.runWithTenant("2001",
                            () -> assertThat(users.selectCount(null)).isEqualTo(1));
                    TenantContext.runWithTenant("2002",
                            () -> assertThat(users.selectCount(null)).isEqualTo(1));
                    org.assertj.core.api.Assertions.assertThatThrownBy(() -> users.selectCount(null))
                            .isInstanceOf(RuntimeException.class);
                });
    }
}
