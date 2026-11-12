package dev.cipherkeep.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.fragment.app.FragmentActivity
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import android.provider.Settings
import dev.cipherkeep.app.data.SensitiveClipboard
import dev.cipherkeep.core.FfiPassphrasePolicy
import dev.cipherkeep.core.FfiPasswordPolicy
import dev.cipherkeep.core.FfiSearchDocument
import dev.cipherkeep.core.FfiSearchIndex
import dev.cipherkeep.core.generatePassphrase
import dev.cipherkeep.core.generatePassword
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import dev.cipherkeep.core.totpCode
import dev.cipherkeep.app.update.UpdateChecker
import dev.cipherkeep.app.update.UpdateOutcome
import dev.cipherkeep.app.update.UpdatePreferences
import dev.cipherkeep.app.update.UpdateScheduler
import dev.cipherkeep.app.data.keystore.BiometricKeyManager
import dev.cipherkeep.app.data.keystore.WrappedSecret
import dev.cipherkeep.app.data.SessionController
import dev.cipherkeep.app.data.VaultItem
import dev.cipherkeep.core.FfiException
import dev.cipherkeep.core.numericCode
import dev.cipherkeep.app.data.VaultUiState
import dev.cipherkeep.app.ui.theme.CKColor
import dev.cipherkeep.app.ui.theme.CKDimens
import dev.cipherkeep.app.ui.theme.CipherKeepTheme
import dev.cipherkeep.app.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


