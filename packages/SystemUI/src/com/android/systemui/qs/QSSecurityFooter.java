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

import static com.android.systemui.qs.dagger.QSFragmentModule.QS_SECURITY_FOOTER_VIEW;

import android.app.admin.DevicePolicyEventLogger;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.android.internal.util.FrameworkStatsLog;
import com.android.systemui.FontSizeUtils;
import com.android.systemui.R;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.common.shared.model.Icon;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.qs.dagger.QSScope;
import com.android.systemui.qs.footer.domain.model.SecurityButtonConfig;
import com.android.systemui.security.data.model.SecurityModel;
import com.android.systemui.statusbar.policy.SecurityController;
import com.android.systemui.util.ViewController;

import javax.inject.Inject;
import javax.inject.Named;

/** ViewController for the footer actions. */
// TODO(b/242040009): Remove this class.
@QSScope
public class QSSecurityFooter extends ViewController<View>
        implements OnClickListener, VisibilityChangedDispatcher {
    protected static final String TAG = "QSSecurityFooter";

    private final TextView mFooterText;
    private final ImageView mPrimaryFooterIcon;
    private Context mContext;
    private final Callback mCallback = new Callback();
    private final SecurityController mSecurityController;
    private final Handler mMainHandler;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final QSSecurityFooterUtils mQSSecurityFooterUtils;

    protected H mHandler;

    private boolean mIsVisible;
    private boolean mIsClickable;
    @Nullable
    private CharSequence mFooterTextContent = null;
    private Icon mFooterIcon;

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
            @Main Handler mainHandler, SecurityController securityController,
            @Background Looper bgLooper, BroadcastDispatcher broadcastDispatcher,
            QSSecurityFooterUtils qSSecurityFooterUtils) {
        super(rootView);
        mFooterText = mView.findViewById(R.id.footer_text);
        mPrimaryFooterIcon = mView.findViewById(R.id.primary_footer_icon);
        mFooterIcon = new Icon.Resource(
                R.drawable.ic_info_outline, /* contentDescription= */ null);
        mContext = rootView.getContext();
        mSecurityController = securityController;
        mMainHandler = mainHandler;
        mHandler = new H(bgLooper);
        mBroadcastDispatcher = broadcastDispatcher;
        mQSSecurityFooterUtils = qSSecurityFooterUtils;
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

    // TODO(b/242040009): Remove this.
    public void showDeviceMonitoringDialog() {
        mQSSecurityFooterUtils.showDeviceMonitoringDialog(mContext, mView);
    }

    public void refreshState() {
        mHandler.sendEmptyMessage(H.REFRESH_STATE);
    }

    private void handleRefreshState() {
        SecurityModel securityModel = SecurityModel.create(mSecurityController);
        SecurityButtonConfig buttonConfig = mQSSecurityFooterUtils.getButtonConfig(securityModel);

        if (buttonConfig == null) {
            mIsVisible = false;
        } else {
            mIsVisible = true;
            mIsClickable = buttonConfig.isClickable();
            mFooterTextContent = buttonConfig.getText();
            mFooterIcon = buttonConfig.getIcon();
        }

        // Update the UI.
        mMainHandler.post(mUpdatePrimaryIcon);
        mMainHandler.post(mUpdateDisplayState);
    }

    private final Runnable mUpdatePrimaryIcon = new Runnable() {
        @Override
        public void run() {
            if (mFooterIcon instanceof Icon.Loaded) {
                mPrimaryFooterIcon.setImageDrawable(((Icon.Loaded) mFooterIcon).getDrawable());
            } else if (mFooterIcon instanceof Icon.Resource) {
                mPrimaryFooterIcon.setImageResource(((Icon.Resource) mFooterIcon).getRes());
            }
        }
    };

    private final Runnable mUpdateDisplayState = new Runnable() {
        @Override
        public void run() {
            if (mFooterTextContent != null) {
                mFooterText.setText(mFooterTextContent);
            }
            mView.setVisibility(mIsVisible ? View.VISIBLE : View.GONE);
            if (mVisibilityChangedListener != null) {
                mVisibilityChangedListener.onVisibilityChanged(mView.getVisibility());
            }

            if (mIsVisible && mIsClickable) {
                mView.setClickable(true);
                mView.findViewById(R.id.footer_icon).setVisibility(View.VISIBLE);
            } else {
                mView.setClickable(false);
                mView.findViewById(R.id.footer_icon).setVisibility(View.GONE);
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
}
