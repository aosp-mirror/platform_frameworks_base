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

enum {
    // TODO(b/180483929): use reverse jni e.g. android_media_MediaDrm_native_init
    // KEEP IN SYNC with MediaDrm$ErrorCodes in MediaDrm.java!
    JERROR_DRM_UNKNOWN = 0,
    JERROR_DRM_NO_LICENSE = 1,
    JERROR_DRM_LICENSE_EXPIRED = 2,
    JERROR_DRM_RESOURCE_BUSY = 3,
    JERROR_DRM_INSUFFICIENT_OUTPUT_PROTECTION = 4,
    JERROR_DRM_SESSION_NOT_OPENED = 5,
    JERROR_DRM_CANNOT_HANDLE = 6,
    JERROR_DRM_INSUFFICIENT_SECURITY = 7,
    JERROR_DRM_FRAME_TOO_LARGE = 8,
    JERROR_DRM_SESSION_LOST_STATE = 9,
    JERROR_DRM_CERTIFICATE_MALFORMED = 10,
    JERROR_DRM_CERTIFICATE_MISSING = 11,
    JERROR_DRM_CRYPTO_LIBRARY = 12,
    JERROR_DRM_GENERIC_OEM = 13,
    JERROR_DRM_GENERIC_PLUGIN = 14,
    JERROR_DRM_INIT_DATA = 15,
    JERROR_DRM_KEY_NOT_LOADED = 16,
    JERROR_DRM_LICENSE_PARSE = 17,
    JERROR_DRM_LICENSE_POLICY = 18,
    JERROR_DRM_LICENSE_RELEASE = 19,
    JERROR_DRM_LICENSE_REQUEST_REJECTED = 20,
    JERROR_DRM_LICENSE_RESTORE = 21,
    JERROR_DRM_LICENSE_STATE = 22,
    JERROR_DRM_MEDIA_FRAMEWORK = 23,
    JERROR_DRM_PROVISIONING_CERTIFICATE = 24,
    JERROR_DRM_PROVISIONING_CONFIG = 25,
    JERROR_DRM_PROVISIONING_PARSE = 26,
    JERROR_DRM_PROVISIONING_REQUEST_REJECTED = 27,
    JERROR_DRM_PROVISIONING_RETRY = 28,
    JERROR_DRM_RESOURCE_CONTENTION = 29,
    JERROR_DRM_SECURE_STOP_RELEASE = 30,
    JERROR_DRM_STORAGE_READ = 31,
    JERROR_DRM_STORAGE_WRITE = 32,
    JERROR_DRM_ZERO_SUBSAMPLES = 33,
};

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

jint MediaErrorToJavaError(status_t err);

}  // namespace android

#endif  // _ANDROID_MEDIA_DRM_H_
