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

#include "Abi.h"

namespace split {
namespace abi {

static const std::vector<Variant> sNoneVariants = {};
static const std::vector<Variant> sArmVariants =
        {Variant::armeabi, Variant::armeabi_v7a, Variant::arm64_v8a};
static const std::vector<Variant> sIntelVariants = {Variant::x86, Variant::x86_64};
static const std::vector<Variant> sMipsVariants = {Variant::mips, Variant::mips64};

Family getFamily(Variant variant) {
    switch (variant) {
        case Variant::none:
            return Family::none;
        case Variant::armeabi:
        case Variant::armeabi_v7a:
        case Variant::arm64_v8a:
            return Family::arm;
        case Variant::x86:
        case Variant::x86_64:
            return Family::intel;
        case Variant::mips:
        case Variant::mips64:
            return Family::mips;
    }
    return Family::none;
}

const std::vector<Variant>& getVariants(Family family) {
    switch (family) {
        case Family::none:
            return sNoneVariants;
        case Family::arm:
            return sArmVariants;
        case Family::intel:
            return sIntelVariants;
        case Family::mips:
            return sMipsVariants;
    }
    return sNoneVariants;
}

const char* toString(Variant variant) {
    switch (variant) {
        case Variant::none:
            return "";
        case Variant::armeabi:
            return "armeabi";
        case Variant::armeabi_v7a:
            return "armeabi-v7a";
        case Variant::arm64_v8a:
            return "arm64-v8a";
        case Variant::x86:
            return "x86";
        case Variant::x86_64:
            return "x86_64";
        case Variant::mips:
            return "mips";
        case Variant::mips64:
            return "mips64";
    }
    return "";
}

} // namespace abi
} // namespace split
