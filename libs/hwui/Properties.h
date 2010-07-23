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

/**
 * This file contains the list of system properties used to configure
 * the OpenGLRenderer.
 */

// These properties are defined in mega-bytes
#define PROPERTY_TEXTURE_CACHE_SIZE "ro.hwui.texture_cache_size"
#define PROPERTY_LAYER_CACHE_SIZE "ro.hwui.layer_cache_size"
#define PROPERTY_GRADIENT_CACHE_SIZE "ro.hwui.gradient_cache_size"

// These properties are defined in pixels
#define PROPERTY_TEXT_CACHE_WIDTH "ro.hwui.text_cache_width"
#define PROPERTY_TEXT_CACHE_HEIGHT "ro.hwui.text_cache_height"

#endif // ANDROID_UI_PROPERTIES_H

