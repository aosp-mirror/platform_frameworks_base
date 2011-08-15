/*
 * Copyright (C) 2012 The Android Open Source Project
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

#ifndef ANDROID_LIBCOMMONCLOCK_UTILS_H
#define ANDROID_LIBCOMMONCLOCK_UTILS_H

#include <linux/socket.h>

#include <binder/Parcel.h>
#include <utils/Errors.h>

namespace android {

extern bool canSerializeSockaddr(const struct sockaddr_storage* addr);
extern void serializeSockaddr(Parcel* p, const struct sockaddr_storage* addr);
extern status_t deserializeSockaddr(const Parcel* p,
                                    struct sockaddr_storage* addr);

};  // namespace android

#endif  // ANDROID_LIBCOMMONCLOCK_UTILS_H
