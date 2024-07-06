/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.settingslib.notification.modes;

import android.app.ActivityManager;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.provider.Settings;
import android.service.notification.Condition;
import android.service.notification.ZenModeConfig;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AlertDialog;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto;
import com.android.internal.policy.PhoneWindow;
import com.android.settingslib.R;

import java.util.Arrays;

public class ZenDurationDialog {
    private static final int[] MINUTE_BUCKETS = ZenModeConfig.MINUTE_BUCKETS;
    @VisibleForTesting
    protected static final int MIN_BUCKET_MINUTES = MINUTE_BUCKETS[0];
    @VisibleForTesting
    protected static final int MAX_BUCKET_MINUTES =
            MINUTE_BUCKETS[MINUTE_BUCKETS.length - 1];
    private static final int DEFAULT_BUCKET_INDEX = Arrays.binarySearch(MINUTE_BUCKETS, 60);
    @VisibleForTesting
    protected int mBucketIndex = -1;

    @VisibleForTesting
    protected static final int FOREVER_CONDITION_INDEX = 0;
    @VisibleForTesting
    protected static final int COUNTDOWN_CONDITION_INDEX = 1;
    @VisibleForTesting
    protected static final int ALWAYS_ASK_CONDITION_INDEX = 2;

    @VisibleForTesting
    protected Context mContext;
    @VisibleForTesting
    protected LinearLayout mZenRadioGroupContent;
    private RadioGroup mZenRadioGroup;
    private static final int MAX_MANUAL_DND_OPTIONS = 3;

    @VisibleForTesting
    protected LayoutInflater mLayoutInflater;

    public ZenDurationDialog(Context context) {
        mContext = context;
    }

    public Dialog createDialog() {
        final AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
        setupDialog(builder);
        return builder.create();
    }

