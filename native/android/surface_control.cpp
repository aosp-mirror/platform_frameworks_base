/*
 * Copyright 2018 The Android Open Source Project
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

#include <android/hardware/configstore/1.0/ISurfaceFlingerConfigs.h>
#include <android/native_window.h>
#include <android/surface_control.h>
#include <surface_control_private.h>

#include <configstore/Utils.h>

#include <gui/HdrMetadata.h>
#include <gui/ISurfaceComposer.h>
#include <gui/Surface.h>
#include <gui/SurfaceComposerClient.h>
#include <gui/SurfaceControl.h>

#include <ui/DynamicDisplayInfo.h>

#include <utils/Timers.h>

using namespace android::hardware::configstore;
using namespace android::hardware::configstore::V1_0;
using namespace android;

using Transaction = SurfaceComposerClient::Transaction;

#define CHECK_NOT_NULL(name) \
    LOG_ALWAYS_FATAL_IF(name == nullptr, "nullptr passed as " #name " argument");

#define CHECK_VALID_RECT(name)                                     \
    LOG_ALWAYS_FATAL_IF(!static_cast<const Rect&>(name).isValid(), \
                        "invalid arg passed as " #name " argument");

static bool getWideColorSupport(const sp<SurfaceControl>& surfaceControl) {
    sp<SurfaceComposerClient> client = surfaceControl->getClient();

    const sp<IBinder> display = client->getInternalDisplayToken();
    if (display == nullptr) {
        ALOGE("unable to get wide color support for disconnected internal display");
        return false;
    }

    bool isWideColorDisplay = false;
    status_t err = client->isWideColorDisplay(display, &isWideColorDisplay);
    if (err) {
        ALOGE("unable to get wide color support");
        return false;
    }
    return isWideColorDisplay;
}

static bool getHdrSupport(const sp<SurfaceControl>& surfaceControl) {
    sp<SurfaceComposerClient> client = surfaceControl->getClient();

    const sp<IBinder> display = client->getInternalDisplayToken();
    if (display == nullptr) {
        ALOGE("unable to get hdr capabilities for disconnected internal display");
        return false;
    }

    ui::DynamicDisplayInfo info;
    if (status_t err = client->getDynamicDisplayInfo(display, &info); err != NO_ERROR) {
        ALOGE("unable to get hdr capabilities");
        return err;
    }

    return !info.hdrCapabilities.getSupportedHdrTypes().empty();
}

static bool isDataSpaceValid(const sp<SurfaceControl>& surfaceControl, ADataSpace dataSpace) {
    static_assert(static_cast<int>(ADATASPACE_UNKNOWN) == static_cast<int>(HAL_DATASPACE_UNKNOWN));
    static_assert(static_cast<int>(ADATASPACE_SCRGB_LINEAR) == static_cast<int>(HAL_DATASPACE_V0_SCRGB_LINEAR));
    static_assert(static_cast<int>(ADATASPACE_SRGB) == static_cast<int>(HAL_DATASPACE_V0_SRGB));
    static_assert(static_cast<int>(ADATASPACE_SCRGB) == static_cast<int>(HAL_DATASPACE_V0_SCRGB));
    static_assert(static_cast<int>(ADATASPACE_DISPLAY_P3) == static_cast<int>(HAL_DATASPACE_DISPLAY_P3));
    static_assert(static_cast<int>(ADATASPACE_BT2020_PQ) == static_cast<int>(HAL_DATASPACE_BT2020_PQ));

    switch (static_cast<android_dataspace_t>(dataSpace)) {
        case HAL_DATASPACE_UNKNOWN:
        case HAL_DATASPACE_V0_SRGB:
            return true;
        // These data space need wide gamut support.
        case HAL_DATASPACE_V0_SCRGB_LINEAR:
        case HAL_DATASPACE_V0_SCRGB:
        case HAL_DATASPACE_DISPLAY_P3:
            return getWideColorSupport(surfaceControl);
        // These data space need HDR support.
        case HAL_DATASPACE_BT2020_PQ:
            if (!getHdrSupport(surfaceControl)) {
                ALOGE("Invalid dataspace - device does not support hdr");
                return false;
            }
            return true;
        default:
            return false;
    }
}

Transaction* ASurfaceTransaction_to_Transaction(ASurfaceTransaction* aSurfaceTransaction) {
    return reinterpret_cast<Transaction*>(aSurfaceTransaction);
}

SurfaceControl* ASurfaceControl_to_SurfaceControl(ASurfaceControl* aSurfaceControl) {
    return reinterpret_cast<SurfaceControl*>(aSurfaceControl);
}

void SurfaceControl_acquire(SurfaceControl* surfaceControl) {
    // incStrong/decStrong token must be the same, doesn't matter what it is
    surfaceControl->incStrong((void*)SurfaceControl_acquire);
}

void SurfaceControl_release(SurfaceControl* surfaceControl) {
    // incStrong/decStrong token must be the same, doesn't matter what it is
    surfaceControl->decStrong((void*)SurfaceControl_acquire);
}

ASurfaceControl* ASurfaceControl_createFromWindow(ANativeWindow* window, const char* debug_name) {
    CHECK_NOT_NULL(window);
    CHECK_NOT_NULL(debug_name);

    sp<SurfaceComposerClient> client = new SurfaceComposerClient();
    if (client->initCheck() != NO_ERROR) {
        return nullptr;
    }

    Surface* surface = static_cast<Surface*>(window);
    sp<IBinder> parentHandle = surface->getSurfaceControlHandle();

    uint32_t flags = ISurfaceComposerClient::eFXSurfaceBufferState;
    sp<SurfaceControl> surfaceControl;
    if (parentHandle) {
        surfaceControl =
                client->createSurface(String8(debug_name), 0 /* width */, 0 /* height */,
                                      // Format is only relevant for buffer queue layers.
                                      PIXEL_FORMAT_UNKNOWN /* format */, flags, parentHandle);
    } else {
        surfaceControl =
                client->createWithSurfaceParent(String8(debug_name), 0 /* width */, 0 /* height */,
                                                // Format is only relevant for buffer queue layers.
                                                PIXEL_FORMAT_UNKNOWN /* format */, flags,
                                                static_cast<Surface*>(window));
    }

    if (!surfaceControl) {
        return nullptr;
    }

    SurfaceControl_acquire(surfaceControl.get());
    return reinterpret_cast<ASurfaceControl*>(surfaceControl.get());
}

