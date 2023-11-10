/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <array>
#include <cstring>
#include <cstdio>
#include <inttypes.h>
#include <memory.h>
#include <vector>

#include <setjmp.h>

#include <android/hardware/camera/device/3.2/types.h>

#include "core_jni_helpers.h"
#include "jni.h"
#include <nativehelper/JNIHelp.h>

#define CAMERA_PROCESSOR_CLASS_NAME "android/hardware/camera2/impl/CameraExtensionJpegProcessor"

extern "C" {
#include "jpeglib.h"
}

using namespace std;
using namespace android;

using android::hardware::camera::device::V3_2::CameraBlob;
using android::hardware::camera::device::V3_2::CameraBlobId;

class Transform;
struct Plane;

inline int sgn(int val) { return (0 < val) - (val < 0); }

inline int min(int a, int b) { return a < b ? a : b; }

inline int max(int a, int b) { return a > b ? a : b; }

/**
 * Represents a combined cropping and rotation transformation.
 *
 * The transformation maps the coordinates (mOrigX, mOrigY) and (mOneX, mOneY)
 * in the input image to the origin and (mOutputWidth, mOutputHeight)
 * respectively.
 */
class Transform {
    public:
        Transform(int origX, int origY, int oneX, int oneY);

        static Transform forCropFollowedByRotation(int cropLeft, int cropTop,
                int cropRight, int cropBottom, int rot90);

        inline int getOutputWidth() const { return mOutputWidth; }

        inline int getOutputHeight() const { return mOutputHeight; }

        bool operator==(const Transform& other) const;

        /**
         * Transforms the input coordinates.  Coordinates outside the cropped region
         * are clamped to valid values.
         */
        void map(int x, int y, int* outX, int* outY) const;

    private:
        int mOutputWidth;
        int mOutputHeight;

        // The coordinates of the point to map the origin to.
        const int mOrigX, mOrigY;
        // The coordinates of the point to map the point (getOutputWidth(),
        // getOutputHeight()) to.
        const int mOneX, mOneY;

        // A matrix for the rotational component.
        int mMat00, mMat01;
        int mMat10, mMat11;
};

/**
 * Represents a model for accessing pixel data for a single plane of an image.
 * Note that the actual data is not owned by this class, and the underlying
 * data does not need to be stored in separate planes.
 */
struct Plane {
    // The dimensions of this plane of the image
    int width;
    int height;

    // A pointer to raw pixel data
    const unsigned char* data;
    // The difference in address between consecutive pixels in the same row
    int pixelStride;
    // The difference in address between the start of consecutive rows
    int rowStride;
};

/**
 * Provides an interface for simultaneously reading a certain number of rows of
 * an image plane as contiguous arrays, suitable for use with libjpeg.
 */
template <unsigned int ROWS>
class RowIterator {
    public:
        /**
         * Creates a new RowIterator which will crop and rotate with the given
         * transform.
         *
         * @param plane the plane to iterate over
         * @param transform the transformation to map output values into the
         * coordinate space of the plane
         * @param rowLength the length of the rows returned via LoadAt().  If this is
         * longer than the width of the output (after applying the transform), then
         * the right-most value is repeated.
         */
        inline RowIterator(Plane plane, Transform transform, int rowLength);

        /**
         * Returns an array of pointers into consecutive rows of contiguous image
         * data starting at y.  That is, samples within each row are contiguous.
         * However, the individual arrays pointed-to may be separate.
         * When the end of the image is reached, the last row of the image is
         * repeated.
         * The returned pointers are valid until the next call to loadAt().
         */
        inline const std::array<unsigned char*, ROWS> loadAt(int baseY);

    private:
        Plane mPlane;
        Transform mTransform;
        // The length of a row, with padding to the next multiple of 64.
        int mPaddedRowLength;
        std::vector<unsigned char> mBuffer;
};

template <unsigned int ROWS>
RowIterator<ROWS>::RowIterator(Plane plane, Transform transform,
                                         int rowLength)
        : mPlane(plane), mTransform(transform) {
    mPaddedRowLength = rowLength;
    mBuffer = std::vector<unsigned char>(rowLength * ROWS);
}

