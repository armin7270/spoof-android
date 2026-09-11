# ---------------------------------------------------------------------------
# R8 rules. The codebase deliberately uses no reflection, so the only rules
# needed here are for kotlinx.serialization and a readability carve-out for the
# desync engine, whose class names appear in user-facing diagnostics.
# ---------------------------------------------------------------------------

-keepattributes *Annotation*, InnerClasses, Signature

# --- kotlinx.serialization ---------------------------------------------------
# Serializers are generated at compile time but linked by name at runtime, so
# R8 must not rename or strip them (ProxyConfig, SpoofProfile).
-keep,includedescriptorclasses class com.armin7270.snispoof.**$$serializer { *; }
-keepclassmembers class com.armin7270.snispoof.** {
    *** Companion;
}
-keepclasseswithmembers class com.armin7270.snispoof.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- desync engine ------------------------------------------------------------
# Kept unobfuscated so log lines and crash traces stay meaningful.
-keep class com.armin7270.snispoof.core.desync.** { *; }
