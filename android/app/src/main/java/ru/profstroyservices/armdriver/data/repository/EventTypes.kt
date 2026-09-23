package ru.profstroyservices.armdriver.data.repository

// Значения `<Тип>` по контракту (AGENTS.md → «События и их уровни»).
// `ПринялЗадание` в контракте есть, но в этом проекте исключён — цикл
// ездки начинается сразу с `ПрибылНаПогрузку`.
object EventTypes {
    const val OZNAKOMLENIE = "Ознакомление"
    const val NACHALO_SMENY = "НачалоСмены"
    const val PRIBYL_NA_POGRUZKU = "ПрибылНаПогрузку"
    const val ZAGRUZILSYA_V_PUT = "ЗагрузилсяВПуть"
    const val PRIBYL_NA_RAZGRUZKU = "ПрибылНаРазгрузку"
    const val RAZGRUZILSYA = "Разгрузился"
    const val SRYV = "Срыв"
    const val OKONCHANIE_SMENY = "ОкончаниеСмены"

    // Не в контракте 1С — вопрос отправлен их разработчику (см. DEVLOG),
    // ответа пока нет. Название по аналогии с уже принятыми именами
    // (НачалоСмены, Срыв). Если 1С попросит другое имя — поменять здесь,
    // больше нигде тип события строкой не завязан.
    const val OTKAZ_OT_RAZNARYADKI = "ОтказОтРазнарядки"

    // Порядок цикла кнопок одной ездки (AGENTS.md → «Флоу приложения», п.3).
    val TRIP_CYCLE = listOf(
        PRIBYL_NA_POGRUZKU,
        ZAGRUZILSYA_V_PUT,
        PRIBYL_NA_RAZGRUZKU,
        RAZGRUZILSYA
    )
}
