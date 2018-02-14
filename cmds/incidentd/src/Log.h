/*
 * Copyright (C) 2017 The Android Open Source Project
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

/*
 * This file must be included at the top of the file. Other header files
 * occasionally include log.h, and if LOG_TAG isn't set when that happens
 * we'll get a preprocesser error when we try to define it here.
 */

#pragma once

#define LOG_TAG "incidentd"

#include <log/log.h>

// Use the local value to turn on/off debug logs instead of using log.tag.properties.
// The advantage is that in production compiler can remove the logging code if the local
// DEBUG/VERBOSE is false.
#define VLOG(...) \
    if (DEBUG) ALOGD(__VA_ARGS__);