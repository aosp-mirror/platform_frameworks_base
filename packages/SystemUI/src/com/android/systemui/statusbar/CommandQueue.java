/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.systemui.statusbar;

import android.os.Handler;
import android.os.IBinder;
import android.os.Message;

import com.android.internal.statusbar.IStatusBar;
import com.android.internal.statusbar.StatusBarIcon;
import com.android.internal.statusbar.StatusBarIconList;
import com.android.internal.statusbar.StatusBarNotification;

/**
 * This class takes the functions from IStatusBar that come in on
 * binder pool threads and posts messages to get them onto the main
 * thread, and calls onto Callbacks.  It also takes care of
 * coalescing these calls so they don't stack up.  For the calls
 * are coalesced, note that they are all idempotent.
 */
public class CommandQueue extends IStatusBar.Stub {
    private static final String TAG = "StatusBar.CommandQueue";

    private static final int MSG_MASK = 0xffff0000;
    private static final int INDEX_MASK = 0x0000ffff;

    private static final int MSG_ICON = 0x00010000;
    private static final int OP_SET_ICON = 1;
    private static final int OP_REMOVE_ICON = 2;

    private static final int MSG_ADD_NOTIFICATION = 0x00020000;
    private static final int MSG_UPDATE_NOTIFICATION = 0x00030000;
    private static final int MSG_REMOVE_NOTIFICATION = 0x00040000;

    private static final int MSG_DISABLE = 0x00050000;

    private static final int MSG_SET_VISIBILITY = 0x00060000;
    private static final int OP_EXPAND = 1;
    private static final int OP_COLLAPSE = 2;

    private StatusBarIconList mList;
    private Callbacks mCallbacks;
    private Handler mHandler = new H();

    private class NotificationQueueEntry {
        IBinder key;
        StatusBarNotification notification;
    }

    /**
     * These methods are called back on the main thread.
     */
    public interface Callbacks {
        public void addIcon(String slot, int index, int viewIndex, StatusBarIcon icon);
        public void updateIcon(String slot, int index, int viewIndex,
                StatusBarIcon old, StatusBarIcon icon);
        public void removeIcon(String slot, int index, int viewIndex);
        public void addNotification(IBinder key, StatusBarNotification notification);
        public void updateNotification(IBinder key, StatusBarNotification notification);
        public void removeNotification(IBinder key);
        public void disable(int state);
        public void animateExpand();
        public void animateCollapse();
    }

    public CommandQueue(Callbacks callbacks, StatusBarIconList list) {
        mCallbacks = callbacks;
        mList = list;
    }

    public void setIcon(int index, StatusBarIcon icon) {
        synchronized (mList) {
            int what = MSG_ICON | index;
            mHandler.removeMessages(what);
            mHandler.obtainMessage(what, OP_SET_ICON, 0, icon.clone()).sendToTarget();
        }
    }

    public void removeIcon(int index) {
        synchronized (mList) {
            int what = MSG_ICON | index;
            mHandler.removeMessages(what);
            mHandler.obtainMessage(what, OP_REMOVE_ICON, 0, null).sendToTarget();
        }
    }

    public void addNotification(IBinder key, StatusBarNotification notification) {
        synchronized (mList) {
            NotificationQueueEntry ne = new NotificationQueueEntry();
            ne.key = key;
            ne.notification = notification;
            mHandler.obtainMessage(MSG_ADD_NOTIFICATION, 0, 0, ne).sendToTarget();
        }
    }

    public void updateNotification(IBinder key, StatusBarNotification notification) {
        synchronized (mList) {
            NotificationQueueEntry ne = new NotificationQueueEntry();
            ne.key = key;
            ne.notification = notification;
            mHandler.obtainMessage(MSG_UPDATE_NOTIFICATION, 0, 0, ne).sendToTarget();
        }
    }

    public void removeNotification(IBinder key) {
        synchronized (mList) {
            mHandler.obtainMessage(MSG_REMOVE_NOTIFICATION, 0, 0, key).sendToTarget();
        }
    }

    public void disable(int state) {
        synchronized (mList) {
            mHandler.removeMessages(MSG_DISABLE);
            mHandler.obtainMessage(MSG_DISABLE, state, 0, null).sendToTarget();
        }
    }

    public void animateExpand() {
        synchronized (mList) {
            mHandler.removeMessages(MSG_SET_VISIBILITY);
            mHandler.obtainMessage(MSG_SET_VISIBILITY, OP_EXPAND, 0, null).sendToTarget();
        }
    }

    public void animateCollapse() {
        synchronized (mList) {
            mHandler.removeMessages(MSG_SET_VISIBILITY);
            mHandler.obtainMessage(MSG_SET_VISIBILITY, OP_COLLAPSE, 0, null).sendToTarget();
        }
    }

    private final class H extends Handler {
        public void handleMessage(Message msg) {
            final int what = msg.what & MSG_MASK;
            switch (what) {
                case MSG_ICON: {
                    final int index = msg.what & INDEX_MASK;
                    final int viewIndex = mList.getViewIndex(index);
                    switch (msg.arg1) {
                        case OP_SET_ICON: {
                            StatusBarIcon icon = (StatusBarIcon)msg.obj;
                            StatusBarIcon old = mList.getIcon(index);
                            if (old == null) {
                                mList.setIcon(index, icon);
                                mCallbacks.addIcon(mList.getSlot(index), index, viewIndex, icon);
                            } else {
                                mList.setIcon(index, icon);
                                mCallbacks.updateIcon(mList.getSlot(index), index, viewIndex,
                                        old, icon);
                            }
                            break;
                        }
                        case OP_REMOVE_ICON:
                            mList.removeIcon(index);
                            mCallbacks.removeIcon(mList.getSlot(index), index, viewIndex);
                            break;
                    }
                    break;
                }
                case MSG_ADD_NOTIFICATION: {
                    final NotificationQueueEntry ne = (NotificationQueueEntry)msg.obj;
                    mCallbacks.addNotification(ne.key, ne.notification);
                    break;
                }
                case MSG_UPDATE_NOTIFICATION: {
                    final NotificationQueueEntry ne = (NotificationQueueEntry)msg.obj;
                    mCallbacks.updateNotification(ne.key, ne.notification);
                    break;
                }
                case MSG_REMOVE_NOTIFICATION: {
                    mCallbacks.removeNotification((IBinder)msg.obj);
                    break;
                }
                case MSG_DISABLE:
                    mCallbacks.disable(msg.arg1);
                    break;
                case MSG_SET_VISIBILITY:
                    if (msg.arg1 == OP_EXPAND) {
                        mCallbacks.animateExpand();
                    } else {
                        mCallbacks.animateCollapse();
                    }
            }
        }
    }
}