ASurfaceControl* ASurfaceControl_create(ASurfaceControl* parent, const char* debug_name) {
    CHECK_NOT_NULL(parent);
    CHECK_NOT_NULL(debug_name);

    SurfaceComposerClient* client = ASurfaceControl_to_SurfaceControl(parent)->getClient().get();

    SurfaceControl* surfaceControlParent = ASurfaceControl_to_SurfaceControl(parent);

    uint32_t flags = ISurfaceComposerClient::eFXSurfaceBufferState;
    sp<SurfaceControl> surfaceControl =
            client->createSurface(String8(debug_name), 0 /* width */, 0 /* height */,
                                  // Format is only relevant for buffer queue layers.
                                  PIXEL_FORMAT_UNKNOWN /* format */, flags,
                                  surfaceControlParent->getHandle());
    if (!surfaceControl) {
        return nullptr;
    }

    SurfaceControl_acquire(surfaceControl.get());
    return reinterpret_cast<ASurfaceControl*>(surfaceControl.get());
}

void ASurfaceControl_acquire(ASurfaceControl* aSurfaceControl) {
    SurfaceControl* surfaceControl = ASurfaceControl_to_SurfaceControl(aSurfaceControl);

    SurfaceControl_acquire(surfaceControl);
}

void ASurfaceControl_release(ASurfaceControl* aSurfaceControl) {
    SurfaceControl* surfaceControl = ASurfaceControl_to_SurfaceControl(aSurfaceControl);

    SurfaceControl_release(surfaceControl);
}

