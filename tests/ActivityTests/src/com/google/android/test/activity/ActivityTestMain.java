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

import java.util.ArrayList;
import java.util.List;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.graphics.Bitmap;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ScrollView;
import android.widget.Toast;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.content.Context;
import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.util.Log;

public class ActivityTestMain extends Activity {
    static final String TAG = "ActivityTest";

    static final String KEY_CONFIGURATION = "configuration";

    ActivityManager mAm;
    Configuration mOverrideConfig;
    int mSecondUser;

    ArrayList<ServiceConnection> mConnections = new ArrayList<ServiceConnection>();

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

    private void addThumbnail(LinearLayout container, Bitmap bm,
            final ActivityManager.RecentTaskInfo task,
            final ActivityManager.TaskThumbnails thumbs, final int subIndex) {
        ImageView iv = new ImageView(this);
        if (bm != null) {
            iv.setImageBitmap(bm);
        }
        iv.setBackgroundResource(android.R.drawable.gallery_thumb);
        int w = getResources().getDimensionPixelSize(android.R.dimen.thumbnail_width);
        int h = getResources().getDimensionPixelSize(android.R.dimen.thumbnail_height);
        container.addView(iv, new LinearLayout.LayoutParams(w, h));

        iv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (task.id >= 0 && thumbs != null) {
                    if (subIndex < (thumbs.numSubThumbbails-1)) {
                        mAm.removeSubTask(task.id, subIndex+1);
                    }
                    mAm.moveTaskToFront(task.id, ActivityManager.MOVE_TASK_WITH_HOME);
                } else {
                    try {
                        startActivity(task.baseIntent);
                    } catch (ActivityNotFoundException e) {
                        Log.w("foo", "Unable to start task: " + e);
                    }
                }
                buildUi();
            }
        });
        iv.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (task.id >= 0 && thumbs != null) {
                    if (subIndex < 0) {
                        mAm.removeTask(task.id, ActivityManager.REMOVE_TASK_KILL_PROCESS);
                    } else {
                        mAm.removeSubTask(task.id, subIndex);
                    }
                    buildUi();
                    return true;
                }
                return false;
            }
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mAm = (ActivityManager)getSystemService(ACTIVITY_SERVICE);
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
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add("Animate!").setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
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
                if (bindServiceAsUser(intent, conn, Context.BIND_AUTO_CREATE, UserHandle.OWNER)) {
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
        return true;
    }

    @Override
    protected void onStart() {
        super.onStart();
        buildUi();
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
        for (ServiceConnection conn : mConnections) {
            unbindService(conn);
        }
        mConnections.clear();
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
                ActivityManager.TaskThumbnails tt = mAm.getTaskThumbnails(r.persistentId);
                TextView tv = new TextView(this);
                tv.setText(r.baseIntent.getComponent().flattenToShortString());
                top.addView(tv, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                LinearLayout item = new LinearLayout(this);
                item.setOrientation(LinearLayout.HORIZONTAL);
                addThumbnail(item, tt != null ? tt.mainThumbnail : null, r, tt, -1);
                for (int j=0; j<tt.numSubThumbbails; j++) {
                    addThumbnail(item, tt.getSubThumbnail(j), r, tt, j);
                }
                top.addView(item, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
            }
        }

        setContentView(scrollWrap(top));
    }
}
