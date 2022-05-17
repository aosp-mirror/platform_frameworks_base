/* //device/libs/android_runtime/android_util_Binder.h
**
** Copyright 2006, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

#ifndef ANDROID_UTIL_BINDER_H
#define ANDROID_UTIL_BINDER_H

#include <binder/IBinder.h>

#include "jni.h"

namespace android {

/**
 * Conversion to Java IBinder Object from C++ IBinder instance.
 *
 * WARNING: this function returns global and local references. This can be
 * figured out using GetObjectRefType. Though, when this function is called
 * from within a Java context, the local ref will automatically be cleaned
 * up. If this is called outside of a Java frame,
 * PushObjectFrame/PopObjectFrame can simulate this automatic cleanup. The
 * platform provides ScopedLocalFrame as an RAII object for this.
 */
extern jobject javaObjectForIBinder(JNIEnv* env, const sp<IBinder>& val);
/** Conversion from Java IBinder Object to C++ IBinder instance. */
extern sp<IBinder> ibinderForJavaObject(JNIEnv* env, jobject obj);

extern jobject newParcelFileDescriptor(JNIEnv* env, jobject fileDesc);

extern void set_dalvik_blockguard_policy(JNIEnv* env, jint strict_policy);

extern void signalExceptionForError(JNIEnv* env, jobject obj, status_t err,
        bool canThrowRemoteException = false, int parcelSize = 0);

// does not take ownership of the exception, aborts if this is an error
void binder_report_exception(JNIEnv* env, jthrowable excep, const char* msg);
}

#endif
