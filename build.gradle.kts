import kotlinx.team.infra.InfraExtension
import kotlinx.validation.ExperimentalBCVApi
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import tasks.CheckReadmeTask

buildscript {
    repositories {
        maven("https://maven.pkg.jetbrains.space/kotlin/p/kotlinx/maven")
        gradlePluginPortal()
        google()

        addDevRepositoryIfEnabled(this, project)
    }

    dependencies {
        classpath(libs.kotlinx.teamInfraGradlePlugin)

        val kotlinVersion = providers.gradleProperty("kotlin_version").orNull
        if (!kotlinVersion.isNullOrBlank()) {
            // In addition to overriding the Kotlin version in the Version Catalog,
            // also enforce the KGP version using a dependency constraint.
            // Constraints are stricter than Version Catalog.
            constraints {
                classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
            }
        }
        classpath("com.android.tools.build:gradle:8.5.1")
    }
}

plugins {
    id("base")
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlinx.binaryCompatibilityValidator)
}

apply(plugin = "kotlinx.team.infra")

extensions.configure<InfraExtension> {
    publishing {
        include(":kotlinx-benchmark-runtime")

        libraryRepoUrl = "https://github.com/Kotlin/kotlinx-benchmark"

        if (project.findProperty("publication_repository") == "sonatype") {
            sonatype {
                libraryStagingRepoDescription = project.name
            }
        }
    }
}

// https://youtrack.jetbrains.com/issue/KT-48410
repositories {
    mavenCentral()
}

// region Workarounds for https://github.com/gradle/gradle/issues/22335
tasks.register("apiDump") {
    dependsOn(gradle.includedBuild("plugin").task(":apiDump"))
}

tasks.register("apiCheck") {
    dependsOn(gradle.includedBuild("plugin").task(":apiCheck"))
}

afterEvaluate {
    gradle.includedBuilds.forEach { included ->
        project(":kotlinx-benchmark-runtime").tasks.named("publishToMavenLocal") { dependsOn(included.task(":publishToMavenLocal")) }
    }
}
tasks.register("publishToMavenLocal") {
    dependsOn(gradle.includedBuild("plugin").task(":publishToMavenLocal"))
}
//endregion

val currentKgpVersion = getKotlinPluginVersion()
logger.info("Using Kotlin Gradle Plugin $currentKgpVersion")

val kotlinVersionOverride = providers.gradleProperty("kotlin_version").getOrNull()

if (kotlinVersionOverride != null) {
    val versionCatalogKotlinVersion = libs.versions.kotlin.asProvider().get()
    if (kotlinVersionOverride != versionCatalogKotlinVersion) {
        throw IllegalStateException("Kotlin version in Version Catalog was not overridden. Expected:$kotlinVersionOverride, actual:$versionCatalogKotlinVersion.")
    }
    if (kotlinVersionOverride != currentKgpVersion) {
        throw IllegalStateException("Kotlin Gradle Plugin version was not overridden. Expected:$kotlinVersionOverride, actual:$currentKgpVersion.")
    }
}

allprojects {
    repositories {
        addDevRepositoryIfEnabled(this, project)
    }

    tasks.withType<KotlinCompilationTask<*>>().configureEach {
        compilerOptions {
            getOverriddenKotlinLanguageVersion(project)?.let {
                languageVersion = KotlinVersion.fromVersion(it)
            }
            getOverriddenKotlinApiVersion(project)?.let {
                apiVersion = KotlinVersion.fromVersion(it)
            }

            progressiveMode = true
            allWarningsAsErrors = true
        }
    }
}

apiValidation {
    ignoredProjects += listOf(
        "examples",
        "java",
        "kotlin-jvm-separate-benchmark-source-set",
        "kotlin-jvm",
        "kotlin-multiplatform",
        "integration",
    )

    nonPublicMarkers += listOf("kotlinx.benchmark.internal.KotlinxBenchmarkRuntimeInternalApi")

    @OptIn(ExperimentalBCVApi::class)
    klib {
        enabled = true
    }
}

val checkReadme by tasks.registering(CheckReadmeTask::class) {
    minSupportedGradleVersion = libs.versions.minSupportedGradle
    minSupportedKotlinVersion = libs.versions.minSupportedKotlin
    readme = file("README.md")
}

tasks.check {
    dependsOn(checkReadme)
    dependsOn(tasks.named("apiCheck"))
}
