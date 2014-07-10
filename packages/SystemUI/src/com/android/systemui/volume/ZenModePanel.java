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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.service.notification.Condition;
import android.service.notification.ZenModeConfig;
import android.util.AttributeSet;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.statusbar.policy.ZenModeController;

import java.util.Arrays;
import java.util.Objects;

public class ZenModePanel extends LinearLayout {
    private static final String TAG = "ZenModePanel";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final int[] MINUTE_BUCKETS = DEBUG
            ? new int[] { 1, 2, 5, 15, 30, 45, 60, 120, 180, 240, 480 }
            : new int[] { 15, 30, 45, 60, 120, 180, 240, 480 };
    private static final int MIN_BUCKET_MINUTES = MINUTE_BUCKETS[0];
    private static final int MAX_BUCKET_MINUTES = MINUTE_BUCKETS[MINUTE_BUCKETS.length - 1];
    private static final int DEFAULT_BUCKET_INDEX = Arrays.binarySearch(MINUTE_BUCKETS, 60);
    private static final int FOREVER_CONDITION_INDEX = 0;
    private static final int TIME_CONDITION_INDEX = 1;
    private static final int FIRST_CONDITION_INDEX = 2;
    private static final float SILENT_HINT_PULSE_SCALE = 1.1f;

    private static final int SECONDS_MS = 1000;
    private static final int MINUTES_MS = 60 * SECONDS_MS;

    public static final Intent ZEN_SETTINGS = new Intent(Settings.ACTION_ZEN_MODE_SETTINGS);

    private final Context mContext;
    private final LayoutInflater mInflater;
    private final H mHandler = new H();
    private final Favorites mFavorites;
    private final Interpolator mFastOutSlowInInterpolator;

    private char mLogTag = '?';
    private String mTag;

    private SegmentedButtons mZenButtons;
    private View mZenSubhead;
    private TextView mZenSubheadCollapsed;
    private TextView mZenSubheadExpanded;
    private View mMoreSettings;
    private LinearLayout mZenConditions;
    private View mAlarmWarning;

    private Callback mCallback;
    private ZenModeController mController;
    private boolean mRequestingConditions;
    private Uri mExitConditionId;
    private int mBucketIndex = -1;
    private boolean mExpanded;
    private int mAttachedZen;
    private String mExitConditionText;

    public ZenModePanel(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
        mFavorites = new Favorites();
        mInflater = LayoutInflater.from(mContext.getApplicationContext());
        mFastOutSlowInInterpolator = AnimationUtils.loadInterpolator(mContext,
                android.R.interpolator.fast_out_slow_in);
        updateTag();
        if (DEBUG) Log.d(mTag, "new ZenModePanel");
    }

