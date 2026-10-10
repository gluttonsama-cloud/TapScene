package com.tapscene.hosting

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Android production implementation; host fakes do not verify Keystore behavior. */
class AndroidKeystoreSessionVault(context: Context) : SessionVault {
    private val root = File(context.applicationContext.noBackupFilesDir.canonicalFile, "hosted-v1")
    private val file = File(root, "session.enc")
    private val pending = File(root, "pending-sessions")
    override fun load(): HostedModels.Session? = synchronized(lock) { readEncrypted(file) }
    private fun readEncrypted(file: File): HostedModels.Session? {
        if (!file.exists()) return null
        try {
            check(root.canonicalFile == root && file.canonicalFile == file &&
                Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && file.length() in 30L..8192L)
            val bytes = Files.readAllBytes(file.toPath())
            val data = ByteBuffer.wrap(bytes)
            check(data.int == VERSION)
            val iv = ByteArray(12).also { data.get(it) }
            val encrypted = ByteArray(data.remaining()).also { data.get(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, iv))
            cipher.updateAAD(aad(file))
            val plaintext = cipher.doFinal(encrypted)
            try {
                val value = HostedApi.`object`(HostedJson.parse(plaintext, 4096))
                check(HostedApi.string(value, "endpoint") == HostedApi.ENDPOINT)
                val account = HostedApi.account(HostedApi.`object`(value["account"]))
                val token = HostedApi.string(value, "sessionToken")
                check(token.matches(Regex("[A-Za-z0-9_-]{43}")))
                return HostedModels.Session(account, HostedApi.iso(HostedApi.string(value, "expiresAt")), token)
            } finally { plaintext.fill(0) }
        } catch (e: Exception) { throw IOException("SESSION_VAULT_UNAVAILABLE", e) }
    }
    override fun save(session: HostedModels.Session) = synchronized(lock) { saveEncrypted(session, file) }
    private fun saveEncrypted(session: HostedModels.Session, file: File) {
        try {
            check(root.canonicalFile == root && (root.isDirectory || root.mkdirs()))
            HostedApi.id(session.account.accountId)
            HostedApi.syntheticEmail(session.account.email)
            HostedApi.iso(session.expiresAt)
            check(session.bearerToken().matches(Regex("[A-Za-z0-9_-]{43}")))
            val plain = HostedApi.json(HostedApi.map("endpoint", HostedApi.ENDPOINT,
                "account", HostedApi.map("accountId", session.account.accountId, "email", session.account.email),
                "expiresAt", session.expiresAt, "sessionToken", session.bearerToken()))
            val bytes = try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
                cipher.updateAAD(aad(file))
                val encrypted = cipher.doFinal(plain)
                check(cipher.iv.size == 12)
                ByteBuffer.allocate(4 + 12 + encrypted.size).putInt(VERSION).put(cipher.iv).put(encrypted).array()
            } finally { plain.fill(0) }
            check(file.canonicalFile == file)
            val directory = file.parentFile!!
            check(directory.canonicalFile == directory && (directory.isDirectory || directory.mkdirs()))
            val temporary = File(directory, "session.${UUID.randomUUID()}.tmp")
            try {
                FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                syncDirectory(directory)
                syncDirectory(root)
            } finally { Files.deleteIfExists(temporary.toPath()) }
        } catch (e: Exception) { throw IOException("SESSION_VAULT_UNAVAILABLE", e) }
    }
    override fun clearIfToken(token: String) = synchronized(lock) {
        val current = load() ?: return@synchronized
        if (MessageDigest.isEqual(current.bearerToken().toByteArray(Charsets.US_ASCII), token.toByteArray(Charsets.US_ASCII))) {
            Files.delete(file.toPath())
            syncDirectory(root)
        }
    }
    override fun queueRevocation(session: HostedModels.Session) = synchronized(lock) {
        saveEncrypted(session, pendingFile(session.bearerToken()))
    }
    override fun pendingRevocations(): List<HostedModels.Session> = synchronized(lock) {
        if (!pending.exists()) return@synchronized emptyList()
        check(pending.canonicalFile == pending)
        pending.listFiles()?.filter { it.name.matches(Regex("[0-9a-f]{64}\\.enc")) }?.map { entry ->
            val session = readEncrypted(entry) ?: throw IOException("SESSION_VAULT_UNAVAILABLE")
            check(entry == pendingFile(session.bearerToken()))
            session
        } ?: throw IOException("SESSION_VAULT_UNAVAILABLE")
    }
    override fun removePendingToken(token: String) = synchronized(lock) {
        val entry = pendingFile(token)
        if (entry.exists()) {
            val session = readEncrypted(entry) ?: throw IOException("SESSION_VAULT_UNAVAILABLE")
            check(MessageDigest.isEqual(session.bearerToken().toByteArray(Charsets.US_ASCII), token.toByteArray(Charsets.US_ASCII)))
            Files.delete(entry.toPath())
            syncDirectory(pending)
        }
    }
    private fun pendingFile(token: String): File {
        check(token.matches(Regex("[A-Za-z0-9_-]{43}")))
        return File(pending, HostedApi.sha(token.toByteArray(Charsets.US_ASCII)) + ".enc")
    }
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "SESSION_KEY_MISSING" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }
    companion object {
        private const val ALIAS = "tapscene.local-hosting.session.v1"
        private const val VERSION = 1
        private val lock = Any()
        private fun aad(file: File) = "tapscene-session-v1:${HostedApi.ENDPOINT}:${file.name}".toByteArray(Charsets.US_ASCII)
        internal fun syncDirectory(directory: File) {
            val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY or OsConstants.O_DIRECTORY or OsConstants.O_NOFOLLOW, 0)
            try { check(OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)); Os.fsync(descriptor) }
            finally { Os.close(descriptor) }
        }
    }
}
