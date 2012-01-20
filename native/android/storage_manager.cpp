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

#include <binder/Binder.h>
#include <binder/IServiceManager.h>
#include <utils/Atomic.h>
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
    ObbActionListener(AStorageManager* mgr) :
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

    void mountObb(const char* filename, const char* key, AStorageManager_obbCallbackFunc func, void* data) {
        ObbCallback* cb = registerObbCallback(func, data);
        String16 filename16(filename);
        String16 key16(key);
        mMountService->mountObb(filename16, key16, mObbActionListener, cb->nonce);
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
