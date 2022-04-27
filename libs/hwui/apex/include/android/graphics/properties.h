/*
 * Copyright 2022 The Android Open Source Project
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

#ifndef ANDROID_GRAPHICS_PROPERTIES_H
#define ANDROID_GRAPHICS_PROPERTIES_H

#include <cutils/compiler.h>
#include <sys/cdefs.h>

__BEGIN_DECLS

/**
 * Returns true if libhwui is using the vulkan backend.
 */
ANDROID_API bool hwui_uses_vulkan();

__END_DECLS

#endif  // ANDROID_GRAPHICS_PROPERTIES_H
