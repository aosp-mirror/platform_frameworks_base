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

#include <system/camera_metadata.h>
#include <camera/CameraMetadata.h>
#include <img_utils/DngUtils.h>
#include <img_utils/TagDefinitions.h>
#include <img_utils/TiffIfd.h>
#include <img_utils/TiffWriter.h>
#include <img_utils/Output.h>

#include <utils/Log.h>
#include <utils/Errors.h>
#include <utils/StrongPointer.h>
#include <utils/RefBase.h>
#include <cutils/properties.h>

#include "android_runtime/AndroidRuntime.h"
#include "android_runtime/android_hardware_camera2_CameraMetadata.h"

#include <jni.h>
#include <JNIHelp.h>

using namespace android;
using namespace img_utils;

#define BAIL_IF_INVALID(expr, jnienv, tagId) \
    if ((expr) != OK) { \
        jniThrowExceptionFmt(jnienv, "java/lang/IllegalArgumentException", \
                "Invalid metadata for tag %x", tagId); \
        return; \
    }

#define BAIL_IF_EMPTY(entry, jnienv, tagId) \
    if (entry.count == 0) { \
        jniThrowExceptionFmt(jnienv, "java/lang/IllegalArgumentException", \
                "Missing metadata fields for tag %x", tagId); \
        return; \
    }

#define ANDROID_MEDIA_DNGCREATOR_CTX_JNI_ID     "mNativeContext"

static struct {
    jfieldID mNativeContext;
} gDngCreatorClassInfo;

static struct {
    jmethodID mWriteMethod;
} gOutputStreamClassInfo;

enum {
    BITS_PER_SAMPLE = 16,
    BYTES_PER_SAMPLE = 2,
    TIFF_IFD_0 = 0
};

// ----------------------------------------------------------------------------

// This class is not intended to be used across JNI calls.
class JniOutputStream : public Output, public LightRefBase<JniOutputStream> {
public:
    JniOutputStream(JNIEnv* env, jobject outStream);

    virtual ~JniOutputStream();

    status_t open();
    status_t write(const uint8_t* buf, size_t offset, size_t count);
    status_t close();
private:
    enum {
        BYTE_ARRAY_LENGTH = 1024
    };
    jobject mOutputStream;
    JNIEnv* mEnv;
    jbyteArray mByteArray;
};

