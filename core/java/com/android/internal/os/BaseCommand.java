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

package com.android.internal.os;

import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;

import com.android.modules.utils.BasicShellCommandHandler;

import java.io.PrintStream;

public abstract class BaseCommand {

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    final protected BasicShellCommandHandler mArgs = new BasicShellCommandHandler() {
        @Override public int onCommand(String cmd) {
            return 0;
        }
        @Override public void onHelp() {
        }
    };

    // These are magic strings understood by the Eclipse plugin.
    public static final String FATAL_ERROR_CODE = "Error type 1";
    public static final String NO_SYSTEM_ERROR_CODE = "Error type 2";
    public static final String NO_CLASS_ERROR_CODE = "Error type 3";

    private String[] mRawArgs;

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public BaseCommand() {
    }

    /**
     * Call to run the command.
     */
    public void run(String[] args) {
        if (args.length < 1) {
            onShowUsage(System.out);
            return;
        }

        mRawArgs = args;
        mArgs.init(null, null, null, null, args, 0);

        int status = 1;
        try {
            onRun();
            status = 0;
        } catch (IllegalArgumentException e) {
            onShowUsage(System.err);
            System.err.println();
            System.err.println("Error: " + e.getMessage());
            status = 0;
        } catch (Exception e) {
            e.printStackTrace(System.err);
        } finally {
            System.out.flush();
            System.err.flush();
        }
        if (status != 0) {
            System.exit(status);
        }
    }

    /**
     * Convenience to show usage information to error output.
     */
    public void showUsage() {
        onShowUsage(System.err);
    }

    /**
     * Convenience to show usage information to error output along
     * with an error message.
     */
    public void showError(String message) {
        onShowUsage(System.err);
        System.err.println();
        System.err.println(message);
    }

    /**
     * Implement the command.
     */
    public abstract void onRun() throws Exception;

    /**
     * Print help text for the command.
     */
    public abstract void onShowUsage(PrintStream out);

    /**
     * Return the next option on the command line -- that is an argument that
     * starts with '-'.  If the next argument is not an option, null is returned.
     */
    public String nextOption() {
        return mArgs.getNextOption();
    }

    /**
     * Return the next argument on the command line, whatever it is; if there are
     * no arguments left, return null.
     */
    public String nextArg() {
        return mArgs.getNextArg();
    }

    /**
     * Peek the next argument on the command line, whatever it is; if there are
     * no arguments left, return null.
     */
    public String peekNextArg() {
        return mArgs.peekNextArg();
    }

    /**
     * Return the next argument on the command line, whatever it is; if there are
     * no arguments left, throws an IllegalArgumentException to report this to the user.
     */
    public String nextArgRequired() {
        return mArgs.getNextArgRequired();
    }

    /**
     * Return the original raw argument list supplied to the command.
     */
    public String[] getRawArgs() {
        return mRawArgs;
    }
}
