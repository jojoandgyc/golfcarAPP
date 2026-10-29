package com.example.followingcar

import android.content.Context
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import android.util.AtomicFile
import android.util.JsonReader
import android.util.JsonToken
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.net.Socket
import java.nio.charset.CodingErrorAction
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import org.json.JSONObject

/** Private key is a non-exportable AndroidKeyStore handle; toString never displays it. */
class RemoteClientIdentity(
    val profile: RemoteProfile,
    val privateKey: PrivateKey,
    val certificateChain: Array<X509Certificate>,
) {
    override fun toString(): String = "RemoteClientIdentity(host=${profile.host})"
}

/** Import is explicit; neither the PKCS#12 nor its password is saved to disk. */
class RemoteCredentials private constructor(private val profileFile: AtomicFile, private val aliasPrefix: String) {
    constructor(context: Context) : this(AtomicFile(File(context.applicationContext.noBackupFilesDir,
        "remote_connection/profile.json")), "followingcar.remote.client.")

    /** Instrumentation uses a unique namespace and cannot clear or replace real credentials. */
    internal constructor(context: Context, testId: UUID) : this(AtomicFile(File(
        context.applicationContext.cacheDir, "remote-credential-test-$testId/profile.json")),
        "followingcar.remote.test.$testId.")

    fun importProfile(input: InputStream): RemoteProfile = synchronized(lock) {
        val bundle = readRemoteCredentialBundle(input)
        try {
            val entry = decodeRemoteIdentity(bundle)
            val leaf = entry.certificate as X509Certificate
            val profile = RemoteProfile(bundle.host, bundle.controlPort, bundle.videoPort,
                certificateFingerprint(leaf), leaf.notAfter.time)
            val store = keyStore()
            val oldAlias = runCatching { readStored()?.alias }.getOrNull()
            val alias = aliasPrefix + UUID.randomUUID()
            var committed = false
            try {
                val protection = KeyProtection.Builder(KeyProperties.PURPOSE_SIGN)
                    // TLS computes its own transcript digest; Conscrypt signs the raw result.
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384,
                        KeyProperties.DIGEST_SHA512)
                    .setUserAuthenticationRequired(false)
                if (entry.privateKey.algorithm == "RSA") {
                    protection.setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,
                        KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                    // BoringSSL applies PSS padding before the AndroidKeyStore RSA operation.
                    // This permits that raw SIGN operation, not a private-key decrypt purpose.
                    protection.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                }
                store.setEntry(alias, entry, protection.build())
                // Verify the imported handle is usable before replacing the active metadata.
                val imported = requireNotNull(store.getEntry(alias, null) as? KeyStore.PrivateKeyEntry)
                verifyClientKey(imported.privateKey, leaf)
                writeStored(StoredProfile(profile, alias))
                committed = true
                if (oldAlias != null && oldAlias != alias) runCatching { store.deleteEntry(oldAlias) }
                Diagnostics.event("remote.credentials_imported", fields = mapOf("host" to profile.host,
                    "controlPort" to profile.controlPort, "videoPort" to profile.videoPort), critical = true)
                profile
            } finally {
                if (!committed) runCatching { store.deleteEntry(alias) }
            }
        } catch (failure: Exception) {
            throw safeCredentialFailure("远程凭据导入失败，请检查证书、有效期和配置文件", failure)
        } finally {
            bundle.close()
        }
    }

    /** null means not provisioned; malformed metadata or missing/expired keys throws. */
    fun load(): RemoteProfile? = getClientIdentity()?.profile

    fun getClientIdentity(): RemoteClientIdentity? = synchronized(lock) {
        try {
            val stored = readStored() ?: return@synchronized null
            val entry = keyStore().getEntry(stored.alias, null) as? KeyStore.PrivateKeyEntry
                ?: throw IOException("远程客户端证书已失效，请重新导入")
            val chain = entry.certificateChain.map { it as X509Certificate }.toTypedArray()
            require(chain.isNotEmpty() && certificateFingerprint(chain.first()) ==
                stored.profile.clientCertificateSha256) { "远程客户端证书与配置不一致" }
            chain.forEach { it.checkValidity() }
            require(chain.first().notAfter.time == stored.profile.certificateExpiresAtEpochMillis)
            RemoteClientIdentity(stored.profile, entry.privateKey, chain)
        } catch (failure: Exception) {
            throw safeCredentialFailure("远程凭据不可用或已过期，请重新导入", failure)
        }
    }

    fun getClientPrivateKey(): PrivateKey? = getClientIdentity()?.privateKey
    fun getClientCertificateChain(): Array<X509Certificate>? = getClientIdentity()?.certificateChain

    fun createTlsConfig(): RemoteTlsConfig {
        val identity = getClientIdentity() ?: throw IOException("请先导入远程连接凭据")
        val ssl = SSLContext.getInstance("TLS")
        // null TrustManager means the Android system CA store. No custom trust bypass.
        ssl.init(arrayOf(RemoteClientKeyManager(identity)), null, SecureRandom())
        return RemoteTlsConfig(ssl.socketFactory, identity.profile.host)
    }

    fun clear() = synchronized(lock) {
        try {
            val store = keyStore()
            // Delete only keys belonging to this feature; remove stale crash-time imports too.
            store.aliases().toList().filter { it.startsWith(aliasPrefix) }.forEach(store::deleteEntry)
            profileFile.delete()
            Diagnostics.event("remote.credentials_cleared", critical = true)
        } catch (failure: Exception) {
            throw safeCredentialFailure("无法清除远程凭据，请重试", failure)
        }
    }

    private fun readStored(): StoredProfile? {
        if (!profileFile.baseFile.exists() && !File(profileFile.baseFile.path + ".bak").exists()) return null
        val raw = profileFile.openRead().use { readBounded(it, 4096) }
        val json = JSONObject(String(raw, Charsets.UTF_8))
        require(json.getInt("schemaVersion") == 1)
        val host = requireRemoteHost(json.getString("host"))
        val alias = json.getString("keyAlias")
        require(alias.startsWith(aliasPrefix) && alias.length == aliasPrefix.length + 36)
        UUID.fromString(alias.removePrefix(aliasPrefix))
        val fingerprint = json.getString("clientCertificateSha256")
        require(fingerprint.matches(Regex("[0-9a-f]{64}")))
        return StoredProfile(RemoteProfile(host, requireRemotePort(json.getInt("controlPort")),
            requireRemotePort(json.getInt("videoPort")), fingerprint,
            json.getLong("certificateExpiresAtEpochMillis")), alias)
    }

    private fun writeStored(stored: StoredProfile) {
        val directory = profileFile.baseFile.parentFile!!
        check(directory.isDirectory || directory.mkdirs()) { "远程配置目录不可用" }
        val profile = stored.profile
        val data = JSONObject().put("schemaVersion", 1).put("host", profile.host)
            .put("controlPort", profile.controlPort).put("videoPort", profile.videoPort)
            .put("clientCertificateSha256", profile.clientCertificateSha256)
            .put("certificateExpiresAtEpochMillis", profile.certificateExpiresAtEpochMillis)
            .put("keyAlias", stored.alias).toString().toByteArray(Charsets.UTF_8)
        val stream = profileFile.startWrite()
        try {
            stream.write(data)
            profileFile.finishWrite(stream)
        } catch (failure: Exception) {
            profileFile.failWrite(stream)
            throw failure
        }
    }

    private data class StoredProfile(val profile: RemoteProfile, val alias: String)
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private companion object {
        val lock = Any()
    }
}

