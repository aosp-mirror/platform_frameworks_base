/*
 * Copyright (C) 2011 The Android Open Source Project
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

#ifndef _UI_SPOT_CONTROLLER_H
#define _UI_SPOT_CONTROLLER_H

#include "SpriteController.h"

#include <utils/RefBase.h>
#include <utils/Looper.h>

#include <SkBitmap.h>

namespace android {

/*
 * Interface for displaying spots on screen that visually represent the positions
 * of fingers on a touch pad.
 *
 * The spot controller is responsible for providing synchronization and for tracking
 * display orientation changes if needed.
 */
class SpotControllerInterface : public virtual RefBase {
protected:
    SpotControllerInterface() { }
    virtual ~SpotControllerInterface() { }

public:

};


/*
 * Sprite-based spot controller implementation.
 */
class SpotController : public SpotControllerInterface, public MessageHandler {
protected:
    virtual ~SpotController();

public:
    SpotController(const sp<Looper>& looper, const sp<SpriteController>& spriteController);

private:
    mutable Mutex mLock;

    sp<Looper> mLooper;
    sp<SpriteController> mSpriteController;
    sp<WeakMessageHandler> mHandler;

    struct Locked {
    } mLocked;

    void handleMessage(const Message& message);
};

} // namespace android

#endif // _UI_SPOT_CONTROLLER_H
