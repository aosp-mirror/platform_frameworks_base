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

#include "include/DRMExtractor.h"
#include "include/AMRExtractor.h"
#include "include/MP3Extractor.h"
#include "include/MPEG4Extractor.h"
#include "include/WAVExtractor.h"
#include "include/OggExtractor.h"

#include <arpa/inet.h>
#include <utils/String8.h>
#include <media/stagefright/Utils.h>
#include <media/stagefright/DataSource.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaDebug.h>

#include <drm/drm_framework_common.h>
#include <utils/Errors.h>


namespace android {

class DRMSource : public MediaSource {
public:
    DRMSource(const sp<MediaSource> &mediaSource,
            const sp<DecryptHandle> &decryptHandle,
            DrmManagerClient *managerClient,
            int32_t trackId, DrmBuffer *ipmpBox);

    virtual status_t start(MetaData *params = NULL);
    virtual status_t stop();
    virtual sp<MetaData> getFormat();
    virtual status_t read(
            MediaBuffer **buffer, const ReadOptions *options = NULL);

protected:
    virtual ~DRMSource();

private:
    sp<MediaSource> mOriginalMediaSource;
    sp<DecryptHandle> mDecryptHandle;
    DrmManagerClient* mDrmManagerClient;
    size_t mTrackId;
    mutable Mutex mDRMLock;
    size_t mNALLengthSize;
    bool mWantsNALFragments;

