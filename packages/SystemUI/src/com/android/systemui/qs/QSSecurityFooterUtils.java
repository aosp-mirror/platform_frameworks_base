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
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_DIALOG_MONITORING_CA_CERT_SUBTITLE;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_DIALOG_MONITORING_NETWORK_SUBTITLE;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_DIALOG_MONITORING_VPN_SUBTITLE;
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

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.admin.DeviceAdminInfo;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.UserInfo;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.systemui.R;
import com.android.systemui.animation.DialogCuj;
import com.android.systemui.animation.DialogLaunchAnimator;
import com.android.systemui.animation.Expandable;
import com.android.systemui.common.shared.model.ContentDescription;
import com.android.systemui.common.shared.model.Icon;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Application;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.qs.footer.domain.model.SecurityButtonConfig;
import com.android.systemui.security.data.model.SecurityModel;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.SecurityController;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import javax.inject.Inject;

/** Helper class for the configuration of the QS security footer button. */
@SysUISingleton
public class QSSecurityFooterUtils implements DialogInterface.OnClickListener {
    protected static final String TAG = "QSSecurityFooterUtils";
    protected static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final boolean DEBUG_FORCE_VISIBLE = false;

    private static final String INTERACTION_JANK_TAG = "managed_device_info";

    @Application private Context mContext;
    private final DevicePolicyManager mDpm;

    private final SecurityController mSecurityController;
    private final ActivityStarter mActivityStarter;
    private final Handler mMainHandler;
    private final UserTracker mUserTracker;
    private final DialogLaunchAnimator mDialogLaunchAnimator;

    private final AtomicBoolean mShouldUseSettingsButton = new AtomicBoolean(false);

    protected Handler mBgHandler;
    private AlertDialog mDialog;

    private Supplier<String> mManagementTitleSupplier = () ->
            mContext == null ? null : mContext.getString(R.string.monitoring_title_device_owned);

    private Supplier<String> mManagementMessageSupplier = () ->
            mContext == null ? null : mContext.getString(
                    R.string.quick_settings_disclosure_management);

    private Supplier<String> mManagementMonitoringStringSupplier = () ->
            mContext == null ? null : mContext.getString(
                    R.string.quick_settings_disclosure_management_monitoring);

    private Supplier<String> mManagementMultipleVpnStringSupplier = () ->
            mContext == null ? null : mContext.getString(
                    R.string.quick_settings_disclosure_management_vpns);

    private Supplier<String> mWorkProfileMonitoringStringSupplier = () ->
            mContext == null ? null : mContext.getString(
                    R.string.quick_settings_disclosure_managed_profile_monitoring);

    private Supplier<String> mWorkProfileNetworkStringSupplier = () ->
            mContext == null ? null : mContext.getString(
                    R.string.quick_settings_disclosure_managed_profile_network_activity);

    private Supplier<String> mMonitoringSubtitleCaCertStringSupplier = () ->
            mContext == null ? null : mContext.getString(
                    R.string.monitoring_subtitle_ca_certificate);

    private Supplier<String> mMonitoringSubtitleNetworkStringSupplier = () ->
            mContext == null ? null : mContext.getString(
                    R.string.monitoring_subtitle_network_logging);

    private Supplier<String> mMonitoringSubtitleVpnStringSupplier = () ->
            mContext == null ? null : mContext.getString(R.string.monitoring_subtitle_vpn);

    private Supplier<String> mViewPoliciesButtonStringSupplier = () ->
            mContext == null ? null : mContext.getString(R.string.monitoring_button_view_policies);

    private Supplier<String> mManagementDialogStringSupplier = () ->
            mContext == null ? null : mContext.getString(
                    R.string.monitoring_description_management);

    private Supplier<String> mManagementDialogCaCertStringSupplier = () ->
            mContext == null ? null : mContext.getString(
                    R.string.monitoring_description_management_ca_certificate);

    private Supplier<String> mWorkProfileDialogCaCertStringSupplier = () ->
            mContext == null ? null : mContext.getString(
                    R.string.monitoring_description_managed_profile_ca_certificate);

    private Supplier<String> mManagementDialogNetworkStringSupplier = () ->
            mContext == null ? null : mContext.getString(
                    R.string.monitoring_description_management_network_logging);

    private Supplier<String> mWorkProfileDialogNetworkStringSupplier = () ->
            mContext == null ? null : mContext.getString(
                    R.string.monitoring_description_managed_profile_network_logging);

    @Inject
    QSSecurityFooterUtils(
            @Application Context context, DevicePolicyManager devicePolicyManager,
            UserTracker userTracker, @Main Handler mainHandler, ActivityStarter activityStarter,
            SecurityController securityController, @Background Looper bgLooper,
            DialogLaunchAnimator dialogLaunchAnimator) {
        mContext = context;
        mDpm = devicePolicyManager;
        mUserTracker = userTracker;
        mMainHandler = mainHandler;
        mActivityStarter = activityStarter;
        mSecurityController = securityController;
        mBgHandler = new Handler(bgLooper);
        mDialogLaunchAnimator = dialogLaunchAnimator;
    }

