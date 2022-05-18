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
import android.animation.AnimatorSet;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.annotation.MainThread;
import android.app.ICompatCameraControlCallback;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Paint;
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
import android.util.MathUtils;
import android.util.Size;
import android.util.TypedValue;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputMonitor;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewRootImpl;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;
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
import com.android.settingslib.applications.InterestingConfigChanges;
import com.android.systemui.R;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.broadcast.BroadcastSender;
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

    private static final String EXTRA_EDIT_SOURCE_CLIPBOARD = "edit_source_clipboard";

    private static final int CLIPBOARD_DEFAULT_TIMEOUT_MILLIS = 6000;
    private static final int SWIPE_PADDING_DP = 12; // extra padding around views to allow swipe
    private static final int FONT_SEARCH_STEP_PX = 4;

    private final Context mContext;
    private final UiEventLogger mUiEventLogger;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final DisplayManager mDisplayManager;
    private final DisplayMetrics mDisplayMetrics;
    private final WindowManager mWindowManager;
    private final WindowManager.LayoutParams mWindowLayoutParams;
    private final PhoneWindow mWindow;
    private final TimeoutHandler mTimeoutHandler;
    private final AccessibilityManager mAccessibilityManager;
    private final TextClassifier mTextClassifier;

    private final DraggableConstraintLayout mView;
    private final View mClipboardPreview;
    private final ImageView mImagePreview;
    private final TextView mTextPreview;
    private final TextView mHiddenTextPreview;
    private final TextView mHiddenImagePreview;
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
    private Animator mExitAnimator;

    /** Tracks config changes that require updating insets */
    private final InterestingConfigChanges mConfigChanges = new InterestingConfigChanges(
                    ActivityInfo.CONFIG_KEYBOARD_HIDDEN);

    public ClipboardOverlayController(Context context,
            BroadcastDispatcher broadcastDispatcher,
            BroadcastSender broadcastSender,
            TimeoutHandler timeoutHandler, UiEventLogger uiEventLogger) {
        mBroadcastDispatcher = broadcastDispatcher;
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

        mView = (DraggableConstraintLayout)
                LayoutInflater.from(mContext).inflate(R.layout.clipboard_overlay, null);
        mActionContainerBackground =
                requireNonNull(mView.findViewById(R.id.actions_container_background));
        mActionContainer = requireNonNull(mView.findViewById(R.id.actions));
        mClipboardPreview = requireNonNull(mView.findViewById(R.id.clipboard_preview));
        mImagePreview = requireNonNull(mView.findViewById(R.id.image_preview));
        mTextPreview = requireNonNull(mView.findViewById(R.id.text_preview));
        mHiddenTextPreview = requireNonNull(mView.findViewById(R.id.hidden_text_preview));
        mHiddenImagePreview = requireNonNull(mView.findViewById(R.id.hidden_image_preview));
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
                mExitAnimator = animator;
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
            mWindow.setContentView(mView);
            updateInsets(mWindowManager.getCurrentWindowMetrics().getWindowInsets());
            mView.requestLayout();
            mWindow.peekDecorView().getViewRootImpl().setActivityConfigCallback(
                    new ViewRootImpl.ActivityConfigCallback() {
                        @Override
                        public void onConfigurationChanged(Configuration overrideConfig,
                                int newDisplayId) {
                            if (mConfigChanges.applyNewConfig(mContext.getResources())) {
                                updateInsets(
                                        mWindowManager.getCurrentWindowMetrics().getWindowInsets());
                            }
                        }

                        @Override
                        public void requestCompatCameraControl(
                                boolean showControl, boolean transformationApplied,
                                ICompatCameraControlCallback callback) {
                            Log.w(TAG, "unexpected requestCompatCameraControl call");
                        }
                    });
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

        mBroadcastDispatcher.registerReceiver(mCloseDialogsReceiver,
                new IntentFilter(ACTION_CLOSE_SYSTEM_DIALOGS));
        mScreenshotReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (SCREENSHOT_ACTION.equals(intent.getAction())) {
                    animateOut();
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

    void setClipData(ClipData clipData, String clipSource) {
        if (mExitAnimator != null && mExitAnimator.isRunning()) {
            mExitAnimator.cancel();
        }
        reset();
        String accessibilityAnnouncement;

        boolean isSensitive = clipData != null && clipData.getDescription().getExtras() != null
                && clipData.getDescription().getExtras()
                .getBoolean(ClipDescription.EXTRA_IS_SENSITIVE);
        if (clipData == null || clipData.getItemCount() == 0) {
            showTextPreview(
                    mContext.getResources().getString(R.string.clipboard_overlay_text_copied),
                    mTextPreview);
            accessibilityAnnouncement = mContext.getString(R.string.clipboard_content_copied);
        } else if (!TextUtils.isEmpty(clipData.getItemAt(0).getText())) {
            ClipData.Item item = clipData.getItemAt(0);
            if (item.getTextLinks() != null) {
                AsyncTask.execute(() -> classifyText(clipData.getItemAt(0), clipSource));
            }
            if (isSensitive) {
                showEditableText(
                        mContext.getResources().getString(R.string.clipboard_text_hidden), true);
            } else {
                showEditableText(item.getText(), false);
            }
            accessibilityAnnouncement = mContext.getString(R.string.clipboard_text_copied);
        } else if (clipData.getItemAt(0).getUri() != null) {
            if (tryShowEditableImage(clipData.getItemAt(0).getUri(), isSensitive)) {
                accessibilityAnnouncement = mContext.getString(R.string.clipboard_image_copied);
            } else {
                accessibilityAnnouncement = mContext.getString(R.string.clipboard_content_copied);
            }
        } else {
            showTextPreview(
                    mContext.getResources().getString(R.string.clipboard_overlay_text_copied),
                    mTextPreview);
            accessibilityAnnouncement = mContext.getString(R.string.clipboard_content_copied);
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
            mActionContainerBackground.setVisibility(View.VISIBLE);
        } else {
            mRemoteCopyChip.setVisibility(View.GONE);
        }
        withWindowAttached(() -> {
            updateInsets(
                    mWindowManager.getCurrentWindowMetrics().getWindowInsets());
            mView.post(this::animateIn);
            mView.announceForAccessibility(accessibilityAnnouncement);
        });
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
        editIntent.putExtra(EXTRA_EDIT_SOURCE_CLIPBOARD, true);
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

    private void showSinglePreview(View v) {
        mTextPreview.setVisibility(View.GONE);
        mImagePreview.setVisibility(View.GONE);
        mHiddenTextPreview.setVisibility(View.GONE);
        mHiddenImagePreview.setVisibility(View.GONE);
        v.setVisibility(View.VISIBLE);
    }

    private void showTextPreview(CharSequence text, TextView textView) {
        showSinglePreview(textView);
        final CharSequence truncatedText = text.subSequence(0, Math.min(500, text.length()));
        textView.setText(truncatedText);
        updateTextSize(truncatedText, textView);

        textView.addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    if (right - left != oldRight - oldLeft) {
                        updateTextSize(truncatedText, textView);
                    }
                });
        mEditChip.setVisibility(View.GONE);
    }

    private void updateTextSize(CharSequence text, TextView textView) {
        Paint paint = new Paint(textView.getPaint());
        Resources res = textView.getResources();
        float minFontSize = res.getDimensionPixelSize(R.dimen.clipboard_overlay_min_font);
        float maxFontSize = res.getDimensionPixelSize(R.dimen.clipboard_overlay_max_font);
        if (isOneWord(text) && fitsInView(text, textView, paint, minFontSize)) {
            // If the text is a single word and would fit within the TextView at the min font size,
            // find the biggest font size that will fit.
            float fontSizePx = minFontSize;
            while (fontSizePx + FONT_SEARCH_STEP_PX < maxFontSize
                    && fitsInView(text, textView, paint, fontSizePx + FONT_SEARCH_STEP_PX)) {
                fontSizePx += FONT_SEARCH_STEP_PX;
            }
            // Need to turn off autosizing, otherwise setTextSize is a no-op.
            textView.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE);
            // It's possible to hit the max font size and not fill the width, so centering
            // horizontally looks better in this case.
            textView.setGravity(Gravity.CENTER);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, (int) fontSizePx);
        } else {
            // Otherwise just stick with autosize.
            textView.setAutoSizeTextTypeUniformWithConfiguration((int) minFontSize,
                    (int) maxFontSize, FONT_SEARCH_STEP_PX, TypedValue.COMPLEX_UNIT_PX);
            textView.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        }
    }

    private static boolean fitsInView(CharSequence text, TextView textView, Paint paint,
            float fontSizePx) {
        paint.setTextSize(fontSizePx);
        float size = paint.measureText(text.toString());
        float availableWidth = textView.getWidth() - textView.getPaddingLeft()
                - textView.getPaddingRight();
        return size < availableWidth;
    }

    private static boolean isOneWord(CharSequence text) {
        return text.toString().split("\\s+", 2).length == 1;
    }

    private void showEditableText(CharSequence text, boolean hidden) {
        TextView textView = hidden ? mHiddenTextPreview : mTextPreview;
        showTextPreview(text, textView);
        mEditChip.setVisibility(View.VISIBLE);
        mActionContainerBackground.setVisibility(View.VISIBLE);
        mEditChip.setAlpha(1f);
        mEditChip.setContentDescription(
                mContext.getString(R.string.clipboard_edit_text_description));
        View.OnClickListener listener = v -> editText();
        mEditChip.setOnClickListener(listener);
        textView.setOnClickListener(listener);
    }

    private boolean tryShowEditableImage(Uri uri, boolean isSensitive) {
        View.OnClickListener listener = v -> editImage(uri);
        ContentResolver resolver = mContext.getContentResolver();
        String mimeType = resolver.getType(uri);
        boolean isEditableImage = mimeType != null && mimeType.startsWith("image");
        if (isSensitive) {
            showSinglePreview(mHiddenImagePreview);
            if (isEditableImage) {
                mHiddenImagePreview.setOnClickListener(listener);
            }
        } else if (isEditableImage) { // if the MIMEtype is image, try to load
            try {
                int size = mContext.getResources().getDimensionPixelSize(R.dimen.overlay_x_scale);
                // The width of the view is capped, height maintains aspect ratio, so allow it to be
                // taller if needed.
                Bitmap thumbnail = resolver.loadThumbnail(uri, new Size(size, size * 4), null);
                showSinglePreview(mImagePreview);
                mImagePreview.setImageBitmap(thumbnail);
                mImagePreview.setOnClickListener(listener);
            } catch (IOException e) {
                Log.e(TAG, "Thumbnail loading failed", e);
                showTextPreview(
                        mContext.getResources().getString(R.string.clipboard_overlay_text_copied),
                        mTextPreview);
                isEditableImage = false;
            }
        } else {
            showTextPreview(
                    mContext.getResources().getString(R.string.clipboard_overlay_text_copied),
                    mTextPreview);
        }
        if (isEditableImage) {
            mEditChip.setVisibility(View.VISIBLE);
            mEditChip.setAlpha(1f);
            mActionContainerBackground.setVisibility(View.VISIBLE);
            mEditChip.setOnClickListener(listener);
            mEditChip.setContentDescription(
                    mContext.getString(R.string.clipboard_edit_image_description));
        }
        return isEditableImage;
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
        if (mAccessibilityManager.isEnabled()) {
            mDismissButton.setVisibility(View.VISIBLE);
        }
        getEnterAnimation().start();
    }

    private void animateOut() {
        if (mExitAnimator != null && mExitAnimator.isRunning()) {
            return;
        }
        Animator anim = getExitAnimation();
        anim.addListener(new AnimatorListenerAdapter() {
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
        mExitAnimator = anim;
        anim.start();
    }

    private Animator getEnterAnimation() {
        TimeInterpolator linearInterpolator = new LinearInterpolator();
        TimeInterpolator scaleInterpolator = new PathInterpolator(0, 0, 0, 1f);
        AnimatorSet enterAnim = new AnimatorSet();

        ValueAnimator rootAnim = ValueAnimator.ofFloat(0, 1);
        rootAnim.setInterpolator(linearInterpolator);
        rootAnim.setDuration(66);
        rootAnim.addUpdateListener(animation -> {
            mView.setAlpha(animation.getAnimatedFraction());
        });

        ValueAnimator scaleAnim = ValueAnimator.ofFloat(0, 1);
        scaleAnim.setInterpolator(scaleInterpolator);
        scaleAnim.setDuration(333);
        scaleAnim.addUpdateListener(animation -> {
            float previewScale = MathUtils.lerp(.9f, 1f, animation.getAnimatedFraction());
            mClipboardPreview.setScaleX(previewScale);
            mClipboardPreview.setScaleY(previewScale);
            mPreviewBorder.setScaleX(previewScale);
            mPreviewBorder.setScaleY(previewScale);

            float pivotX = mClipboardPreview.getWidth() / 2f + mClipboardPreview.getX();
            mActionContainerBackground.setPivotX(pivotX - mActionContainerBackground.getX());
            mActionContainer.setPivotX(pivotX - ((View) mActionContainer.getParent()).getX());
            float actionsScaleX = MathUtils.lerp(.7f, 1f, animation.getAnimatedFraction());
            float actionsScaleY = MathUtils.lerp(.9f, 1f, animation.getAnimatedFraction());
            mActionContainer.setScaleX(actionsScaleX);
            mActionContainer.setScaleY(actionsScaleY);
            mActionContainerBackground.setScaleX(actionsScaleX);
            mActionContainerBackground.setScaleY(actionsScaleY);
        });

        ValueAnimator alphaAnim = ValueAnimator.ofFloat(0, 1);
        alphaAnim.setInterpolator(linearInterpolator);
        alphaAnim.setDuration(283);
        alphaAnim.addUpdateListener(animation -> {
            float alpha = animation.getAnimatedFraction();
            mClipboardPreview.setAlpha(alpha);
            mPreviewBorder.setAlpha(alpha);
            mDismissButton.setAlpha(alpha);
            mActionContainer.setAlpha(alpha);
        });

        mActionContainer.setAlpha(0);
        mPreviewBorder.setAlpha(0);
        mClipboardPreview.setAlpha(0);
        enterAnim.play(rootAnim).with(scaleAnim);
        enterAnim.play(alphaAnim).after(50).after(rootAnim);

        enterAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                mView.setAlpha(1);
                mTimeoutHandler.resetTimeout();
            }
        });
        return enterAnim;
    }

    private Animator getExitAnimation() {
        TimeInterpolator linearInterpolator = new LinearInterpolator();
        TimeInterpolator scaleInterpolator = new PathInterpolator(.3f, 0, 1f, 1f);
        AnimatorSet exitAnim = new AnimatorSet();

        ValueAnimator rootAnim = ValueAnimator.ofFloat(0, 1);
        rootAnim.setInterpolator(linearInterpolator);
        rootAnim.setDuration(100);
        rootAnim.addUpdateListener(anim -> mView.setAlpha(1 - anim.getAnimatedFraction()));

        ValueAnimator scaleAnim = ValueAnimator.ofFloat(0, 1);
        scaleAnim.setInterpolator(scaleInterpolator);
        scaleAnim.setDuration(250);
        scaleAnim.addUpdateListener(animation -> {
            float previewScale = MathUtils.lerp(1f, .9f, animation.getAnimatedFraction());
            mClipboardPreview.setScaleX(previewScale);
            mClipboardPreview.setScaleY(previewScale);
            mPreviewBorder.setScaleX(previewScale);
            mPreviewBorder.setScaleY(previewScale);

            float pivotX = mClipboardPreview.getWidth() / 2f + mClipboardPreview.getX();
            mActionContainerBackground.setPivotX(pivotX - mActionContainerBackground.getX());
            mActionContainer.setPivotX(pivotX - ((View) mActionContainer.getParent()).getX());
            float actionScaleX = MathUtils.lerp(1f, .8f, animation.getAnimatedFraction());
            float actionScaleY = MathUtils.lerp(1f, .9f, animation.getAnimatedFraction());
            mActionContainer.setScaleX(actionScaleX);
            mActionContainer.setScaleY(actionScaleY);
            mActionContainerBackground.setScaleX(actionScaleX);
            mActionContainerBackground.setScaleY(actionScaleY);
        });

        ValueAnimator alphaAnim = ValueAnimator.ofFloat(0, 1);
        alphaAnim.setInterpolator(linearInterpolator);
        alphaAnim.setDuration(166);
        alphaAnim.addUpdateListener(animation -> {
            float alpha = 1 - animation.getAnimatedFraction();
            mClipboardPreview.setAlpha(alpha);
            mPreviewBorder.setAlpha(alpha);
            mDismissButton.setAlpha(alpha);
            mActionContainer.setAlpha(alpha);
        });

        exitAnim.play(alphaAnim).with(scaleAnim);
        exitAnim.play(rootAnim).after(150).after(alphaAnim);
        return exitAnim;
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

    private void resetActionChips() {
        for (OverlayActionChip chip : mActionChips) {
            mActionContainer.removeView(chip);
        }
        mActionChips.clear();
    }

    private void reset() {
        mView.setTranslationX(0);
        mView.setAlpha(0);
        mActionContainerBackground.setVisibility(View.GONE);
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
        Insets imeInsets = insets.getInsets(WindowInsets.Type.ime());
        if (cutout == null) {
            p.setMargins(0, 0, 0, Math.max(imeInsets.bottom, navBarInsets.bottom));
        } else {
            Insets waterfall = cutout.getWaterfallInsets();
            if (orientation == ORIENTATION_PORTRAIT) {
                p.setMargins(
                        waterfall.left,
                        Math.max(cutout.getSafeInsetTop(), waterfall.top),
                        waterfall.right,
                        Math.max(imeInsets.bottom,
                                Math.max(cutout.getSafeInsetBottom(),
                                        Math.max(navBarInsets.bottom, waterfall.bottom))));
            } else {
                p.setMargins(
                        waterfall.left,
                        waterfall.top,
                        waterfall.right,
                        Math.max(imeInsets.bottom,
                                Math.max(navBarInsets.bottom, waterfall.bottom)));
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
