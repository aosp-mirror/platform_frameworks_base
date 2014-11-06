#include "Paint.h"
#include "SkBitmap.h"
#include "SkPixelRef.h"
#include "SkImageEncoder.h"
#include "SkImageInfo.h"
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

#include <ResourceCache.h>

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
static FromColorProc ChooseFromColorProc(const SkBitmap& bitmap) {
    switch (bitmap.colorType()) {
        case kN32_SkColorType:
            return bitmap.alphaType() == kPremul_SkAlphaType ? FromColor_D32 : FromColor_D32_Raw;
        case kARGB_4444_SkColorType:
            return bitmap.alphaType() == kPremul_SkAlphaType ? FromColor_D4444 :
                    FromColor_D4444_Raw;
        case kRGB_565_SkColorType:
            return FromColor_D565;
        default:
            break;
    }
    return NULL;
}

bool GraphicsJNI::SetPixels(JNIEnv* env, jintArray srcColors, int srcOffset, int srcStride,
        int x, int y, int width, int height, const SkBitmap& dstBitmap) {
    SkAutoLockPixels alp(dstBitmap);
    void* dst = dstBitmap.getPixels();
    FromColorProc proc = ChooseFromColorProc(dstBitmap);

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
    ctable->unlockColors();
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
    ctable->unlockColors();
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
    ctable->unlockColors();
}

