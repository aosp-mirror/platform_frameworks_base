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

#ifndef ANDROID_ORIENTATION_ANIMATION_H
#define ANDROID_ORIENTATION_ANIMATION_H

#include <stdint.h>
#include <sys/types.h>

#include "SurfaceFlinger.h"

namespace android {

// ---------------------------------------------------------------------------

class SurfaceFlinger;
class MemoryDealer;
class LayerOrientationAnim;

class OrientationAnimation
{
public:    
                 OrientationAnimation(const sp<SurfaceFlinger>& flinger);
        virtual ~OrientationAnimation();

   void onOrientationChanged();
   void onAnimationFinished();
   inline bool run() {
       if (LIKELY(mState == DONE))
           return false;
       return run_impl();
   }

private:
    enum {
        DONE = 0,
        PREPARE,
        PHASE1,
        PHASE2,
        FINISH
    };

    bool run_impl();
    bool done();
    bool prepare();
    bool phase1();
    bool phase2();
    bool finished();

    sp<SurfaceFlinger> mFlinger;
    sp<MemoryDealer> mTemporaryDealer;
    LayerOrientationAnim* mLayerOrientationAnim;
    int mState;
};

// ---------------------------------------------------------------------------

}; // namespace android

#endif // ANDROID_ORIENTATION_ANIMATION_H