struct ASurfaceControlStats {
    int64_t acquireTime;
    sp<Fence> previousReleaseFence;
    uint64_t frameNumber;
};

void ASurfaceControl_registerSurfaceStatsListener(ASurfaceControl* control, void* context,
        ASurfaceControl_SurfaceStatsListener func) {
    SurfaceStatsCallback callback = [func](void* callback_context,
                                                               nsecs_t,
                                                               const sp<Fence>&,
                                                               const SurfaceStats& surfaceStats) {

        ASurfaceControlStats aSurfaceControlStats;

        ASurfaceControl* aSurfaceControl =
                reinterpret_cast<ASurfaceControl*>(surfaceStats.surfaceControl.get());
        aSurfaceControlStats.acquireTime = surfaceStats.acquireTime;
        aSurfaceControlStats.previousReleaseFence = surfaceStats.previousReleaseFence;
        aSurfaceControlStats.frameNumber = surfaceStats.eventStats.frameNumber;

        (*func)(callback_context, aSurfaceControl, &aSurfaceControlStats);
    };
    TransactionCompletedListener::getInstance()->addSurfaceStatsListener(context,
            reinterpret_cast<void*>(func), ASurfaceControl_to_SurfaceControl(control), callback);
}


void ASurfaceControl_unregisterSurfaceStatsListener(void* context,
        ASurfaceControl_SurfaceStatsListener func) {
    TransactionCompletedListener::getInstance()->removeSurfaceStatsListener(context,
            reinterpret_cast<void*>(func));
}

int64_t ASurfaceControlStats_getAcquireTime(ASurfaceControlStats* stats) {
    return stats->acquireTime;
}

uint64_t ASurfaceControlStats_getFrameNumber(ASurfaceControlStats* stats) {
    return stats->frameNumber;
}

ASurfaceTransaction* ASurfaceTransaction_create() {
    Transaction* transaction = new Transaction;
    return reinterpret_cast<ASurfaceTransaction*>(transaction);
}

void ASurfaceTransaction_delete(ASurfaceTransaction* aSurfaceTransaction) {
    Transaction* transaction = ASurfaceTransaction_to_Transaction(aSurfaceTransaction);
    delete transaction;
}

void ASurfaceTransaction_apply(ASurfaceTransaction* aSurfaceTransaction) {
    CHECK_NOT_NULL(aSurfaceTransaction);

    Transaction* transaction = ASurfaceTransaction_to_Transaction(aSurfaceTransaction);

    transaction->apply();
}

struct ASurfaceTransactionStats {
    std::unordered_map<ASurfaceControl*, ASurfaceControlStats> aSurfaceControlStats;
    int64_t latchTime;
    sp<Fence> presentFence;
};

int64_t ASurfaceTransactionStats_getLatchTime(ASurfaceTransactionStats* aSurfaceTransactionStats) {
    CHECK_NOT_NULL(aSurfaceTransactionStats);
    return aSurfaceTransactionStats->latchTime;
}

int ASurfaceTransactionStats_getPresentFenceFd(ASurfaceTransactionStats* aSurfaceTransactionStats) {
    CHECK_NOT_NULL(aSurfaceTransactionStats);
    auto& presentFence = aSurfaceTransactionStats->presentFence;
    return (presentFence) ? presentFence->dup() : -1;
}

void ASurfaceTransactionStats_getASurfaceControls(ASurfaceTransactionStats* aSurfaceTransactionStats,
                                                  ASurfaceControl*** outASurfaceControls,
                                                  size_t* outASurfaceControlsSize) {
    CHECK_NOT_NULL(aSurfaceTransactionStats);
    CHECK_NOT_NULL(outASurfaceControls);
    CHECK_NOT_NULL(outASurfaceControlsSize);

    size_t size = aSurfaceTransactionStats->aSurfaceControlStats.size();

    SurfaceControl** surfaceControls = new SurfaceControl*[size];
    ASurfaceControl** aSurfaceControls = reinterpret_cast<ASurfaceControl**>(surfaceControls);

    size_t i = 0;
    for (auto& [aSurfaceControl, aSurfaceControlStats] : aSurfaceTransactionStats->aSurfaceControlStats) {
        aSurfaceControls[i] = aSurfaceControl;
        i++;
    }

    *outASurfaceControls = aSurfaceControls;
    *outASurfaceControlsSize = size;
}

