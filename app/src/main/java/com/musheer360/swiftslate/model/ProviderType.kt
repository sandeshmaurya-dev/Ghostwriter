package com.musheer360.swiftslate.model

object ProviderType {
    const val GEMINI = "gemini"
    const val GROQ = "groq"
    const val CUSTOM = "custom"
    const val CLOUD = "cloud"

    private val VALID = setOf(GEMINI, GROQ, CUSTOM, CLOUD)

    /**
     * Unknown/absent values resolve to CLOUD: the zero-setup hosted backend is the
     * product default. A fresh install never visits the provider picker — it lands
     * on Ghostwriter Cloud with no user action at all.
     */
    fun sanitize(value: String?): String = if (value in VALID) value!! else CLOUD
}
