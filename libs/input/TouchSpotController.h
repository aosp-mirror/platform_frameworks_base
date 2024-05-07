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

#ifndef _UI_TOUCH_SPOT_CONTROLLER_H
#define _UI_TOUCH_SPOT_CONTROLLER_H

#include <functional>

#include "PointerControllerContext.h"

namespace android {

/*
 * Helper class for PointerController that specifically handles
 * touch spot resources and actions for a single display.
 */
class TouchSpotController {
public:
    TouchSpotController(int32_t displayId, PointerControllerContext& context);
    ~TouchSpotController();
    void setSpots(const PointerCoords* spotCoords, const uint32_t* spotIdToIndex,
                  BitSet32 spotIdBits, bool skipScreenshot);
    void clearSpots();

    void reloadSpotResources();
    bool doAnimations(nsecs_t timestamp);

    void dump(std::string& out, const char* prefix = "") const;

private:
    struct Spot {
        static const uint32_t INVALID_ID = 0xffffffff;

        uint32_t id;
        sp<Sprite> sprite;
        float alpha;
        float scale;
        float x, y;

        inline Spot(uint32_t id, const sp<Sprite>& sprite)
              : id(id),
                sprite(sprite),
                alpha(1.0f),
                scale(1.0f),
                x(0.0f),
                y(0.0f),
                mLastIcon(nullptr) {}

        void updateSprite(const SpriteIcon* icon, float x, float y, int32_t displayId,
                          bool skipScreenshot);
        void dump(std::string& out, const char* prefix = "") const;

    private:
        const SpriteIcon* mLastIcon;
    };

    int32_t mDisplayId;

    mutable std::mutex mLock;

    PointerResources mResources;

    PointerControllerContext& mContext;

    static constexpr size_t MAX_RECYCLED_SPRITES = 12;
    static constexpr size_t MAX_SPOTS = 12;

    struct Locked {
        std::vector<Spot*> displaySpots;
        std::vector<sp<Sprite>> recycledSprites;

        bool animating{false};

    } mLocked GUARDED_BY(mLock);

    Spot* getSpot(uint32_t id, const std::vector<Spot*>& spots);
    Spot* createAndAddSpotLocked(uint32_t id, std::vector<Spot*>& spots);
    Spot* removeFirstFadingSpotLocked(std::vector<Spot*>& spots);
    void releaseSpotLocked(Spot* spot);
    void fadeOutAndReleaseSpotLocked(Spot* spot);
    void fadeOutAndReleaseAllSpotsLocked();
    bool doFadingAnimationLocked(nsecs_t timestamp);
    void startAnimationLocked();
};

} // namespace android

#endif // _UI_TOUCH_SPOT_CONTROLLER_H
