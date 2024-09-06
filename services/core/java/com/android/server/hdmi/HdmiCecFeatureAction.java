/*
 * Copyright (C) 2014 The Android Open Source Project
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
package com.android.server.hdmi;

import android.hardware.hdmi.IHdmiControlCallback;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.hdmi.HdmiControlService.DevicePollingCallback;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Encapsulates a sequence of CEC command exchange for a certain feature.
 * <p>
 * Many CEC features are accomplished by CEC devices on the bus exchanging more than one
 * command. {@link HdmiCecFeatureAction} represents the life cycle of the communication, manages the
 * state as the process progresses, and if necessary, returns the result to the caller which
 * initiates the action, through the callback given at the creation of the object. All the actual
 * action classes inherit FeatureAction.
 * <p>
 * More than one FeatureAction objects can be up and running simultaneously, maintained by
 * {@link HdmiCecLocalDevice}. Each action is passed a new command arriving from the bus, and either
 * consumes it if the command is what the action expects, or yields it to other action. Declared as
 * package private, accessed by {@link HdmiControlService} only.
 */
abstract class HdmiCecFeatureAction {
    private static final String TAG = "HdmiCecFeatureAction";

    // Timer handler message used for timeout event
    protected static final int MSG_TIMEOUT = 100;

    // Default state used in common by all the feature actions.
    protected static final int STATE_NONE = 0;

    // Delay to query avr's audio status, some avrs could report the old volume first. It could
    // show inverse mute state on TV.
    protected static final long DELAY_GIVE_AUDIO_STATUS = 500;

    // Internal state indicating the progress of action.
    protected int mState = STATE_NONE;

    private final HdmiControlService mService;
    private final HdmiCecLocalDevice mSource;

    // Timer that manages timeout events.
    protected ActionTimer mActionTimer;

    private ArrayList<Pair<HdmiCecFeatureAction, Runnable>> mOnFinishedCallbacks;

    final List<IHdmiControlCallback> mCallbacks = new ArrayList<>();

    HdmiCecFeatureAction(HdmiCecLocalDevice source) {
        this(source, new ArrayList<>());
    }

    HdmiCecFeatureAction(HdmiCecLocalDevice source, IHdmiControlCallback callback) {
        this(source, Arrays.asList(callback));
    }

    HdmiCecFeatureAction(HdmiCecLocalDevice source, List<IHdmiControlCallback> callbacks) {
        for (IHdmiControlCallback callback : callbacks) {
            addCallback(callback);
        }
        mSource = source;
        mService = mSource.getService();
        mActionTimer = createActionTimer(mService.getServiceLooper());
    }

    @VisibleForTesting
    void setActionTimer(ActionTimer actionTimer) {
        mActionTimer = actionTimer;
    }

    /**
     * Called after the action is created. Initialization or first step to take
     * for the action can be done in this method. Shall update {@code mState} to
     * indicate that the action has started.
     *
     * @return true if the operation is successful; otherwise false.
     */
    abstract boolean start();

    /**
     * Process the command. Called whenever a new command arrives.
     *
     * @param cmd command to process
     * @return true if the command was consumed in the process; Otherwise false.
     */
    abstract boolean processCommand(HdmiCecMessage cmd);

    /**
     * Called when the action should handle the timer event it created before.
     *
     * <p>CEC standard mandates each command transmission should be responded within
     * certain period of time. The method is called when the timer it created as it transmitted
     * a command gets expired. Inner logic should take an appropriate action.
     *
     * @param state the state associated with the time when the timer was created
     */
    abstract void handleTimerEvent(int state);

    /**
     * Timer handler interface used for FeatureAction classes.
     */
    interface ActionTimer {
        /**
         * Send a timer message.
         *
         * Also carries the state of the action when the timer is created. Later this state is
         * compared to the one the action is in when it receives the timer to let the action tell
         * the right timer to handle.
         *
         * @param state state of the action is in
         * @param delayMillis amount of delay for the timer
         */
        void sendTimerMessage(int state, long delayMillis);

        /**
         * Removes any pending timer message.
         */
        void clearTimerMessage();
    }

    private class ActionTimerHandler extends Handler implements ActionTimer {

        public ActionTimerHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void sendTimerMessage(int state, long delayMillis) {
            // The third argument(0) is not used.
            sendMessageDelayed(obtainMessage(MSG_TIMEOUT, state, 0), delayMillis);
        }

