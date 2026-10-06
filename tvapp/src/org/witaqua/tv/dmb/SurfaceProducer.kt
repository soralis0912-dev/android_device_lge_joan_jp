/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.witaqua.tv.dmb

import android.os.IBinder
import android.os.Parcel
import android.util.Log
import android.view.Surface

/**
 * Gets the IGraphicBufferProducer binder out of a Surface, in Kotlin.
 *
 * IDmb::setVideoDimension wants writeStrongBinder(IGraphicBufferProducer) -
 * the same object the in-tree JNI takes from Surface::getIGraphicBufferProducer().
 * A Surface parcels itself as (Surface.java + libgui view/Surface.cpp, same in
 * 15.2 and 16.2):
 *
 *   String16 name, int32 isSingleBuffered,          (written by Surface.java)
 *   uint32 'bque' (0x62717565), strong binder IGBP,  (IGraphicBufferProducer::exportToParcel)
 *   strong binder surfaceControlHandle
 *
 * so writing it to a scratch parcel and reading the binder back gives exactly
 * that producer, and no native library is needed.
 */
object SurfaceProducer {
    private const val TAG = "JoanTvDmb"
    private const val USE_BUFFER_QUEUE = 0x62717565 // 'bque'
    private const val IGBP_DESCRIPTOR = "android.gui.IGraphicBufferProducer"

    fun of(surface: Surface?): IBinder? {
        if (surface == null || !surface.isValid) return null
        val p = Parcel.obtain()
        try {
            surface.writeToParcel(p, 0)
            p.setDataPosition(0)
            p.readString() // name
            p.readInt() // isSingleBuffered
            val magic = p.readInt()
            if (magic != USE_BUFFER_QUEUE) {
                Log.e(TAG, "unexpected Surface parcel magic 0x%x".format(magic))
                return null
            }
            val producer = p.readStrongBinder()
            val descriptor = try {
                producer?.interfaceDescriptor
            } catch (e: Exception) {
                null
            }
            if (descriptor != IGBP_DESCRIPTOR) {
                Log.w(TAG, "Surface producer descriptor is '$descriptor'")
            }
            return producer
        } catch (e: RuntimeException) {
            Log.e(TAG, "could not unparcel the Surface", e)
            return null
        } finally {
            p.recycle()
        }
    }
}
