#ifndef _ANDROID_GRAPHICS_GRAPHICS_JNI_H_
#define _ANDROID_GRAPHICS_GRAPHICS_JNI_H_

#include "Bitmap.h"
#include "SkBitmap.h"
#include "SkDevice.h"
#include "SkPixelRef.h"
#include "SkMallocPixelRef.h"
#include "SkPoint.h"
#include "SkRect.h"
#include "SkImageDecoder.h"
#include <Canvas.h>
#include <jni.h>

class SkBitmapRegionDecoder;
class SkCanvas;

namespace android {
class Paint;
struct TypefaceImpl;
}

class GraphicsJNI {
public:
    enum BitmapCreateFlags {
        kBitmapCreateFlag_None = 0x0,
        kBitmapCreateFlag_Mutable = 0x1,
        kBitmapCreateFlag_Premultiplied = 0x2,
    };

    // returns true if an exception is set (and dumps it out to the Log)
    static bool hasException(JNIEnv*);

    static void get_jrect(JNIEnv*, jobject jrect, int* L, int* T, int* R, int* B);
    static void set_jrect(JNIEnv*, jobject jrect, int L, int T, int R, int B);

    static SkIRect* jrect_to_irect(JNIEnv*, jobject jrect, SkIRect*);
    static void irect_to_jrect(const SkIRect&, JNIEnv*, jobject jrect);

    static SkRect* jrectf_to_rect(JNIEnv*, jobject jrectf, SkRect*);
    static SkRect* jrect_to_rect(JNIEnv*, jobject jrect, SkRect*);
    static void rect_to_jrectf(const SkRect&, JNIEnv*, jobject jrectf);

    static void set_jpoint(JNIEnv*, jobject jrect, int x, int y);

    static SkIPoint* jpoint_to_ipoint(JNIEnv*, jobject jpoint, SkIPoint* point);
    static void ipoint_to_jpoint(const SkIPoint& point, JNIEnv*, jobject jpoint);

    static SkPoint* jpointf_to_point(JNIEnv*, jobject jpointf, SkPoint* point);
    static void point_to_jpointf(const SkPoint& point, JNIEnv*, jobject jpointf);

    static android::Canvas* getNativeCanvas(JNIEnv*, jobject canvas);
    static android::Bitmap* getBitmap(JNIEnv*, jobject bitmap);
    static void getSkBitmap(JNIEnv*, jobject bitmap, SkBitmap* outBitmap);
    static SkPixelRef* refSkPixelRef(JNIEnv*, jobject bitmap);
    static SkRegion* getNativeRegion(JNIEnv*, jobject region);

    // Given the 'native' long held by the Rasterizer.java object, return a
    // ref to its SkRasterizer* (or NULL).
    static SkRasterizer* refNativeRasterizer(jlong rasterizerHandle);

    /*
     *  LegacyBitmapConfig is the old enum in Skia that matched the enum int values
     *  in Bitmap.Config. Skia no longer supports this config, but has replaced it
     *  with SkColorType. These routines convert between the two.
     */
    static SkColorType legacyBitmapConfigToColorType(jint legacyConfig);
    static jint colorTypeToLegacyBitmapConfig(SkColorType colorType);

    /** Return the corresponding native colorType from the java Config enum,
        or kUnknown_SkColorType if the java object is null.
    */
    static SkColorType getNativeBitmapColorType(JNIEnv*, jobject jconfig);

    /*
     * Create a java Bitmap object given the native bitmap
     * bitmap's SkAlphaType must already be in sync with bitmapCreateFlags.
    */
    static jobject createBitmap(JNIEnv* env, android::Bitmap* bitmap,
            int bitmapCreateFlags, jbyteArray ninePatchChunk = NULL,
            jobject ninePatchInsets = NULL, int density = -1);

    /** Reinitialize a bitmap. bitmap must already have its SkAlphaType set in
        sync with isPremultiplied
    */
    static void reinitBitmap(JNIEnv* env, jobject javaBitmap, const SkImageInfo& info,
            bool isPremultiplied);

    static int getBitmapAllocationByteCount(JNIEnv* env, jobject javaBitmap);

    static jobject createRegion(JNIEnv* env, SkRegion* region);

    static jobject createBitmapRegionDecoder(JNIEnv* env, SkBitmapRegionDecoder* bitmap);

    static android::Bitmap* allocateJavaPixelRef(JNIEnv* env, SkBitmap* bitmap,
            SkColorTable* ctable);

    static android::Bitmap* allocateAshmemPixelRef(JNIEnv* env, SkBitmap* bitmap,
            SkColorTable* ctable);

    static android::Bitmap* mapAshmemPixelRef(JNIEnv* env, SkBitmap* bitmap,
            SkColorTable* ctable, int fd, void* addr, size_t size, bool readOnly);

