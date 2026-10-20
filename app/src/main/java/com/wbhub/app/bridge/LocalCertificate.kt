package com.wbhub.app.bridge

import android.content.Context
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The certificate the HTTPS endpoint presents.
 *
 * Android's public API can parse an X.509 certificate but cannot issue one, and
 * the endpoint has to work offline with no account and no network, so the
 * certificate is built here: a local CA that is created once, and a leaf it
 * signs. The leaf is what the server presents, and the CA is what a client
 * trusts — importing one certificate is enough, and it keeps working when the
 * leaf is reissued.
 *
 * Both are stored under the app's own directory. The private keys never leave
 * the device.
 *
 * The encoding is written by hand rather than pulled from a certificate
 * library: the DER needed for a certificate is six shapes, while the libraries
 * that provide it add several megabytes to the app for one use.
 */
object LocalCertificate {

    private const val TAG = "WBHub"

    private const val CA_FILE = "wb-tls-ca.der"
    private const val CA_KEY_FILE = "wb-tls-ca.key"
    private const val LEAF_FILE = "wb-tls-leaf.der"
    private const val LEAF_KEY_FILE = "wb-tls-leaf.key"
    private const val SAN_FILE = "wb-tls-san.json"

    private const val CA_DAYS = 3650L
    private const val LEAF_DAYS = 825L

    private const val CA_COMMON_NAME = "WorkBuddy Hub Local CA"
    private const val LEAF_COMMON_NAME = "WorkBuddy Hub Local"
    private const val ORGANISATION = "WorkBuddy Hub"

    /** An issued certificate and its key, ready to hand to a TLS context. */
    class Material(
        val chain: Array<X509Certificate>,
        val privateKey: PrivateKey,
    ) {
        /** The CA certificate a client should be told to trust. */
        val issuer: X509Certificate get() = chain.last()
    }

    /** Returns the certificate for [hosts], issuing or reissuing it as needed. */
    fun load(context: Context, hosts: List<String>): Material? = try {
        val dir = context.filesDir
        val caCertFile = File(dir, CA_FILE)
        val caKeyFile = File(dir, CA_KEY_FILE)
        val leafCertFile = File(dir, LEAF_FILE)
        val leafKeyFile = File(dir, LEAF_KEY_FILE)
        val sanFile = File(dir, SAN_FILE)

        // A CA that cannot be reissued without the client importing a new one,
        // so it is created once and kept. Losing it is not fatal: a new CA is
        // made and the client has to import the replacement.
        val ca = if (caCertFile.exists() && caKeyFile.exists()) {
            runCatching { readCertificate(caCertFile.readBytes()) to readKey(caKeyFile.readBytes()) }
                .getOrNull()
                ?: createAuthority(dir, caCertFile, caKeyFile)
        } else {
            createAuthority(dir, caCertFile, caKeyFile)
        }
        val (caCert, caKey) = ca

        // The leaf is what carries the host names, so an address change has to
        // reach it. Reissuing is cheap and needs no client action, because the
        // CA that signed it is the one already trusted.
        val wanted = hosts.distinct().sorted()
        val issued = if (leafCertFile.exists() && leafKeyFile.exists()) {
            runCatching {
                val cert = readCertificate(leafCertFile.readBytes())
                val key = readKey(leafKeyFile.readBytes())
                val names = runCatching {
                    JSONArray(sanFile.readText()).let { arr ->
                        (0 until arr.length()).map { arr.optString(it) }
                    }
                }.getOrDefault(emptyList())
                if (cert.covers(wanted) && names == wanted) cert to key else null
            }.getOrNull()
        } else {
            null
        }

        val (leafCert, leafKey) = issued ?: run {
            val pair = createLeaf(caCert, caKey, wanted)
            leafCertFile.writeBytes(pair.first.encoded)
            leafKeyFile.writeBytes(pair.second.encoded)
            sanFile.writeText(JSONArray(wanted).toString())
            pair
        }

        Material(arrayOf(leafCert, caCert), leafKey)
    } catch (e: Exception) {
        android.util.Log.e(TAG, "cannot prepare the TLS certificate", e)
        null
    }

