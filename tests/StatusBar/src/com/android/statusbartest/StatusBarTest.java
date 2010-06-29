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

import android.app.ListActivity;
import android.app.Notification;
import android.app.NotificationManager;
import android.widget.ArrayAdapter;
import android.view.View;
import android.widget.ListView;
import android.content.Intent;
import android.app.PendingIntent;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.StatusBarManager;
import android.os.Vibrator;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.net.Uri;
import android.os.SystemClock;
import android.widget.RemoteViews;
import android.widget.Toast;
import android.os.PowerManager;
import android.view.Window;
import android.view.WindowManager;

public class StatusBarTest extends TestActivity
{
    private final static String TAG = "StatusBarTest";
    StatusBarManager mStatusBarManager;
    NotificationManager mNotificationManager;
    Handler mHandler = new Handler();

    @Override
    protected String tag() {
        return TAG;
    }

    @Override
    protected Test[] tests() {
        mStatusBarManager = (StatusBarManager)getSystemService(STATUS_BAR_SERVICE);
        mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        
        return mTests;
    }

    private Test[] mTests = new Test[] {
        new Test("Hide") {
            public void run() {
                Window win = getWindow();
                WindowManager.LayoutParams winParams = win.getAttributes();
                winParams.flags |= WindowManager.LayoutParams.FLAG_FULLSCREEN;
                win.setAttributes(winParams);
            }
        },
        new Test("Show") {
            public void run() {
                Window win = getWindow();
                WindowManager.LayoutParams winParams = win.getAttributes();
                winParams.flags &= ~WindowManager.LayoutParams.FLAG_FULLSCREEN;
                win.setAttributes(winParams);
            }
        },
        new Test("Immersive: Enter") {
            public void run() {
                setImmersive(true);
            }
        },
        new Test("Immersive: Exit") {
            public void run() {
                setImmersive(false);
            }
        },
        new Test("Priority notification") {
            public void run() {
                Notification not = new Notification(StatusBarTest.this,
                                R.drawable.ic_statusbar_missedcall,
                                "tick tick tick",
                                System.currentTimeMillis()-(1000*60*60*24),
                                "(453) 123-2328",
                                "", null
                                );
                not.flags |= Notification.FLAG_HIGH_PRIORITY;
                Intent fullScreenIntent = new Intent(StatusBarTest.this, TestAlertActivity.class);
                int id = (int)System.currentTimeMillis(); // XXX HAX
                fullScreenIntent.putExtra("id", id);
                not.fullScreenIntent = PendingIntent.getActivity(
                    StatusBarTest.this,
                    0,
                    fullScreenIntent,
                    PendingIntent.FLAG_CANCEL_CURRENT);
                // if you tap on it you should get the original alert box
                not.contentIntent = not.fullScreenIntent;
                mNotificationManager.notify(id, not);
            }
        },
        new Test("Disable Alerts") {
            public void run() {
                mStatusBarManager.disable(StatusBarManager.DISABLE_NOTIFICATION_ALERTS);
            }
        },
        new Test("Disable Ticker") {
            public void run() {
                mStatusBarManager.disable(StatusBarManager.DISABLE_NOTIFICATION_TICKER);
            }
        },
        new Test("Disable Expand in 3 sec.") {
            public void run() {
                mHandler.postDelayed(new Runnable() {
                        public void run() {
                            mStatusBarManager.disable(StatusBarManager.DISABLE_EXPAND);
                        }
                    }, 3000);
            }
        },
        new Test("Disable Notifications in 3 sec.") {
            public void run() {
                mHandler.postDelayed(new Runnable() {
                        public void run() {
                            mStatusBarManager.disable(StatusBarManager.DISABLE_NOTIFICATION_ICONS);
                        }
                    }, 3000);
            }
        },
        new Test("Disable Expand + Notifications in 3 sec.") {
            public void run() {
                mHandler.postDelayed(new Runnable() {
                        public void run() {
                            mStatusBarManager.disable(StatusBarManager.DISABLE_EXPAND
                                    | StatusBarManager.DISABLE_NOTIFICATION_ICONS);
                        }
                    }, 3000);
            }
        },
        new Test("Enable everything") {
            public void run() {
                mStatusBarManager.disable(0);
            }
        },
        new Test("Enable everything in 3 sec.") {
            public void run() {
                mHandler.postDelayed(new Runnable() {
                        public void run() {
                            mStatusBarManager.disable(0);
                        }
                    }, 3000);
            }
        },
        new Test("Notify in 3 sec.") {
            public void run() {
                mHandler.postDelayed(new Runnable() {
                        public void run() {
                            mNotificationManager.notify(1,
                                    new Notification(StatusBarTest.this,
                                            R.drawable.ic_statusbar_missedcall,
                                            "tick tick tick",
                                            System.currentTimeMillis()-(1000*60*60*24),
                                            "(453) 123-2328",
                                            "", null
                                            ));
                        }
                    }, 3000);
            }
        },
        new Test("Cancel Notification in 3 sec.") {
            public void run() {
                mHandler.postDelayed(new Runnable() {
                        public void run() {
                            mNotificationManager.cancel(1);
                        }
                    }, 3000);
            }
        },
        new Test("Expand") {
            public void run() {
                mStatusBarManager.expand();
            }
        },
        new Test("Expand in 3 sec.") {
            public void run() {
                mHandler.postDelayed(new Runnable() {
                        public void run() {
                            mStatusBarManager.expand();
                        }
                    }, 3000);
            }
        },
        new Test("Collapse in 3 sec.") {
            public void run() {
                mHandler.postDelayed(new Runnable() {
                        public void run() {
                            mStatusBarManager.collapse();
                        }
                    }, 3000);
            }
        },
        new Test("More icons") {
            public void run() {
                for (String slot: new String[] {
                            "sync_failing",
                            "gps",
                            "bluetooth",
                            "tty",
                            "speakerphone",
                            "mute",
                            "wifi",
                            "alarm_clock",
                            "secure",
                        }) {
                    mStatusBarManager.setIconVisibility(slot, true);
                }
            }
        },
    };
}
