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

package com.android.providers.settings;

import static android.provider.Settings.Config.SYNC_DISABLED_MODE_NONE;
import static android.provider.Settings.Config.SYNC_DISABLED_MODE_PERSISTENT;
import static android.provider.Settings.Config.SYNC_DISABLED_MODE_UNTIL_REBOOT;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.AttributionSource;
import android.content.IContentProvider;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.provider.DeviceConfig;
import android.provider.Settings;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Receives shell commands from the command line related to device config flags, and dispatches them
 * to the SettingsProvider.
 */
public final class DeviceConfigService extends Binder {
    final SettingsProvider mProvider;

    public DeviceConfigService(SettingsProvider provider) {
        mProvider = provider;
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
        (new MyShellCommand(mProvider)).exec(this, in, out, err, args, callback, resultReceiver);
    }

    static final class MyShellCommand extends ShellCommand {
        final SettingsProvider mProvider;

        enum CommandVerb {
            GET,
            PUT,
            DELETE,
            LIST,
            RESET,
            SET_SYNC_DISABLED_FOR_TESTS,
            IS_SYNC_DISABLED_FOR_TESTS,
        }

        MyShellCommand(SettingsProvider provider) {
            mProvider = provider;
        }

        @SuppressLint("AndroidFrameworkRequiresPermission")
        @Override
        public int onCommand(String cmd) {
            if (cmd == null || "help".equals(cmd) || "-h".equals(cmd)) {
                onHelp();
                return -1;
            }

            final PrintWriter perr = getErrPrintWriter();
            boolean isValid = false;

            CommandVerb verb;
            if ("get".equalsIgnoreCase(cmd)) {
                verb = CommandVerb.GET;
            } else if ("put".equalsIgnoreCase(cmd)) {
                verb = CommandVerb.PUT;
            } else if ("delete".equalsIgnoreCase(cmd)) {
                verb = CommandVerb.DELETE;
            } else if ("list".equalsIgnoreCase(cmd)) {
                verb = CommandVerb.LIST;
                if (peekNextArg() == null) {
                    isValid = true;
                }
            } else if ("reset".equalsIgnoreCase(cmd)) {
                verb = CommandVerb.RESET;
            } else if ("set_sync_disabled_for_tests".equalsIgnoreCase(cmd)) {
                verb = CommandVerb.SET_SYNC_DISABLED_FOR_TESTS;
            } else if ("is_sync_disabled_for_tests".equalsIgnoreCase(cmd)) {
                verb = CommandVerb.IS_SYNC_DISABLED_FOR_TESTS;
                if (peekNextArg() != null) {
                    perr.println("Bad arguments");
                    return -1;
                }
                isValid = true;
            } else {
                // invalid
                perr.println("Invalid command: " + cmd);
                return -1;
            }

            // Parse args for those commands that have them.
            int disableSyncMode = -1;
            int resetMode = -1;
            boolean makeDefault = false;
            String namespace = null;
            String key = null;
            String value = null;
            String arg;
            while ((arg = getNextArg()) != null) {
                if (verb == CommandVerb.RESET) {
                    if (resetMode == -1) {
                        // RESET 1st arg (required)
                        if ("untrusted_defaults".equalsIgnoreCase(arg)) {
                            resetMode = Settings.RESET_MODE_UNTRUSTED_DEFAULTS;
                        } else if ("untrusted_clear".equalsIgnoreCase(arg)) {
                            resetMode = Settings.RESET_MODE_UNTRUSTED_CHANGES;
                        } else if ("trusted_defaults".equalsIgnoreCase(arg)) {
                            resetMode = Settings.RESET_MODE_TRUSTED_DEFAULTS;
                        } else {
                            // invalid
                            perr.println("Invalid reset mode: " + arg);
                            return -1;
                        }
                        if (peekNextArg() == null) {
                            isValid = true;
                        }
                    } else {
                        // RESET 2nd arg (optional)
                        namespace = arg;
                        if (peekNextArg() == null) {
                            isValid = true;
                        } else {
                            // invalid
                            perr.println("Too many arguments");
                            return -1;
                        }
                    }
                } else if (verb == CommandVerb.SET_SYNC_DISABLED_FOR_TESTS) {
                    if (disableSyncMode == -1) {
                        // DISABLE_SYNC_FOR_TESTS 1st arg (required)
                        if ("none".equalsIgnoreCase(arg)) {
                            disableSyncMode = SYNC_DISABLED_MODE_NONE;
                        } else if ("persistent".equalsIgnoreCase(arg)) {
                            disableSyncMode = SYNC_DISABLED_MODE_PERSISTENT;
                        } else if ("until_reboot".equalsIgnoreCase(arg)) {
                            disableSyncMode = SYNC_DISABLED_MODE_UNTIL_REBOOT;
                        } else {
                            // invalid
                            perr.println("Invalid sync disabled mode: " + arg);
                            return -1;
                        }
                        if (peekNextArg() == null) {
                            isValid = true;
                        }
                    }
                } else if (namespace == null) {
                    // GET, PUT, DELETE, LIST 1st arg
                    namespace = arg;
                    if (verb == CommandVerb.LIST) {
                        if (peekNextArg() == null) {
                            isValid = true;
                        } else {
                            // invalid
                            perr.println("Too many arguments");
                            return -1;
                        }
                    }
                } else if (key == null) {
                    // GET, PUT, DELETE 2nd arg
                    key = arg;
                    if ((verb == CommandVerb.GET || verb == CommandVerb.DELETE)) {
                        // GET, DELETE only have 2 args
                        if (peekNextArg() == null) {
                            isValid = true;
                        } else {
                            // invalid
                            perr.println("Too many arguments");
                            return -1;
                        }
                    }
                } else if (value == null) {
                    // PUT 3rd arg (required)
                    value = arg;
                    if (verb == CommandVerb.PUT && peekNextArg() == null) {
                        isValid = true;
                    }
                } else if ("default".equalsIgnoreCase(arg)) {
                    // PUT 4th arg (optional)
                    makeDefault = true;
                    if (verb == CommandVerb.PUT && peekNextArg() == null) {
                        isValid = true;
                    } else {
                        // invalid
                        perr.println("Too many arguments");
                        return -1;
                    }
                }
            }

            if (!isValid) {
                perr.println("Bad arguments");
                return -1;
            }

            final IContentProvider iprovider = mProvider.getIContentProvider();
            final PrintWriter pout = getOutPrintWriter();
            switch (verb) {
                case GET:
                    pout.println(DeviceConfig.getProperty(namespace, key));
                    break;
                case PUT:
                    DeviceConfig.setProperty(namespace, key, value, makeDefault);
                    break;
                case DELETE:
                    pout.println(delete(iprovider, namespace, key)
                            ? "Successfully deleted " + key + " from " + namespace
                            : "Failed to delete " + key + " from " + namespace);
                    break;
                case LIST:
                    if (namespace != null) {
                        DeviceConfig.Properties properties = DeviceConfig.getProperties(namespace);
                        List<String> keys = new ArrayList<>(properties.getKeyset());
                        Collections.sort(keys);
                        for (String name : keys) {
                            pout.println(name + "=" + properties.getString(name, null));
                        }
                    } else {
                        for (String line : listAll(iprovider)) {
                            pout.println(line);
                        }
                    }
                    break;
                case RESET:
                    DeviceConfig.resetToDefaults(resetMode, namespace);
                    break;
                case SET_SYNC_DISABLED_FOR_TESTS:
                    DeviceConfig.setSyncDisabled(disableSyncMode);
                    break;
                case IS_SYNC_DISABLED_FOR_TESTS:
                    pout.println(DeviceConfig.isSyncDisabled());
                    break;
                default:
                    perr.println("Unspecified command");
                    return -1;
            }
            return 0;
        }

