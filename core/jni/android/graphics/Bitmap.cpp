#include "SkBitmap.h"
#include "SkPixelRef.h"
#include "SkImageEncoder.h"
#include "SkColorPriv.h"
#include "GraphicsJNI.h"
#include "SkDither.h"
#include "SkUnPreMultiply.h"
#include "SkStream.h"

#include <binder/Parcel.h>
#include "android_os_Parcel.h"
#include "android_util_Binder.h"
#include "android_nio_utils.h"
#include "CreateJavaOutputStreamAdaptor.h"

#include <jni.h>

#include <Caches.h>

#if 0
    #define TRACE_BITMAP(code)  code
#else
    #define TRACE_BITMAP(code)
#endif

///////////////////////////////////////////////////////////////////////////////
// Conversions to/from SkColor, for get/setPixels, and the create method, which
// is basically like setPixels

typedef void (*FromColorProc)(void* dst, const SkColor src[], int width,
                              int x, int y);

static void FromColor_D32(void* dst, const SkColor src[], int width,
                          int, int) {
    SkPMColor* d = (SkPMColor*)dst;

    for (int i = 0; i < width; i++) {
        *d++ = SkPreMultiplyColor(*src++);
    }
}

static void FromColor_D32_Raw(void* dst, const SkColor src[], int width,
                          int, int) {
    // SkColor's ordering may be different from SkPMColor
    if (SK_COLOR_MATCHES_PMCOLOR_BYTE_ORDER) {
        memcpy(dst, src, width * sizeof(SkColor));
        return;
    }

    // order isn't same, repack each pixel manually
    SkPMColor* d = (SkPMColor*)dst;
    for (int i = 0; i < width; i++) {
        SkColor c = *src++;
        *d++ = SkPackARGB32NoCheck(SkColorGetA(c), SkColorGetR(c),
                                   SkColorGetG(c), SkColorGetB(c));
    }
}

static void FromColor_D565(void* dst, const SkColor src[], int width,
                           int x, int y) {
    uint16_t* d = (uint16_t*)dst;

    DITHER_565_SCAN(y);
    for (int stop = x + width; x < stop; x++) {
        SkColor c = *src++;
        *d++ = SkDitherRGBTo565(SkColorGetR(c), SkColorGetG(c), SkColorGetB(c),
                                DITHER_VALUE(x));
    }
}

static void FromColor_D4444(void* dst, const SkColor src[], int width,
                            int x, int y) {
    SkPMColor16* d = (SkPMColor16*)dst;

    DITHER_4444_SCAN(y);
    for (int stop = x + width; x < stop; x++) {
        SkPMColor pmc = SkPreMultiplyColor(*src++);
        *d++ = SkDitherARGB32To4444(pmc, DITHER_VALUE(x));
//        *d++ = SkPixel32ToPixel4444(pmc);
    }
}

static void FromColor_D4444_Raw(void* dst, const SkColor src[], int width,
                            int x, int y) {
    SkPMColor16* d = (SkPMColor16*)dst;

    DITHER_4444_SCAN(y);
    for (int stop = x + width; x < stop; x++) {
        SkColor c = *src++;

        // SkPMColor is used because the ordering is ARGB32, even though the target actually premultiplied
        SkPMColor pmc = SkPackARGB32NoCheck(SkColorGetA(c), SkColorGetR(c),
                                            SkColorGetG(c), SkColorGetB(c));
        *d++ = SkDitherARGB32To4444(pmc, DITHER_VALUE(x));
//        *d++ = SkPixel32ToPixel4444(pmc);
    }
}

// can return NULL
static FromColorProc ChooseFromColorProc(SkBitmap::Config config, bool isPremultiplied) {
    switch (config) {
        case SkBitmap::kARGB_8888_Config:
            return isPremultiplied ? FromColor_D32 : FromColor_D32_Raw;
        case SkBitmap::kARGB_4444_Config:
            return isPremultiplied ? FromColor_D4444 : FromColor_D4444_Raw;
        case SkBitmap::kRGB_565_Config:
            return FromColor_D565;
        default:
            break;
    }
    return NULL;
}

