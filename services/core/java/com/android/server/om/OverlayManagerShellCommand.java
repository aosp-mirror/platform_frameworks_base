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

package com.android.server.om;

import static com.android.internal.content.om.OverlayConfig.PARTITION_ORDER_FILE_PATH;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.om.FabricatedOverlay;
import android.content.om.IOverlayManager;
import android.content.om.OverlayIdentifier;
import android.content.om.OverlayInfo;
import android.content.om.OverlayManagerTransaction;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.ShellCommand;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.util.Xml;

import com.android.modules.utils.TypedXmlPullParser;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Implementation of 'cmd overlay' commands.
 *
 * This class provides an interface to the OverlayManagerService via adb.
 * Intended only for manual debugging. Execute 'adb exec-out cmd overlay help'
 * for a list of available commands.
 */
final class OverlayManagerShellCommand extends ShellCommand {
    private final Context mContext;
    private final IOverlayManager mInterface;
    private static final Map<String, Integer> TYPE_MAP = Map.of(
            "color", TypedValue.TYPE_FIRST_COLOR_INT,
            "string", TypedValue.TYPE_STRING,
            "drawable", -1);

    OverlayManagerShellCommand(@NonNull final Context ctx, @NonNull final IOverlayManager iom) {
        mContext = ctx;
        mInterface = iom;
    }

    @Override
    public int onCommand(@Nullable final String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }
        final PrintWriter err = getErrPrintWriter();
        try {
            switch (cmd) {
                case "list":
                    return runList();
                case "enable":
                    return runEnableDisable(true);
                case "disable":
                    return runEnableDisable(false);
                case "enable-exclusive":
                    return runEnableExclusive();
                case "set-priority":
                    return runSetPriority();
                case "lookup":
                    return runLookup();
                case "fabricate":
                    return runFabricate();
                case "partition-order":
                    return runPartitionOrder();
                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (IllegalArgumentException e) {
            err.println("Error: " + e.getMessage());
        } catch (RemoteException e) {
            err.println("Remote exception: " + e);
        }
        return -1;
    }

    @Override
    public void onHelp() {
        final PrintWriter out = getOutPrintWriter();
        out.println("Overlay manager (overlay) commands:");
        out.println("  help");
        out.println("    Print this help text.");
        out.println("  dump [--verbose] [--user USER_ID] [[FIELD] PACKAGE[:NAME]]");
        out.println("    Print debugging information about the overlay manager.");
        out.println("    With optional parameters PACKAGE and NAME, limit output to the specified");
        out.println("    overlay or target. With optional parameter FIELD, limit output to");
        out.println("    the corresponding SettingsItem field. Field names are all lower case");
        out.println("    and omit the m prefix, i.e. 'userid' for SettingsItem.mUserId.");
        out.println("  list [--user USER_ID] [PACKAGE[:NAME]]");
        out.println("    Print information about target and overlay packages.");
        out.println("    Overlay packages are printed in priority order. With optional");
        out.println("    parameters PACKAGE and NAME, limit output to the specified overlay or");
        out.println("    target.");
        out.println("  enable [--user USER_ID] PACKAGE[:NAME]");
        out.println("    Enable overlay within or owned by PACKAGE with optional unique NAME.");
        out.println("  disable [--user USER_ID] PACKAGE[:NAME]");
        out.println("    Disable overlay within or owned by PACKAGE with optional unique NAME.");
        out.println("  enable-exclusive [--user USER_ID] [--category] PACKAGE");
        out.println("    Enable overlay within or owned by PACKAGE and disable all other overlays");
        out.println("    for its target package. If the --category option is given, only disables");
        out.println("    other overlays in the same category.");
        out.println("  set-priority [--user USER_ID] PACKAGE PARENT|lowest|highest");
        out.println("    Change the priority of the overlay to be just higher than");
        out.println("    the priority of PARENT If PARENT is the special keyword");
        out.println("    'lowest', change priority of PACKAGE to the lowest priority.");
        out.println("    If PARENT is the special keyword 'highest', change priority of");
        out.println("    PACKAGE to the highest priority.");
        out.println("  lookup [--user USER_ID] [--verbose] PACKAGE-TO-LOAD PACKAGE:TYPE/NAME");
        out.println("    Load a package and print the value of a given resource");
        out.println("    applying the current configuration and enabled overlays.");
        out.println("    For a more fine-grained alternative, use 'idmap2 lookup'.");
        out.println("  fabricate [--user USER_ID] [--target-name OVERLAYABLE] --target PACKAGE");
        out.println("            --name NAME [--file FILE] ");
        out.println("            PACKAGE:TYPE/NAME ENCODED-TYPE-ID/TYPE-NAME ENCODED-VALUE");
        out.println("    Create an overlay from a single resource. Caller must be root. Example:");
        out.println("      fabricate --target android --name LighterGray \\");
        out.println("                android:color/lighter_gray 0x1c 0xffeeeeee");
        out.println("  partition-order");
        out.println("    Print the partition order from overlay config and how this order");
        out.println("    got established, by default or by " + PARTITION_ORDER_FILE_PATH);
    }

    private int runList() throws RemoteException {
        final PrintWriter out = getOutPrintWriter();
        final PrintWriter err = getErrPrintWriter();

        int userId = UserHandle.USER_SYSTEM;
        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "--user":
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                    break;
                default:
                    err.println("Error: Unknown option: " + opt);
                    return 1;
            }
        }

