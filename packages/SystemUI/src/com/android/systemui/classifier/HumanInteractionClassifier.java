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
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.MotionEvent;

import com.android.systemui.R;

import java.util.ArrayDeque;

/**
 * An classifier trying to determine whether it is a human interacting with the phone or not.
 */
public class HumanInteractionClassifier extends Classifier {
    private static final String HIC_ENABLE = "HIC_enable";
    private static final float FINGER_DISTANCE = 0.1f;

    private static HumanInteractionClassifier sInstance = null;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Context mContext;

    private final StrokeClassifier[] mStrokeClassifiers;
    private final GestureClassifier[] mGestureClassifiers;
    private final ArrayDeque<MotionEvent> mBufferedEvents = new ArrayDeque<>();
    private final HistoryEvaluator mHistoryEvaluator;
    private final float mDpi;

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
        DisplayMetrics displayMetrics = mContext.getResources().getDisplayMetrics();

        // If the phone is rotated to landscape, the calculations would be wrong if xdpi and ydpi
        // were to be used separately. Due negligible differences in xdpi and ydpi we can just
        // take the average.
        // Note that xdpi and ydpi are the physical pixels per inch and are not affected by scaling.
        mDpi = (displayMetrics.xdpi + displayMetrics.ydpi) / 2.0f;
        mClassifierData = new ClassifierData(mDpi);
        mHistoryEvaluator = new HistoryEvaluator();

        mStrokeClassifiers = new StrokeClassifier[]{
                new AnglesClassifier(mClassifierData),
                new SpeedClassifier(mClassifierData),
                new DurationCountClassifier(mClassifierData),
                new EndPointRatioClassifier(mClassifierData),
                new EndPointLengthClassifier(mClassifierData),
                new AccelerationClassifier(mClassifierData),
                new SpeedAnglesClassifier(mClassifierData),
                new LengthCountClassifier(mClassifierData),
                new DirectionClassifier(mClassifierData),
        };

        mGestureClassifiers = new GestureClassifier[] {
                new PointerCountClassifier(mClassifierData),
                new ProximityClassifier(mClassifierData)
        };

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
        boolean defaultValue = mContext.getResources().getBoolean(
                R.bool.config_lockscreenAntiFalsingClassifierEnabled);

        mEnableClassifier = 0 != Settings.Global.getInt(
                mContext.getContentResolver(),
                HIC_ENABLE, defaultValue ? 1 : 0);
    }

    public void setType(int type) {
        mCurrentType = type;
    }

    @Override
    public void onTouchEvent(MotionEvent event) {
        if (!mEnableClassifier) {
            return;
        }

        // If the user is dragging down the notification, they might want to drag it down
        // enough to see the content, read it for a while and then lift the finger to open
        // the notification. This kind of motion scores very bad in the Classifier so the
        // MotionEvents which are close to the current position of the finger are not
        // sent to the classifiers until the finger moves far enough. When the finger if lifted
        // up, the last MotionEvent which was far enough from the finger is set as the final
        // MotionEvent and sent to the Classifiers.
        if (mCurrentType == Classifier.NOTIFICATION_DRAG_DOWN
                || mCurrentType == Classifier.PULSE_EXPAND) {
            mBufferedEvents.add(MotionEvent.obtain(event));
            Point pointEnd = new Point(event.getX() / mDpi, event.getY() / mDpi);

            while (pointEnd.dist(new Point(mBufferedEvents.getFirst().getX() / mDpi,
                    mBufferedEvents.getFirst().getY() / mDpi)) > FINGER_DISTANCE) {
                addTouchEvent(mBufferedEvents.getFirst());
                mBufferedEvents.remove();
            }

            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_UP) {
                mBufferedEvents.getFirst().setAction(MotionEvent.ACTION_UP);
                addTouchEvent(mBufferedEvents.getFirst());
                mBufferedEvents.clear();
            }
        } else {
            addTouchEvent(event);
        }
    }

    private void addTouchEvent(MotionEvent event) {
        if (!mClassifierData.update(event)) {
            return;
        }

        for (StrokeClassifier c : mStrokeClassifiers) {
            c.onTouchEvent(event);
        }

        for (GestureClassifier c : mGestureClassifiers) {
            c.onTouchEvent(event);
        }

        int size = mClassifierData.getEndingStrokes().size();
        for (int i = 0; i < size; i++) {
            Stroke stroke = mClassifierData.getEndingStrokes().get(i);
            float evaluation = 0.0f;
            StringBuilder sb = FalsingLog.ENABLED ? new StringBuilder("stroke") : null;
            for (StrokeClassifier c : mStrokeClassifiers) {
                float e = c.getFalseTouchEvaluation(mCurrentType, stroke);
                if (FalsingLog.ENABLED) {
                    String tag = c.getTag();
                    sb.append(" ").append(e >= 1f ? tag : tag.toLowerCase()).append("=").append(e);
                }
                evaluation += e;
            }

            if (FalsingLog.ENABLED) {
                FalsingLog.i(" addTouchEvent", sb.toString());
            }
            mHistoryEvaluator.addStroke(evaluation);
        }

        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            float evaluation = 0.0f;
            StringBuilder sb = FalsingLog.ENABLED ? new StringBuilder("gesture") : null;
            for (GestureClassifier c : mGestureClassifiers) {
                float e = c.getFalseTouchEvaluation(mCurrentType);
                if (FalsingLog.ENABLED) {
                    String tag = c.getTag();
                    sb.append(" ").append(e >= 1f ? tag : tag.toLowerCase()).append("=").append(e);
                }
                evaluation += e;
            }
            if (FalsingLog.ENABLED) {
                FalsingLog.i(" addTouchEvent", sb.toString());
            }
            mHistoryEvaluator.addGesture(evaluation);
            setType(Classifier.GENERIC);
        }

        mClassifierData.cleanUp(event);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        for (Classifier c : mStrokeClassifiers) {
            c.onSensorChanged(event);
        }

        for (Classifier c : mGestureClassifiers) {
            c.onSensorChanged(event);
        }
    }

    public boolean isFalseTouch() {
        if (mEnableClassifier) {
            float evaluation = mHistoryEvaluator.getEvaluation();
            boolean result = evaluation >= 5.0f;
            if (FalsingLog.ENABLED) {
                FalsingLog.i("isFalseTouch", new StringBuilder()
                        .append("eval=").append(evaluation).append(" result=")
                        .append(result ? 1 : 0).toString());
            }
            return result;
        }
        return false;
    }

    public boolean isEnabled() {
        return mEnableClassifier;
    }

    @Override
    public String getTag() {
        return "HIC";
    }
}
