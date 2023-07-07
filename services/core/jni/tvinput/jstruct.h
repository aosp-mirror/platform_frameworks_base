/*
 * Copyright 2022 The Android Open Source Project
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

#include "jni.h"

typedef struct {
    jmethodID deviceAvailable;
    jmethodID deviceUnavailable;
    jmethodID streamConfigsChanged;
    jmethodID firstFrameCaptured;
    jmethodID tvMessageReceived;
} gTvInputHalClassInfoType;

typedef struct {
    jclass clazz;
} gTvStreamConfigClassInfoType;

typedef struct {
    jclass clazz;
    jmethodID constructor;
    jmethodID putByteArray;
    jmethodID putString;
    jmethodID putInt;
} gBundleClassInfoType;

typedef struct {
    jclass clazz;

    jmethodID constructor;
    jmethodID streamId;
    jmethodID type;
    jmethodID maxWidth;
    jmethodID maxHeight;
    jmethodID generation;
    jmethodID build;
} gTvStreamConfigBuilderClassInfoType;

typedef struct {
    jclass clazz;

    jmethodID constructor;
    jmethodID deviceId;
    jmethodID type;
    jmethodID hdmiPortId;
    jmethodID cableConnectionStatus;
    jmethodID audioType;
    jmethodID audioAddress;
    jmethodID build;
} gTvInputHardwareInfoBuilderClassInfoType;
