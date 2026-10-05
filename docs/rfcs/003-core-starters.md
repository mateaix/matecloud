# RFC-003: Core Starters (ds, web, nacos, rpc)

- **Status**: Draft
- **Created**: 2026-04-11
- **Author**: MateCloud Team

## 背景

`mate-starters` 目录下的 starter 模块是自动配置模块，每个 starter 都通过 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 注册自动配置类。本 RFC 覆盖 4 个核心 starter：

1. **mate-ds-starter** - 数据源 + MyBatis Plus 自动配置
2. **mate-web-starter** - Web 层自动配置（异常处理、Jackson）
3. **mate-nacos-starter** - Nacos 注册发现 + 配置中心
4. **mate-rpc-starter** - Dubbo RPC 自动配置

## 设计方案

---

## Starter 1: mate-ds-starter

2026-10-05 升级状态：MyBatis-Plus 使用官方 Boot 4 starter 3.5.17，
动态数据源使用 Boot 4 starter 4.5.0。SqlSessionFactory、SqlSessionTemplate 与
mybatis-plus 属性绑定由官方自动配置负责；MateCloud 保留全限定 Mapper 名称、
拦截器顺序、字段填充和 Flyway 策略。Druid 使用核心库并由 MateCloud 创建和绑定数据源。
全局 type-aliases-package 已取消，避免单体模式同名 PO 冲突。
EncryptTypeHandler 仅按处理器类注册，继续用于显式字段映射，不接管普通 String。
H2 回归覆盖单/动态数据源、CRUD、分页、逻辑删除、配置覆盖，以及单体 Mapper
扫描、多租户拦截器顺序和字段加密。外部 MySQL / Nacos / Redis 联调单独执行。

### 1.1 pom.xml

Create `D:\codes\matecloud\mate-starters\mate-ds-starter\pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>vip.mate</groupId>
        <artifactId>mate-starters</artifactId>
        <version>1.0.0</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <artifactId>mate-ds-starter</artifactId>
    <packaging>jar</packaging>
    <name>mate-ds-starter</name>
    <description>DataSource and MyBatis Plus auto-configuration starter</description>

    <dependencies>
        <!-- mate-base (for BaseEntity) -->
        <dependency>
            <groupId>vip.mate</groupId>
            <artifactId>mate-base</artifactId>
        </dependency>

        <!-- Spring Boot Starter JDBC -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jdbc</artifactId>
        </dependency>

        <!-- MyBatis Plus -->
        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>mybatis-plus-spring-boot4-starter</artifactId>
        </dependency>

        <!-- MySQL Driver -->
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <scope>runtime</scope>
        </dependency>

        <!-- Druid Connection Pool -->
        <dependency>
            <groupId>com.alibaba</groupId>
            <artifactId>druid</artifactId>
        </dependency>

        <!-- Dynamic DataSource (optional, for multi-datasource) -->
        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>dynamic-datasource-spring-boot4-starter</artifactId>
            <optional>true</optional>
        </dependency>

        <!-- P6Spy for SQL logging (optional) -->
        <dependency>
            <groupId>p6spy</groupId>
            <artifactId>p6spy</artifactId>
            <optional>true</optional>
        </dependency>

        <!-- Lombok -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <scope>provided</scope>
        </dependency>

        <!-- Spring Boot AutoConfiguration Processor -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-autoconfigure-processor</artifactId>
            <optional>true</optional>
        </dependency>
    </dependencies>

</project>
```

### 1.2 DataSourceAutoConfiguration.java

Create `D:\codes\matecloud\mate-starters\mate-ds-starter\src\main\java\vip\mate\starter\ds\config\DataSourceAutoConfiguration.java`

