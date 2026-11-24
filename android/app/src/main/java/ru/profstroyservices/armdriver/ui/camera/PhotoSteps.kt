package ru.profstroyservices.armdriver.ui.camera

import ru.profstroyservices.armdriver.data.repository.CapturedPhoto
import ru.profstroyservices.armdriver.data.repository.EventTypes
import ru.profstroyservices.armdriver.data.repository.PhotoKind

data class PhotoStep(
    val kind: PhotoKind,
    val prompt: String,
    val hint: String,
    // Накладная бывает на нескольких листах — после снимка можно доснять ещё.
    val allowMore: Boolean = false
)

private val Cargo = PhotoStep(PhotoKind.CARGO, "Сфотографируйте груз", "Покажите причину отказа клиента", allowMore = true)
private val VehicleSides = listOf("спереди", "сзади", "слева", "справа")

// Фото остаётся частью срыва «Претензия к качеству», но не этапов ездки.
fun photoStepsFor(eventType: String): List<PhotoStep>? = when (eventType) {
    EventTypes.SRYV -> listOf(Cargo)
    PhotoKind.VEHICLE_KEYS.code -> VehicleSides.map { PhotoStep(PhotoKind.VEHICLE_KEYS, "Машина $it", "После выдачи ключей. В кадре должна быть вся сторона машины") }
    PhotoKind.VEHICLE_MEDIC.code -> VehicleSides.map { PhotoStep(PhotoKind.VEHICLE_MEDIC, "Машина $it", "Перед осмотром у медика. В кадре должна быть вся сторона машины") }
    PhotoKind.FREE.code -> listOf(PhotoStep(PhotoKind.FREE, "Отправить фото диспетчеру", "Сфотографируйте нужную деталь"))
    else -> null
}

// Первый шаг, по которому нет ни одного снимка; null — сняты все.
fun nextMissingStep(steps: List<PhotoStep>, photos: List<CapturedPhoto>): Int? =
    steps.indexOfFirst { step -> photos.none { it.kind == step.kind } }.takeIf { it >= 0 }
