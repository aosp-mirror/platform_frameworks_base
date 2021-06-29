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

package com.android.server;

import static android.Manifest.permission.MANAGE_SENSOR_PRIVACY;
import static android.app.ActivityManager.PROCESS_CAPABILITY_FOREGROUND_CAMERA;
import static android.app.ActivityManager.PROCESS_CAPABILITY_FOREGROUND_MICROPHONE;
import static android.app.ActivityManager.RunningServiceInfo;
import static android.app.ActivityManager.RunningTaskInfo;
import static android.app.ActivityManager.getCurrentUser;
import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.MODE_IGNORED;
import static android.app.AppOpsManager.OP_CAMERA;
import static android.app.AppOpsManager.OP_PHONE_CALL_CAMERA;
import static android.app.AppOpsManager.OP_PHONE_CALL_MICROPHONE;
import static android.app.AppOpsManager.OP_RECORD_AUDIO;
import static android.app.AppOpsManager.OP_RECORD_AUDIO_HOTWORD;
import static android.content.Intent.EXTRA_PACKAGE_NAME;
import static android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.hardware.SensorPrivacyManager.EXTRA_ALL_SENSORS;
import static android.hardware.SensorPrivacyManager.EXTRA_SENSOR;
import static android.hardware.SensorPrivacyManager.Sensors.CAMERA;
import static android.hardware.SensorPrivacyManager.Sensors.MICROPHONE;
import static android.os.UserHandle.USER_SYSTEM;
import static android.service.SensorPrivacyIndividualEnabledSensorProto.UNKNOWN;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.app.AppOpsManager;
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
import android.graphics.drawable.Icon;
import android.hardware.ISensorPrivacyListener;
import android.hardware.ISensorPrivacyManager;
import android.hardware.SensorPrivacyManager;
import android.hardware.SensorPrivacyManagerInternal;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.service.SensorPrivacyIndividualEnabledSensorProto;
import android.service.SensorPrivacyServiceDumpProto;
import android.service.SensorPrivacyUserProto;
import android.service.voice.VoiceInteractionManagerInternal;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.telephony.emergency.EmergencyNumber;
import android.text.Html;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.Xml;
import android.util.proto.ProtoOutputStream;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FunctionalUtils;
import com.android.internal.util.XmlUtils;
import com.android.internal.util.dump.DualDumpOutputStream;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.pm.UserManagerInternal;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/** @hide */
public final class SensorPrivacyService extends SystemService {

    private static final String TAG = SensorPrivacyService.class.getSimpleName();

    /** Version number indicating compatibility parsing the persisted file */
    private static final int CURRENT_PERSISTENCE_VERSION = 1;
    /** Version number indicating the persisted data needs upgraded to match new internal data
     *  structures and features */
    private static final int CURRENT_VERSION = 1;

    private static final String SENSOR_PRIVACY_XML_FILE = "sensor_privacy.xml";
    private static final String XML_TAG_SENSOR_PRIVACY = "sensor-privacy";
    private static final String XML_TAG_USER = "user";
    private static final String XML_TAG_INDIVIDUAL_SENSOR_PRIVACY = "individual-sensor-privacy";
    private static final String XML_ATTRIBUTE_ID = "id";
    private static final String XML_ATTRIBUTE_PERSISTENCE_VERSION = "persistence-version";
    private static final String XML_ATTRIBUTE_VERSION = "version";
    private static final String XML_ATTRIBUTE_ENABLED = "enabled";
    private static final String XML_ATTRIBUTE_SENSOR = "sensor";

    private static final String SENSOR_PRIVACY_CHANNEL_ID = Context.SENSOR_PRIVACY_SERVICE;
    private static final String ACTION_DISABLE_INDIVIDUAL_SENSOR_PRIVACY =
            SensorPrivacyService.class.getName() + ".action.disable_sensor_privacy";

    // These are associated with fields that existed for older persisted versions of files
    private static final int VER0_ENABLED = 0;
    private static final int VER0_INDIVIDUAL_ENABLED = 1;
    private static final int VER1_ENABLED = 0;
    private static final int VER1_INDIVIDUAL_ENABLED = 1;
    public static final int REMINDER_DIALOG_DELAY_MILLIS = 500;

    private final Context mContext;
    private final SensorPrivacyServiceImpl mSensorPrivacyServiceImpl;
    private final UserManagerInternal mUserManagerInternal;
    private final ActivityManager mActivityManager;
    private final ActivityManagerInternal mActivityManagerInternal;
    private final ActivityTaskManager mActivityTaskManager;
    private final AppOpsManager mAppOpsManager;
    private final TelephonyManager mTelephonyManager;

    private final IBinder mAppOpsRestrictionToken = new Binder();

    private SensorPrivacyManagerInternalImpl mSensorPrivacyManagerInternal;