```java
package vip.mate.starter.ds.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for DataSource and MyBatis Plus.
 *
 * <p>Provides:
 * <ul>
 *   <li>MapperScan for all infrastructure.dao packages</li>
 *   <li>Pagination interceptor (MySQL)</li>
 *   <li>Optimistic locking interceptor</li>
 *   <li>Block attack interceptor (prevents full-table update/delete)</li>
 *   <li>Auto-fill handler for createdAt / updatedAt</li>
 * </ul>
 */
@AutoConfiguration
@MapperScan("vip.mate.*.infrastructure.dao")
public class DataSourceAutoConfiguration {

    /**
     * MyBatis Plus interceptor chain.
     */
    @Bean
    @ConditionalOnMissingBean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // Pagination - must be added first
        PaginationInnerInterceptor paginationInterceptor = new PaginationInnerInterceptor(DbType.MYSQL);
        paginationInterceptor.setMaxLimit(500L);
        interceptor.addInnerInterceptor(paginationInterceptor);

        // Optimistic locking
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());

        // Block full-table update/delete
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());

        return interceptor;
    }

    /**
     * Auto-fill handler for createdAt and updatedAt fields.
     */
    @Bean
    @ConditionalOnMissingBean
    public MetaObjectHandler myMetaObjectHandler() {
        return new MyMetaObjectHandler();
    }
}
```

### 1.3 MyMetaObjectHandler.java

Create `D:\codes\matecloud\mate-starters\mate-ds-starter\src\main\java\vip\mate\starter\ds\config\MyMetaObjectHandler.java`

```java
package vip.mate.starter.ds.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.reflection.MetaObject;

import java.util.Date;

/**
 * MyBatis Plus auto-fill handler.
 * Automatically fills createdAt and updatedAt fields on insert/update.
 */
@Slf4j
public class MyMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        Date now = new Date();
        this.strictInsertFill(metaObject, "createdAt", Date.class, now);
        this.strictInsertFill(metaObject, "updatedAt", Date.class, now);
        this.strictInsertFill(metaObject, "deleted", Integer.class, 0);
        this.strictInsertFill(metaObject, "lockVersion", Integer.class, 0);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updatedAt", Date.class, new Date());
    }
}
```

### 1.4 BasePO.java

Create `D:\codes\matecloud\mate-starters\mate-ds-starter\src\main\java\vip\mate\starter\ds\model\BasePO.java`

```java
package vip.mate.starter.ds.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * Base persistent object for infrastructure layer.
 * Similar to BaseEntity in mate-base but lives in the ds-starter
 * for infrastructure layer usage. Business modules should use this
 * as the base class for their MyBatis Plus entity (PO) classes.
 *
 * <p>Difference from BaseEntity: BasePO is used in the infrastructure/dao layer
 * and may include DB-specific annotations. BaseEntity is in mate-base for
 * domain layer usage.</p>
 */
@Data
@SuperBuilder
@NoArgsConstructor
public abstract class BasePO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    @TableField(fill = FieldFill.INSERT)
    private Date createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updatedAt;

    @TableLogic
    private Integer deleted;

    @Version
    private Integer lockVersion;
}
```

### 1.5 AutoConfiguration.imports

Create `D:\codes\matecloud\mate-starters\mate-ds-starter\src\main\resources\META-INF\spring\org.springframework.boot.autoconfigure.AutoConfiguration.imports`

```text
vip.mate.starter.ds.config.DataSourceAutoConfiguration
```

---

## Starter 2: mate-web-starter

### 2.1 pom.xml

Create `D:\codes\matecloud\mate-starters\mate-web-starter\pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>vip.mate</groupId>
        <artifactId>mate-starters</artifactId>
        <version>1.0.0</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <artifactId>mate-web-starter</artifactId>
    <packaging>jar</packaging>
    <name>mate-web-starter</name>
    <description>Web layer auto-configuration starter (exception handler, Jackson, etc.)</description>

    <dependencies>
        <!-- mate-base (for Result, BizException, ResponseCode) -->
        <dependency>
            <groupId>vip.mate</groupId>
            <artifactId>mate-base</artifactId>
        </dependency>

        <!-- Spring Boot Web -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>

        <!-- Spring Boot Validation -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>

        <!-- Jackson JSR310 (Java 8 Date/Time) -->
        <dependency>
            <groupId>com.fasterxml.jackson.datatype</groupId>
            <artifactId>jackson-datatype-jsr310</artifactId>
        </dependency>

        <!-- Lombok -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <scope>provided</scope>
        </dependency>

        <!-- Spring Boot AutoConfiguration Processor -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-autoconfigure-processor</artifactId>
            <optional>true</optional>
        </dependency>
    </dependencies>

</project>
```

