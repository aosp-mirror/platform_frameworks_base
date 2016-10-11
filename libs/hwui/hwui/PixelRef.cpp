/*
 * Copyright (C) 2015 The Android Open Source Project
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
#include "PixelRef.h"

#include "Caches.h"

#include <cutils/log.h>
#include <sys/mman.h>
#include <cutils/ashmem.h>

namespace android {

void PixelRef::reconfigure(const SkImageInfo& newInfo, size_t rowBytes, SkColorTable* ctable) {
    if (kIndex_8_SkColorType != newInfo.colorType()) {
        ctable = nullptr;
    }
    mRowBytes = rowBytes;
    if (mColorTable.get() != ctable) {
        mColorTable.reset(ctable);
    }

    // Need to validate the alpha type to filter against the color type
    // to prevent things like a non-opaque RGB565 bitmap
    SkAlphaType alphaType;
    LOG_ALWAYS_FATAL_IF(!SkColorTypeValidateAlphaType(
            newInfo.colorType(), newInfo.alphaType(), &alphaType),
            "Failed to validate alpha type!");

    // Dirty hack is dirty
    // TODO: Figure something out here, Skia's current design makes this
    // really hard to work with. Skia really, really wants immutable objects,
    // but with the nested-ref-count hackery going on that's just not
    // feasible without going insane trying to figure it out
    SkImageInfo* myInfo = const_cast<SkImageInfo*>(&this->info());
    *myInfo = newInfo;
    changeAlphaType(alphaType);

    // Docs say to only call this in the ctor, but we're going to call
    // it anyway even if this isn't always the ctor.
    // TODO: Fix this too as part of the above TODO
    setPreLocked(getStorage(), mRowBytes, mColorTable.get());
}

PixelRef::PixelRef(void* address, size_t size, const SkImageInfo& info, size_t rowBytes, SkColorTable* ctable)
            : SkPixelRef(info)
            , mPixelStorageType(PixelStorageType::Heap) {
    mPixelStorage.heap.address = address;
    mPixelStorage.heap.size = size;
    reconfigure(info, rowBytes, ctable);
}

PixelRef::PixelRef(void* address, void* context, FreeFunc freeFunc,
                const SkImageInfo& info, size_t rowBytes, SkColorTable* ctable)
            : SkPixelRef(info)
            , mPixelStorageType(PixelStorageType::External) {
    mPixelStorage.external.address = address;
    mPixelStorage.external.context = context;
    mPixelStorage.external.freeFunc = freeFunc;
    reconfigure(info, rowBytes, ctable);
}

PixelRef::PixelRef(void* address, int fd, size_t mappedSize,
                const SkImageInfo& info, size_t rowBytes, SkColorTable* ctable)
            : SkPixelRef(info)
            , mPixelStorageType(PixelStorageType::Ashmem) {
    mPixelStorage.ashmem.address = address;
    mPixelStorage.ashmem.fd = fd;
    mPixelStorage.ashmem.size = mappedSize;
    reconfigure(info, rowBytes, ctable);
}

PixelRef::~PixelRef() {
    switch (mPixelStorageType) {
    case PixelStorageType::External:
        mPixelStorage.external.freeFunc(mPixelStorage.external.address,
                mPixelStorage.external.context);
        break;
    case PixelStorageType::Ashmem:
        munmap(mPixelStorage.ashmem.address, mPixelStorage.ashmem.size);
        close(mPixelStorage.ashmem.fd);
        break;
    case PixelStorageType::Heap:
        free(mPixelStorage.heap.address);
        break;
    }

    if (android::uirenderer::Caches::hasInstance()) {
        android::uirenderer::Caches::getInstance().textureCache.releaseTexture(getStableID());
    }
}

bool PixelRef::hasHardwareMipMap() const {
    return mHasHardwareMipMap;
}

void PixelRef::setHasHardwareMipMap(bool hasMipMap) {
    mHasHardwareMipMap = hasMipMap;
}

void* PixelRef::getStorage() const {
    switch (mPixelStorageType) {
    case PixelStorageType::External:
        return mPixelStorage.external.address;
    case PixelStorageType::Ashmem:
        return mPixelStorage.ashmem.address;
    case PixelStorageType::Heap:
        return mPixelStorage.heap.address;
    }
}

bool PixelRef::onNewLockPixels(LockRec* rec) {
    rec->fPixels = getStorage();
    rec->fRowBytes = mRowBytes;
    rec->fColorTable = mColorTable.get();
    return true;
}

size_t PixelRef::getAllocatedSizeInBytes() const {
    return info().getSafeSize(mRowBytes);
}

int PixelRef::getAshmemFd() const {
    switch (mPixelStorageType) {
    case PixelStorageType::Ashmem:
        return mPixelStorage.ashmem.fd;
    default:
        return -1;
    }
}

size_t PixelRef::getAllocationByteCount() const {
    switch (mPixelStorageType) {
    case PixelStorageType::Heap:
        return mPixelStorage.heap.size;
    default:
        return rowBytes() * height();
    }
}

void PixelRef::reconfigure(const SkImageInfo& info) {
    reconfigure(info, info.minRowBytes(), nullptr);
}

void PixelRef::setAlphaType(SkAlphaType alphaType) {
    if (!SkColorTypeValidateAlphaType(info().colorType(), alphaType, &alphaType)) {
        return;
    }

    changeAlphaType(alphaType);
}

void PixelRef::getSkBitmap(SkBitmap* outBitmap) {
    outBitmap->setInfo(info(), rowBytes());
    outBitmap->setPixelRef(this);
    outBitmap->setHasHardwareMipMap(mHasHardwareMipMap);
}

} // namespace android