# Stage 1: Build the application using Eclipse Temurin JDK 22
FROM eclipse-temurin:22-jdk AS build

# Set working directory inside the build container
WORKDIR /app

# Copy Gradle wrapper scripts and related files for build setup
COPY gradlew /app/gradlew
COPY gradlew.bat /app/gradlew.bat
COPY gradle/wrapper/gradle-wrapper.jar /app/gradle/wrapper/gradle-wrapper.jar
COPY gradle/wrapper/gradle-wrapper.properties /app/gradle/wrapper/gradle-wrapper.properties

# Copy Gradle build files (build.gradle, settings.gradle)
COPY build.gradle settings.gradle /app/

# Copy application source code to container
COPY src /app/src

# Ensure Gradle wrapper has execute permission
RUN chmod +x gradlew

# Execute Gradle build to clean and create a shadow (fat) JAR
RUN ./gradlew clean shadowJar

# Stage 2: Prepare runtime image
FROM eclipse-temurin:22-jdk

# Set working directory inside the runtime container
WORKDIR /app

# Copy the built shadow JAR from the build stage
COPY --from=build /app/build/libs/VideoServer-1.0-SNAPSHOT.jar /app/VideoServer.jar

# Copy static asset required by the application
COPY device_offline.jpg /app/device_offline.jpg

# Define container start command to run the JAR
ENTRYPOINT ["java", "-jar", "/app/VideoServer.jar"]

# Expose ports used by the application services
# 1234: WebSocket Server
# 4444: MJPEG Streaming Server
# 4321: Camera Server
EXPOSE 1234
EXPOSE 4444
EXPOSE 4321

# Docker usage instructions:
# Build image: sudo docker build -t videoserver:latest .
# Run container detached with port mappings:
# sudo docker run -d -p 1234:1234 -p 4444:4444 -p 4321:4321 videoserver
