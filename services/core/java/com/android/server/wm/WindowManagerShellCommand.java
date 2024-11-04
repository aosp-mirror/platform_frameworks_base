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

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.os.Build.IS_USER;
import static android.view.CrossWindowBlurListeners.CROSS_WINDOW_BLUR_SUPPORTED;

import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND;
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND_FLOATING;
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_BACKGROUND_SOLID_COLOR;
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_BACKGROUND_WALLPAPER;
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_CENTER;
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT;
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_RIGHT;
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_VERTICAL_REACHABILITY_POSITION_BOTTOM;
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_VERTICAL_REACHABILITY_POSITION_CENTER;
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP;

import android.app.WindowConfiguration;
import android.content.res.Resources.NotFoundException;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ShellCommand;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Pair;
import android.util.TypedValue;
import android.view.Display;
import android.view.IWindow;
import android.view.IWindowManager;
import android.view.ViewDebug;

import com.android.internal.os.ByteTransferPipe;
import com.android.internal.protolog.LegacyProtoLogImpl;
import com.android.internal.protolog.PerfettoProtoLogImpl;
import com.android.internal.protolog.ProtoLog;
import com.android.internal.protolog.common.IProtoLog;
import com.android.server.IoThread;
import com.android.server.wm.AppCompatConfiguration.LetterboxBackgroundType;
import com.android.server.wm.AppCompatConfiguration.LetterboxHorizontalReachabilityPosition;
import com.android.server.wm.AppCompatConfiguration.LetterboxVerticalReachabilityPosition;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
    private final AppCompatConfiguration mAppCompatConfiguration;

    public WindowManagerShellCommand(WindowManagerService service) {
        mInterface = service;
        mInternal = service;
        mAppCompatConfiguration = service.mAppCompatConfiguration;
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
                case "scaling":
                    return runDisplayScaling(pw);
                case "dismiss-keyguard":
                    return runDismissKeyguard(pw);
                case "tracing":
                    // XXX this should probably be changed to use openFileForSystem() to create
                    // the output trace file, so the shell gets the correct semantics for where
                    // trace files can be written.
                    return mInternal.mWindowTracing.onShellCommand(this);
                case "logging":
                    IProtoLog instance = ProtoLog.getSingleInstance();
                    int result = 0;
                    if (instance instanceof LegacyProtoLogImpl
                            || instance instanceof PerfettoProtoLogImpl) {
                        if (instance instanceof LegacyProtoLogImpl) {
                            result = ((LegacyProtoLogImpl) instance).onShellCommand(this);
                        } else {
                            result = ((PerfettoProtoLogImpl) instance).onShellCommand(this);
                        }
                        if (result != 0) {
                            pw.println("Not handled, please use "
                                    + "`adb shell dumpsys activity service SystemUIService "
                                    + "WMShell` if you are looking for ProtoLog in WMShell");
                        }
                    } else {
                        result = -1;
                        pw.println("ProtoLog impl doesn't support handling commands");
                    }
                    return result;
                case "user-rotation":
                    return runDisplayUserRotation(pw);
                case "fixed-to-user-rotation":
                    return runFixedToUserRotation(pw);
                case "set-ignore-orientation-request":
                    return runSetIgnoreOrientationRequest(pw);
                case "get-ignore-orientation-request":
                    return runGetIgnoreOrientationRequest(pw);
                case "dump-visible-window-views":
                    return runDumpVisibleWindowViews(pw);
                case "set-letterbox-style":
                    return runSetLetterboxStyle(pw);
                case "get-letterbox-style":
                    return runGetLetterboxStyle(pw);
                case "reset-letterbox-style":
                    return runResetLetterboxStyle(pw);
                case "set-sandbox-display-apis":
                    return runSandboxDisplayApis(pw);
                case "set-multi-window-config":
                    return runSetMultiWindowConfig();
                case "get-multi-window-config":
                    return runGetMultiWindowConfig(pw);
                case "reset-multi-window-config":
                    return runResetMultiWindowConfig();
                case "reset":
                    return runReset(pw);
                case "disable-blur":
                    return runSetBlurDisabled(pw);
                case "set-display-windowing-mode":
                    return runSetDisplayWindowingMode(pw);
                case "get-display-windowing-mode":
                    return runGetDisplayWindowingMode(pw);
                case "shell":
                    return runWmShellCommand(pw);
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

    private int runSetBlurDisabled(PrintWriter pw) throws RemoteException {
        String arg = getNextArg();
        if (arg == null) {
            pw.println("Blur supported on device: " + CROSS_WINDOW_BLUR_SUPPORTED);
            pw.println("Blur enabled: " + mInternal.mBlurController.getBlurEnabled());
            return 0;
        }

        final boolean disableBlur;
        switch (arg) {
            case "true":
            case "1":
                disableBlur = true;
                break;
            case "false":
            case "0":
                disableBlur = false;
                break;
            default:
                getErrPrintWriter().println("Error: expected true, 1, false, 0, but got " + arg);
                return -1;
        }

        Settings.Global.putInt(mInternal.mContext.getContentResolver(),
                Settings.Global.DISABLE_WINDOW_BLURS, disableBlur ? 1 : 0);

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
        String densityStr = null;
        String arg = getNextArg();
        int density;
        int displayId = Display.DEFAULT_DISPLAY;
        if (!"-d".equals(arg) && !"-u".equals(arg)) {
            densityStr = arg;
            arg = getNextArg();
        }
        if ("-d".equals(arg)) {
            arg = getNextArg();
            try {
                displayId = Integer.parseInt(arg);
            } catch (NumberFormatException e) {
                getErrPrintWriter().println("Error: bad number " + e);
            }
        } else if ("-u".equals(arg)) {
            arg = getNextArg();
            displayId = mInterface.getDisplayIdByUniqueId(arg);
            if (displayId == Display.INVALID_DISPLAY) {
                getErrPrintWriter().println("Error: the uniqueId is invalid ");
                return -1;
            }
        }

        if (densityStr == null) {
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

    /**
     * Override display size and metrics to reflect the DisplayArea of the calling activity.
     */
    private int runSandboxDisplayApis(PrintWriter pw) throws RemoteException {
        int displayId = Display.DEFAULT_DISPLAY;
        String arg = getNextArgRequired();
        if ("-d".equals(arg)) {
            displayId = Integer.parseInt(getNextArgRequired());
            arg = getNextArgRequired();
        }

        final boolean sandboxDisplayApis;
        switch (arg) {
            case "true":
            case "1":
                sandboxDisplayApis = true;
                break;
            case "false":
            case "0":
                sandboxDisplayApis = false;
                break;
            default:
                getErrPrintWriter().println("Error: expecting true, 1, false, 0, but we "
                        + "get " + arg);
                return -1;
        }

        mInternal.setSandboxDisplayApis(displayId, sandboxDisplayApis);
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

    private int runDisplayUserRotation(PrintWriter pw) {
        int displayId = Display.DEFAULT_DISPLAY;
        String arg = getNextArg();
        if (arg == null) {
            return printDisplayUserRotation(pw, displayId);
        }

        if ("-d".equals(arg)) {
            displayId = Integer.parseInt(getNextArgRequired());
            arg = getNextArg();
        }

        final String lockMode = arg;
        if (lockMode == null) {
            return printDisplayUserRotation(pw, displayId);
        }

        if ("free".equals(lockMode)) {
            mInternal.thawDisplayRotation(displayId,
                    /* caller= */ "WindowManagerShellCommand#free");
            return 0;
        }

        if (!"lock".equals(lockMode)) {
            getErrPrintWriter().println("Error: argument needs to be either -d, free or lock.");
            return -1;
        }

        arg = getNextArg();
        try {
            final int rotation =
                    arg != null ? Integer.parseInt(arg) : -1 /* lock to current rotation */;
            mInternal.freezeDisplayRotation(displayId, rotation,
                    /* caller= */ "WindowManagerShellCommand#lock");
            return 0;
        } catch (IllegalArgumentException e) {
            getErrPrintWriter().println("Error: " + e.getMessage());
            return -1;
        }
    }

    private int printDisplayUserRotation(PrintWriter pw, int displayId) {
        final int displayUserRotation = mInternal.getDisplayUserRotation(displayId);
        if (displayUserRotation < 0) {
            getErrPrintWriter().println("Error: check logcat for more details.");
            return -1;
        }
        if (!mInternal.isDisplayRotationFrozen(displayId)) {
            pw.println("free");
            return 0;
        }
        pw.print("lock ");
        pw.println(displayUserRotation);
        return 0;
    }

    private int runFixedToUserRotation(PrintWriter pw) throws RemoteException {
        int displayId = Display.DEFAULT_DISPLAY;
        String arg = getNextArg();
        if (arg == null) {
            printFixedToUserRotation(pw, displayId);
            return 0;
        }

        if ("-d".equals(arg)) {
            displayId = Integer.parseInt(getNextArgRequired());
            arg = getNextArg();
        }

        if (arg == null) {
            return printFixedToUserRotation(pw, displayId);
        }

        final int fixedToUserRotation;
        switch (arg) {
            case "enabled":
                fixedToUserRotation = IWindowManager.FIXED_TO_USER_ROTATION_ENABLED;
                break;
            case "disabled":
                fixedToUserRotation = IWindowManager.FIXED_TO_USER_ROTATION_DISABLED;
                break;
            case "default":
                fixedToUserRotation = IWindowManager.FIXED_TO_USER_ROTATION_DEFAULT;
                break;
            case "enabled_if_no_auto_rotation":
                fixedToUserRotation = IWindowManager.FIXED_TO_USER_ROTATION_IF_NO_AUTO_ROTATION;
                break;
            default:
                getErrPrintWriter().println("Error: expecting enabled, disabled or default, but we "
                        + "get " + arg);
                return -1;
        }

        mInterface.setFixedToUserRotation(displayId, fixedToUserRotation);
        return 0;
    }

    private int printFixedToUserRotation(PrintWriter pw, int displayId) {
        int fixedToUserRotationMode = mInternal.getFixedToUserRotation(displayId);
        switch (fixedToUserRotationMode) {
            case IWindowManager.FIXED_TO_USER_ROTATION_DEFAULT:
                pw.println("default");
                return 0;
            case IWindowManager.FIXED_TO_USER_ROTATION_DISABLED:
                pw.println("disabled");
                return 0;
            case IWindowManager.FIXED_TO_USER_ROTATION_IF_NO_AUTO_ROTATION:
                pw.println("enabled_if_no_auto_rotation");
                return 0;
            case IWindowManager.FIXED_TO_USER_ROTATION_ENABLED:
                pw.println("enabled");
                return 0;
            default:
                getErrPrintWriter().println("Error: check logcat for more details.");
                return -1;
        }
    }

    private int runSetIgnoreOrientationRequest(PrintWriter pw) throws RemoteException {
        int displayId = Display.DEFAULT_DISPLAY;
        String arg = getNextArgRequired();
        if ("-d".equals(arg)) {
            displayId = Integer.parseInt(getNextArgRequired());
            arg = getNextArgRequired();
        }

        final boolean ignoreOrientationRequest;
        switch (arg) {
            case "true":
            case "1":
                ignoreOrientationRequest = true;
                break;
            case "false":
            case "0":
                ignoreOrientationRequest = false;
                break;
            default:
                getErrPrintWriter().println("Error: expecting true, 1, false, 0, but we "
                        + "get " + arg);
                return -1;
        }

        mInterface.setIgnoreOrientationRequest(displayId, ignoreOrientationRequest);
        return 0;
    }

    private int runGetIgnoreOrientationRequest(PrintWriter pw) throws RemoteException {
        int displayId = Display.DEFAULT_DISPLAY;
        String arg = getNextArg();
        if ("-d".equals(arg)) {
            displayId = Integer.parseInt(getNextArgRequired());
        }

        final boolean ignoreOrientationRequest = mInternal.getIgnoreOrientationRequest(displayId);
        pw.println("ignoreOrientationRequest " + ignoreOrientationRequest
                + " for displayId=" + displayId);
        return 0;
    }

    private void dumpLocalWindowAsync(IWindow client, ParcelFileDescriptor pfd) {
        // Make it asynchronous to avoid writer from being blocked
        // by waiting for the buffer to be consumed in the same process.
        IoThread.getExecutor().execute(() -> {
            synchronized (mInternal.mGlobalLock) {
                try {
                    client.executeCommand(ViewDebug.REMOTE_COMMAND_DUMP_ENCODED, null, pfd);
                } catch (Exception e) {
                    // Ignore RemoteException for local call. Just print trace for other
                    // exceptions caused by RC with tolerable low possibility.
                    e.printStackTrace();
                }
            }
        });
    }

    private int runDumpVisibleWindowViews(PrintWriter pw) {
        if (!mInternal.checkCallingPermission(android.Manifest.permission.DUMP,
                "runDumpVisibleWindowViews()")) {
            throw new SecurityException("Requires DUMP permission");
        }

        try (ZipOutputStream out = new ZipOutputStream(getRawOutputStream())) {
            ArrayList<Pair<String, ByteTransferPipe>> requestList = new ArrayList<>();
            synchronized (mInternal.mGlobalLock) {
                final RecentTasks recentTasks = mInternal.mAtmService.getRecentTasks();
                final int recentsComponentUid = recentTasks != null
                        ? recentTasks.getRecentsComponentUid()
                        : -1;
                // Request dump from all windows parallelly before writing to disk.
                mInternal.mRoot.forAllWindows(w -> {
                    final boolean isRecents = (w.getUid() == recentsComponentUid);
                    if (w.isVisible() || isRecents) {
                        ByteTransferPipe pipe = null;
                        try {
                            pipe = new ByteTransferPipe();
                            final ParcelFileDescriptor pfd = pipe.getWriteFd();
                            if (w.isClientLocal()) {
                                dumpLocalWindowAsync(w.mClient, pfd);
                            } else {
                                w.mClient.executeCommand(
                                        ViewDebug.REMOTE_COMMAND_DUMP_ENCODED, null, pfd);
                            }
                            requestList.add(Pair.create(w.getName(), pipe));
                        } catch (IOException | RemoteException e) {
                            // Skip this window
                            if (pipe != null) {
                                pipe.kill();
                            }
                        }
                    }
                }, false /* traverseTopToBottom */);
            }
            for (Pair<String, ByteTransferPipe> entry : requestList) {
                byte[] data;
                try {
                    data = entry.second.get();
                } catch (IOException e) {
                    // Ignore this window
                    continue;
                }
                out.putNextEntry(new ZipEntry(entry.first));
                out.write(data);
            }
        } catch (IOException e) {
            pw.println("Error fetching dump " + e.getMessage());
        }
        return 0;
    }

    private int runSetFixedOrientationLetterboxAspectRatio(PrintWriter pw) throws RemoteException {
        final float aspectRatio;
        try {
            String arg = getNextArgRequired();
            aspectRatio = Float.parseFloat(arg);
        } catch (NumberFormatException  e) {
            getErrPrintWriter().println("Error: bad aspect ratio format " + e);
            return -1;
        } catch (IllegalArgumentException  e) {
            getErrPrintWriter().println(
                    "Error: aspect ratio should be provided as an argument " + e);
            return -1;
        }
        synchronized (mInternal.mGlobalLock) {
            mAppCompatConfiguration.setFixedOrientationLetterboxAspectRatio(aspectRatio);
        }
        return 0;
    }

    private int runSetDefaultMinAspectRatioForUnresizableApps(PrintWriter pw)
            throws RemoteException {
        final float aspectRatio;
        try {
            String arg = getNextArgRequired();
            aspectRatio = Float.parseFloat(arg);
        } catch (NumberFormatException  e) {
            getErrPrintWriter().println("Error: bad aspect ratio format " + e);
            return -1;
        } catch (IllegalArgumentException  e) {
            getErrPrintWriter().println(
                    "Error: aspect ratio should be provided as an argument " + e);
            return -1;
        }
        synchronized (mInternal.mGlobalLock) {
            mAppCompatConfiguration.setDefaultMinAspectRatioForUnresizableApps(aspectRatio);
        }
        return 0;
    }

    private int runSetLetterboxActivityCornersRadius(PrintWriter pw) throws RemoteException {
        final int cornersRadius;
        try {
            String arg = getNextArgRequired();
            cornersRadius = Integer.parseInt(arg);
        } catch (NumberFormatException  e) {
            getErrPrintWriter().println("Error: bad corners radius format " + e);
            return -1;
        } catch (IllegalArgumentException  e) {
            getErrPrintWriter().println(
                    "Error: corners radius should be provided as an argument " + e);
            return -1;
        }
        synchronized (mInternal.mGlobalLock) {
            mAppCompatConfiguration.setLetterboxActivityCornersRadius(cornersRadius);
        }
        return 0;
    }

    private int runSetLetterboxBackgroundType(PrintWriter pw) throws RemoteException {
        @LetterboxBackgroundType final int backgroundType;
        try {
            String arg = getNextArgRequired();
            switch (arg) {
                case "solid_color":
                    backgroundType = LETTERBOX_BACKGROUND_SOLID_COLOR;
                    break;
                case "app_color_background":
                    backgroundType = LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND;
                    break;
                case "app_color_background_floating":
                    backgroundType = LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND_FLOATING;
                    break;
                case "wallpaper":
                    backgroundType = LETTERBOX_BACKGROUND_WALLPAPER;
                    break;
                default:
                    getErrPrintWriter().println(
                            "Error: 'solid_color', 'app_color_background' or "
                            + "'wallpaper' should be provided as an argument");
                    return -1;
            }
        } catch (IllegalArgumentException  e) {
            getErrPrintWriter().println(
                    "Error: 'solid_color', 'app_color_background' or "
                        + "'wallpaper' should be provided as an argument" + e);
            return -1;
        }
        synchronized (mInternal.mGlobalLock) {
            mAppCompatConfiguration.setLetterboxBackgroundTypeOverride(backgroundType);
        }
        return 0;
    }

    private int runSetLetterboxBackgroundColorResource(PrintWriter pw) throws RemoteException {
        final int colorId;
        try {
            String arg = getNextArgRequired();
            colorId = mInternal.mContext.getResources()
                    .getIdentifier(arg, "color", "com.android.internal");
        } catch (NotFoundException e) {
            getErrPrintWriter().println(
                    "Error: color in '@android:color/resource_name' format should be provided as "
                            + "an argument " + e);
            return -1;
        }
        synchronized (mInternal.mGlobalLock) {
            mAppCompatConfiguration.setLetterboxBackgroundColorResourceId(colorId);
        }
        return 0;
    }

    private int runSetLetterboxBackgroundColor(PrintWriter pw) throws RemoteException {
        final Color color;
        try {
            String arg = getNextArgRequired();
            color = Color.valueOf(Color.parseColor(arg));
        } catch (IllegalArgumentException  e) {
            getErrPrintWriter().println(
                    "Error: color in #RRGGBB format should be provided as "
                            + "an argument " + e);
            return -1;
        }
        synchronized (mInternal.mGlobalLock) {
            mAppCompatConfiguration.setLetterboxBackgroundColor(color);
        }
        return 0;
    }

    private int runSetLetterboxBackgroundWallpaperBlurRadius(PrintWriter pw)
            throws RemoteException {
        final int radiusDp;
        try {
            String arg = getNextArgRequired();
            radiusDp = Integer.parseInt(arg);
        } catch (NumberFormatException  e) {
            getErrPrintWriter().println("Error: blur radius format " + e);
            return -1;
        } catch (IllegalArgumentException  e) {
            getErrPrintWriter().println(
                    "Error: blur radius should be provided as an argument " + e);
            return -1;
        }
        synchronized (mInternal.mGlobalLock) {
            final int radiusPx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                    radiusDp, mInternal.mContext.getResources().getDisplayMetrics());
            mAppCompatConfiguration.setLetterboxBackgroundWallpaperBlurRadiusPx(radiusPx);
        }
        return 0;
    }

    private int runSetLetterboxBackgroundWallpaperDarkScrimAlpha(PrintWriter pw)
            throws RemoteException {
        final float alpha;
        try {
            String arg = getNextArgRequired();
            alpha = Float.parseFloat(arg);
        } catch (NumberFormatException  e) {
            getErrPrintWriter().println("Error: bad alpha format " + e);
            return -1;
        } catch (IllegalArgumentException  e) {
            getErrPrintWriter().println(
                    "Error: alpha should be provided as an argument " + e);
            return -1;
        }
        synchronized (mInternal.mGlobalLock) {
            mAppCompatConfiguration.setLetterboxBackgroundWallpaperDarkScrimAlpha(alpha);
        }
        return 0;
    }

    private int runSetLetterboxHorizontalPositionMultiplier(PrintWriter pw) throws RemoteException {
        final float multiplier;
        try {
            String arg = getNextArgRequired();
            multiplier = Float.parseFloat(arg);
        } catch (NumberFormatException  e) {
            getErrPrintWriter().println("Error: bad multiplier format " + e);
            return -1;
        } catch (IllegalArgumentException  e) {
            getErrPrintWriter().println(
                    "Error: multiplier should be provided as an argument " + e);
            return -1;
        }
        synchronized (mInternal.mGlobalLock) {
            try {
                mAppCompatConfiguration.setLetterboxHorizontalPositionMultiplier(multiplier);
            } catch (IllegalArgumentException  e) {
                getErrPrintWriter().println("Error: invalid multiplier value " + e);
                return -1;
            }
        }
        return 0;
    }

    private int runSetLetterboxVerticalPositionMultiplier(PrintWriter pw) throws RemoteException {
        final float multiplier;
        try {
            String arg = getNextArgRequired();
            multiplier = Float.parseFloat(arg);
        } catch (NumberFormatException  e) {
            getErrPrintWriter().println("Error: bad multiplier format " + e);
            return -1;
        } catch (IllegalArgumentException  e) {
            getErrPrintWriter().println(
                    "Error: multiplier should be provided as an argument " + e);
            return -1;
        }
        synchronized (mInternal.mGlobalLock) {
            try {
                mAppCompatConfiguration.setLetterboxVerticalPositionMultiplier(multiplier);
            } catch (IllegalArgumentException  e) {
                getErrPrintWriter().println("Error: invalid multiplier value " + e);
                return -1;
            }
        }
        return 0;
    }

    private int runSetLetterboxDefaultPositionForHorizontalReachability(PrintWriter pw)
            throws RemoteException {
        @LetterboxHorizontalReachabilityPosition final int position;
        try {
            String arg = getNextArgRequired();
            switch (arg) {
                case "left":
                    position = LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT;
                    break;
                case "center":
                    position = LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_CENTER;
                    break;
                case "right":
                    position = LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_RIGHT;
                    break;
                default:
                    getErrPrintWriter().println(
                            "Error: 'left', 'center' or 'right' are expected as an argument");
                    return -1;
            }
        } catch (IllegalArgumentException  e) {
            getErrPrintWriter().println(
                    "Error: 'left', 'center' or 'right' are expected as an argument" + e);
            return -1;
        }
        synchronized (mInternal.mGlobalLock) {
            mAppCompatConfiguration.setDefaultPositionForHorizontalReachability(position);
        }
        return 0;
    }

    private int runSetLetterboxDefaultPositionForVerticalReachability(PrintWriter pw)
            throws RemoteException {
        @LetterboxVerticalReachabilityPosition final int position;
        try {
            String arg = getNextArgRequired();
            switch (arg) {
                case "top":
                    position = LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP;
                    break;
                case "center":
                    position = LETTERBOX_VERTICAL_REACHABILITY_POSITION_CENTER;
                    break;
                case "bottom":
                    position = LETTERBOX_VERTICAL_REACHABILITY_POSITION_BOTTOM;
                    break;
                default:
                    getErrPrintWriter().println(
                            "Error: 'top', 'center' or 'bottom' are expected as an argument");
                    return -1;
            }
        } catch (IllegalArgumentException  e) {
            getErrPrintWriter().println(
                    "Error: 'top', 'center' or 'bottom' are expected as an argument" + e);
            return -1;
        }
        synchronized (mInternal.mGlobalLock) {
            mAppCompatConfiguration.setDefaultPositionForVerticalReachability(position);
        }
        return 0;
    }

    private int runSetPersistentLetterboxPositionForHorizontalReachability(PrintWriter pw)
            throws RemoteException {
        @LetterboxHorizontalReachabilityPosition final int position;
        try {
            String arg = getNextArgRequired();
            switch (arg) {
                case "left":
                    position = LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT;
                    break;
                case "center":
                    position = LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_CENTER;
                    break;
                case "right":
                    position = LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_RIGHT;
                    break;
                default:
                    getErrPrintWriter().println(
                            "Error: 'left', 'center' or 'right' are expected as an argument");
                    return -1;
            }
        } catch (IllegalArgumentException e) {
            getErrPrintWriter().println(
                    "Error: 'left', 'center' or 'right' are expected as an argument" + e);
            return -1;
        }
        synchronized (mInternal.mGlobalLock) {
            mAppCompatConfiguration.setPersistentLetterboxPositionForHorizontalReachability(
                    false /* IsInBookMode */, position);
        }
        return 0;
    }

    private int runSetPersistentLetterboxPositionForVerticalReachability(PrintWriter pw)
            throws RemoteException {
        @LetterboxVerticalReachabilityPosition final int position;
        try {
            String arg = getNextArgRequired();
            switch (arg) {
                case "top":
                    position = LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP;
                    break;
                case "center":
                    position = LETTERBOX_VERTICAL_REACHABILITY_POSITION_CENTER;
                    break;
                case "bottom":
                    position = LETTERBOX_VERTICAL_REACHABILITY_POSITION_BOTTOM;
                    break;
                default:
                    getErrPrintWriter().println(
                            "Error: 'top', 'center' or 'bottom' are expected as an argument");
                    return -1;
            }
        } catch (IllegalArgumentException e) {
            getErrPrintWriter().println(
                    "Error: 'top', 'center' or 'bottom' are expected as an argument" + e);
            return -1;
        }
        synchronized (mInternal.mGlobalLock) {
            mAppCompatConfiguration.setPersistentLetterboxPositionForVerticalReachability(
                    false /* forTabletopMode */, position);
        }
        return 0;
    }

    private int runSetBooleanFlag(PrintWriter pw, Consumer<Boolean> setter)
            throws RemoteException {
        String arg = getNextArg();
        if (arg == null) {
            getErrPrintWriter().println("Error: expected true, 1, false, 0, but got empty input.");
            return -1;
        }
        final boolean enabled;
        switch (arg) {
            case "true":
            case "1":
                enabled = true;
                break;
            case "false":
            case "0":
                enabled = false;
                break;
            default:
                getErrPrintWriter().println("Error: expected true, 1, false, 0, but got " + arg);
                return -1;
        }

        synchronized (mInternal.mGlobalLock) {
            setter.accept(enabled);
        }
        return 0;
    }

    private int runSetCameraCompatAspectRatio(PrintWriter pw) throws RemoteException {
        final float aspectRatio;
        try {
            String arg = getNextArgRequired();
            aspectRatio = Float.parseFloat(arg);
        } catch (NumberFormatException e) {
            getErrPrintWriter().println("Error: bad aspect ratio format " + e);
            return -1;
        } catch (IllegalArgumentException e) {
            getErrPrintWriter().println(
                    "Error: aspect ratio should be provided as an argument " + e);
            return -1;
        }
        synchronized (mInternal.mGlobalLock) {
            mAppCompatConfiguration.setCameraCompatAspectRatio(aspectRatio);
        }
        return 0;
    }

    private int runSetLetterboxStyle(PrintWriter pw) throws RemoteException {
        if (peekNextArg() == null) {
            getErrPrintWriter().println("Error: No arguments provided.");
        }
        while (peekNextArg() != null) {
            String arg = getNextArg();
            switch (arg) {
                case "--aspectRatio":
                    runSetFixedOrientationLetterboxAspectRatio(pw);
                    break;
                case "--minAspectRatioForUnresizable":
                    runSetDefaultMinAspectRatioForUnresizableApps(pw);
                    break;
                case "--cornerRadius":
                    runSetLetterboxActivityCornersRadius(pw);
                    break;
                case "--backgroundType":
                    runSetLetterboxBackgroundType(pw);
                    break;
                case "--backgroundColor":
                    runSetLetterboxBackgroundColor(pw);
                    break;
                case "--backgroundColorResource":
                    runSetLetterboxBackgroundColorResource(pw);
                    break;
                case "--wallpaperBlurRadius":
                    runSetLetterboxBackgroundWallpaperBlurRadius(pw);
                    break;
                case "--wallpaperDarkScrimAlpha":
                    runSetLetterboxBackgroundWallpaperDarkScrimAlpha(pw);
                    break;
                case "--horizontalPositionMultiplier":
                    runSetLetterboxHorizontalPositionMultiplier(pw);
                    break;
                case "--verticalPositionMultiplier":
                    runSetLetterboxVerticalPositionMultiplier(pw);
                    break;
                case "--isHorizontalReachabilityEnabled":
                    runSetBooleanFlag(pw, mAppCompatConfiguration
                            ::setIsHorizontalReachabilityEnabled);
                    break;
                case "--isVerticalReachabilityEnabled":
                    runSetBooleanFlag(pw, mAppCompatConfiguration
                            ::setIsVerticalReachabilityEnabled);
                    break;
                case "--isAutomaticReachabilityInBookModeEnabled":
                    runSetBooleanFlag(pw, mAppCompatConfiguration
                            ::setIsAutomaticReachabilityInBookModeEnabled);
                    break;
                case "--defaultPositionForHorizontalReachability":
                    runSetLetterboxDefaultPositionForHorizontalReachability(pw);
                    break;
                case "--defaultPositionForVerticalReachability":
                    runSetLetterboxDefaultPositionForVerticalReachability(pw);
                    break;
                case "--persistentPositionForHorizontalReachability":
                    runSetPersistentLetterboxPositionForHorizontalReachability(pw);
                    break;
                case "--persistentPositionForVerticalReachability":
                    runSetPersistentLetterboxPositionForVerticalReachability(pw);
                    break;
                case "--isEducationEnabled":
                    runSetBooleanFlag(pw, mAppCompatConfiguration::setIsEducationEnabled);
                    break;
                case "--isSplitScreenAspectRatioForUnresizableAppsEnabled":
                    runSetBooleanFlag(pw, mAppCompatConfiguration
                            ::setIsSplitScreenAspectRatioForUnresizableAppsEnabled);
                    break;
                case "--isDisplayAspectRatioEnabledForFixedOrientationLetterbox":
                    runSetBooleanFlag(pw, mAppCompatConfiguration
                            ::setIsDisplayAspectRatioEnabledForFixedOrientationLetterbox);
                    break;
                case "--isTranslucentLetterboxingEnabled":
                    runSetBooleanFlag(pw, mAppCompatConfiguration
                            ::setTranslucentLetterboxingOverrideEnabled);
                    break;
                case "--isUserAppAspectRatioSettingsEnabled":
                    runSetBooleanFlag(pw, mAppCompatConfiguration
                            ::setUserAppAspectRatioSettingsOverrideEnabled);
                    break;
                case "--isUserAppAspectRatioFullscreenEnabled":
                    runSetBooleanFlag(pw, mAppCompatConfiguration
                            ::setUserAppAspectRatioFullscreenOverrideEnabled);
                    break;
                case "--isCameraCompatRefreshEnabled":
                    runSetBooleanFlag(pw, mAppCompatConfiguration::setCameraCompatRefreshEnabled);
                    break;
                case "--isCameraCompatRefreshCycleThroughStopEnabled":
                    runSetBooleanFlag(pw,
                            mAppCompatConfiguration::setCameraCompatRefreshCycleThroughStopEnabled);
                    break;
                case "--cameraCompatAspectRatio":
                    runSetCameraCompatAspectRatio(pw);
                    break;
                default:
                    getErrPrintWriter().println(
                            "Error: Unrecognized letterbox style option: " + arg);
                    return -1;
            }
        }
        return 0;
    }

    private int runResetLetterboxStyle(PrintWriter pw) throws RemoteException {
        if (peekNextArg() == null) {
            resetLetterboxStyle();
        }
        synchronized (mInternal.mGlobalLock) {
            while (peekNextArg() != null) {
                String arg = getNextArg();
                switch (arg) {
                    case "aspectRatio":
                        mAppCompatConfiguration.resetFixedOrientationLetterboxAspectRatio();
                        break;
                    case "minAspectRatioForUnresizable":
                        mAppCompatConfiguration.resetDefaultMinAspectRatioForUnresizableApps();
                        break;
                    case "cornerRadius":
                        mAppCompatConfiguration.resetLetterboxActivityCornersRadius();
                        break;
                    case "backgroundType":
                        mAppCompatConfiguration.resetLetterboxBackgroundType();
                        break;
                    case "backgroundColor":
                        mAppCompatConfiguration.resetLetterboxBackgroundColor();
                        break;
                    case "wallpaperBlurRadius":
                        mAppCompatConfiguration.resetLetterboxBackgroundWallpaperBlurRadiusPx();
                        break;
                    case "wallpaperDarkScrimAlpha":
                        mAppCompatConfiguration.resetLetterboxBackgroundWallpaperDarkScrimAlpha();
                        break;
                    case "horizontalPositionMultiplier":
                        mAppCompatConfiguration.resetLetterboxHorizontalPositionMultiplier();
                        break;
                    case "verticalPositionMultiplier":
                        mAppCompatConfiguration.resetLetterboxVerticalPositionMultiplier();
                        break;
                    case "isHorizontalReachabilityEnabled":
                        mAppCompatConfiguration.resetIsHorizontalReachabilityEnabled();
                        break;
                    case "isVerticalReachabilityEnabled":
                        mAppCompatConfiguration.resetIsVerticalReachabilityEnabled();
                        break;
                    case "defaultPositionForHorizontalReachability":
                        mAppCompatConfiguration.resetDefaultPositionForHorizontalReachability();
                        break;
                    case "defaultPositionForVerticalReachability":
                        mAppCompatConfiguration.resetDefaultPositionForVerticalReachability();
                        break;
                    case "persistentPositionForHorizontalReachability":
                        mAppCompatConfiguration
                                .resetPersistentLetterboxPositionForHorizontalReachability();
                        break;
                    case "persistentPositionForVerticalReachability":
                        mAppCompatConfiguration
                                .resetPersistentLetterboxPositionForVerticalReachability();
                        break;
                    case "isEducationEnabled":
                        mAppCompatConfiguration.resetIsEducationEnabled();
                        break;
                    case "isSplitScreenAspectRatioForUnresizableAppsEnabled":
                        mAppCompatConfiguration
                                .resetIsSplitScreenAspectRatioForUnresizableAppsEnabled();
                        break;
                    case "IsDisplayAspectRatioEnabledForFixedOrientationLetterbox":
                        mAppCompatConfiguration
                                .resetIsDisplayAspectRatioEnabledForFixedOrientationLetterbox();
                        break;
                    case "isTranslucentLetterboxingEnabled":
                        mAppCompatConfiguration.resetTranslucentLetterboxingEnabled();
                        break;
                    case "isUserAppAspectRatioSettingsEnabled":
                        mAppCompatConfiguration.resetUserAppAspectRatioSettingsEnabled();
                        break;
                    case "isUserAppAspectRatioFullscreenEnabled":
                        mAppCompatConfiguration.resetUserAppAspectRatioFullscreenEnabled();
                        break;
                    case "isCameraCompatRefreshEnabled":
                        mAppCompatConfiguration.resetCameraCompatRefreshEnabled();
                        break;
                    case "isCameraCompatRefreshCycleThroughStopEnabled":
                        mAppCompatConfiguration
                                .resetCameraCompatRefreshCycleThroughStopEnabled();
                        break;
                    case "cameraCompatAspectRatio":
                        mAppCompatConfiguration.resetCameraCompatAspectRatio();
                        break;
                    default:
                        getErrPrintWriter().println(
                                "Error: Unrecognized letterbox style option: " + arg);
                        return -1;
                }
            }
        }
        return 0;
    }

    private int runSetMultiWindowConfig() {
        if (peekNextArg() == null) {
            getErrPrintWriter().println("Error: No arguments provided.");
        }
        int result = 0;
        while (peekNextArg() != null) {
            String arg = getNextArg();
            switch (arg) {
                case "--supportsNonResizable":
                    result += runSetSupportsNonResizableMultiWindow();
                    break;
                case "--respectsActivityMinWidthHeight":
                    result += runSetRespectsActivityMinWidthHeightMultiWindow();
                    break;
                default:
                    getErrPrintWriter().println(
                            "Error: Unrecognized multi window option: " + arg);
                    return -1;
            }
        }
        return result == 0 ? 0 : -1;
    }

    private int runSetSupportsNonResizableMultiWindow() {
        final String arg = getNextArg();
        if (!arg.equals("-1") && !arg.equals("0") && !arg.equals("1")) {
            getErrPrintWriter().println("Error: a config value of [-1, 0, 1] must be provided as"
                    + " an argument for supportsNonResizableMultiWindow");
            return -1;
        }
        final int configValue = Integer.parseInt(arg);
        synchronized (mInternal.mAtmService.mGlobalLock) {
            mInternal.mAtmService.mSupportsNonResizableMultiWindow = configValue;
        }
        return 0;
    }

    private int runSetRespectsActivityMinWidthHeightMultiWindow() {
        final String arg = getNextArg();
        if (!arg.equals("-1") && !arg.equals("0") && !arg.equals("1")) {
            getErrPrintWriter().println("Error: a config value of [-1, 0, 1] must be provided as"
                    + " an argument for respectsActivityMinWidthHeightMultiWindow");
            return -1;
        }
        final int configValue = Integer.parseInt(arg);
        synchronized (mInternal.mAtmService.mGlobalLock) {
            mInternal.mAtmService.mRespectsActivityMinWidthHeightMultiWindow = configValue;
        }
        return 0;
    }

    private int runGetMultiWindowConfig(PrintWriter pw) {
        synchronized (mInternal.mAtmService.mGlobalLock) {
            pw.println("Supports non-resizable in multi window: "
                    + mInternal.mAtmService.mSupportsNonResizableMultiWindow);
            pw.println("Respects activity min width/height in multi window: "
                    + mInternal.mAtmService.mRespectsActivityMinWidthHeightMultiWindow);
        }
        return 0;
    }

    private int runResetMultiWindowConfig() {
        final int supportsNonResizable = mInternal.mContext.getResources().getInteger(
                com.android.internal.R.integer.config_supportsNonResizableMultiWindow);
        final int respectsActivityMinWidthHeight = mInternal.mContext.getResources().getInteger(
                com.android.internal.R.integer.config_respectsActivityMinWidthHeightMultiWindow);
        synchronized (mInternal.mAtmService.mGlobalLock) {
            mInternal.mAtmService.mSupportsNonResizableMultiWindow = supportsNonResizable;
            mInternal.mAtmService.mRespectsActivityMinWidthHeightMultiWindow =
                    respectsActivityMinWidthHeight;
        }
        return 0;
    }

    private void resetLetterboxStyle() {
        synchronized (mInternal.mGlobalLock) {
            mAppCompatConfiguration.resetFixedOrientationLetterboxAspectRatio();
            mAppCompatConfiguration.resetDefaultMinAspectRatioForUnresizableApps();
            mAppCompatConfiguration.resetLetterboxActivityCornersRadius();
            mAppCompatConfiguration.resetLetterboxBackgroundType();
            mAppCompatConfiguration.resetLetterboxBackgroundColor();
            mAppCompatConfiguration.resetLetterboxBackgroundWallpaperBlurRadiusPx();
            mAppCompatConfiguration.resetLetterboxBackgroundWallpaperDarkScrimAlpha();
            mAppCompatConfiguration.resetLetterboxHorizontalPositionMultiplier();
            mAppCompatConfiguration.resetLetterboxVerticalPositionMultiplier();
            mAppCompatConfiguration.resetIsHorizontalReachabilityEnabled();
            mAppCompatConfiguration.resetIsVerticalReachabilityEnabled();
            mAppCompatConfiguration.resetEnabledAutomaticReachabilityInBookMode();
            mAppCompatConfiguration.resetDefaultPositionForHorizontalReachability();
            mAppCompatConfiguration.resetDefaultPositionForVerticalReachability();
            mAppCompatConfiguration.resetPersistentLetterboxPositionForHorizontalReachability();
            mAppCompatConfiguration.resetPersistentLetterboxPositionForVerticalReachability();
            mAppCompatConfiguration.resetIsEducationEnabled();
            mAppCompatConfiguration.resetIsSplitScreenAspectRatioForUnresizableAppsEnabled();
            mAppCompatConfiguration.resetIsDisplayAspectRatioEnabledForFixedOrientationLetterbox();
            mAppCompatConfiguration.resetTranslucentLetterboxingEnabled();
            mAppCompatConfiguration.resetUserAppAspectRatioSettingsEnabled();
            mAppCompatConfiguration.resetUserAppAspectRatioFullscreenEnabled();
            mAppCompatConfiguration.resetCameraCompatRefreshEnabled();
            mAppCompatConfiguration.resetCameraCompatRefreshCycleThroughStopEnabled();
            mAppCompatConfiguration.resetCameraCompatAspectRatio();
        }
    }

    private int runGetLetterboxStyle(PrintWriter pw) throws RemoteException {
        synchronized (mInternal.mGlobalLock) {
            pw.println("Corner radius: "
                    + mAppCompatConfiguration.getLetterboxActivityCornersRadius());
            pw.println("Horizontal position multiplier: "
                    + mAppCompatConfiguration.getLetterboxHorizontalPositionMultiplier(
                            false /* isInBookMode */));
            pw.println("Vertical position multiplier: "
                    + mAppCompatConfiguration.getLetterboxVerticalPositionMultiplier(
                            false /* isInTabletopMode */));
            pw.println("Horizontal position multiplier (book mode): "
                    + mAppCompatConfiguration.getLetterboxHorizontalPositionMultiplier(
                            true /* isInBookMode */));
            pw.println("Vertical position multiplier (tabletop mode): "
                    + mAppCompatConfiguration.getLetterboxVerticalPositionMultiplier(
                            true /* isInTabletopMode */));
            pw.println("Horizontal position multiplier for reachability: "
                    + mAppCompatConfiguration.getHorizontalMultiplierForReachability(
                            false /* isInBookMode */));
            pw.println("Vertical position multiplier for reachability: "
                    + mAppCompatConfiguration.getVerticalMultiplierForReachability(
                            false /* isInTabletopMode */));
            pw.println("Aspect ratio: "
                    + mAppCompatConfiguration.getFixedOrientationLetterboxAspectRatio());
            pw.println("Default min aspect ratio for unresizable apps: "
                    + mAppCompatConfiguration.getDefaultMinAspectRatioForUnresizableApps());
            pw.println("Is horizontal reachability enabled: "
                    + mAppCompatConfiguration.getIsHorizontalReachabilityEnabled());
            pw.println("Is vertical reachability enabled: "
                    + mAppCompatConfiguration.getIsVerticalReachabilityEnabled());
            pw.println("Is automatic reachability in book mode enabled: "
                    + mAppCompatConfiguration.getIsAutomaticReachabilityInBookModeEnabled());
            pw.println("Default position for horizontal reachability: "
                    + AppCompatConfiguration.letterboxHorizontalReachabilityPositionToString(
                            mAppCompatConfiguration.getDefaultPositionForHorizontalReachability()));
            pw.println("Default position for vertical reachability: "
                    + AppCompatConfiguration.letterboxVerticalReachabilityPositionToString(
                    mAppCompatConfiguration.getDefaultPositionForVerticalReachability()));
            pw.println("Current position for horizontal reachability:"
                    + AppCompatConfiguration.letterboxHorizontalReachabilityPositionToString(
                    mAppCompatConfiguration.getLetterboxPositionForHorizontalReachability(false)));
            pw.println("Current position for vertical reachability:"
                    + AppCompatConfiguration.letterboxVerticalReachabilityPositionToString(
                    mAppCompatConfiguration.getLetterboxPositionForVerticalReachability(false)));
            pw.println("Is education enabled: "
                    + mAppCompatConfiguration.getIsEducationEnabled());
            pw.println("Is using split screen aspect ratio as aspect ratio for unresizable apps: "
                    + mAppCompatConfiguration
                            .getIsSplitScreenAspectRatioForUnresizableAppsEnabled());
            pw.println("Is using display aspect ratio as aspect ratio for all letterboxed apps: "
                    + mAppCompatConfiguration
                            .getIsDisplayAspectRatioEnabledForFixedOrientationLetterbox());
            pw.println("    Is activity \"refresh\" in camera compatibility treatment enabled: "
                    + mAppCompatConfiguration.isCameraCompatRefreshEnabled());
            pw.println("    Refresh using \"stopped -> resumed\" cycle: "
                    + mAppCompatConfiguration.isCameraCompatRefreshCycleThroughStopEnabled());
            pw.println("Background type: "
                    + AppCompatConfiguration.letterboxBackgroundTypeToString(
                            mAppCompatConfiguration.getLetterboxBackgroundType()));
            pw.println("    Background color: " + Integer.toHexString(
                    mAppCompatConfiguration.getLetterboxBackgroundColor().toArgb()));
            pw.println("    Wallpaper blur radius: "
                    + mAppCompatConfiguration.getLetterboxBackgroundWallpaperBlurRadiusPx());
            pw.println("    Wallpaper dark scrim alpha: "
                    + mAppCompatConfiguration.getLetterboxBackgroundWallpaperDarkScrimAlpha());
            pw.println("Is letterboxing for translucent activities enabled: "
                    + mAppCompatConfiguration.isTranslucentLetterboxingEnabled());
            pw.println("Is the user aspect ratio settings enabled: "
                    + mAppCompatConfiguration.isUserAppAspectRatioSettingsEnabled());
            pw.println("Is the fullscreen option in user aspect ratio settings enabled: "
                    + mAppCompatConfiguration.isUserAppAspectRatioFullscreenEnabled());
        }
        return 0;
    }

    private int runSetDisplayWindowingMode(PrintWriter pw) throws RemoteException {
        int displayId = Display.DEFAULT_DISPLAY;
        String arg = getNextArgRequired();
        if ("-d".equals(arg)) {
            displayId = Integer.parseInt(getNextArgRequired());
            arg = getNextArgRequired();
        }

        final int windowingMode = Integer.parseInt(arg);
        mInterface.setWindowingMode(displayId, windowingMode);

        return 0;
    }

    private int runGetDisplayWindowingMode(PrintWriter pw) throws RemoteException {
        int displayId = Display.DEFAULT_DISPLAY;
        final String arg = getNextArg();
        if ("-d".equals(arg)) {
            displayId = Integer.parseInt(getNextArgRequired());
        }

        final int windowingMode = mInterface.getWindowingMode(displayId);
        pw.println("display windowing mode="
                + WindowConfiguration.windowingModeToString(windowingMode) + " for displayId="
                + displayId);

        return 0;
    }

    private int runWmShellCommand(PrintWriter pw) {
        String arg = getNextArg();

        switch (arg) {
            case "tracing":
                return runWmShellTracing(pw);
            case "help":
            default:
                return runHelp(pw);
        }
    }

    private int runHelp(PrintWriter pw) {
        pw.println("Window Manager Shell commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println("  tracing <start/stop>");
        pw.println("    Start/stop shell transition tracing.");

        return 0;
    }

    private int runWmShellTracing(PrintWriter pw) {
        String arg = getNextArg();

        switch (arg) {
            case "start":
                mInternal.mTransitionTracer.startTrace(pw);
                break;
            case "stop":
                mInternal.mTransitionTracer.stopTrace(pw);
                break;
            case "save-for-bugreport":
                mInternal.mTransitionTracer.saveForBugreport(pw);
                break;
            default:
                getErrPrintWriter()
                        .println("Error: expected 'start' or 'stop', but got '" + arg + "'");
                return -1;
        }

        return 0;
    }

    private int runReset(PrintWriter pw) throws RemoteException {
        int displayId = getDisplayId(getNextArg());

        // size
        mInterface.clearForcedDisplaySize(displayId);

        // density
        mInterface.clearForcedDisplayDensityForUser(displayId, UserHandle.USER_CURRENT);

        // folded-area
        mInternal.setOverrideFoldedArea(new Rect());

        // scaling
        mInterface.setForcedDisplayScalingMode(displayId, DisplayContent.FORCE_SCALING_MODE_AUTO);

        // user-rotation
        mInternal.thawDisplayRotation(displayId,
                /* caller= */ "WindowManagerShellCommand#runReset");

        // fixed-to-user-rotation
        mInterface.setFixedToUserRotation(displayId, IWindowManager.FIXED_TO_USER_ROTATION_DEFAULT);

        // set-ignore-orientation-request
        mInterface.setIgnoreOrientationRequest(displayId, false /* ignoreOrientationRequest */);

        // set-letterbox-style
        resetLetterboxStyle();

        // set-sandbox-display-apis
        mInternal.setSandboxDisplayApis(displayId, /* sandboxDisplayApis= */ true);

        // set-multi-window-config
        runResetMultiWindowConfig();

        // set-display-windowing-mode
        mInternal.setWindowingMode(displayId, WINDOWING_MODE_UNDEFINED);

        pw.println("Reset all settings for displayId=" + displayId);
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
        pw.println("  density [reset|DENSITY] [-d DISPLAY_ID] [-u UNIQUE_ID]");
        pw.println("    Return or override display density.");
        pw.println("  folded-area [reset|LEFT,TOP,RIGHT,BOTTOM]");
        pw.println("    Return or override folded area.");
        pw.println("  scaling [off|auto] [-d DISPLAY_ID]");
        pw.println("    Set display scaling mode.");
        pw.println("  dismiss-keyguard");
        pw.println("    Dismiss the keyguard, prompting user for auth if necessary.");
        pw.println("  disable-blur [true|1|false|0]");
        pw.println("  user-rotation [-d DISPLAY_ID] [free|lock] [rotation]");
        pw.println("    Print or set user rotation mode and user rotation.");
        pw.println("  dump-visible-window-views");
        pw.println("    Dumps the encoded view hierarchies of visible windows");
        pw.println("  fixed-to-user-rotation [-d DISPLAY_ID] [enabled|disabled|default");
        pw.println("      |enabled_if_no_auto_rotation]");
        pw.println("    Print or set rotating display for app requested orientation.");
        pw.println("  set-ignore-orientation-request [-d DISPLAY_ID] [true|1|false|0]");
        pw.println("  get-ignore-orientation-request [-d DISPLAY_ID] ");
        pw.println("    If app requested orientation should be ignored.");
        pw.println("  set-sandbox-display-apis [true|1|false|0]");
        pw.println("    Sets override of Display APIs getRealSize / getRealMetrics to reflect ");
        pw.println("    DisplayArea of the activity, or the window bounds if in letterbox or");
        pw.println("    Size Compat Mode.");

        printLetterboxHelp(pw);
        printMultiWindowConfigHelp(pw);

        pw.println("  set-display-windowing-mode [-d DISPLAY_ID] [mode_id]");
        pw.println("    As mode_id, use " + WINDOWING_MODE_UNDEFINED + " for undefined, "
                + WINDOWING_MODE_FREEFORM + " for freeform, " + WINDOWING_MODE_FULLSCREEN + " for"
                + " fullscreen");
        pw.println("  get-display-windowing-mode [-d DISPLAY_ID]");

        pw.println("  reset [-d DISPLAY_ID]");
        pw.println("    Reset all override settings.");
        if (!IS_USER) {
            pw.println("  tracing (start | stop)");
            pw.println("    Start or stop window tracing.");
            pw.println("  logging (start | stop | enable | disable | enable-text | disable-text)");
            pw.println("    Logging settings.");
        }
    }

    private void printLetterboxHelp(PrintWriter pw) {
        pw.println("  set-letterbox-style");
        pw.println("    Sets letterbox style using the following options:");
        pw.println("      --aspectRatio aspectRatio");
        pw.println("        Aspect ratio of letterbox for fixed orientation. If aspectRatio <= "
                + AppCompatConfiguration.MIN_FIXED_ORIENTATION_LETTERBOX_ASPECT_RATIO);
        pw.println("        both it and R.dimen.config_fixedOrientationLetterboxAspectRatio will");
        pw.println("        be ignored and framework implementation will determine aspect ratio.");
        pw.println("      --minAspectRatioForUnresizable aspectRatio");
        pw.println("        Default min aspect ratio for unresizable apps which is used when an");
        pw.println("        app is eligible for the size compat mode.  If aspectRatio <= "
                + AppCompatConfiguration.MIN_FIXED_ORIENTATION_LETTERBOX_ASPECT_RATIO);
        pw.println("        both it and R.dimen.config_fixedOrientationLetterboxAspectRatio will");
        pw.println("        be ignored and framework implementation will determine aspect ratio.");
        pw.println("      --cornerRadius radius");
        pw.println("        Corners radius (in pixels) for activities in the letterbox mode.");
        pw.println("        If radius < 0, both R.integer.config_letterboxActivityCornersRadius");
        pw.println("        and it will be ignored and corners of the activity won't be rounded.");
        pw.println("      --backgroundType [reset|solid_color|app_color_background");
        pw.println("          |app_color_background_floating|wallpaper]");
        pw.println("        Type of background used in the letterbox mode.");
        pw.println("      --backgroundColor color");
        pw.println("        Color of letterbox which is be used when letterbox background type");
        pw.println("        is 'solid-color'. Use (set)get-letterbox-style to check and control");
        pw.println("        letterbox background type. See Color#parseColor for allowed color");
        pw.println("        formats (#RRGGBB and some colors by name, e.g. magenta or olive).");
        pw.println("      --backgroundColorResource resource_name");
        pw.println("        Color resource name of letterbox background which is used when");
        pw.println("        background type is 'solid-color'. Use (set)get-letterbox-style to");
        pw.println("        check and control background type. Parameter is a color resource");
        pw.println("        name, for example, @android:color/system_accent2_50.");
        pw.println("      --wallpaperBlurRadius radius");
        pw.println("        Blur radius for 'wallpaper' letterbox background. If radius <= 0");
        pw.println("        both it and R.dimen.config_letterboxBackgroundWallpaperBlurRadius");
        pw.println("        are ignored and 0 is used.");
        pw.println("      --wallpaperDarkScrimAlpha alpha");
        pw.println("        Alpha of a black translucent scrim shown over 'wallpaper'");
        pw.println("        letterbox background. If alpha < 0 or >= 1 both it and");
        pw.println("        R.dimen.config_letterboxBackgroundWallaperDarkScrimAlpha are ignored");
        pw.println("        and 0.0 (transparent) is used instead.");
        pw.println("      --horizontalPositionMultiplier multiplier");
        pw.println("        Horizontal position of app window center. If multiplier < 0 or > 1,");
        pw.println("        both it and R.dimen.config_letterboxHorizontalPositionMultiplier");
        pw.println("        are ignored and central position (0.5) is used.");
        pw.println("      --verticalPositionMultiplier multiplier");
        pw.println("        Vertical position of app window center. If multiplier < 0 or > 1,");
        pw.println("        both it and R.dimen.config_letterboxVerticalPositionMultiplier");
        pw.println("        are ignored and central position (0.5) is used.");
        pw.println("      --isHorizontalReachabilityEnabled [true|1|false|0]");
        pw.println("        Whether horizontal reachability repositioning is allowed for ");
        pw.println("        letterboxed fullscreen apps in landscape device orientation.");
        pw.println("      --isVerticalReachabilityEnabled [true|1|false|0]");
        pw.println("        Whether vertical reachability repositioning is allowed for ");
        pw.println("        letterboxed fullscreen apps in portrait device orientation.");
        pw.println("      --defaultPositionForHorizontalReachability [left|center|right]");
        pw.println("        Default position of app window when horizontal reachability is.");
        pw.println("        enabled.");
        pw.println("      --defaultPositionForVerticalReachability [top|center|bottom]");
        pw.println("        Default position of app window when vertical reachability is.");
        pw.println("        enabled.");
        pw.println("      --persistentPositionForHorizontalReachability [left|center|right]");
        pw.println("        Persistent position of app window when horizontal reachability is.");
        pw.println("        enabled.");
        pw.println("      --persistentPositionForVerticalReachability [top|center|bottom]");
        pw.println("        Persistent position of app window when vertical reachability is.");
        pw.println("        enabled.");
        pw.println("      --isEducationEnabled [true|1|false|0]");
        pw.println("        Whether education is allowed for letterboxed fullscreen apps.");
        pw.println("      --isSplitScreenAspectRatioForUnresizableAppsEnabled [true|1|false|0]");
        pw.println("        Whether using split screen aspect ratio as a default aspect ratio for");
        pw.println("        unresizable apps.");
        pw.println("      --isTranslucentLetterboxingEnabled [true|1|false|0]");
        pw.println("        Whether letterboxing for translucent activities is enabled.");
        pw.println("      --isUserAppAspectRatioSettingsEnabled [true|1|false|0]");
        pw.println("        Whether user aspect ratio settings are enabled.");
        pw.println("      --isUserAppAspectRatioFullscreenEnabled [true|1|false|0]");
        pw.println("        Whether user aspect ratio fullscreen option is enabled.");
        pw.println("      --isCameraCompatRefreshEnabled [true|1|false|0]");
        pw.println("        Whether camera compatibility refresh is enabled.");
        pw.println("      --isCameraCompatRefreshCycleThroughStopEnabled [true|1|false|0]");
        pw.println("        Whether activity \"refresh\" in camera compatibility treatment should");
        pw.println("        happen using the \"stopped -> resumed\" cycle rather than");
        pw.println("        \"paused -> resumed\" cycle.");
        pw.println("      --cameraCompatAspectRatio aspectRatio");
        pw.println("        Aspect ratio of letterbox for fixed-orientation camera apps, during ");
        pw.println("        freeform camera compat mode. If aspectRatio <= "
                + AppCompatConfiguration.MIN_FIXED_ORIENTATION_LETTERBOX_ASPECT_RATIO);
        pw.println("        it will be ignored.");
        pw.println("  reset-letterbox-style [aspectRatio|cornerRadius|backgroundType");
        pw.println("      |backgroundColor|wallpaperBlurRadius|wallpaperDarkScrimAlpha");
        pw.println("      |horizontalPositionMultiplier|verticalPositionMultiplier");
        pw.println("      |isHorizontalReachabilityEnabled|isVerticalReachabilityEnabled");
        pw.println("      |isEducationEnabled|defaultPositionMultiplierForHorizontalReachability");
        pw.println("      |isTranslucentLetterboxingEnabled|isUserAppAspectRatioSettingsEnabled");
        pw.println("      |persistentPositionMultiplierForHorizontalReachability");
        pw.println("      |persistentPositionMultiplierForVerticalReachability");
        pw.println("      |defaultPositionMultiplierForVerticalReachability");
        pw.println("      |cameraCompatAspectRatio]");
        pw.println("    Resets overrides to default values for specified properties separated");
        pw.println("    by space, e.g. 'reset-letterbox-style aspectRatio cornerRadius'.");
        pw.println("    If no arguments provided, all values will be reset.");
        pw.println("  get-letterbox-style");
        pw.println("    Prints letterbox style configuration.");
    }

    private void printMultiWindowConfigHelp(PrintWriter pw) {
        pw.println("  set-multi-window-config");
        pw.println("    Sets options to determine if activity should be shown in multi window:");
        pw.println("      --supportsNonResizable [configValue]");
        pw.println("        Whether the device supports non-resizable activity in multi window.");
        pw.println("        -1: The device doesn't support non-resizable in multi window.");
        pw.println("         0: The device supports non-resizable in multi window only if");
        pw.println("            this is a large screen device.");
        pw.println("         1: The device always supports non-resizable in multi window.");
        pw.println("      --respectsActivityMinWidthHeight [configValue]");
        pw.println("        Whether the device checks the activity min width/height to determine ");
        pw.println("        if it can be shown in multi window.");
        pw.println("        -1: The device ignores the activity min width/height when determining");
        pw.println("            if it can be shown in multi window.");
        pw.println("         0: If this is a small screen, the device compares the activity min");
        pw.println("            width/height with the min multi window modes dimensions");
        pw.println("            the device supports to determine if the activity can be shown in");
        pw.println("            multi window.");
        pw.println("         1: The device always compare the activity min width/height with the");
        pw.println("            min multi window dimensions the device supports to determine if");
        pw.println("            the activity can be shown in multi window.");
        pw.println("  get-multi-window-config");
        pw.println("    Prints values of the multi window config options.");
        pw.println("  reset-multi-window-config");
        pw.println("    Resets overrides to default values of the multi window config options.");
    }
}
