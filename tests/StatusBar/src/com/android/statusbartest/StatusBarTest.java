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
import android.app.StatusBarManager;
import android.content.Intent;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

public class StatusBarTest extends TestActivity
{
    private final static String TAG = "StatusBarTest";
    StatusBarManager mStatusBarManager;
    NotificationManager mNotificationManager;
    Handler mHandler = new Handler();
    int mUiVisibility = 0;
    View mListView;

    View.OnSystemUiVisibilityChangeListener mOnSystemUiVisibilityChangeListener
            = new View.OnSystemUiVisibilityChangeListener() {
        public void onSystemUiVisibilityChange(int visibility) {
            Log.d(TAG, "onSystemUiVisibilityChange visibility=" + visibility);
        }
    };

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

    @Override
    public void onResume() {
        super.onResume();

        mListView = findViewById(android.R.id.list);
        mListView.setOnSystemUiVisibilityChangeListener(mOnSystemUiVisibilityChangeListener);
    }

    private Test[] mTests = new Test[] {
        new Test("toggle LOW_PROFILE (lights out)") {
            public void run() {
                if (0 != (mUiVisibility & View.SYSTEM_UI_FLAG_LOW_PROFILE)) {
                    mUiVisibility &= ~View.SYSTEM_UI_FLAG_LOW_PROFILE;
                } else {
                    mUiVisibility |= View.SYSTEM_UI_FLAG_LOW_PROFILE;
                }
                mListView.setSystemUiVisibility(mUiVisibility);
            }
        },
        new Test("toggle HIDE_NAVIGATION") {
            public void run() {
                if (0 != (mUiVisibility & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)) {
                    mUiVisibility &= ~View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
                } else {
                    mUiVisibility |= View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
                }
                mListView.setSystemUiVisibility(mUiVisibility);

            }
        },
        new Test("clear SYSTEM_UI_FLAGs") {
            public void run() {
                mUiVisibility = 0;
                mListView.setSystemUiVisibility(mUiVisibility);
            }
        },
//        new Test("no setSystemUiVisibility") {
//            public void run() {
//                View v = findViewById(android.R.id.list);
//                v.setOnSystemUiVisibilityChangeListener(null);
//                v.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
//            }
//        },
        new Test("systemUiVisibility: STATUS_BAR_DISABLE_HOME") {
            public void run() {
                mListView.setSystemUiVisibility(View.STATUS_BAR_DISABLE_HOME);
            }
        },
        new Test("Double Remove") {
            public void run() {
                Log.d(TAG, "set 0");
                mStatusBarManager.setIcon("speakerphone", R.drawable.stat_sys_phone, 0, null);
                Log.d(TAG, "remove 1");
                mStatusBarManager.removeIcon("tty");

                SystemClock.sleep(1000);

                Log.d(TAG, "set 1");
                mStatusBarManager.setIcon("tty", R.drawable.stat_sys_phone, 0, null);
                if (false) {
                    Log.d(TAG, "set 2");
                    mStatusBarManager.setIcon("tty", R.drawable.stat_sys_phone, 0, null);
                }
                Log.d(TAG, "remove 2");
                mStatusBarManager.removeIcon("tty");
                Log.d(TAG, "set 3");
                mStatusBarManager.setIcon("speakerphone", R.drawable.stat_sys_phone, 0, null);
            }
        },
        new Test("Hide (FLAG_FULLSCREEN)") {
            public void run() {
                Window win = getWindow();
                win.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                        WindowManager.LayoutParams.FLAG_FULLSCREEN);
                Log.d(TAG, "flags=" + Integer.toHexString(win.getAttributes().flags));
            }
        },
        new Test("Show (~FLAG_FULLSCREEN)") {
            public void run() {
                Window win = getWindow();
                win.setFlags(0, WindowManager.LayoutParams.FLAG_FULLSCREEN);
                Log.d(TAG, "flags=" + Integer.toHexString(win.getAttributes().flags));
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
                Intent fullScreenIntent = new Intent(StatusBarTest.this, TestAlertActivity.class);
                int id = (int)System.currentTimeMillis(); // XXX HAX
                fullScreenIntent.putExtra("id", id);
                PendingIntent pi = PendingIntent.getActivity(
                    StatusBarTest.this,
                    0,
                    fullScreenIntent,
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_MUTABLE_UNAUDITED);
                Notification not = new Notification.Builder(StatusBarTest.this)
                        .setSmallIcon(R.drawable.stat_sys_phone)
                        .setWhen(System.currentTimeMillis() - (1000 * 60 * 60 * 24))
                        .setContentTitle("Incoming call")
                        .setContentText("from: Imperious Leader")
                        .setContentIntent(pi)
                        .setFullScreenIntent(pi, true)
                        .setPriority(Notification.PRIORITY_HIGH)
                        .build();

                mNotificationManager.notify(id, not);
            }
        },
        new Test("Disable Alerts") {
            public void run() {
                StatusBarManager.DisableInfo info = new StatusBarManager.DisableInfo();
                info.setNotificationPeekingDisabled(true);
                mStatusBarManager.requestDisabledComponent(info, "test");
            }
        },
        new Test("Disable Ticker") {
            public void run() {
                StatusBarManager.DisableInfo info = new StatusBarManager.DisableInfo();
                info.setNotificationTickerDisabled(true);
                mStatusBarManager.requestDisabledComponent(info, "test");
            }
        },
        new Test("Disable Expand in 3 sec.") {
            public void run() {
                mHandler.postDelayed(new Runnable() {
                        public void run() {
                            StatusBarManager.DisableInfo info = new StatusBarManager.DisableInfo();
                            info.setStatusBarExpansionDisabled(true);
                            mStatusBarManager.requestDisabledComponent(info, "test");
                        }
                    }, 3000);
            }
        },
        new Test("Disable Notifications in 3 sec.") {
            public void run() {
                mHandler.postDelayed(new Runnable() {
                        public void run() {
                            StatusBarManager.DisableInfo info = new StatusBarManager.DisableInfo();
                            info.setNotificationIconsDisabled(true);
                            mStatusBarManager.requestDisabledComponent(info, "test");
                        }
                    }, 3000);
            }
        },
        new Test("Disable Expand + Notifications in 3 sec.") {
            public void run() {
                mHandler.postDelayed(new Runnable() {
                        public void run() {
                            StatusBarManager.DisableInfo info = new StatusBarManager.DisableInfo();
                            info.setStatusBarExpansionDisabled(true);
                            info.setNotificationIconsDisabled(true);
                            mStatusBarManager.requestDisabledComponent(info, "test");
                        }
                    }, 3000);
            }
        },
        new Test("Disable Home (StatusBarManager)") {
            public void run() {
                StatusBarManager.DisableInfo info = new StatusBarManager.DisableInfo();
                info.setNavigationHomeDisabled(true);
                mStatusBarManager.requestDisabledComponent(info, "test");
            }
        },
        new Test("Disable Back (StatusBarManager)") {
            public void run() {
                StatusBarManager.DisableInfo info = new StatusBarManager.DisableInfo();
                info.setBackDisabled(true);
                mStatusBarManager.requestDisabledComponent(info, "test");
            }
        },
        new Test("Disable Recent (StatusBarManager)") {
            public void run() {
                StatusBarManager.DisableInfo info = new StatusBarManager.DisableInfo();
                info.setRecentsDisabled(true);
                mStatusBarManager.requestDisabledComponent(info, "test");
            }
        },
        new Test("Disable Clock") {
            public void run() {
                StatusBarManager.DisableInfo info = new StatusBarManager.DisableInfo();
                info.setClockDisabled(true);
                mStatusBarManager.requestDisabledComponent(info, "test");
            }
        },
        new Test("Disable System Info") {
            public void run() {
                StatusBarManager.DisableInfo info = new StatusBarManager.DisableInfo();
                info.setSystemIconsDisabled(true);
                mStatusBarManager.requestDisabledComponent(info, "test");
            }
        },
        new Test("Disable everything in 3 sec") {
            public void run() {
                mHandler.postDelayed(new Runnable() {
                        public void run() {
                            StatusBarManager.DisableInfo info = new StatusBarManager.DisableInfo();
                            info.setDisableAll();
                            mStatusBarManager.requestDisabledComponent(info, "test");
                        }
                    }, 3000);
            }
        },
        new Test("Enable everything") {
            public void run() {
                StatusBarManager.DisableInfo info = new StatusBarManager.DisableInfo();
                mStatusBarManager.requestDisabledComponent(info, "test");
            }
        },
        new Test("Enable everything in 3 sec.") {
            public void run() {
                mHandler.postDelayed(new Runnable() {
                        public void run() {
                            StatusBarManager.DisableInfo info = new StatusBarManager.DisableInfo();
                            info.setEnableAll();
                            mStatusBarManager.requestDisabledComponent(info, "test");
                        }
                    }, 3000);
            }
        },
        new Test("Notify in 3 sec.") {
            public void run() {
                mHandler.postDelayed(new Runnable() {
                        public void run() {
                            mNotificationManager.notify(1,
                                    new Notification(
                                            R.drawable.ic_statusbar_missedcall,
                                            "tick tick tick",
                                            System.currentTimeMillis()-(1000*60*60*24)
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
        new Test("Expand notifications") {
            public void run() {
                mStatusBarManager.expandNotificationsPanel();
            }
        },
        new Test(" ... in 3 sec.") {
            public void run() {
                mHandler.postDelayed(new Runnable() {
                        public void run() {
                            mStatusBarManager.expandNotificationsPanel();
                        }
                    }, 3000);
            }
        },
        new Test("Expand settings") {
            public void run() {
                mStatusBarManager.expandSettingsPanel();
            }
        },
        new Test(" ... in 3 sec.") {
            public void run() {
                mHandler.postDelayed(new Runnable() {
                        public void run() {
                            mStatusBarManager.expandSettingsPanel();
                        }
                    }, 3000);
            }
        },
        new Test("Collapse panels in 3 sec.") {
            public void run() {
                mHandler.postDelayed(new Runnable() {
                        public void run() {
                            mStatusBarManager.collapsePanels();
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
