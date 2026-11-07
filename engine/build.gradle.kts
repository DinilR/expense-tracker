plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
}

tasks.test {
    // Optional local-only coverage test: ./gradlew :engine:test -PsmsExport=D:/path/sms-export.xml
    project.findProperty("smsExport")?.let { systemProperty("smsExport", it.toString()) }
    project.findProperty("learnDebug")?.let { systemProperty("learnDebug", it.toString()) }
    testLogging {
        events("failed")
        showStandardStreams = true
    }
}
