package org.javacs.kt

import org.eclipse.lsp4j.*
import org.hamcrest.Matchers.*
import org.hamcrest.MatcherAssert.assertThat
import org.junit.Test

class PrepareCallHierarchyTest : SingleFileTestFixture("callhierarchy", "Simple.kt") {

    @Test
    fun `prepares call hierarchy item for function declaration`() {
        val item = prepare("Simple.kt", 1, 5)
        assertThat(item, notNullValue())
        assertThat(item?.name, equalTo("a"))
        assertThat(item?.kind, equalTo(SymbolKind.Function))
    }

    @Test
    fun `prepares call hierarchy item for function with no calls`() {
        val item = prepare("Simple.kt", 6, 5)
        assertThat(item, notNullValue())
        assertThat(item?.name, equalTo("b"))
    }

    @Test
    fun `prepares call hierarchy item from call site`() {
        val item = prepare("Simple.kt", 2, 5)
        assertThat(item, notNullValue())
        assertThat(item?.name, equalTo("b"))
    }

    @Test
    fun `returns empty for uncalled function`() {
        val item = prepare("Simple.kt", 19, 5)
        assertThat(item, notNullValue())
        assertThat(item?.name, equalTo("e"))
    }

    @Test
    fun `prepares call hierarchy item for property`() {
        val item = prepare("Simple.kt", 27, 5)
        assertThat(item, notNullValue())
        assertThat(item?.name, equalTo("x"))
        assertThat(item?.kind, equalTo(SymbolKind.Property))
    }

    private fun prepare(file: String, line: Int, column: Int): CallHierarchyItem? {
        val items = languageServer.textDocumentService.prepareCallHierarchy(prepareParams(file, line, column)).get()
        return items.firstOrNull()
    }

    private fun prepareParams(relativePath: String, line: Int, column: Int): CallHierarchyPrepareParams {
        val file = workspaceRoot.resolve(relativePath)
        val fileId = TextDocumentIdentifier(file.toUri().toString())
        return CallHierarchyPrepareParams(fileId, position(line, column))
    }
}

class IncomingCallsTest : SingleFileTestFixture("callhierarchy", "Simple.kt") {

    @Test
    fun `finds single incoming caller`() {
        val item = prepare("Simple.kt", 10, 5) ?: return
        val incoming = incoming(item)
        // c() is called by a()
        assertThat(incoming, hasSize(1))
        assertThat(incoming[0].from.name, equalTo("a"))
        assertThat(incoming[0].fromRanges, hasSize(1))
    }

    @Test
    fun `finds multiple incoming callers`() {
        val item = prepare("Simple.kt", 6, 5) ?: return
        val incoming = incoming(item)
        // b() is called by a() and d()
        val callerNames = incoming.map { it.from.name }
        assertThat(callerNames, hasItem("a"))
        assertThat(callerNames, hasItem("d"))
    }

    @Test
    fun `finds multiple call sites from same caller`() {
        val item = prepare("Simple.kt", 6, 5) ?: return
        val incoming = incoming(item)
        // d() calls b() twice
        val dCallers = incoming.filter { it.from.name == "d" }
        assertThat(dCallers, hasSize(1))
        assertThat(dCallers[0].fromRanges, hasSize(2))
    }

    @Test
    fun `returns empty for function with no callers`() {
        val item = prepare("Simple.kt", 19, 5) ?: return
        val incoming = incoming(item)
        // e() is never called
        assertThat(incoming, empty())
    }

    @Test
    fun `finds callers for property`() {
        val item = prepare("Simple.kt", 27, 5) ?: return
        val incoming = incoming(item)
        // x is used by f()
        assertThat(incoming, hasSize(1))
        assertThat(incoming[0].from.name, equalTo("f"))
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

    private fun incoming(item: CallHierarchyItem): List<CallHierarchyIncomingCall> {
        return languageServer.textDocumentService.callHierarchyIncomingCalls(CallHierarchyIncomingCallsParams(item)).get()
    }
}

class OutgoingCallsTest : SingleFileTestFixture("callhierarchy", "Simple.kt") {

    @Test
    fun `finds outgoing calls`() {
        val item = prepare("Simple.kt", 1, 5) ?: return
        val outgoing = outgoing(item)
        // a() calls b() and c()
        assertThat(outgoing, hasSize(2))
        val calleeNames = outgoing.map { it.to.name }
        assertThat(calleeNames, hasItem("b"))
        assertThat(calleeNames, hasItem("c"))
    }

    @Test
    fun `returns empty for function with no calls`() {
        val item = prepare("Simple.kt", 6, 5) ?: return
        val outgoing = outgoing(item)
        // b() has no outgoing calls
        assertThat(outgoing, empty())
    }

    @Test
    fun `finds outgoing call ranges from callee b`() {
        val item = prepare("Simple.kt", 1, 5) ?: return
        val outgoing = outgoing(item)
        // a() calls b() once
        val bCalls = outgoing.filter { it.to.name == "b" }
        assertThat(bCalls, hasSize(1))
        assertThat(bCalls[0].fromRanges, hasSize(1))
    }

    @Test
    fun `does not include non-call references in outgoing`() {
        val item = prepare("Simple.kt", 29, 5) ?: return
        val outgoing = outgoing(item)
        // g() has ::b (non-call) and b() (call) -- only b() should appear
        assertThat(outgoing, hasSize(1))
        assertThat(outgoing[0].to.name, equalTo("b"))
    }

    @Test
    fun `finds outgoing calls from property initializer`() {
        val item = prepare("Simple.kt", 34, 5) ?: return
        val outgoing = outgoing(item)
        // y's initializer calls b()
        assertThat(outgoing, hasSize(1))
        assertThat(outgoing[0].to.name, equalTo("b"))
    }

    @Test
    fun `finds outgoing calls from secondary constructor`() {
        val item = prepare("Simple.kt", 37, 5) ?: return
        val outgoing = outgoing(item)
        // constructor body calls b()
        assertThat(outgoing, hasSize(1))
        assertThat(outgoing[0].to.name, equalTo("b"))
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
}

class CrossFileCallHierarchyTest : LanguageServerTestFixture("callhierarchy") {

    @Test
    fun `prepares call hierarchy in multi-file workspace`() {
        open("Simple.kt")
        open("Calls.kt")

        val items = languageServer.textDocumentService.prepareCallHierarchy(
            CallHierarchyPrepareParams(
                TextDocumentIdentifier(workspaceRoot.resolve("Calls.kt").toUri().toString()),
                position(1, 5)
            )
        ).get()

        assertThat(items, hasSize(1))
        assertThat(items[0].name, equalTo("caller"))
    }

    @Test
    fun `finds incoming calls across files`() {
        open("Simple.kt")
        open("Calls.kt")

        val item = requireNotNull(prepare("Simple.kt", 1, 5))
        val incoming = languageServer.textDocumentService
            .callHierarchyIncomingCalls(CallHierarchyIncomingCallsParams(item)).get()

        // a() is called by crossFileCaller() in Calls.kt
        val callerNames = incoming.map { it.from.name }
        assertThat(callerNames, hasItem("crossFileCaller"))
    }

    @Test
    fun `finds outgoing calls across files`() {
        open("Simple.kt")
        open("Calls.kt")

        val item = requireNotNull(prepare("Calls.kt", 9, 5))
        val outgoing = languageServer.textDocumentService
            .callHierarchyOutgoingCalls(CallHierarchyOutgoingCallsParams(item)).get()

        // crossFileCaller() calls a() in Simple.kt
        val calleeNames = outgoing.map { it.to.name }
        assertThat(calleeNames, hasItem("a"))
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
}
