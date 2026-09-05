package com.nanokvm.app.data.ws

/**
 * Minimal HEVC SPS parser → decoded width/height.
 * Input is the raw SPS NAL **including** its 2-byte HEVC NAL header.
 */
object H265Sps {

    fun parseWidthHeight(sps: ByteArray): Pair<Int, Int>? {
        if (sps.size < 16) return null
        val br = BitReader(sps)

        br.readBits(16) // HEVC NAL header (forbidden_zero_bit, nal_unit_type, layer/temp ids)

        br.readBits(4)  // sps_video_parameter_set_id
        val maxSubLayersMinus1 = br.readBits(3)
        br.readBits(1)  // sps_temporal_id_nesting_flag

        // profile_tier_level()
        br.readBits(2)  // general_profile_space
        br.readBits(1)  // general_tier_flag
        br.readBits(5)  // general_profile_idc
        br.readBits(32) // general_profile_compatibility_flag
        br.readBits(1) + br.readBits(1) + br.readBits(1) + br.readBits(1) // progressive/interlaced/non_packed/frame_only
        br.readBits(44) // general_reserved_zero_44bits
        br.readBits(8)  // general_level_idc

        val subLayerProfilePresent = BooleanArray(maxSubLayersMinus1.coerceAtLeast(0))
        val subLayerLevelPresent = BooleanArray(maxSubLayersMinus1.coerceAtLeast(0))
        for (i in 0 until maxSubLayersMinus1) {
            subLayerProfilePresent[i] = br.readBits(1) == 1
            subLayerLevelPresent[i] = br.readBits(1) == 1
        }
        if (maxSubLayersMinus1 > 0) br.readBits(2) // reserved_zero_2bits
        for (i in 0 until maxSubLayersMinus1) {
            if (subLayerProfilePresent[i]) {
                br.readBits(2); br.readBits(1); br.readBits(5); br.readBits(32)
                br.readBits(1) + br.readBits(1) + br.readBits(1) + br.readBits(1)
                br.readBits(44)
            }
            if (subLayerLevelPresent[i]) br.readBits(8)
        }

        br.ue() // sps_seq_parameter_set_id
        val chromaFormatIdc = br.ue()
        if (chromaFormatIdc == 3) br.readBits(1) // separate_colour_plane_flag

        val picWidth = br.ue()
        val picHeight = br.ue()

        val (subWidthC, subHeightC) = when (chromaFormatIdc) {
            0 -> 1 to 1   // monochrome (4:0:0)
            2 -> 2 to 1   // 4:2:2
            3 -> 1 to 1   // 4:4:4
            else -> 2 to 2 // 4:2:0
        }

        var width = picWidth
        var height = picHeight
        if (br.readBits(1) == 1) { // conformance_window_flag
            val left = br.ue()
            val right = br.ue()
            val top = br.ue()
            val bottom = br.ue()
            width -= (left + right) * subWidthC
            height -= (top + bottom) * subHeightC
        }

        if (width <= 0 || height <= 0) return null
        return width to height
    }

    private class BitReader(private val data: ByteArray) {
        private var bit = 0

        fun readBit(): Int {
            val byte = data[bit ushr 3].toInt() and 0xff
            val v = (byte ushr (7 - (bit and 7))) and 1
            bit++
            return v
        }

        fun readBits(n: Int): Int {
            var v = 0
            repeat(n) { v = (v shl 1) or readBit() }
            return v
        }

        fun ue(): Int {
            var zeros = 0
            while (readBit() == 0) zeros++
            return (1 shl zeros) - 1 + if (zeros == 0) 0 else readBits(zeros)
        }
    }
}