/*
 * Copyright (C) 2009 The Android Open Source Project
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

#define LOG_TAG "input"
#include <utils/Log.h>

#include <android/input.h>
#include <ui/Input.h>
#include <ui/InputTransport.h>

#include <poll.h>

using android::InputEvent;
using android::KeyEvent;
using android::MotionEvent;

int32_t input_event_get_type(const input_event_t* event) {
    return static_cast<const InputEvent*>(event)->getType();
}

int32_t input_event_get_device_id(const input_event_t* event) {
    return static_cast<const InputEvent*>(event)->getDeviceId();
}

int32_t input_event_get_nature(const input_event_t* event) {
    return static_cast<const InputEvent*>(event)->getNature();
}

int32_t key_event_get_action(const input_event_t* key_event) {
    return static_cast<const KeyEvent*>(key_event)->getAction();
}

int32_t key_event_get_flags(const input_event_t* key_event) {
    return static_cast<const KeyEvent*>(key_event)->getFlags();
}

int32_t key_event_get_key_code(const input_event_t* key_event) {
    return static_cast<const KeyEvent*>(key_event)->getKeyCode();
}

int32_t key_event_get_scan_code(const input_event_t* key_event) {
    return static_cast<const KeyEvent*>(key_event)->getScanCode();
}

int32_t key_event_get_meta_state(const input_event_t* key_event) {
    return static_cast<const KeyEvent*>(key_event)->getMetaState();
}
int32_t key_event_get_repeat_count(const input_event_t* key_event) {
    return static_cast<const KeyEvent*>(key_event)->getRepeatCount();
}

int64_t key_event_get_down_time(const input_event_t* key_event) {
    return static_cast<const KeyEvent*>(key_event)->getDownTime();
}

int64_t key_event_get_event_time(const input_event_t* key_event) {
    return static_cast<const KeyEvent*>(key_event)->getEventTime();
}

int32_t motion_event_get_action(const input_event_t* motion_event) {
    return static_cast<const MotionEvent*>(motion_event)->getAction();
}

int32_t motion_event_get_meta_state(const input_event_t* motion_event) {
    return static_cast<const MotionEvent*>(motion_event)->getMetaState();
}

int32_t motion_event_get_edge_flags(const input_event_t* motion_event) {
    return reinterpret_cast<const MotionEvent*>(motion_event)->getEdgeFlags();
}

int64_t motion_event_get_down_time(const input_event_t* motion_event) {
    return static_cast<const MotionEvent*>(motion_event)->getDownTime();
}

int64_t motion_event_get_event_time(const input_event_t* motion_event) {
    return static_cast<const MotionEvent*>(motion_event)->getEventTime();
}

float motion_event_get_x_offset(const input_event_t* motion_event) {
    return static_cast<const MotionEvent*>(motion_event)->getXOffset();
}

float motion_event_get_y_offset(const input_event_t* motion_event) {
    return static_cast<const MotionEvent*>(motion_event)->getYOffset();
}

float motion_event_get_x_precision(const input_event_t* motion_event) {
    return static_cast<const MotionEvent*>(motion_event)->getXPrecision();
}

float motion_event_get_y_precision(const input_event_t* motion_event) {
    return static_cast<const MotionEvent*>(motion_event)->getYPrecision();
}

size_t motion_event_get_pointer_count(const input_event_t* motion_event) {
    return static_cast<const MotionEvent*>(motion_event)->getPointerCount();
}

int32_t motion_event_get_pointer_id(const input_event_t* motion_event, size_t pointer_index) {
    return static_cast<const MotionEvent*>(motion_event)->getPointerId(pointer_index);
}

float motion_event_get_raw_x(const input_event_t* motion_event, size_t pointer_index) {
    return static_cast<const MotionEvent*>(motion_event)->getRawX(pointer_index);
}

float motion_event_get_raw_y(const input_event_t* motion_event, size_t pointer_index) {
    return static_cast<const MotionEvent*>(motion_event)->getRawY(pointer_index);
}

float motion_event_get_x(const input_event_t* motion_event, size_t pointer_index) {
    return static_cast<const MotionEvent*>(motion_event)->getX(pointer_index);
}

float motion_event_get_y(const input_event_t* motion_event, size_t pointer_index) {
    return static_cast<const MotionEvent*>(motion_event)->getY(pointer_index);
}

float motion_event_get_pressure(const input_event_t* motion_event, size_t pointer_index) {
    return static_cast<const MotionEvent*>(motion_event)->getPressure(pointer_index);
}

float motion_event_get_size(const input_event_t* motion_event, size_t pointer_index) {
    return static_cast<const MotionEvent*>(motion_event)->getSize(pointer_index);
}

size_t motion_event_get_history_size(const input_event_t* motion_event) {
    return static_cast<const MotionEvent*>(motion_event)->getHistorySize();
}

int64_t motion_event_get_historical_event_time(input_event_t* motion_event,
        size_t history_index) {
    return static_cast<const MotionEvent*>(motion_event)->getHistoricalEventTime(
            history_index);
}

float motion_event_get_historical_raw_x(input_event_t* motion_event, size_t pointer_index,
        size_t history_index) {
    return static_cast<const MotionEvent*>(motion_event)->getHistoricalRawX(
            pointer_index, history_index);
}

float motion_event_get_historical_raw_y(input_event_t* motion_event, size_t pointer_index,
        size_t history_index) {
    return static_cast<const MotionEvent*>(motion_event)->getHistoricalRawY(
            pointer_index, history_index);
}

float motion_event_get_historical_x(input_event_t* motion_event, size_t pointer_index,
        size_t history_index) {
    return static_cast<const MotionEvent*>(motion_event)->getHistoricalX(
            pointer_index, history_index);
}

float motion_event_get_historical_y(input_event_t* motion_event, size_t pointer_index,
        size_t history_index) {
    return static_cast<const MotionEvent*>(motion_event)->getHistoricalY(
            pointer_index, history_index);
}

float motion_event_get_historical_pressure(input_event_t* motion_event, size_t pointer_index,
        size_t history_index) {
    return static_cast<const MotionEvent*>(motion_event)->getHistoricalPressure(
            pointer_index, history_index);
}

float motion_event_get_historical_size(input_event_t* motion_event, size_t pointer_index,
        size_t history_index) {
    return static_cast<const MotionEvent*>(motion_event)->getHistoricalSize(
            pointer_index, history_index);
}

int input_queue_get_fd(input_queue_t* queue) {
    return queue->getConsumer().getChannel()->getReceivePipeFd();
}

int input_queue_has_events(input_queue_t* queue) {
    struct pollfd pfd;
    
    pfd.fd = queue->getConsumer().getChannel()->getReceivePipeFd();
    pfd.events = POLLIN;
    pfd.revents = 0;
    
    int nfd = poll(&pfd, 1, 0);
    if (nfd <= 0) return nfd;
    return pfd.revents == POLLIN ? 1 : -1;
}

int32_t input_queue_get_event(input_queue_t* queue, input_event_t** outEvent) {
    *outEvent = NULL;
    
    int32_t res = queue->getConsumer().receiveDispatchSignal();
    if (res != android::OK) {
        LOGE("channel '%s' ~ Failed to receive dispatch signal.  status=%d",
                queue->getConsumer().getChannel()->getName().string(), res);
        return -1;
    }
    
    InputEvent* myEvent = NULL;
    res = queue->consume(&myEvent);
    if (res != android::OK) {
        LOGW("channel '%s' ~ Failed to consume input event.  status=%d",
                queue->getConsumer().getChannel()->getName().string(), res);
        queue->getConsumer().sendFinishedSignal();
        return -1;
    }
    
    *outEvent = myEvent;
    return 0;
}

void input_queue_finish_event(input_queue_t* queue, input_event_t* event,
        int handled) {
    int32_t res = queue->getConsumer().sendFinishedSignal();
    if (res != android::OK) {
        LOGW("Failed to send finished signal on channel '%s'.  status=%d",
                queue->getConsumer().getChannel()->getName().string(), res);
    }
}
