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

using namespace android;

namespace split {
namespace abi {

static Vector<Variant> buildVariants(Variant v1, Variant v2) {
    Vector<Variant> v;
    v.add(v1);
    v.add(v2);
    return v;
}

static Vector<Variant> buildVariants(Variant v1, Variant v2, Variant v3) {
    Vector<Variant> v;
    v.add(v1);
    v.add(v2);
    v.add(v3);
    return v;
}

static const Vector<Variant> sNoneVariants;
static const Vector<Variant> sArmVariants = buildVariants(Variant_armeabi, Variant_armeabi_v7a, Variant_arm64_v8a);
static const Vector<Variant> sIntelVariants = buildVariants(Variant_x86, Variant_x86_64);
static const Vector<Variant> sMipsVariants = buildVariants(Variant_mips, Variant_mips64);

Family getFamily(Variant variant) {
    switch (variant) {
        case Variant_none:
            return Family_none;
        case Variant_armeabi:
        case Variant_armeabi_v7a:
        case Variant_arm64_v8a:
            return Family_arm;
        case Variant_x86:
        case Variant_x86_64:
            return Family_intel;
        case Variant_mips:
        case Variant_mips64:
            return Family_mips;
    }
    return Family_none;
}

const Vector<Variant>& getVariants(Family family) {
    switch (family) {
        case Family_none:
            return sNoneVariants;
        case Family_arm:
            return sArmVariants;
        case Family_intel:
            return sIntelVariants;
        case Family_mips:
            return sMipsVariants;
    }
    return sNoneVariants;
}

const char* toString(Variant variant) {
    switch (variant) {
        case Variant_none:
            return "";
        case Variant_armeabi:
            return "armeabi";
        case Variant_armeabi_v7a:
            return "armeabi-v7a";
        case Variant_arm64_v8a:
            return "arm64-v8a";
        case Variant_x86:
            return "x86";
        case Variant_x86_64:
            return "x86_64";
        case Variant_mips:
            return "mips";
        case Variant_mips64:
            return "mips64";
    }
    return "";
}

} // namespace abi
} // namespace split
