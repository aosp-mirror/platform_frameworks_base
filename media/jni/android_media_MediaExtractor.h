/*
 * Copyright 2012, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef _ANDROID_MEDIA_MEDIAEXTRACTOR_H_
#define _ANDROID_MEDIA_MEDIAEXTRACTOR_H_

#include <media/stagefright/foundation/ABase.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/DataSource.h>
#include <utils/Errors.h>
#include <utils/KeyedVector.h>
#include <utils/RefBase.h>
#include <utils/String8.h>

#include "jni.h"

namespace android {

struct MetaData;
struct NuMediaExtractor;

struct JMediaExtractor : public RefBase {
    JMediaExtractor(JNIEnv *env, jobject thiz);

    status_t setDataSource(
            const char *path,
            const KeyedVector<String8, String8> *headers);

    status_t setDataSource(int fd, off64_t offset, off64_t size);
    status_t setDataSource(const sp<DataSource> &source);

    size_t countTracks() const;
    status_t getTrackFormat(size_t index, jobject *format) const;

    status_t getFileFormat(jobject *format) const;

    status_t selectTrack(size_t index);
    status_t unselectTrack(size_t index);

    status_t seekTo(int64_t timeUs, MediaSource::ReadOptions::SeekMode mode);

    status_t advance();
    status_t readSampleData(jobject byteBuf, size_t offset, size_t *sampleSize);
    status_t getSampleTrackIndex(size_t *trackIndex);
    status_t getSampleTime(int64_t *sampleTimeUs);
    status_t getSampleFlags(uint32_t *sampleFlags);
    status_t getSampleMeta(sp<MetaData> *sampleMeta);

    bool getCachedDuration(int64_t *durationUs, bool *eos) const;

protected:
    virtual ~JMediaExtractor();

private:
    jclass mClass;
    jweak mObject;
    sp<NuMediaExtractor> mImpl;

    DISALLOW_EVIL_CONSTRUCTORS(JMediaExtractor);
};

}  // namespace android

#endif  // _ANDROID_MEDIA_MEDIAEXTRACTOR_H_