template <unsigned int ROWS>
const std::array<unsigned char*, ROWS> RowIterator<ROWS>::loadAt(int baseY) {
    std::array<unsigned char*, ROWS> bufPtrs;
    for (unsigned int i = 0; i < ROWS; i++) {
        bufPtrs[i] = &mBuffer[mPaddedRowLength * i];
    }

    if (mPlane.width == 0 || mPlane.height == 0) {
        return bufPtrs;
    }

    for (unsigned int i = 0; i < ROWS; i++) {
        int y = i + baseY;
        y = min(y, mTransform.getOutputHeight() - 1);

        int output_width = mPaddedRowLength;
        output_width = min(output_width, mTransform.getOutputWidth());
        output_width = min(output_width, mPlane.width);

        // Each row in the output image will be copied into buf_ by gathering pixels
        // along an axis-aligned line in the plane.
        // The line is defined by (startX, startY) -> (endX, endY), computed via the
        // current Transform.
        int startX;
        int startY;
        mTransform.map(0, y, &startX, &startY);

        int endX;
        int endY;
        mTransform.map(output_width - 1, y, &endX, &endY);

        // Clamp (startX, startY) and (endX, endY) to the valid bounds of the plane.
        startX = min(startX, mPlane.width - 1);
        startY = min(startY, mPlane.height - 1);
        endX = min(endX, mPlane.width - 1);
        endY = min(endY, mPlane.height - 1);
        startX = max(startX, 0);
        startY = max(startY, 0);
        endX = max(endX, 0);
        endY = max(endY, 0);

        // To reduce work inside the copy-loop, precompute the start, end, and
        // stride relating the values to be gathered from mPlane into buf
        // for this particular scan-line.
        int dx = sgn(endX - startX);
        int dy = sgn(endY - startY);
        if (!(dx == 0 || dy == 0)) {
            ALOGE("%s: Unexpected bounds: %dx%d %dx%d!", __FUNCTION__, startX, endX, startY, endY);
            return bufPtrs;
        }

        // The index into mPlane.data of (startX, startY)
        int plane_start = startX * mPlane.pixelStride + startY * mPlane.rowStride;
        // The index into mPlane.data of (endX, endY)
        int plane_end = endX * mPlane.pixelStride + endY * mPlane.rowStride;
        // The stride, in terms of indices in plane_data, required to enumerate the
        // samples between the start and end points.
        int stride = dx * mPlane.pixelStride + dy * mPlane.rowStride;
        // In the degenerate-case of a 1x1 plane, startX and endX are equal, so
        // stride would be 0, resulting in an infinite-loop.  To avoid this case,
        // use a stride of at-least 1.
        if (stride == 0) {
            stride = 1;
        }

        int outX = 0;
        for (int idx = plane_start; idx >= min(plane_start, plane_end) &&
                idx <= max(plane_start, plane_end); idx += stride) {
            bufPtrs[i][outX] = mPlane.data[idx];
            outX++;
        }

        // Fill the remaining right-edge of the buffer by extending the last
        // value.
        unsigned char right_padding_value = bufPtrs[i][outX - 1];
        for (; outX < mPaddedRowLength; outX++) {
            bufPtrs[i][outX] = right_padding_value;
        }
    }

    return bufPtrs;
}

template <typename T>
void safeDelete(T& t) {
    delete t;
    t = nullptr;
}

template <typename T>
void safeDeleteArray(T& t) {
    delete[] t;
    t = nullptr;
}

Transform::Transform(int origX, int origY, int oneX, int oneY)
    : mOrigX(origX), mOrigY(origY), mOneX(oneX), mOneY(oneY) {
    if (origX == oneX || origY == oneY) {
        // Handle the degenerate case of cropping to a 0x0 rectangle.
        mMat00 = 0;
        mMat01 = 0;
        mMat10 = 0;
        mMat11 = 0;
        return;
    }

    if (oneX > origX && oneY > origY) {
        // 0-degree rotation
        mMat00 = 1;
        mMat01 = 0;
        mMat10 = 0;
        mMat11 = 1;
        mOutputWidth = abs(oneX - origX);
        mOutputHeight = abs(oneY - origY);
    } else if (oneX < origX && oneY > origY) {
        // 90-degree CCW rotation
        mMat00 = 0;
        mMat01 = -1;
        mMat10 = 1;
        mMat11 = 0;
        mOutputWidth = abs(oneY - origY);
        mOutputHeight = abs(oneX - origX);
    } else if (oneX > origX && oneY < origY) {
        // 270-degree CCW rotation
        mMat00 = 0;
        mMat01 = 1;
        mMat10 = -1;
        mMat11 = 0;
        mOutputWidth = abs(oneY - origY);
        mOutputHeight = abs(oneX - origX);;
    } else if (oneX < origX && oneY < origY) {
        // 180-degree CCW rotation
        mMat00 = -1;
        mMat01 = 0;
        mMat10 = 0;
        mMat11 = -1;
        mOutputWidth = abs(oneX - origX);
        mOutputHeight = abs(oneY - origY);
    }
}

