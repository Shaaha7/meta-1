/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.compose.compiler)
}

val localProperties =
    Properties().apply {
      val file = rootDir.resolve("local.properties")
      if (file.exists()) {
        file.inputStream().use { load(it) }
      }
    }

android {
  namespace = "com.meta.wearable.dat.externalsampleapps.cameraaccess"
  compileSdk = 36

  buildFeatures { buildConfig = true }

  defaultConfig {
    applicationId = "ae.ustad.app"
    minSdk = 31
    targetSdk = 36
    versionCode = 9
    // Shown in the Ustad hub, so it's easy to see which build is on the phone.
    versionName = "2.2 (new design + accuracy test)"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // Meta Wearables Device Access Toolkit Setup
    // Without Developer Mode, these values need to be set with credentials from the app registered
    // in Wearables Developer Center
    manifestPlaceholders["mwdat_application_id"] =
        localProperties.getProperty("mwdat_application_id", "")
    manifestPlaceholders["mwdat_client_token"] =
        localProperties.getProperty("mwdat_client_token", "")

    // Ustad backend (../backend): when set, the app sends AI and voice requests there with an app
    // token and holds NO Gemini / ElevenLabs keys. Recommended for any APK you share.
    buildConfigField(
        "String", "USTAD_BACKEND_URL", "\"${localProperties.getProperty("USTAD_BACKEND_URL", "")}\"")
    buildConfigField(
        "String", "USTAD_APP_TOKEN", "\"${localProperties.getProperty("USTAD_APP_TOKEN", "")}\"")
    // Ustad: Gemini key and model from local.properties (never commit the key).
    buildConfigField(
        "String", "GEMINI_API_KEY", "\"${localProperties.getProperty("GEMINI_API_KEY", "")}\"")
    buildConfigField(
        "String",
        "GEMINI_MODEL",
        "\"${localProperties.getProperty("GEMINI_MODEL", "gemini-flash-latest")}\"")
    // Backup AI when Gemini is busy: OpenRouter (free key at openrouter.ai/keys).
    buildConfigField(
        "String",
        "OPENROUTER_API_KEY",
        "\"${localProperties.getProperty("OPENROUTER_API_KEY", "")}\"")
    buildConfigField(
        "String",
        "OPENROUTER_MODEL",
        "\"${localProperties.getProperty("OPENROUTER_MODEL", "dots-studio/dots-3-note-preview:free")}\"")
    // Offline backup: Meta's Llama 3.2 Vision on the laptop through Ollama
    // (`ollama pull llama3.2-vision`), reached from the phone over USB via
    // `adb reverse tcp:11434 tcp:11434`. Set LOCAL_AI_URL= (empty) to disable.
    buildConfigField(
        "String",
        "LOCAL_AI_URL",
        "\"${localProperties.getProperty("LOCAL_AI_URL", "http://127.0.0.1:11434/v1/chat/completions")}\"")
    buildConfigField(
        "String", "LOCAL_AI_MODEL", "\"${localProperties.getProperty("LOCAL_AI_MODEL", "llama3.2-vision")}\"")
    // Natural voice: ElevenLabs (free plan ~10k characters/month). Empty key = phone's own voice.
    buildConfigField(
        "String",
        "ELEVENLABS_API_KEY",
        "\"${localProperties.getProperty("ELEVENLABS_API_KEY", "")}\"")
    buildConfigField(
        "String",
        "ELEVENLABS_VOICE_ID",
        "\"${localProperties.getProperty("ELEVENLABS_VOICE_ID", "21m00Tcm4TlvDq8ikWAM")}\"")
    buildConfigField(
        "String",
        "ELEVENLABS_MODEL",
        "\"${localProperties.getProperty("ELEVENLABS_MODEL", "eleven_flash_v2_5")}\"")
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.exifinterface)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.material.icons.extended)
  implementation(libs.androidx.material3)
  implementation(libs.kotlinx.collections.immutable)
  implementation(libs.mwdat.core)
  implementation(libs.mwdat.camera)
  implementation(libs.mwdat.mockdevice)
  // Ustad: on-device face detection (bundled model, works offline) for the privacy filter.
  implementation(libs.mlkit.face.detection)
  // Ustad: on-device text reading, used to find and blur car number plates.
  implementation(libs.mlkit.text.recognition)
  // Ustad: Ray-Ban Display (in-lens view). Does nothing on glasses without a display.
  implementation(libs.mwdat.display)
  testImplementation(libs.junit)
  // Real org.json for JVM unit tests (the Android one is a stub there).
  testImplementation(libs.org.json)
  androidTestImplementation(libs.androidx.ui.test.junit4)
  androidTestImplementation(libs.androidx.test.uiautomator)
  androidTestImplementation(libs.androidx.test.rules)
}
