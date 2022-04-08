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

import android.content.Context;
import android.media.AudioManager;
import android.media.AudioSystem;
import android.media.IAudioService;
import android.os.ServiceManager;
import android.util.AndroidException;

import com.android.internal.os.BaseCommand;

/**
 * Command line tool to exercise AudioService.setStreamVolume()
 *                           and AudioService.adjustStreamVolume()
 */
public class VolumeCtrl {

    private static final String TAG = "VolumeCtrl";

    // --stream affects --set, --adj or --get options.
    // --show affects --set and --adj options.
    // --get can be used with --set, --adj or by itself.
    public static final String USAGE = new String("the options are as follows: \n"
            + "\t\t--stream STREAM selects the stream to control, see AudioManager.STREAM_*\n"
            + "\t\t                controls AudioManager.STREAM_MUSIC if no stream is specified\n"
            + "\t\t--set INDEX     sets the volume index value\n"
            + "\t\t--adj DIRECTION adjusts the volume, use raise|same|lower for the direction\n"
            + "\t\t--get           outputs the current volume\n"
            + "\t\t--show          shows the UI during the volume change\n"
            + "\texamples:\n"
            + "\t\tadb shell media volume --show --stream 3 --set 11\n"
            + "\t\tadb shell media volume --stream 0 --adj lower\n"
            + "\t\tadb shell media volume --stream 3 --get\n"
    );

    private static final int VOLUME_CONTROL_MODE_SET = 1;
    private static final int VOLUME_CONTROL_MODE_ADJUST = 2;

    private static final String ADJUST_LOWER = "lower";
    private static final String ADJUST_SAME = "same";
    private static final String ADJUST_RAISE = "raise";

    /**
     * Runs a given MediaShellCommand
     */
    public static void run(MediaShellCommand cmd) throws Exception {
        //----------------------------------------
        // Default parameters
        int stream = AudioManager.STREAM_MUSIC;
        int volIndex = 5;
        int mode = 0;
        int adjDir = AudioManager.ADJUST_RAISE;
        boolean showUi = false;
        boolean doGet = false;

        //----------------------------------------
        // read options
        String option;
        String adjustment = null;
        while ((option = cmd.getNextOption()) != null) {
            switch (option) {
                case "--show":
                    showUi = true;
                    break;
                case "--get":
                    doGet = true;
                    log(LOG_V, "will get volume");
                    break;
                case "--stream":
                    stream = Integer.decode(cmd.getNextArgRequired()).intValue();
                    log(LOG_V, "will control stream=" + stream + " (" + streamName(stream) + ")");
                    break;
                case "--set":
                    volIndex = Integer.decode(cmd.getNextArgRequired()).intValue();
                    mode = VOLUME_CONTROL_MODE_SET;
                    log(LOG_V, "will set volume to index=" + volIndex);
                    break;
                case "--adj":
                    mode = VOLUME_CONTROL_MODE_ADJUST;
                    adjustment = cmd.getNextArgRequired();
                    log(LOG_V, "will adjust volume");
                    break;
                default:
                    throw new IllegalArgumentException("Unknown argument " + option);
            }
        }

        //------------------------------
        // Read options: validation
        if (mode == VOLUME_CONTROL_MODE_ADJUST) {
            if (adjustment == null) {
                cmd.showError("Error: no valid volume adjustment (null)");
                return;
            }
            switch (adjustment) {
                case ADJUST_RAISE: adjDir = AudioManager.ADJUST_RAISE; break;
                case ADJUST_SAME: adjDir = AudioManager.ADJUST_SAME; break;
                case ADJUST_LOWER: adjDir = AudioManager.ADJUST_LOWER; break;
                default:
                    cmd.showError("Error: no valid volume adjustment, was " + adjustment
                            + ", expected " + ADJUST_LOWER + "|" + ADJUST_SAME + "|"
                            + ADJUST_RAISE);
                    return;
            }
        }

        //----------------------------------------
        // Test initialization
        log(LOG_V, "Connecting to AudioService");
        IAudioService audioService = IAudioService.Stub.asInterface(ServiceManager.checkService(
                Context.AUDIO_SERVICE));
        if (audioService == null) {
            System.err.println(BaseCommand.NO_SYSTEM_ERROR_CODE);
            throw new AndroidException(
                    "Can't connect to audio service; is the system running?");
        }

        if (mode == VOLUME_CONTROL_MODE_SET) {
            if ((volIndex > audioService.getStreamMaxVolume(stream))
                    || (volIndex < audioService.getStreamMinVolume(stream))) {
                cmd.showError(String.format("Error: invalid volume index %d for stream %d "
                                + "(should be in [%d..%d])", volIndex, stream,
                        audioService.getStreamMinVolume(stream),
                        audioService.getStreamMaxVolume(stream)));
                return;
            }
        }

        //----------------------------------------
        // Non-interactive test
        final int flag = showUi ? AudioManager.FLAG_SHOW_UI : 0;
        final String pack = cmd.getClass().getPackage().getName();
        if (mode == VOLUME_CONTROL_MODE_SET) {
            audioService.setStreamVolume(stream, volIndex, flag, pack/*callingPackage*/);
        } else if (mode == VOLUME_CONTROL_MODE_ADJUST) {
            audioService.adjustStreamVolume(stream, adjDir, flag, pack);
        }
        if (doGet) {
            log(LOG_V, "volume is " + audioService.getStreamVolume(stream)
                    + " in range [" + audioService.getStreamMinVolume(stream)
                    + ".." + audioService.getStreamMaxVolume(stream) + "]");
        }
    }

    //--------------------------------------------
    // Utilities

    static final String LOG_V = "[v]";
    static final String LOG_W = "[w]";
    static final String LOG_OK = "[ok]";

    static void log(String code, String msg) {
        System.out.println(code + " " + msg);
    }

    static String streamName(int stream) {
        try {
            return AudioSystem.STREAM_NAMES[stream];
        } catch (ArrayIndexOutOfBoundsException e) {
            return "invalid stream";
        }
    }
}
