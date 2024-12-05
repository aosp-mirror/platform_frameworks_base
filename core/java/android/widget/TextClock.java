/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.widget;

import static android.os.Process.myUserHandle;
import static android.view.ViewDebug.ExportedProperty;
import static android.widget.RemoteViews.RemoteView;

import android.annotation.NonNull;
import android.annotation.TestApi;
import android.app.ActivityManager;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.TypedArray;
import android.database.ContentObserver;
import android.icu.text.DateTimePatternGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.view.RemotableViewMethod;
import android.view.ViewHierarchyEncoder;
import android.view.inspector.InspectableProperty;

import com.android.internal.R;
import com.android.internal.util.Preconditions;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Calendar;
import java.util.TimeZone;

/**
 * <p><code>TextClock</code> can display the current date and/or time as
 * a formatted string.</p>
 *
 * <p>This view honors the 24-hour format system setting. As such, it is
 * possible and recommended to provide two different formatting patterns:
 * one to display the date/time in 24-hour mode and one to display the
 * date/time in 12-hour mode. Most callers will want to use the defaults,
 * though, which will be appropriate for the user's locale.</p>
 *
 * <p>It is possible to determine whether the system is currently in
 * 24-hour mode by calling {@link #is24HourModeEnabled()}.</p>
 *
 * <p>The rules used by this widget to decide how to format the date and
 * time are the following:</p>
 * <ul>
 *     <li>In 24-hour mode:
 *         <ul>
 *             <li>Use the value returned by {@link #getFormat24Hour()} when non-null</li>
 *             <li>Otherwise, use the value returned by {@link #getFormat12Hour()} when non-null</li>
 *             <li>Otherwise, use a default value appropriate for the user's locale, such as {@code h:mm a}</li>
 *         </ul>
 *     </li>
 *     <li>In 12-hour mode:
 *         <ul>
 *             <li>Use the value returned by {@link #getFormat12Hour()} when non-null</li>
 *             <li>Otherwise, use the value returned by {@link #getFormat24Hour()} when non-null</li>
 *             <li>Otherwise, use a default value appropriate for the user's locale, such as {@code HH:mm}</li>
 *         </ul>
 *     </li>
 * </ul>
 *
 * <p>The {@link CharSequence} instances used as formatting patterns when calling either
 * {@link #setFormat24Hour(CharSequence)} or {@link #setFormat12Hour(CharSequence)} can
 * contain styling information. To do so, use a {@link android.text.Spanned} object.
 * Note that if you customize these strings, it is your responsibility to supply strings
 * appropriate for formatting dates and/or times in the user's locale.</p>
 *
 * @attr ref android.R.styleable#TextClock_format12Hour
 * @attr ref android.R.styleable#TextClock_format24Hour
 * @attr ref android.R.styleable#TextClock_timeZone
 */
@RemoteView
public class TextClock extends TextView {
    /**
     * The default formatting pattern in 12-hour mode. This pattern is used
     * if {@link #setFormat12Hour(CharSequence)} is called with a null pattern
     * or if no pattern was specified when creating an instance of this class.
     *
     * This default pattern shows only the time, hours and minutes, and an am/pm
     * indicator.
     *
     * @see #setFormat12Hour(CharSequence)
     * @see #getFormat12Hour()
     *
     * @deprecated Let the system use locale-appropriate defaults instead.
     */
    @Deprecated
    public static final CharSequence DEFAULT_FORMAT_12_HOUR = "h:mm a";

    /**
     * The default formatting pattern in 24-hour mode. This pattern is used
     * if {@link #setFormat24Hour(CharSequence)} is called with a null pattern
     * or if no pattern was specified when creating an instance of this class.
     *
     * This default pattern shows only the time, hours and minutes.
     *
     * @see #setFormat24Hour(CharSequence)
     * @see #getFormat24Hour()
     *
     * @deprecated Let the system use locale-appropriate defaults instead.
     */
    @Deprecated
    public static final CharSequence DEFAULT_FORMAT_24_HOUR = "H:mm";

    private CharSequence mFormat12;
    private CharSequence mFormat24;
    private CharSequence mDescFormat12;
    private CharSequence mDescFormat24;

    @ExportedProperty
    private CharSequence mFormat;
    @ExportedProperty
    private boolean mHasSeconds;

    private CharSequence mDescFormat;

    private boolean mRegistered;
    private boolean mShouldRunTicker;

