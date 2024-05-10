/*
 * Copyright 2014 The Android Open Source Project
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

//#define LOG_NDEBUG 0
#define LOG_TAG "DngCreator_JNI"
#include <inttypes.h>
#include <string.h>
#include <algorithm>
#include <array>
#include <memory>
#include <vector>
#include <cmath>

#include <android-base/properties.h>
#include <utils/Log.h>
#include <utils/Errors.h>
#include <utils/StrongPointer.h>
#include <utils/RefBase.h>
#include <utils/Vector.h>
#include <utils/String8.h>
#include <system/camera_metadata.h>
#include <camera/CameraMetadata.h>
#include <img_utils/DngUtils.h>
#include <img_utils/TagDefinitions.h>
#include <img_utils/TiffIfd.h>
#include <img_utils/TiffWriter.h>
#include <img_utils/Output.h>
#include <img_utils/Input.h>
#include <img_utils/StripSource.h>

#include "core_jni_helpers.h"

#include "android_runtime/AndroidRuntime.h"
#include "android_runtime/android_hardware_camera2_CameraMetadata.h"

#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedUtfChars.h>

using namespace android;
using namespace img_utils;
using android::base::GetProperty;

#define BAIL_IF_INVALID_RET_BOOL(expr, jnienv, tagId, writer) \
    if ((expr) != OK) { \
        jniThrowExceptionFmt(jnienv, "java/lang/IllegalArgumentException", \
                "Invalid metadata for tag %s (%x)", (writer)->getTagName(tagId), (tagId)); \
        return false; \
    }


#define BAIL_IF_INVALID_RET_NULL_SP(expr, jnienv, tagId, writer) \
    if ((expr) != OK) { \
        jniThrowExceptionFmt(jnienv, "java/lang/IllegalArgumentException", \
                "Invalid metadata for tag %s (%x)", (writer)->getTagName(tagId), (tagId)); \
        return nullptr; \
    }


#define BAIL_IF_INVALID_R(expr, jnienv, tagId, writer) \
    if ((expr) != OK) { \
        jniThrowExceptionFmt(jnienv, "java/lang/IllegalArgumentException", \
                "Invalid metadata for tag %s (%x)", (writer)->getTagName(tagId), (tagId)); \
        return -1; \
    }

#define BAIL_IF_EMPTY_RET_NULL_SP(entry, jnienv, tagId, writer) \
    if ((entry).count == 0) { \
        jniThrowExceptionFmt(jnienv, "java/lang/IllegalArgumentException", \
                "Missing metadata fields for tag %s (%x)", (writer)->getTagName(tagId), (tagId)); \
        return nullptr; \
    }

#define BAIL_IF_EMPTY_RET_BOOL(entry, jnienv, tagId, writer)               \
    if ((entry).count == 0) {                                              \
        jniThrowExceptionFmt(jnienv, "java/lang/IllegalArgumentException", \
                             "Missing metadata fields for tag %s (%x)",    \
                             (writer)->getTagName(tagId), (tagId));        \
        return false;                                                      \
    }

#define BAIL_IF_EMPTY_RET_STATUS(entry, jnienv, tagId, writer)             \
    if ((entry).count == 0) {                                              \
        jniThrowExceptionFmt(jnienv, "java/lang/IllegalArgumentException", \
                             "Missing metadata fields for tag %s (%x)",    \
                             (writer)->getTagName(tagId), (tagId));        \
        return BAD_VALUE;                                                  \
    }

#define BAIL_IF_EXPR_RET_NULL_SP(expr, jnienv, tagId, writer) \
    if (expr) { \
        jniThrowExceptionFmt(jnienv, "java/lang/IllegalArgumentException", \
                "Invalid metadata for tag %s (%x)", (writer)->getTagName(tagId), (tagId)); \
        return nullptr; \
    }


#define ANDROID_DNGCREATOR_CTX_JNI_ID     "mNativeContext"

static struct {
    jfieldID mNativeContext;
} gDngCreatorClassInfo;

static struct {
    jmethodID mWriteMethod;
} gOutputStreamClassInfo;

static struct {
    jmethodID mReadMethod;
    jmethodID mSkipMethod;
} gInputStreamClassInfo;

static struct {
    jmethodID mGetMethod;
} gInputByteBufferClassInfo;

enum {
    BITS_PER_SAMPLE = 16,
    BYTES_PER_SAMPLE = 2,
    BYTES_PER_RGB_PIXEL = 3,
    BITS_PER_RGB_SAMPLE = 8,
    BYTES_PER_RGB_SAMPLE = 1,
    SAMPLES_PER_RGB_PIXEL = 3,
    SAMPLES_PER_RAW_PIXEL = 1,
    TIFF_IFD_0 = 0,
    TIFF_IFD_SUB1 = 1,
    TIFF_IFD_GPSINFO = 2,
};


/**
 * POD container class for GPS tag data.
 */
class GpsData {
public:
    enum {
        GPS_VALUE_LENGTH = 6,
        GPS_REF_LENGTH = 2,
        GPS_DATE_LENGTH = 11,
    };

    uint32_t mLatitude[GPS_VALUE_LENGTH];
    uint32_t mLongitude[GPS_VALUE_LENGTH];
    uint32_t mTimestamp[GPS_VALUE_LENGTH];
    uint8_t mLatitudeRef[GPS_REF_LENGTH];
    uint8_t mLongitudeRef[GPS_REF_LENGTH];
    uint8_t mDate[GPS_DATE_LENGTH];
};

// ----------------------------------------------------------------------------

/**
 * Container class for the persistent native context.
 */

class NativeContext : public LightRefBase<NativeContext> {
public:
    enum {
        DATETIME_COUNT = 20,
    };

    NativeContext(const CameraMetadata& characteristics, const CameraMetadata& result);
    virtual ~NativeContext();

    TiffWriter* getWriter();

    std::shared_ptr<const CameraMetadata> getCharacteristics() const;
    std::shared_ptr<const CameraMetadata> getResult() const;

    uint32_t getThumbnailWidth() const;
    uint32_t getThumbnailHeight() const;
    const uint8_t* getThumbnail() const;
    bool hasThumbnail() const;

    bool setThumbnail(const uint8_t* buffer, uint32_t width, uint32_t height);

    void setOrientation(uint16_t orientation);
    uint16_t getOrientation() const;

    void setDescription(const String8& desc);
    String8 getDescription() const;
    bool hasDescription() const;

    void setGpsData(const GpsData& data);
    GpsData getGpsData() const;
    bool hasGpsData() const;

    void setCaptureTime(const String8& formattedCaptureTime);
    String8 getCaptureTime() const;
    bool hasCaptureTime() const;

private:
    Vector<uint8_t> mCurrentThumbnail;
    TiffWriter mWriter;
    std::shared_ptr<CameraMetadata> mCharacteristics;
    std::shared_ptr<CameraMetadata> mResult;
    uint32_t mThumbnailWidth;
    uint32_t mThumbnailHeight;
    uint16_t mOrientation;
    bool mThumbnailSet;
    bool mGpsSet;
    bool mDescriptionSet;
    bool mCaptureTimeSet;
    String8 mDescription;
    GpsData mGpsData;
    String8 mFormattedCaptureTime;
};

NativeContext::NativeContext(const CameraMetadata& characteristics, const CameraMetadata& result) :
        mCharacteristics(std::make_shared<CameraMetadata>(characteristics)),
        mResult(std::make_shared<CameraMetadata>(result)), mThumbnailWidth(0),
        mThumbnailHeight(0), mOrientation(TAG_ORIENTATION_UNKNOWN), mThumbnailSet(false),
        mGpsSet(false), mDescriptionSet(false), mCaptureTimeSet(false) {}

NativeContext::~NativeContext() {}

TiffWriter* NativeContext::getWriter() {
    return &mWriter;
}

std::shared_ptr<const CameraMetadata> NativeContext::getCharacteristics() const {
    return mCharacteristics;
}

std::shared_ptr<const CameraMetadata> NativeContext::getResult() const {
    return mResult;
}

uint32_t NativeContext::getThumbnailWidth() const {
    return mThumbnailWidth;
}

uint32_t NativeContext::getThumbnailHeight() const {
    return mThumbnailHeight;
}

const uint8_t* NativeContext::getThumbnail() const {
    return mCurrentThumbnail.array();
}

bool NativeContext::hasThumbnail() const {
    return mThumbnailSet;
}

bool NativeContext::setThumbnail(const uint8_t* buffer, uint32_t width, uint32_t height) {
    mThumbnailWidth = width;
    mThumbnailHeight = height;

    size_t size = BYTES_PER_RGB_PIXEL * width * height;
    if (mCurrentThumbnail.resize(size) < 0) {
        ALOGE("%s: Could not resize thumbnail buffer.", __FUNCTION__);
        return false;
    }

    uint8_t* thumb = mCurrentThumbnail.editArray();
    memcpy(thumb, buffer, size);
    mThumbnailSet = true;
    return true;
}

void NativeContext::setOrientation(uint16_t orientation) {
    mOrientation = orientation;
}

uint16_t NativeContext::getOrientation() const {
    return mOrientation;
}

void NativeContext::setDescription(const String8& desc) {
    mDescription = desc;
    mDescriptionSet = true;
}

String8 NativeContext::getDescription() const {
    return mDescription;
}

bool NativeContext::hasDescription() const {
    return mDescriptionSet;
}

void NativeContext::setGpsData(const GpsData& data) {
    mGpsData = data;
    mGpsSet = true;
}

GpsData NativeContext::getGpsData() const {
    return mGpsData;
}

bool NativeContext::hasGpsData() const {
    return mGpsSet;
}

void NativeContext::setCaptureTime(const String8& formattedCaptureTime) {
    mFormattedCaptureTime = formattedCaptureTime;
    mCaptureTimeSet = true;
}

String8 NativeContext::getCaptureTime() const {
    return mFormattedCaptureTime;
}

bool NativeContext::hasCaptureTime() const {
    return mCaptureTimeSet;
}

// End of NativeContext
// ----------------------------------------------------------------------------

/**
 * Wrapper class for a Java OutputStream.
 *
 * This class is not intended to be used across JNI calls.
 */
class JniOutputStream : public Output, public LightRefBase<JniOutputStream> {
public:
    JniOutputStream(JNIEnv* env, jobject outStream);

    virtual ~JniOutputStream();

    status_t open();

    status_t write(const uint8_t* buf, size_t offset, size_t count);

    status_t close();
private:
    enum {
        BYTE_ARRAY_LENGTH = 4096
    };
    jobject mOutputStream;
    JNIEnv* mEnv;
    jbyteArray mByteArray;
};

JniOutputStream::JniOutputStream(JNIEnv* env, jobject outStream) : mOutputStream(outStream),
        mEnv(env) {
    mByteArray = env->NewByteArray(BYTE_ARRAY_LENGTH);
    if (mByteArray == nullptr) {
        jniThrowException(env, "java/lang/OutOfMemoryError", "Could not allocate byte array.");
    }
}

JniOutputStream::~JniOutputStream() {
    mEnv->DeleteLocalRef(mByteArray);
}

status_t JniOutputStream::open() {
    // Do nothing
    return OK;
}

status_t JniOutputStream::write(const uint8_t* buf, size_t offset, size_t count) {
    while(count > 0) {
        size_t len = BYTE_ARRAY_LENGTH;
        len = (count > len) ? len : count;
        mEnv->SetByteArrayRegion(mByteArray, 0, len, reinterpret_cast<const jbyte*>(buf + offset));

        if (mEnv->ExceptionCheck()) {
            return BAD_VALUE;
        }

        mEnv->CallVoidMethod(mOutputStream, gOutputStreamClassInfo.mWriteMethod, mByteArray,
                0, len);

        if (mEnv->ExceptionCheck()) {
            return BAD_VALUE;
        }

        count -= len;
        offset += len;
    }
    return OK;
}

status_t JniOutputStream::close() {
    // Do nothing
    return OK;
}

// End of JniOutputStream
// ----------------------------------------------------------------------------

/**
 * Wrapper class for a Java InputStream.
 *
 * This class is not intended to be used across JNI calls.
 */
class JniInputStream : public Input, public LightRefBase<JniInputStream> {
public:
    JniInputStream(JNIEnv* env, jobject inStream);

    status_t open();

    status_t close();

    ssize_t read(uint8_t* buf, size_t offset, size_t count);

    ssize_t skip(size_t count);

    virtual ~JniInputStream();
private:
    enum {
        BYTE_ARRAY_LENGTH = 4096
    };
    jobject mInStream;
    JNIEnv* mEnv;
    jbyteArray mByteArray;

};

JniInputStream::JniInputStream(JNIEnv* env, jobject inStream) : mInStream(inStream), mEnv(env) {
    mByteArray = env->NewByteArray(BYTE_ARRAY_LENGTH);
    if (mByteArray == nullptr) {
        jniThrowException(env, "java/lang/OutOfMemoryError", "Could not allocate byte array.");
    }
}

JniInputStream::~JniInputStream() {
    mEnv->DeleteLocalRef(mByteArray);
}

