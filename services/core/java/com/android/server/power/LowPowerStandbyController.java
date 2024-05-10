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

package com.android.server.power;

import static android.os.PowerManager.LOW_POWER_STANDBY_ALLOWED_REASON_ONGOING_CALL;
import static android.os.PowerManager.LOW_POWER_STANDBY_ALLOWED_REASON_TEMP_POWER_SAVE_ALLOWLIST;
import static android.os.PowerManager.lowPowerStandbyAllowedReasonsToString;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AlarmManager;
import android.app.IActivityManager;
import android.app.IForegroundServiceObserver;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManager.LowPowerStandbyAllowedReason;
import android.os.PowerManager.LowPowerStandbyPolicy;
import android.os.PowerManager.LowPowerStandbyPortDescription;
import android.os.PowerManagerInternal;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.IndentingPrintWriter;
import android.util.Slog;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.Xml;
import android.util.proto.ProtoOutputStream;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.LocalServices;
import com.android.server.PowerAllowlistInternal;
import com.android.server.net.NetworkPolicyManagerInternal;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Controls Low Power Standby state.
 *
 * Instantiated by {@link PowerManagerService} only if Low Power Standby is supported.
 *
 * <p>Low Power Standby is active when all of the following conditions are met:
 * <ul>
 *   <li>Low Power Standby is enabled
 *   <li>The device is not interactive, and has been non-interactive for a given timeout
 *   <li>The device is not in a doze maintenance window (devices may be configured to also
 *   apply restrictions during doze maintenance windows, see {@link #setActiveDuringMaintenance})
 * </ul>
 *
 * <p>When Low Power Standby is active, the following restrictions are applied to applications
 * with procstate less important than {@link android.app.ActivityManager#PROCESS_STATE_BOUND_TOP}
 * unless they are exempted (see {@link LowPowerStandbyPolicy}):
 * <ul>
 *   <li>Network access is blocked
 *   <li>Wakelocks are disabled
 * </ul>
 *
 * @hide
 */
public class LowPowerStandbyController {
    private static final String TAG = "LowPowerStandbyController";
    private static final boolean DEBUG = false;
    private static final boolean DEFAULT_ACTIVE_DURING_MAINTENANCE = false;

    private static final int MSG_STANDBY_TIMEOUT = 0;
    private static final int MSG_NOTIFY_ACTIVE_CHANGED = 1;
    private static final int MSG_NOTIFY_ALLOWLIST_CHANGED = 2;
    private static final int MSG_NOTIFY_POLICY_CHANGED = 3;
    private static final int MSG_FOREGROUND_SERVICE_STATE_CHANGED = 4;
    private static final int MSG_NOTIFY_STANDBY_PORTS_CHANGED = 5;

    private static final String TAG_ROOT = "low-power-standby-policy";
    private static final String TAG_IDENTIFIER = "identifier";
    private static final String TAG_EXEMPT_PACKAGE = "exempt-package";
    private static final String TAG_ALLOWED_REASONS = "allowed-reasons";
    private static final String TAG_ALLOWED_FEATURES = "allowed-features";
    private static final String ATTR_VALUE = "value";

    private final Handler mHandler;
    private final SettingsObserver mSettingsObserver;
    private final DeviceConfigWrapper mDeviceConfig;
    private final Supplier<IActivityManager> mActivityManager;
    private final File mPolicyFile;
    private final Object mLock = new Object();

    private final Context mContext;
    private final Clock mClock;
    private final AlarmManager.OnAlarmListener mOnStandbyTimeoutExpired =
            this::onStandbyTimeoutExpired;
    private final LowPowerStandbyControllerInternal mLocalService = new LocalService();
    private final SparseIntArray mUidAllowedReasons = new SparseIntArray();
    private final List<String> mLowPowerStandbyManagingPackages = new ArrayList<>();
    private final List<StandbyPortsLock> mStandbyPortLocks = new ArrayList<>();

    @GuardedBy("mLock")
    private boolean mEnableCustomPolicy;
    private boolean mEnableStandbyPorts;

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case Intent.ACTION_SCREEN_OFF:
                    onNonInteractive();
                    break;
                case Intent.ACTION_SCREEN_ON:
                    onInteractive();
                    break;
                case PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED:
                    onDeviceIdleModeChanged();
                    break;
            }
        }
    };
    private final TempAllowlistChangeListener mTempAllowlistChangeListener =
            new TempAllowlistChangeListener();
    private final PhoneCallServiceTracker mPhoneCallServiceTracker = new PhoneCallServiceTracker();

    private final BroadcastReceiver mPackageBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) {
                Slog.d(TAG, "Received package intent: action=" + intent.getAction() + ", data="
                        + intent.getData());
            }
            final boolean replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
            if (replacing) {
                return;
            }
            final Uri intentUri = intent.getData();
            final String packageName = (intentUri != null) ? intentUri.getSchemeSpecificPart()
                    : null;
            synchronized (mLock) {
                final LowPowerStandbyPolicy policy = getPolicy();
                if (policy.getExemptPackages().contains(packageName)) {
                    enqueueNotifyAllowlistChangedLocked();
                }
            }
        }
    };

    private final BroadcastReceiver mUserReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) {
                Slog.d(TAG, "Received user intent: action=" + intent.getAction());
            }
            synchronized (mLock) {
                enqueueNotifyAllowlistChangedLocked();
            }
        }
    };

    private final class StandbyPortsLock implements IBinder.DeathRecipient {
        private final IBinder mToken;
        private final int mUid;
        private final List<LowPowerStandbyPortDescription> mPorts;

        StandbyPortsLock(IBinder token, int uid, List<LowPowerStandbyPortDescription> ports) {
            mToken = token;
            mUid = uid;
            mPorts = ports;
        }

        public boolean linkToDeath() {
            try {
                mToken.linkToDeath(this, 0);
                return true;
            } catch (RemoteException e) {
                Slog.i(TAG, "StandbyPorts token already died");
                return false;
            }
        }

        public void unlinkToDeath() {
            mToken.unlinkToDeath(this, 0);
        }

        public IBinder getToken() {
            return mToken;
        }

        public int getUid() {
            return mUid;
        }

        public List<LowPowerStandbyPortDescription> getPorts() {
            return mPorts;
        }

        @Override
        public void binderDied() {
            releaseStandbyPorts(mToken);
        }
    }

    @GuardedBy("mLock")
    private AlarmManager mAlarmManager;
    @GuardedBy("mLock")
    private PowerManager mPowerManager;
    private ActivityManagerInternal mActivityManagerInternal;
    @GuardedBy("mLock")
    private boolean mSupportedConfig;
    @GuardedBy("mLock")
    private boolean mEnabledByDefaultConfig;
    @GuardedBy("mLock")
    private int mStandbyTimeoutConfig;

    /** Whether Low Power Standby is enabled in Settings */
    @GuardedBy("mLock")
    private boolean mIsEnabled;

    /**
     * Whether Low Power Standby is currently active (enforcing restrictions).
     */
    @GuardedBy("mLock")
    private boolean mIsActive;

    /** Whether the device is currently interactive */
    @GuardedBy("mLock")
    private boolean mIsInteractive;

    /** The time the device was last interactive, in {@link SystemClock#elapsedRealtime()}. */
    @GuardedBy("mLock")
    private long mLastInteractiveTimeElapsed;

    /**
     * Whether we are in device idle mode.
     * During maintenance windows Low Power Standby is deactivated to allow
     * apps to run maintenance tasks.
     */
    @GuardedBy("mLock")
    private boolean mIsDeviceIdle;

    /**
     * Whether the device has entered idle mode since becoming non-interactive.
     * In the initial non-idle period after turning the screen off, Low Power Standby is already
     * allowed to become active. Later non-idle periods are treated as maintenance windows, during
     * which Low Power Standby is deactivated to allow apps to run maintenance tasks.
     */
    @GuardedBy("mLock")
    private boolean mIdleSinceNonInteractive;

    /** Whether Low Power Standby restrictions should be active during doze maintenance mode. */
    @GuardedBy("mLock")
    private boolean mActiveDuringMaintenance;

    /** Force Low Power Standby to be active. */
    @GuardedBy("mLock")
    private boolean mForceActive;

    /** Current Low Power Standby policy. */
    @GuardedBy("mLock")
    @Nullable
    private LowPowerStandbyPolicy mPolicy;

    @VisibleForTesting
    static final LowPowerStandbyPolicy DEFAULT_POLICY = new LowPowerStandbyPolicy(
            "DEFAULT_POLICY",
            Collections.emptySet(),
            PowerManager.LOW_POWER_STANDBY_ALLOWED_REASON_VOICE_INTERACTION,
            Collections.emptySet());

    /** Functional interface for providing time. */
    @VisibleForTesting
    interface Clock {
        /** Returns milliseconds since boot, including time spent in sleep. */
        long elapsedRealtime();

        /** Returns milliseconds since boot, not counting time spent in deep sleep. */
        long uptimeMillis();
    }

    private static class RealClock implements Clock {
        @Override
        public long elapsedRealtime() {
            return SystemClock.elapsedRealtime();
        }

        @Override
        public long uptimeMillis() {
            return SystemClock.uptimeMillis();
        }
    }

    public LowPowerStandbyController(Context context, Looper looper) {
        this(context, looper, new RealClock(), new DeviceConfigWrapper(),
                () -> ActivityManager.getService(),
                new File(Environment.getDataSystemDirectory(), "low_power_standby_policy.xml"));
    }

    @VisibleForTesting
    LowPowerStandbyController(Context context, Looper looper, Clock clock,
            DeviceConfigWrapper deviceConfig, Supplier<IActivityManager> activityManager,
            File policyFile) {
        mContext = context;
        mHandler = new LowPowerStandbyHandler(looper);
        mClock = clock;
        mSettingsObserver = new SettingsObserver(mHandler);
        mDeviceConfig = deviceConfig;
        mActivityManager = activityManager;
        mPolicyFile = policyFile;
    }

    /** Call when system services are ready */
    @VisibleForTesting
    public void systemReady() {
        final Resources resources = mContext.getResources();
        synchronized (mLock) {
            mSupportedConfig = resources.getBoolean(
                    com.android.internal.R.bool.config_lowPowerStandbySupported);

            if (!mSupportedConfig) {
                return;
            }

            List<PackageInfo> manageLowPowerStandbyPackages = mContext.getPackageManager()
                    .getPackagesHoldingPermissions(new String[]{
                            Manifest.permission.MANAGE_LOW_POWER_STANDBY
                    }, PackageManager.MATCH_SYSTEM_ONLY);
            for (PackageInfo packageInfo : manageLowPowerStandbyPackages) {
                mLowPowerStandbyManagingPackages.add(packageInfo.packageName);
            }

            mAlarmManager = mContext.getSystemService(AlarmManager.class);
            mPowerManager = mContext.getSystemService(PowerManager.class);
            mActivityManagerInternal = LocalServices.getService(ActivityManagerInternal.class);

            mStandbyTimeoutConfig = resources.getInteger(
                    R.integer.config_lowPowerStandbyNonInteractiveTimeout);
            mEnabledByDefaultConfig = resources.getBoolean(
                    R.bool.config_lowPowerStandbyEnabledByDefault);

            mIsInteractive = mPowerManager.isInteractive();

            mContext.getContentResolver().registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.LOW_POWER_STANDBY_ENABLED),
                    false, mSettingsObserver, UserHandle.USER_ALL);
            mContext.getContentResolver().registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.LOW_POWER_STANDBY_ACTIVE_DURING_MAINTENANCE),
                    false, mSettingsObserver, UserHandle.USER_ALL);

            mDeviceConfig.registerPropertyUpdateListener(mContext.getMainExecutor(),
                    properties -> onDeviceConfigFlagsChanged());
            mEnableCustomPolicy = mDeviceConfig.enableCustomPolicy();
            mEnableStandbyPorts = mDeviceConfig.enableStandbyPorts();

            if (mEnableCustomPolicy) {
                mPolicy = loadPolicy();
            } else {
                mPolicy = DEFAULT_POLICY;
            }
            initSettingsLocked();
            updateSettingsLocked();

            if (mIsEnabled) {
                registerListeners();
            }
        }

        LocalServices.addService(LowPowerStandbyControllerInternal.class, mLocalService);
    }

    private void onDeviceConfigFlagsChanged() {
        synchronized (mLock) {
            boolean enableCustomPolicy = mDeviceConfig.enableCustomPolicy();
            if (mEnableCustomPolicy != enableCustomPolicy) {
                enqueueNotifyPolicyChangedLocked();
                enqueueNotifyAllowlistChangedLocked();
                mEnableCustomPolicy = enableCustomPolicy;
            }

            mEnableStandbyPorts = mDeviceConfig.enableStandbyPorts();
        }
    }

    @GuardedBy("mLock")
    private void initSettingsLocked() {
        final ContentResolver resolver = mContext.getContentResolver();
        if (mSupportedConfig) {
            final int enabledSetting = Settings.Global.getInt(resolver,
                    Settings.Global.LOW_POWER_STANDBY_ENABLED, /* def= */ -1);

            // If the ENABLED setting hasn't been assigned yet, set it to its default value.
            // This ensures reading the setting reflects the enabled state, without having to know
            // the default value for this device.
            if (enabledSetting == -1) {
                Settings.Global.putInt(resolver, Settings.Global.LOW_POWER_STANDBY_ENABLED,
                        /* value= */ mEnabledByDefaultConfig ? 1 : 0);
            }
        }
    }

    @GuardedBy("mLock")
    private void updateSettingsLocked() {
        final ContentResolver resolver = mContext.getContentResolver();
        mIsEnabled = mSupportedConfig && Settings.Global.getInt(resolver,
                Settings.Global.LOW_POWER_STANDBY_ENABLED,
                mEnabledByDefaultConfig ? 1 : 0) != 0;
        mActiveDuringMaintenance = Settings.Global.getInt(resolver,
                Settings.Global.LOW_POWER_STANDBY_ACTIVE_DURING_MAINTENANCE,
                DEFAULT_ACTIVE_DURING_MAINTENANCE ? 1 : 0) != 0;

        updateActiveLocked();
    }

    @Nullable
    private LowPowerStandbyPolicy loadPolicy() {
        final AtomicFile file = getPolicyFile();
        if (!file.exists()) {
            return null;
        }
        if (DEBUG) {
            Slog.d(TAG, "Loading policy from " + file.getBaseFile());
        }

        try (FileInputStream in = file.openRead()) {
            String identifier = null;
            Set<String> exemptPackages = new ArraySet<>();
            int allowedReasons = 0;
            Set<String> allowedFeatures = new ArraySet<>();

            TypedXmlPullParser parser = Xml.resolvePullParser(in);

            int type;
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (type != XmlPullParser.START_TAG) {
                    continue;
                }
                final int depth = parser.getDepth();
                // Check the root tag
                final String tag = parser.getName();
                if (depth == 1) {
                    if (!TAG_ROOT.equals(tag)) {
                        Slog.e(TAG, "Invalid root tag: " + tag);
                        return null;
                    }
                    continue;
                }
                // Assume depth == 2
                switch (tag) {
                    case TAG_IDENTIFIER:
                        identifier = parser.getAttributeValue(null, ATTR_VALUE);
                        break;
                    case TAG_EXEMPT_PACKAGE:
                        exemptPackages.add(parser.getAttributeValue(null, ATTR_VALUE));
                        break;
                    case TAG_ALLOWED_REASONS:
                        allowedReasons = parser.getAttributeInt(null, ATTR_VALUE);
                        break;
                    case TAG_ALLOWED_FEATURES:
                        allowedFeatures.add(parser.getAttributeValue(null, ATTR_VALUE));
                        break;
                    default:
                        Slog.e(TAG, "Invalid tag: " + tag);
                        break;
                }
            }

            final LowPowerStandbyPolicy policy = new LowPowerStandbyPolicy(identifier,
                    exemptPackages, allowedReasons, allowedFeatures);
            if (DEBUG) {
                Slog.d(TAG, "Loaded policy: " + policy);
            }
            return policy;
        } catch (FileNotFoundException e) {
            // Use the default
            return null;
        } catch (IOException | NullPointerException | IllegalArgumentException
                | XmlPullParserException e) {
            Slog.e(TAG, "Failed to read policy file " + file.getBaseFile(), e);
            return null;
        }
    }

    static void writeTagValue(TypedXmlSerializer out, String tag, String value) throws IOException {
        if (TextUtils.isEmpty(value)) return;

        out.startTag(null, tag);
        out.attribute(null, ATTR_VALUE, value);
        out.endTag(null, tag);
    }

    static void writeTagValue(TypedXmlSerializer out, String tag, int value) throws IOException {
        out.startTag(null, tag);
        out.attributeInt(null, ATTR_VALUE, value);
        out.endTag(null, tag);
    }

    private void savePolicy(@Nullable LowPowerStandbyPolicy policy) {
        final AtomicFile file = getPolicyFile();
        if (DEBUG) {
            Slog.d(TAG, "Saving policy to " + file.getBaseFile());
        }
        if (policy == null) {
            file.delete();
            return;
        }

        FileOutputStream outs = null;
        try {
            file.getBaseFile().mkdirs();
            outs = file.startWrite();

            // Write to XML
            TypedXmlSerializer out = Xml.resolveSerializer(outs);
            out.startDocument(null, true);
            out.startTag(null, TAG_ROOT);

            // Body.
            writeTagValue(out, TAG_IDENTIFIER, policy.getIdentifier());
            for (String exemptPackage : policy.getExemptPackages()) {
                writeTagValue(out, TAG_EXEMPT_PACKAGE, exemptPackage);
            }
            writeTagValue(out, TAG_ALLOWED_REASONS, policy.getAllowedReasons());
            for (String allowedFeature : policy.getAllowedFeatures()) {
                writeTagValue(out, TAG_ALLOWED_FEATURES, allowedFeature);
            }

            // Epilogue.
            out.endTag(null, TAG_ROOT);
            out.endDocument();

            // Close.
            file.finishWrite(outs);
        } catch (IOException e) {
            Slog.e(TAG, "Failed to write policy to file " + file.getBaseFile(), e);
            file.failWrite(outs);
        }
    }

    private void enqueueSavePolicy(@Nullable LowPowerStandbyPolicy policy) {
        mHandler.post(() -> savePolicy(policy));
    }

    private AtomicFile getPolicyFile() {
        return new AtomicFile(mPolicyFile);
    }

    @GuardedBy("mLock")
    private void updateActiveLocked() {
        final long nowElapsed = mClock.elapsedRealtime();
        final boolean standbyTimeoutExpired =
                (nowElapsed - mLastInteractiveTimeElapsed) >= mStandbyTimeoutConfig;
        final boolean maintenanceMode = mIdleSinceNonInteractive && !mIsDeviceIdle;
        final boolean newActive =
                mForceActive || (mIsEnabled && !mIsInteractive && standbyTimeoutExpired
                        && (!maintenanceMode || mActiveDuringMaintenance));
        if (DEBUG) {
            Slog.d(TAG, "updateActiveLocked: mIsEnabled=" + mIsEnabled + ", mIsInteractive="
                    + mIsInteractive + ", standbyTimeoutExpired=" + standbyTimeoutExpired
                    + ", mIdleSinceNonInteractive=" + mIdleSinceNonInteractive + ", mIsDeviceIdle="
                    + mIsDeviceIdle + ", mActiveDuringMaintenance=" + mActiveDuringMaintenance
                    + ", mForceActive=" + mForceActive + ", mIsActive=" + mIsActive + ", newActive="
                    + newActive);
        }
        if (mIsActive != newActive) {
            mIsActive = newActive;
            if (DEBUG) {
                Slog.d(TAG, "mIsActive changed, mIsActive=" + mIsActive);
            }
            enqueueNotifyActiveChangedLocked();
        }
    }

    private void onNonInteractive() {
        if (DEBUG) {
            Slog.d(TAG, "onNonInteractive");
        }
        final long nowElapsed = mClock.elapsedRealtime();
        synchronized (mLock) {
            mIsInteractive = false;
            mIsDeviceIdle = false;
            mLastInteractiveTimeElapsed = nowElapsed;

            if (mStandbyTimeoutConfig > 0) {
                scheduleStandbyTimeoutAlarmLocked();
            }

            updateActiveLocked();
        }
    }

    private void onInteractive() {
        if (DEBUG) {
            Slog.d(TAG, "onInteractive");
        }

        synchronized (mLock) {
            cancelStandbyTimeoutAlarmLocked();
            mIsInteractive = true;
            mIsDeviceIdle = false;
            mIdleSinceNonInteractive = false;
            updateActiveLocked();
        }
    }

    @GuardedBy("mLock")
    private void scheduleStandbyTimeoutAlarmLocked() {
        final long nextAlarmTime = mClock.elapsedRealtime() + mStandbyTimeoutConfig;
        mAlarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                nextAlarmTime, "LowPowerStandbyController.StandbyTimeout",
                mOnStandbyTimeoutExpired, mHandler);
    }

    @GuardedBy("mLock")
    private void cancelStandbyTimeoutAlarmLocked() {
        mAlarmManager.cancel(mOnStandbyTimeoutExpired);
    }

    private void onDeviceIdleModeChanged() {
        synchronized (mLock) {
            mIsDeviceIdle = mPowerManager.isDeviceIdleMode();
            if (DEBUG) {
                Slog.d(TAG, "onDeviceIdleModeChanged, mIsDeviceIdle=" + mIsDeviceIdle);
            }

            mIdleSinceNonInteractive = mIdleSinceNonInteractive || mIsDeviceIdle;
            updateActiveLocked();
        }
    }

    @GuardedBy("mLock")
    private void onEnabledLocked() {
        if (DEBUG) {
            Slog.d(TAG, "onEnabledLocked");
        }

        if (mPowerManager.isInteractive()) {
            onInteractive();
        } else {
            onNonInteractive();
        }

        registerListeners();
    }

    @GuardedBy("mLock")
    private void onDisabledLocked() {
        if (DEBUG) {
            Slog.d(TAG, "onDisabledLocked");
        }

        cancelStandbyTimeoutAlarmLocked();
        unregisterListeners();
        updateActiveLocked();
    }

    @VisibleForTesting
    void onSettingsChanged() {
        if (DEBUG) {
            Slog.d(TAG, "onSettingsChanged");
        }
        synchronized (mLock) {
            final boolean oldEnabled = mIsEnabled;
            updateSettingsLocked();

            if (mIsEnabled != oldEnabled) {
                if (mIsEnabled) {
                    onEnabledLocked();
                } else {
                    onDisabledLocked();
                }

                notifyEnabledChangedLocked();
            }
        }
    }

    private void registerListeners() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
        intentFilter.addAction(Intent.ACTION_SCREEN_ON);
        intentFilter.addAction(Intent.ACTION_SCREEN_OFF);

        mContext.registerReceiver(mBroadcastReceiver, intentFilter);

        IntentFilter packageFilter = new IntentFilter();
        packageFilter.addDataScheme(IntentFilter.SCHEME_PACKAGE);
        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        mContext.registerReceiver(mPackageBroadcastReceiver, packageFilter);

        final IntentFilter userFilter = new IntentFilter();
        userFilter.addAction(Intent.ACTION_USER_ADDED);
        userFilter.addAction(Intent.ACTION_USER_REMOVED);
        mContext.registerReceiver(mUserReceiver, userFilter, null, mHandler);

        PowerAllowlistInternal pai = LocalServices.getService(PowerAllowlistInternal.class);
        pai.registerTempAllowlistChangeListener(mTempAllowlistChangeListener);

        mPhoneCallServiceTracker.register();
    }

    private void unregisterListeners() {
        mContext.unregisterReceiver(mBroadcastReceiver);
        mContext.unregisterReceiver(mPackageBroadcastReceiver);
        mContext.unregisterReceiver(mUserReceiver);

        PowerAllowlistInternal pai = LocalServices.getService(PowerAllowlistInternal.class);
        pai.unregisterTempAllowlistChangeListener(mTempAllowlistChangeListener);
    }

    @GuardedBy("mLock")
    private void notifyEnabledChangedLocked() {
        if (DEBUG) {
            Slog.d(TAG, "notifyEnabledChangedLocked, mIsEnabled=" + mIsEnabled);
        }

        sendExplicitBroadcast(PowerManager.ACTION_LOW_POWER_STANDBY_ENABLED_CHANGED);
    }

    @GuardedBy("mLock")
    private void enqueueNotifyPolicyChangedLocked() {
        final Message msg = mHandler.obtainMessage(MSG_NOTIFY_POLICY_CHANGED, getPolicy());
        mHandler.sendMessageAtTime(msg, mClock.uptimeMillis());
    }

    private void notifyPolicyChanged(LowPowerStandbyPolicy policy) {
        if (DEBUG) {
            Slog.d(TAG, "notifyPolicyChanged, policy=" + policy);
        }

        sendExplicitBroadcast(PowerManager.ACTION_LOW_POWER_STANDBY_POLICY_CHANGED);
    }

    private void onStandbyTimeoutExpired() {
        if (DEBUG) {
            Slog.d(TAG, "onStandbyTimeoutExpired");
        }
        synchronized (mLock) {
            updateActiveLocked();
        }
    }

    private void sendExplicitBroadcast(String intentType) {
        final Intent intent = new Intent(intentType);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY | Intent.FLAG_RECEIVER_FOREGROUND);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);

        // Send explicit broadcast to holders of MANAGE_LOW_POWER_STANDBY
        final Intent privilegedIntent = new Intent(intentType);
        privilegedIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        for (String packageName : mLowPowerStandbyManagingPackages) {
            final Intent explicitIntent = new Intent(privilegedIntent);
            explicitIntent.setPackage(packageName);
            mContext.sendBroadcastAsUser(explicitIntent, UserHandle.ALL,
                    Manifest.permission.MANAGE_LOW_POWER_STANDBY);
        }
    }

    @GuardedBy("mLock")
    private void enqueueNotifyActiveChangedLocked() {
        final Message msg = mHandler.obtainMessage(MSG_NOTIFY_ACTIVE_CHANGED, mIsActive);
        mHandler.sendMessageAtTime(msg, mClock.uptimeMillis());
    }

    /** Notify other system components about the updated Low Power Standby active state */
    private void notifyActiveChanged(boolean active) {
        if (DEBUG) {
            Slog.d(TAG, "notifyActiveChanged, active=" + active);
        }
        final PowerManagerInternal pmi = LocalServices.getService(PowerManagerInternal.class);
        final NetworkPolicyManagerInternal npmi = LocalServices.getService(
                NetworkPolicyManagerInternal.class);

        pmi.setLowPowerStandbyActive(active);
        npmi.setLowPowerStandbyActive(active);
    }

    @VisibleForTesting
    boolean isActive() {
        synchronized (mLock) {
            return mIsActive;
        }
    }

    boolean isSupported() {
        synchronized (mLock) {
            return mSupportedConfig;
        }
    }

    boolean isEnabled() {
        synchronized (mLock) {
            return mSupportedConfig && mIsEnabled;
        }
    }

    void setEnabled(boolean enabled) {
        synchronized (mLock) {
            if (!mSupportedConfig) {
                Slog.w(TAG, "Low Power Standby cannot be enabled "
                        + "because it is not supported on this device");
                return;
            }

            Settings.Global.putInt(mContext.getContentResolver(),
                    Settings.Global.LOW_POWER_STANDBY_ENABLED, enabled ? 1 : 0);
            onSettingsChanged();
        }
    }

    /** Set whether Low Power Standby should be active during doze maintenance mode. */
    @VisibleForTesting
    public void setActiveDuringMaintenance(boolean activeDuringMaintenance) {
        synchronized (mLock) {
            if (!mSupportedConfig) {
                Slog.w(TAG, "Low Power Standby settings cannot be changed "
                        + "because it is not supported on this device");
                return;
            }

            Settings.Global.putInt(mContext.getContentResolver(),
                    Settings.Global.LOW_POWER_STANDBY_ACTIVE_DURING_MAINTENANCE,
                    activeDuringMaintenance ? 1 : 0);
            onSettingsChanged();
        }
    }

    void forceActive(boolean active) {
        synchronized (mLock) {
            mForceActive = active;
            updateActiveLocked();
        }
    }

    void setPolicy(@Nullable LowPowerStandbyPolicy policy) {
        synchronized (mLock) {
            if (!mSupportedConfig) {
                Slog.w(TAG, "Low Power Standby policy cannot be changed "
                        + "because it is not supported on this device");
                return;
            }

            if (!mEnableCustomPolicy) {
                Slog.d(TAG, "Custom policies are not enabled.");
                return;
            }

            if (DEBUG) {
                Slog.d(TAG, "setPolicy: policy=" + policy);
            }
            if (Objects.equals(mPolicy, policy)) {
                return;
            }

            boolean allowlistChanged = policyChangeAffectsAllowlistLocked(mPolicy, policy);
            mPolicy = policy;
            enqueueSavePolicy(mPolicy);
            if (allowlistChanged) {
                enqueueNotifyAllowlistChangedLocked();
            }
            enqueueNotifyPolicyChangedLocked();
        }
    }

    @Nullable
    LowPowerStandbyPolicy getPolicy() {
        synchronized (mLock) {
            if (!mSupportedConfig) {
                return null;
            } else if (mEnableCustomPolicy) {
                return policyOrDefault(mPolicy);
            } else {
                return DEFAULT_POLICY;
            }
        }
    }

    @NonNull
    private LowPowerStandbyPolicy policyOrDefault(@Nullable LowPowerStandbyPolicy policy) {
        if (policy == null) {
            return DEFAULT_POLICY;
        }
        return policy;
    }

    boolean isPackageExempt(int uid) {
        synchronized (mLock) {
            if (!isEnabled()) {
                return true;
            }

            return getExemptPackageAppIdsLocked().contains(UserHandle.getAppId(uid));
        }
    }

    boolean isAllowed(@LowPowerStandbyAllowedReason int reason) {
        synchronized (mLock) {
            if (!isEnabled()) {
                return true;
            }

            return (getPolicy().getAllowedReasons() & reason) != 0;
        }
    }

    boolean isAllowed(String feature) {
        synchronized (mLock) {
            if (!mSupportedConfig) {
                return true;
            }

            return !isEnabled() || getPolicy().getAllowedFeatures().contains(feature);
        }
    }

    private int findIndexOfStandbyPorts(@NonNull IBinder token) {
        for (int i = 0; i < mStandbyPortLocks.size(); i++) {
            if (mStandbyPortLocks.get(i).getToken() == token) {
                return i;
            }
        }
        return -1;
    }

    void acquireStandbyPorts(@NonNull IBinder token, int uid,
            @NonNull List<LowPowerStandbyPortDescription> ports) {
        validatePorts(ports);

        StandbyPortsLock standbyPortsLock = new StandbyPortsLock(token, uid, ports);
        synchronized (mLock) {
            if (findIndexOfStandbyPorts(token) != -1) {
                return;
            }

            if (standbyPortsLock.linkToDeath()) {
                mStandbyPortLocks.add(standbyPortsLock);
                if (mEnableStandbyPorts && isEnabled() && isPackageExempt(uid)) {
                    enqueueNotifyStandbyPortsChangedLocked();
                }
            }
        }
    }

    void validatePorts(@NonNull List<LowPowerStandbyPortDescription> ports) {
        for (LowPowerStandbyPortDescription portDescription : ports) {
            int port = portDescription.getPortNumber();
            if (port < 0 || port > 0xFFFF) {
                throw new IllegalArgumentException("port out of range:" + port);
            }
        }
    }

    void releaseStandbyPorts(@NonNull IBinder token) {
        synchronized (mLock) {
            int index = findIndexOfStandbyPorts(token);
            if (index == -1) {
                return;
            }

            StandbyPortsLock standbyPortsLock = mStandbyPortLocks.remove(index);
            standbyPortsLock.unlinkToDeath();
            if (mEnableStandbyPorts && isEnabled() && isPackageExempt(standbyPortsLock.getUid())) {
                enqueueNotifyStandbyPortsChangedLocked();
            }
        }
    }

    @NonNull
    List<LowPowerStandbyPortDescription> getActiveStandbyPorts() {
        List<LowPowerStandbyPortDescription> activeStandbyPorts = new ArrayList<>();
        synchronized (mLock) {
            if (!isEnabled() || !mEnableStandbyPorts) {
                return activeStandbyPorts;
            }

            List<Integer> exemptPackageAppIds = getExemptPackageAppIdsLocked();
            for (StandbyPortsLock standbyPortsLock : mStandbyPortLocks) {
                int standbyPortsAppid = UserHandle.getAppId(standbyPortsLock.getUid());
                if (exemptPackageAppIds.contains(standbyPortsAppid)) {
                    activeStandbyPorts.addAll(standbyPortsLock.getPorts());
                }
            }

            return activeStandbyPorts;
        }
    }

    private boolean policyChangeAffectsAllowlistLocked(
            @Nullable LowPowerStandbyPolicy oldPolicy, @Nullable LowPowerStandbyPolicy newPolicy) {
        final LowPowerStandbyPolicy policyA = policyOrDefault(oldPolicy);
        final LowPowerStandbyPolicy policyB = policyOrDefault(newPolicy);
        int allowedReasonsInUse = 0;
        for (int i = 0; i < mUidAllowedReasons.size(); i++) {
            allowedReasonsInUse |= mUidAllowedReasons.valueAt(i);
        }

        int policyAllowedReasonsChanged = policyA.getAllowedReasons() ^ policyB.getAllowedReasons();

        boolean exemptPackagesChanged = !policyA.getExemptPackages().equals(
                policyB.getExemptPackages());

        return (policyAllowedReasonsChanged & allowedReasonsInUse) != 0 || exemptPackagesChanged;
    }

    void dump(PrintWriter pw) {
        final IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");

        ipw.println();
        ipw.println("Low Power Standby Controller:");
        ipw.increaseIndent();
        synchronized (mLock) {
            ipw.print("mIsActive=");
            ipw.println(mIsActive);
            ipw.print("mIsEnabled=");
            ipw.println(mIsEnabled);
            ipw.print("mSupportedConfig=");
            ipw.println(mSupportedConfig);
            ipw.print("mEnabledByDefaultConfig=");
            ipw.println(mEnabledByDefaultConfig);
            ipw.print("mStandbyTimeoutConfig=");
            ipw.println(mStandbyTimeoutConfig);
            ipw.print("mEnableCustomPolicy=");
            ipw.println(mEnableCustomPolicy);

            if (mIsActive || mIsEnabled) {
                ipw.print("mIsInteractive=");
                ipw.println(mIsInteractive);
                ipw.print("mLastInteractiveTime=");
                ipw.println(mLastInteractiveTimeElapsed);
                ipw.print("mIdleSinceNonInteractive=");
                ipw.println(mIdleSinceNonInteractive);
                ipw.print("mIsDeviceIdle=");
                ipw.println(mIsDeviceIdle);
            }

            final int[] allowlistUids = getAllowlistUidsLocked();
            ipw.print("Allowed UIDs=");
            ipw.println(Arrays.toString(allowlistUids));

            final LowPowerStandbyPolicy policy = getPolicy();
            if (policy != null) {
                ipw.println();
                ipw.println("mPolicy:");
                ipw.increaseIndent();
                ipw.print("mIdentifier=");
                ipw.println(policy.getIdentifier());
                ipw.print("mExemptPackages=");
                ipw.println(String.join(",", policy.getExemptPackages()));
                ipw.print("mAllowedReasons=");
                ipw.println(lowPowerStandbyAllowedReasonsToString(policy.getAllowedReasons()));
                ipw.print("mAllowedFeatures=");
                ipw.println(String.join(",", policy.getAllowedFeatures()));
                ipw.decreaseIndent();
            }

            ipw.println();
            ipw.println("UID allowed reasons:");
            ipw.increaseIndent();
            for (int i = 0; i < mUidAllowedReasons.size(); i++) {
                if (mUidAllowedReasons.valueAt(i) > 0) {
                    ipw.print(mUidAllowedReasons.keyAt(i));
                    ipw.print(": ");
                    ipw.println(
                            lowPowerStandbyAllowedReasonsToString(mUidAllowedReasons.valueAt(i)));
                }
            }
            ipw.decreaseIndent();

            final List<LowPowerStandbyPortDescription> activeStandbyPorts = getActiveStandbyPorts();
            if (!activeStandbyPorts.isEmpty()) {
                ipw.println();
                ipw.println("Active standby ports locks:");
                ipw.increaseIndent();
                for (LowPowerStandbyPortDescription portDescription : activeStandbyPorts) {
                    ipw.print(portDescription.toString());
                }
                ipw.decreaseIndent();
            }
        }
        ipw.decreaseIndent();
    }

    void dumpProto(ProtoOutputStream proto, long tag) {
        synchronized (mLock) {
            final long token = proto.start(tag);
            proto.write(LowPowerStandbyControllerDumpProto.IS_ACTIVE, mIsActive);
            proto.write(LowPowerStandbyControllerDumpProto.IS_ENABLED, mIsEnabled);
            proto.write(LowPowerStandbyControllerDumpProto.IS_SUPPORTED_CONFIG, mSupportedConfig);
            proto.write(LowPowerStandbyControllerDumpProto.IS_ENABLED_BY_DEFAULT_CONFIG,
                    mEnabledByDefaultConfig);
            proto.write(LowPowerStandbyControllerDumpProto.IS_INTERACTIVE, mIsInteractive);
            proto.write(LowPowerStandbyControllerDumpProto.LAST_INTERACTIVE_TIME,
                    mLastInteractiveTimeElapsed);
            proto.write(LowPowerStandbyControllerDumpProto.STANDBY_TIMEOUT_CONFIG,
                    mStandbyTimeoutConfig);
            proto.write(LowPowerStandbyControllerDumpProto.IDLE_SINCE_NON_INTERACTIVE,
                    mIdleSinceNonInteractive);
            proto.write(LowPowerStandbyControllerDumpProto.IS_DEVICE_IDLE, mIsDeviceIdle);

            final int[] allowlistUids = getAllowlistUidsLocked();
            for (int appId : allowlistUids) {
                proto.write(LowPowerStandbyControllerDumpProto.ALLOWLIST, appId);
            }

            final LowPowerStandbyPolicy policy = getPolicy();
            if (policy != null) {
                long policyToken = proto.start(LowPowerStandbyControllerDumpProto.POLICY);
                proto.write(LowPowerStandbyPolicyProto.IDENTIFIER, policy.getIdentifier());
                for (String exemptPackage : policy.getExemptPackages()) {
                    proto.write(LowPowerStandbyPolicyProto.EXEMPT_PACKAGES, exemptPackage);
                }
                proto.write(LowPowerStandbyPolicyProto.ALLOWED_REASONS, policy.getAllowedReasons());
                for (String feature : policy.getAllowedFeatures()) {
                    proto.write(LowPowerStandbyPolicyProto.ALLOWED_FEATURES, feature);
                }
                proto.end(policyToken);
            }
            proto.end(token);
        }
    }

    private class LowPowerStandbyHandler extends Handler {
        LowPowerStandbyHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_STANDBY_TIMEOUT:
                    onStandbyTimeoutExpired();
                    break;
                case MSG_NOTIFY_ACTIVE_CHANGED:
                    boolean active = (boolean) msg.obj;
                    notifyActiveChanged(active);
                    break;
                case MSG_NOTIFY_ALLOWLIST_CHANGED:
                    final int[] allowlistUids = (int[]) msg.obj;
                    notifyAllowlistChanged(allowlistUids);
                    break;
                case MSG_NOTIFY_POLICY_CHANGED:
                    notifyPolicyChanged((LowPowerStandbyPolicy) msg.obj);
                    break;
                case MSG_FOREGROUND_SERVICE_STATE_CHANGED:
                    final int uid = msg.arg1;
                    mPhoneCallServiceTracker.foregroundServiceStateChanged(uid);
                    break;
                case MSG_NOTIFY_STANDBY_PORTS_CHANGED:
                    notifyStandbyPortsChanged();
                    break;
            }
        }
    }

    @GuardedBy("mLock")
    private boolean hasAllowedReasonLocked(int uid,
            @LowPowerStandbyAllowedReason int allowedReason) {
        int allowedReasons = mUidAllowedReasons.get(uid);
        return (allowedReasons & allowedReason) != 0;
    }

    @GuardedBy("mLock")
    private boolean addAllowedReasonLocked(int uid,
            @LowPowerStandbyAllowedReason int allowedReason) {
        int allowedReasons = mUidAllowedReasons.get(uid);
        final int newAllowReasons = allowedReasons | allowedReason;
        mUidAllowedReasons.put(uid, newAllowReasons);
        return allowedReasons != newAllowReasons;
    }

    @GuardedBy("mLock")
    private boolean removeAllowedReasonLocked(int uid,
            @LowPowerStandbyAllowedReason int allowedReason) {
        int allowedReasons = mUidAllowedReasons.get(uid);
        if (allowedReasons == 0) {
            return false;
        }

        final int newAllowedReasons = allowedReasons & ~allowedReason;
        if (newAllowedReasons == 0) {
            mUidAllowedReasons.removeAt(mUidAllowedReasons.indexOfKey(uid));
        } else {
            mUidAllowedReasons.put(uid, newAllowedReasons);
        }
        return allowedReasons != newAllowedReasons;
    }

    private void addToAllowlistInternal(int uid, @LowPowerStandbyAllowedReason int allowedReason) {
        if (DEBUG) {
            Slog.i(TAG,
                    "Adding to allowlist: uid=" + uid + ", allowedReason=" + allowedReason);
        }
        synchronized (mLock) {
            if (!mSupportedConfig) {
                return;
            }
            if (allowedReason != 0 && !hasAllowedReasonLocked(uid, allowedReason)) {
                addAllowedReasonLocked(uid, allowedReason);
                if ((getPolicy().getAllowedReasons() & allowedReason) != 0) {
                    enqueueNotifyAllowlistChangedLocked();
                }
            }
        }
    }

    private void removeFromAllowlistInternal(int uid,
            @LowPowerStandbyAllowedReason int allowedReason) {
        if (DEBUG) {
            Slog.i(TAG, "Removing from allowlist: uid=" + uid + ", allowedReason=" + allowedReason);
        }
        synchronized (mLock) {
            if (!mSupportedConfig) {
                return;
            }
            if (allowedReason != 0 && hasAllowedReasonLocked(uid, allowedReason)) {
                removeAllowedReasonLocked(uid, allowedReason);
                if ((getPolicy().getAllowedReasons() & allowedReason) != 0) {
                    enqueueNotifyAllowlistChangedLocked();
                }
            }
        }
    }

    @GuardedBy("mLock")
    @NonNull
    private List<Integer> getExemptPackageAppIdsLocked() {
        final PackageManager packageManager = mContext.getPackageManager();
        final LowPowerStandbyPolicy policy = getPolicy();
        final List<Integer> appIds = new ArrayList<>();
        if (policy == null) {
            return appIds;
        }

        for (String packageName : policy.getExemptPackages()) {
            try {
                int packageUid = packageManager.getPackageUid(packageName,
                        PackageManager.PackageInfoFlags.of(0));
                int appId = UserHandle.getAppId(packageUid);
                appIds.add(appId);
            } catch (PackageManager.NameNotFoundException e) {
                if (DEBUG) {
                    Slog.d(TAG, "Package UID cannot be resolved: packageName=" + packageName);
                }
            }
        }

        return appIds;
    }

    @GuardedBy("mLock")
    private int[] getAllowlistUidsLocked() {
        final UserManager userManager = mContext.getSystemService(UserManager.class);
        final List<UserHandle> userHandles = userManager.getUserHandles(true);
        final ArraySet<Integer> uids = new ArraySet<>(mUidAllowedReasons.size());
        final LowPowerStandbyPolicy policy = getPolicy();
        if (policy == null) {
            return new int[0];
        }

        final int policyAllowedReasons = policy.getAllowedReasons();
        for (int i = 0; i < mUidAllowedReasons.size(); i++) {
            Integer uid = mUidAllowedReasons.keyAt(i);
            if ((mUidAllowedReasons.valueAt(i) & policyAllowedReasons) != 0) {
                uids.add(uid);
            }
        }

        for (int appId : getExemptPackageAppIdsLocked()) {
            for (int uid : uidsForAppId(appId, userHandles)) {
                uids.add(uid);
            }
        }

        int[] allowlistUids = new int[uids.size()];
        for (int i = 0; i < uids.size(); i++) {
            allowlistUids[i] = uids.valueAt(i);
        }
        Arrays.sort(allowlistUids);
        return allowlistUids;
    }

    private int[] uidsForAppId(int appUid, List<UserHandle> userHandles) {
        final int appId = UserHandle.getAppId(appUid);
        final int[] uids = new int[userHandles.size()];
        for (int i = 0; i < userHandles.size(); i++) {
            uids[i] = userHandles.get(i).getUid(appId);
        }
        return uids;
    }

    @GuardedBy("mLock")
    private void enqueueNotifyAllowlistChangedLocked() {
        final int[] allowlistUids = getAllowlistUidsLocked();

        if (DEBUG) {
            Slog.d(TAG, "enqueueNotifyAllowlistChangedLocked: allowlistUids=" + Arrays.toString(
                    allowlistUids));
        }

        final Message msg = mHandler.obtainMessage(MSG_NOTIFY_ALLOWLIST_CHANGED, allowlistUids);
        mHandler.sendMessageAtTime(msg, mClock.uptimeMillis());
    }

    private void notifyAllowlistChanged(int[] allowlistUids) {
        if (DEBUG) {
            Slog.d(TAG, "notifyAllowlistChanged: " + Arrays.toString(allowlistUids));
        }

        final PowerManagerInternal pmi = LocalServices.getService(PowerManagerInternal.class);
        final NetworkPolicyManagerInternal npmi = LocalServices.getService(
                NetworkPolicyManagerInternal.class);
        pmi.setLowPowerStandbyAllowlist(allowlistUids);
        npmi.setLowPowerStandbyAllowlist(allowlistUids);
    }

    @GuardedBy("mLock")
    private void enqueueNotifyStandbyPortsChangedLocked() {
        if (DEBUG) {
            Slog.d(TAG, "enqueueNotifyStandbyPortsChangedLocked");
        }

        final Message msg = mHandler.obtainMessage(MSG_NOTIFY_STANDBY_PORTS_CHANGED);
        mHandler.sendMessageAtTime(msg, mClock.uptimeMillis());
    }

    private void notifyStandbyPortsChanged() {
        if (DEBUG) {
            Slog.d(TAG, "notifyStandbyPortsChanged");
        }

        final Intent intent = new Intent(PowerManager.ACTION_LOW_POWER_STANDBY_PORTS_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL,
                Manifest.permission.MANAGE_LOW_POWER_STANDBY);
    }

    /**
     * Class that is used to read device config for low power standby configuration.
     */
    @VisibleForTesting
    public static class DeviceConfigWrapper {
        public static final String NAMESPACE = "low_power_standby";
        public static final String FEATURE_FLAG_ENABLE_POLICY = "enable_policy";
        public static final String FEATURE_FLAG_ENABLE_STANDBY_PORTS = "enable_standby_ports";

        /**
         * Returns true if custom policies are enabled.
         * Otherwise, returns false, and the default policy will be used.
         */
        public boolean enableCustomPolicy() {
            return DeviceConfig.getBoolean(NAMESPACE, FEATURE_FLAG_ENABLE_POLICY, true);
        }

        /**
         * Returns true if standby ports are enabled.
         * Otherwise, returns false, and {@link #getActiveStandbyPorts()} will always be empty.
         */
        public boolean enableStandbyPorts() {
            return DeviceConfig.getBoolean(NAMESPACE, FEATURE_FLAG_ENABLE_STANDBY_PORTS, true);
        }

        /**
         * Registers a DeviceConfig update listener.
         */
        public void registerPropertyUpdateListener(
                @NonNull Executor executor,
                @NonNull DeviceConfig.OnPropertiesChangedListener onPropertiesChangedListener) {
            DeviceConfig.addOnPropertiesChangedListener(NAMESPACE, executor,
                    onPropertiesChangedListener);
        }
    }

    private final class LocalService extends LowPowerStandbyControllerInternal {
        @Override
        public void addToAllowlist(int uid, @LowPowerStandbyAllowedReason int allowedReason) {
            addToAllowlistInternal(uid, allowedReason);
        }

        @Override
        public void removeFromAllowlist(int uid, @LowPowerStandbyAllowedReason int allowedReason) {
            removeFromAllowlistInternal(uid, allowedReason);
        }
    }

    private final class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            onSettingsChanged();
        }
    }

    final class TempAllowlistChangeListener implements
            PowerAllowlistInternal.TempAllowlistChangeListener {
        @Override
        public void onAppAdded(int uid) {
            addToAllowlistInternal(uid, LOW_POWER_STANDBY_ALLOWED_REASON_TEMP_POWER_SAVE_ALLOWLIST);
        }

        @Override
        public void onAppRemoved(int uid) {
            removeFromAllowlistInternal(uid,
                    LOW_POWER_STANDBY_ALLOWED_REASON_TEMP_POWER_SAVE_ALLOWLIST);
        }
    }

    final class PhoneCallServiceTracker extends IForegroundServiceObserver.Stub {
        private boolean mRegistered = false;
        private final SparseBooleanArray mUidsWithPhoneCallService = new SparseBooleanArray();

        public void register() {
            if (mRegistered) {
                return;
            }
            try {
                mActivityManager.get().registerForegroundServiceObserver(this);
                mRegistered = true;
            } catch (RemoteException e) {
                // call within system server
            }
        }

        @Override
        public void onForegroundStateChanged(IBinder serviceToken, String packageName,
                int userId, boolean isForeground) {
            try {
                final int uid = mContext.getPackageManager()
                        .getPackageUidAsUser(packageName, userId);
                final Message message =
                        mHandler.obtainMessage(MSG_FOREGROUND_SERVICE_STATE_CHANGED, uid, 0);
                mHandler.sendMessageAtTime(message, mClock.uptimeMillis());
            } catch (PackageManager.NameNotFoundException e) {
                if (DEBUG) {
                    Slog.d(TAG, "onForegroundStateChanged: Unknown package: " + packageName
                            + ", userId=" + userId);
                }
            }
        }

        public void foregroundServiceStateChanged(int uid) {
            if (DEBUG) {
                Slog.d(TAG, "foregroundServiceStateChanged: uid=" + uid);
            }

            final boolean hadPhoneCallService = mUidsWithPhoneCallService.get(uid);
            final boolean hasPhoneCallService =
                    mActivityManagerInternal.hasRunningForegroundService(uid,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL);

            if (DEBUG) {
                Slog.d(TAG, "uid=" + uid + ", hasPhoneCallService=" + hasPhoneCallService
                        + ", hadPhoneCallService=" + hadPhoneCallService);
            }

            if (hasPhoneCallService == hadPhoneCallService) {
                return;
            }

            if (hasPhoneCallService) {
                mUidsWithPhoneCallService.append(uid, true);
                uidStartedPhoneCallService(uid);
            } else {
                mUidsWithPhoneCallService.delete(uid);
                uidStoppedPhoneCallService(uid);
            }
        }

        private void uidStartedPhoneCallService(int uid) {
            if (DEBUG) {
                Slog.d(TAG, "FGS of type phoneCall started: uid=" + uid);
            }
            addToAllowlistInternal(uid, LOW_POWER_STANDBY_ALLOWED_REASON_ONGOING_CALL);
        }

        private void uidStoppedPhoneCallService(int uid) {
            if (DEBUG) {
                Slog.d(TAG, "FGSs of type phoneCall stopped: uid=" + uid);
            }
            removeFromAllowlistInternal(uid, LOW_POWER_STANDBY_ALLOWED_REASON_ONGOING_CALL);
        }
    }
}
