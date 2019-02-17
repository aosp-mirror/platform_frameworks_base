/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.wm;

import static android.os.Build.IS_USER;

import android.graphics.Point;
import android.graphics.Rect;
import android.os.RemoteException;
import android.os.ShellCommand;
import android.os.UserHandle;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.IWindowManager;
import android.view.Surface;

import java.io.PrintWriter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ShellCommands for WindowManagerService.
 *
 * Use with {@code adb shell cmd window ...}.
 */
public class WindowManagerShellCommand extends ShellCommand {

    // IPC interface to activity manager -- don't need to do additional security checks.
    private final IWindowManager mInterface;

    // Internal service impl -- must perform security checks before touching.
    private final WindowManagerService mInternal;

    public WindowManagerShellCommand(WindowManagerService service) {
        mInterface = service;
        mInternal = service;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }
        final PrintWriter pw = getOutPrintWriter();
        try {
            switch (cmd) {
                case "size":
                    return runDisplaySize(pw);
                case "density":
                    return runDisplayDensity(pw);
                case "folded-area":
                    return runDisplayFoldedArea(pw);
                case "overscan":
                    return runDisplayOverscan(pw);
                case "scaling":
                    return runDisplayScaling(pw);
                case "dismiss-keyguard":
                    return runDismissKeyguard(pw);
                case "tracing":
                    // XXX this should probably be changed to use openFileForSystem() to create
                    // the output trace file, so the shell gets the correct semantics for where
                    // trace files can be written.
                    return mInternal.mWindowTracing.onShellCommand(this);
                case "set-user-rotation":
                    return runSetDisplayUserRotation(pw);
                case "set-fix-to-user-rotation":
                    return runSetFixToUserRotation(pw);
                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (RemoteException e) {
            pw.println("Remote exception: " + e);
        }
        return -1;
    }

    private int getDisplayId(String opt) {
        int displayId = Display.DEFAULT_DISPLAY;
        String option = "-d".equals(opt) ? opt : getNextOption();
        if (option != null && "-d".equals(option)) {
            try {
                displayId = Integer.parseInt(getNextArgRequired());
            } catch (NumberFormatException e) {
                getErrPrintWriter().println("Error: bad number " + e);
            } catch (IllegalArgumentException e) {
                getErrPrintWriter().println("Error: " + e);
            }
        }
        return displayId;
    }

    private void printInitialDisplaySize(PrintWriter pw , int displayId) {
        final Point initialSize = new Point();
        final Point baseSize = new Point();

        try {
            mInterface.getInitialDisplaySize(displayId, initialSize);
            mInterface.getBaseDisplaySize(displayId, baseSize);
            pw.println("Physical size: " + initialSize.x + "x" + initialSize.y);
            if (!initialSize.equals(baseSize)) {
                pw.println("Override size: " + baseSize.x + "x" + baseSize.y);
            }
        } catch (RemoteException e) {
            // Can't call getInitialDisplaySize() on IWindowManager or
            // Can't call getBaseDisplaySize() on IWindowManager
            pw.println("Remote exception: " + e);
        }
    }

    private int runDisplaySize(PrintWriter pw) throws RemoteException {
        String size = getNextArg();
        int w, h;
        final int displayId = getDisplayId(size);
        if (size == null) {
            printInitialDisplaySize(pw, displayId);
            return 0;
        } else if ("-d".equals(size)) {
            printInitialDisplaySize(pw, displayId);
            return 0;
        } else if ("reset".equals(size)) {
            w = h = -1;
        } else {
            int div = size.indexOf('x');
            if (div <= 0 || div >= (size.length()-1)) {
                getErrPrintWriter().println("Error: bad size " + size);
                return -1;
            }
            String wstr = size.substring(0, div);
            String hstr = size.substring(div+1);
            try {
                w = parseDimension(wstr, displayId);
                h = parseDimension(hstr, displayId);
            } catch (NumberFormatException e) {
                getErrPrintWriter().println("Error: bad number " + e);
                return -1;
            }
        }

        if (w >= 0 && h >= 0) {
            mInterface.setForcedDisplaySize(displayId, w, h);
        } else {
            mInterface.clearForcedDisplaySize(displayId);
        }
        return 0;
    }

    private void printInitialDisplayDensity(PrintWriter pw , int displayId) {
        try {
            final int initialDensity = mInterface.getInitialDisplayDensity(displayId);
            final int baseDensity = mInterface.getBaseDisplayDensity(displayId);
            pw.println("Physical density: " + initialDensity);
            if (initialDensity != baseDensity) {
                pw.println("Override density: " + baseDensity);
            }
        } catch (RemoteException e) {
            // Can't call getInitialDisplayDensity() on IWindowManager or
            // Can't call getBaseDisplayDensity() on IWindowManager
            pw.println("Remote exception: " + e);
        }
    }

    private int runDisplayDensity(PrintWriter pw) throws RemoteException {
        String densityStr = getNextArg();
        int density;
        final int displayId = getDisplayId(densityStr);

        if (densityStr == null) {
            printInitialDisplayDensity(pw, displayId);
            return 0;
        } else if ("-d".equals(densityStr)) {
            printInitialDisplayDensity(pw, displayId);
            return 0;
        } else if ("reset".equals(densityStr)) {
            density = -1;
        } else {
            try {
                density = Integer.parseInt(densityStr);
            } catch (NumberFormatException e) {
                getErrPrintWriter().println("Error: bad number " + e);
                return -1;
            }
            if (density < 72) {
                getErrPrintWriter().println("Error: density must be >= 72");
                return -1;
            }
        }

        if (density > 0) {
            mInterface.setForcedDisplayDensityForUser(displayId, density,
                    UserHandle.USER_CURRENT);
        } else {
            mInterface.clearForcedDisplayDensityForUser(displayId,
                    UserHandle.USER_CURRENT);
        }
        return 0;
    }

    private void printFoldedArea(PrintWriter pw) {
        final Rect foldedArea = mInternal.getFoldedArea();
        if (foldedArea.isEmpty()) {
            pw.println("Folded area: none");
        } else {
            pw.println("Folded area: " + foldedArea.left + "," + foldedArea.top + ","
                    + foldedArea.right + "," + foldedArea.bottom);
        }
    }

    private int runDisplayFoldedArea(PrintWriter pw) {
        final String areaStr = getNextArg();
        final Rect rect = new Rect();
        if (areaStr == null) {
            printFoldedArea(pw);
            return 0;
        } else if ("reset".equals(areaStr)) {
            rect.setEmpty();
        } else {
            final Pattern flattenedPattern = Pattern.compile(
                    "(-?\\d+),(-?\\d+),(-?\\d+),(-?\\d+)");
            final Matcher matcher = flattenedPattern.matcher(areaStr);
            if (!matcher.matches()) {
                getErrPrintWriter().println("Error: area should be LEFT,TOP,RIGHT,BOTTOM");
                return -1;
            }
            rect.set(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)), Integer.parseInt(matcher.group(4)));
        }

