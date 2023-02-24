/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.pm.dex;

import static com.android.internal.art.ArtStatsLog.ART_DATUM_REPORTED__COMPILATION_REASON__ART_COMPILATION_REASON_INSTALL_BULK_DOWNGRADED;
import static com.android.internal.art.ArtStatsLog.ART_DATUM_REPORTED__COMPILATION_REASON__ART_COMPILATION_REASON_INSTALL_BULK_SECONDARY;
import static com.android.internal.art.ArtStatsLog.ART_DATUM_REPORTED__COMPILATION_REASON__ART_COMPILATION_REASON_INSTALL_BULK_SECONDARY_DOWNGRADED;
import static com.android.internal.art.ArtStatsLog.ART_DATUM_REPORTED__COMPILE_FILTER__ART_COMPILATION_FILTER_FAKE_RUN_FROM_APK_FALLBACK;
import static com.android.internal.art.ArtStatsLog.ART_DATUM_REPORTED__COMPILE_FILTER__ART_COMPILATION_FILTER_FAKE_RUN_FROM_VDEX_FALLBACK;

import android.app.job.JobParameters;
import android.os.SystemClock;
import android.util.Slog;
import android.util.jar.StrictJarFile;

import com.android.internal.art.ArtStatsLog;
import com.android.server.pm.BackgroundDexOptService;
import com.android.server.pm.PackageManagerService;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

/** Utils class to report ART metrics to statsd. */
public class ArtStatsLogUtils {
    private static final String TAG = ArtStatsLogUtils.class.getSimpleName();
    private static final String PROFILE_DEX_METADATA = "primary.prof";
    private static final String VDEX_DEX_METADATA = "primary.vdex";

    private static final int ART_COMPILATION_REASON_INSTALL_BULK_SECONDARY =
            ART_DATUM_REPORTED__COMPILATION_REASON__ART_COMPILATION_REASON_INSTALL_BULK_SECONDARY;
    private static final int ART_COMPILATION_REASON_INSTALL_BULK_DOWNGRADED =
            ART_DATUM_REPORTED__COMPILATION_REASON__ART_COMPILATION_REASON_INSTALL_BULK_DOWNGRADED;
    private static final int ART_COMPILATION_REASON_INSTALL_BULK_SECONDARY_DOWNGRADED =
            ART_DATUM_REPORTED__COMPILATION_REASON__ART_COMPILATION_REASON_INSTALL_BULK_SECONDARY_DOWNGRADED;

    private static final int ART_COMPILATION_FILTER_FAKE_RUN_FROM_APK_FALLBACK =
            ART_DATUM_REPORTED__COMPILE_FILTER__ART_COMPILATION_FILTER_FAKE_RUN_FROM_APK_FALLBACK;
    private static final int ART_COMPILATION_FILTER_FAKE_RUN_FROM_VDEX_FALLBACK =
            ART_DATUM_REPORTED__COMPILE_FILTER__ART_COMPILATION_FILTER_FAKE_RUN_FROM_VDEX_FALLBACK;

    private static final Map<Integer, Integer> COMPILATION_REASON_MAP = new HashMap();

