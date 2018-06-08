/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.internal.policy;

import android.app.KeyguardManager;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.media.session.MediaSessionManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.FallbackEventHandler;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
import com.android.internal.policy.PhoneWindow;

/**
 * @hide
 */
public class PhoneFallbackEventHandler implements FallbackEventHandler {
    private static String TAG = "PhoneFallbackEventHandler";
    private static final boolean DEBUG = false;

    Context mContext;
    View mView;

    AudioManager mAudioManager;
    KeyguardManager mKeyguardManager;
    SearchManager mSearchManager;
    TelephonyManager mTelephonyManager;
    MediaSessionManager mMediaSessionManager;

    public PhoneFallbackEventHandler(Context context) {
        mContext = context;
    }

    public void setView(View v) {
        mView = v;
    }

    public void preDispatchKeyEvent(KeyEvent event) {
        getAudioManager().preDispatchKeyEvent(event, AudioManager.USE_DEFAULT_STREAM_TYPE);
    }

    public boolean dispatchKeyEvent(KeyEvent event) {

        final int action = event.getAction();
        final int keyCode = event.getKeyCode();

        if (action == KeyEvent.ACTION_DOWN) {
            return onKeyDown(keyCode, event);
        } else {
            return onKeyUp(keyCode, event);
        }
    }

