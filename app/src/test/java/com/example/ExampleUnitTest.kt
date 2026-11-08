package com.example

import com.example.crypto.ApkSignatureParser
import com.example.crypto.KeyStoreManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM unit tests for cryptographic algorithms and key management.
 */
class ExampleUnitTest {

    @Test
    fun verifyKeyStoreManagerHasDefaultAospKeys() {
        val keys = KeyStoreManager.getAvailableKeys()
        assertTrue("Available keys should not be empty", keys.isNotEmpty())
        val platformKey = keys.find { it.id == "aosp_platform" }
        assertNotNull("AOSP Platform key must be present", platformKey)
        assertTrue("Platform key must be marked as AOSP built-in", platformKey!!.isAospBuiltIn)
    }

    @Test
    fun verifyFingerprintFormatting() {
        val bytes = byteArrayOf(0x12.toByte(), 0xAB.toByte(), 0xCD.toByte())
        val formatted = ApkSignatureParser.formatFingerprint(bytes)
        assertEquals("12:AB:CD", formatted)
    }

    @Test
    fun verifyCustomKeyGeneration() {
        val customKey = KeyStoreManager.generateCustomKey(
            aliasName = "Test Custom Key",
            commonName = "Custom Signer Test",
            organization = "Test Lab"
        )
        assertNotNull(customKey)
        assertEquals("Test Custom Key", customKey.name)
        assertTrue(customKey.certFingerprintSha256.isNotBlank())
    }
}
