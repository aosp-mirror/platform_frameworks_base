/*
 * Copyright (C) 2013 The Android Open Source Project
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

#include "FontUtils.h"
#include "GraphicsJNI.h"
#include <nativehelper/ScopedPrimitiveArray.h>
#include <nativehelper/ScopedUtfChars.h>
#include "SkTypeface.h"
#include <hwui/Typeface.h>
#include <minikin/FontFamily.h>
#include <minikin/SystemFonts.h>

using namespace android;

static inline Typeface* toTypeface(jlong ptr) {
    return reinterpret_cast<Typeface*>(ptr);
}

template<typename Ptr> static inline jlong toJLong(Ptr ptr) {
    return reinterpret_cast<jlong>(ptr);
}

static jlong Typeface_createFromTypeface(JNIEnv* env, jobject, jlong familyHandle, jint style) {
    Typeface* family = toTypeface(familyHandle);
    Typeface* face = Typeface::createRelative(family, (Typeface::Style)style);
    // TODO: the following logic shouldn't be necessary, the above should always succeed.
    // Try to find the closest matching font, using the standard heuristic
    if (NULL == face) {
        face = Typeface::createRelative(family, (Typeface::Style)(style ^ Typeface::kItalic));
    }
    for (int i = 0; NULL == face && i < 4; i++) {
        face = Typeface::createRelative(family, (Typeface::Style)i);
    }
    return toJLong(face);
}

static jlong Typeface_createFromTypefaceWithExactStyle(JNIEnv* env, jobject, jlong nativeInstance,
        jint weight, jboolean italic) {
    return toJLong(Typeface::createAbsolute(toTypeface(nativeInstance), weight, italic));
}

static jlong Typeface_createFromTypefaceWithVariation(JNIEnv* env, jobject, jlong familyHandle,
        jobject listOfAxis) {
    std::vector<minikin::FontVariation> variations;
    ListHelper list(env, listOfAxis);
    for (jint i = 0; i < list.size(); i++) {
        jobject axisObject = list.get(i);
        if (axisObject == nullptr) {
            continue;
        }
        AxisHelper axis(env, axisObject);
        variations.push_back(minikin::FontVariation(axis.getTag(), axis.getStyleValue()));
    }
    return toJLong(Typeface::createFromTypefaceWithVariation(toTypeface(familyHandle), variations));
}

static jlong Typeface_createWeightAlias(JNIEnv* env, jobject, jlong familyHandle, jint weight) {
    return toJLong(Typeface::createWithDifferentBaseWeight(toTypeface(familyHandle), weight));
}

static void releaseFunc(jlong ptr) {
    delete toTypeface(ptr);
}

// CriticalNative
static jlong Typeface_getReleaseFunc(CRITICAL_JNI_PARAMS) {
    return toJLong(&releaseFunc);
}

// CriticalNative
static jint Typeface_getStyle(CRITICAL_JNI_PARAMS_COMMA jlong faceHandle) {
    return toTypeface(faceHandle)->fAPIStyle;
}

// CriticalNative
static jint Typeface_getWeight(CRITICAL_JNI_PARAMS_COMMA jlong faceHandle) {
    return toTypeface(faceHandle)->fStyle.weight();
}

static jlong Typeface_createFromArray(JNIEnv *env, jobject, jlongArray familyArray,
        int weight, int italic) {
    ScopedLongArrayRO families(env, familyArray);
    std::vector<std::shared_ptr<minikin::FontFamily>> familyVec;
    familyVec.reserve(families.size());
    for (size_t i = 0; i < families.size(); i++) {
        FontFamilyWrapper* family = reinterpret_cast<FontFamilyWrapper*>(families[i]);
        familyVec.emplace_back(family->family);
    }
    return toJLong(Typeface::createFromFamilies(std::move(familyVec), weight, italic));
}

// CriticalNative
static void Typeface_setDefault(CRITICAL_JNI_PARAMS_COMMA jlong faceHandle) {
    Typeface::setDefault(toTypeface(faceHandle));
    minikin::SystemFonts::registerDefault(toTypeface(faceHandle)->fFontCollection);
}

static jobject Typeface_getSupportedAxes(JNIEnv *env, jobject, jlong faceHandle) {
    Typeface* face = toTypeface(faceHandle);
    const std::unordered_set<minikin::AxisTag>& tagSet = face->fFontCollection->getSupportedTags();
    const size_t length = tagSet.size();
    if (length == 0) {
        return nullptr;
    }
    std::vector<jint> tagVec(length);
    int index = 0;
    for (const auto& tag : tagSet) {
        tagVec[index++] = tag;
    }
    std::sort(tagVec.begin(), tagVec.end());
    const jintArray result = env->NewIntArray(length);
    env->SetIntArrayRegion(result, 0, length, tagVec.data());
    return result;
}

static void Typeface_registerGenericFamily(JNIEnv *env, jobject, jstring familyName, jlong ptr) {
    ScopedUtfChars familyNameChars(env, familyName);
    minikin::SystemFonts::registerFallback(familyNameChars.c_str(),
                                           toTypeface(ptr)->fFontCollection);
}

///////////////////////////////////////////////////////////////////////////////

static const JNINativeMethod gTypefaceMethods[] = {
    { "nativeCreateFromTypeface", "(JI)J", (void*)Typeface_createFromTypeface },
    { "nativeCreateFromTypefaceWithExactStyle", "(JIZ)J",
            (void*)Typeface_createFromTypefaceWithExactStyle },
    { "nativeCreateFromTypefaceWithVariation", "(JLjava/util/List;)J",
            (void*)Typeface_createFromTypefaceWithVariation },
    { "nativeCreateWeightAlias",  "(JI)J", (void*)Typeface_createWeightAlias },
    { "nativeGetReleaseFunc",     "()J",  (void*)Typeface_getReleaseFunc },
    { "nativeGetStyle",           "(J)I",  (void*)Typeface_getStyle },
    { "nativeGetWeight",      "(J)I",  (void*)Typeface_getWeight },
    { "nativeCreateFromArray",    "([JII)J",
                                           (void*)Typeface_createFromArray },
    { "nativeSetDefault",         "(J)V",   (void*)Typeface_setDefault },
    { "nativeGetSupportedAxes",   "(J)[I",  (void*)Typeface_getSupportedAxes },
    { "nativeRegisterGenericFamily", "(Ljava/lang/String;J)V",
          (void*)Typeface_registerGenericFamily },
};

int register_android_graphics_Typeface(JNIEnv* env)
{
    return RegisterMethodsOrDie(env, "android/graphics/Typeface", gTypefaceMethods,
                                NELEM(gTypefaceMethods));
}
