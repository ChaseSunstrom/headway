# This file is part of Headway.
# Copyright (C) 2026 The Headway Authors
# SPDX-License-Identifier: GPL-3.0-or-later
#
# R8 rules for the release build.
#
# This file is listed in app/build.gradle.kts's `proguardFiles(...)` and did not
# exist until now. AGP treats a missing entry as an empty ruleset rather than an
# error, so the omission was silent — and the release build it produced shipped a
# permanently broken speech recogniser. Debug builds were fine, which is the
# worst version of this bug.

# --- Vosk -------------------------------------------------------------------
#
# CarVoiceStream loads the recognizer by name (VOSK_RECOGNIZER_CLASS) instead of
# referencing it, deliberately: :core-voice declares Vosk `compileOnly`, so a
# static reference would make the class reachable and R8 would fail the build
# outright on a variant that ships no Vosk. The cost of that seam is that R8
# sees no reference at all and strips the class, after which Class.forName
# throws ClassNotFoundException — caught, and reported to the user as
# "this build cannot run the speech model".
#
# Keep the constructor the reflection actually calls, not just the type.
-keep class dev.headway.voice.VoskSpeechRecognizer {
    <init>(java.lang.String, int);
}

# JNI entry points. The native library resolves these by name at runtime, so
# nothing in the Java/Kotlin graph references them and everything here is
# invisible to R8's reachability analysis.
-keep class org.vosk.** { *; }
-keep class org.vosk.android.** { *; }

# JNA, which Vosk uses to bind the native library. Its callbacks and structure
# classes are likewise resolved reflectively.
-dontwarn com.sun.jna.**
-keep class com.sun.jna.** { *; }

# --- Protobuf ---------------------------------------------------------------
#
# protobuf-lite builds messages through generated static fields and reflection
# over the schema; stripping members produces a runtime failure to parse rather
# than a build error, which would surface as an unexplained handshake failure.
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

# --- Kotlin coroutines ------------------------------------------------------
-dontwarn kotlinx.coroutines.**

# --- Diagnostics ------------------------------------------------------------
#
# Headway's whole real-car debugging story is the exported log, and several of
# its lines print `javaClass.simpleName` for an exception whose type is the only
# clue. Obfuscated names would make those lines useless.
-keepnames class dev.headway.** { *; }
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*
-renamesourcefileattribute SourceFile
