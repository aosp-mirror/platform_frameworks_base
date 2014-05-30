/*
 * Copyright (C) 2014 The Android Open Source Project
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

#define LOG_TAG "Camera2-Legacy-PerfMeasurement-JNI"
#include <utils/Log.h>
#include <utils/Errors.h>
#include <utils/Trace.h>
#include <utils/Vector.h>

#include "jni.h"
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"

#include <ui/GraphicBuffer.h>
#include <system/window.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

using namespace android;

// fully-qualified class name
#define PERF_MEASUREMENT_CLASS_NAME "android/hardware/camera2/legacy/PerfMeasurement"

/** GL utility methods copied from com_google_android_gles_jni_GLImpl.cpp */

// Check if the extension at the head of pExtensions is pExtension. Note that pExtensions is
// terminated by either 0 or space, while pExtension is terminated by 0.

static bool
extensionEqual(const GLubyte* pExtensions, const GLubyte* pExtension) {
    while (true) {
        char a = *pExtensions++;
        char b = *pExtension++;
        bool aEnd = a == '\0' || a == ' ';
        bool bEnd = b == '\0';
        if (aEnd || bEnd) {
            return aEnd == bEnd;
        }
        if (a != b) {
            return false;
        }
    }
}

static const GLubyte*
nextExtension(const GLubyte* pExtensions) {
    while (true) {
        char a = *pExtensions++;
        if (a == '\0') {
            return pExtensions-1;
        } else if ( a == ' ') {
            return pExtensions;
        }
    }
}

static bool
checkForExtension(const GLubyte* pExtensions, const GLubyte* pExtension) {
    for (; *pExtensions != '\0'; pExtensions = nextExtension(pExtensions)) {
        if (extensionEqual(pExtensions, pExtension)) {
            return true;
        }
    }
    return false;
}

/** End copied GL utility methods */

bool checkGlError(JNIEnv* env) {
    int error;
    if ((error = glGetError()) != GL_NO_ERROR) {
        jniThrowExceptionFmt(env, "java/lang/IllegalStateException",
                "GLES20 error: 0x%d", error);
        return true;
    }
    return false;
}

/**
 * Asynchronous low-overhead GL performance measurement using
 * http://www.khronos.org/registry/gles/extensions/EXT/EXT_disjoint_timer_query.txt
 *
 * Measures the duration of GPU processing for a set of GL commands, delivering
 * the measurement asynchronously once processing completes.
 *
 * All calls must come from a single thread with a valid GL context active.
 **/
class PerfMeasurementContext {
  private:
    Vector<GLuint> mTimingQueries;
    size_t mTimingStartIndex;
    size_t mTimingEndIndex;
    size_t mTimingQueryIndex;
    size_t mFreeQueries;

    bool mInitDone;
  public:

    /**
     * maxQueryCount should be a conservative estimate of how many query objects
     * will be active at once, which is a function of the GPU's level of
     * pipelining and the frequency of queries.
     */
    PerfMeasurementContext(size_t maxQueryCount):
            mTimingStartIndex(0),
            mTimingEndIndex(0),
            mTimingQueryIndex(0) {
        mTimingQueries.resize(maxQueryCount);
        mFreeQueries = maxQueryCount;
        mInitDone = false;
    }

    int getMaxQueryCount() {
        return mTimingQueries.size();
    }

    /**
     * Start a measurement period using the next available query object.
     * Returns INVALID_OPERATION if called multiple times in a row,
     * and BAD_VALUE if no more query objects are available.
     */
    int startGlTimer() {
        // Lazy init of queries to avoid needing GL context during construction
        if (!mInitDone) {
            glGenQueriesEXT(mTimingQueries.size(), mTimingQueries.editArray());
            mInitDone = true;
        }

        if (mTimingEndIndex != mTimingStartIndex) {
            return INVALID_OPERATION;
        }

        if (mFreeQueries == 0) {
            return BAD_VALUE;
        }

        glBeginQueryEXT(GL_TIME_ELAPSED_EXT, mTimingQueries[mTimingStartIndex]);

        mTimingStartIndex = (mTimingStartIndex + 1) % mTimingQueries.size();
        mFreeQueries--;

        return OK;
    }

    /**
     * Finish the current measurement period
     * Returns INVALID_OPERATION if called before any startGLTimer calls
     * or if called multiple times in a row.
     */
    int stopGlTimer() {
        size_t nextEndIndex = (mTimingEndIndex + 1) % mTimingQueries.size();
        if (nextEndIndex != mTimingStartIndex) {
            return INVALID_OPERATION;
        }
        glEndQueryEXT(GL_TIME_ELAPSED_EXT);

        mTimingEndIndex = nextEndIndex;

        return OK;
    }

    static const nsecs_t NO_DURATION_YET = -1L;
    static const nsecs_t FAILED_MEASUREMENT = -2L;

