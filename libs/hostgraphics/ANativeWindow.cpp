/*
 * Copyright (C) 2024 The Android Open Source Project
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

#include <system/window.h>

static int32_t query(ANativeWindow* window, int what) {
    int value;
    int res = window->query(window, what, &value);
    return res < 0 ? res : value;
}

static int64_t query64(ANativeWindow* window, int what) {
    int64_t value;
    int res = window->perform(window, what, &value);
    return res < 0 ? res : value;
}

int ANativeWindow_setCancelBufferInterceptor(ANativeWindow* window,
                                             ANativeWindow_cancelBufferInterceptor interceptor,
                                             void* data) {
    return window->perform(window, NATIVE_WINDOW_SET_CANCEL_INTERCEPTOR, interceptor, data);
}

int ANativeWindow_setDequeueBufferInterceptor(ANativeWindow* window,
                                              ANativeWindow_dequeueBufferInterceptor interceptor,
                                              void* data) {
    return window->perform(window, NATIVE_WINDOW_SET_DEQUEUE_INTERCEPTOR, interceptor, data);
}

int ANativeWindow_setQueueBufferInterceptor(ANativeWindow* window,
                                            ANativeWindow_queueBufferInterceptor interceptor,
                                            void* data) {
    return window->perform(window, NATIVE_WINDOW_SET_QUEUE_INTERCEPTOR, interceptor, data);
}

int ANativeWindow_setPerformInterceptor(ANativeWindow* window,
                                        ANativeWindow_performInterceptor interceptor, void* data) {
    return window->perform(window, NATIVE_WINDOW_SET_PERFORM_INTERCEPTOR, interceptor, data);
}

int ANativeWindow_dequeueBuffer(ANativeWindow* window, ANativeWindowBuffer** buffer, int* fenceFd) {
    return window->dequeueBuffer(window, buffer, fenceFd);
}

int ANativeWindow_cancelBuffer(ANativeWindow* window, ANativeWindowBuffer* buffer, int fenceFd) {
    return window->cancelBuffer(window, buffer, fenceFd);
}

int ANativeWindow_setDequeueTimeout(ANativeWindow* window, int64_t timeout) {
    return window->perform(window, NATIVE_WINDOW_SET_DEQUEUE_TIMEOUT, timeout);
}

// extern "C", so that it can be used outside libhostgraphics (in host hwui/.../CanvasContext.cpp)
extern "C" void ANativeWindow_tryAllocateBuffers(ANativeWindow* window) {
    if (!window || !query(window, NATIVE_WINDOW_IS_VALID)) {
        return;
    }
    window->perform(window, NATIVE_WINDOW_ALLOCATE_BUFFERS);
}

int64_t ANativeWindow_getLastDequeueStartTime(ANativeWindow* window) {
    return query64(window, NATIVE_WINDOW_GET_LAST_DEQUEUE_START);
}

int64_t ANativeWindow_getLastDequeueDuration(ANativeWindow* window) {
    return query64(window, NATIVE_WINDOW_GET_LAST_DEQUEUE_DURATION);
}

int64_t ANativeWindow_getLastQueueDuration(ANativeWindow* window) {
    return query64(window, NATIVE_WINDOW_GET_LAST_QUEUE_DURATION);
}

int32_t ANativeWindow_getWidth(ANativeWindow* window) {
    return query(window, NATIVE_WINDOW_WIDTH);
}

int32_t ANativeWindow_getHeight(ANativeWindow* window) {
    return query(window, NATIVE_WINDOW_HEIGHT);
}

int32_t ANativeWindow_getFormat(ANativeWindow* window) {
    return query(window, NATIVE_WINDOW_FORMAT);
}

void ANativeWindow_acquire(ANativeWindow* window) {
    // incStrong/decStrong token must be the same, doesn't matter what it is
    window->incStrong((void*)ANativeWindow_acquire);
}

void ANativeWindow_release(ANativeWindow* window) {
    // incStrong/decStrong token must be the same, doesn't matter what it is
    window->decStrong((void*)ANativeWindow_acquire);
}
