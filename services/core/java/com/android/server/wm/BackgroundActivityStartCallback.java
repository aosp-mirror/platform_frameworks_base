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

import java.util.Collection;

/**
 * Callback to decide activity starts and related operations based on originating tokens.
 */
public interface BackgroundActivityStartCallback {
    /**
     * Returns true if the background activity start originating from {@code tokens} should be
     * allowed or not.
     *
     * Note that if the start was allowed due to a mechanism other than tokens (eg. permission),
     * this won't be called.
     *
     * This will be called holding the WM and local lock, don't do anything costly or invoke AM/WM
     * methods here directly.
     */
    boolean isActivityStartAllowed(Collection<IBinder> tokens, int uid, String packageName);

    /**
     * Returns whether {@code uid} can send {@link android.content.Intent
     * #ACTION_CLOSE_SYSTEM_DIALOGS}, presumably to start activities, based on the originating
     * tokens {@code tokens} currently associated with potential activity starts.
     *
     * This will be called holding the AM and local lock, don't do anything costly or invoke AM/WM
     * methods here directly.
     */
    boolean canCloseSystemDialogs(Collection<IBinder> tokens, int uid);
}
