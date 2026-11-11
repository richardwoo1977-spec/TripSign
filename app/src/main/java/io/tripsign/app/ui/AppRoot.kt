// ver 1.8.0 | 2026-11-07 13:25
package io.tripsign.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import io.tripsign.app.core.Analytics
import io.tripsign.app.core.AuthProof
import io.tripsign.app.core.Chain
import io.tripsign.app.core.DemoData
import io.tripsign.app.core.Driver
import io.tripsign.app.core.FiscalYear
import io.tripsign.app.core.Gear
import io.tripsign.app.core.LogRow
import io.tripsign.app.core.Logbook
import io.tripsign.app.core.Purpose
import io.tripsign.app.core.RecordType
import io.tripsign.app.core.SealedEntry
import io.tripsign.app.core.TripView
import io.tripsign.app.core.ViewKind
import io.tripsign.app.data.Live
import io.tripsign.app.data.Settings
import io.tripsign.app.data.TripRepository
import io.tripsign.app.data.tr
import io.tripsign.app.service.Notifier
import io.tripsign.app.service.WarnBridge
import kotlinx.coroutines.delay
import java.time.YearMonth
import java.time.ZoneId

/**
 * v0.4: the home is the 운행기록부 itself. The analysis pages (AnalysisScreen, Details) are no longer
 * reachable; their code stays in the project for the expansion plan.
 */
private sealed interface Screen {
    data object Logbook : Screen
    data object Trips : Screen
    data object Prefs : Screen
    data object Export : Screen
    data object Login : Screen
}

