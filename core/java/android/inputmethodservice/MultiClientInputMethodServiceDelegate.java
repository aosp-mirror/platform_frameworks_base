/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.inputmethodservice;

import android.annotation.Nullable;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.ResultReceiver;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager.LayoutParams.SoftInputModeFlags;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.CursorAnchorInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import com.android.internal.inputmethod.StartInputFlags;

/**
 * Defines all the public APIs and interfaces that are necessary to implement multi-client IMEs.
 *
 * <p>Actual implementation is further delegated to
 * {@link MultiClientInputMethodServiceDelegateImpl}.</p>
 *
 * @hide
 */
public final class MultiClientInputMethodServiceDelegate {
    // @SdkConstant(SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE =
            "android.inputmethodservice.MultiClientInputMethodService";

    /**
     * Special value that is guaranteed to be not used for IME client ID.
     */
    public static final int INVALID_CLIENT_ID = -1;

    /**
     * Special value that is guaranteed to be not used for window handle.
     */
    public static final int INVALID_WINDOW_HANDLE = -1;

    private final MultiClientInputMethodServiceDelegateImpl mImpl;

    /**
     * Top-level callbacks for this {@link MultiClientInputMethodServiceDelegate}.
     */
    public interface ServiceCallback {
        /**
         * Called when this {@link MultiClientInputMethodServiceDelegate} is recognized by the
         * system and privileged operations like {@link #createInputMethodWindowToken(int)} are
         * ready to be called.
         */
        void initialized();

        /**
         * Called when a new IME client is recognized by the system.
         *
         * <p>Once the IME receives this callback, the IME can start interacting with the IME client
         * by calling {@link #acceptClient(int, ClientCallback, KeyEvent.DispatcherState, Looper)}.
         * </p>
         *
         * @param clientId ID of the client.
         * @param uid UID of the IME client.
         * @param pid PID of the IME client.
         * @param selfReportedDisplayId display ID reported from the IME client. Since the system
         *        does not validate this display ID, and at any time the IME client can lose the
         *        access to this display ID, the IME needs to call
         *        {@link #isUidAllowedOnDisplay(int, int)} to check whether the IME client still
         *        has access to this display or not.
         */
        void addClient(int clientId, int uid, int pid, int selfReportedDisplayId);

        /**
         * Called when an IME client is being destroyed.
         *
         * @param clientId ID of the client.
         */
        void removeClient(int clientId);
    }

    /**
     * Per-client callbacks.
     */
    public interface ClientCallback {
        /**
         * Called when the associated IME client called {@link
         * android.view.inputmethod.InputMethodManager#sendAppPrivateCommand(View, String, Bundle)}.
         *
         * @param action Name of the command to be performed.
         * @param data Any data to include with the command.
         * @see android.inputmethodservice.InputMethodService#onAppPrivateCommand(String, Bundle)
         */
        void onAppPrivateCommand(String action, Bundle data);

        /**
         * Called when the associated IME client called {@link
         * android.view.inputmethod.InputMethodManager#displayCompletions(View, CompletionInfo[])}.
         *
         * @param completions Completion information provided from the IME client.
         * @see android.inputmethodservice.InputMethodService#onDisplayCompletions(CompletionInfo[])
         */
        void onDisplayCompletions(CompletionInfo[] completions);

        /**
         * Called when this callback session is closed. No further callback should not happen on
         * this callback object.
         */
        void onFinishSession();

        /**
         * Called when the associated IME client called {@link
         * android.view.inputmethod.InputMethodManager#hideSoftInputFromWindow(IBinder, int)} or
         * {@link android.view.inputmethod.InputMethodManager#hideSoftInputFromWindow(IBinder, int,
         * ResultReceiver)}.
         *
         * @param flags The flag passed by the client.
         * @param resultReceiver The {@link ResultReceiver} passed by the client.
         * @see android.inputmethodservice.InputMethodService#onWindowHidden()
         */
        void onHideSoftInput(int flags, ResultReceiver resultReceiver);

        /**
         * Called when the associated IME client called {@link
         * android.view.inputmethod.InputMethodManager#showSoftInput(View, int)} or {@link
         * android.view.inputmethod.InputMethodManager#showSoftInput(View, int, ResultReceiver)}.
         *
         * @param flags The flag passed by the client.
         * @param resultReceiver The {@link ResultReceiver} passed by the client.
         * @see android.inputmethodservice.InputMethodService#onWindowShown()
         */
        void onShowSoftInput(int flags, ResultReceiver resultReceiver);

