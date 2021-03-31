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
import android.widget.CompoundButton;
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
public class BrightnessSlider extends ViewController<View> implements ToggleSlider {

    private Listener mListener;
    private ToggleSlider mMirror;
    private final BrightnessSliderView mBrightnessSliderView;
    private BrightnessMirrorController mMirrorController;
    private boolean mTracking;
    private final boolean mUseMirror;
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
            View rootView,
            BrightnessSliderView brightnessSliderView,
            boolean useMirror,
            FalsingManager falsingManager) {
        super(rootView);
        mBrightnessSliderView = brightnessSliderView;
        mUseMirror = useMirror;
        mFalsingManager = falsingManager;
    }

    /**
     * Returns a top level view in the hierarchy that can be attached where necessary
     */
    public View getRootView() {
        return mView;
    }

    private void enableSlider(boolean enable) {
        mBrightnessSliderView.enableSlider(enable);
    }

    @Override
    protected void onViewAttached() {
        mBrightnessSliderView.setOnSeekBarChangeListener(mSeekListener);
        mBrightnessSliderView.setOnCheckedChangeListener(mCheckListener);
        mBrightnessSliderView.setOnInterceptListener(mOnInterceptListener);
    }

    @Override
    protected void onViewDetached() {
        mBrightnessSliderView.setOnSeekBarChangeListener(null);
        mBrightnessSliderView.setOnCheckedChangeListener(null);
        mBrightnessSliderView.setOnDispatchTouchEventListener(null);
        mBrightnessSliderView.setOnInterceptListener(null);
    }

    @Override
    public boolean mirrorTouchEvent(MotionEvent ev) {
        if (mMirror != null) {
            return copyEventToMirror(ev);
        } else {
            // We are the mirror, so we have to dispatch the event
            return mBrightnessSliderView.dispatchTouchEvent(ev);
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
        mBrightnessSliderView.setEnforcedAdmin(admin);
    }

    private void setMirror(ToggleSlider toggleSlider) {
        mMirror = toggleSlider;
        if (mMirror != null) {
            mMirror.setChecked(mBrightnessSliderView.isChecked());
            mMirror.setMax(mBrightnessSliderView.getMax());
            mMirror.setValue(mBrightnessSliderView.getValue());
            mBrightnessSliderView.setOnDispatchTouchEventListener(this::mirrorTouchEvent);
        } else {
            // If there's no mirror, we may be the ones dispatching, events but we should not mirror
            // them
            mBrightnessSliderView.setOnDispatchTouchEventListener(null);
        }
    }

    /**
     * This will set the mirror from the controller using
     * {@link BrightnessMirrorController#getToggleSlider} as a side-effect.
     * @param c
     */
    @Override
    public void setMirrorControllerAndMirror(BrightnessMirrorController c) {
        if (!mUseMirror) return;
        mMirrorController = c;
        if (c != null) {
            setMirror(c.getToggleSlider());
        } else {
            // If there's no mirror, we may be the ones dispatching, events but we should not mirror
            // them
            mBrightnessSliderView.setOnDispatchTouchEventListener(null);
        }
    }

    @Override
    public void setOnChangedListener(Listener l) {
        mListener = l;
    }

    @Override
    public void setChecked(boolean checked) {
        mBrightnessSliderView.setChecked(checked);
    }

    @Override
    public boolean isChecked() {
        return mBrightnessSliderView.isChecked();
    }

    @Override
    public void setMax(int max) {
        mBrightnessSliderView.setMax(max);
        if (mMirror != null) {
            mMirror.setMax(max);
        }
    }

    @Override
    public int getMax() {
        return mBrightnessSliderView.getMax();
    }

    @Override
    public void setValue(int value) {
        mBrightnessSliderView.setValue(value);
        if (mMirror != null) {
            mMirror.setValue(value);
        }
    }

    @Override
    public int getValue() {
        return mBrightnessSliderView.getValue();
    }

    private final SeekBar.OnSeekBarChangeListener mSeekListener =
            new SeekBar.OnSeekBarChangeListener() {
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            if (mListener != null) {
                mListener.onChanged(mTracking, isChecked(), progress, false);
            }
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
            mTracking = true;

            if (mListener != null) {
                mListener.onChanged(mTracking, isChecked(),
                        getValue(), false);
            }

            setChecked(false);

            if (mMirrorController != null) {
                mMirrorController.showMirror();
                mMirrorController.setLocation((View) mBrightnessSliderView.getParent());
            }
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            mTracking = false;

            if (mListener != null) {
                mListener.onChanged(mTracking, isChecked(),
                        getValue(), true);
            }

            if (mMirrorController != null) {
                mMirrorController.hideMirror();
            }
        }
    };

    private final CompoundButton.OnCheckedChangeListener mCheckListener =
            new CompoundButton.OnCheckedChangeListener() {
        @Override
        public void onCheckedChanged(CompoundButton toggle, boolean checked) {
            enableSlider(!checked);

            if (mListener != null) {
                mListener.onChanged(mTracking, checked, getValue(), false);
            }

            if (mMirror != null) {
                mMirror.setChecked(checked);
            }
        }
    };

    /**
     * Creates a {@link BrightnessSlider} with its associated view.
     *
     * The views inflated are determined by {@link BrightnessControllerSettings#useThickSlider()}.
     */
    public static class Factory {

        BrightnessControllerSettings mSettings;
        private final FalsingManager mFalsingManager;

        @Inject
        public Factory(BrightnessControllerSettings settings, FalsingManager falsingManager) {
            mSettings = settings;
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
            ViewGroup root = (ViewGroup) LayoutInflater.from(context)
                    .inflate(layout, viewRoot, false);
            return fromTree(root, mSettings.useMirrorOnThickSlider());
        }

        private BrightnessSlider fromTree(ViewGroup root, boolean useMirror) {
            BrightnessSliderView v = root.requireViewById(R.id.brightness_slider);

            return new BrightnessSlider(root, v, useMirror, mFalsingManager);
        }

        /** Get the layout to inflate based on what slider to use */
        private int getLayout() {
            return mSettings.useThickSlider()
                    ? R.layout.quick_settings_brightness_dialog_thick
                    : R.layout.quick_settings_brightness_dialog;
        }
    }
}
