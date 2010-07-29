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

#ifndef __IDRM_SERVICE_LISTENER_H__
#define __IDRM_SERVICE_LISTENER_H__

#include <utils/RefBase.h>
#include <binder/IInterface.h>
#include <binder/Parcel.h>

namespace android {

class DrmInfoEvent;

/**
 * This is the interface class for DRM service listener.
 *
 */
class IDrmServiceListener : public IInterface
{
public:
    enum {
        NOTIFY = IBinder::FIRST_CALL_TRANSACTION,
    };

public:
    DECLARE_META_INTERFACE(DrmServiceListener);

public:
    virtual status_t notify(const DrmInfoEvent& event) = 0;
};

/**
 * This is the Binder implementation class for DRM service listener.
 */
class BpDrmServiceListener: public BpInterface<IDrmServiceListener>
{
public:
    BpDrmServiceListener(const sp<IBinder>& impl)
            : BpInterface<IDrmServiceListener>(impl) {}

    virtual status_t notify(const DrmInfoEvent& event);
};

/**
 * This is the Binder implementation class for DRM service listener.
 */
class BnDrmServiceListener: public BnInterface<IDrmServiceListener>
{
public:
    virtual status_t onTransact(
            uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags = 0);
};

};

#endif /* __IDRM_SERVICE_LISTENER_H__ */

