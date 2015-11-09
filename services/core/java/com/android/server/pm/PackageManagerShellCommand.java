package com.android.server.pm;

import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.IPackageManager;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.RemoteException;
import android.os.ShellCommand;
import android.os.UserHandle;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.WeakHashMap;

class PackageManagerShellCommand extends ShellCommand {
    final IPackageManager mInterface;
    final private WeakHashMap<String, Resources> mResourceCache =
            new WeakHashMap<String, Resources>();

    PackageManagerShellCommand(PackageManagerService service) {
        mInterface = service;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }

        final PrintWriter pw = getOutPrintWriter();
        try {
            switch(cmd) {
                case "list":
                    return runList();
                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (RemoteException e) {
            pw.println("Remote exception: " + e);
        }
        return -1;
    }

    private int runList() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        final String type = getNextArg();
        if (type == null) {
            pw.println("Error: didn't specify type of data to list");
            return -1;
        }
        switch(type) {
            case "features":
                return runListFeatures();
            case "instrumentation":
                return runListInstrumentation();
            case "libraries":
                return runListLibraries();
            case "package":
            case "packages":
                return runListPackages(false /*showSourceDir*/);
            case "permission-groups":
                return runListPermissionGroups();
            case "permissions":
                return runListPermissions();
        }
        pw.println("Error: unknown list type '" + type + "'");
        return -1;
    }

    private int runListFeatures() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        final List<FeatureInfo> list = new ArrayList<FeatureInfo>();
        final FeatureInfo[] rawList = mInterface.getSystemAvailableFeatures();
        for (int i=0; i<rawList.length; i++) {
            list.add(rawList[i]);
        }

        // sort by name
        Collections.sort(list, new Comparator<FeatureInfo>() {
            public int compare(FeatureInfo o1, FeatureInfo o2) {
                if (o1.name == o2.name) return 0;
                if (o1.name == null) return -1;
                if (o2.name == null) return 1;
                return o1.name.compareTo(o2.name);
            }
        });

