/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.witaqua.tv.dmb

import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import java.io.FileDescriptor

/**
 * The fourteen int32 that IDmb::select carries, in wire order (IDmb.h SelectParam).
 */
data class SelectParam(
    val opMode: Int,
    val freq: Int, // the physical UHF channel number (13..52), not a frequency
    val serviceId: Int = 0,
    val netId: Int = 0,
    val transportId: Int = 0,
    val oriNetId: Int = 0,
    val channelId: Int = 0,
    val serviceType: Int = 0,
    val pmtPid: Int = 0,
    val videoPid: Int = 0,
    val audioPid: Int = 0,
    val emmPid: Int = 0,
    val ecmPid: Int = 0,
    val serviceId1Seg: Int = 0,
) {
    /**
     * The java wrapper the stock stack shipped with sent 0x1fff (the null PID)
     * for any PID left at 0 (docs/tv-tuner-test.md, docs/fullseg-tv.md), so
     * the service has only ever seen that value for "no PID". Do the same.
     */
    fun withNullPids(): SelectParam {
        fun fix(pid: Int) = if (pid == 0) NULL_PID else pid
        return copy(
            pmtPid = fix(pmtPid),
            videoPid = fix(videoPid),
            audioPid = fix(audioPid),
            emmPid = fix(emmPid),
            ecmPid = fix(ecmPid),
        )
    }

    internal fun writeTo(p: Parcel) {
        p.writeInt(opMode)
        p.writeInt(freq)
        p.writeInt(serviceId)
        p.writeInt(netId)
        p.writeInt(transportId)
        p.writeInt(oriNetId)
        p.writeInt(channelId)
        p.writeInt(serviceType)
        p.writeInt(pmtPid)
        p.writeInt(videoPid)
        p.writeInt(audioPid)
        p.writeInt(emmPid)
        p.writeInt(ecmPid)
        p.writeInt(serviceId1Seg)
    }

    companion object {
        const val NULL_PID = 0x1fff
    }
}

/**
 * Client-side proxy of IDmbService, the object registered as "broadcast.dmb".
 * Mirrors BpDmbService in IDmb.cpp.
 */
class DmbServiceProxy(val binder: IBinder) {

    /**
     * code 1: TOKEN, writeStrongBinder(client) -> readStrongBinder() (IDmb).
     * Returns null when the service handed back no session.
     */
    @Throws(RemoteException::class)
    fun connect(client: IBinder): DmbProxy? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DmbProtocol.DESCRIPTOR_SERVICE)
            data.writeStrongBinder(client)
            binder.transact(DmbProtocol.SERVICE_CONNECT, data, reply, 0)
            val session = reply.readStrongBinder() ?: return null
            return DmbProxy(session)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}

/**
 * Client-side proxy of IDmb. One method per transaction, each writing exactly
 * what BpDmb in IDmb.cpp writes: the IDmb interface token, then the arguments
 * in order. All calls are synchronous (flags 0), like the C++ proxy, so they
 * must not be made on the UI thread.
 *
 * Every method throws RemoteException (DeadObjectException when lgemtvserver
 * died); the caller decides what to do about it.
 */
class DmbProxy(val binder: IBinder) {

