plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    // apply false здесь и применяется условно в app/build.gradle.kts — сам
    // плагин падает на конфигурации, если google-services.json отсутствует
    // (см. комментарий там), а до Firebase-проекта его взять неоткуда.
    alias(libs.plugins.google.services) apply false
}
