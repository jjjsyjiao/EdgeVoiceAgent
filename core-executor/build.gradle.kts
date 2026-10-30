plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.edgevoice.agent.executor"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.annotation)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // 双模执行器契约依赖传输通道
    implementation(project(":core-transport"))
    // 端侧 SLM：MediaPipe LLM Inference（官方 Maven 依赖，编译期零风险；模型文件运行时加载）
    implementation(libs.mediapipe.genai)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    // CloudRealtimeExecutor 经 RealtimeProtocol 间接依赖 org.json
    testImplementation(libs.robolectric)
}
