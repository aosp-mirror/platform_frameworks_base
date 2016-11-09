/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.statusbar;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.INotificationManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.StatusBarNotification;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.settingslib.Utils;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.statusbar.stack.StackStateAnimator;
import com.android.systemui.tuner.TunerService;

import java.util.Set;

/**
 * The guts of a notification revealed when performing a long press.
 */
public class NotificationGuts extends LinearLayout implements TunerService.Tunable {
    public static final String SHOW_SLIDER = "show_importance_slider";

    private static final long CLOSE_GUTS_DELAY = 8000;

    private Drawable mBackground;
    private int mClipTopAmount;
    private int mActualHeight;
    private boolean mExposed;
    private INotificationManager mINotificationManager;
    private int mStartingUserImportance;
    private int mNotificationImportance;
    private boolean mShowSlider;

    private SeekBar mSeekBar;
    private ImageView mAutoButton;
    private ColorStateList mActiveSliderTint;
    private ColorStateList mInactiveSliderTint;
    private float mActiveSliderAlpha = 1.0f;
    private float mInactiveSliderAlpha;
    private TextView mImportanceSummary;
    private TextView mImportanceTitle;
    private boolean mAuto;

    private RadioButton mBlock;
    private RadioButton mSilent;
    private RadioButton mReset;

    private Handler mHandler;
    private Runnable mFalsingCheck;
    private boolean mNeedsFalsingProtection;
    private OnGutsClosedListener mListener;

    public interface OnGutsClosedListener {
        public void onGutsClosed(NotificationGuts guts);
    }

    public NotificationGuts(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        mHandler = new Handler();
        mFalsingCheck = new Runnable() {
            @Override
            public void run() {
                if (mNeedsFalsingProtection && mExposed) {
                    closeControls(-1 /* x */, -1 /* y */, true /* notify */);
                }
            }
        };
        final TypedArray ta =
                context.obtainStyledAttributes(attrs, com.android.internal.R.styleable.Theme, 0, 0);
        mInactiveSliderAlpha =
                ta.getFloat(com.android.internal.R.styleable.Theme_disabledAlpha, 0.5f);
        ta.recycle();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        TunerService.get(mContext).addTunable(this, SHOW_SLIDER);
    }

    @Override
    protected void onDetachedFromWindow() {
        TunerService.get(mContext).removeTunable(this);
        super.onDetachedFromWindow();
    }

