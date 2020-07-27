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

package com.android.server.wm;

import android.os.IBinder;

/**
 * Callback to be called when a background activity start is allowed exclusively because of the
 * token provided in {@link #getToken()}.
 */
public interface BackgroundActivityStartCallback {
    /**
     * The token that allowed the activity start that triggered {@link
     * #onExclusiveTokenActivityStart()}.
     *
     * Ideally this should just return a final variable, don't do anything costly here (don't hold
     * any locks).
     */
    IBinder getToken();

    /**
     * Called when the background activity start happens.
     */
    void onExclusiveTokenActivityStart(String packageName);
}