int64_t ASurfaceTransactionStats_getAcquireTime(ASurfaceTransactionStats* aSurfaceTransactionStats,
                                                ASurfaceControl* aSurfaceControl) {
    CHECK_NOT_NULL(aSurfaceTransactionStats);
    CHECK_NOT_NULL(aSurfaceControl);

    const auto& aSurfaceControlStats =
            aSurfaceTransactionStats->aSurfaceControlStats.find(aSurfaceControl);
    LOG_ALWAYS_FATAL_IF(
            aSurfaceControlStats == aSurfaceTransactionStats->aSurfaceControlStats.end(),
            "ASurfaceControl not found");

    return aSurfaceControlStats->second.acquireTime;
}

int ASurfaceTransactionStats_getPreviousReleaseFenceFd(
            ASurfaceTransactionStats* aSurfaceTransactionStats, ASurfaceControl* aSurfaceControl) {
    CHECK_NOT_NULL(aSurfaceTransactionStats);
    CHECK_NOT_NULL(aSurfaceControl);

    const auto& aSurfaceControlStats =
            aSurfaceTransactionStats->aSurfaceControlStats.find(aSurfaceControl);
    LOG_ALWAYS_FATAL_IF(
            aSurfaceControlStats == aSurfaceTransactionStats->aSurfaceControlStats.end(),
            "ASurfaceControl not found");

    auto& previousReleaseFence = aSurfaceControlStats->second.previousReleaseFence;
    return (previousReleaseFence) ? previousReleaseFence->dup() : -1;
}

void ASurfaceTransactionStats_releaseASurfaceControls(ASurfaceControl** aSurfaceControls) {
    CHECK_NOT_NULL(aSurfaceControls);

    SurfaceControl** surfaceControls = reinterpret_cast<SurfaceControl**>(aSurfaceControls);
    delete[] surfaceControls;
}

void ASurfaceTransaction_setOnComplete(ASurfaceTransaction* aSurfaceTransaction, void* context,
                                       ASurfaceTransaction_OnComplete func) {
    CHECK_NOT_NULL(aSurfaceTransaction);
    CHECK_NOT_NULL(context);
    CHECK_NOT_NULL(func);

    TransactionCompletedCallbackTakesContext callback = [func](void* callback_context,
                                                               nsecs_t latchTime,
                                                               const sp<Fence>& presentFence,
                                                               const std::vector<SurfaceControlStats>& surfaceControlStats) {
        ASurfaceTransactionStats aSurfaceTransactionStats;

        aSurfaceTransactionStats.latchTime = latchTime;
        aSurfaceTransactionStats.presentFence = presentFence;

        auto& aSurfaceControlStats = aSurfaceTransactionStats.aSurfaceControlStats;

        for (const auto& [surfaceControl, latchTime, acquireTime, presentFence, previousReleaseFence, transformHint, frameEvents] : surfaceControlStats) {
            ASurfaceControl* aSurfaceControl = reinterpret_cast<ASurfaceControl*>(surfaceControl.get());
            aSurfaceControlStats[aSurfaceControl].acquireTime = acquireTime;
            aSurfaceControlStats[aSurfaceControl].previousReleaseFence = previousReleaseFence;
        }

        (*func)(callback_context, &aSurfaceTransactionStats);
    };

    Transaction* transaction = ASurfaceTransaction_to_Transaction(aSurfaceTransaction);

    transaction->addTransactionCompletedCallback(callback, context);
}

