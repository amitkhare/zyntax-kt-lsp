package org.javacs.kt

import org.javacs.kt.util.winCompatiblePathOf
import org.junit.Assert.assertEquals
import org.junit.Test

class UtilsTest {
    @Test
    fun `winCompatiblePathOf strips leading slash from Windows drive paths`() {
        val path = winCompatiblePathOf("/c:\\Users\\winlogon\\App.kt")

        assertEquals("c:\\Users\\winlogon\\App.kt", path.toString())
    }

    @Test
    fun `winCompatiblePathOf passes through Unix paths unchanged`() {
        val original = "/home/user/App.kt"

        assertEquals(original, winCompatiblePathOf(original).toString())
    }
}
