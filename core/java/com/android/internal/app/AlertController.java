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

import static android.view.ViewGroup.LayoutParams.FILL_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.TypedArray;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Message;
import android.text.TextUtils;
import android.text.TextUtils.TruncateAt;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.ViewGroup.LayoutParams;
import android.widget.AdapterView;
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
import android.widget.AdapterView.OnItemClickListener;

import com.android.internal.R;

import java.lang.ref.WeakReference;

public class AlertController {

    private final Context mContext;
    private final DialogInterface mDialogInterface;
    private final Window mWindow;
    
    private CharSequence mTitle;

    private CharSequence mMessage;

    private ListView mListView;
    
    private View mView;

    private Button mButton1;

    private CharSequence mButton1Text;

    private Message mButton1Message;

    private Button mButton2;

    private CharSequence mButton2Text;

    private Message mButton2Message;

    private Button mButton3;

    private CharSequence mButton3Text;

    private Message mButton3Message;

    private ScrollView mScrollView;
    
    private int mIconId = -1;
    
    private Drawable mIcon;
    
    private ImageView mIconView;
    
    private TextView mTitleView;

    private TextView mMessageView;

    private View mCustomTitleView;
    
    private boolean mForceInverseBackground;
    
    private ListAdapter mAdapter;
    
    private int mCheckedItem = -1;

    private Handler mHandler;

    View.OnClickListener mButtonHandler = new View.OnClickListener() {
        public void onClick(View v) {
            Message m = null;
            if (v == mButton1 && mButton1Message != null) {
                m = Message.obtain(mButton1Message);
            } else if (v == mButton2 && mButton2Message != null) {
                m = Message.obtain(mButton2Message);
            } else if (v == mButton3 && mButton3Message != null) {
                m = Message.obtain(mButton3Message);
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
            mDialog = new WeakReference<DialogInterface>(dialog);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                
                case DialogInterface.BUTTON1:
                case DialogInterface.BUTTON2:
                case DialogInterface.BUTTON3:
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
    }
    
    public void installContent() {
        /* We use a custom title so never request a window title */
        mWindow.requestFeature(Window.FEATURE_NO_TITLE);
        
        mWindow.setContentView(com.android.internal.R.layout.alert_dialog);
        setupView();
    }
    
    public void setTitle(CharSequence title) {
        mTitle = title;
        if (mTitleView != null) {
            mTitleView.setText(title);
        }
    }

    /**
     * @see AlertDialog.Builder#setCustomTitle(View)
     */
    public void setCustomTitle(View customTitleView) {
        mCustomTitleView = customTitleView;
    }
    
    public void setMessage(CharSequence message) {
        mMessage = message;
        if (mMessageView != null) {
            mMessageView.setText(message);
        }
    }

    /**
     * Set the view to display in that dialog.
     */
    public void setView(View view) {
        mView = view;
    }

    public void setButton(CharSequence text, Message msg) {
        mButton1Text = text;
        mButton1Message = msg;
    }

    public void setButton2(CharSequence text, Message msg) {
        mButton2Text = text;
        mButton2Message = msg;
    }

    public void setButton3(CharSequence text, Message msg) {
        mButton3Text = text;
        mButton3Message = msg;
    }

    /**
     * Set a listener to be invoked when button 1 of the dialog is pressed.
     * @param text The text to display in button 1.
     * @param listener The {@link DialogInterface.OnClickListener} to use.
     */
    public void setButton(CharSequence text, final DialogInterface.OnClickListener listener) {
        mButton1Text = text;
        if (listener != null) {
            mButton1Message = mHandler.obtainMessage(DialogInterface.BUTTON1, listener);
        } else {
            mButton1Message = null;
        }
    }

    /**
     * Set a listener to be invoked when button 2 of the dialog is pressed.
     * @param text The text to display in button 2.
     * @param listener The {@link DialogInterface.OnClickListener} to use.
     */
    public void setButton2(CharSequence text, final DialogInterface.OnClickListener listener) {
        mButton2Text = text;
        if (listener != null) {
            mButton2Message = mHandler.obtainMessage(DialogInterface.BUTTON2, listener);
        } else {
            mButton2Message = null;
        }
    }

    /**
     * Set a listener to be invoked when button 3 of the dialog is pressed.
     * @param text The text to display in button 3.
     * @param listener The {@link DialogInterface.OnClickListener} to use.
     */
    public void setButton3(CharSequence text, final DialogInterface.OnClickListener listener) {
        mButton3Text = text;
        if (listener != null) {
            mButton3Message = mHandler.obtainMessage(DialogInterface.BUTTON3, listener);
        } else {
            mButton3Message = null;
        }
    }

    /**
     * Set resId to 0 if you don't want an icon.
     * @param resId the resourceId of the drawable to use as the icon or 0
     * if you don't want an icon.
     */
    public void setIcon(int resId) {
        mIconId = resId;
        if (mIconView != null) {
            if (resId > 0) {
                mIconView.setImageResource(mIconId);
            } else if (resId == 0) {
                mIconView.setVisibility(View.GONE);
            }
        }
    }
    
    public void setIcon(Drawable icon) {
        mIcon = icon;
        if ((mIconView != null) && (mIcon != null)) {
            mIconView.setImageDrawable(icon);
        }
    }

    public void setInverseBackgroundForced(boolean forceInverseBackground) {
        mForceInverseBackground = forceInverseBackground;
    }
    
    public ListView getListView() {
        return mListView;
    }
    
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (mScrollView != null && mScrollView.executeKeyEvent(event)) return true;
        return false;
    }

    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (mScrollView != null && mScrollView.executeKeyEvent(event)) return true;
        return false;
    }
    
