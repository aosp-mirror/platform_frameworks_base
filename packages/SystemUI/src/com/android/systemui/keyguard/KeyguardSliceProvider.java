/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.keyguard;

import android.annotation.AnyThread;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.icu.text.DateFormat;
import android.icu.text.DisplayContext;
import android.media.MediaMetadata;
import android.net.Uri;
import android.os.Handler;
import android.os.Trace;
import android.provider.Settings;
import android.service.notification.ZenModeConfig;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.DynamicDrawableSpan;
import android.text.style.ImageSpan;
import android.text.style.StyleSpan;
import android.util.MathUtils;

import androidx.core.graphics.drawable.IconCompat;
import androidx.slice.Slice;
import androidx.slice.SliceProvider;
import androidx.slice.builders.ListBuilder;
import androidx.slice.builders.ListBuilder.RowBuilder;
import androidx.slice.builders.SliceAction;

import com.android.internal.annotations.VisibleForTesting;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.R;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.policy.NextAlarmController;
import com.android.systemui.statusbar.policy.NextAlarmControllerImpl;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.statusbar.policy.ZenModeControllerImpl;

import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Simple Slice provider that shows the current date.
 */
public class KeyguardSliceProvider extends SliceProvider implements
        NextAlarmController.NextAlarmChangeCallback, ZenModeController.Callback,
        NotificationMediaManager.MediaListener, StatusBarStateController.StateListener {

    private static final StyleSpan BOLD_STYLE = new StyleSpan(Typeface.BOLD);
    public static final String KEYGUARD_SLICE_URI = "content://com.android.systemui.keyguard/main";
    private static final String KEYGUARD_HEADER_URI =
            "content://com.android.systemui.keyguard/header";
    public static final String KEYGUARD_DATE_URI = "content://com.android.systemui.keyguard/date";
    public static final String KEYGUARD_NEXT_ALARM_URI =
            "content://com.android.systemui.keyguard/alarm";
    public static final String KEYGUARD_DND_URI = "content://com.android.systemui.keyguard/dnd";
    public static final String KEYGUARD_MEDIA_URI =
            "content://com.android.systemui.keyguard/media";
    public static final String KEYGUARD_ACTION_URI =
            "content://com.android.systemui.keyguard/action";

    /**
     * Only show alarms that will ring within N hours.
     */
    @VisibleForTesting
    static final int ALARM_VISIBILITY_HOURS = 12;

    private static KeyguardSliceProvider sInstance;

    protected final Uri mSliceUri;
    protected final Uri mHeaderUri;
    protected final Uri mDateUri;
    protected final Uri mAlarmUri;
    protected final Uri mDndUri;
    protected final Uri mMediaUri;
    private final Date mCurrentTime = new Date();
    private final Handler mHandler;
    private final AlarmManager.OnAlarmListener mUpdateNextAlarm = this::updateNextAlarm;
    private ZenModeController mZenModeController;
    private String mDatePattern;
    private DateFormat mDateFormat;
    private String mLastText;
    private boolean mRegistered;
    private String mNextAlarm;
    private NextAlarmController mNextAlarmController;
    protected AlarmManager mAlarmManager;
    protected ContentResolver mContentResolver;
    private AlarmManager.AlarmClockInfo mNextAlarmInfo;
    private PendingIntent mPendingIntent;
    protected NotificationMediaManager mMediaManager;
    private StatusBarStateController mStatusBarStateController;
    protected MediaMetadata mMediaMetaData;
    protected boolean mDozing;

    /**
     * Receiver responsible for time ticking and updating the date format.
     */
    @VisibleForTesting
    final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (Intent.ACTION_DATE_CHANGED.equals(action)) {
                synchronized (this) {
                    updateClockLocked();
                }
            } else if (Intent.ACTION_LOCALE_CHANGED.equals(action)) {
                synchronized (this) {
                    cleanDateFormatLocked();
                }
            }
        }
    };

    @VisibleForTesting
    final KeyguardUpdateMonitorCallback mKeyguardUpdateMonitorCallback =
            new KeyguardUpdateMonitorCallback() {
                @Override
                public void onTimeChanged() {
                    synchronized (this) {
                        updateClockLocked();
                    }
                }

                @Override
                public void onTimeZoneChanged(TimeZone timeZone) {
                    synchronized (this) {
                        cleanDateFormatLocked();
                    }
                }
            };

    public KeyguardSliceProvider() {
        this(new Handler());
    }

    public static KeyguardSliceProvider getAttachedInstance() {
        return KeyguardSliceProvider.sInstance;
    }

    @VisibleForTesting
    KeyguardSliceProvider(Handler handler) {
        mHandler = handler;
        mSliceUri = Uri.parse(KEYGUARD_SLICE_URI);
        mHeaderUri = Uri.parse(KEYGUARD_HEADER_URI);
        mDateUri = Uri.parse(KEYGUARD_DATE_URI);
        mAlarmUri = Uri.parse(KEYGUARD_NEXT_ALARM_URI);
        mDndUri = Uri.parse(KEYGUARD_DND_URI);
        mMediaUri = Uri.parse(KEYGUARD_MEDIA_URI);
    }

    /**
     * Initialize dependencies that don't exist during {@link android.content.ContentProvider}
     * instantiation.
     *
     * @param mediaManager {@link NotificationMediaManager} singleton.
     * @param statusBarStateController {@link StatusBarStateController} singleton.
     */
    public void initDependencies(
            NotificationMediaManager mediaManager,
            StatusBarStateController statusBarStateController) {
        mMediaManager = mediaManager;
        mMediaManager.addCallback(this);
        mStatusBarStateController = statusBarStateController;
        mStatusBarStateController.addCallback(this);
    }

    @AnyThread
    @Override
    public Slice onBindSlice(Uri sliceUri) {
        Trace.beginSection("KeyguardSliceProvider#onBindSlice");
        Slice slice;
        synchronized (this) {
            ListBuilder builder = new ListBuilder(getContext(), mSliceUri, ListBuilder.INFINITY);
            if (needsMediaLocked()) {
                addMediaLocked(builder);
            } else {
                builder.addRow(new RowBuilder(mDateUri).setTitle(mLastText));
                addNextAlarmLocked(builder);
                addZenModeLocked(builder);
            }
            addPrimaryActionLocked(builder);
            slice = builder.build();
        }
        Trace.endSection();
        return slice;
    }

    protected boolean needsMediaLocked() {
        return mMediaMetaData != null && mDozing;
    }

    protected void addMediaLocked(ListBuilder listBuilder) {
        if (mMediaMetaData != null) {
            SpannableStringBuilder builder = new SpannableStringBuilder();

            Icon notificationIcon = mMediaManager == null ? null : mMediaManager.getMediaIcon();
            if (notificationIcon != null) {
                Drawable drawable = notificationIcon.loadDrawable(getContext());
                Rect mediaBounds = new Rect(0 /* left */, 0 /* top */,
                        drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
                int iconHeaderSize = getContext().getResources()
                        .getDimensionPixelSize(R.dimen.header_icon_size);
                MathUtils.fitRect(mediaBounds, iconHeaderSize);
                drawable.setBounds(mediaBounds);
                builder.append("# ");
                builder.setSpan(new ImageSpan(drawable, DynamicDrawableSpan.ALIGN_CENTER),
                        0 /* start */, 1 /* end */, Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
            }

            CharSequence title = mMediaMetaData.getText(MediaMetadata.METADATA_KEY_TITLE);
            if (TextUtils.isEmpty(title)) {
                title = getContext().getResources().getString(R.string.music_controls_no_title);
            }
            builder.append(title);
            listBuilder.setHeader(new ListBuilder.HeaderBuilder(mHeaderUri).setTitle(builder));

            CharSequence album = mMediaMetaData.getText(MediaMetadata.METADATA_KEY_ARTIST);
            if (!TextUtils.isEmpty(album)) {
                listBuilder.addRow(new RowBuilder(mMediaUri).setTitle(album));
            }
        }
    }

    protected void addPrimaryActionLocked(ListBuilder builder) {
        // Add simple action because API requires it; Keyguard handles presenting
        // its own slices so this action + icon are actually never used.
        IconCompat icon = IconCompat.createWithResource(getContext(),
                R.drawable.ic_access_alarms_big);
        SliceAction action = SliceAction.createDeeplink(mPendingIntent, icon,
                ListBuilder.ICON_IMAGE, mLastText);
        RowBuilder primaryActionRow = new RowBuilder(Uri.parse(KEYGUARD_ACTION_URI))
                .setPrimaryAction(action);
        builder.addRow(primaryActionRow);
    }

    protected void addNextAlarmLocked(ListBuilder builder) {
        if (TextUtils.isEmpty(mNextAlarm)) {
            return;
        }
        IconCompat alarmIcon = IconCompat.createWithResource(getContext(),
                R.drawable.ic_access_alarms_big);
        RowBuilder alarmRowBuilder = new RowBuilder(mAlarmUri)
                .setTitle(mNextAlarm)
                .addEndItem(alarmIcon, ListBuilder.ICON_IMAGE);
        builder.addRow(alarmRowBuilder);
    }

    /**
     * Add zen mode (DND) icon to slice if it's enabled.
     * @param builder The slice builder.
     */
    protected void addZenModeLocked(ListBuilder builder) {
        if (!isDndOn()) {
            return;
        }
        RowBuilder dndBuilder = new RowBuilder(mDndUri)
                .setContentDescription(getContext().getResources()
                        .getString(R.string.accessibility_quick_settings_dnd))
                .addEndItem(IconCompat.createWithResource(getContext(), R.drawable.stat_sys_dnd),
                        ListBuilder.ICON_IMAGE);
        builder.addRow(dndBuilder);
    }

    /**
     * Return true if DND is enabled.
     */
    protected boolean isDndOn() {
        return mZenModeController.getZen() != Settings.Global.ZEN_MODE_OFF;
    }

    @Override
    public boolean onCreateSliceProvider() {
        mAlarmManager = getContext().getSystemService(AlarmManager.class);
        mContentResolver = getContext().getContentResolver();
        mNextAlarmController = new NextAlarmControllerImpl(getContext());
        mNextAlarmController.addCallback(this);
        mZenModeController = new ZenModeControllerImpl(getContext(), mHandler);
        mZenModeController.addCallback(this);
        mDatePattern = getContext().getString(R.string.system_ui_aod_date_pattern);
        mPendingIntent = PendingIntent.getActivity(getContext(), 0, new Intent(), 0);
        KeyguardSliceProvider.sInstance = this;
        registerClockUpdate();
        updateClockLocked();
        return true;
    }

    @Override
    public void onZenChanged(int zen) {
        notifyChange();
    }

    @Override
    public void onConfigChanged(ZenModeConfig config) {
        notifyChange();
    }

    private void updateNextAlarm() {
        synchronized (this) {
            if (withinNHoursLocked(mNextAlarmInfo, ALARM_VISIBILITY_HOURS)) {
                String pattern = android.text.format.DateFormat.is24HourFormat(getContext(),
                        ActivityManager.getCurrentUser()) ? "HH:mm" : "h:mm";
                mNextAlarm = android.text.format.DateFormat.format(pattern,
                        mNextAlarmInfo.getTriggerTime()).toString();
            } else {
                mNextAlarm = "";
            }
        }
        notifyChange();
    }

    private boolean withinNHoursLocked(AlarmManager.AlarmClockInfo alarmClockInfo, int hours) {
        if (alarmClockInfo == null) {
            return false;
        }

        long limit = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(hours);
        return mNextAlarmInfo.getTriggerTime() <= limit;
    }

    /**
     * Registers a broadcast receiver for clock updates, include date, time zone and manually
     * changing the date/time via the settings app.
     */
    private void registerClockUpdate() {
        synchronized (this) {
            if (mRegistered) {
                return;
            }

            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_DATE_CHANGED);
            filter.addAction(Intent.ACTION_LOCALE_CHANGED);
            getContext().registerReceiver(mIntentReceiver, filter, null /* permission*/,
                    null /* scheduler */);
            getKeyguardUpdateMonitor().registerCallback(mKeyguardUpdateMonitorCallback);
            mRegistered = true;
        }
    }

    @VisibleForTesting
    boolean isRegistered() {
        synchronized (this) {
            return mRegistered;
        }
    }

    protected void updateClockLocked() {
        final String text = getFormattedDateLocked();
        if (!text.equals(mLastText)) {
            mLastText = text;
            notifyChange();
        }
    }

    protected String getFormattedDateLocked() {
        if (mDateFormat == null) {
            final Locale l = Locale.getDefault();
            DateFormat format = DateFormat.getInstanceForSkeleton(mDatePattern, l);
            format.setContext(DisplayContext.CAPITALIZATION_FOR_STANDALONE);
            mDateFormat = format;
        }
        mCurrentTime.setTime(System.currentTimeMillis());
        return mDateFormat.format(mCurrentTime);
    }

    @VisibleForTesting
    void cleanDateFormatLocked() {
        mDateFormat = null;
    }

    @Override
    public void onNextAlarmChanged(AlarmManager.AlarmClockInfo nextAlarm) {
        synchronized (this) {
            mNextAlarmInfo = nextAlarm;
            mAlarmManager.cancel(mUpdateNextAlarm);

            long triggerAt = mNextAlarmInfo == null ? -1 : mNextAlarmInfo.getTriggerTime()
                    - TimeUnit.HOURS.toMillis(ALARM_VISIBILITY_HOURS);
            if (triggerAt > 0) {
                mAlarmManager.setExact(AlarmManager.RTC, triggerAt, "lock_screen_next_alarm",
                        mUpdateNextAlarm, mHandler);
            }
        }
        updateNextAlarm();
    }

    @VisibleForTesting
    protected KeyguardUpdateMonitor getKeyguardUpdateMonitor() {
        return KeyguardUpdateMonitor.getInstance(getContext());
    }

    /**
     * Called whenever new media metadata is available.
     * @param metadata New metadata.
     */
    @Override
    public void onMetadataChanged(MediaMetadata metadata) {
        synchronized (this) {
            mMediaMetaData = metadata;
        }
        notifyChange();
    }

    protected void notifyChange() {
        mContentResolver.notifyChange(mSliceUri, null /* observer */);
    }

    @Override
    public void onDozingChanged(boolean isDozing) {
        final boolean notify;
        synchronized (this) {
            boolean neededMedia = needsMediaLocked();
            mDozing = isDozing;
            notify = neededMedia != needsMediaLocked();
        }
        if (notify) {
            notifyChange();
        }
    }

    @Override
    public void onStateChanged(int newState) {
    }
}
