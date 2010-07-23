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

#define LOG_TAG "OpenGLRenderer"

#ifndef ANDROID_UI_EXTENSIONS_H
#define ANDROID_UI_EXTENSIONS_H

#include <utils/SortedVector.h>
#include <utils/String8.h>

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

namespace android {
namespace uirenderer {

class Extensions {
public:
    Extensions() {
        const char* buffer = (const char*) glGetString(GL_EXTENSIONS);
        const char* current = buffer;
        const char* head = current;
        do {
            head = strchr(current, ' ');
            String8 s(current, head ? head - current : strlen(current));
            if (s.length()) {
                mExtensionList.add(s);
            }
            current = head + 1;
        } while (head);

        mHasNPot = hasExtension("GL_OES_texture_npot");
    }

    inline bool hasNPot() const { return mHasNPot; }

    bool hasExtension(const char* extension) const {
        const String8 s(extension);
        return mExtensionList.indexOf(s) >= 0;
    }

private:
    SortedVector<String8> mExtensionList;

    bool mHasNPot;
}; // class Extensions

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_UI_EXTENSIONS_H
