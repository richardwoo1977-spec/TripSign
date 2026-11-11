// ver 1.6.0 | 2026-11-07 13:25
package io.tripsign.app.data

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import io.tripsign.app.core.Driver
import java.util.Locale

/** User settings shared by the UI and the service. Backed by SharedPreferences, observed by Compose. */
object Settings {
    const val LANG_SYSTEM = "system"
    const val LANG_KO = "ko"
    const val LANG_EN = "en"
    const val THEME_SYSTEM = "system"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"
    const val SIZE_NORMAL = "normal"
    const val SIZE_LARGE = "large"

    /** Large text multiplies every text size by this; layouts are budgeted for it. */
    const val LARGE_SCALE = 1.2f

    val language = mutableStateOf(LANG_SYSTEM)
    val theme = mutableStateOf(THEME_SYSTEM)
    val textSize = mutableStateOf(SIZE_NORMAL)
    val onboarded = mutableStateOf(false)

    /** 사업연도 시작 월 (1..12). Only changes how totals ⑪⑫⑬ are grouped; records are untouched. */
    val fyStart = mutableIntStateOf(1)

    /** Registered users (form ④), at most [MAX_DRIVERS]. The logged-in one is written into every new trip. */
    val drivers = mutableStateOf<List<Driver>>(emptyList())
    /**
     * Each user's own 4-digit number, same order as [drivers]. "" = not set yet (users added before 0.5).
     * 0.5.2: no longer the last digits of the phone (colleagues know those); the user picks a number of their own.
     */
    val pins = mutableStateOf<List<String>>(emptyList())
    /** Each user's verified identity (server phone check, e-mail hash), same order as [drivers]. "" = not checked yet. */
    val identities = mutableStateOf<List<String>>(emptyList())
    /** The administrator (index into [drivers]): the first registered user unless handed over. Only they may change a closed purpose. */
    val admin = mutableIntStateOf(-1)
    /** Last login time of each user (epoch ms, 0 = never), same order as [drivers]. */
    val lastLogin = mutableStateOf<List<Long>>(emptyList())
    /**
     * Who is logged in for this drive (index into [drivers]), -1 = nobody. Ends when a trip ends
     * (TripRepository), when the car restarts (boot count changes), or after [IDLE_LOGOUT_MS] parked.
     */
    val currentDriver = mutableIntStateOf(-1)
    /**
     * 0.5.2g: the last two car starts (epoch ms, 0 = none yet). A 미인증 운행 that ended between them is put in front of
     * whoever starts the car next, once (see Logbook.pendingUnverified).
     */
    val ignitionAt = mutableLongStateOf(0L)
    val prevIgnitionAt = mutableLongStateOf(0L)
    const val MAX_DRIVERS = 8
    const val IDLE_LOGOUT_MS = 10 * 60_000L
    private var sessionAt = 0L
    private var sessionBoot = -1

