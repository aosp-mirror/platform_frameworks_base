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

package com.android.wm.shell;

import android.view.Gravity;

import com.android.wm.shell.apppairs.AppPairs;
import com.android.wm.shell.common.annotations.ExternalThread;
import com.android.wm.shell.hidedisplaycutout.HideDisplayCutout;
import com.android.wm.shell.letterbox.LetterboxConfigController;
import com.android.wm.shell.onehanded.OneHanded;
import com.android.wm.shell.pip.Pip;
import com.android.wm.shell.splitscreen.SplitScreen;

import java.io.PrintWriter;
import java.util.Optional;

/**
 * An entry point into the shell for dumping shell internal state and running adb commands.
 *
 * Use with {@code adb shell dumpsys activity service SystemUIService WMShell ...}.
 */
public final class ShellCommandHandler {

    private final Optional<SplitScreen> mSplitScreenOptional;
    private final Optional<Pip> mPipOptional;
    private final Optional<OneHanded> mOneHandedOptional;
    private final Optional<HideDisplayCutout> mHideDisplayCutout;
    private final ShellTaskOrganizer mShellTaskOrganizer;
    private final Optional<AppPairs> mAppPairsOptional;
    private final LetterboxConfigController mLetterboxConfigController;

    public ShellCommandHandler(
            ShellTaskOrganizer shellTaskOrganizer,
            Optional<SplitScreen> splitScreenOptional,
            Optional<Pip> pipOptional,
            Optional<OneHanded> oneHandedOptional,
            Optional<HideDisplayCutout> hideDisplayCutout,
            Optional<AppPairs> appPairsOptional,
            LetterboxConfigController letterboxConfigController) {
        mShellTaskOrganizer = shellTaskOrganizer;
        mSplitScreenOptional = splitScreenOptional;
        mPipOptional = pipOptional;
        mOneHandedOptional = oneHandedOptional;
        mHideDisplayCutout = hideDisplayCutout;
        mAppPairsOptional = appPairsOptional;
        mLetterboxConfigController = letterboxConfigController;
    }

    /** Dumps WM Shell internal state. */
    @ExternalThread
    public void dump(PrintWriter pw) {
        mShellTaskOrganizer.dump(pw, "");
        pw.println();
        pw.println();
        mPipOptional.ifPresent(pip -> pip.dump(pw));
        mSplitScreenOptional.ifPresent(splitScreen -> splitScreen.dump(pw));
        mOneHandedOptional.ifPresent(oneHanded -> oneHanded.dump(pw));
        mHideDisplayCutout.ifPresent(hideDisplayCutout -> hideDisplayCutout.dump(pw));
        pw.println();
        pw.println();
        mAppPairsOptional.ifPresent(appPairs -> appPairs.dump(pw, ""));
    }


    /** Returns {@code true} if command was found and executed. */
    @ExternalThread
    public boolean handleCommand(String[] args, PrintWriter pw) {
        if (args.length < 2) {
            // Argument at position 0 is "WMShell".
            return false;
        }
        switch (args[1]) {
            case "set-letterbox-portrait-gravity":
                return runSetLetterboxPortraitGravity(args, pw);
            case "get-letterbox-portrait-gravity":
                return runGetLetterboxPortraitGravity(pw);
            case "set-letterbox-landscape-gravity":
                return runSetLetterboxLandscapeGravity(args, pw);
            case "get-letterbox-landscape-gravity":
                return runGetLetterboxLandscapeGravity(pw);
            case "pair":
                return runPair(args, pw);
            case "unpair":
                return runUnpair(args, pw);
            case "help":
                return runHelp(pw);
            default:
                return false;
        }
    }

    private boolean runSetLetterboxPortraitGravity(String[] args, PrintWriter pw) {
        if (args.length < 3) {
            // First two arguments are "WMShell" and command name.
            pw.println("Error: reset, TOP, CENTER or BOTTOM should be provided as an argument");
            return true;
        }
        switch (args[2]) {
            case "reset":
                mLetterboxConfigController.resetPortraitGravity();
                break;
            case "TOP":
                mLetterboxConfigController.setPortraitGravity(Gravity.TOP);
                break;
            case "CENTER":
                mLetterboxConfigController.setPortraitGravity(Gravity.CENTER);
                break;
            case "BOTTOM":
                mLetterboxConfigController.setPortraitGravity(Gravity.BOTTOM);
                break;
            default:
                pw.println("Error: expected reset, TOP, CENTER or BOTTOM but got " + args[2]);
        }
        return true;
    }

