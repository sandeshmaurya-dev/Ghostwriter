package com.musheer360.swiftslate.model

/**
 * Single source of truth for the SharedPreferences keys used by the
 * provider/model configuration flow. Centralizing these prevents silent
 * breakage from mistyped string literals scattered across UI, service, and
 * client code.
 *
 * Values are unchanged from the literals previously used inline, so existing
 * stored preferences continue to resolve identically.
 */
object PrefKeys {
    /** Active provider ("gemini" | "groq" | "custom") — see [ProviderType]. */
    const val PROVIDER_TYPE = "provider_type"

    /** Selected Gemini model id. */
    const val GEMINI_MODEL = "model"

    /** Selected Groq model id. */
    const val GROQ_MODEL = "groq_model"

    /** Custom (OpenAI-compatible) model id. */
    const val CUSTOM_MODEL = "custom_model"

    /** Custom (OpenAI-compatible) endpoint base URL. */
    const val CUSTOM_ENDPOINT = "custom_endpoint"

    /** Selected Cloud model (Ghostwriter Cloud provider). */
    const val CLOUD_MODEL = "cloud_model"

    /**
     * Optional override of the baked Ghostwriter Cloud Worker URL. Blank in normal
     * installs (the compiled default is used); set only for testing a redeploy without
     * touching the APK.
     */
    const val CLOUD_ENDPOINT = "cloud_endpoint"

    /** False (default) = Simple setup mode; true reveals the full technical settings. */
    const val ADVANCED_SETTINGS = "advanced_settings"

    /** Sampling temperature (Float). */
    const val TEMPERATURE = "temperature"

    /** Epoch millis when structured output was last disabled (0 = never). */
    const val STRUCTURED_OUTPUT_DISABLED_AT = "structured_output_disabled_at"

    /**
     * Packages whose text-changed events must never trigger commands (JSON array of
     * package names, stored in the "settings" prefs). Entry point for the
     * excluded-apps feature: banking and password apps where an accessibility
     * text rewrite would be inappropriate even though the user enabled the service.
     */
    const val EXCLUDED_PACKAGES = "excluded_packages"

    /**
     * When true (default), AI commands stream: the field is rewritten live as chunks
     * arrive instead of showing a spinner until the whole answer lands. The final
     * verified write still happens through [AssistantService.replaceText], so what is
     * left in the field is byte-identical either way.
     */
    const val STREAMING_ENABLED = "streaming_enabled"
}
