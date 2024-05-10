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

#ifndef SKIA_WRAPPER_H_
#define SKIA_WRAPPER_H_

#include <SkRefCnt.h>
#include <utils/RefBase.h>

namespace android::uirenderer {

template <typename T>
class SkiaWrapper : public VirtualLightRefBase {
public:
    sk_sp<T> getInstance() {
        if (mInstance != nullptr && shouldDiscardInstance()) {
            mInstance = nullptr;
        }

        if (mInstance == nullptr) {
            mInstance = createInstance();
            mGenerationId++;
        }
        return mInstance;
    }

    virtual bool shouldDiscardInstance() const { return false; }

    void discardInstance() { mInstance = nullptr; }

    [[nodiscard]] int32_t getGenerationId() const { return mGenerationId; }

protected:
    virtual sk_sp<T> createInstance() = 0;

private:
    sk_sp<T> mInstance = nullptr;
    int32_t mGenerationId = 0;
};

}  // namespace android::uirenderer

#endif  // SKIA_WRAPPER_H_
