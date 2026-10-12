package ru.profstroyservices.armdriver.data.repository

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueRepositoryTest {

    @Test
    fun `transient pending count resolved within grace never surfaces`() = runTest {
        val source = flow {
            emit(QueueState(pendingCount = 1))
            delay(2_000)
            emit(QueueState(pendingCount = 0))
        }

        val result = source.withQuietGrace(6_000).toList()

        assertEquals(listOf(QueueState(pendingCount = 0)), result)
    }

    @Test
    fun `pending count that outlives the grace period is surfaced`() = runTest {
        val result = flowOf(QueueState(pendingCount = 1)).withQuietGrace(6_000).toList()

        assertEquals(listOf(QueueState(pendingCount = 1)), result)
    }

    @Test
    fun `confirmed network failure surfaces immediately`() = runTest {
        val failed = QueueState(pendingCount = 1, networkError = "Нет связи с сервером. Проверьте интернет. (no_connection)")

        val result = flowOf(failed).withQuietGrace(6_000).toList()

        assertEquals(listOf(failed), result)
    }

    @Test
    fun `confirmed event rejection surfaces immediately`() = runTest {
        val rejected = QueueState(pendingCount = 1, rejectionReason = "Рейс не найден (one_c_rejected)")

        val result = flowOf(rejected).withQuietGrace(6_000).toList()

        assertEquals(listOf(rejected), result)
    }

    @Test
    fun `empty queue surfaces immediately`() = runTest {
        val result = flowOf(QueueState(pendingCount = 0)).withQuietGrace(6_000).toList()

        assertEquals(listOf(QueueState(pendingCount = 0)), result)
    }
}
