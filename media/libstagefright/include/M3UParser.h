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

#ifndef M3U_PARSER_H_

#define M3U_PARSER_H_

#include <media/stagefright/foundation/ABase.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/foundation/AString.h>
#include <utils/Vector.h>

namespace android {

struct M3UParser : public RefBase {
    M3UParser(const char *baseURI, const void *data, size_t size);

    status_t initCheck() const;

    bool isExtM3U() const;
    bool isVariantPlaylist() const;
    bool isComplete() const;

    sp<AMessage> meta();

    size_t size();
    bool itemAt(size_t index, AString *uri, sp<AMessage> *meta = NULL);

protected:
    virtual ~M3UParser();

private:
    struct Item {
        AString mURI;
        sp<AMessage> mMeta;
    };

    status_t mInitCheck;

    AString mBaseURI;
    bool mIsExtM3U;
    bool mIsVariantPlaylist;
    bool mIsComplete;

    sp<AMessage> mMeta;
    Vector<Item> mItems;

    status_t parse(const void *data, size_t size);

    static status_t parseMetaData(
            const AString &line, sp<AMessage> *meta, const char *key);

    static status_t parseMetaDataDuration(
            const AString &line, sp<AMessage> *meta, const char *key);

    static status_t parseStreamInf(
            const AString &line, sp<AMessage> *meta);

    static status_t parseCipherInfo(
            const AString &line, sp<AMessage> *meta, const AString &baseURI);

    static status_t ParseInt32(const char *s, int32_t *x);
    static status_t ParseDouble(const char *s, double *x);

    DISALLOW_EVIL_CONSTRUCTORS(M3UParser);
};

}  // namespace android

#endif  // M3U_PARSER_H_
