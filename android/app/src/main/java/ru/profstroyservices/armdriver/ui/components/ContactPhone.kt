package ru.profstroyservices.armdriver.ui.components

import ru.profstroyservices.armdriver.data.network.ContactPhoneDto

private val dialNumberPattern = Regex("\\+?[0-9]+")

// Нормализацию делает 1С. Представление может быть текстом без номера —
// его нельзя использовать как запасную ссылку для звонка.
fun ContactPhoneDto.dialNumber(): String? = number.takeIf { dialNumberPattern.matches(it) }
