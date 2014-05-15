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

/**
 * Utilities for making Minikin work, especially from existing objects like
 * SkPaint and so on.
 **/

 // TODO: does this really need to be separate from MinikinSkia?

#ifndef ANDROID_MINIKIN_UTILS_H
#define ANDROID_MINIKIN_UTILS_H

namespace android {

class MinikinUtils {
public:
    static void SetLayoutProperties(Layout* layout, SkPaint* paint, int flags,
        TypefaceImpl* face);
};

}  // namespace android

#endif  // ANDROID_MINIKIN_UTILS_H