ssize_t JniInputStream::read(uint8_t* buf, size_t offset, size_t count) {

    jint realCount = BYTE_ARRAY_LENGTH;
    if (count < BYTE_ARRAY_LENGTH) {
        realCount = count;
    }
    jint actual = mEnv->CallIntMethod(mInStream, gInputStreamClassInfo.mReadMethod, mByteArray, 0,
            realCount);

    if (actual < 0) {
        return NOT_ENOUGH_DATA;
    }

    if (mEnv->ExceptionCheck()) {
        return BAD_VALUE;
    }

    mEnv->GetByteArrayRegion(mByteArray, 0, actual, reinterpret_cast<jbyte*>(buf + offset));
    if (mEnv->ExceptionCheck()) {
        return BAD_VALUE;
    }
    return actual;
}

ssize_t JniInputStream::skip(size_t count) {
    jlong actual = mEnv->CallLongMethod(mInStream, gInputStreamClassInfo.mSkipMethod,
            static_cast<jlong>(count));

    if (mEnv->ExceptionCheck()) {
        return BAD_VALUE;
    }
    if (actual < 0) {
        return NOT_ENOUGH_DATA;
    }
    return actual;
}

status_t JniInputStream::open() {
    // Do nothing
    return OK;
}

status_t JniInputStream::close() {
    // Do nothing
    return OK;
}

// End of JniInputStream
// ----------------------------------------------------------------------------

/**
 * Wrapper class for a non-direct Java ByteBuffer.
 *
 * This class is not intended to be used across JNI calls.
 */
class JniInputByteBuffer : public Input, public LightRefBase<JniInputByteBuffer> {
public:
    JniInputByteBuffer(JNIEnv* env, jobject inBuf);

    status_t open();

    status_t close();

    ssize_t read(uint8_t* buf, size_t offset, size_t count);

    virtual ~JniInputByteBuffer();
private:
    enum {
        BYTE_ARRAY_LENGTH = 4096
    };
    jobject mInBuf;
    JNIEnv* mEnv;
    jbyteArray mByteArray;
};

JniInputByteBuffer::JniInputByteBuffer(JNIEnv* env, jobject inBuf) : mInBuf(inBuf), mEnv(env) {
    mByteArray = env->NewByteArray(BYTE_ARRAY_LENGTH);
    if (mByteArray == nullptr) {
        jniThrowException(env, "java/lang/OutOfMemoryError", "Could not allocate byte array.");
    }
}

JniInputByteBuffer::~JniInputByteBuffer() {
    mEnv->DeleteLocalRef(mByteArray);
}

ssize_t JniInputByteBuffer::read(uint8_t* buf, size_t offset, size_t count) {
    jint realCount = BYTE_ARRAY_LENGTH;
    if (count < BYTE_ARRAY_LENGTH) {
        realCount = count;
    }

    jobject chainingBuf = mEnv->CallObjectMethod(mInBuf, gInputByteBufferClassInfo.mGetMethod,
            mByteArray, 0, realCount);
    mEnv->DeleteLocalRef(chainingBuf);

    if (mEnv->ExceptionCheck()) {
        ALOGE("%s: Exception while reading from input into byte buffer.", __FUNCTION__);
        return BAD_VALUE;
    }

    mEnv->GetByteArrayRegion(mByteArray, 0, realCount, reinterpret_cast<jbyte*>(buf + offset));
    if (mEnv->ExceptionCheck()) {
        ALOGE("%s: Exception while reading from byte buffer.", __FUNCTION__);
        return BAD_VALUE;
    }
    return realCount;
}

status_t JniInputByteBuffer::open() {
    // Do nothing
    return OK;
}

status_t JniInputByteBuffer::close() {
    // Do nothing
    return OK;
}

// End of JniInputByteBuffer
// ----------------------------------------------------------------------------

/**
 * StripSource subclass for Input types.
 *
 * This class is not intended to be used across JNI calls.
 */

class InputStripSource : public StripSource, public LightRefBase<InputStripSource> {
public:
    InputStripSource(JNIEnv* env, Input& input, uint32_t ifd, uint32_t width, uint32_t height,
            uint32_t pixStride, uint32_t rowStride, uint64_t offset, uint32_t bytesPerSample,
            uint32_t samplesPerPixel);

    virtual ~InputStripSource();

    virtual status_t writeToStream(Output& stream, uint32_t count);

    virtual uint32_t getIfd() const;
protected:
    uint32_t mIfd;
    Input* mInput;
    uint32_t mWidth;
    uint32_t mHeight;
    uint32_t mPixStride;
    uint32_t mRowStride;
    uint64_t mOffset;
    JNIEnv* mEnv;
    uint32_t mBytesPerSample;
    uint32_t mSamplesPerPixel;
};

InputStripSource::InputStripSource(JNIEnv* env, Input& input, uint32_t ifd, uint32_t width,
        uint32_t height, uint32_t pixStride, uint32_t rowStride, uint64_t offset,
        uint32_t bytesPerSample, uint32_t samplesPerPixel) : mIfd(ifd), mInput(&input),
        mWidth(width), mHeight(height), mPixStride(pixStride), mRowStride(rowStride),
        mOffset(offset), mEnv(env), mBytesPerSample(bytesPerSample),
        mSamplesPerPixel(samplesPerPixel) {}

InputStripSource::~InputStripSource() {}

status_t InputStripSource::writeToStream(Output& stream, uint32_t count) {
    uint32_t fullSize = mWidth * mHeight * mBytesPerSample * mSamplesPerPixel;
    jlong offset = mOffset;

    if (fullSize != count) {
        ALOGE("%s: Amount to write %u doesn't match image size %u", __FUNCTION__, count,
                fullSize);
        jniThrowException(mEnv, "java/lang/IllegalStateException", "Not enough data to write");
        return BAD_VALUE;
    }

    // Skip offset
    while (offset > 0) {
        ssize_t skipped = mInput->skip(offset);
        if (skipped <= 0) {
            if (skipped == NOT_ENOUGH_DATA || skipped == 0) {
                jniThrowExceptionFmt(mEnv, "java/io/IOException",
                        "Early EOF encountered in skip, not enough pixel data for image of size %u",
                        fullSize);
                skipped = NOT_ENOUGH_DATA;
            } else {
                if (!mEnv->ExceptionCheck()) {
                    jniThrowException(mEnv, "java/io/IOException",
                            "Error encountered while skip bytes in input stream.");
                }
            }

            return skipped;
        }
        offset -= skipped;
    }

    Vector<uint8_t> row;
    if (row.resize(mRowStride) < 0) {
        jniThrowException(mEnv, "java/lang/OutOfMemoryError", "Could not allocate row vector.");
        return BAD_VALUE;
    }

    uint8_t* rowBytes = row.editArray();

    for (uint32_t i = 0; i < mHeight; ++i) {
        size_t rowFillAmt = 0;
        size_t rowSize = mRowStride;

        while (rowFillAmt < mRowStride) {
            ssize_t bytesRead = mInput->read(rowBytes, rowFillAmt, rowSize);
            if (bytesRead <= 0) {
                if (bytesRead == NOT_ENOUGH_DATA || bytesRead == 0) {
                    ALOGE("%s: Early EOF on row %" PRIu32 ", received bytesRead %zd",
                            __FUNCTION__, i, bytesRead);
                    jniThrowExceptionFmt(mEnv, "java/io/IOException",
                            "Early EOF encountered, not enough pixel data for image of size %"
                            PRIu32, fullSize);
                    bytesRead = NOT_ENOUGH_DATA;
                } else {
                    if (!mEnv->ExceptionCheck()) {
                        jniThrowException(mEnv, "java/io/IOException",
                                "Error encountered while reading");
                    }
                }
                return bytesRead;
            }
            rowFillAmt += bytesRead;
            rowSize -= bytesRead;
        }

        if (mPixStride == mBytesPerSample * mSamplesPerPixel) {
            ALOGV("%s: Using stream per-row write for strip.", __FUNCTION__);

            if (stream.write(rowBytes, 0, mBytesPerSample * mSamplesPerPixel * mWidth) != OK ||
                    mEnv->ExceptionCheck()) {
                if (!mEnv->ExceptionCheck()) {
                    jniThrowException(mEnv, "java/io/IOException", "Failed to write pixel data");
                }
                return BAD_VALUE;
            }
        } else {
            ALOGV("%s: Using stream per-pixel write for strip.", __FUNCTION__);
            jniThrowException(mEnv, "java/lang/IllegalStateException",
                    "Per-pixel strides are not supported for RAW16 -- pixels must be contiguous");
            return BAD_VALUE;

            // TODO: Add support for non-contiguous pixels if needed.
        }
    }
    return OK;
}

uint32_t InputStripSource::getIfd() const {
    return mIfd;
}

// End of InputStripSource
// ----------------------------------------------------------------------------

/**
 * StripSource subclass for direct buffer types.
 *
 * This class is not intended to be used across JNI calls.
 */

class DirectStripSource : public StripSource, public LightRefBase<DirectStripSource> {
public:
    DirectStripSource(JNIEnv* env, const uint8_t* pixelBytes, uint32_t ifd, uint32_t width,
            uint32_t height, uint32_t pixStride, uint32_t rowStride, uint64_t offset,
            uint32_t bytesPerSample, uint32_t samplesPerPixel);

    virtual ~DirectStripSource();

    virtual status_t writeToStream(Output& stream, uint32_t count);

    virtual uint32_t getIfd() const;
protected:
    uint32_t mIfd;
    const uint8_t* mPixelBytes;
    uint32_t mWidth;
    uint32_t mHeight;
    uint32_t mPixStride;
    uint32_t mRowStride;
    uint16_t mOffset;
    JNIEnv* mEnv;
    uint32_t mBytesPerSample;
    uint32_t mSamplesPerPixel;
};

DirectStripSource::DirectStripSource(JNIEnv* env, const uint8_t* pixelBytes, uint32_t ifd,
            uint32_t width, uint32_t height, uint32_t pixStride, uint32_t rowStride,
            uint64_t offset, uint32_t bytesPerSample, uint32_t samplesPerPixel) : mIfd(ifd),
            mPixelBytes(pixelBytes), mWidth(width), mHeight(height), mPixStride(pixStride),
            mRowStride(rowStride), mOffset(offset), mEnv(env), mBytesPerSample(bytesPerSample),
            mSamplesPerPixel(samplesPerPixel) {}

DirectStripSource::~DirectStripSource() {}

status_t DirectStripSource::writeToStream(Output& stream, uint32_t count) {
    uint32_t fullSize = mWidth * mHeight * mBytesPerSample * mSamplesPerPixel;

    if (fullSize != count) {
        ALOGE("%s: Amount to write %u doesn't match image size %u", __FUNCTION__, count,
                fullSize);
        jniThrowException(mEnv, "java/lang/IllegalStateException", "Not enough data to write");
        return BAD_VALUE;
    }


    if (mPixStride == mBytesPerSample * mSamplesPerPixel
            && mRowStride == mWidth * mBytesPerSample * mSamplesPerPixel) {
        ALOGV("%s: Using direct single-pass write for strip.", __FUNCTION__);

        if (stream.write(mPixelBytes, mOffset, fullSize) != OK || mEnv->ExceptionCheck()) {
            if (!mEnv->ExceptionCheck()) {
                jniThrowException(mEnv, "java/io/IOException", "Failed to write pixel data");
            }
            return BAD_VALUE;
        }
    } else if (mPixStride == mBytesPerSample * mSamplesPerPixel) {
        ALOGV("%s: Using direct per-row write for strip.", __FUNCTION__);

        for (size_t i = 0; i < mHeight; ++i) {
            if (stream.write(mPixelBytes, mOffset + i * mRowStride, mPixStride * mWidth) != OK ||
                        mEnv->ExceptionCheck()) {
                if (!mEnv->ExceptionCheck()) {
                    jniThrowException(mEnv, "java/io/IOException", "Failed to write pixel data");
                }
                return BAD_VALUE;
            }
        }
    } else {
        ALOGV("%s: Using direct per-pixel write for strip.", __FUNCTION__);

        jniThrowException(mEnv, "java/lang/IllegalStateException",
                "Per-pixel strides are not supported for RAW16 -- pixels must be contiguous");
        return BAD_VALUE;

        // TODO: Add support for non-contiguous pixels if needed.
    }
    return OK;

}

uint32_t DirectStripSource::getIfd() const {
    return mIfd;
}

// End of DirectStripSource
// ----------------------------------------------------------------------------

// Get the appropriate tag corresponding to default / maximum resolution mode.
static int32_t getAppropriateModeTag(int32_t tag, bool maximumResolution) {
    if (!maximumResolution) {
        return tag;
    }
    switch (tag) {
        case ANDROID_SENSOR_INFO_PIXEL_ARRAY_SIZE:
            return ANDROID_SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION;
        case ANDROID_SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE:
            return ANDROID_SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION;
        case ANDROID_SENSOR_INFO_ACTIVE_ARRAY_SIZE:
            return ANDROID_SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION;
        default:
            ALOGE("%s: Tag %d doesn't have sensor info related maximum resolution counterpart",
                  __FUNCTION__, tag);
            return -1;
    }
}