bool GraphicsJNI::SetPixels(JNIEnv* env, jintArray srcColors, int srcOffset, int srcStride,
        int x, int y, int width, int height,
        const SkBitmap& dstBitmap, bool isPremultiplied) {
    SkAutoLockPixels alp(dstBitmap);
    void* dst = dstBitmap.getPixels();
    FromColorProc proc = ChooseFromColorProc(dstBitmap.config(), isPremultiplied);

    if (NULL == dst || NULL == proc) {
        return false;
    }

    const jint* array = env->GetIntArrayElements(srcColors, NULL);
    const SkColor* src = (const SkColor*)array + srcOffset;

    // reset to to actual choice from caller
    dst = dstBitmap.getAddr(x, y);
    // now copy/convert each scanline
    for (int y = 0; y < height; y++) {
        proc(dst, src, width, x, y);
        src += srcStride;
        dst = (char*)dst + dstBitmap.rowBytes();
    }

    dstBitmap.notifyPixelsChanged();

    env->ReleaseIntArrayElements(srcColors, const_cast<jint*>(array),
                                 JNI_ABORT);
    return true;
}

//////////////////// ToColor procs

typedef void (*ToColorProc)(SkColor dst[], const void* src, int width,
                            SkColorTable*);

static void ToColor_S32_Alpha(SkColor dst[], const void* src, int width,
                              SkColorTable*) {
    SkASSERT(width > 0);
    const SkPMColor* s = (const SkPMColor*)src;
    do {
        *dst++ = SkUnPreMultiply::PMColorToColor(*s++);
    } while (--width != 0);
}

static void ToColor_S32_Raw(SkColor dst[], const void* src, int width,
                              SkColorTable*) {
    SkASSERT(width > 0);
    const SkPMColor* s = (const SkPMColor*)src;
    do {
        SkPMColor c = *s++;
        *dst++ = SkColorSetARGB(SkGetPackedA32(c), SkGetPackedR32(c),
                                SkGetPackedG32(c), SkGetPackedB32(c));
    } while (--width != 0);
}

static void ToColor_S32_Opaque(SkColor dst[], const void* src, int width,
                               SkColorTable*) {
    SkASSERT(width > 0);
    const SkPMColor* s = (const SkPMColor*)src;
    do {
        SkPMColor c = *s++;
        *dst++ = SkColorSetRGB(SkGetPackedR32(c), SkGetPackedG32(c),
                               SkGetPackedB32(c));
    } while (--width != 0);
}

static void ToColor_S4444_Alpha(SkColor dst[], const void* src, int width,
                                SkColorTable*) {
    SkASSERT(width > 0);
    const SkPMColor16* s = (const SkPMColor16*)src;
    do {
        *dst++ = SkUnPreMultiply::PMColorToColor(SkPixel4444ToPixel32(*s++));
    } while (--width != 0);
}

static void ToColor_S4444_Raw(SkColor dst[], const void* src, int width,
                                SkColorTable*) {
    SkASSERT(width > 0);
    const SkPMColor16* s = (const SkPMColor16*)src;
    do {
        SkPMColor c = SkPixel4444ToPixel32(*s++);
        *dst++ = SkColorSetARGB(SkGetPackedA32(c), SkGetPackedR32(c),
                                SkGetPackedG32(c), SkGetPackedB32(c));
    } while (--width != 0);
}

static void ToColor_S4444_Opaque(SkColor dst[], const void* src, int width,
                                 SkColorTable*) {
    SkASSERT(width > 0);
    const SkPMColor16* s = (const SkPMColor16*)src;
    do {
        SkPMColor c = SkPixel4444ToPixel32(*s++);
        *dst++ = SkColorSetRGB(SkGetPackedR32(c), SkGetPackedG32(c),
                               SkGetPackedB32(c));
    } while (--width != 0);
}

