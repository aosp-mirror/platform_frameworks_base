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

#include "rsMutex.h"

using namespace android;
using namespace android::renderscript;


Mutex::Mutex() {
}

Mutex::~Mutex() {
    pthread_mutex_destroy(&mMutex);
}

bool Mutex::init() {
    int status = pthread_mutex_init(&mMutex, NULL);
    if (status) {
        ALOGE("Mutex::Mutex init failure");
        return false;
    }
    return true;
}

bool Mutex::lock() {
    int status;
    status = pthread_mutex_lock(&mMutex);
    if (status) {
        ALOGE("Mutex: error %i locking.", status);
        return false;
    }
    return true;
}

bool Mutex::unlock() {
    int status;
    status = pthread_mutex_unlock(&mMutex);
    if (status) {
        ALOGE("Mutex error %i unlocking.", status);
        return false;
    }
    return true;
}


