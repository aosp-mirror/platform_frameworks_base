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

#include <android/native_window.h>
#include <android/surface_control.h>

#include <gui/Surface.h>
#include <gui/SurfaceComposerClient.h>
#include <gui/SurfaceControl.h>

using namespace android;

using Transaction = SurfaceComposerClient::Transaction;

#define CHECK_NOT_NULL(name) \
    LOG_ALWAYS_FATAL_IF(name == nullptr, "nullptr passed as " #name " argument");

#define CHECK_VALID_RECT(name)                                     \
    LOG_ALWAYS_FATAL_IF(!static_cast<const Rect&>(name).isValid(), \
                        "invalid arg passed as " #name " argument");

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

    uint32_t flags = ISurfaceComposerClient::eFXSurfaceBufferState;
    sp<SurfaceControl> surfaceControl =
            client->createWithSurfaceParent(String8(debug_name), 0 /* width */, 0 /* height */,
                                            // Format is only relevant for buffer queue layers.
                                            PIXEL_FORMAT_UNKNOWN /* format */, flags,
                                            static_cast<Surface*>(window));
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
                                  surfaceControlParent);
    if (!surfaceControl) {
        return nullptr;
    }

    SurfaceControl_acquire(surfaceControl.get());
    return reinterpret_cast<ASurfaceControl*>(surfaceControl.get());
}

void ASurfaceControl_destroy(ASurfaceControl* aSurfaceControl) {
    sp<SurfaceControl> surfaceControl = ASurfaceControl_to_SurfaceControl(aSurfaceControl);

    Transaction().reparent(surfaceControl, nullptr).apply();
    SurfaceControl_release(surfaceControl.get());
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

void ASurfaceTransaction_setOnComplete(ASurfaceTransaction* aSurfaceTransaction, void* context,
                                       ASurfaceTransaction_OnComplete func) {
    CHECK_NOT_NULL(aSurfaceTransaction);
    CHECK_NOT_NULL(context);
    CHECK_NOT_NULL(func);

    TransactionCompletedCallbackTakesContext callback = [func](void* callback_context,
                                                               const TransactionStats& stats) {
        int fence = (stats.presentFence) ? stats.presentFence->dup() : -1;
        (*func)(callback_context, fence);
    };

    Transaction* transaction = ASurfaceTransaction_to_Transaction(aSurfaceTransaction);

    transaction->addTransactionCompletedCallback(callback, context);
}

void ASurfaceTransaction_setVisibility(ASurfaceTransaction* aSurfaceTransaction, ASurfaceControl* aSurfaceControl,
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

void ASurfaceTransaction_setZOrder(ASurfaceTransaction* aSurfaceTransaction, ASurfaceControl* aSurfaceControl,
                                   int32_t z_order) {
    CHECK_NOT_NULL(aSurfaceTransaction);
    CHECK_NOT_NULL(aSurfaceControl);

    sp<SurfaceControl> surfaceControl = ASurfaceControl_to_SurfaceControl(aSurfaceControl);
    Transaction* transaction = ASurfaceTransaction_to_Transaction(aSurfaceTransaction);

    transaction->setLayer(surfaceControl, z_order);
}

void ASurfaceTransaction_setBuffer(ASurfaceTransaction* aSurfaceTransaction, ASurfaceControl* aSurfaceControl,
                                   AHardwareBuffer* buffer, int fence_fd) {
    CHECK_NOT_NULL(aSurfaceTransaction);
    CHECK_NOT_NULL(aSurfaceControl);

    sp<SurfaceControl> surfaceControl = ASurfaceControl_to_SurfaceControl(aSurfaceControl);
    Transaction* transaction = ASurfaceTransaction_to_Transaction(aSurfaceTransaction);

    sp<GraphicBuffer> graphic_buffer(reinterpret_cast<GraphicBuffer*>(buffer));

    transaction->setBuffer(surfaceControl, graphic_buffer);
    if (fence_fd != -1) {
        sp<Fence> fence = new Fence(fence_fd);
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

void ASurfaceTransaction_setDamageRegion(ASurfaceTransaction* aSurfaceTransaction, ASurfaceControl* aSurfaceControl,
                                         const ARect rects[], uint32_t count) {
    CHECK_NOT_NULL(aSurfaceTransaction);
    CHECK_NOT_NULL(aSurfaceControl);

    sp<SurfaceControl> surfaceControl = ASurfaceControl_to_SurfaceControl(aSurfaceControl);
    Transaction* transaction = ASurfaceTransaction_to_Transaction(aSurfaceTransaction);

    Region region;
    for (uint32_t i = 0; i < count; ++i) {
        region.merge(static_cast<const Rect&>(rects[i]));
    }

    transaction->setSurfaceDamageRegion(surfaceControl, region);
}
