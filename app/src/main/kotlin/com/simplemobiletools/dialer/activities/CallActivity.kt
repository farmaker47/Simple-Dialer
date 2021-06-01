package com.simplemobiletools.dialer.activities

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.PowerManager
import android.provider.MediaStore
import android.telecom.Call
import android.telecom.CallAudioState
import android.util.Log
import android.util.Size
import android.view.WindowManager
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.*
import com.simplemobiletools.dialer.R
import com.simplemobiletools.dialer.extensions.addCharacter
import com.simplemobiletools.dialer.extensions.audioManager
import com.simplemobiletools.dialer.extensions.config
import com.simplemobiletools.dialer.extensions.getHandleToUse
import com.simplemobiletools.dialer.helpers.ACCEPT_CALL
import com.simplemobiletools.dialer.helpers.CallManager
import com.simplemobiletools.dialer.helpers.DECLINE_CALL
import com.simplemobiletools.dialer.models.CallContact
import com.simplemobiletools.dialer.receivers.CallActionReceiver
import com.simplemobiletools.dialer.viewmodels.CallActivityViewModel
import kotlinx.android.synthetic.main.activity_call.*
import kotlinx.android.synthetic.main.dialpad.*
import java.util.*
import org.koin.android.viewmodel.ext.android.viewModel
import androidx.lifecycle.Observer
import com.simplemobiletools.dialer.activities.MainActivity.Companion.MINIMUM_TIME_BETWEEN_SAMPLES_MS

class CallActivity : SimpleActivity() {
    private val CALL_NOTIFICATION_ID = 1

    private var isSpeakerOn = false
    private var isMicrophoneOn = true
    private var isCallEnded = false
    private var callDuration = 0
    private var callContact: CallContact? = null
    private var callContactAvatar: Bitmap? = null
    private var proximityWakeLock: PowerManager.WakeLock? = null
    private var callTimer = Timer()
    private var labels: ArrayList<String> = ArrayList()

