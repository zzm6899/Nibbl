package com.foodtracker.diary

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddAPhoto
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.foodtracker.diary.data.AppSettings
import com.foodtracker.diary.data.AppSettingsRepository
import com.foodtracker.diary.data.BackgroundRemover
import com.foodtracker.diary.data.BackendDeviceClient
import com.foodtracker.diary.data.BackendDrinkReporter
import com.foodtracker.diary.data.BackendFriendTagChecker
import com.foodtracker.diary.data.BackendShareClient
import com.foodtracker.diary.data.BillingRepository
import com.foodtracker.diary.data.BillingUiState
import com.foodtracker.diary.data.CafeCrewPerson
import com.foodtracker.diary.data.CafeCrewStore
import com.foodtracker.diary.data.CategoryStore
import com.foodtracker.diary.data.DiaryLocation
import com.foodtracker.diary.data.DrinkCategory
import com.foodtracker.diary.data.FoodLog
import com.foodtracker.diary.data.FoodLogRepository
import com.foodtracker.diary.data.LocationHelper
import com.foodtracker.diary.data.ShareLinkTokenHelper
import com.foodtracker.diary.data.toFriendInviteCode
import com.foodtracker.diary.ui.theme.FoodDiaryTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

class MainActivity : ComponentActivity() {
    private var deepLinkUrl by mutableStateOf<String?>(null)
    private var sharedImageUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deepLinkUrl = intent?.dataString
        sharedImageUri = intent?.nibblSharedImageUri()
        setContent {
            FoodDiaryTheme {
                DiaryApp(deepLinkUrl, sharedImageUri)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        deepLinkUrl = intent.dataString
        sharedImageUri = intent.nibblSharedImageUri()
    }
}

private enum class CalendarMode { Month, Week, Day }
private enum class AppSection { Diary, Crew, Settings }

private const val FREE_BACKGROUND_REMOVALS_PER_MONTH = 60
private const val FREE_CUSTOM_CATEGORY_LIMIT = 3

private data class PendingLog(
    val originalPath: String,
    val cutoutPath: String,
    val location: DiaryLocation,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiaryApp(deepLinkUrl: String? = null, sharedImageUri: Uri? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { FoodLogRepository(context) }
    val settingsRepository = remember { AppSettingsRepository(context) }
    val billingRepository = remember { BillingRepository(context, settingsRepository) }
    val crewStore = remember { CafeCrewStore(context) }
    val categoryStore = remember { CategoryStore(context) }
    val remover = remember { BackgroundRemover(context) }
    val locationHelper = remember { LocationHelper(context) }
    val backendDrinkReporter = remember { BackendDrinkReporter() }
    var logs by remember { mutableStateOf(emptyList<FoodLog>()) }
    var settings by remember { mutableStateOf(AppSettings()) }
    var settingsLoaded by remember { mutableStateOf(false) }
    var crew by remember { mutableStateOf(emptyList<CafeCrewPerson>()) }
    var categories by remember { mutableStateOf(DrinkCategory.defaults) }
    var section by remember { mutableStateOf(AppSection.Diary) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var mode by remember { mutableStateOf(CalendarMode.Month) }
    var pending by remember { mutableStateOf<PendingLog?>(null) }
    var detailLog by remember { mutableStateOf<FoodLog?>(null) }
    var editLog by remember { mutableStateOf<FoodLog?>(null) }
    var shareLink by remember { mutableStateOf<String?>(null) }
    var selectedFriend by remember { mutableStateOf<String?>(null) }
    var selectedCategory by remember { mutableStateOf<DrinkCategory?>(null) }
    var processing by remember { mutableStateOf(false) }
    var processingPreviewPath by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var friendMessage by remember { mutableStateOf<String?>(null) }
    var billingState by remember { mutableStateOf(BillingUiState(loading = true)) }
    var billingMessage by remember { mutableStateOf<String?>(null) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    var pendingAvatarPerson by remember { mutableStateOf<CafeCrewPerson?>(null) }
    var editFriend by remember { mutableStateOf<CafeCrewPerson?>(null) }
    var handledDeepLinkUrl by remember { mutableStateOf<String?>(null) }
    var handledSharedImageUri by remember { mutableStateOf<String?>(null) }
    val filteredLogs = logs.filter { log ->
        (selectedFriend == null || log.friendNames.contains(selectedFriend)) &&
            (selectedCategory == null || log.category == selectedCategory)
    }

    suspend fun refresh() {
        logs = repository.logs()
        settings = settingsRepository.settings()
        settingsLoaded = true
        crew = crewStore.people().filterNot { it.isOwnProfile(settings) || it.isLegacyLocalOnlyFriend() }
        categories = categoryStore.categories()
        if (selectedCategory != null && categories.none { it == selectedCategory }) {
            selectedCategory = null
        }
    }

    suspend fun importImage(uri: Uri) {
        processing = true
        error = null
        runCatching {
            val monthKey = YearMonth.now().toString()
            val monthCount = if (settings.backgroundRemovalMonth == monthKey) settings.backgroundRemovalsThisMonth else 0
            if (!settings.plusUnlocked && !settings.proActive && monthCount >= FREE_BACKGROUND_REMOVALS_PER_MONTH) {
                error = "Free includes $FREE_BACKGROUND_REMOVALS_PER_MONTH background removals each month. Unlock Nibbl Plus or Pro for more."
                section = AppSection.Settings
                return
            }
            val original = context.contentResolver.openInputStream(uri)?.use { input ->
                repository.saveBytes(input.readBytes(), ".jpg")
            } ?: error("Could not open selected image")
            processingPreviewPath = original
            val cutout = repository.saveBytes(remover.removeToPngBytes(uri), ".png")
            settings = settingsRepository.recordBackgroundRemoval(monthKey)
            pending = PendingLog(original, cutout, locationHelper.currentCafeHint())
        }.onFailure {
            error = it.message ?: "Could not process the image"
        }
        processing = false
        processingPreviewPath = null
    }

    suspend fun ensureRegisteredSettings(): AppSettings {
        val registered = BackendDeviceClient.ensureRegistered(settingsRepository, settings)
        if (registered != settings) settings = registered
        return registered
    }

    suspend fun submitLog(log: FoodLog) {
        val registered = ensureRegisteredSettings()
        backendDrinkReporter.submit(registered.shareHost, log, registered)
    }

    suspend fun createPublicDayShare(date: LocalDate): String? {
        val registered = ensureRegisteredSettings()
        repository.logsForDate(repository.logs(), date).forEach { log ->
            backendDrinkReporter.submit(registered.shareHost, log, registered)
        }
        return BackendShareClient.createDayShare(settingsRepository, registered, date).also {
            if (it == null) {
                error = "Could not create a public day link. Check your server connection and try again."
                Toast.makeText(context, error, Toast.LENGTH_LONG).show()
            } else {
                error = null
            }
        }
    }

    suspend fun addFriendFromInviteOrTag(input: String) {
        val resolved = BackendFriendTagChecker.resolve(settings.shareHost, input)
        if (resolved == null) {
            friendMessage = "Friend doesn't exist yet. Check the username or ask them to open Nibbl first."
            return
        }
        if (resolved.tag == settings.username) {
            friendMessage = "Your profile is edited in Settings, not Friends."
            return
        }
        crewStore.upsertInvite(resolved.displayName, resolved.tag, resolved.avatarUrl)
        friendMessage = "Friend added."
        refresh()
    }

    LaunchedEffect(friendMessage) {
        if (friendMessage != null) {
            delay(3_500)
            friendMessage = null
        }
    }

    suspend fun repeatLog(log: FoodLog) {
        val repeated = log.copy(
            id = UUID.randomUUID().toString(),
            timestamp = selectedDate.atTime(LocalTime.now()).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        )
        repository.save(repeated)
        submitLog(repeated)
        refresh()
        mode = CalendarMode.Day
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch { importImage(uri) }
    }
    val takePictureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = cameraUri
        if (saved && uri != null) scope.launch { importImage(uri) }
    }
    val avatarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val person = pendingAvatarPerson
        pendingAvatarPerson = null
        if (uri != null && person != null) {
            scope.launch {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("Could not open selected image")
                    val ext = uri.lastPathSegment
                        ?.substringAfterLast('.', "")
                        ?.takeIf { it.isNotBlank() && it.length <= 5 }
                        ?: "jpg"
                    val suffix = ".$ext"
                    crewStore.saveAvatarImage(person.id, bytes, suffix)
                    refresh()
                }.onFailure {
                    error = it.message ?: "Could not save friend photo"
                }
            }
        }
    }
    val profilePhotoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("Could not open selected image")
                    val ext = uri.lastPathSegment
                        ?.substringAfterLast('.', "")
                        ?.takeIf { it.isNotBlank() && it.length <= 5 }
                        ?: "jpg"
                    settings = settingsRepository.saveProfileImage(bytes, ".$ext")
                    settings = ensureRegisteredSettings()
                    BackendFriendTagChecker.updateOwnerProfile(settings.shareHost, settings)
                }.onFailure {
                    error = it.message ?: "Could not save profile photo"
                }
            }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions[Manifest.permission.CAMERA] == true) {
            scope.launch {
                val uri = repository.newCameraUri()
                cameraUri = uri
                takePictureLauncher.launch(uri)
            }
        }
    }
    val locationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    LaunchedEffect(Unit) {
        refresh()
        billingState = billingRepository.loadProducts()
        locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    LaunchedEffect(deepLinkUrl) {
        val url = deepLinkUrl?.takeIf { it.isNotBlank() && it != handledDeepLinkUrl } ?: return@LaunchedEffect
        handledDeepLinkUrl = url
        ShareLinkTokenHelper.parseCrewInviteUrl(url)?.let { invite ->
            addFriendFromInviteOrTag(url)
            section = AppSection.Crew
        }
        ShareLinkTokenHelper.parseDayUrl(url)?.let { invite ->
            selectedDate = invite.date
            mode = CalendarMode.Day
            section = AppSection.Diary
        }
    }

    LaunchedEffect(sharedImageUri) {
        val uri = sharedImageUri ?: return@LaunchedEffect
        val uriKey = uri.toString()
        if (uriKey == handledSharedImageUri) return@LaunchedEffect
        handledSharedImageUri = uriKey
        selectedDate = LocalDate.now()
        mode = CalendarMode.Day
        section = AppSection.Diary
        importImage(uri)
    }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)) {
                CuteNavItem(
                    selected = section == AppSection.Diary,
                    onClick = { section = AppSection.Diary },
                    icon = { Icon(Icons.Rounded.CalendarMonth, contentDescription = null) },
                    label = "Diary",
                )
                CuteNavItem(
                    selected = section == AppSection.Crew,
                    onClick = { section = AppSection.Crew },
                    icon = { Icon(Icons.Rounded.Group, contentDescription = null) },
                    label = "Friends",
                )
                CuteNavItem(
                    selected = section == AppSection.Settings,
                    onClick = { section = AppSection.Settings },
                    icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                    label = "Settings",
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
                        )
                    )
                ),
        ) {
            CuteMotionBackground()
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            ) {
                when (section) {
                    AppSection.Diary -> {
                        Header(
                            date = selectedDate,
                            mode = mode,
                            displayName = settings.displayName,
                            onPrevious = { selectedDate = selectedDate.shift(mode, -1) },
                            onNext = { selectedDate = selectedDate.shift(mode, 1) },
                        )
                        Spacer(Modifier.height(10.dp))
                        ModePicker(mode = mode, onMode = { mode = it })
                        Spacer(Modifier.height(8.dp))
                        DiaryPulse(selectedDate, mode, filteredLogs, repository)
                        if (selectedFriend != null || selectedCategory != null) {
                            Spacer(Modifier.height(8.dp))
                            ActiveFilters(
                                selectedFriend = selectedFriend,
                                selectedCategory = selectedCategory,
                                onClearFriend = { selectedFriend = null },
                                onClearCategory = { selectedCategory = null },
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        error?.let {
                            Text(it, color = MaterialTheme.colorScheme.secondary)
                            Spacer(Modifier.height(8.dp))
                        }
                        Box(Modifier.weight(1f)) {
                            when (mode) {
                                CalendarMode.Month -> MonthCalendar(
                                    selectedDate = selectedDate,
                                    logs = filteredLogs,
                                    repository = repository,
                                    weekStartsOnMonday = settings.weekStartsOnMonday,
                                    onDate = { selectedDate = it; mode = CalendarMode.Day },
                                )
                                CalendarMode.Week -> WeekCalendar(
                                    selectedDate = selectedDate,
                                    logs = filteredLogs,
                                    repository = repository,
                                    weekStartsOnMonday = settings.weekStartsOnMonday,
                                    onDate = { selectedDate = it; mode = CalendarMode.Day },
                                )
                                CalendarMode.Day -> DayLog(
                                    date = selectedDate,
                                    logs = repository.logsForDate(filteredLogs, selectedDate),
                                    hasAnyLogs = logs.isNotEmpty(),
                                    hasActiveFilters = selectedFriend != null || selectedCategory != null,
                                    onClearFilters = {
                                        selectedFriend = null
                                        selectedCategory = null
                                    },
                                    onDetails = { detailLog = it },
                                    onRepeat = { log -> scope.launch { repeatLog(log) } },
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        AddLogBar(
                            processing = processing,
                            onGallery = { galleryLauncher.launch("image/*") },
                            onCamera = { permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA)) },
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    AppSection.Crew -> CrewScreen(
                        crew = crew,
                        message = friendMessage,
                        onAdd = { name ->
                            scope.launch {
                                addFriendFromInviteOrTag(name)
                            }
                        },
                        onToggleFavorite = { person ->
                            scope.launch {
                                crewStore.update(person.copy(isFavorite = !person.isFavorite))
                                refresh()
                            }
                        },
                        onPhoto = { person ->
                            pendingAvatarPerson = person
                            avatarLauncher.launch("image/*")
                        },
                        onEdit = { person ->
                            editFriend = person
                        },
                        onDelete = { person ->
                            scope.launch {
                                crewStore.delete(person.id)
                                refresh()
                            }
                        },
                    )
                    AppSection.Settings -> SettingsScreen(
                        settings = settings,
                        billingState = billingState,
                        billingMessage = billingMessage,
                        selectedDate = selectedDate,
                        categories = categories,
                        onSettings = { next ->
                            scope.launch {
                                settings = settingsRepository.save(next)
                                settings = ensureRegisteredSettings()
                                BackendFriendTagChecker.updateOwnerProfile(settings.shareHost, settings)
                            }
                        },
                        onProfilePhoto = { profilePhotoLauncher.launch("image/*") },
                        onAddCategory = { label ->
                            scope.launch {
                                val customCount = categories.count { !it.builtIn }
                                if (!settings.plusUnlocked && !settings.proActive && customCount >= FREE_CUSTOM_CATEGORY_LIMIT) {
                                    error = "Free includes $FREE_CUSTOM_CATEGORY_LIMIT custom food + drink types. Unlock Nibbl Plus for unlimited types."
                                    section = AppSection.Settings
                                } else {
                                    categories = categoryStore.add(label)
                                }
                            }
                        },
                        onDeleteCategory = { category ->
                            scope.launch {
                                categories = categoryStore.delete(category.id)
                                if (selectedCategory == category) selectedCategory = null
                            }
                        },
                        onShareDay = {
                            shareLink = createPublicDayShare(selectedDate)
                        },
                        onShareProfile = {
                            shareLink = ShareLinkTokenHelper.createProfileInviteUrl(settings)
                        },
                        onBuyProduct = { productId ->
                            scope.launch {
                                billingMessage = billingRepository.launchPurchase(context as Activity, productId)
                            }
                        },
                        onRestorePurchases = {
                            scope.launch {
                                settings = billingRepository.restorePurchases()
                                billingState = billingRepository.loadProducts()
                                billingMessage = "Purchases checked."
                            }
                        },
                    )
                }
            }
            AnimatedVisibility(
                visible = processing,
                enter = fadeIn() + scaleIn(initialScale = 0.92f),
                exit = fadeOut() + scaleOut(targetScale = 0.92f),
                modifier = Modifier.align(Alignment.Center),
            ) {
                ProcessingOverlay(originalPath = processingPreviewPath)
            }
        }
    }

    pending?.let { item ->
        EntryDialog(
            pendingLog = item,
            onDismiss = { pending = null },
            onSave = { title, category, caffeine, cafe, place, friends ->
                scope.launch {
                    val log = FoodLog(
                        id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        imagePath = item.cutoutPath,
                        originalImagePath = item.originalPath,
                        title = title,
                        category = category,
                        caffeineMg = caffeine,
                        cafe = cafe,
                        locationName = place,
                        latitude = item.location.latitude,
                        longitude = item.location.longitude,
                        friendNames = friends,
                    )
                    repository.save(log)
                    submitLog(log)
                    selectedDate = LocalDate.now()
                    pending = null
                    refresh()
                }
            },
            crewNames = crew.map { it.displayName },
            categories = categories,
        )
    }

    detailLog?.let { log ->
        LogDetailsDialog(
            log = log,
            onDismiss = { detailLog = null },
            onEdit = {
                detailLog = null
                editLog = log
            },
            onShare = {
                scope.launch {
                    shareLink = createPublicDayShare(selectedDate)
                    detailLog = null
                }
            },
            onDelete = {
                scope.launch {
                    repository.delete(log.id)
                    detailLog = null
                    refresh()
                }
            },
        )
    }

    editLog?.let { log ->
        LogEditDialog(
            log = log,
            crewNames = crew.map { it.displayName },
            categories = categories,
            onDismiss = { editLog = null },
            onSave = { updated ->
                scope.launch {
                    repository.update(updated)
                    editLog = null
                    refresh()
                }
            },
        )
    }

    editFriend?.let { person ->
        FriendEditDialog(
            person = person,
            existingTags = crew.filterNot { it.id == person.id }.map { it.inviteCode }.toSet(),
            shareHost = settings.shareHost,
            onDismiss = { editFriend = null },
            onSave = { updated ->
                scope.launch {
                    crewStore.update(updated)
                    editFriend = null
                    refresh()
                }
            },
        )
    }

    shareLink?.let { url ->
        ShareLinkDialog(url = url, onDismiss = { shareLink = null })
    }

    if (settingsLoaded && !settings.hasSeenOnboarding) {
        OnboardingDialog(
            onStart = {
                scope.launch {
                    settings = settingsRepository.save(settings.copy(hasSeenOnboarding = true))
                }
            },
        )
    }
}

@Composable
private fun OnboardingDialog(onStart: () -> Unit) {
    AlertDialog(
        onDismissRequest = onStart,
        title = { Text("Let's get started") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Nibbl saves food and drink photos into a calendar diary.")
                OnboardingRow("1", "Add a photo from your album or camera.")
                OnboardingRow("2", "Nibbl removes the background and keeps the cutout in your day.")
                OnboardingRow("3", "Tag friends, cafes, caffeine, and custom food or drink types.")
            }
        },
        confirmButton = {
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                Text("Start my diary")
            }
        },
    )
}