    public void setupDialog(AlertDialog.Builder builder) {
        int zenDuration = Settings.Secure.getInt(
                mContext.getContentResolver(), Settings.Secure.ZEN_DURATION,
                Settings.Secure.ZEN_DURATION_FOREVER);

        builder.setTitle(R.string.zen_mode_duration_settings_title)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.okay,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                updateZenDuration(zenDuration);
                            }
                        });

        View contentView = getContentView();
        setupRadioButtons(zenDuration);
        builder.setView(contentView);
    }

    @VisibleForTesting
    protected void updateZenDuration(int currZenDuration) {
        final int checkedRadioButtonId = mZenRadioGroup.getCheckedRadioButtonId();

        int newZenDuration = Settings.Secure.getInt(
                mContext.getContentResolver(), Settings.Secure.ZEN_DURATION,
                Settings.Secure.ZEN_DURATION_FOREVER);
        switch (checkedRadioButtonId) {
            case FOREVER_CONDITION_INDEX:
                newZenDuration = Settings.Secure.ZEN_DURATION_FOREVER;
                MetricsLogger.action(mContext,
                        MetricsProto.MetricsEvent
                                .NOTIFICATION_ZEN_MODE_DURATION_FOREVER);
                break;
            case COUNTDOWN_CONDITION_INDEX:
                ConditionTag tag = getConditionTagAt(checkedRadioButtonId);
                newZenDuration = tag.countdownZenDuration;
                MetricsLogger.action(mContext,
                        MetricsProto.MetricsEvent
                                .NOTIFICATION_ZEN_MODE_DURATION_TIME,
                        newZenDuration);
                break;
            case ALWAYS_ASK_CONDITION_INDEX:
                newZenDuration = Settings.Secure.ZEN_DURATION_PROMPT;
                MetricsLogger.action(mContext,
                        MetricsProto.MetricsEvent
                                .NOTIFICATION_ZEN_MODE_DURATION_PROMPT);
                break;
        }

        if (currZenDuration != newZenDuration) {
            Settings.Secure.putInt(mContext.getContentResolver(),
                    Settings.Secure.ZEN_DURATION, newZenDuration);
        }
    }

    @VisibleForTesting
    protected View getContentView() {
        if (mLayoutInflater == null) {
            mLayoutInflater = new PhoneWindow(mContext).getLayoutInflater();
        }
        View contentView = mLayoutInflater.inflate(R.layout.zen_mode_duration_dialog,
                null);
        ScrollView container = (ScrollView) contentView.findViewById(R.id.zen_duration_container);

        mZenRadioGroup = container.findViewById(R.id.zen_radio_buttons);
        mZenRadioGroupContent = container.findViewById(R.id.zen_radio_buttons_content);

        for (int i = 0; i < MAX_MANUAL_DND_OPTIONS; i++) {
            final View radioButton = mLayoutInflater.inflate(R.layout.zen_mode_radio_button,
                    mZenRadioGroup, false);
            mZenRadioGroup.addView(radioButton);
            radioButton.setId(i);

            final View radioButtonContent = mLayoutInflater.inflate(R.layout.zen_mode_condition,
                    mZenRadioGroupContent, false);
            radioButtonContent.setId(i + MAX_MANUAL_DND_OPTIONS);
            mZenRadioGroupContent.addView(radioButtonContent);
        }

        return contentView;
    }

    @VisibleForTesting
    protected void setupRadioButtons(int zenDuration) {
        int checkedIndex = ALWAYS_ASK_CONDITION_INDEX;
        if (zenDuration == 0) {
            checkedIndex = FOREVER_CONDITION_INDEX;
        } else if (zenDuration > 0) {
            checkedIndex = COUNTDOWN_CONDITION_INDEX;
        }

        bindTag(zenDuration, mZenRadioGroupContent.getChildAt(FOREVER_CONDITION_INDEX),
                FOREVER_CONDITION_INDEX);
        bindTag(zenDuration, mZenRadioGroupContent.getChildAt(COUNTDOWN_CONDITION_INDEX),
                COUNTDOWN_CONDITION_INDEX);
        bindTag(zenDuration, mZenRadioGroupContent.getChildAt(ALWAYS_ASK_CONDITION_INDEX),
                ALWAYS_ASK_CONDITION_INDEX);
        getConditionTagAt(checkedIndex).rb.setChecked(true);
    }

    private void bindTag(final int currZenDuration, final View row, final int rowIndex) {
        final ConditionTag tag = row.getTag() != null ? (ConditionTag) row.getTag() :
                new ConditionTag();
        row.setTag(tag);

        if (tag.rb == null) {
            tag.rb = (RadioButton) mZenRadioGroup.getChildAt(rowIndex);
        }

        // if duration is set to forever or always prompt, then countdown time defaults to 1 hour
        if (currZenDuration <= 0) {
            tag.countdownZenDuration = MINUTE_BUCKETS[DEFAULT_BUCKET_INDEX];
        } else {
            tag.countdownZenDuration = currZenDuration;
        }

        tag.rb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    tag.rb.setChecked(true);
                }
                tag.line1.setStateDescription(
                        isChecked ? buttonView.getContext().getString(
                                com.android.internal.R.string.selected) : null);
            }
        });

        updateUi(tag, row, rowIndex);
    }

    @VisibleForTesting
    protected ConditionTag getConditionTagAt(int index) {
        return (ConditionTag) mZenRadioGroupContent.getChildAt(index).getTag();
    }


    private void setupUi(ConditionTag tag, View row) {
        if (tag.lines == null) {
            tag.lines = row.findViewById(android.R.id.content);
        }

        if (tag.line1 == null) {
            tag.line1 = (TextView) row.findViewById(android.R.id.text1);
        }

        // text2 is not used in zen duration dialog
        row.findViewById(android.R.id.text2).setVisibility(View.GONE);

        tag.lines.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tag.rb.setChecked(true);
            }
        });
    }

    private void updateButtons(ConditionTag tag, View row, int rowIndex) {
        final ImageView minusButton = (ImageView) row.findViewById(android.R.id.button1);
        final ImageView plusButton = (ImageView) row.findViewById(android.R.id.button2);
        final long time = tag.countdownZenDuration;
        if (rowIndex == COUNTDOWN_CONDITION_INDEX) {
            minusButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onClickTimeButton(row, tag, false /*down*/, rowIndex);
                    tag.lines.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
                }
            });

            plusButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onClickTimeButton(row, tag, true /*up*/, rowIndex);
                    tag.lines.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
                }
            });
            minusButton.setVisibility(View.VISIBLE);
            plusButton.setVisibility(View.VISIBLE);

            minusButton.setEnabled(time > MIN_BUCKET_MINUTES);
            plusButton.setEnabled(tag.countdownZenDuration != MAX_BUCKET_MINUTES);

            minusButton.setAlpha(minusButton.isEnabled() ? 1f : .5f);
            plusButton.setAlpha(plusButton.isEnabled() ? 1f : .5f);
        } else {
            if (minusButton != null) {
                ((ViewGroup) row).removeView(minusButton);
            }
            if (plusButton != null) {
                ((ViewGroup) row).removeView(plusButton);
            }
        }
    }

    @VisibleForTesting
    protected void updateUi(ConditionTag tag, View row, int rowIndex) {
        if (tag.lines == null) {
            setupUi(tag, row);
        }

        updateButtons(tag, row, rowIndex);

        String radioContentText = "";
        switch (rowIndex) {
            case FOREVER_CONDITION_INDEX:
                radioContentText = mContext.getString(R.string.zen_mode_forever);
                break;
            case COUNTDOWN_CONDITION_INDEX:
                Condition condition = ZenModeConfig.toTimeCondition(mContext,
                        tag.countdownZenDuration, ActivityManager.getCurrentUser(), false);
                radioContentText = condition.line1;
                break;
            case ALWAYS_ASK_CONDITION_INDEX:
                radioContentText = mContext.getString(
                        R.string.zen_mode_duration_always_prompt_title);
                break;
        }

        tag.line1.setText(radioContentText);
    }

    @VisibleForTesting
    protected void onClickTimeButton(View row, ConditionTag tag, boolean up, int rowId) {
        int newDndTimeDuration = -1;
        final int n = MINUTE_BUCKETS.length;
        if (mBucketIndex == -1) {
            // not on a known index, search for the next or prev bucket by time
            final long time = tag.countdownZenDuration;
            for (int i = 0; i < n; i++) {
                int j = up ? i : n - 1 - i;
                final int bucketMinutes = MINUTE_BUCKETS[j];
                if (up && bucketMinutes > time || !up && bucketMinutes < time) {
                    mBucketIndex = j;
                    newDndTimeDuration = bucketMinutes;
                    break;
                }
            }
            if (newDndTimeDuration == -1) {
                mBucketIndex = DEFAULT_BUCKET_INDEX;
                newDndTimeDuration = MINUTE_BUCKETS[mBucketIndex];
            }
        } else {
            // on a known index, simply increment or decrement
            mBucketIndex = Math.max(0, Math.min(n - 1, mBucketIndex + (up ? 1 : -1)));
            newDndTimeDuration = MINUTE_BUCKETS[mBucketIndex];
        }
        tag.countdownZenDuration = newDndTimeDuration;
        bindTag(newDndTimeDuration, row, rowId);
        tag.rb.setChecked(true);
    }

    // used as the view tag on condition rows
    @VisibleForTesting
    protected static class ConditionTag {
        public RadioButton rb;
        public View lines;
        public TextView line1;
        public int countdownZenDuration; // only important for countdown radio button
    }
}
