/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.packageinstaller;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import android.content.Context;
import android.content.DialogInterface;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Message;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewParent;
import android.view.ViewStub;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.android.packageinstaller.R;

import java.lang.ref.WeakReference;

public class AlertController {
    private final Context mContext;
    private final DialogInterface mDialogInterface;
    protected final Window mWindow;

    private CharSequence mTitle;
    protected CharSequence mMessage;
    protected ListView mListView;
    private View mView;

    private int mViewLayoutResId;

    private int mViewSpacingLeft;
    private int mViewSpacingTop;
    private int mViewSpacingRight;
    private int mViewSpacingBottom;
    private boolean mViewSpacingSpecified = false;

    private Button mButtonPositive;
    private CharSequence mButtonPositiveText;
    private Message mButtonPositiveMessage;

    private Button mButtonNegative;
    private CharSequence mButtonNegativeText;
    private Message mButtonNegativeMessage;

    private Button mButtonNeutral;
    private CharSequence mButtonNeutralText;
    private Message mButtonNeutralMessage;

    protected ScrollView mScrollView;

    private int mIconId = 0;
    private Drawable mIcon;

    private ImageView mIconView;
    private TextView mTitleView;
    protected TextView mMessageView;

    private ListAdapter mAdapter;

    private int mAlertDialogLayout;
    private int mButtonPanelSideLayout;
    private int mListLayout;
    private int mMultiChoiceItemLayout;
    private int mSingleChoiceItemLayout;
    private int mListItemLayout;

    private boolean mShowTitle;

    private Handler mHandler;

    private final View.OnClickListener mButtonHandler = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            final Message m;
            if (v == mButtonPositive && mButtonPositiveMessage != null) {
                m = Message.obtain(mButtonPositiveMessage);
            } else if (v == mButtonNegative && mButtonNegativeMessage != null) {
                m = Message.obtain(mButtonNegativeMessage);
            } else if (v == mButtonNeutral && mButtonNeutralMessage != null) {
                m = Message.obtain(mButtonNeutralMessage);
            } else {
                m = null;
            }

            if (m != null) {
                m.sendToTarget();
            }

