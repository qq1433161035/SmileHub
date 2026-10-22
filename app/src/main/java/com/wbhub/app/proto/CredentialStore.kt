package com.wbhub.app.proto

import android.content.Context
import com.wbhub.app.storage.AppStorage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One saved account. A build can hold several of these, so identity is carried
 * explicitly rather than inferred from the slot it sits in.
 */
data class SavedAccount(
    val id: String,
    val region: Wire.Region,
    val nickname: String,
    val uid: String,
    val domain: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val enterpriseId: String? = null,
    val source: String = "oauth",
) {
    /** Display label: the nickname, falling back to a short uid. */
    val label: String get() = nickname.ifBlank { uid.take(8) }

    fun toCredential(): Credential = Credential(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAt = expiresAt,
        domain = domain,
        uid = uid,
        nickname = nickname,
        enterpriseId = enterpriseId,
        source = source,
        accountId = id,
    )
}

/**
 * Stores the accounts of each build and remembers which one is in use.
 *
 * Each build owns one file holding an array of accounts, so adding a second
 * account never disturbs the first. The active selection is a pair of
 * build and account id, persisted separately.
 */
class CredentialStore(context: Context) {

    private val dir = AppStorage.credentials(context)
    private val prefs = AppStorage.prefs(context, "wb-hub")

    private fun fileFor(region: Wire.Region): File =
        File(dir, "wb-auth-${region.name.lowercase()}.json")

    // ------------------------------------------------------------------ //
    // Accounts
    // ------------------------------------------------------------------ //

    /** Every account saved for one build. */
    fun accounts(region: Wire.Region): List<SavedAccount> {
        val file = fileFor(region)
        if (!file.exists()) return emptyList()
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        return runCatching {
            val array = JSONArray(text)
            (0 until array.length()).mapNotNull { readAccount(array.optJSONObject(it), region) }
        }.getOrElse {
            // An older build wrote a single credential object at this path.
            readLegacy(file, region)?.let { listOf(it) } ?: emptyList()
        }
    }

    private fun readAccount(obj: JSONObject?, region: Wire.Region): SavedAccount? {
        obj ?: return null
        val token = obj.optString("accessToken")
        if (token.isEmpty()) return null
        val domain = obj.optString("domain").ifEmpty { defaultDomain(region) }
        val uid = obj.optString("uid")
        val stored = obj.optLong("expiresAt", 0L)
        return SavedAccount(
            id = obj.optString("id").ifEmpty { accountId(region, uid, domain) },
            region = region,
            nickname = obj.optString("nickname"),
            uid = uid,
            domain = domain,
            accessToken = token,
            refreshToken = obj.optString("refreshToken"),
            expiresAt = if (stored > 0) stored else expiryFromJwt(token),
            enterpriseId = obj.optString("enterpriseId").ifEmpty { null },
            source = obj.optString("source", "oauth"),
        )
    }

    /**
     * Reads the single-object shape written before multi-account support, so an
     * existing install does not lose its credential on upgrade.
     */
    private fun readLegacy(file: File, region: Wire.Region): SavedAccount? = runCatching {
        val obj = JSONObject(file.readText())
        val token = obj.optString("accessToken")
        if (token.isEmpty()) return null
        val domain = obj.optString("domain").ifEmpty { defaultDomain(region) }
        val uid = obj.optString("uid")
        val stored = obj.optLong("expiresAt", 0L)
        SavedAccount(
            id = accountId(region, uid, domain),
            region = region,
            nickname = obj.optString("nickname"),
            uid = uid,
            domain = domain,
            accessToken = token,
            refreshToken = obj.optString("refreshToken"),
            expiresAt = if (stored > 0) stored else expiryFromJwt(token),
            enterpriseId = obj.optString("enterpriseId").ifEmpty { null },
            source = obj.optString("source", "oauth"),
        )
    }.getOrNull()

