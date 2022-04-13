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

package android.net.netstats;

import static android.annotation.SystemApi.Client.MODULE_LIBRARIES;
import static android.net.ConnectivityManager.TYPE_MOBILE;
import static android.net.ConnectivityManager.TYPE_MOBILE_DUN;
import static android.net.ConnectivityManager.TYPE_MOBILE_HIPRI;
import static android.net.ConnectivityManager.TYPE_MOBILE_MMS;
import static android.net.ConnectivityManager.TYPE_MOBILE_SUPL;
import static android.net.NetworkStats.SET_DEFAULT;
import static android.net.NetworkStats.TAG_NONE;
import static android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID;

import android.annotation.NonNull;
import android.annotation.StringDef;
import android.annotation.SystemApi;
import android.net.NetworkIdentity;
import android.net.NetworkStatsCollection;
import android.net.NetworkStatsHistory;
import android.net.NetworkTemplate;
import android.os.Environment;
import android.util.AtomicFile;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FastDataInput;

import libcore.io.IoUtils;

import java.io.BufferedInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.ProtocolException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/**
 * Helper class to read old version of persistent network statistics.
 *
 * The implementation is intended to be modified by OEM partners to
 * accommodate their custom changes.
 *
 * @hide
 */
@SystemApi(client = MODULE_LIBRARIES)
public class NetworkStatsDataMigrationUtils {
    /**
     * Prefix of the files which are used to store per network interface statistics.
     */
    public static final String PREFIX_XT = "xt";
    /**
     * Prefix of the files which are used to store per uid statistics.
     */
    public static final String PREFIX_UID = "uid";
    /**
     * Prefix of the files which are used to store per uid tagged traffic statistics.
     */
    public static final String PREFIX_UID_TAG = "uid_tag";

