/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.witaqua.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.res.Configuration
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import org.witaqua.tv.dmb.Mbt

/**
 * Full-screen viewer: video in a SurfaceView, a status overlay, a channel
 * panel and a control bar. All tuner work goes through [TvController].
 */
class TvActivity : Activity(), TvController.Ui, SurfaceHolder.Callback {

    private lateinit var controller: TvController
    private lateinit var store: ChannelStore

    private lateinit var channelLabel: TextView
    private lateinit var stateView: TextView
    private lateinit var eventsView: TextView
    private lateinit var controls: View
    private lateinit var overlay: View
    private lateinit var channelPanel: View
    private lateinit var channelList: ListView
    private lateinit var emptyView: View
    private lateinit var scanButton: Button
    private lateinit var muteButton: Button
    private lateinit var reconnectButton: Button
    private lateinit var adapter: ArrayAdapter<String>

    private var channels: MutableList<Channel> = ArrayList()
    private var currentIndex = 0
    private var state = TvController.State.DISCONNECTED
    private var autoTune = true
    private var muted = false
    /** A scan cancelled by tuning elsewhere must not re-tune afterwards. */
    private var stoppedByButton = false
    private val events = ArrayDeque<String>()
    private var portrait = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        store = ChannelStore(this)
        channels = store.load().toMutableList()
        currentIndex = store.lastIndex.coerceIn(0, maxOf(0, channels.size - 1))
        muted = store.muted

