/*
 * Copyright (c) 2024-2026 Beijing Daotiandi Technology Co., Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package vip.mate.starter.ds.config;

import com.alibaba.druid.pool.DruidDataSource;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Auto-configuration for DataSource and MyBatis Plus.
 *
 * <p>The official MyBatis-Plus Boot 4 starter owns SqlSessionFactory and
 * binds mybatis-plus properties. This configuration contributes the Druid
 * datasource, mapper scanning, interceptor chain and field-fill handler.</p>
 *
 * @author mateaix
 */
@Slf4j
// Order BEFORE Spring Boot's own DataSourceAutoConfiguration so our tuned Druid
// bean (below) wins the @ConditionalOnMissingBean(DataSource) race — otherwise
// Boot's DataSourceConfiguration.Generic builds a Druid from spring.datasource.type
// but ignores the spring.datasource.druid.* pool/monitor tuning (stat/wall/slf4j).
@AutoConfiguration(beforeName = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "com.baomidou.mybatisplus.autoconfigure.MybatisPlusInnerInterceptorAutoConfiguration",
        "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration"})
@ConditionalOnClass(SqlSessionFactory.class)
// One glob: "vip.mate.**.dao" already matches any package ending in `.dao` at any
// depth (incl. `…infrastructure.dao`), so a second `…infrastructure.dao` pattern
// only made MyBatis scan every mapper twice (harmless but ~20 "Skipping
// MapperFactoryBean … already defined" warnings on each boot).
//
// Fully-qualified bean names so mappers with the same simple name in different
// modules (e.g. auth + system both ship a LoginLogDao) don't collide when every
// module is scanned into one context (monolith mode). Mappers are injected by
// type, so FQN names are transparent in single-service mode.
@MapperScan(value = "vip.mate.**.dao",
        nameGenerator = FullyQualifiedAnnotationBeanNameGenerator.class)
public class DataSourceAutoConfiguration {

    /** The legacy single shared Flyway history table (pre per-service split). */
    private static final String LEGACY_FLYWAY_TABLE = "flyway_schema_history";

    /**
     * Create a single Druid DataSource bound to spring.datasource.* properties.
     * Uses the Druid core library without a second datasource auto-configuration.
     *
     * <p>Backs off when baomidou dynamic-datasource is enabled
     * ({@code spring.datasource.dynamic.enabled=true}), in which case
     * {@code DynamicRoutingDataSource} becomes THE datasource — this is how
     * tenant SCHEMA / DATASOURCE isolation modes plug in (RFC-012 Part 5).
     */
    @Bean
    @ConditionalOnMissingBean(DataSource.class)
    @ConditionalOnClass(DruidDataSource.class)
    @ConditionalOnProperty(
            prefix = "spring.datasource.dynamic", name = "enabled",
            havingValue = "false", matchIfMissing = true)
    @ConfigurationProperties(prefix = "spring.datasource.druid")
    public DruidDataSource dataSource(
            Environment env) {
        DruidDataSource ds = new DruidDataSource();
        ds.setUrl(env.getProperty("spring.datasource.url"));
        ds.setUsername(env.getProperty("spring.datasource.username"));
        ds.setPassword(env.getProperty("spring.datasource.password"));
        ds.setDriverClassName(env.getProperty("spring.datasource.driver-class-name",
                "com.mysql.cj.jdbc.Driver"));
        return ds;
    }

    /**
     * Assembles the single MyBatis-Plus interceptor chain.
     *
     * <p>InnerInterceptor ordering is significant: tenant-line and
     * data-permission interceptors must run BEFORE pagination so the rewritten
     * WHERE clause is reflected in both the page and the count SQL. Other
     * starters (tenant, security) contribute their {@link InnerInterceptor}
     * beans; they are collected here, ordered by {@code @Order}, and inserted
     * ahead of the framework's own optimistic-lock / block-attack / pagination
     * interceptors (pagination always last).
     */
    @Bean
    @ConditionalOnMissingBean
    public MybatisPlusInterceptor mybatisPlusInterceptor(
            ObjectProvider<InnerInterceptor> contributedInterceptors) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        List<InnerInterceptor> contributed = contributedInterceptors.orderedStream().toList();
        contributed.forEach(interceptor::addInnerInterceptor);