// can return NULL
static ToColorProc ChooseToColorProc(const SkBitmap& src) {
    switch (src.colorType()) {
        case kN32_SkColorType:
            switch (src.alphaType()) {
                case kOpaque_SkAlphaType:
                    return ToColor_S32_Opaque;
                case kPremul_SkAlphaType:
                    return ToColor_S32_Alpha;
                case kUnpremul_SkAlphaType:
                    return ToColor_S32_Raw;
                default:
                    return NULL;
            }
        case kARGB_4444_SkColorType:
            switch (src.alphaType()) {
                case kOpaque_SkAlphaType:
                    return ToColor_S4444_Opaque;
                case kPremul_SkAlphaType:
                    return ToColor_S4444_Alpha;
                case kUnpremul_SkAlphaType:
                    return ToColor_S4444_Raw;
                default:
                    return NULL;
            }
        case kRGB_565_SkColorType:
            return ToColor_S565;
        case kIndex_8_SkColorType:
            if (src.getColorTable() == NULL) {
                return NULL;
            }
            switch (src.alphaType()) {
                case kOpaque_SkAlphaType:
                    return ToColor_SI8_Opaque;
                case kPremul_SkAlphaType:
                    return ToColor_SI8_Alpha;
                case kUnpremul_SkAlphaType:
                    return ToColor_SI8_Raw;
                default:
                    return NULL;
            }
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
                              jint offset, jint stride, jint width, jint height,
                              jint configHandle, jboolean isMutable) {
    SkColorType colorType = GraphicsJNI::legacyBitmapConfigToColorType(configHandle);
    if (NULL != jColors) {
        size_t n = env->GetArrayLength(jColors);
        if (n < SkAbs32(stride) * (size_t)height) {
            doThrowAIOOBE(env);
            return NULL;
        }
    }

    // ARGB_4444 is a deprecated format, convert automatically to 8888
    if (colorType == kARGB_4444_SkColorType) {
        colorType = kN32_SkColorType;
    }

    SkBitmap bitmap;
    bitmap.setInfo(SkImageInfo::Make(width, height, colorType, kPremul_SkAlphaType));

    jbyteArray buff = GraphicsJNI::allocateJavaPixelRef(env, &bitmap, NULL);
    if (NULL == buff) {
        return NULL;
    }

    if (jColors != NULL) {
        GraphicsJNI::SetPixels(env, jColors, offset, stride,
                0, 0, width, height, bitmap);
    }

    return GraphicsJNI::createBitmap(env, new SkBitmap(bitmap), buff,
            getPremulBitmapCreateFlags(isMutable), NULL, NULL);
}

static jobject Bitmap_copy(JNIEnv* env, jobject, jlong srcHandle,
                           jint dstConfigHandle, jboolean isMutable) {
    const SkBitmap* src = reinterpret_cast<SkBitmap*>(srcHandle);
    SkColorType dstCT = GraphicsJNI::legacyBitmapConfigToColorType(dstConfigHandle);
    SkBitmap            result;
    JavaPixelAllocator  allocator(env);

    if (!src->copyTo(&result, dstCT, &allocator)) {
        return NULL;
    }
    return GraphicsJNI::createBitmap(env, new SkBitmap(result), allocator.getStorageObj(),
            getPremulBitmapCreateFlags(isMutable), NULL, NULL);
}

static void Bitmap_destructor(JNIEnv* env, jobject, jlong bitmapHandle) {
    SkBitmap* bitmap = reinterpret_cast<SkBitmap*>(bitmapHandle);
#ifdef USE_OPENGL_RENDERER
    if (android::uirenderer::ResourceCache::hasInstance()) {
        android::uirenderer::ResourceCache::getInstance().destructor(bitmap);
        return;
    }
#endif // USE_OPENGL_RENDERER
    delete bitmap;
}

static jboolean Bitmap_recycle(JNIEnv* env, jobject, jlong bitmapHandle) {
    SkBitmap* bitmap = reinterpret_cast<SkBitmap*>(bitmapHandle);
#ifdef USE_OPENGL_RENDERER
    if (android::uirenderer::ResourceCache::hasInstance()) {
        bool result;
        result = android::uirenderer::ResourceCache::getInstance().recycle(bitmap);
        return result ? JNI_TRUE : JNI_FALSE;
    }
#endif // USE_OPENGL_RENDERER
    bitmap->setPixels(NULL, NULL);
    return JNI_TRUE;
}

static void Bitmap_reconfigure(JNIEnv* env, jobject clazz, jlong bitmapHandle,
        jint width, jint height, jint configHandle, jint allocSize,
        jboolean requestPremul) {
    SkBitmap* bitmap = reinterpret_cast<SkBitmap*>(bitmapHandle);
    SkColorType colorType = GraphicsJNI::legacyBitmapConfigToColorType(configHandle);

    // ARGB_4444 is a deprecated format, convert automatically to 8888
    if (colorType == kARGB_4444_SkColorType) {
        colorType = kN32_SkColorType;
    }

    if (width * height * SkColorTypeBytesPerPixel(colorType) > allocSize) {
        // done in native as there's no way to get BytesPerPixel in Java
        doThrowIAE(env, "Bitmap not large enough to support new configuration");
        return;
    }
    SkPixelRef* ref = bitmap->pixelRef();
    ref->ref();
    SkAlphaType alphaType;
    if (bitmap->colorType() != kRGB_565_SkColorType
            && bitmap->alphaType() == kOpaque_SkAlphaType) {
        // If the original bitmap was set to opaque, keep that setting, unless it
        // was 565, which is required to be opaque.
        alphaType = kOpaque_SkAlphaType;
    } else {
        // Otherwise respect the premultiplied request.
        alphaType = requestPremul ? kPremul_SkAlphaType : kUnpremul_SkAlphaType;
    }
    bitmap->setInfo(SkImageInfo::Make(width, height, colorType, alphaType));
    // FIXME: Skia thinks of an SkPixelRef as having a constant SkImageInfo (except for
    // its alphatype), so it would make more sense from Skia's perspective to create a
    // new SkPixelRef. That said, libhwui uses the pointer to the SkPixelRef as a key
    // for its cache, so it won't realize this is the same Java Bitmap.
    SkImageInfo& info = const_cast<SkImageInfo&>(ref->info());
    // Use the updated from the SkBitmap, which may have corrected an invalid alphatype.
    // (e.g. 565 non-opaque)
    info = bitmap->info();
    bitmap->setPixelRef(ref);

    // notifyPixelsChanged will increment the generation ID even though the actual pixel data
    // hasn't been touched. This signals the renderer that the bitmap (including width, height,
    // colortype and alphatype) has changed.
    ref->notifyPixelsChanged();
    ref->unref();
}

// These must match the int values in Bitmap.java
enum JavaEncodeFormat {
    kJPEG_JavaEncodeFormat = 0,
    kPNG_JavaEncodeFormat = 1,
    kWEBP_JavaEncodeFormat = 2
};

static jboolean Bitmap_compress(JNIEnv* env, jobject clazz, jlong bitmapHandle,
                                jint format, jint quality,
                                jobject jstream, jbyteArray jstorage) {
    SkBitmap* bitmap = reinterpret_cast<SkBitmap*>(bitmapHandle);
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
        return JNI_FALSE;
    }

    bool success = false;
    if (NULL != bitmap) {
        SkAutoLockPixels alp(*bitmap);

        if (NULL == bitmap->getPixels()) {
            return JNI_FALSE;
        }

        SkWStream* strm = CreateJavaOutputStreamAdaptor(env, jstream, jstorage);
        if (NULL == strm) {
            return JNI_FALSE;
        }

        SkImageEncoder* encoder = SkImageEncoder::Create(fm);
        if (NULL != encoder) {
            success = encoder->encodeStream(strm, *bitmap, quality);
            delete encoder;
        }
        delete strm;
    }
    return success ? JNI_TRUE : JNI_FALSE;
}

