/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.internal.widget;

import android.app.AlertDialog.Builder;
import android.content.Context;
import android.content.DialogInterface;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.Html;
import android.text.Spannable;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.ImageSpan;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.widget.EditText;

/**
 * EditStyledText extends EditText for managing the flow and status to edit
 * the styled text. This manages the states and flows of editing, supports
 * inserting image, import/export HTML.
 */
public class EditStyledText extends EditText {

    private static final String LOG_TAG = "EditStyledText";
    private static final boolean DBG = true;

    /**
     * The modes of editing actions.
     */
    /** The mode that no editing action is done. */
    public static final int MODE_NOTHING = 0;
    /** The mode of copy. */
    public static final int MODE_COPY = 1;
    /** The mode of paste. */
    public static final int MODE_PASTE = 2;
    /** The mode of changing size. */
    public static final int MODE_SIZE = 3;
    /** The mode of changing color. */
    public static final int MODE_COLOR = 4;
    /** The mode of selection. */
    public static final int MODE_SELECT = 5;

    /**
     * The state of selection.
     */
    /** The state that selection isn't started. */
    public static final int STATE_SELECT_OFF = 0;
    /** The state that selection is started. */
    public static final int STATE_SELECT_ON = 1;
    /** The state that selection is done, but not fixed. */
    public static final int STATE_SELECTED = 2;
    /** The state that selection is done and not fixed. */
    public static final int STATE_SELECT_FIX = 3;

    /**
     * The help message strings.
     */
    public static final int HINT_MSG_NULL = 0;
    public static final int HINT_MSG_COPY_BUF_BLANK = 1;
    public static final int HINT_MSG_SELECT_START = 2;
    public static final int HINT_MSG_SELECT_END = 3;
    public static final int HINT_MSG_PUSH_COMPETE = 4;

    /**
     * EditStyledTextInterface provides functions for notifying messages to
     * calling class.
     */
    public interface EditStyledTextNotifier {
        public void notifyHintMsg(int msgId);
    }

    private EditStyledTextNotifier mESTInterface;

    /**
     * EditStyledTextEditorManager manages the flow and status of each
     * function for editing styled text.
     */
    private EditorManager mManager;
    private StyledTextConverter mConverter;
    private StyledTextToast mToast;

