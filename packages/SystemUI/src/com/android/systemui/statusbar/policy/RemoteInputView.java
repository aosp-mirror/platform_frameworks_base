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

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto;
import com.android.systemui.R;
import com.android.systemui.statusbar.ExpandableView;
import com.android.systemui.statusbar.NotificationData;
import com.android.systemui.statusbar.RemoteInputController;
import com.android.systemui.statusbar.stack.ScrollContainer;

/**
 * Host for the remote input.
 */
public class RemoteInputView extends LinearLayout implements View.OnClickListener, TextWatcher {

    private static final String TAG = "RemoteInput";

    // A marker object that let's us easily find views of this class.
    public static final Object VIEW_TAG = new Object();

    private RemoteEditText mEditText;
    private ImageButton mSendButton;
    private ProgressBar mProgressBar;
    private PendingIntent mPendingIntent;
    private RemoteInput[] mRemoteInputs;
    private RemoteInput mRemoteInput;
    private RemoteInputController mController;

    private NotificationData.Entry mEntry;

    private ScrollContainer mScrollContainer;
    private View mScrollContainerChild;
    private boolean mRemoved;

    public RemoteInputView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mProgressBar = (ProgressBar) findViewById(R.id.remote_input_progress);

        mSendButton = (ImageButton) findViewById(R.id.remote_input_send);
        mSendButton.setOnClickListener(this);

