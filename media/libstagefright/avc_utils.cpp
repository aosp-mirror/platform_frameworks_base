/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "include/avc_utils.h"

#include <media/stagefright/foundation/ABitReader.h>
#include <media/stagefright/foundation/ADebug.h>

namespace android {

static unsigned parseUE(ABitReader *br) {
    unsigned numZeroes = 0;
    while (br->getBits(1) == 0) {
        ++numZeroes;
    }

    unsigned x = br->getBits(numZeroes);

    return x + (1u << numZeroes) - 1;
}

// Determine video dimensions from the sequence parameterset.
void FindAVCDimensions(
        const sp<ABuffer> &seqParamSet, int32_t *width, int32_t *height) {
    ABitReader br(seqParamSet->data() + 1, seqParamSet->size() - 1);

    unsigned profile_idc = br.getBits(8);
    br.skipBits(16);
    parseUE(&br);  // seq_parameter_set_id

    if (profile_idc == 100 || profile_idc == 110
            || profile_idc == 122 || profile_idc == 244
            || profile_idc == 44 || profile_idc == 83 || profile_idc == 86) {
        unsigned chroma_format_idc = parseUE(&br);
        if (chroma_format_idc == 3) {
            br.skipBits(1);  // residual_colour_transform_flag
        }
        parseUE(&br);  // bit_depth_luma_minus8
        parseUE(&br);  // bit_depth_chroma_minus8
        br.skipBits(1);  // qpprime_y_zero_transform_bypass_flag
        CHECK_EQ(br.getBits(1), 0u);  // seq_scaling_matrix_present_flag
    }

    parseUE(&br);  // log2_max_frame_num_minus4
    unsigned pic_order_cnt_type = parseUE(&br);

    if (pic_order_cnt_type == 0) {
        parseUE(&br);  // log2_max_pic_order_cnt_lsb_minus4
    } else if (pic_order_cnt_type == 1) {
        // offset_for_non_ref_pic, offset_for_top_to_bottom_field and
        // offset_for_ref_frame are technically se(v), but since we are
        // just skipping over them the midpoint does not matter.

        br.getBits(1);  // delta_pic_order_always_zero_flag
        parseUE(&br);  // offset_for_non_ref_pic
        parseUE(&br);  // offset_for_top_to_bottom_field

        unsigned num_ref_frames_in_pic_order_cnt_cycle = parseUE(&br);
        for (unsigned i = 0; i < num_ref_frames_in_pic_order_cnt_cycle; ++i) {
            parseUE(&br);  // offset_for_ref_frame
        }
    }

    parseUE(&br);  // num_ref_frames
    br.getBits(1);  // gaps_in_frame_num_value_allowed_flag

    unsigned pic_width_in_mbs_minus1 = parseUE(&br);
    unsigned pic_height_in_map_units_minus1 = parseUE(&br);
    unsigned frame_mbs_only_flag = br.getBits(1);

    *width = pic_width_in_mbs_minus1 * 16 + 16;

    *height = (2 - frame_mbs_only_flag)
        * (pic_height_in_map_units_minus1 * 16 + 16);
}

}  // namespace android

