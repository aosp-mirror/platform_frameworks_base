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

package com.android.server.power;

import com.android.server.LightsService;

import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.FloatProperty;
import android.util.IntProperty;
import android.util.Slog;
import android.view.Choreographer;

import java.io.PrintWriter;

/**
 * Controls the display power state.
 * <p>
 * This component is similar in nature to a {@link View} except that it describes
 * the properties of a display.  When properties are changed, the component
 * invalidates itself and posts a callback to apply the changes in a consistent order.
 * This mechanism enables multiple properties of the display power state to be animated
 * together smoothly by the animation framework.  Some of the work to blank or unblank
 * the display is done on a separate thread to avoid blocking the looper.
 * </p><p>
 * This component must only be created or accessed by the {@link Looper} thread
 * that belongs to the {@link DisplayPowerController}.
 * </p><p>
 * We don't need to worry about holding a suspend blocker here because the
 * {@link PowerManagerService} does that for us whenever there is a change
 * in progress.
 * </p>
 */
final class DisplayPowerState {
    private static final String TAG = "DisplayPowerState";

    private static boolean DEBUG = false;

    private final Handler mHandler;
    private final Choreographer mChoreographer;
    private final ElectronBeam mElectronBeam;
    private final DisplayBlanker mDisplayBlanker;
    private final LightsService.Light mBacklight;
    private final PhotonicModulator mPhotonicModulator;

    private boolean mScreenOn;
    private int mScreenBrightness;
    private boolean mScreenReady;
    private boolean mScreenUpdatePending;

    private boolean mElectronBeamPrepared;
    private float mElectronBeamLevel;
    private boolean mElectronBeamReady;
    private boolean mElectronBeamDrawPending;

    private Runnable mCleanListener;

    public DisplayPowerState(ElectronBeam electronBean,
            DisplayBlanker displayBlanker, LightsService.Light backlight) {
        mHandler = new Handler(true /*async*/);
        mChoreographer = Choreographer.getInstance();
        mElectronBeam = electronBean;
        mDisplayBlanker = displayBlanker;
        mBacklight = backlight;
        mPhotonicModulator = new PhotonicModulator();

        // At boot time, we know that the screen is on and the electron beam
        // animation is not playing.  We don't know the screen's brightness though,
        // so prepare to set it to a known state when the state is next applied.
        // Although we set the brightness to full on here, the display power controller
        // will reset the brightness to a new level immediately before the changes
        // actually have a chance to be applied.
        mScreenOn = true;
        mScreenBrightness = PowerManager.BRIGHTNESS_ON;
        scheduleScreenUpdate();

        mElectronBeamPrepared = false;
        mElectronBeamLevel = 1.0f;
        mElectronBeamReady = true;
    }

    public static final FloatProperty<DisplayPowerState> ELECTRON_BEAM_LEVEL =
            new FloatProperty<DisplayPowerState>("electronBeamLevel") {
        @Override
        public void setValue(DisplayPowerState object, float value) {
            object.setElectronBeamLevel(value);
        }

        @Override
        public Float get(DisplayPowerState object) {
            return object.getElectronBeamLevel();
        }
    };

    public static final IntProperty<DisplayPowerState> SCREEN_BRIGHTNESS =
            new IntProperty<DisplayPowerState>("screenBrightness") {
        @Override
        public void setValue(DisplayPowerState object, int value) {
            object.setScreenBrightness(value);
        }

        @Override
        public Integer get(DisplayPowerState object) {
            return object.getScreenBrightness();
        }
    };

    /**
     * Sets whether the screen is on or off.
     */
    public void setScreenOn(boolean on) {
        if (mScreenOn != on) {
            if (DEBUG) {
                Slog.d(TAG, "setScreenOn: on=" + on);
            }

            mScreenOn = on;
            mScreenReady = false;
            scheduleScreenUpdate();
        }
    }

    /**
     * Returns true if the screen is on.
     */
    public boolean isScreenOn() {
        return mScreenOn;
    }

