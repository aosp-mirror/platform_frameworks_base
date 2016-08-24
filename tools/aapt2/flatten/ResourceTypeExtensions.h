/*
 * Copyright (C) 2015 The Android Open Source Project
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

#ifndef AAPT_RESOURCE_TYPE_EXTENSIONS_H
#define AAPT_RESOURCE_TYPE_EXTENSIONS_H

#include <androidfw/ResourceTypes.h>

namespace aapt {

/**
 * An alternative struct to use instead of ResTable_map_entry. This one is a standard_layout
 * struct.
 */
struct ResTable_entry_ext {
    android::ResTable_entry entry;
    android::ResTable_ref parent;
    uint32_t count;
};

} // namespace aapt

#endif // AAPT_RESOURCE_TYPE_EXTENSIONS_H
