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

import java.io.PrintStream;

public abstract class BaseCommand {

    protected String[] mArgs;
    private int mNextArg;
    private String mCurArgData;

    // These are magic strings understood by the Eclipse plugin.
    public static final String FATAL_ERROR_CODE = "Error type 1";
    public static final String NO_SYSTEM_ERROR_CODE = "Error type 2";
    public static final String NO_CLASS_ERROR_CODE = "Error type 3";

    /**
     * Call to run the command.
     */
    public void run(String[] args) {
        if (args.length < 1) {
            onShowUsage(System.out);
            return;
        }

        mArgs = args;
        mNextArg = 0;
        mCurArgData = null;

        try {
            onRun();
        } catch (IllegalArgumentException e) {
            onShowUsage(System.err);
            System.err.println();
            System.err.println("Error: " + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace(System.err);
            System.exit(1);
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

    /**
     * Return the next argument on the command line, whatever it is; if there are
     * no arguments left, return null.
     */
    public String nextArg() {
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

    /**
     * Return the next argument on the command line, whatever it is; if there are
     * no arguments left, throws an IllegalArgumentException to report this to the user.
     */
    public String nextArgRequired() {
        String arg = nextArg();
        if (arg == null) {
            String prev = mArgs[mNextArg - 1];
            throw new IllegalArgumentException("Argument expected after \"" + prev + "\"");
        }
        return arg;
    }
}
