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
import android.os.PowerSaveState;
import android.os.WorkSource;

/** @hide */

interface IPowerManager
{
    // WARNING: When methods are inserted or deleted, the transaction IDs in
    // frameworks/native/include/powermanager/IPowerManager.h must be updated to match the order in this file.
    //
    // When a method's argument list is changed, BnPowerManager's corresponding serialization code (if any) in
    // frameworks/native/services/powermanager/IPowerManager.cpp must be updated.
    void acquireWakeLock(IBinder lock, int flags, String tag, String packageName, in WorkSource ws,
            String historyTag);
    void acquireWakeLockWithUid(IBinder lock, int flags, String tag, String packageName,
            int uidtoblame);
    @UnsupportedAppUsage
    void releaseWakeLock(IBinder lock, int flags);
    void updateWakeLockUids(IBinder lock, in int[] uids);
    oneway void powerHint(int hintId, int data);
    oneway void setPowerBoost(int boost, int durationMs);
    oneway void setPowerMode(int mode, boolean enabled);

    // Functionally identical to setPowerMode, but returns whether the call was successful
    boolean setPowerModeChecked(int mode, boolean enabled);

    void updateWakeLockWorkSource(IBinder lock, in WorkSource ws, String historyTag);
    boolean isWakeLockLevelSupported(int level);

    @UnsupportedAppUsage
    void userActivity(long time, int event, int flags);
    void wakeUp(long time, int reason, String details, String opPackageName);
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void goToSleep(long time, int reason, int flags);
    @UnsupportedAppUsage(maxTargetSdk = 28)
    void nap(long time);
    float getBrightnessConstraint(int constraint);
    @UnsupportedAppUsage
    boolean isInteractive();
    boolean isPowerSaveMode();
    PowerSaveState getPowerSaveState(int serviceType);
    boolean setPowerSaveModeEnabled(boolean mode);
    boolean setDynamicPowerSaveHint(boolean powerSaveHint, int disableThreshold);
    boolean setAdaptivePowerSavePolicy(in BatterySaverPolicyConfig config);
    boolean setAdaptivePowerSaveEnabled(boolean enabled);
    int getPowerSaveModeTrigger();
    boolean isDeviceIdleMode();
    boolean isLightDeviceIdleMode();

    @UnsupportedAppUsage
    void reboot(boolean confirm, String reason, boolean wait);
    void rebootSafeMode(boolean confirm, boolean wait);
    void shutdown(boolean confirm, String reason, boolean wait);
    void crash(String message);
    int getLastShutdownReason();
    int getLastSleepReason();

    void setStayOnSetting(int val);
    void boostScreenBrightness(long time);

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
}
