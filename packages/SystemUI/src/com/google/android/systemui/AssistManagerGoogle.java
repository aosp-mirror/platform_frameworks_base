package com.google.android.systemui;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import com.android.internal.app.AssistUtils;
import com.android.internal.app.IVoiceInteractionSessionListener;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.assist.AssistHandleBehaviorController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import java.util.Objects;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class AssistManagerGoogle extends AssistManager {
    private boolean mCheckAssistantStatus = true;
    private final ContentObserver mContentObserver = new AssistantSettingsObserver();
    private final ContentResolver mContentResolver;
    private final GoogleDefaultUiController mDefaultUiController;
    private boolean mIsGoogleAssistant = false;
    private final OpaEnableDispatcher mOpaEnableDispatcher;
    private AssistManager.UiController mUiController;
    private Handler mUiHandler = new Handler(Looper.getMainLooper());
    private final KeyguardUpdateMonitorCallback mUserSwitchCallback = new KeyguardUpdateMonitorCallback() {
        @Override
        public void onUserSwitching(int i) {
            updateAssistantEnabledState();
            unregisterSettingsObserver();
            registerSettingsObserver();
        }
    };

    @Override
    public boolean shouldShowOrb() {
        return false;
    }

    @Inject
    public AssistManagerGoogle(DeviceProvisionedController deviceProvisionedController, Context context, AssistUtils assistUtils, AssistHandleBehaviorController handleController) {
        super(deviceProvisionedController, context, assistUtils, handleController);
        mContentResolver = context.getContentResolver();
        mOpaEnableDispatcher = new OpaEnableDispatcher(context);
        KeyguardUpdateMonitor.getInstance(mContext).registerCallback(mUserSwitchCallback);
        mDefaultUiController = new GoogleDefaultUiController(context);
        mUiController = mDefaultUiController;
        registerSettingsObserver();
    }

    @Override
    public void registerVoiceInteractionSessionListener() {
        mAssistUtils.registerVoiceInteractionSessionListener(new IVoiceInteractionSessionListener.Stub() {
            @Override
            public void onVoiceSessionHidden() throws RemoteException {
            }

            @Override
            public void onVoiceSessionShown() throws RemoteException {
            }

            @Override
            public void onSetUiHints(Bundle bundle) {
                checkAssistantStatus(bundle);
                mUiController.processBundle(bundle);
            }
        });
    }

    @Override
    public void onInvocationProgress(int i, float f) {
        if (f == 0.0f || f == 1.0f) {
            mCheckAssistantStatus = true;
        }
        if (mCheckAssistantStatus) {
            checkAssistantStatus(null);
        }
        mUiController.onInvocationProgress(i, f);
    }

    @Override
    public void onGestureCompletion(float f) {
        mCheckAssistantStatus = true;
        mUiController.onGestureCompletion(f / mContext.getResources().getDisplayMetrics().density);
    }

    private void checkAssistantStatus(Bundle bundle) {
        ComponentName assistComponentForUser = mAssistUtils.getAssistComponentForUser(-2);
        boolean z = assistComponentForUser != null && OpaUtils.OPA_COMPONENT_NAME.equals(assistComponentForUser.flattenToString());
        if (z != mIsGoogleAssistant) {
            if (z) {
                mUiHandler.post(new Runnable() {
                    @Override
                    public final void run() {
                        mUiController.hide();
                    }
                });
                mUiController = mDefaultUiController;
                ((GoogleDefaultUiController) mUiController).setGoogleAssistant(true);
            } else {
                mUiHandler.post(new Runnable() {
                    @Override
                    public final void run() {
                        mUiController.hide();
                    }
                });
                mUiController = mDefaultUiController;
                ((GoogleDefaultUiController) mUiController).setGoogleAssistant(false);
            }
            mIsGoogleAssistant = z;
        }
        mCheckAssistantStatus = false;
    }

    private void updateAssistantEnabledState() {
        mOpaEnableDispatcher.refreshOpa();
    }

    private void registerSettingsObserver() {
        mContentResolver.registerContentObserver(Settings.Secure.getUriFor("assistant"), false, mContentObserver, KeyguardUpdateMonitor.getCurrentUser());
    }

    private void unregisterSettingsObserver() {
        mContentResolver.unregisterContentObserver(mContentObserver);
    }

    private class AssistantSettingsObserver extends ContentObserver {
        public AssistantSettingsObserver() {
            super(new Handler());
        }

        @Override
        public void onChange(boolean z, Uri uri) {
            updateAssistantEnabledState();
        }
    }

    public void dispatchOpaEnabledState() {
        updateAssistantEnabledState();
    }
}
