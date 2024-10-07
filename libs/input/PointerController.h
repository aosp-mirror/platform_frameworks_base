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

#include <PointerControllerInterface.h>
#include <gui/DisplayEventReceiver.h>
#include <gui/WindowInfosUpdate.h>
#include <input/DisplayViewport.h>
#include <input/Input.h>
#include <utils/BitSet.h>
#include <utils/Looper.h>
#include <utils/RefBase.h>

#include <map>
#include <memory>
#include <string>
#include <vector>

#include "MouseCursorController.h"
#include "PointerControllerContext.h"
#include "SpriteController.h"
#include "TouchSpotController.h"

namespace android {

/*
 * Tracks pointer movements and draws the pointer sprite to a surface.
 *
 * Handles pointer acceleration and animation.
 */
class PointerController : public PointerControllerInterface {
public:
    static std::shared_ptr<PointerController> create(
            const sp<PointerControllerPolicyInterface>& policy, const sp<Looper>& looper,
            SpriteController& spriteController, ControllerType type);

    ~PointerController() override;

    void move(float deltaX, float deltaY) override;
    void setPosition(float x, float y) override;
    FloatPoint getPosition() const override;
    ui::LogicalDisplayId getDisplayId() const override;
    void fade(Transition transition) override;
    void unfade(Transition transition) override;
    void setDisplayViewport(const DisplayViewport& viewport) override;

    void setPresentation(Presentation presentation) override;
    void setSpots(const PointerCoords* spotCoords, const uint32_t* spotIdToIndex,
                  BitSet32 spotIdBits, ui::LogicalDisplayId displayId) override;
    void clearSpots() override;
    void updatePointerIcon(PointerIconStyle iconId) override;
    void setCustomPointerIcon(const SpriteIcon& icon) override;
    void setSkipScreenshotFlagForDisplay(ui::LogicalDisplayId displayId) override;
    void clearSkipScreenshotFlags() override;

    virtual void setInactivityTimeout(InactivityTimeout inactivityTimeout);
    void doInactivityTimeout();
    void reloadPointerResources();
    void onDisplayViewportsUpdated(const std::vector<DisplayViewport>& viewports);

    void onDisplayInfosChangedLocked(const std::vector<gui::DisplayInfo>& displayInfos)
            REQUIRES(getLock());

    std::string dump() override;

protected:
    using WindowListenerRegisterConsumer = std::function<std::vector<gui::DisplayInfo>(
            const sp<android::gui::WindowInfosListener>&)>;
    using WindowListenerUnregisterConsumer =
            std::function<void(const sp<android::gui::WindowInfosListener>&)>;

    // Constructor used to test WindowInfosListener registration.
    PointerController(const sp<PointerControllerPolicyInterface>& policy, const sp<Looper>& looper,
                      SpriteController& spriteController,
                      const WindowListenerRegisterConsumer& registerListener,
                      WindowListenerUnregisterConsumer unregisterListener);

    PointerController(const sp<PointerControllerPolicyInterface>& policy, const sp<Looper>& looper,
                      SpriteController& spriteController);

private:
    friend PointerControllerContext::LooperCallback;
    friend PointerControllerContext::MessageHandler;

    // PointerController's DisplayInfoListener can outlive the PointerController because when the
    // listener is registered, a strong pointer to the listener (which can extend its lifecycle)
    // is given away. To avoid the small overhead of using two separate locks in these two objects,
    // we use the DisplayInfoListener's lock in PointerController.
    std::mutex& getLock() const;

    PointerControllerContext mContext;

    MouseCursorController mCursorController;

    struct Locked {
        Presentation presentation;
        ui::LogicalDisplayId pointerDisplayId = ui::LogicalDisplayId::INVALID;

        std::vector<gui::DisplayInfo> mDisplayInfos;
        std::unordered_map<ui::LogicalDisplayId, TouchSpotController> spotControllers;
        std::unordered_set<ui::LogicalDisplayId> displaysToSkipScreenshot;
    } mLocked GUARDED_BY(getLock());

