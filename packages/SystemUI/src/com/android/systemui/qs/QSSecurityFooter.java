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

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyEventLogger;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.UserInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.UserManager;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.util.FrameworkStatsLog;
import com.android.systemui.Dependency;
import com.android.systemui.FontSizeUtils;
import com.android.systemui.R;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.SecurityController;

public class QSSecurityFooter implements OnClickListener, DialogInterface.OnClickListener {
    protected static final String TAG = "QSSecurityFooter";
    protected static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final boolean DEBUG_FORCE_VISIBLE = false;

    private final View mRootView;
    private final TextView mFooterText;
    private final ImageView mFooterIcon;
    private final Context mContext;
    private final Callback mCallback = new Callback();
    private final SecurityController mSecurityController;
    private final ActivityStarter mActivityStarter;
    private final Handler mMainHandler;

    private final UserManager mUm;

    private AlertDialog mDialog;
    private QSTileHost mHost;
    protected H mHandler;

    private boolean mIsVisible;
    private CharSequence mFooterTextContent = null;
    private int mFooterTextId;
    private int mFooterIconId;

    public QSSecurityFooter(QSPanel qsPanel, Context context) {
        mRootView = LayoutInflater.from(context)
                .inflate(R.layout.quick_settings_footer, qsPanel, false);
        mRootView.setOnClickListener(this);
        mFooterText = (TextView) mRootView.findViewById(R.id.footer_text);
        mFooterIcon = (ImageView) mRootView.findViewById(R.id.footer_icon);
        mFooterIconId = R.drawable.ic_info_outline;
        mContext = context;
        mMainHandler = new Handler(Looper.myLooper());
        mActivityStarter = Dependency.get(ActivityStarter.class);
        mSecurityController = Dependency.get(SecurityController.class);
        mHandler = new H(Dependency.get(Dependency.BG_LOOPER));
        mUm = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
    }

    public void setHostEnvironment(QSTileHost host) {
        mHost = host;
    }

