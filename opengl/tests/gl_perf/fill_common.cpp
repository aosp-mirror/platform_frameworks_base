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

FILE * fOut = NULL;
void ptSwap();

static void checkGlError(const char* op) {
    for (GLint error = glGetError(); error; error
            = glGetError()) {
        LOGE("after %s() glError (0x%x)\n", op, error);
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
                    LOGE("Could not compile shader %d:\n%s\n", shaderType, buf);
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

    if (fOut) {
        fprintf(fOut, "%s, %f, %f\r\n", str, mpps, dc60);
        fflush(fOut);
    } else {
        printf("%s, %f, %f\n", str, mpps, dc60);
    }
    LOGI("%s, %f, %f\r\n", str, mpps, dc60);
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
        tmp = append(tmp, "  c += u_1;\n");
    }
    if (extraMath > 2) {
        tmp = append(tmp, "  c *= u_2;\n");
    }
    if (extraMath > 3) {
        tmp = append(tmp, "  c += u_3;\n");
    }


    tmp = append(tmp, gShaderPostfix);
    tmp[0] = 0;

    //printf("%s", str);
    return str;
}

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

static void doLoop(bool clear, int pgm, uint32_t w, uint32_t h, const char *str) {
    if (clear) {
        glClear(GL_DEPTH_BUFFER_BIT | GL_COLOR_BUFFER_BIT);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        ptSwap();
        glFinish();
        return;
    }

    startTimer();
    glClear(GL_DEPTH_BUFFER_BIT | GL_COLOR_BUFFER_BIT);
    for (int ct=0; ct < 100; ct++) {
        randUniform(pgm, "u_color");
        randUniform(pgm, "u_0");
        randUniform(pgm, "u_1");
        randUniform(pgm, "u_2");
        randUniform(pgm, "u_3");
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    }
    ptSwap();
    glFinish();
    endTimer(str, w, h, 1, 100);
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


