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
import android.os.RemoteException;
import android.os.UserManager;
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

/**
 * A quick and dirty view to show a user switcher.
 */
public class UserSwitcherHostView extends FrameLayout implements ListView.OnItemClickListener {

    private static final String TAG = "UserSwitcherDialog";

    private ArrayList<UserInfo> mUserInfo = new ArrayList<UserInfo>();
    private Adapter mAdapter = new Adapter();
    private UserManager mUserManager;
    private Runnable mFinishRunnable;
    private ListView mListView;

    public UserSwitcherHostView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        if (isInEditMode()) {
            return;
        }
        mUserManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
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
        int userId = mAdapter.getItem(position).id;
        try {
            WindowManagerGlobal.getWindowManagerService().lockNow(null);
            ActivityManagerNative.getDefault().switchUser(userId);
            finish();
        } catch (RemoteException e) {
            Log.e(TAG, "Couldn't switch user.", e);
        }
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
        mUserInfo.addAll(mUserManager.getUsers(true));
        mAdapter.notifyDataSetChanged();
    }

    private class Adapter extends BaseAdapter {

        @Override
        public int getCount() {
            return mUserInfo.size();
        }

        @Override
        public UserInfo getItem(int position) {
            return mUserInfo.get(position);
        }

        @Override
        public long getItemId(int position) {
            return getItem(position).serialNumber;
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
            v.setTag(h);
            return v;
        }

        private void bindView(ViewHolder h, UserInfo item) {
            h.name.setText(item.name);
            h.picture.setImageBitmap(mUserManager.getUserIcon(item.id));
        }

        class ViewHolder {
            TextView name;
            ImageView picture;
        }
    }
}