    private void setupView() {
        LinearLayout contentPanel = (LinearLayout) mWindow.findViewById(R.id.contentPanel);
        setupContent(contentPanel);
        boolean hasButtons = setupButtons();
        
        LinearLayout topPanel = (LinearLayout) mWindow.findViewById(R.id.topPanel);
        TypedArray a = mContext.obtainStyledAttributes(
                null, com.android.internal.R.styleable.AlertDialog, com.android.internal.R.attr.alertDialogStyle, 0);
        boolean hasTitle = setupTitle(topPanel, hasButtons);
            
        View buttonPanel = mWindow.findViewById(R.id.buttonPanel);
        if (!hasButtons) {
            buttonPanel.setVisibility(View.GONE);
        }

        FrameLayout customPanel = null;
        if (mView != null) {
            customPanel = (FrameLayout) mWindow.findViewById(R.id.customPanel);
            FrameLayout custom = (FrameLayout) mWindow.findViewById(R.id.custom);
            custom.addView(mView, new LayoutParams(FILL_PARENT, WRAP_CONTENT));
        } else {
            mWindow.findViewById(R.id.customPanel).setVisibility(View.GONE);
        }
        
        /* Only display the divider if we have a title and a 
         * custom view or a message.
         */
        if (hasTitle && ((mMessage != null) || (mView != null))) {
            View divider = mWindow.findViewById(R.id.titleDivider);
            divider.setVisibility(View.VISIBLE);
        }
        
        setBackground(topPanel, contentPanel, customPanel, hasButtons, a, hasTitle, buttonPanel);
        a.recycle();
    }

    private boolean setupTitle(LinearLayout topPanel, boolean hasButtons) {
        boolean hasTitle = true;
        
        if (mCustomTitleView != null) {
            // Add the custom title view directly to the topPanel layout
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.FILL_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            
            topPanel.addView(mCustomTitleView, lp);
            
            // Hide the title template
            View titleTemplate = mWindow.findViewById(R.id.title_template);
            titleTemplate.setVisibility(View.GONE);
        } else {
            final boolean hasTextTitle = !TextUtils.isEmpty(mTitle);
            
            mIconView = (ImageView) mWindow.findViewById(R.id.icon);
            if (hasTextTitle) {
                
                /* Display the title if a title is supplied, else hide it */
                mTitleView = (TextView) mWindow.findViewById(R.id.alertTitle);
                
                mTitleView.setText(mTitle);
                
                /* The title font size and icon varies depending on 
                 * what else is displayed within the dialog.
                 */
                if (mListView != null) {
                    
                    /* If a ListView is displayed then ensure the title 
                     * is only 1 line and use a special icon.
                     */
                    mTitleView.setSingleLine();
                    mTitleView.setEllipsize(TruncateAt.END);
                    mIconView.setImageResource(
                            R.drawable.ic_dialog_menu_generic);
                } else if ((mMessage != null) && hasButtons) {
                    
                    /* Has a message and buttons, we want the title to
                     * be a single line but large.
                     */
                    mTitleView.setSingleLine();
                    mTitleView.setEllipsize(TruncateAt.END);
                    mTitleView.setTextSize(getLargeTextSize());
                } else {
                    
                    /* We have a Title and buttons or we have title,
                     * and custom content. In either case the layout
                     * handles it so do nothing.
                     */
                }
                
                /* Do this last so that if the user has supplied any
                 * icons we use them instead of the default ones. If the
                 * user has specified 0 then make it dissapear.
                 */
                if (mIconId > 0) {
                    mIconView.setImageResource(mIconId);
                } else if (mIcon != null) {
                    mIconView.setImageDrawable(mIcon);
                } else if (mIconId == 0) {
                    
                    /* Apply the padding from the icon to ensure the
                     * title is aligned correctly.
                     */
                    mTitleView.setPadding(mIconView.getPaddingLeft(),
                            mIconView.getPaddingTop(),
                            mIconView.getPaddingRight(),
                            mIconView.getPaddingBottom());
                    mIconView.setVisibility(View.GONE);
                }
            } else {
                
                // Hide the title template
                View titleTemplate = mWindow.findViewById(R.id.title_template);
                titleTemplate.setVisibility(View.GONE);
                mIconView.setVisibility(View.GONE);
                hasTitle = false;
            }
        }
        return hasTitle;
    }

