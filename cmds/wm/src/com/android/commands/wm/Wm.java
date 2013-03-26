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


package com.android.commands.wm;

import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.AndroidException;
import android.view.Display;
import android.view.IWindowManager;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Wm {

    private IWindowManager mWm;
    private String[] mArgs;
    private int mNextArg;
    private String mCurArgData;

    // These are magic strings understood by the Eclipse plugin.
    private static final String FATAL_ERROR_CODE = "Error type 1";
    private static final String NO_SYSTEM_ERROR_CODE = "Error type 2";
    private static final String NO_CLASS_ERROR_CODE = "Error type 3";

    /**
     * Command-line entry point.
     *
     * @param args The command-line arguments
     */
    public static void main(String[] args) {
        try {
            (new Wm()).run(args);
        } catch (IllegalArgumentException e) {
            showUsage();
            System.err.println("Error: " + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private void run(String[] args) throws Exception {
        if (args.length < 1) {
            showUsage();
            return;
        }

        mWm = IWindowManager.Stub.asInterface(ServiceManager.checkService(
                        Context.WINDOW_SERVICE));
        if (mWm == null) {
            System.err.println(NO_SYSTEM_ERROR_CODE);
            throw new AndroidException("Can't connect to window manager; is the system running?");
        }

        mArgs = args;
        String op = args[0];
        mNextArg = 1;

        if (op.equals("size")) {
            runDisplaySize();
        } else if (op.equals("density")) {
            runDisplayDensity();
        } else if (op.equals("overscan")) {
            runDisplayOverscan();
        } else {
            throw new IllegalArgumentException("Unknown command: " + op);
        }
    }

    private void runDisplaySize() throws Exception {
        String size = nextArg();
        int w, h;
        if (size == null) {
            Point initialSize = new Point();
            Point baseSize = new Point();
            try {
                mWm.getInitialDisplaySize(Display.DEFAULT_DISPLAY, initialSize);
                mWm.getBaseDisplaySize(Display.DEFAULT_DISPLAY, baseSize);
                System.out.println("Physical size: " + initialSize.x + "x" + initialSize.y);
                if (!initialSize.equals(baseSize)) {
                    System.out.println("Override size: " + baseSize.x + "x" + baseSize.y);
                }
            } catch (RemoteException e) {
            }
            return;
        } else if ("reset".equals(size)) {
            w = h = -1;
        } else {
            int div = size.indexOf('x');
            if (div <= 0 || div >= (size.length()-1)) {
                System.err.println("Error: bad size " + size);
                return;
            }
            String wstr = size.substring(0, div);
            String hstr = size.substring(div+1);
            try {
                w = Integer.parseInt(wstr);
                h = Integer.parseInt(hstr);
            } catch (NumberFormatException e) {
                System.err.println("Error: bad number " + e);
                return;
            }
        }

        try {
            if (w >= 0 && h >= 0) {
                // TODO(multidisplay): For now Configuration only applies to main screen.
                mWm.setForcedDisplaySize(Display.DEFAULT_DISPLAY, w, h);
            } else {
                mWm.clearForcedDisplaySize(Display.DEFAULT_DISPLAY);
            }
        } catch (RemoteException e) {
        }
    }

    private void runDisplayDensity() throws Exception {
        String densityStr = nextArg();
        int density;
        if (densityStr == null) {
            try {
                int initialDensity = mWm.getInitialDisplayDensity(Display.DEFAULT_DISPLAY);
                int baseDensity = mWm.getBaseDisplayDensity(Display.DEFAULT_DISPLAY);
                System.out.println("Physical density: " + initialDensity);
                if (initialDensity != baseDensity) {
                    System.out.println("Override density: " + baseDensity);
                }
            } catch (RemoteException e) {
            }
            return;
        } else if ("reset".equals(densityStr)) {
            density = -1;
        } else {
            try {
                density = Integer.parseInt(densityStr);
            } catch (NumberFormatException e) {
                System.err.println("Error: bad number " + e);
                return;
            }
            if (density < 72) {
                System.err.println("Error: density must be >= 72");
                return;
            }
        }

        try {
            if (density > 0) {
                // TODO(multidisplay): For now Configuration only applies to main screen.
                mWm.setForcedDisplayDensity(Display.DEFAULT_DISPLAY, density);
            } else {
                mWm.clearForcedDisplayDensity(Display.DEFAULT_DISPLAY);
            }
        } catch (RemoteException e) {
        }
    }

    private void runDisplayOverscan() throws Exception {
        String overscanStr = nextArgRequired();
        Rect rect = new Rect();
        int density;
        if ("reset".equals(overscanStr)) {
            rect.set(0, 0, 0, 0);
        } else {
            final Pattern FLATTENED_PATTERN = Pattern.compile(
                    "(-?\\d+),(-?\\d+),(-?\\d+),(-?\\d+)");
            Matcher matcher = FLATTENED_PATTERN.matcher(overscanStr);
            if (!matcher.matches()) {
                System.err.println("Error: bad rectangle arg: " + overscanStr);
                return;
            }
            rect.left = Integer.parseInt(matcher.group(1));
            rect.top = Integer.parseInt(matcher.group(2));
            rect.right = Integer.parseInt(matcher.group(3));
            rect.bottom = Integer.parseInt(matcher.group(4));
        }

        try {
            mWm.setOverscan(Display.DEFAULT_DISPLAY, rect.left, rect.top, rect.right, rect.bottom);
        } catch (RemoteException e) {
        }
    }

    private String nextOption() {
        if (mCurArgData != null) {
            String prev = mArgs[mNextArg - 1];
            throw new IllegalArgumentException("No argument expected after \"" + prev + "\"");
        }
        if (mNextArg >= mArgs.length) {
            return null;
        }
        String arg = mArgs[mNextArg];
        if (!arg.startsWith("-")) {
            return null;
        }
        mNextArg++;
        if (arg.equals("--")) {
            return null;
        }
        if (arg.length() > 1 && arg.charAt(1) != '-') {
            if (arg.length() > 2) {
                mCurArgData = arg.substring(2);
                return arg.substring(0, 2);
            } else {
                mCurArgData = null;
                return arg;
            }
        }
        mCurArgData = null;
        return arg;
    }

    private String nextArg() {
        if (mCurArgData != null) {
            String arg = mCurArgData;
            mCurArgData = null;
            return arg;
        } else if (mNextArg < mArgs.length) {
            return mArgs[mNextArg++];
        } else {
            return null;
        }
    }

    private String nextArgRequired() {
        String arg = nextArg();
        if (arg == null) {
            String prev = mArgs[mNextArg - 1];
            throw new IllegalArgumentException("Argument expected after \"" + prev + "\"");
        }
        return arg;
    }

    private static void showUsage() {
        System.err.println(
                "usage: wm [subcommand] [options]\n" +
                "       wm size [reset|WxH]\n" +
                "       wm density [reset|DENSITY]\n" +
                "       wm overscan [reset|LEFT,TOP,RIGHT,BOTTOM]\n" +
                "\n" +
                "wm size: return or override display size.\n" +
                "\n" +
                "wm density: override display density.\n" +
                "\n" +
                "wm overscan: set overscan area for display.\n"
                );
    }
}
