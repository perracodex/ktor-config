/*
 * Copyright (c) 2024-Present Perracodex. Use of this source code is governed by an MIT license.
 */

package io.github.perracodex.ktor.config

import com.typesafe.config.ConfigException
import io.github.perracodex.ktor.utils.Tracer
import io.ktor.server.config.*
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlin.reflect.*
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.jvmErasure

/**
 * Automates the parsing of application configuration settings into Kotlin data classes.
 * Utilizes reflection for mapping configuration paths to data class types, supporting
 * both nested and simple structures in line with HOCON standards.
 *
 * Requirements for effective parsing:
 * - Data class properties must match configuration keys exactly.
 * - Configuration paths should mirror the data class hierarchy for proper mapping.
 * - The configuration file needs to be properly formatted according to HOCON specifications.
 *
 * Additionally, the parser can handle list configurations as comma-separated strings or actual lists,
 * facilitating simpler configuration in environment variables. This allows settings defined as lists
 * in HOCON to be represented as comma-delimited strings in environmental variables.
 */
public object ConfigurationParser {
    private val tracer = Tracer<ConfigurationParser>()

    /**
     * Represents the delimiter used for separating elements in a list,
     * when the list is represented as a single string in the configuration.
     */
    private const val SINGLE_STRING_ARRAY_DELIMITER: Char = ','

    /**
     * Represents the regex pattern used to split strings by the delimiter,
     * ensuring that delimiters within single quotes are ignored.
     */
    private val SINGLE_STRING_ARRAY_REGEX_PATTERN: Regex by lazy {
        Regex(pattern = "${Regex.escape(SINGLE_STRING_ARRAY_DELIMITER.toString())}(?=(?:[^']*'[^']*')*[^']*\$)")
    }

    /**
     * Represents a mapping from a catalog constructor parameter to its corresponding configuration instance.
     *
     * @property parameter A catalog constructor [KParameter].
     * @property instance The instance to be assigned to the  [parameter].
     */
    private data class CatalogPropertyMap(val parameter: KParameter, var instance: Any)

    /**
     * Performs the application configuration parsing.
     * Top-level configurations are parsed concurrently.
     *
     * @param configuration The [ApplicationConfig] object to be parsed.
     * @param catalogClass The class holding all the configuration groups.
     * @param catalogMappings Map of top-level configuration paths to their corresponding classes.
     * @return A new [catalogClass] instance populated with the parsed configuration data.
     * @throws ConfigurationException if the primary constructor is missing in the [catalogClass],
     * or any error occurs during the parsing process.
     */
    public suspend fun <T : Any> parse(
        configuration: ApplicationConfig,
        catalogClass: KClass<T>,
        catalogMappings: List<ConfigCatalogMap>
    ): T {
        // Retrieve the primary constructor of the configuration catalog class,
        // which will be used to instantiate the parsing output result.
        val configConstructor: KFunction<T> = catalogClass.primaryConstructor
            ?: throw ConfigurationException(
                "Primary constructor is required for ${catalogClass.simpleName}."
            )

        // Map each configuration path to its corresponding class,
        // and construct the arguments map for the output object.
        val catalogArguments: Map<KParameter, Any> = withContext(Dispatchers.IO) {
            val tasks: List<Deferred<CatalogPropertyMap>> = catalogMappings.map { configCatalogMap ->
                async {
                    // Map each configuration path to its corresponding class.
                    // Nested settings are handled recursively.
                    val catalogParameterInstance: Any = instantiateConfig(
                        config = configuration,
                        keyPath = configCatalogMap.keyPath,
                        kClass = configCatalogMap.propertyClass
                    )

                    // Find the constructor parameter corresponding to the configuration class.
                    val catalogParameter: KParameter = configConstructor.parameters.find { parameter ->
                        configCatalogMap.catalogProperty.equals(other = parameter.name, ignoreCase = true)
                    } ?: throw ConfigurationException("Catalog argument not found: ${configCatalogMap.catalogProperty}")

                    // Return the mapping of the constructor argument parameter to its value.
                    return@async CatalogPropertyMap(
                        parameter = catalogParameter,
                        instance = catalogParameterInstance
                    )
                }
            }

            // Await all results and construct the catalog parameter instance map.
            return@withContext tasks.map { mappingTask -> mappingTask.await() }
                .associate { propertyMap ->
                    propertyMap.parameter to propertyMap.instance
                }
        }

        // Create the instance of the configuration catalog with the parsed property instances.
        return runCatching {
            configConstructor.callBy(args = catalogArguments)
        }.onFailure { error ->
            throw ConfigurationException("Error instantiating: ${catalogClass.simpleName}", error)
        }.getOrThrow()
    }

