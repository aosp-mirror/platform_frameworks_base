/*
 * Copyright (C) 2011 The Android Open Source Project
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
package com.android.tests.bandwidthenforcement;

import android.app.IntentService;
import android.content.Intent;
import android.net.SntpClient;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.Random;

import libcore.io.Streams;

/*
 * Test Service that tries to connect to the web via different methods and outputs the results to
 * the log and a output file.
 */
public class BandwidthEnforcementTestService extends IntentService {
    private static final String TAG = "BandwidthEnforcementTestService";
    private static final String OUTPUT_FILE = "BandwidthEnforcementTestServiceOutputFile";

    public BandwidthEnforcementTestService() {
        super(TAG);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        Log.d(TAG, "Trying to establish a connection.");
        // Read output file path from intent.
        String outputFile = intent.getStringExtra(OUTPUT_FILE);
        dumpResult("testUrlConnection", testUrlConnection(), outputFile);
        dumpResult("testUrlConnectionv6", testUrlConnectionv6(), outputFile);
        dumpResult("testSntp", testSntp(), outputFile);
        dumpResult("testDns", testDns(), outputFile);
    }

    public static void dumpResult(String tag, boolean result, String outputFile) {
        Log.d(TAG, "Test output file: " + outputFile);
        try {
            if (outputFile != null){
                File extStorage = Environment.getExternalStorageDirectory();
                File outFile = new File(extStorage, outputFile);
                FileWriter writer = new FileWriter(outFile, true);
                BufferedWriter out = new BufferedWriter(writer);
                if (result) {
                    out.append(tag + ":fail\n");
                } else {
                    out.append(tag + ":pass\n");
                }
                out.close();
            }
            if (result) {
                Log.e(TAG, tag + " FAILURE ====================");
                Log.e(TAG, tag + " FAILURE was able to use data");
                Log.e(TAG, tag + " FAILURE ====================");
            } else {
                Log.d(TAG, tag + " success; unable to use data");
            }
        } catch (IOException e) {
            Log.e(TAG, "Could not write file " + e.getMessage());
        }
    }

    /**
     * Tests a normal http url connection.
     * @return true if it was able to connect, false otherwise.
     */
    public static boolean testUrlConnection() {
        try {
            final HttpURLConnection conn = (HttpURLConnection) new URL("http://www.google.com/")
            .openConnection();
            try {
                conn.connect();
                final String content = Streams.readFully(
                        new InputStreamReader(conn.getInputStream()));
                if (content.contains("Google")) {
                    return true;
                }
            } finally {
                conn.disconnect();
            }
        } catch (IOException e) {
            Log.d(TAG, "error: " + e);
        }
        return false;
    }

    /**
     * Tests a ipv6 http url connection.
     * @return true if it was able to connect, false otherwise.
     */
    public static boolean testUrlConnectionv6() {
        try {
            final HttpURLConnection conn = (HttpURLConnection) new URL("http://ipv6.google.com/")
            .openConnection();
            try {
                conn.connect();
                final String content = Streams.readFully(
                        new InputStreamReader(conn.getInputStream()));
                if (content.contains("Google")) {
                    return true;
                }
            } finally {
                conn.disconnect();
            }
        } catch (IOException e) {
            Log.d(TAG, "error: " + e);
        }
        return false;
    }

    /**
     * Tests to connect via sntp.
     * @return true if it was able to connect, false otherwise.
     */
    public static boolean testSntp() {
        final SntpClient client = new SntpClient();
        if (client.requestTime("0.pool.ntp.org", 10000)) {
            return true;
        }
        return false;
    }

    /**
     * Tests dns query.
     * @return true if it was able to connect, false otherwise.
     */
    public static boolean testDns() {
        try {
            final DatagramSocket socket = new DatagramSocket();
            try {
                socket.setSoTimeout(10000);

                final byte[] query = buildDnsQuery("www", "android", "com");
                final DatagramPacket queryPacket = new DatagramPacket(
                        query, query.length, InetAddress.parseNumericAddress("8.8.8.8"), 53);
                socket.send(queryPacket);

                final byte[] reply = new byte[query.length];
                final DatagramPacket replyPacket = new DatagramPacket(reply, reply.length);
                socket.receive(replyPacket);
                return true;

            } finally {
                socket.close();
            }
        } catch (IOException e) {
            Log.d(TAG, "error: " + e);
        }
        return false;
    }

    /**
     * Helper method to build a dns query
     * @param query the dns strings of the server
     * @return the byte array of the dns query to send
     * @throws IOException
     */
    private static byte[] buildDnsQuery(String... query) throws IOException {
        final Random random = new Random();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        final byte[] id = new byte[2];
        random.nextBytes(id);

        out.write(id);
        out.write(new byte[] { 0x01, 0x00 });
        out.write(new byte[] { 0x00, 0x01 });
        out.write(new byte[] { 0x00, 0x00 });
        out.write(new byte[] { 0x00, 0x00 });
        out.write(new byte[] { 0x00, 0x00 });

        for (String phrase : query) {
            final byte[] bytes = phrase.getBytes("US-ASCII");
            out.write(bytes.length);
            out.write(bytes);
        }
        out.write(0x00);

        out.write(new byte[] { 0x00, 0x01 });
        out.write(new byte[] { 0x00, 0x01 });

        return out.toByteArray();
    }
}