### 2.2 WebAutoConfiguration.java

Create `D:\codes\matecloud\mate-starters\mate-web-starter\src\main\java\vip\mate\starter\web\config\WebAutoConfiguration.java`

```java
package vip.mate.starter.web.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Import;

/**
 * Auto-configuration for web layer.
 * Imports GlobalExceptionHandler and JacksonConfiguration.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Import({GlobalExceptionHandler.class, JacksonConfiguration.class})
public class WebAutoConfiguration {
}
```

### 2.3 GlobalExceptionHandler.java

Create `D:\codes\matecloud\mate-starters\mate-web-starter\src\main\java\vip\mate\starter\web\config\GlobalExceptionHandler.java`

```java
package vip.mate.starter.web.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import vip.mate.base.exception.BizException;
import vip.mate.base.response.ResponseCode;
import vip.mate.base.result.Result;

import java.util.stream.Collectors;

/**
 * Global exception handler for all REST controllers.
 * Catches exceptions and converts them to unified Result responses.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handle BizException - business logic errors.
     */
    @ExceptionHandler(BizException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleBizException(BizException e, HttpServletRequest request) {
        log.warn("BizException at {}: [{}] {}", request.getRequestURI(), e.getCode(), e.getMsg());
        return Result.fail(e.getCode(), e.getMsg());
    }

    /**
     * Handle @Valid / @Validated on @RequestBody.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("Validation failed: {}", message);
        return Result.fail(ResponseCode.PARAM_VALID_ERROR.getCode(), message);
    }

    /**
     * Handle @Valid on @ModelAttribute / @RequestParam.
     */
    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleBindException(BindException e) {
        String message = e.getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("Bind failed: {}", message);
        return Result.fail(ResponseCode.PARAM_VALID_ERROR.getCode(), message);
    }

    /**
     * Handle @Validated on method parameters (e.g., @RequestParam @Min).
     */
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleConstraintViolation(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("; "));
        log.warn("Constraint violation: {}", message);
        return Result.fail(ResponseCode.PARAM_VALID_ERROR.getCode(), message);
    }

    /**
     * Handle missing request parameters.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("Missing parameter: {}", e.getParameterName());
        return Result.fail(ResponseCode.PARAM_VALID_ERROR.getCode(),
                "Missing required parameter: " + e.getParameterName());
    }

    /**
     * Handle HTTP method not supported.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public Result<Void> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        log.warn("Method not allowed: {}", e.getMethod());
        return Result.fail(ResponseCode.METHOD_NOT_ALLOWED);
    }

    /**
     * Handle resource not found (Spring 6.1+).
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNoResourceFound(NoResourceFoundException e) {
        log.warn("Resource not found: {}", e.getResourcePath());
        return Result.fail(ResponseCode.NOT_FOUND);
    }

    /**
     * Handle all other uncaught exceptions.
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception e, HttpServletRequest request) {
        log.error("Unexpected error at {}: {}", request.getRequestURI(), e.getMessage(), e);
        return Result.fail(ResponseCode.INTERNAL_ERROR);
    }
}
```

### 2.4 JacksonConfiguration.java

Create `D:\codes\matecloud\mate-starters\mate-web-starter\src\main\java\vip\mate\starter\web\config\JacksonConfiguration.java`

