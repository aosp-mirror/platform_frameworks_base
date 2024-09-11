/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.screenshot;

import static android.content.res.Configuration.ORIENTATION_PORTRAIT;

import static com.android.systemui.screenshot.LogConfig.DEBUG_ANIM;
import static com.android.systemui.screenshot.LogConfig.DEBUG_CALLBACK;
import static com.android.systemui.screenshot.LogConfig.DEBUG_INPUT;
import static com.android.systemui.screenshot.LogConfig.DEBUG_UI;
import static com.android.systemui.screenshot.LogConfig.DEBUG_WINDOW;
import static com.android.systemui.screenshot.LogConfig.logTag;
import static com.android.systemui.screenshot.ScreenshotEvent.SCREENSHOT_DISMISSED_OTHER;
import static com.android.systemui.screenshot.ScreenshotEvent.SCREENSHOT_INTERACTION_TIMEOUT;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.ScrollCaptureResponse;
import android.view.ViewRootImpl;
import android.view.WindowManager;
import android.widget.Toast;
import android.window.WindowContext;

import com.android.internal.logging.UiEventLogger;
import com.android.settingslib.applications.InterestingConfigChanges;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.broadcast.BroadcastSender;
import com.android.systemui.clipboardoverlay.ClipboardOverlayController;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.res.R;
import com.android.systemui.screenshot.TakeScreenshotService.RequestCallback;
import com.android.systemui.screenshot.scroll.ScrollCaptureExecutor;
import com.android.systemui.util.Assert;

import com.google.common.util.concurrent.ListenableFuture;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;

import kotlin.Unit;

import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import javax.inject.Provider;

/**
 * Controls the state and flow for screenshots.
 */
public class ScreenshotController implements InteractiveScreenshotHandler {
    private static final String TAG = logTag(ScreenshotController.class);

    // From WizardManagerHelper.java
    private static final String SETTINGS_SECURE_USER_SETUP_COMPLETE = "user_setup_complete";

    static final int SCREENSHOT_CORNER_DEFAULT_TIMEOUT_MILLIS = 6000;

    private final WindowContext mContext;
    private final FeatureFlags mFlags;
    private final ScreenshotShelfViewProxy mViewProxy;
    private final ScreenshotNotificationsController mNotificationsController;
    private final ScreenshotSmartActions mScreenshotSmartActions;
    private final UiEventLogger mUiEventLogger;
    private final ImageExporter mImageExporter;
    private final ImageCapture mImageCapture;
    private final Executor mMainExecutor;
    private final ExecutorService mBgExecutor;
    private final BroadcastSender mBroadcastSender;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final ScreenshotActionsController mActionsController;

    @Nullable
    private final ScreenshotSoundController mScreenshotSoundController;
    private final ScreenshotWindow mWindow;
    private final Display mDisplay;
    private final ScrollCaptureExecutor mScrollCaptureExecutor;
    private final ScreenshotNotificationSmartActionsProvider
            mScreenshotNotificationSmartActionsProvider;
    private final TimeoutHandler mScreenshotHandler;
    private final UserManager mUserManager;
    private final AssistContentRequester mAssistContentRequester;
    private final ActionExecutor mActionExecutor;


    private final MessageContainerController mMessageContainerController;
    private final AnnouncementResolver mAnnouncementResolver;
    private Bitmap mScreenBitmap;
    private boolean mScreenshotTakenInPortrait;
    private Animator mScreenshotAnimation;
    private RequestCallback mCurrentRequestCallback;
    private String mPackageName = "";
    private final BroadcastReceiver mCopyBroadcastReceiver;

    /** Tracks config changes that require re-creating UI */
    private final InterestingConfigChanges mConfigChanges = new InterestingConfigChanges(
            ActivityInfo.CONFIG_ORIENTATION
                    | ActivityInfo.CONFIG_LAYOUT_DIRECTION
                    | ActivityInfo.CONFIG_LOCALE
                    | ActivityInfo.CONFIG_UI_MODE
                    | ActivityInfo.CONFIG_SCREEN_LAYOUT
                    | ActivityInfo.CONFIG_ASSETS_PATHS);


