/*
** Copyright 2010, The Android Open Source Project
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

#include <stdlib.h>
#include <stdio.h>

#include <EGL/egl.h>

#define ATTRIBUTE(_attr) { _attr, #_attr }

struct Attribute {
    EGLint attribute;
    char const* name;
};

Attribute attributes[] = {
        ATTRIBUTE( EGL_BUFFER_SIZE ),
        ATTRIBUTE( EGL_ALPHA_SIZE ),
        ATTRIBUTE( EGL_BLUE_SIZE ),
        ATTRIBUTE( EGL_GREEN_SIZE ),
        ATTRIBUTE( EGL_RED_SIZE ),
        ATTRIBUTE( EGL_DEPTH_SIZE ),
        ATTRIBUTE( EGL_STENCIL_SIZE ),
        ATTRIBUTE( EGL_CONFIG_CAVEAT ),
        ATTRIBUTE( EGL_CONFIG_ID ),
        ATTRIBUTE( EGL_LEVEL ),
        ATTRIBUTE( EGL_MAX_PBUFFER_HEIGHT ),
        ATTRIBUTE( EGL_MAX_PBUFFER_WIDTH ),
        ATTRIBUTE( EGL_MAX_PBUFFER_PIXELS ),
        ATTRIBUTE( EGL_NATIVE_RENDERABLE ),
        ATTRIBUTE( EGL_NATIVE_VISUAL_ID ),
        ATTRIBUTE( EGL_NATIVE_VISUAL_TYPE ),
        ATTRIBUTE( EGL_SAMPLES ),
        ATTRIBUTE( EGL_SAMPLE_BUFFERS ),
        ATTRIBUTE( EGL_SURFACE_TYPE ),
        ATTRIBUTE( EGL_TRANSPARENT_TYPE ),
        ATTRIBUTE( EGL_TRANSPARENT_BLUE_VALUE ),
        ATTRIBUTE( EGL_TRANSPARENT_GREEN_VALUE ),
        ATTRIBUTE( EGL_TRANSPARENT_RED_VALUE ),
        ATTRIBUTE( EGL_BIND_TO_TEXTURE_RGB ),
        ATTRIBUTE( EGL_BIND_TO_TEXTURE_RGBA ),
        ATTRIBUTE( EGL_MIN_SWAP_INTERVAL ),
        ATTRIBUTE( EGL_MAX_SWAP_INTERVAL ),
        ATTRIBUTE( EGL_LUMINANCE_SIZE ),
        ATTRIBUTE( EGL_ALPHA_MASK_SIZE ),
        ATTRIBUTE( EGL_COLOR_BUFFER_TYPE ),
        ATTRIBUTE( EGL_RENDERABLE_TYPE ),
        ATTRIBUTE( EGL_MATCH_NATIVE_PIXMAP ),
        ATTRIBUTE( EGL_CONFORMANT ),
};


int main(int argc, char** argv)
{
    EGLConfig* configs;
    EGLint n;

    EGLDisplay dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    eglInitialize(dpy, 0, 0);
    eglGetConfigs(dpy, NULL, 0, &n);
    configs = new EGLConfig[n];
    eglGetConfigs(dpy, configs, n, &n);

    for (EGLint i=0 ; i<n ; i++) {
        printf("EGLConfig[%d]\n", i);
        for (int attr = 0 ; attr<sizeof(attributes)/sizeof(Attribute) ; attr++) {
            EGLint value;
            eglGetConfigAttrib(dpy, configs[i], attributes[attr].attribute, &value);
            printf("\t%-32s: %10d (0x%08x)\n", attributes[attr].name, value, value);
        }
    }

    delete [] configs;
    eglTerminate(dpy);
    return 0;
}
