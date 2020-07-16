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

package androidx.window.sidecar;

import android.os.IBinder;

import androidx.annotation.NonNull;

import java.util.HashSet;
import java.util.Set;

/**
 * Basic implementation of the {@link SidecarInterface}. An OEM can choose to use it as the base
 * class for their implementation.
 */
abstract class StubSidecar implements SidecarInterface {

    private SidecarCallback mSidecarCallback;
    private final Set<IBinder> mWindowLayoutChangeListenerTokens = new HashSet<>();
    private boolean mDeviceStateChangeListenerRegistered;

    StubSidecar() {
    }

    @Override
    public void setSidecarCallback(@NonNull SidecarCallback sidecarCallback) {
        this.mSidecarCallback = sidecarCallback;
    }

    @Override
    public void onWindowLayoutChangeListenerAdded(@NonNull IBinder iBinder) {
        this.mWindowLayoutChangeListenerTokens.add(iBinder);
        this.onListenersChanged();
    }

    @Override
    public void onWindowLayoutChangeListenerRemoved(@NonNull IBinder iBinder) {
        this.mWindowLayoutChangeListenerTokens.remove(iBinder);
        this.onListenersChanged();
    }

    @Override
    public void onDeviceStateListenersChanged(boolean isEmpty) {
        this.mDeviceStateChangeListenerRegistered = !isEmpty;
        this.onListenersChanged();
    }

    void updateDeviceState(SidecarDeviceState newState) {
        if (this.mSidecarCallback != null) {
            mSidecarCallback.onDeviceStateChanged(newState);
        }
    }

    void updateWindowLayout(@NonNull IBinder windowToken,
            @NonNull SidecarWindowLayoutInfo newLayout) {
        if (this.mSidecarCallback != null) {
            mSidecarCallback.onWindowLayoutChanged(windowToken, newLayout);
        }
    }

    @NonNull
    Set<IBinder> getWindowsListeningForLayoutChanges() {
        return mWindowLayoutChangeListenerTokens;
    }

    protected boolean hasListeners() {
        return !mWindowLayoutChangeListenerTokens.isEmpty() || mDeviceStateChangeListenerRegistered;
    }

    protected abstract void onListenersChanged();
}
