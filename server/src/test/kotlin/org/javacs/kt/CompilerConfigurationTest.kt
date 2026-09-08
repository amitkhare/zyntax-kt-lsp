package org.javacs.kt

import com.google.gson.JsonParser
import org.javacs.kt.compiler.resolve
import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.config.CompilerConfiguration as KotlinCompilerConfiguration
import org.junit.Assert.*
import org.junit.Test

class CompilerConfigurationTest {
    @Test fun `defaults use normal pinned compiler features and reset the JVM target`() {
        val settings = CompilerConfiguration().resolve()
        assertEquals(LanguageVersion.LATEST_STABLE, settings.languageVersionSettings.languageVersion)
        assertEquals(ApiVersion.LATEST_STABLE, settings.languageVersionSettings.apiVersion)
        val normal = LanguageVersionSettingsImpl(LanguageVersion.LATEST_STABLE, ApiVersion.LATEST_STABLE)
        for (feature in LanguageFeature.entries) {
            assertEquals(feature.name, normal.getFeatureSupport(feature), settings.languageVersionSettings.getFeatureSupport(feature))
        }
        val native = KotlinCompilerConfiguration()
        CompilerConfiguration(JVMConfiguration("17"), "1.9", "1.8").resolve().applyTo(native)
        assertEquals(JvmTarget.JVM_17, native.get(JVMConfigurationKeys.JVM_TARGET))
        assertEquals(LanguageVersion.KOTLIN_1_9, native.get(CommonConfigurationKeys.LANGUAGE_VERSION_SETTINGS)?.languageVersion)
        assertEquals(ApiVersion.KOTLIN_1_8, native.get(CommonConfigurationKeys.LANGUAGE_VERSION_SETTINGS)?.apiVersion)
        settings.applyTo(native)
        assertEquals(JvmTarget.DEFAULT, native.get(JVMConfigurationKeys.JVM_TARGET))
        assertEquals(settings.languageVersionSettings, native.get(CommonConfigurationKeys.LANGUAGE_VERSION_SETTINGS))
    }

    @Test fun `reject unsupported or inconsistent compiler versions`() {
        for (version in listOf("", "default", "1.7", "2.3", "2.2.21", " 2.2")) {
            assertThrows(IllegalArgumentException::class.java) { CompilerConfiguration(languageVersion = version).resolve() }
            assertThrows(IllegalArgumentException::class.java) { CompilerConfiguration(apiVersion = version).resolve() }
        }
        assertThrows(IllegalArgumentException::class.java) { CompilerConfiguration(languageVersion = "1.9", apiVersion = "2.0").resolve() }
        assertThrows(IllegalArgumentException::class.java) { CompilerConfiguration(JVMConfiguration("unknown")).resolve() }
        assertEquals(ApiVersion.KOTLIN_1_9, CompilerConfiguration(languageVersion = "1.9").resolve().languageVersionSettings.apiVersion)
    }

    @Test fun `JSON compiler updates validate without mutating previous settings`() {
        val previous = CompilerConfiguration(JVMConfiguration("17"), "2.2", "2.2")
        fun options(json: String) = JsonParser.parseString(json).asJsonObject
        assertThrows(IllegalArgumentException::class.java) { previous.withOptions(options("""{"languageVersion":"1.9"}""")) }
        val updated = previous.withOptions(options("""{"languageVersion":"1.9","apiVersion":null}"""))
        assertEquals(CompilerConfiguration(JVMConfiguration("17"), "1.9"), updated)
        assertEquals(CompilerConfiguration(JVMConfiguration("17"), "2.2", "2.2"), previous)
        for (json in listOf("""{"languageVersion":2.2}""", """{"languageVersion":null}""", """{"apiVersion":true}""", """{"jvm":null}""", """{"jvm":{"target":17}}""")) {
            assertThrows(IllegalArgumentException::class.java) { previous.withOptions(options(json)) }
        }
    }
}