    @AssistedInject
    ScreenshotController(
            Context context,
            ScreenshotWindow.Factory screenshotWindowFactory,
            FeatureFlags flags,
            ScreenshotShelfViewProxy.Factory viewProxyFactory,
            ScreenshotSmartActions screenshotSmartActions,
            ScreenshotNotificationsController.Factory screenshotNotificationsControllerFactory,
            UiEventLogger uiEventLogger,
            ImageExporter imageExporter,
            ImageCapture imageCapture,
            @Main Executor mainExecutor,
            ScrollCaptureExecutor scrollCaptureExecutor,
            TimeoutHandler timeoutHandler,
            BroadcastSender broadcastSender,
            BroadcastDispatcher broadcastDispatcher,
            ScreenshotNotificationSmartActionsProvider screenshotNotificationSmartActionsProvider,
            ScreenshotActionsController.Factory screenshotActionsControllerFactory,
            ActionExecutor.Factory actionExecutorFactory,
            UserManager userManager,
            AssistContentRequester assistContentRequester,
            MessageContainerController messageContainerController,
            Provider<ScreenshotSoundController> screenshotSoundController,
            AnnouncementResolver announcementResolver,
            @Assisted Display display
    ) {
        mScreenshotSmartActions = screenshotSmartActions;
        mNotificationsController = screenshotNotificationsControllerFactory.create(
                display.getDisplayId());
        mUiEventLogger = uiEventLogger;
        mImageExporter = imageExporter;
        mImageCapture = imageCapture;
        mMainExecutor = mainExecutor;
        mScrollCaptureExecutor = scrollCaptureExecutor;
        mScreenshotNotificationSmartActionsProvider = screenshotNotificationSmartActionsProvider;
        mBgExecutor = Executors.newSingleThreadExecutor();
        mBroadcastSender = broadcastSender;
        mBroadcastDispatcher = broadcastDispatcher;

        mScreenshotHandler = timeoutHandler;
        mScreenshotHandler.setDefaultTimeoutMillis(SCREENSHOT_CORNER_DEFAULT_TIMEOUT_MILLIS);

        mDisplay = display;
        mWindow = screenshotWindowFactory.create(mDisplay);
        mContext = mWindow.getContext();
        mFlags = flags;
        mUserManager = userManager;
        mMessageContainerController = messageContainerController;
        mAssistContentRequester = assistContentRequester;
        mAnnouncementResolver = announcementResolver;

        mViewProxy = viewProxyFactory.getProxy(mContext, mDisplay.getDisplayId());

        mScreenshotHandler.setOnTimeoutRunnable(() -> {
            if (DEBUG_UI) {
                Log.d(TAG, "Corner timeout hit");
            }
            mViewProxy.requestDismissal(SCREENSHOT_INTERACTION_TIMEOUT);
        });

        mConfigChanges.applyNewConfig(context.getResources());
        reloadAssets();

        mActionExecutor = actionExecutorFactory.create(mWindow.getWindow(), mViewProxy,
                () -> {
                    finishDismiss();
                    return Unit.INSTANCE;
                });
        mActionsController = screenshotActionsControllerFactory.getController(mActionExecutor);


        // Sound is only reproduced from the controller of the default display.
        if (mDisplay.getDisplayId() == Display.DEFAULT_DISPLAY) {
            mScreenshotSoundController = screenshotSoundController.get();
        } else {
            mScreenshotSoundController = null;
        }

        mCopyBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ClipboardOverlayController.COPY_OVERLAY_ACTION.equals(intent.getAction())) {
                    mViewProxy.requestDismissal(SCREENSHOT_DISMISSED_OTHER);
                }
            }
        };
        mBroadcastDispatcher.registerReceiver(mCopyBroadcastReceiver, new IntentFilter(
                        ClipboardOverlayController.COPY_OVERLAY_ACTION), null, null,
                Context.RECEIVER_NOT_EXPORTED, ClipboardOverlayController.SELF_PERMISSION);
    }

    @Override
    public void handleScreenshot(ScreenshotData screenshot, Consumer<Uri> finisher,
            RequestCallback requestCallback) {
        Assert.isMainThread();

        mCurrentRequestCallback = requestCallback;
        if (screenshot.getType() == WindowManager.TAKE_SCREENSHOT_FULLSCREEN
                && screenshot.getBitmap() == null) {
            Rect bounds = getFullScreenRect();
            screenshot.setBitmap(mImageCapture.captureDisplay(mDisplay.getDisplayId(), bounds));
            screenshot.setScreenBounds(bounds);
        }

        if (screenshot.getBitmap() == null) {
            Log.e(TAG, "handleScreenshot: Screenshot bitmap was null");
            mNotificationsController.notifyScreenshotError(
                    R.string.screenshot_failed_to_capture_text);
            if (mCurrentRequestCallback != null) {
                mCurrentRequestCallback.reportError();
            }
            return;
        }

        mScreenBitmap = screenshot.getBitmap();
        String oldPackageName = mPackageName;
        mPackageName = screenshot.getPackageNameString();

        if (!isUserSetupComplete(Process.myUserHandle())) {
            Log.w(TAG, "User setup not complete, displaying toast only");
            // User setup isn't complete, so we don't want to show any UI beyond a toast, as editing
            // and sharing shouldn't be exposed to the user.
            saveScreenshotAndToast(screenshot, finisher);
            return;
        }

        mBroadcastSender.sendBroadcast(new Intent(ClipboardOverlayController.SCREENSHOT_ACTION),
                ClipboardOverlayController.SELF_PERMISSION);

        mScreenshotTakenInPortrait =
                mContext.getResources().getConfiguration().orientation == ORIENTATION_PORTRAIT;

        // Optimizations
        mScreenBitmap.setHasAlpha(false);
        mScreenBitmap.prepareToDraw();

        prepareViewForNewScreenshot(screenshot, oldPackageName);

        final UUID requestId;
        requestId = mActionsController.setCurrentScreenshot(screenshot);
        saveScreenshotInBackground(screenshot, requestId, finisher, result -> {
            if (result.uri != null) {
                ScreenshotSavedResult savedScreenshot = new ScreenshotSavedResult(
                        result.uri, screenshot.getUserOrDefault(), result.timestamp);
                mActionsController.setCompletedScreenshot(requestId, savedScreenshot);
            }
        });

        if (screenshot.getTaskId() >= 0) {
            mAssistContentRequester.requestAssistContent(
                    screenshot.getTaskId(),
                    assistContent ->
                            mActionsController.onAssistContent(requestId, assistContent));
        } else {
            mActionsController.onAssistContent(requestId, null);
        }

        // The window is focusable by default
        mWindow.setFocusable(true);
        mViewProxy.requestFocus();

        enqueueScrollCaptureRequest(requestId, screenshot.getUserHandle());

        mWindow.attachWindow();

        boolean showFlash;
        if (screenshot.getType() == WindowManager.TAKE_SCREENSHOT_PROVIDED_IMAGE) {
            if (screenshot.getScreenBounds() != null
                    && aspectRatiosMatch(screenshot.getBitmap(), screenshot.getInsets(),
                    screenshot.getScreenBounds())) {
                showFlash = false;
            } else {
                showFlash = true;
                screenshot.setInsets(Insets.NONE);
                screenshot.setScreenBounds(new Rect(0, 0, screenshot.getBitmap().getWidth(),
                        screenshot.getBitmap().getHeight()));
            }
        } else {
            showFlash = true;
        }

        mViewProxy.prepareEntranceAnimation(
                () -> startAnimation(screenshot.getScreenBounds(), showFlash,
                        () -> mMessageContainerController.onScreenshotTaken(screenshot)));

        mViewProxy.setScreenshot(screenshot);

    }

    void prepareViewForNewScreenshot(@NonNull ScreenshotData screenshot, String oldPackageName) {
        mWindow.whenWindowAttached(() -> {
            mAnnouncementResolver.getScreenshotAnnouncement(
                    screenshot.getUserHandle().getIdentifier(),
                    announcement -> {
                        mViewProxy.announceForAccessibility(announcement);
                    });
        });

        mViewProxy.reset();

        if (mViewProxy.isAttachedToWindow()) {
            // if we didn't already dismiss for another reason
            if (!mViewProxy.isDismissing()) {
                mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_REENTERED, 0,
                        oldPackageName);
            }
            if (DEBUG_WINDOW) {
                Log.d(TAG, "saveScreenshot: screenshotView is already attached, resetting. "
                        + "(dismissing=" + mViewProxy.isDismissing() + ")");
            }
        }

        mViewProxy.setPackageName(mPackageName);
    }

    /**
     * Requests the view to dismiss the current screenshot (may be ignored, if screenshot is already
     * being dismissed)
     */
    @Override
    public void requestDismissal(ScreenshotEvent event) {
        mViewProxy.requestDismissal(event);
    }

    @Override
    public boolean isPendingSharedTransition() {
        return mActionExecutor.isPendingSharedTransition();
    }

    // Any cleanup needed when the service is being destroyed.
    @Override
    public void onDestroy() {
        removeWindow();
        releaseMediaPlayer();
        releaseContext();
        mBgExecutor.shutdown();
    }

    /**
     * Release the constructed window context.
     */
    private void releaseContext() {
        mBroadcastDispatcher.unregisterReceiver(mCopyBroadcastReceiver);
        mContext.release();
    }

    private void releaseMediaPlayer() {
        if (mScreenshotSoundController == null) return;
        mScreenshotSoundController.releaseScreenshotSoundAsync();
    }

    /**
     * Update resources on configuration change. Reinflate for theme/color changes.
     */
    private void reloadAssets() {
        if (DEBUG_UI) {
            Log.d(TAG, "reloadAssets()");
        }

        mMessageContainerController.setView(mViewProxy.getView());
        mViewProxy.setCallbacks(new ScreenshotShelfViewProxy.ScreenshotViewCallback() {
            @Override
            public void onUserInteraction() {
                if (DEBUG_INPUT) {
                    Log.d(TAG, "onUserInteraction");
                }
                mScreenshotHandler.resetTimeout();
            }

            @Override
            public void onDismiss() {
                finishDismiss();
            }

            @Override
            public void onTouchOutside() {
                // TODO(159460485): Remove this when focus is handled properly in the system
                mWindow.setFocusable(false);
            }
        });

        if (DEBUG_WINDOW) {
            Log.d(TAG, "setContentView: " + mViewProxy.getView());
        }
        mWindow.setContentView(mViewProxy.getView());
    }

    private void enqueueScrollCaptureRequest(UUID requestId, UserHandle owner) {
        // Wait until this window is attached to request because it is
        // the reference used to locate the target window (below).
        mWindow.whenWindowAttached(() -> {
            requestScrollCapture(requestId, owner);
            mWindow.setActivityConfigCallback(
                    new ViewRootImpl.ActivityConfigCallback() {
                        @Override
                        public void onConfigurationChanged(Configuration overrideConfig,
                                int newDisplayId) {
                            if (mConfigChanges.applyNewConfig(mContext.getResources())) {
                                // Hide the scroll chip until we know it's available in this
                                // orientation
                                mActionsController.onScrollChipInvalidated();
                                // Delay scroll capture eval a bit to allow the underlying activity
                                // to set up in the new orientation.
                                mScreenshotHandler.postDelayed(
                                        () -> requestScrollCapture(requestId, owner), 150);
                                mViewProxy.updateInsets(mWindow.getWindowInsets());
                                // Screenshot animation calculations won't be valid anymore,
                                // so just end
                                if (mScreenshotAnimation != null
                                        && mScreenshotAnimation.isRunning()) {
                                    mScreenshotAnimation.end();
                                }
                            }
                        }
                    });
        });
    }

    private void requestScrollCapture(UUID requestId, UserHandle owner) {
        mScrollCaptureExecutor.requestScrollCapture(
                mDisplay.getDisplayId(),
                mWindow.getWindowToken(),
                (response) -> {
                    mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_LONG_SCREENSHOT_IMPRESSION,
                            0, response.getPackageName());
                    mActionsController.onScrollChipReady(requestId,
                            () -> onScrollButtonClicked(owner, response));
                    return Unit.INSTANCE;
                }
        );
    }

    private void onScrollButtonClicked(UserHandle owner, ScrollCaptureResponse response) {
        if (DEBUG_INPUT) {
            Log.d(TAG, "scroll chip tapped");
        }
        mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_LONG_SCREENSHOT_REQUESTED, 0,
                response.getPackageName());
        Bitmap newScreenshot = mImageCapture.captureDisplay(mDisplay.getDisplayId(),
                getFullScreenRect());
        if (newScreenshot == null) {
            Log.e(TAG, "Failed to capture current screenshot for scroll transition!");
            return;
        }
        // delay starting scroll capture to make sure scrim is up before the app moves
        mViewProxy.prepareScrollingTransition(response, mScreenBitmap, newScreenshot,
                mScreenshotTakenInPortrait, () -> executeBatchScrollCapture(response, owner));
    }

    private void executeBatchScrollCapture(ScrollCaptureResponse response, UserHandle owner) {
        mScrollCaptureExecutor.executeBatchScrollCapture(response,
                () -> {
                    final Intent intent = ActionIntentCreator.INSTANCE.createLongScreenshotIntent(
                            owner, mContext);
                    mContext.startActivity(intent);
                },
                mViewProxy::restoreNonScrollingUi,
                mViewProxy::startLongScreenshotTransition);
    }

    @Override
    public void removeWindow() {
        mWindow.removeWindow();
        mViewProxy.stopInputListening();
    }

    private void playCameraSoundIfNeeded() {
        if (mScreenshotSoundController == null) return;
        // the controller is not-null only on the default display controller
        mScreenshotSoundController.playScreenshotSoundAsync();
    }

    /**
     * Save the bitmap but don't show the normal screenshot UI.. just a toast (or notification on
     * failure).
     */
    private void saveScreenshotAndToast(ScreenshotData screenshot, Consumer<Uri> finisher) {
        // Play the shutter sound to notify that we've taken a screenshot
        playCameraSoundIfNeeded();

        saveScreenshotInBackground(screenshot, UUID.randomUUID(), finisher, result -> {
            if (result.uri != null) {
                mScreenshotHandler.post(() -> Toast.makeText(mContext,
                        R.string.screenshot_saved_title, Toast.LENGTH_SHORT).show());
            }
        });
    }

    /**
     * Starts the animation after taking the screenshot
     */
    private void startAnimation(Rect screenRect, boolean showFlash, Runnable onAnimationComplete) {
        if (mScreenshotAnimation != null && mScreenshotAnimation.isRunning()) {
            mScreenshotAnimation.cancel();
        }

        mScreenshotAnimation =
                mViewProxy.createScreenshotDropInAnimation(screenRect, showFlash);
        if (onAnimationComplete != null) {
            mScreenshotAnimation.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    super.onAnimationEnd(animation);
                    onAnimationComplete.run();
                }
            });
        }

        // Play the shutter sound to notify that we've taken a screenshot
        playCameraSoundIfNeeded();

        if (DEBUG_ANIM) {
            Log.d(TAG, "starting post-screenshot animation");
        }
        mScreenshotAnimation.start();
    }

    /** Reset screenshot view and then call onCompleteRunnable */
    private void finishDismiss() {
        Log.d(TAG, "finishDismiss");
        mActionsController.endScreenshotSession();
        mScrollCaptureExecutor.close();
        if (mCurrentRequestCallback != null) {
            mCurrentRequestCallback.onFinish();
            mCurrentRequestCallback = null;
        }
        mViewProxy.reset();
        removeWindow();
        mScreenshotHandler.cancelTimeout();
    }

    private void saveScreenshotInBackground(ScreenshotData screenshot, UUID requestId,
            Consumer<Uri> finisher, Consumer<ImageExporter.Result> onResult) {
        ListenableFuture<ImageExporter.Result> future = mImageExporter.export(mBgExecutor,
                requestId, screenshot.getBitmap(), screenshot.getUserOrDefault(),
                mDisplay.getDisplayId());
        future.addListener(() -> {
            try {
                ImageExporter.Result result = future.get();
                Log.d(TAG, "Saved screenshot: " + result);
                logScreenshotResultStatus(result.uri, screenshot.getUserHandle());
                onResult.accept(result);
                if (DEBUG_CALLBACK) {
                    Log.d(TAG, "finished background processing, Calling (Consumer<Uri>) "
                            + "finisher.accept(\"" + result.uri + "\"");
                }
                finisher.accept(result.uri);
            } catch (Exception e) {
                Log.d(TAG, "Failed to store screenshot", e);
                if (DEBUG_CALLBACK) {
                    Log.d(TAG, "Calling (Consumer<Uri>) finisher.accept(null)");
                }
                finisher.accept(null);
            }
        }, mMainExecutor);
    }

    /**
     * Logs success/failure of the screenshot saving task, and shows an error if it failed.
     */
    private void logScreenshotResultStatus(Uri uri, UserHandle owner) {
        if (uri == null) {
            mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_NOT_SAVED, 0, mPackageName);
            mNotificationsController.notifyScreenshotError(
                    R.string.screenshot_failed_to_save_text);
        } else {
            mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_SAVED, 0, mPackageName);
            if (mUserManager.isManagedProfile(owner.getIdentifier())) {
                mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_SAVED_TO_WORK_PROFILE, 0,
                        mPackageName);
            }
        }
    }

    private boolean isUserSetupComplete(UserHandle owner) {
        return Settings.Secure.getInt(mContext.createContextAsUser(owner, 0)
                .getContentResolver(), SETTINGS_SECURE_USER_SETUP_COMPLETE, 0) == 1;
    }

    private Rect getFullScreenRect() {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        mDisplay.getRealMetrics(displayMetrics);
        return new Rect(0, 0, displayMetrics.widthPixels, displayMetrics.heightPixels);
    }

    /** Does the aspect ratio of the bitmap with insets removed match the bounds. */
    private static boolean aspectRatiosMatch(Bitmap bitmap, Insets bitmapInsets,
            Rect screenBounds) {
        int insettedWidth = bitmap.getWidth() - bitmapInsets.left - bitmapInsets.right;
        int insettedHeight = bitmap.getHeight() - bitmapInsets.top - bitmapInsets.bottom;

        if (insettedHeight == 0 || insettedWidth == 0 || bitmap.getWidth() == 0
                || bitmap.getHeight() == 0) {
            if (DEBUG_UI) {
                Log.e(TAG, "Provided bitmap and insets create degenerate region: "
                        + bitmap.getWidth() + "x" + bitmap.getHeight() + " " + bitmapInsets);
            }
            return false;
        }

        float insettedBitmapAspect = ((float) insettedWidth) / insettedHeight;
        float boundsAspect = ((float) screenBounds.width()) / screenBounds.height();

        boolean matchWithinTolerance = Math.abs(insettedBitmapAspect - boundsAspect) < 0.1f;
        if (DEBUG_UI) {
            Log.d(TAG, "aspectRatiosMatch: don't match bitmap: " + insettedBitmapAspect
                    + ", bounds: " + boundsAspect);
        }
        return matchWithinTolerance;
    }

    /** Injectable factory to create screenshot controller instances for a specific display. */
    @AssistedFactory
    public interface Factory extends InteractiveScreenshotHandler.Factory {
        /**
         * Creates an instance of the controller for that specific display.
         *
         * @param display                 display to capture
         */
        ScreenshotController create(Display display);
    }
}
