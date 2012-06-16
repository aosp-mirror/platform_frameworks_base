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

#include <math.h>
#include <stdlib.h>

#include "aah_audio_algorithm.h"

// #define DEBUG_BEAT_VALUE

namespace android {

// magic number, the bar should set higher if kBands is bigger
const float BeatDetectionAlgorithm::kThreshHold = 8;
const float BeatDetectionAlgorithm::kSumThreshold = 250;


// back trace time 1s
const float BeatDetectionAlgorithm::kBacktraceTime = 1;

// we must wait 1 second before switch generate a new sequence number,  this is
// to prevent visualizer switches too much
const int64_t BeatDetectionAlgorithm::kBeatInterval = 1000000;

const float BeatDetectionAlgorithm::kMaxBeatValue = 100000;

// how many beat information will be cached before send out? We group beats
// in one packet to reduce the cost of sending too much packets. The time
// should be shorter than kAAHBufferTimeUs defined in TxPlayer
// The extra latency is introduced by fft, beat algorithm, time transform,
// binder service latency, jni latency, etc.  If all these extra latency
// add up too much, then kAAHBufferTimeUs must be increased
const int32_t BeatDetectionAlgorithm::kAAHBeatInfoBufferTimeMS = 250;

// each thread holds a random data structure
static __thread unsigned short sRandData[3];
static __thread bool sRandDataInitialized = false;

static inline float normalizeBeatValue(float scale, float threshold) {
    if (scale < 1) {
        return 1;
    } else if (scale > threshold) {
        return threshold;
    }
    return scale;
}

BeatDetectionAlgorithm::BeatDetectionAlgorithm()
        : mSamplesPerSegment(0),
          mSegments(0),
          mEnergyTrain(NULL),
          mBeatTrain(NULL) {
    if (!sRandDataInitialized) {
        seed48(sRandData);
        sRandDataInitialized = true;
    }
    mBeatSequenceNumber = nrand48(sRandData);
}

BeatDetectionAlgorithm::~BeatDetectionAlgorithm() {
    cleanup();
}

bool BeatDetectionAlgorithm::initialize(uint32_t samples_per_seg,
                                uint32_t sample_rates) {
    LOGV("initialize algorithm samples_per_seg %d sample_rates %d",
         samples_per_seg, sample_rates);
    uint32_t segments = (uint32_t)(
            sample_rates / samples_per_seg * kBacktraceTime);
    if (mSamplesPerSegment == samples_per_seg && mSegments == segments) {
        return true;
    }
    uint32_t samplesPerBand = samples_per_seg / kBands;
    if (samplesPerBand * kBands != samples_per_seg) {
        LOGE("%s samples per segment not divided evenly by bands",
             __PRETTY_FUNCTION__);
        return false;
    }
    if (samplesPerBand & 1) {
        LOGE("%s each band must contain even number of samples",
             __PRETTY_FUNCTION__);
        return false;
    }
    cleanup();
    mSamplesPerSegment = samples_per_seg;
    mSegments = segments;
    mSamplesPerBand = samplesPerBand;
    mTrainMatrixSize = kSearchBands * mSegments;
    mEnergyTrain = new uint64_t[mTrainMatrixSize];
    mBeatTrain = new float[mTrainMatrixSize];
    if (!mEnergyTrain || !mBeatTrain) {
        LOGE("%s failed allocating memory", __PRETTY_FUNCTION__);
        return false;
    }
    flush();
    return true;
}

void BeatDetectionAlgorithm::process(int64_t ts, int32_t* fft,
                                     uint32_t samples_per_seg) {
    CHECK(samples_per_seg == mSamplesPerSegment);
    if (mSegments == 0) {
        return;
    }
    // access fft array as 16bits
    int16_t* segmentFt = (int16_t*)fft;
    float maxNewEnergyScale = 0;
    int maxBeatIdx = -1;
    float sum = 0;
    for (int i = 0, trainIndexForBand = 0;
            i < kBandEnd - kBandStart;
            i++, trainIndexForBand += mSegments) {
        uint64_t newEnergy = 0;
        // mSamplesPerBand is already validated to be even in initialize()
        uint32_t startSample = (kBandStart + i) * mSamplesPerBand;
        for (uint32_t li = startSample;
             li < startSample + mSamplesPerBand;
             li += 2) {
            uint64_t amplitude = (int32_t)segmentFt[li] * (int32_t)segmentFt[li]
                    + (int32_t)segmentFt[li + 1] * (int32_t)segmentFt[li + 1];
            newEnergy += amplitude;
        }
        newEnergy = newEnergy / (mSamplesPerBand >> 1);
        if (mEnergyTrainFilled) {
            // update beat train
            float newEnergyScale = (float) newEnergy
                    / ((double) mEnergyTrainSum[i] / (double) mSegments);
            mBeatTrain[trainIndexForBand + mBeatTrainIdx] = newEnergyScale;
            if (isnan(newEnergyScale) || isinf(newEnergyScale)
                    || newEnergyScale > maxNewEnergyScale) {
                maxNewEnergyScale = newEnergyScale;
                maxBeatIdx = i;
            }
            if (newEnergyScale > kThreshHold) {
                sum += newEnergyScale;
            }
        }

        // Update the energy train and energy sum
        mEnergyTrainSum[i] -= mEnergyTrain[trainIndexForBand + mEnergyTrainIdx];
        mEnergyTrain[trainIndexForBand + mEnergyTrainIdx] = newEnergy;
        mEnergyTrainSum[i] += mEnergyTrain[trainIndexForBand + mEnergyTrainIdx];

    }
    if (isnan(maxNewEnergyScale) || isinf(maxNewEnergyScale)
            || maxNewEnergyScale > kMaxBeatValue) {
        maxNewEnergyScale = kMaxBeatValue;
    }
    bool beat = false;
    if (sum >= kSumThreshold /*&& maxNewEnergyScale > kThreshHold*/
            && (mBeatLastTs == -1 || (ts - mBeatLastTs) > kBeatInterval)) {
        mBeatLastTs = ts;
        mBeatSequenceNumber++;
        beat = true;
        LOGV("BEAT!!!! %d %f", mBeatSequenceNumber, maxNewEnergyScale);
    }
    mBeatValue = maxNewEnergyScale;
    mBeatValueSmoothed = mBeatValueSmoothed * 0.7
            + normalizeBeatValue(mBeatValue, 30) * 0.3;
    AudioBeatInfo beatInfo(ts, mBeatValue, mBeatValueSmoothed,
                           mBeatSequenceNumber);
    // allowing overwrite existing item in the queue if we didn't send out
    // data in time: lost beats is very unlikely to happen
    mBeatInfoQueue.writeAllowOverflow(beatInfo);

#ifdef DEBUG_BEAT_VALUE
    char debugstr[256];
    uint32_t i;
    for (i = 0; i < mBeatValue && i < sizeof(debugstr) - 1; i++) {
        debugstr[i] = beat ? 'B' : '*';
    }
    debugstr[i] = 0;
    LOGD("%lld %lld %f %f %s", mBeatLastTs, ts, mBeatValue, sum, debugstr);
#endif

    mEnergyTrainIdx = mEnergyTrainIdx + 1;
    if (mEnergyTrainIdx == mSegments) {
        mEnergyTrainIdx = 0;
        mEnergyTrainFilled = true;
    }

    if (mEnergyTrainFilled) {
        mBeatTrainIdx = mBeatTrainIdx + 1;
        if (mBeatTrainIdx == mSegments) {
            mBeatTrainIdx = 0;
        }
    }
}

void BeatDetectionAlgorithm::cleanup() {
    if (mEnergyTrain) {
        delete mEnergyTrain;
        mEnergyTrain = NULL;
    }
    if (mBeatTrain) {
        delete mBeatTrain;
        mBeatTrain = NULL;
    }
}

class TRTPMetaDataBeat : public TRTPMetaDataBlock {
 public:
    TRTPMetaDataBeat()
      : TRTPMetaDataBlock(kMetaDataBeat, 0) {}
    TRTPMetaDataBeat(uint16_t beats,
                     AudioBeatInfo* beatInfo)
      : TRTPMetaDataBlock(kMetaDataBeat, calculateItemLength(beats))
      , mCount(beats)
    {
        memcpy(&beatInfos, beatInfo, beats * sizeof(AudioBeatInfo) );
    }
    static inline uint32_t calculateItemLength(uint16_t beats) {
        return 2 + BeatDetectionAlgorithm::kItemLength * beats;
    }
    virtual ~TRTPMetaDataBeat() {}
    virtual void write(uint8_t*& buf) const;
    uint16_t mCount;
    struct AudioBeatInfo beatInfos[BeatDetectionAlgorithm::kBeatQueueLen];
};

void TRTPMetaDataBeat::write(uint8_t*& buf) const {
    writeBlockHead(buf);
    TRTPPacket::writeU16(buf, mCount);
    for (uint16_t i = 0; i < mCount; i++) {
        TRTPPacket::writeU64(buf, beatInfos[i].ts);
        TRTPPacket::writeFloat(buf, beatInfos[i].beatValue);
        TRTPPacket::writeFloat(buf, beatInfos[i].smoothedBeatValue);
        TRTPPacket::writeU32(buf, beatInfos[i].sequenceNumber);
    }
}

TRTPMetaDataBlock* BeatDetectionAlgorithm::collectMetaData(bool flushOut) {
    AudioBeatInfo beatInfo[kBeatQueueLen];
    uint32_t min_read;
    if (flushOut) {
        min_read = 0;
    } else {
        min_read = mSegments * kAAHBeatInfoBufferTimeMS / 1000;
        if (min_read > kBeatQueueLen) {
            min_read = kBeatQueueLen;
        }
    }
    int beats = mBeatInfoQueue.readBulk(beatInfo, min_read,
                                        kBeatQueueLen);
    if (beats > 0) {
        uint32_t privateSize = TRTPMetaDataBeat::calculateItemLength(beats);
        if (privateSize > 0xffff) {
            LOGE("metadata packet too big");
            return NULL;
        }
        return new TRTPMetaDataBeat(beats, beatInfo);
    } else {
        return NULL;
    }
}

void BeatDetectionAlgorithm::flush() {
    if (mEnergyTrain == NULL || mBeatTrain == NULL) {
        return;
    }
    mEnergyTrainIdx = 0;
    mBeatTrainIdx = 0;
    mEnergyTrainFilled = false;
    mBeatValue = 0;
    mBeatValueSmoothed = 0;
    mBeatLastTs = -1;

    memset(mEnergyTrain, 0, mTrainMatrixSize * sizeof(uint64_t));
    // IEEE745: all zero bytes generates 0.0f
    memset(mBeatTrain, 0, mTrainMatrixSize * sizeof(float));
    memset(&mEnergyTrainSum, 0, sizeof(mEnergyTrainSum));
}

}  // namespace android
