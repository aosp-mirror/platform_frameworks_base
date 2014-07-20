/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.telecomm;

import android.app.PendingIntent;
import android.util.ArrayMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A unified virtual device providing a means of voice (and other) communication on a device.
 */
public final class Phone {

    public abstract static class Listener {
        /**
         * Called when the audio state changes.
         *
         * @param phone The {@code Phone} calling this method.
         * @param audioState The new {@link CallAudioState}.
         */
        public void onAudioStateChanged(Phone phone, CallAudioState audioState) { }

        /**
         * Called to bring the in-call screen to the foreground. The in-call experience should
         * respond immediately by coming to the foreground to inform the user of the state of
         * ongoing {@code Call}s.
         *
         * @param phone The {@code Phone} calling this method.
         * @param showDialpad If true, put up the dialpad when the screen is shown.
         */
        public void onBringToForeground(Phone phone, boolean showDialpad) { }

        /**
         * Called when a {@code Call} has been added to this in-call session. The in-call user
         * experience should add necessary state listeners to the specified {@code Call} and
         * immediately start to show the user information about the existence
         * and nature of this {@code Call}. Subsequent invocations of {@link #getCalls()} will
         * include this {@code Call}.
         *
         * @param phone The {@code Phone} calling this method.
         * @param call A newly added {@code Call}.
         */
        public void onCallAdded(Phone phone, Call call) { }

        /**
         * Called when a {@code Call} has been removed from this in-call session. The in-call user
         * experience should remove any state listeners from the specified {@code Call} and
         * immediately stop displaying any information about this {@code Call}.
         * Subsequent invocations of {@link #getCalls()} will no longer include this {@code Call}.
         *
         * @param phone The {@code Phone} calling this method.
         * @param call A newly removed {@code Call}.
         */
        public void onCallRemoved(Phone phone, Call call) { }
    }

    // A Map allows us to track each Call by its Telecomm-specified call ID
    private final Map<String, Call> mCallByTelecommCallId = new ArrayMap<>();

    // A List allows us to keep the Calls in a stable iteration order so that casually developed
    // user interface components do not incur any spurious jank
    private final List<Call> mCalls = new ArrayList<>();

    // An unmodifiable view of the above List can be safely shared with subclass implementations
    private final List<Call> mUnmodifiableCalls = Collections.unmodifiableList(mCalls);

    private final InCallAdapter mInCallAdapter;

    private CallAudioState mAudioState;

    private final List<Listener> mListeners = new ArrayList<>();

    /** {@hide} */
    Phone(InCallAdapter adapter) {
        mInCallAdapter = adapter;
    }

    /** {@hide} */
    final void internalAddCall(InCallCall inCallCall) {
        Call call = new Call(this, inCallCall.getId(), mInCallAdapter);
        mCallByTelecommCallId.put(inCallCall.getId(), call);
        mCalls.add(call);
        checkCallTree(inCallCall);
        call.internalUpdate(inCallCall);
        fireCallAdded(call);
     }

    /** {@hide} */
    final void internalRemoveCall(Call call) {
        mCallByTelecommCallId.remove(call.internalGetCallId());
        mCalls.remove(call);
        fireCallRemoved(call);
    }

    /** {@hide} */
    final void internalUpdateCall(InCallCall inCallCall) {
         Call call = mCallByTelecommCallId.get(inCallCall.getId());
         if (call != null) {
             checkCallTree(inCallCall);
             call.internalUpdate(inCallCall);
         }
     }

    /** {@hide} */
    final void internalSetPostDial(String telecommId, String remaining) {
        Call call = mCallByTelecommCallId.get(telecommId);
        if (call != null) {
            call.internalSetPostDial(remaining);
        }
    }

    /** {@hide} */
    final void internalSetPostDialWait(String telecommId, String remaining) {
        Call call = mCallByTelecommCallId.get(telecommId);
        if (call != null) {
            call.internalSetPostDialWait(remaining);
        }
    }