static bool isMaximumResolutionModeImage(const CameraMetadata& characteristics, uint32_t imageWidth,
                                         uint32_t imageHeight, const sp<TiffWriter> writer,
                                         JNIEnv* env) {
    // If this isn't an ultra-high resolution sensor, return false;
    camera_metadata_ro_entry capabilitiesEntry =
            characteristics.find(ANDROID_REQUEST_AVAILABLE_CAPABILITIES);
    size_t capsCount = capabilitiesEntry.count;
    const uint8_t* caps = capabilitiesEntry.data.u8;
    if (std::find(caps, caps + capsCount,
                  ANDROID_REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR) ==
        caps + capsCount) {
        // not an ultra-high resolution sensor, cannot have a maximum resolution
        // mode image.
        return false;
    }

    // If the image width and height are either the maximum resolution
    // pre-correction active array size or the maximum resolution pixel array
    // size, this image is a maximum resolution RAW_SENSOR image.

    // Check dimensions
    camera_metadata_ro_entry entry = characteristics.find(
            ANDROID_SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION);

    BAIL_IF_EMPTY_RET_BOOL(entry, env, TAG_IMAGEWIDTH, writer);

    uint32_t preWidth = static_cast<uint32_t>(entry.data.i32[2]);
    uint32_t preHeight = static_cast<uint32_t>(entry.data.i32[3]);

    camera_metadata_ro_entry pixelArrayEntry =
            characteristics.find(ANDROID_SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION);

    BAIL_IF_EMPTY_RET_BOOL(pixelArrayEntry, env, TAG_IMAGEWIDTH, writer);

    uint32_t pixWidth = static_cast<uint32_t>(pixelArrayEntry.data.i32[0]);
    uint32_t pixHeight = static_cast<uint32_t>(pixelArrayEntry.data.i32[1]);

    return (imageWidth == preWidth && imageHeight == preHeight) ||
            (imageWidth == pixWidth && imageHeight == pixHeight);
}

/**
 * Calculate the default crop relative to the "active area" of the image sensor (this active area
 * will always be the pre-correction active area rectangle), and set this.
 */
static status_t calculateAndSetCrop(JNIEnv* env, const CameraMetadata& characteristics,
                                    sp<TiffWriter> writer, bool maximumResolutionMode) {
    camera_metadata_ro_entry entry = characteristics.find(
            getAppropriateModeTag(ANDROID_SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE,
                                  maximumResolutionMode));
    BAIL_IF_EMPTY_RET_STATUS(entry, env, TAG_IMAGEWIDTH, writer);
    uint32_t width = static_cast<uint32_t>(entry.data.i32[2]);
    uint32_t height = static_cast<uint32_t>(entry.data.i32[3]);

    const uint32_t margin = 8; // Default margin recommended by Adobe for interpolation.

    if (width < margin * 2 || height < margin * 2) {
        ALOGE("%s: Cannot calculate default crop for image, pre-correction active area is too"
                "small: h=%" PRIu32 ", w=%" PRIu32, __FUNCTION__, height, width);
        jniThrowException(env, "java/lang/IllegalStateException",
                "Pre-correction active area is too small.");
        return BAD_VALUE;
    }

    uint32_t defaultCropOrigin[] = {margin, margin};
    uint32_t defaultCropSize[] = {width - defaultCropOrigin[0] - margin,
                                  height - defaultCropOrigin[1] - margin};

    BAIL_IF_INVALID_R(writer->addEntry(TAG_DEFAULTCROPORIGIN, 2, defaultCropOrigin,
            TIFF_IFD_0), env, TAG_DEFAULTCROPORIGIN, writer);
    BAIL_IF_INVALID_R(writer->addEntry(TAG_DEFAULTCROPSIZE, 2, defaultCropSize,
            TIFF_IFD_0), env, TAG_DEFAULTCROPSIZE, writer);

    return OK;
}

static bool validateDngHeader(JNIEnv* env, sp<TiffWriter> writer,
        const CameraMetadata& characteristics, jint width, jint height) {
    if (width <= 0) {
        jniThrowExceptionFmt(env, "java/lang/IllegalArgumentException", \
                        "Image width %d is invalid", width);
        return false;
    }

    if (height <= 0) {
        jniThrowExceptionFmt(env, "java/lang/IllegalArgumentException", \
                        "Image height %d is invalid", height);
        return false;
    }
    bool isMaximumResolutionMode =
            isMaximumResolutionModeImage(characteristics, static_cast<uint32_t>(width),
                                         static_cast<uint32_t>(height), writer, env);

    camera_metadata_ro_entry preCorrectionEntry = characteristics.find(
            getAppropriateModeTag(ANDROID_SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE,
                                  isMaximumResolutionMode));
    BAIL_IF_EMPTY_RET_BOOL(preCorrectionEntry, env, TAG_IMAGEWIDTH, writer);

    camera_metadata_ro_entry pixelArrayEntry = characteristics.find(
            getAppropriateModeTag(ANDROID_SENSOR_INFO_PIXEL_ARRAY_SIZE, isMaximumResolutionMode));
    BAIL_IF_EMPTY_RET_BOOL(pixelArrayEntry, env, TAG_IMAGEWIDTH, writer);

    int pWidth = static_cast<int>(pixelArrayEntry.data.i32[0]);
    int pHeight = static_cast<int>(pixelArrayEntry.data.i32[1]);
    int cWidth = static_cast<int>(preCorrectionEntry.data.i32[2]);
    int cHeight = static_cast<int>(preCorrectionEntry.data.i32[3]);

    bool matchesPixelArray = (pWidth == width && pHeight == height);
    bool matchesPreCorrectionArray = (cWidth == width && cHeight == height);

    if (!(matchesPixelArray || matchesPreCorrectionArray)) {
        jniThrowExceptionFmt(env, "java/lang/IllegalArgumentException", \
                        "Image dimensions (w=%d,h=%d) are invalid, must match either the pixel "
                        "array size (w=%d, h=%d) or the pre-correction array size (w=%d, h=%d)",
                        width, height, pWidth, pHeight, cWidth, cHeight);
        return false;
    }

    return true;
}

/**
 * Write CFA pattern for given CFA enum into cfaOut.  cfaOut must have length >= 4.
 * Returns OK on success, or a negative error code if the CFA enum was invalid.
 */
static status_t convertCFA(uint8_t cfaEnum, /*out*/uint8_t* cfaOut) {
    camera_metadata_enum_android_sensor_info_color_filter_arrangement_t cfa =
            static_cast<camera_metadata_enum_android_sensor_info_color_filter_arrangement_t>(
            cfaEnum);
    switch(cfa) {
        case ANDROID_SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB: {
            cfaOut[0] = 0;
            cfaOut[1] = 1;
            cfaOut[2] = 1;
            cfaOut[3] = 2;
            break;
        }
        case ANDROID_SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG: {
            cfaOut[0] = 1;
            cfaOut[1] = 0;
            cfaOut[2] = 2;
            cfaOut[3] = 1;
            break;
        }
        case ANDROID_SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG: {
            cfaOut[0] = 1;
            cfaOut[1] = 2;
            cfaOut[2] = 0;
            cfaOut[3] = 1;
            break;
        }
        case ANDROID_SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR: {
            cfaOut[0] = 2;
            cfaOut[1] = 1;
            cfaOut[2] = 1;
            cfaOut[3] = 0;
            break;
        }
        // MONO and NIR are degenerate case of RGGB pattern: only Red channel
        // will be used.
        case ANDROID_SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO:
        case ANDROID_SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NIR: {
            cfaOut[0] = 0;
            break;
        }
        default: {
            return BAD_VALUE;
        }
    }
    return OK;
}

/**
 * Convert the CFA layout enum to an OpcodeListBuilder::CfaLayout enum, defaults to
 * RGGB for an unknown enum.
 */
static OpcodeListBuilder::CfaLayout convertCFAEnumToOpcodeLayout(uint8_t cfaEnum) {
    camera_metadata_enum_android_sensor_info_color_filter_arrangement_t cfa =
            static_cast<camera_metadata_enum_android_sensor_info_color_filter_arrangement_t>(
            cfaEnum);
    switch(cfa) {
        case ANDROID_SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB: {
            return OpcodeListBuilder::CFA_RGGB;
        }
        case ANDROID_SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG: {
            return OpcodeListBuilder::CFA_GRBG;
        }
        case ANDROID_SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG: {
            return OpcodeListBuilder::CFA_GBRG;
        }
        case ANDROID_SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR: {
            return OpcodeListBuilder::CFA_BGGR;
        }
        default: {
            return OpcodeListBuilder::CFA_RGGB;
        }
    }
}

/**
 * For each color plane, find the corresponding noise profile coefficients given in the
 * per-channel noise profile.  If multiple channels in the CFA correspond to a color in the color
 * plane, this method takes the pair of noise profile coefficients with the higher S coefficient.
 *
 * perChannelNoiseProfile - numChannels * 2 noise profile coefficients.
 * cfa - numChannels color channels corresponding to each of the per-channel noise profile
 *       coefficients.
 * numChannels - the number of noise profile coefficient pairs and color channels given in
 *       the perChannelNoiseProfile and cfa arguments, respectively.
 * planeColors - the color planes in the noise profile output.
 * numPlanes - the number of planes in planeColors and pairs of coefficients in noiseProfile.
 * noiseProfile - 2 * numPlanes doubles containing numPlanes pairs of noise profile coefficients.
 *
 * returns OK, or a negative error code on failure.
 */
static status_t generateNoiseProfile(const double* perChannelNoiseProfile, uint8_t* cfa,
        size_t numChannels, const uint8_t* planeColors, size_t numPlanes,
        /*out*/double* noiseProfile) {

    for (size_t p = 0; p < numPlanes; ++p) {
        size_t S = p * 2;
        size_t O = p * 2 + 1;

        noiseProfile[S] = 0;
        noiseProfile[O] = 0;
        bool uninitialized = true;
        for (size_t c = 0; c < numChannels; ++c) {
            if (cfa[c] == planeColors[p] && perChannelNoiseProfile[c * 2] > noiseProfile[S]) {
                noiseProfile[S] = perChannelNoiseProfile[c * 2];
                noiseProfile[O] = perChannelNoiseProfile[c * 2 + 1];
                uninitialized = false;
            }
        }
        if (uninitialized) {
            ALOGE("%s: No valid NoiseProfile coefficients for color plane %zu",
                  __FUNCTION__, p);
            return BAD_VALUE;
        }
    }
    return OK;
}

static void undistort(/*inout*/double& x, /*inout*/double& y,
        const std::array<float, 6>& distortion,
        const float cx, const float cy, const float f) {
    double xp = (x - cx) / f;
    double yp = (y - cy) / f;

    double x2 = xp * xp;
    double y2 = yp * yp;
    double r2 = x2 + y2;
    double xy2 = 2.0 * xp * yp;

    const float k0 = distortion[0];
    const float k1 = distortion[1];
    const float k2 = distortion[2];
    const float k3 = distortion[3];
    const float p1 = distortion[4];
    const float p2 = distortion[5];

    double kr = k0 + ((k3 * r2 + k2) * r2 + k1) * r2;
    double xpp = xp * kr + p1 * xy2 + p2 * (r2 + 2.0 * x2);
    double ypp = yp * kr + p1 * (r2 + 2.0 * y2) + p2 * xy2;

    x = xpp * f + cx;
    y = ypp * f + cy;
    return;
}

static inline bool unDistortWithinPreCorrArray(
        double x, double y,
        const std::array<float, 6>& distortion,
        const float cx, const float cy, const float f,
        const int preCorrW, const int preCorrH, const int xMin, const int yMin) {
    undistort(x, y, distortion, cx, cy, f);
    // xMin and yMin are inclusive, and xMax and yMax are exclusive.
    int xMax = xMin + preCorrW;
    int yMax = yMin + preCorrH;
    if (x < xMin || y < yMin || x >= xMax || y >= yMax) {
        return false;
    }
    return true;
}

static inline bool boxWithinPrecorrectionArray(
        int left, int top, int right, int bottom,
        const std::array<float, 6>& distortion,
        const float cx, const float cy, const float f,
        const int preCorrW, const int preCorrH, const int xMin, const int yMin){
    // Top row
    if (!unDistortWithinPreCorrArray(left, top,
            distortion, cx, cy, f, preCorrW, preCorrH, xMin, yMin)) {
        return false;
    }

    if (!unDistortWithinPreCorrArray(cx, top,
            distortion, cx, cy, f, preCorrW, preCorrH, xMin, yMin)) {
        return false;
    }

    if (!unDistortWithinPreCorrArray(right, top,
            distortion, cx, cy, f, preCorrW, preCorrH, xMin, yMin)) {
        return false;
    }

    // Middle row
    if (!unDistortWithinPreCorrArray(left, cy,
            distortion, cx, cy, f, preCorrW, preCorrH, xMin, yMin)) {
        return false;
    }

    if (!unDistortWithinPreCorrArray(right, cy,
            distortion, cx, cy, f, preCorrW, preCorrH, xMin, yMin)) {
        return false;
    }

    // Bottom row
    if (!unDistortWithinPreCorrArray(left, bottom,
            distortion, cx, cy, f, preCorrW, preCorrH, xMin, yMin)) {
        return false;
    }

    if (!unDistortWithinPreCorrArray(cx, bottom,
            distortion, cx, cy, f, preCorrW, preCorrH, xMin, yMin)) {
        return false;
    }

    if (!unDistortWithinPreCorrArray(right, bottom,
            distortion, cx, cy, f, preCorrW, preCorrH, xMin, yMin)) {
        return false;
    }
    return true;
}

