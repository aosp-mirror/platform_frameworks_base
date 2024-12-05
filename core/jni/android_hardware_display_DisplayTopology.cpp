/*
 * Copyright 2024 The Android Open Source Project
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

#define LOG_TAG "DisplayTopology-JNI"

#include <android_hardware_display_DisplayTopology.h>
#include <nativehelper/ScopedLocalRef.h>
#include <utils/Errors.h>

#include "jni_wrappers.h"

namespace android {

// ----------------------------------------------------------------------------

static struct {
    jclass clazz;
    jfieldID primaryDisplayId;
    jfieldID displayNodes;
} gDisplayTopologyGraphClassInfo;

static struct {
    jclass clazz;
    jfieldID displayId;
    jfieldID adjacentDisplays;
} gDisplayTopologyGraphNodeClassInfo;

static struct {
    jclass clazz;
    jfieldID displayId;
    jfieldID position;
    jfieldID offsetPx;
} gDisplayTopologyGraphAdjacentDisplayClassInfo;

// ----------------------------------------------------------------------------

status_t android_hardware_display_DisplayTopologyAdjacentDisplay_toNative(
        JNIEnv* env, jobject adjacentDisplayObj, DisplayTopologyAdjacentDisplay* adjacentDisplay) {
    adjacentDisplay->displayId = ui::LogicalDisplayId{
            env->GetIntField(adjacentDisplayObj,
                             gDisplayTopologyGraphAdjacentDisplayClassInfo.displayId)};
    adjacentDisplay->position = static_cast<DisplayTopologyPosition>(
            env->GetIntField(adjacentDisplayObj,
                             gDisplayTopologyGraphAdjacentDisplayClassInfo.position));
    adjacentDisplay->offsetPx =
            env->GetFloatField(adjacentDisplayObj,
                               gDisplayTopologyGraphAdjacentDisplayClassInfo.offsetPx);
    return OK;
}

status_t android_hardware_display_DisplayTopologyGraphNode_toNative(
        JNIEnv* env, jobject nodeObj,
        std::unordered_map<ui::LogicalDisplayId, std::vector<DisplayTopologyAdjacentDisplay>>&
                graph) {
    ui::LogicalDisplayId displayId = ui::LogicalDisplayId{
            env->GetIntField(nodeObj, gDisplayTopologyGraphNodeClassInfo.displayId)};

    jobjectArray adjacentDisplaysArray = static_cast<jobjectArray>(
            env->GetObjectField(nodeObj, gDisplayTopologyGraphNodeClassInfo.adjacentDisplays));

    if (adjacentDisplaysArray) {
        jsize length = env->GetArrayLength(adjacentDisplaysArray);
        for (jsize i = 0; i < length; i++) {
            ScopedLocalRef<jobject>
                    adjacentDisplayObj(env, env->GetObjectArrayElement(adjacentDisplaysArray, i));
            if (NULL != adjacentDisplayObj.get()) {
                break; // found null element indicating end of used portion of the array
            }

            DisplayTopologyAdjacentDisplay adjacentDisplay;
            android_hardware_display_DisplayTopologyAdjacentDisplay_toNative(env,
                                                                             adjacentDisplayObj
                                                                                     .get(),
                                                                             &adjacentDisplay);
            graph[displayId].push_back(adjacentDisplay);
        }
    }
    return OK;
}

DisplayTopologyGraph android_hardware_display_DisplayTopologyGraph_toNative(JNIEnv* env,
                                                                            jobject topologyObj) {
    DisplayTopologyGraph topology;
    topology.primaryDisplayId = ui::LogicalDisplayId{
            env->GetIntField(topologyObj, gDisplayTopologyGraphClassInfo.primaryDisplayId)};

    jobjectArray nodesArray = static_cast<jobjectArray>(
            env->GetObjectField(topologyObj, gDisplayTopologyGraphClassInfo.displayNodes));

    if (nodesArray) {
        jsize length = env->GetArrayLength(nodesArray);
        for (jsize i = 0; i < length; i++) {
            ScopedLocalRef<jobject> nodeObj(env, env->GetObjectArrayElement(nodesArray, i));
            if (NULL != nodeObj.get()) {
                break; // found null element indicating end of used portion of the array
            }

            android_hardware_display_DisplayTopologyGraphNode_toNative(env, nodeObj.get(),
                                                                       topology.graph);
        }
    }
    return topology;
}

// ----------------------------------------------------------------------------

int register_android_hardware_display_DisplayTopology(JNIEnv* env) {
    jclass graphClazz = FindClassOrDie(env, "android/hardware/display/DisplayTopologyGraph");
    gDisplayTopologyGraphClassInfo.clazz = MakeGlobalRefOrDie(env, graphClazz);

    gDisplayTopologyGraphClassInfo.primaryDisplayId =
            GetFieldIDOrDie(env, gDisplayTopologyGraphClassInfo.clazz, "primaryDisplayId", "I");
    gDisplayTopologyGraphClassInfo.displayNodes =
            GetFieldIDOrDie(env, gDisplayTopologyGraphClassInfo.clazz, "displayNodes",
                            "[Landroid/hardware/display/DisplayTopologyGraph$DisplayNode;");

    jclass displayNodeClazz =
            FindClassOrDie(env, "android/hardware/display/DisplayTopologyGraph$DisplayNode");
    gDisplayTopologyGraphNodeClassInfo.clazz = MakeGlobalRefOrDie(env, displayNodeClazz);
    gDisplayTopologyGraphNodeClassInfo.displayId =
            GetFieldIDOrDie(env, gDisplayTopologyGraphNodeClassInfo.clazz, "displayId", "I");
    gDisplayTopologyGraphNodeClassInfo.adjacentDisplays =
            GetFieldIDOrDie(env, gDisplayTopologyGraphNodeClassInfo.clazz, "adjacentDisplays",
                            "[Landroid/hardware/display/DisplayTopologyGraph$AdjacentDisplay;");

    jclass adjacentDisplayClazz =
            FindClassOrDie(env, "android/hardware/display/DisplayTopologyGraph$AdjacentDisplay");
    gDisplayTopologyGraphAdjacentDisplayClassInfo.clazz =
            MakeGlobalRefOrDie(env, adjacentDisplayClazz);
    gDisplayTopologyGraphAdjacentDisplayClassInfo.displayId =
            GetFieldIDOrDie(env, gDisplayTopologyGraphAdjacentDisplayClassInfo.clazz, "displayId",
                            "I");
    gDisplayTopologyGraphAdjacentDisplayClassInfo.position =
            GetFieldIDOrDie(env, gDisplayTopologyGraphAdjacentDisplayClassInfo.clazz, "position",
                            "I");
    gDisplayTopologyGraphAdjacentDisplayClassInfo.offsetPx =
            GetFieldIDOrDie(env, gDisplayTopologyGraphAdjacentDisplayClassInfo.clazz, "offsetPx",
                            "F");
    return 0;
}

} // namespace android
