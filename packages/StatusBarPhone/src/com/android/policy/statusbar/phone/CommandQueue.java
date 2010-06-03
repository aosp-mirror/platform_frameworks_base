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

package com.android.policy.statusbar.phone;

import android.os.Handler;
import android.os.Message;
import android.util.Log;

import com.android.internal.statusbar.IStatusBar;
import com.android.internal.statusbar.StatusBarIcon;
import com.android.internal.statusbar.StatusBarIconList;

class CommandQueue extends IStatusBar.Stub {
    private static final String TAG = "StatusBar.CommandQueue";

    private static final int MSG_MASK = 0xffff0000;
    private static final int INDEX_MASK = 0x0000ffff;

    private static final int MSG_ICON = 0x00010000;
    private static final int OP_SET_ICON = 1;
    private static final int OP_REMOVE_ICON = 2;

    private StatusBarIconList mList;
    private Callbacks mCallbacks;
    private Handler mHandler = new H();

    /**
     * These methods are called back on the main thread.
     */
    public interface Callbacks {
        public void addIcon(String slot, int index, int viewIndex, StatusBarIcon icon);
        public void updateIcon(String slot, int index, int viewIndex,
                StatusBarIcon old, StatusBarIcon icon);
        public void removeIcon(String slot, int index, int viewIndex);
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

    private final class H extends Handler {
        public void handleMessage(Message msg) {
            int what = msg.what & MSG_MASK;
            switch (msg.what) {
                case MSG_ICON: {
                    int index = msg.what & INDEX_MASK;
                    int viewIndex = mList.getViewIndex(index);
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
            }
        }
    }
}


