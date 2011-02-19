/*
 * Copyright (C) 2011 The Android Open Source Project
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

#define LOG_TAG "WVMMediaSource"
#include <utils/Log.h>

#include "WVMMediaSource.h"
#include "WVMFileSource.h"
#include "media/stagefright/MediaErrors.h"
#include "media/stagefright/MediaDefs.h"
#include "media/stagefright/MediaDebug.h"

namespace android {

extern DrmManagerClient *gDrmManagerClient;

WVMMediaSource::WVMMediaSource(WVSession *session, WVEsSelector esSelector,
                               const sp<MetaData> &metaData)
    : mSession(session),
      mESSelector(esSelector),
      mTrackMetaData(metaData),
      mStarted(false),
      mGroup(NULL),
      mDts(0),
      mPts(0)
{
}

// Since the WVMExtractor lifetime is short, we delegate ownership of some resources
// to the media source, which cleans them up after when the media source is destroyed

void WVMMediaSource::delegateFileSource(sp<WVMFileSource> fileSource)
{
    mFileSource = fileSource;
}

void WVMMediaSource::delegateDataSource(sp<DataSource> dataSource)
{
    mDataSource = dataSource;
}

void WVMMediaSource::allocBufferGroup()
{
    if (mGroup)
        delete mGroup;

    mGroup = new MediaBufferGroup;

    size_t size;
    if (mESSelector == WV_EsSelector_Video)
        size = 256 * 1024;
    else
        size = 64 * 1024;

    mGroup->add_buffer(new MediaBuffer(size));
}



status_t WVMMediaSource::start(MetaData *)
{
    //LOGD("WVMMediaSource::start()");
    Mutex::Autolock autoLock(mLock);

    CHECK(!mStarted);

    allocBufferGroup();

    mStarted = true;

    // Let video stream control play/pause
    if (mESSelector == WV_EsSelector_Video) {
        float speed;
        WVStatus result = WV_Play(mSession, 1.0, &speed, "now-");
        if (result != WV_Status_OK) {
            LOGE("WV_Play returned status %d in WVMMediaSource::start\n", result);
        }
    }
    return OK;
}


status_t WVMMediaSource::stop()
{
    //LOGD("WVMMediaSource::stop()");
    Mutex::Autolock autoLock(mLock);

    CHECK(mStarted);

    // Let video stream control play/pause
    if (mESSelector == WV_EsSelector_Video) {
        WVStatus result = WV_Pause(mSession, "now");
        if (result != WV_Status_OK) {
            LOGE("WV_Pause returned status %d in WVMMediaSource::stop\n", result);
        }
    }

    delete mGroup;
    mGroup = NULL;

    mStarted = false;

    return OK;
}

sp<MetaData> WVMMediaSource::getFormat()
{
    Mutex::Autolock autoLock(mLock);
    return mTrackMetaData;
}

std::string usecToNPT(int64_t time)
{
    unsigned hours = (unsigned)(time / (60LL * 60 * 1000000));
    time -= (int64_t)hours * 60 * 60 * 1000000;
    unsigned mins = (unsigned)(time / (60 * 1000000));
    time -= (int64_t)mins * 60 * 1000000;
    float secs = (float)time / 1000000;
    char buf[32];
    sprintf(buf, "%d:%d:%f", hours, mins, secs);
    return std::string(buf);
}

status_t WVMMediaSource::read(MediaBuffer **buffer, const ReadOptions *options)
{
    Mutex::Autolock autoLock(mLock);

    CHECK(mStarted);

    *buffer = NULL;

    int64_t seekTimeUs;
    ReadOptions::SeekMode mode;
    if (options && options->getSeekTo(&seekTimeUs, &mode)) {
        // Let video stream control seek
        if (mESSelector == WV_EsSelector_Video) {
            float scaleUsed;
            std::string when = usecToNPT(seekTimeUs) + std::string("-");
            WVStatus result = WV_Play(mSession, 1.0, &scaleUsed, when );
            if (result != WV_Status_OK) {
                LOGE("WV_Play returned status %d in WVMMediaSource::read\n", result);
                return ERROR_IO;
            }
        }
    }

    MediaBuffer *mediaBuf;

    status_t err = mGroup->acquire_buffer(&mediaBuf);

    if (err != OK) {
        CHECK_EQ(mediaBuf, NULL);
        return err;
    }

    size_t bytesRead;
    bool auStart;
    size_t offset = 0;

    // Pull full access units. Since we aren't sure how big they might be,
    // start with initial buffer size, then allocate a larger buffer if we
    // get more a number of bytes equal to the full buffer size and go back
    // for the rest.  Only loop in this case, usually it's one pass through.

    while (true) {
        size_t size = mediaBuf->size() - offset;

        WVStatus result = WV_GetEsData(mSession, mESSelector, (uint8_t *)mediaBuf->data() + offset,
                                       size, bytesRead, auStart, mDts, mPts);
        if (result == WV_Status_End_Of_Media) {
            mediaBuf->release();
            return ERROR_END_OF_STREAM;
        } else if (result != WV_Status_OK) {
            if (result != WV_Status_Warning_Need_Key &&
                result != WV_Status_Warning_Download_Stalled)
            {
                LOGE("WV_GetEsData returned ERROR %d in WVMMediaSource::read\n", result);
                mediaBuf->release();
                return ERROR_IO;
            } else
                LOGW("WV_GetEsData returned WARNING %d in WVMMediaSource::read\n", result);
        }


        if (bytesRead == 0) {
            // Didn't get anything, sleep a bit so we don't hog the CPU then
            // try again.
            usleep(10000);
            continue;
        }

        if (offset + bytesRead < mediaBuf->size())
            break;

        //LOGD("Resizing...");

        // This buffer is too small, allocate a larger buffer twice the size
        // and copy the data from the current buffer into the first part of
        // the new buffer, then set offset to where the next read should go.

        MediaBuffer *newBuffer = new MediaBuffer(mediaBuf->size() * 2);
        newBuffer->add_ref();

        memcpy(newBuffer->data(), mediaBuf->data(), mediaBuf->size());
        offset = mediaBuf->size();

        mGroup->add_buffer(newBuffer);

        mediaBuf->release();
        mediaBuf = newBuffer;
    }

#define PCR_HZ 90000
    int64_t keyTime = (int64_t)mDts * 1000000 / PCR_HZ;

    mediaBuf->meta_data()->clear();
    mediaBuf->meta_data()->setInt64(kKeyTime, keyTime);

    mediaBuf->set_range(0, bytesRead + offset);

#if 0
    // debug code - log packets to files
    char filename[32];
    static int acounter = 0, vcounter = 0;
    if (mESSelector == WV_EsSelector_Video)
        sprintf(filename, "/data/wvm/v%d", vcounter++);
    else
        sprintf(filename, "/data/wvm/a%d", acounter++);

    FILE *f = fopen(filename, "w");
    if (!f)
        LOGE("WVMFileSource: can't open %s", filename);
    else {
        fwrite(mediaBuf->data(), bytesRead + offset, 1, f);
        fclose(f);
    }
    LOGD("WVMMediaSource::read writing (%d bytes to %s)", bytesRead + offset, filename);
#endif

#if 0
    LOGD("[%p] %s set range_length=%d, get range_length=%d kKeyTime=%lld\n", mediaBuf,
         (mESSelector == WV_EsSelector_Video ? "video" : "audio"),
         bytesRead + offset, mediaBuf->range_length(), keyTime);
#endif

    *buffer = mediaBuf;

    return OK;
}

WVMMediaSource::~WVMMediaSource()
{
    //LOGD("WVMMediaSource::~WVMMediaSource()");

    if (mStarted) {
        stop();
    }

    if (mESSelector == WV_EsSelector_Video) {
        if (mSession != NULL)
            WV_Teardown(mSession);
    }
}

} // namespace android
