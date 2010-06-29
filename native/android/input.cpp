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

int32_t AInputEvent_getType(const AInputEvent* event) {
    return static_cast<const InputEvent*>(event)->getType();
}

int32_t AInputEvent_getDeviceId(const AInputEvent* event) {
    return static_cast<const InputEvent*>(event)->getDeviceId();
}

int32_t AInputEvent_getNature(const AInputEvent* event) {
    return static_cast<const InputEvent*>(event)->getNature();
}

int32_t AKeyEvent_getAction(const AInputEvent* key_event) {
    return static_cast<const KeyEvent*>(key_event)->getAction();
}

int32_t AKeyEvent_getFlags(const AInputEvent* key_event) {
    return static_cast<const KeyEvent*>(key_event)->getFlags();
}

int32_t AKeyEvent_getKeyCode(const AInputEvent* key_event) {
    return static_cast<const KeyEvent*>(key_event)->getKeyCode();
}

int32_t AKeyEvent_getScanCode(const AInputEvent* key_event) {
    return static_cast<const KeyEvent*>(key_event)->getScanCode();
}

int32_t AKeyEvent_getMetaState(const AInputEvent* key_event) {
    return static_cast<const KeyEvent*>(key_event)->getMetaState();
}
int32_t AKeyEvent_getRepeatCount(const AInputEvent* key_event) {
    return static_cast<const KeyEvent*>(key_event)->getRepeatCount();
}

int64_t AKeyEvent_getDownTime(const AInputEvent* key_event) {
    return static_cast<const KeyEvent*>(key_event)->getDownTime();
}

int64_t AKeyEvent_getEventTime(const AInputEvent* key_event) {
    return static_cast<const KeyEvent*>(key_event)->getEventTime();
}

int32_t AMotionEvent_getAction(const AInputEvent* motion_event) {
    return static_cast<const MotionEvent*>(motion_event)->getAction();
}

int32_t AMotionEvent_getMetaState(const AInputEvent* motion_event) {
    return static_cast<const MotionEvent*>(motion_event)->getMetaState();
}

int32_t AMotionEvent_getEdgeFlags(const AInputEvent* motion_event) {
    return reinterpret_cast<const MotionEvent*>(motion_event)->getEdgeFlags();
}

int64_t AMotionEvent_getDownTime(const AInputEvent* motion_event) {
    return static_cast<const MotionEvent*>(motion_event)->getDownTime();
}

int64_t AMotionEvent_getEventTime(const AInputEvent* motion_event) {
    return static_cast<const MotionEvent*>(motion_event)->getEventTime();
}

float AMotionEvent_getXOffset(const AInputEvent* motion_event) {
    return static_cast<const MotionEvent*>(motion_event)->getXOffset();
}

float AMotionEvent_getYOffset(const AInputEvent* motion_event) {
    return static_cast<const MotionEvent*>(motion_event)->getYOffset();
}

float AMotionEvent_getXPrecision(const AInputEvent* motion_event) {
    return static_cast<const MotionEvent*>(motion_event)->getXPrecision();
}

float AMotionEvent_getYPrecision(const AInputEvent* motion_event) {
    return static_cast<const MotionEvent*>(motion_event)->getYPrecision();
}

size_t AMotionEvent_getPointerCount(const AInputEvent* motion_event) {
    return static_cast<const MotionEvent*>(motion_event)->getPointerCount();
}

int32_t AMotionEvent_getPointerId(const AInputEvent* motion_event, size_t pointer_index) {
    return static_cast<const MotionEvent*>(motion_event)->getPointerId(pointer_index);
}

float AMotionEvent_getRawX(const AInputEvent* motion_event, size_t pointer_index) {
    return static_cast<const MotionEvent*>(motion_event)->getRawX(pointer_index);
}

float AMotionEvent_getRawY(const AInputEvent* motion_event, size_t pointer_index) {
    return static_cast<const MotionEvent*>(motion_event)->getRawY(pointer_index);
}

float AMotionEvent_getX(const AInputEvent* motion_event, size_t pointer_index) {
    return static_cast<const MotionEvent*>(motion_event)->getX(pointer_index);
}

float AMotionEvent_getY(const AInputEvent* motion_event, size_t pointer_index) {
    return static_cast<const MotionEvent*>(motion_event)->getY(pointer_index);
}

float AMotionEvent_getPressure(const AInputEvent* motion_event, size_t pointer_index) {
    return static_cast<const MotionEvent*>(motion_event)->getPressure(pointer_index);
}

float AMotionEvent_getSize(const AInputEvent* motion_event, size_t pointer_index) {
    return static_cast<const MotionEvent*>(motion_event)->getSize(pointer_index);
}

size_t AMotionEvent_getHistorySize(const AInputEvent* motion_event) {
    return static_cast<const MotionEvent*>(motion_event)->getHistorySize();
}

int64_t AMotionEvent_getHistoricalEventTime(AInputEvent* motion_event,
        size_t history_index) {
    return static_cast<const MotionEvent*>(motion_event)->getHistoricalEventTime(
            history_index);
}

float AMotionEvent_getHistoricalRawX(AInputEvent* motion_event, size_t pointer_index,
        size_t history_index) {
    return static_cast<const MotionEvent*>(motion_event)->getHistoricalRawX(
            pointer_index, history_index);
}

float AMotionEvent_getHistoricalRawY(AInputEvent* motion_event, size_t pointer_index,
        size_t history_index) {
    return static_cast<const MotionEvent*>(motion_event)->getHistoricalRawY(
            pointer_index, history_index);
}

float AMotionEvent_getHistoricalX(AInputEvent* motion_event, size_t pointer_index,
        size_t history_index) {
    return static_cast<const MotionEvent*>(motion_event)->getHistoricalX(
            pointer_index, history_index);
}

float AMotionEvent_getHistoricalY(AInputEvent* motion_event, size_t pointer_index,
        size_t history_index) {
    return static_cast<const MotionEvent*>(motion_event)->getHistoricalY(
            pointer_index, history_index);
}

float AMotionEvent_getHistoricalPressure(AInputEvent* motion_event, size_t pointer_index,
        size_t history_index) {
    return static_cast<const MotionEvent*>(motion_event)->getHistoricalPressure(
            pointer_index, history_index);
}

float AMotionEvent_getHistoricalSize(AInputEvent* motion_event, size_t pointer_index,
        size_t history_index) {
    return static_cast<const MotionEvent*>(motion_event)->getHistoricalSize(
            pointer_index, history_index);
}

int AInputQueue_getFd(AInputQueue* queue) {
    return queue->getConsumer().getChannel()->getReceivePipeFd();
}

int AInputQueue_hasEvents(AInputQueue* queue) {
    struct pollfd pfd;
    
    pfd.fd = queue->getConsumer().getChannel()->getReceivePipeFd();
    pfd.events = POLLIN;
    pfd.revents = 0;
    
    int nfd = poll(&pfd, 1, 0);
    if (nfd <= 0) return nfd;
    return pfd.revents == POLLIN ? 1 : -1;
}

int32_t AInputQueue_getEvent(AInputQueue* queue, AInputEvent** outEvent) {
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

void AInputQueue_finishEvent(AInputQueue* queue, AInputEvent* event,
        int handled) {
    int32_t res = queue->getConsumer().sendFinishedSignal();
    if (res != android::OK) {
        LOGW("Failed to send finished signal on channel '%s'.  status=%d",
                queue->getConsumer().getChannel()->getName().string(), res);
    }
}
