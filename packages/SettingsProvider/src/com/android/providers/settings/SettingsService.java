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

package com.android.providers.settings;

import android.app.ActivityManager;
import android.content.IContentProvider;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final public class SettingsService extends Binder {
    final SettingsProvider mProvider;

    public SettingsService(SettingsProvider provider) {
        mProvider = provider;
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
        (new MyShellCommand(mProvider, false)).exec(
                this, in, out, err, args, callback, resultReceiver);
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mProvider.getContext().checkCallingPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump SettingsProvider from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " without permission "
                    + android.Manifest.permission.DUMP);
            return;
        }

        int opti = 0;
        boolean dumpAsProto = false;
        while (opti < args.length) {
            String opt = args[opti];
            if (opt == null || opt.length() <= 0 || opt.charAt(0) != '-') {
                break;
            }
            opti++;
            if ("-h".equals(opt)) {
                MyShellCommand.dumpHelp(pw, true);
                return;
            } else if ("--proto".equals(opt)) {
                dumpAsProto = true;
            } else {
                pw.println("Unknown argument: " + opt + "; use -h for help");
            }
        }

        final long ident = Binder.clearCallingIdentity();
        try {
            if (dumpAsProto) {
                mProvider.dumpProto(fd);
            } else {
                mProvider.dumpInternal(fd, pw, args);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    final static class MyShellCommand extends ShellCommand {
        final SettingsProvider mProvider;
        final boolean mDumping;

        enum CommandVerb {
            UNSPECIFIED,
            GET,
            PUT,
            DELETE,
            LIST,
            RESET,
        }

        int mUser = UserHandle.USER_NULL;
        CommandVerb mVerb = CommandVerb.UNSPECIFIED;
        String mTable = null;
        String mKey = null;
        String mValue = null;
        String mPackageName = null;
        String mTag = null;
        int mResetMode = -1;
        boolean mMakeDefault;

        MyShellCommand(SettingsProvider provider, boolean dumping) {
            mProvider = provider;
            mDumping = dumping;
        }

        @Override
        public int onCommand(String cmd) {
            if (cmd == null || "help".equals(cmd) || "-h".equals(cmd)) {
                return handleDefaultCommands(cmd);
            }

            final PrintWriter perr = getErrPrintWriter();

            boolean valid = false;
            String arg = cmd;
            do {
                if ("--user".equals(arg)) {
                    if (mUser != UserHandle.USER_NULL) {
                        perr.println("Invalid user: --user specified more than once");
                        break;
                    }
                    mUser = UserHandle.parseUserArg(getNextArgRequired());

                    if (mUser == UserHandle.USER_ALL) {
                        perr.println("Invalid user: all");
                        return -1;
                    }
                } else if (mVerb == CommandVerb.UNSPECIFIED) {
                    if ("get".equalsIgnoreCase(arg)) {
                        mVerb = CommandVerb.GET;
                    } else if ("put".equalsIgnoreCase(arg)) {
                        mVerb = CommandVerb.PUT;
                    } else if ("delete".equalsIgnoreCase(arg)) {
                        mVerb = CommandVerb.DELETE;
                    } else if ("list".equalsIgnoreCase(arg)) {
                        mVerb = CommandVerb.LIST;
                    } else if ("reset".equalsIgnoreCase(arg)) {
                        mVerb = CommandVerb.RESET;
                    } else {
                        // invalid
                        perr.println("Invalid command: " + arg);
                        return -1;
                    }
                } else if (mTable == null) {
                    if (!"system".equalsIgnoreCase(arg)
                            && !"secure".equalsIgnoreCase(arg)
                            && !"global".equalsIgnoreCase(arg)) {
                        perr.println("Invalid namespace '" + arg + "'");
                        return -1;
                    }
                    mTable = arg.toLowerCase();
                    if (mVerb == CommandVerb.LIST) {
                        valid = true;
                        break;
                    }
                } else if (mVerb == CommandVerb.RESET) {
                    if ("untrusted_defaults".equalsIgnoreCase(arg)) {
                        mResetMode = Settings.RESET_MODE_UNTRUSTED_DEFAULTS;
                    } else if ("untrusted_clear".equalsIgnoreCase(arg)) {
                        mResetMode = Settings.RESET_MODE_UNTRUSTED_CHANGES;
                    } else if ("trusted_defaults".equalsIgnoreCase(arg)) {
                        mResetMode = Settings.RESET_MODE_TRUSTED_DEFAULTS;
                    } else {
                        mPackageName = arg;
                        mResetMode = Settings.RESET_MODE_PACKAGE_DEFAULTS;
                        if (peekNextArg() == null) {
                            valid = true;
                        } else {
                            mTag = getNextArg();
                            if (peekNextArg() == null) {
                                valid = true;
                            } else {
                                perr.println("Too many arguments");
                                return -1;
                            }
                        }
                        break;
                    }
                    if (peekNextArg() == null) {
                        valid = true;
                    } else {
                        perr.println("Too many arguments");
                        return -1;
                    }
                } else if (mVerb == CommandVerb.GET || mVerb == CommandVerb.DELETE) {
                    mKey = arg;
                    if (peekNextArg() == null) {
                        valid = true;
                    } else {
                        perr.println("Too many arguments");
                        return -1;
                    }
                    break;
                } else if (mKey == null) {
                    mKey = arg;
                    // keep going; there's another PUT arg
                } else if (mValue == null) {
                    mValue = arg;
                    // what we have so far is a valid command
                    valid = true;
                    // keep going; there may be another PUT arg
                } else if (mTag == null) {
                    mTag = arg;
                    if ("default".equalsIgnoreCase(mTag)) {
                        mTag = null;
                        mMakeDefault = true;
                        if (peekNextArg() == null) {
                            valid = true;
                        } else {
                            perr.println("Too many arguments");
                            return -1;
                        }
                        break;
                    }
                    if (peekNextArg() == null) {
                        valid = true;
                        break;
                    }
                } else { // PUT, final arg
                    if (!"default".equalsIgnoreCase(arg)) {
                        perr.println("Argument expected to be 'default'");
                        return -1;
                    }
                    mMakeDefault = true;
                    if (peekNextArg() == null) {
                        valid = true;
                    } else {
                        perr.println("Too many arguments");
                        return -1;
                    }
                    break;
                }
            } while ((arg = getNextArg()) != null);

            if (!valid) {
                perr.println("Bad arguments");
                return -1;
            }

            if (mUser == UserHandle.USER_NULL || mUser == UserHandle.USER_CURRENT) {
                try {
                    mUser = ActivityManager.getService().getCurrentUser().id;
                } catch (RemoteException e) {
                    throw new RuntimeException("Failed in IPC", e);
                }
            }
            UserManager userManager = UserManager.get(mProvider.getContext());
            if (userManager.getUserInfo(mUser) == null) {
                perr.println("Invalid user: " + mUser);
                return -1;
            }

            final IContentProvider iprovider = mProvider.getIContentProvider();
            final PrintWriter pout = getOutPrintWriter();
            switch (mVerb) {
                case GET:
                    pout.println(getForUser(iprovider, mUser, mTable, mKey));
                    break;
                case PUT:
                    putForUser(iprovider, mUser, mTable, mKey, mValue, mTag, mMakeDefault);
                    break;
                case DELETE:
                    pout.println("Deleted "
                            + deleteForUser(iprovider, mUser, mTable, mKey) + " rows");
                    break;
                case LIST:
                    for (String line : listForUser(iprovider, mUser, mTable)) {
                        pout.println(line);
                    }
                    break;
                case RESET:
                    resetForUser(iprovider, mUser, mTable, mTag);
                    break;
                default:
                    perr.println("Unspecified command");
                    return -1;
            }

            return 0;
        }

        List<String> listForUser(IContentProvider provider, int userHandle, String table) {
            final String callListCommand;
            if ("system".equals(table)) callListCommand = Settings.CALL_METHOD_LIST_SYSTEM;
            else if ("secure".equals(table)) callListCommand = Settings.CALL_METHOD_LIST_SECURE;
            else if ("global".equals(table)) callListCommand = Settings.CALL_METHOD_LIST_GLOBAL;
            else {
                getErrPrintWriter().println("Invalid table; no list performed");
                throw new IllegalArgumentException("Invalid table " + table);
            }
            final ArrayList<String> lines = new ArrayList<String>();
            try {
                Bundle arg = new Bundle();
                arg.putInt(Settings.CALL_METHOD_USER_KEY, userHandle);
                Bundle result = provider.call(resolveCallingPackage(), null, Settings.AUTHORITY,
                        callListCommand, null, arg);
                lines.addAll(result.getStringArrayList(SettingsProvider.RESULT_SETTINGS_LIST));
                Collections.sort(lines);
            } catch (RemoteException e) {
                throw new RuntimeException("Failed in IPC", e);
            }
            return lines;
        }

        String getForUser(IContentProvider provider, int userHandle,
                final String table, final String key) {
            final String callGetCommand;
            if ("system".equals(table)) callGetCommand = Settings.CALL_METHOD_GET_SYSTEM;
            else if ("secure".equals(table)) callGetCommand = Settings.CALL_METHOD_GET_SECURE;
            else if ("global".equals(table)) callGetCommand = Settings.CALL_METHOD_GET_GLOBAL;
            else {
                getErrPrintWriter().println("Invalid table; no put performed");
                throw new IllegalArgumentException("Invalid table " + table);
            }

            String result = null;
            try {
                Bundle arg = new Bundle();
                arg.putInt(Settings.CALL_METHOD_USER_KEY, userHandle);
                Bundle b = provider.call(resolveCallingPackage(), null, Settings.AUTHORITY,
                        callGetCommand, key, arg);
                if (b != null) {
                    result = b.getPairValue();
                }
            } catch (RemoteException e) {
                throw new RuntimeException("Failed in IPC", e);
            }
            return result;
        }

        void putForUser(IContentProvider provider, int userHandle, final String table,
                final String key, final String value, String tag, boolean makeDefault) {
            final String callPutCommand;
            if ("system".equals(table)) {
                callPutCommand = Settings.CALL_METHOD_PUT_SYSTEM;
                if (makeDefault) {
                    getOutPrintWriter().print("Ignored makeDefault - "
                            + "doesn't apply to system settings");
                    makeDefault = false;
                }
            } else if ("secure".equals(table)) callPutCommand = Settings.CALL_METHOD_PUT_SECURE;
            else if ("global".equals(table)) callPutCommand = Settings.CALL_METHOD_PUT_GLOBAL;
            else {
                getErrPrintWriter().println("Invalid table; no put performed");
                return;
            }

            try {
                Bundle arg = new Bundle();
                arg.putString(Settings.NameValueTable.VALUE, value);
                arg.putInt(Settings.CALL_METHOD_USER_KEY, userHandle);
                if (tag != null) {
                    arg.putString(Settings.CALL_METHOD_TAG_KEY, tag);
                }
                if (makeDefault) {
                    arg.putBoolean(Settings.CALL_METHOD_MAKE_DEFAULT_KEY, true);
                }
                provider.call(resolveCallingPackage(), null, Settings.AUTHORITY,
                        callPutCommand, key, arg);
            } catch (RemoteException e) {
                throw new RuntimeException("Failed in IPC", e);
            }
        }

        int deleteForUser(IContentProvider provider, int userHandle,
                final String table, final String key) {
            final String callDeleteCommand;
            if ("system".equals(table)) {
                callDeleteCommand = Settings.CALL_METHOD_DELETE_SYSTEM;
            } else if ("secure".equals(table)) {
                callDeleteCommand = Settings.CALL_METHOD_DELETE_SECURE;
            } else if ("global".equals(table)) {
                callDeleteCommand = Settings.CALL_METHOD_DELETE_GLOBAL;
            } else {
                getErrPrintWriter().println("Invalid table; no delete performed");
                throw new IllegalArgumentException("Invalid table " + table);
            }

            try {
                Bundle arg = new Bundle();
                arg.putInt(Settings.CALL_METHOD_USER_KEY, userHandle);
                Bundle result = provider.call(resolveCallingPackage(), null, Settings.AUTHORITY,
                        callDeleteCommand, key, arg);
                return result.getInt(SettingsProvider.RESULT_ROWS_DELETED);
            } catch (RemoteException e) {
                throw new RuntimeException("Failed in IPC", e);
            }
        }

        void resetForUser(IContentProvider provider, int userHandle,
                String table, String tag) {
            final String callResetCommand;
            if ("secure".equals(table)) callResetCommand = Settings.CALL_METHOD_RESET_SECURE;
            else if ("global".equals(table)) callResetCommand = Settings.CALL_METHOD_RESET_GLOBAL;
            else {
                getErrPrintWriter().println("Invalid table; no reset performed");
                return;
            }

            try {
                Bundle arg = new Bundle();
                arg.putInt(Settings.CALL_METHOD_USER_KEY, userHandle);
                arg.putInt(Settings.CALL_METHOD_RESET_MODE_KEY, mResetMode);
                if (tag != null) {
                    arg.putString(Settings.CALL_METHOD_TAG_KEY, tag);
                }
                String packageName = mPackageName != null ? mPackageName : resolveCallingPackage();
                arg.putInt(Settings.CALL_METHOD_USER_KEY, userHandle);
                provider.call(packageName, null, Settings.AUTHORITY, callResetCommand, null, arg);
            } catch (RemoteException e) {
                throw new RuntimeException("Failed in IPC", e);
            }
        }

        public static String resolveCallingPackage() {
            switch (Binder.getCallingUid()) {
                case Process.ROOT_UID: {
                    return "root";
                }

                case Process.SHELL_UID: {
                    return "com.android.shell";
                }

                default: {
                    return null;
                }
            }
        }

        @Override
        public void onHelp() {
            PrintWriter pw = getOutPrintWriter();
            dumpHelp(pw, mDumping);
        }

        static void dumpHelp(PrintWriter pw, boolean dumping) {
            if (dumping) {
                pw.println("Settings provider dump options:");
                pw.println("  [-h] [--proto]");
                pw.println("  -h: print this help.");
                pw.println("  --proto: dump as protobuf.");
            } else {
                pw.println("Settings provider (settings) commands:");
                pw.println("  help");
                pw.println("      Print this help text.");
                pw.println("  get [--user <USER_ID> | current] NAMESPACE KEY");
                pw.println("      Retrieve the current value of KEY.");
                pw.println("  put [--user <USER_ID> | current] NAMESPACE KEY VALUE [TAG] [default]");
                pw.println("      Change the contents of KEY to VALUE.");
                pw.println("      TAG to associate with the setting.");
                pw.println("      {default} to set as the default, case-insensitive only for global/secure namespace");
                pw.println("  delete [--user <USER_ID> | current] NAMESPACE KEY");
                pw.println("      Delete the entry for KEY.");
                pw.println("  reset [--user <USER_ID> | current] NAMESPACE {PACKAGE_NAME | RESET_MODE}");
                pw.println("      Reset the global/secure table for a package with mode.");
                pw.println("      RESET_MODE is one of {untrusted_defaults, untrusted_clear, trusted_defaults}, case-insensitive");
                pw.println("  list [--user <USER_ID> | current] NAMESPACE");
                pw.println("      Print all defined keys.");
                pw.println("      NAMESPACE is one of {system, secure, global}, case-insensitive");
            }
        }
    }
}

