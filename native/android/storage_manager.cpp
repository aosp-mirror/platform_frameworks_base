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

#define LOG_TAG "NStorage"

#include <android/storage_manager.h>
#include <storage/IMountService.h>
#include <storage/ObbInfo.h>

#include <androidfw/ObbFile.h>
#include <binder/Binder.h>
#include <binder/IServiceManager.h>
#include <cutils/atomic.h>
#include <utils/Log.h>
#include <utils/RefBase.h>
#include <utils/String8.h>
#include <utils/String16.h>
#include <utils/Vector.h>
#include <utils/threads.h>

using namespace android;

struct ObbActionListener : public BnObbActionListener {
private:
    sp<AStorageManager> mStorageManager;

public:
    explicit ObbActionListener(AStorageManager* mgr) :
            mStorageManager(mgr)
    {}

    virtual void onObbResult(const android::String16& filename, const int32_t nonce,
            const int32_t state);
};

class ObbCallback {
public:
    ObbCallback(int32_t _nonce, AStorageManager_obbCallbackFunc _cb, void* _data)
            : nonce(_nonce)
            , cb(_cb)
            , data(_data)
    {}

    int32_t nonce;
    AStorageManager_obbCallbackFunc cb;
    void* data;
};

struct AStorageManager : public RefBase {
protected:
    Mutex mCallbackLock;
    Vector<ObbCallback*> mCallbacks;
    volatile int32_t mNextNonce;
    sp<ObbActionListener> mObbActionListener;
    sp<IMountService> mMountService;

    int32_t getNextNonce() {
        return android_atomic_inc(&mNextNonce);
    }

    ObbCallback* registerObbCallback(AStorageManager_obbCallbackFunc func, void* data) {
        ObbCallback* cb = new ObbCallback(getNextNonce(), func, data);
        {
            AutoMutex _l(mCallbackLock);
            mCallbacks.push(cb);
        }
        return cb;
    }

    ObbInfo* getObbInfo(char* canonicalPath) {
        sp<ObbFile> obbFile = new ObbFile();
        if (!obbFile->readFrom(canonicalPath)) {
            return nullptr;
        }

        String16 fileName(obbFile->getFileName());
        String16 packageName(obbFile->getPackageName());
        size_t length;
        const unsigned char* salt = obbFile->getSalt(&length);
        return new ObbInfo(fileName, packageName,
                obbFile->getVersion(), obbFile->getFlags(), length, salt);
    }

public:
    AStorageManager()
    {
    }

    bool initialize() {
        sp<IServiceManager> sm = defaultServiceManager();
        if (sm == NULL) {
            ALOGE("Couldn't get default ServiceManager\n");
            return false;
        }

        mMountService = interface_cast<IMountService>(sm->getService(String16("mount")));
        if (mMountService == NULL) {
            ALOGE("Couldn't get connection to MountService\n");
            return false;
        }

        mObbActionListener = new ObbActionListener(this);

        return true;
    }

    void fireCallback(const char* filename, const int32_t nonce, const int32_t state) {
        ObbCallback* target = NULL;
        {
            AutoMutex _l(mCallbackLock);
            int N = mCallbacks.size();
            for (int i = 0; i < N; i++) {
                ObbCallback* cb = mCallbacks.editItemAt(i);
                if (cb->nonce == nonce) {
                    target = cb;
                    mCallbacks.removeAt(i);
                    break;
                }
            }
        }

        if (target != NULL) {
            target->cb(filename, state, target->data);
            delete target;
        } else {
            ALOGI("Didn't find the callback handler for: %s\n", filename);
        }
    }

    void mountObb(const char* rawPath, const char* key, AStorageManager_obbCallbackFunc func,
            void* data) {
        // Resolve path before sending to MountService
        char canonicalPath[PATH_MAX];
        if (realpath(rawPath, canonicalPath) == NULL) {
            ALOGE("mountObb failed to resolve path %s: %s", rawPath, strerror(errno));
            return;
        }

        sp<ObbInfo> obbInfo = getObbInfo(canonicalPath);
        if (obbInfo == nullptr) {
            ALOGE("Couldn't get obb info for %s: %s", canonicalPath, strerror(errno));
            return;
        }

        ObbCallback* cb = registerObbCallback(func, data);
        String16 rawPath16(rawPath);
        String16 canonicalPath16(canonicalPath);
        String16 key16(key);
        mMountService->mountObb(rawPath16, canonicalPath16, key16, mObbActionListener,
                cb->nonce, obbInfo);
    }

    void unmountObb(const char* filename, const bool force, AStorageManager_obbCallbackFunc func, void* data) {
        ObbCallback* cb = registerObbCallback(func, data);
        String16 filename16(filename);
        mMountService->unmountObb(filename16, force, mObbActionListener, cb->nonce);
    }

    int isObbMounted(const char* filename) {
        String16 filename16(filename);
        return mMountService->isObbMounted(filename16);
    }

    const char* getMountedObbPath(const char* filename) {
        String16 filename16(filename);
        String16 path16;
        if (mMountService->getMountedObbPath(filename16, path16)) {
            return String8(path16).string();
        } else {
            return NULL;
        }
    }
};

void ObbActionListener::onObbResult(const android::String16& filename, const int32_t nonce, const int32_t state) {
    mStorageManager->fireCallback(String8(filename).string(), nonce, state);
}


AStorageManager* AStorageManager_new() {
    sp<AStorageManager> mgr = new AStorageManager();
    if (mgr == NULL || !mgr->initialize()) {
        return NULL;
    }
    mgr->incStrong((void*)AStorageManager_new);
    return static_cast<AStorageManager*>(mgr.get());
}

void AStorageManager_delete(AStorageManager* mgr) {
    if (mgr) {
        mgr->decStrong((void*)AStorageManager_new);
    }
}

void AStorageManager_mountObb(AStorageManager* mgr, const char* filename, const char* key,
        AStorageManager_obbCallbackFunc cb, void* data) {
    mgr->mountObb(filename, key, cb, data);
}

void AStorageManager_unmountObb(AStorageManager* mgr, const char* filename, const int force,
        AStorageManager_obbCallbackFunc cb, void* data) {
    mgr->unmountObb(filename, force != 0, cb, data);
}

int AStorageManager_isObbMounted(AStorageManager* mgr, const char* filename) {
    return mgr->isObbMounted(filename) != 0;
}

const char* AStorageManager_getMountedObbPath(AStorageManager* mgr, const char* filename) {
    return mgr->getMountedObbPath(filename);
}