    /** Set by the UI from the system configuration. */
    val systemDark = mutableStateOf(false)

    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        val p = context.getSharedPreferences("tripsign_prefs", Context.MODE_PRIVATE)
        language.value = p.getString("language", LANG_SYSTEM) ?: LANG_SYSTEM
        // v0.4: no app-only light/dark any more; the app follows the car's day/night (a saved choice is ignored)
        theme.value = THEME_SYSTEM
        // v0.5: no large text any more (the glance layout does not hold at x1.2); a saved choice is ignored
        textSize.value = SIZE_NORMAL
        onboarded.value = p.getBoolean("onboarded", false)
        fyStart.intValue = p.getInt("fyStart", 1).coerceIn(1, 12)
        drivers.value = (p.getString("drivers", "") ?: "").split('\u001E').filter { it.isNotEmpty() }.mapNotNull { Driver.decode(it) }
        val n = drivers.value.size
        val savedPins = (p.getString("pins", "") ?: "").split('\u001E')
        pins.value = List(n) { i -> savedPins.getOrNull(i)?.takeIf { isPin(it) } ?: "" }
        val savedLast = (p.getString("lastLogin", "") ?: "").split('\u001E')
        lastLogin.value = List(n) { i -> savedLast.getOrNull(i)?.toLongOrNull() ?: 0L }
        val savedIds = (p.getString("identities", "") ?: "").split('\u001E')
        identities.value = List(n) { i -> savedIds.getOrNull(i)?.takeIf { it.length == 64 } ?: "" }
        val a = p.getInt("admin", -1)
        admin.intValue = if (a in 0 until n) a else if (n > 0) 0 else -1
        // a login survives the app process being restarted during a drive, but not a car restart
        val boot = bootCount(context)
        val s = p.getInt("session", -1)
        sessionAt = p.getLong("sessionAt", 0L)
        sessionBoot = p.getInt("sessionBoot", -1)
        currentDriver.intValue = if (s in 0 until n && boot >= 0 && boot == sessionBoot) s else -1
        ignitionAt.longValue = p.getLong("ignitionAt", 0L)
        prevIgnitionAt.longValue = p.getLong("prevIgnitionAt", 0L)
        loaded = true
    }

    private fun save(context: Context) {
        context.getSharedPreferences("tripsign_prefs", Context.MODE_PRIVATE).edit()
            .putString("language", language.value)
            .putString("theme", theme.value)
            .putString("textSize", textSize.value)
            .putBoolean("onboarded", onboarded.value)
            .putInt("fyStart", fyStart.intValue)
            .putString("drivers", drivers.value.joinToString("\u001E") { it.encode() })
            .putString("pins", pins.value.joinToString("\u001E"))
            .putString("lastLogin", lastLogin.value.joinToString("\u001E"))
            .putString("identities", identities.value.joinToString("\u001E"))
            .putInt("admin", admin.intValue)
            .putInt("session", currentDriver.intValue)
            .putLong("sessionAt", sessionAt)
            .putInt("sessionBoot", sessionBoot)
            .apply()
    }

    fun setFyStart(context: Context, month: Int) {
        fyStart.intValue = month.coerceIn(1, 12)
        save(context)
    }

    /** "1234": exactly four digits. */
    fun isPin(s: String): Boolean = s.length == 4 && s.all { it in '0'..'9' }

    fun exists(d: Driver): Boolean = drivers.value.any { it.dept == d.dept && it.name == d.name }

    /**
     * 가입: adds a user with the last 4 digits of their phone and logs them in. No approval step.
     * Returns false when the list is full, the name is empty, the digits are not 4 digits or the same
     * 부서+성명 already exists.
     */
    fun signUp(context: Context, d: Driver, pin: String, identity: String = "", now: Long = System.currentTimeMillis()): Boolean {
        if (d.name.isBlank() || !isPin(pin) || exists(d) || drivers.value.size >= MAX_DRIVERS) return false
        if (identity.isNotEmpty() && indexOfIdentity(identity) >= 0) return false
        drivers.value = drivers.value + d
        pins.value = pins.value + pin
        lastLogin.value = lastLogin.value + 0L
        identities.value = identities.value + identity
        if (admin.intValue < 0) admin.intValue = drivers.value.size - 1
        startSession(context, drivers.value.size - 1, now)
        return true
    }

    /** The registered user whose phone check gave [identity], or -1. */
    fun indexOfIdentity(identity: String): Int = if (identity.isEmpty()) -1 else identities.value.indexOf(identity)

    /** Records a phone check for a user registered before 0.5.2. Refused when another user already holds that identity. */
    fun setIdentity(context: Context, index: Int, identity: String): Boolean {
        if (index !in drivers.value.indices || identity.length != 64) return false
        val other = indexOfIdentity(identity)
        if (other >= 0 && other != index) return false
        identities.value = identities.value.mapIndexed { i, v -> if (i == index) identity else v }
        save(context)
        return true
    }

    fun isVerified(index: Int): Boolean = identities.value.getOrNull(index)?.isNotEmpty() == true

    fun adminDriver(): Driver? = drivers.value.getOrNull(admin.intValue)

    /** Hands the administrator role to [index] (the screen asks the current administrator's number first). */
    fun setAdmin(context: Context, index: Int): Boolean {
        if (index !in drivers.value.indices) return false
        admin.intValue = index
        save(context)
        return true
    }

    /** Logs [index] in when [pin] matches. A user without digits yet (added before 0.5) must set them first. */
    fun login(context: Context, index: Int, pin: String, now: Long = System.currentTimeMillis()): Boolean {
        val saved = pins.value.getOrNull(index) ?: return false
        if (saved.isEmpty() || saved != pin) return false
        startSession(context, index, now)
        return true
    }

    /** True when [index] has saved 4 digits (users added before 0.5 set them at their first login). */
    fun hasPin(index: Int): Boolean = pins.value.getOrNull(index)?.isNotEmpty() == true

    /** True when [pin] is the saved 4 digits of [index] (checks only; nobody is logged in). */
    fun verifyPin(index: Int, pin: String): Boolean {
        val saved = pins.value.getOrNull(index) ?: return false
        return saved.isNotEmpty() && saved == pin
    }

    /** No login for this long: anyone may remove the account (someone who left, without a master key). */
    const val INACTIVE_REMOVE_MS = 30L * 24 * 60 * 60 * 1000

    /** 지우기 without the owner's digits: only accounts unused for [INACTIVE_REMOVE_MS] (or never used). */
    fun removableByAnyone(index: Int, now: Long = System.currentTimeMillis()): Boolean {
        val last = lastLogin.value.getOrNull(index) ?: return false
        return last <= 0L || now - last >= INACTIVE_REMOVE_MS
    }

    /** Sets or changes the 4 digits of [index] (the screen asks twice before calling this). */
    fun setPin(context: Context, index: Int, pin: String): Boolean {
        if (index !in drivers.value.indices || !isPin(pin)) return false
        pins.value = pins.value.mapIndexed { i, v -> if (i == index) pin else v }
        save(context)
        return true
    }

    private fun startSession(context: Context, index: Int, now: Long) {
        currentDriver.intValue = index
        lastLogin.value = lastLogin.value.mapIndexed { i, v -> if (i == index) now else v }
        sessionAt = now
        sessionBoot = bootCount(context)
        save(context)
    }

    /** The boot on which the service last treated a start as an ignition (so a process restart is not one). */
    fun lastIgnitionBoot(context: Context): Int =
        context.getSharedPreferences("tripsign_prefs", Context.MODE_PRIVATE).getInt("ignitionBoot", -2)

    fun setIgnitionBoot(context: Context, boot: Int) {
        context.getSharedPreferences("tripsign_prefs", Context.MODE_PRIVATE).edit().putInt("ignitionBoot", boot).apply()
    }

    /** 0.5.2g: the car was started at [ms] (ignition, a new boot, or waking from sleep). */
    fun markIgnition(context: Context, ms: Long) {
        prevIgnitionAt.longValue = ignitionAt.longValue
        ignitionAt.longValue = ms
        context.getSharedPreferences("tripsign_prefs", Context.MODE_PRIVATE).edit()
            .putLong("ignitionAt", ms).putLong("prevIgnitionAt", prevIgnitionAt.longValue).apply()
    }

    fun logout(context: Context) {
        if (currentDriver.intValue < 0) return
        currentDriver.intValue = -1
        save(context)
    }

    /** Called while the car is moving: a drive in progress keeps the login alive. */
    fun touchSession(now: Long = System.currentTimeMillis()) {
        if (currentDriver.intValue >= 0) sessionAt = now
    }

    /** Logs out a login left standing in P without driving (someone else may be next). */
    fun expireIfIdle(context: Context, parked: Boolean, now: Long = System.currentTimeMillis()) {
        if (parked && currentDriver.intValue >= 0 && now - sessionAt > IDLE_LOGOUT_MS) logout(context)
    }

    /** Login screen order: most recent login first, users who never logged in last (in sign-up order). */
    fun loginOrder(): List<Int> = drivers.value.indices.sortedWith(compareByDescending<Int> { lastLogin.value.getOrElse(it) { 0L } }.thenBy { it })

    /** 설정 → 사용자 → 지우기. Past trips keep the name they were signed with. */
    fun removeDriver(context: Context, index: Int) {
        if (index !in drivers.value.indices) return
        drivers.value = drivers.value.filterIndexed { i, _ -> i != index }
        pins.value = pins.value.filterIndexed { i, _ -> i != index }
        lastLogin.value = lastLogin.value.filterIndexed { i, _ -> i != index }
        identities.value = identities.value.filterIndexed { i, _ -> i != index }
        val ad = admin.intValue
        // the administrator leaves: the earliest remaining user takes over
        admin.intValue = when {
            drivers.value.isEmpty() -> -1
            ad == index -> 0
            ad > index -> ad - 1
            else -> ad
        }
        val c = currentDriver.intValue
        currentDriver.intValue = when {
            c == index -> -1
            c > index -> c - 1
            else -> c
        }
        save(context)
    }

    fun current(): Driver? = drivers.value.getOrNull(currentDriver.intValue)

    /** Number of boots of the head unit; changes when the car restarts. -1 when unknown. */
    fun bootCount(context: Context): Int =
        try { android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1) } catch (e: Exception) { -1 }

    fun setLanguage(context: Context, value: String) {
        language.value = value
        save(context)
    }

    fun setTheme(context: Context, value: String) {
        theme.value = value
        save(context)
    }

    fun setTextSize(context: Context, value: String) {
        textSize.value = value
        save(context)
    }

    /**
     * Layout check (debug builds): switch language / text size / theme for one screenshot run
     * without saving. A force-stop of the app restores the saved values.
     */
    fun applyForTest(lang: String?, size: String?, themeValue: String?) {
        if (lang != null) language.value = lang
        if (size != null) textSize.value = size
        if (themeValue != null) theme.value = themeValue
    }

    fun setOnboarded(context: Context) {
        onboarded.value = true
        save(context)
    }

    /** Shows title + intro + permission pages again (demo / judging). Records are untouched. */
    fun resetOnboarded(context: Context) {
        onboarded.value = false
        save(context)
    }

    val isEnglish: Boolean
        get() = when (language.value) {
            LANG_EN -> true
            LANG_KO -> false
            else -> Locale.getDefault().language != "ko"
        }

    val textScale: Float
        get() = if (textSize.value == SIZE_LARGE) LARGE_SCALE else 1f

    val isDark: Boolean
        get() = when (theme.value) {
            THEME_DARK -> true
            THEME_LIGHT -> false
            else -> systemDark.value
        }
}

/** Korean / English text picker. Reading it inside a composable recomposes on language change. */
fun tr(ko: String, en: String): String = if (Settings.isEnglish) en else ko
