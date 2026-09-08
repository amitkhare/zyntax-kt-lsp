package org.javacs.kt.j2k

private val platformImports = setOf(
    "java.util.List",
    "java.util.Set",
    "java.util.Map",
    "java.util.Collection",
    "java.lang.Integer",
    "java.lang.Long",
    "java.lang.Short",
    "java.lang.Byte",
    "java.lang.Character",
    "java.lang.Float",
    "java.lang.Double",
    "java.lang.Boolean",
    "java.lang.String",
    "java.lang.Object",
    "java.lang.Iterable"
)

fun isPlatformImport(fqJavaType: String) =
    platformImports.contains(fqJavaType)

fun platformType(javaType: String): String = when (javaType) {
    // Collection types
    "List" -> "MutableList"
    "Set" -> "MutableSet"
    "Map" -> "MutableMap"
    "Collection" -> "MutableCollection"
    "Iterable" -> "Iterable"

    // Boxed primitive types
    "Integer" -> "Int"
    "Long" -> "Long"
    "Short" -> "Short"
    "Byte" -> "Byte"
    "Character" -> "Char"
    "Float" -> "Float"
    "Double" -> "Double"
    "Boolean" -> "Boolean"

    // Other common types
    "String" -> "String"
    "Object" -> "Any"

    else -> javaType
}
