/*
 * Copyright (C) 2023 The Android Open Source Project
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
#include "ScopedParcel.h"

#ifdef __ANDROID__  // Layoutlib does not support parcel

using namespace android;

int32_t ScopedParcel::readInt32() {
    int32_t temp = 0;
    // TODO: This behavior-matches what android::Parcel does
    // but this should probably be better
    if (AParcel_readInt32(mParcel, &temp) != STATUS_OK) {
        temp = 0;
    }
    return temp;
}

uint32_t ScopedParcel::readUint32() {
    uint32_t temp = 0;
    // TODO: This behavior-matches what android::Parcel does
    // but this should probably be better
    if (AParcel_readUint32(mParcel, &temp) != STATUS_OK) {
        temp = 0;
    }
    return temp;
}

float ScopedParcel::readFloat() {
    float temp = 0.;
    if (AParcel_readFloat(mParcel, &temp) != STATUS_OK) {
        temp = 0.;
    }
    return temp;
}

std::optional<sk_sp<SkData>> ScopedParcel::readData() {
    struct Data {
        void* ptr = nullptr;
        size_t size = 0;
    } data;
    auto error = AParcel_readByteArray(
            mParcel, &data, [](void* arrayData, int32_t length, int8_t** outBuffer) -> bool {
                Data* data = reinterpret_cast<Data*>(arrayData);
                if (length > 0) {
                    data->ptr = sk_malloc_canfail(length);
                    if (!data->ptr) {
                        return false;
                    }
                    *outBuffer = reinterpret_cast<int8_t*>(data->ptr);
                    data->size = length;
                }
                return true;
            });
    if (error != STATUS_OK || data.size <= 0) {
        sk_free(data.ptr);
        return std::nullopt;
    } else {
        return SkData::MakeFromMalloc(data.ptr, data.size);
    }
}

void ScopedParcel::writeData(const std::optional<sk_sp<SkData>>& optData) {
    if (optData) {
        const auto& data = *optData;
        AParcel_writeByteArray(mParcel, reinterpret_cast<const int8_t*>(data->data()),
                               data->size());
    } else {
        AParcel_writeByteArray(mParcel, nullptr, -1);
    }
}
#endif  // __ANDROID__ // Layoutlib does not support parcel
