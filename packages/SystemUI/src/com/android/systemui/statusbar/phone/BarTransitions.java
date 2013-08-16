/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.app.ActivityManager;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;

import com.android.systemui.R;

public class BarTransitions {
    private static final boolean DEBUG = false;

    public static final int MODE_NORMAL = 0;
    public static final int MODE_SEMI_TRANSPARENT = 1;
    public static final int MODE_TRANSPARENT = 2;

    private final String mTag;
    private final View mTarget;
    private final Drawable mOpaque;
    private final Drawable mSemiTransparent;

    protected Drawable mTransparent;
    private int mMode;

    public BarTransitions(Context context, View target) {
        mTag = "BarTransitions." + target.getClass().getSimpleName();
        mTarget = target;
        final Resources res = context.getResources();
        mOpaque = new ColorDrawable(res.getColor(R.drawable.status_bar_background));
        mSemiTransparent =
                new ColorDrawable(res.getColor(R.color.status_bar_background_semi_transparent));
    }

    public void setTransparent(Drawable transparent) {
        mTransparent = transparent;
        if (mMode == MODE_TRANSPARENT) {
            transitionTo(MODE_TRANSPARENT);
        }
    }

    public void transitionTo(int mode) {
        if (mMode == mode) return;
        int oldMode = mMode;
        mMode = mode;
        if (!ActivityManager.isHighEndGfx()) return;
        if (DEBUG) Log.d(mTag, String.format("transition from %s to %s",
                modeToString(oldMode), modeToString(mode)));
        onTransition(oldMode, mMode);
    }

    protected void onTransition(int oldMode, int newMode) {
        Drawable background = newMode == MODE_SEMI_TRANSPARENT ? mSemiTransparent
                : newMode == MODE_TRANSPARENT ? mTransparent
                : mOpaque;
        mTarget.setBackground(background);
    }

    public static String modeToString(int mode) {
        if (mode == MODE_NORMAL) return "MODE_NORMAL";
        if (mode == MODE_SEMI_TRANSPARENT) return "MODE_SEMI_TRANSPARENT";
        if (mode == MODE_TRANSPARENT) return "MODE_TRANSPARENT";
        throw new IllegalArgumentException("Unknown mode " + mode);
    }
}