    /** {@hide} */
    final void internalAudioStateChanged(CallAudioState callAudioState) {
        if (!Objects.equals(mAudioState, callAudioState)) {
            mAudioState = callAudioState;
            fireAudioStateChanged(callAudioState);
        }
    }

    /** {@hide} */
    final Call internalGetCallByTelecommId(String telecommId) {
        return mCallByTelecommCallId.get(telecommId);
    }

    /** {@hide} */
    final void internalBringToForeground(boolean showDialpad) {
        fireBringToForeground(showDialpad);
    }

    /** {@hide} */
    final void internalStartActivity(String telecommId, PendingIntent intent) {
        Call call = mCallByTelecommCallId.get(telecommId);
        if (call != null) {
            call.internalStartActivity(intent);
        }
    }

    /**
     * Adds a listener to this {@code Phone}.
     *
     * @param listener A {@code Listener} object.
     */
    public final void addListener(Listener listener) {
        mListeners.add(listener);
    }

    /**
     * Removes a listener from this {@code Phone}.
     *
     * @param listener A {@code Listener} object.
     */
    public final void removeListener(Listener listener) {
        mListeners.remove(listener);
    }

    /**
     * Obtains the current list of {@code Call}s to be displayed by this in-call experience.
     *
     * @return A list of the relevant {@code Call}s.
     */
    public final List<Call> getCalls() {
        return mUnmodifiableCalls;
    }

    /**
     * Sets the microphone mute state. When this request is honored, there will be change to
     * the {@link #getAudioState()}.
     *
     * @param state {@code true} if the microphone should be muted; {@code false} otherwise.
     */
    public final void setMuted(boolean state) {
        mInCallAdapter.mute(state);
    }

    /**
     * Sets the audio route (speaker, bluetooth, etc...).  When this request is honored, there will
     * be change to the {@link #getAudioState()}.
     *
     * @param route The audio route to use.
     */
    public final void setAudioRoute(int route) {
        mInCallAdapter.setAudioRoute(route);
    }

    /**
     * Obtains the current phone call audio state of the {@code Phone}.
     *
     * @return An object encapsulating the audio state.
     */
    public final CallAudioState getAudioState() {
        return mAudioState;
    }

    private void fireCallAdded(Call call) {
        Listener[] listeners = mListeners.toArray(new Listener[mListeners.size()]);
        for (int i = 0; i < listeners.length; i++) {
            listeners[i].onCallAdded(this, call);
        }
    }

    private void fireCallRemoved(Call call) {
        Listener[] listeners = mListeners.toArray(new Listener[mListeners.size()]);
        for (int i = 0; i < listeners.length; i++) {
            listeners[i].onCallRemoved(this, call);
        }
    }

    private void fireAudioStateChanged(CallAudioState audioState) {
        Listener[] listeners = mListeners.toArray(new Listener[mListeners.size()]);
        for (int i = 0; i < listeners.length; i++) {
            listeners[i].onAudioStateChanged(this, audioState);
        }
    }

    private void fireBringToForeground(boolean showDialpad) {
        Listener[] listeners = mListeners.toArray(new Listener[mListeners.size()]);
        for (int i = 0; i < listeners.length; i++) {
            listeners[i].onBringToForeground(this, showDialpad);
        }
    }

    private void checkCallTree(InCallCall inCallCall) {
        if (inCallCall.getParentCallId() != null &&
                !mCallByTelecommCallId.containsKey(inCallCall.getParentCallId())) {
            Log.wtf(this, "InCallCall %s has nonexistent parent %s",
                    inCallCall.getId(), inCallCall.getParentCallId());
        }
        if (inCallCall.getChildCallIds() != null) {
            for (int i = 0; i < inCallCall.getChildCallIds().size(); i++) {
                if (!mCallByTelecommCallId.containsKey(inCallCall.getChildCallIds().get(i))) {
                    Log.wtf(this, "InCallCall %s has nonexistent child %s",
                            inCallCall.getId(), inCallCall.getChildCallIds().get(i));
                }
            }
        }
    }
}
