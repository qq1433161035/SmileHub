package com.wbhub.app.tools.engines

import android.content.Context
import com.wbhub.app.storage.AppStorage
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * Signing keys for rebuilt APKs.
 *
 * Generation and PKCS#12 handling live in the engine module, where BouncyCastle
 * is available; this class only exposes the same app-side shape the tools use.
 */
class SigningKeys(private val context: Context) {

    data class Key(
        val id: String,
        val alias: String,
        val privateKey: PrivateKey,
        val certificate: X509Certificate,
    )

    fun loadOrCreate(id: String = DEFAULT_ID): Key {
        val api = EngineLoader.load(context)
            ?: throw IllegalStateException("逆向引擎初始化失败（已内置在 APK 中，详见 logcat）")
        val material = api.loadOrCreateKey(AppStorage.keys(context), id)
        return Key(id, material.alias, material.privateKey, material.certificate)
    }

    companion object {
        const val DEFAULT_ID = "default"
    }
}
