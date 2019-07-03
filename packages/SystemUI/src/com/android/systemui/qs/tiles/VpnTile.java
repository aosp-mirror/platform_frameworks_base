/*
 * Copyright (C) 2019 The LineageOS Project
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

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager.LayoutParams;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.android.internal.net.VpnConfig;
import com.android.internal.net.VpnProfile;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.SecurityController;
import com.android.systemui.statusbar.policy.SecurityController.SecurityControllerCallback;
import com.android.systemui.statusbar.policy.KeyguardMonitor;

import java.util.List;
import java.util.Set;

import javax.inject.Inject;

/** Quick settings tile: VPN **/
public class VpnTile extends QSTileImpl<BooleanState> {
    private final SecurityController mController;
    private final KeyguardMonitor mKeyguard;
    private final Callback mCallback = new Callback();
    private final ActivityStarter mActivityStarter;

    @Inject
    public VpnTile(QSHost host) {
        super(host);
        mController = Dependency.get(SecurityController.class);
        mKeyguard = Dependency.get(KeyguardMonitor.class);
        mActivityStarter = Dependency.get(ActivityStarter.class);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleSetListening(boolean listening) {
        if (DEBUG) Log.d(TAG, "handleSetListening " + listening);
        if (listening) {
            mController.addCallback(mCallback);
            mKeyguard.addCallback(mCallback);
        } else {
            mController.removeCallback(mCallback);
            mKeyguard.removeCallback(mCallback);
        }
    }

    @Override
    protected void handleUserSwitch(int newUserId) {
        super.handleUserSwitch(newUserId);
        mController.onUserSwitched(newUserId);
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(Settings.ACTION_VPN_SETTINGS);
    }

    @Override
    protected void handleSecondaryClick() {
        handleClick();
    }

    @Override
    protected void handleClick() {
        if (mKeyguard.isSecure() && !mKeyguard.canSkipBouncer()) {
            mActivityStarter.postQSRunnableDismissingKeyguard(() -> {
                showConnectDialogOrDisconnect();
            });
        } else {
            showConnectDialogOrDisconnect();
        }
    }

    private void showConnectDialogOrDisconnect() {
        if (mController.isVpnRestricted()) {
            return;
        }
        if (mController.isVpnEnabled()) {
            mController.disconnectPrimaryVpn();
            return;
        }
        final List<VpnProfile> profiles = mController.getConfiguredLegacyVpns();
        final List<String> vpnApps = mController.getVpnAppPackageNames();
        if (profiles.isEmpty() && vpnApps.isEmpty()) {
            return;
        } else if (profiles.isEmpty() && vpnApps.size() == 1) {
            mController.launchVpnApp(vpnApps.get(0));
            return;
        } else if (vpnApps.isEmpty() && profiles.size() == 1) {
            connectVpnOrAskForCredentials(profiles.get(0));
            return;
        }

        mUiHandler.post(() -> {
            CharSequence[] labels = new CharSequence[profiles.size() + vpnApps.size()];
            int profileCount = profiles.size();
            for (int i = 0; i < profileCount; i++) {
                labels[i] = profiles.get(i).name;
            }
            for (int i = 0; i < vpnApps.size(); i++) {
                try {
                    labels[profileCount + i] = VpnConfig.getVpnLabel(mContext, vpnApps.get(i));
                } catch (PackageManager.NameNotFoundException e) {
                    labels[profileCount + i] = vpnApps.get(i);
                }
            }

            Dialog dialog = new AlertDialog.Builder(mContext)
                    .setTitle(R.string.quick_settings_vpn_connect_dialog_title)
                    .setItems(labels, (dlg, which) -> {
                        if (which < profileCount) {
                            connectVpnOrAskForCredentials(profiles.get(which));
                        } else {
                            mController.launchVpnApp(vpnApps.get(which - profileCount));
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();
            prepareAndShowDialog(dialog);
            mHost.collapsePanels();
        });
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_vpn_label);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.label = mContext.getString(R.string.quick_settings_vpn_label);
        state.value = mController.isVpnEnabled();
        state.secondaryLabel = mController.getPrimaryVpnName();
        state.contentDescription = state.label;
        state.icon = ResourceIcon.get(R.drawable.ic_qs_vpn);
        boolean hasAnyVpn = mController.getConfiguredLegacyVpns().size() > 0
                || mController.getVpnAppPackageNames().size() > 0;
        if (mController.isVpnRestricted() || !hasAnyVpn) {
            state.state = Tile.STATE_UNAVAILABLE;
        } else if (state.value) {
            state.state = Tile.STATE_ACTIVE;
        } else {
            state.state = Tile.STATE_INACTIVE;
        }
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.VPN;
    }

    private void connectVpnOrAskForCredentials(VpnProfile profile) {
        if (profile.saveLogin) {
            mController.connectLegacyVpn(profile);
            return;
        }

        final LayoutInflater inflater = LayoutInflater.from(mContext);
        final View dialogView = inflater.inflate(R.layout.vpn_credentials_dialog, null);
        final EditText userNameEditor = dialogView.findViewById(R.id.username);
        final EditText passwordEditor = dialogView.findViewById(R.id.password);
        final TextView hint = dialogView.findViewById(R.id.hint);

        hint.setText(mContext.getString(R.string.vpn_credentials_hint, profile.name));

        AlertDialog dialog = new AlertDialog.Builder(mContext)
                .setView(dialogView)
                .setPositiveButton(R.string.vpn_credentials_dialog_connect, (dlg, which) -> {
                    profile.username = userNameEditor.getText().toString();
                    profile.password = passwordEditor.getText().toString();
                    mController.connectLegacyVpn(profile);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        prepareAndShowDialog(dialog);
        mHost.collapsePanels();
        mUiHandler.post(() -> {
            TextWatcher watcher = new UsernameAndPasswordWatcher(userNameEditor, passwordEditor,
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE));
            userNameEditor.addTextChangedListener(watcher);
            passwordEditor.addTextChangedListener(watcher);
        });
    }

    private void prepareAndShowDialog(Dialog dialog) {
        dialog.getWindow().setType(LayoutParams.TYPE_KEYGUARD_DIALOG);
        SystemUIDialog.setShowForAllUsers(dialog, true);
        SystemUIDialog.registerDismissListener(dialog);
        SystemUIDialog.setWindowOnTop(dialog);
        mUiHandler.post(() -> dialog.show());
    }

    private static final class UsernameAndPasswordWatcher implements TextWatcher {
        private final Button mOkButton;
        private final EditText mUserNameEditor;
        private final EditText mPasswordEditor;

        public UsernameAndPasswordWatcher(EditText userName, EditText password, Button okButton) {
            mUserNameEditor = userName;
            mPasswordEditor = password;
            mOkButton = okButton;
            updateOkButtonState();
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(Editable s) {
            updateOkButtonState();
        }

        private void updateOkButtonState() {
            mOkButton.setEnabled(mUserNameEditor.getText().length() > 0
                    && mPasswordEditor.getText().length() > 0);
        }
    }

    private final class Callback implements SecurityControllerCallback, KeyguardMonitor.Callback {
        @Override
        public void onStateChanged() {
            refreshState();
        }

        @Override
        public void onKeyguardShowingChanged() {
            refreshState();
        }
    };
}