@Composable
private fun OnboardingRow(number: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Text(number, modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp), fontWeight = FontWeight.Black)
        }
        Text(text, color = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun RowScope.CuteNavItem(selected: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit, label: String) {
    val transition = rememberInfiniteTransition(label = "nav-$label")
    val bounce by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse),
        label = "navBounce",
    )
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = {
            Box(
                modifier = Modifier.graphicsLayer(
                    scaleX = if (selected) bounce else 1f,
                    scaleY = if (selected) bounce else 1f,
                    rotationZ = if (selected) (bounce - 1f) * 14f else 0f,
                ),
            ) { icon() }
        },
        label = { Text(label) },
    )
}

@Composable
private fun CuteMotionBackground() {
    val transition = rememberInfiniteTransition(label = "cute-background")
    val floatA by transition.animateFloat(
        initialValue = -18f,
        targetValue = 22f,
        animationSpec = infiniteRepeatable(tween(3600), RepeatMode.Reverse),
        label = "floatA",
    )
    val floatB by transition.animateFloat(
        initialValue = 16f,
        targetValue = -24f,
        animationSpec = infiniteRepeatable(tween(4300), RepeatMode.Reverse),
        label = "floatB",
    )
    val twinkle by transition.animateFloat(
        initialValue = 0.34f,
        targetValue = 0.72f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "twinkle",
    )
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .size(150.dp)
                .align(Alignment.TopEnd)
                .graphicsLayer(alpha = 0.2f, translationY = floatA, translationX = -floatA)
                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        )
        Box(
            Modifier
                .size(116.dp)
                .align(Alignment.CenterStart)
                .graphicsLayer(alpha = 0.18f, translationY = floatB)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        )
        Icon(
            Icons.Rounded.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary.copy(alpha = twinkle),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(28.dp)
                .graphicsLayer(rotationZ = floatA)
                .size(24.dp),
        )
    }
}

