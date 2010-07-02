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

/*
 * Pixel formats that a window can use.
 */
enum {
    WINDOW_FORMAT_RGBA_8888          = 1,
    WINDOW_FORMAT_RGBX_8888          = 2,
    WINDOW_FORMAT_RGB_565            = 4,
};

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

/*
 * Change the format and size of the window buffers.
 *
 * The width and height control the number of pixels in the buffers, not the
 * dimensions of the window on screen.  If these are different than the
 * window's physical size, then it buffer will be scaled to match that size
 * when compositing it to the screen.
 *
 * The format may be one of the window format constants above.
 *
 * For all of these parameters, if 0 is supplied than the window's base
 * value will come back in force.
 */
int32_t ANativeWindow_setBuffersGeometry(ANativeWindow* window, int32_t width,
        int32_t height, int32_t format);

#ifdef __cplusplus
};
#endif

#endif // ANDROID_NATIVE_WINDOW_H
