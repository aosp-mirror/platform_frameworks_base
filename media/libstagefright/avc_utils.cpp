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

//#define LOG_NDEBUG 0
#define LOG_TAG "avc_utils"
#include <utils/Log.h>

#include "include/avc_utils.h"

#include <media/stagefright/foundation/ABitReader.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MetaData.h>

namespace android {

unsigned parseUE(ABitReader *br) {
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

    unsigned chroma_format_idc = 1;  // 4:2:0 chroma format

    if (profile_idc == 100 || profile_idc == 110
            || profile_idc == 122 || profile_idc == 244
            || profile_idc == 44 || profile_idc == 83 || profile_idc == 86) {
        chroma_format_idc = parseUE(&br);
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

    if (!frame_mbs_only_flag) {
        br.getBits(1);  // mb_adaptive_frame_field_flag
    }

    br.getBits(1);  // direct_8x8_inference_flag

    if (br.getBits(1)) {  // frame_cropping_flag
        unsigned frame_crop_left_offset = parseUE(&br);
        unsigned frame_crop_right_offset = parseUE(&br);
        unsigned frame_crop_top_offset = parseUE(&br);
        unsigned frame_crop_bottom_offset = parseUE(&br);

        unsigned cropUnitX, cropUnitY;
        if (chroma_format_idc == 0  /* monochrome */) {
            cropUnitX = 1;
            cropUnitY = 2 - frame_mbs_only_flag;
        } else {
            unsigned subWidthC = (chroma_format_idc == 3) ? 1 : 2;
            unsigned subHeightC = (chroma_format_idc == 1) ? 2 : 1;

            cropUnitX = subWidthC;
            cropUnitY = subHeightC * (2 - frame_mbs_only_flag);
        }

        ALOGV("frame_crop = (%u, %u, %u, %u), cropUnitX = %u, cropUnitY = %u",
             frame_crop_left_offset, frame_crop_right_offset,
             frame_crop_top_offset, frame_crop_bottom_offset,
             cropUnitX, cropUnitY);

        *width -=
            (frame_crop_left_offset + frame_crop_right_offset) * cropUnitX;
        *height -=
            (frame_crop_top_offset + frame_crop_bottom_offset) * cropUnitY;
    }
}

status_t getNextNALUnit(
        const uint8_t **_data, size_t *_size,
        const uint8_t **nalStart, size_t *nalSize,
        bool startCodeFollows) {
    const uint8_t *data = *_data;
    size_t size = *_size;

    *nalStart = NULL;
    *nalSize = 0;

    if (size == 0) {
        return -EAGAIN;
    }

    // Skip any number of leading 0x00.

    size_t offset = 0;
    while (offset < size && data[offset] == 0x00) {
        ++offset;
    }

    if (offset == size) {
        return -EAGAIN;
    }

    // A valid startcode consists of at least two 0x00 bytes followed by 0x01.

    if (offset < 2 || data[offset] != 0x01) {
        return ERROR_MALFORMED;
    }

    ++offset;

    size_t startOffset = offset;

    for (;;) {
        while (offset < size && data[offset] != 0x01) {
            ++offset;
        }

        if (offset == size) {
            if (startCodeFollows) {
                offset = size + 2;
                break;
            }

            return -EAGAIN;
        }

        if (data[offset - 1] == 0x00 && data[offset - 2] == 0x00) {
            break;
        }

        ++offset;
    }

    size_t endOffset = offset - 2;
    while (endOffset > startOffset + 1 && data[endOffset - 1] == 0x00) {
        --endOffset;
    }

    *nalStart = &data[startOffset];
    *nalSize = endOffset - startOffset;

    if (offset + 2 < size) {
        *_data = &data[offset - 2];
        *_size = size - offset + 2;
    } else {
        *_data = NULL;
        *_size = 0;
    }

    return OK;
}

static sp<ABuffer> FindNAL(
        const uint8_t *data, size_t size, unsigned nalType,
        size_t *stopOffset) {
    const uint8_t *nalStart;
    size_t nalSize;
    while (getNextNALUnit(&data, &size, &nalStart, &nalSize, true) == OK) {
        if ((nalStart[0] & 0x1f) == nalType) {
            sp<ABuffer> buffer = new ABuffer(nalSize);
            memcpy(buffer->data(), nalStart, nalSize);
            return buffer;
        }
    }

    return NULL;
}

const char *AVCProfileToString(uint8_t profile) {
    switch (profile) {
        case kAVCProfileBaseline:
            return "Baseline";
        case kAVCProfileMain:
            return "Main";
        case kAVCProfileExtended:
            return "Extended";
        case kAVCProfileHigh:
            return "High";
        case kAVCProfileHigh10:
            return "High 10";
        case kAVCProfileHigh422:
            return "High 422";
        case kAVCProfileHigh444:
            return "High 444";
        case kAVCProfileCAVLC444Intra:
            return "CAVLC 444 Intra";
        default:   return "Unknown";
    }
}

sp<MetaData> MakeAVCCodecSpecificData(const sp<ABuffer> &accessUnit) {
    const uint8_t *data = accessUnit->data();
    size_t size = accessUnit->size();

    sp<ABuffer> seqParamSet = FindNAL(data, size, 7, NULL);
    if (seqParamSet == NULL) {
        return NULL;
    }

    int32_t width, height;
    FindAVCDimensions(seqParamSet, &width, &height);

    size_t stopOffset;
    sp<ABuffer> picParamSet = FindNAL(data, size, 8, &stopOffset);
    CHECK(picParamSet != NULL);

    size_t csdSize =
        1 + 3 + 1 + 1
        + 2 * 1 + seqParamSet->size()
        + 1 + 2 * 1 + picParamSet->size();

    sp<ABuffer> csd = new ABuffer(csdSize);
    uint8_t *out = csd->data();

    *out++ = 0x01;  // configurationVersion
    memcpy(out, seqParamSet->data() + 1, 3);  // profile/level...

    uint8_t profile = out[0];
    uint8_t level = out[2];

    out += 3;
    *out++ = (0x3f << 2) | 1;  // lengthSize == 2 bytes
    *out++ = 0xe0 | 1;

    *out++ = seqParamSet->size() >> 8;
    *out++ = seqParamSet->size() & 0xff;
    memcpy(out, seqParamSet->data(), seqParamSet->size());
    out += seqParamSet->size();

    *out++ = 1;

    *out++ = picParamSet->size() >> 8;
    *out++ = picParamSet->size() & 0xff;
    memcpy(out, picParamSet->data(), picParamSet->size());

#if 0
    LOGI("AVC seq param set");
    hexdump(seqParamSet->data(), seqParamSet->size());
#endif

    sp<MetaData> meta = new MetaData;
    meta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_VIDEO_AVC);

    meta->setData(kKeyAVCC, kTypeAVCC, csd->data(), csd->size());
    meta->setInt32(kKeyWidth, width);
    meta->setInt32(kKeyHeight, height);

    LOGI("found AVC codec config (%d x %d, %s-profile level %d.%d)",
         width, height, AVCProfileToString(profile), level / 10, level % 10);

    return meta;
}

bool IsIDR(const sp<ABuffer> &buffer) {
    const uint8_t *data = buffer->data();
    size_t size = buffer->size();

    bool foundIDR = false;

    const uint8_t *nalStart;
    size_t nalSize;
    while (getNextNALUnit(&data, &size, &nalStart, &nalSize, true) == OK) {
        CHECK_GT(nalSize, 0u);

        unsigned nalType = nalStart[0] & 0x1f;

        if (nalType == 5) {
            foundIDR = true;
            break;
        }
    }

    return foundIDR;
}

bool IsAVCReferenceFrame(const sp<ABuffer> &accessUnit) {
    const uint8_t *data = accessUnit->data();
    size_t size = accessUnit->size();

    const uint8_t *nalStart;
    size_t nalSize;
    while (getNextNALUnit(&data, &size, &nalStart, &nalSize, true) == OK) {
        CHECK_GT(nalSize, 0u);

        unsigned nalType = nalStart[0] & 0x1f;

        if (nalType == 5) {
            return true;
        } else if (nalType == 1) {
            unsigned nal_ref_idc = (nalStart[0] >> 5) & 3;
            return nal_ref_idc != 0;
        }
    }

    return true;
}

sp<MetaData> MakeAACCodecSpecificData(
        unsigned profile, unsigned sampling_freq_index,
        unsigned channel_configuration) {
    sp<MetaData> meta = new MetaData;
    meta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_AUDIO_AAC);

