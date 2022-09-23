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

import android.service.dreams.DreamService;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dreams.complication.Complication;
import com.android.systemui.statusbar.policy.CallbackController;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.inject.Inject;

/**
 * {@link DreamOverlayStateController} is the source of truth for Dream overlay configurations and
 * state. Clients can register as listeners for changes to the overlay composition and can query for
 * the complications on-demand.
 */
@SysUISingleton
public class DreamOverlayStateController implements
        CallbackController<DreamOverlayStateController.Callback> {
    private static final String TAG = "DreamOverlayStateCtlr";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    public static final int STATE_DREAM_OVERLAY_ACTIVE = 1 << 0;
    public static final int STATE_LOW_LIGHT_ACTIVE = 1 << 1;

    private static final int OP_CLEAR_STATE = 1;
    private static final int OP_SET_STATE = 2;

    private int mState;

    /**
     * Callback for dream overlay events.
     */
    public interface Callback {
        /**
         * Called when the composition of complications changes.
         */
        default void onComplicationsChanged() {
        }

        /**
         * Called when the dream overlay state changes.
         */
        default void onStateChanged() {
        }

        /**
         * Called when the available complication types changes.
         */
        default void onAvailableComplicationTypesChanged() {
        }
    }

    private final Executor mExecutor;
    private final ArrayList<Callback> mCallbacks = new ArrayList<>();

    @Complication.ComplicationType
    private int mAvailableComplicationTypes = Complication.COMPLICATION_TYPE_NONE;

    private boolean mShouldShowComplications = DreamService.DEFAULT_SHOW_COMPLICATIONS;

    private final Collection<Complication> mComplications = new HashSet();

    @VisibleForTesting
    @Inject
    public DreamOverlayStateController(@Main Executor executor) {
        mExecutor = executor;
    }

    /**
     * Adds a complication to be included on the dream overlay.
     */
    public void addComplication(Complication complication) {
        mExecutor.execute(() -> {
            if (mComplications.add(complication)) {
                if (DEBUG) {
                    Log.d(TAG, "addComplication: added " + complication);
                }
                mCallbacks.stream().forEach(callback -> callback.onComplicationsChanged());
            }
        });
    }

    /**
     * Removes a complication from inclusion on the dream overlay.
     */
    public void removeComplication(Complication complication) {
        mExecutor.execute(() -> {
            if (mComplications.remove(complication)) {
                if (DEBUG) {
                    Log.d(TAG, "removeComplication: removed " + complication);
                }
                mCallbacks.stream().forEach(callback -> callback.onComplicationsChanged());
            }
        });
    }

    /**
     * Returns collection of present {@link Complication}.
     */
    public Collection<Complication> getComplications() {
        return getComplications(true);
    }

    /**
     * Returns collection of present {@link Complication}.
     */
    public Collection<Complication> getComplications(boolean filterByAvailability) {
        return Collections.unmodifiableCollection(filterByAvailability
                ? mComplications
                .stream()
                .filter(complication -> {
                    @Complication.ComplicationType
                    final int requiredTypes = complication.getRequiredTypeAvailability();
                    // If it should show complications, show ones whose required types are
                    // available. Otherwise, only show ones that don't require types.
                    if (mShouldShowComplications) {
                        return (requiredTypes & getAvailableComplicationTypes()) == requiredTypes;
                    }
                    return requiredTypes == Complication.COMPLICATION_TYPE_NONE;
                })
                .collect(Collectors.toCollection(HashSet::new))
                : mComplications);
    }

    private void notifyCallbacks(Consumer<Callback> callbackConsumer) {
        mExecutor.execute(() -> {
            for (Callback callback : mCallbacks) {
                callbackConsumer.accept(callback);
            }
        });
    }

    @Override
    public void addCallback(@NonNull Callback callback) {
        mExecutor.execute(() -> {
            Objects.requireNonNull(callback, "Callback must not be null. b/128895449");
            if (mCallbacks.contains(callback)) {
                return;
            }

            mCallbacks.add(callback);

            if (mComplications.isEmpty()) {
                return;
            }

            callback.onComplicationsChanged();
        });
    }

    @Override
    public void removeCallback(@NonNull Callback callback) {
        mExecutor.execute(() -> {
            Objects.requireNonNull(callback, "Callback must not be null. b/128895449");
            mCallbacks.remove(callback);
        });
    }

    /**
     * Returns whether the overlay is active.
     * @return {@code true} if overlay is active, {@code false} otherwise.
     */
    public boolean isOverlayActive() {
        return containsState(STATE_DREAM_OVERLAY_ACTIVE);
    }

    /**
     * Returns whether low light mode is active.
     * @return {@code true} if in low light mode, {@code false} otherwise.
     */
    public boolean isLowLightActive() {
        return containsState(STATE_LOW_LIGHT_ACTIVE);
    }

    private boolean containsState(int state) {
        return (mState & state) != 0;
    }

    private void modifyState(int op, int state) {
        final int existingState = mState;
        switch (op) {
            case OP_CLEAR_STATE:
                mState &= ~state;
                break;
            case OP_SET_STATE:
                mState |= state;
                break;
        }

        if (existingState != mState) {
            notifyCallbacks(callback -> callback.onStateChanged());
        }
    }

    /**
     * Sets whether the overlay is active.
     * @param active {@code true} if overlay is active, {@code false} otherwise.
     */
    public void setOverlayActive(boolean active) {
        modifyState(active ? OP_SET_STATE : OP_CLEAR_STATE, STATE_DREAM_OVERLAY_ACTIVE);
    }

    /**
     * Sets whether low light mode is active.
     * @param active {@code true} if low light mode is active, {@code false} otherwise.
     */
    public void setLowLightActive(boolean active) {
        modifyState(active ? OP_SET_STATE : OP_CLEAR_STATE, STATE_LOW_LIGHT_ACTIVE);
    }

    /**
     * Returns the available complication types.
     */
    @Complication.ComplicationType
    public int getAvailableComplicationTypes() {
        return mAvailableComplicationTypes;
    }

    /**
     * Sets the available complication types for the dream overlay.
     */
    public void setAvailableComplicationTypes(@Complication.ComplicationType int types) {
        mExecutor.execute(() -> {
            mAvailableComplicationTypes = types;
            mCallbacks.forEach(Callback::onAvailableComplicationTypesChanged);
        });
    }

    /**
     * Returns whether the dream overlay should show complications.
     */
    public boolean getShouldShowComplications() {
        return mShouldShowComplications;
    }

    /**
     * Sets whether the dream overlay should show complications.
     */
    public void setShouldShowComplications(boolean shouldShowComplications) {
        mExecutor.execute(() -> {
            mShouldShowComplications = shouldShowComplications;
            mCallbacks.forEach(Callback::onAvailableComplicationTypesChanged);
        });
    }
}
