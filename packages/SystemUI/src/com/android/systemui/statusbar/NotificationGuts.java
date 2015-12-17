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
        final INotificationManager sINM = INotificationManager.Stub.asInterface(
                ServiceManager.getService(Context.NOTIFICATION_SERVICE));
        final Notification.Topic topic = sbn.getNotification().getTopic() == null
                ? new Notification.Topic(Notification.TOPIC_DEFAULT, mContext.getString(
                com.android.internal.R.string.default_notification_topic_label))
                : sbn.getNotification().getTopic();

        final RadioButton applyToTopic = (RadioButton) row.findViewById(R.id.apply_to_topic);
        if (sbn.getNotification().getTopic() != null) {
            applyToTopic.setVisibility(View.VISIBLE);
            applyToTopic.setChecked(true);
            applyToTopic.setText(mContext.getString(R.string.apply_to_topic, topic.getLabel()));
            row.findViewById(R.id.apply_to_app).setVisibility(View.VISIBLE);
        }

        final TextView topicSummary = ((TextView) row.findViewById(R.id.summary));
        final TextView topicTitle = ((TextView) row.findViewById(R.id.title));
        SeekBar seekBar = (SeekBar) row.findViewById(R.id.seekbar);
        seekBar.setMax(4);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateTitleAndSummary(progress);
                if (fromUser) {
                    try {
                        if (applyToTopic.isChecked()) {
                            sINM.setTopicImportance(sbn.getPackageName(), sbn.getUid(), topic,
                                    progress);
                        } else {
                            sINM.setAppImportance(sbn.getPackageName(), sbn.getUid(), progress);
                        }
                    } catch (RemoteException e) {
                        // :(
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
        seekBar.setProgress(importance);
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
}
