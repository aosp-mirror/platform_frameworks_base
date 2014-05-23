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

import android.app.ActivityManagerNative;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.os.Build;
import android.os.RemoteException;
import android.provider.Settings.Global;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;

import com.android.systemui.R;
import com.android.systemui.qs.GlobalSetting;
import com.android.systemui.qs.QSTile;

/** Quick settings tile: Bug report **/
public class BugreportTile extends QSTile<QSTile.State> {

    private final GlobalSetting mSetting;

    public BugreportTile(Host host) {
        super(host);
        mSetting = new GlobalSetting(mContext, mHandler, Global.BUGREPORT_IN_POWER_MENU) {
            @Override
            protected void handleValueChanged(int value) {
                handleRefreshState(null);
            }
        };
    }

    @Override
    protected State newTileState() {
        return new State();
    }

    @Override
    public void setListening(boolean listening) {
        mSetting.setListening(listening);
    }

    @Override
    protected void handleClick() {
        postAfterFeedback(new Runnable() {
            @Override
            public void run() {
                mHost.collapsePanels();
                mUiHandler.post(mShowDialog);
            }
        });
    }

    @Override
    protected void handleUpdateState(State state, Object pushArg) {
        state.visible = mSetting.getValue() != 0;
        state.iconId = R.drawable.ic_qs_bugreport;
        state.label = mContext.getString(
                R.string.bugreport_tile_extended,
                mContext.getString(com.android.internal.R.string.bugreport_title),
                Build.VERSION.RELEASE,
                Build.ID);
    }

    private final Runnable mShowDialog = new Runnable() {
        @Override
        public void run() {
            final AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
            builder.setPositiveButton(com.android.internal.R.string.report, new OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (which == DialogInterface.BUTTON_POSITIVE) {
                        // Add a little delay before executing, to give the
                        // dialog a chance to go away before it takes a
                        // screenshot.
                        mHandler.postDelayed(new Runnable() {
                            @Override public void run() {
                                try {
                                    ActivityManagerNative.getDefault().requestBugReport();
                                } catch (RemoteException e) {
                                }
                            }
                        }, 500);
                    }
                }
            });
            builder.setMessage(com.android.internal.R.string.bugreport_message);
            builder.setTitle(com.android.internal.R.string.bugreport_title);
            builder.setCancelable(true);
            final Dialog dialog = builder.create();
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
            try {
                WindowManagerGlobal.getWindowManagerService().dismissKeyguard();
            } catch (RemoteException e) {
            }
            dialog.show();
        }
    };
}
