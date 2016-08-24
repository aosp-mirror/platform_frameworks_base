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

#define DEFAULT_TEXT_SMALL_CACHE_WIDTH 1024
#define DEFAULT_TEXT_SMALL_CACHE_HEIGHT 512
#define DEFAULT_TEXT_LARGE_CACHE_WIDTH 2048
#define DEFAULT_TEXT_LARGE_CACHE_HEIGHT 512

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

#define AUTO_KERN(prev, next) (((next) - (prev) + 32) >> 6 << 16)

#endif // ANDROID_HWUI_FONT_UTIL_H
