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

#ifndef __AAH_AUDIO_ALGORITHM_H__
#define __AAH_AUDIO_ALGORITHM_H__

#include <utils/RefBase.h>
#include <utils.h>

#include "aah_tx_packet.h"

namespace android {

class AudioAlgorithm : public virtual RefBase {
 public:
    explicit AudioAlgorithm() {}
    virtual bool initialize(uint32_t samples_per_seg, uint32_t samplerate) = 0;
    virtual void process(int64_t ts, int32_t* fft,
                         uint32_t samples_per_seg) = 0;
    virtual void flush() = 0;
    virtual void cleanup() = 0;
    virtual TRTPMetaDataBlock* collectMetaData(bool flushOut) = 0;
    virtual ~AudioAlgorithm() {}
 private:
    DISALLOW_EVIL_CONSTRUCTORS (AudioAlgorithm);
};

struct AudioBeatInfo {
    AudioBeatInfo()
            : ts(0)
            , beatValue(0)
            , smoothedBeatValue(0)
            , sequenceNumber(0) {}
    AudioBeatInfo(int64_t t, float b, float sb, uint32_t s)
            : ts(t)
            , beatValue(b)
            , smoothedBeatValue(sb)
            , sequenceNumber(s) {}
    int64_t ts;
    float beatValue;
    float smoothedBeatValue;
    uint32_t sequenceNumber;
};

class BeatDetectionAlgorithm : public virtual AudioAlgorithm {
 public:

    static const int kItemLength = 20;

    explicit BeatDetectionAlgorithm();
    virtual bool initialize(uint32_t samples_per_seg, uint32_t samplerate);
    // each 32 bits fft value consists of real part on high 16 bits
    // and imaginary part on low 16 bits
    virtual void process(int64_t ts, int32_t* fft, uint32_t samples_per_seg);
    virtual void flush();
    virtual void cleanup();
    virtual TRTPMetaDataBlock* collectMetaData(bool flushOut);
    virtual ~BeatDetectionAlgorithm();
 protected:
    // =======================
    // constant definition
    // =======================

    // divide frequency domain to kBands
    static const int32_t kBands = 128;
    // we search from kBandStart(inclusive) to kBandEnd (exclusive)
    static const int32_t kBandStart = 0;
    static const int32_t kBandEnd = 64;
    static const int32_t kSearchBands = kBandEnd - kBandStart;
    // magic number, the bar should set higher if kBands is bigger
    static const float kThreshHold;
    static const float kSumThreshold;

    static const float kBacktraceTime;

    static const int64_t kBeatInterval;

    static const float kMaxBeatValue;

    static const int32_t kAAHBeatInfoBufferTimeMS;

    // 128 maximum beat allowed,  this is roughly 3 seconds data for 44KHZ, 1024
    // fft samples per segment
    static const uint32_t kBeatQueueLen = 128;

    uint32_t mSamplesPerSegment;
    uint32_t mSegments;
    uint32_t mSamplesPerBand;

    // =======================
    // Energy train
    // =======================
    // circular energy value buffer for each BAND, each maintains one second
    uint32_t mEnergyTrainIdx;
    uint64_t* mEnergyTrain; // 2d array, size is kSearchBands * mSegments
    uint32_t mTrainMatrixSize; // kSearchBands * mSegments

    // sum of last second energy for each sub band
    uint64_t mEnergyTrainSum[kSearchBands];

    // if energy train has been filled for 1 second
    bool mEnergyTrainFilled;

    // =======================
    // Beat train
    // =======================
    // beat value train buffer for each BAND
    // It's not necessary keep a train now, we may need it for detecting peak
    float* mBeatTrain; // 2d array of kSearchBands * mSegments
    uint32_t mBeatTrainIdx;

    // =======================
    // Energy extraction stuff passed to outside
    // There is multi thread issue, but not critical.
    // So we not using synchronized or other mechanism
    // =======================
    float mBeatValue;
    float mBeatValueSmoothed;

    CircularArray<AudioBeatInfo, kBeatQueueLen> mBeatInfoQueue;
    uint32_t mBeatSequenceNumber;
    int64_t mBeatLastTs;

    friend class TRTPMetaDataBeat;

 private:
    DISALLOW_EVIL_CONSTRUCTORS (BeatDetectionAlgorithm);
};

}  // namespace android

#endif // __AAH_AUDIO_ALGORITHM_H__
