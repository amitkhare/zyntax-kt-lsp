package org.javacs.kt.completion

val callPattern = Regex("""(.*?\$\d+:.*?|\w+)\(.*\)""")
val methodSignature = Regex("""(?:fun|constructor) (?:<(?:[a-zA-Z\?\!\: ]+)(?:, [A-Z])*> )?([a-zA-Z]+\(.*\))""")
val importPattern = Regex("import ((\\w+\\.)*)[\\w*]*")
val packagePattern = Regex("package ((\\w+\\.)*)[\\w*]*")
val getterPattern = Regex("(get|is)[A-Z]\\w+")
val setterPattern = Regex("set[A-Z]\\w+")
val dotPattern = Regex(".*\\.")
val dotWordPattern = Regex(".*\\.\\w+")
val wordPattern = Regex("\\w+")
