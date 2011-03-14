/*
 * Copyright (C) 2006 The Android Open Source Project
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

import com.android.internal.util.FastMath;
import com.android.internal.widget.EditableInputConnection;

import org.xmlpull.v1.XmlPullParserException;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.inputmethodservice.ExtractEditText;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.text.BoringLayout;
import android.text.ClipboardManager;
import android.text.DynamicLayout;
import android.text.Editable;
import android.text.GetChars;
import android.text.GraphicsOperations;
import android.text.InputFilter;
import android.text.InputType;
import android.text.Layout;
import android.text.ParcelableSpan;
import android.text.Selection;
import android.text.SpanWatcher;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.SpannedString;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.DateKeyListener;
import android.text.method.DateTimeKeyListener;
import android.text.method.DialerKeyListener;
import android.text.method.DigitsKeyListener;
import android.text.method.KeyListener;
import android.text.method.LinkMovementMethod;
import android.text.method.MetaKeyKeyListener;
import android.text.method.MovementMethod;
import android.text.method.PasswordTransformationMethod;
import android.text.method.SingleLineTransformationMethod;
import android.text.method.TextKeyListener;
import android.text.method.TimeKeyListener;
import android.text.method.TransformationMethod;
import android.text.style.ParagraphStyle;
import android.text.style.URLSpan;
import android.text.style.UpdateAppearance;
import android.text.util.Linkify;
import android.util.AttributeSet;
import android.util.FloatMath;
import android.util.Log;
import android.util.TypedValue;
import android.view.ContextMenu;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewDebug;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewParent;
import android.view.ViewRoot;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.AnimationUtils;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.RemoteViews.RemoteView;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * Displays text to the user and optionally allows them to edit it.  A TextView
 * is a complete text editor, however the basic class is configured to not
 * allow editing; see {@link EditText} for a subclass that configures the text
 * view for editing.
 *
 * <p>
 * <b>XML attributes</b>
 * <p>
 * See {@link android.R.styleable#TextView TextView Attributes},
 * {@link android.R.styleable#View View Attributes}
 *
 * @attr ref android.R.styleable#TextView_text
 * @attr ref android.R.styleable#TextView_bufferType
 * @attr ref android.R.styleable#TextView_hint
 * @attr ref android.R.styleable#TextView_textColor
 * @attr ref android.R.styleable#TextView_textColorHighlight
 * @attr ref android.R.styleable#TextView_textColorHint
 * @attr ref android.R.styleable#TextView_textAppearance
 * @attr ref android.R.styleable#TextView_textColorLink
 * @attr ref android.R.styleable#TextView_textSize
 * @attr ref android.R.styleable#TextView_textScaleX
 * @attr ref android.R.styleable#TextView_typeface
 * @attr ref android.R.styleable#TextView_textStyle
 * @attr ref android.R.styleable#TextView_cursorVisible
 * @attr ref android.R.styleable#TextView_maxLines
 * @attr ref android.R.styleable#TextView_maxHeight
 * @attr ref android.R.styleable#TextView_lines
 * @attr ref android.R.styleable#TextView_height
 * @attr ref android.R.styleable#TextView_minLines
 * @attr ref android.R.styleable#TextView_minHeight
 * @attr ref android.R.styleable#TextView_maxEms
 * @attr ref android.R.styleable#TextView_maxWidth
 * @attr ref android.R.styleable#TextView_ems
 * @attr ref android.R.styleable#TextView_width
 * @attr ref android.R.styleable#TextView_minEms
 * @attr ref android.R.styleable#TextView_minWidth
 * @attr ref android.R.styleable#TextView_gravity
 * @attr ref android.R.styleable#TextView_scrollHorizontally
 * @attr ref android.R.styleable#TextView_password
 * @attr ref android.R.styleable#TextView_singleLine
 * @attr ref android.R.styleable#TextView_selectAllOnFocus
 * @attr ref android.R.styleable#TextView_includeFontPadding
 * @attr ref android.R.styleable#TextView_maxLength
 * @attr ref android.R.styleable#TextView_shadowColor
 * @attr ref android.R.styleable#TextView_shadowDx
 * @attr ref android.R.styleable#TextView_shadowDy
 * @attr ref android.R.styleable#TextView_shadowRadius
 * @attr ref android.R.styleable#TextView_autoLink
 * @attr ref android.R.styleable#TextView_linksClickable
 * @attr ref android.R.styleable#TextView_numeric
 * @attr ref android.R.styleable#TextView_digits
 * @attr ref android.R.styleable#TextView_phoneNumber
 * @attr ref android.R.styleable#TextView_inputMethod
 * @attr ref android.R.styleable#TextView_capitalize
 * @attr ref android.R.styleable#TextView_autoText
 * @attr ref android.R.styleable#TextView_editable
 * @attr ref android.R.styleable#TextView_freezesText
 * @attr ref android.R.styleable#TextView_ellipsize
 * @attr ref android.R.styleable#TextView_drawableTop
 * @attr ref android.R.styleable#TextView_drawableBottom
 * @attr ref android.R.styleable#TextView_drawableRight
 * @attr ref android.R.styleable#TextView_drawableLeft
 * @attr ref android.R.styleable#TextView_drawablePadding
 * @attr ref android.R.styleable#TextView_lineSpacingExtra
 * @attr ref android.R.styleable#TextView_lineSpacingMultiplier
 * @attr ref android.R.styleable#TextView_marqueeRepeatLimit
 * @attr ref android.R.styleable#TextView_inputType
 * @attr ref android.R.styleable#TextView_imeOptions
 * @attr ref android.R.styleable#TextView_privateImeOptions
 * @attr ref android.R.styleable#TextView_imeActionLabel
 * @attr ref android.R.styleable#TextView_imeActionId
 * @attr ref android.R.styleable#TextView_editorExtras
 */
@RemoteView
public class TextView extends View implements ViewTreeObserver.OnPreDrawListener {
    static final String LOG_TAG = "TextView";
    static final boolean DEBUG_EXTRACT = false;
    
    private static int PRIORITY = 100;

    final int[] mTempCoords = new int[2];
    Rect mTempRect;

    private ColorStateList mTextColor;
    private int mCurTextColor;
    private ColorStateList mHintTextColor;
    private ColorStateList mLinkTextColor;
    private int mCurHintTextColor;
    private boolean mFreezesText;
    private boolean mFrozenWithFocus;
    private boolean mTemporaryDetach;
    private boolean mDispatchTemporaryDetach;

    private boolean mEatTouchRelease = false;
    private boolean mScrolled = false;

    private Editable.Factory mEditableFactory = Editable.Factory.getInstance();
    private Spannable.Factory mSpannableFactory = Spannable.Factory.getInstance();

    private float mShadowRadius, mShadowDx, mShadowDy;

    private static final int PREDRAW_NOT_REGISTERED = 0;
    private static final int PREDRAW_PENDING = 1;
    private static final int PREDRAW_DONE = 2;
    private int mPreDrawState = PREDRAW_NOT_REGISTERED;

    private TextUtils.TruncateAt mEllipsize = null;

    // Enum for the "typeface" XML parameter.
    // TODO: How can we get this from the XML instead of hardcoding it here?
    private static final int SANS = 1;
    private static final int SERIF = 2;
    private static final int MONOSPACE = 3;

    // Bitfield for the "numeric" XML parameter.
    // TODO: How can we get this from the XML instead of hardcoding it here?
    private static final int SIGNED = 2;
    private static final int DECIMAL = 4;

    class Drawables {
        final Rect mCompoundRect = new Rect();
        Drawable mDrawableTop, mDrawableBottom, mDrawableLeft, mDrawableRight;
        int mDrawableSizeTop, mDrawableSizeBottom, mDrawableSizeLeft, mDrawableSizeRight;
        int mDrawableWidthTop, mDrawableWidthBottom, mDrawableHeightLeft, mDrawableHeightRight;
        int mDrawablePadding;
    }
    private Drawables mDrawables;

    private CharSequence mError;
    private boolean mErrorWasChanged;
    private ErrorPopup mPopup;
    /**
     * This flag is set if the TextView tries to display an error before it
     * is attached to the window (so its position is still unknown).
     * It causes the error to be shown later, when onAttachedToWindow()
     * is called.
     */
    private boolean mShowErrorAfterAttach;

    private CharWrapper mCharWrapper = null;

    private boolean mSelectionMoved = false;
    private boolean mTouchFocusSelected = false;

    private Marquee mMarquee;
    private boolean mRestartMarquee;

    private int mMarqueeRepeatLimit = 3;

    class InputContentType {
        int imeOptions = EditorInfo.IME_NULL;
        String privateImeOptions;
        CharSequence imeActionLabel;
        int imeActionId;
        Bundle extras;
        OnEditorActionListener onEditorActionListener;
        boolean enterDown;
    }
    InputContentType mInputContentType;

    class InputMethodState {
        Rect mCursorRectInWindow = new Rect();
        RectF mTmpRectF = new RectF();
        float[] mTmpOffset = new float[2];
        ExtractedTextRequest mExtracting;
        final ExtractedText mTmpExtracted = new ExtractedText();
        int mBatchEditNesting;
        boolean mCursorChanged;
        boolean mSelectionModeChanged;
        boolean mContentChanged;
        int mChangedStart, mChangedEnd, mChangedDelta;
    }
    InputMethodState mInputMethodState;

    int mTextSelectHandleLeftRes;
    int mTextSelectHandleRightRes;
    int mTextSelectHandleRes;

    Drawable mSelectHandleLeft;
    Drawable mSelectHandleRight;
    Drawable mSelectHandleCenter;

    // Set when this TextView gained focus with some text selected. Will start selection mode.
    private boolean mCreatedWithASelection = false;

    private boolean mNoContextMenuOnUp = false;

    /*
     * Kick-start the font cache for the zygote process (to pay the cost of
     * initializing freetype for our default font only once).
     */
    static {
        Paint p = new Paint();
        p.setAntiAlias(true);
        // We don't care about the result, just the side-effect of measuring.
        p.measureText("H");
    }

    /**
     * Interface definition for a callback to be invoked when an action is
     * performed on the editor.
     */
    public interface OnEditorActionListener {
        /**
         * Called when an action is being performed.
         *
         * @param v The view that was clicked.
         * @param actionId Identifier of the action.  This will be either the
         * identifier you supplied, or {@link EditorInfo#IME_NULL
         * EditorInfo.IME_NULL} if being called due to the enter key
         * being pressed.
         * @param event If triggered by an enter key, this is the event;
         * otherwise, this is null.
         * @return Return true if you have consumed the action, else false.
         */
        boolean onEditorAction(TextView v, int actionId, KeyEvent event);
    }
    
    public TextView(Context context) {
        this(context, null);
    }

    public TextView(Context context,
                    AttributeSet attrs) {
        this(context, attrs, com.android.internal.R.attr.textViewStyle);
    }

    @SuppressWarnings("deprecation")
    public TextView(Context context,
                    AttributeSet attrs,
                    int defStyle) {
        super(context, attrs, defStyle);
        mText = "";

        mTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        mTextPaint.density = getResources().getDisplayMetrics().density;
        mTextPaint.setCompatibilityScaling(
                getResources().getCompatibilityInfo().applicationScale);
        
        // If we get the paint from the skin, we should set it to left, since
        // the layout always wants it to be left.
        // mTextPaint.setTextAlign(Paint.Align.LEFT);

        mHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mHighlightPaint.setCompatibilityScaling(
                getResources().getCompatibilityInfo().applicationScale);

        mMovement = getDefaultMovementMethod();
        mTransformation = null;

        TypedArray a =
            context.obtainStyledAttributes(
                attrs, com.android.internal.R.styleable.TextView, defStyle, 0);

        int textColorHighlight = 0;
        ColorStateList textColor = null;
        ColorStateList textColorHint = null;
        ColorStateList textColorLink = null;
        int textSize = 15;
        int typefaceIndex = -1;
        int styleIndex = -1;

        /*
         * Look the appearance up without checking first if it exists because
         * almost every TextView has one and it greatly simplifies the logic
         * to be able to parse the appearance first and then let specific tags
         * for this View override it.
         */
        TypedArray appearance = null;
        int ap = a.getResourceId(com.android.internal.R.styleable.TextView_textAppearance, -1);
        if (ap != -1) {
            appearance = context.obtainStyledAttributes(ap,
                                com.android.internal.R.styleable.
                                TextAppearance);
        }
        if (appearance != null) {
            int n = appearance.getIndexCount();
            for (int i = 0; i < n; i++) {
                int attr = appearance.getIndex(i);

                switch (attr) {
                case com.android.internal.R.styleable.TextAppearance_textColorHighlight:
                    textColorHighlight = appearance.getColor(attr, textColorHighlight);
                    break;

                case com.android.internal.R.styleable.TextAppearance_textColor:
                    textColor = appearance.getColorStateList(attr);
                    break;

                case com.android.internal.R.styleable.TextAppearance_textColorHint:
                    textColorHint = appearance.getColorStateList(attr);
                    break;

                case com.android.internal.R.styleable.TextAppearance_textColorLink:
                    textColorLink = appearance.getColorStateList(attr);
                    break;

                case com.android.internal.R.styleable.TextAppearance_textSize:
                    textSize = appearance.getDimensionPixelSize(attr, textSize);
                    break;

                case com.android.internal.R.styleable.TextAppearance_typeface:
                    typefaceIndex = appearance.getInt(attr, -1);
                    break;

                case com.android.internal.R.styleable.TextAppearance_textStyle:
                    styleIndex = appearance.getInt(attr, -1);
                    break;
                }
            }

            appearance.recycle();
        }

        boolean editable = getDefaultEditable();
        CharSequence inputMethod = null;
        int numeric = 0;
        CharSequence digits = null;
        boolean phone = false;
        boolean autotext = false;
        int autocap = -1;
        int buffertype = 0;
        boolean selectallonfocus = false;
        Drawable drawableLeft = null, drawableTop = null, drawableRight = null,
            drawableBottom = null;
        int drawablePadding = 0;
        int ellipsize = -1;
        boolean singleLine = false;
        int maxlength = -1;
        CharSequence text = "";
        CharSequence hint = null;
        int shadowcolor = 0;
        float dx = 0, dy = 0, r = 0;
        boolean password = false;
        int inputType = EditorInfo.TYPE_NULL;

        int n = a.getIndexCount();
        for (int i = 0; i < n; i++) {
            int attr = a.getIndex(i);

            switch (attr) {
            case com.android.internal.R.styleable.TextView_editable:
                editable = a.getBoolean(attr, editable);
                break;

            case com.android.internal.R.styleable.TextView_inputMethod:
                inputMethod = a.getText(attr);
                break;

            case com.android.internal.R.styleable.TextView_numeric:
                numeric = a.getInt(attr, numeric);
                break;

            case com.android.internal.R.styleable.TextView_digits:
                digits = a.getText(attr);
                break;

            case com.android.internal.R.styleable.TextView_phoneNumber:
                phone = a.getBoolean(attr, phone);
                break;

            case com.android.internal.R.styleable.TextView_autoText:
                autotext = a.getBoolean(attr, autotext);
                break;

            case com.android.internal.R.styleable.TextView_capitalize:
                autocap = a.getInt(attr, autocap);
                break;

            case com.android.internal.R.styleable.TextView_bufferType:
                buffertype = a.getInt(attr, buffertype);
                break;

            case com.android.internal.R.styleable.TextView_selectAllOnFocus:
                selectallonfocus = a.getBoolean(attr, selectallonfocus);
                break;

            case com.android.internal.R.styleable.TextView_autoLink:
                mAutoLinkMask = a.getInt(attr, 0);
                break;

            case com.android.internal.R.styleable.TextView_linksClickable:
                mLinksClickable = a.getBoolean(attr, true);
                break;

            case com.android.internal.R.styleable.TextView_drawableLeft:
                drawableLeft = a.getDrawable(attr);
                break;

            case com.android.internal.R.styleable.TextView_drawableTop:
                drawableTop = a.getDrawable(attr);
                break;

            case com.android.internal.R.styleable.TextView_drawableRight:
                drawableRight = a.getDrawable(attr);
                break;

            case com.android.internal.R.styleable.TextView_drawableBottom:
                drawableBottom = a.getDrawable(attr);
                break;

            case com.android.internal.R.styleable.TextView_drawablePadding:
                drawablePadding = a.getDimensionPixelSize(attr, drawablePadding);
                break;

            case com.android.internal.R.styleable.TextView_maxLines:
                setMaxLines(a.getInt(attr, -1));
                break;

            case com.android.internal.R.styleable.TextView_maxHeight:
                setMaxHeight(a.getDimensionPixelSize(attr, -1));
                break;

            case com.android.internal.R.styleable.TextView_lines:
                setLines(a.getInt(attr, -1));
                break;

            case com.android.internal.R.styleable.TextView_height:
                setHeight(a.getDimensionPixelSize(attr, -1));
                break;

            case com.android.internal.R.styleable.TextView_minLines:
                setMinLines(a.getInt(attr, -1));
                break;

            case com.android.internal.R.styleable.TextView_minHeight:
                setMinHeight(a.getDimensionPixelSize(attr, -1));
                break;

            case com.android.internal.R.styleable.TextView_maxEms:
                setMaxEms(a.getInt(attr, -1));
                break;

            case com.android.internal.R.styleable.TextView_maxWidth:
                setMaxWidth(a.getDimensionPixelSize(attr, -1));
                break;

            case com.android.internal.R.styleable.TextView_ems:
                setEms(a.getInt(attr, -1));
                break;

            case com.android.internal.R.styleable.TextView_width:
                setWidth(a.getDimensionPixelSize(attr, -1));
                break;

            case com.android.internal.R.styleable.TextView_minEms:
                setMinEms(a.getInt(attr, -1));
                break;

            case com.android.internal.R.styleable.TextView_minWidth:
                setMinWidth(a.getDimensionPixelSize(attr, -1));
                break;

            case com.android.internal.R.styleable.TextView_gravity:
                setGravity(a.getInt(attr, -1));
                break;

            case com.android.internal.R.styleable.TextView_hint:
                hint = a.getText(attr);
                break;

            case com.android.internal.R.styleable.TextView_text:
                text = a.getText(attr);
                break;

            case com.android.internal.R.styleable.TextView_scrollHorizontally:
                if (a.getBoolean(attr, false)) {
                    setHorizontallyScrolling(true);
                }
                break;

            case com.android.internal.R.styleable.TextView_singleLine:
                singleLine = a.getBoolean(attr, singleLine);
                break;

            case com.android.internal.R.styleable.TextView_ellipsize:
                ellipsize = a.getInt(attr, ellipsize);
                break;

            case com.android.internal.R.styleable.TextView_marqueeRepeatLimit:
                setMarqueeRepeatLimit(a.getInt(attr, mMarqueeRepeatLimit));
                break;

            case com.android.internal.R.styleable.TextView_includeFontPadding:
                if (!a.getBoolean(attr, true)) {
                    setIncludeFontPadding(false);
                }
                break;

            case com.android.internal.R.styleable.TextView_cursorVisible:
                if (!a.getBoolean(attr, true)) {
                    setCursorVisible(false);
                }
                break;

            case com.android.internal.R.styleable.TextView_maxLength:
                maxlength = a.getInt(attr, -1);
                break;

            case com.android.internal.R.styleable.TextView_textScaleX:
                setTextScaleX(a.getFloat(attr, 1.0f));
                break;

            case com.android.internal.R.styleable.TextView_freezesText:
                mFreezesText = a.getBoolean(attr, false);
                break;

            case com.android.internal.R.styleable.TextView_shadowColor:
                shadowcolor = a.getInt(attr, 0);
                break;

            case com.android.internal.R.styleable.TextView_shadowDx:
                dx = a.getFloat(attr, 0);
                break;

            case com.android.internal.R.styleable.TextView_shadowDy:
                dy = a.getFloat(attr, 0);
                break;

            case com.android.internal.R.styleable.TextView_shadowRadius:
                r = a.getFloat(attr, 0);
                break;

            case com.android.internal.R.styleable.TextView_enabled:
                setEnabled(a.getBoolean(attr, isEnabled()));
                break;

            case com.android.internal.R.styleable.TextView_textColorHighlight:
                textColorHighlight = a.getColor(attr, textColorHighlight);
                break;

            case com.android.internal.R.styleable.TextView_textColor:
                textColor = a.getColorStateList(attr);
                break;

            case com.android.internal.R.styleable.TextView_textColorHint:
                textColorHint = a.getColorStateList(attr);
                break;

            case com.android.internal.R.styleable.TextView_textColorLink:
                textColorLink = a.getColorStateList(attr);
                break;

            case com.android.internal.R.styleable.TextView_textSize:
                textSize = a.getDimensionPixelSize(attr, textSize);
                break;

            case com.android.internal.R.styleable.TextView_typeface:
                typefaceIndex = a.getInt(attr, typefaceIndex);
                break;

            case com.android.internal.R.styleable.TextView_textStyle:
                styleIndex = a.getInt(attr, styleIndex);
                break;

            case com.android.internal.R.styleable.TextView_password:
                password = a.getBoolean(attr, password);
                break;

            case com.android.internal.R.styleable.TextView_lineSpacingExtra:
                mSpacingAdd = a.getDimensionPixelSize(attr, (int) mSpacingAdd);
                break;

            case com.android.internal.R.styleable.TextView_lineSpacingMultiplier:
                mSpacingMult = a.getFloat(attr, mSpacingMult);
                break;

            case com.android.internal.R.styleable.TextView_inputType:
                inputType = a.getInt(attr, mInputType);
                break;

            case com.android.internal.R.styleable.TextView_imeOptions:
                if (mInputContentType == null) {
                    mInputContentType = new InputContentType();
                }
                mInputContentType.imeOptions = a.getInt(attr,
                        mInputContentType.imeOptions);
                break;

            case com.android.internal.R.styleable.TextView_imeActionLabel:
                if (mInputContentType == null) {
                    mInputContentType = new InputContentType();
                }
                mInputContentType.imeActionLabel = a.getText(attr);
                break;

            case com.android.internal.R.styleable.TextView_imeActionId:
                if (mInputContentType == null) {
                    mInputContentType = new InputContentType();
                }
                mInputContentType.imeActionId = a.getInt(attr,
                        mInputContentType.imeActionId);
                break;

            case com.android.internal.R.styleable.TextView_privateImeOptions:
                setPrivateImeOptions(a.getString(attr));
                break;

            case com.android.internal.R.styleable.TextView_editorExtras:
                try {
                    setInputExtras(a.getResourceId(attr, 0));
                } catch (XmlPullParserException e) {
                    Log.w(LOG_TAG, "Failure reading input extras", e);
                } catch (IOException e) {
                    Log.w(LOG_TAG, "Failure reading input extras", e);
                }
                break;

            case com.android.internal.R.styleable.TextView_textSelectHandleLeft:
                mTextSelectHandleLeftRes = a.getResourceId(attr, 0);
                break;

            case com.android.internal.R.styleable.TextView_textSelectHandleRight:
                mTextSelectHandleRightRes = a.getResourceId(attr, 0);
                break;

            case com.android.internal.R.styleable.TextView_textSelectHandle:
                mTextSelectHandleRes = a.getResourceId(attr, 0);
                break;
            }
        }
        a.recycle();

        BufferType bufferType = BufferType.EDITABLE;

        if ((inputType & (EditorInfo.TYPE_MASK_CLASS | EditorInfo.TYPE_MASK_VARIATION))
                == (EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_VARIATION_PASSWORD)) {
            password = true;
        }

        if (inputMethod != null) {
            Class<?> c;

            try {
                c = Class.forName(inputMethod.toString());
            } catch (ClassNotFoundException ex) {
                throw new RuntimeException(ex);
            }

            try {
                mInput = (KeyListener) c.newInstance();
            } catch (InstantiationException ex) {
                throw new RuntimeException(ex);
            } catch (IllegalAccessException ex) {
                throw new RuntimeException(ex);
            }
            try {
                mInputType = inputType != EditorInfo.TYPE_NULL
                        ? inputType
                        : mInput.getInputType();
            } catch (IncompatibleClassChangeError e) {
                mInputType = EditorInfo.TYPE_CLASS_TEXT;
            }
        } else if (digits != null) {
            mInput = DigitsKeyListener.getInstance(digits.toString());
            // If no input type was specified, we will default to generic
            // text, since we can't tell the IME about the set of digits
            // that was selected.
            mInputType = inputType != EditorInfo.TYPE_NULL
                    ? inputType : EditorInfo.TYPE_CLASS_TEXT;
        } else if (inputType != EditorInfo.TYPE_NULL) {
            setInputType(inputType, true);
            singleLine = (inputType&(EditorInfo.TYPE_MASK_CLASS
                            | EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE)) !=
                    (EditorInfo.TYPE_CLASS_TEXT
                            | EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE);
        } else if (phone) {
            mInput = DialerKeyListener.getInstance();
            mInputType = inputType = EditorInfo.TYPE_CLASS_PHONE;
        } else if (numeric != 0) {
            mInput = DigitsKeyListener.getInstance((numeric & SIGNED) != 0,
                                                   (numeric & DECIMAL) != 0);
            inputType = EditorInfo.TYPE_CLASS_NUMBER;
            if ((numeric & SIGNED) != 0) {
                inputType |= EditorInfo.TYPE_NUMBER_FLAG_SIGNED;
            }
            if ((numeric & DECIMAL) != 0) {
                inputType |= EditorInfo.TYPE_NUMBER_FLAG_DECIMAL;
            }
            mInputType = inputType;
        } else if (autotext || autocap != -1) {
            TextKeyListener.Capitalize cap;

            inputType = EditorInfo.TYPE_CLASS_TEXT;
            if (!singleLine) {
                inputType |= EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE;
            }

            switch (autocap) {
            case 1:
                cap = TextKeyListener.Capitalize.SENTENCES;
                inputType |= EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES;
                break;

            case 2:
                cap = TextKeyListener.Capitalize.WORDS;
                inputType |= EditorInfo.TYPE_TEXT_FLAG_CAP_WORDS;
                break;

            case 3:
                cap = TextKeyListener.Capitalize.CHARACTERS;
                inputType |= EditorInfo.TYPE_TEXT_FLAG_CAP_CHARACTERS;
                break;

            default:
                cap = TextKeyListener.Capitalize.NONE;
                break;
            }

            mInput = TextKeyListener.getInstance(autotext, cap);
            mInputType = inputType;
        } else if (editable) {
            mInput = TextKeyListener.getInstance();
            mInputType = EditorInfo.TYPE_CLASS_TEXT;
            if (!singleLine) {
                mInputType |= EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE;
            }
        } else {
            mInput = null;

            switch (buffertype) {
                case 0:
                    bufferType = BufferType.NORMAL;
                    break;
                case 1:
                    bufferType = BufferType.SPANNABLE;
                    break;
                case 2:
                    bufferType = BufferType.EDITABLE;
                    break;
            }
        }

        if (password && (mInputType&EditorInfo.TYPE_MASK_CLASS)
                == EditorInfo.TYPE_CLASS_TEXT) {
            mInputType = (mInputType & ~(EditorInfo.TYPE_MASK_VARIATION))
                | EditorInfo.TYPE_TEXT_VARIATION_PASSWORD;
        }

        if (selectallonfocus) {
            mSelectAllOnFocus = true;

            if (bufferType == BufferType.NORMAL)
                bufferType = BufferType.SPANNABLE;
        }

        setCompoundDrawablesWithIntrinsicBounds(
            drawableLeft, drawableTop, drawableRight, drawableBottom);
        setCompoundDrawablePadding(drawablePadding);

        if (singleLine) {
            setSingleLine();

            if (mInput == null && ellipsize < 0) {
                ellipsize = 3; // END
            }
        }

        switch (ellipsize) {
            case 1:
                setEllipsize(TextUtils.TruncateAt.START);
                break;
            case 2:
                setEllipsize(TextUtils.TruncateAt.MIDDLE);
                break;
            case 3:
                setEllipsize(TextUtils.TruncateAt.END);
                break;
            case 4:
                setHorizontalFadingEdgeEnabled(true);
                setEllipsize(TextUtils.TruncateAt.MARQUEE);
                break;
        }

        setTextColor(textColor != null ? textColor : ColorStateList.valueOf(0xFF000000));
        setHintTextColor(textColorHint);
        setLinkTextColor(textColorLink);
        if (textColorHighlight != 0) {
            setHighlightColor(textColorHighlight);
        }
        setRawTextSize(textSize);

        if (password) {
            setTransformationMethod(PasswordTransformationMethod.getInstance());
            typefaceIndex = MONOSPACE;
        } else if ((mInputType&(EditorInfo.TYPE_MASK_CLASS
                |EditorInfo.TYPE_MASK_VARIATION))
                == (EditorInfo.TYPE_CLASS_TEXT
                        |EditorInfo.TYPE_TEXT_VARIATION_PASSWORD)) {
            typefaceIndex = MONOSPACE;
        }

        setTypefaceByIndex(typefaceIndex, styleIndex);

        if (shadowcolor != 0) {
            setShadowLayer(r, dx, dy, shadowcolor);
        }

        if (maxlength >= 0) {
            setFilters(new InputFilter[] { new InputFilter.LengthFilter(maxlength) });
        } else {
            setFilters(NO_FILTERS);
        }

        setText(text, bufferType);
        if (hint != null) setHint(hint);

        /*
         * Views are not normally focusable unless specified to be.
         * However, TextViews that have input or movement methods *are*
         * focusable by default.
         */
        a = context.obtainStyledAttributes(attrs,
                                           com.android.internal.R.styleable.View,
                                           defStyle, 0);

        boolean focusable = mMovement != null || mInput != null;
        boolean clickable = focusable;
        boolean longClickable = focusable;

        n = a.getIndexCount();
        for (int i = 0; i < n; i++) {
            int attr = a.getIndex(i);

            switch (attr) {
            case com.android.internal.R.styleable.View_focusable:
                focusable = a.getBoolean(attr, focusable);
                break;

            case com.android.internal.R.styleable.View_clickable:
                clickable = a.getBoolean(attr, clickable);
                break;

            case com.android.internal.R.styleable.View_longClickable:
                longClickable = a.getBoolean(attr, longClickable);
                break;
            }
        }
        a.recycle();

        setFocusable(focusable);
        setClickable(clickable);
        setLongClickable(longClickable);

        prepareCursorControllers();
    }

    private void setTypefaceByIndex(int typefaceIndex, int styleIndex) {
        Typeface tf = null;
        switch (typefaceIndex) {
            case SANS:
                tf = Typeface.SANS_SERIF;
                break;

            case SERIF:
                tf = Typeface.SERIF;
                break;

            case MONOSPACE:
                tf = Typeface.MONOSPACE;
                break;
        }

        setTypeface(tf, styleIndex);
    }

    /**
     * Sets the typeface and style in which the text should be displayed,
     * and turns on the fake bold and italic bits in the Paint if the
     * Typeface that you provided does not have all the bits in the
     * style that you specified.
     *
     * @attr ref android.R.styleable#TextView_typeface
     * @attr ref android.R.styleable#TextView_textStyle
     */
    public void setTypeface(Typeface tf, int style) {
        if (style > 0) {
            if (tf == null) {
                tf = Typeface.defaultFromStyle(style);
            } else {
                tf = Typeface.create(tf, style);
            }

            setTypeface(tf);
            // now compute what (if any) algorithmic styling is needed
            int typefaceStyle = tf != null ? tf.getStyle() : 0;
            int need = style & ~typefaceStyle;
            mTextPaint.setFakeBoldText((need & Typeface.BOLD) != 0);
            mTextPaint.setTextSkewX((need & Typeface.ITALIC) != 0 ? -0.25f : 0);
        } else {
            mTextPaint.setFakeBoldText(false);
            mTextPaint.setTextSkewX(0);
            setTypeface(tf);
        }
    }

