/*
 * Copyright (C) 2007 The Android Open Source Project
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

#include "fragment_shaders.cpp"

FILE * fOut = NULL;
void ptSwap();

static char gCurrentTestName[1024];
static uint32_t gWidth = 0;
static uint32_t gHeight = 0;

static void checkGlError(const char* op) {
    for (GLint error = glGetError(); error; error
            = glGetError()) {
        ALOGE("after %s() glError (0x%x)\n", op, error);
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
                    ALOGE("Could not compile shader %d:\n%s\n", shaderType, buf);
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
                    ALOGE("Could not link program:\n%s\n", buf);
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


static void endTimer(int count) {
    uint64_t t2 = getTime();
    double delta = ((double)(t2 - gTime)) / 1000000000;
    double pixels = (gWidth * gHeight) * count;
    double mpps = pixels / delta / 1000000;
    double dc60 = ((double)count) / delta / 60;

    if (fOut) {
        fprintf(fOut, "%s, %f, %f\r\n", gCurrentTestName, mpps, dc60);
        fflush(fOut);
    } else {
        printf("%s, %f, %f\n", gCurrentTestName, mpps, dc60);
    }
    ALOGI("%s, %f, %f\r\n", gCurrentTestName, mpps, dc60);
}


static const char gVertexShader[] =
    "attribute vec4 a_pos;\n"
    "attribute vec4 a_color;\n"
    "attribute vec2 a_tex0;\n"
    "attribute vec2 a_tex1;\n"
    "varying vec4 v_color;\n"
    "varying vec2 v_tex0;\n"
    "varying vec2 v_tex1;\n"
    "uniform vec2 u_texOff;\n"

    "void main() {\n"
    "    v_color = a_color;\n"
    "    v_tex0 = a_tex0;\n"
    "    v_tex1 = a_tex1;\n"
    "    v_tex0.x += u_texOff.x;\n"
    "    v_tex1.y += u_texOff.y;\n"
    "    gl_Position = a_pos;\n"
    "}\n";

static void setupVA() {
    static const float vtx[] = {
        -1.0f,-1.0f,
         1.0f,-1.0f,
        -1.0f, 1.0f,
         1.0f, 1.0f };
    static const float color[] = {
        1.0f,0.0f,1.0f,1.0f,
        0.0f,0.0f,1.0f,1.0f,
        1.0f,1.0f,0.0f,1.0f,
        1.0f,1.0f,1.0f,1.0f };
    static const float tex0[] = {
        0.0f,0.0f,
        1.0f,0.0f,
        0.0f,1.0f,
        1.0f,1.0f };
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

static void randUniform(int pgm, const char *var) {
    int loc = glGetUniformLocation(pgm, var);
    if (loc >= 0) {
        float x = ((float)rand()) / RAND_MAX;
        float y = ((float)rand()) / RAND_MAX;
        float z = ((float)rand()) / RAND_MAX;
        float w = ((float)rand()) / RAND_MAX;
        glUniform4f(loc, x, y, z, w);
    }
}

static void doLoop(bool warmup, int pgm, uint32_t passCount) {
    if (warmup) {
        glClear(GL_DEPTH_BUFFER_BIT | GL_COLOR_BUFFER_BIT);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        ptSwap();
        glFinish();
        return;
    }

    startTimer();
    glClear(GL_DEPTH_BUFFER_BIT | GL_COLOR_BUFFER_BIT);
    for (uint32_t ct=0; ct < passCount; ct++) {
        int loc = glGetUniformLocation(pgm, "u_texOff");
        glUniform2f(loc, ((float)ct) / passCount, ((float)ct) / 2.f / passCount);

        randUniform(pgm, "u_color");
        randUniform(pgm, "u_0");
        randUniform(pgm, "u_1");
        randUniform(pgm, "u_2");
        randUniform(pgm, "u_3");
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    }
    ptSwap();
    glFinish();
    endTimer(passCount);
}


static uint32_t rgb(uint32_t r, uint32_t g, uint32_t b)
{
    uint32_t ret = 0xff000000;
    ret |= r & 0xff;
    ret |= (g & 0xff) << 8;
    ret |= (b & 0xff) << 16;
    return ret;
}

void genTextures() {
    uint32_t *m = (uint32_t *)malloc(1024*1024*4);
    for (int y=0; y < 1024; y++){
        for (int x=0; x < 1024; x++){
            m[y*1024 + x] = rgb(x, (((x+y) & 0xff) == 0x7f) * 0xff, y);
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
            m[y*16 + x] = rgb(x << 4, (((x+y) & 0xf) == 0x7) * 0xff, y << 4);
        }
    }
    glBindTexture(GL_TEXTURE_2D, 2);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 16, 16, 0, GL_RGBA, GL_UNSIGNED_BYTE, m);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
    free(m);
}

static void doSingleTest(uint32_t pgmNum, int tex) {
    const char *pgmTxt = gFragmentTests[pgmNum]->txt;
    int pgm = createProgram(gVertexShader, pgmTxt);
    if (!pgm) {
        printf("error running test\n");
        return;
    }
    int loc = glGetUniformLocation(pgm, "u_tex0");
    if (loc >= 0) glUniform1i(loc, 0);
    loc = glGetUniformLocation(pgm, "u_tex1");
    if (loc >= 0) glUniform1i(loc, 1);


    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, tex);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, tex);
    glActiveTexture(GL_TEXTURE0);

    glBlendFunc(GL_ONE, GL_ONE);
    glDisable(GL_BLEND);
    //sprintf(str2, "%i, %i, %i, %i, %i, 0",
            //useVarColor, texCount, modulateFirstTex, extraMath, tex0);
    //doLoop(true, pgm, w, h, str2);
    //doLoop(false, pgm, w, h, str2);

    glEnable(GL_BLEND);
    sprintf(gCurrentTestName, "%s, %i, %i, 1", gFragmentTests[pgmNum]->name, pgmNum, tex);
    doLoop(true, pgm, 100);
    doLoop(false, pgm, 100);
}

