/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.net;

import android.util.Log;

import java.io.File;
import java.io.RandomAccessFile;
import java.io.IOException;

/**
 * Class that provides network traffic statistics.  These statistics include bytes transmitted and
 * received and network packets transmitted and received, over all interfaces, over the mobile
 * interface, and on a per-UID basis.
 * <p>
 * These statistics may not be available on all platforms.  If the statistics are not supported
 * by this device, {@link #UNSUPPORTED} will be returned.
 */
public class TrafficStats {
    /**
     * The return value to indicate that the device does not support the statistic.
     */
    public final static int UNSUPPORTED = -1;

    // Logging tag.
    private final static String TAG = "trafficstats";

    // We pre-create all the File objects so we don't spend a lot of
    // CPU at runtime converting from Java Strings to byte[] for the
    // kernel calls.
    private final static File[] MOBILE_TX_PACKETS = mobileFiles("tx_packets");
    private final static File[] MOBILE_RX_PACKETS = mobileFiles("rx_packets");
    private final static File[] MOBILE_TX_BYTES = mobileFiles("tx_bytes");
    private final static File[] MOBILE_RX_BYTES = mobileFiles("rx_bytes");
    private final static File SYS_CLASS_NET_DIR = new File("/sys/class/net");

    /**
     * Get the total number of packets transmitted through the mobile interface.
     *
     * @return number of packets.  If the statistics are not supported by this device,
     * {@link #UNSUPPORTED} will be returned.
     */
    public static long getMobileTxPackets() {
        return getMobileStat(MOBILE_TX_PACKETS);
    }

    /**
     * Get the total number of packets received through the mobile interface.
     *
     * @return number of packets.  If the statistics are not supported by this device,
     * {@link #UNSUPPORTED} will be returned.
     */
    public static long getMobileRxPackets() {
        return getMobileStat(MOBILE_RX_PACKETS);
    }

    /**
     * Get the total number of bytes transmitted through the mobile interface.
     *
     * @return number of bytes.  If the statistics are not supported by this device,
     * {@link #UNSUPPORTED} will be returned.
     */
      public static long getMobileTxBytes() {
          return getMobileStat(MOBILE_TX_BYTES);
      }

    /**
     * Get the total number of bytes received through the mobile interface.
     *
     * @return number of bytes.  If the statistics are not supported by this device,
     * {@link #UNSUPPORTED} will be returned.
     */
    public static long getMobileRxBytes() {
        return getMobileStat(MOBILE_RX_BYTES);
    }

    /**
     * Get the total number of packets sent through all network interfaces.
     *
     * @return the number of packets.  If the statistics are not supported by this device,
     * {@link #UNSUPPORTED} will be returned.
     */
    public static long getTotalTxPackets() {
        return getTotalStat("tx_packets");
    }

    /**
     * Get the total number of packets received through all network interfaces.
     *
     * @return number of packets.  If the statistics are not supported by this device,
     * {@link #UNSUPPORTED} will be returned.
     */
    public static long getTotalRxPackets() {
        return getTotalStat("rx_packets");
    }

    /**
     * Get the total number of bytes sent through all network interfaces.
     *
     * @return number of bytes.  If the statistics are not supported by this device,
     * {@link #UNSUPPORTED} will be returned.
     */
    public static long getTotalTxBytes() {
        return getTotalStat("tx_bytes");
    }

    /**
     * Get the total number of bytes received through all network interfaces.
     *
     * @return number of bytes.  If the statistics are not supported by this device,
     * {@link #UNSUPPORTED} will be returned.
     */
    public static long getTotalRxBytes() {
        return getTotalStat("rx_bytes");
    }

    /**
     * Get the number of bytes sent through the network for this UID.
     * The statistics are across all interfaces.
     *
     * {@see android.os.Process#myUid()}.
     *
     * @param uid The UID of the process to examine.
     * @return number of bytes.  If the statistics are not supported by this device,
     * {@link #UNSUPPORTED} will be returned.
     */
    public static long getUidTxBytes(int uid) {
        return getNumberFromFilePath("/proc/uid_stat/" + uid + "/tcp_snd");
    }

    /**
     * Get the number of bytes received through the network for this UID.
     * The statistics are across all interfaces.
     *
     * {@see android.os.Process#myUid()}.
     *
     * @param uid The UID of the process to examine.
     * @return number of bytes
     */
    public static long getUidRxBytes(int uid) {
        return getNumberFromFilePath("/proc/uid_stat/" + uid + "/tcp_rcv");
    }

    /**
     * Returns the array of two possible File locations for a given
     * statistic.
     */
    private static File[] mobileFiles(String whatStat) {
        // Note that we stat them at runtime to see which is
        // available, rather than here, to guard against the files
        // coming & going later as modules shut down (e.g. airplane
        // mode) and whatnot.  The runtime stat() isn't expensive compared
        // to the previous charset conversion that happened before we
        // were reusing File instances.
        File[] files = new File[2];
        files[0] = new File("/sys/class/net/rmnet0/statistics/" + whatStat);
        files[1] = new File("/sys/class/net/ppp0/statistics/" + whatStat);
        return files;
    }

    private static long getTotalStat(String whatStat) {
        File netdir = new File("/sys/class/net");

        File[] nets = SYS_CLASS_NET_DIR.listFiles();
        if (nets == null) {
            return UNSUPPORTED;
        }
        long total = 0;
        StringBuffer strbuf = new StringBuffer();
        for (File net : nets) {
            strbuf.append(net.getPath()).append(File.separator).append("statistics")
                    .append(File.separator).append(whatStat);
            total += getNumberFromFilePath(strbuf.toString());
            strbuf.setLength(0);
        }
        return total;
    }

    private static long getMobileStat(File[] files) {
        for (int i = 0; i < files.length; i++) {
            File file = files[i];
            if (!file.exists()) {
                continue;
            }
            try {
                RandomAccessFile raf = new RandomAccessFile(file, "r");
                return getNumberFromFile(raf, file.getAbsolutePath());
            } catch (IOException e) {
                Log.w(TAG,
                      "Exception opening TCP statistics file " + file.getAbsolutePath(),
                      e);
            }
        }
        return UNSUPPORTED;
    }

    // File will have format <number><newline>
    private static long getNumberFromFilePath(String filename) {
        RandomAccessFile raf = getFile(filename);
        if (raf == null) {
            return UNSUPPORTED;
        }
        return getNumberFromFile(raf, filename);
    }

    // Private buffer for getNumberFromFile.  Safe for re-use because
    // getNumberFromFile is synchronized.
    private final static byte[] buf = new byte[16];

    private static synchronized long getNumberFromFile(RandomAccessFile raf, String filename) {
        try {
            raf.read(buf);
            raf.close();
        } catch (IOException e) {
            Log.w(TAG, "Exception getting TCP bytes from " + filename, e);
            return UNSUPPORTED;
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (IOException e) {
                    Log.w(TAG, "Exception closing " + filename, e);
                }
            }
        }

        long num = 0L;
        for (int i = 0; i < buf.length; i++) {
            if (buf[i] < '0' || buf[i] > '9') {
                break;
            }
            num *= 10;
            num += buf[i] - '0';
        }
        return num;
    }

    private static RandomAccessFile getFile(String filename) {
        File f = new File(filename);
        if (!f.canRead()) {
            return null;
        }

        try {
            return new RandomAccessFile(f, "r");
        } catch (IOException e) {
            Log.w(TAG, "Exception opening TCP statistics file " + filename, e);
            return null;
        }
    }
}
