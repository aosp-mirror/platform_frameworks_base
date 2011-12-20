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

#ifndef ANDROID_HWUI_EXTENSIONS_H
#define ANDROID_HWUI_EXTENSIONS_H

#include <utils/SortedVector.h>
#include <utils/String8.h>

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include "Debug.h"

namespace android {
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

// Vendor strings

#define VENDOR_IMG "Imagination Technologies"

///////////////////////////////////////////////////////////////////////////////
// Classes
///////////////////////////////////////////////////////////////////////////////

class Extensions {
public:
    Extensions() {
        const char* buffer = (const char*) glGetString(GL_EXTENSIONS);
        const char* current = buffer;
        const char* head = current;
        EXT_LOGD("Available GL extensions:");
        do {
            head = strchr(current, ' ');
            String8 s(current, head ? head - current : strlen(current));
            if (s.length()) {
                mExtensionList.add(s);
                EXT_LOGD("  %s", s.string());
            }
            current = head + 1;
        } while (head);

        mHasNPot = hasExtension("GL_OES_texture_npot");
        mHasFramebufferFetch = hasExtension("GL_NV_shader_framebuffer_fetch");
        mHasDiscardFramebuffer = hasExtension("GL_EXT_discard_framebuffer");

        const char* vendor = (const char*) glGetString(GL_VENDOR);
        EXT_LOGD("Vendor: %s", vendor);
        mNeedsHighpTexCoords = strcmp(vendor, VENDOR_IMG) == 0;

        // We don't need to copy the string, the OpenGL ES spec
        // guarantees the result of glGetString to point to a
        // static string as long as our OpenGL context is valid
        mExtensions = buffer;
    }

    inline bool hasNPot() const { return mHasNPot; }
    inline bool hasFramebufferFetch() const { return mHasFramebufferFetch; }
    inline bool needsHighpTexCoords() const { return mNeedsHighpTexCoords; }
    inline bool hasDiscardFramebuffer() const { return mHasDiscardFramebuffer; }

    bool hasExtension(const char* extension) const {
        const String8 s(extension);
        return mExtensionList.indexOf(s) >= 0;
    }

    void dump() {
        ALOGD("Supported extensions:\n%s", mExtensions);
    }

private:
    SortedVector<String8> mExtensionList;

    const char* mExtensions;

    bool mHasNPot;
    bool mNeedsHighpTexCoords;
    bool mHasFramebufferFetch;
    bool mHasDiscardFramebuffer;
}; // class Extensions

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_EXTENSIONS_H