        mInternal.setOverrideFoldedArea(rect);
        return 0;
    }

    private int runDisplayOverscan(PrintWriter pw) throws RemoteException {
        String overscanStr = getNextArgRequired();
        Rect rect = new Rect();
        final int displayId = getDisplayId(overscanStr);
        if ("reset".equals(overscanStr)) {
            rect.set(0, 0, 0, 0);
        } else {
            final Pattern FLATTENED_PATTERN = Pattern.compile(
                    "(-?\\d+),(-?\\d+),(-?\\d+),(-?\\d+)");
            Matcher matcher = FLATTENED_PATTERN.matcher(overscanStr);
            if (!matcher.matches()) {
                getErrPrintWriter().println("Error: bad rectangle arg: " + overscanStr);
                return -1;
            }
            rect.left = Integer.parseInt(matcher.group(1));
            rect.top = Integer.parseInt(matcher.group(2));
            rect.right = Integer.parseInt(matcher.group(3));
            rect.bottom = Integer.parseInt(matcher.group(4));
        }

        mInterface.setOverscan(displayId, rect.left, rect.top, rect.right, rect.bottom);
        return 0;
    }

    private int runDisplayScaling(PrintWriter pw) throws RemoteException {
        String scalingStr = getNextArgRequired();
        if ("auto".equals(scalingStr)) {
            mInterface.setForcedDisplayScalingMode(getDisplayId(scalingStr),
                    DisplayContent.FORCE_SCALING_MODE_AUTO);
        } else if ("off".equals(scalingStr)) {
            mInterface.setForcedDisplayScalingMode(getDisplayId(scalingStr),
                    DisplayContent.FORCE_SCALING_MODE_DISABLED);
        } else {
            getErrPrintWriter().println("Error: scaling must be 'auto' or 'off'");
            return -1;
        }
        return 0;
    }

    private int runDismissKeyguard(PrintWriter pw) throws RemoteException {
        mInterface.dismissKeyguard(null /* callback */, null /* message */);
        return 0;
    }

    private int parseDimension(String s, int displayId) throws NumberFormatException {
        if (s.endsWith("px")) {
            return Integer.parseInt(s.substring(0, s.length() - 2));
        }
        if (s.endsWith("dp")) {
            int density;
            try {
                density = mInterface.getBaseDisplayDensity(displayId);
            } catch (RemoteException e) {
                density = DisplayMetrics.DENSITY_DEFAULT;
            }
            return Integer.parseInt(s.substring(0, s.length() - 2)) * density /
                    DisplayMetrics.DENSITY_DEFAULT;
        }
        return Integer.parseInt(s);
    }

    private int runSetDisplayUserRotation(PrintWriter pw) {
        final String lockMode = getNextArgRequired();

        int displayId = Display.DEFAULT_DISPLAY;
        String arg = getNextArg();
        if ("-d".equals(arg)) {
            displayId = Integer.parseInt(getNextArgRequired());
            arg = getNextArg();
        }

        if ("free".equals(lockMode)) {
            mInternal.thawDisplayRotation(displayId);
            return 0;
        }

        if (!lockMode.equals("lock")) {
            getErrPrintWriter().println("Error: lock mode needs to be either free or lock.");
            return -1;
        }

        try {
            final int rotation = arg != null ? Integer.parseInt(arg) : Surface.ROTATION_0;
            mInternal.freezeDisplayRotation(displayId, rotation);
            return 0;
        } catch (IllegalArgumentException e) {
            getErrPrintWriter().println("Error: " + e.getMessage());
            return -1;
        }
    }

    private int runSetFixToUserRotation(PrintWriter pw) {
        int displayId = Display.DEFAULT_DISPLAY;
        String arg = getNextArgRequired();
        if ("-d".equals(arg)) {
            displayId = Integer.parseInt(getNextArgRequired());
            arg = getNextArgRequired();
        }

        final boolean enabled;
        switch (arg) {
            case "enabled":
                enabled = true;
                break;
            case "disabled":
                enabled = false;
                break;
            default:
                getErrPrintWriter().println("Error: expecting enabled or disabled, but we get "
                        + arg);
                return -1;
        }

        mInternal.setRotateForApp(displayId, enabled);
        return 0;
    }

    @Override
    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        pw.println("Window manager (window) commands:");
        pw.println("  help");
        pw.println("      Print this help text.");
        pw.println("  size [reset|WxH|WdpxHdp] [-d DISPLAY_ID]");
        pw.println("    Return or override display size.");
        pw.println("    width and height in pixels unless suffixed with 'dp'.");
        pw.println("  density [reset|DENSITY] [-d DISPLAY_ID]");
        pw.println("    Return or override display density.");
        pw.println("  folded-area [reset|LEFT,TOP,RIGHT,BOTTOM]");
        pw.println("    Return or override folded area.");
        pw.println("  overscan [reset|LEFT,TOP,RIGHT,BOTTOM] [-d DISPLAY ID]");
        pw.println("    Set overscan area for display.");
        pw.println("  scaling [off|auto] [-d DISPLAY_ID]");
        pw.println("    Set display scaling mode.");
        pw.println("  dismiss-keyguard");
        pw.println("    Dismiss the keyguard, prompting user for auth if necessary.");
        pw.println("  set-user-rotation [free|lock] [-d DISPLAY_ID] [rotation]");
        pw.println("    Set user rotation mode and user rotation.");
        pw.println("  set-fix-to-user-rotation [-d DISPLAY_ID] [enabled|disabled]");
        pw.println("    Enable or disable rotating display for app requested orientation.");
        if (!IS_USER) {
            pw.println("  tracing (start | stop)");
            pw.println("    Start or stop window tracing.");
        }
    }
}
