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
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;

import androidx.annotation.Nullable;

import com.android.settingslib.RestrictedLockUtils;
import com.android.systemui.Gefingerpoken;
import com.android.systemui.R;
import com.android.systemui.classifier.Classifier;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.statusbar.policy.BrightnessMirrorController;
import com.android.systemui.util.ViewController;

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
public class BrightnessSlider extends ViewController<BrightnessSliderView> implements ToggleSlider {

    private Listener mListener;
    private ToggleSlider mMirror;
    private BrightnessMirrorController mMirrorController;
    private boolean mTracking;
    private final FalsingManager mFalsingManager;

    private final Gefingerpoken mOnInterceptListener = new Gefingerpoken() {
        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
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

    BrightnessSlider(
            BrightnessSliderView brightnessSliderView,
            FalsingManager falsingManager) {
        super(brightnessSliderView);
        mFalsingManager = falsingManager;
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
        boolean out = mMirror.mirrorTouchEvent(copy);
        copy.recycle();
        return out;
    }

    @Override
    public void setEnforcedAdmin(RestrictedLockUtils.EnforcedAdmin admin) {
        mView.setEnforcedAdmin(admin);
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
    public void setMirrorControllerAndMirror(BrightnessMirrorController c) {
        mMirrorController = c;
        if (c != null) {
            setMirror(c.getToggleSlider());
        } else {
            // If there's no mirror, we may be the ones dispatching, events but we should not mirror
            // them
            mView.setOnDispatchTouchEventListener(null);
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

    private final SeekBar.OnSeekBarChangeListener mSeekListener =
            new SeekBar.OnSeekBarChangeListener() {
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            if (mListener != null) {
                mListener.onChanged(mTracking, progress, false);
            }
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
            mTracking = true;

            if (mListener != null) {
                mListener.onChanged(mTracking, getValue(), false);
            }

            if (mMirrorController != null) {
                mMirrorController.showMirror();
                mMirrorController.setLocationAndSize(mView);
            }
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            mTracking = false;

            if (mListener != null) {
                mListener.onChanged(mTracking, getValue(), true);
            }

            if (mMirrorController != null) {
                mMirrorController.hideMirror();
            }
        }
    };

    /**
     * Creates a {@link BrightnessSlider} with its associated view.
     */
    public static class Factory {

        private final FalsingManager mFalsingManager;

        @Inject
        public Factory(FalsingManager falsingManager) {
            mFalsingManager = falsingManager;
        }

        /**
         * Creates the view hierarchy and controller
         *
         * @param context a {@link Context} to inflate the hierarchy
         * @param viewRoot the {@link ViewGroup} that will contain the hierarchy. The inflated
         *                 hierarchy will not be attached
         */
        public BrightnessSlider create(Context context, @Nullable ViewGroup viewRoot) {
            int layout = getLayout();
            BrightnessSliderView root = (BrightnessSliderView) LayoutInflater.from(context)
                    .inflate(layout, viewRoot, false);
            return new BrightnessSlider(root, mFalsingManager);
        }

        /** Get the layout to inflate based on what slider to use */
        private int getLayout() {
            return R.layout.quick_settings_brightness_dialog;
        }
    }
}