```java
package vip.mate.starter.web.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalTimeSerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.TimeZone;

/**
 * Jackson ObjectMapper configuration with sensible defaults.
 *
 * <ul>
 *   <li>Date format: yyyy-MM-dd HH:mm:ss</li>
 *   <li>Timezone: GMT+8</li>
 *   <li>Java 8 date/time support via JavaTimeModule</li>
 *   <li>Unknown properties ignored on deserialization</li>
 *   <li>Empty beans do not fail</li>
 * </ul>
 */
@Configuration
public class JacksonConfiguration {

    private static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";
    private static final String DATE_PATTERN = "yyyy-MM-dd";
    private static final String TIME_PATTERN = "HH:mm:ss";

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();

        // Date format
        objectMapper.setDateFormat(new SimpleDateFormat(DATE_TIME_PATTERN));
        objectMapper.setTimeZone(TimeZone.getTimeZone("GMT+8"));

        // Java 8 Date/Time module
        JavaTimeModule javaTimeModule = new JavaTimeModule();
        javaTimeModule.addSerializer(LocalDateTime.class,
                new LocalDateTimeSerializer(DateTimeFormatter.ofPattern(DATE_TIME_PATTERN)));
        javaTimeModule.addSerializer(LocalDate.class,
                new LocalDateSerializer(DateTimeFormatter.ofPattern(DATE_PATTERN)));
        javaTimeModule.addSerializer(LocalTime.class,
                new LocalTimeSerializer(DateTimeFormatter.ofPattern(TIME_PATTERN)));
        javaTimeModule.addDeserializer(LocalDateTime.class,
                new LocalDateTimeDeserializer(DateTimeFormatter.ofPattern(DATE_TIME_PATTERN)));
        javaTimeModule.addDeserializer(LocalDate.class,
                new LocalDateDeserializer(DateTimeFormatter.ofPattern(DATE_PATTERN)));
        javaTimeModule.addDeserializer(LocalTime.class,
                new LocalTimeDeserializer(DateTimeFormatter.ofPattern(TIME_PATTERN)));
        objectMapper.registerModule(javaTimeModule);

        // Deserialization settings
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        // Serialization settings
        objectMapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

        return objectMapper;
    }
}
```

### 2.5 AutoConfiguration.imports

Create `D:\codes\matecloud\mate-starters\mate-web-starter\src\main\resources\META-INF\spring\org.springframework.boot.autoconfigure.AutoConfiguration.imports`

```text
vip.mate.starter.web.config.WebAutoConfiguration
```

---

## Starter 3: mate-nacos-starter

### 3.1 pom.xml

Create `D:\codes\matecloud\mate-starters\mate-nacos-starter\pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>vip.mate</groupId>
        <artifactId>mate-starters</artifactId>
        <version>1.0.0</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <artifactId>mate-nacos-starter</artifactId>
    <packaging>jar</packaging>
    <name>mate-nacos-starter</name>
    <description>Nacos service discovery and configuration center starter</description>

    <dependencies>
        <!-- Spring Cloud Alibaba Nacos Discovery -->
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
        </dependency>

        <!-- Spring Cloud Alibaba Nacos Config -->
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
        </dependency>

        <!-- Spring Cloud Bootstrap (required for bootstrap.yml loading) -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-bootstrap</artifactId>
        </dependency>

        <!-- Spring Boot Actuator (for health checks with Nacos) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
            <optional>true</optional>
        </dependency>

        <!-- Spring Cloud LoadBalancer (required for service discovery) -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-loadbalancer</artifactId>
        </dependency>

        <!-- Spring Boot AutoConfiguration Processor -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-autoconfigure-processor</artifactId>
            <optional>true</optional>
        </dependency>
    </dependencies>

</project>
```

### 3.2 NacosAutoConfiguration.java

Create `D:\codes\matecloud\mate-starters\mate-nacos-starter\src\main\java\vip\mate\starter\nacos\config\NacosAutoConfiguration.java`

