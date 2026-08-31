# Multi-stage build：build 階段裝 Maven + JDK 編出 jar，
# 最終 image 只留 JRE + jar，不含原始碼與建置工具。
#
# ⚠️ Render 沒有 Java 的原生 runtime（只支援 JS/Python/Ruby/Go/Rust/Elixir），
# Spring Boot 一定要走 Docker。

FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# 先只複製 pom.xml 跟 wrapper，讓 Docker 的 layer cache 生效 ——
# 只要依賴沒變，改 src 底下的程式碼不會讓這層重新下載一次整個 .m2
COPY .mvn/ .mvn
COPY mvnw pom.xml ./
# ⚠️ mvnw 在 git 裡沒有可執行權限（這個 repo 是在 Windows 上建的，
# 從沒 chmod +x 過）。少這行會在 Linux 容器裡直接 Permission denied
RUN chmod +x mvnw
RUN ./mvnw -B dependency:go-offline

COPY src ./src
# ⚠️ -DskipTests 不是圖快 —— 測試現在用 Testcontainers（今天剛加），
# 需要 Docker daemon 才跑得動。Render 的建置環境沒有 Docker-in-Docker，
# 帶著測試跑這一步會直接卡死或失敗。CI 上驗證測試是另一件事，
# 不該發生在部署建置的路徑上
RUN ./mvnw -B clean package -DskipTests


FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

# 只是文件性質：實際監聽的 port 由 PORT 環境變數決定（見 application.yaml），
# Render 會自己注入，這裡的 8080 只是本機用 docker run 時的預設說明
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
