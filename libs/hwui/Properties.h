/*
 * Copyright (C) 2010 The Android Open Source Project
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

#ifndef ANDROID_UI_PROPERTIES_H
#define ANDROID_UI_PROPERTIES_H

#include <cutils/properties.h>

/**
 * This file contains the list of system properties used to configure
 * the OpenGLRenderer.
 */

// These properties are defined in mega-bytes
#define PROPERTY_TEXTURE_CACHE_SIZE "ro.hwui.texture_cache_size"
#define PROPERTY_LAYER_CACHE_SIZE "ro.hwui.layer_cache_size"
#define PROPERTY_GRADIENT_CACHE_SIZE "ro.hwui.gradient_cache_size"
#define PROPERTY_PATH_CACHE_SIZE "ro.hwui.path_cache_size"
#define PROPERTY_DROP_SHADOW_CACHE_SIZE "ro.hwui.drop_shadow_cache_size"

// These properties are defined in pixels
#define PROPERTY_TEXT_CACHE_WIDTH "ro.hwui.text_cache_width"
#define PROPERTY_TEXT_CACHE_HEIGHT "ro.hwui.text_cache_height"

// Gamma (>= 1.0, <= 10.0)
#define PROPERTY_TEXT_GAMMA "ro.text_gamma"
#define PROPERTY_TEXT_BLACK_GAMMA_THRESHOLD "ro.text_gamma.black_threshold"
#define PROPERTY_TEXT_WHITE_GAMMA_THRESHOLD "ro.text_gamma.white_threshold"

// Converts a number of mega-bytes into bytes
#define MB(s) s * 1024 * 1024

#define DEFAULT_TEXTURE_CACHE_SIZE 20.0f
#define DEFAULT_LAYER_CACHE_SIZE 6.0f
#define DEFAULT_PATH_CACHE_SIZE 6.0f
#define DEFAULT_PATCH_CACHE_SIZE 100
#define DEFAULT_GRADIENT_CACHE_SIZE 0.5f
#define DEFAULT_DROP_SHADOW_CACHE_SIZE 2.0f

#define DEFAULT_TEXT_GAMMA 1.4f
#define DEFAULT_TEXT_BLACK_GAMMA_THRESHOLD 64
#define DEFAULT_TEXT_WHITE_GAMMA_THRESHOLD 192

#endif // ANDROID_UI_PROPERTIES_H
