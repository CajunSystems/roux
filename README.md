# Roux

Roux is an effect system for the JVM, leveraging modern Java features to provide a robust and expressive way to model and manage side effects in your applications.

## Features

- Effectful computations with strong type safety
- Composable and declarative effect management
- Separation of effect specification and execution
- Pluggable runtime with thread pool factory swapping (virtual threads by default)
- Service Loader-based runtime initialization for easy customization
- Integration with Java and Kotlin
- Utilizes modern Java features (records, sealed classes, lambdas, etc.)

## Getting Started

Add Roux to your Gradle project:

```groovy
dependencies {
    implementation 'com.cajunsystems:roux:0.1.0'
}
```

## License

MIT
