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

#include "jni.h"
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"
#include "gsmamr_enc.h"

// ----------------------------------------------------------------------------

using namespace android;

// Corresponds to max bit rate of 12.2 kbps.
static const int MAX_OUTPUT_BUFFER_SIZE = 32;
static const int FRAME_DURATION_MS = 20;
static const int SAMPLING_RATE_HZ = 8000;
static const int SAMPLES_PER_FRAME = ((SAMPLING_RATE_HZ * FRAME_DURATION_MS) / 1000);
static const int BYTES_PER_SAMPLE = 2;  // Assume 16-bit PCM samples
static const int BYTES_PER_FRAME = (SAMPLES_PER_FRAME * BYTES_PER_SAMPLE);

struct GsmAmrEncoderState {
    GsmAmrEncoderState()
        : mEncState(NULL),
          mSidState(NULL),
          mLastModeUsed(0) {
    }

    ~GsmAmrEncoderState() {}

    void*   mEncState;
    void*   mSidState;
    int32_t mLastModeUsed;
};

static jlong android_media_AmrInputStream_GsmAmrEncoderNew
        (JNIEnv *env, jclass clazz) {
    GsmAmrEncoderState* gae = new GsmAmrEncoderState();
    if (gae == NULL) {
        jniThrowRuntimeException(env, "Out of memory");
    }
    return (jlong)gae;
}

static void android_media_AmrInputStream_GsmAmrEncoderInitialize
        (JNIEnv *env, jclass clazz, jlong gae) {
    GsmAmrEncoderState *state = (GsmAmrEncoderState *) gae;
    int32_t nResult = AMREncodeInit(&state->mEncState, &state->mSidState, false);
    if (nResult != OK) {
        jniThrowExceptionFmt(env, "java/lang/IllegalArgumentException",
                "GsmAmrEncoder initialization failed %d", nResult);
    }
}

static jint android_media_AmrInputStream_GsmAmrEncoderEncode
        (JNIEnv *env, jclass clazz,
         jlong gae, jbyteArray pcm, jint pcmOffset, jbyteArray amr, jint amrOffset) {

    jbyte inBuf[BYTES_PER_FRAME];
    jbyte outBuf[MAX_OUTPUT_BUFFER_SIZE];

    env->GetByteArrayRegion(pcm, pcmOffset, sizeof(inBuf), inBuf);
    GsmAmrEncoderState *state = (GsmAmrEncoderState *) gae;
    int32_t length = AMREncode(state->mEncState, state->mSidState,
                                (Mode) MR122,
                                (int16_t *) inBuf,
                                (unsigned char *) outBuf,
                                (Frame_Type_3GPP*) &state->mLastModeUsed,
                                AMR_TX_WMF);
    if (length < 0) {
        jniThrowExceptionFmt(env, "java/io/IOException",
                "Failed to encode a frame with error code: %d", length);
        return (jint)-1;
    }

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

    return (jint)length;
}

static void android_media_AmrInputStream_GsmAmrEncoderCleanup
        (JNIEnv *env, jclass clazz, jlong gae) {
    GsmAmrEncoderState *state = (GsmAmrEncoderState *) gae;
    AMREncodeExit(&state->mEncState, &state->mSidState);
    state->mEncState = NULL;
    state->mSidState = NULL;
}

static void android_media_AmrInputStream_GsmAmrEncoderDelete
        (JNIEnv *env, jclass clazz, jlong gae) {
    delete (GsmAmrEncoderState*)gae;
}

// ----------------------------------------------------------------------------

static JNINativeMethod gMethods[] = {
    {"GsmAmrEncoderNew",        "()J",        (void*)android_media_AmrInputStream_GsmAmrEncoderNew},
    {"GsmAmrEncoderInitialize", "(J)V",       (void*)android_media_AmrInputStream_GsmAmrEncoderInitialize},
    {"GsmAmrEncoderEncode",     "(J[BI[BI)I", (void*)android_media_AmrInputStream_GsmAmrEncoderEncode},
    {"GsmAmrEncoderCleanup",    "(J)V",       (void*)android_media_AmrInputStream_GsmAmrEncoderCleanup},
    {"GsmAmrEncoderDelete",     "(J)V",       (void*)android_media_AmrInputStream_GsmAmrEncoderDelete},
};


int register_android_media_AmrInputStream(JNIEnv *env)
{
    const char* const kClassPathName = "android/media/AmrInputStream";

    return AndroidRuntime::registerNativeMethods(env,
            kClassPathName, gMethods, NELEM(gMethods));
}
