# -----------------------------------------------------------------------------
# BundleCam / Recon R8 rules
# -----------------------------------------------------------------------------
# Release builds enable `isMinifyEnabled = true` + `isShrinkResources = true`.
# Keep these rules minimal: only add what R8 cannot infer.
# -----------------------------------------------------------------------------

# Preserve source / line numbers in crash traces (Play Console de-obfuscates
# with the uploaded mapping.txt, but keeping SourceFile makes raw logcat usable).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# -----------------------------------------------------------------------------
# kotlinx.serialization
# -----------------------------------------------------------------------------
# Official rules from https://github.com/Kotlin/kotlinx.serialization/blob/master/rules/common.pro
# — keep the generated `$$serializer` INSTANCE and the Companion.serializer()
# accessor for every @Serializable class, so runtime lookup via KClass works.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

-if @kotlinx.serialization.Serializable class ** {
    static **$Companion Companion;
}
-keepclassmembers class <2>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}

-if @kotlinx.serialization.Serializable class **
-keep class <1>$$serializer {
    *** INSTANCE;
}

# Belt-and-suspenders for this project's own @Serializable types.
-keep,includedescriptorclasses class com.example.bundlecam.**$$serializer { *; }
-keepclassmembers class com.example.bundlecam.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.bundlecam.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# -----------------------------------------------------------------------------
# WorkManager
# -----------------------------------------------------------------------------
# BundleWorker is instantiated by fully-qualified class name via WorkManager's
# default worker factory; R8 cannot see that reflection.
-keep class com.example.bundlecam.pipeline.BundleWorker { *; }
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# -----------------------------------------------------------------------------
# Room (transitive via WorkManager — it backs its internal DB with Room)
# -----------------------------------------------------------------------------
# Room generates `*_Impl` subclasses for every @Database class (e.g.
# `androidx.work.impl.WorkDatabase_Impl`) and instantiates them reflectively
# via `Class.getDeclaredConstructor().newInstance()`. R8 strips the no-arg
# constructor because nothing in minified bytecode calls `new WorkDatabase_Impl()`
# directly, which causes `NoSuchMethodException: WorkDatabase_Impl.<init>[]`
# in Application.onCreate.
-keep class * extends androidx.room.RoomDatabase {
    <init>();
}
-dontwarn androidx.room.paging.**

# -----------------------------------------------------------------------------
# Application class (manifest-referenced by name)
# -----------------------------------------------------------------------------
-keep class com.example.bundlecam.BundleCamApp { *; }

# -----------------------------------------------------------------------------
# CameraX + extensions
# -----------------------------------------------------------------------------
# Extensions load vendor libraries reflectively; keep the extensions-interface
# surface and anything the vendor shim expects to find by name.
-keep class androidx.camera.extensions.** { *; }
-keep class androidx.camera.extensions.internal.** { *; }
-keep interface androidx.camera.extensions.impl.** { *; }
-dontwarn androidx.camera.extensions.impl.**

# -----------------------------------------------------------------------------
# Google Play services (location)
# -----------------------------------------------------------------------------
# Play services ship their own consumer rules, but silence warnings for the
# optional transitive surfaces R8 may not see at link time.
-dontwarn com.google.android.gms.**

# -----------------------------------------------------------------------------
# Kotlin
# -----------------------------------------------------------------------------
# Kotlin reflection metadata — required for kotlinx.serialization's KClass lookups
# and for a few coroutine internals.
-keep class kotlin.Metadata { *; }
-dontwarn kotlinx.coroutines.debug.**

# -----------------------------------------------------------------------------
# JSR305 / nullability annotations (pulled in transitively)
# -----------------------------------------------------------------------------
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**
