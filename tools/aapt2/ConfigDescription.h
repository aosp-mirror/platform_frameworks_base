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

#ifndef AAPT_CONFIG_DESCRIPTION_H
#define AAPT_CONFIG_DESCRIPTION_H

#include "util/StringPiece.h"

#include <androidfw/ResourceTypes.h>
#include <ostream>

namespace aapt {

/*
 * Subclass of ResTable_config that adds convenient
 * initialization and comparison methods.
 */
struct ConfigDescription : public android::ResTable_config {
    /**
     * Returns an immutable default config.
     */
    static const ConfigDescription& defaultConfig();

    /*
     * Parse a string of the form 'fr-sw600dp-land' and fill in the
     * given ResTable_config with resulting configuration parameters.
     *
     * The resulting configuration has the appropriate sdkVersion defined
     * for backwards compatibility.
     */
    static bool parse(const StringPiece& str, ConfigDescription* out = nullptr);

    /**
     * If the configuration uses an axis that was added after
     * the original Android release, make sure the SDK version
     * is set accordingly.
     */
    static void applyVersionForCompatibility(ConfigDescription* config);

    ConfigDescription();
    ConfigDescription(const android::ResTable_config& o);
    ConfigDescription(const ConfigDescription& o);
    ConfigDescription(ConfigDescription&& o);

    ConfigDescription& operator=(const android::ResTable_config& o);
    ConfigDescription& operator=(const ConfigDescription& o);
    ConfigDescription& operator=(ConfigDescription&& o);

    bool operator<(const ConfigDescription& o) const;
    bool operator<=(const ConfigDescription& o) const;
    bool operator==(const ConfigDescription& o) const;
    bool operator!=(const ConfigDescription& o) const;
    bool operator>=(const ConfigDescription& o) const;
    bool operator>(const ConfigDescription& o) const;
};

inline ConfigDescription::ConfigDescription() {
    memset(this, 0, sizeof(*this));
    size = sizeof(android::ResTable_config);
}

inline ConfigDescription::ConfigDescription(const android::ResTable_config& o) {
    *static_cast<android::ResTable_config*>(this) = o;
    size = sizeof(android::ResTable_config);
}

inline ConfigDescription::ConfigDescription(const ConfigDescription& o) {
    *static_cast<android::ResTable_config*>(this) = o;
}

inline ConfigDescription::ConfigDescription(ConfigDescription&& o) {
    *this = o;
}

inline ConfigDescription& ConfigDescription::operator=(const android::ResTable_config& o) {
    *static_cast<android::ResTable_config*>(this) = o;
    size = sizeof(android::ResTable_config);
    return *this;
}

inline ConfigDescription& ConfigDescription::operator=(const ConfigDescription& o) {
    *static_cast<android::ResTable_config*>(this) = o;
    return *this;
}

inline ConfigDescription& ConfigDescription::operator=(ConfigDescription&& o) {
    *this = o;
    return *this;
}

inline bool ConfigDescription::operator<(const ConfigDescription& o) const {
    return compare(o) < 0;
}

inline bool ConfigDescription::operator<=(const ConfigDescription& o) const {
    return compare(o) <= 0;
}

inline bool ConfigDescription::operator==(const ConfigDescription& o) const {
    return compare(o) == 0;
}

inline bool ConfigDescription::operator!=(const ConfigDescription& o) const {
    return compare(o) != 0;
}

inline bool ConfigDescription::operator>=(const ConfigDescription& o) const {
    return compare(o) >= 0;
}

inline bool ConfigDescription::operator>(const ConfigDescription& o) const {
    return compare(o) > 0;
}

inline ::std::ostream& operator<<(::std::ostream& out, const ConfigDescription& o) {
    return out << o.toString().string();
}

} // namespace aapt

#endif // AAPT_CONFIG_DESCRIPTION_H
