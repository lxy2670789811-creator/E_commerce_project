# 多阶段构建：Maven编译 -> JRE运行
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /app

COPY pom.xml .

# 配置阿里云 Maven 镜像源
RUN mkdir -p /root/.m2 && \
    echo '<?xml version="1.0" encoding="UTF-8"?>' > /root/.m2/settings.xml && \
    echo '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"' >> /root/.m2/settings.xml && \
    echo '          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"' >> /root/.m2/settings.xml && \
    echo '          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0 http://maven.apache.org/xsd/settings-1.0.0.xsd">' >> /root/.m2/settings.xml && \
    echo '  <mirrors>' >> /root/.m2/settings.xml && \
    echo '    <mirror>' >> /root/.m2/settings.xml && \
    echo '      <id>aliyun</id>' >> /root/.m2/settings.xml && \
    echo '      <name>Aliyun Maven Mirror</name>' >> /root/.m2/settings.xml && \
    echo '      <url>https://maven.aliyun.com/repository/public</url>' >> /root/.m2/settings.xml && \
    echo '      <mirrorOf>central</mirrorOf>' >> /root/.m2/settings.xml && \
    echo '    </mirror>' >> /root/.m2/settings.xml && \
    echo '  </mirrors>' >> /root/.m2/settings.xml && \
    echo '</settings>' >> /root/.m2/settings.xml

# 去掉 -q（安静模式），改成显示下载进度（方便观察是否卡住）
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app

# 用通配代替写死版本号：原来写 e-commerce-order-backend-1.0.0.jar，
# pom 里改一次 <version> 镜像就构建失败，而且报错很不直观（只说 COPY 找不到文件）。
# 注：Spring Boot 同时会生成 *.jar.original，其后缀是 .original，不会被 *.jar 匹配到。
COPY --from=build /app/target/*.jar app.jar

# 非 root 运行：容器内不写业务文件（日志走 stdout，不需要挂卷），只需读 jar 与 /tmp。
# 一旦发生容器逃逸，root 会把风险直接放大到宿主机，这是镜像安全的最基本要求。
RUN useradd -r -u 10001 -m appuser && chown -R appuser:appuser /app
USER appuser

EXPOSE 8080

# JVM 参数取舍：
#  - MaxRAMPercentage=70：JDK17 默认开启容器感知（UseContainerSupport），
#    但默认最大堆只取容器上限的 25%，利用率太低；显式给到 70% 后，
#    剩下的 30% 留给元空间、线程栈、直接内存等堆外部分，避免把 requests 吃满后被 OOMKill。
#  - 刻意不设 -Xms/-Xmx：让 JVM 按 cgroup 限制动态算，Pod 从 2G 换到 4G 不用改镜像。
#  - 刻意不加 HEALTHCHECK：K8s 完全忽略镜像里的 HEALTHCHECK 指令，
#    探针只在 Pod spec 里定义（见 L1 的 deployment.yaml），写了只是白增一层。
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70.0", "-Djava.io.tmpdir=/tmp", "-jar", "/app/app.jar"]