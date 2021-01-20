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

package com.android.server.vibrator;

import android.annotation.Nullable;
import android.os.CombinedVibrationEffect;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.os.VibrationEffect;
import android.os.WorkSource;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IBatteryStats;
import com.android.internal.util.FrameworkStatsLog;

import com.google.android.collect.Lists;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

/** Plays a {@link Vibration} in dedicated thread. */
// TODO(b/159207608): Make this package-private once vibrator services are moved to this package
public final class VibrationThread extends Thread implements IBinder.DeathRecipient {
    private static final String TAG = "VibrationThread";
    private static final boolean DEBUG = false;

    /**
     * Extra timeout added to the end of each synced vibration step as a timeout for the callback
     * wait, to ensure it finishes even when callbacks from individual vibrators are lost.
     */
    private static final long CALLBACKS_EXTRA_TIMEOUT = 100;

    /** Callbacks for playing a {@link Vibration}. */
    public interface VibrationCallbacks {

        /**
         * Callback triggered before starting a synchronized vibration step. This will be called
         * with {@code requiredCapabilities = 0} if no synchronization is required.
         *
         * @param requiredCapabilities The required syncing capabilities for this preparation step.
         *                             Expects a combination of values from
         *                             IVibratorManager.CAP_PREPARE_* and
         *                             IVibratorManager.CAP_MIXED_TRIGGER_*.
         * @param vibratorIds          The id of the vibrators to be prepared.
         */
        void prepareSyncedVibration(int requiredCapabilities, int[] vibratorIds);

        /** Callback triggered after synchronized vibrations were prepared. */
        void triggerSyncedVibration(long vibrationId);

        /** Callback triggered when vibration thread is complete. */
        void onVibrationEnded(long vibrationId, Vibration.Status status);
    }

    private final WorkSource mWorkSource = new WorkSource();
    private final PowerManager.WakeLock mWakeLock;
    private final IBatteryStats mBatteryStatsService;
    private final Vibration mVibration;
    private final VibrationCallbacks mCallbacks;
    private final SparseArray<VibratorController> mVibrators;

    @GuardedBy("this")
    @Nullable
    private VibrateStep mCurrentVibrateStep;
    @GuardedBy("this")
    private boolean mForceStop;

    // TODO(b/159207608): Remove this constructor once VibratorService is removed
    public VibrationThread(Vibration vib, VibratorController vibrator,
            PowerManager.WakeLock wakeLock, IBatteryStats batteryStatsService,
            VibrationCallbacks callbacks) {
        this(vib, toSparseArray(vibrator), wakeLock, batteryStatsService, callbacks);
    }

    public VibrationThread(Vibration vib, SparseArray<VibratorController> availableVibrators,
            PowerManager.WakeLock wakeLock, IBatteryStats batteryStatsService,
            VibrationCallbacks callbacks) {
        mVibration = vib;
        mCallbacks = callbacks;
        mWakeLock = wakeLock;
        mWorkSource.set(vib.uid);
        mWakeLock.setWorkSource(mWorkSource);
        mBatteryStatsService = batteryStatsService;

        CombinedVibrationEffect effect = vib.getEffect();
        mVibrators = new SparseArray<>();
        for (int i = 0; i < availableVibrators.size(); i++) {
            if (effect.hasVibrator(availableVibrators.keyAt(i))) {
                mVibrators.put(availableVibrators.keyAt(i), availableVibrators.valueAt(i));
            }
        }
    }

