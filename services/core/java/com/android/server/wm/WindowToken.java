/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.os.IBinder;
import android.util.Slog;

import java.io.PrintWriter;

/**
 * Container of a set of related windows in the window manager.  Often this
 * is an AppWindowToken, which is the handle for an Activity that it uses
 * to display windows.  For nested windows, there is a WindowToken created for
 * the parent window to manage its children.
 */
class WindowToken {
    // The window manager!
    final WindowManagerService service;

    // The actual token.
    final IBinder token;

    // The type of window this token is for, as per WindowManager.LayoutParams.
    final int windowType;

    // Set if this token was explicitly added by a client, so should
    // not be removed when all windows are removed.
    final boolean explicit;

    // For printing.
    String stringName;

    // If this is an AppWindowToken, this is non-null.
    AppWindowToken appWindowToken;

    // All of the windows associated with this token.
    final WindowList windows = new WindowList();

    // Is key dispatching paused for this token?
    boolean paused = false;

    // Should this token's windows be hidden?
    boolean hidden;

    // Temporary for finding which tokens no longer have visible windows.
    boolean hasVisible;

    // Set to true when this token is in a pending transaction where it
    // will be shown.
    boolean waitingToShow;

    // Set to true when this token is in a pending transaction where its
    // windows will be put to the bottom of the list.
    boolean sendingToBottom;

    WindowToken(WindowManagerService _service, IBinder _token, int type, boolean _explicit) {
        service = _service;
        token = _token;
        windowType = type;
        explicit = _explicit;
    }

    void removeAllWindows() {
        for (int winNdx = windows.size() - 1; winNdx >= 0; --winNdx) {
            WindowState win = windows.get(winNdx);
            if (WindowManagerService.DEBUG_WINDOW_MOVEMENT) Slog.w(WindowManagerService.TAG,
                    "removeAllWindows: removing win=" + win);
            win.mService.removeWindowLocked(win);
        }
        windows.clear();
    }

    void dump(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.print("windows="); pw.println(windows);
        pw.print(prefix); pw.print("windowType="); pw.print(windowType);
                pw.print(" hidden="); pw.print(hidden);
                pw.print(" hasVisible="); pw.println(hasVisible);
        if (waitingToShow || sendingToBottom) {
            pw.print(prefix); pw.print("waitingToShow="); pw.print(waitingToShow);
                    pw.print(" sendingToBottom="); pw.print(sendingToBottom);
        }
    }

    @Override
    public String toString() {
        if (stringName == null) {
            StringBuilder sb = new StringBuilder();
            sb.append("WindowToken{");
            sb.append(Integer.toHexString(System.identityHashCode(this)));
            sb.append(" "); sb.append(token); sb.append('}');
            stringName = sb.toString();
        }
        return stringName;
    }
}