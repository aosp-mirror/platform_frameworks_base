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

import java.util.Arrays;
import java.util.Objects;

public class IntentTile extends QSTile<QSTile.State> {
    public static final String PREFIX = "intent(";

    private PendingIntent mOnClick;
    private String mOnClickUri;
    private PendingIntent mOnLongClick;
    private String mOnLongClickUri;
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
        sendIntent("click", mOnClick, mOnClickUri);
    }

    @Override
    protected void handleLongClick() {
        sendIntent("long-click", mOnLongClick, mOnLongClickUri);
    }

    private void sendIntent(String type, PendingIntent pi, String uri) {
        try {
            if (pi != null) {
                pi.send();
            } else if (uri != null) {
                final Intent intent = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME);
                mContext.sendBroadcastAsUser(intent, new UserHandle(mCurrentUserId));
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error sending " + type + " intent", t);
        }
    }

    @Override
    protected void handleUpdateState(State state, Object arg) {
        if (!(arg instanceof Intent)) return;
        final Intent intent = (Intent) arg;
        state.visible = intent.getBooleanExtra("visible", true);
        state.contentDescription = intent.getStringExtra("contentDescription");
        state.label = intent.getStringExtra("label");
        state.icon = null;
        final byte[] iconBitmap = intent.getByteArrayExtra("iconBitmap");
        if (iconBitmap != null) {
            try {
                state.icon = new BytesIcon(iconBitmap);
            } catch (Throwable t) {
                Log.w(TAG, "Error loading icon bitmap, length " + iconBitmap.length, t);
            }
        } else {
            final int iconId = intent.getIntExtra("iconId", 0);
            if (iconId != 0) {
                final String iconPackage = intent.getStringExtra("iconPackage");
                if (!TextUtils.isEmpty(iconPackage)) {
                    state.icon = new PackageDrawableIcon(iconPackage, iconId);
                } else {
                    state.icon = ResourceIcon.get(iconId);
                }
            }
        }
        mOnClick = intent.getParcelableExtra("onClick");
        mOnClickUri = intent.getStringExtra("onClickUri");
        mOnLongClick = intent.getParcelableExtra("onLongClick");
        mOnLongClickUri = intent.getStringExtra("onLongClickUri");
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshState(intent);
        }
    };

    private static class BytesIcon extends Icon {
        private final byte[] mBytes;

        public BytesIcon(byte[] bytes) {
            mBytes = bytes;
        }

        @Override
        public Drawable getDrawable(Context context) {
            final Bitmap b = BitmapFactory.decodeByteArray(mBytes, 0, mBytes.length);
            return new BitmapDrawable(context.getResources(), b);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof BytesIcon && Arrays.equals(((BytesIcon) o).mBytes, mBytes);
        }

        @Override
        public String toString() {
            return String.format("BytesIcon[len=%s]", mBytes.length);
        }
    }

    private class PackageDrawableIcon extends Icon {
        private final String mPackage;
        private final int mResId;

        public PackageDrawableIcon(String pkg, int resId) {
            mPackage = pkg;
            mResId = resId;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof PackageDrawableIcon)) return false;
            final PackageDrawableIcon other = (PackageDrawableIcon) o;
            return Objects.equals(other.mPackage, mPackage) && other.mResId == mResId;
        }

        @Override
        public Drawable getDrawable(Context context) {
            try {
                return context.createPackageContext(mPackage, 0).getDrawable(mResId);
            } catch (Throwable t) {
                Log.w(TAG, "Error loading package drawable pkg=" + mPackage + " id=" + mResId, t);
                return null;
            }
        }

        @Override
        public String toString() {
            return String.format("PackageDrawableIcon[pkg=%s,id=0x%08x]", mPackage, mResId);
        }
    }
}
