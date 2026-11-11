# Use a lightweight Java 21 environment
FROM eclipse-temurin:21-jdk-alpine

# Set the working directory inside the container
WORKDIR /app

# Copy your Java file into the container
COPY WeatherAppWeb.java .

# Compile the Java file
RUN javac WeatherAppWeb.java

# Run the compiled Java app when the container starts
CMD ["java", "WeatherAppWeb"]