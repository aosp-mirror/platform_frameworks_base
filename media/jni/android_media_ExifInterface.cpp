/*
 * Copyright 2016 The Android Open Source Project
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
#define LOG_TAG "ExifInterface_JNI"

#include "android_media_Utils.h"

#include "android/graphics/CreateJavaOutputStreamAdaptor.h"
#include "src/piex_types.h"
#include "src/piex.h"

#include <jni.h>
#include <JNIHelp.h>
#include <androidfw/Asset.h>
#include <android_runtime/AndroidRuntime.h>
#include <android/graphics/Utils.h>
#include <nativehelper/ScopedLocalRef.h>

#include <utils/Log.h>
#include <utils/String8.h>
#include <utils/KeyedVector.h>

// ----------------------------------------------------------------------------

using namespace android;

static const char kJpegSignatureChars[] = {(char)0xff, (char)0xd8, (char)0xff};
static const int kJpegSignatureSize = 3;

#define FIND_CLASS(var, className) \
    var = env->FindClass(className); \
    LOG_FATAL_IF(! var, "Unable to find class " className);

#define GET_METHOD_ID(var, clazz, fieldName, fieldDescriptor) \
    var = env->GetMethodID(clazz, fieldName, fieldDescriptor); \
    LOG_FATAL_IF(! var, "Unable to find method " fieldName);

struct HashMapFields {
    jmethodID init;
    jmethodID put;
};

struct fields_t {
    HashMapFields hashMap;
    jclass hashMapClassId;
};

static fields_t gFields;

static jobject KeyedVectorToHashMap(JNIEnv *env, KeyedVector<String8, String8> const &map) {
    jclass clazz = gFields.hashMapClassId;
    jobject hashMap = env->NewObject(clazz, gFields.hashMap.init);
    for (size_t i = 0; i < map.size(); ++i) {
        jstring jkey = env->NewStringUTF(map.keyAt(i).string());
        jstring jvalue = env->NewStringUTF(map.valueAt(i).string());
        env->CallObjectMethod(hashMap, gFields.hashMap.put, jkey, jvalue);
        env->DeleteLocalRef(jkey);
        env->DeleteLocalRef(jvalue);
    }
    return hashMap;
}

extern "C" {

// -------------------------- ExifInterface methods ---------------------------

static void ExifInterface_initRaw(JNIEnv *env) {
    jclass clazz;
    FIND_CLASS(clazz, "java/util/HashMap");
    gFields.hashMapClassId = static_cast<jclass>(env->NewGlobalRef(clazz));

    GET_METHOD_ID(gFields.hashMap.init, clazz, "<init>", "()V");
    GET_METHOD_ID(gFields.hashMap.put, clazz, "put",
                  "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
}

static bool is_asset_stream(const SkStream& stream) {
    return stream.hasLength() && stream.hasPosition();
}

static jobject ExifInterface_getThumbnailFromAsset(
        JNIEnv* env, jclass /* clazz */, jlong jasset, jint jthumbnailOffset,
        jint jthumbnailLength) {
    Asset* asset = reinterpret_cast<Asset*>(jasset);
    std::unique_ptr<AssetStreamAdaptor> stream(new AssetStreamAdaptor(asset));

    std::unique_ptr<jbyte[]> thumbnailData(new jbyte[(int)jthumbnailLength]);
    if (thumbnailData.get() == NULL) {
        ALOGI("No memory to get thumbnail");
        return NULL;
    }

    // Do not know the current offset. So rewind it.
    stream->rewind();

    // Read thumbnail.
    stream->skip((int)jthumbnailOffset);
    stream->read((void*)thumbnailData.get(), (int)jthumbnailLength);

    // Copy to the byte array.
    jbyteArray byteArray = env->NewByteArray(jthumbnailLength);
    env->SetByteArrayRegion(byteArray, 0, jthumbnailLength, thumbnailData.get());
    return byteArray;
}

