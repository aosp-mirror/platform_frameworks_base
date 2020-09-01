/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.dock;

/**
 * A rudimentary fake for DockManager.
 */
public class DockManagerFake implements DockManager {
    DockEventListener mCallback;
    AlignmentStateListener mAlignmentListener;

    @Override
    public void addListener(DockEventListener callback) {
        this.mCallback = callback;
    }

    @Override
    public void removeListener(DockEventListener callback) {
        this.mCallback = null;
    }

    @Override
    public void addAlignmentStateListener(AlignmentStateListener listener) {
        mAlignmentListener = listener;
    }

    @Override
    public void removeAlignmentStateListener(AlignmentStateListener listener) {
        mAlignmentListener = listener;
    }

    @Override
    public boolean isDocked() {
        return false;
    }

    @Override
    public boolean isHidden() {
        return false;
    }

    public void setDockEvent(int event) {
        mCallback.onEvent(event);
    }
}