Transform Transform::forCropFollowedByRotation(int cropLeft, int cropTop, int cropRight,
        int cropBottom, int rot90) {
    // The input crop-region excludes cropRight and cropBottom, so transform the
    // crop rect such that it defines the entire valid region of pixels
    // inclusively.
    cropRight -= 1;
    cropBottom -= 1;

    int cropXLow = min(cropLeft, cropRight);
    int cropYLow = min(cropTop, cropBottom);
    int cropXHigh = max(cropLeft, cropRight);
    int cropYHigh = max(cropTop, cropBottom);
    rot90 %= 4;
    if (rot90 == 0) {
        return Transform(cropXLow, cropYLow, cropXHigh + 1, cropYHigh + 1);
    } else if (rot90 == 1) {
        return Transform(cropXHigh, cropYLow, cropXLow - 1, cropYHigh + 1);
    } else if (rot90 == 2) {
        return Transform(cropXHigh, cropYHigh, cropXLow - 1, cropYLow - 1);
    } else if (rot90 == 3) {
        return Transform(cropXLow, cropYHigh, cropXHigh + 1, cropYLow - 1);
    }
    // Impossible case.
    return Transform(cropXLow, cropYLow, cropXHigh + 1, cropYHigh + 1);
}

bool Transform::operator==(const Transform& other) const {
    return other.mOrigX == mOrigX &&  //
           other.mOrigY == mOrigY &&  //
           other.mOneX == mOneX &&    //
           other.mOneY == mOneY;
}

/**
 * Transforms the input coordinates.  Coordinates outside the cropped region
 * are clamped to valid values.
 */
void Transform::map(int x, int y, int* outX, int* outY) const {
    x = max(x, 0);
    y = max(y, 0);
    x = min(x, getOutputWidth() - 1);
    y = min(y, getOutputHeight() - 1);
    *outX = x * mMat00 + y * mMat01 + mOrigX;
    *outY = x * mMat10 + y * mMat11 + mOrigY;
}

