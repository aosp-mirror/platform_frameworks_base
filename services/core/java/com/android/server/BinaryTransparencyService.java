/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.ModuleInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.SystemProperties;
import android.util.PackageUtils;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.IBinaryTransparencyService;
import com.android.internal.util.FrameworkStatsLog;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * @hide
 */
public class BinaryTransparencyService extends SystemService {
    private static final String TAG = "TransparencyService";
    private static final String EXTRA_SERVICE = "service";

    @VisibleForTesting
    static final String VBMETA_DIGEST_UNINITIALIZED = "vbmeta-digest-uninitialized";
    @VisibleForTesting
    static final String VBMETA_DIGEST_UNAVAILABLE = "vbmeta-digest-unavailable";
    @VisibleForTesting
    static final String SYSPROP_NAME_VBETA_DIGEST = "ro.boot.vbmeta.digest";

    @VisibleForTesting
    static final String BINARY_HASH_ERROR = "SHA256HashError";

    private final Context mContext;
    private String mVbmetaDigest;
    private HashMap<String, String> mBinaryHashes;
    private HashMap<String, Long> mBinaryLastUpdateTimes;

    final class BinaryTransparencyServiceImpl extends IBinaryTransparencyService.Stub {

        @Override
        public String getSignedImageInfo() {
            return mVbmetaDigest;
        }

        @Override
        public Map getApexInfo() {
            HashMap results = new HashMap();
            if (!updateBinaryMeasurements()) {
                Slog.e(TAG, "Error refreshing APEX measurements.");
                return results;
            }
            PackageManager pm = mContext.getPackageManager();
            if (pm == null) {
                Slog.e(TAG, "Error obtaining an instance of PackageManager.");
                return results;
            }

            for (PackageInfo packageInfo : getInstalledApexs()) {
                results.put(packageInfo, mBinaryHashes.get(packageInfo.packageName));
            }

            return results;
        }