@Composable
private fun Header(date: LocalDate, mode: CalendarMode, displayName: String, onPrevious: () -> Unit, onNext: () -> Unit) {
    val ownerName = displayName.trim().ifBlank { AppSettings.DEFAULT_DISPLAY_NAME }
    val transition = rememberInfiniteTransition(label = "header")
    val badgeFloat by transition.animateFloat(
        initialValue = -3f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Reverse),
        label = "badgeFloat",
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 1.dp,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Surface(
                modifier = Modifier.graphicsLayer(translationY = badgeFloat, rotationZ = badgeFloat),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
            ) {
                Icon(
                    Icons.Rounded.CalendarMonth,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(9.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(date.headerLabel(mode), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${ownerName}'s diary",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onPrevious, modifier = Modifier.graphicsLayer(translationY = -badgeFloat / 2f)) { Icon(Icons.Rounded.ChevronLeft, contentDescription = "Previous") }
            IconButton(onClick = onNext, modifier = Modifier.graphicsLayer(translationY = badgeFloat / 2f)) { Icon(Icons.Rounded.ChevronRight, contentDescription = "Next") }
        }
    }
}

@Composable
private fun ModePicker(mode: CalendarMode, onMode: (CalendarMode) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            tonalElevation = 1.dp,
        ) {
            Row(
                modifier = Modifier.padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CalendarMode.entries.forEach { calendarMode ->
                    val selected = mode == calendarMode
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        modifier = Modifier.clickable { onMode(calendarMode) },
                    ) {
                        Text(
                            calendarMode.name,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                            fontWeight = if (selected) FontWeight.Black else FontWeight.SemiBold,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiaryPulse(date: LocalDate, mode: CalendarMode, logs: List<FoodLog>, repository: FoodLogRepository) {
    val scopedLogs = when (mode) {
        CalendarMode.Month -> logs.filter { it.loggedDate() in YearMonth.from(date).atDay(1)..YearMonth.from(date).atEndOfMonth() }
        CalendarMode.Week -> {
            val start = date.weekStart(false)
            val end = start.plusDays(6)
            logs.filter { it.loggedDate() in start..end }
        }
        CalendarMode.Day -> repository.logsForDate(logs, date)
    }
    val cafes = scopedLogs.map { it.cafe.trim() }.filter { it.isNotBlank() }.distinct().size
    val friends = scopedLogs.flatMap { it.friendNames }.distinct().size

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.76f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
            Text(
                "${scopedLogs.size} saved",
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.primary,
            )
            Text("•", color = MaterialTheme.colorScheme.secondary)
            Text("$cafes cafes", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.SemiBold)
            Text("•", color = MaterialTheme.colorScheme.secondary)
            Text("$friends friends", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun CrewScreen(
    crew: List<CafeCrewPerson>,
    message: String?,
    onAdd: (String) -> Unit,
    onToggleFavorite: (CafeCrewPerson) -> Unit,
    onPhoto: (CafeCrewPerson) -> Unit,
    onEdit: (CafeCrewPerson) -> Unit,
    onDelete: (CafeCrewPerson) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val sortedCrew = remember(crew) {
        crew.sortedWith(compareByDescending<CafeCrewPerson> { it.isFavorite }.thenBy { it.displayName.lowercase() })
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
                tonalElevation = 1.dp,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.74f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Group, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Friends", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                        Text(
                            if (crew.isEmpty()) "Invite links and public @usernames."
                            else "${crew.size} friend${if (crew.size == 1) "" else "s"} ready to tag.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.Share, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                        Column(Modifier.weight(1f)) {
                            Text("Add a friend", fontWeight = FontWeight.Black)
                            Text("Paste an invite link or @username.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            name,
                            { name = it.take(96) },
                            label = { Text("Invite or @name") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            enabled = name.trim().isNotBlank(),
                            onClick = {
                                val clean = name.trim()
                                if (clean.isNotBlank()) {
                                    onAdd(clean)
                                    name = ""
                                }
                            },
                        ) { Text("Add") }
                    }
                    message?.let {
                        Text(
                            it,
                            color = if (it == "Friend added.") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        if (sortedCrew.isEmpty()) {
            item {
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f)) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
                        Text("No friends yet", fontWeight = FontWeight.Black)
                        Text(
                            "Use a Nibbl invite link to add someone. Your own profile stays in Settings.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.secondary,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
        items(sortedCrew, key = { it.id }) { person ->
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f), tonalElevation = 1.dp) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FriendAvatar(person, 54.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(person.displayName, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Surface(shape = RoundedCornerShape(999.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.64f)) {
                                    Text("@${person.inviteCode}", modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                }
                                if (person.isFavorite) {
                                    Surface(shape = RoundedCornerShape(999.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)) {
                                        Text("Favorite", modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                        IconButton(onClick = { onDelete(person) }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Delete ${person.displayName}")
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = { onToggleFavorite(person) },
                            label = { Text(if (person.isFavorite) "Favorited" else "Favorite") },
                            leadingIcon = { Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        )
                        AssistChip(
                            onClick = { onPhoto(person) },
                            label = { Text(if (person.avatarPath == null && person.remoteAvatarUrl != null) "Override photo" else "Photo") },
                            leadingIcon = { Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        )
                        AssistChip(
                            onClick = { onEdit(person) },
                            label = { Text("Edit") },
                            leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun SettingsScreen(
    settings: AppSettings,
    billingState: BillingUiState,
    billingMessage: String?,
    selectedDate: LocalDate,
    categories: List<DrinkCategory>,
    onSettings: (AppSettings) -> Unit,
    onProfilePhoto: () -> Unit,
    onAddCategory: (String) -> Unit,
    onDeleteCategory: (DrinkCategory) -> Unit,
    onShareDay: suspend () -> Unit,
    onShareProfile: () -> Unit,
    onBuyProduct: (String) -> Unit,
    onRestorePurchases: () -> Unit,
) {
    var displayName by remember(settings.displayName) { mutableStateOf(settings.displayName) }
    var username by remember(settings.username) { mutableStateOf(settings.username) }
    var categoryName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var profileSaving by remember { mutableStateOf(false) }
    var profileError by remember { mutableStateOf<String?>(null) }
    var shareDayCreating by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
            Text("Diary defaults, friends, and sharing.", color = MaterialTheme.colorScheme.secondary)
        }
        item {
            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        ProfileAvatar(settings, 74.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Profile", fontWeight = FontWeight.Black)
                            Text("Your name, photo, and friend tag for invites.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        }
                        TextButton(onClick = onProfilePhoto) {
                            Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Photo")
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Week starts Monday", fontWeight = FontWeight.Bold)
                            Text("Month and week views use Monday as day one.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        }
                        Switch(
                            checked = settings.weekStartsOnMonday,
                            onCheckedChange = { onSettings(settings.copy(weekStartsOnMonday = it)) },
                        )
                    }
                    OutlinedTextField(displayName, { displayName = it.take(48) }, label = { Text("Your display name") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it.toFriendInviteCode() },
                        label = { Text("Public username") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        prefix = { Text("@") },
                        supportingText = { Text("Shown on friend invites and shared days.") },
                    )
                    profileError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (profileSaving) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Button(
                        onClick = {
                            val cleanName = displayName.trim()
                            val cleanUsername = username.toFriendInviteCode()
                            when {
                                cleanName.isBlank() -> profileError = "Display name is required."
                                cleanUsername.isNotBlank() && cleanUsername.length < 3 -> profileError = "Username needs at least 3 characters."
                                else -> {
                                    profileSaving = true
                                    profileError = null
                                    scope.launch {
                                        val available = cleanUsername.isBlank() ||
                                            cleanUsername == settings.username ||
                                            BackendFriendTagChecker.isAvailable(settings.shareHost, cleanUsername)
                                        profileSaving = false
                                        if (available) {
                                            onSettings(settings.copy(displayName = cleanName, username = cleanUsername))
                                        } else {
                                            profileError = "That username is already taken."
                                        }
                                    }
                                }
                            }
                        },
                        enabled = !profileSaving,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (profileSaving) "Checking" else "Save profile") }
                    Button(
                        onClick = onShareProfile,
                        enabled = username.toFriendInviteCode().length >= 3,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Share my profile")
                    }
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.primaryContainer, tonalElevation = 1.dp) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Share a day", fontWeight = FontWeight.Black)
                    Text("Create a public web link for your ${selectedDate.format(DateTimeFormatter.ofPattern("d MMM"))} food and drink photos.", color = MaterialTheme.colorScheme.primary)
                    if (shareDayCreating) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Button(
                        onClick = {
                            scope.launch {
                                shareDayCreating = true
                                onShareDay()
                                shareDayCreating = false
                            }
                        },
                        enabled = !shareDayCreating,
                    ) {
                        Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (shareDayCreating) "Creating link" else "Create public day link")
                    }
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Food + drink types", fontWeight = FontWeight.Black)
                    Text("Add custom types for boba, smoothies, dessert, lunch, or anything else you track.", color = MaterialTheme.colorScheme.secondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = categoryName,
                            onValueChange = { categoryName = it.take(28) },
                            label = { Text("New type") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        Button(
                            onClick = {
                                val clean = categoryName.trim()
                                if (clean.isNotBlank()) {
                                    onAddCategory(clean)
                                    categoryName = ""
                                }
                            },
                        ) {
                            Text("Add")
                        }
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        categories.forEach { category ->
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (category.builtIn) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.clickable { onDeleteCategory(category) },
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        Modifier
                                            .size(14.dp)
                                            .clip(CircleShape)
                                            .background(categoryColor(category))
                                    )
                                    Text(category.label, fontWeight = FontWeight.SemiBold)
                                    Icon(
                                        Icons.Rounded.Close,
                                        contentDescription = "Remove ${category.label}",
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                    Text("Tap any type to remove it. Add the same name again to restore a default type.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
        item {
            MonetizationCard(
                settings = settings,
                customCategoryCount = categories.count { !it.builtIn },
                billingState = billingState,
                billingMessage = billingMessage,
                onBuyProduct = onBuyProduct,
                onRestorePurchases = onRestorePurchases,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun MonetizationCard(
    settings: AppSettings,
    customCategoryCount: Int,
    billingState: BillingUiState,
    billingMessage: String?,
    onBuyProduct: (String) -> Unit,
    onRestorePurchases: () -> Unit,
) {
    val planLabel = when {
        settings.proActive -> "Pro"
        settings.plusUnlocked -> "Plus"
        else -> "Free"
    }
    val remainingRemovals = (FREE_BACKGROUND_REMOVALS_PER_MONTH - settings.backgroundRemovalsThisMonth).coerceAtLeast(0)
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.8f)) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(8.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text("Upgrade Nibbl", fontWeight = FontWeight.Black)
                    Text(
                        if (settings.proActive || settings.plusUnlocked) "Thanks for supporting Nibbl." else "$remainingRemovals removals left this month. $customCategoryCount/$FREE_CUSTOM_CATEGORY_LIMIT custom types.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Surface(shape = RoundedCornerShape(999.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Text(
                        planLabel,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (billingState.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            BillingPlanRow(
                title = "Plus",
                subtitle = "Unlimited logs, custom types, themes, stickers, recap cards",
                fallback = "$4.99 once",
                productId = BillingRepository.NIBBL_PLUS_LIFETIME,
                billingState = billingState,
                badge = "Launch pick",
                highlight = !settings.plusUnlocked && !settings.proActive,
                onBuyProduct = onBuyProduct,
            )
            BillingPlanRow(
                title = "Pro",
                subtitle = "Cloud backup, restore, shared albums, recap exports",
                fallback = "$1.99/mo",
                productId = BillingRepository.NIBBL_PRO_MONTHLY,
                billingState = billingState,
                badge = "Cloud",
                onBuyProduct = onBuyProduct,
            )
            BillingPlanRow(
                title = "Yearly Pro",
                subtitle = "Best value for sync, backups, and friend albums",
                fallback = "$14.99/year",
                productId = BillingRepository.NIBBL_PRO_YEARLY,
                billingState = billingState,
                compact = true,
                onBuyProduct = onBuyProduct,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Free keeps diary, photos, sharing, and basic friends.",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = onRestorePurchases) {
                    Text("Restore")
                }
            }
            val message = billingMessage ?: billingState.message
            if (message != null) {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)) {
                    Text(
                        message,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun BillingPlanRow(
    title: String,
    subtitle: String,
    fallback: String,
    productId: String,
    billingState: BillingUiState,
    badge: String? = null,
    highlight: Boolean = false,
    compact: Boolean = false,
    onBuyProduct: (String) -> Unit,
) {
    val product = billingState.products.firstOrNull { it.productId == productId }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (highlight) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
        tonalElevation = if (highlight) 1.dp else 0.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(if (compact) 10.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (badge != null) {
                        Surface(shape = RoundedCornerShape(999.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)) {
                            Text(
                                badge,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.tertiary,
                                maxLines = 1,
                            )
                        }
                    }
                }
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Button(
                onClick = { onBuyProduct(productId) },
                enabled = product != null && billingState.available && !billingState.loading,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(product?.price ?: fallback, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun AddLogBar(processing: Boolean, onGallery: () -> Unit, onCamera: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)) {
                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(9.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("Add food or drink", fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Pick a photo or take one", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            FilledIconButton(
                onClick = onGallery,
                enabled = !processing,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            ) {
                Icon(Icons.Rounded.PhotoLibrary, contentDescription = "Choose from album")
            }
            FilledIconButton(
                onClick = onCamera,
                enabled = !processing,
            ) {
                Icon(Icons.Rounded.AddAPhoto, contentDescription = "Take photo")
            }
        }
    }
}

@Composable
private fun FriendRail(friends: List<String>, selectedFriend: String?, onFriend: (String?) -> Unit) {
    val visibleFriends = if (friends.isEmpty()) listOf("Me", "Mia", "Lulu") else friends.take(8)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Group, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            Text("Friends", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
            FilterChip(
                selected = selectedFriend == null,
                onClick = { onFriend(null) },
                label = { Text("All") },
            )
            visibleFriends.forEach { friend ->
                FilterChip(
                    selected = selectedFriend == friend,
                    onClick = { onFriend(if (selectedFriend == friend) null else friend) },
                    label = { Text(friend) },
                    leadingIcon = { FriendInitial(friend, 22.dp) },
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun CategoryRail(categories: List<DrinkCategory>, selectedCategory: DrinkCategory?, onCategory: (DrinkCategory?) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FilterChip(
            selected = selectedCategory == null,
            onClick = { onCategory(null) },
            label = { Text("All food + drinks") },
        )
        categories.forEach { category ->
            FilterChip(
                selected = selectedCategory == category,
                onClick = { onCategory(if (selectedCategory == category) null else category) },
                label = { Text(category.label) },
                leadingIcon = {
                    Box(
                        Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(categoryColor(category))
                    )
                },
            )
        }
    }
}

@Composable
private fun ActiveFilters(
    selectedFriend: String?,
    selectedCategory: DrinkCategory?,
    onClearFriend: () -> Unit,
    onClearCategory: () -> Unit,
) {
    if (selectedFriend == null && selectedCategory == null) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        selectedFriend?.let {
            AssistChip(
                onClick = onClearFriend,
                label = { Text("Friend: $it") },
                leadingIcon = { Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(16.dp)) },
            )
        }
        selectedCategory?.let {
            AssistChip(
                onClick = onClearCategory,
                label = { Text("Category: ${it.label}") },
                leadingIcon = { Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(16.dp)) },
            )
        }
    }
}

@Composable
private fun SummaryCard(date: LocalDate, mode: CalendarMode, logs: List<FoodLog>, repository: FoodLogRepository) {
    val scopedLogs = when (mode) {
        CalendarMode.Month -> logs.filter { it.loggedDate() in YearMonth.from(date).atDay(1)..YearMonth.from(date).atEndOfMonth() }
        CalendarMode.Week -> {
            val start = date.weekStart(false)
            val end = start.plusDays(6)
            logs.filter { it.loggedDate() in start..end }
        }
        CalendarMode.Day -> repository.logsForDate(logs, date)
    }
    val totalCaffeine = scopedLogs.mapNotNull { it.caffeineMg }.sum()
    val topCategory = scopedLogs.groupingBy { it.category }.eachCount().maxByOrNull { it.value }?.key?.label ?: "None"
    val friends = scopedLogs.flatMap { it.friendNames }.distinct().size

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Rounded.QueryStats, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            SummaryMetric("Entries", scopedLogs.size.toString(), Modifier.weight(1f))
            VerticalDivider(Modifier.height(34.dp))
            SummaryMetric("Caffeine", "${totalCaffeine}mg", Modifier.weight(1f))
            VerticalDivider(Modifier.height(34.dp))
            SummaryMetric(if (friends == 1) "Friend" else "Friends", friends.toString(), Modifier.weight(1f))
            VerticalDivider(Modifier.height(34.dp))
            SummaryMetric("Top", topCategory, Modifier.weight(1.2f))
        }
    }
}

@Composable
private fun SummaryMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
        Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun CafeCrewPerson.isOwnProfile(settings: AppSettings): Boolean =
    displayName.trim().equals(settings.displayName.trim(), ignoreCase = true) ||
        inviteCode.isNotBlank() && inviteCode == settings.username

private fun CafeCrewPerson.isLegacyLocalOnlyFriend(): Boolean =
    inviteCode == id.toFriendInviteCode()

@Composable
private fun FriendInitial(name: String, size: Dp) {
    val colors = listOf(
        MaterialTheme.colorScheme.secondaryContainer,
        MaterialTheme.colorScheme.tertiaryContainer,
        MaterialTheme.colorScheme.primaryContainer,
        Color(0xFFD8EFF1),
    )
    val color = colors[Math.floorMod(name.hashCode(), colors.size)]
    Surface(modifier = Modifier.size(size), shape = CircleShape, color = color) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                name.firstOrNull()?.uppercaseChar()?.toString().orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Black,
                color = Color(0xFF322D2A),
            )
        }
    }
}

@Composable
private fun FriendAvatar(person: CafeCrewPerson, size: Dp) {
    val avatarFile = person.avatarPath?.let(::File)?.takeIf { it.isFile }
    val avatarSource: Any? = avatarFile ?: person.remoteAvatarUrl?.takeIf { it.isNotBlank() }
    if (avatarSource == null) {
        FriendInitial(person.displayName, size)
        return
    }

    Image(
        painter = rememberAsyncImagePainter(avatarSource),
        contentDescription = "${person.displayName} profile photo",
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
    )
}

@Composable
private fun ProfileAvatar(settings: AppSettings, size: Dp) {
    val avatarFile = settings.profileImagePath?.let(::File)?.takeIf { it.isFile }
    if (avatarFile == null) {
        FriendInitial(settings.displayName, size)
        return
    }

    Image(
        painter = rememberAsyncImagePainter(avatarFile),
        contentDescription = "${settings.displayName} profile photo",
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
    )
}

@Composable
private fun ProcessingOverlay(originalPath: String?) {
    val transition = rememberInfiniteTransition(label = "cutout magic")
    val pulse by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(animation = tween(850), repeatMode = RepeatMode.Reverse),
        label = "pulse",
    )
    val spin by transition.animateFloat(
        initialValue = -10f,
        targetValue = 10f,
        animationSpec = infiniteRepeatable(animation = tween(950), repeatMode = RepeatMode.Reverse),
        label = "spin",
    )
    val sparkle by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(700), repeatMode = RepeatMode.Reverse),
        label = "sparkle",
    )
    val reveal by transition.animateFloat(
        initialValue = 0.18f,
        targetValue = 0.86f,
        animationSpec = infiniteRepeatable(animation = tween(1250), repeatMode = RepeatMode.Reverse),
        label = "background peel",
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .border(2.dp, MaterialTheme.colorScheme.tertiary, RoundedCornerShape(28.dp)),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
    ) {
        Box(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.primaryContainer,
                        )
                    )
                )
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .graphicsLayer(alpha = sparkle, rotationZ = spin)
                    .size(34.dp),
            )
            Icon(
                Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .graphicsLayer(alpha = sparkle, rotationZ = -spin)
                    .size(28.dp),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(178.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (originalPath != null) {
                            Image(
                                painter = rememberAsyncImagePainter(File(originalPath)),
                                contentDescription = "Original photo while background is removed",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.White.copy(alpha = 0.34f)),
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .fillMaxWidth(reveal)
                                    .height(178.dp)
                                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)),
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .fillMaxWidth(reveal)
                                    .height(178.dp)
                                    .border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(22.dp)),
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(96.dp)
                                    .graphicsLayer(scaleX = pulse, scaleY = pulse, rotationZ = spin),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Rounded.LocalCafe, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                            }
                        }
                        Icon(
                            Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(12.dp)
                                .graphicsLayer(alpha = sparkle, rotationZ = spin)
                                .size(30.dp),
                        )
                    }
                }
                Text("Peeling off the background", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
                Text(
                    "Nibbl is finding the food or drink, then saving a clean cutout.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun MonthCalendar(
    selectedDate: LocalDate,
    logs: List<FoodLog>,
    repository: FoodLogRepository,
    weekStartsOnMonday: Boolean,
    onDate: (LocalDate) -> Unit,
) {
    val month = YearMonth.from(selectedDate)
    val first = month.atDay(1)
    val leading = if (weekStartsOnMonday) first.dayOfWeek.value - 1 else first.dayOfWeek.value % 7
    val monthDays = (0 until leading).map { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
    val days = monthDays + List((7 - monthDays.size % 7) % 7) { null }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            weekLabels(weekStartsOnMonday).forEach {
                Text(it, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        days.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                week.forEach { day ->
                    DayCell(day, selectedDate, if (day == null) emptyList() else repository.logsForDate(logs, day), onDate, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun WeekCalendar(
    selectedDate: LocalDate,
    logs: List<FoodLog>,
    repository: FoodLogRepository,
    weekStartsOnMonday: Boolean,
    onDate: (LocalDate) -> Unit,
) {
    val start = selectedDate.weekStart(weekStartsOnMonday)
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items((0..6).map { start.plusDays(it.toLong()) }) { day ->
            WeekDrinkCard(day, repository.logsForDate(logs, day), isSelected = day == selectedDate, onDate = onDate)
        }
    }
}

@Composable
private fun WeekDrinkCard(day: LocalDate, logs: List<FoodLog>, isSelected: Boolean, onDate: (LocalDate) -> Unit) {
    val hero = logs.lastOrNull()
    val transition = rememberInfiniteTransition(label = "week-card")
    val lift by transition.animateFloat(
        initialValue = -2f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(1700), RepeatMode.Reverse),
        label = "weekLift",
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer(
                translationY = if (isSelected) lift else 0f,
                scaleX = if (isSelected) 1.015f else 1f,
                scaleY = if (isSelected) 1.015f else 1f,
            )
            .clickable { onDate(day) },
        shape = RoundedCornerShape(24.dp),
        color = if (isSelected) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        tonalElevation = 2.dp,
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(shape = RoundedCornerShape(20.dp), color = categoryColor(hero?.category ?: DrinkCategory.Drink)) {
                Box(Modifier.size(102.dp), contentAlignment = Alignment.Center) {
                    if (hero != null) {
                        Image(
                            painter = rememberAsyncImagePainter(File(hero.imagePath)),
                            contentDescription = hero.title,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        Icon(Icons.Rounded.LocalCafe, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(42.dp))
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(day.format(DateTimeFormatter.ofPattern("EEE d")), fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
                Text(hero?.title?.ifBlank { hero.category.label } ?: "No food or drinks yet", maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (hero != null) {
                    Text(hero.cafe.ifBlank { "Cafe not set" }, color = MaterialTheme.colorScheme.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                        hero.friendNames.take(4).forEach { FriendInitial(it, 22.dp) }
                        if (logs.size > 1) Text("+${logs.size - 1} more", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Text("Add a drink, snack, or cafe treat.", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun DayCell(day: LocalDate?, selectedDate: LocalDate, logs: List<FoodLog>, onDate: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    val isToday = day == LocalDate.now()
    val isSelected = day == selectedDate
    val transition = rememberInfiniteTransition(label = "day-cell")
    val wiggle by transition.animateFloat(
        initialValue = -1.2f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Reverse),
        label = "cellWiggle",
    )
    val cellColor = when {
        isSelected -> MaterialTheme.colorScheme.tertiaryContainer
        isToday -> MaterialTheme.colorScheme.primaryContainer
        logs.isNotEmpty() -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
        else -> MaterialTheme.colorScheme.surface
    }
    Surface(
        modifier = modifier
            .aspectRatio(0.74f)
            .graphicsLayer(
                scaleX = if (isSelected) 1.03f else 1f,
                scaleY = if (isSelected) 1.03f else 1f,
                rotationZ = if (logs.isNotEmpty() && !isSelected) wiggle else 0f,
            )
            .clip(RoundedCornerShape(14.dp))
            .border(
                width = if (isToday || isSelected) 2.dp else 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(14.dp),
            )
            .then(if (day != null) Modifier.clickable { onDate(day) } else Modifier),
        color = cellColor,
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(day?.dayOfMonth?.toString().orEmpty(), fontWeight = FontWeight.Black)
                Spacer(Modifier.weight(1f))
                if (logs.isNotEmpty()) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)) {
                        Text(
                            logs.size.toString(),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                logs.take(4).forEach {
                    Image(
                        painter = rememberAsyncImagePainter(File(it.imagePath)),
                        contentDescription = it.title,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.background),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
            if (logs.any { it.friendNames.isNotEmpty() }) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    logs.flatMap { it.friendNames }.distinct().take(3).forEach {
                        FriendInitial(it, 18.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun DayLog(
    date: LocalDate,
    logs: List<FoodLog>,
    hasAnyLogs: Boolean,
    hasActiveFilters: Boolean,
    onClearFilters: () -> Unit,
    onDetails: (FoodLog) -> Unit,
    onRepeat: (FoodLog) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM")), style = MaterialTheme.typography.titleMedium)
        }
        if (logs.isEmpty()) {
            item {
                EmptyDayState(
                    hasAnyLogs = hasAnyLogs,
                    hasActiveFilters = hasActiveFilters,
                    onClearFilters = onClearFilters,
                )
            }
        }
        items(logs, key = { it.id }) { log ->
            val transition = rememberInfiniteTransition(label = "log-${log.id}")
            val float by transition.animateFloat(
                initialValue = -1f,
                targetValue = 2f,
                animationSpec = infiniteRepeatable(tween(2200), RepeatMode.Reverse),
                label = "logFloat",
            )
            Surface(
                modifier = Modifier.graphicsLayer(translationY = float),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
            ) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = rememberAsyncImagePainter(File(log.imagePath)),
                        contentDescription = log.title,
                        modifier = Modifier
                            .size(78.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentScale = ContentScale.Fit,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(log.title.ifBlank { log.category.label }, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = CircleShape, color = categoryColor(log.category)) {
                                Text(
                                    log.category.label,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF322D2A),
                                )
                            }
                            log.caffeineMg?.let {
                                Text("${it}mg caffeine", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.LocalCafe, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(log.cafe.ifBlank { "Cafe not set" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (log.locationName.isNotBlank()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Place, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(log.locationName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (log.friendNames.isNotEmpty()) {
                            Row(
                                modifier = Modifier.padding(top = 5.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.Group, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
                                log.friendNames.take(4).forEach { FriendInitial(it, 22.dp) }
                                Text(log.friendNames.joinToString(", "), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        Row(
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AssistChip(onClick = { onDetails(log) }, label = { Text("Details") })
                            AssistChip(
                                onClick = { onRepeat(log) },
                                label = { Text("Log again") },
                                leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyDayState(hasAnyLogs: Boolean, hasActiveFilters: Boolean, onClearFilters: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(Icons.Rounded.LocalCafe, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp))
            }
            Text(
                when {
                    hasActiveFilters -> "No matching treats yet"
                    hasAnyLogs -> "Nothing logged on this day"
                    else -> "Your diary is ready"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
            )
            Text(
                when {
                    hasActiveFilters -> "Clear the filters or add a new photo for this friend or category."
                    hasAnyLogs -> "Use the camera or gallery button to add something delicious here."
                    else -> "Start with the camera or gallery button and your food cutouts will appear here."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            if (hasActiveFilters) {
                TextButton(onClick = onClearFilters) {
                    Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Clear filters")
                }
            }
        }
    }
}

@Composable
private fun LogDetailsDialog(log: FoodLog, onDismiss: () -> Unit, onEdit: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    var confirmingDelete by remember(log.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(log.title.ifBlank { log.category.label }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Image(
                    painter = rememberAsyncImagePainter(File(log.originalImagePath.ifBlank { log.imagePath })),
                    contentDescription = log.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.background),
                    contentScale = ContentScale.Fit,
                )
                DetailRow("Category", log.category.label)
                DetailRow("Cafe", log.cafe.ifBlank { "Not set" })
                DetailRow("Location", log.locationName.ifBlank { "Not set" })
                DetailRow("Friends", log.friendNames.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "Solo")
                DetailRow("Caffeine", log.caffeineMg?.let { "${it}mg" } ?: "Not set")
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onEdit) {
                    Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Edit")
                }
                TextButton(onClick = onShare) {
                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Share")
                }
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        },
        dismissButton = {
            TextButton(onClick = { confirmingDelete = true }) {
                Text("Delete", color = MaterialTheme.colorScheme.secondary)
            }
        },
    )
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete this log?") },
            text = { Text("This removes the saved food or drink photo from your diary.") },
            confirmButton = {
                TextButton(onClick = onDelete) {
                    Text("Delete", color = MaterialTheme.colorScheme.secondary)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.width(82.dp), color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
        Text(value, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LogEditDialog(
    log: FoodLog,
    crewNames: List<String>,
    categories: List<DrinkCategory>,
    onDismiss: () -> Unit,
    onSave: (FoodLog) -> Unit,
) {
    var title by remember(log.id) { mutableStateOf(log.title) }
    var category by remember(log.id) { mutableStateOf(log.category) }
    var caffeine by remember(log.id) { mutableStateOf(log.caffeineMg?.toString().orEmpty()) }
    var cafe by remember(log.id) { mutableStateOf(log.cafe) }
    var place by remember(log.id) { mutableStateOf(log.locationName) }
    var selectedFriends by remember(log.id) { mutableStateOf(log.friendNames.ifEmpty { listOf("Me") }) }
    val friendSuggestions = (listOf("Me") + crewNames + selectedFriends).distinct()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit log") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PreviewImageTile("Cutout", log.imagePath, Modifier.fillMaxWidth(), ContentScale.Fit)
                OutlinedTextField(title, { title = it }, label = { Text("Drink or food") }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    categories.forEach {
                        FilterChip(selected = category == it, onClick = { category = it }, label = { Text(it.label) })
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    friendSuggestions.forEach { friend ->
                        FilterChip(
                            selected = selectedFriends.contains(friend),
                            onClick = {
                                selectedFriends = if (selectedFriends.contains(friend)) selectedFriends - friend else selectedFriends + friend
                            },
                            label = { Text(friend) },
                            leadingIcon = { FriendInitial(friend, 20.dp) },
                        )
                    }
                }
                Text("Add new friends from the Friends tab first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                OutlinedTextField(caffeine, { value -> caffeine = value.filter(Char::isDigit).take(4) }, label = { Text("Caffeine mg") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(cafe, { cafe = it }, label = { Text("Cafe") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(place, { place = it }, label = { Text("Location") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    log.copy(
                        title = title.trim(),
                        category = category,
                        caffeineMg = caffeine.toIntOrNull(),
                        cafe = cafe.trim(),
                        locationName = place.trim(),
                        friendNames = selectedFriends.distinct(),
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FriendEditDialog(
    person: CafeCrewPerson,
    existingTags: Set<String>,
    shareHost: String,
    onDismiss: () -> Unit,
    onSave: (CafeCrewPerson) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember(person.id) { mutableStateOf(person.displayName) }
    var tag by remember(person.id) { mutableStateOf(person.inviteCode) }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val cleanTag = tag.toFriendInviteCode()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit friend") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(48) },
                    label = { Text("Friend name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = tag,
                    onValueChange = { tag = it.toFriendInviteCode() },
                    label = { Text("Friend tag") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    prefix = { Text("@") },
                    supportingText = { Text("3-10 letters or numbers. Used in invite links.") },
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (checking) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(
                enabled = !checking,
                onClick = {
                    val cleanName = name.trim()
                    when {
                        cleanName.isBlank() -> error = "Name is required."
                        cleanTag.length < 3 -> error = "Tag needs at least 3 characters."
                        existingTags.contains(cleanTag) -> error = "That tag is already used on this device."
                        else -> {
                            checking = true
                            error = null
                            scope.launch {
                                val available = BackendFriendTagChecker.isAvailable(shareHost, cleanTag)
                                checking = false
                                if (available || cleanTag == person.inviteCode) {
                                    onSave(person.copy(displayName = cleanName, inviteCode = cleanTag))
                                } else {
                                    error = "That tag is already taken."
                                }
                            }
                        }
                    }
                },
            ) {
                Text(if (checking) "Checking" else "Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ShareLinkDialog(url: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val isDayInvite = url.contains("?s=") || url.contains("&s=") || url.contains("?i=") || url.contains("&i=") || url.contains("/i/")
    val isFriendInvite = !isDayInvite && (url.contains("friend=") || url.contains("crew="))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isFriendInvite) "Share friend link" else "Share public day") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (isFriendInvite) {
                        "Send this to someone so they can add this friend to their Nibbl app."
                    } else {
                        "This is a public web link for this calendar day. Anyone with the link can view the synced food and drink photos for that day."
                    },
                    color = MaterialTheme.colorScheme.secondary,
                )
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            url,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(onClick = {
                            copyInviteLink(context, url)
                            onDismiss()
                        }) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy invite link")
                        }
                    }
                }
                Button(
                    onClick = {
                        copyInviteLink(context, url)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Copy link")
                }
                Button(
                    onClick = {
                        shareInviteLink(context, url)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                ) {
                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Share...")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

private fun copyInviteLink(context: Context, url: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Nibbl invite", url))
    Toast.makeText(context, "Invite copied", Toast.LENGTH_SHORT).show()
}

private fun shareInviteLink(context: Context, url: String) {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
    }
    context.startActivity(Intent.createChooser(sendIntent, "Share Nibbl invite"))
}

@Suppress("DEPRECATION")
private fun Intent.nibblSharedImageUri(): Uri? {
    val isImage = type.orEmpty().startsWith("image/")
    return when {
        action == Intent.ACTION_SEND && isImage ->
            getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        action == Intent.ACTION_SEND_MULTIPLE && isImage ->
            getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.firstOrNull()
        else -> null
    }
}

@Composable
private fun EntryDialog(
    pendingLog: PendingLog,
    onDismiss: () -> Unit,
    onSave: (String, DrinkCategory, Int?, String, String, List<String>) -> Unit,
    crewNames: List<String>,
    categories: List<DrinkCategory>,
) {
    var title by remember { mutableStateOf("Matcha") }
    var category by remember(categories) { mutableStateOf(categories.firstOrNull { it == DrinkCategory.Matcha } ?: categories.firstOrNull() ?: DrinkCategory.Drink) }
    var caffeine by remember { mutableStateOf("") }
    var cafe by remember { mutableStateOf("") }
    var place by remember { mutableStateOf(pendingLog.location.name) }
    var selectedFriends by remember { mutableStateOf(listOf("Me")) }
    val friendSuggestions = (listOf("Me") + crewNames).distinct()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to diary") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BeforeAfterPreview(pendingLog)
                OutlinedTextField(title, { title = it }, label = { Text("Drink or food") }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    categories.forEach {
                        FilterChip(selected = category == it, onClick = { category = it }, label = { Text(it.label) })
                    }
                }
                Text("Friends", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    friendSuggestions.forEach { friend ->
                        FilterChip(
                            selected = selectedFriends.contains(friend),
                            onClick = {
                                selectedFriends = if (selectedFriends.contains(friend)) {
                                    selectedFriends - friend
                                } else {
                                    selectedFriends + friend
                                }
                            },
                            label = { Text(friend) },
                            leadingIcon = { FriendInitial(friend, 20.dp) },
                        )
                    }
                }
                Text("Add new friends from the Friends tab first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                OutlinedTextField(caffeine, { value -> caffeine = value.filter(Char::isDigit).take(4) }, label = { Text("Caffeine mg") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(cafe, { cafe = it }, label = { Text("Cafe") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(place, { place = it }, label = { Text("Location") }, modifier = Modifier.fillMaxWidth())
                if (pendingLog.location.latitude != null) {
                    AssistChip(onClick = {}, label = { Text("Location attached") }, leadingIcon = { Icon(Icons.Rounded.Place, contentDescription = null) })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        title.trim(),
                        category,
                        caffeine.toIntOrNull(),
                        cafe.trim(),
                        place.trim(),
                        selectedFriends.distinct(),
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun BeforeAfterPreview(pendingLog: PendingLog) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PreviewImageTile(
            label = "Before",
            path = pendingLog.originalPath,
            modifier = Modifier.weight(1f),
            contentScale = ContentScale.Crop,
        )
        PreviewImageTile(
            label = "Cutout",
            path = pendingLog.cutoutPath,
            modifier = Modifier.weight(1f),
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
private fun PreviewImageTile(label: String, path: String, modifier: Modifier, contentScale: ContentScale) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
        Image(
            painter = rememberAsyncImagePainter(File(path)),
            contentDescription = label,
            modifier = Modifier
                .fillMaxWidth()
                .height(128.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.surface,
                        )
                    )
                )
                .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)),
            contentScale = contentScale,
        )
    }
}

private fun LocalDate.shift(mode: CalendarMode, amount: Long): LocalDate = when (mode) {
    CalendarMode.Month -> plusMonths(amount)
    CalendarMode.Week -> plusWeeks(amount)
    CalendarMode.Day -> plusDays(amount)
}

private fun LocalDate.headerLabel(mode: CalendarMode): String = when (mode) {
    CalendarMode.Month -> format(DateTimeFormatter.ofPattern("MMMM yyyy"))
    CalendarMode.Week -> {
        val start = weekStart(false)
        val end = start.plusDays(6)
        "${start.format(DateTimeFormatter.ofPattern("d MMM"))} - ${end.format(DateTimeFormatter.ofPattern("d MMM yyyy"))}"
    }
    CalendarMode.Day -> format(DateTimeFormatter.ofPattern("EEEE, d MMM yyyy"))
}

private fun LocalDate.weekStart(weekStartsOnMonday: Boolean): LocalDate =
    minusDays(if (weekStartsOnMonday) (dayOfWeek.value - 1).toLong() else (dayOfWeek.value % 7).toLong())

private fun weekLabels(weekStartsOnMonday: Boolean): List<String> =
    if (weekStartsOnMonday) listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    else listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

private fun FoodLog.loggedDate(): LocalDate =
    Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()

private fun categoryColor(category: DrinkCategory): Color = Color(category.colorArgb)