/** Kept internal so production callers cannot accidentally print a PKCS#12 import object. */
internal class RemoteCredentialBundle(
    val host: String,
    val controlPort: Int,
    val videoPort: Int,
    val pkcs12: ByteArray,
    val password: CharArray,
) : AutoCloseable {
    override fun close() { pkcs12.fill(0); password.fill('\u0000') }
    override fun toString(): String = "RemoteCredentialBundle(host=$host, credentials=[REDACTED])"
}

internal fun readRemoteCredentialBundle(input: InputStream): RemoteCredentialBundle {
    var bytes: ByteArray? = null
    var decoded: ByteArray? = null
    var password: CharArray? = null
    try {
        bytes = readBounded(input, 64 * 1024)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        val fields = linkedMapOf<String, String>()
        JsonReader(StringReader(text)).use { reader ->
            reader.isLenient = false
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                require(name in IMPORT_FIELDS && name !in fields) { "远程配置存在未知或重复字段" }
                val numeric = name in NUMERIC_FIELDS
                require(reader.peek() == if (numeric) JsonToken.NUMBER else JsonToken.STRING)
                fields[name] = reader.nextString()
            }
            reader.endObject()
            require(reader.peek() == JsonToken.END_DOCUMENT)
        }
        require(fields.keys == IMPORT_FIELDS && fields["schemaVersion"] == "1")
        val host = requireRemoteHost(fields.getValue("host"))
        val control = parseImportPort(fields.getValue("controlPort"))
        val video = parseImportPort(fields.getValue("videoPort"))
        val secret = fields.getValue("clientPkcs12Password")
        require(secret.length in 1..256 && secret.none { it == '\u0000' })
        Diagnostics.protectSecret(secret)
        val encoded = fields.getValue("clientPkcs12Base64")
        require(encoded.length in 1..60_000)
        // No parser/provider exception is ever propagated with the input as its message/cause.
        encoded.chunked(4096).forEach(Diagnostics::protectSecret)
        decoded = Base64.getDecoder().decode(encoded)
        require(decoded.size in 1..45_000)
        password = secret.toCharArray()
        return RemoteCredentialBundle(host, control, video, decoded, password)
    } catch (failure: Exception) {
        decoded?.fill(0)
        password?.fill('\u0000')
        throw safeCredentialFailure("远程凭据文件格式无效（仅支持版本 1，最大 64 KB）", failure)
    } finally {
        bytes?.fill(0)
    }
}

