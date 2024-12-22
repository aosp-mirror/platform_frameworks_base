/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.os;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemService;
import android.app.ActivityThread;
import android.content.Context;
import android.util.Log;
import android.view.HapticFeedbackConstants;

/**
 * Provides access to all vibrators from the device, as well as the ability to run them
 * in a synchronized fashion.
 * <p>
 * If your process exits, any vibration you started will stop.
 * </p>
 */
@SystemService(Context.VIBRATOR_MANAGER_SERVICE)
public abstract class VibratorManager {
    private static final String TAG = "VibratorManager";

    /** @hide */
    protected final String mPackageName;

    /**
     * @hide to prevent subclassing from outside of the framework
     */
    public VibratorManager() {
        mPackageName = ActivityThread.currentPackageName();
    }

    /**
     * @hide to prevent subclassing from outside of the framework
     */
    protected VibratorManager(Context context) {
        mPackageName = context.getOpPackageName();
    }

    /**
     * List all available vibrator ids, returning a possible empty list.
     *
     * @return An array containing the ids of the vibrators available on the device.
     */
    @NonNull
    public abstract int[] getVibratorIds();

    /**
     * Retrieve a single vibrator by id.
     *
     * @param vibratorId The id of the vibrator to be retrieved.
     * @return The vibrator with given {@code vibratorId}, never null.
     */
    @NonNull
    public abstract Vibrator getVibrator(int vibratorId);

    /**
     * Returns the default Vibrator for the device.
     */
    @NonNull
    public abstract Vibrator getDefaultVibrator();

    /**
     * Configure an always-on haptics effect.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.VIBRATE_ALWAYS_ON)
    public boolean setAlwaysOnEffect(int uid, String opPkg, int alwaysOnId,
            @Nullable CombinedVibration effect, @Nullable VibrationAttributes attributes) {
        Log.w(TAG, "Always-on effects aren't supported");
        return false;
    }

    /**
     * Vibrate with a given combination of effects.
     *
     * <p>
     * Pass in a {@link CombinedVibration} representing a combination of {@link
     * VibrationEffect VibrationEffects} to be played on one or more vibrators.
     * </p>
     *
     * <p>The app should be in foreground for the vibration to happen.</p>
     *
     * @param effect a combination of effects to be performed by one or more vibrators.
     */
    @RequiresPermission(android.Manifest.permission.VIBRATE)
    public final void vibrate(@NonNull CombinedVibration effect) {
        vibrate(effect, null);
    }

    /**
     * Vibrate with a given combination of effects.
     *
     * <p>
     * Pass in a {@link CombinedVibration} representing a combination of {@link
     * VibrationEffect} to be played on one or more vibrators.
     * </p>
     *
     * <p>The app should be in foreground for the vibration to happen. Background apps should
     * specify a ringtone, notification or alarm usage in order to vibrate.</p>
     *
     * @param effect a combination of effects to be performed by one or more vibrators.
     * @param attributes {@link VibrationAttributes} corresponding to the vibration. For example,
     *                   specify {@link VibrationAttributes#USAGE_ALARM} for alarm vibrations or
     *                   {@link VibrationAttributes#USAGE_RINGTONE} for vibrations associated with
     *                   incoming calls.
     */
    @RequiresPermission(android.Manifest.permission.VIBRATE)
    public final void vibrate(@NonNull CombinedVibration effect,
            @Nullable VibrationAttributes attributes) {
        vibrate(Process.myUid(), mPackageName, effect, null, attributes);
    }

    /**
     * Like {@link #vibrate(CombinedVibration, VibrationAttributes)}, but allows the
     * caller to specify the vibration is owned by someone else and set reason for vibration.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.VIBRATE)
    public abstract void vibrate(int uid, String opPkg, @NonNull CombinedVibration effect,
            String reason, @Nullable VibrationAttributes attributes);

    /**
     * Performs a haptic feedback.
     *
     * @param constant the ID of the requested haptic feedback. Should be one of the constants
     *          defined in {@link HapticFeedbackConstants}.
     * @param reason the reason for this haptic feedback.
     * @param flags Additional flags as per {@link HapticFeedbackConstants}.
     * @param privFlags Additional private flags as per {@link HapticFeedbackConstants}.
     * @hide
     */
    public void performHapticFeedback(int constant, String reason,
            @HapticFeedbackConstants.Flags int flags,
            @HapticFeedbackConstants.PrivateFlags int privFlags) {
        Log.w(TAG, "performHapticFeedback is not supported");
    }

    /**
     * Performs a haptic feedback. Similar to {@link #performHapticFeedback} but also take input
     * into consideration.
     *
     * @param constant      the ID of the requested haptic feedback. Should be one of the constants
     *                      defined in {@link HapticFeedbackConstants}.
     * @param inputDeviceId the integer id of the input device that customizes the haptic feedback
     *                      corresponding to the {@code constant}.
     * @param inputSource   the {@link InputDevice.Source} that customizes the haptic feedback
     *                      corresponding to the {@code constant}.
     * @param reason        the reason for this haptic feedback.
     * @param flags         Additional flags as per {@link HapticFeedbackConstants}.
     * @param privFlags     Additional private flags as per {@link HapticFeedbackConstants}.
     * @hide
     */
    public void performHapticFeedbackForInputDevice(int constant, int inputDeviceId,
            int inputSource, String reason, @HapticFeedbackConstants.Flags int flags,
            @HapticFeedbackConstants.PrivateFlags int privFlags) {
        Log.w(TAG, "performHapticFeedbackForInputDevice is not supported");
    }

    /**
     * Turn all the vibrators off.
     */
    @RequiresPermission(android.Manifest.permission.VIBRATE)
    public abstract void cancel();

    /**
     * Cancel specific types of ongoing vibrations.
     *
     * @param usageFilter The type of vibration to be cancelled, represented as a bitwise
     *                    combination of {@link VibrationAttributes.Usage} values.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.VIBRATE)
    public abstract void cancel(int usageFilter);
}
