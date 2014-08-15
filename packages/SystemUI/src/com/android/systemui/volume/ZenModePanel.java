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
import android.content.res.Resources;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.service.notification.Condition;
import android.service.notification.ZenModeConfig;
import android.text.format.DateFormat;
import android.text.format.Time;
import android.util.AttributeSet;
import android.util.Log;
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

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;

public class ZenModePanel extends LinearLayout {
    private static final String TAG = "ZenModePanel";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final int SECONDS_MS = 1000;
    private static final int MINUTES_MS = 60 * SECONDS_MS;

    private static final int[] MINUTE_BUCKETS = DEBUG
            ? new int[] { 0, 1, 2, 5, 15, 30, 45, 60, 120, 180, 240, 480 }
            : new int[] { 15, 30, 45, 60, 120, 180, 240, 480 };
    private static final int MIN_BUCKET_MINUTES = MINUTE_BUCKETS[0];
    private static final int MAX_BUCKET_MINUTES = MINUTE_BUCKETS[MINUTE_BUCKETS.length - 1];
    private static final int DEFAULT_BUCKET_INDEX = Arrays.binarySearch(MINUTE_BUCKETS, 60);
    private static final int FOREVER_CONDITION_INDEX = 0;
    private static final int TIME_CONDITION_INDEX = 1;
    private static final int FIRST_CONDITION_INDEX = 2;
    private static final float SILENT_HINT_PULSE_SCALE = 1.1f;
    private static final int ZERO_VALUE_MS = 20 * SECONDS_MS;

    public static final Intent ZEN_SETTINGS = new Intent(Settings.ACTION_ZEN_MODE_SETTINGS);

    private final Context mContext;
    private final LayoutInflater mInflater;
    private final H mHandler = new H();
    private final Favorites mFavorites;
    private final Interpolator mFastOutSlowInInterpolator;
    private final int mHardWarningColor;
    private final int mSoftWarningColor;

    private String mTag = TAG + "/" + Integer.toHexString(System.identityHashCode(this));

    private SegmentedButtons mZenButtons;
    private View mZenSubhead;
    private TextView mZenSubheadCollapsed;
    private TextView mZenSubheadExpanded;
    private View mMoreSettings;
    private LinearLayout mZenConditions;
    private TextView mAlarmWarning;

    private Callback mCallback;
    private ZenModeController mController;
    private boolean mRequestingConditions;
    private Condition mExitCondition;
    private String mExitConditionText;
    private int mBucketIndex = -1;
    private boolean mExpanded;
    private boolean mHidden = false;
    private int mSessionZen;
    private Condition mSessionExitCondition;
    private long mNextAlarm;
    private Condition[] mConditions;
    private Condition mTimeCondition;

