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

package com.android.server.pm;

import static com.android.server.pm.Installer.DEXOPT_OTA;
import static com.android.server.pm.InstructionSets.getAppDexInstructionSets;
import static com.android.server.pm.InstructionSets.getDexCodeInstructionSets;
import static com.android.server.pm.PackageManagerServiceCompilerMapping.getCompilerFilterForReason;

import android.content.Context;
import android.content.pm.IOtaDexopt;
import android.content.pm.PackageParser;
import android.os.Environment;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.storage.StorageManager;
import android.util.Log;
import android.util.Slog;

import com.android.internal.os.InstallerConnection;
import com.android.internal.os.InstallerConnection.InstallerException;

import java.io.File;
import java.io.FileDescriptor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A service for A/B OTA dexopting.
 *
 * {@hide}
 */
public class OtaDexoptService extends IOtaDexopt.Stub {
    private final static String TAG = "OTADexopt";
    private final static boolean DEBUG_DEXOPT = true;

    // The synthetic library dependencies denoting "no checks."
    private final static String[] NO_LIBRARIES = new String[] { "&" };

    private final Context mContext;
    private final PackageManagerService mPackageManagerService;

    // TODO: Evaluate the need for WeakReferences here.

    /**
     * The list of dexopt invocations for all work.
     */
    private List<String> mDexoptCommands;

    private int completeSize;

    public OtaDexoptService(Context context, PackageManagerService packageManagerService) {
        this.mContext = context;
        this.mPackageManagerService = packageManagerService;

        // Now it's time to check whether we need to move any A/B artifacts.
        moveAbArtifacts(packageManagerService.mInstaller);
    }

    public static OtaDexoptService main(Context context,
            PackageManagerService packageManagerService) {
        OtaDexoptService ota = new OtaDexoptService(context, packageManagerService);
        ServiceManager.addService("otadexopt", ota);

        return ota;
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ResultReceiver resultReceiver) throws RemoteException {
        (new OtaDexoptShellCommand(this)).exec(
                this, in, out, err, args, resultReceiver);
    }

    @Override
    public synchronized void prepare() throws RemoteException {
        if (mDexoptCommands != null) {
            throw new IllegalStateException("already called prepare()");
        }
        final List<PackageParser.Package> important;
        final List<PackageParser.Package> others;
        synchronized (mPackageManagerService.mPackages) {
            // Important: the packages we need to run with ab-ota compiler-reason.
            important = PackageManagerServiceUtils.getPackagesForDexopt(
                    mPackageManagerService.mPackages.values(), mPackageManagerService);
            // Others: we should optimize this with the (first-)boot compiler-reason.
            others = new ArrayList<>(mPackageManagerService.mPackages.values());
            others.removeAll(important);

            // Pre-size the array list by over-allocating by a factor of 1.5.
            mDexoptCommands = new ArrayList<>(3 * mPackageManagerService.mPackages.size() / 2);
        }

        for (PackageParser.Package p : important) {
            // Make sure that core apps are optimized according to their own "reason".
            // If the core apps are not preopted in the B OTA, and REASON_AB_OTA is not speed
            // (by default is speed-profile) they will be interepreted/JITed. This in itself is
            // not a problem as we will end up doing profile guided compilation. However, some
            // core apps may be loaded by system server which doesn't JIT and we need to make
            // sure we don't interpret-only
            int compilationReason = p.coreApp
                    ? PackageManagerService.REASON_CORE_APP
                    : PackageManagerService.REASON_AB_OTA;
            mDexoptCommands.addAll(generatePackageDexopts(p, compilationReason));
        }
        for (PackageParser.Package p : others) {
            // We assume here that there are no core apps left.
            if (p.coreApp) {
                throw new IllegalStateException("Found a core app that's not important");
            }
            mDexoptCommands.addAll(
                    generatePackageDexopts(p, PackageManagerService.REASON_FIRST_BOOT));
        }
        completeSize = mDexoptCommands.size();
    }

    @Override
    public synchronized void cleanup() throws RemoteException {
        if (DEBUG_DEXOPT) {
            Log.i(TAG, "Cleaning up OTA Dexopt state.");
        }
        mDexoptCommands = null;
    }

    @Override
    public synchronized boolean isDone() throws RemoteException {
        if (mDexoptCommands == null) {
            throw new IllegalStateException("done() called before prepare()");
        }

        return mDexoptCommands.isEmpty();
    }

    @Override
    public synchronized float getProgress() throws RemoteException {
        // Approximate the progress by the amount of already completed commands.
        if (completeSize == 0) {
            return 1f;
        }
        int commandsLeft = mDexoptCommands.size();
        return (completeSize - commandsLeft) / ((float)completeSize);
    }

    @Override
    public synchronized String nextDexoptCommand() throws RemoteException {
        if (mDexoptCommands == null) {
            throw new IllegalStateException("dexoptNextPackage() called before prepare()");
        }

        if (mDexoptCommands.isEmpty()) {
            return "(all done)";
        }

        String next = mDexoptCommands.remove(0);

        if (IsFreeSpaceAvailable()) {
            return next;
        } else {
            mDexoptCommands.clear();
            return "(no free space)";
        }
    }

