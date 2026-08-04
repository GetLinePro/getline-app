package pro.getline.vpn.getline

/**
 * Activates an already committed profile without turning a background transition
 * into a terminal failure.
 *
 * An unavailable result observed after the Activity stopped waits for the next
 * foreground and tries again. A foreground failure is terminal immediately.
 *
 * There is deliberately no global attempt cap across repeated foreground/background
 * cycles: every extra attempt requires another user-driven stop/start transition.
 * Activity destruction cancels the waiter and leaves the durable managed binding
 * for Home repair.
 */
internal suspend fun activateImportedProfileWhenForeground(
    isForeground: () -> Boolean,
    awaitForeground: suspend () -> Unit,
    activate: suspend () -> GetLineBackendResult<Boolean>,
): GetLineBackendResult<Boolean> {
    while (true) {
        awaitForeground()

        val result = activate()
        if (result != GetLineBackendResult.Unavailable || isForeground()) {
            return result
        }
        // The Activity stopped during the backend call. Do not show a terminal
        // error; the next iteration blocks until the user returns.
    }
}
