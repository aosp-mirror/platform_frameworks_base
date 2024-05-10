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

#define LOG_TAG "TouchSpotController"

// Log debug messages about pointer updates
#define DEBUG_SPOT_UPDATES 0

#include "TouchSpotController.h"

#include <android-base/stringprintf.h>
#include <input/PrintTools.h>
#include <log/log.h>

#include <mutex>

#define INDENT "  "
#define INDENT2 "    "

namespace {
// Time to spend fading out the spot completely.
const nsecs_t SPOT_FADE_DURATION = 200 * 1000000LL; // 200 ms
} // namespace

namespace android {

// --- Spot ---

void TouchSpotController::Spot::updateSprite(const SpriteIcon* icon, float newX, float newY,
                                             int32_t displayId) {
    sprite->setLayer(Sprite::BASE_LAYER_SPOT + id);
    sprite->setAlpha(alpha);
    sprite->setTransformationMatrix(SpriteTransformationMatrix(scale, 0.0f, 0.0f, scale));
    sprite->setPosition(newX, newY);
    sprite->setDisplayId(displayId);
    x = newX;
    y = newY;

    if (icon != mLastIcon) {
        mLastIcon = icon;
        if (icon) {
            sprite->setIcon(*icon);
            sprite->setVisible(true);
        } else {
            sprite->setVisible(false);
        }
    }
}

void TouchSpotController::Spot::dump(std::string& out, const char* prefix) const {
    out += prefix;
    base::StringAppendF(&out, "Spot{id=%" PRIx32 ", alpha=%f, scale=%f, pos=[%f, %f]}\n", id, alpha,
                        scale, x, y);
}

// --- TouchSpotController ---

TouchSpotController::TouchSpotController(int32_t displayId, PointerControllerContext& context)
      : mDisplayId(displayId), mContext(context) {
    mContext.getPolicy()->loadPointerResources(&mResources, mDisplayId);
}

TouchSpotController::~TouchSpotController() {
    std::scoped_lock lock(mLock);

    size_t numSpots = mLocked.displaySpots.size();
    for (size_t i = 0; i < numSpots; i++) {
        delete mLocked.displaySpots[i];
    }
    mLocked.displaySpots.clear();
}

void TouchSpotController::setSpots(const PointerCoords* spotCoords, const uint32_t* spotIdToIndex,
                                   BitSet32 spotIdBits) {
#if DEBUG_SPOT_UPDATES
    ALOGD("setSpots: idBits=%08x", spotIdBits.value);
    for (BitSet32 idBits(spotIdBits); !idBits.isEmpty();) {
        uint32_t id = idBits.firstMarkedBit();
        idBits.clearBit(id);
        const PointerCoords& c = spotCoords[spotIdToIndex[id]];
        ALOGD(" spot %d: position=(%0.3f, %0.3f), pressure=%0.3f, displayId=%" PRId32 ".", id,
              c.getAxisValue(AMOTION_EVENT_AXIS_X), c.getAxisValue(AMOTION_EVENT_AXIS_Y),
              c.getAxisValue(AMOTION_EVENT_AXIS_PRESSURE), displayId);
    }
#endif

    std::scoped_lock lock(mLock);
    auto& spriteController = mContext.getSpriteController();
    spriteController.openTransaction();

    // Add or move spots for fingers that are down.
    for (BitSet32 idBits(spotIdBits); !idBits.isEmpty();) {
        uint32_t id = idBits.clearFirstMarkedBit();
        const PointerCoords& c = spotCoords[spotIdToIndex[id]];
        const SpriteIcon& icon = c.getAxisValue(AMOTION_EVENT_AXIS_PRESSURE) > 0
                ? mResources.spotTouch
                : mResources.spotHover;
        float x = c.getAxisValue(AMOTION_EVENT_AXIS_X);
        float y = c.getAxisValue(AMOTION_EVENT_AXIS_Y);

        Spot* spot = getSpot(id, mLocked.displaySpots);
        if (!spot) {
            spot = createAndAddSpotLocked(id, mLocked.displaySpots);
        }

        spot->updateSprite(&icon, x, y, mDisplayId);
    }

    for (Spot* spot : mLocked.displaySpots) {
        if (spot->id != Spot::INVALID_ID && !spotIdBits.hasBit(spot->id)) {
            fadeOutAndReleaseSpotLocked(spot);
        }
    }

    spriteController.closeTransaction();
}

void TouchSpotController::clearSpots() {
#if DEBUG_SPOT_UPDATES
    ALOGD("clearSpots");
#endif

    std::scoped_lock lock(mLock);
    fadeOutAndReleaseAllSpotsLocked();
}

TouchSpotController::Spot* TouchSpotController::getSpot(uint32_t id,
                                                        const std::vector<Spot*>& spots) {
    for (size_t i = 0; i < spots.size(); i++) {
        Spot* spot = spots[i];
        if (spot->id == id) {
            return spot;
        }
    }
    return nullptr;
}

TouchSpotController::Spot* TouchSpotController::createAndAddSpotLocked(uint32_t id,
                                                                       std::vector<Spot*>& spots)
        REQUIRES(mLock) {
    // Remove spots until we have fewer than MAX_SPOTS remaining.
    while (spots.size() >= MAX_SPOTS) {
        Spot* spot = removeFirstFadingSpotLocked(spots);
        if (!spot) {
            spot = spots[0];
            spots.erase(spots.begin());
        }
        releaseSpotLocked(spot);
    }

    // Obtain a sprite from the recycled pool.
    sp<Sprite> sprite;
    if (!mLocked.recycledSprites.empty()) {
        sprite = mLocked.recycledSprites.back();
        mLocked.recycledSprites.pop_back();
    } else {
        sprite = mContext.getSpriteController().createSprite();
    }

    // Return the new spot.
    Spot* spot = new Spot(id, sprite);
    spots.push_back(spot);
    return spot;
}

TouchSpotController::Spot* TouchSpotController::removeFirstFadingSpotLocked(
        std::vector<Spot*>& spots) REQUIRES(mLock) {
    for (size_t i = 0; i < spots.size(); i++) {
        Spot* spot = spots[i];
        if (spot->id == Spot::INVALID_ID) {
            spots.erase(spots.begin() + i);
            return spot;
        }
    }
    return NULL;
}

void TouchSpotController::releaseSpotLocked(Spot* spot) REQUIRES(mLock) {
    spot->sprite->clearIcon();

    if (mLocked.recycledSprites.size() < MAX_RECYCLED_SPRITES) {
        mLocked.recycledSprites.push_back(spot->sprite);
    }
    delete spot;
}

void TouchSpotController::fadeOutAndReleaseSpotLocked(Spot* spot) REQUIRES(mLock) {
    if (spot->id != Spot::INVALID_ID) {
        spot->id = Spot::INVALID_ID;
        startAnimationLocked();
    }
}

void TouchSpotController::fadeOutAndReleaseAllSpotsLocked() REQUIRES(mLock) {
    size_t numSpots = mLocked.displaySpots.size();
    for (size_t i = 0; i < numSpots; i++) {
        Spot* spot = mLocked.displaySpots[i];
        fadeOutAndReleaseSpotLocked(spot);
    }
}

void TouchSpotController::reloadSpotResources() {
    mContext.getPolicy()->loadPointerResources(&mResources, mDisplayId);
}

bool TouchSpotController::doAnimations(nsecs_t timestamp) {
    std::scoped_lock lock(mLock);
    bool keepAnimating = doFadingAnimationLocked(timestamp);
    if (!keepAnimating) {
        /*
         * We know that this callback will be removed before another
         * is added. mLock in PointerAnimator will not be released
         * until after this is removed, and adding another callback
         * requires that lock. Thus it's safe to set mLocked.animating
         * here.
         */
        mLocked.animating = false;
    }
    return keepAnimating;
}

bool TouchSpotController::doFadingAnimationLocked(nsecs_t timestamp) REQUIRES(mLock) {
    bool keepAnimating = false;
    nsecs_t animationTime = mContext.getAnimationTime();
    nsecs_t frameDelay = timestamp - animationTime;
    size_t numSpots = mLocked.displaySpots.size();
    for (size_t i = 0; i < numSpots;) {
        Spot* spot = mLocked.displaySpots[i];
        if (spot->id == Spot::INVALID_ID) {
            spot->alpha -= float(frameDelay) / SPOT_FADE_DURATION;
            if (spot->alpha <= 0) {
                mLocked.displaySpots.erase(mLocked.displaySpots.begin() + i);
                releaseSpotLocked(spot);
                numSpots--;
                continue;
            } else {
                spot->sprite->setAlpha(spot->alpha);
                keepAnimating = true;
            }
        }
        ++i;
    }
    return keepAnimating;
}

void TouchSpotController::startAnimationLocked() REQUIRES(mLock) {
    using namespace std::placeholders;

    if (mLocked.animating) {
        return;
    }
    mLocked.animating = true;

    std::function<bool(nsecs_t)> func = std::bind(&TouchSpotController::doAnimations, this, _1);
    mContext.addAnimationCallback(mDisplayId, func);
}

void TouchSpotController::dump(std::string& out, const char* prefix) const {
    using base::StringAppendF;
    out += prefix;
    out += "SpotController:\n";
    out += prefix;
    StringAppendF(&out, INDENT "DisplayId: %" PRId32 "\n", mDisplayId);
    std::scoped_lock lock(mLock);
    out += prefix;
    StringAppendF(&out, INDENT "Animating: %s\n", toString(mLocked.animating));
    out += prefix;
    out += INDENT "Spots:\n";
    std::string spotPrefix = prefix;
    spotPrefix += INDENT2;
    for (const auto& spot : mLocked.displaySpots) {
        spot->dump(out, spotPrefix.c_str());
    }
}

} // namespace android