    /**
     * Subclasses override this to specify that they have a KeyListener
     * by default even if not specifically called for in the XML options.
     */
    protected boolean getDefaultEditable() {
        return false;
    }

    /**
     * Subclasses override this to specify a default movement method.
     */
    protected MovementMethod getDefaultMovementMethod() {
        return null;
    }

    /**
     * Return the text the TextView is displaying. If setText() was called with
     * an argument of BufferType.SPANNABLE or BufferType.EDITABLE, you can cast
     * the return value from this method to Spannable or Editable, respectively.
     *
     * Note: The content of the return value should not be modified. If you want
     * a modifiable one, you should make your own copy first.
     */
    @ViewDebug.CapturedViewProperty
    public CharSequence getText() {
        return mText;
    }

    /**
     * Returns the length, in characters, of the text managed by this TextView
     */
    public int length() {
        return mText.length();
    }

    /**
     * Return the text the TextView is displaying as an Editable object.  If
     * the text is not editable, null is returned.
     *
     * @see #getText
     */
    public Editable getEditableText() {
        return (mText instanceof Editable) ? (Editable)mText : null;
    }

    /**
     * @return the height of one standard line in pixels.  Note that markup
     * within the text can cause individual lines to be taller or shorter
     * than this height, and the layout may contain additional first-
     * or last-line padding.
     */
    public int getLineHeight() {
        return FastMath.round(mTextPaint.getFontMetricsInt(null) * mSpacingMult
                          + mSpacingAdd);
    }

    /**
     * @return the Layout that is currently being used to display the text.
     * This can be null if the text or width has recently changes.
     */
    public final Layout getLayout() {
        return mLayout;
    }

    /**
     * @return the current key listener for this TextView.
     * This will frequently be null for non-EditText TextViews.
     */
    public final KeyListener getKeyListener() {
        return mInput;
    }

    /**
     * Sets the key listener to be used with this TextView.  This can be null
     * to disallow user input.  Note that this method has significant and
     * subtle interactions with soft keyboards and other input method:
     * see {@link KeyListener#getInputType() KeyListener.getContentType()}
     * for important details.  Calling this method will replace the current
     * content type of the text view with the content type returned by the
     * key listener.
     * <p>
     * Be warned that if you want a TextView with a key listener or movement
     * method not to be focusable, or if you want a TextView without a
     * key listener or movement method to be focusable, you must call
     * {@link #setFocusable} again after calling this to get the focusability
     * back the way you want it.
     *
     * @attr ref android.R.styleable#TextView_numeric
     * @attr ref android.R.styleable#TextView_digits
     * @attr ref android.R.styleable#TextView_phoneNumber
     * @attr ref android.R.styleable#TextView_inputMethod
     * @attr ref android.R.styleable#TextView_capitalize
     * @attr ref android.R.styleable#TextView_autoText
     */
    public void setKeyListener(KeyListener input) {
        setKeyListenerOnly(input);
        fixFocusableAndClickableSettings();

        if (input != null) {
            try {
                mInputType = mInput.getInputType();
            } catch (IncompatibleClassChangeError e) {
                mInputType = EditorInfo.TYPE_CLASS_TEXT;
            }
            if ((mInputType&EditorInfo.TYPE_MASK_CLASS)
                    == EditorInfo.TYPE_CLASS_TEXT) {
                if (mSingleLine) {
                    mInputType &= ~EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE;
                } else {
                    mInputType |= EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE;
                }
            }
        } else {
            mInputType = EditorInfo.TYPE_NULL;
        }

        InputMethodManager imm = InputMethodManager.peekInstance();
        if (imm != null) imm.restartInput(this);
    }

    private void setKeyListenerOnly(KeyListener input) {
        mInput = input;
        if (mInput != null && !(mText instanceof Editable))
            setText(mText);

        setFilters((Editable) mText, mFilters);
    }

    /**
     * @return the movement method being used for this TextView.
     * This will frequently be null for non-EditText TextViews.
     */
    public final MovementMethod getMovementMethod() {
        return mMovement;
    }

    /**
     * Sets the movement method (arrow key handler) to be used for
     * this TextView.  This can be null to disallow using the arrow keys
     * to move the cursor or scroll the view.
     * <p>
     * Be warned that if you want a TextView with a key listener or movement
     * method not to be focusable, or if you want a TextView without a
     * key listener or movement method to be focusable, you must call
     * {@link #setFocusable} again after calling this to get the focusability
     * back the way you want it.
     */
    public final void setMovementMethod(MovementMethod movement) {
        mMovement = movement;

        if (mMovement != null && !(mText instanceof Spannable))
            setText(mText);

        fixFocusableAndClickableSettings();

        // SelectionModifierCursorController depends on textCanBeSelected, which depends on mMovement
        prepareCursorControllers();
    }

    private void fixFocusableAndClickableSettings() {
        if ((mMovement != null) || mInput != null) {
            setFocusable(true);
            setClickable(true);
            setLongClickable(true);
        } else {
            setFocusable(false);
            setClickable(false);
            setLongClickable(false);
        }
    }

    /**
     * @return the current transformation method for this TextView.
     * This will frequently be null except for single-line and password
     * fields.
     */
    public final TransformationMethod getTransformationMethod() {
        return mTransformation;
    }

    /**
     * Sets the transformation that is applied to the text that this
     * TextView is displaying.
     *
     * @attr ref android.R.styleable#TextView_password
     * @attr ref android.R.styleable#TextView_singleLine
     */
    public final void setTransformationMethod(TransformationMethod method) {
        if (method == mTransformation) {
            // Avoid the setText() below if the transformation is
            // the same.
            return;
        }
        if (mTransformation != null) {
            if (mText instanceof Spannable) {
                ((Spannable) mText).removeSpan(mTransformation);
            }
        }

        mTransformation = method;

        setText(mText);
    }

    /**
     * Returns the top padding of the view, plus space for the top
     * Drawable if any.
     */
    public int getCompoundPaddingTop() {
        final Drawables dr = mDrawables;
        if (dr == null || dr.mDrawableTop == null) {
            return mPaddingTop;
        } else {
            return mPaddingTop + dr.mDrawablePadding + dr.mDrawableSizeTop;
        }
    }

    /**
     * Returns the bottom padding of the view, plus space for the bottom
     * Drawable if any.
     */
    public int getCompoundPaddingBottom() {
        final Drawables dr = mDrawables;
        if (dr == null || dr.mDrawableBottom == null) {
            return mPaddingBottom;
        } else {
            return mPaddingBottom + dr.mDrawablePadding + dr.mDrawableSizeBottom;
        }
    }

    /**
     * Returns the left padding of the view, plus space for the left
     * Drawable if any.
     */
    public int getCompoundPaddingLeft() {
        final Drawables dr = mDrawables;
        if (dr == null || dr.mDrawableLeft == null) {
            return mPaddingLeft;
        } else {
            return mPaddingLeft + dr.mDrawablePadding + dr.mDrawableSizeLeft;
        }
    }

    /**
     * Returns the right padding of the view, plus space for the right
     * Drawable if any.
     */
    public int getCompoundPaddingRight() {
        final Drawables dr = mDrawables;
        if (dr == null || dr.mDrawableRight == null) {
            return mPaddingRight;
        } else {
            return mPaddingRight + dr.mDrawablePadding + dr.mDrawableSizeRight;
        }
    }

    /**
     * Returns the extended top padding of the view, including both the
     * top Drawable if any and any extra space to keep more than maxLines
     * of text from showing.  It is only valid to call this after measuring.
     */
    public int getExtendedPaddingTop() {
        if (mMaxMode != LINES) {
            return getCompoundPaddingTop();
        }

        if (mLayout.getLineCount() <= mMaximum) {
            return getCompoundPaddingTop();
        }

        int top = getCompoundPaddingTop();
        int bottom = getCompoundPaddingBottom();
        int viewht = getHeight() - top - bottom;
        int layoutht = mLayout.getLineTop(mMaximum);

        if (layoutht >= viewht) {
            return top;
        }

        final int gravity = mGravity & Gravity.VERTICAL_GRAVITY_MASK;
        if (gravity == Gravity.TOP) {
            return top;
        } else if (gravity == Gravity.BOTTOM) {
            return top + viewht - layoutht;
        } else { // (gravity == Gravity.CENTER_VERTICAL)
            return top + (viewht - layoutht) / 2;
        }
    }

    /**
     * Returns the extended bottom padding of the view, including both the
     * bottom Drawable if any and any extra space to keep more than maxLines
     * of text from showing.  It is only valid to call this after measuring.
     */
    public int getExtendedPaddingBottom() {
        if (mMaxMode != LINES) {
            return getCompoundPaddingBottom();
        }

        if (mLayout.getLineCount() <= mMaximum) {
            return getCompoundPaddingBottom();
        }

        int top = getCompoundPaddingTop();
        int bottom = getCompoundPaddingBottom();
        int viewht = getHeight() - top - bottom;
        int layoutht = mLayout.getLineTop(mMaximum);

        if (layoutht >= viewht) {
            return bottom;
        }

        final int gravity = mGravity & Gravity.VERTICAL_GRAVITY_MASK;
        if (gravity == Gravity.TOP) {
            return bottom + viewht - layoutht;
        } else if (gravity == Gravity.BOTTOM) {
            return bottom;
        } else { // (gravity == Gravity.CENTER_VERTICAL)
            return bottom + (viewht - layoutht) / 2;
        }
    }

    /**
     * Returns the total left padding of the view, including the left
     * Drawable if any.
     */
    public int getTotalPaddingLeft() {
        return getCompoundPaddingLeft();
    }

    /**
     * Returns the total right padding of the view, including the right
     * Drawable if any.
     */
    public int getTotalPaddingRight() {
        return getCompoundPaddingRight();
    }

    /**
     * Returns the total top padding of the view, including the top
     * Drawable if any, the extra space to keep more than maxLines
     * from showing, and the vertical offset for gravity, if any.
     */
    public int getTotalPaddingTop() {
        return getExtendedPaddingTop() + getVerticalOffset(true);
    }

    /**
     * Returns the total bottom padding of the view, including the bottom
     * Drawable if any, the extra space to keep more than maxLines
     * from showing, and the vertical offset for gravity, if any.
     */
    public int getTotalPaddingBottom() {
        return getExtendedPaddingBottom() + getBottomVerticalOffset(true);
    }

    /**
     * Sets the Drawables (if any) to appear to the left of, above,
     * to the right of, and below the text.  Use null if you do not
     * want a Drawable there.  The Drawables must already have had
     * {@link Drawable#setBounds} called.
     *
     * @attr ref android.R.styleable#TextView_drawableLeft
     * @attr ref android.R.styleable#TextView_drawableTop
     * @attr ref android.R.styleable#TextView_drawableRight
     * @attr ref android.R.styleable#TextView_drawableBottom
     */
    public void setCompoundDrawables(Drawable left, Drawable top,
                                     Drawable right, Drawable bottom) {
        Drawables dr = mDrawables;

        final boolean drawables = left != null || top != null
                || right != null || bottom != null;

        if (!drawables) {
            // Clearing drawables...  can we free the data structure?
            if (dr != null) {
                if (dr.mDrawablePadding == 0) {
                    mDrawables = null;
                } else {
                    // We need to retain the last set padding, so just clear
                    // out all of the fields in the existing structure.
                    if (dr.mDrawableLeft != null) dr.mDrawableLeft.setCallback(null);
                    dr.mDrawableLeft = null;
                    if (dr.mDrawableTop != null) dr.mDrawableTop.setCallback(null);
                    dr.mDrawableTop = null;
                    if (dr.mDrawableRight != null) dr.mDrawableRight.setCallback(null);
                    dr.mDrawableRight = null;
                    if (dr.mDrawableBottom != null) dr.mDrawableBottom.setCallback(null);
                    dr.mDrawableBottom = null;
                    dr.mDrawableSizeLeft = dr.mDrawableHeightLeft = 0;
                    dr.mDrawableSizeRight = dr.mDrawableHeightRight = 0;
                    dr.mDrawableSizeTop = dr.mDrawableWidthTop = 0;
                    dr.mDrawableSizeBottom = dr.mDrawableWidthBottom = 0;
                }
            }
        } else {
            if (dr == null) {
                mDrawables = dr = new Drawables();
            }

            if (dr.mDrawableLeft != left && dr.mDrawableLeft != null) {
                dr.mDrawableLeft.setCallback(null);
            }
            dr.mDrawableLeft = left;

            if (dr.mDrawableTop != top && dr.mDrawableTop != null) {
                dr.mDrawableTop.setCallback(null);
            }
            dr.mDrawableTop = top;

            if (dr.mDrawableRight != right && dr.mDrawableRight != null) {
                dr.mDrawableRight.setCallback(null);
            }
            dr.mDrawableRight = right;

            if (dr.mDrawableBottom != bottom && dr.mDrawableBottom != null) {
                dr.mDrawableBottom.setCallback(null);
            }
            dr.mDrawableBottom = bottom;

            final Rect compoundRect = dr.mCompoundRect;
            int[] state;

            state = getDrawableState();

            if (left != null) {
                left.setState(state);
                left.copyBounds(compoundRect);
                left.setCallback(this);
                dr.mDrawableSizeLeft = compoundRect.width();
                dr.mDrawableHeightLeft = compoundRect.height();
            } else {
                dr.mDrawableSizeLeft = dr.mDrawableHeightLeft = 0;
            }

            if (right != null) {
                right.setState(state);
                right.copyBounds(compoundRect);
                right.setCallback(this);
                dr.mDrawableSizeRight = compoundRect.width();
                dr.mDrawableHeightRight = compoundRect.height();
            } else {
                dr.mDrawableSizeRight = dr.mDrawableHeightRight = 0;
            }

            if (top != null) {
                top.setState(state);
                top.copyBounds(compoundRect);
                top.setCallback(this);
                dr.mDrawableSizeTop = compoundRect.height();
                dr.mDrawableWidthTop = compoundRect.width();
            } else {
                dr.mDrawableSizeTop = dr.mDrawableWidthTop = 0;
            }

            if (bottom != null) {
                bottom.setState(state);
                bottom.copyBounds(compoundRect);
                bottom.setCallback(this);
                dr.mDrawableSizeBottom = compoundRect.height();
                dr.mDrawableWidthBottom = compoundRect.width();
            } else {
                dr.mDrawableSizeBottom = dr.mDrawableWidthBottom = 0;
            }
        }

        invalidate();
        requestLayout();
    }

    /**
     * Sets the Drawables (if any) to appear to the left of, above,
     * to the right of, and below the text.  Use 0 if you do not
     * want a Drawable there. The Drawables' bounds will be set to
     * their intrinsic bounds.
     *
     * @param left Resource identifier of the left Drawable.
     * @param top Resource identifier of the top Drawable.
     * @param right Resource identifier of the right Drawable.
     * @param bottom Resource identifier of the bottom Drawable.
     *
     * @attr ref android.R.styleable#TextView_drawableLeft
     * @attr ref android.R.styleable#TextView_drawableTop
     * @attr ref android.R.styleable#TextView_drawableRight
     * @attr ref android.R.styleable#TextView_drawableBottom
     */
    public void setCompoundDrawablesWithIntrinsicBounds(int left, int top, int right, int bottom) {
        final Resources resources = getContext().getResources();
        setCompoundDrawablesWithIntrinsicBounds(left != 0 ? resources.getDrawable(left) : null,
                top != 0 ? resources.getDrawable(top) : null,
                right != 0 ? resources.getDrawable(right) : null,
                bottom != 0 ? resources.getDrawable(bottom) : null);
    }

    /**
     * Sets the Drawables (if any) to appear to the left of, above,
     * to the right of, and below the text.  Use null if you do not
     * want a Drawable there. The Drawables' bounds will be set to
     * their intrinsic bounds.
     *
     * @attr ref android.R.styleable#TextView_drawableLeft
     * @attr ref android.R.styleable#TextView_drawableTop
     * @attr ref android.R.styleable#TextView_drawableRight
     * @attr ref android.R.styleable#TextView_drawableBottom
     */
    public void setCompoundDrawablesWithIntrinsicBounds(Drawable left, Drawable top,
            Drawable right, Drawable bottom) {

        if (left != null) {
            left.setBounds(0, 0, left.getIntrinsicWidth(), left.getIntrinsicHeight());
        }
        if (right != null) {
            right.setBounds(0, 0, right.getIntrinsicWidth(), right.getIntrinsicHeight());
        }
        if (top != null) {
            top.setBounds(0, 0, top.getIntrinsicWidth(), top.getIntrinsicHeight());
        }
        if (bottom != null) {
            bottom.setBounds(0, 0, bottom.getIntrinsicWidth(), bottom.getIntrinsicHeight());
        }
        setCompoundDrawables(left, top, right, bottom);
    }

    /**
     * Returns drawables for the left, top, right, and bottom borders.
     */
    public Drawable[] getCompoundDrawables() {
        final Drawables dr = mDrawables;
        if (dr != null) {
            return new Drawable[] {
                dr.mDrawableLeft, dr.mDrawableTop, dr.mDrawableRight, dr.mDrawableBottom
            };
        } else {
            return new Drawable[] { null, null, null, null };
        }
    }

    /**
     * Sets the size of the padding between the compound drawables and
     * the text.
     *
     * @attr ref android.R.styleable#TextView_drawablePadding
     */
    public void setCompoundDrawablePadding(int pad) {
        Drawables dr = mDrawables;
        if (pad == 0) {
            if (dr != null) {
                dr.mDrawablePadding = pad;
            }
        } else {
            if (dr == null) {
                mDrawables = dr = new Drawables();
            }
            dr.mDrawablePadding = pad;
        }

        invalidate();
        requestLayout();
    }

    /**
     * Returns the padding between the compound drawables and the text.
     */
    public int getCompoundDrawablePadding() {
        final Drawables dr = mDrawables;
        return dr != null ? dr.mDrawablePadding : 0;
    }

    @Override
    public void setPadding(int left, int top, int right, int bottom) {
        if (left != mPaddingLeft ||
            right != mPaddingRight ||
            top != mPaddingTop ||
            bottom != mPaddingBottom) {
            nullLayouts();
        }

        // the super call will requestLayout()
        super.setPadding(left, top, right, bottom);
        invalidate();
    }

    /**
     * Gets the autolink mask of the text.  See {@link
     * android.text.util.Linkify#ALL Linkify.ALL} and peers for
     * possible values.
     *
     * @attr ref android.R.styleable#TextView_autoLink
     */
    public final int getAutoLinkMask() {
        return mAutoLinkMask;
    }

    /**
     * Sets the text color, size, style, hint color, and highlight color
     * from the specified TextAppearance resource.
     */
    public void setTextAppearance(Context context, int resid) {
        TypedArray appearance =
            context.obtainStyledAttributes(resid,
                                           com.android.internal.R.styleable.TextAppearance);

        int color;
        ColorStateList colors;
        int ts;

        color = appearance.getColor(com.android.internal.R.styleable.TextAppearance_textColorHighlight, 0);
        if (color != 0) {
            setHighlightColor(color);
        }

        colors = appearance.getColorStateList(com.android.internal.R.styleable.
                                              TextAppearance_textColor);
        if (colors != null) {
            setTextColor(colors);
        }

        ts = appearance.getDimensionPixelSize(com.android.internal.R.styleable.
                                              TextAppearance_textSize, 0);
        if (ts != 0) {
            setRawTextSize(ts);
        }

        colors = appearance.getColorStateList(com.android.internal.R.styleable.
                                              TextAppearance_textColorHint);
        if (colors != null) {
            setHintTextColor(colors);
        }

        colors = appearance.getColorStateList(com.android.internal.R.styleable.
                                              TextAppearance_textColorLink);
        if (colors != null) {
            setLinkTextColor(colors);
        }

        int typefaceIndex, styleIndex;

        typefaceIndex = appearance.getInt(com.android.internal.R.styleable.
                                          TextAppearance_typeface, -1);
        styleIndex = appearance.getInt(com.android.internal.R.styleable.
                                       TextAppearance_textStyle, -1);

        setTypefaceByIndex(typefaceIndex, styleIndex);
        appearance.recycle();
    }

    /**
     * @return the size (in pixels) of the default text size in this TextView.
     */
    public float getTextSize() {
        return mTextPaint.getTextSize();
    }

    /**
     * Set the default text size to the given value, interpreted as "scaled
     * pixel" units.  This size is adjusted based on the current density and
     * user font size preference.
     *
     * @param size The scaled pixel size.
     *
     * @attr ref android.R.styleable#TextView_textSize
     */
    @android.view.RemotableViewMethod
    public void setTextSize(float size) {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
    }

    /**
     * Set the default text size to a given unit and value.  See {@link
     * TypedValue} for the possible dimension units.
     *
     * @param unit The desired dimension unit.
     * @param size The desired size in the given units.
     *
     * @attr ref android.R.styleable#TextView_textSize
     */
    public void setTextSize(int unit, float size) {
        Context c = getContext();
        Resources r;

        if (c == null)
            r = Resources.getSystem();
        else
            r = c.getResources();

        setRawTextSize(TypedValue.applyDimension(
            unit, size, r.getDisplayMetrics()));
    }

    private void setRawTextSize(float size) {
        if (size != mTextPaint.getTextSize()) {
            mTextPaint.setTextSize(size);

            if (mLayout != null) {
                nullLayouts();
                requestLayout();
                invalidate();
            }
        }
    }

    /**
     * @return the extent by which text is currently being stretched
     * horizontally.  This will usually be 1.
     */
    public float getTextScaleX() {
        return mTextPaint.getTextScaleX();
    }

    /**
     * Sets the extent by which text should be stretched horizontally.
     *
     * @attr ref android.R.styleable#TextView_textScaleX
     */
    @android.view.RemotableViewMethod
    public void setTextScaleX(float size) {
        if (size != mTextPaint.getTextScaleX()) {
            mUserSetTextScaleX = true;
            mTextPaint.setTextScaleX(size);

            if (mLayout != null) {
                nullLayouts();
                requestLayout();
                invalidate();
            }
        }
    }

    /**
     * Sets the typeface and style in which the text should be displayed.
     * Note that not all Typeface families actually have bold and italic
     * variants, so you may need to use
     * {@link #setTypeface(Typeface, int)} to get the appearance
     * that you actually want.
     *
     * @attr ref android.R.styleable#TextView_typeface
     * @attr ref android.R.styleable#TextView_textStyle
     */
    public void setTypeface(Typeface tf) {
        if (mTextPaint.getTypeface() != tf) {
            mTextPaint.setTypeface(tf);

            if (mLayout != null) {
                nullLayouts();
                requestLayout();
                invalidate();
            }
        }
    }

    /**
     * @return the current typeface and style in which the text is being
     * displayed.
     */
    public Typeface getTypeface() {
        return mTextPaint.getTypeface();
    }

    /**
     * Sets the text color for all the states (normal, selected,
     * focused) to be this color.
     *
     * @attr ref android.R.styleable#TextView_textColor
     */
    @android.view.RemotableViewMethod
    public void setTextColor(int color) {
        mTextColor = ColorStateList.valueOf(color);
        updateTextColors();
    }

    /**
     * Sets the text color.
     *
     * @attr ref android.R.styleable#TextView_textColor
     */
    public void setTextColor(ColorStateList colors) {
        if (colors == null) {
            throw new NullPointerException();
        }

        mTextColor = colors;
        updateTextColors();
    }

    /**
     * Return the set of text colors.
     *
     * @return Returns the set of text colors.
     */
    public final ColorStateList getTextColors() {
        return mTextColor;
    }

    /**
     * <p>Return the current color selected for normal text.</p>
     *
     * @return Returns the current text color.
     */
    public final int getCurrentTextColor() {
        return mCurTextColor;
    }

    /**
     * Sets the color used to display the selection highlight.
     *
     * @attr ref android.R.styleable#TextView_textColorHighlight
     */
    @android.view.RemotableViewMethod
    public void setHighlightColor(int color) {
        if (mHighlightColor != color) {
            mHighlightColor = color;
            invalidate();
        }
    }

    /**
     * Gives the text a shadow of the specified radius and color, the specified
     * distance from its normal position.
     *
     * @attr ref android.R.styleable#TextView_shadowColor
     * @attr ref android.R.styleable#TextView_shadowDx
     * @attr ref android.R.styleable#TextView_shadowDy
     * @attr ref android.R.styleable#TextView_shadowRadius
     */
    public void setShadowLayer(float radius, float dx, float dy, int color) {
        mTextPaint.setShadowLayer(radius, dx, dy, color);

        mShadowRadius = radius;
        mShadowDx = dx;
        mShadowDy = dy;

        invalidate();
    }

    /**
     * @return the base paint used for the text.  Please use this only to
     * consult the Paint's properties and not to change them.
     */
    public TextPaint getPaint() {
        return mTextPaint;
    }

    /**
     * Sets the autolink mask of the text.  See {@link
     * android.text.util.Linkify#ALL Linkify.ALL} and peers for
     * possible values.
     *
     * @attr ref android.R.styleable#TextView_autoLink
     */
    @android.view.RemotableViewMethod
    public final void setAutoLinkMask(int mask) {
        mAutoLinkMask = mask;
    }

    /**
     * Sets whether the movement method will automatically be set to
     * {@link LinkMovementMethod} if {@link #setAutoLinkMask} has been
     * set to nonzero and links are detected in {@link #setText}.
     * The default is true.
     *
     * @attr ref android.R.styleable#TextView_linksClickable
     */
    @android.view.RemotableViewMethod
    public final void setLinksClickable(boolean whether) {
        mLinksClickable = whether;
    }

    /**
     * Returns whether the movement method will automatically be set to
     * {@link LinkMovementMethod} if {@link #setAutoLinkMask} has been
     * set to nonzero and links are detected in {@link #setText}.
     * The default is true.
     *
     * @attr ref android.R.styleable#TextView_linksClickable
     */
    public final boolean getLinksClickable() {
        return mLinksClickable;
    }

    /**
     * Returns the list of URLSpans attached to the text
     * (by {@link Linkify} or otherwise) if any.  You can call
     * {@link URLSpan#getURL} on them to find where they link to
     * or use {@link Spanned#getSpanStart} and {@link Spanned#getSpanEnd}
     * to find the region of the text they are attached to.
     */
    public URLSpan[] getUrls() {
        if (mText instanceof Spanned) {
            return ((Spanned) mText).getSpans(0, mText.length(), URLSpan.class);
        } else {
            return new URLSpan[0];
        }
    }

    /**
     * Sets the color of the hint text.
     *
     * @attr ref android.R.styleable#TextView_textColorHint
     */
    @android.view.RemotableViewMethod
    public final void setHintTextColor(int color) {
        mHintTextColor = ColorStateList.valueOf(color);
        updateTextColors();
    }

    /**
     * Sets the color of the hint text.
     *
     * @attr ref android.R.styleable#TextView_textColorHint
     */
    public final void setHintTextColor(ColorStateList colors) {
        mHintTextColor = colors;
        updateTextColors();
    }

    /**
     * <p>Return the color used to paint the hint text.</p>
     *
     * @return Returns the list of hint text colors.
     */
    public final ColorStateList getHintTextColors() {
        return mHintTextColor;
    }

    /**
     * <p>Return the current color selected to paint the hint text.</p>
     *
     * @return Returns the current hint text color.
     */
    public final int getCurrentHintTextColor() {
        return mHintTextColor != null ? mCurHintTextColor : mCurTextColor;
    }

    /**
     * Sets the color of links in the text.
     *
     * @attr ref android.R.styleable#TextView_textColorLink
     */
    @android.view.RemotableViewMethod
    public final void setLinkTextColor(int color) {
        mLinkTextColor = ColorStateList.valueOf(color);
        updateTextColors();
    }

    /**
     * Sets the color of links in the text.
     *
     * @attr ref android.R.styleable#TextView_textColorLink
     */
    public final void setLinkTextColor(ColorStateList colors) {
        mLinkTextColor = colors;
        updateTextColors();
    }

    /**
     * <p>Returns the color used to paint links in the text.</p>
     *
     * @return Returns the list of link text colors.
     */
    public final ColorStateList getLinkTextColors() {
        return mLinkTextColor;
    }

    /**
     * Sets the horizontal alignment of the text and the
     * vertical gravity that will be used when there is extra space
     * in the TextView beyond what is required for the text itself.
     *
     * @see android.view.Gravity
     * @attr ref android.R.styleable#TextView_gravity
     */
    public void setGravity(int gravity) {
        if ((gravity & Gravity.HORIZONTAL_GRAVITY_MASK) == 0) {
            gravity |= Gravity.LEFT;
        }
        if ((gravity & Gravity.VERTICAL_GRAVITY_MASK) == 0) {
            gravity |= Gravity.TOP;
        }

        boolean newLayout = false;

        if ((gravity & Gravity.HORIZONTAL_GRAVITY_MASK) !=
            (mGravity & Gravity.HORIZONTAL_GRAVITY_MASK)) {
            newLayout = true;
        }

        if (gravity != mGravity) {
            invalidate();
        }

        mGravity = gravity;

        if (mLayout != null && newLayout) {
            // XXX this is heavy-handed because no actual content changes.
            int want = mLayout.getWidth();
            int hintWant = mHintLayout == null ? 0 : mHintLayout.getWidth();

            makeNewLayout(want, hintWant, UNKNOWN_BORING, UNKNOWN_BORING,
                          mRight - mLeft - getCompoundPaddingLeft() -
                          getCompoundPaddingRight(), true);
        }
    }

    /**
     * Returns the horizontal and vertical alignment of this TextView.
     *
     * @see android.view.Gravity
     * @attr ref android.R.styleable#TextView_gravity
     */
    public int getGravity() {
        return mGravity;
    }

    /**
     * @return the flags on the Paint being used to display the text.
     * @see Paint#getFlags
     */
    public int getPaintFlags() {
        return mTextPaint.getFlags();
    }

    /**
     * Sets flags on the Paint being used to display the text and
     * reflows the text if they are different from the old flags.
     * @see Paint#setFlags
     */
    @android.view.RemotableViewMethod
    public void setPaintFlags(int flags) {
        if (mTextPaint.getFlags() != flags) {
            mTextPaint.setFlags(flags);

            if (mLayout != null) {
                nullLayouts();
                requestLayout();
                invalidate();
            }
        }
    }

    /**
     * Sets whether the text should be allowed to be wider than the
     * View is.  If false, it will be wrapped to the width of the View.
     *
     * @attr ref android.R.styleable#TextView_scrollHorizontally
     */
    public void setHorizontallyScrolling(boolean whether) {
        mHorizontallyScrolling = whether;

        if (mLayout != null) {
            nullLayouts();
            requestLayout();
            invalidate();
        }
    }

    /**
     * Makes the TextView at least this many lines tall
     *
     * @attr ref android.R.styleable#TextView_minLines
     */
    @android.view.RemotableViewMethod
    public void setMinLines(int minlines) {
        mMinimum = minlines;
        mMinMode = LINES;

        requestLayout();
        invalidate();
    }

    /**
     * Makes the TextView at least this many pixels tall
     *
     * @attr ref android.R.styleable#TextView_minHeight
     */
    @android.view.RemotableViewMethod
    public void setMinHeight(int minHeight) {
        mMinimum = minHeight;
        mMinMode = PIXELS;

        requestLayout();
        invalidate();
    }

    /**
     * Makes the TextView at most this many lines tall
     *
     * @attr ref android.R.styleable#TextView_maxLines
     */
    @android.view.RemotableViewMethod
    public void setMaxLines(int maxlines) {
        mMaximum = maxlines;
        mMaxMode = LINES;

        requestLayout();
        invalidate();
    }