    /** @hide */
    @StringDef(prefix = {"PREFIX_"}, value = {
        PREFIX_XT,
        PREFIX_UID,
        PREFIX_UID_TAG,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Prefix {}

    private static final HashMap<String, String> sPrefixLegacyFileNameMap =
            new HashMap<String, String>() {{
                put(PREFIX_XT, "netstats_xt.bin");
                put(PREFIX_UID, "netstats_uid.bin");
                put(PREFIX_UID_TAG, "netstats_uid.bin");
            }};

    // These version constants are copied from NetworkStatsCollection/History, which is okay for
    // OEMs to modify to adapt their own logic.
    private static class CollectionVersion {
        static final int VERSION_NETWORK_INIT = 1;

        static final int VERSION_UID_INIT = 1;
        static final int VERSION_UID_WITH_IDENT = 2;
        static final int VERSION_UID_WITH_TAG = 3;
        static final int VERSION_UID_WITH_SET = 4;

        static final int VERSION_UNIFIED_INIT = 16;
    }

    private static class HistoryVersion {
        static final int VERSION_INIT = 1;
        static final int VERSION_ADD_PACKETS = 2;
        static final int VERSION_ADD_ACTIVE = 3;
    }

    private static class IdentitySetVersion {
        static final int VERSION_INIT = 1;
        static final int VERSION_ADD_ROAMING = 2;
        static final int VERSION_ADD_NETWORK_ID = 3;
        static final int VERSION_ADD_METERED = 4;
        static final int VERSION_ADD_DEFAULT_NETWORK = 5;
        static final int VERSION_ADD_OEM_MANAGED_NETWORK = 6;
        static final int VERSION_ADD_SUB_ID = 7;
    }

    /**
     * File header magic number: "ANET". The definition is copied from NetworkStatsCollection,
     * but it is fine for OEM to re-define to their own value to adapt the legacy file reading
     * logic.
     */
    private static final int FILE_MAGIC = 0x414E4554;
    /** Default buffer size from BufferedInputStream */
    private static final int BUFFER_SIZE = 8192;

    // Constructing this object is not allowed.
    private NetworkStatsDataMigrationUtils() {
    }

    // Used to read files at /data/system/netstats_*.bin.
    @NonNull
    private static File getPlatformSystemDir() {
        return new File(Environment.getDataDirectory(), "system");
    }

    // Used to read files at /data/system/netstats/<tag>.<start>-<end>.
    @NonNull
    private static File getPlatformBaseDir() {
        File baseDir = new File(getPlatformSystemDir(), "netstats");
        baseDir.mkdirs();
        return baseDir;
    }

    // Get /data/system/netstats_*.bin legacy files. Does not check for existence.
    @NonNull
    private static File getLegacyBinFileForPrefix(@NonNull @Prefix String prefix) {
        return new File(getPlatformSystemDir(), sPrefixLegacyFileNameMap.get(prefix));
    }

    // List /data/system/netstats/[xt|uid|uid_tag].<start>-<end> legacy files.
    @NonNull
    private static ArrayList<File> getPlatformFileListForPrefix(@NonNull @Prefix String prefix) {
        final ArrayList<File> list = new ArrayList<>();
        final File platformFiles = getPlatformBaseDir();
        if (platformFiles.exists()) {
            for (String name : platformFiles.list()) {
                // Skip when prefix doesn't match.
                if (!name.startsWith(prefix + ".")) continue;

                list.add(new File(platformFiles, name));
            }
        }
        return list;
    }

    /**
     * Read legacy persisted network stats from disk.
     *
     * This function provides the implementation to read legacy network stats
     * from disk. It is used for migration of legacy network stats into the
     * stats provided by the Connectivity module.
     * This function needs to know about the previous format(s) of the network
     * stats data that might be stored on this device so it can be read and
     * conserved upon upgrade to Android 13 or above.
     *
     * This function will be called multiple times sequentially, all on the
     * same thread, and will not be called multiple times concurrently. This
     * function is expected to do a substantial amount of disk access, and
     * doesn't need to return particularly fast, but the first boot after
     * an upgrade to Android 13+ will be held until migration is done. As
     * migration is only necessary once, after the first boot following the
     * upgrade, this delay is not incurred.
     *
     * If this function fails in any way, it should throw an exception. If this
     * happens, the system can't know about the data that was stored in the
     * legacy files, but it will still count data usage happening on this
     * session. On the next boot, the system will try migration again, and
     * merge the returned data with the data used with the previous session.
     * The system will only try the migration up to three (3) times. The remaining
     * count is stored in the netstats_import_legacy_file_needed device config. The
     * legacy data is never deleted by the mainline module to avoid any possible
     * data loss.
     *
     * It is possible to set the netstats_import_legacy_file_needed device config
     * to any positive integer to force the module to perform the migration. This
     * can be achieved by calling the following command before rebooting :
     *     adb shell device_config put connectivity netstats_import_legacy_file_needed 1
     *
     * The AOSP implementation provides code to read persisted network stats as
     * they were written by AOSP prior to Android 13.
     * OEMs who have used the AOSP implementation of persisting network stats
     * to disk don't need to change anything.
     * OEM that had modifications to this format should modify this function
     * to read from their custom file format or locations if necessary.
     *
     * @param prefix         Type of data which is being read by the service.
     * @param bucketDuration Duration of the buckets of the object, in milliseconds.
     * @return {@link NetworkStatsCollection} instance.
     */
    @NonNull
    public static NetworkStatsCollection readPlatformCollection(
            @NonNull @Prefix String prefix, long bucketDuration) throws IOException {
        final NetworkStatsCollection.Builder builder =
                new NetworkStatsCollection.Builder(bucketDuration);

        // Import /data/system/netstats_uid.bin legacy files if exists.
        switch (prefix) {
            case PREFIX_UID:
            case PREFIX_UID_TAG:
                final File uidFile = getLegacyBinFileForPrefix(prefix);
                if (uidFile.exists()) {
                    readLegacyUid(builder, uidFile, PREFIX_UID_TAG.equals(prefix) ? true : false);
                }
                break;
            default:
                // Ignore other types.
        }

        // Import /data/system/netstats/[xt|uid|uid_tag].<start>-<end> legacy files if exists.
        final ArrayList<File> platformFiles = getPlatformFileListForPrefix(prefix);
        for (final File platformFile : platformFiles) {
            if (platformFile.exists()) {
                readPlatformCollection(builder, platformFile);
            }
        }

        return builder.build();
    }

    private static void readPlatformCollection(@NonNull NetworkStatsCollection.Builder builder,
            @NonNull File file) throws IOException {
        final FileInputStream is = new FileInputStream(file);
        final FastDataInput dataIn = new FastDataInput(is, BUFFER_SIZE);
        try {
            readPlatformCollection(builder, dataIn);
        } finally {
            IoUtils.closeQuietly(dataIn);
        }
    }

    /**
     * Helper function to read old version of NetworkStatsCollections that resided in the platform.
     *
     * @hide
     */
    @VisibleForTesting
    public static void readPlatformCollection(@NonNull NetworkStatsCollection.Builder builder,
            @NonNull DataInput in) throws IOException {
        // verify file magic header intact
        final int magic = in.readInt();
        if (magic != FILE_MAGIC) {
            throw new ProtocolException("unexpected magic: " + magic);
        }

        final int version = in.readInt();
        switch (version) {
            case CollectionVersion.VERSION_UNIFIED_INIT: {
                // uid := size *(NetworkIdentitySet size *(uid set tag NetworkStatsHistory))
                final int identSize = in.readInt();
                for (int i = 0; i < identSize; i++) {
                    final Set<NetworkIdentity> ident = readPlatformNetworkIdentitySet(in);

                    final int size = in.readInt();
                    for (int j = 0; j < size; j++) {
                        final int uid = in.readInt();
                        final int set = in.readInt();
                        final int tag = in.readInt();

                        final NetworkStatsCollection.Key key = new NetworkStatsCollection.Key(
                                ident, uid, set, tag);
                        final NetworkStatsHistory history = readPlatformHistory(in);
                        builder.addEntry(key, history);
                    }
                }
                break;
            }
            default: {
                throw new ProtocolException("unexpected version: " + version);
            }
        }
    }

    // Copied from NetworkStatsHistory#DataStreamUtils.
    private static long[] readFullLongArray(DataInput in) throws IOException {
        final int size = in.readInt();
        if (size < 0) throw new ProtocolException("negative array size");
        final long[] values = new long[size];
        for (int i = 0; i < values.length; i++) {
            values[i] = in.readLong();
        }
        return values;
    }

    // Copied from NetworkStatsHistory#DataStreamUtils.
    private static long[] readVarLongArray(@NonNull DataInput in) throws IOException {
        final int size = in.readInt();
        if (size == -1) return null;
        if (size < 0) throw new ProtocolException("negative array size");
        final long[] values = new long[size];
        for (int i = 0; i < values.length; i++) {
            values[i] = readVarLong(in);
        }
        return values;
    }

    /**
     * Read variable-length {@link Long} using protobuf-style approach.
     */
    // Copied from NetworkStatsHistory#DataStreamUtils.
    private static long readVarLong(DataInput in) throws IOException {
        int shift = 0;
        long result = 0;
        while (shift < 64) {
            byte b = in.readByte();
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
        }
        throw new ProtocolException("malformed var long");
    }

    // Copied from NetworkIdentitySet.
    private static String readOptionalString(DataInput in) throws IOException {
        if (in.readByte() != 0) {
            return in.readUTF();
        } else {
            return null;
        }
    }

    /**
     * This is copied from NetworkStatsHistory#NetworkStatsHistory(DataInput in). But it is fine
     * for OEM to re-write the logic to adapt the legacy file reading.
     */
    @NonNull
    private static NetworkStatsHistory readPlatformHistory(@NonNull DataInput in)
            throws IOException {
        final long bucketDuration;
        final long[] bucketStart;
        final long[] rxBytes;
        final long[] rxPackets;
        final long[] txBytes;
        final long[] txPackets;
        final long[] operations;
        final int bucketCount;
        long[] activeTime = new long[0];

        final int version = in.readInt();
        switch (version) {
            case HistoryVersion.VERSION_INIT: {
                bucketDuration = in.readLong();
                bucketStart = readFullLongArray(in);
                rxBytes = readFullLongArray(in);
                rxPackets = new long[bucketStart.length];
                txBytes = readFullLongArray(in);
                txPackets = new long[bucketStart.length];
                operations = new long[bucketStart.length];
                bucketCount = bucketStart.length;
                break;
            }
            case HistoryVersion.VERSION_ADD_PACKETS:
            case HistoryVersion.VERSION_ADD_ACTIVE: {
                bucketDuration = in.readLong();
                bucketStart = readVarLongArray(in);
                activeTime = (version >= HistoryVersion.VERSION_ADD_ACTIVE)
                        ? readVarLongArray(in)
                        : new long[bucketStart.length];
                rxBytes = readVarLongArray(in);
                rxPackets = readVarLongArray(in);
                txBytes = readVarLongArray(in);
                txPackets = readVarLongArray(in);
                operations = readVarLongArray(in);
                bucketCount = bucketStart.length;
                break;
            }
            default: {
                throw new ProtocolException("unexpected version: " + version);
            }
        }

        final NetworkStatsHistory.Builder historyBuilder =
                new NetworkStatsHistory.Builder(bucketDuration, bucketCount);
        for (int i = 0; i < bucketCount; i++) {
            final NetworkStatsHistory.Entry entry = new NetworkStatsHistory.Entry(
                    bucketStart[i], activeTime[i],
                    rxBytes[i], rxPackets[i], txBytes[i], txPackets[i], operations[i]);
            historyBuilder.addEntry(entry);
        }

        return historyBuilder.build();
    }

    @NonNull
    private static Set<NetworkIdentity> readPlatformNetworkIdentitySet(@NonNull DataInput in)
            throws IOException {
        final int version = in.readInt();
        final int size = in.readInt();
        final Set<NetworkIdentity> set = new HashSet<>();
        for (int i = 0; i < size; i++) {
            if (version <= IdentitySetVersion.VERSION_INIT) {
                final int ignored = in.readInt();
            }
            final int type = in.readInt();
            final int ratType = in.readInt();
            final String subscriberId = readOptionalString(in);
            final String networkId;
            if (version >= IdentitySetVersion.VERSION_ADD_NETWORK_ID) {
                networkId = readOptionalString(in);
            } else {
                networkId = null;
            }
            final boolean roaming;
            if (version >= IdentitySetVersion.VERSION_ADD_ROAMING) {
                roaming = in.readBoolean();
            } else {
                roaming = false;
            }

            final boolean metered;
            if (version >= IdentitySetVersion.VERSION_ADD_METERED) {
                metered = in.readBoolean();
            } else {
                // If this is the old data and the type is mobile, treat it as metered. (Note that
                // if this is a mobile network, TYPE_MOBILE is the only possible type that could be
                // used.)
                metered = (type == TYPE_MOBILE);
            }

            final boolean defaultNetwork;
            if (version >= IdentitySetVersion.VERSION_ADD_DEFAULT_NETWORK) {
                defaultNetwork = in.readBoolean();
            } else {
                defaultNetwork = true;
            }

            final int oemNetCapabilities;
            if (version >= IdentitySetVersion.VERSION_ADD_OEM_MANAGED_NETWORK) {
                oemNetCapabilities = in.readInt();
            } else {
                oemNetCapabilities = NetworkTemplate.OEM_MANAGED_NO;
            }

            final int subId;
            if (version >= IdentitySetVersion.VERSION_ADD_SUB_ID) {
                subId = in.readInt();
            } else {
                subId = INVALID_SUBSCRIPTION_ID;
            }

            // Legacy files might contain TYPE_MOBILE_* types which were deprecated in later
            // releases. For backward compatibility, record them as TYPE_MOBILE instead.
            final int collapsedLegacyType = getCollapsedLegacyType(type);
            final NetworkIdentity.Builder builder = new NetworkIdentity.Builder()
                    .setType(collapsedLegacyType)
                    .setSubscriberId(subscriberId)
                    .setWifiNetworkKey(networkId)
                    .setRoaming(roaming).setMetered(metered)
                    .setDefaultNetwork(defaultNetwork)
                    .setOemManaged(oemNetCapabilities)
                    .setSubId(subId);
            if (type == TYPE_MOBILE && ratType != NetworkTemplate.NETWORK_TYPE_ALL) {
                builder.setRatType(ratType);
            }
            set.add(builder.build());
        }
        return set;
    }

    private static int getCollapsedLegacyType(int networkType) {
        // The constants are referenced from ConnectivityManager#TYPE_MOBILE_*.
        switch (networkType) {
            case TYPE_MOBILE:
            case TYPE_MOBILE_SUPL:
            case TYPE_MOBILE_MMS:
            case TYPE_MOBILE_DUN:
            case TYPE_MOBILE_HIPRI:
            case 10 /* TYPE_MOBILE_FOTA */:
            case 11 /* TYPE_MOBILE_IMS */:
            case 12 /* TYPE_MOBILE_CBS */:
            case 14 /* TYPE_MOBILE_IA */:
            case 15 /* TYPE_MOBILE_EMERGENCY */:
                return TYPE_MOBILE;
        }
        return networkType;
    }

    private static void readLegacyUid(@NonNull NetworkStatsCollection.Builder builder,
            @NonNull File uidFile, boolean onlyTaggedData) throws IOException {
        final AtomicFile inputFile = new AtomicFile(uidFile);
        DataInputStream in = new DataInputStream(new BufferedInputStream(inputFile.openRead()));
        try {
            readLegacyUid(builder, in, onlyTaggedData);
        } finally {
            IoUtils.closeQuietly(in);
        }
    }

    /**
     * Read legacy Uid statistics file format into the collection.
     *
     * This is copied from {@code NetworkStatsCollection#readLegacyUid}.
     * See {@code NetworkStatsService#maybeUpgradeLegacyStatsLocked}.
     *
     * @param taggedData whether to read only tagged data (true) or only non-tagged data
     *                   (false). For legacy uid files, the tagged data was stored in
     *                   the same binary file with non-tagged data. But in later releases,
     *                   these data should be kept in different recorders.
     * @hide
     */
    @VisibleForTesting
    public static void readLegacyUid(@NonNull NetworkStatsCollection.Builder builder,
            @NonNull DataInput in, boolean taggedData) throws IOException {
        try {
            // verify file magic header intact
            final int magic = in.readInt();
            if (magic != FILE_MAGIC) {
                throw new ProtocolException("unexpected magic: " + magic);
            }

            final int version = in.readInt();
            switch (version) {
                case CollectionVersion.VERSION_UID_INIT: {
                    // uid := size *(UID NetworkStatsHistory)
                    // drop this data version, since we don't have a good
                    // mapping into NetworkIdentitySet.
                    break;
                }
                case CollectionVersion.VERSION_UID_WITH_IDENT: {
                    // uid := size *(NetworkIdentitySet size *(UID NetworkStatsHistory))
                    // drop this data version, since this version only existed
                    // for a short time.
                    break;
                }
                case CollectionVersion.VERSION_UID_WITH_TAG:
                case CollectionVersion.VERSION_UID_WITH_SET: {
                    // uid := size *(NetworkIdentitySet size *(uid set tag NetworkStatsHistory))
                    final int identSize = in.readInt();
                    for (int i = 0; i < identSize; i++) {
                        final Set<NetworkIdentity> ident = readPlatformNetworkIdentitySet(in);

                        final int size = in.readInt();
                        for (int j = 0; j < size; j++) {
                            final int uid = in.readInt();
                            final int set = (version >= CollectionVersion.VERSION_UID_WITH_SET)
                                    ? in.readInt()
                                    : SET_DEFAULT;
                            final int tag = in.readInt();

                            final NetworkStatsCollection.Key key = new NetworkStatsCollection.Key(
                                    ident, uid, set, tag);
                            final NetworkStatsHistory history = readPlatformHistory(in);

                            if ((tag == TAG_NONE) != taggedData) {
                                builder.addEntry(key, history);
                            }
                        }
                    }
                    break;
                }
                default: {
                    throw new ProtocolException("unknown version: " + version);
                }
            }
        } catch (FileNotFoundException | ProtocolException e) {
            // missing stats is okay, probably first boot
        }
    }
}