    private void updateTag() {
        mTag = TAG + "/" + mLogTag + "/" + Integer.toHexString(System.identityHashCode(this));
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mZenButtons = (SegmentedButtons) findViewById(R.id.zen_buttons);
        mZenButtons.addButton(R.string.interruption_level_none, Global.ZEN_MODE_NO_INTERRUPTIONS);
        mZenButtons.addButton(R.string.interruption_level_priority,
                Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS);
        mZenButtons.addButton(R.string.interruption_level_all, Global.ZEN_MODE_OFF);
        mZenButtons.setCallback(mZenButtonsCallback);

        mZenSubhead = findViewById(R.id.zen_subhead);

        mZenSubheadCollapsed = (TextView) findViewById(R.id.zen_subhead_collapsed);
        mZenSubheadCollapsed.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setExpanded(true);
                fireInteraction();
            }
        });

        mZenSubheadExpanded = (TextView) findViewById(R.id.zen_subhead_expanded);
        mZenSubheadExpanded.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setExpanded(false);
                fireInteraction();
            }
        });

        mMoreSettings = findViewById(R.id.zen_more_settings);
        mMoreSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fireMoreSettings();
                fireInteraction();
            }
        });

        mZenConditions = (LinearLayout) findViewById(R.id.zen_conditions);

        mAlarmWarning = findViewById(R.id.zen_alarm_warning);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (DEBUG) Log.d(mTag, "onAttachedToWindow");
        mAttachedZen = getSelectedZen(-1);
        refreshExitConditionText();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (DEBUG) Log.d(mTag, "onDetachedFromWindow");
        mAttachedZen = -1;
        setExpanded(false);
    }

    private void setExpanded(boolean expanded) {
        if (expanded == mExpanded) return;
        mExpanded = expanded;
        updateWidgets();
        setRequestingConditions(mExpanded);
        fireExpanded();
    }

    /** Start or stop requesting relevant zen mode exit conditions */
    private void setRequestingConditions(boolean requesting) {
        if (mRequestingConditions == requesting) return;
        if (DEBUG) Log.d(mTag, "setRequestingConditions " + requesting);
        mRequestingConditions = requesting;
        if (mController != null) {
            mController.requestConditions(mRequestingConditions);
        }
        if (mRequestingConditions) {
            Condition timeCondition = parseExistingTimeCondition(mExitConditionId);
            if (timeCondition != null) {
                mBucketIndex = -1;
            } else {
                mBucketIndex = DEFAULT_BUCKET_INDEX;
                timeCondition = newTimeCondition(MINUTE_BUCKETS[mBucketIndex]);
            }
            if (DEBUG) Log.d(mTag, "Initial bucket index: " + mBucketIndex);
            handleUpdateConditions(new Condition[0]);  // ensures forever exists
            bind(timeCondition, mZenConditions.getChildAt(TIME_CONDITION_INDEX));
            checkForDefault();
        } else {
            mZenConditions.removeAllViews();
        }
    }

    public void init(ZenModeController controller, char logTag) {
        mController = controller;
        mLogTag = logTag;
        updateTag();
        setExitConditionId(mController.getExitConditionId());
        refreshExitConditionText();
        mAttachedZen = getSelectedZen(-1);
        handleUpdateZen(mController.getZen());
        if (DEBUG) Log.d(mTag, "init mExitConditionId=" + mExitConditionId);
        mZenConditions.removeAllViews();
        mController.addCallback(mZenCallback);
    }

    private void setExitConditionId(Uri exitConditionId) {
        if (Objects.equals(mExitConditionId, exitConditionId)) return;
        mExitConditionId = exitConditionId;
        refreshExitConditionText();
    }

    private void refreshExitConditionText() {
        if (mExitConditionId == null) {
            mExitConditionText = mContext.getString(R.string.zen_mode_forever);
        } else if (ZenModeConfig.isValidCountdownConditionId(mExitConditionId)) {
            mExitConditionText = parseExistingTimeCondition(mExitConditionId).summary;
        } else {
            mExitConditionText = "(until condition ends)";  // TODO persist current description
        }
    }

    public void setCallback(Callback callback) {
        mCallback = callback;
    }

    public void showSilentHint() {
        if (DEBUG) Log.d(mTag, "showSilentHint");
        if (mZenButtons == null || mZenButtons.getChildCount() == 0) return;
        final View noneButton = mZenButtons.getChildAt(0);
        if (noneButton.getScaleX() != 1) return;  // already running
        noneButton.animate().cancel();
        noneButton.animate().scaleX(SILENT_HINT_PULSE_SCALE).scaleY(SILENT_HINT_PULSE_SCALE)
                .setInterpolator(mFastOutSlowInInterpolator)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        noneButton.animate().scaleX(1).scaleY(1).setListener(null);
                    }
                });
    }

    private void handleUpdateZen(int zen) {
        if (mAttachedZen != -1 && mAttachedZen != zen) {
            setExpanded(zen != Global.ZEN_MODE_OFF);
            mAttachedZen = zen;
        }
        mZenButtons.setSelectedValue(zen);
        updateWidgets();
    }

    private int getSelectedZen(int defValue) {
        final Object zen = mZenButtons.getSelectedValue();
        return zen != null ? (Integer) zen : defValue;
    }

    private void updateWidgets() {
        final int zen = getSelectedZen(Global.ZEN_MODE_OFF);
        final boolean zenOff = zen == Global.ZEN_MODE_OFF;
        final boolean zenImportant = zen == Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        final boolean zenNone = zen == Global.ZEN_MODE_NO_INTERRUPTIONS;

        mZenSubhead.setVisibility(!zenOff ? VISIBLE : GONE);
        mZenSubheadExpanded.setVisibility(mExpanded ? VISIBLE : GONE);
        mZenSubheadCollapsed.setVisibility(!mExpanded ? VISIBLE : GONE);
        mMoreSettings.setVisibility(zenImportant && mExpanded ? VISIBLE : GONE);
        mZenConditions.setVisibility(!zenOff && mExpanded ? VISIBLE : GONE);
        mAlarmWarning.setVisibility(zenNone && mExpanded ? VISIBLE : GONE);

        if (zenNone) {
            mZenSubheadExpanded.setText(R.string.zen_no_interruptions);
            mZenSubheadCollapsed.setText(mExitConditionText);
        } else if (zenImportant) {
            mZenSubheadExpanded.setText(R.string.zen_important_interruptions);
            mZenSubheadCollapsed.setText(mExitConditionText);
        }
    }

    private Condition parseExistingTimeCondition(Uri conditionId) {
        final long time = ZenModeConfig.tryParseCountdownConditionId(conditionId);
        if (time == 0) return null;
        final long span = time - System.currentTimeMillis();
        if (span <= 0 || span > MAX_BUCKET_MINUTES * MINUTES_MS) return null;
        return timeCondition(time, Math.round(span / (float)MINUTES_MS));
    }

    private Condition newTimeCondition(int minutesFromNow) {
        final long now = System.currentTimeMillis();
        return timeCondition(now + minutesFromNow * MINUTES_MS, minutesFromNow);
    }

    private Condition timeCondition(long time, int minutes) {
        final int num = minutes < 60 ? minutes : Math.round(minutes / 60f);
        final int resId = minutes < 60
                ? R.plurals.zen_mode_duration_minutes
                : R.plurals.zen_mode_duration_hours;
        final String caption = mContext.getResources().getQuantityString(resId, num, num);
        final Uri id = ZenModeConfig.toCountdownConditionId(time);
        return new Condition(id, caption, "", "", 0, Condition.STATE_TRUE,
                Condition.FLAG_RELEVANT_NOW);
    }

    private void handleUpdateConditions(Condition[] conditions) {
        final int newCount = conditions == null ? 0 : conditions.length;
        if (DEBUG) Log.d(mTag, "handleUpdateConditions newCount=" + newCount);
        for (int i = mZenConditions.getChildCount(); i >= newCount + FIRST_CONDITION_INDEX; i--) {
            mZenConditions.removeViewAt(i);
        }
        bind(null, mZenConditions.getChildAt(FOREVER_CONDITION_INDEX));
        for (int i = 0; i < newCount; i++) {
            bind(conditions[i], mZenConditions.getChildAt(FIRST_CONDITION_INDEX + i));
        }
    }

    private ConditionTag getConditionTagAt(int index) {
        return (ConditionTag) mZenConditions.getChildAt(index).getTag();
    }

    private void checkForDefault() {
        // are we left without anything selected?  if so, set a default
        for (int i = 0; i < mZenConditions.getChildCount(); i++) {
            if (getConditionTagAt(i).rb.isChecked()) {
                return;
            }
        }
        if (DEBUG) Log.d(mTag, "Selecting a default");
        final int favoriteIndex = mFavorites.getMinuteIndex();
        if (favoriteIndex == -1) {
            getConditionTagAt(FOREVER_CONDITION_INDEX).rb.setChecked(true);
        } else {
            final Condition c = newTimeCondition(MINUTE_BUCKETS[favoriteIndex]);
            mBucketIndex = favoriteIndex;
            bind(c, mZenConditions.getChildAt(TIME_CONDITION_INDEX));
            getConditionTagAt(TIME_CONDITION_INDEX).rb.setChecked(true);
        }
    }

    private void handleExitConditionChanged(Uri exitCondition) {
        setExitConditionId(exitCondition);
        if (DEBUG) Log.d(mTag, "handleExitConditionChanged " + mExitConditionId);
        final int N = mZenConditions.getChildCount();
        for (int i = 0; i < N; i++) {
            final ConditionTag tag = getConditionTagAt(i);
            tag.rb.setChecked(Objects.equals(tag.conditionId, exitCondition));
        }
    }

    private void bind(final Condition condition, View convertView) {
        final boolean enabled = condition == null || condition.state == Condition.STATE_TRUE;
        final View row;
        if (convertView == null) {
            row = mInflater.inflate(R.layout.zen_mode_condition, this, false);
            if (DEBUG) Log.d(mTag, "Adding new condition view for: " + condition);
            mZenConditions.addView(row);
        } else {
            row = convertView;
        }
        final ConditionTag tag =
                row.getTag() != null ? (ConditionTag) row.getTag() : new ConditionTag();
        row.setTag(tag);
        if (tag.rb == null) {
            tag.rb = (RadioButton) row.findViewById(android.R.id.checkbox);
        }
        tag.conditionId = condition != null ? condition.id : null;
        tag.rb.setEnabled(enabled);
        tag.rb.setOnCheckedChangeListener(new OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (mExpanded && isChecked) {
                    if (DEBUG) Log.d(mTag, "onCheckedChanged " + tag.conditionId);
                    final int N = mZenConditions.getChildCount();
                    for (int i = 0; i < N; i++) {
                        ConditionTag childTag = getConditionTagAt(i);
                        if (childTag == tag) continue;
                        childTag.rb.setChecked(false);
                    }
                    select(tag.conditionId);
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
        title.setAlpha(enabled ? 1 : .4f);
        final ImageView button1 = (ImageView) row.findViewById(android.R.id.button1);
        button1.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                onClickTimeButton(row, tag, false /*down*/);
            }
        });

        final ImageView button2 = (ImageView) row.findViewById(android.R.id.button2);
        button2.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                onClickTimeButton(row, tag, true /*up*/);
            }
        });
        title.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                tag.rb.setChecked(true);
                fireInteraction();
            }
        });

        final long time = ZenModeConfig.tryParseCountdownConditionId(tag.conditionId);
        if (time > 0) {
            if (mBucketIndex > -1) {
                button1.setEnabled(mBucketIndex > 0);
                button2.setEnabled(mBucketIndex < MINUTE_BUCKETS.length - 1);
            } else {
                final long span = time - System.currentTimeMillis();
                button1.setEnabled(span > MIN_BUCKET_MINUTES * MINUTES_MS);
                final Condition maxCondition = newTimeCondition(MAX_BUCKET_MINUTES);
                button2.setEnabled(!Objects.equals(condition.summary, maxCondition.summary));
            }

            button1.setAlpha(button1.isEnabled() ? 1f : .5f);
            button2.setAlpha(button2.isEnabled() ? 1f : .5f);
        } else {
            button1.setVisibility(View.GONE);
            button2.setVisibility(View.GONE);
        }
    }

    private void onClickTimeButton(View row, ConditionTag tag, boolean up) {
        Condition newCondition = null;
        final int N = MINUTE_BUCKETS.length;
        if (mBucketIndex == -1) {
            // not on a known index, search for the next or prev bucket by time
            final long time = ZenModeConfig.tryParseCountdownConditionId(tag.conditionId);
            final long now = System.currentTimeMillis();
            for (int i = 0; i < N; i++) {
                int j = up ? i : N - 1 - i;
                final int bucketMinutes = MINUTE_BUCKETS[j];
                final long bucketTime = now + bucketMinutes * MINUTES_MS;
                if (up && bucketTime > time || !up && bucketTime < time) {
                    mBucketIndex = j;
                    newCondition = timeCondition(bucketTime, bucketMinutes);
                    break;
                }
            }
            if (newCondition == null) {
                mBucketIndex = DEFAULT_BUCKET_INDEX;
                newCondition = newTimeCondition(MINUTE_BUCKETS[mBucketIndex]);
            }
        } else {
            // on a known index, simply increment or decrement
            mBucketIndex = Math.max(0, Math.min(N - 1, mBucketIndex + (up ? 1 : -1)));
            newCondition = newTimeCondition(MINUTE_BUCKETS[mBucketIndex]);
        }
        bind(newCondition, row);
        tag.rb.setChecked(true);
        select(newCondition.id);
        fireInteraction();
    }

    private void select(Uri conditionId) {
        if (DEBUG) Log.d(mTag, "select " + conditionId);
        if (mController != null) {
            mController.setExitConditionId(conditionId);
        }
        setExitConditionId(conditionId);
        if (conditionId == null) {
            mFavorites.setMinuteIndex(-1);
        } else if (ZenModeConfig.isValidCountdownConditionId(conditionId) && mBucketIndex != -1) {
            mFavorites.setMinuteIndex(mBucketIndex);
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

    private void fireExpanded() {
        if (mCallback != null) {
            mCallback.onExpanded(mExpanded);
        }
    }

    private final ZenModeController.Callback mZenCallback = new ZenModeController.Callback() {
        @Override
        public void onZenChanged(int zen) {
            mHandler.obtainMessage(H.UPDATE_ZEN, zen, 0).sendToTarget();
        }
        @Override
        public void onConditionsChanged(Condition[] conditions) {
            mHandler.obtainMessage(H.UPDATE_CONDITIONS, conditions).sendToTarget();
        }

        @Override
        public void onExitConditionChanged(Uri exitConditionId) {
            mHandler.obtainMessage(H.EXIT_CONDITION_CHANGED, exitConditionId).sendToTarget();
        }
    };

    private final class H extends Handler {
        private static final int UPDATE_CONDITIONS = 1;
        private static final int EXIT_CONDITION_CHANGED = 2;
        private static final int UPDATE_ZEN = 3;

        private H() {
            super(Looper.getMainLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            if (msg.what == UPDATE_CONDITIONS) {
                handleUpdateConditions((Condition[])msg.obj);
                checkForDefault();
            } else if (msg.what == EXIT_CONDITION_CHANGED) {
                handleExitConditionChanged((Uri)msg.obj);
            } else if (msg.what == UPDATE_ZEN) {
                handleUpdateZen(msg.arg1);
            }
        }
    }

    public interface Callback {
        void onMoreSettings();
        void onInteraction();
        void onExpanded(boolean expanded);
    }

    // used as the view tag on condition rows
    private static class ConditionTag {
        RadioButton rb;
        Uri conditionId;
    }

    private final class Favorites implements OnSharedPreferenceChangeListener {
        private static final String KEY_MINUTE_INDEX = "minuteIndex";

        private int mMinuteIndex;

        private Favorites() {
            prefs().registerOnSharedPreferenceChangeListener(this);
            updateMinuteIndex();
        }

        public int getMinuteIndex() {
            return mMinuteIndex;
        }

        public void setMinuteIndex(int minuteIndex) {
            minuteIndex = clamp(minuteIndex);
            if (minuteIndex == mMinuteIndex) return;
            mMinuteIndex = clamp(minuteIndex);
            if (DEBUG) Log.d(mTag, "Setting favorite minute index: " + mMinuteIndex);
            prefs().edit().putInt(KEY_MINUTE_INDEX, mMinuteIndex).apply();
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
            updateMinuteIndex();
        }

        private SharedPreferences prefs() {
            return mContext.getSharedPreferences(ZenModePanel.class.getSimpleName(), 0);
        }

        private void updateMinuteIndex() {
            mMinuteIndex = clamp(prefs().getInt(KEY_MINUTE_INDEX, DEFAULT_BUCKET_INDEX));
            if (DEBUG) Log.d(mTag, "Favorite minute index: " + mMinuteIndex);
        }

        private int clamp(int index) {
            return Math.max(-1, Math.min(MINUTE_BUCKETS.length - 1, index));
        }
    }

    private final SegmentedButtons.Callback mZenButtonsCallback = new SegmentedButtons.Callback() {
        @Override
        public void onSelected(Object value) {
            if (value != null && mZenButtons.isShown()) {
                if (DEBUG) Log.d(mTag, "mZenButtonsCallback selected=" + value);
                mController.setZen((Integer) value);
                mController.setExitConditionId(null);
            }
        }
    };
}
