/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server;

import android.util.Log;
import android.util.LogWriter;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.Watchdog.HandlerChecker;

import dalvik.system.AnnotatedStackTraceElement;
import dalvik.system.VMStack;

import java.io.PrintWriter;
import java.util.List;

/**
 * Class to give diagnostic messages for Watchdogs.
 */
class WatchdogDiagnostics {
    private static String getBlockedOnString(Object blockedOn) {
        return String.format("- waiting to lock <0x%08x> (a %s)",
                System.identityHashCode(blockedOn), blockedOn.getClass().getName());
    }

    private static String getLockedString(Object heldLock) {
        return String.format("- locked <0x%08x> (a %s)", System.identityHashCode(heldLock),
                heldLock.getClass().getName());
    }

    /**
     * Print the annotated stack for the given thread. If the annotated stack cannot be retrieved,
     * returns false.
     */
    @VisibleForTesting
    public static boolean printAnnotatedStack(Thread thread, PrintWriter out) {
        AnnotatedStackTraceElement stack[] = VMStack.getAnnotatedThreadStackTrace(thread);
        if (stack == null) {
            return false;
        }
        out.println(thread.getName() + " annotated stack trace:");
        for (AnnotatedStackTraceElement element : stack) {
            out.println("    at " + element.getStackTraceElement());
            if (element.getBlockedOn() != null) {
                out.println("    " + getBlockedOnString(element.getBlockedOn()));
            }
            if (element.getHeldLocks() != null) {
                for (Object held : element.getHeldLocks()) {
                    out.println("    " + getLockedString(held));
                }
            }
        }
        return true;
    }

    public static void diagnoseCheckers(final List<HandlerChecker> blockedCheckers) {
        PrintWriter out = new PrintWriter(new LogWriter(Log.WARN, Watchdog.TAG, Log.LOG_ID_SYSTEM),
                true);
        for (int i=0; i<blockedCheckers.size(); i++) {
            Thread blockedThread = blockedCheckers.get(i).getThread();
            if (printAnnotatedStack(blockedThread, out)) {
                continue;
            }

            // Fall back to "regular" stack trace, if necessary.
            Slog.w(Watchdog.TAG, blockedThread.getName() + " stack trace:");
            StackTraceElement[] stackTrace = blockedThread.getStackTrace();
            for (StackTraceElement element : stackTrace) {
                Slog.w(Watchdog.TAG, "    at " + element);
            }
        }
    }
}
