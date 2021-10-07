/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.accessibility;

import static android.app.AlarmManager.RTC_WAKEUP;

import static com.android.internal.messages.nano.SystemMessageProto.SystemMessage.NOTE_A11Y_VIEW_AND_CONTROL_ACCESS;
import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.MainThread;
import android.annotation.NonNull;
import android.app.ActivityOptions;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.view.accessibility.AccessibilityManager;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.util.ImageUtils;

import java.util.Calendar;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * The class handles permission warning notifications for not accessibility-categorized
 * accessibility services from {@link AccessibilitySecurityPolicy}. And also maintains the setting
 * {@link Settings.Secure#NOTIFIED_NON_ACCESSIBILITY_CATEGORY_SERVICES} in order not to
 * resend notifications to the same service.
 */
public class PolicyWarningUIController {
    private static final String TAG = PolicyWarningUIController.class.getSimpleName();
    @VisibleForTesting
    protected static final String ACTION_SEND_NOTIFICATION = TAG + ".ACTION_SEND_NOTIFICATION";
    @VisibleForTesting
    protected static final String ACTION_A11Y_SETTINGS = TAG + ".ACTION_A11Y_SETTINGS";
    @VisibleForTesting
    protected static final String ACTION_DISMISS_NOTIFICATION =
            TAG + ".ACTION_DISMISS_NOTIFICATION";
    private static final int SEND_NOTIFICATION_DELAY_HOURS = 24;

    /** Current enabled accessibility services. */
    private final ArraySet<ComponentName> mEnabledA11yServices = new ArraySet<>();

    private final Handler mMainHandler;
    private final AlarmManager mAlarmManager;
    private final Context mContext;
    private final NotificationController mNotificationController;

    public PolicyWarningUIController(@NonNull Handler handler, @NonNull Context context,
            NotificationController notificationController) {
        mMainHandler = handler;
        mContext = context;
        mNotificationController = notificationController;
        mAlarmManager = mContext.getSystemService(AlarmManager.class);
        final IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_SEND_NOTIFICATION);
        filter.addAction(ACTION_A11Y_SETTINGS);
        filter.addAction(ACTION_DISMISS_NOTIFICATION);
        mContext.registerReceiver(mNotificationController, filter,
                Manifest.permission.MANAGE_ACCESSIBILITY, mMainHandler);

    }

    protected void setAccessibilityPolicyManager(
            AccessibilitySecurityPolicy accessibilitySecurityPolicy) {
        mNotificationController.setAccessibilityPolicyManager(accessibilitySecurityPolicy);
    }

    /**
     * Updates enabled accessibility services and notified accessibility services after switching
     * to another user.
     *
     * @param enabledServices The current enabled services
     */
    public void onSwitchUserLocked(int userId, Set<ComponentName> enabledServices) {
        mEnabledA11yServices.clear();
        mEnabledA11yServices.addAll(enabledServices);
        mMainHandler.sendMessage(obtainMessage(mNotificationController::onSwitchUser, userId));
    }

    /**
     * Computes the newly disabled services and removes its record from the setting
     * {@link Settings.Secure#NOTIFIED_NON_ACCESSIBILITY_CATEGORY_SERVICES} after detecting the
     * setting {@link Settings.Secure#ENABLED_ACCESSIBILITY_SERVICES} changed.
     *
     * @param userId          The user id
     * @param enabledServices The enabled services
     */
    public void onEnabledServicesChangedLocked(int userId,
            Set<ComponentName> enabledServices) {
        final ArraySet<ComponentName> disabledServices = new ArraySet<>(mEnabledA11yServices);
        disabledServices.removeAll(enabledServices);
        mEnabledA11yServices.clear();
        mEnabledA11yServices.addAll(enabledServices);
        mMainHandler.sendMessage(
                obtainMessage(mNotificationController::onServicesDisabled, userId,
                        disabledServices));
    }

    /**
     * Called when the target service is bound. Sets an 24 hours alarm to the service which is not
     * notified yet to execute action {@code ACTION_SEND_NOTIFICATION}.
     *
     * @param userId  The user id
     * @param service The service's component name
     */
    public void onNonA11yCategoryServiceBound(int userId, ComponentName service) {
        mMainHandler.sendMessage(obtainMessage(this::setAlarm, userId, service));
    }

    /**
     * Called when the target service is unbound. Cancels the old alarm with intent action
     * {@code ACTION_SEND_NOTIFICATION} from the service.
     *
     * @param userId  The user id
     * @param service The service's component name
     */
    public void onNonA11yCategoryServiceUnbound(int userId, ComponentName service) {
        mMainHandler.sendMessage(obtainMessage(this::cancelAlarm, userId, service));
    }

    private void setAlarm(int userId, ComponentName service) {
        final Calendar cal = Calendar.getInstance();
        cal.add(Calendar.HOUR, SEND_NOTIFICATION_DELAY_HOURS);
        mAlarmManager.set(RTC_WAKEUP, cal.getTimeInMillis(),
                createPendingIntent(mContext, userId, ACTION_SEND_NOTIFICATION, service));
    }

    private void cancelAlarm(int userId, ComponentName service) {
        mAlarmManager.cancel(
                createPendingIntent(mContext, userId, ACTION_SEND_NOTIFICATION, service));
    }

    protected static PendingIntent createPendingIntent(Context context, int userId, String action,
            ComponentName serviceComponentName) {
        return PendingIntent.getBroadcast(context, 0,
                createIntent(context, userId, action, serviceComponentName),
                PendingIntent.FLAG_IMMUTABLE);
    }

    protected static Intent createIntent(Context context, int userId, String action,
            ComponentName serviceComponentName) {
        final Intent intent = new Intent(action);
        intent.setPackage(context.getPackageName())
                .setIdentifier(serviceComponentName.flattenToShortString())
                .putExtra(Intent.EXTRA_COMPONENT_NAME, serviceComponentName)
                .putExtra(Intent.EXTRA_USER_ID, userId);
        return intent;
    }

    /** A sub class to handle notifications and settings on the main thread. */
    @MainThread
    public static class NotificationController extends BroadcastReceiver {
        private static final char RECORD_SEPARATOR = ':';

        /** All accessibility services which are notified to the user by the policy warning rule. */
        private final ArraySet<ComponentName> mNotifiedA11yServices = new ArraySet<>();
        private final NotificationManager mNotificationManager;
        private final Context mContext;

        private int mCurrentUserId;
        private AccessibilitySecurityPolicy mAccessibilitySecurityPolicy;

        public NotificationController(Context context) {
            mContext = context;
            mNotificationManager = mContext.getSystemService(NotificationManager.class);
        }

        protected void setAccessibilityPolicyManager(
                AccessibilitySecurityPolicy accessibilitySecurityPolicy) {
            mAccessibilitySecurityPolicy = accessibilitySecurityPolicy;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            final ComponentName componentName = intent.getParcelableExtra(
                    Intent.EXTRA_COMPONENT_NAME);
            if (TextUtils.isEmpty(action) || componentName == null) {
                return;
            }
            final int userId = intent.getIntExtra(Intent.EXTRA_USER_ID, UserHandle.USER_SYSTEM);
            if (ACTION_SEND_NOTIFICATION.equals(action)) {
                trySendNotification(userId, componentName);
            } else if (ACTION_A11Y_SETTINGS.equals(action)) {
                launchSettings(userId, componentName);
                mNotificationManager.cancel(componentName.flattenToShortString(),
                        NOTE_A11Y_VIEW_AND_CONTROL_ACCESS);
                onNotificationCanceled(userId, componentName);
            } else if (ACTION_DISMISS_NOTIFICATION.equals(action)) {
                onNotificationCanceled(userId, componentName);
            }
        }

        protected void onSwitchUser(int userId) {
            mCurrentUserId = userId;
            mNotifiedA11yServices.clear();
            mNotifiedA11yServices.addAll(readNotifiedServiceList(userId));
        }

        protected void onServicesDisabled(int userId,
                ArraySet<ComponentName> disabledServices) {
            if (mNotifiedA11yServices.removeAll(disabledServices)) {
                writeNotifiedServiceList(userId, mNotifiedA11yServices);
            }
        }

        private void trySendNotification(int userId, ComponentName componentName) {
            if (!AccessibilitySecurityPolicy.POLICY_WARNING_ENABLED) {
                return;
            }
            if (userId != mCurrentUserId) {
                return;
            }

            List<AccessibilityServiceInfo> enabledServiceInfos = getEnabledServiceInfos();
            for (int i = 0; i < enabledServiceInfos.size(); i++) {
                final AccessibilityServiceInfo a11yServiceInfo = enabledServiceInfos.get(i);
                if (componentName.flattenToShortString().equals(
                        a11yServiceInfo.getComponentName().flattenToShortString())) {
                    if (!mAccessibilitySecurityPolicy.isA11yCategoryService(a11yServiceInfo)
                            && !mNotifiedA11yServices.contains(componentName)) {
                        final CharSequence displayName =
                                a11yServiceInfo.getResolveInfo().serviceInfo.loadLabel(
                                        mContext.getPackageManager());
                        final Drawable drawable = a11yServiceInfo.getResolveInfo().loadIcon(
                                mContext.getPackageManager());
                        final int size = mContext.getResources().getDimensionPixelSize(
                                android.R.dimen.app_icon_size);
                        sendNotification(userId, componentName, displayName,
                                ImageUtils.buildScaledBitmap(drawable, size, size));
                    }
                    break;
                }
            }
        }

        private void launchSettings(int userId, ComponentName componentName) {
            if (userId != mCurrentUserId) {
                return;
            }
            final Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            intent.putExtra(Intent.EXTRA_COMPONENT_NAME, componentName.flattenToShortString());
            final Bundle bundle = ActivityOptions.makeBasic().setLaunchDisplayId(
                    mContext.getDisplayId()).toBundle();
            mContext.startActivityAsUser(intent, bundle, UserHandle.of(userId));
            mContext.getSystemService(StatusBarManager.class).collapsePanels();
        }

        protected void onNotificationCanceled(int userId, ComponentName componentName) {
            if (userId != mCurrentUserId) {
                return;
            }

            if (mNotifiedA11yServices.add(componentName)) {
                writeNotifiedServiceList(userId, mNotifiedA11yServices);
            }
        }

        private void sendNotification(int userId, ComponentName serviceComponentName,
                CharSequence name,
                Bitmap bitmap) {
            final Notification.Builder notificationBuilder = new Notification.Builder(mContext,
                    SystemNotificationChannels.ACCESSIBILITY_SECURITY_POLICY);
            notificationBuilder.setSmallIcon(R.drawable.ic_accessibility_24dp)
                    .setContentTitle(
                            mContext.getString(R.string.view_and_control_notification_title))
                    .setContentText(
                            mContext.getString(R.string.view_and_control_notification_content,
                                    name))
                    .setStyle(new Notification.BigTextStyle()
                            .bigText(
                                    mContext.getString(
                                            R.string.view_and_control_notification_content,
                                            name)))
                    .setTicker(mContext.getString(R.string.view_and_control_notification_title))
                    .setOnlyAlertOnce(true)
                    .setDeleteIntent(
                            createPendingIntent(mContext, userId, ACTION_DISMISS_NOTIFICATION,
                                    serviceComponentName))
                    .setContentIntent(
                            createPendingIntent(mContext, userId, ACTION_A11Y_SETTINGS,
                                    serviceComponentName));
            if (bitmap != null) {
                notificationBuilder.setLargeIcon(bitmap);
            }
            mNotificationManager.notify(serviceComponentName.flattenToShortString(),
                    NOTE_A11Y_VIEW_AND_CONTROL_ACCESS,
                    notificationBuilder.build());
        }

        private ArraySet<ComponentName> readNotifiedServiceList(int userId) {
            final String notifiedServiceSetting = Settings.Secure.getStringForUser(
                    mContext.getContentResolver(),
                    Settings.Secure.NOTIFIED_NON_ACCESSIBILITY_CATEGORY_SERVICES,
                    userId);
            if (TextUtils.isEmpty(notifiedServiceSetting)) {
                return new ArraySet<>();
            }

            final TextUtils.StringSplitter componentNameSplitter =
                    new TextUtils.SimpleStringSplitter(RECORD_SEPARATOR);
            componentNameSplitter.setString(notifiedServiceSetting);

            final ArraySet<ComponentName> notifiedServices = new ArraySet<>();
            final Iterator<String> it = componentNameSplitter.iterator();
            while (it.hasNext()) {
                final String componentNameString = it.next();
                final ComponentName notifiedService = ComponentName.unflattenFromString(
                        componentNameString);
                if (notifiedService != null) {
                    notifiedServices.add(notifiedService);
                }
            }
            return notifiedServices;
        }

        private void writeNotifiedServiceList(int userId, ArraySet<ComponentName> services) {
            StringBuilder notifiedServicesBuilder = new StringBuilder();
            for (int i = 0; i < services.size(); i++) {
                if (i > 0) {
                    notifiedServicesBuilder.append(RECORD_SEPARATOR);
                }
                final ComponentName notifiedService = services.valueAt(i);
                notifiedServicesBuilder.append(notifiedService.flattenToShortString());
            }
            Settings.Secure.putStringForUser(mContext.getContentResolver(),
                    Settings.Secure.NOTIFIED_NON_ACCESSIBILITY_CATEGORY_SERVICES,
                    notifiedServicesBuilder.toString(), userId);
        }

        @VisibleForTesting
        protected List<AccessibilityServiceInfo> getEnabledServiceInfos() {
            final AccessibilityManager accessibilityManager = AccessibilityManager.getInstance(
                    mContext);
            return accessibilityManager.getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        }
    }
}
