#ifndef CreateJavaOutputStream_DEFINED
#define CreateJavaOutputStream_DEFINED

//#include <android_runtime/AndroidRuntime.h>
#include "jni.h"

namespace android {
    class AssetStreamAdaptor;
}

class SkMemoryStream;
class SkStream;
class SkStreamRewindable;
class SkWStream;

/**
 *  Return an adaptor from a Java InputStream to an SkStream.
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
 *  Copy a Java InputStream.
 *  @param env JNIEnv object.
 *  @param stream Pointer to Java InputStream.
 *  @param storage Java byte array for retrieving data from the
 *      Java InputStream.
 *  @return SkStreamRewindable The data in stream will be copied
 *      to a new SkStreamRewindable.
 */
SkStreamRewindable* CopyJavaInputStream(JNIEnv* env, jobject stream,
                                        jbyteArray storage);

/**
 *  Get a rewindable stream from a Java InputStream.
 *  @param env JNIEnv object.
 *  @param stream Pointer to Java InputStream.
 *  @param storage Java byte array for retrieving data from the
 *      Java InputStream.
 *  @return SkStreamRewindable Either a wrapper around the Java
 *      InputStream, if possible, or a copy which is rewindable.
 *      Since it may be a wrapper, must not be used after the
 *      caller returns, like the result of CreateJavaInputStreamAdaptor.
 */
SkStreamRewindable* GetRewindableStream(JNIEnv* env, jobject stream,
                                        jbyteArray storage);

/**
 *  If the Java InputStream is an AssetInputStream, return an adaptor.
 *  This should not be used after the calling function returns, since
 *  the caller may close the asset. Returns NULL if the stream is
 *  not an AssetInputStream.
 *  @param env JNIEnv object.
 *  @param stream Pointer to Java InputStream.
 *  @return AssetStreamAdaptor representing the InputStream, or NULL.
 *      Must not be held onto.
 */
android::AssetStreamAdaptor* CheckForAssetStream(JNIEnv* env, jobject stream);

SkWStream* CreateJavaOutputStreamAdaptor(JNIEnv* env, jobject stream,
                                         jbyteArray storage);
#endif
