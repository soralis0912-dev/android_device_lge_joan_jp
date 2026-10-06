/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.witaqua.tv.dmb

import android.os.IBinder
import android.os.Parcel

/**
 * Wire constants of the "broadcast.dmb" binder interfaces that lgemtvserver
 * publishes. Every value here mirrors device_lge_joan-common/libmtv_jnijp/IDmb.cpp
 * (the C++ proxies known to work against the service); joan-rs/DMB_PROTOCOL.md
 * describes how they were recovered.
 */
internal object DmbProtocol {
    /** Name in the plain servicemanager (service_contexts: mtv_service). */
    const val SERVICE_NAME = "broadcast.dmb"

    const val DESCRIPTOR_SERVICE = "android.broadcast.IDmbService"
    const val DESCRIPTOR_DMB = "android.broadcast.IDmb"
    const val DESCRIPTOR_CLIENT = "android.broadcast.IDmbClient"

    /* IDmbService */
    const val SERVICE_CONNECT = IBinder.FIRST_CALL_TRANSACTION // 1

    /* IDmb. Code 8 is unused on the wire. */
    const val DISCONNECT = IBinder.FIRST_CALL_TRANSACTION // 1
    const val CONNECT = 2
    const val INIT = 3
    const val EXIT = 4
    const val FIND = 5
    const val SELECT = 6
    const val PLAY = 7
    const val STOP_PLAY = 9
    const val RECORD = 10
    const val RECORD_FD = 11
    const val CANCEL_RECORD = 12
    const val STOP_RECORD = 13
    const val SET_DATA_SERVICE = 14
    const val GET_DURATION = 15
    const val SET_VIDEO_DIMENSION = 16
    const val SET_AUDIO = 17
    const val SET_MUTE_STATUS = 18
    const val PAUSE = 19
    const val RESUME = 20
    const val PAUSE_FOR_BACKGROUND_RECORD = 21
    const val RESUME_FOR_BACKGROUND_RECORD = 22
    const val GET_POSITION = 23
    const val FAST_FORWARD = 24
    const val REWIND = 25
    const val GET_CAS_STATUS = 26
    const val GET_DEVICE_ID = 27
    const val CLEAR_CAS_DB = 28
    const val SET_KD = 29
    const val AUTO_SERVICE_CHANGE = 30
    const val GET_PCR = 31

    /* IDmbClient (implemented here, called by the service) */
    const val CLIENT_NOTIFY_CALLBACK = IBinder.FIRST_CALL_TRANSACTION // 1
    const val CLIENT_DATA_CALLBACK = 2

    /**
     * Equivalent of the native Parcel::writeCString(): the UTF-8 bytes plus a
     * terminating NUL, zero padded to a multiple of four, with no length prefix.
     * android.os.Parcel has no public raw-byte writer, so the bytes go in as
     * little-endian int32 words - writeInt32 stores host order, and every
     * Android ABI is little-endian, so the bytes land exactly where
     * writeCString would have put them.
     */
    fun writeCString(parcel: Parcel, value: String?) {
        val text = (value ?: "").toByteArray(Charsets.UTF_8)
        val padded = ByteArray((text.size + 1 + 3) and 3.inv())
        System.arraycopy(text, 0, padded, 0, text.size)
        var i = 0
        while (i < padded.size) {
            val word = (padded[i].toInt() and 0xff) or
                    ((padded[i + 1].toInt() and 0xff) shl 8) or
                    ((padded[i + 2].toInt() and 0xff) shl 16) or
                    ((padded[i + 3].toInt() and 0xff) shl 24)
            parcel.writeInt(word)
            i += 4
        }
    }
}
