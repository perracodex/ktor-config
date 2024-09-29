/*
 * Copyright (c) 2024-Present Perracodex. Use of this source code is governed by an MIT license.
 */

package io.github.perracodex.ktor.config

/**
 * Represent an exception that occurs when there is a problem parsing or reading the configuration.
 *
 * @param message The detail message describing the failure.
 * @param cause Optional underlying reason for this [ConfigurationException].
 */
public class ConfigurationException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
