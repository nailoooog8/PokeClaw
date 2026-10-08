// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.utils

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Authenticated encryption for short secrets that have to survive in a file a
 * human may open (MCP server tokens in mcp_servers.json).
 *
 * The key lives in the Android Keystore and never enters app memory in
 * exportable form, so the ciphertext on disk is the only copy an attacker with
 * filesystem or adb access can get. Encrypted values carry [PREFIX] so a
 * file mixing encrypted and hand-written plaintext entries stays readable.
 *
 * Failures are reported, never swallowed into a plaintext fallback: writing the
 * secret back in the clear would silently undo the reason this exists.
 */
object SecretBox {

    const val PREFIX = "enc:v1:"

    private const val TAG = "SecretBox"
    private const val KEY_ALIAS = "pokeclaw_mcp_secret"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val GCM_TAG_BITS = 128

    fun isEncrypted(value: String?): Boolean = value != null && value.startsWith(PREFIX)

    /** Returns [PREFIX]-tagged ciphertext, or null if the keystore refused. */
    fun encrypt(plain: String): String? {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX +
                Base64.encodeToString(iv, Base64.NO_WRAP) + "." +
                Base64.encodeToString(ct, Base64.NO_WRAP)
        } catch (e: Exception) {
            XLog.e(TAG, "Secret encryption failed", e)
            null
        }
    }

    /** Decrypts a [PREFIX]-tagged value; returns null if it cannot be read. */
    fun decrypt(stored: String): String? {
        if (!isEncrypted(stored)) return stored
        return try {
            val body = stored.removePrefix(PREFIX)
            val ivPart = body.substringBefore('.')
            val ctPart = body.substringAfter('.', "")
            if (ctPart.isEmpty()) {
                XLog.e(TAG, "Encrypted value has no ciphertext part")
                return null
            }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(GCM_TAG_BITS, Base64.decode(ivPart, Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(ctPart, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            XLog.e(TAG, "Secret decryption failed", e)
            null
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Deliberately not setUserAuthenticationRequired: the agent has to
                // call MCP servers while the screen is locked.
                .build()
        )
        return generator.generateKey()
    }
}