    DRMSource(const DRMSource &);
    DRMSource &operator=(const DRMSource &);
};

////////////////////////////////////////////////////////////////////////////////

DRMSource::DRMSource(const sp<MediaSource> &mediaSource,
        const sp<DecryptHandle> &decryptHandle,
        DrmManagerClient *managerClient,
        int32_t trackId, DrmBuffer *ipmpBox)
    : mOriginalMediaSource(mediaSource),
      mDecryptHandle(decryptHandle),
      mDrmManagerClient(managerClient),
      mTrackId(trackId),
      mNALLengthSize(0),
      mWantsNALFragments(false) {
    CHECK(mDrmManagerClient);
    mDrmManagerClient->initializeDecryptUnit(
            mDecryptHandle, trackId, ipmpBox);

    const char *mime;
    bool success = getFormat()->findCString(kKeyMIMEType, &mime);
    CHECK(success);

    if (!strcasecmp(mime, MEDIA_MIMETYPE_VIDEO_AVC)) {
        uint32_t type;
        const void *data;
        size_t size;
        CHECK(getFormat()->findData(kKeyAVCC, &type, &data, &size));

        const uint8_t *ptr = (const uint8_t *)data;

        CHECK(size >= 7);
        CHECK_EQ(ptr[0], 1);  // configurationVersion == 1

        // The number of bytes used to encode the length of a NAL unit.
        mNALLengthSize = 1 + (ptr[4] & 3);
    }
}

DRMSource::~DRMSource() {
    Mutex::Autolock autoLock(mDRMLock);
    mDrmManagerClient->finalizeDecryptUnit(mDecryptHandle, mTrackId);
}

status_t DRMSource::start(MetaData *params) {
    int32_t val;
    if (params && params->findInt32(kKeyWantsNALFragments, &val)
        && val != 0) {
        mWantsNALFragments = true;
    } else {
        mWantsNALFragments = false;
    }

   return mOriginalMediaSource->start(params);
}

status_t DRMSource::stop() {
    return mOriginalMediaSource->stop();
}

sp<MetaData> DRMSource::getFormat() {
    return mOriginalMediaSource->getFormat();
}

status_t DRMSource::read(MediaBuffer **buffer, const ReadOptions *options) {
    Mutex::Autolock autoLock(mDRMLock);
    status_t err;
    if ((err = mOriginalMediaSource->read(buffer, options)) != OK) {
        return err;
    }

    size_t len = (*buffer)->range_length();

    char *src = (char *)(*buffer)->data() + (*buffer)->range_offset();

    DrmBuffer encryptedDrmBuffer(src, len);
    DrmBuffer decryptedDrmBuffer;
    decryptedDrmBuffer.length = len;
    decryptedDrmBuffer.data = new char[len];
    DrmBuffer *pDecryptedDrmBuffer = &decryptedDrmBuffer;

    if ((err = mDrmManagerClient->decrypt(mDecryptHandle, mTrackId,
            &encryptedDrmBuffer, &pDecryptedDrmBuffer)) != NO_ERROR) {

        if (decryptedDrmBuffer.data) {
            delete [] decryptedDrmBuffer.data;
            decryptedDrmBuffer.data = NULL;
        }

        return err;
    }
    CHECK(pDecryptedDrmBuffer == &decryptedDrmBuffer);

    const char *mime;
    CHECK(getFormat()->findCString(kKeyMIMEType, &mime));

    if (!strcasecmp(mime, MEDIA_MIMETYPE_VIDEO_AVC) && !mWantsNALFragments) {
        uint8_t *dstData = (uint8_t*)src;
        size_t srcOffset = 0;
        size_t dstOffset = 0;

        len = decryptedDrmBuffer.length;
        while (srcOffset < len) {
            CHECK(srcOffset + mNALLengthSize <= len);
            size_t nalLength = 0;
            const uint8_t* data = (const uint8_t*)(&decryptedDrmBuffer.data[srcOffset]);

            switch (mNALLengthSize) {
                case 1:
                    nalLength = *data;
                    break;
                case 2:
                    nalLength = U16_AT(data);
                    break;
                case 3:
                    nalLength = ((size_t)data[0] << 16) | U16_AT(&data[1]);
                    break;
                case 4:
                    nalLength = U32_AT(data);
                    break;
                default:
                    CHECK(!"Should not be here.");
                    break;
            }

            srcOffset += mNALLengthSize;

            if (srcOffset + nalLength > len) {
                if (decryptedDrmBuffer.data) {
                    delete [] decryptedDrmBuffer.data;
                    decryptedDrmBuffer.data = NULL;
                }

                return ERROR_MALFORMED;
            }

            if (nalLength == 0) {
                continue;
            }

            CHECK(dstOffset + 4 <= (*buffer)->size());

            dstData[dstOffset++] = 0;
            dstData[dstOffset++] = 0;
            dstData[dstOffset++] = 0;
            dstData[dstOffset++] = 1;
            memcpy(&dstData[dstOffset], &decryptedDrmBuffer.data[srcOffset], nalLength);
            srcOffset += nalLength;
            dstOffset += nalLength;
        }

        CHECK_EQ(srcOffset, len);
        (*buffer)->set_range((*buffer)->range_offset(), dstOffset);

    } else {
        memcpy(src, decryptedDrmBuffer.data, decryptedDrmBuffer.length);
        (*buffer)->set_range((*buffer)->range_offset(), decryptedDrmBuffer.length);
    }

    if (decryptedDrmBuffer.data) {
        delete [] decryptedDrmBuffer.data;
        decryptedDrmBuffer.data = NULL;
    }

    return OK;
}

////////////////////////////////////////////////////////////////////////////////

DRMExtractor::DRMExtractor(const sp<DataSource> &source, const char* mime)
    : mDataSource(source),
      mDecryptHandle(NULL),
      mDrmManagerClient(NULL) {
    mOriginalExtractor = MediaExtractor::Create(source, mime);
    mOriginalExtractor->setDrmFlag(true);
    mOriginalExtractor->getMetaData()->setInt32(kKeyIsDRM, 1);

    source->getDrmInfo(mDecryptHandle, &mDrmManagerClient);
}

DRMExtractor::~DRMExtractor() {
}

size_t DRMExtractor::countTracks() {
    return mOriginalExtractor->countTracks();
}

sp<MediaSource> DRMExtractor::getTrack(size_t index) {
    sp<MediaSource> originalMediaSource = mOriginalExtractor->getTrack(index);
    originalMediaSource->getFormat()->setInt32(kKeyIsDRM, 1);

    int32_t trackID;
    CHECK(getTrackMetaData(index, 0)->findInt32(kKeyTrackID, &trackID));

    DrmBuffer ipmpBox;
    ipmpBox.data = mOriginalExtractor->getDrmTrackInfo(trackID, &(ipmpBox.length));
    CHECK(ipmpBox.length > 0);

    return new DRMSource(originalMediaSource, mDecryptHandle, mDrmManagerClient,
            trackID, &ipmpBox);
}

sp<MetaData> DRMExtractor::getTrackMetaData(size_t index, uint32_t flags) {
    return mOriginalExtractor->getTrackMetaData(index, flags);
}

sp<MetaData> DRMExtractor::getMetaData() {
    return mOriginalExtractor->getMetaData();
}

bool SniffDRM(
    const sp<DataSource> &source, String8 *mimeType, float *confidence,
        sp<AMessage> *) {
    sp<DecryptHandle> decryptHandle = source->DrmInitialization();

    if (decryptHandle != NULL) {
        if (decryptHandle->decryptApiType == DecryptApiType::CONTAINER_BASED) {
            *mimeType = String8("drm+container_based+") + decryptHandle->mimeType;
        } else if (decryptHandle->decryptApiType == DecryptApiType::ELEMENTARY_STREAM_BASED) {
            *mimeType = String8("drm+es_based+") + decryptHandle->mimeType;
        } else if (decryptHandle->decryptApiType == DecryptApiType::WV_BASED) {
            *mimeType = MEDIA_MIMETYPE_CONTAINER_WVM;
            ALOGW("SniffWVM: found match\n");
        }
        *confidence = 10.0f;

        return true;
    }

    return false;
}
} //namespace android