        @Override
        public void onShellCommand(@Nullable FileDescriptor in,
                                   @Nullable FileDescriptor out,
                                   @Nullable FileDescriptor err,
                                   @NonNull String[] args,
                                   @Nullable ShellCallback callback,
                                   @NonNull ResultReceiver resultReceiver) throws RemoteException {
            (new ShellCommand() {

                private int printSignedImageInfo() {
                    final PrintWriter pw = getOutPrintWriter();
                    boolean listAllPartitions = false;
                    String opt;

                    while ((opt = getNextOption()) != null) {
                        switch (opt) {
                            case "-a":
                                listAllPartitions = true;
                                break;
                            default:
                                pw.println("ERROR: Unknown option: " + opt);
                                return 1;
                        }
                    }

                    final String signedImageInfo = getSignedImageInfo();
                    pw.println("Image Info:");
                    pw.println(Build.FINGERPRINT);
                    pw.println(signedImageInfo);
                    pw.println("");

                    if (listAllPartitions) {
                        PackageManager pm = mContext.getPackageManager();
                        if (pm == null) {
                            pw.println("ERROR: Failed to obtain an instance of package manager.");
                            return -1;
                        }

                        pw.println("Other partitions:");
                        List<Build.Partition> buildPartitions = Build.getFingerprintedPartitions();
                        for (Build.Partition buildPartition : buildPartitions) {
                            pw.println("Name: " + buildPartition.getName());
                            pw.println("Fingerprint: " + buildPartition.getFingerprint());
                            pw.println("Build time (ms): " + buildPartition.getBuildTimeMillis());
                        }
                    }
                    return 0;
                }

                private void printModuleDetails(ModuleInfo moduleInfo, final PrintWriter pw) {
                    pw.println("--- Module Details ---");
                    pw.println("Module name: " + moduleInfo.getName());
                    pw.println("Module visibility: "
                            + (moduleInfo.isHidden() ? "hidden" : "visible"));
                }

                private int printAllApexs() {
                    final PrintWriter pw = getOutPrintWriter();
                    boolean verbose = false;
                    String opt;

                    // refresh cache to make sure info is most up-to-date
                    if (!updateBinaryMeasurements()) {
                        pw.println("ERROR: Failed to refresh info for APEXs.");
                        return -1;
                    }
                    if (mBinaryHashes == null || (mBinaryHashes.size() == 0)) {
                        pw.println("ERROR: Unable to obtain apex_info at this time.");
                        return -1;
                    }

                    while ((opt = getNextOption()) != null) {
                        switch (opt) {
                            case "-v":
                                verbose = true;
                                break;
                            default:
                                pw.println("ERROR: Unknown option: " + opt);
                                return 1;
                        }
                    }

                    PackageManager pm = mContext.getPackageManager();
                    if (pm == null) {
                        pw.println("ERROR: Failed to obtain an instance of package manager.");
                        return -1;
                    }

                    pw.println("APEX Info:");
                    for (PackageInfo packageInfo : getInstalledApexs()) {
                        String packageName = packageInfo.packageName;
                        pw.println(packageName + ";"
                                + packageInfo.getLongVersionCode() + ":"
                                + mBinaryHashes.get(packageName).toLowerCase());

                        if (verbose) {
                            pw.println("Install location: "
                                    + packageInfo.applicationInfo.sourceDir);
                            pw.println("Last Update Time (ms): " + packageInfo.lastUpdateTime);

                            ModuleInfo moduleInfo;
                            try {
                                moduleInfo = pm.getModuleInfo(packageInfo.packageName, 0);
                            } catch (PackageManager.NameNotFoundException e) {
                                pw.println("Is A Module: False");
                                pw.println("");
                                continue;
                            }
                            pw.println("Is A Module: True");
                            printModuleDetails(moduleInfo, pw);
                            pw.println("");
                        }
                    }
                    return 0;
                }

                private int printAllModules() {
                    final PrintWriter pw = getOutPrintWriter();
                    boolean verbose = false;
                    String opt;

                    // refresh cache to make sure info is most up-to-date
                    if (!updateBinaryMeasurements()) {
                        pw.println("ERROR: Failed to refresh info for Modules.");
                        return -1;
                    }
                    if (mBinaryHashes == null || (mBinaryHashes.size() == 0)) {
                        pw.println("ERROR: Unable to obtain module_info at this time.");
                        return -1;
                    }

                    while ((opt = getNextOption()) != null) {
                        switch (opt) {
                            case "-v":
                                verbose = true;
                                break;
                            default:
                                pw.println("ERROR: Unknown option: " + opt);
                                return 1;
                        }
                    }

                    PackageManager pm = mContext.getPackageManager();
                    if (pm == null) {
                        pw.println("ERROR: Failed to obtain an instance of package manager.");
                        return -1;
                    }

                    pw.println("Module Info:");
                    for (ModuleInfo module : pm.getInstalledModules(PackageManager.MATCH_ALL)) {
                        String packageName = module.getPackageName();
                        try {
                            PackageInfo packageInfo = pm.getPackageInfo(packageName,
                                    PackageManager.MATCH_APEX);
                            pw.println(packageInfo.packageName + ";"
                                    + packageInfo.getLongVersionCode() + ":"
                                    + mBinaryHashes.get(packageName).toLowerCase());

                            if (verbose) {
                                pw.println("Install location: "
                                        + packageInfo.applicationInfo.sourceDir);
                                printModuleDetails(module, pw);
                                pw.println("");
                            }
                        } catch (PackageManager.NameNotFoundException e) {
                            pw.println(packageName
                                    + ";ERROR:Unable to find PackageInfo for this module.");
                            if (verbose) {
                                printModuleDetails(module, pw);
                                pw.println("");
                            }
                            continue;
                        }
                    }
                    return 0;
                }

                @Override
                public int onCommand(String cmd) {
                    if (cmd == null) {
                        return handleDefaultCommands(cmd);
                    }

                    final PrintWriter pw = getOutPrintWriter();
                    switch (cmd) {
                        case "get": {
                            final String infoType = getNextArg();
                            if (infoType == null) {
                                printHelpMenu();
                                return -1;
                            }

                            switch (infoType) {
                                case "image_info":
                                    return printSignedImageInfo();
                                case "apex_info":
                                    return printAllApexs();
                                case "module_info":
                                    return printAllModules();
                                default:
                                    pw.println(String.format("ERROR: Unknown info type '%s'",
                                            infoType));
                                    return 1;
                            }
                        }
                        default:
                            return handleDefaultCommands(cmd);
                    }
                }

                private void printHelpMenu() {
                    final PrintWriter pw = getOutPrintWriter();
                    pw.println("Transparency manager (transparency) commands:");
                    pw.println("    help");
                    pw.println("        Print this help text.");
                    pw.println("");
                    pw.println("    get image_info [-a]");
                    pw.println("        Print information about loaded image (firmware). Options:");
                    pw.println("            -a: lists all other identifiable partitions.");
                    pw.println("");
                    pw.println("    get apex_info [-v]");
                    pw.println("        Print information about installed APEXs on device.");
                    pw.println("            -v: lists more verbose information about each APEX");
                    pw.println("");
                    pw.println("    get module_info [-v]");
                    pw.println("        Print information about installed modules on device.");
                    pw.println("            -v: lists more verbose information about each module");
                    pw.println("");
                }

                @Override
                public void onHelp() {
                    printHelpMenu();
                }
            }).exec(this, in, out, err, args, callback, resultReceiver);
        }
    }
    private final BinaryTransparencyServiceImpl mServiceImpl;

