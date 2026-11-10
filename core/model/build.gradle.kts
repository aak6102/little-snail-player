import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.android.jvm.get()))
    }
}

tasks.withType<JavaCompile> {
    sourceCompatibility = libs.versions.android.jvm.get()
    targetCompatibility = libs.versions.android.jvm.get()
}

tasks.withType<Test> {
    // 词表完整性测试要读真实的 assets 目录。用系统属性传绝对路径，
    // 不依赖测试进程的工作目录（各 IDE 与 Gradle 对 workingDir 的处理并不一致）。
    systemProperty("quizAssetsDir", rootProject.file("app/src/main/assets/quiz").absolutePath)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit4)
}
