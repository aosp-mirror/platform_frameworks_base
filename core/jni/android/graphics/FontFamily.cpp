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

#include "JNIHelp.h"
#include <core_jni_helpers.h>

#include "SkData.h"
#include "SkFontMgr.h"
#include "SkRefCnt.h"
#include "SkTypeface.h"
#include "GraphicsJNI.h"
#include <ScopedPrimitiveArray.h>
#include <ScopedUtfChars.h>
#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/android_util_AssetManager.h>
#include <androidfw/AssetManager.h>
#include "Utils.h"
#include "FontUtils.h"

#include <hwui/MinikinSkia.h>
#include <hwui/Typeface.h>
#include <minikin/FontFamily.h>

#include <memory>

namespace android {

struct NativeFamilyBuilder {
    uint32_t langId;
    int variant;
    std::vector<minikin::Font> fonts;
};

static jlong FontFamily_initBuilder(JNIEnv* env, jobject clazz, jstring lang, jint variant) {
    NativeFamilyBuilder* builder = new NativeFamilyBuilder();
    if (lang != nullptr) {
        ScopedUtfChars str(env, lang);
        builder->langId = minikin::FontStyle::registerLanguageList(str.c_str());
    } else {
        builder->langId = minikin::FontStyle::registerLanguageList("");
    }
    builder->variant = variant;
    return reinterpret_cast<jlong>(builder);
}

static jlong FontFamily_create(jlong builderPtr) {
    if (builderPtr == 0) {
        return 0;
    }
    NativeFamilyBuilder* builder = reinterpret_cast<NativeFamilyBuilder*>(builderPtr);
    minikin::FontFamily* family = new minikin::FontFamily(
            builder->langId, builder->variant, std::move(builder->fonts));
    delete builder;
    return reinterpret_cast<jlong>(family);
}

static void FontFamily_abort(jlong builderPtr) {
    NativeFamilyBuilder* builder = reinterpret_cast<NativeFamilyBuilder*>(builderPtr);
    minikin::Font::clearElementsWithLock(&builder->fonts);
    delete builder;
}

static void FontFamily_unref(jlong familyPtr) {
    minikin::FontFamily* fontFamily = reinterpret_cast<minikin::FontFamily*>(familyPtr);
    fontFamily->Unref();
}

static void addSkTypeface(jlong builderPtr, sk_sp<SkTypeface> face, const void* fontData,
        size_t fontSize, int ttcIndex) {
    minikin::MinikinFont* minikinFont =
            new MinikinFontSkia(std::move(face), fontData, fontSize, ttcIndex,
                    std::vector<minikin::FontVariation>());
    NativeFamilyBuilder* builder = reinterpret_cast<NativeFamilyBuilder*>(builderPtr);
    int weight;
    bool italic;
    if (!minikin::FontFamily::analyzeStyle(minikinFont, &weight, &italic)) {
        ALOGE("analyzeStyle failed. Using default style");
        weight = 400;
        italic = false;
    }
    builder->fonts.push_back(minikin::Font(minikinFont, minikin::FontStyle(weight / 100, italic)));
    minikinFont->Unref();
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
        jint ttcIndex) {
    NPE_CHECK_RETURN_ZERO(env, bytebuf);
    const void* fontPtr = env->GetDirectBufferAddress(bytebuf);
    if (fontPtr == NULL) {
        ALOGE("addFont failed to create font, buffer invalid");
        return false;
    }
    jlong fontSize = env->GetDirectBufferCapacity(bytebuf);
    if (fontSize < 0) {
        ALOGE("addFont failed to create font, buffer size invalid");
        return false;
    }
    jobject fontRef = MakeGlobalRefOrDie(env, bytebuf);
    sk_sp<SkData> data(SkData::MakeWithProc(fontPtr, fontSize,
            release_global_ref, reinterpret_cast<void*>(fontRef)));
    std::unique_ptr<SkStreamAsset> fontData(new SkMemoryStream(std::move(data)));

    SkFontMgr::FontParameters params;
    params.setCollectionIndex(ttcIndex);

    sk_sp<SkFontMgr> fm(SkFontMgr::RefDefault());
    sk_sp<SkTypeface> face(fm->createFromStream(fontData.release(), params));
    if (face == NULL) {
        ALOGE("addFont failed to create font");
        return false;
    }
    addSkTypeface(builderPtr, std::move(face), fontPtr, (size_t)fontSize, ttcIndex);
    return true;
}

static jboolean FontFamily_addFontWeightStyle(JNIEnv* env, jobject clazz, jlong builderPtr,
        jobject font, jint ttcIndex, jobject listOfAxis, jint weight, jboolean isItalic) {
    NPE_CHECK_RETURN_ZERO(env, font);

    // Declare axis native type.
    std::unique_ptr<SkFontMgr::FontParameters::Axis[]> skiaAxes;
    int skiaAxesLength = 0;
    if (listOfAxis) {
        ListHelper list(env, listOfAxis);
        jint listSize = list.size();

        skiaAxes.reset(new SkFontMgr::FontParameters::Axis[listSize]);
        skiaAxesLength = listSize;
        for (jint i = 0; i < listSize; ++i) {
            jobject axisObject = list.get(i);
            if (!axisObject) {
                skiaAxes[i].fTag = 0;
                skiaAxes[i].fStyleValue = 0;
                continue;
            }
            AxisHelper axis(env, axisObject);

            jint tag = axis.getTag();
            jfloat stylevalue = axis.getStyleValue();
            skiaAxes[i].fTag = tag;
            skiaAxes[i].fStyleValue = SkFloatToScalar(stylevalue);
        }
    }

    const void* fontPtr = env->GetDirectBufferAddress(font);
    if (fontPtr == NULL) {
        ALOGE("addFont failed to create font, buffer invalid");
        return false;
    }
    jlong fontSize = env->GetDirectBufferCapacity(font);
    if (fontSize < 0) {
        ALOGE("addFont failed to create font, buffer size invalid");
        return false;
    }
    jobject fontRef = MakeGlobalRefOrDie(env, font);
    sk_sp<SkData> data(SkData::MakeWithProc(fontPtr, fontSize,
            release_global_ref, reinterpret_cast<void*>(fontRef)));
    std::unique_ptr<SkStreamAsset> fontData(new SkMemoryStream(std::move(data)));

    SkFontMgr::FontParameters params;
    params.setCollectionIndex(ttcIndex);
    params.setAxes(skiaAxes.get(), skiaAxesLength);

    sk_sp<SkFontMgr> fm(SkFontMgr::RefDefault());
    sk_sp<SkTypeface> face(fm->createFromStream(fontData.release(), params));
    if (face == NULL) {
        ALOGE("addFont failed to create font, invalid request");
        return false;
    }
    minikin::MinikinFont* minikinFont =
            new MinikinFontSkia(std::move(face), fontPtr, fontSize, ttcIndex,
                    std::vector<minikin::FontVariation>());
    NativeFamilyBuilder* builder = reinterpret_cast<NativeFamilyBuilder*>(builderPtr);
    builder->fonts.push_back(minikin::Font(minikinFont,
            minikin::FontStyle(weight / 100, isItalic)));
    minikinFont->Unref();
    return true;
}

static void releaseAsset(const void* ptr, void* context) {
    delete static_cast<Asset*>(context);
}

static jboolean FontFamily_addFontFromAssetManager(JNIEnv* env, jobject, jlong builderPtr,
        jobject jassetMgr, jstring jpath, jint cookie, jboolean isAsset) {
    NPE_CHECK_RETURN_ZERO(env, jassetMgr);
    NPE_CHECK_RETURN_ZERO(env, jpath);

    AssetManager* mgr = assetManagerForJavaObject(env, jassetMgr);
    if (NULL == mgr) {
        return false;
    }

    ScopedUtfChars str(env, jpath);
    if (str.c_str() == nullptr) {
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
        return false;
    }

    const void* buf = asset->getBuffer(false);
    if (NULL == buf) {
        delete asset;
        return false;
    }

    size_t bufSize = asset->getLength();
    sk_sp<SkData> data(SkData::MakeWithProc(buf, asset->getLength(), releaseAsset, asset));
    std::unique_ptr<SkStreamAsset> fontData(new SkMemoryStream(std::move(data)));

    sk_sp<SkFontMgr> fm(SkFontMgr::RefDefault());
    sk_sp<SkTypeface> face(fm->createFromStream(fontData.release(), SkFontMgr::FontParameters()));
    if (face == NULL) {
        ALOGE("addFontFromAsset failed to create font %s", str.c_str());
        return false;
    }

    addSkTypeface(builderPtr, std::move(face), buf, bufSize, 0 /* ttc index */);
    return true;
}

///////////////////////////////////////////////////////////////////////////////

static const JNINativeMethod gFontFamilyMethods[] = {
    { "nInitBuilder",          "(Ljava/lang/String;I)J", (void*)FontFamily_initBuilder },
    { "nCreateFamily",         "(J)J", (void*)FontFamily_create },
    { "nAbort",                "(J)V", (void*)FontFamily_abort },
    { "nUnrefFamily",          "(J)V", (void*)FontFamily_unref },
    { "nAddFont",              "(JLjava/nio/ByteBuffer;I)Z", (void*)FontFamily_addFont },
    { "nAddFontWeightStyle",   "(JLjava/nio/ByteBuffer;ILjava/util/List;IZ)Z",
            (void*)FontFamily_addFontWeightStyle },
    { "nAddFontFromAssetManager",     "(JLandroid/content/res/AssetManager;Ljava/lang/String;IZ)Z",
            (void*)FontFamily_addFontFromAssetManager },
};

int register_android_graphics_FontFamily(JNIEnv* env)
{
    int err = RegisterMethodsOrDie(env, "android/graphics/FontFamily", gFontFamilyMethods,
            NELEM(gFontFamilyMethods));

    init_FontUtils(env);
    return err;
}

}