    public void resetFalsingCheck() {
        mHandler.removeCallbacks(mFalsingCheck);
        if (mNeedsFalsingProtection && mExposed) {
            mHandler.postDelayed(mFalsingCheck, CLOSE_GUTS_DELAY);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        draw(canvas, mBackground);
    }

    private void draw(Canvas canvas, Drawable drawable) {
        if (drawable != null) {
            drawable.setBounds(0, mClipTopAmount, getWidth(), mActualHeight);
            drawable.draw(canvas);
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mBackground = mContext.getDrawable(R.drawable.notification_guts_bg);
        if (mBackground != null) {
            mBackground.setCallback(this);
        }
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return super.verifyDrawable(who) || who == mBackground;
    }

    @Override
    protected void drawableStateChanged() {
        drawableStateChanged(mBackground);
    }

    private void drawableStateChanged(Drawable d) {
        if (d != null && d.isStateful()) {
            d.setState(getDrawableState());
        }
    }

    @Override
    public void drawableHotspotChanged(float x, float y) {
        if (mBackground != null) {
            mBackground.setHotspot(x, y);
        }
    }

    void bindImportance(final PackageManager pm, final StatusBarNotification sbn,
            final Set<String> nonBlockablePkgs, final int importance) {
        mINotificationManager = INotificationManager.Stub.asInterface(
                ServiceManager.getService(Context.NOTIFICATION_SERVICE));
        mStartingUserImportance = NotificationListenerService.Ranking.IMPORTANCE_UNSPECIFIED;
        try {
            mStartingUserImportance =
                    mINotificationManager.getImportance(sbn.getPackageName(), sbn.getUid());
        } catch (RemoteException e) {}
        mNotificationImportance = importance;

        final View importanceSlider = findViewById(R.id.importance_slider);
        final View importanceButtons = findViewById(R.id.importance_buttons);
        final View cantTouchThis = findViewById(R.id.cant_silence_or_block);

        final boolean essentialPackage =
                (nonBlockablePkgs != null && nonBlockablePkgs.contains(sbn.getPackageName()));
        if (essentialPackage) {
            importanceButtons.setVisibility(View.GONE);
            importanceSlider.setVisibility(View.GONE);
            cantTouchThis.setVisibility(View.VISIBLE);
        } else {
            cantTouchThis.setVisibility(View.GONE);

            boolean nonBlockable = false;
            try {
                final PackageInfo info =
                        pm.getPackageInfo(sbn.getPackageName(), PackageManager.GET_SIGNATURES);
                nonBlockable = Utils.isSystemPackage(getResources(), pm, info);
            } catch (PackageManager.NameNotFoundException e) {
                // unlikely.
            }

            if (mShowSlider) {
                bindSlider(importanceSlider, nonBlockable);
                importanceSlider.setVisibility(View.VISIBLE);
                importanceButtons.setVisibility(View.GONE);
            } else {
                bindToggles(importanceButtons, mStartingUserImportance, nonBlockable);
                importanceButtons.setVisibility(View.VISIBLE);
                importanceSlider.setVisibility(View.GONE);
            }
        }
    }

    public boolean hasImportanceChanged() {
        return mStartingUserImportance != getSelectedImportance();
    }

    void saveImportance(final StatusBarNotification sbn) {
        int progress = getSelectedImportance();
        MetricsLogger.action(mContext, MetricsEvent.ACTION_SAVE_IMPORTANCE,
                progress - mStartingUserImportance);
        try {
            mINotificationManager.setImportance(sbn.getPackageName(), sbn.getUid(), progress);
        } catch (RemoteException e) {
            // :(
        }
    }

    private int getSelectedImportance() {
        if (mSeekBar != null && mSeekBar.isShown()) {
            if (mSeekBar.isEnabled()) {
                return mSeekBar.getProgress();
            } else {
                return Ranking.IMPORTANCE_UNSPECIFIED;
            }
        } else {
            if (mBlock != null && mBlock.isChecked()) {
                return Ranking.IMPORTANCE_NONE;
            } else if (mSilent != null && mSilent.isChecked()) {
                return Ranking.IMPORTANCE_LOW;
            } else {
                return Ranking.IMPORTANCE_UNSPECIFIED;
            }
        }
    }

    private void bindToggles(final View importanceButtons, final int importance,
            final boolean nonBlockable) {
        ((RadioGroup) importanceButtons).setOnCheckedChangeListener(
                new RadioGroup.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(RadioGroup group, int checkedId) {
                        resetFalsingCheck();
                    }
                });
        mBlock = (RadioButton) importanceButtons.findViewById(R.id.block_importance);
        mSilent = (RadioButton) importanceButtons.findViewById(R.id.silent_importance);
        mReset = (RadioButton) importanceButtons.findViewById(R.id.reset_importance);
        if (nonBlockable) {
            mBlock.setVisibility(View.GONE);
            mReset.setText(mContext.getString(R.string.do_not_silence));
        } else {
            mReset.setText(mContext.getString(R.string.do_not_silence_block));
        }
        mBlock.setText(mContext.getString(R.string.block));
        mSilent.setText(mContext.getString(R.string.show_silently));
        if (importance == NotificationListenerService.Ranking.IMPORTANCE_LOW) {
            mSilent.setChecked(true);
        } else {
            mReset.setChecked(true);
        }
    }

    private void bindSlider(final View importanceSlider, final boolean nonBlockable) {
        mActiveSliderTint = ColorStateList.valueOf(Utils.getColorAccent(mContext));
        mInactiveSliderTint = loadColorStateList(R.color.notification_guts_disabled_slider_color);

        mImportanceSummary = ((TextView) importanceSlider.findViewById(R.id.summary));
        mImportanceTitle = ((TextView) importanceSlider.findViewById(R.id.title));
        mSeekBar = (SeekBar) importanceSlider.findViewById(R.id.seekbar);

        final int minProgress = nonBlockable ?
                NotificationListenerService.Ranking.IMPORTANCE_MIN
                : NotificationListenerService.Ranking.IMPORTANCE_NONE;
        mSeekBar.setMax(NotificationListenerService.Ranking.IMPORTANCE_MAX);
        mSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                resetFalsingCheck();
                if (progress < minProgress) {
                    seekBar.setProgress(minProgress);
                    progress = minProgress;
                }
                updateTitleAndSummary(progress);
                if (fromUser) {
                    MetricsLogger.action(mContext, MetricsEvent.ACTION_MODIFY_IMPORTANCE_SLIDER);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                resetFalsingCheck();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // no-op
            }


        });
        mSeekBar.setProgress(mNotificationImportance);

