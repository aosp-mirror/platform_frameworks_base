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
package com.android.systemui.qs;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.FontSizeUtils;
import com.android.systemui.R;
import com.android.systemui.statusbar.phone.QSTileHost;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.SecurityController;
import com.android.systemui.statusbar.policy.SecurityController.VpnCallback;

public class QSFooter implements OnClickListener, DialogInterface.OnClickListener {
    protected static final String TAG = "QSFooter";
    protected static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final View mRootView;
    private final TextView mFooterText;
    private final ImageView mFooterIcon;
    private final Context mContext;
    private final Callback mCallback = new Callback();

    private SecurityController mSecurityController;
    private AlertDialog mDialog;
    private QSTileHost mHost;
    private Handler mHandler;

    private boolean mIsVisible;
    private boolean mIsIconVisible;
    private int mFooterTextId;

    public QSFooter(QSPanel qsPanel, Context context) {
        mRootView = LayoutInflater.from(context)
                .inflate(R.layout.quick_settings_footer, qsPanel, false);
        mRootView.setOnClickListener(this);
        mFooterText = (TextView) mRootView.findViewById(R.id.footer_text);
        mFooterIcon = (ImageView) mRootView.findViewById(R.id.footer_icon);
        mContext = context;
    }

    public void setHost(QSTileHost host) {
        mHost = host;
        mSecurityController = host.getSecurityController();
        mHandler = new H(host.getLooper());
    }

    public void setListening(boolean listening) {
        if (listening) {
            mSecurityController.addCallback(mCallback);
        } else {
            mSecurityController.removeCallback(mCallback);
        }
    }

    public void onConfigurationChanged() {
        FontSizeUtils.updateFontSize(mFooterText, R.dimen.qs_tile_text_size);
    }

    public View getView() {
        return mRootView;
    }

    public boolean hasFooter() {
        return mRootView.getVisibility() != View.GONE;
    }

    @Override
    public void onClick(View v) {
        mHandler.sendEmptyMessage(H.CLICK);
    }

    private void handleClick() {
        mHost.collapsePanels();
        // TODO: Delay dialog creation until after panels are collapsed.
        createDialog();
    }

    public void refreshState() {
        mHandler.sendEmptyMessage(H.REFRESH_STATE);
    }

    private void handleRefreshState() {
        if (mSecurityController.hasDeviceOwner()) {
            mFooterTextId = R.string.device_owned_footer;
            mIsVisible = true;
            mIsIconVisible = false;
        } else if (mSecurityController.isVpnEnabled()) {
            mFooterTextId = R.string.vpn_footer;
            mIsVisible = true;
            mIsIconVisible = true;
        } else {
            mIsVisible = false;
        }
        mRootView.post(mUpdateDisplayState);
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        if (which == DialogInterface.BUTTON_NEGATIVE) {
            mSecurityController.disconnectFromVpn();
        }
    }

    private void createDialog() {
        mDialog = new SystemUIDialog(mContext);
        mDialog.setTitle(getTitle());
        mDialog.setMessage(getMessage());
        mDialog.setButton(DialogInterface.BUTTON_POSITIVE, getPositiveButton(), this);
        if (mSecurityController.isVpnEnabled()) {
            mDialog.setButton(DialogInterface.BUTTON_NEGATIVE, getNegativeButton(), this);
        }
        mDialog.show();
    }

    private String getNegativeButton() {
        if (mSecurityController.isLegacyVpn()) {
            return mContext.getString(R.string.disconnect_vpn);
        } else {
            return mContext.getString(R.string.disable_vpn);
        }
    }

    private String getPositiveButton() {
        return mContext.getString(R.string.quick_settings_done);
    }

    private String getMessage() {
        if (mSecurityController.hasDeviceOwner()) {
            if (mSecurityController.isVpnEnabled()) {
                if (mSecurityController.isLegacyVpn()) {
                    return mContext.getString(
                            R.string.monitoring_description_legacy_vpn_device_owned,
                            mSecurityController.getDeviceOwnerName(),
                            mSecurityController.getLegacyVpnName());
                } else {
                    return mContext.getString(R.string.monitoring_description_vpn_device_owned,
                            mSecurityController.getDeviceOwnerName(),
                            mSecurityController.getVpnApp());
                }
            } else {
                return mContext.getString(R.string.monitoring_description_device_owned,
                        mSecurityController.getDeviceOwnerName());
            }
        } else {
            if (mSecurityController.isLegacyVpn()) {
                return mContext.getString(R.string.monitoring_description_legacy_vpn,
                        mSecurityController.getLegacyVpnName());

            } else {
                return mContext.getString(R.string.monitoring_description_vpn,
                        mSecurityController.getVpnApp());
            }
        }
    }

    private int getTitle() {
        if (mSecurityController.hasDeviceOwner()) {
            return R.string.monitoring_title_device_owned;
        }
        return R.string.monitoring_title;
    }

    private final Runnable mUpdateDisplayState = new Runnable() {
        @Override
        public void run() {
            if (mFooterTextId != 0) {
                mFooterText.setText(mFooterTextId);
            }
            mRootView.setVisibility(mIsVisible ? View.VISIBLE : View.GONE);
            mFooterIcon.setVisibility(mIsIconVisible ? View.VISIBLE : View.INVISIBLE);
        }
    };

    private class Callback implements VpnCallback {
        @Override
        public void onVpnStateChanged() {
            refreshState();
        }
    }

    private class H extends Handler {
        private static final int CLICK = 0;
        private static final int REFRESH_STATE = 1;

        private H(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            String name = null;
            try {
                if (msg.what == REFRESH_STATE) {
                    name = "handleRefreshState";
                    handleRefreshState();
                } else if (msg.what == CLICK) {
                    name = "handleClick";
                    handleClick();
                }
            } catch (Throwable t) {
                final String error = "Error in " + name;
                Log.w(TAG, error, t);
                mHost.warn(error, t);
            }
        }
    }

}
