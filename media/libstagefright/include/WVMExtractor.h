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

#ifndef WVM_EXTRACTOR_H_

#define WVM_EXTRACTOR_H_

#include <media/stagefright/MediaExtractor.h>
#include <utils/Errors.h>

namespace android {

struct AMessage;
class String8;
class DataSource;

class WVMLoadableExtractor : public MediaExtractor {
public:
    WVMLoadableExtractor() {}
    virtual ~WVMLoadableExtractor() {}

    virtual int64_t getCachedDurationUs(status_t *finalStatus) = 0;
    virtual void setAdaptiveStreamingMode(bool adaptive) = 0;
};

class WVMExtractor : public MediaExtractor {
public:
    WVMExtractor(const sp<DataSource> &source);

    virtual size_t countTracks();
    virtual sp<MediaSource> getTrack(size_t index);
    virtual sp<MetaData> getTrackMetaData(size_t index, uint32_t flags);
    virtual sp<MetaData> getMetaData();

    // Return the amount of data cached from the current
    // playback positiion (in us).
    // While more data is still being fetched *finalStatus == OK,
    // Once fetching is completed (no more data available), *finalStatus != OK
    // If fetching completed normally (i.e. reached EOS instead of IO error)
    // *finalStatus == ERROR_END_OF_STREAM
    int64_t getCachedDurationUs(status_t *finalStatus);

    // Set to use adaptive streaming mode by the WV component.
    // If adaptive == true, adaptive streaming mode will be used.
    // Default mode is non-adaptive streaming mode.
    // Should set to use adaptive streaming mode only if widevine:// protocol
    // is used.
    void setAdaptiveStreamingMode(bool adaptive);

    static bool getVendorLibHandle();

protected:
    virtual ~WVMExtractor();

private:
    sp<DataSource> mDataSource;
    sp<WVMLoadableExtractor> mImpl;

    WVMExtractor(const WVMExtractor &);
    WVMExtractor &operator=(const WVMExtractor &);
};

bool SniffWVM(
        const sp<DataSource> &source, String8 *mimeType, float *confidence,
        sp<AMessage> *);

}  // namespace android

#endif  // DRM_EXTRACTOR_H_

