package com.imaviso.stash.util

/**
 * Utility for converting technical error messages to user-friendly messages
 */
object ErrorUtils {
    /**
     * Convert an exception or error message to a user-friendly message
     */
    fun formatError(e: Throwable): String = formatErrorMessage(e.message ?: e.javaClass.simpleName)

    /**
     * Convert a raw error message to a user-friendly message
     */
    fun formatErrorMessage(message: String): String {
        val lowerMessage = message.lowercase()

        return when {
            // Network errors
            lowerMessage.contains("unknownhostexception") ||
                lowerMessage.contains("unable to resolve host") ||
                lowerMessage.contains("no address associated") -> {
                "Cannot reach server. Check the endpoint URL and your internet connection."
            }

            lowerMessage.contains("connectexception") ||
                lowerMessage.contains("connection refused") -> {
                "Connection refused. The server may be down or the port may be incorrect."
            }

            lowerMessage.contains("sockettimeoutexception") ||
                lowerMessage.contains("timeout") ||
                lowerMessage.contains("timed out") -> {
                "Connection timed out. The server is not responding."
            }

            lowerMessage.contains("connectionreset") ||
                lowerMessage.contains("connection reset") -> {
                "Connection was reset. Please try again."
            }

            lowerMessage.contains("no route to host") -> {
                "Cannot reach the server. Check your network connection."
            }

            // SSL/TLS errors
            lowerMessage.contains("ssl") ||
                lowerMessage.contains("certificate") ||
                lowerMessage.contains("handshake") -> {
                "Secure connection failed. There may be a certificate issue with the server."
            }

            // S3 Authentication errors
            lowerMessage.contains("invalidaccesskeyid") -> {
                "Invalid access key. Please check your credentials."
            }

            lowerMessage.contains("signaturedoesnotmatch") -> {
                "Invalid secret key. Please verify your credentials."
            }

            lowerMessage.contains("accessdenied") ||
                lowerMessage.contains("access denied") -> {
                "Access denied. You don't have permission to perform this action."
            }

            lowerMessage.contains("expired") && lowerMessage.contains("token") -> {
                "Your session has expired. Please re-authenticate."
            }

            // S3 Bucket errors
            lowerMessage.contains("nosuchbucket") -> {
                "The bucket does not exist or you don't have access to it."
            }

            lowerMessage.contains("bucketalreadyexists") ||
                lowerMessage.contains("bucketalreadyownedby") -> {
                "A bucket with this name already exists."
            }

            lowerMessage.contains("bucketnotempty") -> {
                "Cannot delete bucket. The bucket must be empty first."
            }

            lowerMessage.contains("toomanybuckets") -> {
                "Cannot create bucket. You have reached the maximum number of buckets."
            }

            // S3 Object errors
            lowerMessage.contains("nosuchkey") -> {
                "The file does not exist or has been deleted."
            }

            lowerMessage.contains("entitytoolarge") ||
                lowerMessage.contains("too large") -> {
                "The file is too large to upload."
            }

            lowerMessage.contains("invalidbucketname") -> {
                "Invalid bucket name. Use only lowercase letters, numbers, and hyphens."
            }

            lowerMessage.contains("keytolong") ||
                lowerMessage.contains("key too long") -> {
                "The file path is too long."
            }

            // Rate limiting
            lowerMessage.contains("slowdown") ||
                lowerMessage.contains("toomanyrequests") ||
                lowerMessage.contains("rate") && lowerMessage.contains("limit") -> {
                "Too many requests. Please wait a moment and try again."
            }

            // Storage errors
            lowerMessage.contains("insufficientstorage") ||
                lowerMessage.contains("quotaexceeded") -> {
                "Storage quota exceeded. Free up some space and try again."
            }

            // Service errors
            lowerMessage.contains("serviceunavailable") ||
                lowerMessage.contains("internalerror") ||
                lowerMessage.contains("internal server error") -> {
                "The server is experiencing issues. Please try again later."
            }

            // Client initialization
            lowerMessage.contains("not initialized") -> {
                "Not connected. Please configure your S3 account first."
            }

            // Generic network issues
            lowerMessage.contains("network") && lowerMessage.contains("unreachable") -> {
                "Network is unreachable. Please check your connection."
            }

            // Fallback: clean up technical jargon
            else -> {
                cleanupTechnicalMessage(message)
            }
        }
    }

    /**
     * Clean up technical message by removing exception class names and stack traces
     */
    private fun cleanupTechnicalMessage(message: String): String {
        var cleaned = message

        // Remove common exception class prefixes
        val exceptionPatterns =
            listOf(
                "java.net.",
                "java.io.",
                "java.lang.",
                "kotlin.",
                "aws.sdk.",
                "aws.smithy.",
                "okhttp3.",
                "javax.net.",
                "Exception:",
                "Error:",
            )

        for (pattern in exceptionPatterns) {
            cleaned = cleaned.replace(pattern, "", ignoreCase = true)
        }

        // Remove class names like "SomeException: "
        cleaned = cleaned.replace(Regex("\\w+Exception:\\s*"), "")
        cleaned = cleaned.replace(Regex("\\w+Error:\\s*"), "")

        // Capitalize first letter
        cleaned =
            cleaned.trim().replaceFirstChar {
                if (it.isLowerCase()) it.titlecase() else it.toString()
            }

        // Ensure it ends with a period
        if (cleaned.isNotEmpty() && !cleaned.endsWith('.') && !cleaned.endsWith('!') && !cleaned.endsWith('?')) {
            cleaned += "."
        }

        return cleaned.ifEmpty { "An unexpected error occurred. Please try again." }
    }
}
