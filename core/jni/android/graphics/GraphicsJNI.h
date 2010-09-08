#ifndef GraphicsJNI_DEFINED
#define GraphicsJNI_DEFINED

#include "SkPoint.h"
#include "SkRect.h"
#include "SkBitmap.h"
#include "../images/SkBitmapRegionDecoder.h"
#include "../images/SkImageDecoder.h"
#include <jni.h>

class SkCanvas;
class SkPaint;
class SkPicture;

class GraphicsJNI {
public:
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
  
    static SkCanvas* getNativeCanvas(JNIEnv*, jobject canvas);
    static SkPaint*  getNativePaint(JNIEnv*, jobject paint);
    static SkBitmap* getNativeBitmap(JNIEnv*, jobject bitmap);
    static SkPicture* getNativePicture(JNIEnv*, jobject picture);
    static SkRegion* getNativeRegion(JNIEnv*, jobject region);
    
    /** Return the corresponding native config from the java Config enum,
        or kNo_Config if the java object is null.
    */
    static SkBitmap::Config getNativeBitmapConfig(JNIEnv*, jobject jconfig);
    
    /** Create a java Bitmap object given the native bitmap (required) and optional
        storage array (may be null). If storage is specified, then it must already be
        locked, and its native address set as the bitmap's pixels. If storage is null,
        then the bitmap must be an owner of its natively allocated pixels (via allocPixels).
        */
    static jobject createBitmap(JNIEnv* env, SkBitmap* bitmap, bool isMutable,
                                jbyteArray ninePatch, int density = -1);
    
    static jobject createRegion(JNIEnv* env, SkRegion* region);

    static jobject createBitmapRegionDecoder(JNIEnv* env, SkBitmapRegionDecoder* bitmap);

    /** Set a pixelref for the bitmap (needs setConfig to already be called)
        Returns true on success. If it returns false, then it failed, and the
        appropriate exception will have been raised.
    */
    static bool setJavaPixelRef(JNIEnv*, SkBitmap*, SkColorTable* ctable,
                                bool reportSizeToVM);

    /** Copy the colors in colors[] to the bitmap, convert to the correct
        format along the way.
    */
    static bool SetPixels(JNIEnv* env, jintArray colors, int srcOffset,
                          int srcStride, int x, int y, int width, int height,
                          const SkBitmap& dstBitmap);
};

class JavaPixelAllocator : public SkBitmap::Allocator {
public:
    JavaPixelAllocator(JNIEnv* env, bool reportSizeToVM);
    // overrides
    virtual bool allocPixelRef(SkBitmap* bitmap, SkColorTable* ctable);
    
private:
    JavaVM* fVM;
    bool fReportSizeToVM;
};

class JavaMemoryUsageReporter : public SkVMMemoryReporter {
public:
    JavaMemoryUsageReporter(JNIEnv* env);
    virtual ~JavaMemoryUsageReporter();
    // overrides
    virtual bool reportMemory(size_t memorySize);

private:
    JavaVM* fVM;
    size_t fTotalSize;
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

void doThrow(JNIEnv* env, const char* exc, const char* msg = NULL);
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

#endif