        mAutoButton = (ImageView) importanceSlider.findViewById(R.id.auto_importance);
        mAutoButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                mAuto = !mAuto;
                applyAuto();
            }
        });
        mAuto = mStartingUserImportance == Ranking.IMPORTANCE_UNSPECIFIED;
        applyAuto();
    }

    private void applyAuto() {
        mSeekBar.setEnabled(!mAuto);

        final ColorStateList starTint = mAuto ?  mActiveSliderTint : mInactiveSliderTint;
        final float alpha = mAuto ? mInactiveSliderAlpha : mActiveSliderAlpha;
        Drawable icon = mAutoButton.getDrawable().mutate();
        icon.setTintList(starTint);
        mAutoButton.setImageDrawable(icon);
        mSeekBar.setAlpha(alpha);

        if (mAuto) {
            mSeekBar.setProgress(mNotificationImportance);
            mImportanceSummary.setText(mContext.getString(
                    R.string.notification_importance_user_unspecified));
            mImportanceTitle.setText(mContext.getString(
                    R.string.user_unspecified_importance));
        } else {
            updateTitleAndSummary(mSeekBar.getProgress());
        }
    }

    private void updateTitleAndSummary(int progress) {
        switch (progress) {
            case Ranking.IMPORTANCE_NONE:
                mImportanceSummary.setText(mContext.getString(
                        R.string.notification_importance_blocked));
                mImportanceTitle.setText(mContext.getString(R.string.blocked_importance));
                break;
            case Ranking.IMPORTANCE_MIN:
                mImportanceSummary.setText(mContext.getString(
                        R.string.notification_importance_min));
                mImportanceTitle.setText(mContext.getString(R.string.min_importance));
                break;
            case Ranking.IMPORTANCE_LOW:
                mImportanceSummary.setText(mContext.getString(
                        R.string.notification_importance_low));
                mImportanceTitle.setText(mContext.getString(R.string.low_importance));
                break;
            case Ranking.IMPORTANCE_DEFAULT:
                mImportanceSummary.setText(mContext.getString(
                        R.string.notification_importance_default));
                mImportanceTitle.setText(mContext.getString(R.string.default_importance));
                break;
            case Ranking.IMPORTANCE_HIGH:
                mImportanceSummary.setText(mContext.getString(
                        R.string.notification_importance_high));
                mImportanceTitle.setText(mContext.getString(R.string.high_importance));
                break;
            case Ranking.IMPORTANCE_MAX:
                mImportanceSummary.setText(mContext.getString(
                        R.string.notification_importance_max));
                mImportanceTitle.setText(mContext.getString(R.string.max_importance));
                break;
        }
    }

    private ColorStateList loadColorStateList(int colorResId) {
        return ColorStateList.valueOf(mContext.getColor(colorResId));
    }

    public void closeControls(int x, int y, boolean notify) {
        if (getWindowToken() == null) {
            if (notify && mListener != null) {
                mListener.onGutsClosed(this);
            }
            return;
        }
        if (x == -1 || y == -1) {
            x = (getLeft() + getRight()) / 2;
            y = (getTop() + getHeight() / 2);
        }
        final double horz = Math.max(getWidth() - x, x);
        final double vert = Math.max(getHeight() - y, y);
        final float r = (float) Math.hypot(horz, vert);
        final Animator a = ViewAnimationUtils.createCircularReveal(this,
                x, y, r, 0);
        a.setDuration(StackStateAnimator.ANIMATION_DURATION_STANDARD);
        a.setInterpolator(Interpolators.FAST_OUT_LINEAR_IN);
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                setVisibility(View.GONE);
            }
        });
        a.start();
        setExposed(false, mNeedsFalsingProtection);
        if (notify && mListener != null) {
            mListener.onGutsClosed(this);
        }
    }

    public void setActualHeight(int actualHeight) {
        mActualHeight = actualHeight;
        invalidate();
    }

    public int getActualHeight() {
        return mActualHeight;
    }

    public void setClipTopAmount(int clipTopAmount) {
        mClipTopAmount = clipTopAmount;
        invalidate();
    }

    @Override
    public boolean hasOverlappingRendering() {
        // Prevents this view from creating a layer when alpha is animating.
        return false;
    }

    public void setClosedListener(OnGutsClosedListener listener) {
        mListener = listener;
    }

    public void setExposed(boolean exposed, boolean needsFalsingProtection) {
        mExposed = exposed;
        mNeedsFalsingProtection = needsFalsingProtection;
        if (mExposed && mNeedsFalsingProtection) {
            resetFalsingCheck();
        } else {
            mHandler.removeCallbacks(mFalsingCheck);
        }
    }

    public boolean areGutsExposed() {
        return mExposed;
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (SHOW_SLIDER.equals(key)) {
            mShowSlider = newValue != null && Integer.parseInt(newValue) != 0;
        }
    }
}
