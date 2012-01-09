/*
 * Copyright (C) 2009 The Android Open Source Project
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

#include "rsSignal.h"

using namespace android;
using namespace android::renderscript;


Signal::Signal() {
    mSet = true;
}

Signal::~Signal() {
    pthread_mutex_destroy(&mMutex);
    pthread_cond_destroy(&mCondition);
}

bool Signal::init() {
    int status = pthread_mutex_init(&mMutex, NULL);
    if (status) {
        ALOGE("LocklessFifo mutex init failure");
        return false;
    }

    status = pthread_cond_init(&mCondition, NULL);
    if (status) {
        ALOGE("LocklessFifo condition init failure");
        pthread_mutex_destroy(&mMutex);
        return false;
    }

    return true;
}

void Signal::set() {
    int status;

    status = pthread_mutex_lock(&mMutex);
    if (status) {
        ALOGE("LocklessCommandFifo: error %i locking for set condition.", status);
        return;
    }

    mSet = true;

    status = pthread_cond_signal(&mCondition);
    if (status) {
        ALOGE("LocklessCommandFifo: error %i on set condition.", status);
    }

    status = pthread_mutex_unlock(&mMutex);
    if (status) {
        ALOGE("LocklessCommandFifo: error %i unlocking for set condition.", status);
    }
}

bool Signal::wait(uint64_t timeout) {
    int status;
    bool ret = false;

    status = pthread_mutex_lock(&mMutex);
    if (status) {
        ALOGE("LocklessCommandFifo: error %i locking for condition.", status);
        return false;
    }

    if (!mSet) {
        if (!timeout) {
            status = pthread_cond_wait(&mCondition, &mMutex);
        } else {
#if defined(HAVE_PTHREAD_COND_TIMEDWAIT_RELATIVE)
            status = pthread_cond_timeout_np(&mCondition, &mMutex, timeout / 1000000);
#else
            // This is safe it will just make things less reponsive
            status = pthread_cond_wait(&mCondition, &mMutex);
#endif
        }
    }

    if (!status) {
        mSet = false;
        ret = true;
    } else {
        if (status != ETIMEDOUT) {
            ALOGE("LocklessCommandFifo: error %i waiting for condition.", status);
        }
    }

    status = pthread_mutex_unlock(&mMutex);
    if (status) {
        ALOGE("LocklessCommandFifo: error %i unlocking for condition.", status);
    }

    return ret;
}