    public BinaryTransparencyService(Context context) {
        super(context);
        mContext = context;
        mServiceImpl = new BinaryTransparencyServiceImpl();
        mVbmetaDigest = VBMETA_DIGEST_UNINITIALIZED;
        mBinaryHashes = new HashMap<>();
        mBinaryLastUpdateTimes = new HashMap<>();
    }

    /**
     * Called when the system service should publish a binder service using
     * {@link #publishBinderService(String, IBinder).}
     */
    @Override
    public void onStart() {
        try {
            publishBinderService(Context.BINARY_TRANSPARENCY_SERVICE, mServiceImpl);
            Slog.i(TAG, "Started BinaryTransparencyService");
        } catch (Throwable t) {
            Slog.e(TAG, "Failed to start BinaryTransparencyService.", t);
        }
    }

    /**
     * Called on each phase of the boot process. Phases before the service's start phase
     * (as defined in the @Service annotation) are never received.
     *
     * @param phase The current boot phase.
     */
    @Override
    public void onBootPhase(int phase) {

        // we are only interested in doing things at PHASE_BOOT_COMPLETED
        if (phase == PHASE_BOOT_COMPLETED) {
            Slog.i(TAG, "Boot completed. Getting VBMeta Digest.");
            getVBMetaDigestInformation();

            // due to potentially long computation that holds up boot time, computations for
            // SHA256 digests of APEX and Module packages are scheduled here,
            // but only executed when device is idle.
            Slog.i(TAG, "Scheduling APEX and Module measurements to be updated.");
            UpdateMeasurementsJobService.scheduleBinaryMeasurements(mContext,
                    BinaryTransparencyService.this);
        }
    }

    /**
     * JobService to update binary measurements and update internal cache.
     */
    public static class UpdateMeasurementsJobService extends JobService {
        private static final int COMPUTE_APEX_MODULE_SHA256_JOB_ID =
                BinaryTransparencyService.UpdateMeasurementsJobService.class.hashCode();

        @Override
        public boolean onStartJob(JobParameters params) {
            Slog.d(TAG, "Job to update binary measurements started.");
            if (params.getJobId() != COMPUTE_APEX_MODULE_SHA256_JOB_ID) {
                return false;
            }

            // we'll still update the measurements via threads to be mindful of low-end devices
            // where this operation might take longer than expected, and so that we don't block
            // system_server's main thread.
            Executors.defaultThreadFactory().newThread(() -> {
                // since we can't call updateBinaryMeasurements() directly, calling
                // getApexInfo() achieves the same effect, and we simply discard the return
                // value

                IBinder b = ServiceManager.getService(Context.BINARY_TRANSPARENCY_SERVICE);
                IBinaryTransparencyService iBtsService =
                        IBinaryTransparencyService.Stub.asInterface(b);
                try {
                    iBtsService.getApexInfo();
                } catch (RemoteException e) {
                    Slog.e(TAG, "Updating binary measurements was interrupted.", e);
                    return;
                }
                jobFinished(params, false);
            }).start();

            return true;
        }

