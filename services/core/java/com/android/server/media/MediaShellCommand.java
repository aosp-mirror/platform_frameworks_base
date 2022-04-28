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

package com.android.server.media;

import android.app.ActivityThread;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.ISessionManager;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.List;

/**
 * ShellCommand for MediaSessionService.
 */
public class MediaShellCommand extends ShellCommand {
    private static ActivityThread sThread;
    private static MediaSessionManager sMediaSessionManager;

    private final String mPackageName;
    private ISessionManager mSessionService;
    private PrintWriter mWriter;
    private PrintWriter mErrorWriter;
    private InputStream mInput;

    public MediaShellCommand(String packageName) {
        mPackageName = packageName;
    }

    @Override
    public int onCommand(String cmd) {
        mWriter = getOutPrintWriter();
        mErrorWriter = getErrPrintWriter();
        mInput = getRawInputStream();

        if (TextUtils.isEmpty(cmd)) {
            return handleDefaultCommands(cmd);
        }
        if (sThread == null) {
            Looper.prepare();
            sThread = ActivityThread.currentActivityThread();
            Context context = sThread.getSystemContext();
            sMediaSessionManager =
                    (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        }
        mSessionService = ISessionManager.Stub.asInterface(ServiceManager.checkService(
                Context.MEDIA_SESSION_SERVICE));
        if (mSessionService == null) {
            throw new IllegalStateException(
                    "Can't connect to media session service; is the system running?");
        }

        try {
            if (cmd.equals("dispatch")) {
                runDispatch();
            } else if (cmd.equals("list-sessions")) {
                runListSessions();
            } else if (cmd.equals("monitor")) {
                runMonitor();
            } else if (cmd.equals("volume")) {
                runVolume();
            } else {
                showError("Error: unknown command '" + cmd + "'");
                return -1;
            }
        } catch (Exception e) {
            showError(e.toString());
            return -1;
        }
        return 0;
    }

    @Override
    public void onHelp() {
        mWriter.println("usage: media_session [subcommand] [options]");
        mWriter.println("       media_session dispatch KEY");
        mWriter.println("       media_session dispatch KEY");
        mWriter.println("       media_session list-sessions");
        mWriter.println("       media_session monitor <tag>");
        mWriter.println("       media_session volume [options]");
        mWriter.println();
        mWriter.println("media_session dispatch: dispatch a media key to the system.");
        mWriter.println("                KEY may be: play, pause, play-pause, mute, headsethook,");
        mWriter.println("                stop, next, previous, rewind, record, fast-forward.");
        mWriter.println("media_session list-sessions: print a list of the current sessions.");
        mWriter.println("media_session monitor: monitor updates to the specified session.");
        mWriter.println("                       Use the tag from list-sessions.");
        mWriter.println("media_session volume:  " + VolumeCtrl.USAGE);
        mWriter.println();
    }

    private void sendMediaKey(KeyEvent event) {
        try {
            mSessionService.dispatchMediaKeyEvent(
                    mPackageName, /* asSystemService= */ false, event, /* needWakeLock= */ false);
        } catch (RemoteException e) {
        }
    }

    private void runMonitor() throws Exception {
        String id = getNextArgRequired();
        if (id == null) {
            showError("Error: must include a session id");
            return;
        }

        boolean success = false;
        try {
            List<MediaController> controllers = sMediaSessionManager.getActiveSessions(null);
            for (MediaController controller : controllers) {
                try {
                    if (controller != null && id.equals(controller.getTag())) {
                        MediaShellCommand.ControllerMonitor monitor =
                                new MediaShellCommand.ControllerMonitor(controller);
                        monitor.run();
                        success = true;
                        break;
                    }
                } catch (RemoteException e) {
                    // ignore
                }
            }
        } catch (Exception e) {
            mErrorWriter.println("***Error monitoring session*** " + e.getMessage());
        }
        if (!success) {
            mErrorWriter.println("No session found with id " + id);
        }
    }

    private void runDispatch() throws Exception {
        String cmd = getNextArgRequired();
        int keycode;
        if ("play".equals(cmd)) {
            keycode = KeyEvent.KEYCODE_MEDIA_PLAY;
        } else if ("pause".equals(cmd)) {
            keycode = KeyEvent.KEYCODE_MEDIA_PAUSE;
        } else if ("play-pause".equals(cmd)) {
            keycode = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
        } else if ("mute".equals(cmd)) {
            keycode = KeyEvent.KEYCODE_MUTE;
        } else if ("headsethook".equals(cmd)) {
            keycode = KeyEvent.KEYCODE_HEADSETHOOK;
        } else if ("stop".equals(cmd)) {
            keycode = KeyEvent.KEYCODE_MEDIA_STOP;
        } else if ("next".equals(cmd)) {
            keycode = KeyEvent.KEYCODE_MEDIA_NEXT;
        } else if ("previous".equals(cmd)) {
            keycode = KeyEvent.KEYCODE_MEDIA_PREVIOUS;
        } else if ("rewind".equals(cmd)) {
            keycode = KeyEvent.KEYCODE_MEDIA_REWIND;
        } else if ("record".equals(cmd)) {
            keycode = KeyEvent.KEYCODE_MEDIA_RECORD;
        } else if ("fast-forward".equals(cmd)) {
            keycode = KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;
        } else {
            showError("Error: unknown dispatch code '" + cmd + "'");
            return;
        }
        final long now = SystemClock.uptimeMillis();
        sendMediaKey(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keycode, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD));
        sendMediaKey(new KeyEvent(now, now, KeyEvent.ACTION_UP, keycode, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD));
    }

