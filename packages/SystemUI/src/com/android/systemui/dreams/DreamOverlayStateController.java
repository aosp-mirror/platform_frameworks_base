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
 * {@link DreamOverlayStateController} is the source of truth for Dream overlay configurations and
 * state. Clients can register as listeners for changes to the overlay composition and can query for
 * the complications on-demand.
 */
@SysUISingleton
public class DreamOverlayStateController implements
        CallbackController<DreamOverlayStateController.Callback> {
    // A counter for guaranteeing unique complications tokens within the scope of this state
    // controller.
    private int mNextComplicationTokenId = 0;

    /**
     * {@link ComplicationToken} provides a unique key for identifying {@link ComplicationProvider}
     * instances registered with {@link DreamOverlayStateController}.
     */
    public static class ComplicationToken {
        private final int mId;

        private ComplicationToken(int id) {
            mId = id;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ComplicationToken)) return false;
            ComplicationToken that = (ComplicationToken) o;
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
         * Called when the composition of complications changes.
         */
        default void onComplicationsChanged() {
        }
    }

    private final ArrayList<Callback> mCallbacks = new ArrayList<>();
    private final HashMap<ComplicationToken, ComplicationProvider> mComplications = new HashMap<>();

    @VisibleForTesting
    @Inject
    public DreamOverlayStateController() {
    }

    /**
     * Adds a complication to be presented on top of dreams.
     * @param provider The {@link ComplicationProvider} providing the dream.
     * @return The {@link ComplicationToken} tied to the supplied {@link ComplicationProvider}.
     */
    public ComplicationToken addComplication(ComplicationProvider provider) {
        final ComplicationToken token = new ComplicationToken(mNextComplicationTokenId++);
        mComplications.put(token, provider);
        notifyCallbacks();
        return token;
    }

    /**
     * Removes a complication from being shown on dreams.
     * @param token The {@link ComplicationToken} associated with the {@link ComplicationProvider}
     *              to be removed.
     * @return The removed {@link ComplicationProvider}, {@code null} if not found.
     */
    public ComplicationProvider removeComplication(ComplicationToken token) {
        final ComplicationProvider removedComplication = mComplications.remove(token);

        if (removedComplication != null) {
            notifyCallbacks();
        }

        return removedComplication;
    }

    private void notifyCallbacks() {
        for (Callback callback : mCallbacks) {
            callback.onComplicationsChanged();
        }
    }

    @Override
    public void addCallback(@NonNull Callback callback) {
        Objects.requireNonNull(callback, "Callback must not be null. b/128895449");
        if (mCallbacks.contains(callback)) {
            return;
        }

        mCallbacks.add(callback);

        if (mComplications.isEmpty()) {
            return;
        }

        callback.onComplicationsChanged();
    }

    @Override
    public void removeCallback(@NonNull Callback callback) {
        Objects.requireNonNull(callback, "Callback must not be null. b/128895449");
        mCallbacks.remove(callback);
    }

    /**
     * Returns all registered {@link ComplicationProvider} instances.
     * @return A collection of {@link ComplicationProvider}.
     */
    public Collection<ComplicationProvider> getComplications() {
        return mComplications.values();
    }
}
