package com.imaviso.stash.data.model

import java.util.UUID

data class S3Config(
    val endpoint: String = "",
    val accessKey: String = "",
    val secretKey: String = "",
    val region: String = "us-east-1",
    val usePathStyle: Boolean = true  // Required for most S3-compatible services
) {
    fun isValid(): Boolean {
        return endpoint.isNotBlank() && 
               accessKey.isNotBlank() && 
               secretKey.isNotBlank()
    }
}

/**
 * Represents a saved S3 account with a unique identifier and display name
 */
data class S3Account(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",  // Display name for the account
    val endpoint: String = "",
    val accessKey: String = "",
    val secretKey: String = "",
    val region: String = "us-east-1",
    val usePathStyle: Boolean = true
) {
    fun isValid(): Boolean {
        return name.isNotBlank() &&
               endpoint.isNotBlank() && 
               accessKey.isNotBlank() && 
               secretKey.isNotBlank()
    }
    
    fun toConfig(): S3Config = S3Config(
        endpoint = endpoint,
        accessKey = accessKey,
        secretKey = secretKey,
        region = region,
        usePathStyle = usePathStyle
    )
}
