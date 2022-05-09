import com.amazonaws.auth.AWSSessionCredentials
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.api.publish.maven.MavenPublication
import com.google.common.base.CaseFormat
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory

plugins {
    kotlin("android") version "1.4.31" apply false
    id("com.android.library") version "4.1.1" apply false
    id("maven-publish")
}

val awsCredentials = runCatching { DefaultAWSCredentialsProviderChain().credentials }
        .onFailure { logger.warn("Could not load AWS credentials. Publishing will be disabled. Error: ${it.message}") }
        .getOrNull()

buildscript {
    repositories {
        mavenLocal()
        mavenCentral()
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
        mavenLocal()
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
        apply plugin : "com.diffplug.spotless"

        spotless {
            java {
                target fileTree ('.') {
                    include '**/*.java'
                    exclude '**/resources/**'
                    exclude '**/build/**'
                }
                googleJavaFormat()
            }
        }
    }

    apply plugin : 'checkstyle'

    checkstyle {
        toolVersion = '8.5'
    }

    checkstyle {
        configFile = rootProject.file('checkstyle.xml')
        configProperties.checkStyleConfigDir = rootProject.rootDir
    }

    task checkstyle (type: Checkstyle) {
    source 'src'
    include '**/*.java'
    exclude '**/gen/**'
    // Caught by the violations plugin.
    ignoreFailures = true

    // empty classpath
    classpath = files()
}

    apply plugin : "se.bjurr.violations.violations-gradle-plugin"

    task violations (type: ViolationsTask) {
    minSeverity 'INFO'
    detailLevel 'VERBOSE'
    maxViolations = 0
    diffMaxViolations = 0

    // Formats are listed here: https://github.com/tomasbjerre/violations-lib
    def dir = projectDir . absolutePath
            violations = [
        ["PMD", dir, ".*/pmd/.*\\.xml\$", "PMD"],
        ["ANDROIDLINT", dir, ".*/lint-results\\.xml\$", "AndroidLint"],
        ["CHECKSTYLE", dir, ".*/checkstyle/.*\\.xml\$", "Checkstyle"],
    ]
}

    if (awsCredentials != null) {
        plugins.withType<MavenPublishPlugin> {
            configure<PublishingExtension> {
                repositories {
                    maven {
                        name = "streem"
                        setUrl("s3://maven.streem.com.s3.us-west-2.amazonaws.com/")
                        credentials(AwsCredentials::class) {
                            DefaultAWSCredentialsProviderChain().credentials.let {
                                accessKey = it.awsAccessKeyId
                                secretKey = it.awsSecretKey
                                sessionToken = (it as? AWSSessionCredentials)?.sessionToken
                            }
                        }
                    }
                }
            }
        }
    }
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
//TODO change to compiler
// Publishing the StreemSDK will also trigger publishing the AraasSDK, which it depends on.
tasks.register("publish").configure {
dependsOn(gradle.includedBuild("compiler").task(":Compiler:publishCompilerPublicationToStreemRepository"))
}
tasks.register("publishToMavenLocal").configure {
dependsOn(gradle.includedBuild("compiler").task(":Compiler:publishCompilerPublicationToMavenLocal"))
}
