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

#define LOG_TAG "NAsset"
#include <utils/Log.h>

#include <android/asset_manager_jni.h>
#include <android_runtime/android_util_AssetManager.h>
#include <androidfw/Asset.h>
#include <androidfw/AssetDir.h>
#include <androidfw/AssetManager.h>
#include <androidfw/AssetManager2.h>
#include <utils/threads.h>

#include "jni.h"
#include <nativehelper/JNIHelp.h>

using namespace android;

// -------------------- Backing implementation of the public API --------------------

// AAssetManager is actually a secret typedef for an empty base class of AssetManager,
// but AAssetDir and AAsset are actual wrappers for isolation.

// -----
struct AAssetDir {
    std::unique_ptr<AssetDir> mAssetDir;
    size_t mCurFileIndex;
    String8 mCachedFileName;

    explicit AAssetDir(std::unique_ptr<AssetDir> dir) :
        mAssetDir(std::move(dir)), mCurFileIndex(0) { }
};


// -----
struct AAsset {
    std::unique_ptr<Asset> mAsset;

    explicit AAsset(std::unique_ptr<Asset> asset) : mAsset(std::move(asset)) { }
};

// -------------------- Public native C API --------------------

/**
 * Asset Manager functionality
 */
AAssetManager* AAssetManager_fromJava(JNIEnv* env, jobject assetManager)
{
    return (AAssetManager*) env->GetLongField(assetManager, gAssetManagerOffsets.mObject);
}

AAsset* AAssetManager_open(AAssetManager* amgr, const char* filename, int mode)
{
    Asset::AccessMode amMode;
    switch (mode) {
    case AASSET_MODE_UNKNOWN:
        amMode = Asset::ACCESS_UNKNOWN;
        break;
    case AASSET_MODE_RANDOM:
        amMode = Asset::ACCESS_RANDOM;
        break;
    case AASSET_MODE_STREAMING:
        amMode = Asset::ACCESS_STREAMING;
        break;
    case AASSET_MODE_BUFFER:
        amMode = Asset::ACCESS_BUFFER;
        break;
    default:
        return NULL;
    }

    ScopedLock<AssetManager2> locked_mgr(*AssetManagerForNdkAssetManager(amgr));
    std::unique_ptr<Asset> asset = locked_mgr->Open(filename, amMode);
    if (asset == nullptr) {
        return nullptr;
    }
    return new AAsset(std::move(asset));
}

AAssetDir* AAssetManager_openDir(AAssetManager* amgr, const char* dirName)
{
    ScopedLock<AssetManager2> locked_mgr(*AssetManagerForNdkAssetManager(amgr));
    return new AAssetDir(locked_mgr->OpenDir(dirName));
}

/**
 * AssetDir functionality
 */

const char* AAssetDir_getNextFileName(AAssetDir* assetDir)
{
    const char* returnName = NULL;
    size_t index = assetDir->mCurFileIndex;
    const size_t max = assetDir->mAssetDir->getFileCount();

    // Find the next regular file; explicitly don't report directories even if the
    // underlying implementation changes to report them.  At that point we can add
    // a more general iterator to this native interface set if appropriate.
    while ((index < max) && (assetDir->mAssetDir->getFileType(index) != kFileTypeRegular)) {
        index++;
    }

    // still in bounds? then the one at 'index' is the next to be reported; generate
    // the string to return and advance the iterator for next time.
    if (index < max) {
        assetDir->mCachedFileName = assetDir->mAssetDir->getFileName(index);
        returnName = assetDir->mCachedFileName.c_str();
        index++;
    }

    assetDir->mCurFileIndex = index;
    return returnName;
}

void AAssetDir_rewind(AAssetDir* assetDir)
{
    assetDir->mCurFileIndex = 0;
}

const char* AAssetDir_getFileName(AAssetDir* assetDir, int index)
{
    assetDir->mCachedFileName = assetDir->mAssetDir->getFileName(index);
    return assetDir->mCachedFileName.c_str();
}

void AAssetDir_close(AAssetDir* assetDir)
{
    delete assetDir;
}

/**
 * Asset functionality
 */

int AAsset_read(AAsset* asset, void* buf, size_t count)
{
    return asset->mAsset->read(buf, (size_t)count);
}

off_t AAsset_seek(AAsset* asset, off_t offset, int whence)
{
    return asset->mAsset->seek(offset, whence);
}

off64_t AAsset_seek64(AAsset* asset, off64_t offset, int whence)
{
    return asset->mAsset->seek(offset, whence);
}

void AAsset_close(AAsset* asset)
{
    asset->mAsset->close();
    delete asset;
}

const void* AAsset_getBuffer(AAsset* asset)
{
    return asset->mAsset->getBuffer(false);
}

off_t AAsset_getLength(AAsset* asset)
{
    return asset->mAsset->getLength();
}

off64_t AAsset_getLength64(AAsset* asset)
{
    return asset->mAsset->getLength();
}

off_t AAsset_getRemainingLength(AAsset* asset)
{
    return asset->mAsset->getRemainingLength();
}

off64_t AAsset_getRemainingLength64(AAsset* asset)
{
    return asset->mAsset->getRemainingLength();
}

int AAsset_openFileDescriptor(AAsset* asset, off_t* outStart, off_t* outLength)
{
    off64_t outStart64, outLength64;

    int ret = asset->mAsset->openFileDescriptor(&outStart64, &outLength64);

    *outStart = off_t(outStart64);
    *outLength = off_t(outLength64);
    return ret;
}

int AAsset_openFileDescriptor64(AAsset* asset, off64_t* outStart, off64_t* outLength)
{
    return asset->mAsset->openFileDescriptor(outStart, outLength);
}

int AAsset_isAllocated(AAsset* asset)
{
    return asset->mAsset->isAllocated() ? 1 : 0;
}
