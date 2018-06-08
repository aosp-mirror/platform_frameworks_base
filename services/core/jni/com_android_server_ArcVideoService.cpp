/*
 * Copyright 2016, The Android Open Source Project
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

//#define LOG_NDEBUG 0
#define LOG_TAG "ArcVideoService"

#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>
#include <media/arcvideobridge/IArcVideoBridge.h>
#include <utils/Log.h>

#include <base/bind.h>
#include <base/bind_helpers.h>
#include <mojo/edk/embedder/embedder.h>
#include <mojo/public/cpp/bindings/binding.h>

#include <arc/ArcBridgeSupport.h>
#include <arc/ArcService.h>
#include <arc/Future.h>
#include <arc/IArcBridgeService.h>
#include <arc/MojoProcessSupport.h>
#include <components/arc/common/video.mojom.h>

namespace {

// [MinVersion] of OnVideoInstanceReady method in arc_bridge.mojom.
constexpr int kMinimumArcBridgeHostVersion = 6;

void onCaptureResult(arc::Future<arc::MojoBootstrapResult>* future, uint32_t version,
                     mojo::ScopedHandle handle, const std::string& token) {
    mojo::edk::ScopedPlatformHandle scoped_platform_handle;
    MojoResult result =
            mojo::edk::PassWrappedPlatformHandle(handle.release().value(), &scoped_platform_handle);
    if (result != MOJO_RESULT_OK) {
        ALOGE("Received invalid file descriptor.");
        future->set(arc::MojoBootstrapResult());
        return;
    }

    base::ScopedFD fd(scoped_platform_handle.release().handle);
    future->set(arc::MojoBootstrapResult(std::move(fd), token, version));
}

}  // namespace

namespace arc {

class VideoService : public mojom::VideoInstance,
                     public ArcService,
                     public android::BnArcVideoBridge {
public:
    explicit VideoService(MojoProcessSupport* mojoProcessSupport)
          : mMojoProcessSupport(mojoProcessSupport), mBinding(this) {
        mMojoProcessSupport->arc_bridge_support().requestArcBridgeProxyAsync(
                this, kMinimumArcBridgeHostVersion);
    }

    ~VideoService() override { mMojoProcessSupport->disconnect(&mBinding, &mHostPtr); }

    // VideoInstance overrides:
    void InitDeprecated(mojom::VideoHostPtr hostPtr) override {
        Init(std::move(hostPtr), base::Bind(&base::DoNothing));
    }

    void Init(mojom::VideoHostPtr hostPtr, const InitCallback& callback) override {
        ALOGV("Init");
        mHostPtr = std::move(hostPtr);
        // A method must be called while we are still in a Mojo thread so the
        // proxy can perform lazy initialization and be able to be called from
        // non-Mojo threads later.
        // This also caches the version number so it can be obtained by calling
        // .version().
        mHostPtr.QueryVersion(base::Bind(
            [](const InitCallback& callback, uint32_t version) {
                ALOGI("VideoService ready (version=%d)", version);
                callback.Run();
            },
            callback));
        ALOGV("Init done");
    }

    // ArcService overrides:
    void ready(mojom::ArcBridgeHostPtr* bridgeHost) override {
        (*bridgeHost)->OnVideoInstanceReady(mBinding.CreateInterfacePtrAndBind());
    }

    void versionMismatch(uint32_t version) override {
        ALOGE("ArcBridgeHost version %d, does not support video (version %d)\n", version,
              kMinimumArcBridgeHostVersion);
    }

    // BnArcVideoBridge overrides:
    MojoBootstrapResult bootstrapVideoAcceleratorFactory() override {
        ALOGV("VideoService::bootstrapVideoAcceleratorFactory");

        Future<MojoBootstrapResult> future;
        mMojoProcessSupport->mojo_thread().getTaskRunner()->PostTask(
                FROM_HERE, base::Bind(&VideoService::bootstrapVideoAcceleratorFactoryOnMojoThread,
                                      base::Unretained(this), &future));
        return future.get();
    }

    int32_t hostVersion() override {
        ALOGV("VideoService::hostVersion");
        return mHostPtr.version();
    }

private:
    void bootstrapVideoAcceleratorFactoryOnMojoThread(Future<MojoBootstrapResult>* future) {
        if (!mHostPtr) {
            ALOGE("mHostPtr is not ready yet");
            future->set(MojoBootstrapResult());
            return;
        }
        mHostPtr->OnBootstrapVideoAcceleratorFactory(
                base::Bind(&onCaptureResult, base::Unretained(future), mHostPtr.version()));
    }

    // Outlives VideoService.
    MojoProcessSupport* const mMojoProcessSupport;
    mojo::Binding<mojom::VideoInstance> mBinding;
    mojom::VideoHostPtr mHostPtr;
};

}  // namespace arc

namespace android {

int register_android_server_ArcVideoService() {
    defaultServiceManager()->addService(
            String16("android.os.IArcVideoBridge"),
            new arc::VideoService(arc::MojoProcessSupport::getLeakyInstance()));
    return 0;
}

}  // namespace android
