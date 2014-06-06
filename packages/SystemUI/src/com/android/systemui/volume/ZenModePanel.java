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
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings;
import android.service.notification.Condition;
import android.service.notification.ZenModeConfig;
import android.util.AttributeSet;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
    private static final boolean DEBUG = false;

    private static final int[] MINUTE_BUCKETS = new int[] { 15, 30, 45, 60, 120, 180, 240, 480 };
    private static final int MIN_BUCKET_MINUTES = MINUTE_BUCKETS[0];
    private static final int MAX_BUCKET_MINUTES = MINUTE_BUCKETS[MINUTE_BUCKETS.length - 1];
    private static final int DEFAULT_BUCKET_INDEX = Arrays.binarySearch(MINUTE_BUCKETS, 60);

    private static final int SECONDS_MS = 1000;
    private static final int MINUTES_MS = 60 * SECONDS_MS;

    public static final Intent ZEN_SETTINGS = new Intent(Settings.ACTION_ZEN_MODE_SETTINGS);

    private final Context mContext;
    private final LayoutInflater mInflater;
    private final H mHandler = new H();
    private final Favorites mFavorites;

    private char mLogTag = '?';
    private String mTag;
    private LinearLayout mConditions;
    private Callback mCallback;
    private ZenModeController mController;
    private boolean mRequestingConditions;
    private Uri mExitConditionId;
    private int mBucketIndex = -1;
    private boolean mShowing;

    public ZenModePanel(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
        mFavorites = new Favorites();
        mInflater = LayoutInflater.from(new ContextThemeWrapper(context, R.style.QSWhiteTheme));
        updateTag();
        if (DEBUG) Log.d(mTag, "new ZenModePanel");
    }

    private void updateTag() {
        mTag = "ZenModePanel/" + mLogTag + "/" + Integer.toHexString(System.identityHashCode(this));
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
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (DEBUG) Log.d(mTag, "onAttachedToWindow");
        final ViewGroup p = (ViewGroup) getParent();
        updateShowing(p != null && p.getVisibility() == VISIBLE);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (DEBUG) Log.d(mTag, "onDetachedFromWindow");
        updateShowing(false);
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        final boolean vis = visibility == VISIBLE;
        updateShowing(isAttachedToWindow() && vis);
    }

    private void updateShowing(boolean showing) {
        if (showing == mShowing) return;
        mShowing = showing;
        if (DEBUG) Log.d(mTag, "mShowing=" + mShowing);
        if (mController != null) {
            setRequestingConditions(mShowing);
        }
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
            bind(timeCondition, mConditions.getChildAt(0));
            handleUpdateConditions(new Condition[0]);
        } else {
            mConditions.removeAllViews();
        }
    }

    public void init(ZenModeController controller, char logTag) {
        mController = controller;
        mLogTag = logTag;
        updateTag();
        mExitConditionId = mController.getExitConditionId();
        if (DEBUG) Log.d(mTag, "init mExitConditionId=" + mExitConditionId);
        mConditions.removeAllViews();
        mController.addCallback(mZenCallback);
        if (mShowing) {
            setRequestingConditions(true);
        }
    }

    public void setCallback(Callback callback) {
        mCallback = callback;
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
        for (int i = mConditions.getChildCount() - 1; i > newCount; i--) {
            mConditions.removeViewAt(i);
        }
        for (int i = 0; i < newCount; i++) {
            bind(conditions[i], mConditions.getChildAt(i + 1));
        }
        bind(null, mConditions.getChildAt(newCount + 1));
        checkForDefault();
    }

    private ConditionTag getConditionTagAt(int index) {
        return (ConditionTag) mConditions.getChildAt(index).getTag();
    }

    private void checkForDefault() {
        // are we left without anything selected?  if so, set a default
        for (int i = 0; i < mConditions.getChildCount(); i++) {
            if (getConditionTagAt(i).rb.isChecked()) {
                return;
            }
        }
        if (DEBUG) Log.d(mTag, "Selecting a default");
        final int favoriteIndex = mFavorites.getMinuteIndex();
        if (favoriteIndex == -1) {
            getConditionTagAt(mConditions.getChildCount() - 1).rb.setChecked(true);
        } else {
            final Condition c = newTimeCondition(MINUTE_BUCKETS[favoriteIndex]);
            mBucketIndex = favoriteIndex;
            bind(c, mConditions.getChildAt(0));
            getConditionTagAt(0).rb.setChecked(true);
        }
    }

    private void handleExitConditionChanged(Uri exitCondition) {
        mExitConditionId = exitCondition;
        if (DEBUG) Log.d(mTag, "handleExitConditionChanged " + mExitConditionId);
        final int N = mConditions.getChildCount();
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
            mConditions.addView(row);
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
                if (mShowing && isChecked) {
                    if (DEBUG) Log.d(mTag, "onCheckedChanged " + tag.conditionId);
                    final int N = mConditions.getChildCount();
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
        title.setAlpha(enabled ? 1 : .5f);
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
        mExitConditionId = conditionId;
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

    private final ZenModeController.Callback mZenCallback = new ZenModeController.Callback() {
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

        private H() {
            super(Looper.getMainLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            if (msg.what == UPDATE_CONDITIONS) {
                handleUpdateConditions((Condition[])msg.obj);
            } else if (msg.what == EXIT_CONDITION_CHANGED) {
                handleExitConditionChanged((Uri)msg.obj);
            }
        }
    }

    public interface Callback {
        void onMoreSettings();
        void onInteraction();
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
}