    static {
        COMPILATION_REASON_MAP.put(PackageManagerService.REASON_FIRST_BOOT, ArtStatsLog.
                ART_DATUM_REPORTED__COMPILATION_REASON__ART_COMPILATION_REASON_FIRST_BOOT);
        COMPILATION_REASON_MAP.put(PackageManagerService.REASON_BOOT_AFTER_OTA, ArtStatsLog.
                ART_DATUM_REPORTED__COMPILATION_REASON__ART_COMPILATION_REASON_BOOT_AFTER_OTA);
        COMPILATION_REASON_MAP.put(PackageManagerService.REASON_POST_BOOT, ArtStatsLog.
                ART_DATUM_REPORTED__COMPILATION_REASON__ART_COMPILATION_REASON_POST_BOOT);
        COMPILATION_REASON_MAP.put(PackageManagerService.REASON_INSTALL, ArtStatsLog.
                ART_DATUM_REPORTED__COMPILATION_REASON__ART_COMPILATION_REASON_INSTALL);
        COMPILATION_REASON_MAP.put(PackageManagerService.REASON_INSTALL_FAST, ArtStatsLog.
                ART_DATUM_REPORTED__COMPILATION_REASON__ART_COMPILATION_REASON_INSTALL_FAST);
        COMPILATION_REASON_MAP.put(PackageManagerService.REASON_INSTALL_BULK, ArtStatsLog.
                ART_DATUM_REPORTED__COMPILATION_REASON__ART_COMPILATION_REASON_INSTALL_BULK);
        COMPILATION_REASON_MAP.put(PackageManagerService.REASON_INSTALL_BULK_SECONDARY,
                ART_COMPILATION_REASON_INSTALL_BULK_SECONDARY);
        COMPILATION_REASON_MAP.put(PackageManagerService.REASON_INSTALL_BULK_DOWNGRADED,
                ART_COMPILATION_REASON_INSTALL_BULK_DOWNGRADED);
        COMPILATION_REASON_MAP.put(PackageManagerService.REASON_INSTALL_BULK_SECONDARY_DOWNGRADED,
                ART_COMPILATION_REASON_INSTALL_BULK_SECONDARY_DOWNGRADED);
        COMPILATION_REASON_MAP.put(PackageManagerService.REASON_BACKGROUND_DEXOPT, ArtStatsLog.
                ART_DATUM_REPORTED__COMPILATION_REASON__ART_COMPILATION_REASON_BG_DEXOPT);
        COMPILATION_REASON_MAP.put(PackageManagerService.REASON_AB_OTA, ArtStatsLog.
                ART_DATUM_REPORTED__COMPILATION_REASON__ART_COMPILATION_REASON_AB_OTA);
        COMPILATION_REASON_MAP.put(PackageManagerService.REASON_INACTIVE_PACKAGE_DOWNGRADE,
                ArtStatsLog.
                        ART_DATUM_REPORTED__COMPILATION_REASON__ART_COMPILATION_REASON_INACTIVE);
        COMPILATION_REASON_MAP.put(PackageManagerService.REASON_CMDLINE,
                ArtStatsLog.ART_DATUM_REPORTED__COMPILATION_REASON__ART_COMPILATION_REASON_CMDLINE);
        COMPILATION_REASON_MAP.put(PackageManagerService.REASON_SHARED,
                ArtStatsLog.ART_DATUM_REPORTED__COMPILATION_REASON__ART_COMPILATION_REASON_SHARED);
    }

    private static final Map<String, Integer> COMPILE_FILTER_MAP = new HashMap();

