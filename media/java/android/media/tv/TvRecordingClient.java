/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.media.tv;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * The public interface object used to interact with a specific TV input service for TV program
 * recording.
 */
public class TvRecordingClient {
    private static final String TAG = "TvRecordingClient";
    private static final boolean DEBUG = false;

    private final RecordingCallback mCallback;
    private final Handler mHandler;

    private final TvInputManager mTvInputManager;
    private TvInputManager.Session mSession;
    private MySessionCallback mSessionCallback;

    private final Queue<Pair<String, Bundle>> mPendingAppPrivateCommands = new ArrayDeque<>();

    /**
     * Creates a new TvRecordingClient object.
     *
     * @param context The application context to create the TvRecordingClient with.
     * @param tag A short name for debugging purposes.
     * @param callback The callback to receive recording status changes.
     * @param handler The handler to invoke the callback on.
     */
    public TvRecordingClient(Context context, String tag, @NonNull RecordingCallback callback,
            Handler handler) {
        mCallback = callback;
        mHandler = handler == null ? new Handler(Looper.getMainLooper()) : handler;
        mTvInputManager = (TvInputManager) context.getSystemService(Context.TV_INPUT_SERVICE);
    }

    /**
     * Connects to a given input for TV program recording. This will create a new recording session
     * from the TV input and establishes the connection between the application and the session.
     *
     * <p>The recording session will respond by calling
     * {@link RecordingCallback#onConnected()} or {@link RecordingCallback#onError(int)}.
     *
     * @param inputId The ID of the TV input for the given channel.
     * @param channelUri The URI of a channel.
     */
    public void connect(String inputId, Uri channelUri) {
        connect(inputId, channelUri, null);
    }

    /**
     * Connects to a given input for TV program recording. This will create a new recording session
     * from the TV input and establishes the connection between the application and the session.
     *
     * <p>The recording session will respond by calling
     * {@link RecordingCallback#onConnected()} or {@link RecordingCallback#onError(int)}.
     *
     * @param inputId The ID of the TV input for the given channel.
     * @param channelUri The URI of a channel.
     * @param params Extra parameters.
     * @hide
     */
    @SystemApi
    public void connect(String inputId, Uri channelUri, Bundle params) {
        if (DEBUG) Log.d(TAG, "connect(" + channelUri + ")");
        if (TextUtils.isEmpty(inputId)) {
            throw new IllegalArgumentException("inputId cannot be null or an empty string");
        }
        if (mSessionCallback != null && TextUtils.equals(mSessionCallback.mInputId, inputId)) {
            if (mSession != null) {
                mSession.connect(channelUri, params);
            } else {
                mSessionCallback.mChannelUri = channelUri;
                mSessionCallback.mConnectionParams = params;
            }
        } else {
            resetInternal();
            mSessionCallback = new MySessionCallback(inputId, channelUri, params);
            if (mTvInputManager != null) {
                mTvInputManager.createRecordingSession(inputId, mSessionCallback, mHandler);
            }
        }
    }

    /**
     * Disconnects the established connection between the application and the recording session.
     *
     * <p>The recording session will respond by calling
     * {@link RecordingCallback#onDisconnected()} or {@link RecordingCallback#onError(int)}.
     */
    public void disconnect() {
        if (DEBUG) Log.d(TAG, "disconnect()");
        resetInternal();
    }

    private void resetInternal() {
        mSessionCallback = null;
        mPendingAppPrivateCommands.clear();
        if (mSession != null) {
            mSession.release();
            mSession = null;
        }
    }

    /**
     * Starts TV program recording for the current recording session. It is expected that recording
     * starts immediately after calling this method.
     *
     * <p>The recording session will respond by calling
     * {@link RecordingCallback#onRecordingStarted()} or {@link RecordingCallback#onError(int)}.
     */
    public void startRecording() {
        if (mSession != null) {
            mSession.startRecording();
        }
    }

    /**
     * Stops TV program recording for the current recording session. It is expected that recording
     * stops immediately after calling this method.
     *
     * <p>The recording session will respond by calling
     * {@link RecordingCallback#onRecordingStopped(Uri)} or {@link RecordingCallback#onError(int)}.
     */
    public void stopRecording() {
        if (mSession != null) {
            mSession.stopRecording();
        }
    }

    /**
     * Calls {@link TvInputService.RecordingSession#appPrivateCommand(String, Bundle)
     * TvInputService.RecordingSession.appPrivateCommand()} on the current TvView.
     *
     * @param action The name of the private command to send. This <em>must</em> be a scoped name,
     *            i.e. prefixed with a package name you own, so that different developers will not
     *            create conflicting commands.
     * @param data An optional bundle to send with the command.
     * @hide
     */
    @SystemApi
    public void sendAppPrivateCommand(@NonNull String action, Bundle data) {
        if (TextUtils.isEmpty(action)) {
            throw new IllegalArgumentException("action cannot be null or an empty string");
        }
        if (mSession != null) {
            mSession.sendAppPrivateCommand(action, data);
        } else {
            Log.w(TAG, "sendAppPrivateCommand - session not yet created (action \"" + action
                    + "\" pending)");
            mPendingAppPrivateCommands.add(Pair.create(action, data));
        }
    }

