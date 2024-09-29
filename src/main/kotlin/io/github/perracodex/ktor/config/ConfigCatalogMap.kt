/*
 * Copyright (c) 2024-Present Perracodex. Use of this source code is governed by an MIT license.
 */

package io.github.perracodex.ktor.config

import kotlin.reflect.KClass

/**
 * Represents a mapping between a `key path` in a configuration file
 * and a corresponding data class to which the configuration values must be loaded.
 *
 * Each instance of this class defines how a concrete section of the configuration
 * file is mapped into a property within the [IConfigCatalog] implementation.
 *
 * @property keyPath The hierarchical key-path in the configuration file from which to parse, (e.g., `"ktor.deployment"`).
 * @property catalogProperty The property name in the [IConfigCatalog] implementation.
 * @property propertyClass The [catalogProperty] class to instantiate.
 *
 * @see [IConfigCatalog]
 * @see [IConfigCatalogSection]
 */
public data class ConfigCatalogMap<T : IConfigCatalogSection>(
    val keyPath: String,
    val catalogProperty: String,
    val propertyClass: KClass<T>
)