        @Override
        public void clearTimerMessage() {
            removeMessages(MSG_TIMEOUT);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case MSG_TIMEOUT:
                handleTimerEvent(msg.arg1);
                break;
            default:
                Slog.w(TAG, "Unsupported message:" + msg.what);
                break;
            }
        }
    }

    private ActionTimer createActionTimer(Looper looper) {
        return new ActionTimerHandler(looper);
    }

    // Add a new timer. The timer event will come to mActionTimer.handleMessage() in
    // delayMillis.
    protected void addTimer(int state, int delayMillis) {
        mActionTimer.sendTimerMessage(state, delayMillis);
    }

    boolean started() {
        return mState != STATE_NONE;
    }

    protected final void sendCommand(HdmiCecMessage cmd) {
        mService.sendCecCommand(cmd);
    }

    protected final void sendCommand(HdmiCecMessage cmd,
            HdmiControlService.SendMessageCallback callback) {
        mService.sendCecCommand(cmd, callback);
    }

    protected final void sendCommandWithoutRetries(HdmiCecMessage cmd,
            HdmiControlService.SendMessageCallback callback) {
        mService.sendCecCommandWithoutRetries(cmd, callback);
    }

    protected final void addAndStartAction(HdmiCecFeatureAction action) {
        mSource.addAndStartAction(action);
    }

    protected final <T extends HdmiCecFeatureAction> List<T> getActions(final Class<T> clazz) {
        return mSource.getActions(clazz);
    }

    protected final HdmiCecMessageCache getCecMessageCache() {
        return mSource.getCecMessageCache();
    }

    /**
     * Remove the action from the action queue. This is called after the action finishes
     * its role.
     *
     * @param action
     */
    protected final void removeAction(HdmiCecFeatureAction action) {
        mSource.removeAction(action);
    }

    protected final <T extends HdmiCecFeatureAction> void removeAction(final Class<T> clazz) {
        mSource.removeActionExcept(clazz, null);
    }

    protected final <T extends HdmiCecFeatureAction> void removeActionExcept(final Class<T> clazz,
            final HdmiCecFeatureAction exception) {
        mSource.removeActionExcept(clazz, exception);
    }

    protected final void pollDevices(DevicePollingCallback callback, int pickStrategy,
            int retryCount) {
        pollDevices(callback, pickStrategy, retryCount, 0);
    }

    protected final void pollDevices(DevicePollingCallback callback, int pickStrategy,
            int retryCount, long pollingMessageInterval) {
        mService.pollDevices(
                callback, getSourceAddress(), pickStrategy, retryCount, pollingMessageInterval);
    }

    /**
     * Clean up action's state.
     *
     * <p>Declared as package-private. Only {@link HdmiControlService} can access it.
     */
    void clear() {
        mState = STATE_NONE;
        // Clear all timers.
        mActionTimer.clearTimerMessage();
    }

    /**
     * Finish up the action. Reset the state, and remove itself from the action queue.
     */
    protected void finish() {
        finish(true);
    }

    void finish(boolean removeSelf) {
        clear();
        if (removeSelf) {
            removeAction(this);
        }
        if (mOnFinishedCallbacks != null) {
            for (Pair<HdmiCecFeatureAction, Runnable> actionCallbackPair: mOnFinishedCallbacks) {
                if (actionCallbackPair.first.mState != STATE_NONE) {
                    actionCallbackPair.second.run();
                }
            }
            mOnFinishedCallbacks = null;
        }
    }

    protected final HdmiCecLocalDevice localDevice() {
        return mSource;
    }

    protected final HdmiCecLocalDevicePlayback playback() {
        return (HdmiCecLocalDevicePlayback) mSource;
    }

    protected final HdmiCecLocalDeviceSource source() {
        return (HdmiCecLocalDeviceSource) mSource;
    }

    protected final HdmiCecLocalDeviceTv tv() {
        return (HdmiCecLocalDeviceTv) mSource;
    }

    protected final HdmiCecLocalDeviceAudioSystem audioSystem() {
        return (HdmiCecLocalDeviceAudioSystem) mSource;
    }

    protected final int getSourceAddress() {
        return mSource.getDeviceInfo().getLogicalAddress();
    }

    protected final int getSourcePath() {
        return mSource.getDeviceInfo().getPhysicalAddress();
    }

    protected final void sendUserControlPressedAndReleased(int targetAddress, int uiCommand) {
        mSource.sendUserControlPressedAndReleased(targetAddress, uiCommand);
    }

    protected final void addOnFinishedCallback(HdmiCecFeatureAction action, Runnable runnable) {
        if (mOnFinishedCallbacks == null) {
            mOnFinishedCallbacks = new ArrayList<>();
        }
        mOnFinishedCallbacks.add(Pair.create(action, runnable));
    }

    protected void finishWithCallback(int returnCode) {
        invokeCallback(returnCode);
        finish();
    }

    public void addCallback(IHdmiControlCallback callback) {
        mCallbacks.add(callback);
    }

    private void invokeCallback(int result) {
        try {
            for (IHdmiControlCallback callback : mCallbacks) {
                if (callback == null) {
                    continue;
                }
                callback.onComplete(result);
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Callback failed:" + e);
        }
    }
}
