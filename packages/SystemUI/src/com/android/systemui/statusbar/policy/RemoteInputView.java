/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import static android.view.WindowInsetsAnimation.Callback.DISPATCH_MODE_STOP;

import static com.android.systemui.statusbar.notification.stack.StackStateAnimator.ANIMATION_DURATION_STANDARD;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.BlendMode;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Trace;
import android.os.UserHandle;
import android.text.Editable;
import android.text.SpannedString;
import android.text.TextWatcher;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Pair;
import android.view.ContentInfo;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.OnReceiveContentListener;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewRootImpl;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import android.view.WindowInsetsController;
import android.view.accessibility.AccessibilityEvent;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.animation.Animator;
import androidx.core.animation.AnimatorListenerAdapter;
import androidx.core.animation.AnimatorSet;
import androidx.core.animation.ObjectAnimator;
import androidx.core.animation.ValueAnimator;

import com.android.app.animation.InterpolatorsAndroidX;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.graphics.ColorUtils;
import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.util.ContrastColorUtil;
import com.android.systemui.Dependency;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.RemoteInputController;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.wrapper.NotificationViewWrapper;
import com.android.systemui.statusbar.phone.LightBarController;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * Host for the remote input.
 */
public class RemoteInputView extends LinearLayout implements View.OnClickListener {

    private static final boolean DEBUG = false;
    private static final String TAG = "RemoteInput";

    // A marker object that let's us easily find views of this class.
    public static final Object VIEW_TAG = new Object();

    private static final long FOCUS_ANIMATION_TOTAL_DURATION = ANIMATION_DURATION_STANDARD;
    private static final long FOCUS_ANIMATION_CROSSFADE_DURATION = 50;
    private static final long FOCUS_ANIMATION_FADE_IN_DELAY = 33;
    private static final long FOCUS_ANIMATION_FADE_IN_DURATION = 83;
    private static final float FOCUS_ANIMATION_MIN_SCALE = 0.5f;
    private static final long DEFOCUS_ANIMATION_FADE_OUT_DELAY = 120;
    private static final long DEFOCUS_ANIMATION_CROSSFADE_DELAY = 180;

    public final Object mToken = new Object();

    private final SendButtonTextWatcher mTextWatcher;
    private final TextView.OnEditorActionListener mEditorActionHandler;
    private final ArrayList<Runnable> mOnSendListeners = new ArrayList<>();
    private Consumer<Boolean> mOnVisibilityChangedListener = null;
    private final ArrayList<OnFocusChangeListener> mEditTextFocusChangeListeners =
            new ArrayList<>();

    private RemoteEditText mEditText;
    private ImageButton mSendButton;
    private LinearLayout mContentView;
    private GradientDrawable mContentBackground;
    private ProgressBar mProgressBar;
    private ImageView mDelete;
    private ImageView mDeleteBg;
    private boolean mColorized;
    private int mLastBackgroundColor;
    private boolean mResetting;
    private Rect mContentBackgroundBounds;
    private boolean mIsAnimatingAppearance = false;

    // TODO(b/193539698): move these to a Controller
    private RemoteInputController mController;
    private final UiEventLogger mUiEventLogger;
    private NotificationEntry mEntry;
    private boolean mRemoved;
    private boolean mSending;
    private NotificationViewWrapper mWrapper;

    // TODO(b/193539698): remove this; views shouldn't have access to their controller, and places
    //  that need the controller shouldn't have access to the view
    private RemoteInputViewController mViewController;
    private ViewRootImpl mTestableViewRootImpl;

    /**
     * Enum for logged notification remote input UiEvents.
     */
    enum NotificationRemoteInputEvent implements UiEventLogger.UiEventEnum {
        @UiEvent(doc = "Notification remote input view was displayed")
        NOTIFICATION_REMOTE_INPUT_OPEN(795),
        @UiEvent(doc = "Notification remote input view was closed")
        NOTIFICATION_REMOTE_INPUT_CLOSE(796),
        @UiEvent(doc = "User sent data through the notification remote input view")
        NOTIFICATION_REMOTE_INPUT_SEND(797),
        @UiEvent(doc = "Failed attempt to send data through the notification remote input view")
        NOTIFICATION_REMOTE_INPUT_FAILURE(798),
        @UiEvent(doc = "User attached an image to the remote input view")
        NOTIFICATION_REMOTE_INPUT_ATTACH_IMAGE(825);

        private final int mId;
        NotificationRemoteInputEvent(int id) {
            mId = id;
        }
        @Override public int getId() {
            return mId;
        }
    }

