plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

evaluationDependsOn(":kotlinx-benchmark-runtime")

val runtime get() = project(":kotlinx-benchmark-runtime")
val plugin get() = gradle.includedBuild("plugin")

dependencies {
    implementation(gradleTestKit())

    testImplementation(kotlin("test-junit"))
}

tasks.test {
    dependsOn(plugin.task(":publishToBuildLocal"))
    dependsOn(runtime.tasks.getByName("publishToBuildLocal"))

    systemProperty("plugin_repo_url", plugin.projectDir.resolve("build/maven").absoluteFile.invariantSeparatorsPath)
    systemProperty("runtime_repo_url", rootProject.buildDir.resolve("maven").absoluteFile.invariantSeparatorsPath)
    systemProperty("kotlin_repo_url", rootProject.properties["kotlin_repo_url"])
    systemProperty("kotlin_version", libs.versions.kotlin.get())
    systemProperty("minSupportedGradleVersion", libs.versions.minSupportedGradle.get())
}