    /** Show the device monitoring dialog. */
    public void showDeviceMonitoringDialog(Context quickSettingsContext,
            @Nullable Expandable expandable) {
        createDialog(quickSettingsContext, expandable);
    }

    /**
     * Return the {@link SecurityButtonConfig} of the security button, or {@code null} if no
     * security button should be shown.
     */
    @Nullable
    public SecurityButtonConfig getButtonConfig(SecurityModel securityModel) {
        final boolean isDeviceManaged = securityModel.isDeviceManaged();
        final UserInfo currentUser = mUserTracker.getUserInfo();
        final boolean isDemoDevice = UserManager.isDeviceInDemoMode(mContext) && currentUser != null
                && currentUser.isDemo();
        final boolean hasWorkProfile = securityModel.getHasWorkProfile();
        final boolean hasCACerts = securityModel.getHasCACertInCurrentUser();
        final boolean hasCACertsInWorkProfile = securityModel.getHasCACertInWorkProfile();
        final boolean isNetworkLoggingEnabled = securityModel.isNetworkLoggingEnabled();
        final String vpnName = securityModel.getPrimaryVpnName();
        final String vpnNameWorkProfile = securityModel.getWorkProfileVpnName();
        final CharSequence organizationName = securityModel.getDeviceOwnerOrganizationName();
        final CharSequence workProfileOrganizationName =
                securityModel.getWorkProfileOrganizationName();
        final boolean isProfileOwnerOfOrganizationOwnedDevice =
                securityModel.isProfileOwnerOfOrganizationOwnedDevice();
        final boolean isParentalControlsEnabled = securityModel.isParentalControlsEnabled();
        final boolean isWorkProfileOn = securityModel.isWorkProfileOn();
        final boolean hasDisclosableWorkProfilePolicy = hasCACertsInWorkProfile
                || vpnNameWorkProfile != null || (hasWorkProfile && isNetworkLoggingEnabled);
        // Update visibility of footer
        boolean isVisible = (isDeviceManaged && !isDemoDevice)
                || hasCACerts
                || vpnName != null
                || isProfileOwnerOfOrganizationOwnedDevice
                || isParentalControlsEnabled
                || (hasDisclosableWorkProfilePolicy && isWorkProfileOn);
        if (!isVisible && !DEBUG_FORCE_VISIBLE) {
            return null;
        }

        // Update the view to be untappable if the device is an organization-owned device with a
        // managed profile and there is either:
        // a) no policy set which requires a privacy disclosure.
        // b) a specific work policy set but the work profile is turned off.
        boolean isClickable = !(isProfileOwnerOfOrganizationOwnedDevice
                && (!hasDisclosableWorkProfilePolicy || !isWorkProfileOn));

        String text = getFooterText(isDeviceManaged, hasWorkProfile,
                hasCACerts, hasCACertsInWorkProfile, isNetworkLoggingEnabled, vpnName,
                vpnNameWorkProfile, organizationName, workProfileOrganizationName,
                isProfileOwnerOfOrganizationOwnedDevice, isParentalControlsEnabled,
                isWorkProfileOn).toString();

        Icon icon;
        ContentDescription contentDescription = null;
        if (isParentalControlsEnabled && securityModel.getDeviceAdminIcon() != null) {
            icon = new Icon.Loaded(securityModel.getDeviceAdminIcon(), contentDescription);
        } else if (vpnName != null || vpnNameWorkProfile != null) {
            if (securityModel.isVpnBranded()) {
                icon = new Icon.Resource(R.drawable.stat_sys_branded_vpn, contentDescription);
            } else {
                icon = new Icon.Resource(R.drawable.stat_sys_vpn_ic, contentDescription);
            }
        } else {
            icon = new Icon.Resource(R.drawable.ic_info_outline, contentDescription);
        }

        return new SecurityButtonConfig(icon, text, isClickable);
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
                    QS_MSG_MANAGEMENT_MONITORING, mManagementMonitoringStringSupplier);
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
                        QS_MSG_MANAGEMENT_MULTIPLE_VPNS, mManagementMultipleVpnStringSupplier);
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
            return mDpm.getResources().getString(QS_MSG_MANAGEMENT, mManagementMessageSupplier);
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
                        QS_MSG_WORK_PROFILE_MONITORING, mWorkProfileMonitoringStringSupplier);
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
                QS_MSG_WORK_PROFILE_NETWORK, mWorkProfileNetworkStringSupplier);
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

    private void createDialog(Context quickSettingsContext, @Nullable Expandable expandable) {
        mShouldUseSettingsButton.set(false);
        mBgHandler.post(() -> {
            String settingsButtonText = getSettingsButton();
            final View dialogView = createDialogView(quickSettingsContext);
            mMainHandler.post(() -> {
                mDialog = new SystemUIDialog(quickSettingsContext, 0);
                mDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
                mDialog.setButton(DialogInterface.BUTTON_POSITIVE, getPositiveButton(), this);
                mDialog.setButton(DialogInterface.BUTTON_NEGATIVE, mShouldUseSettingsButton.get()
                        ? settingsButtonText : getNegativeButton(), this);

                mDialog.setView(dialogView);
                DialogLaunchAnimator.Controller controller =
                        expandable != null ? expandable.dialogLaunchController(new DialogCuj(
                                InteractionJankMonitor.CUJ_SHADE_DIALOG_OPEN, INTERACTION_JANK_TAG))
                                : null;
                if (controller != null) {
                    mDialogLaunchAnimator.show(mDialog, controller);
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
    View createDialogView(Context quickSettingsContext) {
        if (mSecurityController.isParentalControlsEnabled()) {
            return createParentalControlsDialogView(quickSettingsContext);
        }
        return createOrganizationDialogView(quickSettingsContext);
    }

    private View createOrganizationDialogView(Context quickSettingsContext) {
        final boolean isDeviceManaged = mSecurityController.isDeviceManaged();
        final boolean hasWorkProfile = mSecurityController.hasWorkProfile();
        final CharSequence deviceOwnerOrganization =
                mSecurityController.getDeviceOwnerOrganizationName();
        final boolean hasCACerts = mSecurityController.hasCACertInCurrentUser();
        final boolean hasCACertsInWorkProfile = mSecurityController.hasCACertInWorkProfile();
        final boolean isNetworkLoggingEnabled = mSecurityController.isNetworkLoggingEnabled();
        final String vpnName = mSecurityController.getPrimaryVpnName();
        final String vpnNameWorkProfile = mSecurityController.getWorkProfileVpnName();

        View dialogView = LayoutInflater.from(quickSettingsContext)
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

            TextView caCertsSubtitle = (TextView) dialogView.findViewById(R.id.ca_certs_subtitle);
            String caCertsSubtitleMessage = mDpm.getResources().getString(
                    QS_DIALOG_MONITORING_CA_CERT_SUBTITLE, mMonitoringSubtitleCaCertStringSupplier);
            caCertsSubtitle.setText(caCertsSubtitleMessage);

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

            TextView networkLoggingSubtitle = (TextView) dialogView.findViewById(
                    R.id.network_logging_subtitle);
            String networkLoggingSubtitleMessage = mDpm.getResources().getString(
                    QS_DIALOG_MONITORING_NETWORK_SUBTITLE,
                    mMonitoringSubtitleNetworkStringSupplier);
            networkLoggingSubtitle.setText(networkLoggingSubtitleMessage);
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

            TextView vpnSubtitle = (TextView) dialogView.findViewById(R.id.vpn_subtitle);
            String vpnSubtitleMessage = mDpm.getResources().getString(
                    QS_DIALOG_MONITORING_VPN_SUBTITLE, mMonitoringSubtitleVpnStringSupplier);
            vpnSubtitle.setText(vpnSubtitleMessage);
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

    private View createParentalControlsDialogView(Context quickSettingsContext) {
        View dialogView = LayoutInflater.from(quickSettingsContext)
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
        if (showCaCerts) {
            mSectionCountExcludingDeviceMgt++;
        }
        if (showNetworkLogging) {
            mSectionCountExcludingDeviceMgt++;
        }
        if (showVpn) {
            mSectionCountExcludingDeviceMgt++;
        }

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
                QS_DIALOG_VIEW_POLICIES, mViewPoliciesButtonStringSupplier);
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
        return mDpm.getResources().getString(QS_DIALOG_MANAGEMENT, mManagementDialogStringSupplier);
    }

    @Nullable
    protected CharSequence getCaCertsMessage(boolean isDeviceManaged, boolean hasCACerts,
            boolean hasCACertsInWorkProfile) {
        if (!(hasCACerts || hasCACertsInWorkProfile)) return null;
        if (isDeviceManaged) {
            return mDpm.getResources().getString(
                    QS_DIALOG_MANAGEMENT_CA_CERT, mManagementDialogCaCertStringSupplier);
        }
        if (hasCACertsInWorkProfile) {
            return mDpm.getResources().getString(
                    QS_DIALOG_WORK_PROFILE_CA_CERT, mWorkProfileDialogCaCertStringSupplier);
        }
        return mContext.getString(R.string.monitoring_description_ca_certificate);
    }

    @Nullable
    protected CharSequence getNetworkLoggingMessage(boolean isDeviceManaged,
            boolean isNetworkLoggingEnabled) {
        if (!isNetworkLoggingEnabled) return null;
        if (isDeviceManaged) {
            return mDpm.getResources().getString(
                    QS_DIALOG_MANAGEMENT_NETWORK, mManagementDialogNetworkStringSupplier);
        } else {
            return mDpm.getResources().getString(
                    QS_DIALOG_WORK_PROFILE_NETWORK, mWorkProfileDialogNetworkStringSupplier);
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
                    mManagementTitleSupplier);
        }
    }

    private boolean isFinancedDevice() {
        return mSecurityController.isDeviceManaged()
                && mSecurityController.getDeviceOwnerType(
                mSecurityController.getDeviceOwnerComponentOnAnyUser())
                == DEVICE_OWNER_TYPE_FINANCED;
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
