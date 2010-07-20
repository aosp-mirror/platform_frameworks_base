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

static void printGLString(const char *name, GLenum s) {
    const char *v = (const char *) glGetString(s);
    LOGI("GL %s = %s\n", name, v);
}

static void checkGlError(const char* op) {
    for (GLint error = glGetError(); error; error
            = glGetError()) {
        LOGI("after %s() glError (0x%x)\n", op, error);
    }
}

GLuint loadShader(GLenum shaderType, const char* pSource) {
    GLuint shader = glCreateShader(shaderType);
    if (shader) {
        glShaderSource(shader, 1, &pSource, NULL);
        glCompileShader(shader);
        GLint compiled = 0;
        glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
        if (!compiled) {
            GLint infoLen = 0;
            glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &infoLen);
            if (infoLen) {
                char* buf = (char*) malloc(infoLen);
                if (buf) {
                    glGetShaderInfoLog(shader, infoLen, NULL, buf);
                    LOGE("Could not compile shader %d:\n%s\n",
                            shaderType, buf);
                    free(buf);
                }
                glDeleteShader(shader);
                shader = 0;
            }
        }
    }
    return shader;
}

enum {
    A_POS,
    A_COLOR,
    A_TEX0,
    A_TEX1
};

GLuint createProgram(const char* pVertexSource, const char* pFragmentSource) {
    GLuint vertexShader = loadShader(GL_VERTEX_SHADER, pVertexSource);
    if (!vertexShader) {
        return 0;
    }

    GLuint pixelShader = loadShader(GL_FRAGMENT_SHADER, pFragmentSource);
    if (!pixelShader) {
        return 0;
    }

    GLuint program = glCreateProgram();
    if (program) {
        glAttachShader(program, vertexShader);
        checkGlError("glAttachShader v");
        glAttachShader(program, pixelShader);
        checkGlError("glAttachShader p");

        glBindAttribLocation(program, A_POS, "a_pos");
        glBindAttribLocation(program, A_COLOR, "a_color");
        glBindAttribLocation(program, A_TEX0, "a_tex0");
        glBindAttribLocation(program, A_TEX1, "a_tex1");
        glLinkProgram(program);
        GLint linkStatus = GL_FALSE;
        glGetProgramiv(program, GL_LINK_STATUS, &linkStatus);
        if (linkStatus != GL_TRUE) {
            GLint bufLength = 0;
            glGetProgramiv(program, GL_INFO_LOG_LENGTH, &bufLength);
            if (bufLength) {
                char* buf = (char*) malloc(bufLength);
                if (buf) {
                    glGetProgramInfoLog(program, bufLength, NULL, buf);
                    LOGE("Could not link program:\n%s\n", buf);
                    free(buf);
                }
            }
            glDeleteProgram(program);
            program = 0;
        }
    }
    checkGlError("createProgram");
    glUseProgram(program);
    return program;
}

uint64_t getTime() {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_nsec + ((uint64_t)t.tv_sec * 1000 * 1000 * 1000);
}

uint64_t gTime;
void startTimer() {
    gTime = getTime();
}

void endTimer(const char *str, int w, int h, double dc, int count) {
    uint64_t t2 = getTime();
    double delta = ((double)(t2 - gTime)) / 1000000000;
    double pixels = dc * (w * h) * count;
    double mpps = pixels / delta / 1000000;
    double dc60 = pixels / delta / (w * h) / 60;

    LOGI("%s, %f, %f\n", str, mpps, dc60);
}

static const char gVertexShader[] =
    "attribute vec4 a_pos;\n"
    "attribute vec4 a_color;\n"
    "attribute vec2 a_tex0;\n"
    "attribute vec2 a_tex1;\n"
    "varying vec4 v_color;\n"
    "varying vec2 v_tex0;\n"
    "varying vec2 v_tex1;\n"

    "void main() {\n"
    "    v_color = a_color;\n"
    "    v_tex0 = a_tex0;\n"
    "    v_tex1 = a_tex1;\n"
    "    gl_Position = a_pos;\n"
    "}\n";

