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

private val TruckRear = PhotoStep(PhotoKind.TRUCK_REAR, "Сфотографируйте грузовик сзади", "Кузов и госномер целиком в кадре")
private val TruckSide = PhotoStep(PhotoKind.TRUCK_SIDE, "Сфотографируйте грузовик сбоку", "Машина целиком, вместе с грузом")
private val Document = PhotoStep(
    PhotoKind.DOCUMENT,
    "Сфотографируйте документ",
    "Весь лист в кадре, подпись и печать должны читаться",
    allowMore = true
)

// Обязательные снимки этапа (решение автора 2026-10-27). null — этап без фото.
fun photoStepsFor(eventType: String): List<PhotoStep>? = when (eventType) {
    EventTypes.ZAGRUZILSYA_V_PUT -> listOf(TruckRear, TruckSide)
    EventTypes.RAZGRUZILSYA -> listOf(TruckRear, TruckSide, Document)
    else -> null
}

// Первый шаг, по которому нет ни одного снимка; null — сняты все.
fun nextMissingStep(steps: List<PhotoStep>, photos: List<CapturedPhoto>): Int? =
    steps.indexOfFirst { step -> photos.none { it.kind == step.kind } }.takeIf { it >= 0 }
