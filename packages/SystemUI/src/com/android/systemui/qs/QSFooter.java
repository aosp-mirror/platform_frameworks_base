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
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.View.OnClickListener;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.FontSizeUtils;
import com.android.systemui.R;
import com.android.systemui.statusbar.phone.QSTileHost;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.SecurityController;

import static android.provider.Settings.ACTION_VPN_SETTINGS;

public class QSFooter implements OnClickListener, DialogInterface.OnClickListener {
    protected static final String TAG = "QSFooter";
    protected static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final View mRootView;
    private final TextView mFooterText;
    private final ImageView mFooterIcon;
    private final ImageView mFooterIcon2;
    private final Context mContext;
    private final Callback mCallback = new Callback();

    private SecurityController mSecurityController;
    private AlertDialog mDialog;
    private QSTileHost mHost;
    private Handler mHandler;
    private final Handler mMainHandler;

    private boolean mIsVisible;
    private boolean mIsIconVisible;
    private boolean mIsIcon2Visible;
    private int mFooterTextId;
    private int mFooterIconId;
    private int mFooterIcon2Id;

    public QSFooter(QSPanel qsPanel, Context context) {
        mRootView = LayoutInflater.from(context)
                .inflate(R.layout.quick_settings_footer, qsPanel, false);
        mRootView.setOnClickListener(this);
        mFooterText = (TextView) mRootView.findViewById(R.id.footer_text);
        mFooterIcon = (ImageView) mRootView.findViewById(R.id.footer_icon);
        mFooterIcon2 = (ImageView) mRootView.findViewById(R.id.footer_icon2);
        mFooterIconId = R.drawable.ic_qs_vpn;
        mFooterIcon2Id = R.drawable.ic_qs_network_logging;
        mContext = context;
        mMainHandler = new Handler();
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
        showDeviceMonitoringDialog();
    }

    public void showDeviceMonitoringDialog() {
        mHost.collapsePanels();
        // TODO: Delay dialog creation until after panels are collapsed.
        createDialog();
    }

    public void refreshState() {
        mHandler.sendEmptyMessage(H.REFRESH_STATE);
    }

    private void handleRefreshState() {
        // If the device has device owner, show "Device may be monitored", but --
        // TODO See b/25779452 -- device owner doesn't actually have monitoring power.
        boolean isVpnEnabled = mSecurityController.isVpnEnabled();
        boolean isNetworkLoggingEnabled = mSecurityController.isNetworkLoggingEnabled();
        mIsIconVisible = isVpnEnabled || isNetworkLoggingEnabled;
        mIsIcon2Visible = isVpnEnabled && isNetworkLoggingEnabled;
        if (mSecurityController.isDeviceManaged()) {
            mFooterTextId = R.string.device_owned_footer;
            mIsVisible = true;
            int footerIconId = isVpnEnabled
                    ? R.drawable.ic_qs_vpn
                    : R.drawable.ic_qs_network_logging;
            if (mFooterIconId != footerIconId) {
                mFooterIconId = footerIconId;
                mMainHandler.post(mUpdateIcon);
            }
        } else {
            boolean isBranded = mSecurityController.isVpnBranded();
            mFooterTextId = isBranded ? R.string.branded_vpn_footer : R.string.vpn_footer;
            // Update the VPN footer icon, if needed.
            int footerIconId = isVpnEnabled
                    ? (isBranded ? R.drawable.ic_qs_branded_vpn : R.drawable.ic_qs_vpn)
                    : R.drawable.ic_qs_network_logging;
            if (mFooterIconId != footerIconId) {
                mFooterIconId = footerIconId;
                mMainHandler.post(mUpdateIcon);
            }
            mIsVisible = mIsIconVisible;
        }
        mMainHandler.post(mUpdateDisplayState);
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        if (which == DialogInterface.BUTTON_NEGATIVE) {
            final Intent settingsIntent = new Intent(ACTION_VPN_SETTINGS);
            mHost.startActivityDismissingKeyguard(settingsIntent);
        }
    }