static inline bool scaledBoxWithinPrecorrectionArray(
        double scale/*must be <= 1.0*/,
        const std::array<float, 6>& distortion,
        const float cx, const float cy, const float f,
        const int preCorrW, const int preCorrH,
        const int xMin, const int yMin){

    double left = cx * (1.0 - scale);
    double right = (preCorrW - 1) * scale + cx * (1.0 - scale);
    double top = cy * (1.0 - scale);
    double bottom = (preCorrH - 1) * scale + cy * (1.0 - scale);

    return boxWithinPrecorrectionArray(left, top, right, bottom,
            distortion, cx, cy, f, preCorrW, preCorrH, xMin, yMin);
}

static status_t findPostCorrectionScale(
        double stepSize, double minScale,
        const std::array<float, 6>& distortion,
        const float cx, const float cy, const float f,
        const int preCorrW, const int preCorrH, const int xMin, const int yMin,
        /*out*/ double* outScale) {
    if (outScale == nullptr) {
        ALOGE("%s: outScale must not be null", __FUNCTION__);
        return BAD_VALUE;
    }

    for (double scale = 1.0; scale > minScale; scale -= stepSize) {
        if (scaledBoxWithinPrecorrectionArray(
                scale, distortion, cx, cy, f, preCorrW, preCorrH, xMin, yMin)) {
            *outScale = scale;
            return OK;
        }
    }
    ALOGE("%s: cannot find cropping scale for lens distortion: stepSize %f, minScale %f",
            __FUNCTION__, stepSize, minScale);
    return BAD_VALUE;
}

// Apply a scale factor to distortion coefficients so that the image is zoomed out and all pixels
// are sampled within the precorrection array
static void normalizeLensDistortion(
        /*inout*/std::array<float, 6>& distortion,
        float cx, float cy, float f, int preCorrW, int preCorrH, int xMin = 0, int yMin = 0) {
    ALOGV("%s: distortion [%f, %f, %f, %f, %f, %f], (cx,cy) (%f, %f), f %f, (W,H) (%d, %d)"
            ", (xmin, ymin, xmax, ymax) (%d, %d, %d, %d)",
            __FUNCTION__, distortion[0], distortion[1], distortion[2],
            distortion[3], distortion[4], distortion[5],
            cx, cy, f, preCorrW, preCorrH,
            xMin, yMin, xMin + preCorrW - 1, yMin + preCorrH - 1);

    // Only update distortion coeffients if we can find a good bounding box
    double scale = 1.0;
    if (OK == findPostCorrectionScale(0.002, 0.5,
            distortion, cx, cy, f, preCorrW, preCorrH, xMin, yMin,
            /*out*/&scale)) {
        ALOGV("%s: scaling distortion coefficients by %f", __FUNCTION__, scale);
        // The formula:
        // xc = xi * (k0 + k1*r^2 + k2*r^4 + k3*r^6) + k4 * (2*xi*yi) + k5 * (r^2 + 2*xi^2)
        // To create effective zoom we want to replace xi by xi *m, yi by yi*m and r^2 by r^2*m^2
        // Factor the extra m power terms into k0~k6
        std::array<float, 6> scalePowers = {1, 3, 5, 7, 2, 2};
        for (size_t i = 0; i < 6; i++) {
            distortion[i] *= pow(scale, scalePowers[i]);
        }
    }
    return;
}

// ----------------------------------------------------------------------------
extern "C" {

static NativeContext* DngCreator_getNativeContext(JNIEnv* env, jobject thiz) {
    ALOGV("%s:", __FUNCTION__);
    return reinterpret_cast<NativeContext*>(env->GetLongField(thiz,
            gDngCreatorClassInfo.mNativeContext));
}

static void DngCreator_setNativeContext(JNIEnv* env, jobject thiz, sp<NativeContext> context) {
    ALOGV("%s:", __FUNCTION__);
    NativeContext* current = DngCreator_getNativeContext(env, thiz);

    if (context != nullptr) {
        context->incStrong((void*) DngCreator_setNativeContext);
    }

    if (current) {
        current->decStrong((void*) DngCreator_setNativeContext);
    }

    env->SetLongField(thiz, gDngCreatorClassInfo.mNativeContext,
            reinterpret_cast<jlong>(context.get()));
}

static void DngCreator_nativeClassInit(JNIEnv* env, jclass clazz) {
    ALOGV("%s:", __FUNCTION__);

    gDngCreatorClassInfo.mNativeContext = GetFieldIDOrDie(env,
            clazz, ANDROID_DNGCREATOR_CTX_JNI_ID, "J");

    jclass outputStreamClazz = FindClassOrDie(env, "java/io/OutputStream");
    gOutputStreamClassInfo.mWriteMethod = GetMethodIDOrDie(env,
            outputStreamClazz, "write", "([BII)V");

    jclass inputStreamClazz = FindClassOrDie(env, "java/io/InputStream");
    gInputStreamClassInfo.mReadMethod = GetMethodIDOrDie(env, inputStreamClazz, "read", "([BII)I");
    gInputStreamClassInfo.mSkipMethod = GetMethodIDOrDie(env, inputStreamClazz, "skip", "(J)J");

    jclass inputBufferClazz = FindClassOrDie(env, "java/nio/ByteBuffer");
    gInputByteBufferClassInfo.mGetMethod = GetMethodIDOrDie(env,
            inputBufferClazz, "get", "([BII)Ljava/nio/ByteBuffer;");
}

static void DngCreator_init(JNIEnv* env, jobject thiz, jobject characteristicsPtr,
        jobject resultsPtr, jstring formattedCaptureTime) {
    ALOGV("%s:", __FUNCTION__);
    CameraMetadata characteristics;
    CameraMetadata results;
    if (CameraMetadata_getNativeMetadata(env, characteristicsPtr, &characteristics) != OK) {
         jniThrowException(env, "java/lang/AssertionError",
                "No native metadata defined for camera characteristics.");
         return;
    }
    if (CameraMetadata_getNativeMetadata(env, resultsPtr, &results) != OK) {
        jniThrowException(env, "java/lang/AssertionError",
                "No native metadata defined for capture results.");
        return;
    }

    sp<NativeContext> nativeContext = new NativeContext(characteristics, results);

    ScopedUtfChars captureTime(env, formattedCaptureTime);
    if (captureTime.size() + 1 != NativeContext::DATETIME_COUNT) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                "Formatted capture time string length is not required 20 characters");
        return;
    }

    nativeContext->setCaptureTime(String8(captureTime.c_str()));

    DngCreator_setNativeContext(env, thiz, nativeContext);
}

