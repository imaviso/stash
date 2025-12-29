package com.imaviso.stash.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
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
        private const val TAG = "ConfigRepository"
        private const val ENCRYPTED_PREFS_NAME = "secure_credentials"
        private const val KEY_ACCOUNTS_JSON = "accounts_json"

        // Legacy single account keys (for backward compatibility)
        private val ENDPOINT = stringPreferencesKey("endpoint")
        private val ACCESS_KEY = stringPreferencesKey("access_key")
        private val SECRET_KEY = stringPreferencesKey("secret_key")
        private val REGION = stringPreferencesKey("region")
        private val USE_PATH_STYLE = booleanPreferencesKey("use_path_style")

        // Multiple accounts keys (now only stores active account ID in DataStore)
        private val ACCOUNTS_JSON = stringPreferencesKey("accounts_json") // Legacy, for migration
        private val ACTIVE_ACCOUNT_ID = stringPreferencesKey("active_account_id")

        // Navigation state - stores last path per bucket as JSON
        private val BUCKET_PATHS_JSON = stringPreferencesKey("bucket_paths_json")
    }

    // Encrypted SharedPreferences for secure credential storage
    private val encryptedPrefs: SharedPreferences by lazy {
        try {
            val masterKey =
                MasterKey
                    .Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

            EncryptedSharedPreferences.create(
                context,
                ENCRYPTED_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create encrypted prefs, falling back to regular prefs", e)
            // Fallback to regular SharedPreferences if encryption fails
            context.getSharedPreferences(ENCRYPTED_PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    // Helper to get accounts from encrypted storage
    private fun getAccountsFromSecureStorage(): List<S3Account> {
        val json = encryptedPrefs.getString(KEY_ACCOUNTS_JSON, null) ?: return emptyList()
        return parseAccounts(json)
    }

    // Helper to save accounts to encrypted storage
    private fun saveAccountsToSecureStorage(accounts: List<S3Account>) {
        encryptedPrefs
            .edit()
            .putString(KEY_ACCOUNTS_JSON, serializeAccounts(accounts))
            .apply()
    }

    // Initialize and migrate from unencrypted storage if needed
    init {
        migrateToEncryptedStorage()
    }

    private fun migrateToEncryptedStorage() {
        // Check if we already have accounts in encrypted storage
        val encryptedAccounts = getAccountsFromSecureStorage()
        if (encryptedAccounts.isNotEmpty()) {
            return // Already migrated
        }

        // Migration happens asynchronously on first access via configFlow
        Log.d(TAG, "Will check for legacy accounts to migrate on first access")
    }

    // Legacy config flow for backward compatibility
    val configFlow: Flow<S3Config> =
        context.dataStore.data.map { prefs ->
            // First check encrypted storage for active account
            val activeId = prefs[ACTIVE_ACCOUNT_ID]
            val accounts = getAccountsFromSecureStorage()

            if (activeId != null && accounts.isNotEmpty()) {
                val activeAccount = accounts.find { it.id == activeId }
                if (activeAccount != null) {
                    return@map activeAccount.toConfig()
                }
            }

            // Check for legacy unencrypted accounts and migrate
            val legacyAccountsJson = prefs[ACCOUNTS_JSON]
            if (legacyAccountsJson != null && accounts.isEmpty()) {
                val legacyAccounts = parseAccounts(legacyAccountsJson)
                if (legacyAccounts.isNotEmpty()) {
                    Log.d(TAG, "Migrating ${legacyAccounts.size} accounts to encrypted storage")
                    saveAccountsToSecureStorage(legacyAccounts)
                    val activeAccount =
                        if (activeId != null) {
                            legacyAccounts.find { it.id == activeId }
                        } else {
                            legacyAccounts.firstOrNull()
                        }
                    if (activeAccount != null) {
                        return@map activeAccount.toConfig()
                    }
                }
            }

            // Fall back to legacy single account
            val legacyEndpoint = prefs[ENDPOINT]
            if (!legacyEndpoint.isNullOrBlank()) {
                val legacyAccount =
                    S3Account(
                        id = "legacy",
                        name = "Default Account",
                        endpoint = legacyEndpoint,
                        accessKey = prefs[ACCESS_KEY] ?: "",
                        secretKey = prefs[SECRET_KEY] ?: "",
                        region = prefs[REGION] ?: "us-east-1",
                        usePathStyle = prefs[USE_PATH_STYLE] ?: true,
                    )
                // Migrate single legacy account to encrypted storage
                Log.d(TAG, "Migrating legacy single account to encrypted storage")
                saveAccountsToSecureStorage(listOf(legacyAccount))
                return@map legacyAccount.toConfig()
            }

            S3Config(
                endpoint = "",
                accessKey = "",
                secretKey = "",
                region = "us-east-1",
                usePathStyle = true,
            )
        }

    // Flow for all saved accounts (from encrypted storage)
    val accountsFlow: Flow<List<S3Account>> =
        context.dataStore.data.map { prefs ->
            val accounts = getAccountsFromSecureStorage()
            if (accounts.isNotEmpty()) {
                return@map accounts
            }

            // Check for legacy accounts to migrate
            val legacyAccountsJson = prefs[ACCOUNTS_JSON]
            if (legacyAccountsJson != null) {
                val legacyAccounts = parseAccounts(legacyAccountsJson)
                if (legacyAccounts.isNotEmpty()) {
                    saveAccountsToSecureStorage(legacyAccounts)
                    return@map legacyAccounts
                }
            }

            // Check for legacy single account
            val legacyEndpoint = prefs[ENDPOINT]
            if (!legacyEndpoint.isNullOrBlank()) {
                val legacyAccount =
                    S3Account(
                        id = "legacy",
                        name = "Default Account",
                        endpoint = legacyEndpoint,
                        accessKey = prefs[ACCESS_KEY] ?: "",
                        secretKey = prefs[SECRET_KEY] ?: "",
                        region = prefs[REGION] ?: "us-east-1",
                        usePathStyle = prefs[USE_PATH_STYLE] ?: true,
                    )
                saveAccountsToSecureStorage(listOf(legacyAccount))
                return@map listOf(legacyAccount)
            }

            emptyList()
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
            val accounts = getAccountsFromSecureStorage()

            if (activeId != null && accounts.isNotEmpty()) {
                accounts.find { it.id == activeId }
            } else if (accounts.isNotEmpty()) {
                accounts.firstOrNull()
            } else {
                null
            }
        }

    // Save a new account or update existing (now uses encrypted storage)
    suspend fun saveAccount(account: S3Account) {
        val accounts = getAccountsFromSecureStorage().toMutableList()

        // Remove existing account with same ID
        accounts.removeAll { it.id == account.id }
        accounts.add(account)

        // Save to encrypted storage
        saveAccountsToSecureStorage(accounts)

        // Update active account ID in DataStore
        context.dataStore.edit { prefs ->
            // Set as active if it's the first account or no active account
            if (prefs[ACTIVE_ACCOUNT_ID] == null || accounts.size == 1) {
                prefs[ACTIVE_ACCOUNT_ID] = account.id
            }
        }
    }

    // Delete an account (now uses encrypted storage)
    suspend fun deleteAccount(accountId: String) {
        val accounts = getAccountsFromSecureStorage().toMutableList()
        accounts.removeAll { it.id == accountId }
        saveAccountsToSecureStorage(accounts)

        context.dataStore.edit { prefs ->
            // If deleted account was active, switch to first available
            if (prefs[ACTIVE_ACCOUNT_ID] == accountId) {
                prefs[ACTIVE_ACCOUNT_ID] = accounts.firstOrNull()?.id ?: ""
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
        // Clear encrypted credentials
        encryptedPrefs.edit().clear().apply()
        // Clear DataStore
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
