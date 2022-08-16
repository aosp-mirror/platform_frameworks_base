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

package com.android.systemui.util.condition;

import android.util.Log;

import com.android.systemui.statusbar.policy.CallbackController;

import org.jetbrains.annotations.NotNull;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;

/**
 * Base class for a condition that needs to be fulfilled in order for {@link Monitor} to inform
 * its callbacks.
 */
public abstract class Condition implements CallbackController<Condition.Callback> {
    private final String mTag = getClass().getSimpleName();

    private final ArrayList<WeakReference<Callback>> mCallbacks = new ArrayList<>();
    private boolean mIsConditionMet = false;
    private boolean mStarted = false;
    private boolean mOverriding = false;

    /**
     * Starts monitoring the condition.
     */
    protected abstract void start();

    /**
     * Stops monitoring the condition.
     */
    protected abstract void stop();

    /**
     * Sets whether this condition's value overrides others in determining the overall state.
     */
    public void setOverriding(boolean overriding) {
        mOverriding = overriding;
        updateCondition(mIsConditionMet);
    }

    /**
     * Returns whether the current condition overrides
     */
    public boolean isOverridingCondition() {
        return mOverriding;
    }

    /**
     * Registers a callback to receive updates once started. This should be called before
     * {@link #start()}. Also triggers the callback immediately if already started.
     */
    @Override
    public void addCallback(@NotNull Callback callback) {
        if (shouldLog()) Log.d(mTag, "adding callback");
        mCallbacks.add(new WeakReference<>(callback));

        if (mStarted) {
            callback.onConditionChanged(this);
            return;
        }

        start();
        mStarted = true;
    }

    /**
     * Removes the provided callback from further receiving updates.
     */
    @Override
    public void removeCallback(@NotNull Callback callback) {
        if (shouldLog()) Log.d(mTag, "removing callback");
        final Iterator<WeakReference<Callback>> iterator = mCallbacks.iterator();
        while (iterator.hasNext()) {
            final Callback cb = iterator.next().get();
            if (cb == null || cb == callback) {
                iterator.remove();
            }
        }

        if (!mCallbacks.isEmpty() || !mStarted) {
            return;
        }

        stop();
        mStarted = false;
    }

    /**
     * Updates the value for whether the condition has been fulfilled, and sends an update if the
     * value changes and any callback is registered.
     *
     * @param isConditionMet True if the condition has been fulfilled. False otherwise.
     */
    protected void updateCondition(boolean isConditionMet) {
        if (mIsConditionMet == isConditionMet) {
            return;
        }

        if (shouldLog()) Log.d(mTag, "updating condition to " + isConditionMet);
        mIsConditionMet = isConditionMet;

        final Iterator<WeakReference<Callback>> iterator = mCallbacks.iterator();
        while (iterator.hasNext()) {
            final Callback cb = iterator.next().get();
            if (cb == null) {
                iterator.remove();
            } else {
                cb.onConditionChanged(this);
            }
        }
    }

    public boolean isConditionMet() {
        return mIsConditionMet;
    }

    private boolean shouldLog() {
        return Log.isLoggable(mTag, Log.DEBUG);
    }

    /**
     * Callback that receives updates about whether the condition has been fulfilled.
     */
    public interface Callback {
        /**
         * Called when the fulfillment of the condition changes.
         *
         * @param condition The condition in question.
         */
        void onConditionChanged(Condition condition);
    }
}