    /** The CA a client must trust, or null when none has been made yet. */
    fun issuer(context: Context): ByteArray? {
        val file = File(context.filesDir, CA_FILE)
        return if (file.exists()) file.readBytes() else null
    }

    // ------------------------------------------------------------------ //
    // Issuing
    // ------------------------------------------------------------------ //

    private fun createAuthority(dir: File, certFile: File, keyFile: File): Pair<X509Certificate, PrivateKey> {
        val key = generateKey()
        val now = Instant.now()
        val der = certificate(
            serial = randomSerial(),
            subject = Name(CA_COMMON_NAME, ORGANISATION),
            issuer = Name(CA_COMMON_NAME, ORGANISATION),
            notBefore = now.minus(1, ChronoUnit.DAYS),
            notAfter = now.plus(CA_DAYS, ChronoUnit.DAYS),
            subjectKey = key,
            issuerKey = null,
            extensions = listOf(
                // The CA flag is what lets a client accept the leaf below it;
                // keyCertSign is what lets this key sign that leaf.
                Ext(OID_BASIC_CONSTRAINTS, true, Der().seq(Der().bool(true).done()).done()),
                Ext(OID_KEY_USAGE, true, Der().bitString(byteArrayOf(0x06)).done()),
            ),
        )
        certFile.writeBytes(der)
        keyFile.writeBytes(key.private.encoded)
        return readCertificate(der) to key.private
    }

    private fun createLeaf(
        caCert: X509Certificate,
        caKey: PrivateKey,
        hosts: List<String>,
    ): Pair<X509Certificate, PrivateKey> {
        val key = generateKey()
        val now = Instant.now()
        val dns = hosts.filter { !isIpLiteral(it) }.ifEmpty { listOf("localhost") }
        val ips = hosts.filter { isIpLiteral(it) }.ifEmpty { listOf("127.0.0.1") }
        val der = certificate(
            serial = randomSerial(),
            subject = Name(LEAF_COMMON_NAME, ORGANISATION),
            issuer = Name(CA_COMMON_NAME, ORGANISATION),
            notBefore = now.minus(1, ChronoUnit.DAYS),
            notAfter = now.plus(LEAF_DAYS, ChronoUnit.DAYS),
            subjectKey = key,
            issuerKey = caKey,
            extensions = listOf(
                Ext(OID_BASIC_CONSTRAINTS, true, Der().seq().done()),
                // digitalSignature for the handshake signature,
                // keyEncipherment for the key exchange older clients still use.
                Ext(OID_KEY_USAGE, true, Der().bitString(byteArrayOf(0xA0.toByte())).done()),
                Ext(OID_EXT_KEY_USAGE, false, Der().seq(Der().oid(OID_SERVER_AUTH).done()).done()),
                Ext(OID_SAN, false, SubjectAltNames(dns, ips).der()),
            ),
        )
        return readCertificate(der) to key.private
    }

    private fun generateKey(): KeyPair {
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        return gen.generateKeyPair()
    }

    private fun randomSerial(): BigInteger =
        BigInteger(64, SecureRandom()).abs().add(BigInteger.ONE)

