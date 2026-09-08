package org.javacs.kt.externalsources

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class KlsURITest {
    @Test
    fun `archivePath preserves plus sign in jar name`() {
        // Regression #326: a '+' in a classpath jar name must be preserved across the
        // kls:// -> archivePath -> toJarURL() round-trip, never form-decoded to a space.
        val kls = URI.create("kls:file:///local/foo+bar.jar!/com/example/Class.class").toKlsURI()!!
        val path = kls.archivePath
        assertTrue(path.toString().contains("foo+bar.jar"))
        assertFalse(path.toString().contains("foo bar.jar"))
    }
}