@Composable
fun AppRoot(
    repo: TripRepository,
    missingPermissions: List<String>,
    requestingPermissions: Boolean,
    demoScale: Double,
    shot: String?,
    warnOpens: Int = 0,
    onRequestPermissions: () -> Unit,
    onFinishOnboarding: () -> Unit
) {
    // Palette and tr() read these states, so a change recomposes every screen.
    val systemDark = isSystemInDarkTheme()
    SideEffect { Settings.systemDark.value = systemDark }

    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val entries by repo.entries.collectAsState()
    val check by repo.check.collectAsState()
    val live by Live.state.collectAsState()

    var screen by remember { mutableStateOf<Screen>(Screen.Logbook) }
    var demo by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var dismissedArrival by remember { mutableStateOf<String?>(null) }
    var tripFilter by remember { mutableStateOf(TripFilter()) }
    var month by remember { mutableStateOf(YearMonth.now(zone)) }
    var rowFilter by remember { mutableStateOf(RowFilter.ALL) }
    var purposeFor by remember { mutableStateOf<LogRow?>(null) }
    var driverFor by remember { mutableStateOf<LogRow?>(null) }
    var dropPurposes by remember { mutableStateOf(0) }
    var settingsTab by remember { mutableStateOf(0) }   // layout check only: leave the last N sample trips unclassified
    // the title shows once per app start (a configuration change does not show it again)
    var splashDone by rememberSaveable { mutableStateOf(false) }
    var loginStep by remember { mutableStateOf<String?>(null) }   // layout check only
    // 0.5.2
    var warnBanner by remember { mutableStateOf(false) }
    var authFor by remember { mutableStateOf<LogRow?>(null) }
    var adminPick by remember { mutableStateOf(false) }
    var dismissedAuth by remember { mutableStateOf<String?>(null) }
    var unverifiedFor by remember { mutableStateOf<LogRow?>(null) }   // 0.5.2a
    var confirmed by remember { mutableStateOf<Pair<LogRow, Driver>?>(null) }   // 0.5.2g: result of a trip's phone check
    val carId = remember { try { AuthProof.carId(repo.keys.publicKey.encoded) } catch (e: Exception) { "" } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    var handledWarn by remember { mutableIntStateOf(0) }

    val demoMonth = remember { YearMonth.from(DemoData.ANCHOR_DATE) }

    // Layout check (debug builds): every shot id opens one screen with the sample data.
    LaunchedEffect(shot) {
        // example accounts only while a login/user shot is open (a real name never reaches a screenshot)
        ShotAccounts.active.value = shot in setOf("login", "login_pin", "settings_users")
        val id = shot ?: return@LaunchedEffect
        demo = !id.startsWith("settings")
        tripFilter = TripFilter()
        rowFilter = RowFilter.ALL
        purposeFor = null
        driverFor = null
        dropPurposes = 0
        settingsTab = 0
        month = demoMonth
        when (id) {
            "logbook" -> screen = Screen.Logbook
            "logbook_unc" -> { screen = Screen.Logbook; dropPurposes = 3 }
            "logbook_unc_filter" -> { screen = Screen.Logbook; dropPurposes = 3; rowFilter = RowFilter.UNCLASSIFIED }
            "picker_purpose", "picker_driver" -> screen = Screen.Logbook
            "export" -> screen = Screen.Export
            "trips" -> screen = Screen.Trips
            "trips_filter" -> { screen = Screen.Trips; tripFilter = TripFilter(FilterPeriod.MONTH, FilterPurpose.COMMUTE) }
            "trips_search" -> { screen = Screen.Trips; tripFilter = TripFilter(search = "10/30 18시") }
            "settings" -> screen = Screen.Prefs
            "settings_users" -> { screen = Screen.Prefs; settingsTab = 1 }
            "settings_display" -> { screen = Screen.Prefs; settingsTab = 2 }
            "settings_integrity" -> { screen = Screen.Prefs; settingsTab = 3 }
            "settings_perm" -> { screen = Screen.Prefs; settingsTab = 4 }
            "settings_about" -> { screen = Screen.Prefs; settingsTab = 5 }
            "arrival" -> screen = Screen.Logbook
            "login" -> { demo = false; screen = Screen.Login; loginStep = null }
            "login_pin" -> { demo = false; screen = Screen.Login; loginStep = "pin" }
            "signup" -> { demo = false; screen = Screen.Login; loginStep = "signup" }
        }
        splashDone = id != "splash"
    }
    val onboardingShot = when (shot) {
        "title" -> 0
        "intro1" -> 1
        "intro2" -> 2
        "intro3" -> 3
        "permission" -> 4
        else -> null
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            now = System.currentTimeMillis()
        }
    }
    // 0.5.2a: a trip that just ended (or a gear change) re-reads the clock at once. Without this the
    // arrival card waited for the next 30-second tick (now < endMs) and popped up late over the login tiles.
    LaunchedEffect(entries.size, live.parkedIdle) { now = System.currentTimeMillis() }
    // a drive in progress keeps the login; a login left standing in P (nobody drove) ends after 10 min
    val gearKnown = live.gear != Gear.UNKNOWN
    val inP = live.gear == Gear.PARK
    LaunchedEffect(now, inP, gearKnown) {
        if (gearKnown && !inP) Settings.touchSession(now) else Settings.expireIfIdle(context, parked = true, now = now)
    }
    // the login ends when a trip ends: if the car is in P, ask "who is next?" right away
    val loggedIn = Settings.currentDriver.intValue >= 0
    var wasLoggedIn by remember { mutableStateOf(loggedIn) }
    LaunchedEffect(loggedIn) {
        if (wasLoggedIn && !loggedIn && inP && shot == null) screen = Screen.Login
        if (loggedIn) warnBanner = false
        wasLoggedIn = loggedIn
    }

    // 0.5.2: no input of any kind while moving (or while the gear is unknown). Screens that write go away.
    val locked = shot == null && !live.parkedIdle
    LaunchedEffect(locked) {
        if (locked) {
            purposeFor = null
            driverFor = null
            authFor = null
            unverifiedFor = null
            confirmed = null
            if (screen == Screen.Login || screen == Screen.Prefs || screen == Screen.Export) screen = Screen.Logbook
        } else if (!demo && shot == null && Settings.onboarded.value && Settings.currentDriver.intValue < 0 && screen == Screen.Logbook) {
            screen = Screen.Login
        }
    }

    // 0.5.2: the driver warning (ignition, end of a drive). Recorded only when this screen is really in front.
    val warnSignal = warnOpens + live.warnSeq
    LaunchedEffect(warnSignal, resumed) {
        if (resumed && warnSignal > handledWarn) {
            handledWarn = warnSignal
            if (!demo && shot == null && Settings.onboarded.value && Settings.currentDriver.intValue < 0) {
                warnBanner = true
                splashDone = true
                if (!locked) screen = Screen.Login
                repo.appendWarn("screen")
                WarnBridge.markShown()
            }
        }
    }

    val demoEntries = remember(demoScale, dropPurposes) { scaledDemo(DemoData.build(zone), demoScale, dropPurposes) }
    val demoOk = remember(demoEntries) { Chain.verify(demoEntries, DemoData.DemoVerifier).ok }
    val source: List<SealedEntry> = if (demo) demoEntries else entries
    val allRows = remember(source) { Logbook.rows(source) }
    val vehicle = remember(entries) { Logbook.latestVehicle(entries) }

    // month bounds: earliest recorded month .. max(this month, latest recorded month)
    val months = remember(allRows) { Logbook.months(allRows, zone) }
    val nowMonth = if (demo) demoMonth else YearMonth.now(zone)
    val latest = listOfNotNull(months.firstOrNull(), nowMonth).max()
    val earliest = months.lastOrNull() ?: nowMonth
    val shownMonth = month.coerceIn(earliest, latest)
    val monthRows = remember(allRows, shownMonth) { Logbook.month(allRows, shownMonth, zone) }
    val monthTotals = remember(monthRows) { Logbook.totals(monthRows) }
    val fy = FiscalYear.of(shownMonth, Settings.fyStart.intValue)
    val fyTotals = remember(allRows, fy) { Logbook.totals(Logbook.fiscalYear(allRows, fy, zone)) }

    // Trips screen (search) still works on views
    val views = remember(source) { Analytics.buildViews(source) }
    val realViews = remember(entries) { Analytics.buildViews(entries) }
    // 0.5.2g: the 미인증 운행 before this car start, shown once on the login screen
    val realRows = remember(entries) { Logbook.rows(entries) }
    val pendingUnverified = if (demo || shot != null) null
        else Logbook.pendingUnverified(realRows, Settings.prevIgnitionAt.longValue, Settings.ignitionAt.longValue)
    val shownCheck = if (demo) check.copy(ok = demoOk, count = demoEntries.size, headHash = demoEntries.lastOrNull()?.hash ?: Chain.GENESIS) else check
    val anchor = if (demo) DemoData.anchorMs(zone) else now
    val parked = live.gear == Gear.PARK

    LaunchedEffect(shot, monthRows) {
        when (shot) {
            "picker_purpose" -> purposeFor = monthRows.lastOrNull { it.kind == ViewKind.TRIP }
            "picker_driver" -> driverFor = monthRows.lastOrNull { it.kind == ViewKind.TRIP }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.Bg)) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val unit = minOf(wPx / DESIGN_W, hPx / DESIGN_H).coerceAtLeast(0.3f)
        CompositionLocalProvider(
            LocalUnit provides unit,
            // line height follows each font size instead of Material's fixed 24sp body line
            LocalTextStyle provides LocalTextStyle.current.copy(lineHeight = TextUnit.Unspecified)
        ) {
            if (!Settings.onboarded.value || onboardingShot != null) {
                // the first-run title is part of onboarding; afterwards go straight to "who is driving?"
                LaunchedEffect(Unit) { splashDone = true; screen = Screen.Login }
                OnboardingFlow(missingPermissions, onRequestPermissions, onFinishOnboarding, onboardingShot ?: 0)
            } else if (!splashDone) {
                SplashScreen(hold = shot == "splash", onDone = {
                    splashDone = true
                    // started in P and nobody logged in: log in first (the gear lock moves it there once P is known).
                    // Started while driving: the logbook, locked; the trip is recorded without a user.
                    if (Settings.currentDriver.intValue < 0 && live.gear == Gear.PARK) screen = Screen.Login
                })
            } else {
                when (screen) {
                    Screen.Logbook -> LogbookScreen(
                        month = shownMonth,
                        monthRows = monthRows,
                        monthTotals = monthTotals,
                        fyTotals = fyTotals,
                        fy = fy,
                        canPrev = shownMonth > earliest,
                        canNext = shownMonth < latest,
                        filter = rowFilter,
                        demo = demo,
                        onMonth = { d -> month = shownMonth.plusMonths(d.toLong()); rowFilter = RowFilter.ALL },
                        onFilter = { rowFilter = it },
                        // sample data is never written to the real ledger
                        onPurpose = { r ->
                            // 0.5.2a: a 미인증 운행 opens its own sheet (phone check, then the administrator)
                            if (r.unverified && !demo && shot == null) unverifiedFor = r
                            else if (!demo || shot != null) {
                                adminPick = !demo && !Logbook.driverMayClassify(r, repo.newestTripId(), System.currentTimeMillis(), live.parkedIdle)
                                purposeFor = r
                            }
                        },
                        // 0.5.2: who drove is set only by a phone check in front of the car
                        onDriver = { r ->
                            if (shot != null) driverFor = r
                            else if (r.unverified && !demo) unverifiedFor = r
                            else if (!demo && live.parkedIdle && carId.isNotEmpty()) authFor = r
                        },
                        onSearch = { screen = Screen.Trips },
                        onExport = { screen = Screen.Export },
                        onSettings = { settingsTab = 0; screen = Screen.Prefs },
                        driver = Settings.current(),
                        // 바꾸기 / 고르기: back to "who is driving?" (the current login ends; whoever drives logs in)
                        onWho = {
                            if (live.parkedIdle || shot != null) {
                                Settings.logout(context)
                                screen = Screen.Login
                            }
                        }
                    )
                    Screen.Trips -> TripsScreen(
                        views = views,
                        check = shownCheck,
                        live = live,
                        demo = demo,
                        anchorMs = anchor,
                        zone = zone,
                        filter = tripFilter,
                        parked = parked,
                        onFilter = { tripFilter = it },
                        onBack = { screen = Screen.Logbook },
                        onClassify = { id, p -> if (!demo) repo.classifyByDriver(id, p, System.currentTimeMillis(), live.parkedIdle) }
                    )
                    Screen.Prefs -> SettingsScreen(
                        repo = repo,
                        check = check,
                        vehicle = vehicle,
                        parked = parked,
                        demo = demo,
                        initialTab = settingsTab,
                        missingPermissions = missingPermissions,
                        onRequestPermissions = onRequestPermissions,
                        onReplayOnboarding = {
                            screen = Screen.Logbook
                            Settings.resetOnboarded(context)
                        },
                        onToggleDemo = {
                            demo = !demo
                            month = if (demo) demoMonth else YearMonth.now(zone)
                        },
                        onBack = { screen = Screen.Logbook }
                    )
                    Screen.Login -> LoginFlow(
                        onDone = { warnBanner = false; screen = Screen.Logbook },
                        onSettings = { settingsTab = 1; screen = Screen.Prefs },
                        startStep = loginStep,
                        repo = if (shot == null) repo else null,
                        carId = if (shot == null) carId else "",
                        warn = warnBanner || shot == "login_warn",
                        pending = pendingUnverified,
                        onPendingMine = {
                            val p = pendingUnverified
                            if (p != null && live.parkedIdle && carId.isNotEmpty()) authFor = p
                        },
                        onPendingNotMe = { pendingUnverified?.let { repo.appendDeny(it.id) } }
                    )
                    Screen.Export -> ExportScreen(
                        entries = source,
                        publicKeyBase64 = { repo.keys.publicKeyBase64() },
                        month = shownMonth,
                        zone = zone,
                        demo = demo,
                        unclassified = monthTotals.unclassifiedCount,
                        onBack = { screen = Screen.Logbook }
                    )
                }

                unverifiedFor?.let { r ->
                    UnverifiedSheet(
                        r,
                        canPhone = live.parkedIdle && carId.isNotEmpty(),
                        onPhone = { unverifiedFor = null; authFor = r },
                        onAdmin = { unverifiedFor = null; adminPick = true; purposeFor = r },
                        onClose = { unverifiedFor = null }
                    )
                }
                purposeFor?.let { r ->
                    PurposePicker(
                        r,
                        onPick = { p ->
                            if (!demo) {
                                val admin = Settings.adminDriver()
                                if (adminPick && admin != null) repo.classifyByAdmin(r.id, p, admin)
                                else repo.classifyByDriver(r.id, p, System.currentTimeMillis(), live.parkedIdle)
                                Notifier.cancelArrival(repo.appContext, r.id)
                            }
                            purposeFor = null
                        },
                        onClose = { purposeFor = null },
                        adminNeeded = adminPick
                    )
                }
                authFor?.let { r ->
                    AuthScreen(
                        carId = carId,
                        purpose = AuthProof.CONFIRM,
                        target = r.id,
                        title = tr("미인증 운행 확인", "Unverified trip"),
                        subtitle = Fmt.day(r.startMs) + " " + Fmt.time(r.startMs) + "–" + Fmt.time(r.endMs) + " · " + Fmt.n(r.km) + " km",
                        notice = tr("이 운행을 누가 운전했는지 확인합니다 · 로그인이 아닙니다", "Confirms who drove this trip · this is not a login"),
                        noticeWarn = true,
                        onProof = { proof ->
                            val i = Settings.indexOfIdentity(proof.personId)
                            val d = Settings.drivers.value.getOrNull(i)
                            if (d == null) {
                                tr("가입한 사용자의 이메일이 아닙니다 · 먼저 가입하세요", "Not a registered user's e-mail · sign up first")
                            } else {
                                repo.confirmTrip(r.id, proof, d)
                                authFor = null
                                confirmed = r to d
                                null
                            }
                        },
                        onCancel = { dismissedAuth = r.id; authFor = null }
                    )
                }
                confirmed?.let { (r, d) -> ConfirmedTripDialog(r, d) { confirmed = null } }
                driverFor?.let { r ->
                    DriverPicker(
                        r,
                        drivers = if (demo) DemoData.USERS else Settings.drivers.value,
                        // assigning a trip to someone needs THAT person's 4 digits (sample data is never written)
                        verify = !demo,
                        onPick = { d ->
                            if (!demo) repo.assignDriver(r.id, d)
                            driverFor = null
                        },
                        onOpenSettings = { driverFor = null; settingsTab = 1; screen = Screen.Prefs },
                        onClose = { driverFor = null }
                    )
                }

                if (shot == "arrival") {
                    val sample = views.lastOrNull { it.kind == ViewKind.TRIP }
                    if (sample != null) {
                        Scrim()
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            ArrivalCard(trip = sample, onPick = { }, onLater = { })
                        }
                    }
                } else if (!demo && !requestingPermissions && !locked && authFor == null) {
                    // 0.5.2: the driver's window — newest trip, not skipped, within 10 minutes, parked
                    val newest = allRows.lastOrNull { it.kind == ViewKind.TRIP }
                    val pending = newest?.takeIf { it.purpose == null && Logbook.driverMayClassify(it, it.id, now, live.parkedIdle) }
                    val view = pending?.let { p -> realViews.lastOrNull { it.id == p.id } }
                    if (pending != null && view != null && pending.id != dismissedArrival && purposeFor == null) {
                        Scrim()
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            ArrivalCard(
                                trip = view,
                                onPick = { p ->
                                    repo.classifyByDriver(pending.id, p, System.currentTimeMillis(), live.parkedIdle)
                                    Notifier.cancelArrival(repo.appContext, pending.id)
                                    // nobody was logged in for this trip: who drove is confirmed by a phone check
                                    if (pending.driver == null && Settings.drivers.value.isNotEmpty() && carId.isNotEmpty()) authFor = pending
                                },
                                onLater = {
                                    repo.skipPurpose(pending.id)
                                    Notifier.cancelArrival(repo.appContext, pending.id)
                                    dismissedArrival = pending.id
                                    if (pending.driver == null && Settings.drivers.value.isNotEmpty() && carId.isNotEmpty()) authFor = pending
                                }
                            )
                        }
                    } else if (newest != null && newest.unverified && newest.driver == null && newest.id != dismissedAuth &&
                        // 0.5.2g: after a car start the login screen's card asks instead (not the QR straight away)
                        Settings.ignitionAt.longValue < newest.endMs &&
                        now - newest.endMs <= Logbook.DRIVER_WINDOW_MS && Settings.drivers.value.isNotEmpty() && carId.isNotEmpty() && purposeFor == null
                    ) {
                        // a 미인증 운행 just ended: offer the phone check once (the mark stays either way)
                        LaunchedEffect(newest.id) { authFor = newest }
                    }
                }

                // 0.5.2: moving — every input is off. The screen stays readable; touches go nowhere.
                if (locked) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                                    }
                                }
                            },
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Box(
                            Modifier
                                .padding(bottom = u(28))
                                .background(Palette.Ink, RoundedCornerShape(u(30)))
                                .padding(horizontal = u(44), vertical = u(18))
                        ) {
                            Text(
                                if (live.gear == Gear.UNKNOWN) tr("기어 상태를 확인하는 중 · 입력할 수 없습니다", "Reading the gear · input is off")
                                else tr("주행 중에는 입력할 수 없습니다", "No input while driving"),
                                fontSize = fs(34), fontWeight = FontWeight.Bold, color = Palette.Bg, maxLines = 1
                            )
                        }
                    }
                }
            }

            // System permission dialogs draw above the app; dim the app behind them like any modal.
            if (requestingPermissions) Scrim()
        }
    }
}

