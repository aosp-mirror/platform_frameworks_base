/*
 * Copyright (C) 2019 The Android Open Source Project
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

#include <FrameInfo.h>
#include <FrameMetricsObserver.h>

namespace android {

/*
 * Implements JNI layer for hwui frame metrics reporting.
 */
class HardwareRendererObserver : public uirenderer::FrameMetricsObserver {
public:
    HardwareRendererObserver(JavaVM* vm, jobject observer, bool waitForPresentTime);
    ~HardwareRendererObserver();

    /**
     * Retrieves frame metrics for the oldest frame that the renderer has retained. The renderer
     * will retain a buffer until it has been retrieved, via this method, or its internal storage
     * is exhausted at which point it informs the caller of how many frames it has failed to store
     * since the last time this method was invoked.
     * @param env java env required to populate the provided buffer array
     * @param metrics output parameter that represents the buffer of metrics that is to be filled
     * @param dropCount output parameter that is updated to reflect the number of buffers that were
                        discarded since the last successful invocation of this method.
     * @return true if there was data to populate the array and false otherwise. If false then
     *         neither the metrics buffer or dropCount will be modified.
     */
    bool getNextBuffer(JNIEnv* env, jlongArray metrics, int* dropCount);

    void notify(const int64_t* stats) override;

private:
    static constexpr int kBufferSize = static_cast<int>(uirenderer::FrameInfoIndex::NumIndexes);
    static constexpr int kRingSize = 3;

    class FrameMetricsNotification {
    public:
        FrameMetricsNotification() {}

        std::atomic_bool hasData = false;
        int64_t buffer[kBufferSize];
        int dropCount = 0;
    private:
        // non-copyable
        FrameMetricsNotification(const FrameMetricsNotification&) = delete;
        FrameMetricsNotification& operator=(const FrameMetricsNotification& ) = delete;
    };

    JavaVM* const mVm;
    jweak mObserverWeak;

    int mNextFree = 0;
    int mNextInQueue = 0;
    FrameMetricsNotification mRingBuffer[kRingSize];

    int mDroppedReports = 0;
};

} // namespace android
