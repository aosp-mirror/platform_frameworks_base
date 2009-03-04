#ifndef NIOBuffer_DEFINED
#define NIOBuffer_DEFINED

#include <jni.h>
#include "SkBitmap.h"

class NIOBuffer {
public:
    NIOBuffer(JNIEnv* env, jobject buffer);
    // this checks to ensure that free() was called
    ~NIOBuffer();

    void* lock(JNIEnv* env, int* remaining);
    void unlock(JNIEnv* env, bool dataChanged);
    // must be called before destructor
    void free(JNIEnv* env);

    // call once on boot, to setup JNI globals
    static void RegisterJNI(JNIEnv*);

private:
    jobject     fBuffer;
    void*       fLockedPtr;
    jbyteArray  fLockedArray;
};

#endif