/**
 * Layout-check helpers on the sample ledger: [scale] multiplies distances (big-number test) and
 * [dropPurposes] removes the last N purpose records (unclassified rows). Re-sealed with the demo signer.
 */
private fun scaledDemo(src: List<SealedEntry>, scale: Double, dropPurposes: Int): List<SealedEntry> {
    if (scale == 1.0 && dropPurposes == 0) return src
    val purposeSeqs = src.filter { it.entry.type == RecordType.PURPOSE }.takeLast(dropPurposes).map { it.seq }.toSet()
    val out = ArrayList<SealedEntry>(src.size)
    for (s in src) {
        if (s.seq in purposeSeqs) continue
        val e = if (scale == 1.0) s.entry else s.entry.copy(
            startOdoKm = s.entry.startOdoKm?.times(scale),
            endOdoKm = s.entry.endOdoKm?.times(scale),
            distanceKm = s.entry.distanceKm * scale
        )
        out += Chain.seal(out.lastOrNull(), e, s.createdAtMs, DemoData.DemoSigner)
    }
    return out
}

@Composable
private fun ArrivalCard(trip: TripView, onPick: (Purpose) -> Unit, onLater: () -> Unit) {
    Column(
        Modifier
            .width(u(1100))
            .shadow(u(16), RoundedCornerShape(u(36)))
            .background(Palette.Card, RoundedCornerShape(u(36)))
            .padding(horizontal = u(44), vertical = u(36))
    ) {
        Text(tr("운행이 끝났습니다", "Trip ended"), fontSize = fs(36), fontWeight = FontWeight.Bold, color = Palette.Sub)
        Spacer(Modifier.height(u(8)))
        Row(verticalAlignment = Alignment.Bottom) {
            // integer rule: whole km, the same number the logbook row will show
            val km = if (trip.startOdoKm != null && trip.endOdoKm != null) Logbook.meter(trip.endOdoKm) - Logbook.meter(trip.startOdoKm) else Math.round(trip.distanceKm)
            Text(Fmt.n(km) + " km", fontSize = fs(72), fontWeight = FontWeight.ExtraBold, color = Palette.Ink, tnum = true)
            Spacer(Modifier.width(u(24)))
            Text(Fmt.time(trip.startMs) + " – " + Fmt.time(trip.endMs), fontSize = fs(36), color = Palette.Sub, modifier = Modifier.padding(bottom = u(14)), tnum = true)
        }
        Spacer(Modifier.height(u(28)))
        Row(horizontalArrangement = Arrangement.spacedBy(u(20))) {
            Purpose.entries.forEach { p ->
                Box(
                    Modifier
                        .width(u(324))
                        .height(u(130))
                        .background(if (p.isBusiness) Palette.Primary else Palette.Tile, RoundedCornerShape(u(30)))
                        .clickable { onPick(p) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(purposeLabel(p), fontSize = fs(46), fontWeight = FontWeight.ExtraBold, color = if (p.isBusiness) Palette.OnPrimary else Palette.Ink, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(u(20)))
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .height(u(Sz.TOUCH))
                .clickable { onLater() }
                .padding(horizontal = u(40)),
            contentAlignment = Alignment.Center
        ) {
            Text(tr("건너뛰기 · 미분류로 남고 관리자만 바꿀 수 있습니다", "Skip · stays unclassified, only the admin can change it"), fontSize = fs(30), fontWeight = FontWeight.Bold, color = Palette.Sub, maxLines = 1)
        }
    }
}