        @Override
        public boolean onStopJob(JobParameters params) {
            return false;
        }

        @SuppressLint("DefaultLocale")
        static void scheduleBinaryMeasurements(Context context, BinaryTransparencyService service) {
            Slog.i(TAG, "Scheduling APEX & Module SHA256 digest computation job");
            final JobScheduler jobScheduler = context.getSystemService(JobScheduler.class);
            if (jobScheduler == null) {
                Slog.e(TAG, "Failed to obtain an instance of JobScheduler.");
                return;
            }

            final JobInfo jobInfo = new JobInfo.Builder(COMPUTE_APEX_MODULE_SHA256_JOB_ID,
                    new ComponentName(context, UpdateMeasurementsJobService.class))
                    .setRequiresDeviceIdle(true)
                    .setRequiresCharging(true)
                    .build();
            if (jobScheduler.schedule(jobInfo) != JobScheduler.RESULT_SUCCESS) {
                Slog.e(TAG, "Failed to schedule job to update binary measurements.");
                return;
            }
            Slog.d(TAG, String.format(
                    "Job %d to update binary measurements scheduled successfully.",
                    COMPUTE_APEX_MODULE_SHA256_JOB_ID));
        }
    }

    private void getVBMetaDigestInformation() {
        mVbmetaDigest = SystemProperties.get(SYSPROP_NAME_VBETA_DIGEST, VBMETA_DIGEST_UNAVAILABLE);
        Slog.d(TAG, String.format("VBMeta Digest: %s", mVbmetaDigest));
        FrameworkStatsLog.write(FrameworkStatsLog.VBMETA_DIGEST_REPORTED, mVbmetaDigest);
    }

    @NonNull
    private List<PackageInfo> getInstalledApexs() {
        List<PackageInfo> results = new ArrayList<>();
        PackageManager pm = mContext.getPackageManager();
        if (pm == null) {
            Slog.e(TAG, "Error obtaining an instance of PackageManager.");
            return results;
        }
        List<PackageInfo> allPackages = pm.getInstalledPackages(
                PackageManager.PackageInfoFlags.of(PackageManager.MATCH_APEX));
        if (allPackages == null) {
            Slog.e(TAG, "Error obtaining installed packages (including APEX)");
            return results;
        }

        results = allPackages.stream().filter(p -> p.isApex).collect(Collectors.toList());
        return results;
    }


    /**
     * Updates the internal data structure with the most current APEX measurements.
     * @return true if update is successful; false otherwise.
     */
    private boolean updateBinaryMeasurements() {
        if (mBinaryHashes.size() == 0) {
            Slog.d(TAG, "No apex in cache yet.");
            doFreshBinaryMeasurements();
            return true;
        }

        PackageManager pm = mContext.getPackageManager();
        if (pm == null) {
            Slog.e(TAG, "Failed to obtain a valid PackageManager instance.");
            return false;
        }

        // We're assuming updates to existing modules and APEXs can happen, but not brand new
        // ones appearing out of the blue. Thus, we're going to only go through our cache to check
        // for changes, rather than freshly invoking `getInstalledPackages()` and
        // `getInstalledModules()`
        byte[] largeFileBuffer = PackageUtils.createLargeFileBuffer();
        for (Map.Entry<String, Long> entry : mBinaryLastUpdateTimes.entrySet()) {
            String packageName = entry.getKey();
            try {
                PackageInfo packageInfo = pm.getPackageInfo(packageName,
                        PackageManager.PackageInfoFlags.of(PackageManager.MATCH_APEX));
                long cachedUpdateTime = entry.getValue();

                if (packageInfo.lastUpdateTime > cachedUpdateTime) {
                    Slog.d(TAG, packageName + " has been updated!");
                    entry.setValue(packageInfo.lastUpdateTime);

                    // compute the digest for the updated package
                    String sha256digest = PackageUtils.computeSha256DigestForLargeFile(
                            packageInfo.applicationInfo.sourceDir, largeFileBuffer);
                    if (sha256digest == null) {
                        Slog.e(TAG, "Failed to compute SHA256sum for file at "
                                + packageInfo.applicationInfo.sourceDir);
                        mBinaryHashes.put(packageName, BINARY_HASH_ERROR);
                    } else {
                        mBinaryHashes.put(packageName, sha256digest);
                    }

                    if (packageInfo.isApex) {
                        FrameworkStatsLog.write(FrameworkStatsLog.APEX_INFO_GATHERED,
                                packageInfo.packageName,
                                packageInfo.getLongVersionCode(),
                                mBinaryHashes.get(packageInfo.packageName));
                    }
                }
            } catch (PackageManager.NameNotFoundException e) {
                Slog.e(TAG, "Could not find package with name " + packageName);
                continue;
            }
        }

        return true;
    }

