/*
 * Copyright 2020, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <jni.h>
#include <media/MediaMetrics.h>

#define JNI_FUNCTION(RETURN_TYPE, NAME, ...)                                               \
    extern "C" {                                                                           \
    JNIEXPORT RETURN_TYPE Java_android_media_MediaParser_##NAME(JNIEnv* env, jobject thiz, \
                                                                ##__VA_ARGS__);            \
    }                                                                                      \
    JNIEXPORT RETURN_TYPE Java_android_media_MediaParser_##NAME(JNIEnv* env, jobject thiz, \
                                                                ##__VA_ARGS__)

namespace {

constexpr char kMediaMetricsKey[] = "mediaparser";

constexpr char kAttributeParserName[] = "android.media.mediaparser.parserName";
constexpr char kAttributeCreatedByName[] = "android.media.mediaparser.createdByName";
constexpr char kAttributeParserPool[] = "android.media.mediaparser.parserPool";
constexpr char kAttributeLastException[] = "android.media.mediaparser.lastException";
constexpr char kAttributeResourceByteCount[] = "android.media.mediaparser.resourceByteCount";
constexpr char kAttributeDurationMillis[] = "android.media.mediaparser.durationMillis";
constexpr char kAttributeTrackMimeTypes[] = "android.media.mediaparser.trackMimeTypes";
constexpr char kAttributeTrackCodecs[] = "android.media.mediaparser.trackCodecs";
constexpr char kAttributeAlteredParameters[] = "android.media.mediaparser.alteredParameters";
constexpr char kAttributeVideoWidth[] = "android.media.mediaparser.videoWidth";
constexpr char kAttributeVideoHeight[] = "android.media.mediaparser.videoHeight";

// Util class to handle string resource management.
class JstringHandle {
public:
    JstringHandle(JNIEnv* env, jstring value) : mEnv(env), mJstringValue(value) {
        mCstringValue = env->GetStringUTFChars(value, /* isCopy= */ nullptr);
    }

    ~JstringHandle() {
        if (mCstringValue != nullptr) {
            mEnv->ReleaseStringUTFChars(mJstringValue, mCstringValue);
        }
    }

    [[nodiscard]] const char* value() const {
        return mCstringValue != nullptr ? mCstringValue : "";
    }

    JNIEnv* mEnv;
    jstring mJstringValue;
    const char* mCstringValue;
};

} // namespace

JNI_FUNCTION(void, nativeSubmitMetrics, jstring parserNameJstring, jboolean createdByName,
             jstring parserPoolJstring, jstring lastExceptionJstring, jlong resourceByteCount,
             jlong durationMillis, jstring trackMimeTypesJstring, jstring trackCodecsJstring,
             jstring alteredParameters, jint videoWidth, jint videoHeight) {
    mediametrics_handle_t item(mediametrics_create(kMediaMetricsKey));
    mediametrics_setCString(item, kAttributeParserName,
                            JstringHandle(env, parserNameJstring).value());
    mediametrics_setInt32(item, kAttributeCreatedByName, createdByName ? 1 : 0);
    mediametrics_setCString(item, kAttributeParserPool,
                            JstringHandle(env, parserPoolJstring).value());
    mediametrics_setCString(item, kAttributeLastException,
                            JstringHandle(env, lastExceptionJstring).value());
    mediametrics_setInt64(item, kAttributeResourceByteCount, resourceByteCount);
    mediametrics_setInt64(item, kAttributeDurationMillis, durationMillis);
    mediametrics_setCString(item, kAttributeTrackMimeTypes,
                            JstringHandle(env, trackMimeTypesJstring).value());
    mediametrics_setCString(item, kAttributeTrackCodecs,
                            JstringHandle(env, trackCodecsJstring).value());
    mediametrics_setCString(item, kAttributeAlteredParameters,
                            JstringHandle(env, alteredParameters).value());
    mediametrics_setInt32(item, kAttributeVideoWidth, videoWidth);
    mediametrics_setInt32(item, kAttributeVideoHeight, videoHeight);
    mediametrics_selfRecord(item);
    mediametrics_delete(item);
}