    static {
        COMPILE_FILTER_MAP.put("error", ArtStatsLog.
                ART_DATUM_REPORTED__COMPILE_FILTER__ART_COMPILATION_FILTER_ERROR);
        COMPILE_FILTER_MAP.put("unknown", ArtStatsLog.
                ART_DATUM_REPORTED__COMPILE_FILTER__ART_COMPILATION_FILTER_UNKNOWN);
        COMPILE_FILTER_MAP.put("assume-verified", ArtStatsLog.
                ART_DATUM_REPORTED__COMPILE_FILTER__ART_COMPILATION_FILTER_ASSUMED_VERIFIED);
        COMPILE_FILTER_MAP.put("extract", ArtStatsLog.
                ART_DATUM_REPORTED__COMPILE_FILTER__ART_COMPILATION_FILTER_EXTRACT);
        COMPILE_FILTER_MAP.put("verify", ArtStatsLog.
                ART_DATUM_REPORTED__COMPILE_FILTER__ART_COMPILATION_FILTER_VERIFY);
        COMPILE_FILTER_MAP.put("quicken", ArtStatsLog.
                ART_DATUM_REPORTED__COMPILE_FILTER__ART_COMPILATION_FILTER_QUICKEN);
        COMPILE_FILTER_MAP.put("space-profile", ArtStatsLog.
                ART_DATUM_REPORTED__COMPILE_FILTER__ART_COMPILATION_FILTER_SPACE_PROFILE);
        COMPILE_FILTER_MAP.put("space", ArtStatsLog.
                ART_DATUM_REPORTED__COMPILE_FILTER__ART_COMPILATION_FILTER_SPACE);
        COMPILE_FILTER_MAP.put("speed-profile", ArtStatsLog.
                ART_DATUM_REPORTED__COMPILE_FILTER__ART_COMPILATION_FILTER_SPEED_PROFILE);
        COMPILE_FILTER_MAP.put("speed", ArtStatsLog.
                ART_DATUM_REPORTED__COMPILE_FILTER__ART_COMPILATION_FILTER_SPEED);
        COMPILE_FILTER_MAP.put("everything-profile", ArtStatsLog.
                ART_DATUM_REPORTED__COMPILE_FILTER__ART_COMPILATION_FILTER_EVERYTHING_PROFILE);
        COMPILE_FILTER_MAP.put("everything", ArtStatsLog.
                ART_DATUM_REPORTED__COMPILE_FILTER__ART_COMPILATION_FILTER_EVERYTHING);
        COMPILE_FILTER_MAP.put("run-from-apk", ArtStatsLog.
                ART_DATUM_REPORTED__COMPILE_FILTER__ART_COMPILATION_FILTER_FAKE_RUN_FROM_APK);
        COMPILE_FILTER_MAP.put("run-from-apk-fallback",
                ART_COMPILATION_FILTER_FAKE_RUN_FROM_APK_FALLBACK);
        COMPILE_FILTER_MAP.put("run-from-vdex-fallback",
                ART_COMPILATION_FILTER_FAKE_RUN_FROM_VDEX_FALLBACK);
    }

    private static final Map<String, Integer> ISA_MAP = new HashMap();

    static {
        ISA_MAP.put("arm", ArtStatsLog.ART_DATUM_REPORTED__ISA__ART_ISA_ARM);
        ISA_MAP.put("arm64", ArtStatsLog.ART_DATUM_REPORTED__ISA__ART_ISA_ARM64);
        ISA_MAP.put("x86", ArtStatsLog.ART_DATUM_REPORTED__ISA__ART_ISA_X86);
        ISA_MAP.put("x86_64", ArtStatsLog.ART_DATUM_REPORTED__ISA__ART_ISA_X86_64);
        ISA_MAP.put("mips", ArtStatsLog.ART_DATUM_REPORTED__ISA__ART_ISA_MIPS);
        ISA_MAP.put("mips64", ArtStatsLog.ART_DATUM_REPORTED__ISA__ART_ISA_MIPS64);
    }

    public static void writeStatsLog(
            ArtStatsLogger logger,
            long sessionId,
            String compilerFilter,
            int uid,
            long compileTime,
            String dexMetadataPath,
            int compilationReason,
            int result,
            int apkType,
            String isa,
            String apkPath) {
        int dexMetadataType = getDexMetadataType(dexMetadataPath);
        logger.write(
                sessionId,
                uid,
                compilationReason,
                compilerFilter,
                ArtStatsLog.ART_DATUM_REPORTED__KIND__ART_DATUM_DEX2OAT_RESULT_CODE,
                result,
                dexMetadataType,
                apkType,
                isa);
        logger.write(
                sessionId,
                uid,
                compilationReason,
                compilerFilter,
                ArtStatsLog.ART_DATUM_REPORTED__KIND__ART_DATUM_DEX2OAT_DEX_CODE_COUNTER_BYTES,
                getDexBytes(apkPath),
                dexMetadataType,
                apkType,
                isa);
        logger.write(
                sessionId,
                uid,
                compilationReason,
                compilerFilter,
                ArtStatsLog.ART_DATUM_REPORTED__KIND__ART_DATUM_DEX2OAT_TOTAL_TIME_COUNTER_MILLIS,
                compileTime,
                dexMetadataType,
                apkType,
                isa);
    }

