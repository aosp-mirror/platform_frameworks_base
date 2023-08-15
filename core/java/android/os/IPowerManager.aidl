/* //device/java/android/android/os/IPowerManager.aidl
**
** Copyright 2007, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package android.os;

import android.os.BatterySaverPolicyConfig;
import android.os.ParcelDuration;
import android.os.PowerSaveState;
import android.os.WorkSource;
import android.os.IWakeLockCallback;

/** @hide */

interface IPowerManager
{
    void acquireWakeLock(IBinder lock, int flags, String tag, String packageName, in WorkSource ws,
            String historyTag, int displayId, IWakeLockCallback callback);
    void acquireWakeLockWithUid(IBinder lock, int flags, String tag, String packageName,
            int uidtoblame, int displayId, IWakeLockCallback callback);
    @UnsupportedAppUsage
    void releaseWakeLock(IBinder lock, int flags);
    void updateWakeLockUids(IBinder lock, in int[] uids);
    oneway void setPowerBoost(int boost, int durationMs);
    oneway void setPowerMode(int mode, boolean enabled);

    // Functionally identical to setPowerMode, but returns whether the call was successful
    boolean setPowerModeChecked(int mode, boolean enabled);

    void updateWakeLockWorkSource(IBinder lock, in WorkSource ws, String historyTag);
    void updateWakeLockCallback(IBinder lock, IWakeLockCallback callback);
    boolean isWakeLockLevelSupported(int level);

