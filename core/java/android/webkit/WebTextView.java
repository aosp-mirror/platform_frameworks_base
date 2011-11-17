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

import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.ResultReceiver;
import android.text.BoringLayout.Metrics;
import android.text.DynamicLayout;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.Layout;
import android.text.Selection;
import android.text.Spannable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.method.MovementMethod;
import android.text.method.Touch;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsoluteLayout.LayoutParams;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.TextView;

import junit.framework.Assert;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;

/**
 * WebTextView is a specialized version of EditText used by WebView
 * to overlay html textfields (and textareas) to use our standard
 * text editing.
 */
/* package */ class WebTextView extends AutoCompleteTextView
        implements AdapterView.OnItemClickListener {

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
    // For keeping track of the fact that the delete key was pressed, so
    // we can simply pass a delete key instead of calling deleteSelection.
    private boolean mGotDelete;
    private int mDelSelStart;
    private int mDelSelEnd;

    // Keep in sync with native constant in
    // external/webkit/WebKit/android/WebCoreSupport/autofill/WebAutoFill.cpp
    /* package */ static final int FORM_NOT_AUTOFILLABLE = -1;

    private boolean mAutoFillable; // Is this textview part of an autofillable form?
    private int mQueryId;
    private boolean mAutoFillProfileIsSet;
    // Used to determine whether onFocusChanged was called as a result of
    // calling remove().
    private boolean mInsideRemove;
    private class MyResultReceiver extends ResultReceiver {
        @Override
        protected void onReceiveResult(int resultCode, Bundle resultData) {
            if (resultCode == InputMethodManager.RESULT_SHOWN
                    && mWebView != null) {
                mWebView.revealSelection();
            }
        }

        /**
         * @param handler
         */
        public MyResultReceiver(Handler handler) {
            super(handler);
        }
    }
    private MyResultReceiver mReceiver;

    // Types used with setType.  Keep in sync with CachedInput.h
    private static final int NORMAL_TEXT_FIELD = 0;
    private static final int TEXT_AREA = 1;
    private static final int PASSWORD = 2;
    private static final int SEARCH = 3;
    private static final int EMAIL = 4;
    private static final int NUMBER = 5;
    private static final int TELEPHONE = 6;
    private static final int URL = 7;

    private static final int AUTOFILL_FORM = 100;
    private Handler mHandler;

    /**
     * Create a new WebTextView.
     * @param   context The Context for this WebTextView.
     * @param   webView The WebView that created this.
     */
    /* package */ WebTextView(Context context, WebView webView, int autoFillQueryId) {
        super(context, null, com.android.internal.R.attr.webTextViewStyle);
        mWebView = webView;
        mMaxLength = -1;
        setAutoFillable(autoFillQueryId);
        // Turn on subpixel text, and turn off kerning, so it better matches
        // the text in webkit.
        TextPaint paint = getPaint();
        int flags = paint.getFlags() & ~Paint.DEV_KERN_TEXT_FLAG
                | Paint.SUBPIXEL_TEXT_FLAG | Paint.DITHER_FLAG;
        paint.setFlags(flags);

        // Set the text color to black, regardless of the theme.  This ensures
        // that other applications that use embedded WebViews will properly
        // display the text in password textfields.
        setTextColor(DebugFlags.DRAW_WEBTEXTVIEW ? Color.RED : Color.BLACK);
        setBackgroundDrawable(DebugFlags.DRAW_WEBTEXTVIEW ? null : new ColorDrawable(Color.WHITE));

        // This helps to align the text better with the text in the web page.
        setIncludeFontPadding(false);

        mHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                case AUTOFILL_FORM:
                    mWebView.autoFillForm(mQueryId);
                    break;
                }
            }
        };
        mReceiver = new MyResultReceiver(mHandler);
    }

    public void setAutoFillable(int queryId) {
        mAutoFillable = mWebView.getSettings().getAutoFillEnabled()
                && (queryId != FORM_NOT_AUTOFILLABLE);
        mQueryId = queryId;
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
        int oldStart = Selection.getSelectionStart(text);
        int oldEnd = Selection.getSelectionEnd(text);
        // Normally the delete key's dom events are sent via onTextChanged.
        // However, if the cursor is at the beginning of the field, which
        // includes the case where it has zero length, then the text is not
        // changed, so send the events immediately.
        if (KeyEvent.KEYCODE_DEL == keyCode) {
            if (oldStart == 0 && oldEnd == 0) {
                sendDomEvent(event);
                return true;
            }
            if (down) {
                mGotDelete = true;
                mDelSelStart = oldStart;
                mDelSelEnd = oldEnd;
            }
        }

        if (mSingle && (KeyEvent.KEYCODE_ENTER == keyCode
                    || KeyEvent.KEYCODE_NUMPAD_ENTER == keyCode)) {
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

        int oldLength = text.length();
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
            if (KeyEvent.KEYCODE_ENTER == keyCode
                        || KeyEvent.KEYCODE_NUMPAD_ENTER == keyCode) {
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

    void ensureLayout() {
        if (getLayout() == null) {
            // Ensure we have a Layout
            measure(mWidthSpec, mHeightSpec);
            LayoutParams params = (LayoutParams) getLayoutParams();
            if (params != null) {
                layout(params.x, params.y, params.x + params.width,
                        params.y + params.height);
            }
        }
    }

    /* package */ ResultReceiver getResultReceiver() { return mReceiver; }

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
     * Ensure that the underlying text field/area is lined up with the WebTextView.
     */
    private void lineUpScroll() {
        Layout layout = getLayout();
        if (mWebView != null && layout != null) {
            if (mSingle) {
                // textfields only need to be lined up horizontally.
                float maxScrollX = layout.getLineRight(0) - getWidth();
                if (DebugFlags.WEB_TEXT_VIEW) {
                    Log.v(LOGTAG, "onTouchEvent x=" + mScrollX + " y="
                            + mScrollY + " maxX=" + maxScrollX);
                }
                mWebView.scrollFocusedTextInputX(maxScrollX > 0 ?
                        mScrollX / maxScrollX : 0);
            } else {
                // textareas only need to be lined up vertically.
                mWebView.scrollFocusedTextInputY(mScrollY);
            }
        }
    }

    @Override
    protected void makeNewLayout(int w, int hintWidth, Metrics boring,
            Metrics hintBoring, int ellipsisWidth, boolean bringIntoView) {
        // Necessary to get a Layout to work with, and to do the other work that
        // makeNewLayout does.
        super.makeNewLayout(w, hintWidth, boring, hintBoring, ellipsisWidth,
                bringIntoView);
        lineUpScroll();
    }

    /**
     * Custom layout which figures out its line spacing.  If -1 is passed in for
     * the height, it will use the ascent and descent from the paint to
     * determine the line spacing.  Otherwise it will use the spacing provided.
     */
    private static class WebTextViewLayout extends DynamicLayout {
        private float mLineHeight;
        private float mDifference;
        public WebTextViewLayout(CharSequence base, CharSequence display,
                TextPaint paint,
                int width, Alignment align,
                float spacingMult, float spacingAdd,
                boolean includepad,
                TextUtils.TruncateAt ellipsize, int ellipsizedWidth,
                float lineHeight) {
            super(base, display, paint, width, align, spacingMult, spacingAdd,
                    includepad, ellipsize, ellipsizedWidth);
            float paintLineHeight = paint.descent() - paint.ascent();
            if (lineHeight == -1f) {
                mLineHeight = paintLineHeight;
                mDifference = 0f;
            } else {
                mLineHeight = lineHeight;
                // Through trial and error, I found this calculation to improve
                // the accuracy of line placement.
                mDifference = (lineHeight - paintLineHeight) / 2;
            }
        }

        @Override
        public int getLineTop(int line) {
            return Math.round(mLineHeight * line - mDifference);
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
    public void onEditorAction(int actionCode) {
        switch (actionCode) {
        case EditorInfo.IME_ACTION_NEXT:
            if (mWebView.nativeMoveCursorToNextTextInput()) {
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
        if (focused) {
            mWebView.setActive(true);
        } else if (!mInsideRemove) {
            mWebView.setActive(false);
        }
        mFromFocusChange = false;
    }

    // AdapterView.OnItemClickListener implementation

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        if (id == 0 && position == 0) {
            // Blank out the text box while we wait for WebCore to fill the form.
            replaceText("");
            WebSettings settings = mWebView.getSettings();
            if (mAutoFillProfileIsSet) {
                // Call a webview method to tell WebCore to autofill the form.
                mWebView.autoFillForm(mQueryId);
            } else {
                // There is no autofill profile setup yet and the user has
                // elected to try and set one up. Call through to the
                // embedder to action that.
                mWebView.getWebChromeClient().setupAutoFill(
                        mHandler.obtainMessage(AUTOFILL_FORM));
            }
        }
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        lineUpScroll();
    }

    @Override
    protected void onSelectionChanged(int selStart, int selEnd) {
        if (!mFromWebKit && !mFromFocusChange && !mFromSetInputType
                && mWebView != null && !mInSetTextAndKeepSelection) {
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
        if (0 == count) {
            if (before > 0) {
                // For this and all changes to the text, update our cache
                updateCachedTextfield();
                if (mGotDelete) {
                    mGotDelete = false;
                    int oldEnd = start + before;
                    if (mDelSelEnd == oldEnd
                            && (mDelSelStart == start
                            || (mDelSelStart == oldEnd && before == 1))) {
                        // If the selection is set up properly before the
                        // delete, send the DOM events.
                        sendDomEvent(new KeyEvent(KeyEvent.ACTION_DOWN,
                                KeyEvent.KEYCODE_DEL));
                        sendDomEvent(new KeyEvent(KeyEvent.ACTION_UP,
                                KeyEvent.KEYCODE_DEL));
                        return;
                    }
                }
                // This was simply a delete or a cut, so just delete the
                // selection.
                mWebView.deleteSelection(start, start + before);
            }
            mGotDelete = false;
            // before should never be negative, so whether it was a cut
            // (handled above), or before is 0, in which case nothing has
            // changed, we should return.
            return;
        }
        // Ensure that this flag gets cleared, since with autocorrect on, a
        // delete key press may have a more complex result than deleting one
        // character or the existing selection, so it will not get cleared
        // above.
        mGotDelete = false;
        // Prefer sending javascript events, so when adding one character,
        // don't replace the unchanged text.
        if (count > 1 && before == count - 1) {
            String replaceButOne =  mPreChange.subSequence(start,
                    start + before).toString();
            String replacedString = s.subSequence(start,
                    start + before).toString();
            if (replaceButOne.equals(replacedString)) {
                // we're just adding one character
                start += before;
                before = 0;
                count = 1;
            }
        }
        mPreChange = postChange;
        // Find the last character being replaced.  If it can be represented by
        // events, we will pass them to native so we can see javascript events.
        // Otherwise, replace the text being changed in the textfield.
        KeyEvent[] events = null;
        if (count == 1) {
            TextUtils.getChars(s, start + count - 1, start + count, mCharacter, 0);
            KeyCharacterMap kmap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
            events = kmap.getEvents(mCharacter);
        }
        boolean useKeyEvents = (events != null);
        if (useKeyEvents) {
            // This corrects the selection which may have been affected by the
            // trackball or auto-correct.
            if (DebugFlags.WEB_TEXT_VIEW) {
                Log.v(LOGTAG, "onTextChanged start=" + start
                        + " start + before=" + (start + before));
            }
            if (!mInSetTextAndKeepSelection) {
                mWebView.setSelection(start, start + before);
            }
            int length = events.length;
            for (int i = 0; i < length; i++) {
                // We never send modifier keys to native code so don't send them
                // here either.
                if (!KeyEvent.isModifierKey(events[i].getKeyCode())) {
                    sendDomEvent(events[i]);
                }
            }
        } else {
            String replace = s.subSequence(start,
                    start + count).toString();
            mWebView.replaceTextfieldText(start, start + before, replace,
                    start + count,
                    start + count);
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
        Spannable text = getText();
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
        InputMethodManager imm = InputMethodManager.getInstance(mContext);
        if (imm.isActive(this)) {
            imm.hideSoftInputFromWindow(getWindowToken(), 0);
        }
        mInsideRemove = true;
        boolean isFocused = hasFocus();
        mWebView.removeView(this);
        if (isFocused) {
            mWebView.requestFocus();
        }
        mInsideRemove = false;
        mHandler.removeCallbacksAndMessages(null);
    }

    @Override
    public boolean requestRectangleOnScreen(Rect rectangle, boolean immediate) {
        // Do nothing, since webkit will put the textfield on screen.
        return true;
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
                    | InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE);
            adapter.setTextView(this);
            if (mAutoFillable) {
                setOnItemClickListener(this);
            } else {
                setOnItemClickListener(null);
            }
            showDropDown();
        } else {
            dismissDropDown();
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
                    .web_text_view_dropdown, entries);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView tv =
                    (TextView) super.getView(position, convertView, parent);
            if (tv != null && mTextView != null) {
                tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, mTextView.getTextSize());
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
        if (mWebView != null) mWebView.incrementTextGeneration();
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
        if (ptr != mNodePointer) {
            mNodePointer = ptr;
            setAdapterCustom(null);
        }
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
        boolean needsUpdate = false;
        if (null == lp) {
            lp = new LayoutParams(width, height, x, y);
        } else {
            if ((lp.x != x) || (lp.y != y) || (lp.width != width)
                    || (lp.height != height)) {
                needsUpdate = true;
                lp.x = x;
                lp.y = y;
                lp.width = width;
                lp.height = height;
            }
        }
        if (getParent() == null) {
            // Insert the view so that it's drawn first (at index 0)
            mWebView.addView(this, 0, lp);
        } else if (needsUpdate) {
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
     * Update the text size according to the size of the focus candidate's text
     * size in mWebView.  Should only be called from mWebView.
     */
    /* package */ void updateTextSize() {
        Assert.assertNotNull("updateTextSize should only be called from "
                + "mWebView, so mWebView should never be null!", mWebView);
        // Note that this is approximately WebView.contentToViewDimension,
        // without being rounded.
        float size = mWebView.nativeFocusCandidateTextSize()
                * mWebView.getScale();
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
    }

    /**
     * Set the text to the new string, but use the old selection, making sure
     * to keep it within the new string.
     * @param   text    The new text to place in the textfield.
     */
    /* package */ void setTextAndKeepSelection(String text) {
        Editable edit = getText();
        mPreChange = text;
        if (edit.toString().equals(text)) {
            return;
        }
        int selStart = Selection.getSelectionStart(edit);
        int selEnd = Selection.getSelectionEnd(edit);
        mInSetTextAndKeepSelection = true;
        edit.replace(0, edit.length(), text);
        int newLength = edit.length();
        if (selStart > newLength) selStart = newLength;
        if (selEnd > newLength) selEnd = newLength;
        Selection.setSelection(edit, selStart, selEnd);
        mInSetTextAndKeepSelection = false;
        InputMethodManager imm = InputMethodManager.peekInstance();
        if (imm != null && imm.isActive(this)) {
            // Since the text has changed, do not allow the IME to replace the
            // existing text as though it were a completion.
            imm.restartInput(this);
        }
        updateCachedTextfield();
    }

    /**
     * Called by WebView.rebuildWebTextView().  Based on the type of the <input>
     * element, set up the WebTextView, its InputType, and IME Options properly.
     * @param type int corresponding to enum "Type" defined in CachedInput.h.
     *              Does not correspond to HTMLInputElement::InputType so this
     *              is unaffected if that changes, and also because that has no
     *              type corresponding to textarea (which is its own tag).
     */
    /* package */ void setType(int type) {
        if (mWebView == null) return;
        boolean single = true;
        int maxLength = -1;
        int inputType = InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT;
        int imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_FULLSCREEN;
        if (!mWebView.nativeFocusCandidateIsSpellcheck()) {
            inputType |= InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        }
        if (TEXT_AREA != type
                && mWebView.nativeFocusCandidateHasNextTextfield()) {
            imeOptions |= EditorInfo.IME_FLAG_NAVIGATE_NEXT;
        }
        switch (type) {
            case NORMAL_TEXT_FIELD:
                imeOptions |= EditorInfo.IME_ACTION_GO;
                break;
            case TEXT_AREA:
                single = false;
                inputType |= InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT;
                imeOptions |= EditorInfo.IME_ACTION_NONE;
                break;
            case PASSWORD:
                inputType |= EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD;
                imeOptions |= EditorInfo.IME_ACTION_GO;
                break;
            case SEARCH:
                imeOptions |= EditorInfo.IME_ACTION_SEARCH;
                break;
            case EMAIL:
                // inputType needs to be overwritten because of the different text variation.
                inputType = InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS;
                imeOptions |= EditorInfo.IME_ACTION_GO;
                break;
            case NUMBER:
                // inputType needs to be overwritten because of the different class.
                inputType = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_NORMAL
                        | InputType.TYPE_NUMBER_FLAG_SIGNED | InputType.TYPE_NUMBER_FLAG_DECIMAL;
                // Number and telephone do not have both a Tab key and an
                // action, so set the action to NEXT
                imeOptions |= EditorInfo.IME_ACTION_NEXT;
                break;
            case TELEPHONE:
                // inputType needs to be overwritten because of the different class.
                inputType = InputType.TYPE_CLASS_PHONE;
                imeOptions |= EditorInfo.IME_ACTION_NEXT;
                break;
            case URL:
                // TYPE_TEXT_VARIATION_URI prevents Tab key from showing, so
                // exclude it for now.
                imeOptions |= EditorInfo.IME_ACTION_GO;
                break;
            default:
                imeOptions |= EditorInfo.IME_ACTION_GO;
                break;
        }
        setHint(null);
        setThreshold(1);
        boolean autoComplete = false;
        if (single) {
            mWebView.requestLabel(mWebView.nativeFocusCandidateFramePointer(),
                    mNodePointer);
            maxLength = mWebView.nativeFocusCandidateMaxLength();
            autoComplete = mWebView.nativeFocusCandidateIsAutoComplete();
            if (type != PASSWORD && (mAutoFillable || autoComplete)) {
                String name = mWebView.nativeFocusCandidateName();
                if (name != null && name.length() > 0) {
                    mWebView.requestFormData(name, mNodePointer, mAutoFillable,
                            autoComplete);
                }
            }
        }
        mSingle = single;
        setMaxLength(maxLength);
        setHorizontallyScrolling(single);
        setInputType(inputType);
        clearComposingText();
        setImeOptions(imeOptions);
        setVisibility(VISIBLE);
        if (!autoComplete) {
            setAdapterCustom(null);
        }
    }

    /**
     *  Update the cache to reflect the current text.
     */
    /* package */ void updateCachedTextfield() {
        mWebView.updateCachedTextfield(getText().toString());
    }

    /* package */ void setAutoFillProfileIsSet(boolean autoFillProfileIsSet) {
        mAutoFillProfileIsSet = autoFillProfileIsSet;
    }

    static String urlForAutoCompleteData(String urlString) {
        // Remove any fragment or query string.
        URL url = null;
        try {
            url = new URL(urlString);
        } catch (MalformedURLException e) {
            Log.e(LOGTAG, "Unable to parse URL "+url);
        }

        return url != null ? url.getProtocol() + "://" + url.getHost() + url.getPath() : null;
    }

    public void setGravityForRtl(boolean rtl) {
        int gravity = rtl ? Gravity.RIGHT : Gravity.LEFT;
        gravity |= mSingle ? Gravity.CENTER_VERTICAL : Gravity.TOP;
        setGravity(gravity);
    }

}