static void ToColor_S565(SkColor dst[], const void* src, int width,
                         SkColorTable*) {
    SkASSERT(width > 0);
    const uint16_t* s = (const uint16_t*)src;
    do {
        uint16_t c = *s++;
        *dst++ =  SkColorSetRGB(SkPacked16ToR32(c), SkPacked16ToG32(c),
                                SkPacked16ToB32(c));
    } while (--width != 0);
}

static void ToColor_SI8_Alpha(SkColor dst[], const void* src, int width,
                              SkColorTable* ctable) {
    SkASSERT(width > 0);
    const uint8_t* s = (const uint8_t*)src;
    const SkPMColor* colors = ctable->lockColors();
    do {
        *dst++ = SkUnPreMultiply::PMColorToColor(colors[*s++]);
    } while (--width != 0);
    ctable->unlockColors(false);
}

static void ToColor_SI8_Raw(SkColor dst[], const void* src, int width,
                              SkColorTable* ctable) {
    SkASSERT(width > 0);
    const uint8_t* s = (const uint8_t*)src;
    const SkPMColor* colors = ctable->lockColors();
    do {
        SkPMColor c = colors[*s++];
        *dst++ = SkColorSetARGB(SkGetPackedA32(c), SkGetPackedR32(c),
                                SkGetPackedG32(c), SkGetPackedB32(c));
    } while (--width != 0);
    ctable->unlockColors(false);
}

static void ToColor_SI8_Opaque(SkColor dst[], const void* src, int width,
                               SkColorTable* ctable) {
    SkASSERT(width > 0);
    const uint8_t* s = (const uint8_t*)src;
    const SkPMColor* colors = ctable->lockColors();
    do {
        SkPMColor c = colors[*s++];
        *dst++ = SkColorSetRGB(SkGetPackedR32(c), SkGetPackedG32(c),
                               SkGetPackedB32(c));
    } while (--width != 0);
    ctable->unlockColors(false);
}

// can return NULL
static ToColorProc ChooseToColorProc(const SkBitmap& src, bool isPremultiplied) {
    switch (src.config()) {
        case SkBitmap::kARGB_8888_Config:
            if (src.isOpaque()) return ToColor_S32_Opaque;
            return isPremultiplied ? ToColor_S32_Alpha : ToColor_S32_Raw;
        case SkBitmap::kARGB_4444_Config:
            if (src.isOpaque()) return ToColor_S4444_Opaque;
            return isPremultiplied ? ToColor_S4444_Alpha : ToColor_S4444_Raw;
        case SkBitmap::kRGB_565_Config:
            return ToColor_S565;
        case SkBitmap::kIndex8_Config:
            if (src.getColorTable() == NULL) {
                return NULL;
            }
            if (src.isOpaque()) return ToColor_SI8_Opaque;
            return isPremultiplied ? ToColor_SI8_Raw : ToColor_SI8_Alpha;
        default:
            break;
    }
    return NULL;
}

///////////////////////////////////////////////////////////////////////////////
///////////////////////////////////////////////////////////////////////////////

static int getPremulBitmapCreateFlags(bool isMutable) {
    int flags = GraphicsJNI::kBitmapCreateFlag_Premultiplied;
    if (isMutable) flags |= GraphicsJNI::kBitmapCreateFlag_Mutable;
    return flags;
}