        /**
         * A generic callback when {@link InputConnection} is being established.
         *
         * @param inputConnection The {@link InputConnection} to be established.
         * @param editorInfo The {@link EditorInfo} reported from the IME client.
         * @param startInputFlags Any combinations of {@link StartInputFlags}.
         * @param softInputMode SoftWindowMode specified to this window.
         * @param targetWindowHandle A unique Window token.
         * @see android.inputmethodservice.InputMethodService#onStartInput(EditorInfo, boolean)
         */
        void onStartInputOrWindowGainedFocus(
                @Nullable InputConnection inputConnection,
                @Nullable EditorInfo editorInfo,
                @StartInputFlags int startInputFlags,
                @SoftInputModeFlags int softInputMode,
                int targetWindowHandle);

        /**
         * Called when the associated IME client called {@link
         * android.view.inputmethod.InputMethodManager#toggleSoftInput(int, int)}.
         *
         * @param showFlags The flag passed by the client.
         * @param hideFlags The flag passed by the client.
         * @see android.inputmethodservice.InputMethodService#onToggleSoftInput(int, int)
         */
        void onToggleSoftInput(int showFlags, int hideFlags);

        /**
         * Called when the associated IME client called {@link
         * android.view.inputmethod.InputMethodManager#updateCursorAnchorInfo(View,
         * CursorAnchorInfo)}.
         *
         * @param info The {@link CursorAnchorInfo} passed by the client.
         * @see android.inputmethodservice.InputMethodService#onUpdateCursorAnchorInfo(
         *      CursorAnchorInfo)
         */
        void onUpdateCursorAnchorInfo(CursorAnchorInfo info);

        /**
         * Called when the associated IME client called {@link
         * android.view.inputmethod.InputMethodManager#updateSelection(View, int, int, int, int)}.
         *
         * @param oldSelStart The previous selection start index.
         * @param oldSelEnd The previous selection end index.
         * @param newSelStart The new selection start index.
         * @param newSelEnd The new selection end index.
         * @param candidatesStart The new candidate start index.
         * @param candidatesEnd The new candidate end index.
         * @see android.inputmethodservice.InputMethodService#onUpdateSelection(int, int, int, int,
         *      int, int)
         */
        void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd,
                int candidatesStart, int candidatesEnd);

        /**
         * Called to give a chance for the IME to intercept generic motion events before they are
         * processed by the application.
         *
         * @param event {@link MotionEvent} that is about to be handled by the IME client.
         * @return {@code true} to tell the IME client that the IME handled this event.
         * @see android.inputmethodservice.InputMethodService#onGenericMotionEvent(MotionEvent)
         */
        boolean onGenericMotionEvent(MotionEvent event);

        /**
         * Called to give a chance for the IME to intercept key down events before they are
         * processed by the application.
         *
         * @param keyCode The value in {@link KeyEvent#getKeyCode()}.
         * @param event {@link KeyEvent} for this key down event.
         * @return {@code true} to tell the IME client that the IME handled this event.
         * @see android.inputmethodservice.InputMethodService#onKeyDown(int, KeyEvent)
         */
        boolean onKeyDown(int keyCode, KeyEvent event);

        /**
         * Called to give a chance for the IME to intercept key long press events before they are
         * processed by the application.
         *
         * @param keyCode The value in {@link KeyEvent#getKeyCode()}.
         * @param event {@link KeyEvent} for this key long press event.
         * @return {@code true} to tell the IME client that the IME handled this event.
         * @see android.inputmethodservice.InputMethodService#onKeyLongPress(int, KeyEvent)
         */
        boolean onKeyLongPress(int keyCode, KeyEvent event);

        /**
         * Called to give a chance for the IME to intercept key multiple events before they are
         * processed by the application.
         *
         * @param keyCode The value in {@link KeyEvent#getKeyCode()}.
         * @param event {@link KeyEvent} for this key multiple event.
         * @return {@code true} to tell the IME client that the IME handled this event.
         * @see android.inputmethodservice.InputMethodService#onKeyMultiple(int, int, KeyEvent)
         */
        boolean onKeyMultiple(int keyCode, KeyEvent event);

        /**
         * Called to give a chance for the IME to intercept key up events before they are processed
         * by the application.
         *
         * @param keyCode The value in {@link KeyEvent#getKeyCode()}.
         * @param event {@link KeyEvent} for this key up event.
         * @return {@code true} to tell the IME client that the IME handled this event.
         * @see android.inputmethodservice.InputMethodService#onKeyUp(int, KeyEvent)
         */
        boolean onKeyUp(int keyCode, KeyEvent event);