void ASurfaceTransaction_reparent(ASurfaceTransaction* aSurfaceTransaction,
                                  ASurfaceControl* aSurfaceControl,
                                  ASurfaceControl* newParentASurfaceControl) {
    CHECK_NOT_NULL(aSurfaceTransaction);
    CHECK_NOT_NULL(aSurfaceControl);

    sp<SurfaceControl> surfaceControl = ASurfaceControl_to_SurfaceControl(aSurfaceControl);
    sp<SurfaceControl> newParentSurfaceControl = ASurfaceControl_to_SurfaceControl(
            newParentASurfaceControl);
    Transaction* transaction = ASurfaceTransaction_to_Transaction(aSurfaceTransaction);

    transaction->reparent(surfaceControl, newParentSurfaceControl);
}

void ASurfaceTransaction_setVisibility(ASurfaceTransaction* aSurfaceTransaction,
                                       ASurfaceControl* aSurfaceControl,
                                       int8_t visibility) {
    CHECK_NOT_NULL(aSurfaceTransaction);
    CHECK_NOT_NULL(aSurfaceControl);

    sp<SurfaceControl> surfaceControl = ASurfaceControl_to_SurfaceControl(aSurfaceControl);
    Transaction* transaction = ASurfaceTransaction_to_Transaction(aSurfaceTransaction);

    switch (visibility) {
    case ASURFACE_TRANSACTION_VISIBILITY_SHOW:
        transaction->show(surfaceControl);
        break;
    case ASURFACE_TRANSACTION_VISIBILITY_HIDE:
        transaction->hide(surfaceControl);
        break;
    default:
        LOG_ALWAYS_FATAL("invalid visibility %d", visibility);
    }
}

void ASurfaceTransaction_setZOrder(ASurfaceTransaction* aSurfaceTransaction,
                                   ASurfaceControl* aSurfaceControl,
                                   int32_t z_order) {
    CHECK_NOT_NULL(aSurfaceTransaction);
    CHECK_NOT_NULL(aSurfaceControl);

    sp<SurfaceControl> surfaceControl = ASurfaceControl_to_SurfaceControl(aSurfaceControl);
    Transaction* transaction = ASurfaceTransaction_to_Transaction(aSurfaceTransaction);

    transaction->setLayer(surfaceControl, z_order);
}

void ASurfaceTransaction_setBuffer(ASurfaceTransaction* aSurfaceTransaction,
                                   ASurfaceControl* aSurfaceControl,
                                   AHardwareBuffer* buffer, int acquire_fence_fd) {
    CHECK_NOT_NULL(aSurfaceTransaction);
    CHECK_NOT_NULL(aSurfaceControl);

    sp<SurfaceControl> surfaceControl = ASurfaceControl_to_SurfaceControl(aSurfaceControl);
    Transaction* transaction = ASurfaceTransaction_to_Transaction(aSurfaceTransaction);

    sp<GraphicBuffer> graphic_buffer(reinterpret_cast<GraphicBuffer*>(buffer));

    transaction->setBuffer(surfaceControl, graphic_buffer);
    if (acquire_fence_fd != -1) {
        sp<Fence> fence = new Fence(acquire_fence_fd);
        transaction->setAcquireFence(surfaceControl, fence);
    }
}

void ASurfaceTransaction_setGeometry(ASurfaceTransaction* aSurfaceTransaction,
                                     ASurfaceControl* aSurfaceControl, const ARect& source,
                                     const ARect& destination, int32_t transform) {
    CHECK_NOT_NULL(aSurfaceTransaction);
    CHECK_NOT_NULL(aSurfaceControl);
    CHECK_VALID_RECT(source);
    CHECK_VALID_RECT(destination);

    sp<SurfaceControl> surfaceControl = ASurfaceControl_to_SurfaceControl(aSurfaceControl);
    Transaction* transaction = ASurfaceTransaction_to_Transaction(aSurfaceTransaction);

    transaction->setCrop(surfaceControl, static_cast<const Rect&>(source));
    transaction->setFrame(surfaceControl, static_cast<const Rect&>(destination));
    transaction->setTransform(surfaceControl, transform);
    bool transformToInverseDisplay = (NATIVE_WINDOW_TRANSFORM_INVERSE_DISPLAY & transform) ==
            NATIVE_WINDOW_TRANSFORM_INVERSE_DISPLAY;
    transaction->setTransformToDisplayInverse(surfaceControl, transformToInverseDisplay);
}