static jobject Bitmap_creator(JNIEnv* env, jobject, jintArray jColors,
                              int offset, int stride, int width, int height,
                              SkBitmap::Config config, jboolean isMutable) {
    if (NULL != jColors) {
        size_t n = env->GetArrayLength(jColors);
        if (n < SkAbs32(stride) * (size_t)height) {
            doThrowAIOOBE(env);
            return NULL;
        }
    }

    // ARGB_4444 is a deprecated format, convert automatically to 8888
    if (config == SkBitmap::kARGB_4444_Config) {
        config = SkBitmap::kARGB_8888_Config;
    }

    SkBitmap bitmap;
    bitmap.setConfig(config, width, height);

    jbyteArray buff = GraphicsJNI::allocateJavaPixelRef(env, &bitmap, NULL);
    if (NULL == buff) {
        return NULL;
    }

    if (jColors != NULL) {
        GraphicsJNI::SetPixels(env, jColors, offset, stride,
                0, 0, width, height, bitmap, true);
    }

    return GraphicsJNI::createBitmap(env, new SkBitmap(bitmap), buff,
            getPremulBitmapCreateFlags(isMutable), NULL, NULL);
}

static jobject Bitmap_copy(JNIEnv* env, jobject, const SkBitmap* src,
                           SkBitmap::Config dstConfig, jboolean isMutable) {
    SkBitmap            result;
    JavaPixelAllocator  allocator(env);

    if (!src->copyTo(&result, dstConfig, &allocator)) {
        return NULL;
    }
    return GraphicsJNI::createBitmap(env, new SkBitmap(result), allocator.getStorageObj(),
            getPremulBitmapCreateFlags(isMutable), NULL, NULL);
}

static void Bitmap_destructor(JNIEnv* env, jobject, SkBitmap* bitmap) {
#ifdef USE_OPENGL_RENDERER
    if (android::uirenderer::Caches::hasInstance()) {
        android::uirenderer::Caches::getInstance().resourceCache.destructor(bitmap);
        return;
    }
#endif // USE_OPENGL_RENDERER
    delete bitmap;
}

static jboolean Bitmap_recycle(JNIEnv* env, jobject, SkBitmap* bitmap) {
#ifdef USE_OPENGL_RENDERER
    if (android::uirenderer::Caches::hasInstance()) {
        return android::uirenderer::Caches::getInstance().resourceCache.recycle(bitmap);
    }
#endif // USE_OPENGL_RENDERER
    bitmap->setPixels(NULL, NULL);
    return true;
}

static void Bitmap_reconfigure(JNIEnv* env, jobject clazz, jint bitmapInt,
        int width, int height, SkBitmap::Config config, int allocSize) {
    if (width * height * SkBitmap::ComputeBytesPerPixel(config) > allocSize) {
        // done in native as there's no way to get BytesPerPixel in Java
        doThrowIAE(env, "Bitmap not large enough to support new configuration");
        return;
    }
    SkBitmap* bitmap = reinterpret_cast<SkBitmap*>(bitmapInt);
    SkPixelRef* ref = bitmap->pixelRef();
    SkSafeRef(ref);
    bitmap->setConfig(config, width, height);
    bitmap->setPixelRef(ref);

    // notifyPixelsChanged will increment the generation ID even though the actual pixel data
    // hasn't been touched. This signals the renderer that the bitmap (including width, height,
    // and config) has changed.
    ref->notifyPixelsChanged();
    SkSafeUnref(ref);
}

// These must match the int values in Bitmap.java
enum JavaEncodeFormat {
    kJPEG_JavaEncodeFormat = 0,
    kPNG_JavaEncodeFormat = 1,
    kWEBP_JavaEncodeFormat = 2
};

static bool Bitmap_compress(JNIEnv* env, jobject clazz, SkBitmap* bitmap,
                            int format, int quality,
                            jobject jstream, jbyteArray jstorage) {
    SkImageEncoder::Type fm;

    switch (format) {
    case kJPEG_JavaEncodeFormat:
        fm = SkImageEncoder::kJPEG_Type;
        break;
    case kPNG_JavaEncodeFormat:
        fm = SkImageEncoder::kPNG_Type;
        break;
    case kWEBP_JavaEncodeFormat:
        fm = SkImageEncoder::kWEBP_Type;
        break;
    default:
        return false;
    }

    bool success = false;
    if (NULL != bitmap) {
        SkAutoLockPixels alp(*bitmap);

        if (NULL == bitmap->getPixels()) {
            return false;
        }

        SkWStream* strm = CreateJavaOutputStreamAdaptor(env, jstream, jstorage);
        if (NULL == strm) {
            return false;
        }

        SkImageEncoder* encoder = SkImageEncoder::Create(fm);
        if (NULL != encoder) {
            success = encoder->encodeStream(strm, *bitmap, quality);
            delete encoder;
        }
        delete strm;
    }
    return success;
}

