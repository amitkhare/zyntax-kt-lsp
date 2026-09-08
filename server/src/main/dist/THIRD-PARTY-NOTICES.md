# Third-party notices

The server itself is MIT-licensed; see `LICENSE.txt`. Bundled libraries keep
their own licenses. The Kotlin compiler JAR also contains third-party code:
its Maven POM's Apache-2.0 declaration is not a license for every embedded file.

This inventory describes the pinned runtime, not build/test dependencies.
Upstream license texts and notices are retained under `licenses/`. The
`licenseReport` Gradle task provides a dependency-metadata report for review;
that report does not replace the embedded-code notices here.

## Runtime libraries

Apache-2.0 text is included at `licenses/kotlin/LICENSE.txt`. Copyright belongs
to the respective upstream authors and contributors; source links below identify
the projects. License alternatives below are selected only where upstream
expressly permits that choice.

| Bundled library | Version | Distribution license / source |
| --- | --- | --- |
| Kotlin compiler, reflect, stdlib/JDK7/JDK8, script-runtime, scripting-common/compiler/compiler-impl/JVM/JVM-host-unshaded, sam-with-receiver and power-assert plugins | 2.2.21 | Apache-2.0 plus embedded notices below; [Kotlin](https://github.com/JetBrains/kotlin/tree/v2.2.21) |
| kotlinx-coroutines-core-jvm | 1.10.1 | Apache-2.0; [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines/tree/1.10.1) |
| Exposed core, DAO and JDBC | 0.59.0 | Apache-2.0; [Exposed](https://github.com/JetBrains/Exposed/tree/0.59.0) |
| Gradle Tooling API | 8.12 | Apache-2.0; [Gradle](https://github.com/gradle/gradle/tree/v8.12.0) |
| Eclipse LSP4J and JSON-RPC | 1.0.0 | BSD-3-Clause/EDL-1.0 option; `licenses/lsp4j.txt` and `lsp4j-NOTICE.txt`; [source](https://github.com/eclipse-lsp4j/lsp4j/tree/v1.0.0) |
| Fernflower Java decompiler engine | 243.22562.218 | Apache-2.0; Copyright 2000-2024 JetBrains s.r.o. and contributors; [exact source/license declaration](https://github.com/JetBrains/intellij-community/blob/idea/243.22562.218/plugins/java-decompiler/engine/README.md) |
| ktfmt | b5d31d1 | Apache-2.0; `licenses/ktfmt.txt`, including its bundled google-java-format notice; [source](https://github.com/fwcd/ktfmt/tree/b5d31d1) |
| google-java-format | 1.8 | Apache-2.0; [source](https://github.com/google/google-java-format/tree/google-java-format-1.8) |
| Guava / failureaccess / empty listenablefuture marker | 33.4.0-jre / 1.0.2 / 9999.0-empty-to-avoid-conflict-with-guava | Apache-2.0; [Guava](https://github.com/google/guava) |
| Gson | 2.14.0 | Apache-2.0; [Gson](https://github.com/google/gson) |
| error_prone_annotations | 2.48.0 | Apache-2.0; [Error Prone](https://github.com/google/error-prone) |
| J2ObjC annotations | 3.0.0 | Apache-2.0; [J2ObjC](https://github.com/google/j2objc) |
| FindBugs jsr305 | 3.0.2 | Apache-2.0; [FindBugs](https://github.com/findbugsproject/findbugs) |
| JetBrains annotations | 24.0.0 | Apache-2.0; [java-annotations](https://github.com/JetBrains/java-annotations) |
| hash4j | 0.30.0 | Apache-2.0; [hash4j](https://github.com/dynatrace-oss/hash4j) |
| JCommander | 1.82 | Apache-2.0; [JCommander](https://github.com/cbeust/jcommander) |
| Java Native Access | 4.2.2 | Apache-2.0 option; `licenses/jna.txt`; native libffi MIT notice in `jna-libffi.txt`; [source](https://github.com/java-native-access/jna/tree/4.2.2) |
| JLine bundle | 3.24.1 | BSD-3-Clause; `licenses/jline.txt`; [source](https://github.com/jline/jline3/tree/jline-parent-3.24.1) |
| Checker Qual | 3.43.0 | MIT; `licenses/checker-qual.txt`; [Checker Framework](https://github.com/typetools/checker-framework) |
| jsoup | 1.17.2 | MIT; `licenses/jsoup.txt`; [jsoup](https://github.com/jhy/jsoup) |
| SLF4J API and simple provider | 2.0.17 | MIT; `licenses/slf4j.txt`; [SLF4J](https://github.com/qos-ch/slf4j) |
| SQLite JDBC | 3.49.1.0 | Apache-2.0 plus BSD-2-Clause Zentus code; `licenses/sqlite-NOTICE.txt`, `sqlite-zentus.txt`; [source](https://github.com/xerial/sqlite-jdbc/tree/3.49.1.0). SQLite itself is public-domain software. |

No H2 or test/benchmark library is part of this runtime inventory.

## Kotlin compiler and libraries

Kotlin 2.2.21 is distributed with its copyright, license and third-party
manifest under `licenses/kotlin/`. Those notices include BSD, Apache, Boost and
the Rhino-derived JavaScript parser's Netscape Public License 1.1 terms.
The parser remains unmodified in this distribution. Its covered source code,
including notices, is available under those terms in the pinned upstream tree:

- Source: https://github.com/JetBrains/kotlin/tree/v2.2.21/js/js.parser
- Complete corresponding source and build scripts:
  https://github.com/JetBrains/kotlin/tree/v2.2.21
- Source archive: https://github.com/JetBrains/kotlin/archive/refs/tags/v2.2.21.tar.gz

The NPL option is used for the dual-licensed Rhino-derived code. No server or
client license restricts recipients' rights to that covered source. This notice
does not apply the NPL to independent server or editor/client code. Any future
modification of covered files must retain their notices, document the changes,
and make the modified source available as required by the NPL. Distributors are
responsible for maintaining the source access and retention required by that
license, even when a third party hosts the source.

The compiler's additional embedded libraries are described by its pinned
[packaging script](https://github.com/JetBrains/kotlin/blob/v2.2.21/prepare/compiler/build.gradle.kts),
[versions](https://github.com/JetBrains/kotlin/blob/v2.2.21/gradle/versions.properties)
and [version catalog](https://github.com/JetBrains/kotlin/blob/v2.2.21/gradle/libs.versions.toml):

| Embedded component | License / notice |
| --- | --- |
| IntelliJ core 241.19416.19, JetBrains JNA/JNA-platform 5.9.0.26, Jansi 2.4.0, LZ4 Java 1.7.1, Fastutil 8.5.13-jb4, Guava, Vavr 0.10.4, StreamEx 0.7.2, immutable collections 0.3.7, OpenTelemetry API 1.41.0, Aalto XML 1.3.0, Log4j 1.2.17.2, javax.inject | Apache-2.0; retained component notices under `licenses/`; JNA's native libffi MIT notice is in `licenses/jna-embedded-libffi.txt` |
| ASM 9.6.1 and compiler code derived from ASM | BSD; `licenses/kotlin/third_party/asm_license.txt` |
| JLine 3.24.1 | BSD-3-Clause; `licenses/jline.txt` |
| StAX2 API 4.2.1 | BSD-2-Clause; `licenses/stax2-api.txt` and `stax2-BSD-2-Clause.txt` |
| JetBrains JDOM 2.0.6 | JDOM license (permissive, with naming restrictions); `licenses/jdom.txt`; exact source artifact below |
| Protocol Buffers 2.6.1 (relocated) | BSD-3-Clause; `licenses/protobuf.txt`; [upstream source](https://github.com/protocolbuffers/protobuf/tree/v2.6.1) |
| Native LZ4 code included by LZ4 Java 1.7.1 | BSD-2-Clause; `licenses/lz4-native.txt`; [pinned native source](https://github.com/lz4/lz4/tree/fdf2ef5809ca875c454510610764d9125ef2ebbd) |
| Rhino-derived GWT parser | NPL-1.1 option; `licenses/kotlin/third_party/rhino_LICENSE.txt` and source-access statement above |
| Dart-derived JavaScript AST, ThreeTen backport, Boost-derived math and asmble-derived compiler code | Corresponding BSD, Boost and MIT texts in `licenses/kotlin/third_party/` |

Some components are present both as compiler-embedded classes and as separate
JARs. This inventory records what upstream packages; it does not approve that
duplication as the final runtime layout.

## Notice provenance

- `licenses/kotlin/` preserves selected files from the exact Kotlin `v2.2.21`
  [`license` directory](https://github.com/JetBrains/kotlin/tree/v2.2.21/license).
  Its unmodified upstream README also describes components and test data not
  shipped here; it is provenance, not an assertion that every listed component
  is part of this server.
- Checker Qual, jsoup, SLF4J and SQLite Zentus texts were copied from their
  exact runtime JARs. SQLite's NOTICE comes from its `3.49.1.0` source tag.
- LSP4J LICENSE/NOTICE, JNA LICENSE, ktfmt LICENSE and JLine LICENSE come from
  the exact source versions listed above. The Apache option for embedded
  JetBrains JNA is also explicitly granted in its [5.9.0.26 published POM](https://cache-redirector.jetbrains.com/intellij-dependencies/org/jetbrains/intellij/deps/jna/jna/5.9.0.26/jna-5.9.0.26.pom).
- The older libffi notice comes from JNA `4.2.2`. The compiler's JNA notice is
  copied from the exact vendor `5.9.0.26` JAR (`licenses/jna-vendor.txt`), and
  its libffi notice is copied from the pinned [vendor source commit](https://github.com/JetBrains/intellij-deps-jna/blob/7ed4c7ec485c5a1a690b72205478b1efbbf942a6/native/libffi/LICENSE).
  All 23 JNA native assets retained in the compiler match the vendor JAR bytes;
  `Native.java` at that commit matches the published source JAR. The JDOM
  notice matches the license headers in its exact published `2.0.6` source
  artifact, including `org/jdom/Document.java` and `org/jdom/Element.java`.
- StAX2's notice specifies BSD-2-Clause and the FasterXML copyright; the full
  BSD-2-Clause terms are supplied separately. LZ4's native notice comes from
  the exact `src/lz4` submodule commit pinned by LZ4 Java `1.7.1`.

The vendor artifacts used for that provenance check are identified by SHA-256:

| Exact vendor artifact | SHA-256 |
| --- | --- |
| [JNA 5.9.0.26 binary](https://cache-redirector.jetbrains.com/intellij-dependencies/org/jetbrains/intellij/deps/jna/jna/5.9.0.26/jna-5.9.0.26.jar) | `95cce782fbabcb47d5b5c50ce39bb7359661dc2e0be78940705219e5a5d48fc5` |
| [JNA 5.9.0.26 sources](https://cache-redirector.jetbrains.com/intellij-dependencies/org/jetbrains/intellij/deps/jna/jna/5.9.0.26/jna-5.9.0.26-sources.jar) | `be0cafe7912c958953e081ec210293cc5b6daecac211b3c246ea67e23bfe64d6` |
| [JDOM 2.0.6 sources](https://cache-redirector.jetbrains.com/intellij-dependencies/org/jetbrains/intellij/deps/jdom/2.0.6/jdom-2.0.6-sources.jar) | `70e9f5b6d2d0f4e8049819bd5274761320e8ee55a2eb2b8462d1e7430c8440c0` |

## Distribution review

The inventory and license texts must be reconciled with the actual `lib/`
contents whenever dependency versions or compiler packaging change. Do not
remove embedded notices, strip compiler classes to avoid their licenses, or
describe the combined distribution as wholly MIT/Apache-2.0 licensed.
