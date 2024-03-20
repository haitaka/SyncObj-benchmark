plugins {
    kotlin("multiplatform") version "1.9.23"
    id("org.jetbrains.kotlinx.benchmark") version "0.4.10"
}

repositories {
    mavenCentral()
}

kotlin {
    val nativeTarget = macosArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-benchmark-runtime:0.4.10")
                implementation("org.jetbrains.kotlinx:atomicfu:0.23.2")
            }
        }
    }
}

benchmark {
    targets {
        register("macosArm64")
    }
}