        /**
         * Called to give a chance for the IME to intercept generic motion events before they are
         * processed by the application.
         *
         * @param event {@link MotionEvent} that is about to be handled by the IME client.
         * @return {@code true} to tell the IME client that the IME handled this event.
         * @see android.inputmethodservice.InputMethodService#onTrackballEvent(MotionEvent)
         */
        boolean onTrackballEvent(MotionEvent event);
    }

    private MultiClientInputMethodServiceDelegate(Context context,
            ServiceCallback serviceCallback) {
        mImpl = new MultiClientInputMethodServiceDelegateImpl(context, serviceCallback);
    }

    /**
     * Must be called by the multi-client IME implementer to create
     * {@link MultiClientInputMethodServiceDelegate}.
     *
     * @param context {@link Context} with which the delegate should interact with the system.
     * @param serviceCallback {@link ServiceCallback} to receive service-level callbacks.
     * @return A new instance of {@link MultiClientInputMethodServiceDelegate}.
     */
    public static MultiClientInputMethodServiceDelegate create(Context context,
            ServiceCallback serviceCallback) {
        return new MultiClientInputMethodServiceDelegate(context, serviceCallback);
    }

    /**
     * Must be called by the multi-client IME service when {@link android.app.Service#onDestroy()}
     * is called.
     */
    public void onDestroy() {
        mImpl.onDestroy();
    }

    /**
     * Must be called by the multi-client IME service when
     * {@link android.app.Service#onBind(Intent)} is called.
     *
     * @param intent {@link Intent} passed to {@link android.app.Service#onBind(Intent)}.
     * @return An {@link IBinder} object that needs to be returned from
     *         {@link android.app.Service#onBind(Intent)}.
     */
    public IBinder onBind(Intent intent) {
        return mImpl.onBind(intent);
    }

    /**
     * Must be called by the multi-client IME service when
     * {@link android.app.Service#onUnbind(Intent)} is called.
     *
     * @param intent {@link Intent} passed to {@link android.app.Service#onUnbind(Intent)}.
     * @return A boolean value that needs to be returned from
     *         {@link android.app.Service#onUnbind(Intent)}.
     */
    public boolean onUnbind(Intent intent) {
        return mImpl.onUnbind(intent);
    }

    /**
     * Must be called by the multi-client IME service to create a special window token for IME
     * window.
     *
     * <p>This method is available only after {@link ServiceCallback#initialized()}.</p>
     *
     * @param displayId display ID on which the IME window will be shown.
     * @return Window token to be specified to the IME window/
     */
    public IBinder createInputMethodWindowToken(int displayId) {
        return mImpl.createInputMethodWindowToken(displayId);
    }

    /**
     * Must be called by the multi-client IME service to notify the system when the IME is ready to
     * accept callback events from the specified IME client.
     *
     * @param clientId The IME client ID specified in
     *                 {@link ServiceCallback#addClient(int, int, int, int)}.
     * @param clientCallback The {@link ClientCallback} to receive callback events from this IME
     *                       client.
     * @param dispatcherState {@link KeyEvent.DispatcherState} to be used when receiving key-related
     *                        callbacks in {@link ClientCallback}.
     * @param looper {@link Looper} on which {@link ClientCallback} will be called back.
     */
    public void acceptClient(int clientId, ClientCallback clientCallback,
            KeyEvent.DispatcherState dispatcherState, Looper looper) {
        mImpl.acceptClient(clientId, clientCallback, dispatcherState, looper);
    }

    /**
     * Must be called by the multi-client IME service to notify the system when the IME is ready to
     * interact with the window in the IME client.
     *
     * @param clientId The IME client ID specified in
     *                 {@link ServiceCallback#addClient(int, int, int, int)}.
     * @param targetWindowHandle The window handle specified in
     *                           {@link ClientCallback#onStartInputOrWindowGainedFocus}.
     * @param imeWindowToken The IME window token returned from
     *                       {@link #createInputMethodWindowToken(int)}.
     */
    public void reportImeWindowTarget(int clientId, int targetWindowHandle,
            IBinder imeWindowToken) {
        mImpl.reportImeWindowTarget(clientId, targetWindowHandle, imeWindowToken);
    }

    /**
     * Can be called by the multi-client IME service to check if the given {@code uid} is allowed
     * to access to {@code displayId}.
     *
     * @param displayId Display ID to be queried.
     * @param uid UID to be queried.
     * @return {@code true} if {@code uid} is allowed to access to {@code displayId}.
     */
    public boolean isUidAllowedOnDisplay(int displayId, int uid) {
        return mImpl.isUidAllowedOnDisplay(displayId, uid);
    }

    /**
     * Can be called by MSIME to activate/deactivate a client when it is gaining/losing focus
     * respectively.
     *
     * @param clientId client ID to activate/deactivate.
     * @param active {@code true} to activate a client.
     */
    public void setActive(int clientId, boolean active) {
        mImpl.setActive(clientId, active);
    }
}
