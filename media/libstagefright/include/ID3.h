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

#ifndef ID3_H_

#define ID3_H_

#include <utils/RefBase.h>

namespace android {

struct DataSource;
struct String8;

struct ID3 {
    enum Version {
        ID3_UNKNOWN,
        ID3_V1,
        ID3_V1_1,
        ID3_V2_2,
        ID3_V2_3,
        ID3_V2_4,
    };

    ID3(const sp<DataSource> &source);
    ~ID3();

    bool isValid() const;

    Version version() const;

    const void *getAlbumArt(size_t *length, String8 *mime) const;

    struct Iterator {
        Iterator(const ID3 &parent, const char *id);
        ~Iterator();

        bool done() const;
        void getID(String8 *id) const;
        void getString(String8 *s) const;
        const uint8_t *getData(size_t *length) const;
        void next();

    private:
        const ID3 &mParent;
        char *mID;
        size_t mOffset;

        const uint8_t *mFrameData;
        size_t mFrameSize;

        void findFrame();

        size_t getHeaderLength() const;

        Iterator(const Iterator &);
        Iterator &operator=(const Iterator &);
    };

private:
    bool mIsValid;
    uint8_t *mData;
    size_t mSize;
    size_t mFirstFrameOffset;
    Version mVersion;

    bool parseV1(const sp<DataSource> &source);
    bool parseV2(const sp<DataSource> &source);
    void removeUnsynchronization();
    bool removeUnsynchronizationV2_4(bool iTunesHack);

    static bool ParseSyncsafeInteger(const uint8_t encoded[4], size_t *x);

    ID3(const ID3 &);
    ID3 &operator=(const ID3 &);
};

}  // namespace android

#endif  // ID3_H_

