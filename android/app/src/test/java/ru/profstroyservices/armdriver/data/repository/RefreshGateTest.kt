package ru.profstroyservices.armdriver.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RefreshGateTest {
    @Test
    fun `push during request fetches again after the in flight response`() = runTest {
        val gate = RefreshGate()
        val response = CompletableDeferred<Unit>()
        var requests = 0
        val screen = async { gate.run { requests++; response.await(); Result.success(Unit) } }
        runCurrent()
        val push = async { gate.run(force = true) { requests++; Result.success(Unit) } }
        runCurrent()
        response.complete(Unit)
        screen.await()
        push.await()
        assertEquals(2, requests)
    }

    @Test
    fun `concurrent screens share one request but next refresh fetches again`() = runTest {
        val gate = RefreshGate()
        val response = CompletableDeferred<Unit>()
        var requests = 0
        val screens = List(20) {
            async {
                gate.run {
                    requests++
                    response.await()
                    Result.success(Unit)
                }
            }
        }
        runCurrent()
        assertEquals(1, requests)
        response.complete(Unit)
        assertTrue(screens.awaitAll().all { it.isSuccess })
        assertEquals(1, requests)
        gate.run { requests++; Result.success(Unit) }
        assertEquals(2, requests)
    }

    @Test
    fun `failed request is shared and later retry is allowed`() = runTest {
        val gate = RefreshGate()
        val response = CompletableDeferred<Unit>()
        val failure = IllegalStateException("network failure")
        var requests = 0
        val callers = List(3) {
            async { gate.run { requests++; response.await(); Result.failure(failure) } }
        }
        runCurrent()
        response.complete(Unit)
        callers.awaitAll().forEach { assertSame(failure, it.exceptionOrNull()) }
        assertEquals(1, requests)
        assertTrue(gate.run { requests++; Result.success(Unit) }.isSuccess)
        assertEquals(2, requests)
    }

    @Test
    fun `cancelled screen does not block another waiting screen`() = runTest {
        val gate = RefreshGate()
        val pending = CompletableDeferred<Unit>()
        val first = async { gate.run { pending.await(); Result.success(Unit) } }
        runCurrent()
        var retried = false
        val second = async { gate.run { retried = true; Result.success(Unit) } }
        runCurrent()
        first.cancel()
        assertTrue(second.await().isSuccess)
        assertTrue(retried)
    }
}
