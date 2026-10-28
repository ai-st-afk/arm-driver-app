package ru.profstroyservices.armdriver.data.repository

// Что на снимке. code уходит в 1С заголовком X-Photo-Kind — латиницей,
// кириллица в HTTP-заголовках ненадёжна (architecture.md §14 п.8).
enum class PhotoKind(val code: String) {
    TRUCK_REAR("truck_rear"),
    TRUCK_SIDE("truck_side"),
    DOCUMENT("document");

    companion object {
        fun fromCode(code: String): PhotoKind? = entries.firstOrNull { it.code == code }
    }
}

data class CapturedPhoto(val kind: PhotoKind, val path: String)
