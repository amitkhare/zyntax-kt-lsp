package org.javacs.kt

import org.eclipse.lsp4j.*
import org.hamcrest.Matchers.*
import org.hamcrest.MatcherAssert.assertThat
import org.junit.Test

class PrepareTypeHierarchyTest : SingleFileTestFixture("typehierarchy", "Simple.kt") {
    @Test
    fun `prepares type hierarchy for class`() {
        val item = prepare("Simple.kt", 1, 12)
        assertThat(item, notNullValue())
        assertThat(item?.name, equalTo("SimpleClass"))
        assertThat(item?.kind, equalTo(SymbolKind.Class))
    }

    @Test
    fun `prepares type hierarchy for subclass`() {
        val item = prepare("Simple.kt", 2, 7)
        assertThat(item, notNullValue())
        assertThat(item?.name, equalTo("SubClass"))
        assertThat(item?.kind, equalTo(SymbolKind.Class))
    }

    @Test
    fun `prepares type hierarchy for interface`() {
        val item = prepare("Simple.kt", 5, 11)
        assertThat(item, notNullValue())
        assertThat(item?.name, equalTo("SimpleInterface"))
        assertThat(item?.kind, equalTo(SymbolKind.Interface))
    }

    @Test
    fun `prepares type hierarchy for object`() {
        val item = prepare("Simple.kt", 8, 12)
        assertThat(item, notNullValue())
        assertThat(item?.name, equalTo("Singleton"))
        assertThat(item?.kind, equalTo(SymbolKind.Object))
    }

    private fun prepare(file: String, line: Int, column: Int): TypeHierarchyItem? {
        val items = languageServer.textDocumentService.prepareTypeHierarchy(prepareParams(file, line, column)).get()
        return items.firstOrNull()
    }

    private fun prepareParams(relativePath: String, line: Int, column: Int): TypeHierarchyPrepareParams {
        val file = workspaceRoot.resolve(relativePath)
        val fileId = TextDocumentIdentifier(file.toUri().toString())
        return TypeHierarchyPrepareParams(fileId, position(line, column))
    }
}

class SupertypesTest : SingleFileTestFixture("typehierarchy", "Supertypes.kt") {
    @Test
    fun `finds direct superclass only`() {
        val item = requireNotNull(prepare("Supertypes.kt", 7, 7))
        val result = supertypes(item)
        val names = result.map { it.name }
        // Direct-only: Dog -> Pet (NOT transitive Animal)
        assertThat(names, hasItem("Pet"))
        assertThat(names, not(hasItem("Animal")))
    }

    @Test
    fun `expanding Pet yields its direct supertypes`() {
        val dog = requireNotNull(prepare("Supertypes.kt", 7, 7))
        val dogSupertypes = supertypes(dog)
        val petItem = requireNotNull(dogSupertypes.firstOrNull { it.name == "Pet" }) {
            "Expected Pet in supertypes $dogSupertypes"
        }
        val result = supertypes(petItem)
        val names = result.map { it.name }
        assertThat(names, hasItem("Animal"))
    }

    @Test
    fun `finds direct interface supertypes`() {
        val item = requireNotNull(prepare("Supertypes.kt", 13, 7))
        val result = supertypes(item)
        val names = result.map { it.name }
        // Direct parents only: Pet, CanFetch (Not transitive CanSpeak/Animal)
        assertThat(names, hasItem("Pet"))
        assertThat(names, hasItem("CanFetch"))
        assertThat(names, not(hasItem("Animal")))
        assertThat(names, not(hasItem("CanSpeak")))
    }

    @Test
    fun `finds interface supertypes for interface`() {
        val item = requireNotNull(prepare("Supertypes.kt", 12, 11))
        val result = supertypes(item)
        val names = result.map { it.name }
        assertThat(names, hasItem("CanSpeak"))
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
}

class TypeAliasHierarchyTest : SingleFileTestFixture("typehierarchy", "TypeAliases.kt") {
    @Test
    fun `prepares type hierarchy from typealias declaration`() {
        val item = requireNotNull(prepare("TypeAliases.kt", 4, 11))
        assertThat(item.name, equalTo("Base"))
        assertThat(item.kind, equalTo(SymbolKind.Class))
    }

    @Test
    fun `prepares type hierarchy from typealias usage at call site`() {
        val item = requireNotNull(prepare("TypeAliases.kt", 7, 13))
        assertThat(item.name, equalTo("Base"))
    }

    @Test
    fun `prepares type hierarchy from chained typealias`() {
        val item = requireNotNull(prepare("TypeAliases.kt", 5, 14))
        assertThat(item.name, equalTo("Base"))
    }