        channelLabel = findViewById(R.id.channel_label)
        stateView = findViewById(R.id.state)
        eventsView = findViewById(R.id.events)
        controls = findViewById(R.id.controls)
        overlay = findViewById(R.id.overlay)
        channelPanel = findViewById(R.id.channel_panel)
        channelList = findViewById(R.id.channel_list)
        emptyView = findViewById(R.id.empty)
        scanButton = findViewById(R.id.scan)
        muteButton = findViewById(R.id.mute)
        reconnectButton = findViewById(R.id.reconnect)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_activated_1, ArrayList())
        channelList.adapter = adapter
        channelList.choiceMode = ListView.CHOICE_MODE_SINGLE
        channelList.setOnItemClickListener { _, _, position, _ ->
            tuneTo(position)
            if (!portrait) channelPanel.visibility = View.GONE
        }
        channelList.setOnItemLongClickListener { _, _, position, _ ->
            editChannel(position)
            true
        }
        refreshChannelList()

        findViewById<Button>(R.id.ch_up).setOnClickListener { step(1) }
        findViewById<Button>(R.id.ch_down).setOnClickListener { step(-1) }
        findViewById<Button>(R.id.show_channels).setOnClickListener {
            if (portrait) return@setOnClickListener
            channelPanel.visibility =
                if (channelPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        scanButton.setOnClickListener {
            if (state == TvController.State.SCANNING) {
                stoppedByButton = true
                controller.cancelScan()
            } else {
                stoppedByButton = false
                controller.scan()
            }
        }
        muteButton.setOnClickListener {
            muted = !muted
            store.muted = muted
            updateMuteButton()
            controller.setMuted(muted)
        }
        reconnectButton.setOnClickListener {
            autoTune = true
            controller.reconnect()
        }
        findViewById<View>(R.id.video).setOnClickListener { toggleChrome() }
        findViewById<SurfaceView>(R.id.video).holder.addCallback(this)

        updateMuteButton()
        updateChannelLabel()
        controller = TvController(this)
        applyOrientation(resources.configuration)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation(newConfig)
    }

    override fun onStart() {
        super.onStart()
        applySystemBars()
        autoTune = true
        controller.start(muted)
    }

    override fun onStop() {
        controller.stop()
        super.onStop()
    }

    override fun onDestroy() {
        controller.quit()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Surface

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        controller.setSurface(holder.surface, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        controller.surfaceGone()
    }

    // ------------------------------------------------------------------
    // Keys

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_DPAD_UP -> step(1)
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_DPAD_DOWN -> step(-1)
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    // ------------------------------------------------------------------
    // TvController.Ui

    override fun onState(state: TvController.State, detail: String?) {
        this.state = state
        val text = getString(when (state) {
            TvController.State.DISCONNECTED -> R.string.state_disconnected
            TvController.State.NO_SERVICE -> R.string.state_no_service
            TvController.State.IDLE -> R.string.state_idle
            TvController.State.SCANNING -> R.string.state_scanning
            TvController.State.WATCHING -> R.string.state_watching
            TvController.State.DEAD -> R.string.state_dead
        })
        stateView.text = if (detail != null) "$text — $detail" else text
        reconnectButton.visibility = when (state) {
            TvController.State.DEAD, TvController.State.NO_SERVICE,
            TvController.State.DISCONNECTED -> View.VISIBLE
            else -> View.GONE
        }
        scanButton.setText(
            if (state == TvController.State.SCANNING) R.string.cancel_scan else R.string.scan)
        if (state == TvController.State.DEAD || state == TvController.State.NO_SERVICE) {
            showChrome()
        }
        if (state == TvController.State.IDLE && autoTune) {
            autoTune = false
            if (channels.isNotEmpty()) tuneTo(currentIndex) else showChrome()
        }
    }

    override fun onStatus(line: String) {
        events.addLast(line)
        while (events.size > MAX_EVENTS) events.removeFirst()
        eventsView.text = events.joinToString("\n")
    }

    override fun onSignal(lock: Int) {
        onStatus(getString(when (lock) {
            Mbt.SIG_LOCK -> R.string.signal_lock
            Mbt.SIG_UNLOCK -> R.string.signal_unlock
            else -> return onStatus("signal ${Mbt.name(lock)}")
        }))
    }

    override fun onScanProgress(channel: Int, found: Int) {
        stateView.text = getString(R.string.scan_progress, channel, found)
    }

    override fun onScanFinished(found: List<Channel>, cancelled: Boolean) {
        onStatus(getString(if (cancelled) R.string.scan_cancelled else R.string.scan_done,
                found.size))
        if (found.isNotEmpty()) {
            // Keep what the user set on channels that were found again.
            val old = channels.associateBy { it.physical }
            channels = found.map { f ->
                old[f.physical]?.let { o ->
                    if (f.serviceId != 0 && o.serviceId == 0) o.copy(serviceId = f.serviceId) else o
                } ?: f
            }.toMutableList()
            store.save(channels)
            currentIndex = 0
            store.lastIndex = 0
            refreshChannelList()
            channelPanel.visibility = View.VISIBLE
        }
        if ((!cancelled || stoppedByButton) && channels.isNotEmpty()) tuneTo(currentIndex)
        stoppedByButton = false
    }

    override fun onPsiInfo(physical: Int, serviceId: Int, recordable: Int) {
        // Learn the service id of a channel stored without one.
        val i = channels.indexOfFirst { it.physical == physical && it.serviceId == 0 }
        if (i >= 0 && serviceId != 0) {
            channels[i] = channels[i].copy(serviceId = serviceId)
            store.save(channels)
            refreshChannelList()
        }
    }

    // ------------------------------------------------------------------

    private fun step(delta: Int) {
        if (channels.isEmpty()) return
        tuneTo(Math.floorMod(currentIndex + delta, channels.size))
    }

    private fun tuneTo(index: Int) {
        if (index !in channels.indices) return
        currentIndex = index
        store.lastIndex = index
        channelList.setItemChecked(index, true)
        updateChannelLabel()
        controller.tune(channels[index])
    }

    private fun refreshChannelList() {
        adapter.clear()
        adapter.addAll(channels.map { c ->
            buildString {
                append(c.label)
                append("  (UHF ").append(c.physical)
                if (c.serviceId != 0) append(", sid ").append(c.serviceId)
                append(')')
            }
        })
        adapter.notifyDataSetChanged()
        emptyView.visibility = if (channels.isEmpty()) View.VISIBLE else View.GONE
        if (currentIndex in channels.indices) channelList.setItemChecked(currentIndex, true)
        updateChannelLabel()
    }

    private fun updateChannelLabel() {
        channelLabel.text = channels.getOrNull(currentIndex)?.let {
            "${it.label}  %.3f MHz".format(Mbt.frequencyKhz(it.physical) / 1000.0)
        } ?: ""
    }

    private fun updateMuteButton() {
        muteButton.setText(if (muted) R.string.unmute else R.string.mute)
    }

    /** A bring-up editor for the select() parameters of one channel. */
    private fun editChannel(index: Int) {
        val c = channels.getOrNull(index) ?: return
        val pad = (16 * resources.displayMetrics.density).toInt()
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        fun field(label: Int, value: String, number: Boolean = true): EditText {
            form.addView(TextView(this).apply { setText(label) })
            return EditText(this).apply {
                setText(value)
                inputType = if (number) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
                form.addView(this)
            }
        }
        val name = field(R.string.field_name, c.name, number = false)
        val sid = field(R.string.field_service_id, c.serviceId.toString())
        val type = field(R.string.field_service_type, c.serviceType.toString())
        val sid1 = field(R.string.field_service_id_1seg, c.serviceId1Seg.toString())
        val pmt = field(R.string.field_pmt_pid, c.pmtPid.toString())
        val video = field(R.string.field_video_pid, c.videoPid.toString())
        val audio = field(R.string.field_audio_pid, c.audioPid.toString())
        fun EditText.int(fallback: Int) = text.toString().trim().toIntOrNull() ?: fallback

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.edit_channel) + " — UHF ${c.physical}")
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton(R.string.save) { _, _ ->
                channels[index] = c.copy(
                    name = name.text.toString().trim(),
                    serviceId = sid.int(c.serviceId),
                    serviceType = type.int(c.serviceType),
                    serviceId1Seg = sid1.int(c.serviceId1Seg),
                    pmtPid = pmt.int(c.pmtPid),
                    videoPid = video.int(c.videoPid),
                    audioPid = audio.int(c.audioPid),
                )
                store.save(channels)
                refreshChannelList()
                if (index == currentIndex) tuneTo(index)
            }
            .setNeutralButton(R.string.delete_channel) { _, _ ->
                channels.removeAt(index)
                store.save(channels)
                if (currentIndex >= channels.size) currentIndex = maxOf(0, channels.size - 1)
                refreshChannelList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toggleChrome() {
        // In portrait the controls and the list live below the picture.
        if (portrait) return
        if (controls.visibility == View.VISIBLE) hideChrome() else showChrome()
    }

    /*
     * Landscape: the picture fills the screen and the controls and channel
     * list float over it. Portrait: a 16:9 picture at the top with the
     * controls and the channel list stacked below it. The activity handles
     * the rotation itself, so the connection and the tuned channel survive it;
     * the SurfaceView's new size reaches the service through surfaceChanged.
     */
    private fun applyOrientation(config: Configuration) {
        portrait = config.orientation == Configuration.ORIENTATION_PORTRAIT
        val video = findViewById<SurfaceView>(R.id.video)
        val match = FrameLayout.LayoutParams.MATCH_PARENT
        val wrap = FrameLayout.LayoutParams.WRAP_CONTENT
        if (portrait) {
            val width = resources.displayMetrics.widthPixels
            val videoHeight = width * 9 / 16
            video.layoutParams = FrameLayout.LayoutParams(match, videoHeight, Gravity.TOP)
            controls.layoutParams =
                FrameLayout.LayoutParams(match, wrap, Gravity.TOP).apply { topMargin = videoHeight }
            controls.visibility = View.VISIBLE
            overlay.visibility = View.VISIBLE
            channelPanel.visibility = View.VISIBLE
            shareControlsWidth(true)
            controls.post {
                channelPanel.layoutParams = FrameLayout.LayoutParams(match, match, Gravity.TOP)
                    .apply { topMargin = videoHeight + controls.height }
            }
        } else {
            video.layoutParams = FrameLayout.LayoutParams(match, match, Gravity.CENTER)
            controls.layoutParams = FrameLayout.LayoutParams(match, wrap, Gravity.BOTTOM)
            channelPanel.layoutParams = FrameLayout.LayoutParams(
                (280 * resources.displayMetrics.density).toInt(), match, Gravity.END)
            channelPanel.visibility = View.GONE
            shareControlsWidth(false)
        }
        applySystemBars()
    }

    /*
     * The buttons do not fit across a portrait screen at their natural width,
     * so there they share it equally on one line each. The channel list is
     * always shown in portrait, which makes its toggle redundant there.
     */
    private fun shareControlsWidth(share: Boolean) {
        findViewById<View>(R.id.show_channels).visibility = if (share) View.GONE else View.VISIBLE
        val row = controls as LinearLayout
        for (i in 0 until row.childCount) {
            val child = row.getChildAt(i)
            child.layoutParams = if (share) {
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            } else {
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            if (child is Button) {
                child.maxLines = 1
                child.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (share) 13f else 14f)
            }
        }
    }

    private fun showChrome() {
        controls.visibility = View.VISIBLE
        overlay.visibility = View.VISIBLE
    }

    private fun hideChrome() {
        controls.visibility = View.GONE
        channelPanel.visibility = View.GONE
        // Errors stay visible.
        if (state == TvController.State.WATCHING) overlay.visibility = View.GONE
    }

    private fun applySystemBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        private const val MAX_EVENTS = 6
    }
}
