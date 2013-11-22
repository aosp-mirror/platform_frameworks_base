#ifndef CreateJavaOutputStream_DEFINED
#define CreateJavaOutputStream_DEFINED

//#include <android_runtime/AndroidRuntime.h>
#include "jni.h"

class SkMemoryStream;
class SkStream;
class SkStreamRewindable;
class SkWStream;

/**
 *  Return an adaptor from a Java InputStream to an SkStream.
 *  Does not support rewind.
 *  @param env JNIEnv object.
 *  @param stream Pointer to Java InputStream.
 *  @param storage Java byte array for retrieving data from the
 *      Java InputStream.
 *  @return SkStream Simple subclass of SkStream which supports its
 *      basic methods like reading. Only valid until the calling
 *      function returns, since the Java InputStream is not managed
 *      by the SkStream.
 */
SkStream* CreateJavaInputStreamAdaptor(JNIEnv* env, jobject stream,
                                       jbyteArray storage);

/**
 *  Copy a Java InputStream. The result will be rewindable.
 *  @param env JNIEnv object.
 *  @param stream Pointer to Java InputStream.
 *  @param storage Java byte array for retrieving data from the
 *      Java InputStream.
 *  @return SkStreamRewindable The data in stream will be copied
 *      to a new SkStreamRewindable.
 */
SkStreamRewindable* CopyJavaInputStream(JNIEnv* env, jobject stream,
                                        jbyteArray storage);

SkWStream* CreateJavaOutputStreamAdaptor(JNIEnv* env, jobject stream,
                                         jbyteArray storage);
#endif
