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
#include <input/DisplayViewport.h>
#include <input/Input.h>
#include <utils/BitSet.h>
#include <utils/Looper.h>
#include <utils/RefBase.h>

#include <map>
#include <memory>
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

    virtual ~PointerController() = default;

    virtual bool getBounds(float* outMinX, float* outMinY, float* outMaxX, float* outMaxY) const;
    virtual void move(float deltaX, float deltaY);
    virtual void setButtonState(int32_t buttonState);
    virtual int32_t getButtonState() const;
    virtual void setPosition(float x, float y);
    virtual void getPosition(float* outX, float* outY) const;
    virtual int32_t getDisplayId() const;
    virtual void fade(Transition transition);
    virtual void unfade(Transition transition);
    virtual void setDisplayViewport(const DisplayViewport& viewport);

    virtual void setPresentation(Presentation presentation);
    virtual void setSpots(const PointerCoords* spotCoords, const uint32_t* spotIdToIndex,
                          BitSet32 spotIdBits, int32_t displayId);
    virtual void clearSpots();

    void updatePointerIcon(int32_t iconId);
    void setCustomPointerIcon(const SpriteIcon& icon);
    void setInactivityTimeout(InactivityTimeout inactivityTimeout);
    void doInactivityTimeout();
    void reloadPointerResources();
    void onDisplayViewportsUpdated(std::vector<DisplayViewport>& viewports);

private:
    friend PointerControllerContext::LooperCallback;
    friend PointerControllerContext::MessageHandler;

    mutable std::mutex mLock;

    PointerControllerContext mContext;

    MouseCursorController mCursorController;

    struct Locked {
        Presentation presentation;

        std::unordered_map<int32_t /* displayId */, TouchSpotController> spotControllers;
    } mLocked GUARDED_BY(mLock);

    PointerController(const sp<PointerControllerPolicyInterface>& policy, const sp<Looper>& looper,
                      const sp<SpriteController>& spriteController);
    void clearSpotsLocked();
};

} // namespace android

#endif // _UI_POINTER_CONTROLLER_H
