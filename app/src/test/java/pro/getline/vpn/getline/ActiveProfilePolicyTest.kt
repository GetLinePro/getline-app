package pro.getline.vpn.getline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActiveProfilePolicyTest {
    @Test
    fun emptyImports_returnsNull() {
        assertNull(
            ActiveProfilePolicy.resolveUuidToActivate(
                activeUuid = null,
                importedUuids = emptyList(),
                managedUuid = "managed",
            )
        )
    }

    @Test
    fun validActive_returnsNull_noChange() {
        assertNull(
            ActiveProfilePolicy.resolveUuidToActivate(
                activeUuid = "a",
                importedUuids = listOf("a", "b"),
                managedUuid = "b",
            )
        )
    }

    @Test
    fun staleActive_restoresManagedWhenImported() {
        assertEquals(
            "managed",
            ActiveProfilePolicy.resolveUuidToActivate(
                activeUuid = "gone",
                importedUuids = listOf("other", "managed"),
                managedUuid = "managed",
            )
        )
    }

    @Test
    fun noManaged_doesNotPickSingleImport() {
        assertNull(
            ActiveProfilePolicy.resolveUuidToActivate(
                activeUuid = null,
                importedUuids = listOf("only"),
                managedUuid = null,
            )
        )
    }

    @Test
    fun managedNotImported_returnsNull() {
        assertNull(
            ActiveProfilePolicy.resolveUuidToActivate(
                activeUuid = null,
                importedUuids = listOf("a", "b"),
                managedUuid = "missing",
            )
        )
    }

    @Test
    fun blankManaged_ignored() {
        assertNull(
            ActiveProfilePolicy.resolveUuidToActivate(
                activeUuid = null,
                importedUuids = listOf("only"),
                managedUuid = "  ",
            )
        )
    }

    @Test
    fun nullActive_managedPresent_activatesManaged() {
        assertEquals(
            "managed",
            ActiveProfilePolicy.resolveUuidToActivate(
                activeUuid = null,
                importedUuids = listOf("other", "managed"),
                managedUuid = "managed",
            )
        )
    }
}
