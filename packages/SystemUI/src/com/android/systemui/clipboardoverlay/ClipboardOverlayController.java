/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.clipboardoverlay;

import static android.content.Intent.ACTION_CLOSE_SYSTEM_DIALOGS;


import static com.android.internal.config.sysui.SystemUiDeviceConfigFlags.CLIPBOARD_OVERLAY_SHOW_ACTIONS;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_ACTION_SHOWN;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_ACTION_TAPPED;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_DISMISSED_OTHER;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_DISMISS_TAPPED;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_EDIT_TAPPED;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_EXPANDED_FROM_MINIMIZED;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_REMOTE_COPY_TAPPED;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_SHARE_TAPPED;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_SHOWN_EXPANDED;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_SHOWN_MINIMIZED;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_SWIPE_DISMISSED;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_TAP_OUTSIDE;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_TIMED_OUT;
import static com.android.systemui.flags.Flags.CLIPBOARD_IMAGE_TIMEOUT;
import static com.android.systemui.flags.Flags.CLIPBOARD_SHARED_TRANSITIONS;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.input.InputManager;
import android.net.Uri;
import android.os.Looper;
import android.provider.DeviceConfig;
import android.util.Log;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputMonitor;
import android.view.MotionEvent;
import android.view.WindowInsets;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.UiEventLogger;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.broadcast.BroadcastSender;
import com.android.systemui.clipboardoverlay.dagger.ClipboardOverlayModule.OverlayWindowContext;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.res.R;
import com.android.systemui.screenshot.TimeoutHandler;

import java.util.Optional;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * Controls state and UI for the overlay that appears when something is added to the clipboard
 */
