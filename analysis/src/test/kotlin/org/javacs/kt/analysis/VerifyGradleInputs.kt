@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class, org.jetbrains.kotlin.analysis.api.KaPlatformInterface::class)

package org.javacs.kt.analysis

import org.javacs.kt.project.GradleCompilation
import org.javacs.kt.project.GradleCompilerOptions
import org.javacs.kt.project.GradleCompilerPluginOption
import org.javacs.kt.project.GradleProjectModel
import org.javacs.kt.project.GradleSourceSet
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinProjectStructureProvider
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibraryModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.platform.jvm.JdkPlatform
import java.nio.file.Path

/** Tests the evaluated-input boundary without invoking Gradle or reading/writing source files. */
internal fun verifyGradleInputs(root: Path, jdk: Path, classpath: List<Path>) {
    val buildRoot = canonical(root.resolve("evaluated-inputs"))
    val binaryRoots = (classpath.map(::canonical) + listOf(canonical(root))).distinct()
    check(binaryRoots.size >= 2) { "The fixture needs distinct binary roots to verify friend isolation" }
    val mainPath = buildRoot.resolve("src/main/Main.kt")
    val testPath = buildRoot.resolve("src/test/MainTest.kt")
    val releasePath = buildRoot.resolve("src/release/Release.kt")
    val sourceText = mapOf(
        mainPath to "package evaluated\ninternal fun answer(): Int = 42\n",
        testPath to "package evaluated\nfun checkedAnswer(): Int = answer()\n",
        releasePath to "package releaseOnly\nfun releaseAnswer(): Int = 0\n",
    )
    val defaults = GradleCompilerOptions(
        languageVersion = "2.2", apiVersion = "2.2", jvmTarget = "17", jvmDefault = "enable",
        noJdk = false, javaParameters = false, allWarningsAsErrors = false, suppressWarnings = false,
        verbose = false, moduleName = "explicit-default", optIn = emptyList(), progressiveMode = false,
        freeCompilerArgs = emptyList(),
    )
    val unset = GradleCompilerOptions(null, null, null, null, null, null, null, null, null, null, null, null, null)
    val mainOptions = defaults.copy(languageVersion = "1.8", apiVersion = "1.8", jvmTarget = "1.8",
        jvmDefault = "disable", moduleName = "compiled-main")
    val testOptions = defaults.copy(apiVersion = "2.1", moduleName = "compiled-test",
        javaParameters = true, allWarningsAsErrors = true, progressiveMode = true)

    fun compilation(name: String, file: Path, options: GradleCompilerOptions) = GradleCompilation(
        id = ":app:jvm:$name", projectPath = ":app", projectDirectory = buildRoot.resolve("app"),
        target = "jvm", name = name, kotlinRoots = listOf(file.parent), javaRoots = emptyList(),
        kotlinFiles = listOf(file), javaFiles = emptyList(), generatedRoots = null,
        classpath = binaryRoots, outputs = listOf(buildRoot.resolve("build/classes/$name")),
        sourceSets = listOf(GradleSourceSet(name, listOf(file.parent), emptyList())),
        associatedCompilations = emptyList(), friendPaths = emptyList(),
        javaCompileHome = canonical(jdk), compilerOptions = options,
        compilerPluginClasspath = emptyList(), compilerPluginOptions = emptyList(),
    )
    val main = compilation("main", mainPath, mainOptions)
    val orderedTestBinaries = binaryRoots.reversed()
    val test = compilation("test", testPath, testOptions).copy(
        associatedCompilations = listOf(main.id),
        classpath = orderedTestBinaries.take(1) + main.outputs + orderedTestBinaries.drop(1),
        friendPaths = main.outputs + orderedTestBinaries.take(1),
    )
    // An inactive variant may overlap main; its files must not be loaded into the selected graph.
    val release = compilation("release", releasePath, defaults).copy(kotlinFiles = listOf(mainPath, releasePath))
    val model = GradleProjectModel(buildRoot, "8.12", canonical(jdk), listOf(main, test, release))
    fun id(compilation: GradleCompilation) = CompilationId(buildRoot, compilation.id)
    val inputs = CompilationAnalysisInputs("2.2.21", canonical(jdk), defaults, emptyList())
    val selected = linkedMapOf(id(main) to inputs, id(test) to inputs)
    val reads = mutableListOf<Path>()
    val specs = evaluatedModules(listOf(model), selected) { path ->
        reads.add(path)
        sourceText.getValue(path)
    }
    check(reads == listOf(mainPath, testPath)) { "Inactive variant sources were read: $reads" }
    check(specs.map { it.name } == selected.keys.map { it.moduleName })
    val mainSpec = specs[0]
    val testSpec = specs[1]
    check(mainSpec.files == mapOf(mainPath to sourceText.getValue(mainPath)))
    check(testSpec.files == mapOf(testPath to sourceText.getValue(testPath)))
    check(mainSpec.dependencies == binaryRoots.map { ModuleDependency.Binary(it) } && mainSpec.friends.isEmpty())
    check(testSpec.dependencies == orderedTestBinaries.take(1).map { ModuleDependency.Binary(it) } +
        ModuleDependency.Source(mainSpec.name) + orderedTestBinaries.drop(1).map { ModuleDependency.Binary(it) })
    check(testSpec.friends == listOf(ModuleDependency.Binary(orderedTestBinaries.first()), ModuleDependency.Source(mainSpec.name)))
    check(mainSpec.stableName == "compiled-main" && testSpec.stableName == "compiled-test")
    check(mainSpec.language.languageVersion == LanguageVersion.KOTLIN_1_8)
    check(mainSpec.language.apiVersion == ApiVersion.KOTLIN_1_8)
    check(testSpec.language.languageVersion == LanguageVersion.KOTLIN_2_2)
    check(testSpec.language.apiVersion == ApiVersion.KOTLIN_2_1)
    check(mainSpec.jvmTarget == JvmTarget.JVM_1_8 && testSpec.jvmTarget == JvmTarget.JVM_17)
    check(specs.all { it.jdkHome == canonical(jdk) })
    // These are the canonical project-model DTOs, including request-level diagnostic policy.
    val retainedOptions: GradleCompilerOptions = checkNotNull(testSpec.compilerOptions)
    check(mainSpec.compilerOptions == mainOptions && retainedOptions == testOptions)
    check(retainedOptions.allWarningsAsErrors == true && retainedOptions.javaParameters == true)
    check(model.compilations == listOf(main, test, release)) { "Projection mutated evaluated inputs" }

    FirWorkspace(specs).use { workspace ->
        fun module(path: Path) = workspace.read(path) { file ->
            KotlinProjectStructureProvider.getModule(file.project, file, useSiteModule = null) as KaSourceModule
        }
        val mainModule = module(mainPath)
        val testModule = module(testPath)
        check(mainModule.directRegularDependencies.none { it is KaSourceModule })
        check(mainModule.directFriendDependencies.isEmpty())
        check(testModule.directRegularDependencies.filterIsInstance<KaSourceModule>().single() === mainModule)
        check(testModule.directFriendDependencies.filterIsInstance<KaSourceModule>().single() === mainModule)
        for ((spec, sourceModule) in listOf(mainSpec to mainModule, testSpec to testModule)) {
            check(sourceModule.name == spec.name && sourceModule.stableModuleName == spec.stableName)
            check(sourceModule.languageVersionSettings === spec.language)
            check((sourceModule.targetPlatform.single() as JdkPlatform).targetVersion == spec.jvmTarget)
            val ordered = sourceModule.directRegularDependencies.filterNot { it is KaLibraryModule && it.isSdk }.map { dependency ->
                when (dependency) {
                    is KaLibraryModule -> ModuleDependency.Binary(dependency.binaryRoots.single())
                    is KaSourceModule -> ModuleDependency.Source(dependency.name)
                    else -> error("Unexpected dependency: $dependency")
                }
            }
            check(ordered == spec.dependencies) { "Mixed source/binary dependency order changed: $ordered" }
            val libraries = sourceModule.directRegularDependencies.filterIsInstance<KaLibraryModule>().filterNot { it.isSdk }
            check(libraries.map { it.binaryRoots.single() } == spec.dependencies.filterIsInstance<ModuleDependency.Binary>().map { it.path })
            val friends = sourceModule.directFriendDependencies.filterIsInstance<KaLibraryModule>()
            check(friends.map { it.binaryRoots.single() } == spec.friends.filterIsInstance<ModuleDependency.Binary>().map { it.path })
            check(friends.all { friend -> libraries.any { it === friend } }) { "Friend root has a second module identity" }
        }
        val mainLibraries = mainModule.directRegularDependencies.filterIsInstance<KaLibraryModule>().filterNot { it.isSdk }
        val testLibraries = testModule.directRegularDependencies.filterIsInstance<KaLibraryModule>().filterNot { it.isSdk }
        check(mainLibraries.all { library -> testLibraries.any { it === library } }) { "Shared roots lost module identity" }
        check(testModule.directFriendDependencies.filterIsInstance<KaLibraryModule>().size == 1) {
            "Binary friendship escaped the exact evaluated root"
        }
        val friendCall = workspace.read(testPath, ::inspect)
        check(friendCall.target == "evaluated.answer" && friendCall.errors.isEmpty()) { "Source friend cannot access internal declaration: $friendCall" }
        workspace.reimport(specs.map { if (it.name == testSpec.name) it.copy(friends = emptyList()) else it })
        val ordinaryCall = workspace.read(testPath, ::inspect)
        check(ordinaryCall.errors.any { "INVISIBLE_REFERENCE" in it }) { "Ordinary dependency gained friend access: $ordinaryCall" }
        val androidModel = model.copy(compilations = listOf(main, test.copy(associatedCompilations = emptyList()), release))
        val androidSpecs = evaluatedModules(listOf(androidModel), selected +
            (id(test) to inputs.copy(regularDependencies = listOf(id(main)))), sourceText::getValue)
        check(androidSpecs[1].friends == testSpec.friends) { "Binary output friendship was lost during source substitution" }
        workspace.reimport(androidSpecs)
        val androidFriend = workspace.read(testPath, ::inspect)
        check(androidFriend.errors.isEmpty() && androidFriend.target == "evaluated.answer") { "Android-style source friendship lost: $androidFriend" }
    }
    println("PASS evaluated compilation selection, settings, ordered binaries and exact friend identities")

    fun withCompilation(compilation: GradleCompilation) = model.copy(compilations = model.compilations.map {
        if (it.id == compilation.id) compilation else it
    })
    fun reject(
        label: String,
        expected: String,
        build: GradleProjectModel = model,
        selections: Map<CompilationId, CompilationAnalysisInputs> = selected,
    ) {
        val failure = runCatching { evaluatedModules(listOf(build), selections, sourceText::getValue) }.exceptionOrNull()
        check(failure is IllegalArgumentException && failure.message.orEmpty().contains(expected)) {
            "$label: expected rejection containing '$expected', got $failure"
        }
    }
    reject("unknown compilation", "Unknown selected compilation",
        selections = selected + (CompilationId(buildRoot, ":missing:jvm:main") to inputs))
    reject("unselected friend", "Every source dependency must be selected", selections = mapOf(id(test) to inputs))
    reject("overlapping selected variants", "overlapping sources", selections = selected + (id(release) to inputs))
    reject("unsupported compiler", "Unsupported analysis compiler",
        selections = selected + (id(main) to inputs.copy(compilerVersion = "2.1.21")))
    reject("Java source", "Java source analysis is not integrated",
        withCompilation(main.copy(javaFiles = listOf(mainPath.resolveSibling("JavaSource.java")))))
    reject("script source", "Script analysis needs per-script configuration",
        withCompilation(main.copy(kotlinFiles = listOf(mainPath.resolveSibling("build.gradle.kts")))))
    reject("plugin classpath", "Compiler plugin analysis is not integrated",
        withCompilation(main.copy(compilerPluginClasspath = binaryRoots.take(1))))
    reject("plugin options", "Compiler plugin analysis is not integrated",
        withCompilation(main.copy(compilerPluginOptions = listOf(GradleCompilerPluginOption("plugin", "key", "value")))))
    reject("free compiler arguments", "Free compiler argument analysis is not integrated",
        withCompilation(main.copy(compilerOptions = mainOptions.copy(freeCompilerArgs = listOf("-Xjsr305=strict")))))
    reject("unavailable binary friend", "Friend binaries must belong to the evaluated classpath",
        withCompilation(test.copy(friendPaths = listOf(buildRoot.resolve("unavailable-friend.jar")))))
    reject("invalid language", "Invalid compiler settings",
        withCompilation(main.copy(compilerOptions = mainOptions.copy(languageVersion = "99.0"))))
    reject("API newer than language", "Invalid compiler settings",
        withCompilation(main.copy(compilerOptions = mainOptions.copy(apiVersion = "2.2"))))
    reject("invalid JVM target", "Unsupported JVM target",
        withCompilation(main.copy(compilerOptions = mainOptions.copy(jvmTarget = "999"))))
    reject("removed JVM target", "Unsupported JVM target",
        withCompilation(main.copy(compilerOptions = mainOptions.copy(jvmTarget = "1.6"))))
    // Both Gradle's JVM and JavaCompile's JDK are populated, but neither authorizes a Kotlin JDK guess.
    reject("missing Kotlin JDK", "Explicit Kotlin JDK and noJdk disagree",
        selections = selected + (id(main) to inputs.copy(kotlinJdkHome = null)))
    reject("contradictory noJdk", "Explicit Kotlin JDK and noJdk disagree",
        withCompilation(main.copy(compilerOptions = mainOptions.copy(noJdk = true))))
    val noJdk = evaluatedModules(listOf(withCompilation(main.copy(compilerOptions = mainOptions.copy(noJdk = true)))),
        mapOf(id(main) to inputs.copy(kotlinJdkHome = null)), sourceText::getValue).single()
    check(noJdk.jdkHome == null && noJdk.dependencies == binaryRoots.map { ModuleDependency.Binary(it) })

    val explicitDefaults = evaluatedModules(listOf(withCompilation(main.copy(compilerOptions = unset))),
        mapOf(id(main) to inputs), sourceText::getValue).single()
    check(explicitDefaults.compilerOptions == defaults && explicitDefaults.stableName == defaults.moduleName)
    val missingDefaults = listOf(
        "languageVersion" to defaults.copy(languageVersion = null),
        "apiVersion" to defaults.copy(apiVersion = null),
        "jvmTarget" to defaults.copy(jvmTarget = null),
        "jvmDefault" to defaults.copy(jvmDefault = null),
        "noJdk" to defaults.copy(noJdk = null),
        "javaParameters" to defaults.copy(javaParameters = null),
        "allWarningsAsErrors" to defaults.copy(allWarningsAsErrors = null),
        "suppressWarnings" to defaults.copy(suppressWarnings = null),
        "verbose" to defaults.copy(verbose = null),
        "moduleName" to defaults.copy(moduleName = null),
        "optIn" to defaults.copy(optIn = null),
        "progressiveMode" to defaults.copy(progressiveMode = null),
        "freeCompilerArgs" to defaults.copy(freeCompilerArgs = null),
    )
    for ((name, incompleteDefaults) in missingDefaults) {
        reject("missing $name", "Missing explicit compiler input: $name",
            withCompilation(main.copy(compilerOptions = unset)),
            mapOf(id(main) to inputs.copy(compilerDefaults = incompleteDefaults)))
    }
    println("PASS evaluated inputs reject unsupported or ambiguous semantics and require explicit defaults/JDK")
}
