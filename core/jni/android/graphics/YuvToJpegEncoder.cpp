#include "CreateJavaOutputStreamAdaptor.h"
#include "SkJpegUtility.h"
#include "YuvToJpegEncoder.h"
#include <ui/PixelFormat.h>
#include <hardware/hardware.h>

#include <jni.h>

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

bool YuvToJpegEncoder::encode(SkWStream* stream, void* inYuv, int width,
        int height, int* offsets, int jpegQuality) {
    jpeg_compress_struct    cinfo;
    skjpeg_error_mgr        sk_err;
    skjpeg_destination_mgr  sk_wstream(stream);

    cinfo.err = jpeg_std_error(&sk_err);
    sk_err.error_exit = skjpeg_error_exit;
    if (setjmp(sk_err.fJmpBuf)) {
        return false;
    }
    jpeg_create_compress(&cinfo);

    cinfo.dest = &sk_wstream;

    setJpegCompressStruct(&cinfo, width, height, jpegQuality);

    jpeg_start_compress(&cinfo, TRUE);

    compress(&cinfo, (uint8_t*) inYuv, offsets);

    jpeg_finish_compress(&cinfo);

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
    SkDebugf("onFlyCompress");
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
        deinterleave(vuPlanar, uRows, vRows, cinfo->next_scanline, width);

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
        uint8_t* vRows, int rowIndex, int width) {
    for (int row = 0; row < 8; ++row) {
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
    SkDebugf("onFlyCompress_422");
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
    for (int row = 0; row < 16; ++row) {
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

static jboolean YuvImage_compressToJpeg(JNIEnv* env, jobject, jbyteArray inYuv,
        int format, int width, int height, jintArray offsets,
        jintArray strides, int jpegQuality, jobject jstream,
        jbyteArray jstorage) {
    jbyte* yuv = env->GetByteArrayElements(inYuv, NULL);
    SkWStream* strm = CreateJavaOutputStreamAdaptor(env, jstream, jstorage);

    jint* imgOffsets = env->GetIntArrayElements(offsets, NULL);
    jint* imgStrides = env->GetIntArrayElements(strides, NULL);
    YuvToJpegEncoder* encoder = YuvToJpegEncoder::create(format, imgStrides);
    if (encoder == NULL) {
        return false;
    }
    encoder->encode(strm, yuv, width, height, imgOffsets, jpegQuality);

    delete encoder;
    env->ReleaseByteArrayElements(inYuv, yuv, 0);
    env->ReleaseIntArrayElements(offsets, imgOffsets, 0);
    env->ReleaseIntArrayElements(strides, imgStrides, 0);
    return true;
}
///////////////////////////////////////////////////////////////////////////////

#include <android_runtime/AndroidRuntime.h>

static JNINativeMethod gYuvImageMethods[] = {
    {   "nativeCompressToJpeg",  "([BIII[I[IILjava/io/OutputStream;[B)Z",
        (void*)YuvImage_compressToJpeg }
};

#define kClassPathName  "android/graphics/YuvImage"

int register_android_graphics_YuvImage(JNIEnv* env)
{
    return android::AndroidRuntime::registerNativeMethods(env, kClassPathName,
            gYuvImageMethods, SK_ARRAY_COUNT(gYuvImageMethods));
}
