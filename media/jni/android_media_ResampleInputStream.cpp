/*
**
** Copyright 2007, The Android Open Source Project
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

#define LOG_TAG "ResampleInputStream"
#include "utils/Log.h"

#include <media/mediarecorder.h>
#include <stdio.h>
#include <assert.h>
#include <limits.h>
#include <unistd.h>
#include <fcntl.h>
#include <utils/threads.h>

#include "jni.h"
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"


// ----------------------------------------------------------------------------

using namespace android;


#define FIR_COEF(coef) (short)(0x10000 * coef)
static const short fir21[] = {
    FIR_COEF(-0.006965742326),
    FIR_COEF(-0.008428945737),
    FIR_COEF( 0.004241280174),
    FIR_COEF( 0.022141096893),
    FIR_COEF( 0.018765669437),
    FIR_COEF(-0.009871891152),
    FIR_COEF(-0.024842433247),
    FIR_COEF( 0.006121772058),
    FIR_COEF( 0.045890841611),
    FIR_COEF( 0.021573503509),
    FIR_COEF(-0.059681984668),
    FIR_COEF(-0.076036275138),
    FIR_COEF( 0.072405390275),
    FIR_COEF( 0.308255674582),
    FIR_COEF( 0.424321210495),
    FIR_COEF( 0.308255674582),
    FIR_COEF( 0.072405390275),
    FIR_COEF(-0.076036275138),
    FIR_COEF(-0.059681984668),
    FIR_COEF( 0.021573503509),
    FIR_COEF( 0.045890841611),
    FIR_COEF( 0.006121772058),
    FIR_COEF(-0.024842433247),
    FIR_COEF(-0.009871891152),
    FIR_COEF( 0.018765669437),
    FIR_COEF( 0.022141096893),
    FIR_COEF( 0.004241280174),
    FIR_COEF(-0.008428945737),
    FIR_COEF(-0.006965742326)
};
static const int nFir21 = sizeof(fir21) / sizeof(fir21[0]);

static const int BUF_SIZE = 2048;


static void android_media_ResampleInputStream_fir21(JNIEnv *env, jclass /* clazz */,
         jbyteArray jIn,  jint jInOffset,
         jbyteArray jOut, jint jOutOffset,
         jint jNpoints) {

    // safety first!
    if (nFir21 + jNpoints * 2 > BUF_SIZE) {
        jniThrowExceptionFmt(env, "java/lang/IllegalArgumentException",
                "FIR+data too long %d", nFir21 + jNpoints);
        return;
    }

    // get input data
    short in[BUF_SIZE];
    env->GetByteArrayRegion(jIn, jInOffset, (jNpoints * 2 + nFir21 - 1) * 2, (jbyte*)in);

    // compute filter
    short out[BUF_SIZE];
    for (int i = 0; i < jNpoints; i++) {
        long sum = 0;
        const short* firp = &fir21[0];
        const short* inp = &in[i * 2];
        for (int n = nFir21; --n >= 0; ) {
            sum += ((long)*firp++) * ((long)*inp++);
        }
        out[i] = (short)(sum >> 16);
    }

    // save new values
    env->SetByteArrayRegion(jOut, jOutOffset, jNpoints * 2, (jbyte*)out);
}

// ----------------------------------------------------------------------------

static const JNINativeMethod gMethods[] = {
    {"fir21", "([BI[BII)V", (void*)android_media_ResampleInputStream_fir21},
};


int register_android_media_ResampleInputStream(JNIEnv *env)
{
    const char* const kClassPathName = "android/media/ResampleInputStream";

    return AndroidRuntime::registerNativeMethods(env,
            kClassPathName, gMethods, NELEM(gMethods));
}