    private ClockEventDelegate mClockEventDelegate;

    private Calendar mTime;
    private String mTimeZone;

    private boolean mShowCurrentUserTime;

    private ContentObserver mFormatChangeObserver;
    // Used by tests to stop time change events from triggering the text update
    private boolean mStopTicking;

    private class FormatChangeObserver extends ContentObserver {

        public FormatChangeObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            chooseFormat();
            onTimeChanged();
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            chooseFormat();
            onTimeChanged();
        }
    };

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mStopTicking) {
                return; // Test disabled the clock ticks
            }
            if (mTimeZone == null && Intent.ACTION_TIMEZONE_CHANGED.equals(intent.getAction())) {
                final String timeZone = intent.getStringExtra(Intent.EXTRA_TIMEZONE);
                createTime(timeZone);
            } else if (!mShouldRunTicker && Intent.ACTION_TIME_CHANGED.equals(intent.getAction())) {
                return;
            }
            onTimeChanged();
        }
    };

    private final Runnable mTicker = new Runnable() {
        public void run() {
            removeCallbacks(this);
            if (mStopTicking || !mShouldRunTicker) {
                return; // Test disabled the clock ticks
            }
            onTimeChanged();

            Instant now = mTime.toInstant();
            ZoneId zone = mTime.getTimeZone().toZoneId();

            ZonedDateTime nextTick;
            if (mHasSeconds) {
                nextTick = now.atZone(zone).plusSeconds(1).withNano(0);
            } else {
                nextTick = now.atZone(zone).plusMinutes(1).withSecond(0).withNano(0);
            }

            long millisUntilNextTick = Duration.between(now, nextTick.toInstant()).toMillis();
            if (millisUntilNextTick <= 0) {
                // This should never happen, but if it does, then tick again in a second.
                millisUntilNextTick = 1000;
            }

            postDelayed(this, millisUntilNextTick);
        }
    };

    /**
     * Creates a new clock using the default patterns for the current locale.
     *
     * @param context The Context the view is running in, through which it can
     *        access the current theme, resources, etc.
     */
    @SuppressWarnings("UnusedDeclaration")
    public TextClock(Context context) {
        super(context);
        init();
    }

    /**
     * Creates a new clock inflated from XML. This object's properties are
     * intialized from the attributes specified in XML.
     *
     * This constructor uses a default style of 0, so the only attribute values
     * applied are those in the Context's Theme and the given AttributeSet.
     *
     * @param context The Context the view is running in, through which it can
     *        access the current theme, resources, etc.
     * @param attrs The attributes of the XML tag that is inflating the view
     */
    @SuppressWarnings("UnusedDeclaration")
    public TextClock(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    /**
     * Creates a new clock inflated from XML. This object's properties are
     * intialized from the attributes specified in XML.
     *
     * @param context The Context the view is running in, through which it can
     *        access the current theme, resources, etc.
     * @param attrs The attributes of the XML tag that is inflating the view
     * @param defStyleAttr An attribute in the current theme that contains a
     *        reference to a style resource that supplies default values for
     *        the view. Can be 0 to not look for defaults.
     */
    public TextClock(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public TextClock(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        final TypedArray a = context.obtainStyledAttributes(
                attrs, R.styleable.TextClock, defStyleAttr, defStyleRes);
        saveAttributeDataForStyleable(context, R.styleable.TextClock,
                attrs, a, defStyleAttr, defStyleRes);
        try {
            mFormat12 = a.getText(R.styleable.TextClock_format12Hour);
            mFormat24 = a.getText(R.styleable.TextClock_format24Hour);
            mTimeZone = a.getString(R.styleable.TextClock_timeZone);
        } finally {
            a.recycle();
        }

        init();
    }

    private void init() {
        if (mFormat12 == null) {
            mFormat12 = getBestDateTimePattern("hm");
        }
        if (mFormat24 == null) {
            mFormat24 = getBestDateTimePattern("Hm");
        }
        mClockEventDelegate = new ClockEventDelegate(getContext());

        createTime(mTimeZone);
        chooseFormat();
    }

    private void createTime(String timeZone) {
        TimeZone tz = null;
        if (timeZone == null) {
            tz = TimeZone.getDefault();
            // Note that mTimeZone should always be null if timeZone is.
        } else {
            tz = TimeZone.getTimeZone(timeZone);
            try {
                // Try converting this TZ to a zoneId to make sure it's valid. This
                // performs a different set of checks than TimeZone.getTimeZone so
                // we can avoid exceptions later when we do need this conversion.
                tz.toZoneId();
            } catch (DateTimeException ex) {
                // If we're here, the user supplied timezone is invalid, so reset
                // mTimeZone to something sane.
                tz = TimeZone.getDefault();
                mTimeZone = tz.getID();
            }
        }

        mTime = Calendar.getInstance(tz);
    }

    /**
     * Returns the formatting pattern used to display the date and/or time
     * in 12-hour mode. The formatting pattern syntax is described in
     * {@link DateFormat}.
     *
     * @return A {@link CharSequence} or null.
     *
     * @see #setFormat12Hour(CharSequence)
     * @see #is24HourModeEnabled()
     */
    @InspectableProperty
    @ExportedProperty
    public CharSequence getFormat12Hour() {
        return mFormat12;
    }

    /**
     * <p>Specifies the formatting pattern used to display the date and/or time
     * in 12-hour mode. The formatting pattern syntax is described in
     * {@link DateFormat}.</p>
     *
     * <p>If this pattern is set to null, {@link #getFormat24Hour()} will be used
     * even in 12-hour mode. If both 24-hour and 12-hour formatting patterns
     * are set to null, the default pattern for the current locale will be used
     * instead.</p>
     *
     * <p><strong>Note:</strong> if styling is not needed, it is highly recommended
     * you supply a format string generated by
     * {@link DateFormat#getBestDateTimePattern(java.util.Locale, String)}. This method
     * takes care of generating a format string adapted to the desired locale.</p>
     *
     *
     * @param format A date/time formatting pattern as described in {@link DateFormat}
     *
     * @see #getFormat12Hour()
     * @see #is24HourModeEnabled()
     * @see DateFormat#getBestDateTimePattern(java.util.Locale, String)
     * @see DateFormat
     *
     * @attr ref android.R.styleable#TextClock_format12Hour
     */
    @RemotableViewMethod
    public void setFormat12Hour(CharSequence format) {
        mFormat12 = format;

        chooseFormat();
        onTimeChanged();
    }

    /**
     * Like setFormat12Hour, but for the content description.
     * @hide
     */
    public void setContentDescriptionFormat12Hour(CharSequence format) {
        mDescFormat12 = format;

        chooseFormat();
        onTimeChanged();
    }

    /**
     * Returns the formatting pattern used to display the date and/or time
     * in 24-hour mode. The formatting pattern syntax is described in
     * {@link DateFormat}.
     *
     * @return A {@link CharSequence} or null.
     *
     * @see #setFormat24Hour(CharSequence)
     * @see #is24HourModeEnabled()
     */
    @InspectableProperty
    @ExportedProperty
    public CharSequence getFormat24Hour() {
        return mFormat24;
    }

    /**
     * <p>Specifies the formatting pattern used to display the date and/or time
     * in 24-hour mode. The formatting pattern syntax is described in
     * {@link DateFormat}.</p>
     *
     * <p>If this pattern is set to null, {@link #getFormat24Hour()} will be used
     * even in 12-hour mode. If both 24-hour and 12-hour formatting patterns
     * are set to null, the default pattern for the current locale will be used
     * instead.</p>
     *
     * <p><strong>Note:</strong> if styling is not needed, it is highly recommended
     * you supply a format string generated by
     * {@link DateFormat#getBestDateTimePattern(java.util.Locale, String)}. This method
     * takes care of generating a format string adapted to the desired locale.</p>
     *
     * @param format A date/time formatting pattern as described in {@link DateFormat}
     *
     * @see #getFormat24Hour()
     * @see #is24HourModeEnabled()
     * @see DateFormat#getBestDateTimePattern(java.util.Locale, String)
     * @see DateFormat
     *
     * @attr ref android.R.styleable#TextClock_format24Hour
     */
    @RemotableViewMethod
    public void setFormat24Hour(CharSequence format) {
        mFormat24 = format;

        chooseFormat();
        onTimeChanged();
    }

    /**
     * Like setFormat24Hour, but for the content description.
     * @hide
     */
    public void setContentDescriptionFormat24Hour(CharSequence format) {
        mDescFormat24 = format;

        chooseFormat();
        onTimeChanged();
    }

    /**
     * Sets whether this clock should always track the current user and not the user of the
     * current process. This is used for single instance processes like the systemUI who need
     * to display time for different users.
     *
     * @hide
     */
    public void setShowCurrentUserTime(boolean showCurrentUserTime) {
        mShowCurrentUserTime = showCurrentUserTime;

        chooseFormat();
        onTimeChanged();
        unregisterObserver();
        registerObserver();
    }

    /**
     * Sets a delegate to handle clock event registration. This must be called before the view is
     * attached to the window
     *
     * @hide
     */
    public void setClockEventDelegate(ClockEventDelegate delegate) {
        Preconditions.checkState(!mRegistered, "Clock events already registered");
        mClockEventDelegate = delegate;
    }

    /**
     * Update the displayed time if necessary and invalidate the view.
     */
    public void refreshTime() {
        onTimeChanged();
        invalidate();
    }

    /**
     * Indicates whether the system is currently using the 24-hour mode.
     *
     * When the system is in 24-hour mode, this view will use the pattern
     * returned by {@link #getFormat24Hour()}. In 12-hour mode, the pattern
     * returned by {@link #getFormat12Hour()} is used instead.
     *
     * If either one of the formats is null, the other format is used. If
     * both formats are null, the default formats for the current locale are used.
     *
     * @return true if time should be displayed in 24-hour format, false if it
     *         should be displayed in 12-hour format.
     *
     * @see #setFormat12Hour(CharSequence)
     * @see #getFormat12Hour()
     * @see #setFormat24Hour(CharSequence)
     * @see #getFormat24Hour()
     */
    @InspectableProperty(hasAttributeId = false)
    public boolean is24HourModeEnabled() {
        if (mShowCurrentUserTime) {
            return DateFormat.is24HourFormat(getContext(), ActivityManager.getCurrentUser());
        } else {
            return DateFormat.is24HourFormat(getContext());
        }
    }

    /**
     * Indicates which time zone is currently used by this view.
     *
     * @return The ID of the current time zone or null if the default time zone,
     *         as set by the user, must be used
     *
     * @see TimeZone
     * @see java.util.TimeZone#getAvailableIDs()
     * @see #setTimeZone(String)
     */
    @InspectableProperty
    public String getTimeZone() {
        return mTimeZone;
    }

    /**
     * Sets the specified time zone to use in this clock. When the time zone
     * is set through this method, system time zone changes (when the user
     * sets the time zone in settings for instance) will be ignored.
     *
     * @param timeZone The desired time zone's ID as specified in {@link TimeZone}
     *                 or null to user the time zone specified by the user
     *                 (system time zone)
     *
     * @see #getTimeZone()
     * @see java.util.TimeZone#getAvailableIDs()
     * @see TimeZone#getTimeZone(String)
     *
     * @attr ref android.R.styleable#TextClock_timeZone
     */
    @RemotableViewMethod
    public void setTimeZone(String timeZone) {
        mTimeZone = timeZone;

        createTime(timeZone);
        onTimeChanged();
    }

    /**
     * Returns the current format string. Always valid after constructor has
     * finished, and will never be {@code null}.
     *
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public CharSequence getFormat() {
        return mFormat;
    }

    /**
     * Selects either one of {@link #getFormat12Hour()} or {@link #getFormat24Hour()}
     * depending on whether the user has selected 24-hour format.
     */
    private void chooseFormat() {
        final boolean format24Requested = is24HourModeEnabled();

        if (format24Requested) {
            mFormat = abc(mFormat24, mFormat12, getBestDateTimePattern("Hm"));
            mDescFormat = abc(mDescFormat24, mDescFormat12, mFormat);
        } else {
            mFormat = abc(mFormat12, mFormat24, getBestDateTimePattern("hm"));
            mDescFormat = abc(mDescFormat12, mDescFormat24, mFormat);
        }

        boolean hadSeconds = mHasSeconds;
        mHasSeconds = DateFormat.hasSeconds(mFormat);

        if (mShouldRunTicker && hadSeconds != mHasSeconds) {
            mTicker.run();
        }
    }

    private String getBestDateTimePattern(String skeleton) {
        DateTimePatternGenerator dtpg = DateTimePatternGenerator.getInstance(
                getContext().getResources().getConfiguration().locale);
        return dtpg.getBestPattern(skeleton);
    }

    /**
     * Returns a if not null, else return b if not null, else return c.
     */
    private static CharSequence abc(CharSequence a, CharSequence b, CharSequence c) {
        return a == null ? (b == null ? c : b) : a;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (!mRegistered) {
            mRegistered = true;

            mClockEventDelegate.registerTimeChangeReceiver(mIntentReceiver, getHandler());
            registerObserver();

            createTime(mTimeZone);
        }
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);

        if (!mShouldRunTicker && isVisible) {
            mShouldRunTicker = true;
            mTicker.run();
        } else if (mShouldRunTicker && !isVisible) {
            mShouldRunTicker = false;
            removeCallbacks(mTicker);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        if (mRegistered) {
            mClockEventDelegate.unregisterTimeChangeReceiver(mIntentReceiver);
            unregisterObserver();

            mRegistered = false;
        }
    }

    /**
     * Used by tests to stop the clock tick from updating the text.
     * @hide
     */
    @TestApi
    public void disableClockTick() {
        mStopTicking = true;
    }

    private void registerObserver() {
        if (mRegistered) {
            if (mFormatChangeObserver == null) {
                mFormatChangeObserver = new FormatChangeObserver(getHandler());
            }
            // UserHandle.myUserId() is needed. This class is supported by the
            // remote views mechanism and as a part of that the remote views
            // can be inflated by a context for another user without the app
            // having interact users permission - just for loading resources.
            // For example, when adding widgets from a managed profile to the
            // home screen. Therefore, we register the ContentObserver with the user
            // the app is running (e.g. the launcher) and not the user of the
            // context (e.g. the widget's profile).
            int userHandle = mShowCurrentUserTime ? UserHandle.USER_ALL : UserHandle.myUserId();
            mClockEventDelegate.registerFormatChangeObserver(mFormatChangeObserver, userHandle);
        }
    }

    private void unregisterObserver() {
        if (mFormatChangeObserver != null) {
            mClockEventDelegate.unregisterFormatChangeObserver(mFormatChangeObserver);
        }
    }

    /**
     * Update the displayed time if this view and its ancestors and window is visible
     */
    @UnsupportedAppUsage
    private void onTimeChanged() {
        mTime.setTimeInMillis(System.currentTimeMillis());
        setText(DateFormat.format(mFormat, mTime));
        setContentDescription(DateFormat.format(mDescFormat, mTime));
    }

    /** @hide */
    @Override
    protected void encodeProperties(@NonNull ViewHierarchyEncoder stream) {
        super.encodeProperties(stream);

        CharSequence s = getFormat12Hour();
        stream.addProperty("format12Hour", s == null ? null : s.toString());

        s = getFormat24Hour();
        stream.addProperty("format24Hour", s == null ? null : s.toString());
        stream.addProperty("format", mFormat == null ? null : mFormat.toString());
        stream.addProperty("hasSeconds", mHasSeconds);
    }

    /**
     * Utility class to delegate some system event handling to allow overring the default behavior
     *
     * @hide
     */
    public static class ClockEventDelegate {

        private final Context mContext;

        public ClockEventDelegate(Context context) {
            mContext = context;
        }

        /**
         * Registers a receiver for actions {@link Intent#ACTION_TIME_CHANGED} and
         * {@link Intent#ACTION_TIMEZONE_CHANGED}
         *
         * OK, this is gross but needed. This class is supported by the remote views mechanism and
         * as a part of that the remote views can be inflated by a context for another user without
         * the app having interact users permission - just for loading resources. For example,
         * when adding widgets from a managed profile to the home screen. Therefore, we register
         * the receiver as the user the app is running as not the one the context is for.
         */
        public void registerTimeChangeReceiver(BroadcastReceiver receiver, Handler handler) {
            final IntentFilter filter = new IntentFilter();

            filter.addAction(Intent.ACTION_TIME_CHANGED);
            filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);

            mContext.registerReceiverAsUser(receiver, myUserHandle(), filter, null, handler);
        }

        /**
         * Unregisters a previously registered receiver
         */
        public void unregisterTimeChangeReceiver(BroadcastReceiver receiver) {
            mContext.unregisterReceiver(receiver);
        }

        /**
         * Registers an observer for time format changes
         */
        public void registerFormatChangeObserver(ContentObserver observer, int userHandle) {
            Uri uri = Settings.System.getUriFor(Settings.System.TIME_12_24);
            mContext.getContentResolver().registerContentObserver(uri, true, observer, userHandle);
        }

        /**
         * Unregisters a previously registered observer
         */
        public void unregisterFormatChangeObserver(ContentObserver observer) {
            mContext.getContentResolver().unregisterContentObserver(observer);
        }
    }
}
