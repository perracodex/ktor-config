# [KtorConfig](https://github.com/perracodex/ktor-config)

A type-safe configuration mapper for the [Ktor](https://ktor.io/) framework.

---

### Features
- **Type-Safe Parsing**: Automatically maps configuration keys into Kotlin data classes.
- **Nested Configurations**: Automatically handles complex nested configuration structures.
- **List Parsing**: Supports parsing of lists from both HOCON arrays and comma-separated strings.
- **Asynchronous Parsing**: Utilizes coroutines for efficient and fast configuration parsing.
- **Customizable Mapping**: Define mappings between configuration paths and data classes, allowing a flexible configuration management.

---

### Installation
Add the library to your project gradle dependencies. Make sure to replace `1.0.0` with the latest version.

```kotlin
dependencies {
    implementation("io.github.perracodex:ktor-config:1.0.0")
}
```

**Note:** for multi-module setups the dependency should be added to each module that will require to access the parsed configuration.

---

### Usage

_See also the [API reference documentation](https://www.javadoc.io/doc/io.github.perracodex/ktor-config/latest/-ktor-config/io.perracodex.ktor.config/index.html)._

#### 1. Define Your Configuration Data Classes
Create data classes that represent your configuration structure. Each data class, including nested ones, **must** implement
the `IConfigCatalogSection` interface. Nested data classes are supported for handling complex configuration structures.

**Examples:**
```kotlin
data class DeploymentSettings(
    val port: Int,
    val sslPort: Int,
    val host: String,
) : IConfigCatalogSection

data class SecuritySettings(
    val encryption: EncryptionSettings,
    val basicAuth: BasicAuthSettings,
    val jwtAuth: JwtAuthSettings,
) : IConfigCatalogSection

data class EncryptionSettings(
    val atRest: Spec,
    val atTransit: Spec
) : IConfigCatalogSection {
    data class Spec(
        val algorithm: String,
        val salt: String,
        val key: String,
        val sign: String
    ) : IConfigCatalogSection
}

data class BasicAuthSettings(
    val providerName: String,
    val realm: String,
) : IConfigCatalogSection

data class JwtAuthSettings(
    val providerName: String,
    val tokenLifetimeSec: Long,
    val audience: String,
    val issuer: String,
    val realm: String,
    val secretKey: String
) : IConfigCatalogSection
```

#### 2. Define the catalog class that will hold the configuration data classes.
The catalog class **must** implement the `IConfigCatalog` interface. It should contain the top level configuration sections.

```kotlin
data class ConfigurationCatalog(
    val deployment: DeploymentSettings,
    val security: SecuritySettings
) : IConfigCatalog
```

#### 3. Define a singleton object to load and provide the configuration catalog across the application.
Mapping between the configuration file and the data classes is done in this object.
The syntax is simple: define a list of `ConfigCatalogMap` objects, each representing a mapping between
a configuration section and a data class property.

For example, the following mapping will parse the `ktor.deployment` section from the configuration file
and map it to the `DeploymentSettings` property in the `ConfigurationCatalog` class.

```kotlin
ConfigCatalogMap(keyPath = "ktor.deployment", catalogProperty = "deployment", propertyClass = DeploymentSettings::class)
```

In this example, the `keyPath` is the hierarchical key-path in the HOCON configuration file from which to parse,
the `catalogProperty` is the property name in the `IConfigCatalog` implementation, and the `propertyClass` is the
top-level data class to instantiate.

`keyPath` can be a simple key or a hierarchical path. For example, the key-path `"ktor.deployment"` will parse the
`deployment` section under the `ktor` section in the configuration file.

**Example:**
```kotlin
object AppSettings {
    @Volatile
    private lateinit var configuration: ConfigurationCatalog
    
    val deployment: DeploymentSettings get() = configuration.deployment
    val security: SecuritySettings get() = configuration.security
    
    fun load(applicationConfig: ApplicationConfig) {
        if (AppSettings::configuration.isInitialized) {
            return
        }

        // List defining the mappings between configuration file sections and properties within ConfigurationCatalog.
        // Each entry in the list consists of three components:
        // 1. keyPath: The hierarchical key-path in the configuration file from which to parse, (e.g., `"ktor.deployment"`).
        // 2. catalogProperty: The property name in the [IConfigCatalog] implementation.
        // 3. propertyClass: The catalogProperty class to instantiate.
        val catalogMappings: List<ConfigCatalogMap<out IConfigCatalogSection>> = listOf(
            ConfigCatalogMap(keyPath = "ktor.deployment", catalogProperty = "deployment", propertyClass = DeploymentSettings::class),
            ConfigCatalogMap(keyPath = "security", catalogProperty = "security", propertyClass = SecuritySettings::class)
        )

        // Since the configuration is loaded only once, it is safe to use runBlocking here,
        // which should happen only during application startup. The parsing is through a
        // suspend function, as the configuration sections are parsed asynchronously in parallel.
        runBlocking {
            configuration = ConfigurationParser.parse(
                configuration = applicationConfig,
                catalogClass = ConfigurationCatalog::class,
                catalogMappings = catalogMappings
            )
        }
    }
}
```

#### 4. Load the Configuration in the Application Module
This is the most important step. It must be done in the application module to ensure the configuration
is available throughout the application. It should be the very first step in the module.

**Example:**
```kotlin
fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.yourApplicationModule() {
    // Load the application configuration. Must be the first step in the module.
    AppSettings.load(applicationConfig = environment.config)

    configureKoin()
    configureDatabase()
    configureCors()
    configureSerialization()
    configureSecurity()
    configureRoutes()
}
```

#### 5. Done. Now you can access the configuration from anywhere in the application.
**Example:**
```kotlin
fun someFunction() {
    val foo1 = AppSettings.deployment.host
    val foo2 = AppSettings.security.encryption.atRest.algorithm
}
```

---

### Arrays And Enums
Both Enums and Arrays are supported in the configuration file.
For Arrays, you can use either comma-separated strings or HOCON arrays.

**Example:**
```hocon
ktor {
    deployment {
        ports: [8080, 8443, 8888]
        hosts: "localhost,testhost,prodhost"
    }
    security {
        basicAuth {
            roles: ["admin", "user"]
            environments: "dev,test,prod"
        }
    }
}
```

---

### Sample HOCON configuration file corresponding yo the mentioned data classes examples
```hocon    
ktor {
	deployment {
		port: 8080
		sslPort: 8443
		host: "localhost"
	}
}

security {
    basicAuth {
		providerName: "basic-auth"
		realm: "some-realm"
	}
	jwtAuth {
		providerName: "jwt-auth"
		tokenLifetimeSec: 3600
		audience: "some-audience"
		realm: "some-realm"
		issuer: "localhost"
		secretKey: "9e6e26399b28fc5f5ad1e4431f8a387a60bf94b89716805a376319fcdca35ca8"
	}
	encryption {
		atRest {
			algorithm: "AES_256_PBE_CBC"
			salt: "5c0744940b5c369b"
			key: "db82fafdbfe33a8b2bff5297de6e3a5cc15d1309664543cd376839b4d3b6b62e"
			sign: "6da315f14158bb5b986ea816cb78a329a6d1e0f724e031ca497d6b269cd8e475"
		}
		atTransit {
			algorithm: "AES_256_PBE_CBC"
			salt: "5c0744940b5c369b"
			key: "db82fafdbfe33a8b2bff5297de6e3a5cc15d1309664543cd376839b4d3b6b62e"
			sign: "6da315f14158bb5b986ea816cb78a329a6d1e0f724e031ca497d6b269cd8e475"
		}
	}
}
```

---

### License
This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
