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

package com.android.statusbartest;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.ContentResolver;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Environment;
import android.os.Vibrator;
import android.os.Handler;
import android.os.UserHandle;
import android.util.Log;
import android.net.Uri;
import android.os.SystemClock;
import android.widget.RemoteViews;
import android.widget.TextView;
import android.widget.ProgressBar;
import android.os.PowerManager;

// private NM API
import android.app.INotificationManager;
import com.android.internal.statusbar.StatusBarNotification;

public class NotificationTestList extends TestActivity
{
    private final static String TAG = "NotificationTestList";

    NotificationManager mNM;
    Vibrator mVibrator;
    Handler mHandler = new Handler();

    long mActivityCreateTime;
    long mChronometerBase = 0;

    boolean mProgressDone = true;

    final int[] kNumberedIconResIDs = {
        R.drawable.notification0,
        R.drawable.notification1,
        R.drawable.notification2,
        R.drawable.notification3,
        R.drawable.notification4,
        R.drawable.notification5,
        R.drawable.notification6,
        R.drawable.notification7,
        R.drawable.notification8,
        R.drawable.notification9
    };
    final int kUnnumberedIconResID = R.drawable.notificationx;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        mVibrator = (Vibrator)getSystemService(VIBRATOR_SERVICE);
        mActivityCreateTime = System.currentTimeMillis();
    }

    @Override
    protected String tag() {
        return TAG;
    }

    @Override
    protected Test[] tests() {
        mNM = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        
        return mTests;
    }

    private Test[] mTests = new Test[] {
        new Test("Off and sound") {
            public void run() {
                PowerManager pm = (PowerManager)NotificationTestList.this.getSystemService(Context.POWER_SERVICE);
                PowerManager.WakeLock wl = 
                            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sound");
                wl.acquire();

                pm.goToSleep(SystemClock.uptimeMillis());

                Notification n = new Notification();
                n.sound = Uri.parse("file://" + Environment.getExternalStorageDirectory() +
                        "/virtual-void.mp3");
                Log.d(TAG, "n.sound=" + n.sound);

                mNM.notify(1, n);

                Log.d(TAG, "releasing wake lock");
                wl.release();
                Log.d(TAG, "released wake lock");
            }
        },

        new Test("Cancel #1") {
            public void run()
            {
                mNM.cancel(1);
            }
        },

        new Test("Button") {
            public void run() {
                Notification n = new Notification(R.drawable.icon1, null,
                        mActivityCreateTime);
                n.contentView = new RemoteViews(getPackageName(), R.layout.button_notification);
                n.flags |= Notification.FLAG_ONGOING_EVENT;
                n.contentIntent = makeIntent();
                n.contentView.setOnClickPendingIntent(R.id.button, makeIntent2());

                mNM.notify(1, n);
            }
        },

        new Test("custom intent on text view") {
            public void run() {
                Notification n = new Notification(R.drawable.icon1, null,
                        mActivityCreateTime);
                n.setLatestEventInfo(NotificationTestList.this, "Persistent #1",
                            "This is a notification!!!", null);
                n.contentView.setOnClickPendingIntent(com.android.internal.R.id.text,
                        makeIntent2());
                mNM.notify(1, n);
            }
        },

        new Test("Ticker 1 line") {
            public void run() {
                Notification n = new Notification(R.drawable.icon1, "tick tick tick",
                        mActivityCreateTime);
                n.setLatestEventInfo(NotificationTestList.this, "Persistent #1",
                            "This is a notification!!!", makeIntent());
                mNM.notify(1, n);
            }
        },

        new Test("No view") {
            public void run() {
                Notification n = new Notification(R.drawable.icon1, "No view",
                        System.currentTimeMillis());
                mNM.notify(1, n);
            }
        },

        new Test("No intent") {
            public void run() {
                Notification n = new Notification(R.drawable.icon1, "No intent",
                        System.currentTimeMillis());
                n.setLatestEventInfo(NotificationTestList.this, "No intent",
                            "No intent", null);
                mNM.notify(1, n);
            }
        },

        new Test("Layout") {
            public void run()
            {
                Notification n;

                n = new Notification(NotificationTestList.this,
                            R.drawable.ic_statusbar_missedcall,
                            null, System.currentTimeMillis()-(1000*60*60*24),
                            "(453) 123-2328",
                            "", null);
                n.flags |= Notification.FLAG_ONGOING_EVENT;

                mNM.notify(1, n);

                mNM.notify(2, new Notification(NotificationTestList.this,
                            R.drawable.ic_statusbar_email,
                            null, System.currentTimeMillis(),
                            "Mark Willem, Me (2)",
                            "Re: Didn't you get the memo?", null));

                mNM.notify(3, new Notification(NotificationTestList.this,
                            R.drawable.ic_statusbar_chat,
                            null, System.currentTimeMillis()+(1000*60*60*24),
                            "Sophia Winterlanden",
                            "Lorem ipsum dolor sit amet.", null));
            }
        },

        new Test("Bad Icon #1 (when=create)") {
            public void run() {
                Notification n = new Notification(R.layout.chrono_notification /* not an icon */,
                        null, mActivityCreateTime);
                n.setLatestEventInfo(NotificationTestList.this, "Persistent #1",
                            "This is the same notification!!!", makeIntent());
                mNM.notify(1, n);
            }
        },

        new Test("Bad Icon #1 (when=now)") {
            public void run() {
                Notification n = new Notification(R.layout.chrono_notification /* not an icon */,
                        null, System.currentTimeMillis());
                n.setLatestEventInfo(NotificationTestList.this, "Persistent #1",
                            "This is the same notification!!!", makeIntent());
                mNM.notify(1, n);
            }
        },

        new Test("Null Icon #1 (when=now)") {
            public void run() {
                Notification n = new Notification(0, null, System.currentTimeMillis());
                n.setLatestEventInfo(NotificationTestList.this, "Persistent #1",
                            "This is the same notification!!!", makeIntent());
                mNM.notify(1, n);
            }
        },

        new Test("Bad resource #1 (when=create)") {
            public void run() {
                Notification n = new Notification(R.drawable.icon2,
                        null, mActivityCreateTime);
                n.setLatestEventInfo(NotificationTestList.this, "Persistent #1",
                            "This is the same notification!!!", makeIntent());
                n.contentView.setInt(1 /*bogus*/, "bogus method", 666);
                mNM.notify(1, n);
            }
        },

        new Test("Bad resource #1 (when=now)") {
            public void run() {
                Notification n = new Notification(R.drawable.icon2,
                        null, System.currentTimeMillis());
                n.setLatestEventInfo(NotificationTestList.this, "Persistent #1",
                            "This is the same notification!!!", makeIntent());
                n.contentView.setInt(1 /*bogus*/, "bogus method", 666);
                mNM.notify(1, n);
            }
        },


        new Test("Bad resource #3") {
            public void run()
            {
                Notification n = new Notification(NotificationTestList.this,
                            R.drawable.ic_statusbar_missedcall,
                            null, System.currentTimeMillis()-(1000*60*60*24),
                            "(453) 123-2328",
                            "", null);
                n.contentView.setInt(1 /*bogus*/, "bogus method", 666);
                mNM.notify(3, n);
            }
        },

        new Test("Times") {
            public void run()
            {
                long now = System.currentTimeMillis();

                timeNotification(7, "24 hours from now", now+(1000*60*60*24));
                timeNotification(6, "12:01:00 from now", now+(1000*60*60*12)+(60*1000));
                timeNotification(5, "12 hours from now", now+(1000*60*60*12));
                timeNotification(4, "now", now);
                timeNotification(3, "11:59:00 ago", now-((1000*60*60*12)-(60*1000)));
                timeNotification(2, "12 hours ago", now-(1000*60*60*12));
                timeNotification(1, "24 hours ago", now-(1000*60*60*24));
            }
        },
        new StateStress("Stress - Ongoing / Latest", 100, 100, new Runnable[] {
                new Runnable() {
                    public void run() {
                        Log.d(TAG, "Stress - Ongoing/Latest 0");
                        Notification n = new Notification(NotificationTestList.this,
                                R.drawable.icon3,
                                null, System.currentTimeMillis(), "Stress - Ongoing",
                                "Notify me!!!", null);
                        n.flags |= Notification.FLAG_ONGOING_EVENT;
                        mNM.notify(1, n);
                    }
                },
                new Runnable() {
                    public void run() {
                        Log.d(TAG, "Stress - Ongoing/Latest 1");
                        Notification n = new Notification(NotificationTestList.this,
                                R.drawable.icon4,
                                null, System.currentTimeMillis(), "Stress - Latest",
                                "Notify me!!!", null);
                        //n.flags |= Notification.FLAG_ONGOING_EVENT;
                        mNM.notify(1, n);
                    }
                }
            }),

        new Test("Long") {
            public void run()
            {
                Notification n = new Notification();
                n.defaults |= Notification.DEFAULT_SOUND ;
                n.vibrate = new long[] {
                        300, 400, 300, 400, 300, 400, 300, 400, 300, 400, 300, 400,
                        300, 400, 300, 400, 300, 400, 300, 400, 300, 400, 300, 400, 
                        300, 400, 300, 400, 300, 400, 300, 400, 300, 400, 300, 400 };
                mNM.notify(1, n);
            }
        },

        new Test("Progress #1") {
            public void run() {
                final boolean PROGRESS_UPDATES_WHEN = true;
                if (!mProgressDone) return;
                mProgressDone = false;
                Thread t = new Thread() {
                    public void run() {
                        int x = 0;
                        while (!mProgressDone) {
                            Notification n = new Notification(R.drawable.icon1, null,
                                    PROGRESS_UPDATES_WHEN
                                    ? System.currentTimeMillis()
                                    : mActivityCreateTime);
                            RemoteViews v = new RemoteViews(getPackageName(),
                                    R.layout.progress_notification);
                            
                            v.setProgressBar(R.id.progress_bar, 100, x, false);
                            v.setTextViewText(R.id.status_text, "Progress: " + x + "%");
                    
                            n.contentView = v;
                            n.flags |= Notification.FLAG_ONGOING_EVENT;

                            mNM.notify(500, n);
                            x = (x + 7) % 100;

                            try {
                                Thread.sleep(1000);
                            } catch (InterruptedException e) {
                                break;
                            }
                        }
                    }
                };
                t.start();
            }
        },

        new Test("Stop Progress") {
            public void run() {
                mProgressDone = true;
                mNM.cancel(500);
            }
        },

        new Test("Blue Lights") {
            public void run()
            {
                Notification n = new Notification();
                n.flags |= Notification.FLAG_SHOW_LIGHTS;
                n.ledARGB = 0xff0000ff;
                n.ledOnMS = 1;
                n.ledOffMS = 0;
                mNM.notify(1, n);
            }
        },

        new Test("Red Lights") {
            public void run()
            {
                Notification n = new Notification();
                n.flags |= Notification.FLAG_SHOW_LIGHTS;
                n.ledARGB = 0xffff0000;
                n.ledOnMS = 1;
                n.ledOffMS = 0;
                mNM.notify(1, n);
            }
        },

        new Test("Yellow Lights") {
            public void run()
            {
                Notification n = new Notification();
                n.flags |= Notification.FLAG_SHOW_LIGHTS;
                n.ledARGB = 0xffffff00;
                n.ledOnMS = 1;
                n.ledOffMS = 0;
                mNM.notify(1, n);
            }
        },

        new Test("Lights off") {
            public void run()
            {
                Notification n = new Notification();
                n.flags |= Notification.FLAG_SHOW_LIGHTS;
                n.ledARGB = 0x00000000;
                n.ledOnMS = 0;
                n.ledOffMS = 0;
                mNM.notify(1, n);
            }
        },

        new Test("Blue Blinking Slow") {
            public void run()
            {
                Notification n = new Notification();
                n.flags |= Notification.FLAG_SHOW_LIGHTS;
                n.ledARGB = 0xff0000ff;
                n.ledOnMS = 1300;
                n.ledOffMS = 1300;
                mNM.notify(1, n);
            }
        },

        new Test("Blue Blinking Fast") {
            public void run()
            {
                Notification n = new Notification();
                n.flags |= Notification.FLAG_SHOW_LIGHTS;
                n.ledARGB = 0xff0000ff;
                n.ledOnMS = 300;
                n.ledOffMS = 300;
                mNM.notify(1, n);
            }
        },

        new Test("Default All") {
            public void run()
            {
                Notification n = new Notification();
                n.defaults |= Notification.DEFAULT_ALL;
                mNM.notify(1, n);
            }
        },

        new Test("Default All, once") {
            public void run()
            {
                Notification n = new Notification();
                n.defaults |= Notification.DEFAULT_ALL;
                n.flags |= Notification.FLAG_ONLY_ALERT_ONCE ;
                mNM.notify(1, n);
            }
        },

        new Test("Content Sound") {
            public void run()
            {
                Notification n = new Notification();
                n.sound = Uri.parse(
                        "content://media/internal/audio/media/7");

                mNM.notify(1, n);
            }
        },

        new Test("Resource Sound") {
            public void run()
            {
                Notification n = new Notification();
                n.sound = Uri.parse(
                        ContentResolver.SCHEME_ANDROID_RESOURCE + "://" +
                        getPackageName() + "/raw/ringer");
                Log.d(TAG, "n.sound=" + n.sound);

                mNM.notify(1, n);
            }
        },

        new Test("Sound and Cancel") {
            public void run()
            {
                Notification n = new Notification();
                n.sound = Uri.parse(
                            "content://media/internal/audio/media/7");

                mNM.notify(1, n);
                SystemClock.sleep(200);
                mNM.cancel(1);
            }
        },

        new Test("Vibrate") {
            public void run()
            {
                Notification n = new Notification();
                    n.vibrate = new long[] { 0, 700, 500, 1000 };

                mNM.notify(1, n);
            }
        },

        new Test("Vibrate and cancel") {
            public void run()
            {
                Notification n = new Notification();
                    n.vibrate = new long[] { 0, 700, 500, 1000 };

                mNM.notify(1, n);
                SystemClock.sleep(500);
                mNM.cancel(1);
            }
        },

        new Test("Vibrate pattern") {
            public void run()
            {
                mVibrator.vibrate(new long[] { 250, 1000, 500, 2000 }, -1);
            }
        },

        new Test("Vibrate pattern repeating") {
            public void run()
            {
                mVibrator.vibrate(new long[] { 250, 1000, 500 }, 1);
            }
        },

        new Test("Vibrate 3s") {
            public void run()
            {
                mVibrator.vibrate(3000);
            }
        },

        new Test("Vibrate 100s") {
            public void run()
            {
                mVibrator.vibrate(100000);
            }
        },

        new Test("Vibrate off") {
            public void run()
            {
                mVibrator.cancel();
            }
        },

        new Test("Cancel #1") {
            public void run() {
                mNM.cancel(1);
            }
        },

        new Test("Cancel #1 in 3 sec") {
            public void run() {
                mHandler.postDelayed(new Runnable() {
                            public void run() {
                                Log.d(TAG, "Cancelling now...");
                                mNM.cancel(1);
                            }
                        }, 3000);
            }
        },

        new Test("Cancel #2") {
            public void run() {
                mNM.cancel(2);
            }
        },

        new Test("Persistent #1") {
            public void run() {
                Notification n = new Notification(R.drawable.icon1, "tick tick tick",
                        mActivityCreateTime);
                n.setLatestEventInfo(NotificationTestList.this, "Persistent #1",
                            "This is a notification!!!", makeIntent());
                mNM.notify(1, n);
            }
        },

        new Test("Persistent #1 in 3 sec") {
            public void run() {
                mHandler.postDelayed(new Runnable() {
                            public void run() {
                                Notification n = new Notification(R.drawable.icon1,
                                        "            "
                                        + "tick tock tick tock\n\nSometimes notifications can "
                                        + "be really long and wrap to more than one line.\n"
                                        + "Sometimes."
                                        + "Ohandwhathappensifwehaveonereallylongstringarewesure"
                                        + "thatwesegmentitcorrectly?\n",
                                        System.currentTimeMillis());
                                n.setLatestEventInfo(NotificationTestList.this,
                                        "Still Persistent #1",
                                        "This is still a notification!!!",
                                        makeIntent());
                                mNM.notify(1, n);
                            }
                        }, 3000);
            }
        },

        new Test("Persistent #2") {
            public void run() {
                Notification n = new Notification(R.drawable.icon2, "tock tock tock",
                        System.currentTimeMillis());
                n.setLatestEventInfo(NotificationTestList.this, "Persistent #2",
                            "Notify me!!!", makeIntent());
                mNM.notify(2, n);
            }
        },

        new Test("Persistent #3") {
            public void run() {
                Notification n = new Notification(R.drawable.icon2, "tock tock tock\nmooooo",
                        System.currentTimeMillis());
                n.setLatestEventInfo(NotificationTestList.this, "Persistent #3",
                            "Notify me!!!", makeIntent());
                mNM.notify(3, n);
            }
        },

        new Test("Persistent #2 Vibrate") {
            public void run() {
                Notification n = new Notification(R.drawable.icon2, "tock tock tock",
                        System.currentTimeMillis());
                n.setLatestEventInfo(NotificationTestList.this, "Persistent #2",
                            "Notify me!!!", makeIntent());
                n.defaults = Notification.DEFAULT_VIBRATE;
                mNM.notify(2, n);
            }
        },

        new Test("Persistent #1 - different icon") {
            public void run() {
                Notification n = new Notification(R.drawable.icon2, null,
                        mActivityCreateTime);
                n.setLatestEventInfo(NotificationTestList.this, "Persistent #1",
                            "This is the same notification!!!", makeIntent());
                mNM.notify(1, n);
            }
        },

        new Test("Chronometer Start") {
            public void run() {
                Notification n = new Notification(R.drawable.icon2, "me me me me",
                                                    System.currentTimeMillis());
                n.contentView = new RemoteViews(getPackageName(), R.layout.chrono_notification);
                mChronometerBase = SystemClock.elapsedRealtime();
                n.contentView.setChronometer(R.id.time, mChronometerBase, "Yay! (%s)", true);
                n.flags |= Notification.FLAG_ONGOING_EVENT;
                n.contentIntent = makeIntent();
                mNM.notify(2, n);
            }
        },

        new Test("Chronometer Stop") {
            public void run() {
                mHandler.postDelayed(new Runnable() {
                        public void run() {
                            Log.d(TAG, "Chronometer Stop");
                            Notification n = new Notification();
                            n.icon = R.drawable.icon1;
                            n.contentView = new RemoteViews(getPackageName(),
                                                             R.layout.chrono_notification);
                            n.contentView.setChronometer(R.id.time, mChronometerBase, null, false);
                            n.contentIntent = makeIntent();
                            mNM.notify(2, n);
                        }
                    }, 3000);
            }
        },

        new Test("Sequential Persistent") {
            public void run() {
                mNM.notify(1, notificationWithNumbers(1));
                mNM.notify(2, notificationWithNumbers(2));
            }
        },

        new Test("Replace Persistent") {
            public void run() {
                mNM.notify(1, notificationWithNumbers(1));
                mNM.notify(1, notificationWithNumbers(1));
            }
        },

        new Test("Run and Cancel (n=1)") {
            public void run() {
                mNM.notify(1, notificationWithNumbers(1));
                mNM.cancel(1);
            }
        },

        new Test("Run an Cancel (n=2)") {
            public void run() {
                mNM.notify(1, notificationWithNumbers(1));
                mNM.notify(2, notificationWithNumbers(2));
                mNM.cancel(2);
            }
        },

        // Repeatedly notify and cancel -- triggers bug #670627
        new Test("Bug 670627") {
            public void run() {
                for (int i = 0; i < 10; i++) {
                  Log.d(TAG, "Add two notifications");
                  mNM.notify(1, notificationWithNumbers(1));
                  mNM.notify(2, notificationWithNumbers(2));
                  Log.d(TAG, "Cancel two notifications");
                  mNM.cancel(1);
                  mNM.cancel(2);
                }
            }
        },

        new Test("Ten Notifications") {
            public void run() {
                for (int i = 0; i < 2; i++) {
                    Notification n = new Notification(
                            kNumberedIconResIDs[i],
                            null, System.currentTimeMillis());
                    n.number = i;
                    n.setLatestEventInfo(
                            NotificationTestList.this,
                            "Persistent #" + i,
                            "Notify me!!!" + i, 
                            null);
                    n.flags |= Notification.FLAG_ONGOING_EVENT;
                    mNM.notify((i+1)*10, n);
                }
                for (int i = 2; i < 10; i++) {
                    Notification n = new Notification(
                            kNumberedIconResIDs[i],
                            null, System.currentTimeMillis());
                    n.number = i;
                    n.setLatestEventInfo(
                            NotificationTestList.this,
                            "Persistent #" + i,
                            "Notify me!!!" + i, 
                            null);
                    mNM.notify((i+1)*10, n);
                }
            }
        },
        
        new Test("Cancel eight notifications") {
            public void run() {
                for (int i = 1; i < 9; i++) {
                    mNM.cancel((i+1)*10);
                }
            }
        },
        
        new Test("Cancel the other two notifications") {
            public void run() {
                mNM.cancel(10);
                mNM.cancel(100);
            }
        },
        
        new Test("Persistent with numbers 1") {
            public void run() {
                mNM.notify(1, notificationWithNumbers(1));
            }
        },

        new Test("Persistent with numbers 22") {
            public void run() {
                mNM.notify(1, notificationWithNumbers(22));
            }
        },

        new Test("Persistent with numbers 333") {
            public void run() {
                mNM.notify(1, notificationWithNumbers(333));
            }
        },

        new Test("Persistent with numbers 4444") {
            public void run() {
                mNM.notify(1, notificationWithNumbers(4444));
            }
        },

        new Test("PRIORITY_HIGH") {
            public void run() {
                Notification n = new Notification.Builder(NotificationTestList.this)
                    .setSmallIcon(R.drawable.notification5)
                    .setContentTitle("High priority")
                    .setContentText("This should appear before all others")
                    .setPriority(Notification.PRIORITY_HIGH)
                    .getNotification();

                int[] idOut = new int[1];
                try {
                    INotificationManager directLine = mNM.getService();
                    directLine.enqueueNotificationWithTag(
                            getPackageName(),
                            null, 
                            100, 
                            n,
                            idOut,
                            UserHandle.myUserId());
                } catch (android.os.RemoteException ex) {
                    // oh well
                }
            }
        },

        new Test("PRIORITY_MAX") {
            public void run() {
                Notification n = new Notification.Builder(NotificationTestList.this)
                    .setSmallIcon(R.drawable.notification9)
                    .setContentTitle("MAX priority")
                    .setContentText("This might appear as an intruder alert")
                    .setPriority(Notification.PRIORITY_MAX)
                    .getNotification();

                int[] idOut = new int[1];
                try {
                    INotificationManager directLine = mNM.getService();
                    directLine.enqueueNotificationWithTag(
                            getPackageName(),
                            null, 
                            200, 
                            n,
                            idOut,
                            UserHandle.myUserId());
                } catch (android.os.RemoteException ex) {
                    // oh well
                }
            }
        },

        new Test("PRIORITY_MIN") {
            public void run() {
                Notification n = new Notification.Builder(NotificationTestList.this)
                    .setSmallIcon(R.drawable.notification0)
                    .setContentTitle("MIN priority")
                    .setContentText("You should not see this")
                    .setPriority(Notification.PRIORITY_MIN)
                    .getNotification();

                int[] idOut = new int[1];
                try {
                    INotificationManager directLine = mNM.getService();
                    directLine.enqueueNotificationWithTag(
                            getPackageName(),
                            null, 
                            1, 
                            n,
                            idOut,
                            UserHandle.myUserId());
                } catch (android.os.RemoteException ex) {
                    // oh well
                }
            }
        },

        new Test("Crash") {
            public void run()
            {
                PowerManager.WakeLock wl
                        = ((PowerManager)NotificationTestList.this.getSystemService(Context.POWER_SERVICE))
                            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "crasher");
                wl.acquire();
                mHandler.postDelayed(new Runnable() {
                            public void run() {
                                throw new RuntimeException("Die!");
                            }
                        }, 10000);

            }
        },

    };

    private Notification notificationWithNumbers(int num) {
        Notification n = new Notification(this,
                (num >= 0 && num < kNumberedIconResIDs.length)
                    ? kNumberedIconResIDs[num]
                    : kUnnumberedIconResID,
                null,
                System.currentTimeMillis(),
                "Notification", "Number=" + num,
                null);
        n.number = num;
        return n;
    }

    private PendingIntent makeIntent() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_HOME);
        return PendingIntent.getActivity(this, 0, intent, 0);
    }

    private PendingIntent makeIntent2() {
        Intent intent = new Intent(this, StatusBarTest.class);
        return PendingIntent.getActivity(this, 0, intent, 0);
    }


    class StateStress extends Test {
        StateStress(String name, int pause, int iterations, Runnable[] tasks) {
            super(name);
            mPause = pause;
            mTasks = tasks;
            mIteration = iterations;
        }
        Runnable[] mTasks;
        int mNext;
        int mIteration;
        long mPause;
        Runnable mRunnable = new Runnable() {
            public void run() {
                mTasks[mNext].run();
                mNext++;
                if (mNext >= mTasks.length) {
                    mNext = 0;
                    mIteration--;
                    if (mIteration <= 0) {
                        return;
                    }
                }
                mHandler.postDelayed(mRunnable, mPause);
            }
        };
        public void run() {
            mNext = 0;
            mHandler.postDelayed(mRunnable, mPause);
        }
    }

    void timeNotification(int n, String label, long time) {
        mNM.notify(n, new Notification(NotificationTestList.this,
                    R.drawable.ic_statusbar_missedcall, null,
                    time, label, "" + new java.util.Date(time), null));

    }

    Bitmap loadBitmap(int resId) {
        BitmapDrawable bd = (BitmapDrawable)getResources().getDrawable(resId);
        return Bitmap.createBitmap(bd.getBitmap());
    }
}

