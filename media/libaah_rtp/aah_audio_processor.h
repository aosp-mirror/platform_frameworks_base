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

#ifndef __AAH_AUDIO_PROCESSOR_H__
#define __AAH_AUDIO_PROCESSOR_H__

#include "aah_decoder_pump.h"
#include "aah_audio_algorithm.h"

namespace android {

// decode audio, calculate fft and invoke AudioAlgorithm
class AAH_AudioProcessor : public AAH_DecoderPumpBase {
 public:
    explicit AAH_AudioProcessor(OMXClient& omx);
    void flush();
    void setAlgorithm(const sp<AudioAlgorithm>& processor);
    const sp<AudioAlgorithm>& getAlgorithm();
    virtual ~AAH_AudioProcessor();

 private:

    // fft array size must be 2^n
    static const uint32_t kFFTSize = (1 << 10);

    uint32_t filled_;
    int32_t workspace_[kFFTSize >> 1];

    sp<AudioAlgorithm> processor_;

    virtual void queueToSink(MediaBuffer* decoded_sample);
    virtual void stopAndCleanupSink();
    virtual status_t shutdown_l();

    DISALLOW_EVIL_CONSTRUCTORS (AAH_AudioProcessor);
};

}  // namespace android

#endif // __AAH_AUDIO_PROCESSOR_H__