    /**
     * Sets the display brightness.
     *
     * @param brightness The brightness, ranges from 0 (minimum / off) to 255 (brightest).
     */
    public void setScreenBrightness(int brightness) {
        if (mScreenBrightness != brightness) {
            if (DEBUG) {
                Slog.d(TAG, "setScreenBrightness: brightness=" + brightness);
            }

            mScreenBrightness = brightness;
            if (mScreenOn) {
                mScreenReady = false;
                scheduleScreenUpdate();
            }
        }
    }

    /**
     * Gets the screen brightness.
     */
    public int getScreenBrightness() {
        return mScreenBrightness;
    }

    /**
     * Prepares the electron beam to turn on or off.
     * This method should be called before starting an animation because it
     * can take a fair amount of time to prepare the electron beam surface.
     *
     * @param mode The electron beam animation mode to prepare.
     * @return True if the electron beam was prepared.
     */
    public boolean prepareElectronBeam(int mode) {
        if (!mElectronBeam.prepare(mode)) {
            mElectronBeamPrepared = false;
            mElectronBeamReady = true;
            return false;
        }

        mElectronBeamPrepared = true;
        mElectronBeamReady = false;
        scheduleElectronBeamDraw();
        return true;
    }

    /**
     * Dismisses the electron beam surface.
     */
    public void dismissElectronBeam() {
        mElectronBeam.dismiss();
        mElectronBeamPrepared = false;
        mElectronBeamReady = true;
    }

    /**
     * Sets the level of the electron beam steering current.
     *
     * The display is blanked when the level is 0.0.  In normal use, the electron
     * beam should have a value of 1.0.  The electron beam is unstable in between
     * these states and the picture quality may be compromised.  For best effect,
     * the electron beam should be warmed up or cooled off slowly.
     *
     * Warning: Electron beam emits harmful radiation.  Avoid direct exposure to
     * skin or eyes.
     *
     * @param level The level, ranges from 0.0 (full off) to 1.0 (full on).
     */
    public void setElectronBeamLevel(float level) {
        if (mElectronBeamLevel != level) {
            if (DEBUG) {
                Slog.d(TAG, "setElectronBeamLevel: level=" + level);
            }

            mElectronBeamLevel = level;
            if (mScreenOn) {
                mScreenReady = false;
                scheduleScreenUpdate(); // update backlight brightness
            }
            if (mElectronBeamPrepared) {
                mElectronBeamReady = false;
                scheduleElectronBeamDraw();
            }
        }
    }

    /**
     * Gets the level of the electron beam steering current.
     */
    public float getElectronBeamLevel() {
        return mElectronBeamLevel;
    }

    /**
     * Returns true if no properties have been invalidated.
     * Otherwise, returns false and promises to invoke the specified listener
     * when the properties have all been applied.
     * The listener always overrides any previously set listener.
     */
    public boolean waitUntilClean(Runnable listener) {
        if (!mScreenReady || !mElectronBeamReady) {
            mCleanListener = listener;
            return false;
        } else {
            mCleanListener = null;
            return true;
        }
    }

    public void dump(PrintWriter pw) {
        pw.println();
        pw.println("Display Power State:");
        pw.println("  mScreenOn=" + mScreenOn);
        pw.println("  mScreenBrightness=" + mScreenBrightness);
        pw.println("  mScreenReady=" + mScreenReady);
        pw.println("  mScreenUpdatePending=" + mScreenUpdatePending);
        pw.println("  mElectronBeamPrepared=" + mElectronBeamPrepared);
        pw.println("  mElectronBeamLevel=" + mElectronBeamLevel);
        pw.println("  mElectronBeamReady=" + mElectronBeamReady);
        pw.println("  mElectronBeamDrawPending=" + mElectronBeamDrawPending);

        mPhotonicModulator.dump(pw);
        mElectronBeam.dump(pw);
    }

    private void scheduleScreenUpdate() {
        if (!mScreenUpdatePending) {
            mScreenUpdatePending = true;
            postScreenUpdateThreadSafe();
        }
    }

    private void postScreenUpdateThreadSafe() {
        mHandler.removeCallbacks(mScreenUpdateRunnable);
        mHandler.post(mScreenUpdateRunnable);
    }

