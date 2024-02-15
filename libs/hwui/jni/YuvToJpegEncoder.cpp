#include "CreateJavaOutputStreamAdaptor.h"
#include "SkStream.h"
#include "YuvToJpegEncoder.h"
#include <ui/PixelFormat.h>
#include <utils/Errors.h>
#include <hardware/hardware.h>

#include "graphics_jni_helpers.h"

#include <csetjmp>

extern "C" {
    // We need to include stdio.h before jpeg because jpeg does not include it, but uses FILE
    // See https://github.com/libjpeg-turbo/libjpeg-turbo/issues/17
    #include <stdio.h>
    #include "jpeglib.h"
    #include "jerror.h"
    #include "jmorecfg.h"
}

YuvToJpegEncoder* YuvToJpegEncoder::create(int format, int* strides) {
    // Only ImageFormat.NV21 and ImageFormat.YUY2 are supported
    // for now.
    if (format == HAL_PIXEL_FORMAT_YCrCb_420_SP) {
        return new Yuv420SpToJpegEncoder(strides);
    } else if (format == HAL_PIXEL_FORMAT_YCbCr_422_I) {
        return new Yuv422IToJpegEncoder(strides);
    } else {
      return NULL;
    }
}

YuvToJpegEncoder::YuvToJpegEncoder(int* strides) : fStrides(strides) {
}

struct ErrorMgr {
    struct jpeg_error_mgr pub;
    jmp_buf jmp;
};

void error_exit(j_common_ptr cinfo) {
    ErrorMgr* err = (ErrorMgr*) cinfo->err;
    (*cinfo->err->output_message) (cinfo);
    longjmp(err->jmp, 1);
}

/*
 * Destination struct for directing decompressed pixels to a SkStream.
 */
static constexpr size_t kMgrBufferSize = 1024;
struct skstream_destination_mgr : jpeg_destination_mgr {
    skstream_destination_mgr(SkWStream* stream);

    SkWStream* const fStream;

    uint8_t fBuffer[kMgrBufferSize];
};

static void sk_init_destination(j_compress_ptr cinfo) {
    skstream_destination_mgr* dest = (skstream_destination_mgr*)cinfo->dest;

    dest->next_output_byte = dest->fBuffer;
    dest->free_in_buffer = kMgrBufferSize;
}

static boolean sk_empty_output_buffer(j_compress_ptr cinfo) {
    skstream_destination_mgr* dest = (skstream_destination_mgr*)cinfo->dest;

    if (!dest->fStream->write(dest->fBuffer, kMgrBufferSize)) {
        ERREXIT(cinfo, JERR_FILE_WRITE);
        return FALSE;
    }

    dest->next_output_byte = dest->fBuffer;
    dest->free_in_buffer = kMgrBufferSize;
    return TRUE;
}

static void sk_term_destination(j_compress_ptr cinfo) {
    skstream_destination_mgr* dest = (skstream_destination_mgr*)cinfo->dest;

    size_t size = kMgrBufferSize - dest->free_in_buffer;
    if (size > 0) {
        if (!dest->fStream->write(dest->fBuffer, size)) {
            ERREXIT(cinfo, JERR_FILE_WRITE);
            return;
        }
    }

    dest->fStream->flush();
}

skstream_destination_mgr::skstream_destination_mgr(SkWStream* stream)
        : fStream(stream) {
    this->init_destination = sk_init_destination;
    this->empty_output_buffer = sk_empty_output_buffer;
    this->term_destination = sk_term_destination;
}

bool YuvToJpegEncoder::encode(SkWStream* stream, void* inYuv, int width,
        int height, int* offsets, int jpegQuality) {
    jpeg_compress_struct      cinfo;
    ErrorMgr                  err;
    skstream_destination_mgr  sk_wstream(stream);

    cinfo.err = jpeg_std_error(&err.pub);
    err.pub.error_exit = error_exit;

    if (setjmp(err.jmp)) {
        jpeg_destroy_compress(&cinfo);
        return false;
    }
    jpeg_create_compress(&cinfo);

    cinfo.dest = &sk_wstream;

    setJpegCompressStruct(&cinfo, width, height, jpegQuality);

    jpeg_start_compress(&cinfo, TRUE);

    compress(&cinfo, (uint8_t*) inYuv, offsets);

    jpeg_finish_compress(&cinfo);

    jpeg_destroy_compress(&cinfo);

    return true;
}

