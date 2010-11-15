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

#include "include/M3UParser.h"

#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaErrors.h>

namespace android {

M3UParser::M3UParser(
        const char *baseURI, const void *data, size_t size)
    : mInitCheck(NO_INIT),
      mBaseURI(baseURI),
      mIsExtM3U(false),
      mIsVariantPlaylist(false),
      mIsComplete(false) {
    mInitCheck = parse(data, size);
}

M3UParser::~M3UParser() {
}

status_t M3UParser::initCheck() const {
    return mInitCheck;
}

bool M3UParser::isExtM3U() const {
    return mIsExtM3U;
}

bool M3UParser::isVariantPlaylist() const {
    return mIsVariantPlaylist;
}

bool M3UParser::isComplete() const {
    return mIsComplete;
}

sp<AMessage> M3UParser::meta() {
    return mMeta;
}

size_t M3UParser::size() {
    return mItems.size();
}

bool M3UParser::itemAt(size_t index, AString *uri, sp<AMessage> *meta) {
    uri->clear();
    if (meta) { *meta = NULL; }

    if (index >= mItems.size()) {
        return false;
    }

    *uri = mItems.itemAt(index).mURI;

    if (meta) {
        *meta = mItems.itemAt(index).mMeta;
    }

    return true;
}

static bool MakeURL(const char *baseURL, const char *url, AString *out) {
    out->clear();

    if (strncasecmp("http://", baseURL, 7)
            && strncasecmp("file://", baseURL, 7)) {
        // Base URL must be absolute
        return false;
    }

    if (!strncasecmp("http://", url, 7)) {
        // "url" is already an absolute URL, ignore base URL.
        out->setTo(url);
        return true;
    }

    size_t n = strlen(baseURL);
    if (baseURL[n - 1] == '/') {
        out->setTo(baseURL);
        out->append(url);
    } else {
        const char *slashPos = strrchr(baseURL, '/');

        if (slashPos > &baseURL[6]) {
            out->setTo(baseURL, slashPos - baseURL);
        } else {
            out->setTo(baseURL);
        }

        out->append("/");
        out->append(url);
    }

    return true;
}

status_t M3UParser::parse(const void *_data, size_t size) {
    int32_t lineNo = 0;

    sp<AMessage> itemMeta;

    const char *data = (const char *)_data;
    size_t offset = 0;
    while (offset < size) {
        size_t offsetLF = offset;
        while (offsetLF < size && data[offsetLF] != '\n') {
            ++offsetLF;
        }
        if (offsetLF >= size) {
            break;
        }

        AString line;
        if (offsetLF > offset && data[offsetLF - 1] == '\r') {
            line.setTo(&data[offset], offsetLF - offset - 1);
        } else {
            line.setTo(&data[offset], offsetLF - offset);
        }

        // LOGI("#%s#", line.c_str());

        if (line.empty()) {
            offset = offsetLF + 1;
            continue;
        }

        if (lineNo == 0 && line == "#EXTM3U") {
            mIsExtM3U = true;
        }

        if (mIsExtM3U) {
            status_t err = OK;

            if (line.startsWith("#EXT-X-TARGETDURATION")) {
                if (mIsVariantPlaylist) {
                    return ERROR_MALFORMED;
                }
                err = parseMetaData(line, &mMeta, "target-duration");
            } else if (line.startsWith("#EXT-X-MEDIA-SEQUENCE")) {
                if (mIsVariantPlaylist) {
                    return ERROR_MALFORMED;
                }
                err = parseMetaData(line, &mMeta, "media-sequence");
            } else if (line.startsWith("#EXT-X-KEY")) {
                if (mIsVariantPlaylist) {
                    return ERROR_MALFORMED;
                }
                err = parseCipherInfo(line, &itemMeta);
            } else if (line.startsWith("#EXT-X-ENDLIST")) {
                mIsComplete = true;
            } else if (line.startsWith("#EXTINF")) {
                if (mIsVariantPlaylist) {
                    return ERROR_MALFORMED;
                }
                err = parseMetaData(line, &itemMeta, "duration");
            } else if (line.startsWith("#EXT-X-DISCONTINUITY")) {
                if (mIsVariantPlaylist) {
                    return ERROR_MALFORMED;
                }
                if (itemMeta == NULL) {
                    itemMeta = new AMessage;
                }
                itemMeta->setInt32("discontinuity", true);
            } else if (line.startsWith("#EXT-X-STREAM-INF")) {
                if (mMeta != NULL) {
                    return ERROR_MALFORMED;
                }
                mIsVariantPlaylist = true;
                err = parseStreamInf(line, &itemMeta);
            }

            if (err != OK) {
                return err;
            }
        }

        if (!line.startsWith("#")) {
            if (!mIsVariantPlaylist) {
                int32_t durationSecs;
                if (itemMeta == NULL
                        || !itemMeta->findInt32("duration", &durationSecs)) {
                    return ERROR_MALFORMED;
                }
            }

            mItems.push();
            Item *item = &mItems.editItemAt(mItems.size() - 1);

            CHECK(MakeURL(mBaseURI.c_str(), line.c_str(), &item->mURI));

            item->mMeta = itemMeta;

            itemMeta.clear();
        }

        offset = offsetLF + 1;
        ++lineNo;
    }

    return OK;
}

// static
status_t M3UParser::parseMetaData(
        const AString &line, sp<AMessage> *meta, const char *key) {
    ssize_t colonPos = line.find(":");

    if (colonPos < 0) {
        return ERROR_MALFORMED;
    }

    int32_t x;
    status_t err = ParseInt32(line.c_str() + colonPos + 1, &x);

    if (err != OK) {
        return err;
    }

    if (meta->get() == NULL) {
        *meta = new AMessage;
    }
    (*meta)->setInt32(key, x);

    return OK;
}

// static
status_t M3UParser::parseStreamInf(
        const AString &line, sp<AMessage> *meta) {
    ssize_t colonPos = line.find(":");

    if (colonPos < 0) {
        return ERROR_MALFORMED;
    }

    size_t offset = colonPos + 1;

    while (offset < line.size()) {
        ssize_t end = line.find(",", offset);
        if (end < 0) {
            end = line.size();
        }

        AString attr(line, offset, end - offset);
        attr.trim();

        offset = end + 1;

        ssize_t equalPos = attr.find("=");
        if (equalPos < 0) {
            continue;
        }

        AString key(attr, 0, equalPos);
        key.trim();

        AString val(attr, equalPos + 1, attr.size() - equalPos - 1);
        val.trim();

        LOGV("key=%s value=%s", key.c_str(), val.c_str());

        if (!strcasecmp("bandwidth", key.c_str())) {
            const char *s = val.c_str();
            char *end;
            unsigned long x = strtoul(s, &end, 10);

            if (end == s || *end != '\0') {
                // malformed
                continue;
            }

            if (meta->get() == NULL) {
                *meta = new AMessage;
            }
            (*meta)->setInt32("bandwidth", x);
        }
    }

    return OK;
}

// static
status_t M3UParser::parseCipherInfo(
        const AString &line, sp<AMessage> *meta) {
    ssize_t colonPos = line.find(":");

    if (colonPos < 0) {
        return ERROR_MALFORMED;
    }

    size_t offset = colonPos + 1;

    while (offset < line.size()) {
        ssize_t end = line.find(",", offset);
        if (end < 0) {
            end = line.size();
        }

        AString attr(line, offset, end - offset);
        attr.trim();

        offset = end + 1;

        ssize_t equalPos = attr.find("=");
        if (equalPos < 0) {
            continue;
        }

        AString key(attr, 0, equalPos);
        key.trim();

        AString val(attr, equalPos + 1, attr.size() - equalPos - 1);
        val.trim();

        LOGV("key=%s value=%s", key.c_str(), val.c_str());

        key.tolower();

        if (key == "method" || key == "uri" || key == "iv") {
            if (meta->get() == NULL) {
                *meta = new AMessage;
            }

            key.insert(AString("cipher-"), 0);

            (*meta)->setString(key.c_str(), val.c_str(), val.size());
        }
    }

    return OK;
}

// static
status_t M3UParser::ParseInt32(const char *s, int32_t *x) {
    char *end;
    long lval = strtol(s, &end, 10);

    if (end == s || (*end != '\0' && *end != ',')) {
        return ERROR_MALFORMED;
    }

    *x = (int32_t)lval;

    return OK;
}

}  // namespace android
