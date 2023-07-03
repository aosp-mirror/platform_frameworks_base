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

package android.companion.virtual;

import android.content.Intent;

/**
 * Interceptor interface to be called when an intent matches the IntentFilter passed into {@link
 * VirtualDevice#registerIntentInterceptor}. When the interceptor is called after matching the
 * IntentFilter, the intended activity launch will be aborted and alternatively replaced by
 * the interceptor's receiver.
 *
 * @hide
 */
oneway interface IVirtualDeviceIntentInterceptor {

    /**
     * Called when an intent that matches the IntentFilter registered in {@link
     * VirtualDevice#registerIntentInterceptor} is intercepted for the virtual device to
     * handle.
     *
     * @param intent The intent that has been intercepted by the interceptor.
     */
    void onIntentIntercepted(in Intent intent);
}
