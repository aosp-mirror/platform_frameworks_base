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

import static com.android.systemui.dreams.dagger.DreamModule.DREAM_OVERLAY_ENABLED;

import android.service.dreams.DreamService;

import androidx.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.complication.Complication;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.log.LogBuffer;
import com.android.systemui.log.dagger.DreamLog;
import com.android.systemui.statusbar.policy.CallbackController;
import com.android.systemui.util.annotations.WeaklyReferencedCallback;
import com.android.systemui.util.reference.WeakReferenceFactory;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * {@link DreamOverlayStateController} is the source of truth for Dream overlay configurations and
 * state. Clients can register as listeners for changes to the overlay composition and can query for
 * the complications on-demand.
 */
@SysUISingleton
public class DreamOverlayStateController implements
        CallbackController<DreamOverlayStateController.Callback> {
    private static final String TAG = "DreamOverlayStateCtlr";

    public static final int STATE_DREAM_OVERLAY_ACTIVE = 1 << 0;
    public static final int STATE_LOW_LIGHT_ACTIVE = 1 << 1;
    public static final int STATE_DREAM_ENTRY_ANIMATIONS_FINISHED = 1 << 2;
    public static final int STATE_DREAM_EXIT_ANIMATIONS_RUNNING = 1 << 3;
    public static final int STATE_HAS_ASSISTANT_ATTENTION = 1 << 4;
    public static final int STATE_DREAM_OVERLAY_STATUS_BAR_VISIBLE = 1 << 5;

    private static final int OP_CLEAR_STATE = 1;
    private static final int OP_SET_STATE = 2;

    private int mState;

    /**
     * Callback for dream overlay events.
     * NOTE: Caller should maintain a strong reference to this themselves so the callback does
     * not get garbage collected.
     */
    @WeaklyReferencedCallback
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

        /**
         * Called when the low light dream is exiting and transitioning back to the user dream.
         */
        default void onExitLowLight() {
        }
    }

    private final Executor mExecutor;
    private final boolean mOverlayEnabled;
    private final ArrayList<WeakReference<Callback>> mCallbacks = new ArrayList<>();

    @Complication.ComplicationType
    private int mAvailableComplicationTypes = Complication.COMPLICATION_TYPE_NONE;

    private boolean mShouldShowComplications = DreamService.DEFAULT_SHOW_COMPLICATIONS;

    private final Collection<Complication> mComplications = new HashSet();

    private final FeatureFlags mFeatureFlags;
    private final WeakReferenceFactory mWeakReferenceFactory;

    private final int mSupportedTypes;

    private final DreamLogger mLogger;

    @VisibleForTesting
    @Inject
    public DreamOverlayStateController(@Main Executor executor,
            @Named(DREAM_OVERLAY_ENABLED) boolean overlayEnabled,
            FeatureFlags featureFlags,
            @DreamLog LogBuffer logBuffer,
            WeakReferenceFactory weakReferenceFactory) {
        mExecutor = executor;
        mOverlayEnabled = overlayEnabled;
        mLogger = new DreamLogger(logBuffer, TAG);
        mFeatureFlags = featureFlags;
        mWeakReferenceFactory = weakReferenceFactory;
        if (mFeatureFlags.isEnabled(Flags.ALWAYS_SHOW_HOME_CONTROLS_ON_DREAMS)) {
            mSupportedTypes = Complication.COMPLICATION_TYPE_NONE
                    | Complication.COMPLICATION_TYPE_HOME_CONTROLS;
        } else {
            mSupportedTypes = Complication.COMPLICATION_TYPE_NONE;
        }
        mLogger.logDreamOverlayEnabled(mOverlayEnabled);
    }

    /**
     * Adds a complication to be included on the dream overlay.
     */
    public void addComplication(Complication complication) {
        if (!mOverlayEnabled) {
            mLogger.logIgnoreAddComplication("overlay disabled", complication.toString());
            return;
        }

        mExecutor.execute(() -> {
            if (mComplications.add(complication)) {
                mLogger.logAddComplication(complication.toString());
                notifyCallbacksLocked(Callback::onComplicationsChanged);
            }
        });
    }

    /**
     * Removes a complication from inclusion on the dream overlay.
     */
    public void removeComplication(Complication complication) {
        if (!mOverlayEnabled) {
            mLogger.logIgnoreRemoveComplication("overlay disabled", complication.toString());
            return;
        }

        mExecutor.execute(() -> {
            if (mComplications.remove(complication)) {
                mLogger.logRemoveComplication(complication.toString());
                notifyCallbacksLocked(Callback::onComplicationsChanged);
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
        if (isLowLightActive()) {
            // Don't show complications on low light.
            return Collections.emptyList();
        }
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
                    final int typesToAlwaysShow = mSupportedTypes & getAvailableComplicationTypes();
                    return (requiredTypes & typesToAlwaysShow) == requiredTypes;
                })
                .collect(Collectors.toCollection(HashSet::new))
                : mComplications);
    }

    private void notifyCallbacks(Consumer<Callback> callbackConsumer) {
        mExecutor.execute(() -> notifyCallbacksLocked(callbackConsumer));
    }

    private void notifyCallbacksLocked(Consumer<Callback> callbackConsumer) {
        final Iterator<WeakReference<Callback>> iterator = mCallbacks.iterator();
        while (iterator.hasNext()) {
            final Callback callback = iterator.next().get();
            // Remove any callbacks which have been GC'd
            if (callback == null) {
                iterator.remove();
            } else {
                callbackConsumer.accept(callback);
            }
        }
    }

    @Override
    public void addCallback(@NonNull Callback callback) {
        mExecutor.execute(() -> {
            Objects.requireNonNull(callback, "Callback must not be null. b/128895449");
            final boolean containsCallback = mCallbacks.stream()
                    .anyMatch(reference -> reference.get() == callback);
            if (containsCallback) {
                return;
            }

            mCallbacks.add(mWeakReferenceFactory.create(callback));

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
            final Iterator<WeakReference<Callback>> iterator = mCallbacks.iterator();
            while (iterator.hasNext()) {
                final Callback cb = iterator.next().get();
                if (cb == null || cb == callback) {
                    iterator.remove();
                }
            }
        });
    }

    /**
     * Returns whether the overlay is active.
     * @return {@code true} if overlay is active, {@code false} otherwise.
     */
    public boolean isOverlayActive() {
        return mOverlayEnabled && containsState(STATE_DREAM_OVERLAY_ACTIVE);
    }

    /**
     * Returns whether low light mode is active.
     * @return {@code true} if in low light mode, {@code false} otherwise.
     */
    public boolean isLowLightActive() {
        return containsState(STATE_LOW_LIGHT_ACTIVE);
    }

    /**
     * Returns whether the dream content and dream overlay entry animations are finished.
     * @return {@code true} if animations are finished, {@code false} otherwise.
     */
    public boolean areEntryAnimationsFinished() {
        return containsState(STATE_DREAM_ENTRY_ANIMATIONS_FINISHED);
    }

    /**
     * Returns whether the dream content and dream overlay exit animations are running.
     * @return {@code true} if animations are running, {@code false} otherwise.
     */
    public boolean areExitAnimationsRunning() {
        return containsState(STATE_DREAM_EXIT_ANIMATIONS_RUNNING);
    }

    /**
     * Returns whether assistant currently has the user's attention.
     * @return {@code true} if assistant has the user's attention, {@code false} otherwise.
     */
    public boolean hasAssistantAttention() {
        return containsState(STATE_HAS_ASSISTANT_ATTENTION);
    }

    /**
     * Returns whether the dream overlay status bar is currently visible.
     * @return {@code true} if the status bar is visible, {@code false} otherwise.
     */
    public boolean isDreamOverlayStatusBarVisible() {
        return containsState(STATE_DREAM_OVERLAY_STATUS_BAR_VISIBLE);
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
            notifyCallbacks(Callback::onStateChanged);
        }
    }

    /**
     * Sets whether the overlay is active.
     * @param active {@code true} if overlay is active, {@code false} otherwise.
     */
    public void setOverlayActive(boolean active) {
        mLogger.logOverlayActive(active);
        modifyState(active ? OP_SET_STATE : OP_CLEAR_STATE, STATE_DREAM_OVERLAY_ACTIVE);
    }

    /**
     * Sets whether low light mode is active.
     * @param active {@code true} if low light mode is active, {@code false} otherwise.
     */
    public void setLowLightActive(boolean active) {
        mLogger.logLowLightActive(active);

        if (isLowLightActive() && !active) {
            // Notify that we're exiting low light only on the transition from active to not active.
            notifyCallbacks(Callback::onExitLowLight);
        }
        modifyState(active ? OP_SET_STATE : OP_CLEAR_STATE, STATE_LOW_LIGHT_ACTIVE);
    }

    /**
     * Sets whether dream content and dream overlay entry animations are finished.
     * @param finished {@code true} if entry animations are finished, {@code false} otherwise.
     */
    public void setEntryAnimationsFinished(boolean finished) {
        modifyState(finished ? OP_SET_STATE : OP_CLEAR_STATE,
                STATE_DREAM_ENTRY_ANIMATIONS_FINISHED);
    }

    /**
     * Sets whether dream content and dream overlay exit animations are running.
     * @param running {@code true} if exit animations are running, {@code false} otherwise.
     */
    public void setExitAnimationsRunning(boolean running) {
        modifyState(running ? OP_SET_STATE : OP_CLEAR_STATE,
                STATE_DREAM_EXIT_ANIMATIONS_RUNNING);
    }

    /**
     * Sets whether assistant currently has the user's attention.
     * @param hasAttention {@code true} if has the user's attention, {@code false} otherwise.
     */
    public void setHasAssistantAttention(boolean hasAttention) {
        mLogger.logHasAssistantAttention(hasAttention);
        modifyState(hasAttention ? OP_SET_STATE : OP_CLEAR_STATE, STATE_HAS_ASSISTANT_ATTENTION);
    }

    /**
     * Sets whether the dream overlay status bar is visible.
     * @param visible {@code true} if the status bar is visible, {@code false} otherwise.
     */
    public void setDreamOverlayStatusBarVisible(boolean visible) {
        mLogger.logStatusBarVisible(visible);
        modifyState(
                visible ? OP_SET_STATE : OP_CLEAR_STATE, STATE_DREAM_OVERLAY_STATUS_BAR_VISIBLE);
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
            mLogger.logAvailableComplicationTypes(types);
            mAvailableComplicationTypes = types;
            notifyCallbacksLocked(Callback::onAvailableComplicationTypesChanged);
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
            mLogger.logShouldShowComplications(shouldShowComplications);
            mShouldShowComplications = shouldShowComplications;
            notifyCallbacksLocked(Callback::onAvailableComplicationTypesChanged);
        });
    }
}