    /**
     * EditStyledText extends EditText for managing flow of each editing
     * action.
     */
    public EditStyledText(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    public EditStyledText(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public EditStyledText(Context context) {
        super(context);
        init();
    }

    /**
     * Set Notifier.
     */
    public void setNotifier(EditStyledTextNotifier estInterface) {
        mESTInterface = estInterface;
    }

    /**
     * Set Builder for AlertDialog.
     * 
     * @param builder
     *            Builder for opening Alert Dialog.
     */
    public void setBuilder(Builder builder) {
        mToast.setBuilder(builder);
    }

    /**
     * Set Parameters for ColorAlertDialog.
     * 
     * @param colortitle
     *            Title for Alert Dialog.
     * @param colornames
     *            List of name of selecting color.
     * @param colorints
     *            List of int of color.
     */
    public void setColorAlertParams(CharSequence colortitle,
            CharSequence[] colornames, CharSequence[] colorints) {
        mToast.setColorAlertParams(colortitle, colornames, colorints);
    }

    /**
     * Set Parameters for SizeAlertDialog.
     * 
     * @param sizetitle
     *            Title for Alert Dialog.
     * @param sizenames
     *            List of name of selecting size.
     * @param sizedisplayints
     *            List of int of size displayed in TextView.
     * @param sizesendints
     *            List of int of size exported to HTML.
     */
    public void setSizeAlertParams(CharSequence sizetitle,
            CharSequence[] sizenames, CharSequence[] sizedisplayints,
            CharSequence[] sizesendints) {
        mToast.setSizeAlertParams(sizetitle, sizenames, sizedisplayints,
                sizesendints);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final boolean superResult = super.onTouchEvent(event);
        if (event.getAction() == MotionEvent.ACTION_UP) {
            if (DBG) {
                Log.d(LOG_TAG, "--- onTouchEvent");
            }
            mManager.onTouchScreen();
        }
        return superResult;
    }

    /**
     * Start editing. This function have to be called before other editing
     * actions.
     */
    public void onStartEdit() {
        mManager.onStartEdit();
    }

    /**
     * End editing.
     */
    public void onEndEdit() {
        mManager.onEndEdit();
    }

    /**
     * Start "Copy" action.
     */
    public void onStartCopy() {
        mManager.onStartCopy();
    }

    /**
     * Start "Paste" action.
     */
    public void onStartPaste() {
        mManager.onStartPaste();
    }

    /**
     * Start changing "Size" action.
     */
    public void onStartSize() {
        mManager.onStartSize();
    }

    /**
     * Start changing "Color" action.
     */
    public void onStartColor() {
        mManager.onStartColor();
    }

    /**
     * Start "Select" action.
     */
    public void onStartSelect() {
        mManager.onStartSelect();
    }

    /**
     * Start "SelectAll" action.
     */
    public void onStartSelectAll() {
        mManager.onStartSelectAll();
    }

    /**
     * InsertImage to TextView by using URI
     * 
     * @param uri
     *            URI of the iamge inserted to TextView.
     */
    public void onInsertImage(Uri uri) {
        mManager.onInsertImage(uri);
    }

    /**
     * InsertImage to TextView by using resource ID
     * 
     * @param resId
     *            Resource ID of the iamge inserted to TextView.
     */
    public void onInsertImage(int resId) {
        mManager.onInsertImage(resId);
    }

    /**
     * Fix Selected Item.
     */
    public void fixSelectedItem() {
        mManager.onFixSelectItem();
    }

    /**
     * Set Size of the Item.
     * 
     * @param size
     *            The size of the Item.
     */
    public void setItemSize(int size) {
        mManager.setItemSize(size);
    }

    /**
     * Set Color of the Item.
     * 
     * @param color
     *            The color of the Item.
     */
    public void setItemColor(int color) {
        mManager.setItemColor(color);
    }

    public void onShowColorAlert() {
        mToast.onShowColorAlertDialog();
    }

    public void onShowSizeAlert() {
        mToast.onShowSizeAlertDialog();
    }

    /**
     * Check editing is started.
     * 
     * @return Whether editing is started or not.
     */
    public boolean isEditting() {
        return mManager.isEditting();
    }

    /**
     * Get the mode of the action.
     * 
     * @return The mode of the action.
     */
    public int getEditMode() {
        return mManager.getEditMode();
    }

    /**
     * Get the state of the selection.
     * 
     * @return The state of the selection.
     */
    public int getSelectState() {
        return mManager.getSelectState();
    }

    public String getBody() {
        return mConverter.getConvertedBody();
    }

    /**
     * Initialize members.
     */
    private void init() {
        if (DBG) {
            Log.d(LOG_TAG, "--- init");
            requestFocus();
        }
        mManager = new EditorManager(this);
        mConverter = new StyledTextConverter(this);
        mToast = new StyledTextToast(this);
    }

    /**
     * Notify hint messages what action is expected to calling class.
     * 
     * @param msgId
     *            Id of the hint message.
     */
    private void setHintMessage(int msgId) {
        if (mESTInterface != null) {
            mESTInterface.notifyHintMsg(msgId);
        }
    }

    @Override
    public Bundle getInputExtras(boolean create) {
        Bundle bundle = super.getInputExtras(create);
        if (bundle != null) {
            bundle.putBoolean("allowEmoji", true);
        }
        return bundle;
    }

    /**
     * Object which manages the flow and status of editing actions.
     */
    private class EditorManager {
        private boolean mEditFlag = false;
        private int mMode = 0;
        private int mState = 0;
        private int mCurStart = 0;
        private int mCurEnd = 0;
        private EditStyledText mEST;
        private Editable mTextSelectBuffer;
        private CharSequence mTextCopyBufer;

        EditorManager(EditStyledText est) {
            mEST = est;
        }

        public void onStartEdit() {
            if (DBG) {
                Log.d(LOG_TAG, "--- onEdit");
            }
            handleResetEdit();
        }

        public void onEndEdit() {
            if (DBG) {
                Log.d(LOG_TAG, "--- onClickCancel");
            }
            handleCancel();
        }

        public void onStartCopy() {
            if (DBG) {
                Log.d(LOG_TAG, "--- onClickCopy");
            }
            handleCopy();
        }

        public void onStartPaste() {
            if (DBG) {
                Log.d(LOG_TAG, "--- onClickPaste");
            }
            handlePaste();
        }

        public void onStartSize() {
            if (DBG) {
                Log.d(LOG_TAG, "--- onClickSize");
            }
            handleSize();
        }

        public void setItemSize(int size) {
            if (DBG) {
                Log.d(LOG_TAG, "--- onClickSizeItem");
            }
            if (mState == STATE_SELECTED || mState == STATE_SELECT_FIX) {
                changeSizeSelectedText(size);
                handleResetEdit();
            }
        }

        public void setItemColor(int color) {
            if (DBG) {
                Log.d(LOG_TAG, "--- onClickColorItem");
            }
            if (mState == STATE_SELECTED || mState == STATE_SELECT_FIX) {
                changeColorSelectedText(color);
                handleResetEdit();
            }
        }

        public void onStartColor() {
            if (DBG) {
                Log.d(LOG_TAG, "--- onClickColor");
            }
            handleColor();
        }

        public void onStartSelect() {
            if (DBG) {
                Log.d(LOG_TAG, "--- onClickSelect");
            }
            mMode = MODE_SELECT;
            if (mState == STATE_SELECT_OFF) {
                handleSelect();
            } else {
                offSelect();
                handleSelect();
            }
        }

        public void onStartSelectAll() {
            if (DBG) {
                Log.d(LOG_TAG, "--- onClickSelectAll");
            }
            handleSelectAll();
        }

        public void onTouchScreen() {
            if (DBG) {
                Log.d(LOG_TAG, "--- onClickView");
            }
            if (mState == STATE_SELECT_ON || mState == STATE_SELECTED) {
                handleSelect();
            }
        }

        public void onFixSelectItem() {
            if (DBG) {
                Log.d(LOG_TAG, "--- onClickComplete");
            }
            handleComplete();
        }

        public void onInsertImage(Uri uri) {
            if (DBG) {
                Log.d(LOG_TAG, "--- onInsertImage by URI: " + uri.getPath()
                        + "," + uri.toString());
            }

            mEST.getText().append("a");
            mEST.getText().setSpan(new ImageSpan(mEST.getContext(), uri),
                    mEST.getText().length() - 1, mEST.getText().length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        public void onInsertImage(int resID) {
            if (DBG) {
                Log.d(LOG_TAG, "--- onInsertImage by resID");
            }
            mEST.getText().append("b");
            mEST.getText().setSpan(new ImageSpan(mEST.getContext(), resID),
                    mEST.getText().length() - 1, mEST.getText().length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        public boolean isEditting() {
            return mEditFlag;
        }

        public int getEditMode() {
            return mMode;
        }

        public int getSelectState() {
            return mState;
        }

        private void handleCancel() {
            if (DBG) {
                Log.d(LOG_TAG, "--- handleCancel");
            }
            mMode = MODE_NOTHING;
            mState = STATE_SELECT_OFF;
            mEditFlag = false;
            offSelect();
        }

        private void handleComplete() {
            if (DBG) {
                Log.d(LOG_TAG, "--- handleComplete");
            }
            if (!mEditFlag) {
                return;
            }
            if (mState == STATE_SELECTED) {
                mState = STATE_SELECT_FIX;
            }
            switch (mMode) {
            case MODE_COPY:
                handleCopy();
                break;
            case MODE_COLOR:
                handleColor();
                break;
            case MODE_SIZE:
                handleSize();
                break;
            default:
                break;
            }
        }

        private void handleCopy() {
            if (DBG) {
                Log.d(LOG_TAG, "--- handleCopy: " + mMode + "," + mState);
            }
            if (!mEditFlag) {
                return;
            }
            if (mMode == MODE_NOTHING || mMode == MODE_SELECT) {
                mMode = MODE_COPY;
                if (mState == STATE_SELECTED) {
                    mState = STATE_SELECT_FIX;
                    storeSelectedText();
                } else {
                    handleSelect();
                }
            } else if (mMode != MODE_COPY) {
                handleCancel();
                mMode = MODE_COPY;
                handleCopy();
            } else if (mState == STATE_SELECT_FIX) {
                mEST.setHintMessage(HINT_MSG_NULL);
                storeSelectedText();
                handleResetEdit();
            }
        }

        private void handlePaste() {
            if (DBG) {
                Log.d(LOG_TAG, "--- handlePaste");
            }
            if (!mEditFlag) {
                return;
            }
            if (mTextSelectBuffer != null && mTextCopyBufer.length() > 0) {
                mTextSelectBuffer.insert(mEST.getSelectionStart(),
                        mTextCopyBufer);
            } else {
                mEST.setHintMessage(HINT_MSG_COPY_BUF_BLANK);
            }
        }

        private void handleSize() {
            if (DBG) {
                Log.d(LOG_TAG, "--- handleSize: " + mMode + "," + mState);
            }
            if (!mEditFlag) {
                Log.e(LOG_TAG, "--- Editing is not started for handlesize.");
                return;
            }
            if (mMode == MODE_NOTHING || mMode == MODE_SELECT) {
                mMode = MODE_SIZE;
                if (mState == STATE_SELECTED) {
                    mState = STATE_SELECT_FIX;
                    handleSize();
                } else {
                    handleSelect();
                }
            } else if (mMode != MODE_SIZE) {
                handleCancel();
                mMode = MODE_SIZE;
                handleSize();
            } else {
                if (mState == STATE_SELECT_FIX) {
                    mEST.setHintMessage(HINT_MSG_NULL);
                    mEST.onShowSizeAlert();
                } else {
                    Log.d(LOG_TAG, "--- handlesize: do nothing");
                }
            }
        }

        private void handleColor() {
            if (DBG) {
                Log.d(LOG_TAG, "--- handleSize: " + mMode + "," + mState);
            }
            if (!mEditFlag) {
                Log.e(LOG_TAG, "--- Editing is not started for handlecolor.");
                return;
            }
            if (mMode == MODE_NOTHING || mMode == MODE_SELECT) {
                mMode = MODE_COLOR;
                if (mState == STATE_SELECTED) {
                    mState = STATE_SELECT_FIX;
                    handleColor();
                } else {
                    handleSelect();
                }
            } else if (mMode != MODE_COLOR) {
                handleCancel();
                mMode = MODE_COLOR;
                handleSize();
            } else {
                if (mState == STATE_SELECT_FIX) {
                    mEST.setHintMessage(HINT_MSG_NULL);
                    mEST.onShowColorAlert();
                } else {
                    Log.d(LOG_TAG, "--- handlecolor: do nothing");
                }
            }
        }

        private void handleSelect() {
            if (DBG) {
                Log.d(LOG_TAG, "--- handleSelect:" + mEditFlag + "," + mState);
            }
            if (!mEditFlag) {
                return;
            }
            if (mState == STATE_SELECT_OFF) {
                if (isTextSelected()) {
                    Log.e(LOG_TAG, "Selection is off, but selected");
                }
                setSelectStartPos();
                mEST.setHintMessage(HINT_MSG_SELECT_END);
            } else if (mState == STATE_SELECT_ON) {
                if (isTextSelected()) {
                    Log.e(LOG_TAG, "Selection now start, but selected");
                }
                setSelectEndPos();
                mEST.setHintMessage(HINT_MSG_PUSH_COMPETE);
                doNextHandle();
            } else if (mState == STATE_SELECTED) {
                if (!isTextSelected()) {
                    Log.e(LOG_TAG, "Selection is done, but not selected");
                }
                setSelectEndPos();
                doNextHandle();
            }
        }

        private void handleSelectAll() {
            if (DBG) {
                Log.d(LOG_TAG, "--- handleSelectAll");
            }
            if (!mEditFlag) {
                return;
            }
            mEST.selectAll();
        }

        private void doNextHandle() {
            if (DBG) {
                Log.d(LOG_TAG, "--- doNextHandle: " + mMode + "," + mState);
            }
            switch (mMode) {
            case MODE_COPY:
                handleCopy();
                break;
            case MODE_PASTE:
                handlePaste();
                break;
            case MODE_SIZE:
                handleSize();
                break;
            case MODE_COLOR:
                handleColor();
                break;
            default:
                break;
            }
        }

        private void handleResetEdit() {
            if (DBG) {
                Log.d(LOG_TAG, "Reset Editor");
            }
            handleCancel();
            mEditFlag = true;
            mEST.setHintMessage(HINT_MSG_SELECT_START);
        }

        // Methods of selection
        private void onSelect() {
            if (DBG) {
                Log.d(LOG_TAG, "--- onSelect:" + mCurStart + "," + mCurEnd);
            }
            if (mCurStart >= 0 && mCurStart <= mEST.getText().length()
                    && mCurEnd >= 0 && mCurEnd <= mEST.getText().length()) {
                mEST.setSelection(mCurStart, mCurEnd);
                mState = STATE_SELECTED;
            } else {
                Log.e(LOG_TAG,
                        "Select is on, but cursor positions are illigal.:"
                                + mEST.getText().length() + "," + mCurStart
                                + "," + mCurEnd);
            }
        }

        private void offSelect() {
            if (DBG) {
                Log.d(LOG_TAG, "--- offSelect");
            }
            int currpos = mEST.getSelectionStart();
            mEST.setSelection(currpos, currpos);
            mState = STATE_SELECT_OFF;
        }

        private void setSelectStartPos() {
            if (DBG) {
                Log.d(LOG_TAG, "--- setSelectStartPos");
            }
            mCurStart = mEST.getSelectionStart();
            mState = STATE_SELECT_ON;
        }

        private void setSelectEndPos() {
            if (DBG) {
                Log.d(LOG_TAG, "--- setSelectEndPos:"
                        + mEST.getSelectionStart());
            }
            int curpos = mEST.getSelectionStart();
            if (curpos < mCurStart) {
                if (DBG) {
                    Log.d(LOG_TAG, "--- setSelectEndPos: swap is done.");
                }
                mCurEnd = mCurStart;
                mCurStart = curpos;
            } else {
                mCurEnd = curpos;
            }
            onSelect();
        }

        private boolean isTextSelected() {
            if (DBG) {
                Log.d(LOG_TAG, "--- isTextSelected:" + mCurStart + ","
                        + mCurEnd);
            }
            return (mCurStart != mCurEnd)
                    && (mState == STATE_SELECTED ||
                            mState == STATE_SELECT_FIX);
        }

        private void storeSelectedText() {
            if (DBG) {
                Log.d(LOG_TAG, "--- storeSelectedText");
            }
            mTextSelectBuffer = mEST.getText();
            mTextCopyBufer = mTextSelectBuffer.subSequence(mCurStart, mCurEnd);
        }

        private void changeSizeSelectedText(int size) {
            if (DBG) {
                Log.d(LOG_TAG, "--- changeSizeSelectedText:" + size + ","
                        + mCurStart + "," + mCurEnd);
            }
            mEST.getText().setSpan(new AbsoluteSizeSpan(size), mCurStart,
                    mCurEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        private void changeColorSelectedText(int color) {
            if (DBG) {
                Log.d(LOG_TAG, "--- changeCollorSelectedText:" + color + ","
                        + mCurStart + "," + mCurEnd);
            }
            mEST.getText().setSpan(new ForegroundColorSpan(color), mCurStart,
                    mCurEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private class StyledTextConverter {
        private EditStyledText mEST;

        public StyledTextConverter(EditStyledText est) {
            mEST = est;
        }

        public String getConvertedBody() {
            String htmlBody = Html.toHtml(mEST.getText());
            return htmlBody;
        }
    }

    private class StyledTextToast {
        Builder mBuilder;
        CharSequence mColorTitle;
        CharSequence mSizeTitle;
        CharSequence[] mColorNames;
        CharSequence[] mColorInts;
        CharSequence[] mSizeNames;
        CharSequence[] mSizeDisplayInts;
        CharSequence[] mSizeSendInts;
        EditStyledText mEST;

        public StyledTextToast(EditStyledText est) {
            mEST = est;
        }

        public void setBuilder(Builder builder) {
            mBuilder = builder;
        }

        public void setColorAlertParams(CharSequence colortitle,
                CharSequence[] colornames, CharSequence[] colorints) {
            mColorTitle = colortitle;
            mColorNames = colornames;
            mColorInts = colorints;
        }

        public void setSizeAlertParams(CharSequence sizetitle,
                CharSequence[] sizenames, CharSequence[] sizedisplayints,
                CharSequence[] sizesendints) {
            mSizeTitle = sizetitle;
            mSizeNames = sizenames;
            mSizeDisplayInts = sizedisplayints;
            mSizeSendInts = sizesendints;
        }

        public boolean checkColorAlertParams() {
            if (DBG) {
                Log.d(LOG_TAG, "--- checkParams");
            }
            if (mBuilder == null) {
                Log.e(LOG_TAG, "--- builder is null.");
                return false;
            } else if (mColorTitle == null || mColorNames == null
                    || mColorInts == null) {
                Log.e(LOG_TAG, "--- color alert params are null.");
                return false;
            } else if (mColorNames.length != mColorInts.length) {
                Log.e(LOG_TAG, "--- the length of color alert params are "
                        + "different.");
                return false;
            }
            return true;
        }

        public boolean checkSizeAlertParams() {
            if (DBG) {
                Log.d(LOG_TAG, "--- checkParams");
            }
            if (mBuilder == null) {
                Log.e(LOG_TAG, "--- builder is null.");
            } else if (mSizeTitle == null || mSizeNames == null
                    || mSizeDisplayInts == null || mSizeSendInts == null) {
                Log.e(LOG_TAG, "--- size alert params are null.");
            } else if (mSizeNames.length != mSizeDisplayInts.length
                    && mSizeSendInts.length != mSizeDisplayInts.length) {
                Log.e(LOG_TAG, "--- the length of size alert params are "
                        + "different.");
            }
            return true;
        }

        private void onShowColorAlertDialog() {
            if (DBG) {
                Log.d(LOG_TAG, "--- onShowAlertDialog");
            }
            if (!checkColorAlertParams()) {
                return;
            }
            mBuilder.setTitle(mColorTitle);
            mBuilder.setIcon(0);
            mBuilder.
            setItems(mColorNames,
                    new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    Log.d("EETVM", "mBuilder.onclick:" + which);
                    int color = Integer.parseInt(
                            (String) mColorInts[which], 16) - 0x01000000;
                    mEST.setItemColor(color);
                }
            });
            mBuilder.show();
        }

        private void onShowSizeAlertDialog() {
            if (DBG) {
                Log.d(LOG_TAG, "--- onShowAlertDialog");
            }
            if (!checkColorAlertParams()) {
                return;
            }
            mBuilder.setTitle(mSizeTitle);
            mBuilder.setIcon(0);
            mBuilder.
            setItems(mSizeNames,
                    new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    Log.d("EETVM", "mBuilder.onclick:" + which);
                    int size = Integer
                    .parseInt((String) mSizeDisplayInts[which]);
                    mEST.setItemSize(size);
                }
            });
            mBuilder.show();
        }
    }
}
