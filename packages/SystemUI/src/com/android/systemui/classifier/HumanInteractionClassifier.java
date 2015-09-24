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

import java.util.ArrayList;

/**
 * An classifier trying to determine whether it is a human interacting with the phone or not.
 */
public class HumanInteractionClassifier extends Classifier {
    private static final String HIC_ENABLE = "HIC_enable";
    private static HumanInteractionClassifier sInstance = null;

    private final Handler mHandler = new Handler();
    private final Context mContext;

    private ArrayList<StrokeClassifier> mStrokeClassifiers = new ArrayList<>();
    private ArrayList<GestureClassifier> mGestureClassifiers = new ArrayList<>();
    private final int mStrokeClassifiersSize;
    private final int mGestureClassifiersSize;

    private HistoryEvaluator mHistoryEvaluator;
    private boolean mEnableClassifier = false;
    private int mCurrentType = Classifier.GENERIC;

    protected final ContentObserver mSettingsObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            updateConfiguration();
        }
    };

    private HumanInteractionClassifier(Context context) {
        mContext = context;
        mClassifierData = new ClassifierData();
        mHistoryEvaluator = new HistoryEvaluator();

        mStrokeClassifiers.add(new AnglesVarianceClassifier(mClassifierData));

        mStrokeClassifiersSize = mStrokeClassifiers.size();
        mGestureClassifiersSize = mGestureClassifiers.size();

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

    public void setType(int type) {
        mCurrentType = type;
    }

    @Override
    public void onTouchEvent(MotionEvent event) {
        if (mEnableClassifier) {
            mClassifierData.update(event);

            for (int i = 0; i < mStrokeClassifiersSize; i++) {
                mStrokeClassifiers.get(i).onTouchEvent(event);
            }

            for (int i = 0; i < mGestureClassifiersSize; i++) {
                mGestureClassifiers.get(i).onTouchEvent(event);
            }

            int size = mClassifierData.getEndingStrokes().size();
            for (int i = 0; i < size; i++) {
                Stroke stroke = mClassifierData.getEndingStrokes().get(i);
                float evaluation = 0.0f;
                for (int j = 0; j < mStrokeClassifiersSize; j++) {
                    evaluation += mStrokeClassifiers.get(j).getFalseTouchEvaluation(
                            mCurrentType, stroke);
                }
                mHistoryEvaluator.addStroke(evaluation);
            }

            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                float evaluation = 0.0f;
                for (int i = 0; i < mGestureClassifiersSize; i++) {
                    evaluation += mGestureClassifiers.get(i).getFalseTouchEvaluation(mCurrentType);
                }
                mHistoryEvaluator.addGesture(evaluation);
                setType(Classifier.GENERIC);
            }

            mClassifierData.cleanUp(event);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
    }

    public boolean isFalseTouch() {
        return mHistoryEvaluator.getEvaluation() >= 5.0f;
    }

    public boolean isEnabled() {
        return mEnableClassifier;
    }
}
