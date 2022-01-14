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

package com.android.wm.shell.pip.tv;

import android.content.Context;
import android.graphics.Rect;
import android.util.Log;
import android.view.Gravity;

import androidx.annotation.NonNull;

import com.android.wm.shell.pip.PipBoundsAlgorithm;
import com.android.wm.shell.pip.PipBoundsState;
import com.android.wm.shell.pip.PipSnapAlgorithm;

/**
 * Contains pip bounds calculations that are specific to TV.
 */
public class TvPipBoundsAlgorithm extends PipBoundsAlgorithm {

    private static final String TAG = TvPipBoundsAlgorithm.class.getSimpleName();
    private static final boolean DEBUG = false;

    public TvPipBoundsAlgorithm(Context context,
            @NonNull PipBoundsState pipBoundsState,
            @NonNull PipSnapAlgorithm pipSnapAlgorithm) {
        super(context, pipBoundsState, pipSnapAlgorithm);
    }

    /**
     * The normal bounds at a different position on the screen.
     */
    public Rect getTvNormalBounds(int gravity) {
        Rect normalBounds = getNormalBounds();
        Rect insetBounds = new Rect();
        getInsetBounds(insetBounds);

        if (mPipBoundsState.isImeShowing()) {
            if (DEBUG) Log.d(TAG, "IME showing, height: " + mPipBoundsState.getImeHeight());
            insetBounds.bottom -= mPipBoundsState.getImeHeight();
        }

        Rect result = new Rect();
        Gravity.apply(gravity, normalBounds.width(), normalBounds.height(), insetBounds, result);

        if (DEBUG) {
            Log.d(TAG, "normalBounds: " + normalBounds.toShortString());
            Log.d(TAG, "insetBounds: " + insetBounds.toShortString());
            Log.d(TAG, "gravity: " + Gravity.toString(gravity));
            Log.d(TAG, "resultBounds: " + result.toShortString());
        }

        return result;
    }
}