    @Test
    fun `finds subtypes through typealias`() {
        val item = requireNotNull(prepare("TypeAliases.kt", 4, 11))
        val result = subtypes(item)
        val names = result.map { it.name }
        assertThat(names, hasItem("Derived"))
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

class SubtypesTest : SingleFileTestFixture("typehierarchy", "Subtypes.kt") {
    @Test
    fun `finds direct subtypes of interface`() {
        val item = requireNotNull(prepare("Subtypes.kt", 1, 11))
        val result = subtypes(item)
        val names = result.map { it.name }
        assertThat(names, hasItem("Circle"))
        assertThat(names, hasItem("Square"))
    }

    @Test
    fun `finds direct subtypes of open class`() {
        val item = requireNotNull(prepare("Subtypes.kt", 11, 11))
        val result = subtypes(item)
        val names = result.map { it.name }
        // Direct-only: Vehicle -> Car (NOT transitive Sedan/SUV)
        assertThat(names, hasItem("Car"))
        assertThat(names, not(hasItem("Sedan")))
        assertThat(names, not(hasItem("SUV")))
    }

    @Test
    fun `expanding Car yields its direct subtypes`() {
        val vehicle = requireNotNull(prepare("Subtypes.kt", 11, 11))
        val vehicleSubtypes = subtypes(vehicle)
        val carItem = requireNotNull(vehicleSubtypes.firstOrNull { it.name == "Car" }) {
            "Expected Car in subtypes $vehicleSubtypes"
        }
        val result = subtypes(carItem)
        val names = result.map { it.name }
        assertThat(names, hasItem("Sedan"))
        assertThat(names, hasItem("SUV"))
    }

    @Test
    fun `finds direct subtypes of car class`() {
        val item = requireNotNull(prepare("Subtypes.kt", 12, 11))
        val result = subtypes(item)
        val names = result.map { it.name }
        assertThat(names, hasItem("Sedan"))
        assertThat(names, hasItem("SUV"))
    }

    @Test
    fun `leaf class has no subtypes`() {
        val item = requireNotNull(prepare("Subtypes.kt", 4, 7))
        val result = subtypes(item)
        assertThat(result, empty())
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

    private fun subtypes(item: TypeHierarchyItem): List<TypeHierarchyItem> {
        return languageServer.textDocumentService.typeHierarchySubtypes(TypeHierarchySubtypesParams(item)).get()
    }
}

class SealedTypeHierarchyTest : SingleFileTestFixture("typehierarchy", "Sealed.kt") {
    @Test
    fun `subtypes of sealed interface are all known children`() {
        val item = requireNotNull(prepare("Sealed.kt", 1, 17))
        val result = subtypes(item)
        val names = result.map { it.name }
        assertThat(names, hasItem("Number"))
        assertThat(names, hasItem("Add"))
        assertThat(names, hasItem("UnitExpr"))
        assertThat(result, hasSize(3))
    }

    @Test
    fun `sealed subtype is found even without import`() {
        val item = requireNotNull(prepare("Sealed.kt", 1, 17))
        val result = subtypes(item)
        // Ensures the precise compiler-known set is used, not a package scan.
        assertThat(result.map { it.name }, hasItem("Add"))
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

    private fun subtypes(item: TypeHierarchyItem): List<TypeHierarchyItem> {
        return languageServer.textDocumentService.typeHierarchySubtypes(TypeHierarchySubtypesParams(item)).get()
    }
}

class ConstructorDelegationTest : SingleFileTestFixture("callhierarchy", "Constructors.kt") {
    @Test
    fun `secondary constructor super delegation is an outgoing call`() {
        val item = requireNotNull(prepare("Constructors.kt", 7, 7)) // Derived() : super()
        val outgoing = outgoing(item)
        val names = outgoing.map { it.to.name }
        // super() delegates to Base's constructor
        assertThat(names, hasItem("Base"))
    }

    @Test
    fun `secondary constructor this delegation is an outgoing call`() {
        val item = requireNotNull(prepare("Constructors.kt", 8, 7)) // Derived(x) : this()
        val outgoing = outgoing(item)
        val names = outgoing.map { it.to.name }
        assertThat(names, hasItem("Derived"))
    }

    @Test
    fun `super delegation appears as incoming call to Base constructor`() {
        val item = requireNotNull(prepare("Constructors.kt", 2, 5)) // Base constructor()
        val incoming = incoming(item)
        val names = incoming.map { it.from.name }
        assertThat(names, hasItem("Derived"))
    }

    private fun prepare(file: String, line: Int, column: Int): CallHierarchyItem? {
        val items = languageServer.textDocumentService.prepareCallHierarchy(
            CallHierarchyPrepareParams(
                TextDocumentIdentifier(workspaceRoot.resolve(file).toUri().toString()),
                position(line, column)
            )
        ).get()
        return items.firstOrNull()
    }

    private fun outgoing(item: CallHierarchyItem): List<CallHierarchyOutgoingCall> {
        return languageServer.textDocumentService.callHierarchyOutgoingCalls(CallHierarchyOutgoingCallsParams(item)).get()
    }

    private fun incoming(item: CallHierarchyItem): List<CallHierarchyIncomingCall> {
        return languageServer.textDocumentService.callHierarchyIncomingCalls(CallHierarchyIncomingCallsParams(item)).get()
    }
}
