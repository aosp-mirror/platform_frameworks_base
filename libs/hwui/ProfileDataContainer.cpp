/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include "ProfileDataContainer.h"

#include <errno.h>

#include <cutils/ashmem.h>
#include <log/log.h>

#include <errno.h>
#include <sys/mman.h>

namespace android {
namespace uirenderer {

void ProfileDataContainer::freeData() REQUIRES(mJankDataMutex) {
    if (mIsMapped) {
        munmap(mData, sizeof(ProfileData));
    } else {
        delete mData;
    }
    mIsMapped = false;
    mData = nullptr;
}

void ProfileDataContainer::rotateStorage() {
    std::lock_guard lock(mJankDataMutex);

    // If we are mapped we want to stop using the ashmem backend and switch to malloc
    // We are expecting a switchStorageToAshmem call to follow this, but it's not guaranteed
    // If we aren't sitting on top of ashmem then just do a reset() as it's functionally
    // equivalent do a free, malloc, reset.
    if (mIsMapped) {
        freeData();
        mData = new ProfileData;
    }
    mData->reset();
}

void ProfileDataContainer::switchStorageToAshmem(int ashmemfd) {
    std::lock_guard lock(mJankDataMutex);
    int regionSize = ashmem_get_size_region(ashmemfd);
    if (regionSize < 0) {
        int err = errno;
        ALOGW("Failed to get ashmem region size from fd %d, err %d %s", ashmemfd, err,
              strerror(err));
        return;
    }
    if (regionSize < static_cast<int>(sizeof(ProfileData))) {
        ALOGW("Ashmem region is too small! Received %d, required %u", regionSize,
              static_cast<unsigned int>(sizeof(ProfileData)));
        return;
    }
    ProfileData* newData = reinterpret_cast<ProfileData*>(
            mmap(NULL, sizeof(ProfileData), PROT_READ | PROT_WRITE, MAP_SHARED, ashmemfd, 0));
    if (newData == MAP_FAILED) {
        int err = errno;
        ALOGW("Failed to move profile data to ashmem fd %d, error = %d", ashmemfd, err);
        return;
    }

    if (mData != nullptr) {
        newData->mergeWith(*mData);
    }
    freeData();
    mData = newData;
    mIsMapped = true;
}

} /* namespace uirenderer */
} /* namespace android */