```java
package vip.mate.starter.nacos.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;

/**
 * Auto-configuration for Nacos integration.
 *
 * <p>This starter primarily provides:
 * <ul>
 *   <li>Dependencies for Nacos discovery + config</li>
 *   <li>Bootstrap context support for Nacos config loading</li>
 *   <li>Default nacos.yml resource template</li>
 *   <li>Spring Cloud LoadBalancer for service discovery</li>
 * </ul>
 *
 * <p>Most configuration is handled by Spring Cloud Alibaba's own auto-configuration.
 * This class serves as the entry point and can be extended with custom Nacos behavior.</p>
 */
@AutoConfiguration
@ConditionalOnClass(name = "com.alibaba.cloud.nacos.NacosDiscoveryProperties")
public class NacosAutoConfiguration {

    // Spring Cloud Alibaba's own auto-configuration handles Nacos setup.
    // This class is a placeholder for future custom Nacos configuration
    // such as custom NamingService or ConfigService customization.
}
```

### 3.3 nacos.yml (Resource Template)

Create `D:\codes\matecloud\mate-starters\mate-nacos-starter\src\main\resources\nacos.yml`

This is a reference/template file. Services should include this in their own `bootstrap.yml`.

```yaml
# ==============================================================
# Nacos Configuration Template
# Copy relevant sections to your service's bootstrap.yml
# ==============================================================

spring:
  cloud:
    nacos:
      # Nacos Server address
      server-addr: ${NACOS_SERVER_ADDR:127.0.0.1:8848}
      # Nacos namespace (leave empty for public)
      namespace: ${NACOS_NAMESPACE:}
      # Nacos username
      username: ${NACOS_USERNAME:nacos}
      # Nacos password
      password: ${NACOS_PASSWORD:nacos}

      # Service Discovery
      discovery:
        enabled: true
        # Service group
        group: ${NACOS_GROUP:DEFAULT_GROUP}
        # Ephemeral instance (true for Spring Boot services)
        ephemeral: true
        # Metadata
        metadata:
          version: ${matecloud.version:1.0.0}
          env: ${spring.profiles.active:dev}

      # Configuration Center
      config:
        enabled: true
        # Config file type
        file-extension: yaml
        # Config group
        group: ${NACOS_GROUP:DEFAULT_GROUP}
        # Shared configs (loaded by all services)
        shared-configs:
          - data-id: mate-common.yaml
            group: ${NACOS_GROUP:DEFAULT_GROUP}
            refresh: true
        # Refresh enabled
        refresh-enabled: true
```

### 3.4 AutoConfiguration.imports

Create `D:\codes\matecloud\mate-starters\mate-nacos-starter\src\main\resources\META-INF\spring\org.springframework.boot.autoconfigure.AutoConfiguration.imports`

```text
vip.mate.starter.nacos.config.NacosAutoConfiguration
```

---

## Starter 4: mate-rpc-starter

### 4.1 pom.xml

Create `D:\codes\matecloud\mate-starters\mate-rpc-starter\pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>vip.mate</groupId>
        <artifactId>mate-starters</artifactId>
        <version>1.0.0</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <artifactId>mate-rpc-starter</artifactId>
    <packaging>jar</packaging>
    <name>mate-rpc-starter</name>
    <description>Dubbo RPC auto-configuration starter</description>

    <dependencies>
        <!-- mate-api (RPC interfaces) -->
        <dependency>
            <groupId>vip.mate</groupId>
            <artifactId>mate-api</artifactId>
        </dependency>

        <!-- Dubbo Spring Boot Starter -->
        <dependency>
            <groupId>org.apache.dubbo</groupId>
            <artifactId>dubbo-spring-boot-starter</artifactId>
        </dependency>

        <!-- Dubbo Registry Nacos -->
        <dependency>
            <groupId>org.apache.dubbo</groupId>
            <artifactId>dubbo-registry-nacos</artifactId>
        </dependency>

        <!-- Dubbo Serialization (fastjson2) -->
        <dependency>
            <groupId>com.alibaba.fastjson2</groupId>
            <artifactId>fastjson2</artifactId>
        </dependency>

        <!-- Lombok -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <scope>provided</scope>
        </dependency>

        <!-- Spring Boot AutoConfiguration Processor -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-autoconfigure-processor</artifactId>
            <optional>true</optional>
        </dependency>
    </dependencies>

</project>
```