static sp<TiffWriter> DngCreator_setup(JNIEnv* env, jobject thiz, uint32_t imageWidth,
        uint32_t imageHeight) {

    NativeContext* nativeContext = DngCreator_getNativeContext(env, thiz);

    if (nativeContext == nullptr) {
        jniThrowException(env, "java/lang/AssertionError",
                "No native context, must call init before other operations.");
        return nullptr;
    }

    CameraMetadata characteristics = *(nativeContext->getCharacteristics());
    CameraMetadata results = *(nativeContext->getResult());

    sp<TiffWriter> writer = new TiffWriter();

    uint32_t preXMin = 0;
    uint32_t preYMin = 0;
    uint32_t preWidth = 0;
    uint32_t preHeight = 0;
    uint8_t colorFilter = 0;
    bool isBayer = true;
    bool isMaximumResolutionMode =
            isMaximumResolutionModeImage(characteristics, imageWidth, imageHeight, writer, env);
    {
        // Check dimensions
        camera_metadata_entry entry = characteristics.find(
                getAppropriateModeTag(ANDROID_SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE,
                                      isMaximumResolutionMode));
        BAIL_IF_EMPTY_RET_NULL_SP(entry, env, TAG_IMAGEWIDTH, writer);
        preXMin = static_cast<uint32_t>(entry.data.i32[0]);
        preYMin = static_cast<uint32_t>(entry.data.i32[1]);
        preWidth = static_cast<uint32_t>(entry.data.i32[2]);
        preHeight = static_cast<uint32_t>(entry.data.i32[3]);

        camera_metadata_entry pixelArrayEntry =
                characteristics.find(getAppropriateModeTag(ANDROID_SENSOR_INFO_PIXEL_ARRAY_SIZE,
                                                           isMaximumResolutionMode));

        BAIL_IF_EMPTY_RET_NULL_SP(pixelArrayEntry, env, TAG_IMAGEWIDTH, writer);
        uint32_t pixWidth = static_cast<uint32_t>(pixelArrayEntry.data.i32[0]);
        uint32_t pixHeight = static_cast<uint32_t>(pixelArrayEntry.data.i32[1]);

        if (!((imageWidth == preWidth && imageHeight == preHeight) ||
                (imageWidth == pixWidth && imageHeight == pixHeight))) {
            jniThrowException(env, "java/lang/AssertionError",
                              "Height and width of image buffer did not match height and width of"
                              " either the preCorrectionActiveArraySize or the pixelArraySize.");
            return nullptr;
        }

        camera_metadata_entry colorFilterEntry =
                characteristics.find(ANDROID_SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
        colorFilter = colorFilterEntry.data.u8[0];
        camera_metadata_entry capabilitiesEntry =
                characteristics.find(ANDROID_REQUEST_AVAILABLE_CAPABILITIES);
        size_t capsCount = capabilitiesEntry.count;
        uint8_t* caps = capabilitiesEntry.data.u8;
        if (std::find(caps, caps+capsCount, ANDROID_REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME)
                != caps+capsCount) {
            isBayer = false;
        } else if (colorFilter == ANDROID_SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO ||
                colorFilter == ANDROID_SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NIR) {
            jniThrowException(env, "java/lang/AssertionError",
                    "A camera device with MONO/NIR color filter must have MONOCHROME capability.");
            return nullptr;
        }
    }

    writer->addIfd(TIFF_IFD_0);

    status_t err = OK;

    const uint32_t samplesPerPixel = 1;
    const uint32_t bitsPerSample = BITS_PER_SAMPLE;

    OpcodeListBuilder::CfaLayout opcodeCfaLayout = OpcodeListBuilder::CFA_NONE;
    uint8_t cfaPlaneColor[3] = {0, 1, 2};
    camera_metadata_entry cfaEntry =
            characteristics.find(ANDROID_SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
    BAIL_IF_EMPTY_RET_NULL_SP(cfaEntry, env, TAG_CFAPATTERN, writer);
    uint8_t cfaEnum = cfaEntry.data.u8[0];

    // TODO: Greensplit.
    // TODO: Add remaining non-essential tags

    // Setup main image tags

    {
        // Set orientation
        uint16_t orientation = TAG_ORIENTATION_NORMAL;
        BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_ORIENTATION, 1, &orientation, TIFF_IFD_0),
                env, TAG_ORIENTATION, writer);
    }

    {
        // Set subfiletype
        uint32_t subfileType = 0; // Main image
        BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_NEWSUBFILETYPE, 1, &subfileType,
                TIFF_IFD_0), env, TAG_NEWSUBFILETYPE, writer);
    }

    {
        // Set bits per sample
        uint16_t bits = static_cast<uint16_t>(bitsPerSample);
        BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_BITSPERSAMPLE, 1, &bits, TIFF_IFD_0), env,
                TAG_BITSPERSAMPLE, writer);
    }

    {
        // Set compression
        uint16_t compression = 1; // None
        BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_COMPRESSION, 1, &compression,
                TIFF_IFD_0), env, TAG_COMPRESSION, writer);
    }

    {
        // Set dimensions
        BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_IMAGEWIDTH, 1, &imageWidth, TIFF_IFD_0),
                env, TAG_IMAGEWIDTH, writer);
        BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_IMAGELENGTH, 1, &imageHeight, TIFF_IFD_0),
                env, TAG_IMAGELENGTH, writer);
    }

    {
        // Set photometric interpretation
        uint16_t interpretation = isBayer ? 32803 /* CFA */ :
                34892; /* Linear Raw */;
        BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_PHOTOMETRICINTERPRETATION, 1,
                &interpretation, TIFF_IFD_0), env, TAG_PHOTOMETRICINTERPRETATION, writer);
    }

    {
        uint16_t repeatDim[2] = {2, 2};
        if (!isBayer) {
            repeatDim[0] = repeatDim[1] = 1;
        }
        BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_BLACKLEVELREPEATDIM, 2, repeatDim,
                TIFF_IFD_0), env, TAG_BLACKLEVELREPEATDIM, writer);

        // Set blacklevel tags, using dynamic black level if available
        camera_metadata_entry entry =
                results.find(ANDROID_SENSOR_DYNAMIC_BLACK_LEVEL);
        uint32_t blackLevelRational[8] = {0};
        if (entry.count != 0) {
            BAIL_IF_EXPR_RET_NULL_SP(entry.count != 4, env, TAG_BLACKLEVEL, writer);
            for (size_t i = 0; i < entry.count; i++) {
                blackLevelRational[i * 2] = static_cast<uint32_t>(entry.data.f[i] * 100);
                blackLevelRational[i * 2 + 1] = 100;
            }
        } else {
            // Fall back to static black level which is guaranteed
            entry = characteristics.find(ANDROID_SENSOR_BLACK_LEVEL_PATTERN);
            BAIL_IF_EXPR_RET_NULL_SP(entry.count != 4, env, TAG_BLACKLEVEL, writer);
            for (size_t i = 0; i < entry.count; i++) {
                blackLevelRational[i * 2] = static_cast<uint32_t>(entry.data.i32[i]);
                blackLevelRational[i * 2 + 1] = 1;
            }
        }
        BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_BLACKLEVEL, repeatDim[0]*repeatDim[1],
                blackLevelRational, TIFF_IFD_0), env, TAG_BLACKLEVEL, writer);
    }

    {
        // Set samples per pixel
        uint16_t samples = static_cast<uint16_t>(samplesPerPixel);
        BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_SAMPLESPERPIXEL, 1, &samples, TIFF_IFD_0),
                env, TAG_SAMPLESPERPIXEL, writer);
    }

    {
        // Set planar configuration
        uint16_t config = 1; // Chunky
        BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_PLANARCONFIGURATION, 1, &config,
                TIFF_IFD_0), env, TAG_PLANARCONFIGURATION, writer);
    }

    // All CFA pattern tags are not necessary for monochrome cameras.
    if (isBayer) {
        // Set CFA pattern dimensions
        uint16_t repeatDim[2] = {2, 2};
        BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_CFAREPEATPATTERNDIM, 2, repeatDim,
                TIFF_IFD_0), env, TAG_CFAREPEATPATTERNDIM, writer);

        // Set CFA pattern
        const int cfaLength = 4;
        uint8_t cfa[cfaLength];
        if ((err = convertCFA(cfaEnum, /*out*/cfa)) != OK) {
            jniThrowExceptionFmt(env, "java/lang/IllegalStateException",
                        "Invalid metadata for tag %d", TAG_CFAPATTERN);
        }

        BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_CFAPATTERN, cfaLength, cfa, TIFF_IFD_0),
                env, TAG_CFAPATTERN, writer);

        opcodeCfaLayout = convertCFAEnumToOpcodeLayout(cfaEnum);

        // Set CFA plane color
        BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_CFAPLANECOLOR, 3, cfaPlaneColor,
                TIFF_IFD_0), env, TAG_CFAPLANECOLOR, writer);

        // Set CFA layout
        uint16_t cfaLayout = 1;
        BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_CFALAYOUT, 1, &cfaLayout, TIFF_IFD_0),
                env, TAG_CFALAYOUT, writer);
    }

    {
        // image description
        uint8_t imageDescription = '\0'; // empty
        BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_IMAGEDESCRIPTION, 1, &imageDescription,
                TIFF_IFD_0), env, TAG_IMAGEDESCRIPTION, writer);
    }

    {
        // make
        // Use "" to represent unknown make as suggested in TIFF/EP spec.
        std::string manufacturer = GetProperty("ro.product.manufacturer", "");
        uint32_t count = static_cast<uint32_t>(manufacturer.size()) + 1;

        BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_MAKE, count,
                reinterpret_cast<const uint8_t*>(manufacturer.c_str()), TIFF_IFD_0), env, TAG_MAKE,
                writer);
    }

    {
        // model
        // Use "" to represent unknown model as suggested in TIFF/EP spec.
        std::string model = GetProperty("ro.product.model", "");
        uint32_t count = static_cast<uint32_t>(model.size()) + 1;

        BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_MODEL, count,
                reinterpret_cast<const uint8_t*>(model.c_str()), TIFF_IFD_0), env, TAG_MODEL,
                writer);
    }

    {
        // x resolution
        uint32_t xres[] = { 72, 1 }; // default 72 ppi
        BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_XRESOLUTION, 1, xres, TIFF_IFD_0),
                env, TAG_XRESOLUTION, writer);

        // y resolution
        uint32_t yres[] = { 72, 1 }; // default 72 ppi
        BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_YRESOLUTION, 1, yres, TIFF_IFD_0),
                env, TAG_YRESOLUTION, writer);

        uint16_t unit = 2; // inches
        BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_RESOLUTIONUNIT, 1, &unit, TIFF_IFD_0),
                env, TAG_RESOLUTIONUNIT, writer);
    }

    {
        // software
        std::string software = GetProperty("ro.build.fingerprint", "");
        uint32_t count = static_cast<uint32_t>(software.size()) + 1;
        BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_SOFTWARE, count,
                reinterpret_cast<const uint8_t*>(software.c_str()), TIFF_IFD_0), env, TAG_SOFTWARE,
                writer);
    }

    if (nativeContext->hasCaptureTime()) {
        // datetime
        String8 captureTime = nativeContext->getCaptureTime();

        if (writer->addEntry(TAG_DATETIME, NativeContext::DATETIME_COUNT,
                             reinterpret_cast<const uint8_t*>(captureTime.c_str()),
                             TIFF_IFD_0) != OK) {
            jniThrowExceptionFmt(env, "java/lang/IllegalArgumentException",
                    "Invalid metadata for tag %x", TAG_DATETIME);
            return nullptr;
        }

        // datetime original
        if (writer->addEntry(TAG_DATETIMEORIGINAL, NativeContext::DATETIME_COUNT,
                             reinterpret_cast<const uint8_t*>(captureTime.c_str()),
                             TIFF_IFD_0) != OK) {
            jniThrowExceptionFmt(env, "java/lang/IllegalArgumentException",
                    "Invalid metadata for tag %x", TAG_DATETIMEORIGINAL);
            return nullptr;
        }
    }

    {
        // TIFF/EP standard id
        uint8_t standardId[] = { 1, 0, 0, 0 };
        BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_TIFFEPSTANDARDID, 4, standardId,
                TIFF_IFD_0), env, TAG_TIFFEPSTANDARDID, writer);
    }

    {
        // copyright
        uint8_t copyright = '\0'; // empty
        BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_COPYRIGHT, 1, &copyright,
                TIFF_IFD_0), env, TAG_COPYRIGHT, writer);
    }

    {
        // exposure time
        camera_metadata_entry entry =
            results.find(ANDROID_SENSOR_EXPOSURE_TIME);
        BAIL_IF_EMPTY_RET_NULL_SP(entry, env, TAG_EXPOSURETIME, writer);

        int64_t exposureTime = *(entry.data.i64);

        if (exposureTime < 0) {
            // Should be unreachable
            jniThrowException(env, "java/lang/IllegalArgumentException",
                    "Negative exposure time in metadata");
            return nullptr;
        }

        // Ensure exposure time doesn't overflow (for exposures > 4s)
        uint32_t denominator = 1000000000;
        while (exposureTime > UINT32_MAX) {
            exposureTime >>= 1;
            denominator >>= 1;
            if (denominator == 0) {
                // Should be unreachable
                jniThrowException(env, "java/lang/IllegalArgumentException",
                        "Exposure time too long");
                return nullptr;
            }
        }

        uint32_t exposure[] = { static_cast<uint32_t>(exposureTime), denominator };
        BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_EXPOSURETIME, 1, exposure,
                TIFF_IFD_0), env, TAG_EXPOSURETIME, writer);

    }

    {
        // ISO speed ratings
        camera_metadata_entry entry =
            results.find(ANDROID_SENSOR_SENSITIVITY);
        BAIL_IF_EMPTY_RET_NULL_SP(entry, env, TAG_ISOSPEEDRATINGS, writer);

        int32_t tempIso = *(entry.data.i32);
        if (tempIso < 0) {
            jniThrowException(env, "java/lang/IllegalArgumentException",
                                    "Negative ISO value");
            return nullptr;
        }

        if (tempIso > UINT16_MAX) {
            ALOGW("%s: ISO value overflows UINT16_MAX, clamping to max", __FUNCTION__);
            tempIso = UINT16_MAX;
        }

        uint16_t iso = static_cast<uint16_t>(tempIso);
        BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_ISOSPEEDRATINGS, 1, &iso,
                TIFF_IFD_0), env, TAG_ISOSPEEDRATINGS, writer);
    }

    {
        // Baseline exposure
        camera_metadata_entry entry =
                results.find(ANDROID_CONTROL_POST_RAW_SENSITIVITY_BOOST);
        BAIL_IF_EMPTY_RET_NULL_SP(entry, env, TAG_BASELINEEXPOSURE, writer);

        // post RAW gain should be boostValue / 100
        double postRAWGain = static_cast<double> (entry.data.i32[0]) / 100.f;
        // Baseline exposure should be in EV units so log2(gain) =
        // log10(gain)/log10(2)
        double baselineExposure = std::log(postRAWGain) / std::log(2.0f);
        int32_t baseExposureSRat[] = { static_cast<int32_t> (baselineExposure * 100),
                100 };
        BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_BASELINEEXPOSURE, 1,
                baseExposureSRat, TIFF_IFD_0), env, TAG_BASELINEEXPOSURE, writer);
    }

    {
        // focal length
        camera_metadata_entry entry =
            results.find(ANDROID_LENS_FOCAL_LENGTH);
        BAIL_IF_EMPTY_RET_NULL_SP(entry, env, TAG_FOCALLENGTH, writer);

        uint32_t focalLength[] = { static_cast<uint32_t>(*(entry.data.f) * 100), 100 };
        BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_FOCALLENGTH, 1, focalLength,
                TIFF_IFD_0), env, TAG_FOCALLENGTH, writer);
    }

    {
        // f number
        camera_metadata_entry entry =
            results.find(ANDROID_LENS_APERTURE);
        BAIL_IF_EMPTY_RET_NULL_SP(entry, env, TAG_FNUMBER, writer);

        uint32_t fnum[] = { static_cast<uint32_t>(*(entry.data.f) * 100), 100 };
        BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_FNUMBER, 1, fnum,
                TIFF_IFD_0), env, TAG_FNUMBER, writer);
    }

    {
        // Set DNG version information
        uint8_t version[4] = {1, 4, 0, 0};
        BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_DNGVERSION, 4, version, TIFF_IFD_0),
                env, TAG_DNGVERSION, writer);

        uint8_t backwardVersion[4] = {1, 1, 0, 0};
        BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_DNGBACKWARDVERSION, 4, backwardVersion,
                TIFF_IFD_0), env, TAG_DNGBACKWARDVERSION, writer);
    }

    {
        // Set whitelevel
        camera_metadata_entry entry =
                characteristics.find(ANDROID_SENSOR_INFO_WHITE_LEVEL);
        BAIL_IF_EMPTY_RET_NULL_SP(entry, env, TAG_WHITELEVEL, writer);
        uint32_t whiteLevel = static_cast<uint32_t>(entry.data.i32[0]);
        BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_WHITELEVEL, 1, &whiteLevel, TIFF_IFD_0),
                env, TAG_WHITELEVEL, writer);
    }

    {
        // Set default scale
        uint32_t defaultScale[4] = {1, 1, 1, 1};
        BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_DEFAULTSCALE, 2, defaultScale,
                TIFF_IFD_0), env, TAG_DEFAULTSCALE, writer);
    }

    bool singleIlluminant = false;
    if (isBayer) {
        // Set calibration illuminants
        camera_metadata_entry entry1 =
            characteristics.find(ANDROID_SENSOR_REFERENCE_ILLUMINANT1);
        BAIL_IF_EMPTY_RET_NULL_SP(entry1, env, TAG_CALIBRATIONILLUMINANT1, writer);
        camera_metadata_entry entry2 =
            characteristics.find(ANDROID_SENSOR_REFERENCE_ILLUMINANT2);
        if (entry2.count == 0) {
            singleIlluminant = true;
        }
        uint16_t ref1 = entry1.data.u8[0];

        BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_CALIBRATIONILLUMINANT1, 1, &ref1,
                TIFF_IFD_0), env, TAG_CALIBRATIONILLUMINANT1, writer);

        if (!singleIlluminant) {
            uint16_t ref2 = entry2.data.u8[0];
            BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_CALIBRATIONILLUMINANT2, 1, &ref2,
                    TIFF_IFD_0), env, TAG_CALIBRATIONILLUMINANT2, writer);
        }
    }

    if (isBayer) {
        // Set color transforms
        camera_metadata_entry entry1 =
            characteristics.find(ANDROID_SENSOR_COLOR_TRANSFORM1);
        BAIL_IF_EMPTY_RET_NULL_SP(entry1, env, TAG_COLORMATRIX1, writer);

        int32_t colorTransform1[entry1.count * 2];

        size_t ctr = 0;
        for(size_t i = 0; i < entry1.count; ++i) {
            colorTransform1[ctr++] = entry1.data.r[i].numerator;
            colorTransform1[ctr++] = entry1.data.r[i].denominator;
        }

        BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_COLORMATRIX1, entry1.count,
                colorTransform1, TIFF_IFD_0), env, TAG_COLORMATRIX1, writer);

        if (!singleIlluminant) {
            camera_metadata_entry entry2 = characteristics.find(ANDROID_SENSOR_COLOR_TRANSFORM2);
            BAIL_IF_EMPTY_RET_NULL_SP(entry2, env, TAG_COLORMATRIX2, writer);
            int32_t colorTransform2[entry2.count * 2];

            ctr = 0;
            for(size_t i = 0; i < entry2.count; ++i) {
                colorTransform2[ctr++] = entry2.data.r[i].numerator;
                colorTransform2[ctr++] = entry2.data.r[i].denominator;
            }

            BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_COLORMATRIX2, entry2.count,
                    colorTransform2, TIFF_IFD_0), env, TAG_COLORMATRIX2, writer);
        }
    }

    if (isBayer) {
        // Set calibration transforms
        camera_metadata_entry entry1 =
            characteristics.find(ANDROID_SENSOR_CALIBRATION_TRANSFORM1);
        BAIL_IF_EMPTY_RET_NULL_SP(entry1, env, TAG_CAMERACALIBRATION1, writer);

        int32_t calibrationTransform1[entry1.count * 2];

        size_t ctr = 0;
        for(size_t i = 0; i < entry1.count; ++i) {
            calibrationTransform1[ctr++] = entry1.data.r[i].numerator;
            calibrationTransform1[ctr++] = entry1.data.r[i].denominator;
        }

        BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_CAMERACALIBRATION1, entry1.count,
                calibrationTransform1, TIFF_IFD_0), env, TAG_CAMERACALIBRATION1, writer);

        if (!singleIlluminant) {
            camera_metadata_entry entry2 =
                characteristics.find(ANDROID_SENSOR_CALIBRATION_TRANSFORM2);
            BAIL_IF_EMPTY_RET_NULL_SP(entry2, env, TAG_CAMERACALIBRATION2, writer);
            int32_t calibrationTransform2[entry2.count * 2];

            ctr = 0;
            for(size_t i = 0; i < entry2.count; ++i) {
                calibrationTransform2[ctr++] = entry2.data.r[i].numerator;
                calibrationTransform2[ctr++] = entry2.data.r[i].denominator;
            }

            BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_CAMERACALIBRATION2, entry2.count,
                    calibrationTransform2, TIFF_IFD_0),  env, TAG_CAMERACALIBRATION2, writer);
        }
    }

    if (isBayer) {
        // Set forward transforms
        camera_metadata_entry entry1 =
            characteristics.find(ANDROID_SENSOR_FORWARD_MATRIX1);
        BAIL_IF_EMPTY_RET_NULL_SP(entry1, env, TAG_FORWARDMATRIX1, writer);

        int32_t forwardTransform1[entry1.count * 2];

        size_t ctr = 0;
        for(size_t i = 0; i < entry1.count; ++i) {
            forwardTransform1[ctr++] = entry1.data.r[i].numerator;
            forwardTransform1[ctr++] = entry1.data.r[i].denominator;
        }

        BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_FORWARDMATRIX1, entry1.count,
                forwardTransform1, TIFF_IFD_0), env, TAG_FORWARDMATRIX1, writer);

        if (!singleIlluminant) {
            camera_metadata_entry entry2 =
                characteristics.find(ANDROID_SENSOR_FORWARD_MATRIX2);
            BAIL_IF_EMPTY_RET_NULL_SP(entry2, env, TAG_FORWARDMATRIX2, writer);
            int32_t forwardTransform2[entry2.count * 2];

            ctr = 0;
            for(size_t i = 0; i < entry2.count; ++i) {
                forwardTransform2[ctr++] = entry2.data.r[i].numerator;
                forwardTransform2[ctr++] = entry2.data.r[i].denominator;
            }

            BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_FORWARDMATRIX2, entry2.count,
                    forwardTransform2, TIFF_IFD_0),  env, TAG_FORWARDMATRIX2, writer);
        }
    }

    if (isBayer) {
        // Set camera neutral
        camera_metadata_entry entry =
            results.find(ANDROID_SENSOR_NEUTRAL_COLOR_POINT);
        BAIL_IF_EMPTY_RET_NULL_SP(entry, env, TAG_ASSHOTNEUTRAL, writer);
        uint32_t cameraNeutral[entry.count * 2];

        size_t ctr = 0;
        for(size_t i = 0; i < entry.count; ++i) {
            cameraNeutral[ctr++] =
                    static_cast<uint32_t>(entry.data.r[i].numerator);
            cameraNeutral[ctr++] =
                    static_cast<uint32_t>(entry.data.r[i].denominator);
        }

        BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_ASSHOTNEUTRAL, entry.count, cameraNeutral,
                TIFF_IFD_0), env, TAG_ASSHOTNEUTRAL, writer);
    }


    {
        // Set dimensions
        if (calculateAndSetCrop(env, characteristics, writer, isMaximumResolutionMode) != OK) {
            return nullptr;
        }
        camera_metadata_entry entry = characteristics.find(
                getAppropriateModeTag(ANDROID_SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE,
                                      isMaximumResolutionMode));
        BAIL_IF_EMPTY_RET_NULL_SP(entry, env, TAG_ACTIVEAREA, writer);
        uint32_t xmin = static_cast<uint32_t>(entry.data.i32[0]);
        uint32_t ymin = static_cast<uint32_t>(entry.data.i32[1]);
        uint32_t width = static_cast<uint32_t>(entry.data.i32[2]);
        uint32_t height = static_cast<uint32_t>(entry.data.i32[3]);

        // If we only have a buffer containing the pre-correction rectangle, ignore the offset
        // relative to the pixel array.
        if (imageWidth == width && imageHeight == height) {
            xmin = 0;
            ymin = 0;
        }

        uint32_t activeArea[] = {ymin, xmin, ymin + height, xmin + width};
        BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_ACTIVEAREA, 4, activeArea, TIFF_IFD_0),
                env, TAG_ACTIVEAREA, writer);
    }

    {
        // Setup unique camera model tag
        std::string model = GetProperty("ro.product.model", "");
        std::string manufacturer = GetProperty("ro.product.manufacturer", "");
        std::string brand = GetProperty("ro.product.brand", "");

        String8 cameraModel(model.c_str());
        cameraModel += "-";
        cameraModel += manufacturer.c_str();
        cameraModel += "-";
        cameraModel += brand.c_str();

        BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_UNIQUECAMERAMODEL, cameraModel.size() + 1,
                                                     reinterpret_cast<const uint8_t*>(
                                                             cameraModel.c_str()),
                                                     TIFF_IFD_0),
                                    env, TAG_UNIQUECAMERAMODEL, writer);
    }

    {
        // Setup sensor noise model
        camera_metadata_entry entry =
            results.find(ANDROID_SENSOR_NOISE_PROFILE);

        const unsigned long numPlaneColors = isBayer ? 3 : 1;
        const unsigned long numCfaChannels = isBayer ? 4 : 1;

        uint8_t cfaOut[numCfaChannels];
        if ((err = convertCFA(cfaEnum, /*out*/cfaOut)) != OK) {
            jniThrowException(env, "java/lang/IllegalArgumentException",
                    "Invalid CFA from camera characteristics");
            return nullptr;
        }

        double noiseProfile[numPlaneColors * 2];

        if (entry.count > 0) {
            if (entry.count != numCfaChannels * 2) {
                ALOGW("%s: Invalid entry count %zu for noise profile returned "
                      "in characteristics, no noise profile tag written...",
                      __FUNCTION__, entry.count);
            } else {
                if ((err = generateNoiseProfile(entry.data.d, cfaOut, numCfaChannels,
                        cfaPlaneColor, numPlaneColors, /*out*/ noiseProfile)) == OK) {

                    BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_NOISEPROFILE,
                            numPlaneColors * 2, noiseProfile, TIFF_IFD_0), env, TAG_NOISEPROFILE,
                            writer);
                } else {
                    ALOGW("%s: Error converting coefficients for noise profile, no noise profile"
                            " tag written...", __FUNCTION__);
                }
            }
        } else {
            ALOGW("%s: No noise profile found in result metadata.  Image quality may be reduced.",
                    __FUNCTION__);
        }
    }

    {
        // Set up opcode List 2
        OpcodeListBuilder builder;
        status_t err = OK;

        // Set up lens shading map
        camera_metadata_entry entry1 =
                characteristics.find(ANDROID_LENS_INFO_SHADING_MAP_SIZE);

        uint32_t lsmWidth = 0;
        uint32_t lsmHeight = 0;

        if (entry1.count != 0) {
            lsmWidth = static_cast<uint32_t>(entry1.data.i32[0]);
            lsmHeight = static_cast<uint32_t>(entry1.data.i32[1]);
        }

        camera_metadata_entry entry2 = results.find(ANDROID_STATISTICS_LENS_SHADING_MAP);

        camera_metadata_entry entry = characteristics.find(
                getAppropriateModeTag(ANDROID_SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE,
                                      isMaximumResolutionMode));
        BAIL_IF_EMPTY_RET_NULL_SP(entry, env, TAG_IMAGEWIDTH, writer);
        uint32_t xmin = static_cast<uint32_t>(entry.data.i32[0]);
        uint32_t ymin = static_cast<uint32_t>(entry.data.i32[1]);
        uint32_t width = static_cast<uint32_t>(entry.data.i32[2]);
        uint32_t height = static_cast<uint32_t>(entry.data.i32[3]);
        if (entry2.count > 0 && entry2.count == lsmWidth * lsmHeight * 4) {
            // GainMap rectangle is relative to the active area origin.
            err = builder.addGainMapsForMetadata(lsmWidth,
                                                 lsmHeight,
                                                 0,
                                                 0,
                                                 height,
                                                 width,
                                                 opcodeCfaLayout,
                                                 entry2.data.f);
            if (err != OK) {
                ALOGE("%s: Could not add Lens shading map.", __FUNCTION__);
                jniThrowRuntimeException(env, "failed to add lens shading map.");
                return nullptr;
            }
        }

        // Hot pixel map is specific to bayer camera per DNG spec.
        if (isBayer) {
            // Set up bad pixel correction list
            // We first check the capture result. If the hot pixel map is not
            // available, as a fallback, try the static characteristics.
            camera_metadata_entry entry3 = results.find(ANDROID_STATISTICS_HOT_PIXEL_MAP);
            if (entry3.count == 0) {
                entry3 = characteristics.find(ANDROID_STATISTICS_HOT_PIXEL_MAP);
            }

            if ((entry3.count % 2) != 0) {
                ALOGE("%s: Hot pixel map contains odd number of values, cannot map to pairs!",
                        __FUNCTION__);
                jniThrowRuntimeException(env, "failed to add hotpixel map.");
                return nullptr;
            }

            // Adjust the bad pixel coordinates to be relative to the origin of the active area
            // DNG tag
            std::vector<uint32_t> v;
            for (size_t i = 0; i < entry3.count; i += 2) {
                int32_t x = entry3.data.i32[i];
                int32_t y = entry3.data.i32[i + 1];
                x -= static_cast<int32_t>(xmin);
                y -= static_cast<int32_t>(ymin);
                if (x < 0 || y < 0 || static_cast<uint32_t>(x) >= width ||
                        static_cast<uint32_t>(y) >= height) {
                    continue;
                }
                v.push_back(x);
                v.push_back(y);
            }
            const uint32_t* badPixels = &v[0];
            uint32_t badPixelCount = v.size();

            if (badPixelCount > 0) {
                err = builder.addBadPixelListForMetadata(badPixels, badPixelCount, opcodeCfaLayout);

                if (err != OK) {
                    ALOGE("%s: Could not add hotpixel map.", __FUNCTION__);
                    jniThrowRuntimeException(env, "failed to add hotpixel map.");
                    return nullptr;
                }
            }
        }

        if (builder.getCount() > 0) {
            size_t listSize = builder.getSize();
            uint8_t opcodeListBuf[listSize];
            err = builder.buildOpList(opcodeListBuf);
            if (err == OK) {
                BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_OPCODELIST2, listSize,
                        opcodeListBuf, TIFF_IFD_0), env, TAG_OPCODELIST2, writer);
            } else {
                ALOGE("%s: Could not build list of opcodes for lens shading map and bad pixel "
                        "correction.", __FUNCTION__);
                jniThrowRuntimeException(env, "failed to construct opcode list for lens shading "
                        "map and bad pixel correction");
                return nullptr;
            }
        }
    }

    {
        // Set up opcode List 3
        OpcodeListBuilder builder;
        status_t err = OK;

        // Set up rectilinear distortion correction
        std::array<float, 6> distortion = {1.f, 0.f, 0.f, 0.f, 0.f, 0.f};
        bool gotDistortion = false;

        // The capture result would have the correct intrinsic calibration
        // regardless of the sensor pixel mode.
        camera_metadata_entry entry4 =
                results.find(ANDROID_LENS_INTRINSIC_CALIBRATION);

        if (entry4.count == 5) {
            float cx = entry4.data.f[/*c_x*/2];
            float cy = entry4.data.f[/*c_y*/3];
            // Assuming f_x = f_y, or at least close enough.
            // Also assuming s = 0, or at least close enough.
            float f = entry4.data.f[/*f_x*/0];

            camera_metadata_entry entry3 =
                    results.find(ANDROID_LENS_DISTORTION);
            if (entry3.count == 5) {
                gotDistortion = true;

                // Scale the distortion coefficients to create a zoom in warpped image so that all
                // pixels are drawn within input image.
                for (size_t i = 0; i < entry3.count; i++) {
                    distortion[i+1] = entry3.data.f[i];
                }

                if (preWidth == imageWidth && preHeight == imageHeight) {
                    normalizeLensDistortion(distortion, cx, cy, f, preWidth, preHeight);
                } else {
                    // image size == pixel array size (contains optical black pixels)
                    // cx/cy is defined in preCorrArray so adding the offset
                    // Also changes default xmin/ymin so that pixels are only
                    // sampled within preCorrection array
                    normalizeLensDistortion(
                            distortion, cx + preXMin, cy + preYMin, f, preWidth, preHeight,
                            preXMin, preYMin);
                }

                float m_x = std::fmaxf(preWidth - cx, cx);
                float m_y = std::fmaxf(preHeight - cy, cy);
                float m_sq = m_x*m_x + m_y*m_y;
                float m = sqrtf(m_sq); // distance to farthest corner from optical center
                float f_sq = f * f;
                // Conversion factors from Camera2 K factors for new LENS_DISTORTION field
                // to DNG spec.
                //
                //       Camera2 / OpenCV assume distortion is applied in a space where focal length
                //       is factored out, while DNG assumes a normalized space where the distance
                //       from optical center to the farthest corner is 1.
                //       Scale from camera2 to DNG spec accordingly.
                //       distortion[0] is always 1 with the new LENS_DISTORTION field.
                const double convCoeff[5] = {
                    m_sq / f_sq,
                    pow(m_sq, 2) / pow(f_sq, 2),
                    pow(m_sq, 3) / pow(f_sq, 3),
                    m / f,
                    m / f
                };
                for (size_t i = 0; i < entry3.count; i++) {
                    distortion[i+1] *= convCoeff[i];
                }
            } else {
                entry3 = results.find(ANDROID_LENS_RADIAL_DISTORTION);
                if (entry3.count == 6) {
                    gotDistortion = true;
                    // Conversion factors from Camera2 K factors to DNG spec. K factors:
                    //
                    //      Note: these are necessary because our unit system assumes a
                    //      normalized max radius of sqrt(2), whereas the DNG spec's
                    //      WarpRectilinear opcode assumes a normalized max radius of 1.
                    //      Thus, each K coefficient must include the domain scaling
                    //      factor (the DNG domain is scaled by sqrt(2) to emulate the
                    //      domain used by the Camera2 specification).
                    const double convCoeff[6] = {
                        sqrt(2),
                        2 * sqrt(2),
                        4 * sqrt(2),
                        8 * sqrt(2),
                        2,
                        2
                    };
                    for (size_t i = 0; i < entry3.count; i++) {
                        distortion[i] = entry3.data.f[i] * convCoeff[i];
                    }
                }
            }
            if (gotDistortion) {
                err = builder.addWarpRectilinearForMetadata(
                        distortion.data(), preWidth, preHeight, cx, cy);
                if (err != OK) {
                    ALOGE("%s: Could not add distortion correction.", __FUNCTION__);
                    jniThrowRuntimeException(env, "failed to add distortion correction.");
                    return nullptr;
                }
            }
        }

        if (builder.getCount() > 0) {
            size_t listSize = builder.getSize();
            uint8_t opcodeListBuf[listSize];
            err = builder.buildOpList(opcodeListBuf);
            if (err == OK) {
                BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_OPCODELIST3, listSize,
                        opcodeListBuf, TIFF_IFD_0), env, TAG_OPCODELIST3, writer);
            } else {
                ALOGE("%s: Could not build list of opcodes for distortion correction.",
                        __FUNCTION__);
                jniThrowRuntimeException(env, "failed to construct opcode list for distortion"
                        " correction");
                return nullptr;
            }
        }
    }

    {
        // Set up orientation tags.
        // Note: There's only one orientation field for the whole file, in IFD0
        // The main image and any thumbnails therefore have the same orientation.
        uint16_t orientation = nativeContext->getOrientation();
        BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_ORIENTATION, 1, &orientation, TIFF_IFD_0),
                env, TAG_ORIENTATION, writer);

    }

    if (nativeContext->hasDescription()){
        // Set Description
        String8 description = nativeContext->getDescription();
        size_t len = description.bytes() + 1;
        if (writer->addEntry(TAG_IMAGEDESCRIPTION, len,
                             reinterpret_cast<const uint8_t*>(description.c_str()),
                             TIFF_IFD_0) != OK) {
            jniThrowExceptionFmt(env, "java/lang/IllegalArgumentException",
                    "Invalid metadata for tag %x", TAG_IMAGEDESCRIPTION);
        }
    }

    if (nativeContext->hasGpsData()) {
        // Set GPS tags
        GpsData gpsData = nativeContext->getGpsData();
        if (!writer->hasIfd(TIFF_IFD_GPSINFO)) {
            if (writer->addSubIfd(TIFF_IFD_0, TIFF_IFD_GPSINFO, TiffWriter::GPSINFO) != OK) {
                ALOGE("%s: Failed to add GpsInfo IFD %u to IFD %u", __FUNCTION__, TIFF_IFD_GPSINFO,
                        TIFF_IFD_0);
                jniThrowException(env, "java/lang/IllegalStateException", "Failed to add GPSINFO");
                return nullptr;
            }
        }

        {
            uint8_t version[] = {2, 3, 0, 0};
            BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_GPSVERSIONID, 4, version,
                    TIFF_IFD_GPSINFO), env, TAG_GPSVERSIONID, writer);
        }

        {
            BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_GPSLATITUDEREF,
                    GpsData::GPS_REF_LENGTH, gpsData.mLatitudeRef, TIFF_IFD_GPSINFO), env,
                    TAG_GPSLATITUDEREF, writer);
        }

        {
            BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_GPSLONGITUDEREF,
                    GpsData::GPS_REF_LENGTH, gpsData.mLongitudeRef, TIFF_IFD_GPSINFO), env,
                    TAG_GPSLONGITUDEREF, writer);
        }

        {
            BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_GPSLATITUDE, 3, gpsData.mLatitude,
                    TIFF_IFD_GPSINFO), env, TAG_GPSLATITUDE, writer);
        }

        {
            BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_GPSLONGITUDE, 3, gpsData.mLongitude,
                    TIFF_IFD_GPSINFO), env, TAG_GPSLONGITUDE, writer);
        }

        {
            BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_GPSTIMESTAMP, 3, gpsData.mTimestamp,
                    TIFF_IFD_GPSINFO), env, TAG_GPSTIMESTAMP, writer);
        }

        {
            BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_GPSDATESTAMP,
                    GpsData::GPS_DATE_LENGTH, gpsData.mDate, TIFF_IFD_GPSINFO), env,
                    TAG_GPSDATESTAMP, writer);
        }
    }


    if (nativeContext->hasThumbnail()) {
        if (!writer->hasIfd(TIFF_IFD_SUB1)) {
            if (writer->addSubIfd(TIFF_IFD_0, TIFF_IFD_SUB1) != OK) {
                ALOGE("%s: Failed to add SubIFD %u to IFD %u", __FUNCTION__, TIFF_IFD_SUB1,
                        TIFF_IFD_0);
                jniThrowException(env, "java/lang/IllegalStateException", "Failed to add SubIFD");
                return nullptr;
            }
        }

        // Setup thumbnail tags

        {
            // Set photometric interpretation
            uint16_t interpretation = 2; // RGB
            BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_PHOTOMETRICINTERPRETATION, 1,
                    &interpretation, TIFF_IFD_SUB1), env, TAG_PHOTOMETRICINTERPRETATION, writer);
        }

        {
            // Set planar configuration
            uint16_t config = 1; // Chunky
            BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_PLANARCONFIGURATION, 1, &config,
                    TIFF_IFD_SUB1), env, TAG_PLANARCONFIGURATION, writer);
        }

        {
            // Set samples per pixel
            uint16_t samples = SAMPLES_PER_RGB_PIXEL;
            BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_SAMPLESPERPIXEL, 1, &samples,
                    TIFF_IFD_SUB1), env, TAG_SAMPLESPERPIXEL, writer);
        }

        {
            // Set bits per sample
            uint16_t bits[SAMPLES_PER_RGB_PIXEL];
            for (int i = 0; i < SAMPLES_PER_RGB_PIXEL; i++) bits[i] = BITS_PER_RGB_SAMPLE;
            BAIL_IF_INVALID_RET_NULL_SP(
                    writer->addEntry(TAG_BITSPERSAMPLE, SAMPLES_PER_RGB_PIXEL, bits, TIFF_IFD_SUB1),
                    env, TAG_BITSPERSAMPLE, writer);
        }

        {
            // Set subfiletype
            uint32_t subfileType = 1; // Thumbnail image
            BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_NEWSUBFILETYPE, 1, &subfileType,
                    TIFF_IFD_SUB1), env, TAG_NEWSUBFILETYPE, writer);
        }

        {
            // Set compression
            uint16_t compression = 1; // None
            BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_COMPRESSION, 1, &compression,
                    TIFF_IFD_SUB1), env, TAG_COMPRESSION, writer);
        }

        {
            // Set dimensions
            uint32_t uWidth = nativeContext->getThumbnailWidth();
            uint32_t uHeight = nativeContext->getThumbnailHeight();
            BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_IMAGEWIDTH, 1, &uWidth, TIFF_IFD_SUB1),
                    env, TAG_IMAGEWIDTH, writer);
            BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_IMAGELENGTH, 1, &uHeight,
                    TIFF_IFD_SUB1), env, TAG_IMAGELENGTH, writer);
        }

        {
            // x resolution
            uint32_t xres[] = { 72, 1 }; // default 72 ppi
            BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_XRESOLUTION, 1, xres, TIFF_IFD_SUB1),
                    env, TAG_XRESOLUTION, writer);

            // y resolution
            uint32_t yres[] = { 72, 1 }; // default 72 ppi
            BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_YRESOLUTION, 1, yres, TIFF_IFD_SUB1),
                    env, TAG_YRESOLUTION, writer);

            uint16_t unit = 2; // inches
            BAIL_IF_INVALID_RET_NULL_SP(writer->addEntry(TAG_RESOLUTIONUNIT, 1, &unit,
                    TIFF_IFD_SUB1), env, TAG_RESOLUTIONUNIT, writer);
        }
    }

    if (writer->addStrip(TIFF_IFD_0) != OK) {
        ALOGE("%s: Could not setup main image strip tags.", __FUNCTION__);
        jniThrowException(env, "java/lang/IllegalStateException",
                "Failed to setup main image strip tags.");
        return nullptr;
    }

    if (writer->hasIfd(TIFF_IFD_SUB1)) {
        if (writer->addStrip(TIFF_IFD_SUB1) != OK) {
            ALOGE("%s: Could not thumbnail image strip tags.", __FUNCTION__);
            jniThrowException(env, "java/lang/IllegalStateException",
                    "Failed to setup thumbnail image strip tags.");
            return nullptr;
        }
    }
    return writer;
}

