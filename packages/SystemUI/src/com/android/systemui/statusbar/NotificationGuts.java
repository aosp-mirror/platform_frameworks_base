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

import android.annotation.IdRes;
import android.app.INotificationManager;
import android.app.Notification;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import com.android.systemui.R;

/**
 * The guts of a notification revealed when performing a long press.
 */
public class NotificationGuts extends LinearLayout {

    private Drawable mBackground;
    private int mClipTopAmount;
    private int mActualHeight;
    private boolean mExposed;
    private RadioButton mApplyToTopic;
    private SeekBar mSeekBar;
    private Notification.Topic mTopic;
    private INotificationManager mINotificationManager;

    public NotificationGuts(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
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

    void bindImportance(final StatusBarNotification sbn, final ExpandableNotificationRow row,
            final int importance) {
        mINotificationManager = INotificationManager.Stub.asInterface(
                ServiceManager.getService(Context.NOTIFICATION_SERVICE));
        mTopic = sbn.getNotification().getTopic() == null
                ? new Notification.Topic(Notification.TOPIC_DEFAULT, mContext.getString(
                com.android.internal.R.string.default_notification_topic_label))
                : sbn.getNotification().getTopic();
        boolean doesAppUseTopics = false;
        try {
            doesAppUseTopics =
                    mINotificationManager.doesAppUseTopics(sbn.getPackageName(), sbn.getUid());
        } catch (RemoteException e) {}
        final boolean appUsesTopics = doesAppUseTopics;

        mApplyToTopic = (RadioButton) row.findViewById(R.id.apply_to_topic);
        if (appUsesTopics) {
            mApplyToTopic.setChecked(true);
        }
        final View applyToApp = row.findViewById(R.id.apply_to_app);
        final TextView topicSummary = ((TextView) row.findViewById(R.id.summary));
        final TextView topicTitle = ((TextView) row.findViewById(R.id.title));
        mSeekBar = (SeekBar) row.findViewById(R.id.seekbar);
        mSeekBar.setMax(4);
        mSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateTitleAndSummary(progress);
                if (fromUser) {
                    if (appUsesTopics) {
                        mApplyToTopic.setVisibility(View.VISIBLE);
                        mApplyToTopic.setText(
                                mContext.getString(R.string.apply_to_topic, mTopic.getLabel()));
                        applyToApp.setVisibility(View.VISIBLE);
                    }
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
                        topicSummary.setText(mContext.getString(
                                R.string.notification_importance_blocked));
                        topicTitle.setText(mContext.getString(R.string.blocked_importance));
                        break;
                    case NotificationListenerService.Ranking.IMPORTANCE_LOW:
                        topicSummary.setText(mContext.getString(
                                R.string.notification_importance_low));
                        topicTitle.setText(mContext.getString(R.string.low_importance));
                        break;
                    case NotificationListenerService.Ranking.IMPORTANCE_DEFAULT:
                        topicSummary.setText(mContext.getString(
                                R.string.notification_importance_default));
                        topicTitle.setText(mContext.getString(R.string.default_importance));
                        break;
                    case NotificationListenerService.Ranking.IMPORTANCE_HIGH:
                        topicSummary.setText(mContext.getString(
                                R.string.notification_importance_high));
                        topicTitle.setText(mContext.getString(R.string.high_importance));
                        break;
                    case NotificationListenerService.Ranking.IMPORTANCE_MAX:
                        topicSummary.setText(mContext.getString(
                                R.string.notification_importance_max));
                        topicTitle.setText(mContext.getString(R.string.max_importance));
                        break;
                }
            }
        });
        mSeekBar.setProgress(importance);
    }

    void saveImportance(final StatusBarNotification sbn) {
        int progress = mSeekBar.getProgress();
        try {
            if (mApplyToTopic.isChecked()) {
                mINotificationManager.setTopicImportance(sbn.getPackageName(), sbn.getUid(), mTopic,
                        progress);
            } else {
                mINotificationManager.setAppImportance(
                        sbn.getPackageName(), sbn.getUid(), progress);
            }
        } catch (RemoteException e) {
            // :(
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

    public void setExposed(boolean exposed) {
        mExposed = exposed;
    }

    public boolean areGutsExposed() {
        return mExposed;
    }
}