static void Bitmap_erase(JNIEnv* env, jobject, jlong bitmapHandle, jint color) {
    SkBitmap* bitmap = reinterpret_cast<SkBitmap*>(bitmapHandle);
    bitmap->eraseColor(color);
}

static jint Bitmap_rowBytes(JNIEnv* env, jobject, jlong bitmapHandle) {
    SkBitmap* bitmap = reinterpret_cast<SkBitmap*>(bitmapHandle);
    return static_cast<jint>(bitmap->rowBytes());
}

static jint Bitmap_config(JNIEnv* env, jobject, jlong bitmapHandle) {
    SkBitmap* bitmap = reinterpret_cast<SkBitmap*>(bitmapHandle);
    return GraphicsJNI::colorTypeToLegacyBitmapConfig(bitmap->colorType());
}

static jint Bitmap_getGenerationId(JNIEnv* env, jobject, jlong bitmapHandle) {
    SkBitmap* bitmap = reinterpret_cast<SkBitmap*>(bitmapHandle);
    return static_cast<jint>(bitmap->getGenerationID());
}

static jboolean Bitmap_isPremultiplied(JNIEnv* env, jobject, jlong bitmapHandle) {
    SkBitmap* bitmap = reinterpret_cast<SkBitmap*>(bitmapHandle);
    if (bitmap->alphaType() == kPremul_SkAlphaType) {
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

static jboolean Bitmap_hasAlpha(JNIEnv* env, jobject, jlong bitmapHandle) {
    SkBitmap* bitmap = reinterpret_cast<SkBitmap*>(bitmapHandle);
    return !bitmap->isOpaque() ? JNI_TRUE : JNI_FALSE;
}

static void Bitmap_setHasAlpha(JNIEnv* env, jobject, jlong bitmapHandle,
        jboolean hasAlpha, jboolean requestPremul) {
    SkBitmap* bitmap = reinterpret_cast<SkBitmap*>(bitmapHandle);
    if (hasAlpha) {
        bitmap->setAlphaType(requestPremul ? kPremul_SkAlphaType : kUnpremul_SkAlphaType);
    } else {
        bitmap->setAlphaType(kOpaque_SkAlphaType);
    }
}

static void Bitmap_setPremultiplied(JNIEnv* env, jobject, jlong bitmapHandle,
        jboolean isPremul) {
    SkBitmap* bitmap = reinterpret_cast<SkBitmap*>(bitmapHandle);
    if (!bitmap->isOpaque()) {
        if (isPremul) {
            bitmap->setAlphaType(kPremul_SkAlphaType);
        } else {
            bitmap->setAlphaType(kUnpremul_SkAlphaType);
        }
    }
}

static jboolean Bitmap_hasMipMap(JNIEnv* env, jobject, jlong bitmapHandle) {
    SkBitmap* bitmap = reinterpret_cast<SkBitmap*>(bitmapHandle);
    return bitmap->hasHardwareMipMap() ? JNI_TRUE : JNI_FALSE;
}

static void Bitmap_setHasMipMap(JNIEnv* env, jobject, jlong bitmapHandle,
                                jboolean hasMipMap) {
    SkBitmap* bitmap = reinterpret_cast<SkBitmap*>(bitmapHandle);
    bitmap->setHasHardwareMipMap(hasMipMap);
}

///////////////////////////////////////////////////////////////////////////////

static jobject Bitmap_createFromParcel(JNIEnv* env, jobject, jobject parcel) {
    if (parcel == NULL) {
        SkDebugf("-------- unparcel parcel is NULL\n");
        return NULL;
    }

    android::Parcel* p = android::parcelForJavaObject(env, parcel);

    const bool        isMutable = p->readInt32() != 0;
    const SkColorType colorType = (SkColorType)p->readInt32();
    const SkAlphaType alphaType = (SkAlphaType)p->readInt32();
    const int         width = p->readInt32();
    const int         height = p->readInt32();
    const int         rowBytes = p->readInt32();
    const int         density = p->readInt32();

    if (kN32_SkColorType != colorType &&
            kRGB_565_SkColorType != colorType &&
            kARGB_4444_SkColorType != colorType &&
            kIndex_8_SkColorType != colorType &&
            kAlpha_8_SkColorType != colorType) {
        SkDebugf("Bitmap_createFromParcel unknown colortype: %d\n", colorType);
        return NULL;
    }

    SkBitmap* bitmap = new SkBitmap;

    bitmap->setInfo(SkImageInfo::Make(width, height, colorType, alphaType), rowBytes);

    SkColorTable* ctable = NULL;
    if (colorType == kIndex_8_SkColorType) {
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
                                     jlong bitmapHandle,
                                     jboolean isMutable, jint density,
                                     jobject parcel) {
    const SkBitmap* bitmap = reinterpret_cast<SkBitmap*>(bitmapHandle);
    if (parcel == NULL) {
        SkDebugf("------- writeToParcel null parcel\n");
        return JNI_FALSE;
    }

    android::Parcel* p = android::parcelForJavaObject(env, parcel);

    p->writeInt32(isMutable);
    p->writeInt32(bitmap->colorType());
    p->writeInt32(bitmap->alphaType());
    p->writeInt32(bitmap->width());
    p->writeInt32(bitmap->height());
    p->writeInt32(bitmap->rowBytes());
    p->writeInt32(density);

    if (bitmap->colorType() == kIndex_8_SkColorType) {
        SkColorTable* ctable = bitmap->getColorTable();
        if (ctable != NULL) {
            int count = ctable->count();
            p->writeInt32(count);
            memcpy(p->writeInplace(count * sizeof(SkPMColor)),
                   ctable->lockColors(), count * sizeof(SkPMColor));
            ctable->unlockColors();
        } else {
            p->writeInt32(0);   // indicate no ctable
        }
    }

    size_t size = bitmap->getSize();

    android::Parcel::WritableBlob blob;
    android::status_t status = p->writeBlob(size, &blob);
    if (status) {
        doThrowRE(env, "Could not write bitmap to parcel blob.");
        return JNI_FALSE;
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
    return JNI_TRUE;
}

static jobject Bitmap_extractAlpha(JNIEnv* env, jobject clazz,
                                   jlong srcHandle, jlong paintHandle,
                                   jintArray offsetXY) {
    const SkBitmap* src = reinterpret_cast<SkBitmap*>(srcHandle);
    const android::Paint* paint = reinterpret_cast<android::Paint*>(paintHandle);
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
            getPremulBitmapCreateFlags(true), NULL, NULL);
}

///////////////////////////////////////////////////////////////////////////////

static jint Bitmap_getPixel(JNIEnv* env, jobject, jlong bitmapHandle,
        jint x, jint y) {
    const SkBitmap* bitmap = reinterpret_cast<SkBitmap*>(bitmapHandle);
    SkAutoLockPixels alp(*bitmap);

    ToColorProc proc = ChooseToColorProc(*bitmap);
    if (NULL == proc) {
        return 0;
    }
    const void* src = bitmap->getAddr(x, y);
    if (NULL == src) {
        return 0;
    }

    SkColor dst[1];
    proc(dst, src, 1, bitmap->getColorTable());
    return static_cast<jint>(dst[0]);
}

static void Bitmap_getPixels(JNIEnv* env, jobject, jlong bitmapHandle,
        jintArray pixelArray, jint offset, jint stride,
        jint x, jint y, jint width, jint height) {
    const SkBitmap* bitmap = reinterpret_cast<SkBitmap*>(bitmapHandle);
    SkAutoLockPixels alp(*bitmap);

    ToColorProc proc = ChooseToColorProc(*bitmap);
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

static void Bitmap_setPixel(JNIEnv* env, jobject, jlong bitmapHandle,
        jint x, jint y, jint colorHandle) {
    const SkBitmap* bitmap = reinterpret_cast<SkBitmap*>(bitmapHandle);
    SkColor color = static_cast<SkColor>(colorHandle);
    SkAutoLockPixels alp(*bitmap);
    if (NULL == bitmap->getPixels()) {
        return;
    }

    FromColorProc proc = ChooseFromColorProc(*bitmap);
    if (NULL == proc) {
        return;
    }

    proc(bitmap->getAddr(x, y), &color, 1, x, y);
    bitmap->notifyPixelsChanged();
}

static void Bitmap_setPixels(JNIEnv* env, jobject, jlong bitmapHandle,
        jintArray pixelArray, jint offset, jint stride,
        jint x, jint y, jint width, jint height) {
    const SkBitmap* bitmap = reinterpret_cast<SkBitmap*>(bitmapHandle);
    GraphicsJNI::SetPixels(env, pixelArray, offset, stride,
            x, y, width, height, *bitmap);
}

static void Bitmap_copyPixelsToBuffer(JNIEnv* env, jobject,
                                      jlong bitmapHandle, jobject jbuffer) {
    const SkBitmap* bitmap = reinterpret_cast<SkBitmap*>(bitmapHandle);
    SkAutoLockPixels alp(*bitmap);
    const void* src = bitmap->getPixels();

    if (NULL != src) {
        android::AutoBufferPointer abp(env, jbuffer, JNI_TRUE);

        // the java side has already checked that buffer is large enough
        memcpy(abp.pointer(), src, bitmap->getSize());
    }
}

static void Bitmap_copyPixelsFromBuffer(JNIEnv* env, jobject,
                                        jlong bitmapHandle, jobject jbuffer) {
    SkBitmap* bitmap = reinterpret_cast<SkBitmap*>(bitmapHandle);
    SkAutoLockPixels alp(*bitmap);
    void* dst = bitmap->getPixels();

    if (NULL != dst) {
        android::AutoBufferPointer abp(env, jbuffer, JNI_FALSE);
        // the java side has already checked that buffer is large enough
        memcpy(dst, abp.pointer(), bitmap->getSize());
        bitmap->notifyPixelsChanged();
    }
}

static jboolean Bitmap_sameAs(JNIEnv* env, jobject, jlong bm0Handle,
                              jlong bm1Handle) {
    const SkBitmap* bm0 = reinterpret_cast<SkBitmap*>(bm0Handle);
    const SkBitmap* bm1 = reinterpret_cast<SkBitmap*>(bm1Handle);
    if (bm0->width() != bm1->width() ||
        bm0->height() != bm1->height() ||
        bm0->colorType() != bm1->colorType()) {
        return JNI_FALSE;
    }

    SkAutoLockPixels alp0(*bm0);
    SkAutoLockPixels alp1(*bm1);

    // if we can't load the pixels, return false
    if (NULL == bm0->getPixels() || NULL == bm1->getPixels()) {
        return JNI_FALSE;
    }

    if (bm0->colorType() == kIndex_8_SkColorType) {
        SkColorTable* ct0 = bm0->getColorTable();
        SkColorTable* ct1 = bm1->getColorTable();
        if (NULL == ct0 || NULL == ct1) {
            return JNI_FALSE;
        }
        if (ct0->count() != ct1->count()) {
            return JNI_FALSE;
        }

        SkAutoLockColors alc0(ct0);
        SkAutoLockColors alc1(ct1);
        const size_t size = ct0->count() * sizeof(SkPMColor);
        if (memcmp(alc0.colors(), alc1.colors(), size) != 0) {
            return JNI_FALSE;
        }
    }

    // now compare each scanline. We can't do the entire buffer at once,
    // since we don't care about the pixel values that might extend beyond
    // the width (since the scanline might be larger than the logical width)
    const int h = bm0->height();
    const size_t size = bm0->width() * bm0->bytesPerPixel();
    for (int y = 0; y < h; y++) {
        // SkBitmap::getAddr(int, int) may return NULL due to unrecognized config
        // (ex: kRLE_Index8_Config). This will cause memcmp method to crash. Since bm0
        // and bm1 both have pixel data() (have passed NULL == getPixels() check),
        // those 2 bitmaps should be valid (only unrecognized), we return JNI_FALSE
        // to warn user those 2 unrecognized config bitmaps may be different.
        void *bm0Addr = bm0->getAddr(0, y);
        void *bm1Addr = bm1->getAddr(0, y);

        if(bm0Addr == NULL || bm1Addr == NULL) {
            return JNI_FALSE;
        }

        if (memcmp(bm0Addr, bm1Addr, size) != 0) {
            return JNI_FALSE;
        }
    }
    return JNI_TRUE;
}

static void Bitmap_prepareToDraw(JNIEnv* env, jobject, jlong bitmapHandle) {
    SkBitmap* bitmap = reinterpret_cast<SkBitmap*>(bitmapHandle);
    bitmap->lockPixels();
    bitmap->unlockPixels();
}

///////////////////////////////////////////////////////////////////////////////

#include <android_runtime/AndroidRuntime.h>

static JNINativeMethod gBitmapMethods[] = {
    {   "nativeCreate",             "([IIIIIIZ)Landroid/graphics/Bitmap;",
        (void*)Bitmap_creator },
    {   "nativeCopy",               "(JIZ)Landroid/graphics/Bitmap;",
        (void*)Bitmap_copy },
    {   "nativeDestructor",         "(J)V", (void*)Bitmap_destructor },
    {   "nativeRecycle",            "(J)Z", (void*)Bitmap_recycle },
    {   "nativeReconfigure",        "(JIIIIZ)V", (void*)Bitmap_reconfigure },
    {   "nativeCompress",           "(JIILjava/io/OutputStream;[B)Z",
        (void*)Bitmap_compress },
    {   "nativeErase",              "(JI)V", (void*)Bitmap_erase },
    {   "nativeRowBytes",           "(J)I", (void*)Bitmap_rowBytes },
    {   "nativeConfig",             "(J)I", (void*)Bitmap_config },
    {   "nativeHasAlpha",           "(J)Z", (void*)Bitmap_hasAlpha },
    {   "nativeIsPremultiplied",    "(J)Z", (void*)Bitmap_isPremultiplied},
    {   "nativeSetHasAlpha",        "(JZZ)V", (void*)Bitmap_setHasAlpha},
    {   "nativeSetPremultiplied",   "(JZ)V", (void*)Bitmap_setPremultiplied},
    {   "nativeHasMipMap",          "(J)Z", (void*)Bitmap_hasMipMap },
    {   "nativeSetHasMipMap",       "(JZ)V", (void*)Bitmap_setHasMipMap },
    {   "nativeCreateFromParcel",
        "(Landroid/os/Parcel;)Landroid/graphics/Bitmap;",
        (void*)Bitmap_createFromParcel },
    {   "nativeWriteToParcel",      "(JZILandroid/os/Parcel;)Z",
        (void*)Bitmap_writeToParcel },
    {   "nativeExtractAlpha",       "(JJ[I)Landroid/graphics/Bitmap;",
        (void*)Bitmap_extractAlpha },
    {   "nativeGenerationId",       "(J)I", (void*)Bitmap_getGenerationId },
    {   "nativeGetPixel",           "(JII)I", (void*)Bitmap_getPixel },
    {   "nativeGetPixels",          "(J[IIIIIII)V", (void*)Bitmap_getPixels },
    {   "nativeSetPixel",           "(JIII)V", (void*)Bitmap_setPixel },
    {   "nativeSetPixels",          "(J[IIIIIII)V", (void*)Bitmap_setPixels },
    {   "nativeCopyPixelsToBuffer", "(JLjava/nio/Buffer;)V",
                                            (void*)Bitmap_copyPixelsToBuffer },
    {   "nativeCopyPixelsFromBuffer", "(JLjava/nio/Buffer;)V",
                                            (void*)Bitmap_copyPixelsFromBuffer },
    {   "nativeSameAs",             "(JJ)Z", (void*)Bitmap_sameAs },
    {   "nativePrepareToDraw",      "(J)V", (void*)Bitmap_prepareToDraw },
};

#define kClassPathName  "android/graphics/Bitmap"

int register_android_graphics_Bitmap(JNIEnv* env)
{
    return android::AndroidRuntime::registerNativeMethods(env, kClassPathName,
                                gBitmapMethods, SK_ARRAY_COUNT(gBitmapMethods));
}