void YuvToJpegEncoder::setJpegCompressStruct(jpeg_compress_struct* cinfo,
        int width, int height, int quality) {
    cinfo->image_width = width;
    cinfo->image_height = height;
    cinfo->input_components = 3;
    cinfo->in_color_space = JCS_YCbCr;
    jpeg_set_defaults(cinfo);

    jpeg_set_quality(cinfo, quality, TRUE);
    jpeg_set_colorspace(cinfo, JCS_YCbCr);
    cinfo->raw_data_in = TRUE;
    cinfo->dct_method = JDCT_IFAST;
    configSamplingFactors(cinfo);
}

///////////////////////////////////////////////////////////////////
Yuv420SpToJpegEncoder::Yuv420SpToJpegEncoder(int* strides) :
        YuvToJpegEncoder(strides) {
    fNumPlanes = 2;
}

void Yuv420SpToJpegEncoder::compress(jpeg_compress_struct* cinfo,
        uint8_t* yuv, int* offsets) {
    ALOGD("onFlyCompress");
    JSAMPROW y[16];
    JSAMPROW cb[8];
    JSAMPROW cr[8];
    JSAMPARRAY planes[3];
    planes[0] = y;
    planes[1] = cb;
    planes[2] = cr;

    int width = cinfo->image_width;
    int height = cinfo->image_height;
    uint8_t* yPlanar = yuv + offsets[0];
    uint8_t* vuPlanar = yuv + offsets[1]; //width * height;
    uint8_t* uRows = new uint8_t [8 * (width >> 1)];
    uint8_t* vRows = new uint8_t [8 * (width >> 1)];


    // process 16 lines of Y and 8 lines of U/V each time.
    while (cinfo->next_scanline < cinfo->image_height) {
        //deitnerleave u and v
        deinterleave(vuPlanar, uRows, vRows, cinfo->next_scanline, width, height);

        // Jpeg library ignores the rows whose indices are greater than height.
        for (int i = 0; i < 16; i++) {
            // y row
            y[i] = yPlanar + (cinfo->next_scanline + i) * fStrides[0];

            // construct u row and v row
            if ((i & 1) == 0) {
                // height and width are both halved because of downsampling
                int offset = (i >> 1) * (width >> 1);
                cb[i/2] = uRows + offset;
                cr[i/2] = vRows + offset;
            }
          }
        jpeg_write_raw_data(cinfo, planes, 16);
    }
    delete [] uRows;
    delete [] vRows;

}

void Yuv420SpToJpegEncoder::deinterleave(uint8_t* vuPlanar, uint8_t* uRows,
        uint8_t* vRows, int rowIndex, int width, int height) {
    int numRows = (height - rowIndex) / 2;
    if (numRows > 8) numRows = 8;
    for (int row = 0; row < numRows; ++row) {
        int offset = ((rowIndex >> 1) + row) * fStrides[1];
        uint8_t* vu = vuPlanar + offset;
        for (int i = 0; i < (width >> 1); ++i) {
            int index = row * (width >> 1) + i;
            uRows[index] = vu[1];
            vRows[index] = vu[0];
            vu += 2;
        }
    }
}

void Yuv420SpToJpegEncoder::configSamplingFactors(jpeg_compress_struct* cinfo) {
    // cb and cr are horizontally downsampled and vertically downsampled as well.
    cinfo->comp_info[0].h_samp_factor = 2;
    cinfo->comp_info[0].v_samp_factor = 2;
    cinfo->comp_info[1].h_samp_factor = 1;
    cinfo->comp_info[1].v_samp_factor = 1;
    cinfo->comp_info[2].h_samp_factor = 1;
    cinfo->comp_info[2].v_samp_factor = 1;
}

///////////////////////////////////////////////////////////////////////////////
Yuv422IToJpegEncoder::Yuv422IToJpegEncoder(int* strides) :
        YuvToJpegEncoder(strides) {
    fNumPlanes = 1;
}

