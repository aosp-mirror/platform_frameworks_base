#ifndef _ANDROID_GRAPHICS_YUV_TO_JPEG_ENCODER_H_
#define _ANDROID_GRAPHICS_YUV_TO_JPEG_ENCODER_H_

#include <android/data_space.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <ultrahdr/jpegr.h>

extern "C" {
    #include "jpeglib.h"
    #include "jerror.h"
}

class SkWStream;

class YuvToJpegEncoder {
public:
    /** Create an encoder based on the YUV format.
     *
     *  @param pixelFormat The yuv pixel format as defined in ui/PixelFormat.h.
     *  @param strides The number of row bytes in each image plane.
     *  @return an encoder based on the pixelFormat.
     */
    static YuvToJpegEncoder* create(int pixelFormat, int* strides);

    explicit YuvToJpegEncoder(int* strides);

    /** Encode YUV data to jpeg,  which is output to a stream.
     *
     *  @param stream The jpeg output stream.
     *  @param inYuv The input yuv data.
     *  @param width Width of the Yuv data in terms of pixels.
     *  @param height Height of the Yuv data in terms of pixels.
     *  @param offsets The offsets in each image plane with respect to inYuv.
     *  @param jpegQuality Picture quality in [0, 100].
     *  @return true if successfully compressed the stream.
     */
    bool encode(SkWStream* stream,  void* inYuv, int width,
           int height, int* offsets, int jpegQuality);

    virtual ~YuvToJpegEncoder() {}

protected:
    int fNumPlanes;
    int* fStrides;
    void setJpegCompressStruct(jpeg_compress_struct* cinfo, int width,
            int height, int quality);
    virtual void configSamplingFactors(jpeg_compress_struct* cinfo) = 0;
    virtual void compress(jpeg_compress_struct* cinfo,
            uint8_t* yuv, int* offsets) = 0;
};

class Yuv420SpToJpegEncoder : public YuvToJpegEncoder {
public:
    explicit Yuv420SpToJpegEncoder(int* strides);
    virtual ~Yuv420SpToJpegEncoder() {}

private:
    void configSamplingFactors(jpeg_compress_struct* cinfo);
    void deinterleaveYuv(uint8_t* yuv, int width, int height,
            uint8_t*& yPlanar, uint8_t*& uPlanar, uint8_t*& vPlanar);
    void deinterleave(uint8_t* vuPlanar, uint8_t* uRows, uint8_t* vRows,
            int rowIndex, int width, int height);
    void compress(jpeg_compress_struct* cinfo, uint8_t* yuv, int* offsets);
};

class Yuv422IToJpegEncoder : public YuvToJpegEncoder {
public:
    explicit Yuv422IToJpegEncoder(int* strides);
    virtual ~Yuv422IToJpegEncoder() {}

private:
    void configSamplingFactors(jpeg_compress_struct* cinfo);
    void compress(jpeg_compress_struct* cinfo, uint8_t* yuv, int* offsets);
    void deinterleave(uint8_t* yuv, uint8_t* yRows, uint8_t* uRows,
            uint8_t* vRows, int rowIndex, int width, int height);
};

class P010Yuv420ToJpegREncoder {
public:
    /** Encode YUV data to jpeg/r,  which is output to a stream.
     *  This method will call JpegR::EncodeJPEGR() method. If encoding failed,
     *  Corresponding error code (defined in jpegrerrorcode.h) will be printed and this
     *  method will be terminated and return false.
     *
     *  @param env JNI environment.
     *  @param stream The jpeg output stream.
     *  @param hdr The input yuv data (p010 format).
     *  @param hdrColorSpaceId color space id for the input hdr.
     *  @param sdr The input yuv data (yuv420p format).
     *  @param sdrColorSpaceId color space id for the input sdr.
     *  @param width Width of the Yuv data in terms of pixels.
     *  @param height Height of the Yuv data in terms of pixels.
     *  @param jpegQuality Picture quality in [0, 100].
     *  @param exif Buffer holds EXIF package.
     *  @return true if successfully compressed the stream.
     */
    bool encode(JNIEnv* env,
            SkWStream* stream, void* hdr, int hdrColorSpace, void* sdr, int sdrColorSpace,
            int width, int height, int jpegQuality, ScopedByteArrayRO* exif);

    /** Map data space (defined in DataSpace.java and data_space.h) to the color gamut
     *  used in JPEG/R
     *
     *  @param env JNI environment.
     *  @param aDataSpace data space defined in data_space.h.
     *  @return color gamut for JPEG/R.
     */
    static android::ultrahdr::ultrahdr_color_gamut findColorGamut(JNIEnv* env, int aDataSpace);

    /** Map data space (defined in DataSpace.java and data_space.h) to the transfer function
     *  used in JPEG/R
     *
     *  @param env JNI environment.
     *  @param aDataSpace data space defined in data_space.h.
     *  @return color gamut for JPEG/R.
     */
    static android::ultrahdr::ultrahdr_transfer_function findHdrTransferFunction(
            JNIEnv* env, int aDataSpace);
};

#endif  // _ANDROID_GRAPHICS_YUV_TO_JPEG_ENCODER_H_
