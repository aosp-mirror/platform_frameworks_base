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

#pragma once

#include <stats_event.h>
#include <stats_pull_atom_callback.h>

namespace android {
namespace server {
namespace stats {

/**
 * Reads hal for sleep states
 */
class SubsystemSleepStatePuller {
public:
    SubsystemSleepStatePuller();
    status_pull_atom_return_t Pull(int32_t atomTag, pulled_stats_event_list* data);
};

} // namespace stats
} // namespace server
} // namespace android