void Yuv422IToJpegEncoder::compress(jpeg_compress_struct* cinfo,
        uint8_t* yuv, int* offsets) {
    ALOGD("onFlyCompress_422");
    JSAMPROW y[16];
    JSAMPROW cb[16];
    JSAMPROW cr[16];
    JSAMPARRAY planes[3];
    planes[0] = y;
    planes[1] = cb;
    planes[2] = cr;

    int width = cinfo->image_width;
    int height = cinfo->image_height;
    uint8_t* yRows = new uint8_t [16 * width];
    uint8_t* uRows = new uint8_t [16 * (width >> 1)];
    uint8_t* vRows = new uint8_t [16 * (width >> 1)];

    uint8_t* yuvOffset = yuv + offsets[0];

    // process 16 lines of Y and 16 lines of U/V each time.
    while (cinfo->next_scanline < cinfo->image_height) {
        deinterleave(yuvOffset, yRows, uRows, vRows, cinfo->next_scanline, width, height);

        // Jpeg library ignores the rows whose indices are greater than height.
        for (int i = 0; i < 16; i++) {
            // y row
            y[i] = yRows + i * width;

            // construct u row and v row
            // width is halved because of downsampling
            int offset = i * (width >> 1);
            cb[i] = uRows + offset;
            cr[i] = vRows + offset;
        }

        jpeg_write_raw_data(cinfo, planes, 16);
    }
    delete [] yRows;
    delete [] uRows;
    delete [] vRows;
}


void Yuv422IToJpegEncoder::deinterleave(uint8_t* yuv, uint8_t* yRows, uint8_t* uRows,
        uint8_t* vRows, int rowIndex, int width, int height) {
    int numRows = height - rowIndex;
    if (numRows > 16) numRows = 16;
    for (int row = 0; row < numRows; ++row) {
        uint8_t* yuvSeg = yuv + (rowIndex + row) * fStrides[0];
        for (int i = 0; i < (width >> 1); ++i) {
            int indexY = row * width + (i << 1);
            int indexU = row * (width >> 1) + i;
            yRows[indexY] = yuvSeg[0];
            yRows[indexY + 1] = yuvSeg[2];
            uRows[indexU] = yuvSeg[1];
            vRows[indexU] = yuvSeg[3];
            yuvSeg += 4;
        }
    }
}

void Yuv422IToJpegEncoder::configSamplingFactors(jpeg_compress_struct* cinfo) {
    // cb and cr are horizontally downsampled and vertically downsampled as well.
    cinfo->comp_info[0].h_samp_factor = 2;
    cinfo->comp_info[0].v_samp_factor = 2;
    cinfo->comp_info[1].h_samp_factor = 1;
    cinfo->comp_info[1].v_samp_factor = 2;
    cinfo->comp_info[2].h_samp_factor = 1;
    cinfo->comp_info[2].v_samp_factor = 2;
}
///////////////////////////////////////////////////////////////////////////////

using namespace ultrahdr;

ultrahdr_color_gamut P010Yuv420ToJpegREncoder::findColorGamut(JNIEnv* env, int aDataSpace) {
    switch (aDataSpace & ADataSpace::STANDARD_MASK) {
        case ADataSpace::STANDARD_BT709:
            return ultrahdr_color_gamut::ULTRAHDR_COLORGAMUT_BT709;
        case ADataSpace::STANDARD_DCI_P3:
            return ultrahdr_color_gamut::ULTRAHDR_COLORGAMUT_P3;
        case ADataSpace::STANDARD_BT2020:
            return ultrahdr_color_gamut::ULTRAHDR_COLORGAMUT_BT2100;
        default:
            jclass IllegalArgumentException = env->FindClass("java/lang/IllegalArgumentException");
            env->ThrowNew(IllegalArgumentException,
                    "The requested color gamut is not supported by JPEG/R.");
    }

    return ultrahdr_color_gamut::ULTRAHDR_COLORGAMUT_UNSPECIFIED;
}