    /**
     * Dynamically instantiates an object of the specified KClass using the primary constructor.
     * Supports both simple and nested data class types. For each constructor parameter,
     * fetches the corresponding configuration value from the specified key path.
     *
     * Data classes constructor parameters and setting key names must match exactly.
     *
     * @param config The application configuration object.
     * @param keyPath The key path in the configuration for fetching values.
     * @param kClass The KClass of the type to instantiate.
     * @return An instance of the specified class with properties populated from the configuration.
     * @throws ConfigurationException If a required configuration key is missing or if there is a type mismatch.
     */
    private fun <T : Any> instantiateConfig(
        config: ApplicationConfig,
        keyPath: String,
        kClass: KClass<T>
    ): T {
        tracer.debug("Parsing '${kClass.simpleName}' from '$keyPath'")

        // Fetch the primary constructor of the class.
        val constructor: KFunction<T> = kClass.primaryConstructor
            ?: throw ConfigurationException("No primary constructor found for ${kClass.simpleName}")

        // Map each constructor parameter to its corresponding value from the configuration.
        // This includes direct value assignment for simple types and recursive instantiation
        // for nested data classes.
        val arguments: Map<KParameter, Any?> = constructor.parameters.associateWith { parameter ->
            val parameterKClass: KClass<*> = parameter.type.jvmErasure
            val parameterKeyPath = "$keyPath.${parameter.name}"

            if (parameterKClass.isData) {
                // Recursive instantiation for nested data classes.
                return@associateWith instantiateConfig(
                    config = config,
                    keyPath = parameterKeyPath,
                    kClass = parameterKClass
                )
            } else {
                // Find the target property attribute corresponding to the parameter in the class.
                val property: KProperty1<T, *> = kClass.memberProperties.find { property ->
                    property.name.equals(other = parameter.name, ignoreCase = true)
                } ?: throw ConfigurationException("Property '${parameter.name}' not found in '${kClass.simpleName}'.")

                // Convert and return the configuration value for the parameter.
                return@associateWith convertToType(
                    config = config,
                    keyPath = parameterKeyPath,
                    kClass = parameterKClass,
                    property = property
                )
            }
        }

        // Create an instance of the class with the obtained configuration values.
        return runCatching {
            constructor.callBy(args = arguments)
        }.getOrElse { error ->
            val errorMessage: String = error.message ?: error.cause?.toString() ?: "Unknown error"
            val argumentNames: String = arguments.keys.joinToString(separator = ", ") { it.name ?: "null" }
            throw ConfigurationException(
                "Error instantiating: ${kClass.simpleName}" +
                        "\nMake sure the key path and class properties names match exactly." +
                        "\nKey path: $keyPath" +
                        "\nArguments: $argumentNames" +
                        "\nError: $errorMessage"
            )
        }
    }

    /**
     * Converts a configuration property to the given type.
     *
     * Retrieves a property from the configuration based on the keyPath and converts it
     * to the specified type. For data classes, it recursively instantiates them.
     *
     * @param config The application configuration object.
     * @param keyPath The key path for the property in the configuration.
     * @param kClass The KClass to which the property should be converted.
     * @param property The property attribute from the type's KClass.
     * @return The converted property value or null if not found.
     * @throws ConfigurationException for unsupported types or conversion failures.
     */
    private fun convertToType(
        config: ApplicationConfig,
        keyPath: String,
        kClass: KClass<*>,
        property: KProperty1<*, *>
    ): Any? {
        // Handle data classes. Recursively instantiate them.
        if (kClass.isData) {
            return instantiateConfig(config = config, keyPath = keyPath, kClass = kClass)
        }

        // Handle lists.
        if (kClass == List::class) {
            // Extract the KType of the list's elements by accessing its single type argument.
            // Example: For a property of type List<String>, extract the KType of the elements KType<String>.
            val elementKType: KType = property.returnType.arguments.singleOrNull()?.type
                ?: throw ConfigurationException(
                    "Cannot determine the list elements type for property '${property.name}' in '${property.returnType}'."
                )

            // Retrieve the KClass of the list element type.
            // Example: If elementType is KType<String> then elementsClass is KClass<String>.
            val elementsKClass: KClass<*> = (elementKType.classifier as? KClass<*>)
                ?: throw ConfigurationException(
                    "Cannot determine list elements type class for property '${property.name}' in '${property.returnType}'."
                )

            // Parse the list values using the extracted list element class
            return parseListValues(config = config, keyPath = keyPath, elementsKClass = elementsKClass)
        }

        // Handle simple class types.
        val stringValue: String = config.tryGetString(key = keyPath) ?: return null
        return parseElementValue(keyPath = keyPath, stringValue = stringValue, kClass = kClass)
    }

