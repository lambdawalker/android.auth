package com.apexfission.android.auth.onboarding

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Embeddable direct-onboarding UI. Apply your MaterialTheme in the host.
 * onAuthenticationRequired is user-triggered after completion; onboarding does not issue login tokens.
 * The host must show published terms corresponding exactly to viewModel.consentVersion.
 */
@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onOpenTerms: () -> Unit,
    onAuthenticationRequired: (userPublicId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val session = state.session
    var nativeBusy by remember { mutableStateOf(false) }
    var editingProfile by remember(session?.id, session?.status) { mutableStateOf(false) }
    var confirmRestart by remember { mutableStateOf(false) }
    val enabled = !state.busy && !nativeBusy && !state.canRetry && !state.restartRequired
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Create your account", style = MaterialTheme.typography.headlineMedium)
        Text("Use a passkey stored in your password manager. Email confirmation is the last step.")
        if (state.busy || nativeBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.canRetry) Button(onClick = viewModel::retry, enabled = !state.busy) { Text("Retry same request") }
        if (session != null && session.status != SessionStatus.COMPLETE && !nativeBusy) {
            TextButton(onClick = viewModel::checkStatus, enabled = !state.busy) { Text("Check session status") }
        }
        when {
            session == null -> StartForm(enabled, viewModel::start)
            editingProfile || session.status == SessionStatus.PROFILE -> ProfileForm(session, enabled) { first, last, email ->
                viewModel.saveProfile(first, last, email)
                editingProfile = false
            }
            session.status == SessionStatus.CREDENTIALS -> CredentialForm(state, viewModel, enabled, { nativeBusy = it })
            session.status == SessionStatus.REVIEW -> ReviewForm(session, enabled, onOpenTerms, viewModel::acceptReview)
            session.status == SessionStatus.EMAIL -> EmailForm(session, enabled, viewModel::sendEmail, viewModel::confirmEmail)
            session.status == SessionStatus.ACTIVATING -> Text("We are activating your account. Keep this screen open; you can check status again if it takes longer.")
            session.status == SessionStatus.COMPLETE -> {
                Text("Your email is confirmed and your account is ready. Sign in to continue.")
                Text("Your legal identity has not been verified.", style = MaterialTheme.typography.bodySmall)
                val publicId = session.userPublicId
                if (publicId != null) Button(onClick = { onAuthenticationRequired(publicId) }) { Text("Continue to sign in") }
            }
            session.status == SessionStatus.FAILED -> Text("This enrollment could not be completed. If you already have an account, sign in instead.")
        }
        if (session?.status in setOf(SessionStatus.CREDENTIALS, SessionStatus.REVIEW, SessionStatus.EMAIL) && !editingProfile) {
            TextButton(onClick = { editingProfile = true }, enabled = enabled) { Text("Edit name or email") }
        }
        if (session?.status != SessionStatus.COMPLETE && (session != null || state.canRetry || state.restartRequired)) {
            TextButton(onClick = { confirmRestart = true }, enabled = !nativeBusy) { Text("Start again") }
        }
    }
    if (confirmRestart) AlertDialog(
        onDismissRequest = { confirmRestart = false },
        title = { Text("Start a new enrollment?") },
        text = { Text("This clears your progress on this device. An unfinished passkey may remain in your password manager; you can remove that unused entry there.") },
        confirmButton = { TextButton(onClick = { confirmRestart = false; viewModel.restart() }) { Text("Start again") } },
        dismissButton = { TextButton(onClick = { confirmRestart = false }) { Text("Keep going") } },
    )
}

@Composable
private fun StartForm(enabled: Boolean, onStart: (String) -> Unit) {
    var email by remember { mutableStateOf("") }
    OutlinedTextField(email, { email = it }, label = { Text("Email address") }, singleLine = true, enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.EmailAddress })
    Button(onClick = { onStart(email) }, enabled = enabled && email.isNotBlank()) { Text("Continue") }
}

