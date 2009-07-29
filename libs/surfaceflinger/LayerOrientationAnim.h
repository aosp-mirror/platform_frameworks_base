/*
 * Copyright (C) 2007 The Android Open Source Project
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

#ifndef ANDROID_LAYER_ORIENTATION_ANIM_H
#define ANDROID_LAYER_ORIENTATION_ANIM_H

#include <stdint.h>
#include <sys/types.h>
#include <utils/threads.h>
#include <utils/Parcel.h>

#include "LayerBase.h"
#include "LayerBitmap.h"

namespace android {

// ---------------------------------------------------------------------------
class OrientationAnimation;


class LayerOrientationAnimBase : public LayerBase
{
public:
    LayerOrientationAnimBase(SurfaceFlinger* flinger, DisplayID display)
        : LayerBase(flinger, display) {
    }
    virtual void onOrientationCompleted() = 0;
};

// ---------------------------------------------------------------------------

class LayerOrientationAnim : public LayerOrientationAnimBase
{
public:    
    static const uint32_t typeInfo;
    static const char* const typeID;
    virtual char const* getTypeID() const { return typeID; }
    virtual uint32_t getTypeInfo() const { return typeInfo; }
    
                LayerOrientationAnim(SurfaceFlinger* flinger, DisplayID display,
                        OrientationAnimation* anim, 
                        const LayerBitmap& bitmapIn,
                        const LayerBitmap& bitmapOut);
        virtual ~LayerOrientationAnim();

            void onOrientationCompleted();

    virtual void onDraw(const Region& clip) const;
    virtual Point getPhysicalSize() const;
    virtual void validateVisibility(const Transform& globalTransform);
    virtual bool needsBlending() const;
    virtual bool isSecure() const       { return false; }
private:
    OrientationAnimation* mAnim;
    LayerBitmap mBitmapIn;
    LayerBitmap mBitmapOut;
    bool mOrientationCompleted;
    mutable GLuint  mTextureName;
    mutable GLuint  mTextureNameIn;
    mutable bool mNeedsBlending;
};

// ---------------------------------------------------------------------------

}; // namespace android

#endif // ANDROID_LAYER_ORIENTATION_ANIM_H