    /**
     * Function to parse an element into its final value.
     *
     * @param keyPath The key path for the property in the configuration.
     * @param stringValue The string value to convert.
     * @param kClass The KClass to which the property should be converted.
     * @return The converted property value or null if not found.
     * @throws ConfigurationException For unsupported types or conversion failures.
     */
    private fun parseElementValue(keyPath: String, stringValue: String, kClass: KClass<*>): Any? {
        val key = "$keyPath: $stringValue"

        return when {
            kClass == String::class -> stringValue

            kClass == Boolean::class -> stringValue.toBooleanStrictOrNull()
                ?: throw ConfigurationException("Invalid Boolean value in: '$key'")

            kClass == Int::class -> stringValue.toIntOrNull()
                ?: throw ConfigurationException("Invalid Int value in: '$key'")

            kClass == Long::class -> stringValue.toLongOrNull()
                ?: throw ConfigurationException("Invalid Long value in: '$key'")

            kClass == Double::class -> stringValue.toDoubleOrNull()
                ?: throw ConfigurationException("Invalid Double value in: '$key'")

            kClass.java.isEnum -> {
                // Check if the config value is build by single string with comma-delimited values.
                if (stringValue.contains(char = SINGLE_STRING_ARRAY_DELIMITER)) {
                    // Split the string by commas and trim spaces, then convert each part to enum.
                    stringValue.split(SINGLE_STRING_ARRAY_DELIMITER).mapNotNull { part ->
                        convertToEnum(enumKClass = kClass, stringValue = part.trim(), keyPath = keyPath)
                    }
                } else {
                    // If there is only one single value, without commas, convert it directly to enum.
                    convertToEnum(enumKClass = kClass, stringValue = stringValue, keyPath = keyPath)
                }
            }

            else -> throw ConfigurationException("Unsupported type class '$kClass' in '$key'")
        }
    }

    /**
     * Converts a string value to an enum.
     *
     * @param enumKClass The enum type class to which the string value should be converted.
     * @param stringValue The string value to convert.
     * @param keyPath The key path for the property in the configuration.
     * @return The converted enum value or null if not found.
     * @throws ConfigurationException If the enum value is not found.
     */
    private fun convertToEnum(enumKClass: KClass<*>, stringValue: String, keyPath: String): Enum<*>? {
        if (stringValue.isBlank() || stringValue.lowercase() == "null") {
            return null
        }

        return enumKClass.java.enumConstants.firstOrNull {
            (it as Enum<*>).name.compareTo(stringValue, ignoreCase = true) == 0
        } as Enum<*>?
            ?: throw ConfigurationException(
                "Enum value '$stringValue' not found for type: $enumKClass. Found in path: $keyPath"
            )
    }

    /**
     * Parses a list from the configuration.
     * Lists can be specified as a single string, comma-separated, or as a list of strings.
     * The list is mapped to the specified type.
     *
     * @param config The application configuration object.
     * @param keyPath The key path for the property in the configuration.
     * @param elementsKClass The KClass to which the list elements should be converted.
     * @return The converted list or an empty list if not found.
     */
    private fun parseListValues(
        config: ApplicationConfig,
        keyPath: String,
        elementsKClass: KClass<*>
    ): List<Any?> {
        val rawList: List<String> = try {
            // Attempt to retrieve it as a list.
            config.tryGetStringList(key = keyPath) ?: listOf()
        } catch (_: ConfigException) {
            // If failed to get a list, then treat it as a single string with comma-delimited values.
            val stringValue: String = config.tryGetString(key = keyPath) ?: ""

            if (stringValue.isNotBlank()) {
                // Use regex to split by commas that are not within single quotes.
                stringValue.split(regex = SINGLE_STRING_ARRAY_REGEX_PATTERN)
                    .map { it.trim().trim('\'') } // Trim whitespace and single quotes.
                    .filter { it.isNotEmpty() }
            } else {
                listOf()
            }
        }

        // Map each element of the list to its respective class type.
        return rawList.map { listElementValue ->
            parseElementValue(keyPath = keyPath, stringValue = listElementValue, kClass = elementsKClass)
        }
    }
}
