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

#define DEBUG_LEVEL_HIGH 3
#define DEBUG_LEVEL_MODERATE 2
#define DEBUG_LEVEL_LOW 1
#define DEBUG_LEVEL_NONE 0

// Turn on to check for OpenGL errors on each frame
// Note DEBUG_LEVEL_HIGH for DEBUG_OPENGL is only setable by enabling
// HWUI_ENABLE_OPENGL_VALIDATION when building HWUI. Similarly if
// HWUI_ENABLE_OPENGL_VALIDATION is set then this is always DEBUG_LEVEL_HIGH
#ifndef DEBUG_OPENGL
#define DEBUG_OPENGL DEBUG_LEVEL_LOW
#endif

// Turn on to enable initialization information
#define DEBUG_INIT 0

// Turn on to enable memory usage summary on each frame
#define DEBUG_MEMORY_USAGE 0

// Turn on to enable debugging of cache flushes
#define DEBUG_CACHE_FLUSH 0

// Turn on to enable layers debugging when rendered as regions
#define DEBUG_LAYERS_AS_REGIONS 0

// Turn on to enable debugging when the clip is not a rect
#define DEBUG_CLIP_REGIONS 0

// Turn on to display debug info about vertex/fragment shaders
#define DEBUG_PROGRAMS 0

// Turn on to display info about layers
#define DEBUG_LAYERS 0

// Turn on to display info about render buffers
#define DEBUG_RENDER_BUFFERS 0

// Turn on to make stencil operations easier to debug
// (writes 255 instead of 1 in the buffer, forces 8 bit stencil)
#define DEBUG_STENCIL 0

// Turn on to display debug info about 9patch objects
#define DEBUG_PATCHES 0
// Turn on to display vertex and tex coords data about 9patch objects
// This flag requires DEBUG_PATCHES to be turned on
#define DEBUG_PATCHES_VERTICES 0
// Turn on to display vertex and tex coords data used by empty quads
// in 9patch objects
// This flag requires DEBUG_PATCHES to be turned on
#define DEBUG_PATCHES_EMPTY_VERTICES 0

// Turn on to display debug info about shapes
#define DEBUG_PATHS 0

// Turn on to display debug info about textures
#define DEBUG_TEXTURES 0

// Turn on to display debug info about the layer renderer
#define DEBUG_LAYER_RENDERER 0

// Turn on to enable additional debugging in the font renderers
#define DEBUG_FONT_RENDERER 0

// Turn on to log draw operation batching and deferral information
#define DEBUG_DEFER 0

// Turn on to dump display list state
#define DEBUG_DISPLAY_LIST 0

// Turn on to insert an event marker for each display list op
#define DEBUG_DISPLAY_LIST_OPS_AS_EVENTS 0

// Turn on to insert detailed event markers
#define DEBUG_DETAILED_EVENTS 0

// Turn on to highlight drawing batches and merged batches with different colors
#define DEBUG_MERGE_BEHAVIOR 0

// Turn on to enable debugging shadow
#define DEBUG_SHADOW 0

// Turn on to enable debugging vector drawable
#define DEBUG_VECTOR_DRAWABLE 0

#if DEBUG_INIT
#define INIT_LOGD(...) ALOGD(__VA_ARGS__)
#else
#define INIT_LOGD(...)
#endif

#endif  // ANDROID_HWUI_DEBUG_H
