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

package com.android.systemui.dreams;

import androidx.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.statusbar.policy.CallbackController;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Objects;

import javax.inject.Inject;

/**
 * {@link DreamOverlayStateController} is the source of truth for Dream overlay configurations.
 * Clients can register as listeners for changes to the overlay composition and can query for the
 * overlays on-demand.
 */
@SysUISingleton
public class DreamOverlayStateController implements
        CallbackController<DreamOverlayStateController.Callback> {
    // A counter for guaranteeing unique overlay tokens within the scope of this state controller.
    private int mNextOverlayTokenId = 0;

    /**
     * {@link OverlayToken} provides a unique key for identifying {@link OverlayProvider}
     * instances registered with {@link DreamOverlayStateController}.
     */
    public static class OverlayToken {
        private final int mId;

        private OverlayToken(int id) {
            mId = id;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof OverlayToken)) return false;
            OverlayToken that = (OverlayToken) o;
            return mId == that.mId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mId);
        }
    }

    /**
     * Callback for dream overlay events.
     */
    public interface Callback {
        /**
         * Called when the visibility of the communal view changes.
         */
        default void onOverlayChanged() {
        }
    }

    private final ArrayList<Callback> mCallbacks = new ArrayList<>();
    private final HashMap<OverlayToken, OverlayProvider> mOverlays = new HashMap<>();

    @VisibleForTesting
    @Inject
    public DreamOverlayStateController() {
    }

    /**
     * Adds an overlay to be presented on top of dreams.
     * @param provider The {@link OverlayProvider} providing the dream.
     * @return The {@link OverlayToken} tied to the supplied {@link OverlayProvider}.
     */
    public OverlayToken addOverlay(OverlayProvider provider) {
        final OverlayToken token = new OverlayToken(mNextOverlayTokenId++);
        mOverlays.put(token, provider);
        notifyCallbacks();
        return token;
    }

    /**
     * Removes an overlay from being shown on dreams.
     * @param token The {@link OverlayToken} associated with the {@link OverlayProvider} to be
     *              removed.
     * @return The removed {@link OverlayProvider}, {@code null} if not found.
     */
    public OverlayProvider removeOverlay(OverlayToken token) {
        final OverlayProvider removedOverlay = mOverlays.remove(token);

        if (removedOverlay != null) {
            notifyCallbacks();
        }

        return removedOverlay;
    }

    private void notifyCallbacks() {
        for (Callback callback : mCallbacks) {
            callback.onOverlayChanged();
        }
    }

    @Override
    public void addCallback(@NonNull Callback callback) {
        Objects.requireNonNull(callback, "Callback must not be null. b/128895449");
        if (mCallbacks.contains(callback)) {
            return;
        }

        mCallbacks.add(callback);

        if (mOverlays.isEmpty()) {
            return;
        }

        callback.onOverlayChanged();
    }

    @Override
    public void removeCallback(@NonNull Callback callback) {
        Objects.requireNonNull(callback, "Callback must not be null. b/128895449");
        mCallbacks.remove(callback);
    }

    /**
     * Returns all registered {@link OverlayProvider} instances.
     * @return A collection of {@link OverlayProvider}.
     */
    public Collection<OverlayProvider> getOverlays() {
        return mOverlays.values();
    }
}
