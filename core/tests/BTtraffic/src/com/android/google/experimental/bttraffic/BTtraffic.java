package com.google.android.experimental.bttraffic;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.Exception;
import java.lang.Runtime;
import java.lang.RuntimeException;
import java.lang.Process;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public class BTtraffic extends Service {
    public static final String TAG = "bttraffic";
    static final String SERVICE_NAME = "bttraffic";
    static final String SYS_SERVICE_NAME = "com.android.bluetooth";
    static final UUID SERVICE_UUID = UUID.fromString("5e8945b0-1234-5432-a5e2-0800200c9a67");
    volatile Thread mWorkerThread;
    volatile boolean isShuttingDown = false;
    volatile boolean isServer = false;

    public BTtraffic() {}

    static void safeClose(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException e) {
            Log.d(TAG, "Unable to close resource.\n");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return 0;
        }
        if ("stop".equals(intent.getAction())) {
            stopService();
        } else if ("start".equals(intent.getAction())) {
            startWorker(intent);
        } else {
            Log.d(TAG, "unknown action: + " + intent.getAction());
        }
        return 0;
    }

    private void startWorker(Intent intent) {
        if (mWorkerThread != null) {
            Log.d(TAG, "worker thread already active");
            return;
        }
        isShuttingDown = false;
        String remoteAddr = intent.getStringExtra("addr");
        Log.d(TAG, "startWorker: addr=" + remoteAddr);
        Runnable worker =
                remoteAddr == null
                        ? new ListenerRunnable(this, intent)
                        : new SenderRunnable(this, remoteAddr, intent);
        isServer = remoteAddr == null ? true: false;
        mWorkerThread = new Thread(worker, "BTtrafficWorker");
        try {
            startMonitor();
            Log.d(TAG, "Monitor service started");
            mWorkerThread.start();
            Log.d(TAG, "Worker thread started");
        } catch (Exception e) {
            Log.d(TAG, "Failed to start service", e);
        }
    }

    private void startMonitor()
            throws Exception {
        if (isServer) {
            Log.d(TAG, "Start monitor on server");
            String[] startmonitorCmd = {
                    "/system/bin/am",
                    "startservice",
                    "-a", "start",
                    "-e", "java", SERVICE_NAME,
                    "-e", "hal", SYS_SERVICE_NAME,
                    "com.google.android.experimental.svcmonitor/.SvcMonitor"
            };
            Process ps = new ProcessBuilder()
                    .command(startmonitorCmd)
                    .redirectErrorStream(true)
                    .start();
        } else {
            Log.d(TAG, "No need to start SvcMonitor on client");
        }
    }

    private void stopMonitor()
            throws Exception {
        if (isServer) {
            Log.d(TAG, "StopMonitor on server");
            String[] stopmonitorCmd = {
                    "/system/bin/am",
                    "startservice",
                    "-a", "stop",
                    "com.google.android.experimental.svcmonitor/.SvcMonitor"
            };
            Process ps = new ProcessBuilder()
                    .command(stopmonitorCmd)
                    .redirectErrorStream(true)
                    .start();
        } else {
            Log.d(TAG, "No need to stop Svcmonitor on client");
        }
    }

    public void stopService() {
        if (mWorkerThread == null) {
            Log.d(TAG, "no active thread");
            return;
        }

        isShuttingDown = true;

        try {
            stopMonitor();
        } catch (Exception e) {
            Log.d(TAG, "Unable to stop SvcMonitor!", e);
        }

        if (Thread.currentThread() != mWorkerThread) {
            mWorkerThread.interrupt();
            Log.d(TAG, "Interrupting thread");
            try {
                mWorkerThread.join();
            } catch (InterruptedException e) {
                Log.d(TAG, "Unable to join thread!");
            }
        }

        mWorkerThread = null;
        stopSelf();
        Log.d(TAG, "Service stopped");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    public static class ListenerRunnable implements Runnable {
        private final BTtraffic bttraffic;
        private final boolean sendAck;
        private Intent intent;
        private final int maxbuffersize = 20 * 1024 * 1024;

        public ListenerRunnable(BTtraffic bttraffic, Intent intent) {
            this.bttraffic = bttraffic;
            this.sendAck = intent.getBooleanExtra("ack", true);
            this.intent = intent;
        }

        @Override
        public void run() {
            BluetoothServerSocket serverSocket;

            try {
                Log.d(TAG, "getting server socket");
                serverSocket = BluetoothAdapter.getDefaultAdapter()
                        .listenUsingInsecureRfcommWithServiceRecord(
                                SERVICE_NAME, SERVICE_UUID);
            } catch (IOException e) {
                Log.d(TAG, "error creating server socket, stopping thread");
                bttraffic.stopService();
                return;
            }

            Log.d(TAG, "got server socket, starting accept loop");
            BluetoothSocket socket = null;
            try {
                Log.d(TAG, "accepting");
                socket = serverSocket.accept();

                if (!Thread.interrupted()) {
                    Log.d(TAG, "accepted, listening");
                    doListening(socket.getInputStream(), socket.getOutputStream());
                    Log.d(TAG, "listen finished");
                }
            } catch (IOException e) {
                Log.d(TAG, "error while accepting or listening", e);
            } finally {
                Log.d(TAG, "Linster interruped");
                Log.d(TAG, "closing socket and stopping service");
                safeClose(serverSocket);
                safeClose(socket);
                if (!bttraffic.isShuttingDown)
                    bttraffic.stopService();
            }

        }

        private void doListening(InputStream inputStream, OutputStream outputStream)
                throws IOException {
            ByteBuffer byteBuffer = ByteBuffer.allocate(maxbuffersize);

            while (!Thread.interrupted()) {
                readBytesIntoBuffer(inputStream, byteBuffer, 4);
                byteBuffer.flip();
                int length = byteBuffer.getInt();
                if (Thread.interrupted())
                    break;
                readBytesIntoBuffer(inputStream, byteBuffer, length);

                if (sendAck)
                    outputStream.write(0x55);
            }
        }

        void readBytesIntoBuffer(InputStream inputStream, ByteBuffer byteBuffer, int numToRead)
                throws IOException {
            byteBuffer.clear();
            while (true) {
                int position = byteBuffer.position();
                int remaining = numToRead - position;
                if (remaining == 0) {
                    break;
                }
                int count = inputStream.read(byteBuffer.array(), position, remaining);
                if (count < 0) {
                    throw new IOException("read the EOF");
                }
                byteBuffer.position(position + count);
            }
        }
    }

    public static class SenderRunnable implements Runnable {
        private final BTtraffic bttraffic;
        private final String remoteAddr;
        private final int pkgsize, period;
        private final int defaultpkgsize = 1024;
        private final int defaultperiod = 5000;
        private static ByteBuffer lengthBuffer = ByteBuffer.allocate(4);

        public SenderRunnable(BTtraffic bttraffic, String remoteAddr, Intent intent) {
            this.bttraffic = bttraffic;
            this.remoteAddr = remoteAddr;
            this.pkgsize = intent.getIntExtra("size", defaultpkgsize);
            this.period = intent.getIntExtra("period", defaultperiod);
        }

        @Override
        public void run() {
            BluetoothDevice device = null;
            try {
                device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(remoteAddr);
            } catch (IllegalArgumentException e) {
                Log.d(TAG, "Invalid BT MAC address!\n");
            }
            if (device == null) {
                Log.d(TAG, "can't find matching device, stopping thread and service");
                bttraffic.stopService();
                return;
            }

            BluetoothSocket socket = null;
            try {
                Log.d(TAG, "connecting to device with MAC addr: " + remoteAddr);
                socket = device.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID);
                socket.connect();
                Log.d(TAG, "connected, starting to send");
                doSending(socket.getOutputStream());
                Log.d(TAG, "send stopped, stopping service");
            } catch (Exception e) {
                Log.d(TAG, "error while sending", e);
            } finally {
                Log.d(TAG, "finishing, closing thread and service");
                safeClose(socket);
                if (!bttraffic.isShuttingDown)
                    bttraffic.stopService();
            }
        }

        private void doSending(OutputStream outputStream) throws IOException {
            Log.w(TAG, "doSending");
            try {
                Random random = new Random(System.currentTimeMillis());

                byte[] bytes = new byte[pkgsize];
                random.nextBytes(bytes);
                while (!Thread.interrupted()) {
                    writeBytes(outputStream, bytes.length);
                    outputStream.write(bytes, 0, bytes.length);
                    if (period < 0)
                        break;
                    if (period == 0)
                        continue;

                    SystemClock.sleep(period);
                }
                Log.d(TAG, "Sender interrupted");
            } catch (IOException e) {
                Log.d(TAG, "doSending got error", e);
            }
        }

        private static void writeBytes(OutputStream outputStream, int value) throws IOException {
            lengthBuffer.putInt(value);
            lengthBuffer.flip();
            outputStream.write(lengthBuffer.array(), lengthBuffer.position(), lengthBuffer.limit());
        }
    }

}
