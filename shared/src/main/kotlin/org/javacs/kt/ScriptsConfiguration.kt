package org.javacs.kt

public data class ScriptsConfiguration(
    /** Whether .kts scripts are handled. */
    var enabled: Boolean = false,
    /** Whether .gradle.kts scripts are handled. Only considered if scripts are enabled in general. */
    var buildScriptsEnabled: Boolean = false,
    /** If true, include JDK symbols (constructors, Object members, java.* imports, etc.)
     *  when analysing .kts scripts.  Default = false (fast path, no JDK lookup). */
    var enableJdkSymbols: Boolean = false
)
