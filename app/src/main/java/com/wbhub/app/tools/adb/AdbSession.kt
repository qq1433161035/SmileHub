package com.wbhub.app.tools.adb

import android.content.Context
import android.os.Build
import android.util.Log
import com.wbhub.app.storage.AppStorage
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 手机内的 ADB 客户端，走 Android 11+ 的「无线调试」。
 *
 * 流程：用户打开无线调试 → 系统显示 6 位配对码 → App 通过 mDNS 找到
 * `_adb-tls-pairing._tcp` 端口，用配对码完成 SPAKE2 配对 → 再连
 * `_adb-tls-connect._tcp` 端口，adbd 直接以 shell 身份放行。
 *
 * 整个过程不需要电脑，也不需要 root：配对码就是授权凭据。
 */
object AdbSession {
    private const val TAG = "WBHub"
    private const val MAX_OUTPUT = 200_000
    private const val AUTH_TIMEOUT_SECONDS = 30L

    data class Result(val ok: Boolean, val text: String)

    /** 设置页和通知栏共用的连接状态。 */
    data class State(val connected: Boolean, val endpoint: String?, val message: String)

    private var manager: Manager? = null
    private var endpoint: String? = null
    private val _state = MutableStateFlow(State(false, null, "未连接"))
    val state: StateFlow<State> = _state.asStateFlow()

    /** 实时状态：`state` 只在动作发生时更新，Wi-Fi 切换后会是旧值。 */
    @Synchronized
    fun currentState(): State = State(connected(), endpoint(), _state.value.message)

    @Synchronized
    fun endpoint(): String? = endpoint

    @Synchronized
    fun connected(): Boolean = manager?.isConnected == true

    @Synchronized
    fun disconnect() {
        drop()
        publish("已断开")
    }

    private fun drop() {
        // 不能调 Manager.close()：它会把私钥 destroy 掉，之后就没法再用同一个密钥配对。
        runCatching { manager?.adbConnection?.close() }
        manager = null
        endpoint = null
    }

    private fun publish(message: String) {
        _state.value = State(connected(), endpoint(), message)
    }

    /** 一次性删除本机密钥，下次会重新生成并要求重新配对。 */
    @Synchronized
    fun resetKeys(context: Context) {
        disconnect()
        keyDir(context).listFiles()?.forEach { it.delete() }
        publish("已生成新密钥，需要重新配对")
    }

    /**
     * 用配对码完成配对。
     *
     * [input] 只填 6 位数字时自动用 mDNS 找配对端口；也可以填「端口 配对码」
     * 或「IP:端口 配对码」，用于 mDNS 不可用的环境。
     */
    @Synchronized
    fun pair(context: Context, input: String): Result {
        val parsed = parsePairingInput(input)
        if (parsed == null) {
            Log.w(TAG, "adb pair: bad input '$input'")
            return Result(false, "配对码是 6 位数字；也可以输入「端口,配对码」")
        }
        val (typedHost, typedPort, code) = parsed
        Log.i(TAG, "adb pair start: host=$typedHost port=$typedPort code=$code")
        val candidates = mutableListOf<Pair<String, Int>>()
        if (typedPort > 0) {
            candidates += (typedHost ?: localWifiAddress(context)) to typedPort
            if (typedHost == null) candidates += "127.0.0.1" to typedPort
        } else {
            val found = discover(context, AdbMdns.SERVICE_TYPE_TLS_PAIRING, 15_000)
            Log.i(TAG, "adb pair mdns result: ${found?.let { "${it.first}:${it.second}" } ?: "none"}")
            if (found != null) candidates += found
        }
        if (candidates.isEmpty()) {
            val message = "没发现配对服务：确认「使用配对码配对设备」弹窗还开着、无线调试已打开；" +
                "也可以手动输入「配对端口,配对码」"
            Log.w(TAG, "adb pair: $message")
            publish("配对失败：$message")
            return Result(false, message)
        }
        var lastError: Throwable? = null
        for ((host, port) in candidates) {
            try {
                Log.i(TAG, "adb pair connecting $host:$port")
                Manager(context).also { it.setHostAddress(host) }.pair(host, port, code)
                Log.i(TAG, "adb pair success: $host:$port")
                publish("配对成功：$host:$port，正在连接…")
                return Result(true, "配对成功：$host:$port")
            } catch (error: Throwable) {
                lastError = error
                Log.w(TAG, "adb pair failed: $host:$port", error)
            }
        }
        val message = describe(lastError!!)
        publish("配对失败：$message")
        return Result(false, message)
    }

