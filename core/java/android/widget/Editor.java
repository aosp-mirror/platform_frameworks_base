/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.widget;

import java.text.BreakIterator;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

import android.R;
import android.annotation.Nullable;
import android.app.PendingIntent;
import android.app.PendingIntent.CanceledException;
import android.content.ClipData;
import android.content.ClipData.Item;
import android.content.Context;
import android.content.Intent;
import android.content.UndoManager;
import android.content.UndoOperation;
import android.content.UndoOwner;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.ParcelableParcel;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.DynamicLayout;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.Layout;
import android.text.ParcelableSpan;
import android.text.Selection;
import android.text.SpanWatcher;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextUtils;
import android.text.method.KeyListener;
import android.text.method.MetaKeyKeyListener;
import android.text.method.MovementMethod;
import android.text.method.WordIterator;
import android.text.style.EasyEditSpan;
import android.text.style.SuggestionRangeSpan;
import android.text.style.SuggestionSpan;
import android.text.style.TextAppearanceSpan;
import android.text.style.URLSpan;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.SparseArray;
import android.view.ActionMode;
import android.view.ActionMode.Callback;
import android.view.DisplayListCanvas;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.RenderNode;
import android.view.View;
import android.view.View.DragShadowBuilder;
import android.view.View.OnClickListener;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.CorrectionInfo;
import android.view.inputmethod.CursorAnchorInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.TextView.Drawables;
import android.widget.TextView.OnEditorActionListener;

import com.android.internal.util.ArrayUtils;
import com.android.internal.util.GrowingArrayUtils;
import com.android.internal.util.Preconditions;
import com.android.internal.widget.EditableInputConnection;


/**
 * Helper class used by TextView to handle editable text views.
 *
 * @hide
 */
public class Editor {
    private static final String TAG = "Editor";
    private static final boolean DEBUG_UNDO = false;

    static final int BLINK = 500;
    private static final float[] TEMP_POSITION = new float[2];
    private static int DRAG_SHADOW_MAX_TEXT_LENGTH = 20;
    private static final float LINE_SLOP_MULTIPLIER_FOR_HANDLEVIEWS = 0.5f;
    private static final int UNSET_X_VALUE = -1;
    private static final int UNSET_LINE = -1;
    // Tag used when the Editor maintains its own separate UndoManager.
    private static final String UNDO_OWNER_TAG = "Editor";

    // Ordering constants used to place the Action Mode items in their menu.
    private static final int MENU_ITEM_ORDER_CUT = 1;
    private static final int MENU_ITEM_ORDER_COPY = 2;
    private static final int MENU_ITEM_ORDER_PASTE = 3;
    private static final int MENU_ITEM_ORDER_SHARE = 4;
    private static final int MENU_ITEM_ORDER_SELECT_ALL = 5;
    private static final int MENU_ITEM_ORDER_REPLACE = 6;
    private static final int MENU_ITEM_ORDER_PROCESS_TEXT_INTENT_ACTIONS_START = 10;

    // Each Editor manages its own undo stack.
    private final UndoManager mUndoManager = new UndoManager();
    private UndoOwner mUndoOwner = mUndoManager.getOwner(UNDO_OWNER_TAG, this);
    final UndoInputFilter mUndoInputFilter = new UndoInputFilter(this);
    boolean mAllowUndo = true;

    // Cursor Controllers.
    InsertionPointCursorController mInsertionPointCursorController;
    SelectionModifierCursorController mSelectionModifierCursorController;
    // Action mode used when text is selected or when actions on an insertion cursor are triggered.
    ActionMode mTextActionMode;
    boolean mInsertionControllerEnabled;
    boolean mSelectionControllerEnabled;

    // Used to highlight a word when it is corrected by the IME
    CorrectionHighlighter mCorrectionHighlighter;

    InputContentType mInputContentType;
    InputMethodState mInputMethodState;

    private static class TextRenderNode {
        RenderNode renderNode;
        boolean isDirty;
        public TextRenderNode(String name) {
            isDirty = true;
            renderNode = RenderNode.create(name, null);
        }
        boolean needsRecord() { return isDirty || !renderNode.isValid(); }
    }
    TextRenderNode[] mTextRenderNodes;

    boolean mFrozenWithFocus;
    boolean mSelectionMoved;
    boolean mTouchFocusSelected;

    KeyListener mKeyListener;
    int mInputType = EditorInfo.TYPE_NULL;

    boolean mDiscardNextActionUp;
    boolean mIgnoreActionUpEvent;

    long mShowCursor;
    Blink mBlink;

    boolean mCursorVisible = true;
    boolean mSelectAllOnFocus;
    boolean mTextIsSelectable;

    CharSequence mError;
    boolean mErrorWasChanged;
    ErrorPopup mErrorPopup;

    /**
     * This flag is set if the TextView tries to display an error before it
     * is attached to the window (so its position is still unknown).
     * It causes the error to be shown later, when onAttachedToWindow()
     * is called.
     */
    boolean mShowErrorAfterAttach;

    boolean mInBatchEditControllers;
    boolean mShowSoftInputOnFocus = true;
    boolean mPreserveDetachedSelection;
    boolean mTemporaryDetach;

    SuggestionsPopupWindow mSuggestionsPopupWindow;
    SuggestionRangeSpan mSuggestionRangeSpan;
    Runnable mShowSuggestionRunnable;

    final Drawable[] mCursorDrawable = new Drawable[2];
    int mCursorCount; // Current number of used mCursorDrawable: 0 (resource=0), 1 or 2 (split)

    private Drawable mSelectHandleLeft;
    private Drawable mSelectHandleRight;
    private Drawable mSelectHandleCenter;

    // Global listener that detects changes in the global position of the TextView
    private PositionListener mPositionListener;

    float mLastDownPositionX, mLastDownPositionY;
    Callback mCustomSelectionActionModeCallback;
    Callback mCustomInsertionActionModeCallback;

    // Set when this TextView gained focus with some text selected. Will start selection mode.
    boolean mCreatedWithASelection;

    boolean mDoubleTap = false;

    private Runnable mInsertionActionModeRunnable;

    // The span controller helps monitoring the changes to which the Editor needs to react:
    // - EasyEditSpans, for which we have some UI to display on attach and on hide
    // - SelectionSpans, for which we need to call updateSelection if an IME is attached
    private SpanController mSpanController;

    WordIterator mWordIterator;
    SpellChecker mSpellChecker;

    // This word iterator is set with text and used to determine word boundaries
    // when a user is selecting text.
    private WordIterator mWordIteratorWithText;
    // Indicate that the text in the word iterator needs to be updated.
    private boolean mUpdateWordIteratorText;

    private Rect mTempRect;

    private TextView mTextView;

    final ProcessTextIntentActionsHandler mProcessTextIntentActionsHandler;

    final CursorAnchorInfoNotifier mCursorAnchorInfoNotifier = new CursorAnchorInfoNotifier();

    private final Runnable mShowFloatingToolbar = new Runnable() {
        @Override
        public void run() {
            if (mTextActionMode != null) {
                mTextActionMode.hide(0);  // hide off.
            }
        }
    };

    boolean mIsInsertionActionModeStartPending = false;

    Editor(TextView textView) {
        mTextView = textView;
        // Synchronize the filter list, which places the undo input filter at the end.
        mTextView.setFilters(mTextView.getFilters());
        mProcessTextIntentActionsHandler = new ProcessTextIntentActionsHandler(this);
    }

    ParcelableParcel saveInstanceState() {
        ParcelableParcel state = new ParcelableParcel(getClass().getClassLoader());
        Parcel parcel = state.getParcel();
        mUndoManager.saveInstanceState(parcel);
        mUndoInputFilter.saveInstanceState(parcel);
        return state;
    }

    void restoreInstanceState(ParcelableParcel state) {
        Parcel parcel = state.getParcel();
        mUndoManager.restoreInstanceState(parcel, state.getClassLoader());
        mUndoInputFilter.restoreInstanceState(parcel);
        // Re-associate this object as the owner of undo state.
        mUndoOwner = mUndoManager.getOwner(UNDO_OWNER_TAG, this);
    }

    /**
     * Forgets all undo and redo operations for this Editor.
     */
    void forgetUndoRedo() {
        UndoOwner[] owners = { mUndoOwner };
        mUndoManager.forgetUndos(owners, -1 /* all */);
        mUndoManager.forgetRedos(owners, -1 /* all */);
    }

    boolean canUndo() {
        UndoOwner[] owners = { mUndoOwner };
        return mAllowUndo && mUndoManager.countUndos(owners) > 0;
    }

    boolean canRedo() {
        UndoOwner[] owners = { mUndoOwner };
        return mAllowUndo && mUndoManager.countRedos(owners) > 0;
    }

    void undo() {
        if (!mAllowUndo) {
            return;
        }
        UndoOwner[] owners = { mUndoOwner };
        mUndoManager.undo(owners, 1);  // Undo 1 action.
    }

    void redo() {
        if (!mAllowUndo) {
            return;
        }
        UndoOwner[] owners = { mUndoOwner };
        mUndoManager.redo(owners, 1);  // Redo 1 action.
    }

    void replace() {
        int middle = (mTextView.getSelectionStart() + mTextView.getSelectionEnd()) / 2;
        stopTextActionMode();
        Selection.setSelection((Spannable) mTextView.getText(), middle);
        showSuggestions();
    }

    void onAttachedToWindow() {
        if (mShowErrorAfterAttach) {
            showError();
            mShowErrorAfterAttach = false;
        }
        mTemporaryDetach = false;

        final ViewTreeObserver observer = mTextView.getViewTreeObserver();
        // No need to create the controller.
        // The get method will add the listener on controller creation.
        if (mInsertionPointCursorController != null) {
            observer.addOnTouchModeChangeListener(mInsertionPointCursorController);
        }
        if (mSelectionModifierCursorController != null) {
            mSelectionModifierCursorController.resetTouchOffsets();
            observer.addOnTouchModeChangeListener(mSelectionModifierCursorController);
        }
        updateSpellCheckSpans(0, mTextView.getText().length(),
                true /* create the spell checker if needed */);

        if (mTextView.hasTransientState() &&
                mTextView.getSelectionStart() != mTextView.getSelectionEnd()) {
            // Since transient state is reference counted make sure it stays matched
            // with our own calls to it for managing selection.
            // The action mode callback will set this back again when/if the action mode starts.
            mTextView.setHasTransientState(false);

            // We had an active selection from before, start the selection mode.
            startSelectionActionMode();
        }

        getPositionListener().addSubscriber(mCursorAnchorInfoNotifier, true);
        resumeBlink();
    }

    void onDetachedFromWindow() {
        getPositionListener().removeSubscriber(mCursorAnchorInfoNotifier);

        if (mError != null) {
            hideError();
        }

        suspendBlink();

        if (mInsertionPointCursorController != null) {
            mInsertionPointCursorController.onDetached();
        }

        if (mSelectionModifierCursorController != null) {
            mSelectionModifierCursorController.onDetached();
        }

        if (mShowSuggestionRunnable != null) {
            mTextView.removeCallbacks(mShowSuggestionRunnable);
        }

        // Cancel the single tap delayed runnable.
        if (mInsertionActionModeRunnable != null) {
            mTextView.removeCallbacks(mInsertionActionModeRunnable);
        }

        mTextView.removeCallbacks(mShowFloatingToolbar);

        destroyDisplayListsData();

        if (mSpellChecker != null) {
            mSpellChecker.closeSession();
            // Forces the creation of a new SpellChecker next time this window is created.
            // Will handle the cases where the settings has been changed in the meantime.
            mSpellChecker = null;
        }

        mPreserveDetachedSelection = true;
        hideCursorAndSpanControllers();
        stopTextActionMode();
        mPreserveDetachedSelection = false;
        mTemporaryDetach = false;
    }

    private void destroyDisplayListsData() {
        if (mTextRenderNodes != null) {
            for (int i = 0; i < mTextRenderNodes.length; i++) {
                RenderNode displayList = mTextRenderNodes[i] != null
                        ? mTextRenderNodes[i].renderNode : null;
                if (displayList != null && displayList.isValid()) {
                    displayList.destroyDisplayListData();
                }
            }
        }
    }

    private void showError() {
        if (mTextView.getWindowToken() == null) {
            mShowErrorAfterAttach = true;
            return;
        }

        if (mErrorPopup == null) {
            LayoutInflater inflater = LayoutInflater.from(mTextView.getContext());
            final TextView err = (TextView) inflater.inflate(
                    com.android.internal.R.layout.textview_hint, null);

            final float scale = mTextView.getResources().getDisplayMetrics().density;
            mErrorPopup = new ErrorPopup(err, (int)(200 * scale + 0.5f), (int)(50 * scale + 0.5f));
            mErrorPopup.setFocusable(false);
            // The user is entering text, so the input method is needed.  We
            // don't want the popup to be displayed on top of it.
            mErrorPopup.setInputMethodMode(PopupWindow.INPUT_METHOD_NEEDED);
        }

        TextView tv = (TextView) mErrorPopup.getContentView();
        chooseSize(mErrorPopup, mError, tv);
        tv.setText(mError);

        mErrorPopup.showAsDropDown(mTextView, getErrorX(), getErrorY());
        mErrorPopup.fixDirection(mErrorPopup.isAboveAnchor());
    }

    public void setError(CharSequence error, Drawable icon) {
        mError = TextUtils.stringOrSpannedString(error);
        mErrorWasChanged = true;

        if (mError == null) {
            setErrorIcon(null);
            if (mErrorPopup != null) {
                if (mErrorPopup.isShowing()) {
                    mErrorPopup.dismiss();
                }

                mErrorPopup = null;
            }
            mShowErrorAfterAttach = false;
        } else {
            setErrorIcon(icon);
            if (mTextView.isFocused()) {
                showError();
            }
        }
    }

    private void setErrorIcon(Drawable icon) {
        Drawables dr = mTextView.mDrawables;
        if (dr == null) {
            mTextView.mDrawables = dr = new Drawables(mTextView.getContext());
        }
        dr.setErrorDrawable(icon, mTextView);

        mTextView.resetResolvedDrawables();
        mTextView.invalidate();
        mTextView.requestLayout();
    }

    private void hideError() {
        if (mErrorPopup != null) {
            if (mErrorPopup.isShowing()) {
                mErrorPopup.dismiss();
            }
        }

        mShowErrorAfterAttach = false;
    }

    /**
     * Returns the X offset to make the pointy top of the error point
     * at the middle of the error icon.
     */
    private int getErrorX() {
        /*
         * The "25" is the distance between the point and the right edge
         * of the background
         */
        final float scale = mTextView.getResources().getDisplayMetrics().density;

        final Drawables dr = mTextView.mDrawables;

        final int layoutDirection = mTextView.getLayoutDirection();
        int errorX;
        int offset;
        switch (layoutDirection) {
            default:
            case View.LAYOUT_DIRECTION_LTR:
                offset = - (dr != null ? dr.mDrawableSizeRight : 0) / 2 + (int) (25 * scale + 0.5f);
                errorX = mTextView.getWidth() - mErrorPopup.getWidth() -
                        mTextView.getPaddingRight() + offset;
                break;
            case View.LAYOUT_DIRECTION_RTL:
                offset = (dr != null ? dr.mDrawableSizeLeft : 0) / 2 - (int) (25 * scale + 0.5f);
                errorX = mTextView.getPaddingLeft() + offset;
                break;
        }
        return errorX;
    }

    /**
     * Returns the Y offset to make the pointy top of the error point
     * at the bottom of the error icon.
     */
    private int getErrorY() {
        /*
         * Compound, not extended, because the icon is not clipped
         * if the text height is smaller.
         */
        final int compoundPaddingTop = mTextView.getCompoundPaddingTop();
        int vspace = mTextView.getBottom() - mTextView.getTop() -
                mTextView.getCompoundPaddingBottom() - compoundPaddingTop;

        final Drawables dr = mTextView.mDrawables;

        final int layoutDirection = mTextView.getLayoutDirection();
        int height;
        switch (layoutDirection) {
            default:
            case View.LAYOUT_DIRECTION_LTR:
                height = (dr != null ? dr.mDrawableHeightRight : 0);
                break;
            case View.LAYOUT_DIRECTION_RTL:
                height = (dr != null ? dr.mDrawableHeightLeft : 0);
                break;
        }

        int icontop = compoundPaddingTop + (vspace - height) / 2;

        /*
         * The "2" is the distance between the point and the top edge
         * of the background.
         */
        final float scale = mTextView.getResources().getDisplayMetrics().density;
        return icontop + height - mTextView.getHeight() - (int) (2 * scale + 0.5f);
    }

    void createInputContentTypeIfNeeded() {
        if (mInputContentType == null) {
            mInputContentType = new InputContentType();
        }
    }

    void createInputMethodStateIfNeeded() {
        if (mInputMethodState == null) {
            mInputMethodState = new InputMethodState();
        }
    }

    boolean isCursorVisible() {
        // The default value is true, even when there is no associated Editor
        return mCursorVisible && mTextView.isTextEditable();
    }

    void prepareCursorControllers() {
        boolean windowSupportsHandles = false;

        ViewGroup.LayoutParams params = mTextView.getRootView().getLayoutParams();
        if (params instanceof WindowManager.LayoutParams) {
            WindowManager.LayoutParams windowParams = (WindowManager.LayoutParams) params;
            windowSupportsHandles = windowParams.type < WindowManager.LayoutParams.FIRST_SUB_WINDOW
                    || windowParams.type > WindowManager.LayoutParams.LAST_SUB_WINDOW;
        }

        boolean enabled = windowSupportsHandles && mTextView.getLayout() != null;
        mInsertionControllerEnabled = enabled && isCursorVisible();
        mSelectionControllerEnabled = enabled && mTextView.textCanBeSelected();

        if (!mInsertionControllerEnabled) {
            hideInsertionPointCursorController();
            if (mInsertionPointCursorController != null) {
                mInsertionPointCursorController.onDetached();
                mInsertionPointCursorController = null;
            }
        }

        if (!mSelectionControllerEnabled) {
            stopTextActionMode();
            if (mSelectionModifierCursorController != null) {
                mSelectionModifierCursorController.onDetached();
                mSelectionModifierCursorController = null;
            }
        }
    }

    void hideInsertionPointCursorController() {
        if (mInsertionPointCursorController != null) {
            mInsertionPointCursorController.hide();
        }
    }

    /**
     * Hides the insertion and span controllers.
     */
    void hideCursorAndSpanControllers() {
        hideCursorControllers();
        hideSpanControllers();
    }

    private void hideSpanControllers() {
        if (mSpanController != null) {
            mSpanController.hide();
        }
    }

    private void hideCursorControllers() {
        // When mTextView is not ExtractEditText, we need to distinguish two kinds of focus-lost.
        // One is the true focus lost where suggestions pop-up (if any) should be dismissed, and the
        // other is an side effect of showing the suggestions pop-up itself. We use isShowingUp()
        // to distinguish one from the other.
        if (mSuggestionsPopupWindow != null && ((mTextView.isInExtractedMode()) ||
                !mSuggestionsPopupWindow.isShowingUp())) {
            // Should be done before hide insertion point controller since it triggers a show of it
            mSuggestionsPopupWindow.hide();
        }
        hideInsertionPointCursorController();
    }

    /**
     * Create new SpellCheckSpans on the modified region.
     */
    private void updateSpellCheckSpans(int start, int end, boolean createSpellChecker) {
        // Remove spans whose adjacent characters are text not punctuation
        mTextView.removeAdjacentSuggestionSpans(start);
        mTextView.removeAdjacentSuggestionSpans(end);

        if (mTextView.isTextEditable() && mTextView.isSuggestionsEnabled() &&
                !(mTextView.isInExtractedMode())) {
            if (mSpellChecker == null && createSpellChecker) {
                mSpellChecker = new SpellChecker(mTextView);
            }
            if (mSpellChecker != null) {
                mSpellChecker.spellCheck(start, end);
            }
        }
    }

    void onScreenStateChanged(int screenState) {
        switch (screenState) {
            case View.SCREEN_STATE_ON:
                resumeBlink();
                break;
            case View.SCREEN_STATE_OFF:
                suspendBlink();
                break;
        }
    }

    private void suspendBlink() {
        if (mBlink != null) {
            mBlink.cancel();
        }
    }

    private void resumeBlink() {
        if (mBlink != null) {
            mBlink.uncancel();
            makeBlink();
        }
    }

    void adjustInputType(boolean password, boolean passwordInputType,
            boolean webPasswordInputType, boolean numberPasswordInputType) {
        // mInputType has been set from inputType, possibly modified by mInputMethod.
        // Specialize mInputType to [web]password if we have a text class and the original input
        // type was a password.
        if ((mInputType & EditorInfo.TYPE_MASK_CLASS) == EditorInfo.TYPE_CLASS_TEXT) {
            if (password || passwordInputType) {
                mInputType = (mInputType & ~(EditorInfo.TYPE_MASK_VARIATION))
                        | EditorInfo.TYPE_TEXT_VARIATION_PASSWORD;
            }
            if (webPasswordInputType) {
                mInputType = (mInputType & ~(EditorInfo.TYPE_MASK_VARIATION))
                        | EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD;
            }
        } else if ((mInputType & EditorInfo.TYPE_MASK_CLASS) == EditorInfo.TYPE_CLASS_NUMBER) {
            if (numberPasswordInputType) {
                mInputType = (mInputType & ~(EditorInfo.TYPE_MASK_VARIATION))
                        | EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD;
            }
        }
    }

    private void chooseSize(PopupWindow pop, CharSequence text, TextView tv) {
        int wid = tv.getPaddingLeft() + tv.getPaddingRight();
        int ht = tv.getPaddingTop() + tv.getPaddingBottom();

        int defaultWidthInPixels = mTextView.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.textview_error_popup_default_width);
        Layout l = new StaticLayout(text, tv.getPaint(), defaultWidthInPixels,
                                    Layout.Alignment.ALIGN_NORMAL, 1, 0, true);
        float max = 0;
        for (int i = 0; i < l.getLineCount(); i++) {
            max = Math.max(max, l.getLineWidth(i));
        }

