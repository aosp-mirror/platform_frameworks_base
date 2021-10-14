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
import android.annotation.FloatRange;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.app.ActivityThread;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.hardware.vibrator.IVibrator;
import android.media.AudioAttributes;
import android.util.Log;
import android.util.Range;

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

    /**
     * Vibration effect support: unknown
     *
     * The hardware doesn't report it's supported effects, so we can't determine whether the
     * effect is supported or not.
     */
    public static final int VIBRATION_EFFECT_SUPPORT_UNKNOWN = 0;

    /**
     * Vibration effect support: supported
     *
     * This effect is supported by the underlying hardware.
     */
    public static final int VIBRATION_EFFECT_SUPPORT_YES = 1;

    /**
     * Vibration effect support: unsupported
     *
     * This effect is <b>not</b> supported by the underlying hardware.
     */
    public static final int VIBRATION_EFFECT_SUPPORT_NO = 2;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"VIBRATION_EFFECT_SUPPORT_"}, value = {
            VIBRATION_EFFECT_SUPPORT_UNKNOWN,
            VIBRATION_EFFECT_SUPPORT_YES,
            VIBRATION_EFFECT_SUPPORT_NO,
    })
    public @interface VibrationEffectSupport {}

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
    private float mHapticChannelMaxVibrationAmplitude;

    /**
     * @hide to prevent subclassing from outside of the framework
     */
    @UnsupportedAppUsage
    public Vibrator() {
        mPackageName = ActivityThread.currentPackageName();
        final Context ctx = ActivityThread.currentActivityThread().getSystemContext();
        loadVibrationConfig(ctx);
    }

    /**
     * @hide to prevent subclassing from outside of the framework
     */
    protected Vibrator(Context context) {
        mPackageName = context.getOpPackageName();
        loadVibrationConfig(context);
    }

    private void loadVibrationConfig(Context context) {
        mDefaultHapticFeedbackIntensity = loadDefaultIntensity(context,
                com.android.internal.R.integer.config_defaultHapticFeedbackIntensity);
        mDefaultNotificationVibrationIntensity = loadDefaultIntensity(context,
                com.android.internal.R.integer.config_defaultNotificationVibrationIntensity);
        mDefaultRingVibrationIntensity = loadDefaultIntensity(context,
                com.android.internal.R.integer.config_defaultRingVibrationIntensity);
        mHapticChannelMaxVibrationAmplitude = loadFloat(context,
                com.android.internal.R.dimen.config_hapticChannelMaxVibrationAmplitude, 0);
    }

    private int loadDefaultIntensity(Context ctx, int resId) {
        return ctx != null ? ctx.getResources().getInteger(resId) : VIBRATION_INTENSITY_MEDIUM;
    }

    private float loadFloat(Context ctx, int resId, float defaultValue) {
        return ctx != null ? ctx.getResources().getFloat(resId) : defaultValue;
    }

    /** @hide */
    protected VibratorInfo getInfo() {
        return VibratorInfo.EMPTY_VIBRATOR_INFO;
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
     * Return the ID of this vibrator.
     *
     * @return A non-negative integer representing the id of the vibrator controlled by this
     * service, or -1 this service is not attached to any physical vibrator.
     */
    public int getId() {
        return getInfo().getId();
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
     * Check whether the vibrator has independent frequency control.
     *
     * @return True if the hardware can control the frequency of the vibrations, otherwise false.
     * @hide
     */
    public boolean hasFrequencyControl() {
        // We currently can only control frequency of the vibration using the compose PWLE method.
        return getInfo().hasCapability(
                IVibrator.CAP_FREQUENCY_CONTROL | IVibrator.CAP_COMPOSE_PWLE_EFFECTS);
    }

    /**
     * Check whether the vibrator can be controlled by an external service with the
     * {@link IExternalVibratorService}.
     *
     * @return True if the hardware can be controlled by an external service, otherwise false.
     * @hide
     */
    public boolean hasExternalControl() {
        return getInfo().hasCapability(IVibrator.CAP_EXTERNAL_CONTROL);
    }

    /**
     * Gets the resonant frequency of the vibrator.
     *
     * @return the resonant frequency of the vibrator, or {@link Float#NaN NaN} if it's unknown or
     * this vibrator is a composite of multiple physical devices.
     * @hide
     */
    public float getResonantFrequency() {
        return getInfo().getResonantFrequency();
    }

    /**
     * Gets the <a href="https://en.wikipedia.org/wiki/Q_factor">Q factor</a> of the vibrator.
     *
     * @return the Q factor of the vibrator, or {@link Float#NaN NaN} if it's unknown or
     *         this vibrator is a composite of multiple physical devices.
     * @hide
     */
    public float getQFactor() {
        return getInfo().getQFactor();
    }

    /**
     * Return a range of relative frequency values supported by the vibrator.
     *
     * <p>These values can be used to create waveforms that controls the vibration frequency via
     * {@link VibrationEffect.WaveformBuilder}.
     *
     * @return A range of relative frequency values supported. The range will always contain the
     * value 0, representing the device resonant frequency. Devices without frequency control will
     * return the range [0,0]. Devices with frequency control will always return a range containing
     * the safe range [-1, 1].
     * @hide
     */
    public Range<Float> getRelativeFrequencyRange() {
        return getInfo().getFrequencyRange();
    }

    /**
     * Return the maximum amplitude the vibrator can play at given relative frequency.
     *
     * <p>Devices without frequency control will return 1 for the input zero (resonant frequency),
     * and 0 to any other input.
     *
     * <p>Devices with frequency control will return the supported value, for input in
     * {@link #getRelativeFrequencyRange()}, and 0 for any other input.
     *
     * <p>These values can be used to create waveforms that plays vibrations outside the resonant
     * frequency via {@link VibrationEffect.WaveformBuilder}.
     *
     * @return a value in [0,1] representing the maximum amplitude the device can play at given
     * relative frequency.
     * @hide
     */
    @FloatRange(from = 0, to = 1)
    public float getMaximumAmplitude(float relativeFrequency) {
        return getInfo().getMaxAmplitude(relativeFrequency);
    }

    /**
     * Return the maximum amplitude the vibrator can play using the audio haptic channels.
     *
     * <p>This is a positive value, or {@link Float#NaN NaN} if it's unknown. If this returns a
     * positive value <code>maxAmplitude</code>, then the signals from the haptic channels of audio
     * tracks should be in the range <code>[-maxAmplitude, maxAmplitude]</code>.
     *
     * @return a positive value representing the maximum absolute value the device can play signals
     * from audio haptic channels, or {@link Float#NaN NaN} if it's unknown.
     * @hide
     */
    public float getHapticChannelMaximumAmplitude() {
        if (mHapticChannelMaxVibrationAmplitude <= 0) {
            return Float.NaN;
        }
        return mHapticChannelMaxVibrationAmplitude;
    }

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
     * <p>The app should be in foreground for the vibration to happen.</p>
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
     * <p>The app should be in foreground for the vibration to happen. Background apps should
     * specify a ringtone, notification or alarm usage in order to vibrate.</p>
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
     * <p>The app should be in foreground for the vibration to happen.</p>
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
     * <p>The app should be in foreground for the vibration to happen. Background apps should
     * specify a ringtone, notification or alarm usage in order to vibrate.</p>
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

    /**
     * Vibrate with a given effect.
     *
     * <p>The app should be in foreground for the vibration to happen.</p>
     *
     * @param vibe {@link VibrationEffect} describing the vibration to be performed.
     */
    @RequiresPermission(android.Manifest.permission.VIBRATE)
    public void vibrate(VibrationEffect vibe) {
        vibrate(vibe, null);
    }

    /**
     * Vibrate with a given effect.
     *
     * <p>The app should be in foreground for the vibration to happen. Background apps should
     * specify a ringtone, notification or alarm usage in order to vibrate.</p>
     *
     * @param vibe       {@link VibrationEffect} describing the vibration to be performed.
     * @param attributes {@link AudioAttributes} corresponding to the vibration. For example,
     *                   specify {@link AudioAttributes#USAGE_ALARM} for alarm vibrations or
     *                   {@link AudioAttributes#USAGE_NOTIFICATION_RINGTONE} for
     *                   vibrations associated with incoming calls.
     */
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
    public final void vibrate(int uid, String opPkg, VibrationEffect vibe,
            String reason, AudioAttributes attributes) {
        if (attributes == null) {
            attributes = new AudioAttributes.Builder().build();
        }
        VibrationAttributes attr = new VibrationAttributes.Builder(attributes, vibe).build();
        vibrate(uid, opPkg, vibe, reason, attr);
    }

    /**
     * Like {@link #vibrate(int, String, VibrationEffect, String, AudioAttributes)}, but allows the
     * caller to specify {@link VibrationAttributes} instead of {@link AudioAttributes}.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.VIBRATE)
    public abstract void vibrate(int uid, String opPkg, @NonNull VibrationEffect vibe,
            String reason, @NonNull VibrationAttributes attributes);

    /**
     * Query whether the vibrator supports the given effects.
     *
     * Not all hardware reports its effect capabilities, so the system may not necessarily know
     * whether an effect is supported or not.
     *
     * The returned array will be the same length as the query array and the value at a given index
     * will contain {@link #VIBRATION_EFFECT_SUPPORT_YES} if the effect at that same index in the
     * querying array is supported, {@link #VIBRATION_EFFECT_SUPPORT_NO} if it isn't supported, or
     * {@link #VIBRATION_EFFECT_SUPPORT_UNKNOWN} if the system can't determine whether it's
     * supported or not.
     *
     * @param effectIds Which effects to query for.
     * @return An array containing the systems current knowledge about whether the given effects
     * are supported or not.
     */
    @NonNull
    @VibrationEffectSupport
    public int[] areEffectsSupported(
            @NonNull @VibrationEffect.EffectType int... effectIds) {
        VibratorInfo info = getInfo();
        int[] supported = new int[effectIds.length];
        for (int i = 0; i < effectIds.length; i++) {
            supported[i] = info.isEffectSupported(effectIds[i]);
        }
        return supported;
    }

    /**
     * Query whether the vibrator supports all of the given effects.
     *
     * Not all hardware reports its effect capabilities, so the system may not necessarily know
     * whether an effect is supported or not.
     *
     * If the result is {@link #VIBRATION_EFFECT_SUPPORT_YES}, all effects in the query are
     * supported by the hardware.
     *
     * If the result is {@link #VIBRATION_EFFECT_SUPPORT_NO}, at least one of the effects in the
     * query is not supported.
     *
     * If the result is {@link #VIBRATION_EFFECT_SUPPORT_UNKNOWN}, the system doesn't know whether
     * all of the effects are supported. It may support any or all of the queried effects,
     * but there's no way to programmatically know whether a {@link #vibrate} call will successfully
     * cause a vibration. It's guaranteed, however, that none of the queried effects are
     * definitively unsupported by the hardware.
     *
     * @param effectIds Which effects to query for.
     * @return Whether all of the effects are supported.
     */
    @VibrationEffectSupport
    public final int areAllEffectsSupported(
            @NonNull @VibrationEffect.EffectType int... effectIds) {
        int support = VIBRATION_EFFECT_SUPPORT_YES;
        for (int supported : areEffectsSupported(effectIds)) {
            if (supported == VIBRATION_EFFECT_SUPPORT_NO) {
                return VIBRATION_EFFECT_SUPPORT_NO;
            } else if (supported == VIBRATION_EFFECT_SUPPORT_UNKNOWN) {
                support = VIBRATION_EFFECT_SUPPORT_UNKNOWN;
            }
        }
        return support;
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
            @NonNull @VibrationEffect.Composition.PrimitiveType int... primitiveIds) {
        VibratorInfo info = getInfo();
        boolean[] supported = new boolean[primitiveIds.length];
        for (int i = 0; i < primitiveIds.length; i++) {
            supported[i] = info.isPrimitiveSupported(primitiveIds[i]);
        }
        return supported;
    }

    /**
     * Query whether the vibrator supports all of the given primitives.
     *
     * @param primitiveIds Which primitives to query for.
     * @return Whether primitives effects are supported.
     */
    public final boolean areAllPrimitivesSupported(
            @NonNull @VibrationEffect.Composition.PrimitiveType int... primitiveIds) {
        for (boolean supported : arePrimitivesSupported(primitiveIds)) {
            if (!supported) {
                return false;
            }
        }
        return true;
    }

    /**
     * Query the estimated durations of the given primitives.
     *
     * <p>The returned array will be the same length as the query array and the value at a given
     * index will contain the duration in milliseconds of the effect at the same index in the
     * querying array.
     *
     * <p>The duration will be positive for primitives that are supported and zero for the
     * unsupported ones, in correspondence with {@link #arePrimitivesSupported(int...)}.
     *
     * @param primitiveIds Which primitives to query for.
     * @return The duration of each primitive, with zeroes for primitives that are not supported.
     */
    @NonNull
    public int[] getPrimitiveDurations(
            @NonNull @VibrationEffect.Composition.PrimitiveType int... primitiveIds) {
        VibratorInfo info = getInfo();
        int[] durations = new int[primitiveIds.length];
        for (int i = 0; i < primitiveIds.length; i++) {
            durations[i] = info.getPrimitiveDuration(primitiveIds[i]);
        }
        return durations;
    }

    /**
     * Turn the vibrator off.
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

    /**
     * Check whether the vibrator is vibrating.
     *
     * @return True if the hardware is vibrating, otherwise false.
     * @hide
     */
    @SystemApi
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
    @RequiresPermission(android.Manifest.permission.ACCESS_VIBRATOR_STATE)
    public void removeVibratorStateListener(@NonNull OnVibratorStateChangedListener listener) {
    }
}