    CHECK_LE(sampling_freq_index, 11u);
    static const int32_t kSamplingFreq[] = {
        96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
        16000, 12000, 11025, 8000
    };
    meta->setInt32(kKeySampleRate, kSamplingFreq[sampling_freq_index]);
    meta->setInt32(kKeyChannelCount, channel_configuration);

    static const uint8_t kStaticESDS[] = {
        0x03, 22,
        0x00, 0x00,     // ES_ID
        0x00,           // streamDependenceFlag, URL_Flag, OCRstreamFlag

        0x04, 17,
        0x40,                       // Audio ISO/IEC 14496-3
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,

        0x05, 2,
        // AudioSpecificInfo follows

        // oooo offf fccc c000
        // o - audioObjectType
        // f - samplingFreqIndex
        // c - channelConfig
    };
    sp<ABuffer> csd = new ABuffer(sizeof(kStaticESDS) + 2);
    memcpy(csd->data(), kStaticESDS, sizeof(kStaticESDS));

    csd->data()[sizeof(kStaticESDS)] =
        ((profile + 1) << 3) | (sampling_freq_index >> 1);

    csd->data()[sizeof(kStaticESDS) + 1] =
        ((sampling_freq_index << 7) & 0x80) | (channel_configuration << 3);

    meta->setData(kKeyESDS, 0, csd->data(), csd->size());

