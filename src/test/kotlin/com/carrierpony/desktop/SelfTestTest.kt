// SelfTestTest.kt
// The selftest verb is what a user runs to prove a packaged build works, so the suite runs it
// too: a selftest that fails under `./gradlew test` would fail on their machine.

package com.carrierpony.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class SelfTestTest {

    @Test
    fun selfTestPasses() {
        assertEquals(0, SelfTest.run())
    }
}
