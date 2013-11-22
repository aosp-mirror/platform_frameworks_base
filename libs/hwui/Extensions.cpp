/*
 * Copyright (C) 2013 The Android Open Source Project
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

#define LOG_TAG "OpenGLRenderer"

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>

#include <utils/Log.h>

#include "Debug.h"
#include "Extensions.h"
#include "Properties.h"

namespace android {

using namespace uirenderer;
ANDROID_SINGLETON_STATIC_INSTANCE(Extensions);

namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Defines
///////////////////////////////////////////////////////////////////////////////

// Debug
#if DEBUG_EXTENSIONS
    #define EXT_LOGD(...) ALOGD(__VA_ARGS__)
#else
    #define EXT_LOGD(...)
#endif

///////////////////////////////////////////////////////////////////////////////
// Constructors
///////////////////////////////////////////////////////////////////////////////

Extensions::Extensions(): Singleton<Extensions>() {
    // Query GL extensions
    findExtensions((const char*) glGetString(GL_EXTENSIONS), mGlExtensionList);
    mHasNPot = hasGlExtension("GL_OES_texture_npot");
    mHasFramebufferFetch = hasGlExtension("GL_NV_shader_framebuffer_fetch");
    mHasDiscardFramebuffer = hasGlExtension("GL_EXT_discard_framebuffer");
    mHasDebugMarker = hasGlExtension("GL_EXT_debug_marker");
    mHasDebugLabel = hasGlExtension("GL_EXT_debug_label");
    mHasTiledRendering = hasGlExtension("GL_QCOM_tiled_rendering");
    mHas1BitStencil = hasGlExtension("GL_OES_stencil1");
    mHas4BitStencil = hasGlExtension("GL_OES_stencil4");

    // Query EGL extensions
    findExtensions(eglQueryString(eglGetCurrentDisplay(), EGL_EXTENSIONS), mEglExtensionList);

    char property[PROPERTY_VALUE_MAX];
    if (property_get(PROPERTY_DEBUG_NV_PROFILING, property, NULL) > 0) {
        mHasNvSystemTime = !strcmp(property, "true") && hasEglExtension("EGL_NV_system_time");
    } else {
        mHasNvSystemTime = false;
    }

    const char* version = (const char*) glGetString(GL_VERSION);

    // Section 6.1.5 of the OpenGL ES specification indicates the GL version
    // string strictly follows this format:
    //
    // OpenGL<space>ES<space><version number><space><vendor-specific information>
    //
    // In addition section 6.1.5 describes the version number thusly:
    //
    // "The version number is either of the form major number.minor number or
    // major number.minor number.release number, where the numbers all have one
    // or more digits. The release number and vendor specific information are
    // optional."

    if (sscanf(version, "OpenGL ES %d.%d", &mVersionMajor, &mVersionMinor) != 2) {
        // If we cannot parse the version number, assume OpenGL ES 2.0
        mVersionMajor = 2;
        mVersionMinor = 0;
    }
}

Extensions::~Extensions() {
}

///////////////////////////////////////////////////////////////////////////////
// Methods
///////////////////////////////////////////////////////////////////////////////

bool Extensions::hasGlExtension(const char* extension) const {
   const String8 s(extension);
   return mGlExtensionList.indexOf(s) >= 0;
}

bool Extensions::hasEglExtension(const char* extension) const {
   const String8 s(extension);
   return mEglExtensionList.indexOf(s) >= 0;
}

void Extensions::findExtensions(const char* extensions, SortedVector<String8>& list) const {
    const char* current = extensions;
    const char* head = current;
    EXT_LOGD("Available extensions:");
    do {
        head = strchr(current, ' ');
        String8 s(current, head ? head - current : strlen(current));
        if (s.length()) {
            list.add(s);
            EXT_LOGD("  %s", s.string());
        }
        current = head + 1;
    } while (head);
}

void Extensions::dump() const {
   ALOGD("%s", (const char*) glGetString(GL_VERSION));
   ALOGD("Supported GL extensions:\n%s", (const char*) glGetString(GL_EXTENSIONS));
   ALOGD("Supported EGL extensions:\n%s", eglQueryString(eglGetCurrentDisplay(), EGL_EXTENSIONS));
}

}; // namespace uirenderer
}; // namespace android
