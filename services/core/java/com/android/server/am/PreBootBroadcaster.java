/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.am;

import static android.content.pm.PackageManager.MATCH_SYSTEM_ONLY;
import static android.os.PowerWhitelistManager.REASON_PRE_BOOT_COMPLETED;
import static android.os.PowerWhitelistManager.TEMPORARY_ALLOWLIST_TYPE_FOREGROUND_SERVICE_ALLOWED;

import android.app.ActivityManagerInternal;
import android.app.AppOpsManager;
import android.app.BroadcastOptions;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Process;
import android.os.UserHandle;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.util.ProgressReporter;
import com.android.server.LocalServices;
import com.android.server.UiThread;

import java.util.List;

/**
 * Simple broadcaster that sends {@link Intent#ACTION_PRE_BOOT_COMPLETED} to all
 * system apps that register for it. Override {@link #onFinished()} to handle
 * when all broadcasts are finished.
 */
public abstract class PreBootBroadcaster extends IIntentReceiver.Stub {
    private static final String TAG = "PreBootBroadcaster";

    private final ActivityManagerService mService;
    private final int mUserId;
    private final ProgressReporter mProgress;
    private final boolean mQuiet;

    private final Intent mIntent;
    private final List<ResolveInfo> mTargets;

    private int mIndex = 0;

    public PreBootBroadcaster(ActivityManagerService service, int userId,
            ProgressReporter progress, boolean quiet) {
        mService = service;
        mUserId = userId;
        mProgress = progress;
        mQuiet = quiet;

        mIntent = new Intent(Intent.ACTION_PRE_BOOT_COMPLETED);
        mIntent.addFlags(Intent.FLAG_RECEIVER_BOOT_UPGRADE | Intent.FLAG_DEBUG_TRIAGED_MISSING);

        mTargets = mService.mContext.getPackageManager().queryBroadcastReceiversAsUser(mIntent,
                MATCH_SYSTEM_ONLY, UserHandle.of(userId));
    }

    public void sendNext() {
        if (mIndex >= mTargets.size()) {
            mHandler.obtainMessage(MSG_HIDE).sendToTarget();
            onFinished();
            return;
        }

        if (!mService.isUserRunning(mUserId, 0)) {
            Slog.i(TAG, "User " + mUserId + " is no longer running; skipping remaining receivers");
            mHandler.obtainMessage(MSG_HIDE).sendToTarget();
            onFinished();
            return;
        }

        if (!mQuiet) {
            mHandler.obtainMessage(MSG_SHOW, mTargets.size(), mIndex).sendToTarget();
        }

        final ResolveInfo ri = mTargets.get(mIndex++);
        final ComponentName componentName = ri.activityInfo.getComponentName();

        if (mProgress != null) {
            final CharSequence label = ri.activityInfo
                    .loadLabel(mService.mContext.getPackageManager());
            mProgress.setProgress(mIndex, mTargets.size(),
                    mService.mContext.getString(R.string.android_preparing_apk, label));
        }

        Slog.i(TAG, "Pre-boot of " + componentName.toShortString() + " for user " + mUserId);
        EventLogTags.writeAmPreBoot(mUserId, componentName.getPackageName());

        mIntent.setComponent(componentName);
        long duration = 10_000;
        final ActivityManagerInternal amInternal =
                LocalServices.getService(ActivityManagerInternal.class);
        if (amInternal != null) {
            duration = amInternal.getBootTimeTempAllowListDuration();
        }
        final BroadcastOptions bOptions = BroadcastOptions.makeBasic();
        bOptions.setTemporaryAppAllowlist(duration,
                TEMPORARY_ALLOWLIST_TYPE_FOREGROUND_SERVICE_ALLOWED,
                REASON_PRE_BOOT_COMPLETED, "");
        synchronized (mService) {
            mService.broadcastIntentLocked(null, null, null, mIntent, null, this, 0, null, null,
                    null, null, null, AppOpsManager.OP_NONE, bOptions.toBundle(), true,
                    false, ActivityManagerService.MY_PID,
                    Process.SYSTEM_UID, Binder.getCallingUid(), Binder.getCallingPid(), mUserId);
        }
    }

    @Override
    public void performReceive(Intent intent, int resultCode, String data, Bundle extras,
            boolean ordered, boolean sticky, int sendingUser) {
        sendNext();
    }

    private static final int MSG_SHOW = 1;
    private static final int MSG_HIDE = 2;

    private Handler mHandler = new Handler(UiThread.get().getLooper(), null, true) {
        @Override
        public void handleMessage(Message msg) {
            final Context context = mService.mContext;
            final NotificationManager notifManager = context
                    .getSystemService(NotificationManager.class);
            final int max = msg.arg1;
            final int index = msg.arg2;

            switch (msg.what) {
                case MSG_SHOW:
                    final CharSequence title = context
                            .getText(R.string.android_upgrading_notification_title);

                    final Intent intent = new Intent();
                    intent.setClassName("com.android.settings",
                            "com.android.settings.HelpTrampoline");
                    intent.putExtra(Intent.EXTRA_TEXT, "help_url_upgrading");

                    final PendingIntent contentIntent;
                    if (context.getPackageManager().resolveActivity(intent, 0) != null) {
                        contentIntent = PendingIntent.getActivity(context, 0, intent,
                                PendingIntent.FLAG_IMMUTABLE);
                    } else {
                        contentIntent = null;
                    }

                    final Notification notif =
                            new Notification.Builder(mService.mContext,
                                    SystemNotificationChannels.UPDATES)
                            .setSmallIcon(R.drawable.stat_sys_adb)
                            .setWhen(0)
                            .setOngoing(true)
                            .setTicker(title)
                            .setColor(context.getColor(
                                    com.android.internal.R.color.system_notification_accent_color))
                            .setContentTitle(title)
                            .setContentIntent(contentIntent)
                            .setVisibility(Notification.VISIBILITY_PUBLIC)
                            .setProgress(max, index, false)
                            .build();
                    notifManager.notifyAsUser(TAG, SystemMessage.NOTE_SYSTEM_UPGRADING, notif,
                            UserHandle.of(mUserId));
                    break;

                case MSG_HIDE:
                    notifManager.cancelAsUser(TAG, SystemMessage.NOTE_SYSTEM_UPGRADING,
                            UserHandle.of(mUserId));
                    break;
            }
        }
    };

    public abstract void onFinished();
}