    /**
     * Callback used to receive various status updates on the
     * {@link android.media.tv.TvInputService.RecordingSession}
     */
    public abstract static class RecordingCallback {
        /**
         * This is called when a recording session initiated by a call to
         * {@link #connect(String, Uri)} has been established.
         */
        public void onConnected() {
        }

        /**
         * This is called when the established connection between the application and the recording
         * session has been disconnected. Disconnection can be initiated either by an explicit
         * request (i.e. a call to {@link #disconnect()} or by an error on the TV input service
         * side.
         */
        public void onDisconnected() {
        }

        /**
         * This is called when TV program recording on the current channel has started.
         */
        public void onRecordingStarted() {
        }

        /**
         * This is called when TV program recording on the current channel has stopped. The passed
         * URI contains information about the new recorded program.
         *
         * @param recordedProgramUri The URI for the new recorded program.
         * @see android.media.tv.TvContract.RecordedPrograms
         */
        public void onRecordingStopped(Uri recordedProgramUri) {
        }

        /**
         * This is called when an issue has occurred before or during recording. If the TV input
         * service cannot proceed recording due to this error, a call to {@link #onDisconnected()}
         * is expected to follow.
         *
         * @param error The error code. Should be one of the followings.
         * <ul>
         * <li>{@link TvInputManager#RECORDING_ERROR_UNKNOWN}
         * <li>{@link TvInputManager#RECORDING_ERROR_CONNECTION_FAILED}
         * <li>{@link TvInputManager#RECORDING_ERROR_INSUFFICIENT_SPACE}
         * <li>{@link TvInputManager#RECORDING_ERROR_RESOURCE_BUSY}
         * </ul>
         */
        public void onError(@TvInputManager.RecordingError int error) {
        }

        /**
         * This is invoked when a custom event from the bound TV input is sent to this client.
         *
         * @param inputId The ID of the TV input bound to this client.
         * @param eventType The type of the event.
         * @param eventArgs Optional arguments of the event.
         * @hide
         */
        @SystemApi
        public void onEvent(String inputId, String eventType, Bundle eventArgs) {
        }
    }

    private class MySessionCallback extends TvInputManager.SessionCallback {
        final String mInputId;
        Uri mChannelUri;
        Bundle mConnectionParams;

        MySessionCallback(String inputId, Uri channelUri, Bundle connectionParams) {
            mInputId = inputId;
            mChannelUri = channelUri;
            mConnectionParams = connectionParams;
        }

        @Override
        public void onSessionCreated(TvInputManager.Session session) {
            if (DEBUG) {
                Log.d(TAG, "onSessionCreated()");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onSessionCreated - session already created");
                // This callback is obsolete.
                if (session != null) {
                    session.release();
                }
                return;
            }
            mSession = session;
            if (session != null) {
                // Sends the pending app private commands.
                for (Pair<String, Bundle> command : mPendingAppPrivateCommands) {
                    mSession.sendAppPrivateCommand(command.first, command.second);
                }
                mPendingAppPrivateCommands.clear();
                mSession.connect(mChannelUri, mConnectionParams);
            } else {
                mSessionCallback = null;
                mCallback.onError(TvInputManager.RECORDING_ERROR_CONNECTION_FAILED);
            }
        }

        @Override
        void onConnected(TvInputManager.Session session) {
            if (DEBUG) {
                Log.d(TAG, "onConnected()");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onConnected - session not created");
                return;
            }
            mCallback.onConnected();
        }

        @Override
        public void onSessionReleased(TvInputManager.Session session) {
            if (DEBUG) {
                Log.d(TAG, "onSessionReleased()");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onSessionReleased - session not created");
                return;
            }
            mSessionCallback = null;
            mSession = null;
            mCallback.onDisconnected();
        }

        @Override
        public void onRecordingStarted(TvInputManager.Session session) {
            if (DEBUG) {
                Log.d(TAG, "onRecordingStarted()");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onRecordingStarted - session not created");
                return;
            }
            mCallback.onRecordingStarted();
        }

        @Override
        public void onRecordingStopped(TvInputManager.Session session, Uri recordedProgramUri) {
            if (DEBUG) {
                Log.d(TAG, "onRecordingStopped()");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onRecordingStopped - session not created");
                return;
            }
            mCallback.onRecordingStopped(recordedProgramUri);
        }

        @Override
        public void onError(TvInputManager.Session session, int error) {
            if (DEBUG) {
                Log.d(TAG, "onError()");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onError - session not created");
                return;
            }
            mCallback.onError(error);
        }

        @Override
        public void onSessionEvent(TvInputManager.Session session, String eventType,
                Bundle eventArgs) {
            if (DEBUG) {
                Log.d(TAG, "onSessionEvent(" + eventType + ")");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onSessionEvent - session not created");
                return;
            }
            if (mCallback != null) {
                mCallback.onEvent(mInputId, eventType, eventArgs);
            }
        }
    }
}
