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

package com.google.android.test.activity;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class ActivityTestMain extends Activity {
    static final String TAG = "ActivityTest";

    static final String KEY_CONFIGURATION = "configuration";

    ActivityManager mAm;
    PowerManager mPower;
    AlarmManager mAlarm;
    Configuration mOverrideConfig;
    int mSecondUser;

    ArrayList<ServiceConnection> mConnections = new ArrayList<ServiceConnection>();

    ServiceConnection mIsolatedConnection;

    static final String SLOW_RECEIVER_ACTION = "com.google.android.test.activity.SLOW_ACTION";
    static final String SLOW_RECEIVER_EXTRA = "slow_ordinal";

    static final int MSG_SPAM = 1;
    static final int MSG_SPAM_ALARM = 2;
    static final int MSG_SLOW_RECEIVER = 3;
    static final int MSG_SLOW_ALARM_RECEIVER = 4;
    static final int MSG_REPLACE_BROADCAST = 5;

    final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_SPAM: {
                    boolean fg = msg.arg1 != 0;
                    Intent intent = new Intent(ActivityTestMain.this, SpamActivity.class);
                    Bundle options = null;
                    if (fg) {
                        ActivityOptions opts = ActivityOptions.makeTaskLaunchBehind();
                        options = opts.toBundle();
                    }
                    startActivity(intent, options);
                    scheduleSpam(!fg);
                } break;
                case MSG_SPAM_ALARM: {
                    long when = SystemClock.elapsedRealtime();
                    Intent intent = new Intent(ActivityTestMain.this, AlarmSpamReceiver.class);
                    intent.setAction("com.example.SPAM_ALARM=" + when);
                    PendingIntent pi = PendingIntent.getBroadcast(ActivityTestMain.this,
                            0, intent, 0);
                    mAlarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, when+(30*1000), pi);
                    scheduleSpamAlarm(30*1000);
                } break;
                case MSG_SLOW_RECEIVER: {
                    // Several back to back, to illustrate dispatch policy
                    Intent intent = new Intent(ActivityTestMain.this, SlowReceiver.class);
                    intent.setAction(SLOW_RECEIVER_ACTION);
                    intent.putExtra(SLOW_RECEIVER_EXTRA, 1);
                    sendOrderedBroadcast(intent, null, mSlowReceiverCompletion, mHandler,
                            Activity.RESULT_OK, null, null);
                    intent.putExtra(SLOW_RECEIVER_EXTRA, 2);
                    sendOrderedBroadcast(intent, null, mSlowReceiverCompletion, mHandler,
                            Activity.RESULT_OK, null, null);
                    intent.putExtra(SLOW_RECEIVER_EXTRA, 3);
                    sendOrderedBroadcast(intent, null, mSlowReceiverCompletion, mHandler,
                            Activity.RESULT_OK, null, null);
                } break;
                case MSG_SLOW_ALARM_RECEIVER: {
                    // Several back to back, to illustrate dispatch policy
                    Intent intent = new Intent(ActivityTestMain.this, SlowReceiver.class);
                    intent.setAction(SLOW_RECEIVER_ACTION);
                    intent.putExtra(SLOW_RECEIVER_EXTRA, 1);
                    sendOrderedBroadcast(intent, null, mSlowReceiverCompletion, mHandler,
                            Activity.RESULT_OK, null, null);
                    intent.putExtra(SLOW_RECEIVER_EXTRA, 2);
                    sendOrderedBroadcast(intent, null, mSlowReceiverCompletion, mHandler,
                            Activity.RESULT_OK, null, null);
                    intent.putExtra(SLOW_RECEIVER_EXTRA, 3);
                    sendOrderedBroadcast(intent, null, mSlowReceiverCompletion, mHandler,
                            Activity.RESULT_OK, null, null);

                    // Also send a broadcast alarm to evaluate the alarm fast-forward policy
                    intent.putExtra(SLOW_RECEIVER_EXTRA, 4);
                    PendingIntent pi = PendingIntent.getBroadcast(ActivityTestMain.this, 1, intent, 0);
                    AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                    long now = SystemClock.elapsedRealtime();
                    Log.i(TAG, "Setting alarm for now + 5 seconds");
                    am.setExact(AlarmManager.ELAPSED_REALTIME, now + 5_000, pi);
                } break;
                case MSG_REPLACE_BROADCAST: {
                    Intent intent = new Intent(ActivityTestMain.this, SlowReceiver.class);
                    intent.setAction(SLOW_RECEIVER_ACTION);
                    intent.putExtra(SLOW_RECEIVER_EXTRA, 1);
                    sendOrderedBroadcast(intent, null, mSlowReceiverCompletion, mHandler,
                            Activity.RESULT_OK, null, null);
                    intent.putExtra(SLOW_RECEIVER_EXTRA, 2);
                    sendOrderedBroadcast(intent, null, mSlowReceiverCompletion, mHandler,
                            Activity.RESULT_OK, null, null);
                    intent.putExtra(SLOW_RECEIVER_EXTRA, 5038);
                    intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
                    sendOrderedBroadcast(intent, null, mSlowReceiverCompletion, mHandler,
                            Activity.RESULT_OK, null, null);
                } break;
            }
            super.handleMessage(msg);
        }
    };

    final BroadcastReceiver mSlowReceiverCompletion = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final int extra = intent.getIntExtra(SLOW_RECEIVER_EXTRA, -1);
            final String msg = "Slow receiver " + extra + " completed";
            Toast.makeText(ActivityTestMain.this, msg, Toast.LENGTH_LONG)
                    .show();
            Log.i(TAG, msg);
        }
    };

    class BroadcastResultReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Bundle res = getResultExtras(true);
            int user = res.getInt("user", -1);
            Toast.makeText(ActivityTestMain.this,
                    "Receiver executed as user "
                    + (user >= 0 ? Integer.toString(user) : "unknown"),
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.i(TAG, "Referrer: " + getReferrer());

        mAm = getSystemService(ActivityManager.class);
        mPower = getSystemService(PowerManager.class);
        mAlarm = getSystemService(AlarmManager.class);
        if (savedInstanceState != null) {
            mOverrideConfig = savedInstanceState.getParcelable(KEY_CONFIGURATION);
            if (mOverrideConfig != null) {
                applyOverrideConfiguration(mOverrideConfig);
            }
        }

        UserManager um = (UserManager)getSystemService(Context.USER_SERVICE);
        List<UserInfo> users = um.getUsers();
        mSecondUser = Integer.MAX_VALUE;
        for (UserInfo ui : users) {
            if (ui.id != 0 && mSecondUser > ui.id) {
                mSecondUser = ui.id;
            }
        }

        /*
        AlertDialog ad = new AlertDialog.Builder(this).setTitle("title").setMessage("message").create();
        ad.getWindow().getAttributes().type = WindowManager.LayoutParams.TYPE_SYSTEM_ERROR;
        ad.show();
        */
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add("Animate!").setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                AlertDialog.Builder builder = new AlertDialog.Builder(ActivityTestMain.this,
                        R.style.SlowDialog);
                builder.setTitle("This is a title");
                builder.show();
                return true;
            }
        });
        menu.add("Bind!").setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
                Intent intent = new Intent(ActivityTestMain.this, SingleUserService.class);
                ServiceConnection conn = new ServiceConnection() {
                    @Override
                    public void onServiceConnected(ComponentName name, IBinder service) {
                        Log.i(TAG, "Service connected " + name + " " + service);
                    }
                    @Override
                    public void onServiceDisconnected(ComponentName name) {
                        Log.i(TAG, "Service disconnected " + name);
                    }
                };
                if (bindService(intent, conn, Context.BIND_AUTO_CREATE)) {
                    mConnections.add(conn);
                } else {
                    Toast.makeText(ActivityTestMain.this, "Failed to bind",
                            Toast.LENGTH_LONG).show();
                }
                return true;
            }
        });
        menu.add("Start!").setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
                Intent intent = new Intent(ActivityTestMain.this, SingleUserService.class);
                startService(intent);
                return true;
            }
        });
        menu.add("Rebind Isolated!").setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
                Intent intent = new Intent(ActivityTestMain.this, IsolatedService.class);
                ServiceConnection conn = new ServiceConnection() {
                    @Override
                    public void onServiceConnected(ComponentName name, IBinder service) {
                        Log.i(TAG, "Isolated service connected " + name + " " + service);
                    }
                    @Override
                    public void onServiceDisconnected(ComponentName name) {
                        Log.i(TAG, "Isolated service disconnected " + name);
                    }
                };
                if (mIsolatedConnection != null) {
                    Log.i(TAG, "Unbinding existing service: " + mIsolatedConnection);
                    unbindService(mIsolatedConnection);
                    mIsolatedConnection = null;
                }
                Log.i(TAG, "Binding new service: " + conn);
                if (bindService(intent, conn, Context.BIND_AUTO_CREATE)) {
                    mIsolatedConnection = conn;
                } else {
                    Toast.makeText(ActivityTestMain.this, "Failed to bind",
                            Toast.LENGTH_LONG).show();
                }
                return true;
            }
        });
        menu.add("Send!").setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
                Intent intent = new Intent(ActivityTestMain.this, SingleUserReceiver.class);
                sendOrderedBroadcast(intent, null, new BroadcastResultReceiver(),
                        null, Activity.RESULT_OK, null, null);
                return true;
            }
        });
        menu.add("Call!").setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
                ContentProviderClient cpl = getContentResolver().acquireContentProviderClient(
                        SingleUserProvider.AUTHORITY);
                Bundle res = null;
                try {
                    res = cpl.call("getuser", null, null);
                } catch (RemoteException e) {
                }
                int user = res != null ? res.getInt("user", -1) : -1;
                Toast.makeText(ActivityTestMain.this,
                        "Provider executed as user "
                        + (user >= 0 ? Integer.toString(user) : "unknown"),
                        Toast.LENGTH_LONG).show();
                cpl.release();
                return true;
            }
        });
        menu.add("Send to user 0!").setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
                Intent intent = new Intent(ActivityTestMain.this, UserTarget.class);
                sendOrderedBroadcastAsUser(intent, new UserHandle(0), null,
                        new BroadcastResultReceiver(),
                        null, Activity.RESULT_OK, null, null);
                return true;
            }
        });
        menu.add("Send to user " + mSecondUser + "!").setOnMenuItemClickListener(
                new MenuItem.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
                Intent intent = new Intent(ActivityTestMain.this, UserTarget.class);
                sendOrderedBroadcastAsUser(intent, new UserHandle(mSecondUser), null,
                        new BroadcastResultReceiver(),
                        null, Activity.RESULT_OK, null, null);
                return true;
            }
        });
        menu.add("Bind to user 0!").setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
                Intent intent = new Intent(ActivityTestMain.this, ServiceUserTarget.class);
                ServiceConnection conn = new ServiceConnection() {
                    @Override
                    public void onServiceConnected(ComponentName name, IBinder service) {
                        Log.i(TAG, "Service connected " + name + " " + service);
                    }
                    @Override
                    public void onServiceDisconnected(ComponentName name) {
                        Log.i(TAG, "Service disconnected " + name);
                    }
                };
                if (bindServiceAsUser(intent, conn, Context.BIND_AUTO_CREATE, UserHandle.SYSTEM)) {
                    mConnections.add(conn);
                } else {
                    Toast.makeText(ActivityTestMain.this, "Failed to bind",
                            Toast.LENGTH_LONG).show();
                }
                return true;
            }
        });
        menu.add("Bind to user " + mSecondUser + "!").setOnMenuItemClickListener(
                new MenuItem.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
                Intent intent = new Intent(ActivityTestMain.this, ServiceUserTarget.class);
                ServiceConnection conn = new ServiceConnection() {
                    @Override
                    public void onServiceConnected(ComponentName name, IBinder service) {
                        Log.i(TAG, "Service connected " + name + " " + service);
                    }
                    @Override
                    public void onServiceDisconnected(ComponentName name) {
                        Log.i(TAG, "Service disconnected " + name);
                    }
                };
                if (bindServiceAsUser(intent, conn, Context.BIND_AUTO_CREATE,
                        new UserHandle(mSecondUser))) {
                    mConnections.add(conn);
                } else {
                    Toast.makeText(ActivityTestMain.this, "Failed to bind",
                            Toast.LENGTH_LONG).show();
                }
                return true;
            }
        });
        menu.add("Density!").setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
                if (mOverrideConfig == null) {
                    mOverrideConfig = new Configuration();
                }
                if (mOverrideConfig.densityDpi == Configuration.DENSITY_DPI_UNDEFINED) {
                    mOverrideConfig.densityDpi = (getApplicationContext().getResources()
                            .getConfiguration().densityDpi*2)/3;
                } else {
                    mOverrideConfig.densityDpi = Configuration.DENSITY_DPI_UNDEFINED;
                }
                recreate();
                return true;
            }
        });
        menu.add("HashArray").setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
                ArrayMapTests.run();
                return true;
            }
        });
        menu.add("Add App Recent").setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
                addAppRecents(1);
                return true;
            }
        });
        menu.add("Add App 10x Recent").setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
                addAppRecents(10);
                return true;
            }
        });
        menu.add("Exclude!").setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
                setExclude(true);
                return true;
            }
        });
        menu.add("Include!").setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
                setExclude(false);
                return true;
            }
        });
        menu.add("Open Doc").setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
                ActivityManager.AppTask task = findDocTask();
                if (task == null) {
                    Intent intent = new Intent(ActivityTestMain.this, DocActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                            | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                            | Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS);
                    startActivity(intent);
                } else {
                    task.moveToFront();
                }
                return true;
            }
        });
        menu.add("Replace broadcast").setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
                scheduleReplaceBroadcast();
                return true;
            }
        });
        menu.add("Stack Doc").setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
                ActivityManager.AppTask task = findDocTask();
                if (task != null) {
                    ActivityManager.RecentTaskInfo recent = task.getTaskInfo();
                    Intent intent = new Intent(ActivityTestMain.this, DocActivity.class);
                    if (recent.id >= 0) {
                        // Stack on top.
                        intent.putExtra(DocActivity.LABEL, "Stacked");
                    } else {
                        // Start root activity.
                        intent.putExtra(DocActivity.LABEL, "New Root");
                    }
                    task.startActivity(ActivityTestMain.this, intent, null);
                }
                return true;
            }
        });
        menu.add("Slow receiver").setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
                scheduleSlowReceiver();
                return true;
            }
        });
        menu.add("Slow alarm receiver").setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
                scheduleSlowAlarmReceiver();
                return true;
            }
        });
        menu.add("Spam!").setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
                scheduleSpam(false);
                return true;
            }
        });
        menu.add("Track time").setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("text/plain");
                intent.putExtra(Intent.EXTRA_TEXT, "We are sharing this with you!");
                ActivityOptions options = ActivityOptions.makeBasic();
                Intent receiveIntent = new Intent(ActivityTestMain.this, TrackTimeReceiver.class);
                receiveIntent.putExtra("something", "yeah, this is us!");
                options.requestUsageTimeReport(PendingIntent.getBroadcast(ActivityTestMain.this,
                        0, receiveIntent, PendingIntent.FLAG_CANCEL_CURRENT));
                startActivity(Intent.createChooser(intent, "Who do you love?"), options.toBundle());
                return true;
            }
        });
        menu.add("Transaction fail").setOnMenuItemClickListener(
                new MenuItem.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
                Intent intent = new Intent(Intent.ACTION_MAIN);
                intent.putExtra("gulp", new int[1024 * 1024]);
                startActivity(intent);
                return true;
            }
        });
        menu.add("Spam idle alarm").setOnMenuItemClickListener(
                new MenuItem.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
                scheduleSpamAlarm(0);
                return true;
            }
        });
        menu.add("Ignore battery optimizations").setOnMenuItemClickListener(
                new MenuItem.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
                Intent intent;
                if (!mPower.isIgnoringBatteryOptimizations(getPackageName())) {
                    intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.fromParts("package", getPackageName(), null));
                } else {
                    intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                }
                startActivity(intent);
                return true;
            }
        });
        return true;
    }

    @Override
    protected void onStart() {
        super.onStart();
        buildUi();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.i(TAG, "I'm such a slooow poor loser");
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
        }
        Log.i(TAG, "See?");
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mOverrideConfig != null) {
            outState.putParcelable(KEY_CONFIGURATION, mOverrideConfig);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        mHandler.removeMessages(MSG_SPAM_ALARM);
        mHandler.removeMessages(MSG_SLOW_RECEIVER);
        for (ServiceConnection conn : mConnections) {
            unbindService(conn);
        }
        mConnections.clear();
        if (mIsolatedConnection != null) {
            unbindService(mIsolatedConnection);
            mIsolatedConnection = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mHandler.removeMessages(MSG_SPAM);
    }

    void addAppRecents(int count) {
        ActivityManager am = (ActivityManager)getSystemService(Context.ACTIVITY_SERVICE);
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
        intent.setComponent(new ComponentName(this, ActivityTestMain.class));
        for (int i=0; i<count; i++) {
            ActivityManager.TaskDescription desc = new ActivityManager.TaskDescription();
            desc.setLabel("Added #" + i);
            Bitmap bitmap = BitmapFactory.decodeResource(getResources(), R.drawable.icon);
            if ((i&1) == 0) {
                desc.setIcon(bitmap);
            }
            int taskId = am.addAppTask(this, intent, desc, bitmap);
            Log.i(TAG, "Added new task id #" + taskId);
        }
    }

    void setExclude(boolean exclude) {
        ActivityManager am = (ActivityManager)getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.AppTask> tasks = am.getAppTasks();
        int taskId = getTaskId();
        for (int i=0; i<tasks.size(); i++) {
            ActivityManager.AppTask task = tasks.get(i);
            if (task.getTaskInfo().id == taskId) {
                task.setExcludeFromRecents(exclude);
            }
        }
    }

    ActivityManager.AppTask findDocTask() {
        ActivityManager am = (ActivityManager)getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.AppTask> tasks = am.getAppTasks();
        if (tasks != null) {
            for (int i=0; i<tasks.size(); i++) {
                ActivityManager.AppTask task = tasks.get(i);
                ActivityManager.RecentTaskInfo recent = task.getTaskInfo();
                if (recent.baseIntent != null
                        && recent.baseIntent.getComponent().getClassName().equals(
                                DocActivity.class.getCanonicalName())) {
                    return task;
                }
            }
        }
        return null;
    }

    void scheduleSpam(boolean fg) {
        mHandler.removeMessages(MSG_SPAM);
        Message msg = mHandler.obtainMessage(MSG_SPAM, fg ? 1 : 0, 0);
        mHandler.sendMessageDelayed(msg, 500);
    }

    void scheduleSpamAlarm(long delay) {
        mHandler.removeMessages(MSG_SPAM_ALARM);
        Message msg = mHandler.obtainMessage(MSG_SPAM_ALARM);
        mHandler.sendMessageDelayed(msg, delay);
    }

    void scheduleSlowReceiver() {
        mHandler.removeMessages(MSG_SLOW_RECEIVER);
        mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_SLOW_RECEIVER), 500);
    }

    void scheduleSlowAlarmReceiver() {
        mHandler.removeMessages(MSG_SLOW_ALARM_RECEIVER);
        mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_SLOW_ALARM_RECEIVER), 500);
    }

    void scheduleReplaceBroadcast() {
        mHandler.removeMessages(MSG_REPLACE_BROADCAST);
        mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_REPLACE_BROADCAST), 500);
    }

    private View scrollWrap(View view) {
        ScrollView scroller = new ScrollView(this);
        scroller.addView(view, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.MATCH_PARENT));
        return scroller;
    }

    private void buildUi() {
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);

        List<ActivityManager.RecentTaskInfo> recents = mAm.getRecentTasks(10,
                ActivityManager.RECENT_WITH_EXCLUDED);
        if (recents != null) {
            for (int i=0; i<recents.size(); i++) {
                ActivityManager.RecentTaskInfo r = recents.get(i);
                TextView tv = new TextView(this);
                tv.setText(r.baseIntent.getComponent().flattenToShortString());
                top.addView(tv, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                LinearLayout item = new LinearLayout(this);
                item.setOrientation(LinearLayout.HORIZONTAL);
                top.addView(item, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
            }
        }

        setContentView(scrollWrap(top));
    }
}
