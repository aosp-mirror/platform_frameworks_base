/*
 * Copyright (C) 2016 The Android Open Source Project
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
#pragma once

#include <cutils/log.h>
#include <pthread.h>
#include <ostream>

namespace android {
namespace uirenderer {

extern pthread_t gGpuThread;

#define ASSERT_GPU_THREAD() LOG_ALWAYS_FATAL_IF( \
        !pthread_equal(gGpuThread, pthread_self()), \
        "Error, %p of type %d (size=%d) used on wrong thread! cur thread %lu " \
        "!= gpu thread %lu", this, static_cast<int>(mType), mSize, \
        pthread_self(), gGpuThread)

enum class GpuObjectType {
    Texture = 0,
    OffscreenBuffer,
    Layer,

    TypeCount,
};

class GpuMemoryTracker {
public:
    GpuObjectType objectType() { return mType; }
    int objectSize() { return mSize; }

    static void onGLContextCreated();
    static void onGLContextDestroyed();
    static void dump();
    static void dump(std::ostream& stream);
    static int getInstanceCount(GpuObjectType type);
    static int getTotalSize(GpuObjectType type);
    static void onFrameCompleted();

protected:
    GpuMemoryTracker(GpuObjectType type) : mType(type) {
        ASSERT_GPU_THREAD();
        startTrackingObject();
    }

    ~GpuMemoryTracker() {
        notifySizeChanged(0);
        stopTrackingObject();
    }

    void notifySizeChanged(int newSize);

private:
    void startTrackingObject();
    void stopTrackingObject();

    int mSize = 0;
    GpuObjectType mType;
};

} // namespace uirenderer
} // namespace android;