static void DngCreator_destroy(JNIEnv* env, jobject thiz) {
    ALOGV("%s:", __FUNCTION__);
    DngCreator_setNativeContext(env, thiz, nullptr);
}

static void DngCreator_nativeSetOrientation(JNIEnv* env, jobject thiz, jint orient) {
    ALOGV("%s:", __FUNCTION__);

    NativeContext* context = DngCreator_getNativeContext(env, thiz);
    if (context == nullptr) {
        ALOGE("%s: Failed to initialize DngCreator", __FUNCTION__);
        jniThrowException(env, "java/lang/AssertionError",
                "setOrientation called with uninitialized DngCreator");
        return;
    }

    uint16_t orientation = static_cast<uint16_t>(orient);
    context->setOrientation(orientation);
}

static void DngCreator_nativeSetDescription(JNIEnv* env, jobject thiz, jstring description) {
    ALOGV("%s:", __FUNCTION__);

    NativeContext* context = DngCreator_getNativeContext(env, thiz);
    if (context == nullptr) {
        ALOGE("%s: Failed to initialize DngCreator", __FUNCTION__);
        jniThrowException(env, "java/lang/AssertionError",
                "setDescription called with uninitialized DngCreator");
        return;
    }

    const char* desc = env->GetStringUTFChars(description, nullptr);
    context->setDescription(String8(desc));
    env->ReleaseStringUTFChars(description, desc);
}

