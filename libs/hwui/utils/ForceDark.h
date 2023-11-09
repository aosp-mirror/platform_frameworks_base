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

#ifndef FORCEDARKUTILS_H
#define FORCEDARKUTILS_H

namespace android {
namespace uirenderer {

/**
 * The type of force dark set on the renderer, if any.
 *
 * This should stay in sync with the java @IntDef in
 * frameworks/base/graphics/java/android/graphics/ForceDarkType.java
 */
enum class ForceDarkType : __uint8_t { NONE = 0, FORCE_DARK = 1, FORCE_INVERT_COLOR_DARK = 2 };

} /* namespace uirenderer */
} /* namespace android */

#endif  // FORCEDARKUTILS_H