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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutManager;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.BlendMode;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.Editable;
import android.text.SpannedString;
import android.text.TextUtils;
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
import android.view.ViewAnimationUtils;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import android.view.accessibility.AccessibilityEvent;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.android.internal.graphics.ColorUtils;
import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.util.ContrastColorUtil;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.RemoteInputController;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntry.EditedSuggestionInfo;
import com.android.systemui.statusbar.notification.row.wrapper.NotificationViewWrapper;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;
import com.android.systemui.statusbar.phone.LightBarController;
import com.android.wm.shell.animation.Interpolators;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;

/**
 * Host for the remote input.
 */
public class RemoteInputView extends LinearLayout implements View.OnClickListener {

    private static final String TAG = "RemoteInput";

    // A marker object that let's us easily find views of this class.
    public static final Object VIEW_TAG = new Object();

    public final Object mToken = new Object();

    private final SendButtonTextWatcher mTextWatcher;
    private final TextView.OnEditorActionListener mEditorActionHandler;
    private final NotificationRemoteInputManager mRemoteInputManager;
    private final UiEventLogger mUiEventLogger;
    private final List<OnFocusChangeListener> mEditTextFocusChangeListeners = new ArrayList<>();
    private final List<OnSendRemoteInputListener> mOnSendListeners = new ArrayList<>();
    private RemoteEditText mEditText;
    private ImageButton mSendButton;
    private GradientDrawable mContentBackground;
    private ProgressBar mProgressBar;
    private PendingIntent mPendingIntent;
    private RemoteInput[] mRemoteInputs;
    private RemoteInput mRemoteInput;
    private RemoteInputController mController;
    private RemoteInputQuickSettingsDisabler mRemoteInputQuickSettingsDisabler;

    private IStatusBarService mStatusBarManagerService;

    private NotificationEntry mEntry;

    private boolean mRemoved;

    private int mRevealCx;
    private int mRevealCy;
    private int mRevealR;
    private ContentInfo mAttachment;

    private boolean mColorized;
    private int mTint;

