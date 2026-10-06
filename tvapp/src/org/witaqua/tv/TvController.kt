/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.witaqua.tv

import android.os.DeadObjectException
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.os.ServiceManager
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.witaqua.tv.dmb.DmbClientStub
import org.witaqua.tv.dmb.DmbListener
import org.witaqua.tv.dmb.DmbProxy
import org.witaqua.tv.dmb.DmbServiceProxy
import org.witaqua.tv.dmb.Mbt
import org.witaqua.tv.dmb.SurfaceProducer

/**
 * Owns the one session lgemtvserver allows and drives it.
 *
 * Every binder call happens on a single worker thread, in order, so the service
 * never sees overlapping calls (the stock app and JoanTvTest serialise the same
 * way). Callbacks from the service arrive on binder threads and are handed to
 * [ui] on the main thread. Any RemoteException ends the session and moves to
 * [State.DEAD]; nothing here lets one escape.
 */
class TvController(private val ui: Ui) {

    enum class State { DISCONNECTED, NO_SERVICE, IDLE, SCANNING, WATCHING, DEAD }

    /** Everything is called on the main thread. */
    interface Ui {
        fun onState(state: State, detail: String?)
        fun onStatus(line: String)
        fun onSignal(lock: Int)
        fun onScanProgress(channel: Int, found: Int)
        fun onScanFinished(found: List<Channel>, cancelled: Boolean)
        /** PSI_INFO for the tuned service: physical channel, service id, recordable. */
        fun onPsiInfo(physical: Int, serviceId: Int, recordable: Int)
    }

    private val thread = HandlerThread("JoanTv-dmb").apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    /* Worker-thread state. */
    private var dmb: DmbProxy? = null
    private var serviceBinder: IBinder? = null
    private var client: DmbClientStub? = null
    private var initedMode = 0
    private var state = State.DISCONNECTED
    private var producer: IBinder? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var current: Channel? = null
    private var muted = false

    /** Bumped on every (re)connect so a late death notice of an old session is ignored. */
    private val generation = AtomicInteger()
    private val deathRecipient = object : IBinder.DeathRecipient {
        override fun binderDied() {}
        override fun binderDied(who: IBinder) {
            val gen = generation.get()
            Log.w(TAG, "binder died: $who")
            worker.post { if (gen == generation.get()) onDead("lgemtvserver died") }
        }
    }

    /* Shared with binder threads during a scan. */
    private val scanCancel = AtomicBoolean(false)
    private val signalLatch = AtomicReference<CountDownLatch?>()
    private val psiLatch = AtomicReference<CountDownLatch?>()
    @Volatile private var lastSignal = 0
    @Volatile private var lastPsi: IntArray? = null

    // ------------------------------------------------------------------
    // Public API (main thread)

    fun start(muted: Boolean) = worker.post {
        this.muted = muted
        if (dmb == null) connect()
    }

    /** Ends the session: exit() if inited, then disconnect(). */
    fun stop() {
        cancelScan()
        worker.post { release() }
    }

    fun reconnect() {
        cancelScan()
        worker.post {
            release()
            connect()
        }
    }

    fun quit() {
        stop()
        thread.quitSafely()
    }

    fun setSurface(surface: Surface, width: Int, height: Int) {
        val binder = SurfaceProducer.of(surface)
        if (binder == null) status("could not get a buffer producer from the Surface")
        worker.post {
            producer = binder
            surfaceWidth = width
            surfaceHeight = height
            if (state == State.WATCHING) {
                applyVideoDimension()
            } else if (state == State.IDLE && current != null) {
                // Coming back from a surface loss: tune again.
                current?.let { doTune(it) }
            }
        }
    }

