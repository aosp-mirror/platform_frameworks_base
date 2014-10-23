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

#include <vector>

namespace split {
namespace abi {

enum class Variant {
    none = 0,
    armeabi,
    armeabi_v7a,
    arm64_v8a,
    x86,
    x86_64,
    mips,
    mips64,
};

enum class Family {
    none,
    arm,
    intel,
    mips,
};

Family getFamily(Variant variant);
const std::vector<Variant>& getVariants(Family family);
const char* toString(Variant variant);

} // namespace abi
} // namespace split

#endif // H_ANDROID_SPLIT_ABI
