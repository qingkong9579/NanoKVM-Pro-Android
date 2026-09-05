package com.nanokvm.app.data.ws

/**
 * Minimal H.264 SPS parser → decoded width/height.
 *
 * Handles the common profile block (baseline/main/high and the extended chroma
 * profiles present in bitstreams from HW encoders). Returns null on the rare
 * cases we deliberately don't model (presence of scaling matrices, SPS too short)
 * so the caller can fall back to a codec-configured stream.
 */
object H264Sps {

    private val CHROMA_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

    /** @param sps raw SPS NAL: start code stripped, first byte is the type (0x67). */
    fun parseWidthHeight(sps: ByteArray): Pair<Int, Int>? {
        if (sps.size < 5) return null
        val profileIdc = sps[1].toInt() and 0xff
        // The SPS NAL begins with its type byte (0x67) — start reading after it.
        val br = BitReader(sps, startBit = 8)

        br.readBits(8)   // profile_idc
        br.readBits(8)   // constraint flags
        br.readBits(8)   // level_idc
        br.ue()          // seq_parameter_set_id

        if (profileIdc in CHROMA_PROFILES) {
            val chromaFormat = br.ue()
            if (chromaFormat == 3) br.readBits(1) // separate_colour_plane_flag
            br.ue() // bit_depth_luma_minus8
            br.ue() // bit_depth_chroma_minus8
            br.readBits(1) // qpprime_y_zero_transform_bypass_flag
            if (br.readBits(1) == 1) return null // seq_scaling_matrix_present — uncommon, bail
        }

        br.ue() // log2_max_frame_num_minus4
        val pocType = br.ue()
        if (pocType == 0) {
            br.ue() // log2_max_pic_order_cnt_lsb_minus4
        } else if (pocType == 1) {
            br.readBits(1) // delta_pic_order_always_zero_flag
            br.se() // offset_for_non_ref_pic
            br.se() // offset_for_top_to_bottom_field
            val cycle = br.ue() // num_ref_frames_in_pic_order_cnt_cycle
            repeat(cycle.coerceAtMost(64)) { br.se() }
        }
        br.ue() // max_num_ref_frames
        br.readBits(1) // gaps_in_frame_num_value_allowed_flag

        val picWidthMbs = br.ue() + 1
        val picHeightMapUnits = br.ue() + 1
        val frameMbsOnly = br.readBits(1)
        if (frameMbsOnly == 0) br.readBits(1) // mb_adaptive_frame_field_flag
        br.readBits(1) // direct_8x8_inference_flag

        var width = picWidthMbs * 16
        var height = (2 - frameMbsOnly) * picHeightMapUnits * 16

        if (br.readBits(1) == 1) { // frame_cropping_flag
            val cropLeft = br.ue()
            val cropRight = br.ue()
            val cropTop = br.ue()
            val cropBottom = br.ue()
            // cropping unit: 2 for chroma 4:2:2 and monochrome, 1 for 4:2:0
            val subWidthC = 1 // 4:2:0 progressive; chroma sample offset scaling
            val subHeightC = if (frameMbsOnly == 1) 1 else 2
            width -= (cropLeft + cropRight) * subWidthC * 2
            height -= (cropTop + cropBottom) * subHeightC * 2
        }

        if (width <= 0 || height <= 0) return null
        return width to height
    }

    private class BitReader(private val data: ByteArray, startBit: Int = 0) {
        private var bit = startBit

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

        /** Exp-Golomb unsigned (ue). */
        fun ue(): Int {
            var zeros = 0
            while (readBit() == 0) zeros++
            return (1 shl zeros) - 1 + if (zeros == 0) 0 else readBits(zeros)
        }

        /** Exp-Golomb signed (se). */
        fun se(): Int {
            val codeNum = ue()
            if (codeNum == 0) return 0
            return if ((codeNum and 1) == 0) -(codeNum ushr 1) else (codeNum + 1) ushr 1
        }
    }
}