public class ClipboardOverlayController implements ClipboardListener.ClipboardOverlay,
        ClipboardOverlayView.ClipboardOverlayCallbacks {
    private static final String TAG = "ClipboardOverlayCtrlr";

    /** Constants for screenshot/copy deconflicting */
    public static final String SCREENSHOT_ACTION = "com.android.systemui.SCREENSHOT";
    public static final String SELF_PERMISSION = "com.android.systemui.permission.SELF";
    public static final String COPY_OVERLAY_ACTION = "com.android.systemui.COPY";

    private static final int CLIPBOARD_DEFAULT_TIMEOUT_MILLIS = 6000;

    private final Context mContext;
    private final ClipboardLogger mClipboardLogger;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final ClipboardOverlayWindow mWindow;
    private final TimeoutHandler mTimeoutHandler;
    private final ClipboardOverlayUtils mClipboardUtils;
    private final FeatureFlags mFeatureFlags;
    private final Executor mBgExecutor;
    private final ClipboardImageLoader mClipboardImageLoader;
    private final ClipboardTransitionExecutor mTransitionExecutor;

    private final ClipboardOverlayView mView;

    private Runnable mOnSessionCompleteListener;
    private Runnable mOnRemoteCopyTapped;
    private Runnable mOnShareTapped;
    private Runnable mOnPreviewTapped;

    private InputMonitor mInputMonitor;
    private InputEventReceiver mInputEventReceiver;

    private BroadcastReceiver mCloseDialogsReceiver;
    private BroadcastReceiver mScreenshotReceiver;

    private Animator mExitAnimator;
    private Animator mEnterAnimator;

    private Runnable mOnUiUpdate;

    private boolean mShowingUi;
    private boolean mIsMinimized;
    private ClipboardModel mClipboardModel;

    private final ClipboardOverlayView.ClipboardOverlayCallbacks mClipboardCallbacks =
            new ClipboardOverlayView.ClipboardOverlayCallbacks() {
                @Override
                public void onInteraction() {
                    if (mOnUiUpdate != null) {
                        mOnUiUpdate.run();
                    }
                }

                @Override
                public void onSwipeDismissInitiated(Animator animator) {
                    mClipboardLogger.logSessionComplete(CLIPBOARD_OVERLAY_SWIPE_DISMISSED);
                    mExitAnimator = animator;
                }

                @Override
                public void onDismissComplete() {
                    hideImmediate();
                }

                @Override
                public void onPreviewTapped() {
                    if (mOnPreviewTapped != null) {
                        mOnPreviewTapped.run();
                    }
                }

                @Override
                public void onShareButtonTapped() {
                    if (mOnShareTapped != null) {
                        mOnShareTapped.run();
                    }
                }

                @Override
                public void onRemoteCopyButtonTapped() {
                    if (mOnRemoteCopyTapped != null) {
                        mOnRemoteCopyTapped.run();
                    }
                }

                @Override
                public void onDismissButtonTapped() {
                    mClipboardLogger.logSessionComplete(CLIPBOARD_OVERLAY_DISMISS_TAPPED);
                    animateOut();
                }

                @Override
                public void onMinimizedViewTapped() {
                    animateFromMinimized();
                }
            };

    @Inject
    public ClipboardOverlayController(@OverlayWindowContext Context context,
            ClipboardOverlayView clipboardOverlayView,
            ClipboardOverlayWindow clipboardOverlayWindow,
            BroadcastDispatcher broadcastDispatcher,
            BroadcastSender broadcastSender,
            TimeoutHandler timeoutHandler,
            FeatureFlags featureFlags,
            ClipboardOverlayUtils clipboardUtils,
            @Background Executor bgExecutor,
            ClipboardImageLoader clipboardImageLoader,
            ClipboardTransitionExecutor transitionExecutor,
            UiEventLogger uiEventLogger) {
        mContext = context;
        mBroadcastDispatcher = broadcastDispatcher;
        mClipboardImageLoader = clipboardImageLoader;
        mTransitionExecutor = transitionExecutor;

        mClipboardLogger = new ClipboardLogger(uiEventLogger);

        mView = clipboardOverlayView;
        mWindow = clipboardOverlayWindow;
        mWindow.init(this::onInsetsChanged, () -> {
            mClipboardLogger.logSessionComplete(CLIPBOARD_OVERLAY_DISMISSED_OTHER);
            hideImmediate();
        });

        mFeatureFlags = featureFlags;
        mTimeoutHandler = timeoutHandler;
        mTimeoutHandler.setDefaultTimeoutMillis(CLIPBOARD_DEFAULT_TIMEOUT_MILLIS);

        mClipboardUtils = clipboardUtils;
        mBgExecutor = bgExecutor;

        if (mFeatureFlags.isEnabled(CLIPBOARD_SHARED_TRANSITIONS)) {
            mView.setCallbacks(this);
        } else {
            mView.setCallbacks(mClipboardCallbacks);
        }

        mWindow.withWindowAttached(() -> {
            mWindow.setContentView(mView);
            mView.setInsets(mWindow.getWindowInsets(),
                    mContext.getResources().getConfiguration().orientation);
        });

        mTimeoutHandler.setOnTimeoutRunnable(() -> {
            if (mFeatureFlags.isEnabled(CLIPBOARD_SHARED_TRANSITIONS)) {
                finish(CLIPBOARD_OVERLAY_TIMED_OUT);
            } else {
                mClipboardLogger.logSessionComplete(CLIPBOARD_OVERLAY_TIMED_OUT);
                animateOut();
            }
        });

        mCloseDialogsReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction())) {
                    if (mFeatureFlags.isEnabled(CLIPBOARD_SHARED_TRANSITIONS)) {
                        finish(CLIPBOARD_OVERLAY_DISMISSED_OTHER);
                    } else {
                        mClipboardLogger.logSessionComplete(CLIPBOARD_OVERLAY_DISMISSED_OTHER);
                        animateOut();
                    }
                }
            }
        };

        mBroadcastDispatcher.registerReceiver(mCloseDialogsReceiver,
                new IntentFilter(ACTION_CLOSE_SYSTEM_DIALOGS));
        mScreenshotReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (SCREENSHOT_ACTION.equals(intent.getAction())) {
                    if (mFeatureFlags.isEnabled(CLIPBOARD_SHARED_TRANSITIONS)) {
                        finish(CLIPBOARD_OVERLAY_DISMISSED_OTHER);
                    } else {
                        mClipboardLogger.logSessionComplete(CLIPBOARD_OVERLAY_DISMISSED_OTHER);
                        animateOut();
                    }
                }
            }
        };

        mBroadcastDispatcher.registerReceiver(mScreenshotReceiver,
                new IntentFilter(SCREENSHOT_ACTION), null, null, Context.RECEIVER_EXPORTED,
                SELF_PERMISSION);
        monitorOutsideTouches();

        Intent copyIntent = new Intent(COPY_OVERLAY_ACTION);
        // Set package name so the system knows it's safe
        copyIntent.setPackage(mContext.getPackageName());
        broadcastSender.sendBroadcast(copyIntent, SELF_PERMISSION);
    }

    @VisibleForTesting
    void onInsetsChanged(WindowInsets insets, int orientation) {
        mView.setInsets(insets, orientation);
        if (shouldShowMinimized(insets) && !mIsMinimized) {
            mIsMinimized = true;
            mView.setMinimized(true);
        }
    }

    @Override // ClipboardListener.ClipboardOverlay
    public void setClipData(ClipData data, String source) {
        ClipboardModel model = ClipboardModel.fromClipData(mContext, mClipboardUtils, data, source);
        boolean wasExiting = (mExitAnimator != null && mExitAnimator.isRunning());
        if (wasExiting) {
            mExitAnimator.cancel();
        }
        boolean shouldAnimate = !model.dataMatches(mClipboardModel) || wasExiting;
        mClipboardModel = model;
        mClipboardLogger.setClipSource(mClipboardModel.getSource());
        if (mFeatureFlags.isEnabled(CLIPBOARD_IMAGE_TIMEOUT)) {
            if (shouldAnimate) {
                reset();
                mClipboardLogger.setClipSource(mClipboardModel.getSource());
                if (shouldShowMinimized(mWindow.getWindowInsets())) {
                    mClipboardLogger.logUnguarded(CLIPBOARD_OVERLAY_SHOWN_MINIMIZED);
                    mIsMinimized = true;
                    mView.setMinimized(true);
                    animateIn();
                } else {
                    mClipboardLogger.logUnguarded(CLIPBOARD_OVERLAY_SHOWN_EXPANDED);
                    setExpandedView(this::animateIn);
                }
                mView.announceForAccessibility(
                        getAccessibilityAnnouncement(mClipboardModel.getType()));
            } else if (!mIsMinimized) {
                setExpandedView(() -> {
                });
            }
        } else {
            if (shouldAnimate) {
                reset();
                mClipboardLogger.setClipSource(mClipboardModel.getSource());
                if (shouldShowMinimized(mWindow.getWindowInsets())) {
                    mClipboardLogger.logUnguarded(CLIPBOARD_OVERLAY_SHOWN_MINIMIZED);
                    mIsMinimized = true;
                    mView.setMinimized(true);
                } else {
                    mClipboardLogger.logUnguarded(CLIPBOARD_OVERLAY_SHOWN_EXPANDED);
                    setExpandedView();
                }
                animateIn();
                mView.announceForAccessibility(
                        getAccessibilityAnnouncement(mClipboardModel.getType()));
            } else if (!mIsMinimized) {
                setExpandedView();
            }
        }
        if (mClipboardModel.isRemote()) {
            mTimeoutHandler.cancelTimeout();
            mOnUiUpdate = null;
        } else {
            mOnUiUpdate = mTimeoutHandler::resetTimeout;
            mOnUiUpdate.run();
        }
    }

    private void setExpandedView(Runnable onViewReady) {
        final ClipboardModel model = mClipboardModel;
        mView.setMinimized(false);
        switch (model.getType()) {
            case TEXT:
                if (model.isRemote() || DeviceConfig.getBoolean(
                        DeviceConfig.NAMESPACE_SYSTEMUI, CLIPBOARD_OVERLAY_SHOW_ACTIONS, false)) {
                    if (model.getTextLinks() != null) {
                        classifyText(model);
                    }
                }
                if (model.isSensitive()) {
                    mView.showTextPreview(mContext.getString(R.string.clipboard_asterisks), true);
                } else {
                    mView.showTextPreview(model.getText().toString(), false);
                }
                mView.setEditAccessibilityAction(true);
                mOnPreviewTapped = this::editText;
                onViewReady.run();
                break;
            case IMAGE:
                mView.setEditAccessibilityAction(true);
                mOnPreviewTapped = () -> editImage(model.getUri());
                if (model.isSensitive()) {
                    mView.showImagePreview(null);
                    onViewReady.run();
                } else {
                    mClipboardImageLoader.loadAsync(model.getUri(), (bitmap) -> mView.post(() -> {
                        if (bitmap == null) {
                            mView.showDefaultTextPreview();
                        } else {
                            mView.showImagePreview(bitmap);
                        }
                        onViewReady.run();
                    }));
                }
                break;
            case URI:
            case OTHER:
                mView.showDefaultTextPreview();
                onViewReady.run();
                break;
        }
        if (!model.isRemote()) {
            maybeShowRemoteCopy(model.getClipData());
        }
        if (model.getType() != ClipboardModel.Type.OTHER) {
            mOnShareTapped = () -> shareContent(model.getClipData());
            mView.showShareChip();
        }
    }

    private void setExpandedView() {
        final ClipboardModel model = mClipboardModel;
        mView.setMinimized(false);
        switch (model.getType()) {
            case TEXT:
                if (model.isRemote() || DeviceConfig.getBoolean(
                        DeviceConfig.NAMESPACE_SYSTEMUI, CLIPBOARD_OVERLAY_SHOW_ACTIONS, false)) {
                    if (model.getTextLinks() != null) {
                        classifyText(model);
                    }
                }
                if (model.isSensitive()) {
                    mView.showTextPreview(mContext.getString(R.string.clipboard_asterisks), true);
                } else {
                    mView.showTextPreview(model.getText().toString(), false);
                }
                mView.setEditAccessibilityAction(true);
                mOnPreviewTapped = this::editText;
                break;
            case IMAGE:
                mBgExecutor.execute(() -> {
                    if (model.isSensitive() || model.loadThumbnail(mContext) != null) {
                        mView.post(() -> {
                            mView.showImagePreview(
                                    model.isSensitive() ? null : model.loadThumbnail(mContext));
                            mView.setEditAccessibilityAction(true);
                        });
                        mOnPreviewTapped = () -> editImage(model.getUri());
                    } else {
                        // image loading failed
                        mView.post(mView::showDefaultTextPreview);
                    }
                });
                break;
            case URI:
            case OTHER:
                mView.showDefaultTextPreview();
                break;
        }
        if (!model.isRemote()) {
            maybeShowRemoteCopy(model.getClipData());
        }
        if (model.getType() != ClipboardModel.Type.OTHER) {
            mOnShareTapped = () -> shareContent(model.getClipData());
            mView.showShareChip();
        }
    }

    private boolean shouldShowMinimized(WindowInsets insets) {
        return insets.getInsets(WindowInsets.Type.ime()).bottom > 0;
    }

    private void animateFromMinimized() {
        if (mEnterAnimator != null && mEnterAnimator.isRunning()) {
            mEnterAnimator.cancel();
        }
        mEnterAnimator = mView.getMinimizedFadeoutAnimation();
        mEnterAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                if (mIsMinimized) {
                    mClipboardLogger.logUnguarded(CLIPBOARD_OVERLAY_EXPANDED_FROM_MINIMIZED);
                    mIsMinimized = false;
                }
                if (mFeatureFlags.isEnabled(CLIPBOARD_IMAGE_TIMEOUT)) {
                    setExpandedView(() -> animateIn());
                } else {
                    setExpandedView();
                    animateIn();
                }
            }
        });
        mEnterAnimator.start();
    }

    private String getAccessibilityAnnouncement(ClipboardModel.Type type) {
        if (type == ClipboardModel.Type.TEXT) {
            return mContext.getString(R.string.clipboard_text_copied);
        } else if (type == ClipboardModel.Type.IMAGE) {
            return mContext.getString(R.string.clipboard_image_copied);
        } else {
            return mContext.getString(R.string.clipboard_content_copied);
        }
    }

    private void classifyText(ClipboardModel model) {
        mBgExecutor.execute(() -> {
            Optional<RemoteAction> remoteAction =
                    mClipboardUtils.getAction(model.getTextLinks(), model.getSource());
            if (model.equals(mClipboardModel)) {
                remoteAction.ifPresent(action -> {
                    mClipboardLogger.logUnguarded(CLIPBOARD_OVERLAY_ACTION_SHOWN);
                    mView.post(() -> mView.setActionChip(action, () -> {
                        if (mFeatureFlags.isEnabled(CLIPBOARD_SHARED_TRANSITIONS)) {
                            finish(CLIPBOARD_OVERLAY_ACTION_TAPPED);
                        } else {
                            mClipboardLogger.logSessionComplete(CLIPBOARD_OVERLAY_ACTION_TAPPED);
                            animateOut();
                        }
                    }));
                });
            }
        });
    }

    private void maybeShowRemoteCopy(ClipData clipData) {
        Intent remoteCopyIntent = IntentCreator.getRemoteCopyIntent(clipData, mContext);
        // Only show remote copy if it's available.
        PackageManager packageManager = mContext.getPackageManager();
        if (packageManager.resolveActivity(
                remoteCopyIntent, PackageManager.ResolveInfoFlags.of(0)) != null) {
            mView.setRemoteCopyVisibility(true);
            mOnRemoteCopyTapped = () -> {
                mClipboardLogger.logSessionComplete(CLIPBOARD_OVERLAY_REMOTE_COPY_TAPPED);
                mContext.startActivity(remoteCopyIntent);
                animateOut();
            };
        } else {
            mView.setRemoteCopyVisibility(false);
        }
    }

    @Override // ClipboardListener.ClipboardOverlay
    public void setOnSessionCompleteListener(Runnable runnable) {
        mOnSessionCompleteListener = runnable;
    }

    private void monitorOutsideTouches() {
        InputManager inputManager = mContext.getSystemService(InputManager.class);
        mInputMonitor = inputManager.monitorGestureInput("clipboard overlay", 0);
        mInputEventReceiver = new InputEventReceiver(
                mInputMonitor.getInputChannel(), Looper.getMainLooper()) {
            @Override
            public void onInputEvent(InputEvent event) {
                if ((!mFeatureFlags.isEnabled(CLIPBOARD_IMAGE_TIMEOUT) || mShowingUi)
                        && event instanceof MotionEvent) {
                    MotionEvent motionEvent = (MotionEvent) event;
                    if (motionEvent.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        if (!mView.isInTouchRegion(
                                (int) motionEvent.getRawX(), (int) motionEvent.getRawY())) {
                            if (mFeatureFlags.isEnabled(CLIPBOARD_SHARED_TRANSITIONS)) {
                                finish(CLIPBOARD_OVERLAY_TAP_OUTSIDE);
                            } else {
                                mClipboardLogger.logSessionComplete(CLIPBOARD_OVERLAY_TAP_OUTSIDE);
                                animateOut();
                            }
                        }
                    }
                }
                finishInputEvent(event, true /* handled */);
            }
        };
    }

    private void editImage(Uri uri) {
        mClipboardLogger.logSessionComplete(CLIPBOARD_OVERLAY_EDIT_TAPPED);
        mContext.startActivity(IntentCreator.getImageEditIntent(uri, mContext));
        animateOut();
    }

    private void editText() {
        mClipboardLogger.logSessionComplete(CLIPBOARD_OVERLAY_EDIT_TAPPED);
        mContext.startActivity(IntentCreator.getTextEditorIntent(mContext));
        animateOut();
    }

    private void shareContent(ClipData clip) {
        mClipboardLogger.logSessionComplete(CLIPBOARD_OVERLAY_SHARE_TAPPED);
        mContext.startActivity(IntentCreator.getShareIntent(clip, mContext));
        animateOut();
    }

    private void animateIn() {
        if (mEnterAnimator != null && mEnterAnimator.isRunning()) {
            return;
        }
        mEnterAnimator = mView.getEnterAnimation();
        mEnterAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                super.onAnimationStart(animation);
                mShowingUi = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                if (mOnUiUpdate != null) {
                    mOnUiUpdate.run();
                }
            }
        });
        mEnterAnimator.start();
    }

    private void finish(ClipboardOverlayEvent event) {
        finish(event, null);
    }

    private void animateOut() {
        if (mExitAnimator != null && mExitAnimator.isRunning()) {
            return;
        }
        mExitAnimator = mView.getExitAnimation();
        mExitAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                super.onAnimationCancel(animation);
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                if (!mCancelled) {
                    hideImmediate();
                }
            }
        });
        mExitAnimator.start();
    }

    private void finish(ClipboardOverlayEvent event, @Nullable Intent intent) {
        if (mExitAnimator != null && mExitAnimator.isRunning()) {
            return;
        }
        mExitAnimator = mView.getExitAnimation();
        mExitAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                super.onAnimationCancel(animation);
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                if (!mCancelled) {
                    mClipboardLogger.logSessionComplete(event);
                    if (intent != null) {
                        mContext.startActivity(intent);
                    }
                    hideImmediate();
                }
            }
        });
        mExitAnimator.start();
    }

    private void finishWithSharedTransition(ClipboardOverlayEvent event, Intent intent) {
        if (mExitAnimator != null && mExitAnimator.isRunning()) {
            return;
        }
        mClipboardLogger.logSessionComplete(event);
        mExitAnimator = mView.getFadeOutAnimation();
        mExitAnimator.start();
        mTransitionExecutor.startSharedTransition(
                mWindow, mView.getPreview(), intent, this::hideImmediate);
    }

    void hideImmediate() {
        // Note this may be called multiple times if multiple dismissal events happen at the same
        // time.
        mTimeoutHandler.cancelTimeout();
        mWindow.remove();
        if (mCloseDialogsReceiver != null) {
            mBroadcastDispatcher.unregisterReceiver(mCloseDialogsReceiver);
            mCloseDialogsReceiver = null;
        }
        if (mScreenshotReceiver != null) {
            mBroadcastDispatcher.unregisterReceiver(mScreenshotReceiver);
            mScreenshotReceiver = null;
        }
        if (mInputEventReceiver != null) {
            mInputEventReceiver.dispose();
            mInputEventReceiver = null;
        }
        if (mInputMonitor != null) {
            mInputMonitor.dispose();
            mInputMonitor = null;
        }
        if (mOnSessionCompleteListener != null) {
            mOnSessionCompleteListener.run();
        }
    }

    private void reset() {
        mOnRemoteCopyTapped = null;
        mOnShareTapped = null;
        mOnPreviewTapped = null;
        mShowingUi = false;
        mView.reset();
        mTimeoutHandler.cancelTimeout();
        mClipboardLogger.reset();
    }

    @Override
    public void onDismissButtonTapped() {
        if (mFeatureFlags.isEnabled(CLIPBOARD_SHARED_TRANSITIONS)) {
            finish(CLIPBOARD_OVERLAY_DISMISS_TAPPED);
        }
    }

    @Override
    public void onRemoteCopyButtonTapped() {
        if (mFeatureFlags.isEnabled(CLIPBOARD_SHARED_TRANSITIONS)) {
            finish(CLIPBOARD_OVERLAY_REMOTE_COPY_TAPPED,
                    IntentCreator.getRemoteCopyIntent(mClipboardModel.getClipData(), mContext));
        }
    }

    @Override
    public void onShareButtonTapped() {
        if (mFeatureFlags.isEnabled(CLIPBOARD_SHARED_TRANSITIONS)) {
            if (mClipboardModel.getType() != ClipboardModel.Type.OTHER) {
                finishWithSharedTransition(CLIPBOARD_OVERLAY_SHARE_TAPPED,
                        IntentCreator.getShareIntent(mClipboardModel.getClipData(), mContext));
            }
        }
    }

    @Override
    public void onPreviewTapped() {
        if (mFeatureFlags.isEnabled(CLIPBOARD_SHARED_TRANSITIONS)) {
            switch (mClipboardModel.getType()) {
                case TEXT:
                    finish(CLIPBOARD_OVERLAY_EDIT_TAPPED,
                            IntentCreator.getTextEditorIntent(mContext));
                    break;
                case IMAGE:
                    finishWithSharedTransition(CLIPBOARD_OVERLAY_EDIT_TAPPED,
                            IntentCreator.getImageEditIntent(mClipboardModel.getUri(), mContext));
                    break;
                default:
                    Log.w(TAG, "Got preview tapped callback for non-editable type "
                            + mClipboardModel.getType());
            }
        }
    }

    @Override
    public void onMinimizedViewTapped() {
        animateFromMinimized();
    }

    @Override
    public void onInteraction() {
        if (!mClipboardModel.isRemote()) {
            mTimeoutHandler.resetTimeout();
        }
    }

    @Override
    public void onSwipeDismissInitiated(Animator animator) {
        if (mExitAnimator != null && mExitAnimator.isRunning()) {
            mExitAnimator.cancel();
        }
        mExitAnimator = animator;
        mClipboardLogger.logSessionComplete(CLIPBOARD_OVERLAY_SWIPE_DISMISSED);
    }

    @Override
    public void onDismissComplete() {
        hideImmediate();
    }

    static class ClipboardLogger {
        private final UiEventLogger mUiEventLogger;
        private String mClipSource;
        private boolean mGuarded = false;

        ClipboardLogger(UiEventLogger uiEventLogger) {
            mUiEventLogger = uiEventLogger;
        }

        void setClipSource(String clipSource) {
            mClipSource = clipSource;
        }

        void logUnguarded(@NonNull UiEventLogger.UiEventEnum event) {
            mUiEventLogger.log(event, 0, mClipSource);
        }

        void logSessionComplete(@NonNull UiEventLogger.UiEventEnum event) {
            if (!mGuarded) {
                mGuarded = true;
                mUiEventLogger.log(event, 0, mClipSource);
            }
        }

        void reset() {
            mGuarded = false;
            mClipSource = null;
        }
    }
}
