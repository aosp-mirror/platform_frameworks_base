/*
 * Copyright (C) 2012 The Android Open Source Project
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

#ifndef ANDROID_HWUI_FONT_UTIL_H
#define ANDROID_HWUI_FONT_UTIL_H

#include <SkUtils.h>

#include "Properties.h"

///////////////////////////////////////////////////////////////////////////////
// Defines
///////////////////////////////////////////////////////////////////////////////

#ifdef TEXTURE_BORDER_SIZE
#if TEXTURE_BORDER_SIZE != 1
#error TEXTURE_BORDER_SIZE other than 1 is not currently supported
#endif
#else
#define TEXTURE_BORDER_SIZE 1
#endif

#define CACHE_BLOCK_ROUNDING_SIZE 4

typedef uint16_t glyph_t;
#define GET_METRICS(cache, glyph) cache->getGlyphIDMetrics(glyph)
#define IS_END_OF_STRING(glyph) false

// prev, next are assumed to be signed x.6 fixed-point numbers with range
// [-1, 1]. Result is an integral float.
#define AUTO_KERN(prev, next) static_cast<float>(((next) - (prev) + 32) >> 6)

#endif  // ANDROID_HWUI_FONT_UTIL_H
