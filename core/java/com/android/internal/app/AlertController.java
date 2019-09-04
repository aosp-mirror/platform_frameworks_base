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

package com.android.internal.app;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import com.android.internal.R;

import android.annotation.Nullable;
import android.annotation.UnsupportedAppUsage;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Message;
import android.text.Layout;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.method.MovementMethod;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewParent;
import android.view.ViewStub;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckedTextView;
import android.widget.CursorAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SimpleCursorAdapter;
import android.widget.TextView;

import java.lang.ref.WeakReference;

public class AlertController {
    public static final int MICRO = 1;

    private final Context mContext;
    private final DialogInterface mDialogInterface;
    protected final Window mWindow;

    @UnsupportedAppUsage
    private CharSequence mTitle;
    protected CharSequence mMessage;
    protected ListView mListView;
    @UnsupportedAppUsage
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
    private MovementMethod mMessageMovementMethod;
    @Layout.HyphenationFrequency
    private Integer mMessageHyphenationFrequency;
    @UnsupportedAppUsage
    private View mCustomTitleView;

    @UnsupportedAppUsage
    private boolean mForceInverseBackground;

    private ListAdapter mAdapter;

    private int mCheckedItem = -1;

    private int mAlertDialogLayout;
    private int mButtonPanelSideLayout;
    private int mListLayout;
    private int mMultiChoiceItemLayout;
    private int mSingleChoiceItemLayout;
    private int mListItemLayout;

    private boolean mShowTitle;

    private int mButtonPanelLayoutHint = AlertDialog.LAYOUT_HINT_NONE;

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

    private static boolean shouldCenterSingleButton(Context context) {
        final TypedValue outValue = new TypedValue();
        context.getTheme().resolveAttribute(R.attr.alertDialogCenterButtons, outValue, true);
        return outValue.data != 0;
    }

    public static final AlertController create(Context context, DialogInterface di, Window window) {
        final TypedArray a = context.obtainStyledAttributes(
                null, R.styleable.AlertDialog, R.attr.alertDialogStyle,
                R.style.Theme_DeviceDefault_Settings);
        int controllerType = a.getInt(R.styleable.AlertDialog_controllerType, 0);
        a.recycle();

        switch (controllerType) {
            case MICRO:
                return new MicroAlertController(context, di, window);
            default:
                return new AlertController(context, di, window);
        }
    }

