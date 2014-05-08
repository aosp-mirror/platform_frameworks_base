/*
 * Copyright (C) 2014 The Android Open Source Project
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
package android.hardware.hdmi;

/**
 * HdmiTvClient represents HDMI-CEC logical device of type TV in the Android system
 * which acts as TV/Display. It provides with methods that manage, interact with other
 * devices on the CEC bus.
 */
public final class HdmiTvClient {
    private static final String TAG = "HdmiTvClient";

    private final IHdmiControlService mService;

    HdmiTvClient(IHdmiControlService service) {
        mService = service;
    }
}
