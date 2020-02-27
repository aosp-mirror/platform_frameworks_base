/*
 * Copyright (C) 2018 The Android Open Source Project
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

#undef LOG_TAG
#define LOG_TAG "Minikin"

#include "SkData.h"
#include "SkFontMgr.h"
#include "SkRefCnt.h"
#include "SkTypeface.h"
#include "GraphicsJNI.h"
#include <nativehelper/ScopedUtfChars.h>
#include "Utils.h"
#include "FontUtils.h"

#include <hwui/MinikinSkia.h>
#include <hwui/Typeface.h>
#include <minikin/FontFamily.h>
#include <ui/FatVector.h>

#include <memory>

namespace android {

struct NativeFontBuilder {
    std::vector<minikin::FontVariation> axes;
};

static inline NativeFontBuilder* toBuilder(jlong ptr) {
    return reinterpret_cast<NativeFontBuilder*>(ptr);
}

static void releaseFont(jlong font) {
    delete reinterpret_cast<FontWrapper*>(font);
}

static void release_global_ref(const void* /*data*/, void* context) {
    JNIEnv* env = GraphicsJNI::getJNIEnv();
    bool needToAttach = (env == nullptr);
    if (needToAttach) {
        env = GraphicsJNI::attachJNIEnv("release_font_data");
        if (env == nullptr) {
            ALOGE("failed to attach to thread to release global ref.");
            return;
        }
    }

    jobject obj = reinterpret_cast<jobject>(context);
    env->DeleteGlobalRef(obj);
}

// Regular JNI
static jlong Font_Builder_initBuilder(JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(new NativeFontBuilder());
}

// Critical Native
static void Font_Builder_addAxis(CRITICAL_JNI_PARAMS_COMMA jlong builderPtr, jint tag, jfloat value) {
    toBuilder(builderPtr)->axes.emplace_back(static_cast<minikin::AxisTag>(tag), value);
}

// Regular JNI
static jlong Font_Builder_build(JNIEnv* env, jobject clazz, jlong builderPtr, jobject buffer,
        jstring filePath, jint weight, jboolean italic, jint ttcIndex) {
    NPE_CHECK_RETURN_ZERO(env, buffer);
    std::unique_ptr<NativeFontBuilder> builder(toBuilder(builderPtr));
    const void* fontPtr = env->GetDirectBufferAddress(buffer);
    if (fontPtr == nullptr) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "Not a direct buffer");
        return 0;
    }
    jlong fontSize = env->GetDirectBufferCapacity(buffer);
    if (fontSize <= 0) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "buffer size must not be zero or negative");
        return 0;
    }
    ScopedUtfChars fontPath(env, filePath);
    jobject fontRef = MakeGlobalRefOrDie(env, buffer);
    sk_sp<SkData> data(SkData::MakeWithProc(fontPtr, fontSize,
            release_global_ref, reinterpret_cast<void*>(fontRef)));

    FatVector<SkFontArguments::Axis, 2> skiaAxes;
    for (const auto& axis : builder->axes) {
        skiaAxes.emplace_back(SkFontArguments::Axis{axis.axisTag, axis.value});
    }

    std::unique_ptr<SkStreamAsset> fontData(new SkMemoryStream(std::move(data)));

    SkFontArguments params;
    params.setCollectionIndex(ttcIndex);
    params.setAxes(skiaAxes.data(), skiaAxes.size());

    sk_sp<SkFontMgr> fm(SkFontMgr::RefDefault());
    sk_sp<SkTypeface> face(fm->makeFromStream(std::move(fontData), params));
    if (face == nullptr) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "Failed to create internal object. maybe invalid font data.");
        return 0;
    }
    std::shared_ptr<minikin::MinikinFont> minikinFont =
            std::make_shared<MinikinFontSkia>(std::move(face), fontPtr, fontSize,
                                              std::string_view(fontPath.c_str(), fontPath.size()),
                                              ttcIndex, builder->axes);
    minikin::Font font = minikin::Font::Builder(minikinFont).setWeight(weight)
                    .setSlant(static_cast<minikin::FontStyle::Slant>(italic)).build();
    return reinterpret_cast<jlong>(new FontWrapper(std::move(font)));
}

// Critical Native
static jlong Font_Builder_getReleaseNativeFont(CRITICAL_JNI_PARAMS) {
    return reinterpret_cast<jlong>(releaseFont);
}

///////////////////////////////////////////////////////////////////////////////

static const JNINativeMethod gFontBuilderMethods[] = {
    { "nInitBuilder", "()J", (void*) Font_Builder_initBuilder },
    { "nAddAxis", "(JIF)V", (void*) Font_Builder_addAxis },
    { "nBuild", "(JLjava/nio/ByteBuffer;Ljava/lang/String;IZI)J", (void*) Font_Builder_build },
    { "nGetReleaseNativeFont", "()J", (void*) Font_Builder_getReleaseNativeFont },
};

int register_android_graphics_fonts_Font(JNIEnv* env) {
    return RegisterMethodsOrDie(env, "android/graphics/fonts/Font$Builder", gFontBuilderMethods,
            NELEM(gFontBuilderMethods));
}

}
