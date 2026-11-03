// Health calculations shared by :app and :wear: sleep staging from watch
// minutes, nightly resting heart rate. Pure Kotlin on the JDK only, so the
// phone and the watch compute the same result from the same code.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // WearSleepMinute reads its flags from the wire protocol.
    api(project(":wearlink"))

    testImplementation(libs.junit4)
    testImplementation(libs.org.json)
}
