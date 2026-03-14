import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.11.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

val javaVersion = providers.gradleProperty("javaVersion").get().toInt()
val platformVersion = providers.gradleProperty("platformVersion").get()
val bundledPlugins = providers.gradleProperty("platformBundledPlugins").get()
    .split(',')
    .map(String::trim)
    .filter(String::isNotEmpty)
    .toMutableList()
    .apply {
        // Full Line's YAML module depends on the bundled YAML plugin in 2025.3.
        if ("org.jetbrains.plugins.yaml" !in this) {
            add("org.jetbrains.plugins.yaml")
        }
    }

val localIdePath = System.getenv("IDEA_LOCAL_PATH")?.takeIf { File(it).exists() }

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
    testImplementation("org.opentest4j:opentest4j:1.3.0")

    intellijPlatform {
        if (localIdePath != null) {
            local(localIdePath)
        } else {
            intellijIdea(platformVersion)
        }
        bundledPlugins(bundledPlugins)
        testFramework(TestFrameworkType.Platform)
    }
}

java {
    sourceCompatibility = JavaVersion.toVersion(javaVersion)
    targetCompatibility = JavaVersion.toVersion(javaVersion)
}

kotlin {
    jvmToolchain(javaVersion)

    compilerOptions {
        jvmTarget = JvmTarget.fromTarget(javaVersion.toString())
        freeCompilerArgs.add("-jvm-default=no-compatibility")
    }
}

intellijPlatform {
    buildSearchableOptions = false
    instrumentCode = false

    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }
}

extensions.findByType(org.jetbrains.intellij.platform.gradle.extensions.IntelliJPlatformPluginsExtension::class.java)
    ?.apply {
        // Avoid loading the bundled Full Line plugin which fails descriptor creation in 2025.3.
        disablePlugin("org.jetbrains.completion.full.line")
        disablePlugin("fullLine")
    }

tasks {
    test {
        useJUnitPlatform()
        // IntelliJ test sandbox installs a custom system class loader, which makes JDK CDS log a warning on startup.
        jvmArgs("-Xshare:off")
    }
}
