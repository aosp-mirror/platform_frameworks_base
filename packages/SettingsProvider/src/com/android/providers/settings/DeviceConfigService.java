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

import static com.android.providers.settings.Flags.supportOverrides;

import android.aconfig.Aconfig.parsed_flag;
import android.aconfig.Aconfig.parsed_flags;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.AttributionSource;
import android.content.IContentProvider;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.provider.DeviceConfig;
import android.provider.DeviceConfigShellCommandHandler;
import android.provider.Settings;
import android.provider.Settings.Config.SyncDisabledMode;
import android.provider.UpdatableDeviceConfigServiceReadiness;
import android.util.Slog;

import com.android.internal.util.FastPrintWriter;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Receives shell commands from the command line related to device config flags, and dispatches them
 * to the SettingsProvider.
 */
public final class DeviceConfigService extends Binder {
    private static final List<String> sAconfigTextProtoFilesOnDevice = List.of(
            "/system/etc/aconfig_flags.pb",
            "/system_ext/etc/aconfig_flags.pb",
            "/product/etc/aconfig_flags.pb",
            "/vendor/etc/aconfig_flags.pb");

    private static final List<String> PRIVATE_NAMESPACES = List.of(
            "device_config_overrides",
            "staged",
            "token_staged");

    final SettingsProvider mProvider;

    private static final String TAG = "DeviceConfigService";

    public DeviceConfigService(SettingsProvider provider) {
        mProvider = provider;
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback callback, ResultReceiver resultReceiver)
            throws RemoteException {
        if (UpdatableDeviceConfigServiceReadiness.shouldStartUpdatableService()) {
            callUpdableDeviceConfigShellCommandHandler(in, out, err, args, resultReceiver);
        } else {
            (new MyShellCommand(mProvider))
                    .exec(this, in, out, err, args, callback, resultReceiver);
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        final IContentProvider iprovider = mProvider.getIContentProvider();
        pw.println("DeviceConfig flags:");
        for (String line : MyShellCommand.listAll(iprovider)) {
            pw.println(line);
        }

        ArrayList<String> missingFiles = new ArrayList<String>();
        for (String fileName : sAconfigTextProtoFilesOnDevice) {
            File aconfigFile = new File(fileName);
            if (!aconfigFile.exists()) {
                missingFiles.add(fileName);
            }
        }

        if (missingFiles.isEmpty()) {
            pw.println("\nAconfig flags:");
            for (String name : MyShellCommand.listAllAconfigFlags(iprovider)) {
                pw.println(name);
            }
        } else {
            pw.println("\nFailed to dump aconfig flags due to missing files:");
            for (String fileName : missingFiles) {
                pw.println(fileName);
            }
        }
    }

    private static HashSet<String> getAconfigFlagNamesInDeviceConfig() {
        HashSet<String> nameSet = new HashSet<String>();
        try {
            for (String fileName : sAconfigTextProtoFilesOnDevice) {
                byte[] contents = (new FileInputStream(fileName)).readAllBytes();
                parsed_flags parsedFlags = parsed_flags.parseFrom(contents);
                if (parsedFlags == null) {
                    Slog.e(TAG, "failed to parse aconfig protobuf from " + fileName);
                    continue;
                }

                for (parsed_flag flag : parsedFlags.getParsedFlagList()) {
                    String namespace = flag.getNamespace();
                    String packageName = flag.getPackage();
                    String name = flag.getName();
                    nameSet.add(namespace + "/" + packageName + "." + name);
                }
            }
        } catch (IOException e) {
            Slog.e(TAG, "failed to read aconfig protobuf", e);
        }
        return nameSet;
    }

    private void callUpdableDeviceConfigShellCommandHandler(FileDescriptor in, FileDescriptor out,
            FileDescriptor err, String[] args, ResultReceiver resultReceiver) {
        int result = -1;
        try (
                ParcelFileDescriptor inPfd = ParcelFileDescriptor.dup(in);
                ParcelFileDescriptor outPfd = ParcelFileDescriptor.dup(out);
                ParcelFileDescriptor errPfd = ParcelFileDescriptor.dup(err)) {
            result = DeviceConfigShellCommandHandler.handleShellCommand(inPfd, outPfd, errPfd,
                    args);
        } catch (IOException e) {
            PrintWriter pw = new FastPrintWriter(new FileOutputStream(err));
            pw.println("dup() failed: " + e.getMessage());
            pw.flush();
        } finally {
            resultReceiver.send(result, null);
        }
    }

    static final class MyShellCommand extends ShellCommand {
        final SettingsProvider mProvider;

        enum CommandVerb {
            GET,
            PUT,
            OVERRIDE,
            CLEAR_OVERRIDE,
            DELETE,
            LIST,
            LIST_NAMESPACES,
            LIST_LOCAL_OVERRIDES,
            RESET,
            SET_SYNC_DISABLED_FOR_TESTS,
            GET_SYNC_DISABLED_FOR_TESTS,
        }

        MyShellCommand(SettingsProvider provider) {
            mProvider = provider;
        }

      public static HashMap<String, String> getAllFlags(IContentProvider provider) {
        HashMap<String, String> allFlags = new HashMap<String, String>();
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
                allFlags.putAll(flagsToValues);
            }
        } catch (RemoteException e) {
            throw new RuntimeException("Failed in IPC", e);
        }

        return allFlags;
      }

