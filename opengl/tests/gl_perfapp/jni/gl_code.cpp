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

char saveBuf[1024];


int pgm;

void ptSwap() {
}

static void doSingleTest(uint32_t w, uint32_t h,
                         bool useVarColor,
                         int texCount,
                         bool modulateFirstTex,
                         int extraMath,
                         int tex0, int tex1) {
    int doSingleTestState = (stateClock / doLoopStates) % doSingleTestStates;
    // LOGI("doSingleTest %d\n", doSingleTestState);
    switch (doSingleTestState) {
	case 0: {
	    char *pgmTxt = genShader(useVarColor, texCount, modulateFirstTex, extraMath);
	    pgm = createProgram(gVertexShader, pgmTxt);
	    if (!pgm) {
		LOGE("error running test\n");
		return;
	    }
	    int loc = glGetUniformLocation(pgm, "u_tex0");
	    if (loc >= 0) glUniform1i(loc, 0);
	    loc = glGetUniformLocation(pgm, "u_tex1");
	    if (loc >= 0) glUniform1i(loc, 1);

	    glActiveTexture(GL_TEXTURE0);
	    glBindTexture(GL_TEXTURE_2D, tex0);
	    glActiveTexture(GL_TEXTURE1);
	    glBindTexture(GL_TEXTURE_2D, tex1);
	    glActiveTexture(GL_TEXTURE0);


	    glBlendFunc(GL_ONE, GL_ONE);
	    glDisable(GL_BLEND);
            char str2[1024];
	    sprintf(str2, "%i, %i, %i, %i, %i, 0",
		    useVarColor, texCount, modulateFirstTex, extraMath, tex0);

    	    doLoop((stateClock % doLoopStates) != 0, pgm, w, h, str2);
	 }
         break;
         case 1: {
            char str2[1024];
	    glEnable(GL_BLEND);
	    sprintf(str2, "%i, %i, %i, %i, %i, 1",
		    useVarColor, texCount, modulateFirstTex, extraMath, tex0);
	    doLoop((stateClock % doLoopStates) != 0, pgm, w, h, str2);
        }
        break;
    }
}


void doTest(uint32_t w, uint32_t h) {
    int testState = stateClock / (doLoopStates * doSingleTestStates);
    int texCount;
    int extraMath;
    int testSubState;
    const int extraMathCount = 5;
    const int texCount0SubTestCount = 2;
    const int texCountNSubTestCount = 8;

    if ( testState < extraMathCount * texCount0SubTestCount) {
       texCount = 0; // Only 10 tests for texCount 0
       extraMath = (testState / texCount0SubTestCount) % extraMathCount;
       testSubState = testState % texCount0SubTestCount;
    } else {
       texCount = 1 + (testState - extraMathCount * texCount0SubTestCount) / (extraMathCount * texCountNSubTestCount);
       extraMath = (testState / texCountNSubTestCount) % extraMathCount;
       testSubState = testState % texCountNSubTestCount;
    }
    if (texCount >= 3) {
       LOGI("done\n");
       if (fOut) {
           fclose(fOut);
           fOut = NULL;
       }
       done = true;
       return;
    }


    // LOGI("doTest %d %d %d\n", texCount, extraMath, testSubState);

    switch(testSubState) {
	case 0:
            doSingleTest(w, h, false, texCount, false, extraMath, 1, 1);
	break;
	case 1:
            doSingleTest(w, h, true, texCount, false, extraMath, 1, 1);
	break;
	case 2:
                doSingleTest(w, h, false, texCount, true, extraMath, 1, 1);
	break;
	case 3:
                doSingleTest(w, h, true, texCount, true, extraMath, 1, 1);
	break;

	case 4:
                doSingleTest(w, h, false, texCount, false, extraMath, 2, 2);
	break;
	case 5:
                doSingleTest(w, h, true, texCount, false, extraMath, 2, 2);
	break;
	case 6:
                doSingleTest(w, h, false, texCount, true, extraMath, 2, 2);
	break;
	case 7:
                doSingleTest(w, h, true, texCount, true, extraMath, 2, 2);
	break;
    }
}

extern "C" {
    JNIEXPORT void JNICALL Java_com_android_glperf_GLPerfLib_init(JNIEnv * env, jobject obj,  jint width, jint height);
    JNIEXPORT void JNICALL Java_com_android_glperf_GLPerfLib_step(JNIEnv * env, jobject obj);
};

JNIEXPORT void JNICALL Java_com_android_glperf_GLPerfLib_init(JNIEnv * env, jobject obj,  jint width, jint height)
{
    if (!done) {
	    w = width;
	    h = height;
	    stateClock = 0;
	    done = false;
	    setupVA();
	    genTextures();
	    const char* fileName = "/sdcard/glperf.csv";
            if (fOut != NULL) {
                 LOGI("Closing partially written output.n");
                 fclose(fOut);
                 fOut = NULL;
            }
	    LOGI("Writing to: %s\n",fileName);
	    fOut = fopen(fileName, "w");
	    if (fOut == NULL) {
		LOGE("Could not open: %s\n", fileName);
	    }

	    LOGI("\nvarColor, texCount, modulate, extraMath, texSize, blend, Mpps, DC60\n");
	    if (fOut) fprintf(fOut,"varColor, texCount, modulate, extraMath, texSize, blend, Mpps, DC60\r\n");
    }
}

JNIEXPORT void JNICALL Java_com_android_glperf_GLPerfLib_step(JNIEnv * env, jobject obj)
{
    if (! done) {
        if (stateClock > 0 && ((stateClock & 1) == 0)) {
	    endTimer(saveBuf, w, h, 1, 100);
        }
        doTest(w, h);
        stateClock++;
    } else {
	    glClear(GL_DEPTH_BUFFER_BIT | GL_COLOR_BUFFER_BIT);
    }
}
