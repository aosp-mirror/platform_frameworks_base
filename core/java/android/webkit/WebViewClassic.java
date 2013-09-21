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

package android.webkit;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.animation.ObjectAnimator;
import android.annotation.Widget;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.DataSetObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.DrawFilter;
import android.graphics.Paint;
import android.graphics.PaintFlagsDrawFilter;
import android.graphics.Picture;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.RegionIterator;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.net.Proxy;
import android.net.ProxyProperties;
import android.net.Uri;
import android.net.http.SslCertificate;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.print.PrintDocumentAdapter;
import android.security.KeyChain;
import android.text.Editable;
import android.text.InputType;
import android.text.Selection;
import android.text.TextUtils;
import android.util.AndroidRuntimeException;
import android.util.DisplayMetrics;
import android.util.EventLog;
import android.util.Log;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.HardwareCanvas;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.SoundEffectConstants;
import android.view.VelocityTracker;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewRootImpl;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView.HitTestResult;
import android.webkit.WebView.PictureListener;
import android.webkit.WebViewCore.DrawData;
import android.webkit.WebViewCore.EventHub;
import android.webkit.WebViewCore.TextFieldInitData;
import android.webkit.WebViewCore.TextSelectionData;
import android.webkit.WebViewCore.WebKitHitTest;
import android.widget.AbsoluteLayout;
import android.widget.Adapter;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.CheckedTextView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.OverScroller;
import android.widget.PopupWindow;
import android.widget.Scroller;
import android.widget.TextView;
import android.widget.Toast;

import junit.framework.Assert;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Vector;

/**
 * Implements a backend provider for the {@link WebView} public API.
 * @hide
 */
