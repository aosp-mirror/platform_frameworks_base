/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.content.Context;
import android.graphics.Rect;
import android.view.Surface;

import androidx.annotation.NonNull;

import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.dagger.WMSingleton;

import java.io.PrintWriter;

import javax.inject.Inject;

/**
 * Acts as a source of truth for display related information for PIP.
 */
@WMSingleton
public class PipDisplayLayoutState {
    private static final String TAG = PipDisplayLayoutState.class.getSimpleName();

    private Context mContext;
    private int mDisplayId;
    @NonNull private DisplayLayout mDisplayLayout;

    @Inject
    public PipDisplayLayoutState(Context context) {
        mContext = context;
        mDisplayLayout = new DisplayLayout();
    }

    /** Update the display layout. */
    public void setDisplayLayout(@NonNull DisplayLayout displayLayout) {
        mDisplayLayout.set(displayLayout);
    }

    /** Get a copy of the display layout. */
    @NonNull
    public DisplayLayout getDisplayLayout() {
        return new DisplayLayout(mDisplayLayout);
    }

    /** Get the display bounds */
    @NonNull
    public Rect getDisplayBounds() {
        return new Rect(0, 0, mDisplayLayout.width(), mDisplayLayout.height());
    }

    /**
     * Apply a rotation to this layout and its parameters.
     * @param targetRotation
     */
    public void rotateTo(@Surface.Rotation int targetRotation) {
        mDisplayLayout.rotateTo(mContext.getResources(), targetRotation);
    }

    /** Get the current display id */
    public int getDisplayId() {
        return mDisplayId;
    }

    /** Set the current display id for the associated display layout. */
    public void setDisplayId(int displayId) {
        mDisplayId = displayId;
    }

    /** Dumps internal state. */
    public void dump(PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        pw.println(prefix + TAG);
        pw.println(innerPrefix + "mDisplayId=" + mDisplayId);
        pw.println(innerPrefix + "getDisplayBounds=" + getDisplayBounds());
    }
}
