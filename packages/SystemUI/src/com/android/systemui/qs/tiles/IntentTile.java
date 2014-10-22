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
 * limitations under the License.
 */

package com.android.systemui.qs.tiles;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;

import com.android.systemui.qs.QSTile;

public class IntentTile extends QSTile<QSTile.State> {
    public static final String PREFIX = "intent(";

    private PendingIntent mOnClick;
    private String mOnClickUri;
    private int mCurrentUserId;

    private IntentTile(Host host, String action) {
        super(host);
        mContext.registerReceiver(mReceiver, new IntentFilter(action));
    }

    @Override
    protected void handleDestroy() {
        super.handleDestroy();
        mContext.unregisterReceiver(mReceiver);
    }

    public static QSTile<?> create(Host host, String spec) {
        if (spec == null || !spec.startsWith(PREFIX) || !spec.endsWith(")")) {
            throw new IllegalArgumentException("Bad intent tile spec: " + spec);
        }
        final String action = spec.substring(PREFIX.length(), spec.length() - 1);
        if (action.isEmpty()) {
            throw new IllegalArgumentException("Empty intent tile spec action");
        }
        return new IntentTile(host, action);
    }

    @Override
    public void setListening(boolean listening) {
    }

    @Override
    protected State newTileState() {
        return new State();
    }

    @Override
    protected void handleUserSwitch(int newUserId) {
        super.handleUserSwitch(newUserId);
        mCurrentUserId = newUserId;
    }

    @Override
    protected void handleClick() {
        try {
            if (mOnClick != null) {
                mOnClick.send();
            } else if (mOnClickUri != null) {
                final Intent intent = Intent.parseUri(mOnClickUri, Intent.URI_INTENT_SCHEME);
                mContext.sendBroadcastAsUser(intent, new UserHandle(mCurrentUserId));
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error sending click intent", t);
        }
    }

    @Override
    protected void handleUpdateState(State state, Object arg) {
        if (!(arg instanceof Intent)) return;
        final Intent intent = (Intent) arg;
        state.visible = intent.getBooleanExtra("visible", true);
        state.contentDescription = intent.getStringExtra("contentDescription");
        state.label = intent.getStringExtra("label");
        state.iconId = 0;
        state.icon = null;
        final byte[] iconBitmap = intent.getByteArrayExtra("iconBitmap");
        if (iconBitmap != null) {
            try {
                final Bitmap b = BitmapFactory.decodeByteArray(iconBitmap, 0, iconBitmap.length);
                state.icon = new BitmapDrawable(mContext.getResources(), b);
            } catch (Throwable t) {
                Log.w(TAG, "Error loading icon bitmap, length " + iconBitmap.length, t);
            }
        } else {
            final int iconId = intent.getIntExtra("iconId", 0);
            if (iconId != 0) {
                final String iconPackage = intent.getStringExtra("iconPackage");
                if (!TextUtils.isEmpty(iconPackage)) {
                    state.icon = getPackageDrawable(iconPackage, iconId);
                } else {
                    state.iconId = iconId;
                }
            }
        }
        mOnClick = intent.getParcelableExtra("onClick");
        mOnClickUri = intent.getStringExtra("onClickUri");
    }

    private Drawable getPackageDrawable(String pkg, int id) {
        try {
            return mContext.createPackageContext(pkg, 0).getDrawable(id);
        } catch (Throwable t) {
            Log.w(TAG, "Error loading package drawable pkg=" + pkg + " id=" + id, t);
            return null;
        }
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshState(intent);
        }
    };
}
