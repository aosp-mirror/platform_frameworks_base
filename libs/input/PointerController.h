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

#include <map>
#include <vector>

#include <ui/DisplayInfo.h>
#include <input/Input.h>
#include <inputflinger/PointerControllerInterface.h>
#include <utils/BitSet.h>
#include <utils/RefBase.h>
#include <utils/Looper.h>
#include <utils/String8.h>
#include <gui/DisplayEventReceiver.h>

namespace android {

/*
 * Pointer resources.
 */
struct PointerResources {
    SpriteIcon spotHover;
    SpriteIcon spotTouch;
    SpriteIcon spotAnchor;
};

struct PointerAnimation {
    std::vector<SpriteIcon> animationFrames;
    nsecs_t durationPerFrame;
};

/*
 * Pointer controller policy interface.
 *
 * The pointer controller policy is used by the pointer controller to interact with
 * the Window Manager and other system components.
 *
 * The actual implementation is partially supported by callbacks into the DVM
 * via JNI.  This interface is also mocked in the unit tests.
 */
class PointerControllerPolicyInterface : public virtual RefBase {
protected:
    PointerControllerPolicyInterface() { }
    virtual ~PointerControllerPolicyInterface() { }

public:
    virtual void loadPointerIcon(SpriteIcon* icon) = 0;
    virtual void loadPointerResources(PointerResources* outResources) = 0;
    virtual void loadAdditionalMouseResources(std::map<int32_t, SpriteIcon>* outResources,
            std::map<int32_t, PointerAnimation>* outAnimationResources) = 0;
    virtual int32_t getDefaultPointerIconId() = 0;
    virtual int32_t getCustomPointerIconId() = 0;
};


/*
 * Tracks pointer movements and draws the pointer sprite to a surface.
 *
 * Handles pointer acceleration and animation.
 */
class PointerController : public PointerControllerInterface, public MessageHandler,
                          public LooperCallback {
protected:
    virtual ~PointerController();

public:
    enum InactivityTimeout {
        INACTIVITY_TIMEOUT_NORMAL = 0,
        INACTIVITY_TIMEOUT_SHORT = 1,
    };

    PointerController(const sp<PointerControllerPolicyInterface>& policy,
            const sp<Looper>& looper, const sp<SpriteController>& spriteController);

    virtual bool getBounds(float* outMinX, float* outMinY,
            float* outMaxX, float* outMaxY) const;
    virtual void move(float deltaX, float deltaY);
    virtual void setButtonState(int32_t buttonState);
    virtual int32_t getButtonState() const;
    virtual void setPosition(float x, float y);
    virtual void getPosition(float* outX, float* outY) const;
    virtual void fade(Transition transition);
    virtual void unfade(Transition transition);

    virtual void setPresentation(Presentation presentation);
    virtual void setSpots(const PointerCoords* spotCoords,
            const uint32_t* spotIdToIndex, BitSet32 spotIdBits);
    virtual void clearSpots();

    void updatePointerIcon(int32_t iconId);
    void setCustomPointerIcon(const SpriteIcon& icon);
    void setDisplayViewport(int32_t width, int32_t height, int32_t orientation);
    void setInactivityTimeout(InactivityTimeout inactivityTimeout);
    void reloadPointerResources();

private:
    static const size_t MAX_RECYCLED_SPRITES = 12;
    static const size_t MAX_SPOTS = 12;

    enum {
        MSG_INACTIVITY_TIMEOUT,
    };

    struct Spot {
        static const uint32_t INVALID_ID = 0xffffffff;

        uint32_t id;
        sp<Sprite> sprite;
        float alpha;
        float scale;
        float x, y;

        inline Spot(uint32_t id, const sp<Sprite>& sprite)
                : id(id), sprite(sprite), alpha(1.0f), scale(1.0f),
                  x(0.0f), y(0.0f), lastIcon(NULL) { }

        void updateSprite(const SpriteIcon* icon, float x, float y);

    private:
        const SpriteIcon* lastIcon;
    };

    mutable Mutex mLock;

    sp<PointerControllerPolicyInterface> mPolicy;
    sp<Looper> mLooper;
    sp<SpriteController> mSpriteController;
    sp<WeakMessageHandler> mHandler;

    DisplayEventReceiver mDisplayEventReceiver;

    PointerResources mResources;

    struct Locked {
        bool animationPending;
        nsecs_t animationTime;

        size_t animationFrameIndex;
        nsecs_t lastFrameUpdatedTime;

        int32_t displayWidth;
        int32_t displayHeight;
        int32_t displayOrientation;

        InactivityTimeout inactivityTimeout;

        Presentation presentation;
        bool presentationChanged;

        int32_t pointerFadeDirection;
        float pointerX;
        float pointerY;
        float pointerAlpha;
        sp<Sprite> pointerSprite;
        SpriteIcon pointerIcon;
        bool pointerIconChanged;

        std::map<int32_t, SpriteIcon> additionalMouseResources;
        std::map<int32_t, PointerAnimation> animationResources;

        int32_t requestedPointerType;

        int32_t buttonState;

        Vector<Spot*> spots;
        Vector<sp<Sprite> > recycledSprites;
    } mLocked;

    bool getBoundsLocked(float* outMinX, float* outMinY, float* outMaxX, float* outMaxY) const;
    void setPositionLocked(float x, float y);

    void handleMessage(const Message& message);
    int handleEvent(int fd, int events, void* data);
    void doAnimate(nsecs_t timestamp);
    bool doFadingAnimationLocked(nsecs_t timestamp);
    bool doBitmapAnimationLocked(nsecs_t timestamp);
    void doInactivityTimeout();

    void startAnimationLocked();

    void resetInactivityTimeoutLocked();
    void removeInactivityTimeoutLocked();
    void updatePointerLocked();

    Spot* getSpotLocked(uint32_t id);
    Spot* createAndAddSpotLocked(uint32_t id);
    Spot* removeFirstFadingSpotLocked();
    void releaseSpotLocked(Spot* spot);
    void fadeOutAndReleaseSpotLocked(Spot* spot);
    void fadeOutAndReleaseAllSpotsLocked();

    void loadResources();
};

} // namespace android

#endif // _UI_POINTER_CONTROLLER_H