@Composable
fun CipherKeepApp() {
    var themeName by rememberSaveable { mutableStateOf(ThemeMode.System.name) }
    var screen by rememberSaveable { mutableStateOf("vault") }
    var selectedIndex by rememberSaveable { mutableIntStateOf(-1) }
    val context = LocalContext.current
    val controller = remember {
        SessionController(context).also { dev.cipherkeep.app.autofill.AutofillRegistry.controller = it }
    }
    val reduceMotion = Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) == 0f
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(controller, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    SecretInputRegistry.clearAll()
                    controller.onBackground()
                }
                Lifecycle.Event.ON_START -> controller.onForeground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            dev.cipherkeep.app.autofill.AutofillRegistry.controller = null
            controller.close()
        }
    }
    LaunchedEffect(controller) { controller.bootstrap() }

    CipherKeepTheme(mode = ThemeMode.valueOf(themeName)) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(controller) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent()
                            controller.recordActivity()
                        }
                    }
                },
            color = MaterialTheme.colorScheme.background,
        ) {
            when (val state = controller.state) {
                VaultUiState.Loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (reduceMotion) {
                        Text("Loading vault…")
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.semantics { contentDescription = "Loading vault" },
                        )
                    }
                }
                VaultUiState.Setup -> SetupScreen(controller)
                is VaultUiState.Recovery -> RecoveryScreen(controller, state)
                is VaultUiState.Locked -> UnlockScreen(controller, state)
                VaultUiState.LockWarning -> LockWarningScreen(controller)
                is VaultUiState.StorageError -> StorageErrorScreen(controller, state.message)
                VaultUiState.Unlocked -> {
                    BackHandler(enabled = screen != "vault") {
                        screen = "vault"
                        selectedIndex = -1
                    }
                    when (screen) {
                        "vault" -> if (selectedIndex in controller.items.indices) {
                            ItemDetailScreen(
                                controller = controller,
                                item = controller.items[selectedIndex],
                                onBack = { selectedIndex = -1 },
                                onDeleted = { selectedIndex = -1 },
                            )
                        } else {
                            VaultScreen(
                                controller = controller,
                                items = controller.items,
                                onOpen = { index -> selectedIndex = index },
                                onAdd = { screen = "add" },
                                onSettings = { screen = "settings" },
                                onLock = {
                                    selectedIndex = -1
                                    SecretInputRegistry.clearAll()
                                    controller.lock()
                                },
                            )
                        }
                        "add" -> AddItemScreen(
                            controller = controller,
                            onBack = { screen = "vault" },
                        )
                        "settings" -> SettingsScreen(
                            controller = controller,
                            themeMode = ThemeMode.valueOf(themeName),
                            onThemeMode = { themeName = it.name },
                            onBack = { screen = "vault" },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageErrorScreen(controller: SessionController, message: String) {
    AlertDialog(
        onDismissRequest = { controller.lock() },
        title = { Text("Vault storage unavailable") },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = { controller.lock() }) { Text("Lock vault") }
        },
    )
}

@Composable
private fun LockWarningScreen(controller: SessionController) {
    AlertDialog(
        onDismissRequest = { controller.lock() },
        title = { Text("Session idle") },
        text = {
            Text(
                "CipherKeep was in the background for a minute. Lock the vault now, " +
                    "or continue without re-authenticating.",
            )
        },
        confirmButton = {
            TextButton(onClick = {
                SecretInputRegistry.clearAll()
                controller.lock()
            }) { Text("Lock now") }
        },
        dismissButton = {
            TextButton(onClick = { controller.stayUnlocked() }) { Text("Stay unlocked") }
        },
    )
}

@Composable
private fun SetupScreen(controller: SessionController) {
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    DisposableEffect(Unit) {
        val reset = { password = ""; confirmation = "" }
        SecretInputRegistry.register(reset)
        onDispose { SecretInputRegistry.unregister(reset) }
    }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.primaryContainer,
                    ),
                ),
            )
            .padding(CKDimens.Space4),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BrandMark()
            Spacer(Modifier.height(CKDimens.Space4))
            Text(
                text = "Create your vault",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = "The master password is never stored. Argon2id runs on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = CKDimens.Space2),
            )
            Spacer(Modifier.height(CKDimens.Space4))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Column(modifier = Modifier.padding(CKDimens.Space4)) {
                    Text("First steps", style = MaterialTheme.typography.titleMedium)
                    Text("1. Create the vault and save the three recovery shares offline.", style = MaterialTheme.typography.bodySmall)
                    Text("2. Add logins, TOTP entries and notes.", style = MaterialTheme.typography.bodySmall)
                    Text("3. Open an item and enable autofill for its exact origin when ready.", style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(CKDimens.Space4))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Column(modifier = Modifier.padding(CKDimens.Space4)) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Master password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        shape = MaterialTheme.shapes.small,
                    )
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = { confirmation = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = CKDimens.Space3),
                        label = { Text("Confirm master password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        shape = MaterialTheme.shapes.small,
                    )
                    Text(
                        text = "${password.length} characters · 12 minimum",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (password.length >= 12) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(top = CKDimens.Space2),
                    )
                    if (error != null) {
                        Text(
                            text = error.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = CKDimens.Space2),
                        )
                    }
                    Spacer(Modifier.height(CKDimens.Space4))
                    Button(
                        onClick = {
                            if (password != confirmation) {
                                error = "Passwords do not match."
                                return@Button
                            }
                            busy = true
                            error = null
                            scope.launch {
                                val result = controller.create(password)
                                password = ""
                                confirmation = ""
                                busy = false
                                result.exceptionOrNull()?.let {
                                    error = if ((it as? FfiException)?.numericCode() == 11) {
                                        "Use at least 12 characters and avoid common passwords."
                                    } else {
                                        "Vault could not be created."
                                    }
                                }
                            }
                        },
                        enabled = !busy && password.length >= 12 && password == confirmation,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(if (busy) "Deriving key…" else "Create vault")
                    }
                }
            }
            Spacer(Modifier.height(CKDimens.Space4))
            Text(
                text = "Next: three recovery shares, shown one at a time.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RecoveryScreen(controller: SessionController, state: VaultUiState.Recovery) {
    var saved by remember(state.index) { mutableStateOf(false) }
    val share = state.shares.getOrNull(state.index).orEmpty()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(CKDimens.Space4),
        contentAlignment = Alignment.Center,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            BrandMark()
            Spacer(Modifier.height(CKDimens.Space4))
            Text(
                text = if (state.afterRecovery) "New recovery shares" else "Recovery shares",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = "Share ${state.index + 1} of ${state.shares.size}. " +
                    "Store it offline and never keep all shares together.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = CKDimens.Space2),
            )
            Spacer(Modifier.height(CKDimens.Space4))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Column(modifier = Modifier.padding(CKDimens.Space4)) {
                    Text(
                        text = "CIPHERKEEP RECOVERY SHARE ${state.index + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = share,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        modifier = Modifier.padding(top = CKDimens.Space2),
                    )
                }
            }
            Spacer(Modifier.height(CKDimens.Space3))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = saved, onCheckedChange = { saved = it })
                Text(
                    text = "I have saved this share",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(CKDimens.Space3))
            Button(
                onClick = { controller.nextShare() },
                enabled = saved,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(if (state.index + 1 >= state.shares.size) "Finish" else "Next share")
            }
            Spacer(Modifier.height(CKDimens.Space3))
            Text(
                text = "Any two shares restore access. A single share reveals nothing.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UnlockScreen(controller: SessionController, state: VaultUiState.Locked) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showRecovery by remember { mutableStateOf(false) }
    var firstShare by remember { mutableStateOf("") }
    var secondShare by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    DisposableEffect(Unit) {
        val reset = { password = ""; firstShare = ""; secondShare = ""; newPassword = "" }
        SecretInputRegistry.register(reset)
        onDispose { SecretInputRegistry.unregister(reset) }
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val biometricKey = remember { BiometricKeyManager() }
    var biometricWrapped by remember { mutableStateOf<WrappedSecret?>(null) }
    LaunchedEffect(controller) {
        biometricWrapped = controller.biometricWrapped()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.primaryContainer,
                    ),
                ),
            )
            .padding(CKDimens.Space4),
        contentAlignment = Alignment.Center,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            BrandMark()
            Spacer(Modifier.height(CKDimens.Space4))
            Text(
                text = "CipherKeep",
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = "Unlock your local vault.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(CKDimens.Space4))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Column(modifier = Modifier.padding(CKDimens.Space4)) {
                    if (!showRecovery) {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Master password") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            shape = MaterialTheme.shapes.small,
                        )
                        val message = error ?: state.error
                        if (message != null) {
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = CKDimens.Space2),
                            )
                        }
                        if (state.retryAfterSeconds > 0) {
                            Text(
                                text = "Local delay: ${state.retryAfterSeconds}s",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = CKDimens.Space2),
                            )
                        }
                        Spacer(Modifier.height(CKDimens.Space4))
                        Button(
                            onClick = {
                                busy = true
                                error = null
                                scope.launch {
                                    val result = controller.unlock(password)
                                    password = ""
                                    busy = false
                                    result.exceptionOrNull()?.let {
                                        error = "Vault could not be opened. Check your master password."
                                    }
                                }
                            },
                            enabled = !busy && password.isNotEmpty() && state.retryAfterSeconds == 0L,
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Text(if (busy) "Unlocking…" else "Unlock")
                        }
                        if (biometricWrapped != null && activity != null && canUseBiometrics(activity)) {
                            OutlinedButton(
                                onClick = {
                                    busy = true
                                    error = null
                                    val wrapped = biometricWrapped ?: return@OutlinedButton
                                    try {
                                        val cipher = biometricKey.decryptCipher(wrapped.iv)
                                        promptBiometric(
                                            activity = activity,
                                            cipher = cipher,
                                            title = "Unlock CipherKeep",
                                            subtitle = "Authenticate to unwrap the vault master key.",
                                            onSuccess = { authenticated ->
                                                scope.launch {
                                                    try {
                                                        val master = authenticated.doFinal(wrapped.ciphertext)
                                                        val result = controller.unlockWithMasterKey(master)
                                                        master.fill(0)
                                                        busy = false
                                                        if (result.isFailure) {
                                                            error = "Biometric unlock failed. Use your master password."
                                                        }
                                                    } catch (_: Exception) {
                                                        busy = false
                                                        error = "Biometric unlock failed. Use your master password."
                                                    }
                                                }
                                            },
                                            onFailure = { reason ->
                                                busy = false
                                                error = "Biometric authentication failed: $reason"
                                            },
                                        )
                                    } catch (_: Exception) {
                                        busy = false
                                        error = "Biometric key is unavailable. Use your master password."
                                    }
                                },
                                enabled = !busy,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = CKDimens.Space2),
                                shape = MaterialTheme.shapes.medium,
                            ) {
                                Text("Unlock with biometrics")
                            }
                        }
                        TextButton(
                            onClick = { showRecovery = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Forgot password? Use recovery shares")
                        }
                    } else {
                        Text(
                            text = "Recover with two shares",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "Enter any two of your three shares and choose a new master password.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = CKDimens.Space2),
                        )
                        OutlinedTextField(
                            value = firstShare,
                            onValueChange = { firstShare = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = CKDimens.Space3),
                            label = { Text("Recovery share 1") },
                            minLines = 2,
                            shape = MaterialTheme.shapes.small,
                        )
                        OutlinedTextField(
                            value = secondShare,
                            onValueChange = { secondShare = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = CKDimens.Space3),
                            label = { Text("Recovery share 2") },
                            minLines = 2,
                            shape = MaterialTheme.shapes.small,
                        )
                        OutlinedTextField(
                            value = newPassword,
                            onValueChange = { newPassword = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = CKDimens.Space3),
                            label = { Text("New master password") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            shape = MaterialTheme.shapes.small,
                        )
                        if (error != null) {
                            Text(
                                text = error.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = CKDimens.Space2),
                            )
                        }
                        Spacer(Modifier.height(CKDimens.Space4))
                        Button(
                            onClick = {
                                busy = true
                                error = null
                                scope.launch {
                                    val result = controller.recover(
                                        firstShare,
                                        secondShare,
                                        newPassword,
                                    )
                                    busy = false
                                    result.exceptionOrNull()?.let {
                                        error = "Recovery failed. Check both shares and the new password."
                                    }
                                }
                            },
                            enabled = !busy &&
                                firstShare.isNotBlank() &&
                                secondShare.isNotBlank() &&
                                newPassword.length >= 12,
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Text(if (busy) "Recovering…" else "Recover vault")
                        }
                        TextButton(
                            onClick = {
                                showRecovery = false
                                error = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Back to master password")
                        }
                    }
                }
            }
            Spacer(Modifier.height(CKDimens.Space3))
            Text(
                text = "No account or server is involved. A lost password with fewer than two shares cannot be recovered.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddItemScreen(controller: SessionController, onBack: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isTotp by remember { mutableStateOf(false) }
    var totpUri by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    var favorite by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val reset = {
            password = ""
            totpUri = ""
            url = ""
            notes = ""
            folder = ""
            tags = ""
            favorite = false
        }
        SecretInputRegistry.register(reset)
        onDispose { SecretInputRegistry.unregister(reset) }
    }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showGenerator by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("New item") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(CKDimens.Space4)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(CKDimens.Space3),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(CKDimens.Space2)) {
                TextButton(onClick = { isTotp = false }) { Text("Login") }
                TextButton(onClick = { isTotp = true }) { Text("TOTP") }
            }
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Title") },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (isTotp) "Account" else "Username") },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
            )
            if (!isTotp) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("URL (optional)") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Notes (optional)") },
                    minLines = 2,
                    maxLines = 4,
                    shape = MaterialTheme.shapes.small,
                )
                OutlinedTextField(
                    value = folder,
                    onValueChange = { folder = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Folder (optional)") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                )
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Tags (comma separated)") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { favorite = !favorite },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = favorite, onCheckedChange = { favorite = it })
                    Text("Favorite")
                }
            }
            if (isTotp) {
                OutlinedTextField(
                    value = totpUri,
                    onValueChange = { totpUri = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("TOTP otpauth URI") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    shape = MaterialTheme.shapes.small,
                )
            } else {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    shape = MaterialTheme.shapes.small,
                )
                TextButton(onClick = { showGenerator = true }) {
                    Text("Generate password")
                }
            }
            if (error != null) {
                Text(
                    text = error.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        val result = if (isTotp) {
                            controller.saveTotpItem(title.trim(), username.trim(), totpUri.trim())
                        } else {
                            controller.saveItem(
                                title.trim(),
                                username.trim(),
                                password,
                                url.trim(),
                                notes,
                                folder.trim(),
                                tags,
                                favorite,
                            )
                        }
                        busy = false
                        password = ""
                        totpUri = ""
                        url = ""
                        notes = ""
                        folder = ""
                        tags = ""
                        favorite = false
                        if (result.isSuccess) {
                            onBack()
                        } else {
                            error = "Item could not be saved."
                        }
                    }
                },
                enabled = !busy && title.isNotBlank() &&
                    (if (isTotp) totpUri.trim().startsWith("otpauth://totp/") else password.isNotEmpty()),
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(if (busy) "Saving…" else "Save item")
            }
        }
    }
    if (showGenerator) {
        GeneratorDialog(
            onDismiss = { showGenerator = false },
            onUse = {
                password = it
                showGenerator = false
            },
        )
    }
}

