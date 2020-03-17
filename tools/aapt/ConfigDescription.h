/*
 * Copyright (C) 2014 The Android Open Source Project
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

#ifndef __CONFIG_DESCRIPTION_H
#define __CONFIG_DESCRIPTION_H

#include <androidfw/ResourceTypes.h>

/**
 * Subclass of ResTable_config that adds convenient
 * initialization and comparison methods.
 */
struct ConfigDescription : public android::ResTable_config {
    ConfigDescription() {
        memset(this, 0, sizeof(*this));
        size = sizeof(android::ResTable_config);
    }

    ConfigDescription(const android::ResTable_config&o) {  // NOLINT(google-explicit-constructor)
        *static_cast<android::ResTable_config*>(this) = o;
        size = sizeof(android::ResTable_config);
    }

    ConfigDescription(const ConfigDescription&o)
        : android::ResTable_config(o) {
    }

    ConfigDescription& operator=(const android::ResTable_config& o) {
        *static_cast<android::ResTable_config*>(this) = o;
        size = sizeof(android::ResTable_config);
        return *this;
    }

    ConfigDescription& operator=(const ConfigDescription& o) {
        *static_cast<android::ResTable_config*>(this) = o;
        return *this;
    }

    inline bool operator<(const ConfigDescription& o) const { return compare(o) < 0; }
    inline bool operator<=(const ConfigDescription& o) const { return compare(o) <= 0; }
    inline bool operator==(const ConfigDescription& o) const { return compare(o) == 0; }
    inline bool operator!=(const ConfigDescription& o) const { return compare(o) != 0; }
    inline bool operator>=(const ConfigDescription& o) const { return compare(o) >= 0; }
    inline bool operator>(const ConfigDescription& o) const { return compare(o) > 0; }
};

#endif // __CONFIG_DESCRIPTION_H