    void log(String code, String msg) {
        mWriter.println(code + " " + msg);
    }

    void showError(String errMsg) {
        onHelp();
        mErrorWriter.println(errMsg);
    }

    class ControllerCallback extends MediaController.Callback {
        @Override
        public void onSessionDestroyed() {
            mWriter.println("onSessionDestroyed. Enter q to quit.");
        }

        @Override
        public void onSessionEvent(String event, Bundle extras) {
            mWriter.println("onSessionEvent event=" + event + ", extras=" + extras);
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            mWriter.println("onPlaybackStateChanged " + state);
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            String mmString = metadata == null ? null : "title=" + metadata
                    .getDescription();
            mWriter.println("onMetadataChanged " + mmString);
        }

        @Override
        public void onQueueChanged(List<MediaSession.QueueItem> queue) {
            mWriter.println("onQueueChanged, "
                    + (queue == null ? "null queue" : " size=" + queue.size()));
        }

        @Override
        public void onQueueTitleChanged(CharSequence title) {
            mWriter.println("onQueueTitleChange " + title);
        }

        @Override
        public void onExtrasChanged(Bundle extras) {
            mWriter.println("onExtrasChanged " + extras);
        }

        @Override
        public void onAudioInfoChanged(MediaController.PlaybackInfo info) {
            mWriter.println("onAudioInfoChanged " + info);
        }
    }

    private class ControllerMonitor {
        private final MediaController mController;
        private final MediaShellCommand.ControllerCallback mControllerCallback;

        ControllerMonitor(MediaController controller) {
            mController = controller;
            mControllerCallback = new MediaShellCommand.ControllerCallback();
        }

        void printUsageMessage() {
            try {
                mWriter.println("V2Monitoring session " + mController.getTag()
                        + "...  available commands: play, pause, next, previous");
            } catch (RuntimeException e) {
                mWriter.println("Error trying to monitor session!");
            }
            mWriter.println("(q)uit: finish monitoring");
        }

        void run() throws RemoteException {
            printUsageMessage();
            HandlerThread cbThread = new HandlerThread("MediaCb") {
                @Override
                protected void onLooperPrepared() {
                    try {
                        mController.registerCallback(mControllerCallback);
                    } catch (RuntimeException e) {
                        mErrorWriter.println("Error registering monitor callback");
                    }
                }
            };
            cbThread.start();

            try {
                InputStreamReader converter = new InputStreamReader(mInput);
                BufferedReader in = new BufferedReader(converter);
                String line;

                while (true) {
                    mWriter.flush();
                    mErrorWriter.flush();
                    if ((line = in.readLine()) == null) break;
                    boolean addNewline = true;
                    if (line.length() <= 0) {
                        addNewline = false;
                    } else if ("q".equals(line) || "quit".equals(line)) {
                        break;
                    } else if ("play".equals(line)) {
                        dispatchKeyCode(KeyEvent.KEYCODE_MEDIA_PLAY);
                    } else if ("pause".equals(line)) {
                        dispatchKeyCode(KeyEvent.KEYCODE_MEDIA_PAUSE);
                    } else if ("next".equals(line)) {
                        dispatchKeyCode(KeyEvent.KEYCODE_MEDIA_NEXT);
                    } else if ("previous".equals(line)) {
                        dispatchKeyCode(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                    } else {
                        mErrorWriter.println("Invalid command: " + line);
                    }

                    synchronized (this) {
                        if (addNewline) {
                            mWriter.println("");
                        }
                        printUsageMessage();
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                cbThread.getLooper().quit();
                try {
                    mController.unregisterCallback(mControllerCallback);
                } catch (Exception e) {
                    // ignoring
                }
            }
        }

        private void dispatchKeyCode(int keyCode) {
            final long now = SystemClock.uptimeMillis();
            KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD);
            KeyEvent up = new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD);
            try {
                mController.dispatchMediaButtonEvent(down);
                mController.dispatchMediaButtonEvent(up);
            } catch (RuntimeException e) {
                mErrorWriter.println("Failed to dispatch " + keyCode);
            }
        }
    }

    private void runListSessions() {
        mWriter.println("Sessions:");
        try {
            List<MediaController> controllers = sMediaSessionManager.getActiveSessions(null);
            for (MediaController controller : controllers) {
                if (controller != null) {
                    try {
                        mWriter.println("  tag=" + controller.getTag()
                                + ", package=" + controller.getPackageName());
                    } catch (RuntimeException e) {
                        // ignore
                    }
                }
            }
        } catch (Exception e) {
            mErrorWriter.println("***Error listing sessions***");
        }
    }

    //=================================
    // "volume" command for stream volume control
    private void runVolume() throws Exception {
        VolumeCtrl.run(this);
    }
}
