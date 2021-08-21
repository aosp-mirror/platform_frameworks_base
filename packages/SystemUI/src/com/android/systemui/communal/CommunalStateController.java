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

package com.android.systemui.communal;

import android.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.statusbar.policy.CallbackController;

import java.util.ArrayList;
import java.util.Objects;

import javax.inject.Inject;

/**
 * CommunalStateController enables publishing and listening to communal-related state changes.
 */
@SysUISingleton
public class CommunalStateController implements
        CallbackController<CommunalStateController.Callback> {
    private final ArrayList<Callback> mCallbacks = new ArrayList<>();
    private boolean mCommunalViewOccluded;
    private boolean mCommunalViewShowing;

    /**
     * Callback for communal events.
     */
    public interface Callback {
        /**
         * Called when the visibility of the communal view changes.
         */
        default void onCommunalViewShowingChanged() {
        }

        /**
         * Called when the occlusion of the communal view changes.
         */
        default void onCommunalViewOccludedChanged() {
        }
    }

    @VisibleForTesting
    @Inject
    public CommunalStateController() {
    }

    /**
     * Sets whether the communal view is showing.
     * @param communalViewShowing {@code true} if the view is showing, {@code false} otherwise.
     */
    public void setCommunalViewShowing(boolean communalViewShowing) {
        if (mCommunalViewShowing == communalViewShowing) {
            return;
        }

        mCommunalViewShowing = communalViewShowing;

        final ArrayList<Callback> callbacks = new ArrayList<>(mCallbacks);
        for (Callback callback : callbacks) {
            callback.onCommunalViewShowingChanged();
        }
    }

    /**
     * Sets whether the communal view is occluded (but otherwise still showing).
     * @param communalViewOccluded {@code true} if the view is occluded, {@code false} otherwise.
     */
    public void setCommunalViewOccluded(boolean communalViewOccluded) {
        if (mCommunalViewOccluded == communalViewOccluded) {
            return;
        }

        mCommunalViewOccluded = communalViewOccluded;

        ArrayList<Callback> callbacks = new ArrayList<>(mCallbacks);
        for (int i = 0; i < callbacks.size(); i++) {
            callbacks.get(i).onCommunalViewOccludedChanged();
        }
    }

    /**
     * Returns whether the communal view is showing.
     * @return {@code true} if the view is showing, {@code false} otherwise.
     */
    public boolean getCommunalViewShowing() {
        return mCommunalViewShowing;
    }

    /**
     * Returns whether the communal view is occluded.
     * @return {@code true} if the view is occluded, {@code false} otherwise.
     */
    public boolean getCommunalViewOccluded() {
        return mCommunalViewOccluded;
    }

    @Override
    public void addCallback(@NonNull Callback callback) {
        Objects.requireNonNull(callback, "Callback must not be null. b/128895449");
        if (!mCallbacks.contains(callback)) {
            mCallbacks.add(callback);
        }
    }

    @Override
    public void removeCallback(@NonNull Callback callback) {
        Objects.requireNonNull(callback, "Callback must not be null. b/128895449");
        mCallbacks.remove(callback);
    }
}
