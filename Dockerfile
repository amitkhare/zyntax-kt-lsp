# Running this image will start a language server that listens for TCP connections on port 49100
# Every connection will be run in a forked child process
#
# Build: Java 21 (Kotlin 2.2.x cannot compile with Java 25)
# Runtime: Java 25 (KLS works well with Java 25 projects at runtime)

ARG BUILD_JDK_VERSION=21
ARG RUNTIME_JDK_VERSION=25

FROM --platform=$BUILDPLATFORM eclipse-temurin:${BUILD_JDK_VERSION} AS builder

ARG BUILD_JDK_VERSION

WORKDIR /src/ktlsp

COPY . .
RUN ./gradlew :server:installDist -PjavaVersion=${BUILD_JDK_VERSION}

FROM eclipse-temurin:${RUNTIME_JDK_VERSION}

WORKDIR /opt/ktlsp

COPY --from=builder /src/ktlsp/server/build/install/server /opt/ktlsp
RUN ln -s /opt/ktlsp/bin/kotlin-language-server /usr/local/bin/kotlin-language-server

EXPOSE 49100

CMD ["/usr/local/bin/kotlin-language-server", "--tcpServerPort", "49100"]
