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

#ifndef DIRECTORY_CLEANER_H
#define DIRECTORY_CLEANER_H

#include <sys/types.h>
#include <utils/Errors.h>

namespace android {
namespace os {
namespace incidentd {

android::status_t create_directory(const char* directory);
void clean_directory(const char* directory, off_t maxSize, size_t maxCount);

}  // namespace incidentd
}  // namespace os
}  // namespace android

#endif  // DIRECTORY_CLEANER_H
