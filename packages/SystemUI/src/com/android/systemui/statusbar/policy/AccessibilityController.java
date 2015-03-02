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
 * limitations under the License
 */

package com.android.systemui.statusbar.policy;

import android.content.Context;
import android.view.accessibility.AccessibilityManager;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;

public class AccessibilityController implements
        AccessibilityManager.AccessibilityStateChangeListener,
        AccessibilityManager.TouchExplorationStateChangeListener {

    private final ArrayList<AccessibilityStateChangedCallback> mChangeCallbacks = new ArrayList<>();

    private boolean mAccessibilityEnabled;
    private boolean mTouchExplorationEnabled;

    public AccessibilityController(Context context) {
        AccessibilityManager am =
                (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        am.addTouchExplorationStateChangeListener(this);
        am.addAccessibilityStateChangeListener(this);
        mAccessibilityEnabled = am.isEnabled();
        mTouchExplorationEnabled = am.isTouchExplorationEnabled();
    }

    public boolean isAccessibilityEnabled() {
        return mAccessibilityEnabled;
    }

    public boolean isTouchExplorationEnabled() {
        return mTouchExplorationEnabled;
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("AccessibilityController state:");
        pw.print("  mAccessibilityEnabled="); pw.println(mAccessibilityEnabled);
        pw.print("  mTouchExplorationEnabled="); pw.println(mTouchExplorationEnabled);
    }

    public void addStateChangedCallback(AccessibilityStateChangedCallback cb) {
        mChangeCallbacks.add(cb);
        cb.onStateChanged(mAccessibilityEnabled, mTouchExplorationEnabled);
    }

    public void removeStateChangedCallback(AccessibilityStateChangedCallback cb) {
        mChangeCallbacks.remove(cb);
    }

    private void fireChanged() {
        final int N = mChangeCallbacks.size();
        for (int i = 0; i < N; i++) {
            mChangeCallbacks.get(i).onStateChanged(mAccessibilityEnabled, mTouchExplorationEnabled);
        }
    }

    @Override
    public void onAccessibilityStateChanged(boolean enabled) {
        mAccessibilityEnabled = enabled;
        fireChanged();
    }

    @Override
    public void onTouchExplorationStateChanged(boolean enabled) {
        mTouchExplorationEnabled = enabled;
        fireChanged();
    }

    public interface AccessibilityStateChangedCallback {
        void onStateChanged(boolean accessibilityEnabled, boolean touchExplorationEnabled);
    }
}