    boolean onKeyDown(int keyCode, KeyEvent event) {
        /* ****************************************************************************
         * HOW TO DECIDE WHERE YOUR KEY HANDLING GOES.
         * See the comment in PhoneWindow.onKeyDown
         * ****************************************************************************/
        final KeyEvent.DispatcherState dispatcher = mView.getKeyDispatcherState();

        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE: {
                handleVolumeKeyEvent(event);
                return true;
            }


            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                /* Suppress PLAY/PAUSE toggle when phone is ringing or in-call
                 * to avoid music playback */
                if (getTelephonyManager().getCallState() != TelephonyManager.CALL_STATE_IDLE) {
                    return true;  // suppress key event
                }
            case KeyEvent.KEYCODE_MUTE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_MEDIA_STOP:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_RECORD:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK: {
                handleMediaKeyEvent(event);
                return true;
            }

            case KeyEvent.KEYCODE_CALL: {
                if (isNotInstantAppAndKeyguardRestricted(dispatcher)) {
                    break;
                }
                if (event.getRepeatCount() == 0) {
                    dispatcher.startTracking(event, this);
                } else if (event.isLongPress() && dispatcher.isTracking(event)) {
                    dispatcher.performedLongPress(event);
                    if (isUserSetupComplete()) {
                        mView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        // launch the VoiceDialer
                        Intent intent = new Intent(Intent.ACTION_VOICE_COMMAND);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try {
                            sendCloseSystemWindows();
                            mContext.startActivity(intent);
                        } catch (ActivityNotFoundException e) {
                            startCallActivity();
                        }
                    } else {
                        Log.i(TAG, "Not starting call activity because user "
                                + "setup is in progress.");
                    }
                }
                return true;
            }

            case KeyEvent.KEYCODE_CAMERA: {
                if (isNotInstantAppAndKeyguardRestricted(dispatcher)) {
                    break;
                }
                if (event.getRepeatCount() == 0) {
                    dispatcher.startTracking(event, this);
                } else if (event.isLongPress() && dispatcher.isTracking(event)) {
                    dispatcher.performedLongPress(event);
                    if (isUserSetupComplete()) {
                        mView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        sendCloseSystemWindows();
                        // Broadcast an intent that the Camera button was longpressed
                        Intent intent = new Intent(Intent.ACTION_CAMERA_BUTTON, null);
                        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                        intent.putExtra(Intent.EXTRA_KEY_EVENT, event);
                        mContext.sendOrderedBroadcastAsUser(intent, UserHandle.CURRENT_OR_SELF,
                                null, null, null, 0, null, null);
                    } else {
                        Log.i(TAG, "Not dispatching CAMERA long press because user "
                                + "setup is in progress.");
                    }
                }
                return true;
            }

            case KeyEvent.KEYCODE_SEARCH: {
                if (isNotInstantAppAndKeyguardRestricted(dispatcher)) {
                    break;
                }
                if (event.getRepeatCount() == 0) {
                    dispatcher.startTracking(event, this);
                } else if (event.isLongPress() && dispatcher.isTracking(event)) {
                    Configuration config = mContext.getResources().getConfiguration();
                    if (config.keyboard == Configuration.KEYBOARD_NOKEYS
                            || config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_YES) {
                        if (isUserSetupComplete()) {
                            // launch the search activity
                            Intent intent = new Intent(Intent.ACTION_SEARCH_LONG_PRESS);
                            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            try {
                                mView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                                sendCloseSystemWindows();
                                getSearchManager().stopSearch();
                                mContext.startActivity(intent);
                                // Only clear this if we successfully start the
                                // activity; otherwise we will allow the normal short
                                // press action to be performed.
                                dispatcher.performedLongPress(event);
                                return true;
                            } catch (ActivityNotFoundException e) {
                                // Ignore
                            }
                        } else {
                            Log.i(TAG, "Not dispatching SEARCH long press because user "
                                    + "setup is in progress.");
                        }
                    }
                }
                break;
            }
        }
        return false;
    }

    private boolean isNotInstantAppAndKeyguardRestricted(KeyEvent.DispatcherState dispatcher) {
        return !mContext.getPackageManager().isInstantApp()
                && (getKeyguardManager().inKeyguardRestrictedInputMode() || dispatcher == null);
    }

    boolean onKeyUp(int keyCode, KeyEvent event) {
        if (DEBUG) {
            Log.d(TAG, "up " + keyCode);
        }
        final KeyEvent.DispatcherState dispatcher = mView.getKeyDispatcherState();
        if (dispatcher != null) {
            dispatcher.handleUpEvent(event);
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE: {
                if (!event.isCanceled()) {
                    handleVolumeKeyEvent(event);
                }
                return true;
            }

            case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_MUTE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_RECORD:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK: {
                handleMediaKeyEvent(event);
                return true;
            }

            case KeyEvent.KEYCODE_CAMERA: {
                if (isNotInstantAppAndKeyguardRestricted(dispatcher)) {
                    break;
                }
                if (event.isTracking() && !event.isCanceled()) {
                    // Add short press behavior here if desired
                }
                return true;
            }

            case KeyEvent.KEYCODE_CALL: {
                if (isNotInstantAppAndKeyguardRestricted(dispatcher)) {
                    break;
                }
                if (event.isTracking() && !event.isCanceled()) {
                    if (isUserSetupComplete()) {
                        startCallActivity();
                    } else {
                        Log.i(TAG, "Not starting call activity because user "
                                + "setup is in progress.");
                    }
                }
                return true;
            }
        }
        return false;
    }

    void startCallActivity() {
        sendCloseSystemWindows();
        Intent intent = new Intent(Intent.ACTION_CALL_BUTTON);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            mContext.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "No activity found for android.intent.action.CALL_BUTTON.");
        }
    }

    SearchManager getSearchManager() {
        if (mSearchManager == null) {
            mSearchManager = (SearchManager) mContext.getSystemService(Context.SEARCH_SERVICE);
        }
        return mSearchManager;
    }

    TelephonyManager getTelephonyManager() {
        if (mTelephonyManager == null) {
            mTelephonyManager = (TelephonyManager)mContext.getSystemService(
                    Context.TELEPHONY_SERVICE);
        }
        return mTelephonyManager;
    }

    KeyguardManager getKeyguardManager() {
        if (mKeyguardManager == null) {
            mKeyguardManager = (KeyguardManager)mContext.getSystemService(Context.KEYGUARD_SERVICE);
        }
        return mKeyguardManager;
    }

    AudioManager getAudioManager() {
        if (mAudioManager == null) {
            mAudioManager = (AudioManager)mContext.getSystemService(Context.AUDIO_SERVICE);
        }
        return mAudioManager;
    }

    MediaSessionManager getMediaSessionManager() {
        if (mMediaSessionManager == null) {
            mMediaSessionManager =
                    (MediaSessionManager) mContext.getSystemService(Context.MEDIA_SESSION_SERVICE);
        }
        return mMediaSessionManager;
    }

    void sendCloseSystemWindows() {
        PhoneWindow.sendCloseSystemWindows(mContext, null);
    }

    private void handleVolumeKeyEvent(KeyEvent keyEvent) {
        getMediaSessionManager().dispatchVolumeKeyEventAsSystemService(keyEvent,
                AudioManager.USE_DEFAULT_STREAM_TYPE);
    }

    private void handleMediaKeyEvent(KeyEvent keyEvent) {
        getMediaSessionManager().dispatchMediaKeyEventAsSystemService(keyEvent);
    }

    private boolean isUserSetupComplete() {
        return Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.USER_SETUP_COMPLETE, 0) != 0;
    }
}

