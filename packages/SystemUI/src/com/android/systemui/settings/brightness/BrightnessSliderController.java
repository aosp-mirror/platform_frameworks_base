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

package com.android.systemui.settings.brightness;

import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;

import androidx.annotation.Nullable;

import com.android.internal.logging.UiEventLogger;
import com.android.settingslib.RestrictedLockUtils;
import com.android.systemui.Gefingerpoken;
import com.android.systemui.classifier.Classifier;
import com.android.systemui.haptics.slider.HapticSliderViewBinder;
import com.android.systemui.haptics.slider.SeekbarHapticPlugin;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.statusbar.policy.BrightnessMirrorController;
import com.android.systemui.util.ViewController;
import com.android.systemui.util.time.SystemClock;

import javax.inject.Inject;

/**
 * {@code ViewController} for a {@code BrightnessSliderView}
 *
 * This class allows to control the views of a {@code BrightnessSliderViewView} and get callbacks
 * when the views change value. It also propagates and manipulates another {@link ToggleSlider} as a
 * mirror.
 *
 * @see BrightnessMirrorController
 */
public class BrightnessSliderController extends ViewController<BrightnessSliderView> implements
        ToggleSlider {

    private Listener mListener;
    @Nullable
    private ToggleSlider mMirror;
    @Nullable
    private MirrorController mMirrorController;
    private boolean mTracking;
    private final FalsingManager mFalsingManager;
    private final UiEventLogger mUiEventLogger;

    private final SeekbarHapticPlugin mBrightnessSliderHapticPlugin;
    private final ActivityStarter mActivityStarter;

    private final Gefingerpoken mOnInterceptListener = new Gefingerpoken() {
        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            mBrightnessSliderHapticPlugin.onTouchEvent(ev);
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                mFalsingManager.isFalseTouch(Classifier.BRIGHTNESS_SLIDER);
            }
            return false;
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            return false;
        }
    };

    BrightnessSliderController(
            BrightnessSliderView brightnessSliderView,
            FalsingManager falsingManager,
            UiEventLogger uiEventLogger,
            SeekbarHapticPlugin brightnessSliderHapticPlugin,
            ActivityStarter activityStarter) {
        super(brightnessSliderView);
        mFalsingManager = falsingManager;
        mUiEventLogger = uiEventLogger;
        mBrightnessSliderHapticPlugin = brightnessSliderHapticPlugin;
        mActivityStarter = activityStarter;
    }

    /**
     * Returns a top level view in the hierarchy that can be attached where necessary
     */
    public View getRootView() {
        return mView;
    }


    @Override
    protected void onViewAttached() {
        mView.setOnSeekBarChangeListener(mSeekListener);
        mView.setOnInterceptListener(mOnInterceptListener);
        if (mMirror != null) {
            mView.setOnDispatchTouchEventListener(this::mirrorTouchEvent);
        }
    }

    @Override
    protected void onViewDetached() {
        mView.setOnSeekBarChangeListener(null);
        mView.setOnDispatchTouchEventListener(null);
        mView.setOnInterceptListener(null);
    }

    @Override
    public boolean mirrorTouchEvent(MotionEvent ev) {
        if (mMirror != null) {
            return copyEventToMirror(ev);
        } else {
            // We are the mirror, so we have to dispatch the event
            return mView.dispatchTouchEvent(ev);
        }
    }

    private boolean copyEventToMirror(MotionEvent ev) {
        MotionEvent copy = ev.copy();
        boolean out = false;
        if (mMirror != null) {
            out = mMirror.mirrorTouchEvent(copy);
        }
        copy.recycle();
        return out;
    }

    @Override
    public void setEnforcedAdmin(RestrictedLockUtils.EnforcedAdmin admin) {
        if (admin == null) {
            mView.setAdminBlocker(null);
        } else {
            mView.setAdminBlocker(() -> {
                Intent intent = RestrictedLockUtils.getShowAdminSupportDetailsIntent(admin);
                mActivityStarter.postStartActivityDismissingKeyguard(intent, 0);
                return true;
            });
        }
    }

    private void setMirror(ToggleSlider toggleSlider) {
        mMirror = toggleSlider;
        if (mMirror != null) {
            mMirror.setMax(mView.getMax());
            mMirror.setValue(mView.getValue());
            mView.setOnDispatchTouchEventListener(this::mirrorTouchEvent);
        } else {
            // If there's no mirror, we may be the ones dispatching, events but we should not mirror
            // them
            mView.setOnDispatchTouchEventListener(null);
        }
    }

    /**
     * This will set the mirror from the controller using
     * {@link BrightnessMirrorController#getToggleSlider} as a side-effect.
     * @param c
     */
    @Override
    public void setMirrorControllerAndMirror(@Nullable MirrorController c) {
        mMirrorController = c;
        if (c != null) {
            setMirror(c.getToggleSlider());
        } else {
            setMirror(null);
        }
    }

    @Override
    public void setOnChangedListener(Listener l) {
        mListener = l;
    }

    @Override
    public void setMax(int max) {
        mView.setMax(max);
        if (mMirror != null) {
            mMirror.setMax(max);
        }
    }

    @Override
    public int getMax() {
        return mView.getMax();
    }

    @Override
    public void setValue(int value) {
        mView.setValue(value);
        if (mMirror != null) {
            mMirror.setValue(value);
        }
    }

    @Override
    public int getValue() {
        return mView.getValue();
    }

    @Override
    public void hideView() {
        mView.setVisibility(View.GONE);
    }

    @Override
    public void showView() {
        mView.setVisibility(View.VISIBLE);
    }

    @Override
    public boolean isVisible() {
        // this should be called rarely - once or twice per slider's value change, but not for
        // every value change when user slides finger - only the final one.
        // If view is not visible this call is quick (around 50 µs) as it sees parent is not visible
        // otherwise it's slightly longer (70 µs) because there are more checks to be done
        return mView.isVisibleToUser();
    }

    private final SeekBar.OnSeekBarChangeListener mSeekListener =
            new SeekBar.OnSeekBarChangeListener() {
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            if (mListener != null) {
                mListener.onChanged(mTracking, progress, false);
                if (fromUser) {
                    mBrightnessSliderHapticPlugin.onProgressChanged(seekBar, progress, fromUser);
                }
            }
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
            mTracking = true;
            mUiEventLogger.log(BrightnessSliderEvent.BRIGHTNESS_SLIDER_STARTED_TRACKING_TOUCH);
            if (mListener != null) {
                mListener.onChanged(mTracking, getValue(), false);
                mBrightnessSliderHapticPlugin.onStartTrackingTouch(seekBar);
            }

            if (mMirrorController != null) {
                mMirrorController.showMirror();
                mMirrorController.setLocationAndSize(mView);
            }
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            mTracking = false;
            mUiEventLogger.log(BrightnessSliderEvent.BRIGHTNESS_SLIDER_STOPPED_TRACKING_TOUCH);
            if (mListener != null) {
                mListener.onChanged(mTracking, getValue(), true);
                mBrightnessSliderHapticPlugin.onStopTrackingTouch(seekBar);
            }

            if (mMirrorController != null) {
                mMirrorController.hideMirror();
            }
        }
    };

    /**
     * Creates a {@link BrightnessSliderController} with its associated view.
     */
    public static class Factory {

        private final FalsingManager mFalsingManager;
        private final UiEventLogger mUiEventLogger;
        private final VibratorHelper mVibratorHelper;
        private final SystemClock mSystemClock;
        private final ActivityStarter mActivityStarter;

        @Inject
        public Factory(
                FalsingManager falsingManager,
                UiEventLogger uiEventLogger,
                VibratorHelper vibratorHelper,
                SystemClock clock,
                ActivityStarter activityStarter
        ) {
            mFalsingManager = falsingManager;
            mUiEventLogger = uiEventLogger;
            mVibratorHelper = vibratorHelper;
            mSystemClock = clock;
            mActivityStarter = activityStarter;
        }

        /**
         * Creates the view hierarchy and controller
         *
         * @param context a {@link Context} to inflate the hierarchy
         * @param viewRoot the {@link ViewGroup} that will contain the hierarchy. The inflated
         *                 hierarchy will not be attached
         */
        public BrightnessSliderController create(
                Context context,
                @Nullable ViewGroup viewRoot) {
            int layout = getLayout();
            BrightnessSliderView root = (BrightnessSliderView) LayoutInflater.from(context)
                    .inflate(layout, viewRoot, false);
            SeekbarHapticPlugin plugin = new SeekbarHapticPlugin(
                    mVibratorHelper,
                    mSystemClock);
            HapticSliderViewBinder.bind(viewRoot, plugin);
            return new BrightnessSliderController(
                    root, mFalsingManager, mUiEventLogger, plugin, mActivityStarter);
        }

        /** Get the layout to inflate based on what slider to use */
        private int getLayout() {
            return R.layout.quick_settings_brightness_dialog;
        }
    }
}
