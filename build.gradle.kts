/*
 * Copyright (c) 2024-Present Perracodex. Use of this source code is governed by an MIT license.
 */
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    `java-library`
    signing
    alias(libs.plugins.dokka)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.vanniktech)
}

group = "io.github.perracodex"
version = "1.0.0"

// Configuration block for all projects in this multi-project build.
allprojects {

    // Define repositories where dependencies are fetched from.
    repositories {
        // Use Maven Central as the primary repository for fetching dependencies.
        mavenCentral()

        // Used to include locally published libraries. Useful for testing libraries
        // that are built and published locally.
        mavenLocal()
    }
}

kotlin {
    jvmToolchain(jdkVersion = 17)

    // Enable explicit API mode for all subprojects.
    // https://github.com/Kotlin/KEEP/blob/master/proposals/explicit-api-mode.md
    // https://kotlinlang.org/docs/whatsnew14.html#explicit-api-mode-for-library-authors
    explicitApi()
}

dependencies {
    implementation(libs.ktor.server.core)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

// https://central.sonatype.com/account
// https://central.sonatype.com/publishing/deployments
// https://vanniktech.github.io/gradle-maven-publish-plugin/central/#automatic-release
mavenPublishing {
    coordinates(
        groupId = group as String,
        artifactId = "ktor-config",
        version = version as String
    )

    pom {
        name.set("KtorConfig")
        description.set("A type-safe configuration mapper for Ktor.")
        url.set("https://github.com/perracodex/ktor-config")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }
        developers {
            developer {
                id.set("perracodex")
                name.set("Perracodex")
                email.set(System.getenv("DEVELOPER_EMAIL"))
                url = "https://github.com/perracodex"
            }
        }
        scm {
            connection.set("scm:git:git://github.com/perracodex/ktor-config.git")
            developerConnection.set("scm:git:ssh://github.com:perracodex/ktor-config.git")
            url.set("https://github.com/perracodex/ktor-config")
        }
    }

    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
}

signing {
    val privateKeyPath: String? = System.getenv("MAVEN_SIGNING_KEY_PATH")
    val passphrase: String? = System.getenv("MAVEN_SIGNING_KEY_PASSPHRASE")

    if (privateKeyPath.isNullOrBlank())
        println("MAVEN_SIGNING_KEY_PATH is not set. Skipping signing.")
    else if (passphrase.isNullOrBlank()) {
        println("MAVEN_SIGNING_KEY_PASSPHRASE is not set. Skipping signing.")
    }  else {
        val privateKey: String = File(privateKeyPath).readText()
        useInMemoryPgpKeys(privateKey, passphrase)
        sign(publishing.publications)
    }
}
