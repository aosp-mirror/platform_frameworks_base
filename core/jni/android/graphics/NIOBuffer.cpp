#include "NIOBuffer.h"
#include "GraphicsJNI.h"

// enable this to dump each time we ref/unref a global java object (buffer)
//
//#define TRACE_GLOBAL_REFS

//#define TRACE_ARRAY_LOCKS

static jclass gNIOAccess_classID;
static jmethodID gNIOAccess_getBasePointer;
static jmethodID gNIOAccess_getBaseArray;
static jmethodID gNIOAccess_getBaseArrayOffset;
static jmethodID gNIOAccess_getRemainingBytes;

void NIOBuffer::RegisterJNI(JNIEnv* env) {
    if (0 != gNIOAccess_classID) {
        return; // already called
    }

    jclass c = env->FindClass("java/nio/NIOAccess");
    gNIOAccess_classID = (jclass)env->NewGlobalRef(c);

    gNIOAccess_getBasePointer = env->GetStaticMethodID(gNIOAccess_classID,
                                    "getBasePointer", "(Ljava/nio/Buffer;)J");
    gNIOAccess_getBaseArray = env->GetStaticMethodID(gNIOAccess_classID,
                    "getBaseArray", "(Ljava/nio/Buffer;)Ljava/lang/Object;");
    gNIOAccess_getBaseArrayOffset = env->GetStaticMethodID(gNIOAccess_classID,
                                "getBaseArrayOffset", "(Ljava/nio/Buffer;)I");
    gNIOAccess_getRemainingBytes = env->GetStaticMethodID(gNIOAccess_classID,
                                "getRemainingBytes", "(Ljava/nio/Buffer;)I");
}

///////////////////////////////////////////////////////////////////////////////

#ifdef TRACE_GLOBAL_REFS
    static int gGlobalRefs;
#endif

#ifdef TRACE_ARRAY_LOCKS
    static int gLockCount;
#endif

NIOBuffer::NIOBuffer(JNIEnv* env, jobject buffer) {
    fBuffer = env->NewGlobalRef(buffer);
#ifdef TRACE_GLOBAL_REFS
    SkDebugf("------------ newglobalref bbuffer %X %d\n", buffer, gGlobalRefs++);
#endif
    fLockedPtr = NULL;
    fLockedArray = NULL;
}

NIOBuffer::~NIOBuffer() {
    // free() needs to have already been called
    if (NULL != fBuffer) {
        SkDebugf("----- leaked fBuffer in NIOBuffer");
        sk_throw();
    }
}

void NIOBuffer::free(JNIEnv* env) {

    if (NULL != fLockedPtr) {
        SkDebugf("======= free: array still locked %x %p\n", fLockedArray, fLockedPtr);
    }
    
    
    if (NULL != fBuffer) {
#ifdef TRACE_GLOBAL_REFS
        SkDebugf("----------- deleteglobalref buffer %X %d\n", fBuffer, --gGlobalRefs);
#endif
        env->DeleteGlobalRef(fBuffer);
        fBuffer = NULL;
    }
}

void* NIOBuffer::lock(JNIEnv* env, int* remaining) {
    if (NULL != fLockedPtr) {
        SkDebugf("======= lock: array still locked %x %p\n", fLockedArray, fLockedPtr);
    }

    fLockedPtr = NULL;
    fLockedArray = NULL;

    if (NULL != remaining) {
        *remaining = env->CallStaticIntMethod(gNIOAccess_classID,
                                              gNIOAccess_getRemainingBytes,
                                              fBuffer);
        if (GraphicsJNI::hasException(env)) {
            return NULL;
        }
    }
    
    jlong pointer = env->CallStaticLongMethod(gNIOAccess_classID,
                                              gNIOAccess_getBasePointer,
                                              fBuffer);
    if (GraphicsJNI::hasException(env)) {
        return NULL;
    }
    if (0 != pointer) {
        return reinterpret_cast<void*>(pointer);
    }
    
    fLockedArray = (jbyteArray)env->CallStaticObjectMethod(gNIOAccess_classID,
                                                        gNIOAccess_getBaseArray,
                                                        fBuffer);
    if (GraphicsJNI::hasException(env) || NULL == fLockedArray) {
        return NULL;
    }
    jint offset = env->CallStaticIntMethod(gNIOAccess_classID,
                                           gNIOAccess_getBaseArrayOffset,
                                           fBuffer);
    fLockedPtr = env->GetByteArrayElements(fLockedArray, NULL);
    if (GraphicsJNI::hasException(env)) {
        SkDebugf("------------ failed to lockarray %x\n", fLockedArray);
        return NULL;
    }
#ifdef TRACE_ARRAY_LOCKS
    SkDebugf("------------ lockarray %x %p %d\n",
             fLockedArray, fLockedPtr, gLockCount++);
#endif
    if (NULL == fLockedPtr) {
        offset = 0;
    }
    return (char*)fLockedPtr + offset;
}

void NIOBuffer::unlock(JNIEnv* env, bool dataChanged) {
    if (NULL != fLockedPtr) {
#ifdef TRACE_ARRAY_LOCKS
        SkDebugf("------------ unlockarray %x %p %d\n",
                 fLockedArray, fLockedPtr, --gLockCount);
#endif
        env->ReleaseByteArrayElements(fLockedArray, (jbyte*)fLockedPtr,
                                      dataChanged ? 0 : JNI_ABORT);
        
        fLockedPtr = NULL;
        fLockedArray = NULL;
    } else {
        SkDebugf("============= unlock called with null ptr %x\n", fLockedArray);
    }
}

