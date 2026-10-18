package com.example.auth

data class NudgyUser(
    val uid: String,
    val displayName: String,
    val email: String,
    val photoUrl: String? = null,
    val isGuest: Boolean = false,
    val initials: String = extractInitials(displayName, email)
) {
    companion object {
        fun extractInitials(name: String, email: String): String {
            val trimmedName = name.trim()
            if (trimmedName.isNotEmpty()) {
                val parts = trimmedName.split("\\s+".toRegex())
                return if (parts.size >= 2) {
                    "${parts[0].firstOrNull()?.uppercaseChar() ?: ""}${parts[1].firstOrNull()?.uppercaseChar() ?: ""}"
                } else {
                    trimmedName.take(2).uppercase()
                }
            }
            return email.take(2).uppercase().ifEmpty { "AR" }
        }
    }
}
