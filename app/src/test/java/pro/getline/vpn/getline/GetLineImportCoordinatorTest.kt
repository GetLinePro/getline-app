package pro.getline.vpn.getline

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.getline.vpn.getlineui.model.GetLineImportStage
import java.util.concurrent.atomic.AtomicInteger

class GetLineImportCoordinatorTest {
    @After
    fun tearDown() = runBlocking {
        withTimeout(2_000) {
            GetLineImportCoordinator.reset()
        }
    }

    @Test
    fun cancelledWaiter_doesNotKillImport_secondWaiterJoins() = runBlocking {
        var starts = 0
        val request = request("k1", "https://example.test/sub")

        val first = async {
            GetLineImportCoordinator.run(
                request = request,
                import = { onProgress ->
                    starts++
                    onProgress(GetLineImportStage.LoadingConfig)
                    delay(200)
                    GetLineBackendResult.Success(GetLineSubscriptionId("uuid-1"))
                },
            )
        }
        delay(30)
        first.cancel()

        val second = withTimeout(2_000) {
            GetLineImportCoordinator.run(
                request = request,
                import = { _ ->
                    starts++
                    GetLineBackendResult.Success(GetLineSubscriptionId("uuid-should-not-rerun"))
                },
            )
        }

        assertEquals(
            GetLineImportCoordinator.ImportTerminal.Success(GetLineSubscriptionId("uuid-1")),
            second,
        )
        assertEquals("import body must run once", 1, starts)
    }

    @Test
    fun onTerminal_runsOnceEvenIfNoLiveWaiter() = runBlocking {
        var terminals = 0
        val request = request("k2", "https://example.test/sub2")

        val waiter = async {
            GetLineImportCoordinator.run(
                request = request,
                import = {
                    delay(80)
                    GetLineBackendResult.Success(GetLineSubscriptionId("uuid-2"))
                },
                onTerminal = { terminals++ },
            )
        }
        delay(10)
        waiter.cancel()
        delay(200)

        assertEquals(1, terminals)
        assertTrue(!GetLineImportCoordinator.isInFlight())
    }

    @Test
    fun completedImport_doesNotReturnStaleSuccessOnSecondRun() = runBlocking {
        val request = request("k3", "https://example.test/sub3")
        var starts = 0
        val first = GetLineImportCoordinator.run(
            request = request,
            import = {
                starts++
                GetLineBackendResult.Success(GetLineSubscriptionId("uuid-a"))
            },
        )
        assertEquals(
            GetLineImportCoordinator.ImportTerminal.Success(GetLineSubscriptionId("uuid-a")),
            first,
        )
        delay(20)

        val second = GetLineImportCoordinator.run(
            request = request,
            import = {
                starts++
                GetLineBackendResult.Success(GetLineSubscriptionId("uuid-b"))
            },
        )
        assertEquals(
            GetLineImportCoordinator.ImportTerminal.Success(GetLineSubscriptionId("uuid-b")),
            second,
        )
        assertEquals(2, starts)
    }

    @Test
    fun differentKeys_supersedePriorImport_singleTerminal() = runBlocking {
        val terminals = AtomicInteger(0)
        val lastId = AtomicInteger(0)

        val a = async {
            GetLineImportCoordinator.run(
                request = request("ka", "https://example.test/a"),
                import = {
                    delay(300)
                    GetLineBackendResult.Success(GetLineSubscriptionId("uuid-a"))
                },
                onTerminal = {
                    terminals.incrementAndGet()
                    if (it is GetLineImportCoordinator.ImportTerminal.Success) {
                        lastId.set(1)
                    }
                },
            )
        }
        delay(30)
        val b = GetLineImportCoordinator.run(
            request = request("kb", "https://example.test/b"),
            import = {
                delay(50)
                GetLineBackendResult.Success(GetLineSubscriptionId("uuid-b"))
            },
            onTerminal = {
                terminals.incrementAndGet()
                if (it is GetLineImportCoordinator.ImportTerminal.Success) {
                    lastId.set(2)
                }
            },
        )

        val aResult = a.await()
        assertEquals(GetLineImportCoordinator.ImportTerminal.Superseded, aResult)
        assertEquals(
            GetLineImportCoordinator.ImportTerminal.Success(GetLineSubscriptionId("uuid-b")),
            b,
        )
        // Only B's success onTerminal (A cancelled before terminal or gen invalid).
        assertEquals(2, lastId.get())
        assertEquals(1, terminals.get())
    }

