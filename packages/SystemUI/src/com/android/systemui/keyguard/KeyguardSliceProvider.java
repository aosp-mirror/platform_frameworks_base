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
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.icu.text.DateFormat;
import android.icu.text.DisplayContext;
import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.Trace;
import android.provider.Settings;
import android.service.notification.ZenModeConfig;
import android.text.TextUtils;
import android.text.style.StyleSpan;

import androidx.core.graphics.drawable.IconCompat;
import androidx.slice.Slice;
import androidx.slice.SliceProvider;
import androidx.slice.builders.ListBuilder;
import androidx.slice.builders.ListBuilder.RowBuilder;
import androidx.slice.builders.SliceAction;

import com.android.internal.annotations.VisibleForTesting;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.res.R;
import com.android.systemui.SystemUIAppComponentFactoryBase;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.policy.NextAlarmController;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.util.wakelock.SettableWakeLock;
import com.android.systemui.util.wakelock.WakeLock;
import com.android.systemui.util.wakelock.WakeLockLogger;

import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

/**
 * Simple Slice provider that shows the current date.
 *
 * Injection is handled by {@link SystemUIAppComponentFactoryBase} +
 * {@link com.android.systemui.dagger.GlobalRootComponent#inject(KeyguardSliceProvider)}.
 */