    public RemoteInputView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mTextWatcher = new SendButtonTextWatcher();
        mEditorActionHandler = new EditorActionHandler();
        mUiEventLogger = Dependency.get(UiEventLogger.class);
        TypedArray ta = getContext().getTheme().obtainStyledAttributes(new int[]{
                com.android.internal.R.attr.materialColorSurfaceDim,
        });
        mLastBackgroundColor = ta.getColor(0, 0);
        ta.recycle();
    }

    // TODO(b/193539698): move to Controller, since we're just directly accessing a system service
    /** Hide the IME, if visible. */
    public void hideIme() {
        mEditText.hideIme();
    }

    private ColorStateList colorStateListWithDisabledAlpha(int color, int disabledAlpha) {
        return new ColorStateList(new int[][]{
                new int[]{-com.android.internal.R.attr.state_enabled}, // disabled
                new int[]{},
        }, new int[]{
                ColorUtils.setAlphaComponent(color, disabledAlpha),
                color
        });
    }

    /**
     * The remote view needs to adapt to colorized notifications when set
     * It overrides the background of itself as well as all of its childern
     * @param backgroundColor colorized notification color
     */
    public void setBackgroundTintColor(final int backgroundColor, boolean colorized) {
        if (colorized == mColorized && backgroundColor == mLastBackgroundColor) return;
        mColorized = colorized;
        mLastBackgroundColor = backgroundColor;
        final int editBgColor;
        final int deleteBgColor;
        final int deleteFgColor;
        final ColorStateList accentColor;
        final ColorStateList textColor;
        final int hintColor;
        final int stroke = colorized ? mContext.getResources().getDimensionPixelSize(
                R.dimen.remote_input_view_text_stroke) : 0;
        if (colorized) {
            final boolean dark = ContrastColorUtil.isColorDark(backgroundColor);
            final int foregroundColor = dark ? Color.WHITE : Color.BLACK;
            final int inverseColor = dark ? Color.BLACK : Color.WHITE;
            editBgColor = backgroundColor;
            deleteBgColor = foregroundColor;
            deleteFgColor = inverseColor;
            accentColor = colorStateListWithDisabledAlpha(foregroundColor, 0x4D); // 30%
            textColor = colorStateListWithDisabledAlpha(foregroundColor, 0x99); // 60%
            hintColor = ColorUtils.setAlphaComponent(foregroundColor, 0x99);
        } else {
            accentColor = mContext.getColorStateList(R.color.remote_input_send);
            textColor = mContext.getColorStateList(R.color.remote_input_text);
            hintColor = mContext.getColor(R.color.remote_input_hint);
            deleteFgColor = textColor.getDefaultColor();
            try (TypedArray ta = getContext().getTheme().obtainStyledAttributes(new int[]{
                    com.android.internal.R.attr.materialColorSurfaceDim,
                    com.android.internal.R.attr.materialColorSurfaceVariant
            })) {
                editBgColor = ta.getColor(0, backgroundColor);
                deleteBgColor = ta.getColor(1, Color.GRAY);
            }
        }

        mEditText.setTextColor(textColor);
        mEditText.setHintTextColor(hintColor);
        if (mEditText.getTextCursorDrawable() != null) {
            mEditText.getTextCursorDrawable().setColorFilter(
                    accentColor.getDefaultColor(), PorterDuff.Mode.SRC_IN);
        }
        mContentBackground.setColor(editBgColor);
        mContentBackground.setStroke(stroke, accentColor);
        mDelete.setImageTintList(ColorStateList.valueOf(deleteFgColor));
        mDeleteBg.setImageTintList(ColorStateList.valueOf(deleteBgColor));
        mSendButton.setImageTintList(accentColor);
        mProgressBar.setProgressTintList(accentColor);
        mProgressBar.setIndeterminateTintList(accentColor);
        mProgressBar.setSecondaryProgressTintList(accentColor);
        setBackgroundColor(backgroundColor);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mProgressBar = findViewById(R.id.remote_input_progress);
        mSendButton = findViewById(R.id.remote_input_send);
        mSendButton.setOnClickListener(this);
        mContentBackground = (GradientDrawable)
                mContext.getDrawable(R.drawable.remote_input_view_text_bg).mutate();
        mDelete = findViewById(R.id.remote_input_delete);
        mDeleteBg = findViewById(R.id.remote_input_delete_bg);
        mDeleteBg.setImageTintBlendMode(BlendMode.SRC_IN);
        mDelete.setImageTintBlendMode(BlendMode.SRC_IN);
        mDelete.setOnClickListener(v -> setAttachment(null));
        mContentView = findViewById(R.id.remote_input_content);
        mContentView.setBackground(mContentBackground);
        mEditText = findViewById(R.id.remote_input_text);
        mEditText.setInnerFocusable(false);
        // TextView initializes the spell checked when the view is attached to a window.
        // This causes a couple of IPCs that can jank, especially during animations.
        // By default the text view should be disabled, to avoid the unnecessary initialization.
        mEditText.setEnabled(false);
        mEditText.setWindowInsetsAnimationCallback(
                new WindowInsetsAnimation.Callback(DISPATCH_MODE_STOP) {
            @NonNull
            @Override
            public WindowInsets onProgress(@NonNull WindowInsets insets,
                    @NonNull List<WindowInsetsAnimation> runningAnimations) {
                return insets;
            }
            @Override
            public void onEnd(@NonNull WindowInsetsAnimation animation) {
                super.onEnd(animation);
                if (animation.getTypeMask() == WindowInsets.Type.ime()) {
                    mEntry.mRemoteEditImeAnimatingAway = false;
                    WindowInsets editTextRootWindowInsets = mEditText.getRootWindowInsets();
                    if (editTextRootWindowInsets == null) {
                        Log.w(TAG, "onEnd called on detached view", new Exception());
                    }
                    mEntry.mRemoteEditImeVisible = editTextRootWindowInsets != null
                            && editTextRootWindowInsets.isVisible(WindowInsets.Type.ime());
                    if (!mEntry.mRemoteEditImeVisible && !mEditText.mShowImeOnInputConnection) {
                            mController.removeRemoteInput(mEntry, mToken,
                                    /* reason= */"RemoteInputView$WindowInsetAnimation#onEnd");
                    }
                }
            }
        });
    }

    /**
     * @deprecated TODO(b/193539698): views shouldn't have access to their controller, and places
     *  that need the controller shouldn't have access to the view
     */
    @Deprecated
    public void setController(RemoteInputViewController controller) {
        mViewController = controller;
    }

    /**
     * @deprecated TODO(b/193539698): views shouldn't have access to their controller, and places
     *  that need the controller shouldn't have access to the view
     */
    @Deprecated
    public RemoteInputViewController getController() {
        return mViewController;
    }

    /** Clear the attachment, if present. */
    public void clearAttachment() {
        setAttachment(null);
    }

    @VisibleForTesting
    protected void setAttachment(ContentInfo item) {
        if (mEntry.remoteInputAttachment != null && mEntry.remoteInputAttachment != item) {
            // We need to release permissions when sending the attachment to the target
            // app or if it is deleted by the user. When sending to the target app, we
            // can safely release permissions as soon as the call to
            // `mController.grantInlineReplyUriPermission` is made (ie, after the grant
            // to the target app has been created).
            mEntry.remoteInputAttachment.releasePermissions();
        }
        mEntry.remoteInputAttachment = item;
        if (item != null) {
            mEntry.remoteInputUri = item.getClip().getItemAt(0).getUri();
            mEntry.remoteInputMimeType = item.getClip().getDescription().getMimeType(0);
        }

        View attachment = findViewById(R.id.remote_input_content_container);
        ImageView iconView = findViewById(R.id.remote_input_attachment_image);
        iconView.setImageDrawable(null);
        if (item == null) {
            attachment.setVisibility(GONE);
            return;
        }
        iconView.setImageURI(item.getClip().getItemAt(0).getUri());
        if (iconView.getDrawable() == null) {
            attachment.setVisibility(GONE);
        } else {
            attachment.setVisibility(VISIBLE);
            mUiEventLogger.logWithInstanceId(
                    NotificationRemoteInputEvent.NOTIFICATION_REMOTE_INPUT_ATTACH_IMAGE,
                    mEntry.getSbn().getUid(), mEntry.getSbn().getPackageName(),
                    mEntry.getSbn().getInstanceId());
        }
        updateSendButton();
    }

    /** Show the "sending in-progress" UI. */
    public void startSending() {
        mEditText.setEnabled(false);
        mSending = true;
        mSendButton.setVisibility(INVISIBLE);
        mProgressBar.setVisibility(VISIBLE);
        mEditText.mShowImeOnInputConnection = false;
    }

    private void sendRemoteInput() {
        for (Runnable listener : new ArrayList<>(mOnSendListeners)) {
            listener.run();
        }
    }

    public CharSequence getText() {
        return mEditText.getText();
    }

    public static RemoteInputView inflate(Context context, ViewGroup root,
            NotificationEntry entry,
            RemoteInputController controller) {
        RemoteInputView v = (RemoteInputView)
                LayoutInflater.from(context).inflate(R.layout.remote_input, root, false);
        v.mController = controller;
        v.mEntry = entry;
        UserHandle user = computeTextOperationUser(entry.getSbn().getUser());
        v.mEditText.mUser = user;
        v.mEditText.setTextOperationUser(user);
        v.setTag(VIEW_TAG);

        return v;
    }

    @Override
    public void onClick(View v) {
        if (v == mSendButton) {
            sendRemoteInput();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        super.onTouchEvent(event);

        // We never want for a touch to escape to an outer view or one we covered.
        return true;
    }

    public boolean isAnimatingAppearance() {
        return mIsAnimatingAppearance;
    }

    @VisibleForTesting
    void onDefocus(boolean animate, boolean logClose, @Nullable Runnable doAfterDefocus) {
        mController.removeRemoteInput(mEntry, mToken, /* reason= */"RemoteInputView#onDefocus");
        mEntry.remoteInputText = mEditText.getText();

        // During removal, we get reattached and lose focus. Not hiding in that
        // case to prevent flicker.
        if (!mRemoved) {
            ViewGroup parent = (ViewGroup) getParent();
            if (animate && parent != null) {

                ViewGroup grandParent = (ViewGroup) parent.getParent();
                View actionsContainer = getActionsContainerLayout();
                int actionsContainerHeight =
                        actionsContainer != null ? actionsContainer.getHeight() : 0;

                // When defocusing, the notification needs to shrink. Therefore, we need to free
                // up the space that was needed for the RemoteInputView. This is done by setting
                // a negative top margin of the height difference of the RemoteInputView and its
                // sibling (the actions_container_layout containing the Reply button etc.)
                final int heightToShrink = actionsContainerHeight - getHeight();
                setTopMargin(heightToShrink);
                if (grandParent != null) grandParent.setClipChildren(false);

                final Animator animator = getDefocusAnimator(actionsContainer);
                animator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        setTopMargin(0);
                        if (grandParent != null) grandParent.setClipChildren(true);
                        setVisibility(GONE);
                        setAlpha(1f);
                        if (mWrapper != null) {
                            mWrapper.setRemoteInputVisible(false);
                        }
                        if (doAfterDefocus != null) {
                            doAfterDefocus.run();
                        }
                    }
                });
                if (actionsContainer != null) actionsContainer.setAlpha(0f);
                animator.start();

            } else {
                setVisibility(GONE);
                if (doAfterDefocus != null) doAfterDefocus.run();
                if (mWrapper != null) {
                    mWrapper.setRemoteInputVisible(false);
                }
            }
        }

        if (logClose) {
            mUiEventLogger.logWithInstanceId(
                    NotificationRemoteInputEvent.NOTIFICATION_REMOTE_INPUT_CLOSE,
                    mEntry.getSbn().getUid(), mEntry.getSbn().getPackageName(),
                    mEntry.getSbn().getInstanceId());
        }
    }

    private void setTopMargin(int topMargin) {
        if (!(getLayoutParams() instanceof FrameLayout.LayoutParams layoutParams)) return;
        layoutParams.topMargin = topMargin;
        setLayoutParams(layoutParams);
    }

    @VisibleForTesting
    protected void setViewRootImpl(ViewRootImpl viewRoot) {
        mTestableViewRootImpl = viewRoot;
    }

    @VisibleForTesting
    protected void setEditTextReferenceToSelf() {
        mEditText.mRemoteInputView = this;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        setEditTextReferenceToSelf();
        mEditText.setOnEditorActionListener(mEditorActionHandler);
        mEditText.addTextChangedListener(mTextWatcher);
        if (mEntry.getRow().isChangingPosition()) {
            if (getVisibility() == VISIBLE && mEditText.isFocusable()) {
                mEditText.requestFocus();
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mEditText.removeTextChangedListener(mTextWatcher);
        mEditText.setOnEditorActionListener(null);
        mEditText.mRemoteInputView = null;
        if (mEntry.getRow().isChangingPosition() || isTemporarilyDetached()) {
            return;
        }
        // RemoteInputView can be detached from window before IME close event in some cases like
        // remote input view removal with notification update. As a result of this, RemoteInputView
        // will stop ime animation updates, which results in never removing remote input. That's why
        // we have to set mRemoteEditImeAnimatingAway false on detach to remove remote input.
        mEntry.mRemoteEditImeAnimatingAway = false;
        mController.removeRemoteInput(mEntry, mToken,
                /* reason= */"RemoteInputView#onDetachedFromWindow");
        mController.removeSpinning(mEntry.getKey(), mToken);
    }

    @Override
    public ViewRootImpl getViewRootImpl() {
        if (mTestableViewRootImpl != null) {
            return mTestableViewRootImpl;
        }
        return super.getViewRootImpl();
    }

    private void registerBackCallback() {
        ViewRootImpl viewRoot = getViewRootImpl();
        if (viewRoot == null) {
            if (DEBUG) {
                Log.d(TAG, "ViewRoot was null, NOT registering Predictive Back callback");
            }
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "registering Predictive Back callback");
        }
        viewRoot.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_OVERLAY, mEditText.mOnBackInvokedCallback);
    }

    private void unregisterBackCallback() {
        ViewRootImpl viewRoot = getViewRootImpl();
        if (viewRoot == null) {
            if (DEBUG) {
                Log.d(TAG, "ViewRoot was null, NOT unregistering Predictive Back callback");
            }
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "unregistering Predictive Back callback");
        }
        viewRoot.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                mEditText.mOnBackInvokedCallback);
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        if (isVisible) {
            registerBackCallback();
        } else {
            unregisterBackCallback();
        }
        super.onVisibilityAggregated(isVisible);
        mEditText.setEnabled(isVisible && !mSending);
    }

    public void setHintText(CharSequence hintText) {
        mEditText.setHint(hintText);
    }

    public void setSupportedMimeTypes(Collection<String> mimeTypes) {
        mEditText.setSupportedMimeTypes(mimeTypes);
    }

    /** Populates the text field of the remote input with the given content. */
    public void setEditTextContent(@Nullable CharSequence editTextContent) {
        mEditText.setText(editTextContent);
    }

    /**
     * Focuses the RemoteInputView and animates its appearance
     */
    public void focusAnimated() {
        if (getVisibility() != VISIBLE) {
            mIsAnimatingAppearance = true;
            setAlpha(0f);
            Animator focusAnimator = getFocusAnimator(getActionsContainerLayout());
            focusAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation, boolean isReverse) {
                    mIsAnimatingAppearance = false;
                }
            });
            focusAnimator.start();
        }
        focus();
    }

    private static UserHandle computeTextOperationUser(UserHandle notificationUser) {
        return UserHandle.ALL.equals(notificationUser)
                ? UserHandle.of(ActivityManager.getCurrentUser()) : notificationUser;
    }

    public void focus() {
        mUiEventLogger.logWithInstanceId(
                NotificationRemoteInputEvent.NOTIFICATION_REMOTE_INPUT_OPEN,
                mEntry.getSbn().getUid(), mEntry.getSbn().getPackageName(),
                mEntry.getSbn().getInstanceId());

        setVisibility(VISIBLE);
        if (mWrapper != null) {
            mWrapper.setRemoteInputVisible(true);
        }
        mEditText.setInnerFocusable(true);
        mEditText.mShowImeOnInputConnection = true;
        mEditText.setText(mEntry.remoteInputText);
        mEditText.setSelection(mEditText.length());
        mEditText.requestFocus();
        mController.addRemoteInput(mEntry, mToken, "RemoteInputView#focus");
        setAttachment(mEntry.remoteInputAttachment);

        updateSendButton();
    }

    public void onNotificationUpdateOrReset() {
        boolean sending = mProgressBar.getVisibility() == VISIBLE;

        if (sending) {
            // Update came in after we sent the reply, time to reset.
            reset();
        }

        if (isActive() && mWrapper != null) {
            mWrapper.setRemoteInputVisible(true);
        }
    }

    private void reset() {
        mProgressBar.setVisibility(INVISIBLE);
        mResetting = true;
        mSending = false;
        mController.removeSpinning(mEntry.getKey(), mToken);
        onDefocus(true /* animate */, false /* logClose */, () -> {
            mEntry.remoteInputTextWhenReset = SpannedString.valueOf(mEditText.getText());
            mEditText.getText().clear();
            mEditText.setEnabled(isAggregatedVisible());
            mSendButton.setVisibility(VISIBLE);
            updateSendButton();
            setAttachment(null);
            mResetting = false;
        });
    }

    @Override
    public boolean onRequestSendAccessibilityEvent(View child, AccessibilityEvent event) {
        if (mResetting && child == mEditText) {
            // Suppress text events if it happens during resetting. Ideally this would be
            // suppressed by the text view not being shown, but that doesn't work here because it
            // needs to stay visible for the animation.
            return false;
        }
        return super.onRequestSendAccessibilityEvent(child, event);
    }

    private void updateSendButton() {
        mSendButton.setEnabled(mEditText.length() != 0 || mEntry.remoteInputAttachment != null);
    }

    public void close() {
        mEditText.defocusIfNeeded(false /* animated */);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            mController.requestDisallowLongPressAndDismiss();
        }
        return super.onInterceptTouchEvent(ev);
    }

    public boolean requestScrollTo() {
        mController.lockScrollTo(mEntry);
        return true;
    }

    public boolean isActive() {
        return mEditText.isFocused() && mEditText.isEnabled();
    }

    public void setRemoved() {
        mRemoved = true;
    }

    @Override
    public void dispatchStartTemporaryDetach() {
        super.dispatchStartTemporaryDetach();
        // Detach the EditText temporarily such that it doesn't get onDetachedFromWindow and
        // won't lose IME focus.
        final int iEditText = indexOfChild(mEditText);
        if (iEditText != -1) {
            detachViewFromParent(iEditText);
        }
    }

    @Override
    public void dispatchFinishTemporaryDetach() {
        if (isAttachedToWindow()) {
            attachViewToParent(mEditText, 0, mEditText.getLayoutParams());
        } else {
            removeDetachedView(mEditText, false /* animate */);
        }
        super.dispatchFinishTemporaryDetach();
    }

    public void setWrapper(NotificationViewWrapper wrapper) {
        mWrapper = wrapper;
    }

    /**
     * Register a listener to be notified when this view's visibility changes.
     *
     * Specifically, the passed {@link Consumer} will receive {@code true} when
     * {@link #getVisibility()} would return {@link View#VISIBLE}, and {@code false} it would return
     * any other value.
     */
    public void setOnVisibilityChangedListener(Consumer<Boolean> listener) {
        mOnVisibilityChangedListener = listener;
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (changedView == this) {
            final Consumer<Boolean> visibilityChangedListener = mOnVisibilityChangedListener;
            if (visibilityChangedListener != null) {
                visibilityChangedListener.accept(visibility == VISIBLE);
            }
            // Hide soft-keyboard when the input view became invisible
            // (i.e. The notification shade collapsed by pressing the home key)
            if (visibility != VISIBLE && !mController.isRemoteInputActive()) {
                mEditText.hideIme();
            }
        }
    }

    public boolean isSending() {
        return getVisibility() == VISIBLE && mController.isSpinning(mEntry.getKey(), mToken);
    }

    /** Registers a listener for focus-change events on the EditText */
    public void addOnEditTextFocusChangedListener(View.OnFocusChangeListener listener) {
        mEditTextFocusChangeListeners.add(listener);
    }

    /** Removes a previously-added listener for focus-change events on the EditText */
    public void removeOnEditTextFocusChangedListener(View.OnFocusChangeListener listener) {
        mEditTextFocusChangeListeners.remove(listener);
    }

    /** Determines if the EditText has focus. */
    public boolean editTextHasFocus() {
        return mEditText != null && mEditText.hasFocus();
    }

    private void onEditTextFocusChanged(RemoteEditText remoteEditText, boolean focused) {
        for (View.OnFocusChangeListener listener : new ArrayList<>(mEditTextFocusChangeListeners)) {
            listener.onFocusChange(remoteEditText, focused);
        }
    }

    /** Registers a listener for send events on this RemoteInputView */
    public void addOnSendRemoteInputListener(Runnable listener) {
        mOnSendListeners.add(listener);
    }

    /** Removes a previously-added listener for send events on this RemoteInputView */
    public void removeOnSendRemoteInputListener(Runnable listener) {
        mOnSendListeners.remove(listener);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        setPivotY(getMeasuredHeight());
        if (mContentBackgroundBounds != null) {
            mContentBackground.setBounds(mContentBackgroundBounds);
        }
    }

    /**
     * @return action button container view (i.e. ViewGroup containing Reply button etc.)
     */
    public View getActionsContainerLayout() {
        ViewGroup parentView = (ViewGroup) getParent();
        if (parentView == null) return null;
        return parentView.findViewById(com.android.internal.R.id.actions_container_layout);
    }

    /**
     * Creates an animator for the focus animation.
     *
     * @param fadeOutView View that will be faded out during the focus animation.
     */
    private Animator getFocusAnimator(@Nullable View fadeOutView) {
        final AnimatorSet animatorSet = new AnimatorSet();

        final Animator alphaAnimator = ObjectAnimator.ofFloat(this, View.ALPHA, 0f, 1f);
        alphaAnimator.setStartDelay(FOCUS_ANIMATION_FADE_IN_DELAY);
        alphaAnimator.setDuration(FOCUS_ANIMATION_FADE_IN_DURATION);
        alphaAnimator.setInterpolator(InterpolatorsAndroidX.LINEAR);

        ValueAnimator scaleAnimator = ValueAnimator.ofFloat(FOCUS_ANIMATION_MIN_SCALE, 1f);
        scaleAnimator.addUpdateListener(valueAnimator -> {
            setFocusAnimationScaleY((float) scaleAnimator.getAnimatedValue());
        });
        scaleAnimator.setDuration(FOCUS_ANIMATION_TOTAL_DURATION);
        scaleAnimator.setInterpolator(InterpolatorsAndroidX.FAST_OUT_SLOW_IN);

        if (fadeOutView == null) {
            animatorSet.playTogether(alphaAnimator, scaleAnimator);
        } else {
            final Animator fadeOutViewAlphaAnimator =
                    ObjectAnimator.ofFloat(fadeOutView, View.ALPHA, 1f, 0f);
            fadeOutViewAlphaAnimator.setDuration(FOCUS_ANIMATION_CROSSFADE_DURATION);
            fadeOutViewAlphaAnimator.setInterpolator(InterpolatorsAndroidX.LINEAR);
            animatorSet.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation, boolean isReverse) {
                    fadeOutView.setAlpha(1f);
                }
            });
            animatorSet.playTogether(alphaAnimator, scaleAnimator, fadeOutViewAlphaAnimator);
        }
        return animatorSet;
    }

    /**
     * Creates an animator for the defocus animation.
     *
     * @param fadeInView View that will be faded in during the defocus animation.
     */
    private Animator getDefocusAnimator(@Nullable View fadeInView) {
        final AnimatorSet animatorSet = new AnimatorSet();

        final Animator alphaAnimator = ObjectAnimator.ofFloat(this, View.ALPHA, 1f, 0f);
        alphaAnimator.setDuration(FOCUS_ANIMATION_FADE_IN_DURATION);
        alphaAnimator.setStartDelay(DEFOCUS_ANIMATION_FADE_OUT_DELAY);
        alphaAnimator.setInterpolator(InterpolatorsAndroidX.LINEAR);

        ValueAnimator scaleAnimator = ValueAnimator.ofFloat(1f, FOCUS_ANIMATION_MIN_SCALE);
        scaleAnimator.addUpdateListener(valueAnimator -> {
            setFocusAnimationScaleY((float) scaleAnimator.getAnimatedValue());
        });
        scaleAnimator.setDuration(FOCUS_ANIMATION_TOTAL_DURATION);
        scaleAnimator.setInterpolator(InterpolatorsAndroidX.FAST_OUT_SLOW_IN);
        scaleAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation, boolean isReverse) {
                setFocusAnimationScaleY(1f /* scaleY */);
            }
        });

        if (fadeInView == null) {
            animatorSet.playTogether(alphaAnimator, scaleAnimator);
        } else {
            fadeInView.forceHasOverlappingRendering(false);
            Animator fadeInViewAlphaAnimator =
                    ObjectAnimator.ofFloat(fadeInView, View.ALPHA, 0f, 1f);
            fadeInViewAlphaAnimator.setDuration(FOCUS_ANIMATION_FADE_IN_DURATION);
            fadeInViewAlphaAnimator.setInterpolator(InterpolatorsAndroidX.LINEAR);
            fadeInViewAlphaAnimator.setStartDelay(DEFOCUS_ANIMATION_CROSSFADE_DELAY);
            animatorSet.playTogether(alphaAnimator, scaleAnimator, fadeInViewAlphaAnimator);
        }
        return animatorSet;
    }

    /**
     * Sets affected view properties for a vertical scale animation
     *
     * @param scaleY         desired vertical view scale
     */
    private void setFocusAnimationScaleY(float scaleY) {
        int verticalBoundOffset = (int) ((1f - scaleY) * 0.5f * mContentView.getHeight());
        Rect contentBackgroundBounds = new Rect(0, verticalBoundOffset, mContentView.getWidth(),
                mContentView.getHeight() - verticalBoundOffset);
        mContentBackground.setBounds(contentBackgroundBounds);
        mContentView.setBackground(mContentBackground);
        if (scaleY == 1f) {
            mContentBackgroundBounds = null;
        } else {
            mContentBackgroundBounds = contentBackgroundBounds;
        }
        setTranslationY(verticalBoundOffset);
    }

    /** Handler for button click on send action in IME. */
    private class EditorActionHandler implements TextView.OnEditorActionListener {

        @Override
        public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
            final boolean isSoftImeEvent = event == null
                    && (actionId == EditorInfo.IME_ACTION_DONE
                    || actionId == EditorInfo.IME_ACTION_NEXT
                    || actionId == EditorInfo.IME_ACTION_SEND);
            final boolean isKeyboardEnterKey = event != null
                    && KeyEvent.isConfirmKey(event.getKeyCode())
                    && event.getAction() == KeyEvent.ACTION_DOWN;

            if (isSoftImeEvent || isKeyboardEnterKey) {
                if (mEditText.length() > 0 || mEntry.remoteInputAttachment != null) {
                    sendRemoteInput();
                }
                // Consume action to prevent IME from closing.
                return true;
            }
            return false;
        }
    }

    /** Observes text change events and updates the visibility of the send button accordingly. */
    private class SendButtonTextWatcher implements TextWatcher {

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {}

        @Override
        public void afterTextChanged(Editable s) {
            updateSendButton();
        }
    }

    /**
     * An EditText that changes appearance based on whether it's focusable and becomes
     * un-focusable whenever the user navigates away from it or it becomes invisible.
     */
    public static class RemoteEditText extends EditText {

        private final OnReceiveContentListener mOnReceiveContentListener = this::onReceiveContent;

        private RemoteInputView mRemoteInputView;
        boolean mShowImeOnInputConnection;
        private final LightBarController mLightBarController;
        private InputMethodManager mInputMethodManager;
        private final ArraySet<String> mSupportedMimes = new ArraySet<>();
        UserHandle mUser;

        public RemoteEditText(Context context, AttributeSet attrs) {
            super(context, attrs);
            mLightBarController = Dependency.get(LightBarController.class);
        }

        void setSupportedMimeTypes(@Nullable Collection<String> mimeTypes) {
            String[] types = null;
            OnReceiveContentListener listener = null;
            if (mimeTypes != null && !mimeTypes.isEmpty()) {
                types = mimeTypes.toArray(new String[0]);
                listener = mOnReceiveContentListener;
            }
            setOnReceiveContentListener(types, listener);
            mSupportedMimes.clear();
            mSupportedMimes.addAll(mimeTypes);
        }

        private void hideIme() {
            Trace.beginSection("RemoteEditText#hideIme");
            final WindowInsetsController insetsController = getWindowInsetsController();
            if (insetsController != null) {
                insetsController.hide(WindowInsets.Type.ime());
            }
            Trace.endSection();
        }

        private void defocusIfNeeded(boolean animate) {
            if (mRemoteInputView != null && mRemoteInputView.mEntry.getRow().isChangingPosition()
                    || isTemporarilyDetached()) {
                if (isTemporarilyDetached()) {
                    // We might get reattached but then the other one of HUN / expanded might steal
                    // our focus, so we'll need to save our text here.
                    if (mRemoteInputView != null) {
                        mRemoteInputView.mEntry.remoteInputText = getText();
                    }
                }
                return;
            }
            if (isFocusable() && isEnabled()) {
                setInnerFocusable(false);
                if (mRemoteInputView != null) {
                    mRemoteInputView
                            .onDefocus(animate, true /* logClose */, null /* doAfterDefocus */);
                }
                mShowImeOnInputConnection = false;
            }
        }

        @Override
        protected void onVisibilityChanged(View changedView, int visibility) {
            super.onVisibilityChanged(changedView, visibility);

            if (!isShown()) {
                defocusIfNeeded(false /* animate */);
            }
        }

        @Override
        protected void onFocusChanged(boolean focused, int direction, Rect previouslyFocusedRect) {
            super.onFocusChanged(focused, direction, previouslyFocusedRect);
            if (mRemoteInputView != null) {
                mRemoteInputView.onEditTextFocusChanged(this, focused);
            }
            if (!focused) {
                defocusIfNeeded(true /* animate */);
            }
            if (mRemoteInputView != null && !mRemoteInputView.mRemoved) {
                mLightBarController.setDirectReplying(focused);
            }
        }

        @Override
        public void getFocusedRect(Rect r) {
            super.getFocusedRect(r);
            r.top = mScrollY;
            r.bottom = mScrollY + (mBottom - mTop);
        }

        @Override
        public boolean requestRectangleOnScreen(Rect rectangle) {
            return mRemoteInputView.requestScrollTo();
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                // Eat the DOWN event here to prevent any default behavior.
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }

        private final OnBackInvokedCallback mOnBackInvokedCallback = () -> {
            if (DEBUG) {
                Log.d(TAG, "Predictive Back Callback dispatched");
            }
            respondToKeycodeBack();
        };

        private void respondToKeycodeBack() {
            defocusIfNeeded(true /* animate */);
        }

        @Override
        public boolean onKeyUp(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                respondToKeycodeBack();
                return true;
            }
            return super.onKeyUp(keyCode, event);
        }

        @Override
        public boolean onKeyPreIme(int keyCode, KeyEvent event) {
            // When BACK key is pressed, this method would be invoked twice.
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK &&
                    event.getAction() == KeyEvent.ACTION_UP) {
                defocusIfNeeded(true /* animate */);
            }
            return super.onKeyPreIme(keyCode, event);
        }

        @Override
        public boolean onCheckIsTextEditor() {
            // Stop being editable while we're being removed. During removal, we get reattached,
            // and editable views get their spellchecking state re-evaluated which is too costly
            // during the removal animation.
            boolean flyingOut = mRemoteInputView != null && mRemoteInputView.mRemoved;
            return !flyingOut && super.onCheckIsTextEditor();
        }

        @Override
        public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
            final InputConnection ic = super.onCreateInputConnection(outAttrs);
            Context userContext = null;
            try {
                userContext = mContext.createPackageContextAsUser(
                        mContext.getPackageName(), 0, mUser);
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(TAG, "Unable to create user context:" + e.getMessage(), e);
            }

            if (mShowImeOnInputConnection && ic != null) {
                Context targetContext = userContext != null ? userContext : getContext();
                mInputMethodManager = targetContext.getSystemService(InputMethodManager.class);
                if (mInputMethodManager != null) {
                    // onCreateInputConnection is called by InputMethodManager in the middle of
                    // setting up the connection to the IME; wait with requesting the IME until that
                    // work has completed.
                    post(new Runnable() {
                        @Override
                        public void run() {
                            mInputMethodManager.viewClicked(RemoteEditText.this);
                            mInputMethodManager.showSoftInput(RemoteEditText.this, 0);
                        }
                    });
                }
            }

            return ic;
        }

        @Override
        public void onCommitCompletion(CompletionInfo text) {
            clearComposingText();
            setText(text.getText());
            setSelection(getText().length());
        }

        void setInnerFocusable(boolean focusable) {
            setFocusableInTouchMode(focusable);
            setFocusable(focusable);
            setCursorVisible(focusable);

            if (focusable) {
                requestFocus();
            }
        }

        private ContentInfo onReceiveContent(View view, ContentInfo payload) {
            Pair<ContentInfo, ContentInfo> split =
                    payload.partition(item -> item.getUri() != null);
            ContentInfo uriItems = split.first;
            ContentInfo remainingItems = split.second;
            if (uriItems != null) {
                mRemoteInputView.setAttachment(uriItems);
            }
            return remainingItems;
        }

    }
}
