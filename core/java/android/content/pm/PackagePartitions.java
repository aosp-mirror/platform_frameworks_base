/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.content.pm;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Environment;
import android.os.FileUtils;

import com.android.internal.annotations.VisibleForTesting;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.Function;

/**
 * Exposes {@link #SYSTEM_PARTITIONS} which represents the partitions in which application packages
 * can be installed. The partitions are ordered from most generic (lowest priority) to most specific
 * (greatest priority).
 *
 * @hide
 **/
public class PackagePartitions {
    public static final int PARTITION_SYSTEM = 0;
    public static final int PARTITION_VENDOR = 1;
    public static final int PARTITION_ODM = 2;
    public static final int PARTITION_OEM = 3;
    public static final int PARTITION_PRODUCT = 4;
    public static final int PARTITION_SYSTEM_EXT = 5;

    @IntDef(flag = true, prefix = { "PARTITION_" }, value = {
        PARTITION_SYSTEM,
        PARTITION_VENDOR,
        PARTITION_ODM,
        PARTITION_OEM,
        PARTITION_PRODUCT,
        PARTITION_SYSTEM_EXT
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PartitionType {}

    /**
     * The list of all system partitions that may contain packages in ascending order of
     * specificity (the more generic, the earlier in the list a partition appears).
     */
    private static final ArrayList<SystemPartition> SYSTEM_PARTITIONS =
            new ArrayList<>(Arrays.asList(
                    new SystemPartition(Environment.getRootDirectory(), PARTITION_SYSTEM,
                            true /* containsPrivApp */, false /* containsOverlay */),
                    new SystemPartition(Environment.getVendorDirectory(), PARTITION_VENDOR,
                            true /* containsPrivApp */, true /* containsOverlay */),
                    new SystemPartition(Environment.getOdmDirectory(), PARTITION_ODM,
                            true /* containsPrivApp */, true /* containsOverlay */),
                    new SystemPartition(Environment.getOemDirectory(), PARTITION_OEM,
                            false /* containsPrivApp */, true /* containsOverlay */),
                    new SystemPartition(Environment.getProductDirectory(), PARTITION_PRODUCT,
                            true /* containsPrivApp */, true /* containsOverlay */),
                    new SystemPartition(Environment.getSystemExtDirectory(), PARTITION_SYSTEM_EXT,
                            true /* containsPrivApp */, true /* containsOverlay */)));

    /**
     * Returns a list in which the elements are products of the specified function applied to the
     * list of {@link #SYSTEM_PARTITIONS} in increasing specificity order.
     */
    public static <T> ArrayList<T> getOrderedPartitions(
            @NonNull Function<SystemPartition, T> producer) {
        final ArrayList<T> out = new ArrayList<>();
        for (int i = 0, n = SYSTEM_PARTITIONS.size(); i < n; i++) {
            final T v = producer.apply(SYSTEM_PARTITIONS.get(i));
            if (v != null)  {
                out.add(v);
            }
        }
        return out;
    }

    private static File canonicalize(File path) {
        try {
            return path.getCanonicalFile();
        } catch (IOException e) {
            return path;
        }
    }

    /** Represents a partition that contains application packages. */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public static class SystemPartition {
        @PartitionType
        public final int type;

        @NonNull
        private final DeferredCanonicalFile mFolder;

        @Nullable
        private final DeferredCanonicalFile mAppFolder;

        @Nullable
        private final DeferredCanonicalFile mPrivAppFolder;

        @Nullable
        private final DeferredCanonicalFile mOverlayFolder;

        private SystemPartition(@NonNull File folder, @PartitionType int type,
                boolean containsPrivApp, boolean containsOverlay) {
            this.type = type;
            this.mFolder = new DeferredCanonicalFile(folder);
            this.mAppFolder = new DeferredCanonicalFile(folder, "app");
            this.mPrivAppFolder = containsPrivApp ? new DeferredCanonicalFile(folder, "priv-app")
                    : null;
            this.mOverlayFolder = containsOverlay ? new DeferredCanonicalFile(folder, "overlay")
                    : null;
        }

        public SystemPartition(@NonNull SystemPartition original) {
            this.type = original.type;
            this.mFolder = new DeferredCanonicalFile(original.mFolder.getFile());
            this.mAppFolder = original.mAppFolder;
            this.mPrivAppFolder = original.mPrivAppFolder;
            this.mOverlayFolder = original.mOverlayFolder;
        }

        /**
         * Creates a partition containing the same folders as the original partition but with a
         * different root folder.
         */
        public SystemPartition(@NonNull File rootFolder, @NonNull SystemPartition partition) {
            this(rootFolder, partition.type, partition.mPrivAppFolder != null,
                    partition.mOverlayFolder != null);
        }

        /** Returns the canonical folder of the partition. */
        @NonNull
        public File getFolder() {
            return mFolder.getFile();
        }

        /** Returns the canonical app folder of the partition. */
        @Nullable
        public File getAppFolder() {
            return mAppFolder == null ? null : mAppFolder.getFile();
        }

        /** Returns the canonical priv-app folder of the partition, if one exists. */
        @Nullable
        public File getPrivAppFolder() {
            return mPrivAppFolder == null ? null : mPrivAppFolder.getFile();
        }

        /** Returns the canonical overlay folder of the partition, if one exists. */
        @Nullable
        public File getOverlayFolder() {
            return mOverlayFolder == null ? null : mOverlayFolder.getFile();
        }

        /** Returns whether the partition contains the specified file. */
        public boolean containsPath(@NonNull String path) {
            return containsFile(new File(path));
        }

        /** Returns whether the partition contains the specified file. */
        public boolean containsFile(@NonNull File file) {
            return FileUtils.contains(mFolder.getFile(), canonicalize(file));
        }

        /** Returns whether the partition contains the specified file in its priv-app folder. */
        public boolean containsPrivApp(@NonNull File scanFile) {
            return mPrivAppFolder != null
                    && FileUtils.contains(mPrivAppFolder.getFile(), canonicalize(scanFile));
        }

        /** Returns whether the partition contains the specified file in its app folder. */
        public boolean containsApp(@NonNull File scanFile) {
            return mAppFolder != null
                    && FileUtils.contains(mAppFolder.getFile(), canonicalize(scanFile));
        }

        /** Returns whether the partition contains the specified file in its overlay folder. */
        public boolean containsOverlay(@NonNull File scanFile) {
            return mOverlayFolder != null
                    && FileUtils.contains(mOverlayFolder.getFile(), canonicalize(scanFile));
        }
    }

    /**
     * A class that defers the canonicalization of its underlying file. This must be done so
     * processes do not attempt to canonicalize files in directories for which the process does not
     * have the correct selinux policies.
     */
    private static class DeferredCanonicalFile {
        private boolean mIsCanonical = false;

        @NonNull
        private File mFile;

        private DeferredCanonicalFile(@NonNull File dir) {
            mFile = dir;
        }

        private DeferredCanonicalFile(@NonNull File dir, @NonNull String fileName) {
            mFile = new File(dir, fileName);
        }

        @NonNull
        private File getFile() {
            if (!mIsCanonical) {
                mFile = canonicalize(mFile);
                mIsCanonical = true;
            }
            return mFile;
        }
    }
}