        @Override
        public void onHelp() {
            PrintWriter pw = getOutPrintWriter();
            pw.println("Device Config (device_config) commands:");
            pw.println("  help");
            pw.println("      Print this help text.");
            pw.println("  get NAMESPACE KEY");
            pw.println("      Retrieve the current value of KEY from the given NAMESPACE.");
            pw.println("  put NAMESPACE KEY VALUE [default]");
            pw.println("      Change the contents of KEY to VALUE for the given NAMESPACE.");
            pw.println("      {default} to set as the default value.");
            pw.println("  delete NAMESPACE KEY");
            pw.println("      Delete the entry for KEY for the given NAMESPACE.");
            pw.println("  list [NAMESPACE]");
            pw.println("      Print all keys and values defined, optionally for the given "
                    + "NAMESPACE.");
            pw.println("  reset RESET_MODE [NAMESPACE]");
            pw.println("      Reset all flag values, optionally for a NAMESPACE, according to "
                    + "RESET_MODE.");
            pw.println("      RESET_MODE is one of {untrusted_defaults, untrusted_clear, "
                    + "trusted_defaults}");
            pw.println("      NAMESPACE limits which flags are reset if provided, otherwise all "
                    + "flags are reset");
            pw.println("  set_sync_disabled_for_tests SYNC_DISABLED_MODE");
            pw.println("      Modifies bulk property setting behavior for tests. When in one of the"
                    + " disabled modes this ensures that config isn't overwritten.");
            pw.println("      SYNC_DISABLED_MODE is one of:");
            pw.println("        none: Sync is not disabled. A reboot may be required to restart"
                    + " syncing.");
            pw.println("        persistent: Sync is disabled, this state will survive a reboot.");
            pw.println("        until_reboot: Sync is disabled until the next reboot.");
            pw.println("  is_sync_disabled_for_tests");
            pw.println("      Prints 'true' if sync is disabled, 'false' otherwise.");
        }

        private boolean delete(IContentProvider provider, String namespace, String key) {
            String compositeKey = namespace + "/" + key;
            boolean success;

            try {
                Bundle args = new Bundle();
                args.putInt(Settings.CALL_METHOD_USER_KEY,
                        ActivityManager.getService().getCurrentUser().id);
                Bundle b = provider.call(new AttributionSource(Process.myUid(),
                                resolveCallingPackage(), null), Settings.AUTHORITY,
                        Settings.CALL_METHOD_DELETE_CONFIG, compositeKey, args);
                success = (b != null && b.getInt(SettingsProvider.RESULT_ROWS_DELETED) == 1);
            } catch (RemoteException e) {
                throw new RuntimeException("Failed in IPC", e);
            }
            return success;
        }

        private List<String> listAll(IContentProvider provider) {
            final ArrayList<String> lines = new ArrayList<>();

            try {
                Bundle args = new Bundle();
                args.putInt(Settings.CALL_METHOD_USER_KEY,
                        ActivityManager.getService().getCurrentUser().id);
                Bundle b = provider.call(new AttributionSource(Process.myUid(),
                                resolveCallingPackage(), null), Settings.AUTHORITY,
                        Settings.CALL_METHOD_LIST_CONFIG, null, args);
                if (b != null) {
                    Map<String, String> flagsToValues =
                            (HashMap) b.getSerializable(Settings.NameValueTable.VALUE);
                    for (String key : flagsToValues.keySet()) {
                        lines.add(key + "=" + flagsToValues.get(key));
                    }
                }

                Collections.sort(lines);
            } catch (RemoteException e) {
                throw new RuntimeException("Failed in IPC", e);
            }
            return lines;
        }

        private static String resolveCallingPackage() {
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
    }
}
