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


#ifndef ANDROID_NATIVE_WINDOW_H
#define ANDROID_NATIVE_WINDOW_H

#ifdef __cplusplus
extern "C" {
#endif

struct ANativeWindow;
typedef struct ANativeWindow ANativeWindow;

/*
 * Return the current width in pixels of the window surface.  Returns a
 * negative value on error.
 */
int32_t ANativeWindow_getWidth(ANativeWindow* window);

/*
 * Return the current height in pixels of the window surface.  Returns a
 * negative value on error.
 */
int32_t ANativeWindow_getHeight(ANativeWindow* window);

/*
 * Return the current pixel format of the window surface.  Returns a
 * negative value on error.
 */
int32_t ANativeWindow_getFormat(ANativeWindow* window);

#ifdef __cplusplus
};
#endif

#endif // ANDROID_NATIVE_WINDOW_H
