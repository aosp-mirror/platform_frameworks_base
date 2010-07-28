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

#ifndef ANDROID_IEFFECT_H
#define ANDROID_IEFFECT_H

#include <utils/RefBase.h>
#include <binder/IInterface.h>
#include <binder/Parcel.h>
#include <binder/IMemory.h>

namespace android {

class IEffect: public IInterface
{
public:
    DECLARE_META_INTERFACE(Effect);

    virtual status_t enable() = 0;

    virtual status_t disable() = 0;

    virtual status_t command(uint32_t cmdCode,
                             uint32_t cmdSize,
                             void *pCmdData,
                             uint32_t *pReplySize,
                             void *pReplyData) = 0;

    virtual void disconnect() = 0;

    virtual sp<IMemory> getCblk() const = 0;
};

// ----------------------------------------------------------------------------

class BnEffect: public BnInterface<IEffect>
{
public:
    virtual status_t    onTransact( uint32_t code,
                                    const Parcel& data,
                                    Parcel* reply,
                                    uint32_t flags = 0);
};

}; // namespace android

#endif // ANDROID_IEFFECT_H
