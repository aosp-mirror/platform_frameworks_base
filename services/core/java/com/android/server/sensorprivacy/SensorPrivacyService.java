/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.sensorprivacy;

import static android.Manifest.permission.MANAGE_SENSOR_PRIVACY;
import static android.app.ActivityManager.PROCESS_CAPABILITY_FOREGROUND_CAMERA;
import static android.app.ActivityManager.PROCESS_CAPABILITY_FOREGROUND_MICROPHONE;
import static android.app.ActivityManager.RunningServiceInfo;
import static android.app.ActivityManager.RunningTaskInfo;
import static android.app.AppOpsManager.MODE_IGNORED;
import static android.app.AppOpsManager.OP_CAMERA;
import static android.app.AppOpsManager.OP_PHONE_CALL_CAMERA;
import static android.app.AppOpsManager.OP_PHONE_CALL_MICROPHONE;
import static android.app.AppOpsManager.OP_RECEIVE_AMBIENT_TRIGGER_AUDIO;
import static android.app.AppOpsManager.OP_RECORD_AUDIO;
import static android.content.Intent.EXTRA_PACKAGE_NAME;
import static android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
import static android.content.Intent.FLAG_ACTIVITY_NO_USER_ACTION;
import static android.content.pm.PackageManager.MATCH_SYSTEM_ONLY;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.hardware.SensorPrivacyManager.EXTRA_ALL_SENSORS;
import static android.hardware.SensorPrivacyManager.EXTRA_SENSOR;
import static android.hardware.SensorPrivacyManager.Sensors.CAMERA;
import static android.hardware.SensorPrivacyManager.Sensors.MICROPHONE;
import static android.hardware.SensorPrivacyManager.Sources.DIALOG;
import static android.hardware.SensorPrivacyManager.Sources.OTHER;
import static android.hardware.SensorPrivacyManager.Sources.QS_TILE;
import static android.hardware.SensorPrivacyManager.Sources.SETTINGS;
import static android.hardware.SensorPrivacyManager.Sources.SHELL;
import static android.hardware.SensorPrivacyManager.TOGGLE_TYPE_HARDWARE;
import static android.hardware.SensorPrivacyManager.TOGGLE_TYPE_SOFTWARE;
import static android.os.UserHandle.USER_NULL;
import static android.service.SensorPrivacyIndividualEnabledSensorProto.UNKNOWN;

import static com.android.internal.util.FrameworkStatsLog.PRIVACY_SENSOR_TOGGLE_INTERACTION;
import static com.android.internal.util.FrameworkStatsLog.PRIVACY_SENSOR_TOGGLE_INTERACTION__ACTION__ACTION_UNKNOWN;
import static com.android.internal.util.FrameworkStatsLog.PRIVACY_SENSOR_TOGGLE_INTERACTION__ACTION__TOGGLE_OFF;
import static com.android.internal.util.FrameworkStatsLog.PRIVACY_SENSOR_TOGGLE_INTERACTION__ACTION__TOGGLE_ON;
import static com.android.internal.util.FrameworkStatsLog.PRIVACY_SENSOR_TOGGLE_INTERACTION__SENSOR__CAMERA;
import static com.android.internal.util.FrameworkStatsLog.PRIVACY_SENSOR_TOGGLE_INTERACTION__SENSOR__MICROPHONE;
import static com.android.internal.util.FrameworkStatsLog.PRIVACY_SENSOR_TOGGLE_INTERACTION__SENSOR__SENSOR_UNKNOWN;
import static com.android.internal.util.FrameworkStatsLog.PRIVACY_SENSOR_TOGGLE_INTERACTION__SOURCE__DIALOG;
import static com.android.internal.util.FrameworkStatsLog.PRIVACY_SENSOR_TOGGLE_INTERACTION__SOURCE__QS_TILE;
import static com.android.internal.util.FrameworkStatsLog.PRIVACY_SENSOR_TOGGLE_INTERACTION__SOURCE__SETTINGS;
import static com.android.internal.util.FrameworkStatsLog.PRIVACY_SENSOR_TOGGLE_INTERACTION__SOURCE__SOURCE_UNKNOWN;
import static com.android.internal.util.FrameworkStatsLog.write;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.app.AppOpsManager;
import android.app.AppOpsManagerInternal;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.res.Configuration;
import android.graphics.drawable.Icon;
import android.hardware.ISensorPrivacyListener;
import android.hardware.ISensorPrivacyManager;
import android.hardware.SensorPrivacyManager;
import android.hardware.SensorPrivacyManagerInternal;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.service.voice.VoiceInteractionManagerInternal;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.telephony.emergency.EmergencyNumber;
import android.text.Html;
import android.text.Spanned;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Pair;
import android.util.proto.ProtoOutputStream;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FunctionalUtils;
import com.android.internal.util.dump.DualDumpOutputStream;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.pm.UserManagerInternal;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/** @hide */
public final class SensorPrivacyService extends SystemService {

    private static final String TAG = SensorPrivacyService.class.getSimpleName();
    private static final boolean DEBUG = false;
    private static final boolean DEBUG_LOGGING = false;

    private static final String SENSOR_PRIVACY_CHANNEL_ID = Context.SENSOR_PRIVACY_SERVICE;
    private static final String ACTION_DISABLE_TOGGLE_SENSOR_PRIVACY =
            SensorPrivacyService.class.getName() + ".action.disable_sensor_privacy";

    public static final int REMINDER_DIALOG_DELAY_MILLIS = 500;

    private final Context mContext;
    private final SensorPrivacyServiceImpl mSensorPrivacyServiceImpl;
    private final UserManagerInternal mUserManagerInternal;
    private final ActivityManager mActivityManager;
    private final ActivityManagerInternal mActivityManagerInternal;
    private final ActivityTaskManager mActivityTaskManager;
    private final AppOpsManager mAppOpsManager;
    private final AppOpsManagerInternal mAppOpsManagerInternal;
    private final TelephonyManager mTelephonyManager;
    private final PackageManagerInternal mPackageManagerInternal;

    private CameraPrivacyLightController mCameraPrivacyLightController;

    private final IBinder mAppOpsRestrictionToken = new Binder();

    private SensorPrivacyManagerInternalImpl mSensorPrivacyManagerInternal;

    private CallStateHelper mCallStateHelper;
    private KeyguardManager mKeyguardManager;

    private int mCurrentUser = USER_NULL;

    public SensorPrivacyService(Context context) {
        super(context);

        mContext = context;
        mAppOpsManager = context.getSystemService(AppOpsManager.class);
        mAppOpsManagerInternal = getLocalService(AppOpsManagerInternal.class);
        mUserManagerInternal = getLocalService(UserManagerInternal.class);
        mActivityManager = context.getSystemService(ActivityManager.class);
        mActivityManagerInternal = getLocalService(ActivityManagerInternal.class);
        mActivityTaskManager = context.getSystemService(ActivityTaskManager.class);
        mTelephonyManager = context.getSystemService(TelephonyManager.class);
        mPackageManagerInternal = getLocalService(PackageManagerInternal.class);
        mSensorPrivacyServiceImpl = new SensorPrivacyServiceImpl();
    }