      public static List<String> listAll(IContentProvider provider) {
        HashMap<String, String> allFlags = getAllFlags(provider);
        final ArrayList<String> lines = new ArrayList<>();
        for (String key : allFlags.keySet()) {
          lines.add(key + "=" + allFlags.get(key));
        }
        Collections.sort(lines);
        return lines;
      }

      private static void log(String msg) {
        if (Build.IS_DEBUGGABLE) {
            Slog.wtf(TAG, msg);
        } else {
            Slog.e(TAG, msg);
        }
      }

      public static List<String> listAllAconfigFlags(IContentProvider provider) {
        HashMap<String, String> allFlags = getAllFlags(provider);
        HashSet<String> aconfigFlagNames = getAconfigFlagNamesInDeviceConfig();
        final ArrayList<String> lines = new ArrayList<>();
        for (String aconfigFlag : aconfigFlagNames) {
          String val = allFlags.get(aconfigFlag);
          if (val != null) {
            int idx = aconfigFlag.indexOf("/");
            if (idx == -1 || idx == aconfigFlag.length() - 1 || idx == 0) {
              log("invalid flag entry in device config: " + aconfigFlag);
              continue;
            }
            String aconfigFlagNameByPackage = aconfigFlag.substring(idx+1);
            String namespace = aconfigFlag.substring(0, idx);
            lines.add(aconfigFlagNameByPackage + " " + namespace + "=" + val);
          }
        }
        Collections.sort(lines);
        return lines;
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
            } else if (supportOverrides() && "override".equalsIgnoreCase(cmd)) {
                verb = CommandVerb.OVERRIDE;
            } else if (supportOverrides() && "clear_override".equalsIgnoreCase(cmd)) {
                verb = CommandVerb.CLEAR_OVERRIDE;
            } else if ("delete".equalsIgnoreCase(cmd)) {
                verb = CommandVerb.DELETE;
            } else if ("list".equalsIgnoreCase(cmd)) {
                verb = CommandVerb.LIST;
                if (peekNextArg() == null) {
                    isValid = true;
                }
            } else if ("list_namespaces".equalsIgnoreCase(cmd)) {
                verb = CommandVerb.LIST_NAMESPACES;
                if (peekNextArg() == null) {
                    isValid = true;
                }
            } else if (supportOverrides() && "list_local_overrides".equalsIgnoreCase(cmd)) {
                verb = CommandVerb.LIST_LOCAL_OVERRIDES;
                if (peekNextArg() == null) {
                    isValid = true;
                }
            } else if ("reset".equalsIgnoreCase(cmd)) {
                verb = CommandVerb.RESET;
            } else if ("set_sync_disabled_for_tests".equalsIgnoreCase(cmd)) {
                verb = CommandVerb.SET_SYNC_DISABLED_FOR_TESTS;
            } else if ("get_sync_disabled_for_tests".equalsIgnoreCase(cmd)) {
                verb = CommandVerb.GET_SYNC_DISABLED_FOR_TESTS;
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
            int syncDisabledModeArg = -1;
            int resetMode = -1;
            boolean makeDefault = false;
            String namespace = null;
            String key = null;
            String value = null;
            String arg;
            boolean publicOnly = false;
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
                    if (syncDisabledModeArg == -1) {
                        // SET_SYNC_DISABLED_FOR_TESTS 1st arg (required)
                        syncDisabledModeArg = parseSyncDisabledMode(arg);
                        if (syncDisabledModeArg == -1) {
                            // invalid
                            perr.println("Invalid sync disabled mode: " + arg);
                            return -1;
                        }
                        if (peekNextArg() == null) {
                            isValid = true;
                        }
                    }
                } else if (verb == CommandVerb.LIST_NAMESPACES) {
                    if (arg.equals("--public")) {
                        isValid = true;
                        publicOnly = true;
                    }
                } else if (namespace == null) {
                    // GET, PUT, OVERRIDE, DELETE, LIST 1st arg
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
                    // GET, PUT, OVERRIDE, DELETE 2nd arg
                    key = arg;
                    boolean validVerb = verb == CommandVerb.GET
                            || verb == CommandVerb.DELETE
                            || verb == CommandVerb.CLEAR_OVERRIDE;
                    if (validVerb) {
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
                    // PUT, OVERRIDE 3rd arg (required)
                    value = arg;
                    boolean validVerb = verb == CommandVerb.PUT
                            || verb == CommandVerb.OVERRIDE;
                    if (validVerb && peekNextArg() == null) {
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
                case OVERRIDE:
                    if (supportOverrides()) {
                        DeviceConfig.setLocalOverride(namespace, key, value);
                    }
                    break;
                case CLEAR_OVERRIDE:
                    if (supportOverrides()) {
                        DeviceConfig.clearLocalOverride(namespace, key);
                    }
                    break;
                case DELETE:
                    pout.println(delete(iprovider, namespace, key)
                            ? "Successfully deleted " + key + " from " + namespace
                            : "Failed to delete " + key + " from " + namespace);
                    break;
                case LIST:
                    if (namespace != null) {
                        DeviceConfig.Properties properties =
                                DeviceConfig.getProperties(namespace);
                        List<String> keys = new ArrayList<>(properties.getKeyset());
                        Collections.sort(keys);
                        for (String name : keys) {
                            pout.println(name + "=" + properties.getString(name, null));
                        }
                    } else {
                        for (String line : listAll(iprovider)) {
                            if (supportOverrides()) {
                                boolean isPrivate = false;
                                for (String privateNamespace : PRIVATE_NAMESPACES) {
                                    if (line.startsWith(privateNamespace)) {
                                        isPrivate = true;
                                        break;
                                    }
                                }

                                if (!isPrivate) {
                                    pout.println(line);
                                }
                            } else {
                                pout.println(line);
                            }
                        }
                    }
                    break;
                case LIST_NAMESPACES:
                    List<String> namespaces;
                    if (publicOnly) {
                        namespaces = DeviceConfig.getPublicNamespaces();
                    } else {
                        Field[] fields = DeviceConfig.class.getDeclaredFields();
                        namespaces = new ArrayList<>(fields.length);
                        // TODO(b/265948913): once moved to mainline, it should call a hidden method
                        // directly
                        for (Field field : fields) {
                            int modifiers = field.getModifiers();
                            try {
                                if (Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers)
                                        && field.getType().equals(String.class)
                                        && field.getName().startsWith("NAMESPACE_")) {
                                    namespaces.add((String) field.get(null));
                                }
                            } catch (IllegalAccessException ignored) { }
                        }
                    }
                    for (int i = 0; i < namespaces.size(); i++) {
                        pout.println(namespaces.get(i));
                    }
                    break;
                case LIST_LOCAL_OVERRIDES:
                    if (supportOverrides()) {
                        Map<String, Map<String, String>> underlyingValues =
                                DeviceConfig.getUnderlyingValuesForOverriddenFlags();
                        for (String overrideNamespace : underlyingValues.keySet()) {
                            Map<String, String> flagToValue =
                                    underlyingValues.get(overrideNamespace);
                            for (String flag : flagToValue.keySet()) {
                                String flagText = overrideNamespace + "/" + flag;
                                String valueText =
                                        DeviceConfig.getProperty(overrideNamespace, flag);
                                pout.println(flagText + "=" + valueText);
                            }
                        }
                    }
                    break;
                case RESET:
                    DeviceConfig.resetToDefaults(resetMode, namespace);
                    break;
                case SET_SYNC_DISABLED_FOR_TESTS:
                    DeviceConfig.setSyncDisabledMode(syncDisabledModeArg);
                    break;
                case GET_SYNC_DISABLED_FOR_TESTS:
                    int syncDisabledModeInt = DeviceConfig.getSyncDisabledMode();
                    String syncDisabledModeString = formatSyncDisabledMode(syncDisabledModeInt);
                    if (syncDisabledModeString == null) {
                        perr.println("Unknown mode: " + syncDisabledModeInt);
                        return -1;
                    }
                    pout.println(syncDisabledModeString);
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
            pw.println("  list_namespaces [--public]");
            pw.println("      Prints the name of all (or just the public) namespaces.");
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
            pw.println("  get_sync_disabled_for_tests");
            pw.println("      Prints one of the SYNC_DISABLED_MODE values, see"
                    + " set_sync_disabled_for_tests");
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

    private static @SyncDisabledMode int parseSyncDisabledMode(String arg) {
        int syncDisabledMode;
        if ("none".equalsIgnoreCase(arg)) {
            syncDisabledMode = SYNC_DISABLED_MODE_NONE;
        } else if ("persistent".equalsIgnoreCase(arg)) {
            syncDisabledMode = SYNC_DISABLED_MODE_PERSISTENT;
        } else if ("until_reboot".equalsIgnoreCase(arg)) {
            syncDisabledMode = SYNC_DISABLED_MODE_UNTIL_REBOOT;
        } else {
            syncDisabledMode = -1;
        }
        return syncDisabledMode;
    }

    private static String formatSyncDisabledMode(@SyncDisabledMode int syncDisabledMode) {
        switch (syncDisabledMode) {
            case SYNC_DISABLED_MODE_NONE:
                return "none";
            case SYNC_DISABLED_MODE_PERSISTENT:
                return "persistent";
            case SYNC_DISABLED_MODE_UNTIL_REBOOT:
                return "until_reboot";
            default:
                return null;
        }
    }
}
