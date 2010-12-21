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

#ifndef DECODER_WRAPPER_H_

#define DECODER_WRAPPER_H_

#include <media/stagefright/foundation/AHandler.h>

namespace android {

struct MediaSource;

struct DecoderWrapper : public AHandler {
    DecoderWrapper();

    void setNotificationMessage(const sp<AMessage> &msg);
    void initiateSetup(const sp<AMessage> &msg);
    void initiateShutdown();
    void signalFlush();
    void signalResume();

protected:
    virtual ~DecoderWrapper();

    virtual void onMessageReceived(const sp<AMessage> &msg);

private:
    struct WrapperSource;
    struct WrapperReader;

    enum {
        kWhatSetup,
        kWhatInputBufferFilled,
        kWhatOutputBufferDrained,
        kWhatShutdown,
        kWhatFillBufferDone,
        kWhatInputDataRequested,
        kWhatFlush,
        kWhatResume,
    };

    sp<AMessage> mNotify;

    sp<WrapperSource> mSource;

    sp<ALooper> mReaderLooper;
    sp<WrapperReader> mReader;

    int32_t mNumOutstandingInputBuffers;
    int32_t mNumOutstandingOutputBuffers;
    int32_t mNumPendingDecodes;
    bool mFlushing;

    void onSetup(const sp<AMessage> &msg);
    void onShutdown();
    void onFlush();
    void onResume();

    void postFillBuffer();
    void completeFlushIfPossible();

    DISALLOW_EVIL_CONSTRUCTORS(DecoderWrapper);
};

}  // namespace android

#endif  // DECODER_WRAPPER_H_

