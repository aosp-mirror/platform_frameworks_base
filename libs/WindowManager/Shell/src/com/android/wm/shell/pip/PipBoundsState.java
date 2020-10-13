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
import android.graphics.Rect;

import java.io.PrintWriter;

/**
 * Singleton source of truth for the current state of PIP bounds.
 */
public final class PipBoundsState {
    private static final String TAG = PipBoundsState.class.getSimpleName();

    private final @NonNull Rect mBounds = new Rect();

    void setBounds(@NonNull Rect bounds) {
        mBounds.set(bounds);
    }

    @NonNull
    public Rect getBounds() {
        return new Rect(mBounds);
    }

    /**
     * Dumps internal state.
     */
    public void dump(PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        pw.println(prefix + TAG);
        pw.println(innerPrefix + "mBounds=" + mBounds);
    }
}