    public static int getApkType(String path, String baseApkPath, String[] splitApkPaths) {
        if (path.equals(baseApkPath)) {
            return ArtStatsLog.ART_DATUM_REPORTED__APK_TYPE__ART_APK_TYPE_BASE;
        } else if(Arrays.stream(splitApkPaths).anyMatch(p->p.equals(path))) {
            return ArtStatsLog.ART_DATUM_REPORTED__APK_TYPE__ART_APK_TYPE_SPLIT;
        } else{
            return ArtStatsLog.ART_DATUM_REPORTED__APK_TYPE__ART_APK_TYPE_UNKNOWN;
        }
    }

    private static long getDexBytes(String apkPath) {
        StrictJarFile jarFile = null;
        long dexBytes = 0;
        try {
            jarFile = new StrictJarFile(apkPath,
                    /*verify=*/ false,
                    /*signatureSchemeRollbackProtectionsEnforced=*/ false);
            Iterator<ZipEntry> it = jarFile.iterator();
            Pattern p = Pattern.compile("classes(\\d)*[.]dex");
            Matcher m = p.matcher("");
            while (it.hasNext()) {
                ZipEntry entry = it.next();
                m.reset(entry.getName());
                if (m.matches()) {
                    dexBytes += entry.getSize();
                }
            }
            return dexBytes;
        } catch (IOException ignore) {
            Slog.e(TAG, "Error when parsing APK " + apkPath);
            return -1L;
        } finally {
            try {
                if (jarFile != null) {
                    jarFile.close();
                }
            } catch (IOException ignore) {
            }
        }
    }

    private static int getDexMetadataType(String dexMetadataPath) {
        if (dexMetadataPath == null) {
            return ArtStatsLog.ART_DATUM_REPORTED__DEX_METADATA_TYPE__ART_DEX_METADATA_TYPE_NONE;
        }
        StrictJarFile jarFile = null;
        try {
            jarFile = new StrictJarFile(dexMetadataPath,
                    /*verify=*/ false,
                    /*signatureSchemeRollbackProtectionsEnforced=*/false);
            boolean hasProfile = findFileName(jarFile, PROFILE_DEX_METADATA);
            boolean hasVdex = findFileName(jarFile, VDEX_DEX_METADATA);
            if (hasProfile && hasVdex) {
                return ArtStatsLog.
                    ART_DATUM_REPORTED__DEX_METADATA_TYPE__ART_DEX_METADATA_TYPE_PROFILE_AND_VDEX;
            } else if (hasProfile) {
                return ArtStatsLog.
                    ART_DATUM_REPORTED__DEX_METADATA_TYPE__ART_DEX_METADATA_TYPE_PROFILE;
            } else if (hasVdex) {
                return ArtStatsLog.
                    ART_DATUM_REPORTED__DEX_METADATA_TYPE__ART_DEX_METADATA_TYPE_VDEX;
            } else {
                return ArtStatsLog.
                    ART_DATUM_REPORTED__DEX_METADATA_TYPE__ART_DEX_METADATA_TYPE_UNKNOWN;
            }
        } catch (IOException ignore) {
            Slog.e(TAG, "Error when parsing dex metadata " + dexMetadataPath);
            return ArtStatsLog.ART_DATUM_REPORTED__DEX_METADATA_TYPE__ART_DEX_METADATA_TYPE_ERROR;
        } finally {
            try {
                if (jarFile != null) {
                    jarFile.close();
                }
            } catch (IOException ignore) {
            }
        }
    }

