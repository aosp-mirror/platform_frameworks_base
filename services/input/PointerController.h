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
#include <androidfw/Input.h>
#include <utils/BitSet.h>
#include <utils/RefBase.h>
#include <utils/Looper.h>
#include <utils/String8.h>

#include <SkBitmap.h>

namespace android {

/**
 * Interface for tracking a mouse / touch pad pointer and touch pad spots.
 *
 * The spots are sprites on screen that visually represent the positions of
 * fingers
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
    virtual void setButtonState(int32_t buttonState) = 0;

    /* Gets a mask that indicates which buttons are pressed. */
    virtual int32_t getButtonState() const = 0;

    /* Sets the absolute location of the pointer. */
    virtual void setPosition(float x, float y) = 0;

    /* Gets the absolute location of the pointer. */
    virtual void getPosition(float* outX, float* outY) const = 0;

    enum Transition {
        // Fade/unfade immediately.
        TRANSITION_IMMEDIATE,
        // Fade/unfade gradually.
        TRANSITION_GRADUAL,
    };

    /* Fades the pointer out now. */
    virtual void fade(Transition transition) = 0;

    /* Makes the pointer visible if it has faded out.
     * The pointer never unfades itself automatically.  This method must be called
     * by the client whenever the pointer is moved or a button is pressed and it
     * wants to ensure that the pointer becomes visible again. */
    virtual void unfade(Transition transition) = 0;

    enum Presentation {
        // Show the mouse pointer.
        PRESENTATION_POINTER,
        // Show spots and a spot anchor in place of the mouse pointer.
        PRESENTATION_SPOT,
    };

    /* Sets the mode of the pointer controller. */
    virtual void setPresentation(Presentation presentation) = 0;

    /* Sets the spots for the current gesture.
     * The spots are not subject to the inactivity timeout like the pointer
     * itself it since they are expected to remain visible for so long as
     * the fingers are on the touch pad.
     *
     * The values of the AMOTION_EVENT_AXIS_PRESSURE axis is significant.
     * For spotCoords, pressure != 0 indicates that the spot's location is being
     * pressed (not hovering).
     */
    virtual void setSpots(const PointerCoords* spotCoords, const uint32_t* spotIdToIndex,
            BitSet32 spotIdBits) = 0;

    /* Removes all spots. */
    virtual void clearSpots() = 0;
};


/*
 * Pointer resources.
 */
struct PointerResources {
    SpriteIcon spotHover;
    SpriteIcon spotTouch;
    SpriteIcon spotAnchor;
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
    virtual void loadPointerResources(PointerResources* outResources) = 0;
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

    void setDisplaySize(int32_t width, int32_t height);
    void setDisplayOrientation(int32_t orientation);
    void setPointerIcon(const SpriteIcon& icon);
    void setInactivityTimeout(InactivityTimeout inactivityTimeout);

private:
    static const size_t MAX_RECYCLED_SPRITES = 12;
    static const size_t MAX_SPOTS = 12;

    enum {
        MSG_ANIMATE,
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

    PointerResources mResources;

    struct Locked {
        bool animationPending;
        nsecs_t animationTime;

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

        int32_t buttonState;

        Vector<Spot*> spots;
        Vector<sp<Sprite> > recycledSprites;
    } mLocked;

    bool getBoundsLocked(float* outMinX, float* outMinY, float* outMaxX, float* outMaxY) const;
    void setPositionLocked(float x, float y);

    void handleMessage(const Message& message);
    void doAnimate();
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
