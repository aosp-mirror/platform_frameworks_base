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

#ifndef ANDROID_ISTREAMSOURCE_H_

#define ANDROID_ISTREAMSOURCE_H_

#include <binder/IInterface.h>

namespace android {

struct AMessage;
struct IMemory;
struct IStreamListener;

struct IStreamSource : public IInterface {
    DECLARE_META_INTERFACE(StreamSource);

    virtual void setListener(const sp<IStreamListener> &listener) = 0;
    virtual void setBuffers(const Vector<sp<IMemory> > &buffers) = 0;

    virtual void onBufferAvailable(size_t index) = 0;
};

struct IStreamListener : public IInterface {
    DECLARE_META_INTERFACE(StreamListener);

    enum Command {
        EOS,
        DISCONTINUITY,
    };

    virtual void queueBuffer(size_t index, size_t size) = 0;

    // When signalling a discontinuity you can optionally
    // specify an int64_t PTS timestamp in "msg".
    // If present, rendering of data following the discontinuity
    // will be suppressed until media time reaches this timestamp.
    static const char *const kKeyResumeAtPTS;

    // When signalling a discontinuity you can optionally
    // signal that this is a "hard" discontinuity, i.e. the format
    // or configuration of subsequent stream data differs from that
    // currently active. To do so, include a non-zero int32_t value
    // under the key "kKeyFormatChange" when issuing the DISCONTINUITY
    // command.
    // The new logical stream must start with proper codec initialization
    // information for playback to continue, i.e. SPS and PPS in the case
    // of AVC video etc.
    static const char *const kKeyFormatChange;

    virtual void issueCommand(
            Command cmd, bool synchronous, const sp<AMessage> &msg = NULL) = 0;
};

////////////////////////////////////////////////////////////////////////////////

struct BnStreamSource : public BnInterface<IStreamSource> {
    virtual status_t onTransact(
            uint32_t code, const Parcel &data, Parcel *reply,
            uint32_t flags = 0);
};

struct BnStreamListener : public BnInterface<IStreamListener> {
    virtual status_t onTransact(
            uint32_t code, const Parcel &data, Parcel *reply,
            uint32_t flags = 0);
};

}  // namespace android

#endif  // ANDROID_ISTREAMSOURCE_H_
