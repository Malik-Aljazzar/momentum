package com.momentum.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Worded server failures (no JSON from the server) and the King of Reporters badge's explanation; the same
 *  texts as web/tests/unit/api.test.ts and web/tests/e2e/badges.spec.ts. */
class MessagesTest {
    @Test
    fun serverDownIsWordedWithTheCode() {
        assertTrue(De.serverDown(502).contains("502") && De.serverDown(502).contains("Neustart"))
        assertTrue(En.serverDown(503).contains("503") && En.serverDown(503).contains("restart"))
        assertEquals("Fehler 418 – der Server hat keine Erklärung mitgeschickt", De.serverError(418))
        assertEquals("Error 418 – the server sent no explanation", En.serverError(418))
    }
}
