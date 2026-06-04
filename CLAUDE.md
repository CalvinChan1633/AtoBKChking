# AtoBKChkng REST Service

## 项目概述

基于 Spring Boot 3 的 REST API 后端服务。

- **构建工具**: Maven
- **Java 版本**: 21
- **Spring Boot 版本**: 3.3.5
- **包名**: `com.atobkchkn.rest`

## 快速开始

```bash
# 编译并运行
./mvnw spring-boot:run

# 或编译打包
./mvnw clean package
java -jar target/atobkchkn-rest-*.jar
```

## 项目结构

```
.
├── pom.xml                          # Maven 构建配置
├── src/
│   ├── main/
│   │   ├── java/com/atobkchkn/rest/
│   │   │   ├── AtoBKChkngApplication.java    # 启动类
│   │   │   └── HelloController.java          # 示例控制器
│   │   └── resources/
│   │       └── application.properties        # 应用配置
│   └── test/
│       └── java/com/atobkchkn/rest/
└── CLAUDE.md                        # 本文档
```

## 技术栈

| 组件 | 说明 |
|------|------|
| Spring Boot Starter Web | REST API 支持 |
| Spring Boot Starter Validation | 参数校验 |
| Spring Boot Starter Actuator | 健康检查与监控 |
| Spring Boot Starter Test | 测试框架 |

## API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/hello` | 示例接口 |
| GET | `/api/v1/health` | 健康检查 |
| GET | `/actuator/health` | Actuator 健康 |
| GET | `/actuator/info` | 应用信息 |

## 常用命令

```bash
# 开发模式运行
mvn spring-boot:run

# 运行测试
mvn test

# 打包
mvn clean package

# 跳过测试打包
mvn clean package -DskipTests
```

## 待办

- [ ] 添加数据库支持 (JPA / MyBatis)
- [ ] 添加安全认证 (Spring Security / JWT)
- [ ] 添加 API 文档 (SpringDoc OpenAPI)
- [ ] 添加全局异常处理
- [ ] 添加日志配置 (Logback)
