# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# =============================================================================
# BitChord release keep rules
# -----------------------------------------------------------------------------
# R8 minify is enabled for release only. The app is reflection-heavy, so the
# blocks below pin every known reflective surface. R8 breakage is a RUNTIME
# failure, not a build failure — these rules MUST be device-tested (stream
# resolve via Rhino, Discord RPC, source extensions, automix) before a stable
# tag ships. Debug builds stay unminified.
#
# AndroidX / Material / Compose / Coil / Haze / Security-Crypto / Palette /
# Media3 ship their own consumer-rules AARs and are intentionally NOT
# duplicated here.
# =============================================================================

# -----------------------------------------------------------------------------
# kotlinx.serialization 1.7.x
# Essential keeps: plugin-generated serializers and hand-written KSerializer
# implementations. (Companion-field keeps omitted — they were malformed R8
# syntax and are only a reflection-lookup optimization.)
# -----------------------------------------------------------------------------
# Keep the plugin-generated serializer classes.
-keep class **$$serializer {
    *;
}

# Keep fields of @Serializable classes.
-keepclassmembers class ** {
    @kotlinx.serialization.Serializable <fields>;
}

# Keep any custom KSerializer implementations (named serializers, not the
# generated $$serializer ones above).
-keep class * implements kotlinx.serialization.KSerializer {
    *;
}

# -----------------------------------------------------------------------------
# Mozilla Rhino 1.8.1 (executes YouTube's player JS in StreamResolver)
# Rhino reflects heavily into its OWN classes at runtime (the JS engine's
# internal Java adapters, Continuation, JavaMembers, etc.). Our own classes are
# NOT exposed to the JS scope (no Context.javaToJS / scope.put of app objects
# was found), so the risk is Rhino's internals being stripped, not ours.
# UNCERTAINTY: Rhino's reflective reach is broad; keeping the whole engine
# package is the safe baseline. Narrow this once device-tested.
# -----------------------------------------------------------------------------
-keep class org.mozilla.javascript.** { *; }
-keepclassmembers class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**

# -----------------------------------------------------------------------------
# NewPipeExtractor v0.26.3 (stripped vendored jar)
# The downloader interfaces and a few utils are reached reflectively by the
# extractor's own code. Package is org.schabi.newpipe.extractor (the Maven
# group id is com.github.TeamNewPipe, not a runtime package).
# -----------------------------------------------------------------------------
-keep class org.schabi.newpipe.extractor.** { *; }
-keepclassmembers class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**

# -----------------------------------------------------------------------------
# Ktor 3.0.3 client
# Ktor's client/core reflect on coroutine/Continuation internals and on
# serializer lookups. Keeping the whole namespace is the safe baseline; it can
# be tightened (e.g. to io.ktor.client.** + io.ktor.serialization.**) once
# device-tested. Ktor's own AAR consumer rules cover most of this, but the
# explicit keep guards the reflective paths R8 cannot see.
# -----------------------------------------------------------------------------
-keep class io.ktor.** { *; }
-keepclassmembers class io.ktor.** { *; }
-dontwarn io.ktor.**

# -----------------------------------------------------------------------------
# QuickJS-kt (dokar) 1.0.5 — JS module execution for source extensions
# The library reaches our FunctionBinding / AsyncFunctionBinding
# implementations (anonymous classes in ExtensionSource and QuickJsExecutor)
# via JNI by method signature, so their `call` overrides must survive
# optimization/renaming. The interface-implementation rule below covers those
# anonymous classes regardless of their generated names. The library's own
# classes are kept too.
# -----------------------------------------------------------------------------
-keep class com.dokar.quickjs.** { *; }
-keepclassmembers class com.dokar.quickjs.** { *; }
-keepclassmembers class * implements com.dokar.quickjs.binding.FunctionBinding {
    public *;
}
-keepclassmembers class * implements com.dokar.quickjs.binding.AsyncFunctionBinding {
    public *;
}
-dontwarn com.dokar.quickjs.**

# -----------------------------------------------------------------------------
# ONNX Runtime 1.28 (JNI) — on-device beat/downbeat model (Automix)
# Native methods and the JNI bridge classes must not be renamed or stripped.
# -----------------------------------------------------------------------------
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** {
    native <methods>;
    *;
}
-dontwarn ai.onnxruntime.**
