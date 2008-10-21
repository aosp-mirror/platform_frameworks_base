package com.google.android.net;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.NoSuchElementException;
import java.util.StringTokenizer;

/**
 * Gets network send/receive statistics for this process.
 * The statistics come from /proc/pid/stat, using the ATOP kernel modification.
 */
public class NetStats {
    private static String statsFile = "/proc/" + android.os.Process.myPid() + "/stat";

    private static String TAG = "netstat";

    /**
     * Returns network stats for this process.
     * Returns stats of 0 if problem encountered.
     *
     * @return [bytes sent, bytes received]
     */
    public static long[] getStats() {
        long result[] = new long[2];

        try {
            BufferedReader br = new BufferedReader(new FileReader(statsFile), 512);
            String line = br.readLine(); // Skip first line
            line = br.readLine();
            StringTokenizer st = new StringTokenizer(line);
            st.nextToken(); // disk read
            st.nextToken(); // disk sectors
            st.nextToken(); // disk write
            st.nextToken(); // disk sectors
            st.nextToken(); // tcp send
            result[0] = Long.parseLong(st.nextToken()); // tcp bytes sent
            st.nextToken(); //tcp recv
            result[1] = Long.parseLong(st.nextToken()); // tcp bytes recv
        } catch (IOException e) {
            // Probably wrong kernel; no point logging exception
        } catch (NoSuchElementException e) {
        } catch (NullPointerException e) {
        }
        return result;
    }
}
