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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import android.app.ActivityManagerNative;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.telecomm.TelecommManager;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.R;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.KeyguardAffordanceView;
import com.android.systemui.statusbar.KeyguardIndicationController;
import com.android.systemui.statusbar.policy.AccessibilityController;
import com.android.systemui.statusbar.policy.FlashlightController;
import com.android.systemui.statusbar.policy.PreviewInflater;

import static android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK;
import static android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;

/**
 * Implementation for the bottom area of the Keyguard, including camera/phone affordance and status
 * text.
 */
public class KeyguardBottomAreaView extends FrameLayout implements View.OnClickListener,
        UnlockMethodCache.OnUnlockMethodChangedListener,
        AccessibilityController.AccessibilityStateChangedCallback, View.OnLongClickListener {

    final static String TAG = "PhoneStatusBar/KeyguardBottomAreaView";

    private static final Intent SECURE_CAMERA_INTENT =
            new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE)
                    .addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
    private static final Intent INSECURE_CAMERA_INTENT =
            new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
    private static final Intent PHONE_INTENT = new Intent(Intent.ACTION_DIAL);

    private KeyguardAffordanceView mCameraImageView;
    private KeyguardAffordanceView mPhoneImageView;
    private KeyguardAffordanceView mLockIcon;
    private TextView mIndicationText;
    private ViewGroup mPreviewContainer;

    private View mPhonePreview;
    private View mCameraPreview;

    private ActivityStarter mActivityStarter;
    private UnlockMethodCache mUnlockMethodCache;
    private LockPatternUtils mLockPatternUtils;
    private FlashlightController mFlashlightController;
    private PreviewInflater mPreviewInflater;
    private KeyguardIndicationController mIndicationController;
    private AccessibilityController mAccessibilityController;
    private PhoneStatusBar mPhoneStatusBar;

    private final TrustDrawable mTrustDrawable;

    public KeyguardBottomAreaView(Context context) {
        this(context, null);
    }

    public KeyguardBottomAreaView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyguardBottomAreaView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public KeyguardBottomAreaView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mTrustDrawable = new TrustDrawable(mContext);
    }

    private AccessibilityDelegate mAccessibilityDelegate = new AccessibilityDelegate() {
        @Override
        public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            String label = null;
            if (host == mLockIcon) {
                label = getResources().getString(R.string.unlock_label);
            } else if (host == mCameraImageView) {
                label = getResources().getString(R.string.camera_label);
            } else if (host == mPhoneImageView) {
                label = getResources().getString(R.string.phone_label);
            }
            info.addAction(new AccessibilityAction(ACTION_CLICK, label));
        }

        @Override
        public boolean performAccessibilityAction(View host, int action, Bundle args) {
            if (action == ACTION_CLICK) {
                if (host == mLockIcon) {
                    mPhoneStatusBar.animateCollapsePanels(
                            CommandQueue.FLAG_EXCLUDE_NONE, true /* force */);
                    return true;
                } else if (host == mCameraImageView) {
                    launchCamera();
                    return true;
                } else if (host == mPhoneImageView) {
                    launchPhone();
                    return true;
                }
            }
            return super.performAccessibilityAction(host, action, args);
        }
    };

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mLockPatternUtils = new LockPatternUtils(mContext);
        mPreviewContainer = (ViewGroup) findViewById(R.id.preview_container);
        mCameraImageView = (KeyguardAffordanceView) findViewById(R.id.camera_button);
        mPhoneImageView = (KeyguardAffordanceView) findViewById(R.id.phone_button);
        mLockIcon = (KeyguardAffordanceView) findViewById(R.id.lock_icon);
        mIndicationText = (TextView) findViewById(R.id.keyguard_indication_text);
        watchForCameraPolicyChanges();
        updateCameraVisibility();
        updatePhoneVisibility();
        mUnlockMethodCache = UnlockMethodCache.getInstance(getContext());
        mUnlockMethodCache.addListener(this);
        updateLockIcon();
        setClipChildren(false);
        setClipToPadding(false);
        mPreviewInflater = new PreviewInflater(mContext, new LockPatternUtils(mContext));
        inflatePreviews();
        mLockIcon.setOnClickListener(this);
        mLockIcon.setBackground(mTrustDrawable);
        mLockIcon.setOnLongClickListener(this);
        mCameraImageView.setOnClickListener(this);
        mPhoneImageView.setOnClickListener(this);
        initAccessibility();
    }

    private void initAccessibility() {
        mLockIcon.setAccessibilityDelegate(mAccessibilityDelegate);
        mPhoneImageView.setAccessibilityDelegate(mAccessibilityDelegate);
        mCameraImageView.setAccessibilityDelegate(mAccessibilityDelegate);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int indicationBottomMargin = getResources().getDimensionPixelSize(
                R.dimen.keyguard_indication_margin_bottom);
        MarginLayoutParams mlp = (MarginLayoutParams) mIndicationText.getLayoutParams();
        if (mlp.bottomMargin != indicationBottomMargin) {
            mlp.bottomMargin = indicationBottomMargin;
            mIndicationText.setLayoutParams(mlp);
        }

        // Respect font size setting.
        mIndicationText.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(
                        com.android.internal.R.dimen.text_size_small_material));
    }

    public void setActivityStarter(ActivityStarter activityStarter) {
        mActivityStarter = activityStarter;
    }

    public void setFlashlightController(FlashlightController flashlightController) {
        mFlashlightController = flashlightController;
    }

    public void setAccessibilityController(AccessibilityController accessibilityController) {
        mAccessibilityController = accessibilityController;
        accessibilityController.addStateChangedCallback(this);
    }

    public void setPhoneStatusBar(PhoneStatusBar phoneStatusBar) {
        mPhoneStatusBar = phoneStatusBar;
    }

    private Intent getCameraIntent() {
        KeyguardUpdateMonitor updateMonitor = KeyguardUpdateMonitor.getInstance(mContext);
        boolean currentUserHasTrust = updateMonitor.getUserHasTrust(
                mLockPatternUtils.getCurrentUser());
        return mLockPatternUtils.isSecure() && !currentUserHasTrust
                ? SECURE_CAMERA_INTENT : INSECURE_CAMERA_INTENT;
    }

    private void updateCameraVisibility() {
        ResolveInfo resolved = mContext.getPackageManager().resolveActivityAsUser(getCameraIntent(),
                PackageManager.MATCH_DEFAULT_ONLY,
                mLockPatternUtils.getCurrentUser());
        boolean visible = !isCameraDisabledByDpm() && resolved != null
                && getResources().getBoolean(R.bool.config_keyguardShowCameraAffordance);
        mCameraImageView.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void updatePhoneVisibility() {
        boolean visible = isPhoneVisible();
        mPhoneImageView.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private boolean isPhoneVisible() {
        PackageManager pm = mContext.getPackageManager();
        return pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
                && pm.resolveActivity(PHONE_INTENT, 0) != null;
    }

    private boolean isCameraDisabledByDpm() {
        final DevicePolicyManager dpm =
                (DevicePolicyManager) getContext().getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm != null) {
            try {
                final int userId = ActivityManagerNative.getDefault().getCurrentUser().id;
                final int disabledFlags = dpm.getKeyguardDisabledFeatures(null, userId);
                final  boolean disabledBecauseKeyguardSecure =
                        (disabledFlags & DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA) != 0
                                && KeyguardTouchDelegate.getInstance(getContext()).isSecure();
                return dpm.getCameraDisabled(null) || disabledBecauseKeyguardSecure;
            } catch (RemoteException e) {
                Log.e(TAG, "Can't get userId", e);
            }
        }
        return false;
    }

    private void watchForCameraPolicyChanges() {
        final IntentFilter filter = new IntentFilter();
        filter.addAction(DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED);
        getContext().registerReceiverAsUser(mDevicePolicyReceiver,
                UserHandle.ALL, filter, null, null);
        KeyguardUpdateMonitor.getInstance(mContext).registerCallback(mUpdateMonitorCallback);
    }

    @Override
    public void onStateChanged(boolean accessibilityEnabled, boolean touchExplorationEnabled) {
        mCameraImageView.setClickable(touchExplorationEnabled);
        mPhoneImageView.setClickable(touchExplorationEnabled);
        mCameraImageView.setFocusable(accessibilityEnabled);
        mPhoneImageView.setFocusable(accessibilityEnabled);
        updateLockIconClickability();
    }

    private void updateLockIconClickability() {
        if (mAccessibilityController == null) {
            return;
        }
        mLockIcon.setClickable(mUnlockMethodCache.isTrustManaged()
                || mAccessibilityController.isTouchExplorationEnabled());
        mLockIcon.setLongClickable(mAccessibilityController.isTouchExplorationEnabled()
                && mUnlockMethodCache.isTrustManaged());
        mLockIcon.setFocusable(mAccessibilityController.isAccessibilityEnabled());
    }

    @Override
    public void onClick(View v) {
        if (v == mCameraImageView) {
            launchCamera();
        } else if (v == mPhoneImageView) {
            launchPhone();
        } if (v == mLockIcon) {
            if (!mAccessibilityController.isAccessibilityEnabled()) {
                handleTrustCircleClick();
            } else {
                mPhoneStatusBar.animateCollapsePanels(
                        CommandQueue.FLAG_EXCLUDE_NONE, true /* force */);
            }
        }
    }

    @Override
    public boolean onLongClick(View v) {
        handleTrustCircleClick();
        return true;
    }

    private void handleTrustCircleClick() {
        mIndicationController.showTransientIndication(
                R.string.keyguard_indication_trust_disabled);
        mLockPatternUtils.requireCredentialEntry(mLockPatternUtils.getCurrentUser());
    }

    public void launchCamera() {
        mFlashlightController.killFlashlight();
        Intent intent = getCameraIntent();
        boolean wouldLaunchResolverActivity = mPreviewInflater.wouldLaunchResolverActivity(intent);
        if (intent == SECURE_CAMERA_INTENT && !wouldLaunchResolverActivity) {
            mContext.startActivityAsUser(intent, UserHandle.CURRENT);
        } else {

            // We need to delay starting the activity because ResolverActivity finishes itself if
            // launched behind lockscreen.
            mActivityStarter.startActivity(intent, false /* dismissShade */,
                    wouldLaunchResolverActivity /* afterKeyguardGone */);
        }
    }

    public void launchPhone() {
        final TelecommManager tm = TelecommManager.from(mContext);
        if (tm.isInCall()) {
            AsyncTask.execute(new Runnable() {
                @Override
                public void run() {
                    tm.showInCallScreen(false /* showDialpad */);
                }
            });
        } else {
            mActivityStarter.startActivity(PHONE_INTENT, false /* dismissShade */,
                    mPreviewInflater.wouldLaunchResolverActivity(PHONE_INTENT));
        }
    }


    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (isShown()) {
            mTrustDrawable.start();
        } else {
            mTrustDrawable.stop();
        }
        if (changedView == this && visibility == VISIBLE) {
            updateLockIcon();
            updateCameraVisibility();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mTrustDrawable.stop();
    }

    private void updateLockIcon() {
        boolean visible = isShown() && KeyguardUpdateMonitor.getInstance(mContext).isScreenOn();
        if (visible) {
            mTrustDrawable.start();
        } else {
            mTrustDrawable.stop();
        }
        if (!visible) {
            return;
        }
        // TODO: Real icon for facelock.
        int iconRes = mUnlockMethodCache.isFaceUnlockRunning() ? R.drawable.ic_account_circle
                : mUnlockMethodCache.isMethodInsecure() ? R.drawable.ic_lock_open_24dp
                : R.drawable.ic_lock_24dp;
        mLockIcon.setImageResource(iconRes);
        boolean trustManaged = mUnlockMethodCache.isTrustManaged();
        mTrustDrawable.setTrustManaged(trustManaged);

        updateLockIconClickability();
        updateLockIconContentDescription(mUnlockMethodCache.isFaceUnlockRunning(),
                mUnlockMethodCache.isMethodInsecure(), trustManaged);
    }

    private void updateLockIconContentDescription(boolean faceUnlockRunning, boolean insecure,
            boolean trustManaged) {
        mLockIcon.setContentDescription(getResources().getString(
                faceUnlockRunning ? R.string.accessibility_unlock_button_face_unlock_running
                : insecure && !trustManaged ? R.string.accessibility_unlock_button_not_secured
                : insecure ? R.string.accessibility_unlock_button_not_secured_trust_managed
                : !trustManaged ? R.string.accessibility_unlock_button_secured
                : R.string.accessibility_unlock_button_secured_trust_managed));
    }

    public KeyguardAffordanceView getPhoneView() {
        return mPhoneImageView;
    }

    public KeyguardAffordanceView getCameraView() {
        return mCameraImageView;
    }

    public View getPhonePreview() {
        return mPhonePreview;
    }

    public View getCameraPreview() {
        return mCameraPreview;
    }

    public KeyguardAffordanceView getLockIcon() {
        return mLockIcon;
    }

    public View getIndicationView() {
        return mIndicationText;
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    public void onMethodSecureChanged(boolean methodSecure) {
        updateLockIcon();
        updateCameraVisibility();
    }

    private void inflatePreviews() {
        mPhonePreview = mPreviewInflater.inflatePreview(PHONE_INTENT);
        mCameraPreview = mPreviewInflater.inflatePreview(getCameraIntent());
        if (mPhonePreview != null) {
            mPreviewContainer.addView(mPhonePreview);
            mPhonePreview.setVisibility(View.INVISIBLE);
        }
        if (mCameraPreview != null) {
            mPreviewContainer.addView(mCameraPreview);
            mCameraPreview.setVisibility(View.INVISIBLE);
        }
    }

    private final BroadcastReceiver mDevicePolicyReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            post(new Runnable() {
                @Override
                public void run() {
                    updateCameraVisibility();
                }
            });
        }
    };

    private final KeyguardUpdateMonitorCallback mUpdateMonitorCallback =
            new KeyguardUpdateMonitorCallback() {
        @Override
        public void onUserSwitchComplete(int userId) {
            updateCameraVisibility();
        }

        @Override
        public void onScreenTurnedOn() {
            updateLockIcon();
        }

        @Override
        public void onScreenTurnedOff(int why) {
            updateLockIcon();
        }
    };

    public void setKeyguardIndicationController(
            KeyguardIndicationController keyguardIndicationController) {
        mIndicationController = keyguardIndicationController;
    }
}
