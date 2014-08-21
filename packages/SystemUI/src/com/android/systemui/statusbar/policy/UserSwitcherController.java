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

import com.android.systemui.BitmapHelper;
import com.android.systemui.GuestResumeSessionReceiver;
import com.android.systemui.R;
import com.android.systemui.qs.QSTile;
import com.android.systemui.qs.tiles.UserDetailView;
import com.android.systemui.statusbar.phone.SystemUIDialog;

import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseArray;
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
    private static final boolean DEBUG = false;
    private static final String SIMPLE_USER_SWITCHER_GLOBAL_SETTING =
            "lockscreenSimpleUserSwitcher";

    private final Context mContext;
    private final UserManager mUserManager;
    private final ArrayList<WeakReference<BaseUserAdapter>> mAdapters = new ArrayList<>();
    private final GuestResumeSessionReceiver mGuestResumeSessionReceiver
            = new GuestResumeSessionReceiver();
    private final KeyguardMonitor mKeyguardMonitor;

    private ArrayList<UserRecord> mUsers = new ArrayList<>();
    private Dialog mExitGuestDialog;
    private int mLastNonGuestUser = UserHandle.USER_OWNER;
    private boolean mSimpleUserSwitcher;
    private boolean mAddUsersWhenLocked;

    public UserSwitcherController(Context context, KeyguardMonitor keyguardMonitor) {
        mContext = context;
        mGuestResumeSessionReceiver.register(context);
        mKeyguardMonitor = keyguardMonitor;
        mUserManager = UserManager.get(context);
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_ADDED);
        filter.addAction(Intent.ACTION_USER_REMOVED);
        filter.addAction(Intent.ACTION_USER_INFO_CHANGED);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        filter.addAction(Intent.ACTION_USER_STOPPING);
        mContext.registerReceiverAsUser(mReceiver, UserHandle.OWNER, filter,
                null /* permission */, null /* scheduler */);


        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(SIMPLE_USER_SWITCHER_GLOBAL_SETTING), true,
                mSettingsObserver);
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ADD_USERS_WHEN_LOCKED), true,
                mSettingsObserver);
        // Fetch initial values.
        mSettingsObserver.onChange(false);

        keyguardMonitor.addCallback(mCallback);

        refreshUsers(UserHandle.USER_NULL);
    }

    /**
     * Refreshes users from UserManager.
     *
     * The pictures are only loaded if they have not been loaded yet.
     *
     * @param forcePictureLoadForId forces the picture of the given user to be reloaded.
     */
    @SuppressWarnings("unchecked")
    private void refreshUsers(int forcePictureLoadForId) {

        SparseArray<Bitmap> bitmaps = new SparseArray<>(mUsers.size());
        final int N = mUsers.size();
        for (int i = 0; i < N; i++) {
            UserRecord r = mUsers.get(i);
            if (r == null || r.info == null
                    || r.info.id == forcePictureLoadForId || r.picture == null) {
                continue;
            }
            bitmaps.put(r.info.id, r.picture);
        }

        final boolean addUsersWhenLocked = mAddUsersWhenLocked;
        new AsyncTask<SparseArray<Bitmap>, Void, ArrayList<UserRecord>>() {
            @SuppressWarnings("unchecked")
            @Override
            protected ArrayList<UserRecord> doInBackground(SparseArray<Bitmap>... params) {
                final SparseArray<Bitmap> bitmaps = params[0];
                List<UserInfo> infos = mUserManager.getUsers(true);
                if (infos == null) {
                    return null;
                }
                ArrayList<UserRecord> records = new ArrayList<>(infos.size());
                int currentId = ActivityManager.getCurrentUser();
                UserRecord guestRecord = null;
                int avatarSize = mContext.getResources()
                        .getDimensionPixelSize(R.dimen.max_avatar_size);

                for (UserInfo info : infos) {
                    boolean isCurrent = currentId == info.id;
                    if (info.isGuest()) {
                        guestRecord = new UserRecord(info, null /* picture */,
                                true /* isGuest */, isCurrent, false /* isAddUser */,
                                false /* isRestricted */);
                    } else if (info.supportsSwitchTo()) {
                        Bitmap picture = bitmaps.get(info.id);
                        if (picture == null) {
                            picture = mUserManager.getUserIcon(info.id);
                        }
                        if (picture != null) {
                            picture = BitmapHelper.createCircularClip(
                                    picture, avatarSize, avatarSize);
                        }
                        int index = isCurrent ? 0 : records.size();
                        records.add(index, new UserRecord(info, picture, false /* isGuest */,
                                isCurrent, false /* isAddUser */, false /* isRestricted */));
                    }
                }

                boolean ownerCanCreateUsers = !mUserManager.hasUserRestriction(
                        UserManager.DISALLOW_ADD_USER, UserHandle.OWNER);
                boolean currentUserCanCreateUsers =
                        (currentId == UserHandle.USER_OWNER) && ownerCanCreateUsers;
                boolean anyoneCanCreateUsers = ownerCanCreateUsers && addUsersWhenLocked;
                boolean canCreateGuest = (currentUserCanCreateUsers || anyoneCanCreateUsers)
                        && guestRecord == null;
                boolean canCreateUser = (currentUserCanCreateUsers || anyoneCanCreateUsers)
                        && mUserManager.canAddMoreUsers();
                boolean createIsRestricted = !addUsersWhenLocked;

                if (!mSimpleUserSwitcher) {
                    if (guestRecord == null) {
                        if (canCreateGuest) {
                            records.add(new UserRecord(null /* info */, null /* picture */,
                                    true /* isGuest */, false /* isCurrent */,
                                    false /* isAddUser */, createIsRestricted));
                        }
                    } else {
                        int index = guestRecord.isCurrent ? 0 : records.size();
                        records.add(index, guestRecord);
                    }
                }

                if (canCreateUser) {
                    records.add(new UserRecord(null /* info */, null /* picture */,
                            false /* isGuest */, false /* isCurrent */, true /* isAddUser */,
                            createIsRestricted));
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
        }.execute((SparseArray) bitmaps);
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

    public boolean isSimpleUserSwitcher() {
        return mSimpleUserSwitcher;
    }

    public void switchTo(UserRecord record) {
        int id;
        if (record.isGuest && record.info == null) {
            // No guest user. Create one.
            id = mUserManager.createGuest(mContext, mContext.getString(R.string.guest_nickname)).id;
        } else if (record.isAddUser) {
            id = mUserManager.createUser(
                    mContext.getString(R.string.user_new_user_name), 0 /* flags */).id;
        } else {
            id = record.info.id;
        }

        if (ActivityManager.getCurrentUser() == id) {
            if (record.isGuest) {
                showExitGuestDialog(id);
            }
            return;
        }

        switchToUserId(id);
    }

    private void switchToUserId(int id) {
        try {
            WindowManagerGlobal.getWindowManagerService().lockNow(null);
            ActivityManagerNative.getDefault().switchUser(id);
        } catch (RemoteException e) {
            Log.e(TAG, "Couldn't switch user.", e);
        }
    }

    private void showExitGuestDialog(int id) {
        if (mExitGuestDialog != null && mExitGuestDialog.isShowing()) {
            mExitGuestDialog.cancel();
        }
        mExitGuestDialog = new ExitGuestDialog(mContext, id);
        mExitGuestDialog.show();
    }

    private void exitGuest(int id) {
        int newId = UserHandle.USER_OWNER;
        if (mLastNonGuestUser != UserHandle.USER_OWNER) {
            UserInfo info = mUserManager.getUserInfo(mLastNonGuestUser);
            if (info != null && info.isEnabled() && info.supportsSwitchTo()) {
                newId = info.id;
            }
        }
        switchToUserId(newId);
        mUserManager.removeUser(id);
    }

    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) {
                Log.v(TAG, "Broadcast: a=" + intent.getAction()
                       + " user=" + intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1));
            }
            if (Intent.ACTION_USER_SWITCHED.equals(intent.getAction())) {
                if (mExitGuestDialog != null && mExitGuestDialog.isShowing()) {
                    mExitGuestDialog.cancel();
                    mExitGuestDialog = null;
                }

                final int currentId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                final int N = mUsers.size();
                for (int i = 0; i < N; i++) {
                    UserRecord record = mUsers.get(i);
                    if (record.info == null) continue;
                    boolean shouldBeCurrent = record.info.id == currentId;
                    if (record.isCurrent != shouldBeCurrent) {
                        mUsers.set(i, record.copyWithIsCurrent(shouldBeCurrent));
                    }
                    if (shouldBeCurrent && !record.isGuest) {
                        mLastNonGuestUser = record.info.id;
                    }
                    if (currentId != UserHandle.USER_OWNER && record.isRestricted) {
                        // Immediately remove restricted records in case the AsyncTask is too slow.
                        mUsers.remove(i);
                        i--;
                    }
                }
                notifyAdapters();
            }
            int forcePictureLoadForId = UserHandle.USER_NULL;
            if (Intent.ACTION_USER_INFO_CHANGED.equals(intent.getAction())) {
                forcePictureLoadForId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE,
                        UserHandle.USER_NULL);
            }
            refreshUsers(forcePictureLoadForId);
        }
    };

    private final ContentObserver mSettingsObserver = new ContentObserver(new Handler()) {
        public void onChange(boolean selfChange) {
            mSimpleUserSwitcher = Settings.Global.getInt(mContext.getContentResolver(),
                    SIMPLE_USER_SWITCHER_GLOBAL_SETTING, 0) != 0;
            mAddUsersWhenLocked = Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.ADD_USERS_WHEN_LOCKED, 0) != 0;
            refreshUsers(UserHandle.USER_NULL);
        };
    };

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("UserSwitcherController state:");
        pw.println("  mLastNonGuestUser=" + mLastNonGuestUser);
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
            boolean secureKeyguardShowing = mController.mKeyguardMonitor.isShowing()
                    && mController.mKeyguardMonitor.isSecure();
            if (!secureKeyguardShowing) {
                return mController.mUsers.size();
            }
            // The lock screen is secure and showing. Filter out restricted records.
            final int N = mController.mUsers.size();
            int count = 0;
            for (int i = 0; i < N; i++) {
                if (mController.mUsers.get(i).isRestricted) {
                    break;
                } else {
                    count++;
                }
            }
            return count;
        }

        @Override
        public UserRecord getItem(int position) {
            return mController.mUsers.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        public void switchTo(UserRecord record) {
            mController.switchTo(record);
        }

        public String getName(Context context, UserRecord item) {
            if (item.isGuest) {
                if (item.isCurrent) {
                    return context.getString(R.string.guest_exit_guest);
                } else {
                    return context.getString(
                            item.info == null ? R.string.guest_new_guest : R.string.guest_nickname);
                }
            } else if (item.isAddUser) {
                return context.getString(R.string.user_add_user);
            } else {
                return item.info.name;
            }
        }

        public int getSwitchableUsers() {
            int result = 0;
            ArrayList<UserRecord> users = mController.mUsers;
            int N = users.size();
            for (int i = 0; i < N; i++) {
                if (users.get(i).info != null) {
                    result++;
                }
            }
            return result;
        }

        public Drawable getDrawable(Context context, UserRecord item) {
            if (item.isAddUser) {
                return context.getDrawable(R.drawable.ic_add_circle_qs);
            }
            return context.getDrawable(R.drawable.ic_account_circle_qs);
        }
    }

    public static final class UserRecord {
        public final UserInfo info;
        public final Bitmap picture;
        public final boolean isGuest;
        public final boolean isCurrent;
        public final boolean isAddUser;
        /** If true, the record is only visible to the owner and only when unlocked. */
        public final boolean isRestricted;

        public UserRecord(UserInfo info, Bitmap picture, boolean isGuest, boolean isCurrent,
                boolean isAddUser, boolean isRestricted) {
            this.info = info;
            this.picture = picture;
            this.isGuest = isGuest;
            this.isCurrent = isCurrent;
            this.isAddUser = isAddUser;
            this.isRestricted = isRestricted;
        }

        public UserRecord copyWithIsCurrent(boolean _isCurrent) {
            return new UserRecord(info, picture, isGuest, _isCurrent, isAddUser, isRestricted);
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("UserRecord(");
            if (info != null) {
                sb.append("name=\"" + info.name + "\" id=" + info.id);
            } else {
                if (isGuest) {
                    sb.append("<add guest placeholder>");
                } else if (isAddUser) {
                    sb.append("<add user placeholder>");
                }
            }
            if (isGuest) sb.append(" <isGuest>");
            if (isAddUser) sb.append(" <isAddUser>");
            if (isCurrent) sb.append(" <isCurrent>");
            if (picture != null) sb.append(" <hasPicture>");
            if (isRestricted) sb.append(" <isRestricted>");
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
            UserDetailView v;
            if (!(convertView instanceof UserDetailView)) {
                v = UserDetailView.inflate(context, parent, false);
                v.createAndSetAdapter(UserSwitcherController.this);
            } else {
                v = (UserDetailView) convertView;
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

    private final KeyguardMonitor.Callback mCallback = new KeyguardMonitor.Callback() {
        @Override
        public void onKeyguardChanged() {
            notifyAdapters();
        }
    };

    private final class ExitGuestDialog extends SystemUIDialog implements
            DialogInterface.OnClickListener {

        private final int mGuestId;

        public ExitGuestDialog(Context context, int guestId) {
            super(context);
            setTitle(R.string.guest_exit_guest_dialog_title);
            setMessage(context.getString(R.string.guest_exit_guest_dialog_message));
            setButton(DialogInterface.BUTTON_NEGATIVE,
                    context.getString(android.R.string.cancel), this);
            setButton(DialogInterface.BUTTON_POSITIVE,
                    context.getString(R.string.guest_exit_guest_dialog_remove), this);
            setCanceledOnTouchOutside(false);
            mGuestId = guestId;
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            if (which == BUTTON_NEGATIVE) {
                cancel();
            } else {
                dismiss();
                exitGuest(mGuestId);
            }
        }
    }
}