    class DisplayInfoListener : public gui::WindowInfosListener {
    public:
        explicit DisplayInfoListener(PointerController* pc) : mPointerController(pc){};
        void onWindowInfosChanged(const gui::WindowInfosUpdate&) override;
        void onPointerControllerDestroyed();

        // This lock is also used by PointerController. See PointerController::getLock().
        std::mutex mLock;

    private:
        PointerController* mPointerController GUARDED_BY(mLock);
    };

    sp<DisplayInfoListener> mDisplayInfoListener;
    const WindowListenerUnregisterConsumer mUnregisterWindowInfosListener;

    const ui::Transform& getTransformForDisplayLocked(ui::LogicalDisplayId displayId) const
            REQUIRES(getLock());

    void clearSpotsLocked() REQUIRES(getLock());
};

class MousePointerController : public PointerController {
public:
    /** A version of PointerController that controls one mouse pointer. */
    MousePointerController(const sp<PointerControllerPolicyInterface>& policy,
                           const sp<Looper>& looper, SpriteController& spriteController);

    ~MousePointerController() override;

    void setPresentation(Presentation) override {
        LOG_ALWAYS_FATAL("Should not be called");
    }
    void setSpots(const PointerCoords*, const uint32_t*, BitSet32, ui::LogicalDisplayId) override {
        LOG_ALWAYS_FATAL("Should not be called");
    }
    void clearSpots() override {
        LOG_ALWAYS_FATAL("Should not be called");
    }
};

class TouchPointerController : public PointerController {
public:
    /** A version of PointerController that controls touch spots. */
    TouchPointerController(const sp<PointerControllerPolicyInterface>& policy,
                           const sp<Looper>& looper, SpriteController& spriteController);

    ~TouchPointerController() override;

    void move(float, float) override {
        LOG_ALWAYS_FATAL("Should not be called");
    }
    void setPosition(float, float) override {
        LOG_ALWAYS_FATAL("Should not be called");
    }
    FloatPoint getPosition() const override {
        LOG_ALWAYS_FATAL("Should not be called");
    }
    ui::LogicalDisplayId getDisplayId() const override {
        LOG_ALWAYS_FATAL("Should not be called");
    }
    void fade(Transition) override {
        LOG_ALWAYS_FATAL("Should not be called");
    }
    void unfade(Transition) override {
        LOG_ALWAYS_FATAL("Should not be called");
    }
    void setDisplayViewport(const DisplayViewport&) override {
        LOG_ALWAYS_FATAL("Should not be called");
    }
    void setPresentation(Presentation) override {
        LOG_ALWAYS_FATAL("Should not be called");
    }
    void updatePointerIcon(PointerIconStyle) override {
        LOG_ALWAYS_FATAL("Should not be called");
    }
    void setCustomPointerIcon(const SpriteIcon&) override {
        LOG_ALWAYS_FATAL("Should not be called");
    }
    // fade() should not be called by inactivity timeout. Do nothing.
    void setInactivityTimeout(InactivityTimeout) override {}
};

class StylusPointerController : public PointerController {
public:
    /** A version of PointerController that controls one stylus pointer. */
    StylusPointerController(const sp<PointerControllerPolicyInterface>& policy,
                            const sp<Looper>& looper, SpriteController& spriteController);

    ~StylusPointerController() override;

    void setPresentation(Presentation) override {
        LOG_ALWAYS_FATAL("Should not be called");
    }
    void setSpots(const PointerCoords*, const uint32_t*, BitSet32, ui::LogicalDisplayId) override {
        LOG_ALWAYS_FATAL("Should not be called");
    }
    void clearSpots() override {
        LOG_ALWAYS_FATAL("Should not be called");
    }
};

} // namespace android

#endif // _UI_POINTER_CONTROLLER_H
