package com.apexfission.android

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.viewmodel.compose.viewModel
import com.apexfission.android.auth.onboarding.HttpOnboardingApi
import com.apexfission.android.auth.onboarding.OnboardingScreen
import com.apexfission.android.auth.onboarding.OnboardingViewModel
import com.apexfission.android.ui.theme.AuthTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            AuthTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    val modifier = Modifier.padding(padding)
                    val apiUrl = BuildConfig.ATTESTRA_API_URL
                    val consent = BuildConfig.ATTESTRA_CONSENT_VERSION
                    val terms = BuildConfig.ATTESTRA_TERMS_URL
                    if (apiUrl.isBlank() || consent.isBlank() || BuildConfig.ATTESTRA_RP_DOMAIN.isBlank() || !terms.startsWith("https://")) {
                        Text("Set attestra.apiBaseUrl, attestra.consentVersion attestra.termsUrl and attestra.rpDomain in your Gradle properties, then rebuild. See auth/README.md.", modifier)
                    } else {
                        val api = remember(apiUrl) { HttpOnboardingApi(apiUrl) }
                        val vm: OnboardingViewModel = viewModel(factory = OnboardingViewModel.factory(api, consent))
                        val uriHandler = LocalUriHandler.current
                        var completed by remember { mutableStateOf(false) }
                        if (completed) Text("Account created. The host app should now open its sign-in flow. This sample implements onboarding only.", modifier)
                        else OnboardingScreen(vm, onOpenTerms = { uriHandler.openUri(terms) }, onAuthenticationRequired = { completed = true }, modifier = modifier)
                    }
                }
            }
        }
    }
}