static void Bitmap_erase(JNIEnv* env, jobject, SkBitmap* bitmap, jint color) {
    bitmap->eraseColor(color);
}

static int Bitmap_rowBytes(JNIEnv* env, jobject, SkBitmap* bitmap) {
    return bitmap->rowBytes();
}

static int Bitmap_config(JNIEnv* env, jobject, SkBitmap* bitmap) {
    return bitmap->config();
}

static int Bitmap_getGenerationId(JNIEnv* env, jobject, SkBitmap* bitmap) {
    return bitmap->getGenerationID();
}

static jboolean Bitmap_hasAlpha(JNIEnv* env, jobject, SkBitmap* bitmap) {
    return !bitmap->isOpaque();
}

static void Bitmap_setHasAlpha(JNIEnv* env, jobject, SkBitmap* bitmap,
                               jboolean hasAlpha) {
    bitmap->setIsOpaque(!hasAlpha);
}

static jboolean Bitmap_hasMipMap(JNIEnv* env, jobject, SkBitmap* bitmap) {
    return bitmap->hasHardwareMipMap();
}

static void Bitmap_setHasMipMap(JNIEnv* env, jobject, SkBitmap* bitmap,
                                jboolean hasMipMap) {
    bitmap->setHasHardwareMipMap(hasMipMap);
}

///////////////////////////////////////////////////////////////////////////////

static jobject Bitmap_createFromParcel(JNIEnv* env, jobject, jobject parcel) {
    if (parcel == NULL) {
        SkDebugf("-------- unparcel parcel is NULL\n");
        return NULL;
    }

    android::Parcel* p = android::parcelForJavaObject(env, parcel);

    const bool              isMutable = p->readInt32() != 0;
    const SkBitmap::Config  config = (SkBitmap::Config)p->readInt32();
    const int               width = p->readInt32();
    const int               height = p->readInt32();
    const int               rowBytes = p->readInt32();
    const int               density = p->readInt32();

    if (SkBitmap::kARGB_8888_Config != config &&
            SkBitmap::kRGB_565_Config != config &&
            SkBitmap::kARGB_4444_Config != config &&
            SkBitmap::kIndex8_Config != config &&
            SkBitmap::kA8_Config != config) {
        SkDebugf("Bitmap_createFromParcel unknown config: %d\n", config);
        return NULL;
    }

    SkBitmap* bitmap = new SkBitmap;

    bitmap->setConfig(config, width, height, rowBytes);

    SkColorTable* ctable = NULL;
    if (config == SkBitmap::kIndex8_Config) {
        int count = p->readInt32();
        if (count > 0) {
            size_t size = count * sizeof(SkPMColor);
            const SkPMColor* src = (const SkPMColor*)p->readInplace(size);
            ctable = new SkColorTable(src, count);
        }
    }

    jbyteArray buffer = GraphicsJNI::allocateJavaPixelRef(env, bitmap, ctable);
    if (NULL == buffer) {
        SkSafeUnref(ctable);
        delete bitmap;
        return NULL;
    }

    SkSafeUnref(ctable);

    size_t size = bitmap->getSize();

    android::Parcel::ReadableBlob blob;
    android::status_t status = p->readBlob(size, &blob);
    if (status) {
        doThrowRE(env, "Could not read bitmap from parcel blob.");
        delete bitmap;
        return NULL;
    }

    bitmap->lockPixels();
    memcpy(bitmap->getPixels(), blob.data(), size);
    bitmap->unlockPixels();

    blob.release();

    return GraphicsJNI::createBitmap(env, bitmap, buffer, getPremulBitmapCreateFlags(isMutable),
            NULL, NULL, density);
}

