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

#include <media/stagefright/AMRWriter.h>

#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>

namespace android {

AMRWriter::AMRWriter(const char *filename)
    : mFile(fopen(filename, "wb")),
      mInitCheck(mFile != NULL ? OK : NO_INIT),
      mStarted(false) {
}

AMRWriter::AMRWriter(int fd)
    : mFile(fdopen(fd, "wb")),
      mInitCheck(mFile != NULL ? OK : NO_INIT),
      mStarted(false) {
}

AMRWriter::~AMRWriter() {
    if (mStarted) {
        stop();
    }

    if (mFile != NULL) {
        fclose(mFile);
        mFile = NULL;
    }
}

status_t AMRWriter::initCheck() const {
    return mInitCheck;
}

status_t AMRWriter::addSource(const sp<MediaSource> &source) {
    if (mInitCheck != OK) {
        return mInitCheck;
    }

    if (mSource != NULL) {
        // AMR files only support a single track of audio.
        return UNKNOWN_ERROR;
    }

    sp<MetaData> meta = source->getFormat();

    const char *mime;
    CHECK(meta->findCString(kKeyMIMEType, &mime));

    bool isWide = false;
    if (!strcasecmp(mime, MEDIA_MIMETYPE_AUDIO_AMR_WB)) {
        isWide = true;
    } else if (strcasecmp(mime, MEDIA_MIMETYPE_AUDIO_AMR_NB)) {
        return ERROR_UNSUPPORTED;
    }

    int32_t channelCount;
    int32_t sampleRate;
    CHECK(meta->findInt32(kKeyChannelCount, &channelCount));
    CHECK_EQ(channelCount, 1);
    CHECK(meta->findInt32(kKeySampleRate, &sampleRate));
    CHECK_EQ(sampleRate, (isWide ? 16000 : 8000));

    mSource = source;

    const char *kHeader = isWide ? "#!AMR-WB\n" : "#!AMR\n";
    size_t n = strlen(kHeader);
    if (fwrite(kHeader, 1, n, mFile) != n) {
        return ERROR_IO;
    }

    return OK;
}

status_t AMRWriter::start() {
    if (mInitCheck != OK) {
        return mInitCheck;
    }

    if (mStarted || mSource == NULL) {
        return UNKNOWN_ERROR;
    }

    status_t err = mSource->start();

    if (err != OK) {
        return err;
    }

    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_JOINABLE);

    mReachedEOS = false;
    mDone = false;

    pthread_create(&mThread, &attr, ThreadWrapper, this);
    pthread_attr_destroy(&attr);

    mStarted = true;

    return OK;
}

void AMRWriter::stop() {
    if (!mStarted) {
        return;
    }

    mDone = true;

    void *dummy;
    pthread_join(mThread, &dummy);

    mSource->stop();

    mStarted = false;
}

// static
void *AMRWriter::ThreadWrapper(void *me) {
    static_cast<AMRWriter *>(me)->threadFunc();

    return NULL;
}

void AMRWriter::threadFunc() {
    while (!mDone) {
        MediaBuffer *buffer;
        status_t err = mSource->read(&buffer);

        if (err != OK) {
            break;
        }

        ssize_t n = fwrite(
                (const uint8_t *)buffer->data() + buffer->range_offset(),
                1,
                buffer->range_length(),
                mFile);

        if (n < (ssize_t)buffer->range_length()) {
            buffer->release();
            buffer = NULL;

            break;
        }

        buffer->release();
        buffer = NULL;
    }

    mReachedEOS = true;
}

bool AMRWriter::reachedEOS() {
    return mReachedEOS;
}

}  // namespace android
