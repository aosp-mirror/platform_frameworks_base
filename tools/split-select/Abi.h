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

#ifndef H_ANDROID_SPLIT_ABI
#define H_ANDROID_SPLIT_ABI

#include <utils/Vector.h>

namespace split {
namespace abi {

enum Variant {
    Variant_none = 0,
    Variant_armeabi,
    Variant_armeabi_v7a,
    Variant_arm64_v8a,
    Variant_x86,
    Variant_x86_64,
    Variant_mips,
    Variant_mips64,
};

enum Family {
    Family_none,
    Family_arm,
    Family_intel,
    Family_mips,
};

Family getFamily(Variant variant);
const android::Vector<Variant>& getVariants(Family family);
const char* toString(Variant variant);

} // namespace abi
} // namespace split

#endif // H_ANDROID_SPLIT_ABI