    return meta;
}

bool ExtractDimensionsFromVOLHeader(
        const uint8_t *data, size_t size, int32_t *width, int32_t *height) {
    ABitReader br(&data[4], size - 4);
    br.skipBits(1);  // random_accessible_vol
    unsigned video_object_type_indication = br.getBits(8);

    CHECK_NE(video_object_type_indication,
             0x21u /* Fine Granularity Scalable */);

    unsigned video_object_layer_verid;
    unsigned video_object_layer_priority;
    if (br.getBits(1)) {
        video_object_layer_verid = br.getBits(4);
        video_object_layer_priority = br.getBits(3);
    }
    unsigned aspect_ratio_info = br.getBits(4);
    if (aspect_ratio_info == 0x0f /* extended PAR */) {
        br.skipBits(8);  // par_width
        br.skipBits(8);  // par_height
    }
    if (br.getBits(1)) {  // vol_control_parameters
        br.skipBits(2);  // chroma_format
        br.skipBits(1);  // low_delay
        if (br.getBits(1)) {  // vbv_parameters
            br.skipBits(15);  // first_half_bit_rate
            CHECK(br.getBits(1));  // marker_bit
            br.skipBits(15);  // latter_half_bit_rate
            CHECK(br.getBits(1));  // marker_bit
            br.skipBits(15);  // first_half_vbv_buffer_size
            CHECK(br.getBits(1));  // marker_bit
            br.skipBits(3);  // latter_half_vbv_buffer_size
            br.skipBits(11);  // first_half_vbv_occupancy
            CHECK(br.getBits(1));  // marker_bit
            br.skipBits(15);  // latter_half_vbv_occupancy
            CHECK(br.getBits(1));  // marker_bit
        }
    }
    unsigned video_object_layer_shape = br.getBits(2);
    CHECK_EQ(video_object_layer_shape, 0x00u /* rectangular */);

    CHECK(br.getBits(1));  // marker_bit
    unsigned vop_time_increment_resolution = br.getBits(16);
    CHECK(br.getBits(1));  // marker_bit

    if (br.getBits(1)) {  // fixed_vop_rate
        // range [0..vop_time_increment_resolution)

        // vop_time_increment_resolution
        // 2 => 0..1, 1 bit
        // 3 => 0..2, 2 bits
        // 4 => 0..3, 2 bits
        // 5 => 0..4, 3 bits
        // ...

        CHECK_GT(vop_time_increment_resolution, 0u);
        --vop_time_increment_resolution;

        unsigned numBits = 0;
        while (vop_time_increment_resolution > 0) {
            ++numBits;
            vop_time_increment_resolution >>= 1;
        }

        br.skipBits(numBits);  // fixed_vop_time_increment
    }

    CHECK(br.getBits(1));  // marker_bit
    unsigned video_object_layer_width = br.getBits(13);
    CHECK(br.getBits(1));  // marker_bit
    unsigned video_object_layer_height = br.getBits(13);
    CHECK(br.getBits(1));  // marker_bit

    unsigned interlaced = br.getBits(1);

    *width = video_object_layer_width;
    *height = video_object_layer_height;

    return true;
}

