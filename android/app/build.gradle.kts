import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// google-services падает на этапе конфигурации, если google-services.json
// отсутствует — а до создания Firebase-проекта и добавления в него этого
// приложения взять его неоткуда (см. DEVLOG). Пока файла нет, пуши просто
// не инициализируются (собственные Firebase-вызовы обёрнуты runCatching),
// но сама сборка не ломается. Как появится файл — положить в app/ и плагин
// подключится сам, без правки этого файла.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

fun localProperty(key: String, default: String): String =
    (localProperties.getProperty(key) ?: default)

android {
    namespace = "ru.profstroyservices.armdriver"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.profstroyservices.armdriver"
        minSdk = 33
        targetSdk = 35
        versionCode = 17
        versionName = "0.4.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String",
            "GATEWAY_BASE_URL",
            "\"${localProperty("gateway.baseUrl", "https://arm-driver.profstroy-services.ru/")}\""
        )
        buildConfigField(
            "String",
            "GATEWAY_MOBILE_TOKEN",
            "\"${localProperty("gateway.mobileToken", "")}\""
        )
        // Номера диспетчера в контракте с 1С нет, поэтому он приходит из
        // конфигурации сборки. Пусто — кнопка звонка просто не показывается.
        buildConfigField(
            "String",
            "DISPATCHER_PHONE",
            "\"${localProperty("dispatcher.phone", "")}\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.retrofit.core)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)

    // BOM+messaging компилируются независимо от google-services.json — тот
    // нужен только плагину, который генерирует ресурсы для автостарта
    // Firebase. Без него FirebaseMessaging просто не проинициализируется
    // (см. комментарий у apply(plugin) выше).
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    testImplementation(libs.junit)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
