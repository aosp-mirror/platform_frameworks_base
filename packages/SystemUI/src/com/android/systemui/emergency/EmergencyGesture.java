/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.emergency;

/**
 * Constants for the Emergency gesture.
 *
 * TODO (b/169175022) Update classname and docs when feature name is locked
 */
public final class EmergencyGesture {

    /**
     * Launches the emergency flow.
     *
     * <p>The emergency flow is triggered by the Emergency gesture. By default the flow will call
     * local emergency services, though OEMs can customize the flow.
     *
     * <p>This action can only be triggered by System UI through the emergency gesture.
     *
     * <p>TODO (b/169175022) Update action name and docs when feature name is locked
     */
    public static final String ACTION_LAUNCH_EMERGENCY =
            "com.android.systemui.action.LAUNCH_EMERGENCY";

    private EmergencyGesture() {}
}
