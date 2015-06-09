package com.android.systemui.assist;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.PixelFormat;
import android.media.AudioAttributes;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;

import com.android.internal.app.IVoiceInteractionManagerService;
import com.android.internal.app.IVoiceInteractionSessionShowCallback;
import com.android.systemui.R;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.phone.PhoneStatusBar;

/**
 * Class to manage everything related to assist in SystemUI.
 */
public class AssistManager {

    private static final String TAG = "AssistManager";
    private static final String ASSIST_ICON_METADATA_NAME =
            "com.android.systemui.action_assist_icon";

    private static final AudioAttributes VIBRATION_ATTRIBUTES = new AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .build();

    private static final long TIMEOUT_SERVICE = 2500;
    private static final long TIMEOUT_ACTIVITY = 1000;

    private final Context mContext;
    private final WindowManager mWindowManager;
    private AssistOrbContainer mView;
    private final PhoneStatusBar mBar;
    private final IVoiceInteractionManagerService mVoiceInteractionManagerService;

    private ComponentName mAssistComponent;

    private IVoiceInteractionSessionShowCallback mShowCallback =
            new IVoiceInteractionSessionShowCallback.Stub() {

        @Override
        public void onFailed() throws RemoteException {
            mView.post(mHideRunnable);
        }

        @Override
        public void onShown() throws RemoteException {
            mView.post(mHideRunnable);
        }
    };

    private Runnable mHideRunnable = new Runnable() {
        @Override
        public void run() {
            mView.removeCallbacks(this);
            mView.show(false /* show */, true /* animate */);
        }
    };