int compress(int img_width, int img_height, RowIterator<16>& y_row_generator,
        RowIterator<8>& cb_row_generator, RowIterator<8>& cr_row_generator,
        unsigned char* out_buf, size_t out_buf_capacity, std::function<void(size_t)> flush,
        int quality) {
    // libjpeg requires the use of setjmp/longjmp to recover from errors.  Since
    // this doesn't play well with RAII, we must use pointers and manually call
    // delete. See POSIX documentation for longjmp() for details on why the
    // volatile keyword is necessary.
    volatile jpeg_compress_struct cinfov;

    jpeg_compress_struct& cinfo =
            *const_cast<struct jpeg_compress_struct*>(&cinfov);

    JSAMPROW* volatile yArr = nullptr;
    JSAMPROW* volatile cbArr = nullptr;
    JSAMPROW* volatile crArr = nullptr;

    JSAMPARRAY imgArr[3];

    // Error handling

    struct my_error_mgr {
        struct jpeg_error_mgr pub;
        jmp_buf setjmp_buffer;
    } err;

    cinfo.err = jpeg_std_error(&err.pub);

    // Default error_exit will call exit(), so override
    // to return control via setjmp/longjmp.
    err.pub.error_exit = [](j_common_ptr cinfo) {
        my_error_mgr* myerr = reinterpret_cast<my_error_mgr*>(cinfo->err);

        (*cinfo->err->output_message)(cinfo);

        // Return control to the setjmp point (see call to setjmp()).
        longjmp(myerr->setjmp_buffer, 1);
    };

    cinfo.err = (struct jpeg_error_mgr*)&err;

    // Set the setjmp point to return to in case of error.
    if (setjmp(err.setjmp_buffer)) {
        // If libjpeg hits an error, control will jump to this point (see call to
        // longjmp()).
        jpeg_destroy_compress(&cinfo);

        safeDeleteArray(yArr);
        safeDeleteArray(cbArr);
        safeDeleteArray(crArr);

        return -1;
    }

    // Create jpeg compression context
    jpeg_create_compress(&cinfo);

    // Stores data needed by our c-style callbacks into libjpeg
    struct ClientData {
        unsigned char* out_buf;
        size_t out_buf_capacity;
        std::function<void(size_t)> flush;
        int totalOutputBytes;
    } clientData{out_buf, out_buf_capacity, flush, 0};

    cinfo.client_data = &clientData;

    // Initialize destination manager
    jpeg_destination_mgr dest;

    dest.init_destination = [](j_compress_ptr cinfo) {
        ClientData& cdata = *reinterpret_cast<ClientData*>(cinfo->client_data);

        cinfo->dest->next_output_byte = cdata.out_buf;
        cinfo->dest->free_in_buffer = cdata.out_buf_capacity;
    };

    dest.empty_output_buffer = [](j_compress_ptr cinfo) -> boolean {
        ClientData& cdata = *reinterpret_cast<ClientData*>(cinfo->client_data);

        size_t numBytesInBuffer = cdata.out_buf_capacity;
        cdata.flush(numBytesInBuffer);
        cdata.totalOutputBytes += numBytesInBuffer;

        // Reset the buffer
        cinfo->dest->next_output_byte = cdata.out_buf;
        cinfo->dest->free_in_buffer = cdata.out_buf_capacity;

        return true;
    };

    dest.term_destination = [](j_compress_ptr cinfo __unused) {
        // do nothing to terminate the output buffer
    };

    cinfo.dest = &dest;

    // Set jpeg parameters
    cinfo.image_width = img_width;
    cinfo.image_height = img_height;
    cinfo.input_components = 3;

    // Set defaults based on the above values
    jpeg_set_defaults(&cinfo);

    jpeg_set_quality(&cinfo, quality, true);

    cinfo.dct_method = JDCT_IFAST;

    cinfo.raw_data_in = true;

    jpeg_set_colorspace(&cinfo, JCS_YCbCr);

    cinfo.comp_info[0].h_samp_factor = 2;
    cinfo.comp_info[0].v_samp_factor = 2;
    cinfo.comp_info[1].h_samp_factor = 1;
    cinfo.comp_info[1].v_samp_factor = 1;
    cinfo.comp_info[2].h_samp_factor = 1;
    cinfo.comp_info[2].v_samp_factor = 1;

    jpeg_start_compress(&cinfo, true);

    yArr = new JSAMPROW[cinfo.comp_info[0].v_samp_factor * DCTSIZE];
    cbArr = new JSAMPROW[cinfo.comp_info[1].v_samp_factor * DCTSIZE];
    crArr = new JSAMPROW[cinfo.comp_info[2].v_samp_factor * DCTSIZE];

    imgArr[0] = const_cast<JSAMPARRAY>(yArr);
    imgArr[1] = const_cast<JSAMPARRAY>(cbArr);
    imgArr[2] = const_cast<JSAMPARRAY>(crArr);

    for (int y = 0; y < img_height; y += DCTSIZE * 2) {
        std::array<unsigned char*, 16> yData = y_row_generator.loadAt(y);
        std::array<unsigned char*, 8> cbData = cb_row_generator.loadAt(y / 2);
        std::array<unsigned char*, 8> crData = cr_row_generator.loadAt(y / 2);

        for (int row = 0; row < DCTSIZE * 2; row++) {
            yArr[row] = yData[row];
        }
        for (int row = 0; row < DCTSIZE; row++) {
            cbArr[row] = cbData[row];
            crArr[row] = crData[row];
        }

        jpeg_write_raw_data(&cinfo, imgArr, DCTSIZE * 2);
    }

    jpeg_finish_compress(&cinfo);

    int numBytesInBuffer = cinfo.dest->next_output_byte - out_buf;

    flush(numBytesInBuffer);

    clientData.totalOutputBytes += numBytesInBuffer;

    safeDeleteArray(yArr);
    safeDeleteArray(cbArr);
    safeDeleteArray(crArr);

    jpeg_destroy_compress(&cinfo);

    return clientData.totalOutputBytes;
}