void ASurfaceTransaction_setSourceRect(ASurfaceTransaction* aSurfaceTransaction,
                                       ASurfaceControl* aSurfaceControl, const ARect& source) {
    CHECK_NOT_NULL(aSurfaceTransaction);
    CHECK_NOT_NULL(aSurfaceControl);
    CHECK_VALID_RECT(source);

    sp<SurfaceControl> surfaceControl = ASurfaceControl_to_SurfaceControl(aSurfaceControl);
    Transaction* transaction = ASurfaceTransaction_to_Transaction(aSurfaceTransaction);

    transaction->setCrop(surfaceControl, static_cast<const Rect&>(source));
}

void ASurfaceTransaction_setPosition(ASurfaceTransaction* aSurfaceTransaction,
                                     ASurfaceControl* aSurfaceControl, const ARect& destination) {
    CHECK_NOT_NULL(aSurfaceTransaction);
    CHECK_NOT_NULL(aSurfaceControl);
    CHECK_VALID_RECT(destination);

    sp<SurfaceControl> surfaceControl = ASurfaceControl_to_SurfaceControl(aSurfaceControl);
    Transaction* transaction = ASurfaceTransaction_to_Transaction(aSurfaceTransaction);

    transaction->setFrame(surfaceControl, static_cast<const Rect&>(destination));
}

void ASurfaceTransaction_setTransform(ASurfaceTransaction* aSurfaceTransaction,
                                      ASurfaceControl* aSurfaceControl, int32_t transform) {
    CHECK_NOT_NULL(aSurfaceTransaction);
    CHECK_NOT_NULL(aSurfaceControl);

    sp<SurfaceControl> surfaceControl = ASurfaceControl_to_SurfaceControl(aSurfaceControl);
    Transaction* transaction = ASurfaceTransaction_to_Transaction(aSurfaceTransaction);

    transaction->setTransform(surfaceControl, transform);
    bool transformToInverseDisplay = (NATIVE_WINDOW_TRANSFORM_INVERSE_DISPLAY & transform) ==
            NATIVE_WINDOW_TRANSFORM_INVERSE_DISPLAY;
    transaction->setTransformToDisplayInverse(surfaceControl, transformToInverseDisplay);
}

void ASurfaceTransaction_setBufferTransparency(ASurfaceTransaction* aSurfaceTransaction,
                                               ASurfaceControl* aSurfaceControl,
                                               int8_t transparency) {
    CHECK_NOT_NULL(aSurfaceTransaction);
    CHECK_NOT_NULL(aSurfaceControl);

    sp<SurfaceControl> surfaceControl = ASurfaceControl_to_SurfaceControl(aSurfaceControl);
    Transaction* transaction = ASurfaceTransaction_to_Transaction(aSurfaceTransaction);

    uint32_t flags = (transparency == ASURFACE_TRANSACTION_TRANSPARENCY_OPAQUE) ?
                      layer_state_t::eLayerOpaque : 0;
    transaction->setFlags(surfaceControl, flags, layer_state_t::eLayerOpaque);
}

