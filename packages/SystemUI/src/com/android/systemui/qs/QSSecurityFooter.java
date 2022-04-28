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

import static android.app.admin.DevicePolicyManager.DEVICE_OWNER_TYPE_FINANCED;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_DIALOG_MANAGEMENT;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_DIALOG_MANAGEMENT_CA_CERT;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_DIALOG_MANAGEMENT_NAMED_VPN;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_DIALOG_MANAGEMENT_NETWORK;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_DIALOG_MANAGEMENT_TITLE;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_DIALOG_MANAGEMENT_TWO_NAMED_VPN;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_DIALOG_NAMED_MANAGEMENT;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_DIALOG_PERSONAL_PROFILE_NAMED_VPN;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_DIALOG_VIEW_POLICIES;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_DIALOG_WORK_PROFILE_CA_CERT;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_DIALOG_WORK_PROFILE_NAMED_VPN;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_DIALOG_WORK_PROFILE_NETWORK;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_MSG_MANAGEMENT;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_MSG_MANAGEMENT_MONITORING;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_MSG_MANAGEMENT_MULTIPLE_VPNS;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_MSG_MANAGEMENT_NAMED_VPN;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_MSG_NAMED_MANAGEMENT;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_MSG_NAMED_MANAGEMENT_MONITORING;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_MSG_NAMED_MANAGEMENT_MULTIPLE_VPNS;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_MSG_NAMED_MANAGEMENT_NAMED_VPN;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_MSG_NAMED_WORK_PROFILE_MONITORING;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_MSG_PERSONAL_PROFILE_NAMED_VPN;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_MSG_WORK_PROFILE_MONITORING;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_MSG_WORK_PROFILE_NAMED_VPN;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_MSG_WORK_PROFILE_NETWORK;

import static com.android.systemui.qs.dagger.QSFragmentModule.QS_SECURITY_FOOTER_VIEW;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.admin.DeviceAdminInfo;
import android.app.admin.DevicePolicyEventLogger;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.Window;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.internal.util.FrameworkStatsLog;
import com.android.systemui.FontSizeUtils;
import com.android.systemui.R;
import com.android.systemui.animation.DialogLaunchAnimator;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.qs.dagger.QSScope;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.SecurityController;
import com.android.systemui.util.ViewController;

import java.util.concurrent.atomic.AtomicBoolean;

import javax.inject.Inject;
import javax.inject.Named;

