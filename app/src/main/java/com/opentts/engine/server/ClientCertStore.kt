package com.opentts.engine.server

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Client certificates for mutual TLS against a reverse proxy (Caddy, Nginx).
 *
 * Accepts either a PKCS#12 bundle (`.p12` / `.pfx`, password required) or a PEM
 * file holding an unencrypted PKCS#8 private key plus one or more certificates.
 * Whatever the source, it is normalised to `filesDir/certs/client.p12` and
 * `filesDir/certs/ca.pem`, so there is one code path at connect time.
 *
 * The bundle lives in app-private storage (like the OpenRouter API key, which is
 * also plaintext in SharedPreferences). This protects against other apps, not
 * against a rooted device.
 */
class ClientCertStore private constructor(
    val socketFactory: SSLSocketFactory,
    val summary: String,
) {

    companion object {
        private const val TAG = "ClientCertStore"
        private const val DIR = "certs"
        private const val KEYSTORE = "client.p12"
        private const val CA_FILE = "ca.pem"
        private const val PREFS = "opentts_certs"
        private const val KEY_ALIAS = "client"
        private const val KEY_PASSWORD = "keystore_password"

        fun dir(context: Context) = File(context.filesDir, DIR)

        fun hasCertificate(context: Context): Boolean =
            File(dir(context), KEYSTORE).isFile

        fun prefs(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /** Builds the SSL context, or null when no certificate is installed. */
        fun load(context: Context): ClientCertStore? {
            val file = File(dir(context), KEYSTORE)
            if (!file.isFile) return null
            val password = prefs(context).getString(KEY_PASSWORD, "") ?: ""

            return try {
                val keyStore = KeyStore.getInstance("PKCS12")
                file.inputStream().use { keyStore.load(it, password.toCharArray()) }

                val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                keyManagerFactory.init(keyStore, password.toCharArray())

                val caFile = File(dir(context), CA_FILE)
                val trustManager = if (caFile.isFile) {
                    compositeTrustManager(caFile.readBytes())
                } else {
                    defaultTrustManager()
                }

                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(keyManagerFactory.keyManagers, arrayOf(trustManager), null)
                val alias = keyStore.aliases().toList().firstOrNull().orEmpty()
                val subject = (keyStore.getCertificate(alias) as? X509Certificate)
                    ?.subjectX500Principal?.name.orEmpty()
                ClientCertStore(sslContext.socketFactory, subject.ifBlank { "client certificate installed" })
            } catch (e: Exception) {
                Log.e(TAG, "Could not load client certificate: ${e.message}", e)
                null
            }
        }

        /** Imports a PKCS#12 bundle. Returns a short description on success. */
        fun importPkcs12(context: Context, bytes: ByteArray, password: String): Result<String> = runCatching {
            val probe = KeyStore.getInstance("PKCS12")
            probe.load(ByteArrayInputStream(bytes), password.toCharArray())
            val alias = probe.aliases().toList().firstOrNull { probe.isKeyEntry(it) }
                ?: throw IllegalArgumentException("no private key in this PKCS#12 file")
            val chain = probe.getCertificateChain(alias)?.map { it as X509Certificate }.orEmpty()
            if (chain.isEmpty()) throw IllegalArgumentException("no certificate chain in this file")

            store(context, bytes, password, chain.drop(1))
            "PKCS#12 imported · ${chain.first().subjectX500Principal.name}"
        }

        /** Imports a PEM file (unencrypted PKCS#8 key + certificates). */
        fun importPem(context: Context, bytes: ByteArray, password: String = ""): Result<String> = runCatching {
            val text = bytes.toString(Charsets.UTF_8)
            val certs = pemBlocks(text, "CERTIFICATE").mapNotNull { der ->
                CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(der)) as? X509Certificate
            }
            if (certs.isEmpty()) throw IllegalArgumentException("no CERTIFICATE block in this PEM file")

            val keyDer = pemBlocks(text, "PRIVATE KEY").firstOrNull()
                ?: throw IllegalArgumentException("no unencrypted PKCS#8 PRIVATE KEY block found")
            val privateKey: PrivateKey = parsePrivateKey(keyDer)

            val keyStore = KeyStore.getInstance("PKCS12")
            keyStore.load(null, null)
            val chain = certs.toTypedArray()
            keyStore.setKeyEntry(KEY_ALIAS, privateKey, password.toCharArray(), chain)

            val out = java.io.ByteArrayOutputStream()
            keyStore.store(out, password.toCharArray())
            store(context, out.toByteArray(), password, certs.drop(1))
            "PEM imported · ${chain.first().subjectX500Principal.name}"
        }

        fun clear(context: Context) {
            dir(context).deleteRecursively()
            prefs(context).edit().clear().apply()
        }

        fun description(context: Context): String? {
            val file = File(dir(context), KEYSTORE)
            if (!file.isFile) return null
            return runCatching {
                val keyStore = KeyStore.getInstance("PKCS12")
                val password = prefs(context).getString(KEY_PASSWORD, "") ?: ""
                file.inputStream().use { keyStore.load(it, password.toCharArray()) }
                val alias = keyStore.aliases().toList().firstOrNull()
                (keyStore.getCertificate(alias) as? X509Certificate)?.subjectX500Principal?.name
                    ?: "certificate installed"
            }.getOrNull()
        }

        private fun store(context: Context, keyStoreBytes: ByteArray, password: String, extraCas: List<X509Certificate>) {
            val dir = dir(context)
            dir.mkdirs()
            File(dir, KEYSTORE).writeBytes(keyStoreBytes)
            prefs(context).edit().putString(KEY_PASSWORD, password).apply()
            if (extraCas.isEmpty()) {
                File(dir, CA_FILE).delete()
            } else {
                val pem = extraCas.joinToString("\n") { cert ->
                    val b64 = Base64.encodeToString(cert.encoded, Base64.NO_WRAP)
                    "-----BEGIN CERTIFICATE-----\n$b64\n-----END CERTIFICATE-----"
                }
                File(dir, CA_FILE).writeText(pem)
            }
        }

        private fun pemBlocks(text: String, label: String): List<ByteArray> {
            val regex = Regex(
                "-----BEGIN $label-----(.*?)-----END $label-----",
                RegexOption.DOT_MATCHES_ALL,
            )
            return regex.findAll(text).mapNotNull { match ->
                runCatching { Base64.decode(match.groupValues[1].replace(Regex("\\s"), ""), Base64.DEFAULT) }.getOrNull()
            }.toList()
        }

        private fun parsePrivateKey(der: ByteArray): PrivateKey {
            val spec = PKCS8EncodedKeySpec(der)
            for (algorithm in listOf("RSA", "EC", "Ed25519")) {
                runCatching { return KeyFactory.getInstance(algorithm).generatePrivate(spec) }
            }
            throw IllegalArgumentException("unsupported private key (expected RSA, EC or Ed25519 PKCS#8)")
        }

        private fun defaultTrustManager(): X509TrustManager {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
        }

        /** Trusts the platform CAs *and* the private CA shipped with the client cert. */
        private fun compositeTrustManager(caPem: ByteArray): X509TrustManager {
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
            keyStore.load(null, null)
            val factory = CertificateFactory.getInstance("X.509")
            var index = 0
            pemBlocks(caPem.toString(Charsets.UTF_8), "CERTIFICATE").forEach { der ->
                keyStore.setCertificateEntry("ca-${index++}", factory.generateCertificate(ByteArrayInputStream(der)))
            }
            val custom = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            custom.init(keyStore)
            val privateCa = custom.trustManagers.filterIsInstance<X509TrustManager>().first()
            val platform = defaultTrustManager()

            return object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
                    privateCa.checkClientTrusted(chain, authType)

                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    try {
                        privateCa.checkServerTrusted(chain, authType)
                    } catch (_: Exception) {
                        platform.checkServerTrusted(chain, authType)
                    }
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> =
                    (privateCa.acceptedIssuers + platform.acceptedIssuers).distinctBy { it.subjectX500Principal.name }
                        .toTypedArray()
            }
        }
    }
}
