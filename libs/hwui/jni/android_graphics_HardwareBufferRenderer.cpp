/*
 * Copyright (C) 2010 The Android Open Source Project
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

#define ATRACE_TAG ATRACE_TAG_VIEW

#include <GraphicsJNI.h>
#include <RootRenderNode.h>
#include <TreeInfo.h>
#include <android-base/unique_fd.h>
#include <android/native_window.h>
#include <nativehelper/JNIPlatformHelp.h>
#include <renderthread/CanvasContext.h>
#include <renderthread/RenderProxy.h>
#include <renderthread/RenderThread.h>

#include "HardwareBufferHelpers.h"
#include "JvmErrorReporter.h"

namespace android {

using namespace android::uirenderer;
using namespace android::uirenderer::renderthread;

struct {
    jclass clazz;
    jmethodID invokeRenderCallback;
} gHardwareBufferRendererClassInfo;

static RenderCallback createRenderCallback(JNIEnv* env, jobject releaseCallback) {
    if (releaseCallback == nullptr) return nullptr;

    JavaVM* vm = nullptr;
    LOG_ALWAYS_FATAL_IF(env->GetJavaVM(&vm) != JNI_OK, "Unable to get Java VM");
    auto globalCallbackRef =
            std::make_shared<JGlobalRefHolder>(vm, env->NewGlobalRef(releaseCallback));
    return [globalCallbackRef](android::base::unique_fd&& fd, int status) {
        globalCallbackRef->env()->CallStaticVoidMethod(
                gHardwareBufferRendererClassInfo.clazz,
                gHardwareBufferRendererClassInfo.invokeRenderCallback, globalCallbackRef->object(),
                reinterpret_cast<jint>(fd.release()), reinterpret_cast<jint>(status));
    };
}

static long android_graphics_HardwareBufferRenderer_createRootNode(JNIEnv* env, jobject) {
    auto* node = new RootRenderNode(std::make_unique<JvmErrorReporter>(env));
    node->incStrong(nullptr);
    node->setName("RootRenderNode");
    return reinterpret_cast<jlong>(node);
}

static void android_graphics_hardwareBufferRenderer_destroyRootNode(JNIEnv*, jobject,
                                                                    jlong renderNodePtr) {
    auto* node = reinterpret_cast<RootRenderNode*>(renderNodePtr);
    node->destroy();
}

static long android_graphics_HardwareBufferRenderer_create(JNIEnv* env, jobject, jobject buffer,
                                                           jlong renderNodePtr) {
    auto* hardwareBuffer = HardwareBufferHelpers::AHardwareBuffer_fromHardwareBuffer(env, buffer);
    auto* rootRenderNode = reinterpret_cast<RootRenderNode*>(renderNodePtr);
    ContextFactoryImpl factory(rootRenderNode);
    auto* proxy = new RenderProxy(false, rootRenderNode, &factory);
    proxy->setHardwareBuffer(hardwareBuffer);
    return (jlong)proxy;
}

static void HardwareBufferRenderer_destroy(jlong renderProxy) {
    auto* proxy = reinterpret_cast<RenderProxy*>(renderProxy);
    delete proxy;
}

static SkMatrix createMatrixFromBufferTransform(SkScalar width, SkScalar height, int transform) {
    switch (transform) {
        case ANATIVEWINDOW_TRANSFORM_ROTATE_90:
            return SkMatrix::MakeAll(0, -1, height, 1, 0, 0, 0, 0, 1);
        case ANATIVEWINDOW_TRANSFORM_ROTATE_180:
            return SkMatrix::MakeAll(-1, 0, width, 0, -1, height, 0, 0, 1);
        case ANATIVEWINDOW_TRANSFORM_ROTATE_270:
            return SkMatrix::MakeAll(0, 1, 0, -1, 0, width, 0, 0, 1);
        default:
            ALOGE("Invalid transform provided. Transform should be validated from"
                  "the java side. Leveraging identity transform as a fallback");
            [[fallthrough]];
        case ANATIVEWINDOW_TRANSFORM_IDENTITY:
            return SkMatrix::I();
    }
}

static int android_graphics_HardwareBufferRenderer_render(JNIEnv* env, jobject, jlong renderProxy,
                                                          jint transform, jint width, jint height,
                                                          jlong colorspacePtr, jobject consumer) {
    auto* proxy = reinterpret_cast<RenderProxy*>(renderProxy);
    auto skWidth = static_cast<SkScalar>(width);
    auto skHeight = static_cast<SkScalar>(height);
    auto matrix = createMatrixFromBufferTransform(skWidth, skHeight, transform);
    auto colorSpace = GraphicsJNI::getNativeColorSpace(colorspacePtr);
    proxy->setHardwareBufferRenderParams(HardwareBufferRenderParams(
            width, height, matrix, colorSpace, createRenderCallback(env, consumer)));
    nsecs_t vsync = systemTime(SYSTEM_TIME_MONOTONIC);
    UiFrameInfoBuilder(proxy->frameInfo())
                .setVsync(vsync, vsync, UiFrameInfoBuilder::INVALID_VSYNC_ID,
                    UiFrameInfoBuilder::UNKNOWN_DEADLINE,
                    UiFrameInfoBuilder::UNKNOWN_FRAME_INTERVAL)
                .addFlag(FrameInfoFlags::SurfaceCanvas);
    return proxy->syncAndDrawFrame();
}

static void android_graphics_HardwareBufferRenderer_setLightGeometry(JNIEnv*, jobject,
                                                                     jlong renderProxyPtr,
                                                                     jfloat lightX, jfloat lightY,
                                                                     jfloat lightZ,
                                                                     jfloat lightRadius) {
    auto* proxy = reinterpret_cast<RenderProxy*>(renderProxyPtr);
    proxy->setLightGeometry((Vector3){lightX, lightY, lightZ}, lightRadius);
}

static void android_graphics_HardwareBufferRenderer_setLightAlpha(JNIEnv* env, jobject,
                                                                  jlong renderProxyPtr,
                                                                  jfloat ambientShadowAlpha,
                                                                  jfloat spotShadowAlpha) {
    auto* proxy = reinterpret_cast<RenderProxy*>(renderProxyPtr);
    proxy->setLightAlpha((uint8_t)(255 * ambientShadowAlpha), (uint8_t)(255 * spotShadowAlpha));
}

static jlong android_graphics_HardwareBufferRenderer_getFinalizer(CRITICAL_JNI_PARAMS) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(&HardwareBufferRenderer_destroy));
}

// ----------------------------------------------------------------------------
// JNI Glue
// ----------------------------------------------------------------------------

const char* const kClassPathName = "android/graphics/HardwareBufferRenderer";

static const JNINativeMethod gMethods[] = {
        {"nCreateHardwareBufferRenderer", "(Landroid/hardware/HardwareBuffer;J)J",
         (void*)android_graphics_HardwareBufferRenderer_create},
        {"nRender", "(JIIIJLjava/util/function/Consumer;)I",
         (void*)android_graphics_HardwareBufferRenderer_render},
        {"nCreateRootRenderNode", "()J",
         (void*)android_graphics_HardwareBufferRenderer_createRootNode},
        {"nSetLightGeometry", "(JFFFF)V",
         (void*)android_graphics_HardwareBufferRenderer_setLightGeometry},
        {"nSetLightAlpha", "(JFF)V", (void*)android_graphics_HardwareBufferRenderer_setLightAlpha},
        {"nGetFinalizer", "()J", (void*)android_graphics_HardwareBufferRenderer_getFinalizer},
        {"nDestroyRootRenderNode", "(J)V",
         (void*)android_graphics_hardwareBufferRenderer_destroyRootNode}};

int register_android_graphics_HardwareBufferRenderer(JNIEnv* env) {
    jclass hardwareBufferRendererClazz =
            FindClassOrDie(env, "android/graphics/HardwareBufferRenderer");
    gHardwareBufferRendererClassInfo.clazz =
            reinterpret_cast<jclass>(env->NewGlobalRef(hardwareBufferRendererClazz));
    gHardwareBufferRendererClassInfo.invokeRenderCallback =
            GetStaticMethodIDOrDie(env, hardwareBufferRendererClazz, "invokeRenderCallback",
                                   "(Ljava/util/function/Consumer;II)V");
    HardwareBufferHelpers::init();
    return RegisterMethodsOrDie(env, kClassPathName, gMethods, NELEM(gMethods));
}

}  // namespace android