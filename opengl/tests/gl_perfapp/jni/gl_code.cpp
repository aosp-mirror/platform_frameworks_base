// OpenGL ES 2.0 code

#include <nativehelper/jni.h>
#define LOG_TAG "GLPerf gl_code.cpp"
#include <utils/Log.h>

#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <utils/Timers.h>

#include <stdio.h>
#include <stdlib.h>
#include <math.h>

#include "../../gl_perf/fill_common.cpp"


//////////////////////////

// Width and height of the screen

uint32_t w;
uint32_t h;

// The stateClock starts at zero and increments by 1 every time we draw a frame. It is used to control which phase of the test we are in.

int stateClock;
const int doLoopStates = 2;
const int doSingleTestStates = 2;
bool done;

// Saves the parameters of the test (so we can print them out when we finish the timing.)


int pgm;

void ptSwap() {
}

void doTest() {
    uint32_t testNum = stateClock >> 2;
    int texSize = ((stateClock >> 1) & 0x1) + 1;

    if (testNum >= gFragmentTestCount) {
       ALOGI("done\n");
       if (fOut) {
           fclose(fOut);
           fOut = NULL;
       }
       done = true;
       return;
    }

    // ALOGI("doTest %d %d %d\n", texCount, extraMath, testSubState);

//        for (uint32_t num = 0; num < gFragmentTestCount; num++) {
    doSingleTest(testNum, texSize);
}

extern "C" {
    JNIEXPORT void JNICALL Java_com_android_glperf_GLPerfLib_init(JNIEnv * env, jobject obj,  jint width, jint height);
    JNIEXPORT void JNICALL Java_com_android_glperf_GLPerfLib_step(JNIEnv * env, jobject obj);
};

JNIEXPORT void JNICALL Java_com_android_glperf_GLPerfLib_init(JNIEnv * env, jobject obj,  jint width, jint height)
{
    gWidth = width;
    gHeight = height;
    if (!done) {
            stateClock = 0;
            done = false;
            setupVA();
            genTextures();
            const char* fileName = "/sdcard/glperf.csv";
            if (fOut != NULL) {
                 ALOGI("Closing partially written output.n");
                 fclose(fOut);
                 fOut = NULL;
            }
            ALOGI("Writing to: %s\n",fileName);
            fOut = fopen(fileName, "w");
            if (fOut == NULL) {
                ALOGE("Could not open: %s\n", fileName);
            }

            ALOGI("\nvarColor, texCount, modulate, extraMath, texSize, blend, Mpps, DC60\n");
            if (fOut) fprintf(fOut,"varColor, texCount, modulate, extraMath, texSize, blend, Mpps, DC60\r\n");
    }
}

JNIEXPORT void JNICALL Java_com_android_glperf_GLPerfLib_step(JNIEnv * env, jobject obj)
{
    if (! done) {
        if (stateClock > 0 && ((stateClock & 1) == 0)) {
            //endTimer(100);
        }
        doTest();
        stateClock++;
    } else {
            glClear(GL_DEPTH_BUFFER_BIT | GL_COLOR_BUFFER_BIT);
    }
}
