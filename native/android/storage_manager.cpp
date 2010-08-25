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
#include <utils/Log.h>
#include <utils/RefBase.h>
#include <utils/String8.h>
#include <utils/String16.h>


using namespace android;

struct ObbActionListener : public BnObbActionListener {
private:
    sp<AStorageManager> mStorageManager;

public:
    ObbActionListener(AStorageManager* mgr) :
            mStorageManager(mgr)
    {}

    virtual void onObbResult(const android::String16& filename, const android::String16& state) {
        LOGD("Got obb result (%s, %s)\n", String8(filename).string(), String8(state).string());
    }
};

struct AStorageManager : public RefBase {
protected:
    void* mObbCallback;
    sp<ObbActionListener> mObbActionListener;
    sp<IMountService> mMountService;

public:
    AStorageManager() :
            mObbCallback(NULL)
    {
    }

    bool initialize() {
        sp<IServiceManager> sm = defaultServiceManager();
        if (sm == NULL) {
            LOGE("Couldn't get default ServiceManager\n");
            return false;
        }

        mMountService = interface_cast<IMountService>(sm->getService(String16("mount")));
        if (mMountService == NULL) {
            LOGE("Couldn't get connection to MountService\n");
            return false;
        }

        mObbActionListener = new ObbActionListener(this);

        return true;
    }

    void setObbCallback(void* cb) {
        mObbCallback = cb;
    }

    void mountObb(const char* filename, const char* key) {
        String16 filename16(filename);
        String16 key16(key);
        mMountService->mountObb(filename16, key16, mObbActionListener);
    }

    void unmountObb(const char* filename, const bool force) {
        String16 filename16(filename);
        mMountService->unmountObb(filename16, force);
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

void AStorageManager_setObbCallback(AStorageManager* mgr, void* cb) {
    mgr->setObbCallback(cb);
}

void AStorageManager_mountObb(AStorageManager* mgr, const char* filename, const char* key) {
    mgr->mountObb(filename, key);
}

void AStorageManager_unmountObb(AStorageManager* mgr, const char* filename, const int force) {
    mgr->unmountObb(filename, force != 0);
}

int AStorageManager_isObbMounted(AStorageManager* mgr, const char* filename) {
    return mgr->isObbMounted(filename) != 0;
}

const char* AStorageManager_getMountedObbPath(AStorageManager* mgr, const char* filename) {
    return mgr->getMountedObbPath(filename);
}
