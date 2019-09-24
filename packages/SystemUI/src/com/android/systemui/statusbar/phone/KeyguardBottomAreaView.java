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

import static android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK;
import static android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;

import static com.android.systemui.doze.util.BurnInHelperKt.getBurnInOffset;
import static com.android.systemui.tuner.LockscreenFragment.LOCKSCREEN_LEFT_BUTTON;
import static com.android.systemui.tuner.LockscreenFragment.LOCKSCREEN_LEFT_UNLOCK;
import static com.android.systemui.tuner.LockscreenFragment.LOCKSCREEN_RIGHT_BUTTON;
import static com.android.systemui.tuner.LockscreenFragment.LOCKSCREEN_RIGHT_UNLOCK;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.service.media.CameraPrewarmService;
import android.telecom.TelecomManager;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.ActivityIntentHelper;
import com.android.systemui.Dependency;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.IntentButtonProvider;
import com.android.systemui.plugins.IntentButtonProvider.IntentButton;
import com.android.systemui.plugins.IntentButtonProvider.IntentButton.IconState;
import com.android.systemui.statusbar.KeyguardAffordanceView;
import com.android.systemui.statusbar.policy.AccessibilityController;
import com.android.systemui.statusbar.policy.ExtensionController;
import com.android.systemui.statusbar.policy.ExtensionController.Extension;
import com.android.systemui.statusbar.policy.FlashlightController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.PreviewInflater;
import com.android.systemui.tuner.LockscreenFragment.LockButtonFactory;
import com.android.systemui.tuner.TunerService;

/**
 * Implementation for the bottom area of the Keyguard, including camera/phone affordance and status
 * text.
 */
