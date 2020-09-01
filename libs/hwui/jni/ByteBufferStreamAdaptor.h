#ifndef _ANDROID_GRAPHICS_BYTE_BUFFER_STREAM_ADAPTOR_H_
#define _ANDROID_GRAPHICS_BYTE_BUFFER_STREAM_ADAPTOR_H_

#include <jni.h>
#include <memory>

class SkStream;

/**
 * Create an adaptor for treating a java.nio.ByteBuffer as an SkStream.
 *
 * This will special case direct ByteBuffers, but not the case where a byte[]
 * can be used directly. For that, use CreateByteArrayStreamAdaptor.
 *
 * @param jbyteBuffer corresponding to the java ByteBuffer. This method will
 *      add a global ref.
 * @param initialPosition returned by ByteBuffer.position(). Decoding starts
 *      from here.
 * @param limit returned by ByteBuffer.limit().
 *
 * Returns null on failure.
 */
std::unique_ptr<SkStream> CreateByteBufferStreamAdaptor(JNIEnv*, jobject jbyteBuffer,
                                                        size_t initialPosition, size_t limit);

/**
 * Create an adaptor for treating a Java byte[] as an SkStream.
 *
 * @param offset into the byte[] of the beginning of the data to use.
 * @param length of data to use, starting from offset.
 *
 * Returns null on failure.
 */
std::unique_ptr<SkStream> CreateByteArrayStreamAdaptor(JNIEnv*, jbyteArray array, size_t offset,
                                                       size_t length);

#endif  // _ANDROID_GRAPHICS_BYTE_BUFFER_STREAM_ADAPTOR_H_
