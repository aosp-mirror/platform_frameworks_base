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

import static com.android.wm.shell.common.split.SplitScreenConstants.SPLIT_POSITION_BOTTOM_OR_RIGHT;

import com.android.wm.shell.apppairs.AppPairsController;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.hidedisplaycutout.HideDisplayCutoutController;
import com.android.wm.shell.kidsmode.KidsModeTaskOrganizer;
import com.android.wm.shell.legacysplitscreen.LegacySplitScreenController;
import com.android.wm.shell.onehanded.OneHandedController;
import com.android.wm.shell.pip.Pip;
import com.android.wm.shell.recents.RecentTasksController;
import com.android.wm.shell.splitscreen.SplitScreenController;

import java.io.PrintWriter;
import java.util.Optional;

/**
 * An entry point into the shell for dumping shell internal state and running adb commands.
 *
 * Use with {@code adb shell dumpsys activity service SystemUIService WMShell ...}.
 */
public final class ShellCommandHandlerImpl {
    private static final String TAG = ShellCommandHandlerImpl.class.getSimpleName();

    private final Optional<LegacySplitScreenController> mLegacySplitScreenOptional;
    private final Optional<SplitScreenController> mSplitScreenOptional;
    private final Optional<Pip> mPipOptional;
    private final Optional<OneHandedController> mOneHandedOptional;
    private final Optional<HideDisplayCutoutController> mHideDisplayCutout;
    private final Optional<AppPairsController> mAppPairsOptional;
    private final Optional<RecentTasksController> mRecentTasks;
    private final ShellTaskOrganizer mShellTaskOrganizer;
    private final KidsModeTaskOrganizer mKidsModeTaskOrganizer;
    private final ShellExecutor mMainExecutor;
    private final HandlerImpl mImpl = new HandlerImpl();

    public ShellCommandHandlerImpl(
            ShellTaskOrganizer shellTaskOrganizer,
            KidsModeTaskOrganizer kidsModeTaskOrganizer,
            Optional<LegacySplitScreenController> legacySplitScreenOptional,
            Optional<SplitScreenController> splitScreenOptional,
            Optional<Pip> pipOptional,
            Optional<OneHandedController> oneHandedOptional,
            Optional<HideDisplayCutoutController> hideDisplayCutout,
            Optional<AppPairsController> appPairsOptional,
            Optional<RecentTasksController> recentTasks,
            ShellExecutor mainExecutor) {
        mShellTaskOrganizer = shellTaskOrganizer;
        mKidsModeTaskOrganizer = kidsModeTaskOrganizer;
        mRecentTasks = recentTasks;
        mLegacySplitScreenOptional = legacySplitScreenOptional;
        mSplitScreenOptional = splitScreenOptional;
        mPipOptional = pipOptional;
        mOneHandedOptional = oneHandedOptional;
        mHideDisplayCutout = hideDisplayCutout;
        mAppPairsOptional = appPairsOptional;
        mMainExecutor = mainExecutor;
    }

    public ShellCommandHandler asShellCommandHandler() {
        return mImpl;
    }

    /** Dumps WM Shell internal state. */
    private void dump(PrintWriter pw) {
        mShellTaskOrganizer.dump(pw, "");
        pw.println();
        pw.println();
        mPipOptional.ifPresent(pip -> pip.dump(pw));
        mLegacySplitScreenOptional.ifPresent(splitScreen -> splitScreen.dump(pw));
        mOneHandedOptional.ifPresent(oneHanded -> oneHanded.dump(pw));
        mHideDisplayCutout.ifPresent(hideDisplayCutout -> hideDisplayCutout.dump(pw));
        pw.println();
        pw.println();
        mAppPairsOptional.ifPresent(appPairs -> appPairs.dump(pw, ""));
        pw.println();
        pw.println();
        mSplitScreenOptional.ifPresent(splitScreen -> splitScreen.dump(pw, ""));
        pw.println();
        pw.println();
        mRecentTasks.ifPresent(recentTasks -> recentTasks.dump(pw, ""));
        pw.println();
        pw.println();
        mKidsModeTaskOrganizer.dump(pw, "");
    }