    /**
     * Makes the TextView at most this many pixels tall
     *
     * @attr ref android.R.styleable#TextView_maxHeight
     */
    @android.view.RemotableViewMethod
    public void setMaxHeight(int maxHeight) {
        mMaximum = maxHeight;
        mMaxMode = PIXELS;

        requestLayout();
        invalidate();
    }

    /**
     * Makes the TextView exactly this many lines tall
     *
     * @attr ref android.R.styleable#TextView_lines
     */
    @android.view.RemotableViewMethod
    public void setLines(int lines) {
        mMaximum = mMinimum = lines;
        mMaxMode = mMinMode = LINES;

        requestLayout();
        invalidate();
    }

    /**
     * Makes the TextView exactly this many pixels tall.
     * You could do the same thing by specifying this number in the
     * LayoutParams.
     *
     * @attr ref android.R.styleable#TextView_height
     */
    @android.view.RemotableViewMethod
    public void setHeight(int pixels) {
        mMaximum = mMinimum = pixels;
        mMaxMode = mMinMode = PIXELS;

        requestLayout();
        invalidate();
    }

    /**
     * Makes the TextView at least this many ems wide
     *
     * @attr ref android.R.styleable#TextView_minEms
     */
    @android.view.RemotableViewMethod
    public void setMinEms(int minems) {
        mMinWidth = minems;
        mMinWidthMode = EMS;

        requestLayout();
        invalidate();
    }

    /**
     * Makes the TextView at least this many pixels wide
     *
     * @attr ref android.R.styleable#TextView_minWidth
     */
    @android.view.RemotableViewMethod
    public void setMinWidth(int minpixels) {
        mMinWidth = minpixels;
        mMinWidthMode = PIXELS;

        requestLayout();
        invalidate();
    }

    /**
     * Makes the TextView at most this many ems wide
     *
     * @attr ref android.R.styleable#TextView_maxEms
     */
    @android.view.RemotableViewMethod
    public void setMaxEms(int maxems) {
        mMaxWidth = maxems;
        mMaxWidthMode = EMS;

        requestLayout();
        invalidate();
    }

    /**
     * Makes the TextView at most this many pixels wide
     *
     * @attr ref android.R.styleable#TextView_maxWidth
     */
    @android.view.RemotableViewMethod
    public void setMaxWidth(int maxpixels) {
        mMaxWidth = maxpixels;
        mMaxWidthMode = PIXELS;

        requestLayout();
        invalidate();
    }

    /**
     * Makes the TextView exactly this many ems wide
     *
     * @attr ref android.R.styleable#TextView_ems
     */
    @android.view.RemotableViewMethod
    public void setEms(int ems) {
        mMaxWidth = mMinWidth = ems;
        mMaxWidthMode = mMinWidthMode = EMS;

        requestLayout();
        invalidate();
    }

    /**
     * Makes the TextView exactly this many pixels wide.
     * You could do the same thing by specifying this number in the
     * LayoutParams.
     *
     * @attr ref android.R.styleable#TextView_width
     */
    @android.view.RemotableViewMethod
    public void setWidth(int pixels) {
        mMaxWidth = mMinWidth = pixels;
        mMaxWidthMode = mMinWidthMode = PIXELS;

        requestLayout();
        invalidate();
    }


    /**
     * Sets line spacing for this TextView.  Each line will have its height
     * multiplied by <code>mult</code> and have <code>add</code> added to it.
     *
     * @attr ref android.R.styleable#TextView_lineSpacingExtra
     * @attr ref android.R.styleable#TextView_lineSpacingMultiplier
     */
    public void setLineSpacing(float add, float mult) {
        mSpacingMult = mult;
        mSpacingAdd = add;

        if (mLayout != null) {
            nullLayouts();
            requestLayout();
            invalidate();
        }
    }

    /**
     * Convenience method: Append the specified text to the TextView's
     * display buffer, upgrading it to BufferType.EDITABLE if it was
     * not already editable.
     */
    public final void append(CharSequence text) {
        append(text, 0, text.length());
    }

    /**
     * Convenience method: Append the specified text slice to the TextView's
     * display buffer, upgrading it to BufferType.EDITABLE if it was
     * not already editable.
     */
    public void append(CharSequence text, int start, int end) {
        if (!(mText instanceof Editable)) {
            setText(mText, BufferType.EDITABLE);
        }

        ((Editable) mText).append(text, start, end);
    }

    private void updateTextColors() {
        boolean inval = false;
        int color = mTextColor.getColorForState(getDrawableState(), 0);
        if (color != mCurTextColor) {
            mCurTextColor = color;
            inval = true;
        }
        if (mLinkTextColor != null) {
            color = mLinkTextColor.getColorForState(getDrawableState(), 0);
            if (color != mTextPaint.linkColor) {
                mTextPaint.linkColor = color;
                inval = true;
            }
        }
        if (mHintTextColor != null) {
            color = mHintTextColor.getColorForState(getDrawableState(), 0);
            if (color != mCurHintTextColor && mText.length() == 0) {
                mCurHintTextColor = color;
                inval = true;
            }
        }
        if (inval) {
            invalidate();
        }
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        if (mTextColor != null && mTextColor.isStateful()
                || (mHintTextColor != null && mHintTextColor.isStateful())
                || (mLinkTextColor != null && mLinkTextColor.isStateful())) {
            updateTextColors();
        }

        final Drawables dr = mDrawables;
        if (dr != null) {
            int[] state = getDrawableState();
            if (dr.mDrawableTop != null && dr.mDrawableTop.isStateful()) {
                dr.mDrawableTop.setState(state);
            }
            if (dr.mDrawableBottom != null && dr.mDrawableBottom.isStateful()) {
                dr.mDrawableBottom.setState(state);
            }
            if (dr.mDrawableLeft != null && dr.mDrawableLeft.isStateful()) {
                dr.mDrawableLeft.setState(state);
            }
            if (dr.mDrawableRight != null && dr.mDrawableRight.isStateful()) {
                dr.mDrawableRight.setState(state);
            }
        }
    }

    /**
     * User interface state that is stored by TextView for implementing
     * {@link View#onSaveInstanceState}.
     */
    public static class SavedState extends BaseSavedState {
        int selStart;
        int selEnd;
        CharSequence text;
        boolean frozenWithFocus;
        CharSequence error;

        SavedState(Parcelable superState) {
            super(superState);
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            super.writeToParcel(out, flags);
            out.writeInt(selStart);
            out.writeInt(selEnd);
            out.writeInt(frozenWithFocus ? 1 : 0);
            TextUtils.writeToParcel(text, out, flags);

            if (error == null) {
                out.writeInt(0);
            } else {
                out.writeInt(1);
                TextUtils.writeToParcel(error, out, flags);
            }
        }

        @Override
        public String toString() {
            String str = "TextView.SavedState{"
                    + Integer.toHexString(System.identityHashCode(this))
                    + " start=" + selStart + " end=" + selEnd;
            if (text != null) {
                str += " text=" + text;
            }
            return str + "}";
        }

        @SuppressWarnings("hiding")
        public static final Parcelable.Creator<SavedState> CREATOR
                = new Parcelable.Creator<SavedState>() {
            public SavedState createFromParcel(Parcel in) {
                return new SavedState(in);
            }

            public SavedState[] newArray(int size) {
                return new SavedState[size];
            }
        };

        private SavedState(Parcel in) {
            super(in);
            selStart = in.readInt();
            selEnd = in.readInt();
            frozenWithFocus = (in.readInt() != 0);
            text = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);

