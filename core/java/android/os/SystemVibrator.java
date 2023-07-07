/*
 * Copyright (C) 2012 The Android Open Source Project
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
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.hardware.vibrator.IVibrator;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Range;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * Vibrator implementation that controls the main system vibrator.
 *
 * @hide
 */
public class SystemVibrator extends Vibrator {
    private static final String TAG = "Vibrator";

    private final VibratorManager mVibratorManager;
    private final Context mContext;

    @GuardedBy("mBrokenListeners")
    private final ArrayList<MultiVibratorStateListener> mBrokenListeners = new ArrayList<>();

    @GuardedBy("mRegisteredListeners")
    private final ArrayMap<OnVibratorStateChangedListener, MultiVibratorStateListener>
            mRegisteredListeners = new ArrayMap<>();

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private VibratorInfo mVibratorInfo;

    @UnsupportedAppUsage
    public SystemVibrator(Context context) {
        super(context);
        mContext = context;
        mVibratorManager = mContext.getSystemService(VibratorManager.class);
    }

    @Override
    protected VibratorInfo getInfo() {
        synchronized (mLock) {
            if (mVibratorInfo != null) {
                return mVibratorInfo;
            }
            if (mVibratorManager == null) {
                Log.w(TAG, "Failed to retrieve vibrator info; no vibrator manager.");
                return VibratorInfo.EMPTY_VIBRATOR_INFO;
            }
            int[] vibratorIds = mVibratorManager.getVibratorIds();
            if (vibratorIds.length == 0) {
                // It is known that the device has no vibrator, so cache and return info that
                // reflects the lack of support for effects/primitives.
                return mVibratorInfo = new NoVibratorInfo();
            }
            VibratorInfo[] vibratorInfos = new VibratorInfo[vibratorIds.length];
            for (int i = 0; i < vibratorIds.length; i++) {
                Vibrator vibrator = mVibratorManager.getVibrator(vibratorIds[i]);
                if (vibrator instanceof NullVibrator) {
                    Log.w(TAG, "Vibrator manager service not ready; "
                            + "Info not yet available for vibrator: " + vibratorIds[i]);
                    // This should never happen after the vibrator manager service is ready.
                    // Skip caching this vibrator until then.
                    return VibratorInfo.EMPTY_VIBRATOR_INFO;
                }
                vibratorInfos[i] = vibrator.getInfo();
            }
            if (vibratorInfos.length == 1) {
                // Device has a single vibrator info, cache and return successfully loaded info.
                return mVibratorInfo = new VibratorInfo(/* id= */ -1, vibratorInfos[0]);
            }
            // Device has multiple vibrators, generate a single info representing all of them.
            return mVibratorInfo = new MultiVibratorInfo(vibratorInfos);
        }
    }

    @Override
    public boolean hasVibrator() {
        if (mVibratorManager == null) {
            Log.w(TAG, "Failed to check if vibrator exists; no vibrator manager.");
            return false;
        }
        return mVibratorManager.getVibratorIds().length > 0;
    }

