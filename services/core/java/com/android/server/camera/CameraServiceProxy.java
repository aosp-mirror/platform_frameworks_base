/*
 * Copyright 2015 The Android Open Source Project
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
package com.android.server.camera;

import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.os.Build.VERSION_CODES.M;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.TaskStackListener;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.hardware.CameraSessionStats;
import android.hardware.CameraStreamStats;
import android.hardware.ICameraService;
import android.hardware.ICameraServiceProxy;
import android.hardware.camera2.CameraMetadata;
import android.hardware.display.DisplayManager;
import android.media.AudioManager;
import android.metrics.LogMaker;
import android.nfc.INfcAdapter;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserManager;
import android.stats.camera.nano.CameraProtos.CameraStreamProto;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;
import android.view.Display;
import android.view.Surface;

import com.android.internal.annotations.GuardedBy;
import com.android.framework.protobuf.nano.MessageNano;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.SystemService;
import com.android.server.SystemService.TargetUser;
import com.android.server.wm.WindowManagerInternal;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * CameraServiceProxy is the system_server analog to the camera service running in cameraserver.
 *
 * @hide
 */
public class CameraServiceProxy extends SystemService
        implements Handler.Callback, IBinder.DeathRecipient {
    private static final String TAG = "CameraService_proxy";
    private static final boolean DEBUG = false;

    /**
     * This must match the ICameraService.aidl definition
     */
    private static final String CAMERA_SERVICE_BINDER_NAME = "media.camera";

    public static final String CAMERA_SERVICE_PROXY_BINDER_NAME = "media.camera.proxy";

    // Flags arguments to NFC adapter to enable/disable NFC
    public static final int DISABLE_POLLING_FLAGS = 0x1000;
    public static final int ENABLE_POLLING_FLAGS = 0x0000;

    // Handler message codes
    private static final int MSG_SWITCH_USER = 1;
    private static final int MSG_NOTIFY_DEVICE_STATE = 2;

    private static final int RETRY_DELAY_TIME = 20; //ms
    private static final int RETRY_TIMES = 60;

    @IntDef(flag = true, prefix = { "DEVICE_STATE_" }, value = {
            ICameraService.DEVICE_STATE_BACK_COVERED,
            ICameraService.DEVICE_STATE_FRONT_COVERED,
            ICameraService.DEVICE_STATE_FOLDED
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface DeviceStateFlags {}

    // Maximum entries to keep in usage history before dumping out
    private static final int MAX_USAGE_HISTORY = 20;
    // Number of stream statistics being dumped for each camera session
    // Must be equal to number of CameraStreamProto in CameraActionEvent
    private static final int MAX_STREAM_STATISTICS = 5;

    private final Context mContext;
    private final ServiceThread mHandlerThread;
    private final Handler mHandler;
    private UserManager mUserManager;

    private final Object mLock = new Object();
    private Set<Integer> mEnabledCameraUsers;
    private int mLastUser;
    // The current set of device state flags. May be different from mLastReportedDeviceState if the
    // native camera service has not been notified of the change.
    @GuardedBy("mLock")
    @DeviceStateFlags
    private int mDeviceState;
    // The most recent device state flags reported to the native camera server.
    @GuardedBy("mLock")
    @DeviceStateFlags
    private int mLastReportedDeviceState;

    private ICameraService mCameraServiceRaw;

    // Map of currently active camera IDs
    private final ArrayMap<String, CameraUsageEvent> mActiveCameraUsage = new ArrayMap<>();
    private final List<CameraUsageEvent> mCameraUsageHistory = new ArrayList<>();

    private static final String NFC_NOTIFICATION_PROP = "ro.camera.notify_nfc";
    private static final String NFC_SERVICE_BINDER_NAME = "nfc";
    private static final IBinder nfcInterfaceToken = new Binder();

    private final boolean mNotifyNfc;

    private ScheduledThreadPoolExecutor mLogWriterService = new ScheduledThreadPoolExecutor(
            /*corePoolSize*/ 1);

    /**
     * Structure to track camera usage
     */
    private static class CameraUsageEvent {
        public final String mCameraId;
        public final int mCameraFacing;
        public final String mClientName;
        public final int mAPILevel;
        public final boolean mIsNdk;
        public final int mAction;
        public final int mLatencyMs;
        public final int mOperatingMode;

        private boolean mCompleted;
        public int mInternalReconfigure;
        public long mRequestCount;
        public long mResultErrorCount;
        public boolean mDeviceError;
        public List<CameraStreamStats> mStreamStats;
        private long mDurationOrStartTimeMs;  // Either start time, or duration once completed

        CameraUsageEvent(String cameraId, int facing, String clientName, int apiLevel,
                boolean isNdk, int action, int latencyMs, int operatingMode) {
            mCameraId = cameraId;
            mCameraFacing = facing;
            mClientName = clientName;
            mAPILevel = apiLevel;
            mDurationOrStartTimeMs = SystemClock.elapsedRealtime();
            mCompleted = false;
            mIsNdk = isNdk;
            mAction = action;
            mLatencyMs = latencyMs;
            mOperatingMode = operatingMode;
        }

        public void markCompleted(int internalReconfigure, long requestCount,
                long resultErrorCount, boolean deviceError,
                List<CameraStreamStats>  streamStats) {
            if (mCompleted) {
                return;
            }
            mCompleted = true;
            mDurationOrStartTimeMs = SystemClock.elapsedRealtime() - mDurationOrStartTimeMs;
            mInternalReconfigure = internalReconfigure;
            mRequestCount = requestCount;
            mResultErrorCount = resultErrorCount;
            mDeviceError = deviceError;
            mStreamStats = streamStats;
            if (CameraServiceProxy.DEBUG) {
                Slog.v(TAG, "A camera facing " + cameraFacingToString(mCameraFacing) +
                        " was in use by " + mClientName + " for " +
                        mDurationOrStartTimeMs + " ms");
            }
        }

        /**
         * Return duration of camera usage event, or 0 if the event is not done
         */
        public long getDuration() {
            return mCompleted ? mDurationOrStartTimeMs : 0;
        }
    }

    private final TaskStateHandler mTaskStackListener = new TaskStateHandler();

    private final class TaskInfo {
        private int frontTaskId;
        private boolean isResizeable;
        private boolean isFixedOrientationLandscape;
        private boolean isFixedOrientationPortrait;
        private int displayId;
    }

    private final class TaskStateHandler extends TaskStackListener {
        private final Object mMapLock = new Object();

        // maps the current top level task id to its corresponding package name
        @GuardedBy("mMapLock")
        private final ArrayMap<String, TaskInfo> mTaskInfoMap = new ArrayMap<>();

        @Override
        public void onTaskMovedToFront(ActivityManager.RunningTaskInfo taskInfo)
                throws RemoteException {
            synchronized (mMapLock) {
                TaskInfo info = new TaskInfo();
                info.frontTaskId = taskInfo.taskId;
                info.isResizeable = taskInfo.isResizeable;
                info.displayId = taskInfo.displayId;
                info.isFixedOrientationLandscape = ActivityInfo.isFixedOrientationLandscape(
                        taskInfo.topActivityInfo.screenOrientation);
                info.isFixedOrientationPortrait = ActivityInfo.isFixedOrientationPortrait(
                        taskInfo.topActivityInfo.screenOrientation);
                mTaskInfoMap.put(taskInfo.topActivityInfo.packageName, info);
            }
        }

        @Override
        public void onTaskRemoved(int taskId) throws RemoteException {
            synchronized (mMapLock) {
                for (Map.Entry<String, TaskInfo> entry : mTaskInfoMap.entrySet()){
                    if (entry.getValue().frontTaskId == taskId) {
                        mTaskInfoMap.remove(entry.getKey());
                        break;
                    }
                }
            }
        }

        public @Nullable TaskInfo getFrontTaskInfo(String packageName) {
            synchronized (mMapLock) {
                if (mTaskInfoMap.containsKey(packageName)) {
                    return mTaskInfoMap.get(packageName);
                }
            }

            Log.e(TAG, "Top task with package name: " + packageName + " not found!");
            return null;
        }
    };

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action == null) return;

            switch (action) {
                case Intent.ACTION_USER_ADDED:
                case Intent.ACTION_USER_REMOVED:
                case Intent.ACTION_USER_INFO_CHANGED:
                case Intent.ACTION_MANAGED_PROFILE_ADDED:
                case Intent.ACTION_MANAGED_PROFILE_REMOVED:
                    synchronized(mLock) {
                        // Return immediately if we haven't seen any users start yet
                        if (mEnabledCameraUsers == null) return;
                        switchUserLocked(mLastUser);
                    }
                    break;
                default:
                    break; // do nothing
            }

        }
    };

    private final ICameraServiceProxy.Stub mCameraServiceProxy = new ICameraServiceProxy.Stub() {
        private boolean isMOrBelow(Context ctx, String packageName) {
            try {
                return ctx.getPackageManager().getPackageInfo(
                        packageName, 0).applicationInfo.targetSdkVersion <= M;
            } catch (PackageManager.NameNotFoundException e) {
                Slog.e(TAG,"Package name not found!");
            }
            return false;
        }

        /**
         * Gets whether crop-rotate-scale is needed.
         */
        private boolean getNeedCropRotateScale(Context ctx, String packageName,
                @Nullable TaskInfo taskInfo, int sensorOrientation, int lensFacing) {
            if (taskInfo == null) {
                return false;
            }

            // External cameras do not need crop-rotate-scale.
            if (lensFacing != CameraMetadata.LENS_FACING_FRONT
                    && lensFacing != CameraMetadata.LENS_FACING_BACK) {
                Log.v(TAG, "lensFacing=" + lensFacing + ". Crop-rotate-scale is disabled.");
                return false;
            }

            // Only enable the crop-rotate-scale workaround if the app targets M or below and is not
            // resizeable.
            if ((ctx != null) && !isMOrBelow(ctx, packageName) && taskInfo.isResizeable) {
                Slog.v(TAG,
                        "The activity is N or above and claims to support resizeable-activity. "
                                + "Crop-rotate-scale is disabled.");
                return false;
            }

            DisplayManager displayManager = ctx.getSystemService(DisplayManager.class);
            Display display = displayManager.getDisplay(taskInfo.displayId);
            int rotation = display.getRotation();
            int rotationDegree = 0;
            switch (rotation) {
                case Surface.ROTATION_0:
                    rotationDegree = 0;
                    break;
                case Surface.ROTATION_90:
                    rotationDegree = 90;
                    break;
                case Surface.ROTATION_180:
                    rotationDegree = 180;
                    break;
                case Surface.ROTATION_270:
                    rotationDegree = 270;
                    break;
            }

            // Here we only need to know whether the camera is landscape or portrait. Therefore we
            // don't need to consider whether it is a front or back camera. The formula works for
            // both.
            boolean landscapeCamera = ((rotationDegree + sensorOrientation) % 180 == 0);
            Slog.v(TAG,
                    "Display.getRotation()=" + rotationDegree
                            + " CameraCharacteristics.SENSOR_ORIENTATION=" + sensorOrientation
                            + " isFixedOrientationPortrait=" + taskInfo.isFixedOrientationPortrait
                            + " isFixedOrientationLandscape=" +
                            taskInfo.isFixedOrientationLandscape);
            // We need to do crop-rotate-scale when camera is landscape and activity is portrait or
            // vice versa.
            if ((taskInfo.isFixedOrientationPortrait && landscapeCamera)
                    || (taskInfo.isFixedOrientationLandscape && !landscapeCamera)) {
                return true;
            } else {
                return false;
            }
        }

        @Override
        public boolean isRotateAndCropOverrideNeeded(String packageName, int sensorOrientation,
                int lensFacing) {
            if (Binder.getCallingUid() != Process.CAMERASERVER_UID) {
                Slog.e(TAG, "Calling UID: " + Binder.getCallingUid() + " doesn't match expected " +
                        " camera service UID!");
                return false;
            }

            // A few remaining todos:
            // 1) Do the same check when working in WM compatible mode. The sequence needs
            //    to be adjusted and use orientation events as triggers for all active camera
            //    clients.
            // 2) Modify the sensor orientation in camera characteristics along with any 3A regions
            //    in capture requests/results to account for thea physical rotation. The former
            //    is somewhat tricky as it assumes that camera clients always check for the current
            //    value by retrieving the camera characteristics from the camera device.
            return getNeedCropRotateScale(mContext, packageName,
                    mTaskStackListener.getFrontTaskInfo(packageName), sensorOrientation,
                    lensFacing);
        }

        @Override
        public void pingForUserUpdate() {
            if (Binder.getCallingUid() != Process.CAMERASERVER_UID) {
                Slog.e(TAG, "Calling UID: " + Binder.getCallingUid() + " doesn't match expected " +
                        " camera service UID!");
                return;
            }
            notifySwitchWithRetries(RETRY_TIMES);
            notifyDeviceStateWithRetries(RETRY_TIMES);
        }

        @Override
        public void notifyCameraState(CameraSessionStats cameraState) {
            if (Binder.getCallingUid() != Process.CAMERASERVER_UID) {
                Slog.e(TAG, "Calling UID: " + Binder.getCallingUid() + " doesn't match expected " +
                        " camera service UID!");
                return;
            }
            String state = cameraStateToString(cameraState.getNewCameraState());
            String facingStr = cameraFacingToString(cameraState.getFacing());
            if (DEBUG) {
                Slog.v(TAG, "Camera " + cameraState.getCameraId()
                        + " facing " + facingStr + " state now " + state
                        + " for client " + cameraState.getClientName()
                        + " API Level " + cameraState.getApiLevel());
            }

            updateActivityCount(cameraState);
        }
    };

    public CameraServiceProxy(Context context) {
        super(context);
        mContext = context;
        mHandlerThread = new ServiceThread(TAG, Process.THREAD_PRIORITY_DISPLAY, /*allowTo*/false);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper(), this);

        mNotifyNfc = SystemProperties.getInt(NFC_NOTIFICATION_PROP, 0) > 0;
        if (DEBUG) Slog.v(TAG, "Notify NFC behavior is " + (mNotifyNfc ? "active" : "disabled"));
        // Don't keep any extra logging threads if not needed
        mLogWriterService.setKeepAliveTime(1, TimeUnit.SECONDS);
        mLogWriterService.allowCoreThreadTimeOut(true);
    }

    /**
     * Sets the device state bits set within {@code deviceStateFlags} leaving all other bits the
     * same.
     * <p>
     * Calling requires permission {@link android.Manifest.permission#CAMERA_SEND_SYSTEM_EVENTS}.
     *
     * @param deviceStateFlags a bitmask of the device state bits that should be set.
     *
     * @see #clearDeviceStateFlags(int)
     */
    public void setDeviceStateFlags(@DeviceStateFlags int deviceStateFlags) {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_NOTIFY_DEVICE_STATE);
            mDeviceState |= deviceStateFlags;
            if (mDeviceState != mLastReportedDeviceState) {
                notifyDeviceStateWithRetriesLocked(RETRY_TIMES);
            }
        }
    }

    /**
     * Clears the device state bits set within {@code deviceStateFlags} leaving all other bits the
     * same.
     * <p>
     * Calling requires permission {@link android.Manifest.permission#CAMERA_SEND_SYSTEM_EVENTS}.
     *
     * @param deviceStateFlags a bitmask of the device state bits that should be cleared.
     *
     * @see #setDeviceStateFlags(int)
     */
    public void clearDeviceStateFlags(@DeviceStateFlags int deviceStateFlags) {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_NOTIFY_DEVICE_STATE);
            mDeviceState &= ~deviceStateFlags;
            if (mDeviceState != mLastReportedDeviceState) {
                notifyDeviceStateWithRetriesLocked(RETRY_TIMES);
            }
        }
    }

    @Override
    public boolean handleMessage(Message msg) {
        switch(msg.what) {
            case MSG_SWITCH_USER: {
                notifySwitchWithRetries(msg.arg1);
            } break;
            case MSG_NOTIFY_DEVICE_STATE: {
                notifyDeviceStateWithRetries(msg.arg1);
            } break;
            default: {
                Slog.e(TAG, "CameraServiceProxy error, invalid message: " + msg.what);
            } break;
        }
        return true;
    }

    @Override
    public void onStart() {
        mUserManager = UserManager.get(mContext);
        if (mUserManager == null) {
            // Should never see this unless someone messes up the SystemServer service boot order.
            throw new IllegalStateException("UserManagerService must start before" +
                    " CameraServiceProxy!");
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_ADDED);
        filter.addAction(Intent.ACTION_USER_REMOVED);
        filter.addAction(Intent.ACTION_USER_INFO_CHANGED);
        filter.addAction(Intent.ACTION_MANAGED_PROFILE_ADDED);
        filter.addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED);
        mContext.registerReceiver(mIntentReceiver, filter);

        publishBinderService(CAMERA_SERVICE_PROXY_BINDER_NAME, mCameraServiceProxy);
        publishLocalService(CameraServiceProxy.class, this);

        try {
            ActivityTaskManager.getService().registerTaskStackListener(mTaskStackListener);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to register task stack listener!");
        }
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_BOOT_COMPLETED) {
            CameraStatsJobService.schedule(mContext);
        }
    }

    @Override
    public void onUserStarting(@NonNull TargetUser user) {
        synchronized(mLock) {
            if (mEnabledCameraUsers == null) {
                // Initialize cameraserver, or update cameraserver if we are recovering
                // from a crash.
                switchUserLocked(user.getUserIdentifier());
            }
        }
    }

    @Override
    public void onUserSwitching(@Nullable TargetUser from, @NonNull TargetUser to) {
        synchronized(mLock) {
            switchUserLocked(to.getUserIdentifier());
        }
    }

    /**
     * Handle the death of the native camera service
     */
    @Override
    public void binderDied() {
        if (DEBUG) Slog.w(TAG, "Native camera service has died");
        synchronized(mLock) {
            mCameraServiceRaw = null;

            // All cameras reset to idle on camera service death
            boolean wasEmpty = mActiveCameraUsage.isEmpty();
            mActiveCameraUsage.clear();

            if ( mNotifyNfc && !wasEmpty ) {
                notifyNfcService(/*enablePolling*/ true);
            }
        }
    }

    private class EventWriterTask implements Runnable {
        private ArrayList<CameraUsageEvent> mEventList;
        private static final long WRITER_SLEEP_MS = 100;

        public EventWriterTask(ArrayList<CameraUsageEvent> eventList) {
            mEventList = eventList;
        }

        @Override
        public void run() {
            if (mEventList != null) {
                for (CameraUsageEvent event : mEventList) {
                    logCameraUsageEvent(event);
                    try {
                        Thread.sleep(WRITER_SLEEP_MS);
                    } catch (InterruptedException e) {}
                }
                mEventList.clear();
            }
        }

        /**
         * Write camera usage events to stats log.
         * Package-private
         */
        private void logCameraUsageEvent(CameraUsageEvent e) {
            int facing = FrameworkStatsLog.CAMERA_ACTION_EVENT__FACING__UNKNOWN;
            switch(e.mCameraFacing) {
                case CameraSessionStats.CAMERA_FACING_BACK:
                    facing = FrameworkStatsLog.CAMERA_ACTION_EVENT__FACING__BACK;
                    break;
                case CameraSessionStats.CAMERA_FACING_FRONT:
                    facing = FrameworkStatsLog.CAMERA_ACTION_EVENT__FACING__FRONT;
                    break;
                case CameraSessionStats.CAMERA_FACING_EXTERNAL:
                    facing = FrameworkStatsLog.CAMERA_ACTION_EVENT__FACING__EXTERNAL;
                    break;
                default:
                    Slog.w(TAG, "Unknown camera facing: " + e.mCameraFacing);
            }

            int streamCount = 0;
            if (e.mStreamStats != null) {
                streamCount = e.mStreamStats.size();
            }
            if (CameraServiceProxy.DEBUG) {
                Slog.v(TAG, "CAMERA_ACTION_EVENT: action " + e.mAction
                        + " clientName " + e.mClientName
                        + ", duration " + e.getDuration()
                        + ", APILevel " + e.mAPILevel
                        + ", cameraId " + e.mCameraId
                        + ", facing " + facing
                        + ", isNdk " + e.mIsNdk
                        + ", latencyMs " + e.mLatencyMs
                        + ", operatingMode " + e.mOperatingMode
                        + ", internalReconfigure " + e.mInternalReconfigure
                        + ", requestCount " + e.mRequestCount
                        + ", resultErrorCount " + e.mResultErrorCount
                        + ", deviceError " + e.mDeviceError
                        + ", streamCount is " + streamCount);
            }
            // Convert from CameraStreamStats to CameraStreamProto
            CameraStreamProto[] streamProtos = new CameraStreamProto[MAX_STREAM_STATISTICS];
            for (int i = 0; i < MAX_STREAM_STATISTICS; i++) {
                streamProtos[i] = new CameraStreamProto();
                if (i < streamCount) {
                    CameraStreamStats streamStats = e.mStreamStats.get(i);
                    streamProtos[i].width = streamStats.getWidth();
                    streamProtos[i].height = streamStats.getHeight();
                    streamProtos[i].format = streamStats.getFormat();
                    streamProtos[i].dataSpace = streamStats.getDataSpace();
                    streamProtos[i].usage = streamStats.getUsage();
                    streamProtos[i].requestCount = streamStats.getRequestCount();
                    streamProtos[i].errorCount = streamStats.getErrorCount();
                    streamProtos[i].firstCaptureLatencyMillis = streamStats.getStartLatencyMs();
                    streamProtos[i].maxHalBuffers = streamStats.getMaxHalBuffers();
                    streamProtos[i].maxAppBuffers = streamStats.getMaxAppBuffers();
                    streamProtos[i].histogramType = streamStats.getHistogramType();
                    streamProtos[i].histogramBins = streamStats.getHistogramBins();
                    streamProtos[i].histogramCounts = streamStats.getHistogramCounts();

                    if (CameraServiceProxy.DEBUG) {
                        String histogramTypeName =
                                cameraHistogramTypeToString(streamProtos[i].histogramType);
                        Slog.v(TAG, "Stream " + i + ": width " + streamProtos[i].width
                                + ", height " + streamProtos[i].height
                                + ", format " + streamProtos[i].format
                                + ", dataSpace " + streamProtos[i].dataSpace
                                + ", usage " + streamProtos[i].usage
                                + ", requestCount " + streamProtos[i].requestCount
                                + ", errorCount " + streamProtos[i].errorCount
                                + ", firstCaptureLatencyMillis "
                                + streamProtos[i].firstCaptureLatencyMillis
                                + ", maxHalBuffers " + streamProtos[i].maxHalBuffers
                                + ", maxAppBuffers " + streamProtos[i].maxAppBuffers
                                + ", histogramType " + histogramTypeName
                                + ", histogramBins "
                                + Arrays.toString(streamProtos[i].histogramBins)
                                + ", histogramCounts "
                                + Arrays.toString(streamProtos[i].histogramCounts));
                    }
                }
            }
            FrameworkStatsLog.write(FrameworkStatsLog.CAMERA_ACTION_EVENT, e.getDuration(),
                    e.mAPILevel, e.mClientName, facing, e.mCameraId, e.mAction, e.mIsNdk,
                    e.mLatencyMs, e.mOperatingMode, e.mInternalReconfigure,
                    e.mRequestCount, e.mResultErrorCount, e.mDeviceError,
                    streamCount, MessageNano.toByteArray(streamProtos[0]),
                    MessageNano.toByteArray(streamProtos[1]),
                    MessageNano.toByteArray(streamProtos[2]),
                    MessageNano.toByteArray(streamProtos[3]),
                    MessageNano.toByteArray(streamProtos[4]));
        }
    }

    /**
     * Dump camera usage events to log.
     * Package-private
     */
    void dumpUsageEvents() {
        synchronized(mLock) {
            // Randomize order of events so that it's not meaningful
            Collections.shuffle(mCameraUsageHistory);
            mLogWriterService.execute(new EventWriterTask(
                        new ArrayList<CameraUsageEvent>(mCameraUsageHistory)));

            mCameraUsageHistory.clear();
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            CameraStatsJobService.schedule(mContext);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Nullable
    private ICameraService getCameraServiceRawLocked() {
        if (mCameraServiceRaw == null) {
            IBinder cameraServiceBinder = getBinderService(CAMERA_SERVICE_BINDER_NAME);
            if (cameraServiceBinder == null) {
                return null;
            }
            try {
                cameraServiceBinder.linkToDeath(this, /*flags*/ 0);
            } catch (RemoteException e) {
                Slog.w(TAG, "Could not link to death of native camera service");
                return null;
            }

            mCameraServiceRaw = ICameraService.Stub.asInterface(cameraServiceBinder);
        }
        return mCameraServiceRaw;
    }

    private void switchUserLocked(int userHandle) {
        Set<Integer> currentUserHandles = getEnabledUserHandles(userHandle);
        mLastUser = userHandle;
        if (mEnabledCameraUsers == null || !mEnabledCameraUsers.equals(currentUserHandles)) {
            // Some user handles have been added or removed, update cameraserver.
            mEnabledCameraUsers = currentUserHandles;
            notifySwitchWithRetriesLocked(RETRY_TIMES);
        }
    }

    private Set<Integer> getEnabledUserHandles(int currentUserHandle) {
        int[] userProfiles = mUserManager.getEnabledProfileIds(currentUserHandle);
        Set<Integer> handles = new ArraySet<>(userProfiles.length);

        for (int id : userProfiles) {
            handles.add(id);
        }

        return handles;
    }

    private void notifySwitchWithRetries(int retries) {
        synchronized(mLock) {
            notifySwitchWithRetriesLocked(retries);
        }
    }

    private void notifySwitchWithRetriesLocked(int retries) {
        if (mEnabledCameraUsers == null) {
            return;
        }
        if (notifyCameraserverLocked(ICameraService.EVENT_USER_SWITCHED, mEnabledCameraUsers)) {
            retries = 0;
        }
        if (retries <= 0) {
            return;
        }
        Slog.i(TAG, "Could not notify camera service of user switch, retrying...");
        mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_SWITCH_USER, retries - 1, 0, null),
                RETRY_DELAY_TIME);
    }

    private boolean notifyCameraserverLocked(int eventType, Set<Integer> updatedUserHandles) {
        // Forward the user switch event to the native camera service running in the cameraserver
        // process.
        ICameraService cameraService = getCameraServiceRawLocked();
        if (cameraService == null) {
            Slog.w(TAG, "Could not notify cameraserver, camera service not available.");
            return false;
        }

        try {
            mCameraServiceRaw.notifySystemEvent(eventType, toArray(updatedUserHandles));
        } catch (RemoteException e) {
            Slog.w(TAG, "Could not notify cameraserver, remote exception: " + e);
            // Not much we can do if camera service is dead.
            return false;
        }
        return true;
    }

    private void notifyDeviceStateWithRetries(int retries) {
        synchronized (mLock) {
            notifyDeviceStateWithRetriesLocked(retries);
        }
    }

    private void notifyDeviceStateWithRetriesLocked(int retries) {
        if (notifyDeviceStateChangeLocked(mDeviceState)) {
            return;
        }
        if (retries <= 0) {
            return;
        }
        Slog.i(TAG, "Could not notify camera service of device state change, retrying...");
        mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_NOTIFY_DEVICE_STATE, retries - 1,
                0, null), RETRY_DELAY_TIME);
    }

    private boolean notifyDeviceStateChangeLocked(@DeviceStateFlags int deviceState) {
        // Forward the state to the native camera service running in the cameraserver process.
        ICameraService cameraService = getCameraServiceRawLocked();
        if (cameraService == null) {
            Slog.w(TAG, "Could not notify cameraserver, camera service not available.");
            return false;
        }

        try {
            mCameraServiceRaw.notifyDeviceStateChange(deviceState);
        } catch (RemoteException e) {
            Slog.w(TAG, "Could not notify cameraserver, remote exception: " + e);
            // Not much we can do if camera service is dead.
            return false;
        }
        mLastReportedDeviceState = deviceState;
        return true;
    }

    private void updateActivityCount(CameraSessionStats cameraState) {
        String cameraId = cameraState.getCameraId();
        int newCameraState = cameraState.getNewCameraState();
        int facing = cameraState.getFacing();
        String clientName = cameraState.getClientName();
        int apiLevel = cameraState.getApiLevel();
        boolean isNdk = cameraState.isNdk();
        int sessionType = cameraState.getSessionType();
        int internalReconfigureCount = cameraState.getInternalReconfigureCount();
        int latencyMs = cameraState.getLatencyMs();
        long requestCount = cameraState.getRequestCount();
        long resultErrorCount = cameraState.getResultErrorCount();
        boolean deviceError = cameraState.getDeviceErrorFlag();
        List<CameraStreamStats> streamStats = cameraState.getStreamStats();
        synchronized(mLock) {
            // Update active camera list and notify NFC if necessary
            boolean wasEmpty = mActiveCameraUsage.isEmpty();
            switch (newCameraState) {
                case CameraSessionStats.CAMERA_STATE_OPEN:
                    // Notify the audio subsystem about the facing of the most-recently opened
                    // camera This can be used to select the best audio tuning in case video
                    // recording with that camera will happen.  Since only open events are used, if
                    // multiple cameras are opened at once, the one opened last will be used to
                    // select audio tuning.
                    AudioManager audioManager = getContext().getSystemService(AudioManager.class);
                    if (audioManager != null) {
                        // Map external to front for audio tuning purposes
                        String facingStr = (facing == CameraSessionStats.CAMERA_FACING_BACK) ?
                                "back" : "front";
                        String facingParameter = "cameraFacing=" + facingStr;
                        audioManager.setParameters(facingParameter);
                    }
                    CameraUsageEvent openEvent = new CameraUsageEvent(
                            cameraId, facing, clientName, apiLevel, isNdk,
                            FrameworkStatsLog.CAMERA_ACTION_EVENT__ACTION__OPEN,
                            latencyMs, sessionType);
                    mCameraUsageHistory.add(openEvent);
                    break;
                case CameraSessionStats.CAMERA_STATE_ACTIVE:
                    // Check current active camera IDs to see if this package is already talking to
                    // some camera
                    boolean alreadyActivePackage = false;
                    for (int i = 0; i < mActiveCameraUsage.size(); i++) {
                        if (mActiveCameraUsage.valueAt(i).mClientName.equals(clientName)) {
                            alreadyActivePackage = true;
                            break;
                        }
                    }
                    // If not already active, notify window manager about this new package using a
                    // camera
                    if (!alreadyActivePackage) {
                        WindowManagerInternal wmi =
                                LocalServices.getService(WindowManagerInternal.class);
                        wmi.addNonHighRefreshRatePackage(clientName);
                    }

                    // Update activity events
                    CameraUsageEvent newEvent = new CameraUsageEvent(
                            cameraId, facing, clientName, apiLevel, isNdk,
                            FrameworkStatsLog.CAMERA_ACTION_EVENT__ACTION__SESSION,
                            latencyMs, sessionType);
                    CameraUsageEvent oldEvent = mActiveCameraUsage.put(cameraId, newEvent);
                    if (oldEvent != null) {
                        Slog.w(TAG, "Camera " + cameraId + " was already marked as active");
                        oldEvent.markCompleted(/*internalReconfigure*/0, /*requestCount*/0,
                                /*resultErrorCount*/0, /*deviceError*/false, streamStats);
                        mCameraUsageHistory.add(oldEvent);
                    }
                    break;
                case CameraSessionStats.CAMERA_STATE_IDLE:
                case CameraSessionStats.CAMERA_STATE_CLOSED:
                    CameraUsageEvent doneEvent = mActiveCameraUsage.remove(cameraId);
                    if (doneEvent != null) {

                        doneEvent.markCompleted(internalReconfigureCount, requestCount,
                                resultErrorCount, deviceError, streamStats);
                        mCameraUsageHistory.add(doneEvent);

                        // Check current active camera IDs to see if this package is still
                        // talking to some camera
                        boolean stillActivePackage = false;
                        for (int i = 0; i < mActiveCameraUsage.size(); i++) {
                            if (mActiveCameraUsage.valueAt(i).mClientName.equals(clientName)) {
                                stillActivePackage = true;
                                break;
                            }
                        }
                        // If not longer active, notify window manager about this package being done
                        // with camera
                        if (!stillActivePackage) {
                            WindowManagerInternal wmi =
                                    LocalServices.getService(WindowManagerInternal.class);
                            wmi.removeNonHighRefreshRatePackage(clientName);
                        }
                    }

                    if (newCameraState == CameraSessionStats.CAMERA_STATE_CLOSED) {
                        CameraUsageEvent closeEvent = new CameraUsageEvent(
                                cameraId, facing, clientName, apiLevel, isNdk,
                                FrameworkStatsLog.CAMERA_ACTION_EVENT__ACTION__CLOSE,
                                latencyMs, sessionType);
                        mCameraUsageHistory.add(closeEvent);
                    }

                    if (mCameraUsageHistory.size() > MAX_USAGE_HISTORY) {
                        dumpUsageEvents();
                    }

                    break;
            }
            boolean isEmpty = mActiveCameraUsage.isEmpty();
            if ( mNotifyNfc && (wasEmpty != isEmpty) ) {
                notifyNfcService(isEmpty);
            }
        }
    }

    private void notifyNfcService(boolean enablePolling) {

        IBinder nfcServiceBinder = getBinderService(NFC_SERVICE_BINDER_NAME);
        if (nfcServiceBinder == null) {
            Slog.w(TAG, "Could not connect to NFC service to notify it of camera state");
            return;
        }
        INfcAdapter nfcAdapterRaw = INfcAdapter.Stub.asInterface(nfcServiceBinder);
        int flags = enablePolling ? ENABLE_POLLING_FLAGS : DISABLE_POLLING_FLAGS;
        if (DEBUG) Slog.v(TAG, "Setting NFC reader mode to flags " + flags);
        try {
            nfcAdapterRaw.setReaderMode(nfcInterfaceToken, null, flags, null);
        } catch (RemoteException e) {
            Slog.w(TAG, "Could not notify NFC service, remote exception: " + e);
        }
    }

    private static int[] toArray(Collection<Integer> c) {
        int len = c.size();
        int[] ret = new int[len];
        int idx = 0;
        for (Integer i : c) {
            ret[idx++] = i;
        }
        return ret;
    }

    private static String cameraStateToString(int newCameraState) {
        switch (newCameraState) {
            case CameraSessionStats.CAMERA_STATE_OPEN: return "CAMERA_STATE_OPEN";
            case CameraSessionStats.CAMERA_STATE_ACTIVE: return "CAMERA_STATE_ACTIVE";
            case CameraSessionStats.CAMERA_STATE_IDLE: return "CAMERA_STATE_IDLE";
            case CameraSessionStats.CAMERA_STATE_CLOSED: return "CAMERA_STATE_CLOSED";
            default: break;
        }
        return "CAMERA_STATE_UNKNOWN";
    }

    private static String cameraFacingToString(int cameraFacing) {
        switch (cameraFacing) {
            case CameraSessionStats.CAMERA_FACING_BACK: return "CAMERA_FACING_BACK";
            case CameraSessionStats.CAMERA_FACING_FRONT: return "CAMERA_FACING_FRONT";
            case CameraSessionStats.CAMERA_FACING_EXTERNAL: return "CAMERA_FACING_EXTERNAL";
            default: break;
        }
        return "CAMERA_FACING_UNKNOWN";
    }

    private static String cameraHistogramTypeToString(int cameraHistogramType) {
        switch (cameraHistogramType) {
            case CameraStreamStats.HISTOGRAM_TYPE_CAPTURE_LATENCY:
                return "HISTOGRAM_TYPE_CAPTURE_LATENCY";
            default:
                break;
        }
        return "HISTOGRAM_TYPE_UNKNOWN";
    }

}