        mEditText = (RemoteEditText) getChildAt(0);
        mEditText.setOnEditorActionListener(new TextView.OnEditorActionListener() {
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
                    if (mEditText.length() > 0) {
                        sendRemoteInput();
                    }
                    // Consume action to prevent IME from closing.
                    return true;
                }
                return false;
            }
        });
        mEditText.addTextChangedListener(this);
        mEditText.setInnerFocusable(false);
        mEditText.mRemoteInputView = this;
    }

    private void sendRemoteInput() {
        Bundle results = new Bundle();
        results.putString(mRemoteInput.getResultKey(), mEditText.getText().toString());
        Intent fillInIntent = new Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        RemoteInput.addResultsToIntent(mRemoteInputs, fillInIntent,
                results);

        mEditText.setEnabled(false);
        mSendButton.setVisibility(INVISIBLE);
        mProgressBar.setVisibility(VISIBLE);
        mEntry.remoteInputText = mEditText.getText();
        mController.addSpinning(mEntry.key);
        mController.removeRemoteInput(mEntry);
        mEditText.mShowImeOnInputConnection = false;
        mController.remoteInputSent(mEntry);

        MetricsLogger.action(mContext, MetricsProto.MetricsEvent.ACTION_REMOTE_INPUT_SEND,
                mEntry.notification.getPackageName());
        try {
            mPendingIntent.send(mContext, 0, fillInIntent);
        } catch (PendingIntent.CanceledException e) {
            Log.i(TAG, "Unable to send remote input result", e);
            MetricsLogger.action(mContext, MetricsProto.MetricsEvent.ACTION_REMOTE_INPUT_FAIL,
                    mEntry.notification.getPackageName());
        }
    }

    public static RemoteInputView inflate(Context context, ViewGroup root,
            NotificationData.Entry entry,
            RemoteInputController controller) {
        RemoteInputView v = (RemoteInputView)
                LayoutInflater.from(context).inflate(R.layout.remote_input, root, false);
        v.mController = controller;
        v.mEntry = entry;
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

    public void onDefocus() {
        mController.removeRemoteInput(mEntry);
        mEntry.remoteInputText = mEditText.getText();

        // During removal, we get reattached and lose focus. Not hiding in that
        // case to prevent flicker.
        if (!mRemoved) {
            setVisibility(INVISIBLE);
        }
        MetricsLogger.action(mContext, MetricsProto.MetricsEvent.ACTION_REMOTE_INPUT_CLOSE,
                mEntry.notification.getPackageName());
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mEntry.row.isChangingPosition()) {
            if (getVisibility() == VISIBLE && mEditText.isFocusable()) {
                mEditText.requestFocus();
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mEntry.row.isChangingPosition()) {
            return;
        }
        mController.removeRemoteInput(mEntry);
        mController.removeSpinning(mEntry.key);
    }

    public void setPendingIntent(PendingIntent pendingIntent) {
        mPendingIntent = pendingIntent;
    }

    public void setRemoteInput(RemoteInput[] remoteInputs, RemoteInput remoteInput) {
        mRemoteInputs = remoteInputs;
        mRemoteInput = remoteInput;
        mEditText.setHint(mRemoteInput.getLabel());
    }

    public void focus() {
        MetricsLogger.action(mContext, MetricsProto.MetricsEvent.ACTION_REMOTE_INPUT_OPEN,
                mEntry.notification.getPackageName());

        setVisibility(VISIBLE);
        mController.addRemoteInput(mEntry);
        mEditText.setInnerFocusable(true);
        mEditText.mShowImeOnInputConnection = true;
        mEditText.setText(mEntry.remoteInputText);
        mEditText.setSelection(mEditText.getText().length());
        mEditText.requestFocus();
        updateSendButton();
    }

    public void onNotificationUpdateOrReset() {
        boolean sending = mProgressBar.getVisibility() == VISIBLE;

        if (sending) {
            // Update came in after we sent the reply, time to reset.
            reset();
        }
    }

    private void reset() {
        mEditText.getText().clear();
        mEditText.setEnabled(true);
        mSendButton.setVisibility(VISIBLE);
        mProgressBar.setVisibility(INVISIBLE);
        mController.removeSpinning(mEntry.key);
        updateSendButton();
        onDefocus();
    }

    private void updateSendButton() {
        mSendButton.setEnabled(mEditText.getText().length() != 0);
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {}

    @Override
    public void afterTextChanged(Editable s) {
        updateSendButton();
    }

    public void close() {
        mEditText.defocusIfNeeded();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            findScrollContainer();
            if (mScrollContainer != null) {
                mScrollContainer.requestDisallowLongPress();
                mScrollContainer.requestDisallowDismiss();
            }
        }
        return super.onInterceptTouchEvent(ev);
    }

    public boolean requestScrollTo() {
        findScrollContainer();
        mScrollContainer.lockScrollTo(mScrollContainerChild);
        return true;
    }

    private void findScrollContainer() {
        if (mScrollContainer == null) {
            mScrollContainerChild = null;
            ViewParent p = this;
            while (p != null) {
                if (mScrollContainerChild == null && p instanceof ExpandableView) {
                    mScrollContainerChild = (View) p;
                }
                if (p.getParent() instanceof ScrollContainer) {
                    mScrollContainer = (ScrollContainer) p.getParent();
                    if (mScrollContainerChild == null) {
                        mScrollContainerChild = (View) p;
                    }
                    break;
                }
                p = p.getParent();
            }
        }
    }

    public boolean isActive() {
        return mEditText.isFocused();
    }

    public void stealFocusFrom(RemoteInputView other) {
        other.close();
        setPendingIntent(other.mPendingIntent);
        setRemoteInput(other.mRemoteInputs, other.mRemoteInput);
        focus();
    }

    /**
     * Tries to find an action in {@param actions} that matches the current pending intent
     * of this view and updates its state to that of the found action
     *
     * @return true if a matching action was found, false otherwise
     */
    public boolean updatePendingIntentFromActions(Notification.Action[] actions) {
        boolean found = false;
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
            setRemoteInput(inputs, input);
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

    /**
     * An EditText that changes appearance based on whether it's focusable and becomes
     * un-focusable whenever the user navigates away from it or it becomes invisible.
     */
    public static class RemoteEditText extends EditText {

        private final Drawable mBackground;
        private RemoteInputView mRemoteInputView;
        boolean mShowImeOnInputConnection;

        public RemoteEditText(Context context, AttributeSet attrs) {
            super(context, attrs);
            mBackground = getBackground();
        }

        private void defocusIfNeeded() {
            if (mRemoteInputView != null && mRemoteInputView.mEntry.row.isChangingPosition()) {
                return;
            }
            if (isFocusable() && isEnabled()) {
                setInnerFocusable(false);
                if (mRemoteInputView != null) {
                    mRemoteInputView.onDefocus();
                }
                mShowImeOnInputConnection = false;
            }
        }

        @Override
        protected void onVisibilityChanged(View changedView, int visibility) {
            super.onVisibilityChanged(changedView, visibility);

            if (!isShown()) {
                defocusIfNeeded();
            }
        }

        @Override
        protected void onFocusChanged(boolean focused, int direction, Rect previouslyFocusedRect) {
            super.onFocusChanged(focused, direction, previouslyFocusedRect);
            if (!focused) {
                defocusIfNeeded();
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
        public boolean onKeyPreIme(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                defocusIfNeeded();
                final InputMethodManager imm = InputMethodManager.getInstance();
                imm.hideSoftInputFromWindow(getWindowToken(), 0);
                return true;
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
            final InputConnection inputConnection = super.onCreateInputConnection(outAttrs);

            if (mShowImeOnInputConnection && inputConnection != null) {
                final InputMethodManager imm = InputMethodManager.getInstance();
                if (imm != null) {
                    // onCreateInputConnection is called by InputMethodManager in the middle of
                    // setting up the connection to the IME; wait with requesting the IME until that
                    // work has completed.
                    post(new Runnable() {
                        @Override
                        public void run() {
                            imm.viewClicked(RemoteEditText.this);
                            imm.showSoftInput(RemoteEditText.this, 0);
                        }
                    });
                }
            }

            return inputConnection;
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
    }
}
