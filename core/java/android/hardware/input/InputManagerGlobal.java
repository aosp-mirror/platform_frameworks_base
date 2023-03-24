/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.hardware.input;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.content.Context;
import android.hardware.BatteryState;
import android.hardware.SensorManager;
import android.hardware.input.InputManager.InputDeviceBatteryListener;
import android.hardware.input.InputManager.InputDeviceListener;
import android.hardware.input.InputManager.KeyboardBacklightListener;
import android.hardware.input.InputManager.OnTabletModeChangedListener;
import android.hardware.lights.Light;
import android.hardware.lights.LightState;
import android.hardware.lights.LightsRequest;
import android.os.Binder;
import android.os.CombinedVibration;
import android.os.Handler;
import android.os.IBinder;
import android.os.IVibratorStateListener;
import android.os.InputEventInjectionSync;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputMonitor;
import android.view.PointerIcon;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.SomeArgs;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Manages communication with the input manager service on behalf of
 * an application process. You're probably looking for {@link InputManager}.
 *
 * @hide
 */
public final class InputManagerGlobal {
    private static final String TAG = "InputManagerGlobal";
    // To enable these logs, run: 'adb shell setprop log.tag.InputManagerGlobal DEBUG'
    // (requires restart)
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    @GuardedBy("mInputDeviceListeners")
    @Nullable private SparseArray<InputDevice> mInputDevices;
    @GuardedBy("mInputDeviceListeners")
    @Nullable private InputDevicesChangedListener mInputDevicesChangedListener;
    @GuardedBy("mInputDeviceListeners")
    private final ArrayList<InputDeviceListenerDelegate> mInputDeviceListeners = new ArrayList<>();

    @GuardedBy("mOnTabletModeChangedListeners")
    private final ArrayList<OnTabletModeChangedListenerDelegate> mOnTabletModeChangedListeners =
            new ArrayList<>();

    private final Object mBatteryListenersLock = new Object();
    // Maps a deviceId whose battery is currently being monitored to an entry containing the
    // registered listeners for that device.
    @GuardedBy("mBatteryListenersLock")
    @Nullable private SparseArray<RegisteredBatteryListeners> mBatteryListeners;
    @GuardedBy("mBatteryListenersLock")
    @Nullable private IInputDeviceBatteryListener mInputDeviceBatteryListener;

    private final Object mKeyboardBacklightListenerLock = new Object();
    @GuardedBy("mKeyboardBacklightListenerLock")
    @Nullable private ArrayList<KeyboardBacklightListenerDelegate> mKeyboardBacklightListeners;
    @GuardedBy("mKeyboardBacklightListenerLock")
    @Nullable private IKeyboardBacklightListener mKeyboardBacklightListener;

    @Nullable private InputDeviceSensorManager mInputDeviceSensorManager;

    private static InputManagerGlobal sInstance;

    private final IInputManager mIm;

    public InputManagerGlobal(IInputManager im) {
        mIm = im;
    }

    /**
     * Gets an instance of the input manager global singleton.
     *
     * @return The display manager instance, may be null early in system startup
     * before the display manager has been fully initialized.
     */
    public static InputManagerGlobal getInstance() {
        synchronized (InputManagerGlobal.class) {
            if (sInstance == null) {
                IBinder b = ServiceManager.getService(Context.INPUT_SERVICE);
                if (b != null) {
                    sInstance = new InputManagerGlobal(IInputManager.Stub.asInterface(b));
                }
            }
            return sInstance;
        }
    }

    public IInputManager getInputManagerService() {
        return mIm;
    }

    /**
     * Gets an instance of the input manager.
     *
     * @return The input manager instance.
     */
    public static InputManagerGlobal resetInstance(IInputManager inputManagerService) {
        synchronized (InputManager.class) {
            sInstance = new InputManagerGlobal(inputManagerService);
            return sInstance;
        }
    }

    /**
     * Clear the instance of the input manager.
     */
    public static void clearInstance() {
        synchronized (InputManagerGlobal.class) {
            sInstance = null;
        }
    }

    /**
     * @see InputManager#getInputDevice(int)
     */
    @Nullable
    public InputDevice getInputDevice(int id) {
        synchronized (mInputDeviceListeners) {
            populateInputDevicesLocked();

            int index = mInputDevices.indexOfKey(id);
            if (index < 0) {
                return null;
            }

            InputDevice inputDevice = mInputDevices.valueAt(index);
            if (inputDevice == null) {
                try {
                    inputDevice = mIm.getInputDevice(id);
                } catch (RemoteException ex) {
                    throw ex.rethrowFromSystemServer();
                }
                if (inputDevice != null) {
                    mInputDevices.setValueAt(index, inputDevice);
                }
            }
            return inputDevice;
        }
    }

