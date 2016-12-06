/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.recents;

import android.graphics.Rect;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;

import com.android.internal.os.SomeArgs;

/**
 * A proxy class which directs all methods from {@link IRecentsNonSystemUserCallbacks} to
 * {@link RecentsImpl} and makes sure they are called from the main thread.
 */
public class RecentsImplProxy extends IRecentsNonSystemUserCallbacks.Stub {

    private static final int MSG_PRELOAD_RECENTS = 1;
    private static final int MSG_CANCEL_PRELOADING_RECENTS = 2;
    private static final int MSG_SHOW_RECENTS = 3;
    private static final int MSG_HIDE_RECENTS = 4;
    private static final int MSG_TOGGLE_RECENTS = 5;
    private static final int MSG_ON_CONFIGURATION_CHANGED = 6;
    private static final int MSG_DOCK_TOP_TASK = 7;
    private static final int MSG_ON_DRAGGING_IN_RECENTS = 8;
    private static final int MSG_ON_DRAGGING_IN_RECENTS_ENDED = 9;
    private static final int MSG_SHOW_USER_TOAST = 10;

    private RecentsImpl mImpl;

    public RecentsImplProxy(RecentsImpl recentsImpl) {
        mImpl = recentsImpl;
    }

    @Override
    public void preloadRecents() throws RemoteException {
        mHandler.sendEmptyMessage(MSG_PRELOAD_RECENTS);
    }

    @Override
    public void cancelPreloadingRecents() throws RemoteException {
        mHandler.sendEmptyMessage(MSG_CANCEL_PRELOADING_RECENTS);
    }

    @Override
    public void showRecents(boolean triggeredFromAltTab, boolean draggingInRecents, boolean animate,
            boolean reloadTasks, boolean fromHome, int growTarget)
            throws RemoteException {
        SomeArgs args = SomeArgs.obtain();
        args.argi1 = triggeredFromAltTab ? 1 : 0;
        args.argi2 = draggingInRecents ? 1 : 0;
        args.argi3 = animate ? 1 : 0;
        args.argi4 = reloadTasks ? 1 : 0;
        args.argi5 = fromHome ? 1 : 0;
        args.argi6 = growTarget;
        mHandler.sendMessage(mHandler.obtainMessage(MSG_SHOW_RECENTS, args));
    }

    @Override
    public void hideRecents(boolean triggeredFromAltTab, boolean triggeredFromHomeKey)
            throws RemoteException {
        mHandler.sendMessage(mHandler.obtainMessage(MSG_HIDE_RECENTS, triggeredFromAltTab ? 1 :0,
                triggeredFromHomeKey ? 1 : 0));
    }

    @Override
    public void toggleRecents(int growTarget) throws RemoteException {
        SomeArgs args = SomeArgs.obtain();
        args.argi1 = growTarget;
        mHandler.sendMessage(mHandler.obtainMessage(MSG_TOGGLE_RECENTS, args));
    }

    @Override
    public void onConfigurationChanged() throws RemoteException {
        mHandler.sendEmptyMessage(MSG_ON_CONFIGURATION_CHANGED);
    }

    @Override
    public void dockTopTask(int topTaskId, int dragMode, int stackCreateMode,
            Rect initialBounds) throws RemoteException {
        SomeArgs args = SomeArgs.obtain();
        args.argi1 = topTaskId;
        args.argi2 = dragMode;
        args.argi3 = stackCreateMode;
        args.arg1 = initialBounds;
        mHandler.sendMessage(mHandler.obtainMessage(MSG_DOCK_TOP_TASK, args));
    }

    @Override
    public void onDraggingInRecents(float distanceFromTop) throws RemoteException {
        mHandler.sendMessage(mHandler.obtainMessage(MSG_ON_DRAGGING_IN_RECENTS, distanceFromTop));
    }

    @Override
    public void onDraggingInRecentsEnded(float velocity) throws RemoteException {
        mHandler.sendMessage(mHandler.obtainMessage(MSG_ON_DRAGGING_IN_RECENTS_ENDED, velocity));
    }

    @Override
    public void showCurrentUserToast(int msgResId, int msgLength) {
        mHandler.sendMessage(mHandler.obtainMessage(MSG_SHOW_USER_TOAST, msgResId, msgLength));
    }

    private final Handler mHandler = new Handler() {

        @Override
        public void handleMessage(Message msg) {
            SomeArgs args;
            switch (msg.what) {
                case MSG_PRELOAD_RECENTS:
                    mImpl.preloadRecents();
                    break;
                case MSG_CANCEL_PRELOADING_RECENTS:
                    mImpl.cancelPreloadingRecents();
                    break;
                case MSG_SHOW_RECENTS:
                    args = (SomeArgs) msg.obj;
                    mImpl.showRecents(args.argi1 != 0, args.argi2 != 0, args.argi3 != 0,
                            args.argi4 != 0, args.argi5 != 0, args.argi6);
                    break;
                case MSG_HIDE_RECENTS:
                    mImpl.hideRecents(msg.arg1 != 0, msg.arg2 != 0);
                    break;
                case MSG_TOGGLE_RECENTS:
                    args = (SomeArgs) msg.obj;
                    mImpl.toggleRecents(args.argi1);
                    break;
                case MSG_ON_CONFIGURATION_CHANGED:
                    mImpl.onConfigurationChanged();
                    break;
                case MSG_DOCK_TOP_TASK:
                    args = (SomeArgs) msg.obj;
                    mImpl.dockTopTask(args.argi1, args.argi2, args.argi3 = 0,
                            (Rect) args.arg1);
                    break;
                case MSG_ON_DRAGGING_IN_RECENTS:
                    mImpl.onDraggingInRecents((Float) msg.obj);
                    break;
                case MSG_ON_DRAGGING_IN_RECENTS_ENDED:
                    mImpl.onDraggingInRecentsEnded((Float) msg.obj);
                    break;
                case MSG_SHOW_USER_TOAST:
                    mImpl.onShowCurrentUserToast(msg.arg1, msg.arg2);
                    break;
                default:
                    super.handleMessage(msg);
            }
            super.handleMessage(msg);
        }
    };
}