static jboolean Bitmap_writeToParcel(JNIEnv* env, jobject,
                                     const SkBitmap* bitmap,
                                     jboolean isMutable, jint density,
                                     jobject parcel) {
    if (parcel == NULL) {
        SkDebugf("------- writeToParcel null parcel\n");
        return false;
    }

    android::Parcel* p = android::parcelForJavaObject(env, parcel);

    p->writeInt32(isMutable);
    p->writeInt32(bitmap->config());
    p->writeInt32(bitmap->width());
    p->writeInt32(bitmap->height());
    p->writeInt32(bitmap->rowBytes());
    p->writeInt32(density);

    if (bitmap->getConfig() == SkBitmap::kIndex8_Config) {
        SkColorTable* ctable = bitmap->getColorTable();
        if (ctable != NULL) {
            int count = ctable->count();
            p->writeInt32(count);
            memcpy(p->writeInplace(count * sizeof(SkPMColor)),
                   ctable->lockColors(), count * sizeof(SkPMColor));
            ctable->unlockColors(false);
        } else {
            p->writeInt32(0);   // indicate no ctable
        }
    }

    size_t size = bitmap->getSize();

    android::Parcel::WritableBlob blob;
    android::status_t status = p->writeBlob(size, &blob);
    if (status) {
        doThrowRE(env, "Could not write bitmap to parcel blob.");
        return false;
    }

    bitmap->lockPixels();
    const void* pSrc =  bitmap->getPixels();
    if (pSrc == NULL) {
        memset(blob.data(), 0, size);
    } else {
        memcpy(blob.data(), pSrc, size);
    }
    bitmap->unlockPixels();

    blob.release();
    return true;
}

static jobject Bitmap_extractAlpha(JNIEnv* env, jobject clazz,
                                   const SkBitmap* src, const SkPaint* paint,
                                   jintArray offsetXY) {
    SkIPoint  offset;
    SkBitmap* dst = new SkBitmap;
    JavaPixelAllocator allocator(env);

    src->extractAlpha(dst, paint, &allocator, &offset);
    // If Skia can't allocate pixels for destination bitmap, it resets
    // it, that is set its pixels buffer to NULL, and zero width and height.
    if (dst->getPixels() == NULL && src->getPixels() != NULL) {
        delete dst;
        doThrowOOME(env, "failed to allocate pixels for alpha");
        return NULL;
    }
    if (offsetXY != 0 && env->GetArrayLength(offsetXY) >= 2) {
        int* array = env->GetIntArrayElements(offsetXY, NULL);
        array[0] = offset.fX;
        array[1] = offset.fY;
        env->ReleaseIntArrayElements(offsetXY, array, 0);
    }

    return GraphicsJNI::createBitmap(env, dst, allocator.getStorageObj(),
            GraphicsJNI::kBitmapCreateFlag_Mutable, NULL, NULL);
}

///////////////////////////////////////////////////////////////////////////////

static int Bitmap_getPixel(JNIEnv* env, jobject, const SkBitmap* bitmap,
        int x, int y, bool isPremultiplied) {
    SkAutoLockPixels alp(*bitmap);

    ToColorProc proc = ChooseToColorProc(*bitmap, isPremultiplied);
    if (NULL == proc) {
        return 0;
    }
    const void* src = bitmap->getAddr(x, y);
    if (NULL == src) {
        return 0;
    }

    SkColor dst[1];
    proc(dst, src, 1, bitmap->getColorTable());
    return dst[0];
}

