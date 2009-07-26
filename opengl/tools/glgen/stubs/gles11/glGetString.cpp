#include <string.h>

/* const GLubyte * glGetString ( GLenum name ) */
static
jstring
android_glGetString
  (JNIEnv *_env, jobject _this, jint name) {
    const char * chars = (const char *)glGetString((GLenum)name);
    jstring output = _env->NewStringUTF(chars);
    return output;
}
