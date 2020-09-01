/*
 ** Copyright 2020, The Android Open Source Project
 **
 ** Licensed under the Apache License, Version 2.0 (the "License");
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at
 **
 **     http://www.apache.org/licenses/LICENSE-2.0
 **
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ** See the License for the specific language governing permissions and
 ** limitations under the License.
 */
package android.app;

import android.content.res.Configuration;
import android.view.IWindow;

/**
 * Callback to receive configuration changes from {@link com.android.server.WindowToken}.
 * WindowToken can be regarded to as a group of {@link android.view.IWindow} added from the same
 * visual context, such as {@link Activity} or one created with
 * {@link android.content.Context#createWindowContext(int)}. When WindowToken receives configuration
 * changes and/or when it is moved between displays, it will propagate the changes to client side
 * via this interface.
 * @see android.content.Context#createWindowContext(int)
 * {@hide}
 */
oneway interface IWindowToken {
    void onConfigurationChanged(in Configuration newConfig, int newDisplayId);

    void onWindowTokenRemoved();
}
