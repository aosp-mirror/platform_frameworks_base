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

package android.media.tv;

/**
 * {@hide}
 */
oneway interface ITvRemoteServiceInput {
    // InputBridge related
    @UnsupportedAppUsage
    void openInputBridge(IBinder token, String name, int width, int height, int maxPointers);
    @UnsupportedAppUsage
    void closeInputBridge(IBinder token);
    @UnsupportedAppUsage
    void clearInputBridge(IBinder token);
    @UnsupportedAppUsage
    void sendTimestamp(IBinder token, long timestamp);
    @UnsupportedAppUsage
    void sendKeyDown(IBinder token, int keyCode);
    @UnsupportedAppUsage
    void sendKeyUp(IBinder token, int keyCode);
    @UnsupportedAppUsage
    void sendPointerDown(IBinder token, int pointerId, int x, int y);
    @UnsupportedAppUsage
    void sendPointerUp(IBinder token, int pointerId);
    @UnsupportedAppUsage
    void sendPointerSync(IBinder token);

    // API specific to gamepads. Close gamepads with closeInputBridge
    void openGamepadBridge(IBinder token, String name);
    void sendGamepadKeyDown(IBinder token, int keyCode);
    void sendGamepadKeyUp(IBinder token, int keyCode);
    void sendGamepadAxisValue(IBinder token, int axis, float value);
}