    private boolean mResetting;
    private NotificationViewWrapper mWrapper;
    private Consumer<Boolean> mOnVisibilityChangedListener;
    private NotificationRemoteInputManager.BouncerChecker mBouncerChecker;
    private LinearLayout mContentView;
    private ImageView mDelete;
    private ImageView mDeleteBg;

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
        NOTIFICATION_REMOTE_INPUT_FAILURE(798);

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
        mRemoteInputQuickSettingsDisabler = Dependency.get(RemoteInputQuickSettingsDisabler.class);
        mRemoteInputManager = Dependency.get(NotificationRemoteInputManager.class);
        mUiEventLogger = Dependency.get(UiEventLogger.class);
        mStatusBarManagerService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));
        TypedArray ta = getContext().getTheme().obtainStyledAttributes(new int[]{
                com.android.internal.R.attr.colorAccent,
                com.android.internal.R.attr.colorSurface,
        });
        mTint = ta.getColor(0, 0);
        ta.recycle();
    }

    /**
     * The remote view needs to adapt to colorized notifications when set
     * It overrides the background of itself as well as all of its childern
     * @param backgroundColor colorized notification color
     */
    public void setBackgroundTintColor(final int backgroundColor, boolean colorized) {
        if (colorized == mColorized && backgroundColor == mTint) return;
        mColorized = colorized;
        mTint = backgroundColor;
        final int editBgColor;
        final int alternateBgColor;
        final int alternateTextColor;
        final int accentColor;
        final int textColor;
        final int hintTextColor;
        final int stroke = mContext.getResources().getDimensionPixelSize(
                R.dimen.remote_input_view_text_stroke);
        if (colorized) {
            final boolean dark = !ContrastColorUtil.isColorLight(backgroundColor);
            final int foregroundColor = dark ? Color.WHITE : Color.BLACK;
            editBgColor = backgroundColor;
            accentColor = foregroundColor;
            alternateBgColor = foregroundColor;
            alternateTextColor = backgroundColor;
            textColor = foregroundColor;
            hintTextColor = ColorUtils.setAlphaComponent(foregroundColor, 0x99);
        } else {
            textColor = mContext.getColor(R.color.remote_input_text);
            hintTextColor = mContext.getColor(R.color.remote_input_hint);
            try (TypedArray ta = getContext().getTheme().obtainStyledAttributes(new int[]{
                    com.android.internal.R.attr.colorAccent,
                    com.android.internal.R.attr.colorSurface,
                    com.android.internal.R.attr.colorSurfaceVariant,
                    com.android.internal.R.attr.textColorPrimary
            })) {
                accentColor = ta.getColor(0, textColor);
                editBgColor = ta.getColor(1, backgroundColor);
                alternateBgColor = ta.getColor(2, textColor);
                alternateTextColor = ta.getColor(3, backgroundColor);
            }
        }
        mEditText.setAllColors(backgroundColor, editBgColor,
                accentColor, textColor, hintTextColor);
        final ColorStateList accentTint = new ColorStateList(new int[][]{
                new int[]{com.android.internal.R.attr.state_enabled},
                new int[]{},
        }, new int[]{
                accentColor,
                accentColor & 0x4DFFFFFF // %30 opacity
        });
        mContentBackground.setColor(editBgColor);
        mContentBackground.setStroke(stroke, accentTint);
        mDelete.setImageTintList(ColorStateList.valueOf(alternateTextColor));
        mDeleteBg.setImageTintList(ColorStateList.valueOf(alternateBgColor));
        mSendButton.setImageTintList(accentTint);
        mProgressBar.setProgressTintList(accentTint);
        mProgressBar.setIndeterminateTintList(accentTint);
        mProgressBar.setSecondaryProgressTintList(accentTint);
        setBackgroundColor(editBgColor);
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
                    mEntry.mRemoteEditImeVisible =
                            mEditText.getRootWindowInsets().isVisible(WindowInsets.Type.ime());
                    if (!mEntry.mRemoteEditImeVisible && !mEditText.mShowImeOnInputConnection) {
                        mController.removeRemoteInput(mEntry, mToken);
                    }
                }
            }
        });
    }

    private void setAttachment(ContentInfo item) {
        if (mAttachment != null) {
            // We need to release permissions when sending the attachment to the target
            // app or if it is deleted by the user. When sending to the target app, we
            // can safely release permissions as soon as the call to
            // `mController.grantInlineReplyUriPermission` is made (ie, after the grant
            // to the target app has been created).
            mAttachment.releasePermissions();
        }
        mAttachment = item;
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
        }
    }

    /**
     * Reply intent
     * @return returns intent with granted URI permissions that should be used immediately
     */
    private Intent prepareRemoteInput() {
        if (mAttachment == null) return prepareRemoteInputFromText();
        return prepareRemoteInputFromData(
                mAttachment.getClip().getDescription().getMimeType(0),
                mAttachment.getClip().getItemAt(0).getUri());
    }

    private Intent prepareRemoteInputFromText() {
        Bundle results = new Bundle();
        results.putString(mRemoteInput.getResultKey(), mEditText.getText().toString());
        Intent fillInIntent = new Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        RemoteInput.addResultsToIntent(mRemoteInputs, fillInIntent,
                results);

        mEntry.remoteInputText = mEditText.getText();
        mEntry.remoteInputUri = null;
        mEntry.remoteInputMimeType = null;

        if (mEntry.editedSuggestionInfo == null) {
            RemoteInput.setResultsSource(fillInIntent, RemoteInput.SOURCE_FREE_FORM_INPUT);
        } else {
            RemoteInput.setResultsSource(fillInIntent, RemoteInput.SOURCE_CHOICE);
        }

        return fillInIntent;
    }

    private Intent prepareRemoteInputFromData(String contentType, Uri data) {
        HashMap<String, Uri> results = new HashMap<>();
        results.put(contentType, data);
        // grant for the target app.
        mController.grantInlineReplyUriPermission(mEntry.getSbn(), data);
        Intent fillInIntent = new Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        RemoteInput.addDataResultToIntent(mRemoteInput, fillInIntent, results);

        Bundle bundle = new Bundle();
        bundle.putString(mRemoteInput.getResultKey(), mEditText.getText().toString());
        RemoteInput.addResultsToIntent(mRemoteInputs, fillInIntent,
                bundle);

        CharSequence attachmentText = mAttachment.getClip().getDescription().getLabel();

        CharSequence attachmentLabel = TextUtils.isEmpty(attachmentText)
                ? mContext.getString(R.string.remote_input_image_insertion_text)
                : attachmentText;
        // add content description to reply text for context
        CharSequence fullText = TextUtils.isEmpty(mEditText.getText())
                ? attachmentLabel
                : "\"" + attachmentLabel + "\" " + mEditText.getText();

        mEntry.remoteInputText = fullText;
        mEntry.remoteInputMimeType = contentType;
        mEntry.remoteInputUri = data;

        // mirror prepareRemoteInputFromText for text input
        if (mEntry.editedSuggestionInfo == null) {
            RemoteInput.setResultsSource(fillInIntent, RemoteInput.SOURCE_FREE_FORM_INPUT);
        } else if (mAttachment == null) {
            RemoteInput.setResultsSource(fillInIntent, RemoteInput.SOURCE_CHOICE);
        }

        return fillInIntent;
    }

    private void sendRemoteInput(Intent intent) {
        if (mBouncerChecker != null && mBouncerChecker.showBouncerIfNecessary()) {
            mEditText.hideIme();
            for (OnSendRemoteInputListener listener : mOnSendListeners) {
                listener.onSendRequestBounced();
            }
            return;
        }

        mEditText.setEnabled(false);
        mSendButton.setVisibility(INVISIBLE);
        mProgressBar.setVisibility(VISIBLE);
        mEntry.lastRemoteInputSent = SystemClock.elapsedRealtime();
        mController.addSpinning(mEntry.getKey(), mToken);
        mController.removeRemoteInput(mEntry, mToken);
        mEditText.mShowImeOnInputConnection = false;
        mController.remoteInputSent(mEntry);
        mEntry.setHasSentReply();

        for (OnSendRemoteInputListener listener : mOnSendListeners) {
            listener.onSendRemoteInput();
        }

        // Tell ShortcutManager that this package has been "activated".  ShortcutManager
        // will reset the throttling for this package.
        // Strictly speaking, the intent receiver may be different from the notification publisher,
        // but that's an edge case, and also because we can't always know which package will receive
        // an intent, so we just reset for the publisher.
        getContext().getSystemService(ShortcutManager.class).onApplicationActive(
                mEntry.getSbn().getPackageName(),
                mEntry.getSbn().getUser().getIdentifier());

        mUiEventLogger.logWithInstanceId(
                NotificationRemoteInputEvent.NOTIFICATION_REMOTE_INPUT_SEND,
                mEntry.getSbn().getUid(), mEntry.getSbn().getPackageName(),
                mEntry.getSbn().getInstanceId());
        try {
            mPendingIntent.send(mContext, 0, intent);
        } catch (PendingIntent.CanceledException e) {
            Log.i(TAG, "Unable to send remote input result", e);
            mUiEventLogger.logWithInstanceId(
                    NotificationRemoteInputEvent.NOTIFICATION_REMOTE_INPUT_FAILURE,
                    mEntry.getSbn().getUid(), mEntry.getSbn().getPackageName(),
                    mEntry.getSbn().getInstanceId());
        }
        setAttachment(null);
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
            sendRemoteInput(prepareRemoteInput());
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        super.onTouchEvent(event);

        // We never want for a touch to escape to an outer view or one we covered.
        return true;
    }

    private void onDefocus(boolean animate, boolean logClose) {
        mController.removeRemoteInput(mEntry, mToken);
        mEntry.remoteInputText = mEditText.getText();

        // During removal, we get reattached and lose focus. Not hiding in that
        // case to prevent flicker.
        if (!mRemoved) {
            if (animate && mRevealR > 0) {
                Animator reveal = ViewAnimationUtils.createCircularReveal(
                        this, mRevealCx, mRevealCy, mRevealR, 0);
                reveal.setInterpolator(Interpolators.FAST_OUT_LINEAR_IN);
                reveal.setDuration(StackStateAnimator.ANIMATION_DURATION_CLOSE_REMOTE_INPUT);
                reveal.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        setVisibility(GONE);
                        if (mWrapper != null) {
                            mWrapper.setRemoteInputVisible(false);
                        }
                    }
                });
                reveal.start();
            } else {
                setVisibility(GONE);
                if (mWrapper != null) {
                    mWrapper.setRemoteInputVisible(false);
                }
            }
        }

        mRemoteInputQuickSettingsDisabler.setRemoteInputActive(false);

        if (logClose) {
            mUiEventLogger.logWithInstanceId(
                    NotificationRemoteInputEvent.NOTIFICATION_REMOTE_INPUT_CLOSE,
                    mEntry.getSbn().getUid(), mEntry.getSbn().getPackageName(),
                    mEntry.getSbn().getInstanceId());
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mEditText.mRemoteInputView = this;
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
        mController.removeRemoteInput(mEntry, mToken);
        mController.removeSpinning(mEntry.getKey(), mToken);
    }

    public void setPendingIntent(PendingIntent pendingIntent) {
        mPendingIntent = pendingIntent;
    }

    /**
     * Sets the remote input for this view.
     *
     * @param remoteInputs The remote inputs that need to be sent to the app.
     * @param remoteInput The remote input that needs to be activated.
     * @param editedSuggestionInfo The smart reply that should be inserted in the remote input, or
     *         {@code null} if the user is not editing a smart reply.
     */
    public void setRemoteInput(RemoteInput[] remoteInputs, RemoteInput remoteInput,
            @Nullable EditedSuggestionInfo editedSuggestionInfo) {
        mRemoteInputs = remoteInputs;
        mRemoteInput = remoteInput;
        mEditText.setHint(mRemoteInput.getLabel());
        mEditText.setSupportedMimeTypes(remoteInput.getAllowedDataTypes());

        mEntry.editedSuggestionInfo = editedSuggestionInfo;
        if (editedSuggestionInfo != null) {
            mEntry.remoteInputText = editedSuggestionInfo.originalText;
        }
    }

    /** Populates the text field of the remote input with the given content. */
    public void setEditTextContent(@Nullable CharSequence editTextContent) {
        mEditText.setText(editTextContent);
    }

    public void focusAnimated() {
        if (getVisibility() != VISIBLE) {
            Animator animator = ViewAnimationUtils.createCircularReveal(
                    this, mRevealCx, mRevealCy, 0, mRevealR);
            animator.setDuration(StackStateAnimator.ANIMATION_DURATION_STANDARD);
            animator.setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN);
            animator.start();
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
        mEditText.setSelection(mEditText.getText().length());
        mEditText.requestFocus();
        mController.addRemoteInput(mEntry, mToken);

        mRemoteInputQuickSettingsDisabler.setRemoteInputActive(true);

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
        mResetting = true;
        mEntry.remoteInputTextWhenReset = SpannedString.valueOf(mEditText.getText());

        mEditText.getText().clear();
        mEditText.setEnabled(true);
        mSendButton.setVisibility(VISIBLE);
        mProgressBar.setVisibility(INVISIBLE);
        mController.removeSpinning(mEntry.getKey(), mToken);
        updateSendButton();
        onDefocus(false /* animate */, false /* logClose */);

        mResetting = false;
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
        mSendButton.setEnabled(mEditText.getText().length() != 0);
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

    public void stealFocusFrom(RemoteInputView other) {
        other.close();
        setPendingIntent(other.mPendingIntent);
        setRemoteInput(other.mRemoteInputs, other.mRemoteInput, mEntry.editedSuggestionInfo);
        setRevealParameters(other.mRevealCx, other.mRevealCy, other.mRevealR);
        focus();
    }

    /**
     * Tries to find an action in {@param actions} that matches the current pending intent
     * of this view and updates its state to that of the found action
     *
     * @return true if a matching action was found, false otherwise
     */
    public boolean updatePendingIntentFromActions(Notification.Action[] actions) {
        if (mPendingIntent == null || actions == null) {
            return false;
        }
        Intent current = mPendingIntent.getIntent();
        if (current == null) {
            return false;
        }

        for (Notification.Action a : actions) {
            RemoteInput[] inputs = a.getRemoteInputs();
            if (a.actionIntent == null || inputs == null) {
                continue;
            }
            Intent candidate = a.actionIntent.getIntent();
            if (!current.filterEquals(candidate)) {
                continue;
            }

            RemoteInput input = null;
            for (RemoteInput i : inputs) {
                if (i.getAllowFreeFormInput()) {
                    input = i;
                }
            }
            if (input == null) {
                continue;
            }
            setPendingIntent(a.actionIntent);
            setRemoteInput(inputs, input, null /* editedSuggestionInfo*/);
            return true;
        }
        return false;
    }

    public PendingIntent getPendingIntent() {
        return mPendingIntent;
    }

    public void setRemoved() {
        mRemoved = true;
    }

    public void setRevealParameters(int cx, int cy, int r) {
        mRevealCx = cx;
        mRevealCy = cy;
        mRevealR = r;
    }

    @Override
    public void dispatchStartTemporaryDetach() {
        super.dispatchStartTemporaryDetach();
        // Detach the EditText temporarily such that it doesn't get onDetachedFromWindow and
        // won't lose IME focus.
        detachViewFromParent(mEditText);
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

    public void setOnVisibilityChangedListener(Consumer<Boolean> visibilityChangedListener) {
        mOnVisibilityChangedListener = visibilityChangedListener;
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (changedView == this && mOnVisibilityChangedListener != null) {
            mOnVisibilityChangedListener.accept(visibility == VISIBLE);
            // Hide soft-keyboard when the input view became invisible
            // (i.e. The notification shade collapsed by pressing the home key)
            if (visibility != VISIBLE && !mEditText.isVisibleToUser()) {
                mEditText.hideIme();
            }
        }
    }

    public boolean isSending() {
        return getVisibility() == VISIBLE && mController.isSpinning(mEntry.getKey(), mToken);
    }

    /**
     * Sets a {@link com.android.systemui.statusbar.NotificationRemoteInputManager.BouncerChecker}
     * that will be used to determine if the device needs to be unlocked before sending the
     * RemoteInput.
     */
    public void setBouncerChecker(
            @Nullable NotificationRemoteInputManager.BouncerChecker bouncerChecker) {
        mBouncerChecker = bouncerChecker;
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
        for (View.OnFocusChangeListener listener : mEditTextFocusChangeListeners) {
            listener.onFocusChange(remoteEditText, focused);
        }
    }

    /** Registers a listener for send events on this RemoteInputView */
    public void addOnSendRemoteInputListener(OnSendRemoteInputListener listener) {
        mOnSendListeners.add(listener);
    }

    /** Removes a previously-added listener for send events on this RemoteInputView */
    public void removeOnSendRemoteInputListener(OnSendRemoteInputListener listener) {
        mOnSendListeners.remove(listener);
    }

    /** Listener for send events */
    public interface OnSendRemoteInputListener {
        /** Invoked when the remote input has been sent successfully. */
        void onSendRemoteInput();
        /**
         * Invoked when the user had requested to send the remote input, but authentication was
         * required and the bouncer was shown instead.
         */
        void onSendRequestBounced();
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
                if (mEditText.length() > 0 || mAttachment != null) {
                    sendRemoteInput(prepareRemoteInput());
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
        private ColorDrawable mBackground;
        boolean mShowImeOnInputConnection;
        private LightBarController mLightBarController;
        private InputMethodManager mInputMethodManager;
        private ArraySet<String> mSupportedMimes = new ArraySet<>();
        UserHandle mUser;

        public RemoteEditText(Context context, AttributeSet attrs) {
            super(context, attrs);
            mLightBarController = Dependency.get(LightBarController.class);

            mBackground = new ColorDrawable();
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
            if (mInputMethodManager != null) {
                mInputMethodManager.hideSoftInputFromWindow(getWindowToken(), 0);
            }
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
                    mRemoteInputView.onDefocus(animate, true /* logClose */);
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

        @Override
        public boolean onKeyUp(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                defocusIfNeeded(true /* animate */);
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
                setBackground(mBackground);
            } else {
                setBackground(null);
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

        protected void setAllColors(int backgroundColor, int editBackgroundColor,
                int accentColor, int textColor, int hintTextColor) {
            setBackgroundColor(editBackgroundColor);
            mBackground.setColor(editBackgroundColor);
            setTextColor(textColor);
            setHintTextColor(hintTextColor);
            getTextCursorDrawable().setColorFilter(accentColor, PorterDuff.Mode.SRC_IN);
        }
    }
}