    @GuardedBy("mInputDeviceListeners")
    private void populateInputDevicesLocked() {
        if (mInputDevicesChangedListener == null) {
            final InputDevicesChangedListener
                    listener = new InputDevicesChangedListener();
            try {
                mIm.registerInputDevicesChangedListener(listener);
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
            mInputDevicesChangedListener = listener;
        }

        if (mInputDevices == null) {
            final int[] ids;
            try {
                ids = mIm.getInputDeviceIds();
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }

            mInputDevices = new SparseArray<>();
            for (int id : ids) {
                mInputDevices.put(id, null);
            }
        }
    }

    private final class InputDevicesChangedListener extends IInputDevicesChangedListener.Stub {
        @Override
        public void onInputDevicesChanged(int[] deviceIdAndGeneration) throws RemoteException {
            InputManagerGlobal.this.onInputDevicesChanged(deviceIdAndGeneration);
        }
    }

    private void onInputDevicesChanged(int[] deviceIdAndGeneration) {
        if (DEBUG) {
            Log.d(TAG, "Received input devices changed.");
        }

        synchronized (mInputDeviceListeners) {
            for (int i = mInputDevices.size(); --i > 0; ) {
                final int deviceId = mInputDevices.keyAt(i);
                if (!containsDeviceId(deviceIdAndGeneration, deviceId)) {
                    if (DEBUG) {
                        Log.d(TAG, "Device removed: " + deviceId);
                    }
                    mInputDevices.removeAt(i);
                    sendMessageToInputDeviceListenersLocked(
                            InputDeviceListenerDelegate.MSG_DEVICE_REMOVED, deviceId);
                }
            }

            for (int i = 0; i < deviceIdAndGeneration.length; i += 2) {
                final int deviceId = deviceIdAndGeneration[i];
                int index = mInputDevices.indexOfKey(deviceId);
                if (index >= 0) {
                    final InputDevice device = mInputDevices.valueAt(index);
                    if (device != null) {
                        final int generation = deviceIdAndGeneration[i + 1];
                        if (device.getGeneration() != generation) {
                            if (DEBUG) {
                                Log.d(TAG, "Device changed: " + deviceId);
                            }
                            mInputDevices.setValueAt(index, null);
                            sendMessageToInputDeviceListenersLocked(
                                    InputDeviceListenerDelegate.MSG_DEVICE_CHANGED, deviceId);
                        }
                    }
                } else {
                    if (DEBUG) {
                        Log.d(TAG, "Device added: " + deviceId);
                    }
                    mInputDevices.put(deviceId, null);
                    sendMessageToInputDeviceListenersLocked(
                            InputDeviceListenerDelegate.MSG_DEVICE_ADDED, deviceId);
                }
            }
        }
    }

    private static final class InputDeviceListenerDelegate extends Handler {
        public final InputDeviceListener mListener;
        static final int MSG_DEVICE_ADDED = 1;
        static final int MSG_DEVICE_REMOVED = 2;
        static final int MSG_DEVICE_CHANGED = 3;

        InputDeviceListenerDelegate(InputDeviceListener listener, Handler handler) {
            super(handler != null ? handler.getLooper() : Looper.myLooper());
            mListener = listener;
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_DEVICE_ADDED:
                    mListener.onInputDeviceAdded(msg.arg1);
                    break;
                case MSG_DEVICE_REMOVED:
                    mListener.onInputDeviceRemoved(msg.arg1);
                    break;
                case MSG_DEVICE_CHANGED:
                    mListener.onInputDeviceChanged(msg.arg1);
                    break;
            }
        }
    }

    private static boolean containsDeviceId(int[] deviceIdAndGeneration, int deviceId) {
        for (int i = 0; i < deviceIdAndGeneration.length; i += 2) {
            if (deviceIdAndGeneration[i] == deviceId) {
                return true;
            }
        }
        return false;
    }

    @GuardedBy("mInputDeviceListeners")
    private void sendMessageToInputDeviceListenersLocked(int what, int deviceId) {
        final int numListeners = mInputDeviceListeners.size();
        for (int i = 0; i < numListeners; i++) {
            InputDeviceListenerDelegate listener = mInputDeviceListeners.get(i);
            listener.sendMessage(listener.obtainMessage(what, deviceId, 0));
        }
    }

