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

import android.app.PendingIntent;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.IAudioService;
import android.media.IRemoteControlDisplay;
import android.os.Bundle;
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

public class Media extends BaseCommand {

    private IAudioService mAudioService;

    /**
     * Command-line entry point.
     *
     * @param args The command-line arguments
     */
    public static void main(String[] args) {
        (new Media()).run(args);
    }

    public void onShowUsage(PrintStream out) {
        out.println(
                "usage: media [subcommand] [options]\n" +
                "       media dispatch KEY\n" +
                "       media remote-display\n" +
                "\n" +
                "media dispatch: dispatch a media key to the current media client.\n" +
                "                KEY may be: play, pause, play-pause, mute, headsethook,\n" +
                "                stop, next, previous, rewind, recordm fast-forword.\n" +
                "media remote-display: monitor remote display updates.\n"
        );
    }

    public void onRun() throws Exception {
        mAudioService = IAudioService.Stub.asInterface(ServiceManager.checkService(
                Context.AUDIO_SERVICE));
        if (mAudioService == null) {
            System.err.println(NO_SYSTEM_ERROR_CODE);
            throw new AndroidException("Can't connect to audio service; is the system running?");
        }

        String op = nextArgRequired();

        if (op.equals("dispatch")) {
            runDispatch();
        } else if (op.equals("remote-display")) {
            runRemoteDisplay();
        } else {
            showError("Error: unknown command '" + op + "'");
            return;
        }
    }

    private void sendMediaKey(KeyEvent event) {
        try {
            mAudioService.dispatchMediaKeyEvent(event);
        } catch (RemoteException e) {
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

    class RemoteDisplayMonitor extends IRemoteControlDisplay.Stub {
        RemoteDisplayMonitor() {
        }


        @Override
        public void setCurrentClientId(int clientGeneration, PendingIntent clientMediaIntent,
                boolean clearing) {
            System.out.println("New client: id=" + clientGeneration
                    + " intent=" + clientMediaIntent + " clearing=" + clearing);
        }

        @Override
        public void setEnabled(boolean enabled) {
            System.out.println("New enable state= " + (enabled ? "enabled" : "disabled"));
        }

        @Override
        public void setPlaybackState(int generationId, int state, long stateChangeTimeMs,
                long currentPosMs, float speed) {
            System.out.println("New state: id=" + generationId + " state=" + state
                    + " time=" + stateChangeTimeMs + " pos=" + currentPosMs + " speed=" + speed);
        }

        @Override
        public void setTransportControlInfo(int generationId, int transportControlFlags,
                int posCapabilities) {
            System.out.println("New control info: id=" + generationId
                    + " flags=0x" + Integer.toHexString(transportControlFlags)
                    + " cap=0x" + Integer.toHexString(posCapabilities));
        }

        @Override
        public void setMetadata(int generationId, Bundle metadata) {
            System.out.println("New metadata: id=" + generationId
                    + " data=" + metadata);
        }

        @Override
        public void setArtwork(int generationId, Bitmap artwork) {
            System.out.println("New artwork: id=" + generationId
                    + " art=" + artwork);
        }

        @Override
        public void setAllMetadata(int generationId, Bundle metadata, Bitmap artwork) {
            System.out.println("New metadata+artwork: id=" + generationId
                    + " data=" + metadata + " art=" + artwork);
        }

        void printUsageMessage() {
            System.out.println("Monitoring remote control displays...  available commands:");
            System.out.println("(q)uit: finish monitoring");
        }

        void run() throws RemoteException {
            printUsageMessage();

            mAudioService.registerRemoteControlDisplay(this, 0, 0);

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
                mAudioService.unregisterRemoteControlDisplay(this);
            }
        }
    }

    private void runRemoteDisplay() throws Exception {
        RemoteDisplayMonitor monitor = new RemoteDisplayMonitor();
        monitor.run();
    }
}
