/**
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
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.service.notification.Condition;
import android.service.notification.ZenModeConfig;
import android.service.notification.ZenModeConfig.ZenRule;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.util.Log;
import android.util.MathUtils;
import android.util.Slog;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.Prefs;
import com.android.systemui.R;
import com.android.systemui.statusbar.policy.ZenModeController;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.Objects;

public class ZenModePanel extends FrameLayout {
    private static final String TAG = "ZenModePanel";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    public static final int STATE_MODIFY = 0;
    public static final int STATE_AUTO_RULE = 1;
    public static final int STATE_OFF = 2;

    private static final int SECONDS_MS = 1000;
    private static final int MINUTES_MS = 60 * SECONDS_MS;

    private static final int[] MINUTE_BUCKETS = ZenModeConfig.MINUTE_BUCKETS;
    private static final int MIN_BUCKET_MINUTES = MINUTE_BUCKETS[0];
    private static final int MAX_BUCKET_MINUTES = MINUTE_BUCKETS[MINUTE_BUCKETS.length - 1];
    private static final int DEFAULT_BUCKET_INDEX = Arrays.binarySearch(MINUTE_BUCKETS, 60);
    private static final int FOREVER_CONDITION_INDEX = 0;
    private static final int COUNTDOWN_CONDITION_INDEX = 1;
    private static final int COUNTDOWN_ALARM_CONDITION_INDEX = 2;
    private static final int COUNTDOWN_CONDITION_COUNT = 2;

    public static final Intent ZEN_SETTINGS
            = new Intent(Settings.ACTION_ZEN_MODE_SETTINGS);
    public static final Intent ZEN_PRIORITY_SETTINGS
            = new Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS);

    private static final long TRANSITION_DURATION = 300;

    private final Context mContext;
    protected final LayoutInflater mInflater;
    private final H mHandler = new H();
    private final ZenPrefs mPrefs;
    private final TransitionHelper mTransitionHelper = new TransitionHelper();
    private final Uri mForeverId;
    private final ConfigurableTexts mConfigurableTexts;

    private String mTag = TAG + "/" + Integer.toHexString(System.identityHashCode(this));

    protected SegmentedButtons mZenButtons;
    private View mZenIntroduction;
    private TextView mZenIntroductionMessage;
    private View mZenIntroductionConfirm;
    private TextView mZenIntroductionCustomize;
    protected LinearLayout mZenConditions;
    private TextView mZenAlarmWarning;
    private RadioGroup mZenRadioGroup;
    private LinearLayout mZenRadioGroupContent;

    private Callback mCallback;
    private ZenModeController mController;
    private Condition mExitCondition;
    private int mBucketIndex = -1;
    private boolean mExpanded;
    private boolean mHidden;
    private int mSessionZen;
    private int mAttachedZen;
    private boolean mAttached;
    private Condition mSessionExitCondition;
    private boolean mVoiceCapable;

    protected int mZenModeConditionLayoutId;
    protected int mZenModeButtonLayoutId;
    private View mEmpty;
    private TextView mEmptyText;
    private ImageView mEmptyIcon;
    private View mAutoRule;
    private TextView mAutoTitle;
    private int mState = STATE_MODIFY;
    private ViewGroup mEdit;

    public ZenModePanel(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
        mPrefs = new ZenPrefs();
        mInflater = LayoutInflater.from(mContext);
        mForeverId = Condition.newId(mContext).appendPath("forever").build();
        mConfigurableTexts = new ConfigurableTexts(mContext);
        mVoiceCapable = Util.isVoiceCapable(mContext);
        mZenModeConditionLayoutId = R.layout.zen_mode_condition;
        mZenModeButtonLayoutId = R.layout.zen_mode_button;
        if (DEBUG) Log.d(mTag, "new ZenModePanel");
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("ZenModePanel state:");
        pw.print("  mAttached="); pw.println(mAttached);
        pw.print("  mHidden="); pw.println(mHidden);
        pw.print("  mExpanded="); pw.println(mExpanded);
        pw.print("  mSessionZen="); pw.println(mSessionZen);
        pw.print("  mAttachedZen="); pw.println(mAttachedZen);
        pw.print("  mConfirmedPriorityIntroduction=");
        pw.println(mPrefs.mConfirmedPriorityIntroduction);
        pw.print("  mConfirmedSilenceIntroduction=");
        pw.println(mPrefs.mConfirmedSilenceIntroduction);
        pw.print("  mVoiceCapable="); pw.println(mVoiceCapable);
        mTransitionHelper.dump(fd, pw, args);
    }

    protected void createZenButtons() {
        mZenButtons = findViewById(R.id.zen_buttons);
        mZenButtons.addButton(R.string.interruption_level_none_twoline,
                R.string.interruption_level_none_with_warning,
                Global.ZEN_MODE_NO_INTERRUPTIONS);
        mZenButtons.addButton(R.string.interruption_level_alarms_twoline,
                R.string.interruption_level_alarms,
                Global.ZEN_MODE_ALARMS);
        mZenButtons.addButton(R.string.interruption_level_priority_twoline,
                R.string.interruption_level_priority,
                Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS);
        mZenButtons.setCallback(mZenButtonsCallback);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        createZenButtons();
        mZenIntroduction = findViewById(R.id.zen_introduction);
        mZenIntroductionMessage = findViewById(R.id.zen_introduction_message);
        mZenIntroductionConfirm = findViewById(R.id.zen_introduction_confirm);
        mZenIntroductionConfirm.setOnClickListener(v -> confirmZenIntroduction());
        mZenIntroductionCustomize = findViewById(R.id.zen_introduction_customize);
        mZenIntroductionCustomize.setOnClickListener(v -> {
            confirmZenIntroduction();
            if (mCallback != null) {
                mCallback.onPrioritySettings();
            }
        });
        mConfigurableTexts.add(mZenIntroductionCustomize, R.string.zen_priority_customize_button);

        mZenConditions = findViewById(R.id.zen_conditions);
        mZenAlarmWarning = findViewById(R.id.zen_alarm_warning);
        mZenRadioGroup = findViewById(R.id.zen_radio_buttons);
        mZenRadioGroupContent = findViewById(R.id.zen_radio_buttons_content);

        mEdit = findViewById(R.id.edit_container);

        mEmpty = findViewById(android.R.id.empty);
        mEmpty.setVisibility(INVISIBLE);
        mEmptyText = mEmpty.findViewById(android.R.id.title);
        mEmptyIcon = mEmpty.findViewById(android.R.id.icon);

        mAutoRule = findViewById(R.id.auto_rule);
        mAutoTitle = mAutoRule.findViewById(android.R.id.title);
        mAutoRule.setVisibility(INVISIBLE);
    }

    public void setEmptyState(int icon, int text) {
        mEmptyIcon.post(() -> {
            mEmptyIcon.setImageResource(icon);
            mEmptyText.setText(text);
        });
    }

    public void setAutoText(CharSequence text) {
        mAutoTitle.post(() -> mAutoTitle.setText(text));
    }

    public void setState(int state) {
        if (mState == state) return;
        transitionFrom(getView(mState), getView(state));
        mState = state;
    }

    private void transitionFrom(View from, View to) {
        from.post(() -> {
            // TODO: Better transitions
            to.setAlpha(0);
            to.setVisibility(VISIBLE);
            to.bringToFront();
            to.animate().cancel();
            to.animate().alpha(1)
                    .setDuration(TRANSITION_DURATION)
                    .withEndAction(() -> from.setVisibility(INVISIBLE))
                    .start();
        });
    }

    private View getView(int state) {
        switch (state) {
            case STATE_AUTO_RULE:
                return mAutoRule;
            case STATE_OFF:
                return mEmpty;
            default:
                return mEdit;
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mConfigurableTexts.update();
        if (mZenButtons != null) {
            mZenButtons.update();
        }
    }

    private void confirmZenIntroduction() {
        final String prefKey = prefKeyForConfirmation(getSelectedZen(Global.ZEN_MODE_OFF));
        if (prefKey == null) return;
        if (DEBUG) Log.d(TAG, "confirmZenIntroduction " + prefKey);
        Prefs.putBoolean(mContext, prefKey, true);
        mHandler.sendEmptyMessage(H.UPDATE_WIDGETS);
    }

    private static String prefKeyForConfirmation(int zen) {
        switch (zen) {
            case Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS:
                return Prefs.Key.DND_CONFIRMED_PRIORITY_INTRODUCTION;
            case Global.ZEN_MODE_NO_INTERRUPTIONS:
                return Prefs.Key.DND_CONFIRMED_SILENCE_INTRODUCTION;
            case Global.ZEN_MODE_ALARMS:
                return Prefs.Key.DND_CONFIRMED_ALARM_INTRODUCTION;
            default:
                return null;
        }
    }

    private void onAttach() {
        setExpanded(true);
        mAttachedZen = mController.getZen();
        ZenRule manualRule = mController.getManualRule();
        mExitCondition = manualRule != null ? manualRule.condition : null;
        if (DEBUG) Log.d(mTag, "onAttach " + mAttachedZen + " " + manualRule);
        handleUpdateManualRule(manualRule);
        mZenButtons.setSelectedValue(mAttachedZen, false);
        mSessionZen = mAttachedZen;
        mTransitionHelper.clear();
        mController.addCallback(mZenCallback);
        setSessionExitCondition(copy(mExitCondition));
        updateWidgets();
        setAttached(true);
    }

    private void onDetach() {
        if (DEBUG) Log.d(mTag, "onDetach");
        setExpanded(false);
        checkForAttachedZenChange();
        setAttached(false);
        mAttachedZen = -1;
        mSessionZen = -1;
        mController.removeCallback(mZenCallback);
        setSessionExitCondition(null);
        mTransitionHelper.clear();
    }

    @VisibleForTesting
    void setAttached(boolean attached) {
        mAttached = attached;
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (isVisible == mAttached) return;
        if (isVisible) {
            onAttach();
        } else {
            onDetach();
        }
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
        if (DEBUG) Log.d(mTag, "setExpanded " + expanded);
        mExpanded = expanded;
        updateWidgets();
        fireExpanded();
    }

    protected void addZenConditions(int count) {
        for (int i = 0; i < count; i++) {
            final View rb = mInflater.inflate(mZenModeButtonLayoutId, mEdit, false);
            rb.setId(i);
            mZenRadioGroup.addView(rb);
            final View rbc = mInflater.inflate(mZenModeConditionLayoutId, mEdit, false);
            rbc.setId(i + count);
            mZenRadioGroupContent.addView(rbc);
        }
    }

    public void init(ZenModeController controller) {
        mController = controller;
        final int minConditions = 1 /*forever*/ + COUNTDOWN_CONDITION_COUNT;
        addZenConditions(minConditions);
        mSessionZen = getSelectedZen(-1);
        handleUpdateManualRule(mController.getManualRule());
        if (DEBUG) Log.d(mTag, "init mExitCondition=" + mExitCondition);
        hideAllConditions();
    }

    private void setExitCondition(Condition exitCondition) {
        if (Objects.equals(mExitCondition, exitCondition)) return;
        mExitCondition = exitCondition;
        if (DEBUG) Log.d(mTag, "mExitCondition=" + getConditionId(mExitCondition));
        updateWidgets();
    }

    private static Uri getConditionId(Condition condition) {
        return condition != null ? condition.id : null;
    }

    private Uri getRealConditionId(Condition condition) {
        return isForever(condition) ? null : getConditionId(condition);
    }

    private static Condition copy(Condition condition) {
        return condition == null ? null : condition.copy();
    }

    public void setCallback(Callback callback) {
        mCallback = callback;
    }

    @VisibleForTesting
    void handleUpdateManualRule(ZenRule rule) {
        final int zen = rule != null ? rule.zenMode : Global.ZEN_MODE_OFF;
        handleUpdateZen(zen);
        final Condition c = rule == null ? null
                : rule.condition != null ? rule.condition
                : createCondition(rule.conditionId);
        handleUpdateConditions(c);
        setExitCondition(c);
    }

    private Condition createCondition(Uri conditionId) {
        if (ZenModeConfig.isValidCountdownToAlarmConditionId(conditionId)) {
            long time = ZenModeConfig.tryParseCountdownConditionId(conditionId);
            Condition c = ZenModeConfig.toNextAlarmCondition(
                    mContext, time, ActivityManager.getCurrentUser());
            return c;
        } else if (ZenModeConfig.isValidCountdownConditionId(conditionId)) {
            long time = ZenModeConfig.tryParseCountdownConditionId(conditionId);
            int mins = (int) ((time - System.currentTimeMillis() + DateUtils.MINUTE_IN_MILLIS / 2)
                    / DateUtils.MINUTE_IN_MILLIS);
            Condition c = ZenModeConfig.toTimeCondition(mContext, time, mins,
                    ActivityManager.getCurrentUser(), false);
            return c;
        }
        // If there is a manual rule, but it has no condition listed then it is forever.
        return forever();
    }

    private void handleUpdateZen(int zen) {
        if (mSessionZen != -1 && mSessionZen != zen) {
            mSessionZen = zen;
        }
        mZenButtons.setSelectedValue(zen, false /* fromClick */);
        updateWidgets();
    }

    @VisibleForTesting
    int getSelectedZen(int defValue) {
        final Object zen = mZenButtons.getSelectedValue();
        return zen != null ? (Integer) zen : defValue;
    }

    private void updateWidgets() {
        if (mTransitionHelper.isTransitioning()) {
            mTransitionHelper.pendingUpdateWidgets();
            return;
        }
        final int zen = getSelectedZen(Global.ZEN_MODE_OFF);
        final boolean zenImportant = zen == Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        final boolean zenNone = zen == Global.ZEN_MODE_NO_INTERRUPTIONS;
        final boolean zenAlarm = zen == Global.ZEN_MODE_ALARMS;
        final boolean introduction = (zenImportant && !mPrefs.mConfirmedPriorityIntroduction
                || zenNone && !mPrefs.mConfirmedSilenceIntroduction
                || zenAlarm && !mPrefs.mConfirmedAlarmIntroduction);

        mZenButtons.setVisibility(mHidden ? GONE : VISIBLE);
        mZenIntroduction.setVisibility(introduction ? VISIBLE : GONE);
        if (introduction) {
            int message = zenImportant
                    ? R.string.zen_priority_introduction
                    : zenAlarm
                            ? R.string.zen_alarms_introduction
                            : mVoiceCapable
                                    ? R.string.zen_silence_introduction_voice
                                    : R.string.zen_silence_introduction;
            mConfigurableTexts.add(mZenIntroductionMessage, message);
            mConfigurableTexts.update();
            mZenIntroductionCustomize.setVisibility(zenImportant ? VISIBLE : GONE);
        }
        final boolean allowAlarms = !zenNone &&
                !(zenImportant && !mController.areAlarmsAllowedInPriority());
        final String warning = computeAlarmWarningText(allowAlarms);
        mZenAlarmWarning.setVisibility(warning != null ? VISIBLE : GONE);
        mZenAlarmWarning.setText(warning);
    }

    private String computeAlarmWarningText(boolean allowAlarms) {
        // don't show alarm warning if alarms are allowed to bypass dnd
        if (allowAlarms) {
            return null;
        }
        final long now = System.currentTimeMillis();
        final long nextAlarm = mController.getNextAlarm();
        if (nextAlarm < now) {
            return null;
        }
        int warningRes = 0;
        if (mSessionExitCondition == null || isForever(mSessionExitCondition)) {
            warningRes = R.string.zen_alarm_warning_indef;
        } else {
            final long time = ZenModeConfig.tryParseCountdownConditionId(mSessionExitCondition.id);
            if (time > now && nextAlarm < time) {
                warningRes = R.string.zen_alarm_warning;
            }
        }
        if (warningRes == 0) {
            return null;
        }
        final boolean soon = (nextAlarm - now) < 24 * 60 * 60 * 1000;
        final boolean is24 = DateFormat.is24HourFormat(mContext, ActivityManager.getCurrentUser());
        final String skeleton = soon ? (is24 ? "Hm" : "hma") : (is24 ? "EEEHm" : "EEEhma");
        final String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton);
        final CharSequence formattedTime = DateFormat.format(pattern, nextAlarm);
        final int templateRes = soon ? R.string.alarm_template : R.string.alarm_template_far;
        final String template = getResources().getString(templateRes, formattedTime);
        return getResources().getString(warningRes, template);
    }

    @VisibleForTesting
    void handleUpdateConditions(Condition c) {
        if (mTransitionHelper.isTransitioning()) {
            return;
        }
        // forever
        bind(forever(), mZenRadioGroupContent.getChildAt(FOREVER_CONDITION_INDEX),
                FOREVER_CONDITION_INDEX);
        if (c == null) {
            bindGenericCountdown();
            bindNextAlarm(getTimeUntilNextAlarmCondition());
        } else if (isForever(c)) {

            getConditionTagAt(FOREVER_CONDITION_INDEX).rb.setChecked(true);
            bindGenericCountdown();
            bindNextAlarm(getTimeUntilNextAlarmCondition());
        } else {
            if (isAlarm(c)) {
                bindGenericCountdown();
                bindNextAlarm(c);
                getConditionTagAt(COUNTDOWN_ALARM_CONDITION_INDEX).rb.setChecked(true);
            } else if (isCountdown(c)) {
                bindNextAlarm(getTimeUntilNextAlarmCondition());
                bind(c, mZenRadioGroupContent.getChildAt(COUNTDOWN_CONDITION_INDEX),
                        COUNTDOWN_CONDITION_INDEX);
                getConditionTagAt(COUNTDOWN_CONDITION_INDEX).rb.setChecked(true);
            } else {
                Slog.wtf(TAG, "Invalid manual condition: " + c);
            }
        }
        mZenConditions.setVisibility(mSessionZen != Global.ZEN_MODE_OFF ? View.VISIBLE : View.GONE);
    }

    private void bindGenericCountdown() {
        mBucketIndex = DEFAULT_BUCKET_INDEX;
        Condition countdown = ZenModeConfig.toTimeCondition(mContext,
                MINUTE_BUCKETS[mBucketIndex], ActivityManager.getCurrentUser());
        // don't change the hour condition while the user is viewing the panel
        if (!mAttached || getConditionTagAt(COUNTDOWN_CONDITION_INDEX).condition == null) {
            bind(countdown, mZenRadioGroupContent.getChildAt(COUNTDOWN_CONDITION_INDEX),
                    COUNTDOWN_CONDITION_INDEX);
        }
    }

    private void bindNextAlarm(Condition c) {
        View alarmContent = mZenRadioGroupContent.getChildAt(COUNTDOWN_ALARM_CONDITION_INDEX);
        ConditionTag tag = (ConditionTag) alarmContent.getTag();
        // Don't change the alarm condition while the user is viewing the panel
        if (c != null && (!mAttached || tag == null || tag.condition == null)) {
            bind(c, alarmContent, COUNTDOWN_ALARM_CONDITION_INDEX);
        }

        tag = (ConditionTag) alarmContent.getTag();
        boolean showAlarm = tag != null && tag.condition != null;
        mZenRadioGroup.getChildAt(COUNTDOWN_ALARM_CONDITION_INDEX).setVisibility(
                showAlarm ? View.VISIBLE : View.INVISIBLE);
        alarmContent.setVisibility(showAlarm ? View.VISIBLE : View.INVISIBLE);
    }

    private Condition forever() {
        return new Condition(mForeverId, foreverSummary(mContext), "", "", 0 /*icon*/,
                Condition.STATE_TRUE, 0 /*flags*/);
    }

    private static String foreverSummary(Context context) {
        return context.getString(com.android.internal.R.string.zen_mode_forever);
    }

    // Returns a time condition if the next alarm is within the next week.
    private Condition getTimeUntilNextAlarmCondition() {
        GregorianCalendar weekRange = new GregorianCalendar();
        setToMidnight(weekRange);
        weekRange.add(Calendar.DATE, 6);
        final long nextAlarmMs = mController.getNextAlarm();
        if (nextAlarmMs > 0) {
            GregorianCalendar nextAlarm = new GregorianCalendar();
            nextAlarm.setTimeInMillis(nextAlarmMs);
            setToMidnight(nextAlarm);

            if (weekRange.compareTo(nextAlarm) >= 0) {
                return ZenModeConfig.toNextAlarmCondition(mContext, nextAlarmMs,
                        ActivityManager.getCurrentUser());
            }
        }
        return null;
    }

    private void setToMidnight(Calendar calendar) {
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
    }

    @VisibleForTesting
    ConditionTag getConditionTagAt(int index) {
        return (ConditionTag) mZenRadioGroupContent.getChildAt(index).getTag();
    }

    @VisibleForTesting
    int getVisibleConditions() {
        int rt = 0;
        final int N = mZenRadioGroupContent.getChildCount();
        for (int i = 0; i < N; i++) {
            rt += mZenRadioGroupContent.getChildAt(i).getVisibility() == VISIBLE ? 1 : 0;
        }
        return rt;
    }

    private void hideAllConditions() {
        final int N = mZenRadioGroupContent.getChildCount();
        for (int i = 0; i < N; i++) {
            mZenRadioGroupContent.getChildAt(i).setVisibility(GONE);
        }
    }

    private static boolean isAlarm(Condition c) {
        return c != null && ZenModeConfig.isValidCountdownToAlarmConditionId(c.id);
    }

    private static boolean isCountdown(Condition c) {
        return c != null && ZenModeConfig.isValidCountdownConditionId(c.id);
    }

    private boolean isForever(Condition c) {
        return c != null && mForeverId.equals(c.id);
    }

    private void bind(final Condition condition, final View row, final int rowId) {
        if (condition == null) throw new IllegalArgumentException("condition must not be null");
        final boolean enabled = condition.state == Condition.STATE_TRUE;
        final ConditionTag tag =
                row.getTag() != null ? (ConditionTag) row.getTag() : new ConditionTag();
        row.setTag(tag);
        final boolean first = tag.rb == null;
        if (tag.rb == null) {
            tag.rb = (RadioButton) mZenRadioGroup.getChildAt(rowId);
        }
        tag.condition = condition;
        final Uri conditionId = getConditionId(tag.condition);
        if (DEBUG) Log.d(mTag, "bind i=" + mZenRadioGroupContent.indexOfChild(row) + " first="
                + first + " condition=" + conditionId);
        tag.rb.setEnabled(enabled);
        tag.rb.setOnCheckedChangeListener(new OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (mExpanded && isChecked) {
                    tag.rb.setChecked(true);
                    if (DEBUG) Log.d(mTag, "onCheckedChanged " + conditionId);
                    MetricsLogger.action(mContext, MetricsEvent.QS_DND_CONDITION_SELECT);
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
            mConfigurableTexts.add(tag.line1);
        }
        if (tag.line2 == null) {
            tag.line2 = (TextView) row.findViewById(android.R.id.text2);
            mConfigurableTexts.add(tag.line2);
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
                onClickTimeButton(row, tag, false /*down*/, rowId);
            }
        });

        final ImageView button2 = (ImageView) row.findViewById(android.R.id.button2);
        button2.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                onClickTimeButton(row, tag, true /*up*/, rowId);
            }
        });
        tag.lines.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                tag.rb.setChecked(true);
            }
        });

        final long time = ZenModeConfig.tryParseCountdownConditionId(conditionId);
        if (rowId != COUNTDOWN_ALARM_CONDITION_INDEX && time > 0) {
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
                modeText = mContext.getString(R.string.interruption_level_priority);
                break;
            case Global.ZEN_MODE_NO_INTERRUPTIONS:
                modeText = mContext.getString(R.string.interruption_level_none);
                break;
            case Global.ZEN_MODE_ALARMS:
                modeText = mContext.getString(R.string.interruption_level_alarms);
                break;
            default:
                return;
        }
        announceForAccessibility(mContext.getString(R.string.zen_mode_and_condition, modeText,
                tag.line1.getText()));
    }

    private void onClickTimeButton(View row, ConditionTag tag, boolean up, int rowId) {
        MetricsLogger.action(mContext, MetricsEvent.QS_DND_TIME, up);
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
                            bucketTime, bucketMinutes, ActivityManager.getCurrentUser(),
                            false /*shortVersion*/);
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
        bind(newCondition, row, rowId);
        tag.rb.setChecked(true);
        select(newCondition);
        announceConditionSelection(tag);
    }

    private void select(final Condition condition) {
        if (DEBUG) Log.d(mTag, "select " + condition);
        if (mSessionZen == -1 || mSessionZen == Global.ZEN_MODE_OFF) {
            if (DEBUG) Log.d(mTag, "Ignoring condition selection outside of manual zen");
            return;
        }
        final Uri realConditionId = getRealConditionId(condition);
        if (mController != null) {
            AsyncTask.execute(new Runnable() {
                @Override
                public void run() {
                    mController.setZen(mSessionZen, realConditionId, TAG + ".selectCondition");
                }
            });
        }
        setExitCondition(condition);
        if (realConditionId == null) {
            mPrefs.setMinuteIndex(-1);
        } else if ((isAlarm(condition) || isCountdown(condition)) && mBucketIndex != -1) {
            mPrefs.setMinuteIndex(mBucketIndex);
        }
        setSessionExitCondition(copy(condition));
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
        public void onManualRuleChanged(ZenRule rule) {
            mHandler.obtainMessage(H.MANUAL_RULE_CHANGED, rule).sendToTarget();
        }
    };

    private final class H extends Handler {
        private static final int MANUAL_RULE_CHANGED = 2;
        private static final int UPDATE_WIDGETS = 3;

        private H() {
            super(Looper.getMainLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MANUAL_RULE_CHANGED: handleUpdateManualRule((ZenRule) msg.obj); break;
                case UPDATE_WIDGETS: updateWidgets(); break;
            }
        }
    }

    public interface Callback {
        void onPrioritySettings();
        void onInteraction();
        void onExpanded(boolean expanded);
    }

    // used as the view tag on condition rows
    @VisibleForTesting
    static class ConditionTag {
        RadioButton rb;
        View lines;
        TextView line1;
        TextView line2;
        Condition condition;
    }

    private final class ZenPrefs implements OnSharedPreferenceChangeListener {
        private final int mNoneDangerousThreshold;

        private int mMinuteIndex;
        private int mNoneSelected;
        private boolean mConfirmedPriorityIntroduction;
        private boolean mConfirmedSilenceIntroduction;
        private boolean mConfirmedAlarmIntroduction;

        private ZenPrefs() {
            mNoneDangerousThreshold = mContext.getResources()
                    .getInteger(R.integer.zen_mode_alarm_warning_threshold);
            Prefs.registerListener(mContext, this);
            updateMinuteIndex();
            updateNoneSelected();
            updateConfirmedPriorityIntroduction();
            updateConfirmedSilenceIntroduction();
            updateConfirmedAlarmIntroduction();
        }

        public void trackNoneSelected() {
            mNoneSelected = clampNoneSelected(mNoneSelected + 1);
            if (DEBUG) Log.d(mTag, "Setting none selected: " + mNoneSelected + " threshold="
                    + mNoneDangerousThreshold);
            Prefs.putInt(mContext, Prefs.Key.DND_NONE_SELECTED, mNoneSelected);
        }

        public int getMinuteIndex() {
            return mMinuteIndex;
        }

        public void setMinuteIndex(int minuteIndex) {
            minuteIndex = clampIndex(minuteIndex);
            if (minuteIndex == mMinuteIndex) return;
            mMinuteIndex = clampIndex(minuteIndex);
            if (DEBUG) Log.d(mTag, "Setting favorite minute index: " + mMinuteIndex);
            Prefs.putInt(mContext, Prefs.Key.DND_FAVORITE_BUCKET_INDEX, mMinuteIndex);
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
            updateMinuteIndex();
            updateNoneSelected();
            updateConfirmedPriorityIntroduction();
            updateConfirmedSilenceIntroduction();
            updateConfirmedAlarmIntroduction();
        }

        private void updateMinuteIndex() {
            mMinuteIndex = clampIndex(Prefs.getInt(mContext,
                    Prefs.Key.DND_FAVORITE_BUCKET_INDEX, DEFAULT_BUCKET_INDEX));
            if (DEBUG) Log.d(mTag, "Favorite minute index: " + mMinuteIndex);
        }

        private int clampIndex(int index) {
            return MathUtils.constrain(index, -1, MINUTE_BUCKETS.length - 1);
        }

        private void updateNoneSelected() {
            mNoneSelected = clampNoneSelected(Prefs.getInt(mContext,
                    Prefs.Key.DND_NONE_SELECTED, 0));
            if (DEBUG) Log.d(mTag, "None selected: " + mNoneSelected);
        }

        private int clampNoneSelected(int noneSelected) {
            return MathUtils.constrain(noneSelected, 0, Integer.MAX_VALUE);
        }

        private void updateConfirmedPriorityIntroduction() {
            final boolean confirmed =  Prefs.getBoolean(mContext,
                    Prefs.Key.DND_CONFIRMED_PRIORITY_INTRODUCTION, false);
            if (confirmed == mConfirmedPriorityIntroduction) return;
            mConfirmedPriorityIntroduction = confirmed;
            if (DEBUG) Log.d(mTag, "Confirmed priority introduction: "
                    + mConfirmedPriorityIntroduction);
        }

        private void updateConfirmedSilenceIntroduction() {
            final boolean confirmed =  Prefs.getBoolean(mContext,
                    Prefs.Key.DND_CONFIRMED_SILENCE_INTRODUCTION, false);
            if (confirmed == mConfirmedSilenceIntroduction) return;
            mConfirmedSilenceIntroduction = confirmed;
            if (DEBUG) Log.d(mTag, "Confirmed silence introduction: "
                    + mConfirmedSilenceIntroduction);
        }

        private void updateConfirmedAlarmIntroduction() {
            final boolean confirmed =  Prefs.getBoolean(mContext,
                    Prefs.Key.DND_CONFIRMED_ALARM_INTRODUCTION, false);
            if (confirmed == mConfirmedAlarmIntroduction) return;
            mConfirmedAlarmIntroduction = confirmed;
            if (DEBUG) Log.d(mTag, "Confirmed alarm introduction: "
                    + mConfirmedAlarmIntroduction);
        }
    }

    protected final SegmentedButtons.Callback mZenButtonsCallback = new SegmentedButtons.Callback() {
        @Override
        public void onSelected(final Object value, boolean fromClick) {
            if (value != null && mZenButtons.isShown() && isAttachedToWindow()) {
                final int zen = (Integer) value;
                if (fromClick) {
                    MetricsLogger.action(mContext, MetricsEvent.QS_DND_ZEN_SELECT, zen);
                }
                if (DEBUG) Log.d(mTag, "mZenButtonsCallback selected=" + zen);
                final Uri realConditionId = getRealConditionId(mSessionExitCondition);
                AsyncTask.execute(new Runnable() {
                    @Override
                    public void run() {
                        mController.setZen(zen, realConditionId, TAG + ".selectZen");
                        if (zen != Global.ZEN_MODE_OFF) {
                            Prefs.putInt(mContext, Prefs.Key.DND_FAVORITE_ZEN, zen);
                        }
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
        private boolean mPendingUpdateWidgets;

        public void clear() {
            mTransitioningViews.clear();
            mPendingUpdateWidgets = false;
        }

        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            pw.println("  TransitionHelper state:");
            pw.print("    mPendingUpdateWidgets="); pw.println(mPendingUpdateWidgets);
            pw.print("    mTransitioning="); pw.println(mTransitioning);
            pw.print("    mTransitioningViews="); pw.println(mTransitioningViews);
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
                    + " mPendingUpdateWidgets=" + mPendingUpdateWidgets);
            if (mPendingUpdateWidgets) {
                updateWidgets();
            }
            mPendingUpdateWidgets = false;
        }

        private void updateTransitioning() {
            final boolean transitioning = isTransitioning();
            if (mTransitioning == transitioning) return;
            mTransitioning = transitioning;
            if (DEBUG) Log.d(mTag, "TransitionHelper mTransitioning=" + mTransitioning);
            if (!mTransitioning) {
                if (mPendingUpdateWidgets) {
                    mHandler.post(this);
                } else {
                    mPendingUpdateWidgets = false;
                }
            }
        }
    }
}
