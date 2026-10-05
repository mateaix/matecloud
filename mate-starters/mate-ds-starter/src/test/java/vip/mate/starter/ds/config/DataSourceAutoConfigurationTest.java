package vip.mate.starter.ds.config;

import com.alibaba.druid.pool.DruidDataSource;
import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.dynamic.datasource.spring.boot.autoconfigure.DynamicDataSourceAutoConfiguration;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusInnerInterceptorAutoConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import vip.mate.starter.ds.fixture.dao.UpgradeDao;
import vip.mate.starter.ds.fixture.dao.UpgradeRow;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataSourceAutoConfigurationTest {
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withInitializer(context -> {
                    try {
                        new YamlPropertySourceLoader().load("mate-defaults",
                                new ClassPathResource("mate-defaults.yml"))
                                .forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                        org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class,
                        MybatisPlusAutoConfiguration.class, MybatisPlusInnerInterceptorAutoConfiguration.class,
                        DynamicDataSourceAutoConfiguration.class))
                .withPropertyValues("spring.datasource.dynamic.enabled=false",
                        "spring.datasource.url=jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "spring.datasource.driver-class-name=org.h2.Driver",
                        "spring.datasource.username=sa", "spring.datasource.password=",
                        "spring.datasource.druid.initial-size=0");
    }

    @Test
    void officialBoot4FactoryBindsDefaultsAndRunsCrud() {
        runner().run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(SqlSessionFactory.class);
            assertThat(context.getBean(DataSource.class)).isInstanceOf(DruidDataSource.class);
            SqlSessionFactory factory = context.getBean(SqlSessionFactory.class);
            assertThat(factory.getConfiguration().isMapUnderscoreToCamelCase()).isTrue();
            assertThat(factory.getConfiguration().isCacheEnabled()).isFalse();
            assertThat(factory.getConfiguration().getInterceptors())
                    .containsExactly(context.getBean(MybatisPlusInterceptor.class));
            assertThat(context.getBean(MybatisPlusInterceptor.class).getInterceptors())
                    .hasSize(2).first().isInstanceOf(BlockAttackInnerInterceptor.class);
            assertThat(context.getBean(MybatisPlusInterceptor.class).getInterceptors().getLast())
                    .isInstanceOf(PaginationInnerInterceptor.class);
            assertThat(context).hasBean(UpgradeDao.class.getName());
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            jdbc.execute("CREATE TABLE mate_upgrade_test (id VARCHAR(64) PRIMARY KEY, name VARCHAR(100),"
                    + " created_at TIMESTAMP, updated_at TIMESTAMP, deleted INT)");
            UpgradeDao dao = context.getBean(UpgradeDao.class);
            UpgradeRow row = new UpgradeRow();
            row.setName("first");
            assertThat(dao.insert(row)).isEqualTo(1);
            assertThat(row.getId()).isNotBlank();
            assertThat(row.getCreatedAt()).isNotNull();
            assertThat(row.getDeleted()).isZero();
            assertThat(dao.selectOne(new LambdaQueryWrapper<UpgradeRow>().eq(UpgradeRow::getName, "first")))
                    .extracting(UpgradeRow::getId).isEqualTo(row.getId());
            row.setName("updated");
            assertThat(dao.updateById(row)).isEqualTo(1);
            assertThat(dao.selectPage(new Page<>(1, 10), null).getTotal()).isEqualTo(1);
            assertThatThrownBy(() -> dao.update(new UpdateWrapper<UpgradeRow>().set("name", "unsafe")))
                    .isInstanceOf(RuntimeException.class);
            assertThat(dao.deleteById(row.getId())).isEqualTo(1);
            assertThat(dao.selectById(row.getId())).isNull();
            assertThat(jdbc.queryForObject("SELECT deleted FROM mate_upgrade_test WHERE id=?",
                    Integer.class, row.getId())).isEqualTo(1);
        });
    }

    @Test
    void propertyOverridesReachOfficialFactory() {
        runner().withPropertyValues("mybatis-plus.configuration.cache-enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SqlSessionFactory.class).getConfiguration().isCacheEnabled()).isTrue();
        });
    }

    @Test
    void dynamicDatasourceOwnsFactoryWhenEnabled() {
        runner().withPropertyValues("spring.datasource.dynamic.enabled=true",
                "spring.datasource.dynamic.strict=true",
                "spring.datasource.dynamic.datasource.master.url=jdbc:h2:mem:dynamic_upgrade;MODE=MySQL",
                "spring.datasource.dynamic.datasource.master.username=sa",
                "spring.datasource.dynamic.datasource.master.driver-class-name=org.h2.Driver",
                "spring.datasource.dynamic.datasource.master.type=com.alibaba.druid.pool.DruidDataSource")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(DataSource.class)
                            .hasSingleBean(SqlSessionFactory.class);
                    assertThat(context.getBean(DataSource.class)).isInstanceOf(DynamicRoutingDataSource.class);
                    assertThat(new JdbcTemplate(context.getBean(DataSource.class))
                            .queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
                });
    }
}
