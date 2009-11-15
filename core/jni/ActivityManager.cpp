/*
 * Copyright (C) 2006 The Android Open Source Project
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

#include <unistd.h>
#include <android_runtime/ActivityManager.h>
#include <binder/IBinder.h>
#include <binder/IServiceManager.h>
#include <binder/Parcel.h>
#include <utils/String8.h>

namespace android {

const uint32_t OPEN_CONTENT_URI_TRANSACTION = IBinder::FIRST_CALL_TRANSACTION + 4;

// Perform ContentProvider.openFile() on the given URI, returning
// the resulting native file descriptor.  Returns < 0 on error.
int openContentProviderFile(const String16& uri)
{
    int fd = -1;

    sp<IServiceManager> sm = defaultServiceManager();
    sp<IBinder> am = sm->getService(String16("activity"));
    if (am != NULL) {
        Parcel data, reply;
        data.writeInterfaceToken(String16("android.app.IActivityManager"));
        data.writeString16(uri);
        status_t ret = am->transact(OPEN_CONTENT_URI_TRANSACTION, data, &reply);
        if (ret == NO_ERROR) {
            int32_t exceptionCode = reply.readInt32();
            if (!exceptionCode) {
                // Success is indicated here by a nonzero int followed by the fd;
                // failure by a zero int with no data following.
                if (reply.readInt32() != 0) {
                    fd = dup(reply.readFileDescriptor());
                }
            } else {
                // An exception was thrown back; fall through to return failure
                LOGD("openContentUri(%s) caught exception %d\n",
                        String8(uri).string(), exceptionCode);
            }
        }
    }

    return fd;
}

} /* namespace android */
