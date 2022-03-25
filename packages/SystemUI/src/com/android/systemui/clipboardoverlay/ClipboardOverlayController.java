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
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.LayoutParams.TYPE_SCREENSHOT;

import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_ACTION_TAPPED;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_EDIT_TAPPED;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_REMOTE_COPY_TAPPED;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_SWIPE_DISMISSED;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_TIMED_OUT;

import static java.util.Objects.requireNonNull;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.MainThread;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputMonitor;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.view.textclassifier.TextClassification;
import android.view.textclassifier.TextClassificationManager;
import android.view.textclassifier.TextClassifier;
import android.view.textclassifier.TextLinks;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.logging.UiEventLogger;
import com.android.internal.policy.PhoneWindow;
import com.android.systemui.R;
import com.android.systemui.screenshot.DraggableConstraintLayout;
import com.android.systemui.screenshot.FloatingWindowUtil;
import com.android.systemui.screenshot.OverlayActionChip;
import com.android.systemui.screenshot.TimeoutHandler;

import java.io.IOException;
import java.util.ArrayList;

/**
 * Controls state and UI for the overlay that appears when something is added to the clipboard
 */
public class ClipboardOverlayController {
    private static final String TAG = "ClipboardOverlayCtrlr";
    private static final String REMOTE_COPY_ACTION = "android.intent.action.REMOTE_COPY";

    /** Constants for screenshot/copy deconflicting */
    public static final String SCREENSHOT_ACTION = "com.android.systemui.SCREENSHOT";
    public static final String SELF_PERMISSION = "com.android.systemui.permission.SELF";
    public static final String COPY_OVERLAY_ACTION = "com.android.systemui.COPY";

    private static final int CLIPBOARD_DEFAULT_TIMEOUT_MILLIS = 6000;
    private static final int SWIPE_PADDING_DP = 12; // extra padding around views to allow swipe

    private final Context mContext;
    private final UiEventLogger mUiEventLogger;
    private final DisplayManager mDisplayManager;
    private final DisplayMetrics mDisplayMetrics;
    private final WindowManager mWindowManager;
    private final WindowManager.LayoutParams mWindowLayoutParams;
    private final PhoneWindow mWindow;
    private final TimeoutHandler mTimeoutHandler;
    private final AccessibilityManager mAccessibilityManager;
    private final TextClassifier mTextClassifier;

    private final FrameLayout mContainer;
    private final DraggableConstraintLayout mView;
    private final ImageView mImagePreview;
    private final TextView mTextPreview;
    private final View mPreviewBorder;
    private final OverlayActionChip mEditChip;
    private final OverlayActionChip mRemoteCopyChip;
    private final View mActionContainerBackground;
    private final View mDismissButton;
    private final LinearLayout mActionContainer;
    private final ArrayList<OverlayActionChip> mActionChips = new ArrayList<>();

    private Runnable mOnSessionCompleteListener;


    private InputMonitor mInputMonitor;
    private InputEventReceiver mInputEventReceiver;

    private BroadcastReceiver mCloseDialogsReceiver;
    private BroadcastReceiver mScreenshotReceiver;

    private boolean mBlockAttach = false;