void ASurfaceTransaction_setDamageRegion(ASurfaceTransaction* aSurfaceTransaction,
                                         ASurfaceControl* aSurfaceControl,
                                         const ARect rects[], uint32_t count) {
    CHECK_NOT_NULL(aSurfaceTransaction);
    CHECK_NOT_NULL(aSurfaceControl);

    sp<SurfaceControl> surfaceControl = ASurfaceControl_to_SurfaceControl(aSurfaceControl);
    Transaction* transaction = ASurfaceTransaction_to_Transaction(aSurfaceTransaction);

    Region region;
    for (uint32_t i = 0; i < count; ++i) {
        region.orSelf(static_cast<const Rect&>(rects[i]));
    }

    // Hardware composer interprets a DamageRegion with a single Rect of {0,0,0,0} to be an
    // undamaged region and {0,0,-1,-1} to be a fully damaged buffer. This is a confusing
    // distinction for a public api. Instead, default both cases to be a fully damaged buffer.
    if (count == 1 && region.getBounds().isEmpty()) {
        transaction->setSurfaceDamageRegion(surfaceControl, Region::INVALID_REGION);
        return;
    }

    transaction->setSurfaceDamageRegion(surfaceControl, region);
}

void ASurfaceTransaction_setDesiredPresentTime(ASurfaceTransaction* aSurfaceTransaction,
                                         int64_t desiredPresentTime) {
    CHECK_NOT_NULL(aSurfaceTransaction);

    Transaction* transaction = ASurfaceTransaction_to_Transaction(aSurfaceTransaction);

    transaction->setDesiredPresentTime(static_cast<nsecs_t>(desiredPresentTime));
}

void ASurfaceTransaction_setBufferAlpha(ASurfaceTransaction* aSurfaceTransaction,
                                         ASurfaceControl* aSurfaceControl,
                                         float alpha) {
    CHECK_NOT_NULL(aSurfaceTransaction);
    CHECK_NOT_NULL(aSurfaceControl);

    LOG_ALWAYS_FATAL_IF(alpha < 0.0 || alpha > 1.0, "invalid alpha");

    sp<SurfaceControl> surfaceControl = ASurfaceControl_to_SurfaceControl(aSurfaceControl);
    Transaction* transaction = ASurfaceTransaction_to_Transaction(aSurfaceTransaction);

    transaction->setAlpha(surfaceControl, alpha);
}

void ASurfaceTransaction_setBufferDataSpace(ASurfaceTransaction* aSurfaceTransaction,
                                         ASurfaceControl* aSurfaceControl,
                                         ADataSpace aDataSpace) {
    CHECK_NOT_NULL(aSurfaceTransaction);
    CHECK_NOT_NULL(aSurfaceControl);

    sp<SurfaceControl> surfaceControl = ASurfaceControl_to_SurfaceControl(aSurfaceControl);
    if (!isDataSpaceValid(surfaceControl, aDataSpace)) {
        ALOGE("Failed to set buffer dataspace - invalid dataspace");
        return;
    }
    Transaction* transaction = ASurfaceTransaction_to_Transaction(aSurfaceTransaction);
    transaction->setDataspace(surfaceControl, static_cast<ui::Dataspace>(aDataSpace));
}

void ASurfaceTransaction_setHdrMetadata_smpte2086(ASurfaceTransaction* aSurfaceTransaction,
                                                  ASurfaceControl* aSurfaceControl,
                                                  struct AHdrMetadata_smpte2086* metadata) {
    CHECK_NOT_NULL(aSurfaceTransaction);
    CHECK_NOT_NULL(aSurfaceControl);

    sp<SurfaceControl> surfaceControl = ASurfaceControl_to_SurfaceControl(aSurfaceControl);
    Transaction* transaction = ASurfaceTransaction_to_Transaction(aSurfaceTransaction);

    HdrMetadata hdrMetadata;

    if (metadata) {
        hdrMetadata.smpte2086.displayPrimaryRed.x = metadata->displayPrimaryRed.x;
        hdrMetadata.smpte2086.displayPrimaryRed.y = metadata->displayPrimaryRed.y;
        hdrMetadata.smpte2086.displayPrimaryGreen.x = metadata->displayPrimaryGreen.x;
        hdrMetadata.smpte2086.displayPrimaryGreen.y = metadata->displayPrimaryGreen.y;
        hdrMetadata.smpte2086.displayPrimaryBlue.x = metadata->displayPrimaryBlue.x;
        hdrMetadata.smpte2086.displayPrimaryBlue.y = metadata->displayPrimaryBlue.y;
        hdrMetadata.smpte2086.whitePoint.x = metadata->whitePoint.x;
        hdrMetadata.smpte2086.whitePoint.y = metadata->whitePoint.y;
        hdrMetadata.smpte2086.minLuminance = metadata->minLuminance;
        hdrMetadata.smpte2086.maxLuminance = metadata->maxLuminance;

        hdrMetadata.validTypes |= HdrMetadata::SMPTE2086;
    } else {
        hdrMetadata.validTypes &= ~HdrMetadata::SMPTE2086;
    }

    transaction->setHdrMetadata(surfaceControl, hdrMetadata);
}

