/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.volume;

import android.content.Context;
import android.util.AttributeSet;

import com.android.keyguard.AlphaOptimizedImageButton;
import com.android.systemui.R;

/** Toggle button in Volume Dialog that allows extra state for when streams are opted-out */
public class CaptionsToggleImageButton extends AlphaOptimizedImageButton {

    private static final int[] OPTED_OUT_STATE = new int[] { R.attr.optedOut };

    private boolean mComponentEnabled = false;
    private boolean mOptedOut = false;

    public CaptionsToggleImageButton(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public int[] onCreateDrawableState(int extraSpace) {
        int[] state = super.onCreateDrawableState(extraSpace + 1);
        if (mOptedOut) {
            mergeDrawableStates(state, OPTED_OUT_STATE);
        }
        return state;
    }

    Runnable setComponentEnabled(boolean isComponentEnabled) {
        this.mComponentEnabled = isComponentEnabled;

        return this.setImageResourceAsync(this.mComponentEnabled
                ? R.drawable.ic_volume_odi_captions
                : R.drawable.ic_volume_odi_captions_disabled);
    }

    boolean getComponentEnabled() {
        return this.mComponentEnabled;
    }

    /** Sets whether or not the current stream has opted out of captions */
    void setOptedOut(boolean isOptedOut) {
        this.mOptedOut = isOptedOut;
        refreshDrawableState();
    }

    boolean getOptedOut() {
        return this.mOptedOut;
    }
}
