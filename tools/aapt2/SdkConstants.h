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

#ifndef AAPT_SDK_CONSTANTS_H
#define AAPT_SDK_CONSTANTS_H

#include "Resource.h"

namespace aapt {

enum {
    SDK_CUPCAKE = 3,
    SDK_DONUT = 4,
    SDK_ECLAIR = 5,
    SDK_ECLAIR_0_1 = 6,
    SDK_ECLAIR_MR1 = 7,
    SDK_FROYO = 8,
    SDK_GINGERBREAD = 9,
    SDK_GINGERBREAD_MR1 = 10,
    SDK_HONEYCOMB = 11,
    SDK_HONEYCOMB_MR1 = 12,
    SDK_HONEYCOMB_MR2 = 13,
    SDK_ICE_CREAM_SANDWICH = 14,
    SDK_ICE_CREAM_SANDWICH_MR1 = 15,
    SDK_JELLY_BEAN = 16,
    SDK_JELLY_BEAN_MR1 = 17,
    SDK_JELLY_BEAN_MR2 = 18,
    SDK_KITKAT = 19,
    SDK_KITKAT_WATCH = 20,
    SDK_LOLLIPOP = 21,
    SDK_LOLLIPOP_MR1 = 22,
    SDK_MARSHMALLOW = 23,
};

size_t findAttributeSdkLevel(ResourceId id);
size_t findAttributeSdkLevel(const ResourceName& name);

} // namespace aapt

#endif // AAPT_SDK_CONSTANTS_H
