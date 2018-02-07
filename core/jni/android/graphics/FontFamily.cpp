/*
 * Copyright (C) 2014 The Android Open Source Project
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

#define LOG_TAG "Minikin"

#include <nativehelper/JNIHelp.h>
#include <core_jni_helpers.h>

#include "SkData.h"
#include "SkFontMgr.h"
#include "SkRefCnt.h"
#include "SkTypeface.h"
#include "GraphicsJNI.h"
#include <nativehelper/ScopedPrimitiveArray.h>
#include <nativehelper/ScopedUtfChars.h>
#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/android_util_AssetManager.h>
#include <androidfw/AssetManager.h>
#include "Utils.h"
#include "FontUtils.h"

#include <hwui/MinikinSkia.h>
#include <hwui/Typeface.h>
#include <utils/FatVector.h>
#include <minikin/FontFamily.h>
#include <minikin/LocaleList.h>

#include <memory>

namespace android {

struct NativeFamilyBuilder {
    NativeFamilyBuilder(uint32_t langId, int variant)
        : langId(langId), variant(static_cast<minikin::FontFamily::Variant>(variant)) {}
    uint32_t langId;
    minikin::FontFamily::Variant variant;
    std::vector<minikin::Font> fonts;
    std::vector<minikin::FontVariation> axes;
};

static jlong FontFamily_initBuilder(JNIEnv* env, jobject clazz, jstring langs, jint variant) {
    NativeFamilyBuilder* builder;
    if (langs != nullptr) {
        ScopedUtfChars str(env, langs);
        builder = new NativeFamilyBuilder(minikin::registerLocaleList(str.c_str()), variant);
    } else {
        builder = new NativeFamilyBuilder(minikin::registerLocaleList(""), variant);
    }
    return reinterpret_cast<jlong>(builder);
}

static jlong FontFamily_create(jlong builderPtr) {
    if (builderPtr == 0) {
        return 0;
    }
    std::unique_ptr<NativeFamilyBuilder> builder(
            reinterpret_cast<NativeFamilyBuilder*>(builderPtr));
    if (builder->fonts.empty()) {
        return 0;
    }
    std::shared_ptr<minikin::FontFamily> family = std::make_shared<minikin::FontFamily>(
            builder->langId, builder->variant, std::move(builder->fonts));
    if (family->getCoverage().length() == 0) {
        return 0;
    }
    return reinterpret_cast<jlong>(new FontFamilyWrapper(std::move(family)));
}

static void FontFamily_abort(jlong builderPtr) {
    NativeFamilyBuilder* builder = reinterpret_cast<NativeFamilyBuilder*>(builderPtr);
    delete builder;
}

static void FontFamily_unref(jlong familyPtr) {
    FontFamilyWrapper* family = reinterpret_cast<FontFamilyWrapper*>(familyPtr);
    delete family;
}

static bool addSkTypeface(NativeFamilyBuilder* builder, sk_sp<SkData>&& data, int ttcIndex,
        jint givenWeight, jint givenItalic) {
    uirenderer::FatVector<SkFontArguments::Axis, 2> skiaAxes;
    for (const auto& axis : builder->axes) {
        skiaAxes.emplace_back(SkFontArguments::Axis{axis.axisTag, axis.value});
    }

    const size_t fontSize = data->size();
    const void* fontPtr = data->data();
    std::unique_ptr<SkStreamAsset> fontData(new SkMemoryStream(std::move(data)));

    SkFontArguments params;
    params.setCollectionIndex(ttcIndex);
    params.setAxes(skiaAxes.data(), skiaAxes.size());

    sk_sp<SkFontMgr> fm(SkFontMgr::RefDefault());
    sk_sp<SkTypeface> face(fm->makeFromStream(std::move(fontData), params));
    if (face == NULL) {
        ALOGE("addFont failed to create font, invalid request");
        builder->axes.clear();
        return false;
    }
    std::shared_ptr<minikin::MinikinFont> minikinFont =
            std::make_shared<MinikinFontSkia>(std::move(face), fontPtr, fontSize, ttcIndex,
                    builder->axes);

    int weight = givenWeight;
    bool italic = givenItalic == 1;
    if (givenWeight == RESOLVE_BY_FONT_TABLE || givenItalic == RESOLVE_BY_FONT_TABLE) {
        int os2Weight;
        bool os2Italic;
        if (!minikin::FontFamily::analyzeStyle(minikinFont, &os2Weight, &os2Italic)) {
            ALOGE("analyzeStyle failed. Using default style");
            os2Weight = 400;
            os2Italic = false;
        }
        if (givenWeight == RESOLVE_BY_FONT_TABLE) {
            weight = os2Weight;
        }
        if (givenItalic == RESOLVE_BY_FONT_TABLE) {
            italic = os2Italic;
        }
    }

    builder->fonts.push_back(minikin::Font(minikinFont,
            minikin::FontStyle(weight, static_cast<minikin::FontStyle::Slant>(italic))));
    builder->axes.clear();
    return true;
}

static void release_global_ref(const void* /*data*/, void* context) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    bool needToAttach = (env == NULL);
    if (needToAttach) {
        JavaVMAttachArgs args;
        args.version = JNI_VERSION_1_4;
        args.name = "release_font_data";
        args.group = NULL;
        jint result = AndroidRuntime::getJavaVM()->AttachCurrentThread(&env, &args);
        if (result != JNI_OK) {
            ALOGE("failed to attach to thread to release global ref.");
            return;
        }
    }

    jobject obj = reinterpret_cast<jobject>(context);
    env->DeleteGlobalRef(obj);

    if (needToAttach) {
       AndroidRuntime::getJavaVM()->DetachCurrentThread();
    }
}