    private final ContentObserver mAssistSettingsObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            updateAssistInfo();
        }
    };

    public AssistManager(PhoneStatusBar bar, Context context) {
        mContext = context;
        mBar = bar;
        mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        mVoiceInteractionManagerService = IVoiceInteractionManagerService.Stub.asInterface(
                ServiceManager.getService(Context.VOICE_INTERACTION_MANAGER_SERVICE));

        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ASSISTANT), false,
                mAssistSettingsObserver);
        mAssistSettingsObserver.onChange(false);
    }

    public void onConfigurationChanged() {
        boolean visible = false;
        if (mView != null) {
            visible = mView.isShowing();
            mWindowManager.removeView(mView);
        }

        mView = (AssistOrbContainer) LayoutInflater.from(mContext).inflate(
                R.layout.assist_orb, null);
        mView.setVisibility(View.GONE);
        mView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        WindowManager.LayoutParams lp = getLayoutParams();
        mWindowManager.addView(mView, lp);
        mBar.getNavigationBarView().setDelegateView(mView);
        if (visible) {
            mView.show(true /* show */, false /* animate */);
        }
    }

    public void onGestureInvoked(boolean vibrate) {
        if (mAssistComponent == null) {
            return;
        }

        if (vibrate) {
            vibrate();
        }
        final boolean isService = isAssistantService();
        if (isService || !isVoiceSessionRunning()) {
            showOrb();
            mView.postDelayed(mHideRunnable, isService
                    ? TIMEOUT_SERVICE
                    : TIMEOUT_ACTIVITY);
        }
        startAssist();
    }

    public void hideAssist() {
        try {
            mVoiceInteractionManagerService.hideCurrentSession();
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to call hideCurrentSession", e);
        }
    }

    private WindowManager.LayoutParams getLayoutParams() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                mContext.getResources().getDimensionPixelSize(R.dimen.assist_orb_scrim_height),
                WindowManager.LayoutParams.TYPE_VOICE_INTERACTION_STARTING,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        if (ActivityManager.isHighEndGfx()) {
            lp.flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        }
        lp.gravity = Gravity.BOTTOM | Gravity.START;
        lp.setTitle("AssistPreviewPanel");
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        return lp;
    }

    private void showOrb() {
        maybeSwapSearchIcon();
        mView.show(true /* show */, true /* animate */);
    }

    private void startAssist() {
        if (mAssistComponent != null) {
            if (isAssistantService()) {
                startVoiceInteractor();
            } else {
                startAssistActivity();
            }
        }
    }

    private void startAssistActivity() {
        if (!mBar.isDeviceProvisioned()) {
            return;
        }

        // Close Recent Apps if needed
        mBar.animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_SEARCH_PANEL |
                CommandQueue.FLAG_EXCLUDE_RECENTS_PANEL);

        final Intent intent = ((SearchManager) mContext.getSystemService(Context.SEARCH_SERVICE))
                .getAssistIntent(mContext, true, UserHandle.USER_CURRENT);
        if (intent == null) {
            return;
        }
        if (mAssistComponent != null) {
            intent.setComponent(mAssistComponent);
        }

        try {
            final ActivityOptions opts = ActivityOptions.makeCustomAnimation(mContext,
                    R.anim.search_launch_enter, R.anim.search_launch_exit);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            AsyncTask.execute(new Runnable() {
                @Override
                public void run() {
                    mContext.startActivityAsUser(intent, opts.toBundle(),
                            new UserHandle(UserHandle.USER_CURRENT));
                }
            });
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "Activity not found for " + intent.getAction());
        }
    }

    private void startVoiceInteractor() {
        try {
            mVoiceInteractionManagerService.showSessionForActiveService(mShowCallback);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to call showSessionForActiveService", e);
        }
    }

    public void launchVoiceAssistFromKeyguard() {
        try {
            mVoiceInteractionManagerService.launchVoiceAssistFromKeyguard();
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to call launchVoiceAssistFromKeyguard", e);
        }
    }

    private boolean getVoiceInteractorSupportsAssistGesture() {
        try {
            return mVoiceInteractionManagerService != null
                    && mVoiceInteractionManagerService.activeServiceSupportsAssist();
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to call activeServiceSupportsAssistGesture", e);
            return false;
        }
    }

    public boolean canVoiceAssistBeLaunchedFromKeyguard() {
        try {
            return mVoiceInteractionManagerService != null
                    && mVoiceInteractionManagerService.activeServiceSupportsLaunchFromKeyguard();
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to call activeServiceSupportsLaunchFromKeyguard", e);
            return false;
        }
    }

    public ComponentName getVoiceInteractorComponentName() {
        try {
            return mVoiceInteractionManagerService.getActiveServiceComponentName();
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to call getActiveServiceComponentName", e);
            return null;
        }
    }

    private boolean isVoiceSessionRunning() {
        try {
            return mVoiceInteractionManagerService != null
                    && mVoiceInteractionManagerService.isSessionRunning();
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to call isSessionRunning", e);
            return false;
        }
    }

    public void destroy() {
        mWindowManager.removeViewImmediate(mView);
    }

    private void maybeSwapSearchIcon() {
        if (mAssistComponent != null) {
            replaceDrawable(mView.getOrb().getLogo(), mAssistComponent, ASSIST_ICON_METADATA_NAME,
                    isAssistantService());
        } else {
            mView.getOrb().getLogo().setImageDrawable(null);
        }
    }

    public void replaceDrawable(ImageView v, ComponentName component, String name,
            boolean isService) {
        if (component != null) {
            try {
                PackageManager packageManager = mContext.getPackageManager();
                // Look for the search icon specified in the activity meta-data
                Bundle metaData = isService
                        ? packageManager.getServiceInfo(
                                component, PackageManager.GET_META_DATA).metaData
                        : packageManager.getActivityInfo(
                                component, PackageManager.GET_META_DATA).metaData;
                if (metaData != null) {
                    int iconResId = metaData.getInt(name);
                    if (iconResId != 0) {
                        Resources res = packageManager.getResourcesForApplication(
                                component.getPackageName());
                        v.setImageDrawable(res.getDrawable(iconResId));
                        return;
                    }
                }
            } catch (PackageManager.NameNotFoundException e) {
                Log.w(TAG, "Failed to swap drawable; "
                        + component.flattenToShortString() + " not found", e);
            } catch (Resources.NotFoundException nfe) {
                Log.w(TAG, "Failed to swap drawable from "
                        + component.flattenToShortString(), nfe);
            }
        }
        v.setImageDrawable(null);
    }

    private void vibrate() {
        mView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    private boolean isAssistantService() {
        return mAssistComponent == null ?
                false : mAssistComponent.equals(getVoiceInteractorComponentName());
    }

    private void updateAssistInfo() {
        final String setting = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                Settings.Secure.ASSISTANT, UserHandle.USER_CURRENT);
        if (setting != null) {
            mAssistComponent = ComponentName.unflattenFromString(setting);
            return;
        }

        // Fallback to keep backward compatible behavior when there is no user setting.
        if (getVoiceInteractorSupportsAssistGesture()) {
            mAssistComponent = getVoiceInteractorComponentName();
            return;
        }

        Intent intent = ((SearchManager) mContext.getSystemService(Context.SEARCH_SERVICE))
                .getAssistIntent(mContext, false, UserHandle.USER_CURRENT);
        if (intent != null) {
            mAssistComponent = intent.getComponent();
            return;
        }

        mAssistComponent = null;
    }
}