static jobject getRawAttributes(JNIEnv* env, SkStream* stream, bool returnThumbnail) {
    std::unique_ptr<SkStream> streamDeleter(stream);

    std::unique_ptr<::piex::StreamInterface> piexStream;
    if (is_asset_stream(*stream)) {
        piexStream.reset(new AssetStream(streamDeleter.release()));
    } else {
        piexStream.reset(new BufferedStream(streamDeleter.release()));
    }

    piex::PreviewImageData image_data;

    if (!GetExifFromRawImage(piexStream.get(), String8("[piex stream]"), image_data)) {
        ALOGI("Raw image not detected");
        return NULL;
    }

    KeyedVector<String8, String8> map;

    if (image_data.thumbnail.length > 0
            && image_data.thumbnail.format == ::piex::Image::kJpegCompressed) {
        map.add(String8("HasThumbnail"), String8("true"));
        map.add(String8("ThumbnailOffset"), String8::format("%d", image_data.thumbnail.offset));
        map.add(String8("ThumbnailLength"), String8::format("%d", image_data.thumbnail.length));
    } else {
        map.add(String8("HasThumbnail"), String8("false"));
    }

    map.add(
            String8("Orientation"),
            String8::format("%u", image_data.exif_orientation));
    map.add(
            String8("ImageWidth"),
            String8::format("%u", image_data.full_width));
    map.add(
            String8("ImageLength"),
            String8::format("%u", image_data.full_height));

    // Current PIEX does not have LightSource information while JPEG version of
    // EXIFInterface always declares the light source field. For the
    // compatibility, it provides the default value of the light source field.
    map.add(String8("LightSource"), String8("0"));

    if (!image_data.maker.empty()) {
        map.add(String8("Make"), String8(image_data.maker.c_str()));
    }

    if (!image_data.model.empty()) {
        map.add(String8("Model"), String8(image_data.model.c_str()));
    }

    if (!image_data.date_time.empty()) {
        map.add(String8("DateTime"), String8(image_data.date_time.c_str()));
    }

    if (image_data.iso) {
        map.add(
                String8("ISOSpeedRatings"),
                String8::format("%u", image_data.iso));
    }

    if (image_data.exposure_time.numerator != 0
            && image_data.exposure_time.denominator != 0) {
        double exposureTime =
            (double)image_data.exposure_time.numerator
            / image_data.exposure_time.denominator;

        const char* format;
        if (exposureTime < 0.01) {
            format = "%6.4f";
        } else {
            format = "%5.3f";
        }
        map.add(String8("ExposureTime"), String8::format(format, exposureTime));
    }

    if (image_data.fnumber.numerator != 0
            && image_data.fnumber.denominator != 0) {
        double fnumber =
            (double)image_data.fnumber.numerator
            / image_data.fnumber.denominator;
        map.add(String8("FNumber"), String8::format("%5.3f", fnumber));
    }

    if (image_data.focal_length.numerator != 0
            && image_data.focal_length.denominator != 0) {
        map.add(
                String8("FocalLength"),
                String8::format(
                        "%u/%u",
                        image_data.focal_length.numerator,
                        image_data.focal_length.denominator));
    }

    if (image_data.gps.is_valid) {
        if (image_data.gps.latitude[0].denominator != 0
                && image_data.gps.latitude[1].denominator != 0
                && image_data.gps.latitude[2].denominator != 0) {
            map.add(
                    String8("GPSLatitude"),
                    String8::format(
                            "%u/%u,%u/%u,%u/%u",
                            image_data.gps.latitude[0].numerator,
                            image_data.gps.latitude[0].denominator,
                            image_data.gps.latitude[1].numerator,
                            image_data.gps.latitude[1].denominator,
                            image_data.gps.latitude[2].numerator,
                            image_data.gps.latitude[2].denominator));
        }

        if (image_data.gps.latitude_ref) {
            char str[2];
            str[0] = image_data.gps.latitude_ref;
            str[1] = 0;
            map.add(String8("GPSLatitudeRef"), String8(str));
        }

        if (image_data.gps.longitude[0].denominator != 0
                && image_data.gps.longitude[1].denominator != 0
                && image_data.gps.longitude[2].denominator != 0) {
            map.add(
                    String8("GPSLongitude"),
                    String8::format(
                            "%u/%u,%u/%u,%u/%u",
                            image_data.gps.longitude[0].numerator,
                            image_data.gps.longitude[0].denominator,
                            image_data.gps.longitude[1].numerator,
                            image_data.gps.longitude[1].denominator,
                            image_data.gps.longitude[2].numerator,
                            image_data.gps.longitude[2].denominator));
        }

        if (image_data.gps.longitude_ref) {
            char str[2];
            str[0] = image_data.gps.longitude_ref;
            str[1] = 0;
            map.add(String8("GPSLongitudeRef"), String8(str));
        }

        if (image_data.gps.altitude.denominator != 0) {
            map.add(
                    String8("GPSAltitude"),
                    String8::format("%u/%u",
                            image_data.gps.altitude.numerator,
                            image_data.gps.altitude.denominator));

            map.add(
                    String8("GPSAltitudeRef"),
                    String8(image_data.gps.altitude_ref ? "1" : "0"));
        }

        if (image_data.gps.time_stamp[0].denominator != 0
                && image_data.gps.time_stamp[1].denominator != 0
                && image_data.gps.time_stamp[2].denominator != 0) {
            map.add(
                    String8("GPSTimeStamp"),
                    String8::format(
                            "%02u:%02u:%02u",
                            image_data.gps.time_stamp[0].numerator
                            / image_data.gps.time_stamp[0].denominator,
                            image_data.gps.time_stamp[1].numerator
                            / image_data.gps.time_stamp[1].denominator,
                            image_data.gps.time_stamp[2].numerator
                            / image_data.gps.time_stamp[2].denominator));
        }

        if (!image_data.gps.date_stamp.empty()) {
            map.add(
                    String8("GPSDateStamp"),
                    String8(image_data.gps.date_stamp.c_str()));
        }
    }

    jobject hashMap = KeyedVectorToHashMap(env, map);

    if (returnThumbnail) {
        std::unique_ptr<jbyte[]> thumbnailData(new jbyte[image_data.thumbnail.length]);
        if (thumbnailData.get() == NULL) {
            ALOGE("No memory to parse a thumbnail");
            return NULL;
        }
        jbyteArray jthumbnailByteArray = env->NewByteArray(image_data.thumbnail.length);
        if (jthumbnailByteArray == NULL) {
            ALOGE("No memory to parse a thumbnail");
            return NULL;
        }
        piexStream.get()->GetData(image_data.thumbnail.offset, image_data.thumbnail.length,
                (uint8_t*)thumbnailData.get());
        env->SetByteArrayRegion(
                jthumbnailByteArray, 0, image_data.thumbnail.length, thumbnailData.get());
        jstring jkey = env->NewStringUTF(String8("ThumbnailData"));
        env->CallObjectMethod(hashMap, gFields.hashMap.put, jkey, jthumbnailByteArray);
        env->DeleteLocalRef(jkey);
        env->DeleteLocalRef(jthumbnailByteArray);
    }
    return hashMap;
}