    @Test
    fun reset_invalidatesOnTerminalAfterLogout() = runBlocking {
        var terminals = 0
        val request = request("k-logout", "https://example.test/logout")

        val waiter = async {
            GetLineImportCoordinator.run(
                request = request,
                import = {
                    delay(150)
                    GetLineBackendResult.Success(GetLineSubscriptionId("uuid-late"))
                },
                onTerminal = { terminals++ },
            )
        }
        delay(30)
        GetLineImportCoordinator.reset()
        delay(200)
        val result = waiter.await()

        assertEquals(GetLineImportCoordinator.ImportTerminal.Superseded, result)
        assertEquals("onTerminal must not run after reset", 0, terminals)
    }

    @Test
    fun reset_waitsForInFlightOnTerminal_thenBlocksNewCommit() = runBlocking {
        val writes = AtomicInteger(0)
        val enteredTerminal = CompletableDeferred<Unit>()
        val releaseTerminal = CompletableDeferred<Unit>()
        val request = request("k-fence", "https://example.test/fence")

        val waiter = async {
            GetLineImportCoordinator.run(
                request = request,
                import = {
                    GetLineBackendResult.Success(GetLineSubscriptionId("uuid-fence"))
                },
                onTerminal = {
                    enteredTerminal.complete(Unit)
                    // Simulate slow persistence; reset must wait here.
                    releaseTerminal.await()
                    writes.incrementAndGet()
                },
            )
        }

        withTimeout(2_000) { enteredTerminal.await() }

        val resetJob = async {
            GetLineImportCoordinator.reset()
        }
        // Give reset a chance to block on terminalCommit.
        delay(50)
        assertTrue("reset must not finish while onTerminal holds the fence", resetJob.isActive)

        releaseTerminal.complete(Unit)
        withTimeout(2_000) { resetJob.await() }
        waiter.await()

        assertEquals(1, writes.get())

        // After reset, a completed late producer path must not accept new terminal
        // without a fresh run — next run starts clean.
        var secondTerminals = 0
        GetLineImportCoordinator.run(
            request = request("k-fence-2", "https://example.test/fence2"),
            import = {
                GetLineBackendResult.Success(GetLineSubscriptionId("uuid-after"))
            },
            onTerminal = { secondTerminals++ },
        )
        assertEquals(1, secondTerminals)
    }

    @Test
    fun join_requiresNotCompleted() = runBlocking {
        // Complete-before-clear race: second run with same key after settle must re-import.
        val request = request("k-join", "https://example.test/join")
        var starts = 0
        GetLineImportCoordinator.run(
            request = request,
            import = {
                starts++
                GetLineBackendResult.Success(GetLineSubscriptionId("uuid-1"))
            },
        )
        val second = GetLineImportCoordinator.run(
            request = request,
            import = {
                starts++
                GetLineBackendResult.Success(GetLineSubscriptionId("uuid-2"))
            },
        )
        assertNotEquals(
            GetLineImportCoordinator.ImportTerminal.Success(GetLineSubscriptionId("uuid-1")),
            // if stale join happened, second would be uuid-1 with starts==1
            if (starts == 1) second else null,
        )
        assertEquals(2, starts)
        assertEquals(
            GetLineImportCoordinator.ImportTerminal.Success(GetLineSubscriptionId("uuid-2")),
            second,
        )
    }

    @Test
    fun importKey_stableForSameInputs() {
        val a = GetLineImportCoordinator.importKey("https://x", "sub", GetLineSubscriptionId("u"))
        val b = GetLineImportCoordinator.importKey("https://x", "sub", GetLineSubscriptionId("u"))
        assertEquals(a, b)
    }

    private fun request(key: String, source: String) =
        GetLineImportCoordinator.ImportRequest(
            key = key,
            draft = GetLineSubscriptionDraft(
                type = GetLineSubscriptionType.Url,
                name = "t",
                source = source,
            ),
            reuseId = null,
        )
}
