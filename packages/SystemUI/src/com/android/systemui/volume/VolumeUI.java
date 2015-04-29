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

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.media.session.MediaSessionManager;
import android.os.Handler;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import com.android.systemui.Prefs;
import com.android.systemui.R;
import com.android.systemui.SystemUI;
import com.android.systemui.qs.tiles.DndTile;
import com.android.systemui.statusbar.ServiceMonitor;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.statusbar.policy.ZenModeControllerImpl;

import java.io.FileDescriptor;
import java.io.PrintWriter;

public class VolumeUI extends SystemUI {
    private static final String TAG = "VolumeUI";
    private static boolean LOGD = Log.isLoggable(TAG, Log.DEBUG);

    private final Handler mHandler = new Handler();
    private final Receiver mReceiver = new Receiver();
    private final RestorationNotification mRestorationNotification = new RestorationNotification();

    private boolean mEnabled;
    private AudioManager mAudioManager;
    private NotificationManager mNotificationManager;
    private MediaSessionManager mMediaSessionManager;
    private ServiceMonitor mVolumeControllerService;

    private VolumeDialogComponent mVolumeComponent;

    @Override
    public void start() {
        mEnabled = mContext.getResources().getBoolean(R.bool.enable_volume_ui);
        if (!mEnabled) return;
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        mNotificationManager =
                (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        mMediaSessionManager = (MediaSessionManager) mContext
                .getSystemService(Context.MEDIA_SESSION_SERVICE);
        final ZenModeController zenController = new ZenModeControllerImpl(mContext, mHandler);
        mVolumeComponent = new VolumeDialogComponent(this, mContext, null, zenController);
        putComponent(VolumeComponent.class, getVolumeComponent());
        mReceiver.start();
        mVolumeControllerService = new ServiceMonitor(TAG, LOGD,
                mContext, Settings.Secure.VOLUME_CONTROLLER_SERVICE_COMPONENT,
                new ServiceMonitorCallbacks());
        mVolumeControllerService.start();
    }

    private VolumeComponent getVolumeComponent() {
        return mVolumeComponent;
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (!mEnabled) return;
        getVolumeComponent().onConfigurationChanged(newConfig);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.print("mEnabled="); pw.println(mEnabled);
        if (!mEnabled) return;
        pw.print("mVolumeControllerService="); pw.println(mVolumeControllerService.getComponent());
        getVolumeComponent().dump(fd, pw, args);
    }

    private void setDefaultVolumeController(boolean register) {
        if (register) {
            DndTile.setVisible(mContext, true);
            if (LOGD) Log.d(TAG, "Registering default volume controller");
            getVolumeComponent().register();
        } else {
            if (LOGD) Log.d(TAG, "Unregistering default volume controller");
            mAudioManager.setVolumeController(null);
            mMediaSessionManager.setRemoteVolumeController(null);
        }
    }

    private String getAppLabel(ComponentName component) {
        final String pkg = component.getPackageName();
        try {
            final ApplicationInfo ai = mContext.getPackageManager().getApplicationInfo(pkg, 0);
            final String rt = mContext.getPackageManager().getApplicationLabel(ai).toString();
            if (!TextUtils.isEmpty(rt)) {
                return rt;
            }
        } catch (Exception e) {
            Log.w(TAG, "Error loading app label", e);
        }
        return pkg;
    }

    private void showServiceActivationDialog(final ComponentName component) {
        final SystemUIDialog d = new SystemUIDialog(mContext);
        d.setMessage(mContext.getString(R.string.volumeui_prompt_message, getAppLabel(component)));
        d.setPositiveButton(R.string.volumeui_prompt_allow, new OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                mVolumeControllerService.setComponent(component);
            }
        });
        d.setNegativeButton(R.string.volumeui_prompt_deny, null);
        d.show();
    }

    private final class ServiceMonitorCallbacks implements ServiceMonitor.Callbacks {
        @Override
        public void onNoService() {
            if (LOGD) Log.d(TAG, "onNoService");
            setDefaultVolumeController(true);
            mRestorationNotification.hide();
            if (!mVolumeControllerService.isPackageAvailable()) {
                mVolumeControllerService.setComponent(null);
            }
        }

        @Override
        public long onServiceStartAttempt() {
            if (LOGD) Log.d(TAG, "onServiceStartAttempt");
            // poke the setting to update the uid
            mVolumeControllerService.setComponent(mVolumeControllerService.getComponent());
            setDefaultVolumeController(false);
            getVolumeComponent().dismissNow();
            mRestorationNotification.show();
            return 0;
        }
    }

    private final class Receiver extends BroadcastReceiver {
        private static final String ENABLE = "com.android.systemui.vui.ENABLE";
        private static final String DISABLE = "com.android.systemui.vui.DISABLE";
        private static final String EXTRA_COMPONENT = "component";

        private static final String PREF = "com.android.systemui.PREF";
        private static final String EXTRA_KEY = "key";
        private static final String EXTRA_VALUE = "value";

        public void start() {
            final IntentFilter filter = new IntentFilter();
            filter.addAction(ENABLE);
            filter.addAction(DISABLE);
            filter.addAction(PREF);
            mContext.registerReceiver(this, filter, null, mHandler);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (PREF.equals(action)) {
                final String key = intent.getStringExtra(EXTRA_KEY);
                if (key != null && intent.getExtras() != null) {
                    final Object value = intent.getExtras().get(EXTRA_VALUE);
                    if (value == null) {
                        Prefs.remove(mContext, key);
                    } else if (value instanceof Boolean) {
                        Prefs.putBoolean(mContext, key, (Boolean) value);
                    } else if (value instanceof Integer) {
                        Prefs.putInt(mContext, key, (Integer) value);
                    } else if (value instanceof Long) {
                        Prefs.putLong(mContext, key, (Long) value);
                    }
                }
                return;
            }
            final ComponentName component = intent.getParcelableExtra(EXTRA_COMPONENT);
            final boolean current = component != null
                    && component.equals(mVolumeControllerService.getComponent());
            if (ENABLE.equals(action) && component != null) {
                if (!current) {
                    showServiceActivationDialog(component);
                }
            }
            if (DISABLE.equals(action) && component != null) {
                if (current) {
                    mVolumeControllerService.setComponent(null);
                }
            }
        }
    }

    private final class RestorationNotification {
        public void hide() {
            mNotificationManager.cancel(R.id.notification_volumeui);
        }

        public void show() {
            final ComponentName component = mVolumeControllerService.getComponent();
            if (component == null) {
                Log.w(TAG, "Not showing restoration notification, component not active");
                return;
            }
            final Intent intent =  new Intent(Receiver.DISABLE)
                    .putExtra(Receiver.EXTRA_COMPONENT, component);
            mNotificationManager.notify(R.id.notification_volumeui,
                    new Notification.Builder(mContext)
                            .setSmallIcon(R.drawable.ic_volume_media)
                            .setWhen(0)
                            .setShowWhen(false)
                            .setOngoing(true)
                            .setContentTitle(mContext.getString(
                                    R.string.volumeui_notification_title, getAppLabel(component)))
                            .setContentText(mContext.getString(R.string.volumeui_notification_text))
                            .setContentIntent(PendingIntent.getBroadcast(mContext, 0, intent,
                                    PendingIntent.FLAG_UPDATE_CURRENT))
                            .setPriority(Notification.PRIORITY_MIN)
                            .setVisibility(Notification.VISIBILITY_PUBLIC)
                            .setColor(mContext.getColor(
                                    com.android.internal.R.color.system_notification_accent_color))
                            .build());
        }
    }
}