public class KeyguardBottomAreaView extends FrameLayout implements View.OnClickListener,
        KeyguardStateController.Callback,
        AccessibilityController.AccessibilityStateChangedCallback {

    final static String TAG = "StatusBar/KeyguardBottomAreaView";

    public static final String CAMERA_LAUNCH_SOURCE_AFFORDANCE = "lockscreen_affordance";
    public static final String CAMERA_LAUNCH_SOURCE_WIGGLE = "wiggle_gesture";
    public static final String CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP = "power_double_tap";
    public static final String CAMERA_LAUNCH_SOURCE_LIFT_TRIGGER = "lift_to_launch_ml";

    public static final String EXTRA_CAMERA_LAUNCH_SOURCE
            = "com.android.systemui.camera_launch_source";

    private static final String LEFT_BUTTON_PLUGIN
            = "com.android.systemui.action.PLUGIN_LOCKSCREEN_LEFT_BUTTON";
    private static final String RIGHT_BUTTON_PLUGIN
            = "com.android.systemui.action.PLUGIN_LOCKSCREEN_RIGHT_BUTTON";

    private static final Intent SECURE_CAMERA_INTENT =
            new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE)
                    .addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
    public static final Intent INSECURE_CAMERA_INTENT =
            new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
    private static final Intent PHONE_INTENT = new Intent(Intent.ACTION_DIAL);
    private static final int DOZE_ANIMATION_STAGGER_DELAY = 48;
    private static final int DOZE_ANIMATION_ELEMENT_DURATION = 250;

    private KeyguardAffordanceView mRightAffordanceView;
    private KeyguardAffordanceView mLeftAffordanceView;
    private ViewGroup mIndicationArea;
    private TextView mIndicationText;
    private ViewGroup mPreviewContainer;
    private ViewGroup mOverlayContainer;

    private View mLeftPreview;
    private View mCameraPreview;

    private ActivityStarter mActivityStarter;
    private KeyguardStateController mKeyguardStateController;
    private LockPatternUtils mLockPatternUtils;
    private FlashlightController mFlashlightController;
    private PreviewInflater mPreviewInflater;
    private AccessibilityController mAccessibilityController;
    private StatusBar mStatusBar;
    private KeyguardAffordanceHelper mAffordanceHelper;

    private boolean mUserSetupComplete;
    private boolean mPrewarmBound;
    private Messenger mPrewarmMessenger;
    private final ServiceConnection mPrewarmConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mPrewarmMessenger = new Messenger(service);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mPrewarmMessenger = null;
        }
    };

    private boolean mLeftIsVoiceAssist;
    private AssistManager mAssistManager;
    private Drawable mLeftAssistIcon;

    private IntentButton mRightButton = new DefaultRightButton();
    private Extension<IntentButton> mRightExtension;
    private String mRightButtonStr;
    private IntentButton mLeftButton = new DefaultLeftButton();
    private Extension<IntentButton> mLeftExtension;
    private String mLeftButtonStr;
    private boolean mDozing;
    private int mIndicationBottomMargin;
    private float mDarkAmount;
    private int mBurnInXOffset;
    private int mBurnInYOffset;
    private ActivityIntentHelper mActivityIntentHelper;

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
    }

    private AccessibilityDelegate mAccessibilityDelegate = new AccessibilityDelegate() {
        @Override
        public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            String label = null;
            if (host == mRightAffordanceView) {
                label = getResources().getString(R.string.camera_label);
            } else if (host == mLeftAffordanceView) {
                if (mLeftIsVoiceAssist) {
                    label = getResources().getString(R.string.voice_assist_label);
                } else {
                    label = getResources().getString(R.string.phone_label);
                }
            }
            info.addAction(new AccessibilityAction(ACTION_CLICK, label));
        }

        @Override
        public boolean performAccessibilityAction(View host, int action, Bundle args) {
            if (action == ACTION_CLICK) {
                if (host == mRightAffordanceView) {
                    launchCamera(CAMERA_LAUNCH_SOURCE_AFFORDANCE);
                    return true;
                } else if (host == mLeftAffordanceView) {
                    launchLeftAffordance();
                    return true;
                }
            }
            return super.performAccessibilityAction(host, action, args);
        }
    };

    public void initFrom(KeyguardBottomAreaView oldBottomArea) {
        setStatusBar(oldBottomArea.mStatusBar);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mLockPatternUtils = new LockPatternUtils(mContext);
        mPreviewInflater = new PreviewInflater(mContext, new LockPatternUtils(mContext),
                new ActivityIntentHelper(mContext));
        mPreviewContainer = findViewById(R.id.preview_container);
        mOverlayContainer = findViewById(R.id.overlay_container);
        mRightAffordanceView = findViewById(R.id.camera_button);
        mLeftAffordanceView = findViewById(R.id.left_button);
        mIndicationArea = findViewById(R.id.keyguard_indication_area);
        mIndicationText = findViewById(R.id.keyguard_indication_text);
        mIndicationBottomMargin = getResources().getDimensionPixelSize(
                R.dimen.keyguard_indication_margin_bottom);
        mBurnInYOffset = getResources().getDimensionPixelSize(
                R.dimen.default_burn_in_prevention_offset);
        updateCameraVisibility();
        mKeyguardStateController = Dependency.get(KeyguardStateController.class);
        mKeyguardStateController.addCallback(this);
        setClipChildren(false);
        setClipToPadding(false);
        inflateCameraPreview();
        mRightAffordanceView.setOnClickListener(this);
        mLeftAffordanceView.setOnClickListener(this);
        initAccessibility();
        mActivityStarter = Dependency.get(ActivityStarter.class);
        mFlashlightController = Dependency.get(FlashlightController.class);
        mAccessibilityController = Dependency.get(AccessibilityController.class);
        mAssistManager = Dependency.get(AssistManager.class);
        mActivityIntentHelper = new ActivityIntentHelper(getContext());
        updateLeftAffordance();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mAccessibilityController.addStateChangedCallback(this);
        mRightExtension = Dependency.get(ExtensionController.class).newExtension(IntentButton.class)
                .withPlugin(IntentButtonProvider.class, RIGHT_BUTTON_PLUGIN,
                        p -> p.getIntentButton())
                .withTunerFactory(new LockButtonFactory(mContext, LOCKSCREEN_RIGHT_BUTTON))
                .withDefault(() -> new DefaultRightButton())
                .withCallback(button -> setRightButton(button))
                .build();
        mLeftExtension = Dependency.get(ExtensionController.class).newExtension(IntentButton.class)
                .withPlugin(IntentButtonProvider.class, LEFT_BUTTON_PLUGIN,
                        p -> p.getIntentButton())
                .withTunerFactory(new LockButtonFactory(mContext, LOCKSCREEN_LEFT_BUTTON))
                .withDefault(() -> new DefaultLeftButton())
                .withCallback(button -> setLeftButton(button))
                .build();
        final IntentFilter filter = new IntentFilter();
        filter.addAction(DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED);
        getContext().registerReceiverAsUser(mDevicePolicyReceiver,
                UserHandle.ALL, filter, null, null);
        Dependency.get(KeyguardUpdateMonitor.class).registerCallback(mUpdateMonitorCallback);
        mKeyguardStateController.addCallback(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mKeyguardStateController.removeCallback(this);
        mAccessibilityController.removeStateChangedCallback(this);
        mRightExtension.destroy();
        mLeftExtension.destroy();
        getContext().unregisterReceiver(mDevicePolicyReceiver);
        Dependency.get(KeyguardUpdateMonitor.class).removeCallback(mUpdateMonitorCallback);
    }

    private void initAccessibility() {
        mLeftAffordanceView.setAccessibilityDelegate(mAccessibilityDelegate);
        mRightAffordanceView.setAccessibilityDelegate(mAccessibilityDelegate);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mIndicationBottomMargin = getResources().getDimensionPixelSize(
                R.dimen.keyguard_indication_margin_bottom);
        mBurnInYOffset = getResources().getDimensionPixelSize(
                R.dimen.default_burn_in_prevention_offset);
        MarginLayoutParams mlp = (MarginLayoutParams) mIndicationArea.getLayoutParams();
        if (mlp.bottomMargin != mIndicationBottomMargin) {
            mlp.bottomMargin = mIndicationBottomMargin;
            mIndicationArea.setLayoutParams(mlp);
        }

        // Respect font size setting.
        mIndicationText.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(
                        com.android.internal.R.dimen.text_size_small_material));

        ViewGroup.LayoutParams lp = mRightAffordanceView.getLayoutParams();
        lp.width = getResources().getDimensionPixelSize(R.dimen.keyguard_affordance_width);
        lp.height = getResources().getDimensionPixelSize(R.dimen.keyguard_affordance_height);
        mRightAffordanceView.setLayoutParams(lp);
        updateRightAffordanceIcon();

        lp = mLeftAffordanceView.getLayoutParams();
        lp.width = getResources().getDimensionPixelSize(R.dimen.keyguard_affordance_width);
        lp.height = getResources().getDimensionPixelSize(R.dimen.keyguard_affordance_height);
        mLeftAffordanceView.setLayoutParams(lp);
        updateLeftAffordanceIcon();
    }

    private void updateRightAffordanceIcon() {
        IconState state = mRightButton.getIcon();
        mRightAffordanceView.setVisibility(!mDozing && state.isVisible ? View.VISIBLE : View.GONE);
        if (state.drawable != mRightAffordanceView.getDrawable()
                || state.tint != mRightAffordanceView.shouldTint()) {
            mRightAffordanceView.setImageDrawable(state.drawable, state.tint);
        }
        mRightAffordanceView.setContentDescription(state.contentDescription);
    }

    public void setStatusBar(StatusBar statusBar) {
        mStatusBar = statusBar;
        updateCameraVisibility(); // in case onFinishInflate() was called too early
    }

    public void setAffordanceHelper(KeyguardAffordanceHelper affordanceHelper) {
        mAffordanceHelper = affordanceHelper;
    }

    public void setUserSetupComplete(boolean userSetupComplete) {
        mUserSetupComplete = userSetupComplete;
        updateCameraVisibility();
        updateLeftAffordanceIcon();
    }

    private Intent getCameraIntent() {
        return mRightButton.getIntent();
    }

    /**
     * Resolves the intent to launch the camera application.
     */
    public ResolveInfo resolveCameraIntent() {
        return mContext.getPackageManager().resolveActivityAsUser(getCameraIntent(),
                PackageManager.MATCH_DEFAULT_ONLY,
                KeyguardUpdateMonitor.getCurrentUser());
    }

    private void updateCameraVisibility() {
        if (mRightAffordanceView == null) {
            // Things are not set up yet; reply hazy, ask again later
            return;
        }
        mRightAffordanceView.setVisibility(!mDozing && mRightButton.getIcon().isVisible
                ? View.VISIBLE : View.GONE);
    }

    /**
     * Set an alternate icon for the left assist affordance (replace the mic icon)
     */
    public void setLeftAssistIcon(Drawable drawable) {
        mLeftAssistIcon = drawable;
        updateLeftAffordanceIcon();
    }

    private void updateLeftAffordanceIcon() {
        IconState state = mLeftButton.getIcon();
        mLeftAffordanceView.setVisibility(!mDozing && state.isVisible ? View.VISIBLE : View.GONE);
        if (state.drawable != mLeftAffordanceView.getDrawable()
                || state.tint != mLeftAffordanceView.shouldTint()) {
            mLeftAffordanceView.setImageDrawable(state.drawable, state.tint);
        }
        mLeftAffordanceView.setContentDescription(state.contentDescription);
    }

    public boolean isLeftVoiceAssist() {
        return mLeftIsVoiceAssist;
    }

    private boolean isPhoneVisible() {
        PackageManager pm = mContext.getPackageManager();
        return pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
                && pm.resolveActivity(PHONE_INTENT, 0) != null;
    }

    @Override
    public void onStateChanged(boolean accessibilityEnabled, boolean touchExplorationEnabled) {
        mRightAffordanceView.setClickable(touchExplorationEnabled);
        mLeftAffordanceView.setClickable(touchExplorationEnabled);
        mRightAffordanceView.setFocusable(accessibilityEnabled);
        mLeftAffordanceView.setFocusable(accessibilityEnabled);
    }

    @Override
    public void onClick(View v) {
        if (v == mRightAffordanceView) {
            launchCamera(CAMERA_LAUNCH_SOURCE_AFFORDANCE);
        } else if (v == mLeftAffordanceView) {
            launchLeftAffordance();
        }
    }

    public void bindCameraPrewarmService() {
        Intent intent = getCameraIntent();
        ActivityInfo targetInfo = mActivityIntentHelper.getTargetActivityInfo(intent,
                KeyguardUpdateMonitor.getCurrentUser(), true /* onlyDirectBootAware */);
        if (targetInfo != null && targetInfo.metaData != null) {
            String clazz = targetInfo.metaData.getString(
                    MediaStore.META_DATA_STILL_IMAGE_CAMERA_PREWARM_SERVICE);
            if (clazz != null) {
                Intent serviceIntent = new Intent();
                serviceIntent.setClassName(targetInfo.packageName, clazz);
                serviceIntent.setAction(CameraPrewarmService.ACTION_PREWARM);
                try {
                    if (getContext().bindServiceAsUser(serviceIntent, mPrewarmConnection,
                            Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE,
                            new UserHandle(UserHandle.USER_CURRENT))) {
                        mPrewarmBound = true;
                    }
                } catch (SecurityException e) {
                    Log.w(TAG, "Unable to bind to prewarm service package=" + targetInfo.packageName
                            + " class=" + clazz, e);
                }
            }
        }
    }

    public void unbindCameraPrewarmService(boolean launched) {
        if (mPrewarmBound) {
            if (mPrewarmMessenger != null && launched) {
                try {
                    mPrewarmMessenger.send(Message.obtain(null /* handler */,
                            CameraPrewarmService.MSG_CAMERA_FIRED));
                } catch (RemoteException e) {
                    Log.w(TAG, "Error sending camera fired message", e);
                }
            }
            mContext.unbindService(mPrewarmConnection);
            mPrewarmBound = false;
        }
    }

    public void launchCamera(String source) {
        final Intent intent = getCameraIntent();
        intent.putExtra(EXTRA_CAMERA_LAUNCH_SOURCE, source);
        boolean wouldLaunchResolverActivity = mActivityIntentHelper.wouldLaunchResolverActivity(
                intent, KeyguardUpdateMonitor.getCurrentUser());
        if (intent == SECURE_CAMERA_INTENT && !wouldLaunchResolverActivity) {
            AsyncTask.execute(new Runnable() {
                @Override
                public void run() {
                    int result = ActivityManager.START_CANCELED;

                    // Normally an activity will set it's requested rotation
                    // animation on its window. However when launching an activity
                    // causes the orientation to change this is too late. In these cases
                    // the default animation is used. This doesn't look good for
                    // the camera (as it rotates the camera contents out of sync
                    // with physical reality). So, we ask the WindowManager to
                    // force the crossfade animation if an orientation change
                    // happens to occur during the launch.
                    ActivityOptions o = ActivityOptions.makeBasic();
                    o.setDisallowEnterPictureInPictureWhileLaunching(true);
                    o.setRotationAnimationHint(
                            WindowManager.LayoutParams.ROTATION_ANIMATION_SEAMLESS);
                    try {
                        result = ActivityTaskManager.getService().startActivityAsUser(
                                null, getContext().getBasePackageName(),
                                intent,
                                intent.resolveTypeIfNeeded(getContext().getContentResolver()),
                                null, null, 0, Intent.FLAG_ACTIVITY_NEW_TASK, null, o.toBundle(),
                                UserHandle.CURRENT.getIdentifier());
                    } catch (RemoteException e) {
                        Log.w(TAG, "Unable to start camera activity", e);
                    }
                    final boolean launched = isSuccessfulLaunch(result);
                    post(new Runnable() {
                        @Override
                        public void run() {
                            unbindCameraPrewarmService(launched);
                        }
                    });
                }
            });
        } else {

            // We need to delay starting the activity because ResolverActivity finishes itself if
            // launched behind lockscreen.
            mActivityStarter.startActivity(intent, false /* dismissShade */,
                    new ActivityStarter.Callback() {
                        @Override
                        public void onActivityStarted(int resultCode) {
                            unbindCameraPrewarmService(isSuccessfulLaunch(resultCode));
                        }
                    });
        }
    }

    public void setDarkAmount(float darkAmount) {
        if (darkAmount == mDarkAmount) {
            return;
        }
        mDarkAmount = darkAmount;
        dozeTimeTick();
    }

    private static boolean isSuccessfulLaunch(int result) {
        return result == ActivityManager.START_SUCCESS
                || result == ActivityManager.START_DELIVERED_TO_TOP
                || result == ActivityManager.START_TASK_TO_FRONT;
    }

    public void launchLeftAffordance() {
        if (mLeftIsVoiceAssist) {
            launchVoiceAssist();
        } else {
            launchPhone();
        }
    }

    @VisibleForTesting
    void launchVoiceAssist() {
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                mAssistManager.launchVoiceAssistFromKeyguard();
            }
        };
        if (!mKeyguardStateController.canDismissLockScreen()) {
            AsyncTask.execute(runnable);
        } else {
            boolean dismissShade = !TextUtils.isEmpty(mRightButtonStr)
                    && Dependency.get(TunerService.class).getValue(LOCKSCREEN_RIGHT_UNLOCK, 1) != 0;
            mStatusBar.executeRunnableDismissingKeyguard(runnable, null /* cancelAction */,
                    dismissShade, false /* afterKeyguardGone */, true /* deferred */);
        }
    }

    private boolean canLaunchVoiceAssist() {
        return mAssistManager.canVoiceAssistBeLaunchedFromKeyguard();
    }

    private void launchPhone() {
        final TelecomManager tm = TelecomManager.from(mContext);
        if (tm.isInCall()) {
            AsyncTask.execute(new Runnable() {
                @Override
                public void run() {
                    tm.showInCallScreen(false /* showDialpad */);
                }
            });
        } else {
            boolean dismissShade = !TextUtils.isEmpty(mLeftButtonStr)
                    && Dependency.get(TunerService.class).getValue(LOCKSCREEN_LEFT_UNLOCK, 1) != 0;
            mActivityStarter.startActivity(mLeftButton.getIntent(), dismissShade);
        }
    }


    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (changedView == this && visibility == VISIBLE) {
            updateCameraVisibility();
        }
    }

    public KeyguardAffordanceView getLeftView() {
        return mLeftAffordanceView;
    }

    public KeyguardAffordanceView getRightView() {
        return mRightAffordanceView;
    }

    public View getLeftPreview() {
        return mLeftPreview;
    }

    public View getRightPreview() {
        return mCameraPreview;
    }

    public View getIndicationArea() {
        return mIndicationArea;
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    public void onUnlockedChanged() {
        updateCameraVisibility();
    }

    private void inflateCameraPreview() {
        View previewBefore = mCameraPreview;
        boolean visibleBefore = false;
        if (previewBefore != null) {
            mPreviewContainer.removeView(previewBefore);
            visibleBefore = previewBefore.getVisibility() == View.VISIBLE;
        }
        mCameraPreview = mPreviewInflater.inflatePreview(getCameraIntent());
        if (mCameraPreview != null) {
            mPreviewContainer.addView(mCameraPreview);
            mCameraPreview.setVisibility(visibleBefore ? View.VISIBLE : View.INVISIBLE);
        }
        if (mAffordanceHelper != null) {
            mAffordanceHelper.updatePreviews();
        }
    }

    private void updateLeftPreview() {
        View previewBefore = mLeftPreview;
        if (previewBefore != null) {
            mPreviewContainer.removeView(previewBefore);
        }
        if (mLeftIsVoiceAssist) {
            mLeftPreview = mPreviewInflater.inflatePreviewFromService(
                    mAssistManager.getVoiceInteractorComponentName());
        } else {
            mLeftPreview = mPreviewInflater.inflatePreview(mLeftButton.getIntent());
        }
        if (mLeftPreview != null) {
            mPreviewContainer.addView(mLeftPreview);
            mLeftPreview.setVisibility(View.INVISIBLE);
        }
        if (mAffordanceHelper != null) {
            mAffordanceHelper.updatePreviews();
        }
    }

    public void startFinishDozeAnimation() {
        long delay = 0;
        if (mLeftAffordanceView.getVisibility() == View.VISIBLE) {
            startFinishDozeAnimationElement(mLeftAffordanceView, delay);
            delay += DOZE_ANIMATION_STAGGER_DELAY;
        }
        if (mRightAffordanceView.getVisibility() == View.VISIBLE) {
            startFinishDozeAnimationElement(mRightAffordanceView, delay);
        }
    }

    private void startFinishDozeAnimationElement(View element, long delay) {
        element.setAlpha(0f);
        element.setTranslationY(element.getHeight() / 2);
        element.animate()
                .alpha(1f)
                .translationY(0f)
                .setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN)
                .setStartDelay(delay)
                .setDuration(DOZE_ANIMATION_ELEMENT_DURATION);
    }

    private final BroadcastReceiver mDevicePolicyReceiver = new BroadcastReceiver() {
        @Override
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
                public void onUserUnlocked() {
                    inflateCameraPreview();
                    updateCameraVisibility();
                    updateLeftAffordance();
                }
            };

    public void updateLeftAffordance() {
        updateLeftAffordanceIcon();
        updateLeftPreview();
    }

    private void setRightButton(IntentButton button) {
        mRightButton = button;
        updateRightAffordanceIcon();
        updateCameraVisibility();
        inflateCameraPreview();
    }

    private void setLeftButton(IntentButton button) {
        mLeftButton = button;
        if (!(mLeftButton instanceof DefaultLeftButton)) {
            mLeftIsVoiceAssist = false;
        }
        updateLeftAffordance();
    }

    public void setDozing(boolean dozing, boolean animate) {
        mDozing = dozing;

        updateCameraVisibility();
        updateLeftAffordanceIcon();

        if (dozing) {
            mOverlayContainer.setVisibility(INVISIBLE);
        } else {
            mOverlayContainer.setVisibility(VISIBLE);
            if (animate) {
                startFinishDozeAnimation();
            }
        }
    }

    public void dozeTimeTick() {
        int burnInYOffset = getBurnInOffset(mBurnInYOffset * 2, false /* xAxis */)
                - mBurnInYOffset;
        mIndicationArea.setTranslationY(burnInYOffset * mDarkAmount);
    }

    public void setAntiBurnInOffsetX(int burnInXOffset) {
        if (mBurnInXOffset == burnInXOffset) {
            return;
        }
        mBurnInXOffset = burnInXOffset;
        mIndicationArea.setTranslationX(burnInXOffset);
    }

    /**
     * Sets the alpha of the indication areas and affordances, excluding the lock icon.
     */
    public void setAffordanceAlpha(float alpha) {
        mLeftAffordanceView.setAlpha(alpha);
        mRightAffordanceView.setAlpha(alpha);
        mIndicationArea.setAlpha(alpha);
    }

    private class DefaultLeftButton implements IntentButton {

        private IconState mIconState = new IconState();

        @Override
        public IconState getIcon() {
            mLeftIsVoiceAssist = canLaunchVoiceAssist();
            final boolean showAffordance =
                    getResources().getBoolean(R.bool.config_keyguardShowLeftAffordance);
            if (mLeftIsVoiceAssist) {
                mIconState.isVisible = mUserSetupComplete && showAffordance;
                if (mLeftAssistIcon == null) {
                    mIconState.drawable = mContext.getDrawable(R.drawable.ic_mic_26dp);
                } else {
                    mIconState.drawable = mLeftAssistIcon;
                }
                mIconState.contentDescription = mContext.getString(
                        R.string.accessibility_voice_assist_button);
            } else {
                mIconState.isVisible = mUserSetupComplete && showAffordance && isPhoneVisible();
                mIconState.drawable = mContext.getDrawable(
                        com.android.internal.R.drawable.ic_phone);
                mIconState.contentDescription = mContext.getString(
                        R.string.accessibility_phone_button);
            }
            return mIconState;
        }

        @Override
        public Intent getIntent() {
            return PHONE_INTENT;
        }
    }

    private class DefaultRightButton implements IntentButton {

        private IconState mIconState = new IconState();

        @Override
        public IconState getIcon() {
            boolean isCameraDisabled = (mStatusBar != null) && !mStatusBar.isCameraAllowedByAdmin();
            mIconState.isVisible = !isCameraDisabled
                    && getResources().getBoolean(R.bool.config_keyguardShowCameraAffordance)
                    && mUserSetupComplete
                    && resolveCameraIntent() != null;
            mIconState.drawable = mContext.getDrawable(R.drawable.ic_camera_alt_24dp);
            mIconState.contentDescription =
                    mContext.getString(R.string.accessibility_camera_button);
            return mIconState;
        }

        @Override
        public Intent getIntent() {
            boolean canDismissLs = mKeyguardStateController.canDismissLockScreen();
            boolean secure = mKeyguardStateController.isMethodSecure();
            return (secure && !canDismissLs) ? SECURE_CAMERA_INTENT : INSECURE_CAMERA_INTENT;
        }
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        int bottom = insets.getDisplayCutout() != null
                ? insets.getDisplayCutout().getSafeInsetBottom() : 0;
        if (isPaddingRelative()) {
            setPaddingRelative(getPaddingStart(), getPaddingTop(), getPaddingEnd(), bottom);
        } else {
            setPadding(getPaddingLeft(), getPaddingTop(), getPaddingRight(), bottom);
        }
        return insets;
    }
}