public class KeyguardSliceProvider extends SliceProvider implements
        NextAlarmController.NextAlarmChangeCallback, ZenModeController.Callback,
        NotificationMediaManager.MediaListener, StatusBarStateController.StateListener,
        SystemUIAppComponentFactoryBase.ContextInitializer {

    private static final String TAG = "KgdSliceProvider";

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

    private static final Object sInstanceLock = new Object();
    private static KeyguardSliceProvider sInstance;

    protected final Uri mSliceUri;
    protected final Uri mHeaderUri;
    protected final Uri mDateUri;
    protected final Uri mAlarmUri;
    protected final Uri mDndUri;
    protected final Uri mMediaUri;
    private final Date mCurrentTime = new Date();
    private final Handler mHandler;
    private final Handler mMediaHandler;
    private final AlarmManager.OnAlarmListener mUpdateNextAlarm = this::updateNextAlarm;
    @Inject
    public DozeParameters mDozeParameters;
    @VisibleForTesting
    protected SettableWakeLock mMediaWakeLock;
    @Inject
    public ZenModeController mZenModeController;
    private String mDatePattern;
    private DateFormat mDateFormat;
    private String mLastText;
    private boolean mRegistered;
    private String mNextAlarm;
    @Inject
    public NextAlarmController mNextAlarmController;
    @Inject
    public AlarmManager mAlarmManager;
    @Inject
    public ContentResolver mContentResolver;
    private AlarmManager.AlarmClockInfo mNextAlarmInfo;
    private PendingIntent mPendingIntent;
    @Inject
    public NotificationMediaManager mMediaManager;
    @Inject
    public StatusBarStateController mStatusBarStateController;
    @Inject
    public KeyguardBypassController mKeyguardBypassController;
    @Inject
    public KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Inject
    UserTracker mUserTracker;
    private CharSequence mMediaTitle;
    private CharSequence mMediaArtist;
    protected boolean mDozing;
    private int mStatusBarState;
    private boolean mMediaIsVisible;
    private SystemUIAppComponentFactoryBase.ContextAvailableCallback mContextAvailableCallback;
    @Inject
    WakeLockLogger mWakeLockLogger;
    @Inject
    @Background
    Handler mBgHandler;

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

    public static KeyguardSliceProvider getAttachedInstance() {
        return KeyguardSliceProvider.sInstance;
    }

    public KeyguardSliceProvider() {
        mHandler = new Handler();
        mMediaHandler = new Handler();
        mSliceUri = Uri.parse(KEYGUARD_SLICE_URI);
        mHeaderUri = Uri.parse(KEYGUARD_HEADER_URI);
        mDateUri = Uri.parse(KEYGUARD_DATE_URI);
        mAlarmUri = Uri.parse(KEYGUARD_NEXT_ALARM_URI);
        mDndUri = Uri.parse(KEYGUARD_DND_URI);
        mMediaUri = Uri.parse(KEYGUARD_MEDIA_URI);
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
            }
            addNextAlarmLocked(builder);
            addZenModeLocked(builder);
            addPrimaryActionLocked(builder);
            slice = builder.build();
        }
        Trace.endSection();
        return slice;
    }

    protected boolean needsMediaLocked() {
        boolean keepWhenAwake = mKeyguardBypassController != null
                && mKeyguardBypassController.getBypassEnabled() && mDozeParameters.getAlwaysOn();
        // Show header if music is playing and the status bar is in the shade state. This way, an
        // animation isn't necessary when pressing power and transitioning to AOD.
        boolean keepWhenShade = mStatusBarState == StatusBarState.SHADE && mMediaIsVisible;
        return !TextUtils.isEmpty(mMediaTitle) && mMediaIsVisible && (mDozing || keepWhenAwake
                || keepWhenShade);
    }

    protected void addMediaLocked(ListBuilder listBuilder) {
        if (TextUtils.isEmpty(mMediaTitle)) {
            return;
        }
        listBuilder.setHeader(new ListBuilder.HeaderBuilder(mHeaderUri).setTitle(mMediaTitle));

        if (!TextUtils.isEmpty(mMediaArtist)) {
            RowBuilder albumBuilder = new RowBuilder(mMediaUri);
            albumBuilder.setTitle(mMediaArtist);

            Icon mediaIcon = mMediaManager == null ? null : mMediaManager.getMediaIcon();
            IconCompat mediaIconCompat = mediaIcon == null ? null
                    : IconCompat.createFromIcon(getContext(), mediaIcon);
            if (mediaIconCompat != null) {
                albumBuilder.addEndItem(mediaIconCompat, ListBuilder.ICON_IMAGE);
            }

            listBuilder.addRow(albumBuilder);
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
                .addEndItem(
                    IconCompat.createWithResource(getContext(), R.drawable.stat_sys_dnd),
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
        mContextAvailableCallback.onContextAvailable(getContext());
        mMediaWakeLock = new SettableWakeLock(
                WakeLock.createPartial(getContext(), mWakeLockLogger, "media"), "media");
        synchronized (KeyguardSliceProvider.sInstanceLock) {
            KeyguardSliceProvider oldInstance = KeyguardSliceProvider.sInstance;
            if (oldInstance != null) {
                oldInstance.onDestroy();
            }
            mDatePattern = getContext().getString(R.string.system_ui_aod_date_pattern);
            mPendingIntent = PendingIntent.getActivity(getContext(), 0,
                    new Intent(getContext(), KeyguardSliceProvider.class),
                    PendingIntent.FLAG_IMMUTABLE);
            mMediaManager.addCallback(this);
            mStatusBarStateController.addCallback(this);
            mNextAlarmController.addCallback(this);
            mZenModeController.addCallback(this);
            KeyguardSliceProvider.sInstance = this;
            registerClockUpdate();
            updateClockLocked();
        }
        return true;
    }

    @VisibleForTesting
    protected void onDestroy() {
        synchronized (KeyguardSliceProvider.sInstanceLock) {
            mNextAlarmController.removeCallback(this);
            mZenModeController.removeCallback(this);
            mMediaWakeLock.setAcquired(false);
            mAlarmManager.cancel(mUpdateNextAlarm);
            if (mRegistered) {
                mRegistered = false;
                mKeyguardUpdateMonitor.removeCallback(mKeyguardUpdateMonitorCallback);
                getContext().unregisterReceiver(mIntentReceiver);
            }
            KeyguardSliceProvider.sInstance = null;
        }
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
                        mUserTracker.getUserId()) ? "HH:mm" : "h:mm";
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
    @VisibleForTesting
    protected void registerClockUpdate() {
        synchronized (this) {
            if (mRegistered) {
                return;
            }

            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_DATE_CHANGED);
            filter.addAction(Intent.ACTION_LOCALE_CHANGED);
            getContext().registerReceiver(mIntentReceiver, filter, null /* permission*/,
                    null /* scheduler */);
            mKeyguardUpdateMonitor.registerCallback(mKeyguardUpdateMonitorCallback);
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
            // The use of CAPITALIZATION_FOR_BEGINNING_OF_SENTENCE instead of
            // CAPITALIZATION_FOR_STANDALONE is to address
            // https://unicode-org.atlassian.net/browse/ICU-21631
            // TODO(b/229287642): Switch back to CAPITALIZATION_FOR_STANDALONE
            format.setContext(DisplayContext.CAPITALIZATION_FOR_BEGINNING_OF_SENTENCE);
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

    /**
     * Called whenever new media metadata is available.
     * @param metadata New metadata.
     */
    @Override
    public void onPrimaryMetadataOrStateChanged(MediaMetadata metadata,
            @PlaybackState.State int state) {
        synchronized (this) {
            boolean nextVisible = NotificationMediaManager.isPlayingState(state);
            mMediaHandler.removeCallbacksAndMessages(null);
            if (mMediaIsVisible && !nextVisible && mStatusBarState != StatusBarState.SHADE) {
                // We need to delay this event for a few millis when stopping to avoid jank in the
                // animation. The media app might not send its update when buffering, and the slice
                // would end up without a header for 0.5 second.
                mMediaWakeLock.setAcquired(true);
                mMediaHandler.postDelayed(() -> {
                    synchronized (this) {
                        updateMediaStateLocked(metadata, state);
                        mMediaWakeLock.setAcquired(false);
                    }
                }, 2000);
            } else {
                mMediaWakeLock.setAcquired(false);
                updateMediaStateLocked(metadata, state);
            }
        }
    }

    private void updateMediaStateLocked(MediaMetadata metadata, @PlaybackState.State int state) {
        boolean nextVisible = NotificationMediaManager.isPlayingState(state);
        CharSequence title = null;
        if (metadata != null) {
            title = metadata.getText(MediaMetadata.METADATA_KEY_TITLE);
            if (TextUtils.isEmpty(title)) {
                title = getContext().getResources().getString(R.string.music_controls_no_title);
            }
        }
        CharSequence artist = metadata == null ? null : metadata.getText(
                MediaMetadata.METADATA_KEY_ARTIST);

        if (nextVisible == mMediaIsVisible && TextUtils.equals(title, mMediaTitle)
                && TextUtils.equals(artist, mMediaArtist)) {
            return;
        }
        mMediaTitle = title;
        mMediaArtist = artist;
        mMediaIsVisible = nextVisible;
        notifyChange();
    }

    protected void notifyChange() {
        mBgHandler.post(() -> mContentResolver.notifyChange(mSliceUri, null /* observer */));
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
        final boolean notify;
        synchronized (this) {
            boolean needsMedia = needsMediaLocked();
            mStatusBarState = newState;
            notify = needsMedia != needsMediaLocked();
        }
        if (notify) {
            notifyChange();
        }
    }

    @Override
    public void setContextAvailableCallback(
            SystemUIAppComponentFactoryBase.ContextAvailableCallback callback) {
        mContextAvailableCallback = callback;
    }
}
