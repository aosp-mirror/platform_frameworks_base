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

package com.android.server.display;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Trace;
import android.util.FloatProperty;
import android.util.Slog;
import android.view.Choreographer;
import android.view.Display;

import java.io.PrintWriter;

/**
 * Controls the display power state.
 * <p>
 * This component is similar in nature to a {@link android.view.View} except that it
 * describes the properties of a display.  When properties are changed, the component
 * invalidates itself and posts a callback to apply the changes in a consistent order.
 * This mechanism enables multiple properties of the display power state to be animated
 * together smoothly by the animation framework.  Some of the work to blank or unblank
 * the display is done on a separate thread to avoid blocking the looper.
 * </p><p>
 * This component must only be created or accessed by the {@link Looper} thread
 * that belongs to the {@link DisplayPowerController}.
 * </p><p>
 * We don't need to worry about holding a suspend blocker here because the
 * power manager does that for us whenever there is a change in progress.
 * </p>
 */
final class DisplayPowerState {
    private static final String TAG = "DisplayPowerState";

    private static boolean DEBUG = false;
    private static String COUNTER_COLOR_FADE = "ColorFadeLevel";

    private final Handler mHandler;
    private final Choreographer mChoreographer;
    private final DisplayBlanker mBlanker;
    private final ColorFade mColorFade;
    private final PhotonicModulator mPhotonicModulator;
    private final int mDisplayId;

    private int mScreenState;
    private float mScreenBrightness;
    private float mSdrScreenBrightness;
    private boolean mScreenReady;
    private boolean mScreenUpdatePending;

    private boolean mColorFadePrepared;
    private float mColorFadeLevel;
    private boolean mColorFadeReady;
    private boolean mColorFadeDrawPending;

    private Runnable mCleanListener;

    private volatile boolean mStopped;

    DisplayPowerState(
            DisplayBlanker blanker, ColorFade colorFade, int displayId, int displayState) {
        mHandler = new Handler(true /*async*/);
        mChoreographer = Choreographer.getInstance();
        mBlanker = blanker;
        mColorFade = colorFade;
        mPhotonicModulator = new PhotonicModulator();
        mPhotonicModulator.start();
        mDisplayId = displayId;

        // At boot time, we don't know the screen's brightness,
        // so prepare to set it to a known state when the state is next applied.
        // Although we set the brightness here, the display power controller
        // will reset the brightness to a new level immediately before the changes
        // actually have a chance to be applied.
        mScreenState = displayState;
        mScreenBrightness = (displayState != Display.STATE_OFF) ? PowerManager.BRIGHTNESS_MAX
                : PowerManager.BRIGHTNESS_OFF_FLOAT;
        mSdrScreenBrightness = mScreenBrightness;
        scheduleScreenUpdate();

        mColorFadePrepared = false;
        mColorFadeLevel = 1.0f;
        mColorFadeReady = true;
    }

    public static final FloatProperty<DisplayPowerState> COLOR_FADE_LEVEL =
            new FloatProperty<DisplayPowerState>("electronBeamLevel") {
        @Override
        public void setValue(DisplayPowerState object, float value) {
            object.setColorFadeLevel(value);
        }

        @Override
        public Float get(DisplayPowerState object) {
            return object.getColorFadeLevel();
        }
    };


    public static final FloatProperty<DisplayPowerState> SCREEN_BRIGHTNESS_FLOAT =
            new FloatProperty<DisplayPowerState>("screenBrightnessFloat") {
                @Override
                public void setValue(DisplayPowerState object, float value) {
                    object.setScreenBrightness(value);
                }

                @Override
                public Float get(DisplayPowerState object) {
                    return object.getScreenBrightness();
                }
            };

    public static final FloatProperty<DisplayPowerState> SCREEN_SDR_BRIGHTNESS_FLOAT =
            new FloatProperty<DisplayPowerState>("sdrScreenBrightnessFloat") {
                @Override
                public void setValue(DisplayPowerState object, float value) {
                    object.setSdrScreenBrightness(value);
                }

                @Override
                public Float get(DisplayPowerState object) {
                    return object.getSdrScreenBrightness();
                }
            };

    /**
     * Sets whether the screen is on, off, or dozing.
     */
    public void setScreenState(int state) {
        if (mScreenState != state) {
            if (DEBUG) {
                Slog.d(TAG, "setScreenState: state=" + state);
            }

            mScreenState = state;
            mScreenReady = false;
            scheduleScreenUpdate();
        }
    }

    /**
     * Gets the desired screen state.
     */
    public int getScreenState() {
        return mScreenState;
    }

    /**
     * Sets the display's SDR brightness.
     *
     * @param brightness The brightness, ranges from 0.0f (minimum) to 1.0f (brightest), or is -1f
     *                   (off).
     */
    public void setSdrScreenBrightness(float brightness) {
        if (mSdrScreenBrightness != brightness) {
            if (DEBUG) {
                Slog.d(TAG, "setSdrScreenBrightness: brightness=" + brightness);
            }

            mSdrScreenBrightness = brightness;
            if (mScreenState != Display.STATE_OFF) {
                mScreenReady = false;
                scheduleScreenUpdate();
            }
        }
    }

