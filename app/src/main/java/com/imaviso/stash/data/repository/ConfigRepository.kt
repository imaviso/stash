package com.imaviso.stash.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.imaviso.stash.data.model.S3Account
import com.imaviso.stash.data.model.S3Config
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "s3_config")

class ConfigRepository(
    private val context: Context,
) {
    companion object {
        // Legacy single account keys (for backward compatibility)
        private val ENDPOINT = stringPreferencesKey("endpoint")
        private val ACCESS_KEY = stringPreferencesKey("access_key")
        private val SECRET_KEY = stringPreferencesKey("secret_key")
        private val REGION = stringPreferencesKey("region")
        private val USE_PATH_STYLE = booleanPreferencesKey("use_path_style")

        // Multiple accounts keys
        private val ACCOUNTS_JSON = stringPreferencesKey("accounts_json")
        private val ACTIVE_ACCOUNT_ID = stringPreferencesKey("active_account_id")

        // Navigation state - stores last path per bucket as JSON
        private val BUCKET_PATHS_JSON = stringPreferencesKey("bucket_paths_json")
    }

    // Legacy config flow for backward compatibility
    val configFlow: Flow<S3Config> =
        context.dataStore.data.map { prefs ->
            // First check if we have an active account
            val activeId = prefs[ACTIVE_ACCOUNT_ID]
            val accountsJson = prefs[ACCOUNTS_JSON]

            if (activeId != null && accountsJson != null) {
                val accounts = parseAccounts(accountsJson)
                val activeAccount = accounts.find { it.id == activeId }
                if (activeAccount != null) {
                    return@map activeAccount.toConfig()
                }
            }

            // Fall back to legacy single account
            S3Config(
                endpoint = prefs[ENDPOINT] ?: "",
                accessKey = prefs[ACCESS_KEY] ?: "",
                secretKey = prefs[SECRET_KEY] ?: "",
                region = prefs[REGION] ?: "us-east-1",
                usePathStyle = prefs[USE_PATH_STYLE] ?: true,
            )
        }

    // Flow for all saved accounts
    val accountsFlow: Flow<List<S3Account>> =
        context.dataStore.data.map { prefs ->
            val accountsJson = prefs[ACCOUNTS_JSON]
            if (accountsJson != null) {
                parseAccounts(accountsJson)
            } else {
                // Migrate legacy account if exists
                val legacyEndpoint = prefs[ENDPOINT]
                if (!legacyEndpoint.isNullOrBlank()) {
                    listOf(
                        S3Account(
                            id = "legacy",
                            name = "Default Account",
                            endpoint = legacyEndpoint,
                            accessKey = prefs[ACCESS_KEY] ?: "",
                            secretKey = prefs[SECRET_KEY] ?: "",
                            region = prefs[REGION] ?: "us-east-1",
                            usePathStyle = prefs[USE_PATH_STYLE] ?: true,
                        ),
                    )
                } else {
                    emptyList()
                }
            }
        }

    // Flow for active account ID
    val activeAccountIdFlow: Flow<String?> =
        context.dataStore.data.map { prefs ->
            prefs[ACTIVE_ACCOUNT_ID]
        }

    // Get active account
    val activeAccountFlow: Flow<S3Account?> =
        context.dataStore.data.map { prefs ->
            val activeId = prefs[ACTIVE_ACCOUNT_ID]
            val accountsJson = prefs[ACCOUNTS_JSON]

            if (activeId != null && accountsJson != null) {
                val accounts = parseAccounts(accountsJson)
                accounts.find { it.id == activeId }
            } else {
                // Return legacy account if exists
                val legacyEndpoint = prefs[ENDPOINT]
                if (!legacyEndpoint.isNullOrBlank()) {
                    S3Account(
                        id = "legacy",
                        name = "Default Account",
                        endpoint = legacyEndpoint,
                        accessKey = prefs[ACCESS_KEY] ?: "",
                        secretKey = prefs[SECRET_KEY] ?: "",
                        region = prefs[REGION] ?: "us-east-1",
                        usePathStyle = prefs[USE_PATH_STYLE] ?: true,
                    )
                } else {
                    null
                }
            }
        }

    // Save a new account or update existing
    suspend fun saveAccount(account: S3Account) {
        context.dataStore.edit { prefs ->
            val accountsJson = prefs[ACCOUNTS_JSON]
            val accounts =
                if (accountsJson != null) {
                    parseAccounts(accountsJson).toMutableList()
                } else {
                    mutableListOf()
                }

            // Remove existing account with same ID
            accounts.removeAll { it.id == account.id }
            accounts.add(account)

            prefs[ACCOUNTS_JSON] = serializeAccounts(accounts)

            // Set as active if it's the first account or no active account
            if (prefs[ACTIVE_ACCOUNT_ID] == null || accounts.size == 1) {
                prefs[ACTIVE_ACCOUNT_ID] = account.id
            }
        }
    }

    // Delete an account
    suspend fun deleteAccount(accountId: String) {
        context.dataStore.edit { prefs ->
            val accountsJson = prefs[ACCOUNTS_JSON]
            if (accountsJson != null) {
                val accounts = parseAccounts(accountsJson).toMutableList()
                accounts.removeAll { it.id == accountId }
                prefs[ACCOUNTS_JSON] = serializeAccounts(accounts)

                // If deleted account was active, switch to first available
                if (prefs[ACTIVE_ACCOUNT_ID] == accountId) {
                    prefs[ACTIVE_ACCOUNT_ID] = accounts.firstOrNull()?.id ?: ""
                }
            }
        }
    }

    // Set active account
    suspend fun setActiveAccount(accountId: String) {
        context.dataStore.edit { prefs ->
            prefs[ACTIVE_ACCOUNT_ID] = accountId
        }
    }

    // Legacy save method for backward compatibility
    suspend fun saveConfig(config: S3Config) {
        context.dataStore.edit { prefs ->
            prefs[ENDPOINT] = config.endpoint
            prefs[ACCESS_KEY] = config.accessKey
            prefs[SECRET_KEY] = config.secretKey
            prefs[REGION] = config.region
            prefs[USE_PATH_STYLE] = config.usePathStyle
        }
    }

    suspend fun clearConfig() {
        context.dataStore.edit { it.clear() }
    }

    // JSON serialization helpers
    private fun serializeAccounts(accounts: List<S3Account>): String {
        val jsonArray = JSONArray()
        accounts.forEach { account ->
            val jsonObj =
                JSONObject().apply {
                    put("id", account.id)
                    put("name", account.name)
                    put("endpoint", account.endpoint)
                    put("accessKey", account.accessKey)
                    put("secretKey", account.secretKey)
                    put("region", account.region)
                    put("usePathStyle", account.usePathStyle)
                }
            jsonArray.put(jsonObj)
        }
        return jsonArray.toString()
    }

    private fun parseAccounts(json: String): List<S3Account> =
        try {
            val jsonArray = JSONArray(json)
            (0 until jsonArray.length()).map { i ->
                val obj = jsonArray.getJSONObject(i)
                S3Account(
                    id = obj.getString("id"),
                    name = obj.optString("name", "Unnamed"),
                    endpoint = obj.getString("endpoint"),
                    accessKey = obj.getString("accessKey"),
                    secretKey = obj.getString("secretKey"),
                    region = obj.optString("region", "us-east-1"),
                    usePathStyle = obj.optBoolean("usePathStyle", true),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }

    // ==================== NAVIGATION STATE ====================

    /**
     * Data class to hold navigation state for a bucket
     */
    data class BucketNavState(
        val currentPrefix: String,
        val pathHistory: List<String>,
        val scrollIndex: Int = 0,
        val scrollOffset: Int = 0,
    )

    /**
     * Save navigation state for a bucket
     */
    suspend fun saveBucketNavState(
        bucketName: String,
        prefix: String,
        pathHistory: List<String>,
        scrollIndex: Int = 0,
        scrollOffset: Int = 0,
    ) {
        context.dataStore.edit { prefs ->
            val pathsJson = prefs[BUCKET_PATHS_JSON] ?: "{}"
            val pathsObj =
                try {
                    JSONObject(pathsJson)
                } catch (e: Exception) {
                    JSONObject()
                }

            val stateObj =
                JSONObject().apply {
                    put("prefix", prefix)
                    put("pathHistory", JSONArray(pathHistory))
                    put("scrollIndex", scrollIndex)
                    put("scrollOffset", scrollOffset)
                }
            pathsObj.put(bucketName, stateObj)

            prefs[BUCKET_PATHS_JSON] = pathsObj.toString()
        }
    }

    /**
     * Get saved navigation state for a bucket
     */
    suspend fun getBucketNavState(bucketName: String): BucketNavState? {
        val prefs = context.dataStore.data.first()
        val pathsJson = prefs[BUCKET_PATHS_JSON] ?: return null

        return try {
            val pathsObj = JSONObject(pathsJson)
            if (pathsObj.has(bucketName)) {
                val stateObj = pathsObj.getJSONObject(bucketName)
                val prefix = stateObj.optString("prefix", "")
                val historyArray = stateObj.optJSONArray("pathHistory")
                val pathHistory =
                    if (historyArray != null) {
                        (0 until historyArray.length()).map { historyArray.getString(it) }
                    } else {
                        listOf("")
                    }
                val scrollIndex = stateObj.optInt("scrollIndex", 0)
                val scrollOffset = stateObj.optInt("scrollOffset", 0)
                BucketNavState(prefix, pathHistory, scrollIndex, scrollOffset)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Clear navigation state for a bucket (e.g., when bucket is deleted)
     */
    suspend fun clearBucketNavState(bucketName: String) {
        context.dataStore.edit { prefs ->
            val pathsJson = prefs[BUCKET_PATHS_JSON] ?: return@edit
            val pathsObj =
                try {
                    JSONObject(pathsJson)
                } catch (e: Exception) {
                    return@edit
                }

            pathsObj.remove(bucketName)
            prefs[BUCKET_PATHS_JSON] = pathsObj.toString()
        }
    }
}
