/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.witaqua.tv.dmb

/**
 * The numbers the service speaks in. They are bare ints on the wire; the values
 * were collected for interoperability in docs/tv-tuner-test.md and
 * tv64/interfaces.md section 1.2.
 */
object Mbt {
    /* init() op modes. Changing mode means exit() and init() again. */
    const val OP_MODE_DMB = 2 // watching / recording
    const val OP_MODE_ENSQUERY = 6 // scanning, which find() needs

    /* init() live flag */
    const val LIVE_FLAG_LOCAL = 0 // playing a file back
    const val LIVE_FLAG_ONAIR = 1

    /* signal callback */
    const val SIG_LOCK = 257
    const val SIG_UNLOCK = 258

    /* event callback (selection) */
    const val SYSTEM_ABORTED = -32
    const val SRV_OK = 2049
    const val TV_JACK_ON = 2050
    const val TV_JACK_OFF = 2051
    const val NOSIG_TIMEOUT = 2052
    const val PLAY_READY_SRV = 2066
    const val SELECT_READY = 2080
    const val FIRST_ERROR = 3073
    const val LAST_ERROR = 3094

    /* data types: the bit setDataService() takes and data callbacks report */
    const val DATA_CHANNEL_SI = 4
    const val DATA_SI_INFO = 16
    const val DATA_PSI_INFO = 32
    const val DATA_TEXT = 64
    const val DATA_EPG = 128
    const val DATA_TOT = 2048
    const val DATA_TEXT_FULLSEG = 4096
    const val DATA_RESOLUTION_UPDATED = 131072

    /** Japanese terrestrial UHF channels. */
    val UHF_CHANNELS = 13..52

    private val names = mapOf(
        -32 to "SYSTEM_ABORTED",
        257 to "SIG_LOCK",
        258 to "SIG_UNLOCK",
        2049 to "SRV_OK",
        2050 to "TV_JACK_ON",
        2051 to "TV_JACK_OFF",
        2052 to "NOSIG_TIMEOUT",
        2053 to "PAR_FILE_FULL",
        2054 to "PAR_FILE_END",
        2064 to "PCOUT_CHANNEL_SI_GET",
        2065 to "SRV_INFO_UPDATE",
        2066 to "PLAY_READY_SRV",
        2068 to "VIDEO_RESOLUTION_NORMAL",
        2069 to "VIDEO_RESOLUTION_WIDE",
        2070 to "SRV_OFF",
        2071 to "CAS_CHK",
        2072 to "EMM_CHK",
        2080 to "SELECT_READY",
        3073 to "ERROR_SET_CHANNEL",
        3074 to "ERROR_INIT_TUNER",
        3075 to "ERROR_INIT_BB",
        3076 to "ERROR_DECODE_VIDEO",
        3077 to "ERROR_DECODE_AUDIO",
        3078 to "ERROR_UNLOCK_CIR",
        3079 to "ERROR_UNLOCK_SYNC",
        3080 to "ERROR_UNLOCK_TP",
        3081 to "ERROR_RECEIVE_SIGNAL_STRENGTH",
        3082 to "ERROR_RESET_SYSTEM",
        3083 to "ERROR_RECEIVE_DATA",
        3084 to "ERROR_EXIT_SRV",
        3085 to "ERROR_SET_AUTO_SEARCHING",
        3086 to "ERROR_RECEIVE_CHANNEL_SI",
        3087 to "ERROR_CAPTURE_IMAGE",
        3088 to "ERROR_FILE_FULL",
        3089 to "ERROR_FILE_EMPTY",
        3090 to "ERROR_FILE_OPEN",
        3091 to "ERROR_FILE_READ",
        3092 to "ERROR_FILE_WRITE",
        3093 to "ERROR_FILE_STOP",
        3094 to "ERROR_FILE_PLAY",
    )

    private val dataNames = mapOf(
        1 to "DLS", 2 to "SLS", 4 to "CHANNEL_SI", 16 to "SI_INFO", 32 to "PSI_INFO",
        64 to "TEXT", 128 to "EPG", 256 to "TS", 512 to "MULTI_SID", 1024 to "MULTI_ES",
        2048 to "TOT", 4096 to "TEXT_FULLSEG", 8192 to "TEXT_SUPER_IM",
        16384 to "EPG_SCHEDULE", 32768 to "CDT_LOGO", 65536 to "DSMCC_SECTION",
        131072 to "RESOLUTION_UPDATED", 262144 to "CAS_DEVICEID_UPDATED",
        524288 to "TEXT_MNG", 1048576 to "TEXT_SUPER_MNG", 2097152 to "REQ_CHG_SERVICE",
    )

    fun name(value: Int): String = names[value] ?: value.toString()

    fun dataName(value: Int): String = dataNames[value] ?: "type $value"

    fun isError(event: Int) = event == SYSTEM_ABORTED || event in FIRST_ERROR..LAST_ERROR

    /** Centre frequency in kHz (ISDB-T: 473 MHz + 1/7 MHz for ch13, 6 MHz steps). */
    fun frequencyKhz(channel: Int) = 473143 + (channel - 13) * 6000
}