            // Post a message so we dismiss after the above handlers are executed
            mHandler.obtainMessage(ButtonHandler.MSG_DISMISS_DIALOG, mDialogInterface)
                    .sendToTarget();
        }
    };

    private static final class ButtonHandler extends Handler {
        // Button clicks have Message.what as the BUTTON{1,2,3} constant
        private static final int MSG_DISMISS_DIALOG = 1;

        private WeakReference<DialogInterface> mDialog;

        public ButtonHandler(DialogInterface dialog) {
            mDialog = new WeakReference<>(dialog);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {

                case DialogInterface.BUTTON_POSITIVE:
                case DialogInterface.BUTTON_NEGATIVE:
                case DialogInterface.BUTTON_NEUTRAL:
                    ((DialogInterface.OnClickListener) msg.obj).onClick(mDialog.get(), msg.what);
                    break;

                case MSG_DISMISS_DIALOG:
                    ((DialogInterface) msg.obj).dismiss();
            }
        }
    }

    public AlertController(Context context, DialogInterface di, Window window) {
        mContext = context;
        mDialogInterface = di;
        mWindow = window;
        mHandler = new ButtonHandler(di);

        final TypedArray a = context.obtainStyledAttributes(null,
                R.styleable.AlertDialog, R.attr.alertDialogStyle, 0);

        mAlertDialogLayout = a.getResourceId(
                R.styleable.AlertDialog_layout, R.layout.alert_dialog_material);
        mButtonPanelSideLayout = a.getResourceId(
                R.styleable.AlertDialog_buttonPanelSideLayout, 0);
        mListLayout = a.getResourceId(
                R.styleable.AlertDialog_listLayout, R.layout.select_dialog_material);

        mMultiChoiceItemLayout = a.getResourceId(
                R.styleable.AlertDialog_multiChoiceItemLayout,
                R.layout.select_dialog_multichoice_material);
        mSingleChoiceItemLayout = a.getResourceId(
                R.styleable.AlertDialog_singleChoiceItemLayout,
                R.layout.select_dialog_singlechoice_material);
        mListItemLayout = a.getResourceId(
                R.styleable.AlertDialog_listItemLayout,
                R.layout.select_dialog_item_material);
        mShowTitle = a.getBoolean(R.styleable.AlertDialog_showTitle, true);

        a.recycle();

        /* We use a custom title so never request a window title */
        window.requestFeature(Window.FEATURE_NO_TITLE);
    }

    static boolean canTextInput(View v) {
        if (v.onCheckIsTextEditor()) {
            return true;
        }

        if (!(v instanceof ViewGroup)) {
            return false;
        }

        ViewGroup vg = (ViewGroup)v;
        int i = vg.getChildCount();
        while (i > 0) {
            i--;
            v = vg.getChildAt(i);
            if (canTextInput(v)) {
                return true;
            }
        }

        return false;
    }

    public void installContent() {
        mWindow.setContentView(mAlertDialogLayout);
        setupView();
    }

    public void setTitle(CharSequence title) {
        mTitle = title;
        if (mTitleView != null) {
            mTitleView.setText(title);
        }
        mWindow.setTitle(title);
    }

    /**
     * Set the view resource to display in the dialog.
     */
    public void setView(int layoutResId) {
        mView = null;
        mViewLayoutResId = layoutResId;
        mViewSpacingSpecified = false;
    }

    /**
     * Sets a click listener or a message to be sent when the button is clicked.
     * You only need to pass one of {@code listener} or {@code msg}.
     *
     * @param whichButton Which button, can be one of
     *            {@link DialogInterface#BUTTON_POSITIVE},
     *            {@link DialogInterface#BUTTON_NEGATIVE}, or
     *            {@link DialogInterface#BUTTON_NEUTRAL}
     * @param text The text to display in positive button.
     * @param listener The {@link DialogInterface.OnClickListener} to use.
     * @param msg The {@link Message} to be sent when clicked.
     */
    public void setButton(int whichButton, CharSequence text,
            DialogInterface.OnClickListener listener, Message msg) {

        if (msg == null && listener != null) {
            msg = mHandler.obtainMessage(whichButton, listener);
        }

        switch (whichButton) {

            case DialogInterface.BUTTON_POSITIVE:
                mButtonPositiveText = text;
                mButtonPositiveMessage = msg;
                break;

            case DialogInterface.BUTTON_NEGATIVE:
                mButtonNegativeText = text;
                mButtonNegativeMessage = msg;
                break;

            case DialogInterface.BUTTON_NEUTRAL:
                mButtonNeutralText = text;
                mButtonNeutralMessage = msg;
                break;

            default:
                throw new IllegalArgumentException("Button does not exist");
        }
    }

    /**
     * Specifies the icon to display next to the alert title.
     *
     * @param resId the resource identifier of the drawable to use as the icon,
     *            or 0 for no icon
     */
    public void setIcon(int resId) {
        mIcon = null;
        mIconId = resId;

        if (mIconView != null) {
            if (resId != 0) {
                mIconView.setVisibility(View.VISIBLE);
                mIconView.setImageResource(mIconId);
            } else {
                mIconView.setVisibility(View.GONE);
            }
        }
    }

    /**
     * Specifies the icon to display next to the alert title.
     *
     * @param icon the drawable to use as the icon or null for no icon
     */
    public void setIcon(Drawable icon) {
        mIcon = icon;
        mIconId = 0;

        if (mIconView != null) {
            if (icon != null) {
                mIconView.setVisibility(View.VISIBLE);
                mIconView.setImageDrawable(icon);
            } else {
                mIconView.setVisibility(View.GONE);
            }
        }
    }

    public Button getButton(int whichButton) {
        switch (whichButton) {
            case DialogInterface.BUTTON_POSITIVE:
                return mButtonPositive;
            case DialogInterface.BUTTON_NEGATIVE:
                return mButtonNegative;
            case DialogInterface.BUTTON_NEUTRAL:
                return mButtonNeutral;
            default:
                return null;
        }
    }

    @SuppressWarnings({"UnusedDeclaration"})
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return mScrollView != null && mScrollView.executeKeyEvent(event);
    }

    @SuppressWarnings({"UnusedDeclaration"})
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return mScrollView != null && mScrollView.executeKeyEvent(event);
    }

    /**
     * Resolves whether a custom or default panel should be used. Removes the
     * default panel if a custom panel should be used. If the resolved panel is
     * a view stub, inflates before returning.
     *
     * @param customPanel the custom panel
     * @param defaultPanel the default panel
     * @return the panel to use
     */
    @Nullable
    private ViewGroup resolvePanel(@Nullable View customPanel, @Nullable View defaultPanel) {
        if (customPanel == null) {
            // Inflate the default panel, if needed.
            if (defaultPanel instanceof ViewStub) {
                defaultPanel = ((ViewStub) defaultPanel).inflate();
            }

            return (ViewGroup) defaultPanel;
        }

        // Remove the default panel entirely.
        if (defaultPanel != null) {
            final ViewParent parent = defaultPanel.getParent();
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).removeView(defaultPanel);
            }
        }

        // Inflate the custom panel, if needed.
        if (customPanel instanceof ViewStub) {
            customPanel = ((ViewStub) customPanel).inflate();
        }

        return (ViewGroup) customPanel;
    }

    private void setupView() {
        final View parentPanel = mWindow.findViewById(R.id.parentPanel);
        final View defaultTopPanel = parentPanel.findViewById(R.id.topPanel);
        final View defaultContentPanel = parentPanel.findViewById(R.id.contentPanel);
        final View defaultButtonPanel = parentPanel.findViewById(R.id.buttonPanel);

        // Install custom content before setting up the title or buttons so
        // that we can handle panel overrides.
        final ViewGroup customPanel = parentPanel.findViewById(R.id.customPanel);
        setupCustomContent(customPanel);

        final View customTopPanel = customPanel.findViewById(R.id.topPanel);
        final View customContentPanel = customPanel.findViewById(R.id.contentPanel);
        final View customButtonPanel = customPanel.findViewById(R.id.buttonPanel);

        // Resolve the correct panels and remove the defaults, if needed.
        final ViewGroup topPanel = resolvePanel(customTopPanel, defaultTopPanel);
        final ViewGroup contentPanel = resolvePanel(customContentPanel, defaultContentPanel);
        final ViewGroup buttonPanel = resolvePanel(customButtonPanel, defaultButtonPanel);

        setupContent(contentPanel);
        setupButtons(buttonPanel);
        setupTitle(topPanel);

        final boolean hasCustomPanel = customPanel != null
                && customPanel.getVisibility() != View.GONE;
        final boolean hasTopPanel = topPanel != null
                && topPanel.getVisibility() != View.GONE;
        final boolean hasButtonPanel = buttonPanel != null
                && buttonPanel.getVisibility() != View.GONE;

        if (!parentPanel.isInTouchMode()) {
            final View content = hasCustomPanel ? customPanel : contentPanel;
            if (!requestFocusForContent(content)) {
                requestFocusForDefaultButton();
            }
        }

        if (hasTopPanel) {
            // Only clip scrolling content to padding if we have a title.
            if (mScrollView != null) {
                mScrollView.setClipToPadding(true);
            }

            // Only show the divider if we have a title.
            View divider = null;
            if (mMessage != null || hasCustomPanel) {
                divider = topPanel.findViewById(R.id.titleDividerNoCustom);
            }

            if (divider != null) {
                divider.setVisibility(View.VISIBLE);
            }
        } else {
            if (contentPanel != null) {
                final View spacer = contentPanel.findViewById(R.id.textSpacerNoTitle);
                if (spacer != null) {
                    spacer.setVisibility(View.VISIBLE);
                }
            }
        }

        // Update scroll indicators as needed.
        if (!hasCustomPanel) {
            final View content = mScrollView;
            if (content != null) {
                final int indicators = (hasTopPanel ? View.SCROLL_INDICATOR_TOP : 0)
                        | (hasButtonPanel ? View.SCROLL_INDICATOR_BOTTOM : 0);
                content.setScrollIndicators(indicators,
                        View.SCROLL_INDICATOR_TOP | View.SCROLL_INDICATOR_BOTTOM);
            }
        }

        final TypedArray a = mContext.obtainStyledAttributes(
                null, R.styleable.AlertDialog, R.attr.alertDialogStyle, 0);
        setBackground(a, topPanel, contentPanel, customPanel, buttonPanel,
                hasTopPanel, hasCustomPanel, hasButtonPanel);
        a.recycle();
    }

    private boolean requestFocusForContent(View content) {
        return content != null && content.requestFocus();
    }

    private void requestFocusForDefaultButton() {
        if (mButtonPositive.getVisibility() == View.VISIBLE) {
            mButtonPositive.requestFocus();
        } else if (mButtonNegative.getVisibility() == View.VISIBLE) {
            mButtonNegative.requestFocus();
        } else if (mButtonNeutral.getVisibility() == View.VISIBLE) {
            mButtonNeutral.requestFocus();
        }
    }

    private void setupCustomContent(ViewGroup customPanel) {
        final View customView;
        if (mView != null) {
            customView = mView;
        } else if (mViewLayoutResId != 0) {
            final LayoutInflater inflater = LayoutInflater.from(mContext);
            customView = inflater.inflate(mViewLayoutResId, customPanel, false);
        } else {
            customView = null;
        }

        final boolean hasCustomView = customView != null;
        if (!hasCustomView || !canTextInput(customView)) {
            mWindow.setFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        }

        if (hasCustomView) {
            final FrameLayout custom = mWindow.findViewById(R.id.custom);
            custom.addView(customView, new LayoutParams(MATCH_PARENT, MATCH_PARENT));

            if (mViewSpacingSpecified) {
                custom.setPadding(
                        mViewSpacingLeft, mViewSpacingTop, mViewSpacingRight, mViewSpacingBottom);
            }
        } else {
            customPanel.setVisibility(View.GONE);
        }
    }

    private void setupTitle(ViewGroup topPanel) {
        mIconView = mWindow.findViewById(R.id.icon);

        final boolean hasTextTitle = !TextUtils.isEmpty(mTitle);
        if (hasTextTitle && mShowTitle) {
            // Display the title if a title is supplied, else hide it.
            mTitleView = mWindow.findViewById(R.id.alertTitle);
            mTitleView.setText(mTitle);

            // Do this last so that if the user has supplied any icons we
            // use them instead of the default ones. If the user has
            // specified 0 then make it disappear.
            if (mIconId != 0) {
                mIconView.setImageResource(mIconId);
            } else if (mIcon != null) {
                mIconView.setImageDrawable(mIcon);
            } else {
                // Apply the padding from the icon to ensure the title is
                // aligned correctly.
                mTitleView.setPadding(mIconView.getPaddingLeft(),
                        mIconView.getPaddingTop(),
                        mIconView.getPaddingRight(),
                        mIconView.getPaddingBottom());
                mIconView.setVisibility(View.GONE);
            }
        } else {
            // Hide the title template
            final View titleTemplate = mWindow.findViewById(R.id.title_template);
            titleTemplate.setVisibility(View.GONE);
            mIconView.setVisibility(View.GONE);
            topPanel.setVisibility(View.GONE);
        }
    }

    private void setupContent(ViewGroup contentPanel) {
        mScrollView = contentPanel.findViewById(R.id.scrollView);
        mScrollView.setFocusable(false);

        // Special case for users that only want to display a String
        mMessageView = contentPanel.findViewById(R.id.message);
        if (mMessageView == null) {
            return;
        }

        mMessageView.setVisibility(View.GONE);
        mScrollView.removeView(mMessageView);

        contentPanel.setVisibility(View.GONE);
    }

    private void setupButtons(ViewGroup buttonPanel) {
        int BIT_BUTTON_POSITIVE = 1;
        int BIT_BUTTON_NEGATIVE = 2;
        int BIT_BUTTON_NEUTRAL = 4;
        int whichButtons = 0;
        mButtonPositive = buttonPanel.findViewById(R.id.button1);
        mButtonPositive.setOnClickListener(mButtonHandler);

        if (TextUtils.isEmpty(mButtonPositiveText)) {
            mButtonPositive.setVisibility(View.GONE);
        } else {
            mButtonPositive.setText(mButtonPositiveText);
            mButtonPositive.setVisibility(View.VISIBLE);
            whichButtons = whichButtons | BIT_BUTTON_POSITIVE;
        }

        mButtonNegative = buttonPanel.findViewById(R.id.button2);
        mButtonNegative.setOnClickListener(mButtonHandler);

        if (TextUtils.isEmpty(mButtonNegativeText)) {
            mButtonNegative.setVisibility(View.GONE);
        } else {
            mButtonNegative.setText(mButtonNegativeText);
            mButtonNegative.setVisibility(View.VISIBLE);

            whichButtons = whichButtons | BIT_BUTTON_NEGATIVE;
        }

        mButtonNeutral = buttonPanel.findViewById(R.id.button3);
        mButtonNeutral.setOnClickListener(mButtonHandler);

        if (TextUtils.isEmpty(mButtonNeutralText)) {
            mButtonNeutral.setVisibility(View.GONE);
        } else {
            mButtonNeutral.setText(mButtonNeutralText);
            mButtonNeutral.setVisibility(View.VISIBLE);

            whichButtons = whichButtons | BIT_BUTTON_NEUTRAL;
        }

        final boolean hasButtons = whichButtons != 0;
        if (!hasButtons) {
            buttonPanel.setVisibility(View.GONE);
        }
    }

    private void setBackground(TypedArray a, View topPanel, View contentPanel, View customPanel,
            View buttonPanel, boolean hasTitle, boolean hasCustomView, boolean hasButtons) {
        int fullDark = 0;
        int topDark = 0;
        int centerDark = 0;
        int bottomDark = 0;
        int fullBright = 0;
        int topBright = 0;
        int centerBright = 0;
        int bottomBright = 0;
        int bottomMedium = 0;

        topBright = a.getResourceId(R.styleable.AlertDialog_topBright, topBright);
        topDark = a.getResourceId(R.styleable.AlertDialog_topDark, topDark);
        centerBright = a.getResourceId(R.styleable.AlertDialog_centerBright, centerBright);
        centerDark = a.getResourceId(R.styleable.AlertDialog_centerDark, centerDark);

        /* We now set the background of all of the sections of the alert.
         * First collect together each section that is being displayed along
         * with whether it is on a light or dark background, then run through
         * them setting their backgrounds.  This is complicated because we need
         * to correctly use the full, top, middle, and bottom graphics depending
         * on how many views they are and where they appear.
         */

        final View[] views = new View[4];
        final boolean[] light = new boolean[4];
        View lastView = null;
        boolean lastLight = false;

        int pos = 0;
        if (hasTitle) {
            views[pos] = topPanel;
            light[pos] = false;
            pos++;
        }

        /* The contentPanel displays either a custom text message or
         * a ListView. If it's text we should use the dark background
         * for ListView we should use the light background. PIA does not use
         * a list view. Hence, we set it to use dark background.  If neither
         * are there the contentPanel will be hidden so set it as null.
         */
        views[pos] = contentPanel.getVisibility() == View.GONE ? null : contentPanel;
        light[pos] = false;
        pos++;

        if (hasCustomView) {
            views[pos] = customPanel;
            light[pos] = false;
            pos++;
        }

        if (hasButtons) {
            views[pos] = buttonPanel;
            light[pos] = true;
        }

        boolean setView = false;
        for (pos = 0; pos < views.length; pos++) {
            final View v = views[pos];
            if (v == null) {
                continue;
            }

            if (lastView != null) {
                if (!setView) {
                    lastView.setBackgroundResource(lastLight ? topBright : topDark);
                } else {
                    lastView.setBackgroundResource(lastLight ? centerBright : centerDark);
                }
                setView = true;
            }

            lastView = v;
            lastLight = light[pos];
        }

        if (lastView != null) {
            if (setView) {
                bottomBright = a.getResourceId(R.styleable.AlertDialog_bottomBright, bottomBright);
                bottomMedium = a.getResourceId(R.styleable.AlertDialog_bottomMedium, bottomMedium);
                bottomDark = a.getResourceId(R.styleable.AlertDialog_bottomDark, bottomDark);

                // ListViews will use the Bright background, but buttons use the
                // Medium background.
                lastView.setBackgroundResource(
                        lastLight ? (hasButtons ? bottomMedium : bottomBright) : bottomDark);
            } else {
                fullBright = a.getResourceId(R.styleable.AlertDialog_fullBright, fullBright);
                fullDark = a.getResourceId(R.styleable.AlertDialog_fullDark, fullDark);

                lastView.setBackgroundResource(lastLight ? fullBright : fullDark);
            }
        }
    }
}
