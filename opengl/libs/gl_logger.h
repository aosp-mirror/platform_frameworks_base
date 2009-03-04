/* 
 ** Copyright 2007, The Android Open Source Project
 **
 ** Licensed under the Apache License, Version 2.0 (the "License"); 
 ** you may not use this file except in compliance with the License. 
 ** You may obtain a copy of the License at 
 **
 **     http://www.apache.org/licenses/LICENSE-2.0 
 **
 ** Unless required by applicable law or agreed to in writing, software 
 ** distributed under the License is distributed on an "AS IS" BASIS, 
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 ** See the License for the specific language governing permissions and 
 ** limitations under the License.
 */

#ifndef ANDROID_GL_LOGGER_H
#define ANDROID_GL_LOGGER_H

namespace android {
#define GL_ENTRY(r, api, ...) r log_##api(__VA_ARGS__);
#include "gl_entries.in"
#undef GL_ENTRY
}; // namespace android

#endif /* ANDROID_GL_LOGGER_H */
