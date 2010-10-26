/*
 * Copyright (C) 2009 The Android Open Source Project
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

#include <media/stagefright/FileSource.h>
#include <media/stagefright/MediaDebug.h>

namespace android {

FileSource::FileSource(const char *filename)
    : mFile(fopen(filename, "rb")),
      mFd(fileno(mFile)),
      mOffset(0),
      mLength(-1),
      mDecryptHandle(NULL),
      mDrmManagerClient(NULL),
      mDrmBufOffset(0),
      mDrmBufSize(0),
      mDrmBuf(NULL){
}

FileSource::FileSource(int fd, int64_t offset, int64_t length)
    : mFile(fdopen(fd, "rb")),
      mFd(fd),
      mOffset(offset),
      mLength(length),
      mDecryptHandle(NULL),
      mDrmManagerClient(NULL),
      mDrmBufOffset(0),
      mDrmBufSize(0),
      mDrmBuf(NULL){
    CHECK(offset >= 0);
    CHECK(length >= 0);
}

FileSource::~FileSource() {
    if (mFile != NULL) {
        fclose(mFile);
        mFile = NULL;
    }

    if (mDrmBuf != NULL) {
        delete[] mDrmBuf;
        mDrmBuf = NULL;
    }
    if (mDecryptHandle != NULL) {
        mDrmManagerClient->closeDecryptSession(mDecryptHandle);
    }
}

status_t FileSource::initCheck() const {
    return mFile != NULL ? OK : NO_INIT;
}

ssize_t FileSource::readAt(off_t offset, void *data, size_t size) {
    if (mFile == NULL) {
        return NO_INIT;
    }

    Mutex::Autolock autoLock(mLock);

    if (mLength >= 0) {
        if (offset >= mLength) {
            return 0;  // read beyond EOF.
        }
        int64_t numAvailable = mLength - offset;
        if ((int64_t)size > numAvailable) {
            size = numAvailable;
        }
    }

    if (mDecryptHandle != NULL && DecryptApiType::CONTAINER_BASED
            == mDecryptHandle->decryptApiType) {
        return readAtDRM(offset, data, size);
   } else {
        int err = fseeko(mFile, offset + mOffset, SEEK_SET);
        if (err < 0) {
            LOGE("seek to %lld failed", offset + mOffset);
            return UNKNOWN_ERROR;
        }

        return fread(data, 1, size, mFile);
    }
}

status_t FileSource::getSize(off_t *size) {
    if (mFile == NULL) {
        return NO_INIT;
    }

    if (mLength >= 0) {
        *size = mLength;

        return OK;
    }

    fseek(mFile, 0, SEEK_END);
    *size = ftello(mFile);

    return OK;
}

DecryptHandle* FileSource::DrmInitialization(DrmManagerClient* client) {
    mDrmManagerClient = client;
    if (mDecryptHandle == NULL) {
        mDecryptHandle = mDrmManagerClient->openDecryptSession(
                mFd, mOffset, mLength);
    }

    if (mDecryptHandle == NULL) {
        mDrmManagerClient = NULL;
    }

    return mDecryptHandle;
}

void FileSource::getDrmInfo(DecryptHandle **handle, DrmManagerClient **client) {
    *handle = mDecryptHandle;

    *client = mDrmManagerClient;
}

ssize_t FileSource::readAtDRM(off_t offset, void *data, size_t size) {
    size_t DRM_CACHE_SIZE = 1024;
    if (mDrmBuf == NULL) {
        mDrmBuf = new unsigned char[DRM_CACHE_SIZE];
    }

    if (mDrmBuf != NULL && mDrmBufSize > 0 && (offset + mOffset) >= mDrmBufOffset
            && (offset + mOffset + size) <= (mDrmBufOffset + mDrmBufSize)) {
        /* Use buffered data */
        memcpy(data, (void*)(mDrmBuf+(offset+mOffset-mDrmBufOffset)), size);
        return size;
    } else if (size <= DRM_CACHE_SIZE) {
        /* Buffer new data */
        mDrmBufOffset =  offset + mOffset;
        mDrmBufSize = mDrmManagerClient->pread(mDecryptHandle, mDrmBuf,
                DRM_CACHE_SIZE, offset + mOffset);
        if (mDrmBufSize > 0) {
            int64_t dataRead = 0;
            dataRead = size > mDrmBufSize ? mDrmBufSize : size;
            memcpy(data, (void*)mDrmBuf, dataRead);
            return dataRead;
        } else {
            return mDrmBufSize;
        }
    } else {
        /* Too big chunk to cache. Call DRM directly */
        return mDrmManagerClient->pread(mDecryptHandle, data, size, offset + mOffset);
    }
}
}  // namespace android
