/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.commands.settings;

import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.app.IActivityManager.ContentProviderHolder;
import android.content.IContentProvider;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;

public final class SettingsCmd {
    static final String TAG = "settings";

    enum CommandVerb {
        UNSPECIFIED,
        GET,
        PUT
    }

    static String[] mArgs;
    int mNextArg;
    int mUser = -1;     // unspecified
    CommandVerb mVerb = CommandVerb.UNSPECIFIED;
    String mTable = null;
    String mKey = null;
    String mValue = null;

    public static void main(String[] args) {
        if (args == null || args.length < 3) {
            printUsage();
            return;
        }

        mArgs = args;
        try {
            new SettingsCmd().run();
        } catch (Exception e) {
            System.err.println("Unable to run settings command");
        }
    }

    public void run() {
        boolean valid = false;
        String arg;
        try {
            while ((arg = nextArg()) != null) {
                if ("--user".equals(arg)) {
                    if (mUser != -1) {
                        // --user specified more than once; invalid
                        break;
                    }
                    mUser = Integer.parseInt(nextArg());
                } else if (mVerb == CommandVerb.UNSPECIFIED) {
                    if ("get".equalsIgnoreCase(arg)) {
                        mVerb = CommandVerb.GET;
                    } else if ("put".equalsIgnoreCase(arg)) {
                        mVerb = CommandVerb.PUT;
                    } else {
                        // invalid
                        System.err.println("Invalid command: " + arg);
                        break;
                    }
                } else if (mTable == null) {
                    if (!"system".equalsIgnoreCase(arg)
                            && !"secure".equalsIgnoreCase(arg)
                            && !"global".equalsIgnoreCase(arg)) {
                        System.err.println("Invalid namespace '" + arg + "'");
                        break;  // invalid
                    }
                    mTable = arg.toLowerCase();
                } else if (mVerb == CommandVerb.GET) {
                    mKey = arg;
                    if (mNextArg >= mArgs.length) {
                        valid = true;
                    } else {
                        System.err.println("Too many arguments");
                    }
                    break;
                } else if (mKey == null) {
                    mKey = arg;
                    // keep going; there's another PUT arg
                } else {    // PUT, final arg
                    mValue = arg;
                    if (mNextArg >= mArgs.length) {
                        valid = true;
                    } else {
                        System.err.println("Too many arguments");
                    }
                    break;
                }
            }
        } catch (Exception e) {
            valid = false;
        }

        if (valid) {
            if (mUser < 0) {
                mUser = UserHandle.USER_OWNER;
            }

            try {
                IActivityManager activityManager = ActivityManagerNative.getDefault();
                IContentProvider provider = null;
                IBinder token = new Binder();
                try {
                    ContentProviderHolder holder = activityManager.getContentProviderExternal(
                            "settings", UserHandle.USER_OWNER, token);
                    if (holder == null) {
                        throw new IllegalStateException("Could not find settings provider");
                    }
                    provider = holder.provider;

                    switch (mVerb) {
                        case GET:
                            System.out.println(getForUser(provider, mUser, mTable, mKey));
                            break;
                        case PUT:
                            putForUser(provider, mUser, mTable, mKey, mValue);
                            break;
                        default:
                            System.err.println("Unspecified command");
                            break;
                    }

                } finally {
                    if (provider != null) {
                        activityManager.removeContentProviderExternal("settings", token);
                    }
                }
            } catch (Exception e) {
                System.err.println("Error while accessing settings provider");
                e.printStackTrace();
            }

        } else {
            printUsage();
        }
    }

    private String nextArg() {
        if (mNextArg >= mArgs.length) {
            return null;
        }
        String arg = mArgs[mNextArg];
        mNextArg++;
        return arg;
    }

    String getForUser(IContentProvider provider, int userHandle,
            final String table, final String key) {
        final String callGetCommand;
        if ("system".equals(table)) callGetCommand = Settings.CALL_METHOD_GET_SYSTEM;
        else if ("secure".equals(table)) callGetCommand = Settings.CALL_METHOD_GET_SECURE;
        else if ("global".equals(table)) callGetCommand = Settings.CALL_METHOD_GET_GLOBAL;
        else {
            System.err.println("Invalid table; no put performed");
            throw new IllegalArgumentException("Invalid table " + table);
        }

        String result = null;
        try {
            Bundle arg = new Bundle();
            arg.putInt(Settings.CALL_METHOD_USER_KEY, userHandle);
            Bundle b = provider.call(null, callGetCommand, key, arg);
            if (b != null) {
                result = b.getPairValue();
            }
        } catch (RemoteException e) {
            System.err.println("Can't read key " + key + " in " + table + " for user " + userHandle);
        }
        return result;
    }

    void putForUser(IContentProvider provider, int userHandle,
            final String table, final String key, final String value) {
        final String callPutCommand;
        if ("system".equals(table)) callPutCommand = Settings.CALL_METHOD_PUT_SYSTEM;
        else if ("secure".equals(table)) callPutCommand = Settings.CALL_METHOD_PUT_SECURE;
        else if ("global".equals(table)) callPutCommand = Settings.CALL_METHOD_PUT_GLOBAL;
        else {
            System.err.println("Invalid table; no put performed");
            return;
        }

        try {
            Bundle arg = new Bundle();
            arg.putString(Settings.NameValueTable.VALUE, value);
            arg.putInt(Settings.CALL_METHOD_USER_KEY, userHandle);
            provider.call(null, callPutCommand, key, arg);
        } catch (RemoteException e) {
            System.err.println("Can't set key " + key + " in " + table + " for user " + userHandle);
        }
    }

    private static void printUsage() {
        System.err.println("usage:  settings [--user NUM] get namespace key");
        System.err.println("        settings [--user NUM] put namespace key value");
        System.err.println("\n'namespace' is one of {system, secure, global}, case-insensitive");
        System.err.println("If '--user NUM' is not given, the operations are performed on the owner user.");
    }
}
