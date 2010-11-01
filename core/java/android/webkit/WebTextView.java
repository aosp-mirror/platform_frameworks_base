/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.webkit;

import com.android.internal.widget.EditableInputConnection;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.Layout;
import android.text.Selection;
import android.text.Spannable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.method.MovementMethod;
import android.text.method.Touch;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputConnection;
import android.widget.AbsoluteLayout.LayoutParams;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * WebTextView is a specialized version of EditText used by WebView
 * to overlay html textfields (and textareas) to use our standard
 * text editing.
 */
/* package */ class WebTextView extends AutoCompleteTextView {

    static final String LOGTAG = "webtextview";

    private WebView         mWebView;
    private boolean         mSingle;
    private int             mWidthSpec;
    private int             mHeightSpec;
    private int             mNodePointer;
    // FIXME: This is a hack for blocking unmatched key ups, in particular
    // on the enter key.  The method for blocking unmatched key ups prevents
    // the shift key from working properly.
    private boolean         mGotEnterDown;
    private int             mMaxLength;
    // Keep track of the text before the change so we know whether we actually
    // need to send down the DOM events.
    private String          mPreChange;
    private Drawable        mBackground;
    // Variables for keeping track of the touch down, to send to the WebView
    // when a drag starts
    private float           mDragStartX;
    private float           mDragStartY;
    private long            mDragStartTime;
    private boolean         mDragSent;
    // True if the most recent drag event has caused either the TextView to
    // scroll or the web page to scroll.  Gets reset after a touch down.
    private boolean         mScrolled;
    // Whether or not a selection change was generated from webkit.  If it was,
    // we do not need to pass the selection back to webkit.
    private boolean         mFromWebKit;
    // Whether or not a selection change was generated from the WebTextView
    // gaining focus.  If it is, we do not want to pass it to webkit.  This
    // selection comes from the MovementMethod, but we behave differently.  If
    // WebTextView gained focus from a touch, webkit will determine the
    // selection.
    private boolean         mFromFocusChange;
    // Whether or not a selection change was generated from setInputType.  We
    // do not want to pass this change to webkit.
    private boolean         mFromSetInputType;
    private boolean         mGotTouchDown;
    // Keep track of whether a long press has happened.  Only meaningful after
    // an ACTION_DOWN MotionEvent
    private boolean         mHasPerformedLongClick;
    private boolean         mInSetTextAndKeepSelection;
    // Array to store the final character added in onTextChanged, so that its
    // KeyEvents may be determined.
    private char[]          mCharacter = new char[1];
    // This is used to reset the length filter when on a textfield
    // with no max length.
    // FIXME: This can be replaced with TextView.NO_FILTERS if that
    // is made public/protected.
    private static final InputFilter[] NO_FILTERS = new InputFilter[0];

    /**
     * Create a new WebTextView.
     * @param   context The Context for this WebTextView.
     * @param   webView The WebView that created this.
     */
    /* package */ WebTextView(Context context, WebView webView) {
        super(context, null, com.android.internal.R.attr.webTextViewStyle);
        mWebView = webView;
        mMaxLength = -1;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.isSystem()) {
            return super.dispatchKeyEvent(event);
        }
        // Treat ACTION_DOWN and ACTION MULTIPLE the same
        boolean down = event.getAction() != KeyEvent.ACTION_UP;
        int keyCode = event.getKeyCode();

        boolean isArrowKey = false;
        switch(keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (!mWebView.nativeCursorMatchesFocus()) {
                    return down ? mWebView.onKeyDown(keyCode, event) : mWebView
                            .onKeyUp(keyCode, event);

                }
                isArrowKey = true;
                break;
        }

        if (KeyEvent.KEYCODE_TAB == keyCode) {
            if (down) {
                onEditorAction(EditorInfo.IME_ACTION_NEXT);
            }
            return true;
        }
        Spannable text = (Spannable) getText();
        int oldLength = text.length();
        // Normally the delete key's dom events are sent via onTextChanged.
        // However, if the length is zero, the text did not change, so we
        // go ahead and pass the key down immediately.
        if (KeyEvent.KEYCODE_DEL == keyCode && 0 == oldLength) {
            sendDomEvent(event);
            return true;
        }

        if ((mSingle && KeyEvent.KEYCODE_ENTER == keyCode)) {
            if (isPopupShowing()) {
                return super.dispatchKeyEvent(event);
            }
            if (!down) {
                // Hide the keyboard, since the user has just submitted this
                // form.  The submission happens thanks to the two calls
                // to sendDomEvent.
                InputMethodManager.getInstance(mContext)
                        .hideSoftInputFromWindow(getWindowToken(), 0);
                sendDomEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
                sendDomEvent(event);
            }
            return super.dispatchKeyEvent(event);
        } else if (KeyEvent.KEYCODE_DPAD_CENTER == keyCode) {
            // Note that this handles center key and trackball.
            if (isPopupShowing()) {
                return super.dispatchKeyEvent(event);
            }
            if (!mWebView.nativeCursorMatchesFocus()) {
                return down ? mWebView.onKeyDown(keyCode, event) : mWebView
                        .onKeyUp(keyCode, event);
            }
            // Center key should be passed to a potential onClick
            if (!down) {
                mWebView.centerKeyPressOnTextField();
            }
            // Pass to super to handle longpress.
            return super.dispatchKeyEvent(event);
        }

        // Ensure there is a layout so arrow keys are handled properly.
        if (getLayout() == null) {
            measure(mWidthSpec, mHeightSpec);
        }
        int oldStart = Selection.getSelectionStart(text);
        int oldEnd = Selection.getSelectionEnd(text);

        boolean maxedOut = mMaxLength != -1 && oldLength == mMaxLength;
        // If we are at max length, and there is a selection rather than a
        // cursor, we need to store the text to compare later, since the key
        // may have changed the string.
        String oldText;
        if (maxedOut && oldEnd != oldStart) {
            oldText = text.toString();
        } else {
            oldText = "";
        }
        if (super.dispatchKeyEvent(event)) {
            // If the WebTextView handled the key it was either an alphanumeric
            // key, a delete, or a movement within the text. All of those are
            // ok to pass to javascript.

            // UNLESS there is a max length determined by the html.  In that
            // case, if the string was already at the max length, an
            // alphanumeric key will be erased by the LengthFilter,
            // so do not pass down to javascript, and instead
            // return true.  If it is an arrow key or a delete key, we can go
            // ahead and pass it down.
            if (KeyEvent.KEYCODE_ENTER == keyCode) {
                // For multi-line text boxes, newlines will
                // trigger onTextChanged for key down (which will send both
                // key up and key down) but not key up.
                mGotEnterDown = true;
            }
            if (maxedOut && !isArrowKey && keyCode != KeyEvent.KEYCODE_DEL) {
                if (oldEnd == oldStart) {
                    // Return true so the key gets dropped.
                    return true;
                } else if (!oldText.equals(getText().toString())) {
                    // FIXME: This makes the text work properly, but it
                    // does not pass down the key event, so it may not
                    // work for a textfield that has the type of
                    // behavior of GoogleSuggest.  That said, it is
                    // unlikely that a site would combine the two in
                    // one textfield.
                    Spannable span = (Spannable) getText();
                    int newStart = Selection.getSelectionStart(span);
                    int newEnd = Selection.getSelectionEnd(span);
                    mWebView.replaceTextfieldText(0, oldLength, span.toString(),
                            newStart, newEnd);
                    return true;
                }
            }
            /* FIXME:
             * In theory, we would like to send the events for the arrow keys.
             * However, the TextView can arbitrarily change the selection (i.e.
             * long press followed by using the trackball).  Therefore, we keep
             * in sync with the TextView via onSelectionChanged.  If we also
             * send the DOM event, we lose the correct selection.
            if (isArrowKey) {
                // Arrow key does not change the text, but we still want to send
                // the DOM events.
                sendDomEvent(event);
            }
             */
            return true;
        }
        // Ignore the key up event for newlines. This prevents
        // multiple newlines in the native textarea.
        if (mGotEnterDown && !down) {
            return true;
        }
        // if it is a navigation key, pass it to WebView
        if (isArrowKey) {
            // WebView check the trackballtime in onKeyDown to avoid calling
            // native from both trackball and key handling. As this is called
            // from WebTextView, we always want WebView to check with native.
            // Reset trackballtime to ensure it.
            mWebView.resetTrackballTime();
            return down ? mWebView.onKeyDown(keyCode, event) : mWebView
                    .onKeyUp(keyCode, event);
        }
        return false;
    }

    /**
     *  Determine whether this WebTextView currently represents the node
     *  represented by ptr.
     *  @param  ptr Pointer to a node to compare to.
     *  @return boolean Whether this WebTextView already represents the node
     *          pointed to by ptr.
     */
    /* package */ boolean isSameTextField(int ptr) {
        return ptr == mNodePointer;
    }

    /**
     * Ensure that the underlying textfield is lined up with the WebTextView.
     */
    private void lineUpScroll() {
        Layout layout = getLayout();
        if (mWebView != null && layout != null) {
            float maxScrollX = Touch.getMaxScrollX(this, layout, mScrollY);
            if (DebugFlags.WEB_TEXT_VIEW) {
                Log.v(LOGTAG, "onTouchEvent x=" + mScrollX + " y="
                        + mScrollY + " maxX=" + maxScrollX);
            }
            mWebView.scrollFocusedTextInput(maxScrollX > 0 ?
                    mScrollX / maxScrollX : 0, mScrollY);
        }
    }

    @Override public InputConnection onCreateInputConnection(
            EditorInfo outAttrs) {
        InputConnection connection = super.onCreateInputConnection(outAttrs);
        if (mWebView != null) {
            // Use the name of the textfield + the url.  Use backslash as an
            // arbitrary separator.
            outAttrs.fieldName = mWebView.nativeFocusCandidateName() + "\\"
                    + mWebView.getUrl();
        }
        return connection;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // onDraw should only be called for password fields.  If WebTextView is
        // still drawing, but is no longer corresponding to a password field,
        // remove it.
        if (mWebView == null || !mWebView.nativeFocusCandidateIsPassword()
                || !isSameTextField(mWebView.nativeFocusCandidatePointer())) {
            // Although calling remove() would seem to make more sense here,
            // changing it to not be a password field will make it not draw.
            // Other code will make sure that it is removed completely, but this
            // way the user will not see it.
            setInPassword(false);
        } else {
            super.onDraw(canvas);
        }
    }

    public void onDrawSubstitute() {
      updateCursorControllerPositions();
    }

    @Override
    public void onEditorAction(int actionCode) {
        switch (actionCode) {
        case EditorInfo.IME_ACTION_NEXT:
            if (mWebView.nativeMoveCursorToNextTextInput()) {
                // Since the cursor will no longer be in the same place as the
                // focus, set the focus controller back to inactive
                mWebView.setFocusControllerInactive();
                // Preemptively rebuild the WebTextView, so that the action will
                // be set properly.
                mWebView.rebuildWebTextView();
                setDefaultSelection();
                mWebView.invalidate();
            }
            break;
        case EditorInfo.IME_ACTION_DONE:
            super.onEditorAction(actionCode);
            break;
        case EditorInfo.IME_ACTION_GO:
        case EditorInfo.IME_ACTION_SEARCH:
            // Send an enter and hide the soft keyboard
            InputMethodManager.getInstance(mContext)
                    .hideSoftInputFromWindow(getWindowToken(), 0);
            sendDomEvent(new KeyEvent(KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_ENTER));
            sendDomEvent(new KeyEvent(KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_ENTER));

        default:
            break;
        }
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction,
            Rect previouslyFocusedRect) {
        mFromFocusChange = true;
        super.onFocusChanged(focused, direction, previouslyFocusedRect);
        mFromFocusChange = false;
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        lineUpScroll();
    }

    @Override
    protected void onSelectionChanged(int selStart, int selEnd) {
        if (mInSetTextAndKeepSelection) return;
        // This code is copied from TextView.onDraw().  That code does not get
        // executed, however, because the WebTextView does not draw, allowing
        // webkit's drawing to show through.
        InputMethodManager imm = InputMethodManager.peekInstance();
        if (imm != null && imm.isActive(this)) {
            Spannable sp = (Spannable) getText();
            int candStart = EditableInputConnection.getComposingSpanStart(sp);
            int candEnd = EditableInputConnection.getComposingSpanEnd(sp);
            imm.updateSelection(this, selStart, selEnd, candStart, candEnd);
        }
        if (!mFromWebKit && !mFromFocusChange && !mFromSetInputType
                && mWebView != null) {
            if (DebugFlags.WEB_TEXT_VIEW) {
                Log.v(LOGTAG, "onSelectionChanged selStart=" + selStart
                        + " selEnd=" + selEnd);
            }
            mWebView.setSelection(selStart, selEnd);
            lineUpScroll();
        }
    }

    @Override
    protected void onTextChanged(CharSequence s,int start,int before,int count){
        super.onTextChanged(s, start, before, count);
        String postChange = s.toString();
        // Prevent calls to setText from invoking onTextChanged (since this will
        // mean we are on a different textfield).  Also prevent the change when
        // going from a textfield with a string of text to one with a smaller
        // limit on text length from registering the onTextChanged event.
        if (mPreChange == null || mPreChange.equals(postChange) ||
                (mMaxLength > -1 && mPreChange.length() > mMaxLength &&
                mPreChange.substring(0, mMaxLength).equals(postChange))) {
            return;
        }
        mPreChange = postChange;
        if (0 == count) {
            if (before > 0) {
                // This was simply a delete or a cut, so just delete the
                // selection.
                mWebView.deleteSelection(start, start + before);
                // For this and all changes to the text, update our cache
                updateCachedTextfield();
            }
            // before should never be negative, so whether it was a cut
            // (handled above), or before is 0, in which case nothing has
            // changed, we should return.
            return;
        }
        // Find the last character being replaced.  If it can be represented by
        // events, we will pass them to native (after replacing the beginning
        // of the changed text), so we can see javascript events.
        // Otherwise, replace the text being changed (including the last
        // character) in the textfield.
        TextUtils.getChars(s, start + count - 1, start + count, mCharacter, 0);
        KeyCharacterMap kmap =
                KeyCharacterMap.load(KeyCharacterMap.BUILT_IN_KEYBOARD);
        KeyEvent[] events = kmap.getEvents(mCharacter);
        boolean cannotUseKeyEvents = null == events;
        int charactersFromKeyEvents = cannotUseKeyEvents ? 0 : 1;
        if (count > 1 || cannotUseKeyEvents) {
            String replace = s.subSequence(start,
                    start + count - charactersFromKeyEvents).toString();
            mWebView.replaceTextfieldText(start, start + before, replace,
                    start + count - charactersFromKeyEvents,
                    start + count - charactersFromKeyEvents);
        } else {
            // This corrects the selection which may have been affected by the
            // trackball or auto-correct.
            if (DebugFlags.WEB_TEXT_VIEW) {
                Log.v(LOGTAG, "onTextChanged start=" + start
                        + " start + before=" + (start + before));
            }
            if (!mInSetTextAndKeepSelection) {
                mWebView.setSelection(start, start + before);
            }
        }
        if (!cannotUseKeyEvents) {
            int length = events.length;
            for (int i = 0; i < length; i++) {
                // We never send modifier keys to native code so don't send them
                // here either.
                if (!KeyEvent.isModifierKey(events[i].getKeyCode())) {
                    sendDomEvent(events[i]);
                }
            }
        }
        updateCachedTextfield();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
        case MotionEvent.ACTION_DOWN:
            super.onTouchEvent(event);
            // This event may be the start of a drag, so store it to pass to the
            // WebView if it is.
            mDragStartX = event.getX();
            mDragStartY = event.getY();
            mDragStartTime = event.getEventTime();
            mDragSent = false;
            mScrolled = false;
            mGotTouchDown = true;
            mHasPerformedLongClick = false;
            break;
        case MotionEvent.ACTION_MOVE:
            if (mHasPerformedLongClick) {
                mGotTouchDown = false;
                return false;
            }
            int slop = ViewConfiguration.get(mContext).getScaledTouchSlop();
            Spannable buffer = getText();
            int initialScrollX = Touch.getInitialScrollX(this, buffer);
            int initialScrollY = Touch.getInitialScrollY(this, buffer);
            super.onTouchEvent(event);
            int dx = Math.abs(mScrollX - initialScrollX);
            int dy = Math.abs(mScrollY - initialScrollY);
            // Use a smaller slop when checking to see if we've moved far enough
            // to scroll the text, because experimentally, slop has shown to be
            // to big for the case of a small textfield.
            int smallerSlop = slop/2;
            if (dx > smallerSlop || dy > smallerSlop) {
                // Scrolling is handled in onScrollChanged.
                mScrolled = true;
                cancelLongPress();
                return true;
            }
            if (Math.abs((int) event.getX() - mDragStartX) < slop
                    && Math.abs((int) event.getY() - mDragStartY) < slop) {
                // If the user has not scrolled further than slop, we should not
                // send the drag.  Instead, do nothing, and when the user lifts
                // their finger, we will change the selection.
                return true;
            }
            if (mWebView != null) {
                // Only want to set the initial state once.
                if (!mDragSent) {
                    mWebView.initiateTextFieldDrag(mDragStartX, mDragStartY,
                            mDragStartTime);
                    mDragSent = true;
                }
                boolean scrolled = mWebView.textFieldDrag(event);
                if (scrolled) {
                    mScrolled = true;
                    cancelLongPress();
                    return true;
                }
            }
            return false;
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_CANCEL:
            super.onTouchEvent(event);
            if (mHasPerformedLongClick) {
                mGotTouchDown = false;
                return false;
            }
            if (!mScrolled) {
                // If the page scrolled, or the TextView scrolled, we do not
                // want to change the selection
                cancelLongPress();
                if (mGotTouchDown && mWebView != null) {
                    mWebView.touchUpOnTextField(event);
                }
            }
            // Necessary for the WebView to reset its state
            if (mWebView != null && mDragSent) {
                mWebView.onTouchEvent(event);
            }
            mGotTouchDown = false;
            break;
        default:
            break;
        }
        return true;
    }

    @Override
    public boolean onTrackballEvent(MotionEvent event) {
        if (isPopupShowing()) {
            return super.onTrackballEvent(event);
        }
        if (event.getAction() != MotionEvent.ACTION_MOVE) {
            return false;
        }
        // If the Cursor is not on the text input, webview should handle the
        // trackball
        if (!mWebView.nativeCursorMatchesFocus()) {
            return mWebView.onTrackballEvent(event);
        }
        Spannable text = (Spannable) getText();
        MovementMethod move = getMovementMethod();
        if (move != null && getLayout() != null &&
            move.onTrackballEvent(this, text, event)) {
            // Selection is changed in onSelectionChanged
            return true;
        }
        return false;
    }

    @Override
    public boolean performLongClick() {
        mHasPerformedLongClick = true;
        return super.performLongClick();
    }

    /**
     * Remove this WebTextView from its host WebView, and return
     * focus to the host.
     */
    /* package */ void remove() {
        // hide the soft keyboard when the edit text is out of focus
        InputMethodManager.getInstance(mContext).hideSoftInputFromWindow(
                getWindowToken(), 0);
        mWebView.removeView(this);
        mWebView.requestFocus();
    }

    /* package */ void bringIntoView() {
        if (getLayout() != null) {
            bringPointIntoView(Selection.getSelectionEnd(getText()));
        }
    }

    /**
     *  Send the DOM events for the specified event.
     *  @param event    KeyEvent to be translated into a DOM event.
     */
    private void sendDomEvent(KeyEvent event) {
        mWebView.passToJavaScript(getText().toString(), event);
    }

    /**
     *  Always use this instead of setAdapter, as this has features specific to
     *  the WebTextView.
     */
    public void setAdapterCustom(AutoCompleteAdapter adapter) {
        if (adapter != null) {
            setInputType(getInputType()
                    | EditorInfo.TYPE_TEXT_FLAG_AUTO_COMPLETE);
            adapter.setTextView(this);
        }
        super.setAdapter(adapter);
    }

    /**
     *  This is a special version of ArrayAdapter which changes its text size
     *  to match the text size of its host TextView.
     */
    public static class AutoCompleteAdapter extends ArrayAdapter<String> {
        private TextView mTextView;

        public AutoCompleteAdapter(Context context, ArrayList<String> entries) {
            super(context, com.android.internal.R.layout
                    .search_dropdown_item_1line, entries);
        }

        /**
         * {@inheritDoc}
         */
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView tv =
                    (TextView) super.getView(position, convertView, parent);
            if (tv != null && mTextView != null) {
                tv.setTextSize(mTextView.getTextSize());
            }
            return tv;
        }

        /**
         * Set the TextView so we can match its text size.
         */
        private void setTextView(TextView tv) {
            mTextView = tv;
        }
    }

    /**
     * Sets the selection when the user clicks on a textfield or textarea with
     * the trackball or center key, or starts typing into it without clicking on
     * it.
     */
    /* package */ void setDefaultSelection() {
        Spannable text = (Spannable) getText();
        int selection = mSingle ? text.length() : 0;
        if (Selection.getSelectionStart(text) == selection
                && Selection.getSelectionEnd(text) == selection) {
            // The selection of the UI copy is set correctly, but the
            // WebTextView still needs to inform the webkit thread to set the
            // selection.  Normally that is done in onSelectionChanged, but
            // onSelectionChanged will not be called because the UI copy is not
            // changing.  (This can happen when the WebTextView takes focus.
            // That onSelectionChanged was blocked because the selection set
            // when focusing is not necessarily the desirable selection for
            // WebTextView.)
            if (mWebView != null) {
                mWebView.setSelection(selection, selection);
            }
        } else {
            Selection.setSelection(text, selection, selection);
        }
    }

    /**
     * Determine whether to use the system-wide password disguising method,
     * or to use none.
     * @param   inPassword  True if the textfield is a password field.
     */
    /* package */ void setInPassword(boolean inPassword) {
        if (inPassword) {
            setInputType(EditorInfo.TYPE_CLASS_TEXT | EditorInfo.
                    TYPE_TEXT_VARIATION_PASSWORD);
            createBackground();
        }
        // For password fields, draw the WebTextView.  For others, just show
        // webkit's drawing.
        setWillNotDraw(!inPassword);
        setBackgroundDrawable(inPassword ? mBackground : null);
    }

    /**
     * Private class used for the background of a password textfield.
     */
    private static class OutlineDrawable extends Drawable {
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            Paint paint = new Paint();
            paint.setAntiAlias(true);
            // Draw the background.
            paint.setColor(Color.WHITE);
            canvas.drawRect(bounds, paint);
            // Draw the outline.
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(Color.BLACK);
            canvas.drawRect(bounds, paint);
        }
        // Always want it to be opaque.
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }
        // These are needed because they are abstract in Drawable.
        public void setAlpha(int alpha) { }
        public void setColorFilter(ColorFilter cf) { }
    }

    /**
     * Create a background for the WebTextView and set up the paint for drawing
     * the text.  This way, we can see the password transformation of the
     * system, which (optionally) shows the actual text before changing to dots.
     * The background is necessary to hide the webkit-drawn text beneath.
     */
    private void createBackground() {
        if (mBackground != null) {
            return;
        }
        mBackground = new OutlineDrawable();

        setGravity(Gravity.CENTER_VERTICAL);
        // Turn on subpixel text, and turn off kerning, so it better matches
        // the text in webkit.
        TextPaint paint = getPaint();
        int flags = paint.getFlags() | Paint.SUBPIXEL_TEXT_FLAG |
                Paint.ANTI_ALIAS_FLAG & ~Paint.DEV_KERN_TEXT_FLAG;
        paint.setFlags(flags);
        // Set the text color to black, regardless of the theme.  This ensures
        // that other applications that use embedded WebViews will properly
        // display the text in password textfields.
        setTextColor(Color.BLACK);
    }

    @Override
    public void setInputType(int type) {
        mFromSetInputType = true;
        super.setInputType(type);
        mFromSetInputType = false;
    }

    private void setMaxLength(int maxLength) {
        mMaxLength = maxLength;
        if (-1 == maxLength) {
            setFilters(NO_FILTERS);
        } else {
            setFilters(new InputFilter[] {
                new InputFilter.LengthFilter(maxLength) });
        }
    }

    /**
     *  Set the pointer for this node so it can be determined which node this
     *  WebTextView represents.
     *  @param  ptr Integer representing the pointer to the node which this
     *          WebTextView represents.
     */
    /* package */ void setNodePointer(int ptr) {
        mNodePointer = ptr;
    }

    /**
     * Determine the position and size of WebTextView, and add it to the
     * WebView's view heirarchy.  All parameters are presumed to be in
     * view coordinates.  Also requests Focus and sets the cursor to not
     * request to be in view.
     * @param x         x-position of the textfield.
     * @param y         y-position of the textfield.
     * @param width     width of the textfield.
     * @param height    height of the textfield.
     */
    /* package */ void setRect(int x, int y, int width, int height) {
        LayoutParams lp = (LayoutParams) getLayoutParams();
        if (null == lp) {
            lp = new LayoutParams(width, height, x, y);
        } else {
            lp.x = x;
            lp.y = y;
            lp.width = width;
            lp.height = height;
        }
        if (getParent() == null) {
            mWebView.addView(this, lp);
        } else {
            setLayoutParams(lp);
        }
        // Set up a measure spec so a layout can always be recreated.
        mWidthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
        mHeightSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);
    }

    /**
     * Set the selection, and disable our onSelectionChanged action.
     */
    /* package */ void setSelectionFromWebKit(int start, int end) {
        if (start < 0 || end < 0) return;
        Spannable text = (Spannable) getText();
        int length = text.length();
        if (start > length || end > length) return;
        mFromWebKit = true;
        Selection.setSelection(text, start, end);
        mFromWebKit = false;
    }

    /**
     * Set the text to the new string, but use the old selection, making sure
     * to keep it within the new string.
     * @param   text    The new text to place in the textfield.
     */
    /* package */ void setTextAndKeepSelection(String text) {
        mPreChange = text.toString();
        Editable edit = (Editable) getText();
        int selStart = Selection.getSelectionStart(edit);
        int selEnd = Selection.getSelectionEnd(edit);
        mInSetTextAndKeepSelection = true;
        edit.replace(0, edit.length(), text);
        int newLength = edit.length();
        if (selStart > newLength) selStart = newLength;
        if (selEnd > newLength) selEnd = newLength;
        Selection.setSelection(edit, selStart, selEnd);
        mInSetTextAndKeepSelection = false;
        updateCachedTextfield();
    }

    /**
     * Called by WebView.rebuildWebTextView().  Based on the type of the <input>
     * element, set up the WebTextView, its InputType, and IME Options properly.
     * @param type int corresponding to enum "type" defined in WebView.cpp.
     *              Does not correspond to HTMLInputElement::InputType so this
     *              is unaffected if that changes, and also because that has no
     *              type corresponding to textarea (which is its own tag).
     */
    /* package */ void setType(int type) {
        if (mWebView == null) return;
        boolean single = true;
        boolean inPassword = false;
        int maxLength = -1;
        int inputType = EditorInfo.TYPE_CLASS_TEXT;
        if (mWebView.nativeFocusCandidateHasNextTextfield()) {
            inputType |= EditorInfo.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT;
        }
        int imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_FULLSCREEN;
        switch (type) {
            case 0: // NORMAL_TEXT_FIELD
                imeOptions |= EditorInfo.IME_ACTION_GO;
                break;
            case 1: // TEXT_AREA
                single = false;
                inputType = EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE
                        | EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES
                        | EditorInfo.TYPE_CLASS_TEXT
                        | EditorInfo.TYPE_TEXT_FLAG_AUTO_CORRECT;
                imeOptions |= EditorInfo.IME_ACTION_NONE;
                break;
            case 2: // PASSWORD
                inPassword = true;
                imeOptions |= EditorInfo.IME_ACTION_GO;
                break;
            case 3: // SEARCH
                imeOptions |= EditorInfo.IME_ACTION_SEARCH;
                break;
            case 4: // EMAIL
                // TYPE_TEXT_VARIATION_WEB_EDIT_TEXT prevents EMAIL_ADDRESS
                // from working, so exclude it for now.
                imeOptions |= EditorInfo.IME_ACTION_GO;
                break;
            case 5: // NUMBER
                inputType |= EditorInfo.TYPE_CLASS_NUMBER;
                // Number and telephone do not have both a Tab key and an
                // action, so set the action to NEXT
                imeOptions |= EditorInfo.IME_ACTION_NEXT;
                break;
            case 6: // TELEPHONE
                inputType |= EditorInfo.TYPE_CLASS_PHONE;
                imeOptions |= EditorInfo.IME_ACTION_NEXT;
                break;
            case 7: // URL
                // TYPE_TEXT_VARIATION_URI prevents Tab key from showing, so
                // exclude it for now.
                imeOptions |= EditorInfo.IME_ACTION_GO;
                break;
            default:
                imeOptions |= EditorInfo.IME_ACTION_GO;
                break;
        }
        setHint(null);
        if (single) {
            mWebView.requestLabel(mWebView.nativeFocusCandidateFramePointer(),
                    mNodePointer);
            maxLength = mWebView.nativeFocusCandidateMaxLength();
            if (type != 2 /* PASSWORD */) {
                String name = mWebView.nativeFocusCandidateName();
                if (name != null && name.length() > 0) {
                    mWebView.requestFormData(name, mNodePointer);
                }
            }
        }
        mSingle = single;
        setMaxLength(maxLength);
        setHorizontallyScrolling(single);
        setInputType(inputType);
        setImeOptions(imeOptions);
        setInPassword(inPassword);
        AutoCompleteAdapter adapter = null;
        setAdapterCustom(adapter);
    }

    /**
     *  Update the cache to reflect the current text.
     */
    /* package */ void updateCachedTextfield() {
        mWebView.updateCachedTextfield(getText().toString());
    }

    @Override
    public boolean requestRectangleOnScreen(Rect rectangle) {
        // don't scroll while in zoom animation. When it is done, we will adjust
        // the WebTextView if it is in editing mode.
        if (!mWebView.inAnimateZoom()) {
            return super.requestRectangleOnScreen(rectangle);
        }
        return false;
    }
}