static jobject ExifInterface_getRawAttributesFromAsset(
        JNIEnv* env, jclass /* clazz */, jlong jasset) {
    std::unique_ptr<char[]> jpegSignature(new char[kJpegSignatureSize]);
    if (jpegSignature.get() == NULL) {
        ALOGE("No enough memory to parse");
        return NULL;
    }

    Asset* asset = reinterpret_cast<Asset*>(jasset);
    std::unique_ptr<AssetStreamAdaptor> stream(new AssetStreamAdaptor(asset));

    if (stream.get()->read(jpegSignature.get(), kJpegSignatureSize) != kJpegSignatureSize) {
        // Rewind the stream.
        stream.get()->rewind();

        ALOGI("Corrupted image.");
        return NULL;
    }

    // Rewind the stream.
    stream.get()->rewind();

    if (memcmp(jpegSignature.get(), kJpegSignatureChars, kJpegSignatureSize) == 0) {
        ALOGI("Should be a JPEG stream.");
        return NULL;
    }

    // Try to parse from the given stream.
    jobject result = getRawAttributes(env, stream.get(), false);

    // Rewind the stream for the chance to read JPEG.
    if (result == NULL) {
        stream.get()->rewind();
    }
    return result;
}

static jobject ExifInterface_getRawAttributesFromFileDescriptor(
        JNIEnv* env, jclass /* clazz */, jobject jfileDescriptor) {
    std::unique_ptr<char[]> jpegSignature(new char[kJpegSignatureSize]);
    if (jpegSignature.get() == NULL) {
        ALOGE("No enough memory to parse");
        return NULL;
    }

    int fd = jniGetFDFromFileDescriptor(env, jfileDescriptor);
    if (fd < 0) {
        ALOGI("Invalid file descriptor");
        return NULL;
    }

    // Restore the file descriptor's offset on exiting this function.
    AutoFDSeek autoRestore(fd);

    int dupFd = dup(fd);

    FILE* file = fdopen(dupFd, "r");
    if (file == NULL) {
        ALOGI("Failed to open the file descriptor");
        return NULL;
    }

    if (fgets(jpegSignature.get(), kJpegSignatureSize, file) == NULL) {
        ALOGI("Corrupted image.");
        return NULL;
    }

    if (memcmp(jpegSignature.get(), kJpegSignatureChars, kJpegSignatureSize) == 0) {
        ALOGI("Should be a JPEG stream.");
        return NULL;
    }

    // Rewind the file descriptor.
    fseek(file, 0L, SEEK_SET);

    std::unique_ptr<SkFILEStream> fileStream(new SkFILEStream(file,
                SkFILEStream::kCallerPasses_Ownership));
    return getRawAttributes(env, fileStream.get(), false);
}

static jobject ExifInterface_getRawAttributesFromInputStream(
        JNIEnv* env, jclass /* clazz */, jobject jinputStream) {
    jbyteArray byteArray = env->NewByteArray(8*1024);
    ScopedLocalRef<jbyteArray> scoper(env, byteArray);
    std::unique_ptr<SkStream> stream(CreateJavaInputStreamAdaptor(env, jinputStream, scoper.get()));
    return getRawAttributes(env, stream.get(), true);
}

} // extern "C"

// ----------------------------------------------------------------------------

static JNINativeMethod gMethods[] = {
    { "nativeInitRaw", "()V", (void *)ExifInterface_initRaw },
    { "nativeGetThumbnailFromAsset", "(JII)[B", (void *)ExifInterface_getThumbnailFromAsset },
    { "nativeGetRawAttributesFromAsset", "(J)Ljava/util/HashMap;",
      (void*)ExifInterface_getRawAttributesFromAsset },
    { "nativeGetRawAttributesFromFileDescriptor", "(Ljava/io/FileDescriptor;)Ljava/util/HashMap;",
      (void*)ExifInterface_getRawAttributesFromFileDescriptor },
    { "nativeGetRawAttributesFromInputStream", "(Ljava/io/InputStream;)Ljava/util/HashMap;",
      (void*)ExifInterface_getRawAttributesFromInputStream },
};

int register_android_media_ExifInterface(JNIEnv *env) {
    return AndroidRuntime::registerNativeMethods(
            env,
            "android/media/ExifInterface",
            gMethods,
            NELEM(gMethods));
}