    private int getLargeTextSize() {
        TypedArray a =
            mContext.obtainStyledAttributes(
                    R.style.TextAppearance_Large,
                    R.styleable.TextAppearance);
        int textSize = a.getDimensionPixelSize(
                R.styleable.TextAppearance_textSize, 22);
        a.recycle();
        return textSize;
    }

    private void setupContent(LinearLayout contentPanel) {
        mScrollView = (ScrollView) mWindow.findViewById(R.id.scrollView);
        mScrollView.setFocusable(false);
        
        // Special case for users that only want to display a String
        mMessageView = (TextView) mWindow.findViewById(R.id.message);
        if (mMessageView == null) {
            return;
        }
        
        if (mMessage != null) {
            mMessageView.setText(mMessage);
        } else {
            mMessageView.setVisibility(View.GONE);
            mScrollView.removeView(mMessageView);
            
            if (mListView != null) {
                contentPanel.removeView(mWindow.findViewById(R.id.scrollView));
                contentPanel.addView(mListView, new LinearLayout.LayoutParams(FILL_PARENT, WRAP_CONTENT));
            } else {
                contentPanel.setVisibility(View.GONE);
            }
        }
    }

    private boolean setupButtons() {
        View defaultButton = null;
        int BUTTON1 = 1;
        int BUTTON2 = 2;
        int BUTTON3 = 4;
        int whichButton = 0;
        mButton1 = (Button) mWindow.findViewById(R.id.button1);
        mButton1.setOnClickListener(mButtonHandler);

        if (TextUtils.isEmpty(mButton1Text)) {
            mButton1.setVisibility(View.GONE);
        } else {
            mButton1.setText(mButton1Text);
            mButton1.setVisibility(View.VISIBLE);
            defaultButton = mButton1;
            whichButton = whichButton | BUTTON1;
        }

        mButton2 = (Button) mWindow.findViewById(R.id.button2);
        mButton2.setOnClickListener(mButtonHandler);

        if (TextUtils.isEmpty(mButton2Text)) {
            mButton2.setVisibility(View.GONE);
        } else {
            mButton2.setText(mButton2Text);
            mButton2.setVisibility(View.VISIBLE);

            if (defaultButton == null) {
                defaultButton = mButton2;
            }
            whichButton = whichButton | BUTTON2;
        }

        mButton3 = (Button) mWindow.findViewById(R.id.button3);
        mButton3.setOnClickListener(mButtonHandler);

        if (TextUtils.isEmpty(mButton3Text)) {
            mButton3.setVisibility(View.GONE);
        } else {
            mButton3.setText(mButton3Text);
            mButton3.setVisibility(View.VISIBLE);

            if (defaultButton == null) {
                defaultButton = mButton3;
            }
            whichButton = whichButton | BUTTON3;
        }

        /*
         * If we only have 1 button it should be centered on the layout and
         * expand to fill 50% of the available space.
         */
        if (whichButton == BUTTON1) {
            centerButton(mButton1);
        } else if (whichButton == BUTTON2) {
            centerButton(mButton3);
        } else if (whichButton == BUTTON3) {
            centerButton(mButton3);
        }
        
        return whichButton != 0;
    }

