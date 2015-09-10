/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.classifier;

import android.content.Context;
import android.database.ContentObserver;
import android.hardware.SensorEvent;
import android.os.Build;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.MotionEvent;

/**
 * An classifier trying to determine whether it is a human interacting with the phone or not.
 */
public class HumanInteractionClassifier extends Classifier {
    private static final String HIC_ENABLE = "HIC_enable";
    private static HumanInteractionClassifier sInstance = null;

    private final Handler mHandler = new Handler();
    private final Context mContext;

    private AnglesVarianceClassifier mAnglesVarianceClassifier;
    private boolean mEnableClassifier = false;

    protected final ContentObserver mSettingsObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            updateConfiguration();
        }
    };

    private HumanInteractionClassifier(Context context) {
        mContext = context;
        mClassifierData = new ClassifierData();
        mAnglesVarianceClassifier = new AnglesVarianceClassifier(mClassifierData);

        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(HIC_ENABLE), false,
                mSettingsObserver,
                UserHandle.USER_ALL);

        updateConfiguration();
    }

    public static HumanInteractionClassifier getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new HumanInteractionClassifier(context);
        }
        return sInstance;
    }

    private void updateConfiguration() {
        mEnableClassifier = Build.IS_DEBUGGABLE && 0 != Settings.Global.getInt(
                mContext.getContentResolver(),
                HIC_ENABLE, 0);
    }

    @Override
    public void onTouchEvent(MotionEvent event) {
        if (mEnableClassifier) {
            mClassifierData.update(event);
            mAnglesVarianceClassifier.onTouchEvent(event);
            mClassifierData.cleanUp(event);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
    }

    @Override
    public float getFalseTouchEvaluation(int type) {
        if (mEnableClassifier) {
            return mAnglesVarianceClassifier.getFalseTouchEvaluation(type);
        }
        return 0.0f;
    }

    public boolean isEnabled() {
        return mEnableClassifier;
    }
}
