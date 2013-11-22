#include "ScopedLocalRef.h"
#include "SkFrontBufferedStream.h"
#include "SkMovie.h"
#include "SkStream.h"
#include "GraphicsJNI.h"
#include "SkTemplates.h"
#include "SkUtils.h"
#include "Utils.h"
#include "CreateJavaOutputStreamAdaptor.h"

#include <androidfw/Asset.h>
#include <androidfw/ResourceTypes.h>
#include <netinet/in.h>

#if 0
    #define TRACE_BITMAP(code)  code
#else
    #define TRACE_BITMAP(code)
#endif

static jclass       gMovie_class;
static jmethodID    gMovie_constructorMethodID;
static jfieldID     gMovie_nativeInstanceID;

jobject create_jmovie(JNIEnv* env, SkMovie* moov) {
    if (NULL == moov) {
        return NULL;
    }
    return env->NewObject(gMovie_class, gMovie_constructorMethodID,
            static_cast<jint>(reinterpret_cast<uintptr_t>(moov)));
}

static SkMovie* J2Movie(JNIEnv* env, jobject movie) {
    SkASSERT(env);
    SkASSERT(movie);
    SkASSERT(env->IsInstanceOf(movie, gMovie_class));
    SkMovie* m = (SkMovie*)env->GetIntField(movie, gMovie_nativeInstanceID);
    SkASSERT(m);
    return m;
}

///////////////////////////////////////////////////////////////////////////////

static int movie_width(JNIEnv* env, jobject movie) {
    NPE_CHECK_RETURN_ZERO(env, movie);
    return J2Movie(env, movie)->width();
}

static int movie_height(JNIEnv* env, jobject movie) {
    NPE_CHECK_RETURN_ZERO(env, movie);
    return J2Movie(env, movie)->height();
}

static jboolean movie_isOpaque(JNIEnv* env, jobject movie) {
    NPE_CHECK_RETURN_ZERO(env, movie);
    return J2Movie(env, movie)->isOpaque();
}

static int movie_duration(JNIEnv* env, jobject movie) {
    NPE_CHECK_RETURN_ZERO(env, movie);
    return J2Movie(env, movie)->duration();
}

static jboolean movie_setTime(JNIEnv* env, jobject movie, int ms) {
    NPE_CHECK_RETURN_ZERO(env, movie);
    return J2Movie(env, movie)->setTime(ms);
}

static void movie_draw(JNIEnv* env, jobject movie, jobject canvas,
                       jfloat fx, jfloat fy, jobject jpaint) {
    NPE_CHECK_RETURN_VOID(env, movie);
    NPE_CHECK_RETURN_VOID(env, canvas);
    // its OK for paint to be null

    SkMovie* m = J2Movie(env, movie);
    SkCanvas* c = GraphicsJNI::getNativeCanvas(env, canvas);
    SkScalar sx = SkFloatToScalar(fx);
    SkScalar sy = SkFloatToScalar(fy);
    const SkBitmap& b = m->bitmap();
    const SkPaint* p = jpaint ? GraphicsJNI::getNativePaint(env, jpaint) : NULL;

    c->drawBitmap(b, sx, sy, p);
}

static jobject movie_decodeAsset(JNIEnv* env, jobject clazz, jint native_asset) {
    android::Asset* asset = reinterpret_cast<android::Asset*>(native_asset);
    if (asset == NULL) return NULL;
    SkAutoTUnref<SkStreamRewindable> stream (new android::AssetStreamAdaptor(asset));
    SkMovie* moov = SkMovie::DecodeStream(stream.get());
    return create_jmovie(env, moov);
}

static jobject movie_decodeStream(JNIEnv* env, jobject clazz, jobject istream) {

    NPE_CHECK_RETURN_ZERO(env, istream);

    jbyteArray byteArray = env->NewByteArray(16*1024);
    ScopedLocalRef<jbyteArray> scoper(env, byteArray);
    SkStream* strm = CreateJavaInputStreamAdaptor(env, istream, byteArray);
    if (NULL == strm) {
        return 0;
    }

    // Need to buffer enough input to be able to rewind as much as might be read by a decoder
    // trying to determine the stream's format. The only decoder for movies is GIF, which
    // will only read 6.
    // FIXME: Get this number from SkImageDecoder
    SkAutoTUnref<SkStreamRewindable> bufferedStream(SkFrontBufferedStream::Create(strm, 6));
    SkASSERT(bufferedStream.get() != NULL);

    SkMovie* moov = SkMovie::DecodeStream(bufferedStream);
    strm->unref();
    return create_jmovie(env, moov);
}

static jobject movie_decodeByteArray(JNIEnv* env, jobject clazz,
                                     jbyteArray byteArray,
                                     int offset, int length) {

    NPE_CHECK_RETURN_ZERO(env, byteArray);

    int totalLength = env->GetArrayLength(byteArray);
    if ((offset | length) < 0 || offset + length > totalLength) {
        doThrowAIOOBE(env);
        return 0;
    }

    AutoJavaByteArray   ar(env, byteArray);
    SkMovie* moov = SkMovie::DecodeMemory(ar.ptr() + offset, length);
    return create_jmovie(env, moov);
}

static void movie_destructor(JNIEnv* env, jobject, SkMovie* movie) {
    delete movie;
}

//////////////////////////////////////////////////////////////////////////////////////////////

#include <android_runtime/AndroidRuntime.h>

static JNINativeMethod gMethods[] = {
    {   "width",    "()I",  (void*)movie_width  },
    {   "height",   "()I",  (void*)movie_height  },
    {   "isOpaque", "()Z",  (void*)movie_isOpaque  },
    {   "duration", "()I",  (void*)movie_duration  },
    {   "setTime",  "(I)Z", (void*)movie_setTime  },
    {   "draw",     "(Landroid/graphics/Canvas;FFLandroid/graphics/Paint;)V",
                            (void*)movie_draw  },
    { "nativeDecodeAsset", "(I)Landroid/graphics/Movie;",
                            (void*)movie_decodeAsset },
    { "nativeDecodeStream", "(Ljava/io/InputStream;)Landroid/graphics/Movie;",
                            (void*)movie_decodeStream },
    { "nativeDestructor","(I)V", (void*)movie_destructor },
    { "decodeByteArray", "([BII)Landroid/graphics/Movie;",
                            (void*)movie_decodeByteArray },
};

#define kClassPathName  "android/graphics/Movie"

#define RETURN_ERR_IF_NULL(value)   do { if (!(value)) { assert(0); return -1; } } while (false)

int register_android_graphics_Movie(JNIEnv* env)
{
    gMovie_class = env->FindClass(kClassPathName);
    RETURN_ERR_IF_NULL(gMovie_class);
    gMovie_class = (jclass)env->NewGlobalRef(gMovie_class);

    gMovie_constructorMethodID = env->GetMethodID(gMovie_class, "<init>", "(I)V");
    RETURN_ERR_IF_NULL(gMovie_constructorMethodID);

    gMovie_nativeInstanceID = env->GetFieldID(gMovie_class, "mNativeMovie", "I");
    RETURN_ERR_IF_NULL(gMovie_nativeInstanceID);

    return android::AndroidRuntime::registerNativeMethods(env, kClassPathName,
                                                       gMethods, SK_ARRAY_COUNT(gMethods));
}
