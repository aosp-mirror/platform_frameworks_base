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

#ifndef ANDROID_HWUI_DEBUG_H
#define ANDROID_HWUI_DEBUG_H

// Turn on to check for OpenGL errors on each frame
#define DEBUG_OPENGL 1

// Turn on to enable memory usage summary on each frame
#define DEBUG_MEMORY_USAGE 0

// Turn on to enable layers debugging when renderered as regions
#define DEBUG_LAYERS_AS_REGIONS 0

// Turn on to display debug info about vertex/fragment shaders
#define DEBUG_PROGRAMS 0

// Turn on to display info about layers
#define DEBUG_LAYERS 0

// Turn on to display debug infor about 9patch objects
#define DEBUG_PATCHES 0
// Turn on to display vertex and tex coords data about 9patch objects
// This flag requires DEBUG_PATCHES to be turned on
#define DEBUG_PATCHES_VERTICES 0

// Turn on to display debug info about paths
#define DEBUG_PATHS 0

// Turn on to display debug info about textures
#define DEBUG_TEXTURES 0

#endif // ANDROID_HWUI_DEBUG_H
