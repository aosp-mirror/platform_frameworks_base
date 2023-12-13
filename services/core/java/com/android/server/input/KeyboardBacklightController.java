/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.input;

import android.animation.ValueAnimator;
import android.annotation.BinderThread;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Color;
import android.hardware.input.IKeyboardBacklightListener;
import android.hardware.input.IKeyboardBacklightState;
import android.hardware.input.InputManager;
import android.hardware.lights.Light;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UEventObserver;
import android.text.TextUtils;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.view.InputDevice;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.TreeSet;

/**
 * A thread-safe component of {@link InputManagerService} responsible for managing the keyboard
 * backlight for supported keyboards.
 */
final class KeyboardBacklightController implements
        InputManagerService.KeyboardBacklightControllerInterface, InputManager.InputDeviceListener {

    private static final String TAG = "KbdBacklightController";

    // To enable these logs, run:
    // 'adb shell setprop log.tag.KbdBacklightController DEBUG' (requires restart)
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private enum Direction {
        DIRECTION_UP, DIRECTION_DOWN
    }
    private static final int MSG_UPDATE_EXISTING_DEVICES = 1;
    private static final int MSG_INCREMENT_KEYBOARD_BACKLIGHT = 2;
    private static final int MSG_DECREMENT_KEYBOARD_BACKLIGHT = 3;
    private static final int MSG_NOTIFY_USER_ACTIVITY = 4;
    private static final int MSG_NOTIFY_USER_INACTIVITY = 5;
    private static final int MSG_INTERACTIVE_STATE_CHANGED = 6;
    private static final int MAX_BRIGHTNESS = 255;
    private static final int DEFAULT_NUM_BRIGHTNESS_CHANGE_STEPS = 10;
    @VisibleForTesting
    static final int MAX_BRIGHTNESS_CHANGE_STEPS = 10;
    private static final long TRANSITION_ANIMATION_DURATION_MILLIS =
            Duration.ofSeconds(1).toMillis();

    private static final String UEVENT_KEYBOARD_BACKLIGHT_TAG = "kbd_backlight";

    @VisibleForTesting
    static final long USER_INACTIVITY_THRESHOLD_MILLIS = Duration.ofSeconds(30).toMillis();

    @VisibleForTesting
    static final int[] DEFAULT_BRIGHTNESS_VALUE_FOR_LEVEL =
            new int[DEFAULT_NUM_BRIGHTNESS_CHANGE_STEPS + 1];

    private final Context mContext;
    private final NativeInputManagerService mNative;
    // The PersistentDataStore should be locked before use.
    @GuardedBy("mDataStore")
    private final PersistentDataStore mDataStore;
    private final Handler mHandler;
    private final AnimatorFactory mAnimatorFactory;
    private final UEventManager mUEventManager;
    // Always access on handler thread or need to lock this for synchronization.
    private final SparseArray<KeyboardBacklightState> mKeyboardBacklights = new SparseArray<>(1);
    // Maintains state if all backlights should be on or turned off
    private boolean mIsBacklightOn = false;
    // Maintains state if currently the device is interactive or not
    private boolean mIsInteractive = true;

    // List of currently registered keyboard backlight listeners
    @GuardedBy("mKeyboardBacklightListenerRecords")
    private final SparseArray<KeyboardBacklightListenerRecord> mKeyboardBacklightListenerRecords =
            new SparseArray<>();

    private final AmbientKeyboardBacklightController mAmbientController;
    @Nullable
    private AmbientKeyboardBacklightController.AmbientKeyboardBacklightListener mAmbientListener;

    private int mAmbientBacklightValue = 0;

    static {
        // Fixed brightness levels to avoid issues when converting back and forth from the
        // device brightness range to [0-255]
        // Levels are: 0, 51, ..., 255
        for (int i = 0; i <= DEFAULT_NUM_BRIGHTNESS_CHANGE_STEPS; i++) {
            DEFAULT_BRIGHTNESS_VALUE_FOR_LEVEL[i] = (int) Math.floor(
                    ((float) i * MAX_BRIGHTNESS) / DEFAULT_NUM_BRIGHTNESS_CHANGE_STEPS);
        }
    }

    KeyboardBacklightController(Context context, NativeInputManagerService nativeService,
            PersistentDataStore dataStore, Looper looper, UEventManager uEventManager) {
        this(context, nativeService, dataStore, looper, ValueAnimator::ofInt, uEventManager);
    }

    @VisibleForTesting
    KeyboardBacklightController(Context context, NativeInputManagerService nativeService,
            PersistentDataStore dataStore, Looper looper, AnimatorFactory animatorFactory,
            UEventManager uEventManager) {
        mContext = context;
        mNative = nativeService;
        mDataStore = dataStore;
        mHandler = new Handler(looper, this::handleMessage);
        mAnimatorFactory = animatorFactory;
        mAmbientController = new AmbientKeyboardBacklightController(context, looper);
        mUEventManager = uEventManager;
    }

    @Override
    public void systemRunning() {
        InputManager inputManager = Objects.requireNonNull(
                mContext.getSystemService(InputManager.class));
        inputManager.registerInputDeviceListener(this, mHandler);

        Message msg = Message.obtain(mHandler, MSG_UPDATE_EXISTING_DEVICES,
                inputManager.getInputDeviceIds());
        mHandler.sendMessage(msg);

        // Observe UEvents for "kbd_backlight" sysfs nodes.
        // We want to observe creation of such LED nodes since they might be created after device
        // FD created and InputDevice creation logic doesn't initialize LED nodes which leads to
        // backlight not working.
        mUEventManager.addListener(new UEventManager.UEventListener() {
            @Override
            public void onUEvent(UEventObserver.UEvent event) {
                onKeyboardBacklightUEvent(event);
            }
        }, UEVENT_KEYBOARD_BACKLIGHT_TAG);

        if (InputFeatureFlagProvider.isAmbientKeyboardBacklightControlEnabled()) {
            // Start ambient backlight controller
            mAmbientController.systemRunning();
        }
    }

    @Override
    public void incrementKeyboardBacklight(int deviceId) {
        Message msg = Message.obtain(mHandler, MSG_INCREMENT_KEYBOARD_BACKLIGHT, deviceId);
        mHandler.sendMessage(msg);
    }

    @Override
    public void decrementKeyboardBacklight(int deviceId) {
        Message msg = Message.obtain(mHandler, MSG_DECREMENT_KEYBOARD_BACKLIGHT, deviceId);
        mHandler.sendMessage(msg);
    }

    @Override
    public void notifyUserActivity() {
        Message msg = Message.obtain(mHandler, MSG_NOTIFY_USER_ACTIVITY);
        mHandler.sendMessage(msg);
    }

    @Override
    public void onInteractiveChanged(boolean isInteractive) {
        Message msg = Message.obtain(mHandler, MSG_INTERACTIVE_STATE_CHANGED, isInteractive);
        mHandler.sendMessage(msg);
    }

    private void updateKeyboardBacklight(int deviceId, Direction direction) {
        InputDevice inputDevice = getInputDevice(deviceId);
        KeyboardBacklightState state = mKeyboardBacklights.get(deviceId);
        if (inputDevice == null || state == null) {
            return;
        }
        // Follow preset levels of brightness defined in BRIGHTNESS_LEVELS
        final int currBrightnessLevel;
        if (state.mUseAmbientController) {
            int index = Arrays.binarySearch(state.mBrightnessValueForLevel, mAmbientBacklightValue);
            // Set current level to the lower bound of the ambient value in the brightness array.
            if (index < 0) {
                int lowerBound = Math.max(0, -(index + 1) - 1);
                currBrightnessLevel =
                        direction == Direction.DIRECTION_UP ? lowerBound : lowerBound + 1;
            } else {
                currBrightnessLevel = index;
            }
        } else {
            currBrightnessLevel = state.mBrightnessLevel;
        }
        final int newBrightnessLevel;
        if (direction == Direction.DIRECTION_UP) {
            newBrightnessLevel = Math.min(currBrightnessLevel + 1,
                    state.getNumBrightnessChangeSteps());
        } else {
            newBrightnessLevel = Math.max(currBrightnessLevel - 1, 0);
        }

        state.setBrightnessLevel(newBrightnessLevel);

        // Might need to stop listening to ALS since user has manually selected backlight
        // level through keyboard up/down button
        updateAmbientLightListener();

        maybeBackupBacklightBrightness(inputDevice, state.mLight,
                state.mBrightnessValueForLevel[newBrightnessLevel]);

        if (DEBUG) {
            Slog.d(TAG,
                    "Changing state from " + state.mBrightnessLevel + " to " + newBrightnessLevel);
        }

        synchronized (mKeyboardBacklightListenerRecords) {
            for (int i = 0; i < mKeyboardBacklightListenerRecords.size(); i++) {
                IKeyboardBacklightState callbackState = new IKeyboardBacklightState();
                callbackState.brightnessLevel = newBrightnessLevel;
                callbackState.maxBrightnessLevel = state.getNumBrightnessChangeSteps();
                mKeyboardBacklightListenerRecords.valueAt(i).notifyKeyboardBacklightChanged(
                        deviceId, callbackState, true);
            }
        }
    }

    private void maybeBackupBacklightBrightness(InputDevice inputDevice, Light keyboardBacklight,
            int brightnessValue) {
        // Don't back up or restore when ALS based keyboard backlight is enabled
        if (InputFeatureFlagProvider.isAmbientKeyboardBacklightControlEnabled()) {
            return;
        }
        synchronized (mDataStore) {
            try {
                mDataStore.setKeyboardBacklightBrightness(inputDevice.getDescriptor(),
                        keyboardBacklight.getId(),
                        brightnessValue);
            } finally {
                mDataStore.saveIfNeeded();
            }
        }
    }

    private void maybeRestoreBacklightBrightness(InputDevice inputDevice, Light keyboardBacklight) {
        // Don't back up or restore when ALS based keyboard backlight is enabled
        if (InputFeatureFlagProvider.isAmbientKeyboardBacklightControlEnabled()) {
            return;
        }
        KeyboardBacklightState state = mKeyboardBacklights.get(inputDevice.getId());
        OptionalInt brightness;
        synchronized (mDataStore) {
            brightness = mDataStore.getKeyboardBacklightBrightness(
                    inputDevice.getDescriptor(), keyboardBacklight.getId());
        }
        if (state != null && brightness.isPresent()) {
            int brightnessValue = Math.max(0, Math.min(MAX_BRIGHTNESS, brightness.getAsInt()));
            int newLevel = Arrays.binarySearch(state.mBrightnessValueForLevel, brightnessValue);
            if (newLevel < 0) {
                newLevel = Math.min(state.getNumBrightnessChangeSteps(), -(newLevel + 1));
            }
            state.setBrightnessLevel(newLevel);
            if (DEBUG) {
                Slog.d(TAG, "Restoring brightness level " + brightness.getAsInt());
            }
        }
    }

    private void handleUserActivity() {
        // Ignore user activity if device is not interactive. When device becomes interactive, we
        // will send another user activity to turn backlight on.
        if (!mIsInteractive) {
            return;
        }
        mIsBacklightOn = true;
        for (int i = 0; i < mKeyboardBacklights.size(); i++) {
            KeyboardBacklightState state = mKeyboardBacklights.valueAt(i);
            state.onBacklightStateChanged();
        }
        mHandler.removeMessages(MSG_NOTIFY_USER_INACTIVITY);
        mHandler.sendEmptyMessageAtTime(MSG_NOTIFY_USER_INACTIVITY,
                SystemClock.uptimeMillis() + USER_INACTIVITY_THRESHOLD_MILLIS);
    }

    private void handleUserInactivity() {
        mIsBacklightOn = false;
        for (int i = 0; i < mKeyboardBacklights.size(); i++) {
            KeyboardBacklightState state = mKeyboardBacklights.valueAt(i);
            state.onBacklightStateChanged();
        }
    }

    @VisibleForTesting
    public void handleInteractiveStateChange(boolean isInteractive) {
        // Interactive state changes should force the keyboard to turn on/off irrespective of
        // whether time out occurred or not.
        mIsInteractive = isInteractive;
        if (isInteractive) {
            handleUserActivity();
        } else {
            handleUserInactivity();
        }
        updateAmbientLightListener();
    }

    @VisibleForTesting
    public void handleAmbientLightValueChanged(int brightnessValue) {
        mAmbientBacklightValue = brightnessValue;
        for (int i = 0; i < mKeyboardBacklights.size(); i++) {
            KeyboardBacklightState state = mKeyboardBacklights.valueAt(i);
            state.onAmbientBacklightValueChanged();
        }
    }

    private boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_UPDATE_EXISTING_DEVICES:
                for (int deviceId : (int[]) msg.obj) {
                    onInputDeviceAdded(deviceId);
                }
                return true;
            case MSG_INCREMENT_KEYBOARD_BACKLIGHT:
                updateKeyboardBacklight((int) msg.obj, Direction.DIRECTION_UP);
                return true;
            case MSG_DECREMENT_KEYBOARD_BACKLIGHT:
                updateKeyboardBacklight((int) msg.obj, Direction.DIRECTION_DOWN);
                return true;
            case MSG_NOTIFY_USER_ACTIVITY:
                handleUserActivity();
                return true;
            case MSG_NOTIFY_USER_INACTIVITY:
                handleUserInactivity();
                return true;
            case MSG_INTERACTIVE_STATE_CHANGED:
                handleInteractiveStateChange((boolean) msg.obj);
                return true;
        }
        return false;
    }

    @VisibleForTesting
    @Override
    public void onInputDeviceAdded(int deviceId) {
        onInputDeviceChanged(deviceId);
        updateAmbientLightListener();
    }

    @VisibleForTesting
    @Override
    public void onInputDeviceRemoved(int deviceId) {
        mKeyboardBacklights.remove(deviceId);
        updateAmbientLightListener();
    }

    @VisibleForTesting
    @Override
    public void onInputDeviceChanged(int deviceId) {
        InputDevice inputDevice = getInputDevice(deviceId);
        if (inputDevice == null) {
            return;
        }
        final Light keyboardBacklight = getKeyboardBacklight(inputDevice);
        if (keyboardBacklight == null) {
            mKeyboardBacklights.remove(deviceId);
            return;
        }
        KeyboardBacklightState state = mKeyboardBacklights.get(deviceId);
        if (state != null && state.mLight.getId() == keyboardBacklight.getId()) {
            return;
        }
        // The keyboard backlight was added or changed.
        mKeyboardBacklights.put(deviceId, new KeyboardBacklightState(deviceId, keyboardBacklight));
        maybeRestoreBacklightBrightness(inputDevice, keyboardBacklight);
    }

    private InputDevice getInputDevice(int deviceId) {
        InputManager inputManager = mContext.getSystemService(InputManager.class);
        return inputManager != null ? inputManager.getInputDevice(deviceId) : null;
    }

    private Light getKeyboardBacklight(InputDevice inputDevice) {
        // Assuming each keyboard can have only single Light node for Keyboard backlight control
        // for simplicity.
        for (Light light : inputDevice.getLightsManager().getLights()) {
            if (light.getType() == Light.LIGHT_TYPE_KEYBOARD_BACKLIGHT
                    && light.hasBrightnessControl()) {
                return light;
            }
        }
        return null;
    }

    /** Register the keyboard backlight listener for a process. */
    @BinderThread
    @Override
    public void registerKeyboardBacklightListener(IKeyboardBacklightListener listener,
            int pid) {
        synchronized (mKeyboardBacklightListenerRecords) {
            if (mKeyboardBacklightListenerRecords.get(pid) != null) {
                throw new IllegalStateException("The calling process has already registered "
                        + "a KeyboardBacklightListener.");
            }
            KeyboardBacklightListenerRecord record = new KeyboardBacklightListenerRecord(pid,
                    listener);
            try {
                listener.asBinder().linkToDeath(record, 0);
            } catch (RemoteException ex) {
                throw new RuntimeException(ex);
            }
            mKeyboardBacklightListenerRecords.put(pid, record);
        }
    }

    /** Unregister the keyboard backlight listener for a process. */
    @BinderThread
    @Override
    public void unregisterKeyboardBacklightListener(IKeyboardBacklightListener listener,
            int pid) {
        synchronized (mKeyboardBacklightListenerRecords) {
            KeyboardBacklightListenerRecord record = mKeyboardBacklightListenerRecords.get(pid);
            if (record == null) {
                throw new IllegalStateException("The calling process has no registered "
                        + "KeyboardBacklightListener.");
            }
            if (record.mListener.asBinder() != listener.asBinder()) {
                throw new IllegalStateException("The calling process has a different registered "
                        + "KeyboardBacklightListener.");
            }
            record.mListener.asBinder().unlinkToDeath(record, 0);
            mKeyboardBacklightListenerRecords.remove(pid);
        }
    }

    private void onKeyboardBacklightListenerDied(int pid) {
        synchronized (mKeyboardBacklightListenerRecords) {
            mKeyboardBacklightListenerRecords.remove(pid);
        }
    }

    @VisibleForTesting
    public void onKeyboardBacklightUEvent(UEventObserver.UEvent event) {
        if ("ADD".equalsIgnoreCase(event.get("ACTION")) && "LEDS".equalsIgnoreCase(
                event.get("SUBSYSTEM"))) {
            final String devPath = event.get("DEVPATH");
            if (isValidBacklightNodePath(devPath)) {
                mNative.sysfsNodeChanged("/sys" + devPath);
            }
        }
    }

    private void updateAmbientLightListener() {
        if (!InputFeatureFlagProvider.isAmbientKeyboardBacklightControlEnabled()) {
            return;
        }
        boolean needToListenAmbientLightSensor = false;
        for (int i = 0; i < mKeyboardBacklights.size(); i++) {
            needToListenAmbientLightSensor |= mKeyboardBacklights.valueAt(i).mUseAmbientController;
        }
        needToListenAmbientLightSensor &= mIsInteractive;
        if (needToListenAmbientLightSensor && mAmbientListener == null) {
            mAmbientListener = this::handleAmbientLightValueChanged;
            mAmbientController.registerAmbientBacklightListener(mAmbientListener);
        }
        if (!needToListenAmbientLightSensor && mAmbientListener != null) {
            mAmbientController.unregisterAmbientBacklightListener(mAmbientListener);
            mAmbientListener = null;
        }
    }

    private static boolean isValidBacklightNodePath(String devPath) {
        if (TextUtils.isEmpty(devPath)) {
            return false;
        }
        int index = devPath.lastIndexOf('/');
        if (index < 0) {
            return false;
        }
        String backlightNode = devPath.substring(index + 1);
        devPath = devPath.substring(0, index);
        if (!devPath.endsWith("leds") || !backlightNode.contains("kbd_backlight")) {
            return false;
        }
        index = devPath.lastIndexOf('/');
        return index >= 0;
    }

    @Override
    public void dump(PrintWriter pw) {
        IndentingPrintWriter ipw = new IndentingPrintWriter(pw);
        ipw.println(TAG + ": " + mKeyboardBacklights.size() + " keyboard backlights");
        ipw.increaseIndent();
        for (int i = 0; i < mKeyboardBacklights.size(); i++) {
            KeyboardBacklightState state = mKeyboardBacklights.valueAt(i);
            ipw.println(i + ": " + state.toString());
        }
        ipw.decreaseIndent();
    }

    // A record of a registered Keyboard backlight listener from one process.
    private class KeyboardBacklightListenerRecord implements IBinder.DeathRecipient {
        public final int mPid;
        public final IKeyboardBacklightListener mListener;

        KeyboardBacklightListenerRecord(int pid, IKeyboardBacklightListener listener) {
            mPid = pid;
            mListener = listener;
        }

        @Override
        public void binderDied() {
            if (DEBUG) {
                Slog.d(TAG, "Keyboard backlight listener for pid " + mPid + " died.");
            }
            onKeyboardBacklightListenerDied(mPid);
        }

        public void notifyKeyboardBacklightChanged(int deviceId, IKeyboardBacklightState state,
                boolean isTriggeredByKeyPress) {
            try {
                mListener.onBrightnessChanged(deviceId, state, isTriggeredByKeyPress);
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify process " + mPid
                        + " that keyboard backlight changed, assuming it died.", ex);
                binderDied();
            }
        }
    }

    private class KeyboardBacklightState {
        private final int mDeviceId;
        private final Light mLight;
        private int mBrightnessLevel;
        private ValueAnimator mAnimator;
        private final int[] mBrightnessValueForLevel;
        private boolean mUseAmbientController =
                InputFeatureFlagProvider.isAmbientKeyboardBacklightControlEnabled();

        KeyboardBacklightState(int deviceId, Light light) {
            mDeviceId = deviceId;
            mLight = light;
            mBrightnessValueForLevel = setupBrightnessLevels();
        }

        private int[] setupBrightnessLevels() {
            if (!InputFeatureFlagProvider.isKeyboardBacklightCustomLevelsEnabled()) {
                return DEFAULT_BRIGHTNESS_VALUE_FOR_LEVEL;
            }
            int[] customLevels = mLight.getPreferredBrightnessLevels();
            if (customLevels == null || customLevels.length == 0) {
                return DEFAULT_BRIGHTNESS_VALUE_FOR_LEVEL;
            }
            TreeSet<Integer> brightnessLevels = new TreeSet<>();
            brightnessLevels.add(0);
            for (int level : customLevels) {
                if (level > 0 && level < MAX_BRIGHTNESS) {
                    brightnessLevels.add(level);
                }
            }
            brightnessLevels.add(MAX_BRIGHTNESS);
            int brightnessChangeSteps = brightnessLevels.size() - 1;
            if (brightnessChangeSteps > MAX_BRIGHTNESS_CHANGE_STEPS) {
                return DEFAULT_BRIGHTNESS_VALUE_FOR_LEVEL;
            }
            int[] result = new int[brightnessLevels.size()];
            int index = 0;
            for (int val : brightnessLevels) {
                result[index++] = val;
            }
            return result;
        }

        private int getNumBrightnessChangeSteps() {
            return mBrightnessValueForLevel.length - 1;
        }

        private void onBacklightStateChanged() {
            int toValue = mUseAmbientController ? mAmbientBacklightValue
                    : mBrightnessValueForLevel[mBrightnessLevel];
            setBacklightValue(mIsBacklightOn ? toValue : 0);
        }
        private void setBrightnessLevel(int brightnessLevel) {
            // Once we manually set level, disregard ambient light controller
            mUseAmbientController = false;
            if (mIsBacklightOn) {
                setBacklightValue(mBrightnessValueForLevel[brightnessLevel]);
            }
            mBrightnessLevel = brightnessLevel;
        }

        private void onAmbientBacklightValueChanged() {
            if (mIsBacklightOn && mUseAmbientController) {
                setBacklightValue(mAmbientBacklightValue);
            }
        }

        private void cancelAnimation() {
            if (mAnimator != null && mAnimator.isRunning()) {
                mAnimator.cancel();
            }
        }

        private void setBacklightValue(int toValue) {
            int fromValue = Color.alpha(mNative.getLightColor(mDeviceId, mLight.getId()));
            if (fromValue == toValue) {
                return;
            }
            if (InputFeatureFlagProvider.isKeyboardBacklightAnimationEnabled()) {
                startAnimation(fromValue, toValue);
            } else {
                mNative.setLightColor(mDeviceId, mLight.getId(), Color.argb(toValue, 0, 0, 0));
            }
        }

        private void startAnimation(int fromValue, int toValue) {
            // Cancel any ongoing animation before starting a new one
            cancelAnimation();
            mAnimator = mAnimatorFactory.makeIntAnimator(fromValue, toValue);
            mAnimator.addUpdateListener(
                    (animation) -> mNative.setLightColor(mDeviceId, mLight.getId(),
                            Color.argb((int) animation.getAnimatedValue(), 0, 0, 0)));
            mAnimator.setDuration(TRANSITION_ANIMATION_DURATION_MILLIS).start();
        }

        @Override
        public String toString() {
            return "KeyboardBacklightState{Light=" + mLight.getId()
                    + ", BrightnessLevel=" + mBrightnessLevel
                    + "}";
        }
    }

    @VisibleForTesting
    interface AnimatorFactory {
        ValueAnimator makeIntAnimator(int from, int to);
    }
}