static void Bitmap_getPixels(JNIEnv* env, jobject, const SkBitmap* bitmap,
        jintArray pixelArray, int offset, int stride,
        int x, int y, int width, int height, bool isPremultiplied) {
    SkAutoLockPixels alp(*bitmap);

    ToColorProc proc = ChooseToColorProc(*bitmap, isPremultiplied);
    if (NULL == proc) {
        return;
    }
    const void* src = bitmap->getAddr(x, y);
    if (NULL == src) {
        return;
    }

    SkColorTable* ctable = bitmap->getColorTable();
    jint* dst = env->GetIntArrayElements(pixelArray, NULL);
    SkColor* d = (SkColor*)dst + offset;
    while (--height >= 0) {
        proc(d, src, width, ctable);
        d += stride;
        src = (void*)((const char*)src + bitmap->rowBytes());
    }
    env->ReleaseIntArrayElements(pixelArray, dst, 0);
}

///////////////////////////////////////////////////////////////////////////////

static void Bitmap_setPixel(JNIEnv* env, jobject, const SkBitmap* bitmap,
        int x, int y, SkColor color, bool isPremultiplied) {
    SkAutoLockPixels alp(*bitmap);
    if (NULL == bitmap->getPixels()) {
        return;
    }

    FromColorProc proc = ChooseFromColorProc(bitmap->config(), isPremultiplied);
    if (NULL == proc) {
        return;
    }

    proc(bitmap->getAddr(x, y), &color, 1, x, y);
    bitmap->notifyPixelsChanged();
}

static void Bitmap_setPixels(JNIEnv* env, jobject, const SkBitmap* bitmap,
        jintArray pixelArray, int offset, int stride,
        int x, int y, int width, int height, bool isPremultiplied) {
    GraphicsJNI::SetPixels(env, pixelArray, offset, stride,
            x, y, width, height, *bitmap, isPremultiplied);
}

static void Bitmap_copyPixelsToBuffer(JNIEnv* env, jobject,
                                      const SkBitmap* bitmap, jobject jbuffer) {
    SkAutoLockPixels alp(*bitmap);
    const void* src = bitmap->getPixels();

    if (NULL != src) {
        android::AutoBufferPointer abp(env, jbuffer, JNI_TRUE);

        // the java side has already checked that buffer is large enough
        memcpy(abp.pointer(), src, bitmap->getSize());
    }
}

static void Bitmap_copyPixelsFromBuffer(JNIEnv* env, jobject,
                                    const SkBitmap* bitmap, jobject jbuffer) {
    SkAutoLockPixels alp(*bitmap);
    void* dst = bitmap->getPixels();

    if (NULL != dst) {
        android::AutoBufferPointer abp(env, jbuffer, JNI_FALSE);
        // the java side has already checked that buffer is large enough
        memcpy(dst, abp.pointer(), bitmap->getSize());
        bitmap->notifyPixelsChanged();
    }
}

static bool Bitmap_sameAs(JNIEnv* env, jobject, const SkBitmap* bm0,
                             const SkBitmap* bm1) {
    if (bm0->width() != bm1->width() ||
        bm0->height() != bm1->height() ||
        bm0->config() != bm1->config()) {
        return false;
    }

    SkAutoLockPixels alp0(*bm0);
    SkAutoLockPixels alp1(*bm1);

    // if we can't load the pixels, return false
    if (NULL == bm0->getPixels() || NULL == bm1->getPixels()) {
        return false;
    }

    if (bm0->config() == SkBitmap::kIndex8_Config) {
        SkColorTable* ct0 = bm0->getColorTable();
        SkColorTable* ct1 = bm1->getColorTable();
        if (NULL == ct0 || NULL == ct1) {
            return false;
        }
        if (ct0->count() != ct1->count()) {
            return false;
        }

        SkAutoLockColors alc0(ct0);
        SkAutoLockColors alc1(ct1);
        const size_t size = ct0->count() * sizeof(SkPMColor);
        if (memcmp(alc0.colors(), alc1.colors(), size) != 0) {
            return false;
        }
    }

    // now compare each scanline. We can't do the entire buffer at once,
    // since we don't care about the pixel values that might extend beyond
    // the width (since the scanline might be larger than the logical width)
    const int h = bm0->height();
    const size_t size = bm0->width() * bm0->bytesPerPixel();
    for (int y = 0; y < h; y++) {
        if (memcmp(bm0->getAddr(0, y), bm1->getAddr(0, y), size) != 0) {
            return false;
        }
    }
    return true;
}