    private fun writeAccounts(region: Wire.Region, accounts: List<SavedAccount>) {
        val array = JSONArray()
        accounts.forEach { account ->
            array.put(
                JSONObject().apply {
                    put("id", account.id)
                    put("accessToken", account.accessToken)
                    put("refreshToken", account.refreshToken)
                    put("expiresAt", account.expiresAt)
                    put("domain", account.domain)
                    put("uid", account.uid)
                    put("nickname", account.nickname)
                    put("enterpriseId", account.enterpriseId ?: "")
                    put("source", account.source)
                },
            )
        }
        fileFor(region).writeText(array.toString())
    }

    /**
     * Adds a credential, or replaces the one with the same account when it is
     * already stored. Replacing rather than duplicating means signing in again
     * renews a token instead of creating a confusing second entry.
     */
    fun save(region: Wire.Region, credential: Credential): SavedAccount {
        val id = accountId(region, credential.uid, credential.domain, credential.accessToken)
        val account = SavedAccount(
            id = id,
            region = region,
            nickname = credential.nickname,
            uid = credential.uid,
            domain = credential.domain.ifEmpty { defaultDomain(region) },
            accessToken = credential.accessToken,
            refreshToken = credential.refreshToken,
            expiresAt = credential.expiresAt,
            enterpriseId = credential.enterpriseId,
            source = credential.source,
        )
        val current = accounts(region).toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index >= 0) current[index] = account else current.add(account)
        writeAccounts(region, current)
        setActive(region, id)
        return account
    }

    /** Removes one account; the selection falls back to another one. */
    fun delete(region: Wire.Region, accountId: String) {
        val remaining = accounts(region).filterNot { it.id == accountId }
        writeAccounts(region, remaining)
        if (activeId(region) == accountId) {
            remaining.firstOrNull()?.let { setActive(region, it.id) }
                ?: clearActive(region)
        }
    }

    fun clear(region: Wire.Region) {
        fileFor(region).delete()
        clearActive(region)
    }

    /**
     * Stable id for an account. The uid identifies a person, so it is preferred;
     * a credential without one falls back to its domain plus a token digest so
     * two such accounts still get distinct ids.
     */
    private fun accountId(region: Wire.Region, uid: String, domain: String, accessToken: String = ""): String =
        if (uid.isNotBlank()) "$region:${uid.take(8)}"
        else "$region:${domain}:${accessToken.hashCode()}"

    // ------------------------------------------------------------------ //
    // Selection
    // ------------------------------------------------------------------ //

    /** The build currently in use. */
    fun activeRegion(): Wire.Region {
        val stored = prefs.getString(KEY_ACTIVE_REGION, null) ?: return Wire.Region.CN
        return runCatching { Wire.Region.valueOf(stored) }.getOrDefault(Wire.Region.CN)
    }

    fun setActiveRegion(region: Wire.Region) {
        prefs.edit().putString(KEY_ACTIVE_REGION, region.name).apply()
        mirrorActiveForDaemon()
    }

    /**
     * Selects which saved account a build uses. The id is ignored when it does
     * not belong to the build, so a stale selection cannot leak across.
     */
    /** Looks one account up by id, or null when it is no longer stored. */
    fun account(region: Wire.Region, accountId: String): SavedAccount? =
        accounts(region).firstOrNull { it.id == accountId }

    fun selectAccount(region: Wire.Region, accountId: String) {
        if (accounts(region).none { it.id == accountId }) return
        setActive(region, accountId)
    }

    private fun activeId(region: Wire.Region): String? = prefs.getString("$KEY_ACTIVE_ID${region.name}", null)

    private fun setActive(region: Wire.Region, accountId: String) {
        prefs.edit().putString("$KEY_ACTIVE_ID${region.name}", accountId).apply()
        mirrorActiveForDaemon()
    }

    private fun clearActive(region: Wire.Region) {
        prefs.edit().remove("$KEY_ACTIVE_ID${region.name}").apply()
    }

    /** The account in use, or null when that build holds none. */
    fun active(): Credential? = activeAccount()?.toCredential()

    fun activeAccount(): SavedAccount? {
        val region = activeRegion()
        val list = accounts(region)
        if (list.isEmpty()) return null
        val selected = activeId(region)
        return list.firstOrNull { it.id == selected } ?: list.first()
    }

    /** Whether a build has at least one saved account. */
    fun has(region: Wire.Region): Boolean = accounts(region).isNotEmpty()

    fun hasAny(): Boolean = Wire.Region.entries.any { has(it) }

    // ------------------------------------------------------------------ //
    // Refresh
    // ------------------------------------------------------------------ //

    fun needsRefresh(credential: Credential): Boolean {
        if (credential.expiresAt <= 0) return true
        val nowSeconds = System.currentTimeMillis() / 1000
        return nowSeconds + REFRESH_MARGIN_SECONDS >= credential.expiresAt
    }

    /**
     * Returns a credential safe to send, renewing first when it is inside the
     * margin. The refreshed token is written back to the account it came from,
     * so a switch back does not serve a stale token.
     */
    @Synchronized
    fun resolve(renew: (Credential) -> Credential): Credential? {
        val account = activeAccount() ?: return null
        if (!needsRefresh(account.toCredential())) return account.toCredential()
        return runCatching {
            val renewed = renew(account.toCredential())
            val region = account.region
            val updated = accounts(region).map {
                if (it.id == account.id) it.copy(
                    accessToken = renewed.accessToken,
                    refreshToken = renewed.refreshToken,
                    expiresAt = renewed.expiresAt,
                    domain = renewed.domain,
                ) else it
            }
            writeAccounts(region, updated)
            renewed
        }.getOrElse {
            // A failed renewal still returns the existing token when it has not
            // yet expired, so an unreachable refresh endpoint does not take a
            // working session down.
            android.util.Log.w("WBHub", "token refresh failed; using existing token", it)
            account.toCredential()
        }
    }

    /**
     * Publishes the account in use to the path a local consumer reads.
     * Only the active account is mirrored, which keeps the others' tokens out of
     * a world-readable location.
     */
    fun mirrorActiveForDaemon() {
        val target = File(DAEMON_AUTH_PATH)
        val credential = active()
        runCatching {
            target.parentFile?.mkdirs()
            if (credential == null) {
                if (target.exists()) target.delete()
                return@runCatching
            }
            val json = JSONObject().apply {
                put("accessToken", credential.accessToken)
                put("refreshToken", credential.refreshToken)
                put("expiresAt", credential.expiresAt)
                put("domain", credential.domain)
                put("uid", credential.uid)
                put("nickname", credential.nickname)
                put("enterpriseId", credential.enterpriseId ?: "")
            }
            target.writeText(json.toString(2))
            // A local consumer may run as another user, so the copy has to be
            // readable by it.
            target.setReadable(true, false)
        }
    }

    private fun defaultDomain(region: Wire.Region): String =
        if (region == Wire.Region.GLOBAL) "www.workbuddy.ai" else Wire.CN_CHAT_BASE

    private fun expiryFromJwt(token: String): Long {
        val parts = token.split(".")
        if (parts.size < 2) return 0L
        return runCatching {
            val bytes = android.util.Base64.decode(
                parts[1],
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
            )
            JSONObject(String(bytes, Charsets.UTF_8)).optLong("exp", 0L)
        }.getOrDefault(0L)
    }

    companion object {
        /** Where a local consumer expects the account in use. */
        const val DAEMON_AUTH_PATH = "/data/local/tmp/wb-hub/wb-auth.json"

        private const val KEY_ACTIVE_REGION = "active_region"
        private const val KEY_ACTIVE_ID = "active_account_"

        /** Renew this long before the token actually expires. */
        private const val REFRESH_MARGIN_SECONDS = 5 * 60L
    }
}