    /**
     * Check for low space. Returns true if there's space left.
     */
    private boolean IsFreeSpaceAvailable() {
        // TODO: If apps are not installed in the internal /data partition, we should compare
        //       against that storage's free capacity.
        File dataDir = Environment.getDataDirectory();
        @SuppressWarnings("deprecation")
        long lowThreshold = StorageManager.from(mContext).getStorageLowBytes(dataDir);
        if (lowThreshold == 0) {
            throw new IllegalStateException("Invalid low memory threshold");
        }
        long usableSpace = dataDir.getUsableSpace();
        return (usableSpace >= lowThreshold);
    }

    /**
     * Generate all dexopt commands for the given package.
     */
    private synchronized List<String> generatePackageDexopts(PackageParser.Package pkg,
            int compilationReason) {
        // Use our custom connection that just collects the commands.
        RecordingInstallerConnection collectingConnection = new RecordingInstallerConnection();
        Installer collectingInstaller = new Installer(mContext, collectingConnection);

        // Use the package manager install and install lock here for the OTA dex optimizer.
        PackageDexOptimizer optimizer = new OTADexoptPackageDexOptimizer(
                collectingInstaller, mPackageManagerService.mInstallLock, mContext);

        String[] libraryDependencies = pkg.usesLibraryFiles;
        if (pkg.isSystemApp()) {
            // For system apps, we want to avoid classpaths checks.
            libraryDependencies = NO_LIBRARIES;
        }

        optimizer.performDexOpt(pkg, libraryDependencies,
                null /* ISAs */, false /* checkProfiles */,
                getCompilerFilterForReason(compilationReason),
                null /* CompilerStats.PackageStats */);

        return collectingConnection.commands;
    }

    @Override
    public synchronized void dexoptNextPackage() throws RemoteException {
        throw new UnsupportedOperationException();
    }

    private void moveAbArtifacts(Installer installer) {
        if (mDexoptCommands != null) {
            throw new IllegalStateException("Should not be ota-dexopting when trying to move.");
        }

        // Look into all packages.
        Collection<PackageParser.Package> pkgs = mPackageManagerService.getPackages();
        for (PackageParser.Package pkg : pkgs) {
            if (pkg == null) {
                continue;
            }

            // Does the package have code? If not, there won't be any artifacts.
            if (!PackageDexOptimizer.canOptimizePackage(pkg)) {
                continue;
            }
            if (pkg.codePath == null) {
                Slog.w(TAG, "Package " + pkg + " can be optimized but has null codePath");
                continue;
            }

            // If the path is in /system or /vendor, ignore. It will have been ota-dexopted into
            // /data/ota and moved into the dalvik-cache already.
            if (pkg.codePath.startsWith("/system") || pkg.codePath.startsWith("/vendor")) {
                continue;
            }

            final String[] instructionSets = getAppDexInstructionSets(pkg.applicationInfo);
            final List<String> paths = pkg.getAllCodePathsExcludingResourceOnly();
            final String[] dexCodeInstructionSets = getDexCodeInstructionSets(instructionSets);
            for (String dexCodeInstructionSet : dexCodeInstructionSets) {
                for (String path : paths) {
                    String oatDir = PackageDexOptimizer.getOatDir(new File(pkg.codePath)).
                            getAbsolutePath();

                    // TODO: Check first whether there is an artifact, to save the roundtrip time.

                    try {
                        installer.moveAb(path, dexCodeInstructionSet, oatDir);
                    } catch (InstallerException e) {
                    }
                }
            }
        }
    }

    private static class OTADexoptPackageDexOptimizer extends
            PackageDexOptimizer.ForcedUpdatePackageDexOptimizer {

        public OTADexoptPackageDexOptimizer(Installer installer, Object installLock,
                Context context) {
            super(installer, installLock, context, "*otadexopt*");
        }

        @Override
        protected int adjustDexoptFlags(int dexoptFlags) {
            // Add the OTA flag.
            return dexoptFlags | DEXOPT_OTA;
        }

    }

    private static class RecordingInstallerConnection extends InstallerConnection {
        public List<String> commands = new ArrayList<String>(1);

        @Override
        public void setWarnIfHeld(Object warnIfHeld) {
            throw new IllegalStateException("Should not reach here");
        }

        @Override
        public synchronized String transact(String cmd) {
            commands.add(cmd);
            return "0";
        }

        @Override
        public boolean mergeProfiles(int uid, String pkgName) throws InstallerException {
            throw new IllegalStateException("Should not reach here");
        }

        @Override
        public boolean dumpProfiles(String gid, String packageName, String codePaths)
                throws InstallerException {
            throw new IllegalStateException("Should not reach here");
        }

        @Override
        public void disconnect() {
            throw new IllegalStateException("Should not reach here");
        }

        @Override
        public void waitForConnection() {
            throw new IllegalStateException("Should not reach here");
        }
    }
}
