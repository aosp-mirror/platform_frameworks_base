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

#define LOG_TAG "AmrInputStream"
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
#include "gsmamr_encoder_wrapper.h"


// ----------------------------------------------------------------------------

using namespace android;

// Corresponds to max bit rate of 12.2 kbps.
static const int aMaxOutputBufferSize = 32;

static const int SAMPLES_PER_FRAME = 8000 * 20 / 1000;


//
// helper function to throw an exception
//
static void throwException(JNIEnv *env, const char* ex, const char* fmt, int data) {
    if (jclass cls = env->FindClass(ex)) {
        char msg[1000];
        sprintf(msg, fmt, data);
        env->ThrowNew(cls, msg);
        env->DeleteLocalRef(cls);
    }
}

static jint android_media_AmrInputStream_GsmAmrEncoderNew
        (JNIEnv *env, jclass clazz) {
    CPvGsmAmrEncoder* gae = new CPvGsmAmrEncoder();
    if (gae == NULL) {
        throwException(env, "java/lang/IllegalStateException",
                "new CPvGsmAmrEncoder() failed", 0);
    }
    return (jint)gae;
}

static void android_media_AmrInputStream_GsmAmrEncoderInitialize
        (JNIEnv *env, jclass clazz, jint gae) {
    // set input parameters
    TEncodeProperties encodeProps;
    encodeProps.iInBitsPerSample = 16;
    encodeProps.iInSamplingRate = 8000;
    encodeProps.iInClockRate = 1000;
    encodeProps.iInNumChannels = 1;
    encodeProps.iInInterleaveMode = TEncodeProperties::EINTERLEAVE_LR;
    encodeProps.iMode = CPvGsmAmrEncoder::GSM_AMR_12_2;
    encodeProps.iBitStreamFormatIf2 = false;
    encodeProps.iAudioObjectType = 0;
    encodeProps.iOutSamplingRate = encodeProps.iInSamplingRate;
    encodeProps.iOutNumChannels = encodeProps.iInNumChannels;
    encodeProps.iOutClockRate = encodeProps.iInClockRate;

    if (int rtn = ((CPvGsmAmrEncoder*)gae)->
            InitializeEncoder(aMaxOutputBufferSize, &encodeProps)) {
        throwException(env, "java/lang/IllegalArgumentException",
                "CPvGsmAmrEncoder::InitializeEncoder failed %d", rtn);
    }
}

static jint android_media_AmrInputStream_GsmAmrEncoderEncode
        (JNIEnv *env, jclass clazz,
         jint gae, jbyteArray pcm, jint pcmOffset, jbyteArray amr, jint amrOffset) {

    // set up input stream
    jbyte inBuf[SAMPLES_PER_FRAME*2];
    TInputAudioStream in;
    in.iSampleBuffer = (uint8*)inBuf;
    env->GetByteArrayRegion(pcm, pcmOffset, sizeof(inBuf), inBuf);
    in.iSampleLength = sizeof(inBuf);
    in.iMode = CPvGsmAmrEncoder::GSM_AMR_12_2;
    in.iStartTime = 0;
    in.iStopTime = 0;

    // set up output stream
    jbyte outBuf[aMaxOutputBufferSize];
    int32 sampleFrameSize[1] = { 0 };
    TOutputAudioStream out;
    out.iBitStreamBuffer = (uint8*)outBuf;
    out.iNumSampleFrames = 0;
    out.iSampleFrameSize = sampleFrameSize;
    out.iStartTime = 0;
    out.iStopTime = 0;

    // encode
    if (int rtn = ((CPvGsmAmrEncoder*)gae)->Encode(in, out)) {
        throwException(env, "java/io/IOException", "CPvGsmAmrEncoder::Encode failed %d", rtn);
        return -1;
    }

    // validate one-frame assumption
    if (out.iNumSampleFrames != 1) {
        throwException(env, "java/io/IOException",
                "CPvGsmAmrEncoder::Encode more than one frame returned %d", out.iNumSampleFrames);
        return 0;
    }

    // copy result
    int length = out.iSampleFrameSize[0];

    // The 1st byte of PV AMR frames are WMF (Wireless Multimedia Forum)
    // bitpacked, i.e.;
    //    [P(4) + FT(4)]. Q=1 for good frame, P=padding bit, 0
    // Here we are converting the header to be as specified in Section 5.3 of
    // RFC 3267 (AMR storage format) i.e.
    //    [P(1) + FT(4) + Q(1) + P(2)].
    if (length > 0) {
      outBuf[0] = (outBuf[0] << 3) | 0x4;
    }

    env->SetByteArrayRegion(amr, amrOffset, length, outBuf);

    return length;
}

static void android_media_AmrInputStream_GsmAmrEncoderCleanup
        (JNIEnv *env, jclass clazz, jint gae) {
    if (int rtn = ((CPvGsmAmrEncoder*)gae)->CleanupEncoder()) {
        throwException(env, "java/lang/IllegalStateException",
                "CPvGsmAmrEncoder::CleanupEncoder failed %d", rtn);
    }
}

static void android_media_AmrInputStream_GsmAmrEncoderDelete
        (JNIEnv *env, jclass clazz, jint gae) {
    delete (CPvGsmAmrEncoder*)gae;
}

// ----------------------------------------------------------------------------

static JNINativeMethod gMethods[] = {
    {"GsmAmrEncoderNew",        "()I",        (void*)android_media_AmrInputStream_GsmAmrEncoderNew},
    {"GsmAmrEncoderInitialize", "(I)V",       (void*)android_media_AmrInputStream_GsmAmrEncoderInitialize},
    {"GsmAmrEncoderEncode",     "(I[BI[BI)I", (void*)android_media_AmrInputStream_GsmAmrEncoderEncode},
    {"GsmAmrEncoderCleanup",    "(I)V",       (void*)android_media_AmrInputStream_GsmAmrEncoderCleanup},
    {"GsmAmrEncoderDelete",     "(I)V",       (void*)android_media_AmrInputStream_GsmAmrEncoderDelete},
};


int register_android_media_AmrInputStream(JNIEnv *env)
{
    const char* const kClassPathName = "android/media/AmrInputStream";
    jclass clazz;

    clazz = env->FindClass(kClassPathName);
    if (clazz == NULL) {
        LOGE("Can't find %s", kClassPathName);
        return -1;
    }

    return AndroidRuntime::registerNativeMethods(env,
            kClassPathName, gMethods, NELEM(gMethods));
}


