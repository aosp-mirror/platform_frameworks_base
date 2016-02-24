/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.server.webkit;

import android.os.RemoteException;
import android.os.ShellCommand;
import android.webkit.IWebViewUpdateService;

import java.io.PrintWriter;

class WebViewUpdateServiceShellCommand extends ShellCommand {
    final IWebViewUpdateService mInterface;

    WebViewUpdateServiceShellCommand(IWebViewUpdateService service) {
        mInterface = service;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }

        final PrintWriter pw = getOutPrintWriter();
        try {
            // TODO(gsennton) add command for changing WebView provider
            switch(cmd) {
                case "enable-redundant-packages":
                    return enableFallbackLogic(false);
                case "disable-redundant-packages":
                    return enableFallbackLogic(true);
                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (RemoteException e) {
            pw.println("Remote exception: " + e);
        }
        return -1;
    }

    private int enableFallbackLogic(boolean enable) throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        mInterface.enableFallbackLogic(enable);
        pw.println("Success");
        return 0;
    }

    @Override
    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        pw.println("WebView updater commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println("");
        pw.println("  enable-redundant-packages");
        pw.println("    Allow a fallback package to be installed and enabled even when a");
        pw.println("    more-preferred package is available. This command is useful when testing");
        pw.println("    fallback packages.");
        pw.println("  disable-redundant-packages");
        pw.println("    Disallow installing and enabling fallback packages when a more-preferred");
        pw.println("    package is available.");
        pw.println();
    }
}