    /**
     * Gets the screen SDR brightness.
     */
    public float getSdrScreenBrightness() {
        return mSdrScreenBrightness;
    }

    /**
     * Sets the display brightness.
     *
     * @param brightness The brightness, ranges from 0.0f (minimum) to 1.0f (brightest), or is -1f
     *                   (off).
     */
    public void setScreenBrightness(float brightness) {
        if (mScreenBrightness != brightness) {
            if (DEBUG) {
                Slog.d(TAG, "setScreenBrightness: brightness=" + brightness);
            }

            mScreenBrightness = brightness;
            if (mScreenState != Display.STATE_OFF) {
                mScreenReady = false;
                scheduleScreenUpdate();
            }
        }
    }

    /**
     * Gets the screen brightness.
     */
    public float getScreenBrightness() {
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
    public boolean prepareColorFade(Context context, int mode) {
        if (mColorFade == null || !mColorFade.prepare(context, mode)) {
            mColorFadePrepared = false;
            mColorFadeReady = true;
            return false;
        }

        mColorFadePrepared = true;
        mColorFadeReady = false;
        scheduleColorFadeDraw();
        return true;
    }

    /**
     * Dismisses the color fade surface.
     */
    public void dismissColorFade() {
        Trace.traceCounter(Trace.TRACE_TAG_POWER, COUNTER_COLOR_FADE, 100);
        if (mColorFade != null) mColorFade.dismiss();
        mColorFadePrepared = false;
        mColorFadeReady = true;
    }

   /**
     * Dismisses the color fade resources.
     */
    public void dismissColorFadeResources() {
        if (mColorFade != null) mColorFade.dismissResources();
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
    public void setColorFadeLevel(float level) {
        if (mColorFadeLevel != level) {
            if (DEBUG) {
                Slog.d(TAG, "setColorFadeLevel: level=" + level);
            }

            mColorFadeLevel = level;
            if (mScreenState != Display.STATE_OFF) {
                mScreenReady = false;
                scheduleScreenUpdate(); // update backlight brightness
            }
            if (mColorFadePrepared) {
                mColorFadeReady = false;
                scheduleColorFadeDraw();
            }
        }
    }

    /**
     * Gets the level of the electron beam steering current.
     */
    public float getColorFadeLevel() {
        return mColorFadeLevel;
    }

    /**
     * Returns true if no properties have been invalidated.
     * Otherwise, returns false and promises to invoke the specified listener
     * when the properties have all been applied.
     * The listener always overrides any previously set listener.
     */
    public boolean waitUntilClean(Runnable listener) {
        if (!mScreenReady || !mColorFadeReady) {
            mCleanListener = listener;
            return false;
        } else {
            mCleanListener = null;
            return true;
        }
    }

    /**
     * Interrupts all running threads; halting future work.
     *
     * This method should be called when the DisplayPowerState is no longer in use; i.e. when
     * the {@link #mDisplayId display} has been removed.
     */
    public void stop() {
        mStopped = true;
        mPhotonicModulator.interrupt();
        dismissColorFade();
        mCleanListener = null;
        mHandler.removeCallbacksAndMessages(null);
    }

    public void dump(PrintWriter pw) {
        pw.println();
        pw.println("Display Power State:");
        pw.println("  mStopped=" + mStopped);
        pw.println("  mScreenState=" + Display.stateToString(mScreenState));
        pw.println("  mScreenBrightness=" + mScreenBrightness);
        pw.println("  mSdrScreenBrightness=" + mSdrScreenBrightness);
        pw.println("  mScreenReady=" + mScreenReady);
        pw.println("  mScreenUpdatePending=" + mScreenUpdatePending);
        pw.println("  mColorFadePrepared=" + mColorFadePrepared);
        pw.println("  mColorFadeLevel=" + mColorFadeLevel);
        pw.println("  mColorFadeReady=" + mColorFadeReady);
        pw.println("  mColorFadeDrawPending=" + mColorFadeDrawPending);

        mPhotonicModulator.dump(pw);
        if (mColorFade != null) mColorFade.dump(pw);
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

    private void scheduleColorFadeDraw() {
        if (!mColorFadeDrawPending) {
            mColorFadeDrawPending = true;
            mChoreographer.postCallback(Choreographer.CALLBACK_TRAVERSAL,
                    mColorFadeDrawRunnable, null);
        }
    }

    private void invokeCleanListenerIfNeeded() {
        final Runnable listener = mCleanListener;
        if (listener != null && mScreenReady && mColorFadeReady) {
            mCleanListener = null;
            listener.run();
        }
    }

    private final Runnable mScreenUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            mScreenUpdatePending = false;

            float brightnessState = mScreenState != Display.STATE_OFF
                    && mColorFadeLevel > 0f ? mScreenBrightness : PowerManager.BRIGHTNESS_OFF_FLOAT;
            float sdrBrightnessState = mScreenState != Display.STATE_OFF
                    && mColorFadeLevel > 0f
                            ? mSdrScreenBrightness : PowerManager.BRIGHTNESS_OFF_FLOAT;
            if (mPhotonicModulator.setState(mScreenState, brightnessState, sdrBrightnessState)) {
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

    private final Runnable mColorFadeDrawRunnable = new Runnable() {
        @Override
        public void run() {
            mColorFadeDrawPending = false;

            if (mColorFadePrepared) {
                mColorFade.draw(mColorFadeLevel);
                Trace.traceCounter(Trace.TRACE_TAG_POWER,
                        COUNTER_COLOR_FADE, Math.round(mColorFadeLevel * 100));
            }

            mColorFadeReady = true;
            invokeCleanListenerIfNeeded();
        }
    };

    /**
     * Updates the state of the screen and backlight asynchronously on a separate thread.
     */
    private final class PhotonicModulator extends Thread {
        private static final int INITIAL_SCREEN_STATE = Display.STATE_UNKNOWN;
        private static final float INITIAL_BACKLIGHT_FLOAT = PowerManager.BRIGHTNESS_INVALID_FLOAT;

        private final Object mLock = new Object();

        private int mPendingState = INITIAL_SCREEN_STATE;
        private float mPendingBacklight = INITIAL_BACKLIGHT_FLOAT;
        private float mPendingSdrBacklight = INITIAL_BACKLIGHT_FLOAT;
        private int mActualState = INITIAL_SCREEN_STATE;
        private float mActualBacklight = INITIAL_BACKLIGHT_FLOAT;
        private float mActualSdrBacklight = INITIAL_BACKLIGHT_FLOAT;
        private boolean mStateChangeInProgress;
        private boolean mBacklightChangeInProgress;

        public PhotonicModulator() {
            super("PhotonicModulator");
        }

        public boolean setState(int state, float brightnessState, float sdrBrightnessState) {
            synchronized (mLock) {
                boolean stateChanged = state != mPendingState;
                boolean backlightChanged = brightnessState != mPendingBacklight
                        || sdrBrightnessState != mPendingSdrBacklight;
                if (stateChanged || backlightChanged) {
                    if (DEBUG) {
                        Slog.d(TAG, "Requesting new screen state: state="
                                + Display.stateToString(state) + ", backlight=" + brightnessState);
                    }

                    mPendingState = state;
                    mPendingBacklight = brightnessState;
                    mPendingSdrBacklight = sdrBrightnessState;
                    boolean changeInProgress = mStateChangeInProgress || mBacklightChangeInProgress;
                    mStateChangeInProgress = stateChanged || mStateChangeInProgress;
                    mBacklightChangeInProgress = backlightChanged || mBacklightChangeInProgress;

                    if (!changeInProgress) {
                        mLock.notifyAll();
                    }
                }
                return !mStateChangeInProgress;
            }
        }

        public void dump(PrintWriter pw) {
            synchronized (mLock) {
                pw.println();
                pw.println("Photonic Modulator State:");
                pw.println("  mPendingState=" + Display.stateToString(mPendingState));
                pw.println("  mPendingBacklight=" + mPendingBacklight);
                pw.println("  mPendingSdrBacklight=" + mPendingSdrBacklight);
                pw.println("  mActualState=" + Display.stateToString(mActualState));
                pw.println("  mActualBacklight=" + mActualBacklight);
                pw.println("  mActualSdrBacklight=" + mActualSdrBacklight);
                pw.println("  mStateChangeInProgress=" + mStateChangeInProgress);
                pw.println("  mBacklightChangeInProgress=" + mBacklightChangeInProgress);
            }
        }

        @Override
        public void run() {
            for (;;) {
                // Get pending change.
                final int state;
                final boolean stateChanged;
                final float brightnessState;
                final float sdrBrightnessState;
                final boolean backlightChanged;
                synchronized (mLock) {
                    state = mPendingState;
                    stateChanged = (state != mActualState);
                    brightnessState = mPendingBacklight;
                    sdrBrightnessState = mPendingSdrBacklight;
                    backlightChanged = brightnessState != mActualBacklight
                            || sdrBrightnessState != mActualSdrBacklight;
                    if (!stateChanged) {
                        // State changed applied, notify outer class.
                        postScreenUpdateThreadSafe();
                        mStateChangeInProgress = false;
                    }
                    if (!backlightChanged) {
                        mBacklightChangeInProgress = false;
                    }
                    boolean valid = state != Display.STATE_UNKNOWN && !Float.isNaN(brightnessState);
                    boolean changed = stateChanged || backlightChanged;
                    if (!valid || !changed) {
                        try {
                            mLock.wait();
                        } catch (InterruptedException ex) {
                            if (mStopped) {
                                return;
                            }
                        }
                        continue;
                    }
                    mActualState = state;
                    mActualBacklight = brightnessState;
                    mActualSdrBacklight = sdrBrightnessState;
                }

                // Apply pending change.
                if (DEBUG) {
                    Slog.d(TAG, "Updating screen state: id=" + mDisplayId +  ", state="
                            + Display.stateToString(state) + ", backlight=" + brightnessState
                            + ", sdrBacklight=" + sdrBrightnessState);
                }
                mBlanker.requestDisplayState(mDisplayId, state, brightnessState,
                        sdrBrightnessState);
            }
        }
    }
}