    /**
     * @see InputManager#registerInputDeviceListener
     */
    void registerInputDeviceListener(InputDeviceListener listener, Handler handler) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }

        synchronized (mInputDeviceListeners) {
            populateInputDevicesLocked();
            int index = findInputDeviceListenerLocked(listener);
            if (index < 0) {
                mInputDeviceListeners.add(new InputDeviceListenerDelegate(listener, handler));
            }
        }
    }

    /**
     * @see InputManager#unregisterInputDeviceListener
     */
    void unregisterInputDeviceListener(InputDeviceListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }

        synchronized (mInputDeviceListeners) {
            int index = findInputDeviceListenerLocked(listener);
            if (index >= 0) {
                InputDeviceListenerDelegate d = mInputDeviceListeners.get(index);
                d.removeCallbacksAndMessages(null);
                mInputDeviceListeners.remove(index);
            }
        }
    }

    @GuardedBy("mInputDeviceListeners")
    private int findInputDeviceListenerLocked(InputDeviceListener listener) {
        final int numListeners = mInputDeviceListeners.size();
        for (int i = 0; i < numListeners; i++) {
            if (mInputDeviceListeners.get(i).mListener == listener) {
                return i;
            }
        }
        return -1;
    }

    /**
     * @see InputManager#getInputDeviceIds
     */
    public int[] getInputDeviceIds() {
        synchronized (mInputDeviceListeners) {
            populateInputDevicesLocked();

            final int count = mInputDevices.size();
            final int[] ids = new int[count];
            for (int i = 0; i < count; i++) {
                ids[i] = mInputDevices.keyAt(i);
            }
            return ids;
        }
    }

    /**
     * @see InputManager#isInputDeviceEnabled(int)
     */
    public boolean isInputDeviceEnabled(int id) {
        try {
            return mIm.isInputDeviceEnabled(id);
        } catch (RemoteException ex) {
            Log.w(TAG, "Could not check enabled status of input device with id = " + id);
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * @see InputManager#enableInputDevice(int)
     */
    public void enableInputDevice(int id) {
        try {
            mIm.enableInputDevice(id);
        } catch (RemoteException ex) {
            Log.w(TAG, "Could not enable input device with id = " + id);
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * @see InputManager#disableInputDevice(int)
     */
    public void disableInputDevice(int id) {
        try {
            mIm.disableInputDevice(id);
        } catch (RemoteException ex) {
            Log.w(TAG, "Could not disable input device with id = " + id);
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * @see InputManager#getInputDeviceByDescriptor
     */
    InputDevice getInputDeviceByDescriptor(String descriptor) {
        if (descriptor == null) {
            throw new IllegalArgumentException("descriptor must not be null.");
        }

        synchronized (mInputDeviceListeners) {
            populateInputDevicesLocked();

            int numDevices = mInputDevices.size();
            for (int i = 0; i < numDevices; i++) {
                InputDevice inputDevice = mInputDevices.valueAt(i);
                if (inputDevice == null) {
                    int id = mInputDevices.keyAt(i);
                    try {
                        inputDevice = mIm.getInputDevice(id);
                    } catch (RemoteException ex) {
                        throw ex.rethrowFromSystemServer();
                    }
                    if (inputDevice == null) {
                        continue;
                    }
                    mInputDevices.setValueAt(i, inputDevice);
                }
                if (descriptor.equals(inputDevice.getDescriptor())) {
                    return inputDevice;
                }
            }
            return null;
        }
    }

    /**
     * @see InputManager#getHostUsiVersion
     */
    @Nullable
    HostUsiVersion getHostUsiVersion(@NonNull Display display) {
        Objects.requireNonNull(display, "display should not be null");

        // Return the first valid USI version reported by any input device associated with
        // the display.
        synchronized (mInputDeviceListeners) {
            populateInputDevicesLocked();

            for (int i = 0; i < mInputDevices.size(); i++) {
                final InputDevice device = getInputDevice(mInputDevices.keyAt(i));
                if (device != null && device.getAssociatedDisplayId() == display.getDisplayId()) {
                    if (device.getHostUsiVersion() != null) {
                        return device.getHostUsiVersion();
                    }
                }
            }
        }

        // If there are no input devices that report a valid USI version, see if there is a config
        // that specifies the USI version for the display. This is to handle cases where the USI
        // input device is not registered by the kernel/driver all the time.
        try {
            return mIm.getHostUsiVersionFromDisplayConfig(display.getDisplayId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private void onTabletModeChanged(long whenNanos, boolean inTabletMode) {
        if (DEBUG) {
            Log.d(TAG, "Received tablet mode changed: "
                    + "whenNanos=" + whenNanos + ", inTabletMode=" + inTabletMode);
        }
        synchronized (mOnTabletModeChangedListeners) {
            final int numListeners = mOnTabletModeChangedListeners.size();
            for (int i = 0; i < numListeners; i++) {
                OnTabletModeChangedListenerDelegate listener =
                        mOnTabletModeChangedListeners.get(i);
                listener.sendTabletModeChanged(whenNanos, inTabletMode);
            }
        }
    }

    private final class TabletModeChangedListener extends ITabletModeChangedListener.Stub {
        @Override
        public void onTabletModeChanged(long whenNanos, boolean inTabletMode) {
            InputManagerGlobal.this.onTabletModeChanged(whenNanos, inTabletMode);
        }
    }

    private static final class OnTabletModeChangedListenerDelegate extends Handler {
        private static final int MSG_TABLET_MODE_CHANGED = 0;

        public final OnTabletModeChangedListener mListener;

        OnTabletModeChangedListenerDelegate(
                OnTabletModeChangedListener listener, Handler handler) {
            super(handler != null ? handler.getLooper() : Looper.myLooper());
            mListener = listener;
        }

        public void sendTabletModeChanged(long whenNanos, boolean inTabletMode) {
            SomeArgs args = SomeArgs.obtain();
            args.argi1 = (int) whenNanos;
            args.argi2 = (int) (whenNanos >> 32);
            args.arg1 = inTabletMode;
            obtainMessage(MSG_TABLET_MODE_CHANGED, args).sendToTarget();
        }

        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MSG_TABLET_MODE_CHANGED) {
                SomeArgs args = (SomeArgs) msg.obj;
                long whenNanos = (args.argi1 & 0xFFFFFFFFL) | ((long) args.argi2 << 32);
                boolean inTabletMode = (boolean) args.arg1;
                mListener.onTabletModeChanged(whenNanos, inTabletMode);
            }
        }
    }

    /**
     * @see InputManager#registerInputDeviceListener(InputDeviceListener, Handler)
     */
    void registerOnTabletModeChangedListener(
            OnTabletModeChangedListener listener, Handler handler) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        synchronized (mOnTabletModeChangedListeners) {
            if (mOnTabletModeChangedListeners == null) {
                initializeTabletModeListenerLocked();
            }
            int idx = findOnTabletModeChangedListenerLocked(listener);
            if (idx < 0) {
                OnTabletModeChangedListenerDelegate d =
                        new OnTabletModeChangedListenerDelegate(listener, handler);
                mOnTabletModeChangedListeners.add(d);
            }
        }
    }

    /**
     * @see InputManager#unregisterOnTabletModeChangedListener(OnTabletModeChangedListener)
     */
    void unregisterOnTabletModeChangedListener(OnTabletModeChangedListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        synchronized (mOnTabletModeChangedListeners) {
            int idx = findOnTabletModeChangedListenerLocked(listener);
            if (idx >= 0) {
                OnTabletModeChangedListenerDelegate d = mOnTabletModeChangedListeners.remove(idx);
                d.removeCallbacksAndMessages(null);
            }
        }
    }

    @GuardedBy("mOnTabletModeChangedListeners")
    private void initializeTabletModeListenerLocked() {
        final TabletModeChangedListener listener = new TabletModeChangedListener();
        try {
            mIm.registerTabletModeChangedListener(listener);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    @GuardedBy("mOnTabletModeChangedListeners")
    private int findOnTabletModeChangedListenerLocked(OnTabletModeChangedListener listener) {
        final int n = mOnTabletModeChangedListeners.size();
        for (int i = 0; i < n; i++) {
            if (mOnTabletModeChangedListeners.get(i).mListener == listener) {
                return i;
            }
        }
        return -1;
    }

    private static final class RegisteredBatteryListeners {
        final List<InputDeviceBatteryListenerDelegate> mDelegates = new ArrayList<>();
        IInputDeviceBatteryState mInputDeviceBatteryState;
    }

    private static final class InputDeviceBatteryListenerDelegate {
        final InputDeviceBatteryListener mListener;
        final Executor mExecutor;

        InputDeviceBatteryListenerDelegate(InputDeviceBatteryListener listener, Executor executor) {
            mListener = listener;
            mExecutor = executor;
        }

        void notifyBatteryStateChanged(IInputDeviceBatteryState state) {
            mExecutor.execute(() ->
                    mListener.onBatteryStateChanged(state.deviceId, state.updateTime,
                            new LocalBatteryState(state.isPresent, state.status, state.capacity)));
        }
    }

    /**
     * @see InputManager#addInputDeviceBatteryListener(int, Executor, InputDeviceBatteryListener)
     */
    void addInputDeviceBatteryListener(int deviceId, @NonNull Executor executor,
            @NonNull InputDeviceBatteryListener listener) {
        Objects.requireNonNull(executor, "executor should not be null");
        Objects.requireNonNull(listener, "listener should not be null");

        synchronized (mBatteryListenersLock) {
            if (mBatteryListeners == null) {
                mBatteryListeners = new SparseArray<>();
                mInputDeviceBatteryListener = new LocalInputDeviceBatteryListener();
            }
            RegisteredBatteryListeners listenersForDevice = mBatteryListeners.get(deviceId);
            if (listenersForDevice == null) {
                // The deviceId is currently not being monitored for battery changes.
                // Start monitoring the device.
                listenersForDevice = new RegisteredBatteryListeners();
                mBatteryListeners.put(deviceId, listenersForDevice);
                try {
                    mIm.registerBatteryListener(deviceId, mInputDeviceBatteryListener);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            } else {
                // The deviceId is already being monitored for battery changes.
                // Ensure that the listener is not already registered.
                final int numDelegates = listenersForDevice.mDelegates.size();
                for (int i = 0; i < numDelegates; i++) {
                    InputDeviceBatteryListener registeredListener =
                            listenersForDevice.mDelegates.get(i).mListener;
                    if (Objects.equals(listener, registeredListener)) {
                        throw new IllegalArgumentException(
                                "Attempting to register an InputDeviceBatteryListener that has "
                                        + "already been registered for deviceId: "
                                        + deviceId);
                    }
                }
            }
            final InputDeviceBatteryListenerDelegate delegate =
                    new InputDeviceBatteryListenerDelegate(listener, executor);
            listenersForDevice.mDelegates.add(delegate);

            // Notify the listener immediately if we already have the latest battery state.
            if (listenersForDevice.mInputDeviceBatteryState != null) {
                delegate.notifyBatteryStateChanged(listenersForDevice.mInputDeviceBatteryState);
            }
        }
    }

    /**
     * @see InputManager#removeInputDeviceBatteryListener(int, InputDeviceBatteryListener)
     */
    void removeInputDeviceBatteryListener(int deviceId,
            @NonNull InputDeviceBatteryListener listener) {
        Objects.requireNonNull(listener, "listener should not be null");

        synchronized (mBatteryListenersLock) {
            if (mBatteryListeners == null) {
                return;
            }
            RegisteredBatteryListeners listenersForDevice = mBatteryListeners.get(deviceId);
            if (listenersForDevice == null) {
                // The deviceId is not currently being monitored.
                return;
            }
            final List<InputDeviceBatteryListenerDelegate> delegates =
                    listenersForDevice.mDelegates;
            for (int i = 0; i < delegates.size();) {
                if (Objects.equals(listener, delegates.get(i).mListener)) {
                    delegates.remove(i);
                    continue;
                }
                i++;
            }
            if (!delegates.isEmpty()) {
                return;
            }

            // There are no more battery listeners for this deviceId. Stop monitoring this device.
            mBatteryListeners.remove(deviceId);
            try {
                mIm.unregisterBatteryListener(deviceId, mInputDeviceBatteryListener);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            if (mBatteryListeners.size() == 0) {
                // There are no more devices being monitored, so the registered
                // IInputDeviceBatteryListener will be automatically dropped by the server.
                mBatteryListeners = null;
                mInputDeviceBatteryListener = null;
            }
        }
    }

    private class LocalInputDeviceBatteryListener extends IInputDeviceBatteryListener.Stub {
        @Override
        public void onBatteryStateChanged(IInputDeviceBatteryState state) {
            synchronized (mBatteryListenersLock) {
                if (mBatteryListeners == null) return;
                final RegisteredBatteryListeners entry = mBatteryListeners.get(state.deviceId);
                if (entry == null) return;

                entry.mInputDeviceBatteryState = state;
                final int numDelegates = entry.mDelegates.size();
                for (int i = 0; i < numDelegates; i++) {
                    entry.mDelegates.get(i)
                            .notifyBatteryStateChanged(entry.mInputDeviceBatteryState);
                }
            }
        }
    }

    /**
     * @see InputManager#getInputDeviceBatteryState(int, boolean)
     */
    @NonNull
    public BatteryState getInputDeviceBatteryState(int deviceId, boolean hasBattery) {
        if (!hasBattery) {
            return new LocalBatteryState();
        }
        try {
            final IInputDeviceBatteryState state = mIm.getBatteryState(deviceId);
            return new LocalBatteryState(state.isPresent, state.status, state.capacity);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    // Implementation of the android.hardware.BatteryState interface used to report the battery
    // state via the InputDevice#getBatteryState() and InputDeviceBatteryListener interfaces.
    private static final class LocalBatteryState extends BatteryState {
        private final boolean mIsPresent;
        private final int mStatus;
        private final float mCapacity;

        LocalBatteryState() {
            this(false /*isPresent*/, BatteryState.STATUS_UNKNOWN, Float.NaN /*capacity*/);
        }

        LocalBatteryState(boolean isPresent, int status, float capacity) {
            mIsPresent = isPresent;
            mStatus = status;
            mCapacity = capacity;
        }

        @Override
        public boolean isPresent() {
            return mIsPresent;
        }

        @Override
        public int getStatus() {
            return mStatus;
        }

        @Override
        public float getCapacity() {
            return mCapacity;
        }
    }

    private static final class KeyboardBacklightListenerDelegate {
        final InputManager.KeyboardBacklightListener mListener;
        final Executor mExecutor;

        KeyboardBacklightListenerDelegate(KeyboardBacklightListener listener, Executor executor) {
            mListener = listener;
            mExecutor = executor;
        }

        void notifyKeyboardBacklightChange(int deviceId, IKeyboardBacklightState state,
                boolean isTriggeredByKeyPress) {
            mExecutor.execute(() ->
                    mListener.onKeyboardBacklightChanged(deviceId,
                            new LocalKeyboardBacklightState(state.brightnessLevel,
                                    state.maxBrightnessLevel), isTriggeredByKeyPress));
        }
    }

    private class LocalKeyboardBacklightListener extends IKeyboardBacklightListener.Stub {

        @Override
        public void onBrightnessChanged(int deviceId, IKeyboardBacklightState state,
                boolean isTriggeredByKeyPress) {
            synchronized (mKeyboardBacklightListenerLock) {
                if (mKeyboardBacklightListeners == null) return;
                final int numListeners = mKeyboardBacklightListeners.size();
                for (int i = 0; i < numListeners; i++) {
                    mKeyboardBacklightListeners.get(i)
                            .notifyKeyboardBacklightChange(deviceId, state, isTriggeredByKeyPress);
                }
            }
        }
    }

    // Implementation of the android.hardware.input.KeyboardBacklightState interface used to report
    // the keyboard backlight state via the KeyboardBacklightListener interfaces.
    private static final class LocalKeyboardBacklightState extends KeyboardBacklightState {

        private final int mBrightnessLevel;
        private final int mMaxBrightnessLevel;

        LocalKeyboardBacklightState(int brightnessLevel, int maxBrightnessLevel) {
            mBrightnessLevel = brightnessLevel;
            mMaxBrightnessLevel = maxBrightnessLevel;
        }

        @Override
        public int getBrightnessLevel() {
            return mBrightnessLevel;
        }

        @Override
        public int getMaxBrightnessLevel() {
            return mMaxBrightnessLevel;
        }
    }

    /**
     * @see InputManager#registerKeyboardBacklightListener(Executor, KeyboardBacklightListener)
     */
    @RequiresPermission(Manifest.permission.MONITOR_KEYBOARD_BACKLIGHT)
    void registerKeyboardBacklightListener(@NonNull Executor executor,
            @NonNull KeyboardBacklightListener listener) throws IllegalArgumentException {
        Objects.requireNonNull(executor, "executor should not be null");
        Objects.requireNonNull(listener, "listener should not be null");

        synchronized (mKeyboardBacklightListenerLock) {
            if (mKeyboardBacklightListener == null) {
                mKeyboardBacklightListeners = new ArrayList<>();
                mKeyboardBacklightListener = new LocalKeyboardBacklightListener();

                try {
                    mIm.registerKeyboardBacklightListener(mKeyboardBacklightListener);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
            final int numListeners = mKeyboardBacklightListeners.size();
            for (int i = 0; i < numListeners; i++) {
                if (mKeyboardBacklightListeners.get(i).mListener == listener) {
                    throw new IllegalArgumentException("Listener has already been registered!");
                }
            }
            KeyboardBacklightListenerDelegate delegate =
                    new KeyboardBacklightListenerDelegate(listener, executor);
            mKeyboardBacklightListeners.add(delegate);
        }
    }

    /**
     * @see InputManager#unregisterKeyboardBacklightListener(KeyboardBacklightListener)
     */
    @RequiresPermission(Manifest.permission.MONITOR_KEYBOARD_BACKLIGHT)
    void unregisterKeyboardBacklightListener(
            @NonNull KeyboardBacklightListener listener) {
        Objects.requireNonNull(listener, "listener should not be null");

        synchronized (mKeyboardBacklightListenerLock) {
            if (mKeyboardBacklightListeners == null) {
                return;
            }
            mKeyboardBacklightListeners.removeIf((delegate) -> delegate.mListener == listener);
            if (mKeyboardBacklightListeners.isEmpty()) {
                try {
                    mIm.unregisterKeyboardBacklightListener(mKeyboardBacklightListener);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
                mKeyboardBacklightListeners = null;
                mKeyboardBacklightListener = null;
            }
        }
    }

    /**
     * @see InputManager#getInputDeviceSensorManager(int)
     */
    @NonNull
    public SensorManager getInputDeviceSensorManager(int deviceId) {
        if (mInputDeviceSensorManager == null) {
            mInputDeviceSensorManager = new InputDeviceSensorManager(this);
        }
        return mInputDeviceSensorManager.getSensorManager(deviceId);
    }

    /**
     * @see InputManager#getSensorList(int)
     */
    InputSensorInfo[] getSensorList(int deviceId) {
        try {
            return mIm.getSensorList(deviceId);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * @see InputManager#enableSensor(int, int, int, int)
     */
    boolean enableSensor(int deviceId, int sensorType, int samplingPeriodUs,
            int maxBatchReportLatencyUs) {
        try {
            return mIm.enableSensor(deviceId, sensorType, samplingPeriodUs,
                    maxBatchReportLatencyUs);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * @see InputManager#disableSensor(int, int)
     */
    void disableSensor(int deviceId, int sensorType) {
        try {
            mIm.disableSensor(deviceId, sensorType);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * @see InputManager#flushSensor(int, int)
     */
    boolean flushSensor(int deviceId, int sensorType) {
        try {
            return mIm.flushSensor(deviceId, sensorType);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * @see InputManager#registerSensorListener(IInputSensorEventListener)
     */
    boolean registerSensorListener(IInputSensorEventListener listener) {
        try {
            return mIm.registerSensorListener(listener);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * @see InputManager#unregisterSensorListener(IInputSensorEventListener)
     */
    void unregisterSensorListener(IInputSensorEventListener listener) {
        try {
            mIm.unregisterSensorListener(listener);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Gets a list of light objects associated with an input device.
     * @return The list of lights, never null.
     */
    @NonNull List<Light> getLights(int deviceId) {
        try {
            return mIm.getLights(deviceId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the state of an input device light.
     * @return the light state
     */
    @NonNull LightState getLightState(int deviceId, @NonNull Light light) {
        try {
            return mIm.getLightState(deviceId, light.getId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Request to modify the states of multiple lights.
     *
     * @param request the settings for lights that should change
     */
    void requestLights(int deviceId, @NonNull LightsRequest request, IBinder token) {
        try {
            List<Integer> lightIdList = request.getLights();
            int[] lightIds = new int[lightIdList.size()];
            for (int i = 0; i < lightIds.length; i++) {
                lightIds[i] = lightIdList.get(i);
            }
            List<LightState> lightStateList = request.getLightStates();
            mIm.setLightStates(deviceId, lightIds,
                    lightStateList.toArray(new LightState[0]),
                    token);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Open light session for input device manager
     *
     * @param token The token for the light session
     */
    void openLightSession(int deviceId, String opPkg, @NonNull IBinder token) {
        try {
            mIm.openLightSession(deviceId, opPkg, token);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Close light session
     *
     */
    void closeLightSession(int deviceId, @NonNull IBinder token) {
        try {
            mIm.closeLightSession(deviceId, token);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @see InputManager#getInputDeviceVibrator(int, int)
     */
    public Vibrator getInputDeviceVibrator(int deviceId, int vibratorId) {
        return new InputDeviceVibrator(deviceId, vibratorId);
    }

    /**
     * @see InputManager#getInputDeviceVibratorManager(int)
     */
    @NonNull
    public VibratorManager getInputDeviceVibratorManager(int deviceId) {
        return new InputDeviceVibratorManager(deviceId);
    }

    /*
     * Get the list of device vibrators
     * @return The list of vibrators IDs
     */
    int[] getVibratorIds(int deviceId) {
        try {
            return mIm.getVibratorIds(deviceId);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /*
     * Perform vibration effect
     */
    void vibrate(int deviceId, VibrationEffect effect, IBinder token) {
        try {
            mIm.vibrate(deviceId, effect, token);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /*
     * Perform combined vibration effect
     */
    void vibrate(int deviceId, CombinedVibration effect, IBinder token) {
        try {
            mIm.vibrateCombined(deviceId, effect, token);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /*
     * Cancel an ongoing vibration
     */
    void cancelVibrate(int deviceId, IBinder token) {
        try {
            mIm.cancelVibrate(deviceId, token);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /*
     * Check if input device is vibrating
     */
    boolean isVibrating(int deviceId)  {
        try {
            return mIm.isVibrating(deviceId);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Register input device vibrator state listener
     */
    boolean registerVibratorStateListener(int deviceId, IVibratorStateListener listener) {
        try {
            return mIm.registerVibratorStateListener(deviceId, listener);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Unregister input device vibrator state listener
     */
    boolean unregisterVibratorStateListener(int deviceId, IVibratorStateListener listener) {
        try {
            return mIm.unregisterVibratorStateListener(deviceId, listener);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * @see InputManager#deviceHasKeys(int, int[])
     */
    public boolean[] deviceHasKeys(int id, int[] keyCodes) {
        boolean[] ret = new boolean[keyCodes.length];
        try {
            mIm.hasKeys(id, InputDevice.SOURCE_ANY, keyCodes, ret);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return ret;
    }

    /**
     * @see InputManager#getKeyCodeforKeyLocation(int, int)
     */
    public int getKeyCodeForKeyLocation(int deviceId, int locationKeyCode) {
        try {
            return mIm.getKeyCodeForKeyLocation(deviceId, locationKeyCode);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @see InputManager#injectInputEvent(InputEvent, int, int)
     */

    public boolean injectInputEvent(InputEvent event, int mode, int targetUid) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        if (mode != InputEventInjectionSync.NONE
                && mode != InputEventInjectionSync.WAIT_FOR_FINISHED
                && mode != InputEventInjectionSync.WAIT_FOR_RESULT) {
            throw new IllegalArgumentException("mode is invalid");
        }

        try {
            return mIm.injectInputEventToTarget(event, mode, targetUid);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * @see InputManager#injectInputEvent(InputEvent, int)
     */
    public boolean injectInputEvent(InputEvent event, int mode) {
        return injectInputEvent(event, mode, Process.INVALID_UID);
    }

    /**
     * @see InputManager#setPointerIconType(int)
     */
    public void setPointerIconType(int iconId) {
        try {
            mIm.setPointerIconType(iconId);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * @see InputManager#setCustomPointerIcon(PointerIcon)
     */
    public void setCustomPointerIcon(PointerIcon icon) {
        try {
            mIm.setCustomPointerIcon(icon);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * @see InputManager#requestPointerCapture(IBinder, boolean)
     */
    void requestPointerCapture(IBinder windowToken, boolean enable) {
        try {
            mIm.requestPointerCapture(windowToken, enable);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * @see Inputmanager#monitorGestureInput(String, int)
     */
    InputMonitor monitorGestureInput(String name, int displayId) {
        try {
            return mIm.monitorGestureInput(new Binder(), name, displayId);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * @see InputManager#addUniqueIdAssociation(String, String)
     */
    void addUniqueIdAssociation(@NonNull String inputPort, @NonNull String displayUniqueId) {
        try {
            mIm.addUniqueIdAssociation(inputPort, displayUniqueId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @see InputManager#removeUniqueIdAssociation(String)
     */
    public void removeUniqueIdAssociation(@NonNull String inputPort) {
        try {
            mIm.removeUniqueIdAssociation(inputPort);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @see InputManager#getInputDeviceBluetoothAddress(int)
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH)
    @Nullable
    public String getInputDeviceBluetoothAddress(int deviceId) {
        try {
            return mIm.getInputDeviceBluetoothAddress(deviceId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @see InputManager#cancelCurrentTouch()
     */
    void cancelCurrentTouch() {
        try {
            mIm.cancelCurrentTouch();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @see InputManager#pilferPointers(IBinder)
     */
    @RequiresPermission(Manifest.permission.MONITOR_INPUT)
    void pilferPointers(IBinder inputChannelToken) {
        try {
            mIm.pilferPointers(inputChannelToken);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
