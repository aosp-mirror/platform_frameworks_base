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

package com.android.wm.shell.pip;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.graphics.Rect;
import android.util.Size;

import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.util.Objects;

/**
 * Singleton source of truth for the current state of PIP bounds.
 */
public final class PipBoundsState {
    private static final String TAG = PipBoundsState.class.getSimpleName();

    private final @NonNull Rect mBounds = new Rect();
    private PipReentryState mPipReentryState;
    private ComponentName mLastPipComponentName;

    void setBounds(@NonNull Rect bounds) {
        mBounds.set(bounds);
    }

    @NonNull
    public Rect getBounds() {
        return new Rect(mBounds);
    }

    /**
     * Save the reentry state to restore to when re-entering PIP mode.
     *
     * TODO(b/169373982): consider refactoring this so that this class alone can use mBounds and
     * calculate the snap fraction to save for re-entry.
     */
    public void saveReentryState(@NonNull Rect bounds, float fraction) {
        mPipReentryState = new PipReentryState(new Size(bounds.width(), bounds.height()), fraction);
    }

    /**
     * Returns the saved reentry state.
     */
    @Nullable
    public PipReentryState getReentryState() {
        return mPipReentryState;
    }

    /**
     * Set the last {@link ComponentName} to enter PIP mode.
     */
    public void setLastPipComponentName(ComponentName lastPipComponentName) {
        final boolean changed = !Objects.equals(mLastPipComponentName, lastPipComponentName);
        mLastPipComponentName = lastPipComponentName;
        if (changed) {
            clearReentryState();
        }
    }

    public ComponentName getLastPipComponentName() {
        return mLastPipComponentName;
    }

    @VisibleForTesting
    void clearReentryState() {
        mPipReentryState = null;
    }

    static final class PipReentryState {
        private static final String TAG = PipReentryState.class.getSimpleName();

        private final @NonNull Size mSize;
        private final float mSnapFraction;

        PipReentryState(@NonNull Size size, float snapFraction) {
            mSize = size;
            mSnapFraction = snapFraction;
        }

        @NonNull
        Size getSize() {
            return mSize;
        }

        float getSnapFraction() {
            return mSnapFraction;
        }

        void dump(PrintWriter pw, String prefix) {
            final String innerPrefix = prefix + "  ";
            pw.println(prefix + TAG);
            pw.println(innerPrefix + "mSize=" + mSize);
            pw.println(innerPrefix + "mSnapFraction=" + mSnapFraction);
        }
    }

    /**
     * Dumps internal state.
     */
    public void dump(PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        pw.println(prefix + TAG);
        pw.println(innerPrefix + "mBounds=" + mBounds);
        pw.println(innerPrefix + "mLastPipComponentName=" + mLastPipComponentName);
        if (mPipReentryState == null) {
            pw.println(innerPrefix + "mPipReentryState=null");
        } else {
            mPipReentryState.dump(pw, innerPrefix);
        }
    }
}