    private fun readCertificate(der: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der)) as X509Certificate

    private fun readKey(der: ByteArray): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(der))

    private fun isIpLiteral(value: String): Boolean =
        value == "::1" || value.count { it == '.' } == 3 ||
            runCatching { InetAddress.getByName(value) }.map { it.address.size == 16 }.getOrDefault(false)

    /** Whether [cert] names everything in [hosts]. */
    private fun X509Certificate.covers(hosts: List<String>): Boolean {
        val names = runCatching {
            subjectAlternativeNames.orEmpty().mapNotNull { it.getOrNull(1)?.toString() }
        }.getOrDefault(emptyList())
        val notExpired = runCatching {
            notAfter.toInstant().isAfter(Instant.now().plus(1, ChronoUnit.DAYS))
        }.getOrDefault(false)
        return notExpired && hosts.all { host -> names.any { matches(it, host) } }
    }

    /** IP literals arrive from the parser in expanded form, so compare as bytes. */
    private fun matches(san: String, host: String): Boolean {
        if (san.equals(host, ignoreCase = true)) return true
        if (!isIpLiteral(host)) return false
        return runCatching {
            InetAddress.getByName(san).address.contentEquals(InetAddress.getByName(host).address)
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------ //
    // DER
    // ------------------------------------------------------------------ //

    private const val OID_ECDSA_SHA256 = "1.2.840.10045.4.3.2"
    private const val OID_CN = "2.5.4.3"
    private const val OID_O = "2.5.4.10"
    private const val OID_BASIC_CONSTRAINTS = "2.5.29.19"
    private const val OID_KEY_USAGE = "2.5.29.15"
    private const val OID_EXT_KEY_USAGE = "2.5.29.37"
    private const val OID_SAN = "2.5.29.17"
    private const val OID_SERVER_AUTH = "1.3.6.1.5.5.7.3.1"

    private data class Name(val commonName: String, val org: String) {
        fun der(): ByteArray = Der().seq(
            Der().set(Der().seq(Der().oid(OID_O).done(), Der().utf8(org).done()).done()).done(),
            Der().set(Der().seq(Der().oid(OID_CN).done(), Der().utf8(commonName).done()).done()).done(),
        ).done()
    }

    private data class SubjectAltNames(val dns: List<String>, val ips: List<String>) {
        fun der(): ByteArray {
            val parts = mutableListOf<ByteArray>()
            dns.forEach { parts += Der.wrap(0x82, it.toByteArray(StandardCharsets.US_ASCII)) }
            ips.forEach {
                parts += Der.wrap(0x87, InetAddress.getByName(it).address)
            }
            return Der().seq(*parts.toTypedArray()).done()
        }
    }

    private data class Ext(val oid: String, val critical: Boolean, val value: ByteArray) {
        fun der(): ByteArray = Der.wrap(
            0x30,
            Der.concat(
                Der().oid(oid).done(),
                if (critical) Der().bool(true).done() else ByteArray(0),
                Der().octet(value).done(),
            ),
        )
    }

    /**
     * Writes one certificate.
     *
     * [issuerKey] null makes it self-signed, which is how the CA is made; the
     * leaf is signed by the CA's key instead.
     */
    private fun certificate(
        serial: BigInteger,
        subject: Name,
        issuer: Name,
        notBefore: Instant,
        notAfter: Instant,
        subjectKey: KeyPair,
        issuerKey: PrivateKey?,
        extensions: List<Ext>,
    ): ByteArray {
        val sigAlg = Der().seq(Der().oid(OID_ECDSA_SHA256).done()).done()
        val extBlock = if (extensions.isEmpty()) {
            ByteArray(0)
        } else {
            Der.wrap(0xA3, Der().seq(*extensions.map { it.der() }.toTypedArray()).done())
        }

        val tbs = Der().seq(
            // version: [0] EXPLICIT INTEGER 2, which is X.509 v3
            Der.wrap(0xA0, Der().intVal(BigInteger.valueOf(2)).done()),
            Der().intVal(serial).done(),
            sigAlg,
            issuer.der(),
            Der().seq(Der.time(notBefore), Der.time(notAfter)).done(),
            subject.der(),
            subjectKey.public.encoded,
            extBlock,
        ).done()

        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(issuerKey ?: subjectKey.private)
        signer.update(tbs)
        val signature = signer.sign()

        return Der().seq(tbs, sigAlg, Der().bitString(signature).done()).done()
    }

    /** Minimal DER writer: only the shapes a certificate needs. */
    private class Der {
        private val out = ByteArrayOutputStream()

        fun seq(vararg parts: ByteArray): Der { out.writeBytes(wrap(0x30, concat(*parts))); return this }
        fun set(vararg parts: ByteArray): Der { out.writeBytes(wrap(0x31, concat(*parts))); return this }
        fun bool(v: Boolean): Der {
            out.writeBytes(wrap(0x01, byteArrayOf(if (v) 0xFF.toByte() else 0x00)))
            return this
        }

        fun intVal(v: BigInteger): Der { out.writeBytes(wrap(0x02, v.toByteArray())); return this }
        fun bitString(bytes: ByteArray): Der {
            // The first byte counts the unused bits at the end; none are unused here.
            out.writeBytes(wrap(0x03, byteArrayOf(0) + bytes))
            return this
        }

        fun octet(bytes: ByteArray): Der { out.writeBytes(wrap(0x04, bytes)); return this }
        fun utf8(s: String): Der {
            out.writeBytes(wrap(0x0C, s.toByteArray(StandardCharsets.UTF_8)))
            return this
        }

        fun oid(dotted: String): Der { out.writeBytes(wrap(0x06, encodeOid(dotted))); return this }
        fun done(): ByteArray = out.toByteArray()

        companion object {
            /** Length-prefixes [body] under [tag]. */
            fun wrap(tag: Int, body: ByteArray): ByteArray {
                val out = ByteArrayOutputStream()
                out.write(tag)
                val n = body.size
                if (n < 0x80) {
                    out.write(n)
                } else {
                    val bytes = (32 - Integer.numberOfLeadingZeros(n) + 7) / 8
                    out.write(0x80 or bytes)
                    for (i in bytes - 1 downTo 0) out.write((n ushr (8 * i)) and 0xFF)
                }
                out.writeBytes(body)
                return out.toByteArray()
            }

            fun concat(vararg parts: ByteArray): ByteArray {
                val out = ByteArrayOutputStream()
                parts.forEach { out.writeBytes(it) }
                return out.toByteArray()
            }

            /**
             * Time, in the form RFC 5280 requires: UTCTime before 2050 and
             * GeneralizedTime after, which is also when two-digit years stop
             * being unambiguous.
             */
            fun time(t: Instant): ByteArray {
                val utc = t.atZone(java.time.ZoneOffset.UTC)
                return if (t.isBefore(Instant.parse("2050-01-01T00:00:00Z"))) {
                    wrap(
                        0x17,
                        String.format(
                            Locale.US, "%02d%02d%02d%02d%02d%02dZ",
                            utc.year % 100, utc.monthValue, utc.dayOfMonth,
                            utc.hour, utc.minute, utc.second,
                        ).toByteArray(StandardCharsets.US_ASCII),
                    )
                } else {
                    wrap(
                        0x18,
                        String.format(
                            Locale.US, "%04d%02d%02d%02d%02d%02dZ",
                            utc.year, utc.monthValue, utc.dayOfMonth,
                            utc.hour, utc.minute, utc.second,
                        ).toByteArray(StandardCharsets.US_ASCII),
                    )
                }
            }

            /** Encodes an OID body: the first two arcs pack into one byte, the rest are base-128. */
            fun encodeOid(dotted: String): ByteArray {
                val parts = dotted.split(".")
                val out = ByteArrayOutputStream()
                out.write(parts[0].toInt() * 40 + parts[1].toInt())
                for (i in 2 until parts.size) {
                    var v = parts[i].toLong()
                    var groups = 1
                    while ((v ushr (7 * groups)) != 0L) groups++
                    for (g in groups - 1 downTo 0) {
                        val b = ((v ushr (7 * g)) and 0x7F).toInt()
                        out.write(if (g == 0) b else (b or 0x80))
                    }
                }
                return out.toByteArray()
            }
        }
    }
}