### 4.2 RpcAutoConfiguration.java

Create `D:\codes\matecloud\mate-starters\mate-rpc-starter\src\main\java\vip\mate\starter\rpc\config\RpcAutoConfiguration.java`

```java
package vip.mate.starter.rpc.config;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;

/**
 * Auto-configuration for Dubbo RPC.
 *
 * <p>Enables Dubbo scanning and provides default configuration.
 * Dubbo services will be registered with Nacos as the registry.
 *
 * <p>Default Dubbo properties should be set in application.yml:
 * <pre>
 * dubbo:
 *   application:
 *     name: ${spring.application.name}
 *     qos-enable: false
 *   protocol:
 *     name: dubbo
 *     port: -1          # auto-increment from 20880
 *     serialization: fastjson2
 *   registry:
 *     address: nacos://${spring.cloud.nacos.server-addr:127.0.0.1:8848}
 *     parameters:
 *       namespace: ${spring.cloud.nacos.namespace:}
 *   consumer:
 *     check: false       # don't fail on startup if provider not ready
 *     timeout: 5000
 *     retries: 2
 *   provider:
 *     timeout: 5000
 *     retries: 0         # providers default no retry (non-idempotent)
 *   scan:
 *     base-packages: vip.mate
 * </pre>
 */
@AutoConfiguration
@EnableDubbo
@ConditionalOnClass(name = "org.apache.dubbo.config.spring.context.annotation.EnableDubbo")
public class RpcAutoConfiguration {

    // Dubbo's own auto-configuration handles most of the setup.
    // @EnableDubbo triggers component scanning for @DubboService and @DubboReference.
    // Additional beans can be added here for custom Dubbo behavior, such as:
    // - Custom filters
    // - Custom serialization
    // - Custom load balancing strategies
}
```

### 4.3 DubboExceptionFilter.java

Create `D:\codes\matecloud\mate-starters\mate-rpc-starter\src\main\java\vip\mate\starter\rpc\filter\DubboExceptionFilter.java`

```java
package vip.mate.starter.rpc.filter;

import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import vip.mate.base.exception.BizException;

/**
 * Dubbo provider-side exception filter.
 * Catches BizException and logs it with appropriate level.
 * Lets BizException pass through to the consumer so it can be handled there.
 * Wraps unexpected exceptions in RpcException with a generic message.
 */
@Slf4j
@Activate(group = CommonConstants.PROVIDER)
public class DubboExceptionFilter implements Filter {

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        try {
            Result result = invoker.invoke(invocation);
            if (result.hasException()) {
                Throwable exception = result.getException();
                if (exception instanceof BizException bizEx) {
                    log.warn("Dubbo BizException in {}.{}: [{}] {}",
                            invoker.getInterface().getSimpleName(),
                            invocation.getMethodName(),
                            bizEx.getCode(),
                            bizEx.getMsg());
                    // Let BizException pass through
                } else {
                    log.error("Dubbo unexpected exception in {}.{}: {}",
                            invoker.getInterface().getSimpleName(),
                            invocation.getMethodName(),
                            exception.getMessage(),
                            exception);
                }
            }
            return result;
        } catch (RpcException e) {
            log.error("Dubbo RpcException in {}.{}: {}",
                    invoker.getInterface().getSimpleName(),
                    invocation.getMethodName(),
                    e.getMessage(),
                    e);
            throw e;
        }
    }
}
```

### 4.4 Dubbo SPI Extension File

Create `D:\codes\matecloud\mate-starters\mate-rpc-starter\src\main\resources\META-INF\dubbo\org.apache.dubbo.rpc.Filter`

```text
mateExceptionFilter=vip.mate.starter.rpc.filter.DubboExceptionFilter
```

### 4.5 AutoConfiguration.imports

