/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.dreams;

import android.view.View;

import androidx.annotation.NonNull;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.statusbar.policy.CallbackController;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * {@link DreamOverlayStatusBarItemsProvider} provides extra dream overlay status bar items. A
 * callback can be registered that will be informed of items being added or removed from the
 * provider.
 */
@SysUISingleton
public class DreamOverlayStatusBarItemsProvider implements
        CallbackController<DreamOverlayStatusBarItemsProvider.Callback> {
    /**
     * Represents one item in the dream overlay status bar.
     */
    public interface StatusBarItem {
        /**
         * Return the {@link View} associated with this item.
         */
        View getView();
    }

    /**
     * A callback to be registered with the provider to be informed of when the list of status bar
     * items has changed.
     */
    public interface Callback {
        /**
         * Inform the callback that status bar items have changed.
         */
        void onStatusBarItemsChanged(List<StatusBarItem> newItems);
    }

    private final Executor mExecutor;
    private final List<StatusBarItem> mItems = new ArrayList<>();
    private final List<Callback> mCallbacks = new ArrayList<>();

    @Inject
    public DreamOverlayStatusBarItemsProvider(@Main Executor executor) {
        mExecutor = executor;
    }

    @Override
    public void addCallback(@NonNull Callback callback) {
        mExecutor.execute(() -> {
            Objects.requireNonNull(callback, "Callback must not be null.");
            if (mCallbacks.contains(callback)) {
                return;
            }

            mCallbacks.add(callback);
            if (!mItems.isEmpty()) {
                callback.onStatusBarItemsChanged(mItems);
            }
        });
    }

    @Override
    public void removeCallback(@NonNull Callback callback) {
        mExecutor.execute(() -> {
            Objects.requireNonNull(callback, "Callback must not be null.");
            mCallbacks.remove(callback);
        });
    }

    /**
     * Adds an item to the dream overlay status bar.
     */
    public void addStatusBarItem(StatusBarItem item) {
        mExecutor.execute(() -> {
            if (!mItems.contains(item)) {
                mItems.add(item);
                mCallbacks.forEach(callback -> callback.onStatusBarItemsChanged(mItems));
            }
        });
    }

    /**
     * Removes an item from the dream overlay status bar.
     */
    public void removeStatusBarItem(StatusBarItem item) {
        mExecutor.execute(() -> {
            if (mItems.remove(item)) {
                mCallbacks.forEach(callback -> callback.onStatusBarItemsChanged(mItems));
            }
        });
    }
}