static const char gShaderPrefix[] =
    "precision mediump float;\n"
    "uniform vec4 u_color;\n"
    "uniform vec4 u_0;\n"
    "uniform vec4 u_1;\n"
    "uniform vec4 u_2;\n"
    "uniform vec4 u_3;\n"
    "varying vec4 v_color;\n"
    "varying vec2 v_tex0;\n"
    "varying vec2 v_tex1;\n"
    "uniform sampler2D u_tex0;\n"
    "uniform sampler2D u_tex1;\n"
    "void main() {\n";

static const char gShaderPostfix[] =
    "  gl_FragColor = c;\n"
    "}\n";


static char * append(char *d, const char *s) {
    size_t len = strlen(s);
    memcpy(d, s, len);
    return d + len;
}

static char * genShader(
    bool useVarColor,
    int texCount,
    bool modulateFirstTex,
    int extraMath)
{
    char *str = (char *)calloc(16 * 1024, 1);
    char *tmp = append(str, gShaderPrefix);

    if (modulateFirstTex || !texCount) {
        if (useVarColor) {
            tmp = append(tmp, "  vec4 c = v_color;\n");
        } else {
            tmp = append(tmp, "  vec4 c = u_color;\n");
        }
    } else {
        tmp = append(tmp, "  vec4 c = texture2D(u_tex0, v_tex0);\n");
    }

    if (modulateFirstTex && texCount) {
        tmp = append(tmp, "  c *= texture2D(u_tex0, v_tex0);\n");
    }
    if (texCount > 1) {
        tmp = append(tmp, "  c *= texture2D(u_tex1, v_tex1);\n");
    }

    if (extraMath > 0) {
        tmp = append(tmp, "  c *= u_0;\n");
    }
    if (extraMath > 1) {
        tmp = append(tmp, "  c *= u_1;\n");
    }
    if (extraMath > 2) {
        tmp = append(tmp, "  c *= u_2;\n");
    }
    if (extraMath > 3) {
        tmp = append(tmp, "  c *= u_3;\n");
    }


    tmp = append(tmp, gShaderPostfix);
    tmp[0] = 0;

    //LOGI("%s", str);
    return str;
}

static void setupVA() {
    static const float vtx[] = {
        -2.0f,-1.0f,
         1.0f,-1.0f,
        -2.0f, 1.0f,
         1.0f, 1.0f };
    static const float color[] = {
        1.0f,0.0f,1.0f,1.0f,
        0.0f,0.0f,1.0f,1.0f,
        1.0f,1.0f,0.0f,1.0f,
        1.0f,1.0f,1.0f,1.0f };
    static const float tex0[] = {
        0.0f,0.0f,
        1.0f,0.0f,
        1.0f,1.0f,
        0.0f,1.0f };
    static const float tex1[] = {
        1.0f,0.0f,
        1.0f,1.0f,
        0.0f,1.0f,
        0.0f,0.0f };

    glEnableVertexAttribArray(A_POS);
    glEnableVertexAttribArray(A_COLOR);
    glEnableVertexAttribArray(A_TEX0);
    glEnableVertexAttribArray(A_TEX1);

    glVertexAttribPointer(A_POS, 2, GL_FLOAT, false, 8, vtx);
    glVertexAttribPointer(A_COLOR, 4, GL_FLOAT, false, 16, color);
    glVertexAttribPointer(A_TEX0, 2, GL_FLOAT, false, 8, tex0);
    glVertexAttribPointer(A_TEX1, 2, GL_FLOAT, false, 8, tex1);
}

//////////////////////////

// Tells us what to draw next

uint32_t w;
uint32_t h;

// The stateClock starts at zero and increments by 1 every time we draw a frame. It is used to control which phase of the test we are in.

int stateClock;
const int doLoopStates = 2;
const int doSingleTestStates = 2;
bool done;

char saveBuf[1024];

