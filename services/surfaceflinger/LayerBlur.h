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

#ifndef ANDROID_LAYER_BLUR_H
#define ANDROID_LAYER_BLUR_H

#include <stdint.h>
#include <sys/types.h>

#include <ui/Region.h>

#include "LayerBase.h"

namespace android {

// ---------------------------------------------------------------------------

class LayerBlur : public LayerBaseClient
{
public:    
                LayerBlur(SurfaceFlinger* flinger, DisplayID display,
                        const sp<Client>& client);
        virtual ~LayerBlur();

    virtual void onDraw(const Region& clip) const;
    virtual bool needsBlending() const  { return true; }
    virtual bool isSecure() const       { return false; }
    virtual const char* getTypeId() const { return "LayerBlur"; }

    virtual uint32_t doTransaction(uint32_t flags);
    virtual void setVisibleRegion(const Region& visibleRegion);
    virtual void unlockPageFlip(const Transform& planeTransform, Region& outDirtyRegion);

private:
            bool    mCacheDirty;
    mutable bool    mRefreshCache;
    mutable bool    mAutoRefreshPending;
            nsecs_t mCacheAge;
    mutable GLuint  mTextureName;
    mutable GLfloat mWidthScale;
    mutable GLfloat mHeightScale;
    mutable GLfloat mYOffset;
    mutable GLint   mReadFormat;
    mutable GLint   mReadType;
    mutable uint32_t mBlurFormat;
};

// ---------------------------------------------------------------------------

}; // namespace android

#endif // ANDROID_LAYER_BLUR_H
