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

#include "SplitDescription.h"

#include "aapt/AaptConfig.h"
#include "aapt/AaptUtil.h"

#include <utils/String8.h>
#include <utils/Vector.h>

using namespace android;

namespace split {

SplitDescription::SplitDescription()
: abi(abi::Variant_none) {
}

int SplitDescription::compare(const SplitDescription& rhs) const {
    int cmp;
    cmp = (int)abi - (int)rhs.abi;
    if (cmp != 0) return cmp;
    return config.compareLogical(rhs.config);
}

bool SplitDescription::isBetterThan(const SplitDescription& o, const SplitDescription& target) const {
    if (abi != abi::Variant_none || o.abi != abi::Variant_none) {
        abi::Family family = abi::getFamily(abi);
        abi::Family oFamily = abi::getFamily(o.abi);
        if (family != oFamily) {
            return family != abi::Family_none;
        }

        if (int(target.abi) - int(abi) < int(target.abi) - int(o.abi)) {
            return true;
        }
    }
    return config.isBetterThan(o.config, &target.config);
}

bool SplitDescription::match(const SplitDescription& o) const {
    if (abi != abi::Variant_none) {
        abi::Family family = abi::getFamily(abi);
        abi::Family oFamily = abi::getFamily(o.abi);
        if (family != oFamily) {
            return false;
        }

        if (int(abi) > int(o.abi)) {
            return false;
        }
    }
    return config.match(o.config);
}

String8 SplitDescription::toString() const {
    String8 extension;
    if (abi != abi::Variant_none) {
        if (extension.isEmpty()) {
            extension.append(":");
        } else {
            extension.append("-");
        }
        extension.append(abi::toString(abi));
    }
    String8 str(config.toString());
    str.append(extension);
    return str;
}

ssize_t parseAbi(const Vector<String8>& parts, const ssize_t index,
        SplitDescription* outSplit) {
    const ssize_t N = parts.size();
    abi::Variant abi = abi::Variant_none;
    ssize_t endIndex = index;
    if (parts[endIndex] == "arm64") {
        endIndex++;
        if (endIndex < N) {
            if (parts[endIndex] == "v8a") {
                endIndex++;
                abi = abi::Variant_arm64_v8a;
            }
        }
    } else if (parts[endIndex] == "armeabi") {
        endIndex++;
        abi = abi::Variant_armeabi;
        if (endIndex < N) {
            if (parts[endIndex] == "v7a") {
                endIndex++;
                abi = abi::Variant_armeabi_v7a;
            }
        }
    } else if (parts[endIndex] == "x86") {
        endIndex++;
        abi = abi::Variant_x86;
    } else if (parts[endIndex] == "x86_64") {
        endIndex++;
        abi = abi::Variant_x86_64;
    } else if (parts[endIndex] == "mips") {
        endIndex++;
        abi = abi::Variant_mips;
    } else if (parts[endIndex] == "mips64") {
        endIndex++;
        abi = abi::Variant_mips64;
    }

    if (abi == abi::Variant_none && endIndex != index) {
        return -1;
    }

    if (outSplit != NULL) {
        outSplit->abi = abi;
    }
    return endIndex;
}

bool SplitDescription::parse(const String8& str, SplitDescription* outSplit) {
    ssize_t index = str.find(":");

    String8 configStr;
    String8 extensionStr;
    if (index >= 0) {
        configStr.setTo(str.string(), index);
        extensionStr.setTo(str.string() + index + 1);
    } else {
        configStr.setTo(str);
    }

    SplitDescription split;
    if (!AaptConfig::parse(configStr, &split.config)) {
        return false;
    }

    Vector<String8> parts = AaptUtil::splitAndLowerCase(extensionStr, '-');
    const ssize_t N = parts.size();
    index = 0;

    if (extensionStr.length() == 0) {
        goto success;
    }

    index = parseAbi(parts, index, &split);
    if (index < 0) {
        return false;
    } else {
        if (index == N) {
            goto success;
        }
    }

    // Unrecognized
    return false;

success:
    if (outSplit != NULL) {
        *outSplit = split;
    }
    return true;
}

} // namespace split
