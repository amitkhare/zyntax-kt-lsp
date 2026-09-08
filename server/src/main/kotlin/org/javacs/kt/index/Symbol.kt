package org.javacs.kt.index

import org.jetbrains.kotlin.name.FqName

data class Symbol(
    val fqName: FqName,
    val kind: Kind,
    val visibility: Visibility,
    val extensionReceiverType: FqName?,
    val location: Location? = null
) {
    /**
     * Represents the source location of a symbol.
     *
     * NOTE: This field is currently stored in the database but NOT YET POPULATED
     * during indexing. The index is updated incrementally when files are edited
     * (see SourcePath.refreshWorkspaceIndexes), so this data could remain current
     * for actively-edited files.
     *
     * For authoritative source locations (especially for stale entries), use
     * PSI-based lookups such as GoToDefinition, FindReferences, or the
     * workspaceSymbols function in Symbols.kt.
     *
     * TODO: Populate this field during index updates by extracting source location
     * from DeclarationDescriptor.findPsi(). This would enable fast jump-to-definition
     * from completion results and reduce reliance on PSI-based lookups.
     */
    data class Location(
        val uri: String,
        val startLine: Int,
        val startCharacter: Int,
        val endLine: Int,
        val endCharacter: Int
    )

    enum class Kind(val rawValue: Int) {
        CLASS(0),
        INTERFACE(1),
        FUNCTION(2),
        VARIABLE(3),
        MODULE(4),
        ENUM(5),
        ENUM_MEMBER(6),
        CONSTRUCTOR(7),
        FIELD(8),
        UNKNOWN(9);

        companion object {
            fun fromRaw(rawValue: Int) = entries.firstOrNull { it.rawValue == rawValue } ?: UNKNOWN
        }
    }

    enum class Visibility(val rawValue: Int) {
        PRIVATE_TO_THIS(0),
        PRIVATE(1),
        INTERNAL(2),
        PROTECTED(3),
        PUBLIC(4),
        UNKNOWN(5);

        companion object {
            fun fromRaw(rawValue: Int) = entries.firstOrNull { it.rawValue == rawValue } ?: UNKNOWN
        }
    }
}
