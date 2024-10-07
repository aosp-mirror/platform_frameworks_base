/*
 * Copyright (C) 2023 The Android Open Source Project
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

#ifndef ANDROID_HWUI_FEATURE_FLAGS_H
#define ANDROID_HWUI_FEATURE_FLAGS_H

#ifdef __ANDROID__
#include <com_android_text_flags.h>
#endif  // __ANDROID__

namespace android {

namespace text_feature {

inline bool letter_spacing_justification() {
#ifdef __ANDROID__
    return com_android_text_flags_letter_spacing_justification();
#else
    return true;
#endif  // __ANDROID__
}

inline bool typeface_redesign() {
#ifdef __ANDROID__
    static bool flag = com_android_text_flags_typeface_redesign();
    return flag;
#else
    return true;
#endif  // __ANDROID__
}

}  // namespace text_feature

}  // namespace android

#endif  // ANDROID_HWUI_FEATURE_FLAGS_H
