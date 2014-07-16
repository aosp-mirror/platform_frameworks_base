/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.policy;

import com.android.systemui.R;
import com.android.systemui.qs.QSTile;
import com.android.systemui.qs.tiles.UserDetailView;

import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.graphics.Bitmap;
import android.os.AsyncTask;
import android.os.RemoteException;
import android.os.UserManager;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManagerGlobal;
import android.widget.BaseAdapter;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps a list of all users on the device for user switching.
 */
public class UserSwitcherController {

    private static final String TAG = "UserSwitcherController";

    private final Context mContext;
    private final UserManager mUserManager;
    private final ArrayList<WeakReference<BaseUserAdapter>> mAdapters = new ArrayList<>();

    private ArrayList<UserRecord> mUsers = new ArrayList<>();

    public UserSwitcherController(Context context) {
        mContext = context;
        mUserManager = UserManager.get(context);
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_ADDED);
        filter.addAction(Intent.ACTION_USER_REMOVED);
        filter.addAction(Intent.ACTION_USER_INFO_CHANGED);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        mContext.registerReceiver(mReceiver, filter);
        refreshUsers();
    }

    private void refreshUsers() {
        new AsyncTask<Void, Void, ArrayList<UserRecord>>() {

            @Override
            protected ArrayList<UserRecord> doInBackground(Void... params) {
                List<UserInfo> infos = mUserManager.getUsers(true);
                if (infos == null) {
                    return null;
                }
                ArrayList<UserRecord> records = new ArrayList<>(infos.size());
                int currentId = ActivityManager.getCurrentUser();
                UserRecord guestRecord = null;

                for (UserInfo info : infos) {
                    boolean isCurrent = currentId == info.id;
                    if (info.isGuest()) {
                        guestRecord = new UserRecord(info, null /* picture */,
                                true /* isGuest */, isCurrent);
                    } else if (!info.isManagedProfile()) {
                        records.add(new UserRecord(info, mUserManager.getUserIcon(info.id),
                                false /* isGuest */, isCurrent));
                    }
                }

                if (guestRecord == null) {
                    records.add(new UserRecord(null /* info */, null /* picture */,
                            true /* isGuest */, false /* isCurrent */));
                } else {
                    records.add(guestRecord);
                }

                return records;
            }

            @Override
            protected void onPostExecute(ArrayList<UserRecord> userRecords) {
                if (userRecords != null) {
                    mUsers = userRecords;
                    notifyAdapters();
                }
            }
        }.execute((Void[])null);
    }

    private void notifyAdapters() {
        for (int i = mAdapters.size() - 1; i >= 0; i--) {
            BaseUserAdapter adapter = mAdapters.get(i).get();
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            } else {
                mAdapters.remove(i);
            }
        }
    }

    public void switchTo(UserRecord record) {
        int id;
        if (record.isGuest && record.info == null) {
            // No guest user. Create one.
            id = mUserManager.createGuest(mContext,
                    mContext.getResources().getString(R.string.guest_nickname)).id;
        } else {
            id = record.info.id;
        }

        if (ActivityManager.getCurrentUser() == id) {
            return;
        }

        try {
            WindowManagerGlobal.getWindowManagerService().lockNow(null);
            ActivityManagerNative.getDefault().switchUser(id);
        } catch (RemoteException e) {
            Log.e(TAG, "Couldn't switch user.", e);
        }
    }

    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_USER_SWITCHED.equals(intent.getAction())) {
                final int currentId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                final int N = mUsers.size();
                for (int i = 0; i < N; i++) {
                    UserRecord record = mUsers.get(i);
                    boolean shouldBeCurrent = record.info.id == currentId;
                    if (record.isCurrent != shouldBeCurrent) {
                        mUsers.set(i, record.copyWithIsCurrent(shouldBeCurrent));
                    }
                }
                notifyAdapters();
            } else {
                refreshUsers();
            }
        }
    };

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("UserSwitcherController state:");
        pw.print("  mUsers.size="); pw.println(mUsers.size());
        for (int i = 0; i < mUsers.size(); i++) {
            final UserRecord u = mUsers.get(i);
            pw.print("    "); pw.println(u.toString());
        }
    }

    public static abstract class BaseUserAdapter extends BaseAdapter {

        final UserSwitcherController mController;

        protected BaseUserAdapter(UserSwitcherController controller) {
            mController = controller;
            controller.mAdapters.add(new WeakReference<>(this));
        }

        @Override
        public int getCount() {
            return mController.mUsers.size();
        }

        @Override
        public UserRecord getItem(int position) {
            return mController.mUsers.get(position);
        }

        @Override
        public long getItemId(int position) {
            return mController.mUsers.get(position).info.id;
        }

        public void switchTo(UserRecord record) {
            mController.switchTo(record);
        }
    }

    public static final class UserRecord {
        public final UserInfo info;
        public final Bitmap picture;
        public final boolean isGuest;
        public final boolean isCurrent;

        public UserRecord(UserInfo info, Bitmap picture, boolean isGuest, boolean isCurrent) {
            this.info = info;
            this.picture = picture;
            this.isGuest = isGuest;
            this.isCurrent = isCurrent;
        }

        public UserRecord copyWithIsCurrent(boolean _isCurrent) {
            return new UserRecord(info, picture, isGuest, _isCurrent);
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("UserRecord(");
            if (info != null) {
                sb.append("name=\"" + info.name + "\" id=" + info.id);
            } else {
                sb.append("<add guest placeholder>");
            }
            if (isGuest) {
                sb.append(" <isGuest>");
            }
            if (isCurrent) {
                sb.append(" <isCurrent>");
            }
            if (picture != null) {
                sb.append(" <hasPicture>");
            }
            sb.append(')');
            return sb.toString();
        }
    }

    public final QSTile.DetailAdapter userDetailAdapter = new QSTile.DetailAdapter() {
        private final Intent USER_SETTINGS_INTENT = new Intent("android.settings.USER_SETTINGS");

        @Override
        public int getTitle() {
            return R.string.quick_settings_user_title;
        }

        @Override
        public View createDetailView(Context context, View convertView, ViewGroup parent) {
            if (!(convertView instanceof UserDetailView)) {
                convertView = UserDetailView.inflate(context, parent, false);
            }
            UserDetailView v = (UserDetailView) convertView;
            if (v.getAdapter() == null) {
                v.createAndSetAdapter(UserSwitcherController.this);
            }
            return v;
        }

        @Override
        public Intent getSettingsIntent() {
            return USER_SETTINGS_INTENT;
        }

        @Override
        public Boolean getToggleState() {
            return null;
        }

        @Override
        public void setToggleState(boolean state) {
        }
    };
}