int compress(
        /** Input image dimensions */
        int width, int height,
        /** Y Plane */
        unsigned char* yBuf, int yPStride, int yRStride,
        /** Cb Plane */
        unsigned char* cbBuf, int cbPStride, int cbRStride,
        /** Cr Plane */
        unsigned char* crBuf, int crPStride, int crRStride,
        /** Output */
        unsigned char* outBuf, size_t outBufCapacity,
        /** Jpeg compression parameters */
        int quality,
        /** Crop */
        int cropLeft, int cropTop, int cropRight, int cropBottom,
        /** Rotation (multiple of 90).  For example, rot90 = 1 implies a 90 degree
         * rotation. */
        int rot90) {
    int finalWidth;
    int finalHeight;
    finalWidth = cropRight - cropLeft;
    finalHeight = cropBottom - cropTop;

    rot90 %= 4;
    // for 90 and 270-degree rotations, flip the final width and height
    if (rot90 == 1) {
        finalWidth = cropBottom - cropTop;
        finalHeight = cropRight - cropLeft;
    } else if (rot90 == 3) {
        finalWidth = cropBottom - cropTop;
        finalHeight = cropRight - cropLeft;
    }

    const Plane yP = {width, height, yBuf, yPStride, yRStride};
    const Plane cbP = {width / 2, height / 2, cbBuf, cbPStride, cbRStride};
    const Plane crP = {width / 2, height / 2, crBuf, crPStride, crRStride};

    auto flush = [](size_t numBytes __unused) {
        // do nothing
    };

    // Round up to the nearest multiple of 64.
    int y_row_length = (finalWidth + 16 + 63) & ~63;
    int cb_row_length = (finalWidth / 2 + 16 + 63) & ~63;
    int cr_row_length = (finalWidth / 2 + 16 + 63) & ~63;

    Transform yTrans = Transform::forCropFollowedByRotation(
            cropLeft, cropTop, cropRight, cropBottom, rot90);

    Transform chromaTrans = Transform::forCropFollowedByRotation(
            cropLeft / 2, cropTop / 2, cropRight / 2, cropBottom / 2, rot90);

    RowIterator<16> yIter(yP, yTrans, y_row_length);
    RowIterator<8> cbIter(cbP, chromaTrans, cb_row_length);
    RowIterator<8> crIter(crP, chromaTrans, cr_row_length);

    return compress(finalWidth, finalHeight, yIter, cbIter, crIter, outBuf, outBufCapacity, flush,
            quality);
}

extern "C" {

static jint CameraExtensionJpegProcessor_compressJpegFromYUV420p(
        JNIEnv* env, jclass clazz __unused,
        /** Input image dimensions */
        jint width, jint height,
        /** Y Plane */
        jobject yBuf, jint yPStride, jint yRStride,
        /** Cb Plane */
        jobject cbBuf, jint cbPStride, jint cbRStride,
        /** Cr Plane */
        jobject crBuf, jint crPStride, jint crRStride,
        /** Output */
        jobject outBuf, jint outBufCapacity,
        /** Jpeg compression parameters */
        jint quality,
        /** Crop */
        jint cropLeft, jint cropTop, jint cropRight, jint cropBottom,
        /** Rotation (multiple of 90).  For example, rot90 = 1 implies a 90 degree
         * rotation. */
        jint rot90) {
    jbyte* y = (jbyte*)env->GetDirectBufferAddress(yBuf);
    jbyte* cb = (jbyte*)env->GetDirectBufferAddress(cbBuf);
    jbyte* cr = (jbyte*)env->GetDirectBufferAddress(crBuf);
    jbyte* out = (jbyte*)env->GetDirectBufferAddress(outBuf);

    size_t actualJpegSize = compress(width, height,
            (unsigned char*)y, yPStride, yRStride,
            (unsigned char*)cb, cbPStride, cbRStride,
            (unsigned char*)cr, crPStride, crRStride,
            (unsigned char*)out, (size_t)outBufCapacity,
            quality, cropLeft, cropTop, cropRight, cropBottom, rot90);

    size_t finalJpegSize = actualJpegSize + sizeof(CameraBlob);
    if (finalJpegSize > static_cast<size_t>(outBufCapacity)) {
        ALOGE("%s: Final jpeg buffer %zu not large enough for the jpeg blob header with "\
                "capacity %d", __FUNCTION__, finalJpegSize, outBufCapacity);
        return actualJpegSize;
    }

    int8_t* header = static_cast<int8_t *> (out) +
            (outBufCapacity - sizeof(CameraBlob));
    CameraBlob *blob = reinterpret_cast<CameraBlob *> (header);
    blob->blobId = CameraBlobId::JPEG;
    blob->blobSize = actualJpegSize;

    return actualJpegSize;
}

} // extern "C"

static const JNINativeMethod gCameraExtensionJpegProcessorMethods[] = {
    {"compressJpegFromYUV420pNative",
    "(IILjava/nio/ByteBuffer;IILjava/nio/ByteBuffer;IILjava/nio/ByteBuffer;IILjava/nio/ByteBuffer;IIIIIII)I",
    (void*)CameraExtensionJpegProcessor_compressJpegFromYUV420p}};

// Get all the required offsets in java class and register native functions
int register_android_hardware_camera2_impl_CameraExtensionJpegProcessor(JNIEnv* env) {
    // Register native functions
    return RegisterMethodsOrDie(env, CAMERA_PROCESSOR_CLASS_NAME,
            gCameraExtensionJpegProcessorMethods, NELEM(gCameraExtensionJpegProcessorMethods));
}
