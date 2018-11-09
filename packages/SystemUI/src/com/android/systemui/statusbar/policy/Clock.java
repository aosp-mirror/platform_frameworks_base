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

import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.format.DateFormat;
import android.text.style.CharacterStyle;
import android.text.style.RelativeSizeSpan;
import android.util.AttributeSet;
import android.view.Display;
import android.view.View;
import android.widget.TextView;

import com.android.settingslib.Utils;
import com.android.systemui.DemoMode;
import com.android.systemui.Dependency;
import com.android.systemui.FontSizeUtils;
import com.android.systemui.R;
import com.android.systemui.SysUiServiceProvider;
import com.android.systemui.settings.CurrentUserTracker;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;
import com.android.systemui.statusbar.policy.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;

import libcore.icu.LocaleData;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Digital clock for the status bar.
 */
public class Clock extends TextView implements DemoMode, Tunable, CommandQueue.Callbacks,
        DarkReceiver, ConfigurationListener {

    public static final String CLOCK_SECONDS = "clock_seconds";
    private static final String CLOCK_SUPER_PARCELABLE = "clock_super_parcelable";
    private static final String CURRENT_USER_ID = "current_user_id";
    private static final String VISIBLE_BY_POLICY = "visible_by_policy";
    private static final String VISIBLE_BY_USER = "visible_by_user";
    private static final String SHOW_SECONDS = "show_seconds";
    private static final String VISIBILITY = "visibility";

    private final CurrentUserTracker mCurrentUserTracker;
    private int mCurrentUserId;

    private boolean mClockVisibleByPolicy = true;
    private boolean mClockVisibleByUser = true;

    private boolean mAttached;
    private Calendar mCalendar;
    private String mClockFormatString;
    private SimpleDateFormat mClockFormat;
    private SimpleDateFormat mContentDescriptionFormat;
    private Locale mLocale;

    private static final int AM_PM_STYLE_NORMAL  = 0;
    private static final int AM_PM_STYLE_SMALL   = 1;
    private static final int AM_PM_STYLE_GONE    = 2;

    private final int mAmPmStyle;
    private final boolean mShowDark;
    private boolean mShowSeconds;
    private Handler mSecondsHandler;

    /**
     * Whether we should use colors that adapt based on wallpaper/the scrim behind quick settings
     * for text.
     */
    private boolean mUseWallpaperTextColor;

    /**
     * Color to be set on this {@link TextView}, when wallpaperTextColor is <b>not</b> utilized.
     */
    private int mNonAdaptedColor;

    public Clock(Context context) {
        this(context, null);
    }

    public Clock(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public Clock(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        TypedArray a = context.getTheme().obtainStyledAttributes(
                attrs,
                R.styleable.Clock,
                0, 0);
        try {
            mAmPmStyle = a.getInt(R.styleable.Clock_amPmStyle, AM_PM_STYLE_GONE);
            mShowDark = a.getBoolean(R.styleable.Clock_showDark, true);
            mNonAdaptedColor = getCurrentTextColor();
        } finally {
            a.recycle();
        }
        mCurrentUserTracker = new CurrentUserTracker(context) {
            @Override
            public void onUserSwitched(int newUserId) {
                mCurrentUserId = newUserId;
            }
        };
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
            setVisibility(bundle.getInt(VISIBILITY));
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
            filter.addAction(Intent.ACTION_USER_SWITCHED);

            getContext().registerReceiverAsUser(mIntentReceiver, UserHandle.ALL, filter,
                    null, Dependency.get(Dependency.TIME_TICK_HANDLER));
            Dependency.get(TunerService.class).addTunable(this, CLOCK_SECONDS,
                    StatusBarIconController.ICON_BLACKLIST);
            SysUiServiceProvider.getComponent(getContext(), CommandQueue.class).addCallbacks(this);
            if (mShowDark) {
                Dependency.get(DarkIconDispatcher.class).addDarkReceiver(this);
            }
            mCurrentUserTracker.startTracking();
            mCurrentUserId = mCurrentUserTracker.getCurrentUserId();
        }

        // NOTE: It's safe to do these after registering the receiver since the receiver always runs
        // in the main thread, therefore the receiver can't run before this method returns.

        // The time zone may have changed while the receiver wasn't registered, so update the Time
        mCalendar = Calendar.getInstance(TimeZone.getDefault());

        // Make sure we update to the current time
        updateClock();
        updateShowSeconds();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mAttached) {
            getContext().unregisterReceiver(mIntentReceiver);
            mAttached = false;
            Dependency.get(TunerService.class).removeTunable(this);
            SysUiServiceProvider.getComponent(getContext(), CommandQueue.class)
                    .removeCallbacks(this);
            if (mShowDark) {
                Dependency.get(DarkIconDispatcher.class).removeDarkReceiver(this);
            }
            mCurrentUserTracker.stopTracking();
        }
    }

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_TIMEZONE_CHANGED)) {
                String tz = intent.getStringExtra("time-zone");
                getHandler().post(() -> {
                    mCalendar = Calendar.getInstance(TimeZone.getTimeZone(tz));
                    if (mClockFormat != null) {
                        mClockFormat.setTimeZone(mCalendar.getTimeZone());
                    }
                });
            } else if (action.equals(Intent.ACTION_CONFIGURATION_CHANGED)) {
                final Locale newLocale = getResources().getConfiguration().locale;
                getHandler().post(() -> {
                    if (!newLocale.equals(mLocale)) {
                        mLocale = newLocale;
                        mClockFormatString = ""; // force refresh
                    }
                });
            }
            getHandler().post(() -> updateClock());
        }
    };

    public void setClockVisibleByUser(boolean visible) {
        mClockVisibleByUser = visible;
        updateClockVisibility();
    }

    public void setClockVisibilityByPolicy(boolean visible) {
        mClockVisibleByPolicy = visible;
        updateClockVisibility();
    }

    private void updateClockVisibility() {
        boolean visible = mClockVisibleByPolicy && mClockVisibleByUser;
        Dependency.get(IconLogger.class).onIconVisibility("clock", visible);
        int visibility = visible ? View.VISIBLE : View.GONE;
        setVisibility(visibility);
    }

    final void updateClock() {
        if (mDemoMode) return;
        mCalendar.setTimeInMillis(System.currentTimeMillis());
        setText(getSmallTime());
        setContentDescription(mContentDescriptionFormat.format(mCalendar.getTime()));
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (CLOCK_SECONDS.equals(key)) {
            mShowSeconds = newValue != null && Integer.parseInt(newValue) != 0;
            updateShowSeconds();
        } else {
            setClockVisibleByUser(!StatusBarIconController.getIconBlacklist(newValue)
                    .contains("clock"));
            updateClockVisibility();
        }
    }

    @Override
    public void disable(int state1, int state2, boolean animate) {
        boolean clockVisibleByPolicy = (state1 & StatusBarManager.DISABLE_CLOCK) == 0;
        if (clockVisibleByPolicy != mClockVisibleByPolicy) {
            setClockVisibilityByPolicy(clockVisibleByPolicy);
        }
    }

    @Override
    public void onDarkChanged(Rect area, float darkIntensity, int tint) {
        mNonAdaptedColor = DarkIconDispatcher.getTint(area, this, tint);
        if (!mUseWallpaperTextColor) {
            setTextColor(mNonAdaptedColor);
        }
    }

    @Override
    public void onDensityOrFontScaleChanged() {
        FontSizeUtils.updateFontSize(this, R.dimen.status_bar_clock_size);
        setPaddingRelative(
                mContext.getResources().getDimensionPixelSize(
                        R.dimen.status_bar_clock_starting_padding),
                0,
                mContext.getResources().getDimensionPixelSize(
                        R.dimen.status_bar_clock_end_padding),
                0);
    }

    /**
     * Sets whether the clock uses the wallpaperTextColor. If we're not using it, we'll revert back
     * to dark-mode-based/tinted colors.
     *
     * @param shouldUseWallpaperTextColor whether we should use wallpaperTextColor for text color
     */
    public void useWallpaperTextColor(boolean shouldUseWallpaperTextColor) {
        if (shouldUseWallpaperTextColor == mUseWallpaperTextColor) {
            return;
        }
        mUseWallpaperTextColor = shouldUseWallpaperTextColor;

        if (mUseWallpaperTextColor) {
            setTextColor(Utils.getColorAttr(mContext, R.attr.wallpaperTextColor));
        } else {
            setTextColor(mNonAdaptedColor);
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
                IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
                filter.addAction(Intent.ACTION_SCREEN_ON);
                mContext.registerReceiver(mScreenReceiver, filter);
            }
        } else {
            if (mSecondsHandler != null) {
                mContext.unregisterReceiver(mScreenReceiver);
                mSecondsHandler.removeCallbacks(mSecondTick);
                mSecondsHandler = null;
                updateClock();
            }
        }
    }

    private final CharSequence getSmallTime() {
        Context context = getContext();
        boolean is24 = DateFormat.is24HourFormat(context, mCurrentUserId);
        LocaleData d = LocaleData.get(context.getResources().getConfiguration().locale);

        final char MAGIC1 = '\uEF00';
        final char MAGIC2 = '\uEF01';

        SimpleDateFormat sdf;
        String format = mShowSeconds
                ? is24 ? d.timeFormat_Hms : d.timeFormat_hms
                : is24 ? d.timeFormat_Hm : d.timeFormat_hm;
        if (!format.equals(mClockFormatString)) {
            mContentDescriptionFormat = new SimpleDateFormat(format);
            /*
             * Search for an unquoted "a" in the format string, so we can
             * add dummy characters around it to let us find it again after
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
                    while (a > 0 && Character.isWhitespace(format.charAt(a-1))) {
                        a--;
                    }
                    format = format.substring(0, a) + MAGIC1 + format.substring(a, b)
                        + "a" + MAGIC2 + format.substring(b + 1);
                }
            }
            mClockFormat = sdf = new SimpleDateFormat(format);
            mClockFormatString = format;
        } else {
            sdf = mClockFormat;
        }
        String result = sdf.format(mCalendar.getTime());

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
        if (!mDemoMode && command.equals(COMMAND_ENTER)) {
            mDemoMode = true;
        } else if (mDemoMode && command.equals(COMMAND_EXIT)) {
            mDemoMode = false;
            updateClock();
        } else if (mDemoMode && command.equals(COMMAND_CLOCK)) {
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