    private void centerButton(Button button) {
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) button.getLayoutParams();
        params.gravity = Gravity.CENTER_HORIZONTAL;
        params.weight = 0.5f;
        button.setLayoutParams(params);
        View leftSpacer = mWindow.findViewById(R.id.leftSpacer);
        leftSpacer.setVisibility(View.VISIBLE);
        View rightSpacer = mWindow.findViewById(R.id.rightSpacer);
        rightSpacer.setVisibility(View.VISIBLE);
    }

    private void setBackground(LinearLayout topPanel, LinearLayout contentPanel,
            View customPanel, boolean hasButtons, TypedArray a, boolean hasTitle, 
            View buttonPanel) {
        
        /* Get all the different background required */
        int fullDark = a.getResourceId(
                R.styleable.AlertDialog_fullDark, R.drawable.popup_full_dark);
        int topDark = a.getResourceId(
                R.styleable.AlertDialog_topDark, R.drawable.popup_top_dark);
        int centerDark = a.getResourceId(
                R.styleable.AlertDialog_centerDark, R.drawable.popup_center_dark);
        int bottomDark = a.getResourceId(
                R.styleable.AlertDialog_bottomDark, R.drawable.popup_bottom_dark);
        int fullBright = a.getResourceId(
                R.styleable.AlertDialog_fullBright, R.drawable.popup_full_bright);
        int topBright = a.getResourceId(
                R.styleable.AlertDialog_topBright, R.drawable.popup_top_bright);
        int centerBright = a.getResourceId(
                R.styleable.AlertDialog_centerBright, R.drawable.popup_center_bright);
        int bottomBright = a.getResourceId(
                R.styleable.AlertDialog_bottomBright, R.drawable.popup_bottom_bright);
        int bottomMedium = a.getResourceId(
                R.styleable.AlertDialog_bottomMedium, R.drawable.popup_bottom_medium);
        int centerMedium = a.getResourceId(
                R.styleable.AlertDialog_centerMedium, R.drawable.popup_center_medium);
        
        /*
         * We now set the background of all of the sections of the alert.
         * First collect together each section that is being displayed along
         * with whether it is on a light or dark background, then run through
         * them setting their backgrounds.  This is complicated because we need
         * to correctly use the full, top, middle, and bottom graphics depending
         * on how many views they are and where they appear.
         */
        
        View[] views = new View[4];
        boolean[] light = new boolean[4];
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
        views[pos] = (contentPanel.getVisibility() == View.GONE) 
                ? null : contentPanel;
        light[pos] = mListView == null ? false : true;
        pos++;
        if (customPanel != null) {
            views[pos] = customPanel;
            light[pos] = mForceInverseBackground;
            pos++;
        }
        if (hasButtons) {
            views[pos] = buttonPanel;
            light[pos] = true;
        }
        
        boolean setView = false;
        for (pos=0; pos<views.length; pos++) {
            View v = views[pos];
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
                
                /* ListViews will use the Bright background but buttons use
                 * the Medium background.
                 */ 
                lastView.setBackgroundResource(
                        lastLight ? (hasButtons ? bottomMedium : bottomBright) : bottomDark);
            } else {
                lastView.setBackgroundResource(lastLight ? fullBright : fullDark);
            }
        }
        
        /* TODO: uncomment section below. The logic for this should be if 
         * it's a Contextual menu being displayed AND only a Cancel button 
         * is shown then do this.
         */
//        if (hasButtons && (mListView != null)) {
            
            /* Yet another *special* case. If there is a ListView with buttons
             * don't put the buttons on the bottom but instead put them in the
             * footer of the ListView this will allow more items to be
             * displayed.
             */
            
            /*
            contentPanel.setBackgroundResource(bottomBright);
            buttonPanel.setBackgroundResource(centerMedium);
            ViewGroup parent = (ViewGroup) mWindow.findViewById(R.id.parentPanel);
            parent.removeView(buttonPanel);
            AbsListView.LayoutParams params = new AbsListView.LayoutParams(
                    AbsListView.LayoutParams.FILL_PARENT, 
                    AbsListView.LayoutParams.FILL_PARENT);
            buttonPanel.setLayoutParams(params);
            mListView.addFooterView(buttonPanel);
            */
