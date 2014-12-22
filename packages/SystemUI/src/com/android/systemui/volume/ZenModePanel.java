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

import android.animation.LayoutTransition;
import android.animation.LayoutTransition.TransitionListener;
import android.app.ActivityManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.res.Resources;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.service.notification.Condition;
import android.service.notification.ZenModeConfig;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.util.Log;
import android.util.MathUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Objects;

public class ZenModePanel extends LinearLayout {
    private static final String TAG = "ZenModePanel";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final int SECONDS_MS = 1000;
    private static final int MINUTES_MS = 60 * SECONDS_MS;

    private static final int[] MINUTE_BUCKETS = DEBUG
            ? new int[] { 0, 1, 2, 5, 15, 30, 45, 60, 120, 180, 240, 480 }
            : ZenModeConfig.MINUTE_BUCKETS;
    private static final int MIN_BUCKET_MINUTES = MINUTE_BUCKETS[0];
    private static final int MAX_BUCKET_MINUTES = MINUTE_BUCKETS[MINUTE_BUCKETS.length - 1];
    private static final int DEFAULT_BUCKET_INDEX = Arrays.binarySearch(MINUTE_BUCKETS, 60);
    private static final int FOREVER_CONDITION_INDEX = 0;
    private static final int COUNTDOWN_CONDITION_INDEX = 1;

    public static final Intent ZEN_SETTINGS = new Intent(Settings.ACTION_ZEN_MODE_SETTINGS);

    private final Context mContext;
    private final LayoutInflater mInflater;
    private final H mHandler = new H();
    private final Prefs mPrefs;
    private final IconPulser mIconPulser;
    private final int mSubheadWarningColor;
    private final int mSubheadColor;
    private final Interpolator mInterpolator;
    private final int mMaxConditions;
    private final int mMaxOptionalConditions;
    private final boolean mCountdownConditionSupported;
    private final int mFirstConditionIndex;
    private final TransitionHelper mTransitionHelper = new TransitionHelper();
    private final Uri mForeverId;

    private String mTag = TAG + "/" + Integer.toHexString(System.identityHashCode(this));

    private SegmentedButtons mZenButtons;
    private View mZenSubhead;
    private TextView mZenSubheadCollapsed;
    private TextView mZenSubheadExpanded;
    private View mMoreSettings;
    private LinearLayout mZenConditions;

    private Callback mCallback;
    private ZenModeController mController;
    private boolean mRequestingConditions;
    private Condition mExitCondition;
    private String mExitConditionText;
    private int mBucketIndex = -1;
    private boolean mExpanded;
    private boolean mHidden;
    private int mSessionZen;
    private int mAttachedZen;
    private boolean mAttached;
    private Condition mSessionExitCondition;
    private Condition[] mConditions;
    private Condition mTimeCondition;

