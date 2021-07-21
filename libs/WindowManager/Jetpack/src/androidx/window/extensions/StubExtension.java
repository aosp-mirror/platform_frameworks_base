/*
 * Copyright (C) 2021 The Android Open Source Project
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

package androidx.window.extensions;

import android.app.Activity;

import androidx.annotation.NonNull;

import java.util.HashSet;
import java.util.Set;

/**
 * Basic implementation of the {@link ExtensionInterface}. An OEM can choose to use it as the base
 * class for their implementation.
 */
abstract class StubExtension implements ExtensionInterface {

    private ExtensionCallback mExtensionCallback;
    private final Set<Activity> mWindowLayoutChangeListenerActivities = new HashSet<>();

    StubExtension() {
    }

    @Override
    public void setExtensionCallback(@NonNull ExtensionCallback extensionCallback) {
        this.mExtensionCallback = extensionCallback;
    }

    @Override
    public void onWindowLayoutChangeListenerAdded(@NonNull Activity activity) {
        this.mWindowLayoutChangeListenerActivities.add(activity);
        this.onListenersChanged();
    }

    @Override
    public void onWindowLayoutChangeListenerRemoved(@NonNull Activity activity) {
        this.mWindowLayoutChangeListenerActivities.remove(activity);
        this.onListenersChanged();
    }

    void updateWindowLayout(@NonNull Activity activity,
            @NonNull ExtensionWindowLayoutInfo newLayout) {
        if (this.mExtensionCallback != null) {
            mExtensionCallback.onWindowLayoutChanged(activity, newLayout);
        }
    }

    @NonNull
    Set<Activity> getActivitiesListeningForLayoutChanges() {
        return mWindowLayoutChangeListenerActivities;
    }

    protected boolean hasListeners() {
        return !mWindowLayoutChangeListenerActivities.isEmpty();
    }

    protected abstract void onListenersChanged();
}