static void doLoop(uint32_t w, uint32_t h, const char *str) {
    int doLoopState = stateClock % doLoopStates;
    // LOGI("doLoop %d\n", doLoopState);
    switch(doLoopState) {
    case 0:
	    glClear(GL_DEPTH_BUFFER_BIT | GL_COLOR_BUFFER_BIT);
	    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    	break;
    case 1:
            strcpy(saveBuf, str);
	    startTimer();
	    glClear(GL_DEPTH_BUFFER_BIT | GL_COLOR_BUFFER_BIT);
	    for (int ct=0; ct < 100; ct++) {
		glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
	    }
	break;
    }
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
	    int pgm = createProgram(gVertexShader, pgmTxt);
	    if (!pgm) {
		LOGE("error running test\n");
		return;
	    }
	    int loc = glGetUniformLocation(pgm, "u_tex0");
	    //LOGI("loc = %i \n", loc);
	    if (loc >= 0) glUniform1i(loc, 0);
	    loc = glGetUniformLocation(pgm, "u_tex1");
	    if (loc >= 0) glUniform1i(loc, 1);

	    loc = glGetUniformLocation(pgm, "u_color");
	    if (loc >= 0) glUniform4f(loc, 1.f, 0.4f, 0.6f, 0.8f);

	    loc = glGetUniformLocation(pgm, "u_0");
	    if (loc >= 0) glUniform4f(loc, 1.f, 0.4f, 0.6f, 0.8f);

	    loc = glGetUniformLocation(pgm, "u_1");
	    if (loc >= 0) glUniform4f(loc, 0.7f, 0.8f, 0.6f, 0.8f);

	    loc = glGetUniformLocation(pgm, "u_2");
	    if (loc >= 0) glUniform4f(loc, 0.9f, 0.6f, 0.7f, 1.0f);

	    loc = glGetUniformLocation(pgm, "u_3");
	    if (loc >= 0) glUniform4f(loc, 0.88f, 0.2f, 0.4f, 0.2f);

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
    	    doLoop(w, h, str2);
	 }
         break;
         case 1: {
            char str2[1024];
	    glEnable(GL_BLEND);
	    sprintf(str2, "%i, %i, %i, %i, %i, 1",
		    useVarColor, texCount, modulateFirstTex, extraMath, tex0);
	    doLoop(w, h, str2);
        }
        break;
    }
}

void genTextures() {
    uint32_t *m = (uint32_t *)malloc(1024*1024*4);
    for (int y=0; y < 1024; y++){
        for (int x=0; x < 1024; x++){
            m[y*1024 + x] = 0xff0000ff | ((x & 0xff) << 8) | (y << 16);
        }
    }
    glBindTexture(GL_TEXTURE_2D, 1);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 1024, 1024, 0, GL_RGBA, GL_UNSIGNED_BYTE, m);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);

    for (int y=0; y < 16; y++){
        for (int x=0; x < 16; x++){
            m[y*16 + x] = 0xff0000ff | (x<<12) | (y<<20);
        }
    }
    glBindTexture(GL_TEXTURE_2D, 2);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 16, 16, 0, GL_RGBA, GL_UNSIGNED_BYTE, m);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);

}

void doTest(uint32_t w, uint32_t h) {
    int testState = stateClock / (doLoopStates * doSingleTestStates);
    int texCount;
    int extraMath;
    int testSubState;
    if ( testState < 5 * 2) {
       texCount = 0; // Only 10 tests for texCout 0
       extraMath = testState / 2;
       testSubState = testState % 2;
    } else {
       texCount = 1 + (testState - 10) / (5 * 8);
       extraMath = testState / 8;
       testSubState = testState % 8;
    }
    if (texCount >= 3) {
       // LOGI("done\n");
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
    w = width;
    h = height;
    stateClock = 0;
    done = false;
    setupVA();
    genTextures();

    LOGI("\nvarColor, texCount, modulate, extraMath, texSize, blend, Mpps, DC60\n");
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