Create `D:\codes\matecloud\mate-starters\mate-rpc-starter\src\main\resources\META-INF\spring\org.springframework.boot.autoconfigure.AutoConfiguration.imports`

```text
vip.mate.starter.rpc.config.RpcAutoConfiguration
```

---

## 涉及文件

| 文件 | 类型 | 说明 |
|------|------|------|
| **mate-ds-starter** | | |
| `mate-starters/mate-ds-starter/pom.xml` | New | DS starter POM |
| `.../ds/config/DataSourceAutoConfiguration.java` | New | MyBatis Plus 自动配置 |
| `.../ds/config/MyMetaObjectHandler.java` | New | 自动填充处理器 |
| `.../ds/model/BasePO.java` | New | 基础持久化对象 |
| `.../META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | New | Spring Boot 3 自动配置注册 |
| **mate-web-starter** | | |
| `mate-starters/mate-web-starter/pom.xml` | New | Web starter POM |
| `.../web/config/WebAutoConfiguration.java` | New | Web 自动配置入口 |
| `.../web/config/GlobalExceptionHandler.java` | New | 全局异常处理器 |
| `.../web/config/JacksonConfiguration.java` | New | Jackson 序列化配置 |
| `.../META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | New | 自动配置注册 |
| **mate-nacos-starter** | | |
| `mate-starters/mate-nacos-starter/pom.xml` | New | Nacos starter POM |
| `.../nacos/config/NacosAutoConfiguration.java` | New | Nacos 自动配置 |
| `.../resources/nacos.yml` | New | Nacos 配置模板 |
| `.../META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | New | 自动配置注册 |
| **mate-rpc-starter** | | |
| `mate-starters/mate-rpc-starter/pom.xml` | New | RPC starter POM |
| `.../rpc/config/RpcAutoConfiguration.java` | New | Dubbo 自动配置 + @EnableDubbo |
| `.../rpc/filter/DubboExceptionFilter.java` | New | Dubbo 异常过滤器 |
| `.../META-INF/dubbo/org.apache.dubbo.rpc.Filter` | New | Dubbo SPI 扩展注册 |
| `.../META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | New | 自动配置注册 |

## 验证方案

1. `cd mate-starters && mvn compile` 所有 4 个 starter 编译通过
2. 每个 starter 的 `AutoConfiguration.imports` 文件存在且内容正确
3. `mate-ds-starter`:
   - `@MapperScan` 扫描路径符合 DDD 约定 (`vip.mate.*.infrastructure.dao`)
   - MybatisPlusInterceptor 包含分页、乐观锁、防全表操作三个拦截器
   - MyMetaObjectHandler 自动填充 createdAt/updatedAt/deleted/lockVersion
4. `mate-web-starter`:
   - GlobalExceptionHandler 覆盖 BizException、参数校验异常、404、405、500
   - JacksonConfiguration 支持 Java 8 时间类型，时区 GMT+8
5. `mate-nacos-starter`:
   - 依赖包含 nacos-discovery + nacos-config + bootstrap + loadbalancer
   - nacos.yml 模板支持环境变量覆盖
6. `mate-rpc-starter`:
   - `@EnableDubbo` 注解启用 Dubbo 扫描
   - DubboExceptionFilter 通过 SPI 注册
   - 依赖包含 dubbo-spring-boot-starter + dubbo-registry-nacos

## 注意事项

- 所有 starter 使用 Spring Boot 3.x 的 `AutoConfiguration.imports` 机制（非 spring.factories）
- `@AutoConfiguration` 注解替代 `@Configuration` + `spring.factories` 组合
- `@ConditionalOnMissingBean` 确保用户自定义 Bean 优先
- `@ConditionalOnClass` 确保仅在相关依赖存在时才激活自动配置
- mate-nacos-starter 的 nacos.yml 是模板文件，实际使用时服务应在自己的 bootstrap.yml 中配置
- Dubbo SPI 文件路径必须是 `META-INF/dubbo/org.apache.dubbo.rpc.Filter`（不是 services）