static jboolean FontFamily_addFont(JNIEnv* env, jobject clazz, jlong builderPtr, jobject bytebuf,
        jint ttcIndex, jint weight, jint isItalic) {
    NPE_CHECK_RETURN_ZERO(env, bytebuf);
    NativeFamilyBuilder* builder = reinterpret_cast<NativeFamilyBuilder*>(builderPtr);
    const void* fontPtr = env->GetDirectBufferAddress(bytebuf);
    if (fontPtr == NULL) {
        ALOGE("addFont failed to create font, buffer invalid");
        builder->axes.clear();
        return false;
    }
    jlong fontSize = env->GetDirectBufferCapacity(bytebuf);
    if (fontSize < 0) {
        ALOGE("addFont failed to create font, buffer size invalid");
        builder->axes.clear();
        return false;
    }
    jobject fontRef = MakeGlobalRefOrDie(env, bytebuf);
    sk_sp<SkData> data(SkData::MakeWithProc(fontPtr, fontSize,
            release_global_ref, reinterpret_cast<void*>(fontRef)));
    return addSkTypeface(builder, std::move(data), ttcIndex, weight, isItalic);
}

static jboolean FontFamily_addFontWeightStyle(JNIEnv* env, jobject clazz, jlong builderPtr,
        jobject font, jint ttcIndex, jint weight, jint isItalic) {
    NPE_CHECK_RETURN_ZERO(env, font);
    NativeFamilyBuilder* builder = reinterpret_cast<NativeFamilyBuilder*>(builderPtr);
    const void* fontPtr = env->GetDirectBufferAddress(font);
    if (fontPtr == NULL) {
        ALOGE("addFont failed to create font, buffer invalid");
        builder->axes.clear();
        return false;
    }
    jlong fontSize = env->GetDirectBufferCapacity(font);
    if (fontSize < 0) {
        ALOGE("addFont failed to create font, buffer size invalid");
        builder->axes.clear();
        return false;
    }
    jobject fontRef = MakeGlobalRefOrDie(env, font);
    sk_sp<SkData> data(SkData::MakeWithProc(fontPtr, fontSize,
            release_global_ref, reinterpret_cast<void*>(fontRef)));
    return addSkTypeface(builder, std::move(data), ttcIndex, weight, isItalic);
}

static void releaseAsset(const void* ptr, void* context) {
    delete static_cast<Asset*>(context);
}

static jboolean FontFamily_addFontFromAssetManager(JNIEnv* env, jobject, jlong builderPtr,
        jobject jassetMgr, jstring jpath, jint cookie, jboolean isAsset, jint ttcIndex,
        jint weight, jint isItalic) {
    NPE_CHECK_RETURN_ZERO(env, jassetMgr);
    NPE_CHECK_RETURN_ZERO(env, jpath);

    NativeFamilyBuilder* builder = reinterpret_cast<NativeFamilyBuilder*>(builderPtr);
    AssetManager* mgr = assetManagerForJavaObject(env, jassetMgr);
    if (NULL == mgr) {
        builder->axes.clear();
        return false;
    }

    ScopedUtfChars str(env, jpath);
    if (str.c_str() == nullptr) {
        builder->axes.clear();
        return false;
    }

    Asset* asset;
    if (isAsset) {
        asset = mgr->open(str.c_str(), Asset::ACCESS_BUFFER);
    } else {
        asset = cookie ? mgr->openNonAsset(static_cast<int32_t>(cookie), str.c_str(),
                Asset::ACCESS_BUFFER) : mgr->openNonAsset(str.c_str(), Asset::ACCESS_BUFFER);
    }

    if (NULL == asset) {
        builder->axes.clear();
        return false;
    }

    const void* buf = asset->getBuffer(false);
    if (NULL == buf) {
        delete asset;
        builder->axes.clear();
        return false;
    }

    sk_sp<SkData> data(SkData::MakeWithProc(buf, asset->getLength(), releaseAsset, asset));
    return addSkTypeface(builder, std::move(data), ttcIndex, weight, isItalic);
}

static void FontFamily_addAxisValue(jlong builderPtr, jint tag, jfloat value) {
    NativeFamilyBuilder* builder = reinterpret_cast<NativeFamilyBuilder*>(builderPtr);
    builder->axes.push_back({static_cast<minikin::AxisTag>(tag), value});
}

///////////////////////////////////////////////////////////////////////////////

static const JNINativeMethod gFontFamilyMethods[] = {
    { "nInitBuilder",          "(Ljava/lang/String;I)J", (void*)FontFamily_initBuilder },
    { "nCreateFamily",         "(J)J", (void*)FontFamily_create },
    { "nAbort",                "(J)V", (void*)FontFamily_abort },
    { "nUnrefFamily",          "(J)V", (void*)FontFamily_unref },
    { "nAddFont",              "(JLjava/nio/ByteBuffer;III)Z", (void*)FontFamily_addFont },
    { "nAddFontWeightStyle",   "(JLjava/nio/ByteBuffer;III)Z",
            (void*)FontFamily_addFontWeightStyle },
    { "nAddFontFromAssetManager",    "(JLandroid/content/res/AssetManager;Ljava/lang/String;IZIII)Z",
            (void*)FontFamily_addFontFromAssetManager },
    { "nAddAxisValue",         "(JIF)V", (void*)FontFamily_addAxisValue },
};

int register_android_graphics_FontFamily(JNIEnv* env)
{
    int err = RegisterMethodsOrDie(env, "android/graphics/FontFamily", gFontFamilyMethods,
            NELEM(gFontFamilyMethods));

    init_FontUtils(env);
    return err;
}

}
