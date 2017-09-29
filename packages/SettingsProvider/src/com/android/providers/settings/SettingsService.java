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
import android.database.Cursor;
import android.net.Uri;
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

        int mUser = -1;     // unspecified
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
            if (cmd == null) {
                return handleDefaultCommands(cmd);
            }

            final PrintWriter perr = getErrPrintWriter();

            boolean valid = false;
            String arg = cmd;
            do {
                if ("--user".equals(arg)) {
                    if (mUser != -1) {
                        // --user specified more than once; invalid
                        break;
                    }
                    arg = getNextArgRequired();
                    if ("current".equals(arg) || "cur".equals(arg)) {
                        mUser = UserHandle.USER_CURRENT;
                    } else {
                        mUser = Integer.parseInt(arg);
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

            if (mUser == UserHandle.USER_CURRENT) {
                try {
                    mUser = ActivityManager.getService().getCurrentUser().id;
                } catch (RemoteException e) {
                    throw new RuntimeException("Failed in IPC", e);
                }
            }
            if (mUser < 0) {
                mUser = UserHandle.USER_SYSTEM;
            } else if (mVerb == CommandVerb.DELETE || mVerb == CommandVerb.LIST) {
                perr.println("--user not supported for delete and list.");
                return -1;
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

        private List<String> listForUser(IContentProvider provider, int userHandle, String table) {
            final Uri uri = "system".equals(table) ? Settings.System.CONTENT_URI
                    : "secure".equals(table) ? Settings.Secure.CONTENT_URI
                    : "global".equals(table) ? Settings.Global.CONTENT_URI
                    : null;
            final ArrayList<String> lines = new ArrayList<String>();
            if (uri == null) {
                return lines;
            }
            try {
                final Cursor cursor = provider.query(resolveCallingPackage(), uri, null, null,
                        null);
                try {
                    while (cursor != null && cursor.moveToNext()) {
                        lines.add(cursor.getString(1) + "=" + cursor.getString(2));
                    }
                } finally {
                    if (cursor != null) {
                        cursor.close();
                    }
                }
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
                Bundle b = provider.call(resolveCallingPackage(), callGetCommand, key, arg);
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
                provider.call(resolveCallingPackage(), callPutCommand, key, arg);
            } catch (RemoteException e) {
                throw new RuntimeException("Failed in IPC", e);
            }
        }

        int deleteForUser(IContentProvider provider, int userHandle,
                final String table, final String key) {
            Uri targetUri;
            if ("system".equals(table)) targetUri = Settings.System.getUriFor(key);
            else if ("secure".equals(table)) targetUri = Settings.Secure.getUriFor(key);
            else if ("global".equals(table)) targetUri = Settings.Global.getUriFor(key);
            else {
                getErrPrintWriter().println("Invalid table; no delete performed");
                throw new IllegalArgumentException("Invalid table " + table);
            }

            int num = 0;
            try {
                num = provider.delete(resolveCallingPackage(), targetUri, null, null);
            } catch (RemoteException e) {
                throw new RuntimeException("Failed in IPC", e);
            }
            return num;
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
                provider.call(packageName, callResetCommand, null, arg);
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
                pw.println("  delete NAMESPACE KEY");
                pw.println("      Delete the entry for KEY.");
                pw.println("  reset [--user <USER_ID> | current] NAMESPACE {PACKAGE_NAME | RESET_MODE}");
                pw.println("      Reset the global/secure table for a package with mode.");
                pw.println("      RESET_MODE is one of {untrusted_defaults, untrusted_clear, trusted_defaults}, case-insensitive");
                pw.println("  list NAMESPACE");
                pw.println("      Print all defined keys.");
                pw.println("      NAMESPACE is one of {system, secure, global}, case-insensitive");
            }
        }
    }
}

