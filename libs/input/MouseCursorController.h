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

#ifndef _UI_MOUSE_CURSOR_CONTROLLER_H
#define _UI_MOUSE_CURSOR_CONTROLLER_H

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

#include "PointerControllerContext.h"
#include "SpriteController.h"

namespace android {

/*
 * Helper class for PointerController that specifically handles
 * mouse cursor resources and actions.
 */
class MouseCursorController {
public:
    MouseCursorController(PointerControllerContext& context);
    ~MouseCursorController();

    void move(float deltaX, float deltaY);
    void setPosition(float x, float y);
    FloatPoint getPosition() const;
    ui::LogicalDisplayId getDisplayId() const;
    void fade(PointerControllerInterface::Transition transition);
    void unfade(PointerControllerInterface::Transition transition);
    void setDisplayViewport(const DisplayViewport& viewport, bool getAdditionalMouseResources);
    void setStylusHoverMode(bool stylusHoverMode);

    // Set/Unset flag to hide the mouse cursor on the mirrored display
    void setSkipScreenshot(bool skip);

    void updatePointerIcon(PointerIconStyle iconId);
    void setCustomPointerIcon(const SpriteIcon& icon);
    void reloadPointerResources(bool getAdditionalMouseResources);

    void getAdditionalMouseResources();
    bool isViewportValid();

    bool doAnimations(nsecs_t timestamp);

    bool resourcesLoaded();

    std::string dump() const;

private:
    mutable std::mutex mLock;

    PointerResources mResources;

    PointerControllerContext& mContext;

    struct Locked {
        DisplayViewport viewport;
        bool stylusHoverMode;

        size_t animationFrameIndex;
        nsecs_t lastFrameUpdatedTime;

        int32_t pointerFadeDirection;
        float pointerX;
        float pointerY;
        float pointerAlpha;
        sp<Sprite> pointerSprite;
        SpriteIcon pointerIcon;
        bool updatePointerIcon;

        bool resourcesLoaded;

        std::map<PointerIconStyle, SpriteIcon> additionalMouseResources;
        std::map<PointerIconStyle, PointerAnimation> animationResources;

        PointerIconStyle requestedPointerType;
        PointerIconStyle resolvedPointerType;

        bool skipScreenshot{false};
        bool animating{false};

    } mLocked GUARDED_BY(mLock);

    void setPositionLocked(float x, float y);

    void updatePointerLocked();

    void loadResourcesLocked(bool getAdditionalMouseResources);

    bool doBitmapAnimationLocked(nsecs_t timestamp);
    bool doFadingAnimationLocked(nsecs_t timestamp);

    void startAnimationLocked();
};

} // namespace android

#endif // _UI_MOUSE_CURSOR_CONTROLLER_H