@Composable
private fun ProfileForm(session: OnboardingSession, enabled: Boolean, onSave: (String, String, String) -> Unit) {
    var first by remember(session.version) { mutableStateOf(session.profile.givenName) }
    var last by remember(session.version) { mutableStateOf(session.profile.familyName) }
    var email by remember(session.version) { mutableStateOf(session.email) }
    Text("Your profile", style = MaterialTheme.typography.titleLarge)
    OutlinedTextField(first, { first = it }, label = { Text("Given name") }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(last, { last = it }, label = { Text("Family name") }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(email, { email = it }, label = { Text("Email address") }, enabled = enabled, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.EmailAddress })
    Text("Changing these details requires another review and invalidates any previous email code.")
    Button(onClick = { onSave(first, last, email) }, enabled = enabled && first.isNotBlank() && last.isNotBlank() && email.isNotBlank()) { Text("Save and continue") }
}

@Composable
private fun CredentialForm(state: OnboardingUiState, vm: OnboardingViewModel, enabled: Boolean, setNativeBusy: (Boolean) -> Unit) {
    val activity = LocalContext.current.activity()
    val credentials = remember { AndroidCredentials() }
    val scope = rememberCoroutineScope()
    val setup = state.setup
    var password by remember { mutableStateOf("") }
    var saveToManager by remember { mutableStateOf(true) }
    var pendingPassword by remember { mutableStateOf<String?>(null) }
    var totp by remember { mutableStateOf("") }

    // The password is not saved until the server has accepted it. This is optional and stays local.
    LaunchedEffect(setup?.status) {
        if (setup?.status == "totp-pending") {
            val toSave = pendingPassword
            pendingPassword = null
            if (toSave != null && activity != null) {
                setNativeBusy(true)
                try { credentials.savePassword(activity, state.session!!.email, toSave) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { vm.credentialProviderMessage("Password-manager save was skipped. Keep your password safe and continue with your authenticator.") }
                finally { setNativeBusy(false) }
            }
        }
    }
    Text("Secure your account", style = MaterialTheme.typography.titleLarge)
    if (setup == null) {
        Button(onClick = { vm.chooseMethod(EnrollmentMethod.PASSKEY) }, enabled = enabled && credentials.supportsPasskeys && activity != null) { Text("Use a passkey (recommended)") }
        if (!credentials.supportsPasskeys) Text("Passkeys require Android 9 or later. Use a password and authenticator on this device.")
        OutlinedButton(onClick = { vm.chooseMethod(EnrollmentMethod.PASSWORD_MFA) }, enabled = enabled) { Text("Use password + authenticator") }
        Text("Once setup begins, changing the method requires starting a new enrollment.", style = MaterialTheme.typography.bodySmall)
    } else when (setup.status) {
        "passkey-pending" -> {
            Text("Your device will ask you to choose a password manager and confirm with your fingerprint, face, or screen lock.")
            Button(onClick = {
                val host = activity ?: return@Button
                val options = setup.creationOptions ?: return@Button
                scope.launch {
                    setNativeBusy(true)
                    try { vm.submitPasskey(credentials.createPasskey(host, options)) }
                    catch (_: CreateCredentialCancellationException) { vm.credentialProviderMessage("Passkey creation was cancelled. You can try again.") }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { vm.credentialProviderMessage("Passkey creation failed. Check your password manager and the app's domain association, then try again or start over.") }
                    finally { setNativeBusy(false) }
                }
            }, enabled = enabled && setup.creationOptions != null && activity != null) { Text("Create passkey") }
        }
        "created" -> if (setup.method == EnrollmentMethod.PASSWORD_MFA) {
            Text("Choose a unique password of at least 12 characters. Use your password manager to generate and save it. An authenticator code will also be required.")
            OutlinedTextField(password, { password = it }, label = { Text("New password") }, enabled = enabled, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.NewPassword })
            Row { Checkbox(saveToManager, { saveToManager = it }, enabled = enabled); Text("Offer to save in my password manager") }
            Button(onClick = {
                pendingPassword = if (saveToManager) password else null
                vm.setPassword(password)
                password = ""
            }, enabled = enabled && password.toByteArray(Charsets.UTF_8).size in 12..256) { Text("Set password") }
        } else Button(onClick = vm::continueCredentials, enabled = enabled) { Text("Continue passkey setup") }
        "totp-pending" -> {
            Text("In your authenticator or password manager, add a time-based code manually using this setup key (six digits, 30-second period). Keep the key private.")
            setup.secret?.let { SelectionContainer { Text(it.value, style = MaterialTheme.typography.bodyLarge) } }
            CodeField(totp, { totp = it }, 6, "Authenticator code", enabled)
            Button(onClick = { vm.confirmTotp(totp); totp = "" }, enabled = enabled && totp.length == 6) { Text("Verify authenticator") }
        }
        "password-set", "ready", "complete" -> Button(onClick = vm::continueCredentials, enabled = enabled) { Text("Continue") }
        else -> Text("Credential setup needs attention. Check status or start again.")
    }
}

@Composable
private fun ReviewForm(session: OnboardingSession, enabled: Boolean, onOpenTerms: () -> Unit, onAccept: () -> Unit) {
    var accepted by remember(session.version, session.consentVersion) { mutableStateOf(false) }
    Text("Review your details", style = MaterialTheme.typography.titleLarge)
    Text("${session.profile.givenName} ${session.profile.familyName}\n${session.email}")
    TextButton(onClick = onOpenTerms) { Text("Read terms (${session.consentVersion})") }
    Row { Checkbox(accepted, { accepted = it }, enabled = enabled); Text("I accept these terms and confirm my details are correct.") }
    Button(onClick = onAccept, enabled = enabled && accepted) { Text("Accept and continue") }
}

@Composable
private fun EmailForm(session: OnboardingSession, enabled: Boolean, onSend: () -> Unit, onConfirm: (String) -> Unit) {
    var code by remember(session.confirmationId) { mutableStateOf("") }
    Text("Confirm your email", style = MaterialTheme.typography.titleLarge)
    Text("This is the final step. Your confirmation email goes to ${session.email}.")
    if (session.confirmationId == null) {
        Button(onClick = onSend, enabled = enabled) { Text("Send confirmation email") }
    } else {
        Text("Enter the eight-digit code in this app. The code expires after ten minutes or when this session expires.")
        CodeField(code, { code = it }, 8, "Email code", enabled)
        Button(onClick = { onConfirm(code); code = "" }, enabled = enabled && code.length == 8) { Text("Confirm email") }
        TextButton(onClick = onSend, enabled = enabled) { Text("Send a new code") }
        Text("Wait at least one minute between sends. A new code invalidates the previous one.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CodeField(value: String, onChange: (String) -> Unit, digits: Int, label: String, enabled: Boolean) {
    OutlinedTextField(value, { onChange(it.filter { c -> c in '0'..'9' }.take(digits)) }, label = { Text(label) },
        enabled = enabled, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