        /*
         * Now set the popup size to be big enough for the text plus the border capped
         * to DEFAULT_MAX_POPUP_WIDTH
         */
        pop.setWidth(wid + (int) Math.ceil(max));
        pop.setHeight(ht + l.getHeight());
    }

    void setFrame() {
        if (mErrorPopup != null) {
            TextView tv = (TextView) mErrorPopup.getContentView();
            chooseSize(mErrorPopup, mError, tv);
            mErrorPopup.update(mTextView, getErrorX(), getErrorY(),
                    mErrorPopup.getWidth(), mErrorPopup.getHeight());
        }
    }

    private int getWordStart(int offset) {
        // FIXME - For this and similar methods we're not doing anything to check if there's
        // a LocaleSpan in the text, this may be something we should try handling or checking for.
        int retOffset = getWordIteratorWithText().prevBoundary(offset);
        if (getWordIteratorWithText().isOnPunctuation(retOffset)) {
            // On punctuation boundary or within group of punctuation, find punctuation start.
            retOffset = getWordIteratorWithText().getPunctuationBeginning(offset);
        } else {
            // Not on a punctuation boundary, find the word start.
            retOffset = getWordIteratorWithText().getPrevWordBeginningOnTwoWordsBoundary(offset);
        }
        if (retOffset == BreakIterator.DONE) {
            return offset;
        }
        return retOffset;
    }

    private int getWordEnd(int offset) {
        int retOffset = getWordIteratorWithText().nextBoundary(offset);
        if (getWordIteratorWithText().isAfterPunctuation(retOffset)) {
            // On punctuation boundary or within group of punctuation, find punctuation end.
            retOffset = getWordIteratorWithText().getPunctuationEnd(offset);
        } else {
            // Not on a punctuation boundary, find the word end.
            retOffset = getWordIteratorWithText().getNextWordEndOnTwoWordBoundary(offset);
        }
        if (retOffset == BreakIterator.DONE) {
            return offset;
        }
        return retOffset;
    }

    /**
     * Adjusts selection to the word under last touch offset. Return true if the operation was
     * successfully performed.
     */
    private boolean selectCurrentWord() {
        if (!mTextView.canSelectText()) {
            return false;
        }

        if (mTextView.hasPasswordTransformationMethod()) {
            // Always select all on a password field.
            // Cut/copy menu entries are not available for passwords, but being able to select all
            // is however useful to delete or paste to replace the entire content.
            return mTextView.selectAllText();
        }

        int inputType = mTextView.getInputType();
        int klass = inputType & InputType.TYPE_MASK_CLASS;
        int variation = inputType & InputType.TYPE_MASK_VARIATION;

        // Specific text field types: select the entire text for these
        if (klass == InputType.TYPE_CLASS_NUMBER ||
                klass == InputType.TYPE_CLASS_PHONE ||
                klass == InputType.TYPE_CLASS_DATETIME ||
                variation == InputType.TYPE_TEXT_VARIATION_URI ||
                variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_FILTER) {
            return mTextView.selectAllText();
        }

        long lastTouchOffsets = getLastTouchOffsets();
        final int minOffset = TextUtils.unpackRangeStartFromLong(lastTouchOffsets);
        final int maxOffset = TextUtils.unpackRangeEndFromLong(lastTouchOffsets);

        // Safety check in case standard touch event handling has been bypassed
        if (minOffset < 0 || minOffset >= mTextView.getText().length()) return false;
        if (maxOffset < 0 || maxOffset >= mTextView.getText().length()) return false;

        int selectionStart, selectionEnd;

        // If a URLSpan (web address, email, phone...) is found at that position, select it.
        URLSpan[] urlSpans = ((Spanned) mTextView.getText()).
                getSpans(minOffset, maxOffset, URLSpan.class);
        if (urlSpans.length >= 1) {
            URLSpan urlSpan = urlSpans[0];
            selectionStart = ((Spanned) mTextView.getText()).getSpanStart(urlSpan);
            selectionEnd = ((Spanned) mTextView.getText()).getSpanEnd(urlSpan);
        } else {
            // FIXME - We should check if there's a LocaleSpan in the text, this may be
            // something we should try handling or checking for.
            final WordIterator wordIterator = getWordIterator();
            wordIterator.setCharSequence(mTextView.getText(), minOffset, maxOffset);

            selectionStart = wordIterator.getBeginning(minOffset);
            selectionEnd = wordIterator.getEnd(maxOffset);

            if (selectionStart == BreakIterator.DONE || selectionEnd == BreakIterator.DONE ||
                    selectionStart == selectionEnd) {
                // Possible when the word iterator does not properly handle the text's language
                long range = getCharClusterRange(minOffset);
                selectionStart = TextUtils.unpackRangeStartFromLong(range);
                selectionEnd = TextUtils.unpackRangeEndFromLong(range);
            }
        }

        Selection.setSelection((Spannable) mTextView.getText(), selectionStart, selectionEnd);
        return selectionEnd > selectionStart;
    }

    void onLocaleChanged() {
        // Will be re-created on demand in getWordIterator with the proper new locale
        mWordIterator = null;
        mWordIteratorWithText = null;
    }

    /**
     * @hide
     */
    public WordIterator getWordIterator() {
        if (mWordIterator == null) {
            mWordIterator = new WordIterator(mTextView.getTextServicesLocale());
        }
        return mWordIterator;
    }

    private WordIterator getWordIteratorWithText() {
        if (mWordIteratorWithText == null) {
            mWordIteratorWithText = new WordIterator(mTextView.getTextServicesLocale());
            mUpdateWordIteratorText = true;
        }
        if (mUpdateWordIteratorText) {
            // FIXME - Shouldn't copy all of the text as only the area of the text relevant
            // to the user's selection is needed. A possible solution would be to
            // copy some number N of characters near the selection and then when the
            // user approaches N then we'd do another copy of the next N characters.
            CharSequence text = mTextView.getText();
            mWordIteratorWithText.setCharSequence(text, 0, text.length());
            mUpdateWordIteratorText = false;
        }
        return mWordIteratorWithText;
    }

    private int getNextCursorOffset(int offset, boolean findAfterGivenOffset) {
        final Layout layout = mTextView.getLayout();
        if (layout == null) return offset;
        final CharSequence text = mTextView.getText();
        final int nextOffset = layout.getPaint().getTextRunCursor(text, 0, text.length(),
                layout.isRtlCharAt(offset) ? Paint.DIRECTION_RTL : Paint.DIRECTION_LTR,
                offset, findAfterGivenOffset ? Paint.CURSOR_AFTER : Paint.CURSOR_BEFORE);
        return nextOffset == -1 ? offset : nextOffset;
    }

    private long getCharClusterRange(int offset) {
        final int textLength = mTextView.getText().length();
        if (offset < textLength) {
            return TextUtils.packRangeInLong(offset, getNextCursorOffset(offset, true));
        }
        if (offset - 1 >= 0) {
            return TextUtils.packRangeInLong(getNextCursorOffset(offset, false), offset);
        }
        return TextUtils.packRangeInLong(offset, offset);
    }

    private boolean touchPositionIsInSelection() {
        int selectionStart = mTextView.getSelectionStart();
        int selectionEnd = mTextView.getSelectionEnd();

        if (selectionStart == selectionEnd) {
            return false;
        }

        if (selectionStart > selectionEnd) {
            int tmp = selectionStart;
            selectionStart = selectionEnd;
            selectionEnd = tmp;
            Selection.setSelection((Spannable) mTextView.getText(), selectionStart, selectionEnd);
        }

        SelectionModifierCursorController selectionController = getSelectionController();
        int minOffset = selectionController.getMinTouchOffset();
        int maxOffset = selectionController.getMaxTouchOffset();

        return ((minOffset >= selectionStart) && (maxOffset < selectionEnd));
    }

    private PositionListener getPositionListener() {
        if (mPositionListener == null) {
            mPositionListener = new PositionListener();
        }
        return mPositionListener;
    }

    private interface TextViewPositionListener {
        public void updatePosition(int parentPositionX, int parentPositionY,
                boolean parentPositionChanged, boolean parentScrolled);
    }

    private boolean isPositionVisible(final float positionX, final float positionY) {
        synchronized (TEMP_POSITION) {
            final float[] position = TEMP_POSITION;
            position[0] = positionX;
            position[1] = positionY;
            View view = mTextView;

            while (view != null) {
                if (view != mTextView) {
                    // Local scroll is already taken into account in positionX/Y
                    position[0] -= view.getScrollX();
                    position[1] -= view.getScrollY();
                }

                if (position[0] < 0 || position[1] < 0 ||
                        position[0] > view.getWidth() || position[1] > view.getHeight()) {
                    return false;
                }

                if (!view.getMatrix().isIdentity()) {
                    view.getMatrix().mapPoints(position);
                }

                position[0] += view.getLeft();
                position[1] += view.getTop();

                final ViewParent parent = view.getParent();
                if (parent instanceof View) {
                    view = (View) parent;
                } else {
                    // We've reached the ViewRoot, stop iterating
                    view = null;
                }
            }
        }

        // We've been able to walk up the view hierarchy and the position was never clipped
        return true;
    }

    private boolean isOffsetVisible(int offset) {
        Layout layout = mTextView.getLayout();
        if (layout == null) return false;

        final int line = layout.getLineForOffset(offset);
        final int lineBottom = layout.getLineBottom(line);
        final int primaryHorizontal = (int) layout.getPrimaryHorizontal(offset);
        return isPositionVisible(primaryHorizontal + mTextView.viewportToContentHorizontalOffset(),
                lineBottom + mTextView.viewportToContentVerticalOffset());
    }

    /** Returns true if the screen coordinates position (x,y) corresponds to a character displayed
     * in the view. Returns false when the position is in the empty space of left/right of text.
     */
    private boolean isPositionOnText(float x, float y) {
        Layout layout = mTextView.getLayout();
        if (layout == null) return false;

        final int line = mTextView.getLineAtCoordinate(y);
        x = mTextView.convertToLocalHorizontalCoordinate(x);

        if (x < layout.getLineLeft(line)) return false;
        if (x > layout.getLineRight(line)) return false;
        return true;
    }

    public boolean performLongClick(boolean handled) {
        // Long press in empty space moves cursor and starts the insertion action mode.
        if (!handled && !isPositionOnText(mLastDownPositionX, mLastDownPositionY) &&
                mInsertionControllerEnabled) {
            final int offset = mTextView.getOffsetForPosition(mLastDownPositionX,
                    mLastDownPositionY);
            stopTextActionMode();
            Selection.setSelection((Spannable) mTextView.getText(), offset);
            getInsertionController().show();
            mIsInsertionActionModeStartPending = true;
            handled = true;
        }

        if (!handled && mTextActionMode != null) {
            // TODO: Fix dragging in extracted mode.
            if (touchPositionIsInSelection() && !mTextView.isInExtractedMode()) {
                // Start a drag
                final int start = mTextView.getSelectionStart();
                final int end = mTextView.getSelectionEnd();
                CharSequence selectedText = mTextView.getTransformedText(start, end);
                ClipData data = ClipData.newPlainText(null, selectedText);
                DragLocalState localState = new DragLocalState(mTextView, start, end);
                mTextView.startDrag(data, getTextThumbnailBuilder(selectedText), localState,
                        View.DRAG_FLAG_GLOBAL);
                stopTextActionMode();
            } else {
                stopTextActionMode();
                selectCurrentWordAndStartDrag();
            }
            handled = true;
        }

        // Start a new selection
        if (!handled) {
            handled = selectCurrentWordAndStartDrag();
        }

        return handled;
    }

    private long getLastTouchOffsets() {
        SelectionModifierCursorController selectionController = getSelectionController();
        final int minOffset = selectionController.getMinTouchOffset();
        final int maxOffset = selectionController.getMaxTouchOffset();
        return TextUtils.packRangeInLong(minOffset, maxOffset);
    }

    void onFocusChanged(boolean focused, int direction) {
        mShowCursor = SystemClock.uptimeMillis();
        ensureEndedBatchEdit();

        if (focused) {
            int selStart = mTextView.getSelectionStart();
            int selEnd = mTextView.getSelectionEnd();

            // SelectAllOnFocus fields are highlighted and not selected. Do not start text selection
            // mode for these, unless there was a specific selection already started.
            final boolean isFocusHighlighted = mSelectAllOnFocus && selStart == 0 &&
                    selEnd == mTextView.getText().length();

            mCreatedWithASelection = mFrozenWithFocus && mTextView.hasSelection() &&
                    !isFocusHighlighted;

            if (!mFrozenWithFocus || (selStart < 0 || selEnd < 0)) {
                // If a tap was used to give focus to that view, move cursor at tap position.
                // Has to be done before onTakeFocus, which can be overloaded.
                final int lastTapPosition = getLastTapPosition();
                if (lastTapPosition >= 0) {
                    Selection.setSelection((Spannable) mTextView.getText(), lastTapPosition);
                }

                // Note this may have to be moved out of the Editor class
                MovementMethod mMovement = mTextView.getMovementMethod();
                if (mMovement != null) {
                    mMovement.onTakeFocus(mTextView, (Spannable) mTextView.getText(), direction);
                }

                // The DecorView does not have focus when the 'Done' ExtractEditText button is
                // pressed. Since it is the ViewAncestor's mView, it requests focus before
                // ExtractEditText clears focus, which gives focus to the ExtractEditText.
                // This special case ensure that we keep current selection in that case.
                // It would be better to know why the DecorView does not have focus at that time.
                if (((mTextView.isInExtractedMode()) || mSelectionMoved) &&
                        selStart >= 0 && selEnd >= 0) {
                    /*
                     * Someone intentionally set the selection, so let them
                     * do whatever it is that they wanted to do instead of
                     * the default on-focus behavior.  We reset the selection
                     * here instead of just skipping the onTakeFocus() call
                     * because some movement methods do something other than
                     * just setting the selection in theirs and we still
                     * need to go through that path.
                     */
                    Selection.setSelection((Spannable) mTextView.getText(), selStart, selEnd);
                }

                if (mSelectAllOnFocus) {
                    mTextView.selectAllText();
                }

                mTouchFocusSelected = true;
            }

            mFrozenWithFocus = false;
            mSelectionMoved = false;

            if (mError != null) {
                showError();
            }

            makeBlink();
        } else {
            if (mError != null) {
                hideError();
            }
            // Don't leave us in the middle of a batch edit.
            mTextView.onEndBatchEdit();

            if (mTextView.isInExtractedMode()) {
                // terminateTextSelectionMode removes selection, which we want to keep when
                // ExtractEditText goes out of focus.
                final int selStart = mTextView.getSelectionStart();
                final int selEnd = mTextView.getSelectionEnd();
                hideCursorAndSpanControllers();
                stopTextActionMode();
                Selection.setSelection((Spannable) mTextView.getText(), selStart, selEnd);
            } else {
                if (mTemporaryDetach) mPreserveDetachedSelection = true;
                hideCursorAndSpanControllers();
                stopTextActionMode();
                if (mTemporaryDetach) mPreserveDetachedSelection = false;
                downgradeEasyCorrectionSpans();
            }
            // No need to create the controller
            if (mSelectionModifierCursorController != null) {
                mSelectionModifierCursorController.resetTouchOffsets();
            }
        }
    }

    /**
     * Downgrades to simple suggestions all the easy correction spans that are not a spell check
     * span.
     */
    private void downgradeEasyCorrectionSpans() {
        CharSequence text = mTextView.getText();
        if (text instanceof Spannable) {
            Spannable spannable = (Spannable) text;
            SuggestionSpan[] suggestionSpans = spannable.getSpans(0,
                    spannable.length(), SuggestionSpan.class);
            for (int i = 0; i < suggestionSpans.length; i++) {
                int flags = suggestionSpans[i].getFlags();
                if ((flags & SuggestionSpan.FLAG_EASY_CORRECT) != 0
                        && (flags & SuggestionSpan.FLAG_MISSPELLED) == 0) {
                    flags &= ~SuggestionSpan.FLAG_EASY_CORRECT;
                    suggestionSpans[i].setFlags(flags);
                }
            }
        }
    }

    void sendOnTextChanged(int start, int after) {
        updateSpellCheckSpans(start, start + after, false);

        // Flip flag to indicate the word iterator needs to have the text reset.
        mUpdateWordIteratorText = true;

        // Hide the controllers as soon as text is modified (typing, procedural...)
        // We do not hide the span controllers, since they can be added when a new text is
        // inserted into the text view (voice IME).
        hideCursorControllers();
        // Reset drag accelerator.
        if (mSelectionModifierCursorController != null) {
            mSelectionModifierCursorController.resetTouchOffsets();
        }
        stopTextActionMode();
    }

    private int getLastTapPosition() {
        // No need to create the controller at that point, no last tap position saved
        if (mSelectionModifierCursorController != null) {
            int lastTapPosition = mSelectionModifierCursorController.getMinTouchOffset();
            if (lastTapPosition >= 0) {
                // Safety check, should not be possible.
                if (lastTapPosition > mTextView.getText().length()) {
                    lastTapPosition = mTextView.getText().length();
                }
                return lastTapPosition;
            }
        }

        return -1;
    }

    void onWindowFocusChanged(boolean hasWindowFocus) {
        if (hasWindowFocus) {
            if (mBlink != null) {
                mBlink.uncancel();
                makeBlink();
            }
            final InputMethodManager imm = InputMethodManager.peekInstance();
            final boolean immFullScreen = (imm != null && imm.isFullscreenMode());
            if (mSelectionModifierCursorController != null && mTextView.hasSelection()
                    && !immFullScreen && mTextActionMode != null) {
                mSelectionModifierCursorController.show();
            }
        } else {
            if (mBlink != null) {
                mBlink.cancel();
            }
            if (mInputContentType != null) {
                mInputContentType.enterDown = false;
            }
            // Order matters! Must be done before onParentLostFocus to rely on isShowingUp
            hideCursorAndSpanControllers();
            if (mSelectionModifierCursorController != null) {
                mSelectionModifierCursorController.hide();
            }
            if (mSuggestionsPopupWindow != null) {
                mSuggestionsPopupWindow.onParentLostFocus();
            }

            // Don't leave us in the middle of a batch edit. Same as in onFocusChanged
            ensureEndedBatchEdit();
        }
    }

    void onTouchEvent(MotionEvent event) {
        updateFloatingToolbarVisibility(event);

        if (hasSelectionController()) {
            getSelectionController().onTouchEvent(event);
        }

        if (mShowSuggestionRunnable != null) {
            mTextView.removeCallbacks(mShowSuggestionRunnable);
            mShowSuggestionRunnable = null;
        }

        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mLastDownPositionX = event.getX();
            mLastDownPositionY = event.getY();

            // Reset this state; it will be re-set if super.onTouchEvent
            // causes focus to move to the view.
            mTouchFocusSelected = false;
            mIgnoreActionUpEvent = false;
        }
    }

    private void updateFloatingToolbarVisibility(MotionEvent event) {
        if (mTextActionMode != null) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    hideFloatingToolbar();
                    break;
                case MotionEvent.ACTION_UP:  // fall through
                case MotionEvent.ACTION_CANCEL:
                    showFloatingToolbar();
            }
        }
    }

    private void hideFloatingToolbar() {
        if (mTextActionMode != null) {
            mTextView.removeCallbacks(mShowFloatingToolbar);
            mTextActionMode.hide(ActionMode.DEFAULT_HIDE_DURATION);
        }
    }

    private void showFloatingToolbar() {
        if (mTextActionMode != null) {
            // Delay "show" so it doesn't interfere with click confirmations
            // or double-clicks that could "dismiss" the floating toolbar.
            int delay = ViewConfiguration.getDoubleTapTimeout();
            mTextView.postDelayed(mShowFloatingToolbar, delay);
        }
    }

    public void beginBatchEdit() {
        mInBatchEditControllers = true;
        final InputMethodState ims = mInputMethodState;
        if (ims != null) {
            int nesting = ++ims.mBatchEditNesting;
            if (nesting == 1) {
                ims.mCursorChanged = false;
                ims.mChangedDelta = 0;
                if (ims.mContentChanged) {
                    // We already have a pending change from somewhere else,
                    // so turn this into a full update.
                    ims.mChangedStart = 0;
                    ims.mChangedEnd = mTextView.getText().length();
                } else {
                    ims.mChangedStart = EXTRACT_UNKNOWN;
                    ims.mChangedEnd = EXTRACT_UNKNOWN;
                    ims.mContentChanged = false;
                }
                mUndoInputFilter.beginBatchEdit();
                mTextView.onBeginBatchEdit();
            }
        }
    }

    public void endBatchEdit() {
        mInBatchEditControllers = false;
        final InputMethodState ims = mInputMethodState;
        if (ims != null) {
            int nesting = --ims.mBatchEditNesting;
            if (nesting == 0) {
                finishBatchEdit(ims);
            }
        }
    }

    void ensureEndedBatchEdit() {
        final InputMethodState ims = mInputMethodState;
        if (ims != null && ims.mBatchEditNesting != 0) {
            ims.mBatchEditNesting = 0;
            finishBatchEdit(ims);
        }
    }

    void finishBatchEdit(final InputMethodState ims) {
        mTextView.onEndBatchEdit();
        mUndoInputFilter.endBatchEdit();

        if (ims.mContentChanged || ims.mSelectionModeChanged) {
            mTextView.updateAfterEdit();
            reportExtractedText();
        } else if (ims.mCursorChanged) {
            // Cheesy way to get us to report the current cursor location.
            mTextView.invalidateCursor();
        }
        // sendUpdateSelection knows to avoid sending if the selection did
        // not actually change.
        sendUpdateSelection();
    }

    static final int EXTRACT_NOTHING = -2;
    static final int EXTRACT_UNKNOWN = -1;

    boolean extractText(ExtractedTextRequest request, ExtractedText outText) {
        return extractTextInternal(request, EXTRACT_UNKNOWN, EXTRACT_UNKNOWN,
                EXTRACT_UNKNOWN, outText);
    }

    private boolean extractTextInternal(@Nullable ExtractedTextRequest request,
            int partialStartOffset, int partialEndOffset, int delta,
            @Nullable ExtractedText outText) {
        if (request == null || outText == null) {
            return false;
        }

        final CharSequence content = mTextView.getText();
        if (content == null) {
            return false;
        }

        if (partialStartOffset != EXTRACT_NOTHING) {
            final int N = content.length();
            if (partialStartOffset < 0) {
                outText.partialStartOffset = outText.partialEndOffset = -1;
                partialStartOffset = 0;
                partialEndOffset = N;
            } else {
                // Now use the delta to determine the actual amount of text
                // we need.
                partialEndOffset += delta;
                // Adjust offsets to ensure we contain full spans.
                if (content instanceof Spanned) {
                    Spanned spanned = (Spanned)content;
                    Object[] spans = spanned.getSpans(partialStartOffset,
                            partialEndOffset, ParcelableSpan.class);
                    int i = spans.length;
                    while (i > 0) {
                        i--;
                        int j = spanned.getSpanStart(spans[i]);
                        if (j < partialStartOffset) partialStartOffset = j;
                        j = spanned.getSpanEnd(spans[i]);
                        if (j > partialEndOffset) partialEndOffset = j;
                    }
                }
                outText.partialStartOffset = partialStartOffset;
                outText.partialEndOffset = partialEndOffset - delta;

                if (partialStartOffset > N) {
                    partialStartOffset = N;
                } else if (partialStartOffset < 0) {
                    partialStartOffset = 0;
                }
                if (partialEndOffset > N) {
                    partialEndOffset = N;
                } else if (partialEndOffset < 0) {
                    partialEndOffset = 0;
                }
            }
            if ((request.flags&InputConnection.GET_TEXT_WITH_STYLES) != 0) {
                outText.text = content.subSequence(partialStartOffset,
                        partialEndOffset);
            } else {
                outText.text = TextUtils.substring(content, partialStartOffset,
                        partialEndOffset);
            }
        } else {
            outText.partialStartOffset = 0;
            outText.partialEndOffset = 0;
            outText.text = "";
        }
        outText.flags = 0;
        if (MetaKeyKeyListener.getMetaState(content, MetaKeyKeyListener.META_SELECTING) != 0) {
            outText.flags |= ExtractedText.FLAG_SELECTING;
        }
        if (mTextView.isSingleLine()) {
            outText.flags |= ExtractedText.FLAG_SINGLE_LINE;
        }
        outText.startOffset = 0;
        outText.selectionStart = mTextView.getSelectionStart();
        outText.selectionEnd = mTextView.getSelectionEnd();
        return true;
    }

    boolean reportExtractedText() {
        final Editor.InputMethodState ims = mInputMethodState;
        if (ims != null) {
            final boolean contentChanged = ims.mContentChanged;
            if (contentChanged || ims.mSelectionModeChanged) {
                ims.mContentChanged = false;
                ims.mSelectionModeChanged = false;
                final ExtractedTextRequest req = ims.mExtractedTextRequest;
                if (req != null) {
                    InputMethodManager imm = InputMethodManager.peekInstance();
                    if (imm != null) {
                        if (TextView.DEBUG_EXTRACT) Log.v(TextView.LOG_TAG,
                                "Retrieving extracted start=" + ims.mChangedStart +
                                " end=" + ims.mChangedEnd +
                                " delta=" + ims.mChangedDelta);
                        if (ims.mChangedStart < 0 && !contentChanged) {
                            ims.mChangedStart = EXTRACT_NOTHING;
                        }
                        if (extractTextInternal(req, ims.mChangedStart, ims.mChangedEnd,
                                ims.mChangedDelta, ims.mExtractedText)) {
                            if (TextView.DEBUG_EXTRACT) Log.v(TextView.LOG_TAG,
                                    "Reporting extracted start=" +
                                    ims.mExtractedText.partialStartOffset +
                                    " end=" + ims.mExtractedText.partialEndOffset +
                                    ": " + ims.mExtractedText.text);

                            imm.updateExtractedText(mTextView, req.token, ims.mExtractedText);
                            ims.mChangedStart = EXTRACT_UNKNOWN;
                            ims.mChangedEnd = EXTRACT_UNKNOWN;
                            ims.mChangedDelta = 0;
                            ims.mContentChanged = false;
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private void sendUpdateSelection() {
        if (null != mInputMethodState && mInputMethodState.mBatchEditNesting <= 0) {
            final InputMethodManager imm = InputMethodManager.peekInstance();
            if (null != imm) {
                final int selectionStart = mTextView.getSelectionStart();
                final int selectionEnd = mTextView.getSelectionEnd();
                int candStart = -1;
                int candEnd = -1;
                if (mTextView.getText() instanceof Spannable) {
                    final Spannable sp = (Spannable) mTextView.getText();
                    candStart = EditableInputConnection.getComposingSpanStart(sp);
                    candEnd = EditableInputConnection.getComposingSpanEnd(sp);
                }
                // InputMethodManager#updateSelection skips sending the message if
                // none of the parameters have changed since the last time we called it.
                imm.updateSelection(mTextView,
                        selectionStart, selectionEnd, candStart, candEnd);
            }
        }
    }

    void onDraw(Canvas canvas, Layout layout, Path highlight, Paint highlightPaint,
            int cursorOffsetVertical) {
        final int selectionStart = mTextView.getSelectionStart();
        final int selectionEnd = mTextView.getSelectionEnd();

        final InputMethodState ims = mInputMethodState;
        if (ims != null && ims.mBatchEditNesting == 0) {
            InputMethodManager imm = InputMethodManager.peekInstance();
            if (imm != null) {
                if (imm.isActive(mTextView)) {
                    if (ims.mContentChanged || ims.mSelectionModeChanged) {
                        // We are in extract mode and the content has changed
                        // in some way... just report complete new text to the
                        // input method.
                        reportExtractedText();
                    }
                }
            }
        }

        if (mCorrectionHighlighter != null) {
            mCorrectionHighlighter.draw(canvas, cursorOffsetVertical);
        }

        if (highlight != null && selectionStart == selectionEnd && mCursorCount > 0) {
            drawCursor(canvas, cursorOffsetVertical);
            // Rely on the drawable entirely, do not draw the cursor line.
            // Has to be done after the IMM related code above which relies on the highlight.
            highlight = null;
        }

        if (mTextView.canHaveDisplayList() && canvas.isHardwareAccelerated()) {
            drawHardwareAccelerated(canvas, layout, highlight, highlightPaint,
                    cursorOffsetVertical);
        } else {
            layout.draw(canvas, highlight, highlightPaint, cursorOffsetVertical);
        }
    }

    private void drawHardwareAccelerated(Canvas canvas, Layout layout, Path highlight,
            Paint highlightPaint, int cursorOffsetVertical) {
        final long lineRange = layout.getLineRangeForDraw(canvas);
        int firstLine = TextUtils.unpackRangeStartFromLong(lineRange);
        int lastLine = TextUtils.unpackRangeEndFromLong(lineRange);
        if (lastLine < 0) return;

        layout.drawBackground(canvas, highlight, highlightPaint, cursorOffsetVertical,
                firstLine, lastLine);

        if (layout instanceof DynamicLayout) {
            if (mTextRenderNodes == null) {
                mTextRenderNodes = ArrayUtils.emptyArray(TextRenderNode.class);
            }

            DynamicLayout dynamicLayout = (DynamicLayout) layout;
            int[] blockEndLines = dynamicLayout.getBlockEndLines();
            int[] blockIndices = dynamicLayout.getBlockIndices();
            final int numberOfBlocks = dynamicLayout.getNumberOfBlocks();
            final int indexFirstChangedBlock = dynamicLayout.getIndexFirstChangedBlock();

            int endOfPreviousBlock = -1;
            int searchStartIndex = 0;
            for (int i = 0; i < numberOfBlocks; i++) {
                int blockEndLine = blockEndLines[i];
                int blockIndex = blockIndices[i];

                final boolean blockIsInvalid = blockIndex == DynamicLayout.INVALID_BLOCK_INDEX;
                if (blockIsInvalid) {
                    blockIndex = getAvailableDisplayListIndex(blockIndices, numberOfBlocks,
                            searchStartIndex);
                    // Note how dynamic layout's internal block indices get updated from Editor
                    blockIndices[i] = blockIndex;
                    if (mTextRenderNodes[blockIndex] != null) {
                        mTextRenderNodes[blockIndex].isDirty = true;
                    }
                    searchStartIndex = blockIndex + 1;
                }

                if (mTextRenderNodes[blockIndex] == null) {
                    mTextRenderNodes[blockIndex] =
                            new TextRenderNode("Text " + blockIndex);
                }

                final boolean blockDisplayListIsInvalid = mTextRenderNodes[blockIndex].needsRecord();
                RenderNode blockDisplayList = mTextRenderNodes[blockIndex].renderNode;
                if (i >= indexFirstChangedBlock || blockDisplayListIsInvalid) {
                    final int blockBeginLine = endOfPreviousBlock + 1;
                    final int top = layout.getLineTop(blockBeginLine);
                    final int bottom = layout.getLineBottom(blockEndLine);
                    int left = 0;
                    int right = mTextView.getWidth();
                    if (mTextView.getHorizontallyScrolling()) {
                        float min = Float.MAX_VALUE;
                        float max = Float.MIN_VALUE;
                        for (int line = blockBeginLine; line <= blockEndLine; line++) {
                            min = Math.min(min, layout.getLineLeft(line));
                            max = Math.max(max, layout.getLineRight(line));
                        }
                        left = (int) min;
                        right = (int) (max + 0.5f);
                    }

                    // Rebuild display list if it is invalid
                    if (blockDisplayListIsInvalid) {
                        final DisplayListCanvas displayListCanvas = blockDisplayList.start(
                                right - left, bottom - top);
                        try {
                            // drawText is always relative to TextView's origin, this translation
                            // brings this range of text back to the top left corner of the viewport
                            displayListCanvas.translate(-left, -top);
                            layout.drawText(displayListCanvas, blockBeginLine, blockEndLine);
                            mTextRenderNodes[blockIndex].isDirty = false;
                            // No need to untranslate, previous context is popped after
                            // drawDisplayList
                        } finally {
                            blockDisplayList.end(displayListCanvas);
                            // Same as drawDisplayList below, handled by our TextView's parent
                            blockDisplayList.setClipToBounds(false);
                        }
                    }

                    // Valid disply list whose index is >= indexFirstChangedBlock
                    // only needs to update its drawing location.
                    blockDisplayList.setLeftTopRightBottom(left, top, right, bottom);
                }

                ((DisplayListCanvas) canvas).drawRenderNode(blockDisplayList);

                endOfPreviousBlock = blockEndLine;
            }

            dynamicLayout.setIndexFirstChangedBlock(numberOfBlocks);
        } else {
            // Boring layout is used for empty and hint text
            layout.drawText(canvas, firstLine, lastLine);
        }
    }

    private int getAvailableDisplayListIndex(int[] blockIndices, int numberOfBlocks,
            int searchStartIndex) {
        int length = mTextRenderNodes.length;
        for (int i = searchStartIndex; i < length; i++) {
            boolean blockIndexFound = false;
            for (int j = 0; j < numberOfBlocks; j++) {
                if (blockIndices[j] == i) {
                    blockIndexFound = true;
                    break;
                }
            }
            if (blockIndexFound) continue;
            return i;
        }

        // No available index found, the pool has to grow
        mTextRenderNodes = GrowingArrayUtils.append(mTextRenderNodes, length, null);
        return length;
    }

    private void drawCursor(Canvas canvas, int cursorOffsetVertical) {
        final boolean translate = cursorOffsetVertical != 0;
        if (translate) canvas.translate(0, cursorOffsetVertical);
        for (int i = 0; i < mCursorCount; i++) {
            mCursorDrawable[i].draw(canvas);
        }
        if (translate) canvas.translate(0, -cursorOffsetVertical);
    }

    /**
     * Invalidates all the sub-display lists that overlap the specified character range
     */
    void invalidateTextDisplayList(Layout layout, int start, int end) {
        if (mTextRenderNodes != null && layout instanceof DynamicLayout) {
            final int firstLine = layout.getLineForOffset(start);
            final int lastLine = layout.getLineForOffset(end);

            DynamicLayout dynamicLayout = (DynamicLayout) layout;
            int[] blockEndLines = dynamicLayout.getBlockEndLines();
            int[] blockIndices = dynamicLayout.getBlockIndices();
            final int numberOfBlocks = dynamicLayout.getNumberOfBlocks();

            int i = 0;
            // Skip the blocks before firstLine
            while (i < numberOfBlocks) {
                if (blockEndLines[i] >= firstLine) break;
                i++;
            }

            // Invalidate all subsequent blocks until lastLine is passed
            while (i < numberOfBlocks) {
                final int blockIndex = blockIndices[i];
                if (blockIndex != DynamicLayout.INVALID_BLOCK_INDEX) {
                    mTextRenderNodes[blockIndex].isDirty = true;
                }
                if (blockEndLines[i] >= lastLine) break;
                i++;
            }
        }
    }

    void invalidateTextDisplayList() {
        if (mTextRenderNodes != null) {
            for (int i = 0; i < mTextRenderNodes.length; i++) {
                if (mTextRenderNodes[i] != null) mTextRenderNodes[i].isDirty = true;
            }
        }
    }

    void updateCursorsPositions() {
        if (mTextView.mCursorDrawableRes == 0) {
            mCursorCount = 0;
            return;
        }

        Layout layout = getActiveLayout();
        final int offset = mTextView.getSelectionStart();
        final int line = layout.getLineForOffset(offset);
        final int top = layout.getLineTop(line);
        final int bottom = layout.getLineTop(line + 1);

        mCursorCount = layout.isLevelBoundary(offset) ? 2 : 1;

        int middle = bottom;
        if (mCursorCount == 2) {
            // Similar to what is done in {@link Layout.#getCursorPath(int, Path, CharSequence)}
            middle = (top + bottom) >> 1;
        }

        boolean clamped = layout.shouldClampCursor(line);
        updateCursorPosition(0, top, middle, layout.getPrimaryHorizontal(offset, clamped));

        if (mCursorCount == 2) {
            updateCursorPosition(1, middle, bottom,
                    layout.getSecondaryHorizontal(offset, clamped));
        }
    }

    /**
     * Start an Insertion action mode.
     */
    void startInsertionActionMode() {
        if (mInsertionActionModeRunnable != null) {
            mTextView.removeCallbacks(mInsertionActionModeRunnable);
        }
        if (extractedTextModeWillBeStarted()) {
            return;
        }
        stopTextActionMode();

        ActionMode.Callback actionModeCallback =
                new TextActionModeCallback(false /* hasSelection */);
        mTextActionMode = mTextView.startActionMode(
                actionModeCallback, ActionMode.TYPE_FLOATING);
        if (mTextActionMode != null && getInsertionController() != null) {
            getInsertionController().show();
        }
    }

    /**
     * Starts a Selection Action Mode with the current selection and ensures the selection handles
     * are shown if there is a selection, otherwise the insertion handle is shown. This should be
     * used when the mode is started from a non-touch event.
     *
     * @return true if the selection mode was actually started.
     */
    boolean startSelectionActionMode() {
        boolean selectionStarted = startSelectionActionModeInternal();
        if (selectionStarted) {
            getSelectionController().show();
        } else if (getInsertionController() != null) {
            getInsertionController().show();
        }
        return selectionStarted;
    }

    /**
     * If the TextView allows text selection, selects the current word when no existing selection
     * was available and starts a drag.
     *
     * @return true if the drag was started.
     */
    private boolean selectCurrentWordAndStartDrag() {
        if (mInsertionActionModeRunnable != null) {
            mTextView.removeCallbacks(mInsertionActionModeRunnable);
        }
        if (extractedTextModeWillBeStarted()) {
            return false;
        }
        if (mTextActionMode != null) {
            mTextActionMode.finish();
        }
        if (!checkFieldAndSelectCurrentWord()) {
            return false;
        }

        // Avoid dismissing the selection if it exists.
        mPreserveDetachedSelection = true;
        stopTextActionMode();
        mPreserveDetachedSelection = false;

        getSelectionController().enterDrag();
        return true;
    }

    /**
     * Checks whether a selection can be performed on the current TextView and if so selects
     * the current word.
     *
     * @return true if there already was a selection or if the current word was selected.
     */
    boolean checkFieldAndSelectCurrentWord() {
        if (!mTextView.canSelectText() || !mTextView.requestFocus()) {
            Log.w(TextView.LOG_TAG,
                    "TextView does not support text selection. Selection cancelled.");
            return false;
        }

        if (!mTextView.hasSelection()) {
            // There may already be a selection on device rotation
            return selectCurrentWord();
        }
        return true;
    }

    private boolean startSelectionActionModeInternal() {
        if (mTextActionMode != null) {
            // Text action mode is already started
            mTextActionMode.invalidate();
            return false;
        }

        if (!checkFieldAndSelectCurrentWord()) {
            return false;
        }

        boolean willExtract = extractedTextModeWillBeStarted();

        // Do not start the action mode when extracted text will show up full screen, which would
        // immediately hide the newly created action bar and would be visually distracting.
        if (!willExtract) {
            ActionMode.Callback actionModeCallback =
                    new TextActionModeCallback(true /* hasSelection */);
            mTextActionMode = mTextView.startActionMode(
                    actionModeCallback, ActionMode.TYPE_FLOATING);
        }

        final boolean selectionStarted = mTextActionMode != null || willExtract;
        if (selectionStarted && !mTextView.isTextSelectable() && mShowSoftInputOnFocus) {
            // Show the IME to be able to replace text, except when selecting non editable text.
            final InputMethodManager imm = InputMethodManager.peekInstance();
            if (imm != null) {
                imm.showSoftInput(mTextView, 0, null);
            }
        }
        return selectionStarted;
    }

    boolean extractedTextModeWillBeStarted() {
        if (!(mTextView.isInExtractedMode())) {
            final InputMethodManager imm = InputMethodManager.peekInstance();
            return  imm != null && imm.isFullscreenMode();
        }
        return false;
    }

    /**
     * @return <code>true</code> if it's reasonable to offer to show suggestions depending on
     * the current cursor position or selection range. This method is consistent with the
     * method to show suggestions {@link SuggestionsPopupWindow#updateSuggestions}.
     */
    private boolean shouldOfferToShowSuggestions() {
        CharSequence text = mTextView.getText();
        if (!(text instanceof Spannable)) return false;

        final Spannable spannable = (Spannable) text;
        final int selectionStart = mTextView.getSelectionStart();
        final int selectionEnd = mTextView.getSelectionEnd();
        final SuggestionSpan[] suggestionSpans = spannable.getSpans(selectionStart, selectionEnd,
                SuggestionSpan.class);
        if (suggestionSpans.length == 0) {
            return false;
        }
        if (selectionStart == selectionEnd) {
            // Spans overlap the cursor.
            for (int i = 0; i < suggestionSpans.length; i++) {
                if (suggestionSpans[i].getSuggestions().length > 0) {
                    return true;
                }
            }
            return false;
        }
        int minSpanStart = mTextView.getText().length();
        int maxSpanEnd = 0;
        int unionOfSpansCoveringSelectionStartStart = mTextView.getText().length();
        int unionOfSpansCoveringSelectionStartEnd = 0;
        boolean hasValidSuggestions = false;
        for (int i = 0; i < suggestionSpans.length; i++) {
            final int spanStart = spannable.getSpanStart(suggestionSpans[i]);
            final int spanEnd = spannable.getSpanEnd(suggestionSpans[i]);
            minSpanStart = Math.min(minSpanStart, spanStart);
            maxSpanEnd = Math.max(maxSpanEnd, spanEnd);
            if (selectionStart < spanStart || selectionStart > spanEnd) {
                // The span doesn't cover the current selection start point.
                continue;
            }
            hasValidSuggestions =
                    hasValidSuggestions || suggestionSpans[i].getSuggestions().length > 0;
            unionOfSpansCoveringSelectionStartStart =
                    Math.min(unionOfSpansCoveringSelectionStartStart, spanStart);
            unionOfSpansCoveringSelectionStartEnd =
                    Math.max(unionOfSpansCoveringSelectionStartEnd, spanEnd);
        }
        if (!hasValidSuggestions) {
            return false;
        }
        if (unionOfSpansCoveringSelectionStartStart >= unionOfSpansCoveringSelectionStartEnd) {
            // No spans cover the selection start point.
            return false;
        }
        if (minSpanStart < unionOfSpansCoveringSelectionStartStart
                || maxSpanEnd > unionOfSpansCoveringSelectionStartEnd) {
            // There is a span that is not covered by the union. In this case, we soouldn't offer
            // to show suggestions as it's confusing.
            return false;
        }
        return true;
    }

    /**
     * @return <code>true</code> if the cursor is inside an {@link SuggestionSpan} with
     * {@link SuggestionSpan#FLAG_EASY_CORRECT} set.
     */
    private boolean isCursorInsideEasyCorrectionSpan() {
        Spannable spannable = (Spannable) mTextView.getText();
        SuggestionSpan[] suggestionSpans = spannable.getSpans(mTextView.getSelectionStart(),
                mTextView.getSelectionEnd(), SuggestionSpan.class);
        for (int i = 0; i < suggestionSpans.length; i++) {
            if ((suggestionSpans[i].getFlags() & SuggestionSpan.FLAG_EASY_CORRECT) != 0) {
                return true;
            }
        }
        return false;
    }

    void onTouchUpEvent(MotionEvent event) {
        boolean selectAllGotFocus = mSelectAllOnFocus && mTextView.didTouchFocusSelect();
        hideCursorAndSpanControllers();
        stopTextActionMode();
        CharSequence text = mTextView.getText();
        if (!selectAllGotFocus && text.length() > 0) {
            // Move cursor
            final int offset = mTextView.getOffsetForPosition(event.getX(), event.getY());
            Selection.setSelection((Spannable) text, offset);
            if (mSpellChecker != null) {
                // When the cursor moves, the word that was typed may need spell check
                mSpellChecker.onSelectionChanged();
            }

            if (!extractedTextModeWillBeStarted()) {
                if (isCursorInsideEasyCorrectionSpan()) {
                    // Cancel the single tap delayed runnable.
                    if (mInsertionActionModeRunnable != null) {
                        mTextView.removeCallbacks(mInsertionActionModeRunnable);
                    }

                    mShowSuggestionRunnable = new Runnable() {
                        public void run() {
                            showSuggestions();
                        }
                    };
                    // removeCallbacks is performed on every touch
                    mTextView.postDelayed(mShowSuggestionRunnable,
                            ViewConfiguration.getDoubleTapTimeout());
                } else if (hasInsertionController()) {
                    getInsertionController().show();
                }
            }
        }
    }

    protected void stopTextActionMode() {
        if (mTextActionMode != null) {
            // This will hide the mSelectionModifierCursorController
            mTextActionMode.finish();
        }
    }

    /**
     * @return True if this view supports insertion handles.
     */
    boolean hasInsertionController() {
        return mInsertionControllerEnabled;
    }

    /**
     * @return True if this view supports selection handles.
     */
    boolean hasSelectionController() {
        return mSelectionControllerEnabled;
    }

    InsertionPointCursorController getInsertionController() {
        if (!mInsertionControllerEnabled) {
            return null;
        }

        if (mInsertionPointCursorController == null) {
            mInsertionPointCursorController = new InsertionPointCursorController();

            final ViewTreeObserver observer = mTextView.getViewTreeObserver();
            observer.addOnTouchModeChangeListener(mInsertionPointCursorController);
        }

        return mInsertionPointCursorController;
    }

    SelectionModifierCursorController getSelectionController() {
        if (!mSelectionControllerEnabled) {
            return null;
        }

        if (mSelectionModifierCursorController == null) {
            mSelectionModifierCursorController = new SelectionModifierCursorController();

            final ViewTreeObserver observer = mTextView.getViewTreeObserver();
            observer.addOnTouchModeChangeListener(mSelectionModifierCursorController);
        }

        return mSelectionModifierCursorController;
    }

    private void updateCursorPosition(int cursorIndex, int top, int bottom, float horizontal) {
        if (mCursorDrawable[cursorIndex] == null)
            mCursorDrawable[cursorIndex] = mTextView.getContext().getDrawable(
                    mTextView.mCursorDrawableRes);

        if (mTempRect == null) mTempRect = new Rect();
        mCursorDrawable[cursorIndex].getPadding(mTempRect);
        final int width = mCursorDrawable[cursorIndex].getIntrinsicWidth();
        horizontal = Math.max(0.5f, horizontal - 0.5f);
        final int left = (int) (horizontal) - mTempRect.left;
        mCursorDrawable[cursorIndex].setBounds(left, top - mTempRect.top, left + width,
                bottom + mTempRect.bottom);
    }

    /**
     * Called by the framework in response to a text auto-correction (such as fixing a typo using a
     * a dictionary) from the current input method, provided by it calling
     * {@link InputConnection#commitCorrection} InputConnection.commitCorrection()}. The default
     * implementation flashes the background of the corrected word to provide feedback to the user.
     *
     * @param info The auto correct info about the text that was corrected.
     */
    public void onCommitCorrection(CorrectionInfo info) {
        if (mCorrectionHighlighter == null) {
            mCorrectionHighlighter = new CorrectionHighlighter();
        } else {
            mCorrectionHighlighter.invalidate(false);
        }

        mCorrectionHighlighter.highlight(info);
    }

    void showSuggestions() {
        if (mSuggestionsPopupWindow == null) {
            mSuggestionsPopupWindow = new SuggestionsPopupWindow();
        }
        hideCursorAndSpanControllers();
        stopTextActionMode();
        mSuggestionsPopupWindow.show();
    }

    void onScrollChanged() {
        if (mPositionListener != null) {
            mPositionListener.onScrollChanged();
        }
        if (mTextActionMode != null) {
            mTextActionMode.invalidateContentRect();
        }
    }

    /**
     * @return True when the TextView isFocused and has a valid zero-length selection (cursor).
     */
    private boolean shouldBlink() {
        if (!isCursorVisible() || !mTextView.isFocused()) return false;

        final int start = mTextView.getSelectionStart();
        if (start < 0) return false;

        final int end = mTextView.getSelectionEnd();
        if (end < 0) return false;

        return start == end;
    }

    void makeBlink() {
        if (shouldBlink()) {
            mShowCursor = SystemClock.uptimeMillis();
            if (mBlink == null) mBlink = new Blink();
            mBlink.removeCallbacks(mBlink);
            mBlink.postAtTime(mBlink, mShowCursor + BLINK);
        } else {
            if (mBlink != null) mBlink.removeCallbacks(mBlink);
        }
    }

    private class Blink extends Handler implements Runnable {
        private boolean mCancelled;

        public void run() {
            if (mCancelled) {
                return;
            }

            removeCallbacks(Blink.this);

            if (shouldBlink()) {
                if (mTextView.getLayout() != null) {
                    mTextView.invalidateCursorPath();
                }

                postAtTime(this, SystemClock.uptimeMillis() + BLINK);
            }
        }

        void cancel() {
            if (!mCancelled) {
                removeCallbacks(Blink.this);
                mCancelled = true;
            }
        }

        void uncancel() {
            mCancelled = false;
        }
    }

    private DragShadowBuilder getTextThumbnailBuilder(CharSequence text) {
        TextView shadowView = (TextView) View.inflate(mTextView.getContext(),
                com.android.internal.R.layout.text_drag_thumbnail, null);

        if (shadowView == null) {
            throw new IllegalArgumentException("Unable to inflate text drag thumbnail");
        }

        if (text.length() > DRAG_SHADOW_MAX_TEXT_LENGTH) {
            text = text.subSequence(0, DRAG_SHADOW_MAX_TEXT_LENGTH);
        }
        shadowView.setText(text);
        shadowView.setTextColor(mTextView.getTextColors());

        shadowView.setTextAppearance(R.styleable.Theme_textAppearanceLarge);
        shadowView.setGravity(Gravity.CENTER);

        shadowView.setLayoutParams(new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        final int size = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        shadowView.measure(size, size);

        shadowView.layout(0, 0, shadowView.getMeasuredWidth(), shadowView.getMeasuredHeight());
        shadowView.invalidate();
        return new DragShadowBuilder(shadowView);
    }

    private static class DragLocalState {
        public TextView sourceTextView;
        public int start, end;

        public DragLocalState(TextView sourceTextView, int start, int end) {
            this.sourceTextView = sourceTextView;
            this.start = start;
            this.end = end;
        }
    }

    void onDrop(DragEvent event) {
        StringBuilder content = new StringBuilder("");
        ClipData clipData = event.getClipData();
        final int itemCount = clipData.getItemCount();
        for (int i=0; i < itemCount; i++) {
            Item item = clipData.getItemAt(i);
            content.append(item.coerceToStyledText(mTextView.getContext()));
        }

        final int offset = mTextView.getOffsetForPosition(event.getX(), event.getY());

        Object localState = event.getLocalState();
        DragLocalState dragLocalState = null;
        if (localState instanceof DragLocalState) {
            dragLocalState = (DragLocalState) localState;
        }
        boolean dragDropIntoItself = dragLocalState != null &&
                dragLocalState.sourceTextView == mTextView;

        if (dragDropIntoItself) {
            if (offset >= dragLocalState.start && offset < dragLocalState.end) {
                // A drop inside the original selection discards the drop.
                return;
            }
        }

        final int originalLength = mTextView.getText().length();
        int min = offset;
        int max = offset;

        Selection.setSelection((Spannable) mTextView.getText(), max);
        mTextView.replaceText_internal(min, max, content);

        if (dragDropIntoItself) {
            int dragSourceStart = dragLocalState.start;
            int dragSourceEnd = dragLocalState.end;
            if (max <= dragSourceStart) {
                // Inserting text before selection has shifted positions
                final int shift = mTextView.getText().length() - originalLength;
                dragSourceStart += shift;
                dragSourceEnd += shift;
            }

            // Delete original selection
            mTextView.deleteText_internal(dragSourceStart, dragSourceEnd);

            // Make sure we do not leave two adjacent spaces.
            final int prevCharIdx = Math.max(0,  dragSourceStart - 1);
            final int nextCharIdx = Math.min(mTextView.getText().length(), dragSourceStart + 1);
            if (nextCharIdx > prevCharIdx + 1) {
                CharSequence t = mTextView.getTransformedText(prevCharIdx, nextCharIdx);
                if (Character.isSpaceChar(t.charAt(0)) && Character.isSpaceChar(t.charAt(1))) {
                    mTextView.deleteText_internal(prevCharIdx, prevCharIdx + 1);
                }
            }
        }
    }

    public void addSpanWatchers(Spannable text) {
        final int textLength = text.length();

        if (mKeyListener != null) {
            text.setSpan(mKeyListener, 0, textLength, Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        }

        if (mSpanController == null) {
            mSpanController = new SpanController();
        }
        text.setSpan(mSpanController, 0, textLength, Spanned.SPAN_INCLUSIVE_INCLUSIVE);
    }

    /**
     * Controls the {@link EasyEditSpan} monitoring when it is added, and when the related
     * pop-up should be displayed.
     * Also monitors {@link Selection} to call back to the attached input method.
     */
    class SpanController implements SpanWatcher {

        private static final int DISPLAY_TIMEOUT_MS = 3000; // 3 secs

        private EasyEditPopupWindow mPopupWindow;

        private Runnable mHidePopup;

        // This function is pure but inner classes can't have static functions
        private boolean isNonIntermediateSelectionSpan(final Spannable text,
                final Object span) {
            return (Selection.SELECTION_START == span || Selection.SELECTION_END == span)
                    && (text.getSpanFlags(span) & Spanned.SPAN_INTERMEDIATE) == 0;
        }

        @Override
        public void onSpanAdded(Spannable text, Object span, int start, int end) {
            if (isNonIntermediateSelectionSpan(text, span)) {
                sendUpdateSelection();
            } else if (span instanceof EasyEditSpan) {
                if (mPopupWindow == null) {
                    mPopupWindow = new EasyEditPopupWindow();
                    mHidePopup = new Runnable() {
                        @Override
                        public void run() {
                            hide();
                        }
                    };
                }

                // Make sure there is only at most one EasyEditSpan in the text
                if (mPopupWindow.mEasyEditSpan != null) {
                    mPopupWindow.mEasyEditSpan.setDeleteEnabled(false);
                }

                mPopupWindow.setEasyEditSpan((EasyEditSpan) span);
                mPopupWindow.setOnDeleteListener(new EasyEditDeleteListener() {
                    @Override
                    public void onDeleteClick(EasyEditSpan span) {
                        Editable editable = (Editable) mTextView.getText();
                        int start = editable.getSpanStart(span);
                        int end = editable.getSpanEnd(span);
                        if (start >= 0 && end >= 0) {
                            sendEasySpanNotification(EasyEditSpan.TEXT_DELETED, span);
                            mTextView.deleteText_internal(start, end);
                        }
                        editable.removeSpan(span);
                    }
                });

                if (mTextView.getWindowVisibility() != View.VISIBLE) {
                    // The window is not visible yet, ignore the text change.
                    return;
                }

                if (mTextView.getLayout() == null) {
                    // The view has not been laid out yet, ignore the text change
                    return;
                }

                if (extractedTextModeWillBeStarted()) {
                    // The input is in extract mode. Do not handle the easy edit in
                    // the original TextView, as the ExtractEditText will do
                    return;
                }

                mPopupWindow.show();
                mTextView.removeCallbacks(mHidePopup);
                mTextView.postDelayed(mHidePopup, DISPLAY_TIMEOUT_MS);
            }
        }

        @Override
        public void onSpanRemoved(Spannable text, Object span, int start, int end) {
            if (isNonIntermediateSelectionSpan(text, span)) {
                sendUpdateSelection();
            } else if (mPopupWindow != null && span == mPopupWindow.mEasyEditSpan) {
                hide();
            }
        }

        @Override
        public void onSpanChanged(Spannable text, Object span, int previousStart, int previousEnd,
                int newStart, int newEnd) {
            if (isNonIntermediateSelectionSpan(text, span)) {
                sendUpdateSelection();
            } else if (mPopupWindow != null && span instanceof EasyEditSpan) {
                EasyEditSpan easyEditSpan = (EasyEditSpan) span;
                sendEasySpanNotification(EasyEditSpan.TEXT_MODIFIED, easyEditSpan);
                text.removeSpan(easyEditSpan);
            }
        }

        public void hide() {
            if (mPopupWindow != null) {
                mPopupWindow.hide();
                mTextView.removeCallbacks(mHidePopup);
            }
        }

        private void sendEasySpanNotification(int textChangedType, EasyEditSpan span) {
            try {
                PendingIntent pendingIntent = span.getPendingIntent();
                if (pendingIntent != null) {
                    Intent intent = new Intent();
                    intent.putExtra(EasyEditSpan.EXTRA_TEXT_CHANGED_TYPE, textChangedType);
                    pendingIntent.send(mTextView.getContext(), 0, intent);
                }
            } catch (CanceledException e) {
                // This should not happen, as we should try to send the intent only once.
                Log.w(TAG, "PendingIntent for notification cannot be sent", e);
            }
        }
    }

    /**
     * Listens for the delete event triggered by {@link EasyEditPopupWindow}.
     */
    private interface EasyEditDeleteListener {

        /**
         * Clicks the delete pop-up.
         */
        void onDeleteClick(EasyEditSpan span);
    }

    /**
     * Displays the actions associated to an {@link EasyEditSpan}. The pop-up is controlled
     * by {@link SpanController}.
     */
    private class EasyEditPopupWindow extends PinnedPopupWindow
            implements OnClickListener {
        private static final int POPUP_TEXT_LAYOUT =
                com.android.internal.R.layout.text_edit_action_popup_text;
        private TextView mDeleteTextView;
        private EasyEditSpan mEasyEditSpan;
        private EasyEditDeleteListener mOnDeleteListener;

        @Override
        protected void createPopupWindow() {
            mPopupWindow = new PopupWindow(mTextView.getContext(), null,
                    com.android.internal.R.attr.textSelectHandleWindowStyle);
            mPopupWindow.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
            mPopupWindow.setClippingEnabled(true);
        }

        @Override
        protected void initContentView() {
            LinearLayout linearLayout = new LinearLayout(mTextView.getContext());
            linearLayout.setOrientation(LinearLayout.HORIZONTAL);
            mContentView = linearLayout;
            mContentView.setBackgroundResource(
                    com.android.internal.R.drawable.text_edit_side_paste_window);

            LayoutInflater inflater = (LayoutInflater)mTextView.getContext().
                    getSystemService(Context.LAYOUT_INFLATER_SERVICE);

            LayoutParams wrapContent = new LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);

            mDeleteTextView = (TextView) inflater.inflate(POPUP_TEXT_LAYOUT, null);
            mDeleteTextView.setLayoutParams(wrapContent);
            mDeleteTextView.setText(com.android.internal.R.string.delete);
            mDeleteTextView.setOnClickListener(this);
            mContentView.addView(mDeleteTextView);
        }

        public void setEasyEditSpan(EasyEditSpan easyEditSpan) {
            mEasyEditSpan = easyEditSpan;
        }

        private void setOnDeleteListener(EasyEditDeleteListener listener) {
            mOnDeleteListener = listener;
        }

        @Override
        public void onClick(View view) {
            if (view == mDeleteTextView
                    && mEasyEditSpan != null && mEasyEditSpan.isDeleteEnabled()
                    && mOnDeleteListener != null) {
                mOnDeleteListener.onDeleteClick(mEasyEditSpan);
            }
        }

        @Override
        public void hide() {
            if (mEasyEditSpan != null) {
                mEasyEditSpan.setDeleteEnabled(false);
            }
            mOnDeleteListener = null;
            super.hide();
        }

        @Override
        protected int getTextOffset() {
            // Place the pop-up at the end of the span
            Editable editable = (Editable) mTextView.getText();
            return editable.getSpanEnd(mEasyEditSpan);
        }

        @Override
        protected int getVerticalLocalPosition(int line) {
            return mTextView.getLayout().getLineBottom(line);
        }

        @Override
        protected int clipVertically(int positionY) {
            // As we display the pop-up below the span, no vertical clipping is required.
            return positionY;
        }
    }

    private class PositionListener implements ViewTreeObserver.OnPreDrawListener {
        // 3 handles
        // 3 ActionPopup [replace, suggestion, easyedit] (suggestionsPopup first hides the others)
        // 1 CursorAnchorInfoNotifier
        private final int MAXIMUM_NUMBER_OF_LISTENERS = 7;
        private TextViewPositionListener[] mPositionListeners =
                new TextViewPositionListener[MAXIMUM_NUMBER_OF_LISTENERS];
        private boolean mCanMove[] = new boolean[MAXIMUM_NUMBER_OF_LISTENERS];
        private boolean mPositionHasChanged = true;
        // Absolute position of the TextView with respect to its parent window
        private int mPositionX, mPositionY;
        private int mNumberOfListeners;
        private boolean mScrollHasChanged;
        final int[] mTempCoords = new int[2];

        public void addSubscriber(TextViewPositionListener positionListener, boolean canMove) {
            if (mNumberOfListeners == 0) {
                updatePosition();
                ViewTreeObserver vto = mTextView.getViewTreeObserver();
                vto.addOnPreDrawListener(this);
            }

            int emptySlotIndex = -1;
            for (int i = 0; i < MAXIMUM_NUMBER_OF_LISTENERS; i++) {
                TextViewPositionListener listener = mPositionListeners[i];
                if (listener == positionListener) {
                    return;
                } else if (emptySlotIndex < 0 && listener == null) {
                    emptySlotIndex = i;
                }
            }

            mPositionListeners[emptySlotIndex] = positionListener;
            mCanMove[emptySlotIndex] = canMove;
            mNumberOfListeners++;
        }

        public void removeSubscriber(TextViewPositionListener positionListener) {
            for (int i = 0; i < MAXIMUM_NUMBER_OF_LISTENERS; i++) {
                if (mPositionListeners[i] == positionListener) {
                    mPositionListeners[i] = null;
                    mNumberOfListeners--;
                    break;
                }
            }

            if (mNumberOfListeners == 0) {
                ViewTreeObserver vto = mTextView.getViewTreeObserver();
                vto.removeOnPreDrawListener(this);
            }
        }

        public int getPositionX() {
            return mPositionX;
        }

        public int getPositionY() {
            return mPositionY;
        }

        @Override
        public boolean onPreDraw() {
            updatePosition();

            for (int i = 0; i < MAXIMUM_NUMBER_OF_LISTENERS; i++) {
                if (mPositionHasChanged || mScrollHasChanged || mCanMove[i]) {
                    TextViewPositionListener positionListener = mPositionListeners[i];
                    if (positionListener != null) {
                        positionListener.updatePosition(mPositionX, mPositionY,
                                mPositionHasChanged, mScrollHasChanged);
                    }
                }
            }

            mScrollHasChanged = false;
            return true;
        }

        private void updatePosition() {
            mTextView.getLocationInWindow(mTempCoords);

            mPositionHasChanged = mTempCoords[0] != mPositionX || mTempCoords[1] != mPositionY;

            mPositionX = mTempCoords[0];
            mPositionY = mTempCoords[1];
        }

        public void onScrollChanged() {
            mScrollHasChanged = true;
        }
    }

    private abstract class PinnedPopupWindow implements TextViewPositionListener {
        protected PopupWindow mPopupWindow;
        protected ViewGroup mContentView;
        int mPositionX, mPositionY;

        protected abstract void createPopupWindow();
        protected abstract void initContentView();
        protected abstract int getTextOffset();
        protected abstract int getVerticalLocalPosition(int line);
        protected abstract int clipVertically(int positionY);

        public PinnedPopupWindow() {
            createPopupWindow();

            mPopupWindow.setWindowLayoutType(
                    WindowManager.LayoutParams.TYPE_APPLICATION_ABOVE_SUB_PANEL);
            mPopupWindow.setWidth(ViewGroup.LayoutParams.WRAP_CONTENT);
            mPopupWindow.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);

            initContentView();

            LayoutParams wrapContent = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            mContentView.setLayoutParams(wrapContent);

            mPopupWindow.setContentView(mContentView);
        }

        public void show() {
            getPositionListener().addSubscriber(this, false /* offset is fixed */);

            computeLocalPosition();

            final PositionListener positionListener = getPositionListener();
            updatePosition(positionListener.getPositionX(), positionListener.getPositionY());
        }

        protected void measureContent() {
            final DisplayMetrics displayMetrics = mTextView.getResources().getDisplayMetrics();
            mContentView.measure(
                    View.MeasureSpec.makeMeasureSpec(displayMetrics.widthPixels,
                            View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(displayMetrics.heightPixels,
                            View.MeasureSpec.AT_MOST));
        }

        /* The popup window will be horizontally centered on the getTextOffset() and vertically
         * positioned according to viewportToContentHorizontalOffset.
         *
         * This method assumes that mContentView has properly been measured from its content. */
        private void computeLocalPosition() {
            measureContent();
            final int width = mContentView.getMeasuredWidth();
            final int offset = getTextOffset();
            mPositionX = (int) (mTextView.getLayout().getPrimaryHorizontal(offset) - width / 2.0f);
            mPositionX += mTextView.viewportToContentHorizontalOffset();

            final int line = mTextView.getLayout().getLineForOffset(offset);
            mPositionY = getVerticalLocalPosition(line);
            mPositionY += mTextView.viewportToContentVerticalOffset();
        }

        private void updatePosition(int parentPositionX, int parentPositionY) {
            int positionX = parentPositionX + mPositionX;
            int positionY = parentPositionY + mPositionY;

            positionY = clipVertically(positionY);

            // Horizontal clipping
            final DisplayMetrics displayMetrics = mTextView.getResources().getDisplayMetrics();
            final int width = mContentView.getMeasuredWidth();
            positionX = Math.min(displayMetrics.widthPixels - width, positionX);
            positionX = Math.max(0, positionX);

            if (isShowing()) {
                mPopupWindow.update(positionX, positionY, -1, -1);
            } else {
                mPopupWindow.showAtLocation(mTextView, Gravity.NO_GRAVITY,
                        positionX, positionY);
            }
        }

        public void hide() {
            mPopupWindow.dismiss();
            getPositionListener().removeSubscriber(this);
        }

        @Override
        public void updatePosition(int parentPositionX, int parentPositionY,
                boolean parentPositionChanged, boolean parentScrolled) {
            // Either parentPositionChanged or parentScrolled is true, check if still visible
            if (isShowing() && isOffsetVisible(getTextOffset())) {
                if (parentScrolled) computeLocalPosition();
                updatePosition(parentPositionX, parentPositionY);
            } else {
                hide();
            }
        }

        public boolean isShowing() {
            return mPopupWindow.isShowing();
        }
    }

    private class SuggestionsPopupWindow extends PinnedPopupWindow implements OnItemClickListener {
        private static final int MAX_NUMBER_SUGGESTIONS = SuggestionSpan.SUGGESTIONS_MAX_SIZE;
        private static final int ADD_TO_DICTIONARY = -1;
        private static final int DELETE_TEXT = -2;
        private SuggestionInfo[] mSuggestionInfos;
        private int mNumberOfSuggestions;
        private boolean mCursorWasVisibleBeforeSuggestions;
        private boolean mIsShowingUp = false;
        private SuggestionAdapter mSuggestionsAdapter;
        private final Comparator<SuggestionSpan> mSuggestionSpanComparator;
        private final HashMap<SuggestionSpan, Integer> mSpansLengths;

        private class CustomPopupWindow extends PopupWindow {
            public CustomPopupWindow(Context context, int defStyleAttr) {
                super(context, null, defStyleAttr);
            }

            @Override
            public void dismiss() {
                super.dismiss();

                getPositionListener().removeSubscriber(SuggestionsPopupWindow.this);

                // Safe cast since show() checks that mTextView.getText() is an Editable
                ((Spannable) mTextView.getText()).removeSpan(mSuggestionRangeSpan);

                mTextView.setCursorVisible(mCursorWasVisibleBeforeSuggestions);
                if (hasInsertionController()) {
                    getInsertionController().show();
                }
            }
        }

        public SuggestionsPopupWindow() {
            mCursorWasVisibleBeforeSuggestions = mCursorVisible;
            mSuggestionSpanComparator = new SuggestionSpanComparator();
            mSpansLengths = new HashMap<SuggestionSpan, Integer>();
        }

        @Override
        protected void createPopupWindow() {
            mPopupWindow = new CustomPopupWindow(mTextView.getContext(),
                com.android.internal.R.attr.textSuggestionsWindowStyle);
            mPopupWindow.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
            mPopupWindow.setFocusable(true);
            mPopupWindow.setClippingEnabled(false);
        }

        @Override
        protected void initContentView() {
            ListView listView = new ListView(mTextView.getContext());
            mSuggestionsAdapter = new SuggestionAdapter();
            listView.setAdapter(mSuggestionsAdapter);
            listView.setOnItemClickListener(this);
            mContentView = listView;

            // Inflate the suggestion items once and for all. + 2 for add to dictionary and delete
            mSuggestionInfos = new SuggestionInfo[MAX_NUMBER_SUGGESTIONS + 2];
            for (int i = 0; i < mSuggestionInfos.length; i++) {
                mSuggestionInfos[i] = new SuggestionInfo();
            }
        }

        public boolean isShowingUp() {
            return mIsShowingUp;
        }

        public void onParentLostFocus() {
            mIsShowingUp = false;
        }

        private class SuggestionInfo {
            int suggestionStart, suggestionEnd; // range of actual suggestion within text
            SuggestionSpan suggestionSpan; // the SuggestionSpan that this TextView represents
            int suggestionIndex; // the index of this suggestion inside suggestionSpan
            SpannableStringBuilder text = new SpannableStringBuilder();
            TextAppearanceSpan highlightSpan = new TextAppearanceSpan(mTextView.getContext(),
                    android.R.style.TextAppearance_SuggestionHighlight);
        }

        private class SuggestionAdapter extends BaseAdapter {
            private LayoutInflater mInflater = (LayoutInflater) mTextView.getContext().
                    getSystemService(Context.LAYOUT_INFLATER_SERVICE);

            @Override
            public int getCount() {
                return mNumberOfSuggestions;
            }

            @Override
            public Object getItem(int position) {
                return mSuggestionInfos[position];
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView textView = (TextView) convertView;

                if (textView == null) {
                    textView = (TextView) mInflater.inflate(mTextView.mTextEditSuggestionItemLayout,
                            parent, false);
                }

                final SuggestionInfo suggestionInfo = mSuggestionInfos[position];
                textView.setText(suggestionInfo.text);

                if (suggestionInfo.suggestionIndex == ADD_TO_DICTIONARY ||
                suggestionInfo.suggestionIndex == DELETE_TEXT) {
                    textView.setBackgroundColor(Color.TRANSPARENT);
                } else {
                    textView.setBackgroundColor(Color.WHITE);
                }

                return textView;
            }
        }

        private class SuggestionSpanComparator implements Comparator<SuggestionSpan> {
            public int compare(SuggestionSpan span1, SuggestionSpan span2) {
                final int flag1 = span1.getFlags();
                final int flag2 = span2.getFlags();
                if (flag1 != flag2) {
                    // The order here should match what is used in updateDrawState
                    final boolean easy1 = (flag1 & SuggestionSpan.FLAG_EASY_CORRECT) != 0;
                    final boolean easy2 = (flag2 & SuggestionSpan.FLAG_EASY_CORRECT) != 0;
                    final boolean misspelled1 = (flag1 & SuggestionSpan.FLAG_MISSPELLED) != 0;
                    final boolean misspelled2 = (flag2 & SuggestionSpan.FLAG_MISSPELLED) != 0;
                    if (easy1 && !misspelled1) return -1;
                    if (easy2 && !misspelled2) return 1;
                    if (misspelled1) return -1;
                    if (misspelled2) return 1;
                }

                return mSpansLengths.get(span1).intValue() - mSpansLengths.get(span2).intValue();
            }
        }

        /**
         * Returns the suggestion spans that cover the current cursor position. The suggestion
         * spans are sorted according to the length of text that they are attached to.
         */
        private SuggestionSpan[] getSuggestionSpans() {
            int pos = mTextView.getSelectionStart();
            Spannable spannable = (Spannable) mTextView.getText();
            SuggestionSpan[] suggestionSpans = spannable.getSpans(pos, pos, SuggestionSpan.class);

            mSpansLengths.clear();
            for (SuggestionSpan suggestionSpan : suggestionSpans) {
                int start = spannable.getSpanStart(suggestionSpan);
                int end = spannable.getSpanEnd(suggestionSpan);
                mSpansLengths.put(suggestionSpan, Integer.valueOf(end - start));
            }

            // The suggestions are sorted according to their types (easy correction first, then
            // misspelled) and to the length of the text that they cover (shorter first).
            Arrays.sort(suggestionSpans, mSuggestionSpanComparator);
            return suggestionSpans;
        }

        @Override
        public void show() {
            if (!(mTextView.getText() instanceof Editable)) return;

            if (updateSuggestions()) {
                mCursorWasVisibleBeforeSuggestions = mCursorVisible;
                mTextView.setCursorVisible(false);
                mIsShowingUp = true;
                super.show();
            }
        }

        @Override
        protected void measureContent() {
            final DisplayMetrics displayMetrics = mTextView.getResources().getDisplayMetrics();
            final int horizontalMeasure = View.MeasureSpec.makeMeasureSpec(
                    displayMetrics.widthPixels, View.MeasureSpec.AT_MOST);
            final int verticalMeasure = View.MeasureSpec.makeMeasureSpec(
                    displayMetrics.heightPixels, View.MeasureSpec.AT_MOST);

            int width = 0;
            View view = null;
            for (int i = 0; i < mNumberOfSuggestions; i++) {
                view = mSuggestionsAdapter.getView(i, view, mContentView);
                view.getLayoutParams().width = LayoutParams.WRAP_CONTENT;
                view.measure(horizontalMeasure, verticalMeasure);
                width = Math.max(width, view.getMeasuredWidth());
            }

            // Enforce the width based on actual text widths
            mContentView.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    verticalMeasure);

            Drawable popupBackground = mPopupWindow.getBackground();
            if (popupBackground != null) {
                if (mTempRect == null) mTempRect = new Rect();
                popupBackground.getPadding(mTempRect);
                width += mTempRect.left + mTempRect.right;
            }
            mPopupWindow.setWidth(width);
        }

        @Override
        protected int getTextOffset() {
            return mTextView.getSelectionStart();
        }

        @Override
        protected int getVerticalLocalPosition(int line) {
            return mTextView.getLayout().getLineBottom(line);
        }

        @Override
        protected int clipVertically(int positionY) {
            final int height = mContentView.getMeasuredHeight();
            final DisplayMetrics displayMetrics = mTextView.getResources().getDisplayMetrics();
            return Math.min(positionY, displayMetrics.heightPixels - height);
        }

        @Override
        public void hide() {
            super.hide();
        }

        private boolean updateSuggestions() {
            Spannable spannable = (Spannable) mTextView.getText();
            SuggestionSpan[] suggestionSpans = getSuggestionSpans();

            final int nbSpans = suggestionSpans.length;
            // Suggestions are shown after a delay: the underlying spans may have been removed
            if (nbSpans == 0) return false;

            mNumberOfSuggestions = 0;
            int spanUnionStart = mTextView.getText().length();
            int spanUnionEnd = 0;

            SuggestionSpan misspelledSpan = null;
            int underlineColor = 0;

            for (int spanIndex = 0; spanIndex < nbSpans; spanIndex++) {
                SuggestionSpan suggestionSpan = suggestionSpans[spanIndex];
                final int spanStart = spannable.getSpanStart(suggestionSpan);
                final int spanEnd = spannable.getSpanEnd(suggestionSpan);
                spanUnionStart = Math.min(spanStart, spanUnionStart);
                spanUnionEnd = Math.max(spanEnd, spanUnionEnd);

                if ((suggestionSpan.getFlags() & SuggestionSpan.FLAG_MISSPELLED) != 0) {
                    misspelledSpan = suggestionSpan;
                }

                // The first span dictates the background color of the highlighted text
                if (spanIndex == 0) underlineColor = suggestionSpan.getUnderlineColor();

                String[] suggestions = suggestionSpan.getSuggestions();
                int nbSuggestions = suggestions.length;
                for (int suggestionIndex = 0; suggestionIndex < nbSuggestions; suggestionIndex++) {
                    String suggestion = suggestions[suggestionIndex];

                    boolean suggestionIsDuplicate = false;
                    for (int i = 0; i < mNumberOfSuggestions; i++) {
                        if (mSuggestionInfos[i].text.toString().equals(suggestion)) {
                            SuggestionSpan otherSuggestionSpan = mSuggestionInfos[i].suggestionSpan;
                            final int otherSpanStart = spannable.getSpanStart(otherSuggestionSpan);
                            final int otherSpanEnd = spannable.getSpanEnd(otherSuggestionSpan);
                            if (spanStart == otherSpanStart && spanEnd == otherSpanEnd) {
                                suggestionIsDuplicate = true;
                                break;
                            }
                        }
                    }

                    if (!suggestionIsDuplicate) {
                        SuggestionInfo suggestionInfo = mSuggestionInfos[mNumberOfSuggestions];
                        suggestionInfo.suggestionSpan = suggestionSpan;
                        suggestionInfo.suggestionIndex = suggestionIndex;
                        suggestionInfo.text.replace(0, suggestionInfo.text.length(), suggestion);

                        mNumberOfSuggestions++;

                        if (mNumberOfSuggestions == MAX_NUMBER_SUGGESTIONS) {
                            // Also end outer for loop
                            spanIndex = nbSpans;
                            break;
                        }
                    }
                }
            }

            for (int i = 0; i < mNumberOfSuggestions; i++) {
                highlightTextDifferences(mSuggestionInfos[i], spanUnionStart, spanUnionEnd);
            }

            // Add "Add to dictionary" item if there is a span with the misspelled flag
            if (misspelledSpan != null) {
                final int misspelledStart = spannable.getSpanStart(misspelledSpan);
                final int misspelledEnd = spannable.getSpanEnd(misspelledSpan);
                if (misspelledStart >= 0 && misspelledEnd > misspelledStart) {
                    SuggestionInfo suggestionInfo = mSuggestionInfos[mNumberOfSuggestions];
                    suggestionInfo.suggestionSpan = misspelledSpan;
                    suggestionInfo.suggestionIndex = ADD_TO_DICTIONARY;
                    suggestionInfo.text.replace(0, suggestionInfo.text.length(), mTextView.
                            getContext().getString(com.android.internal.R.string.addToDictionary));
                    suggestionInfo.text.setSpan(suggestionInfo.highlightSpan, 0, 0,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

                    mNumberOfSuggestions++;
                }
            }

            // Delete item
            SuggestionInfo suggestionInfo = mSuggestionInfos[mNumberOfSuggestions];
            suggestionInfo.suggestionSpan = null;
            suggestionInfo.suggestionIndex = DELETE_TEXT;
            suggestionInfo.text.replace(0, suggestionInfo.text.length(),
                    mTextView.getContext().getString(com.android.internal.R.string.deleteText));
            suggestionInfo.text.setSpan(suggestionInfo.highlightSpan, 0, 0,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            mNumberOfSuggestions++;

            if (mSuggestionRangeSpan == null) mSuggestionRangeSpan = new SuggestionRangeSpan();
            if (underlineColor == 0) {
                // Fallback on the default highlight color when the first span does not provide one
                mSuggestionRangeSpan.setBackgroundColor(mTextView.mHighlightColor);
            } else {
                final float BACKGROUND_TRANSPARENCY = 0.4f;
                final int newAlpha = (int) (Color.alpha(underlineColor) * BACKGROUND_TRANSPARENCY);
                mSuggestionRangeSpan.setBackgroundColor(
                        (underlineColor & 0x00FFFFFF) + (newAlpha << 24));
            }
            spannable.setSpan(mSuggestionRangeSpan, spanUnionStart, spanUnionEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            mSuggestionsAdapter.notifyDataSetChanged();
            return true;
        }

        private void highlightTextDifferences(SuggestionInfo suggestionInfo, int unionStart,
                int unionEnd) {
            final Spannable text = (Spannable) mTextView.getText();
            final int spanStart = text.getSpanStart(suggestionInfo.suggestionSpan);
            final int spanEnd = text.getSpanEnd(suggestionInfo.suggestionSpan);

            // Adjust the start/end of the suggestion span
            suggestionInfo.suggestionStart = spanStart - unionStart;
            suggestionInfo.suggestionEnd = suggestionInfo.suggestionStart
                    + suggestionInfo.text.length();

            suggestionInfo.text.setSpan(suggestionInfo.highlightSpan, 0,
                    suggestionInfo.text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            // Add the text before and after the span.
            final String textAsString = text.toString();
            suggestionInfo.text.insert(0, textAsString.substring(unionStart, spanStart));
            suggestionInfo.text.append(textAsString.substring(spanEnd, unionEnd));
        }

        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            Editable editable = (Editable) mTextView.getText();
            SuggestionInfo suggestionInfo = mSuggestionInfos[position];

            if (suggestionInfo.suggestionIndex == DELETE_TEXT) {
                final int spanUnionStart = editable.getSpanStart(mSuggestionRangeSpan);
                int spanUnionEnd = editable.getSpanEnd(mSuggestionRangeSpan);
                if (spanUnionStart >= 0 && spanUnionEnd > spanUnionStart) {
                    // Do not leave two adjacent spaces after deletion, or one at beginning of text
                    if (spanUnionEnd < editable.length() &&
                            Character.isSpaceChar(editable.charAt(spanUnionEnd)) &&
                            (spanUnionStart == 0 ||
                            Character.isSpaceChar(editable.charAt(spanUnionStart - 1)))) {
                        spanUnionEnd = spanUnionEnd + 1;
                    }
                    mTextView.deleteText_internal(spanUnionStart, spanUnionEnd);
                }
                hide();
                return;
            }

            final int spanStart = editable.getSpanStart(suggestionInfo.suggestionSpan);
            final int spanEnd = editable.getSpanEnd(suggestionInfo.suggestionSpan);
            if (spanStart < 0 || spanEnd <= spanStart) {
                // Span has been removed
                hide();
                return;
            }

            final String originalText = editable.toString().substring(spanStart, spanEnd);

            if (suggestionInfo.suggestionIndex == ADD_TO_DICTIONARY) {
                Intent intent = new Intent(Settings.ACTION_USER_DICTIONARY_INSERT);
                intent.putExtra("word", originalText);
                intent.putExtra("locale", mTextView.getTextServicesLocale().toString());
                // Put a listener to replace the original text with a word which the user
                // modified in a user dictionary dialog.
                intent.setFlags(intent.getFlags() | Intent.FLAG_ACTIVITY_NEW_TASK);
                mTextView.getContext().startActivity(intent);
                // There is no way to know if the word was indeed added. Re-check.
                // TODO The ExtractEditText should remove the span in the original text instead
                editable.removeSpan(suggestionInfo.suggestionSpan);
                Selection.setSelection(editable, spanEnd);
                updateSpellCheckSpans(spanStart, spanEnd, false);
            } else {
                // SuggestionSpans are removed by replace: save them before
                SuggestionSpan[] suggestionSpans = editable.getSpans(spanStart, spanEnd,
                        SuggestionSpan.class);
                final int length = suggestionSpans.length;
                int[] suggestionSpansStarts = new int[length];
                int[] suggestionSpansEnds = new int[length];
                int[] suggestionSpansFlags = new int[length];
                for (int i = 0; i < length; i++) {
                    final SuggestionSpan suggestionSpan = suggestionSpans[i];
                    suggestionSpansStarts[i] = editable.getSpanStart(suggestionSpan);
                    suggestionSpansEnds[i] = editable.getSpanEnd(suggestionSpan);
                    suggestionSpansFlags[i] = editable.getSpanFlags(suggestionSpan);

                    // Remove potential misspelled flags
                    int suggestionSpanFlags = suggestionSpan.getFlags();
                    if ((suggestionSpanFlags & SuggestionSpan.FLAG_MISSPELLED) > 0) {
                        suggestionSpanFlags &= ~SuggestionSpan.FLAG_MISSPELLED;
                        suggestionSpanFlags &= ~SuggestionSpan.FLAG_EASY_CORRECT;
                        suggestionSpan.setFlags(suggestionSpanFlags);
                    }
                }

                final int suggestionStart = suggestionInfo.suggestionStart;
                final int suggestionEnd = suggestionInfo.suggestionEnd;
                final String suggestion = suggestionInfo.text.subSequence(
                        suggestionStart, suggestionEnd).toString();
                mTextView.replaceText_internal(spanStart, spanEnd, suggestion);

                // Notify source IME of the suggestion pick. Do this before
                // swaping texts.
                suggestionInfo.suggestionSpan.notifySelection(
                        mTextView.getContext(), originalText, suggestionInfo.suggestionIndex);

                // Swap text content between actual text and Suggestion span
                String[] suggestions = suggestionInfo.suggestionSpan.getSuggestions();
                suggestions[suggestionInfo.suggestionIndex] = originalText;

                // Restore previous SuggestionSpans
                final int lengthDifference = suggestion.length() - (spanEnd - spanStart);
                for (int i = 0; i < length; i++) {
                    // Only spans that include the modified region make sense after replacement
                    // Spans partially included in the replaced region are removed, there is no
                    // way to assign them a valid range after replacement
                    if (suggestionSpansStarts[i] <= spanStart &&
                            suggestionSpansEnds[i] >= spanEnd) {
                        mTextView.setSpan_internal(suggestionSpans[i], suggestionSpansStarts[i],
                                suggestionSpansEnds[i] + lengthDifference, suggestionSpansFlags[i]);
                    }
                }

                // Move cursor at the end of the replaced word
                final int newCursorPosition = spanEnd + lengthDifference;
                mTextView.setCursorPosition_internal(newCursorPosition, newCursorPosition);
            }

            hide();
        }
    }

    /**
     * An ActionMode Callback class that is used to provide actions while in text insertion or
     * selection mode.
     *
     * The default callback provides a subset of Select All, Cut, Copy, Paste, Share and Replace
     * actions, depending on which of these this TextView supports and the current selection.
     */
    private class TextActionModeCallback extends ActionMode.Callback2 {
        private final Path mSelectionPath = new Path();
        private final RectF mSelectionBounds = new RectF();
        private final boolean mHasSelection;

        private int mHandleHeight;

        public TextActionModeCallback(boolean hasSelection) {
            mHasSelection = hasSelection;
            if (mHasSelection) {
                SelectionModifierCursorController selectionController = getSelectionController();
                if (selectionController.mStartHandle == null) {
                    // As these are for initializing selectionController, hide() must be called.
                    selectionController.initDrawables();
                    selectionController.initHandles();
                    selectionController.hide();
                }
                mHandleHeight = Math.max(
                        mSelectHandleLeft.getMinimumHeight(),
                        mSelectHandleRight.getMinimumHeight());
            } else {
                InsertionPointCursorController insertionController = getInsertionController();
                if (insertionController != null) {
                    insertionController.getHandle();
                    mHandleHeight = mSelectHandleCenter.getMinimumHeight();
                }
            }
        }

        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            mode.setTitle(null);
            mode.setSubtitle(null);
            mode.setTitleOptionalHint(true);
            populateMenuWithItems(menu);

            Callback customCallback = getCustomCallback();
            if (customCallback != null) {
                if (!customCallback.onCreateActionMode(mode, menu)) {
                    // The custom mode can choose to cancel the action mode, dismiss selection.
                    Selection.setSelection((Spannable) mTextView.getText(),
                            mTextView.getSelectionEnd());
                    return false;
                }
            }

            if (mTextView.canProcessText()) {
                mProcessTextIntentActionsHandler.onInitializeMenu(menu);
            }

            if (menu.hasVisibleItems() || mode.getCustomView() != null) {
                mTextView.setHasTransientState(true);
                return true;
            } else {
                return false;
            }
        }

        private Callback getCustomCallback() {
            return mHasSelection
                    ? mCustomSelectionActionModeCallback
                    : mCustomInsertionActionModeCallback;
        }

        private void populateMenuWithItems(Menu menu) {
            if (mTextView.canCut()) {
                menu.add(Menu.NONE, TextView.ID_CUT, MENU_ITEM_ORDER_CUT,
                        com.android.internal.R.string.cut).
                    setAlphabeticShortcut('x').
                    setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
            }

            if (mTextView.canCopy()) {
                menu.add(Menu.NONE, TextView.ID_COPY, MENU_ITEM_ORDER_COPY,
                        com.android.internal.R.string.copy).
                    setAlphabeticShortcut('c').
                    setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
            }

            if (mTextView.canPaste()) {
                menu.add(Menu.NONE, TextView.ID_PASTE, MENU_ITEM_ORDER_PASTE,
                        com.android.internal.R.string.paste).
                    setAlphabeticShortcut('v').
                    setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
            }

            if (mTextView.canShare()) {
                menu.add(Menu.NONE, TextView.ID_SHARE, MENU_ITEM_ORDER_SHARE,
                        com.android.internal.R.string.share).
                    setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
            }

            updateSelectAllItem(menu);
            updateReplaceItem(menu);
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            updateSelectAllItem(menu);
            updateReplaceItem(menu);

            Callback customCallback = getCustomCallback();
            if (customCallback != null) {
                return customCallback.onPrepareActionMode(mode, menu);
            }
            return true;
        }

        private void updateSelectAllItem(Menu menu) {
            boolean canSelectAll = mTextView.canSelectAllText();
            boolean selectAllItemExists = menu.findItem(TextView.ID_SELECT_ALL) != null;
            if (canSelectAll && !selectAllItemExists) {
                menu.add(Menu.NONE, TextView.ID_SELECT_ALL, MENU_ITEM_ORDER_SELECT_ALL,
                        com.android.internal.R.string.selectAll)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
            } else if (!canSelectAll && selectAllItemExists) {
                menu.removeItem(TextView.ID_SELECT_ALL);
            }
        }

        private void updateReplaceItem(Menu menu) {
            boolean canReplace = mTextView.isSuggestionsEnabled() && shouldOfferToShowSuggestions()
                    && !(mTextView.isInExtractedMode() && mTextView.hasSelection());
            boolean replaceItemExists = menu.findItem(TextView.ID_REPLACE) != null;
            if (canReplace && !replaceItemExists) {
                menu.add(Menu.NONE, TextView.ID_REPLACE, MENU_ITEM_ORDER_REPLACE,
                        com.android.internal.R.string.replace)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
            } else if (!canReplace && replaceItemExists) {
                menu.removeItem(TextView.ID_REPLACE);
            }
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            if (mProcessTextIntentActionsHandler.performMenuItemAction(item)) {
                return true;
            }
            Callback customCallback = getCustomCallback();
            if (customCallback != null && customCallback.onActionItemClicked(mode, item)) {
                return true;
            }
            return mTextView.onTextContextMenuItem(item.getItemId());
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            Callback customCallback = getCustomCallback();
            if (customCallback != null) {
                customCallback.onDestroyActionMode(mode);
            }

            /*
             * If we're ending this mode because we're detaching from a window,
             * we still have selection state to preserve. Don't clear it, we'll
             * bring back the selection mode when (if) we get reattached.
             */
            if (!mPreserveDetachedSelection) {
                Selection.setSelection((Spannable) mTextView.getText(),
                        mTextView.getSelectionEnd());
                mTextView.setHasTransientState(false);
            }

            if (mSelectionModifierCursorController != null) {
                mSelectionModifierCursorController.hide();
            }

            mTextActionMode = null;
        }

        @Override
        public void onGetContentRect(ActionMode mode, View view, Rect outRect) {
            if (!view.equals(mTextView) || mTextView.getLayout() == null) {
                super.onGetContentRect(mode, view, outRect);
                return;
            }
            if (mTextView.getSelectionStart() != mTextView.getSelectionEnd()) {
                // We have a selection.
                mSelectionPath.reset();
                mTextView.getLayout().getSelectionPath(
                        mTextView.getSelectionStart(), mTextView.getSelectionEnd(), mSelectionPath);
                mSelectionPath.computeBounds(mSelectionBounds, true);
                mSelectionBounds.bottom += mHandleHeight;
            } else if (mCursorCount == 2) {
                // We have a split cursor. In this case, we take the rectangle that includes both
                // parts of the cursor to ensure we don't obscure either of them.
                Rect firstCursorBounds = mCursorDrawable[0].getBounds();
                Rect secondCursorBounds = mCursorDrawable[1].getBounds();
                mSelectionBounds.set(
                        Math.min(firstCursorBounds.left, secondCursorBounds.left),
                        Math.min(firstCursorBounds.top, secondCursorBounds.top),
                        Math.max(firstCursorBounds.right, secondCursorBounds.right),
                        Math.max(firstCursorBounds.bottom, secondCursorBounds.bottom)
                                + mHandleHeight);
            } else {
                // We have a single cursor.
                Layout layout = getActiveLayout();
                int line = layout.getLineForOffset(mTextView.getSelectionStart());
                float primaryHorizontal =
                        layout.getPrimaryHorizontal(mTextView.getSelectionStart());
                mSelectionBounds.set(
                        primaryHorizontal,
                        layout.getLineTop(line),
                        primaryHorizontal,
                        layout.getLineTop(line + 1) + mHandleHeight);
            }
            // Take TextView's padding and scroll into account.
            int textHorizontalOffset = mTextView.viewportToContentHorizontalOffset();
            int textVerticalOffset = mTextView.viewportToContentVerticalOffset();
            outRect.set(
                    (int) Math.floor(mSelectionBounds.left + textHorizontalOffset),
                    (int) Math.floor(mSelectionBounds.top + textVerticalOffset),
                    (int) Math.ceil(mSelectionBounds.right + textHorizontalOffset),
                    (int) Math.ceil(mSelectionBounds.bottom + textVerticalOffset));
        }
    }

    /**
     * A listener to call {@link InputMethodManager#updateCursorAnchorInfo(View, CursorAnchorInfo)}
     * while the input method is requesting the cursor/anchor position. Does nothing as long as
     * {@link InputMethodManager#isWatchingCursor(View)} returns false.
     */
    private final class CursorAnchorInfoNotifier implements TextViewPositionListener {
        final CursorAnchorInfo.Builder mSelectionInfoBuilder = new CursorAnchorInfo.Builder();
        final int[] mTmpIntOffset = new int[2];
        final Matrix mViewToScreenMatrix = new Matrix();

        @Override
        public void updatePosition(int parentPositionX, int parentPositionY,
                boolean parentPositionChanged, boolean parentScrolled) {
            final InputMethodState ims = mInputMethodState;
            if (ims == null || ims.mBatchEditNesting > 0) {
                return;
            }
            final InputMethodManager imm = InputMethodManager.peekInstance();
            if (null == imm) {
                return;
            }
            if (!imm.isActive(mTextView)) {
                return;
            }
            // Skip if the IME has not requested the cursor/anchor position.
            if (!imm.isCursorAnchorInfoEnabled()) {
                return;
            }
            Layout layout = mTextView.getLayout();
            if (layout == null) {
                return;
            }

            final CursorAnchorInfo.Builder builder = mSelectionInfoBuilder;
            builder.reset();

            final int selectionStart = mTextView.getSelectionStart();
            builder.setSelectionRange(selectionStart, mTextView.getSelectionEnd());

            // Construct transformation matrix from view local coordinates to screen coordinates.
            mViewToScreenMatrix.set(mTextView.getMatrix());
            mTextView.getLocationOnScreen(mTmpIntOffset);
            mViewToScreenMatrix.postTranslate(mTmpIntOffset[0], mTmpIntOffset[1]);
            builder.setMatrix(mViewToScreenMatrix);

            final float viewportToContentHorizontalOffset =
                    mTextView.viewportToContentHorizontalOffset();
            final float viewportToContentVerticalOffset =
                    mTextView.viewportToContentVerticalOffset();

            final CharSequence text = mTextView.getText();
            if (text instanceof Spannable) {
                final Spannable sp = (Spannable) text;
                int composingTextStart = EditableInputConnection.getComposingSpanStart(sp);
                int composingTextEnd = EditableInputConnection.getComposingSpanEnd(sp);
                if (composingTextEnd < composingTextStart) {
                    final int temp = composingTextEnd;
                    composingTextEnd = composingTextStart;
                    composingTextStart = temp;
                }
                final boolean hasComposingText =
                        (0 <= composingTextStart) && (composingTextStart < composingTextEnd);
                if (hasComposingText) {
                    final CharSequence composingText = text.subSequence(composingTextStart,
                            composingTextEnd);
                    builder.setComposingText(composingTextStart, composingText);

                    final int minLine = layout.getLineForOffset(composingTextStart);
                    final int maxLine = layout.getLineForOffset(composingTextEnd - 1);
                    for (int line = minLine; line <= maxLine; ++line) {
                        final int lineStart = layout.getLineStart(line);
                        final int lineEnd = layout.getLineEnd(line);
                        final int offsetStart = Math.max(lineStart, composingTextStart);
                        final int offsetEnd = Math.min(lineEnd, composingTextEnd);
                        final boolean ltrLine =
                                layout.getParagraphDirection(line) == Layout.DIR_LEFT_TO_RIGHT;
                        final float[] widths = new float[offsetEnd - offsetStart];
                        layout.getPaint().getTextWidths(text, offsetStart, offsetEnd, widths);
                        final float top = layout.getLineTop(line);
                        final float bottom = layout.getLineBottom(line);
                        for (int offset = offsetStart; offset < offsetEnd; ++offset) {
                            final float charWidth = widths[offset - offsetStart];
                            final boolean isRtl = layout.isRtlCharAt(offset);
                            final float primary = layout.getPrimaryHorizontal(offset);
                            final float secondary = layout.getSecondaryHorizontal(offset);
                            // TODO: This doesn't work perfectly for text with custom styles and
                            // TAB chars.
                            final float left;
                            final float right;
                            if (ltrLine) {
                                if (isRtl) {
                                    left = secondary - charWidth;
                                    right = secondary;
                                } else {
                                    left = primary;
                                    right = primary + charWidth;
                                }
                            } else {
                                if (!isRtl) {
                                    left = secondary;
                                    right = secondary + charWidth;
                                } else {
                                    left = primary - charWidth;
                                    right = primary;
                                }
                            }
                            // TODO: Check top-right and bottom-left as well.
                            final float localLeft = left + viewportToContentHorizontalOffset;
                            final float localRight = right + viewportToContentHorizontalOffset;
                            final float localTop = top + viewportToContentVerticalOffset;
                            final float localBottom = bottom + viewportToContentVerticalOffset;
                            final boolean isTopLeftVisible = isPositionVisible(localLeft, localTop);
                            final boolean isBottomRightVisible =
                                    isPositionVisible(localRight, localBottom);
                            int characterBoundsFlags = 0;
                            if (isTopLeftVisible || isBottomRightVisible) {
                                characterBoundsFlags |= CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION;
                            }
                            if (!isTopLeftVisible || !isBottomRightVisible) {
                                characterBoundsFlags |= CursorAnchorInfo.FLAG_HAS_INVISIBLE_REGION;
                            }
                            if (isRtl) {
                                characterBoundsFlags |= CursorAnchorInfo.FLAG_IS_RTL;
                            }
                            // Here offset is the index in Java chars.
                            builder.addCharacterBounds(offset, localLeft, localTop, localRight,
                                    localBottom, characterBoundsFlags);
                        }
                    }
                }
            }

            // Treat selectionStart as the insertion point.
            if (0 <= selectionStart) {
                final int offset = selectionStart;
                final int line = layout.getLineForOffset(offset);
                final float insertionMarkerX = layout.getPrimaryHorizontal(offset)
                        + viewportToContentHorizontalOffset;
                final float insertionMarkerTop = layout.getLineTop(line)
                        + viewportToContentVerticalOffset;
                final float insertionMarkerBaseline = layout.getLineBaseline(line)
                        + viewportToContentVerticalOffset;
                final float insertionMarkerBottom = layout.getLineBottom(line)
                        + viewportToContentVerticalOffset;
                final boolean isTopVisible =
                        isPositionVisible(insertionMarkerX, insertionMarkerTop);
                final boolean isBottomVisible =
                        isPositionVisible(insertionMarkerX, insertionMarkerBottom);
                int insertionMarkerFlags = 0;
                if (isTopVisible || isBottomVisible) {
                    insertionMarkerFlags |= CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION;
                }
                if (!isTopVisible || !isBottomVisible) {
                    insertionMarkerFlags |= CursorAnchorInfo.FLAG_HAS_INVISIBLE_REGION;
                }
                if (layout.isRtlCharAt(offset)) {
                    insertionMarkerFlags |= CursorAnchorInfo.FLAG_IS_RTL;
                }
                builder.setInsertionMarkerLocation(insertionMarkerX, insertionMarkerTop,
                        insertionMarkerBaseline, insertionMarkerBottom, insertionMarkerFlags);
            }

            imm.updateCursorAnchorInfo(mTextView, builder.build());
        }
    }

    private abstract class HandleView extends View implements TextViewPositionListener {
        protected Drawable mDrawable;
        protected Drawable mDrawableLtr;
        protected Drawable mDrawableRtl;
        private final PopupWindow mContainer;
        // Position with respect to the parent TextView
        private int mPositionX, mPositionY;
        private boolean mIsDragging;
        // Offset from touch position to mPosition
        private float mTouchToWindowOffsetX, mTouchToWindowOffsetY;
        protected int mHotspotX;
        protected int mHorizontalGravity;
        // Offsets the hotspot point up, so that cursor is not hidden by the finger when moving up
        private float mTouchOffsetY;
        // Where the touch position should be on the handle to ensure a maximum cursor visibility
        private float mIdealVerticalOffset;
        // Parent's (TextView) previous position in window
        private int mLastParentX, mLastParentY;
        // Previous text character offset
        protected int mPreviousOffset = -1;
        // Previous text character offset
        private boolean mPositionHasChanged = true;
        // Minimum touch target size for handles
        private int mMinSize;
        // Indicates the line of text that the handle is on.
        protected int mPrevLine = UNSET_LINE;
        // Indicates the line of text that the user was touching. This can differ from mPrevLine
        // when selecting text when the handles jump to the end / start of words which may be on
        // a different line.
        protected int mPreviousLineTouched = UNSET_LINE;

        public HandleView(Drawable drawableLtr, Drawable drawableRtl) {
            super(mTextView.getContext());
            mContainer = new PopupWindow(mTextView.getContext(), null,
                    com.android.internal.R.attr.textSelectHandleWindowStyle);
            mContainer.setSplitTouchEnabled(true);
            mContainer.setClippingEnabled(false);
            mContainer.setWindowLayoutType(WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL);
            mContainer.setWidth(ViewGroup.LayoutParams.WRAP_CONTENT);
            mContainer.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
            mContainer.setContentView(this);

            mDrawableLtr = drawableLtr;
            mDrawableRtl = drawableRtl;
            mMinSize = mTextView.getContext().getResources().getDimensionPixelSize(
                    com.android.internal.R.dimen.text_handle_min_size);

            updateDrawable();

            final int handleHeight = getPreferredHeight();
            mTouchOffsetY = -0.3f * handleHeight;
            mIdealVerticalOffset = 0.7f * handleHeight;
        }

        public float getIdealVerticalOffset() {
            return mIdealVerticalOffset;
        }

        protected void updateDrawable() {
            if (mIsDragging) {
                // Don't update drawable during dragging.
                return;
            }
            final int offset = getCurrentCursorOffset();
            final boolean isRtlCharAtOffset = mTextView.getLayout().isRtlCharAt(offset);
            final Drawable oldDrawable = mDrawable;
            mDrawable = isRtlCharAtOffset ? mDrawableRtl : mDrawableLtr;
            mHotspotX = getHotspotX(mDrawable, isRtlCharAtOffset);
            mHorizontalGravity = getHorizontalGravity(isRtlCharAtOffset);
            final Layout layout = mTextView.getLayout();
            if (layout != null && oldDrawable != mDrawable && isShowing()) {
                // Update popup window position.
                mPositionX = (int) (layout.getPrimaryHorizontal(offset) - 0.5f - mHotspotX -
                        getHorizontalOffset() + getCursorOffset());
                mPositionX += mTextView.viewportToContentHorizontalOffset();
                mPositionHasChanged = true;
                updatePosition(mLastParentX, mLastParentY, false, false);
                postInvalidate();
            }
        }

        protected abstract int getHotspotX(Drawable drawable, boolean isRtlRun);
        protected abstract int getHorizontalGravity(boolean isRtlRun);

        // Touch-up filter: number of previous positions remembered
        private static final int HISTORY_SIZE = 5;
        private static final int TOUCH_UP_FILTER_DELAY_AFTER = 150;
        private static final int TOUCH_UP_FILTER_DELAY_BEFORE = 350;
        private final long[] mPreviousOffsetsTimes = new long[HISTORY_SIZE];
        private final int[] mPreviousOffsets = new int[HISTORY_SIZE];
        private int mPreviousOffsetIndex = 0;
        private int mNumberPreviousOffsets = 0;

        private void startTouchUpFilter(int offset) {
            mNumberPreviousOffsets = 0;
            addPositionToTouchUpFilter(offset);
        }

        private void addPositionToTouchUpFilter(int offset) {
            mPreviousOffsetIndex = (mPreviousOffsetIndex + 1) % HISTORY_SIZE;
            mPreviousOffsets[mPreviousOffsetIndex] = offset;
            mPreviousOffsetsTimes[mPreviousOffsetIndex] = SystemClock.uptimeMillis();
            mNumberPreviousOffsets++;
        }

        private void filterOnTouchUp() {
            final long now = SystemClock.uptimeMillis();
            int i = 0;
            int index = mPreviousOffsetIndex;
            final int iMax = Math.min(mNumberPreviousOffsets, HISTORY_SIZE);
            while (i < iMax && (now - mPreviousOffsetsTimes[index]) < TOUCH_UP_FILTER_DELAY_AFTER) {
                i++;
                index = (mPreviousOffsetIndex - i + HISTORY_SIZE) % HISTORY_SIZE;
            }

            if (i > 0 && i < iMax &&
                    (now - mPreviousOffsetsTimes[index]) > TOUCH_UP_FILTER_DELAY_BEFORE) {
                positionAtCursorOffset(mPreviousOffsets[index], false);
            }
        }

        public boolean offsetHasBeenChanged() {
            return mNumberPreviousOffsets > 1;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(getPreferredWidth(), getPreferredHeight());
        }

        private int getPreferredWidth() {
            return Math.max(mDrawable.getIntrinsicWidth(), mMinSize);
        }

        private int getPreferredHeight() {
            return Math.max(mDrawable.getIntrinsicHeight(), mMinSize);
        }

        public void show() {
            if (isShowing()) return;

            getPositionListener().addSubscriber(this, true /* local position may change */);

            // Make sure the offset is always considered new, even when focusing at same position
            mPreviousOffset = -1;
            positionAtCursorOffset(getCurrentCursorOffset(), false);
        }

        protected void dismiss() {
            mIsDragging = false;
            mContainer.dismiss();
            onDetached();
        }

        public void hide() {
            dismiss();

            getPositionListener().removeSubscriber(this);
        }

        public boolean isShowing() {
            return mContainer.isShowing();
        }

        private boolean isVisible() {
            // Always show a dragging handle.
            if (mIsDragging) {
                return true;
            }

            if (mTextView.isInBatchEditMode()) {
                return false;
            }

            return isPositionVisible(mPositionX + mHotspotX + getHorizontalOffset(), mPositionY);
        }

        public abstract int getCurrentCursorOffset();

        protected abstract void updateSelection(int offset);

        public abstract void updatePosition(float x, float y);

        protected void positionAtCursorOffset(int offset, boolean parentScrolled) {
            // A HandleView relies on the layout, which may be nulled by external methods
            Layout layout = mTextView.getLayout();
            if (layout == null) {
                // Will update controllers' state, hiding them and stopping selection mode if needed
                prepareCursorControllers();
                return;
            }
            layout = getActiveLayout();

            boolean offsetChanged = offset != mPreviousOffset;
            if (offsetChanged || parentScrolled) {
                if (offsetChanged) {
                    updateSelection(offset);
                    addPositionToTouchUpFilter(offset);
                }
                final int line = layout.getLineForOffset(offset);
                mPrevLine = line;

                mPositionX = (int) (layout.getPrimaryHorizontal(offset) - 0.5f - mHotspotX -
                        getHorizontalOffset() + getCursorOffset());
                mPositionY = layout.getLineBottom(line);

                // Take TextView's padding and scroll into account.
                mPositionX += mTextView.viewportToContentHorizontalOffset();
                mPositionY += mTextView.viewportToContentVerticalOffset();

                mPreviousOffset = offset;
                mPositionHasChanged = true;
            }
        }

        public void updatePosition(int parentPositionX, int parentPositionY,
                boolean parentPositionChanged, boolean parentScrolled) {
            positionAtCursorOffset(getCurrentCursorOffset(), parentScrolled);
            if (parentPositionChanged || mPositionHasChanged) {
                if (mIsDragging) {
                    // Update touchToWindow offset in case of parent scrolling while dragging
                    if (parentPositionX != mLastParentX || parentPositionY != mLastParentY) {
                        mTouchToWindowOffsetX += parentPositionX - mLastParentX;
                        mTouchToWindowOffsetY += parentPositionY - mLastParentY;
                        mLastParentX = parentPositionX;
                        mLastParentY = parentPositionY;
                    }

                    onHandleMoved();
                }

                if (isVisible()) {
                    final int positionX = parentPositionX + mPositionX;
                    final int positionY = parentPositionY + mPositionY;
                    if (isShowing()) {
                        mContainer.update(positionX, positionY, -1, -1);
                    } else {
                        mContainer.showAtLocation(mTextView, Gravity.NO_GRAVITY,
                                positionX, positionY);
                    }
                } else {
                    if (isShowing()) {
                        dismiss();
                    }
                }

                mPositionHasChanged = false;
            }
        }

        public void showAtLocation(int offset) {
            // TODO - investigate if there's a better way to show the handles
            // after the drag accelerator has occured.
            int[] tmpCords = new int[2];
            mTextView.getLocationInWindow(tmpCords);

            Layout layout = mTextView.getLayout();
            int posX = tmpCords[0];
            int posY = tmpCords[1];

            final int line = layout.getLineForOffset(offset);

            int startX = (int) (layout.getPrimaryHorizontal(offset) - 0.5f
                    - mHotspotX - getHorizontalOffset() + getCursorOffset());
            int startY = layout.getLineBottom(line);

            // Take TextView's padding and scroll into account.
            startX += mTextView.viewportToContentHorizontalOffset();
            startY += mTextView.viewportToContentVerticalOffset();

            mContainer.showAtLocation(mTextView, Gravity.NO_GRAVITY,
                    startX + posX, startY + posY);
        }

        @Override
        protected void onDraw(Canvas c) {
            final int drawWidth = mDrawable.getIntrinsicWidth();
            final int left = getHorizontalOffset();

            mDrawable.setBounds(left, 0, left + drawWidth, mDrawable.getIntrinsicHeight());
            mDrawable.draw(c);
        }

        private int getHorizontalOffset() {
            final int width = getPreferredWidth();
            final int drawWidth = mDrawable.getIntrinsicWidth();
            final int left;
            switch (mHorizontalGravity) {
                case Gravity.LEFT:
                    left = 0;
                    break;
                default:
                case Gravity.CENTER:
                    left = (width - drawWidth) / 2;
                    break;
                case Gravity.RIGHT:
                    left = width - drawWidth;
                    break;
            }
            return left;
        }

        protected int getCursorOffset() {
            return 0;
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            updateFloatingToolbarVisibility(ev);

            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    startTouchUpFilter(getCurrentCursorOffset());
                    mTouchToWindowOffsetX = ev.getRawX() - mPositionX;
                    mTouchToWindowOffsetY = ev.getRawY() - mPositionY;

                    final PositionListener positionListener = getPositionListener();
                    mLastParentX = positionListener.getPositionX();
                    mLastParentY = positionListener.getPositionY();
                    mIsDragging = true;
                    mPreviousLineTouched = UNSET_LINE;
                    break;
                }

                case MotionEvent.ACTION_MOVE: {
                    final float rawX = ev.getRawX();
                    final float rawY = ev.getRawY();

                    // Vertical hysteresis: vertical down movement tends to snap to ideal offset
                    final float previousVerticalOffset = mTouchToWindowOffsetY - mLastParentY;
                    final float currentVerticalOffset = rawY - mPositionY - mLastParentY;
                    float newVerticalOffset;
                    if (previousVerticalOffset < mIdealVerticalOffset) {
                        newVerticalOffset = Math.min(currentVerticalOffset, mIdealVerticalOffset);
                        newVerticalOffset = Math.max(newVerticalOffset, previousVerticalOffset);
                    } else {
                        newVerticalOffset = Math.max(currentVerticalOffset, mIdealVerticalOffset);
                        newVerticalOffset = Math.min(newVerticalOffset, previousVerticalOffset);
                    }
                    mTouchToWindowOffsetY = newVerticalOffset + mLastParentY;

                    final float newPosX =
                            rawX - mTouchToWindowOffsetX + mHotspotX + getHorizontalOffset();
                    final float newPosY = rawY - mTouchToWindowOffsetY + mTouchOffsetY;

                    updatePosition(newPosX, newPosY);
                    break;
                }

                case MotionEvent.ACTION_UP:
                    filterOnTouchUp();
                    mIsDragging = false;
                    updateDrawable();
                    break;

                case MotionEvent.ACTION_CANCEL:
                    mIsDragging = false;
                    updateDrawable();
                    break;
            }
            return true;
        }

        public boolean isDragging() {
            return mIsDragging;
        }

        void onHandleMoved() {}

        public void onDetached() {}
    }

    /**
     * Returns the active layout (hint or text layout). Note that the text layout can be null.
     */
    private Layout getActiveLayout() {
        Layout layout = mTextView.getLayout();
        Layout hintLayout = mTextView.getHintLayout();
        if (TextUtils.isEmpty(layout.getText()) && hintLayout != null &&
                !TextUtils.isEmpty(hintLayout.getText())) {
            layout = hintLayout;
        }
        return layout;
    }

    private class InsertionHandleView extends HandleView {
        private static final int DELAY_BEFORE_HANDLE_FADES_OUT = 4000;
        private static final int RECENT_CUT_COPY_DURATION = 15 * 1000; // seconds

        // Used to detect taps on the insertion handle, which will affect the insertion action mode
        private float mDownPositionX, mDownPositionY;
        private Runnable mHider;

        public InsertionHandleView(Drawable drawable) {
            super(drawable, drawable);
        }

        @Override
        public void show() {
            super.show();

            final long durationSinceCutOrCopy =
                    SystemClock.uptimeMillis() - TextView.sLastCutCopyOrTextChangedTime;

            // Cancel the single tap delayed runnable.
            if (mInsertionActionModeRunnable != null
                    && (mDoubleTap || isCursorInsideEasyCorrectionSpan())) {
                mTextView.removeCallbacks(mInsertionActionModeRunnable);
            }

            // Prepare and schedule the single tap runnable to run exactly after the double tap
            // timeout has passed.
            if (!mDoubleTap && !isCursorInsideEasyCorrectionSpan()
                    && (durationSinceCutOrCopy < RECENT_CUT_COPY_DURATION)) {
                if (mTextActionMode == null) {
                    if (mInsertionActionModeRunnable == null) {
                        mInsertionActionModeRunnable = new Runnable() {
                            @Override
                            public void run() {
                                startInsertionActionMode();
                            }
                        };
                    }
                    mTextView.postDelayed(
                            mInsertionActionModeRunnable,
                            ViewConfiguration.getDoubleTapTimeout() + 1);
                }

            }

            hideAfterDelay();
        }

        private void hideAfterDelay() {
            if (mHider == null) {
                mHider = new Runnable() {
                    public void run() {
                        hide();
                    }
                };
            } else {
                removeHiderCallback();
            }
            mTextView.postDelayed(mHider, DELAY_BEFORE_HANDLE_FADES_OUT);
        }

        private void removeHiderCallback() {
            if (mHider != null) {
                mTextView.removeCallbacks(mHider);
            }
        }

        @Override
        protected int getHotspotX(Drawable drawable, boolean isRtlRun) {
            return drawable.getIntrinsicWidth() / 2;
        }

        @Override
        protected int getHorizontalGravity(boolean isRtlRun) {
            return Gravity.CENTER_HORIZONTAL;
        }

        @Override
        protected int getCursorOffset() {
            int offset = super.getCursorOffset();
            final Drawable cursor = mCursorCount > 0 ? mCursorDrawable[0] : null;
            if (cursor != null) {
                cursor.getPadding(mTempRect);
                offset += (cursor.getIntrinsicWidth() - mTempRect.left - mTempRect.right) / 2;
            }
            return offset;
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            final boolean result = super.onTouchEvent(ev);

            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mDownPositionX = ev.getRawX();
                    mDownPositionY = ev.getRawY();
                    break;

                case MotionEvent.ACTION_UP:
                    if (!offsetHasBeenChanged()) {
                        final float deltaX = mDownPositionX - ev.getRawX();
                        final float deltaY = mDownPositionY - ev.getRawY();
                        final float distanceSquared = deltaX * deltaX + deltaY * deltaY;

                        final ViewConfiguration viewConfiguration = ViewConfiguration.get(
                                mTextView.getContext());
                        final int touchSlop = viewConfiguration.getScaledTouchSlop();

                        if (distanceSquared < touchSlop * touchSlop) {
                            // Tapping on the handle toggles the insertion action mode.
                            if (mTextActionMode != null) {
                                mTextActionMode.finish();
                            } else {
                                startInsertionActionMode();
                            }
                        }
                    } else {
                        if (mTextActionMode != null) {
                            mTextActionMode.invalidateContentRect();
                        }
                    }
                    hideAfterDelay();
                    break;

                case MotionEvent.ACTION_CANCEL:
                    hideAfterDelay();
                    break;

                default:
                    break;
            }

            return result;
        }

        @Override
        public int getCurrentCursorOffset() {
            return mTextView.getSelectionStart();
        }

        @Override
        public void updateSelection(int offset) {
            Selection.setSelection((Spannable) mTextView.getText(), offset);
        }

        @Override
        public void updatePosition(float x, float y) {
            Layout layout = mTextView.getLayout();
            int offset;
            if (layout != null) {
                if (mPreviousLineTouched == UNSET_LINE) {
                    mPreviousLineTouched = mTextView.getLineAtCoordinate(y);
                }
                int currLine = getCurrentLineAdjustedForSlop(layout, mPreviousLineTouched, y);
                offset = mTextView.getOffsetAtCoordinate(currLine, x);
                mPreviousLineTouched = currLine;
            } else {
                offset = mTextView.getOffsetForPosition(x, y);
            }
            positionAtCursorOffset(offset, false);
            if (mTextActionMode != null) {
                mTextActionMode.invalidate();
            }
        }

        @Override
        void onHandleMoved() {
            super.onHandleMoved();
            removeHiderCallback();
        }

        @Override
        public void onDetached() {
            super.onDetached();
            removeHiderCallback();
        }
    }

    private class SelectionStartHandleView extends HandleView {
        // Indicates whether the cursor is making adjustments within a word.
        private boolean mInWord = false;
        // Difference between touch position and word boundary position.
        private float mTouchWordDelta;
        // X value of the previous updatePosition call.
        private float mPrevX;
        // Indicates if the handle has moved a boundary between LTR and RTL text.
        private boolean mLanguageDirectionChanged = false;
        // Distance from edge of horizontally scrolling text view
        // to use to switch to character mode.
        private final float mTextViewEdgeSlop;
        // Used to save text view location.
        private final int[] mTextViewLocation = new int[2];

        public SelectionStartHandleView(Drawable drawableLtr, Drawable drawableRtl) {
            super(drawableLtr, drawableRtl);
            ViewConfiguration viewConfiguration = ViewConfiguration.get(
                    mTextView.getContext());
            mTextViewEdgeSlop = viewConfiguration.getScaledTouchSlop() * 4;
        }

        @Override
        protected int getHotspotX(Drawable drawable, boolean isRtlRun) {
            if (isRtlRun) {
                return drawable.getIntrinsicWidth() / 4;
            } else {
                return (drawable.getIntrinsicWidth() * 3) / 4;
            }
        }

        @Override
        protected int getHorizontalGravity(boolean isRtlRun) {
            return isRtlRun ? Gravity.LEFT : Gravity.RIGHT;
        }

        @Override
        public int getCurrentCursorOffset() {
            return mTextView.getSelectionStart();
        }

        @Override
        public void updateSelection(int offset) {
            Selection.setSelection((Spannable) mTextView.getText(), offset,
                    mTextView.getSelectionEnd());
            updateDrawable();
            if (mTextActionMode != null) {
                mTextActionMode.invalidate();
            }
        }

        @Override
        public void updatePosition(float x, float y) {
            final Layout layout = mTextView.getLayout();
            if (layout == null) {
                // HandleView will deal appropriately in positionAtCursorOffset when
                // layout is null.
                positionAndAdjustForCrossingHandles(mTextView.getOffsetForPosition(x, y));
                return;
            }

            if (mPreviousLineTouched == UNSET_LINE) {
                mPreviousLineTouched = mTextView.getLineAtCoordinate(y);
            }

            boolean positionCursor = false;
            final int selectionEnd = mTextView.getSelectionEnd();
            int currLine = getCurrentLineAdjustedForSlop(layout, mPreviousLineTouched, y);
            int initialOffset = mTextView.getOffsetAtCoordinate(currLine, x);

            if (initialOffset >= selectionEnd) {
                // Handles have crossed, bound it to the last selected line and
                // adjust by word / char as normal.
                currLine = layout.getLineForOffset(selectionEnd);
                initialOffset = mTextView.getOffsetAtCoordinate(currLine, x);
            }

            int offset = initialOffset;
            int end = getWordEnd(offset);
            int start = getWordStart(offset);

            if (mPrevX == UNSET_X_VALUE) {
                mPrevX = x;
            }

            final int selectionStart = mTextView.getSelectionStart();
            final boolean selectionStartRtl = layout.isRtlCharAt(selectionStart);
            final boolean atRtl = layout.isRtlCharAt(offset);
            final boolean isLvlBoundary = layout.isLevelBoundary(offset);
            boolean isExpanding;

            // We can't determine if the user is expanding or shrinking the selection if they're
            // on a bi-di boundary, so until they've moved past the boundary we'll just place
            // the cursor at the current position.
            if (isLvlBoundary || (selectionStartRtl && !atRtl) || (!selectionStartRtl && atRtl)) {
                // We're on a boundary or this is the first direction change -- just update
                // to the current position.
                mLanguageDirectionChanged = true;
                mTouchWordDelta = 0.0f;
                positionAndAdjustForCrossingHandles(offset);
                return;
            } else if (mLanguageDirectionChanged && !isLvlBoundary) {
                // We've just moved past the boundary so update the position. After this we can
                // figure out if the user is expanding or shrinking to go by word or character.
                positionAndAdjustForCrossingHandles(offset);
                mTouchWordDelta = 0.0f;
                mLanguageDirectionChanged = false;
                return;
            } else {
                final float xDiff = x - mPrevX;
                if (atRtl) {
                    isExpanding = xDiff > 0 || currLine > mPreviousLineTouched;
                } else {
                    isExpanding = xDiff < 0 || currLine < mPreviousLineTouched;
                }
            }

            if (mTextView.getHorizontallyScrolling()) {
                if (positionNearEdgeOfScrollingView(x, atRtl)
                        && (mTextView.getScrollX() != 0)
                        && ((isExpanding && offset < selectionStart) || !isExpanding)) {
                    // If we're expanding ensure that the offset is smaller than the
                    // selection start, if the handle snapped to the word, the finger position
                    // may be out of sync and we don't want the selection to jump back.
                    mTouchWordDelta = 0.0f;
                    final int nextOffset = atRtl ? layout.getOffsetToRightOf(mPreviousOffset)
                            : layout.getOffsetToLeftOf(mPreviousOffset);
                    positionAndAdjustForCrossingHandles(nextOffset);
                    return;
                }
            }

            if (isExpanding) {
                // User is increasing the selection.
                if (!mInWord || currLine < mPrevLine) {
                    // Sometimes words can be broken across lines (Chinese, hyphenation).
                    // We still snap to the start of the word but we only use the letters on the
                    // current line to determine if the user is far enough into the word to snap.
                    int wordStartOnCurrLine = start;
                    if (layout != null && layout.getLineForOffset(start) != currLine) {
                        wordStartOnCurrLine = layout.getLineStart(currLine);
                    }
                    int offsetThresholdToSnap = end - ((end - wordStartOnCurrLine) / 2);
                    if (offset <= offsetThresholdToSnap || currLine < mPrevLine) {
                        // User is far enough into the word or on a different
                        // line so we expand by word.
                        offset = start;
                    } else {
                        offset = mPreviousOffset;
                    }
                }
                if (layout != null && offset < initialOffset) {
                    final float adjustedX = layout.getPrimaryHorizontal(offset);
                    mTouchWordDelta =
                            mTextView.convertToLocalHorizontalCoordinate(x) - adjustedX;
                } else {
                    mTouchWordDelta = 0.0f;
                }
                positionCursor = true;
            } else {
                final int adjustedOffset =
                        mTextView.getOffsetAtCoordinate(currLine, x - mTouchWordDelta);
                if (adjustedOffset > mPreviousOffset || currLine > mPrevLine) {
                    // User is shrinking the selection.
                    if (currLine > mPrevLine) {
                        // We're on a different line, so we'll snap to word boundaries.
                        offset = start;
                        if (layout != null && offset < initialOffset) {
                            final float adjustedX = layout.getPrimaryHorizontal(offset);
                            mTouchWordDelta =
                                    mTextView.convertToLocalHorizontalCoordinate(x) - adjustedX;
                        } else {
                            mTouchWordDelta = 0.0f;
                        }
                    } else {
                        offset = adjustedOffset;
                    }
                    positionCursor = true;
                } else if (adjustedOffset < mPreviousOffset) {
                    // Handle has jumped to the start of the word, and the user is moving
                    // their finger towards the handle, the delta should be updated.
                    mTouchWordDelta = mTextView.convertToLocalHorizontalCoordinate(x)
                            - layout.getPrimaryHorizontal(mPreviousOffset);
                }
            }

            if (positionCursor) {
                mPreviousLineTouched = currLine;
                positionAndAdjustForCrossingHandles(offset);
            }
            mPrevX = x;
        }

        private void positionAndAdjustForCrossingHandles(int offset) {
            final int selectionEnd = mTextView.getSelectionEnd();
            if (offset >= selectionEnd) {
                // Handles can not cross and selection is at least one character.
                offset = getNextCursorOffset(selectionEnd, false);
                mTouchWordDelta = 0.0f;
            }
            positionAtCursorOffset(offset, false);
        }

        @Override
        protected void positionAtCursorOffset(int offset, boolean parentScrolled) {
            super.positionAtCursorOffset(offset, parentScrolled);
            mInWord = !getWordIteratorWithText().isBoundary(offset);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            boolean superResult = super.onTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                // Reset the touch word offset and x value when the user
                // re-engages the handle.
                mTouchWordDelta = 0.0f;
                mPrevX = UNSET_X_VALUE;
            }
            return superResult;
        }

        private boolean positionNearEdgeOfScrollingView(float x, boolean atRtl) {
            mTextView.getLocationOnScreen(mTextViewLocation);
            boolean nearEdge;
            if (atRtl) {
                int rightEdge = mTextViewLocation[0] + mTextView.getWidth()
                        - mTextView.getPaddingRight();
                nearEdge = x > rightEdge - mTextViewEdgeSlop;
            } else {
                int leftEdge = mTextViewLocation[0] + mTextView.getPaddingLeft();
                nearEdge = x < leftEdge + mTextViewEdgeSlop;
            }
            return nearEdge;
        }
    }

    private class SelectionEndHandleView extends HandleView {
        // Indicates whether the cursor is making adjustments within a word.
        private boolean mInWord = false;
        // Difference between touch position and word boundary position.
        private float mTouchWordDelta;
        // X value of the previous updatePosition call.
        private float mPrevX;
        // Indicates if the handle has moved a boundary between LTR and RTL text.
        private boolean mLanguageDirectionChanged = false;
        // Distance from edge of horizontally scrolling text view
        // to use to switch to character mode.
        private final float mTextViewEdgeSlop;
        // Used to save the text view location.
        private final int[] mTextViewLocation = new int[2];

        public SelectionEndHandleView(Drawable drawableLtr, Drawable drawableRtl) {
            super(drawableLtr, drawableRtl);
            ViewConfiguration viewConfiguration = ViewConfiguration.get(
                    mTextView.getContext());
            mTextViewEdgeSlop = viewConfiguration.getScaledTouchSlop() * 4;
        }

        @Override
        protected int getHotspotX(Drawable drawable, boolean isRtlRun) {
            if (isRtlRun) {
                return (drawable.getIntrinsicWidth() * 3) / 4;
            } else {
                return drawable.getIntrinsicWidth() / 4;
            }
        }

        @Override
        protected int getHorizontalGravity(boolean isRtlRun) {
            return isRtlRun ? Gravity.RIGHT : Gravity.LEFT;
        }

        @Override
        public int getCurrentCursorOffset() {
            return mTextView.getSelectionEnd();
        }

        @Override
        public void updateSelection(int offset) {
            Selection.setSelection((Spannable) mTextView.getText(),
                    mTextView.getSelectionStart(), offset);
            if (mTextActionMode != null) {
                mTextActionMode.invalidate();
            }
            updateDrawable();
        }

        @Override
        public void updatePosition(float x, float y) {
            final Layout layout = mTextView.getLayout();
            if (layout == null) {
                // HandleView will deal appropriately in positionAtCursorOffset when
                // layout is null.
                positionAndAdjustForCrossingHandles(mTextView.getOffsetForPosition(x, y));
                return;
            }

            if (mPreviousLineTouched == UNSET_LINE) {
                mPreviousLineTouched = mTextView.getLineAtCoordinate(y);
            }

            boolean positionCursor = false;
            final int selectionStart = mTextView.getSelectionStart();
            int currLine = getCurrentLineAdjustedForSlop(layout, mPreviousLineTouched, y);
            int initialOffset = mTextView.getOffsetAtCoordinate(currLine, x);

            if (initialOffset <= selectionStart) {
                // Handles have crossed, bound it to the first selected line and
                // adjust by word / char as normal.
                currLine = layout.getLineForOffset(selectionStart);
                initialOffset = mTextView.getOffsetAtCoordinate(currLine, x);
            }

            int offset = initialOffset;
            int end = getWordEnd(offset);
            int start = getWordStart(offset);

            if (mPrevX == UNSET_X_VALUE) {
                mPrevX = x;
            }

            final int selectionEnd = mTextView.getSelectionEnd();
            final boolean selectionEndRtl = layout.isRtlCharAt(selectionEnd);
            final boolean atRtl = layout.isRtlCharAt(offset);
            final boolean isLvlBoundary = layout.isLevelBoundary(offset);
            boolean isExpanding;

            // We can't determine if the user is expanding or shrinking the selection if they're
            // on a bi-di boundary, so until they've moved past the boundary we'll just place
            // the cursor at the current position.
            if (isLvlBoundary || (selectionEndRtl && !atRtl) || (!selectionEndRtl && atRtl)) {
                // We're on a boundary or this is the first direction change -- just update
                // to the current position.
                mLanguageDirectionChanged = true;
                mTouchWordDelta = 0.0f;
                positionAndAdjustForCrossingHandles(offset);
                return;
            } else if (mLanguageDirectionChanged && !isLvlBoundary) {
                // We've just moved past the boundary so update the position. After this we can
                // figure out if the user is expanding or shrinking to go by word or character.
                positionAndAdjustForCrossingHandles(offset);
                mTouchWordDelta = 0.0f;
                mLanguageDirectionChanged = false;
                return;
            } else {
                final float xDiff = x - mPrevX;
                if (atRtl) {
                    isExpanding = xDiff < 0 || currLine < mPreviousLineTouched;
                } else {
                    isExpanding = xDiff > 0 || currLine > mPreviousLineTouched;
                }
            }

            if (mTextView.getHorizontallyScrolling()) {
                if (positionNearEdgeOfScrollingView(x, atRtl)
                        && mTextView.canScrollHorizontally(atRtl ? -1 : 1)
                        && ((isExpanding && offset > selectionEnd) || !isExpanding)) {
                    // If we're expanding ensure that the offset is actually greater than the
                    // selection end, if the handle snapped to the word, the finger position
                    // may be out of sync and we don't want the selection to jump back.
                    mTouchWordDelta = 0.0f;
                    final int nextOffset = atRtl ? layout.getOffsetToLeftOf(mPreviousOffset)
                            : layout.getOffsetToRightOf(mPreviousOffset);
                    positionAndAdjustForCrossingHandles(nextOffset);
                    return;
                }
            }

            if (isExpanding) {
                // User is increasing the selection.
                if (!mInWord || currLine > mPrevLine) {
                    // Sometimes words can be broken across lines (Chinese, hyphenation).
                    // We still snap to the end of the word but we only use the letters on the
                    // current line to determine if the user is far enough into the word to snap.
                    int wordEndOnCurrLine = end;
                    if (layout != null && layout.getLineForOffset(end) != currLine) {
                        wordEndOnCurrLine = layout.getLineEnd(currLine);
                    }
                    final int offsetThresholdToSnap = start + ((wordEndOnCurrLine - start) / 2);
                    if (offset >= offsetThresholdToSnap || currLine > mPrevLine) {
                        // User is far enough into the word or on a different
                        // line so we expand by word.
                        offset = end;
                    } else {
                        offset = mPreviousOffset;
                    }
                }
                if (offset > initialOffset) {
                    final float adjustedX = layout.getPrimaryHorizontal(offset);
                    mTouchWordDelta =
                            adjustedX - mTextView.convertToLocalHorizontalCoordinate(x);
                } else {
                    mTouchWordDelta = 0.0f;
                }
                positionCursor = true;
            } else {
                final int adjustedOffset =
                        mTextView.getOffsetAtCoordinate(currLine, x + mTouchWordDelta);
                if (adjustedOffset < mPreviousOffset || currLine < mPrevLine) {
                    // User is shrinking the selection.
                    if (currLine < mPrevLine) {
                        // We're on a different line, so we'll snap to word boundaries.
                        offset = end;
                        if (offset > initialOffset) {
                            final float adjustedX = layout.getPrimaryHorizontal(offset);
                            mTouchWordDelta =
                                    adjustedX - mTextView.convertToLocalHorizontalCoordinate(x);
                        } else {
                            mTouchWordDelta = 0.0f;
                        }
                    } else {
                        offset = adjustedOffset;
                    }
                    positionCursor = true;
                } else if (adjustedOffset > mPreviousOffset) {
                    // Handle has jumped to the end of the word, and the user is moving
                    // their finger towards the handle, the delta should be updated.
                    mTouchWordDelta = layout.getPrimaryHorizontal(mPreviousOffset)
                            - mTextView.convertToLocalHorizontalCoordinate(x);
                }
            }

            if (positionCursor) {
                mPreviousLineTouched = currLine;
                positionAndAdjustForCrossingHandles(offset);
            }
            mPrevX = x;
        }

        private void positionAndAdjustForCrossingHandles(int offset) {
            final int selectionStart = mTextView.getSelectionStart();
            if (offset <= selectionStart) {
                // Handles can not cross and selection is at least one character.
                offset = getNextCursorOffset(selectionStart, true);
                mTouchWordDelta = 0.0f;
            }
            positionAtCursorOffset(offset, false);
        }

        @Override
        protected void positionAtCursorOffset(int offset, boolean parentScrolled) {
            super.positionAtCursorOffset(offset, parentScrolled);
            mInWord = !getWordIteratorWithText().isBoundary(offset);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            boolean superResult = super.onTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                // Reset the touch word offset and x value when the user
                // re-engages the handle.
                mTouchWordDelta = 0.0f;
                mPrevX = UNSET_X_VALUE;
            }
            return superResult;
        }

        private boolean positionNearEdgeOfScrollingView(float x, boolean atRtl) {
            mTextView.getLocationOnScreen(mTextViewLocation);
            boolean nearEdge;
            if (atRtl) {
                int leftEdge = mTextViewLocation[0] + mTextView.getPaddingLeft();
                nearEdge = x < leftEdge + mTextViewEdgeSlop;
            } else {
                int rightEdge = mTextViewLocation[0] + mTextView.getWidth()
                        - mTextView.getPaddingRight();
                nearEdge = x > rightEdge - mTextViewEdgeSlop;
            }
            return nearEdge;
        }
    }

    private int getCurrentLineAdjustedForSlop(Layout layout, int prevLine, float y) {
        final int trueLine = mTextView.getLineAtCoordinate(y);
        if (layout == null || prevLine > layout.getLineCount()
                || layout.getLineCount() <= 0 || prevLine < 0) {
            // Invalid parameters, just return whatever line is at y.
            return trueLine;
        }

        if (Math.abs(trueLine - prevLine) >= 2) {
            // Only stick to lines if we're within a line of the previous selection.
            return trueLine;
        }

        final float verticalOffset = mTextView.viewportToContentVerticalOffset();
        final int lineCount = layout.getLineCount();
        final float slop = mTextView.getLineHeight() * LINE_SLOP_MULTIPLIER_FOR_HANDLEVIEWS;

        final float firstLineTop = layout.getLineTop(0) + verticalOffset;
        final float prevLineTop = layout.getLineTop(prevLine) + verticalOffset;
        final float yTopBound = Math.max(prevLineTop - slop, firstLineTop + slop);

        final float lastLineBottom = layout.getLineBottom(lineCount - 1) + verticalOffset;
        final float prevLineBottom = layout.getLineBottom(prevLine) + verticalOffset;
        final float yBottomBound = Math.min(prevLineBottom + slop, lastLineBottom - slop);

        // Determine if we've moved lines based on y position and previous line.
        int currLine;
        if (y <= yTopBound) {
            currLine = Math.max(prevLine - 1, 0);
        } else if (y >= yBottomBound) {
            currLine = Math.min(prevLine + 1, lineCount - 1);
        } else {
            currLine = prevLine;
        }
        return currLine;
    }

    /**
     * A CursorController instance can be used to control a cursor in the text.
     */
    private interface CursorController extends ViewTreeObserver.OnTouchModeChangeListener {
        /**
         * Makes the cursor controller visible on screen.
         * See also {@link #hide()}.
         */
        public void show();

        /**
         * Hide the cursor controller from screen.
         * See also {@link #show()}.
         */
        public void hide();

        /**
         * Called when the view is detached from window. Perform house keeping task, such as
         * stopping Runnable thread that would otherwise keep a reference on the context, thus
         * preventing the activity from being recycled.
         */
        public void onDetached();
    }

    private class InsertionPointCursorController implements CursorController {
        private InsertionHandleView mHandle;

        public void show() {
            getHandle().show();

            if (mSelectionModifierCursorController != null) {
                mSelectionModifierCursorController.hide();
            }
        }

        public void hide() {
            if (mHandle != null) {
                mHandle.hide();
            }
        }

        public void onTouchModeChanged(boolean isInTouchMode) {
            if (!isInTouchMode) {
                hide();
            }
        }

        private InsertionHandleView getHandle() {
            if (mSelectHandleCenter == null) {
                mSelectHandleCenter = mTextView.getContext().getDrawable(
                        mTextView.mTextSelectHandleRes);
            }
            if (mHandle == null) {
                mHandle = new InsertionHandleView(mSelectHandleCenter);
            }
            return mHandle;
        }

        @Override
        public void onDetached() {
            final ViewTreeObserver observer = mTextView.getViewTreeObserver();
            observer.removeOnTouchModeChangeListener(this);

            if (mHandle != null) mHandle.onDetached();
        }
    }

    class SelectionModifierCursorController implements CursorController {
        // The cursor controller handles, lazily created when shown.
        private SelectionStartHandleView mStartHandle;
        private SelectionEndHandleView mEndHandle;
        // The offsets of that last touch down event. Remembered to start selection there.
        private int mMinTouchOffset, mMaxTouchOffset;

        private float mDownPositionX, mDownPositionY;
        private boolean mGestureStayedInTapRegion;

        // Where the user first starts the drag motion.
        private int mStartOffset = -1;
        // Indicates whether the user is selecting text and using the drag accelerator.
        private boolean mDragAcceleratorActive;
        private boolean mHaventMovedEnoughToStartDrag;
        // The line that a selection happened most recently with the drag accelerator.
        private int mLineSelectionIsOn = -1;
        // Whether the drag accelerator has selected past the initial line.
        private boolean mSwitchedLines = false;

        SelectionModifierCursorController() {
            resetTouchOffsets();
        }

        public void show() {
            if (mTextView.isInBatchEditMode()) {
                return;
            }
            initDrawables();
            initHandles();
            hideInsertionPointCursorController();
        }

        private void initDrawables() {
            if (mSelectHandleLeft == null) {
                mSelectHandleLeft = mTextView.getContext().getDrawable(
                        mTextView.mTextSelectHandleLeftRes);
            }
            if (mSelectHandleRight == null) {
                mSelectHandleRight = mTextView.getContext().getDrawable(
                        mTextView.mTextSelectHandleRightRes);
            }
        }

        private void initHandles() {
            // Lazy object creation has to be done before updatePosition() is called.
            if (mStartHandle == null) {
                mStartHandle = new SelectionStartHandleView(mSelectHandleLeft, mSelectHandleRight);
            }
            if (mEndHandle == null) {
                mEndHandle = new SelectionEndHandleView(mSelectHandleRight, mSelectHandleLeft);
            }

            mStartHandle.show();
            mEndHandle.show();

            hideInsertionPointCursorController();
        }

        public void hide() {
            if (mStartHandle != null) mStartHandle.hide();
            if (mEndHandle != null) mEndHandle.hide();
        }

        public void enterDrag() {
            // Just need to init the handles / hide insertion cursor.
            show();
            mDragAcceleratorActive = true;
            // Start location of selection.
            mStartOffset = mTextView.getOffsetForPosition(mLastDownPositionX,
                    mLastDownPositionY);
            mLineSelectionIsOn = mTextView.getLineAtCoordinate(mLastDownPositionY);
            // Don't show the handles until user has lifted finger.
            hide();

            // This stops scrolling parents from intercepting the touch event, allowing
            // the user to continue dragging across the screen to select text; TextView will
            // scroll as necessary.
            mTextView.getParent().requestDisallowInterceptTouchEvent(true);
        }

        public void onTouchEvent(MotionEvent event) {
            // This is done even when the View does not have focus, so that long presses can start
            // selection and tap can move cursor from this tap position.
            final float eventX = event.getX();
            final float eventY = event.getY();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (extractedTextModeWillBeStarted()) {
                        // Prevent duplicating the selection handles until the mode starts.
                        hide();
                    } else {
                        // Remember finger down position, to be able to start selection from there.
                        mMinTouchOffset = mMaxTouchOffset = mTextView.getOffsetForPosition(
                                eventX, eventY);

                        // Double tap detection
                        if (mGestureStayedInTapRegion) {
                            if (mDoubleTap) {
                                final float deltaX = eventX - mDownPositionX;
                                final float deltaY = eventY - mDownPositionY;
                                final float distanceSquared = deltaX * deltaX + deltaY * deltaY;

                                ViewConfiguration viewConfiguration = ViewConfiguration.get(
                                        mTextView.getContext());
                                int doubleTapSlop = viewConfiguration.getScaledDoubleTapSlop();
                                boolean stayedInArea =
                                        distanceSquared < doubleTapSlop * doubleTapSlop;

                                if (stayedInArea && isPositionOnText(eventX, eventY)) {
                                    selectCurrentWordAndStartDrag();
                                    mDiscardNextActionUp = true;
                                }
                            }
                        }

                        mDownPositionX = eventX;
                        mDownPositionY = eventY;
                        mGestureStayedInTapRegion = true;
                        mHaventMovedEnoughToStartDrag = true;
                    }
                    break;

                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_POINTER_UP:
                    // Handle multi-point gestures. Keep min and max offset positions.
                    // Only activated for devices that correctly handle multi-touch.
                    if (mTextView.getContext().getPackageManager().hasSystemFeature(
                            PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH_DISTINCT)) {
                        updateMinAndMaxOffsets(event);
                    }
                    break;

                case MotionEvent.ACTION_MOVE:
                    final ViewConfiguration viewConfig = ViewConfiguration.get(
                            mTextView.getContext());
                    final int touchSlop = viewConfig.getScaledTouchSlop();

                    if (mGestureStayedInTapRegion || mHaventMovedEnoughToStartDrag) {
                        final float deltaX = eventX - mDownPositionX;
                        final float deltaY = eventY - mDownPositionY;
                        final float distanceSquared = deltaX * deltaX + deltaY * deltaY;

                        if (mGestureStayedInTapRegion) {
                            int doubleTapTouchSlop = viewConfig.getScaledDoubleTapTouchSlop();
                            mGestureStayedInTapRegion =
                                    distanceSquared <= doubleTapTouchSlop * doubleTapTouchSlop;
                        }
                        if (mHaventMovedEnoughToStartDrag) {
                            // We don't start dragging until the user has moved enough.
                            mHaventMovedEnoughToStartDrag =
                                    distanceSquared <= touchSlop * touchSlop;
                        }
                    }

                    if (mStartHandle != null && mStartHandle.isShowing()) {
                        // Don't do the drag if the handles are showing already.
                        break;
                    }

                    if (mStartOffset != -1 && mTextView.getLayout() != null) {
                        if (!mHaventMovedEnoughToStartDrag) {

                            float y = eventY;
                            if (mSwitchedLines) {
                                // Offset the finger by the same vertical offset as the handles.
                                // This improves visibility of the content being selected by
                                // shifting the finger below the content, this is applied once
                                // the user has switched lines.
                                final float fingerOffset = (mStartHandle != null)
                                        ? mStartHandle.getIdealVerticalOffset()
                                        : touchSlop;
                                y = eventY - fingerOffset;
                            }

                            final int currLine = getCurrentLineAdjustedForSlop(
                                    mTextView.getLayout(),
                                    mLineSelectionIsOn, y);
                            if (!mSwitchedLines && currLine != mLineSelectionIsOn) {
                                // Break early here, we want to offset the finger position from
                                // the selection highlight, once the user moved their finger
                                // to a different line we should apply the offset and *not* switch
                                // lines until recomputing the position with the finger offset.
                                mSwitchedLines = true;
                                break;
                            }

                            int startOffset;
                            int offset = mTextView.getOffsetAtCoordinate(currLine, eventX);
                            // Snap to word boundaries.
                            if (mStartOffset < offset) {
                                // Expanding with end handle.
                                offset = getWordEnd(offset);
                                startOffset = getWordStart(mStartOffset);
                            } else {
                                // Expanding with start handle.
                                offset = getWordStart(offset);
                                startOffset = getWordEnd(mStartOffset);
                            }
                            mLineSelectionIsOn = currLine;
                            Selection.setSelection((Spannable) mTextView.getText(),
                                    startOffset, offset);
                        }
                    }
                    break;

                case MotionEvent.ACTION_UP:
                    if (mDragAcceleratorActive) {
                        // No longer dragging to select text, let the parent intercept events.
                        mTextView.getParent().requestDisallowInterceptTouchEvent(false);

                        show();
                        int startOffset = mTextView.getSelectionStart();
                        int endOffset = mTextView.getSelectionEnd();

                        // Since we don't let drag handles pass once they're visible, we need to
                        // make sure the start / end locations are correct because the user *can*
                        // switch directions during the initial drag.
                        if (endOffset < startOffset) {
                            int tmp = endOffset;
                            endOffset = startOffset;
                            startOffset = tmp;

                            // Also update the selection with the right offsets in this case.
                            Selection.setSelection((Spannable) mTextView.getText(),
                                    startOffset, endOffset);
                        }

                        // Need to do this to display the handles.
                        mStartHandle.showAtLocation(startOffset);
                        mEndHandle.showAtLocation(endOffset);

                        // No longer the first dragging motion, reset.
                        if (!(mTextView.isInExtractedMode())) {
                            startSelectionActionMode();
                        }
                        mDragAcceleratorActive = false;
                        mStartOffset = -1;
                        mSwitchedLines = false;
                    }
                    break;
            }
        }

        /**
         * @param event
         */
        private void updateMinAndMaxOffsets(MotionEvent event) {
            int pointerCount = event.getPointerCount();
            for (int index = 0; index < pointerCount; index++) {
                int offset = mTextView.getOffsetForPosition(event.getX(index), event.getY(index));
                if (offset < mMinTouchOffset) mMinTouchOffset = offset;
                if (offset > mMaxTouchOffset) mMaxTouchOffset = offset;
            }
        }

        public int getMinTouchOffset() {
            return mMinTouchOffset;
        }

        public int getMaxTouchOffset() {
            return mMaxTouchOffset;
        }

        public void resetTouchOffsets() {
            mMinTouchOffset = mMaxTouchOffset = -1;
            mStartOffset = -1;
            mDragAcceleratorActive = false;
            mSwitchedLines = false;
        }

        /**
         * @return true iff this controller is currently used to move the selection start.
         */
        public boolean isSelectionStartDragged() {
            return mStartHandle != null && mStartHandle.isDragging();
        }

        /**
         * @return true if the user is selecting text using the drag accelerator.
         */
        public boolean isDragAcceleratorActive() {
            return mDragAcceleratorActive;
        }

        public void onTouchModeChanged(boolean isInTouchMode) {
            if (!isInTouchMode) {
                hide();
            }
        }

        @Override
        public void onDetached() {
            final ViewTreeObserver observer = mTextView.getViewTreeObserver();
            observer.removeOnTouchModeChangeListener(this);

            if (mStartHandle != null) mStartHandle.onDetached();
            if (mEndHandle != null) mEndHandle.onDetached();
        }
    }

    private class CorrectionHighlighter {
        private final Path mPath = new Path();
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int mStart, mEnd;
        private long mFadingStartTime;
        private RectF mTempRectF;
        private final static int FADE_OUT_DURATION = 400;

        public CorrectionHighlighter() {
            mPaint.setCompatibilityScaling(mTextView.getResources().getCompatibilityInfo().
                    applicationScale);
            mPaint.setStyle(Paint.Style.FILL);
        }

        public void highlight(CorrectionInfo info) {
            mStart = info.getOffset();
            mEnd = mStart + info.getNewText().length();
            mFadingStartTime = SystemClock.uptimeMillis();

            if (mStart < 0 || mEnd < 0) {
                stopAnimation();
            }
        }

        public void draw(Canvas canvas, int cursorOffsetVertical) {
            if (updatePath() && updatePaint()) {
                if (cursorOffsetVertical != 0) {
                    canvas.translate(0, cursorOffsetVertical);
                }

                canvas.drawPath(mPath, mPaint);

                if (cursorOffsetVertical != 0) {
                    canvas.translate(0, -cursorOffsetVertical);
                }
                invalidate(true); // TODO invalidate cursor region only
            } else {
                stopAnimation();
                invalidate(false); // TODO invalidate cursor region only
            }
        }

        private boolean updatePaint() {
            final long duration = SystemClock.uptimeMillis() - mFadingStartTime;
            if (duration > FADE_OUT_DURATION) return false;

            final float coef = 1.0f - (float) duration / FADE_OUT_DURATION;
            final int highlightColorAlpha = Color.alpha(mTextView.mHighlightColor);
            final int color = (mTextView.mHighlightColor & 0x00FFFFFF) +
                    ((int) (highlightColorAlpha * coef) << 24);
            mPaint.setColor(color);
            return true;
        }

        private boolean updatePath() {
            final Layout layout = mTextView.getLayout();
            if (layout == null) return false;

            // Update in case text is edited while the animation is run
            final int length = mTextView.getText().length();
            int start = Math.min(length, mStart);
            int end = Math.min(length, mEnd);

            mPath.reset();
            layout.getSelectionPath(start, end, mPath);
            return true;
        }

        private void invalidate(boolean delayed) {
            if (mTextView.getLayout() == null) return;

            if (mTempRectF == null) mTempRectF = new RectF();
            mPath.computeBounds(mTempRectF, false);

            int left = mTextView.getCompoundPaddingLeft();
            int top = mTextView.getExtendedPaddingTop() + mTextView.getVerticalOffset(true);

            if (delayed) {
                mTextView.postInvalidateOnAnimation(
                        left + (int) mTempRectF.left, top + (int) mTempRectF.top,
                        left + (int) mTempRectF.right, top + (int) mTempRectF.bottom);
            } else {
                mTextView.postInvalidate((int) mTempRectF.left, (int) mTempRectF.top,
                        (int) mTempRectF.right, (int) mTempRectF.bottom);
            }
        }

        private void stopAnimation() {
            Editor.this.mCorrectionHighlighter = null;
        }
    }

    private static class ErrorPopup extends PopupWindow {
        private boolean mAbove = false;
        private final TextView mView;
        private int mPopupInlineErrorBackgroundId = 0;
        private int mPopupInlineErrorAboveBackgroundId = 0;

        ErrorPopup(TextView v, int width, int height) {
            super(v, width, height);
            mView = v;
            // Make sure the TextView has a background set as it will be used the first time it is
            // shown and positioned. Initialized with below background, which should have
            // dimensions identical to the above version for this to work (and is more likely).
            mPopupInlineErrorBackgroundId = getResourceId(mPopupInlineErrorBackgroundId,
                    com.android.internal.R.styleable.Theme_errorMessageBackground);
            mView.setBackgroundResource(mPopupInlineErrorBackgroundId);
        }

        void fixDirection(boolean above) {
            mAbove = above;

            if (above) {
                mPopupInlineErrorAboveBackgroundId =
                    getResourceId(mPopupInlineErrorAboveBackgroundId,
                            com.android.internal.R.styleable.Theme_errorMessageAboveBackground);
            } else {
                mPopupInlineErrorBackgroundId = getResourceId(mPopupInlineErrorBackgroundId,
                        com.android.internal.R.styleable.Theme_errorMessageBackground);
            }

            mView.setBackgroundResource(above ? mPopupInlineErrorAboveBackgroundId :
                mPopupInlineErrorBackgroundId);
        }

        private int getResourceId(int currentId, int index) {
            if (currentId == 0) {
                TypedArray styledAttributes = mView.getContext().obtainStyledAttributes(
                        R.styleable.Theme);
                currentId = styledAttributes.getResourceId(index, 0);
                styledAttributes.recycle();
            }
            return currentId;
        }

        @Override
        public void update(int x, int y, int w, int h, boolean force) {
            super.update(x, y, w, h, force);

            boolean above = isAboveAnchor();
            if (above != mAbove) {
                fixDirection(above);
            }
        }
    }

    static class InputContentType {
        int imeOptions = EditorInfo.IME_NULL;
        String privateImeOptions;
        CharSequence imeActionLabel;
        int imeActionId;
        Bundle extras;
        OnEditorActionListener onEditorActionListener;
        boolean enterDown;
    }

    static class InputMethodState {
        ExtractedTextRequest mExtractedTextRequest;
        final ExtractedText mExtractedText = new ExtractedText();
        int mBatchEditNesting;
        boolean mCursorChanged;
        boolean mSelectionModeChanged;
        boolean mContentChanged;
        int mChangedStart, mChangedEnd, mChangedDelta;
    }

    /**
     * @return True iff (start, end) is a valid range within the text.
     */
    private static boolean isValidRange(CharSequence text, int start, int end) {
        return 0 <= start && start <= end && end <= text.length();
    }

    /**
     * An InputFilter that monitors text input to maintain undo history. It does not modify the
     * text being typed (and hence always returns null from the filter() method).
     */
    public static class UndoInputFilter implements InputFilter {
        private final Editor mEditor;

        // Whether the current filter pass is directly caused by an end-user text edit.
        private boolean mIsUserEdit;

        // Whether the text field is handling an IME composition. Must be parceled in case the user
        // rotates the screen during composition.
        private boolean mHasComposition;

        public UndoInputFilter(Editor editor) {
            mEditor = editor;
        }

        public void saveInstanceState(Parcel parcel) {
            parcel.writeInt(mIsUserEdit ? 1 : 0);
            parcel.writeInt(mHasComposition ? 1 : 0);
        }

        public void restoreInstanceState(Parcel parcel) {
            mIsUserEdit = parcel.readInt() != 0;
            mHasComposition = parcel.readInt() != 0;
        }

        /**
         * Signals that a user-triggered edit is starting.
         */
        public void beginBatchEdit() {
            if (DEBUG_UNDO) Log.d(TAG, "beginBatchEdit");
            mIsUserEdit = true;
        }

        public void endBatchEdit() {
            if (DEBUG_UNDO) Log.d(TAG, "endBatchEdit");
            mIsUserEdit = false;
        }

        @Override
        public CharSequence filter(CharSequence source, int start, int end,
                Spanned dest, int dstart, int dend) {
            if (DEBUG_UNDO) {
                Log.d(TAG, "filter: source=" + source + " (" + start + "-" + end + ") " +
                        "dest=" + dest + " (" + dstart + "-" + dend + ")");
            }

            // Check to see if this edit should be tracked for undo.
            if (!canUndoEdit(source, start, end, dest, dstart, dend)) {
                return null;
            }

            // Check for and handle IME composition edits.
            if (handleCompositionEdit(source, start, end, dstart)) {
                return null;
            }

            // Handle keyboard edits.
            handleKeyboardEdit(source, start, end, dest, dstart, dend);
            return null;
        }

        /**
         * Returns true iff the edit was handled, either because it should be ignored or because
         * this function created an undo operation for it.
         */
        private boolean handleCompositionEdit(CharSequence source, int start, int end, int dstart) {
            // Ignore edits while the user is composing.
            if (isComposition(source)) {
                mHasComposition = true;
                return true;
            }
            final boolean hadComposition = mHasComposition;
            mHasComposition = false;

            // Check for the transition out of the composing state.
            if (hadComposition) {
                // If there was no text the user canceled composition. Ignore the edit.
                if (start == end) {
                    return true;
                }

                // Otherwise the user inserted the composition.
                String newText = TextUtils.substring(source, start, end);
                EditOperation edit = new EditOperation(mEditor, "", dstart, newText);
                recordEdit(edit, false /* forceMerge */);
                return true;
            }

            // This was neither a composition event nor a transition out of composing.
            return false;
        }

        private void handleKeyboardEdit(CharSequence source, int start, int end,
                Spanned dest, int dstart, int dend) {
            // An application may install a TextWatcher to provide additional modifications after
            // the initial input filters run (e.g. a credit card formatter that adds spaces to a
            // string). This results in multiple filter() calls for what the user considers to be
            // a single operation. Always undo the whole set of changes in one step.
            final boolean forceMerge = isInTextWatcher();

            // Build a new operation with all the information from this edit.
            String newText = TextUtils.substring(source, start, end);
            String oldText = TextUtils.substring(dest, dstart, dend);
            EditOperation edit = new EditOperation(mEditor, oldText, dstart, newText);
            recordEdit(edit, forceMerge);
        }

        /**
         * Fetches the last undo operation and checks to see if a new edit should be merged into it.
         * If forceMerge is true then the new edit is always merged.
         */
        private void recordEdit(EditOperation edit, boolean forceMerge) {
            // Fetch the last edit operation and attempt to merge in the new edit.
            final UndoManager um = mEditor.mUndoManager;
            um.beginUpdate("Edit text");
            EditOperation lastEdit = um.getLastOperation(
                  EditOperation.class, mEditor.mUndoOwner, UndoManager.MERGE_MODE_UNIQUE);
            if (lastEdit == null) {
                // Add this as the first edit.
                if (DEBUG_UNDO) Log.d(TAG, "filter: adding first op " + edit);
                um.addOperation(edit, UndoManager.MERGE_MODE_NONE);
            } else if (forceMerge) {
                // Forced merges take priority because they could be the result of a non-user-edit
                // change and this case should not create a new undo operation.
                if (DEBUG_UNDO) Log.d(TAG, "filter: force merge " + edit);
                lastEdit.forceMergeWith(edit);
            } else if (!mIsUserEdit) {
                // An application directly modified the Editable outside of a text edit. Treat this
                // as a new change and don't attempt to merge.
                if (DEBUG_UNDO) Log.d(TAG, "non-user edit, new op " + edit);
                um.commitState(mEditor.mUndoOwner);
                um.addOperation(edit, UndoManager.MERGE_MODE_NONE);
            } else if (lastEdit.mergeWith(edit)) {
                // Merge succeeded, nothing else to do.
                if (DEBUG_UNDO) Log.d(TAG, "filter: merge succeeded, created " + lastEdit);
            } else {
                // Could not merge with the last edit, so commit the last edit and add this edit.
                if (DEBUG_UNDO) Log.d(TAG, "filter: merge failed, adding " + edit);
                um.commitState(mEditor.mUndoOwner);
                um.addOperation(edit, UndoManager.MERGE_MODE_NONE);
            }
            um.endUpdate();
        }

        private boolean canUndoEdit(CharSequence source, int start, int end,
                Spanned dest, int dstart, int dend) {
            if (!mEditor.mAllowUndo) {
                if (DEBUG_UNDO) Log.d(TAG, "filter: undo is disabled");
                return false;
            }

            if (mEditor.mUndoManager.isInUndo()) {
                if (DEBUG_UNDO) Log.d(TAG, "filter: skipping, currently performing undo/redo");
                return false;
            }

            // Text filters run before input operations are applied. However, some input operations
            // are invalid and will throw exceptions when applied. This is common in tests. Don't
            // attempt to undo invalid operations.
            if (!isValidRange(source, start, end) || !isValidRange(dest, dstart, dend)) {
                if (DEBUG_UNDO) Log.d(TAG, "filter: invalid op");
                return false;
            }

            // Earlier filters can rewrite input to be a no-op, for example due to a length limit
            // on an input field. Skip no-op changes.
            if (start == end && dstart == dend) {
                if (DEBUG_UNDO) Log.d(TAG, "filter: skipping no-op");
                return false;
            }

            return true;
        }

        private boolean isComposition(CharSequence source) {
            if (!(source instanceof Spannable)) {
                return false;
            }
            // This is a composition edit if the source has a non-zero-length composing span.
            Spannable text = (Spannable) source;
            int composeBegin = EditableInputConnection.getComposingSpanStart(text);
            int composeEnd = EditableInputConnection.getComposingSpanEnd(text);
            return composeBegin < composeEnd;
        }

        private boolean isInTextWatcher() {
            CharSequence text = mEditor.mTextView.getText();
            return (text instanceof SpannableStringBuilder)
                    && ((SpannableStringBuilder) text).getTextWatcherDepth() > 0;
        }
    }

    /**
     * An operation to undo a single "edit" to a text view.
     */
    public static class EditOperation extends UndoOperation<Editor> {
        private static final int TYPE_INSERT = 0;
        private static final int TYPE_DELETE = 1;
        private static final int TYPE_REPLACE = 2;

        private int mType;
        private String mOldText;
        private int mOldTextStart;
        private String mNewText;
        private int mNewTextStart;

        private int mOldCursorPos;
        private int mNewCursorPos;

        /**
         * Constructs an edit operation from a text input operation on editor that replaces the
         * oldText starting at dstart with newText.
         */
        public EditOperation(Editor editor, String oldText, int dstart, String newText) {
            super(editor.mUndoOwner);
            mOldText = oldText;
            mNewText = newText;

            // Determine the type of the edit and store where it occurred. Avoid storing
            // irrevelant data (e.g. mNewTextStart for a delete) because that makes the
            // merging logic more complex (e.g. merging deletes could lead to mNewTextStart being
            // outside the bounds of the final text).
            if (mNewText.length() > 0 && mOldText.length() == 0) {
                mType = TYPE_INSERT;
                mNewTextStart = dstart;
            } else if (mNewText.length() == 0 && mOldText.length() > 0) {
                mType = TYPE_DELETE;
                mOldTextStart = dstart;
            } else {
                mType = TYPE_REPLACE;
                mOldTextStart = mNewTextStart = dstart;
            }

            // Store cursor data.
            mOldCursorPos = editor.mTextView.getSelectionStart();
            mNewCursorPos = dstart + mNewText.length();
        }

        public EditOperation(Parcel src, ClassLoader loader) {
            super(src, loader);
            mType = src.readInt();
            mOldText = src.readString();
            mOldTextStart = src.readInt();
            mNewText = src.readString();
            mNewTextStart = src.readInt();
            mOldCursorPos = src.readInt();
            mNewCursorPos = src.readInt();
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mType);
            dest.writeString(mOldText);
            dest.writeInt(mOldTextStart);
            dest.writeString(mNewText);
            dest.writeInt(mNewTextStart);
            dest.writeInt(mOldCursorPos);
            dest.writeInt(mNewCursorPos);
        }

        private int getNewTextEnd() {
            return mNewTextStart + mNewText.length();
        }

        private int getOldTextEnd() {
            return mOldTextStart + mOldText.length();
        }

        @Override
        public void commit() {
        }

        @Override
        public void undo() {
            if (DEBUG_UNDO) Log.d(TAG, "undo");
            // Remove the new text and insert the old.
            Editor editor = getOwnerData();
            Editable text = (Editable) editor.mTextView.getText();
            modifyText(text, mNewTextStart, getNewTextEnd(), mOldText, mOldTextStart,
                    mOldCursorPos);
        }

        @Override
        public void redo() {
            if (DEBUG_UNDO) Log.d(TAG, "redo");
            // Remove the old text and insert the new.
            Editor editor = getOwnerData();
            Editable text = (Editable) editor.mTextView.getText();
            modifyText(text, mOldTextStart, getOldTextEnd(), mNewText, mNewTextStart,
                    mNewCursorPos);
        }

        /**
         * Attempts to merge this existing operation with a new edit.
         * @param edit The new edit operation.
         * @return If the merge succeeded, returns true. Otherwise returns false and leaves this
         * object unchanged.
         */
        private boolean mergeWith(EditOperation edit) {
            if (DEBUG_UNDO) {
                Log.d(TAG, "mergeWith old " + this);
                Log.d(TAG, "mergeWith new " + edit);
            }
            switch (mType) {
                case TYPE_INSERT:
                    return mergeInsertWith(edit);
                case TYPE_DELETE:
                    return mergeDeleteWith(edit);
                case TYPE_REPLACE:
                    return mergeReplaceWith(edit);
                default:
                    return false;
            }
        }

        private boolean mergeInsertWith(EditOperation edit) {
            // Only merge continuous insertions.
            if (edit.mType != TYPE_INSERT) {
                return false;
            }
            // Only merge insertions that are contiguous.
            if (getNewTextEnd() != edit.mNewTextStart) {
                return false;
            }
            mNewText += edit.mNewText;
            mNewCursorPos = edit.mNewCursorPos;
            return true;
        }

        // TODO: Support forward delete.
        private boolean mergeDeleteWith(EditOperation edit) {
            // Only merge continuous deletes.
            if (edit.mType != TYPE_DELETE) {
                return false;
            }
            // Only merge deletions that are contiguous.
            if (mOldTextStart != edit.getOldTextEnd()) {
                return false;
            }
            mOldTextStart = edit.mOldTextStart;
            mOldText = edit.mOldText + mOldText;
            mNewCursorPos = edit.mNewCursorPos;
            return true;
        }

        private boolean mergeReplaceWith(EditOperation edit) {
            // Replacements can merge only with adjacent inserts.
            if (edit.mType != TYPE_INSERT || getNewTextEnd() != edit.mNewTextStart) {
                return false;
            }
            mOldText += edit.mOldText;
            mNewText += edit.mNewText;
            mNewCursorPos = edit.mNewCursorPos;
            return true;
        }

        /**
         * Forcibly creates a single merged edit operation by simulating the entire text
         * contents being replaced.
         */
        public void forceMergeWith(EditOperation edit) {
            if (DEBUG_UNDO) Log.d(TAG, "forceMerge");
            Editor editor = getOwnerData();

            // Copy the text of the current field.
            // NOTE: Using StringBuilder instead of SpannableStringBuilder would be somewhat faster,
            // but would require two parallel implementations of modifyText() because Editable and
            // StringBuilder do not share an interface for replace/delete/insert.
            Editable editable = (Editable) editor.mTextView.getText();
            Editable originalText = new SpannableStringBuilder(editable.toString());

            // Roll back the last operation.
            modifyText(originalText, mNewTextStart, getNewTextEnd(), mOldText, mOldTextStart,
                    mOldCursorPos);

            // Clone the text again and apply the new operation.
            Editable finalText = new SpannableStringBuilder(editable.toString());
            modifyText(finalText, edit.mOldTextStart, edit.getOldTextEnd(), edit.mNewText,
                    edit.mNewTextStart, edit.mNewCursorPos);

            // Convert this operation into a non-mergeable replacement of the entire string.
            mType = TYPE_REPLACE;
            mNewText = finalText.toString();
            mNewTextStart = 0;
            mOldText = originalText.toString();
            mOldTextStart = 0;
            mNewCursorPos = edit.mNewCursorPos;
            // mOldCursorPos is unchanged.
        }

        private static void modifyText(Editable text, int deleteFrom, int deleteTo,
                CharSequence newText, int newTextInsertAt, int newCursorPos) {
            // Apply the edit if it is still valid.
            if (isValidRange(text, deleteFrom, deleteTo) &&
                    newTextInsertAt <= text.length() - (deleteTo - deleteFrom)) {
                if (deleteFrom != deleteTo) {
                    text.delete(deleteFrom, deleteTo);
                }
                if (newText.length() != 0) {
                    text.insert(newTextInsertAt, newText);
                }
            }
            // Restore the cursor position. If there wasn't an old cursor (newCursorPos == -1) then
            // don't explicitly set it and rely on SpannableStringBuilder to position it.
            // TODO: Select all the text that was undone.
            if (0 <= newCursorPos && newCursorPos <= text.length()) {
                Selection.setSelection(text, newCursorPos);
            }
        }

        private String getTypeString() {
            switch (mType) {
                case TYPE_INSERT:
                    return "insert";
                case TYPE_DELETE:
                    return "delete";
                case TYPE_REPLACE:
                    return "replace";
                default:
                    return "";
            }
        }

        @Override
        public String toString() {
            return "[mType=" + getTypeString() + ", " +
                    "mOldText=" + mOldText + ", " +
                    "mOldTextStart=" + mOldTextStart + ", " +
                    "mNewText=" + mNewText + ", " +
                    "mNewTextStart=" + mNewTextStart + ", " +
                    "mOldCursorPos=" + mOldCursorPos + ", " +
                    "mNewCursorPos=" + mNewCursorPos + "]";
        }

        public static final Parcelable.ClassLoaderCreator<EditOperation> CREATOR
                = new Parcelable.ClassLoaderCreator<EditOperation>() {
            @Override
            public EditOperation createFromParcel(Parcel in) {
                return new EditOperation(in, null);
            }

            @Override
            public EditOperation createFromParcel(Parcel in, ClassLoader loader) {
                return new EditOperation(in, loader);
            }

            @Override
            public EditOperation[] newArray(int size) {
                return new EditOperation[size];
            }
        };
    }

    /**
     * A helper for enabling and handling "PROCESS_TEXT" menu actions.
     * These allow external applications to plug into currently selected text.
     */
    static final class ProcessTextIntentActionsHandler {

        private final Editor mEditor;
        private final TextView mTextView;
        private final PackageManager mPackageManager;
        private final SparseArray<Intent> mAccessibilityIntents = new SparseArray<Intent>();
        private final SparseArray<AccessibilityNodeInfo.AccessibilityAction> mAccessibilityActions
                = new SparseArray<AccessibilityNodeInfo.AccessibilityAction>();

        private ProcessTextIntentActionsHandler(Editor editor) {
            mEditor = Preconditions.checkNotNull(editor);
            mTextView = Preconditions.checkNotNull(mEditor.mTextView);
            mPackageManager = Preconditions.checkNotNull(
                    mTextView.getContext().getPackageManager());
        }

        /**
         * Adds "PROCESS_TEXT" menu items to the specified menu.
         */
        public void onInitializeMenu(Menu menu) {
            int i = 0;
            for (ResolveInfo resolveInfo : getSupportedActivities()) {
                menu.add(Menu.NONE, Menu.NONE,
                        Editor.MENU_ITEM_ORDER_PROCESS_TEXT_INTENT_ACTIONS_START + i++,
                        getLabel(resolveInfo))
                        .setIntent(createProcessTextIntentForResolveInfo(resolveInfo))
                        .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
            }
        }

        /**
         * Performs a "PROCESS_TEXT" action if there is one associated with the specified
         * menu item.
         *
         * @return True if the action was performed, false otherwise.
         */
        public boolean performMenuItemAction(MenuItem item) {
            return fireIntent(item.getIntent());
        }

        /**
         * Initializes and caches "PROCESS_TEXT" accessibility actions.
         */
        public void initializeAccessibilityActions() {
            mAccessibilityIntents.clear();
            mAccessibilityActions.clear();
            int i = 0;
            for (ResolveInfo resolveInfo : getSupportedActivities()) {
                int actionId = TextView.ACCESSIBILITY_ACTION_PROCESS_TEXT_START_ID + i++;
                mAccessibilityActions.put(
                        actionId,
                        new AccessibilityNodeInfo.AccessibilityAction(
                                actionId, getLabel(resolveInfo)));
                mAccessibilityIntents.put(
                        actionId, createProcessTextIntentForResolveInfo(resolveInfo));
            }
        }

        /**
         * Adds "PROCESS_TEXT" accessibility actions to the specified accessibility node info.
         * NOTE: This needs a prior call to {@link #initializeAccessibilityActions()} to make the
         * latest accessibility actions available for this call.
         */
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo nodeInfo) {
            for (int i = 0; i < mAccessibilityActions.size(); i++) {
                nodeInfo.addAction(mAccessibilityActions.valueAt(i));
            }
        }

        /**
         * Performs a "PROCESS_TEXT" action if there is one associated with the specified
         * accessibility action id.
         *
         * @return True if the action was performed, false otherwise.
         */
        public boolean performAccessibilityAction(int actionId) {
            return fireIntent(mAccessibilityIntents.get(actionId));
        }

        private boolean fireIntent(Intent intent) {
            if (intent != null && Intent.ACTION_PROCESS_TEXT.equals(intent.getAction())) {
                intent.putExtra(Intent.EXTRA_PROCESS_TEXT, mTextView.getSelectedText());
                mEditor.mPreserveDetachedSelection = true;
                mTextView.startActivityForResult(intent, TextView.PROCESS_TEXT_REQUEST_CODE);
                return true;
            }
            return false;
        }

        private List<ResolveInfo> getSupportedActivities() {
            PackageManager packageManager = mTextView.getContext().getPackageManager();
            return packageManager.queryIntentActivities(createProcessTextIntent(), 0);
        }

        private Intent createProcessTextIntentForResolveInfo(ResolveInfo info) {
            return createProcessTextIntent()
                    .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, !mTextView.isTextEditable())
                    .setClassName(info.activityInfo.packageName, info.activityInfo.name);
        }

        private Intent createProcessTextIntent() {
            return new Intent()
                    .setAction(Intent.ACTION_PROCESS_TEXT)
                    .setType("text/plain");
        }

        private CharSequence getLabel(ResolveInfo resolveInfo) {
            return resolveInfo.loadLabel(mPackageManager);
        }
    }
}
