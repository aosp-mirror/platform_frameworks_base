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
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RectShape;
import android.os.Handler;
import android.os.Message;
import android.text.Editable;
import android.text.InputFilter;
import android.text.Selection;
import android.text.Spannable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.method.MetaKeyKeyListener;
import android.text.method.MovementMethod;
import android.text.method.PasswordTransformationMethod;
import android.text.method.TextKeyListener;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.View.MeasureSpec;
import android.view.ViewConfiguration;
import android.widget.AbsoluteLayout.LayoutParams;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * TextDialog is a specialized version of EditText used by WebView
 * to overlay html textfields (and textareas) to use our standard
 * text editing.
 */
/* package */ class TextDialog extends AutoCompleteTextView {

    private WebView         mWebView;
    private boolean         mSingle;
    private int             mWidthSpec;
    private int             mHeightSpec;
    private int             mNodePointer;
    // FIXME: This is a hack for blocking unmatched key ups, in particular
    // on the enter key.  The method for blocking unmatched key ups prevents
    // the shift key from working properly.
    private boolean         mGotEnterDown;
    // Determines whether we allow calls to requestRectangleOnScreen to
    // propagate.  We only want to scroll if the user is typing.  If the
    // user is simply navigating through a textfield, we do not want to
    // scroll.
    private boolean         mScrollToAccommodateCursor;
    private int             mMaxLength;
    // Keep track of the text before the change so we know whether we actually
    // need to send down the DOM events.
    private String          mPreChange;
    // Array to store the final character added in onTextChanged, so that its
    // KeyEvents may be determined.
    private char[]          mCharacter = new char[1];
    // This is used to reset the length filter when on a textfield
    // with no max length.
    // FIXME: This can be replaced with TextView.NO_FILTERS if that
    // is made public/protected.
    private static final InputFilter[] NO_FILTERS = new InputFilter[0];
    // The time of the last enter down, so we know whether to perform a long
    // press.
    private long            mDownTime;

    private boolean         mTrackballDown = false;
    private static int      LONGPRESS = 1;
    private Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            if (msg.what == LONGPRESS) {
                if (mTrackballDown) {
                    performLongClick();
                    mTrackballDown = false;
                }
            }
        }
    };

    /**
     * Create a new TextDialog.
     * @param   context The Context for this TextDialog.
     * @param   webView The WebView that created this.
     */
    /* package */ TextDialog(Context context, WebView webView) {
        super(context);
        mWebView = webView;
        ShapeDrawable background = new ShapeDrawable(new RectShape());
        Paint shapePaint = background.getPaint();
        shapePaint.setStyle(Paint.Style.STROKE);
        ColorDrawable color = new ColorDrawable(Color.WHITE);
        Drawable[] array = new Drawable[2];
        array[0] = color;
        array[1] = background;
        LayerDrawable layers = new LayerDrawable(array);
        // Hide WebCore's text behind this and allow the WebView
        // to draw its own focusring.
        setBackgroundDrawable(layers);
        // Align the text better with the text behind it, so moving
        // off of the textfield will not appear to move the text.
        setPadding(3, 2, 0, 0);
        mMaxLength = -1;
        // Turn on subpixel text, and turn off kerning, so it better matches
        // the text in webkit.
        TextPaint paint = getPaint();
        int flags = paint.getFlags() | Paint.SUBPIXEL_TEXT_FLAG |
                Paint.ANTI_ALIAS_FLAG & ~Paint.DEV_KERN_TEXT_FLAG;
        paint.setFlags(flags);
        // Set the text color to black, regardless of the theme.  This ensures
        // that other applications that use embedded WebViews will properly
        // display the text in textfields.
        setTextColor(Color.BLACK);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.isSystem()) {
            return super.dispatchKeyEvent(event);
        }
        // Treat ACTION_DOWN and ACTION MULTIPLE the same
        boolean down = event.getAction() != KeyEvent.ACTION_UP;
        int keyCode = event.getKeyCode();
        Spannable text = (Spannable) getText();
        int oldLength = text.length();
        // Normally the delete key's dom events are sent via onTextChanged.
        // However, if the length is zero, the text did not change, so we 
        // go ahead and pass the key down immediately.
        if (KeyEvent.KEYCODE_DEL == keyCode && 0 == oldLength) {
            sendDomEvent(event);
            return true;
        }

        // For single-line textfields, return key should not be handled
        // here.  Instead, the WebView is passed the key up, so it may fire a
        // submit/onClick.
        // Center key should always be passed to a potential onClick
        if ((mSingle && KeyEvent.KEYCODE_ENTER == keyCode)
                || KeyEvent.KEYCODE_DPAD_CENTER == keyCode) {
            if (isPopupShowing()) {
                super.dispatchKeyEvent(event);
                return true;
            }
            if (down) {
                if (event.getRepeatCount() == 0) {
                    mGotEnterDown = true;
                    mDownTime = event.getEventTime();
                    // Send the keydown when the up comes, so that we have
                    // a chance to handle a long press.
                } else if (mGotEnterDown && event.getEventTime() - mDownTime >
                        ViewConfiguration.getLongPressTimeout()) {
                    performLongClick();
                    mGotEnterDown = false;
                }
            } else if (mGotEnterDown) {
                mGotEnterDown = false;
                if (KeyEvent.KEYCODE_DPAD_CENTER == keyCode) {
                    mWebView.shortPressOnTextField();
                    return true;
                }
                sendDomEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
                sendDomEvent(event);
            }
            return true;
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
            // If the TextDialog handled the key it was either an alphanumeric
            // key, a delete, or a movement within the text. All of those are
            // ok to pass to javascript.

            // UNLESS there is a max length determined by the html.  In that
            // case, if the string was already at the max length, an
            // alphanumeric key will be erased by the LengthFilter,
            // so do not pass down to javascript, and instead
            // return true.  If it is an arrow key or a delete key, we can go
            // ahead and pass it down.
            boolean isArrowKey;
            switch(keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    isArrowKey = true;
                    break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    // For multi-line text boxes, newlines and dpad center will
                    // trigger onTextChanged for key down (which will send both
                    // key up and key down) but not key up.
                    mGotEnterDown = true;
                default:
                    isArrowKey = false;
                    break;
            }
            if (maxedOut && !isArrowKey && keyCode != KeyEvent.KEYCODE_DEL) {
                if (oldEnd == oldStart) {
                    // Return true so the key gets dropped.
                    mScrollToAccommodateCursor = true;
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
                    mScrollToAccommodateCursor = true;
                    return true;
                }
            }
            if (isArrowKey) {
                // Arrow key does not change the text, but we still want to send
                // the DOM events.
                sendDomEvent(event);
            }
            mScrollToAccommodateCursor = true;
            return true;
        }
        // FIXME: TextViews return false for up and down key events even though
        // they change the selection. Since we don't want the get out of sync
        // with WebCore's notion of the current selection, reset the selection
        // to what it was before the key event.
        Selection.setSelection(text, oldStart, oldEnd);
        // Ignore the key up event for newlines or dpad center. This prevents
        // multiple newlines in the native textarea.
        if (mGotEnterDown && !down) {
            return true;
        }
        // if it is a navigation key, pass it to WebView
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            // WebView check the trackballtime in onKeyDown to avoid calling
            // native from both trackball and key handling. As this is called 
            // from TextDialog, we always want WebView to check with native. 
            // Reset trackballtime to ensure it.
            mWebView.resetTrackballTime();
            return down ? mWebView.onKeyDown(keyCode, event) : mWebView
                    .onKeyUp(keyCode, event);
        }
        return false;
    }

    /**
     *  Determine whether this TextDialog currently represents the node
     *  represented by ptr.
     *  @param  ptr Pointer to a node to compare to.
     *  @return boolean Whether this TextDialog already represents the node
     *          pointed to by ptr.
     */
    /* package */ boolean isSameTextField(int ptr) {
        return ptr == mNodePointer;
    }

    @Override
    public boolean onPreDraw() {
        if (getLayout() == null) {
            measure(mWidthSpec, mHeightSpec);
        }
        return super.onPreDraw();
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
        // This was simply a delete or a cut, so just delete the 
        // selection.
        if (before > 0 && 0 == count) {
            mWebView.deleteSelection(start, start + before);
            // For this and all changes to the text, update our cache
            updateCachedTextfield();
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
            mWebView.setSelection(start, start + before);
        }
        updateCachedTextfield();
        if (cannotUseKeyEvents) {
            return;
        }
        int length = events.length;
        for (int i = 0; i < length; i++) {
            // We never send modifier keys to native code so don't send them
            // here either.
            if (!KeyEvent.isModifierKey(events[i].getKeyCode())) {
                sendDomEvent(events[i]);
            }
        }
    }
    
    @Override
    public boolean onTrackballEvent(MotionEvent event) {
        if (isPopupShowing()) {
            return super.onTrackballEvent(event);
        }
        int action = event.getAction();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                if (!mTrackballDown) {
                    mTrackballDown = true;
                    mHandler.sendEmptyMessageDelayed(LONGPRESS, 
                            ViewConfiguration.getLongPressTimeout());
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (mTrackballDown) {
                    mWebView.shortPressOnTextField();
                    mTrackballDown = false;
                    mHandler.removeMessages(LONGPRESS);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                mTrackballDown = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                // fall through
        }
        Spannable text = (Spannable) getText();
        MovementMethod move = getMovementMethod();
        if (move != null && getLayout() != null &&
            move.onTrackballEvent(this, text, event)) {
            // Need to pass down the selection, which has changed.
            // FIXME: This should work, but does not, so we set the selection
            // in onTextChanged.
            //int start = Selection.getSelectionStart(text);
            //int end = Selection.getSelectionEnd(text);
            //mWebView.setSelection(start, end);
            return true;
        }
        return false;
    }

    /**
     * Remove this TextDialog from its host WebView, and return
     * focus to the host.
     */
    /* package */ void remove() {
        // hide the soft keyboard when the edit text is out of focus
        InputMethodManager.getInstance(mContext).hideSoftInputFromWindow(
                getWindowToken(), 0);
        mHandler.removeMessages(LONGPRESS);
        mWebView.removeView(this);
        mWebView.requestFocus();
    }

    @Override
    public boolean requestRectangleOnScreen(Rect rectangle) {
        if (mScrollToAccommodateCursor) {
            return super.requestRectangleOnScreen(rectangle);
        }
        return false;
    }
    
    /**
     *  Send the DOM events for the specified event.
     *  @param event    KeyEvent to be translated into a DOM event.
     */
    private void sendDomEvent(KeyEvent event) {
        mWebView.passToJavaScript(getText().toString(), event);
    }

    public void setAdapterCustom(AutoCompleteAdapter adapter) {
        adapter.setTextView(this);
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
     * Determine whether to use the system-wide password disguising method,
     * or to use none.
     * @param   inPassword  True if the textfield is a password field.
     */
    /* package */ void setInPassword(boolean inPassword) {
        PasswordTransformationMethod method;
        if (inPassword) {
            method = PasswordTransformationMethod.getInstance();
        } else {
            method = null;
        }
        setTransformationMethod(method);
        setInputType(inPassword ? EditorInfo.TYPE_TEXT_VARIATION_PASSWORD :
                EditorInfo.TYPE_CLASS_TEXT);
    }

    /* package */ void setMaxLength(int maxLength) {
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
     *  TextDialog represents.
     *  @param  ptr Integer representing the pointer to the node which this
     *          TextDialog represents.
     */
    /* package */ void setNodePointer(int ptr) {
        mNodePointer = ptr;
    }

    /**
     * Determine the position and size of TextDialog, and add it to the
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
        mScrollToAccommodateCursor = false;
        requestFocus();
    }

    /**
     * Set whether this is a single-line textfield or a multi-line textarea.
     * Textfields scroll horizontally, and do not handle the enter key.
     * Textareas behave oppositely.
     */
    public void setSingleLine(boolean single) {
        if (mSingle != single) {
            TextKeyListener.Capitalize cap;
            if (single) {
                cap = TextKeyListener.Capitalize.NONE;
            } else {
                cap = TextKeyListener.Capitalize.SENTENCES;
            }
            setKeyListener(TextKeyListener.getInstance(!single, cap));
            mSingle = single;
            setHorizontallyScrolling(single);
        }
    }

    /**
     * Set the text for this TextDialog, and set the selection to (start, end)
     * @param   text    Text to go into this TextDialog.
     * @param   start   Beginning of the selection.
     * @param   end     End of the selection.
     */
    /* package */ void setText(CharSequence text, int start, int end) {
        mPreChange = text.toString();
        setText(text);
        Spannable span = (Spannable) getText();
        int length = span.length();
        if (end > length) {
            end = length;
        }
        if (start < 0) {
            start = 0;
        } else if (start > length) {
            start = length;
        }
        Selection.setSelection(span, start, end);
    }

    /**
     * Set the text to the new string, but use the old selection, making sure
     * to keep it within the new string.
     * @param   text    The new text to place in the textfield.
     */
    /* package */ void setTextAndKeepSelection(String text) {
        mPreChange = text.toString();
        Editable edit = (Editable) getText();
        edit.replace(0, edit.length(), text);
        updateCachedTextfield();
    }
    
    /**
     *  Update the cache to reflect the current text.
     */
    /* package */ void updateCachedTextfield() {
        mWebView.updateCachedTextfield(getText().toString());
    }
}
