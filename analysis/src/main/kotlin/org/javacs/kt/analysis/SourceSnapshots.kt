package org.javacs.kt.analysis

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.SingleRootFileViewProvider
import com.intellij.testFramework.LightVirtualFile
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.psi.KtFile
import java.nio.file.Path

internal class SnapshotFile(val sourcePath: Path, text: String, version: Long) :
    LightVirtualFile(sourcePath.fileName.toString(), KotlinFileType.INSTANCE, text, version) {
    init { setWritable(false) }
    override fun getPath(): String = sourcePath.toString().replace('\\', '/')
    override fun getUrl(): String = sourcePath.toUri().toString()
}

internal data class SourceSnapshot(val path: Path, val version: Long, val file: KtFile)

internal class SourceSnapshots(private val project: Project) {
    private val entries = linkedMapOf<Path, SourceSnapshot>()
    val files: List<KtFile> get() = entries.values.map { it.file }
    var parseCount = 0
        private set

    operator fun get(path: Path): SourceSnapshot = entries.getValue(path.toAbsolutePath().normalize())

    fun parse(path: Path, text: String, version: Long): SourceSnapshot {
        val canonicalPath = path.toAbsolutePath().normalize()
        val virtualFile = SnapshotFile(canonicalPath, text, version)
        val view = SingleRootFileViewProvider(PsiManager.getInstance(project), virtualFile, true, KotlinFileType.INSTANCE)
        val file = checkNotNull(view.getPsi(KotlinLanguage.INSTANCE) as? KtFile)
        check(file.text == text)
        parseCount++
        return SourceSnapshot(canonicalPath, version, file)
    }

    fun publish(snapshot: SourceSnapshot) { entries[snapshot.path] = snapshot }
}