        final String packageName = getNextArg();
        if (packageName != null) {
            List<OverlayInfo> overlaysForTarget = mInterface.getOverlayInfosForTarget(
                    packageName, userId);

            // If the package is not targeted by any overlays, check if the package is an overlay.
            if (overlaysForTarget.isEmpty()) {
                final OverlayInfo info = mInterface.getOverlayInfo(packageName, userId);
                if (info != null) {
                    printListOverlay(out, info);
                }
                return 0;
            }

            out.println(packageName);

            // Print the overlays for the target.
            final int n = overlaysForTarget.size();
            for (int i = 0; i < n; i++) {
                printListOverlay(out, overlaysForTarget.get(i));
            }

            return 0;
        }

        // Print all overlays grouped by target package name.
        final Map<String, List<OverlayInfo>> allOverlays = mInterface.getAllOverlays(userId);
        for (final String targetPackageName : allOverlays.keySet()) {
            out.println(targetPackageName);

            List<OverlayInfo> overlaysForTarget = allOverlays.get(targetPackageName);
            final int n = overlaysForTarget.size();
            for (int i = 0; i < n; i++) {
                printListOverlay(out, overlaysForTarget.get(i));
            }
            out.println();
        }

        return 0;
    }

    private void printListOverlay(PrintWriter out, OverlayInfo oi) {
        String status;
        switch (oi.state) {
            case OverlayInfo.STATE_ENABLED_IMMUTABLE:
            case OverlayInfo.STATE_ENABLED:
                status = "[x]";
                break;
            case OverlayInfo.STATE_DISABLED:
                status = "[ ]";
                break;
            default:
                status = "---";
                break;
        }
        out.println(String.format("%s %s", status, oi.getOverlayIdentifier()));
    }

    private int runEnableDisable(final boolean enable) throws RemoteException {
        final PrintWriter err = getErrPrintWriter();

        int userId = UserHandle.USER_SYSTEM;
        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "--user":
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                    break;
                default:
                    err.println("Error: Unknown option: " + opt);
                    return 1;
            }
        }

        final OverlayIdentifier overlay = OverlayIdentifier.fromString(getNextArgRequired());
        mInterface.commit(new OverlayManagerTransaction.Builder()
                .setEnabled(overlay, enable, userId)
                .build());
        return 0;
    }

    private int runPartitionOrder() throws RemoteException {
        final PrintWriter out = getOutPrintWriter();
        out.println("Partition order (low to high priority): " + mInterface.getPartitionOrder());
        out.println("Established by " + (mInterface.isDefaultPartitionOrder() ? "default"
                : PARTITION_ORDER_FILE_PATH));
        return 0;
    }

    private int runFabricate() throws RemoteException {
        final PrintWriter err = getErrPrintWriter();
        if (Binder.getCallingUid() != Process.ROOT_UID) {
            err.println("Error: must be root to fabricate overlays through the shell");
            return 1;
        }

        int userId = UserHandle.USER_SYSTEM;
        String targetPackage = "";
        String targetOverlayable = "";
        String name = "";
        String filename = null;
        String opt;
        String config = null;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "--user":
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                    break;
                case "--target":
                    targetPackage = getNextArgRequired();
                    break;
                case "--target-name":
                    targetOverlayable = getNextArgRequired();
                    break;
                case "--name":
                    name = getNextArgRequired();
                    break;
                case "--file":
                    filename = getNextArgRequired();
                    break;
                case "--config":
                    config = getNextArgRequired();
                    break;
                default:
                    err.println("Error: Unknown option: " + opt);
                    return 1;
            }
        }

        if (name.isEmpty()) {
            err.println("Error: Missing required arg '--name'");
            return 1;
        }

        if (targetPackage.isEmpty()) {
            err.println("Error: Missing required arg '--target'");
            return 1;
        }
        if (filename != null && getRemainingArgsCount() > 0) {
            err.println(
                    "Error: When passing --file don't pass resource name, type, and value as well");
            return 1;
        }
        final String overlayPackageName = "com.android.shell";
        FabricatedOverlay.Builder overlayBuilder = new FabricatedOverlay.Builder(
                overlayPackageName, name, targetPackage)
                .setTargetOverlayable(targetOverlayable);
        if (filename != null) {
            int result = addOverlayValuesFromXml(overlayBuilder, targetPackage, filename);
            if (result != 0) {
                return result;
            }
        } else {
            final String resourceName = getNextArgRequired();
            final String typeStr = getNextArgRequired();
            final String strData = String.join(" ", peekRemainingArgs());
            if (addOverlayValue(overlayBuilder, resourceName, typeStr, strData, config) != 0) {
                return 1;
            }
        }

        mInterface.commit(new OverlayManagerTransaction.Builder()
                .registerFabricatedOverlay(overlayBuilder.build()).build());
        return 0;
    }

    private int addOverlayValuesFromXml(
            FabricatedOverlay.Builder overlayBuilder, String targetPackage, String filename) {
        final PrintWriter err = getErrPrintWriter();
        File file = new File(filename);
        if (!file.exists()) {
            err.println("Error: File does not exist");
            return 1;
        }
        if (!file.canRead()) {
            err.println("Error: File is unreadable");
            return 1;
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            TypedXmlPullParser parser = Xml.resolvePullParser(fis);
            int type;
            while ((type = parser.next()) != XmlPullParser.START_TAG
                    && type != XmlPullParser.END_DOCUMENT) {
                continue;
            }
            parser.require(XmlPullParser.START_TAG, null, "overlay");
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (type == XmlPullParser.START_TAG) {
                    String tagName = parser.getName();
                    if (!tagName.equals("item")) {
                        err.println(TextUtils.formatSimple("Error: Unexpected tag: %s at line %d",
                                tagName, parser.getLineNumber()));
                    } else if (!parser.isEmptyElementTag()) {
                        err.println("Error: item tag must be empty");
                        return 1;
                    } else {
                        String target = parser.getAttributeValue(null, "target");
                        if (TextUtils.isEmpty(target)) {
                            err.println(
                                    "Error: target name missing at line " + parser.getLineNumber());
                            return 1;
                        }
                        int index = target.indexOf('/');
                        if (index < 0) {
                            err.println("Error: target malformed, missing '/' at line "
                                    + parser.getLineNumber());
                            return 1;
                        }
                        String overlayType = target.substring(0, index);
                        String value = parser.getAttributeValue(null, "value");
                        if (TextUtils.isEmpty(value)) {
                            err.println("Error: value missing at line " + parser.getLineNumber());
                            return 1;
                        }
                        String config = parser.getAttributeValue(null, "config");
                        if (addOverlayValue(overlayBuilder, targetPackage + ':' + target,
                                  overlayType, value, config) != 0) {
                            return 1;
                        }
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
            return 1;
        } catch (XmlPullParserException e) {
            e.printStackTrace();
            return 1;
        }
        return 0;
    }

    private int addOverlayValue(FabricatedOverlay.Builder overlayBuilder,
            String resourceName, String typeString, String valueString, String configuration) {
        final int type;
        typeString = typeString.toLowerCase(Locale.getDefault());
        if (TYPE_MAP.containsKey(typeString)) {
            type = TYPE_MAP.get(typeString);
        } else {
            if (typeString.startsWith("0x")) {
                type = Integer.parseUnsignedInt(typeString.substring(2), 16);
            } else {
                type = Integer.parseUnsignedInt(typeString);
            }
        }
        if (type == TypedValue.TYPE_STRING) {
            overlayBuilder.setResourceValue(resourceName, type, valueString, configuration);
        } else if (type < 0) {
            ParcelFileDescriptor pfd =  openFileForSystem(valueString, "r");
            overlayBuilder.setResourceValue(resourceName, pfd, configuration);
        } else {
            final int intData;
            if (valueString.startsWith("0x")) {
                intData = Integer.parseUnsignedInt(valueString.substring(2), 16);
            } else {
                intData = Integer.parseUnsignedInt(valueString);
            }
            overlayBuilder.setResourceValue(resourceName, type, intData, configuration);
        }
        return 0;
    }

    private int runEnableExclusive() throws RemoteException {
        final PrintWriter err = getErrPrintWriter();

        int userId = UserHandle.USER_SYSTEM;
        boolean inCategory = false;
        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "--user":
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                    break;
                case "--category":
                    inCategory = true;
                    break;
                default:
                    err.println("Error: Unknown option: " + opt);
                    return 1;
            }
        }
        final String overlay = getNextArgRequired();
        if (inCategory) {
            return mInterface.setEnabledExclusiveInCategory(overlay, userId) ? 0 : 1;
        } else {
            return mInterface.setEnabledExclusive(overlay, true, userId) ? 0 : 1;
        }
    }

    private int runSetPriority() throws RemoteException {
        final PrintWriter err = getErrPrintWriter();

        int userId = UserHandle.USER_SYSTEM;
        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "--user":
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                    break;
                default:
                    err.println("Error: Unknown option: " + opt);
                    return 1;
            }
        }

        final String packageName = getNextArgRequired();
        final String newParentPackageName = getNextArgRequired();

        if ("highest".equals(newParentPackageName)) {
            return mInterface.setHighestPriority(packageName, userId) ? 0 : 1;
        } else if ("lowest".equals(newParentPackageName)) {
            return mInterface.setLowestPriority(packageName, userId) ? 0 : 1;
        } else {
            return mInterface.setPriority(packageName, newParentPackageName, userId) ? 0 : 1;
        }
    }

    private int runLookup() throws RemoteException {
        final PrintWriter out = getOutPrintWriter();
        final PrintWriter err = getErrPrintWriter();

        int userId = UserHandle.USER_SYSTEM;
        boolean verbose = false;
        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "--user":
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                    break;
                case "--verbose":
                    verbose = true;
                    break;
                default:
                    err.println("Error: Unknown option: " + opt);
                    return 1;
            }
        }

        final String packageToLoad = getNextArgRequired();

        final String fullyQualifiedResourceName = getNextArgRequired(); // package:type/name
        final Pattern regex = Pattern.compile("(.*?):(.*?)/(.*?)");
        final Matcher matcher = regex.matcher(fullyQualifiedResourceName);
        if (!matcher.matches()) {
            err.println("Error: bad resource name, doesn't match package:type/name");
            return 1;
        }

        final Resources res;
        try {
            res = mContext
                .createContextAsUser(UserHandle.of(userId), /* flags */ 0)
                .getPackageManager()
                .getResourcesForApplication(packageToLoad);
        } catch (PackageManager.NameNotFoundException e) {
            err.println(String.format("Error: failed to get resources for package %s for user %d",
                    packageToLoad, userId));
            return 1;
        }
        final AssetManager assets = res.getAssets();
        try {
            assets.setResourceResolutionLoggingEnabled(true);

            // first try as non-complex type ...
            try {
                final TypedValue value = new TypedValue();
                res.getValue(fullyQualifiedResourceName, value, false /* resolveRefs */);
                final CharSequence valueString = value.coerceToString();
                final String resolution = assets.getLastResourceResolution();

                res.getValue(fullyQualifiedResourceName, value, true /* resolveRefs */);
                final CharSequence resolvedString = value.coerceToString();

                if (verbose) {
                    out.println(resolution);
                }

                if (valueString.equals(resolvedString)) {
                    out.println(valueString);
                } else {
                    out.println(valueString + " -> " + resolvedString);
                }
                return 0;
            } catch (Resources.NotFoundException e) {
                // this is ok, resource could still be a complex type
            }

            // ... then try as complex type
            try {

                final String pkg = matcher.group(1);
                final String type = matcher.group(2);
                final String name = matcher.group(3);
                final int resid = res.getIdentifier(name, type, pkg);
                if (resid == 0) {
                    throw new Resources.NotFoundException();
                }
                final TypedArray array = res.obtainTypedArray(resid);
                if (verbose) {
                    out.println(assets.getLastResourceResolution());
                }
                TypedValue tv = new TypedValue();
                for (int i = 0; i < array.length(); i++) {
                    array.getValue(i, tv);
                    out.println(tv.coerceToString());
                }
                array.recycle();
                return 0;
            } catch (Resources.NotFoundException e) {
                // give up
                err.println("Error: failed to get the resource " + fullyQualifiedResourceName);
                return 1;
            }
        } finally {
            assets.setResourceResolutionLoggingEnabled(false);
        }
    }
}