        // NOTE: optimistic-locking interceptor intentionally NOT registered.
        // Real OCC requires the load-time version to travel back on update, which
        // means the aggregate root must carry it. The domain layer is kept
        // framework-free (no version field), so the `lock_version` columns are
        // reserved-but-inert. To enable OCC later: add a version to the aggregate
        // root, map it through the repository convertor, re-add @Version on the
        // PO field, and register OptimisticLockerInnerInterceptor here.
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());

        PaginationInnerInterceptor paginationInterceptor = new PaginationInnerInterceptor(DbType.MYSQL);
        paginationInterceptor.setMaxLimit(500L);
        interceptor.addInnerInterceptor(paginationInterceptor);
        return interceptor;
    }

    @Bean
    @ConditionalOnMissingBean
    public MetaObjectHandler myMetaObjectHandler() {
        return new MyMetaObjectHandler();
    }

    /**
     * Repair-then-migrate Flyway strategy that makes the documented
     * {@code spring.flyway.repair-on-migrate} flag actually do something (plain
     * Spring Boot has no such property — it was previously silently ignored).
     *
     * <p>When present, Spring Boot calls THIS instead of the default
     * {@code flyway.migrate()}. {@link Flyway#repair()} first
     * (1) realigns checksums / descriptions of applied migrations to the resolved
     * scripts, (2) removes failed migration entries, and (3) marks applied-but-
     * missing migrations as deleted — so a multi-statement DDL that half-failed,
     * or a per-service history split (see docs/conventions/pluggable-module-guide.md
     * + scripts/flyway-split-history.sql), does not brick boot under
     * {@code validate-on-migrate=true}. Successful, matching versions are never
     * re-run.
     *
     * <p>Toggle off with {@code spring.flyway.repair-on-migrate=false} to get the
     * strict default (migrate only).
     */
    @Bean
    @ConditionalOnMissingBean(FlywayMigrationStrategy.class)
    @ConditionalOnClass(Flyway.class)
    @ConditionalOnProperty(
            prefix = "spring.flyway", name = "repair-on-migrate",
            havingValue = "true", matchIfMissing = true)
    public FlywayMigrationStrategy repairThenMigrate() {
        return flyway -> {
            autoSeedPerServiceHistory(flyway);
            flyway.repair();
            flyway.migrate();
        };
    }

    /**
     * One-time, automatic transition from the legacy single shared
     * {@code flyway_schema_history} to this service's per-service history table
     * (e.g. {@code flyway_history_system}). Runs on MySQL only (H2 dev is always fresh).
     *
     * <p>Without this, an existing DB upgraded to per-service history would find its
     * new table empty, baseline at version 0, and RE-RUN every migration from V1 —
     * which fails on the first non-idempotent {@code ALTER ... ADD COLUMN} (e.g.
     * "Duplicate column name"). Here we pre-seed the new table with exactly the rows
     * for THIS service's resolved scripts, copied from the legacy table, so already-
     * applied migrations are recognised and never re-run. Idempotent: once the new
     * table has rows, this is a no-op. Equivalent to scripts/flyway-split-history.sql
     * but built into the boot path so no manual step is needed.
     */
    private void autoSeedPerServiceHistory(Flyway flyway) {
        String table = flyway.getConfiguration().getTable();
        if (table == null || LEGACY_FLYWAY_TABLE.equalsIgnoreCase(table)) {
            return; // not using a per-service history table — nothing to transition
        }
        try (Connection conn = flyway.getConfiguration().getDataSource().getConnection()) {
            String product = conn.getMetaData().getDatabaseProductName();
            if (product == null || !product.toLowerCase().contains("mysql")) {
                return; // only persistent MySQL carries history to adopt; H2 is fresh
            }
            // This app's versioned migration scripts, in version order. Skip the
            // "<< Flyway Baseline >>" pseudo-entry — the target keeps its own baseline.
            List<String> scripts = new ArrayList<>();
            for (MigrationInfo mi : flyway.info().all()) {
                String script = mi.getScript();
                if (script != null && !script.isBlank() && !script.startsWith("<<")) {
                    scripts.add(script);
                }
            }
            if (scripts.isEmpty()) {
                return;
            }
            boolean targetExists = tableExists(conn, table);
            if (targetExists && appliedScriptCount(conn, table, scripts) >= scripts.size()) {
                return; // already fully seeded / migrated — no-op
            }
            // Pull already-applied rows for our scripts from every OTHER Flyway history
            // table in this schema: the legacy single `flyway_schema_history` AND sibling
            // per-service tables (flyway_history_auth/system/notice/...). This is what lets
            // the monolith adopt a database the microservices already migrated (and the
            // reverse), instead of re-running CREATE TABLE and colliding.
            List<String> sources = discoverHistoryTables(conn, table);
            Map<String, AppliedRow> applied = collectAppliedRows(conn, sources, scripts);
            if (applied.isEmpty()) {
                return; // fresh schema — let migrate() create everything
            }
            if (!targetExists) {
                try (Statement st = conn.createStatement()) {
                    st.execute("CREATE TABLE `" + table + "` LIKE `" + sources.get(0) + "`");
                }
            } else {
                // Drop half-failed rows so they neither fail validation nor collide with seeds.
                try (Statement st = conn.createStatement()) {
                    st.executeUpdate("DELETE FROM `" + table + "` WHERE success = 0");
                }
            }
            int rank = (int) maxInstalledRank(conn, table);
            int seeded = 0;
            String insert = "INSERT IGNORE INTO `" + table + "` (installed_rank, version, description, "
                    + "type, script, checksum, installed_by, installed_on, execution_time, success) "
                    + "VALUES (?,?,?,?,?,?,?,NOW(),?,1)";
            try (PreparedStatement ps = conn.prepareStatement(insert)) {
                for (String script : scripts) {   // version order
                    AppliedRow row = applied.get(script);
                    if (row == null) {
                        continue;
                    }
                    rank++;
                    ps.setInt(1, rank);
                    ps.setString(2, row.version);
                    ps.setString(3, row.description);
                    ps.setString(4, row.type);
                    ps.setString(5, row.script);
                    if (row.checksum == null) {
                        ps.setNull(6, Types.INTEGER);
                    } else {
                        ps.setInt(6, row.checksum);
                    }
                    ps.setString(7, row.installedBy);
                    ps.setInt(8, row.executionTime);
                    ps.addBatch();
                    seeded++;
                }
                ps.executeBatch();
            }
            log.info("[flyway] history adoption: seeded {} applied row(s) into `{}` from {} "
                    + "— schema already migrated by another deployment topology", seeded, table, sources);
        } catch (Exception e) {
            // Never block boot on the seed itself; repair+migrate still run. If the seed
            // could not complete, migrate may fail loudly (same as before this fix).
            log.warn("[flyway] history auto-seed skipped for `{}`: {}", table, e.toString());
        }
    }

    /** A successfully-applied migration row copied from another history table. */
    private record AppliedRow(String version, String description, String type, String script,
                              Integer checksum, String installedBy, int executionTime) {
    }

    /** Count of this app's scripts already recorded as successful in {@code table}. */
    private static int appliedScriptCount(Connection conn, String table, List<String> scripts)
            throws SQLException {
        String placeholders = String.join(",", Collections.nCopies(scripts.size(), "?"));
        String sql = "SELECT COUNT(*) FROM `" + table + "` WHERE success = 1 AND script IN ("
                + placeholders + ")";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < scripts.size(); i++) {
                ps.setString(i + 1, scripts.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** Legacy {@code flyway_schema_history} + sibling {@code flyway_history_*} tables (excluding target). */
    private static List<String> discoverHistoryTables(Connection conn, String target) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (ResultSet rs = conn.getMetaData()
                .getTables(conn.getCatalog(), null, "flyway%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                if (name == null || name.equalsIgnoreCase(target)) {
                    continue;
                }
                if (name.equalsIgnoreCase(LEGACY_FLYWAY_TABLE) || name.startsWith("flyway_history_")) {
                    tables.add(name);
                }
            }
        }
        return tables;
    }

    /** Map of script -> applied row, gathered from the source tables (first hit wins). */
    private static Map<String, AppliedRow> collectAppliedRows(
            Connection conn, List<String> sources, List<String> scripts) throws SQLException {
        Map<String, AppliedRow> applied = new LinkedHashMap<>();
        if (sources.isEmpty()) {
            return applied;
        }
        String placeholders = String.join(",", Collections.nCopies(scripts.size(), "?"));
        for (String src : sources) {
            String sql = "SELECT version, description, type, script, checksum, installed_by, "
                    + "execution_time FROM `" + src + "` WHERE success = 1 AND script IN ("
                    + placeholders + ")";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < scripts.size(); i++) {
                    ps.setString(i + 1, scripts.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String script = rs.getString("script");
                        int checksum = rs.getInt("checksum");
                        applied.putIfAbsent(script, new AppliedRow(
                                rs.getString("version"),
                                rs.getString("description"),
                                rs.getString("type"),
                                script,
                                rs.wasNull() ? null : checksum,
                                rs.getString("installed_by"),
                                rs.getInt("execution_time")));
                    }
                }
            }
        }
        return applied;
    }

    private static long maxInstalledRank(Connection conn, String table) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(installed_rank),0) FROM `" + table + "`")) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private static boolean tableExists(Connection conn, String name) throws SQLException {
        try (ResultSet rs = conn.getMetaData()
                .getTables(conn.getCatalog(), null, name, new String[]{"TABLE"})) {
            return rs.next();
        }
    }

}