    /**
     * Get the next available duration measurement.
     *
     * Returns NO_DURATION_YET if no new measurement is available,
     * and FAILED_MEASUREMENT if an error occurred during the next
     * measurement period.
     *
     * Otherwise returns a positive number of nanoseconds measuring the
     * duration of the oldest completed query.
     */
    nsecs_t getNextGlDuration() {
        if (!mInitDone) {
            // No start/stop called yet
            return NO_DURATION_YET;
        }

        GLint available;
        glGetQueryObjectivEXT(mTimingQueries[mTimingQueryIndex],
                GL_QUERY_RESULT_AVAILABLE_EXT, &available);
        if (!available) {
            return NO_DURATION_YET;
        }

        GLint64 duration = FAILED_MEASUREMENT;
        GLint disjointOccurred;
        glGetIntegerv(GL_GPU_DISJOINT_EXT, &disjointOccurred);

        if (!disjointOccurred) {
            glGetQueryObjecti64vEXT(mTimingQueries[mTimingQueryIndex],
                    GL_QUERY_RESULT_EXT,
                    &duration);
        }

        mTimingQueryIndex = (mTimingQueryIndex + 1) % mTimingQueries.size();
        mFreeQueries++;

        return static_cast<nsecs_t>(duration);
    }

    static bool isMeasurementSupported() {
        const GLubyte* extensions = glGetString(GL_EXTENSIONS);
        return checkForExtension(extensions,
                reinterpret_cast<const GLubyte*>("GL_EXT_disjoint_timer_query"));
    }

};

PerfMeasurementContext* getContext(jlong context) {
    return reinterpret_cast<PerfMeasurementContext*>(context);
}

extern "C" {

static jlong PerfMeasurement_nativeCreateContext(JNIEnv* env, jobject thiz,
        jint maxQueryCount) {
    PerfMeasurementContext *context = new PerfMeasurementContext(maxQueryCount);
    return reinterpret_cast<jlong>(context);
}

static void PerfMeasurement_nativeDeleteContext(JNIEnv* env, jobject thiz,
        jlong contextHandle) {
    PerfMeasurementContext *context = getContext(contextHandle);
    delete(context);
}

static jboolean PerfMeasurement_nativeQuerySupport(JNIEnv* env, jobject thiz) {
    bool supported = PerfMeasurementContext::isMeasurementSupported();
    checkGlError(env);
    return static_cast<jboolean>(supported);
}

static void PerfMeasurement_nativeStartGlTimer(JNIEnv* env, jobject thiz,
        jlong contextHandle) {

    PerfMeasurementContext *context = getContext(contextHandle);
    status_t err = context->startGlTimer();
    if (err != OK) {
        switch (err) {
            case INVALID_OPERATION:
                jniThrowExceptionFmt(env, "java/lang/IllegalStateException",
                        "Mismatched start/end GL timing calls");
                return;
            case BAD_VALUE:
                jniThrowExceptionFmt(env, "java/lang/IllegalStateException",
                        "Too many timing queries in progress, max %d",
                        context->getMaxQueryCount());
                return;
            default:
                jniThrowExceptionFmt(env, "java/lang/IllegalStateException",
                        "Unknown error starting GL timing");
                return;
        }
    }
    checkGlError(env);
}

static void PerfMeasurement_nativeStopGlTimer(JNIEnv* env, jobject thiz,
            jlong contextHandle) {

    PerfMeasurementContext *context = getContext(contextHandle);
    status_t err = context->stopGlTimer();
    if (err != OK) {
        switch (err) {
            case INVALID_OPERATION:
                jniThrowExceptionFmt(env, "java/lang/IllegalStateException",
                        "Mismatched start/end GL timing calls");
                return;
            default:
                jniThrowExceptionFmt(env, "java/lang/IllegalStateException",
                        "Unknown error ending GL timing");
                return;
        }
    }
    checkGlError(env);
}

static jlong PerfMeasurement_nativeGetNextGlDuration(JNIEnv* env,
        jobject thiz, jlong contextHandle) {
    PerfMeasurementContext *context = getContext(contextHandle);
    nsecs_t duration = context->getNextGlDuration();

    checkGlError(env);
    return static_cast<jlong>(duration);
}

} // extern "C"

static JNINativeMethod gPerfMeasurementMethods[] = {
    { "nativeCreateContext",
      "(I)J",
      (jlong *)PerfMeasurement_nativeCreateContext },
    { "nativeDeleteContext",
      "(J)V",
      (void *)PerfMeasurement_nativeDeleteContext },
    { "nativeQuerySupport",
      "()Z",
      (jboolean *)PerfMeasurement_nativeQuerySupport },
    { "nativeStartGlTimer",
      "(J)V",
      (void *)PerfMeasurement_nativeStartGlTimer },
    { "nativeStopGlTimer",
      "(J)V",
      (void *)PerfMeasurement_nativeStopGlTimer },
    { "nativeGetNextGlDuration",
      "(J)J",
      (jlong *)PerfMeasurement_nativeGetNextGlDuration }
};


// Get all the required offsets in java class and register native functions
int register_android_hardware_camera2_legacy_PerfMeasurement(JNIEnv* env)
{
    // Register native functions
    return AndroidRuntime::registerNativeMethods(env,
            PERF_MEASUREMENT_CLASS_NAME,
            gPerfMeasurementMethods,
            NELEM(gPerfMeasurementMethods));
}