    private inline fun call(code: Int, write: (Parcel) -> Unit = {}) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DmbProtocol.DESCRIPTOR_DMB)
            write(data)
            binder.transact(code, data, reply, 0)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private inline fun <T> callFor(code: Int, write: (Parcel) -> Unit, read: (Parcel) -> T): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DmbProtocol.DESCRIPTOR_DMB)
            write(data)
            binder.transact(code, data, reply, 0)
            return read(reply)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private inline fun callInt(code: Int, write: (Parcel) -> Unit = {}): Int =
        callFor(code, write) { it.readInt() }

    /** 1 */
    @Throws(RemoteException::class)
    fun disconnect() = call(DmbProtocol.DISCONNECT)

    /**
     * 2: wBinder -> rI32. Not used by the in-tree JNI (it only calls
     * IDmbService::connect, which already registers the client); kept for
     * completeness, in case the service turns out to want it after connect.
     */
    @Throws(RemoteException::class)
    fun connect(client: IBinder): Int =
        callInt(DmbProtocol.CONNECT) { it.writeStrongBinder(client) }

    /** 3: init(opMode, liveFlag) */
    @Throws(RemoteException::class)
    fun init(opMode: Int, liveFlag: Int) = call(DmbProtocol.INIT) {
        it.writeInt(opMode)
        it.writeInt(liveFlag)
    }

    /** 4 */
    @Throws(RemoteException::class)
    fun exit() = call(DmbProtocol.EXIT)

    /** 5: find(physicalChannel). The answer comes back as a signal callback. */
    @Throws(RemoteException::class)
    fun find(physicalChannel: Int) = call(DmbProtocol.FIND) { it.writeInt(physicalChannel) }

    /** 6: 14 x int32 */
    @Throws(RemoteException::class)
    fun select(param: SelectParam) = call(DmbProtocol.SELECT) { param.writeTo(it) }

    /**
     * 7: wI32 wCStr wI64 wI32 wI32. The last two are C `long` in a 32-bit
     * build, so they travel as int32 whatever the caller's bitness.
     */
    @Throws(RemoteException::class)
    fun play(mode: Int, path: String?, position: Long, arg1: Int, arg2: Int) =
        call(DmbProtocol.PLAY) {
            it.writeInt(mode)
            DmbProtocol.writeCString(it, path)
            it.writeLong(position)
            it.writeInt(arg1)
            it.writeInt(arg2)
        }

    /** 9 */
    @Throws(RemoteException::class)
    fun stopPlay() = call(DmbProtocol.STOP_PLAY)

    /** 10: wI32 wCStr wI64 -> rI32. freeSizeKb as the stock app passes it. */
    @Throws(RemoteException::class)
    fun record(mode: Int, path: String, freeSizeKb: Long): Int = callInt(DmbProtocol.RECORD) {
        it.writeInt(mode)
        DmbProtocol.writeCString(it, path)
        it.writeLong(freeSizeKb)
    }

    /** 11: wI32 wFileDescriptor wI64 -> rI32 */
    @Throws(RemoteException::class)
    fun record(mode: Int, fd: FileDescriptor, freeSizeKb: Long): Int =
        callInt(DmbProtocol.RECORD_FD) {
            it.writeInt(mode)
            it.writeFileDescriptor(fd)
            it.writeLong(freeSizeKb)
        }

    /** 12 */
    @Throws(RemoteException::class)
    fun cancelRecord() = call(DmbProtocol.CANCEL_RECORD)

    /** 13 */
    @Throws(RemoteException::class)
    fun stopRecord() = call(DmbProtocol.STOP_RECORD)

    /** 14: setDataService(type bit, on/off) - what the java wrapper calls data(). */
    @Throws(RemoteException::class)
    fun setDataService(type: Int, onOff: Int) = call(DmbProtocol.SET_DATA_SERVICE) {
        it.writeInt(type)
        it.writeInt(onOff)
    }

    /** 15 -> rI32 */
    @Throws(RemoteException::class)
    fun getDuration(): Int = callInt(DmbProtocol.GET_DURATION)

    /**
     * 16: wI32 x5, wBinder(IGraphicBufferProducer). [producer] is the binder
     * from [SurfaceProducer.of]; null is written as a null binder, like the
     * C++ proxy does for a null sp<>.
     */
    @Throws(RemoteException::class)
    fun setVideoDimension(x: Int, y: Int, width: Int, height: Int, rotation: Int,
                          producer: IBinder?) = call(DmbProtocol.SET_VIDEO_DIMENSION) {
        it.writeInt(x)
        it.writeInt(y)
        it.writeInt(width)
        it.writeInt(height)
        it.writeInt(rotation)
        it.writeStrongBinder(producer)
    }

    /** 17: three int32 whose meaning is not known yet. */
    @Throws(RemoteException::class)
    fun setAudio(arg1: Int, arg2: Int, arg3: Int) = call(DmbProtocol.SET_AUDIO) {
        it.writeInt(arg1)
        it.writeInt(arg2)
        it.writeInt(arg3)
    }

    /** 18: wI32 -> rI32 */
    @Throws(RemoteException::class)
    fun setMuteStatus(mute: Int): Int = callInt(DmbProtocol.SET_MUTE_STATUS) { it.writeInt(mute) }

    /** 19 */
    @Throws(RemoteException::class)
    fun pause() = call(DmbProtocol.PAUSE)

    /** 20: wI32 -> rI32 */
    @Throws(RemoteException::class)
    fun resume(arg: Int): Int = callInt(DmbProtocol.RESUME) { it.writeInt(arg) }

    /** 21: wI32 -> rI32 */
    @Throws(RemoteException::class)
    fun pauseForBackgroundRecord(arg: Int): Int =
        callInt(DmbProtocol.PAUSE_FOR_BACKGROUND_RECORD) { it.writeInt(arg) }

    /** 22: wI32 -> rI32 */
    @Throws(RemoteException::class)
    fun resumeForBackgroundRecord(arg: Int): Int =
        callInt(DmbProtocol.RESUME_FOR_BACKGROUND_RECORD) { it.writeInt(arg) }

    /** 23 -> rI32 */
    @Throws(RemoteException::class)
    fun getPosition(): Int = callInt(DmbProtocol.GET_POSITION)

    /** 31 -> rI64 (sits between getPosition and fastForward in the vtable only) */
    @Throws(RemoteException::class)
    fun getPCR(): Long = callFor(DmbProtocol.GET_PCR, {}) { it.readLong() }

    /** 24: wI32 -> rI32 */
    @Throws(RemoteException::class)
    fun fastForward(speed: Int): Int = callInt(DmbProtocol.FAST_FORWARD) { it.writeInt(speed) }

    /** 25: wI32 -> rI32 */
    @Throws(RemoteException::class)
    fun rewind(speed: Int): Int = callInt(DmbProtocol.REWIND) { it.writeInt(speed) }

    /** 26 -> rI32 */
    @Throws(RemoteException::class)
    fun getCASStatus(): Int = callInt(DmbProtocol.GET_CAS_STATUS)

    /** 27: wI32. The id comes back through dataCallback. */
    @Throws(RemoteException::class)
    fun getDeviceID(type: Int) = call(DmbProtocol.GET_DEVICE_ID) { it.writeInt(type) }

    /** 28 */
    @Throws(RemoteException::class)
    fun clearCASDB() = call(DmbProtocol.CLEAR_CAS_DB)

    /** 29: wI32 wI32 */
    @Throws(RemoteException::class)
    fun setKd(arg1: Int, arg2: Int) = call(DmbProtocol.SET_KD) {
        it.writeInt(arg1)
        it.writeInt(arg2)
    }

    /** 30: two C `long` of a 32-bit build, so two int32 on the wire. */
    @Throws(RemoteException::class)
    fun autoServiceChange(arg1: Int, arg2: Int) = call(DmbProtocol.AUTO_SERVICE_CHANGE) {
        it.writeInt(arg1)
        it.writeInt(arg2)
    }
}
