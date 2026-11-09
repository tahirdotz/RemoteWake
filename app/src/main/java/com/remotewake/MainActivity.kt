package com.remotewake

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateUtils
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.io.IOException
import kotlin.concurrent.thread

/** One screen per role: choose a role, Phone A (gets woken) or Phone B (the remote). */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private var shown: Role? = null
    private val onListenerChange: () -> Unit = { if (shown == Role.TARGET) renderTarget() }
    private val onSharingChange: () -> Unit = { if (shown == Role.TARGET) renderSharing() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Notifications.createChannels(this)
        show()
    }

    override fun onStart() {
        super.onStart()
        ListenerState.observe(onListenerChange)
        SharingState.observe(onSharingChange)
    }

    override fun onResume() {
        super.onResume()
        if (shown == Role.TARGET) {
            // If Android stopped the listener, coming back to the app starts it again.
            if (prefs.listening) ListenerService.start(this)
            renderTarget()
        }
    }

    override fun onStop() {
        ListenerState.remove(onListenerChange)
        SharingState.remove(onSharingChange)
        super.onStop()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CAPTURE && resultCode == RESULT_OK && data != null) {
            ScreenCaptureService.start(this, resultCode, data)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (shown == Role.TARGET) renderTarget()
    }

    private fun show() {
        shown = prefs.role
        when (prefs.role) {
            Role.NONE -> showChooser()
            Role.TARGET -> showTarget()
            Role.REMOTE -> showRemote()
        }
    }

    // ---- Choosing a role ----

    private fun showChooser() {
        setContentView(R.layout.activity_choose)
        button(R.id.choose_target).setOnClickListener {
            prefs.role = Role.TARGET
            if (prefs.targetCode == null) prefs.targetCode = Protocol.newPairCode()
            prefs.listening = true
            askForNotifications()
            ListenerService.start(this)
            show()
        }
        button(R.id.choose_remote).setOnClickListener {
            prefs.role = Role.REMOTE
            show()
        }
    }

    private fun switchRole() {
        if (prefs.role == Role.TARGET) {
            prefs.listening = false
            ListenerService.stop(this)
        }
        prefs.role = Role.NONE
        show()
    }

    // ---- Phone A ----

    private fun showTarget() {
        setContentView(R.layout.activity_target)
        val code = prefs.targetCode ?: Protocol.newPairCode().also { prefs.targetCode = it }
        text(R.id.code).text = Protocol.formatCode(code)

        button(R.id.share_code).setOnClickListener {
            val share = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, getString(R.string.share_code_text, Protocol.formatCode(code)))
            startActivity(Intent.createChooser(share, getString(R.string.share_code)))
        }
        button(R.id.new_code).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.new_code_title)
                .setMessage(R.string.new_code_message)
                .setPositiveButton(R.string.new_code) { _, _ ->
                    prefs.targetCode = Protocol.newPairCode()
                    if (prefs.listening) ListenerService.start(this) // picks up the new code
                    showTarget()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        button(R.id.toggle_listening).setOnClickListener {
            prefs.listening = !prefs.listening
            if (prefs.listening) ListenerService.start(this) else ListenerService.stop(this)
            renderTarget()
        }
        button(R.id.fix_notifications).setOnClickListener { fixNotifications() }
        button(R.id.fix_full_screen).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                openSettings(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri()))
            }
        }
        button(R.id.fix_battery).setOnClickListener {
            @Suppress("BatteryLife") // This app's whole job is to stay reachable.
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri())
            openSettings(intent)
        }
        button(R.id.test_wake).setOnClickListener { sendTestWake(code) }

        // Screen sharing setup
        button(R.id.fix_accessibility).setOnClickListener {
            openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        setUpControlRelay()
        button(R.id.toggle_sharing).setOnClickListener { toggleSharing() }

        setUpServer { if (prefs.listening) ListenerService.start(this) }
        button(R.id.switch_role).setOnClickListener { switchRole() }
        renderTarget()
    }

    private fun renderTarget() {
        val notificationsOk = notificationsAllowed()
        val fullScreenOk = fullScreenAllowed()
        val batteryOk = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        check(R.id.check_notifications, R.id.fix_notifications, notificationsOk, R.string.check_notifications)
        check(R.id.check_full_screen, R.id.fix_full_screen, fullScreenOk, R.string.check_full_screen)
        check(R.id.check_battery, R.id.fix_battery, batteryOk, R.string.check_battery)

        val status = if (!prefs.listening) {
            getString(R.string.status_off)
        } else when (ListenerState.status) {
            ListenerState.Status.CONNECTED -> getString(R.string.status_connected)
            ListenerState.Status.CONNECTING, ListenerState.Status.STOPPED -> getString(R.string.status_connecting)
            ListenerState.Status.RETRYING -> getString(R.string.status_retrying, ListenerState.detail)
        }
        text(R.id.status).text = status
        button(R.id.toggle_listening).setText(if (prefs.listening) R.string.stop_listening else R.string.start_listening)

        val woken = prefs.lastWokenAt
        text(R.id.last_woken).text = if (woken == 0L) {
            getString(R.string.never_woken)
        } else {
            getString(R.string.last_woken, DateUtils.getRelativeTimeSpanString(woken))
        }

        renderSharing()
    }

    private fun renderSharing() {
        val accessibilityOk = TouchAccessibilityService.isEnabled(this)
        val relayOk = prefs.controlRelay != null
        check(R.id.check_accessibility, R.id.fix_accessibility, accessibilityOk, R.string.check_accessibility)
        check(R.id.check_control_relay, R.id.fix_accessibility, relayOk, R.string.check_control_relay)
        // Hide the "fix" for relay — the input field is right there.
        findViewById<View>(R.id.check_control_relay)?.let {
            text(R.id.check_control_relay).text =
                getString(if (relayOk) R.string.check_ok else R.string.check_missing, getString(R.string.check_control_relay))
        }

        val sharingStatus = when (SharingState.status) {
            SharingState.Status.STOPPED -> getString(R.string.sharing_status_off)
            SharingState.Status.CONNECTING -> getString(R.string.sharing_status_connecting)
            SharingState.Status.SHARING -> getString(R.string.sharing_status_sharing)
        }
        text(R.id.sharing_status).text = sharingStatus

        val sharing = SharingState.status != SharingState.Status.STOPPED
        button(R.id.toggle_sharing).setText(if (sharing) R.string.stop_sharing else R.string.start_sharing)
    }

    @Suppress("DEPRECATION")
    private fun toggleSharing() {
        if (SharingState.status != SharingState.Status.STOPPED) {
            ScreenCaptureService.stop(this)
            return
        }
        if (prefs.controlRelay == null) {
            toast(R.string.sharing_needs_relay)
            return
        }
        if (!TouchAccessibilityService.isEnabled(this)) {
            toast(R.string.sharing_needs_accessibility)
            return
        }
        // Ask the user for screen capture permission. The result arrives in onActivityResult().
        val mpManager = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpManager.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    private fun check(textId: Int, fixId: Int, ok: Boolean, labelId: Int) {
        text(textId).text = getString(if (ok) R.string.check_ok else R.string.check_missing, getString(labelId))
        button(fixId).visibility = if (ok) View.GONE else View.VISIBLE
    }

    private fun notificationsAllowed(): Boolean =
        getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    private fun fullScreenAllowed(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun fixNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            askForNotifications()
        } else {
            openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    /** Sends a real wake command through the relay to this phone, so you can test Phone A on its own. */
    private fun sendTestWake(code: String) {
        val server = prefs.server
        Toast.makeText(this, R.string.test_wake_started, Toast.LENGTH_LONG).show()
        thread(name = "test-wake") {
            Thread.sleep(TEST_DELAY_MS)
            val message = Protocol.CommandMessage(Protocol.Command.WAKE, Protocol.newId())
            val result = runCatching { Relay(server).publish(Protocol.commandTopic(code), Protocol.encodeCommand(message)) }
            if (result.isFailure) {
                ui { Toast.makeText(this, getString(R.string.relay_error, result.exceptionOrNull()?.message), Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun setUpControlRelay() {
        val input = findViewById<EditText>(R.id.control_relay_input)
        prefs.controlRelay?.let { input.setText(it) }
        button(R.id.save_control_relay).setOnClickListener {
            val url = input.text.toString().trim().trimEnd('/')
            if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
                input.error = getString(R.string.control_relay_invalid)
                return@setOnClickListener
            }
            prefs.controlRelay = url
            input.setText(url)
            input.error = null
            hideKeyboard()
            toast(R.string.control_relay_saved)
            if (shown == Role.TARGET) renderSharing()
        }
    }

    // ---- Phone B ----

    private fun showRemote() {
        setContentView(R.layout.activity_remote)
        val input = findViewById<EditText>(R.id.code_input)
        prefs.remoteCode?.let { input.setText(Protocol.formatCode(it)) }
        button(R.id.save_code).setOnClickListener {
            if (saveRemoteCode() != null) {
                toast(R.string.code_saved)
                remoteStatus(getString(R.string.remote_ready))
            }
        }
        button(R.id.wake).setOnClickListener { send(Protocol.Command.WAKE) }
        button(R.id.ping).setOnClickListener { send(Protocol.Command.PING) }
        setUpControlRelay()
        button(R.id.view_phone_a).setOnClickListener {
            if (prefs.controlRelay == null) {
                toast(R.string.sharing_needs_relay)
                return@setOnClickListener
            }
            if (prefs.remoteCode == null) {
                toast(R.string.viewer_needs_setup)
                return@setOnClickListener
            }
            startActivity(Intent(this, ViewerActivity::class.java))
        }
        setUpServer {}
        button(R.id.switch_role).setOnClickListener { switchRole() }
        text(R.id.remote_status).setText(if (prefs.remoteCode == null) R.string.remote_needs_code else R.string.remote_ready)
    }

    /** Saves the typed code. Null (and an error on the field) if it is not a valid code. */
    private fun saveRemoteCode(): String? {
        val input = findViewById<EditText>(R.id.code_input)
        val code = Protocol.normalizeCode(input.text.toString())
        if (code == null) {
            input.error = getString(R.string.code_invalid)
            return null
        }
        prefs.remoteCode = code
        input.setText(Protocol.formatCode(code))
        input.error = null
        hideKeyboard()
        return code
    }

    private fun send(command: Protocol.Command) {
        val code = saveRemoteCode() ?: return
        val server = prefs.server
        val id = Protocol.newId()
        setRemoteBusy(true)
        remoteStatus(getString(R.string.remote_sending))
        thread(name = "send-command") {
            val relay = Relay(server)
            // A little earlier than now, in case this phone's clock is ahead of the relay's.
            val since = System.currentTimeMillis() / 1000 - 30
            try {
                relay.publish(Protocol.commandTopic(code), Protocol.encodeCommand(Protocol.CommandMessage(command, id)))
                ui { remoteStatus(getString(R.string.remote_waiting)) }

                var reply: Protocol.Reply? = null
                val deadline = SystemClock.elapsedRealtime() + REPLY_TIMEOUT_MS
                while (reply == null && SystemClock.elapsedRealtime() < deadline) {
                    Thread.sleep(POLL_INTERVAL_MS)
                    reply = try {
                        relay.poll(Protocol.replyTopic(code), since)
                            .mapNotNull { Protocol.decodeReply(it.message) }
                            .firstOrNull { it.id == id }
                    } catch (e: IOException) {
                        null // a dropped poll; try again until the deadline
                    }
                }
                val result = reply
                ui { remoteStatus(if (result == null) getString(R.string.remote_no_answer) else describe(result)) }
            } catch (e: IOException) {
                ui { remoteStatus(getString(R.string.relay_error, e.message ?: e.javaClass.simpleName)) }
            } finally {
                ui { setRemoteBusy(false) }
            }
        }
    }

    private fun describe(reply: Protocol.Reply): String {
        val battery = when {
            reply.batteryPercent < 0 -> getString(R.string.battery_unknown)
            reply.charging -> getString(R.string.battery_charging, reply.batteryPercent)
            else -> getString(R.string.battery_not_charging, reply.batteryPercent)
        }
        return when {
            reply.kind == Protocol.Reply.Kind.PONG ->
                getString(if (reply.screenOn) R.string.reply_pong_on else R.string.reply_pong_off, battery)
            reply.screenOn -> getString(R.string.reply_awake, battery)
            else -> getString(R.string.reply_awake_but_dark, battery)
        }
    }

    // Null-safe: a reply can arrive after you switched this phone to another role.
    private fun setRemoteBusy(busy: Boolean) {
        findViewById<Button>(R.id.wake)?.isEnabled = !busy
        findViewById<Button>(R.id.ping)?.isEnabled = !busy
    }

    private fun remoteStatus(message: String) {
        findViewById<TextView>(R.id.remote_status)?.text = message
    }

    // ---- Shared ----

    private fun setUpServer(onChanged: () -> Unit) {
        val input = findViewById<EditText>(R.id.server_input)
        input.setText(prefs.server)
        button(R.id.save_server).setOnClickListener {
            val server = Protocol.normalizeServer(input.text.toString())
            if (server == null) {
                input.error = getString(R.string.server_invalid)
                return@setOnClickListener
            }
            prefs.server = server
            input.setText(server)
            hideKeyboard()
            toast(R.string.server_saved)
            onChanged()
        }
    }

    private fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri()))
        }
    }

    private fun packageUri() = Uri.parse("package:$packageName")

    private fun hideKeyboard() {
        currentFocus?.let { getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0) }
    }

    private fun toast(textId: Int) = Toast.makeText(this, textId, Toast.LENGTH_SHORT).show()

    /** Runs [block] on the main thread, unless the screen has gone away in the meantime. */
    private fun ui(block: () -> Unit) = runOnUiThread { if (!isDestroyed) block() }

    private fun button(id: Int) = findViewById<Button>(id)

    private fun text(id: Int) = findViewById<TextView>(id)

    private companion object {
        const val REPLY_TIMEOUT_MS = 30_000L
        const val POLL_INTERVAL_MS = 2_000L
        const val TEST_DELAY_MS = 10_000L
        const val REQUEST_CAPTURE = 100
    }
}