    private boolean runGetLetterboxPortraitGravity(PrintWriter pw) {
        final int gravity = mLetterboxConfigController.getPortraitGravity();
        switch (gravity) {
            case Gravity.TOP:
                pw.println("TOP");
                break;
            case Gravity.CENTER:
                pw.println("CENTER");
                break;
            case Gravity.BOTTOM:
                pw.println("BOTTOM");
                break;
            default:
                throw new AssertionError("Unexpected gravity: " + gravity);
        }
        return true;
    }

    private boolean runSetLetterboxLandscapeGravity(String[] args, PrintWriter pw) {
        if (args.length < 3) {
            // First two arguments are "WMShell" and command name.
            pw.println("Error: reset, LEFT, CENTER or RIGHT should be provided as an argument");
            return false;
        }
        switch (args[2]) {
            case "reset":
                mLetterboxConfigController.resetLandscapeGravity();
                break;
            case "LEFT":
                mLetterboxConfigController.setLandscapeGravity(Gravity.LEFT);
                break;
            case "CENTER":
                mLetterboxConfigController.setLandscapeGravity(Gravity.CENTER);
                break;
            case "RIGHT":
                mLetterboxConfigController.setLandscapeGravity(Gravity.RIGHT);
                break;
            default:
                pw.println(
                        "Error: expected reset, LEFT, CENTER or RIGHT but got " + args[2]);
        }
        return true;
    }

    private boolean runGetLetterboxLandscapeGravity(PrintWriter pw) {
        final int gravity = mLetterboxConfigController.getLandscapeGravity();
        switch (gravity) {
            case Gravity.LEFT:
                pw.println("LEFT");
                break;
            case Gravity.CENTER:
                pw.println("CENTER");
                break;
            case Gravity.RIGHT:
                pw.println("RIGHT");
                break;
            default:
                throw new AssertionError("Unexpected gravity: " + gravity);
        }
        return true;
    }

    private boolean runPair(String[] args, PrintWriter pw) {
        if (args.length < 4) {
            // First two arguments are "WMShell" and command name.
            pw.println("Error: two task ids should be provided as arguments");
            return false;
        }
        final int taskId1 = new Integer(args[2]);
        final int taskId2 = new Integer(args[3]);
        mAppPairsOptional.ifPresent(appPairs -> appPairs.pair(taskId1, taskId2));
        return true;
    }

    private boolean runUnpair(String[] args, PrintWriter pw) {
        if (args.length < 3) {
            // First two arguments are "WMShell" and command name.
            pw.println("Error: task id should be provided as an argument");
            return false;
        }
        final int taskId = new Integer(args[2]);
        mAppPairsOptional.ifPresent(appPairs -> appPairs.unpair(taskId));
        return true;
    }

    private boolean runHelp(PrintWriter pw) {
        pw.println("Window Manager Shell commands:");
        pw.println("  help");
        pw.println("      Print this help text.");
        pw.println("  <no arguments provided>");
        pw.println("    Dump Window Manager Shell internal state");
        pw.println("  set-letterbox-portrait-gravity [reset|TOP|CENTER|BOTTOM]");
        pw.println("  get-letterbox-portrait-gravity");
        pw.println("    Set, reset or print letterbox gravity for portrait screen mode.");
        pw.println("  set-letterbox-landscape-gravity [reset|LEFT|CENTER|RIGHT]");
        pw.println("  get-letterbox-landscape-gravity");
        pw.println("    Set, reset or print letterbox gravity for landscape screen mode.");
        pw.println("  pair <taskId1> <taskId2>");
        pw.println("  unpair <taskId>");
        pw.println("    Pairs/unpairs tasks with given ids.");
        return true;
    }
}
