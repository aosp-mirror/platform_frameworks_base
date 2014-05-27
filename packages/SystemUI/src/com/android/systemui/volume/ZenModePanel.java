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
 * limitations under the License.
 */

package com.android.systemui.volume;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings;
import android.service.notification.Condition;
import android.util.AttributeSet;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.statusbar.policy.ZenModeController;

import java.util.Arrays;
import java.util.HashSet;

public class ZenModePanel extends LinearLayout {
    private static final int[] MINUTES = new int[] { 15, 30, 45, 60, 120, 180, 240, 480 };
    public static final Intent ZEN_SETTINGS = new Intent(Settings.ACTION_ZEN_MODE_SETTINGS);

    private final LayoutInflater mInflater;
    private final HashSet<RadioButton> mRadioButtons = new HashSet<RadioButton>();
    private final H mHandler = new H();
    private LinearLayout mConditions;
    private int mMinutesIndex = Arrays.binarySearch(MINUTES, 60);  // default to one hour
    private Callback mCallback;
    private ZenModeController mController;
    private boolean mRequestingConditions;

    public ZenModePanel(Context context, AttributeSet attrs) {
        super(context, attrs);
        mInflater = LayoutInflater.from(new ContextThemeWrapper(context, R.style.QSWhiteTheme));
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mConditions = (LinearLayout) findViewById(android.R.id.content);
        findViewById(android.R.id.button2).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fireMoreSettings();
            }
        });
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        setRequestingConditions(visibility == VISIBLE);
    }

    /** Start or stop requesting relevant zen mode exit conditions */
    private void setRequestingConditions(boolean requesting) {
        if (mRequestingConditions == requesting) return;
        mRequestingConditions = requesting;
        if (mRequestingConditions) {
            mController.addCallback(mZenCallback);
        } else {
            mController.removeCallback(mZenCallback);
        }
        mController.requestConditions(mRequestingConditions);
    }

    public void init(ZenModeController controller) {
        mController = controller;
        mConditions.removeAllViews();
        bind(updateTimeCondition(), mConditions.getChildAt(0));
        handleUpdateConditions(new Condition[0]);
    }

    public void setCallback(Callback callback) {
        mCallback = callback;
    }

    private Condition updateTimeCondition() {
        final int minutes = MINUTES[mMinutesIndex];
        final long millis = System.currentTimeMillis() + minutes * 60 * 1000;
        final Uri id = new Uri.Builder().scheme(Condition.SCHEME).authority("android")
                .appendPath("countdown").appendPath(Long.toString(millis)).build();
        final int num = minutes < 60 ? minutes : minutes / 60;
        final int resId = minutes < 60
                ? R.plurals.zen_mode_duration_minutes
                : R.plurals.zen_mode_duration_hours;
        final String caption = mContext.getResources().getQuantityString(resId, num, num);
        return new Condition(id, caption, "", "", 0, Condition.STATE_TRUE,
                Condition.FLAG_RELEVANT_NOW);
    }

    private void handleUpdateConditions(Condition[] conditions) {
        final int newCount = conditions == null ? 0 : conditions.length;
        for (int i = mConditions.getChildCount() - 1; i > newCount; i--) {
            mConditions.removeViewAt(i);
        }
        for (int i = 0; i < newCount; i++) {
            bind(conditions[i], mConditions.getChildAt(i + 1));
        }
        bind(null, mConditions.getChildAt(newCount + 1));
    }

    private void editTimeCondition(int delta) {
        final int i = mMinutesIndex + delta;
        if (i < 0 || i >= MINUTES.length) return;
        mMinutesIndex = i;
        final Condition c = updateTimeCondition();
        bind(c, mConditions.getChildAt(0));
    }

    private void bind(final Condition condition, View convertView) {
        final boolean enabled = condition == null || condition.state == Condition.STATE_TRUE;
        final View row;
        if (convertView == null) {
            row = mInflater.inflate(R.layout.zen_mode_condition, this, false);
            mConditions.addView(row);
        } else {
            row = convertView;
        }
        final int position = mConditions.indexOfChild(row);
        final RadioButton rb = (RadioButton) row.findViewById(android.R.id.checkbox);
        mRadioButtons.add(rb);
        rb.setEnabled(enabled);
        rb.setOnCheckedChangeListener(new OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    for (RadioButton otherButton : mRadioButtons) {
                        if (otherButton == rb) continue;
                        otherButton.setChecked(false);
                    }
                    mController.select(condition);
                    fireInteraction();
                }
            }
        });
        final TextView title = (TextView) row.findViewById(android.R.id.title);
        if (condition == null) {
            title.setText(R.string.zen_mode_forever);
        } else {
            title.setText(condition.summary);
        }
        title.setEnabled(enabled);
        title.setAlpha(enabled ? 1 : .5f);
        final ImageView button1 = (ImageView) row.findViewById(android.R.id.button1);
        button1.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                rb.setChecked(true);
                editTimeCondition(-1);
                fireInteraction();
            }
        });

        final ImageView button2 = (ImageView) row.findViewById(android.R.id.button2);
        button2.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                rb.setChecked(true);
                editTimeCondition(1);
                fireInteraction();
            }
        });
        title.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                rb.setChecked(true);
                fireInteraction();
            }
        });
        if (position == 0) {
            button1.setEnabled(mMinutesIndex > 0);
            button2.setEnabled(mMinutesIndex < MINUTES.length - 1);
            button1.setImageAlpha(button1.isEnabled() ? 0xff : 0x7f);
            button2.setImageAlpha(button2.isEnabled() ? 0xff : 0x7f);
        } else {
            button1.setVisibility(View.GONE);
            button2.setVisibility(View.GONE);
        }
        if (position == 0 &&  mConditions.getChildCount() == 1) {
            rb.setChecked(true);
        }
    }

    private void fireMoreSettings() {
        if (mCallback != null) {
            mCallback.onMoreSettings();
        }
    }

    private void fireInteraction() {
        if (mCallback != null) {
            mCallback.onInteraction();
        }
    }

    private final ZenModeController.Callback mZenCallback = new ZenModeController.Callback() {
        @Override
        public void onConditionsChanged(Condition[] conditions) {
            mHandler.obtainMessage(H.UPDATE_CONDITIONS, conditions).sendToTarget();
        }
    };

    private final class H extends Handler {
        private static final int UPDATE_CONDITIONS = 1;

        private H() {
            super(Looper.getMainLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            if (msg.what == UPDATE_CONDITIONS) {
                handleUpdateConditions((Condition[])msg.obj);
            }
        }
    }

    public interface Callback {
        void onMoreSettings();
        void onInteraction();
    }
}