    private void scheduleElectronBeamDraw() {
        if (!mElectronBeamDrawPending) {
            mElectronBeamDrawPending = true;
            mChoreographer.postCallback(Choreographer.CALLBACK_TRAVERSAL,
                    mElectronBeamDrawRunnable, null);
        }
    }

    private void invokeCleanListenerIfNeeded() {
        final Runnable listener = mCleanListener;
        if (listener != null && mScreenReady && mElectronBeamReady) {
            mCleanListener = null;
            listener.run();
        }
    }

    private final Runnable mScreenUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            mScreenUpdatePending = false;

            int brightness = mScreenOn && mElectronBeamLevel > 0f ? mScreenBrightness : 0;
            if (mPhotonicModulator.setState(mScreenOn, brightness)) {
                if (DEBUG) {
                    Slog.d(TAG, "Screen ready");
                }
                mScreenReady = true;
                invokeCleanListenerIfNeeded();
            } else {
                if (DEBUG) {
                    Slog.d(TAG, "Screen not ready");
                }
            }
        }
    };

    private final Runnable mElectronBeamDrawRunnable = new Runnable() {
        @Override
        public void run() {
            mElectronBeamDrawPending = false;

            if (mElectronBeamPrepared) {
                mElectronBeam.draw(mElectronBeamLevel);
            }

            mElectronBeamReady = true;
            invokeCleanListenerIfNeeded();
        }
    };

    /**
     * Updates the state of the screen and backlight asynchronously on a separate thread.
     */
    private final class PhotonicModulator {
        private static final boolean INITIAL_SCREEN_ON = false; // unknown, assume off
        private static final int INITIAL_BACKLIGHT = -1; // unknown

        private final Object mLock = new Object();

        private boolean mPendingOn = INITIAL_SCREEN_ON;
        private int mPendingBacklight = INITIAL_BACKLIGHT;
        private boolean mActualOn = INITIAL_SCREEN_ON;
        private int mActualBacklight = INITIAL_BACKLIGHT;
        private boolean mChangeInProgress;

        public boolean setState(boolean on, int backlight) {
            synchronized (mLock) {
                if (on != mPendingOn || backlight != mPendingBacklight) {
                    if (DEBUG) {
                        Slog.d(TAG, "Requesting new screen state: on=" + on
                                + ", backlight=" + backlight);
                    }

                    mPendingOn = on;
                    mPendingBacklight = backlight;

                    if (!mChangeInProgress) {
                        mChangeInProgress = true;
                        AsyncTask.THREAD_POOL_EXECUTOR.execute(mTask);
                    }
                }
                return !mChangeInProgress;
            }
        }

        public void dump(PrintWriter pw) {
            pw.println();
            pw.println("Photonic Modulator State:");
            pw.println("  mPendingOn=" + mPendingOn);
            pw.println("  mPendingBacklight=" + mPendingBacklight);
            pw.println("  mActualOn=" + mActualOn);
            pw.println("  mActualBacklight=" + mActualBacklight);
            pw.println("  mChangeInProgress=" + mChangeInProgress);
        }

        private final Runnable mTask = new Runnable() {
            @Override
            public void run() {
                // Apply pending changes until done.
                for (;;) {
                    final boolean on;
                    final boolean onChanged;
                    final int backlight;
                    final boolean backlightChanged;
                    synchronized (mLock) {
                        on = mPendingOn;
                        onChanged = (on != mActualOn);
                        backlight = mPendingBacklight;
                        backlightChanged = (backlight != mActualBacklight);
                        if (!onChanged && !backlightChanged) {
                            mChangeInProgress = false;
                            break;
                        }
                        mActualOn = on;
                        mActualBacklight = backlight;
                    }

                    if (DEBUG) {
                        Slog.d(TAG, "Updating screen state: on=" + on
                                + ", backlight=" + backlight);
                    }
                    if (onChanged && on) {
                        mDisplayBlanker.unblankAllDisplays();
                    }
                    if (backlightChanged) {
                        mBacklight.setBrightness(backlight);
                    }
                    if (onChanged && !on) {
                        mDisplayBlanker.blankAllDisplays();
                    }
                }

                // Let the outer class know that all changes have been applied.
                postScreenUpdateThreadSafe();
            }
        };
    }
}