static void DngCreator_nativeSetGpsTags(JNIEnv* env, jobject thiz, jintArray latTag,
        jstring latRef, jintArray longTag, jstring longRef, jstring dateTag, jintArray timeTag) {
    ALOGV("%s:", __FUNCTION__);

    NativeContext* context = DngCreator_getNativeContext(env, thiz);
    if (context == nullptr) {
        ALOGE("%s: Failed to initialize DngCreator", __FUNCTION__);
        jniThrowException(env, "java/lang/AssertionError",
                "setGpsTags called with uninitialized DngCreator");
        return;
    }

    GpsData data;

    jsize latLen = env->GetArrayLength(latTag);
    jsize longLen = env->GetArrayLength(longTag);
    jsize timeLen = env->GetArrayLength(timeTag);
    if (latLen != GpsData::GPS_VALUE_LENGTH) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                "invalid latitude tag length");
        return;
    } else if (longLen != GpsData::GPS_VALUE_LENGTH) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                "invalid longitude tag length");
        return;
    } else if (timeLen != GpsData::GPS_VALUE_LENGTH) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                "invalid time tag length");
        return;
    }

    env->GetIntArrayRegion(latTag, 0, static_cast<jsize>(GpsData::GPS_VALUE_LENGTH),
            reinterpret_cast<jint*>(&data.mLatitude));
    env->GetIntArrayRegion(longTag, 0, static_cast<jsize>(GpsData::GPS_VALUE_LENGTH),
            reinterpret_cast<jint*>(&data.mLongitude));
    env->GetIntArrayRegion(timeTag, 0, static_cast<jsize>(GpsData::GPS_VALUE_LENGTH),
            reinterpret_cast<jint*>(&data.mTimestamp));


    env->GetStringUTFRegion(latRef, 0, 1, reinterpret_cast<char*>(&data.mLatitudeRef));
    data.mLatitudeRef[GpsData::GPS_REF_LENGTH - 1] = '\0';
    env->GetStringUTFRegion(longRef, 0, 1, reinterpret_cast<char*>(&data.mLongitudeRef));
    data.mLongitudeRef[GpsData::GPS_REF_LENGTH - 1] = '\0';
    env->GetStringUTFRegion(dateTag, 0, GpsData::GPS_DATE_LENGTH - 1,
            reinterpret_cast<char*>(&data.mDate));
    data.mDate[GpsData::GPS_DATE_LENGTH - 1] = '\0';

    context->setGpsData(data);
}

