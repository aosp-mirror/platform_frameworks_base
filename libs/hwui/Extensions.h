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

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Classes
///////////////////////////////////////////////////////////////////////////////

class Extensions {
public:
    Extensions() {}

    inline bool hasNPot() const { return false; }
    inline bool hasFramebufferFetch() const { return false; }
    inline bool hasDiscardFramebuffer() const { return false; }
    inline bool hasDebugMarker() const { return false; }
    inline bool has1BitStencil() const { return false; }
    inline bool has4BitStencil() const { return false; }
    inline bool hasUnpackRowLength() const { return mVersionMajor >= 3; }
    inline bool hasPixelBufferObjects() const { return mVersionMajor >= 3; }
    inline bool hasOcclusionQueries() const { return mVersionMajor >= 3; }
    inline bool hasFloatTextures() const { return mVersionMajor >= 3; }
    inline bool hasRenderableFloatTextures() const {
        return (mVersionMajor >= 3 && mVersionMinor >= 2);
    }
    inline bool hasSRGB() const { return false; }
    inline bool hasSRGBWriteControl() const { return hasSRGB() && false; }
    inline bool hasLinearBlending() const { return hasSRGB() && false; }

    inline int getMajorGlVersion() const { return mVersionMajor; }
    inline int getMinorGlVersion() const { return mVersionMinor; }

private:
    int mVersionMajor = 2;
    int mVersionMinor = 0;
};  // class Extensions

};  // namespace uirenderer
};  // namespace android

#endif  // ANDROID_HWUI_EXTENSIONS_H