            if (in.readInt() != 0) {
                error = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
            }
        }
    }

    @Override
    public Parcelable onSaveInstanceState() {
        Parcelable superState = super.onSaveInstanceState();

        // Save state if we are forced to
        boolean save = mFreezesText;
        int start = 0;
        int end = 0;

        if (mText != null) {
            start = getSelectionStart();
            end = getSelectionEnd();
            if (start >= 0 || end >= 0) {
                // Or save state if there is a selection
                save = true;
            }
        }

        if (save) {
            SavedState ss = new SavedState(superState);
            // XXX Should also save the current scroll position!
            ss.selStart = start;
            ss.selEnd = end;

            if (mText instanceof Spanned) {
                /*
                 * Calling setText() strips off any ChangeWatchers;
                 * strip them now to avoid leaking references.
                 * But do it to a copy so that if there are any
                 * further changes to the text of this view, it
                 * won't get into an inconsistent state.
                 */

                Spannable sp = new SpannableString(mText);

                for (ChangeWatcher cw :
                     sp.getSpans(0, sp.length(), ChangeWatcher.class)) {
                    sp.removeSpan(cw);
                }

                ss.text = sp;
            } else {
                ss.text = mText.toString();
            }

            if (isFocused() && start >= 0 && end >= 0) {
                ss.frozenWithFocus = true;
            }

            ss.error = mError;

            return ss;
        }

        return superState;
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
        if (!(state instanceof SavedState)) {
            super.onRestoreInstanceState(state);
            return;
        }

        SavedState ss = (SavedState)state;
        super.onRestoreInstanceState(ss.getSuperState());

        // XXX restore buffer type too, as well as lots of other stuff
        if (ss.text != null) {
            setText(ss.text);
        }

        if (ss.selStart >= 0 && ss.selEnd >= 0) {
            if (mText instanceof Spannable) {
                int len = mText.length();

                if (ss.selStart > len || ss.selEnd > len) {
                    String restored = "";

                    if (ss.text != null) {
                        restored = "(restored) ";
                    }

                    Log.e(LOG_TAG, "Saved cursor position " + ss.selStart +
                          "/" + ss.selEnd + " out of range for " + restored +
                          "text " + mText);
                } else {
                    Selection.setSelection((Spannable) mText, ss.selStart,
                                           ss.selEnd);

                    if (ss.frozenWithFocus) {
                        mFrozenWithFocus = true;
                    }
                }
            }
        }

        if (ss.error != null) {
            final CharSequence error = ss.error;
            // Display the error later, after the first layout pass
            post(new Runnable() {
                public void run() {
                    setError(error);
                }
            });
        }
    }

    /**
     * Control whether this text view saves its entire text contents when
     * freezing to an icicle, in addition to dynamic state such as cursor
     * position.  By default this is false, not saving the text.  Set to true
     * if the text in the text view is not being saved somewhere else in
     * persistent storage (such as in a content provider) so that if the
     * view is later thawed the user will not lose their data.
     *
     * @param freezesText Controls whether a frozen icicle should include the
     * entire text data: true to include it, false to not.
     *
     * @attr ref android.R.styleable#TextView_freezesText
     */
    @android.view.RemotableViewMethod
    public void setFreezesText(boolean freezesText) {
        mFreezesText = freezesText;
    }

    /**
     * Return whether this text view is including its entire text contents
     * in frozen icicles.
     *
     * @return Returns true if text is included, false if it isn't.
     *
     * @see #setFreezesText
     */
    public boolean getFreezesText() {
        return mFreezesText;
    }

    ///////////////////////////////////////////////////////////////////////////

    /**
     * Sets the Factory used to create new Editables.
     */
    public final void setEditableFactory(Editable.Factory factory) {
        mEditableFactory = factory;
        setText(mText);
    }

    /**
     * Sets the Factory used to create new Spannables.
     */
    public final void setSpannableFactory(Spannable.Factory factory) {
        mSpannableFactory = factory;
        setText(mText);
    }

    /**
     * Sets the string value of the TextView. TextView <em>does not</em> accept
     * HTML-like formatting, which you can do with text strings in XML resource files.
     * To style your strings, attach android.text.style.* objects to a
     * {@link android.text.SpannableString SpannableString}, or see the
     * <a href="{@docRoot}guide/topics/resources/available-resources.html#stringresources">
     * Available Resource Types</a> documentation for an example of setting 
     * formatted text in the XML resource file.
     *
     * @attr ref android.R.styleable#TextView_text
     */
    @android.view.RemotableViewMethod
    public final void setText(CharSequence text) {
        setText(text, mBufferType);
    }

    /**
     * Like {@link #setText(CharSequence)},
     * except that the cursor position (if any) is retained in the new text.
     *
     * @param text The new text to place in the text view.
     *
     * @see #setText(CharSequence)
     */
    @android.view.RemotableViewMethod
    public final void setTextKeepState(CharSequence text) {
        setTextKeepState(text, mBufferType);
    }

    /**
     * Sets the text that this TextView is to display (see
     * {@link #setText(CharSequence)}) and also sets whether it is stored
     * in a styleable/spannable buffer and whether it is editable.
     *
     * @attr ref android.R.styleable#TextView_text
     * @attr ref android.R.styleable#TextView_bufferType
     */
    public void setText(CharSequence text, BufferType type) {
        setText(text, type, true, 0);

        if (mCharWrapper != null) {
            mCharWrapper.mChars = null;
        }
    }

    private void setText(CharSequence text, BufferType type,
                         boolean notifyBefore, int oldlen) {
        if (text == null) {
            text = "";
        }

        if (!mUserSetTextScaleX) mTextPaint.setTextScaleX(1.0f);

        if (text instanceof Spanned &&
            ((Spanned) text).getSpanStart(TextUtils.TruncateAt.MARQUEE) >= 0) {
            setHorizontalFadingEdgeEnabled(true);
            setEllipsize(TextUtils.TruncateAt.MARQUEE);
        }

        int n = mFilters.length;
        for (int i = 0; i < n; i++) {
            CharSequence out = mFilters[i].filter(text, 0, text.length(),
                                                  EMPTY_SPANNED, 0, 0);
            if (out != null) {
                text = out;
            }
        }

        if (notifyBefore) {
            if (mText != null) {
                oldlen = mText.length();
                sendBeforeTextChanged(mText, 0, oldlen, text.length());
            } else {
                sendBeforeTextChanged("", 0, 0, text.length());
            }
        }

        boolean needEditableForNotification = false;

        if (mListeners != null && mListeners.size() != 0) {
            needEditableForNotification = true;
        }

        if (type == BufferType.EDITABLE || mInput != null ||
            needEditableForNotification) {
            Editable t = mEditableFactory.newEditable(text);
            text = t;
            setFilters(t, mFilters);
            InputMethodManager imm = InputMethodManager.peekInstance();
            if (imm != null) imm.restartInput(this);
        } else if (type == BufferType.SPANNABLE || mMovement != null) {
            text = mSpannableFactory.newSpannable(text);
        } else if (!(text instanceof CharWrapper)) {
            text = TextUtils.stringOrSpannedString(text);
        }

        if (mAutoLinkMask != 0) {
            Spannable s2;

            if (type == BufferType.EDITABLE || text instanceof Spannable) {
                s2 = (Spannable) text;
            } else {
                s2 = mSpannableFactory.newSpannable(text);
            }

            if (Linkify.addLinks(s2, mAutoLinkMask)) {
                text = s2;
                type = (type == BufferType.EDITABLE) ? BufferType.EDITABLE : BufferType.SPANNABLE;

                /*
                 * We must go ahead and set the text before changing the
                 * movement method, because setMovementMethod() may call
                 * setText() again to try to upgrade the buffer type.
                 */
                mText = text;

                if (mLinksClickable) {
                    setMovementMethod(LinkMovementMethod.getInstance());
                }
            }
        }

        mBufferType = type;
        mText = text;

        if (mTransformation == null)
            mTransformed = text;
        else
            mTransformed = mTransformation.getTransformation(text, this);

        final int textLength = text.length();

        if (text instanceof Spannable) {
            Spannable sp = (Spannable) text;

            // Remove any ChangeWatchers that might have come
            // from other TextViews.
            final ChangeWatcher[] watchers = sp.getSpans(0, sp.length(), ChangeWatcher.class);
            final int count = watchers.length;
            for (int i = 0; i < count; i++)
                sp.removeSpan(watchers[i]);

            if (mChangeWatcher == null)
                mChangeWatcher = new ChangeWatcher();

            sp.setSpan(mChangeWatcher, 0, textLength, Spanned.SPAN_INCLUSIVE_INCLUSIVE |
                       (PRIORITY << Spanned.SPAN_PRIORITY_SHIFT));

            if (mInput != null) {
                sp.setSpan(mInput, 0, textLength, Spanned.SPAN_INCLUSIVE_INCLUSIVE);
            }

            if (mTransformation != null) {
                sp.setSpan(mTransformation, 0, textLength, Spanned.SPAN_INCLUSIVE_INCLUSIVE);

            }

            if (mMovement != null) {
                mMovement.initialize(this, (Spannable) text);

                /*
                 * Initializing the movement method will have set the
                 * selection, so reset mSelectionMoved to keep that from
                 * interfering with the normal on-focus selection-setting.
                 */
                mSelectionMoved = false;
            }
        }

        if (mLayout != null) {
            checkForRelayout();
        }

        sendOnTextChanged(text, 0, oldlen, textLength);
        onTextChanged(text, 0, oldlen, textLength);

        if (needEditableForNotification) {
            sendAfterTextChanged((Editable) text);
        }

        // SelectionModifierCursorController depends on textCanBeSelected, which depends on text
        prepareCursorControllers();
    }

    /**
     * Sets the TextView to display the specified slice of the specified
     * char array.  You must promise that you will not change the contents
     * of the array except for right before another call to setText(),
     * since the TextView has no way to know that the text
     * has changed and that it needs to invalidate and re-layout.
     */
    public final void setText(char[] text, int start, int len) {
        int oldlen = 0;

        if (start < 0 || len < 0 || start + len > text.length) {
            throw new IndexOutOfBoundsException(start + ", " + len);
        }

        /*
         * We must do the before-notification here ourselves because if
         * the old text is a CharWrapper we destroy it before calling
         * into the normal path.
         */
        if (mText != null) {
            oldlen = mText.length();
            sendBeforeTextChanged(mText, 0, oldlen, len);
        } else {
            sendBeforeTextChanged("", 0, 0, len);
        }

        if (mCharWrapper == null) {
            mCharWrapper = new CharWrapper(text, start, len);
        } else {
            mCharWrapper.set(text, start, len);
        }

        setText(mCharWrapper, mBufferType, false, oldlen);
    }

    private static class CharWrapper
            implements CharSequence, GetChars, GraphicsOperations {
        private char[] mChars;
        private int mStart, mLength;

        public CharWrapper(char[] chars, int start, int len) {
            mChars = chars;
            mStart = start;
            mLength = len;
        }

        /* package */ void set(char[] chars, int start, int len) {
            mChars = chars;
            mStart = start;
            mLength = len;
        }

        public int length() {
            return mLength;
        }

        public char charAt(int off) {
            return mChars[off + mStart];
        }

        @Override
        public String toString() {
            return new String(mChars, mStart, mLength);
        }

        public CharSequence subSequence(int start, int end) {
            if (start < 0 || end < 0 || start > mLength || end > mLength) {
                throw new IndexOutOfBoundsException(start + ", " + end);
            }

            return new String(mChars, start + mStart, end - start);
        }

        public void getChars(int start, int end, char[] buf, int off) {
            if (start < 0 || end < 0 || start > mLength || end > mLength) {
                throw new IndexOutOfBoundsException(start + ", " + end);
            }

            System.arraycopy(mChars, start + mStart, buf, off, end - start);
        }

        public void drawText(Canvas c, int start, int end,
                             float x, float y, Paint p) {
            c.drawText(mChars, start + mStart, end - start, x, y, p);
        }

        public float measureText(int start, int end, Paint p) {
            return p.measureText(mChars, start + mStart, end - start);
        }

        public int getTextWidths(int start, int end, float[] widths, Paint p) {
            return p.getTextWidths(mChars, start + mStart, end - start, widths);
        }
    }

    /**
     * Like {@link #setText(CharSequence, android.widget.TextView.BufferType)},
     * except that the cursor position (if any) is retained in the new text.
     *
     * @see #setText(CharSequence, android.widget.TextView.BufferType)
     */
    public final void setTextKeepState(CharSequence text, BufferType type) {
        int start = getSelectionStart();
        int end = getSelectionEnd();
        int len = text.length();

        setText(text, type);

        if (start >= 0 || end >= 0) {
            if (mText instanceof Spannable) {
                Selection.setSelection((Spannable) mText,
                                       Math.max(0, Math.min(start, len)),
                                       Math.max(0, Math.min(end, len)));
            }
        }
    }

    @android.view.RemotableViewMethod
    public final void setText(int resid) {
        setText(getContext().getResources().getText(resid));
    }

    public final void setText(int resid, BufferType type) {
        setText(getContext().getResources().getText(resid), type);
    }

    /**
     * Sets the text to be displayed when the text of the TextView is empty.
     * Null means to use the normal empty text. The hint does not currently
     * participate in determining the size of the view.
     *
     * @attr ref android.R.styleable#TextView_hint
     */
    @android.view.RemotableViewMethod
    public final void setHint(CharSequence hint) {
        mHint = TextUtils.stringOrSpannedString(hint);

        if (mLayout != null) {
            checkForRelayout();
        }

        if (mText.length() == 0) {
            invalidate();
        }
    }

    /**
     * Sets the text to be displayed when the text of the TextView is empty,
     * from a resource.
     *
     * @attr ref android.R.styleable#TextView_hint
     */
    @android.view.RemotableViewMethod
    public final void setHint(int resid) {
        setHint(getContext().getResources().getText(resid));
    }

    /**
     * Returns the hint that is displayed when the text of the TextView
     * is empty.
     *
     * @attr ref android.R.styleable#TextView_hint
     */
    @ViewDebug.CapturedViewProperty
    public CharSequence getHint() {
        return mHint;
    }

    /**
     * Set the type of the content with a constant as defined for
     * {@link EditorInfo#inputType}.  This will take care of changing
     * the key listener, by calling {@link #setKeyListener(KeyListener)}, to
     * match the given content type.  If the given content type is
     * {@link EditorInfo#TYPE_NULL} then a soft keyboard will
     * not be displayed for this text view.
     *
     * @see #getInputType()
     * @see #setRawInputType(int)
     * @see android.text.InputType
     * @attr ref android.R.styleable#TextView_inputType
     */
    public void setInputType(int type) {
        final boolean wasPassword = isPasswordInputType(mInputType);
        final boolean wasVisiblePassword = isVisiblePasswordInputType(mInputType);
        setInputType(type, false);
        final boolean isPassword = isPasswordInputType(type);
        final boolean isVisiblePassword = isVisiblePasswordInputType(type);
        boolean forceUpdate = false;
        if (isPassword) {
            setTransformationMethod(PasswordTransformationMethod.getInstance());
            setTypefaceByIndex(MONOSPACE, 0);
        } else if (isVisiblePassword) {
            if (mTransformation == PasswordTransformationMethod.getInstance()) {
                forceUpdate = true;
            }
            setTypefaceByIndex(MONOSPACE, 0);
        } else if (wasPassword || wasVisiblePassword) {
            // not in password mode, clean up typeface and transformation
            setTypefaceByIndex(-1, -1);
            if (mTransformation == PasswordTransformationMethod.getInstance()) {
                forceUpdate = true;
            }
        }
        
        boolean multiLine = (type&(EditorInfo.TYPE_MASK_CLASS
                        | EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE)) ==
                (EditorInfo.TYPE_CLASS_TEXT
                        | EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE);
        
        // We need to update the single line mode if it has changed or we
        // were previously in password mode.
        if (mSingleLine == multiLine || forceUpdate) {
            // Change single line mode, but only change the transformation if
            // we are not in password mode.
            applySingleLine(!multiLine, !isPassword);
        }
        
        InputMethodManager imm = InputMethodManager.peekInstance();
        if (imm != null) imm.restartInput(this);
    }

    /**
     * It would be better to rely on the input type for everything. A password inputType should have
     * a password transformation. We should hence use isPasswordInputType instead of this method.
     *
     * We should:
     * - Call setInputType in setKeyListener instead of changing the input type directly (which
     * would install the correct transformation).
     * - Refuse the installation of a non-password transformation in setTransformation if the input
     * type is password.
     *
     * However, this is like this for legacy reasons and we cannot break existing apps. This method
     * is useful since it matches what the user can see (obfuscated text or not).
     *
     * @return true if the current transformation method is of the password type.
     */
    private boolean hasPasswordTransformationMethod() {
        return mTransformation instanceof PasswordTransformationMethod;
    }

    private boolean isPasswordInputType(int inputType) {
        final int variation = inputType & (EditorInfo.TYPE_MASK_CLASS
                | EditorInfo.TYPE_MASK_VARIATION);
        return variation
                == (EditorInfo.TYPE_CLASS_TEXT
                        | EditorInfo.TYPE_TEXT_VARIATION_PASSWORD);
    }

    private boolean isVisiblePasswordInputType(int inputType) {
        final int variation = inputType & (EditorInfo.TYPE_MASK_CLASS
                | EditorInfo.TYPE_MASK_VARIATION);
        return variation
                == (EditorInfo.TYPE_CLASS_TEXT
                        | EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
    }

    /**
     * Directly change the content type integer of the text view, without
     * modifying any other state.
     * @see #setInputType(int)
     * @see android.text.InputType
     * @attr ref android.R.styleable#TextView_inputType
     */
    public void setRawInputType(int type) {
        mInputType = type;
    }

    private void setInputType(int type, boolean direct) {
        final int cls = type & EditorInfo.TYPE_MASK_CLASS;
        KeyListener input;
        if (cls == EditorInfo.TYPE_CLASS_TEXT) {
            boolean autotext = (type & EditorInfo.TYPE_TEXT_FLAG_AUTO_CORRECT) != 0;
            TextKeyListener.Capitalize cap;
            if ((type & EditorInfo.TYPE_TEXT_FLAG_CAP_CHARACTERS) != 0) {
                cap = TextKeyListener.Capitalize.CHARACTERS;
            } else if ((type & EditorInfo.TYPE_TEXT_FLAG_CAP_WORDS) != 0) {
                cap = TextKeyListener.Capitalize.WORDS;
            } else if ((type & EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES) != 0) {
                cap = TextKeyListener.Capitalize.SENTENCES;
            } else {
                cap = TextKeyListener.Capitalize.NONE;
            }
            input = TextKeyListener.getInstance(autotext, cap);
        } else if (cls == EditorInfo.TYPE_CLASS_NUMBER) {
            input = DigitsKeyListener.getInstance(
                    (type & EditorInfo.TYPE_NUMBER_FLAG_SIGNED) != 0,
                    (type & EditorInfo.TYPE_NUMBER_FLAG_DECIMAL) != 0);
        } else if (cls == EditorInfo.TYPE_CLASS_DATETIME) {
            switch (type & EditorInfo.TYPE_MASK_VARIATION) {
                case EditorInfo.TYPE_DATETIME_VARIATION_DATE:
                    input = DateKeyListener.getInstance();
                    break;
                case EditorInfo.TYPE_DATETIME_VARIATION_TIME:
                    input = TimeKeyListener.getInstance();
                    break;
                default:
                    input = DateTimeKeyListener.getInstance();
                    break;
            }
        } else if (cls == EditorInfo.TYPE_CLASS_PHONE) {
            input = DialerKeyListener.getInstance();
        } else {
            input = TextKeyListener.getInstance();
        }
        setRawInputType(type);
        if (direct) mInput = input;
        else {
            setKeyListenerOnly(input);
        }
    }

    /**
     * Get the type of the content.
     *
     * @see #setInputType(int)
     * @see android.text.InputType
     */
    public int getInputType() {
        return mInputType;
    }

    /**
     * Change the editor type integer associated with the text view, which
     * will be reported to an IME with {@link EditorInfo#imeOptions} when it
     * has focus.
     * @see #getImeOptions
     * @see android.view.inputmethod.EditorInfo
     * @attr ref android.R.styleable#TextView_imeOptions
     */
    public void setImeOptions(int imeOptions) {
        if (mInputContentType == null) {
            mInputContentType = new InputContentType();
        }
        mInputContentType.imeOptions = imeOptions;
    }

    /**
     * Get the type of the IME editor.
     *
     * @see #setImeOptions(int)
     * @see android.view.inputmethod.EditorInfo
     */
    public int getImeOptions() {
        return mInputContentType != null
                ? mInputContentType.imeOptions : EditorInfo.IME_NULL;
    }

    /**
     * Change the custom IME action associated with the text view, which
     * will be reported to an IME with {@link EditorInfo#actionLabel}
     * and {@link EditorInfo#actionId} when it has focus.
     * @see #getImeActionLabel
     * @see #getImeActionId
     * @see android.view.inputmethod.EditorInfo
     * @attr ref android.R.styleable#TextView_imeActionLabel
     * @attr ref android.R.styleable#TextView_imeActionId
     */
    public void setImeActionLabel(CharSequence label, int actionId) {
        if (mInputContentType == null) {
            mInputContentType = new InputContentType();
        }
        mInputContentType.imeActionLabel = label;
        mInputContentType.imeActionId = actionId;
    }

    /**
     * Get the IME action label previous set with {@link #setImeActionLabel}.
     *
     * @see #setImeActionLabel
     * @see android.view.inputmethod.EditorInfo
     */
    public CharSequence getImeActionLabel() {
        return mInputContentType != null
                ? mInputContentType.imeActionLabel : null;
    }

    /**
     * Get the IME action ID previous set with {@link #setImeActionLabel}.
     *
     * @see #setImeActionLabel
     * @see android.view.inputmethod.EditorInfo
     */
    public int getImeActionId() {
        return mInputContentType != null
                ? mInputContentType.imeActionId : 0;
    }

    /**
     * Set a special listener to be called when an action is performed
     * on the text view.  This will be called when the enter key is pressed,
     * or when an action supplied to the IME is selected by the user.  Setting
     * this means that the normal hard key event will not insert a newline
     * into the text view, even if it is multi-line; holding down the ALT
     * modifier will, however, allow the user to insert a newline character.
     */
    public void setOnEditorActionListener(OnEditorActionListener l) {
        if (mInputContentType == null) {
            mInputContentType = new InputContentType();
        }
        mInputContentType.onEditorActionListener = l;
    }
    
    /**
     * Called when an attached input method calls
     * {@link InputConnection#performEditorAction(int)
     * InputConnection.performEditorAction()}
     * for this text view.  The default implementation will call your action
     * listener supplied to {@link #setOnEditorActionListener}, or perform
     * a standard operation for {@link EditorInfo#IME_ACTION_NEXT
     * EditorInfo.IME_ACTION_NEXT} or {@link EditorInfo#IME_ACTION_DONE
     * EditorInfo.IME_ACTION_DONE}.
     * 
     * <p>For backwards compatibility, if no IME options have been set and the
     * text view would not normally advance focus on enter, then
     * the NEXT and DONE actions received here will be turned into an enter
     * key down/up pair to go through the normal key handling.
     * 
     * @param actionCode The code of the action being performed.
     * 
     * @see #setOnEditorActionListener
     */
    public void onEditorAction(int actionCode) {
        final InputContentType ict = mInputContentType;
        if (ict != null) {
            if (ict.onEditorActionListener != null) {
                if (ict.onEditorActionListener.onEditorAction(this,
                        actionCode, null)) {
                    return;
                }
            }
            
            // This is the handling for some default action.
            // Note that for backwards compatibility we don't do this
            // default handling if explicit ime options have not been given,
            // instead turning this into the normal enter key codes that an
            // app may be expecting.
            if (actionCode == EditorInfo.IME_ACTION_NEXT) {
                View v = focusSearch(FOCUS_DOWN);
                if (v != null) {
                    if (!v.requestFocus(FOCUS_DOWN)) {
                        throw new IllegalStateException("focus search returned a view " +
                                "that wasn't able to take focus!");
                    }
                }
                return;
                
            } else if (actionCode == EditorInfo.IME_ACTION_DONE) {
                InputMethodManager imm = InputMethodManager.peekInstance();
                if (imm != null) {
                    imm.hideSoftInputFromWindow(getWindowToken(), 0);
                }
                return;
            }
        }
        
        Handler h = getHandler();
        if (h != null) {
            long eventTime = SystemClock.uptimeMillis();
            h.sendMessage(h.obtainMessage(ViewRoot.DISPATCH_KEY_FROM_IME,
                    new KeyEvent(eventTime, eventTime,
                    KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0, 0, 0, 0,
                    KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE
                    | KeyEvent.FLAG_EDITOR_ACTION)));
            h.sendMessage(h.obtainMessage(ViewRoot.DISPATCH_KEY_FROM_IME,
                    new KeyEvent(SystemClock.uptimeMillis(), eventTime,
                    KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0, 0, 0, 0,
                    KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE
                    | KeyEvent.FLAG_EDITOR_ACTION)));
        }
    }
    
    /**
     * Set the private content type of the text, which is the
     * {@link EditorInfo#privateImeOptions EditorInfo.privateImeOptions}
     * field that will be filled in when creating an input connection.
     *
     * @see #getPrivateImeOptions()
     * @see EditorInfo#privateImeOptions
     * @attr ref android.R.styleable#TextView_privateImeOptions
     */
    public void setPrivateImeOptions(String type) {
        if (mInputContentType == null) mInputContentType = new InputContentType();
        mInputContentType.privateImeOptions = type;
    }

    /**
     * Get the private type of the content.
     *
     * @see #setPrivateImeOptions(String)
     * @see EditorInfo#privateImeOptions
     */
    public String getPrivateImeOptions() {
        return mInputContentType != null
                ? mInputContentType.privateImeOptions : null;
    }

    /**
     * Set the extra input data of the text, which is the
     * {@link EditorInfo#extras TextBoxAttribute.extras}
     * Bundle that will be filled in when creating an input connection.  The
     * given integer is the resource ID of an XML resource holding an
     * {@link android.R.styleable#InputExtras &lt;input-extras&gt;} XML tree.
     *
     * @see #getInputExtras(boolean) 
     * @see EditorInfo#extras
     * @attr ref android.R.styleable#TextView_editorExtras
     */
    public void setInputExtras(int xmlResId)
            throws XmlPullParserException, IOException {
        XmlResourceParser parser = getResources().getXml(xmlResId);
        if (mInputContentType == null) mInputContentType = new InputContentType();
        mInputContentType.extras = new Bundle();
        getResources().parseBundleExtras(parser, mInputContentType.extras);
    }

    /**
     * Retrieve the input extras currently associated with the text view, which
     * can be viewed as well as modified.
     *
     * @param create If true, the extras will be created if they don't already
     * exist.  Otherwise, null will be returned if none have been created.
     * @see #setInputExtras(int)
     * @see EditorInfo#extras
     * @attr ref android.R.styleable#TextView_editorExtras
     */
    public Bundle getInputExtras(boolean create) {
        if (mInputContentType == null) {
            if (!create) return null;
            mInputContentType = new InputContentType();
        }
        if (mInputContentType.extras == null) {
            if (!create) return null;
            mInputContentType.extras = new Bundle();
        }
        return mInputContentType.extras;
    }

    /**
     * Returns the error message that was set to be displayed with
     * {@link #setError}, or <code>null</code> if no error was set
     * or if it the error was cleared by the widget after user input.
     */
    public CharSequence getError() {
        return mError;
    }

    /**
     * Sets the right-hand compound drawable of the TextView to the "error"
     * icon and sets an error message that will be displayed in a popup when
     * the TextView has focus.  The icon and error message will be reset to
     * null when any key events cause changes to the TextView's text.  If the
     * <code>error</code> is <code>null</code>, the error message and icon
     * will be cleared.
     */
    @android.view.RemotableViewMethod
    public void setError(CharSequence error) {
        if (error == null) {
            setError(null, null);
        } else {
            Drawable dr = getContext().getResources().
                getDrawable(com.android.internal.R.drawable.
                            indicator_input_error);

            dr.setBounds(0, 0, dr.getIntrinsicWidth(), dr.getIntrinsicHeight());
            setError(error, dr);
        }
    }

    /**
     * Sets the right-hand compound drawable of the TextView to the specified
     * icon and sets an error message that will be displayed in a popup when
     * the TextView has focus.  The icon and error message will be reset to
     * null when any key events cause changes to the TextView's text.  The
     * drawable must already have had {@link Drawable#setBounds} set on it.
     * If the <code>error</code> is <code>null</code>, the error message will
     * be cleared (and you should provide a <code>null</code> icon as well).
     */
    public void setError(CharSequence error, Drawable icon) {
        error = TextUtils.stringOrSpannedString(error);

        mError = error;
        mErrorWasChanged = true;
        final Drawables dr = mDrawables;
        if (dr != null) {
            setCompoundDrawables(dr.mDrawableLeft, dr.mDrawableTop,
                                 icon, dr.mDrawableBottom);
        } else {
            setCompoundDrawables(null, null, icon, null);
        }

        if (error == null) {
            if (mPopup != null) {
                if (mPopup.isShowing()) {
                    mPopup.dismiss();
                }

                mPopup = null;
            }
        } else {
            if (isFocused()) {
                showError();
            }
        }
    }

    private void showError() {
        if (getWindowToken() == null) {
            mShowErrorAfterAttach = true;
            return;
        }

        if (mPopup == null) {
            LayoutInflater inflater = LayoutInflater.from(getContext());
            final TextView err = (TextView) inflater.inflate(com.android.internal.R.layout.textview_hint,
                    null);

            final float scale = getResources().getDisplayMetrics().density;
            mPopup = new ErrorPopup(err, (int) (200 * scale + 0.5f),
                    (int) (50 * scale + 0.5f));
            mPopup.setFocusable(false);
            // The user is entering text, so the input method is needed.  We
            // don't want the popup to be displayed on top of it.
            mPopup.setInputMethodMode(PopupWindow.INPUT_METHOD_NEEDED);
        }

        TextView tv = (TextView) mPopup.getContentView();
        chooseSize(mPopup, mError, tv);
        tv.setText(mError);

        mPopup.showAsDropDown(this, getErrorX(), getErrorY());
        mPopup.fixDirection(mPopup.isAboveAnchor());
    }

    private static class ErrorPopup extends PopupWindow {
        private boolean mAbove = false;
        private final TextView mView;

        ErrorPopup(TextView v, int width, int height) {
            super(v, width, height);
            mView = v;
        }

        void fixDirection(boolean above) {
            mAbove = above;

            if (above) {
                mView.setBackgroundResource(com.android.internal.R.drawable.popup_inline_error_above);
            } else {
                mView.setBackgroundResource(com.android.internal.R.drawable.popup_inline_error);
            }
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

    /**
     * Returns the Y offset to make the pointy top of the error point
     * at the middle of the error icon.
     */
    private int getErrorX() {
        /*
         * The "25" is the distance between the point and the right edge
         * of the background
         */
        final float scale = getResources().getDisplayMetrics().density;

        final Drawables dr = mDrawables;
        return getWidth() - mPopup.getWidth()
                - getPaddingRight()
                - (dr != null ? dr.mDrawableSizeRight : 0) / 2 + (int) (25 * scale + 0.5f);
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
        int vspace = mBottom - mTop -
                     getCompoundPaddingBottom() - getCompoundPaddingTop();

        final Drawables dr = mDrawables;
        int icontop = getCompoundPaddingTop()
                + (vspace - (dr != null ? dr.mDrawableHeightRight : 0)) / 2;

        /*
         * The "2" is the distance between the point and the top edge
         * of the background.
         */

        return icontop + (dr != null ? dr.mDrawableHeightRight : 0)
                - getHeight() - 2;
    }

    private void hideError() {
        if (mPopup != null) {
            if (mPopup.isShowing()) {
                mPopup.dismiss();
            }
        }

        mShowErrorAfterAttach = false;
    }

    private void chooseSize(PopupWindow pop, CharSequence text, TextView tv) {
        int wid = tv.getPaddingLeft() + tv.getPaddingRight();
        int ht = tv.getPaddingTop() + tv.getPaddingBottom();

        /*
         * Figure out how big the text would be if we laid it out to the
         * full width of this view minus the border.
         */
        int cap = getWidth() - wid;
        if (cap < 0) {
            cap = 200; // We must not be measured yet -- setFrame() will fix it.
        }

        Layout l = new StaticLayout(text, tv.getPaint(), cap,
                                    Layout.Alignment.ALIGN_NORMAL, 1, 0, true);
        float max = 0;
        for (int i = 0; i < l.getLineCount(); i++) {
            max = Math.max(max, l.getLineWidth(i));
        }

        /*
         * Now set the popup size to be big enough for the text plus the border.
         */
        pop.setWidth(wid + (int) Math.ceil(max));
        pop.setHeight(ht + l.getHeight());
    }


    @Override
    protected boolean setFrame(int l, int t, int r, int b) {
        boolean result = super.setFrame(l, t, r, b);

        if (mPopup != null) {
            TextView tv = (TextView) mPopup.getContentView();
            chooseSize(mPopup, mError, tv);
            mPopup.update(this, getErrorX(), getErrorY(),
                          mPopup.getWidth(), mPopup.getHeight());
        }

        restartMarqueeIfNeeded();

        return result;
    }

    private void restartMarqueeIfNeeded() {
        if (mRestartMarquee && mEllipsize == TextUtils.TruncateAt.MARQUEE) {
            mRestartMarquee = false;
            startMarquee();
        }
    }

    /**
     * Sets the list of input filters that will be used if the buffer is
     * Editable.  Has no effect otherwise.
     *
     * @attr ref android.R.styleable#TextView_maxLength
     */
    public void setFilters(InputFilter[] filters) {
        if (filters == null) {
            throw new IllegalArgumentException();
        }

        mFilters = filters;

        if (mText instanceof Editable) {
            setFilters((Editable) mText, filters);
        }
    }

    /**
     * Sets the list of input filters on the specified Editable,
     * and includes mInput in the list if it is an InputFilter.
     */
    private void setFilters(Editable e, InputFilter[] filters) {
        if (mInput instanceof InputFilter) {
            InputFilter[] nf = new InputFilter[filters.length + 1];

            System.arraycopy(filters, 0, nf, 0, filters.length);
            nf[filters.length] = (InputFilter) mInput;

            e.setFilters(nf);
        } else {
            e.setFilters(filters);
        }
    }

    /**
     * Returns the current list of input filters.
     */
    public InputFilter[] getFilters() {
        return mFilters;
    }

    /////////////////////////////////////////////////////////////////////////

    private int getVerticalOffset(boolean forceNormal) {
        int voffset = 0;
        final int gravity = mGravity & Gravity.VERTICAL_GRAVITY_MASK;

        Layout l = mLayout;
        if (!forceNormal && mText.length() == 0 && mHintLayout != null) {
            l = mHintLayout;
        }

        if (gravity != Gravity.TOP) {
            int boxht;

            if (l == mHintLayout) {
                boxht = getMeasuredHeight() - getCompoundPaddingTop() -
                        getCompoundPaddingBottom();
            } else {
                boxht = getMeasuredHeight() - getExtendedPaddingTop() -
                        getExtendedPaddingBottom();
            }
            int textht = l.getHeight();

            if (textht < boxht) {
                if (gravity == Gravity.BOTTOM)
                    voffset = boxht - textht;
                else // (gravity == Gravity.CENTER_VERTICAL)
                    voffset = (boxht - textht) >> 1;
            }
        }
        return voffset;
    }

    private int getBottomVerticalOffset(boolean forceNormal) {
        int voffset = 0;
        final int gravity = mGravity & Gravity.VERTICAL_GRAVITY_MASK;

        Layout l = mLayout;
        if (!forceNormal && mText.length() == 0 && mHintLayout != null) {
            l = mHintLayout;
        }

        if (gravity != Gravity.BOTTOM) {
            int boxht;

            if (l == mHintLayout) {
                boxht = getMeasuredHeight() - getCompoundPaddingTop() -
                        getCompoundPaddingBottom();
            } else {
                boxht = getMeasuredHeight() - getExtendedPaddingTop() -
                        getExtendedPaddingBottom();
            }
            int textht = l.getHeight();

            if (textht < boxht) {
                if (gravity == Gravity.TOP)
                    voffset = boxht - textht;
                else // (gravity == Gravity.CENTER_VERTICAL)
                    voffset = (boxht - textht) >> 1;
            }
        }
        return voffset;
    }

    private void invalidateCursorPath() {
        if (mHighlightPathBogus) {
            invalidateCursor();
        } else {
            synchronized (sTempRect) {
                /*
                 * The reason for this concern about the thickness of the
                 * cursor and doing the floor/ceil on the coordinates is that
                 * some EditTexts (notably textfields in the Browser) have
                 * anti-aliased text where not all the characters are
                 * necessarily at integer-multiple locations.  This should
                 * make sure the entire cursor gets invalidated instead of
                 * sometimes missing half a pixel.
                 */

                float thick = FloatMath.ceil(mTextPaint.getStrokeWidth());
                if (thick < 1.0f) {
                    thick = 1.0f;
                }

                thick /= 2;

                mHighlightPath.computeBounds(sTempRect, false);

                int left = getCompoundPaddingLeft();
                int top = getExtendedPaddingTop() + getVerticalOffset(true);

                invalidate((int) FloatMath.floor(left + sTempRect.left - thick),
                           (int) FloatMath.floor(top + sTempRect.top - thick),
                           (int) FloatMath.ceil(left + sTempRect.right + thick),
                           (int) FloatMath.ceil(top + sTempRect.bottom + thick));
            }
        }
    }

    private void invalidateCursor() {
        int where = getSelectionEnd();

        invalidateCursor(where, where, where);
    }

    private void invalidateCursor(int a, int b, int c) {
        if (mLayout == null) {
            invalidate();
        } else {
            if (a >= 0 || b >= 0 || c >= 0) {
                int first = Math.min(Math.min(a, b), c);
                int last = Math.max(Math.max(a, b), c);

                int line = mLayout.getLineForOffset(first);
                int top = mLayout.getLineTop(line);

                // This is ridiculous, but the descent from the line above
                // can hang down into the line we really want to redraw,
                // so we have to invalidate part of the line above to make
                // sure everything that needs to be redrawn really is.
                // (But not the whole line above, because that would cause
                // the same problem with the descenders on the line above it!)
                if (line > 0) {
                    top -= mLayout.getLineDescent(line - 1);
                }

                int line2;

                if (first == last)
                    line2 = line;
                else
                    line2 = mLayout.getLineForOffset(last);

                int bottom = mLayout.getLineTop(line2 + 1);
                int voffset = getVerticalOffset(true);

                int left = getCompoundPaddingLeft() + mScrollX;
                invalidate(left, top + voffset + getExtendedPaddingTop(),
                           left + getWidth() - getCompoundPaddingLeft() -
                           getCompoundPaddingRight(),
                           bottom + voffset + getExtendedPaddingTop());
            }
        }
    }

    private void registerForPreDraw() {
        final ViewTreeObserver observer = getViewTreeObserver();
        if (observer == null) {
            return;
        }

        if (mPreDrawState == PREDRAW_NOT_REGISTERED) {
            observer.addOnPreDrawListener(this);
            mPreDrawState = PREDRAW_PENDING;
        } else if (mPreDrawState == PREDRAW_DONE) {
            mPreDrawState = PREDRAW_PENDING;
        }

        // else state is PREDRAW_PENDING, so keep waiting.
    }

    /**
     * {@inheritDoc}
     */
    public boolean onPreDraw() {
        if (mPreDrawState != PREDRAW_PENDING) {
            return true;
        }

        if (mLayout == null) {
            assumeLayout();
        }

        boolean changed = false;

        SelectionModifierCursorController selectionController = null;
        if (mSelectionModifierCursorController != null) {
            selectionController = (SelectionModifierCursorController)
                mSelectionModifierCursorController;
        }


        if (mMovement != null) {
            /* This code also provides auto-scrolling when a cursor is moved using a
             * CursorController (insertion point or selection limits).
             * For selection, ensure start or end is visible depending on controller's state.
             */
            int curs = getSelectionEnd();
            if (selectionController != null && selectionController.isSelectionStartDragged()) {
                curs = getSelectionStart();
            }

            /*
             * TODO: This should really only keep the end in view if
             * it already was before the text changed.  I'm not sure
             * of a good way to tell from here if it was.
             */
            if (curs < 0 &&
                  (mGravity & Gravity.VERTICAL_GRAVITY_MASK) == Gravity.BOTTOM) {
                curs = mText.length();
            }

            if (curs >= 0) {
                changed = bringPointIntoView(curs);
            }
        } else {
            changed = bringTextIntoView();
        }

        // This has to be checked here since:
        // - onFocusChanged cannot start it when focus is given to a view with selected text (after
        //   a screen rotation) since layout is not yet initialized at that point.
        // - ExtractEditText does not call onFocus when it is displayed. Fixing this issue would
        //   allow to test for hasSelection in onFocusChanged, which would trigger a
        //   startTextSelectionMode here. TODO
        if (mCreatedWithASelection ||
           (this instanceof ExtractEditText && selectionController != null && hasSelection())) {
            startTextSelectionMode();
            mCreatedWithASelection = false;
        }

        mPreDrawState = PREDRAW_DONE;
        return !changed;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        mTemporaryDetach = false;
        
        if (mShowErrorAfterAttach) {
            showError();
            mShowErrorAfterAttach = false;
        }

        final ViewTreeObserver observer = getViewTreeObserver();
        if (observer != null) {
            if (mInsertionPointCursorController != null) {
                observer.addOnTouchModeChangeListener(mInsertionPointCursorController);
            }
            if (mSelectionModifierCursorController != null) {
                observer.addOnTouchModeChangeListener(mSelectionModifierCursorController);
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        final ViewTreeObserver observer = getViewTreeObserver();
        if (observer != null) {
            if (mPreDrawState != PREDRAW_NOT_REGISTERED) {
                observer.removeOnPreDrawListener(this);
                mPreDrawState = PREDRAW_NOT_REGISTERED;
            }
            if (mInsertionPointCursorController != null) {
                observer.removeOnTouchModeChangeListener(mInsertionPointCursorController);
            }
            if (mSelectionModifierCursorController != null) {
                observer.removeOnTouchModeChangeListener(mSelectionModifierCursorController);
            }
        }

        if (mError != null) {
            hideError();
        }

        if (mBlink != null) {
            mBlink.cancel();
        }

        if (mInsertionPointCursorController != null) {
            mInsertionPointCursorController.onDetached();
        }

        if (mSelectionModifierCursorController != null) {
            mSelectionModifierCursorController.onDetached();
        }

        hideControllers();
    }

    @Override
    protected boolean isPaddingOffsetRequired() {
        return mShadowRadius != 0 || mDrawables != null;
    }

    @Override
    protected int getLeftPaddingOffset() {
        return getCompoundPaddingLeft() - mPaddingLeft +
                (int) Math.min(0, mShadowDx - mShadowRadius);
    }

    @Override
    protected int getTopPaddingOffset() {
        return (int) Math.min(0, mShadowDy - mShadowRadius);
    }

    @Override
    protected int getBottomPaddingOffset() {
        return (int) Math.max(0, mShadowDy + mShadowRadius);
    }

    @Override
    protected int getRightPaddingOffset() {
        return -(getCompoundPaddingRight() - mPaddingRight) +
                (int) Math.max(0, mShadowDx + mShadowRadius);
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        final boolean verified = super.verifyDrawable(who);
        if (!verified && mDrawables != null) {
            return who == mDrawables.mDrawableLeft || who == mDrawables.mDrawableTop ||
                    who == mDrawables.mDrawableRight || who == mDrawables.mDrawableBottom;
        }
        return verified;
    }

    @Override
    public void invalidateDrawable(Drawable drawable) {
        if (verifyDrawable(drawable)) {
            final Rect dirty = drawable.getBounds();
            int scrollX = mScrollX;
            int scrollY = mScrollY;

            // IMPORTANT: The coordinates below are based on the coordinates computed
            // for each compound drawable in onDraw(). Make sure to update each section
            // accordingly.
            final TextView.Drawables drawables = mDrawables;
            if (drawables != null) {
                if (drawable == drawables.mDrawableLeft) {
                    final int compoundPaddingTop = getCompoundPaddingTop();
                    final int compoundPaddingBottom = getCompoundPaddingBottom();
                    final int vspace = mBottom - mTop - compoundPaddingBottom - compoundPaddingTop;

                    scrollX += mPaddingLeft;
                    scrollY += compoundPaddingTop + (vspace - drawables.mDrawableHeightLeft) / 2;
                } else if (drawable == drawables.mDrawableRight) {
                    final int compoundPaddingTop = getCompoundPaddingTop();
                    final int compoundPaddingBottom = getCompoundPaddingBottom();
                    final int vspace = mBottom - mTop - compoundPaddingBottom - compoundPaddingTop;

                    scrollX += (mRight - mLeft - mPaddingRight - drawables.mDrawableSizeRight);
                    scrollY += compoundPaddingTop + (vspace - drawables.mDrawableHeightRight) / 2;
                } else if (drawable == drawables.mDrawableTop) {
                    final int compoundPaddingLeft = getCompoundPaddingLeft();
                    final int compoundPaddingRight = getCompoundPaddingRight();
                    final int hspace = mRight - mLeft - compoundPaddingRight - compoundPaddingLeft;

                    scrollX += compoundPaddingLeft + (hspace - drawables.mDrawableWidthTop) / 2;
                    scrollY += mPaddingTop;
                } else if (drawable == drawables.mDrawableBottom) {
                    final int compoundPaddingLeft = getCompoundPaddingLeft();
                    final int compoundPaddingRight = getCompoundPaddingRight();
                    final int hspace = mRight - mLeft - compoundPaddingRight - compoundPaddingLeft;

                    scrollX += compoundPaddingLeft + (hspace - drawables.mDrawableWidthBottom) / 2;
                    scrollY += (mBottom - mTop - mPaddingBottom - drawables.mDrawableSizeBottom);
                }
            }

            invalidate(dirty.left + scrollX, dirty.top + scrollY,
                    dirty.right + scrollX, dirty.bottom + scrollY);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        restartMarqueeIfNeeded();

        // Draw the background for this view
        super.onDraw(canvas);

        final int compoundPaddingLeft = getCompoundPaddingLeft();
        final int compoundPaddingTop = getCompoundPaddingTop();
        final int compoundPaddingRight = getCompoundPaddingRight();
        final int compoundPaddingBottom = getCompoundPaddingBottom();
        final int scrollX = mScrollX;
        final int scrollY = mScrollY;
        final int right = mRight;
        final int left = mLeft;
        final int bottom = mBottom;
        final int top = mTop;

        final Drawables dr = mDrawables;
        if (dr != null) {
            /*
             * Compound, not extended, because the icon is not clipped
             * if the text height is smaller.
             */

            int vspace = bottom - top - compoundPaddingBottom - compoundPaddingTop;
            int hspace = right - left - compoundPaddingRight - compoundPaddingLeft;

            // IMPORTANT: The coordinates computed are also used in invalidateDrawable()
            // Make sure to update invalidateDrawable() when changing this code.
            if (dr.mDrawableLeft != null) {
                canvas.save();
                canvas.translate(scrollX + mPaddingLeft,
                                 scrollY + compoundPaddingTop +
                                 (vspace - dr.mDrawableHeightLeft) / 2);
                dr.mDrawableLeft.draw(canvas);
                canvas.restore();
            }

            // IMPORTANT: The coordinates computed are also used in invalidateDrawable()
            // Make sure to update invalidateDrawable() when changing this code.
            if (dr.mDrawableRight != null) {
                canvas.save();
                canvas.translate(scrollX + right - left - mPaddingRight - dr.mDrawableSizeRight,
                         scrollY + compoundPaddingTop + (vspace - dr.mDrawableHeightRight) / 2);
                dr.mDrawableRight.draw(canvas);
                canvas.restore();
            }

            // IMPORTANT: The coordinates computed are also used in invalidateDrawable()
            // Make sure to update invalidateDrawable() when changing this code.
            if (dr.mDrawableTop != null) {
                canvas.save();
                canvas.translate(scrollX + compoundPaddingLeft + (hspace - dr.mDrawableWidthTop) / 2,
                        scrollY + mPaddingTop);
                dr.mDrawableTop.draw(canvas);
                canvas.restore();
            }

            // IMPORTANT: The coordinates computed are also used in invalidateDrawable()
            // Make sure to update invalidateDrawable() when changing this code.
            if (dr.mDrawableBottom != null) {
                canvas.save();
                canvas.translate(scrollX + compoundPaddingLeft +
                        (hspace - dr.mDrawableWidthBottom) / 2,
                         scrollY + bottom - top - mPaddingBottom - dr.mDrawableSizeBottom);
                dr.mDrawableBottom.draw(canvas);
                canvas.restore();
            }
        }

        if (mPreDrawState == PREDRAW_DONE) {
            final ViewTreeObserver observer = getViewTreeObserver();
            if (observer != null) {
                observer.removeOnPreDrawListener(this);
                mPreDrawState = PREDRAW_NOT_REGISTERED;
            }
        }

        int color = mCurTextColor;

        if (mLayout == null) {
            assumeLayout();
        }

        Layout layout = mLayout;
        int cursorcolor = color;

        if (mHint != null && mText.length() == 0) {
            if (mHintTextColor != null) {
                color = mCurHintTextColor;
            }

            layout = mHintLayout;
        }

        mTextPaint.setColor(color);
        mTextPaint.drawableState = getDrawableState();

        canvas.save();
        /*  Would be faster if we didn't have to do this. Can we chop the
            (displayable) text so that we don't need to do this ever?
        */

        int extendedPaddingTop = getExtendedPaddingTop();
        int extendedPaddingBottom = getExtendedPaddingBottom();

        float clipLeft = compoundPaddingLeft + scrollX;
        float clipTop = extendedPaddingTop + scrollY;
        float clipRight = right - left - compoundPaddingRight + scrollX;
        float clipBottom = bottom - top - extendedPaddingBottom + scrollY;

        if (mShadowRadius != 0) {
            clipLeft += Math.min(0, mShadowDx - mShadowRadius);
            clipRight += Math.max(0, mShadowDx + mShadowRadius);

            clipTop += Math.min(0, mShadowDy - mShadowRadius);
            clipBottom += Math.max(0, mShadowDy + mShadowRadius);
        }

        canvas.clipRect(clipLeft, clipTop, clipRight, clipBottom);

        int voffsetText = 0;
        int voffsetCursor = 0;

        // translate in by our padding
        {
            /* shortcircuit calling getVerticaOffset() */
            if ((mGravity & Gravity.VERTICAL_GRAVITY_MASK) != Gravity.TOP) {
                voffsetText = getVerticalOffset(false);
                voffsetCursor = getVerticalOffset(true);
            }
            canvas.translate(compoundPaddingLeft, extendedPaddingTop + voffsetText);
        }

        if (mEllipsize == TextUtils.TruncateAt.MARQUEE) {
            if (!mSingleLine && getLineCount() == 1 && canMarquee() &&
                    (mGravity & Gravity.HORIZONTAL_GRAVITY_MASK) != Gravity.LEFT) {
                canvas.translate(mLayout.getLineRight(0) - (mRight - mLeft -
                        getCompoundPaddingLeft() - getCompoundPaddingRight()), 0.0f);
            }

            if (mMarquee != null && mMarquee.isRunning()) {
                canvas.translate(-mMarquee.mScroll, 0.0f);
            }
        }

        Path highlight = null;
        int selStart = -1, selEnd = -1;

        //  If there is no movement method, then there can be no selection.
        //  Check that first and attempt to skip everything having to do with
        //  the cursor.
        //  XXX This is not strictly true -- a program could set the
        //  selection manually if it really wanted to.
        if (mMovement != null && (isFocused() || isPressed())) {
            selStart = getSelectionStart();
            selEnd = getSelectionEnd();

            if (mCursorVisible && selStart >= 0 && isEnabled()) {
                if (mHighlightPath == null)
                    mHighlightPath = new Path();

                if (selStart == selEnd) {
                    if ((SystemClock.uptimeMillis() - mShowCursor) % (2 * BLINK) < BLINK) {
                        if (mHighlightPathBogus) {
                            mHighlightPath.reset();
                            mLayout.getCursorPath(selStart, mHighlightPath, mText);
                            mHighlightPathBogus = false;
                        }

                        // XXX should pass to skin instead of drawing directly
                        mHighlightPaint.setColor(cursorcolor);
                        mHighlightPaint.setStyle(Paint.Style.STROKE);

                        highlight = mHighlightPath;
                    }
                } else {
                    if (mHighlightPathBogus) {
                        mHighlightPath.reset();
                        mLayout.getSelectionPath(selStart, selEnd, mHighlightPath);
                        mHighlightPathBogus = false;
                    }

                    // XXX should pass to skin instead of drawing directly
                    mHighlightPaint.setColor(mHighlightColor);
                    mHighlightPaint.setStyle(Paint.Style.FILL);

                    highlight = mHighlightPath;
                }
            }
        }

        /*  Comment out until we decide what to do about animations
        boolean isLinearTextOn = false;
        if (currentTransformation != null) {
            isLinearTextOn = mTextPaint.isLinearTextOn();
            Matrix m = currentTransformation.getMatrix();
            if (!m.isIdentity()) {
                // mTextPaint.setLinearTextOn(true);
            }
        }
        */

        final InputMethodState ims = mInputMethodState;
        if (ims != null && ims.mBatchEditNesting == 0) {
            InputMethodManager imm = InputMethodManager.peekInstance();
            if (imm != null) {
                if (imm.isActive(this)) {
                    boolean reported = false;
                    if (ims.mContentChanged || ims.mSelectionModeChanged) {
                        // We are in extract mode and the content has changed
                        // in some way... just report complete new text to the
                        // input method.
                        reported = reportExtractedText();
                    }
                    if (!reported && highlight != null) {
                        int candStart = -1;
                        int candEnd = -1;
                        if (mText instanceof Spannable) {
                            Spannable sp = (Spannable)mText;
                            candStart = EditableInputConnection.getComposingSpanStart(sp);
                            candEnd = EditableInputConnection.getComposingSpanEnd(sp);
                        }
                        imm.updateSelection(this, selStart, selEnd, candStart, candEnd);
                    }
                }
                
                if (imm.isWatchingCursor(this) && highlight != null) {
                    highlight.computeBounds(ims.mTmpRectF, true);
                    ims.mTmpOffset[0] = ims.mTmpOffset[1] = 0;
    
                    canvas.getMatrix().mapPoints(ims.mTmpOffset);
                    ims.mTmpRectF.offset(ims.mTmpOffset[0], ims.mTmpOffset[1]);
    
                    ims.mTmpRectF.offset(0, voffsetCursor - voffsetText);
    
                    ims.mCursorRectInWindow.set((int)(ims.mTmpRectF.left + 0.5),
                            (int)(ims.mTmpRectF.top + 0.5),
                            (int)(ims.mTmpRectF.right + 0.5),
                            (int)(ims.mTmpRectF.bottom + 0.5));
    
                    imm.updateCursor(this,
                            ims.mCursorRectInWindow.left, ims.mCursorRectInWindow.top,
                            ims.mCursorRectInWindow.right, ims.mCursorRectInWindow.bottom);
                }
            }
        }

        layout.draw(canvas, highlight, mHighlightPaint, voffsetCursor - voffsetText);

        if (mMarquee != null && mMarquee.shouldDrawGhost()) {
            canvas.translate((int) mMarquee.getGhostOffset(), 0.0f);
            layout.draw(canvas, highlight, mHighlightPaint, voffsetCursor - voffsetText);
        }

        /*  Comment out until we decide what to do about animations
        if (currentTransformation != null) {
            mTextPaint.setLinearTextOn(isLinearTextOn);
        }
        */

        canvas.restore();

        updateCursorControllerPositions();
    }

    /**
     * Update the positions of the CursorControllers.  Needed by WebTextView,
     * which does not draw.
     * @hide
     */
    protected void updateCursorControllerPositions() {
        if (mInsertionPointCursorController != null &&
                mInsertionPointCursorController.isShowing()) {
            mInsertionPointCursorController.updatePosition();
        }

        if (mSelectionModifierCursorController != null &&
                mSelectionModifierCursorController.isShowing()) {
            mSelectionModifierCursorController.updatePosition();
        }
    }

    @Override
    public void getFocusedRect(Rect r) {
        if (mLayout == null) {
            super.getFocusedRect(r);
            return;
        }

        int sel = getSelectionEnd();
        if (sel < 0) {
            super.getFocusedRect(r);
            return;
        }

        int line = mLayout.getLineForOffset(sel);
        r.top = mLayout.getLineTop(line);
        r.bottom = mLayout.getLineBottom(line);

        r.left = (int) mLayout.getPrimaryHorizontal(sel);
        r.right = r.left + 1;

        // Adjust for padding and gravity.
        int paddingLeft = getCompoundPaddingLeft();
        int paddingTop = getExtendedPaddingTop();
        if ((mGravity & Gravity.VERTICAL_GRAVITY_MASK) != Gravity.TOP) {
            paddingTop += getVerticalOffset(false);
        }
        r.offset(paddingLeft, paddingTop);
    }

    /**
     * Return the number of lines of text, or 0 if the internal Layout has not
     * been built.
     */
    public int getLineCount() {
        return mLayout != null ? mLayout.getLineCount() : 0;
    }

    /**
     * Return the baseline for the specified line (0...getLineCount() - 1)
     * If bounds is not null, return the top, left, right, bottom extents
     * of the specified line in it. If the internal Layout has not been built,
     * return 0 and set bounds to (0, 0, 0, 0)
     * @param line which line to examine (0..getLineCount() - 1)
     * @param bounds Optional. If not null, it returns the extent of the line
     * @return the Y-coordinate of the baseline
     */
    public int getLineBounds(int line, Rect bounds) {
        if (mLayout == null) {
            if (bounds != null) {
                bounds.set(0, 0, 0, 0);
            }
            return 0;
        }
        else {
            int baseline = mLayout.getLineBounds(line, bounds);

            int voffset = getExtendedPaddingTop();
            if ((mGravity & Gravity.VERTICAL_GRAVITY_MASK) != Gravity.TOP) {
                voffset += getVerticalOffset(true);
            }
            if (bounds != null) {
                bounds.offset(getCompoundPaddingLeft(), voffset);
            }
            return baseline + voffset;
        }
    }

    @Override
    public int getBaseline() {
        if (mLayout == null) {
            return super.getBaseline();
        }

        int voffset = 0;
        if ((mGravity & Gravity.VERTICAL_GRAVITY_MASK) != Gravity.TOP) {
            voffset = getVerticalOffset(true);
        }

        return getExtendedPaddingTop() + voffset + mLayout.getLineBaseline(0);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int which = doKeyDown(keyCode, event, null);
        if (which == 0) {
            // Go through default dispatching.
            return super.onKeyDown(keyCode, event);
        }

        return true;
    }

    @Override
    public boolean onKeyMultiple(int keyCode, int repeatCount, KeyEvent event) {
        KeyEvent down = KeyEvent.changeAction(event, KeyEvent.ACTION_DOWN);

        int which = doKeyDown(keyCode, down, event);
        if (which == 0) {
            // Go through default dispatching.
            return super.onKeyMultiple(keyCode, repeatCount, event);
        }
        if (which == -1) {
            // Consumed the whole thing.
            return true;
        }

        repeatCount--;
        
        // We are going to dispatch the remaining events to either the input
        // or movement method.  To do this, we will just send a repeated stream
        // of down and up events until we have done the complete repeatCount.
        // It would be nice if those interfaces had an onKeyMultiple() method,
        // but adding that is a more complicated change.
        KeyEvent up = KeyEvent.changeAction(event, KeyEvent.ACTION_UP);
        if (which == 1) {
            mInput.onKeyUp(this, (Editable)mText, keyCode, up);
            while (--repeatCount > 0) {
                mInput.onKeyDown(this, (Editable)mText, keyCode, down);
                mInput.onKeyUp(this, (Editable)mText, keyCode, up);
            }
            if (mError != null && !mErrorWasChanged) {
                setError(null, null);
            }

        } else if (which == 2) {
            mMovement.onKeyUp(this, (Spannable)mText, keyCode, up);
            while (--repeatCount > 0) {
                mMovement.onKeyDown(this, (Spannable)mText, keyCode, down);
                mMovement.onKeyUp(this, (Spannable)mText, keyCode, up);
            }
        }

        return true;
    }

    /**
     * Returns true if pressing ENTER in this field advances focus instead
     * of inserting the character.  This is true mostly in single-line fields,
     * but also in mail addresses and subjects which will display on multiple
     * lines but where it doesn't make sense to insert newlines.
     */
    private boolean shouldAdvanceFocusOnEnter() {
        if (mInput == null) {
            return false;
        }

        if (mSingleLine) {
            return true;
        }

        if ((mInputType & EditorInfo.TYPE_MASK_CLASS) == EditorInfo.TYPE_CLASS_TEXT) {
            int variation = mInputType & EditorInfo.TYPE_MASK_VARIATION;

            if (variation == EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == EditorInfo.TYPE_TEXT_VARIATION_EMAIL_SUBJECT) {
                return true;
            }
        }

        return false;
    }

    private int doKeyDown(int keyCode, KeyEvent event, KeyEvent otherEvent) {
        if (!isEnabled()) {
            return 0;
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_ENTER:
                mEnterKeyIsDown = true;
                // If ALT modifier is held, then we always insert a
                // newline character.
                if ((event.getMetaState()&KeyEvent.META_ALT_ON) == 0) {
                    
                    // When mInputContentType is set, we know that we are
                    // running in a "modern" cupcake environment, so don't need
                    // to worry about the application trying to capture
                    // enter key events.
                    if (mInputContentType != null) {
                        
                        // If there is an action listener, given them a
                        // chance to consume the event.
                        if (mInputContentType.onEditorActionListener != null &&
                                mInputContentType.onEditorActionListener.onEditorAction(
                                this, EditorInfo.IME_NULL, event)) {
                            mInputContentType.enterDown = true;
                            // We are consuming the enter key for them.
                            return -1;
                        }
                    }
                    
                    // If our editor should move focus when enter is pressed, or
                    // this is a generated event from an IME action button, then
                    // don't let it be inserted into the text.
                    if ((event.getFlags()&KeyEvent.FLAG_EDITOR_ACTION) != 0
                            || shouldAdvanceFocusOnEnter()) {
                        return -1;
                    }
                }
                break;
                
            case KeyEvent.KEYCODE_DPAD_CENTER:
                mDPadCenterIsDown = true;
                if (shouldAdvanceFocusOnEnter()) {
                    return 0;
                }
                break;

                // Has to be done on key down (and not on key up) to correctly be intercepted.
            case KeyEvent.KEYCODE_BACK:
                if (mIsInTextSelectionMode) {
                    stopTextSelectionMode();
                    return -1;
                }
                break;
        }

        if (mInput != null) {
            /*
             * Keep track of what the error was before doing the input
             * so that if an input filter changed the error, we leave
             * that error showing.  Otherwise, we take down whatever
             * error was showing when the user types something.
             */
            mErrorWasChanged = false;

            boolean doDown = true;
            if (otherEvent != null) {
                try {
                    beginBatchEdit();
                    boolean handled = mInput.onKeyOther(this, (Editable) mText,
                            otherEvent);
                    if (mError != null && !mErrorWasChanged) {
                        setError(null, null);
                    }
                    doDown = false;
                    if (handled) {
                        return -1;
                    }
                } catch (AbstractMethodError e) {
                    // onKeyOther was added after 1.0, so if it isn't
                    // implemented we need to try to dispatch as a regular down.
                } finally {
                    endBatchEdit();
                }
            }
            
            if (doDown) {
                beginBatchEdit();
                if (mInput.onKeyDown(this, (Editable) mText, keyCode, event)) {
                    endBatchEdit();
                    if (mError != null && !mErrorWasChanged) {
                        setError(null, null);
                    }
                    return 1;
                }
                endBatchEdit();
            }
        }

        // bug 650865: sometimes we get a key event before a layout.
        // don't try to move around if we don't know the layout.

        if (mMovement != null && mLayout != null) {
            boolean doDown = true;
            if (otherEvent != null) {
                try {
                    boolean handled = mMovement.onKeyOther(this, (Spannable) mText,
                            otherEvent);
                    doDown = false;
                    if (handled) {
                        return -1;
                    }
                } catch (AbstractMethodError e) {
                    // onKeyOther was added after 1.0, so if it isn't
                    // implemented we need to try to dispatch as a regular down.
                }
            }
            if (doDown) {
                if (mMovement.onKeyDown(this, (Spannable)mText, keyCode, event))
                    return 2;
            }
        }

        return 0;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (!isEnabled()) {
            return super.onKeyUp(keyCode, event);
        }

        hideControllers();
        stopTextSelectionMode();

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
                mDPadCenterIsDown = false;
                /*
                 * If there is a click listener, just call through to
                 * super, which will invoke it.
                 *
                 * If there isn't a click listener, try to show the soft
                 * input method.  (It will also
                 * call performClick(), but that won't do anything in
                 * this case.)
                 */
                if (mOnClickListener == null) {
                    if (mMovement != null && mText instanceof Editable
                            && mLayout != null && onCheckIsTextEditor()) {
                        InputMethodManager imm = (InputMethodManager)
                                getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                        imm.showSoftInput(this, 0);
                    }
                }
                return super.onKeyUp(keyCode, event);
                
            case KeyEvent.KEYCODE_ENTER:
                mEnterKeyIsDown = false;
                if (mInputContentType != null
                        && mInputContentType.onEditorActionListener != null
                        && mInputContentType.enterDown) {
                    mInputContentType.enterDown = false;
                    if (mInputContentType.onEditorActionListener.onEditorAction(
                            this, EditorInfo.IME_NULL, event)) {
                        return true;
                    }
                }
                
                if ((event.getFlags()&KeyEvent.FLAG_EDITOR_ACTION) != 0
                        || shouldAdvanceFocusOnEnter()) {
                    /*
                     * If there is a click listener, just call through to
                     * super, which will invoke it.
                     *
                     * If there isn't a click listener, try to advance focus,
                     * but still call through to super, which will reset the
                     * pressed state and longpress state.  (It will also
                     * call performClick(), but that won't do anything in
                     * this case.)
                     */
                    if (mOnClickListener == null) {
                        View v = focusSearch(FOCUS_DOWN);

                        if (v != null) {
                            if (!v.requestFocus(FOCUS_DOWN)) {
                                throw new IllegalStateException("focus search returned a view " +
                                        "that wasn't able to take focus!");
                            }

                            /*
                             * Return true because we handled the key; super
                             * will return false because there was no click
                             * listener.
                             */
                            super.onKeyUp(keyCode, event);
                            return true;
                        } else if ((event.getFlags()
                                & KeyEvent.FLAG_EDITOR_ACTION) != 0) {
                            // No target for next focus, but make sure the IME
                            // if this came from it.
                            InputMethodManager imm = InputMethodManager.peekInstance();
                            if (imm != null) {
                                imm.hideSoftInputFromWindow(getWindowToken(), 0);
                            }
                        }
                    }

                    return super.onKeyUp(keyCode, event);
                }
                break;
        }

        if (mInput != null)
            if (mInput.onKeyUp(this, (Editable) mText, keyCode, event))
                return true;

        if (mMovement != null && mLayout != null)
            if (mMovement.onKeyUp(this, (Spannable) mText, keyCode, event))
                return true;

        return super.onKeyUp(keyCode, event);
    }

    @Override public boolean onCheckIsTextEditor() {
        return mInputType != EditorInfo.TYPE_NULL;
    }
    
    @Override public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        if (onCheckIsTextEditor()) {
            if (mInputMethodState == null) {
                mInputMethodState = new InputMethodState();
            }
            outAttrs.inputType = mInputType;
            if (mInputContentType != null) {
                outAttrs.imeOptions = mInputContentType.imeOptions;
                outAttrs.privateImeOptions = mInputContentType.privateImeOptions;
                outAttrs.actionLabel = mInputContentType.imeActionLabel;
                outAttrs.actionId = mInputContentType.imeActionId;
                outAttrs.extras = mInputContentType.extras;
            } else {
                outAttrs.imeOptions = EditorInfo.IME_NULL;
            }
            if ((outAttrs.imeOptions&EditorInfo.IME_MASK_ACTION)
                    == EditorInfo.IME_ACTION_UNSPECIFIED) {
                if (focusSearch(FOCUS_DOWN) != null) {
                    // An action has not been set, but the enter key will move to
                    // the next focus, so set the action to that.
                    outAttrs.imeOptions |= EditorInfo.IME_ACTION_NEXT;
                } else {
                    // An action has not been set, and there is no focus to move
                    // to, so let's just supply a "done" action.
                    outAttrs.imeOptions |= EditorInfo.IME_ACTION_DONE;
                }
                if (!shouldAdvanceFocusOnEnter()) {
                    outAttrs.imeOptions |= EditorInfo.IME_FLAG_NO_ENTER_ACTION;
                }
            }
            if ((outAttrs.inputType & (InputType.TYPE_MASK_CLASS
                    | InputType.TYPE_TEXT_FLAG_MULTI_LINE))
                    == (InputType.TYPE_CLASS_TEXT
                            | InputType.TYPE_TEXT_FLAG_MULTI_LINE)) {
                // Multi-line text editors should always show an enter key.
                outAttrs.imeOptions |= EditorInfo.IME_FLAG_NO_ENTER_ACTION;
            }
            outAttrs.hintText = mHint;
            if (mText instanceof Editable) {
                InputConnection ic = new EditableInputConnection(this);
                outAttrs.initialSelStart = getSelectionStart();
                outAttrs.initialSelEnd = getSelectionEnd();
                outAttrs.initialCapsMode = ic.getCursorCapsMode(mInputType);
                return ic;
            }
        }
        return null;
    }

    /**
     * If this TextView contains editable content, extract a portion of it
     * based on the information in <var>request</var> in to <var>outText</var>.
     * @return Returns true if the text was successfully extracted, else false.
     */
    public boolean extractText(ExtractedTextRequest request,
            ExtractedText outText) {
        return extractTextInternal(request, EXTRACT_UNKNOWN, EXTRACT_UNKNOWN,
                EXTRACT_UNKNOWN, outText);
    }
    
    static final int EXTRACT_NOTHING = -2;
    static final int EXTRACT_UNKNOWN = -1;
    
    boolean extractTextInternal(ExtractedTextRequest request,
            int partialStartOffset, int partialEndOffset, int delta,
            ExtractedText outText) {
        final CharSequence content = mText;
        if (content != null) {
            if (partialStartOffset != EXTRACT_NOTHING) {
                final int N = content.length();
                if (partialStartOffset < 0) {
                    outText.partialStartOffset = outText.partialEndOffset = -1;
                    partialStartOffset = 0;
                    partialEndOffset = N;
                } else {
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
                    outText.partialEndOffset = partialEndOffset;
                    // Now use the delta to determine the actual amount of text
                    // we need.
                    partialEndOffset += delta;
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
            if (MetaKeyKeyListener.getMetaState(mText, MetaKeyKeyListener.META_SELECTING) != 0) {
                outText.flags |= ExtractedText.FLAG_SELECTING;
            }
            if (mSingleLine) {
                outText.flags |= ExtractedText.FLAG_SINGLE_LINE;
            }
            outText.startOffset = 0;
            outText.selectionStart = getSelectionStart();
            outText.selectionEnd = getSelectionEnd();
            return true;
        }
        return false;
    }
    
    boolean reportExtractedText() {
        final InputMethodState ims = mInputMethodState;
        if (ims != null) {
            final boolean contentChanged = ims.mContentChanged;
            if (contentChanged || ims.mSelectionModeChanged) {
                ims.mContentChanged = false;
                ims.mSelectionModeChanged = false;
                final ExtractedTextRequest req = mInputMethodState.mExtracting;
                if (req != null) {
                    InputMethodManager imm = InputMethodManager.peekInstance();
                    if (imm != null) {
                        if (DEBUG_EXTRACT) Log.v(LOG_TAG, "Retrieving extracted start="
                                + ims.mChangedStart + " end=" + ims.mChangedEnd
                                + " delta=" + ims.mChangedDelta);
                        if (ims.mChangedStart < 0 && !contentChanged) {
                            ims.mChangedStart = EXTRACT_NOTHING;
                        }
                        if (extractTextInternal(req, ims.mChangedStart, ims.mChangedEnd,
                                ims.mChangedDelta, ims.mTmpExtracted)) {
                            if (DEBUG_EXTRACT) Log.v(LOG_TAG, "Reporting extracted start="
                                    + ims.mTmpExtracted.partialStartOffset
                                    + " end=" + ims.mTmpExtracted.partialEndOffset
                                    + ": " + ims.mTmpExtracted.text);
                            imm.updateExtractedText(this, req.token,
                                    mInputMethodState.mTmpExtracted);
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }
    
    /**
     * This is used to remove all style-impacting spans from text before new
     * extracted text is being replaced into it, so that we don't have any
     * lingering spans applied during the replace.
     */
    static void removeParcelableSpans(Spannable spannable, int start, int end) {
        Object[] spans = spannable.getSpans(start, end, ParcelableSpan.class);
        int i = spans.length;
        while (i > 0) {
            i--;
            spannable.removeSpan(spans[i]);
        }
    }
    
    /**
     * Apply to this text view the given extracted text, as previously
     * returned by {@link #extractText(ExtractedTextRequest, ExtractedText)}.
     */
    public void setExtractedText(ExtractedText text) {
        Editable content = getEditableText();
        if (text.text != null) {
            if (content == null) {
                setText(text.text, TextView.BufferType.EDITABLE);
            } else if (text.partialStartOffset < 0) {
                removeParcelableSpans(content, 0, content.length());
                content.replace(0, content.length(), text.text);
            } else {
                final int N = content.length();
                int start = text.partialStartOffset;
                if (start > N) start = N;
                int end = text.partialEndOffset;
                if (end > N) end = N;
                removeParcelableSpans(content, start, end);
                content.replace(start, end, text.text);
            }
        }
        
        // Now set the selection position...  make sure it is in range, to
        // avoid crashes.  If this is a partial update, it is possible that
        // the underlying text may have changed, causing us problems here.
        // Also we just don't want to trust clients to do the right thing.
        Spannable sp = (Spannable)getText();
        final int N = sp.length();
        int start = text.selectionStart;
        if (start < 0) start = 0;
        else if (start > N) start = N;
        int end = text.selectionEnd;
        if (end < 0) end = 0;
        else if (end > N) end = N;
        Selection.setSelection(sp, start, end);
        
        // Finally, update the selection mode.
        if ((text.flags&ExtractedText.FLAG_SELECTING) != 0) {
            MetaKeyKeyListener.startSelecting(this, sp);
        } else {
            MetaKeyKeyListener.stopSelecting(this, sp);
        }
    }
    
    /**
     * @hide
     */
    public void setExtracting(ExtractedTextRequest req) {
        if (mInputMethodState != null) {
            mInputMethodState.mExtracting = req;
        }
        hideControllers();
    }
    
    /**
     * Called by the framework in response to a text completion from
     * the current input method, provided by it calling
     * {@link InputConnection#commitCompletion
     * InputConnection.commitCompletion()}.  The default implementation does
     * nothing; text views that are supporting auto-completion should override
     * this to do their desired behavior.
     *
     * @param text The auto complete text the user has selected.
     */
    public void onCommitCompletion(CompletionInfo text) {
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
                    ims.mChangedEnd = mText.length();
                } else {
                    ims.mChangedStart = EXTRACT_UNKNOWN;
                    ims.mChangedEnd = EXTRACT_UNKNOWN;
                    ims.mContentChanged = false;
                }
                onBeginBatchEdit();
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
        onEndBatchEdit();
        
        if (ims.mContentChanged || ims.mSelectionModeChanged) {
            updateAfterEdit();
            reportExtractedText();
        } else if (ims.mCursorChanged) {
            // Cheezy way to get us to report the current cursor location.
            invalidateCursor();
        }
    }
    
    void updateAfterEdit() {
        invalidate();
        int curs = getSelectionStart();

        if (curs >= 0 || (mGravity & Gravity.VERTICAL_GRAVITY_MASK) ==
                             Gravity.BOTTOM) {
            registerForPreDraw();
        }

        if (curs >= 0) {
            mHighlightPathBogus = true;

            if (isFocused()) {
                mShowCursor = SystemClock.uptimeMillis();
                makeBlink();
            }
        }
        
        checkForResize();
    }
    
    /**
     * Called by the framework in response to a request to begin a batch
     * of edit operations through a call to link {@link #beginBatchEdit()}.
     */
    public void onBeginBatchEdit() {
    }
    
    /**
     * Called by the framework in response to a request to end a batch
     * of edit operations through a call to link {@link #endBatchEdit}.
     */
    public void onEndBatchEdit() {
    }
    
    /**
     * Called by the framework in response to a private command from the
     * current method, provided by it calling
     * {@link InputConnection#performPrivateCommand
     * InputConnection.performPrivateCommand()}.
     *
     * @param action The action name of the command.
     * @param data Any additional data for the command.  This may be null.
     * @return Return true if you handled the command, else false.
     */
    public boolean onPrivateIMECommand(String action, Bundle data) {
        return false;
    }

    private void nullLayouts() {
        if (mLayout instanceof BoringLayout && mSavedLayout == null) {
            mSavedLayout = (BoringLayout) mLayout;
        }
        if (mHintLayout instanceof BoringLayout && mSavedHintLayout == null) {
            mSavedHintLayout = (BoringLayout) mHintLayout;
        }

        mLayout = mHintLayout = null;
    }

    /**
     * Make a new Layout based on the already-measured size of the view,
     * on the assumption that it was measured correctly at some point.
     */
    private void assumeLayout() {
        int width = mRight - mLeft - getCompoundPaddingLeft() - getCompoundPaddingRight();

        if (width < 1) {
            width = 0;
        }

        int physicalWidth = width;

        if (mHorizontallyScrolling) {
            width = VERY_WIDE;
        }

        makeNewLayout(width, physicalWidth, UNKNOWN_BORING, UNKNOWN_BORING,
                      physicalWidth, false);
    }

    /**
     * The width passed in is now the desired layout width,
     * not the full view width with padding.
     * {@hide}
     */
    protected void makeNewLayout(int w, int hintWidth,
                                 BoringLayout.Metrics boring,
                                 BoringLayout.Metrics hintBoring,
                                 int ellipsisWidth, boolean bringIntoView) {
        stopMarquee();

        mHighlightPathBogus = true;

        if (w < 0) {
            w = 0;
        }
        if (hintWidth < 0) {
            hintWidth = 0;
        }

        Layout.Alignment alignment;
        switch (mGravity & Gravity.HORIZONTAL_GRAVITY_MASK) {
            case Gravity.CENTER_HORIZONTAL:
                alignment = Layout.Alignment.ALIGN_CENTER;
                break;

            case Gravity.RIGHT:
                alignment = Layout.Alignment.ALIGN_OPPOSITE;
                break;

            default:
                alignment = Layout.Alignment.ALIGN_NORMAL;
        }

        boolean shouldEllipsize = mEllipsize != null && mInput == null;

        if (mText instanceof Spannable) {
            mLayout = new DynamicLayout(mText, mTransformed, mTextPaint, w,
                    alignment, mSpacingMult,
                    mSpacingAdd, mIncludePad, mInput == null ? mEllipsize : null,
                    ellipsisWidth);
        } else {
            if (boring == UNKNOWN_BORING) {
                boring = BoringLayout.isBoring(mTransformed, mTextPaint,
                                               mBoring);
                if (boring != null) {
                    mBoring = boring;
                }
            }

            if (boring != null) {
                if (boring.width <= w &&
                    (mEllipsize == null || boring.width <= ellipsisWidth)) {
                    if (mSavedLayout != null) {
                        mLayout = mSavedLayout.
                                replaceOrMake(mTransformed, mTextPaint,
                                w, alignment, mSpacingMult, mSpacingAdd,
                                boring, mIncludePad);
                    } else {
                        mLayout = BoringLayout.make(mTransformed, mTextPaint,
                                w, alignment, mSpacingMult, mSpacingAdd,
                                boring, mIncludePad);
                    }

                    mSavedLayout = (BoringLayout) mLayout;
                } else if (shouldEllipsize && boring.width <= w) {
                    if (mSavedLayout != null) {
                        mLayout = mSavedLayout.
                                replaceOrMake(mTransformed, mTextPaint,
                                w, alignment, mSpacingMult, mSpacingAdd,
                                boring, mIncludePad, mEllipsize,
                                ellipsisWidth);
                    } else {
                        mLayout = BoringLayout.make(mTransformed, mTextPaint,
                                w, alignment, mSpacingMult, mSpacingAdd,
                                boring, mIncludePad, mEllipsize,
                                ellipsisWidth);
                    }
                } else if (shouldEllipsize) {
                    mLayout = new StaticLayout(mTransformed,
                                0, mTransformed.length(),
                                mTextPaint, w, alignment, mSpacingMult,
                                mSpacingAdd, mIncludePad, mEllipsize,
                                ellipsisWidth);
                } else {
                    mLayout = new StaticLayout(mTransformed, mTextPaint,
                            w, alignment, mSpacingMult, mSpacingAdd,
                            mIncludePad);
                }
            } else if (shouldEllipsize) {
                mLayout = new StaticLayout(mTransformed,
                            0, mTransformed.length(),
                            mTextPaint, w, alignment, mSpacingMult,
                            mSpacingAdd, mIncludePad, mEllipsize,
                            ellipsisWidth);
            } else {
                mLayout = new StaticLayout(mTransformed, mTextPaint,
                        w, alignment, mSpacingMult, mSpacingAdd,
                        mIncludePad);
            }
        }

        shouldEllipsize = mEllipsize != null;
        mHintLayout = null;

        if (mHint != null) {
            if (shouldEllipsize) hintWidth = w;

            if (hintBoring == UNKNOWN_BORING) {
                hintBoring = BoringLayout.isBoring(mHint, mTextPaint,
                                                   mHintBoring);
                if (hintBoring != null) {
                    mHintBoring = hintBoring;
                }
            }

            if (hintBoring != null) {
                if (hintBoring.width <= hintWidth &&
                    (!shouldEllipsize || hintBoring.width <= ellipsisWidth)) {
                    if (mSavedHintLayout != null) {
                        mHintLayout = mSavedHintLayout.
                                replaceOrMake(mHint, mTextPaint,
                                hintWidth, alignment, mSpacingMult, mSpacingAdd,
                                hintBoring, mIncludePad);
                    } else {
                        mHintLayout = BoringLayout.make(mHint, mTextPaint,
                                hintWidth, alignment, mSpacingMult, mSpacingAdd,
                                hintBoring, mIncludePad);
                    }

                    mSavedHintLayout = (BoringLayout) mHintLayout;
                } else if (shouldEllipsize && hintBoring.width <= hintWidth) {
                    if (mSavedHintLayout != null) {
                        mHintLayout = mSavedHintLayout.
                                replaceOrMake(mHint, mTextPaint,
                                hintWidth, alignment, mSpacingMult, mSpacingAdd,
                                hintBoring, mIncludePad, mEllipsize,
                                ellipsisWidth);
                    } else {
                        mHintLayout = BoringLayout.make(mHint, mTextPaint,
                                hintWidth, alignment, mSpacingMult, mSpacingAdd,
                                hintBoring, mIncludePad, mEllipsize,
                                ellipsisWidth);
                    }
                } else if (shouldEllipsize) {
                    mHintLayout = new StaticLayout(mHint,
                                0, mHint.length(),
                                mTextPaint, hintWidth, alignment, mSpacingMult,
                                mSpacingAdd, mIncludePad, mEllipsize,
                                ellipsisWidth);
                } else {
                    mHintLayout = new StaticLayout(mHint, mTextPaint,
                            hintWidth, alignment, mSpacingMult, mSpacingAdd,
                            mIncludePad);
                }
            } else if (shouldEllipsize) {
                mHintLayout = new StaticLayout(mHint,
                            0, mHint.length(),
                            mTextPaint, hintWidth, alignment, mSpacingMult,
                            mSpacingAdd, mIncludePad, mEllipsize,
                            ellipsisWidth);
            } else {
                mHintLayout = new StaticLayout(mHint, mTextPaint,
                        hintWidth, alignment, mSpacingMult, mSpacingAdd,
                        mIncludePad);
            }
        }

        if (bringIntoView) {
            registerForPreDraw();
        }

        if (mEllipsize == TextUtils.TruncateAt.MARQUEE) {
            if (!compressText(ellipsisWidth)) {
                final int height = mLayoutParams.height;
                // If the size of the view does not depend on the size of the text, try to
                // start the marquee immediately
                if (height != LayoutParams.WRAP_CONTENT && height != LayoutParams.MATCH_PARENT) {
                    startMarquee();
                } else {
                    // Defer the start of the marquee until we know our width (see setFrame())
                    mRestartMarquee = true;
                }
            }
        }

        // CursorControllers need a non-null mLayout
        prepareCursorControllers();
    }

    private boolean compressText(float width) {
        // Only compress the text if it hasn't been compressed by the previous pass
        if (width > 0.0f && mLayout != null && getLineCount() == 1 && !mUserSetTextScaleX &&
                mTextPaint.getTextScaleX() == 1.0f) {
            final float textWidth = mLayout.getLineWidth(0);
            final float overflow = (textWidth + 1.0f - width) / width;
            if (overflow > 0.0f && overflow <= Marquee.MARQUEE_DELTA_MAX) {
                mTextPaint.setTextScaleX(1.0f - overflow - 0.005f);
                post(new Runnable() {
                    public void run() {
                        requestLayout();
                    }
                });
                return true;
            }
        }

        return false;
    }

    private static int desired(Layout layout) {
        int n = layout.getLineCount();
        CharSequence text = layout.getText();
        float max = 0;

        // if any line was wrapped, we can't use it.
        // but it's ok for the last line not to have a newline

        for (int i = 0; i < n - 1; i++) {
            if (text.charAt(layout.getLineEnd(i) - 1) != '\n')
                return -1;
        }

        for (int i = 0; i < n; i++) {
            max = Math.max(max, layout.getLineWidth(i));
        }

        return (int) FloatMath.ceil(max);
    }

    /**
     * Set whether the TextView includes extra top and bottom padding to make
     * room for accents that go above the normal ascent and descent.
     * The default is true.
     *
     * @attr ref android.R.styleable#TextView_includeFontPadding
     */
    public void setIncludeFontPadding(boolean includepad) {
        mIncludePad = includepad;

        if (mLayout != null) {
            nullLayouts();
            requestLayout();
            invalidate();
        }
    }

    private static final BoringLayout.Metrics UNKNOWN_BORING = new BoringLayout.Metrics();

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);

        int width;
        int height;

        BoringLayout.Metrics boring = UNKNOWN_BORING;
        BoringLayout.Metrics hintBoring = UNKNOWN_BORING;

        int des = -1;
        boolean fromexisting = false;

        if (widthMode == MeasureSpec.EXACTLY) {
            // Parent has told us how big to be. So be it.
            width = widthSize;
        } else {
            if (mLayout != null && mEllipsize == null) {
                des = desired(mLayout);
            }

            if (des < 0) {
                boring = BoringLayout.isBoring(mTransformed, mTextPaint, mBoring);
                if (boring != null) {
                    mBoring = boring;
                }
            } else {
                fromexisting = true;
            }

            if (boring == null || boring == UNKNOWN_BORING) {
                if (des < 0) {
                    des = (int) FloatMath.ceil(Layout.getDesiredWidth(mTransformed, mTextPaint));
                }

                width = des;
            } else {
                width = boring.width;
            }

            final Drawables dr = mDrawables;
            if (dr != null) {
                width = Math.max(width, dr.mDrawableWidthTop);
                width = Math.max(width, dr.mDrawableWidthBottom);
            }

            if (mHint != null) {
                int hintDes = -1;
                int hintWidth;

                if (mHintLayout != null && mEllipsize == null) {
                    hintDes = desired(mHintLayout);
                }

                if (hintDes < 0) {
                    hintBoring = BoringLayout.isBoring(mHint, mTextPaint, mHintBoring);
                    if (hintBoring != null) {
                        mHintBoring = hintBoring;
                    }
                }

                if (hintBoring == null || hintBoring == UNKNOWN_BORING) {
                    if (hintDes < 0) {
                        hintDes = (int) FloatMath.ceil(
                                Layout.getDesiredWidth(mHint, mTextPaint));
                    }

                    hintWidth = hintDes;
                } else {
                    hintWidth = hintBoring.width;
                }

                if (hintWidth > width) {
                    width = hintWidth;
                }
            }

            width += getCompoundPaddingLeft() + getCompoundPaddingRight();

            if (mMaxWidthMode == EMS) {
                width = Math.min(width, mMaxWidth * getLineHeight());
            } else {
                width = Math.min(width, mMaxWidth);
            }

            if (mMinWidthMode == EMS) {
                width = Math.max(width, mMinWidth * getLineHeight());
            } else {
                width = Math.max(width, mMinWidth);
            }

            // Check against our minimum width
            width = Math.max(width, getSuggestedMinimumWidth());

            if (widthMode == MeasureSpec.AT_MOST) {
                width = Math.min(widthSize, width);
            }
        }

        int want = width - getCompoundPaddingLeft() - getCompoundPaddingRight();
        int unpaddedWidth = want;
        int hintWant = want;

        if (mHorizontallyScrolling)
            want = VERY_WIDE;

        int hintWidth = mHintLayout == null ? hintWant : mHintLayout.getWidth();

        if (mLayout == null) {
            makeNewLayout(want, hintWant, boring, hintBoring,
                          width - getCompoundPaddingLeft() - getCompoundPaddingRight(), false);
        } else if ((mLayout.getWidth() != want) || (hintWidth != hintWant) ||
                   (mLayout.getEllipsizedWidth() !=
                        width - getCompoundPaddingLeft() - getCompoundPaddingRight())) {
            if (mHint == null && mEllipsize == null &&
                    want > mLayout.getWidth() &&
                    (mLayout instanceof BoringLayout ||
                            (fromexisting && des >= 0 && des <= want))) {
                mLayout.increaseWidthTo(want);
            } else {
                makeNewLayout(want, hintWant, boring, hintBoring,
                              width - getCompoundPaddingLeft() - getCompoundPaddingRight(), false);
            }
        } else {
            // Width has not changed.
        }

        if (heightMode == MeasureSpec.EXACTLY) {
            // Parent has told us how big to be. So be it.
            height = heightSize;
            mDesiredHeightAtMeasure = -1;
        } else {
            int desired = getDesiredHeight();

            height = desired;
            mDesiredHeightAtMeasure = desired;

            if (heightMode == MeasureSpec.AT_MOST) {
                height = Math.min(desired, heightSize);
            }
        }

        int unpaddedHeight = height - getCompoundPaddingTop() - getCompoundPaddingBottom();
        if (mMaxMode == LINES && mLayout.getLineCount() > mMaximum) {
            unpaddedHeight = Math.min(unpaddedHeight, mLayout.getLineTop(mMaximum));
        }

        /*
         * We didn't let makeNewLayout() register to bring the cursor into view,
         * so do it here if there is any possibility that it is needed.
         */
        if (mMovement != null ||
            mLayout.getWidth() > unpaddedWidth ||
            mLayout.getHeight() > unpaddedHeight) {
            registerForPreDraw();
        } else {
            scrollTo(0, 0);
        }

        setMeasuredDimension(width, height);
    }

    private int getDesiredHeight() {
        return Math.max(
                getDesiredHeight(mLayout, true),
                getDesiredHeight(mHintLayout, mEllipsize != null));
    }

    private int getDesiredHeight(Layout layout, boolean cap) {
        if (layout == null) {
            return 0;
        }

        int linecount = layout.getLineCount();
        int pad = getCompoundPaddingTop() + getCompoundPaddingBottom();
        int desired = layout.getLineTop(linecount);

        final Drawables dr = mDrawables;
        if (dr != null) {
            desired = Math.max(desired, dr.mDrawableHeightLeft);
            desired = Math.max(desired, dr.mDrawableHeightRight);
        }

        desired += pad;

        if (mMaxMode == LINES) {
            /*
             * Don't cap the hint to a certain number of lines.
             * (Do cap it, though, if we have a maximum pixel height.)
             */
            if (cap) {
                if (linecount > mMaximum) {
                    desired = layout.getLineTop(mMaximum) +
                              layout.getBottomPadding();

                    if (dr != null) {
                        desired = Math.max(desired, dr.mDrawableHeightLeft);
                        desired = Math.max(desired, dr.mDrawableHeightRight);
                    }

                    desired += pad;
                    linecount = mMaximum;
                }
            }
        } else {
            desired = Math.min(desired, mMaximum);
        }

        if (mMinMode == LINES) {
            if (linecount < mMinimum) {
                desired += getLineHeight() * (mMinimum - linecount);
            }
        } else {
            desired = Math.max(desired, mMinimum);
        }

        // Check against our minimum height
        desired = Math.max(desired, getSuggestedMinimumHeight());

        return desired;
    }

    /**
     * Check whether a change to the existing text layout requires a
     * new view layout.
     */
    private void checkForResize() {
        boolean sizeChanged = false;

        if (mLayout != null) {
            // Check if our width changed
            if (mLayoutParams.width == LayoutParams.WRAP_CONTENT) {
                sizeChanged = true;
                invalidate();
            }

            // Check if our height changed
            if (mLayoutParams.height == LayoutParams.WRAP_CONTENT) {
                int desiredHeight = getDesiredHeight();

                if (desiredHeight != this.getHeight()) {
                    sizeChanged = true;
                }
            } else if (mLayoutParams.height == LayoutParams.MATCH_PARENT) {
                if (mDesiredHeightAtMeasure >= 0) {
                    int desiredHeight = getDesiredHeight();

                    if (desiredHeight != mDesiredHeightAtMeasure) {
                        sizeChanged = true;
                    }
                }
            }
        }

        if (sizeChanged) {
            requestLayout();
            // caller will have already invalidated
        }
    }

    /**
     * Check whether entirely new text requires a new view layout
     * or merely a new text layout.
     */
    private void checkForRelayout() {
        // If we have a fixed width, we can just swap in a new text layout
        // if the text height stays the same or if the view height is fixed.

        if ((mLayoutParams.width != LayoutParams.WRAP_CONTENT ||
                (mMaxWidthMode == mMinWidthMode && mMaxWidth == mMinWidth)) &&
                (mHint == null || mHintLayout != null) &&
                (mRight - mLeft - getCompoundPaddingLeft() - getCompoundPaddingRight() > 0)) {
            // Static width, so try making a new text layout.

            int oldht = mLayout.getHeight();
            int want = mLayout.getWidth();
            int hintWant = mHintLayout == null ? 0 : mHintLayout.getWidth();

            /*
             * No need to bring the text into view, since the size is not
             * changing (unless we do the requestLayout(), in which case it
             * will happen at measure).
             */
            makeNewLayout(want, hintWant, UNKNOWN_BORING, UNKNOWN_BORING,
                          mRight - mLeft - getCompoundPaddingLeft() - getCompoundPaddingRight(),
                          false);

            if (mEllipsize != TextUtils.TruncateAt.MARQUEE) {
                // In a fixed-height view, so use our new text layout.
                if (mLayoutParams.height != LayoutParams.WRAP_CONTENT &&
                    mLayoutParams.height != LayoutParams.MATCH_PARENT) {
                    invalidate();
                    return;
                }
    
                // Dynamic height, but height has stayed the same,
                // so use our new text layout.
                if (mLayout.getHeight() == oldht &&
                    (mHintLayout == null || mHintLayout.getHeight() == oldht)) {
                    invalidate();
                    return;
                }
            }

            // We lose: the height has changed and we have a dynamic height.
            // Request a new view layout using our new text layout.
            requestLayout();
            invalidate();
        } else {
            // Dynamic width, so we have no choice but to request a new
            // view layout with a new text layout.

            nullLayouts();
            requestLayout();
            invalidate();
        }
    }

    /**
     * Returns true if anything changed.
     */
    private boolean bringTextIntoView() {
        int line = 0;
        if ((mGravity & Gravity.VERTICAL_GRAVITY_MASK) == Gravity.BOTTOM) {
            line = mLayout.getLineCount() - 1;
        }

        Layout.Alignment a = mLayout.getParagraphAlignment(line);
        int dir = mLayout.getParagraphDirection(line);
        int hspace = mRight - mLeft - getCompoundPaddingLeft() - getCompoundPaddingRight();
        int vspace = mBottom - mTop - getExtendedPaddingTop() - getExtendedPaddingBottom();
        int ht = mLayout.getHeight();

        int scrollx, scrolly;

        if (a == Layout.Alignment.ALIGN_CENTER) {
            /*
             * Keep centered if possible, or, if it is too wide to fit,
             * keep leading edge in view.
             */

            int left = (int) FloatMath.floor(mLayout.getLineLeft(line));
            int right = (int) FloatMath.ceil(mLayout.getLineRight(line));

            if (right - left < hspace) {
                scrollx = (right + left) / 2 - hspace / 2;
            } else {
                if (dir < 0) {
                    scrollx = right - hspace;
                } else {
                    scrollx = left;
                }
            }
        } else if (a == Layout.Alignment.ALIGN_NORMAL) {
            /*
             * Keep leading edge in view.
             */

            if (dir < 0) {
                int right = (int) FloatMath.ceil(mLayout.getLineRight(line));
                scrollx = right - hspace;
            } else {
                scrollx = (int) FloatMath.floor(mLayout.getLineLeft(line));
            }
        } else /* a == Layout.Alignment.ALIGN_OPPOSITE */ {
            /*
             * Keep trailing edge in view.
             */

            if (dir < 0) {
                scrollx = (int) FloatMath.floor(mLayout.getLineLeft(line));
            } else {
                int right = (int) FloatMath.ceil(mLayout.getLineRight(line));
                scrollx = right - hspace;
            }
        }

        if (ht < vspace) {
            scrolly = 0;
        } else {
            if ((mGravity & Gravity.VERTICAL_GRAVITY_MASK) == Gravity.BOTTOM) {
                scrolly = ht - vspace;
            } else {
                scrolly = 0;
            }
        }

        if (scrollx != mScrollX || scrolly != mScrollY) {
            scrollTo(scrollx, scrolly);
            return true;
        } else {
            return false;
        }
    }

    /**
     * Move the point, specified by the offset, into the view if it is needed.
     * This has to be called after layout. Returns true if anything changed.
     */
    public boolean bringPointIntoView(int offset) {
        boolean changed = false;

        int line = mLayout.getLineForOffset(offset);

        // FIXME: Is it okay to truncate this, or should we round?
        final int x = (int)mLayout.getPrimaryHorizontal(offset);
        final int top = mLayout.getLineTop(line);
        final int bottom = mLayout.getLineTop(line + 1);

        int left = (int) FloatMath.floor(mLayout.getLineLeft(line));
        int right = (int) FloatMath.ceil(mLayout.getLineRight(line));
        int ht = mLayout.getHeight();

        int grav;

        switch (mLayout.getParagraphAlignment(line)) {
            case ALIGN_NORMAL:
                grav = 1;
                break;

            case ALIGN_OPPOSITE:
                grav = -1;
                break;

            default:
                grav = 0;
        }

        grav *= mLayout.getParagraphDirection(line);

        int hspace = mRight - mLeft - getCompoundPaddingLeft() - getCompoundPaddingRight();
        int vspace = mBottom - mTop - getExtendedPaddingTop() - getExtendedPaddingBottom();

        int hslack = (bottom - top) / 2;
        int vslack = hslack;

        if (vslack > vspace / 4)
            vslack = vspace / 4;
        if (hslack > hspace / 4)
            hslack = hspace / 4;

        int hs = mScrollX;
        int vs = mScrollY;

        if (top - vs < vslack)
            vs = top - vslack;
        if (bottom - vs > vspace - vslack)
            vs = bottom - (vspace - vslack);
        if (ht - vs < vspace)
            vs = ht - vspace;
        if (0 - vs > 0)
            vs = 0;

        if (grav != 0) {
            if (x - hs < hslack) {
                hs = x - hslack;
            }
            if (x - hs > hspace - hslack) {
                hs = x - (hspace - hslack);
            }
        }

        if (grav < 0) {
            if (left - hs > 0)
                hs = left;
            if (right - hs < hspace)
                hs = right - hspace;
        } else if (grav > 0) {
            if (right - hs < hspace)
                hs = right - hspace;
            if (left - hs > 0)
                hs = left;
        } else /* grav == 0 */ {
            if (right - left <= hspace) {
                /*
                 * If the entire text fits, center it exactly.
                 */
                hs = left - (hspace - (right - left)) / 2;
            } else if (x > right - hslack) {
                /*
                 * If we are near the right edge, keep the right edge
                 * at the edge of the view.
                 */
                hs = right - hspace;
            } else if (x < left + hslack) {
                /*
                 * If we are near the left edge, keep the left edge
                 * at the edge of the view.
                 */
                hs = left;
            } else if (left > hs) {
                /*
                 * Is there whitespace visible at the left?  Fix it if so.
                 */
                hs = left;
            } else if (right < hs + hspace) {
                /*
                 * Is there whitespace visible at the right?  Fix it if so.
                 */
                hs = right - hspace;
            } else {
                /*
                 * Otherwise, float as needed.
                 */
                if (x - hs < hslack) {
                    hs = x - hslack;
                }
                if (x - hs > hspace - hslack) {
                    hs = x - (hspace - hslack);
                }
            }
        }

        if (hs != mScrollX || vs != mScrollY) {
            if (mScroller == null) {
                scrollTo(hs, vs);
            } else {
                long duration = AnimationUtils.currentAnimationTimeMillis() - mLastScroll;
                int dx = hs - mScrollX;
                int dy = vs - mScrollY;

                if (duration > ANIMATED_SCROLL_GAP) {
                    mScroller.startScroll(mScrollX, mScrollY, dx, dy);
                    awakenScrollBars(mScroller.getDuration());
                    invalidate();
                } else {
                    if (!mScroller.isFinished()) {
                        mScroller.abortAnimation();
                    }

                    scrollBy(dx, dy);
                }

                mLastScroll = AnimationUtils.currentAnimationTimeMillis();
            }

            changed = true;
        }

        if (isFocused()) {
            // This offsets because getInterestingRect() is in terms of
            // viewport coordinates, but requestRectangleOnScreen()
            // is in terms of content coordinates.

            Rect r = new Rect(x, top, x + 1, bottom);
            getInterestingRect(r, line);
            r.offset(mScrollX, mScrollY);

            if (requestRectangleOnScreen(r)) {
                changed = true;
            }
        }

        return changed;
    }

    /**
     * Move the cursor, if needed, so that it is at an offset that is visible
     * to the user.  This will not move the cursor if it represents more than
     * one character (a selection range).  This will only work if the
     * TextView contains spannable text; otherwise it will do nothing.
     *
     * @return True if the cursor was actually moved, false otherwise.
     */
    public boolean moveCursorToVisibleOffset() {
        if (!(mText instanceof Spannable)) {
            return false;
        }
        int start = getSelectionStart();
        int end = getSelectionEnd();
        if (start != end) {
            return false;
        }
        
        // First: make sure the line is visible on screen:
        
        int line = mLayout.getLineForOffset(start);

        final int top = mLayout.getLineTop(line);
        final int bottom = mLayout.getLineTop(line + 1);
        final int vspace = mBottom - mTop - getExtendedPaddingTop() - getExtendedPaddingBottom();
        int vslack = (bottom - top) / 2;
        if (vslack > vspace / 4)
            vslack = vspace / 4;
        final int vs = mScrollY;

        if (top < (vs+vslack)) {
            line = mLayout.getLineForVertical(vs+vslack+(bottom-top));
        } else if (bottom > (vspace+vs-vslack)) {
            line = mLayout.getLineForVertical(vspace+vs-vslack-(bottom-top));
        }
        
        // Next: make sure the character is visible on screen:
        
        final int hspace = mRight - mLeft - getCompoundPaddingLeft() - getCompoundPaddingRight();
        final int hs = mScrollX;
        final int leftChar = mLayout.getOffsetForHorizontal(line, hs);
        final int rightChar = mLayout.getOffsetForHorizontal(line, hspace+hs);
        
        int newStart = start;
        if (newStart < leftChar) {
            newStart = leftChar;
        } else if (newStart > rightChar) {
            newStart = rightChar;
        }
        
        if (newStart != start) {
            Selection.setSelection((Spannable)mText, newStart);
            return true;
        }
        
        return false;
    }

    @Override
    public void computeScroll() {
        if (mScroller != null) {
            if (mScroller.computeScrollOffset()) {
                mScrollX = mScroller.getCurrX();
                mScrollY = mScroller.getCurrY();
                postInvalidate();  // So we draw again
            }
        }
    }

    private void getInterestingRect(Rect r, int line) {
        convertFromViewportToContentCoordinates(r);

        // Rectangle can can be expanded on first and last line to take
        // padding into account.
        // TODO Take left/right padding into account too?
        if (line == 0) r.top -= getExtendedPaddingTop();
        if (line == mLayout.getLineCount() - 1) r.bottom += getExtendedPaddingBottom();
    }

    private void convertFromViewportToContentCoordinates(Rect r) {
        final int horizontalOffset = viewportToContentHorizontalOffset();
        r.left += horizontalOffset;
        r.right += horizontalOffset;

        final int verticalOffset = viewportToContentVerticalOffset();
        r.top += verticalOffset;
        r.bottom += verticalOffset;
    }

    private int viewportToContentHorizontalOffset() {
        return getCompoundPaddingLeft() - mScrollX;
    }

    private int viewportToContentVerticalOffset() {
        int offset = getExtendedPaddingTop() - mScrollY;
        if ((mGravity & Gravity.VERTICAL_GRAVITY_MASK) != Gravity.TOP) {
            offset += getVerticalOffset(false);
        }
        return offset;
    }

    @Override
    public void debug(int depth) {
        super.debug(depth);

        String output = debugIndent(depth);
        output += "frame={" + mLeft + ", " + mTop + ", " + mRight
                + ", " + mBottom + "} scroll={" + mScrollX + ", " + mScrollY
                + "} ";

        if (mText != null) {

            output += "mText=\"" + mText + "\" ";
            if (mLayout != null) {
                output += "mLayout width=" + mLayout.getWidth()
                        + " height=" + mLayout.getHeight();
            }
        } else {
            output += "mText=NULL";
        }
        Log.d(VIEW_LOG_TAG, output);
    }

    /**
     * Convenience for {@link Selection#getSelectionStart}.
     */
    @ViewDebug.ExportedProperty(category = "text")
    public int getSelectionStart() {
        return Selection.getSelectionStart(getText());
    }

    /**
     * Convenience for {@link Selection#getSelectionEnd}.
     */
    @ViewDebug.ExportedProperty(category = "text")
    public int getSelectionEnd() {
        return Selection.getSelectionEnd(getText());
    }

    /**
     * Return true iff there is a selection inside this text view.
     */
    public boolean hasSelection() {
        final int selectionStart = getSelectionStart();
        final int selectionEnd = getSelectionEnd();

        return selectionStart >= 0 && selectionStart != selectionEnd;
    }

    /**
     * Sets the properties of this field (lines, horizontally scrolling,
     * transformation method) to be for a single-line input.
     *
     * @attr ref android.R.styleable#TextView_singleLine
     */
    public void setSingleLine() {
        setSingleLine(true);
    }

    /**
     * If true, sets the properties of this field (lines, horizontally
     * scrolling, transformation method) to be for a single-line input;
     * if false, restores these to the default conditions.
     * Note that calling this with false restores default conditions,
     * not necessarily those that were in effect prior to calling
     * it with true.
     *
     * @attr ref android.R.styleable#TextView_singleLine
     */
    @android.view.RemotableViewMethod
    public void setSingleLine(boolean singleLine) {
        if ((mInputType&EditorInfo.TYPE_MASK_CLASS)
                == EditorInfo.TYPE_CLASS_TEXT) {
            if (singleLine) {
                mInputType &= ~EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE;
            } else {
                mInputType |= EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE;
            }
        }
        applySingleLine(singleLine, true);
    }

    private void applySingleLine(boolean singleLine, boolean applyTransformation) {
        mSingleLine = singleLine;
        if (singleLine) {
            setLines(1);
            setHorizontallyScrolling(true);
            if (applyTransformation) {
                setTransformationMethod(SingleLineTransformationMethod.
                                        getInstance());
            }
        } else {
            setMaxLines(Integer.MAX_VALUE);
            setHorizontallyScrolling(false);
            if (applyTransformation) {
                setTransformationMethod(null);
            }
        }
    }
    
    /**
     * Causes words in the text that are longer than the view is wide
     * to be ellipsized instead of broken in the middle.  You may also
     * want to {@link #setSingleLine} or {@link #setHorizontallyScrolling}
     * to constrain the text to a single line.  Use <code>null</code>
     * to turn off ellipsizing.
     *
     * @attr ref android.R.styleable#TextView_ellipsize
     */
    public void setEllipsize(TextUtils.TruncateAt where) {
        mEllipsize = where;

        if (mLayout != null) {
            nullLayouts();
            requestLayout();
            invalidate();
        }
    }

    /**
     * Sets how many times to repeat the marquee animation. Only applied if the
     * TextView has marquee enabled. Set to -1 to repeat indefinitely.
     *
     * @attr ref android.R.styleable#TextView_marqueeRepeatLimit
     */
    public void setMarqueeRepeatLimit(int marqueeLimit) {
        mMarqueeRepeatLimit = marqueeLimit;
    }

    /**
     * Returns where, if anywhere, words that are longer than the view
     * is wide should be ellipsized.
     */
    @ViewDebug.ExportedProperty
    public TextUtils.TruncateAt getEllipsize() {
        return mEllipsize;
    }

    /**
     * Set the TextView so that when it takes focus, all the text is
     * selected.
     *
     * @attr ref android.R.styleable#TextView_selectAllOnFocus
     */
    @android.view.RemotableViewMethod
    public void setSelectAllOnFocus(boolean selectAllOnFocus) {
        mSelectAllOnFocus = selectAllOnFocus;

        if (selectAllOnFocus && !(mText instanceof Spannable)) {
            setText(mText, BufferType.SPANNABLE);
        }
    }

    /**
     * Set whether the cursor is visible.  The default is true.
     *
     * @attr ref android.R.styleable#TextView_cursorVisible
     */
    @android.view.RemotableViewMethod
    public void setCursorVisible(boolean visible) {
        mCursorVisible = visible;
        invalidate();

        if (visible) {
            makeBlink();
        } else if (mBlink != null) {
            mBlink.removeCallbacks(mBlink);
        }

        // InsertionPointCursorController depends on mCursorVisible
        prepareCursorControllers();
    }

    private boolean canMarquee() {
        int width = (mRight - mLeft - getCompoundPaddingLeft() - getCompoundPaddingRight());
        return width > 0 && mLayout.getLineWidth(0) > width;
    }

    private void startMarquee() {
        // Do not ellipsize EditText
        if (mInput != null) return;

        if (compressText(getWidth() - getCompoundPaddingLeft() - getCompoundPaddingRight())) {
            return;
        }

        if ((mMarquee == null || mMarquee.isStopped()) && (isFocused() || isSelected()) &&
                getLineCount() == 1 && canMarquee()) {

            if (mMarquee == null) mMarquee = new Marquee(this);
            mMarquee.start(mMarqueeRepeatLimit);
        }
    }

    private void stopMarquee() {
        if (mMarquee != null && !mMarquee.isStopped()) {
            mMarquee.stop();
        }
    }

    private void startStopMarquee(boolean start) {
        if (mEllipsize == TextUtils.TruncateAt.MARQUEE) {
            if (start) {
                startMarquee();
            } else {
                stopMarquee();
            }
        }
    }

    private static final class Marquee extends Handler {
        // TODO: Add an option to configure this
        private static final float MARQUEE_DELTA_MAX = 0.07f;
        private static final int MARQUEE_DELAY = 1200;
        private static final int MARQUEE_RESTART_DELAY = 1200;
        private static final int MARQUEE_RESOLUTION = 1000 / 30;
        private static final int MARQUEE_PIXELS_PER_SECOND = 30;

        private static final byte MARQUEE_STOPPED = 0x0;
        private static final byte MARQUEE_STARTING = 0x1;
        private static final byte MARQUEE_RUNNING = 0x2;

        private static final int MESSAGE_START = 0x1;
        private static final int MESSAGE_TICK = 0x2;
        private static final int MESSAGE_RESTART = 0x3;

        private final WeakReference<TextView> mView;

        private byte mStatus = MARQUEE_STOPPED;
        private final float mScrollUnit;
        private float mMaxScroll;
        float mMaxFadeScroll;
        private float mGhostStart;
        private float mGhostOffset;
        private float mFadeStop;
        private int mRepeatLimit;

        float mScroll;

        Marquee(TextView v) {
            final float density = v.getContext().getResources().getDisplayMetrics().density;
            mScrollUnit = (MARQUEE_PIXELS_PER_SECOND * density) / MARQUEE_RESOLUTION;
            mView = new WeakReference<TextView>(v);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_START:
                    mStatus = MARQUEE_RUNNING;
                    tick();
                    break;
                case MESSAGE_TICK:
                    tick();
                    break;
                case MESSAGE_RESTART:
                    if (mStatus == MARQUEE_RUNNING) {
                        if (mRepeatLimit >= 0) {
                            mRepeatLimit--;
                        }
                        start(mRepeatLimit);
                    }
                    break;
            }
        }

        void tick() {
            if (mStatus != MARQUEE_RUNNING) {
                return;
            }

            removeMessages(MESSAGE_TICK);

            final TextView textView = mView.get();
            if (textView != null && (textView.isFocused() || textView.isSelected())) {
                mScroll += mScrollUnit;
                if (mScroll > mMaxScroll) {
                    mScroll = mMaxScroll;
                    sendEmptyMessageDelayed(MESSAGE_RESTART, MARQUEE_RESTART_DELAY);
                } else {
                    sendEmptyMessageDelayed(MESSAGE_TICK, MARQUEE_RESOLUTION);
                }
                textView.invalidate();
            }
        }

        void stop() {
            mStatus = MARQUEE_STOPPED;
            removeMessages(MESSAGE_START);
            removeMessages(MESSAGE_RESTART);
            removeMessages(MESSAGE_TICK);
            resetScroll();
        }

        private void resetScroll() {
            mScroll = 0.0f;
            final TextView textView = mView.get();
            if (textView != null) textView.invalidate();
        }

        void start(int repeatLimit) {
            if (repeatLimit == 0) {
                stop();
                return;
            }
            mRepeatLimit = repeatLimit;
            final TextView textView = mView.get();
            if (textView != null && textView.mLayout != null) {
                mStatus = MARQUEE_STARTING;
                mScroll = 0.0f;
                final int textWidth = textView.getWidth() - textView.getCompoundPaddingLeft() -
                        textView.getCompoundPaddingRight();
                final float lineWidth = textView.mLayout.getLineWidth(0);
                final float gap = textWidth / 3.0f;
                mGhostStart = lineWidth - textWidth + gap;
                mMaxScroll = mGhostStart + textWidth;
                mGhostOffset = lineWidth + gap;
                mFadeStop = lineWidth + textWidth / 6.0f;
                mMaxFadeScroll = mGhostStart + lineWidth + lineWidth;

                textView.invalidate();
                sendEmptyMessageDelayed(MESSAGE_START, MARQUEE_DELAY);
            }
        }

        float getGhostOffset() {
            return mGhostOffset;
        }

        boolean shouldDrawLeftFade() {
            return mScroll <= mFadeStop;
        }

        boolean shouldDrawGhost() {
            return mStatus == MARQUEE_RUNNING && mScroll > mGhostStart;
        }

        boolean isRunning() {
            return mStatus == MARQUEE_RUNNING;
        }

        boolean isStopped() {
            return mStatus == MARQUEE_STOPPED;
        }
    }

    /**
     * This method is called when the text is changed, in case any
     * subclasses would like to know.
     *
     * @param text The text the TextView is displaying.
     * @param start The offset of the start of the range of the text
     *              that was modified.
     * @param before The offset of the former end of the range of the
     *               text that was modified.  If text was simply inserted,
     *               this will be the same as <code>start</code>.
     *               If text was replaced with new text or deleted, the
     *               length of the old text was <code>before-start</code>.
     * @param after The offset of the end of the range of the text
     *              that was modified.  If text was simply deleted,
     *              this will be the same as <code>start</code>.
     *              If text was replaced with new text or inserted,
     *              the length of the new text is <code>after-start</code>.
     */
    protected void onTextChanged(CharSequence text,
                                 int start, int before, int after) {
    }

    /**
     * This method is called when the selection has changed, in case any
     * subclasses would like to know.
     * 
     * @param selStart The new selection start location.
     * @param selEnd The new selection end location.
     */
    protected void onSelectionChanged(int selStart, int selEnd) {
    }
    
    /**
     * Adds a TextWatcher to the list of those whose methods are called
     * whenever this TextView's text changes.
     * <p>
     * In 1.0, the {@link TextWatcher#afterTextChanged} method was erroneously
     * not called after {@link #setText} calls.  Now, doing {@link #setText}
     * if there are any text changed listeners forces the buffer type to
     * Editable if it would not otherwise be and does call this method.
     */
    public void addTextChangedListener(TextWatcher watcher) {
        if (mListeners == null) {
            mListeners = new ArrayList<TextWatcher>();
        }

        mListeners.add(watcher);
    }

    /**
     * Removes the specified TextWatcher from the list of those whose
     * methods are called
     * whenever this TextView's text changes.
     */
    public void removeTextChangedListener(TextWatcher watcher) {
        if (mListeners != null) {
            int i = mListeners.indexOf(watcher);

            if (i >= 0) {
                mListeners.remove(i);
            }
        }
    }

    private void sendBeforeTextChanged(CharSequence text, int start, int before,
                                   int after) {
        if (mListeners != null) {
            final ArrayList<TextWatcher> list = mListeners;
            final int count = list.size();
            for (int i = 0; i < count; i++) {
                list.get(i).beforeTextChanged(text, start, before, after);
            }
        }
    }

    /**
     * Not private so it can be called from an inner class without going
     * through a thunk.
     */
    void sendOnTextChanged(CharSequence text, int start, int before,
                                   int after) {
        if (mListeners != null) {
            final ArrayList<TextWatcher> list = mListeners;
            final int count = list.size();
            for (int i = 0; i < count; i++) {
                list.get(i).onTextChanged(text, start, before, after);
            }
        }
    }

    /**
     * Not private so it can be called from an inner class without going
     * through a thunk.
     */
    void sendAfterTextChanged(Editable text) {
        if (mListeners != null) {
            final ArrayList<TextWatcher> list = mListeners;
            final int count = list.size();
            for (int i = 0; i < count; i++) {
                list.get(i).afterTextChanged(text);
            }
        }
    }

    /**
     * Not private so it can be called from an inner class without going
     * through a thunk.
     */
    void handleTextChanged(CharSequence buffer, int start,
            int before, int after) {
        final InputMethodState ims = mInputMethodState;
        if (ims == null || ims.mBatchEditNesting == 0) {
            updateAfterEdit();
        }
        if (ims != null) {
            ims.mContentChanged = true;
            if (ims.mChangedStart < 0) {
                ims.mChangedStart = start;
                ims.mChangedEnd = start+before;
            } else {
                if (ims.mChangedStart > start) ims.mChangedStart = start;
                if (ims.mChangedEnd < (start+before)) ims.mChangedEnd = start+before;
            }
            ims.mChangedDelta += after-before;
        }
        
        sendOnTextChanged(buffer, start, before, after);
        onTextChanged(buffer, start, before, after);

        // Hide the controller if the amount of content changed
        if (before != after) {
            hideControllers();
        }
    }
    
    /**
     * Not private so it can be called from an inner class without going
     * through a thunk.
     */
    void spanChange(Spanned buf, Object what, int oldStart, int newStart,
            int oldEnd, int newEnd) {
        // XXX Make the start and end move together if this ends up
        // spending too much time invalidating.

        boolean selChanged = false;
        int newSelStart=-1, newSelEnd=-1;
        
        final InputMethodState ims = mInputMethodState;
        
        if (what == Selection.SELECTION_END) {
            mHighlightPathBogus = true;
            selChanged = true;
            newSelEnd = newStart;

            if (!isFocused()) {
                mSelectionMoved = true;
            }

            if (oldStart >= 0 || newStart >= 0) {
                invalidateCursor(Selection.getSelectionStart(buf), oldStart, newStart);
                registerForPreDraw();

                if (isFocused()) {
                    mShowCursor = SystemClock.uptimeMillis();
                    makeBlink();
                }
            }
        }

        if (what == Selection.SELECTION_START) {
            mHighlightPathBogus = true;
            selChanged = true;
            newSelStart = newStart;

            if (!isFocused()) {
                mSelectionMoved = true;
            }

            if (oldStart >= 0 || newStart >= 0) {
                int end = Selection.getSelectionEnd(buf);
                invalidateCursor(end, oldStart, newStart);
            }
        }

        if (selChanged) {
            if ((buf.getSpanFlags(what)&Spanned.SPAN_INTERMEDIATE) == 0) {
                if (newSelStart < 0) {
                    newSelStart = Selection.getSelectionStart(buf);
                }
                if (newSelEnd < 0) {
                    newSelEnd = Selection.getSelectionEnd(buf);
                }
                onSelectionChanged(newSelStart, newSelEnd);
            }
        }
        
        if (what instanceof UpdateAppearance ||
            what instanceof ParagraphStyle) {
            if (ims == null || ims.mBatchEditNesting == 0) {
                invalidate();
                mHighlightPathBogus = true;
                checkForResize();
            } else {
                ims.mContentChanged = true;
            }
        }

        if (MetaKeyKeyListener.isMetaTracker(buf, what)) {
            mHighlightPathBogus = true;
            if (ims != null && MetaKeyKeyListener.isSelectingMetaTracker(buf, what)) {
                ims.mSelectionModeChanged = true;
            }

            if (Selection.getSelectionStart(buf) >= 0) {
                if (ims == null || ims.mBatchEditNesting == 0) {
                    invalidateCursor();
                } else {
                    ims.mCursorChanged = true;
                }
            }
        }
        
        if (what instanceof ParcelableSpan) {
            // If this is a span that can be sent to a remote process,
            // the current extract editor would be interested in it.
            if (ims != null && ims.mExtracting != null) {
                if (ims.mBatchEditNesting != 0) {
                    if (oldStart >= 0) {
                        if (ims.mChangedStart > oldStart) {
                            ims.mChangedStart = oldStart;
                        }
                        if (ims.mChangedStart > oldEnd) {
                            ims.mChangedStart = oldEnd;
                        }
                    }
                    if (newStart >= 0) {
                        if (ims.mChangedStart > newStart) {
                            ims.mChangedStart = newStart;
                        }
                        if (ims.mChangedStart > newEnd) {
                            ims.mChangedStart = newEnd;
                        }
                    }
                } else {
                    if (DEBUG_EXTRACT) Log.v(LOG_TAG, "Span change outside of batch: "
                            + oldStart + "-" + oldEnd + ","
                            + newStart + "-" + newEnd + what);
                    ims.mContentChanged = true;
                }
            }
        }
    }

    private class ChangeWatcher
    implements TextWatcher, SpanWatcher {

        private CharSequence mBeforeText;

        public void beforeTextChanged(CharSequence buffer, int start,
                                      int before, int after) {
            if (DEBUG_EXTRACT) Log.v(LOG_TAG, "beforeTextChanged start=" + start
                    + " before=" + before + " after=" + after + ": " + buffer);

            if (AccessibilityManager.getInstance(mContext).isEnabled()
                    && !isPasswordInputType(mInputType)) {
                mBeforeText = buffer.toString();
            }

            TextView.this.sendBeforeTextChanged(buffer, start, before, after);
        }

        public void onTextChanged(CharSequence buffer, int start,
                                  int before, int after) {
            if (DEBUG_EXTRACT) Log.v(LOG_TAG, "onTextChanged start=" + start
                    + " before=" + before + " after=" + after + ": " + buffer);
            TextView.this.handleTextChanged(buffer, start, before, after);

            if (AccessibilityManager.getInstance(mContext).isEnabled() &&
                    (isFocused() || isSelected() &&
                    isShown())) {
                sendAccessibilityEventTypeViewTextChanged(mBeforeText, start, before, after);
                mBeforeText = null;
            }
        }

        public void afterTextChanged(Editable buffer) {
            if (DEBUG_EXTRACT) Log.v(LOG_TAG, "afterTextChanged: " + buffer);
            TextView.this.sendAfterTextChanged(buffer);

            if (MetaKeyKeyListener.getMetaState(buffer,
                                 MetaKeyKeyListener.META_SELECTING) != 0) {
                MetaKeyKeyListener.stopSelecting(TextView.this, buffer);
            }
        }

        public void onSpanChanged(Spannable buf,
                                  Object what, int s, int e, int st, int en) {
            if (DEBUG_EXTRACT) Log.v(LOG_TAG, "onSpanChanged s=" + s + " e=" + e
                    + " st=" + st + " en=" + en + " what=" + what + ": " + buf);
            TextView.this.spanChange(buf, what, s, st, e, en);
        }

        public void onSpanAdded(Spannable buf, Object what, int s, int e) {
            if (DEBUG_EXTRACT) Log.v(LOG_TAG, "onSpanAdded s=" + s + " e=" + e
                    + " what=" + what + ": " + buf);
            TextView.this.spanChange(buf, what, -1, s, -1, e);
        }

        public void onSpanRemoved(Spannable buf, Object what, int s, int e) {
            if (DEBUG_EXTRACT) Log.v(LOG_TAG, "onSpanRemoved s=" + s + " e=" + e
                    + " what=" + what + ": " + buf);
            TextView.this.spanChange(buf, what, s, -1, e, -1);
        }
    }

    private void makeBlink() {
        if (!mCursorVisible || !isTextEditable()) {
            if (mBlink != null) {
                mBlink.removeCallbacks(mBlink);
            }

            return;
        }

        if (mBlink == null)
            mBlink = new Blink(this);

        mBlink.removeCallbacks(mBlink);
        mBlink.postAtTime(mBlink, mShowCursor + BLINK);
    }

    /**
     * @hide
     */
    @Override
    public void dispatchFinishTemporaryDetach() {
        mDispatchTemporaryDetach = true;
        super.dispatchFinishTemporaryDetach();
        mDispatchTemporaryDetach = false;
    }

    @Override
    public void onStartTemporaryDetach() {
        super.onStartTemporaryDetach();
        // Only track when onStartTemporaryDetach() is called directly,
        // usually because this instance is an editable field in a list
        if (!mDispatchTemporaryDetach) mTemporaryDetach = true;
    }
    
    @Override
    public void onFinishTemporaryDetach() {
        super.onFinishTemporaryDetach();
        // Only track when onStartTemporaryDetach() is called directly,
        // usually because this instance is an editable field in a list
        if (!mDispatchTemporaryDetach) mTemporaryDetach = false;
    }
    
    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previouslyFocusedRect) {
        if (mTemporaryDetach) {
            // If we are temporarily in the detach state, then do nothing.
            super.onFocusChanged(focused, direction, previouslyFocusedRect);
            return;
        }
        
        mShowCursor = SystemClock.uptimeMillis();

        ensureEndedBatchEdit();

        if (focused) {
            int selStart = getSelectionStart();
            int selEnd = getSelectionEnd();

            // SelectAllOnFocus fields are highlighted and not selected. Do not start text selection
            // mode for these, unless there was a specific selection already started.
            final boolean isFocusHighlighted = mSelectAllOnFocus && selStart == 0 &&
                    selEnd == mText.length();
            mCreatedWithASelection = mFrozenWithFocus && hasSelection() && !isFocusHighlighted;

            if (!mFrozenWithFocus || (selStart < 0 || selEnd < 0)) {
                // If a tap was used to give focus to that view, move cursor at tap position.
                // Has to be done before onTakeFocus, which can be overloaded.
                final int lastTapPosition = getLastTapPosition();
                if (lastTapPosition >= 0) {
                    Selection.setSelection((Spannable) mText, lastTapPosition);
                }

                if (mMovement != null) {
                    mMovement.onTakeFocus(this, (Spannable) mText, direction);
                }

                // The DecorView does not have focus when the 'Done' ExtractEditText button is
                // pressed. Since it is the ViewRoot's mView, it requests focus before
                // ExtractEditText clears focus, which gives focus to the ExtractEditText.
                // This special case ensure that we keep current selection in that case.
                // It would be better to know why the DecorView does not have focus at that time.
                if (((this instanceof ExtractEditText) || mSelectionMoved) &&
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
                    Selection.setSelection((Spannable) mText, selStart, selEnd);
                }

                if (mSelectAllOnFocus) {
                    Selection.setSelection((Spannable) mText, 0, mText.length());
                }

                mTouchFocusSelected = true;
            }

            mFrozenWithFocus = false;
            mSelectionMoved = false;

            if (mText instanceof Spannable) {
                Spannable sp = (Spannable) mText;
                MetaKeyKeyListener.resetMetaState(sp);
            }

            makeBlink();

            if (mError != null) {
                showError();
            }
        } else {
            if (mError != null) {
                hideError();
            }
            // Don't leave us in the middle of a batch edit.
            onEndBatchEdit();

            hideInsertionPointCursorController();
            if (this instanceof ExtractEditText) {
                // terminateTextSelectionMode would remove selection, which we want to keep when
                // ExtractEditText goes out of focus.
                mIsInTextSelectionMode = false;
            } else {
                stopTextSelectionMode();
            }

            if (mSelectionModifierCursorController != null) {
                ((SelectionModifierCursorController) mSelectionModifierCursorController).resetTouchOffsets();
            }
        }

        startStopMarquee(focused);

        if (mTransformation != null) {
            mTransformation.onFocusChanged(this, mText, focused, direction, previouslyFocusedRect);
        }

        super.onFocusChanged(focused, direction, previouslyFocusedRect);
    }

    private int getLastTapPosition() {
        if (mSelectionModifierCursorController != null) {
            int lastTapPosition = ((SelectionModifierCursorController)
                    mSelectionModifierCursorController).getMinTouchOffset();
            if (lastTapPosition >= 0) {
                // Safety check, should not be possible.
                if (lastTapPosition > mText.length()) {
                    Log.e(LOG_TAG, "Invalid tap focus position (" + lastTapPosition + " vs "
                            + mText.length() + ")");
                    lastTapPosition = mText.length();
                }
                return lastTapPosition;
            }
        }

        return -1;
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);

        if (hasWindowFocus) {
            if (mBlink != null) {
                mBlink.uncancel();

                if (isFocused()) {
                    mShowCursor = SystemClock.uptimeMillis();
                    makeBlink();
                }
            }
        } else {
            if (mBlink != null) {
                mBlink.cancel();
            }
            // Don't leave us in the middle of a batch edit.
            onEndBatchEdit();
            if (mInputContentType != null) {
                mInputContentType.enterDown = false;
            }
            hideControllers();
        }

        startStopMarquee(hasWindowFocus);
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility != VISIBLE) {
            hideControllers();
        }
    }

    /**
     * Use {@link BaseInputConnection#removeComposingSpans
     * BaseInputConnection.removeComposingSpans()} to remove any IME composing
     * state from this text view.
     */
    public void clearComposingText() {
        if (mText instanceof Spannable) {
            BaseInputConnection.removeComposingSpans((Spannable)mText);
        }
    }
    
    @Override
    public void setSelected(boolean selected) {
        boolean wasSelected = isSelected();

        super.setSelected(selected);

        if (selected != wasSelected && mEllipsize == TextUtils.TruncateAt.MARQUEE) {
            if (selected) {
                startMarquee();
            } else {
                stopMarquee();
            }
        }
    }

    private void onTapUpEvent(int prevStart, int prevEnd) {
        final int start = getSelectionStart();
        final int end = getSelectionEnd();

        if (start == end) {
            boolean tapInsideSelectAllOnFocus = mSelectAllOnFocus && prevStart == 0 &&
                  prevEnd == mText.length();
            if (start >= prevStart && start < prevEnd && !tapInsideSelectAllOnFocus) {
                // Restore previous selection
                Selection.setSelection((Spannable)mText, prevStart, prevEnd);

                // Tapping inside the selection displays the cut/copy/paste context menu, unless
                // this is a double tap that should simply trigger text selection mode.
                if (!mNoContextMenuOnUp) showContextMenu();
            } else {
                // Tapping outside stops selection mode, if any
                stopTextSelectionMode();

                boolean selectAllGotFocus = mSelectAllOnFocus && mTouchFocusSelected;
                if (hasInsertionController() && !selectAllGotFocus) {
                    getInsertionController().show();
                }
            }
        }
    }

    class CommitSelectionReceiver extends ResultReceiver {
        private final int mPrevStart, mPrevEnd;
        
        public CommitSelectionReceiver(int prevStart, int prevEnd) {
            super(getHandler());
            mPrevStart = prevStart;
            mPrevEnd = prevEnd;
        }
        
        @Override
        protected void onReceiveResult(int resultCode, Bundle resultData) {
            // If this tap was actually used to show the IMM, leave cursor or selection unchanged
            // by restoring its previous position.
            if (resultCode == InputMethodManager.RESULT_SHOWN) {
                final int len = mText.length();
                int start = Math.min(len, mPrevStart);
                int end = Math.min(len, mPrevEnd);
                Selection.setSelection((Spannable)mText, start, end);

                boolean selectAllGotFocus = mSelectAllOnFocus && mTouchFocusSelected;
                if (hasSelection() && !selectAllGotFocus) {
                    startTextSelectionMode();
                }
            }
        }
    }
    
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final int action = event.getActionMasked();

        if (hasInsertionController()) {
            getInsertionController().onTouchEvent(event);
        }
        if (hasSelectionController()) {
            getSelectionController().onTouchEvent(event);
        }

        if (action == MotionEvent.ACTION_DOWN) {
        // Reset this state; it will be re-set if super.onTouchEvent
        // causes focus to move to the view.
            mTouchFocusSelected = false;
            mScrolled = false;
        }

        boolean result = super.onTouchEvent(event);

        /*
         * Don't handle the release after a long press, because it will
         * move the selection away from whatever the menu action was
         * trying to affect.
         */
        if (mEatTouchRelease && action == MotionEvent.ACTION_UP) {
            mEatTouchRelease = false;
        } else if ((mMovement != null || onCheckIsTextEditor()) && mText instanceof Spannable &&
                mLayout != null) {
            boolean handled = false;

            // Save previous selection, in case this event is used to show the IME.
            int oldSelStart = getSelectionStart();
            int oldSelEnd = getSelectionEnd();

            final int oldScrollX = mScrollX;
            final int oldScrollY = mScrollY;
            
            if (mMovement != null) {
                handled |= mMovement.onTouchEvent(this, (Spannable) mText, event);
            }

            if (isTextEditable()) {
                if (mScrollX != oldScrollX || mScrollY != oldScrollY) {
                    // Hide insertion anchor while scrolling. Leave selection.
                    hideInsertionPointCursorController();
                    if (mSelectionModifierCursorController != null &&
                            mSelectionModifierCursorController.isShowing()) {
                        mSelectionModifierCursorController.updatePosition();
                    }
                }
                if (action == MotionEvent.ACTION_UP && isFocused() && !mScrolled) {
                    InputMethodManager imm = (InputMethodManager)
                          getContext().getSystemService(Context.INPUT_METHOD_SERVICE);

                    CommitSelectionReceiver csr = null;
                    if (getSelectionStart() != oldSelStart || getSelectionEnd() != oldSelEnd ||
                            didTouchFocusSelect()) {
                        csr = new CommitSelectionReceiver(oldSelStart, oldSelEnd);
                    }

                    handled |= imm.showSoftInput(this, 0, csr) && (csr != null);

                    // Cannot be done by CommitSelectionReceiver, which might not always be called,
                    // for instance when dealing with an ExtractEditText.
                    onTapUpEvent(oldSelStart, oldSelEnd);
                }
            }

            if (handled) result = true;
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            mNoContextMenuOnUp = false;
        }

        return result;
    }

    private void prepareCursorControllers() {
        boolean windowSupportsHandles = false;

        ViewGroup.LayoutParams params = getRootView().getLayoutParams();
        if (params instanceof WindowManager.LayoutParams) {
            WindowManager.LayoutParams windowParams = (WindowManager.LayoutParams) params;
            windowSupportsHandles = windowParams.type < WindowManager.LayoutParams.FIRST_SUB_WINDOW
                    || windowParams.type > WindowManager.LayoutParams.LAST_SUB_WINDOW;
        }

        // TODO Add an extra android:cursorController flag to disable the controller?
        mInsertionControllerEnabled = windowSupportsHandles && mCursorVisible && mLayout != null;
        mSelectionControllerEnabled = windowSupportsHandles && textCanBeSelected() &&
                mLayout != null;

        if (!mInsertionControllerEnabled) {
            mInsertionPointCursorController = null;
        }

        if (!mSelectionControllerEnabled) {
            // Stop selection mode if the controller becomes unavailable.
            stopTextSelectionMode();
            mSelectionModifierCursorController = null;
        }
    }

    /**
     * @return True iff this TextView contains a text that can be edited.
     */
    private boolean isTextEditable() {
        return mText instanceof Editable && onCheckIsTextEditor() && isEnabled();
    }

    /**
     * Returns true, only while processing a touch gesture, if the initial
     * touch down event caused focus to move to the text view and as a result
     * its selection changed.  Only valid while processing the touch gesture
     * of interest.
     */
    public boolean didTouchFocusSelect() {
        return mTouchFocusSelected;
    }
    
    @Override
    public void cancelLongPress() {
        super.cancelLongPress();
        mScrolled = true;
    }
    
    @Override
    public boolean onTrackballEvent(MotionEvent event) {
        if (mMovement != null && mText instanceof Spannable &&
            mLayout != null) {
            if (mMovement.onTrackballEvent(this, (Spannable) mText, event)) {
                return true;
            }
        }

        return super.onTrackballEvent(event);
    }

    public void setScroller(Scroller s) {
        mScroller = s;
    }

    private static class Blink extends Handler implements Runnable {
        private final WeakReference<TextView> mView;
        private boolean mCancelled;

        public Blink(TextView v) {
            mView = new WeakReference<TextView>(v);
        }

        public void run() {
            if (mCancelled) {
                return;
            }

            removeCallbacks(Blink.this);

            TextView tv = mView.get();

            if (tv != null && tv.isFocused()) {
                int st = tv.getSelectionStart();
                int en = tv.getSelectionEnd();

                if (st == en && st >= 0 && en >= 0) {
                    if (tv.mLayout != null) {
                        tv.invalidateCursorPath();
                    }

                    postAtTime(this, SystemClock.uptimeMillis() + BLINK);
                }
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

    @Override
    protected float getLeftFadingEdgeStrength() {
        if (mEllipsize == TextUtils.TruncateAt.MARQUEE) {
            if (mMarquee != null && !mMarquee.isStopped()) {
                final Marquee marquee = mMarquee;
                if (marquee.shouldDrawLeftFade()) {
                    return marquee.mScroll / getHorizontalFadingEdgeLength();
                } else {
                    return 0.0f;
                }
            } else if (getLineCount() == 1) {
                switch (mGravity & Gravity.HORIZONTAL_GRAVITY_MASK) {
                    case Gravity.LEFT:
                        return 0.0f;
                    case Gravity.RIGHT:
                        return (mLayout.getLineRight(0) - (mRight - mLeft) -
                                getCompoundPaddingLeft() - getCompoundPaddingRight() -
                                mLayout.getLineLeft(0)) / getHorizontalFadingEdgeLength();
                    case Gravity.CENTER_HORIZONTAL:
                        return 0.0f;
                }
            }
        }
        return super.getLeftFadingEdgeStrength();
    }

    @Override
    protected float getRightFadingEdgeStrength() {
        if (mEllipsize == TextUtils.TruncateAt.MARQUEE) {
            if (mMarquee != null && !mMarquee.isStopped()) {
                final Marquee marquee = mMarquee;
                return (marquee.mMaxFadeScroll - marquee.mScroll) / getHorizontalFadingEdgeLength();
            } else if (getLineCount() == 1) {
                switch (mGravity & Gravity.HORIZONTAL_GRAVITY_MASK) {
                    case Gravity.LEFT:
                        final int textWidth = (mRight - mLeft) - getCompoundPaddingLeft() -
                                getCompoundPaddingRight();
                        final float lineWidth = mLayout.getLineWidth(0);
                        return (lineWidth - textWidth) / getHorizontalFadingEdgeLength();
                    case Gravity.RIGHT:
                        return 0.0f;
                    case Gravity.CENTER_HORIZONTAL:
                        return (mLayout.getLineWidth(0) - ((mRight - mLeft) -
                                getCompoundPaddingLeft() - getCompoundPaddingRight())) /
                                getHorizontalFadingEdgeLength();
                }
            }
        }
        return super.getRightFadingEdgeStrength();
    }

    @Override
    protected int computeHorizontalScrollRange() {
        if (mLayout != null)
            return mLayout.getWidth();

        return super.computeHorizontalScrollRange();
    }

    @Override
    protected int computeVerticalScrollRange() {
        if (mLayout != null)
            return mLayout.getHeight();

        return super.computeVerticalScrollRange();
    }

    @Override
    protected int computeVerticalScrollExtent() {
        return getHeight() - getCompoundPaddingTop() - getCompoundPaddingBottom();
    }
    
    public enum BufferType {
        NORMAL, SPANNABLE, EDITABLE,
    }

    /**
     * Returns the TextView_textColor attribute from the
     * Resources.StyledAttributes, if set, or the TextAppearance_textColor
     * from the TextView_textAppearance attribute, if TextView_textColor
     * was not set directly.
     */
    public static ColorStateList getTextColors(Context context, TypedArray attrs) {
        ColorStateList colors;
        colors = attrs.getColorStateList(com.android.internal.R.styleable.
                                         TextView_textColor);

        if (colors == null) {
            int ap = attrs.getResourceId(com.android.internal.R.styleable.
                                         TextView_textAppearance, -1);
            if (ap != -1) {
                TypedArray appearance;
                appearance = context.obtainStyledAttributes(ap,
                                            com.android.internal.R.styleable.TextAppearance);
                colors = appearance.getColorStateList(com.android.internal.R.styleable.
                                                  TextAppearance_textColor);
                appearance.recycle();
            }
        }

        return colors;
    }

    /**
     * Returns the default color from the TextView_textColor attribute
     * from the AttributeSet, if set, or the default color from the
     * TextAppearance_textColor from the TextView_textAppearance attribute,
     * if TextView_textColor was not set directly.
     */
    public static int getTextColor(Context context,
                                   TypedArray attrs,
                                   int def) {
        ColorStateList colors = getTextColors(context, attrs);

        if (colors == null) {
            return def;
        } else {
            return colors.getDefaultColor();
        }
    }

    @Override
    public boolean onKeyShortcut(int keyCode, KeyEvent event) {
        switch (keyCode) {
        case KeyEvent.KEYCODE_A:
            if (canSelectText()) {
                return onTextContextMenuItem(ID_SELECT_ALL);
            }

            break;

        case KeyEvent.KEYCODE_X:
            if (canCut()) {
                return onTextContextMenuItem(ID_CUT);
            }

            break;

        case KeyEvent.KEYCODE_C:
            if (canCopy()) {
                return onTextContextMenuItem(ID_COPY);
            }

            break;

        case KeyEvent.KEYCODE_V:
            if (canPaste()) {
                return onTextContextMenuItem(ID_PASTE);
            }

            break;
        }

        return super.onKeyShortcut(keyCode, event);
    }

    private boolean canSelectText() {
        return hasSelectionController() && mText.length() != 0;
    }

    private boolean textCanBeSelected() {
        // prepareCursorController() relies on this method.
        // If you change this condition, make sure prepareCursorController is called anywhere
        // the value of this condition might be changed.
        return (mText instanceof Spannable &&
                mMovement != null &&
                mMovement.canSelectArbitrarily());
    }

    private boolean canCut() {
        if (hasPasswordTransformationMethod()) {
            return false;
        }

        if (mText.length() > 0 && hasSelection()) {
            if (mText instanceof Editable && mInput != null) {
                return true;
            }
        }

        return false;
    }

    private boolean canCopy() {
        if (hasPasswordTransformationMethod()) {
            return false;
        }

        if (mText.length() > 0 && hasSelection()) {
            return true;
        }

        return false;
    }

    private boolean canPaste() {
        return (mText instanceof Editable &&
                mInput != null &&
                getSelectionStart() >= 0 &&
                getSelectionEnd() >= 0 &&
                ((ClipboardManager)getContext().getSystemService(Context.CLIPBOARD_SERVICE)).
                hasText());
    }

    /**
     * Returns the offsets delimiting the 'word' located at position offset.
     *
     * @param offset An offset in the text.
     * @return The offsets for the start and end of the word located at <code>offset</code>.
     * The two ints offsets are packed in a long, with the starting offset shifted by 32 bits.
     * Returns a negative value if no valid word was found.
     */
    private long getWordLimitsAt(int offset) {
        /*
         * Quick return if the input type is one where adding words
         * to the dictionary doesn't make any sense.
         */
        int klass = mInputType & InputType.TYPE_MASK_CLASS;
        if (klass == InputType.TYPE_CLASS_NUMBER ||
            klass == InputType.TYPE_CLASS_PHONE ||
            klass == InputType.TYPE_CLASS_DATETIME) {
            return -1;
        }

        int variation = mInputType & InputType.TYPE_MASK_VARIATION;
        if (variation == InputType.TYPE_TEXT_VARIATION_URI ||
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_FILTER) {
            return -1;
        }

        int len = mText.length();
        int end = Math.min(offset, len);

        if (end < 0) {
            return -1;
        }

        int start = end;

        for (; start > 0; start--) {
            char c = mTransformed.charAt(start - 1);
            int type = Character.getType(c);

            if (c != '\'' &&
                type != Character.UPPERCASE_LETTER &&
                type != Character.LOWERCASE_LETTER &&
                type != Character.TITLECASE_LETTER &&
                type != Character.MODIFIER_LETTER &&
                type != Character.DECIMAL_DIGIT_NUMBER) {
                break;
            }
        }

        for (; end < len; end++) {
            char c = mTransformed.charAt(end);
            int type = Character.getType(c);

            if (c != '\'' &&
                type != Character.UPPERCASE_LETTER &&
                type != Character.LOWERCASE_LETTER &&
                type != Character.TITLECASE_LETTER &&
                type != Character.MODIFIER_LETTER &&
                type != Character.DECIMAL_DIGIT_NUMBER) {
                break;
            }
        }

        if (start == end) {
            return -1;
        }

        if (end - start > 48) {
            return -1;
        }

        boolean hasLetter = false;
        for (int i = start; i < end; i++) {
            if (Character.isLetter(mTransformed.charAt(i))) {
                hasLetter = true;
                break;
            }
        }

        if (!hasLetter) {
            return -1;
        }

        // Two ints packed in a long
        return packRangeInLong(start, end);
    }
    
    private static long packRangeInLong(int start, int end) {
        return (((long) start) << 32) | end;
    }

    private static int extractRangeStartFromLong(long range) {
        return (int) (range >>> 32);
    }

    private static int extractRangeEndFromLong(long range) {
        return (int) (range & 0x00000000FFFFFFFFL);
    }

    private void selectCurrentWord() {
        // In case selection mode is started after an orientation change or after a select all,
        // use the current selection instead of creating one
        if (hasSelection()) {
            return;
        }

        int minOffset, maxOffset;

        if (mContextMenuTriggeredByKey) {
            minOffset = getSelectionStart();
            maxOffset = getSelectionEnd();
        } else {
            // hasSelectionController is true since we canSelectText.
            SelectionModifierCursorController selectionModifierCursorController =
                (SelectionModifierCursorController) getSelectionController();
            minOffset = selectionModifierCursorController.getMinTouchOffset();
            maxOffset = selectionModifierCursorController.getMaxTouchOffset();
        }

        int selectionStart, selectionEnd;

        long wordLimits = getWordLimitsAt(minOffset);
        if (wordLimits >= 0) {
            selectionStart = extractRangeStartFromLong(wordLimits);
        } else {
            selectionStart = Math.max(minOffset - 5, 0);
        }

        wordLimits = getWordLimitsAt(maxOffset);
        if (wordLimits >= 0) {
            selectionEnd = extractRangeEndFromLong(wordLimits);
        } else {
            selectionEnd = Math.min(maxOffset + 5, mText.length());
        }

        Selection.setSelection((Spannable) mText, selectionStart, selectionEnd);
    }
    
    private String getWordForDictionary() {
        int seedPosition = mContextMenuTriggeredByKey ? getSelectionStart() : getLastTapPosition();
        long wordLimits = getWordLimitsAt(seedPosition);
        if (wordLimits >= 0) {
            int start = extractRangeStartFromLong(wordLimits);
            int end = extractRangeEndFromLong(wordLimits);
            return mTransformed.subSequence(start, end).toString();
        } else {
            return null;
        }
    }
    
    @Override
    public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
        if (!isShown()) {
            return false;
        }

        final boolean isPassword = isPasswordInputType(mInputType);

        if (!isPassword) {
            CharSequence text = getText();
            if (TextUtils.isEmpty(text)) {
                text = getHint();
            }
            if (!TextUtils.isEmpty(text)) {
                if (text.length() > AccessibilityEvent.MAX_TEXT_LENGTH) {
                    text = text.subSequence(0, AccessibilityEvent.MAX_TEXT_LENGTH + 1);
                }
                event.getText().add(text);
            }
        } else {
            event.setPassword(isPassword);
        }
        return false;
    }

    void sendAccessibilityEventTypeViewTextChanged(CharSequence beforeText,
            int fromIndex, int removedCount, int addedCount) {
        AccessibilityEvent event =
            AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED);
        event.setFromIndex(fromIndex);
        event.setRemovedCount(removedCount);
        event.setAddedCount(addedCount);
        event.setBeforeText(beforeText);
        sendAccessibilityEventUnchecked(event);
    }

    @Override
    protected void onCreateContextMenu(ContextMenu menu) {
        super.onCreateContextMenu(menu);
        boolean added = false;
        mContextMenuTriggeredByKey = mDPadCenterIsDown || mEnterKeyIsDown;
        // Problem with context menu on long press: the menu appears while the key in down and when
        // the key is released, the view does not receive the key_up event. This ensures that the
        // state is reset whenever the context menu action is displayed.
        // mContextMenuTriggeredByKey saved that state so that it is available in
        // onTextContextMenuItem. We cannot simply clear these flags in onTextContextMenuItem since
        // it may not be called (if the user/ discards the context menu with the back key).
        mDPadCenterIsDown = mEnterKeyIsDown = false;

        if (mIsInTextSelectionMode) {
            MenuHandler handler = new MenuHandler();
            
            if (canCut()) {
                menu.add(0, ID_CUT, 0, com.android.internal.R.string.cut).
                     setOnMenuItemClickListener(handler).
                     setAlphabeticShortcut('x');
                added = true;
            }

            if (canCopy()) {
                menu.add(0, ID_COPY, 0, com.android.internal.R.string.copy).
                     setOnMenuItemClickListener(handler).
                     setAlphabeticShortcut('c');
                added = true;
            }

            if (canPaste()) {
                menu.add(0, ID_PASTE, 0, com.android.internal.R.string.paste).
                     setOnMenuItemClickListener(handler).
                     setAlphabeticShortcut('v');
                added = true;
            }
        } else {
            MenuHandler handler = new MenuHandler();

            if (canSelectText()) {
                if (!hasPasswordTransformationMethod()) {
                    // selectCurrentWord is not available on a password field and would return an
                    // arbitrary 10-charater selection around pressed position. Discard it.
                    // SelectAll is still useful to be able to clear the field using the delete key.
                    menu.add(0, ID_START_SELECTING_TEXT, 0, com.android.internal.R.string.selectText).
                    setOnMenuItemClickListener(handler);
                }
                menu.add(0, ID_SELECT_ALL, 0, com.android.internal.R.string.selectAll).
                     setOnMenuItemClickListener(handler).
                     setAlphabeticShortcut('a');
                added = true;
            }

            if (mText instanceof Spanned) {
                int selStart = getSelectionStart();
                int selEnd = getSelectionEnd();

                int min = Math.min(selStart, selEnd);
                int max = Math.max(selStart, selEnd);

                URLSpan[] urls = ((Spanned) mText).getSpans(min, max,
                        URLSpan.class);
                if (urls.length == 1) {
                    menu.add(0, ID_COPY_URL, 0, com.android.internal.R.string.copyUrl).
                         setOnMenuItemClickListener(handler);
                    added = true;
                }
            }
            
            if (canPaste()) {
                menu.add(0, ID_PASTE, 0, com.android.internal.R.string.paste).
                     setOnMenuItemClickListener(handler).
                     setAlphabeticShortcut('v');
                added = true;
            }

            if (isInputMethodTarget()) {
                menu.add(1, ID_SWITCH_INPUT_METHOD, 0, com.android.internal.R.string.inputMethod).
                     setOnMenuItemClickListener(handler);
                added = true;
            }

            String word = getWordForDictionary();
            if (word != null) {
                menu.add(1, ID_ADD_TO_DICTIONARY, 0,
                     getContext().getString(com.android.internal.R.string.addToDictionary, word)).
                     setOnMenuItemClickListener(handler);
                added = true;

            }
        }

        if (added) {
            hideControllers();
            menu.setHeaderTitle(com.android.internal.R.string.editTextMenuTitle);
        }
    }

    /**
     * Returns whether this text view is a current input method target.  The
     * default implementation just checks with {@link InputMethodManager}.
     */
    public boolean isInputMethodTarget() {
        InputMethodManager imm = InputMethodManager.peekInstance();
        return imm != null && imm.isActive(this);
    }
    
    // Context menu entries
    private static final int ID_SELECT_ALL = android.R.id.selectAll;
    private static final int ID_START_SELECTING_TEXT = android.R.id.startSelectingText;
    private static final int ID_CUT = android.R.id.cut;
    private static final int ID_COPY = android.R.id.copy;
    private static final int ID_PASTE = android.R.id.paste;
    private static final int ID_COPY_URL = android.R.id.copyUrl;
    private static final int ID_SWITCH_INPUT_METHOD = android.R.id.switchInputMethod;
    private static final int ID_ADD_TO_DICTIONARY = android.R.id.addToDictionary;

    private class MenuHandler implements MenuItem.OnMenuItemClickListener {
        public boolean onMenuItemClick(MenuItem item) {
            return onTextContextMenuItem(item.getItemId());
        }
    }

    /**
     * Called when a context menu option for the text view is selected.  Currently
     * this will be one of: {@link android.R.id#selectAll},
     * {@link android.R.id#startSelectingText},
     * {@link android.R.id#cut}, {@link android.R.id#copy},
     * {@link android.R.id#paste}, {@link android.R.id#copyUrl},
     * or {@link android.R.id#switchInputMethod}.
     */
    public boolean onTextContextMenuItem(int id) {
        int min = 0;
        int max = mText.length();

        if (isFocused()) {
            final int selStart = getSelectionStart();
            final int selEnd = getSelectionEnd();

            min = Math.max(0, Math.min(selStart, selEnd));
            max = Math.max(0, Math.max(selStart, selEnd));
        }

        ClipboardManager clip = (ClipboardManager)getContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);

        switch (id) {
            case ID_SELECT_ALL:
                Selection.setSelection((Spannable) mText, 0, mText.length());
                startTextSelectionMode();
                getSelectionController().show();
                return true;

            case ID_START_SELECTING_TEXT:
                startTextSelectionMode();
                getSelectionController().show();
                return true;

            case ID_CUT:                
                clip.setText(mTransformed.subSequence(min, max));
                ((Editable) mText).delete(min, max);
                stopTextSelectionMode();
                return true;

            case ID_COPY:
                clip.setText(mTransformed.subSequence(min, max));
                stopTextSelectionMode();
                return true;

            case ID_PASTE:
                CharSequence paste = clip.getText();

                if (paste != null && paste.length() > 0) {
                    long minMax = prepareSpacesAroundPaste(min, max, paste);
                    min = extractRangeStartFromLong(minMax);
                    max = extractRangeEndFromLong(minMax);
                    Selection.setSelection((Spannable) mText, max);
                    ((Editable) mText).replace(min, max, paste);
                    stopTextSelectionMode();
                }
                return true;

            case ID_COPY_URL:
                URLSpan[] urls = ((Spanned) mText).getSpans(min, max, URLSpan.class);
                if (urls.length == 1) {
                    clip.setText(urls[0].getURL());
                }
                return true;

            case ID_SWITCH_INPUT_METHOD:
                InputMethodManager imm = InputMethodManager.peekInstance();
                if (imm != null) {
                    imm.showInputMethodPicker();
                }
                return true;

            case ID_ADD_TO_DICTIONARY:
                String word = getWordForDictionary();
                if (word != null) {
                    Intent i = new Intent("com.android.settings.USER_DICTIONARY_INSERT");
                    i.putExtra("word", word);
                    i.setFlags(i.getFlags() | Intent.FLAG_ACTIVITY_NEW_TASK);
                    getContext().startActivity(i);
                }
                return true;
            }

        return false;
    }

    /**
     * Prepare text so that there are not zero or two spaces at beginning and end of region defined
     * by [min, max] when replacing this region by paste.
     */
    private long prepareSpacesAroundPaste(int min, int max, CharSequence paste) {
        // Paste adds/removes spaces before or after insertion as needed.
        if (Character.isSpaceChar(paste.charAt(0))) {
            if (min > 0 && Character.isSpaceChar(mTransformed.charAt(min - 1))) {
                // Two spaces at beginning of paste: remove one
                final int originalLength = mText.length();
                ((Editable) mText).replace(min - 1, min, "");
                // Due to filters, there is no garantee that exactly one character was
                // removed. Count instead.
                final int delta = mText.length() - originalLength;
                min += delta;
                max += delta;
            }
        } else {
            if (min > 0 && !Character.isSpaceChar(mTransformed.charAt(min - 1))) {
                // No space at beginning of paste: add one
                final int originalLength = mText.length();
                ((Editable) mText).replace(min, min, " ");
                // Taking possible filters into account as above.
                final int delta = mText.length() - originalLength;
                min += delta;
                max += delta;
            }
        }

        if (Character.isSpaceChar(paste.charAt(paste.length() - 1))) {
            if (max < mText.length() && Character.isSpaceChar(mTransformed.charAt(max))) {
                // Two spaces at end of paste: remove one
                ((Editable) mText).replace(max, max + 1, "");
            }
        } else {
            if (max < mText.length() && !Character.isSpaceChar(mTransformed.charAt(max))) {
                // No space at end of paste: add one
                ((Editable) mText).replace(max, max, " ");
            }
        }
        return packRangeInLong(min, max);
    }

    @Override
    public boolean performLongClick() {
        if (super.performLongClick()) {
            mEatTouchRelease = true;
            return true;
        }

        return false;
    }

    private void startTextSelectionMode() {
        if (!mIsInTextSelectionMode) {
            if (!hasSelectionController()) {
                Log.w(LOG_TAG, "TextView has no selection controller. Action mode cancelled.");
                return;
            }

            if (!canSelectText() || !requestFocus()) {
                return;
            }

            selectCurrentWord();
            getSelectionController().show();
            final InputMethodManager imm = (InputMethodManager)
                    getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.showSoftInput(this, 0, null);
            mIsInTextSelectionMode = true;
        }
    }

    private void stopTextSelectionMode() {
        if (mIsInTextSelectionMode) {
            Selection.setSelection((Spannable) mText, getSelectionEnd());
            hideSelectionModifierCursorController();
            mIsInTextSelectionMode = false;
        }
    }

    /**
     * A CursorController instance can be used to control a cursor in the text.
     * It is not used outside of {@link TextView}.
     * @hide
     */
    private interface CursorController extends ViewTreeObserver.OnTouchModeChangeListener {
        /**
         * Makes the cursor controller visible on screen. Will be drawn by {@link #draw(Canvas)}.
         * See also {@link #hide()}.
         */
        public void show();

        /**
         * Hide the cursor controller from screen.
         * See also {@link #show()}.
         */
        public void hide();

        /**
         * @return true if the CursorController is currently visible
         */
        public boolean isShowing();

        /**
         * Update the controller's position.
         */
        public void updatePosition(HandleView handle, int x, int y);

        public void updatePosition();

        /**
         * This method is called by {@link #onTouchEvent(MotionEvent)} and gives the controller
         * a chance to become active and/or visible.
         * @param event The touch event
         */
        public boolean onTouchEvent(MotionEvent event);

        /**
         * Called when the view is detached from window. Perform house keeping task, such as
         * stopping Runnable thread that would otherwise keep a reference on the context, thus
         * preventing the activity to be recycled.
         */
        public void onDetached();
    }

    private class HandleView extends View {
        private boolean mPositionOnTop = false;
        private Drawable mDrawable;
        private PopupWindow mContainer;
        private int mPositionX;
        private int mPositionY;
        private CursorController mController;
        private boolean mIsDragging;
        private float mTouchToWindowOffsetX;
        private float mTouchToWindowOffsetY;
        private float mHotspotX;
        private float mHotspotY;
        private int mHeight;
        private float mTouchOffsetY;
        private int mLastParentX;
        private int mLastParentY;

        public static final int LEFT = 0;
        public static final int CENTER = 1;
        public static final int RIGHT = 2;

        public HandleView(CursorController controller, int pos) {
            super(TextView.this.mContext);
            mController = controller;
            mContainer = new PopupWindow(TextView.this.mContext, null,
                    com.android.internal.R.attr.textSelectHandleWindowStyle);
            mContainer.setSplitTouchEnabled(true);
            mContainer.setClippingEnabled(false);
            mContainer.setWindowLayoutType(WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL);

            setOrientation(pos);
        }

        public void setOrientation(int pos) {
            int handleWidth;
            switch (pos) {
            case LEFT: {
                if (mSelectHandleLeft == null) {
                    mSelectHandleLeft = mContext.getResources().getDrawable(
                            mTextSelectHandleLeftRes);
                }
                mDrawable = mSelectHandleLeft;
                handleWidth = mDrawable.getIntrinsicWidth();
                mHotspotX = (handleWidth * 3) / 4;
                break;
            }

            case RIGHT: {
                if (mSelectHandleRight == null) {
                    mSelectHandleRight = mContext.getResources().getDrawable(
                            mTextSelectHandleRightRes);
                }
                mDrawable = mSelectHandleRight;
                handleWidth = mDrawable.getIntrinsicWidth();
                mHotspotX = handleWidth / 4;
                break;
            }

            case CENTER:
            default: {
                if (mSelectHandleCenter == null) {
                    mSelectHandleCenter = mContext.getResources().getDrawable(
                            mTextSelectHandleRes);
                }
                mDrawable = mSelectHandleCenter;
                handleWidth = mDrawable.getIntrinsicWidth();
                mHotspotX = handleWidth / 2;
                break;
            }
            }

            final int handleHeight = mDrawable.getIntrinsicHeight();

            mTouchOffsetY = -handleHeight * 0.3f;
            mHotspotY = 0;
            mHeight = handleHeight;
            invalidate();
        }

        @Override
        public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(mDrawable.getIntrinsicWidth(),
                    mDrawable.getIntrinsicHeight());
        }

        public void show() {
            if (!isPositionVisible()) {
                hide();
                return;
            }
            mContainer.setContentView(this);
            final int[] coords = mTempCoords;
            TextView.this.getLocationInWindow(coords);
            coords[0] += mPositionX;
            coords[1] += mPositionY;
            mContainer.showAtLocation(TextView.this, 0, coords[0], coords[1]);
        }

        public void hide() {
            mIsDragging = false;
            mContainer.dismiss();
        }

        public boolean isShowing() {
            return mContainer.isShowing();
        }

        private boolean isPositionVisible() {
            // Always show a dragging handle.
            if (mIsDragging) {
                return true;
            }

            if (isInBatchEditMode()) {
                return false;
            }

            final int extendedPaddingTop = getExtendedPaddingTop();
            final int extendedPaddingBottom = getExtendedPaddingBottom();
            final int compoundPaddingLeft = getCompoundPaddingLeft();
            final int compoundPaddingRight = getCompoundPaddingRight();

            final TextView hostView = TextView.this;
            final int left = 0;
            final int right = hostView.getWidth();
            final int top = 0;
            final int bottom = hostView.getHeight();

            if (mTempRect == null) {
                mTempRect = new Rect();
            }
            final Rect clip = mTempRect;
            clip.left = left + compoundPaddingLeft;
            clip.top = top + extendedPaddingTop;
            clip.right = right - compoundPaddingRight;
            clip.bottom = bottom - extendedPaddingBottom;

            final ViewParent parent = hostView.getParent();
            if (parent == null || !parent.getChildVisibleRect(hostView, clip, null)) {
                return false;
            }

            final int[] coords = mTempCoords;
            hostView.getLocationInWindow(coords);
            final int posX = coords[0] + mPositionX + (int) mHotspotX;
            final int posY = coords[1] + mPositionY + (int) mHotspotY;

            return posX >= clip.left && posX <= clip.right &&
                    posY >= clip.top && posY <= clip.bottom;
        }

        private void moveTo(int x, int y) {
            mPositionX = x - TextView.this.mScrollX;
            mPositionY = y - TextView.this.mScrollY;
            if (isPositionVisible()) {
                int[] coords = null;
                if (mContainer.isShowing()) {
                    coords = mTempCoords;
                    TextView.this.getLocationInWindow(coords);
                    mContainer.update(coords[0] + mPositionX, coords[1] + mPositionY,
                            mRight - mLeft, mBottom - mTop);
                } else {
                    show();
                }

                if (mIsDragging) {
                    if (coords == null) {
                        coords = mTempCoords;
                        TextView.this.getLocationInWindow(coords);
                    }
                    if (coords[0] != mLastParentX || coords[1] != mLastParentY) {
                        mTouchToWindowOffsetX += coords[0] - mLastParentX;
                        mTouchToWindowOffsetY += coords[1] - mLastParentY;
                        mLastParentX = coords[0];
                        mLastParentY = coords[1];
                    }
                }
            } else {
                hide();
            }
        }

        @Override
        public void onDraw(Canvas c) {
            mDrawable.setBounds(0, 0, mRight - mLeft, mBottom - mTop);
            if (mPositionOnTop) {
                c.save();
                c.rotate(180, (mRight - mLeft) / 2, (mBottom - mTop) / 2);
                mDrawable.draw(c);
                c.restore();
            } else {
                mDrawable.draw(c);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                final float rawX = ev.getRawX();
                final float rawY = ev.getRawY();
                mTouchToWindowOffsetX = rawX - mPositionX;
                mTouchToWindowOffsetY = rawY - mPositionY;
                final int[] coords = mTempCoords;
                TextView.this.getLocationInWindow(coords);
                mLastParentX = coords[0];
                mLastParentY = coords[1];
                mIsDragging = true;
                break;
            }

            case MotionEvent.ACTION_MOVE: {
                final float rawX = ev.getRawX();
                final float rawY = ev.getRawY();
                final float newPosX = rawX - mTouchToWindowOffsetX + mHotspotX;
                final float newPosY = rawY - mTouchToWindowOffsetY + mHotspotY + mTouchOffsetY;

                mController.updatePosition(this, Math.round(newPosX), Math.round(newPosY));

                break;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mIsDragging = false;
            }
            return true;
        }

        public boolean isDragging() {
            return mIsDragging;
        }

        void positionAtCursor(final int offset, boolean bottom) {
            final int width = mDrawable.getIntrinsicWidth();
            final int height = mDrawable.getIntrinsicHeight();
            final int line = mLayout.getLineForOffset(offset);
            final int lineTop = mLayout.getLineTop(line);
            final int lineBottom = mLayout.getLineBottom(line);

            final Rect bounds = sCursorControllerTempRect;
            bounds.left = (int) (mLayout.getPrimaryHorizontal(offset) - mHotspotX)
                + TextView.this.mScrollX;
            bounds.top = (bottom ? lineBottom : lineTop - mHeight) + TextView.this.mScrollY;

            bounds.right = bounds.left + width;
            bounds.bottom = bounds.top + height;

            convertFromViewportToContentCoordinates(bounds);
            moveTo(bounds.left, bounds.top);
        }
    }

    private class InsertionPointCursorController implements CursorController {
        private static final int DELAY_BEFORE_FADE_OUT = 4100;

        // The cursor controller image
        private final HandleView mHandle;

        private final Runnable mHider = new Runnable() {
            public void run() {
                hide();
            }
        };

        InsertionPointCursorController() {
            mHandle = new HandleView(this, HandleView.CENTER);
        }

        public void show() {
            updatePosition();
            mHandle.show();
            hideDelayed(DELAY_BEFORE_FADE_OUT);
        }

        public void hide() {
            mHandle.hide();
            removeCallbacks(mHider);
        }

        private void hideDelayed(int msec) {
            removeCallbacks(mHider);
            postDelayed(mHider, msec);
        }

        public boolean isShowing() {
            return mHandle.isShowing();
        }

        public void updatePosition(HandleView handle, int x, int y) {
            final int previousOffset = getSelectionStart();
            int offset = getHysteresisOffset(x, y, previousOffset);

            if (offset != previousOffset) {
                Selection.setSelection((Spannable) mText, offset);
                updatePosition();
            }
            hideDelayed(DELAY_BEFORE_FADE_OUT);
        }

        public void updatePosition() {
            final int offset = getSelectionStart();

            if (offset < 0) {
                // Should never happen, safety check.
                Log.w(LOG_TAG, "Update cursor controller position called with no cursor");
                hide();
                return;
            }

            mHandle.positionAtCursor(offset, true);
        }

        public boolean onTouchEvent(MotionEvent ev) {
            return false;
        }

        public void onTouchModeChanged(boolean isInTouchMode) {
            if (!isInTouchMode) {
                hide();
            }
        }

        @Override
        public void onDetached() {
            removeCallbacks(mHider);
        }
    }

    private class SelectionModifierCursorController implements CursorController {
        // The cursor controller images
        private HandleView mStartHandle, mEndHandle;
        // The offsets of that last touch down event. Remembered to start selection there.
        private int mMinTouchOffset, mMaxTouchOffset;
        // Whether selection anchors are active
        private boolean mIsShowing;
        // Double tap detection
        private long mPreviousTapUpTime = 0;
        private int mPreviousTapPositionX;
        private int mPreviousTapPositionY;

        SelectionModifierCursorController() {
            mStartHandle = new HandleView(this, HandleView.LEFT);
            mEndHandle = new HandleView(this, HandleView.RIGHT);
            resetTouchOffsets();
        }

        public void show() {
            if (isInBatchEditMode()) {
                return;
            }

            mIsShowing = true;
            updatePosition();
            mStartHandle.show();
            mEndHandle.show();
            hideInsertionPointCursorController();
        }

        public void hide() {
            mStartHandle.hide();
            mEndHandle.hide();
            mIsShowing = false;
        }

        public boolean isShowing() {
            return mIsShowing;
        }

        public void updatePosition(HandleView handle, int x, int y) {
            int selectionStart = getSelectionStart();
            int selectionEnd = getSelectionEnd();

            final int previousOffset = handle == mStartHandle ? selectionStart : selectionEnd;
            int offset = getHysteresisOffset(x, y, previousOffset);

            // Handle the case where start and end are swapped, making sure start <= end
            if (handle == mStartHandle) {
                if (selectionStart == offset || offset > selectionEnd) {
                    return; // no change, no need to redraw;
                }
                // If the user "closes" the selection entirely they were probably trying to
                // select a single character. Help them out.
                if (offset == selectionEnd) {
                    offset = selectionEnd - 1;
                }
                selectionStart = offset;
            } else {
                if (selectionEnd == offset || offset < selectionStart) {
                    return; // no change, no need to redraw;
                }
                // If the user "closes" the selection entirely they were probably trying to
                // select a single character. Help them out.
                if (offset == selectionStart) {
                    offset = selectionStart + 1;
                }
                selectionEnd = offset;
            }

            Selection.setSelection((Spannable) mText, selectionStart, selectionEnd);
            updatePosition();
        }

        public void updatePosition() {
            if (!isShowing()) {
                return;
            }

            final int selectionStart = getSelectionStart();
            final int selectionEnd = getSelectionEnd();

            if ((selectionStart < 0) || (selectionEnd < 0)) {
                // Should never happen, safety check.
                Log.w(LOG_TAG, "Update selection controller position called with no cursor");
                hide();
                return;
            }

            mStartHandle.positionAtCursor(selectionStart, true);
            mEndHandle.positionAtCursor(selectionEnd, true);
        }

        public boolean onTouchEvent(MotionEvent event) {
            // This is done even when the View does not have focus, so that long presses can start
            // selection and tap can move cursor from this tap position.
            if (isTextEditable()) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        final int x = (int) event.getX();
                        final int y = (int) event.getY();

                        // Remember finger down position, to be able to start selection from there
                        mMinTouchOffset = mMaxTouchOffset = getOffset(x, y);

                        // Double tap detection
                        long duration = SystemClock.uptimeMillis() - mPreviousTapUpTime;
                        if (duration <= ViewConfiguration.getDoubleTapTimeout()) {
                            final int deltaX = x - mPreviousTapPositionX;
                            final int deltaY = y - mPreviousTapPositionY;
                            final int distanceSquared = deltaX * deltaX + deltaY * deltaY;
                            final int doubleTapSlop = ViewConfiguration.get(getContext()).getScaledDoubleTapSlop();
                            final int slopSquared = doubleTapSlop * doubleTapSlop;
                            if (distanceSquared < slopSquared) {
                                startTextSelectionMode();
                                // prevents onTapUpEvent from opening a context menu with cut/copy
                                mNoContextMenuOnUp = true;
                            }
                        }
                        mPreviousTapPositionX = x;
                        mPreviousTapPositionY = y;

                        break;

                    case MotionEvent.ACTION_POINTER_DOWN:
                    case MotionEvent.ACTION_POINTER_UP:
                        // Handle multi-point gestures. Keep min and max offset positions.
                        // Only activated for devices that correctly handle multi-touch.
                        if (mContext.getPackageManager().hasSystemFeature(
                                PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH_DISTINCT)) {
                            updateMinAndMaxOffsets(event);
                        }
                        break;

                    case MotionEvent.ACTION_UP:
                        mPreviousTapUpTime = SystemClock.uptimeMillis();
                        break;
                }
            }
            return false;
        }

        /**
         * @param event
         */
        private void updateMinAndMaxOffsets(MotionEvent event) {
            int pointerCount = event.getPointerCount();
            for (int index = 0; index < pointerCount; index++) {
                final int x = (int) event.getX(index);
                final int y = (int) event.getY(index);
                int offset = getOffset(x, y);
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
        }

        /**
         * @return true iff this controller is currently used to move the selection start.
         */
        public boolean isSelectionStartDragged() {
            return mStartHandle.isDragging();
        }

        public void onTouchModeChanged(boolean isInTouchMode) {
            if (!isInTouchMode) {
                hide();
            }
        }

        @Override
        public void onDetached() {}
    }

    private void hideInsertionPointCursorController() {
        if (mInsertionPointCursorController != null) {
            mInsertionPointCursorController.hide();
        }
    }

    private void hideSelectionModifierCursorController() {
        if (mSelectionModifierCursorController != null) {
            mSelectionModifierCursorController.hide();
        }
    }
    
    private void hideControllers() {
        hideInsertionPointCursorController();
        hideSelectionModifierCursorController();
    }

    private int getOffsetForHorizontal(int line, int x) {
        x -= getTotalPaddingLeft();
        // Clamp the position to inside of the view.
        x = Math.max(0, x);
        x = Math.min(getWidth() - getTotalPaddingRight() - 1, x);
        x += getScrollX();
        return getLayout().getOffsetForHorizontal(line, x);
    }

    /**
     * Get the offset character closest to the specified absolute position.
     *
     * @param x The horizontal absolute position of a point on screen
     * @param y The vertical absolute position of a point on screen
     * @return the character offset for the character whose position is closest to the specified
     *  position. Returns -1 if there is no layout.
     *
     * @hide
     */
    public int getOffset(int x, int y) {
        if (getLayout() == null) return -1;

        y -= getTotalPaddingTop();
        // Clamp the position to inside of the view.
        y = Math.max(0, y);
        y = Math.min(getHeight() - getTotalPaddingBottom() - 1, y);
        y += getScrollY();

        final int line = getLayout().getLineForVertical(y);
        final int offset = getOffsetForHorizontal(line, x);
        return offset;
    }

    int getHysteresisOffset(int x, int y, int previousOffset) {
        final Layout layout = getLayout();
        if (layout == null) return -1;

        y -= getTotalPaddingTop();
        // Clamp the position to inside of the view.
        y = Math.max(0, y);
        y = Math.min(getHeight() - getTotalPaddingBottom() - 1, y);
        y += getScrollY();

        int line = getLayout().getLineForVertical(y);

        final int previousLine = layout.getLineForOffset(previousOffset);
        final int previousLineTop = layout.getLineTop(previousLine);
        final int previousLineBottom = layout.getLineBottom(previousLine);
        final int hysteresisThreshold = (previousLineBottom - previousLineTop) / 8;

        // If new line is just before or after previous line and y position is less than
        // hysteresisThreshold away from previous line, keep cursor on previous line.
        if (((line == previousLine + 1) && ((y - previousLineBottom) < hysteresisThreshold)) ||
            ((line == previousLine - 1) && ((previousLineTop - y)    < hysteresisThreshold))) {
            line = previousLine;
        }

        return getOffsetForHorizontal(line, x);
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

    CursorController getInsertionController() {
        if (!mInsertionControllerEnabled) {
            return null;
        }

        if (mInsertionPointCursorController == null) {
            mInsertionPointCursorController = new InsertionPointCursorController();

            final ViewTreeObserver observer = getViewTreeObserver();
            if (observer != null) {
                observer.addOnTouchModeChangeListener(mInsertionPointCursorController);
            }
        }

        return mInsertionPointCursorController;
    }

    CursorController getSelectionController() {
        if (!mSelectionControllerEnabled) {
            return null;
        }

        if (mSelectionModifierCursorController == null) {
            mSelectionModifierCursorController = new SelectionModifierCursorController();

            final ViewTreeObserver observer = getViewTreeObserver();
            if (observer != null) {
                observer.addOnTouchModeChangeListener(mSelectionModifierCursorController);
            }
        }

        return mSelectionModifierCursorController;
    }

    boolean isInBatchEditMode() {
        final InputMethodState ims = mInputMethodState;
        if (ims != null) {
            return ims.mBatchEditNesting > 0;
        }
        return mInBatchEditControllers;
    }

    @ViewDebug.ExportedProperty
    private CharSequence            mText;
    private CharSequence            mTransformed;
    private BufferType              mBufferType = BufferType.NORMAL;

    private int                     mInputType = EditorInfo.TYPE_NULL;
    private CharSequence            mHint;
    private Layout                  mHintLayout;

    private KeyListener             mInput;

    private MovementMethod          mMovement;
    private TransformationMethod    mTransformation;
    private ChangeWatcher           mChangeWatcher;

    private ArrayList<TextWatcher>  mListeners = null;

    // display attributes
    private final TextPaint         mTextPaint;
    private boolean                 mUserSetTextScaleX;
    private final Paint             mHighlightPaint;
    private int                     mHighlightColor = 0xCC475925;
    private Layout                  mLayout;

    private long                    mShowCursor;
    private Blink                   mBlink;
    private boolean                 mCursorVisible = true;

    // Cursor Controllers. Null when disabled.
    private CursorController        mInsertionPointCursorController;
    private CursorController        mSelectionModifierCursorController;
    private boolean                 mInsertionControllerEnabled;
    private boolean                 mSelectionControllerEnabled;
    private boolean                 mInBatchEditControllers;
    private boolean                 mIsInTextSelectionMode = false;
    // These are needed to desambiguate a long click. If the long click comes from ones of these, we
    // select from the current cursor position. Otherwise, select from long pressed position.
    private boolean                 mDPadCenterIsDown = false;
    private boolean                 mEnterKeyIsDown = false;
    private boolean                 mContextMenuTriggeredByKey = false;
    // Created once and shared by different CursorController helper methods.
    // Only one cursor controller is active at any time which prevent race conditions.
    private static Rect             sCursorControllerTempRect = new Rect();

    private boolean                 mSelectAllOnFocus = false;

    private int                     mGravity = Gravity.TOP | Gravity.LEFT;
    private boolean                 mHorizontallyScrolling;

    private int                     mAutoLinkMask;
    private boolean                 mLinksClickable = true;

    private float                   mSpacingMult = 1;
    private float                   mSpacingAdd = 0;

    private static final int        LINES = 1;
    private static final int        EMS = LINES;
    private static final int        PIXELS = 2;

    private int                     mMaximum = Integer.MAX_VALUE;
    private int                     mMaxMode = LINES;
    private int                     mMinimum = 0;
    private int                     mMinMode = LINES;

    private int                     mMaxWidth = Integer.MAX_VALUE;
    private int                     mMaxWidthMode = PIXELS;
    private int                     mMinWidth = 0;
    private int                     mMinWidthMode = PIXELS;

    private boolean                 mSingleLine;
    private int                     mDesiredHeightAtMeasure = -1;
    private boolean                 mIncludePad = true;

    // tmp primitives, so we don't alloc them on each draw
    private Path                    mHighlightPath;
    private boolean                 mHighlightPathBogus = true;
    private static final RectF      sTempRect = new RectF();

    // XXX should be much larger
    private static final int        VERY_WIDE = 16384;

    private static final int        BLINK = 500;

    private static final int ANIMATED_SCROLL_GAP = 250;
    private long mLastScroll;
    private Scroller mScroller = null;

    private BoringLayout.Metrics mBoring;
    private BoringLayout.Metrics mHintBoring;

    private BoringLayout mSavedLayout, mSavedHintLayout;

    private static final InputFilter[] NO_FILTERS = new InputFilter[0];
    private InputFilter[] mFilters = NO_FILTERS;
    private static final Spanned EMPTY_SPANNED = new SpannedString("");
}