static void Bitmap_prepareToDraw(JNIEnv* env, jobject, SkBitmap* bitmap) {
    bitmap->lockPixels();
    bitmap->unlockPixels();
}

///////////////////////////////////////////////////////////////////////////////

#include <android_runtime/AndroidRuntime.h>

static JNINativeMethod gBitmapMethods[] = {
    {   "nativeCreate",             "([IIIIIIZ)Landroid/graphics/Bitmap;",
        (void*)Bitmap_creator },
    {   "nativeCopy",               "(IIZ)Landroid/graphics/Bitmap;",
        (void*)Bitmap_copy },
    {   "nativeDestructor",         "(I)V", (void*)Bitmap_destructor },
    {   "nativeRecycle",            "(I)Z", (void*)Bitmap_recycle },
    {   "nativeReconfigure",        "(IIIII)V", (void*)Bitmap_reconfigure },
    {   "nativeCompress",           "(IIILjava/io/OutputStream;[B)Z",
        (void*)Bitmap_compress },
    {   "nativeErase",              "(II)V", (void*)Bitmap_erase },
    {   "nativeRowBytes",           "(I)I", (void*)Bitmap_rowBytes },
    {   "nativeConfig",             "(I)I", (void*)Bitmap_config },
    {   "nativeHasAlpha",           "(I)Z", (void*)Bitmap_hasAlpha },
    {   "nativeSetHasAlpha",        "(IZ)V", (void*)Bitmap_setHasAlpha },
    {   "nativeHasMipMap",          "(I)Z", (void*)Bitmap_hasMipMap },
    {   "nativeSetHasMipMap",       "(IZ)V", (void*)Bitmap_setHasMipMap },
    {   "nativeCreateFromParcel",
        "(Landroid/os/Parcel;)Landroid/graphics/Bitmap;",
        (void*)Bitmap_createFromParcel },
    {   "nativeWriteToParcel",      "(IZILandroid/os/Parcel;)Z",
        (void*)Bitmap_writeToParcel },
    {   "nativeExtractAlpha",       "(II[I)Landroid/graphics/Bitmap;",
        (void*)Bitmap_extractAlpha },
    {   "nativeGenerationId",       "(I)I", (void*)Bitmap_getGenerationId },
    {   "nativeGetPixel",           "(IIIZ)I", (void*)Bitmap_getPixel },
    {   "nativeGetPixels",          "(I[IIIIIIIZ)V", (void*)Bitmap_getPixels },
    {   "nativeSetPixel",           "(IIIIZ)V", (void*)Bitmap_setPixel },
    {   "nativeSetPixels",          "(I[IIIIIIIZ)V", (void*)Bitmap_setPixels },
    {   "nativeCopyPixelsToBuffer", "(ILjava/nio/Buffer;)V",
                                            (void*)Bitmap_copyPixelsToBuffer },
    {   "nativeCopyPixelsFromBuffer", "(ILjava/nio/Buffer;)V",
                                            (void*)Bitmap_copyPixelsFromBuffer },
    {   "nativeSameAs",             "(II)Z", (void*)Bitmap_sameAs },
    {   "nativePrepareToDraw",      "(I)V", (void*)Bitmap_prepareToDraw },
};

#define kClassPathName  "android/graphics/Bitmap"

int register_android_graphics_Bitmap(JNIEnv* env)
{
    return android::AndroidRuntime::registerNativeMethods(env, kClassPathName,
                                gBitmapMethods, SK_ARRAY_COUNT(gBitmapMethods));
}
