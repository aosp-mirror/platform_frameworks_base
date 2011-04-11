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

#ifndef _UI_POINTER_CONTROLLER_H
#define _UI_POINTER_CONTROLLER_H

#include "SpriteController.h"

#include <ui/DisplayInfo.h>
#include <ui/Input.h>
#include <utils/RefBase.h>
#include <utils/Looper.h>
#include <utils/String8.h>

#include <SkBitmap.h>

namespace android {

/**
 * Interface for tracking a single (mouse) pointer.
 *
 * The pointer controller is responsible for providing synchronization and for tracking
 * display orientation changes if needed.
 */
class PointerControllerInterface : public virtual RefBase {
protected:
    PointerControllerInterface() { }
    virtual ~PointerControllerInterface() { }

public:
    /* Gets the bounds of the region that the pointer can traverse.
     * Returns true if the bounds are available. */
    virtual bool getBounds(float* outMinX, float* outMinY,
            float* outMaxX, float* outMaxY) const = 0;

    /* Move the pointer. */
    virtual void move(float deltaX, float deltaY) = 0;

    /* Sets a mask that indicates which buttons are pressed. */
    virtual void setButtonState(uint32_t buttonState) = 0;

    /* Gets a mask that indicates which buttons are pressed. */
    virtual uint32_t getButtonState() const = 0;

    /* Sets the absolute location of the pointer. */
    virtual void setPosition(float x, float y) = 0;

    /* Gets the absolute location of the pointer. */
    virtual void getPosition(float* outX, float* outY) const = 0;

    /* Fades the pointer out now. */
    virtual void fade() = 0;

    /* Makes the pointer visible if it has faded out. */
    virtual void unfade() = 0;
};


/*
 * Tracks pointer movements and draws the pointer sprite to a surface.
 *
 * Handles pointer acceleration and animation.
 */
class PointerController : public PointerControllerInterface, public MessageHandler {
protected:
    virtual ~PointerController();

public:
    enum InactivityFadeDelay {
        INACTIVITY_FADE_DELAY_NORMAL = 0,
        INACTIVITY_FADE_DELAY_SHORT = 1,
    };

    PointerController(const sp<Looper>& looper, const sp<SpriteController>& spriteController);

    virtual bool getBounds(float* outMinX, float* outMinY,
            float* outMaxX, float* outMaxY) const;
    virtual void move(float deltaX, float deltaY);
    virtual void setButtonState(uint32_t buttonState);
    virtual uint32_t getButtonState() const;
    virtual void setPosition(float x, float y);
    virtual void getPosition(float* outX, float* outY) const;
    virtual void fade();
    virtual void unfade();

    void setDisplaySize(int32_t width, int32_t height);
    void setDisplayOrientation(int32_t orientation);
    void setPointerIcon(const SkBitmap* bitmap, float hotSpotX, float hotSpotY);
    void setInactivityFadeDelay(InactivityFadeDelay inactivityFadeDelay);

private:
    enum {
        MSG_FADE_STEP = 0,
    };

    mutable Mutex mLock;

    sp<Looper> mLooper;
    sp<SpriteController> mSpriteController;
    sp<WeakMessageHandler> mHandler;

    struct Locked {
        int32_t displayWidth;
        int32_t displayHeight;
        int32_t displayOrientation;

        float pointerX;
        float pointerY;
        uint32_t buttonState;

        float fadeAlpha;
        InactivityFadeDelay inactivityFadeDelay;

        bool visible;

        sp<Sprite> sprite;
    } mLocked;

    bool getBoundsLocked(float* outMinX, float* outMinY, float* outMaxX, float* outMaxY) const;
    void setPositionLocked(float x, float y);
    void updateLocked();

    void handleMessage(const Message& message);
    bool unfadeBeforeUpdateLocked();
    void startFadeLocked();
    void startInactivityFadeDelayLocked();
    void fadeStepLocked();
    bool isFadingLocked();
    nsecs_t getInactivityFadeDelayTimeLocked();
    void sendFadeStepMessageDelayedLocked(nsecs_t delayTime);
};

} // namespace android

#endif // _UI_POINTER_CONTROLLER_H
