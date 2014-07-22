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

package com.android.systemui.statusbar.policy;

import java.util.ArrayList;

public final class KeyguardMonitor {

    private final ArrayList<Callback> mCallbacks = new ArrayList<Callback>();

    private boolean mShowing;
    private boolean mSecure;

    public void addCallback(Callback callback) {
        mCallbacks.add(callback);
    }

    public void removeCallback(Callback callback) {
        mCallbacks.remove(callback);
    }

    public boolean isShowing() {
        return mShowing;
    }

    public boolean isSecure() {
        return mSecure;
    }

    public void notifyKeyguardState(boolean showing, boolean secure) {
        if (mShowing == showing && mSecure == secure) return;
        mShowing = showing;
        mSecure = secure;
        for (Callback callback : mCallbacks) {
            callback.onKeyguardChanged();
        }
    }

    public interface Callback {
        void onKeyguardChanged();
    }
}