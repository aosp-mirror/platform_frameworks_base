
/* void glShaderSource ( GLuint shader, GLsizei count, const GLchar ** string, const GLint * length ) */
static
void
android_glShaderSource
    (JNIEnv *_env, jobject _this, jint shader, jstring string) {

    if (!string) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", "string == null");
        return;
    }

    const char* nativeString = _env->GetStringUTFChars(string, 0);
    const char* strings[] = {nativeString};
    glShaderSource(shader, 1, strings, 0);
    _env->ReleaseStringUTFChars(string, nativeString);
}
