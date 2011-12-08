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

#include "include/AMRExtractor.h"
#include "include/MP3Extractor.h"
#include "include/MPEG4Extractor.h"
#include "include/WAVExtractor.h"
#include "include/OggExtractor.h"
#include "include/MPEG2PSExtractor.h"
#include "include/MPEG2TSExtractor.h"
#include "include/NuCachedSource2.h"
#include "include/HTTPBase.h"
#include "include/DRMExtractor.h"
#include "include/FLACExtractor.h"
#include "include/AACExtractor.h"
#include "include/WVMExtractor.h"

#include "matroska/MatroskaExtractor.h"

#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/DataSource.h>
#include <media/stagefright/FileSource.h>
#include <media/stagefright/MediaErrors.h>
#include <utils/String8.h>

#include <cutils/properties.h>

namespace android {

bool DataSource::getUInt16(off64_t offset, uint16_t *x) {
    *x = 0;

    uint8_t byte[2];
    if (readAt(offset, byte, 2) != 2) {
        return false;
    }

    *x = (byte[0] << 8) | byte[1];

    return true;
}

status_t DataSource::getSize(off64_t *size) {
    *size = 0;

    return ERROR_UNSUPPORTED;
}

////////////////////////////////////////////////////////////////////////////////

Mutex DataSource::gSnifferMutex;
List<DataSource::SnifferFunc> DataSource::gSniffers;

bool DataSource::sniff(
        String8 *mimeType, float *confidence, sp<AMessage> *meta) {
    *mimeType = "";
    *confidence = 0.0f;
    meta->clear();

    Mutex::Autolock autoLock(gSnifferMutex);
    for (List<SnifferFunc>::iterator it = gSniffers.begin();
         it != gSniffers.end(); ++it) {
        String8 newMimeType;
        float newConfidence;
        sp<AMessage> newMeta;
        if ((*it)(this, &newMimeType, &newConfidence, &newMeta)) {
            if (newConfidence > *confidence) {
                *mimeType = newMimeType;
                *confidence = newConfidence;
                *meta = newMeta;
            }
        }
    }

    return *confidence > 0.0;
}

// static
void DataSource::RegisterSniffer(SnifferFunc func) {
    Mutex::Autolock autoLock(gSnifferMutex);

    for (List<SnifferFunc>::iterator it = gSniffers.begin();
         it != gSniffers.end(); ++it) {
        if (*it == func) {
            return;
        }
    }

    gSniffers.push_back(func);
}

// static
void DataSource::RegisterDefaultSniffers() {
    RegisterSniffer(SniffMPEG4);
    RegisterSniffer(SniffMatroska);
    RegisterSniffer(SniffOgg);
    RegisterSniffer(SniffWAV);
    RegisterSniffer(SniffFLAC);
    RegisterSniffer(SniffAMR);
    RegisterSniffer(SniffMPEG2TS);
    RegisterSniffer(SniffMP3);
    RegisterSniffer(SniffAAC);
    RegisterSniffer(SniffMPEG2PS);
    RegisterSniffer(SniffWVM);

    char value[PROPERTY_VALUE_MAX];
    if (property_get("drm.service.enabled", value, NULL)
            && (!strcmp(value, "1") || !strcasecmp(value, "true"))) {
        RegisterSniffer(SniffDRM);
    }
}

// static
sp<DataSource> DataSource::CreateFromURI(
        const char *uri, const KeyedVector<String8, String8> *headers) {
    sp<DataSource> source;
    if (!strncasecmp("file://", uri, 7)) {
        source = new FileSource(uri + 7);
    } else if (!strncasecmp("http://", uri, 7)
            || !strncasecmp("https://", uri, 8)) {
        sp<HTTPBase> httpSource = HTTPBase::Create();
        if (httpSource->connect(uri, headers) != OK) {
            return NULL;
        }
        source = new NuCachedSource2(httpSource);
    } else {
        // Assume it's a filename.
        source = new FileSource(uri);
    }

    if (source == NULL || source->initCheck() != OK) {
        return NULL;
    }

    return source;
}

String8 DataSource::getMIMEType() const {
    return String8("application/octet-stream");
}

}  // namespace android