JniOutputStream::JniOutputStream(JNIEnv* env, jobject outStream) : mOutputStream(outStream),
        mEnv(env) {
    mByteArray = env->NewByteArray(BYTE_ARRAY_LENGTH);
    if (mByteArray == NULL) {
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

// ----------------------------------------------------------------------------

extern "C" {

static TiffWriter* DngCreator_getCreator(JNIEnv* env, jobject thiz) {
    ALOGV("%s:", __FUNCTION__);
    return reinterpret_cast<TiffWriter*>(env->GetLongField(thiz,
            gDngCreatorClassInfo.mNativeContext));
}

static void DngCreator_setCreator(JNIEnv* env, jobject thiz, sp<TiffWriter> writer) {
    ALOGV("%s:", __FUNCTION__);
    TiffWriter* current = DngCreator_getCreator(env, thiz);
    if (writer != NULL) {
        writer->incStrong((void*) DngCreator_setCreator);
    }
    if (current) {
        current->decStrong((void*) DngCreator_setCreator);
    }
    env->SetLongField(thiz, gDngCreatorClassInfo.mNativeContext,
            reinterpret_cast<jlong>(writer.get()));
}

static void DngCreator_nativeClassInit(JNIEnv* env, jclass clazz) {
    ALOGV("%s:", __FUNCTION__);

    gDngCreatorClassInfo.mNativeContext = env->GetFieldID(clazz,
            ANDROID_MEDIA_DNGCREATOR_CTX_JNI_ID, "J");
    LOG_ALWAYS_FATAL_IF(gDngCreatorClassInfo.mNativeContext == NULL,
            "can't find android/media/DngCreator.%s", ANDROID_MEDIA_DNGCREATOR_CTX_JNI_ID);

    jclass outputStreamClazz = env->FindClass("java/io/OutputStream");
    LOG_ALWAYS_FATAL_IF(outputStreamClazz == NULL, "Can't find java/io/OutputStream class");
    gOutputStreamClassInfo.mWriteMethod = env->GetMethodID(outputStreamClazz, "write", "([BII)V");
    LOG_ALWAYS_FATAL_IF(gOutputStreamClassInfo.mWriteMethod == NULL, "Can't find write method");
}

static void DngCreator_init(JNIEnv* env, jobject thiz, jobject characteristicsPtr,
        jobject resultsPtr) {
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

    sp<TiffWriter> writer = new TiffWriter();

    writer->addIfd(TIFF_IFD_0);

    status_t err = OK;

    const uint32_t samplesPerPixel = 1;
    const uint32_t bitsPerSample = BITS_PER_SAMPLE;
    const uint32_t bitsPerByte = BITS_PER_SAMPLE / BYTES_PER_SAMPLE;
    uint32_t imageWidth = 0;
    uint32_t imageHeight = 0;

    OpcodeListBuilder::CfaLayout opcodeCfaLayout = OpcodeListBuilder::CFA_RGGB;

    // TODO: Greensplit.
    // TODO: UniqueCameraModel
    // TODO: Add remaining non-essential tags
    {
        // Set orientation
        uint16_t orientation = 1; // Normal
        BAIL_IF_INVALID(writer->addEntry(TAG_ORIENTATION, 1, &orientation, TIFF_IFD_0), env,
                TAG_ORIENTATION);
    }

    {
        // Set subfiletype
        uint32_t subfileType = 0; // Main image
        BAIL_IF_INVALID(writer->addEntry(TAG_NEWSUBFILETYPE, 1, &subfileType, TIFF_IFD_0), env,
                TAG_NEWSUBFILETYPE);
    }

    {
        // Set bits per sample
        uint16_t bits = static_cast<uint16_t>(bitsPerSample);
        BAIL_IF_INVALID(writer->addEntry(TAG_BITSPERSAMPLE, 1, &bits, TIFF_IFD_0), env,
                TAG_BITSPERSAMPLE);
    }

    {
        // Set compression
        uint16_t compression = 1; // None
        BAIL_IF_INVALID(writer->addEntry(TAG_COMPRESSION, 1, &compression, TIFF_IFD_0), env,
                TAG_COMPRESSION);
    }

    {
        // Set dimensions
        camera_metadata_entry entry =
                characteristics.find(ANDROID_SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        BAIL_IF_EMPTY(entry, env, TAG_IMAGEWIDTH);
        uint32_t width = static_cast<uint32_t>(entry.data.i32[2]);
        uint32_t height = static_cast<uint32_t>(entry.data.i32[3]);
        BAIL_IF_INVALID(writer->addEntry(TAG_IMAGEWIDTH, 1, &width, TIFF_IFD_0), env,
                TAG_IMAGEWIDTH);
        BAIL_IF_INVALID(writer->addEntry(TAG_IMAGELENGTH, 1, &height, TIFF_IFD_0), env,
                TAG_IMAGELENGTH);
        imageWidth = width;
        imageHeight = height;
    }

    {
        // Set photometric interpretation
        uint16_t interpretation = 32803;
        BAIL_IF_INVALID(writer->addEntry(TAG_PHOTOMETRICINTERPRETATION, 1, &interpretation,
                TIFF_IFD_0), env, TAG_PHOTOMETRICINTERPRETATION);
    }

    {
        // Set blacklevel tags
        camera_metadata_entry entry =
                characteristics.find(ANDROID_SENSOR_BLACK_LEVEL_PATTERN);
        BAIL_IF_EMPTY(entry, env, TAG_BLACKLEVEL);
        const uint32_t* blackLevel = reinterpret_cast<const uint32_t*>(entry.data.i32);
        BAIL_IF_INVALID(writer->addEntry(TAG_BLACKLEVEL, entry.count, blackLevel, TIFF_IFD_0), env,
                TAG_BLACKLEVEL);

        uint16_t repeatDim[2] = {2, 2};
        BAIL_IF_INVALID(writer->addEntry(TAG_BLACKLEVELREPEATDIM, 2, repeatDim, TIFF_IFD_0), env,
                TAG_BLACKLEVELREPEATDIM);
    }

    {
        // Set samples per pixel
        uint16_t samples = static_cast<uint16_t>(samplesPerPixel);
        BAIL_IF_INVALID(writer->addEntry(TAG_SAMPLESPERPIXEL, 1, &samples, TIFF_IFD_0),
                env, TAG_SAMPLESPERPIXEL);
    }

    {
        // Set planar configuration
        uint16_t config = 1; // Chunky
        BAIL_IF_INVALID(writer->addEntry(TAG_PLANARCONFIGURATION, 1, &config, TIFF_IFD_0),
                env, TAG_PLANARCONFIGURATION);
    }

    {
        // Set CFA pattern dimensions
        uint16_t repeatDim[2] = {2, 2};
        BAIL_IF_INVALID(writer->addEntry(TAG_CFAREPEATPATTERNDIM, 2, repeatDim, TIFF_IFD_0),
                env, TAG_CFAREPEATPATTERNDIM);
    }

    {
        // Set CFA pattern
        camera_metadata_entry entry =
                        characteristics.find(ANDROID_SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
        BAIL_IF_EMPTY(entry, env, TAG_CFAPATTERN);
        camera_metadata_enum_android_sensor_info_color_filter_arrangement_t cfa =
                static_cast<camera_metadata_enum_android_sensor_info_color_filter_arrangement_t>(
                entry.data.u8[0]);
        switch(cfa) {
            case ANDROID_SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB: {
                uint8_t cfa[4] = {0, 1, 1, 2};
                BAIL_IF_INVALID(writer->addEntry(TAG_CFAPATTERN, 4, cfa, TIFF_IFD_0),
                                                env, TAG_CFAPATTERN);
                opcodeCfaLayout = OpcodeListBuilder::CFA_RGGB;
                break;
            }
            case ANDROID_SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG: {
                uint8_t cfa[4] = {1, 0, 2, 1};
                BAIL_IF_INVALID(writer->addEntry(TAG_CFAPATTERN, 4, cfa, TIFF_IFD_0),
                                                env, TAG_CFAPATTERN);
                opcodeCfaLayout = OpcodeListBuilder::CFA_GRBG;
                break;
            }
            case ANDROID_SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG: {
                uint8_t cfa[4] = {1, 2, 0, 1};
                BAIL_IF_INVALID(writer->addEntry(TAG_CFAPATTERN, 4, cfa, TIFF_IFD_0),
                                                env, TAG_CFAPATTERN);
                opcodeCfaLayout = OpcodeListBuilder::CFA_GBRG;
                break;
            }
            case ANDROID_SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR: {
                uint8_t cfa[4] = {2, 1, 1, 0};
                BAIL_IF_INVALID(writer->addEntry(TAG_CFAPATTERN, 4, cfa, TIFF_IFD_0),
                                env, TAG_CFAPATTERN);
                opcodeCfaLayout = OpcodeListBuilder::CFA_BGGR;
                break;
            }
            default: {
                jniThrowExceptionFmt(env, "java/lang/IllegalStateException",
                            "Invalid metadata for tag %d", TAG_CFAPATTERN);
                return;
            }
        }
    }

    {
        // Set CFA plane color
        uint8_t cfaPlaneColor[3] = {0, 1, 2};
        BAIL_IF_INVALID(writer->addEntry(TAG_CFAPLANECOLOR, 3, cfaPlaneColor, TIFF_IFD_0),
                env, TAG_CFAPLANECOLOR);
    }

    {
        // Set CFA layout
        uint16_t cfaLayout = 1;
        BAIL_IF_INVALID(writer->addEntry(TAG_CFALAYOUT, 1, &cfaLayout, TIFF_IFD_0),
                env, TAG_CFALAYOUT);
    }

    {
        // Set DNG version information
        uint8_t version[4] = {1, 4, 0, 0};
        BAIL_IF_INVALID(writer->addEntry(TAG_DNGVERSION, 4, version, TIFF_IFD_0),
                env, TAG_DNGVERSION);

        uint8_t backwardVersion[4] = {1, 1, 0, 0};
        BAIL_IF_INVALID(writer->addEntry(TAG_DNGBACKWARDVERSION, 4, backwardVersion, TIFF_IFD_0),
                env, TAG_DNGBACKWARDVERSION);
    }

    {
        // Set whitelevel
        camera_metadata_entry entry =
                characteristics.find(ANDROID_SENSOR_INFO_WHITE_LEVEL);
        BAIL_IF_EMPTY(entry, env, TAG_WHITELEVEL);
        uint32_t whiteLevel = static_cast<uint32_t>(entry.data.i32[0]);
        BAIL_IF_INVALID(writer->addEntry(TAG_WHITELEVEL, 1, &whiteLevel, TIFF_IFD_0), env,
                TAG_WHITELEVEL);
    }

    {
        // Set default scale
        uint32_t defaultScale[4] = {1, 1, 1, 1};
        BAIL_IF_INVALID(writer->addEntry(TAG_DEFAULTSCALE, 2, defaultScale, TIFF_IFD_0),
                env, TAG_DEFAULTSCALE);
    }

    bool singleIlluminant = false;
    {
        // Set calibration illuminants
        camera_metadata_entry entry1 =
            characteristics.find(ANDROID_SENSOR_REFERENCE_ILLUMINANT1);
        BAIL_IF_EMPTY(entry1, env, TAG_CALIBRATIONILLUMINANT1);
        camera_metadata_entry entry2 =
            characteristics.find(ANDROID_SENSOR_REFERENCE_ILLUMINANT2);
        if (entry2.count == 0) {
            singleIlluminant = true;
        }
        uint16_t ref1 = entry1.data.u8[0];

        BAIL_IF_INVALID(writer->addEntry(TAG_CALIBRATIONILLUMINANT1, 1, &ref1,
                TIFF_IFD_0), env, TAG_CALIBRATIONILLUMINANT1);

        if (!singleIlluminant) {
            uint16_t ref2 = entry2.data.u8[0];
            BAIL_IF_INVALID(writer->addEntry(TAG_CALIBRATIONILLUMINANT2, 1, &ref2,
                    TIFF_IFD_0), env, TAG_CALIBRATIONILLUMINANT2);
        }
    }

    {
        // Set color transforms
        camera_metadata_entry entry1 =
            characteristics.find(ANDROID_SENSOR_COLOR_TRANSFORM1);
        BAIL_IF_EMPTY(entry1, env, TAG_COLORMATRIX1);

        int32_t colorTransform1[entry1.count * 2];

        size_t ctr = 0;
        for(size_t i = 0; i < entry1.count; ++i) {
            colorTransform1[ctr++] = entry1.data.r[i].numerator;
            colorTransform1[ctr++] = entry1.data.r[i].denominator;
        }

        BAIL_IF_INVALID(writer->addEntry(TAG_COLORMATRIX1, entry1.count, colorTransform1, TIFF_IFD_0),
                env, TAG_COLORMATRIX1);

        if (!singleIlluminant) {
            camera_metadata_entry entry2 = characteristics.find(ANDROID_SENSOR_COLOR_TRANSFORM2);
            BAIL_IF_EMPTY(entry2, env, TAG_COLORMATRIX2);
            int32_t colorTransform2[entry2.count * 2];

            ctr = 0;
            for(size_t i = 0; i < entry2.count; ++i) {
                colorTransform2[ctr++] = entry2.data.r[i].numerator;
                colorTransform2[ctr++] = entry2.data.r[i].denominator;
            }

            BAIL_IF_INVALID(writer->addEntry(TAG_COLORMATRIX2, entry2.count, colorTransform2, TIFF_IFD_0),
                    env, TAG_COLORMATRIX2);
        }
    }

    {
        // Set calibration transforms
        camera_metadata_entry entry1 =
            characteristics.find(ANDROID_SENSOR_CALIBRATION_TRANSFORM1);
        BAIL_IF_EMPTY(entry1, env, TAG_CAMERACALIBRATION1);

        int32_t calibrationTransform1[entry1.count * 2];

        size_t ctr = 0;
        for(size_t i = 0; i < entry1.count; ++i) {
            calibrationTransform1[ctr++] = entry1.data.r[i].numerator;
            calibrationTransform1[ctr++] = entry1.data.r[i].denominator;
        }

        BAIL_IF_INVALID(writer->addEntry(TAG_CAMERACALIBRATION1, entry1.count, calibrationTransform1,
                TIFF_IFD_0), env, TAG_CAMERACALIBRATION1);

        if (!singleIlluminant) {
            camera_metadata_entry entry2 =
                characteristics.find(ANDROID_SENSOR_CALIBRATION_TRANSFORM2);
            BAIL_IF_EMPTY(entry2, env, TAG_CAMERACALIBRATION2);
            int32_t calibrationTransform2[entry2.count * 2];

            ctr = 0;
            for(size_t i = 0; i < entry2.count; ++i) {
                calibrationTransform2[ctr++] = entry2.data.r[i].numerator;
                calibrationTransform2[ctr++] = entry2.data.r[i].denominator;
            }

            BAIL_IF_INVALID(writer->addEntry(TAG_CAMERACALIBRATION2, entry2.count, calibrationTransform1,
                    TIFF_IFD_0),  env, TAG_CAMERACALIBRATION2);
        }
    }

    {
        // Set forward transforms
        camera_metadata_entry entry1 =
            characteristics.find(ANDROID_SENSOR_FORWARD_MATRIX1);
        BAIL_IF_EMPTY(entry1, env, TAG_FORWARDMATRIX1);

        int32_t forwardTransform1[entry1.count * 2];

        size_t ctr = 0;
        for(size_t i = 0; i < entry1.count; ++i) {
            forwardTransform1[ctr++] = entry1.data.r[i].numerator;
            forwardTransform1[ctr++] = entry1.data.r[i].denominator;
        }

        BAIL_IF_INVALID(writer->addEntry(TAG_FORWARDMATRIX1, entry1.count, forwardTransform1,
                TIFF_IFD_0), env, TAG_FORWARDMATRIX1);

        if (!singleIlluminant) {
            camera_metadata_entry entry2 =
                characteristics.find(ANDROID_SENSOR_FORWARD_MATRIX2);
            BAIL_IF_EMPTY(entry2, env, TAG_FORWARDMATRIX2);
            int32_t forwardTransform2[entry2.count * 2];

            ctr = 0;
            for(size_t i = 0; i < entry2.count; ++i) {
                forwardTransform2[ctr++] = entry2.data.r[i].numerator;
                forwardTransform2[ctr++] = entry2.data.r[i].denominator;
            }

            BAIL_IF_INVALID(writer->addEntry(TAG_FORWARDMATRIX2, entry2.count, forwardTransform2,
                    TIFF_IFD_0),  env, TAG_FORWARDMATRIX2);
        }
    }

    {
        // Set camera neutral
        camera_metadata_entry entry =
            results.find(ANDROID_SENSOR_NEUTRAL_COLOR_POINT);
        BAIL_IF_EMPTY(entry, env, TAG_ASSHOTNEUTRAL);
        uint32_t cameraNeutral[entry.count * 2];

        size_t ctr = 0;
        for(size_t i = 0; i < entry.count; ++i) {
            cameraNeutral[ctr++] =
                    static_cast<uint32_t>(entry.data.r[i].numerator);
            cameraNeutral[ctr++] =
                    static_cast<uint32_t>(entry.data.r[i].denominator);
        }

        BAIL_IF_INVALID(writer->addEntry(TAG_ASSHOTNEUTRAL, entry.count, cameraNeutral,
                TIFF_IFD_0), env, TAG_ASSHOTNEUTRAL);
    }

    {
        // Setup data strips
        // TODO: Switch to tiled implementation.
        uint32_t offset = 0;
        BAIL_IF_INVALID(writer->addEntry(TAG_STRIPOFFSETS, 1, &offset, TIFF_IFD_0), env,
                TAG_STRIPOFFSETS);

        BAIL_IF_INVALID(writer->addEntry(TAG_ROWSPERSTRIP, 1, &imageHeight, TIFF_IFD_0), env,
                TAG_ROWSPERSTRIP);

        uint32_t byteCount = imageWidth * imageHeight * bitsPerSample * samplesPerPixel /
                bitsPerByte;
        BAIL_IF_INVALID(writer->addEntry(TAG_STRIPBYTECOUNTS, 1, &byteCount, TIFF_IFD_0), env,
                TAG_STRIPBYTECOUNTS);
    }

    {
        // Setup default crop + crop origin tags
        uint32_t margin = 8; // Default margin recommended by Adobe for interpolation.
        uint32_t dimensionLimit = 128; // Smallest image dimension crop margin from.
        if (imageWidth >= dimensionLimit && imageHeight >= dimensionLimit) {
            uint32_t defaultCropOrigin[] = {margin, margin};
            uint32_t defaultCropSize[] = {imageWidth - margin, imageHeight - margin};
            BAIL_IF_INVALID(writer->addEntry(TAG_DEFAULTCROPORIGIN, 2, defaultCropOrigin,
                    TIFF_IFD_0), env, TAG_DEFAULTCROPORIGIN);
            BAIL_IF_INVALID(writer->addEntry(TAG_DEFAULTCROPSIZE, 2, defaultCropSize,
                    TIFF_IFD_0), env, TAG_DEFAULTCROPSIZE);
        }
    }

    {
        // Setup unique camera model tag
        char model[PROPERTY_VALUE_MAX];
        property_get("ro.product.model", model, "");

        char manufacturer[PROPERTY_VALUE_MAX];
        property_get("ro.product.manufacturer", manufacturer, "");

        char brand[PROPERTY_VALUE_MAX];
        property_get("ro.product.brand", brand, "");

        String8 cameraModel(model);
        cameraModel += "-";
        cameraModel += manufacturer;
        cameraModel += "-";
        cameraModel += brand;

        BAIL_IF_INVALID(writer->addEntry(TAG_UNIQUECAMERAMODEL, cameraModel.size() + 1,
                reinterpret_cast<const uint8_t*>(cameraModel.string()), TIFF_IFD_0), env,
                TAG_UNIQUECAMERAMODEL);
    }

    {
        // Setup opcode List 2
        camera_metadata_entry entry1 =
                characteristics.find(ANDROID_LENS_INFO_SHADING_MAP_SIZE);
        BAIL_IF_EMPTY(entry1, env, TAG_OPCODELIST2);
        uint32_t lsmWidth = static_cast<uint32_t>(entry1.data.i32[0]);
        uint32_t lsmHeight = static_cast<uint32_t>(entry1.data.i32[1]);

        camera_metadata_entry entry2 =
                results.find(ANDROID_STATISTICS_LENS_SHADING_MAP);
        BAIL_IF_EMPTY(entry2, env, TAG_OPCODELIST2);
        if (entry2.count == lsmWidth * lsmHeight * 4) {

            OpcodeListBuilder builder;
            status_t err = builder.addGainMapsForMetadata(lsmWidth,
                                                          lsmHeight,
                                                          0,
                                                          0,
                                                          imageHeight,
                                                          imageWidth,
                                                          opcodeCfaLayout,
                                                          entry2.data.f);
            if (err == OK) {
                size_t listSize = builder.getSize();
                uint8_t opcodeListBuf[listSize];
                err = builder.buildOpList(opcodeListBuf);
                if (err == OK) {
                    BAIL_IF_INVALID(writer->addEntry(TAG_OPCODELIST2, listSize, opcodeListBuf,
                            TIFF_IFD_0), env, TAG_OPCODELIST2);
                } else {
                    ALOGE("%s: Could not build Lens shading map opcode.", __FUNCTION__);
                    jniThrowRuntimeException(env, "failed to construct lens shading map opcode.");
                }
            } else {
                ALOGE("%s: Could not add Lens shading map.", __FUNCTION__);
                jniThrowRuntimeException(env, "failed to add lens shading map.");
            }
        } else {
            ALOGW("%s: Lens shading map not present in results, skipping...", __FUNCTION__);
        }
    }

    DngCreator_setCreator(env, thiz, writer);
}

static void DngCreator_destroy(JNIEnv* env, jobject thiz) {
    ALOGV("%s:", __FUNCTION__);
    DngCreator_setCreator(env, thiz, NULL);
}

static void DngCreator_nativeSetOrientation(JNIEnv* env, jobject thiz) {
    ALOGV("%s:", __FUNCTION__);
    jniThrowRuntimeException(env, "nativeSetOrientation is not implemented");
}

static void DngCreator_nativeSetThumbnailBitmap(JNIEnv* env, jobject thiz, jobject bitmap) {
    ALOGV("%s:", __FUNCTION__);
    jniThrowRuntimeException(env, "nativeSetThumbnailBitmap is not implemented");
}

static void DngCreator_nativeSetThumbnailImage(JNIEnv* env, jobject thiz, jint width, jint height,
        jobject yBuffer, jint yRowStride, jint yPixStride, jobject uBuffer, jint uRowStride,
        jint uPixStride, jobject vBuffer, jint vRowStride, jint vPixStride) {
    ALOGV("%s:", __FUNCTION__);
    jniThrowRuntimeException(env, "nativeSetThumbnailImage is not implemented");
}

static void DngCreator_nativeWriteImage(JNIEnv* env, jobject thiz, jobject outStream, jint width,
        jint height, jobject inBuffer, jint rowStride, jint pixStride) {
    ALOGV("%s:", __FUNCTION__);

    sp<JniOutputStream> out = new JniOutputStream(env, outStream);
    if(env->ExceptionCheck()) {
        ALOGE("%s: Could not allocate buffers for output stream", __FUNCTION__);
        return;
    }

    uint8_t* pixelBytes = reinterpret_cast<uint8_t*>(env->GetDirectBufferAddress(inBuffer));
    if (pixelBytes == NULL) {
        ALOGE("%s: Could not get native byte buffer", __FUNCTION__);
        jniThrowException(env, "java/lang/IllegalArgumentException", "Invalid bytebuffer");
        return;
    }

    TiffWriter* writer = DngCreator_getCreator(env, thiz);
    if (writer == NULL) {
        ALOGE("%s: Failed to initialize DngCreator", __FUNCTION__);
        jniThrowException(env, "java/lang/AssertionError",
                "Write called with uninitialized DngCreator");
        return;
    }
    // TODO: handle lens shading map, etc. conversions for other raw buffer sizes.
    uint32_t metadataWidth = *(writer->getEntry(TAG_IMAGEWIDTH, TIFF_IFD_0)->getData<uint32_t>());
    uint32_t metadataHeight = *(writer->getEntry(TAG_IMAGELENGTH, TIFF_IFD_0)->getData<uint32_t>());
    if (metadataWidth != width) {
        jniThrowExceptionFmt(env, "java/lang/IllegalStateException", \
                        "Metadata width %d doesn't match image width %d", metadataWidth, width);
        return;
    }

    if (metadataHeight != height) {
        jniThrowExceptionFmt(env, "java/lang/IllegalStateException", \
                        "Metadata height %d doesn't match image height %d", metadataHeight, height);
        return;
    }

    uint32_t stripOffset = writer->getTotalSize();

    BAIL_IF_INVALID(writer->addEntry(TAG_STRIPOFFSETS, 1, &stripOffset, TIFF_IFD_0), env,
                    TAG_STRIPOFFSETS);

    if (writer->write(out.get()) != OK) {
        if (!env->ExceptionCheck()) {
            jniThrowException(env, "java/io/IOException", "Failed to write metadata");
        }
        return;
    }

    size_t fullSize = rowStride * height;
    jlong capacity = env->GetDirectBufferCapacity(inBuffer);
    if (capacity < 0 || fullSize > capacity) {
        jniThrowExceptionFmt(env, "java/lang/IllegalStateException",
                "Invalid size %d for Image, size given in metadata is %d at current stride",
                capacity, fullSize);
        return;
    }

    if (pixStride == BYTES_PER_SAMPLE && rowStride == width * BYTES_PER_SAMPLE) {
        if (out->write(pixelBytes, 0, fullSize) != OK || env->ExceptionCheck()) {
            if (!env->ExceptionCheck()) {
                jniThrowException(env, "java/io/IOException", "Failed to write pixel data");
            }
            return;
        }
    } else if (pixStride == BYTES_PER_SAMPLE) {
        for (size_t i = 0; i < height; ++i) {
            if (out->write(pixelBytes, i * rowStride, pixStride * width) != OK ||
                        env->ExceptionCheck()) {
                if (!env->ExceptionCheck()) {
                    jniThrowException(env, "java/io/IOException", "Failed to write pixel data");
                }
                return;
            }
        }
    } else {
        for (size_t i = 0; i < height; ++i) {
            for (size_t j = 0; j < width; ++j) {
                if (out->write(pixelBytes, i * rowStride + j * pixStride,
                        BYTES_PER_SAMPLE) != OK || !env->ExceptionCheck()) {
                    if (env->ExceptionCheck()) {
                        jniThrowException(env, "java/io/IOException", "Failed to write pixel data");
                    }
                    return;
                }
            }
        }
    }

}

static void DngCreator_nativeWriteByteBuffer(JNIEnv* env, jobject thiz, jobject outStream,
        jobject rawBuffer, jlong offset) {
    ALOGV("%s:", __FUNCTION__);
    jniThrowRuntimeException(env, "nativeWriteByteBuffer is not implemented.");
}

static void DngCreator_nativeWriteInputStream(JNIEnv* env, jobject thiz, jobject outStream,
        jobject inStream, jlong offset) {
    ALOGV("%s:", __FUNCTION__);
    jniThrowRuntimeException(env, "nativeWriteInputStream is not implemented.");
}

} /*extern "C" */

static JNINativeMethod gDngCreatorMethods[] = {
    {"nativeClassInit",        "()V", (void*) DngCreator_nativeClassInit},
    {"nativeInit", "(Landroid/hardware/camera2/impl/CameraMetadataNative;"
            "Landroid/hardware/camera2/impl/CameraMetadataNative;)V", (void*) DngCreator_init},
    {"nativeDestroy",           "()V",      (void*) DngCreator_destroy},
    {"nativeSetOrientation",    "(I)V",     (void*) DngCreator_nativeSetOrientation},
    {"nativeSetThumbnailBitmap","(Landroid/graphics/Bitmap;)V",
            (void*) DngCreator_nativeSetThumbnailBitmap},
    {"nativeSetThumbnailImage",
            "(IILjava/nio/ByteBuffer;IILjava/nio/ByteBuffer;IILjava/nio/ByteBuffer;II)V",
            (void*) DngCreator_nativeSetThumbnailImage},
    {"nativeWriteImage",        "(Ljava/io/OutputStream;IILjava/nio/ByteBuffer;II)V",
            (void*) DngCreator_nativeWriteImage},
    {"nativeWriteByteBuffer",    "(Ljava/io/OutputStream;Ljava/nio/ByteBuffer;J)V",
            (void*) DngCreator_nativeWriteByteBuffer},
    {"nativeWriteInputStream",    "(Ljava/io/OutputStream;Ljava/io/InputStream;J)V",
            (void*) DngCreator_nativeWriteInputStream},
};

int register_android_media_DngCreator(JNIEnv *env) {
    return AndroidRuntime::registerNativeMethods(env,
                   "android/media/DngCreator", gDngCreatorMethods, NELEM(gDngCreatorMethods));
}
