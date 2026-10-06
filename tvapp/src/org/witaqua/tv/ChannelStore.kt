/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.witaqua.tv

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.witaqua.tv.dmb.Mbt
import org.witaqua.tv.dmb.SelectParam

/**
 * One tunable entry. Everything select() needs; zero means "not known", and the
 * PIDs go out as 0x1fff in that case (see SelectParam.withNullPids).
 */
data class Channel(
    val physical: Int,
    val name: String = "",
    val serviceId: Int = 0,
    val serviceType: Int = DEFAULT_SERVICE_TYPE,
    val serviceId1Seg: Int = 0,
    val netId: Int = 0,
    val transportId: Int = 0,
    val oriNetId: Int = 0,
    val channelId: Int = 0,
    val pmtPid: Int = 0,
    val videoPid: Int = 0,
    val audioPid: Int = 0,
    val emmPid: Int = 0,
    val ecmPid: Int = 0,
) {
    val label: String
        get() = name.ifEmpty { "UHF $physical" }

    fun toSelectParam() = SelectParam(
        opMode = Mbt.OP_MODE_DMB,
        freq = physical,
        serviceId = serviceId,
        netId = netId,
        transportId = transportId,
        oriNetId = oriNetId,
        channelId = channelId,
        serviceType = serviceType,
        pmtPid = pmtPid,
        videoPid = videoPid,
        audioPid = audioPid,
        emmPid = emmPid,
        ecmPid = ecmPid,
        serviceId1Seg = serviceId1Seg,
    ).withNullPids()

    fun toJson(): JSONObject = JSONObject()
        .put("physical", physical)
        .put("name", name)
        .put("serviceId", serviceId)
        .put("serviceType", serviceType)
        .put("serviceId1Seg", serviceId1Seg)
        .put("netId", netId)
        .put("transportId", transportId)
        .put("oriNetId", oriNetId)
        .put("channelId", channelId)
        .put("pmtPid", pmtPid)
        .put("videoPid", videoPid)
        .put("audioPid", audioPid)
        .put("emmPid", emmPid)
        .put("ecmPid", ecmPid)

    companion object {
        /**
         * What JoanTvTest selects with. The meaning of the service type values
         * (and how full-seg vs one-seg is chosen) is not recovered yet: init()
         * takes no segment, so it is presumably serviceType / serviceId1Seg.
         */
        const val DEFAULT_SERVICE_TYPE = 1

        fun fromJson(o: JSONObject) = Channel(
            physical = o.getInt("physical"),
            name = o.optString("name", ""),
            serviceId = o.optInt("serviceId"),
            serviceType = o.optInt("serviceType", DEFAULT_SERVICE_TYPE),
            serviceId1Seg = o.optInt("serviceId1Seg"),
            netId = o.optInt("netId"),
            transportId = o.optInt("transportId"),
            oriNetId = o.optInt("oriNetId"),
            channelId = o.optInt("channelId"),
            pmtPid = o.optInt("pmtPid"),
            videoPid = o.optInt("videoPid"),
            audioPid = o.optInt("audioPid"),
            emmPid = o.optInt("emmPid"),
            ecmPid = o.optInt("ecmPid"),
        )
    }
}

/** The channel list and the last channel watched, as JSON in SharedPreferences. */
class ChannelStore(context: Context) {
    private val prefs = context.getSharedPreferences("channels", Context.MODE_PRIVATE)

    fun load(): List<Channel> {
        val json = prefs.getString(KEY_CHANNELS, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { Channel.fromJson(array.getJSONObject(it)) }
        } catch (e: JSONException) {
            Log.w(TAG, "stored channel list is corrupt; ignoring it", e)
            emptyList()
        }
    }

    fun save(channels: List<Channel>) {
        val array = JSONArray()
        channels.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_CHANNELS, array.toString()).apply()
    }

    var lastIndex: Int
        get() = prefs.getInt(KEY_LAST, 0)
        set(value) = prefs.edit().putInt(KEY_LAST, value).apply()

    var muted: Boolean
        get() = prefs.getBoolean(KEY_MUTED, false)
        set(value) = prefs.edit().putBoolean(KEY_MUTED, value).apply()

    companion object {
        private const val TAG = "JoanTv"
        private const val KEY_CHANNELS = "channels"
        private const val KEY_LAST = "last_index"
        private const val KEY_MUTED = "muted"
    }
}
