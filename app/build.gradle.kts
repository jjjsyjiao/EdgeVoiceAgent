import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/** 从 local.properties 读取密钥与模型路径，构建期注入 BuildConfig（不入库） */
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun cup(key: String, def: String = ""): String =
    (localProps.getProperty(key) ?: def).replace("\\", "\\\\").replace("\"", "\\\"")

/** 布尔型 local.properties 读取（未配置取默认值）。 */
fun cupBool(key: String, def: Boolean): Boolean =
    localProps.getProperty(key)?.toBoolean() ?: def

android {
    namespace = "com.edgevoice.agent.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.edgevoice.agent.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "3.1.0"
        testInstrumentationRunner = "com.edgevoice.agent.app.NecessaryChainInstrumentation"

        buildConfigField("String", "OPENAI_API_KEY", "\"${cup("openai.api.key")}\"")
        buildConfigField("String", "OPENAI_REALTIME_URL", "\"${cup("openai.realtime.url", "wss://api.openai.com/v1/realtime")}\"")
        buildConfigField("String", "OPENAI_REALTIME_MODEL", "\"${cup("openai.realtime.model", "gpt-4o-realtime-preview")}\"")
        // Realtime 供应商画像：openai（默认，官方路径）| qwen（阿里百炼 Qwen-Omni-Realtime）
        buildConfigField("String", "REALTIME_PROVIDER", "\"${cup("realtime.provider", "openai")}\"")
        buildConfigField("String", "MEDIAPIPE_MODEL_PATH", "\"${cup("mediapipe.model.path")}\"")
        // 同仓 server/ 长期记忆地址（模拟器访问宿主机用 http://10.0.2.2:8000）；留空=NoOp
        buildConfigField("String", "MEMORY_ENDPOINT", "\"${cup("memory.endpoint")}\"")
        // 视觉帧实时上行总开关（V1：仅 qwen 画像实际发送，OpenAI 另有能力位门控）
        buildConfigField("boolean", "VISION_UPLINK_ENABLED", cupBool("vision.uplink", true).toString())
        // 历史留存与实时视觉独立，首版默认不写盘、不上传历史。
        buildConfigField("boolean", "HISTORY_ENABLED", cupBool("history.enabled", false).toString())
        buildConfigField("String", "HISTORY_ENDPOINT", "\"${cup("history.endpoint")}\"")
        buildConfigField("String", "HISTORY_API_TOKEN", "\"${cup("history.api.token")}\"")
        buildConfigField("String", "HISTORY_TIME_ZONE", "\"${cup("history.time.zone")}\"")
        buildConfigField("boolean", "SESSION_FRAME_ENABLED", cupBool("session.frame.enabled", false).toString())
        buildConfigField(
            "String",
            "SESSION_FRAME_ENDPOINT",
            "\"${cup("session.frame.endpoint")}\"",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get() }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildTypes {
        getByName("debug") {
            // Dedicated instrumentation owner; never mix disposable fixtures with a person's records.
            buildConfigField("String", "HISTORY_TEST_API_TOKEN", "\"${cup("history.test.api.token")}\"")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.runtime)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.kotlinx.coroutines.android)
    // sdk-core 的 HttpLongTermMemory 公共构造签名引用 OkHttpClient（其默认参数），顶层装配需可见
    implementation(libs.okhttp)

    implementation(project(":ui-presentation"))
    implementation(project(":sdk-core"))
    implementation(project(":core-media"))
    implementation(project(":core-transport"))
    implementation(project(":core-executor"))
    implementation(project(":hardware-bridge"))

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