    @Override
    public void onStart() {
        publishBinderService(Context.SENSOR_PRIVACY_SERVICE, mSensorPrivacyServiceImpl);
        mSensorPrivacyManagerInternal = new SensorPrivacyManagerInternalImpl();
        publishLocalService(SensorPrivacyManagerInternal.class,
                mSensorPrivacyManagerInternal);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_SYSTEM_SERVICES_READY) {
            mKeyguardManager = mContext.getSystemService(KeyguardManager.class);
            mCallStateHelper = new CallStateHelper();
        } else if (phase == PHASE_ACTIVITY_MANAGER_READY) {
            mCameraPrivacyLightController = new CameraPrivacyLightController(mContext);
        }
    }

    @Override
    public void onUserStarting(TargetUser user) {
        if (mCurrentUser == USER_NULL) {
            mCurrentUser = user.getUserIdentifier();
            mSensorPrivacyServiceImpl.userSwitching(USER_NULL, user.getUserIdentifier());
        }
    }

    @Override
    public void onUserSwitching(TargetUser from, TargetUser to) {
        mCurrentUser = to.getUserIdentifier();
        mSensorPrivacyServiceImpl.userSwitching(from.getUserIdentifier(), to.getUserIdentifier());
    }

    class SensorPrivacyServiceImpl extends ISensorPrivacyManager.Stub implements
            AppOpsManager.OnOpNotedListener, AppOpsManager.OnOpStartedListener,
            IBinder.DeathRecipient, UserManagerInternal.UserRestrictionsListener {

        private final SensorPrivacyHandler mHandler;
        private final Object mLock = new Object();

        private SensorPrivacyStateController mSensorPrivacyStateController;

        /**
         * Packages for which not to show sensor use reminders.
         *
         * <Package, User> -> list of suppressor tokens
         */
        @GuardedBy("mLock")
        private ArrayMap<Pair<Integer, UserHandle>, ArrayList<IBinder>> mSuppressReminders =
                new ArrayMap<>();

        private final ArrayMap<SensorUseReminderDialogInfo, ArraySet<Integer>>
                mQueuedSensorUseReminderDialogs = new ArrayMap<>();

        private class SensorUseReminderDialogInfo {
            private int mTaskId;
            private UserHandle mUser;
            private String mPackageName;

            SensorUseReminderDialogInfo(int taskId, UserHandle user, String packageName) {
                mTaskId = taskId;
                mUser = user;
                mPackageName = packageName;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || !(o instanceof SensorUseReminderDialogInfo)) return false;
                SensorUseReminderDialogInfo that = (SensorUseReminderDialogInfo) o;
                return mTaskId == that.mTaskId
                        && Objects.equals(mUser, that.mUser)
                        && Objects.equals(mPackageName, that.mPackageName);
            }

            @Override
            public int hashCode() {
                return Objects.hash(mTaskId, mUser, mPackageName);
            }
        }

        SensorPrivacyServiceImpl() {
            mHandler = new SensorPrivacyHandler(FgThread.get().getLooper(), mContext);
            mSensorPrivacyStateController = SensorPrivacyStateController.getInstance();

            int[] micAndCameraOps = new int[]{OP_RECORD_AUDIO, OP_PHONE_CALL_MICROPHONE,
                    OP_CAMERA, OP_PHONE_CALL_CAMERA};
            mAppOpsManager.startWatchingNoted(micAndCameraOps, this);
            mAppOpsManager.startWatchingStarted(micAndCameraOps, this);



            mContext.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    setToggleSensorPrivacy(
                            ((UserHandle) intent.getParcelableExtra(
                                    Intent.EXTRA_USER)).getIdentifier(), OTHER,
                            intent.getIntExtra(EXTRA_SENSOR, UNKNOWN), false);
                }
            }, new IntentFilter(ACTION_DISABLE_TOGGLE_SENSOR_PRIVACY),
                    MANAGE_SENSOR_PRIVACY, null, Context.RECEIVER_EXPORTED);

            mContext.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    mSensorPrivacyStateController.forEachState(
                            (toggleType, userId, sensor, state) ->
                                    logSensorPrivacyToggle(OTHER, sensor, state.isEnabled(),
                                    state.getLastChange(), true)
                    );
                }
            }, new IntentFilter(Intent.ACTION_SHUTDOWN));

            mUserManagerInternal.addUserRestrictionsListener(this);

            mSensorPrivacyStateController.setAllSensorPrivacyListener(
                    mHandler, mHandler::handleSensorPrivacyChanged);
            mSensorPrivacyStateController.setSensorPrivacyListener(
                    mHandler,
                    (toggleType, userId, sensor, state) -> mHandler.handleSensorPrivacyChanged(
                            userId, toggleType, sensor, state.isEnabled()));
        }

        @Override
        public void onUserRestrictionsChanged(int userId, Bundle newRestrictions,
                Bundle prevRestrictions) {
            // Reset sensor privacy when restriction is added
            if (!prevRestrictions.getBoolean(UserManager.DISALLOW_CAMERA_TOGGLE)
                    && newRestrictions.getBoolean(UserManager.DISALLOW_CAMERA_TOGGLE)) {
                setToggleSensorPrivacyUnchecked(TOGGLE_TYPE_SOFTWARE, userId, OTHER, CAMERA, false);
            }
            if (!prevRestrictions.getBoolean(UserManager.DISALLOW_MICROPHONE_TOGGLE)
                    && newRestrictions.getBoolean(UserManager.DISALLOW_MICROPHONE_TOGGLE)) {
                setToggleSensorPrivacyUnchecked(TOGGLE_TYPE_SOFTWARE, userId, OTHER, MICROPHONE,
                        false);
            }
        }

        @Override
        public void onOpStarted(int code, int uid, String packageName, String attributionTag,
                @AppOpsManager.OpFlags int flags, @AppOpsManager.Mode int result) {
            onOpNoted(code, uid, packageName, attributionTag, flags, result);
        }

        @Override
        public void onOpNoted(int code, int uid, String packageName,
                String attributionTag, @AppOpsManager.OpFlags int flags,
                @AppOpsManager.Mode int result) {
            if ((flags & AppOpsManager.OP_FLAGS_ALL_TRUSTED) == 0) {
                return;
            }

            int sensor;
            if (result == MODE_IGNORED) {
                if (code == OP_RECORD_AUDIO || code == OP_PHONE_CALL_MICROPHONE) {
                    sensor = MICROPHONE;
                } else if (code == OP_CAMERA || code == OP_PHONE_CALL_CAMERA) {
                    sensor = CAMERA;
                } else {
                    return;
                }
            } else {
                return;
            }

            final long token = Binder.clearCallingIdentity();
            try {
                onSensorUseStarted(uid, packageName, sensor);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        /**
         * Called when a sensor protected by toggle sensor privacy is attempting to get used.
         *
         * @param uid The uid of the app using the sensor
         * @param packageName The package name of the app using the sensor
         * @param sensor The sensor that is attempting to be used
         */
        private void onSensorUseStarted(int uid, String packageName, int sensor) {
            UserHandle user = UserHandle.of(mCurrentUser);
            if (!isCombinedToggleSensorPrivacyEnabled(sensor)) {
                return;
            }

            if (uid == Process.SYSTEM_UID) {
                // If the system uid is being blamed for sensor access, the ui must be shown
                // explicitly using SensorPrivacyManager#showSensorUseDialog
                return;
            }

            synchronized (mLock) {
                if (mSuppressReminders.containsKey(new Pair<>(sensor, user))) {
                    Log.d(TAG,
                            "Suppressed sensor privacy reminder for " + packageName + "/"
                                    + user);
                    return;
                }
            }

            // TODO: Handle reminders with multiple sensors

            // - If we have a likely activity that triggered the sensor use overlay a dialog over
            //   it. This should be the most common case.
            // - If there is no use visible entity that triggered the sensor don't show anything as
            //   this is - from the point of the user - a background usage
            // - Otherwise show a notification as we are not quite sure where to display the dialog.

            List<RunningTaskInfo> tasksOfPackageUsingSensor = new ArrayList<>();

            List<RunningTaskInfo> tasks = mActivityTaskManager.getTasks(Integer.MAX_VALUE);
            int numTasks = tasks.size();
            for (int taskNum = 0; taskNum < numTasks; taskNum++) {
                RunningTaskInfo task = tasks.get(taskNum);

                if (task.isVisible) {
                    if (task.topActivity.getPackageName().equals(packageName)) {
                        if (task.isFocused) {
                            // There is the one focused activity
                            enqueueSensorUseReminderDialogAsync(task.taskId, user, packageName,
                                    sensor);
                            return;
                        }

                        tasksOfPackageUsingSensor.add(task);
                    } else if (task.topActivity.flattenToString().equals(
                            getSensorUseActivityName(new ArraySet<>(Arrays.asList(sensor))))
                            && task.isFocused) {
                        enqueueSensorUseReminderDialogAsync(task.taskId, user, packageName,
                                sensor);
                    }
                }
            }

            // TODO: Test this case
            // There is one or more non-focused activity
            if (tasksOfPackageUsingSensor.size() == 1) {
                enqueueSensorUseReminderDialogAsync(tasksOfPackageUsingSensor.get(0).taskId, user,
                        packageName, sensor);
                return;
            } else if (tasksOfPackageUsingSensor.size() > 1) {
                showSensorUseReminderNotification(user, packageName, sensor);
                return;
            }

            // TODO: Test this case
            // Check if there is a foreground service for this package
            List<RunningServiceInfo> services = mActivityManager.getRunningServices(
                    Integer.MAX_VALUE);
            int numServices = services.size();
            for (int serviceNum = 0; serviceNum < numServices; serviceNum++) {
                RunningServiceInfo service = services.get(serviceNum);

                if (service.foreground && service.service.getPackageName().equals(packageName)) {
                    showSensorUseReminderNotification(user, packageName, sensor);
                    return;
                }
            }

            String inputMethodComponent = Settings.Secure.getStringForUser(
                    mContext.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD,
                    user.getIdentifier());
            String inputMethodPackageName = null;
            if (inputMethodComponent != null) {
                inputMethodPackageName = ComponentName.unflattenFromString(
                        inputMethodComponent).getPackageName();
            }

            int capability;
            try {
                capability = mActivityManagerInternal.getUidCapability(uid);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, e);
                return;
            }

            if (sensor == MICROPHONE) {
                VoiceInteractionManagerInternal voiceInteractionManagerInternal =
                        LocalServices.getService(VoiceInteractionManagerInternal.class);
                if (voiceInteractionManagerInternal != null
                        && voiceInteractionManagerInternal.hasActiveSession(packageName)) {
                    enqueueSensorUseReminderDialogAsync(-1, user, packageName, sensor);
                    return;
                }

                if (TextUtils.equals(packageName, inputMethodPackageName)
                        && (capability & PROCESS_CAPABILITY_FOREGROUND_MICROPHONE) != 0) {
                    enqueueSensorUseReminderDialogAsync(-1, user, packageName, sensor);
                    return;
                }
            }

            if (sensor == CAMERA && TextUtils.equals(packageName, inputMethodPackageName)
                    && (capability & PROCESS_CAPABILITY_FOREGROUND_CAMERA) != 0) {
                enqueueSensorUseReminderDialogAsync(-1, user, packageName, sensor);
                return;
            }

            Log.i(TAG, packageName + "/" + uid + " started using sensor " + sensor
                    + " but no activity or foreground service was running. The user will not be"
                    + " informed. System components should check if sensor privacy is enabled for"
                    + " the sensor before accessing it.");
        }

        /**
         * Show a dialog that informs the user that a sensor use or a blocked sensor started.
         * The user can then react to this event.
         *
         * @param taskId The task this dialog should be overlaid on.
         * @param user The user of the package using the sensor.
         * @param packageName The name of the package using the sensor.
         * @param sensor The sensor that is being used.
         */
        private void enqueueSensorUseReminderDialogAsync(int taskId, @NonNull UserHandle user,
                @NonNull String packageName, int sensor) {
            mHandler.sendMessage(PooledLambda.obtainMessage(
                    SensorPrivacyServiceImpl::enqueueSensorUseReminderDialog, this, taskId, user,
                    packageName, sensor));
        }

        private void enqueueSensorUseReminderDialog(int taskId, @NonNull UserHandle user,
                @NonNull String packageName, int sensor) {
            SensorUseReminderDialogInfo info =
                    new SensorUseReminderDialogInfo(taskId, user, packageName);
            if (!mQueuedSensorUseReminderDialogs.containsKey(info)) {
                ArraySet<Integer> sensors = new ArraySet<>();
                if (sensor == MICROPHONE && mSuppressReminders.containsKey(new Pair<>(CAMERA, user))
                        || sensor == CAMERA && mSuppressReminders
                        .containsKey(new Pair<>(MICROPHONE, user))) {
                    sensors.add(MICROPHONE);
                    sensors.add(CAMERA);
                } else {
                    sensors.add(sensor);
                }
                mQueuedSensorUseReminderDialogs.put(info, sensors);
                mHandler.sendMessageDelayed(PooledLambda.obtainMessage(
                        SensorPrivacyServiceImpl::showSensorUserReminderDialog, this, info),
                        REMINDER_DIALOG_DELAY_MILLIS);
                return;
            }
            ArraySet<Integer> sensors = mQueuedSensorUseReminderDialogs.get(info);
            sensors.add(sensor);
        }

        private void showSensorUserReminderDialog(@NonNull SensorUseReminderDialogInfo info) {
            ArraySet<Integer> sensors = mQueuedSensorUseReminderDialogs.get(info);
            mQueuedSensorUseReminderDialogs.remove(info);
            if (sensors == null) {
                Log.e(TAG, "Unable to show sensor use dialog because sensor set is null."
                        + " Was the dialog queue modified from outside the handler thread?");
                return;
            }
            Intent dialogIntent = new Intent();
            dialogIntent.setComponent(
                    ComponentName.unflattenFromString(getSensorUseActivityName(sensors)));

            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchTaskId(info.mTaskId);
            options.setTaskOverlay(true, true);

            dialogIntent.addFlags(
                    FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS | FLAG_ACTIVITY_NO_USER_ACTION);

            dialogIntent.putExtra(EXTRA_PACKAGE_NAME, info.mPackageName);
            if (sensors.size() == 1) {
                dialogIntent.putExtra(EXTRA_SENSOR, sensors.valueAt(0));
            } else if (sensors.size() == 2) {
                dialogIntent.putExtra(EXTRA_ALL_SENSORS, true);
            } else {
                // Currently the only cases can be 1 or two
                Log.e(TAG, "Attempted to show sensor use dialog for " + sensors.size()
                        + " sensors");
                return;
            }
            mContext.startActivityAsUser(dialogIntent, options.toBundle(), UserHandle.SYSTEM);
        }

        /**
         * Get the activity component based on which privacy toggles are enabled.
         * @param sensors
         * @return component name to launch
         */
        private String getSensorUseActivityName(ArraySet<Integer> sensors) {
            for (Integer sensor : sensors) {
                if (isToggleSensorPrivacyEnabled(TOGGLE_TYPE_HARDWARE, sensor)) {
                    return mContext.getResources().getString(
                            R.string.config_sensorUseStartedActivity_hwToggle);
                }
            }
            return mContext.getResources().getString(R.string.config_sensorUseStartedActivity);
        }

        /**
         * Show a notification that informs the user that a sensor use or a blocked sensor started.
         * The user can then react to this event.
         *
         * @param user The user of the package using the sensor.
         * @param packageName The name of the package using the sensor.
         * @param sensor The sensor that is being used.
         */
        private void showSensorUseReminderNotification(@NonNull UserHandle user,
                @NonNull String packageName, int sensor) {
            int iconRes;
            int messageRes;
            int notificationId;

            CharSequence packageLabel;
            try {
                packageLabel = getUiContext().getPackageManager()
                        .getApplicationInfoAsUser(packageName, 0, user)
                        .loadLabel(mContext.getPackageManager());
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(TAG, "Cannot show sensor use notification for " + packageName);
                return;
            }

            if (sensor == MICROPHONE) {
                iconRes = R.drawable.ic_mic_blocked;
                messageRes = R.string.sensor_privacy_start_use_mic_notification_content_title;
                notificationId = SystemMessage.NOTE_UNBLOCK_MIC_TOGGLE;
            } else {
                iconRes = R.drawable.ic_camera_blocked;
                messageRes = R.string.sensor_privacy_start_use_camera_notification_content_title;
                notificationId = SystemMessage.NOTE_UNBLOCK_CAM_TOGGLE;
            }

            NotificationManager notificationManager =
                    mContext.getSystemService(NotificationManager.class);
            NotificationChannel channel = new NotificationChannel(
                    SENSOR_PRIVACY_CHANNEL_ID,
                    getUiContext().getString(R.string.sensor_privacy_notification_channel_label),
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setSound(null, null);
            channel.setBypassDnd(true);
            channel.enableVibration(false);
            channel.setBlockable(false);

            notificationManager.createNotificationChannel(channel);

            Icon icon = Icon.createWithResource(getUiContext().getResources(), iconRes);

            String contentTitle = getUiContext().getString(messageRes);
            Spanned contentText = Html.fromHtml(getUiContext().getString(
                    R.string.sensor_privacy_start_use_notification_content_text, packageLabel), 0);
            PendingIntent contentIntent = PendingIntent.getActivity(mContext, sensor,
                    new Intent(Settings.ACTION_PRIVACY_SETTINGS),
                    PendingIntent.FLAG_IMMUTABLE
                            | PendingIntent.FLAG_UPDATE_CURRENT);

            String actionTitle = getUiContext().getString(
                    R.string.sensor_privacy_start_use_dialog_turn_on_button);
            PendingIntent actionIntent = PendingIntent.getBroadcast(mContext, sensor,
                    new Intent(ACTION_DISABLE_TOGGLE_SENSOR_PRIVACY)
                            .setPackage(mContext.getPackageName())
                            .putExtra(EXTRA_SENSOR, sensor)
                            .putExtra(Intent.EXTRA_USER, user),
                    PendingIntent.FLAG_IMMUTABLE
                            | PendingIntent.FLAG_UPDATE_CURRENT);
            notificationManager.notify(notificationId,
                    new Notification.Builder(mContext, SENSOR_PRIVACY_CHANNEL_ID)
                            .setContentTitle(contentTitle)
                            .setContentText(contentText)
                            .setSmallIcon(icon)
                            .addAction(new Notification.Action.Builder(icon,
                                    actionTitle, actionIntent).build())
                            .setContentIntent(contentIntent)
                            .extend(new Notification.TvExtender())
                            .setTimeoutAfter(isTelevision(mContext)
                                    ? /* dismiss immediately */ 1
                                    : /* no timeout */ 0)
                            .build());
        }

        private boolean isTelevision(Context context) {
            int uiMode = context.getResources().getConfiguration().uiMode;
            return (uiMode & Configuration.UI_MODE_TYPE_MASK)
                    == Configuration.UI_MODE_TYPE_TELEVISION;
        }

        /**
         * Sets the sensor privacy to the provided state and notifies all listeners of the new
         * state.
         */
        @Override
        public void setSensorPrivacy(boolean enable) {
            enforceManageSensorPrivacyPermission();
            mSensorPrivacyStateController.setAllSensorState(enable);
        }

        @Override
        public void setToggleSensorPrivacy(@UserIdInt int userId,
                @SensorPrivacyManager.Sources.Source int source, int sensor, boolean enable) {
            if (DEBUG) {
                Log.d(TAG, "callingUid=" + Binder.getCallingUid()
                        + " callingPid=" + Binder.getCallingPid()
                        + " setToggleSensorPrivacy("
                        + "userId=" + userId
                        + " source=" + source
                        + " sensor=" + sensor
                        + " enable=" + enable
                        + ")");
            }
            enforceManageSensorPrivacyPermission();
            if (userId == UserHandle.USER_CURRENT) {
                userId = mCurrentUser;
            }
            if (!canChangeToggleSensorPrivacy(userId, sensor)) {
                return;
            }

            setToggleSensorPrivacyUnchecked(TOGGLE_TYPE_SOFTWARE, userId, source, sensor, enable);
        }

        private void setToggleSensorPrivacyUnchecked(int toggleType, int userId, int source,
                int sensor, boolean enable) {
            final long[] lastChange = new long[1];
            mSensorPrivacyStateController.atomic(() -> {
                SensorState sensorState = mSensorPrivacyStateController
                        .getState(toggleType, userId, sensor);
                lastChange[0] = sensorState.getLastChange();
                mSensorPrivacyStateController.setState(
                        toggleType, userId, sensor, enable, mHandler,
                        changeSuccessful -> {
                            if (changeSuccessful) {
                                if (userId == mUserManagerInternal.getProfileParentId(userId)) {
                                    mHandler.sendMessage(PooledLambda.obtainMessage(
                                            SensorPrivacyServiceImpl::logSensorPrivacyToggle, this,
                                            source, sensor, enable, lastChange[0], false));
                                }
                            }
                        });
            });
        }

        private boolean canChangeToggleSensorPrivacy(@UserIdInt int userId, int sensor) {
            if (sensor == MICROPHONE && mCallStateHelper.isInEmergencyCall()) {
                // During emergency call the microphone toggle managed automatically
                Log.i(TAG, "Can't change mic toggle during an emergency call");
                return false;
            }

            if (requiresAuthentication() && mKeyguardManager != null
                    && mKeyguardManager.isDeviceLocked(userId)) {
                Log.i(TAG, "Can't change mic/cam toggle while device is locked");
                return false;
            }

            if (sensor == MICROPHONE && mUserManagerInternal.getUserRestriction(userId,
                    UserManager.DISALLOW_MICROPHONE_TOGGLE)) {
                Log.i(TAG, "Can't change mic toggle due to admin restriction");
                return false;
            }

            if (sensor == CAMERA && mUserManagerInternal.getUserRestriction(userId,
                    UserManager.DISALLOW_CAMERA_TOGGLE)) {
                Log.i(TAG, "Can't change camera toggle due to admin restriction");
                return false;
            }
            return true;
        }

        private void logSensorPrivacyToggle(int source, int sensor, boolean enabled,
                long lastChange, boolean onShutDown) {
            long logMins = Math.max(0, (getCurrentTimeMillis() - lastChange) / (1000 * 60));

            int logAction = -1;
            if (onShutDown) {
                // TODO ACTION_POWER_OFF_WHILE_(ON/OFF)
                if (enabled) {
                    logAction = PRIVACY_SENSOR_TOGGLE_INTERACTION__ACTION__ACTION_UNKNOWN;
                } else {
                    logAction = PRIVACY_SENSOR_TOGGLE_INTERACTION__ACTION__ACTION_UNKNOWN;
                }
            } else {
                if (enabled) {
                    logAction = PRIVACY_SENSOR_TOGGLE_INTERACTION__ACTION__TOGGLE_OFF;
                } else {
                    logAction = PRIVACY_SENSOR_TOGGLE_INTERACTION__ACTION__TOGGLE_ON;
                }
            }

            int logSensor = -1;
            switch(sensor) {
                case CAMERA:
                    logSensor = PRIVACY_SENSOR_TOGGLE_INTERACTION__SENSOR__CAMERA;
                    break;
                case MICROPHONE:
                    logSensor = PRIVACY_SENSOR_TOGGLE_INTERACTION__SENSOR__MICROPHONE;
                    break;
                default:
                    logSensor = PRIVACY_SENSOR_TOGGLE_INTERACTION__SENSOR__SENSOR_UNKNOWN;
            }

            int logSource = -1;
            switch(source) {
                case QS_TILE :
                    logSource = PRIVACY_SENSOR_TOGGLE_INTERACTION__SOURCE__QS_TILE;
                    break;
                case DIALOG :
                    logSource = PRIVACY_SENSOR_TOGGLE_INTERACTION__SOURCE__DIALOG;
                    break;
                case SETTINGS:
                    logSource = PRIVACY_SENSOR_TOGGLE_INTERACTION__SOURCE__SETTINGS;
                    break;
                default:
                    logSource = PRIVACY_SENSOR_TOGGLE_INTERACTION__SOURCE__SOURCE_UNKNOWN;
            }

            if (DEBUG || DEBUG_LOGGING) {
                Log.d(TAG, "Logging sensor toggle interaction:" + " logSensor=" + logSensor
                        + " logAction=" + logAction + " logSource=" + logSource + " logMins="
                        + logMins);
            }
            write(PRIVACY_SENSOR_TOGGLE_INTERACTION, logSensor, logAction, logSource, logMins);

        }

        @Override
        public void setToggleSensorPrivacyForProfileGroup(@UserIdInt int userId,
                @SensorPrivacyManager.Sources.Source int source, int sensor, boolean enable) {
            enforceManageSensorPrivacyPermission();
            if (userId == UserHandle.USER_CURRENT) {
                userId = mCurrentUser;
            }
            int parentId = mUserManagerInternal.getProfileParentId(userId);
            forAllUsers(userId2 -> {
                if (parentId == mUserManagerInternal.getProfileParentId(userId2)) {
                    setToggleSensorPrivacy(userId2, source, sensor, enable);
                }
            });
        }

        /**
         * Enforces the caller contains the necessary permission to change the state of sensor
         * privacy.
         */
        private void enforceManageSensorPrivacyPermission() {
            enforcePermission(android.Manifest.permission.MANAGE_SENSOR_PRIVACY,
                    "Changing sensor privacy requires the following permission: "
                            + MANAGE_SENSOR_PRIVACY);
        }

        /**
         * Enforces the caller contains the necessary permission to observe changes to the sate of
         * sensor privacy.
         */
        private void enforceObserveSensorPrivacyPermission() {
            String systemUIPackage = mContext.getString(R.string.config_systemUi);
            if (Binder.getCallingUid() == mPackageManagerInternal
                    .getPackageUid(systemUIPackage, MATCH_SYSTEM_ONLY, UserHandle.USER_SYSTEM)) {
                // b/221782106, possible race condition with role grant might bootloop device.
                return;
            }
            enforcePermission(android.Manifest.permission.OBSERVE_SENSOR_PRIVACY,
                    "Observing sensor privacy changes requires the following permission: "
                            + android.Manifest.permission.OBSERVE_SENSOR_PRIVACY);
        }

        private void enforcePermission(String permission, String message) {
            if (mContext.checkCallingOrSelfPermission(permission) == PERMISSION_GRANTED) {
                return;
            }
            throw new SecurityException(message);
        }

        /**
         * Returns whether sensor privacy is enabled.
         */
        @Override
        public boolean isSensorPrivacyEnabled() {
            enforceObserveSensorPrivacyPermission();
            return mSensorPrivacyStateController.getAllSensorState();
        }

        @Override
        public boolean isToggleSensorPrivacyEnabled(int toggleType, int sensor) {
            if (DEBUG) {
                Log.d(TAG, "callingUid=" + Binder.getCallingUid()
                        + " callingPid=" + Binder.getCallingPid()
                        + " isToggleSensorPrivacyEnabled("
                        + "toggleType=" + toggleType
                        + " sensor=" + sensor
                        + ")");
            }
            enforceObserveSensorPrivacyPermission();

            return mSensorPrivacyStateController.getState(toggleType, mCurrentUser, sensor)
                    .isEnabled();
        }

        @Override
        public boolean isCombinedToggleSensorPrivacyEnabled(int sensor) {
            return isToggleSensorPrivacyEnabled(TOGGLE_TYPE_SOFTWARE, sensor)
                    || isToggleSensorPrivacyEnabled(TOGGLE_TYPE_HARDWARE, sensor);
        }

        private boolean isToggleSensorPrivacyEnabledInternal(int userId, int toggleType,
                int sensor) {

            return mSensorPrivacyStateController.getState(toggleType,
                    userId, sensor).isEnabled();
        }

        @Override
        public boolean supportsSensorToggle(int toggleType, int sensor) {
            if (toggleType == TOGGLE_TYPE_SOFTWARE) {
                if (sensor == MICROPHONE) {
                    return mContext.getResources()
                            .getBoolean(R.bool.config_supportsMicToggle);
                } else if (sensor == CAMERA) {
                    return mContext.getResources()
                            .getBoolean(R.bool.config_supportsCamToggle);
                }
            } else if (toggleType == TOGGLE_TYPE_HARDWARE) {
                if (sensor == MICROPHONE) {
                    return mContext.getResources()
                            .getBoolean(R.bool.config_supportsHardwareMicToggle);
                } else if (sensor == CAMERA) {
                    return mContext.getResources()
                            .getBoolean(R.bool.config_supportsHardwareCamToggle);
                }
            }
            throw new IllegalArgumentException("Invalid arguments. "
                    + "toggleType=" + toggleType + " sensor=" + sensor);
        }

        /**
         * Registers a listener to be notified when the sensor privacy state changes.
         */
        @Override
        public void addSensorPrivacyListener(ISensorPrivacyListener listener) {
            enforceObserveSensorPrivacyPermission();
            if (listener == null) {
                throw new NullPointerException("listener cannot be null");
            }
            mHandler.addListener(listener);
        }

        /**
         * Registers a listener to be notified when the sensor privacy state changes.
         */
        @Override
        public void addToggleSensorPrivacyListener(ISensorPrivacyListener listener) {
            enforceObserveSensorPrivacyPermission();
            if (listener == null) {
                throw new IllegalArgumentException("listener cannot be null");
            }
            mHandler.addToggleListener(listener);
        }

        /**
         * Unregisters a listener from sensor privacy state change notifications.
         */
        @Override
        public void removeSensorPrivacyListener(ISensorPrivacyListener listener) {
            enforceObserveSensorPrivacyPermission();
            if (listener == null) {
                throw new NullPointerException("listener cannot be null");
            }
            mHandler.removeListener(listener);
        }

        /**
         * Unregisters a listener from sensor privacy state change notifications.
         */
        @Override
        public void removeToggleSensorPrivacyListener(ISensorPrivacyListener listener) {
            enforceObserveSensorPrivacyPermission();
            if (listener == null) {
                throw new IllegalArgumentException("listener cannot be null");
            }
            mHandler.removeToggleListener(listener);
        }

        @Override
        public void suppressToggleSensorPrivacyReminders(int userId, int sensor,
                IBinder token, boolean suppress) {
            enforceManageSensorPrivacyPermission();
            if (userId == UserHandle.USER_CURRENT) {
                userId = mCurrentUser;
            }
            Objects.requireNonNull(token);

            Pair<Integer, UserHandle> key = new Pair<>(sensor, UserHandle.of(userId));

            synchronized (mLock) {
                if (suppress) {
                    try {
                        token.linkToDeath(this, 0);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Could not suppress sensor use reminder", e);
                        return;
                    }

                    ArrayList<IBinder> suppressPackageReminderTokens = mSuppressReminders.get(key);
                    if (suppressPackageReminderTokens == null) {
                        suppressPackageReminderTokens = new ArrayList<>(1);
                        mSuppressReminders.put(key, suppressPackageReminderTokens);
                    }

                    suppressPackageReminderTokens.add(token);
                } else {
                    mHandler.removeSuppressPackageReminderToken(key, token);
                }
            }
        }

        @Override
        public boolean requiresAuthentication() {
            enforceObserveSensorPrivacyPermission();
            return mContext.getResources()
                    .getBoolean(R.bool.config_sensorPrivacyRequiresAuthentication);
        }

        @Override
        public void showSensorUseDialog(int sensor) {
            if (Binder.getCallingUid() != Process.SYSTEM_UID) {
                throw new SecurityException("Can only be called by the system uid");
            }
            if (!isCombinedToggleSensorPrivacyEnabled(sensor)) {
                return;
            }
            enqueueSensorUseReminderDialogAsync(
                    -1, UserHandle.of(mCurrentUser), "android", sensor);
        }

        private void userSwitching(int from, int to) {
            final boolean[] micState = new boolean[2];
            final boolean[] camState = new boolean[2];
            final boolean[] prevMicState = new boolean[2];
            final boolean[] prevCamState = new boolean[2];
            final int swToggleIdx = 0;
            final int hwToggleIdx = 1;
            // Get SW toggles state
            mSensorPrivacyStateController.atomic(() -> {
                prevMicState[swToggleIdx] = isToggleSensorPrivacyEnabledInternal(from,
                        TOGGLE_TYPE_SOFTWARE, MICROPHONE);
                prevCamState[swToggleIdx] = isToggleSensorPrivacyEnabledInternal(from,
                        TOGGLE_TYPE_SOFTWARE, CAMERA);
                micState[swToggleIdx] = isToggleSensorPrivacyEnabledInternal(to,
                        TOGGLE_TYPE_SOFTWARE, MICROPHONE);
                camState[swToggleIdx] = isToggleSensorPrivacyEnabledInternal(to,
                        TOGGLE_TYPE_SOFTWARE, CAMERA);
            });
            // Get HW toggles state
            mSensorPrivacyStateController.atomic(() -> {
                prevMicState[hwToggleIdx] = isToggleSensorPrivacyEnabledInternal(from,
                        TOGGLE_TYPE_HARDWARE, MICROPHONE);
                prevCamState[hwToggleIdx] = isToggleSensorPrivacyEnabledInternal(from,
                        TOGGLE_TYPE_HARDWARE, CAMERA);
                micState[hwToggleIdx] = isToggleSensorPrivacyEnabledInternal(to,
                        TOGGLE_TYPE_HARDWARE, MICROPHONE);
                camState[hwToggleIdx] = isToggleSensorPrivacyEnabledInternal(to,
                        TOGGLE_TYPE_HARDWARE, CAMERA);
            });

            if (from == USER_NULL || prevMicState[swToggleIdx] != micState[swToggleIdx]
                    || prevMicState[hwToggleIdx] != micState[hwToggleIdx]) {
                mHandler.handleSensorPrivacyChanged(to, TOGGLE_TYPE_SOFTWARE, MICROPHONE,
                        micState[swToggleIdx]);
                mHandler.handleSensorPrivacyChanged(to, TOGGLE_TYPE_HARDWARE, MICROPHONE,
                        micState[hwToggleIdx]);
                setGlobalRestriction(MICROPHONE, micState[swToggleIdx] || micState[hwToggleIdx]);
            }
            if (from == USER_NULL || prevCamState[swToggleIdx] != camState[swToggleIdx]
                    || prevCamState[hwToggleIdx] != camState[hwToggleIdx]) {
                mHandler.handleSensorPrivacyChanged(to, TOGGLE_TYPE_SOFTWARE, CAMERA,
                        camState[swToggleIdx]);
                mHandler.handleSensorPrivacyChanged(to, TOGGLE_TYPE_HARDWARE, CAMERA,
                        camState[hwToggleIdx]);
                setGlobalRestriction(CAMERA, camState[swToggleIdx] || camState[hwToggleIdx]);
            }
        }

        private void setGlobalRestriction(int sensor, boolean enabled) {
            switch(sensor) {
                case MICROPHONE:
                    mAppOpsManagerInternal.setGlobalRestriction(OP_RECORD_AUDIO, enabled,
                            mAppOpsRestrictionToken);
                    mAppOpsManagerInternal.setGlobalRestriction(OP_PHONE_CALL_MICROPHONE, enabled,
                            mAppOpsRestrictionToken);
                    // We don't show the dialog for RECEIVE_SOUNDTRIGGER_AUDIO, but still want to
                    // restrict it when the microphone is disabled
                    mAppOpsManagerInternal.setGlobalRestriction(OP_RECEIVE_AMBIENT_TRIGGER_AUDIO,
                            enabled, mAppOpsRestrictionToken);
                    break;
                case CAMERA:
                    mAppOpsManagerInternal.setGlobalRestriction(OP_CAMERA, enabled,
                            mAppOpsRestrictionToken);
                    mAppOpsManagerInternal.setGlobalRestriction(OP_PHONE_CALL_CAMERA, enabled,
                            mAppOpsRestrictionToken);
                    break;
            }
        }

        /**
         * Remove a sensor use reminder suppression token.
         *
         * @param key Key the token is in
         * @param token The token to remove
         */
        private void removeSuppressPackageReminderToken(@NonNull Pair<Integer, UserHandle> key,
                @NonNull IBinder token) {
            synchronized (mLock) {
                ArrayList<IBinder> suppressPackageReminderTokens =
                        mSuppressReminders.get(key);
                if (suppressPackageReminderTokens == null) {
                    Log.e(TAG, "No tokens for " + key);
                    return;
                }

                boolean wasRemoved = suppressPackageReminderTokens.remove(token);
                if (wasRemoved) {
                    token.unlinkToDeath(this, 0);

                    if (suppressPackageReminderTokens.isEmpty()) {
                        mSuppressReminders.remove(key);
                    }
                } else {
                    Log.w(TAG, "Could not remove sensor use reminder suppression token " + token
                            + " from " + key);
                }
            }
        }

        /**
         * A owner of a suppressor token died. Clean up.
         *
         * @param token The token that is invalid now.
         */
        @Override
        public void binderDied(@NonNull IBinder token) {
            synchronized (mLock) {
                for (Pair<Integer, UserHandle> key : mSuppressReminders.keySet()) {
                    removeSuppressPackageReminderToken(key, token);
                }
            }
        }

        @Override
        public void binderDied() {
            // Handled in binderDied(IBinder)
        }

        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            Objects.requireNonNull(fd);

            if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

            int opti = 0;
            boolean dumpAsProto = false;
            while (opti < args.length) {
                String opt = args[opti];
                if (opt == null || opt.length() <= 0 || opt.charAt(0) != '-') {
                    break;
                }
                opti++;
                if ("--proto".equals(opt)) {
                    dumpAsProto = true;
                } else {
                    pw.println("Unknown argument: " + opt + "; use -h for help");
                }
            }

            final long identity = Binder.clearCallingIdentity();
            try {
                if (dumpAsProto) {
                    mSensorPrivacyStateController.dump(
                            new DualDumpOutputStream(new ProtoOutputStream(fd)));
                } else {
                    pw.println("SENSOR PRIVACY MANAGER STATE (dumpsys "
                            + Context.SENSOR_PRIVACY_SERVICE + ")");

                    mSensorPrivacyStateController.dump(
                            new DualDumpOutputStream(new IndentingPrintWriter(pw, "  ")));
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        /**
         * Convert a string into a {@link SensorPrivacyManager.Sensors.Sensor id}.
         *
         * @param sensor The name to convert
         *
         * @return The id corresponding to the name
         */
        private @SensorPrivacyManager.Sensors.Sensor int sensorStrToId(@Nullable String sensor) {
            if (sensor == null) {
                return UNKNOWN;
            }

            switch (sensor) {
                case "microphone":
                    return MICROPHONE;
                case "camera":
                    return CAMERA;
                default: {
                    return UNKNOWN;
                }
            }
        }

        @Override
        public void onShellCommand(FileDescriptor in, FileDescriptor out,
                FileDescriptor err, String[] args, ShellCallback callback,
                ResultReceiver resultReceiver) {
            (new ShellCommand() {
                @Override
                public int onCommand(String cmd) {
                    if (cmd == null) {
                        return handleDefaultCommands(cmd);
                    }

                    int userId = Integer.parseInt(getNextArgRequired());

                    final PrintWriter pw = getOutPrintWriter();
                    switch (cmd) {
                        case "enable" : {
                            int sensor = sensorStrToId(getNextArgRequired());
                            if (sensor == UNKNOWN) {
                                pw.println("Invalid sensor");
                                return -1;
                            }

                            setToggleSensorPrivacy(userId, SHELL, sensor, true);
                        }
                        break;
                        case "disable" : {
                            int sensor = sensorStrToId(getNextArgRequired());
                            if (sensor == UNKNOWN) {
                                pw.println("Invalid sensor");
                                return -1;
                            }

                            setToggleSensorPrivacy(userId, SHELL, sensor, false);
                        }
                        break;
                        default:
                            return handleDefaultCommands(cmd);
                    }

                    return 0;
                }

                @Override
                public void onHelp() {
                    final PrintWriter pw = getOutPrintWriter();

                    pw.println("Sensor privacy manager (" + Context.SENSOR_PRIVACY_SERVICE
                            + ") commands:");
                    pw.println("  help");
                    pw.println("    Print this help text.");
                    pw.println("");
                    pw.println("  enable USER_ID SENSOR");
                    pw.println("    Enable privacy for a certain sensor.");
                    pw.println("");
                    pw.println("  disable USER_ID SENSOR");
                    pw.println("    Disable privacy for a certain sensor.");
                    pw.println("");
                }
            }).exec(this, in, out, err, args, callback, resultReceiver);
        }
    }

    /**
     * Handles sensor privacy state changes and notifying listeners of the change.
     */
    private final class SensorPrivacyHandler extends Handler {
        private static final int MESSAGE_SENSOR_PRIVACY_CHANGED = 1;

        private final Object mListenerLock = new Object();

        @GuardedBy("mListenerLock")
        private final RemoteCallbackList<ISensorPrivacyListener> mListeners =
                new RemoteCallbackList<>();
        @GuardedBy("mListenerLock")
        private final RemoteCallbackList<ISensorPrivacyListener>
                mToggleSensorListeners = new RemoteCallbackList<>();
        @GuardedBy("mListenerLock")
        private final ArrayMap<ISensorPrivacyListener, Pair<DeathRecipient, Integer>>
                mDeathRecipients;
        private final Context mContext;

        SensorPrivacyHandler(Looper looper, Context context) {
            super(looper);
            mDeathRecipients = new ArrayMap<>();
            mContext = context;
        }

        public void addListener(ISensorPrivacyListener listener) {
            synchronized (mListenerLock) {
                if (mListeners.register(listener)) {
                    addDeathRecipient(listener);
                }
            }
        }

        public void addToggleListener(ISensorPrivacyListener listener) {
            synchronized (mListenerLock) {
                if (mToggleSensorListeners.register(listener)) {
                    addDeathRecipient(listener);
                }
            }
        }

        public void removeListener(ISensorPrivacyListener listener) {
            synchronized (mListenerLock) {
                if (mListeners.unregister(listener)) {
                    removeDeathRecipient(listener);
                }
            }
        }

        public void removeToggleListener(ISensorPrivacyListener listener) {
            synchronized (mListenerLock) {
                if (mToggleSensorListeners.unregister(listener)) {
                    removeDeathRecipient(listener);
                }
            }
        }

        public void handleSensorPrivacyChanged(boolean enabled) {
            final int count = mListeners.beginBroadcast();
            for (int i = 0; i < count; i++) {
                ISensorPrivacyListener listener = mListeners.getBroadcastItem(i);
                try {
                    listener.onSensorPrivacyChanged(-1, -1, enabled);
                } catch (RemoteException e) {
                    Log.e(TAG, "Caught an exception notifying listener " + listener + ": ", e);
                }
            }
            mListeners.finishBroadcast();
        }

        public void handleSensorPrivacyChanged(int userId, int toggleType, int sensor,
                boolean enabled) {
            mSensorPrivacyManagerInternal.dispatch(userId, sensor, enabled);

            if (userId == mCurrentUser) {
                mSensorPrivacyServiceImpl.setGlobalRestriction(sensor,
                        mSensorPrivacyServiceImpl.isCombinedToggleSensorPrivacyEnabled(sensor));
            }

            if (userId != mCurrentUser) {
                return;
            }
            synchronized (mListenerLock) {
                try {
                    final int count = mToggleSensorListeners.beginBroadcast();
                    for (int i = 0; i < count; i++) {
                        ISensorPrivacyListener listener = mToggleSensorListeners.getBroadcastItem(
                                i);
                        try {
                            listener.onSensorPrivacyChanged(toggleType, sensor, enabled);
                        } catch (RemoteException e) {
                            Log.e(TAG, "Caught an exception notifying listener " + listener + ": ",
                                    e);
                        }
                    }
                } finally {
                    mToggleSensorListeners.finishBroadcast();
                }
            }
        }

        public void removeSuppressPackageReminderToken(Pair<Integer, UserHandle> key,
                IBinder token) {
            sendMessage(PooledLambda.obtainMessage(
                    SensorPrivacyServiceImpl::removeSuppressPackageReminderToken,
                    mSensorPrivacyServiceImpl, key, token));
        }

        private void addDeathRecipient(ISensorPrivacyListener listener) {
            Pair<DeathRecipient, Integer> deathRecipient = mDeathRecipients.get(listener);
            if (deathRecipient == null) {
                deathRecipient = new Pair<>(new DeathRecipient(listener), 1);
            } else {
                int newRefCount = deathRecipient.second + 1;
                deathRecipient = new Pair<>(deathRecipient.first, newRefCount);
            }
            mDeathRecipients.put(listener, deathRecipient);
        }

        private void removeDeathRecipient(ISensorPrivacyListener listener) {
            Pair<DeathRecipient, Integer> deathRecipient = mDeathRecipients.get(listener);
            if (deathRecipient == null) {
                return;
            } else {
                int newRefCount = deathRecipient.second - 1;
                if (newRefCount == 0) {
                    mDeathRecipients.remove(listener);
                    deathRecipient.first.destroy();
                    return;
                }
                deathRecipient = new Pair<>(deathRecipient.first, newRefCount);
            }
            mDeathRecipients.put(listener, deathRecipient);
        }
    }

    private final class DeathRecipient implements IBinder.DeathRecipient {

        private ISensorPrivacyListener mListener;

        DeathRecipient(ISensorPrivacyListener listener) {
            mListener = listener;
            try {
                mListener.asBinder().linkToDeath(this, 0);
            } catch (RemoteException e) {
            }
        }

        @Override
        public void binderDied() {
            mSensorPrivacyServiceImpl.removeSensorPrivacyListener(mListener);
            mSensorPrivacyServiceImpl.removeToggleSensorPrivacyListener(mListener);
        }

        public void destroy() {
            try {
                mListener.asBinder().unlinkToDeath(this, 0);
            } catch (NoSuchElementException e) {
            }
        }
    }

    private void forAllUsers(FunctionalUtils.ThrowingConsumer<Integer> c) {
        int[] userIds = mUserManagerInternal.getUserIds();
        for (int i = 0; i < userIds.length; i++) {
            c.accept(userIds[i]);
        }
    }

    private class SensorPrivacyManagerInternalImpl extends SensorPrivacyManagerInternal {

        private ArrayMap<Integer, ArrayMap<Integer, ArraySet<OnSensorPrivacyChangedListener>>>
                mListeners = new ArrayMap<>();
        private ArrayMap<Integer, ArraySet<OnUserSensorPrivacyChangedListener>> mAllUserListeners =
                new ArrayMap<>();

        private final Object mLock = new Object();

        private void dispatch(int userId, int sensor, boolean enabled) {
            synchronized (mLock) {
                ArraySet<OnUserSensorPrivacyChangedListener> allUserSensorListeners =
                        mAllUserListeners.get(sensor);
                if (allUserSensorListeners != null) {
                    for (int i = 0; i < allUserSensorListeners.size(); i++) {
                        OnUserSensorPrivacyChangedListener listener =
                                allUserSensorListeners.valueAt(i);
                        BackgroundThread.getHandler().post(() ->
                                listener.onSensorPrivacyChanged(userId, enabled));
                    }
                }

                ArrayMap<Integer, ArraySet<OnSensorPrivacyChangedListener>> userSensorListeners =
                        mListeners.get(userId);
                if (userSensorListeners != null) {
                    ArraySet<OnSensorPrivacyChangedListener> sensorListeners =
                            userSensorListeners.get(sensor);
                    if (sensorListeners != null) {
                        for (int i = 0; i < sensorListeners.size(); i++) {
                            OnSensorPrivacyChangedListener listener = sensorListeners.valueAt(i);
                            BackgroundThread.getHandler().post(() ->
                                    listener.onSensorPrivacyChanged(enabled));
                        }
                    }
                }
            }
        }

        @Override
        public boolean isSensorPrivacyEnabled(int userId, int sensor) {
            return SensorPrivacyService.this
                    .mSensorPrivacyServiceImpl.isToggleSensorPrivacyEnabledInternal(userId,
                            TOGGLE_TYPE_SOFTWARE, sensor);
        }

        @Override
        public void addSensorPrivacyListener(int userId, int sensor,
                OnSensorPrivacyChangedListener listener) {
            synchronized (mLock) {
                ArrayMap<Integer, ArraySet<OnSensorPrivacyChangedListener>> userSensorListeners =
                        mListeners.get(userId);
                if (userSensorListeners == null) {
                    userSensorListeners = new ArrayMap<>();
                    mListeners.put(userId, userSensorListeners);
                }

                ArraySet<OnSensorPrivacyChangedListener> sensorListeners =
                        userSensorListeners.get(sensor);
                if (sensorListeners == null) {
                    sensorListeners = new ArraySet<>();
                    userSensorListeners.put(sensor, sensorListeners);
                }

                sensorListeners.add(listener);
            }
        }

        @Override
        public void addSensorPrivacyListenerForAllUsers(int sensor,
                OnUserSensorPrivacyChangedListener listener) {
            synchronized (mLock) {
                ArraySet<OnUserSensorPrivacyChangedListener> sensorListeners =
                        mAllUserListeners.get(sensor);
                if (sensorListeners == null) {
                    sensorListeners = new ArraySet<>();
                    mAllUserListeners.put(sensor, sensorListeners);
                }

                sensorListeners.add(listener);
            }
        }

        @Override
        public void setPhysicalToggleSensorPrivacy(int userId, int sensor, boolean enable) {
            final SensorPrivacyServiceImpl sps =
                    SensorPrivacyService.this.mSensorPrivacyServiceImpl;

            // Convert userId to actual user Id. mCurrentUser is USER_NULL if toggle state is set
            // before onUserStarting.
            userId = (userId == UserHandle.USER_CURRENT ? mCurrentUser : userId);
            final int realUserId = (userId == UserHandle.USER_NULL ? mContext.getUserId() : userId);

            sps.setToggleSensorPrivacyUnchecked(TOGGLE_TYPE_HARDWARE, realUserId, OTHER, sensor,
                    enable);
            // Also disable the SW toggle when disabling the HW toggle
            if (!enable) {
                sps.setToggleSensorPrivacyUnchecked(TOGGLE_TYPE_SOFTWARE, realUserId, OTHER, sensor,
                        enable);
            }
        }
    }

    private class CallStateHelper {
        private OutgoingEmergencyStateCallback mEmergencyStateCallback;
        private CallStateCallback mCallStateCallback;

        private boolean mIsInEmergencyCall;
        private boolean mMicUnmutedForEmergencyCall;

        private Object mCallStateLock = new Object();

        CallStateHelper() {
            mEmergencyStateCallback = new OutgoingEmergencyStateCallback();
            mCallStateCallback = new CallStateCallback();

            mTelephonyManager.registerTelephonyCallback(FgThread.getExecutor(),
                    mEmergencyStateCallback);
            mTelephonyManager.registerTelephonyCallback(FgThread.getExecutor(),
                    mCallStateCallback);
        }

        boolean isInEmergencyCall() {
            synchronized (mCallStateLock) {
                return mIsInEmergencyCall;
            }
        }

        private class OutgoingEmergencyStateCallback extends TelephonyCallback implements
                TelephonyCallback.OutgoingEmergencyCallListener {
            @Override
            public void onOutgoingEmergencyCall(EmergencyNumber placedEmergencyNumber,
                    int subscriptionId) {
                onEmergencyCall();
            }
        }

        private class CallStateCallback extends TelephonyCallback implements
                TelephonyCallback.CallStateListener {
            @Override
            public void onCallStateChanged(int state) {
                if (state == TelephonyManager.CALL_STATE_IDLE) {
                    onCallOver();
                } else {
                    onCall();
                }
            }
        }

        private void onEmergencyCall() {
            synchronized (mCallStateLock) {
                if (!mIsInEmergencyCall) {
                    mIsInEmergencyCall = true;
                    if (mSensorPrivacyServiceImpl
                            .isToggleSensorPrivacyEnabled(TOGGLE_TYPE_SOFTWARE, MICROPHONE)) {
                        mSensorPrivacyServiceImpl.setToggleSensorPrivacyUnchecked(
                                TOGGLE_TYPE_SOFTWARE, mCurrentUser, OTHER, MICROPHONE, false);
                        mMicUnmutedForEmergencyCall = true;
                    } else {
                        mMicUnmutedForEmergencyCall = false;
                    }
                }
            }
        }

        private void onCall() {
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (mCallStateLock) {
                    mSensorPrivacyServiceImpl.showSensorUseDialog(MICROPHONE);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        private void onCallOver() {
            synchronized (mCallStateLock) {
                if (mIsInEmergencyCall) {
                    mIsInEmergencyCall = false;
                    if (mMicUnmutedForEmergencyCall) {
                        mSensorPrivacyServiceImpl.setToggleSensorPrivacyUnchecked(
                                TOGGLE_TYPE_SOFTWARE, mCurrentUser, OTHER, MICROPHONE, true);
                        mMicUnmutedForEmergencyCall = false;
                    }
                }
            }
        }
    }

    static long getCurrentTimeMillis() {
        return SystemClock.elapsedRealtime();
    }
}