    @Override
    public boolean isVibrating() {
        if (mVibratorManager == null) {
            Log.w(TAG, "Failed to vibrate; no vibrator manager.");
            return false;
        }
        for (int vibratorId : mVibratorManager.getVibratorIds()) {
            if (mVibratorManager.getVibrator(vibratorId).isVibrating()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void addVibratorStateListener(@NonNull OnVibratorStateChangedListener listener) {
        Objects.requireNonNull(listener);
        if (mContext == null) {
            Log.w(TAG, "Failed to add vibrate state listener; no vibrator context.");
            return;
        }
        addVibratorStateListener(mContext.getMainExecutor(), listener);
    }

    @Override
    public void addVibratorStateListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnVibratorStateChangedListener listener) {
        Objects.requireNonNull(listener);
        Objects.requireNonNull(executor);
        if (mVibratorManager == null) {
            Log.w(TAG, "Failed to add vibrate state listener; no vibrator manager.");
            return;
        }
        MultiVibratorStateListener delegate = null;
        try {
            synchronized (mRegisteredListeners) {
                // If listener is already registered, reject and return.
                if (mRegisteredListeners.containsKey(listener)) {
                    Log.w(TAG, "Listener already registered.");
                    return;
                }
                delegate = new MultiVibratorStateListener(executor, listener);
                delegate.register(mVibratorManager);
                mRegisteredListeners.put(listener, delegate);
                delegate = null;
            }
        } finally {
            if (delegate != null && delegate.hasRegisteredListeners()) {
                // The delegate listener was left in a partial state with listeners registered to
                // some but not all vibrators. Keep track of this to try to unregister them later.
                synchronized (mBrokenListeners) {
                    mBrokenListeners.add(delegate);
                }
            }
            tryUnregisterBrokenListeners();
        }
    }

    @Override
    public void removeVibratorStateListener(@NonNull OnVibratorStateChangedListener listener) {
        Objects.requireNonNull(listener);
        if (mVibratorManager == null) {
            Log.w(TAG, "Failed to remove vibrate state listener; no vibrator manager.");
            return;
        }
        synchronized (mRegisteredListeners) {
            if (mRegisteredListeners.containsKey(listener)) {
                MultiVibratorStateListener delegate = mRegisteredListeners.get(listener);
                delegate.unregister(mVibratorManager);
                mRegisteredListeners.remove(listener);
            }
        }
        tryUnregisterBrokenListeners();
    }

    @Override
    public boolean hasAmplitudeControl() {
        return getInfo().hasAmplitudeControl();
    }

    @Override
    public boolean setAlwaysOnEffect(int uid, String opPkg, int alwaysOnId, VibrationEffect effect,
            VibrationAttributes attrs) {
        if (mVibratorManager == null) {
            Log.w(TAG, "Failed to set always-on effect; no vibrator manager.");
            return false;
        }
        CombinedVibration combinedEffect = CombinedVibration.createParallel(effect);
        return mVibratorManager.setAlwaysOnEffect(uid, opPkg, alwaysOnId, combinedEffect, attrs);
    }

    @Override
    public void vibrate(int uid, String opPkg, @NonNull VibrationEffect effect,
            String reason, @NonNull VibrationAttributes attributes) {
        if (mVibratorManager == null) {
            Log.w(TAG, "Failed to vibrate; no vibrator manager.");
            return;
        }
        CombinedVibration combinedEffect = CombinedVibration.createParallel(effect);
        mVibratorManager.vibrate(uid, opPkg, combinedEffect, reason, attributes);
    }

    @Override
    public void cancel() {
        if (mVibratorManager == null) {
            Log.w(TAG, "Failed to cancel vibrate; no vibrator manager.");
            return;
        }
        mVibratorManager.cancel();
    }

    @Override
    public void cancel(int usageFilter) {
        if (mVibratorManager == null) {
            Log.w(TAG, "Failed to cancel vibrate; no vibrator manager.");
            return;
        }
        mVibratorManager.cancel(usageFilter);
    }

    /**
     * Tries to unregister individual {@link android.os.Vibrator.OnVibratorStateChangedListener}
     * that were left registered to vibrators after failures to register them to all vibrators.
     *
     * <p>This might happen if {@link MultiVibratorStateListener} fails to register to any vibrator
     * and also fails to unregister any previously registered single listeners to other vibrators.
     *
     * <p>This method never throws {@link RuntimeException} if it fails to unregister again, it will
     * fail silently and attempt to unregister the same broken listener later.
     */
    private void tryUnregisterBrokenListeners() {
        synchronized (mBrokenListeners) {
            try {
                for (int i = mBrokenListeners.size(); --i >= 0; ) {
                    mBrokenListeners.get(i).unregister(mVibratorManager);
                    mBrokenListeners.remove(i);
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "Failed to unregister broken listener", e);
            }
        }
    }

    /** Listener for a single vibrator state change. */
    private static class SingleVibratorStateListener implements OnVibratorStateChangedListener {
        private final MultiVibratorStateListener mAllVibratorsListener;
        private final int mVibratorIdx;

        SingleVibratorStateListener(MultiVibratorStateListener listener, int vibratorIdx) {
            mAllVibratorsListener = listener;
            mVibratorIdx = vibratorIdx;
        }

        @Override
        public void onVibratorStateChanged(boolean isVibrating) {
            mAllVibratorsListener.onVibrating(mVibratorIdx, isVibrating);
        }
    }

    /**
     * Represents a device with no vibrator as a single {@link VibratorInfo}.
     *
     * @hide
     */
    @VisibleForTesting
    public static class NoVibratorInfo extends VibratorInfo {
        public NoVibratorInfo() {
            // Use empty arrays to indicate no support, while null would indicate support unknown.
            super(/* id= */ -1,
                    /* capabilities= */ 0,
                    /* supportedEffects= */ new SparseBooleanArray(),
                    /* supportedBraking= */ new SparseBooleanArray(),
                    /* supportedPrimitives= */ new SparseIntArray(),
                    /* primitiveDelayMax= */ 0,
                    /* compositionSizeMax= */ 0,
                    /* pwlePrimitiveDurationMax= */ 0,
                    /* pwleSizeMax= */ 0,
                    /* qFactor= */ Float.NaN,
                    new FrequencyProfile(/* resonantFrequencyHz= */ Float.NaN,
                            /* minFrequencyHz= */ Float.NaN,
                            /* frequencyResolutionHz= */ Float.NaN,
                            /* maxAmplitudes= */ null));
        }
    }

    /**
     * Represents multiple vibrator information as a single {@link VibratorInfo}.
     *
     * <p>This uses an intersection of all vibrators to decide the capabilities and effect/primitive
     * support.
     *
     * @hide
     */
    @VisibleForTesting
    public static class MultiVibratorInfo extends VibratorInfo {
        // Epsilon used for float comparison applied in calculations for the merged info.
        private static final float EPSILON = 1e-5f;

        public MultiVibratorInfo(VibratorInfo[] vibrators) {
            // Need to use an extra constructor to share the computation in super initialization.
            this(vibrators, frequencyProfileIntersection(vibrators));
        }

        private MultiVibratorInfo(VibratorInfo[] vibrators,
                VibratorInfo.FrequencyProfile mergedProfile) {
            super(/* id= */ -1,
                    capabilitiesIntersection(vibrators, mergedProfile.isEmpty()),
                    supportedEffectsIntersection(vibrators),
                    supportedBrakingIntersection(vibrators),
                    supportedPrimitivesAndDurationsIntersection(vibrators),
                    integerLimitIntersection(vibrators, VibratorInfo::getPrimitiveDelayMax),
                    integerLimitIntersection(vibrators, VibratorInfo::getCompositionSizeMax),
                    integerLimitIntersection(vibrators, VibratorInfo::getPwlePrimitiveDurationMax),
                    integerLimitIntersection(vibrators, VibratorInfo::getPwleSizeMax),
                    floatPropertyIntersection(vibrators, VibratorInfo::getQFactor),
                    mergedProfile);
        }

        private static int capabilitiesIntersection(VibratorInfo[] infos,
                boolean frequencyProfileIsEmpty) {
            int intersection = ~0;
            for (VibratorInfo info : infos) {
                intersection &= info.getCapabilities();
            }
            if (frequencyProfileIsEmpty) {
                // Revoke frequency control if the merged frequency profile ended up empty.
                intersection &= ~IVibrator.CAP_FREQUENCY_CONTROL;
            }
            return intersection;
        }

        @Nullable
        private static SparseBooleanArray supportedBrakingIntersection(VibratorInfo[] infos) {
            for (VibratorInfo info : infos) {
                if (!info.isBrakingSupportKnown()) {
                    // If one vibrator support is unknown, then the intersection is also unknown.
                    return null;
                }
            }

            SparseBooleanArray intersection = new SparseBooleanArray();
            SparseBooleanArray firstVibratorBraking = infos[0].getSupportedBraking();

            brakingIdLoop:
            for (int i = 0; i < firstVibratorBraking.size(); i++) {
                int brakingId = firstVibratorBraking.keyAt(i);
                if (!firstVibratorBraking.valueAt(i)) {
                    // The first vibrator already doesn't support this braking, so skip it.
                    continue brakingIdLoop;
                }

                for (int j = 1; j < infos.length; j++) {
                    if (!infos[j].hasBrakingSupport(brakingId)) {
                        // One vibrator doesn't support this braking, so the intersection doesn't.
                        continue brakingIdLoop;
                    }
                }

                intersection.put(brakingId, true);
            }

            return intersection;
        }

        @Nullable
        private static SparseBooleanArray supportedEffectsIntersection(VibratorInfo[] infos) {
            for (VibratorInfo info : infos) {
                if (!info.isEffectSupportKnown()) {
                    // If one vibrator support is unknown, then the intersection is also unknown.
                    return null;
                }
            }

            SparseBooleanArray intersection = new SparseBooleanArray();
            SparseBooleanArray firstVibratorEffects = infos[0].getSupportedEffects();

            effectIdLoop:
            for (int i = 0; i < firstVibratorEffects.size(); i++) {
                int effectId = firstVibratorEffects.keyAt(i);
                if (!firstVibratorEffects.valueAt(i)) {
                    // The first vibrator already doesn't support this effect, so skip it.
                    continue effectIdLoop;
                }

                for (int j = 1; j < infos.length; j++) {
                    if (infos[j].isEffectSupported(effectId) != VIBRATION_EFFECT_SUPPORT_YES) {
                        // One vibrator doesn't support this effect, so the intersection doesn't.
                        continue effectIdLoop;
                    }
                }

                intersection.put(effectId, true);
            }

            return intersection;
        }

        @NonNull
        private static SparseIntArray supportedPrimitivesAndDurationsIntersection(
                VibratorInfo[] infos) {
            SparseIntArray intersection = new SparseIntArray();
            SparseIntArray firstVibratorPrimitives = infos[0].getSupportedPrimitives();

            primitiveIdLoop:
            for (int i = 0; i < firstVibratorPrimitives.size(); i++) {
                int primitiveId = firstVibratorPrimitives.keyAt(i);
                int primitiveDuration = firstVibratorPrimitives.valueAt(i);
                if (primitiveDuration == 0) {
                    // The first vibrator already doesn't support this primitive, so skip it.
                    continue primitiveIdLoop;
                }

                for (int j = 1; j < infos.length; j++) {
                    int vibratorPrimitiveDuration = infos[j].getPrimitiveDuration(primitiveId);
                    if (vibratorPrimitiveDuration == 0) {
                        // One vibrator doesn't support this primitive, so the intersection doesn't.
                        continue primitiveIdLoop;
                    } else {
                        // The primitive vibration duration is the maximum among all vibrators.
                        primitiveDuration = Math.max(primitiveDuration, vibratorPrimitiveDuration);
                    }
                }

                intersection.put(primitiveId, primitiveDuration);
            }
            return intersection;
        }

        private static int integerLimitIntersection(VibratorInfo[] infos,
                Function<VibratorInfo, Integer> propertyGetter) {
            int limit = 0; // Limit 0 means unlimited
            for (VibratorInfo info : infos) {
                int vibratorLimit = propertyGetter.apply(info);
                if ((limit == 0) || (vibratorLimit > 0 && vibratorLimit < limit)) {
                    // This vibrator is limited and intersection is unlimited or has a larger limit:
                    // use smaller limit here for the intersection.
                    limit = vibratorLimit;
                }
            }
            return limit;
        }

        private static float floatPropertyIntersection(VibratorInfo[] infos,
                Function<VibratorInfo, Float> propertyGetter) {
            float property = propertyGetter.apply(infos[0]);
            if (Float.isNaN(property)) {
                // If one vibrator is undefined then the intersection is undefined.
                return Float.NaN;
            }
            for (int i = 1; i < infos.length; i++) {
                if (Float.compare(property, propertyGetter.apply(infos[i])) != 0) {
                    // If one vibrator has a different value then the intersection is undefined.
                    return Float.NaN;
                }
            }
            return property;
        }

        @NonNull
        private static FrequencyProfile frequencyProfileIntersection(VibratorInfo[] infos) {
            float freqResolution = floatPropertyIntersection(infos,
                    info -> info.getFrequencyProfile().getFrequencyResolutionHz());
            float resonantFreq = floatPropertyIntersection(infos,
                    VibratorInfo::getResonantFrequencyHz);
            Range<Float> freqRange = frequencyRangeIntersection(infos, freqResolution);

            if ((freqRange == null) || Float.isNaN(freqResolution)) {
                return new FrequencyProfile(resonantFreq, Float.NaN, freqResolution, null);
            }

            int amplitudeCount =
                    Math.round(1 + (freqRange.getUpper() - freqRange.getLower()) / freqResolution);
            float[] maxAmplitudes = new float[amplitudeCount];

            // Use MAX_VALUE here to ensure that the FrequencyProfile constructor called with this
            // will fail if the loop below is broken and do not replace filled values with actual
            // vibrator measurements.
            Arrays.fill(maxAmplitudes, Float.MAX_VALUE);

            for (VibratorInfo info : infos) {
                Range<Float> vibratorFreqRange = info.getFrequencyProfile().getFrequencyRangeHz();
                float[] vibratorMaxAmplitudes = info.getFrequencyProfile().getMaxAmplitudes();
                int vibratorStartIdx = Math.round(
                        (freqRange.getLower() - vibratorFreqRange.getLower()) / freqResolution);
                int vibratorEndIdx = vibratorStartIdx + maxAmplitudes.length - 1;

                if ((vibratorStartIdx < 0) || (vibratorEndIdx >= vibratorMaxAmplitudes.length)) {
                    Slog.w(TAG, "Error calculating the intersection of vibrator frequency"
                            + " profiles: attempted to fetch from vibrator "
                            + info.getId() + " max amplitude with bad index " + vibratorStartIdx);
                    return new FrequencyProfile(resonantFreq, Float.NaN, Float.NaN, null);
                }

                for (int i = 0; i < maxAmplitudes.length; i++) {
                    maxAmplitudes[i] = Math.min(maxAmplitudes[i],
                            vibratorMaxAmplitudes[vibratorStartIdx + i]);
                }
            }

            return new FrequencyProfile(resonantFreq, freqRange.getLower(),
                    freqResolution, maxAmplitudes);
        }

        @Nullable
        private static Range<Float> frequencyRangeIntersection(VibratorInfo[] infos,
                float frequencyResolution) {
            Range<Float> firstRange = infos[0].getFrequencyProfile().getFrequencyRangeHz();
            if (firstRange == null) {
                // If one vibrator is undefined then the intersection is undefined.
                return null;
            }
            float intersectionLower = firstRange.getLower();
            float intersectionUpper = firstRange.getUpper();

            // Generate the intersection of all vibrator supported ranges, making sure that both
            // min supported frequencies are aligned w.r.t. the frequency resolution.

            for (int i = 1; i < infos.length; i++) {
                Range<Float> vibratorRange = infos[i].getFrequencyProfile().getFrequencyRangeHz();
                if (vibratorRange == null) {
                    // If one vibrator is undefined then the intersection is undefined.
                    return null;
                }

                if ((vibratorRange.getLower() >= intersectionUpper)
                        || (vibratorRange.getUpper() <= intersectionLower)) {
                    // If the range and intersection are disjoint then the intersection is undefined
                    return null;
                }

                float frequencyDelta = Math.abs(intersectionLower - vibratorRange.getLower());
                if ((frequencyDelta % frequencyResolution) > EPSILON) {
                    // If the intersection is not aligned with one vibrator then it's undefined
                    return null;
                }

                intersectionLower = Math.max(intersectionLower, vibratorRange.getLower());
                intersectionUpper = Math.min(intersectionUpper, vibratorRange.getUpper());
            }

            if ((intersectionUpper - intersectionLower) < frequencyResolution) {
                // If the intersection is empty then it's undefined.
                return null;
            }

            return Range.create(intersectionLower, intersectionUpper);
        }
    }

    /**
     * Listener for all vibrators state change.
     *
     * <p>This registers a listener to all vibrators to merge the callbacks into a single state
     * that is set to true if any individual vibrator is also true, and false otherwise.
     *
     * @hide
     */
    @VisibleForTesting
    public static class MultiVibratorStateListener {
        private final Object mLock = new Object();
        private final Executor mExecutor;
        private final OnVibratorStateChangedListener mDelegate;

        @GuardedBy("mLock")
        private final SparseArray<SingleVibratorStateListener> mVibratorListeners =
                new SparseArray<>();

        @GuardedBy("mLock")
        private int mInitializedMask;
        @GuardedBy("mLock")
        private int mVibratingMask;

        public MultiVibratorStateListener(@NonNull Executor executor,
                @NonNull OnVibratorStateChangedListener listener) {
            mExecutor = executor;
            mDelegate = listener;
        }

        /** Returns true if at least one listener was registered to an individual vibrator. */
        public boolean hasRegisteredListeners() {
            synchronized (mLock) {
                return mVibratorListeners.size() > 0;
            }
        }

        /** Registers a listener to all individual vibrators in {@link VibratorManager}. */
        public void register(VibratorManager vibratorManager) {
            int[] vibratorIds = vibratorManager.getVibratorIds();
            synchronized (mLock) {
                for (int i = 0; i < vibratorIds.length; i++) {
                    int vibratorId = vibratorIds[i];
                    SingleVibratorStateListener listener = new SingleVibratorStateListener(this, i);
                    try {
                        vibratorManager.getVibrator(vibratorId).addVibratorStateListener(mExecutor,
                                listener);
                        mVibratorListeners.put(vibratorId, listener);
                    } catch (RuntimeException e) {
                        try {
                            unregister(vibratorManager);
                        } catch (RuntimeException e1) {
                            Log.w(TAG,
                                    "Failed to unregister listener while recovering from a failed "
                                            + "register call", e1);
                        }
                        throw e;
                    }
                }
            }
        }

        /** Unregisters the listeners from all individual vibrators in {@link VibratorManager}. */
        public void unregister(VibratorManager vibratorManager) {
            synchronized (mLock) {
                for (int i = mVibratorListeners.size(); --i >= 0; ) {
                    int vibratorId = mVibratorListeners.keyAt(i);
                    SingleVibratorStateListener listener = mVibratorListeners.valueAt(i);
                    vibratorManager.getVibrator(vibratorId).removeVibratorStateListener(listener);
                    mVibratorListeners.removeAt(i);
                }
            }
        }

        /** Callback triggered by {@link SingleVibratorStateListener} for each vibrator. */
        public void onVibrating(int vibratorIdx, boolean vibrating) {
            mExecutor.execute(() -> {
                boolean shouldNotifyStateChange;
                boolean isAnyVibrating;
                synchronized (mLock) {
                    // Bitmask indicating that all vibrators have been initialized.
                    int allInitializedMask = (1 << mVibratorListeners.size()) - 1;

                    // Save current global state before processing this vibrator state change.
                    boolean previousIsAnyVibrating = (mVibratingMask != 0);
                    boolean previousAreAllInitialized = (mInitializedMask == allInitializedMask);

                    // Mark this vibrator as initialized.
                    int vibratorMask = (1 << vibratorIdx);
                    mInitializedMask |= vibratorMask;

                    // Flip the vibrating bit flag for this vibrator, only if the state is changing.
                    boolean previousVibrating = (mVibratingMask & vibratorMask) != 0;
                    if (previousVibrating != vibrating) {
                        mVibratingMask ^= vibratorMask;
                    }

                    // Check new global state after processing this vibrator state change.
                    isAnyVibrating = (mVibratingMask != 0);
                    boolean areAllInitialized = (mInitializedMask == allInitializedMask);

                    // Prevent multiple triggers with the same state.
                    // Trigger once when all vibrators have reported their state, and then only when
                    // the merged vibrating state changes.
                    boolean isStateChanging = (previousIsAnyVibrating != isAnyVibrating);
                    shouldNotifyStateChange =
                            areAllInitialized && (!previousAreAllInitialized || isStateChanging);
                }
                // Notify delegate listener outside the lock, only if merged state is changing.
                if (shouldNotifyStateChange) {
                    mDelegate.onVibratorStateChanged(isAnyVibrating);
                }
            });
        }
    }
}