    public void setListening(boolean listening) {
        if (listening) {
            mSecurityController.addCallback(mCallback);
            refreshState();
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
        if (!hasFooter()) return;
        mHandler.sendEmptyMessage(H.CLICK);
    }

    private void handleClick() {
        showDeviceMonitoringDialog();
        DevicePolicyEventLogger
                .createEvent(FrameworkStatsLog.DEVICE_POLICY_EVENT__EVENT_ID__DO_USER_INFO_CLICKED)
                .write();
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
        final boolean isDeviceManaged = mSecurityController.isDeviceManaged();
        final UserInfo currentUser = mUm.getUserInfo(ActivityManager.getCurrentUser());
        final boolean isDemoDevice = UserManager.isDeviceInDemoMode(mContext) && currentUser != null
                && currentUser.isDemo();
        final boolean hasWorkProfile = mSecurityController.hasWorkProfile();
        final boolean hasCACerts = mSecurityController.hasCACertInCurrentUser();
        final boolean hasCACertsInWorkProfile = mSecurityController.hasCACertInWorkProfile();
        final boolean isNetworkLoggingEnabled = mSecurityController.isNetworkLoggingEnabled();
        final String vpnName = mSecurityController.getPrimaryVpnName();
        final String vpnNameWorkProfile = mSecurityController.getWorkProfileVpnName();
        final CharSequence organizationName = mSecurityController.getDeviceOwnerOrganizationName();
        final CharSequence workProfileOrganizationName =
                mSecurityController.getWorkProfileOrganizationName();
        final boolean isProfileOwnerOfOrganizationOwnedDevice =
                mSecurityController.isProfileOwnerOfOrganizationOwnedDevice();
        // Update visibility of footer
        mIsVisible = (isDeviceManaged && !isDemoDevice) || hasCACerts || hasCACertsInWorkProfile
                || vpnName != null || vpnNameWorkProfile != null
                || isProfileOwnerOfOrganizationOwnedDevice;
        // Update the string
        mFooterTextContent = getFooterText(isDeviceManaged, hasWorkProfile,
                hasCACerts, hasCACertsInWorkProfile, isNetworkLoggingEnabled, vpnName,
                vpnNameWorkProfile, organizationName, workProfileOrganizationName,
                isProfileOwnerOfOrganizationOwnedDevice);
        // Update the icon
        int footerIconId = R.drawable.ic_info_outline;
        if (vpnName != null || vpnNameWorkProfile != null) {
            if (mSecurityController.isVpnBranded()) {
                footerIconId = R.drawable.stat_sys_branded_vpn;
            } else {
                footerIconId = R.drawable.stat_sys_vpn_ic;
            }
        }
        if (mFooterIconId != footerIconId) {
            mFooterIconId = footerIconId;
            mMainHandler.post(mUpdateIcon);
        }
        mMainHandler.post(mUpdateDisplayState);
    }

    protected CharSequence getFooterText(boolean isDeviceManaged, boolean hasWorkProfile,
            boolean hasCACerts, boolean hasCACertsInWorkProfile, boolean isNetworkLoggingEnabled,
            String vpnName, String vpnNameWorkProfile, CharSequence organizationName,
            CharSequence workProfileOrganizationName,
            boolean isProfileOwnerOfOrganizationOwnedDevice) {
        if (isDeviceManaged || DEBUG_FORCE_VISIBLE) {
            if (hasCACerts || hasCACertsInWorkProfile || isNetworkLoggingEnabled) {
                if (organizationName == null) {
                    return mContext.getString(
                            R.string.quick_settings_disclosure_management_monitoring);
                }
                return mContext.getString(
                        R.string.quick_settings_disclosure_named_management_monitoring,
                        organizationName);
            }
            if (vpnName != null && vpnNameWorkProfile != null) {
                if (organizationName == null) {
                    return mContext.getString(R.string.quick_settings_disclosure_management_vpns);
                }
                return mContext.getString(R.string.quick_settings_disclosure_named_management_vpns,
                        organizationName);
            }
            if (vpnName != null || vpnNameWorkProfile != null) {
                if (organizationName == null) {
                    return mContext.getString(
                            R.string.quick_settings_disclosure_management_named_vpn,
                            vpnName != null ? vpnName : vpnNameWorkProfile);
                }
                return mContext.getString(
                        R.string.quick_settings_disclosure_named_management_named_vpn,
                        organizationName,
                        vpnName != null ? vpnName : vpnNameWorkProfile);
            }
            if (organizationName == null) {
                return mContext.getString(R.string.quick_settings_disclosure_management);
            }
            return mContext.getString(R.string.quick_settings_disclosure_named_management,
                    organizationName);
        } // end if(isDeviceManaged)
        if (hasCACertsInWorkProfile) {
            if (workProfileOrganizationName == null) {
                return mContext.getString(
                        R.string.quick_settings_disclosure_managed_profile_monitoring);
            }
            return mContext.getString(
                    R.string.quick_settings_disclosure_named_managed_profile_monitoring,
                    workProfileOrganizationName);
        }
        if (hasCACerts) {
            return mContext.getString(R.string.quick_settings_disclosure_monitoring);
        }
        if (vpnName != null && vpnNameWorkProfile != null) {
            return mContext.getString(R.string.quick_settings_disclosure_vpns);
        }
        if (vpnNameWorkProfile != null) {
            return mContext.getString(R.string.quick_settings_disclosure_managed_profile_named_vpn,
                    vpnNameWorkProfile);
        }
        if (vpnName != null) {
            if (hasWorkProfile) {
                return mContext.getString(
                        R.string.quick_settings_disclosure_personal_profile_named_vpn,
                        vpnName);
            }
            return mContext.getString(R.string.quick_settings_disclosure_named_vpn,
                    vpnName);
        }
        if (isProfileOwnerOfOrganizationOwnedDevice) {
            if (workProfileOrganizationName == null) {
                return mContext.getString(R.string.quick_settings_disclosure_management);
            }
            return mContext.getString(R.string.quick_settings_disclosure_named_management,
                    workProfileOrganizationName);
        }
        return null;
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        if (which == DialogInterface.BUTTON_NEGATIVE) {
            final Intent intent = new Intent(Settings.ACTION_ENTERPRISE_PRIVACY_SETTINGS);
            mDialog.dismiss();
            mActivityStarter.postStartActivityDismissingKeyguard(intent, 0);
        }
    }

    private void createDialog() {
        final boolean isDeviceManaged = mSecurityController.isDeviceManaged();
        boolean isProfileOwnerOfOrganizationOwnedDevice =
                mSecurityController.isProfileOwnerOfOrganizationOwnedDevice();
        final boolean hasWorkProfile = mSecurityController.hasWorkProfile();
        final CharSequence deviceOwnerOrganization =
                mSecurityController.getDeviceOwnerOrganizationName();
        final CharSequence workProfileOrganizationName =
                mSecurityController.getWorkProfileOrganizationName();
        final boolean hasCACerts = mSecurityController.hasCACertInCurrentUser();
        final boolean hasCACertsInWorkProfile = mSecurityController.hasCACertInWorkProfile();
        final boolean isNetworkLoggingEnabled = mSecurityController.isNetworkLoggingEnabled();
        final String vpnName = mSecurityController.getPrimaryVpnName();
        final String vpnNameWorkProfile = mSecurityController.getWorkProfileVpnName();

        mDialog = new SystemUIDialog(mContext);
        mDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View dialogView = LayoutInflater.from(
                new ContextThemeWrapper(mContext, R.style.Theme_SystemUI_Dialog))
                .inflate(R.layout.quick_settings_footer_dialog, null, false);
        mDialog.setView(dialogView);
        mDialog.setButton(DialogInterface.BUTTON_POSITIVE, getPositiveButton(), this);

        // device management section
        CharSequence managementMessage = getManagementMessage(isDeviceManaged,
                deviceOwnerOrganization, isProfileOwnerOfOrganizationOwnedDevice,
                workProfileOrganizationName);
        if (managementMessage == null) {
            dialogView.findViewById(R.id.device_management_disclosures).setVisibility(View.GONE);
        } else {
            dialogView.findViewById(R.id.device_management_disclosures).setVisibility(View.VISIBLE);
            TextView deviceManagementWarning =
                    (TextView) dialogView.findViewById(R.id.device_management_warning);
            deviceManagementWarning.setText(managementMessage);
            // Don't show the policies button for profile owner of org owned device, because there
            // is no policies settings screen for it
            if (!isProfileOwnerOfOrganizationOwnedDevice) {
                mDialog.setButton(DialogInterface.BUTTON_NEGATIVE, getSettingsButton(), this);
            }
        }

        // ca certificate section
        CharSequence caCertsMessage = getCaCertsMessage(isDeviceManaged, hasCACerts,
                hasCACertsInWorkProfile);
        if (caCertsMessage == null) {
            dialogView.findViewById(R.id.ca_certs_disclosures).setVisibility(View.GONE);
        } else {
            dialogView.findViewById(R.id.ca_certs_disclosures).setVisibility(View.VISIBLE);
            TextView caCertsWarning = (TextView) dialogView.findViewById(R.id.ca_certs_warning);
            caCertsWarning.setText(caCertsMessage);
            // Make "Open trusted credentials"-link clickable
            caCertsWarning.setMovementMethod(new LinkMovementMethod());
        }

        // network logging section
        CharSequence networkLoggingMessage = getNetworkLoggingMessage(isNetworkLoggingEnabled);
        if (networkLoggingMessage == null) {
            dialogView.findViewById(R.id.network_logging_disclosures).setVisibility(View.GONE);
        } else {
            dialogView.findViewById(R.id.network_logging_disclosures).setVisibility(View.VISIBLE);
            TextView networkLoggingWarning =
                    (TextView) dialogView.findViewById(R.id.network_logging_warning);
            networkLoggingWarning.setText(networkLoggingMessage);
        }

        // vpn section
        CharSequence vpnMessage = getVpnMessage(isDeviceManaged, hasWorkProfile, vpnName,
                vpnNameWorkProfile);
        if (vpnMessage == null) {
            dialogView.findViewById(R.id.vpn_disclosures).setVisibility(View.GONE);
        } else {
            dialogView.findViewById(R.id.vpn_disclosures).setVisibility(View.VISIBLE);
            TextView vpnWarning = (TextView) dialogView.findViewById(R.id.vpn_warning);
            vpnWarning.setText(vpnMessage);
            // Make "Open VPN Settings"-link clickable
            vpnWarning.setMovementMethod(new LinkMovementMethod());
        }

        // Note: if a new section is added, should update configSubtitleVisibility to include
        // the handling of the subtitle
        configSubtitleVisibility(managementMessage != null,
                caCertsMessage != null,
                networkLoggingMessage != null,
                vpnMessage != null,
                dialogView);

        mDialog.show();
        mDialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    protected void configSubtitleVisibility(boolean showDeviceManagement, boolean showCaCerts,
            boolean showNetworkLogging, boolean showVpn, View dialogView) {
        // Device Management title should always been shown
        // When there is a Device Management message, all subtitles should be shown
        if (showDeviceManagement) {
            return;
        }
        // Hide the subtitle if there is only 1 message shown
        int mSectionCountExcludingDeviceMgt = 0;
        if (showCaCerts) { mSectionCountExcludingDeviceMgt++; }
        if (showNetworkLogging) { mSectionCountExcludingDeviceMgt++; }
        if (showVpn) { mSectionCountExcludingDeviceMgt++; }

        // No work needed if there is no sections or more than 1 section
        if (mSectionCountExcludingDeviceMgt != 1) {
            return;
        }
        if (showCaCerts) {
            dialogView.findViewById(R.id.ca_certs_subtitle).setVisibility(View.GONE);
        }
        if (showNetworkLogging) {
            dialogView.findViewById(R.id.network_logging_subtitle).setVisibility(View.GONE);
        }
        if (showVpn) {
            dialogView.findViewById(R.id.vpn_subtitle).setVisibility(View.GONE);
        }
    }

    private String getSettingsButton() {
        return mContext.getString(R.string.monitoring_button_view_policies);
    }

    private String getPositiveButton() {
        return mContext.getString(R.string.ok);
    }

    protected CharSequence getManagementMessage(boolean isDeviceManaged,
            CharSequence organizationName, boolean isProfileOwnerOfOrganizationOwnedDevice,
            CharSequence workProfileOrganizationName) {
        if (!isDeviceManaged && !isProfileOwnerOfOrganizationOwnedDevice) {
            return null;
        }
        if (isDeviceManaged && organizationName != null) {
            return mContext.getString(
                    R.string.monitoring_description_named_management, organizationName);
        } else if (isProfileOwnerOfOrganizationOwnedDevice && workProfileOrganizationName != null) {
            return mContext.getString(
                    R.string.monitoring_description_named_management, workProfileOrganizationName);
        }
        return mContext.getString(R.string.monitoring_description_management);
    }

    protected CharSequence getCaCertsMessage(boolean isDeviceManaged, boolean hasCACerts,
            boolean hasCACertsInWorkProfile) {
        if (!(hasCACerts || hasCACertsInWorkProfile)) return null;
        if (isDeviceManaged) {
            return mContext.getString(R.string.monitoring_description_management_ca_certificate);
        }
        if (hasCACertsInWorkProfile) {
            return mContext.getString(
                    R.string.monitoring_description_managed_profile_ca_certificate);
        }
        return mContext.getString(R.string.monitoring_description_ca_certificate);
    }

    protected CharSequence getNetworkLoggingMessage(boolean isNetworkLoggingEnabled) {
        if (!isNetworkLoggingEnabled) return null;
        return mContext.getString(R.string.monitoring_description_management_network_logging);
    }

    protected CharSequence getVpnMessage(boolean isDeviceManaged, boolean hasWorkProfile,
            String vpnName, String vpnNameWorkProfile) {
        if (vpnName == null && vpnNameWorkProfile == null) return null;
        final SpannableStringBuilder message = new SpannableStringBuilder();
        if (isDeviceManaged) {
            if (vpnName != null && vpnNameWorkProfile != null) {
                message.append(mContext.getString(R.string.monitoring_description_two_named_vpns,
                        vpnName, vpnNameWorkProfile));
            } else {
                message.append(mContext.getString(R.string.monitoring_description_named_vpn,
                        vpnName != null ? vpnName : vpnNameWorkProfile));
            }
        } else {
            if (vpnName != null && vpnNameWorkProfile != null) {
                message.append(mContext.getString(R.string.monitoring_description_two_named_vpns,
                        vpnName, vpnNameWorkProfile));
            } else if (vpnNameWorkProfile != null) {
                message.append(mContext.getString(
                        R.string.monitoring_description_managed_profile_named_vpn,
                        vpnNameWorkProfile));
            } else if (hasWorkProfile) {
                message.append(mContext.getString(
                        R.string.monitoring_description_personal_profile_named_vpn, vpnName));
            } else {
                message.append(mContext.getString(R.string.monitoring_description_named_vpn,
                        vpnName));
            }
        }
        message.append(mContext.getString(R.string.monitoring_description_vpn_settings_separator));
        message.append(mContext.getString(R.string.monitoring_description_vpn_settings),
                new VpnSpan(), 0);
        return message;
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
        }
    };

    private final Runnable mUpdateDisplayState = new Runnable() {
        @Override
        public void run() {
            if (mFooterTextContent != null) {
                mFooterText.setText(mFooterTextContent);
            }
            mRootView.setVisibility(mIsVisible || DEBUG_FORCE_VISIBLE ? View.VISIBLE : View.GONE);
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
            mDialog.dismiss();
            mActivityStarter.postStartActivityDismissingKeyguard(intent, 0);
        }

        // for testing, to compare two CharSequences containing VpnSpans
        @Override
        public boolean equals(Object object) {
            return object instanceof VpnSpan;
        }

        @Override
        public int hashCode() {
            return 314159257; // prime
        }
    }
}