    @Override
    public void binderDied() {
        cancel();
    }

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        mWakeLock.acquire();
        try {
            mVibration.token.linkToDeath(this, 0);
            Vibration.Status status = playVibration();
            mCallbacks.onVibrationEnded(mVibration.id, status);
        } catch (RemoteException e) {
            Slog.e(TAG, "Error linking vibration to token death", e);
        } finally {
            mVibration.token.unlinkToDeath(this, 0);
            mWakeLock.release();
        }
    }

    /** Cancel current vibration and shuts down the thread gracefully. */
    public void cancel() {
        synchronized (this) {
            mForceStop = true;
            notify();
        }
    }

    /** Notify current vibration that a step has completed on given vibrator. */
    public void vibratorComplete(int vibratorId) {
        synchronized (this) {
            if (mCurrentVibrateStep != null) {
                mCurrentVibrateStep.vibratorComplete(vibratorId);
            }
        }
    }

    @VisibleForTesting
    SparseArray<VibratorController> getVibrators() {
        return mVibrators;
    }

    private Vibration.Status playVibration() {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "playVibration");
        try {
            List<Step> steps = generateSteps(mVibration.getEffect());
            if (steps.isEmpty()) {
                // No vibrator matching any incoming vibration effect.
                return Vibration.Status.IGNORED;
            }
            Vibration.Status status = Vibration.Status.FINISHED;
            final int stepCount = steps.size();
            for (int i = 0; i < stepCount; i++) {
                Step step = steps.get(i);
                synchronized (this) {
                    if (step instanceof VibrateStep) {
                        mCurrentVibrateStep = (VibrateStep) step;
                    } else {
                        mCurrentVibrateStep = null;
                    }
                }
                status = step.play();
                if (status != Vibration.Status.FINISHED) {
                    // This step was ignored by the vibrators, probably effects were unsupported.
                    break;
                }
                if (mForceStop) {
                    break;
                }
            }
            if (mForceStop) {
                return Vibration.Status.CANCELLED;
            }
            return status;
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    private List<Step> generateSteps(CombinedVibrationEffect effect) {
        if (effect instanceof CombinedVibrationEffect.Sequential) {
            CombinedVibrationEffect.Sequential sequential =
                    (CombinedVibrationEffect.Sequential) effect;
            List<Step> steps = new ArrayList<>();
            final int sequentialEffectCount = sequential.getEffects().size();
            for (int i = 0; i < sequentialEffectCount; i++) {
                int delay = sequential.getDelays().get(i);
                if (delay > 0) {
                    steps.add(new DelayStep(delay));
                }
                steps.addAll(generateSteps(sequential.getEffects().get(i)));
            }
            final int stepCount = steps.size();
            for (int i = 0; i < stepCount; i++) {
                if (steps.get(i) instanceof VibrateStep) {
                    return steps;
                }
            }
            // No valid vibrate step was generated, ignore effect completely.
            return Lists.newArrayList();
        }
        VibrateStep vibrateStep = null;
        if (effect instanceof CombinedVibrationEffect.Mono) {
            vibrateStep = createVibrateStep(mapToAvailableVibrators(
                    ((CombinedVibrationEffect.Mono) effect).getEffect()));
        } else if (effect instanceof CombinedVibrationEffect.Stereo) {
            vibrateStep = createVibrateStep(filterByAvailableVibrators(
                    ((CombinedVibrationEffect.Stereo) effect).getEffects()));
        }
        return vibrateStep == null ? Lists.newArrayList() : Lists.newArrayList(vibrateStep);
    }

    @Nullable
    private VibrateStep createVibrateStep(SparseArray<VibrationEffect> effects) {
        if (effects.size() == 0) {
            return null;
        }
        if (effects.size() == 1) {
            // Create simplified step that handles a single vibrator.
            return new SingleVibrateStep(mVibrators.get(effects.keyAt(0)), effects.valueAt(0));
        }
        return new SyncedVibrateStep(effects);
    }

    private SparseArray<VibrationEffect> mapToAvailableVibrators(VibrationEffect effect) {
        SparseArray<VibrationEffect> mappedEffects = new SparseArray<>(mVibrators.size());
        for (int i = 0; i < mVibrators.size(); i++) {
            mappedEffects.put(mVibrators.keyAt(i), effect);
        }
        return mappedEffects;
    }

    private SparseArray<VibrationEffect> filterByAvailableVibrators(
            SparseArray<VibrationEffect> effects) {
        SparseArray<VibrationEffect> filteredEffects = new SparseArray<>();
        for (int i = 0; i < effects.size(); i++) {
            if (mVibrators.contains(effects.keyAt(i))) {
                filteredEffects.put(effects.keyAt(i), effects.valueAt(i));
            }
        }
        return filteredEffects;
    }

    private static SparseArray<VibratorController> toSparseArray(VibratorController controller) {
        SparseArray<VibratorController> array = new SparseArray<>(1);
        array.put(controller.getVibratorInfo().getId(), controller);
        return array;
    }

    /**
     * Get the duration the vibrator will be on for given {@code waveform}, starting at {@code
     * startIndex} until the next time it's vibrating amplitude is zero.
     */
    private static long getVibratorOnDuration(VibrationEffect.Waveform waveform, int startIndex) {
        long[] timings = waveform.getTimings();
        int[] amplitudes = waveform.getAmplitudes();
        int repeatIndex = waveform.getRepeatIndex();
        int i = startIndex;
        long timing = 0;
        while (timings[i] == 0 || amplitudes[i] != 0) {
            timing += timings[i++];
            if (i >= timings.length) {
                if (repeatIndex >= 0) {
                    i = repeatIndex;
                    // prevent infinite loop
                    repeatIndex = -1;
                } else {
                    break;
                }
            }
            if (i == startIndex) {
                return 1000;
            }
        }
        return timing;
    }

    /**
     * Sleeps until given {@code wakeUpTime}.
     *
     * <p>This stops immediately when {@link #cancel()} is called.
     */
    private void waitUntil(long wakeUpTime) {
        synchronized (this) {
            long durationRemaining = wakeUpTime - SystemClock.uptimeMillis();
            while (durationRemaining > 0) {
                try {
                    VibrationThread.this.wait(durationRemaining);
                } catch (InterruptedException e) {
                }
                if (mForceStop) {
                    break;
                }
                durationRemaining = wakeUpTime - SystemClock.uptimeMillis();
            }
        }
    }

    private void noteVibratorOn(long duration) {
        try {
            mBatteryStatsService.noteVibratorOn(mVibration.uid, duration);
            FrameworkStatsLog.write_non_chained(FrameworkStatsLog.VIBRATOR_STATE_CHANGED,
                    mVibration.uid, null, FrameworkStatsLog.VIBRATOR_STATE_CHANGED__STATE__ON,
                    duration);
        } catch (RemoteException e) {
        }
    }

    private void noteVibratorOff() {
        try {
            mBatteryStatsService.noteVibratorOff(mVibration.uid);
            FrameworkStatsLog.write_non_chained(FrameworkStatsLog.VIBRATOR_STATE_CHANGED,
                    mVibration.uid, null, FrameworkStatsLog.VIBRATOR_STATE_CHANGED__STATE__OFF,
                    /* duration= */ 0);
        } catch (RemoteException e) {
        }
    }

    /** Represent a single synchronized step while playing a {@link CombinedVibrationEffect}. */
    private interface Step {
        Vibration.Status play();
    }

    /** Represent a synchronized vibration step. */
    private interface VibrateStep extends Step {
        /** Callback to notify a vibrator has finished playing a effect. */
        void vibratorComplete(int vibratorId);
    }

    /** Represent a vibration on a single vibrator. */
    private final class SingleVibrateStep implements VibrateStep {
        private final VibratorController mVibrator;
        private final VibrationEffect mEffect;

        SingleVibrateStep(VibratorController vibrator, VibrationEffect effect) {
            mVibrator = vibrator;
            mEffect = effect;
        }

        @Override
        public void vibratorComplete(int vibratorId) {
            if (mVibrator.getVibratorInfo().getId() != vibratorId) {
                return;
            }
            if (mEffect instanceof VibrationEffect.OneShot
                    || mEffect instanceof VibrationEffect.Waveform) {
                // Oneshot and Waveform are controlled by amplitude steps, ignore callbacks.
                return;
            }
            mVibrator.off();
            synchronized (VibrationThread.this) {
                VibrationThread.this.notify();
            }
        }

        @Override
        public Vibration.Status play() {
            Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "SingleVibrateStep");
            long duration = -1;
            try {
                if (DEBUG) {
                    Slog.d(TAG, "SingleVibrateStep starting...");
                }
                long startTime = SystemClock.uptimeMillis();
                duration = vibratePredefined(mEffect);

                if (duration > 0) {
                    noteVibratorOn(duration);
                    // Vibration is playing with no need to control amplitudes, just wait for native
                    // callback or timeout.
                    waitUntil(startTime + duration + CALLBACKS_EXTRA_TIMEOUT);
                    if (mForceStop) {
                        mVibrator.off();
                        return Vibration.Status.CANCELLED;
                    }
                    return Vibration.Status.FINISHED;
                }

                startTime = SystemClock.uptimeMillis();
                AmplitudeStep amplitudeStep = vibrateWithAmplitude(mEffect, startTime);
                if (amplitudeStep == null) {
                    // Vibration could not be played with or without amplitude steps.
                    return Vibration.Status.IGNORED_UNSUPPORTED;
                }

                duration = mEffect instanceof VibrationEffect.Prebaked
                        ? ((VibrationEffect.Prebaked) mEffect).getFallbackEffect().getDuration()
                        : mEffect.getDuration();
                if (duration < Long.MAX_VALUE) {
                    // Only report vibration stats if we know how long we will be vibrating.
                    noteVibratorOn(duration);
                }
                while (amplitudeStep != null) {
                    waitUntil(amplitudeStep.startTime);
                    if (mForceStop) {
                        mVibrator.off();
                        return Vibration.Status.CANCELLED;
                    }
                    amplitudeStep.play();
                    amplitudeStep = amplitudeStep.nextStep();
                }

                return Vibration.Status.FINISHED;
            } finally {
                if (duration > 0 && duration < Long.MAX_VALUE) {
                    noteVibratorOff();
                }
                if (DEBUG) {
                    Slog.d(TAG, "SingleVibrateStep step done.");
                }
                Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
            }
        }

        /**
         * Try to vibrate given effect using prebaked or composed predefined effects.
         *
         * @return the duration, in millis, expected for the vibration, or -1 if effect cannot be
         * played with predefined effects.
         */
        private long vibratePredefined(VibrationEffect effect) {
            if (effect instanceof VibrationEffect.Prebaked) {
                VibrationEffect.Prebaked prebaked = (VibrationEffect.Prebaked) effect;
                long duration = mVibrator.on(prebaked, mVibration.id);
                if (duration > 0) {
                    return duration;
                }
                if (prebaked.getFallbackEffect() != null) {
                    return vibratePredefined(prebaked.getFallbackEffect());
                }
            } else if (effect instanceof VibrationEffect.Composed) {
                VibrationEffect.Composed composed = (VibrationEffect.Composed) effect;
                return mVibrator.on(composed, mVibration.id);
            }
            // OneShot and Waveform effects require amplitude change after calling vibrator.on.
            return -1;
        }

        /**
         * Try to vibrate given effect using {@link AmplitudeStep} to control vibration amplitude.
         *
         * @return the {@link AmplitudeStep} to start this vibration, or {@code null} if vibration
         * do not require amplitude control.
         */
        private AmplitudeStep vibrateWithAmplitude(VibrationEffect effect, long startTime) {
            int vibratorId = mVibrator.getVibratorInfo().getId();
            if (effect instanceof VibrationEffect.OneShot) {
                VibrationEffect.OneShot oneShot = (VibrationEffect.OneShot) effect;
                return new AmplitudeStep(vibratorId, oneShot, startTime, startTime);
            } else if (effect instanceof VibrationEffect.Waveform) {
                VibrationEffect.Waveform waveform = (VibrationEffect.Waveform) effect;
                return new AmplitudeStep(vibratorId, waveform, startTime, startTime);
            } else if (effect instanceof VibrationEffect.Prebaked) {
                VibrationEffect.Prebaked prebaked = (VibrationEffect.Prebaked) effect;
                if (prebaked.getFallbackEffect() != null) {
                    return vibrateWithAmplitude(prebaked.getFallbackEffect(), startTime);
                }
            }
            return null;
        }
    }

    /** Represent a synchronized vibration step on multiple vibrators. */
    private final class SyncedVibrateStep implements VibrateStep {
        private final SparseArray<VibrationEffect> mEffects;
        private final int mRequiredCapabilities;
        private final int[] mVibratorIds;

        @GuardedBy("VibrationThread.this")
        private int mActiveVibratorCounter;

        SyncedVibrateStep(SparseArray<VibrationEffect> effects) {
            mEffects = effects;
            mActiveVibratorCounter = mEffects.size();
            // TODO(b/159207608): Calculate required capabilities for syncing this step.
            mRequiredCapabilities = 0;
            mVibratorIds = new int[effects.size()];
            for (int i = 0; i < effects.size(); i++) {
                mVibratorIds[i] = effects.keyAt(i);
            }
        }

        @Override
        public void vibratorComplete(int vibratorId) {
            VibrationEffect effect = mEffects.get(vibratorId);
            if (effect == null) {
                return;
            }
            if (effect instanceof VibrationEffect.OneShot
                    || effect instanceof VibrationEffect.Waveform) {
                // Oneshot and Waveform are controlled by amplitude steps, ignore callbacks.
                return;
            }
            mVibrators.get(vibratorId).off();
            synchronized (VibrationThread.this) {
                if (--mActiveVibratorCounter <= 0) {
                    VibrationThread.this.notify();
                }
            }
        }

        @Override
        public Vibration.Status play() {
            Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "SyncedVibrateStep");
            long timeout = -1;
            try {
                if (DEBUG) {
                    Slog.d(TAG, "SyncedVibrateStep starting...");
                }
                final PriorityQueue<AmplitudeStep> nextSteps = new PriorityQueue<>(mEffects.size());
                long startTime = SystemClock.uptimeMillis();
                mCallbacks.prepareSyncedVibration(mRequiredCapabilities, mVibratorIds);
                timeout = startVibrating(startTime, nextSteps);
                mCallbacks.triggerSyncedVibration(mVibration.id);
                noteVibratorOn(timeout);

                while (!nextSteps.isEmpty()) {
                    AmplitudeStep step = nextSteps.poll();
                    waitUntil(step.startTime);
                    if (mForceStop) {
                        stopAllVibrators();
                        return Vibration.Status.CANCELLED;
                    }
                    step.play();
                    AmplitudeStep nextStep = step.nextStep();
                    if (nextStep == null) {
                        // This vibrator has finished playing the effect for this step.
                        synchronized (VibrationThread.this) {
                            mActiveVibratorCounter--;
                        }
                    } else {
                        nextSteps.add(nextStep);
                    }
                }

                // All OneShot and Waveform effects have finished. Just wait for the other effects
                // to end via native callbacks before finishing this synced step.
                synchronized (VibrationThread.this) {
                    if (mActiveVibratorCounter > 0) {
                        waitUntil(startTime + timeout + CALLBACKS_EXTRA_TIMEOUT);
                    }
                }
                if (mForceStop) {
                    stopAllVibrators();
                    return Vibration.Status.CANCELLED;
                }

                return Vibration.Status.FINISHED;
            } finally {
                if (timeout > 0) {
                    noteVibratorOff();
                }
                if (DEBUG) {
                    Slog.d(TAG, "SyncedVibrateStep done.");
                }
                Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
            }
        }

        /**
         * Starts playing effects on designated vibrators.
         *
         * <p>This includes the {@link VibrationEffect.OneShot} and {@link VibrationEffect.Waveform}
         * effects, that should start in sync with all other effects in this step. The waveforms are
         * controlled by {@link AmplitudeStep} added to the {@code nextSteps} queue.
         *
         * @return A duration, in millis, to wait for the completion of all vibrations. This ignores
         * any repeating waveform duration and returns the duration of a single run.
         */
        private long startVibrating(long startTime, PriorityQueue<AmplitudeStep> nextSteps) {
            long maxDuration = 0;
            for (int i = 0; i < mEffects.size(); i++) {
                VibratorController controller = mVibrators.get(mEffects.keyAt(i));
                VibrationEffect effect = mEffects.valueAt(i);
                maxDuration = Math.max(maxDuration,
                        startVibrating(controller, effect, startTime, nextSteps));
            }
            return maxDuration;
        }

        /**
         * Play a single effect on a single vibrator.
         *
         * @return A duration, in millis, to wait for the completion of this effect. This ignores
         * any repeating waveform duration and returns the duration of a single run to be used as
         * timeout for callbacks.
         */
        private long startVibrating(VibratorController controller, VibrationEffect effect,
                long startTime, PriorityQueue<AmplitudeStep> nextSteps) {
            int vibratorId = controller.getVibratorInfo().getId();
            long duration;
            if (effect instanceof VibrationEffect.OneShot) {
                VibrationEffect.OneShot oneShot = (VibrationEffect.OneShot) effect;
                duration = oneShot.getDuration();
                controller.on(duration, mVibration.id);
                nextSteps.add(
                        new AmplitudeStep(vibratorId, oneShot, startTime, startTime + duration));
            } else if (effect instanceof VibrationEffect.Waveform) {
                VibrationEffect.Waveform waveform = (VibrationEffect.Waveform) effect;
                duration = getVibratorOnDuration(waveform, 0);
                if (duration > 0) {
                    // Waveform starts by turning vibrator on. Do it in this sync vibrate step.
                    controller.on(duration, mVibration.id);
                }
                nextSteps.add(
                        new AmplitudeStep(vibratorId, waveform, startTime, startTime + duration));
            } else if (effect instanceof VibrationEffect.Prebaked) {
                VibrationEffect.Prebaked prebaked = (VibrationEffect.Prebaked) effect;
                duration = controller.on(prebaked, mVibration.id);
                if (duration <= 0 && prebaked.getFallbackEffect() != null) {
                    return startVibrating(controller, prebaked.getFallbackEffect(), startTime,
                            nextSteps);
                }
            } else if (effect instanceof VibrationEffect.Composed) {
                VibrationEffect.Composed composed = (VibrationEffect.Composed) effect;
                duration = controller.on(composed, mVibration.id);
            } else {
                duration = 0;
            }
            return duration;
        }

        private void stopAllVibrators() {
            for (int vibratorId : mVibratorIds) {
                VibratorController controller = mVibrators.get(vibratorId);
                if (controller != null) {
                    controller.off();
                }
            }
        }
    }

    /** Represent a step to set amplitude on a single vibrator. */
    private final class AmplitudeStep implements Step, Comparable<AmplitudeStep> {
        public final int vibratorId;
        public final VibrationEffect.Waveform waveform;
        public final int currentIndex;
        public final long startTime;
        public final long vibratorStopTime;

        AmplitudeStep(int vibratorId, VibrationEffect.OneShot oneShot,
                long startTime, long vibratorStopTime) {
            this(vibratorId, (VibrationEffect.Waveform) VibrationEffect.createWaveform(
                    new long[]{oneShot.getDuration()},
                    new int[]{oneShot.getAmplitude()}, /* repeat= */ -1),
                    startTime,
                    vibratorStopTime);
        }

        AmplitudeStep(int vibratorId, VibrationEffect.Waveform waveform,
                long startTime, long vibratorStopTime) {
            this(vibratorId, waveform, /* index= */ 0, startTime, vibratorStopTime);
        }

        AmplitudeStep(int vibratorId, VibrationEffect.Waveform waveform,
                int index, long startTime, long vibratorStopTime) {
            this.vibratorId = vibratorId;
            this.waveform = waveform;
            this.currentIndex = index;
            this.startTime = startTime;
            this.vibratorStopTime = vibratorStopTime;
        }

        @Override
        public Vibration.Status play() {
            Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "AmplitudeStep");
            try {
                if (DEBUG) {
                    Slog.d(TAG, "AmplitudeStep starting on vibrator " + vibratorId + "...");
                }
                VibratorController controller = mVibrators.get(vibratorId);
                if (currentIndex < 0) {
                    controller.off();
                    if (DEBUG) {
                        Slog.d(TAG, "Vibrator turned off and finishing");
                    }
                    return Vibration.Status.FINISHED;
                }
                if (waveform.getTimings()[currentIndex] == 0) {
                    // Skip waveform entries with zero timing.
                    return Vibration.Status.FINISHED;
                }
                int amplitude = waveform.getAmplitudes()[currentIndex];
                if (amplitude == 0) {
                    controller.off();
                    if (DEBUG) {
                        Slog.d(TAG, "Vibrator turned off");
                    }
                    return Vibration.Status.FINISHED;
                }
                if (startTime >= vibratorStopTime) {
                    // Vibrator has stopped. Turn vibrator back on for the duration of another
                    // cycle before setting the amplitude.
                    long onDuration = getVibratorOnDuration(waveform, currentIndex);
                    if (onDuration > 0) {
                        controller.on(onDuration, mVibration.id);
                        if (DEBUG) {
                            Slog.d(TAG, "Vibrator turned on for " + onDuration + "ms");
                        }
                    }
                }
                controller.setAmplitude(amplitude);
                if (DEBUG) {
                    Slog.d(TAG, "Amplitude changed to " + amplitude);
                }
                return Vibration.Status.FINISHED;
            } finally {
                if (DEBUG) {
                    Slog.d(TAG, "AmplitudeStep done.");
                }
                Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
            }
        }

        @Override
        public int compareTo(AmplitudeStep o) {
            return Long.compare(startTime, o.startTime);
        }

        /** Return next {@link AmplitudeStep} from this waveform, of {@code null} if finished. */
        @Nullable
        public AmplitudeStep nextStep() {
            if (currentIndex < 0) {
                // Waveform has ended, no more steps to run.
                return null;
            }
            long nextWakeUpTime = startTime + waveform.getTimings()[currentIndex];
            int nextIndex = currentIndex + 1;
            if (nextIndex >= waveform.getTimings().length) {
                nextIndex = waveform.getRepeatIndex();
            }
            return new AmplitudeStep(vibratorId, waveform, nextIndex, nextWakeUpTime,
                    nextVibratorStopTime());
        }

        /** Return next time the vibrator will stop after this step is played. */
        private long nextVibratorStopTime() {
            if (currentIndex < 0 || waveform.getTimings()[currentIndex] == 0
                    || startTime < vibratorStopTime) {
                return vibratorStopTime;
            }
            return startTime + getVibratorOnDuration(waveform, currentIndex);
        }
    }

    /** Represent a delay step with fixed duration, that starts counting when it starts playing. */
    private final class DelayStep implements Step {
        private final int mDelay;

        DelayStep(int delay) {
            mDelay = delay;
        }

        @Override
        public Vibration.Status play() {
            Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "DelayStep");
            try {
                if (DEBUG) {
                    Slog.d(TAG, "DelayStep of " + mDelay + "ms starting...");
                }
                waitUntil(SystemClock.uptimeMillis() + mDelay);
                return mForceStop ? Vibration.Status.CANCELLED : Vibration.Status.FINISHED;
            } finally {
                if (DEBUG) {
                    Slog.d(TAG, "DelayStep done.");
                }
                Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
            }
        }
    }
}