    private static boolean findFileName(StrictJarFile jarFile, String filename) throws IOException {
        Iterator<ZipEntry> it = jarFile.iterator();
        while (it.hasNext()) {
            ZipEntry entry = it.next();
            if (entry.getName().equals(filename)) {
                return true;
            }
        }
        return false;
    }

    public static class ArtStatsLogger {
        public void write(
                long sessionId,
                int uid,
                int compilationReason,
                String compilerFilter,
                int kind,
                long value,
                int dexMetadataType,
                int apkType,
                String isa) {
            ArtStatsLog.write(
                    ArtStatsLog.ART_DATUM_REPORTED,
                    sessionId,
                    uid,
                    COMPILE_FILTER_MAP.getOrDefault(compilerFilter, ArtStatsLog.
                            ART_DATUM_REPORTED__COMPILE_FILTER__ART_COMPILATION_FILTER_UNKNOWN),
                    COMPILATION_REASON_MAP.getOrDefault(compilationReason, ArtStatsLog.
                            ART_DATUM_REPORTED__COMPILATION_REASON__ART_COMPILATION_REASON_UNKNOWN),
                    /*timestamp_millis=*/ SystemClock.uptimeMillis(),
                    ArtStatsLog.ART_DATUM_REPORTED__THREAD_TYPE__ART_THREAD_MAIN,
                    kind,
                    value,
                    dexMetadataType,
                    apkType,
                    ISA_MAP.getOrDefault(isa,
                            ArtStatsLog.ART_DATUM_REPORTED__ISA__ART_ISA_UNKNOWN),
                    ArtStatsLog.ART_DATUM_REPORTED__GC__ART_GC_COLLECTOR_TYPE_UNKNOWN,
                    ArtStatsLog.ART_DATUM_REPORTED__UFFD_SUPPORT__ART_UFFD_SUPPORT_UNKNOWN);
        }
    }

    private static final Map<Integer, Integer> STATUS_MAP =
            Map.of(BackgroundDexOptService.STATUS_OK,
                    ArtStatsLog.BACKGROUND_DEXOPT_JOB_ENDED__STATUS__STATUS_JOB_FINISHED,
                    BackgroundDexOptService.STATUS_ABORT_BY_CANCELLATION,
                    ArtStatsLog.BACKGROUND_DEXOPT_JOB_ENDED__STATUS__STATUS_ABORT_BY_CANCELLATION,
                    BackgroundDexOptService.STATUS_ABORT_NO_SPACE_LEFT,
                    ArtStatsLog.BACKGROUND_DEXOPT_JOB_ENDED__STATUS__STATUS_ABORT_NO_SPACE_LEFT,
                    BackgroundDexOptService.STATUS_ABORT_THERMAL,
                    ArtStatsLog.BACKGROUND_DEXOPT_JOB_ENDED__STATUS__STATUS_ABORT_THERMAL,
                    BackgroundDexOptService.STATUS_ABORT_BATTERY,
                    ArtStatsLog.BACKGROUND_DEXOPT_JOB_ENDED__STATUS__STATUS_ABORT_BATTERY,
                    BackgroundDexOptService.STATUS_DEX_OPT_FAILED,
                    ArtStatsLog.BACKGROUND_DEXOPT_JOB_ENDED__STATUS__STATUS_JOB_FINISHED);

    /** Helper class to write background dexopt job stats to statsd. */
    public static class BackgroundDexoptJobStatsLogger {
        /** Writes background dexopt job stats to statsd. */
        public void write(@BackgroundDexOptService.Status int status,
                          @JobParameters.StopReason int cancellationReason,
                          long durationMs) {
            ArtStatsLog.write(
                    ArtStatsLog.BACKGROUND_DEXOPT_JOB_ENDED,
                    STATUS_MAP.getOrDefault(status,
                            ArtStatsLog.BACKGROUND_DEXOPT_JOB_ENDED__STATUS__STATUS_UNKNOWN),
                    cancellationReason,
                    durationMs,
                    0);  // deprecated, used to be durationIncludingSleepMs
        }
    }
}
