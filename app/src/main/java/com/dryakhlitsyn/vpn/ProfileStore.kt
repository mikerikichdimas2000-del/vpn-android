package com.dryakhlitsyn.vpn

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keeps the imported client profile encrypted with a key held by Android Keystore. */
object ProfileStore {
    private const val KEY_ALIAS = "vpn-profile-v1"
    private const val FILE_NAME = "vpn-profile.bin"

    fun hasProfile(context: Context): Boolean = profileFile(context).isFile

    fun save(context: Context, profileText: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(profileText.toByteArray(Charsets.UTF_8))
        val bytes = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { data ->
                data.writeInt(cipher.iv.size)
                data.write(cipher.iv)
                data.write(encrypted)
            }
            buffer.toByteArray()
        }
        val target = profileFile(context)
        val temporary = File(context.filesDir, "$FILE_NAME.tmp")
        temporary.writeBytes(bytes)
        if (target.exists() && !target.delete()) {
            temporary.delete()
            throw IllegalStateException("Не удалось заменить сохранённый профиль")
        }
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw IllegalStateException("Не удалось сохранить профиль")
        }
    }

    fun load(context: Context): String {
        val encoded = profileFile(context).readBytes()
        val input = DataInputStream(ByteArrayInputStream(encoded))
        val ivSize = input.readInt()
        if (ivSize !in 12..16 || encoded.size <= 4 + ivSize) {
            throw IllegalStateException("Сохранённый профиль повреждён — импортируйте его заново")
        }
        val iv = ByteArray(ivSize).also(input::readFully)
        val encrypted = ByteArray(input.available()).also(input::readFully)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }

    fun clear(context: Context) {
        profileFile(context).delete()
    }

    private fun profileFile(context: Context) = File(context.filesDir, FILE_NAME)

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }
}
