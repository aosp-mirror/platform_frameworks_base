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
#include <media/IDrm.h>
#include <media/IDrmClient.h>
#include <utils/Errors.h>
#include <utils/RefBase.h>

namespace android {

struct IDrm;

class DrmListener: virtual public RefBase
{
public:
    virtual void notify(DrmPlugin::EventType eventType, int extra,
                        const Parcel *obj) = 0;
};

struct JDrm : public BnDrmClient {
    static bool IsCryptoSchemeSupported(const uint8_t uuid[16], const String8 &mimeType);

    JDrm(JNIEnv *env, jobject thiz, const uint8_t uuid[16]);

    status_t initCheck() const;
    sp<IDrm> getDrm() { return mDrm; }

    void notify(DrmPlugin::EventType, int extra, const Parcel *obj);
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
    static sp<IDrm> MakeDrm(const uint8_t uuid[16]);

    DISALLOW_EVIL_CONSTRUCTORS(JDrm);
};

}  // namespace android

#endif  // _ANDROID_MEDIA_DRM_H_
