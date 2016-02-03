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

#ifndef AAPT_LOCALE_VALUE_H
#define AAPT_LOCALE_VALUE_H

#include "util/StringPiece.h"

#include <androidfw/ResourceTypes.h>
#include <string>
#include <vector>

namespace aapt {

/**
 * A convenience class to build and parse locales.
 */
struct LocaleValue {
    char language[4];
    char region[4];
    char script[4];
    char variant[8];

    inline LocaleValue();

    /**
     * Initialize this LocaleValue from a config string.
     */
    bool initFromFilterString(const StringPiece& config);

    /**
     * Initialize this LocaleValue from parts of a vector.
     */
    ssize_t initFromParts(std::vector<std::string>::iterator iter,
            std::vector<std::string>::iterator end);

    /**
     * Initialize this LocaleValue from a ResTable_config.
     */
    void initFromResTable(const android::ResTable_config& config);

    /**
     * Set the locale in a ResTable_config from this LocaleValue.
     */
    void writeTo(android::ResTable_config* out) const;

    std::string toDirName() const;

    inline int compare(const LocaleValue& other) const;

    inline bool operator<(const LocaleValue& o) const;
    inline bool operator<=(const LocaleValue& o) const;
    inline bool operator==(const LocaleValue& o) const;
    inline bool operator!=(const LocaleValue& o) const;
    inline bool operator>=(const LocaleValue& o) const;
    inline bool operator>(const LocaleValue& o) const;

private:
     void setLanguage(const char* language);
     void setRegion(const char* language);
     void setScript(const char* script);
     void setVariant(const char* variant);
};

//
// Implementation
//

LocaleValue::LocaleValue() {
    memset(this, 0, sizeof(LocaleValue));
}

int LocaleValue::compare(const LocaleValue& other) const {
    return memcmp(this, &other, sizeof(LocaleValue));
}

bool LocaleValue::operator<(const LocaleValue& o) const {
    return compare(o) < 0;
}

bool LocaleValue::operator<=(const LocaleValue& o) const {
    return compare(o) <= 0;
}

bool LocaleValue::operator==(const LocaleValue& o) const {
    return compare(o) == 0;
}

bool LocaleValue::operator!=(const LocaleValue& o) const {
    return compare(o) != 0;
}

bool LocaleValue::operator>=(const LocaleValue& o) const {
    return compare(o) >= 0;
}

bool LocaleValue::operator>(const LocaleValue& o) const {
    return compare(o) > 0;
}

} // namespace aapt

#endif // AAPT_LOCALE_VALUE_H