    public ClipboardOverlayController(
            Context context, TimeoutHandler timeoutHandler, UiEventLogger uiEventLogger) {
        mDisplayManager = requireNonNull(context.getSystemService(DisplayManager.class));
        final Context displayContext = context.createDisplayContext(getDefaultDisplay());
        mContext = displayContext.createWindowContext(TYPE_SCREENSHOT, null);

        mUiEventLogger = uiEventLogger;

        mAccessibilityManager = AccessibilityManager.getInstance(mContext);
        mTextClassifier = requireNonNull(context.getSystemService(TextClassificationManager.class))
                .getTextClassifier();

        mWindowManager = mContext.getSystemService(WindowManager.class);

        mDisplayMetrics = new DisplayMetrics();
        mContext.getDisplay().getRealMetrics(mDisplayMetrics);

        mTimeoutHandler = timeoutHandler;
        mTimeoutHandler.setDefaultTimeoutMillis(CLIPBOARD_DEFAULT_TIMEOUT_MILLIS);

        // Setup the window that we are going to use
        mWindowLayoutParams = FloatingWindowUtil.getFloatingWindowParams();
        mWindowLayoutParams.setTitle("ClipboardOverlay");
        mWindow = FloatingWindowUtil.getFloatingWindow(mContext);
        mWindow.setWindowManager(mWindowManager, null, null);

        setWindowFocusable(false);

        mContainer = (FrameLayout)
                LayoutInflater.from(mContext).inflate(R.layout.clipboard_overlay, null);
        mView = requireNonNull(mContainer.findViewById(R.id.clipboard_ui));
        mActionContainerBackground =
                requireNonNull(mView.findViewById(R.id.actions_container_background));
        mActionContainer = requireNonNull(mView.findViewById(R.id.actions));
        mImagePreview = requireNonNull(mView.findViewById(R.id.image_preview));
        mTextPreview = requireNonNull(mView.findViewById(R.id.text_preview));
        mPreviewBorder = requireNonNull(mView.findViewById(R.id.preview_border));
        mEditChip = requireNonNull(mView.findViewById(R.id.edit_chip));
        mRemoteCopyChip = requireNonNull(mView.findViewById(R.id.remote_copy_chip));
        mDismissButton = requireNonNull(mView.findViewById(R.id.dismiss_button));

        mView.setCallbacks(new DraggableConstraintLayout.SwipeDismissCallbacks() {
            @Override
            public void onInteraction() {
                mTimeoutHandler.resetTimeout();
            }

            @Override
            public void onSwipeDismissInitiated(Animator animator) {
                mUiEventLogger.log(CLIPBOARD_OVERLAY_SWIPE_DISMISSED);
                animator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationStart(Animator animation) {
                        super.onAnimationStart(animation);
                        mContainer.animate().alpha(0).start();
                    }
                });
            }

            @Override
            public void onDismissComplete() {
                hideImmediate();
            }
        });

        mTextPreview.getViewTreeObserver().addOnPreDrawListener(() -> {
            int availableHeight = mTextPreview.getHeight()
                    - (mTextPreview.getPaddingTop() + mTextPreview.getPaddingBottom());
            mTextPreview.setMaxLines(availableHeight / mTextPreview.getLineHeight());
            return true;
        });

        mDismissButton.setOnClickListener(view -> animateOut());

        mEditChip.setIcon(Icon.createWithResource(mContext, R.drawable.ic_screenshot_edit), true);
        mRemoteCopyChip.setIcon(
                Icon.createWithResource(mContext, R.drawable.ic_baseline_devices_24), true);

        attachWindow();
        withWindowAttached(() -> {
            mWindow.setContentView(mContainer);
            updateInsets(mWindowManager.getCurrentWindowMetrics().getWindowInsets());
            mView.requestLayout();
            mView.post(this::animateIn);
        });

        mTimeoutHandler.setOnTimeoutRunnable(() -> {
            mUiEventLogger.log(CLIPBOARD_OVERLAY_TIMED_OUT);
            animateOut();
        });

        mCloseDialogsReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction())) {
                    animateOut();
                }
            }
        };
        mContext.registerReceiver(mCloseDialogsReceiver,
                new IntentFilter(ACTION_CLOSE_SYSTEM_DIALOGS));

        mScreenshotReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (SCREENSHOT_ACTION.equals(intent.getAction())) {
                    animateOut();
                }
            }
        };
        mContext.registerReceiver(mScreenshotReceiver, new IntentFilter(SCREENSHOT_ACTION),
                SELF_PERMISSION, null);
        monitorOutsideTouches();

        Intent copyIntent = new Intent(COPY_OVERLAY_ACTION);
        // Set package name so the system knows it's safe
        copyIntent.setPackage(mContext.getPackageName());
        mContext.sendBroadcast(copyIntent, SELF_PERMISSION);
    }

    void setClipData(ClipData clipData, String clipSource) {
        reset();
        if (clipData == null || clipData.getItemCount() == 0) {
            showTextPreview(mContext.getResources().getString(
                    R.string.clipboard_overlay_text_copied));
        } else if (!TextUtils.isEmpty(clipData.getItemAt(0).getText())) {
            ClipData.Item item = clipData.getItemAt(0);
            if (item.getTextLinks() != null) {
                AsyncTask.execute(() -> classifyText(clipData.getItemAt(0), clipSource));
            }
            showEditableText(item.getText());
        } else if (clipData.getItemAt(0).getUri() != null) {
            // How to handle non-image URIs?
            showEditableImage(clipData.getItemAt(0).getUri());
        } else {
            showTextPreview(
                    mContext.getResources().getString(R.string.clipboard_overlay_text_copied));
        }
        Intent remoteCopyIntent = getRemoteCopyIntent(clipData);
        // Only show remote copy if it's available.
        PackageManager packageManager = mContext.getPackageManager();
        if (remoteCopyIntent != null && packageManager.resolveActivity(
                remoteCopyIntent, PackageManager.ResolveInfoFlags.of(0)) != null) {
            mRemoteCopyChip.setOnClickListener((v) -> {
                mUiEventLogger.log(CLIPBOARD_OVERLAY_REMOTE_COPY_TAPPED);
                mContext.startActivity(remoteCopyIntent);
                animateOut();
            });
            mRemoteCopyChip.setAlpha(1f);
        } else {
            mRemoteCopyChip.setVisibility(View.GONE);
        }
        mTimeoutHandler.resetTimeout();
    }

    void setOnSessionCompleteListener(Runnable runnable) {
        mOnSessionCompleteListener = runnable;
    }

    private void classifyText(ClipData.Item item, String source) {
        ArrayList<RemoteAction> actions = new ArrayList<>();
        for (TextLinks.TextLink link : item.getTextLinks().getLinks()) {
            TextClassification classification = mTextClassifier.classifyText(
                    item.getText(), link.getStart(), link.getEnd(), null);
            actions.addAll(classification.getActions());
        }
        mView.post(() -> {
            resetActionChips();
            for (RemoteAction action : actions) {
                Intent targetIntent = action.getActionIntent().getIntent();
                ComponentName component = targetIntent.getComponent();
                if (component != null && !TextUtils.equals(source, component.getPackageName())) {
                    OverlayActionChip chip = constructActionChip(action);
                    mActionContainer.addView(chip);
                    mActionChips.add(chip);
                    break; // only show at most one action chip
                }
            }
        });
    }

    private OverlayActionChip constructActionChip(RemoteAction action) {
        OverlayActionChip chip = (OverlayActionChip) LayoutInflater.from(mContext).inflate(
                R.layout.overlay_action_chip, mActionContainer, false);
        chip.setText(action.getTitle());
        chip.setContentDescription(action.getTitle());
        chip.setIcon(action.getIcon(), false);
        chip.setPendingIntent(action.getActionIntent(), () -> {
            mUiEventLogger.log(CLIPBOARD_OVERLAY_ACTION_TAPPED);
            animateOut();
        });
        chip.setAlpha(1);
        return chip;
    }

    private void monitorOutsideTouches() {
        InputManager inputManager = mContext.getSystemService(InputManager.class);
        mInputMonitor = inputManager.monitorGestureInput("clipboard overlay", 0);
        mInputEventReceiver = new InputEventReceiver(mInputMonitor.getInputChannel(),
                Looper.getMainLooper()) {
            @Override
            public void onInputEvent(InputEvent event) {
                if (event instanceof MotionEvent) {
                    MotionEvent motionEvent = (MotionEvent) event;
                    if (motionEvent.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        Region touchRegion = new Region();

                        final Rect tmpRect = new Rect();
                        mPreviewBorder.getBoundsOnScreen(tmpRect);
                        tmpRect.inset(
                                (int) FloatingWindowUtil.dpToPx(mDisplayMetrics, -SWIPE_PADDING_DP),
                                (int) FloatingWindowUtil.dpToPx(mDisplayMetrics,
                                        -SWIPE_PADDING_DP));
                        touchRegion.op(tmpRect, Region.Op.UNION);
                        mActionContainerBackground.getBoundsOnScreen(tmpRect);
                        tmpRect.inset(
                                (int) FloatingWindowUtil.dpToPx(mDisplayMetrics, -SWIPE_PADDING_DP),
                                (int) FloatingWindowUtil.dpToPx(mDisplayMetrics,
                                        -SWIPE_PADDING_DP));
                        touchRegion.op(tmpRect, Region.Op.UNION);
                        mDismissButton.getBoundsOnScreen(tmpRect);
                        touchRegion.op(tmpRect, Region.Op.UNION);
                        if (!touchRegion.contains(
                                (int) motionEvent.getRawX(), (int) motionEvent.getRawY())) {
                            animateOut();
                        }
                    }
                }
                finishInputEvent(event, true /* handled */);
            }
        };
    }

    private void editImage(Uri uri) {
        mUiEventLogger.log(CLIPBOARD_OVERLAY_EDIT_TAPPED);
        String editorPackage = mContext.getString(R.string.config_screenshotEditor);
        Intent editIntent = new Intent(Intent.ACTION_EDIT);
        if (!TextUtils.isEmpty(editorPackage)) {
            editIntent.setComponent(ComponentName.unflattenFromString(editorPackage));
        }
        editIntent.setDataAndType(uri, "image/*");
        editIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        editIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        mContext.startActivity(editIntent);
        animateOut();
    }

    private void editText() {
        mUiEventLogger.log(CLIPBOARD_OVERLAY_EDIT_TAPPED);
        Intent editIntent = new Intent(mContext, EditTextActivity.class);
        editIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        mContext.startActivity(editIntent);
        animateOut();
    }

    private void showTextPreview(CharSequence text) {
        mTextPreview.setVisibility(View.VISIBLE);
        mImagePreview.setVisibility(View.GONE);
        mTextPreview.setText(text);
        mEditChip.setVisibility(View.GONE);
    }

    private void showEditableText(CharSequence text) {
        showTextPreview(text);
        mEditChip.setVisibility(View.VISIBLE);
        mEditChip.setAlpha(1f);
        mEditChip.setContentDescription(
                mContext.getString(R.string.clipboard_edit_text_description));
        View.OnClickListener listener = v -> editText();
        mEditChip.setOnClickListener(listener);
        mTextPreview.setOnClickListener(listener);
    }

    private void showEditableImage(Uri uri) {
        mTextPreview.setVisibility(View.GONE);
        mImagePreview.setVisibility(View.VISIBLE);
        mEditChip.setAlpha(1f);
        ContentResolver resolver = mContext.getContentResolver();
        try {
            int size = mContext.getResources().getDimensionPixelSize(R.dimen.overlay_x_scale);
            // The width of the view is capped, height maintains aspect ratio, so allow it to be
            // taller if needed.
            Bitmap thumbnail = resolver.loadThumbnail(uri, new Size(size, size * 4), null);
            mImagePreview.setImageBitmap(thumbnail);
        } catch (IOException e) {
            Log.e(TAG, "Thumbnail loading failed", e);
        }
        View.OnClickListener listener = v -> editImage(uri);
        mEditChip.setOnClickListener(listener);
        mEditChip.setContentDescription(
                mContext.getString(R.string.clipboard_edit_image_description));
        mImagePreview.setOnClickListener(listener);
    }

    private Intent getRemoteCopyIntent(ClipData clipData) {
        String remoteCopyPackage = mContext.getString(R.string.config_remoteCopyPackage);
        if (TextUtils.isEmpty(remoteCopyPackage)) {
            return null;
        }
        Intent nearbyIntent = new Intent(REMOTE_COPY_ACTION);
        nearbyIntent.setComponent(ComponentName.unflattenFromString(remoteCopyPackage));
        nearbyIntent.setClipData(clipData);
        nearbyIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        nearbyIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        return nearbyIntent;
    }

    private void animateIn() {
        getEnterAnimation().start();
    }

    private void animateOut() {
        mView.dismiss();
    }

    private ValueAnimator getEnterAnimation() {
        ValueAnimator anim = ValueAnimator.ofFloat(0, 1);

        mContainer.setAlpha(0);
        mDismissButton.setVisibility(View.GONE);
        final View previewBorder = requireNonNull(mView.findViewById(R.id.preview_border));
        final View actionBackground = requireNonNull(
                mView.findViewById(R.id.actions_container_background));
        mImagePreview.setVisibility(View.VISIBLE);
        mActionContainerBackground.setVisibility(View.VISIBLE);
        if (mAccessibilityManager.isEnabled()) {
            mDismissButton.setVisibility(View.VISIBLE);
        }

        anim.addUpdateListener(animation -> {
            mContainer.setAlpha(animation.getAnimatedFraction());
            float scale = 0.6f + 0.4f * animation.getAnimatedFraction();
            mView.setPivotY(mView.getHeight() - previewBorder.getHeight() / 2f);
            mView.setPivotX(actionBackground.getWidth() / 2f);
            mView.setScaleX(scale);
            mView.setScaleY(scale);
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                mContainer.setAlpha(1);
                mTimeoutHandler.resetTimeout();
            }
        });
        return anim;
    }

    private void hideImmediate() {
        // Note this may be called multiple times if multiple dismissal events happen at the same
        // time.
        mTimeoutHandler.cancelTimeout();
        final View decorView = mWindow.peekDecorView();
        if (decorView != null && decorView.isAttachedToWindow()) {
            mWindowManager.removeViewImmediate(decorView);
        }
        if (mCloseDialogsReceiver != null) {
            mContext.unregisterReceiver(mCloseDialogsReceiver);
            mCloseDialogsReceiver = null;
        }
        if (mScreenshotReceiver != null) {
            mContext.unregisterReceiver(mScreenshotReceiver);
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

    private void resetActionChips() {
        for (OverlayActionChip chip : mActionChips) {
            mActionContainer.removeView(chip);
        }
        mActionChips.clear();
    }

    private void reset() {
        mView.setTranslationX(0);
        mContainer.setAlpha(0);
        resetActionChips();
        mTimeoutHandler.cancelTimeout();
    }

    @MainThread
    private void attachWindow() {
        View decorView = mWindow.getDecorView();
        if (decorView.isAttachedToWindow() || mBlockAttach) {
            return;
        }
        mBlockAttach = true;
        mWindowManager.addView(decorView, mWindowLayoutParams);
        decorView.requestApplyInsets();
        mView.requestApplyInsets();
        decorView.getViewTreeObserver().addOnWindowAttachListener(
                new ViewTreeObserver.OnWindowAttachListener() {
                    @Override
                    public void onWindowAttached() {
                        mBlockAttach = false;
                    }

                    @Override
                    public void onWindowDetached() {
                    }
                }
        );
    }

    private void withWindowAttached(Runnable action) {
        View decorView = mWindow.getDecorView();
        if (decorView.isAttachedToWindow()) {
            action.run();
        } else {
            decorView.getViewTreeObserver().addOnWindowAttachListener(
                    new ViewTreeObserver.OnWindowAttachListener() {
                        @Override
                        public void onWindowAttached() {
                            mBlockAttach = false;
                            decorView.getViewTreeObserver().removeOnWindowAttachListener(this);
                            action.run();
                        }

                        @Override
                        public void onWindowDetached() {
                        }
                    });
        }
    }

    private void updateInsets(WindowInsets insets) {
        int orientation = mContext.getResources().getConfiguration().orientation;
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) mView.getLayoutParams();
        if (p == null) {
            return;
        }
        DisplayCutout cutout = insets.getDisplayCutout();
        Insets navBarInsets = insets.getInsets(WindowInsets.Type.navigationBars());
        if (cutout == null) {
            p.setMargins(0, 0, 0, navBarInsets.bottom);
        } else {
            Insets waterfall = cutout.getWaterfallInsets();
            if (orientation == ORIENTATION_PORTRAIT) {
                p.setMargins(
                        waterfall.left,
                        Math.max(cutout.getSafeInsetTop(), waterfall.top),
                        waterfall.right,
                        Math.max(cutout.getSafeInsetBottom(),
                                Math.max(navBarInsets.bottom, waterfall.bottom)));
            } else {
                p.setMargins(
                        Math.max(cutout.getSafeInsetLeft(), waterfall.left),
                        waterfall.top,
                        Math.max(cutout.getSafeInsetRight(), waterfall.right),
                        Math.max(navBarInsets.bottom, waterfall.bottom));
            }
        }
        mView.setLayoutParams(p);
        mView.requestLayout();
    }

    private Display getDefaultDisplay() {
        return mDisplayManager.getDisplay(DEFAULT_DISPLAY);
    }

    /**
     * Updates the window focusability.  If the window is already showing, then it updates the
     * window immediately, otherwise the layout params will be applied when the window is next
     * shown.
     */
    private void setWindowFocusable(boolean focusable) {
        int flags = mWindowLayoutParams.flags;
        if (focusable) {
            mWindowLayoutParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        } else {
            mWindowLayoutParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }
        if (mWindowLayoutParams.flags == flags) {
            return;
        }
        final View decorView = mWindow.peekDecorView();
        if (decorView != null && decorView.isAttachedToWindow()) {
            mWindowManager.updateViewLayout(decorView, mWindowLayoutParams);
        }
    }
}
