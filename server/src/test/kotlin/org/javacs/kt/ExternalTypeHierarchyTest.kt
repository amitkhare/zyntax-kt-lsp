package org.javacs.kt

import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TypeHierarchyItem
import org.eclipse.lsp4j.TypeHierarchyPrepareParams
import org.eclipse.lsp4j.TypeHierarchySupertypesParams
import org.eclipse.lsp4j.TypeHierarchySubtypesParams
import org.hamcrest.Matchers.*
import org.hamcrest.MatcherAssert.assertThat
import org.junit.Test

/**
 * Exercises type hierarchy across the source/JAR boundary. Uses a source class
 * extending a stdlib class (always on the classpath) and enables the symbol
 * index so JAR-backed relations are covered. Kept separate from the fast
 * source-only [TypeHierarchyTest] so the common suite stays quick.
 */
class ExternalTypeHierarchyTest : LanguageServerTestFixture(
    "externalhierarchy",
    Configuration().apply {
        indexing.enabled = true
        cache.workspaceCacheEnabled = false
    }
) {
    @Test
    fun `supertypes of source class include the JAR stdlib supertype`() {
        open("JdkHierarchy.kt")
        val item = requireNotNull(prepare("JdkHierarchy.kt", 3, 7))
        val result = supertypes(item)
        val names = result.map { it.name }
        assertThat(names, hasItem("AbstractList"))
    }

    @Test
    fun `expanding the external supertype node returns its own supertypes`() {
        open("JdkHierarchy.kt")
        val item = requireNotNull(prepare("JdkHierarchy.kt", 3, 7))
        val supertypes = supertypes(item)
        val abstractList = requireNotNull(supertypes.firstOrNull { it.name == "AbstractList" }) {
            "Expected AbstractList in supertypes $supertypes"
        }
        // AbstractList's direct supertypes include AbstractCollection / List; resolve across the JAR boundary.
        val result = supertypes(abstractList)
        val names = result.map { it.name }
        assertThat(names, hasItem("AbstractCollection"))
    }

    @Test
    fun `subtypes of a JAR class include the source class`() {
        open("JdkHierarchy.kt")
        // Ensure the file is compiled before querying subtypes.
        requireNotNull(prepare("JdkHierarchy.kt", 3, 7))
        // MyList directly extends kotlin.collections.AbstractList. Resolve AbstractList by FQN
        // and ask for its subtypes; the source scan should surface MyList as a direct subtype.
        val zeroRange = org.eclipse.lsp4j.Range(
            org.eclipse.lsp4j.Position(0, 0),
            org.eclipse.lsp4j.Position(0, 0)
        )
        val abstractListItem = TypeHierarchyItem(
            "AbstractList",
            org.eclipse.lsp4j.SymbolKind.Class,
            "kls:kotlin/collections/AbstractList.class",
            zeroRange,
            zeroRange
        )
        abstractListItem.data = "kotlin.collections.AbstractList"
        val result = subtypes(abstractListItem)
        val names = result.map { it.name }
        assertThat(names, hasItem("MyList"))
    }

    private fun prepare(file: String, line: Int, column: Int): TypeHierarchyItem? {
        val items = languageServer.textDocumentService.prepareTypeHierarchy(
            TypeHierarchyPrepareParams(
                TextDocumentIdentifier(workspaceRoot.resolve(file).toUri().toString()),
                position(line, column)
            )
        ).get()
        return items.firstOrNull()
    }

    private fun supertypes(item: TypeHierarchyItem): List<TypeHierarchyItem> {
        return languageServer.textDocumentService.typeHierarchySupertypes(TypeHierarchySupertypesParams(item)).get()
    }

    private fun subtypes(item: TypeHierarchyItem): List<TypeHierarchyItem> {
        return languageServer.textDocumentService.typeHierarchySubtypes(TypeHierarchySubtypesParams(item)).get()
    }
}