private val IMPORT_FIELDS = setOf("schemaVersion", "host", "controlPort", "videoPort",
    "clientPkcs12Base64", "clientPkcs12Password")
private val NUMERIC_FIELDS = setOf("schemaVersion", "controlPort", "videoPort")
private fun parseImportPort(value: String): Int {
    require(value.matches(Regex("[0-9]{1,5}")))
    return requireRemotePort(value.toInt())
}

internal fun readBounded(input: InputStream, limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (true) {
        val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
        if (count < 0) return output.toByteArray()
        if (count == 0) throw IOException("配置文件读取失败")
        output.write(buffer, 0, count)
        if (output.size() > limit) throw IOException("配置文件过大")
    }
}

internal fun decodeRemoteIdentity(bundle: RemoteCredentialBundle): KeyStore.PrivateKeyEntry {
    val store = KeyStore.getInstance("PKCS12")
    store.load(ByteArrayInputStream(bundle.pkcs12), bundle.password)
    val aliases = store.aliases().toList().filter(store::isKeyEntry)
    require(aliases.size == 1) { "客户端身份必须包含且仅包含一个私钥" }
    val entry = store.getEntry(aliases.single(), KeyStore.PasswordProtection(bundle.password))
        as? KeyStore.PrivateKeyEntry ?: throw IOException("客户端私钥不可用")
    val chain = entry.certificateChain.map { it as X509Certificate }
    require(chain.isNotEmpty() && chain.size <= 5)
    chain.forEach { it.checkValidity() }
    val leaf = chain.first()
    require(leaf.basicConstraints == -1) { "客户端证书不能是 CA 证书" }
    require(leaf.extendedKeyUsage?.contains("1.3.6.1.5.5.7.3.2") == true) { "证书不允许客户端认证" }
    require(leaf.keyUsage == null || leaf.keyUsage[0]) { "客户端证书不允许签名" }
    chain.zipWithNext().forEach { (child, issuer) ->
        require(child.issuerX500Principal == issuer.subjectX500Principal)
        child.verify(issuer.publicKey)
    }
    require(entry.privateKey.algorithm in setOf("RSA", "EC")) { "仅支持 RSA 或 EC 客户端证书" }
    verifyClientKey(entry.privateKey, leaf)
    return entry
}

internal fun verifyClientKey(key: PrivateKey, leaf: X509Certificate) {
    val algorithm = when (key.algorithm) {
        "RSA" -> "SHA256withRSA"
        "EC" -> "SHA256withECDSA"
        else -> throw IOException("客户端私钥算法不受支持")
    }
    val challenge = ByteArray(32).also(SecureRandom()::nextBytes)
    val signature = Signature.getInstance(algorithm).apply { initSign(key); update(challenge) }.sign()
    require(Signature.getInstance(algorithm).apply { initVerify(leaf.publicKey); update(challenge) }
        .verify(signature)) { "客户端私钥与证书不匹配" }
}

private fun certificateFingerprint(certificate: X509Certificate): String =
    MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }

/** Intentionally omit original message/cause: parsers and providers can echo credential input. */
private fun safeCredentialFailure(message: String, failure: Exception): IOException = IOException(message).apply {
    stackTrace = failure.stackTrace
}

internal class RemoteClientKeyManager(private val identity: RemoteClientIdentity) : X509ExtendedKeyManager() {
    private fun accepted(keyTypes: Array<out String>?, issuers: Array<out Principal>?): Boolean =
        keyTypes?.any { it.substringBefore('_') == identity.privateKey.algorithm } == true &&
            (issuers.isNullOrEmpty() || identity.certificateChain.any { certificate ->
                issuers.any { it == certificate.issuerX500Principal || it == certificate.subjectX500Principal }
            })
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? =
        if (accepted(keyType, issuers)) "remote-client" else null
    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String? =
        if (accepted(keyType, issuers)) "remote-client" else null
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
        if (accepted(keyType?.let { arrayOf(it) }, issuers)) arrayOf("remote-client") else null
    override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
        identity.certificateChain.clone().takeIf { alias == "remote-client" }
    override fun getPrivateKey(alias: String?): PrivateKey? = identity.privateKey.takeIf { alias == "remote-client" }
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
}
