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

#ifndef ANDROID_IMEDIADEATHNOTIFIER_H
#define ANDROID_IMEDIADEATHNOTIFIER_H

#include <utils/threads.h>
#include <media/IMediaPlayerService.h>
#include <utils/SortedVector.h>

namespace android {

class IMediaDeathNotifier: virtual public RefBase
{
public:
    IMediaDeathNotifier() { addObitRecipient(this); }
    virtual ~IMediaDeathNotifier() { removeObitRecipient(this); }

    virtual void died() = 0;
    static const sp<IMediaPlayerService>& getMediaPlayerService();

private:
    IMediaDeathNotifier &operator=(const IMediaDeathNotifier &);
    IMediaDeathNotifier(const IMediaDeathNotifier &);

    static void addObitRecipient(const wp<IMediaDeathNotifier>& recipient);
    static void removeObitRecipient(const wp<IMediaDeathNotifier>& recipient);

    class DeathNotifier: public IBinder::DeathRecipient
    {
    public:
                DeathNotifier() {}
        virtual ~DeathNotifier();

        virtual void binderDied(const wp<IBinder>& who);
    };

    friend class DeathNotifier;

    static  Mutex                                   sServiceLock;
    static  sp<IMediaPlayerService>                 sMediaPlayerService;
    static  sp<DeathNotifier>                       sDeathNotifier;
    static  SortedVector< wp<IMediaDeathNotifier> > sObitRecipients;
};

}; // namespace android

#endif // ANDROID_IMEDIADEATHNOTIFIER_H