@QSScope
class QSSecurityFooter extends ViewController<View>
        implements OnClickListener, DialogInterface.OnClickListener,
        VisibilityChangedDispatcher {
    protected static final String TAG = "QSSecurityFooter";
    protected static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final boolean DEBUG_FORCE_VISIBLE = false;

    private final TextView mFooterText;
    private final ImageView mPrimaryFooterIcon;
    private final Context mContext;
    private final DevicePolicyManager mDpm;
    private final Callback mCallback = new Callback();
    private final SecurityController mSecurityController;
    private final ActivityStarter mActivityStarter;
    private final Handler mMainHandler;
    private final UserTracker mUserTracker;
    private final DialogLaunchAnimator mDialogLaunchAnimator;
    private final BroadcastDispatcher mBroadcastDispatcher;

    private final AtomicBoolean mShouldUseSettingsButton = new AtomicBoolean(false);

    private AlertDialog mDialog;
    protected H mHandler;

    private boolean mIsVisible;
    @Nullable
    private CharSequence mFooterTextContent = null;
    private int mFooterIconId;
    @Nullable
    private Drawable mPrimaryFooterIconDrawable;

    @Nullable
    private VisibilityChangedDispatcher.OnVisibilityChangedListener mVisibilityChangedListener;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(
                    DevicePolicyManager.ACTION_SHOW_DEVICE_MONITORING_DIALOG)) {
                showDeviceMonitoringDialog();
            }
        }
    };

    @Inject
    QSSecurityFooter(@Named(QS_SECURITY_FOOTER_VIEW) View rootView,
            UserTracker userTracker, @Main Handler mainHandler,
            ActivityStarter activityStarter, SecurityController securityController,
            DialogLaunchAnimator dialogLaunchAnimator, @Background Looper bgLooper,
            BroadcastDispatcher broadcastDispatcher) {
        super(rootView);
        mFooterText = mView.findViewById(R.id.footer_text);
        mPrimaryFooterIcon = mView.findViewById(R.id.primary_footer_icon);
        mFooterIconId = R.drawable.ic_info_outline;
        mContext = rootView.getContext();
        mDpm = rootView.getContext().getSystemService(DevicePolicyManager.class);
        mMainHandler = mainHandler;
        mActivityStarter = activityStarter;
        mSecurityController = securityController;
        mHandler = new H(bgLooper);
        mUserTracker = userTracker;
        mDialogLaunchAnimator = dialogLaunchAnimator;
        mBroadcastDispatcher = broadcastDispatcher;
    }

    @Override
    protected void onViewAttached() {
        // Use background handler, as it's the same thread that handleClick is called on.
        mBroadcastDispatcher.registerReceiverWithHandler(mReceiver,
                new IntentFilter(DevicePolicyManager.ACTION_SHOW_DEVICE_MONITORING_DIALOG),
                mHandler, UserHandle.ALL);
        mView.setOnClickListener(this);
    }

    @Override
    protected void onViewDetached() {
        mBroadcastDispatcher.unregisterReceiver(mReceiver);
        mView.setOnClickListener(null);
    }

    public void setListening(boolean listening) {
        if (listening) {
            mSecurityController.addCallback(mCallback);
            refreshState();
        } else {
            mSecurityController.removeCallback(mCallback);
        }
    }

    @Override
    public void setOnVisibilityChangedListener(
            @Nullable OnVisibilityChangedListener onVisibilityChangedListener) {
        mVisibilityChangedListener = onVisibilityChangedListener;
    }

    public void onConfigurationChanged() {
        FontSizeUtils.updateFontSize(mFooterText, R.dimen.qs_tile_text_size);
        Resources r = mContext.getResources();

        int padding = r.getDimensionPixelSize(R.dimen.qs_footer_padding);
        mView.setPaddingRelative(padding, 0, padding, 0);
        mView.setBackground(mContext.getDrawable(R.drawable.qs_security_footer_background));
    }

    public View getView() {
        return mView;
    }

    public boolean hasFooter() {
        return mView.getVisibility() != View.GONE;
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
        createDialog();
    }

    public void refreshState() {
        mHandler.sendEmptyMessage(H.REFRESH_STATE);
    }

    private void handleRefreshState() {
        final boolean isDeviceManaged = mSecurityController.isDeviceManaged();
        final UserInfo currentUser = mUserTracker.getUserInfo();
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
        final boolean isParentalControlsEnabled = mSecurityController.isParentalControlsEnabled();
        final boolean isWorkProfileOn = mSecurityController.isWorkProfileOn();
        final boolean hasDisclosableWorkProfilePolicy = hasCACertsInWorkProfile
                || vpnNameWorkProfile != null || (hasWorkProfile && isNetworkLoggingEnabled);
        // Update visibility of footer
        mIsVisible = (isDeviceManaged && !isDemoDevice)
                || hasCACerts
                || vpnName != null
                || isProfileOwnerOfOrganizationOwnedDevice
                || isParentalControlsEnabled
                || (hasDisclosableWorkProfilePolicy && isWorkProfileOn);
        // Update the view to be untappable if the device is an organization-owned device with a
        // managed profile and there is either:
        // a) no policy set which requires a privacy disclosure.
        // b) a specific work policy set but the work profile is turned off.
        if (mIsVisible && isProfileOwnerOfOrganizationOwnedDevice
                && (!hasDisclosableWorkProfilePolicy || !isWorkProfileOn)) {
            mView.setClickable(false);
            mView.findViewById(R.id.footer_icon).setVisibility(View.GONE);
        } else {
            mView.setClickable(true);
            mView.findViewById(R.id.footer_icon).setVisibility(View.VISIBLE);
        }
        // Update the string
        mFooterTextContent = getFooterText(isDeviceManaged, hasWorkProfile,
                hasCACerts, hasCACertsInWorkProfile, isNetworkLoggingEnabled, vpnName,
                vpnNameWorkProfile, organizationName, workProfileOrganizationName,
                isProfileOwnerOfOrganizationOwnedDevice, isParentalControlsEnabled,
                isWorkProfileOn);
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
        }

        // Update the primary icon
        if (isParentalControlsEnabled) {
            if (mPrimaryFooterIconDrawable == null) {
                DeviceAdminInfo info = mSecurityController.getDeviceAdminInfo();
                mPrimaryFooterIconDrawable = mSecurityController.getIcon(info);
            }
        } else {
            mPrimaryFooterIconDrawable = null;
        }
        mMainHandler.post(mUpdatePrimaryIcon);

        mMainHandler.post(mUpdateDisplayState);
    }

    @Nullable
    protected CharSequence getFooterText(boolean isDeviceManaged, boolean hasWorkProfile,
            boolean hasCACerts, boolean hasCACertsInWorkProfile, boolean isNetworkLoggingEnabled,
            String vpnName, String vpnNameWorkProfile, CharSequence organizationName,
            CharSequence workProfileOrganizationName,
            boolean isProfileOwnerOfOrganizationOwnedDevice, boolean isParentalControlsEnabled,
            boolean isWorkProfileOn) {
        if (isParentalControlsEnabled) {
            return mContext.getString(R.string.quick_settings_disclosure_parental_controls);
        }
        if (isDeviceManaged || DEBUG_FORCE_VISIBLE) {
            return getManagedDeviceFooterText(hasCACerts, hasCACertsInWorkProfile,
                    isNetworkLoggingEnabled, vpnName, vpnNameWorkProfile, organizationName);
        }
        return getManagedAndPersonalProfileFooterText(hasWorkProfile, hasCACerts,
                hasCACertsInWorkProfile, isNetworkLoggingEnabled, vpnName, vpnNameWorkProfile,
                workProfileOrganizationName, isProfileOwnerOfOrganizationOwnedDevice,
                isWorkProfileOn);
    }

    private String getManagedDeviceFooterText(
            boolean hasCACerts, boolean hasCACertsInWorkProfile, boolean isNetworkLoggingEnabled,
            String vpnName, String vpnNameWorkProfile, CharSequence organizationName) {
        if (hasCACerts || hasCACertsInWorkProfile || isNetworkLoggingEnabled) {
            return getManagedDeviceMonitoringText(organizationName);
        }
        if (vpnName != null || vpnNameWorkProfile != null) {
            return getManagedDeviceVpnText(vpnName, vpnNameWorkProfile, organizationName);
        }
        return getMangedDeviceGeneralText(organizationName);
    }

    private String getManagedDeviceMonitoringText(CharSequence organizationName) {
        if (organizationName == null) {
            return mDpm.getResources().getString(
                    QS_MSG_MANAGEMENT_MONITORING,
                    () -> mContext.getString(
                            R.string.quick_settings_disclosure_management_monitoring));
        }
        return mDpm.getResources().getString(
                QS_MSG_NAMED_MANAGEMENT_MONITORING,
                () -> mContext.getString(
                        R.string.quick_settings_disclosure_named_management_monitoring,
                        organizationName),
                organizationName);
    }

    private String getManagedDeviceVpnText(
            String vpnName, String vpnNameWorkProfile, CharSequence organizationName) {
        if (vpnName != null && vpnNameWorkProfile != null) {
            if (organizationName == null) {
                return mDpm.getResources().getString(
                        QS_MSG_MANAGEMENT_MULTIPLE_VPNS,
                        () -> mContext.getString(
                                R.string.quick_settings_disclosure_management_vpns));
            }
            return mDpm.getResources().getString(
                    QS_MSG_NAMED_MANAGEMENT_MULTIPLE_VPNS,
                    () -> mContext.getString(
                            R.string.quick_settings_disclosure_named_management_vpns,
                            organizationName),
                    organizationName);
        }
        String name = vpnName != null ? vpnName : vpnNameWorkProfile;
        if (organizationName == null) {
            return mDpm.getResources().getString(
                    QS_MSG_MANAGEMENT_NAMED_VPN,
                    () -> mContext.getString(
                            R.string.quick_settings_disclosure_management_named_vpn,
                            name),
                    name);
        }
        return mDpm.getResources().getString(
                QS_MSG_NAMED_MANAGEMENT_NAMED_VPN,
                () -> mContext.getString(
                        R.string.quick_settings_disclosure_named_management_named_vpn,
                        organizationName,
                        name),
                organizationName,
                name);
    }

    private String getMangedDeviceGeneralText(CharSequence organizationName) {
        if (organizationName == null) {
            return mDpm.getResources().getString(
                    QS_MSG_MANAGEMENT,
                    () -> mContext.getString(
                            R.string.quick_settings_disclosure_management));
        }
        if (isFinancedDevice()) {
            return mContext.getString(
                    R.string.quick_settings_financed_disclosure_named_management,
                    organizationName);
        } else {
            return mDpm.getResources().getString(
                    QS_MSG_NAMED_MANAGEMENT,
                    () -> mContext.getString(
                            R.string.quick_settings_disclosure_named_management,
                            organizationName),
                    organizationName);
        }
    }

    private String getManagedAndPersonalProfileFooterText(boolean hasWorkProfile,
            boolean hasCACerts, boolean hasCACertsInWorkProfile, boolean isNetworkLoggingEnabled,
            String vpnName, String vpnNameWorkProfile, CharSequence workProfileOrganizationName,
            boolean isProfileOwnerOfOrganizationOwnedDevice, boolean isWorkProfileOn) {
        if (hasCACerts || (hasCACertsInWorkProfile && isWorkProfileOn)) {
            return getMonitoringText(
                    hasCACerts, hasCACertsInWorkProfile, workProfileOrganizationName,
                    isWorkProfileOn);
        }
        if (vpnName != null || (vpnNameWorkProfile != null && isWorkProfileOn)) {
            return getVpnText(hasWorkProfile, vpnName, vpnNameWorkProfile, isWorkProfileOn);
        }
        if (hasWorkProfile && isNetworkLoggingEnabled && isWorkProfileOn) {
            return getManagedProfileNetworkActivityText();
        }
        if (isProfileOwnerOfOrganizationOwnedDevice) {
            return getMangedDeviceGeneralText(workProfileOrganizationName);
        }
        return null;
    }

    private String getMonitoringText(boolean hasCACerts, boolean hasCACertsInWorkProfile,
            CharSequence workProfileOrganizationName, boolean isWorkProfileOn) {
        if (hasCACertsInWorkProfile && isWorkProfileOn) {
            if (workProfileOrganizationName == null) {
                return mDpm.getResources().getString(
                        QS_MSG_WORK_PROFILE_MONITORING,
                        () -> mContext.getString(
                                R.string.quick_settings_disclosure_managed_profile_monitoring));
            }
            return mDpm.getResources().getString(
                    QS_MSG_NAMED_WORK_PROFILE_MONITORING,
                    () -> mContext.getString(
                            R.string.quick_settings_disclosure_named_managed_profile_monitoring,
                            workProfileOrganizationName),
                    workProfileOrganizationName);
        }
        if (hasCACerts) {
            return mContext.getString(R.string.quick_settings_disclosure_monitoring);
        }
        return null;
    }

    private String getVpnText(boolean hasWorkProfile, String vpnName, String vpnNameWorkProfile,
            boolean isWorkProfileOn) {
        if (vpnName != null && vpnNameWorkProfile != null) {
            return mContext.getString(R.string.quick_settings_disclosure_vpns);
        }
        if (vpnNameWorkProfile != null && isWorkProfileOn) {
            return mDpm.getResources().getString(
                    QS_MSG_WORK_PROFILE_NAMED_VPN,
                    () -> mContext.getString(
                            R.string.quick_settings_disclosure_managed_profile_named_vpn,
                            vpnNameWorkProfile),
                    vpnNameWorkProfile);
        }
        if (vpnName != null) {
            if (hasWorkProfile) {
                return mDpm.getResources().getString(
                        QS_MSG_PERSONAL_PROFILE_NAMED_VPN,
                        () -> mContext.getString(
                                R.string.quick_settings_disclosure_personal_profile_named_vpn,
                                vpnName),
                        vpnName);
            }
            return mContext.getString(R.string.quick_settings_disclosure_named_vpn,
                    vpnName);
        }
        return null;
    }

    private String getManagedProfileNetworkActivityText() {
        return mDpm.getResources().getString(
                QS_MSG_WORK_PROFILE_NETWORK,
                () -> mContext.getString(
                        R.string.quick_settings_disclosure_managed_profile_network_activity));
    }
    @Override
    public void onClick(DialogInterface dialog, int which) {
        if (which == DialogInterface.BUTTON_NEGATIVE) {
            final Intent intent = new Intent(Settings.ACTION_ENTERPRISE_PRIVACY_SETTINGS);
            dialog.dismiss();
            // This dismisses the shade on opening the activity
            mActivityStarter.postStartActivityDismissingKeyguard(intent, 0);
        }
    }

    private void createDialog() {
        mShouldUseSettingsButton.set(false);
        mHandler.post(() -> {
            String settingsButtonText = getSettingsButton();
            final View view = createDialogView();
            mMainHandler.post(() -> {
                mDialog = new SystemUIDialog(mContext, 0); // Use mContext theme
                mDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
                mDialog.setButton(DialogInterface.BUTTON_POSITIVE, getPositiveButton(), this);
                mDialog.setButton(DialogInterface.BUTTON_NEGATIVE, mShouldUseSettingsButton.get()
                        ? settingsButtonText : getNegativeButton(), this);

                mDialog.setView(view);
                if (mView.isAggregatedVisible()) {
                    mDialogLaunchAnimator.showFromView(mDialog, mView);
                } else {
                    mDialog.show();
                }
            });
        });
    }

    @VisibleForTesting
    Dialog getDialog() {
        return mDialog;
    }

    @VisibleForTesting
    View createDialogView() {
        if (mSecurityController.isParentalControlsEnabled()) {
            return createParentalControlsDialogView();
        }
        return createOrganizationDialogView();
    }

    private View createOrganizationDialogView() {
        final boolean isDeviceManaged = mSecurityController.isDeviceManaged();
        final boolean hasWorkProfile = mSecurityController.hasWorkProfile();
        final CharSequence deviceOwnerOrganization =
                mSecurityController.getDeviceOwnerOrganizationName();
        final boolean hasCACerts = mSecurityController.hasCACertInCurrentUser();
        final boolean hasCACertsInWorkProfile = mSecurityController.hasCACertInWorkProfile();
        final boolean isNetworkLoggingEnabled = mSecurityController.isNetworkLoggingEnabled();
        final String vpnName = mSecurityController.getPrimaryVpnName();
        final String vpnNameWorkProfile = mSecurityController.getWorkProfileVpnName();

        View dialogView = LayoutInflater.from(mContext)
                .inflate(R.layout.quick_settings_footer_dialog, null, false);

        // device management section
        TextView deviceManagementSubtitle =
                dialogView.findViewById(R.id.device_management_subtitle);
        deviceManagementSubtitle.setText(getManagementTitle(deviceOwnerOrganization));

        CharSequence managementMessage = getManagementMessage(isDeviceManaged,
                deviceOwnerOrganization);
        if (managementMessage == null) {
            dialogView.findViewById(R.id.device_management_disclosures).setVisibility(View.GONE);
        } else {
            dialogView.findViewById(R.id.device_management_disclosures).setVisibility(View.VISIBLE);
            TextView deviceManagementWarning =
                    (TextView) dialogView.findViewById(R.id.device_management_warning);
            deviceManagementWarning.setText(managementMessage);
            mShouldUseSettingsButton.set(true);
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
        CharSequence networkLoggingMessage = getNetworkLoggingMessage(isDeviceManaged,
                isNetworkLoggingEnabled);
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

        return dialogView;
    }

    private View createParentalControlsDialogView() {
        View dialogView = LayoutInflater.from(mContext)
                .inflate(R.layout.quick_settings_footer_dialog_parental_controls, null, false);

        DeviceAdminInfo info = mSecurityController.getDeviceAdminInfo();
        Drawable icon = mSecurityController.getIcon(info);
        if (icon != null) {
            ImageView imageView = (ImageView) dialogView.findViewById(R.id.parental_controls_icon);
            imageView.setImageDrawable(icon);
        }

        TextView parentalControlsTitle =
                (TextView) dialogView.findViewById(R.id.parental_controls_title);
        parentalControlsTitle.setText(mSecurityController.getLabel(info));

        return dialogView;
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

    // This should not be called on the main thread to avoid making an IPC.
    @VisibleForTesting
    String getSettingsButton() {
        return mDpm.getResources().getString(
                QS_DIALOG_VIEW_POLICIES,
                () -> mContext.getString(R.string.monitoring_button_view_policies));
    }

    private String getPositiveButton() {
        return mContext.getString(R.string.ok);
    }

    @Nullable
    private String getNegativeButton() {
        if (mSecurityController.isParentalControlsEnabled()) {
            return mContext.getString(R.string.monitoring_button_view_controls);
        }
        return null;
    }

    @Nullable
    protected CharSequence getManagementMessage(boolean isDeviceManaged,
            CharSequence organizationName) {
        if (!isDeviceManaged) {
            return null;
        }
        if (organizationName != null) {
            if (isFinancedDevice()) {
                return mContext.getString(R.string.monitoring_financed_description_named_management,
                        organizationName, organizationName);
            } else {
                return mDpm.getResources().getString(
                        QS_DIALOG_NAMED_MANAGEMENT,
                        () -> mContext.getString(
                                R.string.monitoring_description_named_management,
                                organizationName),
                        organizationName);
            }
        }
        return mDpm.getResources().getString(
                QS_DIALOG_MANAGEMENT,
                () -> mContext.getString(R.string.monitoring_description_management));
    }

    @Nullable
    protected CharSequence getCaCertsMessage(boolean isDeviceManaged, boolean hasCACerts,
            boolean hasCACertsInWorkProfile) {
        if (!(hasCACerts || hasCACertsInWorkProfile)) return null;
        if (isDeviceManaged) {
            return mDpm.getResources().getString(
                    QS_DIALOG_MANAGEMENT_CA_CERT,
                    () -> mContext.getString(
                            R.string.monitoring_description_management_ca_certificate));
        }
        if (hasCACertsInWorkProfile) {
            return mDpm.getResources().getString(
                    QS_DIALOG_WORK_PROFILE_CA_CERT,
                    () -> mContext.getString(
                            R.string.monitoring_description_managed_profile_ca_certificate));
        }
        return mContext.getString(R.string.monitoring_description_ca_certificate);
    }

    @Nullable
    protected CharSequence getNetworkLoggingMessage(boolean isDeviceManaged,
            boolean isNetworkLoggingEnabled) {
        if (!isNetworkLoggingEnabled) return null;
        if (isDeviceManaged) {
            return mDpm.getResources().getString(
                    QS_DIALOG_MANAGEMENT_NETWORK,
                    () -> mContext.getString(
                            R.string.monitoring_description_management_network_logging));
        } else {
            return mDpm.getResources().getString(
                    QS_DIALOG_WORK_PROFILE_NETWORK,
                    () -> mContext.getString(
                            R.string.monitoring_description_managed_profile_network_logging));
        }
    }

    @Nullable
    protected CharSequence getVpnMessage(boolean isDeviceManaged, boolean hasWorkProfile,
            String vpnName, String vpnNameWorkProfile) {
        if (vpnName == null && vpnNameWorkProfile == null) return null;
        final SpannableStringBuilder message = new SpannableStringBuilder();
        if (isDeviceManaged) {
            if (vpnName != null && vpnNameWorkProfile != null) {
                String namedVpns = mDpm.getResources().getString(
                        QS_DIALOG_MANAGEMENT_TWO_NAMED_VPN,
                        () -> mContext.getString(
                                R.string.monitoring_description_two_named_vpns,
                                vpnName, vpnNameWorkProfile),
                        vpnName, vpnNameWorkProfile);
                message.append(namedVpns);
            } else {
                String name = vpnName != null ? vpnName : vpnNameWorkProfile;
                String namedVp = mDpm.getResources().getString(
                        QS_DIALOG_MANAGEMENT_NAMED_VPN,
                        () -> mContext.getString(R.string.monitoring_description_named_vpn, name),
                        name);
                message.append(namedVp);
            }
        } else {
            if (vpnName != null && vpnNameWorkProfile != null) {
                String namedVpns = mDpm.getResources().getString(
                        QS_DIALOG_MANAGEMENT_TWO_NAMED_VPN,
                        () -> mContext.getString(
                                R.string.monitoring_description_two_named_vpns,
                                vpnName, vpnNameWorkProfile),
                        vpnName, vpnNameWorkProfile);
                message.append(namedVpns);
            } else if (vpnNameWorkProfile != null) {
                String namedVpn = mDpm.getResources().getString(
                        QS_DIALOG_WORK_PROFILE_NAMED_VPN,
                        () -> mContext.getString(
                                R.string.monitoring_description_managed_profile_named_vpn,
                                vpnNameWorkProfile),
                        vpnNameWorkProfile);
                message.append(namedVpn);
            } else if (hasWorkProfile) {
                String namedVpn = mDpm.getResources().getString(
                        QS_DIALOG_PERSONAL_PROFILE_NAMED_VPN,
                        () -> mContext.getString(
                                R.string.monitoring_description_personal_profile_named_vpn,
                                vpnName),
                        vpnName);
                message.append(namedVpn);
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

    @VisibleForTesting
    CharSequence getManagementTitle(CharSequence deviceOwnerOrganization) {
        if (deviceOwnerOrganization != null && isFinancedDevice()) {
            return mContext.getString(R.string.monitoring_title_financed_device,
                    deviceOwnerOrganization);
        } else {
            return mDpm.getResources().getString(
                    QS_DIALOG_MANAGEMENT_TITLE,
                    () -> mContext.getString(R.string.monitoring_title_device_owned));
        }
    }

    private boolean isFinancedDevice() {
        return mSecurityController.isDeviceManaged()
                && mSecurityController.getDeviceOwnerType(
                        mSecurityController.getDeviceOwnerComponentOnAnyUser())
                == DEVICE_OWNER_TYPE_FINANCED;
    }

    private final Runnable mUpdatePrimaryIcon = new Runnable() {
        @Override
        public void run() {
            if (mPrimaryFooterIconDrawable != null) {
                mPrimaryFooterIcon.setImageDrawable(mPrimaryFooterIconDrawable);
            } else {
                mPrimaryFooterIcon.setImageResource(mFooterIconId);
            }
        }
    };

    private final Runnable mUpdateDisplayState = new Runnable() {
        @Override
        public void run() {
            if (mFooterTextContent != null) {
                mFooterText.setText(mFooterTextContent);
            }
            mView.setVisibility(mIsVisible || DEBUG_FORCE_VISIBLE ? View.VISIBLE : View.GONE);
            if (mVisibilityChangedListener != null) {
                mVisibilityChangedListener.onVisibilityChanged(mView.getVisibility());
            }
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
            }
        }
    }

    protected class VpnSpan extends ClickableSpan {
        @Override
        public void onClick(View widget) {
            final Intent intent = new Intent(Settings.ACTION_VPN_SETTINGS);
            mDialog.dismiss();
            // This dismisses the shade on opening the activity
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