    public ZenModePanel(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
        mPrefs = new Prefs();
        mInflater = LayoutInflater.from(mContext.getApplicationContext());
        mIconPulser = new IconPulser(mContext);
        final Resources res = mContext.getResources();
        mSubheadWarningColor = res.getColor(R.color.system_warning_color);
        mSubheadColor = res.getColor(R.color.qs_subhead);
        mInterpolator = AnimationUtils.loadInterpolator(mContext,
                com.android.internal.R.interpolator.fast_out_slow_in);
        mCountdownConditionSupported = NotificationManager.from(mContext)
                .isSystemConditionProviderEnabled(ZenModeConfig.COUNTDOWN_PATH);
        final int countdownDelta = mCountdownConditionSupported ? 1 : 0;
        mFirstConditionIndex = COUNTDOWN_CONDITION_INDEX + countdownDelta;
        final int minConditions = 1 /*forever*/ + countdownDelta;
        mMaxConditions = MathUtils.constrain(res.getInteger(R.integer.zen_mode_max_conditions),
                minConditions, 100);
        mMaxOptionalConditions = mMaxConditions - minConditions;
        mForeverId = Condition.newId(mContext).appendPath("forever").build();
        if (DEBUG) Log.d(mTag, "new ZenModePanel");
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("ZenModePanel state:");
        pw.print("  mCountdownConditionSupported="); pw.println(mCountdownConditionSupported);
        pw.print("  mMaxConditions="); pw.println(mMaxConditions);
        pw.print("  mRequestingConditions="); pw.println(mRequestingConditions);
        pw.print("  mAttached="); pw.println(mAttached);
        pw.print("  mHidden="); pw.println(mHidden);
        pw.print("  mExpanded="); pw.println(mExpanded);
        pw.print("  mSessionZen="); pw.println(mSessionZen);
        pw.print("  mAttachedZen="); pw.println(mAttachedZen);
        mTransitionHelper.dump(fd, pw, args);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mZenButtons = (SegmentedButtons) findViewById(R.id.zen_buttons);
        mZenButtons.addButton(R.string.interruption_level_none, R.drawable.ic_zen_none,
                Global.ZEN_MODE_NO_INTERRUPTIONS);
        mZenButtons.addButton(R.string.interruption_level_priority, R.drawable.ic_zen_important,
                Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS);
        mZenButtons.addButton(R.string.interruption_level_all, R.drawable.ic_zen_all,
                Global.ZEN_MODE_OFF);
        mZenButtons.setCallback(mZenButtonsCallback);

        final ViewGroup zenButtonsContainer = (ViewGroup) findViewById(R.id.zen_buttons_container);
        zenButtonsContainer.setLayoutTransition(newLayoutTransition(null));

        mZenSubhead = findViewById(R.id.zen_subhead);

        mZenSubheadCollapsed = (TextView) findViewById(R.id.zen_subhead_collapsed);
        mZenSubheadCollapsed.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setExpanded(true);
            }
        });
        Interaction.register(mZenSubheadCollapsed, mInteractionCallback);

        mZenSubheadExpanded = (TextView) findViewById(R.id.zen_subhead_expanded);
        Interaction.register(mZenSubheadExpanded, mInteractionCallback);

        mMoreSettings = findViewById(R.id.zen_more_settings);
        mMoreSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fireMoreSettings();
            }
        });
        Interaction.register(mMoreSettings, mInteractionCallback);

        mZenConditions = (LinearLayout) findViewById(R.id.zen_conditions);
        for (int i = 0; i < mMaxConditions; i++) {
            mZenConditions.addView(mInflater.inflate(R.layout.zen_mode_condition, this, false));
        }

        setLayoutTransition(newLayoutTransition(mTransitionHelper));
    }

    private LayoutTransition newLayoutTransition(TransitionListener listener) {
        final LayoutTransition transition = new LayoutTransition();
        transition.disableTransitionType(LayoutTransition.DISAPPEARING);
        transition.disableTransitionType(LayoutTransition.CHANGE_DISAPPEARING);
        transition.disableTransitionType(LayoutTransition.APPEARING);
        transition.setInterpolator(LayoutTransition.CHANGE_APPEARING, mInterpolator);
        if (listener != null) {
            transition.addTransitionListener(listener);
        }
        return transition;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (DEBUG) Log.d(mTag, "onAttachedToWindow");
        mAttached = true;
        mAttachedZen = getSelectedZen(-1);
        mSessionZen = mAttachedZen;
        mTransitionHelper.clear();
        setSessionExitCondition(copy(mExitCondition));
        refreshExitConditionText();
        updateWidgets();
        setRequestingConditions(!mHidden);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (DEBUG) Log.d(mTag, "onDetachedFromWindow");
        checkForAttachedZenChange();
        mAttached = false;
        mAttachedZen = -1;
        mSessionZen = -1;
        setSessionExitCondition(null);
        setExpanded(false);
        setRequestingConditions(false);
        mTransitionHelper.clear();
    }

    private void setSessionExitCondition(Condition condition) {
        if (Objects.equals(condition, mSessionExitCondition)) return;
        if (DEBUG) Log.d(mTag, "mSessionExitCondition=" + getConditionId(condition));
        mSessionExitCondition = condition;
    }

    public void setHidden(boolean hidden) {
        if (mHidden == hidden) return;
        if (DEBUG) Log.d(mTag, "hidden=" + hidden);
        mHidden = hidden;
        setRequestingConditions(mAttached && !mHidden);
        updateWidgets();
    }

    private void checkForAttachedZenChange() {
        final int selectedZen = getSelectedZen(-1);
        if (DEBUG) Log.d(mTag, "selectedZen=" + selectedZen);
        if (selectedZen != mAttachedZen) {
            if (DEBUG) Log.d(mTag, "attachedZen: " + mAttachedZen + " -> " + selectedZen);
            if (selectedZen == Global.ZEN_MODE_NO_INTERRUPTIONS) {
                mPrefs.trackNoneSelected();
            }
        }
    }

    private void setExpanded(boolean expanded) {
        if (expanded == mExpanded) return;
        mExpanded = expanded;
        if (mExpanded) {
            ensureSelection();
        }
        updateWidgets();
        fireExpanded();
    }

    /** Start or stop requesting relevant zen mode exit conditions */
    private void setRequestingConditions(final boolean requesting) {
        if (mRequestingConditions == requesting) return;
        if (DEBUG) Log.d(mTag, "setRequestingConditions " + requesting);
        mRequestingConditions = requesting;
        if (mController != null) {
            AsyncTask.execute(new Runnable() {
                @Override
                public void run() {
                    mController.requestConditions(requesting);
                }
            });
        }
        if (mRequestingConditions) {
            mTimeCondition = parseExistingTimeCondition(mExitCondition);
            if (mTimeCondition != null) {
                mBucketIndex = -1;
            } else {
                mBucketIndex = DEFAULT_BUCKET_INDEX;
                mTimeCondition = ZenModeConfig.toTimeCondition(mContext,
                        MINUTE_BUCKETS[mBucketIndex], ActivityManager.getCurrentUser());
            }
            if (DEBUG) Log.d(mTag, "Initial bucket index: " + mBucketIndex);
            mConditions = null; // reset conditions
            handleUpdateConditions();
        } else {
            hideAllConditions();
        }
    }

    public void init(ZenModeController controller) {
        mController = controller;
        setExitCondition(mController.getExitCondition());
        refreshExitConditionText();
        mSessionZen = getSelectedZen(-1);
        handleUpdateZen(mController.getZen());
        if (DEBUG) Log.d(mTag, "init mExitCondition=" + mExitCondition);
        hideAllConditions();
        mController.addCallback(mZenCallback);
    }

    public void updateLocale() {
        mZenButtons.updateLocale();
    }

    private void setExitCondition(Condition exitCondition) {
        if (Objects.equals(mExitCondition, exitCondition)) return;
        mExitCondition = exitCondition;
        if (DEBUG) Log.d(mTag, "mExitCondition=" + getConditionId(mExitCondition));
        refreshExitConditionText();
        updateWidgets();
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
        if (mExitCondition == null) {
            mExitConditionText = foreverSummary();
        } else if (isCountdown(mExitCondition)) {
            final Condition condition = parseExistingTimeCondition(mExitCondition);
            mExitConditionText = condition != null ? condition.summary : foreverSummary();
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
        mIconPulser.start(noneButton);
    }

    private void handleUpdateZen(int zen) {
        if (mSessionZen != -1 && mSessionZen != zen) {
            setExpanded(zen != Global.ZEN_MODE_OFF);
            mSessionZen = zen;
        }
        mZenButtons.setSelectedValue(zen);
        updateWidgets();
        handleUpdateConditions();
        if (mExpanded) {
            final Condition selected = getSelectedCondition();
            if (!Objects.equals(mExitCondition, selected)) {
                select(selected);
            }
        }
    }

    private Condition getSelectedCondition() {
        final int N = getVisibleConditions();
        for (int i = 0; i < N; i++) {
            final ConditionTag tag = getConditionTagAt(i);
            if (tag != null && tag.rb.isChecked()) {
                return tag.condition;
            }
        }
        return null;
    }

    private int getSelectedZen(int defValue) {
        final Object zen = mZenButtons.getSelectedValue();
        return zen != null ? (Integer) zen : defValue;
    }

    private void updateWidgets() {
        if (mTransitionHelper.isTransitioning()) {
            mTransitionHelper.pendingUpdateWidgets();
            return;
        }
        final int zen = getSelectedZen(Global.ZEN_MODE_OFF);
        final boolean zenOff = zen == Global.ZEN_MODE_OFF;
        final boolean zenImportant = zen == Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        final boolean zenNone = zen == Global.ZEN_MODE_NO_INTERRUPTIONS;
        final boolean expanded = !mHidden && mExpanded;

        mZenButtons.setVisibility(mHidden ? GONE : VISIBLE);
        mZenSubhead.setVisibility(!mHidden && !zenOff ? VISIBLE : GONE);
        mZenSubheadExpanded.setVisibility(expanded ? VISIBLE : GONE);
        mZenSubheadCollapsed.setVisibility(!expanded ? VISIBLE : GONE);
        mMoreSettings.setVisibility(zenImportant && expanded ? VISIBLE : GONE);
        mZenConditions.setVisibility(!zenOff && expanded ? VISIBLE : GONE);

        if (zenNone) {
            mZenSubheadExpanded.setText(R.string.zen_no_interruptions_with_warning);
            mZenSubheadCollapsed.setText(mExitConditionText);
        } else if (zenImportant) {
            mZenSubheadExpanded.setText(R.string.zen_important_interruptions);
            mZenSubheadCollapsed.setText(mExitConditionText);
        }
        mZenSubheadExpanded.setTextColor(zenNone && mPrefs.isNoneDangerous()
                ? mSubheadWarningColor : mSubheadColor);
    }

    private Condition parseExistingTimeCondition(Condition condition) {
        if (condition == null) return null;
        final long time = ZenModeConfig.tryParseCountdownConditionId(condition.id);
        if (time == 0) return null;
        final long now = System.currentTimeMillis();
        final long span = time - now;
        if (span <= 0 || span > MAX_BUCKET_MINUTES * MINUTES_MS) return null;
        return ZenModeConfig.toTimeCondition(mContext,
                time, Math.round(span / (float) MINUTES_MS), now, ActivityManager.getCurrentUser());
    }

    private void handleUpdateConditions(Condition[] conditions) {
        conditions = trimConditions(conditions);
        if (Arrays.equals(conditions, mConditions)) {
            final int count = mConditions == null ? 0 : mConditions.length;
            if (DEBUG) Log.d(mTag, "handleUpdateConditions unchanged conditionCount=" + count);
            return;
        }
        mConditions = conditions;
        handleUpdateConditions();
    }

    private Condition[] trimConditions(Condition[] conditions) {
        if (conditions == null || conditions.length <= mMaxOptionalConditions) {
            // no need to trim
            return conditions;
        }
        // look for current exit condition, ensure it is included if found
        int found = -1;
        for (int i = 0; i < conditions.length; i++) {
            final Condition c = conditions[i];
            if (mSessionExitCondition != null && sameConditionId(mSessionExitCondition, c)) {
                found = i;
                break;
            }
        }
        final Condition[] rt = Arrays.copyOf(conditions, mMaxOptionalConditions);
        if (found >= mMaxOptionalConditions) {
            // found after the first N, promote to the end of the first N
            rt[mMaxOptionalConditions - 1] = conditions[found];
        }
        return rt;
    }

    private void handleUpdateConditions() {
        if (mTransitionHelper.isTransitioning()) {
            mTransitionHelper.pendingUpdateConditions();
            return;
        }
        final int conditionCount = mConditions == null ? 0 : mConditions.length;
        if (DEBUG) Log.d(mTag, "handleUpdateConditions conditionCount=" + conditionCount);
        // forever
        bind(forever(), mZenConditions.getChildAt(FOREVER_CONDITION_INDEX));
        // countdown
        if (mCountdownConditionSupported && mTimeCondition != null) {
            bind(mTimeCondition, mZenConditions.getChildAt(COUNTDOWN_CONDITION_INDEX));
        }
        // provider conditions
        for (int i = 0; i < conditionCount; i++) {
            bind(mConditions[i], mZenConditions.getChildAt(mFirstConditionIndex + i));
        }
        // hide the rest
        for (int i = mZenConditions.getChildCount() - 1; i > mFirstConditionIndex + conditionCount;
                i--) {
            mZenConditions.getChildAt(i).setVisibility(GONE);
        }
        // ensure something is selected
        if (mExpanded) {
            ensureSelection();
        }
    }

    private Condition forever() {
        return new Condition(mForeverId, foreverSummary(), "", "", 0 /*icon*/, Condition.STATE_TRUE,
                0 /*flags*/);
    }

    private String foreverSummary() {
        return mContext.getString(com.android.internal.R.string.zen_mode_forever);
    }

    private ConditionTag getConditionTagAt(int index) {
        return (ConditionTag) mZenConditions.getChildAt(index).getTag();
    }

    private int getVisibleConditions() {
        int rt = 0;
        final int N = mZenConditions.getChildCount();
        for (int i = 0; i < N; i++) {
            rt += mZenConditions.getChildAt(i).getVisibility() == VISIBLE ? 1 : 0;
        }
        return rt;
    }

    private void hideAllConditions() {
        final int N = mZenConditions.getChildCount();
        for (int i = 0; i < N; i++) {
            mZenConditions.getChildAt(i).setVisibility(GONE);
        }
    }

    private void ensureSelection() {
        // are we left without anything selected?  if so, set a default
        final int visibleConditions = getVisibleConditions();
        if (visibleConditions == 0) return;
        for (int i = 0; i < visibleConditions; i++) {
            final ConditionTag tag = getConditionTagAt(i);
            if (tag != null && tag.rb.isChecked()) {
                if (DEBUG) Log.d(mTag, "Not selecting a default, checked=" + tag.condition);
                return;
            }
        }
        final ConditionTag foreverTag = getConditionTagAt(FOREVER_CONDITION_INDEX);
        if (foreverTag == null) return;
        if (DEBUG) Log.d(mTag, "Selecting a default");
        final int favoriteIndex = mPrefs.getMinuteIndex();
        if (favoriteIndex == -1 || !mCountdownConditionSupported) {
            foreverTag.rb.setChecked(true);
        } else {
            mTimeCondition = ZenModeConfig.toTimeCondition(mContext,
                    MINUTE_BUCKETS[favoriteIndex], ActivityManager.getCurrentUser());
            mBucketIndex = favoriteIndex;
            bind(mTimeCondition, mZenConditions.getChildAt(COUNTDOWN_CONDITION_INDEX));
            getConditionTagAt(COUNTDOWN_CONDITION_INDEX).rb.setChecked(true);
        }
    }

    private void handleExitConditionChanged(Condition exitCondition) {
        setExitCondition(exitCondition);
        if (DEBUG) Log.d(mTag, "handleExitConditionChanged " + mExitCondition);
        final int N = getVisibleConditions();
        for (int i = 0; i < N; i++) {
            final ConditionTag tag = getConditionTagAt(i);
            if (tag != null) {
                if (sameConditionId(tag.condition, mExitCondition)) {
                    bind(exitCondition, mZenConditions.getChildAt(i));
                }
            }
        }
    }

    private boolean isCountdown(Condition c) {
        return c != null && ZenModeConfig.isValidCountdownConditionId(c.id);
    }

    private boolean isForever(Condition c) {
        return c != null && mForeverId.equals(c.id);
    }

    private void bind(final Condition condition, final View row) {
        if (condition == null) throw new IllegalArgumentException("condition must not be null");
        final boolean enabled = condition.state == Condition.STATE_TRUE;
        final ConditionTag tag =
                row.getTag() != null ? (ConditionTag) row.getTag() : new ConditionTag();
        row.setTag(tag);
        final boolean first = tag.rb == null;
        if (tag.rb == null) {
            tag.rb = (RadioButton) row.findViewById(android.R.id.checkbox);
        }
        tag.condition = condition;
        final Uri conditionId = getConditionId(tag.condition);
        if (DEBUG) Log.d(mTag, "bind i=" + mZenConditions.indexOfChild(row) + " first=" + first
                + " condition=" + conditionId);
        tag.rb.setEnabled(enabled);
        final boolean checked = (mSessionExitCondition != null
                    || mAttachedZen != Global.ZEN_MODE_OFF)
                && (sameConditionId(mSessionExitCondition, tag.condition)
                    || isCountdown(mSessionExitCondition) && isCountdown(tag.condition));
        if (checked != tag.rb.isChecked()) {
            if (DEBUG) Log.d(mTag, "bind checked=" + checked + " condition=" + conditionId);
            tag.rb.setChecked(checked);
        }
        tag.rb.setOnCheckedChangeListener(new OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (mExpanded && isChecked) {
                    if (DEBUG) Log.d(mTag, "onCheckedChanged " + conditionId);
                    final int N = getVisibleConditions();
                    for (int i = 0; i < N; i++) {
                        final ConditionTag childTag = getConditionTagAt(i);
                        if (childTag == null || childTag == tag) continue;
                        childTag.rb.setChecked(false);
                    }
                    select(tag.condition);
                    announceConditionSelection(tag);
                }
            }
        });

        if (tag.lines == null) {
            tag.lines = row.findViewById(android.R.id.content);
        }
        if (tag.line1 == null) {
            tag.line1 = (TextView) row.findViewById(android.R.id.text1);
        }
        if (tag.line2 == null) {
            tag.line2 = (TextView) row.findViewById(android.R.id.text2);
        }
        final String line1 = !TextUtils.isEmpty(condition.line1) ? condition.line1
                : condition.summary;
        final String line2 = condition.line2;
        tag.line1.setText(line1);
        if (TextUtils.isEmpty(line2)) {
            tag.line2.setVisibility(GONE);
        } else {
            tag.line2.setVisibility(VISIBLE);
            tag.line2.setText(line2);
        }
        tag.lines.setEnabled(enabled);
        tag.lines.setAlpha(enabled ? 1 : .4f);

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
        tag.lines.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                tag.rb.setChecked(true);
            }
        });

        final long time = ZenModeConfig.tryParseCountdownConditionId(conditionId);
        if (time > 0) {
            button1.setVisibility(VISIBLE);
            button2.setVisibility(VISIBLE);
            if (mBucketIndex > -1) {
                button1.setEnabled(mBucketIndex > 0);
                button2.setEnabled(mBucketIndex < MINUTE_BUCKETS.length - 1);
            } else {
                final long span = time - System.currentTimeMillis();
                button1.setEnabled(span > MIN_BUCKET_MINUTES * MINUTES_MS);
                final Condition maxCondition = ZenModeConfig.toTimeCondition(mContext,
                        MAX_BUCKET_MINUTES, ActivityManager.getCurrentUser());
                button2.setEnabled(!Objects.equals(condition.summary, maxCondition.summary));
            }

            button1.setAlpha(button1.isEnabled() ? 1f : .5f);
            button2.setAlpha(button2.isEnabled() ? 1f : .5f);
        } else {
            button1.setVisibility(GONE);
            button2.setVisibility(GONE);
        }
        // wire up interaction callbacks for newly-added condition rows
        if (first) {
            Interaction.register(tag.rb, mInteractionCallback);
            Interaction.register(tag.lines, mInteractionCallback);
            Interaction.register(button1, mInteractionCallback);
            Interaction.register(button2, mInteractionCallback);
        }
        row.setVisibility(VISIBLE);
    }

    private void announceConditionSelection(ConditionTag tag) {
        final int zen = getSelectedZen(Global.ZEN_MODE_OFF);
        String modeText;
        switch(zen) {
            case Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS:
                modeText = mContext.getString(R.string.zen_important_interruptions);
                break;
            case Global.ZEN_MODE_NO_INTERRUPTIONS:
                modeText = mContext.getString(R.string.zen_no_interruptions);
                break;
             default:
                return;
        }
        announceForAccessibility(mContext.getString(R.string.zen_mode_and_condition, modeText,
                tag.line1.getText()));
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
                    newCondition = ZenModeConfig.toTimeCondition(mContext,
                            bucketTime, bucketMinutes, now, ActivityManager.getCurrentUser());
                    break;
                }
            }
            if (newCondition == null) {
                mBucketIndex = DEFAULT_BUCKET_INDEX;
                newCondition = ZenModeConfig.toTimeCondition(mContext,
                        MINUTE_BUCKETS[mBucketIndex], ActivityManager.getCurrentUser());
            }
        } else {
            // on a known index, simply increment or decrement
            mBucketIndex = Math.max(0, Math.min(N - 1, mBucketIndex + (up ? 1 : -1)));
            newCondition = ZenModeConfig.toTimeCondition(mContext,
                    MINUTE_BUCKETS[mBucketIndex], ActivityManager.getCurrentUser());
        }
        mTimeCondition = newCondition;
        bind(mTimeCondition, row);
        tag.rb.setChecked(true);
        select(mTimeCondition);
        announceConditionSelection(tag);
    }

    private void select(final Condition condition) {
        if (DEBUG) Log.d(mTag, "select " + condition);
        final boolean isForever = isForever(condition);
        if (mController != null) {
            AsyncTask.execute(new Runnable() {
                @Override
                public void run() {
                    mController.setExitCondition(isForever ? null : condition);
                }
            });
        }
        setExitCondition(condition);
        if (isForever) {
            mPrefs.setMinuteIndex(-1);
        } else if (isCountdown(condition) && mBucketIndex != -1) {
            mPrefs.setMinuteIndex(mBucketIndex);
        }
        setSessionExitCondition(copy(condition));
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
                handleUpdateConditions((Condition[]) msg.obj);
            } else if (msg.what == EXIT_CONDITION_CHANGED) {
                handleExitConditionChanged((Condition) msg.obj);
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
        View lines;
        TextView line1;
        TextView line2;
        Condition condition;
    }

    private final class Prefs implements OnSharedPreferenceChangeListener {
        private static final String KEY_MINUTE_INDEX = "minuteIndex";
        private static final String KEY_NONE_SELECTED = "noneSelected";

        private final int mNoneDangerousThreshold;

        private int mMinuteIndex;
        private int mNoneSelected;

        private Prefs() {
            mNoneDangerousThreshold = mContext.getResources()
                    .getInteger(R.integer.zen_mode_alarm_warning_threshold);
            prefs().registerOnSharedPreferenceChangeListener(this);
            updateMinuteIndex();
            updateNoneSelected();
        }

        public boolean isNoneDangerous() {
            return mNoneSelected < mNoneDangerousThreshold;
        }

        public void trackNoneSelected() {
            mNoneSelected = clampNoneSelected(mNoneSelected + 1);
            if (DEBUG) Log.d(mTag, "Setting none selected: " + mNoneSelected + " threshold="
                    + mNoneDangerousThreshold);
            prefs().edit().putInt(KEY_NONE_SELECTED, mNoneSelected).apply();
        }

        public int getMinuteIndex() {
            return mMinuteIndex;
        }

        public void setMinuteIndex(int minuteIndex) {
            minuteIndex = clampIndex(minuteIndex);
            if (minuteIndex == mMinuteIndex) return;
            mMinuteIndex = clampIndex(minuteIndex);
            if (DEBUG) Log.d(mTag, "Setting favorite minute index: " + mMinuteIndex);
            prefs().edit().putInt(KEY_MINUTE_INDEX, mMinuteIndex).apply();
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
            updateMinuteIndex();
            updateNoneSelected();
        }

        private SharedPreferences prefs() {
            return mContext.getSharedPreferences(mContext.getPackageName(), 0);
        }

        private void updateMinuteIndex() {
            mMinuteIndex = clampIndex(prefs().getInt(KEY_MINUTE_INDEX, DEFAULT_BUCKET_INDEX));
            if (DEBUG) Log.d(mTag, "Favorite minute index: " + mMinuteIndex);
        }

        private int clampIndex(int index) {
            return MathUtils.constrain(index, -1, MINUTE_BUCKETS.length - 1);
        }

        private void updateNoneSelected() {
            mNoneSelected = clampNoneSelected(prefs().getInt(KEY_NONE_SELECTED, 0));
            if (DEBUG) Log.d(mTag, "None selected: " + mNoneSelected);
        }

        private int clampNoneSelected(int noneSelected) {
            return MathUtils.constrain(noneSelected, 0, Integer.MAX_VALUE);
        }
    }

    private final SegmentedButtons.Callback mZenButtonsCallback = new SegmentedButtons.Callback() {
        @Override
        public void onSelected(final Object value) {
            if (value != null && mZenButtons.isShown()) {
                if (DEBUG) Log.d(mTag, "mZenButtonsCallback selected=" + value);
                AsyncTask.execute(new Runnable() {
                    @Override
                    public void run() {
                        mController.setZen((Integer) value);
                    }
                });
            }
        }

        @Override
        public void onInteraction() {
            fireInteraction();
        }
    };

    private final Interaction.Callback mInteractionCallback = new Interaction.Callback() {
        @Override
        public void onInteraction() {
            fireInteraction();
        }
    };

    private final class TransitionHelper implements TransitionListener, Runnable {
        private final ArraySet<View> mTransitioningViews = new ArraySet<View>();

        private boolean mTransitioning;
        private boolean mPendingUpdateConditions;
        private boolean mPendingUpdateWidgets;

        public void clear() {
            mTransitioningViews.clear();
            mPendingUpdateConditions = mPendingUpdateWidgets = false;
        }

        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            pw.println("  TransitionHelper state:");
            pw.print("    mPendingUpdateConditions="); pw.println(mPendingUpdateConditions);
            pw.print("    mPendingUpdateWidgets="); pw.println(mPendingUpdateWidgets);
            pw.print("    mTransitioning="); pw.println(mTransitioning);
            pw.print("    mTransitioningViews="); pw.println(mTransitioningViews);
        }

        public void pendingUpdateConditions() {
            mPendingUpdateConditions = true;
        }

        public void pendingUpdateWidgets() {
            mPendingUpdateWidgets = true;
        }

        public boolean isTransitioning() {
            return !mTransitioningViews.isEmpty();
        }

        @Override
        public void startTransition(LayoutTransition transition,
                ViewGroup container, View view, int transitionType) {
            mTransitioningViews.add(view);
            updateTransitioning();
        }

        @Override
        public void endTransition(LayoutTransition transition,
                ViewGroup container, View view, int transitionType) {
            mTransitioningViews.remove(view);
            updateTransitioning();
        }

        @Override
        public void run() {
            if (DEBUG) Log.d(mTag, "TransitionHelper run"
                    + " mPendingUpdateWidgets=" + mPendingUpdateWidgets
                    + " mPendingUpdateConditions=" + mPendingUpdateConditions);
            if (mPendingUpdateWidgets) {
                updateWidgets();
            }
            if (mPendingUpdateConditions) {
                handleUpdateConditions();
            }
            mPendingUpdateWidgets = mPendingUpdateConditions = false;
        }

        private void updateTransitioning() {
            final boolean transitioning = isTransitioning();
            if (mTransitioning == transitioning) return;
            mTransitioning = transitioning;
            if (DEBUG) Log.d(mTag, "TransitionHelper mTransitioning=" + mTransitioning);
            if (!mTransitioning) {
                if (mPendingUpdateConditions || mPendingUpdateWidgets) {
                    mHandler.post(this);
                } else {
                    mPendingUpdateConditions = mPendingUpdateWidgets = false;
                }
            }
        }
    }
}
