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

#include <cutils/compiler.h>

#include <utils/Singleton.h>
#include <utils/SortedVector.h>
#include <utils/String8.h>

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Classes
///////////////////////////////////////////////////////////////////////////////

class ANDROID_API Extensions: public Singleton<Extensions> {
public:
    inline bool hasNPot() const { return mHasNPot; }
    inline bool hasFramebufferFetch() const { return mHasFramebufferFetch; }
    inline bool hasDiscardFramebuffer() const { return mHasDiscardFramebuffer; }
    inline bool hasDebugMarker() const { return mHasDebugMarker; }
    inline bool hasDebugLabel() const { return mHasDebugLabel; }
    inline bool hasTiledRendering() const { return mHasTiledRendering; }
    inline bool has1BitStencil() const { return mHas1BitStencil; }
    inline bool has4BitStencil() const { return mHas4BitStencil; }
    inline bool hasNvSystemTime() const { return mHasNvSystemTime; }
    inline bool hasUnpackRowLength() const { return mVersionMajor >= 3; }
    inline bool hasPixelBufferObjects() const { return mVersionMajor >= 3; }
    inline bool hasOcclusionQueries() const { return mVersionMajor >= 3; }
    inline bool hasFloatTextures() const { return mVersionMajor >= 3; }

    inline int getMajorGlVersion() const { return mVersionMajor; }
    inline int getMinorGlVersion() const { return mVersionMinor; }

    bool hasGlExtension(const char* extension) const;
    bool hasEglExtension(const char* extension) const;

    void dump() const;

private:
    Extensions();
    ~Extensions();

    void findExtensions(const char* extensions, SortedVector<String8>& list) const;

    friend class Singleton<Extensions>;

    SortedVector<String8> mGlExtensionList;
    SortedVector<String8> mEglExtensionList;

    bool mHasNPot;
    bool mHasFramebufferFetch;
    bool mHasDiscardFramebuffer;
    bool mHasDebugMarker;
    bool mHasDebugLabel;
    bool mHasTiledRendering;
    bool mHas1BitStencil;
    bool mHas4BitStencil;
    bool mHasNvSystemTime;

    int mVersionMajor;
    int mVersionMinor;
}; // class Extensions

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_EXTENSIONS_H
