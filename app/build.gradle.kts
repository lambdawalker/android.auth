import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.apexfission.android"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.apexfission.android"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        fun configString(name: String) = "\"" + providers.gradleProperty(name).orElse("").get()
            .replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
        buildConfigField("String", "ATTESTRA_API_URL", configString("attestra.apiBaseUrl"))
        buildConfigField("String", "ATTESTRA_CONSENT_VERSION", configString("attestra.consentVersion"))
        buildConfigField("String", "ATTESTRA_TERMS_URL", configString("attestra.termsUrl"))
        buildConfigField("String", "ATTESTRA_RP_DOMAIN", configString("attestra.rpDomain"))
        val rpDomain = providers.gradleProperty("attestra.rpDomain").orElse("").get()
        require(rpDomain.isEmpty() || URI("https://$rpDomain").host == rpDomain) {
            "attestra.rpDomain must be a domain without a scheme, port or path"
        }
        val statements = if (rpDomain.isEmpty()) "[]" else """[{"include":"https://$rpDomain/.well-known/assetlinks.json"}]"""
        resValue("string", "asset_statements", statements.replace("\"", "\\\""))

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }
}

dependencies {
    implementation(project(":auth"))
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}