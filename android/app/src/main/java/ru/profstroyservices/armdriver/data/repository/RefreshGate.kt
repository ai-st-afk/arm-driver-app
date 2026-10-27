package ru.profstroyservices.armdriver.data.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Одновременные подписчики ждут один запрос; следующий отдельный вызов
// снова идёт в сеть, поэтому push и возврат на экран не теряют свежесть.
internal class RefreshGate {
    private val mutex = Mutex()
    @Volatile private var generation = 0L
    private var lastResult: Result<Unit> = Result.success(Unit)

    suspend fun run(force: Boolean = false, refresh: suspend () -> Result<Unit>): Result<Unit> {
        val observed = generation
        return mutex.withLock {
            if (!force && observed != generation) return@withLock lastResult
            refresh().also {
                lastResult = it
                generation++
            }
        }
    }
}
