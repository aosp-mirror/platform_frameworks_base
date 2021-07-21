/*
 * Copyright (C) 2020 The Android Open Source Project
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

#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <string>
#include <memory>

#include "android-base/logging.h"
#include "androidfw/CursorWindow.h"
#include "binder/Parcel.h"

#include <fuzzer/FuzzedDataProvider.h>

using android::CursorWindow;
using android::Parcel;

extern "C" int LLVMFuzzerInitialize(int *, char ***) {
    setenv("ANDROID_LOG_TAGS", "*:s", 1);
    android::base::InitLogging(nullptr, &android::base::StderrLogger);
    return 0;
}

extern "C" int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
    Parcel p;
    p.setData(data, size);

    CursorWindow* w = nullptr;
    if (!CursorWindow::createFromParcel(&p, &w)) {
        LOG(WARNING) << "Valid cursor with " << w->getNumRows() << " rows, "
                << w->getNumColumns() << " cols";

        // Try obtaining heap allocations for most items; we trim the
        // search space to speed things up
        auto rows = std::min(w->getNumRows(), static_cast<uint32_t>(128));
        auto cols = std::min(w->getNumColumns(), static_cast<uint32_t>(128));
        for (auto row = 0; row < rows; row++) {
            for (auto col = 0; col < cols; col++) {
                auto field = w->getFieldSlot(row, col);
                if (!field) continue;
                switch (w->getFieldSlotType(field)) {
                case CursorWindow::FIELD_TYPE_STRING: {
                    size_t size;
                    w->getFieldSlotValueString(field, &size);
                    break;
                }
                case CursorWindow::FIELD_TYPE_BLOB: {
                    size_t size;
                    w->getFieldSlotValueBlob(field, &size);
                    break;
                }
                }
            }
        }

        // Finally, try obtaining the furthest valid field
        if (rows > 0 && cols > 0) {
            w->getFieldSlot(w->getNumRows() - 1, w->getNumColumns() - 1);
        }
    }
    delete w;

    return 0;
}
