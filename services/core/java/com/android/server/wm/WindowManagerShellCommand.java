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
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ShellCommand;
import android.os.UserHandle;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.IWindowManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
                    return mInternal.mWindowTracing.onShellCommand(this,
                            getNextArgRequired());
                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (RemoteException e) {
            pw.println("Remote exception: " + e);
        }
        return -1;
    }

    private int runDisplaySize(PrintWriter pw) throws RemoteException {
        String size = getNextArg();
        int w, h;
        if (size == null) {
            Point initialSize = new Point();
            Point baseSize = new Point();
            try {
                mInterface.getInitialDisplaySize(Display.DEFAULT_DISPLAY, initialSize);
                mInterface.getBaseDisplaySize(Display.DEFAULT_DISPLAY, baseSize);
                pw.println("Physical size: " + initialSize.x + "x" + initialSize.y);
                if (!initialSize.equals(baseSize)) {
                    pw.println("Override size: " + baseSize.x + "x" + baseSize.y);
                }
            } catch (RemoteException e) {
            }
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
                w = parseDimension(wstr);
                h = parseDimension(hstr);
            } catch (NumberFormatException e) {
                getErrPrintWriter().println("Error: bad number " + e);
                return -1;
            }
        }

        if (w >= 0 && h >= 0) {
            // TODO(multidisplay): For now Configuration only applies to main screen.
            mInterface.setForcedDisplaySize(Display.DEFAULT_DISPLAY, w, h);
        } else {
            mInterface.clearForcedDisplaySize(Display.DEFAULT_DISPLAY);
        }
        return 0;
    }

    private int runDisplayDensity(PrintWriter pw) throws RemoteException {
        String densityStr = getNextArg();
        int density;
        if (densityStr == null) {
            try {
                int initialDensity = mInterface.getInitialDisplayDensity(Display.DEFAULT_DISPLAY);
                int baseDensity = mInterface.getBaseDisplayDensity(Display.DEFAULT_DISPLAY);
                pw.println("Physical density: " + initialDensity);
                if (initialDensity != baseDensity) {
                    pw.println("Override density: " + baseDensity);
                }
            } catch (RemoteException e) {
            }
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
            // TODO(multidisplay): For now Configuration only applies to main screen.
            mInterface.setForcedDisplayDensityForUser(Display.DEFAULT_DISPLAY, density,
                    UserHandle.USER_CURRENT);
        } else {
            mInterface.clearForcedDisplayDensityForUser(Display.DEFAULT_DISPLAY,
                    UserHandle.USER_CURRENT);
        }
        return 0;
    }

    private int runDisplayOverscan(PrintWriter pw) throws RemoteException {
        String overscanStr = getNextArgRequired();
        Rect rect = new Rect();
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

        mInterface.setOverscan(Display.DEFAULT_DISPLAY, rect.left, rect.top, rect.right,
                rect.bottom);
        return 0;
    }

    private int runDisplayScaling(PrintWriter pw) throws RemoteException {
        String scalingStr = getNextArgRequired();
        if ("auto".equals(scalingStr)) {
            mInterface.setForcedDisplayScalingMode(Display.DEFAULT_DISPLAY, 0);
        } else if ("off".equals(scalingStr)) {
            mInterface.setForcedDisplayScalingMode(Display.DEFAULT_DISPLAY, 1);
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

    private int parseDimension(String s) throws NumberFormatException {
        if (s.endsWith("px")) {
            return Integer.parseInt(s.substring(0, s.length() - 2));
        }
        if (s.endsWith("dp")) {
            int density;
            try {
                density = mInterface.getBaseDisplayDensity(Display.DEFAULT_DISPLAY);
            } catch (RemoteException e) {
                density = DisplayMetrics.DENSITY_DEFAULT;
            }
            return Integer.parseInt(s.substring(0, s.length() - 2)) * density /
                    DisplayMetrics.DENSITY_DEFAULT;
        }
        return Integer.parseInt(s);
    }

    @Override
    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        pw.println("Window manager (window) commands:");
        pw.println("  help");
        pw.println("      Print this help text.");
        pw.println("  size [reset|WxH|WdpxHdp]");
        pw.println("    Return or override display size.");
        pw.println("    width and height in pixels unless suffixed with 'dp'.");
        pw.println("  density [reset|DENSITY]");
        pw.println("    Return or override display density.");
        pw.println("  overscan [reset|LEFT,TOP,RIGHT,BOTTOM]");
        pw.println("    Set overscan area for display.");
        pw.println("  scaling [off|auto]");
        pw.println("    Set display scaling mode.");
        pw.println("  dismiss-keyguard");
        pw.println("    Dismiss the keyguard, prompting user for auth if necessary.");
        if (!IS_USER) {
            pw.println("  tracing (start | stop)");
            pw.println("    Start or stop window tracing.");
        }
    }
}
