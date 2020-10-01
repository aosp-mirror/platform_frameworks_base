/*
 * Copyright 2013, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef _ANDROID_MEDIA_DRM_H_
#define _ANDROID_MEDIA_DRM_H_

#include "jni.h"

#include <media/stagefright/foundation/ABase.h>
#include <mediadrm/IDrm.h>
#include <mediadrm/IDrmClient.h>
#include <hidl/HidlSupport.h>
#include <utils/Errors.h>
#include <utils/RefBase.h>

namespace {

struct ListenerArgs {
    jbyteArray jSessionId;
    jbyteArray jData;
    jlong jExpirationTime;
    jobject jKeyStatusList;
    jboolean jHasNewUsableKey;
};

}

namespace android {

class DrmListener: virtual public RefBase
{
public:
    virtual void notify(DrmPlugin::EventType eventType, int extra,
                        const ListenerArgs *args) = 0;
};

struct JDrm : public IDrmClient {
    static status_t IsCryptoSchemeSupported(const uint8_t uuid[16],
                                            const String8 &mimeType,
                                            DrmPlugin::SecurityLevel level,
                                            bool *isSupported);

    JDrm(JNIEnv *env, jobject thiz, const uint8_t uuid[16], const String8 &appPackageName);

    status_t initCheck() const;
    sp<IDrm> getDrm() { return mDrm; }

    void sendEvent(
            DrmPlugin::EventType eventType,
            const hardware::hidl_vec<uint8_t> &sessionId,
            const hardware::hidl_vec<uint8_t> &data) override;

    void sendExpirationUpdate(
            const hardware::hidl_vec<uint8_t> &sessionId,
            int64_t expiryTimeInMS) override;

    void sendKeysChange(
            const hardware::hidl_vec<uint8_t> &sessionId,
            const std::vector<DrmKeyStatus> &keyStatusList,
            bool hasNewUsableKey) override;

    void sendSessionLostState(
            const hardware::hidl_vec<uint8_t> &sessionId) override;

    status_t setListener(const sp<DrmListener>& listener);

    void disconnect();

protected:
    virtual ~JDrm();

private:
    jweak mObject;
    sp<IDrm> mDrm;

    sp<DrmListener> mListener;
    Mutex mNotifyLock;
    Mutex mLock;

    static sp<IDrm> MakeDrm();
    static sp<IDrm> MakeDrm(const uint8_t uuid[16], const String8 &appPackageName);

    void notify(DrmPlugin::EventType, int extra, const ListenerArgs *args);

    DISALLOW_EVIL_CONSTRUCTORS(JDrm);
};

}  // namespace android

#endif  // _ANDROID_MEDIA_DRM_H_