    /** Returns {@code true} if command was found and executed. */
    private boolean handleCommand(final String[] args, PrintWriter pw) {
        if (args.length < 2) {
            // Argument at position 0 is "WMShell".
            return false;
        }
        switch (args[1]) {
            case "pair":
                return runPair(args, pw);
            case "unpair":
                return runUnpair(args, pw);
            case "moveToSideStage":
                return runMoveToSideStage(args, pw);
            case "removeFromSideStage":
                return runRemoveFromSideStage(args, pw);
            case "setSideStagePosition":
                return runSetSideStagePosition(args, pw);
            case "help":
                return runHelp(pw);
            default:
                return false;
        }
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

    private boolean runMoveToSideStage(String[] args, PrintWriter pw) {
        if (args.length < 3) {
            // First arguments are "WMShell" and command name.
            pw.println("Error: task id should be provided as arguments");
            return false;
        }
        final int taskId = new Integer(args[2]);
        final int sideStagePosition = args.length > 3
                ? new Integer(args[3]) : SPLIT_POSITION_BOTTOM_OR_RIGHT;
        mSplitScreenOptional.ifPresent(split -> split.moveToSideStage(taskId, sideStagePosition));
        return true;
    }

    private boolean runRemoveFromSideStage(String[] args, PrintWriter pw) {
        if (args.length < 3) {
            // First arguments are "WMShell" and command name.
            pw.println("Error: task id should be provided as arguments");
            return false;
        }
        final int taskId = new Integer(args[2]);
        mSplitScreenOptional.ifPresent(split -> split.removeFromSideStage(taskId));
        return true;
    }

    private boolean runSetSideStagePosition(String[] args, PrintWriter pw) {
        if (args.length < 3) {
            // First arguments are "WMShell" and command name.
            pw.println("Error: side stage position should be provided as arguments");
            return false;
        }
        final int position = new Integer(args[2]);
        mSplitScreenOptional.ifPresent(split -> split.setSideStagePosition(position));
        return true;
    }

    private boolean runHelp(PrintWriter pw) {
        pw.println("Window Manager Shell commands:");
        pw.println("  help");
        pw.println("      Print this help text.");
        pw.println("  <no arguments provided>");
        pw.println("    Dump Window Manager Shell internal state");
        pw.println("  pair <taskId1> <taskId2>");
        pw.println("  unpair <taskId>");
        pw.println("    Pairs/unpairs tasks with given ids.");
        pw.println("  moveToSideStage <taskId> <SideStagePosition>");
        pw.println("    Move a task with given id in split-screen mode.");
        pw.println("  removeFromSideStage <taskId>");
        pw.println("    Remove a task with given id in split-screen mode.");
        pw.println("  setSideStageOutline <true/false>");
        pw.println("    Enable/Disable outline on the side-stage.");
        pw.println("  setSideStagePosition <SideStagePosition>");
        pw.println("    Sets the position of the side-stage.");
        return true;
    }

    private class HandlerImpl implements ShellCommandHandler {
        @Override
        public void dump(PrintWriter pw) {
            try {
                mMainExecutor.executeBlocking(() -> ShellCommandHandlerImpl.this.dump(pw));
            } catch (InterruptedException e) {
                throw new RuntimeException("Failed to dump the Shell in 2s", e);
            }
        }

        @Override
        public boolean handleCommand(String[] args, PrintWriter pw) {
            try {
                boolean[] result = new boolean[1];
                mMainExecutor.executeBlocking(() -> {
                    result[0] = ShellCommandHandlerImpl.this.handleCommand(args, pw);
                });
                return result[0];
            } catch (InterruptedException e) {
                throw new RuntimeException("Failed to handle Shell command in 2s", e);
            }
        }
    }
}