        final int count = (list != null) ? list.size() : 0;
        for (int p = 0; p < count; p++) {
            FeatureInfo fi = list.get(p);
            pw.print("feature:");
            if (fi.name != null) pw.println(fi.name);
            else pw.println("reqGlEsVersion=0x"
                    + Integer.toHexString(fi.reqGlEsVersion));
        }
        return 0;
    }

    private int runListInstrumentation() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        boolean showSourceDir = false;
        String targetPackage = null;

        try {
            String opt;
            while ((opt = getNextArg()) != null) {
                switch (opt) {
                    case "-f":
                        showSourceDir = true;
                        break;
                    default:
                        if (opt.charAt(0) != '-') {
                            targetPackage = opt;
                        } else {
                            pw.println("Error: Unknown option: " + opt);
                            return -1;
                        }
                        break;
                }
            }
        } catch (RuntimeException ex) {
            pw.println("Error: " + ex.toString());
            return -1;
        }

        final List<InstrumentationInfo> list =
                mInterface.queryInstrumentation(targetPackage, 0 /*flags*/);

        // sort by target package
        Collections.sort(list, new Comparator<InstrumentationInfo>() {
            public int compare(InstrumentationInfo o1, InstrumentationInfo o2) {
                return o1.targetPackage.compareTo(o2.targetPackage);
            }
        });

        final int count = (list != null) ? list.size() : 0;
        for (int p = 0; p < count; p++) {
            final InstrumentationInfo ii = list.get(p);
            pw.print("instrumentation:");
            if (showSourceDir) {
                pw.print(ii.sourceDir);
                pw.print("=");
            }
            final ComponentName cn = new ComponentName(ii.packageName, ii.name);
            pw.print(cn.flattenToShortString());
            pw.print(" (target=");
            pw.print(ii.targetPackage);
            pw.println(")");
        }
        return 0;
    }

    private int runListLibraries() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        final List<String> list = new ArrayList<String>();
        final String[] rawList = mInterface.getSystemSharedLibraryNames();
        for (int i = 0; i < rawList.length; i++) {
            list.add(rawList[i]);
        }

        // sort by name
        Collections.sort(list, new Comparator<String>() {
            public int compare(String o1, String o2) {
                if (o1 == o2) return 0;
                if (o1 == null) return -1;
                if (o2 == null) return 1;
                return o1.compareTo(o2);
            }
        });

        final int count = (list != null) ? list.size() : 0;
        for (int p = 0; p < count; p++) {
            String lib = list.get(p);
            pw.print("library:");
            pw.println(lib);
        }
        return 0;
    }

    private int runListPackages(boolean showSourceDir) throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        int getFlags = 0;
        boolean listDisabled = false, listEnabled = false;
        boolean listSystem = false, listThirdParty = false;
        boolean listInstaller = false;
        int userId = UserHandle.USER_SYSTEM;
        try {
            String opt;
            while ((opt = getNextOption()) != null) {
                switch (opt) {
                    case "-d":
                        listDisabled = true;
                        break;
                    case "-e":
                        listEnabled = true;
                        break;
                    case "-f":
                        showSourceDir = true;
                        break;
                    case "-i":
                        listInstaller = true;
                        break;
                    case "-l":
                        // old compat
                        break;
                    case "-lf":
                        showSourceDir = true;
                        break;
                    case "-s":
                        listSystem = true;
                        break;
                    case "-u":
                        getFlags |= PackageManager.GET_UNINSTALLED_PACKAGES;
                        break;
                    case "-3":
                        listThirdParty = true;
                        break;
                    case "--user":
                        userId = Integer.parseInt(getNextArg());
                        break;
                    default:
                        pw.println("Error: Unknown option: " + opt);
                        return -1;
                }
            }
        } catch (RuntimeException ex) {
            pw.println("Error: " + ex.toString());
            return -1;
        }

        final String filter = getNextArg();

        @SuppressWarnings("unchecked")
        final ParceledListSlice<PackageInfo> slice =
                mInterface.getInstalledPackages(getFlags, userId);
        final List<PackageInfo> packages = slice.getList();

        final int count = packages.size();
        for (int p = 0; p < count; p++) {
            final PackageInfo info = packages.get(p);
            if (filter != null && !info.packageName.contains(filter)) {
                continue;
            }
            final boolean isSystem =
                    (info.applicationInfo.flags&ApplicationInfo.FLAG_SYSTEM) != 0;
            if ((!listDisabled || !info.applicationInfo.enabled) &&
                    (!listEnabled || info.applicationInfo.enabled) &&
                    (!listSystem || isSystem) &&
                    (!listThirdParty || !isSystem)) {
                pw.print("package:");
                if (showSourceDir) {
                    pw.print(info.applicationInfo.sourceDir);
                    pw.print("=");
                }
                pw.print(info.packageName);
                if (listInstaller) {
                    pw.print("  installer=");
                    pw.print(mInterface.getInstallerPackageName(info.packageName));
                }
                pw.println();
            }
        }
        return 0;
    }

    private int runListPermissionGroups() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        final List<PermissionGroupInfo> pgs = mInterface.getAllPermissionGroups(0);

        final int count = pgs.size();
        for (int p = 0; p < count ; p++) {
            final PermissionGroupInfo pgi = pgs.get(p);
            pw.print("permission group:");
            pw.println(pgi.name);
        }
        return 0;
    }

    private int runListPermissions() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        boolean labels = false;
        boolean groups = false;
        boolean userOnly = false;
        boolean summary = false;
        boolean dangerousOnly = false;
        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "-d":
                    dangerousOnly = true;
                    break;
                case "-f":
                    labels = true;
                    break;
                case "-g":
                    groups = true;
                    break;
                case "-s":
                    groups = true;
                    labels = true;
                    summary = true;
                    break;
                case "-u":
                    userOnly = true;
                    break;
                default:
                    pw.println("Error: Unknown option: " + opt);
                    return 1;
            }
        }

        final ArrayList<String> groupList = new ArrayList<String>();
        if (groups) {
            final List<PermissionGroupInfo> infos =
                    mInterface.getAllPermissionGroups(0 /*flags*/);
            final int count = infos.size();
            for (int i = 0; i < count; i++) {
                groupList.add(infos.get(i).name);
            }
            groupList.add(null);
        } else {
            final String grp = getNextArg();
            groupList.add(grp);
        }

        if (dangerousOnly) {
            pw.println("Dangerous Permissions:");
            pw.println("");
            doListPermissions(groupList, groups, labels, summary,
                    PermissionInfo.PROTECTION_DANGEROUS,
                    PermissionInfo.PROTECTION_DANGEROUS);
            if (userOnly) {
                pw.println("Normal Permissions:");
                pw.println("");
                doListPermissions(groupList, groups, labels, summary,
                        PermissionInfo.PROTECTION_NORMAL,
                        PermissionInfo.PROTECTION_NORMAL);
            }
        } else if (userOnly) {
            pw.println("Dangerous and Normal Permissions:");
            pw.println("");
            doListPermissions(groupList, groups, labels, summary,
                    PermissionInfo.PROTECTION_NORMAL,
                    PermissionInfo.PROTECTION_DANGEROUS);
        } else {
            pw.println("All Permissions:");
            pw.println("");
            doListPermissions(groupList, groups, labels, summary,
                    -10000, 10000);
        }
        return 0;
    }

    private void doListPermissions(ArrayList<String> groupList, boolean groups, boolean labels,
            boolean summary, int startProtectionLevel, int endProtectionLevel)
                    throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        final int groupCount = groupList.size();
        for (int i = 0; i < groupCount; i++) {
            String groupName = groupList.get(i);
            String prefix = "";
            if (groups) {
                if (i > 0) {
                    pw.println("");
                }
                if (groupName != null) {
                    PermissionGroupInfo pgi =
                            mInterface.getPermissionGroupInfo(groupName, 0 /*flags*/);
                    if (summary) {
                        Resources res = getResources(pgi);
                        if (res != null) {
                            pw.print(loadText(pgi, pgi.labelRes, pgi.nonLocalizedLabel) + ": ");
                        } else {
                            pw.print(pgi.name + ": ");

                        }
                    } else {
                        pw.println((labels ? "+ " : "") + "group:" + pgi.name);
                        if (labels) {
                            pw.println("  package:" + pgi.packageName);
                            Resources res = getResources(pgi);
                            if (res != null) {
                                pw.println("  label:"
                                        + loadText(pgi, pgi.labelRes, pgi.nonLocalizedLabel));
                                pw.println("  description:"
                                        + loadText(pgi, pgi.descriptionRes,
                                                pgi.nonLocalizedDescription));
                            }
                        }
                    }
                } else {
                    pw.println(((labels && !summary) ? "+ " : "") + "ungrouped:");
                }
                prefix = "  ";
            }
            List<PermissionInfo> ps =
                    mInterface.queryPermissionsByGroup(groupList.get(i), 0 /*flags*/);
            final int count = ps.size();
            boolean first = true;
            for (int p = 0 ; p < count ; p++) {
                PermissionInfo pi = ps.get(p);
                if (groups && groupName == null && pi.group != null) {
                    continue;
                }
                final int base = pi.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE;
                if (base < startProtectionLevel
                        || base > endProtectionLevel) {
                    continue;
                }
                if (summary) {
                    if (first) {
                        first = false;
                    } else {
                        pw.print(", ");
                    }
                    Resources res = getResources(pi);
                    if (res != null) {
                        pw.print(loadText(pi, pi.labelRes,
                                pi.nonLocalizedLabel));
                    } else {
                        pw.print(pi.name);
                    }
                } else {
                    pw.println(prefix + (labels ? "+ " : "")
                            + "permission:" + pi.name);
                    if (labels) {
                        pw.println(prefix + "  package:" + pi.packageName);
                        Resources res = getResources(pi);
                        if (res != null) {
                            pw.println(prefix + "  label:"
                                    + loadText(pi, pi.labelRes,
                                            pi.nonLocalizedLabel));
                            pw.println(prefix + "  description:"
                                    + loadText(pi, pi.descriptionRes,
                                            pi.nonLocalizedDescription));
                        }
                        pw.println(prefix + "  protectionLevel:"
                                + PermissionInfo.protectionToString(pi.protectionLevel));
                    }
                }
            }

            if (summary) {
                pw.println("");
            }
        }
    }

    private String loadText(PackageItemInfo pii, int res, CharSequence nonLocalized)
            throws RemoteException {
        if (nonLocalized != null) {
            return nonLocalized.toString();
        }
        if (res != 0) {
            Resources r = getResources(pii);
            if (r != null) {
                try {
                    return r.getString(res);
                } catch (Resources.NotFoundException e) {
                }
            }
        }
        return null;
    }

    private Resources getResources(PackageItemInfo pii) throws RemoteException {
        Resources res = mResourceCache.get(pii.packageName);
        if (res != null) return res;

        ApplicationInfo ai = mInterface.getApplicationInfo(pii.packageName, 0, 0);
        AssetManager am = new AssetManager();
        am.addAssetPath(ai.publicSourceDir);
        res = new Resources(am, null, null);
        mResourceCache.put(pii.packageName, res);
        return res;
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutPrintWriter();
        pw.println("Package manager (package) commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println("");
        pw.println("  list features");
        pw.println("    Prints all features of the system.");
        pw.println("  list instrumentation [-f] [TARGET-PACKAGE]");
        pw.println("    Prints all test packages; optionally only those targetting TARGET-PACKAGE");
        pw.println("    Options:");
        pw.println("      -f: dump the name of the .apk file containing the test package");
        pw.println("  list libraries");
        pw.println("    Prints all system libraries.");
        pw.println("  list packages [-f] [-d] [-e] [-s] [-3] [-i] [-u] [--user USER_ID] [FILTER]");
        pw.println("    Prints all packages; optionally only those whose name contains");
        pw.println("    the text in FILTER.");
        pw.println("    Options:");
        pw.println("      -f: see their associated file");
        pw.println("      -d: filter to only show disbled packages");
        pw.println("      -e: filter to only show enabled packages");
        pw.println("      -s: filter to only show system packages");
        pw.println("      -3: filter to only show third party packages");
        pw.println("      -i: see the installer for the packages");
        pw.println("      -u: also include uninstalled packages");
        pw.println("  list permission-groups");
        pw.println("    Prints all known permission groups.");
        pw.println("  list permissions [-g] [-f] [-d] [-u] [GROUP]");
        pw.println("    Prints all known permissions; optionally only those in GROUP.");
        pw.println("    Options:");
        pw.println("      -g: organize by group");
        pw.println("      -f: print all information");
        pw.println("      -s: short summary");
        pw.println("      -d: only list dangerous permissions");
        pw.println("      -u: list only the permissions users will see");
        pw.println("");
    }
}