//        }
        
        if ((mListView != null) && (mAdapter != null)) {
            mListView.setAdapter(mAdapter);
            if (mCheckedItem > -1) {
                mListView.setItemChecked(mCheckedItem, true);
                mListView.setSelection(mCheckedItem);
            }
        }
    }
    
    public static class AlertParams {
        public final Context mContext;
        public final LayoutInflater mInflater;
        
        public int mIconId = -1;
        public Drawable mIcon;
        public CharSequence mTitle;
        public View mCustomTitleView;
        public CharSequence mMessage;
        public CharSequence mPositiveButtonText;
        public DialogInterface.OnClickListener mPositiveButtonListener;
        public CharSequence mNegativeButtonText;
        public DialogInterface.OnClickListener mNegativeButtonListener;
        public CharSequence mNeutralButtonText;
        public DialogInterface.OnClickListener mNeutralButtonListener;
        public boolean mCancelable;
        public DialogInterface.OnCancelListener mOnCancelListener;
        public DialogInterface.OnKeyListener mOnKeyListener;
        public CharSequence[] mItems;
        public ListAdapter mAdapter;
        public DialogInterface.OnClickListener mOnClickListener;
        public View mView;
        public boolean[] mCheckedItems;
        public boolean mIsMultiChoice;
        public boolean mIsSingleChoice;
        public int mCheckedItem = -1;
        public DialogInterface.OnMultiChoiceClickListener mOnCheckboxClickListener;
        public Cursor mCursor;
        public String mLabelColumn;
        public String mIsCheckedColumn;
        public boolean mForceInverseBackground;
        public AdapterView.OnItemSelectedListener mOnItemSelectedListener;
        public OnPrepareListViewListener mOnPrepareListViewListener;
        
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
        
        public AlertParams(Context context) {
            mContext = context;
            mCancelable = true;
            mInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        }
    
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
                if (mIconId >= 0) {
                    dialog.setIcon(mIconId);
                }
            }
            if (mMessage != null) {
                dialog.setMessage(mMessage);
            }
            if (mPositiveButtonText != null) {
                dialog.setButton(mPositiveButtonText, mPositiveButtonListener);
            }
            if (mNegativeButtonText != null) {
                dialog.setButton2(mNegativeButtonText, mNegativeButtonListener);
            }
            if (mNeutralButtonText != null) {
                dialog.setButton3(mNeutralButtonText, mNeutralButtonListener);
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
                dialog.setView(mView);
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
            final ListView listView = (ListView) mInflater.inflate(R.layout.select_dialog, null);
            ListAdapter adapter;
            
            if (mIsMultiChoice) {
                if (mCursor == null) {
                    adapter = new ArrayAdapter<CharSequence>(
                            mContext, R.layout.select_dialog_multichoice, R.id.text1, mItems) {
                        @Override
                        public View getView(int position, View convertView,
                                ViewGroup parent) {
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
    
                        @Override
                        public void bindView(View view, Context context,
                                Cursor cursor) {
                            CheckedTextView text = (CheckedTextView) view.findViewById(R.id.text1);
                            text.setText(cursor.getString(cursor.getColumnIndexOrThrow(mLabelColumn)));
                            if (cursor.getInt(cursor.getColumnIndexOrThrow(mIsCheckedColumn)) == 1) {
                                listView.setItemChecked(cursor.getPosition(), true);
                            }
                        }
    
                        @Override
                        public View newView(Context context, Cursor cursor,
                                ViewGroup parent) {
                            View view = mInflater.inflate(
                                    R.layout.select_dialog_multichoice, parent, false);
                            bindView(view, context, cursor);
                            return view;
                        }
                        
                    };
                }
            } else {
                int layout = mIsSingleChoice 
                        ? R.layout.select_dialog_singlechoice : R.layout.select_dialog_item;
                if (mCursor == null) {
                    adapter = (mAdapter != null) ? mAdapter
                            : new ArrayAdapter<CharSequence>(mContext, layout, R.id.text1, mItems);
                } else {
                    adapter = new SimpleCursorAdapter(mContext, layout, 
                            mCursor, new String[]{mLabelColumn}, new int[]{R.id.text1});
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
                    public void onItemClick(AdapterView parent, View v, int position, long id) {
                        mOnClickListener.onClick(dialog.mDialogInterface, position);
                        if (!mIsSingleChoice) {
                            dialog.mDialogInterface.dismiss();
                        }
                    }
                });
            } else if (mOnCheckboxClickListener != null) {
                listView.setOnItemClickListener(new OnItemClickListener() {
                    public void onItemClick(AdapterView parent, View v, int position, long id) {
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
            dialog.mListView = listView;
        }
    }

}
