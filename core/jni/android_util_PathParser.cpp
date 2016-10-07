/*
 * Copyright (C) 2015 The Android Open Source Project
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

#include "jni.h"
#include "GraphicsJNI.h"

#include <PathParser.h>
#include <SkPath.h>
#include <utils/VectorDrawableUtils.h>

#include <android/log.h>
#include "core_jni_helpers.h"

namespace android {

using namespace uirenderer;

static void parseStringForPath(JNIEnv* env, jobject, jlong skPathHandle, jstring inputPathStr,
        jint strLength) {
    const char* pathString = env->GetStringUTFChars(inputPathStr, NULL);
    SkPath* skPath = reinterpret_cast<SkPath*>(skPathHandle);

    PathParser::ParseResult result;
    PathParser::parseAsciiStringForSkPath(skPath, &result, pathString, strLength);
    env->ReleaseStringUTFChars(inputPathStr, pathString);
    if (result.failureOccurred) {
        doThrowIAE(env, result.failureMessage.c_str());
    }
}

static long createEmptyPathData(JNIEnv*, jobject) {
    PathData* pathData = new PathData();
    return reinterpret_cast<jlong>(pathData);
}

static long createPathData(JNIEnv*, jobject, jlong pathDataPtr) {
    PathData* pathData = reinterpret_cast<PathData*>(pathDataPtr);
    PathData* newPathData = new PathData(*pathData);
    return reinterpret_cast<jlong>(newPathData);
}

static long createPathDataFromStringPath(JNIEnv* env, jobject, jstring inputStr, jint strLength) {
    const char* pathString = env->GetStringUTFChars(inputStr, NULL);
    PathData* pathData = new PathData();
    PathParser::ParseResult result;
    PathParser::getPathDataFromAsciiString(pathData, &result, pathString, strLength);
    env->ReleaseStringUTFChars(inputStr, pathString);
    if (!result.failureOccurred) {
        return reinterpret_cast<jlong>(pathData);
    } else {
        delete pathData;
        doThrowIAE(env, result.failureMessage.c_str());
        return NULL;
    }
}

static bool interpolatePathData(JNIEnv*, jobject, jlong outPathDataPtr, jlong fromPathDataPtr,
        jlong toPathDataPtr, jfloat fraction) {
    PathData* outPathData = reinterpret_cast<PathData*>(outPathDataPtr);
    PathData* fromPathData = reinterpret_cast<PathData*>(fromPathDataPtr);
    PathData* toPathData = reinterpret_cast<PathData*>(toPathDataPtr);
    return VectorDrawableUtils::interpolatePathData(outPathData, *fromPathData,
            *toPathData, fraction);
}

static void deletePathData(JNIEnv*, jobject, jlong pathDataHandle) {
    PathData* pathData = reinterpret_cast<PathData*>(pathDataHandle);
    delete pathData;
}

static bool canMorphPathData(JNIEnv*, jobject, jlong fromPathDataPtr, jlong toPathDataPtr) {
    PathData* fromPathData = reinterpret_cast<PathData*>(fromPathDataPtr);
    PathData* toPathData = reinterpret_cast<PathData*>(toPathDataPtr);
    return VectorDrawableUtils::canMorph(*fromPathData, *toPathData);
}

static void setPathData(JNIEnv*, jobject, jlong outPathDataPtr, jlong fromPathDataPtr) {
    PathData* fromPathData = reinterpret_cast<PathData*>(fromPathDataPtr);
    PathData* outPathData = reinterpret_cast<PathData*>(outPathDataPtr);
    *outPathData = *fromPathData;
}

static void setSkPathFromPathData(JNIEnv*, jobject, jlong outPathPtr, jlong pathDataPtr) {
    PathData* pathData = reinterpret_cast<PathData*>(pathDataPtr);
    SkPath* skPath = reinterpret_cast<SkPath*>(outPathPtr);
    VectorDrawableUtils::verbsToPath(skPath, *pathData);
}

static const JNINativeMethod gMethods[] = {
    {"nParseStringForPath", "(JLjava/lang/String;I)V", (void*)parseStringForPath},
    {"nCreatePathDataFromString", "(Ljava/lang/String;I)J", (void*)createPathDataFromStringPath},

    // ---------------- @FastNative -----------------

    {"nCreateEmptyPathData", "()J", (void*)createEmptyPathData},
    {"nCreatePathData", "(J)J", (void*)createPathData},
    {"nInterpolatePathData", "(JJJF)Z", (void*)interpolatePathData},
    {"nFinalize", "(J)V", (void*)deletePathData},
    {"nCanMorph", "(JJ)Z", (void*)canMorphPathData},
    {"nSetPathData", "(JJ)V", (void*)setPathData},
    {"nCreatePathFromPathData", "(JJ)V", (void*)setSkPathFromPathData},
};

int register_android_util_PathParser(JNIEnv* env) {
    return RegisterMethodsOrDie(env, "android/util/PathParser", gMethods, NELEM(gMethods));
}
};
