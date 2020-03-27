/*
 * Copyright (C) 2006 The Android Open Source Project
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

import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.app.ActivityThread;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.media.AudioAttributes;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.Executor;

/**
 * Class that operates the vibrator on the device.
 * <p>
 * If your process exits, any vibration you started will stop.
 * </p>
 */
@SystemService(Context.VIBRATOR_SERVICE)
public abstract class Vibrator {
    private static final String TAG = "Vibrator";

    /**
     * Vibration intensity: no vibrations.
     *
     * @hide
     */
    public static final int VIBRATION_INTENSITY_OFF = 0;

    /**
     * Vibration intensity: low.
     *
     * @hide
     */
    public static final int VIBRATION_INTENSITY_LOW = 1;

    /**
     * Vibration intensity: medium.
     *
     * @hide
     */
    public static final int VIBRATION_INTENSITY_MEDIUM = 2;

    /**
     * Vibration intensity: high.
     *
     * @hide
     */
    public static final int VIBRATION_INTENSITY_HIGH = 3;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"VIBRATION_INTENSITY_"}, value = {
            VIBRATION_INTENSITY_OFF,
            VIBRATION_INTENSITY_LOW,
            VIBRATION_INTENSITY_MEDIUM,
            VIBRATION_INTENSITY_HIGH
    })
    public @interface VibrationIntensity {
    }

    private final String mPackageName;
    // The default vibration intensity level for haptic feedback.
    @VibrationIntensity
    private int mDefaultHapticFeedbackIntensity;
    // The default vibration intensity level for notifications.
    @VibrationIntensity
    private int mDefaultNotificationVibrationIntensity;
    // The default vibration intensity level for ringtones.
    @VibrationIntensity
    private int mDefaultRingVibrationIntensity;

    /**
     * @hide to prevent subclassing from outside of the framework
     */
    @UnsupportedAppUsage
    public Vibrator() {
        mPackageName = ActivityThread.currentPackageName();
        final Context ctx = ActivityThread.currentActivityThread().getSystemContext();
        loadVibrationIntensities(ctx);
    }

    /**
     * @hide to prevent subclassing from outside of the framework
     */
    protected Vibrator(Context context) {
        mPackageName = context.getOpPackageName();
        loadVibrationIntensities(context);
    }

    private void loadVibrationIntensities(Context context) {
        mDefaultHapticFeedbackIntensity = loadDefaultIntensity(context,
                com.android.internal.R.integer.config_defaultHapticFeedbackIntensity);
        mDefaultNotificationVibrationIntensity = loadDefaultIntensity(context,
                com.android.internal.R.integer.config_defaultNotificationVibrationIntensity);
        mDefaultRingVibrationIntensity = loadDefaultIntensity(context,
                com.android.internal.R.integer.config_defaultRingVibrationIntensity);
    }

    private int loadDefaultIntensity(Context ctx, int resId) {
        return ctx != null ? ctx.getResources().getInteger(resId) : VIBRATION_INTENSITY_MEDIUM;
    }

    /**
     * Get the default vibration intensity for haptic feedback.
     *
     * @hide
     */
    public int getDefaultHapticFeedbackIntensity() {
        return mDefaultHapticFeedbackIntensity;
    }

    /**
     * Get the default vibration intensity for notifications.
     *
     * @hide
     */
    public int getDefaultNotificationVibrationIntensity() {
        return mDefaultNotificationVibrationIntensity;
    }

    /**
     * Get the default vibration intensity for ringtones.
     *
     * @hide
     */
    public int getDefaultRingVibrationIntensity() {
        return mDefaultRingVibrationIntensity;
    }

    /**
     * Check whether the hardware has a vibrator.
     *
     * @return True if the hardware has a vibrator, else false.
     */
    public abstract boolean hasVibrator();

    /**
     * Check whether the vibrator has amplitude control.
     *
     * @return True if the hardware can control the amplitude of the vibrations, otherwise false.
     */
    public abstract boolean hasAmplitudeControl();

    /**
     * Configure an always-on haptics effect.
     *
     * @param alwaysOnId The board-specific always-on ID to configure.
     * @param effect     Vibration effect to assign to always-on id. Passing null will disable it.
     * @param attributes {@link AudioAttributes} corresponding to the vibration. For example,
     *                   specify {@link AudioAttributes#USAGE_ALARM} for alarm vibrations or
     *                   {@link AudioAttributes#USAGE_NOTIFICATION_RINGTONE} for
     *                   vibrations associated with incoming calls. May only be null when effect is
     *                   null.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.VIBRATE_ALWAYS_ON)
    public boolean setAlwaysOnEffect(int alwaysOnId, @Nullable VibrationEffect effect,
            @Nullable AudioAttributes attributes) {
        return setAlwaysOnEffect(Process.myUid(), mPackageName, alwaysOnId, effect, attributes);
    }

    /**
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.VIBRATE_ALWAYS_ON)
    public boolean setAlwaysOnEffect(int uid, String opPkg, int alwaysOnId,
            @Nullable VibrationEffect effect, @Nullable AudioAttributes attributes) {
        Log.w(TAG, "Always-on effects aren't supported");
        return false;
    }

    /**
     * Vibrate constantly for the specified period of time.
     *
     * @param milliseconds The number of milliseconds to vibrate.
     * @deprecated Use {@link #vibrate(VibrationEffect)} instead.
     */
    @Deprecated
    @RequiresPermission(android.Manifest.permission.VIBRATE)
    public void vibrate(long milliseconds) {
        vibrate(milliseconds, null);
    }

    /**
     * Vibrate constantly for the specified period of time.
     *
     * @param milliseconds The number of milliseconds to vibrate.
     * @param attributes   {@link AudioAttributes} corresponding to the vibration. For example,
     *                     specify {@link AudioAttributes#USAGE_ALARM} for alarm vibrations or
     *                     {@link AudioAttributes#USAGE_NOTIFICATION_RINGTONE} for
     *                     vibrations associated with incoming calls.
     * @deprecated Use {@link #vibrate(VibrationEffect, AudioAttributes)} instead.
     */
    @Deprecated
    @RequiresPermission(android.Manifest.permission.VIBRATE)
    public void vibrate(long milliseconds, AudioAttributes attributes) {
        try {
            // This ignores all exceptions to stay compatible with pre-O implementations.
            VibrationEffect effect =
                    VibrationEffect.createOneShot(milliseconds, VibrationEffect.DEFAULT_AMPLITUDE);
            vibrate(effect, attributes);
        } catch (IllegalArgumentException iae) {
            Log.e(TAG, "Failed to create VibrationEffect", iae);
        }
    }

    /**
     * Vibrate with a given pattern.
     *
     * <p>
     * Pass in an array of ints that are the durations for which to turn on or off
     * the vibrator in milliseconds.  The first value indicates the number of milliseconds
     * to wait before turning the vibrator on.  The next value indicates the number of milliseconds
     * for which to keep the vibrator on before turning it off.  Subsequent values alternate
     * between durations in milliseconds to turn the vibrator off or to turn the vibrator on.
     * </p><p>
     * To cause the pattern to repeat, pass the index into the pattern array at which
     * to start the repeat, or -1 to disable repeating.
     * </p>
     *
     * @param pattern an array of longs of times for which to turn the vibrator on or off.
     * @param repeat  the index into pattern at which to repeat, or -1 if
     *                you don't want to repeat.
     * @deprecated Use {@link #vibrate(VibrationEffect)} instead.
     */
    @Deprecated
    @RequiresPermission(android.Manifest.permission.VIBRATE)
    public void vibrate(long[] pattern, int repeat) {
        vibrate(pattern, repeat, null);
    }

    /**
     * Vibrate with a given pattern.
     *
     * <p>
     * Pass in an array of ints that are the durations for which to turn on or off
     * the vibrator in milliseconds.  The first value indicates the number of milliseconds
     * to wait before turning the vibrator on.  The next value indicates the number of milliseconds
     * for which to keep the vibrator on before turning it off.  Subsequent values alternate
     * between durations in milliseconds to turn the vibrator off or to turn the vibrator on.
     * </p><p>
     * To cause the pattern to repeat, pass the index into the pattern array at which
     * to start the repeat, or -1 to disable repeating.
     * </p>
     *
     * @param pattern    an array of longs of times for which to turn the vibrator on or off.
     * @param repeat     the index into pattern at which to repeat, or -1 if
     *                   you don't want to repeat.
     * @param attributes {@link AudioAttributes} corresponding to the vibration. For example,
     *                   specify {@link AudioAttributes#USAGE_ALARM} for alarm vibrations or
     *                   {@link AudioAttributes#USAGE_NOTIFICATION_RINGTONE} for
     *                   vibrations associated with incoming calls.
     * @deprecated Use {@link #vibrate(VibrationEffect, AudioAttributes)} instead.
     */
    @Deprecated
    @RequiresPermission(android.Manifest.permission.VIBRATE)
    public void vibrate(long[] pattern, int repeat, AudioAttributes attributes) {
        // This call needs to continue throwing ArrayIndexOutOfBoundsException but ignore all other
        // exceptions for compatibility purposes
        if (repeat < -1 || repeat >= pattern.length) {
            Log.e(TAG, "vibrate called with repeat index out of bounds" +
                    " (pattern.length=" + pattern.length + ", index=" + repeat + ")");
            throw new ArrayIndexOutOfBoundsException();
        }

        try {
            vibrate(VibrationEffect.createWaveform(pattern, repeat), attributes);
        } catch (IllegalArgumentException iae) {
            Log.e(TAG, "Failed to create VibrationEffect", iae);
        }
    }

    @RequiresPermission(android.Manifest.permission.VIBRATE)
    public void vibrate(VibrationEffect vibe) {
        vibrate(vibe, null);
    }

    @RequiresPermission(android.Manifest.permission.VIBRATE)
    public void vibrate(VibrationEffect vibe, AudioAttributes attributes) {
        vibrate(Process.myUid(), mPackageName, vibe, null, attributes);
    }

    /**
     * Like {@link #vibrate(VibrationEffect, AudioAttributes)}, but allows the
     * caller to specify the vibration is owned by someone else and set reason for vibration.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.VIBRATE)
    public abstract void vibrate(int uid, String opPkg, VibrationEffect vibe,
            String reason, AudioAttributes attributes);

    /**
     * Query whether the vibrator supports the given effects.
     *
     * If the returned array is {@code null}, the hardware doesn't support querying its supported
     * effects. It may support any or all effects, but there's no way to programmatically know
     * whether a {@link #vibrate} call will be successful.
     *
     * If the returned array is non-null, then it will be the same length as the query array and
     * the value at a given index will contain whether the effect at that same index in the
     * querying array is supported or not.
     *
     * @param effectIds Which effects to query for.
     * @return Whether the effects are supported. Null when the hardware doesn't tell us what it
     *         supports.
     */
    @Nullable
    public boolean[] areEffectsSupported(
            @NonNull @VibrationEffect.EffectType int... effectIds) {
        return new boolean[effectIds.length];
    }

    /**
     * Query whether the vibrator supports all of the given effects.
     *
     * If the result is {@code null}, the hardware doesn't support querying its supported
     * effects. It may support any or all effects, but there's no way to programmatically know
     * whether a {@link #vibrate} call will be successful.
     *
     * If the returned array is non-null, then it will return whether all of the effects are
     * supported by the hardware.
     *
     * @param effectIds Which effects to query for.
     * @return Whether the effects are supported. {@code null} when the hardware doesn't tell us
     *         what it supports.
     */
    @Nullable
    public final Boolean areAllEffectsSupported(
            @NonNull @VibrationEffect.EffectType int... effectIds) {
        for (boolean supported : areEffectsSupported(effectIds)) {
            if (!supported) {
                return false;
            }
        }
        return true;
    }


    /**
     * Query whether the vibrator supports the given primitives.
     *
     * The returned array will be the same length as the query array and the value at a given index
     * will contain whether the effect at that same index in the querying array is supported or
     * not.
     *
     * @param primitiveIds Which primitives to query for.
     * @return Whether the primitives are supported.
     */
    @NonNull
    public boolean[] arePrimitivesSupported(
            @NonNull @VibrationEffect.Composition.Primitive int... primitiveIds) {
        return new boolean[primitiveIds.length];
    }

    /**
     * Query whether the vibrator supports all of the given primitives.
     *
     * @param primitiveIds Which primitives to query for.
     * @return Whether primitives effects are supported.
     */
    public final boolean areAllPrimitivesSupported(
            @NonNull @VibrationEffect.Composition.Primitive int... primitiveIds) {
        for (boolean supported : arePrimitivesSupported(primitiveIds)) {
            if (!supported) {
                return false;
            }
        }
        return true;
    }

    /**
     * Turn the vibrator off.
     */
    @RequiresPermission(android.Manifest.permission.VIBRATE)
    public abstract void cancel();

    /**
     * Check whether the vibrator is vibrating.
     *
     * @return True if the hardware is vibrating, otherwise false.
     * @hide
     */
    @SystemApi
    @TestApi
    @RequiresPermission(android.Manifest.permission.ACCESS_VIBRATOR_STATE)
    public boolean isVibrating() {
        return false;
    }

    /**
    * Listener for when the vibrator state has changed.
    *
    * @see #addVibratorStateListener
    * @see #removeVibratorStateListener
    * @hide
    */
    @SystemApi
    @TestApi
    public interface OnVibratorStateChangedListener  {
        /**
         * Called when the vibrator state has changed.
         *
         * @param isVibrating If true, the vibrator has started vibrating. If false,
         *                    it's stopped vibrating.
         */
        void onVibratorStateChanged(boolean isVibrating);
    }

    /**
     * Adds a listener for vibrator state changes. Callbacks will be executed on the main thread.
     * If the listener was previously added and not removed, this call will be ignored.
     *
     * @param listener listener to be added
     * @hide
     */
    @SystemApi
    @TestApi
    @RequiresPermission(android.Manifest.permission.ACCESS_VIBRATOR_STATE)
    public void addVibratorStateListener(@NonNull OnVibratorStateChangedListener listener) {
    }

    /**
     * Adds a listener for vibrator state change. If the listener was previously added and not
     * removed, this call will be ignored.
     *
     * @param listener listener to be added
     * @param executor executor of listener
     * @hide
     */
    @SystemApi
    @TestApi
    @RequiresPermission(android.Manifest.permission.ACCESS_VIBRATOR_STATE)
    public void addVibratorStateListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnVibratorStateChangedListener listener) {
    }

    /**
     * Removes the listener for vibrator state changes. If the listener was not previously
     * registered, this call will do nothing.
     *
     * @param listener listener to be removed
     * @hide
     */
    @SystemApi
    @TestApi
    @RequiresPermission(android.Manifest.permission.ACCESS_VIBRATOR_STATE)
    public void removeVibratorStateListener(@NonNull OnVibratorStateChangedListener listener) {
    }
}