// TODO: Check if any WebView published API methods are called from within here, and if so
// we should bounce the call out via the proxy to enable any sub-class to override it.
@Widget
@SuppressWarnings("deprecation")
public final class WebViewClassic implements WebViewProvider, WebViewProvider.ScrollDelegate,
        WebViewProvider.ViewDelegate {
    /**
     * InputConnection used for ContentEditable. This captures changes
     * to the text and sends them either as key strokes or text changes.
     */
    class WebViewInputConnection extends BaseInputConnection {
        // Used for mapping characters to keys typed.
        private KeyCharacterMap mKeyCharacterMap;
        private boolean mIsKeySentByMe;
        private int mInputType;
        private int mImeOptions;
        private String mHint;
        private int mMaxLength;
        private boolean mIsAutoFillable;
        private boolean mIsAutoCompleteEnabled;
        private String mName;
        private int mBatchLevel;

        public WebViewInputConnection() {
            super(mWebView, true);
        }

        public void setAutoFillable(int queryId) {
            mIsAutoFillable = getSettings().getAutoFillEnabled()
                    && (queryId != WebTextView.FORM_NOT_AUTOFILLABLE);
            int variation = mInputType & EditorInfo.TYPE_MASK_VARIATION;
            if (variation != EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD
                    && (mIsAutoFillable || mIsAutoCompleteEnabled)) {
                if (mName != null && mName.length() > 0) {
                    requestFormData(mName, mFieldPointer, mIsAutoFillable,
                            mIsAutoCompleteEnabled);
                }
            }
        }

        @Override
        public boolean beginBatchEdit() {
            if (mBatchLevel == 0) {
                beginTextBatch();
            }
            mBatchLevel++;
            return false;
        }

        @Override
        public boolean endBatchEdit() {
            mBatchLevel--;
            if (mBatchLevel == 0) {
                commitTextBatch();
            }
            return false;
        }

        public boolean getIsAutoFillable() {
            return mIsAutoFillable;
        }

        @Override
        public boolean sendKeyEvent(KeyEvent event) {
            // Some IMEs send key events directly using sendKeyEvents.
            // WebViewInputConnection should treat these as text changes.
            if (!mIsKeySentByMe) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    if (event.getKeyCode() == KeyEvent.KEYCODE_DEL) {
                        return deleteSurroundingText(1, 0);
                    } else if (event.getKeyCode() == KeyEvent.KEYCODE_FORWARD_DEL) {
                        return deleteSurroundingText(0, 1);
                    } else if (event.getUnicodeChar() != 0){
                        String newComposingText =
                                Character.toString((char)event.getUnicodeChar());
                        return commitText(newComposingText, 1);
                    }
                } else if (event.getAction() == KeyEvent.ACTION_DOWN &&
                        (event.getKeyCode() == KeyEvent.KEYCODE_DEL
                        || event.getKeyCode() == KeyEvent.KEYCODE_FORWARD_DEL
                        || event.getUnicodeChar() != 0)) {
                    return true; // only act on action_down
                }
            }
            return super.sendKeyEvent(event);
        }

        public void setTextAndKeepSelection(CharSequence text) {
            Editable editable = getEditable();
            int selectionStart = Selection.getSelectionStart(editable);
            int selectionEnd = Selection.getSelectionEnd(editable);
            text = limitReplaceTextByMaxLength(text, editable.length());
            editable.replace(0, editable.length(), text);
            restartInput();
            // Keep the previous selection.
            selectionStart = Math.min(selectionStart, editable.length());
            selectionEnd = Math.min(selectionEnd, editable.length());
            setSelection(selectionStart, selectionEnd);
            finishComposingText();
        }

        public void replaceSelection(CharSequence text) {
            Editable editable = getEditable();
            int selectionStart = Selection.getSelectionStart(editable);
            int selectionEnd = Selection.getSelectionEnd(editable);
            text = limitReplaceTextByMaxLength(text, selectionEnd - selectionStart);
            setNewText(selectionStart, selectionEnd, text);
            editable.replace(selectionStart, selectionEnd, text);
            restartInput();
            // Move caret to the end of the new text
            int newCaret = selectionStart + text.length();
            setSelection(newCaret, newCaret);
        }

        @Override
        public boolean setComposingText(CharSequence text, int newCursorPosition) {
            Editable editable = getEditable();
            int start = getComposingSpanStart(editable);
            int end = getComposingSpanEnd(editable);
            if (start < 0 || end < 0) {
                start = Selection.getSelectionStart(editable);
                end = Selection.getSelectionEnd(editable);
            }
            if (end < start) {
                int temp = end;
                end = start;
                start = temp;
            }
            CharSequence limitedText = limitReplaceTextByMaxLength(text, end - start);
            setNewText(start, end, limitedText);
            if (limitedText != text) {
                newCursorPosition -= text.length() - limitedText.length();
            }
            super.setComposingText(limitedText, newCursorPosition);
            updateSelection();
            if (limitedText != text) {
                int lastCaret = start + limitedText.length();
                finishComposingText();
                setSelection(lastCaret, lastCaret);
            }
            return true;
        }

        @Override
        public boolean commitText(CharSequence text, int newCursorPosition) {
            setComposingText(text, newCursorPosition);
            finishComposingText();
            return true;
        }

        @Override
        public boolean deleteSurroundingText(int leftLength, int rightLength) {
            // This code is from BaseInputConnection#deleteSurroundText.
            // We have to delete the same text in webkit.
            Editable content = getEditable();
            int a = Selection.getSelectionStart(content);
            int b = Selection.getSelectionEnd(content);

            if (a > b) {
                int tmp = a;
                a = b;
                b = tmp;
            }

            int ca = getComposingSpanStart(content);
            int cb = getComposingSpanEnd(content);
            if (cb < ca) {
                int tmp = ca;
                ca = cb;
                cb = tmp;
            }
            if (ca != -1 && cb != -1) {
                if (ca < a) a = ca;
                if (cb > b) b = cb;
            }

            int endDelete = Math.min(content.length(), b + rightLength);
            if (endDelete > b) {
                setNewText(b, endDelete, "");
            }
            int startDelete = Math.max(0, a - leftLength);
            if (startDelete < a) {
                setNewText(startDelete, a, "");
            }
            return super.deleteSurroundingText(leftLength, rightLength);
        }

        @Override
        public boolean performEditorAction(int editorAction) {

            boolean handled = true;
            switch (editorAction) {
            case EditorInfo.IME_ACTION_NEXT:
                mWebView.requestFocus(View.FOCUS_FORWARD);
                break;
            case EditorInfo.IME_ACTION_PREVIOUS:
                mWebView.requestFocus(View.FOCUS_BACKWARD);
                break;
            case EditorInfo.IME_ACTION_DONE:
                WebViewClassic.this.hideSoftKeyboard();
                break;
            case EditorInfo.IME_ACTION_GO:
            case EditorInfo.IME_ACTION_SEARCH:
                WebViewClassic.this.hideSoftKeyboard();
                String text = getEditable().toString();
                passToJavaScript(text, new KeyEvent(KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_ENTER));
                passToJavaScript(text, new KeyEvent(KeyEvent.ACTION_UP,
                        KeyEvent.KEYCODE_ENTER));
                break;

            default:
                handled = super.performEditorAction(editorAction);
                break;
            }

            return handled;
        }

        public void initEditorInfo(WebViewCore.TextFieldInitData initData) {
            int type = initData.mType;
            int inputType = InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT;
            int imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
                    | EditorInfo.IME_FLAG_NO_FULLSCREEN;
            if (!initData.mIsSpellCheckEnabled) {
                inputType |= InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
            }
            if (WebTextView.TEXT_AREA != type) {
                if (initData.mIsTextFieldNext) {
                    imeOptions |= EditorInfo.IME_FLAG_NAVIGATE_NEXT;
                }
                if (initData.mIsTextFieldPrev) {
                    imeOptions |= EditorInfo.IME_FLAG_NAVIGATE_PREVIOUS;
                }
            }
            int action = EditorInfo.IME_ACTION_GO;
            switch (type) {
                case WebTextView.NORMAL_TEXT_FIELD:
                    break;
                case WebTextView.TEXT_AREA:
                    inputType |= InputType.TYPE_TEXT_FLAG_MULTI_LINE
                            | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                            | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT;
                    action = EditorInfo.IME_ACTION_NONE;
                    break;
                case WebTextView.PASSWORD:
                    inputType |= EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD;
                    break;
                case WebTextView.SEARCH:
                    action = EditorInfo.IME_ACTION_SEARCH;
                    break;
                case WebTextView.EMAIL:
                    // inputType needs to be overwritten because of the different text variation.
                    inputType = InputType.TYPE_CLASS_TEXT
                            | InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS;
                    break;
                case WebTextView.NUMBER:
                    // inputType needs to be overwritten because of the different class.
                    inputType = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_NORMAL
                            | InputType.TYPE_NUMBER_FLAG_SIGNED | InputType.TYPE_NUMBER_FLAG_DECIMAL;
                    // Number and telephone do not have both a Tab key and an
                    // action, so set the action to NEXT
                    break;
                case WebTextView.TELEPHONE:
                    // inputType needs to be overwritten because of the different class.
                    inputType = InputType.TYPE_CLASS_PHONE;
                    break;
                case WebTextView.URL:
                    // TYPE_TEXT_VARIATION_URI prevents Tab key from showing, so
                    // exclude it for now.
                    inputType |= InputType.TYPE_TEXT_VARIATION_URI;
                    break;
                default:
                    break;
            }
            imeOptions |= action;
            mHint = initData.mLabel;
            mInputType = inputType;
            mImeOptions = imeOptions;
            mMaxLength = initData.mMaxLength;
            mIsAutoCompleteEnabled = initData.mIsAutoCompleteEnabled;
            mName = initData.mName;
            mAutoCompletePopup.clearAdapter();
        }

        public void setupEditorInfo(EditorInfo outAttrs) {
            outAttrs.inputType = mInputType;
            outAttrs.imeOptions = mImeOptions;
            outAttrs.hintText = mHint;
            outAttrs.initialCapsMode = getCursorCapsMode(InputType.TYPE_CLASS_TEXT);

            Editable editable = getEditable();
            int selectionStart = Selection.getSelectionStart(editable);
            int selectionEnd = Selection.getSelectionEnd(editable);
            if (selectionStart < 0 || selectionEnd < 0) {
                selectionStart = editable.length();
                selectionEnd = selectionStart;
            }
            outAttrs.initialSelStart = selectionStart;
            outAttrs.initialSelEnd = selectionEnd;
        }

        @Override
        public boolean setSelection(int start, int end) {
            boolean result = super.setSelection(start, end);
            updateSelection();
            return result;
        }

        @Override
        public boolean setComposingRegion(int start, int end) {
            boolean result = super.setComposingRegion(start, end);
            updateSelection();
            return result;
        }

        /**
         * Send the selection and composing spans to the IME.
         */
        private void updateSelection() {
            Editable editable = getEditable();
            int selectionStart = Selection.getSelectionStart(editable);
            int selectionEnd = Selection.getSelectionEnd(editable);
            int composingStart = getComposingSpanStart(editable);
            int composingEnd = getComposingSpanEnd(editable);
            InputMethodManager imm = InputMethodManager.peekInstance();
            if (imm != null) {
                imm.updateSelection(mWebView, selectionStart, selectionEnd,
                        composingStart, composingEnd);
            }
        }

        /**
         * Sends a text change to webkit indirectly. If it is a single-
         * character add or delete, it sends it as a key stroke. If it cannot
         * be represented as a key stroke, it sends it as a field change.
         * @param start The start offset (inclusive) of the text being changed.
         * @param end The end offset (exclusive) of the text being changed.
         * @param text The new text to replace the changed text.
         */
        private void setNewText(int start, int end, CharSequence text) {
            mIsKeySentByMe = true;
            Editable editable = getEditable();
            CharSequence original = editable.subSequence(start, end);
            boolean isCharacterAdd = false;
            boolean isCharacterDelete = false;
            int textLength = text.length();
            int originalLength = original.length();
            int selectionStart = Selection.getSelectionStart(editable);
            int selectionEnd = Selection.getSelectionEnd(editable);
            if (selectionStart == selectionEnd) {
                if (textLength > originalLength) {
                    isCharacterAdd = (textLength == originalLength + 1)
                            && TextUtils.regionMatches(text, 0, original, 0,
                                    originalLength);
                } else if (originalLength > textLength) {
                    isCharacterDelete = (textLength == originalLength - 1)
                            && TextUtils.regionMatches(text, 0, original, 0,
                                    textLength);
                }
            }
            if (isCharacterAdd) {
                sendCharacter(text.charAt(textLength - 1));
            } else if (isCharacterDelete) {
                sendKey(KeyEvent.KEYCODE_DEL);
            } else if ((textLength != originalLength) ||
                    !TextUtils.regionMatches(text, 0, original, 0,
                            textLength)) {
                // Send a message so that key strokes and text replacement
                // do not come out of order.
                Message replaceMessage = mPrivateHandler.obtainMessage(
                        REPLACE_TEXT, start,  end, text.toString());
                mPrivateHandler.sendMessage(replaceMessage);
            }
            if (mAutoCompletePopup != null) {
                StringBuilder newText = new StringBuilder();
                newText.append(editable.subSequence(0, start));
                newText.append(text);
                newText.append(editable.subSequence(end, editable.length()));
                mAutoCompletePopup.setText(newText.toString());
            }
            mIsKeySentByMe = false;
        }

        /**
         * Send a single character to the WebView as a key down and up event.
         * @param c The character to be sent.
         */
        private void sendCharacter(char c) {
            if (mKeyCharacterMap == null) {
                mKeyCharacterMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
            }
            char[] chars = new char[1];
            chars[0] = c;
            KeyEvent[] events = mKeyCharacterMap.getEvents(chars);
            if (events != null) {
                for (KeyEvent event : events) {
                    sendKeyEvent(event);
                }
            } else {
                Message msg = mPrivateHandler.obtainMessage(KEY_PRESS, (int) c, 0);
                mPrivateHandler.sendMessage(msg);
            }
        }

        /**
         * Send a key event for a specific key code, not a standard
         * unicode character.
         * @param keyCode The key code to send.
         */
        private void sendKey(int keyCode) {
            long eventTime = SystemClock.uptimeMillis();
            sendKeyEvent(new KeyEvent(eventTime, eventTime,
                    KeyEvent.ACTION_DOWN, keyCode, 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                    KeyEvent.FLAG_SOFT_KEYBOARD));
            sendKeyEvent(new KeyEvent(SystemClock.uptimeMillis(), eventTime,
                    KeyEvent.ACTION_UP, keyCode, 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                    KeyEvent.FLAG_SOFT_KEYBOARD));
        }

        private CharSequence limitReplaceTextByMaxLength(CharSequence text,
                int numReplaced) {
            if (mMaxLength > 0) {
                Editable editable = getEditable();
                int maxReplace = mMaxLength - editable.length() + numReplaced;
                if (maxReplace < text.length()) {
                    maxReplace = Math.max(maxReplace, 0);
                    // New length is greater than the maximum. trim it down.
                    text = text.subSequence(0, maxReplace);
                }
            }
            return text;
        }

        private void restartInput() {
            InputMethodManager imm = InputMethodManager.peekInstance();
            if (imm != null) {
                // Since the text has changed, do not allow the IME to replace the
                // existing text as though it were a completion.
                imm.restartInput(mWebView);
            }
        }
    }

    private class PastePopupWindow extends PopupWindow implements View.OnClickListener {
        private ViewGroup mContentView;
        private TextView mPasteTextView;

        public PastePopupWindow() {
            super(mContext, null,
                    com.android.internal.R.attr.textSelectHandleWindowStyle);
            setClippingEnabled(true);
            LinearLayout linearLayout = new LinearLayout(mContext);
            linearLayout.setOrientation(LinearLayout.HORIZONTAL);
            mContentView = linearLayout;
            mContentView.setBackgroundResource(
                    com.android.internal.R.drawable.text_edit_paste_window);

            LayoutInflater inflater = (LayoutInflater)mContext.
                    getSystemService(Context.LAYOUT_INFLATER_SERVICE);

            ViewGroup.LayoutParams wrapContent = new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);

            mPasteTextView = (TextView) inflater.inflate(
                    com.android.internal.R.layout.text_edit_action_popup_text, null);
            mPasteTextView.setLayoutParams(wrapContent);
            mContentView.addView(mPasteTextView);
            mPasteTextView.setText(com.android.internal.R.string.paste);
            mPasteTextView.setOnClickListener(this);
            this.setContentView(mContentView);
        }

        public void show(Point cursorBottom, Point cursorTop,
                int windowLeft, int windowTop) {
            measureContent();

            int width = mContentView.getMeasuredWidth();
            int height = mContentView.getMeasuredHeight();
            int y = cursorTop.y - height;
            int x = cursorTop.x - (width / 2);
            if (y < windowTop) {
                // There's not enough room vertically, move it below the
                // handle.
                ensureSelectionHandles();
                y = cursorBottom.y + mSelectHandleCenter.getIntrinsicHeight();
                x = cursorBottom.x - (width / 2);
            }
            if (x < windowLeft) {
                x = windowLeft;
            }
            if (!isShowing()) {
                showAtLocation(mWebView, Gravity.NO_GRAVITY, x, y);
            }
            update(x, y, width, height);
        }

        public void hide() {
            dismiss();
        }

        @Override
        public void onClick(View view) {
            pasteFromClipboard();
            selectionDone();
        }

        protected void measureContent() {
            final DisplayMetrics displayMetrics = mContext.getResources().getDisplayMetrics();
            mContentView.measure(
                    View.MeasureSpec.makeMeasureSpec(displayMetrics.widthPixels,
                            View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(displayMetrics.heightPixels,
                            View.MeasureSpec.AT_MOST));
        }
    }

    // if AUTO_REDRAW_HACK is true, then the CALL key will toggle redrawing
    // the screen all-the-time. Good for profiling our drawing code
    static private final boolean AUTO_REDRAW_HACK = false;

    // The rate at which edit text is scrolled in content pixels per millisecond
    static private final float TEXT_SCROLL_RATE = 0.01f;

    // The presumed scroll rate for the first scroll of edit text
    static private final long TEXT_SCROLL_FIRST_SCROLL_MS = 16;

    // Buffer pixels of the caret rectangle when moving edit text into view
    // after resize.
    static private final int EDIT_RECT_BUFFER = 10;

    static private final long SELECTION_HANDLE_ANIMATION_MS = 150;

    // true means redraw the screen all-the-time. Only with AUTO_REDRAW_HACK
    private boolean mAutoRedraw;

    // Reference to the AlertDialog displayed by InvokeListBox.
    // It's used to dismiss the dialog in destroy if not done before.
    private AlertDialog mListBoxDialog = null;

    // Reference to the save password dialog so it can be dimissed in
    // destroy if not done before.
    private AlertDialog mSavePasswordDialog = null;

    static final String LOGTAG = "webview";

    private ZoomManager mZoomManager;

    private final Rect mInvScreenRect = new Rect();
    private final Rect mScreenRect = new Rect();
    private final RectF mVisibleContentRect = new RectF();
    private boolean mIsWebViewVisible = true;
    WebViewInputConnection mInputConnection = null;
    private int mFieldPointer;
    private PastePopupWindow mPasteWindow;
    private AutoCompletePopup mAutoCompletePopup;
    Rect mEditTextContentBounds = new Rect();
    Rect mEditTextContent = new Rect();
    int mEditTextLayerId;
    boolean mIsEditingText = false;
    ArrayList<Message> mBatchedTextChanges = new ArrayList<Message>();
    boolean mIsBatchingTextChanges = false;
    private long mLastEditScroll = 0;

    private static class OnTrimMemoryListener implements ComponentCallbacks2 {
        private static OnTrimMemoryListener sInstance = null;

        static void init(Context c) {
            if (sInstance == null) {
                sInstance = new OnTrimMemoryListener(c.getApplicationContext());
            }
        }

        private OnTrimMemoryListener(Context c) {
            c.registerComponentCallbacks(this);
        }

        @Override
        public void onConfigurationChanged(Configuration newConfig) {
            // Ignore
        }

        @Override
        public void onLowMemory() {
            // Ignore
        }

        @Override
        public void onTrimMemory(int level) {
            if (DebugFlags.WEB_VIEW) {
                Log.d("WebView", "onTrimMemory: " + level);
            }
            // When framework reset EGL context during high memory pressure, all
            // the existing GL resources for the html5 video will be destroyed
            // at native side.
            // Here we just need to clean up the Surface Texture which is static.
            if (level > TRIM_MEMORY_UI_HIDDEN) {
                HTML5VideoInline.cleanupSurfaceTexture();
                HTML5VideoView.release();
            }
            WebViewClassic.nativeOnTrimMemory(level);
        }
    }

    // A final CallbackProxy shared by WebViewCore and BrowserFrame.
    private CallbackProxy mCallbackProxy;

    private WebViewDatabaseClassic mDatabase;

    // SSL certificate for the main top-level page (if secure)
    private SslCertificate mCertificate;

    // Native WebView pointer that is 0 until the native object has been
    // created.
    private int mNativeClass;
    // This would be final but it needs to be set to null when the WebView is
    // destroyed.
    private WebViewCore mWebViewCore;
    // Handler for dispatching UI messages.
    /* package */ final Handler mPrivateHandler = new PrivateHandler();
    // Used to ignore changes to webkit text that arrives to the UI side after
    // more key events.
    private int mTextGeneration;

    /* package */ void incrementTextGeneration() { mTextGeneration++; }

    // Used by WebViewCore to create child views.
    /* package */ ViewManager mViewManager;

    // Used to display in full screen mode
    PluginFullScreenHolder mFullScreenHolder;

    /**
     * Position of the last touch event in pixels.
     * Use integer to prevent loss of dragging delta calculation accuracy;
     * which was done in float and converted to integer, and resulted in gradual
     * and compounding touch position and view dragging mismatch.
     */
    private int mLastTouchX;
    private int mLastTouchY;
    private int mStartTouchX;
    private int mStartTouchY;
    private float mAverageAngle;

    /**
     * Time of the last touch event.
     */
    private long mLastTouchTime;

    /**
     * Time of the last time sending touch event to WebViewCore
     */
    private long mLastSentTouchTime;

    /**
     * The minimum elapsed time before sending another ACTION_MOVE event to
     * WebViewCore. This really should be tuned for each type of the devices.
     * For example in Google Map api test case, it takes Dream device at least
     * 150ms to do a full cycle in the WebViewCore by processing a touch event,
     * triggering the layout and drawing the picture. While the same process
     * takes 60+ms on the current high speed device. If we make
     * TOUCH_SENT_INTERVAL too small, there will be multiple touch events sent
     * to WebViewCore queue and the real layout and draw events will be pushed
     * to further, which slows down the refresh rate. Choose 50 to favor the
     * current high speed devices. For Dream like devices, 100 is a better
     * choice. Maybe make this in the buildspec later.
     * (Update 12/14/2010: changed to 0 since current device should be able to
     * handle the raw events and Map team voted to have the raw events too.
     */
    private static final int TOUCH_SENT_INTERVAL = 0;
    private int mCurrentTouchInterval = TOUCH_SENT_INTERVAL;

    /**
     * Helper class to get velocity for fling
     */
    VelocityTracker mVelocityTracker;
    private int mMaximumFling;
    private float mLastVelocity;
    private float mLastVelX;
    private float mLastVelY;

    // The id of the native layer being scrolled.
    private int mCurrentScrollingLayerId;
    private Rect mScrollingLayerRect = new Rect();

    // only trigger accelerated fling if the new velocity is at least
    // MINIMUM_VELOCITY_RATIO_FOR_ACCELERATION times of the previous velocity
    private static final float MINIMUM_VELOCITY_RATIO_FOR_ACCELERATION = 0.2f;

    /**
     * Touch mode
     * TODO: Some of this is now unnecessary as it is handled by
     * WebInputTouchDispatcher (such as click, long press, and double tap).
     */
    private int mTouchMode = TOUCH_DONE_MODE;
    private static final int TOUCH_INIT_MODE = 1;
    private static final int TOUCH_DRAG_START_MODE = 2;
    private static final int TOUCH_DRAG_MODE = 3;
    private static final int TOUCH_SHORTPRESS_START_MODE = 4;
    private static final int TOUCH_SHORTPRESS_MODE = 5;
    private static final int TOUCH_DOUBLE_TAP_MODE = 6;
    private static final int TOUCH_DONE_MODE = 7;
    private static final int TOUCH_PINCH_DRAG = 8;
    private static final int TOUCH_DRAG_LAYER_MODE = 9;
    private static final int TOUCH_DRAG_TEXT_MODE = 10;

    // true when the touch movement exceeds the slop
    private boolean mConfirmMove;
    private boolean mTouchInEditText;

    // Whether or not to draw the cursor ring.
    private boolean mDrawCursorRing = true;

    // true if onPause has been called (and not onResume)
    private boolean mIsPaused;

    private HitTestResult mInitialHitTestResult;
    private WebKitHitTest mFocusedNode;

    /**
     * Customizable constant
     */
    // pre-computed square of ViewConfiguration.getScaledTouchSlop()
    private int mTouchSlopSquare;
    // pre-computed square of ViewConfiguration.getScaledDoubleTapSlop()
    private int mDoubleTapSlopSquare;
    // pre-computed density adjusted navigation slop
    private int mNavSlop;
    // This should be ViewConfiguration.getTapTimeout()
    // But system time out is 100ms, which is too short for the browser.
    // In the browser, if it switches out of tap too soon, jump tap won't work.
    // In addition, a double tap on a trackpad will always have a duration of
    // 300ms, so this value must be at least that (otherwise we will timeout the
    // first tap and convert it to a long press).
    private static final int TAP_TIMEOUT = 300;
    // This should be ViewConfiguration.getLongPressTimeout()
    // But system time out is 500ms, which is too short for the browser.
    // With a short timeout, it's difficult to treat trigger a short press.
    private static final int LONG_PRESS_TIMEOUT = 1000;
    // needed to avoid flinging after a pause of no movement
    private static final int MIN_FLING_TIME = 250;
    // draw unfiltered after drag is held without movement
    private static final int MOTIONLESS_TIME = 100;
    // The amount of content to overlap between two screens when going through
    // pages with the space bar, in pixels.
    private static final int PAGE_SCROLL_OVERLAP = 24;

    /**
     * These prevent calling requestLayout if either dimension is fixed. This
     * depends on the layout parameters and the measure specs.
     */
    boolean mWidthCanMeasure;
    boolean mHeightCanMeasure;

    // Remember the last dimensions we sent to the native side so we can avoid
    // sending the same dimensions more than once.
    int mLastWidthSent;
    int mLastHeightSent;
    // Since view height sent to webkit could be fixed to avoid relayout, this
    // value records the last sent actual view height.
    int mLastActualHeightSent;

    private int mContentWidth;   // cache of value from WebViewCore
    private int mContentHeight;  // cache of value from WebViewCore

    // Need to have the separate control for horizontal and vertical scrollbar
    // style than the View's single scrollbar style
    private boolean mOverlayHorizontalScrollbar = true;
    private boolean mOverlayVerticalScrollbar = false;

    // our standard speed. this way small distances will be traversed in less
    // time than large distances, but we cap the duration, so that very large
    // distances won't take too long to get there.
    private static final int STD_SPEED = 480;  // pixels per second
    // time for the longest scroll animation
    private static final int MAX_DURATION = 750;   // milliseconds

    // Used by OverScrollGlow
    OverScroller mScroller;
    Scroller mEditTextScroller;

    private boolean mInOverScrollMode = false;
    private static Paint mOverScrollBackground;
    private static Paint mOverScrollBorder;

    private boolean mWrapContent;
    private static final int MOTIONLESS_FALSE           = 0;
    private static final int MOTIONLESS_PENDING         = 1;
    private static final int MOTIONLESS_TRUE            = 2;
    private static final int MOTIONLESS_IGNORE          = 3;
    private int mHeldMotionless;

    // Lazily-instantiated instance for injecting accessibility.
    private AccessibilityInjector mAccessibilityInjector;

    /**
     * How long the caret handle will last without being touched.
     */
    private static final long CARET_HANDLE_STAMINA_MS = 3000;

    private Drawable mSelectHandleLeft;
    private Drawable mSelectHandleRight;
    private Drawable mSelectHandleCenter;
    private Point mSelectOffset;
    private Point mSelectCursorBase = new Point();
    private Rect mSelectHandleBaseBounds = new Rect();
    private int mSelectCursorBaseLayerId;
    private QuadF mSelectCursorBaseTextQuad = new QuadF();
    private Point mSelectCursorExtent = new Point();
    private Rect mSelectHandleExtentBounds = new Rect();
    private int mSelectCursorExtentLayerId;
    private QuadF mSelectCursorExtentTextQuad = new QuadF();
    private Point mSelectDraggingCursor;
    private QuadF mSelectDraggingTextQuad;
    private boolean mIsCaretSelection;
    static final int HANDLE_ID_BASE = 0;
    static final int HANDLE_ID_EXTENT = 1;

    // the color used to highlight the touch rectangles
    static final int HIGHLIGHT_COLOR = 0x6633b5e5;
    // the region indicating where the user touched on the screen
    private Region mTouchHighlightRegion = new Region();
    // the paint for the touch highlight
    private Paint mTouchHightlightPaint = new Paint();
    // debug only
    private static final boolean DEBUG_TOUCH_HIGHLIGHT = true;
    private static final int TOUCH_HIGHLIGHT_ELAPSE_TIME = 2000;
    private Paint mTouchCrossHairColor;
    private int mTouchHighlightX;
    private int mTouchHighlightY;
    private boolean mShowTapHighlight;

    // Basically this proxy is used to tell the Video to update layer tree at
    // SetBaseLayer time and to pause when WebView paused.
    private HTML5VideoViewProxy mHTML5VideoViewProxy;

    // If we are using a set picture, don't send view updates to webkit
    private boolean mBlockWebkitViewMessages = false;

    // cached value used to determine if we need to switch drawing models
    private boolean mHardwareAccelSkia = false;

    /*
     * Private message ids
     */
    private static final int REMEMBER_PASSWORD          = 1;
    private static final int NEVER_REMEMBER_PASSWORD    = 2;
    private static final int SWITCH_TO_SHORTPRESS       = 3;
    private static final int SWITCH_TO_LONGPRESS        = 4;
    private static final int RELEASE_SINGLE_TAP         = 5;
    private static final int REQUEST_FORM_DATA          = 6;
    private static final int DRAG_HELD_MOTIONLESS       = 8;
    private static final int PREVENT_DEFAULT_TIMEOUT    = 10;
    private static final int SCROLL_SELECT_TEXT         = 11;


    private static final int FIRST_PRIVATE_MSG_ID = REMEMBER_PASSWORD;
    private static final int LAST_PRIVATE_MSG_ID = SCROLL_SELECT_TEXT;

    /*
     * Package message ids
     */
    static final int SCROLL_TO_MSG_ID                   = 101;
    static final int NEW_PICTURE_MSG_ID                 = 105;
    static final int WEBCORE_INITIALIZED_MSG_ID         = 107;
    static final int UPDATE_TEXTFIELD_TEXT_MSG_ID       = 108;
    static final int UPDATE_ZOOM_RANGE                  = 109;
    static final int TAKE_FOCUS                         = 110;
    static final int CLEAR_TEXT_ENTRY                   = 111;
    static final int UPDATE_TEXT_SELECTION_MSG_ID       = 112;
    static final int SHOW_RECT_MSG_ID                   = 113;
    static final int LONG_PRESS_CENTER                  = 114;
    static final int PREVENT_TOUCH_ID                   = 115;
    static final int WEBCORE_NEED_TOUCH_EVENTS          = 116;
    // obj=Rect in doc coordinates
    static final int INVAL_RECT_MSG_ID                  = 117;
    static final int REQUEST_KEYBOARD                   = 118;
    static final int SHOW_FULLSCREEN                    = 120;
    static final int HIDE_FULLSCREEN                    = 121;
    static final int UPDATE_MATCH_COUNT                 = 126;
    static final int CENTER_FIT_RECT                    = 127;
    static final int SET_SCROLLBAR_MODES                = 129;
    static final int HIT_TEST_RESULT                    = 130;
    static final int SAVE_WEBARCHIVE_FINISHED           = 131;
    static final int SET_AUTOFILLABLE                   = 132;
    static final int AUTOFILL_COMPLETE                  = 133;
    static final int SCREEN_ON                          = 134;
    static final int UPDATE_ZOOM_DENSITY                = 135;
    static final int EXIT_FULLSCREEN_VIDEO              = 136;
    static final int COPY_TO_CLIPBOARD                  = 137;
    static final int INIT_EDIT_FIELD                    = 138;
    static final int REPLACE_TEXT                       = 139;
    static final int CLEAR_CARET_HANDLE                 = 140;
    static final int KEY_PRESS                          = 141;
    static final int RELOCATE_AUTO_COMPLETE_POPUP       = 142;
    static final int FOCUS_NODE_CHANGED                 = 143;
    static final int AUTOFILL_FORM                      = 144;
    static final int SCROLL_EDIT_TEXT                   = 145;
    static final int EDIT_TEXT_SIZE_CHANGED             = 146;
    static final int SHOW_CARET_HANDLE                  = 147;
    static final int UPDATE_CONTENT_BOUNDS              = 148;
    static final int SCROLL_HANDLE_INTO_VIEW            = 149;

    private static final int FIRST_PACKAGE_MSG_ID = SCROLL_TO_MSG_ID;
    private static final int LAST_PACKAGE_MSG_ID = HIT_TEST_RESULT;

    static final String[] HandlerPrivateDebugString = {
        "REMEMBER_PASSWORD", //              = 1;
        "NEVER_REMEMBER_PASSWORD", //        = 2;
        "SWITCH_TO_SHORTPRESS", //           = 3;
        "SWITCH_TO_LONGPRESS", //            = 4;
        "RELEASE_SINGLE_TAP", //             = 5;
        "REQUEST_FORM_DATA", //              = 6;
        "RESUME_WEBCORE_PRIORITY", //        = 7;
        "DRAG_HELD_MOTIONLESS", //           = 8;
        "", //             = 9;
        "PREVENT_DEFAULT_TIMEOUT", //        = 10;
        "SCROLL_SELECT_TEXT" //              = 11;
    };

    static final String[] HandlerPackageDebugString = {
        "SCROLL_TO_MSG_ID", //               = 101;
        "102", //                            = 102;
        "103", //                            = 103;
        "104", //                            = 104;
        "NEW_PICTURE_MSG_ID", //             = 105;
        "UPDATE_TEXT_ENTRY_MSG_ID", //       = 106;
        "WEBCORE_INITIALIZED_MSG_ID", //     = 107;
        "UPDATE_TEXTFIELD_TEXT_MSG_ID", //   = 108;
        "UPDATE_ZOOM_RANGE", //              = 109;
        "UNHANDLED_NAV_KEY", //              = 110;
        "CLEAR_TEXT_ENTRY", //               = 111;
        "UPDATE_TEXT_SELECTION_MSG_ID", //   = 112;
        "SHOW_RECT_MSG_ID", //               = 113;
        "LONG_PRESS_CENTER", //              = 114;
        "PREVENT_TOUCH_ID", //               = 115;
        "WEBCORE_NEED_TOUCH_EVENTS", //      = 116;
        "INVAL_RECT_MSG_ID", //              = 117;
        "REQUEST_KEYBOARD", //               = 118;
        "DO_MOTION_UP", //                   = 119;
        "SHOW_FULLSCREEN", //                = 120;
        "HIDE_FULLSCREEN", //                = 121;
        "DOM_FOCUS_CHANGED", //              = 122;
        "REPLACE_BASE_CONTENT", //           = 123;
        "RETURN_LABEL", //                   = 125;
        "UPDATE_MATCH_COUNT", //             = 126;
        "CENTER_FIT_RECT", //                = 127;
        "REQUEST_KEYBOARD_WITH_SELECTION_MSG_ID", // = 128;
        "SET_SCROLLBAR_MODES", //            = 129;
        "SELECTION_STRING_CHANGED", //       = 130;
        "SET_TOUCH_HIGHLIGHT_RECTS", //      = 131;
        "SAVE_WEBARCHIVE_FINISHED", //       = 132;
        "SET_AUTOFILLABLE", //               = 133;
        "AUTOFILL_COMPLETE", //              = 134;
        "SELECT_AT", //                      = 135;
        "SCREEN_ON", //                      = 136;
        "ENTER_FULLSCREEN_VIDEO", //         = 137;
        "UPDATE_SELECTION", //               = 138;
        "UPDATE_ZOOM_DENSITY" //             = 139;
    };

    // If the site doesn't use the viewport meta tag to specify the viewport,
    // use DEFAULT_VIEWPORT_WIDTH as the default viewport width
    static final int DEFAULT_VIEWPORT_WIDTH = 980;

    // normally we try to fit the content to the minimum preferred width
    // calculated by the Webkit. To avoid the bad behavior when some site's
    // minimum preferred width keeps growing when changing the viewport width or
    // the minimum preferred width is huge, an upper limit is needed.
    static int sMaxViewportWidth = DEFAULT_VIEWPORT_WIDTH;

    // initial scale in percent. 0 means using default.
    private int mInitialScaleInPercent = 0;

    // Whether or not a scroll event should be sent to webkit.  This is only set
    // to false when restoring the scroll position.
    private boolean mSendScrollEvent = true;

    private int mSnapScrollMode = SNAP_NONE;
    private static final int SNAP_NONE = 0;
    private static final int SNAP_LOCK = 1; // not a separate state
    private static final int SNAP_X = 2; // may be combined with SNAP_LOCK
    private static final int SNAP_Y = 4; // may be combined with SNAP_LOCK
    private boolean mSnapPositive;

    // keep these in sync with their counterparts in WebView.cpp
    private static final int DRAW_EXTRAS_NONE = 0;
    private static final int DRAW_EXTRAS_SELECTION = 1;
    private static final int DRAW_EXTRAS_CURSOR_RING = 2;

    // keep this in sync with WebCore:ScrollbarMode in WebKit
    private static final int SCROLLBAR_AUTO = 0;
    private static final int SCROLLBAR_ALWAYSOFF = 1;
    // as we auto fade scrollbar, this is ignored.
    private static final int SCROLLBAR_ALWAYSON = 2;
    private int mHorizontalScrollBarMode = SCROLLBAR_AUTO;
    private int mVerticalScrollBarMode = SCROLLBAR_AUTO;

    /**
     * Max distance to overscroll by in pixels.
     * This how far content can be pulled beyond its normal bounds by the user.
     */
    private int mOverscrollDistance;

    /**
     * Max distance to overfling by in pixels.
     * This is how far flinged content can move beyond the end of its normal bounds.
     */
    private int mOverflingDistance;

    private OverScrollGlow mOverScrollGlow;

    // Used to match key downs and key ups
    private Vector<Integer> mKeysPressed;

    /* package */ static boolean mLogEvent = true;

    // for event log
    private long mLastTouchUpTime = 0;

    private WebViewCore.AutoFillData mAutoFillData;

    private static boolean sNotificationsEnabled = true;

    /**
     * URI scheme for telephone number
     */
    public static final String SCHEME_TEL = "tel:";
    /**
     * URI scheme for email address
     */
    public static final String SCHEME_MAILTO = "mailto:";
    /**
     * URI scheme for map address
     */
    public static final String SCHEME_GEO = "geo:0,0?q=";

    private int mBackgroundColor = Color.WHITE;

    private static final long SELECT_SCROLL_INTERVAL = 1000 / 60; // 60 / second
    private int mAutoScrollX = 0;
    private int mAutoScrollY = 0;
    private int mMinAutoScrollX = 0;
    private int mMaxAutoScrollX = 0;
    private int mMinAutoScrollY = 0;
    private int mMaxAutoScrollY = 0;
    private Rect mScrollingLayerBounds = new Rect();
    private boolean mSentAutoScrollMessage = false;

    // used for serializing asynchronously handled touch events.
    private WebViewInputDispatcher mInputDispatcher;

    // Used to track whether picture updating was paused due to a window focus change.
    private boolean mPictureUpdatePausedForFocusChange = false;

    // Used to notify listeners of a new picture.
    private PictureListener mPictureListener;

    // Used to notify listeners about find-on-page results.
    private WebView.FindListener mFindListener;

    // Used to prevent resending save password message
    private Message mResumeMsg;

    /**
     * Refer to {@link WebView#requestFocusNodeHref(Message)} for more information
     */
    static class FocusNodeHref {
        static final String TITLE = "title";
        static final String URL = "url";
        static final String SRC = "src";
    }

    public WebViewClassic(WebView webView, WebView.PrivateAccess privateAccess) {
        mWebView = webView;
        mWebViewPrivate = privateAccess;
        mContext = webView.getContext();
    }

    /**
     * See {@link WebViewProvider#init(Map, boolean)}
     */
    @Override
    public void init(Map<String, Object> javaScriptInterfaces, boolean privateBrowsing) {
        Context context = mContext;

        // Used by the chrome stack to find application paths
        JniUtil.setContext(context);

        mCallbackProxy = new CallbackProxy(context, this);
        mViewManager = new ViewManager(this);
        L10nUtils.setApplicationContext(context.getApplicationContext());
        mWebViewCore = new WebViewCore(context, this, mCallbackProxy, javaScriptInterfaces);
        mDatabase = WebViewDatabaseClassic.getInstance(context);
        mScroller = new OverScroller(context, null, 0, 0, false); //TODO Use OverScroller's flywheel
        mZoomManager = new ZoomManager(this, mCallbackProxy);

        /* The init method must follow the creation of certain member variables,
         * such as the mZoomManager.
         */
        init();
        setupPackageListener(context);
        setupProxyListener(context);
        setupTrustStorageListener(context);
        updateMultiTouchSupport(context);

        if (privateBrowsing) {
            startPrivateBrowsing();
        }

        mAutoFillData = new WebViewCore.AutoFillData();
        mEditTextScroller = new Scroller(context);

        // Calculate channel distance
        calculateChannelDistance(context);
    }

    /**
     * Calculate sChannelDistance based on the screen information.
     * @param context A Context object used to access application assets.
     */
    private void calculateChannelDistance(Context context) {
        // The channel distance is adjusted for density and screen size
        final DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        final double screenSize = Math.hypot((double)(metrics.widthPixels/metrics.densityDpi),
                (double)(metrics.heightPixels/metrics.densityDpi));
        if (screenSize < 3.0) {
            sChannelDistance = 16;
        } else if (screenSize < 5.0) {
            sChannelDistance = 22;
        } else if (screenSize < 7.0) {
            sChannelDistance = 28;
        } else {
            sChannelDistance = 34;
        }
        sChannelDistance = (int)(sChannelDistance * metrics.density);
        if (sChannelDistance < 16) sChannelDistance = 16;

        if (DebugFlags.WEB_VIEW) {
            Log.v(LOGTAG, "sChannelDistance : " + sChannelDistance
                    + ", density : " + metrics.density
                    + ", screenSize : " + screenSize
                    + ", metrics.heightPixels : " + metrics.heightPixels
                    + ", metrics.widthPixels : " + metrics.widthPixels
                    + ", metrics.densityDpi : " + metrics.densityDpi);
        }
    }

    // WebViewProvider bindings

    static class Factory implements WebViewFactoryProvider,  WebViewFactoryProvider.Statics {
        Factory() {
            // Touch JniUtil and WebViewCore in case this is being called from
            // WebViewFactory.Preloader, to ensure that the JNI libraries that they use are
            // preloaded in the zygote.
            try {
                Class.forName("android.webkit.JniUtil");
                Class.forName("android.webkit.WebViewCore");
            } catch (ClassNotFoundException e) {
                Log.e(LOGTAG, "failed to load JNI libraries");
                throw new AndroidRuntimeException(e);
            }
        }

        @Override
        public String findAddress(String addr) {
            return WebViewClassic.findAddress(addr);
        }
        @Override
        public void setPlatformNotificationsEnabled(boolean enable) {
            if (enable) {
                WebViewClassic.enablePlatformNotifications();
            } else {
                WebViewClassic.disablePlatformNotifications();
            }
        }

        @Override
        public Statics getStatics() { return this; }

        @Override
        public WebViewProvider createWebView(WebView webView, WebView.PrivateAccess privateAccess) {
            return new WebViewClassic(webView, privateAccess);
        }

        @Override
        public GeolocationPermissions getGeolocationPermissions() {
            return GeolocationPermissionsClassic.getInstance();
        }

        @Override
        public CookieManager getCookieManager() {
            return CookieManagerClassic.getInstance();
        }

        @Override
        public WebIconDatabase getWebIconDatabase() {
            return WebIconDatabaseClassic.getInstance();
        }

        @Override
        public WebStorage getWebStorage() {
            return WebStorageClassic.getInstance();
        }

        @Override
        public WebViewDatabase getWebViewDatabase(Context context) {
            return WebViewDatabaseClassic.getInstance(context);
        }

        @Override
        public String getDefaultUserAgent(Context context) {
            return WebSettingsClassic.getDefaultUserAgentForLocale(context,
                    Locale.getDefault());
        }

        @Override
        public void setWebContentsDebuggingEnabled(boolean enable) {
            // no-op for WebViewClassic.
        }
    }

    private void onHandleUiEvent(MotionEvent event, int eventType, int flags) {
        switch (eventType) {
        case WebViewInputDispatcher.EVENT_TYPE_LONG_PRESS:
            HitTestResult hitTest = getHitTestResult();
            if (hitTest != null) {
                mWebView.performLongClick();
            }
            break;
        case WebViewInputDispatcher.EVENT_TYPE_DOUBLE_TAP:
            mZoomManager.handleDoubleTap(event.getX(), event.getY());
            break;
        case WebViewInputDispatcher.EVENT_TYPE_TOUCH:
            onHandleUiTouchEvent(event);
            break;
        case WebViewInputDispatcher.EVENT_TYPE_CLICK:
            if (mFocusedNode != null && mFocusedNode.mIntentUrl != null) {
                mWebView.playSoundEffect(SoundEffectConstants.CLICK);
                overrideLoading(mFocusedNode.mIntentUrl);
            }
            break;
        }
    }

    private void onHandleUiTouchEvent(MotionEvent ev) {
        final ScaleGestureDetector detector =
                mZoomManager.getScaleGestureDetector();

        int action = ev.getActionMasked();
        final boolean pointerUp = action == MotionEvent.ACTION_POINTER_UP;
        final boolean configChanged =
            action == MotionEvent.ACTION_POINTER_UP ||
            action == MotionEvent.ACTION_POINTER_DOWN;
        final int skipIndex = pointerUp ? ev.getActionIndex() : -1;

        // Determine focal point
        float sumX = 0, sumY = 0;
        final int count = ev.getPointerCount();
        for (int i = 0; i < count; i++) {
            if (skipIndex == i) continue;
            sumX += ev.getX(i);
            sumY += ev.getY(i);
        }
        final int div = pointerUp ? count - 1 : count;
        float x = sumX / div;
        float y = sumY / div;

        if (configChanged) {
            mLastTouchX = Math.round(x);
            mLastTouchY = Math.round(y);
            mLastTouchTime = ev.getEventTime();
            mWebView.cancelLongPress();
            mPrivateHandler.removeMessages(SWITCH_TO_LONGPRESS);
        }

        if (detector != null) {
            detector.onTouchEvent(ev);
            if (detector.isInProgress()) {
                mLastTouchTime = ev.getEventTime();

                if (!mZoomManager.supportsPanDuringZoom()) {
                    return;
                }
                mTouchMode = TOUCH_DRAG_MODE;
                if (mVelocityTracker == null) {
                    mVelocityTracker = VelocityTracker.obtain();
                }
            }
        }

        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            cancelTouch();
            action = MotionEvent.ACTION_DOWN;
        } else if (action == MotionEvent.ACTION_MOVE) {
            // negative x or y indicate it is on the edge, skip it.
            if (x < 0 || y < 0) {
                return;
            }
        }

        handleTouchEventCommon(ev, action, Math.round(x), Math.round(y));
    }

    // The webview that is bound to this WebViewClassic instance. Primarily needed for supplying
    // as the first param in the WebViewClient and WebChromeClient callbacks.
    final private WebView mWebView;
    // Callback interface, provides priviledged access into the WebView instance.
    final private WebView.PrivateAccess mWebViewPrivate;
    // Cached reference to mWebView.getContext(), for convenience.
    final private Context mContext;

    /**
     * @return The webview proxy that this classic webview is bound to.
     */
    public WebView getWebView() {
        return mWebView;
    }

    @Override
    public ViewDelegate getViewDelegate() {
        return this;
    }

    @Override
    public ScrollDelegate getScrollDelegate() {
        return this;
    }

    public static WebViewClassic fromWebView(WebView webView) {
        return webView == null ? null : (WebViewClassic) webView.getWebViewProvider();
    }

    // Accessors, purely for convenience (and to reduce code churn during webview proxy migration).
    int getScrollX() {
        return mWebView.getScrollX();
    }

    int getScrollY() {
        return mWebView.getScrollY();
    }

    int getWidth() {
        return mWebView.getWidth();
    }

    int getHeight() {
        return mWebView.getHeight();
    }

    Context getContext() {
        return mContext;
    }

    void invalidate() {
        mWebView.invalidate();
    }

    // Setters for the Scroll X & Y, without invoking the onScrollChanged etc code paths.
    void setScrollXRaw(int mScrollX) {
        mWebViewPrivate.setScrollXRaw(mScrollX);
    }

    void setScrollYRaw(int mScrollY) {
        mWebViewPrivate.setScrollYRaw(mScrollY);
    }

    private static class TrustStorageListener extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(KeyChain.ACTION_STORAGE_CHANGED)) {
                handleCertTrustChanged();
            }
        }
    }
    private static TrustStorageListener sTrustStorageListener;

    /**
     * Handles update to the trust storage.
     */
    private static void handleCertTrustChanged() {
        // send a message for indicating trust storage change
        WebViewCore.sendStaticMessage(EventHub.TRUST_STORAGE_UPDATED, null);
    }

    /*
     * @param context This method expects this to be a valid context.
     */
    private static void setupTrustStorageListener(Context context) {
        if (sTrustStorageListener != null ) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(KeyChain.ACTION_STORAGE_CHANGED);
        sTrustStorageListener = new TrustStorageListener();
        Intent current =
            context.getApplicationContext().registerReceiver(sTrustStorageListener, filter);
        if (current != null) {
            handleCertTrustChanged();
        }
    }

    private static class ProxyReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Proxy.PROXY_CHANGE_ACTION)) {
                handleProxyBroadcast(intent);
            }
        }
    }

    /*
     * Receiver for PROXY_CHANGE_ACTION, will be null when it is not added handling broadcasts.
     */
    private static ProxyReceiver sProxyReceiver;

    /*
     * @param context This method expects this to be a valid context
     */
    private static synchronized void setupProxyListener(Context context) {
        if (sProxyReceiver != null || sNotificationsEnabled == false) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(Proxy.PROXY_CHANGE_ACTION);
        sProxyReceiver = new ProxyReceiver();
        Intent currentProxy = context.getApplicationContext().registerReceiver(
                sProxyReceiver, filter);
        if (currentProxy != null) {
            handleProxyBroadcast(currentProxy);
        }
    }

    /*
     * @param context This method expects this to be a valid context
     */
    private static synchronized void disableProxyListener(Context context) {
        if (sProxyReceiver == null)
            return;

        context.getApplicationContext().unregisterReceiver(sProxyReceiver);
        sProxyReceiver = null;
    }

    private static void handleProxyBroadcast(Intent intent) {
        ProxyProperties proxyProperties = (ProxyProperties)intent.getExtra(Proxy.EXTRA_PROXY_INFO);
        if (proxyProperties == null || proxyProperties.getHost() == null) {
            WebViewCore.sendStaticMessage(EventHub.PROXY_CHANGED, null);
            return;
        }
        WebViewCore.sendStaticMessage(EventHub.PROXY_CHANGED, proxyProperties);
    }

    /*
     * A variable to track if there is a receiver added for ACTION_PACKAGE_ADDED
     * or ACTION_PACKAGE_REMOVED.
     */
    private static boolean sPackageInstallationReceiverAdded = false;

    /*
     * A set of Google packages we monitor for the
     * navigator.isApplicationInstalled() API. Add additional packages as
     * needed.
     */
    private static Set<String> sGoogleApps;
    static {
        sGoogleApps = new HashSet<String>();
        sGoogleApps.add("com.google.android.youtube");
    }

    private static class PackageListener extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            final String packageName = intent.getData().getSchemeSpecificPart();
            final boolean replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
            if (Intent.ACTION_PACKAGE_REMOVED.equals(action) && replacing) {
                // if it is replacing, refreshPlugins() when adding
                return;
            }

            if (sGoogleApps.contains(packageName)) {
                if (Intent.ACTION_PACKAGE_ADDED.equals(action)) {
                    WebViewCore.sendStaticMessage(EventHub.ADD_PACKAGE_NAME, packageName);
                } else {
                    WebViewCore.sendStaticMessage(EventHub.REMOVE_PACKAGE_NAME, packageName);
                }
            }

            PluginManager pm = PluginManager.getInstance(context);
            if (pm.containsPluginPermissionAndSignatures(packageName)) {
                pm.refreshPlugins(Intent.ACTION_PACKAGE_ADDED.equals(action));
            }
        }
    }

    private void setupPackageListener(Context context) {

        /*
         * we must synchronize the instance check and the creation of the
         * receiver to ensure that only ONE receiver exists for all WebView
         * instances.
         */
        synchronized (WebViewClassic.class) {

            // if the receiver already exists then we do not need to register it
            // again
            if (sPackageInstallationReceiverAdded) {
                return;
            }

            IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
            filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            filter.addDataScheme("package");
            BroadcastReceiver packageListener = new PackageListener();
            context.getApplicationContext().registerReceiver(packageListener, filter);
            sPackageInstallationReceiverAdded = true;
        }

        // check if any of the monitored apps are already installed
        AsyncTask<Void, Void, Set<String>> task = new AsyncTask<Void, Void, Set<String>>() {

            @Override
            protected Set<String> doInBackground(Void... unused) {
                Set<String> installedPackages = new HashSet<String>();
                PackageManager pm = mContext.getPackageManager();
                for (String name : sGoogleApps) {
                    try {
                        pm.getPackageInfo(name,
                                PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES);
                        installedPackages.add(name);
                    } catch (PackageManager.NameNotFoundException e) {
                        // package not found
                    }
                }
                return installedPackages;
            }

            // Executes on the UI thread
            @Override
            protected void onPostExecute(Set<String> installedPackages) {
                if (mWebViewCore != null) {
                    mWebViewCore.sendMessage(EventHub.ADD_PACKAGE_NAMES, installedPackages);
                }
            }
        };
        task.execute();
    }

    void updateMultiTouchSupport(Context context) {
        mZoomManager.updateMultiTouchSupport(context);
    }

    void updateJavaScriptEnabled(boolean enabled) {
        if (isAccessibilityInjectionEnabled()) {
            getAccessibilityInjector().updateJavaScriptEnabled(enabled);
        }
    }

    private void init() {
        OnTrimMemoryListener.init(mContext);
        mWebView.setWillNotDraw(false);
        mWebView.setClickable(true);
        mWebView.setLongClickable(true);

        final ViewConfiguration configuration = ViewConfiguration.get(mContext);
        int slop = configuration.getScaledTouchSlop();
        mTouchSlopSquare = slop * slop;
        slop = configuration.getScaledDoubleTapSlop();
        mDoubleTapSlopSquare = slop * slop;
        final float density = WebViewCore.getFixedDisplayDensity(mContext);
        // use one line height, 16 based on our current default font, for how
        // far we allow a touch be away from the edge of a link
        mNavSlop = (int) (16 * density);
        mZoomManager.init(density);
        mMaximumFling = configuration.getScaledMaximumFlingVelocity();

        // Compute the inverse of the density squared.
        DRAG_LAYER_INVERSE_DENSITY_SQUARED = 1 / (density * density);

        mOverscrollDistance = configuration.getScaledOverscrollDistance();
        mOverflingDistance = configuration.getScaledOverflingDistance();

        setScrollBarStyle(mWebViewPrivate.super_getScrollBarStyle());
        // Initially use a size of two, since the user is likely to only hold
        // down two keys at a time (shift + another key)
        mKeysPressed = new Vector<Integer>(2);
        mHTML5VideoViewProxy = null ;
    }

    @Override
    public boolean shouldDelayChildPressedState() {
        return true;
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (!mWebView.isEnabled()) {
            // Only default actions are supported while disabled.
            return mWebViewPrivate.super_performAccessibilityAction(action, arguments);
        }

        if (getAccessibilityInjector().supportsAccessibilityAction(action)) {
            return getAccessibilityInjector().performAccessibilityAction(action, arguments);
        }

        switch (action) {
            case AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD:
            case AccessibilityNodeInfo.ACTION_SCROLL_FORWARD: {
                final int convertedContentHeight = contentToViewY(getContentHeight());
                final int adjustedViewHeight = getHeight() - mWebView.getPaddingTop()
                        - mWebView.getPaddingBottom();
                final int maxScrollY = Math.max(convertedContentHeight - adjustedViewHeight, 0);
                final boolean canScrollBackward = (getScrollY() > 0);
                final boolean canScrollForward = ((getScrollY() - maxScrollY) > 0);
                if ((action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) && canScrollBackward) {
                    mWebView.scrollBy(0, adjustedViewHeight);
                    return true;
                }
                if ((action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) && canScrollForward) {
                    mWebView.scrollBy(0, -adjustedViewHeight);
                    return true;
                }
                return false;
            }
        }

        return mWebViewPrivate.super_performAccessibilityAction(action, arguments);
    }

    @Override
    public AccessibilityNodeProvider getAccessibilityNodeProvider() {
      return null;
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        if (!mWebView.isEnabled()) {
            // Only default actions are supported while disabled.
            return;
        }

        info.setScrollable(isScrollableForAccessibility());

        final int convertedContentHeight = contentToViewY(getContentHeight());
        final int adjustedViewHeight = getHeight() - mWebView.getPaddingTop()
                - mWebView.getPaddingBottom();
        final int maxScrollY = Math.max(convertedContentHeight - adjustedViewHeight, 0);
        final boolean canScrollBackward = (getScrollY() > 0);
        final boolean canScrollForward = ((getScrollY() - maxScrollY) > 0);

        if (canScrollForward) {
            info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        }

        if (canScrollForward) {
            info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        }

        getAccessibilityInjector().onInitializeAccessibilityNodeInfo(info);
    }

    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        event.setScrollable(isScrollableForAccessibility());
        event.setScrollX(getScrollX());
        event.setScrollY(getScrollY());
        final int convertedContentWidth = contentToViewX(getContentWidth());
        final int adjustedViewWidth = getWidth() - mWebView.getPaddingLeft()
                - mWebView.getPaddingLeft();
        event.setMaxScrollX(Math.max(convertedContentWidth - adjustedViewWidth, 0));
        final int convertedContentHeight = contentToViewY(getContentHeight());
        final int adjustedViewHeight = getHeight() - mWebView.getPaddingTop()
                - mWebView.getPaddingBottom();
        event.setMaxScrollY(Math.max(convertedContentHeight - adjustedViewHeight, 0));
    }

    /* package */ void handleSelectionChangedWebCoreThread(String selection, int token) {
        if (isAccessibilityInjectionEnabled()) {
            getAccessibilityInjector().onSelectionStringChangedWebCoreThread(selection, token);
        }
    }

    private boolean isAccessibilityInjectionEnabled() {
        final AccessibilityManager manager = AccessibilityManager.getInstance(mContext);
        if (!manager.isEnabled()) {
            return false;
        }

        // Accessibility scripts should be injected only when a speaking service
        // is enabled. This may need to change later to accommodate Braille.
        final List<AccessibilityServiceInfo> services = manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_SPOKEN);
        if (services.isEmpty()) {
            return false;
        }

        return true;
    }

    private AccessibilityInjector getAccessibilityInjector() {
        if (mAccessibilityInjector == null) {
            mAccessibilityInjector = new AccessibilityInjector(this);
        }
        return mAccessibilityInjector;
    }

    private boolean isScrollableForAccessibility() {
        return (contentToViewX(getContentWidth()) > getWidth() - mWebView.getPaddingLeft()
                - mWebView.getPaddingRight()
                || contentToViewY(getContentHeight()) > getHeight() - mWebView.getPaddingTop()
                - mWebView.getPaddingBottom());
    }

    @Override
    public void setOverScrollMode(int mode) {
        if (mode != View.OVER_SCROLL_NEVER) {
            if (mOverScrollGlow == null) {
                mOverScrollGlow = new OverScrollGlow(this);
            }
        } else {
            mOverScrollGlow = null;
        }
    }

    /* package */ void adjustDefaultZoomDensity(int zoomDensity) {
        final float density = WebViewCore.getFixedDisplayDensity(mContext)
                * 100 / zoomDensity;
        updateDefaultZoomDensity(density);
    }

    /* package */ void updateDefaultZoomDensity(float density) {
        mNavSlop = (int) (16 * density);
        mZoomManager.updateDefaultZoomDensity(density);
    }

    /* package */ int getScaledNavSlop() {
        return viewToContentDimension(mNavSlop);
    }

    /* package */ boolean onSavePassword(String schemePlusHost, String username,
            String password, final Message resumeMsg) {
        boolean rVal = false;
        if (resumeMsg == null) {
            // null resumeMsg implies saving password silently
            mDatabase.setUsernamePassword(schemePlusHost, username, password);
        } else {
            if (mResumeMsg != null) {
                Log.w(LOGTAG, "onSavePassword should not be called while dialog is up");
                resumeMsg.sendToTarget();
                return true;
            }
            mResumeMsg = resumeMsg;
            final Message remember = mPrivateHandler.obtainMessage(
                    REMEMBER_PASSWORD);
            remember.getData().putString("host", schemePlusHost);
            remember.getData().putString("username", username);
            remember.getData().putString("password", password);
            remember.obj = resumeMsg;

            final Message neverRemember = mPrivateHandler.obtainMessage(
                    NEVER_REMEMBER_PASSWORD);
            neverRemember.getData().putString("host", schemePlusHost);
            neverRemember.getData().putString("username", username);
            neverRemember.getData().putString("password", password);
            neverRemember.obj = resumeMsg;

            mSavePasswordDialog = new AlertDialog.Builder(mContext)
                    .setTitle(com.android.internal.R.string.save_password_label)
                    .setMessage(com.android.internal.R.string.save_password_message)
                    .setPositiveButton(com.android.internal.R.string.save_password_notnow,
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (mResumeMsg != null) {
                                resumeMsg.sendToTarget();
                                mResumeMsg = null;
                            }
                            mSavePasswordDialog = null;
                        }
                    })
                    .setNeutralButton(com.android.internal.R.string.save_password_remember,
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (mResumeMsg != null) {
                                remember.sendToTarget();
                                mResumeMsg = null;
                            }
                            mSavePasswordDialog = null;
                        }
                    })
                    .setNegativeButton(com.android.internal.R.string.save_password_never,
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (mResumeMsg != null) {
                                neverRemember.sendToTarget();
                                mResumeMsg = null;
                            }
                            mSavePasswordDialog = null;
                        }
                    })
                    .setOnDismissListener(new DialogInterface.OnDismissListener() {
                        @Override
                        public void onDismiss(DialogInterface dialog) {
                            if (mResumeMsg != null) {
                                resumeMsg.sendToTarget();
                                mResumeMsg = null;
                            }
                            mSavePasswordDialog = null;
                        }
                    }).show();
            // Return true so that WebViewCore will pause while the dialog is
            // up.
            rVal = true;
        }
        return rVal;
    }

    @Override
    public void setScrollBarStyle(int style) {
        if (style == View.SCROLLBARS_INSIDE_INSET
                || style == View.SCROLLBARS_OUTSIDE_INSET) {
            mOverlayHorizontalScrollbar = mOverlayVerticalScrollbar = false;
        } else {
            mOverlayHorizontalScrollbar = mOverlayVerticalScrollbar = true;
        }
    }

    /**
     * See {@link WebView#setHorizontalScrollbarOverlay(boolean)}
     */
    @Override
    public void setHorizontalScrollbarOverlay(boolean overlay) {
        mOverlayHorizontalScrollbar = overlay;
    }

    /**
     * See {@link WebView#setVerticalScrollbarOverlay(boolean)
     */
    @Override
    public void setVerticalScrollbarOverlay(boolean overlay) {
        mOverlayVerticalScrollbar = overlay;
    }

    /**
     * See {@link WebView#overlayHorizontalScrollbar()}
     */
    @Override
    public boolean overlayHorizontalScrollbar() {
        return mOverlayHorizontalScrollbar;
    }

    /**
     * See {@link WebView#overlayVerticalScrollbar()}
     */
    @Override
    public boolean overlayVerticalScrollbar() {
        return mOverlayVerticalScrollbar;
    }

    /*
     * Return the width of the view where the content of WebView should render
     * to.
     * Note: this can be called from WebCoreThread.
     */
    /* package */ int getViewWidth() {
        if (!mWebView.isVerticalScrollBarEnabled() || mOverlayVerticalScrollbar) {
            return getWidth();
        } else {
            return Math.max(0, getWidth() - mWebView.getVerticalScrollbarWidth());
        }
    }

    // Interface to enable the browser to override title bar handling.
    public interface TitleBarDelegate {
        int getTitleHeight();
        public void onSetEmbeddedTitleBar(final View title);
    }

    /**
     * Returns the height (in pixels) of the embedded title bar (if any). Does not care about
     * scrolling
     */
    protected int getTitleHeight() {
        if (mWebView instanceof TitleBarDelegate) {
            return ((TitleBarDelegate) mWebView).getTitleHeight();
        }
        return 0;
    }

    /**
     * See {@link WebView#getVisibleTitleHeight()}
     */
    @Override
    @Deprecated
    public int getVisibleTitleHeight() {
        // Actually, this method returns the height of the embedded title bar if one is set via the
        // hidden setEmbeddedTitleBar method.
        return getVisibleTitleHeightImpl();
    }

    private int getVisibleTitleHeightImpl() {
        // need to restrict mScrollY due to over scroll
        return Math.max(getTitleHeight() - Math.max(0, getScrollY()),
                getOverlappingActionModeHeight());
    }

    private int mCachedOverlappingActionModeHeight = -1;

    private int getOverlappingActionModeHeight() {
        if (mFindCallback == null) {
            return 0;
        }
        if (mCachedOverlappingActionModeHeight < 0) {
            mWebView.getGlobalVisibleRect(mGlobalVisibleRect, mGlobalVisibleOffset);
            mCachedOverlappingActionModeHeight = Math.max(0,
                    mFindCallback.getActionModeGlobalBottom() - mGlobalVisibleRect.top);
        }
        return mCachedOverlappingActionModeHeight;
    }

    /*
     * Return the height of the view where the content of WebView should render
     * to.  Note that this excludes mTitleBar, if there is one.
     * Note: this can be called from WebCoreThread.
     */
    /* package */ int getViewHeight() {
        return getViewHeightWithTitle() - getVisibleTitleHeightImpl();
    }

    int getViewHeightWithTitle() {
        int height = getHeight();
        if (mWebView.isHorizontalScrollBarEnabled() && !mOverlayHorizontalScrollbar) {
            height -= mWebViewPrivate.getHorizontalScrollbarHeight();
        }
        return height;
    }

    /**
     * See {@link WebView#getCertificate()}
     */
    @Override
    public SslCertificate getCertificate() {
        return mCertificate;
    }

    /**
     * See {@link WebView#setCertificate(SslCertificate)}
     */
    @Override
    public void setCertificate(SslCertificate certificate) {
        if (DebugFlags.WEB_VIEW) {
            Log.v(LOGTAG, "setCertificate=" + certificate);
        }
        // here, the certificate can be null (if the site is not secure)
        mCertificate = certificate;
    }

    //-------------------------------------------------------------------------
    // Methods called by activity
    //-------------------------------------------------------------------------

    /**
     * See {@link WebView#savePassword(String, String, String)}
     */
    @Override
    public void savePassword(String host, String username, String password) {
        mDatabase.setUsernamePassword(host, username, password);
    }

    /**
     * See {@link WebView#setHttpAuthUsernamePassword(String, String, String, String)}
     */
    @Override
    public void setHttpAuthUsernamePassword(String host, String realm,
            String username, String password) {
        mDatabase.setHttpAuthUsernamePassword(host, realm, username, password);
    }

    /**
     * See {@link WebView#getHttpAuthUsernamePassword(String, String)}
     */
    @Override
    public String[] getHttpAuthUsernamePassword(String host, String realm) {
        return mDatabase.getHttpAuthUsernamePassword(host, realm);
    }

    /**
     * Remove Find or Select ActionModes, if active.
     */
    private void clearActionModes() {
        if (mSelectCallback != null) {
            mSelectCallback.finish();
        }
        if (mFindCallback != null) {
            mFindCallback.finish();
        }
    }

    /**
     * Called to clear state when moving from one page to another, or changing
     * in some other way that makes elements associated with the current page
     * (such as ActionModes) no longer relevant.
     */
    private void clearHelpers() {
        hideSoftKeyboard();
        clearActionModes();
        dismissFullScreenMode();
        cancelDialogs();
    }

    private void cancelDialogs() {
        if (mListBoxDialog != null) {
            mListBoxDialog.cancel();
            mListBoxDialog = null;
        }
        if (mSavePasswordDialog != null) {
            mSavePasswordDialog.dismiss();
            mSavePasswordDialog = null;
        }
    }

    /**
     * See {@link WebView#destroy()}
     */
    @Override
    public void destroy() {
        if (mWebView.getViewRootImpl() != null) {
            Log.e(LOGTAG, Log.getStackTraceString(
                    new Throwable("Error: WebView.destroy() called while still attached!")));
        }
        ensureFunctorDetached();
        destroyJava();
        destroyNative();
    }

    private void ensureFunctorDetached() {
        if (mWebView.isHardwareAccelerated()) {
            int drawGLFunction = nativeGetDrawGLFunction(mNativeClass);
            ViewRootImpl viewRoot = mWebView.getViewRootImpl();
            if (drawGLFunction != 0 && viewRoot != null) {
                viewRoot.detachFunctor(drawGLFunction);
            }
        }
    }

    private void destroyJava() {
        mCallbackProxy.blockMessages();
        if (mAccessibilityInjector != null) {
            mAccessibilityInjector.destroy();
            mAccessibilityInjector = null;
        }
        if (mWebViewCore != null) {
            // Tell WebViewCore to destroy itself
            synchronized (this) {
                WebViewCore webViewCore = mWebViewCore;
                mWebViewCore = null; // prevent using partial webViewCore
                webViewCore.destroy();
            }
            // Remove any pending messages that might not be serviced yet.
            mPrivateHandler.removeCallbacksAndMessages(null);
        }
    }

    private void destroyNative() {
        if (mNativeClass == 0) return;
        int nptr = mNativeClass;
        mNativeClass = 0;
        if (Thread.currentThread() == mPrivateHandler.getLooper().getThread()) {
            // We are on the main thread and can safely delete
            nativeDestroy(nptr);
        } else {
            mPrivateHandler.post(new DestroyNativeRunnable(nptr));
        }
    }

    private static class DestroyNativeRunnable implements Runnable {

        private int mNativePtr;

        public DestroyNativeRunnable(int nativePtr) {
            mNativePtr = nativePtr;
        }

        @Override
        public void run() {
            // nativeDestroy also does a stopGL()
            nativeDestroy(mNativePtr);
        }

    }

    /**
     * See {@link WebView#enablePlatformNotifications()}
     */
    @Deprecated
    public static void enablePlatformNotifications() {
        synchronized (WebViewClassic.class) {
            sNotificationsEnabled = true;
            Context context = JniUtil.getContext();
            if (context != null)
                setupProxyListener(context);
        }
    }

    /**
     * See {@link WebView#disablePlatformNotifications()}
     */
    @Deprecated
    public static void disablePlatformNotifications() {
        synchronized (WebViewClassic.class) {
            sNotificationsEnabled = false;
            Context context = JniUtil.getContext();
            if (context != null)
                disableProxyListener(context);
        }
    }

    /**
     * Sets JavaScript engine flags.
     *
     * @param flags JS engine flags in a String
     *
     * This is an implementation detail.
     */
    public void setJsFlags(String flags) {
        mWebViewCore.sendMessage(EventHub.SET_JS_FLAGS, flags);
    }

    /**
     * See {@link WebView#setNetworkAvailable(boolean)}
     */
    @Override
    public void setNetworkAvailable(boolean networkUp) {
        mWebViewCore.sendMessage(EventHub.SET_NETWORK_STATE,
                networkUp ? 1 : 0, 0);
    }

    /**
     * Inform WebView about the current network type.
     */
    public void setNetworkType(String type, String subtype) {
        Map<String, String> map = new HashMap<String, String>();
        map.put("type", type);
        map.put("subtype", subtype);
        mWebViewCore.sendMessage(EventHub.SET_NETWORK_TYPE, map);
    }

    /**
     * See {@link WebView#saveState(Bundle)}
     */
    @Override
    public WebBackForwardList saveState(Bundle outState) {
        if (outState == null) {
            return null;
        }
        // We grab a copy of the back/forward list because a client of WebView
        // may have invalidated the history list by calling clearHistory.
        WebBackForwardListClassic list = copyBackForwardList();
        final int currentIndex = list.getCurrentIndex();
        final int size = list.getSize();
        // We should fail saving the state if the list is empty or the index is
        // not in a valid range.
        if (currentIndex < 0 || currentIndex >= size || size == 0) {
            return null;
        }
        outState.putInt("index", currentIndex);
        // FIXME: This should just be a byte[][] instead of ArrayList but
        // Parcel.java does not have the code to handle multi-dimensional
        // arrays.
        ArrayList<byte[]> history = new ArrayList<byte[]>(size);
        for (int i = 0; i < size; i++) {
            WebHistoryItemClassic item = list.getItemAtIndex(i);
            if (null == item) {
                // FIXME: this shouldn't happen
                // need to determine how item got set to null
                Log.w(LOGTAG, "saveState: Unexpected null history item.");
                return null;
            }
            byte[] data = item.getFlattenedData();
            if (data == null) {
                // It would be very odd to not have any data for a given history
                // item. And we will fail to rebuild the history list without
                // flattened data.
                return null;
            }
            history.add(data);
        }
        outState.putSerializable("history", history);
        if (mCertificate != null) {
            outState.putBundle("certificate",
                               SslCertificate.saveState(mCertificate));
        }
        outState.putBoolean("privateBrowsingEnabled", isPrivateBrowsingEnabled());
        mZoomManager.saveZoomState(outState);
        return list;
    }

    /**
     * See {@link WebView#savePicture(Bundle, File)}
     */
    @Override
    @Deprecated
    public boolean savePicture(Bundle b, final File dest) {
        if (dest == null || b == null) {
            return false;
        }
        final Picture p = capturePicture();
        // Use a temporary file while writing to ensure the destination file
        // contains valid data.
        final File temp = new File(dest.getPath() + ".writing");
        new Thread(new Runnable() {
            @Override
            public void run() {
                FileOutputStream out = null;
                try {
                    out = new FileOutputStream(temp);
                    p.writeToStream(out);
                    // Writing the picture succeeded, rename the temporary file
                    // to the destination.
                    temp.renameTo(dest);
                } catch (Exception e) {
                    // too late to do anything about it.
                } finally {
                    if (out != null) {
                        try {
                            out.close();
                        } catch (Exception e) {
                            // Can't do anything about that
                        }
                    }
                    temp.delete();
                }
            }
        }).start();
        // now update the bundle
        b.putInt("scrollX", getScrollX());
        b.putInt("scrollY", getScrollY());
        mZoomManager.saveZoomState(b);
        return true;
    }

    private void restoreHistoryPictureFields(Picture p, Bundle b) {
        int sx = b.getInt("scrollX", 0);
        int sy = b.getInt("scrollY", 0);

        mDrawHistory = true;
        mHistoryPicture = p;

        setScrollXRaw(sx);
        setScrollYRaw(sy);
        mZoomManager.restoreZoomState(b);
        final float scale = mZoomManager.getScale();
        mHistoryWidth = Math.round(p.getWidth() * scale);
        mHistoryHeight = Math.round(p.getHeight() * scale);

        invalidate();
    }

    /**
     * See {@link WebView#restorePicture(Bundle, File)};
     */
    @Override
    @Deprecated
    public boolean restorePicture(Bundle b, File src) {
        if (src == null || b == null) {
            return false;
        }
        if (!src.exists()) {
            return false;
        }
        try {
            final FileInputStream in = new FileInputStream(src);
            final Bundle copy = new Bundle(b);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        final Picture p = Picture.createFromStream(in);
                        if (p != null) {
                            // Post a runnable on the main thread to update the
                            // history picture fields.
                            mPrivateHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    restoreHistoryPictureFields(p, copy);
                                }
                            });
                        }
                    } finally {
                        try {
                            in.close();
                        } catch (Exception e) {
                            // Nothing we can do now.
                        }
                    }
                }
            }).start();
        } catch (FileNotFoundException e){
            e.printStackTrace();
        }
        return true;
    }

    /**
     * Saves the view data to the output stream. The output is highly
     * version specific, and may not be able to be loaded by newer versions
     * of WebView.
     * @param stream The {@link OutputStream} to save to
     * @param callback The {@link ValueCallback} to call with the result
     */
    public void saveViewState(OutputStream stream, ValueCallback<Boolean> callback) {
        if (mWebViewCore == null) {
            callback.onReceiveValue(false);
            return;
        }
        mWebViewCore.sendMessageAtFrontOfQueue(EventHub.SAVE_VIEW_STATE,
                new WebViewCore.SaveViewStateRequest(stream, callback));
    }

    /**
     * Loads the view data from the input stream. See
     * {@link #saveViewState(java.io.OutputStream, ValueCallback)} for more information.
     * @param stream The {@link InputStream} to load from
     */
    public void loadViewState(InputStream stream) {
        mBlockWebkitViewMessages = true;
        new AsyncTask<InputStream, Void, DrawData>() {

            @Override
            protected DrawData doInBackground(InputStream... params) {
                try {
                    return ViewStateSerializer.deserializeViewState(params[0]);
                } catch (IOException e) {
                    return null;
                }
            }

            @Override
            protected void onPostExecute(DrawData draw) {
                if (draw == null) {
                    Log.e(LOGTAG, "Failed to load view state!");
                    return;
                }
                int viewWidth = getViewWidth();
                int viewHeight = getViewHeightWithTitle() - getTitleHeight();
                draw.mViewSize = new Point(viewWidth, viewHeight);
                draw.mViewState.mDefaultScale = getDefaultZoomScale();
                mLoadedPicture = draw;
                setNewPicture(mLoadedPicture, true);
                mLoadedPicture.mViewState = null;
            }

        }.execute(stream);
    }

    /**
     * Clears the view state set with {@link #loadViewState(InputStream)}.
     * This WebView will then switch to showing the content from webkit
     */
    public void clearViewState() {
        mBlockWebkitViewMessages = false;
        mLoadedPicture = null;
        invalidate();
    }

    /**
     * See {@link WebView#restoreState(Bundle)}
     */
    @Override
    public WebBackForwardList restoreState(Bundle inState) {
        WebBackForwardListClassic returnList = null;
        if (inState == null) {
            return returnList;
        }
        if (inState.containsKey("index") && inState.containsKey("history")) {
            mCertificate = SslCertificate.restoreState(
                inState.getBundle("certificate"));

            final WebBackForwardListClassic list = mCallbackProxy.getBackForwardList();
            final int index = inState.getInt("index");
            // We can't use a clone of the list because we need to modify the
            // shared copy, so synchronize instead to prevent concurrent
            // modifications.
            synchronized (list) {
                final List<byte[]> history =
                        (List<byte[]>) inState.getSerializable("history");
                final int size = history.size();
                // Check the index bounds so we don't crash in native code while
                // restoring the history index.
                if (index < 0 || index >= size) {
                    return null;
                }
                for (int i = 0; i < size; i++) {
                    byte[] data = history.remove(0);
                    if (data == null) {
                        // If we somehow have null data, we cannot reconstruct
                        // the item and thus our history list cannot be rebuilt.
                        return null;
                    }
                    WebHistoryItem item = new WebHistoryItemClassic(data);
                    list.addHistoryItem(item);
                }
                // Grab the most recent copy to return to the caller.
                returnList = copyBackForwardList();
                // Update the copy to have the correct index.
                returnList.setCurrentIndex(index);
            }
            // Restore private browsing setting.
            if (inState.getBoolean("privateBrowsingEnabled")) {
                getSettings().setPrivateBrowsingEnabled(true);
            }
            mZoomManager.restoreZoomState(inState);
            // Remove all pending messages because we are restoring previous
            // state.
            mWebViewCore.removeMessages();
            if (isAccessibilityInjectionEnabled()) {
                getAccessibilityInjector().addAccessibilityApisIfNecessary();
            }
            // Send a restore state message.
            mWebViewCore.sendMessage(EventHub.RESTORE_STATE, index);
        }
        return returnList;
    }

    /**
     * See {@link WebView#loadUrl(String, Map)}
     */
    @Override
    public void loadUrl(String url, Map<String, String> additionalHttpHeaders) {
        loadUrlImpl(url, additionalHttpHeaders);
    }

    private void loadUrlImpl(String url, Map<String, String> extraHeaders) {
        switchOutDrawHistory();
        WebViewCore.GetUrlData arg = new WebViewCore.GetUrlData();
        arg.mUrl = url;
        arg.mExtraHeaders = extraHeaders;
        mWebViewCore.sendMessage(EventHub.LOAD_URL, arg);
        clearHelpers();
    }

    /**
     * See {@link WebView#loadUrl(String)}
     */
    @Override
    public void loadUrl(String url) {
        loadUrlImpl(url);
    }

    private void loadUrlImpl(String url) {
        if (url == null) {
            return;
        }
        loadUrlImpl(url, null);
    }

    /**
     * See {@link WebView#postUrl(String, byte[])}
     */
    @Override
    public void postUrl(String url, byte[] postData) {
        if (URLUtil.isNetworkUrl(url)) {
            switchOutDrawHistory();
            WebViewCore.PostUrlData arg = new WebViewCore.PostUrlData();
            arg.mUrl = url;
            arg.mPostData = postData;
            mWebViewCore.sendMessage(EventHub.POST_URL, arg);
            clearHelpers();
        } else {
            loadUrlImpl(url);
        }
    }

    /**
     * See {@link WebView#loadData(String, String, String)}
     */
    @Override
    public void loadData(String data, String mimeType, String encoding) {
        loadDataImpl(data, mimeType, encoding);
    }

    private void loadDataImpl(String data, String mimeType, String encoding) {
        StringBuilder dataUrl = new StringBuilder("data:");
        dataUrl.append(mimeType);
        if ("base64".equals(encoding)) {
            dataUrl.append(";base64");
        }
        dataUrl.append(",");
        dataUrl.append(data);
        loadUrlImpl(dataUrl.toString());
    }

    /**
     * See {@link WebView#loadDataWithBaseURL(String, String, String, String, String)}
     */
    @Override
    public void loadDataWithBaseURL(String baseUrl, String data,
            String mimeType, String encoding, String historyUrl) {

        if (baseUrl != null && baseUrl.toLowerCase().startsWith("data:")) {
            loadDataImpl(data, mimeType, encoding);
            return;
        }
        switchOutDrawHistory();
        WebViewCore.BaseUrlData arg = new WebViewCore.BaseUrlData();
        arg.mBaseUrl = baseUrl;
        arg.mData = data;
        arg.mMimeType = mimeType;
        arg.mEncoding = encoding;
        arg.mHistoryUrl = historyUrl;
        mWebViewCore.sendMessage(EventHub.LOAD_DATA, arg);
        clearHelpers();
    }

    @Override
    public void evaluateJavaScript(String script, ValueCallback<String> resultCallback) {
        // K-only API not implemented in WebViewClassic.
        throw new IllegalStateException("This API not supported on Android 4.3 and earlier");
    }

    /**
     * See {@link WebView#saveWebArchive(String)}
     */
    @Override
    public void saveWebArchive(String filename) {
        saveWebArchiveImpl(filename, false, null);
    }

    /* package */ static class SaveWebArchiveMessage {
        SaveWebArchiveMessage (String basename, boolean autoname, ValueCallback<String> callback) {
            mBasename = basename;
            mAutoname = autoname;
            mCallback = callback;
        }

        /* package */ final String mBasename;
        /* package */ final boolean mAutoname;
        /* package */ final ValueCallback<String> mCallback;
        /* package */ String mResultFile;
    }

    /**
     * See {@link WebView#saveWebArchive(String, boolean, ValueCallback)}
     */
    @Override
    public void saveWebArchive(String basename, boolean autoname, ValueCallback<String> callback) {
        saveWebArchiveImpl(basename, autoname, callback);
    }

    private void saveWebArchiveImpl(String basename, boolean autoname,
            ValueCallback<String> callback) {
        mWebViewCore.sendMessage(EventHub.SAVE_WEBARCHIVE,
            new SaveWebArchiveMessage(basename, autoname, callback));
    }

    /**
     * See {@link WebView#stopLoading()}
     */
    @Override
    public void stopLoading() {
        // TODO: should we clear all the messages in the queue before sending
        // STOP_LOADING?
        switchOutDrawHistory();
        mWebViewCore.sendMessage(EventHub.STOP_LOADING);
    }

    /**
     * See {@link WebView#reload()}
     */
    @Override
    public void reload() {
        clearHelpers();
        switchOutDrawHistory();
        mWebViewCore.sendMessage(EventHub.RELOAD);
    }

    /**
     * See {@link WebView#canGoBack()}
     */
    @Override
    public boolean canGoBack() {
        WebBackForwardListClassic l = mCallbackProxy.getBackForwardList();
        synchronized (l) {
            if (l.getClearPending()) {
                return false;
            } else {
                return l.getCurrentIndex() > 0;
            }
        }
    }

    /**
     * See {@link WebView#goBack()}
     */
    @Override
    public void goBack() {
        goBackOrForwardImpl(-1);
    }

    /**
     * See {@link WebView#canGoForward()}
     */
    @Override
    public boolean canGoForward() {
        WebBackForwardListClassic l = mCallbackProxy.getBackForwardList();
        synchronized (l) {
            if (l.getClearPending()) {
                return false;
            } else {
                return l.getCurrentIndex() < l.getSize() - 1;
            }
        }
    }

    /**
     * See {@link WebView#goForward()}
     */
    @Override
    public void goForward() {
        goBackOrForwardImpl(1);
    }

    /**
     * See {@link WebView#canGoBackOrForward(int)}
     */
    @Override
    public boolean canGoBackOrForward(int steps) {
        WebBackForwardListClassic l = mCallbackProxy.getBackForwardList();
        synchronized (l) {
            if (l.getClearPending()) {
                return false;
            } else {
                int newIndex = l.getCurrentIndex() + steps;
                return newIndex >= 0 && newIndex < l.getSize();
            }
        }
    }

    /**
     * See {@link WebView#goBackOrForward(int)}
     */
    @Override
    public void goBackOrForward(int steps) {
        goBackOrForwardImpl(steps);
    }

    private void goBackOrForwardImpl(int steps) {
        goBackOrForward(steps, false);
    }

    private void goBackOrForward(int steps, boolean ignoreSnapshot) {
        if (steps != 0) {
            clearHelpers();
            mWebViewCore.sendMessage(EventHub.GO_BACK_FORWARD, steps,
                    ignoreSnapshot ? 1 : 0);
        }
    }

    /**
     * See {@link WebView#isPrivateBrowsingEnabled()}
     */
    @Override
    public boolean isPrivateBrowsingEnabled() {
        WebSettingsClassic settings = getSettings();
        return (settings != null) ? settings.isPrivateBrowsingEnabled() : false;
    }

    private void startPrivateBrowsing() {
        getSettings().setPrivateBrowsingEnabled(true);
    }

    private boolean extendScroll(int y) {
        int finalY = mScroller.getFinalY();
        int newY = pinLocY(finalY + y);
        if (newY == finalY) return false;
        mScroller.setFinalY(newY);
        mScroller.extendDuration(computeDuration(0, y));
        return true;
    }

    /**
     * See {@link WebView#pageUp(boolean)}
     */
    @Override
    public boolean pageUp(boolean top) {
        if (mNativeClass == 0) {
            return false;
        }
        if (top) {
            // go to the top of the document
            return pinScrollTo(getScrollX(), 0, true, 0);
        }
        // Page up
        int h = getHeight();
        int y;
        if (h > 2 * PAGE_SCROLL_OVERLAP) {
            y = -h + PAGE_SCROLL_OVERLAP;
        } else {
            y = -h / 2;
        }
        return mScroller.isFinished() ? pinScrollBy(0, y, true, 0)
                : extendScroll(y);
    }

    /**
     * See {@link WebView#pageDown(boolean)}
     */
    @Override
    public boolean pageDown(boolean bottom) {
        if (mNativeClass == 0) {
            return false;
        }
        if (bottom) {
            return pinScrollTo(getScrollX(), computeRealVerticalScrollRange(), true, 0);
        }
        // Page down.
        int h = getHeight();
        int y;
        if (h > 2 * PAGE_SCROLL_OVERLAP) {
            y = h - PAGE_SCROLL_OVERLAP;
        } else {
            y = h / 2;
        }
        return mScroller.isFinished() ? pinScrollBy(0, y, true, 0)
                : extendScroll(y);
    }

    /**
     * See {@link WebView#clearView()}
     */
    @Override
    public void clearView() {
        mContentWidth = 0;
        mContentHeight = 0;
        setBaseLayer(0, false, false);
        mWebViewCore.sendMessage(EventHub.CLEAR_CONTENT);
    }

    /**
     * See {@link WebView#capturePicture()}
     */
    @Override
    public Picture capturePicture() {
        if (mNativeClass == 0) return null;
        Picture result = new Picture();
        nativeCopyBaseContentToPicture(result);
        return result;
    }

    /**
     * See {@link WebView#createPrintDocumentAdapter()}
     */
    @Override
    public PrintDocumentAdapter createPrintDocumentAdapter() {
        // K-only API not implemented in WebViewClassic.
        throw new IllegalStateException("This API not supported on Android 4.3 and earlier");
    }

    /**
     * See {@link WebView#getScale()}
     */
    @Override
    public float getScale() {
        return mZoomManager.getScale();
    }

    /**
     * Compute the reading level scale of the WebView
     * @param scale The current scale.
     * @return The reading level scale.
     */
    /*package*/ float computeReadingLevelScale(float scale) {
        return mZoomManager.computeReadingLevelScale(scale);
    }

    /**
     * See {@link WebView#setInitialScale(int)}
     */
    @Override
    public void setInitialScale(int scaleInPercent) {
        mZoomManager.setInitialScaleInPercent(scaleInPercent);
    }

    /**
     * See {@link WebView#invokeZoomPicker()}
     */
    @Override
    public void invokeZoomPicker() {
        if (!getSettings().supportZoom()) {
            Log.w(LOGTAG, "This WebView doesn't support zoom.");
            return;
        }
        clearHelpers();
        mZoomManager.invokeZoomPicker();
    }

    /**
     * See {@link WebView#getHitTestResult()}
     */
    @Override
    public HitTestResult getHitTestResult() {
        return mInitialHitTestResult;
    }

    // No left edge for double-tap zoom alignment
    static final int NO_LEFTEDGE = -1;

    int getBlockLeftEdge(int x, int y, float readingScale) {
        float invReadingScale = 1.0f / readingScale;
        int readingWidth = (int) (getViewWidth() * invReadingScale);
        int left = NO_LEFTEDGE;
        if (mFocusedNode != null) {
            final int length = mFocusedNode.mEnclosingParentRects.length;
            for (int i = 0; i < length; i++) {
                Rect rect = mFocusedNode.mEnclosingParentRects[i];
                if (rect.width() < mFocusedNode.mHitTestSlop) {
                    // ignore bounding boxes that are too small
                    continue;
                } else if (rect.width() > readingWidth) {
                    // stop when bounding box doesn't fit the screen width
                    // at reading scale
                    break;
                }

                left = rect.left;
            }
        }

        return left;
    }

    /**
     * See {@link WebView#requestFocusNodeHref(Message)}
     */
    @Override
    public void requestFocusNodeHref(Message hrefMsg) {
        if (hrefMsg == null) {
            return;
        }
        int contentX = viewToContentX(mLastTouchX + getScrollX());
        int contentY = viewToContentY(mLastTouchY + getScrollY());
        if (mFocusedNode != null && mFocusedNode.mHitTestX == contentX
                && mFocusedNode.mHitTestY == contentY) {
            hrefMsg.getData().putString(FocusNodeHref.URL, mFocusedNode.mLinkUrl);
            hrefMsg.getData().putString(FocusNodeHref.TITLE, mFocusedNode.mAnchorText);
            hrefMsg.getData().putString(FocusNodeHref.SRC, mFocusedNode.mImageUrl);
            hrefMsg.sendToTarget();
            return;
        }
        mWebViewCore.sendMessage(EventHub.REQUEST_CURSOR_HREF,
                contentX, contentY, hrefMsg);
    }

    /**
     * See {@link WebView#requestImageRef(Message)}
     */
    @Override
    public void requestImageRef(Message msg) {
        if (0 == mNativeClass) return; // client isn't initialized
        String url = mFocusedNode != null ? mFocusedNode.mImageUrl : null;
        Bundle data = msg.getData();
        data.putString("url", url);
        msg.setData(data);
        msg.sendToTarget();
    }

    static int pinLoc(int x, int viewMax, int docMax) {
//        Log.d(LOGTAG, "-- pinLoc " + x + " " + viewMax + " " + docMax);
        if (docMax < viewMax) {   // the doc has room on the sides for "blank"
            // pin the short document to the top/left of the screen
            x = 0;
//            Log.d(LOGTAG, "--- center " + x);
        } else if (x < 0) {
            x = 0;
//            Log.d(LOGTAG, "--- zero");
        } else if (x + viewMax > docMax) {
            x = docMax - viewMax;
//            Log.d(LOGTAG, "--- pin " + x);
        }
        return x;
    }

    // Expects x in view coordinates
    int pinLocX(int x) {
        if (mInOverScrollMode) return x;
        return pinLoc(x, getViewWidth(), computeRealHorizontalScrollRange());
    }

    // Expects y in view coordinates
    int pinLocY(int y) {
        if (mInOverScrollMode) return y;
        return pinLoc(y, getViewHeightWithTitle(),
                      computeRealVerticalScrollRange() + getTitleHeight());
    }

    /**
     * Given a distance in view space, convert it to content space. Note: this
     * does not reflect translation, just scaling, so this should not be called
     * with coordinates, but should be called for dimensions like width or
     * height.
     */
    private int viewToContentDimension(int d) {
        return Math.round(d * mZoomManager.getInvScale());
    }

    /**
     * Given an x coordinate in view space, convert it to content space.  Also
     * may be used for absolute heights.
     */
    /*package*/ int viewToContentX(int x) {
        return viewToContentDimension(x);
    }

    /**
     * Given a y coordinate in view space, convert it to content space.
     * Takes into account the height of the title bar if there is one
     * embedded into the WebView.
     */
    /*package*/ int viewToContentY(int y) {
        return viewToContentDimension(y - getTitleHeight());
    }

    /**
     * Given a x coordinate in view space, convert it to content space.
     * Returns the result as a float.
     */
    private float viewToContentXf(int x) {
        return x * mZoomManager.getInvScale();
    }

    /**
     * Given a y coordinate in view space, convert it to content space.
     * Takes into account the height of the title bar if there is one
     * embedded into the WebView. Returns the result as a float.
     */
    private float viewToContentYf(int y) {
        return (y - getTitleHeight()) * mZoomManager.getInvScale();
    }

    /**
     * Given a distance in content space, convert it to view space. Note: this
     * does not reflect translation, just scaling, so this should not be called
     * with coordinates, but should be called for dimensions like width or
     * height.
     */
    /*package*/ int contentToViewDimension(int d) {
        return Math.round(d * mZoomManager.getScale());
    }

    /**
     * Given an x coordinate in content space, convert it to view
     * space.
     */
    /*package*/ int contentToViewX(int x) {
        return contentToViewDimension(x);
    }

    /**
     * Given a y coordinate in content space, convert it to view
     * space.  Takes into account the height of the title bar.
     */
    /*package*/ int contentToViewY(int y) {
        return contentToViewDimension(y) + getTitleHeight();
    }

    private Rect contentToViewRect(Rect x) {
        return new Rect(contentToViewX(x.left), contentToViewY(x.top),
                        contentToViewX(x.right), contentToViewY(x.bottom));
    }

    /*  To invalidate a rectangle in content coordinates, we need to transform
        the rect into view coordinates, so we can then call invalidate(...).

        Normally, we would just call contentToView[XY](...), which eventually
        calls Math.round(coordinate * mActualScale). However, for invalidates,
        we need to account for the slop that occurs with antialiasing. To
        address that, we are a little more liberal in the size of the rect that
        we invalidate.

        This liberal calculation calls floor() for the top/left, and ceil() for
        the bottom/right coordinates. This catches the possible extra pixels of
        antialiasing that we might have missed with just round().
     */

    // Called by JNI to invalidate the View, given rectangle coordinates in
    // content space
    private void viewInvalidate(int l, int t, int r, int b) {
        final float scale = mZoomManager.getScale();
        final int dy = getTitleHeight();
        mWebView.invalidate((int)Math.floor(l * scale),
                (int)Math.floor(t * scale) + dy,
                (int)Math.ceil(r * scale),
                (int)Math.ceil(b * scale) + dy);
    }

    // Called by JNI to invalidate the View after a delay, given rectangle
    // coordinates in content space
    private void viewInvalidateDelayed(long delay, int l, int t, int r, int b) {
        final float scale = mZoomManager.getScale();
        final int dy = getTitleHeight();
        mWebView.postInvalidateDelayed(delay,
                (int)Math.floor(l * scale),
                (int)Math.floor(t * scale) + dy,
                (int)Math.ceil(r * scale),
                (int)Math.ceil(b * scale) + dy);
    }

    private void invalidateContentRect(Rect r) {
        viewInvalidate(r.left, r.top, r.right, r.bottom);
    }

    // stop the scroll animation, and don't let a subsequent fling add
    // to the existing velocity
    private void abortAnimation() {
        mScroller.abortAnimation();
        mLastVelocity = 0;
    }

    /* call from webcoreview.draw(), so we're still executing in the UI thread
    */
    private void recordNewContentSize(int w, int h, boolean updateLayout) {

        // premature data from webkit, ignore
        if ((w | h) == 0) {
            invalidate();
            return;
        }

        // don't abort a scroll animation if we didn't change anything
        if (mContentWidth != w || mContentHeight != h) {
            // record new dimensions
            mContentWidth = w;
            mContentHeight = h;
            // If history Picture is drawn, don't update scroll. They will be
            // updated when we get out of that mode.
            if (!mDrawHistory) {
                // repin our scroll, taking into account the new content size
                updateScrollCoordinates(pinLocX(getScrollX()), pinLocY(getScrollY()));
                if (!mScroller.isFinished()) {
                    // We are in the middle of a scroll.  Repin the final scroll
                    // position.
                    mScroller.setFinalX(pinLocX(mScroller.getFinalX()));
                    mScroller.setFinalY(pinLocY(mScroller.getFinalY()));
                }
            }
            invalidate();
        }
        contentSizeChanged(updateLayout);
    }

    // Used to avoid sending many visible rect messages.
    private Rect mLastVisibleRectSent = new Rect();
    private Rect mLastGlobalRect = new Rect();
    private Rect mVisibleRect = new Rect();
    private Rect mGlobalVisibleRect = new Rect();
    private Point mScrollOffset = new Point();

    Rect sendOurVisibleRect() {
        if (mZoomManager.isPreventingWebkitUpdates()) return mLastVisibleRectSent;
        calcOurContentVisibleRect(mVisibleRect);
        // Rect.equals() checks for null input.
        if (!mVisibleRect.equals(mLastVisibleRectSent)) {
            if (!mBlockWebkitViewMessages) {
                mScrollOffset.set(mVisibleRect.left, mVisibleRect.top);
                mWebViewCore.removeMessages(EventHub.SET_SCROLL_OFFSET);
                mWebViewCore.sendMessage(EventHub.SET_SCROLL_OFFSET,
                        mSendScrollEvent ? 1 : 0, mScrollOffset);
            }
            mLastVisibleRectSent.set(mVisibleRect);
            mPrivateHandler.removeMessages(SWITCH_TO_LONGPRESS);
        }
        if (mWebView.getGlobalVisibleRect(mGlobalVisibleRect)
                && !mGlobalVisibleRect.equals(mLastGlobalRect)) {
            if (DebugFlags.WEB_VIEW) {
                Log.v(LOGTAG, "sendOurVisibleRect=(" + mGlobalVisibleRect.left + ","
                        + mGlobalVisibleRect.top + ",r=" + mGlobalVisibleRect.right + ",b="
                        + mGlobalVisibleRect.bottom);
            }
            // TODO: the global offset is only used by windowRect()
            // in ChromeClientAndroid ; other clients such as touch
            // and mouse events could return view + screen relative points.
            if (!mBlockWebkitViewMessages) {
                mWebViewCore.sendMessage(EventHub.SET_GLOBAL_BOUNDS, mGlobalVisibleRect);
            }
            mLastGlobalRect.set(mGlobalVisibleRect);
        }
        return mVisibleRect;
    }

    private Point mGlobalVisibleOffset = new Point();
    // Sets r to be the visible rectangle of our webview in view coordinates
    private void calcOurVisibleRect(Rect r) {
        mWebView.getGlobalVisibleRect(r, mGlobalVisibleOffset);
        r.offset(-mGlobalVisibleOffset.x, -mGlobalVisibleOffset.y);
    }

    // Sets r to be our visible rectangle in content coordinates
    private void calcOurContentVisibleRect(Rect r) {
        calcOurVisibleRect(r);
        r.left = viewToContentX(r.left);
        // viewToContentY will remove the total height of the title bar.  Add
        // the visible height back in to account for the fact that if the title
        // bar is partially visible, the part of the visible rect which is
        // displaying our content is displaced by that amount.
        r.top = viewToContentY(r.top + getVisibleTitleHeightImpl());
        r.right = viewToContentX(r.right);
        r.bottom = viewToContentY(r.bottom);
    }

    private final Rect mTempContentVisibleRect = new Rect();
    // Sets r to be our visible rectangle in content coordinates. We use this
    // method on the native side to compute the position of the fixed layers.
    // Uses floating coordinates (necessary to correctly place elements when
    // the scale factor is not 1)
    private void calcOurContentVisibleRectF(RectF r) {
        calcOurVisibleRect(mTempContentVisibleRect);
        viewToContentVisibleRect(r, mTempContentVisibleRect);
    }

    static class ViewSizeData {
        int mWidth;
        int mHeight;
        float mHeightWidthRatio;
        int mActualViewHeight;
        int mTextWrapWidth;
        int mAnchorX;
        int mAnchorY;
        float mScale;
        boolean mIgnoreHeight;
    }

    /**
     * Compute unzoomed width and height, and if they differ from the last
     * values we sent, send them to webkit (to be used as new viewport)
     *
     * @param force ensures that the message is sent to webkit even if the width
     * or height has not changed since the last message
     *
     * @return true if new values were sent
     */
    boolean sendViewSizeZoom(boolean force) {
        if (mBlockWebkitViewMessages) return false;
        if (mZoomManager.isPreventingWebkitUpdates()) return false;

        int viewWidth = getViewWidth();
        int newWidth = Math.round(viewWidth * mZoomManager.getInvScale());
        // This height could be fixed and be different from actual visible height.
        int viewHeight = getViewHeightWithTitle() - getTitleHeight();
        int newHeight = Math.round(viewHeight * mZoomManager.getInvScale());
        // Make the ratio more accurate than (newHeight / newWidth), since the
        // latter both are calculated and rounded.
        float heightWidthRatio = (float) viewHeight / viewWidth;
        /*
         * Because the native side may have already done a layout before the
         * View system was able to measure us, we have to send a height of 0 to
         * remove excess whitespace when we grow our width. This will trigger a
         * layout and a change in content size. This content size change will
         * mean that contentSizeChanged will either call this method directly or
         * indirectly from onSizeChanged.
         */
        if (newWidth > mLastWidthSent && mWrapContent) {
            newHeight = 0;
            heightWidthRatio = 0;
        }
        // Actual visible content height.
        int actualViewHeight = Math.round(getViewHeight() * mZoomManager.getInvScale());
        // Avoid sending another message if the dimensions have not changed.
        if (newWidth != mLastWidthSent || newHeight != mLastHeightSent || force ||
                actualViewHeight != mLastActualHeightSent) {
            ViewSizeData data = new ViewSizeData();
            data.mWidth = newWidth;
            data.mHeight = newHeight;
            data.mHeightWidthRatio = heightWidthRatio;
            data.mActualViewHeight = actualViewHeight;
            data.mTextWrapWidth = Math.round(viewWidth / mZoomManager.getTextWrapScale());
            data.mScale = mZoomManager.getScale();
            data.mIgnoreHeight = mZoomManager.isFixedLengthAnimationInProgress()
                    && !mHeightCanMeasure;
            data.mAnchorX = mZoomManager.getDocumentAnchorX();
            data.mAnchorY = mZoomManager.getDocumentAnchorY();
            mWebViewCore.sendMessage(EventHub.VIEW_SIZE_CHANGED, data);
            mLastWidthSent = newWidth;
            mLastHeightSent = newHeight;
            mLastActualHeightSent = actualViewHeight;
            mZoomManager.clearDocumentAnchor();
            return true;
        }
        return false;
    }

    /**
     * Update the double-tap zoom.
     */
    /* package */ void updateDoubleTapZoom(int doubleTapZoom) {
        mZoomManager.updateDoubleTapZoom(doubleTapZoom);
    }

    private int computeRealHorizontalScrollRange() {
        if (mDrawHistory) {
            return mHistoryWidth;
        } else {
            // to avoid rounding error caused unnecessary scrollbar, use floor
            return (int) Math.floor(mContentWidth * mZoomManager.getScale());
        }
    }

    @Override
    public int computeHorizontalScrollRange() {
        int range = computeRealHorizontalScrollRange();

        // Adjust reported range if overscrolled to compress the scroll bars
        final int scrollX = getScrollX();
        final int overscrollRight = computeMaxScrollX();
        if (scrollX < 0) {
            range -= scrollX;
        } else if (scrollX > overscrollRight) {
            range += scrollX - overscrollRight;
        }

        return range;
    }

    @Override
    public int computeHorizontalScrollOffset() {
        return Math.max(getScrollX(), 0);
    }

    private int computeRealVerticalScrollRange() {
        if (mDrawHistory) {
            return mHistoryHeight;
        } else {
            // to avoid rounding error caused unnecessary scrollbar, use floor
            return (int) Math.floor(mContentHeight * mZoomManager.getScale());
        }
    }

    @Override
    public int computeVerticalScrollRange() {
        int range = computeRealVerticalScrollRange();

        // Adjust reported range if overscrolled to compress the scroll bars
        final int scrollY = getScrollY();
        final int overscrollBottom = computeMaxScrollY();
        if (scrollY < 0) {
            range -= scrollY;
        } else if (scrollY > overscrollBottom) {
            range += scrollY - overscrollBottom;
        }

        return range;
    }

    @Override
    public int computeVerticalScrollOffset() {
        return Math.max(getScrollY() - getTitleHeight(), 0);
    }

    @Override
    public int computeVerticalScrollExtent() {
        return getViewHeight();
    }

    @Override
    public void onDrawVerticalScrollBar(Canvas canvas,
                                           Drawable scrollBar,
                                           int l, int t, int r, int b) {
        if (getScrollY() < 0) {
            t -= getScrollY();
        }
        scrollBar.setBounds(l, t + getVisibleTitleHeightImpl(), r, b);
        scrollBar.draw(canvas);
    }

    @Override
    public void onOverScrolled(int scrollX, int scrollY, boolean clampedX,
            boolean clampedY) {
        // Special-case layer scrolling so that we do not trigger normal scroll
        // updating.
        if (mTouchMode == TOUCH_DRAG_TEXT_MODE) {
            scrollEditText(scrollX, scrollY);
            return;
        }
        if (mTouchMode == TOUCH_DRAG_LAYER_MODE) {
            scrollLayerTo(scrollX, scrollY);
            animateHandles();
            return;
        }
        mInOverScrollMode = false;
        int maxX = computeMaxScrollX();
        int maxY = computeMaxScrollY();
        if (maxX == 0) {
            // do not over scroll x if the page just fits the screen
            scrollX = pinLocX(scrollX);
        } else if (scrollX < 0 || scrollX > maxX) {
            mInOverScrollMode = true;
        }
        if (scrollY < 0 || scrollY > maxY) {
            mInOverScrollMode = true;
        }

        int oldX = getScrollX();
        int oldY = getScrollY();

        mWebViewPrivate.super_scrollTo(scrollX, scrollY);

        animateHandles();

        if (mOverScrollGlow != null) {
            mOverScrollGlow.pullGlow(getScrollX(), getScrollY(), oldX, oldY, maxX, maxY);
        }
    }

    /**
     * See {@link WebView#getUrl()}
     */
    @Override
    public String getUrl() {
        WebHistoryItem h = mCallbackProxy.getBackForwardList().getCurrentItem();
        return h != null ? h.getUrl() : null;
    }

    /**
     * See {@link WebView#getOriginalUrl()}
     */
    @Override
    public String getOriginalUrl() {
        WebHistoryItem h = mCallbackProxy.getBackForwardList().getCurrentItem();
        return h != null ? h.getOriginalUrl() : null;
    }

    /**
     * See {@link WebView#getTitle()}
     */
    @Override
    public String getTitle() {
        WebHistoryItem h = mCallbackProxy.getBackForwardList().getCurrentItem();
        return h != null ? h.getTitle() : null;
    }

    /**
     * See {@link WebView#getFavicon()}
     */
    @Override
    public Bitmap getFavicon() {
        WebHistoryItem h = mCallbackProxy.getBackForwardList().getCurrentItem();
        return h != null ? h.getFavicon() : null;
    }

    /**
     * See {@link WebView#getTouchIconUrl()}
     */
    @Override
    public String getTouchIconUrl() {
        WebHistoryItemClassic h = mCallbackProxy.getBackForwardList().getCurrentItem();
        return h != null ? h.getTouchIconUrl() : null;
    }

    /**
     * See {@link WebView#getProgress()}
     */
    @Override
    public int getProgress() {
        return mCallbackProxy.getProgress();
    }

    /**
     * See {@link WebView#getContentHeight()}
     */
    @Override
    public int getContentHeight() {
        return mContentHeight;
    }

    /**
     * See {@link WebView#getContentWidth()}
     */
    @Override
    public int getContentWidth() {
        return mContentWidth;
    }

    public int getPageBackgroundColor() {
        if (mNativeClass == 0) return Color.WHITE;
        return nativeGetBackgroundColor(mNativeClass);
    }

    /**
     * See {@link WebView#pauseTimers()}
     */
    @Override
    public void pauseTimers() {
        mWebViewCore.sendMessage(EventHub.PAUSE_TIMERS);
    }

    /**
     * See {@link WebView#resumeTimers()}
     */
    @Override
    public void resumeTimers() {
        mWebViewCore.sendMessage(EventHub.RESUME_TIMERS);
    }

    /**
     * See {@link WebView#onPause()}
     */
    @Override
    public void onPause() {
        if (!mIsPaused) {
            mIsPaused = true;
            mWebViewCore.sendMessage(EventHub.ON_PAUSE);
            // We want to pause the current playing video when switching out
            // from the current WebView/tab.
            if (mHTML5VideoViewProxy != null) {
                mHTML5VideoViewProxy.pauseAndDispatch();
            }
            if (mNativeClass != 0) {
                nativeSetPauseDrawing(mNativeClass, true);
            }

            cancelDialogs();
            WebCoreThreadWatchdog.pause();
        }
    }

    @Override
    public void onWindowVisibilityChanged(int visibility) {
        updateDrawingState();
    }

    void updateDrawingState() {
        if (mNativeClass == 0 || mIsPaused) return;
        if (mWebView.getWindowVisibility() != View.VISIBLE) {
            nativeSetPauseDrawing(mNativeClass, true);
        } else if (mWebView.getVisibility() != View.VISIBLE) {
            nativeSetPauseDrawing(mNativeClass, true);
        } else {
            nativeSetPauseDrawing(mNativeClass, false);
        }
    }

    /**
     * See {@link WebView#onResume()}
     */
    @Override
    public void onResume() {
        if (mIsPaused) {
            mIsPaused = false;
            mWebViewCore.sendMessage(EventHub.ON_RESUME);
            if (mNativeClass != 0) {
                nativeSetPauseDrawing(mNativeClass, false);
            }
        }
        // We get a call to onResume for new WebViews (i.e. mIsPaused will be false). We need
        // to ensure that the Watchdog thread is running for the new WebView, so call
        // it outside the if block above.
        WebCoreThreadWatchdog.resume();
    }

    /**
     * See {@link WebView#isPaused()}
     */
    @Override
    public boolean isPaused() {
        return mIsPaused;
    }

    /**
     * See {@link WebView#freeMemory()}
     */
    @Override
    public void freeMemory() {
        mWebViewCore.sendMessage(EventHub.FREE_MEMORY);
    }

    /**
     * See {@link WebView#clearCache(boolean)}
     */
    @Override
    public void clearCache(boolean includeDiskFiles) {
        // Note: this really needs to be a static method as it clears cache for all
        // WebView. But we need mWebViewCore to send message to WebCore thread, so
        // we can't make this static.
        mWebViewCore.sendMessage(EventHub.CLEAR_CACHE,
                includeDiskFiles ? 1 : 0, 0);
    }

    /**
     * See {@link WebView#clearFormData()}
     */
    @Override
    public void clearFormData() {
        if (mAutoCompletePopup != null) {
            mAutoCompletePopup.clearAdapter();
        }
    }

    /**
     * See {@link WebView#clearHistory()}
     */
    @Override
    public void clearHistory() {
        mCallbackProxy.getBackForwardList().setClearPending();
        mWebViewCore.sendMessage(EventHub.CLEAR_HISTORY);
    }

    /**
     * See {@link WebView#clearSslPreferences()}
     */
    @Override
    public void clearSslPreferences() {
        mWebViewCore.sendMessage(EventHub.CLEAR_SSL_PREF_TABLE);
    }

    /**
     * See {@link WebView#copyBackForwardList()}
     */
    @Override
    public WebBackForwardListClassic copyBackForwardList() {
        return mCallbackProxy.getBackForwardList().clone();
    }

    /**
     * See {@link WebView#setFindListener(WebView.FindListener)}.
     * @hide
     */
     @Override
    public void setFindListener(WebView.FindListener listener) {
         mFindListener = listener;
     }

    /**
     * See {@link WebView#findNext(boolean)}
     */
    @Override
    public void findNext(boolean forward) {
        if (0 == mNativeClass) return; // client isn't initialized
        if (mFindRequest != null) {
            mWebViewCore.sendMessage(EventHub.FIND_NEXT, forward ? 1 : 0, mFindRequest);
        }
    }

    /**
     * See {@link WebView#findAll(String)}
     */
    @Override
    public int findAll(String find) {
        return findAllBody(find, false);
    }

    @Override
    public void findAllAsync(String find) {
        findAllBody(find, true);
    }

    private int findAllBody(String find, boolean isAsync) {
        if (0 == mNativeClass) return 0; // client isn't initialized
        mFindRequest = null;
        if (find == null) return 0;
        mWebViewCore.removeMessages(EventHub.FIND_ALL);
        mFindRequest = new WebViewCore.FindAllRequest(find);
        if (isAsync) {
            mWebViewCore.sendMessage(EventHub.FIND_ALL, mFindRequest);
            return 0; // no need to wait for response
        }
        synchronized(mFindRequest) {
            try {
                mWebViewCore.sendMessageAtFrontOfQueue(EventHub.FIND_ALL, mFindRequest);
                while (mFindRequest.mMatchCount == -1) {
                    mFindRequest.wait();
                }
            }
            catch (InterruptedException e) {
                return 0;
            }
            return mFindRequest.mMatchCount;
        }
    }

    /**
     * Start an ActionMode for finding text in this WebView.  Only works if this
     *              WebView is attached to the view system.
     * @param text If non-null, will be the initial text to search for.
     *             Otherwise, the last String searched for in this WebView will
     *             be used to start.
     * @param showIme If true, show the IME, assuming the user will begin typing.
     *             If false and text is non-null, perform a find all.
     * @return boolean True if the find dialog is shown, false otherwise.
     */
    @Override
    public boolean showFindDialog(String text, boolean showIme) {
        FindActionModeCallback callback = new FindActionModeCallback(mContext);
        if (mWebView.getParent() == null || mWebView.startActionMode(callback) == null) {
            // Could not start the action mode, so end Find on page
            return false;
        }
        mCachedOverlappingActionModeHeight = -1;
        mFindCallback = callback;
        setFindIsUp(true);
        mFindCallback.setWebView(getWebView());
        if (showIme) {
            mFindCallback.showSoftInput();
        } else if (text != null) {
            mFindCallback.setText(text);
            mFindCallback.findAll();
            return true;
        }
        if (text == null) {
            text = mFindRequest == null ? null : mFindRequest.mSearchText;
        }
        if (text != null) {
            mFindCallback.setText(text);
            mFindCallback.findAll();
        }
        return true;
    }

    /**
     * Keep track of the find callback so that we can remove its titlebar if
     * necessary.
     */
    private FindActionModeCallback mFindCallback;

    /**
     * Toggle whether the find dialog is showing, for both native and Java.
     */
    private void setFindIsUp(boolean isUp) {
        mFindIsUp = isUp;
    }

    // Used to know whether the find dialog is open.  Affects whether
    // or not we draw the highlights for matches.
    private boolean mFindIsUp;

    // Keep track of the last find request sent.
    private WebViewCore.FindAllRequest mFindRequest = null;

    /**
     * Return the first substring consisting of the address of a physical
     * location. Currently, only addresses in the United States are detected,
     * and consist of:
     * - a house number
     * - a street name
     * - a street type (Road, Circle, etc), either spelled out or abbreviated
     * - a city name
     * - a state or territory, either spelled out or two-letter abbr.
     * - an optional 5 digit or 9 digit zip code.
     *
     * All names must be correctly capitalized, and the zip code, if present,
     * must be valid for the state. The street type must be a standard USPS
     * spelling or abbreviation. The state or territory must also be spelled
     * or abbreviated using USPS standards. The house number may not exceed
     * five digits.
     * @param addr The string to search for addresses.
     *
     * @return the address, or if no address is found, return null.
     */
    public static String findAddress(String addr) {
        return findAddress(addr, false);
    }

    /**
     * Return the first substring consisting of the address of a physical
     * location. Currently, only addresses in the United States are detected,
     * and consist of:
     * - a house number
     * - a street name
     * - a street type (Road, Circle, etc), either spelled out or abbreviated
     * - a city name
     * - a state or territory, either spelled out or two-letter abbr.
     * - an optional 5 digit or 9 digit zip code.
     *
     * Names are optionally capitalized, and the zip code, if present,
     * must be valid for the state. The street type must be a standard USPS
     * spelling or abbreviation. The state or territory must also be spelled
     * or abbreviated using USPS standards. The house number may not exceed
     * five digits.
     * @param addr The string to search for addresses.
     * @param caseInsensitive addr Set to true to make search ignore case.
     *
     * @return the address, or if no address is found, return null.
     */
    public static String findAddress(String addr, boolean caseInsensitive) {
        return WebViewCore.nativeFindAddress(addr, caseInsensitive);
    }

    /**
     * See {@link WebView#clearMatches()}
     */
    @Override
    public void clearMatches() {
        if (mNativeClass == 0)
            return;
        mWebViewCore.removeMessages(EventHub.FIND_ALL);
        mWebViewCore.sendMessage(EventHub.FIND_ALL, null);
    }


    /**
     * Called when the find ActionMode ends.
     */
    @Override
    public void notifyFindDialogDismissed() {
        mFindCallback = null;
        mCachedOverlappingActionModeHeight = -1;
        if (mWebViewCore == null) {
            return;
        }
        clearMatches();
        setFindIsUp(false);
        // Now that the dialog has been removed, ensure that we scroll to a
        // location that is not beyond the end of the page.
        pinScrollTo(getScrollX(), getScrollY(), false, 0);
        invalidate();
    }

    /**
     * See {@link WebView#documentHasImages(Message)}
     */
    @Override
    public void documentHasImages(Message response) {
        if (response == null) {
            return;
        }
        mWebViewCore.sendMessage(EventHub.DOC_HAS_IMAGES, response);
    }

    /**
     * Request the scroller to abort any ongoing animation
     */
    public void stopScroll() {
        mScroller.forceFinished(true);
        mLastVelocity = 0;
    }

    @Override
    public void computeScroll() {
        if (mScroller.computeScrollOffset()) {
            int oldX = getScrollX();
            int oldY = getScrollY();
            int x = mScroller.getCurrX();
            int y = mScroller.getCurrY();
            invalidate();  // So we draw again

            if (!mScroller.isFinished()) {
                int rangeX = computeMaxScrollX();
                int rangeY = computeMaxScrollY();
                int overflingDistance = mOverflingDistance;

                // Use the layer's scroll data if needed.
                if (mTouchMode == TOUCH_DRAG_LAYER_MODE) {
                    oldX = mScrollingLayerRect.left;
                    oldY = mScrollingLayerRect.top;
                    rangeX = mScrollingLayerRect.right;
                    rangeY = mScrollingLayerRect.bottom;
                    // No overscrolling for layers.
                    overflingDistance = 0;
                } else if (mTouchMode == TOUCH_DRAG_TEXT_MODE) {
                    oldX = getTextScrollX();
                    oldY = getTextScrollY();
                    rangeX = getMaxTextScrollX();
                    rangeY = getMaxTextScrollY();
                    overflingDistance = 0;
                }

                mWebViewPrivate.overScrollBy(x - oldX, y - oldY, oldX, oldY,
                        rangeX, rangeY,
                        overflingDistance, overflingDistance, false);

                if (mOverScrollGlow != null) {
                    mOverScrollGlow.absorbGlow(x, y, oldX, oldY, rangeX, rangeY);
                }
            } else {
                if (mTouchMode == TOUCH_DRAG_LAYER_MODE) {
                    // Update the layer position instead of WebView.
                    scrollLayerTo(x, y);
                } else if (mTouchMode == TOUCH_DRAG_TEXT_MODE) {
                    scrollEditText(x, y);
                } else {
                    setScrollXRaw(x);
                    setScrollYRaw(y);
                }
                abortAnimation();
                nativeSetIsScrolling(false);
                if (!mBlockWebkitViewMessages) {
                    WebViewCore.resumePriority();
                    if (!mSelectingText) {
                        WebViewCore.resumeUpdatePicture(mWebViewCore);
                    }
                }
                if (oldX != getScrollX() || oldY != getScrollY()) {
                    sendOurVisibleRect();
                }
            }
        } else {
            mWebViewPrivate.super_computeScroll();
        }
    }

    private void scrollLayerTo(int x, int y) {
        int dx = mScrollingLayerRect.left - x;
        int dy = mScrollingLayerRect.top - y;
        if ((dx == 0 && dy == 0) || mNativeClass == 0) {
            return;
        }
        if (mSelectingText) {
            if (mSelectCursorBaseLayerId == mCurrentScrollingLayerId) {
                mSelectCursorBase.offset(dx, dy);
                mSelectCursorBaseTextQuad.offset(dx, dy);
            }
            if (mSelectCursorExtentLayerId == mCurrentScrollingLayerId) {
                mSelectCursorExtent.offset(dx, dy);
                mSelectCursorExtentTextQuad.offset(dx, dy);
            }
        }
        if (mAutoCompletePopup != null &&
                mCurrentScrollingLayerId == mEditTextLayerId) {
            mEditTextContentBounds.offset(dx, dy);
            mAutoCompletePopup.resetRect();
        }
        nativeScrollLayer(mNativeClass, mCurrentScrollingLayerId, x, y);
        mScrollingLayerRect.left = x;
        mScrollingLayerRect.top = y;
        mWebViewCore.sendMessage(WebViewCore.EventHub.SCROLL_LAYER, mCurrentScrollingLayerId,
                mScrollingLayerRect);
        mWebViewPrivate.onScrollChanged(getScrollX(), getScrollY(), getScrollX(), getScrollY());
        invalidate();
    }

    private static int computeDuration(int dx, int dy) {
        int distance = Math.max(Math.abs(dx), Math.abs(dy));
        int duration = distance * 1000 / STD_SPEED;
        return Math.min(duration, MAX_DURATION);
    }

    // helper to pin the scrollBy parameters (already in view coordinates)
    // returns true if the scroll was changed
    private boolean pinScrollBy(int dx, int dy, boolean animate, int animationDuration) {
        return pinScrollTo(getScrollX() + dx, getScrollY() + dy, animate, animationDuration);
    }
    // helper to pin the scrollTo parameters (already in view coordinates)
    // returns true if the scroll was changed
    private boolean pinScrollTo(int x, int y, boolean animate, int animationDuration) {
        abortAnimation();
        x = pinLocX(x);
        y = pinLocY(y);
        int dx = x - getScrollX();
        int dy = y - getScrollY();

        if ((dx | dy) == 0) {
            return false;
        }
        if (animate) {
            //        Log.d(LOGTAG, "startScroll: " + dx + " " + dy);
            mScroller.startScroll(getScrollX(), getScrollY(), dx, dy,
                    animationDuration > 0 ? animationDuration : computeDuration(dx, dy));
            invalidate();
        } else {
            mWebView.scrollTo(x, y);
        }
        return true;
    }

    // Scale from content to view coordinates, and pin.
    // Also called by jni webview.cpp
    private boolean setContentScrollBy(int cx, int cy, boolean animate) {
        if (mDrawHistory) {
            // disallow WebView to change the scroll position as History Picture
            // is used in the view system.
            // TODO: as we switchOutDrawHistory when trackball or navigation
            // keys are hit, this should be safe. Right?
            return false;
        }
        cx = contentToViewDimension(cx);
        cy = contentToViewDimension(cy);
        if (mHeightCanMeasure) {
            // move our visible rect according to scroll request
            if (cy != 0) {
                Rect tempRect = new Rect();
                calcOurVisibleRect(tempRect);
                tempRect.offset(cx, cy);
                mWebView.requestRectangleOnScreen(tempRect);
            }
            // FIXME: We scroll horizontally no matter what because currently
            // ScrollView and ListView will not scroll horizontally.
            // FIXME: Why do we only scroll horizontally if there is no
            // vertical scroll?
//                Log.d(LOGTAG, "setContentScrollBy cy=" + cy);
            return cy == 0 && cx != 0 && pinScrollBy(cx, 0, animate, 0);
        } else {
            return pinScrollBy(cx, cy, animate, 0);
        }
    }

    /**
     * Called by CallbackProxy when the page starts loading.
     * @param url The URL of the page which has started loading.
     */
    /* package */ void onPageStarted(String url) {
        // every time we start a new page, we want to reset the
        // WebView certificate:  if the new site is secure, we
        // will reload it and get a new certificate set;
        // if the new site is not secure, the certificate must be
        // null, and that will be the case
        mWebView.setCertificate(null);

        if (isAccessibilityInjectionEnabled()) {
            getAccessibilityInjector().onPageStarted(url);
        }

        // Don't start out editing.
        mIsEditingText = false;
    }

    /**
     * Called by CallbackProxy when the page finishes loading.
     * @param url The URL of the page which has finished loading.
     */
    /* package */ void onPageFinished(String url) {
        mZoomManager.onPageFinished(url);

        if (isAccessibilityInjectionEnabled()) {
            getAccessibilityInjector().onPageFinished(url);
        }
    }

    // scale from content to view coordinates, and pin
    private void contentScrollTo(int cx, int cy, boolean animate) {
        if (mDrawHistory) {
            // disallow WebView to change the scroll position as History Picture
            // is used in the view system.
            return;
        }
        int vx = contentToViewX(cx);
        int vy = contentToViewY(cy);
        pinScrollTo(vx, vy, animate, 0);
    }

    /**
     * These are from webkit, and are in content coordinate system (unzoomed)
     */
    private void contentSizeChanged(boolean updateLayout) {
        // suppress 0,0 since we usually see real dimensions soon after
        // this avoids drawing the prev content in a funny place. If we find a
        // way to consolidate these notifications, this check may become
        // obsolete
        if ((mContentWidth | mContentHeight) == 0) {
            return;
        }

        if (mHeightCanMeasure) {
            if (mWebView.getMeasuredHeight() != contentToViewDimension(mContentHeight)
                    || updateLayout) {
                mWebView.requestLayout();
            }
        } else if (mWidthCanMeasure) {
            if (mWebView.getMeasuredWidth() != contentToViewDimension(mContentWidth)
                    || updateLayout) {
                mWebView.requestLayout();
            }
        } else {
            // If we don't request a layout, try to send our view size to the
            // native side to ensure that WebCore has the correct dimensions.
            sendViewSizeZoom(false);
        }
    }

    /**
     * See {@link WebView#setWebViewClient(WebViewClient)}
     */
    @Override
    public void setWebViewClient(WebViewClient client) {
        mCallbackProxy.setWebViewClient(client);
    }

    /**
     * Gets the WebViewClient
     * @return the current WebViewClient instance.
     *
     * This is an implementation detail.
     */
    public WebViewClient getWebViewClient() {
        return mCallbackProxy.getWebViewClient();
    }

    /**
     * See {@link WebView#setDownloadListener(DownloadListener)}
     */
    @Override
    public void setDownloadListener(DownloadListener listener) {
        mCallbackProxy.setDownloadListener(listener);
    }

    /**
     * See {@link WebView#setWebChromeClient(WebChromeClient)}
     */
    @Override
    public void setWebChromeClient(WebChromeClient client) {
        mCallbackProxy.setWebChromeClient(client);
    }

    /**
     * Gets the chrome handler.
     * @return the current WebChromeClient instance.
     *
     * This is an implementation detail.
     */
    public WebChromeClient getWebChromeClient() {
        return mCallbackProxy.getWebChromeClient();
    }

    /**
     * Set the back/forward list client. This is an implementation of
     * WebBackForwardListClient for handling new items and changes in the
     * history index.
     * @param client An implementation of WebBackForwardListClient.
     */
    public void setWebBackForwardListClient(WebBackForwardListClient client) {
        mCallbackProxy.setWebBackForwardListClient(client);
    }

    /**
     * Gets the WebBackForwardListClient.
     */
    public WebBackForwardListClient getWebBackForwardListClient() {
        return mCallbackProxy.getWebBackForwardListClient();
    }

    /**
     * See {@link WebView#setPictureListener(PictureListener)}
     */
    @Override
    @Deprecated
    public void setPictureListener(PictureListener listener) {
        mPictureListener = listener;
    }

    /* FIXME: Debug only! Remove for SDK! */
    public void externalRepresentation(Message callback) {
        mWebViewCore.sendMessage(EventHub.REQUEST_EXT_REPRESENTATION, callback);
    }

    /* FIXME: Debug only! Remove for SDK! */
    public void documentAsText(Message callback) {
        mWebViewCore.sendMessage(EventHub.REQUEST_DOC_AS_TEXT, callback);
    }

    /**
     * See {@link WebView#addJavascriptInterface(Object, String)}
     */
    @Override
    public void addJavascriptInterface(Object object, String name) {

        if (object == null) {
            return;
        }
        WebViewCore.JSInterfaceData arg = new WebViewCore.JSInterfaceData();

        arg.mObject = object;
        arg.mInterfaceName = name;

        // starting with JELLY_BEAN_MR1, annotations are mandatory for enabling access to
        // methods that are accessible from JS.
        if (mContext.getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            arg.mRequireAnnotation = true;
        } else {
            arg.mRequireAnnotation = false;
        }
        mWebViewCore.sendMessage(EventHub.ADD_JS_INTERFACE, arg);
    }

    /**
     * See {@link WebView#removeJavascriptInterface(String)}
     */
    @Override
    public void removeJavascriptInterface(String interfaceName) {
        if (mWebViewCore != null) {
            WebViewCore.JSInterfaceData arg = new WebViewCore.JSInterfaceData();
            arg.mInterfaceName = interfaceName;
            mWebViewCore.sendMessage(EventHub.REMOVE_JS_INTERFACE, arg);
        }
    }

    /**
     * See {@link WebView#getSettings()}
     * Note this returns WebSettingsClassic, a sub-class of WebSettings, which can be used
     * to access extension APIs.
     */
    @Override
    public WebSettingsClassic getSettings() {
        return (mWebViewCore != null) ? mWebViewCore.getSettings() : null;
    }

    /**
     * See {@link WebView#getPluginList()}
     */
    @Deprecated
    public static synchronized PluginList getPluginList() {
        return new PluginList();
    }

    /**
     * See {@link WebView#refreshPlugins(boolean)}
     */
    @Deprecated
    public void refreshPlugins(boolean reloadOpenPages) {
    }

    //-------------------------------------------------------------------------
    // Override View methods
    //-------------------------------------------------------------------------

    @Override
    protected void finalize() throws Throwable {
        try {
            destroy();
        } finally {
            super.finalize();
        }
    }

    private void drawContent(Canvas canvas) {
        if (mDrawHistory) {
            canvas.scale(mZoomManager.getScale(), mZoomManager.getScale());
            canvas.drawPicture(mHistoryPicture);
            return;
        }
        if (mNativeClass == 0) return;

        boolean animateZoom = mZoomManager.isFixedLengthAnimationInProgress();
        boolean animateScroll = ((!mScroller.isFinished()
                || mVelocityTracker != null)
                && (mTouchMode != TOUCH_DRAG_MODE ||
                mHeldMotionless != MOTIONLESS_TRUE));
        if (mTouchMode == TOUCH_DRAG_MODE) {
            if (mHeldMotionless == MOTIONLESS_PENDING) {
                mPrivateHandler.removeMessages(DRAG_HELD_MOTIONLESS);
                mHeldMotionless = MOTIONLESS_FALSE;
            }
            if (mHeldMotionless == MOTIONLESS_FALSE) {
                mPrivateHandler.sendMessageDelayed(mPrivateHandler
                        .obtainMessage(DRAG_HELD_MOTIONLESS), MOTIONLESS_TIME);
                mHeldMotionless = MOTIONLESS_PENDING;
            }
        }
        int saveCount = canvas.save();
        if (animateZoom) {
            mZoomManager.animateZoom(canvas);
        } else if (!canvas.isHardwareAccelerated()) {
            canvas.scale(mZoomManager.getScale(), mZoomManager.getScale());
        }

        boolean UIAnimationsRunning = false;
        // Currently for each draw we compute the animation values;
        // We may in the future decide to do that independently.
        if (mNativeClass != 0 && !canvas.isHardwareAccelerated()
                && nativeEvaluateLayersAnimations(mNativeClass)) {
            UIAnimationsRunning = true;
            // If we have unfinished (or unstarted) animations,
            // we ask for a repaint. We only need to do this in software
            // rendering (with hardware rendering we already have a different
            // method of requesting a repaint)
            mWebViewCore.sendMessage(EventHub.NOTIFY_ANIMATION_STARTED);
            invalidate();
        }

        // decide which adornments to draw
        int extras = DRAW_EXTRAS_NONE;
        if (!mFindIsUp && mShowTextSelectionExtra) {
            extras = DRAW_EXTRAS_SELECTION;
        }

        calcOurContentVisibleRectF(mVisibleContentRect);
        if (canvas.isHardwareAccelerated()) {
            Rect invScreenRect = mIsWebViewVisible ? mInvScreenRect : null;
            Rect screenRect = mIsWebViewVisible ? mScreenRect : null;

            int functor = nativeCreateDrawGLFunction(mNativeClass, invScreenRect,
                    screenRect, mVisibleContentRect, getScale(), extras);
            ((HardwareCanvas) canvas).callDrawGLFunction(functor);
            if (mHardwareAccelSkia != getSettings().getHardwareAccelSkiaEnabled()) {
                mHardwareAccelSkia = getSettings().getHardwareAccelSkiaEnabled();
                nativeUseHardwareAccelSkia(mHardwareAccelSkia);
            }

        } else {
            DrawFilter df = null;
            if (mZoomManager.isZoomAnimating() || UIAnimationsRunning) {
                df = mZoomFilter;
            } else if (animateScroll) {
                df = mScrollFilter;
            }
            canvas.setDrawFilter(df);
            nativeDraw(canvas, mVisibleContentRect, mBackgroundColor, extras);
            canvas.setDrawFilter(null);
        }

        canvas.restoreToCount(saveCount);
        drawTextSelectionHandles(canvas);

        if (extras == DRAW_EXTRAS_CURSOR_RING) {
            if (mTouchMode == TOUCH_SHORTPRESS_START_MODE) {
                mTouchMode = TOUCH_SHORTPRESS_MODE;
            }
        }
    }

    /**
     * Draw the background when beyond bounds
     * @param canvas Canvas to draw into
     */
    private void drawOverScrollBackground(Canvas canvas) {
        if (mOverScrollBackground == null) {
            mOverScrollBackground = new Paint();
            Bitmap bm = BitmapFactory.decodeResource(
                    mContext.getResources(),
                    com.android.internal.R.drawable.status_bar_background);
            mOverScrollBackground.setShader(new BitmapShader(bm,
                    Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
            mOverScrollBorder = new Paint();
            mOverScrollBorder.setStyle(Paint.Style.STROKE);
            mOverScrollBorder.setStrokeWidth(0);
            mOverScrollBorder.setColor(0xffbbbbbb);
        }

        int top = 0;
        int right = computeRealHorizontalScrollRange();
        int bottom = top + computeRealVerticalScrollRange();
        // first draw the background and anchor to the top of the view
        canvas.save();
        canvas.translate(getScrollX(), getScrollY());
        canvas.clipRect(-getScrollX(), top - getScrollY(), right - getScrollX(), bottom
                - getScrollY(), Region.Op.DIFFERENCE);
        canvas.drawPaint(mOverScrollBackground);
        canvas.restore();
        // then draw the border
        canvas.drawRect(-1, top - 1, right, bottom, mOverScrollBorder);
        // next clip the region for the content
        canvas.clipRect(0, top, right, bottom);
    }

    @Override
    public void onDraw(Canvas canvas) {
        if (inFullScreenMode()) {
            return; // no need to draw anything if we aren't visible.
        }
        // if mNativeClass is 0, the WebView is either destroyed or not
        // initialized. In either case, just draw the background color and return
        if (mNativeClass == 0) {
            canvas.drawColor(mBackgroundColor);
            return;
        }

        // if both mContentWidth and mContentHeight are 0, it means there is no
        // valid Picture passed to WebView yet. This can happen when WebView
        // just starts. Draw the background and return.
        if ((mContentWidth | mContentHeight) == 0 && mHistoryPicture == null) {
            canvas.drawColor(mBackgroundColor);
            return;
        }

        if (canvas.isHardwareAccelerated()) {
            mZoomManager.setHardwareAccelerated();
        } else {
            mWebViewCore.resumeWebKitDraw();
        }

        int saveCount = canvas.save();
        if (mInOverScrollMode && !getSettings()
                .getUseWebViewBackgroundForOverscrollBackground()) {
            drawOverScrollBackground(canvas);
        }

        canvas.translate(0, getTitleHeight());
        drawContent(canvas);
        canvas.restoreToCount(saveCount);

        if (AUTO_REDRAW_HACK && mAutoRedraw) {
            invalidate();
        }
        mWebViewCore.signalRepaintDone();

        if (mOverScrollGlow != null && mOverScrollGlow.drawEdgeGlows(canvas)) {
            invalidate();
        }

        if (mFocusTransition != null) {
            mFocusTransition.draw(canvas);
        } else if (shouldDrawHighlightRect()) {
            RegionIterator iter = new RegionIterator(mTouchHighlightRegion);
            Rect r = new Rect();
            while (iter.next(r)) {
                canvas.drawRect(r, mTouchHightlightPaint);
            }
        }
        if (DEBUG_TOUCH_HIGHLIGHT) {
            if (getSettings().getNavDump()) {
                if ((mTouchHighlightX | mTouchHighlightY) != 0) {
                    if (mTouchCrossHairColor == null) {
                        mTouchCrossHairColor = new Paint();
                        mTouchCrossHairColor.setColor(Color.RED);
                    }
                    canvas.drawLine(mTouchHighlightX - mNavSlop,
                            mTouchHighlightY - mNavSlop, mTouchHighlightX
                                    + mNavSlop + 1, mTouchHighlightY + mNavSlop
                                    + 1, mTouchCrossHairColor);
                    canvas.drawLine(mTouchHighlightX + mNavSlop + 1,
                            mTouchHighlightY - mNavSlop, mTouchHighlightX
                                    - mNavSlop,
                            mTouchHighlightY + mNavSlop + 1,
                            mTouchCrossHairColor);
                }
            }
        }
    }

    private void removeTouchHighlight() {
        setTouchHighlightRects(null);
    }

    @Override
    public void setLayoutParams(ViewGroup.LayoutParams params) {
        if (params.height == AbsoluteLayout.LayoutParams.WRAP_CONTENT) {
            mWrapContent = true;
        }
        mWebViewPrivate.super_setLayoutParams(params);
    }

    @Override
    public boolean performLongClick() {
        // performLongClick() is the result of a delayed message. If we switch
        // to windows overview, the WebView will be temporarily removed from the
        // view system. In that case, do nothing.
        if (mWebView.getParent() == null) return false;

        // A multi-finger gesture can look like a long press; make sure we don't take
        // long press actions if we're scaling.
        final ScaleGestureDetector detector = mZoomManager.getScaleGestureDetector();
        if (detector != null && detector.isInProgress()) {
            return false;
        }

        if (mSelectingText) return false; // long click does nothing on selection
        /* if long click brings up a context menu, the super function
         * returns true and we're done. Otherwise, nothing happened when
         * the user clicked. */
        if (mWebViewPrivate.super_performLongClick()) {
            return true;
        }
        /* In the case where the application hasn't already handled the long
         * click action, look for a word under the  click. If one is found,
         * animate the text selection into view.
         * FIXME: no animation code yet */
        final boolean isSelecting = selectText();
        if (isSelecting) {
            mWebView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        } else if (focusCandidateIsEditableText()) {
            mSelectCallback = new SelectActionModeCallback();
            mSelectCallback.setWebView(this);
            mSelectCallback.setTextSelected(false);
            mWebView.startActionMode(mSelectCallback);
        }
        return isSelecting;
    }

    /**
     * Select the word at the last click point.
     *
     * This is an implementation detail.
     */
    public boolean selectText() {
        int x = viewToContentX(mLastTouchX + getScrollX());
        int y = viewToContentY(mLastTouchY + getScrollY());
        return selectText(x, y);
    }

    /**
     * Select the word at the indicated content coordinates.
     */
    boolean selectText(int x, int y) {
        if (mWebViewCore == null) {
            return false;
        }
        mWebViewCore.sendMessage(EventHub.SELECT_WORD_AT, x, y);
        return true;
    }

    private int mOrientation = Configuration.ORIENTATION_UNDEFINED;

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        mCachedOverlappingActionModeHeight = -1;
        if (mSelectingText && mOrientation != newConfig.orientation) {
            selectionDone();
        }
        mOrientation = newConfig.orientation;
        if (mWebViewCore != null && !mBlockWebkitViewMessages) {
            mWebViewCore.sendMessage(EventHub.CLEAR_CONTENT);
        }
    }

    /**
     * Keep track of the Callback so we can end its ActionMode or remove its
     * titlebar.
     */
    private SelectActionModeCallback mSelectCallback;

    void setBaseLayer(int layer, boolean showVisualIndicator,
            boolean isPictureAfterFirstLayout) {
        if (mNativeClass == 0)
            return;
        boolean queueFull;
        final int scrollingLayer = (mTouchMode == TOUCH_DRAG_LAYER_MODE)
                ? mCurrentScrollingLayerId : 0;
        queueFull = nativeSetBaseLayer(mNativeClass, layer,
                                       showVisualIndicator, isPictureAfterFirstLayout,
                                       scrollingLayer);

        if (queueFull) {
            mWebViewCore.pauseWebKitDraw();
        } else {
            mWebViewCore.resumeWebKitDraw();
        }

        if (mHTML5VideoViewProxy != null) {
            mHTML5VideoViewProxy.setBaseLayer(layer);
        }
    }

    int getBaseLayer() {
        if (mNativeClass == 0) {
            return 0;
        }
        return nativeGetBaseLayer(mNativeClass);
    }

    private void onZoomAnimationStart() {
    }

    private void onZoomAnimationEnd() {
        mPrivateHandler.sendEmptyMessage(RELOCATE_AUTO_COMPLETE_POPUP);
    }

    void onFixedLengthZoomAnimationStart() {
        WebViewCore.pauseUpdatePicture(getWebViewCore());
        onZoomAnimationStart();
    }

    void onFixedLengthZoomAnimationEnd() {
        if (!mBlockWebkitViewMessages && !mSelectingText) {
            WebViewCore.resumeUpdatePicture(mWebViewCore);
        }
        onZoomAnimationEnd();
    }

    private static final int ZOOM_BITS = Paint.FILTER_BITMAP_FLAG |
                                         Paint.DITHER_FLAG |
                                         Paint.SUBPIXEL_TEXT_FLAG;
    private static final int SCROLL_BITS = Paint.FILTER_BITMAP_FLAG |
                                           Paint.DITHER_FLAG;

    private final DrawFilter mZoomFilter =
            new PaintFlagsDrawFilter(ZOOM_BITS, Paint.LINEAR_TEXT_FLAG);
    // If we need to trade better quality for speed, set mScrollFilter to null
    private final DrawFilter mScrollFilter =
            new PaintFlagsDrawFilter(SCROLL_BITS, 0);

    private class SelectionHandleAlpha {
        private int mAlpha = 0;
        private int mTargetAlpha = 0;

        public void setAlpha(int alpha) {
            mAlpha = alpha;
            // TODO: Use partial invalidate
            invalidate();
        }

        public int getAlpha() {
            return mAlpha;
        }

        public void setTargetAlpha(int alpha) {
            mTargetAlpha = alpha;
        }

        public int getTargetAlpha() {
            return mTargetAlpha;
        }

    }

    private void startSelectingText() {
        mSelectingText = true;
        mShowTextSelectionExtra = true;
        animateHandles();
    }

    private void animateHandle(boolean canShow, ObjectAnimator animator,
            Point selectionPoint, int selectionLayerId,
            SelectionHandleAlpha alpha) {
        boolean isVisible = canShow && mSelectingText
                && ((mSelectionStarted && mSelectDraggingCursor == selectionPoint)
                || isHandleVisible(selectionPoint, selectionLayerId));
        int targetValue = isVisible ? 255 : 0;
        if (targetValue != alpha.getTargetAlpha()) {
            alpha.setTargetAlpha(targetValue);
            animator.setIntValues(targetValue);
            animator.setDuration(SELECTION_HANDLE_ANIMATION_MS);
            animator.start();
        }
    }

    private void animateHandles() {
        boolean canShowBase = mSelectingText;
        boolean canShowExtent = mSelectingText && !mIsCaretSelection;
        animateHandle(canShowBase, mBaseHandleAlphaAnimator, mSelectCursorBase,
                mSelectCursorBaseLayerId, mBaseAlpha);
        animateHandle(canShowExtent, mExtentHandleAlphaAnimator,
                mSelectCursorExtent, mSelectCursorExtentLayerId,
                mExtentAlpha);
    }

    private void endSelectingText() {
        mSelectingText = false;
        mShowTextSelectionExtra = false;
        animateHandles();
    }

    private void ensureSelectionHandles() {
        if (mSelectHandleCenter == null) {
            mSelectHandleCenter = mContext.getResources().getDrawable(
                    com.android.internal.R.drawable.text_select_handle_middle).mutate();
            mSelectHandleLeft = mContext.getResources().getDrawable(
                    com.android.internal.R.drawable.text_select_handle_left).mutate();
            mSelectHandleRight = mContext.getResources().getDrawable(
                    com.android.internal.R.drawable.text_select_handle_right).mutate();
            // All handles have the same height, so we can save effort with
            // this assumption.
            mSelectOffset = new Point(0,
                    -mSelectHandleLeft.getIntrinsicHeight());
        }
    }

    private void drawHandle(Point point, int handleId, Rect bounds,
            int alpha, Canvas canvas) {
        int offset;
        int width;
        int height;
        Drawable drawable;
        boolean isLeft = nativeIsHandleLeft(mNativeClass, handleId);
        if (isLeft) {
            drawable = mSelectHandleLeft;
            width = mSelectHandleLeft.getIntrinsicWidth();
            height = mSelectHandleLeft.getIntrinsicHeight();
            // Magic formula copied from TextView
            offset = (width * 3) / 4;
        } else {
            drawable = mSelectHandleRight;
            width = mSelectHandleRight.getIntrinsicWidth();
            height = mSelectHandleRight.getIntrinsicHeight();
            // Magic formula copied from TextView
            offset = width / 4;
        }
        int x = contentToViewDimension(point.x);
        int y = contentToViewDimension(point.y);
        bounds.set(x - offset, y, x - offset + width, y + height);
        drawable.setBounds(bounds);
        drawable.setAlpha(alpha);
        drawable.draw(canvas);
    }

    private void drawTextSelectionHandles(Canvas canvas) {
        if (mBaseAlpha.getAlpha() == 0 && mExtentAlpha.getAlpha() == 0) {
            return;
        }
        ensureSelectionHandles();
        if (mIsCaretSelection) {
            // Caret handle is centered
            int x = contentToViewDimension(mSelectCursorBase.x) -
                    (mSelectHandleCenter.getIntrinsicWidth() / 2);
            int y = contentToViewDimension(mSelectCursorBase.y);
            mSelectHandleBaseBounds.set(x, y,
                    x + mSelectHandleCenter.getIntrinsicWidth(),
                    y + mSelectHandleCenter.getIntrinsicHeight());
            mSelectHandleCenter.setBounds(mSelectHandleBaseBounds);
            mSelectHandleCenter.setAlpha(mBaseAlpha.getAlpha());
            mSelectHandleCenter.draw(canvas);
        } else {
            drawHandle(mSelectCursorBase, HANDLE_ID_BASE,
                    mSelectHandleBaseBounds, mBaseAlpha.getAlpha(), canvas);
            drawHandle(mSelectCursorExtent, HANDLE_ID_EXTENT,
                    mSelectHandleExtentBounds, mExtentAlpha.getAlpha(), canvas);
        }
    }

    private boolean isHandleVisible(Point selectionPoint, int layerId) {
        boolean isVisible = true;
        if (mIsEditingText) {
            isVisible = mEditTextContentBounds.contains(selectionPoint.x,
                    selectionPoint.y);
        }
        if (isVisible) {
            isVisible = nativeIsPointVisible(mNativeClass, layerId,
                    selectionPoint.x, selectionPoint.y);
        }
        return isVisible;
    }

    /**
     * Takes an int[4] array as an output param with the values being
     * startX, startY, endX, endY
     */
    private void getSelectionHandles(int[] handles) {
        handles[0] = mSelectCursorBase.x;
        handles[1] = mSelectCursorBase.y;
        handles[2] = mSelectCursorExtent.x;
        handles[3] = mSelectCursorExtent.y;
    }

    // draw history
    private boolean mDrawHistory = false;
    private Picture mHistoryPicture = null;
    private int mHistoryWidth = 0;
    private int mHistoryHeight = 0;

    // Only check the flag, can be called from WebCore thread
    boolean drawHistory() {
        return mDrawHistory;
    }

    int getHistoryPictureWidth() {
        return (mHistoryPicture != null) ? mHistoryPicture.getWidth() : 0;
    }

    // Should only be called in UI thread
    void switchOutDrawHistory() {
        if (null == mWebViewCore) return; // CallbackProxy may trigger this
        if (mDrawHistory && (getProgress() == 100 || nativeHasContent())) {
            mDrawHistory = false;
            mHistoryPicture = null;
            invalidate();
            int oldScrollX = getScrollX();
            int oldScrollY = getScrollY();
            setScrollXRaw(pinLocX(getScrollX()));
            setScrollYRaw(pinLocY(getScrollY()));
            if (oldScrollX != getScrollX() || oldScrollY != getScrollY()) {
                mWebViewPrivate.onScrollChanged(getScrollX(), getScrollY(), oldScrollX, oldScrollY);
            } else {
                sendOurVisibleRect();
            }
        }
    }

    /**
     *  Delete text from start to end in the focused textfield. If there is no
     *  focus, or if start == end, silently fail.  If start and end are out of
     *  order, swap them.
     *  @param  start   Beginning of selection to delete.
     *  @param  end     End of selection to delete.
     */
    /* package */ void deleteSelection(int start, int end) {
        mTextGeneration++;
        WebViewCore.TextSelectionData data
                = new WebViewCore.TextSelectionData(start, end, 0);
        mWebViewCore.sendMessage(EventHub.DELETE_SELECTION, mTextGeneration, 0,
                data);
    }

    /**
     *  Set the selection to (start, end) in the focused textfield. If start and
     *  end are out of order, swap them.
     *  @param  start   Beginning of selection.
     *  @param  end     End of selection.
     */
    /* package */ void setSelection(int start, int end) {
        if (mWebViewCore != null) {
            mWebViewCore.sendMessage(EventHub.SET_SELECTION, start, end);
        }
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        if (mInputConnection == null) {
            mInputConnection = new WebViewInputConnection();
            mAutoCompletePopup = new AutoCompletePopup(this, mInputConnection);
        }
        mInputConnection.setupEditorInfo(outAttrs);
        return mInputConnection;
    }

    private void relocateAutoCompletePopup() {
        if (mAutoCompletePopup != null) {
            mAutoCompletePopup.resetRect();
            mAutoCompletePopup.setText(mInputConnection.getEditable());
        }
    }

    /**
     * Called in response to a message from webkit telling us that the soft
     * keyboard should be launched.
     */
    private void displaySoftKeyboard(boolean isTextView) {
        InputMethodManager imm = (InputMethodManager)
                mContext.getSystemService(Context.INPUT_METHOD_SERVICE);

        // bring it back to the default level scale so that user can enter text
        boolean zoom = mZoomManager.getScale() < mZoomManager.getDefaultScale();
        if (zoom) {
            mZoomManager.setZoomCenter(mLastTouchX, mLastTouchY);
            mZoomManager.setZoomScale(mZoomManager.getDefaultScale(), false);
        }
        // Used by plugins and contentEditable.
        // Also used if the navigation cache is out of date, and
        // does not recognize that a textfield is in focus.  In that
        // case, use WebView as the targeted view.
        // see http://b/issue?id=2457459
        imm.showSoftInput(mWebView, 0);
    }

    // Called by WebKit to instruct the UI to hide the keyboard
    private void hideSoftKeyboard() {
        InputMethodManager imm = InputMethodManager.peekInstance();
        if (imm != null && (imm.isActive(mWebView))) {
            imm.hideSoftInputFromWindow(mWebView.getWindowToken(), 0);
        }
    }

    /**
     * Called by AutoCompletePopup to find saved form data associated with the
     * textfield
     * @param name Name of the textfield.
     * @param nodePointer Pointer to the node of the textfield, so it can be
     *          compared to the currently focused textfield when the data is
     *          retrieved.
     * @param autoFillable true if WebKit has determined this field is part of
     *          a form that can be auto filled.
     * @param autoComplete true if the attribute "autocomplete" is set to true
     *          on the textfield.
     */
    /* package */ void requestFormData(String name, int nodePointer,
            boolean autoFillable, boolean autoComplete) {
        if (mWebViewCore.getSettings().getSaveFormData()) {
            Message update = mPrivateHandler.obtainMessage(REQUEST_FORM_DATA);
            update.arg1 = nodePointer;
            RequestFormData updater = new RequestFormData(name, getUrl(),
                    update, autoFillable, autoComplete);
            Thread t = new Thread(updater);
            t.start();
        }
    }

    /*
     * This class requests an Adapter for the AutoCompletePopup which shows past
     * entries stored in the database.  It is a Runnable so that it can be done
     * in its own thread, without slowing down the UI.
     */
    private class RequestFormData implements Runnable {
        private String mName;
        private String mUrl;
        private Message mUpdateMessage;
        private boolean mAutoFillable;
        private boolean mAutoComplete;
        private WebSettingsClassic mWebSettings;

        public RequestFormData(String name, String url, Message msg,
                boolean autoFillable, boolean autoComplete) {
            mName = name;
            mUrl = WebTextView.urlForAutoCompleteData(url);
            mUpdateMessage = msg;
            mAutoFillable = autoFillable;
            mAutoComplete = autoComplete;
            mWebSettings = getSettings();
        }

        @Override
        public void run() {
            ArrayList<String> pastEntries = new ArrayList<String>();

            if (mAutoFillable) {
                // Note that code inside the adapter click handler in AutoCompletePopup depends
                // on the AutoFill item being at the top of the drop down list. If you change
                // the order, make sure to do it there too!
                if (mWebSettings != null && mWebSettings.getAutoFillProfile() != null) {
                    pastEntries.add(mWebView.getResources().getText(
                            com.android.internal.R.string.autofill_this_form).toString() +
                            " " +
                    mAutoFillData.getPreviewString());
                    mAutoCompletePopup.setIsAutoFillProfileSet(true);
                } else {
                    // There is no autofill profile set up yet, so add an option that
                    // will invite the user to set their profile up.
                    pastEntries.add(mWebView.getResources().getText(
                            com.android.internal.R.string.setup_autofill).toString());
                    mAutoCompletePopup.setIsAutoFillProfileSet(false);
                }
            }

            if (mAutoComplete) {
                pastEntries.addAll(mDatabase.getFormData(mUrl, mName));
            }

            if (pastEntries.size() > 0) {
                ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                        mContext,
                        com.android.internal.R.layout.web_text_view_dropdown,
                        pastEntries);
                mUpdateMessage.obj = adapter;
                mUpdateMessage.sendToTarget();
            }
        }
    }

    /**
     * Dump the display tree to "/sdcard/displayTree.txt"
     *
     * debug only
     */
    public void dumpDisplayTree() {
        nativeDumpDisplayTree(getUrl());
    }

    /**
     * Dump the dom tree to adb shell if "toFile" is False, otherwise dump it to
     * "/sdcard/domTree.txt"
     *
     * debug only
     */
    public void dumpDomTree(boolean toFile) {
        mWebViewCore.sendMessage(EventHub.DUMP_DOMTREE, toFile ? 1 : 0, 0);
    }

    /**
     * Dump the render tree to adb shell if "toFile" is False, otherwise dump it
     * to "/sdcard/renderTree.txt"
     *
     * debug only
     */
    public void dumpRenderTree(boolean toFile) {
        mWebViewCore.sendMessage(EventHub.DUMP_RENDERTREE, toFile ? 1 : 0, 0);
    }

    /**
     * Called by DRT on UI thread, need to proxy to WebCore thread.
     *
     * debug only
     */
    public void setUseMockDeviceOrientation() {
        mWebViewCore.sendMessage(EventHub.SET_USE_MOCK_DEVICE_ORIENTATION);
    }

    /**
     * Sets use of the Geolocation mock client. Also resets that client. Called
     * by DRT on UI thread, need to proxy to WebCore thread.
     *
     * debug only
     */
    public void setUseMockGeolocation() {
        mWebViewCore.sendMessage(EventHub.SET_USE_MOCK_GEOLOCATION);
    }

    /**
     * Called by DRT on WebCore thread.
     *
     * debug only
     */
    public void setMockGeolocationPosition(double latitude, double longitude, double accuracy) {
        mWebViewCore.setMockGeolocationPosition(latitude, longitude, accuracy);
    }

    /**
     * Called by DRT on WebCore thread.
     *
     * debug only
     */
    public void setMockGeolocationError(int code, String message) {
        mWebViewCore.setMockGeolocationError(code, message);
    }

    /**
     * Called by DRT on WebCore thread.
     *
     * debug only
     */
    public void setMockGeolocationPermission(boolean allow) {
        mWebViewCore.setMockGeolocationPermission(allow);
    }

    /**
     * Called by DRT on WebCore thread.
     *
     * debug only
     */
    public void setMockDeviceOrientation(boolean canProvideAlpha, double alpha,
            boolean canProvideBeta, double beta, boolean canProvideGamma, double gamma) {
        mWebViewCore.setMockDeviceOrientation(canProvideAlpha, alpha, canProvideBeta, beta,
                canProvideGamma, gamma);
    }

    // This is used to determine long press with the center key.  Does not
    // affect long press with the trackball/touch.
    private boolean mGotCenterDown = false;

    @Override
    public boolean onKeyMultiple(int keyCode, int repeatCount, KeyEvent event) {
        if (mBlockWebkitViewMessages) {
            return false;
        }
        // send complex characters to webkit for use by JS and plugins
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN && event.getCharacters() != null) {
            // pass the key to DOM
            sendBatchableInputMessage(EventHub.KEY_DOWN, 0, 0, event);
            sendBatchableInputMessage(EventHub.KEY_UP, 0, 0, event);
            // return true as DOM handles the key
            return true;
        }
        return false;
    }

    private boolean isEnterActionKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    public boolean onKeyPreIme(int keyCode, KeyEvent event) {
        if (mAutoCompletePopup != null) {
            return mAutoCompletePopup.onKeyPreIme(keyCode, event);
        }
        return false;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (DebugFlags.WEB_VIEW) {
            Log.v(LOGTAG, "keyDown at " + System.currentTimeMillis()
                    + "keyCode=" + keyCode
                    + ", " + event + ", unicode=" + event.getUnicodeChar());
        }
        if (mIsCaretSelection) {
            selectionDone();
        }
        if (mBlockWebkitViewMessages) {
            return false;
        }

        // don't implement accelerator keys here; defer to host application
        if (event.isCtrlPressed()) {
            return false;
        }

        if (mNativeClass == 0) {
            return false;
        }

        // do this hack up front, so it always works, regardless of touch-mode
        if (AUTO_REDRAW_HACK && (keyCode == KeyEvent.KEYCODE_CALL)) {
            mAutoRedraw = !mAutoRedraw;
            if (mAutoRedraw) {
                invalidate();
            }
            return true;
        }

        // Bubble up the key event if
        // 1. it is a system key; or
        // 2. the host application wants to handle it;
        if (event.isSystem()
                || mCallbackProxy.uiOverrideKeyEvent(event)) {
            return false;
        }

        // See if the accessibility injector needs to handle this event.
        if (isAccessibilityInjectionEnabled()
                && getAccessibilityInjector().handleKeyEventIfNecessary(event)) {
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_PAGE_UP) {
            if (event.hasNoModifiers()) {
                pageUp(false);
                return true;
            } else if (event.hasModifiers(KeyEvent.META_ALT_ON)) {
                pageUp(true);
                return true;
            }
        }

        if (keyCode == KeyEvent.KEYCODE_PAGE_DOWN) {
            if (event.hasNoModifiers()) {
                pageDown(false);
                return true;
            } else if (event.hasModifiers(KeyEvent.META_ALT_ON)) {
                pageDown(true);
                return true;
            }
        }

        if (keyCode == KeyEvent.KEYCODE_MOVE_HOME && event.hasNoModifiers()) {
            pageUp(true);
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_MOVE_END && event.hasNoModifiers()) {
            pageDown(true);
            return true;
        }

        if (keyCode >= KeyEvent.KEYCODE_DPAD_UP
                && keyCode <= KeyEvent.KEYCODE_DPAD_RIGHT) {
            switchOutDrawHistory();
        }

        if (isEnterActionKey(keyCode)) {
            switchOutDrawHistory();
            if (event.getRepeatCount() == 0) {
                if (mSelectingText) {
                    return true; // discard press if copy in progress
                }
                mGotCenterDown = true;
                mPrivateHandler.sendMessageDelayed(mPrivateHandler
                        .obtainMessage(LONG_PRESS_CENTER), LONG_PRESS_TIMEOUT);
            }
        }

        if (getSettings().getNavDump()) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_4:
                    dumpDisplayTree();
                    break;
                case KeyEvent.KEYCODE_5:
                case KeyEvent.KEYCODE_6:
                    dumpDomTree(keyCode == KeyEvent.KEYCODE_5);
                    break;
                case KeyEvent.KEYCODE_7:
                case KeyEvent.KEYCODE_8:
                    dumpRenderTree(keyCode == KeyEvent.KEYCODE_7);
                    break;
            }
        }

        // pass the key to DOM
        sendKeyEvent(event);
        // return true as DOM handles the key
        return true;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (DebugFlags.WEB_VIEW) {
            Log.v(LOGTAG, "keyUp at " + System.currentTimeMillis()
                    + ", " + event + ", unicode=" + event.getUnicodeChar());
        }
        if (mBlockWebkitViewMessages) {
            return false;
        }

        if (mNativeClass == 0) {
            return false;
        }

        // special CALL handling when cursor node's href is "tel:XXX"
        if (keyCode == KeyEvent.KEYCODE_CALL
                && mInitialHitTestResult != null
                && mInitialHitTestResult.getType() == HitTestResult.PHONE_TYPE) {
            String text = mInitialHitTestResult.getExtra();
            Intent intent = new Intent(Intent.ACTION_DIAL, Uri.parse(text));
            mContext.startActivity(intent);
            return true;
        }

        // Bubble up the key event if
        // 1. it is a system key; or
        // 2. the host application wants to handle it;
        if (event.isSystem()
                || mCallbackProxy.uiOverrideKeyEvent(event)) {
            return false;
        }

        // See if the accessibility injector needs to handle this event.
        if (isAccessibilityInjectionEnabled()
                && getAccessibilityInjector().handleKeyEventIfNecessary(event)) {
            return true;
        }

        if (isEnterActionKey(keyCode)) {
            // remove the long press message first
            mPrivateHandler.removeMessages(LONG_PRESS_CENTER);
            mGotCenterDown = false;

            if (mSelectingText) {
                copySelection();
                selectionDone();
                return true; // discard press if copy in progress
            }
        }

        // pass the key to DOM
        sendKeyEvent(event);
        // return true as DOM handles the key
        return true;
    }

    private boolean startSelectActionMode() {
        mSelectCallback = new SelectActionModeCallback();
        mSelectCallback.setTextSelected(!mIsCaretSelection);
        mSelectCallback.setWebView(this);
        if (mWebView.startActionMode(mSelectCallback) == null) {
            // There is no ActionMode, so do not allow the user to modify a
            // selection.
            selectionDone();
            return false;
        }
        mWebView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        return true;
    }

    private void showPasteWindow() {
        ClipboardManager cm = (ClipboardManager)(mContext
                .getSystemService(Context.CLIPBOARD_SERVICE));
        if (cm.hasPrimaryClip()) {
            Point cursorPoint = new Point(contentToViewX(mSelectCursorBase.x),
                    contentToViewY(mSelectCursorBase.y));
            Point cursorTop = calculateBaseCaretTop();
            cursorTop.set(contentToViewX(cursorTop.x),
                    contentToViewY(cursorTop.y));

            int[] location = new int[2];
            mWebView.getLocationInWindow(location);
            int offsetX = location[0] - getScrollX();
            int offsetY = location[1] - getScrollY();
            cursorPoint.offset(offsetX, offsetY);
            cursorTop.offset(offsetX, offsetY);
            if (mPasteWindow == null) {
                mPasteWindow = new PastePopupWindow();
            }
            mPasteWindow.show(cursorPoint, cursorTop, location[0], location[1]);
        }
    }

    /**
     * Given segment AB, this finds the point C along AB that is closest to
     * point and then returns it scale along AB. The scale factor is AC/AB.
     *
     * @param x The x coordinate of the point near segment AB that determines
     * the scale factor.
     * @param y The y coordinate of the point near segment AB that determines
     * the scale factor.
     * @param a The first point of the line segment.
     * @param b The second point of the line segment.
     * @return The scale factor AC/AB, where C is the point on AB closest to
     *         point.
     */
    private static float scaleAlongSegment(int x, int y, PointF a, PointF b) {
        // The bottom line of the text box is line AB
        float abX = b.x - a.x;
        float abY = b.y - a.y;
        float ab2 = (abX * abX) + (abY * abY);

        // The line from first point in text bounds to bottom is AP
        float apX = x - a.x;
        float apY = y - a.y;
        float abDotAP = (apX * abX) + (apY * abY);
        float scale = abDotAP / ab2;
        return scale;
    }

    private Point calculateBaseCaretTop() {
        return calculateCaretTop(mSelectCursorBase, mSelectCursorBaseTextQuad);
    }

    private Point calculateDraggingCaretTop() {
        return calculateCaretTop(mSelectDraggingCursor, mSelectDraggingTextQuad);
    }

    /**
     * Assuming arbitrary shape of a quadralateral forming text bounds, this
     * calculates the top of a caret.
     */
    private static Point calculateCaretTop(Point base, QuadF quad) {
        float scale = scaleAlongSegment(base.x, base.y, quad.p4, quad.p3);
        int x = Math.round(scaleCoordinate(scale, quad.p1.x, quad.p2.x));
        int y = Math.round(scaleCoordinate(scale, quad.p1.y, quad.p2.y));
        return new Point(x, y);
    }

    private void hidePasteButton() {
        if (mPasteWindow != null) {
            mPasteWindow.hide();
        }
    }

    private void syncSelectionCursors() {
        mSelectCursorBaseLayerId =
                nativeGetHandleLayerId(mNativeClass, HANDLE_ID_BASE,
                        mSelectCursorBase, mSelectCursorBaseTextQuad);
        mSelectCursorExtentLayerId =
                nativeGetHandleLayerId(mNativeClass, HANDLE_ID_EXTENT,
                        mSelectCursorExtent, mSelectCursorExtentTextQuad);
    }

    private boolean setupWebkitSelect() {
        syncSelectionCursors();
        if (!mIsCaretSelection && !startSelectActionMode()) {
            selectionDone();
            return false;
        }
        startSelectingText();
        mTouchMode = TOUCH_DRAG_MODE;
        return true;
    }

    private void updateWebkitSelection(boolean isSnapped) {
        int handleId = (mSelectDraggingCursor == mSelectCursorBase)
                ? HANDLE_ID_BASE : HANDLE_ID_EXTENT;
        int x = mSelectDraggingCursor.x;
        int y = mSelectDraggingCursor.y;
        if (isSnapped) {
            // "center" the cursor in the snapping quad
            Point top = calculateDraggingCaretTop();
            x = Math.round((top.x + x) / 2);
            y = Math.round((top.y + y) / 2);
        }
        mWebViewCore.removeMessages(EventHub.SELECT_TEXT);
        mWebViewCore.sendMessageAtFrontOfQueue(EventHub.SELECT_TEXT,
                x, y, (Integer)handleId);
    }

    private void resetCaretTimer() {
        mPrivateHandler.removeMessages(CLEAR_CARET_HANDLE);
        if (!mSelectionStarted) {
            mPrivateHandler.sendEmptyMessageDelayed(CLEAR_CARET_HANDLE,
                    CARET_HANDLE_STAMINA_MS);
        }
    }

    /**
     * Select all of the text in this WebView.
     *
     * This is an implementation detail.
     */
    public void selectAll() {
        mWebViewCore.sendMessage(EventHub.SELECT_ALL);
    }

    /**
     * Called when the selection has been removed.
     */
    void selectionDone() {
        if (mSelectingText) {
            hidePasteButton();
            endSelectingText();
            // finish is idempotent, so this is fine even if selectionDone was
            // called by mSelectCallback.onDestroyActionMode
            if (mSelectCallback != null) {
                mSelectCallback.finish();
                mSelectCallback = null;
            }
            invalidate(); // redraw without selection
            mAutoScrollX = 0;
            mAutoScrollY = 0;
            mSentAutoScrollMessage = false;
        }
    }

    /**
     * Copy the selection to the clipboard
     *
     * This is an implementation detail.
     */
    public boolean copySelection() {
        boolean copiedSomething = false;
        String selection = getSelection();
        if (selection != null && selection != "") {
            if (DebugFlags.WEB_VIEW) {
                Log.v(LOGTAG, "copySelection \"" + selection + "\"");
            }
            Toast.makeText(mContext
                    , com.android.internal.R.string.text_copied
                    , Toast.LENGTH_SHORT).show();
            copiedSomething = true;
            ClipboardManager cm = (ClipboardManager)mContext
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setText(selection);
            int[] handles = new int[4];
            getSelectionHandles(handles);
            mWebViewCore.sendMessage(EventHub.COPY_TEXT, handles);
        }
        invalidate(); // remove selection region and pointer
        return copiedSomething;
    }

    /**
     * Cut the selected text into the clipboard
     *
     * This is an implementation detail
     */
    public void cutSelection() {
        copySelection();
        int[] handles = new int[4];
        getSelectionHandles(handles);
        mWebViewCore.sendMessage(EventHub.DELETE_TEXT, handles);
    }

    /**
     * Paste text from the clipboard to the cursor position.
     *
     * This is an implementation detail
     */
    public void pasteFromClipboard() {
        ClipboardManager cm = (ClipboardManager)mContext
                .getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clipData = cm.getPrimaryClip();
        if (clipData != null) {
            ClipData.Item clipItem = clipData.getItemAt(0);
            CharSequence pasteText = clipItem.coerceToText(mContext);
            if (mInputConnection != null) {
                mInputConnection.replaceSelection(pasteText);
            }
        }
    }

    /**
     * Returns the currently highlighted text as a string.
     */
    String getSelection() {
        if (mNativeClass == 0) return "";
        return nativeGetSelection();
    }

    @Override
    public void onAttachedToWindow() {
        if (mWebView.hasWindowFocus()) setActive(true);

        if (isAccessibilityInjectionEnabled()) {
            getAccessibilityInjector().toggleAccessibilityFeedback(true);
        }

        updateHwAccelerated();
    }

    @Override
    public void onDetachedFromWindow() {
        clearHelpers();
        mZoomManager.dismissZoomPicker();
        if (mWebView.hasWindowFocus()) setActive(false);

        if (isAccessibilityInjectionEnabled()) {
            getAccessibilityInjector().toggleAccessibilityFeedback(false);
        }

        updateHwAccelerated();

        ensureFunctorDetached();
    }

    @Override
    public void onVisibilityChanged(View changedView, int visibility) {
        // The zoomManager may be null if the webview is created from XML that
        // specifies the view's visibility param as not visible (see http://b/2794841)
        if (visibility != View.VISIBLE && mZoomManager != null) {
            mZoomManager.dismissZoomPicker();
        }
        updateDrawingState();
    }

    void setActive(boolean active) {
        if (active) {
            if (mWebView.hasFocus()) {
                // If our window regained focus, and we have focus, then begin
                // drawing the cursor ring
                mDrawCursorRing = true;
                setFocusControllerActive(true);
            } else {
                mDrawCursorRing = false;
                setFocusControllerActive(false);
            }
        } else {
            if (!mZoomManager.isZoomPickerVisible()) {
                /*
                 * The external zoom controls come in their own window, so our
                 * window loses focus. Our policy is to not draw the cursor ring
                 * if our window is not focused, but this is an exception since
                 * the user can still navigate the web page with the zoom
                 * controls showing.
                 */
                mDrawCursorRing = false;
            }
            mKeysPressed.clear();
            mPrivateHandler.removeMessages(SWITCH_TO_LONGPRESS);
            mTouchMode = TOUCH_DONE_MODE;
            setFocusControllerActive(false);
        }
        invalidate();
    }

    // To avoid drawing the cursor ring, and remove the TextView when our window
    // loses focus.
    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        setActive(hasWindowFocus);
        if (hasWindowFocus) {
            JWebCoreJavaBridge.setActiveWebView(this);
            if (mPictureUpdatePausedForFocusChange) {
                WebViewCore.resumeUpdatePicture(mWebViewCore);
                mPictureUpdatePausedForFocusChange = false;
            }
        } else {
            JWebCoreJavaBridge.removeActiveWebView(this);
            final WebSettings settings = getSettings();
            if (settings != null && settings.enableSmoothTransition() &&
                    mWebViewCore != null && !WebViewCore.isUpdatePicturePaused(mWebViewCore)) {
                WebViewCore.pauseUpdatePicture(mWebViewCore);
                mPictureUpdatePausedForFocusChange = true;
            }
        }
    }

    /*
     * Pass a message to WebCore Thread, telling the WebCore::Page's
     * FocusController to be  "inactive" so that it will
     * not draw the blinking cursor.  It gets set to "active" to draw the cursor
     * in WebViewCore.cpp, when the WebCore thread receives key events/clicks.
     */
    /* package */ void setFocusControllerActive(boolean active) {
        if (mWebViewCore == null) return;
        mWebViewCore.sendMessage(EventHub.SET_ACTIVE, active ? 1 : 0, 0);
        // Need to send this message after the document regains focus.
        if (active && mListBoxMessage != null) {
            mWebViewCore.sendMessage(mListBoxMessage);
            mListBoxMessage = null;
        }
    }

    @Override
    public void onFocusChanged(boolean focused, int direction,
            Rect previouslyFocusedRect) {
        if (DebugFlags.WEB_VIEW) {
            Log.v(LOGTAG, "MT focusChanged " + focused + ", " + direction);
        }
        if (focused) {
            mDrawCursorRing = true;
            setFocusControllerActive(true);
        } else {
            mDrawCursorRing = false;
            setFocusControllerActive(false);
            mKeysPressed.clear();
        }
        if (!mTouchHighlightRegion.isEmpty()) {
            mWebView.invalidate(mTouchHighlightRegion.getBounds());
        }
    }

    // updateRectsForGL() happens almost every draw call, in order to avoid creating
    // any object in this code path, we move the local variable out to be a private
    // final member, and we marked them as mTemp*.
    private final Point mTempVisibleRectOffset = new Point();
    private final Rect mTempVisibleRect = new Rect();

    void updateRectsForGL() {
        // Use the getGlobalVisibleRect() to get the intersection among the parents
        // visible == false means we're clipped - send a null rect down to indicate that
        // we should not draw
        boolean visible = mWebView.getGlobalVisibleRect(mTempVisibleRect, mTempVisibleRectOffset);
        mInvScreenRect.set(mTempVisibleRect);
        if (visible) {
            // Then need to invert the Y axis, just for GL
            View rootView = mWebView.getRootView();
            int rootViewHeight = rootView.getHeight();
            mScreenRect.set(mInvScreenRect);
            int savedWebViewBottom = mInvScreenRect.bottom;
            mInvScreenRect.bottom = rootViewHeight - mInvScreenRect.top - getVisibleTitleHeightImpl();
            mInvScreenRect.top = rootViewHeight - savedWebViewBottom;
            mIsWebViewVisible = true;
        } else {
            mIsWebViewVisible = false;
        }

        mTempVisibleRect.offset(-mTempVisibleRectOffset.x, -mTempVisibleRectOffset.y);
        viewToContentVisibleRect(mVisibleContentRect, mTempVisibleRect);

        nativeUpdateDrawGLFunction(mNativeClass, mIsWebViewVisible ? mInvScreenRect : null,
                mIsWebViewVisible ? mScreenRect : null,
                mVisibleContentRect, getScale());
    }

    // Input : viewRect, rect in view/screen coordinate.
    // Output: contentRect, rect in content/document coordinate.
    private void viewToContentVisibleRect(RectF contentRect, Rect viewRect) {
        contentRect.left = viewToContentXf(viewRect.left) / mWebView.getScaleX();
        // viewToContentY will remove the total height of the title bar.  Add
        // the visible height back in to account for the fact that if the title
        // bar is partially visible, the part of the visible rect which is
        // displaying our content is displaced by that amount.
        contentRect.top = viewToContentYf(viewRect.top + getVisibleTitleHeightImpl())
                / mWebView.getScaleY();
        contentRect.right = viewToContentXf(viewRect.right) / mWebView.getScaleX();
        contentRect.bottom = viewToContentYf(viewRect.bottom) / mWebView.getScaleY();
    }

    @Override
    public boolean setFrame(int left, int top, int right, int bottom) {
        boolean changed = mWebViewPrivate.super_setFrame(left, top, right, bottom);
        if (!changed && mHeightCanMeasure) {
            // When mHeightCanMeasure is true, we will set mLastHeightSent to 0
            // in WebViewCore after we get the first layout. We do call
            // requestLayout() when we get contentSizeChanged(). But the View
            // system won't call onSizeChanged if the dimension is not changed.
            // In this case, we need to call sendViewSizeZoom() explicitly to
            // notify the WebKit about the new dimensions.
            sendViewSizeZoom(false);
        }
        updateRectsForGL();
        return changed;
    }

    @Override
    public void onSizeChanged(int w, int h, int ow, int oh) {
        // adjust the max viewport width depending on the view dimensions. This
        // is to ensure the scaling is not going insane. So do not shrink it if
        // the view size is temporarily smaller, e.g. when soft keyboard is up.
        int newMaxViewportWidth = (int) (Math.max(w, h) / mZoomManager.getDefaultMinZoomScale());
        if (newMaxViewportWidth > sMaxViewportWidth) {
            sMaxViewportWidth = newMaxViewportWidth;
        }

        mZoomManager.onSizeChanged(w, h, ow, oh);

        if (mLoadedPicture != null && mDelaySetPicture == null) {
            // Size changes normally result in a new picture
            // Re-set the loaded picture to simulate that
            // However, do not update the base layer as that hasn't changed
            setNewPicture(mLoadedPicture, false);
        }
        if (mIsEditingText) {
            scrollEditIntoView();
        }
        relocateAutoCompletePopup();
    }

    /**
     * Scrolls the edit field into view using the minimum scrolling necessary.
     * If the edit field is too large to fit in the visible window, the caret
     * dimensions are used so that at least the caret is visible.
     * A buffer of EDIT_RECT_BUFFER in view pixels is used to offset the
     * edit rectangle to ensure a margin with the edge of the screen.
     */
    private void scrollEditIntoView() {
        Rect visibleRect = new Rect(viewToContentX(getScrollX()),
                viewToContentY(getScrollY()),
                viewToContentX(getScrollX() + getWidth()),
                viewToContentY(getScrollY() + getViewHeightWithTitle()));
        if (visibleRect.contains(mEditTextContentBounds)) {
            return; // no need to scroll
        }
        syncSelectionCursors();
        nativeFindMaxVisibleRect(mNativeClass, mEditTextLayerId, visibleRect);
        final int buffer = Math.max(1, viewToContentDimension(EDIT_RECT_BUFFER));
        Rect showRect = new Rect(
                Math.max(0, mEditTextContentBounds.left - buffer),
                Math.max(0, mEditTextContentBounds.top - buffer),
                mEditTextContentBounds.right + buffer,
                mEditTextContentBounds.bottom + buffer);
        Point caretTop = calculateBaseCaretTop();
        if (visibleRect.width() < mEditTextContentBounds.width()) {
            // The whole edit won't fit in the width, so use the caret rect
            if (mSelectCursorBase.x < caretTop.x) {
                showRect.left = Math.max(0, mSelectCursorBase.x - buffer);
                showRect.right = caretTop.x + buffer;
            } else {
                showRect.left = Math.max(0, caretTop.x - buffer);
                showRect.right = mSelectCursorBase.x + buffer;
            }
        }
        if (visibleRect.height() < mEditTextContentBounds.height()) {
            // The whole edit won't fit in the height, so use the caret rect
            if (mSelectCursorBase.y > caretTop.y) {
                showRect.top = Math.max(0, caretTop.y - buffer);
                showRect.bottom = mSelectCursorBase.y + buffer;
            } else {
                showRect.top = Math.max(0, mSelectCursorBase.y - buffer);
                showRect.bottom = caretTop.y + buffer;
            }
        }

        if (visibleRect.contains(showRect)) {
            return; // no need to scroll
        }

        int scrollX = viewToContentX(getScrollX());
        if (visibleRect.left > showRect.left) {
            // We are scrolled too far
            scrollX += showRect.left - visibleRect.left;
        } else if (visibleRect.right < showRect.right) {
            // We aren't scrolled enough to include the right
            scrollX += showRect.right - visibleRect.right;
        }
        int scrollY = viewToContentY(getScrollY());
        if (visibleRect.top > showRect.top) {
            scrollY += showRect.top - visibleRect.top;
        } else if (visibleRect.bottom < showRect.bottom) {
            scrollY += showRect.bottom - visibleRect.bottom;
        }

        contentScrollTo(scrollX, scrollY, false);
    }

    @Override
    public void onScrollChanged(int l, int t, int oldl, int oldt) {
        if (!mInOverScrollMode) {
            sendOurVisibleRect();
            // update WebKit if visible title bar height changed. The logic is same
            // as getVisibleTitleHeightImpl.
            int titleHeight = getTitleHeight();
            if (Math.max(titleHeight - t, 0) != Math.max(titleHeight - oldt, 0)) {
                sendViewSizeZoom(false);
            }
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        switch (event.getAction()) {
            case KeyEvent.ACTION_DOWN:
                mKeysPressed.add(Integer.valueOf(event.getKeyCode()));
                break;
            case KeyEvent.ACTION_MULTIPLE:
                // Always accept the action.
                break;
            case KeyEvent.ACTION_UP:
                int location = mKeysPressed.indexOf(Integer.valueOf(event.getKeyCode()));
                if (location == -1) {
                    // We did not receive the key down for this key, so do not
                    // handle the key up.
                    return false;
                } else {
                    // We did receive the key down.  Handle the key up, and
                    // remove it from our pressed keys.
                    mKeysPressed.remove(location);
                }
                break;
            default:
                // Accept the action.  This should not happen, unless a new
                // action is added to KeyEvent.
                break;
        }
        return mWebViewPrivate.super_dispatchKeyEvent(event);
    }
    
    private static final int SNAP_BOUND = 16;
    private static int sChannelDistance = 16;
    private int mFirstTouchX = -1; // the first touched point
    private int mFirstTouchY = -1;
    private int mDistanceX = 0;
    private int mDistanceY = 0;

    private boolean inFullScreenMode() {
        return mFullScreenHolder != null;
    }

    private void dismissFullScreenMode() {
        if (inFullScreenMode()) {
            mFullScreenHolder.hide();
            mFullScreenHolder = null;
            invalidate();
        }
    }

    void onPinchToZoomAnimationStart() {
        // cancel the single touch handling
        cancelTouch();
        onZoomAnimationStart();
    }

    void onPinchToZoomAnimationEnd(ScaleGestureDetector detector) {
        onZoomAnimationEnd();
        // start a drag, TOUCH_PINCH_DRAG, can't use TOUCH_INIT_MODE as
        // it may trigger the unwanted click, can't use TOUCH_DRAG_MODE
        // as it may trigger the unwanted fling.
        mTouchMode = TOUCH_PINCH_DRAG;
        mConfirmMove = true;
        startTouch(detector.getFocusX(), detector.getFocusY(), mLastTouchTime);
    }

    // See if there is a layer at x, y and switch to TOUCH_DRAG_LAYER_MODE if a
    // layer is found.
    private void startScrollingLayer(float x, float y) {
        if (mNativeClass == 0)
            return;

        int contentX = viewToContentX((int) x + getScrollX());
        int contentY = viewToContentY((int) y + getScrollY());
        mCurrentScrollingLayerId = nativeScrollableLayer(mNativeClass,
                contentX, contentY, mScrollingLayerRect, mScrollingLayerBounds);
        if (mCurrentScrollingLayerId != 0) {
            mTouchMode = TOUCH_DRAG_LAYER_MODE;
        }
    }

    // 1/(density * density) used to compute the distance between points.
    // Computed in init().
    private float DRAG_LAYER_INVERSE_DENSITY_SQUARED;

    // The distance between two points reported in onTouchEvent scaled by the
    // density of the screen.
    private static final int DRAG_LAYER_FINGER_DISTANCE = 20000;

    @Override
    public boolean onHoverEvent(MotionEvent event) {
        if (mNativeClass == 0) {
            return false;
        }
        int x = viewToContentX((int) event.getX() + getScrollX());
        int y = viewToContentY((int) event.getY() + getScrollY());
        mWebViewCore.sendMessage(EventHub.SET_MOVE_MOUSE, x, y);
        mWebViewPrivate.super_onHoverEvent(event);
        return true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (mNativeClass == 0 || (!mWebView.isClickable() && !mWebView.isLongClickable())) {
            return false;
        }

        if (mInputDispatcher == null) {
            return false;
        }

        if (mWebView.isFocusable() && mWebView.isFocusableInTouchMode()
                && !mWebView.isFocused()) {
            mWebView.requestFocus();
        }

        if (mInputDispatcher.postPointerEvent(ev, getScrollX(),
                getScrollY() - getTitleHeight(), mZoomManager.getInvScale())) {
            mInputDispatcher.dispatchUiEvents();
            return true;
        } else {
            Log.w(LOGTAG, "mInputDispatcher rejected the event!");
            return false;
        }
    }

    /*
    * Common code for single touch and multi-touch.
    * (x, y) denotes current focus point, which is the touch point for single touch
    * and the middle point for multi-touch.
    */
    private void handleTouchEventCommon(MotionEvent event, int action, int x, int y) {
        ScaleGestureDetector detector = mZoomManager.getScaleGestureDetector();

        long eventTime = event.getEventTime();

        // Due to the touch screen edge effect, a touch closer to the edge
        // always snapped to the edge. As getViewWidth() can be different from
        // getWidth() due to the scrollbar, adjusting the point to match
        // getViewWidth(). Same applied to the height.
        x = Math.min(x, getViewWidth() - 1);
        y = Math.min(y, getViewHeightWithTitle() - 1);

        int deltaX = mLastTouchX - x;
        int deltaY = mLastTouchY - y;
        int contentX = viewToContentX(x + getScrollX());
        int contentY = viewToContentY(y + getScrollY());

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                mConfirmMove = false;

                // Channel Scrolling
                mFirstTouchX = x;
                mFirstTouchY = y;
                mDistanceX = mDistanceY = 0;

                if (!mEditTextScroller.isFinished()) {
                    mEditTextScroller.abortAnimation();
                }
                if (!mScroller.isFinished()) {
                    // stop the current scroll animation, but if this is
                    // the start of a fling, allow it to add to the current
                    // fling's velocity
                    mScroller.abortAnimation();
                    mTouchMode = TOUCH_DRAG_START_MODE;
                    mConfirmMove = true;
                    nativeSetIsScrolling(false);
                } else if (mPrivateHandler.hasMessages(RELEASE_SINGLE_TAP)) {
                    mPrivateHandler.removeMessages(RELEASE_SINGLE_TAP);
                    removeTouchHighlight();
                    if (deltaX * deltaX + deltaY * deltaY < mDoubleTapSlopSquare) {
                        mTouchMode = TOUCH_DOUBLE_TAP_MODE;
                    } else {
                        mTouchMode = TOUCH_INIT_MODE;
                    }
                } else { // the normal case
                    mTouchMode = TOUCH_INIT_MODE;
                    if (mLogEvent && eventTime - mLastTouchUpTime < 1000) {
                        EventLog.writeEvent(EventLogTags.BROWSER_DOUBLE_TAP_DURATION,
                                (eventTime - mLastTouchUpTime), eventTime);
                    }
                    mSelectionStarted = false;
                    if (mSelectingText) {
                        ensureSelectionHandles();
                        int shiftedY = y - getTitleHeight() + getScrollY();
                        int shiftedX = x + getScrollX();
                        if (mSelectHandleBaseBounds.contains(shiftedX, shiftedY)) {
                            mSelectionStarted = true;
                            mSelectDraggingCursor = mSelectCursorBase;
                            mSelectDraggingTextQuad = mSelectCursorBaseTextQuad;
                            if (mIsCaretSelection) {
                                mPrivateHandler.removeMessages(CLEAR_CARET_HANDLE);
                                hidePasteButton();
                            }
                        } else if (mSelectHandleExtentBounds
                                .contains(shiftedX, shiftedY)) {
                            mSelectionStarted = true;
                            mSelectDraggingCursor = mSelectCursorExtent;
                            mSelectDraggingTextQuad = mSelectCursorExtentTextQuad;
                        } else if (mIsCaretSelection) {
                            selectionDone();
                        }
                        if (DebugFlags.WEB_VIEW) {
                            Log.v(LOGTAG, "select=" + contentX + "," + contentY);
                        }
                    }
                }
                // Trigger the link
                if (!mSelectingText && (mTouchMode == TOUCH_INIT_MODE
                        || mTouchMode == TOUCH_DOUBLE_TAP_MODE)) {
                    mPrivateHandler.sendEmptyMessageDelayed(
                            SWITCH_TO_SHORTPRESS, TAP_TIMEOUT);
                    mPrivateHandler.sendEmptyMessageDelayed(
                            SWITCH_TO_LONGPRESS, LONG_PRESS_TIMEOUT);
                }
                startTouch(x, y, eventTime);
                if (mIsEditingText) {
                    mTouchInEditText = mEditTextContentBounds
                            .contains(contentX, contentY);
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!mConfirmMove && (deltaX * deltaX + deltaY * deltaY)
                        >= mTouchSlopSquare) {
                    mPrivateHandler.removeMessages(SWITCH_TO_SHORTPRESS);
                    mPrivateHandler.removeMessages(SWITCH_TO_LONGPRESS);
                    mConfirmMove = true;
                    if (mTouchMode == TOUCH_DOUBLE_TAP_MODE) {
                        mTouchMode = TOUCH_INIT_MODE;
                    }
                    removeTouchHighlight();
                }
                if (mSelectingText && mSelectionStarted) {
                    if (DebugFlags.WEB_VIEW) {
                        Log.v(LOGTAG, "extend=" + contentX + "," + contentY);
                    }
                    ViewParent parent = mWebView.getParent();
                    if (parent != null) {
                        parent.requestDisallowInterceptTouchEvent(true);
                    }
                    if (deltaX != 0 || deltaY != 0) {
                        int handleX = contentX +
                                viewToContentDimension(mSelectOffset.x);
                        int handleY = contentY +
                                viewToContentDimension(mSelectOffset.y);
                        mSelectDraggingCursor.set(handleX, handleY);
                        boolean inCursorText =
                                mSelectDraggingTextQuad.containsPoint(handleX, handleY);
                        boolean inEditBounds = mEditTextContentBounds
                                .contains(handleX, handleY);
                        if (mIsEditingText && !inEditBounds) {
                            beginScrollEdit();
                        } else {
                            endScrollEdit();
                        }
                        boolean snapped = false;
                        if (inCursorText || (mIsEditingText && !inEditBounds)) {
                            snapDraggingCursor();
                            snapped = true;
                        }
                        updateWebkitSelection(snapped);
                        if (!inCursorText && mIsEditingText && inEditBounds) {
                            // Visually snap even if we have moved the handle.
                            snapDraggingCursor();
                        }
                        mLastTouchX = x;
                        mLastTouchY = y;
                        invalidate();
                    }
                    break;
                }

                if (mTouchMode == TOUCH_DONE_MODE) {
                    // no dragging during scroll zoom animation, or when prevent
                    // default is yes
                    break;
                }
                if (mVelocityTracker == null) {
                    Log.e(LOGTAG, "Got null mVelocityTracker when "
                            + " mTouchMode = " + mTouchMode);
                } else {
                    mVelocityTracker.addMovement(event);
                }

                if (mTouchMode != TOUCH_DRAG_MODE &&
                        mTouchMode != TOUCH_DRAG_LAYER_MODE &&
                        mTouchMode != TOUCH_DRAG_TEXT_MODE) {

                    if (!mConfirmMove) {
                        break;
                    }

                    if ((detector == null || !detector.isInProgress())
                            && SNAP_NONE == mSnapScrollMode) {
                        int ax = Math.abs(x - mFirstTouchX);
                        int ay = Math.abs(y - mFirstTouchY);
                        if (ax < SNAP_BOUND && ay < SNAP_BOUND) {
                            break;
                        } else if (ax < SNAP_BOUND) {
                            mSnapScrollMode = SNAP_Y;
                        } else if (ay < SNAP_BOUND) {
                            mSnapScrollMode = SNAP_X;
                        }
                    }

                    mTouchMode = TOUCH_DRAG_MODE;
                    mLastTouchX = x;
                    mLastTouchY = y;
                    deltaX = 0;
                    deltaY = 0;

                    startScrollingLayer(x, y);
                    startDrag();
                }

                // do pan
                boolean keepScrollBarsVisible = false;
                if (deltaX == 0 && deltaY == 0) {
                    keepScrollBarsVisible = true;
                } else {
                    if (mSnapScrollMode == SNAP_X || mSnapScrollMode == SNAP_Y) {
                        mDistanceX += Math.abs(deltaX);
                        mDistanceY += Math.abs(deltaY);
                        if (mSnapScrollMode == SNAP_X) {
                            if (mDistanceY > sChannelDistance) {
                                mSnapScrollMode = SNAP_NONE;
                            } else if (mDistanceX > sChannelDistance) {
                                mDistanceX = mDistanceY = 0;
                        }
                    } else {
                            if (mDistanceX > sChannelDistance) {
                                mSnapScrollMode = SNAP_NONE;
                            } else if (mDistanceY > sChannelDistance) {
                                mDistanceX = mDistanceY = 0;
                            }
                        }
                    }
                    if (mSnapScrollMode != SNAP_NONE) {
                        if ((mSnapScrollMode & SNAP_X) == SNAP_X) {
                            deltaY = 0;
                        } else {
                            deltaX = 0;
                        }
                    }
                    if (deltaX * deltaX + deltaY * deltaY > mTouchSlopSquare) {
                        mHeldMotionless = MOTIONLESS_FALSE;
                    } else {
                        mHeldMotionless = MOTIONLESS_TRUE;
                        keepScrollBarsVisible = true;
                    }

                    mLastTouchTime = eventTime;
                    boolean allDrag = doDrag(deltaX, deltaY);
                    if (allDrag) {
                        mLastTouchX = x;
                        mLastTouchY = y;
                    } else {
                        int contentDeltaX = (int)Math.floor(deltaX * mZoomManager.getInvScale());
                        int roundedDeltaX = contentToViewDimension(contentDeltaX);
                        int contentDeltaY = (int)Math.floor(deltaY * mZoomManager.getInvScale());
                        int roundedDeltaY = contentToViewDimension(contentDeltaY);
                        mLastTouchX -= roundedDeltaX;
                        mLastTouchY -= roundedDeltaY;
                    }
                }

                break;
            }
            case MotionEvent.ACTION_UP: {
                mFirstTouchX  = mFirstTouchY = -1;
                if (mIsEditingText && mSelectionStarted) {
                    endScrollEdit();
                    mPrivateHandler.sendEmptyMessageDelayed(SCROLL_HANDLE_INTO_VIEW,
                            TEXT_SCROLL_FIRST_SCROLL_MS);
                    if (!mConfirmMove && mIsCaretSelection) {
                        showPasteWindow();
                        stopTouch();
                        break;
                    }
                }
                mLastTouchUpTime = eventTime;
                if (mSentAutoScrollMessage) {
                    mAutoScrollX = mAutoScrollY = 0;
                }
                switch (mTouchMode) {
                    case TOUCH_DOUBLE_TAP_MODE: // double tap
                        mPrivateHandler.removeMessages(SWITCH_TO_SHORTPRESS);
                        mPrivateHandler.removeMessages(SWITCH_TO_LONGPRESS);
                        mTouchMode = TOUCH_DONE_MODE;
                        break;
                    case TOUCH_INIT_MODE: // tap
                    case TOUCH_SHORTPRESS_START_MODE:
                    case TOUCH_SHORTPRESS_MODE:
                        mPrivateHandler.removeMessages(SWITCH_TO_SHORTPRESS);
                        mPrivateHandler.removeMessages(SWITCH_TO_LONGPRESS);
                        if (!mConfirmMove) {
                            if (mSelectingText) {
                                // tapping on selection or controls does nothing
                                if (!mSelectionStarted) {
                                    selectionDone();
                                }
                                break;
                            }
                            // only trigger double tap if the WebView is
                            // scalable
                            if (mTouchMode == TOUCH_INIT_MODE
                                    && (canZoomIn() || canZoomOut())) {
                                mPrivateHandler.sendEmptyMessageDelayed(
                                        RELEASE_SINGLE_TAP, ViewConfiguration
                                                .getDoubleTapTimeout());
                            }
                            break;
                        }
                    case TOUCH_DRAG_MODE:
                    case TOUCH_DRAG_LAYER_MODE:
                    case TOUCH_DRAG_TEXT_MODE:
                        mPrivateHandler.removeMessages(DRAG_HELD_MOTIONLESS);
                        // if the user waits a while w/o moving before the
                        // up, we don't want to do a fling
                        if (eventTime - mLastTouchTime <= MIN_FLING_TIME) {
                            if (mVelocityTracker == null) {
                                Log.e(LOGTAG, "Got null mVelocityTracker");
                            } else {
                                mVelocityTracker.addMovement(event);
                            }
                            // set to MOTIONLESS_IGNORE so that it won't keep
                            // removing and sending message in
                            // drawCoreAndCursorRing()
                            mHeldMotionless = MOTIONLESS_IGNORE;
                            doFling();
                            break;
                        } else {
                            if (mScroller.springBack(getScrollX(), getScrollY(), 0,
                                    computeMaxScrollX(), 0,
                                    computeMaxScrollY())) {
                                invalidate();
                            }
                        }
                        // redraw in high-quality, as we're done dragging
                        mHeldMotionless = MOTIONLESS_TRUE;
                        invalidate();
                        // fall through
                    case TOUCH_DRAG_START_MODE:
                        // TOUCH_DRAG_START_MODE should not happen for the real
                        // device as we almost certain will get a MOVE. But this
                        // is possible on emulator.
                        mLastVelocity = 0;
                        WebViewCore.resumePriority();
                        if (!mSelectingText) {
                            WebViewCore.resumeUpdatePicture(mWebViewCore);
                        }
                        break;
                }
                stopTouch();
                break;
            }
            case MotionEvent.ACTION_CANCEL: {
                if (mTouchMode == TOUCH_DRAG_MODE) {
                    mScroller.springBack(getScrollX(), getScrollY(), 0,
                            computeMaxScrollX(), 0, computeMaxScrollY());
                    invalidate();
                }
                cancelTouch();
                break;
            }
        }
    }

    /**
     * Returns the text scroll speed in content pixels per millisecond based on
     * the touch location.
     * @param coordinate The x or y touch coordinate in content space
     * @param min The minimum coordinate (x or y) of the edit content bounds
     * @param max The maximum coordinate (x or y) of the edit content bounds
     */
    private static float getTextScrollSpeed(int coordinate, int min, int max) {
        if (coordinate < min) {
            return (coordinate - min) * TEXT_SCROLL_RATE;
        } else if (coordinate >= max) {
            return (coordinate - max + 1) * TEXT_SCROLL_RATE;
        } else {
            return 0.0f;
        }
    }

    private static int getSelectionCoordinate(int coordinate, int min, int max) {
        return Math.max(Math.min(coordinate, max), min);
    }

    private void beginScrollEdit() {
        if (mLastEditScroll == 0) {
            mLastEditScroll = SystemClock.uptimeMillis() -
                    TEXT_SCROLL_FIRST_SCROLL_MS;
            scrollEditWithCursor();
        }
    }

    private void scrollDraggedSelectionHandleIntoView() {
        if (mSelectDraggingCursor == null) {
            return;
        }
        int x = mSelectDraggingCursor.x;
        int y = mSelectDraggingCursor.y;
        if (!mEditTextContentBounds.contains(x,y)) {
            int left = Math.min(0, x - mEditTextContentBounds.left - EDIT_RECT_BUFFER);
            int right = Math.max(0, x - mEditTextContentBounds.right + EDIT_RECT_BUFFER);
            int deltaX = left + right;
            int above = Math.min(0, y - mEditTextContentBounds.top - EDIT_RECT_BUFFER);
            int below = Math.max(0, y - mEditTextContentBounds.bottom + EDIT_RECT_BUFFER);
            int deltaY = above + below;
            if (deltaX != 0 || deltaY != 0) {
                int scrollX = getTextScrollX() + deltaX;
                int scrollY = getTextScrollY() + deltaY;
                scrollX = clampBetween(scrollX, 0, getMaxTextScrollX());
                scrollY = clampBetween(scrollY, 0, getMaxTextScrollY());
                scrollEditText(scrollX, scrollY);
            }
        }
    }

    private void endScrollEdit() {
        mLastEditScroll = 0;
    }

    private static int clampBetween(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private static int getTextScrollDelta(float speed, long deltaT) {
        float distance = speed * deltaT;
        int intDistance = (int)Math.floor(distance);
        float probability = distance - intDistance;
        if (Math.random() < probability) {
            intDistance++;
        }
        return intDistance;
    }
    /**
     * Scrolls edit text a distance based on the last touch point,
     * the last scroll time, and the edit text content bounds.
     */
    private void scrollEditWithCursor() {
        if (mLastEditScroll != 0) {
            int x = viewToContentX(mLastTouchX + getScrollX() + mSelectOffset.x);
            float scrollSpeedX = getTextScrollSpeed(x, mEditTextContentBounds.left,
                    mEditTextContentBounds.right);
            int y = viewToContentY(mLastTouchY + getScrollY() + mSelectOffset.y);
            float scrollSpeedY = getTextScrollSpeed(y, mEditTextContentBounds.top,
                    mEditTextContentBounds.bottom);
            if (scrollSpeedX == 0.0f && scrollSpeedY == 0.0f) {
                endScrollEdit();
            } else {
                long currentTime = SystemClock.uptimeMillis();
                long timeSinceLastUpdate = currentTime - mLastEditScroll;
                int deltaX = getTextScrollDelta(scrollSpeedX, timeSinceLastUpdate);
                int deltaY = getTextScrollDelta(scrollSpeedY, timeSinceLastUpdate);
                int scrollX = getTextScrollX() + deltaX;
                scrollX = clampBetween(scrollX, 0, getMaxTextScrollX());
                int scrollY = getTextScrollY() + deltaY;
                scrollY = clampBetween(scrollY, 0, getMaxTextScrollY());

                mLastEditScroll = currentTime;
                if (scrollX == getTextScrollX() && scrollY == getTextScrollY()) {
                    // By probability no text scroll this time. Try again later.
                    mPrivateHandler.sendEmptyMessageDelayed(SCROLL_EDIT_TEXT,
                            TEXT_SCROLL_FIRST_SCROLL_MS);
                } else {
                    int selectionX = getSelectionCoordinate(x,
                            mEditTextContentBounds.left, mEditTextContentBounds.right);
                    int selectionY = getSelectionCoordinate(y,
                            mEditTextContentBounds.top, mEditTextContentBounds.bottom);
                    int oldX = mSelectDraggingCursor.x;
                    int oldY = mSelectDraggingCursor.y;
                    mSelectDraggingCursor.set(selectionX, selectionY);
                    updateWebkitSelection(false);
                    scrollEditText(scrollX, scrollY);
                    mSelectDraggingCursor.set(oldX, oldY);
                }
            }
        }
    }

    private void startTouch(float x, float y, long eventTime) {
        // Remember where the motion event started
        mStartTouchX = mLastTouchX = Math.round(x);
        mStartTouchY = mLastTouchY = Math.round(y);
        mLastTouchTime = eventTime;
        mVelocityTracker = VelocityTracker.obtain();
        mSnapScrollMode = SNAP_NONE;
    }

    private void startDrag() {
        WebViewCore.reducePriority();
        // to get better performance, pause updating the picture
        WebViewCore.pauseUpdatePicture(mWebViewCore);
        nativeSetIsScrolling(true);

        if (mHorizontalScrollBarMode != SCROLLBAR_ALWAYSOFF
                || mVerticalScrollBarMode != SCROLLBAR_ALWAYSOFF) {
            mZoomManager.invokeZoomPicker();
        }
    }

    private boolean doDrag(int deltaX, int deltaY) {
        boolean allDrag = true;
        if ((deltaX | deltaY) != 0) {
            int oldX = getScrollX();
            int oldY = getScrollY();
            int rangeX = computeMaxScrollX();
            int rangeY = computeMaxScrollY();
            final int contentX = (int)Math.floor(deltaX * mZoomManager.getInvScale());
            final int contentY = (int)Math.floor(deltaY * mZoomManager.getInvScale());

            // Assume page scrolling and change below if we're wrong
            mTouchMode = TOUCH_DRAG_MODE;

            // Check special scrolling before going to main page scrolling.
            if (mIsEditingText && mTouchInEditText && canTextScroll(deltaX, deltaY)) {
                // Edit text scrolling
                oldX = getTextScrollX();
                rangeX = getMaxTextScrollX();
                deltaX = contentX;
                oldY = getTextScrollY();
                rangeY = getMaxTextScrollY();
                deltaY = contentY;
                mTouchMode = TOUCH_DRAG_TEXT_MODE;
                allDrag = false;
            } else if (mCurrentScrollingLayerId != 0) {
                // Check the scrolling bounds to see if we will actually do any
                // scrolling.  The rectangle is in document coordinates.
                final int maxX = mScrollingLayerRect.right;
                final int maxY = mScrollingLayerRect.bottom;
                final int resultX = clampBetween(maxX, 0,
                        mScrollingLayerRect.left + contentX);
                final int resultY = clampBetween(maxY, 0,
                        mScrollingLayerRect.top + contentY);

                if (resultX != mScrollingLayerRect.left
                        || resultY != mScrollingLayerRect.top
                        || (contentX | contentY) == 0) {
                    // In case we switched to dragging the page.
                    mTouchMode = TOUCH_DRAG_LAYER_MODE;
                    deltaX = contentX;
                    deltaY = contentY;
                    oldX = mScrollingLayerRect.left;
                    oldY = mScrollingLayerRect.top;
                    rangeX = maxX;
                    rangeY = maxY;
                    allDrag = false;
                }
            }

            if (mOverScrollGlow != null) {
                mOverScrollGlow.setOverScrollDeltas(deltaX, deltaY);
            }

            mWebViewPrivate.overScrollBy(deltaX, deltaY, oldX, oldY,
                    rangeX, rangeY,
                    mOverscrollDistance, mOverscrollDistance, true);
            if (mOverScrollGlow != null && mOverScrollGlow.isAnimating()) {
                invalidate();
            }
        }
        mZoomManager.keepZoomPickerVisible();
        return allDrag;
    }

    private void stopTouch() {
        if (mScroller.isFinished() && !mSelectingText
                && (mTouchMode == TOUCH_DRAG_MODE
                || mTouchMode == TOUCH_DRAG_LAYER_MODE)) {
            WebViewCore.resumePriority();
            WebViewCore.resumeUpdatePicture(mWebViewCore);
            nativeSetIsScrolling(false);
        }

        // we also use mVelocityTracker == null to tell us that we are
        // not "moving around", so we can take the slower/prettier
        // mode in the drawing code
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }

        // Release any pulled glows
        if (mOverScrollGlow != null) {
            mOverScrollGlow.releaseAll();
        }

        if (mSelectingText) {
            mSelectionStarted = false;
            syncSelectionCursors();
            if (mIsCaretSelection) {
                resetCaretTimer();
            }
            invalidate();
        }
    }

    private void cancelTouch() {
        // we also use mVelocityTracker == null to tell us that we are
        // not "moving around", so we can take the slower/prettier
        // mode in the drawing code
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }

        if ((mTouchMode == TOUCH_DRAG_MODE
                || mTouchMode == TOUCH_DRAG_LAYER_MODE) && !mSelectingText) {
            WebViewCore.resumePriority();
            WebViewCore.resumeUpdatePicture(mWebViewCore);
            nativeSetIsScrolling(false);
        }
        mPrivateHandler.removeMessages(SWITCH_TO_SHORTPRESS);
        mPrivateHandler.removeMessages(SWITCH_TO_LONGPRESS);
        mPrivateHandler.removeMessages(DRAG_HELD_MOTIONLESS);
        removeTouchHighlight();
        mHeldMotionless = MOTIONLESS_TRUE;
        mTouchMode = TOUCH_DONE_MODE;
    }

    private void snapDraggingCursor() {
        float scale = scaleAlongSegment(
                mSelectDraggingCursor.x, mSelectDraggingCursor.y,
                mSelectDraggingTextQuad.p4, mSelectDraggingTextQuad.p3);
        // clamp scale to ensure point is on the bottom segment
        scale = Math.max(0.0f, scale);
        scale = Math.min(scale, 1.0f);
        float newX = scaleCoordinate(scale,
                mSelectDraggingTextQuad.p4.x, mSelectDraggingTextQuad.p3.x);
        float newY = scaleCoordinate(scale,
                mSelectDraggingTextQuad.p4.y, mSelectDraggingTextQuad.p3.y);
        int x = Math.round(newX);
        int y = Math.round(newY);
        if (mIsEditingText) {
            x = clampBetween(x, mEditTextContentBounds.left,
                    mEditTextContentBounds.right);
            y = clampBetween(y, mEditTextContentBounds.top,
                    mEditTextContentBounds.bottom);
        }
        mSelectDraggingCursor.set(x, y);
    }

    private static float scaleCoordinate(float scale, float coord1, float coord2) {
        float diff = coord2 - coord1;
        return coord1 + (scale * diff);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_CLASS_POINTER) != 0) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_SCROLL: {
                    final float vscroll;
                    final float hscroll;
                    if ((event.getMetaState() & KeyEvent.META_SHIFT_ON) != 0) {
                        vscroll = 0;
                        hscroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                    } else {
                        vscroll = -event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                        hscroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL);
                    }
                    if (hscroll != 0 || vscroll != 0) {
                        final int vdelta = (int) (vscroll *
                                mWebViewPrivate.getVerticalScrollFactor());
                        final int hdelta = (int) (hscroll *
                                mWebViewPrivate.getHorizontalScrollFactor());

                        abortAnimation();
                        int oldTouchMode = mTouchMode;
                        startScrollingLayer(event.getX(), event.getY());
                        doDrag(hdelta, vdelta);
                        mTouchMode = oldTouchMode;
                        return true;
                    }
                }
            }
        }
        return mWebViewPrivate.super_onGenericMotionEvent(event);
    }

    private long mTrackballFirstTime = 0;
    private long mTrackballLastTime = 0;
    private float mTrackballRemainsX = 0.0f;
    private float mTrackballRemainsY = 0.0f;
    private int mTrackballXMove = 0;
    private int mTrackballYMove = 0;
    private boolean mSelectingText = false;
    private boolean mShowTextSelectionExtra = false;
    private boolean mSelectionStarted = false;
    private static final int TRACKBALL_KEY_TIMEOUT = 1000;
    private static final int TRACKBALL_TIMEOUT = 200;
    private static final int TRACKBALL_WAIT = 100;
    private static final int TRACKBALL_SCALE = 400;
    private static final int TRACKBALL_SCROLL_COUNT = 5;
    private static final int TRACKBALL_MOVE_COUNT = 10;
    private static final int TRACKBALL_MULTIPLIER = 3;
    private static final int SELECT_CURSOR_OFFSET = 16;
    private static final int SELECT_SCROLL = 5;
    private int mSelectX = 0;
    private int mSelectY = 0;
    private boolean mTrackballDown = false;
    private long mTrackballUpTime = 0;
    private long mLastCursorTime = 0;
    private Rect mLastCursorBounds;
    private SelectionHandleAlpha mBaseAlpha = new SelectionHandleAlpha();
    private SelectionHandleAlpha mExtentAlpha = new SelectionHandleAlpha();
    private ObjectAnimator mBaseHandleAlphaAnimator =
            ObjectAnimator.ofInt(mBaseAlpha, "alpha", 0);
    private ObjectAnimator mExtentHandleAlphaAnimator =
            ObjectAnimator.ofInt(mExtentAlpha, "alpha", 0);

    // Set by default; BrowserActivity clears to interpret trackball data
    // directly for movement. Currently, the framework only passes
    // arrow key events, not trackball events, from one child to the next
    private boolean mMapTrackballToArrowKeys = true;

    private DrawData mDelaySetPicture;
    private DrawData mLoadedPicture;

    @Override
    public void setMapTrackballToArrowKeys(boolean setMap) {
        mMapTrackballToArrowKeys = setMap;
    }

    void resetTrackballTime() {
        mTrackballLastTime = 0;
    }

    @Override
    public boolean onTrackballEvent(MotionEvent ev) {
        long time = ev.getEventTime();
        if ((ev.getMetaState() & KeyEvent.META_ALT_ON) != 0) {
            if (ev.getY() > 0) pageDown(true);
            if (ev.getY() < 0) pageUp(true);
            return true;
        }
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            if (mSelectingText) {
                return true; // discard press if copy in progress
            }
            mTrackballDown = true;
            if (mNativeClass == 0) {
                return false;
            }
            if (DebugFlags.WEB_VIEW) {
                Log.v(LOGTAG, "onTrackballEvent down ev=" + ev
                        + " time=" + time
                        + " mLastCursorTime=" + mLastCursorTime);
            }
            if (mWebView.isInTouchMode()) mWebView.requestFocusFromTouch();
            return false; // let common code in onKeyDown at it
        }
        if (ev.getAction() == MotionEvent.ACTION_UP) {
            // LONG_PRESS_CENTER is set in common onKeyDown
            mPrivateHandler.removeMessages(LONG_PRESS_CENTER);
            mTrackballDown = false;
            mTrackballUpTime = time;
            if (mSelectingText) {
                copySelection();
                selectionDone();
                return true; // discard press if copy in progress
            }
            if (DebugFlags.WEB_VIEW) {
                Log.v(LOGTAG, "onTrackballEvent up ev=" + ev
                        + " time=" + time
                );
            }
            return false; // let common code in onKeyUp at it
        }
        if ((mMapTrackballToArrowKeys && (ev.getMetaState() & KeyEvent.META_SHIFT_ON) == 0) ||
                AccessibilityManager.getInstance(mContext).isEnabled()) {
            if (DebugFlags.WEB_VIEW) Log.v(LOGTAG, "onTrackballEvent gmail quit");
            return false;
        }
        if (mTrackballDown) {
            if (DebugFlags.WEB_VIEW) Log.v(LOGTAG, "onTrackballEvent down quit");
            return true; // discard move if trackball is down
        }
        if (time - mTrackballUpTime < TRACKBALL_TIMEOUT) {
            if (DebugFlags.WEB_VIEW) Log.v(LOGTAG, "onTrackballEvent up timeout quit");
            return true;
        }
        // TODO: alternatively we can do panning as touch does
        switchOutDrawHistory();
        if (time - mTrackballLastTime > TRACKBALL_TIMEOUT) {
            if (DebugFlags.WEB_VIEW) {
                Log.v(LOGTAG, "onTrackballEvent time="
                        + time + " last=" + mTrackballLastTime);
            }
            mTrackballFirstTime = time;
            mTrackballXMove = mTrackballYMove = 0;
        }
        mTrackballLastTime = time;
        if (DebugFlags.WEB_VIEW) {
            Log.v(LOGTAG, "onTrackballEvent ev=" + ev + " time=" + time);
        }
        mTrackballRemainsX += ev.getX();
        mTrackballRemainsY += ev.getY();
        doTrackball(time, ev.getMetaState());
        return true;
    }

    private int scaleTrackballX(float xRate, int width) {
        int xMove = (int) (xRate / TRACKBALL_SCALE * width);
        int nextXMove = xMove;
        if (xMove > 0) {
            if (xMove > mTrackballXMove) {
                xMove -= mTrackballXMove;
            }
        } else if (xMove < mTrackballXMove) {
            xMove -= mTrackballXMove;
        }
        mTrackballXMove = nextXMove;
        return xMove;
    }

    private int scaleTrackballY(float yRate, int height) {
        int yMove = (int) (yRate / TRACKBALL_SCALE * height);
        int nextYMove = yMove;
        if (yMove > 0) {
            if (yMove > mTrackballYMove) {
                yMove -= mTrackballYMove;
            }
        } else if (yMove < mTrackballYMove) {
            yMove -= mTrackballYMove;
        }
        mTrackballYMove = nextYMove;
        return yMove;
    }

    private int keyCodeToSoundsEffect(int keyCode) {
        switch(keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                return SoundEffectConstants.NAVIGATION_UP;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return SoundEffectConstants.NAVIGATION_RIGHT;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return SoundEffectConstants.NAVIGATION_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return SoundEffectConstants.NAVIGATION_LEFT;
        }
        return 0;
    }

    private void doTrackball(long time, int metaState) {
        int elapsed = (int) (mTrackballLastTime - mTrackballFirstTime);
        if (elapsed == 0) {
            elapsed = TRACKBALL_TIMEOUT;
        }
        float xRate = mTrackballRemainsX * 1000 / elapsed;
        float yRate = mTrackballRemainsY * 1000 / elapsed;
        int viewWidth = getViewWidth();
        int viewHeight = getViewHeight();
        float ax = Math.abs(xRate);
        float ay = Math.abs(yRate);
        float maxA = Math.max(ax, ay);
        if (DebugFlags.WEB_VIEW) {
            Log.v(LOGTAG, "doTrackball elapsed=" + elapsed
                    + " xRate=" + xRate
                    + " yRate=" + yRate
                    + " mTrackballRemainsX=" + mTrackballRemainsX
                    + " mTrackballRemainsY=" + mTrackballRemainsY);
        }
        int width = mContentWidth - viewWidth;
        int height = mContentHeight - viewHeight;
        if (width < 0) width = 0;
        if (height < 0) height = 0;
        ax = Math.abs(mTrackballRemainsX * TRACKBALL_MULTIPLIER);
        ay = Math.abs(mTrackballRemainsY * TRACKBALL_MULTIPLIER);
        maxA = Math.max(ax, ay);
        int count = Math.max(0, (int) maxA);
        int oldScrollX = getScrollX();
        int oldScrollY = getScrollY();
        if (count > 0) {
            int selectKeyCode = ax < ay ? mTrackballRemainsY < 0 ?
                    KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN :
                    mTrackballRemainsX < 0 ? KeyEvent.KEYCODE_DPAD_LEFT :
                    KeyEvent.KEYCODE_DPAD_RIGHT;
            count = Math.min(count, TRACKBALL_MOVE_COUNT);
            if (DebugFlags.WEB_VIEW) {
                Log.v(LOGTAG, "doTrackball keyCode=" + selectKeyCode
                        + " count=" + count
                        + " mTrackballRemainsX=" + mTrackballRemainsX
                        + " mTrackballRemainsY=" + mTrackballRemainsY);
            }
            if (mNativeClass != 0) {
                for (int i = 0; i < count; i++) {
                    letPageHandleNavKey(selectKeyCode, time, true, metaState);
                }
                letPageHandleNavKey(selectKeyCode, time, false, metaState);
            }
            mTrackballRemainsX = mTrackballRemainsY = 0;
        }
        if (count >= TRACKBALL_SCROLL_COUNT) {
            int xMove = scaleTrackballX(xRate, width);
            int yMove = scaleTrackballY(yRate, height);
            if (DebugFlags.WEB_VIEW) {
                Log.v(LOGTAG, "doTrackball pinScrollBy"
                        + " count=" + count
                        + " xMove=" + xMove + " yMove=" + yMove
                        + " mScrollX-oldScrollX=" + (getScrollX()-oldScrollX)
                        + " mScrollY-oldScrollY=" + (getScrollY()-oldScrollY)
                        );
            }
            if (Math.abs(getScrollX() - oldScrollX) > Math.abs(xMove)) {
                xMove = 0;
            }
            if (Math.abs(getScrollY() - oldScrollY) > Math.abs(yMove)) {
                yMove = 0;
            }
            if (xMove != 0 || yMove != 0) {
                pinScrollBy(xMove, yMove, true, 0);
            }
        }
    }

    /**
     * Compute the maximum horizontal scroll position. Used by {@link OverScrollGlow}.
     * @return Maximum horizontal scroll position within real content
     */
    int computeMaxScrollX() {
        return Math.max(computeRealHorizontalScrollRange() - getViewWidth(), 0);
    }

    /**
     * Compute the maximum vertical scroll position. Used by {@link OverScrollGlow}.
     * @return Maximum vertical scroll position within real content
     */
    int computeMaxScrollY() {
        return Math.max(computeRealVerticalScrollRange() + getTitleHeight()
                - getViewHeightWithTitle(), 0);
    }

    boolean updateScrollCoordinates(int x, int y) {
        int oldX = getScrollX();
        int oldY = getScrollY();
        setScrollXRaw(x);
        setScrollYRaw(y);
        if (oldX != getScrollX() || oldY != getScrollY()) {
            mWebViewPrivate.onScrollChanged(getScrollX(), getScrollY(), oldX, oldY);
            return true;
        } else {
            return false;
        }
    }

    @Override
    public void flingScroll(int vx, int vy) {
        mScroller.fling(getScrollX(), getScrollY(), vx, vy, 0, computeMaxScrollX(), 0,
                computeMaxScrollY(), mOverflingDistance, mOverflingDistance);
        invalidate();
    }

    private void doFling() {
        if (mVelocityTracker == null) {
            return;
        }
        int maxX = computeMaxScrollX();
        int maxY = computeMaxScrollY();

        mVelocityTracker.computeCurrentVelocity(1000, mMaximumFling);
        int vx = (int) mVelocityTracker.getXVelocity();
        int vy = (int) mVelocityTracker.getYVelocity();

        int scrollX = getScrollX();
        int scrollY = getScrollY();
        int overscrollDistance = mOverscrollDistance;
        int overflingDistance = mOverflingDistance;

        // Use the layer's scroll data if applicable.
        if (mTouchMode == TOUCH_DRAG_LAYER_MODE) {
            scrollX = mScrollingLayerRect.left;
            scrollY = mScrollingLayerRect.top;
            maxX = mScrollingLayerRect.right;
            maxY = mScrollingLayerRect.bottom;
            // No overscrolling for layers.
            overscrollDistance = overflingDistance = 0;
        } else if (mTouchMode == TOUCH_DRAG_TEXT_MODE) {
            scrollX = getTextScrollX();
            scrollY = getTextScrollY();
            maxX = getMaxTextScrollX();
            maxY = getMaxTextScrollY();
            // No overscrolling for edit text.
            overscrollDistance = overflingDistance = 0;
        }

        if (mSnapScrollMode != SNAP_NONE) {
            if ((mSnapScrollMode & SNAP_X) == SNAP_X) {
                vy = 0;
            } else {
                vx = 0;
            }
        }
        if ((maxX == 0 && vy == 0) || (maxY == 0 && vx == 0)) {
            WebViewCore.resumePriority();
            if (!mSelectingText) {
                WebViewCore.resumeUpdatePicture(mWebViewCore);
            }
            if (mScroller.springBack(scrollX, scrollY, 0, maxX, 0, maxY)) {
                invalidate();
            }
            return;
        }
        float currentVelocity = mScroller.getCurrVelocity();
        float velocity = (float) Math.hypot(vx, vy);
        if (mLastVelocity > 0 && currentVelocity > 0 && velocity
                > mLastVelocity * MINIMUM_VELOCITY_RATIO_FOR_ACCELERATION) {
            float deltaR = (float) (Math.abs(Math.atan2(mLastVelY, mLastVelX)
                    - Math.atan2(vy, vx)));
            final float circle = (float) (Math.PI) * 2.0f;
            if (deltaR > circle * 0.9f || deltaR < circle * 0.1f) {
                vx += currentVelocity * mLastVelX / mLastVelocity;
                vy += currentVelocity * mLastVelY / mLastVelocity;
                velocity = (float) Math.hypot(vx, vy);
                if (DebugFlags.WEB_VIEW) {
                    Log.v(LOGTAG, "doFling vx= " + vx + " vy=" + vy);
                }
            } else if (DebugFlags.WEB_VIEW) {
                Log.v(LOGTAG, "doFling missed " + deltaR / circle);
            }
        } else if (DebugFlags.WEB_VIEW) {
            Log.v(LOGTAG, "doFling start last=" + mLastVelocity
                    + " current=" + currentVelocity
                    + " vx=" + vx + " vy=" + vy
                    + " maxX=" + maxX + " maxY=" + maxY
                    + " scrollX=" + scrollX + " scrollY=" + scrollY
                    + " layer=" + mCurrentScrollingLayerId);
        }

        // Allow sloppy flings without overscrolling at the edges.
        if ((scrollX == 0 || scrollX == maxX) && Math.abs(vx) < Math.abs(vy)) {
            vx = 0;
        }
        if ((scrollY == 0 || scrollY == maxY) && Math.abs(vy) < Math.abs(vx)) {
            vy = 0;
        }

        if (overscrollDistance < overflingDistance) {
            if ((vx > 0 && scrollX == -overscrollDistance) ||
                    (vx < 0 && scrollX == maxX + overscrollDistance)) {
                vx = 0;
            }
            if ((vy > 0 && scrollY == -overscrollDistance) ||
                    (vy < 0 && scrollY == maxY + overscrollDistance)) {
                vy = 0;
            }
        }

        mLastVelX = vx;
        mLastVelY = vy;
        mLastVelocity = velocity;

        // no horizontal overscroll if the content just fits
        mScroller.fling(scrollX, scrollY, -vx, -vy, 0, maxX, 0, maxY,
                maxX == 0 ? 0 : overflingDistance, overflingDistance);

        invalidate();
    }

    /**
     * See {@link WebView#getZoomControls()}
     */
    @Override
    @Deprecated
    public View getZoomControls() {
        if (!getSettings().supportZoom()) {
            Log.w(LOGTAG, "This WebView doesn't support zoom.");
            return null;
        }
        return mZoomManager.getExternalZoomPicker();
    }

    void dismissZoomControl() {
        mZoomManager.dismissZoomPicker();
    }

    float getDefaultZoomScale() {
        return mZoomManager.getDefaultScale();
    }

    /**
     * Return the overview scale of the WebView
     * @return The overview scale.
     */
    float getZoomOverviewScale() {
        return mZoomManager.getZoomOverviewScale();
    }

    /**
     * See {@link WebView#canZoomIn()}
     */
    @Override
    public boolean canZoomIn() {
        return mZoomManager.canZoomIn();
    }

    /**
     * See {@link WebView#canZoomOut()}
     */
    @Override
    public boolean canZoomOut() {
        return mZoomManager.canZoomOut();
    }

    /**
     * See {@link WebView#zoomIn()}
     */
    @Override
    public boolean zoomIn() {
        return mZoomManager.zoomIn();
    }

    /**
     * See {@link WebView#zoomOut()}
     */
    @Override
    public boolean zoomOut() {
        return mZoomManager.zoomOut();
    }

    /*
     * Return true if the rect (e.g. plugin) is fully visible and maximized
     * inside the WebView.
     */
    boolean isRectFitOnScreen(Rect rect) {
        final int rectWidth = rect.width();
        final int rectHeight = rect.height();
        final int viewWidth = getViewWidth();
        final int viewHeight = getViewHeightWithTitle();
        float scale = Math.min((float) viewWidth / rectWidth, (float) viewHeight / rectHeight);
        scale = mZoomManager.computeScaleWithLimits(scale);
        return !mZoomManager.willScaleTriggerZoom(scale)
                && contentToViewX(rect.left) >= getScrollX()
                && contentToViewX(rect.right) <= getScrollX() + viewWidth
                && contentToViewY(rect.top) >= getScrollY()
                && contentToViewY(rect.bottom) <= getScrollY() + viewHeight;
    }

    /*
     * Maximize and center the rectangle, specified in the document coordinate
     * space, inside the WebView. If the zoom doesn't need to be changed, do an
     * animated scroll to center it. If the zoom needs to be changed, find the
     * zoom center and do a smooth zoom transition. The rect is in document
     * coordinates
     */
    void centerFitRect(Rect rect) {
        final int rectWidth = rect.width();
        final int rectHeight = rect.height();
        final int viewWidth = getViewWidth();
        final int viewHeight = getViewHeightWithTitle();
        float scale = Math.min((float) viewWidth / rectWidth, (float) viewHeight
                / rectHeight);
        scale = mZoomManager.computeScaleWithLimits(scale);
        if (!mZoomManager.willScaleTriggerZoom(scale)) {
            pinScrollTo(contentToViewX(rect.left + rectWidth / 2) - viewWidth / 2,
                    contentToViewY(rect.top + rectHeight / 2) - viewHeight / 2,
                    true, 0);
        } else {
            float actualScale = mZoomManager.getScale();
            float oldScreenX = rect.left * actualScale - getScrollX();
            float rectViewX = rect.left * scale;
            float rectViewWidth = rectWidth * scale;
            float newMaxWidth = mContentWidth * scale;
            float newScreenX = (viewWidth - rectViewWidth) / 2;
            // pin the newX to the WebView
            if (newScreenX > rectViewX) {
                newScreenX = rectViewX;
            } else if (newScreenX > (newMaxWidth - rectViewX - rectViewWidth)) {
                newScreenX = viewWidth - (newMaxWidth - rectViewX);
            }
            float zoomCenterX = (oldScreenX * scale - newScreenX * actualScale)
                    / (scale - actualScale);
            float oldScreenY = rect.top * actualScale + getTitleHeight()
                    - getScrollY();
            float rectViewY = rect.top * scale + getTitleHeight();
            float rectViewHeight = rectHeight * scale;
            float newMaxHeight = mContentHeight * scale + getTitleHeight();
            float newScreenY = (viewHeight - rectViewHeight) / 2;
            // pin the newY to the WebView
            if (newScreenY > rectViewY) {
                newScreenY = rectViewY;
            } else if (newScreenY > (newMaxHeight - rectViewY - rectViewHeight)) {
                newScreenY = viewHeight - (newMaxHeight - rectViewY);
            }
            float zoomCenterY = (oldScreenY * scale - newScreenY * actualScale)
                    / (scale - actualScale);
            mZoomManager.setZoomCenter(zoomCenterX, zoomCenterY);
            mZoomManager.startZoomAnimation(scale, false);
        }
    }

    // Called by JNI to handle a touch on a node representing an email address,
    // address, or phone number
    private void overrideLoading(String url) {
        mCallbackProxy.uiOverrideUrlLoading(url);
    }

    @Override
    public boolean requestFocus(int direction, Rect previouslyFocusedRect) {
        // Check if we are destroyed
        if (mWebViewCore == null) return false;
        // FIXME: If a subwindow is showing find, and the user touches the
        // background window, it can steal focus.
        if (mFindIsUp) return false;
        boolean result = false;
        result = mWebViewPrivate.super_requestFocus(direction, previouslyFocusedRect);
        if (mWebViewCore.getSettings().getNeedInitialFocus()
                && !mWebView.isInTouchMode()) {
            // For cases such as GMail, where we gain focus from a direction,
            // we want to move to the first available link.
            // FIXME: If there are no visible links, we may not want to
            int fakeKeyDirection = 0;
            switch(direction) {
                case View.FOCUS_UP:
                    fakeKeyDirection = KeyEvent.KEYCODE_DPAD_UP;
                    break;
                case View.FOCUS_DOWN:
                    fakeKeyDirection = KeyEvent.KEYCODE_DPAD_DOWN;
                    break;
                case View.FOCUS_LEFT:
                    fakeKeyDirection = KeyEvent.KEYCODE_DPAD_LEFT;
                    break;
                case View.FOCUS_RIGHT:
                    fakeKeyDirection = KeyEvent.KEYCODE_DPAD_RIGHT;
                    break;
                default:
                    return result;
            }
            mWebViewCore.sendMessage(EventHub.SET_INITIAL_FOCUS, fakeKeyDirection);
        }
        return result;
    }

    @Override
    public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);

        int measuredHeight = heightSize;
        int measuredWidth = widthSize;

        // Grab the content size from WebViewCore.
        int contentHeight = contentToViewDimension(mContentHeight);
        int contentWidth = contentToViewDimension(mContentWidth);