static void DngCreator_nativeSetThumbnail(JNIEnv* env, jobject thiz, jobject buffer, jint width,
        jint height) {
    ALOGV("%s:", __FUNCTION__);

    NativeContext* context = DngCreator_getNativeContext(env, thiz);
    if (context == nullptr) {
        ALOGE("%s: Failed to initialize DngCreator", __FUNCTION__);
        jniThrowException(env, "java/lang/AssertionError",
                "setThumbnail called with uninitialized DngCreator");
        return;
    }

    size_t fullSize = width * height * BYTES_PER_RGB_PIXEL;
    jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (static_cast<uint64_t>(capacity) != static_cast<uint64_t>(fullSize)) {
        jniThrowExceptionFmt(env, "java/lang/AssertionError",
                "Invalid size %d for thumbnail, expected size was %d",
                capacity, fullSize);
        return;
    }

    uint8_t* pixelBytes = reinterpret_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (pixelBytes == nullptr) {
        ALOGE("%s: Could not get native ByteBuffer", __FUNCTION__);
        jniThrowException(env, "java/lang/IllegalArgumentException", "Invalid ByteBuffer");
        return;
    }

    if (!context->setThumbnail(pixelBytes, width, height)) {
        jniThrowException(env, "java/lang/IllegalStateException",
                "Failed to set thumbnail.");
        return;
    }
}

// TODO: Refactor out common preamble for the two nativeWrite methods.
static void DngCreator_nativeWriteImage(JNIEnv* env, jobject thiz, jobject outStream, jint width,
        jint height, jobject inBuffer, jint rowStride, jint pixStride, jlong offset,
        jboolean isDirect) {
    ALOGV("%s:", __FUNCTION__);
    ALOGV("%s: nativeWriteImage called with: width=%d, height=%d, "
          "rowStride=%d, pixStride=%d, offset=%" PRId64, __FUNCTION__, width,
          height, rowStride, pixStride, offset);
    uint32_t rStride = static_cast<uint32_t>(rowStride);
    uint32_t pStride = static_cast<uint32_t>(pixStride);
    uint32_t uWidth = static_cast<uint32_t>(width);
    uint32_t uHeight = static_cast<uint32_t>(height);
    uint64_t uOffset = static_cast<uint64_t>(offset);

    sp<JniOutputStream> out = new JniOutputStream(env, outStream);
    if(env->ExceptionCheck()) {
        ALOGE("%s: Could not allocate buffers for output stream", __FUNCTION__);
        return;
    }

    NativeContext* context = DngCreator_getNativeContext(env, thiz);
    if (context == nullptr) {
        ALOGE("%s: Failed to initialize DngCreator", __FUNCTION__);
        jniThrowException(env, "java/lang/AssertionError",
                "Write called with uninitialized DngCreator");
        return;
    }
    sp<TiffWriter> writer = DngCreator_setup(env, thiz, uWidth, uHeight);

    if (writer.get() == nullptr) {
        return;
    }

    // Validate DNG size
    if (!validateDngHeader(env, writer, *(context->getCharacteristics()), width, height)) {
        return;
    }

    sp<JniInputByteBuffer> inBuf;
    Vector<StripSource*> sources;
    sp<DirectStripSource> thumbnailSource;
    uint32_t targetIfd = TIFF_IFD_0;
    bool hasThumbnail = writer->hasIfd(TIFF_IFD_SUB1);
    if (hasThumbnail) {
        ALOGV("%s: Adding thumbnail strip sources.", __FUNCTION__);
        uint32_t bytesPerPixel = SAMPLES_PER_RGB_PIXEL * BYTES_PER_RGB_SAMPLE;
        uint32_t thumbWidth = context->getThumbnailWidth();
        thumbnailSource = new DirectStripSource(env, context->getThumbnail(), TIFF_IFD_SUB1,
                thumbWidth, context->getThumbnailHeight(), bytesPerPixel,
                bytesPerPixel * thumbWidth, /*offset*/0, BYTES_PER_RGB_SAMPLE,
                SAMPLES_PER_RGB_PIXEL);
    }

    if (isDirect) {
        size_t fullSize = rStride * uHeight;
        jlong capacity = env->GetDirectBufferCapacity(inBuffer);
        if (capacity < 0 || fullSize + uOffset > static_cast<uint64_t>(capacity)) {
            jniThrowExceptionFmt(env, "java/lang/IllegalStateException",
                    "Invalid size %d for Image, size given in metadata is %d at current stride",
                    capacity, fullSize);
            return;
        }

        uint8_t* pixelBytes = reinterpret_cast<uint8_t*>(env->GetDirectBufferAddress(inBuffer));
        if (pixelBytes == nullptr) {
            ALOGE("%s: Could not get native ByteBuffer", __FUNCTION__);
            jniThrowException(env, "java/lang/IllegalArgumentException", "Invalid ByteBuffer");
            return;
        }

        ALOGV("%s: Using direct-type strip source.", __FUNCTION__);
        DirectStripSource stripSource(env, pixelBytes, targetIfd, uWidth, uHeight, pStride,
                rStride, uOffset, BYTES_PER_SAMPLE, SAMPLES_PER_RAW_PIXEL);
        sources.add(&stripSource);
        if (thumbnailSource.get() != nullptr) {
            sources.add(thumbnailSource.get());
        }

        status_t ret = OK;
        if ((ret = writer->write(out.get(), sources.editArray(), sources.size())) != OK) {
            ALOGE("%s: write failed with error %d.", __FUNCTION__, ret);
            if (!env->ExceptionCheck()) {
                jniThrowExceptionFmt(env, "java/io/IOException",
                        "Encountered error %d while writing file.", ret);
            }
            return;
        }
    } else {
        inBuf = new JniInputByteBuffer(env, inBuffer);

        ALOGV("%s: Using input-type strip source.", __FUNCTION__);
        InputStripSource stripSource(env, *inBuf, targetIfd, uWidth, uHeight, pStride,
                 rStride, uOffset, BYTES_PER_SAMPLE, SAMPLES_PER_RAW_PIXEL);
        sources.add(&stripSource);
        if (thumbnailSource.get() != nullptr) {
            sources.add(thumbnailSource.get());
        }

        status_t ret = OK;
        if ((ret = writer->write(out.get(), sources.editArray(), sources.size())) != OK) {
            ALOGE("%s: write failed with error %d.", __FUNCTION__, ret);
            if (!env->ExceptionCheck()) {
                jniThrowExceptionFmt(env, "java/io/IOException",
                        "Encountered error %d while writing file.", ret);
            }
            return;
        }
    }

}

static void DngCreator_nativeWriteInputStream(JNIEnv* env, jobject thiz, jobject outStream,
        jobject inStream, jint width, jint height, jlong offset) {
    ALOGV("%s:", __FUNCTION__);

    uint32_t rowStride = width * BYTES_PER_SAMPLE;
    uint32_t pixStride = BYTES_PER_SAMPLE;
    uint32_t uWidth = static_cast<uint32_t>(width);
    uint32_t uHeight = static_cast<uint32_t>(height);
    uint64_t uOffset = static_cast<uint32_t>(offset);

    ALOGV("%s: nativeWriteInputStream called with: width=%d, height=%d, "
          "rowStride=%d, pixStride=%d, offset=%" PRId64, __FUNCTION__, width,
          height, rowStride, pixStride, offset);

    sp<JniOutputStream> out = new JniOutputStream(env, outStream);
    if (env->ExceptionCheck()) {
        ALOGE("%s: Could not allocate buffers for output stream", __FUNCTION__);
        return;
    }

    NativeContext* context = DngCreator_getNativeContext(env, thiz);
    if (context == nullptr) {
        ALOGE("%s: Failed to initialize DngCreator", __FUNCTION__);
        jniThrowException(env, "java/lang/AssertionError",
                "Write called with uninitialized DngCreator");
        return;
    }
    sp<TiffWriter> writer = DngCreator_setup(env, thiz, uWidth, uHeight);

    if (writer.get() == nullptr) {
        return;
    }

    // Validate DNG size
    if (!validateDngHeader(env, writer, *(context->getCharacteristics()), width, height)) {
        return;
    }

    sp<DirectStripSource> thumbnailSource;
    uint32_t targetIfd = TIFF_IFD_0;
    Vector<StripSource*> sources;


    sp<JniInputStream> in = new JniInputStream(env, inStream);

    ALOGV("%s: Using input-type strip source.", __FUNCTION__);
    InputStripSource stripSource(env, *in, targetIfd, uWidth, uHeight, pixStride,
             rowStride, uOffset, BYTES_PER_SAMPLE, SAMPLES_PER_RAW_PIXEL);
    sources.add(&stripSource);

    bool hasThumbnail = writer->hasIfd(TIFF_IFD_SUB1);
    if (hasThumbnail) {
        ALOGV("%s: Adding thumbnail strip sources.", __FUNCTION__);
        uint32_t bytesPerPixel = SAMPLES_PER_RGB_PIXEL * BYTES_PER_RGB_SAMPLE;
        uint32_t width = context->getThumbnailWidth();
        thumbnailSource = new DirectStripSource(env, context->getThumbnail(), TIFF_IFD_SUB1,
                width, context->getThumbnailHeight(), bytesPerPixel,
                bytesPerPixel * width, /*offset*/0, BYTES_PER_RGB_SAMPLE,
                SAMPLES_PER_RGB_PIXEL);
        sources.add(thumbnailSource.get());
    }

    status_t ret = OK;
    if ((ret = writer->write(out.get(), sources.editArray(), sources.size())) != OK) {
        ALOGE("%s: write failed with error %d.", __FUNCTION__, ret);
        if (!env->ExceptionCheck()) {
            jniThrowExceptionFmt(env, "java/io/IOException",
                    "Encountered error %d while writing file.", ret);
        }
        return;
    }
}

} /*extern "C" */

static const JNINativeMethod gDngCreatorMethods[] = {
    {"nativeClassInit",        "()V", (void*) DngCreator_nativeClassInit},
    {"nativeInit", "(Landroid/hardware/camera2/impl/CameraMetadataNative;"
            "Landroid/hardware/camera2/impl/CameraMetadataNative;Ljava/lang/String;)V",
            (void*) DngCreator_init},
    {"nativeDestroy",           "()V",      (void*) DngCreator_destroy},
    {"nativeSetOrientation",    "(I)V",     (void*) DngCreator_nativeSetOrientation},
    {"nativeSetDescription",    "(Ljava/lang/String;)V", (void*) DngCreator_nativeSetDescription},
    {"nativeSetGpsTags",    "([ILjava/lang/String;[ILjava/lang/String;Ljava/lang/String;[I)V",
            (void*) DngCreator_nativeSetGpsTags},
    {"nativeSetThumbnail","(Ljava/nio/ByteBuffer;II)V", (void*) DngCreator_nativeSetThumbnail},
    {"nativeWriteImage",        "(Ljava/io/OutputStream;IILjava/nio/ByteBuffer;IIJZ)V",
            (void*) DngCreator_nativeWriteImage},
    {"nativeWriteInputStream",    "(Ljava/io/OutputStream;Ljava/io/InputStream;IIJ)V",
            (void*) DngCreator_nativeWriteInputStream},
};

int register_android_hardware_camera2_DngCreator(JNIEnv *env) {
    return RegisterMethodsOrDie(env,
            "android/hardware/camera2/DngCreator", gDngCreatorMethods, NELEM(gDngCreatorMethods));
}
