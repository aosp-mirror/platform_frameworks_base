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
import android.content.res.Resources;
import android.hardware.vibrator.IVibrator;
import android.media.AudioAttributes;
import android.os.vibrator.VibrationConfig;
import android.os.vibrator.VibratorFrequencyProfile;
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
    @TestApi
    public static final int VIBRATION_INTENSITY_OFF = 0;

    /**
     * Vibration intensity: low.
     *
     * @hide
     */
    @TestApi
    public static final int VIBRATION_INTENSITY_LOW = 1;

    /**
     * Vibration intensity: medium.
     *
     * @hide
     */
    @TestApi
    public static final int VIBRATION_INTENSITY_MEDIUM = 2;

    /**
     * Vibration intensity: high.
     *
     * @hide
     */
    @TestApi
    public static final int VIBRATION_INTENSITY_HIGH = 3;

    /**
     * Vibration effect support: unknown
     *
     * <p>The hardware doesn't report its supported effects, so we can't determine whether the
     * effect is supported or not.
     */
    public static final int VIBRATION_EFFECT_SUPPORT_UNKNOWN = 0;

    /**
     * Vibration effect support: supported
     *
     * <p>This effect is supported by the underlying hardware.
     */
    public static final int VIBRATION_EFFECT_SUPPORT_YES = 1;

    /**
     * Vibration effect support: unsupported
     *
     * <p>This effect is <b>not</b> natively supported by the underlying hardware, although
     * the system may still play a fallback vibration.
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
    @Nullable
    private final Resources mResources;

    // This is lazily loaded only for the few clients that need this (e. Settings app).
    @Nullable
    private volatile VibrationConfig mVibrationConfig;

    /**
     * @hide to prevent subclassing from outside of the framework
     */
    @UnsupportedAppUsage
    public Vibrator() {
        mPackageName = ActivityThread.currentPackageName();
        mResources = null;
    }

    /**
     * @hide to prevent subclassing from outside of the framework
     */
    protected Vibrator(Context context) {
        mPackageName = context.getOpPackageName();
        mResources = context.getResources();
    }

    /**
     * Get the info describing this vibrator.
     *
     * @hide
     */
    public VibratorInfo getInfo() {
        return VibratorInfo.EMPTY_VIBRATOR_INFO;
    }

    /** Get the static vibrator configuration from config.xml. */
    private VibrationConfig getConfig() {
        if (mVibrationConfig == null) {
            Resources resources = mResources;
            if (resources == null) {
                final Context ctx = ActivityThread.currentActivityThread().getSystemContext();
                resources = ctx != null ? ctx.getResources() : null;
            }
            // This might be constructed more than once, but it only loads static config data from a
            // xml file, so it would be ok.
            mVibrationConfig = new VibrationConfig(resources);
        }
        return mVibrationConfig;
    }

    /**
     * Get the default vibration intensity for given usage.
     *
     * @hide
     */
    @TestApi
    @VibrationIntensity
    public int getDefaultVibrationIntensity(@VibrationAttributes.Usage int usage) {
        return getConfig().getDefaultVibrationIntensity(usage);
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
     * @return True if the hardware can control the frequency of the vibrations independently of
     * the vibration amplitude, false otherwise.
     * @hide
     */
    @TestApi
    public boolean hasFrequencyControl() {
        return getInfo().hasFrequencyControl();
    }

    /**
     * Checks whether or not the vibrator supports all components of a given {@link VibrationEffect}
     * (i.e. the vibrator can play the given effect as intended).
     *
     * <p>If this method returns {@code true}, then the VibrationEffect should play as expected.
     * If {@code false}, playing the VibrationEffect might still make a vibration, but the vibration
     * may be significantly degraded from the intention.
     *
     * <p>This method aggregates the results of feature check methods such as
     * {@link #hasAmplitudeControl}, {@link #areAllPrimitivesSupported(int...)}, etc, depending
     * on the features that are actually used by the VibrationEffect.
     *
     * @param effect the {@link VibrationEffect} to check if it is supported
     * @return {@code true} if the vibrator can play the given {@code effect} as intended,
     *         {@code false} otherwise.
     *
     * @hide
     */
    public boolean areVibrationFeaturesSupported(@NonNull VibrationEffect effect) {
        return getInfo().areVibrationFeaturesSupported(effect);
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
     * Gets the resonant frequency of the vibrator, if applicable.
     *
     * @return the resonant frequency of the vibrator, or {@link Float#NaN NaN} if it's unknown, not
     * applicable, or if this vibrator is a composite of multiple physical devices with different
     * frequencies.
     */
    public float getResonantFrequency() {
        return getInfo().getResonantFrequencyHz();
    }

    /**
     * Gets the <a href="https://en.wikipedia.org/wiki/Q_factor">Q factor</a> of the vibrator.
     *
     * @return the Q factor of the vibrator, or {@link Float#NaN NaN} if it's unknown, not
     * applicable, or if this vibrator is a composite of multiple physical devices with different
     * Q factors.
     */
    public float getQFactor() {
        return getInfo().getQFactor();
    }

    /**
     * Gets the profile that describes the vibrator output across the supported frequency range.
     *
     * <p>The profile describes the relative output acceleration that the device can reach when it
     * vibrates at different frequencies.
     *
     * @return The frequency profile for this vibrator, or null if the vibrator does not have
     * frequency control. If this vibrator is a composite of multiple physical devices then this
     * will return a profile supported in all devices, or null if the intersection is empty or not
     * available.
     * @hide
     */
    @TestApi
    @Nullable
    public VibratorFrequencyProfile getFrequencyProfile() {
        VibratorInfo.FrequencyProfile frequencyProfile = getInfo().getFrequencyProfile();
        if (frequencyProfile.isEmpty()) {
            return null;
        }
        return new VibratorFrequencyProfile(frequencyProfile);
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
        return getConfig().getHapticChannelMaximumAmplitude();
    }

    /**
     * Configure an always-on haptics effect.
     *
     * @param alwaysOnId The board-specific always-on ID to configure.
     * @param effect     Vibration effect to assign to always-on id. Passing null will disable it.
     * @param attributes {@link VibrationAttributes} corresponding to the vibration. For example,
     *                   specify {@link VibrationAttributes#USAGE_ALARM} for alarm vibrations or
     *                   {@link VibrationAttributes#USAGE_RINGTONE} for vibrations associated with
     *                   incoming calls. May only be null when effect is null.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.VIBRATE_ALWAYS_ON)
    public boolean setAlwaysOnEffect(int alwaysOnId, @Nullable VibrationEffect effect,
            @Nullable VibrationAttributes attributes) {
        return setAlwaysOnEffect(Process.myUid(), mPackageName, alwaysOnId, effect, attributes);
    }

    /**
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.VIBRATE_ALWAYS_ON)
    public boolean setAlwaysOnEffect(int uid, String opPkg, int alwaysOnId,
            @Nullable VibrationEffect effect, @Nullable VibrationAttributes attributes) {
        Log.w(TAG, "Always-on effects aren't supported");
        return false;
    }

    /**
     * Vibrate constantly for the specified period of time.
     *
     * <p>The app should be in the foreground for the vibration to happen.</p>
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
     * <p>The app should be in the foreground for the vibration to happen. Background apps should
     * specify a ringtone, notification or alarm usage in order to vibrate.</p>
     *
     * @param milliseconds The number of milliseconds to vibrate.
     * @param attributes   {@link AudioAttributes} corresponding to the vibration. For example,
     *                     specify {@link AudioAttributes#USAGE_ALARM} for alarm vibrations or
     *                     {@link AudioAttributes#USAGE_NOTIFICATION_RINGTONE} for
     *                     vibrations associated with incoming calls.
     * @deprecated Use {@link #vibrate(VibrationEffect, VibrationAttributes)} instead.
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
     * <p>The app should be in the foreground for the vibration to happen.</p>
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
     * <p>The app should be in the foreground for the vibration to happen. Background apps should
     * specify a ringtone, notification or alarm usage in order to vibrate.</p>
     *
     * @param pattern    an array of longs of times for which to turn the vibrator on or off.
     * @param repeat     the index into pattern at which to repeat, or -1 if
     *                   you don't want to repeat.
     * @param attributes {@link AudioAttributes} corresponding to the vibration. For example,
     *                   specify {@link AudioAttributes#USAGE_ALARM} for alarm vibrations or
     *                   {@link AudioAttributes#USAGE_NOTIFICATION_RINGTONE} for
     *                   vibrations associated with incoming calls.
     * @deprecated Use {@link #vibrate(VibrationEffect, VibrationAttributes)} instead.
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
     * <p>The app should be in the foreground for the vibration to happen.</p>
     *
     * @param vibe {@link VibrationEffect} describing the vibration to be performed.
     */
    @RequiresPermission(android.Manifest.permission.VIBRATE)
    public void vibrate(VibrationEffect vibe) {
        vibrate(vibe, new VibrationAttributes.Builder().build());
    }

    /**
     * Vibrate with a given effect.
     *
     * <p>The app should be in the foreground for the vibration to happen. Background apps should
     * specify a ringtone, notification or alarm usage in order to vibrate.</p>
     *
     * @param vibe       {@link VibrationEffect} describing the vibration to be performed.
     * @param attributes {@link AudioAttributes} corresponding to the vibration. For example,
     *                   specify {@link AudioAttributes#USAGE_ALARM} for alarm vibrations or
     *                   {@link AudioAttributes#USAGE_NOTIFICATION_RINGTONE} for
     *                   vibrations associated with incoming calls.
     * @deprecated Use {@link #vibrate(VibrationEffect, VibrationAttributes)} instead.
     */
    @RequiresPermission(android.Manifest.permission.VIBRATE)
    public void vibrate(VibrationEffect vibe, AudioAttributes attributes) {
        vibrate(vibe,
                attributes == null
                        ? new VibrationAttributes.Builder().build()
                        : new VibrationAttributes.Builder(attributes).build());
    }

    /**
     * Vibrate with a given effect.
     *
     * <p>The app should be in the foreground for the vibration to happen. Background apps should
     * specify a ringtone, notification or alarm usage in order to vibrate.</p>
     *
     * @param vibe       {@link VibrationEffect} describing the vibration to be performed.
     * @param attributes {@link VibrationAttributes} corresponding to the vibration. For example,
     *                   specify {@link VibrationAttributes#USAGE_ALARM} for alarm vibrations or
     *                   {@link VibrationAttributes#USAGE_RINGTONE} for vibrations associated with
     *                   incoming calls.
     */
    @RequiresPermission(android.Manifest.permission.VIBRATE)
    public void vibrate(@NonNull VibrationEffect vibe, @NonNull VibrationAttributes attributes) {
        vibrate(Process.myUid(), mPackageName, vibe, null, attributes);
    }

    /**
     * Like {@link #vibrate(VibrationEffect, VibrationAttributes)}, but allows the
     * caller to specify the vibration is owned by someone else and set a reason for vibration.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.VIBRATE)
    public abstract void vibrate(int uid, String opPkg, @NonNull VibrationEffect vibe,
            String reason, @NonNull VibrationAttributes attributes);

    /**
     * Performs a haptic feedback.
     *
     * <p>A haptic feedback is a short vibration feedback. The type of feedback is identified via
     * the {@code constant}, which should be one of the effect constants provided in
     * {@link HapticFeedbackConstants}. The haptic feedback provided for a given effect ID is
     * consistent across all usages on the same device.
     *
     * @param constant the ID for the haptic feedback. This should be one of the constants defined
     *          in {@link HapticFeedbackConstants}.
     * @param always {@code true} if the haptic feedback should be played regardless of the user
     *          vibration intensity settings applicable to the corresponding vibration.
     *          {@code false} if the vibration for the haptic feedback should respect the applicable
     *          vibration intensity settings.
     * @param reason the reason for this haptic feedback.
     * @param fromIme the haptic feedback is performed from an IME.
     *
     * @hide
     */
    public void performHapticFeedback(int constant, boolean always, String reason,
            boolean fromIme) {
        Log.w(TAG, "performHapticFeedback is not supported");
    }

    /**
     * Query whether the vibrator natively supports the given effects.
     *
     * <p>If an effect is not supported, the system may still automatically fall back to playing
     * a simpler vibration instead, which is not optimised for the specific device. This includes
     * the unknown case, which can't be determined in advance, that will dynamically attempt to
     * fall back if the optimised effect fails to play.
     *
     * <p>The returned array will be the same length as the query array and the value at a given
     * index will contain {@link #VIBRATION_EFFECT_SUPPORT_YES} if the effect at that same index
     * in the querying array is supported, {@link #VIBRATION_EFFECT_SUPPORT_NO} if it isn't
     * supported, or {@link #VIBRATION_EFFECT_SUPPORT_UNKNOWN} if the system can't determine whether
     * it's supported or not, as some hardware doesn't report its effect capabilities.
     *
     * <p>Use {@link #areAllEffectsSupported(int...)} to get a single combined result,
     * or for convenience when querying exactly one effect.
     *
     * @param effectIds Which effects to query for.
     * @return An array containing the systems current knowledge about whether the given effects
     * are natively supported by the device, or not.
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
     * Query whether the vibrator supports all the given effects. If no argument is provided this
     * method will always return {@link #VIBRATION_EFFECT_SUPPORT_YES}.
     *
     * <p>If an effect is not supported, the system may still automatically fall back to a simpler
     * vibration instead, which is not optimised for the specific device, however vibration isn't
     * guaranteed in this case.
     *
     * <p>If the result is {@link #VIBRATION_EFFECT_SUPPORT_YES}, all effects in the query are
     * supported by the hardware.
     *
     * <p>If the result is {@link #VIBRATION_EFFECT_SUPPORT_NO}, at least one of the effects in the
     * query is not supported, and using them may fall back to an un-optimized vibration or no
     * vibration.
     *
     * <p>If the result is {@link #VIBRATION_EFFECT_SUPPORT_UNKNOWN}, the system doesn't know
     * whether all the effects are supported. It may support any or all of the queried effects,
     * but there's no way to programmatically know whether a {@link #vibrate} call will successfully
     * cause a vibration. It's guaranteed, however, that none of the queried effects are
     * definitively unsupported by the hardware.
     *
     * <p>Use {@link #areEffectsSupported(int...)} to get individual results for each effect.
     *
     * @param effectIds Which effects to query for.
     * @return Whether all specified effects are natively supported by the device. Empty query
     * defaults to {@link #VIBRATION_EFFECT_SUPPORT_YES}.
     */
    @VibrationEffectSupport
    public final int areAllEffectsSupported(
            @NonNull @VibrationEffect.EffectType int... effectIds) {
        VibratorInfo info = getInfo();
        int allSupported = VIBRATION_EFFECT_SUPPORT_YES;
        for (int effectId : effectIds) {
            switch (info.isEffectSupported(effectId)) {
                case VIBRATION_EFFECT_SUPPORT_NO:
                    return VIBRATION_EFFECT_SUPPORT_NO;
                case VIBRATION_EFFECT_SUPPORT_YES:
                    continue;
                default: // VIBRATION_EFFECT_SUPPORT_UNKNOWN
                    allSupported = VIBRATION_EFFECT_SUPPORT_UNKNOWN;
                    break;
            }
        }
        return allSupported;
    }

    /**
     * Query whether the vibrator supports the given primitives.
     *
     * The returned array will be the same length as the query array and the value at a given index
     * will contain whether the effect at that same index in the querying array is supported or
     * not.
     *
     * <p>If a primitive is not supported by the device, then <em>no vibration</em> will occur if
     * it is played.
     *
     * <p>Use {@link #areAllPrimitivesSupported(int...)} to get a single combined result,
     * or for convenience when querying exactly one primitive.
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
     * Query whether the vibrator supports all of the given primitives.  If no argument is provided
     * this method will always return {@code true}.
     *
     * <p>If a primitive is not supported by the device, then <em>no vibration</em> will occur if
     * it is played.
     *
     * <p>Use {@link #arePrimitivesSupported(int...)} to get individual results for each primitive.
     *
     * @param primitiveIds Which primitives to query for.
     * @return Whether all specified primitives are supported. Empty query defaults to {@code true}.
     */
    public final boolean areAllPrimitivesSupported(
            @NonNull @VibrationEffect.Composition.PrimitiveType int... primitiveIds) {
        VibratorInfo info = getInfo();
        for (int primitiveId : primitiveIds) {
            if (!info.isPrimitiveSupported(primitiveId)) {
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
