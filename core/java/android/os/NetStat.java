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

package android.os;

import android.util.Log;

import java.io.File;
import java.io.RandomAccessFile;
import java.io.IOException;

/** @hide */
public class NetStat{

    /**
     * Get total number of tx packets sent through rmnet0 or ppp0
     *
     * @return number of Tx packets through rmnet0 or ppp0
     */
    public static long getMobileTxPkts() {
        return getMobileStat("tx_packets");
    }

    /**
     *  Get total number of rx packets received through rmnet0 or ppp0
     *
     * @return number of Rx packets through rmnet0 or ppp0
     */
    public static long getMobileRxPkts() {
        return getMobileStat("rx_packets");
    }

      /**
     *  Get total number of tx bytes received through rmnet0 or ppp0
     *
     * @return number of Tx bytes through rmnet0 or ppp0
     */
      public static long getMobileTxBytes() {
          return getMobileStat("tx_bytes");
      }

    /**
     *  Get total number of rx bytes received through rmnet0 or ppp0
     *
     * @return number of Rx bytes through rmnet0 or ppp0
     */
    public static long getMobileRxBytes() {
        return getMobileStat("rx_bytes");
    }

    /**
     * Get the total number of packets sent through all network interfaces.
     *
     * @return the number of packets sent through all network interfaces
     */
    public static long getTotalTxPkts() {
        return getTotalStat("tx_packets");
    }

    /**
     * Get the total number of packets received through all network interfaces.
     *
     * @return the number of packets received through all network interfaces
     */
    public static long getTotalRxPkts() {
        return getTotalStat("rx_packets");
    }

    /**
     * Get the total number of bytes sent through all network interfaces.
     *
     * @return the number of bytes sent through all network interfaces
     */
    public static long getTotalTxBytes() {
        return getTotalStat("tx_bytes");
    }

    /**
     * Get the total number of bytes received through all network interfaces.
     *
     * @return the number of bytes received through all network interfaces
     */
    public static long getTotalRxBytes() {
        return getTotalStat("rx_bytes");
    }

    /**
     * Gets network bytes sent for this UID.
     * The statistics are across all interfaces.
     * The statistics come from /proc/uid_stat.
     *
     * {@see android.os.Process#myUid()}.
     * 
     * @param uid
     * @return byte count
     */
    public static long getUidTxBytes(int uid) {
        return getNumberFromFilePath("/proc/uid_stat/" + uid + "/tcp_snd");
    }

    /**
     * Gets network bytes received for this UID.
     * The statistics are across all interfaces.
     * The statistics come from /proc/uid_stat.
     *
     * {@see android.os.Process#myUid()}.
     *
     * @param uid
     * @return byte count
     */
    public static long getUidRxBytes(int uid) {
        return getNumberFromFilePath("/proc/uid_stat/" + uid + "/tcp_rcv");
    }

    private static String TAG = "netstat";
    private static final byte[] buf = new byte[16];

    private static long getTotalStat(String whatStat) {
        File netdir = new File("/sys/class/net");

        File[] nets = netdir.listFiles();
        if (nets == null) {
            return 0;
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

    private static long getMobileStat(String whatStat) {
        String filename = "/sys/class/net/rmnet0/statistics/" + whatStat;
        RandomAccessFile raf = getFile(filename);
        if (raf == null) {
            filename = "/sys/class/net/ppp0/statistics/" + whatStat;
            raf = getFile(filename);
        }
        if (raf == null) {
            return 0L;
        }
        return getNumberFromFile(raf, filename);
    }

    // File will have format <number><newline>
    private static long getNumberFromFilePath(String filename) {
        RandomAccessFile raf = getFile(filename);
        if (raf == null) {
            return 0L;
        }
        return getNumberFromFile(raf, filename);
    }

    private static synchronized long getNumberFromFile(RandomAccessFile raf, String filename) {
        try {
            raf.read(buf);
            raf.close();
        } catch (IOException e) {
            Log.w(TAG, "Exception getting TCP bytes from " + filename, e);
            return 0L;
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
