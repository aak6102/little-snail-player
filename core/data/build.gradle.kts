import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.koin.compiler)
}

android {
    namespace = "dev.anilbeesetti.nextplayer.core.data"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.android.jvm.get().toInt())
        targetCompatibility = JavaVersion.toVersion(libs.versions.android.jvm.get().toInt())
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.android.jvm.get()))
    }
}

dependencies {

    implementation(project(":core:database"))
    implementation(project(":core:media"))
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:datastore"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    // 票据载荷解析：core:model 已声明 kotlinx-serialization，但那是 implementation，
    // 不传递到本模块，所以要显式声明一次（只用到 Json 运行时，不需要再挂插件）
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.github.anilbeesetti.nextlib.mediainfo)

    // Koin
    implementation(libs.koin.core)
    implementation(libs.koin.annotations)

    testImplementation(libs.junit4)
    testImplementation(libs.androidx.datastore.core)
    testImplementation(libs.androidx.room.runtime)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.activity.ktx)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.room.runtime)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
