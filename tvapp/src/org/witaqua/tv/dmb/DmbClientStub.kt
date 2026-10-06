/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.witaqua.tv.dmb

import android.os.Binder
import android.os.Parcel
import android.util.Log

/**
 * What the app gets told. Same five kinds the stock java wrapper dispatched to,
 * already decoded from the raw service message (see [DmbClientStub]).
 * All of these are called on a binder thread while the service waits for the
 * transaction to return, so implementations must only hand the value off.
 */
interface DmbListener {
    /** [lock] is the full message id: 257 SIG_LOCK, 258 SIG_UNLOCK, ... */
    fun onSignal(lock: Int)
    fun onVideo(what: Int, extra: Int)
    fun onAudio(what: Int, extra: Int)
    /** [type] is the data bit (Mbt.DATA_*), [extra] what the service sent with it. */
    fun onData(type: Int, extra: Int, data: ByteArray?)
    /** [event] is the message id (2049 SRV_OK, 3073.. errors, -32 SYSTEM_ABORTED). */
    fun onEvent(event: Int)
    /** A message whose kind is not known. */
    fun onUnknown(msg: Int, ext1: Int, ext2: Int) {}
}

/**
 * The IDmbClient the service calls back on, implemented directly as a Binder.
 *
 * Wire format (BnDmbClient::onTransact in IDmb.cpp):
 *   1 notifyCallback: TOKEN, int32 msg, int32 ext1, int32 ext2
 *   2 dataCallback:   TOKEN, int32 msg, int32 ext1, strong binder (IMemory, may be null)
 *
 * The service's msg is its own message id, not the 0..4 callback number. The
 * high byte selects the kind, exactly as android_broadcast_dmb.cpp decodes it:
 *   0x01xx signal  -> onSignal(msg)
 *   0x02xx video   -> onVideo(ext1, ext2)
 *   0x04xx audio   -> onAudio(ext1, ext2)
 *   0x08xx, 0x0cxx event -> onEvent(msg), with 0x0c17 reported as -32
 *   dataCallback   -> onData(msg, ext1, bytes)
 */
class DmbClientStub(private val listener: DmbListener) : Binder(DmbProtocol.DESCRIPTOR_CLIENT) {

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            DmbProtocol.CLIENT_NOTIFY_CALLBACK -> {
                if (!enforce(data)) return true
                val msg = data.readInt()
                val ext1 = data.readInt()
                val ext2 = data.readInt()
                dispatchNotify(msg, ext1, ext2)
                return true
            }
            DmbProtocol.CLIENT_DATA_CALLBACK -> {
                if (!enforce(data)) return true
                val msg = data.readInt()
                val ext1 = data.readInt()
                val memory = data.readStrongBinder()
                // Read it now: the heap may be gone once this transaction returns.
                val bytes = RemoteMemory.read(memory)
                Log.v(TAG, "dataCallback msg=$msg ext1=$ext1 bytes=${bytes?.size}")
                guarded { listener.onData(msg, ext1, bytes) }
                return true
            }
            else -> return super.onTransact(code, data, reply, flags)
        }
    }

    private fun dispatchNotify(msg: Int, ext1: Int, ext2: Int) {
        Log.v(TAG, "notifyCallback msg=0x%x ext1=%d ext2=%d".format(msg, ext1, ext2))
        guarded {
            when (msg and 0xff00) {
                0x0100 -> listener.onSignal(msg)
                0x0200 -> listener.onVideo(ext1, ext2)
                0x0400 -> listener.onAudio(ext1, ext2)
                0x0800, 0x0c00 -> listener.onEvent(if (msg == 0x0c17) Mbt.SYSTEM_ABORTED else msg)
                else -> {
                    Log.w(TAG, "notifyCallback: no mapping for msg=$msg (0x%x)".format(msg))
                    listener.onUnknown(msg, ext1, ext2)
                }
            }
        }
    }

    private fun enforce(data: Parcel): Boolean = try {
        data.enforceInterface(DmbProtocol.DESCRIPTOR_CLIENT)
        true
    } catch (e: SecurityException) {
        Log.w(TAG, "callback with a wrong interface token", e)
        false
    }

    /** A bug in a listener must not travel back into the service. */
    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.e(TAG, "listener threw", t)
        }
    }

    companion object {
        private const val TAG = "JoanTvDmb"
    }
}