    private EmergencyCallHelper mEmergencyCallHelper;
    private KeyguardManager mKeyguardManager;

    public SensorPrivacyService(Context context) {
        super(context);
        mContext = context;
        mAppOpsManager = context.getSystemService(AppOpsManager.class);
        mUserManagerInternal = getLocalService(UserManagerInternal.class);
        mActivityManager = context.getSystemService(ActivityManager.class);
        mActivityManagerInternal = getLocalService(ActivityManagerInternal.class);
        mActivityTaskManager = context.getSystemService(ActivityTaskManager.class);
        mTelephonyManager = context.getSystemService(TelephonyManager.class);

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
            mEmergencyCallHelper = new EmergencyCallHelper();
        }
    }

    class SensorPrivacyServiceImpl extends ISensorPrivacyManager.Stub implements
            AppOpsManager.OnOpNotedListener, AppOpsManager.OnOpStartedListener,
            IBinder.DeathRecipient, UserManagerInternal.UserRestrictionsListener {

        private final SensorPrivacyHandler mHandler;
        private final Object mLock = new Object();
        @GuardedBy("mLock")
        private final AtomicFile mAtomicFile;
        @GuardedBy("mLock")
        private SparseBooleanArray mEnabled = new SparseBooleanArray();
        @GuardedBy("mLock")
        private SparseArray<SparseBooleanArray> mIndividualEnabled = new SparseArray<>();

        /**
         * Packages for which not to show sensor use reminders.
         *
         * <Package, User> -> list of suppressor tokens
         */
        @GuardedBy("mLock")
        private ArrayMap<Pair<String, UserHandle>, ArrayList<IBinder>> mSuppressReminders =
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
            File sensorPrivacyFile = new File(Environment.getDataSystemDirectory(),
                    SENSOR_PRIVACY_XML_FILE);
            mAtomicFile = new AtomicFile(sensorPrivacyFile);
            synchronized (mLock) {
                if (readPersistedSensorPrivacyStateLocked()) {
                    persistSensorPrivacyStateLocked();
                }

                for (int i = 0; i < mIndividualEnabled.size(); i++) {
                    int userId = mIndividualEnabled.keyAt(i);
                    SparseBooleanArray userIndividualEnabled =
                            mIndividualEnabled.valueAt(i);
                    for (int j = 0; j < userIndividualEnabled.size(); j++) {
                        int sensor = userIndividualEnabled.keyAt(j);
                        boolean enabled = userIndividualEnabled.valueAt(j);
                        setUserRestriction(userId, sensor, enabled);
                    }
                }
            }

            int[] micAndCameraOps = new int[]{OP_RECORD_AUDIO, OP_PHONE_CALL_MICROPHONE,
                    OP_CAMERA, OP_PHONE_CALL_CAMERA};
            mAppOpsManager.startWatchingNoted(micAndCameraOps, this);
            mAppOpsManager.startWatchingStarted(micAndCameraOps, this);

            mContext.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    setIndividualSensorPrivacy(
                            ((UserHandle) intent.getParcelableExtra(
                                    Intent.EXTRA_USER)).getIdentifier(),
                            intent.getIntExtra(EXTRA_SENSOR, UNKNOWN), false);
                }
            }, new IntentFilter(ACTION_DISABLE_INDIVIDUAL_SENSOR_PRIVACY),
                    MANAGE_SENSOR_PRIVACY, null);
            mUserManagerInternal.addUserRestrictionsListener(this);
        }

        @Override
        public void onUserRestrictionsChanged(int userId, Bundle newRestrictions,
                Bundle prevRestrictions) {
            // Reset sensor privacy when restriction is added
            if (!prevRestrictions.getBoolean(UserManager.DISALLOW_CAMERA_TOGGLE)
                    && newRestrictions.getBoolean(UserManager.DISALLOW_CAMERA_TOGGLE)) {
                setIndividualSensorPrivacyUnchecked(userId, CAMERA, false);
            }
            if (!prevRestrictions.getBoolean(UserManager.DISALLOW_MICROPHONE_TOGGLE)
                    && newRestrictions.getBoolean(UserManager.DISALLOW_MICROPHONE_TOGGLE)) {
                setIndividualSensorPrivacyUnchecked(userId, MICROPHONE, false);
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
                if (code == OP_RECORD_AUDIO) {
                    sensor = MICROPHONE;
                } else if (code == OP_CAMERA) {
                    sensor = CAMERA;
                } else {
                    return;
                }
            } else if (result == MODE_ALLOWED) {
                if (code == OP_PHONE_CALL_MICROPHONE) {
                    sensor = MICROPHONE;
                } else if (code == OP_PHONE_CALL_CAMERA) {
                    sensor = CAMERA;
                } else {
                    return;
                }
            } else {
                return;
            }

            long token = Binder.clearCallingIdentity();
            try {
                onSensorUseStarted(uid, packageName, sensor);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        /**
         * Called when a sensor protected by individual sensor privacy is attempting to get used.
         *
         * @param uid The uid of the app using the sensor
         * @param packageName The package name of the app using the sensor
         * @param sensor The sensor that is attempting to be used
         */
        private void onSensorUseStarted(int uid, String packageName, int sensor) {
            UserHandle user = UserHandle.getUserHandleForUid(uid);
            if (!isIndividualSensorPrivacyEnabled(user.getIdentifier(), sensor)) {
                return;
            }

            synchronized (mLock) {
                if (mSuppressReminders.containsKey(new Pair<>(packageName, user))) {
                    Log.d(TAG,
                            "Suppressed sensor privacy reminder for " + packageName + "/" + user);
                    return;
                }
            }

            if (uid == Process.SYSTEM_UID) {
                enqueueSensorUseReminderDialogAsync(-1, user, packageName, sensor);
                return;
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

                if (task.isVisible && task.topActivity.getPackageName().equals(packageName)) {
                    if (task.isFocused) {
                        // There is the one focused activity
                        enqueueSensorUseReminderDialogAsync(task.taskId, user, packageName, sensor);
                        return;
                    }

                    tasksOfPackageUsingSensor.add(task);
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

            String inputMethodComponent = Settings.Secure.getString(mContext.getContentResolver(),
                    Settings.Secure.DEFAULT_INPUT_METHOD);
            String inputMethodPackageName = null;
            if (inputMethodComponent != null) {
                inputMethodPackageName = ComponentName.unflattenFromString(
                        inputMethodComponent).getPackageName();
            }
            int capability = mActivityManagerInternal.getUidCapability(uid);

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
                    this:: enqueueSensorUseReminderDialog, taskId, user, packageName, sensor));
        }

        private void enqueueSensorUseReminderDialog(int taskId, @NonNull UserHandle user,
                @NonNull String packageName, int sensor) {
            SensorUseReminderDialogInfo info =
                    new SensorUseReminderDialogInfo(taskId, user, packageName);
            if (!mQueuedSensorUseReminderDialogs.containsKey(info)) {
                ArraySet<Integer> sensors = new ArraySet<Integer>();
                sensors.add(sensor);
                mQueuedSensorUseReminderDialogs.put(info, sensors);
                mHandler.sendMessageDelayed(
                        PooledLambda.obtainMessage(this::showSensorUserReminderDialog, info),
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
            dialogIntent.setComponent(ComponentName.unflattenFromString(
                    mContext.getResources().getString(
                            R.string.config_sensorUseStartedActivity)));

            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchTaskId(info.mTaskId);
            options.setTaskOverlay(true, true);

            dialogIntent.addFlags(FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);

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
            mContext.startActivityAsUser(dialogIntent, options.toBundle(), info.mUser);
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
            } else {
                iconRes = R.drawable.ic_camera_blocked;
                messageRes = R.string.sensor_privacy_start_use_camera_notification_content_title;
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
            notificationManager.notify(sensor,
                    new Notification.Builder(mContext, SENSOR_PRIVACY_CHANNEL_ID)
                            .setContentTitle(getUiContext().getString(messageRes))
                            .setContentText(Html.fromHtml(getUiContext().getString(
                                    R.string.sensor_privacy_start_use_notification_content_text,
                                    packageLabel),0))
                            .setSmallIcon(icon)
                            .addAction(new Notification.Action.Builder(icon,
                                    getUiContext().getString(
                                            R.string.sensor_privacy_start_use_dialog_turn_on_button),
                                    PendingIntent.getBroadcast(mContext, sensor,
                                            new Intent(ACTION_DISABLE_INDIVIDUAL_SENSOR_PRIVACY)
                                                    .setPackage(mContext.getPackageName())
                                                    .putExtra(EXTRA_SENSOR, sensor)
                                                    .putExtra(Intent.EXTRA_USER, user),
                                            PendingIntent.FLAG_IMMUTABLE
                                                    | PendingIntent.FLAG_UPDATE_CURRENT))
                                    .build())
                            .setContentIntent(PendingIntent.getActivity(mContext, sensor,
                                    new Intent(Settings.ACTION_PRIVACY_SETTINGS),
                                    PendingIntent.FLAG_IMMUTABLE
                                            | PendingIntent.FLAG_UPDATE_CURRENT))
                            .build());
        }

        /**
         * Sets the sensor privacy to the provided state and notifies all listeners of the new
         * state.
         */
        @Override
        public void setSensorPrivacy(boolean enable) {
            enforceManageSensorPrivacyPermission();
            // Keep the state consistent between all users to make it a single global state
            forAllUsers(userId -> setSensorPrivacy(userId, enable));
        }

        private void setSensorPrivacy(@UserIdInt int userId, boolean enable) {
            synchronized (mLock) {
                mEnabled.put(userId, enable);
                persistSensorPrivacyStateLocked();
            }
            mHandler.onSensorPrivacyChanged(enable);
        }

        @Override
        public void setIndividualSensorPrivacy(@UserIdInt int userId, int sensor, boolean enable) {
            enforceManageSensorPrivacyPermission();
            if (!canChangeIndividualSensorPrivacy(userId, sensor)) {
                return;
            }

            setIndividualSensorPrivacyUnchecked(userId, sensor, enable);
        }

        private void setIndividualSensorPrivacyUnchecked(int userId, int sensor, boolean enable) {
            synchronized (mLock) {
                SparseBooleanArray userIndividualEnabled = mIndividualEnabled.get(userId,
                        new SparseBooleanArray());
                userIndividualEnabled.put(sensor, enable);
                mIndividualEnabled.put(userId, userIndividualEnabled);

                if (!enable) {
                    long token = Binder.clearCallingIdentity();
                    try {
                        // Remove any notifications prompting the user to disable sensory privacy
                        NotificationManager notificationManager =
                                mContext.getSystemService(NotificationManager.class);

                        notificationManager.cancel(sensor);
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
                persistSensorPrivacyState();
            }
            mHandler.onSensorPrivacyChanged(userId, sensor, enable);
        }

        private boolean canChangeIndividualSensorPrivacy(@UserIdInt int userId, int sensor) {
            if (sensor == MICROPHONE && mEmergencyCallHelper.isInEmergencyCall()) {
                // During emergency call the microphone toggle managed automatically
                Log.i(TAG, "Can't change mic toggle during an emergency call");
                return false;
            }

            if (mKeyguardManager != null && mKeyguardManager.isDeviceLocked(userId)) {
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

        @Override
        public void setIndividualSensorPrivacyForProfileGroup(@UserIdInt int userId, int sensor,
                boolean enable) {
            enforceManageSensorPrivacyPermission();
            int parentId = mUserManagerInternal.getProfileParentId(userId);
            forAllUsers(userId2 -> {
                if (parentId == mUserManagerInternal.getProfileParentId(userId2)) {
                    setIndividualSensorPrivacy(userId2, sensor, enable);
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
            return isSensorPrivacyEnabled(USER_SYSTEM);
        }

        private boolean isSensorPrivacyEnabled(@UserIdInt int userId) {
            synchronized (mLock) {
                return mEnabled.get(userId, false);
            }
        }

        @Override
        public boolean isIndividualSensorPrivacyEnabled(@UserIdInt int userId, int sensor) {
            enforceObserveSensorPrivacyPermission();
            synchronized (mLock) {
                SparseBooleanArray states = mIndividualEnabled.get(userId);
                if (states == null) {
                    return false;
                }
                return states.get(sensor, false);
            }
        }

        /**
         * Returns the state of sensor privacy from persistent storage.
         */
        private boolean readPersistedSensorPrivacyStateLocked() {
            // if the file does not exist then sensor privacy has not yet been enabled on
            // the device.

            SparseArray<Object> map = new SparseArray<>();
            int version = -1;

            if (mAtomicFile.exists()) {
                try (FileInputStream inputStream = mAtomicFile.openRead()) {
                    TypedXmlPullParser parser = Xml.resolvePullParser(inputStream);
                    XmlUtils.beginDocument(parser, XML_TAG_SENSOR_PRIVACY);
                    final int persistenceVersion = parser.getAttributeInt(null,
                            XML_ATTRIBUTE_PERSISTENCE_VERSION, 0);

                    // Use inline string literals for xml tags/attrs when parsing old versions since
                    // these should never be changed even with refactorings.
                    if (persistenceVersion == 0) {
                        boolean enabled = parser.getAttributeBoolean(null, "enabled", false);
                        SparseBooleanArray individualEnabled = new SparseBooleanArray();
                        version = 0;

                        XmlUtils.nextElement(parser);
                        while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {
                            String tagName = parser.getName();
                            if ("individual-sensor-privacy".equals(tagName)) {
                                int sensor = XmlUtils.readIntAttribute(parser, "sensor");
                                boolean indEnabled = XmlUtils.readBooleanAttribute(parser,
                                        "enabled");
                                individualEnabled.put(sensor, indEnabled);
                                XmlUtils.skipCurrentTag(parser);
                            } else {
                                XmlUtils.nextElement(parser);
                            }
                        }
                        map.put(VER0_ENABLED, enabled);
                        map.put(VER0_INDIVIDUAL_ENABLED, individualEnabled);
                    } else if (persistenceVersion == CURRENT_PERSISTENCE_VERSION) {
                        SparseBooleanArray enabled = new SparseBooleanArray();
                        SparseArray<SparseBooleanArray> individualEnabled = new SparseArray<>();
                        version = parser.getAttributeInt(null,
                                XML_ATTRIBUTE_VERSION, 1);

                        int currentUserId = -1;
                        while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {
                            XmlUtils.nextElement(parser);
                            String tagName = parser.getName();
                            if (XML_TAG_USER.equals(tagName)) {
                                currentUserId = parser.getAttributeInt(null, XML_ATTRIBUTE_ID);
                                boolean isEnabled = parser.getAttributeBoolean(null,
                                        XML_ATTRIBUTE_ENABLED);
                                if (enabled.indexOfKey(currentUserId) >= 0) {
                                    Log.e(TAG, "User listed multiple times in file.",
                                            new RuntimeException());
                                    mAtomicFile.delete();
                                    version = -1;
                                    break;
                                }

                                if (mUserManagerInternal.getUserInfo(currentUserId) == null) {
                                    // User may no longer exist, skip this user
                                    currentUserId = -1;
                                    continue;
                                }

                                enabled.put(currentUserId, isEnabled);
                            }
                            if (XML_TAG_INDIVIDUAL_SENSOR_PRIVACY.equals(tagName)) {
                                if (mUserManagerInternal.getUserInfo(currentUserId) == null) {
                                    // User may no longer exist or isn't set
                                    continue;
                                }
                                int sensor = parser.getAttributeInt(null, XML_ATTRIBUTE_SENSOR);
                                boolean isEnabled = parser.getAttributeBoolean(null,
                                        XML_ATTRIBUTE_ENABLED);
                                SparseBooleanArray userIndividualEnabled = individualEnabled.get(
                                        currentUserId, new SparseBooleanArray());

                                userIndividualEnabled.put(sensor, isEnabled);
                                individualEnabled.put(currentUserId, userIndividualEnabled);
                            }
                        }

                        map.put(VER1_ENABLED, enabled);
                        map.put(VER1_INDIVIDUAL_ENABLED, individualEnabled);
                    } else {
                        Log.e(TAG, "Unknown persistence version: " + persistenceVersion
                                        + ". Deleting.",
                                new RuntimeException());
                        mAtomicFile.delete();
                        version = -1;
                    }

                } catch (IOException | XmlPullParserException e) {
                    Log.e(TAG, "Caught an exception reading the state from storage: ", e);
                    // Delete the file to prevent the same error on subsequent calls and assume
                    // sensor privacy is not enabled.
                    mAtomicFile.delete();
                    version = -1;
                }
            }

            return upgradeAndInit(version, map);
        }

        private boolean upgradeAndInit(int version, SparseArray map) {
            if (version == -1) {
                // New file, default state for current version goes here.
                mEnabled = new SparseBooleanArray();
                mIndividualEnabled = new SparseArray<>();
                forAllUsers(userId -> mEnabled.put(userId, false));
                forAllUsers(userId -> mIndividualEnabled.put(userId, new SparseBooleanArray()));
                return true;
            }
            boolean upgraded = false;
            final int[] users = getLocalService(UserManagerInternal.class).getUserIds();
            if (version == 0) {
                final boolean enabled = (boolean) map.get(VER0_ENABLED);
                final SparseBooleanArray individualEnabled =
                        (SparseBooleanArray) map.get(VER0_INDIVIDUAL_ENABLED);

                final SparseBooleanArray perUserEnabled = new SparseBooleanArray();
                final SparseArray<SparseBooleanArray> perUserIndividualEnabled =
                        new SparseArray<>();

                // Copy global state to each user
                for (int i = 0; i < users.length; i++) {
                    int user = users[i];
                    perUserEnabled.put(user, enabled);
                    SparseBooleanArray userIndividualSensorEnabled = new SparseBooleanArray();
                    perUserIndividualEnabled.put(user, userIndividualSensorEnabled);
                    for (int j = 0; j < individualEnabled.size(); j++) {
                        final int sensor = individualEnabled.keyAt(j);
                        final boolean isSensorEnabled = individualEnabled.valueAt(j);
                        userIndividualSensorEnabled.put(sensor, isSensorEnabled);
                    }
                }

                map.clear();
                map.put(VER1_ENABLED, perUserEnabled);
                map.put(VER1_INDIVIDUAL_ENABLED, perUserIndividualEnabled);

                version = 1;
                upgraded = true;
            }
            if (version == CURRENT_VERSION) {
                mEnabled = (SparseBooleanArray) map.get(VER1_ENABLED);
                mIndividualEnabled =
                        (SparseArray<SparseBooleanArray>) map.get(VER1_INDIVIDUAL_ENABLED);
            }
            return upgraded;
        }

        /**
         * Persists the state of sensor privacy.
         */
        private void persistSensorPrivacyState() {
            synchronized (mLock) {
                persistSensorPrivacyStateLocked();
            }
        }

        private void persistSensorPrivacyStateLocked() {
            FileOutputStream outputStream = null;
            try {
                outputStream = mAtomicFile.startWrite();
                TypedXmlSerializer serializer = Xml.resolveSerializer(outputStream);
                serializer.startDocument(null, true);
                serializer.startTag(null, XML_TAG_SENSOR_PRIVACY);
                serializer.attributeInt(
                        null, XML_ATTRIBUTE_PERSISTENCE_VERSION, CURRENT_PERSISTENCE_VERSION);
                serializer.attributeInt(null, XML_ATTRIBUTE_VERSION, CURRENT_VERSION);
                forAllUsers(userId -> {
                    serializer.startTag(null, XML_TAG_USER);
                    serializer.attributeInt(null, XML_ATTRIBUTE_ID, userId);
                    serializer.attributeBoolean(
                            null, XML_ATTRIBUTE_ENABLED, isSensorPrivacyEnabled(userId));

                    SparseBooleanArray individualEnabled =
                            mIndividualEnabled.get(userId, new SparseBooleanArray());
                    int numIndividual = individualEnabled.size();
                    for (int i = 0; i < numIndividual; i++) {
                        serializer.startTag(null, XML_TAG_INDIVIDUAL_SENSOR_PRIVACY);
                        int sensor = individualEnabled.keyAt(i);
                        boolean enabled = individualEnabled.valueAt(i);
                        serializer.attributeInt(null, XML_ATTRIBUTE_SENSOR, sensor);
                        serializer.attributeBoolean(null, XML_ATTRIBUTE_ENABLED, enabled);
                        serializer.endTag(null, XML_TAG_INDIVIDUAL_SENSOR_PRIVACY);
                    }
                    serializer.endTag(null, XML_TAG_USER);

                });
                serializer.endTag(null, XML_TAG_SENSOR_PRIVACY);
                serializer.endDocument();
                mAtomicFile.finishWrite(outputStream);
            } catch (IOException e) {
                Log.e(TAG, "Caught an exception persisting the sensor privacy state: ", e);
                mAtomicFile.failWrite(outputStream);
            }
        }

        @Override
        public boolean supportsSensorToggle(int sensor) {
            if (sensor == MICROPHONE) {
                return mContext.getResources().getBoolean(R.bool.config_supportsMicToggle);
            } else if (sensor == CAMERA) {
                return mContext.getResources().getBoolean(R.bool.config_supportsCamToggle);
            }
            throw new IllegalArgumentException("Unable to find value " + sensor);
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
        public void addIndividualSensorPrivacyListener(int userId, int sensor,
                ISensorPrivacyListener listener) {
            enforceObserveSensorPrivacyPermission();
            if (listener == null) {
                throw new NullPointerException("listener cannot be null");
            }
            mHandler.addListener(userId, sensor, listener);
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
        public void removeIndividualSensorPrivacyListener(int sensor,
                ISensorPrivacyListener listener) {
            enforceObserveSensorPrivacyPermission();
            if (listener == null) {
                throw new NullPointerException("listener cannot be null");
            }
            mHandler.removeListener(sensor, listener);
        }

        @Override
        public void suppressIndividualSensorPrivacyReminders(int userId, String packageName,
                IBinder token, boolean suppress) {
            enforceManageSensorPrivacyPermission();
            Objects.requireNonNull(packageName);
            Objects.requireNonNull(token);

            Pair<String, UserHandle> key = new Pair<>(packageName, UserHandle.of(userId));

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

        /**
         * Remove a sensor use reminder suppression token.
         *
         * @param key Key the token is in
         * @param token The token to remove
         */
        private void removeSuppressPackageReminderToken(@NonNull Pair<String, UserHandle> key,
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
                for (Pair<String, UserHandle> key : mSuppressReminders.keySet()) {
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
                    dump(new DualDumpOutputStream(new ProtoOutputStream(fd)));
                } else {
                    pw.println("SENSOR PRIVACY MANAGER STATE (dumpsys "
                            + Context.SENSOR_PRIVACY_SERVICE + ")");

                    dump(new DualDumpOutputStream(new IndentingPrintWriter(pw, "  ")));
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        /**
         * Dump state to {@link DualDumpOutputStream}.
         *
         * @param dumpStream The destination to dump to
         */
        private void dump(@NonNull DualDumpOutputStream dumpStream) {
            synchronized (mLock) {

                forAllUsers(userId -> {
                    long userToken = dumpStream.start("users", SensorPrivacyServiceDumpProto.USER);
                    dumpStream.write("user_id", SensorPrivacyUserProto.USER_ID, userId);
                    dumpStream.write("is_enabled", SensorPrivacyUserProto.IS_ENABLED,
                            mEnabled.get(userId, false));

                    SparseBooleanArray individualEnabled = mIndividualEnabled.get(userId);
                    if (individualEnabled != null) {
                        int numIndividualEnabled = individualEnabled.size();
                        for (int i = 0; i < numIndividualEnabled; i++) {
                            long individualToken = dumpStream.start("individual_enabled_sensor",
                                    SensorPrivacyUserProto.INDIVIDUAL_ENABLED_SENSOR);

                            dumpStream.write("sensor",
                                    SensorPrivacyIndividualEnabledSensorProto.SENSOR,
                                    individualEnabled.keyAt(i));
                            dumpStream.write("is_enabled",
                                    SensorPrivacyIndividualEnabledSensorProto.IS_ENABLED,
                                    individualEnabled.valueAt(i));

                            dumpStream.end(individualToken);
                        }
                    }
                    dumpStream.end(userToken);
                });
            }

            dumpStream.flush();
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

                            setIndividualSensorPrivacy(userId, sensor, true);
                        }
                        break;
                        case "disable" : {
                            int sensor = sensorStrToId(getNextArgRequired());
                            if (sensor == UNKNOWN) {
                                pw.println("Invalid sensor");
                                return -1;
                            }

                            setIndividualSensorPrivacy(userId, sensor, false);
                        }
                        break;
                        case "reset": {
                            int sensor = sensorStrToId(getNextArgRequired());
                            if (sensor == UNKNOWN) {
                                pw.println("Invalid sensor");
                                return -1;
                            }

                            enforceManageSensorPrivacyPermission();

                            synchronized (mLock) {
                                SparseBooleanArray individualEnabled =
                                        mIndividualEnabled.get(userId);
                                if (individualEnabled != null) {
                                    individualEnabled.delete(sensor);
                                }
                                persistSensorPrivacyState();
                            }
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
                    pw.println("  reset USER_ID SENSOR");
                    pw.println("    Reset privacy state for a certain sensor.");
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
        private final SparseArray<SparseArray<RemoteCallbackList<ISensorPrivacyListener>>>
                mIndividualSensorListeners = new SparseArray<>();
        private final ArrayMap<ISensorPrivacyListener, DeathRecipient> mDeathRecipients;
        private final Context mContext;

        SensorPrivacyHandler(Looper looper, Context context) {
            super(looper);
            mDeathRecipients = new ArrayMap<>();
            mContext = context;
        }

        public void onSensorPrivacyChanged(boolean enabled) {
            sendMessage(PooledLambda.obtainMessage(SensorPrivacyHandler::handleSensorPrivacyChanged,
                    this, enabled));
            sendMessage(
                    PooledLambda.obtainMessage(SensorPrivacyServiceImpl::persistSensorPrivacyState,
                            mSensorPrivacyServiceImpl));
        }

        public void onSensorPrivacyChanged(int userId, int sensor, boolean enabled) {
            sendMessage(PooledLambda.obtainMessage(SensorPrivacyHandler::handleSensorPrivacyChanged,
                    this, userId, sensor, enabled));
            sendMessage(
                    PooledLambda.obtainMessage(SensorPrivacyServiceImpl::persistSensorPrivacyState,
                            mSensorPrivacyServiceImpl));
        }

        public void addListener(ISensorPrivacyListener listener) {
            synchronized (mListenerLock) {
                DeathRecipient deathRecipient = new DeathRecipient(listener);
                mDeathRecipients.put(listener, deathRecipient);
                mListeners.register(listener);
            }
        }

        public void addListener(int userId, int sensor, ISensorPrivacyListener listener) {
            synchronized (mListenerLock) {
                DeathRecipient deathRecipient = new DeathRecipient(listener);
                mDeathRecipients.put(listener, deathRecipient);
                SparseArray<RemoteCallbackList<ISensorPrivacyListener>> listenersForUser =
                        mIndividualSensorListeners.get(userId);
                if (listenersForUser == null) {
                    listenersForUser = new SparseArray<>();
                    mIndividualSensorListeners.put(userId, listenersForUser);
                }
                RemoteCallbackList<ISensorPrivacyListener> listeners = listenersForUser.get(sensor);
                if (listeners == null) {
                    listeners = new RemoteCallbackList<>();
                    listenersForUser.put(sensor, listeners);
                }
                listeners.register(listener);
            }
        }

        public void removeListener(ISensorPrivacyListener listener) {
            synchronized (mListenerLock) {
                DeathRecipient deathRecipient = mDeathRecipients.remove(listener);
                if (deathRecipient != null) {
                    deathRecipient.destroy();
                }
                mListeners.unregister(listener);
            }
        }

        public void removeListener(int sensor, ISensorPrivacyListener listener) {
            synchronized (mListenerLock) {
                DeathRecipient deathRecipient = mDeathRecipients.remove(listener);
                if (deathRecipient != null) {
                    deathRecipient.destroy();
                }

                for (int i = 0, numUsers = mIndividualSensorListeners.size(); i < numUsers; i++) {
                    RemoteCallbackList callbacks =
                            mIndividualSensorListeners.valueAt(i).get(sensor);
                    if (callbacks != null) {
                        callbacks.unregister(listener);
                    }
                }
            }
        }

        public void handleSensorPrivacyChanged(boolean enabled) {
            final int count = mListeners.beginBroadcast();
            for (int i = 0; i < count; i++) {
                ISensorPrivacyListener listener = mListeners.getBroadcastItem(i);
                try {
                    listener.onSensorPrivacyChanged(enabled);
                } catch (RemoteException e) {
                    Log.e(TAG, "Caught an exception notifying listener " + listener + ": ", e);
                }
            }
            mListeners.finishBroadcast();
        }

        public void handleSensorPrivacyChanged(int userId, int sensor, boolean enabled) {
            mSensorPrivacyManagerInternal.dispatch(userId, sensor, enabled);
            SparseArray<RemoteCallbackList<ISensorPrivacyListener>> listenersForUser =
                    mIndividualSensorListeners.get(userId);

            setUserRestriction(userId, sensor, enabled);

            if (listenersForUser == null) {
                return;
            }
            RemoteCallbackList<ISensorPrivacyListener> listeners = listenersForUser.get(sensor);
            if (listeners == null) {
                return;
            }
            final int count = listeners.beginBroadcast();
            for (int i = 0; i < count; i++) {
                ISensorPrivacyListener listener = listeners.getBroadcastItem(i);
                try {
                    listener.onSensorPrivacyChanged(enabled);
                } catch (RemoteException e) {
                    Log.e(TAG, "Caught an exception notifying listener " + listener + ": ", e);
                }
            }
            listeners.finishBroadcast();
        }

        public void removeSuppressPackageReminderToken(Pair<String, UserHandle> key,
                IBinder token) {
            sendMessage(PooledLambda.obtainMessage(
                    SensorPrivacyServiceImpl::removeSuppressPackageReminderToken,
                    mSensorPrivacyServiceImpl, key, token));
        }
    }

    private void setUserRestriction(int userId, int sensor, boolean enabled) {
        if (sensor == CAMERA) {
            mAppOpsManager.setUserRestrictionForUser(OP_CAMERA, enabled,
                    mAppOpsRestrictionToken, null, userId);
        } else if (sensor == MICROPHONE) {
            mAppOpsManager.setUserRestrictionForUser(OP_RECORD_AUDIO, enabled,
                    mAppOpsRestrictionToken, null, userId);
            mAppOpsManager.setUserRestrictionForUser(OP_RECORD_AUDIO_HOTWORD, enabled,
                    mAppOpsRestrictionToken, null, userId);
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
                    .mSensorPrivacyServiceImpl.isIndividualSensorPrivacyEnabled(userId, sensor);
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
    }

    private class EmergencyCallHelper {
        private OutogingEmergencyStateCallback mEmergencyStateCallback;
        private CallStateCallback mCallStateCallback;

        private boolean mIsInEmergencyCall;
        private boolean mMicUnmutedForEmergencyCall;

        private Object mEmergencyStateLock = new Object();

        EmergencyCallHelper() {
            mEmergencyStateCallback = new OutogingEmergencyStateCallback();
            mCallStateCallback = new CallStateCallback();

            mTelephonyManager.registerTelephonyCallback(FgThread.getExecutor(),
                    mEmergencyStateCallback);
            mTelephonyManager.registerTelephonyCallback(FgThread.getExecutor(),
                    mCallStateCallback);
        }

        boolean isInEmergencyCall() {
            synchronized (mEmergencyStateLock) {
                return mIsInEmergencyCall;
            }
        }

        private class OutogingEmergencyStateCallback extends TelephonyCallback implements
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
                }
            }
        }

        private void onEmergencyCall() {
            synchronized (mEmergencyStateLock) {
                if (!mIsInEmergencyCall) {
                    mIsInEmergencyCall = true;
                    if (mSensorPrivacyServiceImpl
                            .isIndividualSensorPrivacyEnabled(getCurrentUser(), MICROPHONE)) {
                        mSensorPrivacyServiceImpl.setIndividualSensorPrivacyUnchecked(
                                getCurrentUser(), MICROPHONE, false);
                        mMicUnmutedForEmergencyCall = true;
                    } else {
                        mMicUnmutedForEmergencyCall = false;
                    }
                }
            }
        }

        private void onCallOver() {
            synchronized (mEmergencyStateLock) {
                if (mIsInEmergencyCall) {
                    mIsInEmergencyCall = false;
                    if (mMicUnmutedForEmergencyCall) {
                        mSensorPrivacyServiceImpl.setIndividualSensorPrivacyUnchecked(
                                getCurrentUser(), MICROPHONE, true);
                        mMicUnmutedForEmergencyCall = false;
                    }
                }
            }
        }
    }
}