void ASurfaceTransaction_setHdrMetadata_cta861_3(ASurfaceTransaction* aSurfaceTransaction,
                                                 ASurfaceControl* aSurfaceControl,
                                                 struct AHdrMetadata_cta861_3* metadata) {
    CHECK_NOT_NULL(aSurfaceTransaction);
    CHECK_NOT_NULL(aSurfaceControl);

    sp<SurfaceControl> surfaceControl = ASurfaceControl_to_SurfaceControl(aSurfaceControl);
    Transaction* transaction = ASurfaceTransaction_to_Transaction(aSurfaceTransaction);

    HdrMetadata hdrMetadata;

    if (metadata) {
        hdrMetadata.cta8613.maxContentLightLevel = metadata->maxContentLightLevel;
        hdrMetadata.cta8613.maxFrameAverageLightLevel = metadata->maxFrameAverageLightLevel;

        hdrMetadata.validTypes |= HdrMetadata::CTA861_3;
    } else {
        hdrMetadata.validTypes &= ~HdrMetadata::CTA861_3;
    }

    transaction->setHdrMetadata(surfaceControl, hdrMetadata);
}

void ASurfaceTransaction_setColor(ASurfaceTransaction* aSurfaceTransaction,
                                  ASurfaceControl* aSurfaceControl,
                                  float r, float g, float b, float alpha,
                                  ADataSpace dataspace) {
    CHECK_NOT_NULL(aSurfaceTransaction);
    CHECK_NOT_NULL(aSurfaceControl);

    sp<SurfaceControl> surfaceControl = ASurfaceControl_to_SurfaceControl(aSurfaceControl);
    if (!isDataSpaceValid(surfaceControl, dataspace)) {
        ALOGE("Failed to set buffer dataspace - invalid dataspace");
        return;
    }
    Transaction* transaction = ASurfaceTransaction_to_Transaction(aSurfaceTransaction);

    half3 color;
    color.r = r;
    color.g = g;
    color.b = b;

    transaction->setBackgroundColor(surfaceControl, color, alpha,
                                    static_cast<ui::Dataspace>(dataspace));
}

void ASurfaceTransaction_setFrameRate(ASurfaceTransaction* aSurfaceTransaction,
                                      ASurfaceControl* aSurfaceControl, float frameRate,
                                      int8_t compatibility) {
    ASurfaceTransaction_setFrameRateWithChangeStrategy(
            aSurfaceTransaction, aSurfaceControl, frameRate, compatibility,
            ANATIVEWINDOW_CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS);
}

void ASurfaceTransaction_setFrameRateWithChangeStrategy(ASurfaceTransaction* aSurfaceTransaction,
                                                        ASurfaceControl* aSurfaceControl,
                                                        float frameRate, int8_t compatibility,
                                                        int8_t changeFrameRateStrategy) {
    CHECK_NOT_NULL(aSurfaceTransaction);
    CHECK_NOT_NULL(aSurfaceControl);
    Transaction* transaction = ASurfaceTransaction_to_Transaction(aSurfaceTransaction);
    sp<SurfaceControl> surfaceControl = ASurfaceControl_to_SurfaceControl(aSurfaceControl);
    transaction->setFrameRate(surfaceControl, frameRate, compatibility, changeFrameRateStrategy);
}
