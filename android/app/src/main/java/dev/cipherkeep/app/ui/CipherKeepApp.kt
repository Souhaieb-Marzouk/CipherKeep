package dev.cipherkeep.app.ui

import android.content.Context
import android.content.Intent
import android.view.WindowManager
import android.net.Uri
import dev.cipherkeep.app.BuildConfig
import dev.cipherkeep.app.update.UpdateInstaller

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.fragment.app.FragmentActivity
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
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
import dev.cipherkeep.app.data.BackupFileBridge
import dev.cipherkeep.app.data.DisplayPreferences
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
    androidx.compose.runtime.LaunchedEffect(Unit) {
        dev.cipherkeep.app.data.SyncRuntime.controller = controller
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "cipherkeep-sync",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<dev.cipherkeep.app.data.SyncWorker>(15, TimeUnit.MINUTES).build(),
        )
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
            VaultUiState.Travel -> TravelScreen(controller)
                VaultUiState.LockWarning -> LockWarningScreen(controller)
                is VaultUiState.StorageError -> StorageErrorScreen(controller, state.message)
                VaultUiState.Unlocked -> {
                    BackHandler(enabled = screen != "vault" || selectedIndex >= 0) {
                        if (selectedIndex >= 0) {
                            selectedIndex = -1
                            screen = "vault"
                        } else {
                            screen = "vault"
                        }
                    }
                    Scaffold(
                        contentWindowInsets = WindowInsets.safeDrawing,
                        bottomBar = {
                            AuroraNavBar(
                                current = screen,
                                onVault = { screen = "vault"; selectedIndex = -1 },
                                onAdd = { screen = "add" },
                                onSecurity = { screen = "security"; selectedIndex = -1 },
                                onSettings = { screen = "settings"; selectedIndex = -1 },
                                onMonitor = { screen = "monitor"; selectedIndex = -1 },
                            )
                        },
                    ) { shellPadding ->
                    Box(Modifier.padding(shellPadding)) {
                    when (screen) {
                        "vault" -> if (selectedIndex in controller.items.indices) {
                            ItemDetailScreen(
                                controller = controller,
                                item = controller.items[selectedIndex],
                                onBack = { selectedIndex = -1 },
                                onDeleted = { selectedIndex = -1 },
                                onStable = { id -> selectedIndex = controller.items.indexOfFirst { it.id.contentEquals(id) } },
                            )
                        } else {
                            VaultScreen(
                                controller = controller,
                                items = controller.items,
                                onOpen = { index -> selectedIndex = index },
                                onAdd = { screen = "add" },
                                onSettings = { screen = "settings" },
                                onSecurity = { screen = "security" },
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
                        "security" -> SecurityHubScreen(
                            context = context,
                            controller = controller,
                            onBack = { screen = "vault" },
                        )
                        "settings" -> SettingsHubScreen(
                            context = context,
                            controller = controller,
                            onAdvanced = { screen = "settings_advanced" },
                            onBack = { screen = "vault" },
                        )
                        "settings_advanced" -> SettingsScreen(
                            controller = controller,
                            themeMode = ThemeMode.valueOf(themeName),
                            onThemeMode = { themeName = it.name },
                            onBack = { screen = "settings" },
                        )
                        "monitor" -> MonitorScreen(
                            controller = controller,
                            onOpen = { index -> selectedIndex = index; screen = "vault" },
                        )
                    }
                    }
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
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
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
    var biometricAttempted by remember { mutableStateOf(false) }
    LaunchedEffect(biometricWrapped, activity) {
        if (!biometricAttempted && biometricWrapped != null && activity != null && canUseBiometrics(activity)) {
            biometricAttempted = true
            busy = true
            error = null
            val wrapped = biometricWrapped ?: return@LaunchedEffect
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
        }
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
    var kind by remember { mutableStateOf("login") }
    var title by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    var favorite by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var totpUri by remember { mutableStateOf("") }
    var cardholder by remember { mutableStateOf("") }
    var cardNumber by remember { mutableStateOf("") }
    var cardExpiry by remember { mutableStateOf("") }
    var cardCvv by remember { mutableStateOf("") }
    var fullName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var company by remember { mutableStateOf("") }
    var jobTitle by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val scanQr = rememberQrScanner({ totpUri = it }, { error = it })

    fun saveLogin() {
        busy = true
        scope.launch {
            if (totpUri.isNotBlank() && runCatching {
                    totpCode(totpUri, (System.currentTimeMillis() / 1000).toULong())
                }.isFailure) {
                busy = false
                error = "Invalid TOTP URI."
                return@launch
            }
            val result = controller.saveItem(
                title.trim(), username.trim(), password, url.trim(), notes,
                folder.trim(), tags, favorite, false, totpUri.trim(),
            )
            busy = false
            password = ""
            totpUri = ""
            if (result.isSuccess) onBack() else error = "Item could not be saved."
        }
    }

    fun saveTyped() {
        busy = true
        scope.launch {
            val fields = when (kind) {
                "card" -> mapOf(
                    "notes" to notes, "cardholder" to cardholder.trim(), "cardNumber" to cardNumber.trim(),
                    "cardExpiry" to cardExpiry.trim(), "cardCvv" to cardCvv.trim(),
                )
                "identity" -> mapOf(
                    "notes" to notes, "fullName" to fullName.trim(), "email" to email.trim(),
                    "phone" to phone.trim(), "address" to address.trim(), "company" to company.trim(),
                    "jobTitle" to jobTitle.trim(),
                )
                else -> mapOf("notes" to notes)
            }
            val result = controller.saveTypedItem(kind, title.trim(), fields, folder.trim(), tags, favorite)
            busy = false
            if (result.isSuccess) onBack() else error = "Item could not be saved."
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("New item") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(CKDimens.Space4),
            verticalArrangement = Arrangement.spacedBy(CKDimens.Space3),
        ) {
            Text("What are you adding?", style = MaterialTheme.typography.titleLarge)
            Text("Choose a card to start. Only the fields you need will appear.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(CKDimens.Space2),
            ) {
                listOf(
                    Triple("login", "Login", "✦"),
                    Triple("note", "Note", "✎"),
                    Triple("card", "Card", "▣"),
                    Triple("identity", "Identity", "◎"),
                ).forEach { (value, label, glyph) ->
                    val selected = kind == value
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.width(88.dp).height(82.dp).clickable { kind = value },
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(10.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(glyph, style = MaterialTheme.typography.headlineSmall, color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary)
                            Text(label, style = MaterialTheme.typography.labelSmall, color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Title") },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
            )
            when (kind) {
                "login" -> {
                    OutlinedTextField(username, { username = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Username") }, singleLine = true)
                    OutlinedTextField(password, { password = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    OutlinedTextField(url, { url = it }, modifier = Modifier.fillMaxWidth(), label = { Text("URL (optional)") }, singleLine = true)
                    OutlinedTextField(totpUri, { totpUri = it }, modifier = Modifier.fillMaxWidth(), label = { Text("TOTP code (paste otpauth URI)") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                    OutlinedButton(onClick = scanQr, modifier = Modifier.fillMaxWidth()) { Text("Scan QR code") }
                }
                "card" -> {
                    OutlinedTextField(cardholder, { cardholder = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Cardholder name") }, singleLine = true)
                    OutlinedTextField(cardNumber, { cardNumber = it.filter { ch -> ch.isDigit() || ch == ' ' }.take(23) }, modifier = Modifier.fillMaxWidth(), label = { Text("Card number") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Row(horizontalArrangement = Arrangement.spacedBy(CKDimens.Space2)) {
                        OutlinedTextField(cardExpiry, { cardExpiry = it.take(5) }, modifier = Modifier.weight(1f), label = { Text("MM/YY") }, singleLine = true)
                        OutlinedTextField(cardCvv, { cardCvv = it.filter(Char::isDigit).take(4) }, modifier = Modifier.weight(1f), label = { Text("CVV") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                    }
                }
                "identity" -> {
                    OutlinedTextField(fullName, { fullName = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Full name") }, singleLine = true)
                    OutlinedTextField(email, { email = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Email") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                    OutlinedTextField(phone, { phone = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Phone") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                    OutlinedTextField(address, { address = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Address") }, minLines = 2)
                    OutlinedTextField(company, { company = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Company") }, singleLine = true)
                    OutlinedTextField(jobTitle, { jobTitle = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Job title") }, singleLine = true)
                }
            }
            OutlinedTextField(notes, { notes = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Notes (optional)") }, minLines = 2, maxLines = 4)
            OutlinedTextField(folder, { folder = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Folder (optional)") }, singleLine = true)
            OutlinedTextField(tags, { tags = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Tags (comma separated)") }, singleLine = true)
            Row(modifier = Modifier.fillMaxWidth().clickable { favorite = !favorite }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = favorite, onCheckedChange = { favorite = it })
                Text("Favorite")
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = { if (kind == "login") saveLogin() else saveTyped() },
                enabled = !busy && title.isNotBlank() && (kind != "login" || password.isNotEmpty()),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "Saving…" else "Save ${kind.replaceFirstChar(Char::uppercase)}") }
        }
    }
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
private fun SecurityHubScreen(context: Context, controller: SessionController, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Security") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(CKDimens.Space4),
            verticalArrangement = Arrangement.spacedBy(CKDimens.Space3),
        ) {
            Text("Password health", style = MaterialTheme.typography.titleMedium)
            HealthSection(controller)
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Text("Breach monitoring", style = MaterialTheme.typography.titleMedium)
            HibpSection(context, controller)
        }
    }
}

private fun typeGlyph(item: VaultItem): String = when (item.type) {
    "note" -> "N"
    "card" -> "C"
    "identity" -> "I"
    "totp" -> "T"
    else -> "L"
}

@Composable
private fun itemSubtitle(item: VaultItem): String {
    val showUsername = DisplayPreferences(LocalContext.current).showUsernames()
    return when (item.type) {
    "note" -> "Secure note"
    "card" -> listOf("Card", item.cardholder).filter(String::isNotBlank).joinToString(" · ")
    "identity" -> listOf("Identity", item.fullName.ifBlank { item.email }).filter(String::isNotBlank).joinToString(" · ")
    "totp" -> listOf("TOTP", item.username).filter(String::isNotBlank).joinToString(" · ")
        else -> if (showUsername) item.username.ifBlank { "No username" } else ""
    }
}


@Composable
private fun AuroraNavBar(current: String, onVault: () -> Unit, onAdd: () -> Unit, onSecurity: () -> Unit, onSettings: () -> Unit, onMonitor: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(96.dp).navigationBarsPadding()) {
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = CKDimens.Space4).height(66.dp),
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 10.dp,
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { AuroraNavItem("⌂", "Vault", current == "vault", onVault) }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { AuroraNavItem("◈", "Security", current == "security", onSecurity) }
                Spacer(Modifier.weight(1f))
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { AuroraNavItem("⚙", "Settings", current == "settings" || current == "settings_advanced", onSettings) }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { AuroraNavItem("◔", "Monitor", current == "monitor", onMonitor) }
            }
        }
        Surface(
            modifier = Modifier.align(Alignment.TopCenter).size(62.dp).clickable(onClick = onAdd),
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.primary,
            shadowElevation = 12.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("+", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

@Composable
private fun AuroraNavItem(glyph: String, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(glyph, style = MaterialTheme.typography.titleMedium, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}


private fun passwordWeak(password: String): Boolean {
    if (password.length < 12) return true
    val classes = listOf(
        password.any(Char::isLowerCase),
        password.any(Char::isUpperCase),
        password.any(Char::isDigit),
        password.any { !it.isLetterOrDigit() },
    ).count { it }
    return classes < 3
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MonitorScreen(controller: SessionController, onOpen: (Int) -> Unit) {
    var filter by remember { mutableStateOf("all") }
    val logins = controller.items.filter { it.type == "login" && it.password.isNotEmpty() }
    val groups = logins.groupBy { it.password }
    val weak = logins.filter { passwordWeak(it.password) }
    val reused = logins.filter { (groups[it.password]?.size ?: 0) > 1 }
    val no2fa = logins.filter { it.totpUri.isBlank() }
    val filtered = when (filter) {
        "weak" -> weak
        "reused" -> reused
        "no2fa" -> no2fa
        else -> logins
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Monitor") }) },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(CKDimens.Space4),
            verticalArrangement = Arrangement.spacedBy(CKDimens.Space3),
        ) {
            item {
                Text("Password health", style = MaterialTheme.typography.headlineSmall)
                Text("Tap a card to filter the list.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(CKDimens.Space3))
                Row(horizontalArrangement = Arrangement.spacedBy(CKDimens.Space2)) {
                    MonitorMetric("Weak", weak.size, filter == "weak", Modifier.weight(1f)) { filter = "weak" }
                    MonitorMetric("Reused", reused.size, filter == "reused", Modifier.weight(1f)) { filter = "reused" }
                    MonitorMetric("No 2FA", no2fa.size, filter == "no2fa", Modifier.weight(1f)) { filter = "no2fa" }
                }
                if (filter != "all") {
                    TextButton(onClick = { filter = "all" }) { Text("Show all logins") }
                }
                Text("${filtered.size} items", style = MaterialTheme.typography.titleMedium)
            }
            items(filtered) { item ->
                val index = controller.items.indexOf(item)
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onOpen(index) },
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Row(Modifier.fillMaxWidth().padding(CKDimens.Space4), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(44.dp)) {
                            Box(contentAlignment = Alignment.Center) { WebsiteIcon(item, typeGlyph(item)) }
                        }
                        Spacer(Modifier.width(CKDimens.Space3))
                        Column(Modifier.weight(1f)) {
                            Text(item.title, style = MaterialTheme.typography.titleMedium)
                            Text(item.username.ifBlank { "No username" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(if (item.totpUri.isBlank()) "2FA off" else "2FA on", style = MaterialTheme.typography.labelSmall, color = if (item.totpUri.isBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun MonitorMetric(label: String, count: Int, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(CKDimens.Space3), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(count.toString(), style = MaterialTheme.typography.headlineMedium, color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
            Text(label, style = MaterialTheme.typography.labelSmall, color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
    onSecurity: () -> Unit,
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
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(CKDimens.Space4),
            verticalArrangement = Arrangement.spacedBy(CKDimens.Space3),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("CIPHERKEEP", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text("Your vault, quietly secure", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                    }
                    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
                        TextButton(onClick = onLock) { Text("Lock") }
                    }
                }
                Spacer(Modifier.height(CKDimens.Space3))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Row(Modifier.fillMaxWidth().padding(CKDimens.Space4), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${items.size} ${if (items.size == 1) "item" else "items"}", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimary)
                            Text(
                                "${items.count { it.favorite }} favorites · ${items.map { it.folder }.filter { it.isNotBlank() }.distinct().size} folders",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                            )
                        }
                        Text("✦", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                }
                Spacer(Modifier.height(CKDimens.Space3))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search by name, user or website") },
                    leadingIcon = { Text("⌕", style = MaterialTheme.typography.titleLarge) },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                )
                Spacer(Modifier.height(CKDimens.Space2))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CKDimens.Space2)) {
                    listOf(false to "All", true to "Favorites").forEach { (fav, label) ->
                        val active = favoritesOnly == fav
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.clickable { favoritesOnly = fav },
                        ) { Text(label, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium, color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface) }
                    }
                    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.clickable { showFolders = true }) {
                        Text("Folders", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { sortMode = (sortMode + 1) % 3 }) {
                        Text(when (sortMode) { 1 -> "Recent"; 2 -> "Folder"; else -> "A–Z" })
                    }
                }
                OutlinedTextField(
                    value = filterText,
                    onValueChange = { filterText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Filter by folder or tag") },
                    singleLine = true,
                    shape = RoundedCornerShape(50),
                )
                Spacer(Modifier.height(CKDimens.Space2))
                Text("${visibleItems.size} shown", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (visibleItems.isEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(24.dp),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(CKDimens.Space6)) {
                            Text(if (items.isEmpty()) "A quiet vault" else "Nothing here yet", style = MaterialTheme.typography.titleLarge)
                            Text(
                                if (items.isEmpty()) "Create your first login, card, note or identity with the Add button below." else "Try another search or clear the filter.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            val pinnedItems = visibleItems.filter { it.favorite }
            if (pinnedItems.isNotEmpty()) {
                item { Text("Pinned", style = MaterialTheme.typography.titleMedium) }
                items(pinnedItems) { item ->
                    val index = items.indexOf(item)
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.fillMaxWidth().clickable { onOpen(index) },
                    ) {
                        Row(Modifier.fillMaxWidth().padding(CKDimens.Space3), verticalAlignment = Alignment.CenterVertically) {
                            Text("★", color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(CKDimens.Space2))
                            Text(item.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Text(itemSubtitle(item), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item { Text("All items", style = MaterialTheme.typography.titleMedium) }
            }
            items(visibleItems.filterNot { it.favorite }) { item ->
                val index = items.indexOf(item)
                Card(
                    modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "Open ${item.title}") { onOpen(index) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(20.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = CKDimens.Space4, vertical = CKDimens.Space3),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(46.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                WebsiteIcon(item, typeGlyph(item))
                            }
                        }
                        Spacer(Modifier.width(CKDimens.Space3))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(item.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                if (item.favorite) Text("★", color = MaterialTheme.colorScheme.primary)
                            }
                            Text(itemSubtitle(item), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            if (item.folder.isNotBlank()) Text(item.folder, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    onStable: (ByteArray) -> Unit = {},
) {
    var revealed by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showMove by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
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
                    totpRemaining = it.secondsRemaining.toInt().takeIf { seconds -> seconds > 0 }
                        ?: (30 - (System.currentTimeMillis() / 1000 % 30)).toInt()
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
                    TextButton(onClick = { menuOpen = true }) { Text("⋮") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text(if (item.favorite) "Unpin" else "Pin") }, onClick = {
                            menuOpen = false
                            scope.launch { controller.setFavorite(item, !item.favorite).onSuccess { onStable(item.id) }.onFailure { actionError = "Could not update pin." } }
                        })
                        DropdownMenuItem(text = { Text("Move to folder") }, onClick = { menuOpen = false; showMove = true })
                        DropdownMenuItem(text = { Text("Duplicate") }, onClick = {
                            menuOpen = false
                            scope.launch { controller.duplicateItem(item).onSuccess { onStable(item.id) }.onFailure { actionError = "Could not duplicate." } }
                        })
                        DropdownMenuItem(text = { Text("Edit") }, onClick = { menuOpen = false; showEdit = true })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; showDelete = true })
                    }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(58.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(typeGlyph(item), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Spacer(Modifier.width(CKDimens.Space3))
                Column {
                    Text(
                        text = itemSubtitle(item).substringBefore(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() },
                    )
                    if (item.folder.isNotBlank()) {
                        Text(item.folder, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
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
            }
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
                        copyValue = item.password,
                    )
                    val strength = when {
                        item.password.length < 8 -> "Weak"
                        item.password.length < 12 -> "Fair"
                        item.password.length >= 16 && item.password.any { !it.isLetterOrDigit() } -> "Strong"
                        else -> "Good"
                    }
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (strength == "Weak") MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            strength,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (strength == "Weak") MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    if (item.type == "card") {
                        if (item.cardholder.isNotBlank()) DetailRow("Cardholder", item.cardholder)
                        if (item.cardNumber.isNotBlank()) {
                            DetailRow(
                                "Card number",
                                if (revealed) item.cardNumber else "•••• " + item.cardNumber.takeLast(4),
                                item.cardNumber,
                            )
                        }
                        if (item.cardExpiry.isNotBlank()) DetailRow("Expiry", item.cardExpiry)
                        if (item.cardCvv.isNotBlank()) {
                            DetailRow("CVV", if (revealed) item.cardCvv else "•••", item.cardCvv)
                        }
                    }
                    if (item.type == "identity") {
                        if (item.fullName.isNotBlank()) DetailRow("Full name", item.fullName)
                        if (item.email.isNotBlank()) DetailRow("Email", item.email)
                        if (item.phone.isNotBlank()) DetailRow("Phone", item.phone)
                        if (item.address.isNotBlank()) DetailRow("Address", item.address)
                        if (item.company.isNotBlank()) DetailRow("Company", item.company)
                        if (item.jobTitle.isNotBlank()) DetailRow("Job title", item.jobTitle)
                    }
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
    if (showMove) {
        var folderText by remember { mutableStateOf(item.folder) }
        AlertDialog(
            onDismissRequest = { showMove = false },
            title = { Text("Move to folder") },
            text = {
                Column {
                    OutlinedTextField(value = folderText, onValueChange = { folderText = it }, label = { Text("Folder") }, singleLine = true)
                    TextButton(onClick = {
                        scope.launch { controller.moveItem(item, folderText.trim()).onSuccess { showMove = false; onStable(item.id) }.onFailure { actionError = "Could not move item." } }
                    }) { Text("Move") }
                }
            },
            confirmButton = { TextButton(onClick = { showMove = false }) { Text("Cancel") } },
        )
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
    var totpUri by remember { mutableStateOf(item.totpUri) }
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
                OutlinedTextField(totpUri, { totpUri = it }, label = { Text("TOTP code (optional)") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
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
                        folder.trim(), tags, favorite, autofillAllowed, totpUri.trim(),
                    ).onSuccess { onDismiss() }
                        .onFailure { error = "Item could not be saved." }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DetailRow(label: String, value: String, copyValue: String = value) {
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = Modifier.fillMaxWidth().clickable {
            if (copyValue.isNotEmpty()) clipboard.setText(AnnotatedString(copyValue))
        }.padding(vertical = CKDimens.Space2),
    ) {
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


private fun extractOnePuxExportData(bytes: ByteArray): ByteArray? =
    extractZipEntry(bytes, "export.data")

private fun extractZipEntry(bytes: ByteArray, wanted: String): ByteArray? = runCatching {
    java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val entry = zip.nextEntry ?: return null
            if (entry.name == wanted) {
                val output = java.io.ByteArrayOutputStream()
                var total = 0
                while (true) {
                    val read = zip.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > 48_000_000) return null
                    output.write(buffer, 0, read)
                }
                return output.toByteArray()
            }
        }
        null
    }
}.getOrNull()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportSection(context: Context, controller: SessionController) {
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var importPassword by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(CKDimens.Space2)) {
        Text("Import passwords", style = MaterialTheme.typography.titleMedium)
        Text(
            "Supports plaintext CSV/JSON exports from Proton Pass, Bitwarden, 1Password and KeePass. Encrypted exports are not supported.",
            style = MaterialTheme.typography.labelSmall,
        )
        OutlinedTextField(
            value = importPassword,
            onValueChange = { importPassword = it },
            label = { Text("Bitwarden export password (only for encrypted JSON)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = {
                val activity = context as? FragmentActivity
                if (activity == null) {
                    message = "File picker unavailable."
                    return@OutlinedButton
                }
                BackupFileBridge.textImportHandler = { uri ->
                    scope.launch {
                        val bytes = runCatching {
                            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        }.getOrNull()
                        if (bytes == null) {
                            message = "Could not read the selected file."
                        } else {
                            val payload = when {
                                dev.cipherkeep.app.data.BitwardenEncryptedImporter.looksEncrypted(bytes) -> {
                                    if (importPassword.isEmpty()) {
                                        message = "Enter the Bitwarden export password."
                                        null
                                    } else {
                                        runCatching {
                                            dev.cipherkeep.app.data.BitwardenEncryptedImporter
                                                .decrypt(bytes, importPassword)
                                        }.onFailure {
                                            message = "Bitwarden decryption failed. Argon2id exports are not supported."
                                        }.getOrNull()
                                    }
                                }
                                bytes.size >= 4 && bytes[0] == 0x03.toByte() && bytes[1] == 0xD9.toByte() &&
                                    bytes[2] == 0xA2.toByte() && bytes[3] == 0x9A.toByte() -> {
                                    if (importPassword.isEmpty()) {
                                        message = "Enter the KeePass database password."
                                    } else {
                                        controller.importKdbx(bytes, importPassword)
                                            .onSuccess { message = "$it items imported." }
                                            .onFailure { message = "KeePass import failed. Check the password." }
                                    }
                                    null
                                }
                                bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() -> {
                                    val jsonEntry = extractZipEntry(bytes, "export.data")
                                    val pgpEntry = extractZipEntry(bytes, "data.pgp")
                                    when {
                                        jsonEntry != null -> jsonEntry
                                        pgpEntry != null -> {
                                            if (importPassword.isEmpty()) {
                                                message = "Enter the Proton export passphrase."
                                                null
                                            } else {
                                                runCatching {
                                                    dev.cipherkeep.core.decryptProtonPgp(pgpEntry, importPassword)
                                                }.onFailure {
                                                    message = "Proton PGP decryption failed. Check the passphrase."
                                                }.getOrNull()
                                            }
                                        }
                                        else -> {
                                            message = "The ZIP archive does not contain export.data or data.pgp."
                                            null
                                        }
                                    }
                                }
                                else -> bytes
                            }
                            if (payload != null) {
                                controller.importPlaintext(payload)
                                    .onSuccess { message = "$it items imported." }
                                    .onFailure { message = "Import failed. Check the format." }
                            }
                        }
                    }
                }
                activity.startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    },
                    BackupFileBridge.TEXT_IMPORT_REQUEST_CODE,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Import from a password manager") }
        message?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutSection(context: Context) {
    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(CKDimens.Space4), verticalArrangement = Arrangement.spacedBy(CKDimens.Space2)) {
            Text("About CipherKeep", style = MaterialTheme.typography.titleLarge)
            Text("Designed and architected by Souhaieb Marzouk.", style = MaterialTheme.typography.bodyMedium)
            Text("A local-first, zero-knowledge password manager for Android and Firefox.", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
            Text("License: GNU AGPL-3.0", style = MaterialTheme.typography.bodySmall)
            Text("Package: ${context.packageName}", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(CKDimens.Space2)) {
                TextButton(onClick = { open("https://github.com/Souhaieb-Marzouk/CipherKeep") }) { Text("GitHub") }
                TextButton(onClick = { open("https://github.com/Souhaieb-Marzouk/CipherKeep/blob/main/LICENSE") }) { Text("License") }
                TextButton(onClick = { open("https://github.com/Souhaieb-Marzouk/CipherKeep/blob/main/SECURITY.md") }) { Text("Security") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsHubScreen(
    context: Context,
    controller: SessionController,
    onAdvanced: () -> Unit,
    onBack: () -> Unit,
) {
    var section by remember { mutableStateOf<String?>(null) }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(CKDimens.Space4),
            verticalArrangement = Arrangement.spacedBy(CKDimens.Space2),
        ) {
            Text("Everything in one place", style = MaterialTheme.typography.headlineSmall)
            Text("Choose an area. Each opens its own window.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(CKDimens.Space2))
            SettingsRow("♥", "Password health", "Weak, reused and aging passwords") { section = "health" }
            SettingsRow("◆", "Breach monitoring", "Check passwords against HIBP") { section = "hibp" }
            SettingsRow("↓", "Import passwords", "Proton, Bitwarden, 1Password, KeePass") { section = "import" }
            SettingsRow("✉", "Email aliases", "SimpleLogin and Addy.io") { section = "aliases" }
            SettingsRow("◷", "Emergency access", "Cron-delivered recovery shares") { section = "emergency" }
            SettingsRow("✈", "Travel Mode", "Decoy vault for travel") { section = "travel" }
            SettingsRow("◎", "Family sharing", "Owner, editor and viewer roles") { section = "family" }
            SettingsRow("ℹ", "About CipherKeep", "Designer, version, license and links") { section = "about" }
            Spacer(Modifier.height(CKDimens.Space2))
            SettingsRow("⚙", "Advanced settings", "Backup, sync, biometrics, updates, passkeys") { onAdvanced() }
        }
    }
    section?.let { selected ->
        AlertDialog(
            onDismissRequest = { section = null },
            title = { Text(when (selected) { "health" -> "Password health"; "hibp" -> "Breach monitoring"; "import" -> "Import passwords"; "aliases" -> "Email aliases"; "emergency" -> "Emergency access"; "travel" -> "Travel Mode"; "family" -> "Family sharing"; else -> "About CipherKeep" }) },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    when (selected) {
                        "health" -> HealthSection(controller)
                        "hibp" -> HibpSection(context, controller)
                        "import" -> ImportSection(context, controller)
                        "aliases" -> AliasSection(context, controller)
                        "emergency" -> EmergencySection(context, controller)
                        "travel" -> TravelModeSection(controller)
                        "family" -> FamilySection(context, controller)
                        else -> AboutSection(context)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { section = null }) { Text("Close") } },
        )
    }
}

@Composable
private fun SettingsRow(glyph: String, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shadowElevation = 1.dp,
    ) {
        Row(Modifier.fillMaxWidth().padding(CKDimens.Space4), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(42.dp)) {
                Box(contentAlignment = Alignment.Center) { Text(glyph, color = MaterialTheme.colorScheme.onPrimaryContainer) }
            }
            Spacer(Modifier.width(CKDimens.Space3))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
    var availableUpdate by remember { mutableStateOf<UpdateOutcome.Available?>(null) }
    var updateBusy by remember { mutableStateOf(false) }
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
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(24.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(CKDimens.Space4),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(56.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("C", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                    Spacer(Modifier.width(CKDimens.Space3))
                    Column {
                        Text("CipherKeep", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(
                            "Local vault, security and sync settings",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
            val displayPrefs = remember { DisplayPreferences(context) }
            var autofillDefault by remember { mutableStateOf(displayPrefs.autofillDefault()) }
            var screenshotsAllowed by remember { mutableStateOf(displayPrefs.screenshotsAllowed()) }
            var showUsernames by remember { mutableStateOf(displayPrefs.showUsernames()) }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(CKDimens.Space4), verticalArrangement = Arrangement.spacedBy(CKDimens.Space2)) {
                    Text("Preferences", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Autofill new items", modifier = Modifier.weight(1f))
                        Switch(checked = autofillDefault, onCheckedChange = { autofillDefault = it; displayPrefs.setAutofillDefault(it) })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Allow screenshots and app preview", modifier = Modifier.weight(1f))
                        Switch(checked = screenshotsAllowed, onCheckedChange = {
                            screenshotsAllowed = it
                            displayPrefs.setScreenshotsAllowed(it)
                            if (it) activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                            else activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Show usernames under item names", modifier = Modifier.weight(1f))
                        Switch(checked = showUsernames, onCheckedChange = { showUsernames = it; displayPrefs.setShowUsernames(it) })
                    }
                }
            }
            Text(
                text = "Appearance",
                style = MaterialTheme.typography.titleMedium,
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
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(20.dp),
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
                    updateMessage = "Checking…"
                    availableUpdate = null
                    scope.launch {
                        when (val outcome = updateChecker.check()) {
                            is UpdateOutcome.Available -> {
                                availableUpdate = outcome
                                updateMessage = "Update available: ${outcome.latestVersion}"
                            }
                            is UpdateOutcome.UpToDate -> {
                                updateMessage = "You're up to date (${outcome.currentVersion})."
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
            availableUpdate?.let { update ->
                if (update.apkUrl == null) {
                    Text(
                        "This release has no Android APK asset.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Button(
                        enabled = !updateBusy,
                        onClick = {
                            updateBusy = true
                            updateMessage = "Downloading ${update.apkName.ifBlank { "update" }}…"
                            scope.launch {
                                val result = UpdateInstaller.download(context, update)
                                updateBusy = false
                                result.onSuccess { file ->
                                    updateMessage = "Downloaded. Opening installer…"
                                    UpdateInstaller.install(context, file)
                                        .onFailure { updateMessage = "Could not open the installer: ${it.message}" }
                                }.onFailure {
                                    updateMessage = it.message ?: "Update download failed."
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (updateBusy) "Downloading…" else "Download & install ${update.latestVersion}")
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = CKDimens.Space3),
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
                text = "Only a checked release with a matching APK signature can be installed.",
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
            OutlinedButton(onClick = { dev.cipherkeep.app.autofill.PhishingOverrideStore(context).clear() }) { Text("Clear phishing overrides") }
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

            SyncFolderSection(context, controller)
            HibpSection(context, controller)
            HealthSection(controller)
            WebAuthnSection(context, controller)
            PasskeyProviderSection(context)
            RelaySyncSection(controller)
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
                        val target = activity
                        if (target == null) {
                            backupMessage = "File picker unavailable."
                        } else {
                            BackupFileBridge.handler = { uri ->
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
                                        .onFailure {
                                            backupMessage = "Backup encryption failed. Use a 12+ character export password."
                                        }
                                    backupPassword = ""
                                }
                            }
                            target.startActivityForResult(
                                Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                    addCategory(Intent.CATEGORY_OPENABLE)
                                    type = "application/octet-stream"
                                    putExtra(Intent.EXTRA_TITLE, "cipherkeep-backup-$date.ckexp")
                                },
                                BackupFileBridge.REQUEST_CODE,
                            )
                        }
                    },
                    enabled = backupPassword.length >= 12,
                    modifier = Modifier.weight(1f),
                ) { Text("Export") }
                OutlinedButton(
                    onClick = {
                        backupMessage = null
                        val target = activity
                        if (target == null) {
                            backupMessage = "File picker unavailable."
                        } else {
                            BackupFileBridge.importHandler = { uri ->
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
                            target.startActivityForResult(
                                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                    addCategory(Intent.CATEGORY_OPENABLE)
                                    type = "*/*"
                                },
                                BackupFileBridge.IMPORT_REQUEST_CODE,
                            )
                        }
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
