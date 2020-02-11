/*
 * Copyright 2020 The Android Open Source Project
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
package com.android.pacprocessor;

/**
 * Common interface for both Android's and WebView's implementation of PAC processor.
 *
 * @hide
 */
interface LibpacInterface {
    default boolean startPacSupport() {
        return true;
    }

    default boolean stopPacSupport() {
        return true;
    }

    boolean setCurrentProxyScript(String script);
    String makeProxyRequest(String url, String host);
}
