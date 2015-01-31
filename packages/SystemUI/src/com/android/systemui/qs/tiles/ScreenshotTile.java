/*
 * Copyright (C) 2014 The Android Open Source Project
 * Copyright (C) 2012-2015 The CyanogenMod Project
 * Copyright (C) 2014-2015 The Euphoria-OS Project
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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.view.View;

import com.android.systemui.R;
import com.android.systemui.screenshot.TakeScreenshotService;
import com.android.systemui.qs.QSTile;

import com.android.internal.logging.MetricsProto.MetricsEvent;

/** Quick settings tile: Screenshot **/
public class ScreenshotTile extends QSTile<QSTile.BooleanState> {

    private boolean mListening;
    private final Object mScreenshotLock = new Object();
    private ServiceConnection mScreenshotConnection = null;

    public ScreenshotTile(Host host) {
        super(host);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    public void setListening(boolean listening) {
        if (mListening == listening) return;
        mListening = listening;
    }

    @Override
    public void handleClick() {
        mHost.collapsePanels();
        /* wait for the panel to close */
        try {
             Thread.sleep(2000);
        } catch (InterruptedException ie) {
             // Do nothing
        }
        takeScreenshot();
    }

    @Override
    public void handleLongClick() {
        mHost.collapsePanels();
        /* wait for the panel to close */
        try {
             Thread.sleep(2000);
        } catch (InterruptedException ie) {
             // Do nothing
        }
        takeScreenshot();
    }

    @Override
    public Intent getLongClickIntent() {
        return null;
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.label = mContext.getString(R.string.quick_settings_screenshot_label);
        state.icon = ResourceIcon.get(R.drawable.ic_qs_screenshot);
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_screenshot_label);
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.SCREEN;
    }

    final Runnable mScreenshotTimeout = new Runnable() {
        @Override
        public void run() {
            synchronized (mScreenshotLock) {
                if (mScreenshotConnection != null) {
                    mContext.unbindService(mScreenshotConnection);
                    mScreenshotConnection = null;
                }
            }
        }
    };

    private void takeScreenshot() {
        synchronized (mScreenshotLock) {
            if (mScreenshotConnection != null) {
                return;
            }

            Intent intent = new Intent(mContext, TakeScreenshotService.class);
            ServiceConnection conn = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    synchronized (mScreenshotLock) {
                        if (mScreenshotConnection != this) {
                            return;
                        }

                        Messenger messenger = new Messenger(service);
                        Message msg = Message.obtain(null, 1);
                        final ServiceConnection myConn = this;
                        Handler h = new Handler(mHandler.getLooper()) {
                            @Override
                            public void handleMessage(Message msg) {
                                synchronized (mScreenshotLock) {
                                    if (mScreenshotConnection == myConn) {
                                        mContext.unbindService(mScreenshotConnection);
                                        mScreenshotConnection = null;
                                        mHandler.removeCallbacks(mScreenshotTimeout);
                                    }
                                }
                            }
                        };
                        msg.replyTo = new Messenger(h);
                        msg.arg1 = msg.arg2 = 0;

                        // Take the screenshot
                        try {
                            messenger.send(msg);
                        } catch (RemoteException e) {
                            // Do nothing here
                        }
                    }
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    // Do nothing here
                }
            };

            if (mContext.bindService(intent, conn, mContext.BIND_AUTO_CREATE)) {
                mScreenshotConnection = conn;
                mHandler.postDelayed(mScreenshotTimeout, 10000);
            }
        }
    }
}