    void userActivity(int displayId, long time, int event, int flags);
    void wakeUp(long time, int reason, String details, String opPackageName);
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void goToSleep(long time, int reason, int flags);
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void goToSleepWithDisplayId(int displayId, long time, int reason, int flags);
    @UnsupportedAppUsage(maxTargetSdk = 28)
    void nap(long time);
    float getBrightnessConstraint(int constraint);
    @UnsupportedAppUsage
    boolean isInteractive();
    boolean isDisplayInteractive(int displayId);
    boolean areAutoPowerSaveModesEnabled();
    boolean isPowerSaveMode();
    PowerSaveState getPowerSaveState(int serviceType);
    boolean setPowerSaveModeEnabled(boolean mode);
    boolean isBatterySaverSupported();
    BatterySaverPolicyConfig getFullPowerSavePolicy();
    boolean setFullPowerSavePolicy(in BatterySaverPolicyConfig config);
    boolean setDynamicPowerSaveHint(boolean powerSaveHint, int disableThreshold);
    boolean setAdaptivePowerSavePolicy(in BatterySaverPolicyConfig config);
    boolean setAdaptivePowerSaveEnabled(boolean enabled);
    int getPowerSaveModeTrigger();
    void setBatteryDischargePrediction(in ParcelDuration timeRemaining, boolean isCustomized);
    ParcelDuration getBatteryDischargePrediction();
    boolean isBatteryDischargePredictionPersonalized();
    boolean isDeviceIdleMode();
    boolean isLightDeviceIdleMode();
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(anyOf = { android.Manifest.permission.MANAGE_LOW_POWER_STANDBY, android.Manifest.permission.DEVICE_POWER })")
    boolean isLowPowerStandbySupported();
    boolean isLowPowerStandbyEnabled();
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(anyOf = { android.Manifest.permission.MANAGE_LOW_POWER_STANDBY, android.Manifest.permission.DEVICE_POWER })")
    void setLowPowerStandbyEnabled(boolean enabled);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(anyOf = { android.Manifest.permission.MANAGE_LOW_POWER_STANDBY, android.Manifest.permission.DEVICE_POWER })")
    void setLowPowerStandbyActiveDuringMaintenance(boolean activeDuringMaintenance);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(anyOf = { android.Manifest.permission.MANAGE_LOW_POWER_STANDBY, android.Manifest.permission.DEVICE_POWER })")
    void forceLowPowerStandbyActive(boolean active);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(anyOf = { android.Manifest.permission.MANAGE_LOW_POWER_STANDBY, android.Manifest.permission.DEVICE_POWER })")
    void setLowPowerStandbyPolicy(in @nullable LowPowerStandbyPolicy policy);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(anyOf = { android.Manifest.permission.MANAGE_LOW_POWER_STANDBY, android.Manifest.permission.DEVICE_POWER })")
    LowPowerStandbyPolicy getLowPowerStandbyPolicy();
    boolean isExemptFromLowPowerStandby();
    boolean isReasonAllowedInLowPowerStandby(int reason);
    boolean isFeatureAllowedInLowPowerStandby(String feature);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.SET_LOW_POWER_STANDBY_PORTS)")
    void acquireLowPowerStandbyPorts(in IBinder token, in List<LowPowerStandbyPortDescription> ports);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.SET_LOW_POWER_STANDBY_PORTS)")
    void releaseLowPowerStandbyPorts(in IBinder token);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(anyOf = { android.Manifest.permission.MANAGE_LOW_POWER_STANDBY, android.Manifest.permission.DEVICE_POWER })")
    List<LowPowerStandbyPortDescription> getActiveLowPowerStandbyPorts();

    parcelable LowPowerStandbyPolicy {
        String identifier;
        List<String> exemptPackages;
        int allowedReasons;
        List<String> allowedFeatures;
    }

    parcelable LowPowerStandbyPortDescription {
        int protocol;
        int portMatcher;
        int portNumber;
        @nullable byte[] localAddress;
    }

    @UnsupportedAppUsage
    void reboot(boolean confirm, String reason, boolean wait);
    void rebootSafeMode(boolean confirm, boolean wait);
    void shutdown(boolean confirm, String reason, boolean wait);
    void crash(String message);
    int getLastShutdownReason();
    int getLastSleepReason();

    void setStayOnSetting(int val);
    void boostScreenBrightness(long time);

    // Do not use, will be deprecated soon.  b/151831987
    oneway void acquireWakeLockAsync(IBinder lock, int flags, String tag, String packageName,
            in WorkSource ws, String historyTag);
    oneway void releaseWakeLockAsync(IBinder lock, int flags);
    oneway void updateWakeLockUidsAsync(IBinder lock, in int[] uids);

    // --- deprecated ---
    boolean isScreenBrightnessBoosted();

    // sets the attention light (used by phone app only)
    void setAttentionLight(boolean on, int color);

    // controls whether PowerManager should doze after the screen turns off or not
    void setDozeAfterScreenOff(boolean on);
    // returns whether ambient display is available on the device.
    boolean isAmbientDisplayAvailable();
    // suppresses the current ambient display configuration and disables ambient display.
    void suppressAmbientDisplay(String token, boolean suppress);
    // returns whether ambient display is suppressed by the calling app with the given token.
    boolean isAmbientDisplaySuppressedForToken(String token);
    // returns whether ambient display is suppressed by any app with any token.
    boolean isAmbientDisplaySuppressed();
    // returns whether ambient display is suppressed by the given app with the given token.
    boolean isAmbientDisplaySuppressedForTokenByApp(String token, int appUid);

    // Forces the system to suspend even if there are held wakelocks.
    boolean forceSuspend();

    const int LOCATION_MODE_NO_CHANGE = 0;
    const int LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF = 1;
    const int LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF = 2;
    const int LOCATION_MODE_FOREGROUND_ONLY = 3;
    const int LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF = 4;
    const int MIN_LOCATION_MODE = 0;
    const int MAX_LOCATION_MODE = 4;

    const int GO_TO_SLEEP_REASON_MIN = 0;
    const int GO_TO_SLEEP_REASON_APPLICATION = 0;
    const int GO_TO_SLEEP_REASON_TIMEOUT = 2;
    const int GO_TO_SLEEP_REASON_LID_SWITCH = 3;
    const int GO_TO_SLEEP_REASON_POWER_BUTTON = 4;
    const int GO_TO_SLEEP_REASON_HDMI = 5;
    const int GO_TO_SLEEP_REASON_SLEEP_BUTTON = 6;
    const int GO_TO_SLEEP_REASON_ACCESSIBILITY = 7;
    const int GO_TO_SLEEP_REASON_FORCE_SUSPEND = 8;
    const int GO_TO_SLEEP_REASON_INATTENTIVE = 9;
    const int GO_TO_SLEEP_REASON_QUIESCENT = 10;
    const int GO_TO_SLEEP_REASON_MAX = 10;
    const int GO_TO_SLEEP_FLAG_NO_DOZE = 1 << 0;

}
