/*
 * Copyright (C) 2019, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#pragma once

#include <utils/RefBase.h>

#include "HashableDimensionKey.h"

namespace android {
namespace os {
namespace statsd {

class StateListener : public virtual RefBase {
public:
    StateListener(){};

    virtual ~StateListener(){};

    /**
     * Interface for handling a state change.
     *
     * The old and new state values map to the original state values.
     * StateTrackers only track the original state values and are unaware
     * of higher-level state groups. MetricProducers hold information on
     * state groups and are responsible for mapping original state values to
     * the correct state group.
     *
     * [eventTimeNs]: Time of the state change log event.
     * [atomId]: The id of the state atom
     * [primaryKey]: The primary field values of the state atom
     * [oldState]: Previous state value before state change
     * [newState]: Current state value after state change
     */
    virtual void onStateChanged(const int64_t eventTimeNs, const int32_t atomId,
                                const HashableDimensionKey& primaryKey, const FieldValue& oldState,
                                const FieldValue& newState) = 0;
};

}  // namespace statsd
}  // namespace os
}  // namespace android
