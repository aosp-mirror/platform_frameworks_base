#include <string.h>

/* void glGetProgramInfoLog ( GLuint shader, GLsizei maxLength, GLsizei* length, GLchar* infoLog ) */
static
jstring
android_glGetProgramInfoLog (JNIEnv *_env, jobject _this, jint shader) {
    GLint infoLen = 0;
    jstring _result = 0;
    char* buf = 0;
    glGetProgramiv(shader, GL_INFO_LOG_LENGTH, &infoLen);
    if (infoLen) {
        char* buf = (char*) malloc(infoLen);
        if (buf == 0) {
            _env->ThrowNew(IAEClass, "out of memory");
            goto exit;
        }
        glGetProgramInfoLog(shader, infoLen, NULL, buf);
        _result = _env->NewStringUTF(buf);
    } else {
        _result = _env->NewStringUTF("");
    }
exit:
    if (buf) {
            free(buf);
    }
    return _result;
}