    /**
     * The SurfaceView is about to destroy its surface. Stop the service from
     * drawing into it first: the stack has to be re-inited and re-tuned
     * afterwards, which setSurface() does when the new surface arrives. Waits a
     * little so that happens before the buffers go away.
     */
    fun surfaceGone() {
        val done = CountDownLatch(1)
        worker.post {
            producer = null
            if (state == State.WATCHING) {
                guard("exit") { it.exit() }
                initedMode = 0
                if (state == State.WATCHING) setState(State.IDLE, null)
            }
            done.countDown()
        }
        try {
            done.await(1500, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    fun tune(channel: Channel) {
        cancelScan()
        worker.post { doTune(channel) }
    }

    fun setMuted(mute: Boolean) = worker.post {
        muted = mute
        if (state == State.WATCHING) {
            val r = guard("setMuteStatus") { it.setMuteStatus(if (mute) 1 else 0) }
            status("setMuteStatus(${if (mute) 1 else 0}) -> $r")
        }
    }

    fun scan(channels: IntRange = Mbt.UHF_CHANNELS) {
        scanCancel.set(false)
        worker.post { doScan(channels) }
    }

    fun cancelScan() {
        scanCancel.set(true)
        signalLatch.get()?.countDown()
        psiLatch.get()?.countDown()
    }

    // ------------------------------------------------------------------
    // Worker thread

    private fun connect() {
        val binder = try {
            ServiceManager.checkService(SERVICE_NAME)
        } catch (e: RuntimeException) {
            Log.e(TAG, "checkService failed", e)
            null
        }
        if (binder == null) {
            setState(State.NO_SERVICE, "$SERVICE_NAME is not published")
            return
        }
        val stub = DmbClientStub(listener)
        try {
            val session = DmbServiceProxy(binder).connect(stub)
            if (session == null) {
                setState(State.NO_SERVICE, "IDmbService.connect returned no session")
                return
            }
            generation.incrementAndGet()
            binder.linkToDeath(deathRecipient, 0)
            session.binder.linkToDeath(deathRecipient, 0)
            serviceBinder = binder
            client = stub
            dmb = session
            initedMode = 0
            Log.i(TAG, "connected to $SERVICE_NAME")
            setState(State.IDLE, null)
        } catch (e: RemoteException) {
            Log.e(TAG, "connect failed", e)
            setState(State.DEAD, e.toString())
        }
    }

    private fun release() {
        val session = dmb ?: return
        generation.incrementAndGet()
        if (initedMode != 0) {
            guardQuiet("exit") { session.exit() }
        }
        guardQuiet("disconnect") { session.disconnect() }
        runCatching { session.binder.unlinkToDeath(deathRecipient, 0) }
        runCatching { serviceBinder?.unlinkToDeath(deathRecipient, 0) }
        dmb = null
        serviceBinder = null
        client = null
        initedMode = 0
        Log.i(TAG, "released")
        setState(State.DISCONNECTED, null)
    }

    private fun onDead(why: String) {
        Log.e(TAG, "session lost: $why")
        dmb = null
        serviceBinder = null
        client = null
        initedMode = 0
        cancelScan()
        setState(State.DEAD, why)
    }

    /** init() in [mode], doing exit() first if the stack is in another one. */
    private fun ensureMode(mode: Int): Boolean {
        if (initedMode == mode) return true
        if (initedMode != 0) {
            guard("exit") { it.exit() } ?: return false
            initedMode = 0
        }
        guard("init($mode, ${Mbt.LIVE_FLAG_ONAIR})") { it.init(mode, Mbt.LIVE_FLAG_ONAIR) }
            ?: return false
        initedMode = mode
        return true
    }

    private fun doTune(channel: Channel) {
        current = channel
        if (dmb == null) {
            status("not connected")
            return
        }
        if (producer == null) {
            // Wait for the surface; setSurface() tunes when it arrives.
            status("waiting for the surface")
            if (state != State.IDLE) setState(State.IDLE, null)
            return
        }
        if (!ensureMode(Mbt.OP_MODE_DMB)) return
        applyVideoDimension()
        val param = channel.toSelectParam()
        status("select ch=${channel.physical} sid=${channel.serviceId} type=${channel.serviceType}")
        guard("select") { it.select(param) } ?: return
        // PSI_INFO tells us the service id actually being received (16 bytes).
        guard("setDataService(PSI_INFO)") { it.setDataService(Mbt.DATA_PSI_INFO, 1) }
        if (muted) guard("setMuteStatus") { it.setMuteStatus(1) }
        setState(State.WATCHING, channel.label)
        // TODO: captions (DATA_TEXT / DATA_TEXT_FULLSEG) and EPG (DATA_EPG) once
        // the byte layouts of those data callbacks are known (tv64 A7).
    }

    private fun applyVideoDimension() {
        val p = producer ?: return
        guard("setVideoDimension") {
            // rotation 0: the meaning of the other values is not known yet.
            it.setVideoDimension(0, 0, surfaceWidth, surfaceHeight, 0, p)
        }
    }

    private fun doScan(channels: IntRange) {
        if (dmb == null) {
            status("not connected")
            post { ui.onScanFinished(emptyList(), true) }
            return
        }
        val previous = current
        setState(State.SCANNING, null)
        if (!ensureMode(Mbt.OP_MODE_ENSQUERY)) {
            if (dmb != null) setState(State.IDLE, null)
            post { ui.onScanFinished(emptyList(), true) }
            return
        }
        guard("setDataService(PSI_INFO)") { it.setDataService(Mbt.DATA_PSI_INFO, 1) }
        guard("setDataService(CHANNEL_SI)") { it.setDataService(Mbt.DATA_CHANNEL_SI, 1) }

        val found = ArrayList<Channel>()
        var cancelled = false
        for (ch in channels) {
            if (scanCancel.get() || dmb == null) {
                cancelled = true
                break
            }
            post { ui.onScanProgress(ch, found.size) }
            val latch = CountDownLatch(1)
            signalLatch.set(latch)
            lastSignal = 0
            lastPsi = null
            val psi = CountDownLatch(1)
            psiLatch.set(psi)
            guard("find($ch)") { it.find(ch) } ?: run { cancelled = true }
            if (cancelled) break
            val answered = await(latch, FIND_TIMEOUT_MS)
            signalLatch.set(null)
            if (!answered) {
                Log.w(TAG, "find($ch): no signal callback in ${FIND_TIMEOUT_MS}ms")
                status("ch $ch: no answer")
                continue
            }
            if (lastSignal == Mbt.SIG_LOCK) {
                // Give the PSI a moment to arrive so the service id can be kept.
                await(psi, PSI_WAIT_MS)
                val info = lastPsi
                val sid = if (info != null && info[0] == ch) info[1] else 0
                found.add(Channel(physical = ch, serviceId = sid))
                status("ch $ch: LOCK${if (sid != 0) " sid=$sid" else ""}")
            }
            psiLatch.set(null)
        }
        signalLatch.set(null)
        psiLatch.set(null)

        guardQuiet("setDataService(CHANNEL_SI, 0)") { dmb?.setDataService(Mbt.DATA_CHANNEL_SI, 0) }
        guardQuiet("setDataService(PSI_INFO, 0)") { dmb?.setDataService(Mbt.DATA_PSI_INFO, 0) }
        // Leave scan mode; watching needs init(DMB).
        if (dmb != null && initedMode != 0) {
            guard("exit") { it.exit() }
            initedMode = 0
        }
        current = previous
        if (dmb != null) setState(State.IDLE, null)
        val result = found.toList()
        post { ui.onScanFinished(result, cancelled) }
    }

    private fun await(latch: CountDownLatch, ms: Long): Boolean = try {
        latch.await(ms, TimeUnit.MILLISECONDS)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    /**
     * Runs [block] against the session. Returns null (and never throws) when
     * there is no session or the call failed; a dead service ends the session.
     */
    private fun <T> guard(what: String, block: (DmbProxy) -> T): T? {
        val session = dmb ?: run {
            status("$what: not connected")
            return null
        }
        return try {
            Log.d(TAG, what)
            block(session)
        } catch (e: DeadObjectException) {
            Log.e(TAG, "$what: service died", e)
            onDead("$what: ${e.javaClass.simpleName}")
            null
        } catch (e: RemoteException) {
            Log.e(TAG, "$what failed", e)
            onDead("$what: $e")
            null
        } catch (e: RuntimeException) {
            Log.e(TAG, "$what threw", e)
            status("$what: $e")
            null
        }
    }

    /** For teardown: log and carry on. */
    private inline fun guardQuiet(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "$what failed during teardown: $e")
        }
    }

    private fun setState(newState: State, detail: String?) {
        state = newState
        Log.i(TAG, "state $newState${detail?.let { " ($it)" } ?: ""}")
        post { ui.onState(newState, detail) }
    }

    private fun status(line: String) {
        Log.i(TAG, line)
        post { ui.onStatus(line) }
    }

    private fun post(block: () -> Unit) {
        main.post(block)
    }

    // ------------------------------------------------------------------
    // Binder threads

    private val listener = object : DmbListener {
        override fun onSignal(lock: Int) {
            lastSignal = lock
            signalLatch.get()?.countDown()
            Log.i(TAG, "signal ${Mbt.name(lock)}")
            post { ui.onSignal(lock) }
        }

        override fun onVideo(what: Int, extra: Int) {
            status("video what=$what extra=$extra")
        }

        override fun onAudio(what: Int, extra: Int) {
            status("audio what=$what extra=$extra")
        }

        override fun onEvent(event: Int) {
            status("event ${Mbt.name(event)}")
            // TODO: TV_JACK_OFF (antenna = earphone cable unplugged) prompt.
        }

        override fun onData(type: Int, extra: Int, data: ByteArray?) {
            val bytes = data ?: ByteArray(0)
            when (type) {
                Mbt.DATA_PSI_INFO -> if (bytes.size >= 16) {
                    // 4 x int32 LE: physical ch, service id, (unknown), recordable.
                    val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    val info = intArrayOf(b.int, b.int, b.int, b.int)
                    lastPsi = info
                    psiLatch.get()?.countDown()
                    status("psi ch=${info[0]} sid=${info[1]} recordable=${info[3]}")
                    post { ui.onPsiInfo(info[0], info[1], info[3]) }
                }
                // TODO: CHANNEL_SI parser (service names / ids during a scan).
                // The layout is not documented yet (tv64/open-questions.md A7),
                // so for now it is only logged.
                else -> Log.i(TAG, "data ${Mbt.dataName(type)} extra=$extra " +
                        "${bytes.size}B ${bytes.take(32).joinToString(" ") { "%02x".format(it) }}")
            }
        }

        override fun onUnknown(msg: Int, ext1: Int, ext2: Int) {
            status("message 0x%x %d %d".format(msg, ext1, ext2))
        }
    }

    companion object {
        private const val TAG = "JoanTv"
        /** DmbProtocol.SERVICE_NAME */
        private const val SERVICE_NAME = "broadcast.dmb"
        private const val FIND_TIMEOUT_MS = 10_000L
        private const val PSI_WAIT_MS = 1_500L
    }
}