    /**
     * Given a bitmap we natively allocate a memory block to store the contents
     * of that bitmap.  The memory is then attached to the bitmap via an
     * SkPixelRef, which ensures that upon deletion the appropriate caches
     * are notified.
     */
    static bool allocatePixels(JNIEnv* env, SkBitmap* bitmap, SkColorTable* ctable);

    /** Copy the colors in colors[] to the bitmap, convert to the correct
        format along the way.
        Whether to use premultiplied pixels is determined by dstBitmap's alphaType.
    */
    static bool SetPixels(JNIEnv* env, jintArray colors, int srcOffset,
            int srcStride, int x, int y, int width, int height,
            const SkBitmap& dstBitmap);
};

/** Allocator which allocates the backing buffer in the Java heap.
 *  Instances can only be used to perform a single allocation, which helps
 *  ensure that the allocated buffer is properly accounted for with a
 *  reference in the heap (or a JNI global reference).
 */
class JavaPixelAllocator : public SkBitmap::Allocator {
public:
    JavaPixelAllocator(JNIEnv* env);
    ~JavaPixelAllocator();

    virtual bool allocPixelRef(SkBitmap* bitmap, SkColorTable* ctable) override;

    /**
     * Fetches the backing allocation object. Must be called!
     */
    android::Bitmap* getStorageObjAndReset() {
        android::Bitmap* result = mStorage;
        mStorage = NULL;
        return result;
    };

private:
    JavaVM* mJavaVM;
    android::Bitmap* mStorage = nullptr;
};

class AshmemPixelAllocator : public SkBitmap::Allocator {
public:
    AshmemPixelAllocator(JNIEnv* env);
    ~AshmemPixelAllocator();
    virtual bool allocPixelRef(SkBitmap* bitmap, SkColorTable* ctable);
    android::Bitmap* getStorageObjAndReset() {
        android::Bitmap* result = mStorage;
        mStorage = NULL;
        return result;
    };

private:
    JavaVM* mJavaVM;
    android::Bitmap* mStorage = nullptr;
};


enum JNIAccess {
    kRO_JNIAccess,
    kRW_JNIAccess
};

class AutoJavaFloatArray {
public:
    AutoJavaFloatArray(JNIEnv* env, jfloatArray array,
                       int minLength = 0, JNIAccess = kRW_JNIAccess);
    ~AutoJavaFloatArray();

    float* ptr() const { return fPtr; }
    int    length() const { return fLen; }

private:
    JNIEnv*     fEnv;
    jfloatArray fArray;
    float*      fPtr;
    int         fLen;
    int         fReleaseMode;
};

class AutoJavaIntArray {
public:
    AutoJavaIntArray(JNIEnv* env, jintArray array, int minLength = 0);
    ~AutoJavaIntArray();

    jint* ptr() const { return fPtr; }
    int    length() const { return fLen; }

private:
    JNIEnv*     fEnv;
    jintArray fArray;
    jint*      fPtr;
    int         fLen;
};

class AutoJavaShortArray {
public:
    AutoJavaShortArray(JNIEnv* env, jshortArray array,
                       int minLength = 0, JNIAccess = kRW_JNIAccess);
    ~AutoJavaShortArray();

    jshort* ptr() const { return fPtr; }
    int    length() const { return fLen; }

private:
    JNIEnv*     fEnv;
    jshortArray fArray;
    jshort*      fPtr;
    int         fLen;
    int         fReleaseMode;
};

class AutoJavaByteArray {
public:
    AutoJavaByteArray(JNIEnv* env, jbyteArray array, int minLength = 0);
    ~AutoJavaByteArray();

    jbyte* ptr() const { return fPtr; }
    int    length() const { return fLen; }

private:
    JNIEnv*     fEnv;
    jbyteArray fArray;
    jbyte*      fPtr;
    int         fLen;
};

void doThrowNPE(JNIEnv* env);
void doThrowAIOOBE(JNIEnv* env); // Array Index Out Of Bounds Exception
void doThrowIAE(JNIEnv* env, const char* msg = NULL);   // Illegal Argument
void doThrowRE(JNIEnv* env, const char* msg = NULL);   // Runtime
void doThrowISE(JNIEnv* env, const char* msg = NULL);   // Illegal State
void doThrowOOME(JNIEnv* env, const char* msg = NULL);   // Out of memory
void doThrowIOE(JNIEnv* env, const char* msg = NULL);   // IO Exception

#define NPE_CHECK_RETURN_ZERO(env, object)    \
    do { if (NULL == (object)) { doThrowNPE(env); return 0; } } while (0)

#define NPE_CHECK_RETURN_VOID(env, object)    \
    do { if (NULL == (object)) { doThrowNPE(env); return; } } while (0)

#endif  // _ANDROID_GRAPHICS_GRAPHICS_JNI_H_