    private void createDialog() {
        final String deviceOwnerPackage = mSecurityController.getDeviceOwnerName();
        final String profileOwnerPackage = mSecurityController.getProfileOwnerName();
        final boolean isNetworkLoggingEnabled = mSecurityController.isNetworkLoggingEnabled();
        final String primaryVpn = mSecurityController.getPrimaryVpnName();
        final String profileVpn = mSecurityController.getProfileVpnName();
        boolean hasProfileOwner = mSecurityController.hasProfileOwner();
        boolean isBranded = deviceOwnerPackage == null && mSecurityController.isVpnBranded();

        mDialog = new SystemUIDialog(mContext);
        if (!isBranded) {
            mDialog.setTitle(getTitle(deviceOwnerPackage));
        }
        CharSequence msg = getMessage(deviceOwnerPackage, profileOwnerPackage, primaryVpn,
                profileVpn, hasProfileOwner, isBranded);
        if (deviceOwnerPackage == null) {
            mDialog.setMessage(msg);
            if (mSecurityController.isVpnEnabled() && !mSecurityController.isVpnRestricted()) {
                mDialog.setButton(DialogInterface.BUTTON_NEGATIVE, getSettingsButton(), this);
            }
        } else {
            View dialogView = LayoutInflater.from(mContext)
                   .inflate(R.layout.quick_settings_footer_dialog, null, false);
            mDialog.setView(dialogView);
            TextView deviceOwnerWarning =
                    (TextView) dialogView.findViewById(R.id.device_owner_warning);
            deviceOwnerWarning.setText(msg);
            // Make the link "learn more" clickable.
            deviceOwnerWarning.setMovementMethod(new LinkMovementMethod());
            if (primaryVpn == null) {
                dialogView.findViewById(R.id.vpn_icon).setVisibility(View.GONE);
                dialogView.findViewById(R.id.vpn_subtitle).setVisibility(View.GONE);
                dialogView.findViewById(R.id.vpn_warning).setVisibility(View.GONE);
            } else {
                final SpannableStringBuilder message = new SpannableStringBuilder();
                message.append(mContext.getString(R.string.monitoring_description_do_body_vpn,
                        primaryVpn));
                if (!mSecurityController.isVpnRestricted()) {
                    message.append(mContext.getString(
                            R.string.monitoring_description_vpn_settings_separator));
                    message.append(mContext.getString(R.string.monitoring_description_vpn_settings),
                            new VpnSpan(), 0);
                }

                TextView vpnWarning = (TextView) dialogView.findViewById(R.id.vpn_warning);
                vpnWarning.setText(message);
                // Make the link "Open VPN Settings" clickable.
                vpnWarning.setMovementMethod(new LinkMovementMethod());
            }
            if (!isNetworkLoggingEnabled) {
                dialogView.findViewById(R.id.network_logging_icon).setVisibility(View.GONE);
                dialogView.findViewById(R.id.network_logging_subtitle).setVisibility(View.GONE);
                dialogView.findViewById(R.id.network_logging_warning).setVisibility(View.GONE);
            }
        }

        mDialog.setButton(DialogInterface.BUTTON_POSITIVE, getPositiveButton(isBranded), this);
        mDialog.show();
        mDialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                                      ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private String getSettingsButton() {
        return mContext.getString(R.string.status_bar_settings_settings_button);
    }

    private String getPositiveButton(boolean isBranded) {
        return mContext.getString(isBranded ? android.R.string.ok : R.string.quick_settings_done);
    }

    protected CharSequence getMessage(String deviceOwnerPackage, String profileOwnerPackage,
            String primaryVpn, String profileVpn, boolean hasProfileOwner, boolean isBranded) {
        if (deviceOwnerPackage != null) {
            return mContext.getString(R.string.monitoring_description_device_owned,
                    deviceOwnerPackage);
        } else if (primaryVpn != null) {
            if (profileVpn != null) {
                return mContext.getString(R.string.monitoring_description_app_personal_work,
                        profileOwnerPackage, profileVpn, primaryVpn);
            } else {
                if (isBranded) {
                    return mContext.getString(R.string.branded_monitoring_description_app_personal,
                            primaryVpn);
                } else {
                    return mContext.getString(R.string.monitoring_description_app_personal,
                            primaryVpn);
                }
            }
        } else if (profileVpn != null) {
            return mContext.getString(R.string.monitoring_description_app_work,
                    profileOwnerPackage, profileVpn);
        } else if (profileOwnerPackage != null && hasProfileOwner) {
            return mContext.getString(R.string.monitoring_description_device_owned,
                    profileOwnerPackage);
        } else {
            // No device owner, no personal VPN, no work VPN, no user owner. Why are we here?
            return null;
        }
    }

    private int getTitle(String deviceOwner) {
        if (deviceOwner != null) {
            return R.string.monitoring_title_device_owned;
        } else {
            return R.string.monitoring_title;
        }
    }

    private final Runnable mUpdateIcon = new Runnable() {
        @Override
        public void run() {
            mFooterIcon.setImageResource(mFooterIconId);
            mFooterIcon2.setImageResource(mFooterIcon2Id);
        }
    };

    private final Runnable mUpdateDisplayState = new Runnable() {
        @Override
        public void run() {
            if (mFooterTextId != 0) {
                mFooterText.setText(mFooterTextId);
            }
            mRootView.setVisibility(mIsVisible ? View.VISIBLE : View.GONE);
            mFooterIcon.setVisibility(mIsIconVisible ? View.VISIBLE : View.INVISIBLE);
            mFooterIcon2.setVisibility(mIsIcon2Visible ? View.VISIBLE : View.INVISIBLE);
        }
    };

    private class Callback implements SecurityController.SecurityControllerCallback {
        @Override
        public void onStateChanged() {
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

    protected class VpnSpan extends ClickableSpan {
        @Override
        public void onClick(View widget) {
            final Intent intent = new Intent(Settings.ACTION_VPN_SETTINGS);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            mDialog.dismiss();
            mContext.startActivity(intent);
        }
    }
}