ultrahdr_transfer_function P010Yuv420ToJpegREncoder::findHdrTransferFunction(JNIEnv* env,
        int aDataSpace) {
    switch (aDataSpace & ADataSpace::TRANSFER_MASK) {
        case ADataSpace::TRANSFER_ST2084:
            return ultrahdr_transfer_function::ULTRAHDR_TF_PQ;
        case ADataSpace::TRANSFER_HLG:
            return ultrahdr_transfer_function::ULTRAHDR_TF_HLG;
        default:
            jclass IllegalArgumentException = env->FindClass("java/lang/IllegalArgumentException");
            env->ThrowNew(IllegalArgumentException,
                    "The requested HDR transfer function is not supported by JPEG/R.");
    }

    return ultrahdr_transfer_function::ULTRAHDR_TF_UNSPECIFIED;
}

bool P010Yuv420ToJpegREncoder::encode(JNIEnv* env,
        SkWStream* stream, void* hdr, int hdrColorSpace, void* sdr, int sdrColorSpace,
        int width, int height, int jpegQuality, ScopedByteArrayRO* jExif,
        ScopedIntArrayRO* jHdrStrides, ScopedIntArrayRO* jSdrStrides) {
    // Check SDR color space. Now we only support SRGB transfer function
    if ((sdrColorSpace & ADataSpace::TRANSFER_MASK) !=  ADataSpace::TRANSFER_SRGB) {
        jclass IllegalArgumentException = env->FindClass("java/lang/IllegalArgumentException");
        env->ThrowNew(IllegalArgumentException,
            "The requested SDR color space is not supported. Transfer function must be SRGB");
        return false;
    }
    // Check HDR and SDR strides length.
    // HDR is YCBCR_P010 color format, and its strides length must be 2 (Y, chroma (Cb, Cr)).
    // SDR is YUV_420_888 color format, and its strides length must be 3 (Y, Cb, Cr).
    if (jHdrStrides->size() != 2) {
        jclass IllegalArgumentException = env->FindClass("java/lang/IllegalArgumentException");
        env->ThrowNew(IllegalArgumentException, "HDR stride length must be 2.");
        return false;
    }
    if (jSdrStrides->size() != 3) {
        jclass IllegalArgumentException = env->FindClass("java/lang/IllegalArgumentException");
        env->ThrowNew(IllegalArgumentException, "SDR stride length must be 3.");
        return false;
    }

    ultrahdr_color_gamut hdrColorGamut = findColorGamut(env, hdrColorSpace);
    ultrahdr_color_gamut sdrColorGamut = findColorGamut(env, sdrColorSpace);
    ultrahdr_transfer_function hdrTransferFunction = findHdrTransferFunction(env, hdrColorSpace);

    if (hdrColorGamut == ultrahdr_color_gamut::ULTRAHDR_COLORGAMUT_UNSPECIFIED
            || sdrColorGamut == ultrahdr_color_gamut::ULTRAHDR_COLORGAMUT_UNSPECIFIED
            || hdrTransferFunction == ultrahdr_transfer_function::ULTRAHDR_TF_UNSPECIFIED) {
        return false;
    }

    const int* hdrStrides = reinterpret_cast<const int*>(jHdrStrides->get());
    const int* sdrStrides = reinterpret_cast<const int*>(jSdrStrides->get());

    JpegR jpegREncoder;

    jpegr_uncompressed_struct p010;
    p010.data = hdr;
    p010.width = width;
    p010.height = height;
    // Divided by 2 because unit in libultrader is pixel and in YuvImage it is byte.
    p010.luma_stride = (hdrStrides[0] + 1) / 2;
    p010.chroma_stride = (hdrStrides[1] + 1) / 2;
    p010.colorGamut = hdrColorGamut;

    jpegr_uncompressed_struct yuv420;
    yuv420.data = sdr;
    yuv420.width = width;
    yuv420.height = height;
    yuv420.luma_stride = sdrStrides[0];
    yuv420.chroma_stride = sdrStrides[1];
    yuv420.colorGamut = sdrColorGamut;

    jpegr_exif_struct exif;
    exif.data = const_cast<void*>(reinterpret_cast<const void*>(jExif->get()));
    exif.length = jExif->size();

    jpegr_compressed_struct jpegR;
    jpegR.maxLength = width * height * sizeof(uint8_t);

    std::unique_ptr<uint8_t[]> jpegr_data = std::make_unique<uint8_t[]>(jpegR.maxLength);
    jpegR.data = jpegr_data.get();

    if (int success = jpegREncoder.encodeJPEGR(&p010, &yuv420,
            hdrTransferFunction,
            &jpegR, jpegQuality,
            exif.length > 0 ? &exif : NULL); success != JPEGR_NO_ERROR) {
        ALOGW("Encode JPEG/R failed, error code: %d.", success);
        return false;
    }

    if (!stream->write(jpegR.data, jpegR.length)) {
        ALOGW("Writing JPEG/R to stream failed.");
        return false;
    }

    return true;
}

