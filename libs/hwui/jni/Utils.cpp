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

#include "Utils.h"
#include "SkUtils.h"
#include "SkData.h"

#include <inttypes.h>
#include <log/log.h>

using namespace android;

AssetStreamAdaptor::AssetStreamAdaptor(Asset* asset)
    : fAsset(asset)
{
}

bool AssetStreamAdaptor::rewind() {
    off64_t pos = fAsset->seek(0, SEEK_SET);
    if (pos == (off64_t)-1) {
        ALOGD("----- fAsset->seek(rewind) failed\n");
        return false;
    }
    return true;
}

size_t AssetStreamAdaptor::getLength() const {
    return fAsset->getLength();
}

bool AssetStreamAdaptor::isAtEnd() const {
    return fAsset->getRemainingLength() == 0;
}

SkStreamRewindable* AssetStreamAdaptor::onDuplicate() const {
    // Cannot create a duplicate, since each AssetStreamAdaptor
    // would be modifying the Asset.
    //return new AssetStreamAdaptor(fAsset);
    return NULL;
}

bool AssetStreamAdaptor::hasPosition() const {
    return fAsset->seek(0, SEEK_CUR) != -1;
}

size_t AssetStreamAdaptor::getPosition() const {
    const off64_t offset = fAsset->seek(0, SEEK_CUR);
    if (offset == -1) {
        ALOGD("---- fAsset->seek(0, SEEK_CUR) failed\n");
        return 0;
    }

    return offset;
}

bool AssetStreamAdaptor::seek(size_t position) {
    if (fAsset->seek(position, SEEK_SET) == -1) {
        ALOGD("---- fAsset->seek(0, SEEK_SET) failed\n");
        return false;
    }

    return true;
}

bool AssetStreamAdaptor::move(long offset) {
    if (fAsset->seek(offset, SEEK_CUR) == -1) {
        ALOGD("---- fAsset->seek(%li, SEEK_CUR) failed\n", offset);
        return false;
    }

    return true;
}

size_t AssetStreamAdaptor::read(void* buffer, size_t size) {
    ssize_t amount;

    if (NULL == buffer) {
        if (0 == size) {
            return 0;
        }
        // asset->seek returns new total offset
        // we want to return amount that was skipped

        off64_t oldOffset = fAsset->seek(0, SEEK_CUR);
        if (-1 == oldOffset) {
            ALOGD("---- fAsset->seek(oldOffset) failed\n");
            return 0;
        }
        off64_t newOffset = fAsset->seek(size, SEEK_CUR);
        if (-1 == newOffset) {
            ALOGD("---- fAsset->seek(%zu) failed\n", size);
            return 0;
        }
        amount = newOffset - oldOffset;
    } else {
        amount = fAsset->read(buffer, size);
    }

    if (amount < 0) {
        amount = 0;
    }
    return amount;
}

sk_sp<SkData> android::CopyAssetToData(Asset* asset) {
    if (NULL == asset) {
        return NULL;
    }

    const off64_t seekReturnVal = asset->seek(0, SEEK_SET);
    if ((off64_t)-1 == seekReturnVal) {
        ALOGD("---- copyAsset: asset rewind failed\n");
        return NULL;
    }

    const off64_t size = asset->getLength();
    if (size <= 0) {
        ALOGD("---- copyAsset: asset->getLength() returned %" PRId64 "\n", size);
        return NULL;
    }

    sk_sp<SkData> data(SkData::MakeUninitialized(size));
    const off64_t len = asset->read(data->writable_data(), size);
    if (len != size) {
        ALOGD("---- copyAsset: asset->read(%" PRId64 ") returned %" PRId64 "\n", size, len);
        return NULL;
    }

    return data;
}

jobject android::nullObjectReturn(const char msg[]) {
    if (msg) {
        ALOGD("--- %s\n", msg);
    }
    return NULL;
}

bool android::isSeekable(int descriptor) {
    return ::lseek64(descriptor, 0, SEEK_CUR) != -1;
}

JNIEnv* android::get_env_or_die(JavaVM* jvm) {
    JNIEnv* env;
    if (jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        LOG_ALWAYS_FATAL("Failed to get JNIEnv for JavaVM: %p", jvm);
    }
    return env;
}

JNIEnv* android::requireEnv(JavaVM* jvm) {
    JNIEnv* env;
    if (jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (jvm->AttachCurrentThreadAsDaemon(&env, nullptr) != JNI_OK) {
            LOG_ALWAYS_FATAL("Failed to AttachCurrentThread!");
        }
    }
    return env;
}