    /**
     * 连接 adbd。[host]/[port] 为空时先用 mDNS 找无线调试端口，
     * 找不到再退回 127.0.0.1:5555。
     */
    @Synchronized
    fun connect(context: Context, host: String? = null, port: Int = 0): Result {
        drop()
        val candidate = if (!host.isNullOrBlank() && port > 0) {
            host to port
        } else {
            discover(context, AdbMdns.SERVICE_TYPE_TLS_CONNECT, 8_000)
                ?: discover(context, AdbMdns.SERVICE_TYPE_ADB, 3_000)
                ?: ("127.0.0.1" to 5555)
        }
        val instance = Manager(context)
        return try {
            Log.i(TAG, "adb connect: try ${candidate.first}:${candidate.second}")
            val ok = instance.connect(candidate.first, candidate.second)
            if (!ok) {
                drop()
                publish("adbd 拒绝连接：先在无线调试里用配对码配对")
                Log.w(TAG, "adb connect rejected by ${candidate.first}:${candidate.second}")
                return Result(false, "adbd 拒绝了连接：先在无线调试里用配对码配对")
            }
            manager = instance
            endpoint = "${candidate.first}:${candidate.second}"
            val probe = shellOn(instance, "getprop ro.product.model", 10).trim()
            Log.i(TAG, "adb connected to $endpoint")
            publish("已连接 $endpoint")
            Result(true, probe.ifEmpty { "connected" })
        } catch (error: Throwable) {
            drop()
            Log.w(TAG, "adb connect failed: ${candidate.first}:${candidate.second}", error)
            publish("连接失败：${describe(error)}")
            Result(false, describe(error))
        }
    }

    @Synchronized
    fun shell(context: Context, command: String, timeoutSeconds: Long): Result {
        val instance = manager?.takeIf { it.isConnected }
            ?: run {
                val opened = connect(context)
                if (!opened.ok) return opened
                manager ?: return Result(false, "ADB 未连接")
            }
        return try {
            val output = shellOn(instance, command, timeoutSeconds)
            if (output.startsWith("E_TIMEOUT:") || output.startsWith("E_IO:")) {
                Result(false, output)
            } else {
                Result(true, output)
            }
        } catch (error: Throwable) {
            drop()
            publish("ADB 命令失败：${describe(error)}")
            Result(false, describe(error))
        }
    }

    /** 兼容旧调用：指定了 host/port 就按指定地址连，否则自动发现。 */
    @Synchronized
    fun shell(
        context: Context,
        host: String?,
        port: Int,
        command: String,
        timeoutSeconds: Long,
    ): Result {
        if (!connected() && !host.isNullOrBlank() && port > 0) {
            val opened = connect(context, host, port)
            if (!opened.ok) return opened
        }
        return shell(context, command, timeoutSeconds)
    }

    private data class ShellRead(
        val bytes: ByteArray,
        val interrupted: Boolean,
        val truncated: Boolean,
    )