bool GetMPEGAudioFrameSize(
        uint32_t header, size_t *frame_size,
        int *out_sampling_rate, int *out_channels,
        int *out_bitrate, int *out_num_samples) {
    *frame_size = 0;

    if (out_sampling_rate) {
        *out_sampling_rate = 0;
    }

    if (out_channels) {
        *out_channels = 0;
    }

    if (out_bitrate) {
        *out_bitrate = 0;
    }

    if (out_num_samples) {
        *out_num_samples = 1152;
    }

    if ((header & 0xffe00000) != 0xffe00000) {
        return false;
    }

    unsigned version = (header >> 19) & 3;

    if (version == 0x01) {
        return false;
    }

    unsigned layer = (header >> 17) & 3;

    if (layer == 0x00) {
        return false;
    }

    unsigned protection = (header >> 16) & 1;

    unsigned bitrate_index = (header >> 12) & 0x0f;

    if (bitrate_index == 0 || bitrate_index == 0x0f) {
        // Disallow "free" bitrate.
        return false;
    }

    unsigned sampling_rate_index = (header >> 10) & 3;

    if (sampling_rate_index == 3) {
        return false;
    }

    static const int kSamplingRateV1[] = { 44100, 48000, 32000 };
    int sampling_rate = kSamplingRateV1[sampling_rate_index];
    if (version == 2 /* V2 */) {
        sampling_rate /= 2;
    } else if (version == 0 /* V2.5 */) {
        sampling_rate /= 4;
    }

    unsigned padding = (header >> 9) & 1;

    if (layer == 3) {
        // layer I

        static const int kBitrateV1[] = {
            32, 64, 96, 128, 160, 192, 224, 256,
            288, 320, 352, 384, 416, 448
        };

        static const int kBitrateV2[] = {
            32, 48, 56, 64, 80, 96, 112, 128,
            144, 160, 176, 192, 224, 256
        };

        int bitrate =
            (version == 3 /* V1 */)
                ? kBitrateV1[bitrate_index - 1]
                : kBitrateV2[bitrate_index - 1];

        if (out_bitrate) {
            *out_bitrate = bitrate;
        }

        *frame_size = (12000 * bitrate / sampling_rate + padding) * 4;

        if (out_num_samples) {
            *out_num_samples = 384;
        }
    } else {
        // layer II or III

        static const int kBitrateV1L2[] = {
            32, 48, 56, 64, 80, 96, 112, 128,
            160, 192, 224, 256, 320, 384
        };

        static const int kBitrateV1L3[] = {
            32, 40, 48, 56, 64, 80, 96, 112,
            128, 160, 192, 224, 256, 320
        };

        static const int kBitrateV2[] = {
            8, 16, 24, 32, 40, 48, 56, 64,
            80, 96, 112, 128, 144, 160
        };

        int bitrate;
        if (version == 3 /* V1 */) {
            bitrate = (layer == 2 /* L2 */)
                ? kBitrateV1L2[bitrate_index - 1]
                : kBitrateV1L3[bitrate_index - 1];

            if (out_num_samples) {
                *out_num_samples = 1152;
            }
        } else {
            // V2 (or 2.5)

            bitrate = kBitrateV2[bitrate_index - 1];
            if (out_num_samples) {
                *out_num_samples = 576;
            }
        }

        if (out_bitrate) {
            *out_bitrate = bitrate;
        }

        if (version == 3 /* V1 */) {
            *frame_size = 144000 * bitrate / sampling_rate + padding;
        } else {
            // V2 or V2.5
            *frame_size = 72000 * bitrate / sampling_rate + padding;
        }
    }

    if (out_sampling_rate) {
        *out_sampling_rate = sampling_rate;
    }

    if (out_channels) {
        int channel_mode = (header >> 6) & 3;

        *out_channels = (channel_mode == 3) ? 1 : 2;
    }

    return true;
}

}  // namespace android

