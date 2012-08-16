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

import android.os.Looper;
import android.os.PowerManager;
import android.util.FloatProperty;
import android.util.IntProperty;
import android.util.Slog;
import android.view.Choreographer;

import java.io.PrintWriter;

/**
 * Represents the current display power state and realizes it.
 *
 * This component is similar in nature to a {@link View} except that it describes
 * the properties of a display.  When properties are changed, the component
 * invalidates itself and posts a callback to the {@link Choreographer} to
 * apply the changes.  This mechanism enables the display power state to be
 * animated smoothly by the animation framework.
 *
 * This component must only be created or accessed by the {@link Looper} thread
 * that belongs to the {@link DisplayPowerController}.
 *
 * We don't need to worry about holding a suspend blocker here because the
 * {@link DisplayPowerController} does that for us whenever there is a pending invalidate.
 */
final class DisplayPowerState {
    private static final String TAG = "DisplayPowerState";

    private static boolean DEBUG = false;

    private static final int DIRTY_SCREEN_ON = 1 << 0;
    private static final int DIRTY_ELECTRON_BEAM = 1 << 1;
    private static final int DIRTY_BRIGHTNESS = 1 << 2;

    private static final int DIRTY_ALL = 0xffffffff;

    private final Choreographer mChoreographer;
    private final ElectronBeam mElectronBeam;
    private final PhotonicModulator mScreenBrightnessModulator;

    private int mDirty;
    private boolean mScreenOn;
    private float mElectronBeamLevel;
    private int mScreenBrightness;

    private Runnable mCleanListener;

    public DisplayPowerState(ElectronBeam electronBean,
            PhotonicModulator screenBrightnessModulator) {
        mChoreographer = Choreographer.getInstance();
        mElectronBeam = electronBean;
        mScreenBrightnessModulator = screenBrightnessModulator;

        mScreenOn = true;
        mElectronBeamLevel = 1.0f;
        mScreenBrightness = PowerManager.BRIGHTNESS_ON;
        invalidate(DIRTY_ALL);
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
            invalidate(DIRTY_SCREEN_ON);
        }
    }

    /**
     * Returns true if the screen is on.
     */
    public boolean isScreenOn() {
        return mScreenOn;
    }

    /**
     * Prepares the electron beam to turn on or off.
     * This method should be called before starting an animation because it
     * can take a fair amount of time to prepare the electron beam surface.
     *
     * @param warmUp True if the electron beam should start warming up.
     * @return True if the electron beam was prepared.
     */
    public boolean prepareElectronBeam(boolean warmUp) {
        boolean success = mElectronBeam.prepare(warmUp);
        invalidate(DIRTY_ELECTRON_BEAM);
        return success;
    }

    /**
     * Dismisses the electron beam surface.
     */
    public void dismissElectronBeam() {
        mElectronBeam.dismiss();
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
            invalidate(DIRTY_ELECTRON_BEAM);
        }
    }

    /**
     * Gets the level of the electron beam steering current.
     */
    public float getElectronBeamLevel() {
        return mElectronBeamLevel;
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
            invalidate(DIRTY_BRIGHTNESS);
        }
    }

    /**
     * Gets the screen brightness.
     */
    public int getScreenBrightness() {
        return mScreenBrightness;
    }

    /**
     * Returns true if no properties have been invalidated.
     * Otherwise, returns false and promises to invoke the specified listener
     * when the properties have all been applied.
     * The listener always overrides any previously set listener.
     */
    public boolean waitUntilClean(Runnable listener) {
        if (mDirty != 0) {
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
        pw.println("  mDirty=" + Integer.toHexString(mDirty));
        pw.println("  mScreenOn=" + mScreenOn);
        pw.println("  mScreenBrightness=" + mScreenBrightness);
        pw.println("  mElectronBeamLevel=" + mElectronBeamLevel);

        mElectronBeam.dump(pw);
    }

    private void invalidate(int dirty) {
        if (mDirty == 0) {
            mChoreographer.postCallback(Choreographer.CALLBACK_TRAVERSAL,
                    mTraversalRunnable, null);
        }

        mDirty |= dirty;
    }

    private void apply() {
        if (mDirty != 0) {
            if ((mDirty & DIRTY_SCREEN_ON) != 0 && !mScreenOn) {
                PowerManagerService.nativeSetScreenState(false);
            }

            if ((mDirty & DIRTY_ELECTRON_BEAM) != 0) {
                mElectronBeam.draw(mElectronBeamLevel);
            }

            if ((mDirty & (DIRTY_BRIGHTNESS | DIRTY_SCREEN_ON | DIRTY_ELECTRON_BEAM)) != 0) {
                mScreenBrightnessModulator.setBrightness(mScreenOn ?
                        (int)(mScreenBrightness * mElectronBeamLevel) : 0);
            }

            if ((mDirty & DIRTY_SCREEN_ON) != 0 && mScreenOn) {
                PowerManagerService.nativeSetScreenState(true);
            }

            mDirty = 0;

            if (mCleanListener != null) {
                mCleanListener.run();
            }
        }
    }

    private final Runnable mTraversalRunnable = new Runnable() {
        @Override
        public void run() {
            if (mDirty != 0) {
                apply();
            }
        }
    };
}
