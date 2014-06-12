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

package com.android.systemui.settings;

import com.android.systemui.R;

import android.app.ActivityManagerNative;
import android.content.Context;
import android.content.pm.UserInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserManager;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManagerGlobal;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * A quick and dirty view to show a user switcher.
 */
public class UserSwitcherHostView extends FrameLayout
        implements ListView.OnItemClickListener, View.OnClickListener {

    private static final String TAG = "UserSwitcherDialog";

    private ArrayList<UserInfo> mUserInfo = new ArrayList<UserInfo>();
    private UserInfo mGuestUser;
    private Adapter mAdapter = new Adapter();
    private UserManager mUserManager;
    private Runnable mFinishRunnable;
    private ListView mListView;
    private boolean mGuestUserEnabled;

    public UserSwitcherHostView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        if (isInEditMode()) {
            return;
        }
        mUserManager = (UserManager) context.getSystemService(Context.USER_SERVICE);

        mGuestUserEnabled = Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.GUEST_USER_ENABLED, 0) == 1;
    }

    public UserSwitcherHostView(Context context, AttributeSet attrs) {
        this(context, attrs, com.android.internal.R.attr.listViewStyle);
    }

    public UserSwitcherHostView(Context context) {
        this(context, null);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mListView = (ListView) findViewById(android.R.id.list);
        mListView.setAdapter(mAdapter);
        mListView.setOnItemClickListener(this);
    }

    @Override
    public void onItemClick(AdapterView<?> l, View v, int position, long id) {
        // Last item is the guest
        if (position == mUserInfo.size()) {
            postDelayed(new Runnable() {
                public void run() {
                    switchToGuestUser();
                }
            }, 100);
        } else {
            final int userId = mAdapter.getItem(position).id;
            postDelayed(new Runnable() {
                public void run() {
                    switchUser(userId);
                }
            }, 100);
        }
    }

    @Override
    public void onClick(View v) {
        // Delete was clicked
        postDelayed(new Runnable() {
            public void run() {
                if (mGuestUser != null) {
                    switchUser(0);
                    mUserManager.removeUser(mGuestUser.id);
                    mGuestUser = null;
                    refreshUsers();
                }
            }
        }, 100);
    }

    private void switchUser(int userId) {
        try {
            WindowManagerGlobal.getWindowManagerService().lockNow(null);
            ActivityManagerNative.getDefault().switchUser(userId);
            finish();
        } catch (RemoteException e) {
            Log.e(TAG, "Couldn't switch user.", e);
        }
    }

    private void switchToGuestUser() {
        if (mGuestUser == null) {
            // No guest user. Create one.
            mGuestUser = mUserManager.createGuest(mContext, 
                    mContext.getResources().getString(R.string.guest_nickname));
        }
        switchUser(mGuestUser.id);
    }

    private void finish() {
        if (mFinishRunnable != null) {
            mFinishRunnable.run();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP) {
            finish();
        }
        return true;
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        // A gross hack to get rid of the switcher when the shade is collapsed.
        if (visibility != VISIBLE) {
            finish();
        }
    }

    public void setFinishRunnable(Runnable finishRunnable) {
        mFinishRunnable = finishRunnable;
    }

    public void refreshUsers() {
        mUserInfo.clear();
        mGuestUser = null;
        List<UserInfo> users = mUserManager.getUsers(true);
        for (UserInfo user : users) {
            if (user.isGuest()) {
                mGuestUser = user;
            } else if (!user.isManagedProfile()) {
                mUserInfo.add(user);
            }
        }
        mAdapter.notifyDataSetChanged();
    }

    private class Adapter extends BaseAdapter {

        @Override
        public int getCount() {
            return mUserInfo.size() + (mGuestUserEnabled ? 1 : 0);
        }

        @Override
        public UserInfo getItem(int position) {
            if (position < mUserInfo.size()) {
                return mUserInfo.get(position);
            } else {
                return mGuestUser;
            }
        }

        @Override
        public long getItemId(int position) {
            if (position < mUserInfo.size()) {
                return getItem(position).serialNumber;
            } else {
                return mGuestUser != null ? mGuestUser.serialNumber : -1;
            }
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null || (!(convertView.getTag() instanceof ViewHolder))) {
                convertView = createView(parent);
            }
            ViewHolder h = (ViewHolder) convertView.getTag();
            bindView(h, getItem(position));
            return convertView;
        }

        private View createView(ViewGroup parent) {
            View v = LayoutInflater.from(getContext()).inflate(
                    R.layout.user_switcher_item, parent, false);
            ViewHolder h = new ViewHolder();
            h.name = (TextView) v.findViewById(R.id.user_name);
            h.picture = (ImageView) v.findViewById(R.id.user_picture);
            h.delete = (ImageView) v.findViewById(R.id.user_delete);
            v.setTag(h);
            return v;
        }

        private void bindView(ViewHolder h, UserInfo item) {
            if (item != null) {
                h.name.setText(item.name);
                h.picture.setImageBitmap(circularClip(mUserManager.getUserIcon(item.id)));
                h.delete.setVisibility(item.isGuest() ? View.VISIBLE : View.GONE);
                h.delete.setOnClickListener(UserSwitcherHostView.this);
                if (item.isGuest()) {
                    h.picture.setImageResource(R.drawable.ic_account_circle);
                }
            } else {
                h.name.setText(R.string.guest_new_guest);
                h.picture.setImageResource(R.drawable.ic_account_circle);
                h.delete.setVisibility(View.GONE);
            }
        }

        private Bitmap circularClip(Bitmap input) {
            if (input == null) {
                return null;
            }
            Bitmap output = Bitmap.createBitmap(input.getWidth(),
                    input.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(output);
            final Paint paint = new Paint();
            paint.setShader(new BitmapShader(input, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            paint.setAntiAlias(true);
            canvas.drawCircle(input.getWidth() / 2, input.getHeight() / 2, input.getWidth() / 2,
                    paint);
            return output;
        }

        class ViewHolder {
            TextView name;
            ImageView picture;
            ImageView delete;
        }
    }
}
