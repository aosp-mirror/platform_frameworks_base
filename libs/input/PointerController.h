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
            const sp<SpriteController>& spriteController);

    ~PointerController() override;

    std::optional<FloatRect> getBounds() const override;
    void move(float deltaX, float deltaY) override;
    void setPosition(float x, float y) override;
    FloatPoint getPosition() const override;
    int32_t getDisplayId() const override;
    void fade(Transition transition) override;
    void unfade(Transition transition) override;
    void setDisplayViewport(const DisplayViewport& viewport) override;

    void setPresentation(Presentation presentation) override;
    void setSpots(const PointerCoords* spotCoords, const uint32_t* spotIdToIndex,
                  BitSet32 spotIdBits, int32_t displayId) override;
    void clearSpots() override;

    void updatePointerIcon(PointerIconStyle iconId);
    void setCustomPointerIcon(const SpriteIcon& icon);
    void setInactivityTimeout(InactivityTimeout inactivityTimeout);
    void doInactivityTimeout();
    void reloadPointerResources();
    void onDisplayViewportsUpdated(std::vector<DisplayViewport>& viewports);

    void onDisplayInfosChangedLocked(const std::vector<gui::DisplayInfo>& displayInfos)
            REQUIRES(getLock());

    void dump(std::string& dump);

protected:
    using WindowListenerConsumer =
            std::function<void(const sp<android::gui::WindowInfosListener>&)>;

    // Constructor used to test WindowInfosListener registration.
    PointerController(const sp<PointerControllerPolicyInterface>& policy, const sp<Looper>& looper,
                      const sp<SpriteController>& spriteController,
                      WindowListenerConsumer registerListener,
                      WindowListenerConsumer unregisterListener);

private:
    PointerController(const sp<PointerControllerPolicyInterface>& policy, const sp<Looper>& looper,
                      const sp<SpriteController>& spriteController);

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
        int32_t pointerDisplayId = ADISPLAY_ID_NONE;

        std::vector<gui::DisplayInfo> mDisplayInfos;
        std::unordered_map<int32_t /* displayId */, TouchSpotController> spotControllers;
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
    const WindowListenerConsumer mUnregisterWindowInfosListener;

    const ui::Transform& getTransformForDisplayLocked(int displayId) const REQUIRES(getLock());

    void clearSpotsLocked() REQUIRES(getLock());
};

} // namespace android

#endif // _UI_POINTER_CONTROLLER_H
