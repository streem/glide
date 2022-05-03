import io.github.gradlenexus.publishplugin.NexusPublishExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.api.publish.maven.MavenPublication
import com.google.common.base.CaseFormat
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory


plugins {
    kotlin("android") version "1.4.31" apply false
    id("com.android.library") version "4.1.1" apply false

    id("io.github.gradle-nexus.publish-plugin") version "1.1.0"
}

val sonatypeApiUser = providers.gradlePropertyOrEnvironmentVariable("sonatypeApiUser")
val sonatypeApiKey = providers.gradlePropertyOrEnvironmentVariable("sonatypeApiKey")
if (sonatypeApiUser.isPresent && sonatypeApiKey.isPresent) {
    configure<NexusPublishExtension> {
        repositories {
            sonatype {
                nexusUrl.set(uri("https://s01.oss.sonatype.org/service/local/"))
                snapshotRepositoryUrl.set(uri("https://s01.oss.sonatype.org/content/repositories/snapshots/"))
                username.set(sonatypeApiUser)
                password.set(sonatypeApiKey)
            }
        }
    }
} else {
    logger.info("Sonatype API key not defined, skipping configuration of Maven Central publishing repository")
}

fun MavenPublication.configureGlidePom(pomDescription: String) {
    val pomName = artifactId
    pom {
        name.set(pomName)
        description.set(pomDescription)
        url.set("https://github.com/streem/glide")

        licenses {
            license {
                name.set("Apache License, Version 2.0")
                url.set("https://opensource.org/licenses/Apache-2.0")
            }
        }

        organization {
            name.set("Streem, LLC")
            url.set("https://github.com/streem")
        }

        developers {
            developer {
                id.set("streem")
                name.set("Streem, LLC")
                url.set("https://github.com/streem")
            }
        }

        scm {
            connection.set("scm:git:git@github.com:streem/glide.git")
            developerConnection.set("scm:git:git@github.com:streem/glide.git")
            url.set("git@github.com:streem/glide.git")
        }
    }
}

@Suppress("UnstableApiUsage")
fun ProviderFactory.gradlePropertyOrEnvironmentVariable(propertyName: String): Provider<String> {
    val envVariableName = CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_UNDERSCORE, propertyName)
    return gradleProperty(propertyName)
            .forUseAtConfigurationTime()
            .orElse(environmentVariable(envVariableName).forUseAtConfigurationTime())
}

val signingKeyAsciiArmored = providers.gradlePropertyOrEnvironmentVariable("signingKeyAsciiArmored")
if (signingKeyAsciiArmored.isPresent) {
    subprojects {
        plugins.withType<SigningPlugin> {
            configure<SigningExtension> {
                @Suppress("UnstableApiUsage")
                useInMemoryPgpKeys(signingKeyAsciiArmored.get(), "")
                sign(extensions.getByType<PublishingExtension>().publications)
            }
        }
    }
} else {
    logger.info("PGP signing key not defined, skipping signing configuration")
}

buildscript {
    repositories {
        mavenLocal()
        jcenter()
        google()
        gradlePluginPortal()
    }

    dependencies {
        classpath "com.android.tools.build:gradle:${ANDROID_GRADLE_VERSION}"
        if (!hasProperty('DISABLE_ERROR_PRONE')) {
            classpath "net.ltgt.gradle:gradle-errorprone-plugin:${ERROR_PRONE_PLUGIN_VERSION}"
        }
        classpath "se.bjurr.violations:violations-gradle-plugin:${VIOLATIONS_PLUGIN_VERSION}"
        classpath "com.diffplug.spotless:spotless-plugin-gradle:5.11.0"
        classpath "androidx.benchmark:benchmark-gradle-plugin:${ANDROID_X_BENCHMARK_VERSION}"
    }
}

// See http://blog.joda.org/2014/02/turning-off-doclint-in-jdk-8-javadoc.html.
allprojects {
    tasks.withType(Javadoc).all { enabled = false }
}

subprojects { project ->

    repositories {
        google()
        mavenCentral()
        maven {
            url "https://oss.sonatype.org/content/repositories/snapshots"
        }
        gradlePluginPortal()
    }

    tasks.withType(JavaCompile) {
        sourceCompatibility = 1.7
        targetCompatibility = 1.7

        options.setBootstrapClasspath(files("${System.getProperty('java.home')}/lib/rt.jar"))
        // gifencoder is a legacy project that has a ton of warnings and is basically never
        // modified, so we're not going to worry about cleaning it up.
        // Imgur uses generated code from dagger that has warnings.
    }

    tasks.withType(Test) {
        testLogging {
            exceptionFormat = TestExceptionFormat.FULL
        }
    }

    // Avoid issues like #2452.
    tasks.withType(Jar) {
        duplicatesStrategy = DuplicatesStrategy.FAIL
    }

    def isDisallowedProject =
    project.name in ["third_party", "gif_decoder", "gif_encoder", "disklrucache", "glide"]
    if (!isDisallowedProject) {
        apply plugin: "com.diffplug.spotless"

        spotless {
            java {
                target fileTree('.') {
                    include '**/*.java'
                    exclude '**/resources/**'
                    exclude '**/build/**'
                }
                googleJavaFormat()
            }
        }
    }

    apply plugin: 'checkstyle'

    checkstyle {
        toolVersion = '8.5'
    }

    checkstyle {
        configFile = rootProject.file('checkstyle.xml')
        configProperties.checkStyleConfigDir = rootProject.rootDir
    }

    task checkstyle(type: Checkstyle) {
    source 'src'
    include '**/*.java'
    exclude '**/gen/**'
    // Caught by the violations plugin.
    ignoreFailures = true

    // empty classpath
    classpath = files()
}

    apply plugin: "se.bjurr.violations.violations-gradle-plugin"

    task violations(type: ViolationsTask) {
    minSeverity 'INFO'
    detailLevel 'VERBOSE'
    maxViolations = 0
    diffMaxViolations = 0

    // Formats are listed here: https://github.com/tomasbjerre/violations-lib
    def dir = projectDir.absolutePath
            violations = [
        ["PMD",         dir, ".*/pmd/.*\\.xml\$",        "PMD"],
        ["ANDROIDLINT", dir, ".*/lint-results\\.xml\$",  "AndroidLint"],
        ["CHECKSTYLE",  dir, ".*/checkstyle/.*\\.xml\$", "Checkstyle"],
    ]
}

    afterEvaluate {

        if (project.hasProperty("android")
                && project.name != 'pmd' ) {
            android {
                lintOptions {
                    warningsAsErrors true
                    quiet true
                    // Caught by the violations plugin.
                    abortOnError false
                }
            }

            android.variantFilter { variant ->
                if(variant.buildType.name == 'release') {
                    variant.setIgnore(true)
                }
            }
        }

        if (project.tasks.findByName('check')) {
            check.dependsOn('checkstyle')
            check.finalizedBy violations
        }


        if (project.plugins.hasPlugin('com.android.library')) {
            android.libraryVariants.all {
                it.generateBuildConfigProvider.configure { enabled = false }
            }
        }
    }
}