    // Koin DI
    private val viewModel: CallActivityViewModel by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        supportActionBar?.hide()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_call)

        updateTextColors(call_holder)
        // Init and set the click listeners
        initButtons()

        audioManager.mode = AudioManager.MODE_IN_CALL

        CallManager.getCallContact(applicationContext) { contact ->
            callContact = contact
            callContactAvatar = getCallContactAvatar()
            runOnUiThread {
                setupNotification()
                updateOtherPersonsInfo()
                checkCalledSIMCard()
            }
        }

        addLockScreenFlags()

        CallManager.registerCallback(callCallback)
        updateCallState(CallManager.getState())

        // Observe values of viewmodel
        observeViewmodel()
    }

    private fun observeViewmodel() {

        // Labels
        viewModel.labels.observe(this, { labelsCommands ->
                if (labelsCommands != null) {
                    labels = labelsCommands
                    Log.v(MainActivity.LOG_TAG, labels.toArray().contentToString())
                }
            }
        )

        // Result
        viewModel.result.observe(
            this,
            Observer { result ->
                if (result != null) {

                    runOnUiThread(
                        Runnable {

                            // If we do have a new command, highlight the right list entry.
                            if (!result.foundCommand.startsWith("_") && result.isNewCommand) {
                                var labelIndex = -1
                                for (i in labels.indices) {
                                    if (labels.get(i) == result.foundCommand) {
                                        labelIndex = i
                                    }
                                }
                                when (labelIndex - 2) {
                                    0 -> Log.v(MainActivity.LOG_TAG_RESULT, "YES")//selectedTextView = bindingActivitySpeechBinding.yes
                                    1 -> Log.v(MainActivity.LOG_TAG_RESULT, "NO")//selectedTextView = bindingActivitySpeechBinding.no
                                    2 -> Log.v(MainActivity.LOG_TAG_RESULT, "UP")//selectedTextView = bindingActivitySpeechBinding.up
                                    3 -> Log.v(MainActivity.LOG_TAG_RESULT, "DOWN")//selectedTextView = bindingActivitySpeechBinding.down
                                    4 -> Log.v(MainActivity.LOG_TAG_RESULT, "LEFT")//selectedTextView = bindingActivitySpeechBinding.left
                                    5 -> Log.v(MainActivity.LOG_TAG_RESULT, "RIGHT")//selectedTextView = bindingActivitySpeechBinding.right
                                    6 -> Log.v(MainActivity.LOG_TAG_RESULT, "ON")//selectedTextView = bindingActivitySpeechBinding.on
                                    7 -> Log.v(MainActivity.LOG_TAG_RESULT, "OFF")//selectedTextView = bindingActivitySpeechBinding.off
                                    8 -> Log.v(MainActivity.LOG_TAG_RESULT, "STOP")//selectedTextView = bindingActivitySpeechBinding.stop
                                    9 -> Log.v(MainActivity.LOG_TAG_RESULT, "GO")//selectedTextView = bindingActivitySpeechBinding.go
                                }
                                /*if (selectedTextView != null) {
                                    selectedTextView?.setBackgroundResource(R.drawable.round_corner_text_bg_selected)
                                    val score =
                                        Math.round(result.score * 100).toString() + "%"
                                    selectedTextView?.setText(
                                        selectedTextView?.text.toString() + "\n" + score
                                    )
                                    selectedTextView?.setTextColor(
                                        resources.getColor(android.R.color.holo_orange_light)
                                    )
                                    handler.postDelayed(
                                        Runnable {
                                            val origionalString: String =
                                                selectedTextView?.getText().toString()
                                                    .replace(score, "").trim({ it <= ' ' })
                                            selectedTextView?.text = origionalString
                                            selectedTextView?.setBackgroundResource(
                                                R.drawable.round_corner_text_bg_unselected
                                            )
                                            selectedTextView?.setTextColor(
                                                resources.getColor(android.R.color.black)
                                            )
                                        },
                                        750
                                    )
                                }*/
                            }
                        })
                    try {
                        // We don't need to run too frequently, so snooze for a bit.
                        Thread.sleep(MINIMUM_TIME_BETWEEN_SAMPLES_MS)
                    } catch (e: InterruptedException) {
                        // Ignore
                    }

                }
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        notificationManager.cancel(CALL_NOTIFICATION_ID)
        CallManager.unregisterCallback(callCallback)
        callTimer.cancel()
        if (proximityWakeLock?.isHeld == true) {
            proximityWakeLock!!.release()
        }

        endCall()
    }

    override fun onResume() {
        super.onResume()
        viewModel.startRecording()
        viewModel.startRecognition()
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopRecording()
        viewModel.stopRecognition()
    }

    override fun onBackPressed() {
        if (dialpad_wrapper.isVisible()) {
            dialpad_wrapper.beGone()
            return
        } else {
            super.onBackPressed()
        }

        if (CallManager.getState() == Call.STATE_DIALING) {
            endCall()
        }
    }

    private fun initButtons() {
        call_decline.setOnClickListener {
            endCall()
        }

        call_accept.setOnClickListener {
            acceptCall()
        }

        call_toggle_microphone.setOnClickListener {
            toggleMicrophone()
        }

        call_toggle_speaker.setOnClickListener {
            toggleSpeaker()
        }

        call_dialpad.setOnClickListener {
            toggleDialpadVisibility()
        }

        dialpad_close.setOnClickListener {
            dialpad_wrapper.beGone()
        }

        call_end.setOnClickListener {
            endCall()
        }

        dialpad_0_holder.setOnClickListener { dialpadPressed('0') }
        dialpad_1_holder.setOnClickListener { dialpadPressed('1') }
        dialpad_2_holder.setOnClickListener { dialpadPressed('2') }
        dialpad_3_holder.setOnClickListener { dialpadPressed('3') }
        dialpad_4_holder.setOnClickListener { dialpadPressed('4') }
        dialpad_5_holder.setOnClickListener { dialpadPressed('5') }
        dialpad_6_holder.setOnClickListener { dialpadPressed('6') }
        dialpad_7_holder.setOnClickListener { dialpadPressed('7') }
        dialpad_8_holder.setOnClickListener { dialpadPressed('8') }
        dialpad_9_holder.setOnClickListener { dialpadPressed('9') }

        dialpad_0_holder.setOnLongClickListener { dialpadPressed('+'); true }
        dialpad_asterisk_holder.setOnClickListener { dialpadPressed('*') }
        dialpad_hashtag_holder.setOnClickListener { dialpadPressed('#') }

        dialpad_wrapper.setBackgroundColor(config.backgroundColor)
        arrayOf(
            call_toggle_microphone,
            call_toggle_speaker,
            call_dialpad,
            dialpad_close,
            call_sim_image
        ).forEach {
            it.applyColorFilter(config.textColor)
        }

        call_sim_id.setTextColor(config.textColor.getContrastColor())
    }

    private fun dialpadPressed(char: Char) {
        CallManager.keypad(char)
        dialpad_input.addCharacter(char)
    }

    private fun toggleSpeaker() {
        isSpeakerOn = !isSpeakerOn
        val drawable =
            if (isSpeakerOn) R.drawable.ic_speaker_on_vector else R.drawable.ic_speaker_off_vector
        call_toggle_speaker.setImageDrawable(getDrawable(drawable))
        audioManager.isSpeakerphoneOn = isSpeakerOn

        val newRoute =
            if (isSpeakerOn) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_EARPIECE
        CallManager.inCallService?.setAudioRoute(newRoute)
    }

    private fun toggleMicrophone() {
        isMicrophoneOn = !isMicrophoneOn
        val drawable =
            if (isMicrophoneOn) R.drawable.ic_microphone_vector else R.drawable.ic_microphone_off_vector
        call_toggle_microphone.setImageDrawable(getDrawable(drawable))
        audioManager.isMicrophoneMute = !isMicrophoneOn
        CallManager.inCallService?.setMuted(!isMicrophoneOn)
    }

    private fun toggleDialpadVisibility() {
        if (dialpad_wrapper.isVisible()) {
            dialpad_wrapper.beGone()
        } else {
            dialpad_wrapper.beVisible()
        }
    }

    private fun updateOtherPersonsInfo() {
        if (callContact == null) {
            return
        }

        caller_name_label.text =
            if (callContact!!.name.isNotEmpty()) callContact!!.name else getString(R.string.unknown_caller)
        if (callContact!!.number.isNotEmpty() && callContact!!.number != callContact!!.name) {
            caller_number_label.text = callContact!!.number
        } else {
            caller_number_label.beGone()
        }

        if (callContactAvatar != null) {
            caller_avatar.setImageBitmap(callContactAvatar)
        }
    }

    @SuppressLint("MissingPermission")
    private fun checkCalledSIMCard() {
        try {
            val accounts = telecomManager.callCapablePhoneAccounts
            if (accounts.size > 1) {
                accounts.forEachIndexed { index, account ->
                    if (account == CallManager.call?.details?.accountHandle) {
                        call_sim_id.text = "${index + 1}"
                        call_sim_id.beVisible()
                        call_sim_image.beVisible()
                    }
                }
            }
        } catch (ignored: Exception) {
        }
    }

    private fun updateCallState(state: Int) {
        when (state) {
            Call.STATE_RINGING -> callRinging()
            Call.STATE_ACTIVE -> callStarted()
            Call.STATE_DISCONNECTED -> endCall()
            Call.STATE_CONNECTING, Call.STATE_DIALING -> initOutgoingCallUI()
            Call.STATE_SELECT_PHONE_ACCOUNT -> showPhoneAccountPicker()
        }

        if (state == Call.STATE_DISCONNECTED || state == Call.STATE_DISCONNECTING) {
            callTimer.cancel()
        }

        val statusTextId = when (state) {
            Call.STATE_RINGING -> R.string.is_calling
            Call.STATE_DIALING -> R.string.dialing
            else -> 0
        }

        if (statusTextId != 0) {
            call_status_label.text = getString(statusTextId)
        }

        setupNotification()
    }

    private fun acceptCall() {
        CallManager.accept()
    }

    private fun initOutgoingCallUI() {
        initProximitySensor()
        incoming_call_holder.beGone()
        ongoing_call_holder.beVisible()
    }

    private fun callRinging() {
        incoming_call_holder.beVisible()
    }

    private fun callStarted() {
        initProximitySensor()
        incoming_call_holder.beGone()
        ongoing_call_holder.beVisible()
        try {
            callTimer.scheduleAtFixedRate(getCallTimerUpdateTask(), 1000, 1000)
        } catch (ignored: Exception) {
        }
    }

    private fun showPhoneAccountPicker() {
        if (callContact != null) {
            getHandleToUse(intent, callContact!!.number) { handle ->
                CallManager.call?.phoneAccountSelected(handle, false)
            }
        }
    }

    private fun endCall() {
        CallManager.reject()
        if (proximityWakeLock?.isHeld == true) {
            proximityWakeLock!!.release()
        }

        if (isCallEnded) {
            finish()
            return
        }

        try {
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (ignored: Exception) {
        }

        isCallEnded = true
        if (callDuration > 0) {
            runOnUiThread {
                call_status_label.text =
                    "${callDuration.getFormattedDuration()} (${getString(R.string.call_ended)})"
                Handler().postDelayed({
                    finish()
                }, 3000)
            }
        } else {
            call_status_label.text = getString(R.string.call_ended)
            finish()
        }
    }

    private fun getCallTimerUpdateTask() = object : TimerTask() {
        override fun run() {
            callDuration++
            runOnUiThread {
                if (!isCallEnded) {
                    call_status_label.text = callDuration.getFormattedDuration()
                }
            }
        }
    }

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            super.onStateChanged(call, state)
            updateCallState(state)
        }
    }

    @SuppressLint("NewApi")
    private fun addLockScreenFlags() {
        if (isOreoMr1Plus()) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }

        if (isOreoPlus()) {
            (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).requestDismissKeyguard(
                this,
                null
            )
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        }
    }

    private fun initProximitySensor() {
        if (proximityWakeLock == null || proximityWakeLock?.isHeld == false) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            proximityWakeLock = powerManager.newWakeLock(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                "com.simplemobiletools.dialer.pro:wake_lock"
            )
            proximityWakeLock!!.acquire(10 * MINUTE_SECONDS * 1000L)
        }
    }

    @SuppressLint("NewApi")
    private fun setupNotification() {
        val callState = CallManager.getState()
        val channelId = "simple_dialer_call"
        if (isOreoPlus()) {
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val name = "call_notification_channel"

            NotificationChannel(channelId, name, importance).apply {
                setSound(null, null)
                notificationManager.createNotificationChannel(this)
            }
        }

        val openAppIntent = Intent(this, CallActivity::class.java)
        openAppIntent.flags = Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT
        val openAppPendingIntent = PendingIntent.getActivity(this, 0, openAppIntent, 0)

        val acceptCallIntent = Intent(this, CallActionReceiver::class.java)
        acceptCallIntent.action = ACCEPT_CALL
        val acceptPendingIntent =
            PendingIntent.getBroadcast(this, 0, acceptCallIntent, PendingIntent.FLAG_CANCEL_CURRENT)

        val declineCallIntent = Intent(this, CallActionReceiver::class.java)
        declineCallIntent.action = DECLINE_CALL
        val declinePendingIntent = PendingIntent.getBroadcast(
            this,
            1,
            declineCallIntent,
            PendingIntent.FLAG_CANCEL_CURRENT
        )

        val callerName =
            if (callContact != null && callContact!!.name.isNotEmpty()) callContact!!.name else getString(
                R.string.unknown_caller
            )
        val contentTextId = when (callState) {
            Call.STATE_RINGING -> R.string.is_calling
            Call.STATE_DIALING -> R.string.dialing
            Call.STATE_DISCONNECTED -> R.string.call_ended
            Call.STATE_DISCONNECTING -> R.string.call_ending
            else -> R.string.ongoing_call
        }

        val collapsedView = RemoteViews(packageName, R.layout.call_notification).apply {
            setText(R.id.notification_caller_name, callerName)
            setText(R.id.notification_call_status, getString(contentTextId))
            setVisibleIf(R.id.notification_accept_call, callState == Call.STATE_RINGING)

            setOnClickPendingIntent(R.id.notification_decline_call, declinePendingIntent)
            setOnClickPendingIntent(R.id.notification_accept_call, acceptPendingIntent)

            if (callContactAvatar != null) {
                setImageViewBitmap(
                    R.id.notification_thumbnail,
                    getCircularBitmap(callContactAvatar!!)
                )
            }
        }

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_phone_vector)
            .setContentIntent(openAppPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(Notification.CATEGORY_CALL)
            .setCustomContentView(collapsedView)
            .setOngoing(true)
            .setSound(null)
            .setUsesChronometer(callState == Call.STATE_ACTIVE)
            .setChannelId(channelId)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())

        val notification = builder.build()
        notificationManager.notify(CALL_NOTIFICATION_ID, notification)
    }

    @SuppressLint("NewApi")
    private fun getCallContactAvatar(): Bitmap? {
        var bitmap: Bitmap? = null
        if (callContact?.photoUri?.isNotEmpty() == true) {
            val photoUri = Uri.parse(callContact!!.photoUri)
            try {
                bitmap = if (isQPlus()) {
                    val tmbSize = resources.getDimension(R.dimen.list_avatar_size).toInt()
                    contentResolver.loadThumbnail(photoUri, Size(tmbSize, tmbSize), null)
                } else {
                    MediaStore.Images.Media.getBitmap(contentResolver, photoUri)

                }

                bitmap = getCircularBitmap(bitmap!!)
            } catch (ignored: Exception) {
                return null
            }
        }

        return bitmap
    }

    private fun getCircularBitmap(bitmap: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(bitmap.width, bitmap.width, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint()
        val rect = Rect(0, 0, bitmap.width, bitmap.height)
        val radius = bitmap.width / 2.toFloat()

        paint.isAntiAlias = true
        canvas.drawARGB(0, 0, 0, 0)
        canvas.drawCircle(radius, radius, radius, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(bitmap, rect, rect, paint)
        return output
    }
}
