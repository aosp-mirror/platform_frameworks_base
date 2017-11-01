/*
**
** Copyright 2013, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package com.android.commands.media;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.media.MediaMetadata;
import android.media.session.ISessionController;
import android.media.session.ISessionControllerCallback;
import android.media.session.ISessionManager;
import android.media.session.ParcelableVolumeInfo;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.AndroidException;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import com.android.internal.os.BaseCommand;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.List;

public class Media extends BaseCommand {
    private ISessionManager mSessionService;

    /**
     * Command-line entry point.
     *
     * @param args The command-line arguments
     */
    public static void main(String[] args) {
        (new Media()).run(args);
    }

    @Override
    public void onShowUsage(PrintStream out) {
        out.println(
                "usage: media [subcommand] [options]\n" +
                "       media dispatch KEY\n" +
                "       media list-sessions\n" +
                "       media monitor <tag>\n" +
                "       media volume [options]\n" +
                "\n" +
                "media dispatch: dispatch a media key to the system.\n" +
                "                KEY may be: play, pause, play-pause, mute, headsethook,\n" +
                "                stop, next, previous, rewind, record, fast-forword.\n" +
                "media list-sessions: print a list of the current sessions.\n" +
                        "media monitor: monitor updates to the specified session.\n" +
                "                       Use the tag from list-sessions.\n" +
                "media volume:  " + VolumeCtrl.USAGE
        );
    }

    @Override
    public void onRun() throws Exception {
        mSessionService = ISessionManager.Stub.asInterface(ServiceManager.checkService(
                Context.MEDIA_SESSION_SERVICE));
        if (mSessionService == null) {
            System.err.println(NO_SYSTEM_ERROR_CODE);
            throw new AndroidException(
                    "Can't connect to media session service; is the system running?");
        }

        String op = nextArgRequired();

        if (op.equals("dispatch")) {
            runDispatch();
        } else if (op.equals("list-sessions")) {
            runListSessions();
        } else if (op.equals("monitor")) {
            runMonitor();
        } else if (op.equals("volume")) {
            runVolume();
        } else {
            showError("Error: unknown command '" + op + "'");
            return;
        }
    }

    private void sendMediaKey(KeyEvent event) {
        try {
            mSessionService.dispatchMediaKeyEvent(event, false);
        } catch (RemoteException e) {
        }
    }

    private void runMonitor() throws Exception {
        String id = nextArgRequired();
        if (id == null) {
            showError("Error: must include a session id");
            return;
        }
        boolean success = false;
        try {
            List<IBinder> sessions = mSessionService
                    .getSessions(null, ActivityManager.getCurrentUser());
            for (IBinder session : sessions) {
                ISessionController controller = ISessionController.Stub.asInterface(session);
                try {
                    if (controller != null && id.equals(controller.getTag())) {
                        ControllerMonitor monitor = new ControllerMonitor(controller);
                        monitor.run();
                        success = true;
                        break;
                    }
                } catch (RemoteException e) {
                    // ignore
                }
            }
        } catch (Exception e) {
            System.out.println("***Error monitoring session*** " + e.getMessage());
        }
        if (!success) {
            System.out.println("No session found with id " + id);
        }
    }

    private void runDispatch() throws Exception {
        String cmd = nextArgRequired();
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

    class ControllerMonitor extends ISessionControllerCallback.Stub {
        private final ISessionController mController;

        public ControllerMonitor(ISessionController controller) {
            mController = controller;
        }

        @Override
        public void onSessionDestroyed() {
            System.out.println("onSessionDestroyed. Enter q to quit.");

        }

        @Override
        public void onEvent(String event, Bundle extras) {
            System.out.println("onSessionEvent event=" + event + ", extras=" + extras);
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            System.out.println("onPlaybackStateChanged " + state);
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            String mmString = metadata == null ? null : "title=" + metadata
                    .getDescription();
            System.out.println("onMetadataChanged " + mmString);
        }

        @Override
        public void onQueueChanged(ParceledListSlice queue) throws RemoteException {
            System.out.println("onQueueChanged, "
                    + (queue == null ? "null queue" : " size=" + queue.getList().size()));
        }

        @Override
        public void onQueueTitleChanged(CharSequence title) throws RemoteException {
            System.out.println("onQueueTitleChange " + title);
        }

        @Override
        public void onExtrasChanged(Bundle extras) throws RemoteException {
            System.out.println("onExtrasChanged " + extras);
        }

        @Override
        public void onVolumeInfoChanged(ParcelableVolumeInfo info) throws RemoteException {
            System.out.println("onVolumeInfoChanged " + info);
        }

        void printUsageMessage() {
            try {
                System.out.println("V2Monitoring session " + mController.getTag()
                        + "...  available commands: play, pause, next, previous");
            } catch (RemoteException e) {
                System.out.println("Error trying to monitor session!");
            }
            System.out.println("(q)uit: finish monitoring");
        }

        void run() throws RemoteException {
            printUsageMessage();
            HandlerThread cbThread = new HandlerThread("MediaCb") {
                @Override
                protected void onLooperPrepared() {
                    try {
                        mController.registerCallbackListener(ControllerMonitor.this);
                    } catch (RemoteException e) {
                        System.out.println("Error registering monitor callback");
                    }
                }
            };
            cbThread.start();

            try {
                InputStreamReader converter = new InputStreamReader(System.in);
                BufferedReader in = new BufferedReader(converter);
                String line;

                while ((line = in.readLine()) != null) {
                    boolean addNewline = true;
                    if (line.length() <= 0) {
                        addNewline = false;
                    } else if ("q".equals(line) || "quit".equals(line)) {
                        break;
                    } else if ("play".equals(line)) {
                        mController.play();
                    } else if ("pause".equals(line)) {
                        mController.pause();
                    } else if ("next".equals(line)) {
                        mController.next();
                    } else if ("previous".equals(line)) {
                        mController.previous();
                    } else {
                        System.out.println("Invalid command: " + line);
                    }

                    synchronized (this) {
                        if (addNewline) {
                            System.out.println("");
                        }
                        printUsageMessage();
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                cbThread.getLooper().quit();
                try {
                    mController.unregisterCallbackListener(this);
                } catch (Exception e) {
                    // ignoring
                }
            }
        }
    }

    private void runListSessions() {
        System.out.println("Sessions:");
        try {
            List<IBinder> sessions = mSessionService
                    .getSessions(null, ActivityManager.getCurrentUser());
            for (IBinder session : sessions) {

                ISessionController controller = ISessionController.Stub.asInterface(session);
                if (controller != null) {
                    try {
                        System.out.println("  tag=" + controller.getTag()
                                + ", package=" + controller.getPackageName());
                    } catch (RemoteException e) {
                        // ignore
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("***Error listing sessions***");
        }
    }

    //=================================
    // "volume" command for stream volume control
    private void runVolume() throws Exception {
        VolumeCtrl.run(this);
    }
}
