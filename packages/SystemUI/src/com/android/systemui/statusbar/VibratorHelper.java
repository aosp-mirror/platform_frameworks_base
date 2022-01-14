/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.statusbar;

import android.content.Context;
import android.os.AsyncTask;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;

import com.android.systemui.dagger.SysUISingleton;

import javax.inject.Inject;

/**
 */
@SysUISingleton
public class VibratorHelper {

    private final Vibrator mVibrator;
    private final Context mContext;
    private static final VibrationAttributes TOUCH_VIBRATION_ATTRIBUTES =
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH);

    /**
     */
    @Inject
    public VibratorHelper(Context context) {
        mContext = context;
        mVibrator = context.getSystemService(Vibrator.class);
    }

    public void vibrate(final int effectId) {
        AsyncTask.execute(() ->
                mVibrator.vibrate(VibrationEffect.get(effectId, false /* fallback */),
                        TOUCH_VIBRATION_ATTRIBUTES));
    }
}