    public ZenModePanel(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
        mFavorites = new Favorites();
        mInflater = LayoutInflater.from(mContext.getApplicationContext());
        mFastOutSlowInInterpolator = AnimationUtils.loadInterpolator(mContext,
                android.R.interpolator.fast_out_slow_in);
        final Resources res = mContext.getResources();
        mHardWarningColor = res.getColor(R.color.system_warning_color);
        mSoftWarningColor = res.getColor(R.color.qs_subhead);
        if (DEBUG) Log.d(mTag, "new ZenModePanel");
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

        mMoreSettings = findViewById(R.id.zen_more_settings);
        mMoreSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fireMoreSettings();
                fireInteraction();
            }
        });

        mZenConditions = (LinearLayout) findViewById(R.id.zen_conditions);
        mAlarmWarning = (TextView) findViewById(R.id.zen_alarm_warning);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (DEBUG) Log.d(mTag, "onAttachedToWindow");
        mSessionZen = getSelectedZen(-1);
        mSessionExitCondition = copy(mExitCondition);
        refreshExitConditionText();
        refreshNextAlarm();
        updateWidgets();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (DEBUG) Log.d(mTag, "onDetachedFromWindow");
        mSessionZen = -1;
        mSessionExitCondition = null;
        setExpanded(false);
    }

    public void setHidden(boolean hidden) {
        if (mHidden == hidden) return;
        mHidden = hidden;
        updateWidgets();
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
            mTimeCondition = parseExistingTimeCondition(mExitCondition);
            if (mTimeCondition != null) {
                mBucketIndex = -1;
            } else {
                mBucketIndex = DEFAULT_BUCKET_INDEX;
                mTimeCondition = newTimeCondition(MINUTE_BUCKETS[mBucketIndex]);
            }
            if (DEBUG) Log.d(mTag, "Initial bucket index: " + mBucketIndex);
            mConditions = null; // reset conditions
            handleUpdateConditions();
        } else {
            mZenConditions.removeAllViews();
        }
    }

    public void init(ZenModeController controller) {
        mController = controller;
        setExitCondition(mController.getExitCondition());
        refreshExitConditionText();
        mSessionZen = getSelectedZen(-1);
        handleUpdateZen(mController.getZen());
        if (DEBUG) Log.d(mTag, "init mExitCondition=" + mExitCondition);
        mZenConditions.removeAllViews();
        mController.addCallback(mZenCallback);
    }

    private void setExitCondition(Condition exitCondition) {
        if (sameConditionId(mExitCondition, exitCondition)) return;
        mExitCondition = exitCondition;
        refreshExitConditionText();
        updateWidgets();
    }

    private Uri getExitConditionId() {
        return getConditionId(mExitCondition);
    }

    private static Uri getConditionId(Condition condition) {
        return condition != null ? condition.id : null;
    }

    private static boolean sameConditionId(Condition lhs, Condition rhs) {
        return lhs == null ? rhs == null : rhs != null && lhs.id.equals(rhs.id);
    }

    private static Condition copy(Condition condition) {
        return condition == null ? null : condition.copy();
    }

    private void refreshExitConditionText() {
        final String forever = mContext.getString(R.string.zen_mode_forever);
        if (mExitCondition == null) {
            mExitConditionText = forever;
        } else if (ZenModeConfig.isValidCountdownConditionId(mExitCondition.id)) {
            final Condition condition = parseExistingTimeCondition(mExitCondition);
            mExitConditionText = condition != null ? condition.summary : forever;
        } else {
            mExitConditionText = mExitCondition.summary;
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
        if (mSessionZen != -1 && mSessionZen != zen) {
            setExpanded(zen != Global.ZEN_MODE_OFF);
            mSessionZen = zen;
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
        final boolean hasNextAlarm = mNextAlarm != 0;
        final boolean expanded = !mHidden && mExpanded;
        final boolean showAlarmWarning = zenNone && expanded && hasNextAlarm;

        mZenButtons.setVisibility(mHidden ? GONE : VISIBLE);
        mZenSubhead.setVisibility(!mHidden && !zenOff ? VISIBLE : GONE);
        mZenSubheadExpanded.setVisibility(expanded ? VISIBLE : GONE);
        mZenSubheadCollapsed.setVisibility(!expanded ? VISIBLE : GONE);
        mMoreSettings.setVisibility(zenImportant && expanded ? VISIBLE : GONE);
        mZenConditions.setVisibility(!zenOff && expanded ? VISIBLE : GONE);
        mAlarmWarning.setVisibility(zenNone && expanded && hasNextAlarm ? VISIBLE : GONE);
        if (showAlarmWarning) {
            final long exitTime = ZenModeConfig.tryParseCountdownConditionId(getExitConditionId());
            final long now = System.currentTimeMillis();
            final boolean alarmToday = time(mNextAlarm).yearDay == time(now).yearDay;
            final String skeleton = (alarmToday ? "" : "E")
                    + (DateFormat.is24HourFormat(mContext) ? "Hm" : "hma");
            final String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton);
            final String alarm = new SimpleDateFormat(pattern).format(new Date(mNextAlarm));
            final boolean isWarning = exitTime > 0 && mNextAlarm > now && mNextAlarm < exitTime;
            if (isWarning) {
                mAlarmWarning.setText(mContext.getString(R.string.zen_alarm_warning, alarm));
                mAlarmWarning.setTextColor(mHardWarningColor);
            } else {
                mAlarmWarning.setText(mContext.getString(alarmToday
                        ? R.string.zen_alarm_information_time
                        : R.string.zen_alarm_information_day_time, alarm));
                mAlarmWarning.setTextColor(mSoftWarningColor);
            }
        }

        if (zenNone) {
            mZenSubheadExpanded.setText(R.string.zen_no_interruptions_with_warning);
            mZenSubheadCollapsed.setText(mExitConditionText);
        } else if (zenImportant) {
            mZenSubheadExpanded.setText(R.string.zen_important_interruptions);
            mZenSubheadCollapsed.setText(mExitConditionText);
        }
    }

    private static Time time(long millis) {
        final Time t = new Time();
        t.set(millis);
        return t;
    }

    private Condition parseExistingTimeCondition(Condition condition) {
        if (condition == null) return null;
        final long time = ZenModeConfig.tryParseCountdownConditionId(condition.id);
        if (time == 0) return null;
        final long span = time - System.currentTimeMillis();
        if (span <= 0 || span > MAX_BUCKET_MINUTES * MINUTES_MS) return null;
        return timeCondition(time, Math.round(span / (float)MINUTES_MS));
    }

    private Condition newTimeCondition(int minutesFromNow) {
        final long now = System.currentTimeMillis();
        final long millis = minutesFromNow == 0 ? ZERO_VALUE_MS : minutesFromNow * MINUTES_MS;
        return timeCondition(now + millis, minutesFromNow);
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

    private void refreshNextAlarm() {
        mNextAlarm = mController.getNextAlarm();
    }

    private void handleNextAlarmChanged() {
        refreshNextAlarm();
        updateWidgets();
    }

    private void handleUpdateConditions(Condition[] conditions) {
        mConditions = conditions;
        handleUpdateConditions();
    }

    private void handleUpdateConditions() {
        final int conditionCount = mConditions == null ? 0 : mConditions.length;
        if (DEBUG) Log.d(mTag, "handleUpdateConditions conditionCount=" + conditionCount);
        for (int i = mZenConditions.getChildCount() - 1; i >= FIRST_CONDITION_INDEX; i--) {
            mZenConditions.removeViewAt(i);
        }
        // forever
        bind(null, mZenConditions.getChildAt(FOREVER_CONDITION_INDEX));
        // countdown
        bind(mTimeCondition, mZenConditions.getChildAt(TIME_CONDITION_INDEX));
        // provider conditions
        boolean foundDowntime = false;
        for (int i = 0; i < conditionCount; i++) {
            bind(mConditions[i], mZenConditions.getChildAt(FIRST_CONDITION_INDEX + i));
            foundDowntime |= isDowntime(mConditions[i]);
        }
        // ensure downtime exists, if active
        if (isDowntime(mSessionExitCondition) && !foundDowntime) {
            bind(mSessionExitCondition, null);
        }
        // ensure something is selected
        checkForDefault();
    }

    private static boolean isDowntime(Condition c) {
        return ZenModeConfig.isValidDowntimeConditionId(getConditionId(c));
    }

    private ConditionTag getConditionTagAt(int index) {
        return (ConditionTag) mZenConditions.getChildAt(index).getTag();
    }

    private void checkForDefault() {
        // are we left without anything selected?  if so, set a default
        for (int i = 0; i < mZenConditions.getChildCount(); i++) {
            if (getConditionTagAt(i).rb.isChecked()) {
                if (DEBUG) Log.d(mTag, "Not selecting a default, checked="
                        + getConditionTagAt(i).condition);
                return;
            }
        }
        if (DEBUG) Log.d(mTag, "Selecting a default");
        final int favoriteIndex = mFavorites.getMinuteIndex();
        if (favoriteIndex == -1) {
            getConditionTagAt(FOREVER_CONDITION_INDEX).rb.setChecked(true);
        } else {
            mTimeCondition = newTimeCondition(MINUTE_BUCKETS[favoriteIndex]);
            mBucketIndex = favoriteIndex;
            bind(mTimeCondition, mZenConditions.getChildAt(TIME_CONDITION_INDEX));
            getConditionTagAt(TIME_CONDITION_INDEX).rb.setChecked(true);
        }
    }

    private void handleExitConditionChanged(Condition exitCondition) {
        setExitCondition(exitCondition);
        if (DEBUG) Log.d(mTag, "handleExitConditionChanged " + mExitCondition);
        final int N = mZenConditions.getChildCount();
        for (int i = 0; i < N; i++) {
            final ConditionTag tag = getConditionTagAt(i);
            tag.rb.setChecked(sameConditionId(tag.condition, mExitCondition));
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
        tag.condition = condition;
        tag.rb.setEnabled(enabled);
        if (sameConditionId(mSessionExitCondition, tag.condition)) {
            tag.rb.setChecked(true);
        }
        tag.rb.setOnCheckedChangeListener(new OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (mExpanded && isChecked) {
                    if (DEBUG) Log.d(mTag, "onCheckedChanged " + tag.condition);
                    final int N = mZenConditions.getChildCount();
                    for (int i = 0; i < N; i++) {
                        ConditionTag childTag = getConditionTagAt(i);
                        if (childTag == tag) continue;
                        childTag.rb.setChecked(false);
                    }
                    select(tag.condition);
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

        final long time = ZenModeConfig.tryParseCountdownConditionId(getConditionId(tag.condition));
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
            final Uri conditionId = getConditionId(tag.condition);
            final long time = ZenModeConfig.tryParseCountdownConditionId(conditionId);
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
        mTimeCondition = newCondition;
        bind(mTimeCondition, row);
        tag.rb.setChecked(true);
        select(mTimeCondition);
        fireInteraction();
    }

    private void select(Condition condition) {
        if (DEBUG) Log.d(mTag, "select " + condition);
        if (mController != null) {
            mController.setExitCondition(condition);
        }
        setExitCondition(condition);
        if (condition == null) {
            mFavorites.setMinuteIndex(-1);
        } else if (ZenModeConfig.isValidCountdownConditionId(condition.id) && mBucketIndex != -1) {
            mFavorites.setMinuteIndex(mBucketIndex);
        }
        mSessionExitCondition = copy(condition);
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
        public void onExitConditionChanged(Condition exitCondition) {
            mHandler.obtainMessage(H.EXIT_CONDITION_CHANGED, exitCondition).sendToTarget();
        }

        @Override
        public void onNextAlarmChanged() {
            mHandler.sendEmptyMessage(H.NEXT_ALARM_CHANGED);
        }
    };

    private final class H extends Handler {
        private static final int UPDATE_CONDITIONS = 1;
        private static final int EXIT_CONDITION_CHANGED = 2;
        private static final int UPDATE_ZEN = 3;
        private static final int NEXT_ALARM_CHANGED = 4;

        private H() {
            super(Looper.getMainLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            if (msg.what == UPDATE_CONDITIONS) {
                handleUpdateConditions((Condition[]) msg.obj);
            } else if (msg.what == EXIT_CONDITION_CHANGED) {
                handleExitConditionChanged((Condition) msg.obj);
            } else if (msg.what == UPDATE_ZEN) {
                handleUpdateZen(msg.arg1);
            } else if (msg.what == NEXT_ALARM_CHANGED) {
                handleNextAlarmChanged();
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
        Condition condition;
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
            }
        }
    };
}
