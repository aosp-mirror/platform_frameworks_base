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

#include "GraphicsJNI.h"
#include "jni.h"
#include "core_jni_helpers.h"

#include "PathParser.h"
#include "VectorDrawable.h"

#include <hwui/Paint.h>

namespace android {
using namespace uirenderer;
using namespace uirenderer::VectorDrawable;

/**
 * VectorDrawable's pre-draw construction.
 */
static jlong createTree(JNIEnv*, jobject, jlong groupPtr) {
    VectorDrawable::Group* rootGroup = reinterpret_cast<VectorDrawable::Group*>(groupPtr);
    VectorDrawable::Tree* tree = new VectorDrawable::Tree(rootGroup);
    return reinterpret_cast<jlong>(tree);
}

static jlong createTreeFromCopy(JNIEnv*, jobject, jlong treePtr, jlong groupPtr) {
    VectorDrawable::Group* rootGroup = reinterpret_cast<VectorDrawable::Group*>(groupPtr);
    VectorDrawable::Tree* treeToCopy = reinterpret_cast<VectorDrawable::Tree*>(treePtr);
    VectorDrawable::Tree* tree = new VectorDrawable::Tree(treeToCopy, rootGroup);
    return reinterpret_cast<jlong>(tree);
}

static jlong createEmptyFullPath(JNIEnv*, jobject) {
    VectorDrawable::FullPath* newPath = new VectorDrawable::FullPath();
    return reinterpret_cast<jlong>(newPath);
}

static jlong createFullPath(JNIEnv*, jobject, jlong srcFullPathPtr) {
    VectorDrawable::FullPath* srcFullPath =
            reinterpret_cast<VectorDrawable::FullPath*>(srcFullPathPtr);
    VectorDrawable::FullPath* newPath = new VectorDrawable::FullPath(*srcFullPath);
    return reinterpret_cast<jlong>(newPath);
}

static jlong createEmptyClipPath(JNIEnv*, jobject) {
    VectorDrawable::ClipPath* newPath = new VectorDrawable::ClipPath();
    return reinterpret_cast<jlong>(newPath);
}

static jlong createClipPath(JNIEnv*, jobject, jlong srcClipPathPtr) {
    VectorDrawable::ClipPath* srcClipPath =
            reinterpret_cast<VectorDrawable::ClipPath*>(srcClipPathPtr);
    VectorDrawable::ClipPath* newPath = new VectorDrawable::ClipPath(*srcClipPath);
    return reinterpret_cast<jlong>(newPath);
}

static jlong createEmptyGroup(JNIEnv*, jobject) {
    VectorDrawable::Group* newGroup = new VectorDrawable::Group();
    return reinterpret_cast<jlong>(newGroup);
}

static jlong createGroup(JNIEnv*, jobject, jlong srcGroupPtr) {
    VectorDrawable::Group* srcGroup = reinterpret_cast<VectorDrawable::Group*>(srcGroupPtr);
    VectorDrawable::Group* newGroup = new VectorDrawable::Group(*srcGroup);
    return reinterpret_cast<jlong>(newGroup);
}

static void setNodeName(JNIEnv* env, jobject, jlong nodePtr, jstring nameStr) {
    VectorDrawable::Node* node = reinterpret_cast<VectorDrawable::Node*>(nodePtr);
    const char* nodeName = env->GetStringUTFChars(nameStr, NULL);
    node->setName(nodeName);
    env->ReleaseStringUTFChars(nameStr, nodeName);
}

static void addChild(JNIEnv*, jobject, jlong groupPtr, jlong childPtr) {
    VectorDrawable::Group* group = reinterpret_cast<VectorDrawable::Group*>(groupPtr);
    VectorDrawable::Node* child = reinterpret_cast<VectorDrawable::Node*>(childPtr);
    group->addChild(child);
}

static void setAllowCaching(JNIEnv*, jobject, jlong treePtr, jboolean allowCaching) {
    VectorDrawable::Tree* tree = reinterpret_cast<VectorDrawable::Tree*>(treePtr);
    tree->setAllowCaching(allowCaching);
}

/**
 * Draw
 */
static int draw(JNIEnv* env, jobject, jlong treePtr, jlong canvasPtr,
        jlong colorFilterPtr, jobject jrect, jboolean needsMirroring, jboolean canReuseCache) {
    VectorDrawable::Tree* tree = reinterpret_cast<VectorDrawable::Tree*>(treePtr);
    Canvas* canvas = reinterpret_cast<Canvas*>(canvasPtr);
    SkRect rect;
    GraphicsJNI::jrect_to_rect(env, jrect, &rect);
    SkColorFilter* colorFilter = reinterpret_cast<SkColorFilter*>(colorFilterPtr);
    return tree->draw(canvas, colorFilter, rect, needsMirroring, canReuseCache);
}

/**
 * Setters and getters for updating staging properties that can happen both pre-draw and post draw.
 */
static void setTreeViewportSize(JNIEnv*, jobject, jlong treePtr,
        jfloat viewportWidth, jfloat viewportHeight) {
    VectorDrawable::Tree* tree = reinterpret_cast<VectorDrawable::Tree*>(treePtr);
    tree->mutateStagingProperties()->setViewportSize(viewportWidth, viewportHeight);
}

static jboolean setRootAlpha(JNIEnv*, jobject, jlong treePtr, jfloat alpha) {
    VectorDrawable::Tree* tree = reinterpret_cast<VectorDrawable::Tree*>(treePtr);
    return tree->mutateStagingProperties()->setRootAlpha(alpha);
}

static jfloat getRootAlpha(JNIEnv*, jobject, jlong treePtr) {
    VectorDrawable::Tree* tree = reinterpret_cast<VectorDrawable::Tree*>(treePtr);
    return tree->stagingProperties()->getRootAlpha();
}

static void updateFullPathPropertiesAndStrokeStyles(JNIEnv*, jobject, jlong fullPathPtr,
        jfloat strokeWidth, jint strokeColor, jfloat strokeAlpha, jint fillColor, jfloat fillAlpha,
        jfloat trimPathStart, jfloat trimPathEnd, jfloat trimPathOffset, jfloat strokeMiterLimit,
        jint strokeLineCap, jint strokeLineJoin, jint fillType) {
    VectorDrawable::FullPath* fullPath = reinterpret_cast<VectorDrawable::FullPath*>(fullPathPtr);
    fullPath->mutateStagingProperties()->updateProperties(strokeWidth, strokeColor, strokeAlpha,
            fillColor, fillAlpha, trimPathStart, trimPathEnd, trimPathOffset, strokeMiterLimit,
            strokeLineCap, strokeLineJoin, fillType);
}

static void updateFullPathFillGradient(JNIEnv*, jobject, jlong pathPtr, jlong fillGradientPtr) {
    VectorDrawable::FullPath* path = reinterpret_cast<VectorDrawable::FullPath*>(pathPtr);
    SkShader* fillShader = reinterpret_cast<SkShader*>(fillGradientPtr);
    path->mutateStagingProperties()->setFillGradient(fillShader);
}

static void updateFullPathStrokeGradient(JNIEnv*, jobject, jlong pathPtr, jlong strokeGradientPtr) {
    VectorDrawable::FullPath* path = reinterpret_cast<VectorDrawable::FullPath*>(pathPtr);
    SkShader* strokeShader = reinterpret_cast<SkShader*>(strokeGradientPtr);
    path->mutateStagingProperties()->setStrokeGradient(strokeShader);
}

static jboolean getFullPathProperties(JNIEnv* env, jobject, jlong fullPathPtr,
        jbyteArray outProperties, jint length) {
    VectorDrawable::FullPath* fullPath = reinterpret_cast<VectorDrawable::FullPath*>(fullPathPtr);
    int8_t pathProperties[length];
    bool success = fullPath->stagingProperties()->copyProperties(pathProperties, length);
    env->SetByteArrayRegion(outProperties, 0, length, reinterpret_cast<int8_t*>(&pathProperties));
    return success;
}

static jboolean getGroupProperties(JNIEnv* env, jobject, jlong groupPtr,
        jfloatArray outProperties, jint length) {
    VectorDrawable::Group* group = reinterpret_cast<VectorDrawable::Group*>(groupPtr);
    float groupProperties[length];
    bool success = group->stagingProperties()->copyProperties(groupProperties, length);
    env->SetFloatArrayRegion(outProperties, 0, length, reinterpret_cast<float*>(&groupProperties));
    return success;
}

static void updateGroupProperties(JNIEnv*, jobject, jlong groupPtr, jfloat rotate, jfloat pivotX,
        jfloat pivotY, jfloat scaleX, jfloat scaleY, jfloat translateX, jfloat translateY) {
    VectorDrawable::Group* group = reinterpret_cast<VectorDrawable::Group*>(groupPtr);
    group->mutateStagingProperties()->updateProperties(rotate, pivotX, pivotY, scaleX, scaleY,
            translateX, translateY);
}

static void setPathString(JNIEnv* env, jobject, jlong pathPtr, jstring inputStr,
        jint stringLength) {
    VectorDrawable::Path* path = reinterpret_cast<VectorDrawable::Path*>(pathPtr);
    const char* pathString = env->GetStringUTFChars(inputStr, NULL);

    PathParser::ParseResult result;
    PathData data;
    PathParser::getPathDataFromAsciiString(&data, &result, pathString, stringLength);
    if (result.failureOccurred) {
        doThrowIAE(env, result.failureMessage.c_str());
    }
    path->mutateStagingProperties()->setData(data);
    env->ReleaseStringUTFChars(inputStr, pathString);
}

/**
 * Setters and getters that should only be called from animation thread for animation purpose.
 */
static jfloat getRotation(JNIEnv*, jobject, jlong groupPtr) {
    VectorDrawable::Group* group = reinterpret_cast<VectorDrawable::Group*>(groupPtr);
    return group->stagingProperties()->getRotation();
}

static void setRotation(JNIEnv*, jobject, jlong groupPtr, jfloat rotation) {
    VectorDrawable::Group* group = reinterpret_cast<VectorDrawable::Group*>(groupPtr);
    group->mutateStagingProperties()->setRotation(rotation);
}

static jfloat getPivotX(JNIEnv*, jobject, jlong groupPtr) {
    VectorDrawable::Group* group = reinterpret_cast<VectorDrawable::Group*>(groupPtr);
    return group->stagingProperties()->getPivotX();
}

static void setPivotX(JNIEnv*, jobject, jlong groupPtr, jfloat pivotX) {
    VectorDrawable::Group* group = reinterpret_cast<VectorDrawable::Group*>(groupPtr);
    group->mutateStagingProperties()->setPivotX(pivotX);
}

static jfloat getPivotY(JNIEnv*, jobject, jlong groupPtr) {
    VectorDrawable::Group* group = reinterpret_cast<VectorDrawable::Group*>(groupPtr);
    return group->stagingProperties()->getPivotY();
}

static void setPivotY(JNIEnv*, jobject, jlong groupPtr, jfloat pivotY) {
    VectorDrawable::Group* group = reinterpret_cast<VectorDrawable::Group*>(groupPtr);
    group->mutateStagingProperties()->setPivotY(pivotY);
}

static jfloat getScaleX(JNIEnv*, jobject, jlong groupPtr) {
    VectorDrawable::Group* group = reinterpret_cast<VectorDrawable::Group*>(groupPtr);
    return group->stagingProperties()->getScaleX();
}

static void setScaleX(JNIEnv*, jobject, jlong groupPtr, jfloat scaleX) {
    VectorDrawable::Group* group = reinterpret_cast<VectorDrawable::Group*>(groupPtr);
    group->mutateStagingProperties()->setScaleX(scaleX);
}

static jfloat getScaleY(JNIEnv*, jobject, jlong groupPtr) {
    VectorDrawable::Group* group = reinterpret_cast<VectorDrawable::Group*>(groupPtr);
    return group->stagingProperties()->getScaleY();
}

static void setScaleY(JNIEnv*, jobject, jlong groupPtr, jfloat scaleY) {
    VectorDrawable::Group* group = reinterpret_cast<VectorDrawable::Group*>(groupPtr);
    group->mutateStagingProperties()->setScaleY(scaleY);
}

static jfloat getTranslateX(JNIEnv*, jobject, jlong groupPtr) {
    VectorDrawable::Group* group = reinterpret_cast<VectorDrawable::Group*>(groupPtr);
    return group->stagingProperties()->getTranslateX();
}

static void setTranslateX(JNIEnv*, jobject, jlong groupPtr, jfloat translateX) {
    VectorDrawable::Group* group = reinterpret_cast<VectorDrawable::Group*>(groupPtr);
    group->mutateStagingProperties()->setTranslateX(translateX);
}

static jfloat getTranslateY(JNIEnv*, jobject, jlong groupPtr) {
    VectorDrawable::Group* group = reinterpret_cast<VectorDrawable::Group*>(groupPtr);
    return group->stagingProperties()->getTranslateY();
}

static void setTranslateY(JNIEnv*, jobject, jlong groupPtr, jfloat translateY) {
    VectorDrawable::Group* group = reinterpret_cast<VectorDrawable::Group*>(groupPtr);
    group->mutateStagingProperties()->setTranslateY(translateY);
}

static void setPathData(JNIEnv*, jobject, jlong pathPtr, jlong pathDataPtr) {
    VectorDrawable::Path* path = reinterpret_cast<VectorDrawable::Path*>(pathPtr);
    PathData* pathData = reinterpret_cast<PathData*>(pathDataPtr);
    path->mutateStagingProperties()->setData(*pathData);
}

static jfloat getStrokeWidth(JNIEnv*, jobject, jlong fullPathPtr) {
    VectorDrawable::FullPath* fullPath = reinterpret_cast<VectorDrawable::FullPath*>(fullPathPtr);
    return fullPath->stagingProperties()->getStrokeWidth();
}

static void setStrokeWidth(JNIEnv*, jobject, jlong fullPathPtr, jfloat strokeWidth) {
    VectorDrawable::FullPath* fullPath = reinterpret_cast<VectorDrawable::FullPath*>(fullPathPtr);
    fullPath->mutateStagingProperties()->setStrokeWidth(strokeWidth);
}

static jint getStrokeColor(JNIEnv*, jobject, jlong fullPathPtr) {
    VectorDrawable::FullPath* fullPath = reinterpret_cast<VectorDrawable::FullPath*>(fullPathPtr);
    return fullPath->stagingProperties()->getStrokeColor();
}

static void setStrokeColor(JNIEnv*, jobject, jlong fullPathPtr, jint strokeColor) {
    VectorDrawable::FullPath* fullPath = reinterpret_cast<VectorDrawable::FullPath*>(fullPathPtr);
    fullPath->mutateStagingProperties()->setStrokeColor(strokeColor);
}

static jfloat getStrokeAlpha(JNIEnv*, jobject, jlong fullPathPtr) {
    VectorDrawable::FullPath* fullPath = reinterpret_cast<VectorDrawable::FullPath*>(fullPathPtr);
    return fullPath->stagingProperties()->getStrokeAlpha();
}

static void setStrokeAlpha(JNIEnv*, jobject, jlong fullPathPtr, jfloat strokeAlpha) {
    VectorDrawable::FullPath* fullPath = reinterpret_cast<VectorDrawable::FullPath*>(fullPathPtr);
    fullPath->mutateStagingProperties()->setStrokeAlpha(strokeAlpha);
}

static jint getFillColor(JNIEnv*, jobject, jlong fullPathPtr) {
    VectorDrawable::FullPath* fullPath = reinterpret_cast<VectorDrawable::FullPath*>(fullPathPtr);
    return fullPath->stagingProperties()->getFillColor();
}

static void setFillColor(JNIEnv*, jobject, jlong fullPathPtr, jint fillColor) {
    VectorDrawable::FullPath* fullPath = reinterpret_cast<VectorDrawable::FullPath*>(fullPathPtr);
    fullPath->mutateStagingProperties()->setFillColor(fillColor);
}

static jfloat getFillAlpha(JNIEnv*, jobject, jlong fullPathPtr) {
    VectorDrawable::FullPath* fullPath = reinterpret_cast<VectorDrawable::FullPath*>(fullPathPtr);
    return fullPath->stagingProperties()->getFillAlpha();
}

static void setFillAlpha(JNIEnv*, jobject, jlong fullPathPtr, jfloat fillAlpha) {
    VectorDrawable::FullPath* fullPath = reinterpret_cast<VectorDrawable::FullPath*>(fullPathPtr);
    fullPath->mutateStagingProperties()->setFillAlpha(fillAlpha);
}

static jfloat getTrimPathStart(JNIEnv*, jobject, jlong fullPathPtr) {
    VectorDrawable::FullPath* fullPath = reinterpret_cast<VectorDrawable::FullPath*>(fullPathPtr);
    return fullPath->stagingProperties()->getTrimPathStart();
}

static void setTrimPathStart(JNIEnv*, jobject, jlong fullPathPtr, jfloat trimPathStart) {
    VectorDrawable::FullPath* fullPath = reinterpret_cast<VectorDrawable::FullPath*>(fullPathPtr);
    fullPath->mutateStagingProperties()->setTrimPathStart(trimPathStart);
}

static jfloat getTrimPathEnd(JNIEnv*, jobject, jlong fullPathPtr) {
    VectorDrawable::FullPath* fullPath = reinterpret_cast<VectorDrawable::FullPath*>(fullPathPtr);
    return fullPath->stagingProperties()->getTrimPathEnd();
}

static void setTrimPathEnd(JNIEnv*, jobject, jlong fullPathPtr, jfloat trimPathEnd) {
    VectorDrawable::FullPath* fullPath = reinterpret_cast<VectorDrawable::FullPath*>(fullPathPtr);
    fullPath->mutateStagingProperties()->setTrimPathEnd(trimPathEnd);
}

static jfloat getTrimPathOffset(JNIEnv*, jobject, jlong fullPathPtr) {
    VectorDrawable::FullPath* fullPath = reinterpret_cast<VectorDrawable::FullPath*>(fullPathPtr);
    return fullPath->stagingProperties()->getTrimPathOffset();
}

static void setTrimPathOffset(JNIEnv*, jobject, jlong fullPathPtr, jfloat trimPathOffset) {
    VectorDrawable::FullPath* fullPath = reinterpret_cast<VectorDrawable::FullPath*>(fullPathPtr);
    fullPath->mutateStagingProperties()->setTrimPathOffset(trimPathOffset);
}

static const JNINativeMethod gMethods[] = {
        {"nCreateTree", "!(J)J", (void*)createTree},
        {"nCreateTreeFromCopy", "!(JJ)J", (void*)createTreeFromCopy},
        {"nSetRendererViewportSize", "!(JFF)V", (void*)setTreeViewportSize},
        {"nSetRootAlpha", "!(JF)Z", (void*)setRootAlpha},
        {"nGetRootAlpha", "!(J)F", (void*)getRootAlpha},
        {"nSetAllowCaching", "!(JZ)V", (void*)setAllowCaching},

        {"nDraw", "(JJJLandroid/graphics/Rect;ZZ)I", (void*)draw},
        {"nCreateFullPath", "!()J", (void*)createEmptyFullPath},
        {"nCreateFullPath", "!(J)J", (void*)createFullPath},
        {"nUpdateFullPathProperties", "!(JFIFIFFFFFIII)V", (void*)updateFullPathPropertiesAndStrokeStyles},
        {"nUpdateFullPathFillGradient", "!(JJ)V", (void*)updateFullPathFillGradient},
        {"nUpdateFullPathStrokeGradient", "!(JJ)V", (void*)updateFullPathStrokeGradient},
        {"nGetFullPathProperties", "(J[BI)Z", (void*)getFullPathProperties},
        {"nGetGroupProperties", "(J[FI)Z", (void*)getGroupProperties},

        {"nCreateClipPath", "!()J", (void*)createEmptyClipPath},
        {"nCreateClipPath", "!(J)J", (void*)createClipPath},
        {"nCreateGroup", "!()J", (void*)createEmptyGroup},
        {"nCreateGroup", "!(J)J", (void*)createGroup},
        {"nSetName", "(JLjava/lang/String;)V", (void*)setNodeName},
        {"nUpdateGroupProperties", "!(JFFFFFFF)V", (void*)updateGroupProperties},

        {"nAddChild", "!(JJ)V", (void*)addChild},
        {"nSetPathString", "(JLjava/lang/String;I)V", (void*)setPathString},

        {"nGetRotation", "!(J)F", (void*)getRotation},
        {"nSetRotation", "!(JF)V", (void*)setRotation},
        {"nGetPivotX", "!(J)F", (void*)getPivotX},
        {"nSetPivotX", "!(JF)V", (void*)setPivotX},
        {"nGetPivotY", "!(J)F", (void*)getPivotY},
        {"nSetPivotY", "!(JF)V", (void*)setPivotY},
        {"nGetScaleX", "!(J)F", (void*)getScaleX},
        {"nSetScaleX", "!(JF)V", (void*)setScaleX},
        {"nGetScaleY", "!(J)F", (void*)getScaleY},
        {"nSetScaleY", "!(JF)V", (void*)setScaleY},
        {"nGetTranslateX", "!(J)F", (void*)getTranslateX},
        {"nSetTranslateX", "!(JF)V", (void*)setTranslateX},
        {"nGetTranslateY", "!(J)F", (void*)getTranslateY},
        {"nSetTranslateY", "!(JF)V", (void*)setTranslateY},

        {"nSetPathData", "!(JJ)V", (void*)setPathData},
        {"nGetStrokeWidth", "!(J)F", (void*)getStrokeWidth},
        {"nSetStrokeWidth", "!(JF)V", (void*)setStrokeWidth},
        {"nGetStrokeColor", "!(J)I", (void*)getStrokeColor},
        {"nSetStrokeColor", "!(JI)V", (void*)setStrokeColor},
        {"nGetStrokeAlpha", "!(J)F", (void*)getStrokeAlpha},
        {"nSetStrokeAlpha", "!(JF)V", (void*)setStrokeAlpha},
        {"nGetFillColor", "!(J)I", (void*)getFillColor},
        {"nSetFillColor", "!(JI)V", (void*)setFillColor},
        {"nGetFillAlpha", "!(J)F", (void*)getFillAlpha},
        {"nSetFillAlpha", "!(JF)V", (void*)setFillAlpha},
        {"nGetTrimPathStart", "!(J)F", (void*)getTrimPathStart},
        {"nSetTrimPathStart", "!(JF)V", (void*)setTrimPathStart},
        {"nGetTrimPathEnd", "!(J)F", (void*)getTrimPathEnd},
        {"nSetTrimPathEnd", "!(JF)V", (void*)setTrimPathEnd},
        {"nGetTrimPathOffset", "!(J)F", (void*)getTrimPathOffset},
        {"nSetTrimPathOffset", "!(JF)V", (void*)setTrimPathOffset},
};

int register_android_graphics_drawable_VectorDrawable(JNIEnv* env) {
    return RegisterMethodsOrDie(env, "android/graphics/drawable/VectorDrawable", gMethods, NELEM(gMethods));
}

}; // namespace android
