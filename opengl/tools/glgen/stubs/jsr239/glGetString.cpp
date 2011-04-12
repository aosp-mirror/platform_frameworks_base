/* const GLubyte * glGetString ( GLenum name ) */
static jstring android_glGetString(JNIEnv *_env, jobject, jint name) {
    const char* chars = (const char*) glGetString((GLenum) name);
    return _env->NewStringUTF(chars);
}