    private void doFreshBinaryMeasurements() {
        PackageManager pm = mContext.getPackageManager();
        Slog.d(TAG, "Obtained package manager");

        // In general, we care about all APEXs, *and* all Modules, which may include some APKs.

        // First, we deal with all installed APEXs.
        byte[] largeFileBuffer = PackageUtils.createLargeFileBuffer();
        for (PackageInfo packageInfo : getInstalledApexs()) {
            ApplicationInfo appInfo = packageInfo.applicationInfo;

            // compute SHA256 for these APEXs
            String sha256digest = PackageUtils.computeSha256DigestForLargeFile(appInfo.sourceDir,
                    largeFileBuffer);
            if (sha256digest == null) {
                Slog.e(TAG, String.format("Failed to compute SHA256 digest for %s",
                        packageInfo.packageName));
                mBinaryHashes.put(packageInfo.packageName, BINARY_HASH_ERROR);
            } else {
                mBinaryHashes.put(packageInfo.packageName, sha256digest);
            }
            FrameworkStatsLog.write(FrameworkStatsLog.APEX_INFO_GATHERED, packageInfo.packageName,
                    packageInfo.getLongVersionCode(), mBinaryHashes.get(packageInfo.packageName));
            Slog.d(TAG, String.format("Last update time for %s: %d", packageInfo.packageName,
                    packageInfo.lastUpdateTime));
            mBinaryLastUpdateTimes.put(packageInfo.packageName, packageInfo.lastUpdateTime);
        }

        // Next, get all installed modules from PackageManager - skip over those APEXs we've
        // processed above
        for (ModuleInfo module : pm.getInstalledModules(PackageManager.MATCH_ALL)) {
            String packageName = module.getPackageName();
            if (packageName == null) {
                Slog.e(TAG, "ERROR: Encountered null package name for module "
                        + module.getApexModuleName());
                continue;
            }
            if (mBinaryHashes.containsKey(module.getPackageName())) {
                continue;
            }

            // get PackageInfo for this module
            try {
                PackageInfo packageInfo = pm.getPackageInfo(packageName,
                        PackageManager.PackageInfoFlags.of(PackageManager.MATCH_APEX));
                ApplicationInfo appInfo = packageInfo.applicationInfo;

                // compute SHA256 digest for these modules
                String sha256digest = PackageUtils.computeSha256DigestForLargeFile(
                        appInfo.sourceDir, largeFileBuffer);
                if (sha256digest == null) {
                    Slog.e(TAG, String.format("Failed to compute SHA256 digest for %s",
                            packageName));
                    mBinaryHashes.put(packageName, BINARY_HASH_ERROR);
                } else {
                    mBinaryHashes.put(packageName, sha256digest);
                }
                Slog.d(TAG, String.format("Last update time for %s: %d", packageName,
                        packageInfo.lastUpdateTime));
                mBinaryLastUpdateTimes.put(packageName, packageInfo.lastUpdateTime);
            } catch (PackageManager.NameNotFoundException e) {
                Slog.e(TAG, "ERROR: Could not obtain PackageInfo for package name: "
                        + packageName);
                continue;
            }
        }
    }

}
