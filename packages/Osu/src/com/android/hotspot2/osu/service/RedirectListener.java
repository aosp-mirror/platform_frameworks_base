package com.android.hotspot2.osu.service;

import android.util.Log;

import com.android.hotspot2.osu.OSUManager;
import com.android.hotspot2.osu.OSUOperationStatus;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Random;

public class RedirectListener extends Thread {
    private static final long ThreadTimeout = 3000L;
    private static final long UserTimeout = 3600000L;
    private static final int MaxRetry = 5;
    private static final String TAG = "OSULSN";

    private static final String HTTPResponseHeader =
            "HTTP/1.1 304 Not Modified\r\n" +
                    "Server: dummy\r\n" +
                    "Keep-Alive: timeout=500, max=5\r\n\r\n";

    private static final String GoodBye =
            "<html>" +
                    "<head><title>Goodbye</title></head>" +
                    "<body>" +
                    "<h3>Killing browser...</h3>" +
                    "</body>" +
                    "</html>\r\n";

    private final OSUManager mOSUManager;
    private final String mSpName;
    private final ServerSocket mServerSocket;
    private final String mPath;
    private final URL mURL;
    private final Object mLock = new Object();

    private boolean mListening;
    private OSUOperationStatus mUserStatus;
    private volatile boolean mAborted;

    public RedirectListener(OSUManager osuManager, String spName) throws IOException {
        mOSUManager = osuManager;
        mSpName = spName;
        mServerSocket = new ServerSocket(0, 5, InetAddress.getLocalHost());
        Random rnd = new Random(System.currentTimeMillis());
        mPath = "rnd" + Integer.toString(Math.abs(rnd.nextInt()), Character.MAX_RADIX);
        mURL = new URL("http", mServerSocket.getInetAddress().getHostAddress(),
                mServerSocket.getLocalPort(), mPath);

        Log.d(TAG, "Redirect URL: " + mURL);
        setName("HS20-Redirect-Listener");
        setDaemon(true);
    }

    public void startService() throws IOException {
        start();
        synchronized (mLock) {
            long bail = System.currentTimeMillis() + ThreadTimeout;
            long remainder = ThreadTimeout;
            while (remainder > 0 && !mListening) {
                try {
                    mLock.wait(remainder);
                } catch (InterruptedException ie) {
                    /**/
                }
                if (mListening) {
                    break;
                }
                remainder = bail - System.currentTimeMillis();
            }
            if (!mListening) {
                throw new IOException("Failed to start listener");
            } else {
                Log.d(TAG, "OSU Redirect listener running");
            }
        }
    }

    public boolean waitForUser() {
        boolean success;
        synchronized (mLock) {
            long bail = System.currentTimeMillis() + UserTimeout;
            long remainder = UserTimeout;
            while (remainder > 0 && mUserStatus == null) {
                try {
                    mLock.wait(remainder);
                } catch (InterruptedException ie) {
                    /**/
                }
                if (mUserStatus != null) {
                    break;
                }
                remainder = bail - System.currentTimeMillis();
            }
            success = mUserStatus == OSUOperationStatus.UserInputComplete;
        }
        abort();
        return success;
    }

    public void abort() {
        try {
            mAborted = true;
            mServerSocket.close();
        } catch (IOException ioe) {
            /**/
        }
    }

    public URL getURL() {
        return mURL;
    }

    @Override
    public void run() {
        int count = 0;
        synchronized (mLock) {
            mListening = true;
            mLock.notifyAll();
        }

        boolean terminate = false;

        for (; ; ) {
            count++;
            try (Socket instance = mServerSocket.accept()) {
                try (BufferedReader in = new BufferedReader(
                        new InputStreamReader(instance.getInputStream(), StandardCharsets.UTF_8))) {
                    boolean detected = false;
                    StringBuilder sb = new StringBuilder();
                    String s;
                    while ((s = in.readLine()) != null) {
                        sb.append(s).append('\n');
                        if (!detected && s.startsWith("GET")) {
                            String[] segments = s.split(" ");
                            if (segments.length == 3 &&
                                    segments[2].startsWith("HTTP/") &&
                                    segments[1].regionMatches(1, mPath, 0, mPath.length())) {
                                detected = true;
                            }
                        }
                        if (s.length() == 0) {
                            break;
                        }
                    }
                    Log.d(TAG, "Redirect receive: " + sb);
                    String response = null;
                    if (detected) {
                        response = status(OSUOperationStatus.UserInputComplete);
                        if (response == null) {
                            response = GoodBye;
                            terminate = true;
                        }
                    }
                    try (BufferedWriter out = new BufferedWriter(
                            new OutputStreamWriter(instance.getOutputStream(),
                                    StandardCharsets.UTF_8))) {

                        out.write(HTTPResponseHeader);
                        if (response != null) {
                            out.write(response);
                        }
                    }
                    if (terminate) {
                        break;
                    } else if (count > MaxRetry) {
                        status(OSUOperationStatus.UserInputAborted);
                        break;
                    }
                }
            } catch (IOException ioe) {
                if (mAborted) {
                    return;
                } else if (count > MaxRetry) {
                    status(OSUOperationStatus.UserInputAborted);
                    break;
                }
            }
        }
    }

    private String status(OSUOperationStatus status) {
        Log.d(TAG, "User input status: " + status);
        synchronized (mLock) {
            mUserStatus = status;
            mLock.notifyAll();
        }
        String message = (status == OSUOperationStatus.UserInputAborted) ?
                "Browser closed" : null;

        return mOSUManager.notifyUser(status, message, mSpName);
    }
}