    private fun shellOn(instance: AbsAdbConnectionManager, command: String, timeoutSeconds: Long): String {
        val stream: AdbStream = instance.openStream("shell:$command")
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<ShellRead> { readAll(stream) }
            val read = try {
                future.get(timeoutSeconds.coerceIn(1, 600), TimeUnit.SECONDS)
            } catch (timeout: TimeoutException) {
                future.cancel(true)
                return "E_TIMEOUT: 命令在 ${timeoutSeconds}s 内没有结束"
            }
            val text = read.bytes.toString(Charsets.UTF_8)
            val suffix = buildString {
                if (read.truncated) append("\n… 输出超过 200KB，已截断")
                if (read.interrupted) append("\n(ADB 连接中断，输出可能不完整)")
            }
            // 读流被掐断、或空输出且连接已掉：不能当成命令成功，否则模型会把
            // 半截结果当完整结果继续用。
            if (read.interrupted || (read.bytes.isEmpty() && !instance.isConnected)) {
                return "E_IO: ADB 会话被 adbd 中断$suffix\n$text"
            }
            return text + suffix
        } finally {
            executor.shutdownNow()
            runCatching { stream.close() }
        }
    }

    private fun readAll(stream: AdbStream): ShellRead {
        val output = ByteArrayOutputStream()
        val input = stream.openInputStream()
        val buffer = ByteArray(8 * 1024)
        var interrupted = false
        while (output.size() < MAX_OUTPUT) {
            // libadb 在流正常结束和被掐断时都抛 "Stream closed."；这里记录下来，
            // 由 shellOn 决定是否按失败返回。
            val read = try {
                input.read(buffer)
            } catch (closed: java.io.IOException) {
                interrupted = true
                break
            }
            if (read < 0) break
            output.write(buffer, 0, read)
        }
        return ShellRead(output.toByteArray(), interrupted, output.size() >= MAX_OUTPUT)
    }

    /** 阻塞式 mDNS 发现，返回第一个解析到的 host:port。 */
    private fun discover(context: Context, serviceType: String, timeoutMillis: Long): Pair<String, Int>? {
        val latch = CountDownLatch(1)
        val host = AtomicReference<String>()
        val port = AtomicInteger(-1)
        val mdns = AdbMdns(context, serviceType) { address, foundPort ->
            Log.i(TAG, "mdns $serviceType -> ${address?.hostAddress}:$foundPort")
            if (address != null && foundPort > 0) {
                host.set(address.hostAddress)
                port.set(foundPort)
                latch.countDown()
            }
        }
        mdns.start()
        Log.i(TAG, "mdns start $serviceType (timeout ${timeoutMillis}ms)")
        return try {
            if (!latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "mdns timeout $serviceType")
                null
            } else {
                (host.get() ?: return null) to port.get()
            }
        } finally {
            mdns.stop()
        }
    }

    /** 配对服务只监听 WLAN 地址，优先用本机 Wi-Fi IP 而不是回环地址。 */
    private fun localWifiAddress(context: Context): String {
        val wifi = context.getSystemService(android.net.wifi.WifiManager::class.java)
        @Suppress("DEPRECATION")
        val ip = wifi?.connectionInfo?.ipAddress ?: 0
        if (ip != 0) {
            return "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
        }
        return runCatching {
            java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
                .flatMap { java.util.Collections.list(it.inetAddresses) }
                .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress
        }.getOrNull() ?: "127.0.0.1"
    }

    /** 解析「123456」/「37421 123456」/「192.168.1.5:37421 123456」。 */
    private fun parsePairingInput(input: String): Triple<String?, Int, String>? {
        val parts = input.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val code = parts.last()
        if (!Regex("\\d{6}").matches(code)) return null
        val target = parts.dropLast(1).firstOrNull() ?: return Triple(null, 0, code)
        val match = Regex("^(?:(\\d{1,3}(?:\\.\\d{1,3}){3}):)?(\\d{2,5})$").find(target)
            ?: return Triple(null, 0, code)
        val host = match.groupValues[1].ifEmpty { null }
        val port = match.groupValues[2].toIntOrNull() ?: 0
        return Triple(host, port, code)
    }

    private fun keyDir(context: Context): File = AppStorage.adb(context)

    private fun describe(error: Throwable): String = when (error) {
        is AdbPairingRequiredException ->
            "还没有配对：先在无线调试里点「使用配对码配对设备」，把配对码发到通知栏"
        is java.net.ConnectException ->
            "连接被拒绝：无线调试没有打开，或端口已经变化，重新配对即可"
        is java.net.SocketTimeoutException ->
            "连接超时：检查 Wi-Fi 是否开启、无线调试是否在同一网络"
        is java.net.UnknownHostException ->
            "找不到配对服务：确认无线调试已打开、系统弹窗还开着"
        is java.io.IOException ->
            "ADB 会话被中断（${error.message ?: "连接关闭"}）：重新打开无线调试或重新配对后再试"
        else -> error.message ?: error.toString()
    }

    /** libadb 的子类：负责把本地密钥/证书交给库。 */
    private class Manager(context: Context) : AbsAdbConnectionManager() {
        private val keys = AdbKeys.load(context)

        init {
            setApi(Build.VERSION.SDK_INT)
            setTimeout(AUTH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            setThrowOnUnauthorised(true)
        }

        override fun getPrivateKey(): PrivateKey = keys.privateKey

        override fun getCertificate(): Certificate = keys.certificate

        override fun getDeviceName(): String = "wbhub@android"
    }

    /** RSA 私钥 + 自签证书，保存在配置目录/adb 下，卸载前一直复用。 */
    private class AdbKeys(val privateKey: PrivateKey, val certificate: X509Certificate) {
        companion object {
            fun load(context: Context): AdbKeys {
                val dir = AppStorage.adb(context)
                val keyFile = File(dir, "client.key")
                val certFile = File(dir, "client.crt")
                // adblib 时代的裸 DER 文件不再使用，避免和证书密钥混淆
                File(dir, "adbkey").delete()
                File(dir, "adbkey.pub").delete()
                if (keyFile.isFile && certFile.isFile) {
                    runCatching {
                        val key = KeyFactory.getInstance("RSA")
                            .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                        val cert = CertificateFactory.getInstance("X.509")
                            .generateCertificate(certFile.inputStream()) as X509Certificate
                        return AdbKeys(key, cert)
                    }.onFailure { Log.w(TAG, "adb key pair unreadable, regenerating", it) }
                }
                val generated = generate()
                runCatching {
                    keyFile.writeBytes(generated.privateKey.encoded)
                    certFile.writeBytes(generated.certificate.encoded)
                }.onFailure { Log.w(TAG, "adb key pair not saved", it) }
                return generated
            }

            private fun generate(): AdbKeys {
                val generator = KeyPairGenerator.getInstance("RSA")
                generator.initialize(2048, SecureRandom())
                val pair = generator.generateKeyPair()
                val now = System.currentTimeMillis()
                val subject = X500Name("CN=WBHub")
                val builder = JcaX509v3CertificateBuilder(
                    subject,
                    BigInteger.valueOf(now),
                    Date(now - 86_400_000L),
                    Date(now + 3_650L * 86_400_000L),
                    subject,
                    pair.public,
                )
                val signer = JcaContentSignerBuilder("SHA256withRSA").build(pair.private)
                val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))
                return AdbKeys(pair.private, certificate)
            }
        }
    }
}
