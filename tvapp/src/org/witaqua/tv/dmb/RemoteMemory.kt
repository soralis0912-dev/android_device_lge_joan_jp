/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.witaqua.tv.dmb

import android.os.IBinder
import android.os.Parcel
import android.system.ErrnoException
import android.system.Os
import android.util.Log

/**
 * Reads the bytes behind a remote android::IMemory without native code.
 *
 * Wire format of libbinder's IMemory.cpp (frameworks/native/libs/binder):
 *   IMemory     "android.utils.IMemory"     code 1 GET_MEMORY
 *     reply: strong binder (IMemoryHeap), int64 offset, uint64 size
 *   IMemoryHeap "android.utils.IMemoryHeap" code 1 HEAP_ID
 *     reply: fd, uint64 size, int64 offset, uint32 flags
 *
 * BpMemoryHeap maps the fd at the heap offset and the IMemory lives at its own
 * offset inside that mapping, so the bytes start at heapOffset + offset in the
 * file. pread() reaches them the same way without mapping anything.
 */
internal object RemoteMemory {
    private const val TAG = "JoanTvDmb"
    private const val DESCRIPTOR_MEMORY = "android.utils.IMemory"
    private const val DESCRIPTOR_HEAP = "android.utils.IMemoryHeap"
    private const val GET_MEMORY = IBinder.FIRST_CALL_TRANSACTION
    private const val HEAP_ID = IBinder.FIRST_CALL_TRANSACTION

    /** Nothing the service sends should come close; this only bounds a bad size. */
    private const val MAX_SIZE = 8L shl 20

    fun read(memory: IBinder?): ByteArray? {
        if (memory == null) return null
        try {
            var heap: IBinder? = null
            var offset = 0L
            var size = 0L
            transact(memory, DESCRIPTOR_MEMORY, GET_MEMORY) { reply ->
                heap = reply.readStrongBinder()
                offset = reply.readLong()
                size = reply.readLong()
            }
            val heapBinder = heap ?: return null
            if (size <= 0 || offset < 0 || size > MAX_SIZE) {
                if (size != 0L) Log.w(TAG, "IMemory offset=$offset size=$size rejected")
                return null
            }

            var result: ByteArray? = null
            transact(heapBinder, DESCRIPTOR_HEAP, HEAP_ID) { reply ->
                val pfd = reply.readFileDescriptor() ?: return@transact
                pfd.use {
                    val heapSize = reply.readLong()
                    val heapOffset = reply.readLong()
                    reply.readInt() // flags
                    if (offset + size > heapSize) {
                        Log.w(TAG, "IMemory [$offset,+$size) outside heap of $heapSize")
                        return@transact
                    }
                    val buffer = ByteArray(size.toInt())
                    var done = 0
                    while (done < buffer.size) {
                        val n = Os.pread(it.fileDescriptor, buffer, done, buffer.size - done,
                                heapOffset + offset + done)
                        if (n <= 0) break
                        done += n
                    }
                    result = if (done == buffer.size) buffer else buffer.copyOf(done)
                }
            }
            return result
        } catch (e: ErrnoException) {
            Log.w(TAG, "reading IMemory failed", e)
        } catch (e: Exception) {
            // RemoteException, SecurityException (sepolicy on the fd), ...
            Log.w(TAG, "reading IMemory failed", e)
        }
        return null
    }

    private inline fun transact(binder: IBinder, descriptor: String, code: Int,
                                read: (Parcel) -> Unit) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(descriptor)
            binder.transact(code, data, reply, 0)
            read(reply)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