@Composable
private fun GeneratorDialog(onDismiss: () -> Unit, onUse: (String) -> Unit) {
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var lengthText by rememberSaveable { mutableStateOf("20") }
    var lowercase by rememberSaveable { mutableStateOf(true) }
    var uppercase by rememberSaveable { mutableStateOf(true) }
    var digits by rememberSaveable { mutableStateOf(true) }
    var symbols by rememberSaveable { mutableStateOf(true) }
    var excludeAmbiguous by rememberSaveable { mutableStateOf(false) }
    var wordsText by rememberSaveable { mutableStateOf("6") }
    var separator by rememberSaveable { mutableStateOf("-") }
    var generated by remember { mutableStateOf<dev.cipherkeep.core.FfiGeneratedSecret?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = remember { SensitiveClipboard(context, scope) }

    DisposableEffect(Unit) {
        val clear = { generated = null }
        SecretInputRegistry.register(clear)
        onDispose { SecretInputRegistry.unregister(clear) }
    }

    AlertDialog(
        onDismissRequest = {
            generated = null
            onDismiss()
        },
        title = { Text("Generate") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(CKDimens.Space2)) {
                Row(horizontalArrangement = Arrangement.spacedBy(CKDimens.Space2)) {
                    TextButton(onClick = { mode = 0; generated = null }) { Text("Password") }
                    TextButton(onClick = { mode = 1; generated = null }) { Text("Passphrase") }
                }
                if (mode == 0) {
                    OutlinedTextField(
                        value = lengthText,
                        onValueChange = { lengthText = it.filter(Char::isDigit).take(3) },
                        label = { Text("Length (8-128)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    GeneratorCheck("Lowercase", lowercase) { lowercase = it }
                    GeneratorCheck("Uppercase", uppercase) { uppercase = it }
                    GeneratorCheck("Digits", digits) { digits = it }
                    GeneratorCheck("Symbols", symbols) { symbols = it }
                    GeneratorCheck("Exclude ambiguous", excludeAmbiguous) { excludeAmbiguous = it }
                } else {
                    OutlinedTextField(
                        value = wordsText,
                        onValueChange = { wordsText = it.filter(Char::isDigit).take(2) },
                        label = { Text("Words (3-12)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        value = separator,
                        onValueChange = { if (it.length <= 1) separator = it },
                        label = { Text("Separator") },
                        singleLine = true,
                    )
                }
                if (generated != null) {
                    Text(
                        text = generated?.value.orEmpty(),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "${generated?.entropyBits?.toInt()} bits · ${generated?.strength}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(CKDimens.Space2)) {
                        OutlinedButton(onClick = {
                            generated?.let {
                                clipboard.copy("CipherKeep generated secret", it.value)
                            }
                        }) { Text("Copy") }
                        Button(onClick = {
                            generated?.let {
                                onUse(it.value)
                                generated = null
                            }
                        }) { Text("Use") }
                    }
                }
                if (error != null) {
                    Text(
                        text = error.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                error = null
                generated = try {
                    if (mode == 0) {
                        generatePassword(
                            FfiPasswordPolicy(
                                length = (lengthText.toIntOrNull() ?: 20).toUInt(),
                                lowercase = lowercase,
                                uppercase = uppercase,
                                digits = digits,
                                symbols = symbols,
                                excludeAmbiguous = excludeAmbiguous,
                            ),
                        )
                    } else {
                        generatePassphrase(
                            FfiPassphrasePolicy(
                                words = (wordsText.toIntOrNull() ?: 6).toUInt(),
                                separator = separator,
                            ),
                        )
                    }
                } catch (_: Exception) {
                    error = "Could not generate a value for this policy."
                    null
                }
            }) { Text("Generate") }
        },
        dismissButton = {
            TextButton(onClick = {
                generated = null
                onDismiss()
            }) { Text("Close") }
        },
    )
}

@Composable
private fun GeneratorCheck(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}

@Composable
private fun BrandMark() {
    Box(
        modifier = Modifier
            .size(64.dp)
            .background(
                brush = Brush.linearGradient(
                    listOf(CKColor.LightAccent, CKColor.DarkPrimary),
                ),
                shape = RoundedCornerShape(CKDimens.RadiusLg),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "CK",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = CKColor.DarkOnPrimary,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultScreen(
    controller: SessionController,
    items: List<VaultItem>,
    onOpen: (Int) -> Unit,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
    onLock: () -> Unit,
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var filterText by rememberSaveable { mutableStateOf("") }
    var showFolders by remember { mutableStateOf(false) }
    var sortMode by rememberSaveable { mutableIntStateOf(0) }
    val searchIndex = remember(items) {
        FfiSearchIndex().also { index ->
            items.forEach { item ->
                index.insert(
                    FfiSearchDocument(
                        id = item.id,
                        title = item.title,
                        username = item.username,
                        url = item.url,
                        folder = listOf(item.folder, item.tags.joinToString(" "))
                            .filter(String::isNotBlank)
                            .joinToString(" "),
                        notes = item.notes,
                    ),
                )
            }
        }
    }
    DisposableEffect(searchIndex) {
        onDispose { searchIndex.close() }
    }
    val visibleItems = remember(searchQuery, filterText, favoritesOnly, items, searchIndex) {
        val searched = if (searchQuery.isBlank()) {
            items
        } else {
            val ids = searchIndex.search(searchQuery, 50u).map { it.id }
            items.filter { item -> ids.any { it.contentEquals(item.id) } }
        }
        val normalized = filterText.trim().lowercase()
        searched.filter { item ->
            (!favoritesOnly || item.favorite) &&
                (normalized.isEmpty() ||
                    item.folder.lowercase().contains(normalized) ||
                    item.tags.any { it.lowercase().contains(normalized) })
        }.sortedWith(
            when (sortMode) {
                1 -> compareByDescending<VaultItem> { it.updatedAt }.thenBy { it.title }
                2 -> compareBy<VaultItem> { it.folder }.thenBy { it.title }
                else -> compareBy<VaultItem> { it.title }
            },
        )
    }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Vault") },
                actions = {
                    TextButton(onClick = onAdd) { Text("Add") }
                    TextButton(onClick = onLock) { Text("Lock") }
                    TextButton(onClick = onSettings) { Text("Settings") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(CKDimens.Space4),
            verticalArrangement = Arrangement.spacedBy(CKDimens.Space3),
        ) {
            item {
                Text(
                    text = "Vault",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = "Items are encrypted with the session key and stored in the local encrypted database.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = CKDimens.Space1),
                )
                Spacer(Modifier.height(CKDimens.Space2))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Search title or username") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(CKDimens.Space2)) {
                    TextButton(onClick = { favoritesOnly = false }) { Text("All") }
                    TextButton(onClick = { favoritesOnly = true }) { Text("Favorites") }
                    TextButton(onClick = { showFolders = true }) { Text("Folders") }
                    TextButton(onClick = { sortMode = (sortMode + 1) % 3 }) {
                        Text(
                            when (sortMode) {
                                1 -> "Recent"
                                2 -> "Folder"
                                else -> "Title"
                            },
                        )
                    }
                }
                OutlinedTextField(
                    value = filterText,
                    onValueChange = { filterText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Filter folder or tag") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                )
                Spacer(Modifier.height(CKDimens.Space2))
                if (visibleItems.isEmpty()) {
                    Text(
                        text = if (items.isEmpty()) "No items yet. Use Add to create the first one." else "No items match these filters.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(visibleItems) { item ->
                val index = items.indexOf(item)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = "Open ${item.title}") { onOpen(index) },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(CKDimens.Space4),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = if (item.totpUri.isNotEmpty()) "TOTP · ${item.username}" else item.username,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = "Open",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            item {
                Spacer(Modifier.height(CKDimens.Space2))
                Text(
                    text = "Autofill is opt-in per item. Open Edit and allow the exact origin.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (showFolders) {
        FolderDialog(controller = controller, onDismiss = { showFolders = false })
    }
}

@Composable
private fun FolderDialog(controller: SessionController, onDismiss: () -> Unit) {
    var names by remember { mutableStateOf<List<String>>(emptyList()) }
    var newName by remember { mutableStateOf("") }
    var renameFrom by remember { mutableStateOf<String?>(null) }
    var renameTo by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun reload() {
        scope.launch {
            controller.folderNames()
                .onSuccess { names = it }
                .onFailure { error = "Folders could not be loaded." }
        }
    }
    LaunchedEffect(Unit) { reload() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Folders") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(CKDimens.Space2),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("New folder") },
                        singleLine = true,
                    )
                    TextButton(onClick = {
                        error = null
                        scope.launch {
                            controller.createFolder(newName)
                                .onSuccess {
                                    newName = ""
                                    reload()
                                }
                                .onFailure { error = "Folder could not be created." }
                        }
                    }) { Text("Add") }
                }
                if (names.isEmpty()) {
                    Text(
                        text = "No folders yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                names.forEach { name ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(name, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            renameFrom = name
                            renameTo = name
                        }) { Text("Rename") }
                        TextButton(onClick = {
                            error = null
                            scope.launch {
                                controller.deleteFolder(name)
                                    .onSuccess { reload() }
                                    .onFailure { error = "Folder could not be deleted." }
                            }
                        }) { Text("Delete") }
                    }
                }
                renameFrom?.let { oldName ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = renameTo,
                            onValueChange = { renameTo = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("Rename folder") },
                            singleLine = true,
                        )
                        TextButton(onClick = {
                            error = null
                            scope.launch {
                                controller.renameFolder(oldName, renameTo)
                                    .onSuccess {
                                        renameFrom = null
                                        reload()
                                    }
                                    .onFailure { error = "Folder could not be renamed." }
                            }
                        }) { Text("Save") }
                    }
                }
                if (error != null) {
                    Text(
                        text = error.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemDetailScreen(
    controller: SessionController,
    item: VaultItem,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
) {
    var revealed by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    DisposableEffect(item.id) {
        val reset = { revealed = false }
        SecretInputRegistry.register(reset)
        onDispose { SecretInputRegistry.unregister(reset) }
    }
    var clipboardTick by remember { mutableIntStateOf(0) }
    var totpValue by remember(item.id) { mutableStateOf("") }
    var totpRemaining by remember(item.id) { mutableIntStateOf(0) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = remember { SensitiveClipboard(context, scope) }
    LaunchedEffect(item.totpUri) {
        if (item.totpUri.isNotEmpty()) {
            while (true) {
                runCatching {
                    totpCode(item.totpUri, (System.currentTimeMillis() / 1000).toULong())
                }.onSuccess {
                    totpValue = it.code
                    totpRemaining = it.secondsRemaining.toInt()
                }
                delay(1_000)
            }
        }
    }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(item.title) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                },
                actions = {
                    TextButton(onClick = { showEdit = true }) { Text("Edit") }
                    TextButton(onClick = { showDelete = true }) { Text("Delete") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(CKDimens.Space4)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = if (item.totpUri.isNotEmpty()) "TOTP" else "Login",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = item.title,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier
                    .padding(top = CKDimens.Space2)
                    .semantics { heading() },
            )
            Spacer(Modifier.height(CKDimens.Space4))
            if (item.totpUri.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                ) {
                    Column(modifier = Modifier.padding(CKDimens.Space4)) {
                        DetailRow("Account", item.username)
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = CKDimens.Space3),
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Text(
                            text = totpValue.ifEmpty { "------" },
                            style = MaterialTheme.typography.headlineMedium,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.semantics {
                                contentDescription =
                                    "One-time code $totpValue, $totpRemaining seconds remaining"
                            },
                        )
                        Text(
                            text = "Refreshes in ${totpRemaining}s",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = {
                                if (totpValue.isNotEmpty()) {
                                    clipboard.copy("CipherKeep TOTP", totpValue) {
                                        clipboardTick = it
                                    }
                                }
                            }) { Text("Copy code") }
                        }
                        if (clipboardTick > 0) {
                            Text(
                                text = "Clipboard clears in ${clipboardTick}s",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Column(modifier = Modifier.padding(CKDimens.Space4)) {
                    DetailRow("Username", item.username)
                    if (item.url.isNotBlank()) {
                        DetailRow("URL", item.url)
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = CKDimens.Space3),
                        color = MaterialTheme.colorScheme.outline,
                    )
                    DetailRow(
                        label = "Password",
                        value = if (revealed) item.password else if (item.password.isEmpty()) "" else "\u2022".repeat(item.password.length.coerceAtMost(32)),
                    )
                    if (item.notes.isNotBlank()) {
                        DetailRow("Notes", item.notes)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { revealed = !revealed }) {
                            Text(if (revealed) "Hide" else "Reveal")
                        }
                        if (revealed) {
                            TextButton(
                                onClick = {
                                    clipboard.copy("CipherKeep password", item.password) {
                                        clipboardTick = it
                                    }
                                },
                            ) {
                                Text("Copy")
                            }
                        }
                    }
                    if (clipboardTick > 0) {
                        Text(
                            text = "Clipboard clears in ${clipboardTick}s",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            }
            Spacer(Modifier.height(CKDimens.Space4))
            Text(
                text = "Clipboard clears after 30 seconds. Codes are generated locally.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (actionError != null) {
                Text(actionError.orEmpty(), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (showEdit) {
        EditItemDialog(controller, item, onDismiss = { showEdit = false })
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete item?") },
            text = { Text("This removes the encrypted item record. It cannot be recovered unless you have a backup.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        controller.deleteItem(item)
                            .onSuccess { onDeleted() }
                            .onFailure { actionError = "Item could not be deleted." }
                    }
                    showDelete = false
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EditItemDialog(controller: SessionController, item: VaultItem, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf(item.title) }
    var username by remember { mutableStateOf(item.username) }
    var password by remember { mutableStateOf(item.password) }
    var url by remember { mutableStateOf(item.url) }
    var notes by remember { mutableStateOf(item.notes) }
    var folder by remember { mutableStateOf(item.folder) }
    var tags by remember { mutableStateOf(item.tags.joinToString(",")) }
    var favorite by remember { mutableStateOf(item.favorite) }
    var autofillAllowed by remember { mutableStateOf(item.autofillAllowed) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        val reset = { password = "" }
        SecretInputRegistry.register(reset)
        onDispose { SecretInputRegistry.unregister(reset) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit item") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(CKDimens.Space2),
            ) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true)
                OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true)
                OutlinedTextField(
                    password,
                    { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(url, { url = it }, label = { Text("URL") }, singleLine = true)
                OutlinedTextField(notes, { notes = it }, label = { Text("Notes") }, minLines = 2)
                OutlinedTextField(folder, { folder = it }, label = { Text("Folder") }, singleLine = true)
                OutlinedTextField(tags, { tags = it }, label = { Text("Tags") }, singleLine = true)
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { favorite = !favorite },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(favorite, onCheckedChange = { favorite = it })
                    Text("Favorite")
                }
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { autofillAllowed = !autofillAllowed },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(autofillAllowed, onCheckedChange = { autofillAllowed = it })
                    Text("Allow autofill for this item's exact origin")
                }
                if (error != null) {
                    Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (title.isBlank()) {
                    error = "Title is required."
                    return@TextButton
                }
                scope.launch {
                    controller.updateItem(
                        item, title.trim(), username.trim(), password, url.trim(), notes,
                        folder.trim(), tags, favorite, autofillAllowed,
                    ).onSuccess { onDismiss() }
                        .onFailure { error = "Item could not be saved." }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = CKDimens.Space1),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    controller: SessionController,
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
    onBack: () -> Unit,
) {
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var updateNotes by remember { mutableStateOf<String?>(null) }
    var dailyCheck by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val updateChecker = remember { UpdateChecker(context) }
    val updatePreferences = remember { UpdatePreferences(context) }
    val activity = context as? FragmentActivity
    val scope = rememberCoroutineScope()
    val biometricKey = remember { BiometricKeyManager() }
    var biometricEnabled by remember { mutableStateOf(false) }
    var biometricMessage by remember { mutableStateOf<String?>(null) }
    var showDesignGallery by remember { mutableStateOf(false) }
    var syncTestMessage by remember { mutableStateOf<String?>(null) }
    var backupPassword by remember { mutableStateOf("") }
    var backupMessage by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null && backupPassword.isNotEmpty()) {
            scope.launch {
                controller.exportBackup(backupPassword)
                    .onSuccess { bytes ->
                        runCatching {
                            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        }.onSuccess {
                            val checksum = MessageDigest.getInstance("SHA-256")
                                .digest(bytes)
                                .joinToString("") { "%02x".format(it) }
                            backupMessage = "Encrypted backup written (${bytes.size} bytes).\nSHA-256: $checksum"
                        }.onFailure {
                            backupMessage = "Could not write the backup file."
                        }
                    }
                    .onFailure { backupMessage = "Backup encryption failed. Use a 12+ character export password." }
                backupPassword = ""
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null && backupPassword.isNotEmpty()) {
            scope.launch {
                val bytes = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
                if (bytes == null) {
                    backupMessage = "Could not read the selected file."
                } else {
                    controller.importBackup(bytes, backupPassword)
                        .onSuccess { count -> backupMessage = "$count items restored." }
                        .onFailure { backupMessage = "Restore failed. Check the password and file." }
                }
                backupPassword = ""
            }
        }
    }
    LaunchedEffect(controller) {
        biometricEnabled = controller.biometricWrapped() != null
        dailyCheck = updatePreferences.dailyEnabled()
    }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(CKDimens.Space4)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(CKDimens.Space4),
        ) {
            Text(
                text = "Appearance",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(CKDimens.Space2)) {
                ThemeMode.entries.forEach { mode ->
                    if (mode == themeMode) {
                        Button(onClick = { onThemeMode(mode) }) { Text(mode.name) }
                    } else {
                        OutlinedButton(onClick = { onThemeMode(mode) }) { Text(mode.name) }
                    }
                }
            }

            OutlinedButton(
                onClick = { showDesignGallery = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Design token gallery") }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            Text(
                text = "Security",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Column(modifier = Modifier.padding(CKDimens.Space4)) {
                    Text("Screenshots blocked (FLAG_SECURE)", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Backups disabled (allowBackup=false)",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = CKDimens.Space2),
                    )
                    Text(
                        "Network: manual GitHub update checks only",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = CKDimens.Space2),
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            Text(
                text = "Biometric unlock",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Column(modifier = Modifier.padding(CKDimens.Space4)) {
                    Text(
                        text = "Wraps the master key with a Keystore key that requires biometric or device-credential authentication.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (biometricMessage != null) {
                        Text(
                            text = biometricMessage.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = CKDimens.Space2),
                        )
                    }
                    Spacer(Modifier.height(CKDimens.Space3))
                    Button(
                        onClick = {
                            if (activity == null || !canUseBiometrics(activity)) {
                                biometricMessage = "No biometric or device-credential authenticator is available."
                                return@Button
                            }
                            if (biometricEnabled) {
                                scope.launch {
                                    controller.clearBiometric()
                                    biometricKey.deleteKey()
                                    biometricEnabled = false
                                    biometricMessage = "Biometric unlock disabled."
                                }
                            } else {
                                scope.launch {
                                    val master = controller.biometricMasterKey()
                                    if (master == null) {
                                        biometricMessage = "Unlock the vault with your password first."
                                        return@launch
                                    }
                                    try {
                                        val cipher = biometricKey.encryptCipher()
                                        promptBiometric(
                                            activity = activity,
                                            cipher = cipher,
                                            title = "Enable biometric unlock",
                                            subtitle = "Authenticate to wrap the vault master key.",
                                            onSuccess = { authenticated ->
                                                scope.launch {
                                                    try {
                                                        val ciphertext = authenticated.doFinal(master)
                                                        controller.saveBiometricWrapped(
                                                            WrappedSecret(
                                                                WrappedSecret.VERSION,
                                                                authenticated.iv,
                                                                ciphertext,
                                                            ),
                                                        )
                                                        biometricEnabled = true
                                                        biometricMessage = "Biometric unlock enabled."
                                                    } catch (_: Exception) {
                                                        biometricMessage = "Could not enable biometric unlock."
                                                    } finally {
                                                        master.fill(0)
                                                    }
                                                }
                                            },
                                            onFailure = { reason ->
                                                master.fill(0)
                                                biometricMessage = "Biometric setup failed: $reason"
                                            },
                                        )
                                    } catch (_: Exception) {
                                        master.fill(0)
                                        biometricMessage = "Biometric key is unavailable on this device."
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (biometricEnabled) "Disable biometric unlock" else "Enable biometric unlock")
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            Text(
                text = "Updates",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            OutlinedButton(
                onClick = {
                    updateMessage = "Checking GitHub Releases…"
                    updateNotes = null
                    scope.launch {
                        when (val outcome = updateChecker.check()) {
                            is UpdateOutcome.Available -> {
                                updateMessage = "Update available: ${outcome.currentVersion} → ${outcome.latestVersion}\n" +
                                    "Published ${outcome.publishedAt}; ${outcome.sizeBytes} bytes."
                                updateNotes = outcome.notes
                            }
                            is UpdateOutcome.UpToDate -> {
                                updateMessage = "Up to date (${outcome.currentVersion})."
                            }
                            UpdateOutcome.NoRelease -> {
                                updateMessage = "No public release has been published yet."
                            }
                            UpdateOutcome.RateLimited -> {
                                updateMessage = "GitHub rate limit reached. Try again later."
                            }
                            is UpdateOutcome.Failed -> {
                                updateMessage = outcome.message
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Check for updates")
            }
            if (updateMessage != null) {
                Text(
                    text = updateMessage.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (updateNotes != null) {
                Text(
                    text = updateNotes.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = CKDimens.Space2),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = CKDimens.Space3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Switch(
                    checked = dailyCheck,
                    onCheckedChange = { enabled ->
                        dailyCheck = enabled
                        scope.launch {
                            updatePreferences.setDailyEnabled(enabled)
                            UpdateScheduler.setDaily(context, enabled)
                        }
                    },
                )
                Text(
                    text = "Daily check (off by default)",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = CKDimens.Space2),
                )
            }
            Text(
                text = "Download and install arrive in P5-001. This check never installs anything.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = CKDimens.Space2),
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            Text(
                text = "Autofill Inspector",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(CKDimens.Space4)) {
                    var recipeRegistry by remember {
                        mutableStateOf<dev.cipherkeep.app.data.RecipeRegistry?>(null)
                    }
                    LaunchedEffect(Unit) {
                        recipeRegistry = withContext(Dispatchers.IO) {
                            dev.cipherkeep.app.data.RecipeRegistry.load(context)
                        }
                    }
                    Text(
                        text = recipeRegistry?.let { "Recipe registry v${it.version} · ${it.hash.take(16)}…" }
                            ?: "Recipe registry unavailable or signature invalid",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val eligible = controller.items.filter {
                        it.autofillAllowed && it.url.isNotBlank() && it.password.isNotEmpty()
                    }
                    Text("${eligible.size} item(s) explicitly allowed for autofill")
                    eligible.forEach { item ->
                        val origin = runCatching { java.net.URI(item.url).host }.getOrNull() ?: "invalid URL"
                        Text(
                            text = "${item.title} · $origin",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (eligible.isEmpty()) {
                        Text(
                            text = "No item is autofill-eligible. Open an item, choose Edit and opt in for its exact origin.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            SyncFolderSection(context)
            OutlinedButton(
                onClick = {
                    scope.launch {
                        controller.exportSyncRecordLength()
                            .onSuccess { syncTestMessage = "V2 sync record: $it bytes" }
                            .onFailure { syncTestMessage = "V2 sync record failed" }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Test V2 sync record") }
            syncTestMessage?.let {
                Text(it, style = MaterialTheme.typography.labelSmall)
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            Text(
                text = "Encrypted backup",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = "Use a separate password of at least 12 characters. The file is encrypted with Argon2id and XChaCha20-Poly1305.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = backupPassword,
                onValueChange = { backupPassword = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Export/restore password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(CKDimens.Space2)) {
                Button(
                    onClick = {
                        backupMessage = null
                        val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                        exportLauncher.launch("cipherkeep-backup-$date.ckexp")
                    },
                    enabled = backupPassword.length >= 12,
                    modifier = Modifier.weight(1f),
                ) { Text("Export") }
                OutlinedButton(
                    onClick = {
                        backupMessage = null
                        importLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                    },
                    enabled = backupPassword.length >= 12,
                    modifier = Modifier.weight(1f),
                ) { Text("Restore") }
            }
            if (backupMessage != null) {
                Text(
                    text = backupMessage.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            Text(
                text = "About",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = "CipherKeep 0.1.0-shell\nRust core connected via UniFFI.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (showDesignGallery) {
        AlertDialog(
            onDismissRequest = { showDesignGallery = false },
            title = { Text("Design tokens") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(CKDimens.Space2)) {
                    listOf(
                        "Primary" to CKColor.LightPrimary,
                        "Accent strong" to CKColor.LightAccentStrong,
                        "Danger" to CKColor.LightDangerText,
                        "Surface" to CKColor.LightSurfaceMuted,
                    ).forEach { (label, color) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(CKDimens.TouchTarget)
                                    .background(color, RoundedCornerShape(CKDimens.RadiusSm)),
                            )
                            Text(label, modifier = Modifier.padding(start = CKDimens.Space2))
                        }
                    }
                    Text("Body 16sp · Small 14sp · Caption 12sp", style = MaterialTheme.typography.bodyMedium)
                    Text("Spacing 4/8/12/16/24/32/40/48 dp", style = MaterialTheme.typography.bodySmall)
                    Text("Focus ring 2px · touch target 48dp", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { showDesignGallery = false }) { Text("Close") } },
        )
    }
}
