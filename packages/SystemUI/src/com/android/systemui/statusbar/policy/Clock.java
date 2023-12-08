/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import android.annotation.NonNull;
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.icu.lang.UCharacter;
import android.icu.text.DateTimePatternGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.style.CharacterStyle;
import android.text.style.RelativeSizeSpan;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.android.settingslib.Utils;
import com.android.systemui.Dependency;
import com.android.systemui.FontSizeUtils;
import com.android.systemui.R;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.demomode.DemoModeCommandReceiver;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Digital clock for the status bar.
 */
public class Clock extends TextView implements
        DemoModeCommandReceiver,
        Tunable,
        CommandQueue.Callbacks,
        DarkReceiver, ConfigurationListener {

    public static final String CLOCK_SECONDS = "clock_seconds";
    private static final String CLOCK_SUPER_PARCELABLE = "clock_super_parcelable";
    private static final String CURRENT_USER_ID = "current_user_id";
    private static final String VISIBLE_BY_POLICY = "visible_by_policy";
    private static final String VISIBLE_BY_USER = "visible_by_user";
    private static final String SHOW_SECONDS = "show_seconds";
    private static final String VISIBILITY = "visibility";

    private final UserTracker mUserTracker;
    private final CommandQueue mCommandQueue;
    private int mCurrentUserId;

    private boolean mClockVisibleByPolicy = true;
    private boolean mClockVisibleByUser = true;

    private boolean mAttached;
    private boolean mScreenReceiverRegistered;
    private Calendar mCalendar;
    private String mContentDescriptionFormatString;
    private SimpleDateFormat mClockFormat;
    private SimpleDateFormat mContentDescriptionFormat;
    private Locale mLocale;
    private DateTimePatternGenerator mDateTimePatternGenerator;

    private static final int AM_PM_STYLE_NORMAL  = 0;
    private static final int AM_PM_STYLE_SMALL   = 1;
    private static final int AM_PM_STYLE_GONE    = 2;

    private final int mAmPmStyle;
    private boolean mShowSeconds;
    private Handler mSecondsHandler;

    // Fields to cache the width so the clock remains at an approximately constant width
    private int mCharsAtCurrentWidth = -1;
    private int mCachedWidth = -1;

    /**
     * Color to be set on this {@link TextView}, when wallpaperTextColor is <b>not</b> utilized.
     */
    private int mNonAdaptedColor;

    private final BroadcastDispatcher mBroadcastDispatcher;

    private final UserTracker.Callback mUserChangedCallback =
            new UserTracker.Callback() {
                @Override
                public void onUserChanged(int newUser, @NonNull Context userContext) {
                    mCurrentUserId = newUser;
                    updateClock();
                }
            };

    public Clock(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public Clock(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mCommandQueue = Dependency.get(CommandQueue.class);
        TypedArray a = context.getTheme().obtainStyledAttributes(
                attrs,
                R.styleable.Clock,
                0, 0);
        try {
            mAmPmStyle = a.getInt(R.styleable.Clock_amPmStyle, AM_PM_STYLE_GONE);
            mNonAdaptedColor = getCurrentTextColor();
        } finally {
            a.recycle();
        }
        mBroadcastDispatcher = Dependency.get(BroadcastDispatcher.class);
        mUserTracker = Dependency.get(UserTracker.class);

        setIncludeFontPadding(false);
    }

    @Override
    public Parcelable onSaveInstanceState() {
        Bundle bundle = new Bundle();
        bundle.putParcelable(CLOCK_SUPER_PARCELABLE, super.onSaveInstanceState());
        bundle.putInt(CURRENT_USER_ID, mCurrentUserId);
        bundle.putBoolean(VISIBLE_BY_POLICY, mClockVisibleByPolicy);
        bundle.putBoolean(VISIBLE_BY_USER, mClockVisibleByUser);
        bundle.putBoolean(SHOW_SECONDS, mShowSeconds);
        bundle.putInt(VISIBILITY, getVisibility());

        return bundle;
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
        if (state == null || !(state instanceof Bundle)) {
            super.onRestoreInstanceState(state);
            return;
        }

        Bundle bundle = (Bundle) state;
        Parcelable superState = bundle.getParcelable(CLOCK_SUPER_PARCELABLE);
        super.onRestoreInstanceState(superState);
        if (bundle.containsKey(CURRENT_USER_ID)) {
            mCurrentUserId = bundle.getInt(CURRENT_USER_ID);
        }
        mClockVisibleByPolicy = bundle.getBoolean(VISIBLE_BY_POLICY, true);
        mClockVisibleByUser = bundle.getBoolean(VISIBLE_BY_USER, true);
        mShowSeconds = bundle.getBoolean(SHOW_SECONDS, false);
        if (bundle.containsKey(VISIBILITY)) {
            super.setVisibility(bundle.getInt(VISIBILITY));
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (!mAttached) {
            mAttached = true;
            IntentFilter filter = new IntentFilter();

            filter.addAction(Intent.ACTION_TIME_TICK);
            filter.addAction(Intent.ACTION_TIME_CHANGED);
            filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);

            // NOTE: This receiver could run before this method returns, as it's not dispatching
            // on the main thread and BroadcastDispatcher may not need to register with Context.
            // The receiver will return immediately if the view does not have a Handler yet.
            mBroadcastDispatcher.registerReceiverWithHandler(mIntentReceiver, filter,
                    Dependency.get(Dependency.TIME_TICK_HANDLER), UserHandle.ALL);
            Dependency.get(TunerService.class).addTunable(this, CLOCK_SECONDS,
                    StatusBarIconController.ICON_HIDE_LIST);
            mCommandQueue.addCallback(this);
            mUserTracker.addCallback(mUserChangedCallback, mContext.getMainExecutor());
            mCurrentUserId = mUserTracker.getUserId();
        }

        // The time zone may have changed while the receiver wasn't registered, so update the Time
        mCalendar = Calendar.getInstance(TimeZone.getDefault());
        mContentDescriptionFormatString = "";
        mDateTimePatternGenerator = null;

        // Make sure we update to the current time
        updateClock();
        updateClockVisibility();
        updateShowSeconds();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mScreenReceiverRegistered) {
            mScreenReceiverRegistered = false;
            mBroadcastDispatcher.unregisterReceiver(mScreenReceiver);
            if (mSecondsHandler != null) {
                mSecondsHandler.removeCallbacks(mSecondTick);
                mSecondsHandler = null;
            }
        }
        if (mAttached) {
            mBroadcastDispatcher.unregisterReceiver(mIntentReceiver);
            mAttached = false;
            Dependency.get(TunerService.class).removeTunable(this);
            mCommandQueue.removeCallback(this);
            mUserTracker.removeCallback(mUserChangedCallback);
        }
    }

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // If the handler is null, it means we received a broadcast while the view has not
            // finished being attached or in the process of being detached.
            // In that case, do not post anything.
            Handler handler = getHandler();
            if (handler == null) return;

            String action = intent.getAction();
            if (action.equals(Intent.ACTION_TIMEZONE_CHANGED)) {
                String tz = intent.getStringExtra(Intent.EXTRA_TIMEZONE);
                handler.post(() -> {
                    mCalendar = Calendar.getInstance(TimeZone.getTimeZone(tz));
                    if (mClockFormat != null) {
                        mClockFormat.setTimeZone(mCalendar.getTimeZone());
                    }
                });
            } else if (action.equals(Intent.ACTION_CONFIGURATION_CHANGED)) {
                final Locale newLocale = getResources().getConfiguration().locale;
                handler.post(() -> {
                    if (!newLocale.equals(mLocale)) {
                        mLocale = newLocale;
                         // Force refresh of dependent variables.
                        mContentDescriptionFormatString = "";
                        mDateTimePatternGenerator = null;
                    }
                });
            }
            handler.post(() -> updateClock());
        }
    };

    @Override
    public void setVisibility(int visibility) {
        if (visibility == View.VISIBLE && !shouldBeVisible()) {
            return;
        }

        super.setVisibility(visibility);
    }

    public void setClockVisibleByUser(boolean visible) {
        mClockVisibleByUser = visible;
        updateClockVisibility();
    }

    public void setClockVisibilityByPolicy(boolean visible) {
        mClockVisibleByPolicy = visible;
        updateClockVisibility();
    }

    private boolean shouldBeVisible() {
        return mClockVisibleByPolicy && mClockVisibleByUser;
    }

    private void updateClockVisibility() {
        boolean visible = shouldBeVisible();
        int visibility = visible ? View.VISIBLE : View.GONE;
        super.setVisibility(visibility);
    }

    final void updateClock() {
        if (mDemoMode) return;
        mCalendar.setTimeInMillis(System.currentTimeMillis());
        CharSequence smallTime = getSmallTime();
        // Setting text actually triggers a layout pass (because the text view is set to
        // wrap_content width and TextView always relayouts for this). Avoid needless
        // relayout if the text didn't actually change.
        if (!TextUtils.equals(smallTime, getText())) {
            setText(smallTime);
        }
        setContentDescription(mContentDescriptionFormat.format(mCalendar.getTime()));
    }

    /**
     * In order to avoid the clock growing and shrinking due to proportional fonts, we want to
     * cache the drawn width at a given number of characters (removing the cache when it changes),
     * and only use the biggest value. This means that the clock width with grow to the maximum
     * size over time, but reset whenever the number of characters changes (or the configuration
     * changes)
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        int chars = getText().length();
        if (chars != mCharsAtCurrentWidth) {
            mCharsAtCurrentWidth = chars;
            mCachedWidth = getMeasuredWidth();
            return;
        }

        int measuredWidth = getMeasuredWidth();
        if (mCachedWidth > measuredWidth) {
            setMeasuredDimension(mCachedWidth, getMeasuredHeight());
        } else {
            mCachedWidth = measuredWidth;
        }
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (CLOCK_SECONDS.equals(key)) {
            mShowSeconds = TunerService.parseIntegerSwitch(newValue, false);
            updateShowSeconds();
        } else if (StatusBarIconController.ICON_HIDE_LIST.equals(key)) {
            setClockVisibleByUser(!StatusBarIconController.getIconHideList(getContext(), newValue)
                    .contains("clock"));
            updateClockVisibility();
        }
    }

    @Override
    public void disable(int displayId, int state1, int state2, boolean animate) {
        if (displayId != getDisplay().getDisplayId()) {
            return;
        }
        boolean clockVisibleByPolicy = (state1 & StatusBarManager.DISABLE_CLOCK) == 0;
        if (clockVisibleByPolicy != mClockVisibleByPolicy) {
            setClockVisibilityByPolicy(clockVisibleByPolicy);
        }
    }

    @Override
    public void onDarkChanged(ArrayList<Rect> areas, float darkIntensity, int tint) {
        mNonAdaptedColor = DarkIconDispatcher.getTint(areas, this, tint);
        setTextColor(mNonAdaptedColor);
    }

    // Update text color based when shade scrim changes color.
    public void onColorsChanged(boolean lightTheme) {
        final Context context = new ContextThemeWrapper(mContext,
                lightTheme ? R.style.Theme_SystemUI_LightWallpaper : R.style.Theme_SystemUI);
        setTextColor(Utils.getColorAttrDefaultColor(context, R.attr.wallpaperTextColor));
    }

    @Override
    public void onDensityOrFontScaleChanged() {
        reloadDimens();
    }

    private void reloadDimens() {
        // reset mCachedWidth so the new width would be updated properly when next onMeasure
        mCachedWidth = -1;

        FontSizeUtils.updateFontSize(this, R.dimen.status_bar_clock_size);
        setPaddingRelative(
                mContext.getResources().getDimensionPixelSize(
                        R.dimen.status_bar_clock_starting_padding),
                0,
                mContext.getResources().getDimensionPixelSize(
                        R.dimen.status_bar_clock_end_padding),
                0);

        float fontHeight = getPaint().getFontMetricsInt(null);
        setLineHeight(TypedValue.COMPLEX_UNIT_PX, fontHeight);

        ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp != null) {
            lp.height = (int) Math.ceil(fontHeight);
            setLayoutParams(lp);
        }
    }

    private void updateShowSeconds() {
        if (mShowSeconds) {
            // Wait until we have a display to start trying to show seconds.
            if (mSecondsHandler == null && getDisplay() != null) {
                mSecondsHandler = new Handler();
                if (getDisplay().getState() == Display.STATE_ON) {
                    mSecondsHandler.postAtTime(mSecondTick,
                            SystemClock.uptimeMillis() / 1000 * 1000 + 1000);
                }
                mScreenReceiverRegistered = true;
                IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
                filter.addAction(Intent.ACTION_SCREEN_ON);
                mBroadcastDispatcher.registerReceiver(mScreenReceiver, filter);
            }
        } else {
            if (mSecondsHandler != null) {
                mScreenReceiverRegistered = false;
                mBroadcastDispatcher.unregisterReceiver(mScreenReceiver);
                mSecondsHandler.removeCallbacks(mSecondTick);
                mSecondsHandler = null;
                updateClock();
            }
        }
    }

    private final CharSequence getSmallTime() {
        Context context = getContext();
        boolean is24 = DateFormat.is24HourFormat(context, mCurrentUserId);
        if (mDateTimePatternGenerator == null) {
            // Despite its name, getInstance creates a cloned instance, so reuse the generator to
            // avoid unnecessary churn.
            mDateTimePatternGenerator = DateTimePatternGenerator.getInstance(
                context.getResources().getConfiguration().locale);
        }

        final char MAGIC1 = '\uEF00';
        final char MAGIC2 = '\uEF01';

        final String formatSkeleton = mShowSeconds
                ? is24 ? "Hms" : "hms"
                : is24 ? "Hm" : "hm";
        String format = mDateTimePatternGenerator.getBestPattern(formatSkeleton);
        if (!format.equals(mContentDescriptionFormatString)) {
            mContentDescriptionFormatString = format;
            mContentDescriptionFormat = new SimpleDateFormat(format);
            /*
             * Search for an unquoted "a" in the format string, so we can
             * add marker characters around it to let us find it again after
             * formatting and change its size.
             */
            if (mAmPmStyle != AM_PM_STYLE_NORMAL) {
                int a = -1;
                boolean quoted = false;
                for (int i = 0; i < format.length(); i++) {
                    char c = format.charAt(i);

                    if (c == '\'') {
                        quoted = !quoted;
                    }
                    if (!quoted && c == 'a') {
                        a = i;
                        break;
                    }
                }

                if (a >= 0) {
                    // Move a back so any whitespace before AM/PM is also in the alternate size.
                    final int b = a;
                    while (a > 0 && UCharacter.isUWhiteSpace(format.charAt(a - 1))) {
                        a--;
                    }
                    format = format.substring(0, a) + MAGIC1 + format.substring(a, b)
                        + "a" + MAGIC2 + format.substring(b + 1);
                }
            }
            mClockFormat = new SimpleDateFormat(format);
        }
        String result = mClockFormat.format(mCalendar.getTime());

        if (mAmPmStyle != AM_PM_STYLE_NORMAL) {
            int magic1 = result.indexOf(MAGIC1);
            int magic2 = result.indexOf(MAGIC2);
            if (magic1 >= 0 && magic2 > magic1) {
                SpannableStringBuilder formatted = new SpannableStringBuilder(result);
                if (mAmPmStyle == AM_PM_STYLE_GONE) {
                    formatted.delete(magic1, magic2+1);
                } else {
                    if (mAmPmStyle == AM_PM_STYLE_SMALL) {
                        CharacterStyle style = new RelativeSizeSpan(0.7f);
                        formatted.setSpan(style, magic1, magic2,
                                          Spannable.SPAN_EXCLUSIVE_INCLUSIVE);
                    }
                    formatted.delete(magic2, magic2 + 1);
                    formatted.delete(magic1, magic1 + 1);
                }
                return formatted;
            }
        }

        return result;

    }

    private boolean mDemoMode;

    @Override
    public void dispatchDemoCommand(String command, Bundle args) {
        // Only registered for COMMAND_CLOCK
        String millis = args.getString("millis");
        String hhmm = args.getString("hhmm");
        if (millis != null) {
            mCalendar.setTimeInMillis(Long.parseLong(millis));
        } else if (hhmm != null && hhmm.length() == 4) {
            int hh = Integer.parseInt(hhmm.substring(0, 2));
            int mm = Integer.parseInt(hhmm.substring(2));
            boolean is24 = DateFormat.is24HourFormat(getContext(), mCurrentUserId);
            if (is24) {
                mCalendar.set(Calendar.HOUR_OF_DAY, hh);
            } else {
                mCalendar.set(Calendar.HOUR, hh);
            }
            mCalendar.set(Calendar.MINUTE, mm);
        }
        setText(getSmallTime());
        setContentDescription(mContentDescriptionFormat.format(mCalendar.getTime()));
    }

    @Override
    public void onDemoModeStarted() {
        mDemoMode = true;
    }

    @Override
    public void onDemoModeFinished() {
        mDemoMode = false;
        updateClock();
    }

    private final BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                if (mSecondsHandler != null) {
                    mSecondsHandler.removeCallbacks(mSecondTick);
                }
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                if (mSecondsHandler != null) {
                    mSecondsHandler.postAtTime(mSecondTick,
                            SystemClock.uptimeMillis() / 1000 * 1000 + 1000);
                }
            }
        }
    };

    private final Runnable mSecondTick = new Runnable() {
        @Override
        public void run() {
            if (mCalendar != null) {
                updateClock();
            }
            mSecondsHandler.postAtTime(this, SystemClock.uptimeMillis() / 1000 * 1000 + 1000);
        }
    };
}

