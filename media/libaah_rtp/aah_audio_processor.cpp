/*
 * Copyright (C) 2012 The Android Open Source Project
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

#define LOG_TAG "LibAAH_RTP"
//#define LOG_NDEBUG 0
#include <utils/Log.h>

#include <common_time/cc_helper.h>
#include <media/AudioSystem.h>
#include <media/AudioTrack.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/OMXClient.h>
#include <media/stagefright/OMXCodec.h>
#include <media/stagefright/Utils.h>
#include <utils/Timers.h>
#include <utils/threads.h>

#include <stdio.h>

#include "aah_audio_processor.h"

extern void fixed_fft_real(int n, int32_t *v);

#define CLIP(x, low, high) ((x) < (low) ? (low) : ((x) > (high) ? (high) : (x)))

#define SAMPLES_TO_TS(samples, sample_rate) \
    ( static_cast<int64_t>(samples) * 1000000 / (sample_rate) )


// fill mono audio data into 16 bits workspace
template<int BITS, typename SRCTYPE>
static inline void fillWorkspaceOneChannel(int32_t* dst, SRCTYPE* src,
                                    uint32_t to_fill) {
    for (uint32_t inIdx = 0; inIdx + 1 < to_fill; inIdx += 2) {
        int32_t smp1 = src[inIdx];
        int32_t smp2 = src[inIdx + 1];
        // following "if" clause will be optimized at compilation time
        if (BITS < 16) {
            smp1 = (smp1 << (16 - BITS)) & 0xffff;
            smp2 = (smp2 << (16 - BITS)) & 0xffff;
        } else {
            smp1 = (smp1 >> (BITS - 16)) & 0xffff;
            smp2 = (smp2 >> (BITS - 16)) & 0xffff;
        }
        *(dst++) = (smp1 << 16) | (smp2 << 0);
    }
}

// fill stereo audio data into 16 bits workspace, left/right are mixed
template<int BITS, typename SRCTYPE>
static inline void fillWorkspaceTwoChannel(int32_t* dst, SRCTYPE* src,
                                    uint32_t to_fill) {
    for (uint32_t inIdx = 0; inIdx + 3 < to_fill; inIdx += 4) {
        int32_t smp1 = static_cast<int32_t>(src[inIdx]) + src[inIdx + 1];
        int32_t smp2 = static_cast<int32_t>(src[inIdx + 2]) + src[inIdx + 3];
        // following "if" clause will be optimized at compilation time
        if (BITS < 16) {
            smp1 = (smp1 << (15 - BITS)) & 0xffff;
            smp2 = (smp2 << (15 - BITS)) & 0xffff;
        } else {
            smp1 = (smp1 >> (BITS - 15)) & 0xffff;
            smp2 = (smp2 >> (BITS - 15)) & 0xffff;
        }
        *(dst++) = (smp1 << 16) | (smp2 << 0);
    }
}

template<int BITS, typename SRCTYPE>
static inline bool fillWorkspace(int32_t* dst, SRCTYPE* src,
                          uint32_t to_fill, int32_t channels) {
    switch(channels) {
        case 2:
            fillWorkspaceTwoChannel<BITS, SRCTYPE>(dst, src, to_fill);
            return true;
        case 1:
            fillWorkspaceOneChannel<BITS, SRCTYPE>(dst, src, to_fill);
            return true;
        default:
            LOGE("Unsupported channel %d", channels);
            return false;
    }
}


namespace android {

AAH_AudioProcessor::AAH_AudioProcessor(OMXClient& omx)
        : AAH_DecoderPumpBase(omx),
          filled_(0) {
}

AAH_AudioProcessor::~AAH_AudioProcessor() {
}

void AAH_AudioProcessor::setAlgorithm(const sp<AudioAlgorithm>& processor) {
    processor_ = processor;
}

const sp<AudioAlgorithm>& AAH_AudioProcessor::getAlgorithm() {
    return processor_;
}

status_t AAH_AudioProcessor::shutdown_l() {
    status_t ret = AAH_DecoderPumpBase::shutdown_l();
    LOGV("Shutdown AAH_AudioProcessor");
    return ret;
}

void AAH_AudioProcessor::queueToSink(MediaBuffer* decoded_frames) {
    sp<MetaData> meta;
    int64_t ts;
    status_t res;

    // Fetch the metadata and make sure the sample has a timestamp.  We
    // cannot process samples which are missing PTSs.
    meta = decoded_frames->meta_data();
    if ((meta == NULL) || (!meta->findInt64(kKeyTime, &ts))) {
        LOGV("Decoded sample missing timestamp, cannot process.");
        return;
    }

    if (!processor_->initialize(kFFTSize, format_sample_rate_)) {
        return;
    }

    uint8_t* decoded_data = reinterpret_cast<uint8_t*>(decoded_frames->data());
    uint32_t decoded_amt = decoded_frames->range_length();
    decoded_data += decoded_frames->range_offset();

    // timestamp for the current workspace start position is calculated by
    // current ts minus filled samples.
    int64_t start_ts = ts - SAMPLES_TO_TS(filled_, format_sample_rate_);

    // following code is an excerpt of system visualizer, the differences are
    // in three places in order to get a more accurate output fft value
    // - full 16 bits are kept comparing to dynamic shifting in system
    //   visualizer
    // - full audio stream are processed unlike the "sparse" sampling in system
    //   visualizer
    // - system visualizer uses a weird dynamic shifting down of output fft
    //   values,  we output full 16 bits

    uint32_t sampleBytes = 2;  // android system assumes 16bits for now
    uint32_t frameBytes = sampleBytes * format_channels_;
    int loopcount = 0;  // how many fft chunks have been sent to algorithm
    while (decoded_amt >= frameBytes * 2) { // at least two samples
        uint32_t decoded_frames = decoded_amt / frameBytes;
        decoded_frames &= (~1); // only handle even samples
        uint32_t to_fill = MIN(kFFTSize - filled_, decoded_frames);
        uint32_t to_fill_bytes = to_fill * frameBytes;

        // workspace is array of 32bits integer, each 32bits has two samples.
        // The integer order in CPU register is "S1 00 S2 00" from high to low.
        // In memory, the workspace layout depends on endian order.
        // In another word, memory layout is different on different endian
        // system, but when they are read into CPU 32bits register, they are the
        // same order to perform arithmetic and bitwise operations
        // For details see fixedfft.cpp
        int32_t* dst = workspace_ + (filled_ >> 1);
        switch (sampleBytes) {
            case 2:
                if (!fillWorkspace<16, int16_t>(
                        dst, reinterpret_cast<int16_t*>(decoded_data), to_fill,
                        format_channels_)) {
                    return;
                }
                break;
            case 1:
                if (!fillWorkspace<8, int8_t>(
                        dst, reinterpret_cast<int8_t*>(decoded_data), to_fill,
                        format_channels_)) {
                    return;
                }
                break;
            default:
                LOGE("Unsupported sample size %d", sampleBytes);
                return;
        }

        decoded_data += to_fill_bytes;
        decoded_amt -= to_fill_bytes;
        filled_ += to_fill;

        if (filled_ == kFFTSize) {
            // workspace_ is full, calcuate fft
            fixed_fft_real(kFFTSize >> 1, workspace_);
            // now workspace_ contains 16 bits fft values
            processor_->process(
                    start_ts + SAMPLES_TO_TS((kFFTSize) * loopcount,
                            format_sample_rate_),
                    workspace_, kFFTSize);
            // open business for next chunk of kFFTSize samples
            filled_ = 0;
            loopcount++;
        }
    }
}

void AAH_AudioProcessor::stopAndCleanupSink() {
    processor_->cleanup();
}

void AAH_AudioProcessor::flush() {
    filled_ = 0;
    if (processor_ != NULL) {
        processor_->flush();
    }
}

}  // namespace android