//        Log.d(LOGTAG, "------- measure " + heightMode);

        if (heightMode != MeasureSpec.EXACTLY) {
            mHeightCanMeasure = true;
            measuredHeight = contentHeight;
            if (heightMode == MeasureSpec.AT_MOST) {
                // If we are larger than the AT_MOST height, then our height can
                // no longer be measured and we should scroll internally.
                if (measuredHeight > heightSize) {
                    measuredHeight = heightSize;
                    mHeightCanMeasure = false;
                    measuredHeight |= View.MEASURED_STATE_TOO_SMALL;
                }
            }
        } else {
            mHeightCanMeasure = false;
        }
        if (mNativeClass != 0) {
            nativeSetHeightCanMeasure(mHeightCanMeasure);
        }
        // For the width, always use the given size unless unspecified.
        if (widthMode == MeasureSpec.UNSPECIFIED) {
            mWidthCanMeasure = true;
            measuredWidth = contentWidth;
        } else {
            if (measuredWidth < contentWidth) {
                measuredWidth |= View.MEASURED_STATE_TOO_SMALL;
            }
            mWidthCanMeasure = false;
        }

        synchronized (this) {
            mWebViewPrivate.setMeasuredDimension(measuredWidth, measuredHeight);
        }
    }

    @Override
    public boolean requestChildRectangleOnScreen(View child,
                                                 Rect rect,
                                                 boolean immediate) {
        if (mNativeClass == 0) {
            return false;
        }
        // don't scroll while in zoom animation. When it is done, we will adjust
        // the necessary components
        if (mZoomManager.isFixedLengthAnimationInProgress()) {
            return false;
        }

        rect.offset(child.getLeft() - child.getScrollX(),
                child.getTop() - child.getScrollY());

        Rect content = new Rect(viewToContentX(getScrollX()),
                viewToContentY(getScrollY()),
                viewToContentX(getScrollX() + getWidth()
                - mWebView.getVerticalScrollbarWidth()),
                viewToContentY(getScrollY() + getViewHeightWithTitle()));
        int screenTop = contentToViewY(content.top);
        int screenBottom = contentToViewY(content.bottom);
        int height = screenBottom - screenTop;
        int scrollYDelta = 0;

        if (rect.bottom > screenBottom) {
            int oneThirdOfScreenHeight = height / 3;
            if (rect.height() > 2 * oneThirdOfScreenHeight) {
                // If the rectangle is too tall to fit in the bottom two thirds
                // of the screen, place it at the top.
                scrollYDelta = rect.top - screenTop;
            } else {
                // If the rectangle will still fit on screen, we want its
                // top to be in the top third of the screen.
                scrollYDelta = rect.top - (screenTop + oneThirdOfScreenHeight);
            }
        } else if (rect.top < screenTop) {
            scrollYDelta = rect.top - screenTop;
        }

        int screenLeft = contentToViewX(content.left);
        int screenRight = contentToViewX(content.right);
        int width = screenRight - screenLeft;
        int scrollXDelta = 0;

        if (rect.right > screenRight && rect.left > screenLeft) {
            if (rect.width() > width) {
                scrollXDelta += (rect.left - screenLeft);
            } else {
                scrollXDelta += (rect.right - screenRight);
            }
        } else if (rect.left < screenLeft) {
            scrollXDelta -= (screenLeft - rect.left);
        }

        if ((scrollYDelta | scrollXDelta) != 0) {
            return pinScrollBy(scrollXDelta, scrollYDelta, !immediate, 0);
        }

        return false;
    }

    /* package */ void replaceTextfieldText(int oldStart, int oldEnd,
            String replace, int newStart, int newEnd) {
        WebViewCore.ReplaceTextData arg = new WebViewCore.ReplaceTextData();
        arg.mReplace = replace;
        arg.mNewStart = newStart;
        arg.mNewEnd = newEnd;
        mTextGeneration++;
        arg.mTextGeneration = mTextGeneration;
        sendBatchableInputMessage(EventHub.REPLACE_TEXT, oldStart, oldEnd, arg);
    }

    /* package */ void passToJavaScript(String currentText, KeyEvent event) {
        // check if mWebViewCore has been destroyed
        if (mWebViewCore == null) {
            return;
        }
        WebViewCore.JSKeyData arg = new WebViewCore.JSKeyData();
        arg.mEvent = event;
        arg.mCurrentText = currentText;
        // Increase our text generation number, and pass it to webcore thread
        mTextGeneration++;
        mWebViewCore.sendMessage(EventHub.PASS_TO_JS, mTextGeneration, 0, arg);
        // WebKit's document state is not saved until about to leave the page.
        // To make sure the host application, like Browser, has the up to date
        // document state when it goes to background, we force to save the
        // document state.
        mWebViewCore.removeMessages(EventHub.SAVE_DOCUMENT_STATE);
        mWebViewCore.sendMessageDelayed(EventHub.SAVE_DOCUMENT_STATE, null, 1000);
    }

    public synchronized WebViewCore getWebViewCore() {
        return mWebViewCore;
    }

    private boolean canTextScroll(int directionX, int directionY) {
        int scrollX = getTextScrollX();
        int scrollY = getTextScrollY();
        int maxScrollX = getMaxTextScrollX();
        int maxScrollY = getMaxTextScrollY();
        boolean canScrollX = (directionX > 0)
                ? (scrollX < maxScrollX)
                : (scrollX > 0);
        boolean canScrollY = (directionY > 0)
                ? (scrollY < maxScrollY)
                : (scrollY > 0);
        return canScrollX || canScrollY;
    }

    private int getTextScrollX() {
        return -mEditTextContent.left;
    }

    private int getTextScrollY() {
        return -mEditTextContent.top;
    }

    private int getMaxTextScrollX() {
        return Math.max(0, mEditTextContent.width() - mEditTextContentBounds.width());
    }

    private int getMaxTextScrollY() {
        return Math.max(0, mEditTextContent.height() - mEditTextContentBounds.height());
    }

    //-------------------------------------------------------------------------
    // Methods can be called from a separate thread, like WebViewCore
    // If it needs to call the View system, it has to send message.
    //-------------------------------------------------------------------------

    /**
     * General handler to receive message coming from webkit thread
     */
    class PrivateHandler extends Handler implements WebViewInputDispatcher.UiCallbacks {
        @Override
        public void handleMessage(Message msg) {
            // exclude INVAL_RECT_MSG_ID since it is frequently output
            if (DebugFlags.WEB_VIEW && msg.what != INVAL_RECT_MSG_ID) {
                if (msg.what >= FIRST_PRIVATE_MSG_ID
                        && msg.what <= LAST_PRIVATE_MSG_ID) {
                    Log.v(LOGTAG, HandlerPrivateDebugString[msg.what
                            - FIRST_PRIVATE_MSG_ID]);
                } else if (msg.what >= FIRST_PACKAGE_MSG_ID
                        && msg.what <= LAST_PACKAGE_MSG_ID) {
                    Log.v(LOGTAG, HandlerPackageDebugString[msg.what
                            - FIRST_PACKAGE_MSG_ID]);
                } else {
                    Log.v(LOGTAG, Integer.toString(msg.what));
                }
            }
            if (mWebViewCore == null) {
                // after WebView's destroy() is called, skip handling messages.
                return;
            }
            if (mBlockWebkitViewMessages
                    && msg.what != WEBCORE_INITIALIZED_MSG_ID) {
                // Blocking messages from webkit
                return;
            }
            switch (msg.what) {
                case REMEMBER_PASSWORD: {
                    mDatabase.setUsernamePassword(
                            msg.getData().getString("host"),
                            msg.getData().getString("username"),
                            msg.getData().getString("password"));
                    ((Message) msg.obj).sendToTarget();
                    break;
                }
                case NEVER_REMEMBER_PASSWORD: {
                    mDatabase.setUsernamePassword(msg.getData().getString("host"), null, null);
                    ((Message) msg.obj).sendToTarget();
                    break;
                }
                case SCROLL_SELECT_TEXT: {
                    if (mAutoScrollX == 0 && mAutoScrollY == 0) {
                        mSentAutoScrollMessage = false;
                        break;
                    }
                    if (mCurrentScrollingLayerId == 0) {
                        pinScrollBy(mAutoScrollX, mAutoScrollY, true, 0);
                    } else {
                        scrollLayerTo(mScrollingLayerRect.left + mAutoScrollX,
                                mScrollingLayerRect.top + mAutoScrollY);
                    }
                    sendEmptyMessageDelayed(
                            SCROLL_SELECT_TEXT, SELECT_SCROLL_INTERVAL);
                    break;
                }
                case SCROLL_TO_MSG_ID: {
                    // arg1 = animate, arg2 = onlyIfImeIsShowing
                    // obj = Point(x, y)
                    if (msg.arg2 == 1) {
                        // This scroll is intended to bring the textfield into
                        // view, but is only necessary if the IME is showing
                        InputMethodManager imm = InputMethodManager.peekInstance();
                        if (imm == null || !imm.isAcceptingText()
                                || !imm.isActive(mWebView)) {
                            break;
                        }
                    }
                    final Point p = (Point) msg.obj;
                    contentScrollTo(p.x, p.y, msg.arg1 == 1);
                    break;
                }
                case UPDATE_ZOOM_RANGE: {
                    WebViewCore.ViewState viewState = (WebViewCore.ViewState) msg.obj;
                    // mScrollX contains the new minPrefWidth
                    mZoomManager.updateZoomRange(viewState, getViewWidth(), viewState.mScrollX);
                    break;
                }
                case UPDATE_ZOOM_DENSITY: {
                    final float density = (Float) msg.obj;
                    mZoomManager.updateDefaultZoomDensity(density);
                    break;
                }
                case NEW_PICTURE_MSG_ID: {
                    // called for new content
                    final WebViewCore.DrawData draw = (WebViewCore.DrawData) msg.obj;
                    setNewPicture(draw, true);
                    break;
                }
                case WEBCORE_INITIALIZED_MSG_ID:
                    // nativeCreate sets mNativeClass to a non-zero value
                    String drawableDir = BrowserFrame.getRawResFilename(
                            BrowserFrame.DRAWABLEDIR, mContext);
                    nativeCreate(msg.arg1, drawableDir, ActivityManager.isHighEndGfx());
                    if (mDelaySetPicture != null) {
                        setNewPicture(mDelaySetPicture, true);
                        mDelaySetPicture = null;
                    }
                    if (mIsPaused) {
                        nativeSetPauseDrawing(mNativeClass, true);
                    }
                    mInputDispatcher = new WebViewInputDispatcher(this,
                            mWebViewCore.getInputDispatcherCallbacks());
                    break;
                case UPDATE_TEXTFIELD_TEXT_MSG_ID:
                    // Make sure that the textfield is currently focused
                    // and representing the same node as the pointer.
                    if (msg.arg2 == mTextGeneration) {
                        String text = (String) msg.obj;
                        if (null == text) {
                            text = "";
                        }
                        if (mInputConnection != null &&
                                mFieldPointer == msg.arg1) {
                            mInputConnection.setTextAndKeepSelection(text);
                        }
                    }
                    break;
                case UPDATE_TEXT_SELECTION_MSG_ID:
                    updateTextSelectionFromMessage(msg.arg1, msg.arg2,
                            (WebViewCore.TextSelectionData) msg.obj);
                    break;
                case TAKE_FOCUS:
                    int direction = msg.arg1;
                    View focusSearch = mWebView.focusSearch(direction);
                    if (focusSearch != null && focusSearch != mWebView) {
                        focusSearch.requestFocus();
                    }
                    break;
                case CLEAR_TEXT_ENTRY:
                    hideSoftKeyboard();
                    break;
                case INVAL_RECT_MSG_ID: {
                    Rect r = (Rect)msg.obj;
                    if (r == null) {
                        invalidate();
                    } else {
                        // we need to scale r from content into view coords,
                        // which viewInvalidate() does for us
                        viewInvalidate(r.left, r.top, r.right, r.bottom);
                    }
                    break;
                }
                case REQUEST_FORM_DATA:
                    if (mFieldPointer == msg.arg1) {
                        ArrayAdapter<String> adapter = (ArrayAdapter<String>)msg.obj;
                        mAutoCompletePopup.setAdapter(adapter);
                    }
                    break;

                case LONG_PRESS_CENTER:
                    // as this is shared by keydown and trackballdown, reset all
                    // the states
                    mGotCenterDown = false;
                    mTrackballDown = false;
                    mWebView.performLongClick();
                    break;

                case WEBCORE_NEED_TOUCH_EVENTS:
                    mInputDispatcher.setWebKitWantsTouchEvents(msg.arg1 != 0);
                    break;

                case REQUEST_KEYBOARD:
                    if (msg.arg1 == 0) {
                        hideSoftKeyboard();
                    } else {
                        displaySoftKeyboard(false);
                    }
                    break;

                case DRAG_HELD_MOTIONLESS:
                    mHeldMotionless = MOTIONLESS_TRUE;
                    invalidate();
                    break;

                case SCREEN_ON:
                    mWebView.setKeepScreenOn(msg.arg1 == 1);
                    break;

                case EXIT_FULLSCREEN_VIDEO:
                    if (mHTML5VideoViewProxy != null) {
                        mHTML5VideoViewProxy.exitFullScreenVideo();
                    }
                    break;

                case SHOW_FULLSCREEN: {
                    View view = (View) msg.obj;
                    int orientation = msg.arg1;
                    int npp = msg.arg2;

                    if (inFullScreenMode()) {
                        Log.w(LOGTAG, "Should not have another full screen.");
                        dismissFullScreenMode();
                    }
                    mFullScreenHolder = new PluginFullScreenHolder(WebViewClassic.this, orientation, npp);
                    mFullScreenHolder.setContentView(view);
                    mFullScreenHolder.show();
                    invalidate();

                    break;
                }
                case HIDE_FULLSCREEN:
                    dismissFullScreenMode();
                    break;

                case SHOW_RECT_MSG_ID: {
                    WebViewCore.ShowRectData data = (WebViewCore.ShowRectData) msg.obj;
                    int left = contentToViewX(data.mLeft);
                    int width = contentToViewDimension(data.mWidth);
                    int maxWidth = contentToViewDimension(data.mContentWidth);
                    int viewWidth = getViewWidth();
                    int x = (int) (left + data.mXPercentInDoc * width -
                                   data.mXPercentInView * viewWidth);
                    if (DebugFlags.WEB_VIEW) {
                        Log.v(LOGTAG, "showRectMsg=(left=" + left + ",width=" +
                              width + ",maxWidth=" + maxWidth +
                              ",viewWidth=" + viewWidth + ",x="
                              + x + ",xPercentInDoc=" + data.mXPercentInDoc +
                              ",xPercentInView=" + data.mXPercentInView+ ")");
                    }
                    // use the passing content width to cap x as the current
                    // mContentWidth may not be updated yet
                    x = Math.max(0,
                            (Math.min(maxWidth, x + viewWidth)) - viewWidth);
                    int top = contentToViewY(data.mTop);
                    int height = contentToViewDimension(data.mHeight);
                    int maxHeight = contentToViewDimension(data.mContentHeight);
                    int viewHeight = getViewHeight();
                    int y = (int) (top + data.mYPercentInDoc * height -
                                   data.mYPercentInView * viewHeight);
                    if (DebugFlags.WEB_VIEW) {
                        Log.v(LOGTAG, "showRectMsg=(top=" + top + ",height=" +
                              height + ",maxHeight=" + maxHeight +
                              ",viewHeight=" + viewHeight + ",y="
                              + y + ",yPercentInDoc=" + data.mYPercentInDoc +
                              ",yPercentInView=" + data.mYPercentInView+ ")");
                    }
                    // use the passing content height to cap y as the current
                    // mContentHeight may not be updated yet
                    y = Math.max(0,
                            (Math.min(maxHeight, y + viewHeight) - viewHeight));
                    // We need to take into account the visible title height
                    // when scrolling since y is an absolute view position.
                    y = Math.max(0, y - getVisibleTitleHeightImpl());
                    mWebView.scrollTo(x, y);
                    }
                    break;

                case CENTER_FIT_RECT:
                    centerFitRect((Rect)msg.obj);
                    break;

                case SET_SCROLLBAR_MODES:
                    mHorizontalScrollBarMode = msg.arg1;
                    mVerticalScrollBarMode = msg.arg2;
                    break;

                case FOCUS_NODE_CHANGED:
                    mIsEditingText = (msg.arg1 == mFieldPointer);
                    if (mAutoCompletePopup != null && !mIsEditingText) {
                        mAutoCompletePopup.clearAdapter();
                    }
                    // fall through to HIT_TEST_RESULT
                case HIT_TEST_RESULT:
                    WebKitHitTest hit = (WebKitHitTest) msg.obj;
                    mFocusedNode = hit;
                    setTouchHighlightRects(hit);
                    setHitTestResult(hit);
                    break;

                case SAVE_WEBARCHIVE_FINISHED:
                    SaveWebArchiveMessage saveMessage = (SaveWebArchiveMessage)msg.obj;
                    if (saveMessage.mCallback != null) {
                        saveMessage.mCallback.onReceiveValue(saveMessage.mResultFile);
                    }
                    break;

                case SET_AUTOFILLABLE:
                    mAutoFillData = (WebViewCore.AutoFillData) msg.obj;
                    if (mInputConnection != null) {
                        mInputConnection.setAutoFillable(mAutoFillData.getQueryId());
                        mAutoCompletePopup.setAutoFillQueryId(mAutoFillData.getQueryId());
                    }
                    break;

                case AUTOFILL_COMPLETE:
                    if (mAutoCompletePopup != null) {
                        ArrayList<String> pastEntries = new ArrayList<String>();
                        mAutoCompletePopup.setAdapter(new ArrayAdapter<String>(
                                mContext,
                                com.android.internal.R.layout.web_text_view_dropdown,
                                pastEntries));
                    }
                    break;

                case COPY_TO_CLIPBOARD:
                    copyToClipboard((String) msg.obj);
                    break;

                case INIT_EDIT_FIELD:
                    if (mInputConnection != null) {
                        TextFieldInitData initData = (TextFieldInitData) msg.obj;
                        mTextGeneration = 0;
                        mFieldPointer = initData.mFieldPointer;
                        mInputConnection.initEditorInfo(initData);
                        mInputConnection.setTextAndKeepSelection(initData.mText);
                        mEditTextContentBounds.set(initData.mContentBounds);
                        mEditTextLayerId = initData.mNodeLayerId;
                        nativeMapLayerRect(mNativeClass, mEditTextLayerId,
                                mEditTextContentBounds);
                        mEditTextContent.set(initData.mClientRect);
                        relocateAutoCompletePopup();
                    }
                    break;

                case REPLACE_TEXT:{
                    String text = (String)msg.obj;
                    int start = msg.arg1;
                    int end = msg.arg2;
                    int cursorPosition = start + text.length();
                    replaceTextfieldText(start, end, text,
                            cursorPosition, cursorPosition);
                    selectionDone();
                    break;
                }

                case UPDATE_MATCH_COUNT: {
                    WebViewCore.FindAllRequest request = (WebViewCore.FindAllRequest)msg.obj;
                    if (request == null) {
                        if (mFindCallback != null) {
                            mFindCallback.updateMatchCount(0, 0, true);
                        }
                    } else if (request == mFindRequest) {
                        int matchCount, matchIndex;
                        synchronized (mFindRequest) {
                            matchCount = request.mMatchCount;
                            matchIndex = request.mMatchIndex;
                        }
                        if (mFindCallback != null) {
                            mFindCallback.updateMatchCount(matchIndex, matchCount, false);
                        }
                        if (mFindListener != null) {
                            mFindListener.onFindResultReceived(matchIndex, matchCount, true);
                        }
                    }
                    break;
                }

                case CLEAR_CARET_HANDLE:
                    if (mIsCaretSelection) {
                        selectionDone();
                    }
                    break;

                case KEY_PRESS:
                    sendBatchableInputMessage(EventHub.KEY_PRESS, msg.arg1, 0, null);
                    break;

                case RELOCATE_AUTO_COMPLETE_POPUP:
                    relocateAutoCompletePopup();
                    break;

                case AUTOFILL_FORM:
                    mWebViewCore.sendMessage(EventHub.AUTOFILL_FORM,
                            msg.arg1, /* unused */0);
                    break;

                case EDIT_TEXT_SIZE_CHANGED:
                    if (msg.arg1 == mFieldPointer) {
                        mEditTextContent.set((Rect)msg.obj);
                    }
                    break;

                case SHOW_CARET_HANDLE:
                    if (!mSelectingText && mIsEditingText && mIsCaretSelection) {
                        setupWebkitSelect();
                        resetCaretTimer();
                        showPasteWindow();
                    }
                    break;

                case UPDATE_CONTENT_BOUNDS:
                    mEditTextContentBounds.set((Rect) msg.obj);
                    nativeMapLayerRect(mNativeClass, mEditTextLayerId,
                            mEditTextContentBounds);
                    break;

                case SCROLL_EDIT_TEXT:
                    scrollEditWithCursor();
                    break;

                case SCROLL_HANDLE_INTO_VIEW:
                    scrollDraggedSelectionHandleIntoView();
                    break;

                default:
                    super.handleMessage(msg);
                    break;
            }
        }

        @Override
        public Looper getUiLooper() {
            return getLooper();
        }

        @Override
        public void dispatchUiEvent(MotionEvent event, int eventType, int flags) {
            onHandleUiEvent(event, eventType, flags);
        }

        @Override
        public Context getContext() {
            return WebViewClassic.this.getContext();
        }

        @Override
        public boolean shouldInterceptTouchEvent(MotionEvent event) {
            if (!mSelectingText) {
                return false;
            }
            ensureSelectionHandles();
            int y = Math.round(event.getY() - getTitleHeight() + getScrollY());
            int x = Math.round(event.getX() + getScrollX());
            boolean isPressingHandle;
            if (mIsCaretSelection) {
                isPressingHandle = mSelectHandleCenter.getBounds()
                        .contains(x, y);
            } else {
                isPressingHandle =
                        mSelectHandleBaseBounds.contains(x, y)
                        || mSelectHandleExtentBounds.contains(x, y);
            }
            return isPressingHandle;
        }

        @Override
        public void showTapHighlight(boolean show) {
            if (mShowTapHighlight != show) {
                mShowTapHighlight = show;
                invalidate();
            }
        }

        @Override
        public void clearPreviousHitTest() {
            setHitTestResult(null);
        }
    }

    private void setHitTestTypeFromUrl(String url) {
        String substr = null;
        if (url.startsWith(SCHEME_GEO)) {
            mInitialHitTestResult.setType(HitTestResult.GEO_TYPE);
            substr = url.substring(SCHEME_GEO.length());
        } else if (url.startsWith(SCHEME_TEL)) {
            mInitialHitTestResult.setType(HitTestResult.PHONE_TYPE);
            substr = url.substring(SCHEME_TEL.length());
        } else if (url.startsWith(SCHEME_MAILTO)) {
            mInitialHitTestResult.setType(HitTestResult.EMAIL_TYPE);
            substr = url.substring(SCHEME_MAILTO.length());
        } else {
            mInitialHitTestResult.setType(HitTestResult.SRC_ANCHOR_TYPE);
            mInitialHitTestResult.setExtra(url);
            return;
        }
        try {
            mInitialHitTestResult.setExtra(URLDecoder.decode(substr, "UTF-8"));
        } catch (Throwable e) {
            Log.w(LOGTAG, "Failed to decode URL! " + substr, e);
            mInitialHitTestResult.setType(HitTestResult.UNKNOWN_TYPE);
        }
    }

    private void setHitTestResult(WebKitHitTest hit) {
        if (hit == null) {
            mInitialHitTestResult = null;
            return;
        }
        mInitialHitTestResult = new HitTestResult();
        if (hit.mLinkUrl != null) {
            setHitTestTypeFromUrl(hit.mLinkUrl);
            if (hit.mImageUrl != null
                    && mInitialHitTestResult.getType() == HitTestResult.SRC_ANCHOR_TYPE) {
                mInitialHitTestResult.setType(HitTestResult.SRC_IMAGE_ANCHOR_TYPE);
                mInitialHitTestResult.setExtra(hit.mImageUrl);
            }
        } else if (hit.mImageUrl != null) {
            mInitialHitTestResult.setType(HitTestResult.IMAGE_TYPE);
            mInitialHitTestResult.setExtra(hit.mImageUrl);
        } else if (hit.mEditable) {
            mInitialHitTestResult.setType(HitTestResult.EDIT_TEXT_TYPE);
        } else if (hit.mIntentUrl != null) {
            setHitTestTypeFromUrl(hit.mIntentUrl);
        }
    }

    private boolean shouldDrawHighlightRect() {
        if (mFocusedNode == null || mInitialHitTestResult == null) {
            return false;
        }
        if (mTouchHighlightRegion.isEmpty()) {
            return false;
        }
        if (mFocusedNode.mHasFocus && !mWebView.isInTouchMode()) {
            return mDrawCursorRing && !mFocusedNode.mEditable;
        }
        if (mFocusedNode.mHasFocus && mFocusedNode.mEditable) {
            return false;
        }
        return mShowTapHighlight;
    }


    private FocusTransitionDrawable mFocusTransition = null;
    static class FocusTransitionDrawable extends Drawable {
        Region mPreviousRegion;
        Region mNewRegion;
        float mProgress = 0;
        WebViewClassic mWebView;
        Paint mPaint;
        int mMaxAlpha;
        Point mTranslate;

        public FocusTransitionDrawable(WebViewClassic view) {
            mWebView = view;
            mPaint = new Paint(mWebView.mTouchHightlightPaint);
            mMaxAlpha = mPaint.getAlpha();
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public int getOpacity() {
            return 0;
        }

        public void setProgress(float p) {
            mProgress = p;
            if (mWebView.mFocusTransition == this) {
                if (mProgress == 1f)
                    mWebView.mFocusTransition = null;
                mWebView.invalidate();
            }
        }

        public float getProgress() {
            return mProgress;
        }

        @Override
        public void draw(Canvas canvas) {
            if (mTranslate == null) {
                Rect bounds = mPreviousRegion.getBounds();
                Point from = new Point(bounds.centerX(), bounds.centerY());
                mNewRegion.getBounds(bounds);
                Point to = new Point(bounds.centerX(), bounds.centerY());
                mTranslate = new Point(from.x - to.x, from.y - to.y);
            }
            int alpha = (int) (mProgress * mMaxAlpha);
            RegionIterator iter = new RegionIterator(mPreviousRegion);
            Rect r = new Rect();
            mPaint.setAlpha(mMaxAlpha - alpha);
            float tx = mTranslate.x * mProgress;
            float ty = mTranslate.y * mProgress;
            int save = canvas.save(Canvas.MATRIX_SAVE_FLAG);
            canvas.translate(-tx, -ty);
            while (iter.next(r)) {
                canvas.drawRect(r, mPaint);
            }
            canvas.restoreToCount(save);
            iter = new RegionIterator(mNewRegion);
            r = new Rect();
            mPaint.setAlpha(alpha);
            save = canvas.save(Canvas.MATRIX_SAVE_FLAG);
            tx = mTranslate.x - tx;
            ty = mTranslate.y - ty;
            canvas.translate(tx, ty);
            while (iter.next(r)) {
                canvas.drawRect(r, mPaint);
            }
            canvas.restoreToCount(save);
        }
    };

    private boolean shouldAnimateTo(WebKitHitTest hit) {
        // TODO: Don't be annoying or throw out the animation entirely
        return false;
    }

    private void setTouchHighlightRects(WebKitHitTest hit) {
        FocusTransitionDrawable transition = null;
        if (shouldAnimateTo(hit)) {
            transition = new FocusTransitionDrawable(this);
        }
        Rect[] rects = hit != null ? hit.mTouchRects : null;
        if (!mTouchHighlightRegion.isEmpty()) {
            mWebView.invalidate(mTouchHighlightRegion.getBounds());
            if (transition != null) {
                transition.mPreviousRegion = new Region(mTouchHighlightRegion);
            }
            mTouchHighlightRegion.setEmpty();
        }
        if (rects != null) {
            mTouchHightlightPaint.setColor(hit.mTapHighlightColor);
            for (Rect rect : rects) {
                Rect viewRect = contentToViewRect(rect);
                // some sites, like stories in nytimes.com, set
                // mouse event handler in the top div. It is not
                // user friendly to highlight the div if it covers
                // more than half of the screen.
                if (viewRect.width() < getWidth() >> 1
                        || viewRect.height() < getHeight() >> 1) {
                    mTouchHighlightRegion.union(viewRect);
                } else if (DebugFlags.WEB_VIEW) {
                    Log.d(LOGTAG, "Skip the huge selection rect:"
                            + viewRect);
                }
            }
            mWebView.invalidate(mTouchHighlightRegion.getBounds());
            if (transition != null && transition.mPreviousRegion != null) {
                transition.mNewRegion = new Region(mTouchHighlightRegion);
                mFocusTransition = transition;
                ObjectAnimator animator = ObjectAnimator.ofFloat(
                        mFocusTransition, "progress", 1f);
                animator.start();
            }
        }
    }

    // Interface to allow the profiled WebView to hook the page swap notifications.
    public interface PageSwapDelegate {
        void onPageSwapOccurred(boolean notifyAnimationStarted);
    }

    long mLastSwapTime;
    double mAverageSwapFps;

    /** Called by JNI when pages are swapped (only occurs with hardware
     * acceleration) */
    protected void pageSwapCallback(boolean notifyAnimationStarted) {
        if (DebugFlags.MEASURE_PAGE_SWAP_FPS) {
            long now = System.currentTimeMillis();
            long diff = now - mLastSwapTime;
            mAverageSwapFps = ((1000.0 / diff) + mAverageSwapFps) / 2;
            Log.d(LOGTAG, "page swap fps: " + mAverageSwapFps);
            mLastSwapTime = now;
        }
        mWebViewCore.resumeWebKitDraw();
        if (notifyAnimationStarted) {
            mWebViewCore.sendMessage(EventHub.NOTIFY_ANIMATION_STARTED);
        }
        if (mWebView instanceof PageSwapDelegate) {
            // This provides a hook for ProfiledWebView to observe the tile page swaps.
            ((PageSwapDelegate) mWebView).onPageSwapOccurred(notifyAnimationStarted);
        }

        if (mPictureListener != null) {
            // trigger picture listener for hardware layers. Software layers are
            // triggered in setNewPicture
            Picture picture = mContext.getApplicationInfo().targetSdkVersion <
                    Build.VERSION_CODES.JELLY_BEAN_MR2 ? capturePicture() : null;
            if (DebugFlags.TRACE_CALLBACK) Log.d(CallbackProxy.LOGTAG, "onNewPicture");
            mPictureListener.onNewPicture(getWebView(), picture);
        }
    }

    void setNewPicture(final WebViewCore.DrawData draw, boolean updateBaseLayer) {
        if (mNativeClass == 0) {
            if (mDelaySetPicture != null) {
                throw new IllegalStateException("Tried to setNewPicture with"
                        + " a delay picture already set! (memory leak)");
            }
            // Not initialized yet, delay set
            mDelaySetPicture = draw;
            return;
        }
        WebViewCore.ViewState viewState = draw.mViewState;
        boolean isPictureAfterFirstLayout = viewState != null;

        if (updateBaseLayer) {
            setBaseLayer(draw.mBaseLayer,
                    getSettings().getShowVisualIndicator(),
                    isPictureAfterFirstLayout);
        }
        final Point viewSize = draw.mViewSize;
        // We update the layout (i.e. request a layout from the
        // view system) if the last view size that we sent to
        // WebCore matches the view size of the picture we just
        // received in the fixed dimension.
        final boolean updateLayout = viewSize.x == mLastWidthSent
                && viewSize.y == mLastHeightSent;
        // Don't send scroll event for picture coming from webkit,
        // since the new picture may cause a scroll event to override
        // the saved history scroll position.
        mSendScrollEvent = false;
        recordNewContentSize(draw.mContentSize.x,
                draw.mContentSize.y, updateLayout);
        if (isPictureAfterFirstLayout) {
            // Reset the last sent data here since dealing with new page.
            mLastWidthSent = 0;
            mZoomManager.onFirstLayout(draw);
            int scrollX = viewState.mShouldStartScrolledRight
                    ? getContentWidth() : viewState.mScrollX;
            int scrollY = viewState.mScrollY;
            contentScrollTo(scrollX, scrollY, false);
            if (!mDrawHistory) {
                // As we are on a new page, hide the keyboard
                hideSoftKeyboard();
            }
        }
        mSendScrollEvent = true;

        int functor = 0;
        boolean forceInval = isPictureAfterFirstLayout;
        ViewRootImpl viewRoot = mWebView.getViewRootImpl();
        if (mWebView.isHardwareAccelerated()
                && mWebView.getLayerType() != View.LAYER_TYPE_SOFTWARE
                && viewRoot != null) {
            functor = nativeGetDrawGLFunction(mNativeClass);
            if (functor != 0) {
                // force an invalidate if functor attach not successful
                forceInval |= !viewRoot.attachFunctor(functor);
            }
        }

        if (functor == 0
                || forceInval
                || mWebView.getLayerType() != View.LAYER_TYPE_NONE) {
            // invalidate the screen so that the next repaint will show new content
            // TODO: partial invalidate
            mWebView.invalidate();
        }

        // update the zoom information based on the new picture
        if (mZoomManager.onNewPicture(draw))
            invalidate();

        if (isPictureAfterFirstLayout) {
            mViewManager.postReadyToDrawAll();
        }
        scrollEditWithCursor();

        if (mPictureListener != null) {
            if (!mWebView.isHardwareAccelerated()
                    || mWebView.getLayerType() == View.LAYER_TYPE_SOFTWARE) {
                // trigger picture listener for software layers. Hardware layers are
                // triggered in pageSwapCallback
                Picture picture = mContext.getApplicationInfo().targetSdkVersion <
                        Build.VERSION_CODES.JELLY_BEAN_MR2 ? capturePicture() : null;
                if (DebugFlags.TRACE_CALLBACK) Log.d(CallbackProxy.LOGTAG, "onNewPicture");
                mPictureListener.onNewPicture(getWebView(), picture);
            }
        }
    }

    /**
     * Used when receiving messages for REQUEST_KEYBOARD_WITH_SELECTION_MSG_ID
     * and UPDATE_TEXT_SELECTION_MSG_ID.
     */
    private void updateTextSelectionFromMessage(int nodePointer,
            int textGeneration, WebViewCore.TextSelectionData data) {
        if (textGeneration == mTextGeneration) {
            if (mInputConnection != null && mFieldPointer == nodePointer) {
                mInputConnection.setSelection(data.mStart, data.mEnd);
            }
        }
        nativeSetTextSelection(mNativeClass, data.mSelectTextPtr);

        if ((data.mSelectionReason == TextSelectionData.REASON_ACCESSIBILITY_INJECTOR)
                || (!mSelectingText && data.mStart != data.mEnd
                        && data.mSelectionReason != TextSelectionData.REASON_SELECT_WORD)) {
            selectionDone();
            mShowTextSelectionExtra = true;
            invalidate();
            return;
        }

        if (data.mSelectTextPtr != 0 &&
                (data.mStart != data.mEnd ||
                (mFieldPointer == nodePointer && mFieldPointer != 0) ||
                (nodePointer == 0 && data.mStart == 0 && data.mEnd == 0))) {
            mIsEditingText = (mFieldPointer == nodePointer) && nodePointer != 0;
            mIsCaretSelection = (data.mStart == data.mEnd && nodePointer != 0);
            if (mIsCaretSelection &&
                    (mInputConnection == null ||
                    mInputConnection.getEditable().length() == 0)) {
                // There's no text, don't show caret handle.
                selectionDone();
            } else {
                if (!mSelectingText) {
                    setupWebkitSelect();
                } else {
                    syncSelectionCursors();
                }
                animateHandles();
                if (mIsCaretSelection) {
                    resetCaretTimer();
                }
            }
        } else {
            selectionDone();
        }
        invalidate();
    }

    private void scrollEditText(int scrollX, int scrollY) {
        // Scrollable edit text. Scroll it.
        float maxScrollX = getMaxTextScrollX();
        float scrollPercentX = ((float)scrollX)/maxScrollX;
        mEditTextContent.offsetTo(-scrollX, -scrollY);
        mWebViewCore.removeMessages(EventHub.SCROLL_TEXT_INPUT);
        mWebViewCore.sendMessage(EventHub.SCROLL_TEXT_INPUT, 0,
                scrollY, (Float)scrollPercentX);
        animateHandles();
    }

    private void beginTextBatch() {
        mIsBatchingTextChanges = true;
    }

    private void commitTextBatch() {
        if (mWebViewCore != null) {
            mWebViewCore.sendMessages(mBatchedTextChanges);
        }
        mBatchedTextChanges.clear();
        mIsBatchingTextChanges = false;
    }

    void sendBatchableInputMessage(int what, int arg1, int arg2,
            Object obj) {
        if (mWebViewCore == null) {
            return;
        }
        Message message = Message.obtain(null, what, arg1, arg2, obj);
        if (mIsBatchingTextChanges) {
            mBatchedTextChanges.add(message);
        } else {
            mWebViewCore.sendMessage(message);
        }
    }

    // Class used to use a dropdown for a <select> element
    private class InvokeListBox implements Runnable {
        // Whether the listbox allows multiple selection.
        private boolean     mMultiple;
        // Passed in to a list with multiple selection to tell
        // which items are selected.
        private int[]       mSelectedArray;
        // Passed in to a list with single selection to tell
        // where the initial selection is.
        private int         mSelection;

        private Container[] mContainers;

        // Need these to provide stable ids to my ArrayAdapter,
        // which normally does not have stable ids. (Bug 1250098)
        private class Container extends Object {
            /**
             * Possible values for mEnabled.  Keep in sync with OptionStatus in
             * WebViewCore.cpp
             */
            final static int OPTGROUP = -1;
            final static int OPTION_DISABLED = 0;
            final static int OPTION_ENABLED = 1;

            String  mString;
            int     mEnabled;
            int     mId;

            @Override
            public String toString() {
                return mString;
            }
        }

        /**
         *  Subclass ArrayAdapter so we can disable OptionGroupLabels,
         *  and allow filtering.
         */
        private class MyArrayListAdapter extends ArrayAdapter<Container> {
            public MyArrayListAdapter() {
                super(WebViewClassic.this.mContext,
                        mMultiple ? com.android.internal.R.layout.select_dialog_multichoice :
                        com.android.internal.R.layout.webview_select_singlechoice,
                        mContainers);
            }

            @Override
            public View getView(int position, View convertView,
                    ViewGroup parent) {
                // Always pass in null so that we will get a new CheckedTextView
                // Otherwise, an item which was previously used as an <optgroup>
                // element (i.e. has no check), could get used as an <option>
                // element, which needs a checkbox/radio, but it would not have
                // one.
                convertView = super.getView(position, null, parent);
                Container c = item(position);
                if (c != null && Container.OPTION_ENABLED != c.mEnabled) {
                    // ListView does not draw dividers between disabled and
                    // enabled elements.  Use a LinearLayout to provide dividers
                    LinearLayout layout = new LinearLayout(mContext);
                    layout.setOrientation(LinearLayout.VERTICAL);
                    if (position > 0) {
                        View dividerTop = new View(mContext);
                        dividerTop.setBackgroundResource(
                                android.R.drawable.divider_horizontal_bright);
                        layout.addView(dividerTop);
                    }

                    if (Container.OPTGROUP == c.mEnabled) {
                        // Currently select_dialog_multichoice uses CheckedTextViews.
                        // If that changes, the class cast will no longer be valid.
                        if (mMultiple) {
                            Assert.assertTrue(convertView instanceof CheckedTextView);
                            ((CheckedTextView) convertView).setCheckMarkDrawable(null);
                        }
                    } else {
                        // c.mEnabled == Container.OPTION_DISABLED
                        // Draw the disabled element in a disabled state.
                        convertView.setEnabled(false);
                    }

                    layout.addView(convertView);
                    if (position < getCount() - 1) {
                        View dividerBottom = new View(mContext);
                        dividerBottom.setBackgroundResource(
                                android.R.drawable.divider_horizontal_bright);
                        layout.addView(dividerBottom);
                    }
                    return layout;
                }
                return convertView;
            }

            @Override
            public boolean hasStableIds() {
                // AdapterView's onChanged method uses this to determine whether
                // to restore the old state.  Return false so that the old (out
                // of date) state does not replace the new, valid state.
                return false;
            }

            private Container item(int position) {
                if (position < 0 || position >= getCount()) {
                    return null;
                }
                return getItem(position);
            }

            @Override
            public long getItemId(int position) {
                Container item = item(position);
                if (item == null) {
                    return -1;
                }
                return item.mId;
            }

            @Override
            public boolean areAllItemsEnabled() {
                return false;
            }

            @Override
            public boolean isEnabled(int position) {
                Container item = item(position);
                if (item == null) {
                    return false;
                }
                return Container.OPTION_ENABLED == item.mEnabled;
            }
        }

        private InvokeListBox(String[] array, int[] enabled, int[] selected) {
            mMultiple = true;
            mSelectedArray = selected;

            int length = array.length;
            mContainers = new Container[length];
            for (int i = 0; i < length; i++) {
                mContainers[i] = new Container();
                mContainers[i].mString = array[i];
                mContainers[i].mEnabled = enabled[i];
                mContainers[i].mId = i;
            }
        }

        private InvokeListBox(String[] array, int[] enabled, int selection) {
            mSelection = selection;
            mMultiple = false;

            int length = array.length;
            mContainers = new Container[length];
            for (int i = 0; i < length; i++) {
                mContainers[i] = new Container();
                mContainers[i].mString = array[i];
                mContainers[i].mEnabled = enabled[i];
                mContainers[i].mId = i;
            }
        }

        /*
         * Whenever the data set changes due to filtering, this class ensures
         * that the checked item remains checked.
         */
        private class SingleDataSetObserver extends DataSetObserver {
            private long        mCheckedId;
            private ListView    mListView;
            private Adapter     mAdapter;

            /*
             * Create a new observer.
             * @param id The ID of the item to keep checked.
             * @param l ListView for getting and clearing the checked states
             * @param a Adapter for getting the IDs
             */
            public SingleDataSetObserver(long id, ListView l, Adapter a) {
                mCheckedId = id;
                mListView = l;
                mAdapter = a;
            }

            @Override
            public void onChanged() {
                // The filter may have changed which item is checked.  Find the
                // item that the ListView thinks is checked.
                int position = mListView.getCheckedItemPosition();
                long id = mAdapter.getItemId(position);
                if (mCheckedId != id) {
                    // Clear the ListView's idea of the checked item, since
                    // it is incorrect
                    mListView.clearChoices();
                    // Search for mCheckedId.  If it is in the filtered list,
                    // mark it as checked
                    int count = mAdapter.getCount();
                    for (int i = 0; i < count; i++) {
                        if (mAdapter.getItemId(i) == mCheckedId) {
                            mListView.setItemChecked(i, true);
                            break;
                        }
                    }
                }
            }
        }

        @Override
        public void run() {
            if (mWebViewCore == null
                    || getWebView().getWindowToken() == null
                    || getWebView().getViewRootImpl() == null) {
                // We've been detached and/or destroyed since this was posted
                return;
            }
            final ListView listView = (ListView) LayoutInflater.from(mContext)
                    .inflate(com.android.internal.R.layout.select_dialog, null);
            final MyArrayListAdapter adapter = new MyArrayListAdapter();
            AlertDialog.Builder b = new AlertDialog.Builder(mContext)
                    .setView(listView).setCancelable(true)
                    .setInverseBackgroundForced(true);

            if (mMultiple) {
                b.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        mWebViewCore.sendMessage(
                                EventHub.LISTBOX_CHOICES,
                                adapter.getCount(), 0,
                                listView.getCheckedItemPositions());
                    }});
                b.setNegativeButton(android.R.string.cancel,
                        new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        mWebViewCore.sendMessage(
                                EventHub.SINGLE_LISTBOX_CHOICE, -2, 0);
                }});
            }
            mListBoxDialog = b.create();
            listView.setAdapter(adapter);
            listView.setFocusableInTouchMode(true);
            // There is a bug (1250103) where the checks in a ListView with
            // multiple items selected are associated with the positions, not
            // the ids, so the items do not properly retain their checks when
            // filtered.  Do not allow filtering on multiple lists until
            // that bug is fixed.

            listView.setTextFilterEnabled(!mMultiple);
            if (mMultiple) {
                listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
                int length = mSelectedArray.length;
                for (int i = 0; i < length; i++) {
                    listView.setItemChecked(mSelectedArray[i], true);
                }
            } else {
                listView.setOnItemClickListener(new OnItemClickListener() {
                    @Override
                    public void onItemClick(AdapterView<?> parent, View v,
                            int position, long id) {
                        // Rather than sending the message right away, send it
                        // after the page regains focus.
                        mListBoxMessage = Message.obtain(null,
                                EventHub.SINGLE_LISTBOX_CHOICE, (int) id, 0);
                        if (mListBoxDialog != null) {
                            mListBoxDialog.dismiss();
                            mListBoxDialog = null;
                        }
                    }
                });
                if (mSelection != -1) {
                    listView.setSelection(mSelection);
                    listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
                    listView.setItemChecked(mSelection, true);
                    DataSetObserver observer = new SingleDataSetObserver(
                            adapter.getItemId(mSelection), listView, adapter);
                    adapter.registerDataSetObserver(observer);
                }
            }
            mListBoxDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
                @Override
                public void onCancel(DialogInterface dialog) {
                 if (mWebViewCore != null) {
                    mWebViewCore.sendMessage(
                                EventHub.SINGLE_LISTBOX_CHOICE, -2, 0);
                    }
                    mListBoxDialog = null;
                }
            });
            mListBoxDialog.show();
        }
    }

    private Message mListBoxMessage;

    /*
     * Request a dropdown menu for a listbox with multiple selection.
     *
     * @param array Labels for the listbox.
     * @param enabledArray  State for each element in the list.  See static
     *      integers in Container class.
     * @param selectedArray Which positions are initally selected.
     */
    void requestListBox(String[] array, int[] enabledArray, int[]
            selectedArray) {
        mPrivateHandler.post(
                new InvokeListBox(array, enabledArray, selectedArray));
    }

    /*
     * Request a dropdown menu for a listbox with single selection or a single
     * <select> element.
     *
     * @param array Labels for the listbox.
     * @param enabledArray  State for each element in the list.  See static
     *      integers in Container class.
     * @param selection Which position is initally selected.
     */
    void requestListBox(String[] array, int[] enabledArray, int selection) {
        mPrivateHandler.post(
                new InvokeListBox(array, enabledArray, selection));
    }

    private int getScaledMaxXScroll() {
        int width;
        if (mHeightCanMeasure == false) {
            width = getViewWidth() / 4;
        } else {
            Rect visRect = new Rect();
            calcOurVisibleRect(visRect);
            width = visRect.width() / 2;
        }
        // FIXME the divisor should be retrieved from somewhere
        return viewToContentX(width);
    }

    private int getScaledMaxYScroll() {
        int height;
        if (mHeightCanMeasure == false) {
            height = getViewHeight() / 4;
        } else {
            Rect visRect = new Rect();
            calcOurVisibleRect(visRect);
            height = visRect.height() / 2;
        }
        // FIXME the divisor should be retrieved from somewhere
        // the closest thing today is hard-coded into ScrollView.java
        // (from ScrollView.java, line 363)   int maxJump = height/2;
        return Math.round(height * mZoomManager.getInvScale());
    }

    /**
     * Called by JNI to invalidate view
     */
    private void viewInvalidate() {
        invalidate();
    }

    /**
     * Pass the key directly to the page.  This assumes that
     * nativePageShouldHandleShiftAndArrows() returned true.
     */
    private void letPageHandleNavKey(int keyCode, long time, boolean down, int metaState) {
        int keyEventAction;
        if (down) {
            keyEventAction = KeyEvent.ACTION_DOWN;
        } else {
            keyEventAction = KeyEvent.ACTION_UP;
        }

        KeyEvent event = new KeyEvent(time, time, keyEventAction, keyCode,
                1, (metaState & KeyEvent.META_SHIFT_ON)
                | (metaState & KeyEvent.META_ALT_ON)
                | (metaState & KeyEvent.META_SYM_ON)
                , KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0);
        sendKeyEvent(event);
    }

    private void sendKeyEvent(KeyEvent event) {
        int direction = 0;
        switch (event.getKeyCode()) {
        case KeyEvent.KEYCODE_DPAD_DOWN:
            direction = View.FOCUS_DOWN;
            break;
        case KeyEvent.KEYCODE_DPAD_UP:
            direction = View.FOCUS_UP;
            break;
        case KeyEvent.KEYCODE_DPAD_LEFT:
            direction = View.FOCUS_LEFT;
            break;
        case KeyEvent.KEYCODE_DPAD_RIGHT:
            direction = View.FOCUS_RIGHT;
            break;
        case KeyEvent.KEYCODE_TAB:
            direction = event.isShiftPressed() ? View.FOCUS_BACKWARD : View.FOCUS_FORWARD;
            break;
        }
        if (direction != 0 && mWebView.focusSearch(direction) == null) {
            // Can't take focus in that direction
            direction = 0;
        }
        int eventHubAction = EventHub.KEY_UP;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            eventHubAction = EventHub.KEY_DOWN;
            int sound = keyCodeToSoundsEffect(event.getKeyCode());
            if (sound != 0) {
                mWebView.playSoundEffect(sound);
            }
        }
        sendBatchableInputMessage(eventHubAction, direction, 0, event);
    }

    /**
     * See {@link WebView#setBackgroundColor(int)}
     */
    @Override
    public void setBackgroundColor(int color) {
        mBackgroundColor = color;
        mWebViewCore.sendMessage(EventHub.SET_BACKGROUND_COLOR, color);
    }

    /**
     * Enable the communication b/t the webView and VideoViewProxy
     *
     * only used by the Browser
     */
    public void setHTML5VideoViewProxy(HTML5VideoViewProxy proxy) {
        mHTML5VideoViewProxy = proxy;
    }

    /**
     * Set the time to wait between passing touches to WebCore. See also the
     * TOUCH_SENT_INTERVAL member for further discussion.
     *
     * This is only used by the DRT test application.
     */
    public void setTouchInterval(int interval) {
        mCurrentTouchInterval = interval;
    }

    /**
     * Copy text into the clipboard. This is called indirectly from
     * WebViewCore.
     * @param text The text to put into the clipboard.
     */
    private void copyToClipboard(String text) {
        ClipboardManager cm = (ClipboardManager)mContext
                .getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText(getTitle(), text);
        cm.setPrimaryClip(clip);
    }

    /*package*/ void autoFillForm(int autoFillQueryId) {
        mPrivateHandler.obtainMessage(AUTOFILL_FORM, autoFillQueryId, 0)
            .sendToTarget();
    }

    /* package */ ViewManager getViewManager() {
        return mViewManager;
    }

    /** send content invalidate */
    protected void contentInvalidateAll() {
        if (mWebViewCore != null && !mBlockWebkitViewMessages) {
            mWebViewCore.sendMessage(EventHub.CONTENT_INVALIDATE_ALL);
        }
    }

    /** discard all textures from tiles. Used in Profiled WebView */
    public void discardAllTextures() {
        nativeDiscardAllTextures();
    }

    @Override
    public void setLayerType(int layerType, Paint paint) {
        updateHwAccelerated();
    }

    @Override
    public void preDispatchDraw(Canvas canvas) {
        // no-op for WebViewClassic.
    }

    private void updateHwAccelerated() {
        if (mNativeClass == 0) {
            return;
        }
        boolean hwAccelerated = false;
        if (mWebView.isHardwareAccelerated()
                && mWebView.getLayerType() != View.LAYER_TYPE_SOFTWARE) {
            hwAccelerated = true;
        }

        // result is of type LayerAndroid::InvalidateFlags, non zero means invalidate/redraw
        int result = nativeSetHwAccelerated(mNativeClass, hwAccelerated);
        if (mWebViewCore != null && !mBlockWebkitViewMessages && result != 0) {
            mWebViewCore.contentDraw();
        }
    }

    /**
     * Begin collecting per-tile profiling data
     *
     * only used by profiling tests
     */
    public void tileProfilingStart() {
        nativeTileProfilingStart();
    }
    /**
     * Return per-tile profiling data
     *
     * only used by profiling tests
     */
    public float tileProfilingStop() {
        return nativeTileProfilingStop();
    }

    /** only used by profiling tests */
    public void tileProfilingClear() {
        nativeTileProfilingClear();
    }
    /** only used by profiling tests */
    public int tileProfilingNumFrames() {
        return nativeTileProfilingNumFrames();
    }
    /** only used by profiling tests */
    public int tileProfilingNumTilesInFrame(int frame) {
        return nativeTileProfilingNumTilesInFrame(frame);
    }
    /** only used by profiling tests */
    public int tileProfilingGetInt(int frame, int tile, String key) {
        return nativeTileProfilingGetInt(frame, tile, key);
    }
    /** only used by profiling tests */
    public float tileProfilingGetFloat(int frame, int tile, String key) {
        return nativeTileProfilingGetFloat(frame, tile, key);
    }

    /**
     * Checks the focused content for an editable text field. This can be
     * text input or ContentEditable.
     * @return true if the focused item is an editable text field.
     */
    boolean focusCandidateIsEditableText() {
        if (mFocusedNode != null) {
            return mFocusedNode.mEditable;
        }
        return false;
    }

    // Called via JNI
    private void postInvalidate() {
        mWebView.postInvalidate();
    }

    // Note: must be called before first WebViewClassic is created.
    public static void setShouldMonitorWebCoreThread() {
        WebViewCore.setShouldMonitorWebCoreThread();
    }

    @Override
    public void dumpViewHierarchyWithProperties(BufferedWriter out, int level) {
        int layer = getBaseLayer();
        if (layer != 0) {
            try {
                ByteArrayOutputStream stream = new ByteArrayOutputStream();
                ViewStateSerializer.dumpLayerHierarchy(layer, stream, level);
                stream.close();
                byte[] buf = stream.toByteArray();
                out.write(new String(buf, "ascii"));
            } catch (IOException e) {}
        }
    }

    @Override
    public View findHierarchyView(String className, int hashCode) {
        if (mNativeClass == 0) return null;
        Picture pic = new Picture();
        if (!nativeDumpLayerContentToPicture(mNativeClass, className, hashCode, pic)) {
            return null;
        }
        return new PictureWrapperView(getContext(), pic, mWebView);
    }

    private static class PictureWrapperView extends View {
        Picture mPicture;
        WebView mWebView;

        public PictureWrapperView(Context context, Picture picture, WebView parent) {
            super(context);
            mPicture = picture;
            mWebView = parent;
            setWillNotDraw(false);
            setRight(mPicture.getWidth());
            setBottom(mPicture.getHeight());
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawPicture(mPicture);
        }

        @Override
        public boolean post(Runnable action) {
            return mWebView.post(action);
        }
    }

    private native void     nativeCreate(int ptr, String drawableDir, boolean isHighEndGfx);
    private native void     nativeDebugDump();
    private static native void nativeDestroy(int ptr);

    private native void nativeDraw(Canvas canvas, RectF visibleRect,
            int color, int extra);
    private native void     nativeDumpDisplayTree(String urlOrNull);
    private native boolean  nativeEvaluateLayersAnimations(int nativeInstance);
    private native int      nativeCreateDrawGLFunction(int nativeInstance, Rect invScreenRect,
            Rect screenRect, RectF visibleContentRect, float scale, int extras);
    private native int      nativeGetDrawGLFunction(int nativeInstance);
    private native void     nativeUpdateDrawGLFunction(int nativeInstance, Rect invScreenRect,
            Rect screenRect, RectF visibleContentRect, float scale);
    private native String   nativeGetSelection();
    private native void     nativeSetHeightCanMeasure(boolean measure);
    private native boolean  nativeSetBaseLayer(int nativeInstance,
            int layer, boolean showVisualIndicator, boolean isPictureAfterFirstLayout,
            int scrollingLayer);
    private native int      nativeGetBaseLayer(int nativeInstance);
    private native void     nativeCopyBaseContentToPicture(Picture pict);
    private native boolean     nativeDumpLayerContentToPicture(int nativeInstance,
            String className, int layerId, Picture pict);
    private native boolean  nativeHasContent();
    private native void     nativeStopGL(int ptr);
    private native void     nativeDiscardAllTextures();
    private native void     nativeTileProfilingStart();
    private native float    nativeTileProfilingStop();
    private native void     nativeTileProfilingClear();
    private native int      nativeTileProfilingNumFrames();
    private native int      nativeTileProfilingNumTilesInFrame(int frame);
    private native int      nativeTileProfilingGetInt(int frame, int tile, String key);
    private native float    nativeTileProfilingGetFloat(int frame, int tile, String key);

    private native void     nativeUseHardwareAccelSkia(boolean enabled);

    // Returns a pointer to the scrollable LayerAndroid at the given point.
    private native int      nativeScrollableLayer(int nativeInstance, int x, int y, Rect scrollRect,
            Rect scrollBounds);
    /**
     * Scroll the specified layer.
     * @param nativeInstance Native WebView instance
     * @param layer Id of the layer to scroll, as determined by nativeScrollableLayer.
     * @param newX Destination x position to which to scroll.
     * @param newY Destination y position to which to scroll.
     * @return True if the layer is successfully scrolled.
     */
    private native boolean  nativeScrollLayer(int nativeInstance, int layer, int newX, int newY);
    private native void     nativeSetIsScrolling(boolean isScrolling);
    private native int      nativeGetBackgroundColor(int nativeInstance);
    native boolean  nativeSetProperty(String key, String value);
    native String   nativeGetProperty(String key);
    /**
     * See {@link ComponentCallbacks2} for the trim levels and descriptions
     */
    private static native void     nativeOnTrimMemory(int level);
    private static native void nativeSetPauseDrawing(int instance, boolean pause);
    private static native void nativeSetTextSelection(int instance, int selection);
    private static native int nativeGetHandleLayerId(int instance, int handle,
            Point cursorLocation, QuadF textQuad);
    private static native void nativeMapLayerRect(int instance, int layerId,
            Rect rect);
    // Returns 1 if a layer sync is needed, else 0
    private static native int nativeSetHwAccelerated(int instance, boolean hwAccelerated);
    private static native void nativeFindMaxVisibleRect(int instance, int layerId,
            Rect visibleContentRect);
    private static native boolean nativeIsHandleLeft(int instance, int handleId);
    private static native boolean nativeIsPointVisible(int instance,
            int layerId, int contentX, int contentY);
}
