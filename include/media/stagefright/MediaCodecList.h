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

#ifndef MEDIA_CODEC_LIST_H_

#define MEDIA_CODEC_LIST_H_

#include <media/stagefright/foundation/ABase.h>
#include <media/stagefright/foundation/AString.h>

#include <sys/types.h>
#include <utils/Errors.h>
#include <utils/KeyedVector.h>
#include <utils/Vector.h>

namespace android {

struct MediaCodecList {
    static const MediaCodecList *getInstance();

    ssize_t findCodecByType(
            const char *type, bool encoder, size_t startIndex = 0) const;

    ssize_t findCodecByName(const char *name) const;

    const char *getCodecName(size_t index) const;
    bool codecHasQuirk(size_t index, const char *quirkName) const;

private:
    enum Section {
        SECTION_TOPLEVEL,
        SECTION_DECODERS,
        SECTION_DECODER,
        SECTION_ENCODERS,
        SECTION_ENCODER,
    };

    struct CodecInfo {
        AString mName;
        bool mIsEncoder;
        uint32_t mTypes;
        uint32_t mQuirks;
    };

    static MediaCodecList *sCodecList;

    status_t mInitCheck;
    Section mCurrentSection;
    int32_t mDepth;

    Vector<CodecInfo> mCodecInfos;
    KeyedVector<AString, size_t> mCodecQuirks;
    KeyedVector<AString, size_t> mTypes;

    MediaCodecList();
    ~MediaCodecList();

    status_t initCheck() const;
    void parseXMLFile(FILE *file);

    static void StartElementHandlerWrapper(
            void *me, const char *name, const char **attrs);

    static void EndElementHandlerWrapper(void *me, const char *name);

    void startElementHandler(const char *name, const char **attrs);
    void endElementHandler(const char *name);

    status_t addMediaCodecFromAttributes(bool encoder, const char **attrs);
    void addMediaCodec(bool encoder, const char *name, const char *type = NULL);

    status_t addQuirk(const char **attrs);
    status_t addTypeFromAttributes(const char **attrs);
    void addType(const char *name);

    DISALLOW_EVIL_CONSTRUCTORS(MediaCodecList);
};

}  // namespace android

#endif  // MEDIA_CODEC_LIST_H_

