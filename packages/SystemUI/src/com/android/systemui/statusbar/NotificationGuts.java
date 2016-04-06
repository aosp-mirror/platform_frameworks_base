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

import android.app.INotificationManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.SeekBar;
import android.widget.TextView;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.settingslib.Utils;
import com.android.systemui.R;
import com.android.systemui.tuner.TunerService;

/**
 * The guts of a notification revealed when performing a long press.
 */
public class NotificationGuts extends LinearLayout implements TunerService.Tunable {
    public static final String SHOW_SLIDER = "show_importance_slider";

    private Drawable mBackground;
    private int mClipTopAmount;
    private int mActualHeight;
    private boolean mExposed;
    private INotificationManager mINotificationManager;
    private int mStartingImportance;
    private boolean mShowSlider;

    private SeekBar mSeekBar;
    private RadioButton mBlock;
    private RadioButton mSilent;
    private RadioButton mReset;

    public NotificationGuts(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        TunerService.get(mContext).addTunable(this, SHOW_SLIDER);
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
            final ExpandableNotificationRow row, final int importance) {
        mStartingImportance = importance;
        mINotificationManager = INotificationManager.Stub.asInterface(
                ServiceManager.getService(Context.NOTIFICATION_SERVICE));
        boolean systemApp = false;
        try {
            final PackageInfo info =
                    pm.getPackageInfo(sbn.getPackageName(), PackageManager.GET_SIGNATURES);
            systemApp = Utils.isSystemPackage(pm, info);
        } catch (PackageManager.NameNotFoundException e) {
            // unlikely.
        }

        final View importanceSlider = row.findViewById(R.id.importance_slider);
        final View importanceButtons = row.findViewById(R.id.importance_buttons);
        if (mShowSlider) {
            bindSlider(importanceSlider, sbn, systemApp);
            importanceSlider.setVisibility(View.VISIBLE);
            importanceButtons.setVisibility(View.GONE);
        } else {
            mStartingImportance = NotificationListenerService.Ranking.IMPORTANCE_UNSPECIFIED;
            try {
                mStartingImportance =
                        mINotificationManager.getImportance(sbn.getPackageName(), sbn.getUid());
            } catch (RemoteException e) {}
            bindToggles(importanceButtons, mStartingImportance, systemApp);
            importanceButtons.setVisibility(View.VISIBLE);
            importanceSlider.setVisibility(View.GONE);
        }
    }

    public boolean hasImportanceChanged() {
        return mStartingImportance != getSelectedImportance();
    }

    void saveImportance(final StatusBarNotification sbn) {
        int progress = getSelectedImportance();
        MetricsLogger.action(mContext, MetricsEvent.ACTION_SAVE_IMPORTANCE,
                progress - mStartingImportance);
        try {
            mINotificationManager.setImportance(sbn.getPackageName(), sbn.getUid(), progress);
        } catch (RemoteException e) {
            // :(
        }
    }

    private int getSelectedImportance() {
        if (mSeekBar!= null && mSeekBar.isShown()) {
            return mSeekBar.getProgress();
        } else {
            if (mBlock.isChecked()) {
                return NotificationListenerService.Ranking.IMPORTANCE_NONE;
            } else if (mSilent.isChecked()) {
                return NotificationListenerService.Ranking.IMPORTANCE_LOW;
            } else {
                return NotificationListenerService.Ranking.IMPORTANCE_UNSPECIFIED;
            }
        }
    }

    private void bindToggles(final View importanceButtons, final int importance,
            final boolean systemApp) {
        mBlock = (RadioButton) importanceButtons.findViewById(R.id.block_importance);
        mSilent = (RadioButton) importanceButtons.findViewById(R.id.silent_importance);
        mReset = (RadioButton) importanceButtons.findViewById(R.id.reset_importance);
        if (systemApp) {
            mBlock.setVisibility(View.GONE);
            mReset.setText(mContext.getString(R.string.do_not_silence));
        } else {
            mReset.setText(mContext.getString(R.string.do_not_silence_block));
        }
        if (importance == NotificationListenerService.Ranking.IMPORTANCE_LOW) {
            mSilent.setChecked(true);
        } else {
            mReset.setChecked(true);
        }
    }

    private void bindSlider(final View importanceSlider, final StatusBarNotification sbn,
            final boolean systemApp) {
        final TextView importanceSummary = ((TextView) importanceSlider.findViewById(R.id.summary));
        final TextView importanceTitle = ((TextView) importanceSlider.findViewById(R.id.title));
        mSeekBar = (SeekBar) importanceSlider.findViewById(R.id.seekbar);

        if (systemApp) {
            ((ImageView) importanceSlider.findViewById(R.id.low_importance)).getDrawable().setTint(
                    mContext.getColor(R.color.notification_guts_disabled_icon_tint));
        }
        final int minProgress = systemApp ?
                NotificationListenerService.Ranking.IMPORTANCE_MIN
                : NotificationListenerService.Ranking.IMPORTANCE_NONE;
        mSeekBar.setMax(NotificationListenerService.Ranking.IMPORTANCE_MAX);
        mSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
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
                // no-op
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // no-op
            }

            private void updateTitleAndSummary(int progress) {
                switch (progress) {
                    case NotificationListenerService.Ranking.IMPORTANCE_NONE:
                        importanceSummary.setText(mContext.getString(
                                R.string.notification_importance_blocked));
                        importanceTitle.setText(mContext.getString(R.string.blocked_importance));
                        break;
                    case NotificationListenerService.Ranking.IMPORTANCE_MIN:
                        importanceSummary.setText(mContext.getString(
                                R.string.notification_importance_min));
                        importanceTitle.setText(mContext.getString(R.string.min_importance));
                        break;
                    case NotificationListenerService.Ranking.IMPORTANCE_LOW:
                        importanceSummary.setText(mContext.getString(
                                R.string.notification_importance_low));
                        importanceTitle.setText(mContext.getString(R.string.low_importance));
                        break;
                    case NotificationListenerService.Ranking.IMPORTANCE_DEFAULT:
                        importanceSummary.setText(mContext.getString(
                                R.string.notification_importance_default));
                        importanceTitle.setText(mContext.getString(R.string.default_importance));
                        break;
                    case NotificationListenerService.Ranking.IMPORTANCE_HIGH:
                        importanceSummary.setText(mContext.getString(
                                R.string.notification_importance_high));
                        importanceTitle.setText(mContext.getString(R.string.high_importance));
                        break;
                    case NotificationListenerService.Ranking.IMPORTANCE_MAX:
                        importanceSummary.setText(mContext.getString(
                                R.string.notification_importance_max));
                        importanceTitle.setText(mContext.getString(R.string.max_importance));
                        break;
                }
            }
        });
        mSeekBar.setProgress(mStartingImportance);
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

    public void setExposed(boolean exposed) {
        mExposed = exposed;
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