    @UnsupportedAppUsage
    protected AlertController(Context context, DialogInterface di, Window window) {
        mContext = context;
        mDialogInterface = di;
        mWindow = window;
        mHandler = new ButtonHandler(di);

        final TypedArray a = context.obtainStyledAttributes(null,
                R.styleable.AlertDialog, R.attr.alertDialogStyle, 0);

        mAlertDialogLayout = a.getResourceId(
                R.styleable.AlertDialog_layout, R.layout.alert_dialog);
        mButtonPanelSideLayout = a.getResourceId(
                R.styleable.AlertDialog_buttonPanelSideLayout, 0);
        mListLayout = a.getResourceId(
                R.styleable.AlertDialog_listLayout, R.layout.select_dialog);

        mMultiChoiceItemLayout = a.getResourceId(
                R.styleable.AlertDialog_multiChoiceItemLayout,
                R.layout.select_dialog_multichoice);
        mSingleChoiceItemLayout = a.getResourceId(
                R.styleable.AlertDialog_singleChoiceItemLayout,
                R.layout.select_dialog_singlechoice);
        mListItemLayout = a.getResourceId(
                R.styleable.AlertDialog_listItemLayout,
                R.layout.select_dialog_item);
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

    public void installContent(AlertParams params) {
        params.apply(this);
        installContent();
    }

    @UnsupportedAppUsage
    public void installContent() {
        int contentView = selectContentView();
        mWindow.setContentView(contentView);
        setupView();
    }

    private int selectContentView() {
        if (mButtonPanelSideLayout == 0) {
            return mAlertDialogLayout;
        }
        if (mButtonPanelLayoutHint == AlertDialog.LAYOUT_HINT_SIDE) {
            return mButtonPanelSideLayout;
        }
        // TODO: use layout hint side for long messages/lists
        return mAlertDialogLayout;
    }

    @UnsupportedAppUsage
    public void setTitle(CharSequence title) {
        mTitle = title;
        if (mTitleView != null) {
            mTitleView.setText(title);
        }
    }

    /**
     * @see AlertDialog.Builder#setCustomTitle(View)
     */
    @UnsupportedAppUsage
    public void setCustomTitle(View customTitleView) {
        mCustomTitleView = customTitleView;
    }

    @UnsupportedAppUsage
    public void setMessage(CharSequence message) {
        mMessage = message;
        if (mMessageView != null) {
            mMessageView.setText(message);
        }
    }

    public void setMessageMovementMethod(MovementMethod movementMethod) {
        mMessageMovementMethod = movementMethod;
        if (mMessageView != null) {
            mMessageView.setMovementMethod(movementMethod);
        }
    }

    public void setMessageHyphenationFrequency(
            @Layout.HyphenationFrequency int hyphenationFrequency) {
        mMessageHyphenationFrequency = hyphenationFrequency;
        if (mMessageView != null) {
            mMessageView.setHyphenationFrequency(hyphenationFrequency);
        }
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
     * Set the view to display in the dialog.
     */
    @UnsupportedAppUsage
    public void setView(View view) {
        mView = view;
        mViewLayoutResId = 0;
        mViewSpacingSpecified = false;
    }

    /**
     * Set the view to display in the dialog along with the spacing around that view
     */
    public void setView(View view, int viewSpacingLeft, int viewSpacingTop, int viewSpacingRight,
            int viewSpacingBottom) {
        mView = view;
        mViewLayoutResId = 0;
        mViewSpacingSpecified = true;
        mViewSpacingLeft = viewSpacingLeft;
        mViewSpacingTop = viewSpacingTop;
        mViewSpacingRight = viewSpacingRight;
        mViewSpacingBottom = viewSpacingBottom;
    }

    /**
     * Sets a hint for the best button panel layout.
     */
    public void setButtonPanelLayoutHint(int layoutHint) {
        mButtonPanelLayoutHint = layoutHint;
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
    @UnsupportedAppUsage
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
    @UnsupportedAppUsage
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
    @UnsupportedAppUsage
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

    /**
     * @param attrId the attributeId of the theme-specific drawable
     * to resolve the resourceId for.
     *
     * @return resId the resourceId of the theme-specific drawable
     */
    public int getIconAttributeResId(int attrId) {
        TypedValue out = new TypedValue();
        mContext.getTheme().resolveAttribute(attrId, out, true);
        return out.resourceId;
    }

    public void setInverseBackgroundForced(boolean forceInverseBackground) {
        mForceInverseBackground = forceInverseBackground;
    }

    @UnsupportedAppUsage
    public ListView getListView() {
        return mListView;
    }

    @UnsupportedAppUsage
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
    @UnsupportedAppUsage
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return mScrollView != null && mScrollView.executeKeyEvent(event);
    }

    @SuppressWarnings({"UnusedDeclaration"})
    @UnsupportedAppUsage
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
        final ViewGroup customPanel = (ViewGroup) parentPanel.findViewById(R.id.customPanel);
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

        // Only display the text spacer if we don't have buttons.
        if (!hasButtonPanel) {
            if (contentPanel != null) {
                final View spacer = contentPanel.findViewById(R.id.textSpacerNoButtons);
                if (spacer != null) {
                    spacer.setVisibility(View.VISIBLE);
                }
            }
            mWindow.setCloseOnTouchOutsideIfNotSet(true);
        }

        if (hasTopPanel) {
            // Only clip scrolling content to padding if we have a title.
            if (mScrollView != null) {
                mScrollView.setClipToPadding(true);
            }

            // Only show the divider if we have a title.
            View divider = null;
            if (mMessage != null || mListView != null || hasCustomPanel) {
                if (!hasCustomPanel) {
                    divider = topPanel.findViewById(R.id.titleDividerNoCustom);
                }
                if (divider == null) {
                    divider = topPanel.findViewById(R.id.titleDivider);
                }

            } else {
                divider = topPanel.findViewById(R.id.titleDividerTop);
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

        if (mListView instanceof RecycleListView) {
            ((RecycleListView) mListView).setHasDecor(hasTopPanel, hasButtonPanel);
        }

        // Update scroll indicators as needed.
        if (!hasCustomPanel) {
            final View content = mListView != null ? mListView : mScrollView;
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
            final FrameLayout custom = (FrameLayout) mWindow.findViewById(R.id.custom);
            custom.addView(customView, new LayoutParams(MATCH_PARENT, MATCH_PARENT));

            if (mViewSpacingSpecified) {
                custom.setPadding(
                        mViewSpacingLeft, mViewSpacingTop, mViewSpacingRight, mViewSpacingBottom);
            }

            if (mListView != null) {
                ((LinearLayout.LayoutParams) customPanel.getLayoutParams()).weight = 0;
            }
        } else {
            customPanel.setVisibility(View.GONE);
        }
    }

    protected void setupTitle(ViewGroup topPanel) {
        if (mCustomTitleView != null && mShowTitle) {
            // Add the custom title view directly to the topPanel layout
            final LayoutParams lp = new LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);

            topPanel.addView(mCustomTitleView, 0, lp);

            // Hide the title template
            final View titleTemplate = mWindow.findViewById(R.id.title_template);
            titleTemplate.setVisibility(View.GONE);
        } else {
            mIconView = (ImageView) mWindow.findViewById(R.id.icon);

            final boolean hasTextTitle = !TextUtils.isEmpty(mTitle);
            if (hasTextTitle && mShowTitle) {
                // Display the title if a title is supplied, else hide it.
                mTitleView = (TextView) mWindow.findViewById(R.id.alertTitle);
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
    }

    protected void setupContent(ViewGroup contentPanel) {
        mScrollView = (ScrollView) contentPanel.findViewById(R.id.scrollView);
        mScrollView.setFocusable(false);

        // Special case for users that only want to display a String
        mMessageView = (TextView) contentPanel.findViewById(R.id.message);
        if (mMessageView == null) {
            return;
        }

        if (mMessage != null) {
            mMessageView.setText(mMessage);
            if (mMessageMovementMethod != null) {
                mMessageView.setMovementMethod(mMessageMovementMethod);
            }
            if (mMessageHyphenationFrequency != null) {
                mMessageView.setHyphenationFrequency(mMessageHyphenationFrequency);
            }
        } else {
            mMessageView.setVisibility(View.GONE);
            mScrollView.removeView(mMessageView);

            if (mListView != null) {
                final ViewGroup scrollParent = (ViewGroup) mScrollView.getParent();
                final int childIndex = scrollParent.indexOfChild(mScrollView);
                scrollParent.removeViewAt(childIndex);
                scrollParent.addView(mListView, childIndex,
                        new LayoutParams(MATCH_PARENT, MATCH_PARENT));
            } else {
                contentPanel.setVisibility(View.GONE);
            }
        }
    }

    private static void manageScrollIndicators(View v, View upIndicator, View downIndicator) {
        if (upIndicator != null) {
            upIndicator.setVisibility(v.canScrollVertically(-1) ? View.VISIBLE : View.INVISIBLE);
        }
        if (downIndicator != null) {
            downIndicator.setVisibility(v.canScrollVertically(1) ? View.VISIBLE : View.INVISIBLE);
        }
    }

    protected void setupButtons(ViewGroup buttonPanel) {
        int BIT_BUTTON_POSITIVE = 1;
        int BIT_BUTTON_NEGATIVE = 2;
        int BIT_BUTTON_NEUTRAL = 4;
        int whichButtons = 0;
        mButtonPositive = (Button) buttonPanel.findViewById(R.id.button1);
        mButtonPositive.setOnClickListener(mButtonHandler);

        if (TextUtils.isEmpty(mButtonPositiveText)) {
            mButtonPositive.setVisibility(View.GONE);
        } else {
            mButtonPositive.setText(mButtonPositiveText);
            mButtonPositive.setVisibility(View.VISIBLE);
            whichButtons = whichButtons | BIT_BUTTON_POSITIVE;
        }

        mButtonNegative = (Button) buttonPanel.findViewById(R.id.button2);
        mButtonNegative.setOnClickListener(mButtonHandler);

        if (TextUtils.isEmpty(mButtonNegativeText)) {
            mButtonNegative.setVisibility(View.GONE);
        } else {
            mButtonNegative.setText(mButtonNegativeText);
            mButtonNegative.setVisibility(View.VISIBLE);

            whichButtons = whichButtons | BIT_BUTTON_NEGATIVE;
        }

        mButtonNeutral = (Button) buttonPanel.findViewById(R.id.button3);
        mButtonNeutral.setOnClickListener(mButtonHandler);

        if (TextUtils.isEmpty(mButtonNeutralText)) {
            mButtonNeutral.setVisibility(View.GONE);
        } else {
            mButtonNeutral.setText(mButtonNeutralText);
            mButtonNeutral.setVisibility(View.VISIBLE);

            whichButtons = whichButtons | BIT_BUTTON_NEUTRAL;
        }

        if (shouldCenterSingleButton(mContext)) {
            /*
             * If we only have 1 button it should be centered on the layout and
             * expand to fill 50% of the available space.
             */
            if (whichButtons == BIT_BUTTON_POSITIVE) {
                centerButton(mButtonPositive);
            } else if (whichButtons == BIT_BUTTON_NEGATIVE) {
                centerButton(mButtonNegative);
            } else if (whichButtons == BIT_BUTTON_NEUTRAL) {
                centerButton(mButtonNeutral);
            }
        }

        final boolean hasButtons = whichButtons != 0;
        if (!hasButtons) {
            buttonPanel.setVisibility(View.GONE);
        }
    }

    private void centerButton(Button button) {
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) button.getLayoutParams();
        params.gravity = Gravity.CENTER_HORIZONTAL;
        params.weight = 0.5f;
        button.setLayoutParams(params);
        View leftSpacer = mWindow.findViewById(R.id.leftSpacer);
        if (leftSpacer != null) {
            leftSpacer.setVisibility(View.VISIBLE);
        }
        View rightSpacer = mWindow.findViewById(R.id.rightSpacer);
        if (rightSpacer != null) {
            rightSpacer.setVisibility(View.VISIBLE);
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

        // If the needsDefaultBackgrounds attribute is set, we know we're
        // inheriting from a framework style.
        final boolean needsDefaultBackgrounds = a.getBoolean(
                R.styleable.AlertDialog_needsDefaultBackgrounds, true);
        if (needsDefaultBackgrounds) {
            fullDark = R.drawable.popup_full_dark;
            topDark = R.drawable.popup_top_dark;
            centerDark = R.drawable.popup_center_dark;
            bottomDark = R.drawable.popup_bottom_dark;
            fullBright = R.drawable.popup_full_bright;
            topBright = R.drawable.popup_top_bright;
            centerBright = R.drawable.popup_center_bright;
            bottomBright = R.drawable.popup_bottom_bright;
            bottomMedium = R.drawable.popup_bottom_medium;
        }

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
         * for ListView we should use the light background. If neither
         * are there the contentPanel will be hidden so set it as null.
         */
        views[pos] = contentPanel.getVisibility() == View.GONE ? null : contentPanel;
        light[pos] = mListView != null;
        pos++;

        if (hasCustomView) {
            views[pos] = customPanel;
            light[pos] = mForceInverseBackground;
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

        final ListView listView = mListView;
        if (listView != null && mAdapter != null) {
            listView.setAdapter(mAdapter);
            final int checkedItem = mCheckedItem;
            if (checkedItem > -1) {
                listView.setItemChecked(checkedItem, true);
                listView.setSelectionFromTop(checkedItem,
                        a.getDimensionPixelSize(R.styleable.AlertDialog_selectionScrollOffset, 0));
            }
        }
    }

    public static class RecycleListView extends ListView {
        private final int mPaddingTopNoTitle;
        private final int mPaddingBottomNoButtons;

        boolean mRecycleOnMeasure = true;

        @UnsupportedAppUsage
        public RecycleListView(Context context) {
            this(context, null);
        }

        @UnsupportedAppUsage
        public RecycleListView(Context context, AttributeSet attrs) {
            super(context, attrs);

            final TypedArray ta = context.obtainStyledAttributes(
                    attrs, R.styleable.RecycleListView);
            mPaddingBottomNoButtons = ta.getDimensionPixelOffset(
                    R.styleable.RecycleListView_paddingBottomNoButtons, -1);
            mPaddingTopNoTitle = ta.getDimensionPixelOffset(
                    R.styleable.RecycleListView_paddingTopNoTitle, -1);
        }

        public void setHasDecor(boolean hasTitle, boolean hasButtons) {
            if (!hasButtons || !hasTitle) {
                final int paddingLeft = getPaddingLeft();
                final int paddingTop = hasTitle ? getPaddingTop() : mPaddingTopNoTitle;
                final int paddingRight = getPaddingRight();
                final int paddingBottom = hasButtons ? getPaddingBottom() : mPaddingBottomNoButtons;
                setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom);
            }
        }

        @Override
        protected boolean recycleOnMeasure() {
            return mRecycleOnMeasure;
        }
    }

    public static class AlertParams {
        @UnsupportedAppUsage
        public final Context mContext;
        @UnsupportedAppUsage
        public final LayoutInflater mInflater;

        @UnsupportedAppUsage
        public int mIconId = 0;
        @UnsupportedAppUsage
        public Drawable mIcon;
        public int mIconAttrId = 0;
        @UnsupportedAppUsage
        public CharSequence mTitle;
        @UnsupportedAppUsage
        public View mCustomTitleView;
        @UnsupportedAppUsage
        public CharSequence mMessage;
        @UnsupportedAppUsage
        public CharSequence mPositiveButtonText;
        @UnsupportedAppUsage
        public DialogInterface.OnClickListener mPositiveButtonListener;
        @UnsupportedAppUsage
        public CharSequence mNegativeButtonText;
        @UnsupportedAppUsage
        public DialogInterface.OnClickListener mNegativeButtonListener;
        @UnsupportedAppUsage
        public CharSequence mNeutralButtonText;
        @UnsupportedAppUsage
        public DialogInterface.OnClickListener mNeutralButtonListener;
        @UnsupportedAppUsage
        public boolean mCancelable;
        @UnsupportedAppUsage
        public DialogInterface.OnCancelListener mOnCancelListener;
        @UnsupportedAppUsage
        public DialogInterface.OnDismissListener mOnDismissListener;
        @UnsupportedAppUsage
        public DialogInterface.OnKeyListener mOnKeyListener;
        @UnsupportedAppUsage
        public CharSequence[] mItems;
        @UnsupportedAppUsage
        public ListAdapter mAdapter;
        @UnsupportedAppUsage
        public DialogInterface.OnClickListener mOnClickListener;
        public int mViewLayoutResId;
        @UnsupportedAppUsage
        public View mView;
        public int mViewSpacingLeft;
        public int mViewSpacingTop;
        public int mViewSpacingRight;
        public int mViewSpacingBottom;
        public boolean mViewSpacingSpecified = false;
        @UnsupportedAppUsage
        public boolean[] mCheckedItems;
        @UnsupportedAppUsage
        public boolean mIsMultiChoice;
        @UnsupportedAppUsage
        public boolean mIsSingleChoice;
        @UnsupportedAppUsage
        public int mCheckedItem = -1;
        @UnsupportedAppUsage
        public DialogInterface.OnMultiChoiceClickListener mOnCheckboxClickListener;
        @UnsupportedAppUsage
        public Cursor mCursor;
        @UnsupportedAppUsage
        public String mLabelColumn;
        @UnsupportedAppUsage
        public String mIsCheckedColumn;
        public boolean mForceInverseBackground;
        @UnsupportedAppUsage
        public AdapterView.OnItemSelectedListener mOnItemSelectedListener;
        public OnPrepareListViewListener mOnPrepareListViewListener;
        public boolean mRecycleOnMeasure = true;

        /**
         * Interface definition for a callback to be invoked before the ListView
         * will be bound to an adapter.
         */
        public interface OnPrepareListViewListener {

            /**
             * Called before the ListView is bound to an adapter.
             * @param listView The ListView that will be shown in the dialog.
             */
            void onPrepareListView(ListView listView);
        }

        @UnsupportedAppUsage
        public AlertParams(Context context) {
            mContext = context;
            mCancelable = true;
            mInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        }

        @UnsupportedAppUsage
        public void apply(AlertController dialog) {
            if (mCustomTitleView != null) {
                dialog.setCustomTitle(mCustomTitleView);
            } else {
                if (mTitle != null) {
                    dialog.setTitle(mTitle);
                }
                if (mIcon != null) {
                    dialog.setIcon(mIcon);
                }
                if (mIconId != 0) {
                    dialog.setIcon(mIconId);
                }
                if (mIconAttrId != 0) {
                    dialog.setIcon(dialog.getIconAttributeResId(mIconAttrId));
                }
            }
            if (mMessage != null) {
                dialog.setMessage(mMessage);
            }
            if (mPositiveButtonText != null) {
                dialog.setButton(DialogInterface.BUTTON_POSITIVE, mPositiveButtonText,
                        mPositiveButtonListener, null);
            }
            if (mNegativeButtonText != null) {
                dialog.setButton(DialogInterface.BUTTON_NEGATIVE, mNegativeButtonText,
                        mNegativeButtonListener, null);
            }
            if (mNeutralButtonText != null) {
                dialog.setButton(DialogInterface.BUTTON_NEUTRAL, mNeutralButtonText,
                        mNeutralButtonListener, null);
            }
            if (mForceInverseBackground) {
                dialog.setInverseBackgroundForced(true);
            }
            // For a list, the client can either supply an array of items or an
            // adapter or a cursor
            if ((mItems != null) || (mCursor != null) || (mAdapter != null)) {
                createListView(dialog);
            }
            if (mView != null) {
                if (mViewSpacingSpecified) {
                    dialog.setView(mView, mViewSpacingLeft, mViewSpacingTop, mViewSpacingRight,
                            mViewSpacingBottom);
                } else {
                    dialog.setView(mView);
                }
            } else if (mViewLayoutResId != 0) {
                dialog.setView(mViewLayoutResId);
            }

            /*
            dialog.setCancelable(mCancelable);
            dialog.setOnCancelListener(mOnCancelListener);
            if (mOnKeyListener != null) {
                dialog.setOnKeyListener(mOnKeyListener);
            }
            */
        }

        private void createListView(final AlertController dialog) {
            final RecycleListView listView =
                    (RecycleListView) mInflater.inflate(dialog.mListLayout, null);
            final ListAdapter adapter;

            if (mIsMultiChoice) {
                if (mCursor == null) {
                    adapter = new ArrayAdapter<CharSequence>(
                            mContext, dialog.mMultiChoiceItemLayout, R.id.text1, mItems) {
                        @Override
                        public View getView(int position, View convertView, ViewGroup parent) {
                            View view = super.getView(position, convertView, parent);
                            if (mCheckedItems != null) {
                                boolean isItemChecked = mCheckedItems[position];
                                if (isItemChecked) {
                                    listView.setItemChecked(position, true);
                                }
                            }
                            return view;
                        }
                    };
                } else {
                    adapter = new CursorAdapter(mContext, mCursor, false) {
                        private final int mLabelIndex;
                        private final int mIsCheckedIndex;

                        {
                            final Cursor cursor = getCursor();
                            mLabelIndex = cursor.getColumnIndexOrThrow(mLabelColumn);
                            mIsCheckedIndex = cursor.getColumnIndexOrThrow(mIsCheckedColumn);
                        }

                        @Override
                        public void bindView(View view, Context context, Cursor cursor) {
                            CheckedTextView text = (CheckedTextView) view.findViewById(R.id.text1);
                            text.setText(cursor.getString(mLabelIndex));
                            listView.setItemChecked(
                                    cursor.getPosition(),
                                    cursor.getInt(mIsCheckedIndex) == 1);
                        }

                        @Override
                        public View newView(Context context, Cursor cursor, ViewGroup parent) {
                            return mInflater.inflate(dialog.mMultiChoiceItemLayout,
                                    parent, false);
                        }

                    };
                }
            } else {
                final int layout;
                if (mIsSingleChoice) {
                    layout = dialog.mSingleChoiceItemLayout;
                } else {
                    layout = dialog.mListItemLayout;
                }

                if (mCursor != null) {
                    adapter = new SimpleCursorAdapter(mContext, layout, mCursor,
                            new String[] { mLabelColumn }, new int[] { R.id.text1 });
                } else if (mAdapter != null) {
                    adapter = mAdapter;
                } else {
                    adapter = new CheckedItemAdapter(mContext, layout, R.id.text1, mItems);
                }
            }

            if (mOnPrepareListViewListener != null) {
                mOnPrepareListViewListener.onPrepareListView(listView);
            }

            /* Don't directly set the adapter on the ListView as we might
             * want to add a footer to the ListView later.
             */
            dialog.mAdapter = adapter;
            dialog.mCheckedItem = mCheckedItem;

            if (mOnClickListener != null) {
                listView.setOnItemClickListener(new OnItemClickListener() {
                    @Override
                    public void onItemClick(AdapterView<?> parent, View v, int position, long id) {
                        mOnClickListener.onClick(dialog.mDialogInterface, position);
                        if (!mIsSingleChoice) {
                            dialog.mDialogInterface.dismiss();
                        }
                    }
                });
            } else if (mOnCheckboxClickListener != null) {
                listView.setOnItemClickListener(new OnItemClickListener() {
                    @Override
                    public void onItemClick(AdapterView<?> parent, View v, int position, long id) {
                        if (mCheckedItems != null) {
                            mCheckedItems[position] = listView.isItemChecked(position);
                        }
                        mOnCheckboxClickListener.onClick(
                                dialog.mDialogInterface, position, listView.isItemChecked(position));
                    }
                });
            }

            // Attach a given OnItemSelectedListener to the ListView
            if (mOnItemSelectedListener != null) {
                listView.setOnItemSelectedListener(mOnItemSelectedListener);
            }

            if (mIsSingleChoice) {
                listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
            } else if (mIsMultiChoice) {
                listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
            }
            listView.mRecycleOnMeasure = mRecycleOnMeasure;
            dialog.mListView = listView;
        }
    }

    private static class CheckedItemAdapter extends ArrayAdapter<CharSequence> {
        public CheckedItemAdapter(Context context, int resource, int textViewResourceId,
                CharSequence[] objects) {
            super(context, resource, textViewResourceId, objects);
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }
    }
}
