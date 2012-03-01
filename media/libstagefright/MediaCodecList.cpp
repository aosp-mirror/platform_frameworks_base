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

//#define LOG_NDEBUG 0
#define LOG_TAG "MediaCodecList"
#include <utils/Log.h>

#include <media/stagefright/MediaCodecList.h>

#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/MediaErrors.h>
#include <utils/threads.h>

#include <expat.h>

namespace android {

static Mutex sInitMutex;

// static
MediaCodecList *MediaCodecList::sCodecList;

// static
const MediaCodecList *MediaCodecList::getInstance() {
    Mutex::Autolock autoLock(sInitMutex);

    if (sCodecList == NULL) {
        sCodecList = new MediaCodecList;
    }

    return sCodecList->initCheck() == OK ? sCodecList : NULL;
}

MediaCodecList::MediaCodecList()
    : mInitCheck(NO_INIT) {
    FILE *file = fopen("/etc/media_codecs.xml", "r");

    if (file == NULL) {
        ALOGW("unable to open media codecs configuration xml file.");
        return;
    }

    parseXMLFile(file);

    if (mInitCheck == OK) {
        // These are currently still used by the video editing suite.

        addMediaCodec(true /* encoder */, "AACEncoder", "audio/mp4a-latm");
        addMediaCodec(true /* encoder */, "AVCEncoder", "video/avc");

        addMediaCodec(true /* encoder */, "M4vH263Encoder");
        addType("video/3gpp");
        addType("video/mp4v-es");
    }

#if 0
    for (size_t i = 0; i < mCodecInfos.size(); ++i) {
        const CodecInfo &info = mCodecInfos.itemAt(i);

        AString line = info.mName;
        line.append(" supports ");
        for (size_t j = 0; j < mTypes.size(); ++j) {
            uint32_t value = mTypes.valueAt(j);

            if (info.mTypes & (1ul << value)) {
                line.append(mTypes.keyAt(j));
                line.append(" ");
            }
        }

        ALOGI("%s", line.c_str());
    }
#endif

    fclose(file);
    file = NULL;
}

MediaCodecList::~MediaCodecList() {
}

status_t MediaCodecList::initCheck() const {
    return mInitCheck;
}

void MediaCodecList::parseXMLFile(FILE *file) {
    mInitCheck = OK;
    mCurrentSection = SECTION_TOPLEVEL;
    mDepth = 0;

    XML_Parser parser = ::XML_ParserCreate(NULL);
    CHECK(parser != NULL);

    ::XML_SetUserData(parser, this);
    ::XML_SetElementHandler(
            parser, StartElementHandlerWrapper, EndElementHandlerWrapper);

    const int BUFF_SIZE = 512;
    while (mInitCheck == OK) {
        void *buff = ::XML_GetBuffer(parser, BUFF_SIZE);
        if (buff == NULL) {
            ALOGE("failed to in call to XML_GetBuffer()");
            mInitCheck = UNKNOWN_ERROR;
            break;
        }

        int bytes_read = ::fread(buff, 1, BUFF_SIZE, file);
        if (bytes_read < 0) {
            ALOGE("failed in call to read");
            mInitCheck = ERROR_IO;
            break;
        }

        if (::XML_ParseBuffer(parser, bytes_read, bytes_read == 0)
                != XML_STATUS_OK) {
            mInitCheck = ERROR_MALFORMED;
            break;
        }

        if (bytes_read == 0) {
            break;
        }
    }

    ::XML_ParserFree(parser);

    if (mInitCheck == OK) {
        for (size_t i = mCodecInfos.size(); i-- > 0;) {
            CodecInfo *info = &mCodecInfos.editItemAt(i);

            if (info->mTypes == 0) {
                // No types supported by this component???

                ALOGW("Component %s does not support any type of media?",
                      info->mName.c_str());

                mCodecInfos.removeAt(i);
            }
        }
    }

    if (mInitCheck != OK) {
        mCodecInfos.clear();
        mCodecQuirks.clear();
    }
}

// static
void MediaCodecList::StartElementHandlerWrapper(
        void *me, const char *name, const char **attrs) {
    static_cast<MediaCodecList *>(me)->startElementHandler(name, attrs);
}

// static
void MediaCodecList::EndElementHandlerWrapper(void *me, const char *name) {
    static_cast<MediaCodecList *>(me)->endElementHandler(name);
}

void MediaCodecList::startElementHandler(
        const char *name, const char **attrs) {
    if (mInitCheck != OK) {
        return;
    }

    switch (mCurrentSection) {
        case SECTION_TOPLEVEL:
        {
            if (!strcmp(name, "Decoders")) {
                mCurrentSection = SECTION_DECODERS;
            } else if (!strcmp(name, "Encoders")) {
                mCurrentSection = SECTION_ENCODERS;
            }
            break;
        }

        case SECTION_DECODERS:
        {
            if (!strcmp(name, "MediaCodec")) {
                mInitCheck =
                    addMediaCodecFromAttributes(false /* encoder */, attrs);

                mCurrentSection = SECTION_DECODER;
            }
            break;
        }

        case SECTION_ENCODERS:
        {
            if (!strcmp(name, "MediaCodec")) {
                mInitCheck =
                    addMediaCodecFromAttributes(true /* encoder */, attrs);

                mCurrentSection = SECTION_ENCODER;
            }
            break;
        }

        case SECTION_DECODER:
        case SECTION_ENCODER:
        {
            if (!strcmp(name, "Quirk")) {
                mInitCheck = addQuirk(attrs);
            } else if (!strcmp(name, "Type")) {
                mInitCheck = addTypeFromAttributes(attrs);
            }
            break;
        }

        default:
            break;
    }

    ++mDepth;
}

void MediaCodecList::endElementHandler(const char *name) {
    if (mInitCheck != OK) {
        return;
    }

    switch (mCurrentSection) {
        case SECTION_DECODERS:
        {
            if (!strcmp(name, "Decoders")) {
                mCurrentSection = SECTION_TOPLEVEL;
            }
            break;
        }

        case SECTION_ENCODERS:
        {
            if (!strcmp(name, "Encoders")) {
                mCurrentSection = SECTION_TOPLEVEL;
            }
            break;
        }

        case SECTION_DECODER:
        {
            if (!strcmp(name, "MediaCodec")) {
                mCurrentSection = SECTION_DECODERS;
            }
            break;
        }

        case SECTION_ENCODER:
        {
            if (!strcmp(name, "MediaCodec")) {
                mCurrentSection = SECTION_ENCODERS;
            }
            break;
        }

        default:
            break;
    }

    --mDepth;
}

status_t MediaCodecList::addMediaCodecFromAttributes(
        bool encoder, const char **attrs) {
    const char *name = NULL;
    const char *type = NULL;

    size_t i = 0;
    while (attrs[i] != NULL) {
        if (!strcmp(attrs[i], "name")) {
            if (attrs[i + 1] == NULL) {
                return -EINVAL;
            }
            name = attrs[i + 1];
            ++i;
        } else if (!strcmp(attrs[i], "type")) {
            if (attrs[i + 1] == NULL) {
                return -EINVAL;
            }
            type = attrs[i + 1];
            ++i;
        } else {
            return -EINVAL;
        }

        ++i;
    }

    if (name == NULL) {
        return -EINVAL;
    }

    addMediaCodec(encoder, name, type);

    return OK;
}

void MediaCodecList::addMediaCodec(
        bool encoder, const char *name, const char *type) {
    mCodecInfos.push();
    CodecInfo *info = &mCodecInfos.editItemAt(mCodecInfos.size() - 1);
    info->mName = name;
    info->mIsEncoder = encoder;
    info->mTypes = 0;
    info->mQuirks = 0;

    if (type != NULL) {
        addType(type);
    }
}

status_t MediaCodecList::addQuirk(const char **attrs) {
    const char *name = NULL;

    size_t i = 0;
    while (attrs[i] != NULL) {
        if (!strcmp(attrs[i], "name")) {
            if (attrs[i + 1] == NULL) {
                return -EINVAL;
            }
            name = attrs[i + 1];
            ++i;
        } else {
            return -EINVAL;
        }

        ++i;
    }

    if (name == NULL) {
        return -EINVAL;
    }

    uint32_t bit;
    ssize_t index = mCodecQuirks.indexOfKey(name);
    if (index < 0) {
        bit = mCodecQuirks.size();

        if (bit == 32) {
            ALOGW("Too many distinct quirk names in configuration.");
            return OK;
        }

        mCodecQuirks.add(name, bit);
    } else {
        bit = mCodecQuirks.valueAt(index);
    }

    CodecInfo *info = &mCodecInfos.editItemAt(mCodecInfos.size() - 1);
    info->mQuirks |= 1ul << bit;

    return OK;
}

status_t MediaCodecList::addTypeFromAttributes(const char **attrs) {
    const char *name = NULL;

    size_t i = 0;
    while (attrs[i] != NULL) {
        if (!strcmp(attrs[i], "name")) {
            if (attrs[i + 1] == NULL) {
                return -EINVAL;
            }
            name = attrs[i + 1];
            ++i;
        } else {
            return -EINVAL;
        }

        ++i;
    }

    if (name == NULL) {
        return -EINVAL;
    }

    addType(name);

    return OK;
}

void MediaCodecList::addType(const char *name) {
    uint32_t bit;
    ssize_t index = mTypes.indexOfKey(name);
    if (index < 0) {
        bit = mTypes.size();

        if (bit == 32) {
            ALOGW("Too many distinct type names in configuration.");
            return;
        }

        mTypes.add(name, bit);
    } else {
        bit = mTypes.valueAt(index);
    }

    CodecInfo *info = &mCodecInfos.editItemAt(mCodecInfos.size() - 1);
    info->mTypes |= 1ul << bit;
}

ssize_t MediaCodecList::findCodecByType(
        const char *type, bool encoder, size_t startIndex) const {
    ssize_t typeIndex = mTypes.indexOfKey(type);

    if (typeIndex < 0) {
        return -ENOENT;
    }

    uint32_t typeMask = 1ul << mTypes.valueAt(typeIndex);

    while (startIndex < mCodecInfos.size()) {
        const CodecInfo &info = mCodecInfos.itemAt(startIndex);

        if (info.mIsEncoder == encoder && (info.mTypes & typeMask)) {
            return startIndex;
        }

        ++startIndex;
    }

    return -ENOENT;
}

ssize_t MediaCodecList::findCodecByName(const char *name) const {
    for (size_t i = 0; i < mCodecInfos.size(); ++i) {
        const CodecInfo &info = mCodecInfos.itemAt(i);

        if (info.mName == name) {
            return i;
        }
    }

    return -ENOENT;
}

const char *MediaCodecList::getCodecName(size_t index) const {
    if (index >= mCodecInfos.size()) {
        return NULL;
    }

    const CodecInfo &info = mCodecInfos.itemAt(index);
    return info.mName.c_str();
}

bool MediaCodecList::codecHasQuirk(
        size_t index, const char *quirkName) const {
    if (index >= mCodecInfos.size()) {
        return NULL;
    }

    const CodecInfo &info = mCodecInfos.itemAt(index);

    if (info.mQuirks != 0) {
        ssize_t index = mCodecQuirks.indexOfKey(quirkName);
        if (index >= 0 && info.mQuirks & (1ul << mCodecQuirks.valueAt(index))) {
            return true;
        }
    }

    return false;
}

}  // namespace android
