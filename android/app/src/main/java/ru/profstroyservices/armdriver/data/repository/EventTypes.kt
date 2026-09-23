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
    // В контракте есть, но водитель сам его не отправляет: срыв и отмену
    // рейса/разнарядки решает диспетчер по звонку. Остаётся для событий,
    // записанных прежними версиями приложения.
    const val SRYV = "Срыв"
    const val OKONCHANIE_SMENY = "ОкончаниеСмены"

    // Порядок цикла кнопок одной ездки (AGENTS.md → «Флоу приложения», п.3).
    val TRIP_CYCLE = listOf(
        PRIBYL_NA_POGRUZKU,
        ZAGRUZILSYA_V_PUT,
        PRIBYL_NA_RAZGRUZKU,
        RAZGRUZILSYA
    )
}
