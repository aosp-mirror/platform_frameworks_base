package com.google.android.experimental.svcmonitor;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.lang.Runnable;
import java.lang.Thread;
import java.util.Set;

public class SvcMonitor extends Service {
    public static final String TAG = "svcmonitor";
    String javaProc, halProc;
    volatile Thread tMonitor;
    int period;

    public SvcMonitor() {};

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return 0;
        }
        Log.d(TAG, "Starting SvcMonitor");
        if ("stop".equals(intent.getAction())) {
            stopService();
        } else if ("start".equals(intent.getAction())) {
            startMonitor(intent);
        } else if ("change".equals(intent.getAction())) {
            changeConfig(intent);
        } else {
            Log.d(TAG, "unknown action: + " + intent.getAction());
        }
        return 0;
    }

    private void changeConfig(Intent intent) {
        if (tMonitor == null) {
            Log.d(TAG, "Service not active. Start service first");
            return;
        }
        stopThread();
        startMonitor(intent);
    }

    private void startMonitor(Intent intent) {
        if (tMonitor != null) {
            Log.d(TAG, "thread already active");
            return;
        }
        javaProc = intent.getStringExtra("java");
        halProc = intent.getStringExtra("hal");
        period = intent.getIntExtra("period", 1000);
        if (javaProc == null || halProc == null || period < 100) {
            Log.d(TAG, "Failed starting monitor, invalid arguments.");
            stopSelf();
            return;
        }
        Runnable monitor = new MonitorRunnable(this);
        tMonitor = new Thread(monitor);
        tMonitor.start();
    }

    private void stopService() {
        stopThread();
        stopSelf();
        Log.d(TAG, "SvcMonitor stopped");
    }

    private void stopThread() {
        if (tMonitor == null) {
            Log.d(TAG, "no active thread");
            return;
        }
        Log.d(TAG, "interrupting monitor thread");
        tMonitor.interrupt();
        try {
            tMonitor.join();
        } catch (InterruptedException e) {
            Log.d(TAG, "Unable to finish monitor thread");
        }
        tMonitor = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    public static class MonitorRunnable implements Runnable {
        long java_time_old, hal_time_old, cpu_time_old = -1;
        String javaPID, halPID;
        SvcMonitor svcmonitor;
        static String javaProcTAG;
        int period;

        public MonitorRunnable(SvcMonitor svcmonitor) {
            this.svcmonitor = svcmonitor;
            this.period = svcmonitor.period;
            javaPID = getPIDof(svcmonitor.javaProc);
            halPID = getPIDof(svcmonitor.halProc);
            java_time_old = getPsTime(javaPID);
            hal_time_old = getPsTime(halPID);
            cpu_time_old = getPsTime("");
            javaProcTAG = String.valueOf(svcmonitor.javaProc.toCharArray());
        }

        @Override
        public void run() {
            if (halPID.isEmpty() || javaPID.isEmpty()) {
                Log.d(javaProcTAG, "No such process: " +
                        (halPID.isEmpty() ? svcmonitor.halProc : svcmonitor.javaProc));
                return;
            }
            while (!Thread.interrupted()) {
                calculateUsage();
                SystemClock.sleep(period);
            }
            Log.d(TAG, "Stopping monitor thread");
        }

        private void calculateUsage() {
            long java_time = getPsTime(javaPID);
            long hal_time = getPsTime(halPID);
            long cpu_time = getPsTime("");

            if (cpu_time_old >= 0) {
                float java_diff = (float) (java_time - java_time_old);
                float hal_diff = (float) (hal_time - hal_time_old);
                float cpu_diff = (float) (cpu_time - cpu_time_old);
                Log.w(javaProcTAG, "\n----------------\n");
                Log.w(javaProcTAG, "JAVA level CPU: "
                        + (java_diff * 100.0 / cpu_diff) + "%\n");
                Log.w(javaProcTAG, " HAL level CPU: "
                        + (hal_diff * 100.0 / cpu_diff) + "%\n");
                Log.w(javaProcTAG, " SYS level CPU: "
                        + ((java_diff + hal_diff) * 100.0 / cpu_diff) + "%\n");
            } else {
                Log.w(TAG, "Waiting for status\n");
            }

            java_time_old = java_time;
            hal_time_old = hal_time;
            cpu_time_old = cpu_time;
        }

        private String getPIDof(String psName) {
            String pid = "";

            try {
                String[] cmd = {"/system/bin/sh", "-c", "ps | grep " + psName};
                Process ps = Runtime.getRuntime().exec(cmd);
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(ps.getInputStream()));
                String temp = in.readLine();
                if (temp == null || temp.isEmpty())
                    throw new IOException("No such process: " + psName);
                pid = temp.split(" +")[1];
                in.close();
            } catch (IOException e) {
                Log.d(javaProcTAG, "Error finding PID of process: " + psName + "\n", e);
            }
            return pid;
        }

        private long getPsTime(String pid) {
            String psStat = getPsStat("/" + pid);
            String[] statBreakDown = psStat.split(" +");
            long psTime;

            if (pid.isEmpty()) {
                psTime = Long.parseLong(statBreakDown[1])
                        + Long.parseLong(statBreakDown[2])
                        + Long.parseLong(statBreakDown[3])
                        + Long.parseLong(statBreakDown[4]);
            } else {
                psTime = Long.parseLong(statBreakDown[13])
                        + Long.parseLong(statBreakDown[14]);
            }

            return psTime;
        }

        private String getPsStat(String psname) {
            String stat = "";
            try {
                FileInputStream fs = new FileInputStream("/proc" + psname + "/stat");
                BufferedReader br = new BufferedReader(new InputStreamReader(fs));
                stat = br.readLine();
                fs.close();
            } catch (IOException e) {
                Log.d(TAG, "Error retreiving stat. \n");
            }
            return stat;
        }
    }
}
