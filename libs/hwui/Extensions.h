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

#include <utils/Singleton.h>
#include <utils/SortedVector.h>
#include <utils/String8.h>

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Classes
///////////////////////////////////////////////////////////////////////////////

class Extensions: public Singleton<Extensions> {
public:
    Extensions();
    ~Extensions();

    inline bool hasNPot() const { return mHasNPot; }
    inline bool hasFramebufferFetch() const { return mHasFramebufferFetch; }
    inline bool hasDiscardFramebuffer() const { return mHasDiscardFramebuffer; }
    inline bool hasDebugMarker() const { return mHasDebugMarker; }
    inline bool hasDebugLabel() const { return mHasDebugLabel; }
    inline bool hasTiledRendering() const { return mHasTiledRendering; }
    inline bool has1BitStencil() const { return mHas1BitStencil; }
    inline bool has4BitStencil() const { return mHas4BitStencil; }

    inline int getMajorGlVersion() const { return mVersionMajor; }
    inline int getMinorGlVersion() const { return mVersionMinor; }

    bool hasExtension(const char* extension) const;

    void dump() const;

private:
    friend class Singleton<Extensions>;

    SortedVector<String8> mExtensionList;

    char* mExtensions;
    char* mVersion;

    bool mHasNPot;
    bool mHasFramebufferFetch;
    bool mHasDiscardFramebuffer;
    bool mHasDebugMarker;
    bool mHasDebugLabel;
    bool mHasTiledRendering;
    bool mHas1BitStencil;
    bool mHas4BitStencil;

    int mVersionMajor;
    int mVersionMinor;
}; // class Extensions

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_EXTENSIONS_H
