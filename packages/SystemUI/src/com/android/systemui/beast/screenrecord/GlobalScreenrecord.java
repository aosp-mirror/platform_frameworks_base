/*
 * Copyright (C) 2011 The Android Open Source Project
 * Modifications Copyright (C) The OmniROM Project
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
 *
 * Per article 5 of the Apache 2.0 License, some modifications to this code
 * were made by the OmniROM Project.
 *
 * Modifications Copyright (C) 2013 The OmniROM Project
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package com.android.systemui.beast.screenrecord;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.content.res.Resources;
import android.media.MediaActionSound;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.UserHandle;
import android.util.Log;

import com.android.systemui.R;
import com.android.systemui.util.NotificationChannels;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;

class GlobalScreenrecord {
    private static final String TAG = "GlobalScreenrecord";

    private static final int SCREENRECORD_NOTIFICATION_ID = 42;
    private static final int MSG_TASK_ENDED = 1;
    private static final int MSG_TASK_ERROR = 2;

    private static final String TMP_PATH = "/sdcard/__tmp_screenrecord.mp4";

    private Context mContext;
    private Handler mHandler;
    private NotificationManager mNotificationManager;

    private MediaActionSound mCameraSound;

    private CaptureThread mCaptureThread;

    private class CaptureThread extends Thread {
        public void run() {
            Runtime rt = Runtime.getRuntime();
            String[] cmds = new String[] {"/system/bin/screenrecord", TMP_PATH};
            try {
                Process proc = rt.exec(cmds);
                BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()));

                while (!isInterrupted()) {
                    if (br.ready()) {
                        String log = br.readLine();
                        Log.d(TAG, log);
                    }

                    try {
                        int code = proc.exitValue();

                        // If the recording is still running, we won't reach here,
                        // but will land in the catch block below.
                        Message msg = Message.obtain(mHandler, MSG_TASK_ENDED, code, 0, null);
                        mHandler.sendMessage(msg);

                        // No need to stop the process, so we can exit this method early
                        return;
                    } catch (IllegalThreadStateException ignore) {
                        // ignored
                    }
                }

                // Terminate the recording process
                // HACK: There is no way to send SIGINT to a process, so we... hack
                rt.exec(new String[]{"killall", "-2", "screenrecord"});
            } catch (IOException e) {
                // Notify something went wrong
                Message msg = Message.obtain(mHandler, MSG_TASK_ERROR);
                mHandler.sendMessage(msg);

                // Log the error as well
                Log.e(TAG, "Error while starting the screenrecord process", e);
            }
        }
    };


    /**
     * @param context everything needs a context :(
     */
    public GlobalScreenrecord(Context context) {
        mContext = context;
        mHandler = new Handler() {
            public void handleMessage(Message msg) {
                if (msg.what == MSG_TASK_ENDED) {
                    // The screenrecord process stopped, act as if user
                    // requested the record to stop.
                    stopScreenrecord();
                } else if (msg.what == MSG_TASK_ERROR) {
                    mCaptureThread = null;
                    // TODO: Notify the error
                }
            }
        };

        mNotificationManager =
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

    }

    public boolean isRecording() {
        return (mCaptureThread != null);
    }

    /**
     * Starts recording the screen.
     */
    void takeScreenrecord() {
        if (mCaptureThread != null) {
            Log.e(TAG, "Capture Thread is already running, ignoring screenrecord start request");
            return;
        }

        mCaptureThread = new CaptureThread();
        mCaptureThread.start();

        updateNotification();
    }

    public void updateNotification(){
        final Resources r = mContext.getResources();
        // Display a notification
        Notification.Builder builder = new Notification.Builder(mContext, NotificationChannels.SCREENRECORDS)
            .setTicker(r.getString(R.string.screenrecord_notif_ticker))
            .setContentTitle(r.getString(R.string.screenrecord_notif_title))
            .setSmallIcon(R.drawable.ic_capture_video)
            .setWhen(System.currentTimeMillis())
            .setOngoing(true);

        Intent stopIntent = new Intent(mContext, TakeScreenrecordService.class)
            .setAction(TakeScreenrecordService.ACTION_STOP);
        PendingIntent stopPendIntent = PendingIntent.getService(mContext, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT);

        Intent pointerIntent = new Intent(mContext, TakeScreenrecordService.class)
            .setAction(TakeScreenrecordService.ACTION_TOGGLE_POINTER);
        PendingIntent pointerPendIntent = PendingIntent.getService(mContext, 0, pointerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT);

        boolean showTouches = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.SHOW_TOUCHES, 0, UserHandle.USER_CURRENT) != 0;
        int togglePointerIconId = showTouches ?
                R.drawable.ic_pointer_off :
                R.drawable.ic_pointer_on;
        int togglePointerStringId = showTouches ?
                R.string.screenrecord_notif_pointer_off :
                R.string.screenrecord_notif_pointer_on;
        builder
            .addAction(com.android.internal.R.drawable.ic_media_stop,
                r.getString(R.string.screenrecord_notif_stop), stopPendIntent)
            .addAction(togglePointerIconId,
                r.getString(togglePointerStringId), pointerPendIntent);

        Notification notif = builder.build();
        mNotificationManager.notify(SCREENRECORD_NOTIFICATION_ID, notif);
    }

    /**
     * Stops recording the screen.
     */
    void stopScreenrecord() {
        if (mCaptureThread == null) {
            Log.e(TAG, "No capture thread, cannot stop screen recording!");
            return;
        }

        mNotificationManager.cancel(SCREENRECORD_NOTIFICATION_ID);

        try {
            mCaptureThread.interrupt();
        } catch (Exception e) { /* ignore */ }

        // Wait a bit and copy the output file to a safe place
        while (mCaptureThread.isAlive()) {
            // wait...
        }

        // Give a second to screenrecord to finish the file
        mHandler.postDelayed(new Runnable() { public void run() {
            mCaptureThread = null;

            String fileName = "SCR_" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + ".mp4";
            File pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
            File screenshots = new File(pictures, "Screenshots");

            if (!screenshots.exists()) {
                if (!screenshots.mkdir()) {
                    Log.e(TAG, "Cannot create screenshots directory");
                    return;
                }
            }

            File input = new File(TMP_PATH);
            final File output = new File(screenshots, fileName);

            Log.d(TAG, "Copying file to " + output.getAbsolutePath());

            try {
                copyFileUsingStream(input, output);
            } catch (IOException e) {
                Log.e(TAG, "Unable to copy output file", e);
                Message msg = Message.obtain(mHandler, MSG_TASK_ERROR);
                mHandler.sendMessage(msg);
                return;
            } finally {
                input.delete();
            }

            // Make it appear in gallery, run MediaScanner
            // also make sure to tell media scammer that the tmp file got deleted
            MediaScannerConnection.scanFile(mContext,
                new String[] { output.getAbsolutePath(), input.getAbsolutePath() }, null,
                new MediaScannerConnection.OnScanCompletedListener() {
                public void onScanCompleted(String path, Uri uri) {
                    Log.i(TAG, "MediaScanner done scanning " + path);
                }
            });
        } }, 2000);
    }


    private static void copyFileUsingStream(File source, File dest) throws IOException {
        InputStream is = null;
        OutputStream os = null;
        try {
            is = new FileInputStream(source);
            os = new FileOutputStream(dest);
            byte[] buffer = new byte[1024];
            int length;
            while ((length = is.read(buffer)) > 0) {
                os.write(buffer, 0, length);
            }
        } finally {
            is.close();
            os.close();
        }
    }
}
