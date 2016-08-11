/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.display;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TypeEvaluator;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings.Secure;
import android.util.MathUtils;
import android.util.Slog;
import android.view.animation.AnimationUtils;

import com.android.internal.app.NightDisplayController;
import com.android.server.SystemService;
import com.android.server.twilight.TwilightListener;
import com.android.server.twilight.TwilightManager;
import com.android.server.twilight.TwilightState;

import java.util.Calendar;
import java.util.TimeZone;

import static com.android.server.display.DisplayTransformManager.LEVEL_COLOR_MATRIX_NIGHT_DISPLAY;

/**
 * Tints the display at night.
 */
public final class NightDisplayService extends SystemService
        implements NightDisplayController.Callback {

    private static final String TAG = "NightDisplayService";
    private static final boolean DEBUG = false;

    /**
     * Night display ~= 3400 K.
     */
    private static final float[] MATRIX_NIGHT = new float[] {
        1,      0,      0, 0,
        0, 0.754f,      0, 0,
        0,      0, 0.516f, 0,
        0,      0,      0, 1
    };

    /**
     * The identity matrix, used if one of the given matrices is {@code null}.
     */
    private static final float[] MATRIX_IDENTITY = new float[16];
    static {
        Matrix.setIdentityM(MATRIX_IDENTITY, 0);
    }

    /**
     * Evaluator used to animate color matrix transitions.
     */
    private static final ColorMatrixEvaluator COLOR_MATRIX_EVALUATOR = new ColorMatrixEvaluator();

    private final Handler mHandler;

    private int mCurrentUser = UserHandle.USER_NULL;
    private ContentObserver mUserSetupObserver;
    private boolean mBootCompleted;

    private NightDisplayController mController;
    private ValueAnimator mColorMatrixAnimator;
    private Boolean mIsActivated;
    private AutoMode mAutoMode;

    public NightDisplayService(Context context) {
        super(context);
        mHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public void onStart() {
        // Nothing to publish.
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_BOOT_COMPLETED) {
            mBootCompleted = true;

            // Register listeners now that boot is complete.
            if (mCurrentUser != UserHandle.USER_NULL && mUserSetupObserver == null) {
                setUp();
            }
        }
    }

    @Override
    public void onStartUser(int userHandle) {
        super.onStartUser(userHandle);

        if (mCurrentUser == UserHandle.USER_NULL) {
            onUserChanged(userHandle);
        }
    }

    @Override
    public void onSwitchUser(int userHandle) {
        super.onSwitchUser(userHandle);

        onUserChanged(userHandle);
    }

    @Override
    public void onStopUser(int userHandle) {
        super.onStopUser(userHandle);

        if (mCurrentUser == userHandle) {
            onUserChanged(UserHandle.USER_NULL);
        }
    }

    private void onUserChanged(int userHandle) {
        final ContentResolver cr = getContext().getContentResolver();

        if (mCurrentUser != UserHandle.USER_NULL) {
            if (mUserSetupObserver != null) {
                cr.unregisterContentObserver(mUserSetupObserver);
                mUserSetupObserver = null;
            } else if (mBootCompleted) {
                tearDown();
            }
        }

        mCurrentUser = userHandle;

        if (mCurrentUser != UserHandle.USER_NULL) {
            if (!isUserSetupCompleted(cr, mCurrentUser)) {
                mUserSetupObserver = new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange, Uri uri) {
                        if (isUserSetupCompleted(cr, mCurrentUser)) {
                            cr.unregisterContentObserver(this);
                            mUserSetupObserver = null;

                            if (mBootCompleted) {
                                setUp();
                            }
                        }
                    }
                };
                cr.registerContentObserver(Secure.getUriFor(Secure.USER_SETUP_COMPLETE),
                        false /* notifyForDescendents */, mUserSetupObserver, mCurrentUser);
            } else if (mBootCompleted) {
                setUp();
            }
        }
    }

    private static boolean isUserSetupCompleted(ContentResolver cr, int userHandle) {
        return Secure.getIntForUser(cr, Secure.USER_SETUP_COMPLETE, 0, userHandle) == 1;
    }

    private void setUp() {
        // Create a new controller for the current user and start listening for changes.
        mController = new NightDisplayController(getContext(), mCurrentUser);
        mController.setListener(this);

        // Initialize the current auto mode.
        onAutoModeChanged(mController.getAutoMode());

        // Force the initialization current activated state.
        if (mIsActivated == null) {
            onActivated(mController.isActivated());
        }
    }

    private void tearDown() {
        if (mController != null) {
            mController.setListener(null);
            mController = null;
        }

        if (mAutoMode != null) {
            mAutoMode.onStop();
            mAutoMode = null;
        }

        if (mColorMatrixAnimator != null) {
            mColorMatrixAnimator.end();
            mColorMatrixAnimator = null;
        }

        mIsActivated = null;
    }

    @Override
    public void onActivated(boolean activated) {
        if (mIsActivated == null || mIsActivated != activated) {
            Slog.i(TAG, activated ? "Turning on night display" : "Turning off night display");

            if (mAutoMode != null) {
                mAutoMode.onActivated(activated);
            }

            mIsActivated = activated;

            // Cancel the old animator if still running.
            if (mColorMatrixAnimator != null) {
                mColorMatrixAnimator.cancel();
            }

            final DisplayTransformManager dtm = getLocalService(DisplayTransformManager.class);
            final float[] from = dtm.getColorMatrix(LEVEL_COLOR_MATRIX_NIGHT_DISPLAY);
            final float[] to = mIsActivated ? MATRIX_NIGHT : null;

            mColorMatrixAnimator = ValueAnimator.ofObject(COLOR_MATRIX_EVALUATOR,
                    from == null ? MATRIX_IDENTITY : from, to == null ? MATRIX_IDENTITY : to);
            mColorMatrixAnimator.setDuration(getContext().getResources()
                    .getInteger(android.R.integer.config_longAnimTime));
            mColorMatrixAnimator.setInterpolator(AnimationUtils.loadInterpolator(
                    getContext(), android.R.interpolator.fast_out_slow_in));
            mColorMatrixAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animator) {
                    final float[] value = (float[]) animator.getAnimatedValue();
                    dtm.setColorMatrix(LEVEL_COLOR_MATRIX_NIGHT_DISPLAY, value);
                }
            });
            mColorMatrixAnimator.addListener(new AnimatorListenerAdapter() {

                private boolean mIsCancelled;

                @Override
                public void onAnimationCancel(Animator animator) {
                    mIsCancelled = true;
                }

                @Override
                public void onAnimationEnd(Animator animator) {
                    if (!mIsCancelled) {
                        // Ensure final color matrix is set at the end of the animation. If the
                        // animation is cancelled then don't set the final color matrix so the new
                        // animator can pick up from where this one left off.
                        dtm.setColorMatrix(LEVEL_COLOR_MATRIX_NIGHT_DISPLAY, to);
                    }
                    mColorMatrixAnimator = null;
                }
            });
            mColorMatrixAnimator.start();
        }
    }

    @Override
    public void onAutoModeChanged(int autoMode) {
        if (mAutoMode != null) {
            mAutoMode.onStop();
            mAutoMode = null;
        }

        if (autoMode == NightDisplayController.AUTO_MODE_CUSTOM) {
            mAutoMode = new CustomAutoMode();
        } else if (autoMode == NightDisplayController.AUTO_MODE_TWILIGHT) {
            mAutoMode = new TwilightAutoMode();
        }

        if (mAutoMode != null) {
            mAutoMode.onStart();
        }
    }

    @Override
    public void onCustomStartTimeChanged(NightDisplayController.LocalTime startTime) {
        if (mAutoMode != null) {
            mAutoMode.onCustomStartTimeChanged(startTime);
        }
    }

    @Override
    public void onCustomEndTimeChanged(NightDisplayController.LocalTime endTime) {
        if (mAutoMode != null) {
            mAutoMode.onCustomEndTimeChanged(endTime);
        }
    }

    private abstract class AutoMode implements NightDisplayController.Callback {
        public abstract void onStart();
        public abstract void onStop();
    }

    private class CustomAutoMode extends AutoMode implements AlarmManager.OnAlarmListener {

        private final AlarmManager mAlarmManager;
        private final BroadcastReceiver mTimeChangedReceiver;

        private NightDisplayController.LocalTime mStartTime;
        private NightDisplayController.LocalTime mEndTime;

        private Calendar mLastActivatedTime;

        public CustomAutoMode() {
            mAlarmManager = (AlarmManager) getContext().getSystemService(Context.ALARM_SERVICE);
            mTimeChangedReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    updateActivated();
                }
            };
        }

        private void updateActivated() {
            final Calendar now = Calendar.getInstance();
            final Calendar startTime = mStartTime.getDateTimeBefore(now);
            final Calendar endTime = mEndTime.getDateTimeAfter(startTime);
            final boolean activated = now.before(endTime);

            boolean setActivated = mIsActivated == null || mLastActivatedTime == null;
            if (!setActivated && mIsActivated != activated) {
                final TimeZone currentTimeZone = now.getTimeZone();
                if (!currentTimeZone.equals(mLastActivatedTime.getTimeZone())) {
                    final int year = mLastActivatedTime.get(Calendar.YEAR);
                    final int dayOfYear = mLastActivatedTime.get(Calendar.DAY_OF_YEAR);
                    final int hourOfDay = mLastActivatedTime.get(Calendar.HOUR_OF_DAY);
                    final int minute = mLastActivatedTime.get(Calendar.MINUTE);

                    mLastActivatedTime.setTimeZone(currentTimeZone);
                    mLastActivatedTime.set(Calendar.YEAR, year);
                    mLastActivatedTime.set(Calendar.DAY_OF_YEAR, dayOfYear);
                    mLastActivatedTime.set(Calendar.HOUR_OF_DAY, hourOfDay);
                    mLastActivatedTime.set(Calendar.MINUTE, minute);
                }

                if (mIsActivated) {
                    setActivated = now.before(mStartTime.getDateTimeBefore(mLastActivatedTime))
                            || now.after(mEndTime.getDateTimeAfter(mLastActivatedTime));
                } else {
                    setActivated = now.before(mEndTime.getDateTimeBefore(mLastActivatedTime))
                            || now.after(mStartTime.getDateTimeAfter(mLastActivatedTime));
                }
            }

            if (setActivated) {
                mController.setActivated(activated);
            }
            updateNextAlarm(mIsActivated, now);
        }

        private void updateNextAlarm(@Nullable Boolean activated, @NonNull Calendar now) {
            if (activated != null) {
                final Calendar next = activated ? mEndTime.getDateTimeAfter(now)
                        : mStartTime.getDateTimeAfter(now);
                mAlarmManager.setExact(AlarmManager.RTC, next.getTimeInMillis(), TAG, this, null);
            }
        }

        @Override
        public void onStart() {
            final IntentFilter intentFilter = new IntentFilter(Intent.ACTION_TIME_CHANGED);
            intentFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            getContext().registerReceiver(mTimeChangedReceiver, intentFilter);

            mStartTime = mController.getCustomStartTime();
            mEndTime = mController.getCustomEndTime();

            // Force an update to initialize state.
            updateActivated();
        }

        @Override
        public void onStop() {
            getContext().unregisterReceiver(mTimeChangedReceiver);

            mAlarmManager.cancel(this);
            mLastActivatedTime = null;
        }

        @Override
        public void onActivated(boolean activated) {
            final Calendar now = Calendar.getInstance();
            if (mIsActivated != null) {
                mLastActivatedTime = now;
            }
            updateNextAlarm(activated, now);
        }

        @Override
        public void onCustomStartTimeChanged(NightDisplayController.LocalTime startTime) {
            mStartTime = startTime;
            mLastActivatedTime = null;
            updateActivated();
        }

        @Override
        public void onCustomEndTimeChanged(NightDisplayController.LocalTime endTime) {
            mEndTime = endTime;
            mLastActivatedTime = null;
            updateActivated();
        }

        @Override
        public void onAlarm() {
            if (DEBUG) Slog.d(TAG, "onAlarm");
            updateActivated();
        }
    }

    private class TwilightAutoMode extends AutoMode implements TwilightListener {

        private final TwilightManager mTwilightManager;

        private Calendar mLastActivatedTime;

        public TwilightAutoMode() {
            mTwilightManager = getLocalService(TwilightManager.class);
        }

        private void updateActivated(TwilightState state) {
            final boolean isNight = state != null && state.isNight();
            boolean setActivated = mIsActivated == null || mIsActivated != isNight;
            if (setActivated && state != null && mLastActivatedTime != null) {
                final Calendar sunrise = state.sunrise();
                final Calendar sunset = state.sunset();
                if (sunrise.before(sunset)) {
                    setActivated = mLastActivatedTime.before(sunrise)
                            || mLastActivatedTime.after(sunset);
                } else {
                    setActivated = mLastActivatedTime.before(sunset)
                            || mLastActivatedTime.after(sunrise);
                }
            }

            if (setActivated) {
                mController.setActivated(isNight);
            }
        }

        @Override
        public void onStart() {
            mTwilightManager.registerListener(this, mHandler);

            // Force an update to initialize state.
            updateActivated(mTwilightManager.getLastTwilightState());
        }

        @Override
        public void onStop() {
            mTwilightManager.unregisterListener(this);
            mLastActivatedTime = null;
        }

        @Override
        public void onActivated(boolean activated) {
            if (mIsActivated != null) {
                mLastActivatedTime = Calendar.getInstance();
            }
        }

        @Override
        public void onTwilightStateChanged(@Nullable TwilightState state) {
            if (DEBUG) Slog.d(TAG, "onTwilightStateChanged");
            updateActivated(state);
        }
    }

    /**
     * Interpolates between two 4x4 color transform matrices (in column-major order).
     */
    private static class ColorMatrixEvaluator implements TypeEvaluator<float[]> {

        /**
         * Result matrix returned by {@link #evaluate(float, float[], float[])}.
         */
        private final float[] mResultMatrix = new float[16];

        @Override
        public float[] evaluate(float fraction, float[] startValue, float[] endValue) {
            for (int i = 0; i < mResultMatrix.length; i++) {
                mResultMatrix[i] = MathUtils.lerp(startValue[i], endValue[i], fraction);
            }
            return mResultMatrix;
        }
    }
}