///////////////////////////////////////////////////////////////////////////////

static jboolean YuvImage_compressToJpeg(JNIEnv* env, jobject, jbyteArray inYuv,
        jint format, jint width, jint height, jintArray offsets,
        jintArray strides, jint jpegQuality, jobject jstream,
        jbyteArray jstorage) {
    jbyte* yuv = env->GetByteArrayElements(inYuv, NULL);
    SkWStream* strm = CreateJavaOutputStreamAdaptor(env, jstream, jstorage);

    jint* imgOffsets = env->GetIntArrayElements(offsets, NULL);
    jint* imgStrides = env->GetIntArrayElements(strides, NULL);
    YuvToJpegEncoder* encoder = YuvToJpegEncoder::create(format, imgStrides);
    jboolean result = JNI_FALSE;
    if (encoder != NULL) {
        encoder->encode(strm, yuv, width, height, imgOffsets, jpegQuality);
        delete encoder;
        result = JNI_TRUE;
    }

    env->ReleaseByteArrayElements(inYuv, yuv, 0);
    env->ReleaseIntArrayElements(offsets, imgOffsets, 0);
    env->ReleaseIntArrayElements(strides, imgStrides, 0);
    delete strm;
    return result;
}

static jboolean YuvImage_compressToJpegR(JNIEnv* env, jobject, jbyteArray inHdr,
        jint hdrColorSpace, jbyteArray inSdr, jint sdrColorSpace,
        jint width, jint height, jint quality, jobject jstream,
        jbyteArray jstorage, jbyteArray jExif,
        jintArray jHdrStrides, jintArray jSdrStrides) {
    jbyte* hdr = env->GetByteArrayElements(inHdr, NULL);
    jbyte* sdr = env->GetByteArrayElements(inSdr, NULL);
    ScopedByteArrayRO exif(env, jExif);
    ScopedIntArrayRO hdrStrides(env, jHdrStrides);
    ScopedIntArrayRO sdrStrides(env, jSdrStrides);

    SkWStream* strm = CreateJavaOutputStreamAdaptor(env, jstream, jstorage);
    P010Yuv420ToJpegREncoder encoder;

    jboolean result = JNI_FALSE;
    if (encoder.encode(env, strm, hdr, hdrColorSpace, sdr, sdrColorSpace,
                       width, height, quality, &exif,
                       &hdrStrides, &sdrStrides)) {
        result = JNI_TRUE;
    }

    env->ReleaseByteArrayElements(inHdr, hdr, 0);
    env->ReleaseByteArrayElements(inSdr, sdr, 0);

    delete strm;
    return result;
}
///////////////////////////////////////////////////////////////////////////////

static const JNINativeMethod gYuvImageMethods[] = {
    {   "nativeCompressToJpeg",  "([BIII[I[IILjava/io/OutputStream;[B)Z",
        (void*)YuvImage_compressToJpeg },
    {   "nativeCompressToJpegR",  "([BI[BIIIILjava/io/OutputStream;[B[B[I[I)Z",
        (void*)YuvImage_compressToJpegR }
};

int register_android_graphics_YuvImage(JNIEnv* env)
{
    return android::RegisterMethodsOrDie(env, "android/graphics/YuvImage", gYuvImageMethods,
                                         NELEM(gYuvImageMethods));
}
