/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.internal.display;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManager.DisplayListener;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.MathUtils;
import android.util.Slog;
import android.view.Display;

import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;

/**
 * BrightnessSynchronizer helps convert between the int (old) system and float
 * (new) system for storing the brightness. It has methods to convert between the two and also
 * observes for when one of the settings is changed and syncs this with the other.
 */
public class BrightnessSynchronizer {
    private static final String TAG = "BrightnessSynchronizer";

    private static final boolean DEBUG = false;
    private static final Uri BRIGHTNESS_URI =
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS);

    private static final long WAIT_FOR_RESPONSE_MILLIS = 200;

    private static final int MSG_RUN_UPDATE = 1;

    // The tolerance within which we consider brightness values approximately equal to eachother.
    // This value is approximately 1/3 of the smallest possible brightness value.
    public static final float EPSILON = 0.001f;

    private static int sBrightnessUpdateCount = 1;

    private final Context mContext;
    private final BrightnessSyncObserver mBrightnessSyncObserver;
    private final Clock mClock;
    private final Handler mHandler;

    private DisplayManager mDisplayManager;
    private int mLatestIntBrightness;
    private float mLatestFloatBrightness;
    private BrightnessUpdate mCurrentUpdate;
    private BrightnessUpdate mPendingUpdate;

    public BrightnessSynchronizer(Context context) {
        this(context, Looper.getMainLooper(), SystemClock::uptimeMillis);
    }

    @VisibleForTesting
    public BrightnessSynchronizer(Context context, Looper looper, Clock clock) {
        mContext = context;
        mClock = clock;
        mBrightnessSyncObserver = new BrightnessSyncObserver();
        mHandler = new BrightnessSynchronizerHandler(looper);
    }

    /**
     * Starts brightnessSyncObserver to ensure that the float and int brightness values stay
     * in sync.
     * This also ensures that values are synchronized at system start up too.
     * So we force an update to the int value, since float is the source of truth. Fallback to int
     * value, if float is invalid. If both are invalid, use default float value from config.
     */
    public void startSynchronizing() {
        if (mDisplayManager == null) {
            mDisplayManager = mContext.getSystemService(DisplayManager.class);
        }
        if (mBrightnessSyncObserver.isObserving()) {
            Slog.wtf(TAG, "Brightness sync observer requesting synchronization a second time.");
            return;
        }
        mLatestFloatBrightness = getScreenBrightnessFloat();
        mLatestIntBrightness = getScreenBrightnessInt();
        Slog.i(TAG, "Initial brightness readings: " + mLatestIntBrightness + "(int), "
                + mLatestFloatBrightness + "(float)");

        if (!Float.isNaN(mLatestFloatBrightness)) {
            mPendingUpdate = new BrightnessUpdate(BrightnessUpdate.TYPE_FLOAT,
                    mLatestFloatBrightness);
        } else if (mLatestIntBrightness != PowerManager.BRIGHTNESS_INVALID) {
            mPendingUpdate = new BrightnessUpdate(BrightnessUpdate.TYPE_INT,
                    mLatestIntBrightness);
        } else {
            final float defaultBrightness = mContext.getResources().getFloat(
                    com.android.internal.R.dimen.config_screenBrightnessSettingDefaultFloat);
            mPendingUpdate = new BrightnessUpdate(BrightnessUpdate.TYPE_FLOAT, defaultBrightness);
            Slog.i(TAG, "Setting initial brightness to default value of: " + defaultBrightness);
        }

        mBrightnessSyncObserver.startObserving();
        mHandler.sendEmptyMessageAtTime(MSG_RUN_UPDATE, mClock.uptimeMillis());
    }

    /**
     * Prints data on dumpsys.
     */
    public void dump(PrintWriter pw) {
        pw.println("BrightnessSynchronizer");
        pw.println("  mLatestIntBrightness=" + mLatestIntBrightness);
        pw.println("  mLatestFloatBrightness=" + mLatestFloatBrightness);
        pw.println("  mCurrentUpdate=" + mCurrentUpdate);
        pw.println("  mPendingUpdate=" + mPendingUpdate);
    }

    /**
     * Converts between the int brightness system and the float brightness system.
     */
    public static float brightnessIntToFloat(int brightnessInt) {
        if (brightnessInt == PowerManager.BRIGHTNESS_OFF) {
            return PowerManager.BRIGHTNESS_OFF_FLOAT;
        } else if (brightnessInt == PowerManager.BRIGHTNESS_INVALID) {
            return PowerManager.BRIGHTNESS_INVALID_FLOAT;
        } else {
            final float minFloat = PowerManager.BRIGHTNESS_MIN;
            final float maxFloat = PowerManager.BRIGHTNESS_MAX;
            final float minInt = PowerManager.BRIGHTNESS_OFF + 1;
            final float maxInt = PowerManager.BRIGHTNESS_ON;
            return MathUtils.constrainedMap(minFloat, maxFloat, minInt, maxInt, brightnessInt);
        }
    }

    /**
     * Converts between the float brightness system and the int brightness system.
     */
    public static int brightnessFloatToInt(float brightnessFloat) {
        return Math.round(brightnessFloatToIntRange(brightnessFloat));
    }

    /**
     * Translates specified value from the float brightness system to the int brightness system,
     * given the min/max of each range. Accounts for special values such as OFF and invalid values.
     * Value returned as a float primitive (to preserve precision), but is a value within the
     * int-system range.
     */
    public static float brightnessFloatToIntRange(float brightnessFloat) {
        if (floatEquals(brightnessFloat, PowerManager.BRIGHTNESS_OFF_FLOAT)) {
            return PowerManager.BRIGHTNESS_OFF;
        } else if (Float.isNaN(brightnessFloat)) {
            return PowerManager.BRIGHTNESS_INVALID;
        } else {
            final float minFloat = PowerManager.BRIGHTNESS_MIN;
            final float maxFloat = PowerManager.BRIGHTNESS_MAX;
            final float minInt = PowerManager.BRIGHTNESS_OFF + 1;
            final float maxInt = PowerManager.BRIGHTNESS_ON;
            return MathUtils.constrainedMap(minInt, maxInt, minFloat, maxFloat, brightnessFloat);
        }
    }

    /**
     * Consumes a brightness change event for the float-based brightness.
     *
     * @param brightness Float brightness.
     */
    private void handleBrightnessChangeFloat(float brightness) {
        mLatestFloatBrightness = brightness;
        handleBrightnessChange(BrightnessUpdate.TYPE_FLOAT, brightness);
    }

    /**
     * Consumes a brightness change event for the int-based brightness.
     *
     * @param brightness Int brightness.
     */
    private void handleBrightnessChangeInt(int brightness) {
        mLatestIntBrightness = brightness;
        handleBrightnessChange(BrightnessUpdate.TYPE_INT, brightness);
    }

    /**
     * Consumes a brightness change event.
     *
     * @param type Type of the brightness change (int/float)
     * @param brightness brightness.
     */
    private void handleBrightnessChange(int type, float brightness) {
        boolean swallowUpdate = mCurrentUpdate != null
                && mCurrentUpdate.swallowUpdate(type, brightness);
        BrightnessUpdate prevUpdate = null;
        if (!swallowUpdate) {
            prevUpdate = mPendingUpdate;
            mPendingUpdate = new BrightnessUpdate(type, brightness);
        }
        runUpdate();

        // If we created a new update and it is still pending after the update, add a log.
        if (!swallowUpdate && mPendingUpdate != null) {
            Slog.i(TAG, "New PendingUpdate: " + mPendingUpdate + ", prev=" + prevUpdate);
        }
    }

    /**
     * Runs updates for current and pending BrightnessUpdates.
     */
    private void runUpdate() {
        if (DEBUG) {
            Slog.d(TAG, "Running update mCurrent="  + mCurrentUpdate
                    + ", mPending=" + mPendingUpdate);
        }

        // do-while instead of while to allow mCurrentUpdate to get set if there's a pending update.
        do {
            if (mCurrentUpdate != null) {
                mCurrentUpdate.update();
                if (mCurrentUpdate.isRunning()) {
                    break; // current update is still running, nothing to do.
                } else if (mCurrentUpdate.isCompleted()) {
                    if (mCurrentUpdate.madeUpdates()) {
                        Slog.i(TAG, "Completed Update: " + mCurrentUpdate);
                    }
                    mCurrentUpdate = null;
                }
            }
            // No current update any more, lets start the next update if there is one.
            if (mCurrentUpdate == null && mPendingUpdate != null) {
                mCurrentUpdate = mPendingUpdate;
                mPendingUpdate = null;
            }
        } while (mCurrentUpdate != null);
    }

    /**
     * Gets the stored screen brightness float value from the display brightness setting.
     * @return brightness
     */
    private float getScreenBrightnessFloat() {
        return mDisplayManager.getBrightness(Display.DEFAULT_DISPLAY);
    }

    /**
     * Gets the stored screen brightness int from the system settings.
     * @return brightness
     */
    private int getScreenBrightnessInt() {
        return Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS, PowerManager.BRIGHTNESS_INVALID,
                UserHandle.USER_CURRENT);
    }

    /**
     * Tests whether two brightness float values are within a small enough tolerance
     * of each other.
     * @param a first float to compare
     * @param b second float to compare
     * @return whether the two values are within a small enough tolerance value
     */
    public static boolean floatEquals(float a, float b) {
        if (a == b) {
            return true;
        } else if (Float.isNaN(a) && Float.isNaN(b)) {
            return true;
        } else if (Math.abs(a - b) < EPSILON) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * Encapsulates a brightness change event and contains logic for synchronizing the appropriate
     * settings for the specified brightness change.
     */
    @VisibleForTesting
    public class BrightnessUpdate {
        static final int TYPE_INT = 0x1;
        static final int TYPE_FLOAT = 0x2;

        private static final int STATE_NOT_STARTED = 1;
        private static final int STATE_RUNNING = 2;
        private static final int STATE_COMPLETED = 3;

        private final int mSourceType;
        private final float mBrightness;

        private long mTimeUpdated;
        private int mState;
        private int mUpdatedTypes;
        private int mConfirmedTypes;
        private int mId;

        BrightnessUpdate(int sourceType, float brightness) {
            mId = sBrightnessUpdateCount++;
            mSourceType = sourceType;
            mBrightness = brightness;
            mTimeUpdated = 0;
            mUpdatedTypes = 0x0;
            mConfirmedTypes = 0x0;
            mState = STATE_NOT_STARTED;
        }

        @Override
        public String toString() {
            return "{[" + mId + "] " + toStringLabel(mSourceType, mBrightness)
                    + ", mUpdatedTypes=" + mUpdatedTypes + ", mConfirmedTypes=" + mConfirmedTypes
                    + ", mTimeUpdated=" + mTimeUpdated + "}";
        }

        /**
         * Runs the synchronization process, moving forward through the internal state machine.
         */
        void update() {
            if (mState == STATE_NOT_STARTED) {
                mState = STATE_RUNNING;

                // check if we need to update int
                int brightnessInt = getBrightnessAsInt();
                if (mLatestIntBrightness != brightnessInt) {
                    Settings.System.putIntForUser(mContext.getContentResolver(),
                            Settings.System.SCREEN_BRIGHTNESS, brightnessInt,
                            UserHandle.USER_CURRENT);
                    mLatestIntBrightness = brightnessInt;
                    mUpdatedTypes |= TYPE_INT;
                }

                // check if we need to update float
                float brightnessFloat = getBrightnessAsFloat();
                if (!floatEquals(mLatestFloatBrightness, brightnessFloat)) {
                    mDisplayManager.setBrightness(Display.DEFAULT_DISPLAY, brightnessFloat);
                    mLatestFloatBrightness = brightnessFloat;
                    mUpdatedTypes |= TYPE_FLOAT;
                }

                // If we made updates, lets wait for responses.
                if (mUpdatedTypes != 0x0) {
                    // Give some time for our updates to return a confirmation response. If they
                    // don't return by that time, MSG_RUN_UPDATE will get sent and we will stop
                    // listening for responses and mark this update as complete.
                    if (DEBUG) {
                        Slog.d(TAG, "Sending MSG_RUN_UPDATE for "
                                + toStringLabel(mSourceType, mBrightness));
                    }
                    Slog.i(TAG, "[" + mId + "] New Update "
                            + toStringLabel(mSourceType, mBrightness) + " set brightness values: "
                            + toStringLabel(mUpdatedTypes & TYPE_FLOAT, brightnessFloat) + " "
                            + toStringLabel(mUpdatedTypes & TYPE_INT, brightnessInt));

                    mHandler.sendEmptyMessageAtTime(MSG_RUN_UPDATE,
                            mClock.uptimeMillis() + WAIT_FOR_RESPONSE_MILLIS);
                }
                mTimeUpdated = mClock.uptimeMillis();
            }

            if (mState == STATE_RUNNING) {
                // If we're not waiting on any more confirmations or the time has expired, move to
                // completed state.
                if (mConfirmedTypes == mUpdatedTypes
                        || (mTimeUpdated + WAIT_FOR_RESPONSE_MILLIS) < mClock.uptimeMillis()) {
                    mState = STATE_COMPLETED;
                }
            }
        }

        /**
         * Attempts to consume the specified brightness change if it is determined that the change
         * is a notification of a change previously made by this class.
         *
         * @param type The type of change (int|float)
         * @param brightness The brightness value.
         * @return True if the change was caused by this class, thus swallowed.
         */
        boolean swallowUpdate(int type, float brightness) {
            if ((mUpdatedTypes & type) != type || (mConfirmedTypes & type) != 0x0) {
                // It's either a type we didn't update, or one we've already confirmed.
                return false;
            }

            final boolean floatUpdateConfirmed =
                    type == TYPE_FLOAT && floatEquals(getBrightnessAsFloat(), brightness);
            final boolean intUpdateConfirmed =
                    type == TYPE_INT && getBrightnessAsInt() == (int) brightness;

            if (floatUpdateConfirmed || intUpdateConfirmed) {
                mConfirmedTypes |= type;
                Slog.i(TAG, "Swallowing update of " + toStringLabel(type, brightness)
                        + " by update: " + this);
                return true;
            }
            return false;
        }

        boolean isRunning() {
            return mState == STATE_RUNNING;
        }

        boolean isCompleted() {
            return mState == STATE_COMPLETED;
        }

        boolean madeUpdates() {
            return mUpdatedTypes != 0x0;
        }

        private int getBrightnessAsInt() {
            if (mSourceType == TYPE_INT) {
                return (int) mBrightness;
            }
            return brightnessFloatToInt(mBrightness);
        }

        private float getBrightnessAsFloat() {
            if (mSourceType == TYPE_FLOAT) {
                return mBrightness;
            }
            return brightnessIntToFloat((int) mBrightness);
        }

        private String toStringLabel(int type, float brightness) {
            return (type == TYPE_INT) ? ((int) brightness) + "(i)"
                    : ((type == TYPE_FLOAT) ? brightness + "(f)"
                    : "");
        }
    }

    /** Functional interface for providing time. */
    @VisibleForTesting
    public interface Clock {
        /** @return system uptime in milliseconds. */
        long uptimeMillis();
    }

    class BrightnessSynchronizerHandler extends Handler {
        BrightnessSynchronizerHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_RUN_UPDATE:
                    if (DEBUG) {
                        Slog.d(TAG, "MSG_RUN_UPDATE");
                    }
                    runUpdate();
                    break;
                default:
                    super.handleMessage(msg);
            }

        }
    };

    private class BrightnessSyncObserver {
        private boolean mIsObserving;

        private final DisplayListener mListener = new DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {}

            @Override
            public void onDisplayRemoved(int displayId) {}

            @Override
            public void onDisplayChanged(int displayId) {
                handleBrightnessChangeFloat(getScreenBrightnessFloat());
            }
        };

        private final ContentObserver mContentObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                if (selfChange) {
                    return;
                }
                if (BRIGHTNESS_URI.equals(uri)) {
                    handleBrightnessChangeInt(getScreenBrightnessInt());
                }
            }
        };

        boolean isObserving() {
            return mIsObserving;
        }

        void startObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.registerContentObserver(BRIGHTNESS_URI, false, mContentObserver,
                    UserHandle.USER_ALL);
            mDisplayManager.registerDisplayListener(mListener, mHandler,
                    DisplayManager.EVENT_FLAG_DISPLAY_BRIGHTNESS);
            mIsObserving = true;
        }

    }
}
