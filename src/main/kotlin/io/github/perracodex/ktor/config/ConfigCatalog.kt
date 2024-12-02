/*
 * Copyright (c) 2024-Present Perracodex. Use of this source code is governed by an MIT license.
 */

package io.github.perracodex.ktor.config

/**
 * Interface for the configuration catalog holding each of the [ConfigCatalogSection] settings.
 *
 * @see ConfigCatalogSection
 */
public interface ConfigCatalog

/**
 * Interface for each concrete configuration section within the [ConfigCatalog] implementation.
 *
 * @see ConfigCatalog
 */
public interface ConfigCatalogSection
