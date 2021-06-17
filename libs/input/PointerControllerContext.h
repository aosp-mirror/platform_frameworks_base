/*
 * Copyright (C) 2020 The Android Open Source Project
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

#ifndef _UI_POINTER_CONTROLLER_CONTEXT_H
#define _UI_POINTER_CONTROLLER_CONTEXT_H

#include <PointerControllerInterface.h>
#include <gui/DisplayEventReceiver.h>
#include <input/DisplayViewport.h>
#include <input/Input.h>
#include <utils/BitSet.h>
#include <utils/Looper.h>
#include <utils/RefBase.h>

#include <functional>
#include <map>
#include <memory>
#include <vector>

#include "SpriteController.h"

namespace android {

class PointerController;
class MouseCursorController;
class TouchSpotController;

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

enum class InactivityTimeout {
    NORMAL = 0,
    SHORT = 1,
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
    PointerControllerPolicyInterface() {}
    virtual ~PointerControllerPolicyInterface() {}

public:
    virtual void loadPointerIcon(SpriteIcon* icon, int32_t displayId) = 0;
    virtual void loadPointerResources(PointerResources* outResources, int32_t displayId) = 0;
    virtual void loadAdditionalMouseResources(
            std::map<int32_t, SpriteIcon>* outResources,
            std::map<int32_t, PointerAnimation>* outAnimationResources, int32_t displayId) = 0;
    virtual int32_t getDefaultPointerIconId() = 0;
    virtual int32_t getCustomPointerIconId() = 0;
};

/*
 * Contains logic and resources shared among PointerController,
 * MouseCursorController, and TouchSpotController.
 */

class PointerControllerContext {
public:
    PointerControllerContext(const sp<PointerControllerPolicyInterface>& policy,
                             const sp<Looper>& looper, const sp<SpriteController>& spriteController,
                             PointerController& controller);
    ~PointerControllerContext();

    void removeInactivityTimeout();
    void resetInactivityTimeout();
    void startAnimation();
    void setInactivityTimeout(InactivityTimeout inactivityTimeout);

    nsecs_t getAnimationTime();

    void clearSpotsByDisplay(int32_t displayId);

    void setHandlerController(std::shared_ptr<PointerController> controller);
    void setCallbackController(std::shared_ptr<PointerController> controller);

    sp<PointerControllerPolicyInterface> getPolicy();
    sp<SpriteController> getSpriteController();

    void handleDisplayEvents();

    void addAnimationCallback(int32_t displayId, std::function<bool(nsecs_t)> callback);
    void removeAnimationCallback(int32_t displayId);

    class MessageHandler : public virtual android::MessageHandler {
    public:
        enum {
            MSG_INACTIVITY_TIMEOUT,
        };

        void handleMessage(const Message& message) override;
        std::weak_ptr<PointerController> pointerController;
    };

    class LooperCallback : public virtual android::LooperCallback {
    public:
        int handleEvent(int fd, int events, void* data) override;
        std::weak_ptr<PointerController> pointerController;
    };

private:
    class PointerAnimator {
    public:
        PointerAnimator(PointerControllerContext& context);

        void addCallback(int32_t displayId, std::function<bool(nsecs_t)> callback);
        void removeCallback(int32_t displayId);
        void handleVsyncEvents();
        nsecs_t getAnimationTimeLocked();

        mutable std::mutex mLock;

    private:
        struct Locked {
            bool animationPending{false};
            nsecs_t animationTime{systemTime(SYSTEM_TIME_MONOTONIC)};

            std::unordered_map<int32_t, std::function<bool(nsecs_t)>> callbacks;
        } mLocked GUARDED_BY(mLock);

        DisplayEventReceiver mDisplayEventReceiver;

        PointerControllerContext& mContext;

        void initializeDisplayEventReceiver();
        void startAnimationLocked();
        void handleCallbacksLocked(nsecs_t timestamp);
    };

    sp<PointerControllerPolicyInterface> mPolicy;
    sp<Looper> mLooper;
    sp<SpriteController> mSpriteController;
    sp<MessageHandler> mHandler;
    sp<LooperCallback> mCallback;

    PointerController& mController;

    PointerAnimator mAnimator;

    mutable std::mutex mLock;

    struct Locked {
        InactivityTimeout inactivityTimeout;
    } mLocked GUARDED_BY(mLock);

    void resetInactivityTimeoutLocked();
};

} // namespace android

#endif // _UI_POINTER_CONTROLLER_CONTEXT_H
