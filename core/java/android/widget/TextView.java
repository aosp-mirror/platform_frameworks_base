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

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.text.BoringLayout;
import android.text.DynamicLayout;
import android.text.Editable;
import android.text.GetChars;
import android.text.GraphicsOperations;
import android.text.ClipboardManager;
import android.text.InputFilter;
import android.text.Layout;
import android.text.Selection;
import android.text.SpanWatcher;
import android.text.Spannable;
import android.text.Spanned;
import android.text.SpannedString;
import android.text.SpannableString;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.DialerKeyListener;
import android.text.method.DigitsKeyListener;
import android.text.method.KeyListener;
import android.text.method.LinkMovementMethod;
import android.text.method.MetaKeyKeyListener;
import android.text.method.MovementMethod;
import android.text.method.PasswordTransformationMethod;
import android.text.method.SingleLineTransformationMethod;
import android.text.method.TextKeyListener;
import android.text.method.TransformationMethod;
import android.text.style.ParagraphStyle;
import android.text.style.URLSpan;
import android.text.style.UpdateLayout;
import android.text.util.Linkify;
import android.util.AttributeSet;
import android.util.Log;
import android.util.FloatMath;
import android.util.TypedValue;
import android.view.ContextMenu;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewDebug;
import android.view.ViewTreeObserver;
import android.view.ViewGroup.LayoutParams;
import android.view.animation.AnimationUtils;
import android.widget.RemoteViews.RemoteView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

import com.android.internal.util.FastMath;

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
 * @attr ref android.R.styleable#TextView_drawableTop
 * @attr ref android.R.styleable#TextView_drawableBottom
 * @attr ref android.R.styleable#TextView_drawableRight
 * @attr ref android.R.styleable#TextView_drawableLeft
 * @attr ref android.R.styleable#TextView_lineSpacingExtra
 * @attr ref android.R.styleable#TextView_lineSpacingMultiplier
 */
@RemoteView
public class TextView extends View implements ViewTreeObserver.OnPreDrawListener {
    private static int PRIORITY = 100;

    private ColorStateList mTextColor;
    private int mCurTextColor;
    private ColorStateList mHintTextColor;
    private ColorStateList mLinkTextColor;
    private int mCurHintTextColor;
    private boolean mFreezesText;
    private boolean mFrozenWithFocus;

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

    private Drawable mDrawableTop, mDrawableBottom, mDrawableLeft, mDrawableRight;
    private int mDrawableSizeTop, mDrawableSizeBottom, mDrawableSizeLeft, mDrawableSizeRight;
    private int mDrawableWidthTop, mDrawableWidthBottom, mDrawableHeightLeft, mDrawableHeightRight;
    private boolean mDrawables;
    private int mDrawablePadding;

    private CharSequence mError;
    private boolean mErrorWasChanged;
    private PopupWindow mPopup;

    private CharWrapper mCharWrapper = null;
    private Rect mCompoundRect;

    private boolean mSelectionMoved = false;

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

    public TextView(Context context) {
        this(context, null);
    }

    public TextView(Context context,
                    AttributeSet attrs) {
        this(context, attrs, com.android.internal.R.attr.textViewStyle);
    }

    public TextView(Context context,
                    AttributeSet attrs,
                    int defStyle) {
        super(context, attrs, defStyle);

        mText = "";

        mTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        // If we get the paint from the skin, we should set it to left, since
        // the layout always wants it to be left.
        // mTextPaint.setTextAlign(Paint.Align.LEFT);

        mHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

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
        int shadowcolor = 0;
        float dx = 0, dy = 0, r = 0;
        boolean password = false;

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
                setHint(a.getText(attr));
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
            }
        }
        a.recycle();

        BufferType bufferType = BufferType.EDITABLE;

        if (inputMethod != null) {
            Class c;

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
        } else if (digits != null) {
            mInput = DigitsKeyListener.getInstance(digits.toString());
        } else if (phone) {
            mInput = DialerKeyListener.getInstance();
        } else if (numeric != 0) {
            mInput = DigitsKeyListener.getInstance((numeric & SIGNED) != 0,
                                                   (numeric & DECIMAL) != 0);
        } else if (autotext || autocap != -1) {
            TextKeyListener.Capitalize cap;

            switch (autocap) {
            case 1:
                cap = TextKeyListener.Capitalize.SENTENCES;
                break;

            case 2:
                cap = TextKeyListener.Capitalize.WORDS;
                break;

            case 3:
                cap = TextKeyListener.Capitalize.CHARACTERS;
                break;

            default:
                cap = TextKeyListener.Capitalize.NONE;
                break;
            }

            mInput = TextKeyListener.getInstance(autotext, cap);
        } else if (editable) {
            mInput = TextKeyListener.getInstance();
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
     * Return the text the TextView is displaying.  If setText() was called
     * with an argument of BufferType.SPANNABLE or BufferType.EDITABLE,
     * you can cast the return value from this method to Spannable
     * or Editable, respectively.
     */
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
     * to disallow user input.
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
        mInput = input;

        if (mInput != null && !(mText instanceof Editable))
            setText(mText);

        setFilters((Editable) mText, mFilters);
        fixFocusableAndClickableSettings();
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
    }

    private void fixFocusableAndClickableSettings() {
        if (mMovement != null || mInput != null) {
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
        if (mDrawableTop == null) {
            return mPaddingTop;
        } else {
            return mPaddingTop + mDrawablePadding + mDrawableSizeTop;
        }
    }

    /**
     * Returns the bottom padding of the view, plus space for the bottom
     * Drawable if any.
     */
    public int getCompoundPaddingBottom() {
        if (mDrawableBottom == null) {
            return mPaddingBottom;
        } else {
            return mPaddingBottom + mDrawablePadding + mDrawableSizeBottom;
        }
    }

    /**
     * Returns the left padding of the view, plus space for the left
     * Drawable if any.
     */
    public int getCompoundPaddingLeft() {
        if (mDrawableLeft == null) {
            return mPaddingLeft;
        } else {
            return mPaddingLeft + mDrawablePadding + mDrawableSizeLeft;
        }
    }

    /**
     * Returns the right padding of the view, plus space for the right
     * Drawable if any.
     */
    public int getCompoundPaddingRight() {
        if (mDrawableRight == null) {
            return mPaddingRight;
        } else {
            return mPaddingRight + mDrawablePadding + mDrawableSizeRight;
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
        mDrawableLeft = left;
        mDrawableTop = top;
        mDrawableRight = right;
        mDrawableBottom = bottom;

        mDrawables = mDrawableLeft != null
                || mDrawableRight != null
                || mDrawableTop != null
                || mDrawableBottom != null;

        if (mCompoundRect == null &&
                (left != null || top != null || right != null || bottom != null)) {
            mCompoundRect = new Rect();
        }

        final Rect compoundRect = mCompoundRect;
        int[] state = null;
        
        if (mDrawables) {
            state = getDrawableState();
        }
        
        if (mDrawableLeft != null) {
            mDrawableLeft.setState(state);
            mDrawableLeft.copyBounds(compoundRect);
            mDrawableSizeLeft = compoundRect.width();
            mDrawableHeightLeft = compoundRect.height();
        } else {
            mDrawableSizeLeft = mDrawableHeightLeft = 0;
        }

        if (mDrawableRight != null) {
            mDrawableRight.setState(state);
            mDrawableRight.copyBounds(compoundRect);
            mDrawableSizeRight = compoundRect.width();
            mDrawableHeightRight = compoundRect.height();
        } else {
            mDrawableSizeRight = mDrawableHeightRight = 0;
        }

        if (mDrawableTop != null) {
            mDrawableTop.setState(state);
            mDrawableTop.copyBounds(compoundRect);
            mDrawableSizeTop = compoundRect.height();
            mDrawableWidthTop = compoundRect.width();
        } else {
            mDrawableSizeTop = mDrawableWidthTop = 0;
        }

        if (mDrawableBottom != null) {
            mDrawableBottom.setState(state);
            mDrawableBottom.copyBounds(compoundRect);
            mDrawableSizeBottom = compoundRect.height();
            mDrawableWidthBottom = compoundRect.width();
        } else {
            mDrawableSizeBottom = mDrawableWidthBottom = 0;
        }

        invalidate();
        requestLayout();
    }

    /**
     * Sets the Drawables (if any) to appear to the left of, above,
     * to the right of, and below the text.  Use null if you do not
     * want a Drawable there.  The Drawables' bounds will be set to
     * their intrinsic bounds.
     *
     * @attr ref android.R.styleable#TextView_drawableLeft
     * @attr ref android.R.styleable#TextView_drawableTop
     * @attr ref android.R.styleable#TextView_drawableRight
     * @attr ref android.R.styleable#TextView_drawableBottom
     */
    public void setCompoundDrawablesWithIntrinsicBounds(Drawable left,
                                     Drawable top,
                                     Drawable right, Drawable bottom) {
        if (left != null) {
            left.setBounds(0, 0,
                           left.getIntrinsicWidth(), left.getIntrinsicHeight());
        }
        if (right != null) {
            right.setBounds(0, 0,
                           right.getIntrinsicWidth(), right.getIntrinsicHeight());
        }
        if (top != null) {
            top.setBounds(0, 0,
                           top.getIntrinsicWidth(), top.getIntrinsicHeight());
        }
        if (bottom != null) {
            bottom.setBounds(0, 0,
                           bottom.getIntrinsicWidth(), bottom.getIntrinsicHeight());
        }
        setCompoundDrawables(left, top, right, bottom);
    }

    /**
     * Returns drawables for the left, top, right, and bottom borders.
     */
    public Drawable[] getCompoundDrawables() {
        return new Drawable[] {
            mDrawableLeft, mDrawableTop, mDrawableRight, mDrawableBottom
        };
    }

    /**
     * Sets the size of the padding between the compound drawables and
     * the text.
     *
     * @attr ref android.R.styleable#TextView_drawablePadding
     */
    public void setCompoundDrawablePadding(int pad) {
        mDrawablePadding = pad;

        invalidate();
        requestLayout();
    }

    /**
     * Returns the padding between the compound drawables and the text.
     */
    public int getCompoundDrawablePadding() {
        return mDrawablePadding;
    }

    @Override
    public void setPadding(int left, int top, int right, int bottom) {
        if (left != getPaddingLeft() ||
            right != getPaddingRight() ||
            top != getPaddingTop() ||
            bottom != getPaddingBottom()) {
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
    public void setTextScaleX(float size) {
        if (size != mTextPaint.getTextScaleX()) {
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

        int[] state = getDrawableState();
        if (mDrawableTop != null && mDrawableTop.isStateful()) {
            mDrawableTop.setState(state);
        }
        if (mDrawableBottom != null && mDrawableBottom.isStateful()) {
            mDrawableBottom.setState(state);
        }
        if (mDrawableLeft != null && mDrawableLeft.isStateful()) {
            mDrawableLeft.setState(state);
        }
        if (mDrawableRight != null && mDrawableRight.isStateful()) {
            mDrawableRight.setState(state);
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
            start = Selection.getSelectionStart(mText);
            end = Selection.getSelectionEnd(mText);
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

            return ss;
        }
        
        return null;
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
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

                    Log.e("TextView", "Saved cursor position " + ss.selStart +
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
     * {@link android.text.SpannableString SpannableString}, or see
     * <a href="{@docRoot}reference/available-resources.html#stringresources">
     * String Resources</a> for an example of setting formatted text in the XML resource file.
     *
     * @attr ref android.R.styleable#TextView_text
     */
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

        if (type == BufferType.EDITABLE || mInput != null) {
            Editable t = mEditableFactory.newEditable(text);
            text = t;

            setFilters(t, mFilters);
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

    public final void setText(int resid) {
        setText(getContext().getResources().getText(resid));
    }

    public final void setText(int resid, BufferType type) {
        setText(getContext().getResources().getText(resid), type);
    }

    /**
     * Sets the text to be displayed when the text of the TextView is empty.
     * Null means to use the normal empty text.  The hint does not
     * currently participate in determining the size of the view.
     *
     * @attr ref android.R.styleable#TextView_hint
     */
    public final void setHint(CharSequence hint) {
        mHint = TextUtils.stringOrSpannedString(hint);

        if (mLayout != null) {
            checkForRelayout();
        }

        if (mText.length() == 0)
            invalidate();
    }

    /**
     * Sets the text to be displayed when the text of the TextView is empty,
     * from a resource.
     *
     * @attr ref android.R.styleable#TextView_hint
     */
    public final void setHint(int resid) {
        setHint(getContext().getResources().getText(resid));
    }

    /**
     * Returns the hint that is displayed when the text of the TextView
     * is empty.
     *
     * @attr ref android.R.styleable#TextView_hint
     */
    public CharSequence getHint() {
        return mHint;
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
        setCompoundDrawables(mDrawableLeft, mDrawableTop,
                             icon, mDrawableBottom);

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
        if (mPopup == null) {
            LayoutInflater inflater = LayoutInflater.from(getContext());
            TextView err = (TextView) inflater.inflate(com.android.internal.R.layout.textview_hint,
                    null);

            mPopup = new PopupWindow(err, 200, 50);
            mPopup.setFocusable(false);
        }

        TextView tv = (TextView) mPopup.getContentView();
        chooseSize(mPopup, mError, tv);
        tv.setText(mError);

        mPopup.showAsDropDown(this, getErrorX(), getErrorY());
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
        
        return getWidth() - mPopup.getWidth()
                - getPaddingRight() - mDrawableSizeRight / 2 + 25;
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

        int icontop = getCompoundPaddingTop() +
                                 (vspace - mDrawableHeightRight) / 2;

        /*
         * The "2" is the distance between the point and the top edge
         * of the background.
         */

        return icontop + mDrawableHeightRight - getHeight() - 2;
    }
    
    private void hideError() {
        if (mPopup != null) {
            if (mPopup.isShowing()) {
                mPopup.dismiss();
            }
        }
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
            mPopup.update(this, getErrorX(), getErrorY(), -1, -1);
        }

        return result;
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
                mHighlightPath.computeBounds(sTempRect, false);

                int left = getCompoundPaddingLeft();
                int top = getExtendedPaddingTop() + getVerticalOffset(true);

                invalidate((int) sTempRect.left + left,
                           (int) sTempRect.top + top,
                           (int) sTempRect.right + left + 1,
                           (int) sTempRect.bottom + top + 1);
            }
        }
    }

    private void invalidateCursor() {
        int where = Selection.getSelectionEnd(mText);

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

        if (mMovement != null) {
            int curs = Selection.getSelectionEnd(mText);

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

        mPreDrawState = PREDRAW_DONE;
        return !changed;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        if (mPreDrawState != PREDRAW_NOT_REGISTERED) {
            final ViewTreeObserver observer = getViewTreeObserver();
            if (observer != null) {
                observer.removeOnPreDrawListener(this);
                mPreDrawState = PREDRAW_NOT_REGISTERED;
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
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

        if (mDrawables) {
            /*
             * Compound, not extended, because the icon is not clipped
             * if the text height is smaller.
             */

            int vspace = bottom - top - compoundPaddingBottom - compoundPaddingTop;
            int hspace = right - left - compoundPaddingRight - compoundPaddingLeft;

            if (mDrawableLeft != null) {
                canvas.save();
                canvas.translate(scrollX + mPaddingLeft,
                                 scrollY + compoundPaddingTop +
                                 (vspace - mDrawableHeightLeft) / 2);
                mDrawableLeft.draw(canvas);
                canvas.restore();
            }

            if (mDrawableRight != null) {
                canvas.save();
                canvas.translate(scrollX + right - left - mPaddingRight - mDrawableSizeRight,
                         scrollY + compoundPaddingTop + (vspace - mDrawableHeightRight) / 2);
                mDrawableRight.draw(canvas);
                canvas.restore();
            }

            if (mDrawableTop != null) {
                canvas.save();
                canvas.translate(scrollX + compoundPaddingLeft + (hspace - mDrawableWidthTop) / 2,
                        scrollY + mPaddingTop);
                mDrawableTop.draw(canvas);
                canvas.restore();
            }

            if (mDrawableBottom != null) {
                canvas.save();
                canvas.translate(scrollX + compoundPaddingLeft +
                        (hspace - mDrawableWidthBottom) / 2,
                         scrollY + bottom - top - mPaddingBottom - mDrawableSizeBottom);
                mDrawableBottom.draw(canvas);
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

        Path highlight = null;

        //  If there is no movement method, then there can be no selection.
        //  Check that first and attempt to skip everything having to do with
        //  the cursor.
        //  XXX This is not strictly true -- a program could set the
        //  selection manually if it really wanted to.
        if (mMovement != null && (isFocused() || isPressed())) {
            int start = Selection.getSelectionStart(mText);
            int end = Selection.getSelectionEnd(mText);

            if (mCursorVisible && start >= 0 && isEnabled()) {
                if (mHighlightPath == null)
                    mHighlightPath = new Path();

                if (start == end) {
                    if ((SystemClock.uptimeMillis() - mShowCursor) % (2 * BLINK)
                        < BLINK) {
                        if (mHighlightPathBogus) {
                            mHighlightPath.reset();
                            mLayout.getCursorPath(start, mHighlightPath, mText);
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
                        mLayout.getSelectionPath(start, end, mHighlightPath);
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

        layout.draw(canvas, highlight, mHighlightPaint, voffsetCursor - voffsetText);

        /*  Comment out until we decide what to do about animations
        if (currentTransformation != null) {
            mTextPaint.setLinearTextOn(isLinearTextOn);
        }
        */

        canvas.restore();
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
        if (!isEnabled()) {
            return super.onKeyDown(keyCode, event);
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (mSingleLine && mInput != null) {
                    return super.onKeyDown(keyCode, event);
                }
        }

        if (mInput != null) {
            /*  
             * Keep track of what the error was before doing the input
             * so that if an input filter changed the error, we leave
             * that error showing.  Otherwise, we take down whatever
             * error was showing when the user types something.
             */
            mErrorWasChanged = false;

            if (mInput.onKeyDown(this, (Editable) mText, keyCode, event)) {
                if (mError != null && !mErrorWasChanged) {
                    setError(null, null);
                }
                return true;
            }
        }

        // bug 650865: sometimes we get a key event before a layout.
        // don't try to move around if we don't know the layout.

        if (mMovement != null && mLayout != null)
            if (mMovement.onKeyDown(this, (Spannable)mText, keyCode, event))
                return true;

        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (!isEnabled()) {
            return super.onKeyUp(keyCode, event);
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (mSingleLine && mInput != null) {
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
                        }
                    }

                    return super.onKeyUp(keyCode, event);
                }
        }

        if (mInput != null)
            if (mInput.onKeyUp(this, (Editable) mText, keyCode, event))
                return true;

        if (mMovement != null && mLayout != null)
            if (mMovement.onKeyUp(this, (Spannable) mText, keyCode, event))
                return true;

        return super.onKeyUp(keyCode, event);
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

        if (mText instanceof Spannable) {
            mLayout = new DynamicLayout(mText, mTransformed, mTextPaint, w,
                    alignment, mSpacingMult,
                    mSpacingAdd, mIncludePad, mEllipsize,
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
                    // Log.e("aaa", "Boring: " + mTransformed);

                    mSavedLayout = (BoringLayout) mLayout;
                } else if (mEllipsize != null && boring.width <= w) {
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
                } else if (mEllipsize != null) {
                    mLayout = new StaticLayout(mTransformed,
                                0, mTransformed.length(),
                                mTextPaint, w, alignment, mSpacingMult,
                                mSpacingAdd, mIncludePad, mEllipsize,
                                ellipsisWidth);
                } else {
                    mLayout = new StaticLayout(mTransformed, mTextPaint,
                            w, alignment, mSpacingMult, mSpacingAdd,
                            mIncludePad);
                    // Log.e("aaa", "Boring but wide: " + mTransformed);
                }
            } else if (mEllipsize != null) {
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

        mHintLayout = null;

        if (mHint != null) {
            if (hintBoring == UNKNOWN_BORING) {
                hintBoring = BoringLayout.isBoring(mHint, mTextPaint,
                                                   mHintBoring);
                if (hintBoring != null) {
                    mHintBoring = hintBoring;
                }
            }

            if (hintBoring != null) {
                if (hintBoring.width <= hintWidth) {
                    if (mSavedHintLayout != null) {
                        mHintLayout = mSavedHintLayout.
                                replaceOrMake(mHint, mTextPaint,
                                hintWidth, alignment, mSpacingMult,
                                mSpacingAdd, hintBoring, mIncludePad);
                    } else {
                        mHintLayout = BoringLayout.make(mHint, mTextPaint,
                                hintWidth, alignment, mSpacingMult,
                                mSpacingAdd, hintBoring, mIncludePad);
                    }

                    mSavedHintLayout = (BoringLayout) mHintLayout;
                } else {
                    mHintLayout = new StaticLayout(mHint, mTextPaint,
                            hintWidth, alignment, mSpacingMult, mSpacingAdd,
                            mIncludePad);
                }
            } else {
                mHintLayout = new StaticLayout(mHint, mTextPaint,
                        hintWidth, alignment, mSpacingMult, mSpacingAdd,
                        mIncludePad);
            }
        }

        if (bringIntoView) {
            registerForPreDraw();
        }
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

    private static final BoringLayout.Metrics UNKNOWN_BORING =
                                                new BoringLayout.Metrics();

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
                boring = BoringLayout.isBoring(mTransformed, mTextPaint,
                                               mBoring);
                if (boring != null) {
                    mBoring = boring;
                }
            } else {
                fromexisting = true;
            }

            if (boring == null || boring == UNKNOWN_BORING) {
                if (des < 0) {
                    des = (int) FloatMath.ceil(Layout.
                                    getDesiredWidth(mTransformed, mTextPaint));
                }

                width = des;
            } else {
                width = boring.width;
            }

            width = Math.max(width, mDrawableWidthTop);
            width = Math.max(width, mDrawableWidthBottom);

            if (mHint != null) {
                int hintDes = -1;
                int hintWidth;

                if (mHintLayout != null) {
                    hintDes = desired(mHintLayout);
                }

                if (hintDes < 0) {
                    hintBoring = BoringLayout.isBoring(mHint, mTextPaint,
                                                       mHintBoring);
                    if (hintBoring != null) {
                        mHintBoring = hintBoring;
                    }
                }

                if (hintBoring == null || hintBoring == UNKNOWN_BORING) {
                    if (hintDes < 0) {
                        hintDes = (int) FloatMath.ceil(Layout.
                                        getDesiredWidth(mHint, mTextPaint));
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
                          width - getCompoundPaddingLeft() - getCompoundPaddingRight(),
                          false);
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
                              width - getCompoundPaddingLeft() - getCompoundPaddingRight(),
                              false);
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
                height = Math.min(desired, height);
            }
        }

        int unpaddedHeight = height - getCompoundPaddingTop() -
                                getCompoundPaddingBottom();
        if (mMaxMode == LINES && mLayout.getLineCount() > mMaximum) {
            unpaddedHeight = Math.min(unpaddedHeight,
                                      mLayout.getLineTop(mMaximum));
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
        return Math.max(getDesiredHeight(mLayout, true),
                        getDesiredHeight(mHintLayout, false));
    }

    private int getDesiredHeight(Layout layout, boolean cap) {
        if (layout == null) {
            return 0;
        }

        int linecount = layout.getLineCount();
        int pad = getCompoundPaddingTop() + getCompoundPaddingBottom();
        int desired = layout.getLineTop(linecount);

        desired = Math.max(desired, mDrawableHeightLeft);
        desired = Math.max(desired, mDrawableHeightRight);

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

                    desired = Math.max(desired, mDrawableHeightLeft);
                    desired = Math.max(desired, mDrawableHeightRight);

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
            } else if (mLayoutParams.height == LayoutParams.FILL_PARENT) {
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
                          mRight - mLeft - getCompoundPaddingLeft() - getCompoundPaddingRight(), false);

            // In a fixed-height view, so use our new text layout.
            if (mLayoutParams.height != LayoutParams.WRAP_CONTENT &&
                mLayoutParams.height != LayoutParams.FILL_PARENT) {
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
     * Returns true if anything changed.
     */
    private boolean bringPointIntoView(int offset) {
        boolean changed = false;

        int line = mLayout.getLineForOffset(offset);

        // FIXME: Is it okay to truncate this, or should we round?
        final int x = (int)mLayout.getPrimaryHorizontal(offset);
        final int top = mLayout.getLineTop(line);
        final int bottom = mLayout.getLineTop(line+1);

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

            Rect r = new Rect();
            getInterestingRect(r, x, top, bottom, line);
            r.offset(mScrollX, mScrollY);

            if (requestRectangleOnScreen(r)) {
                changed = true;
            }
        }

        return changed;
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

    private void getInterestingRect(Rect r, int h, int top, int bottom,
                                    int line) {
        top += getExtendedPaddingTop();
        bottom += getExtendedPaddingTop();
        h += getCompoundPaddingLeft();

        if (line == 0)
            top -= getExtendedPaddingTop();
        if (line == mLayout.getLineCount() - 1)
            bottom += getExtendedPaddingBottom();

        r.set(h, top, h, bottom);
        r.offset(-mScrollX, -mScrollY);
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
    public int getSelectionStart() {
        return Selection.getSelectionStart(getText());
    }

    /**
     * Convenience for {@link Selection#getSelectionEnd}.
     */
    public int getSelectionEnd() {
        return Selection.getSelectionEnd(getText());
    }

    /**
     * Return true iff there is a selection inside this text view.
     */
    public boolean hasSelection() {
        return getSelectionStart() != getSelectionEnd();
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
    public void setSingleLine(boolean singleLine) {
        mSingleLine = singleLine;

        if (singleLine) {
            setLines(1);
            setHorizontallyScrolling(true);
            setTransformationMethod(SingleLineTransformationMethod.
                                    getInstance());
        } else {
            setMaxLines(Integer.MAX_VALUE);
            setHorizontallyScrolling(false);
            setTransformationMethod(null);
        }
    }

    /**
     * Causes words in the text that are longer than the view is wide
     * to be ellipsized instead of broken in the middle.  You may also
     * want to {@link #setSingleLine} or {@link #setHorizontallyScrolling}
     * to constrain the text toa single line.  Use <code>null</code>
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
     * Returns where, if anywhere, words that are longer than the view
     * is wide should be ellipsized.
     */
    public TextUtils.TruncateAt getEllipsize() {
        return mEllipsize;
    }

    /**
     * Set the TextView so that when it takes focus, all the text is
     * selected.
     *
     * @attr ref android.R.styleable#TextView_selectAllOnFocus
     */
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
    public void setCursorVisible(boolean visible) {
        mCursorVisible = visible;
        invalidate();

        if (visible) {
            makeBlink();
        } else if (mBlink != null) {
            mBlink.removeCallbacks(mBlink);
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
     * Adds a TextWatcher to the list of those whose methods are called
     * whenever this TextView's text changes.
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

    private void sendOnTextChanged(CharSequence text, int start, int before,
                                   int after) {
        if (mListeners != null) {
            final ArrayList<TextWatcher> list = mListeners;
            final int count = list.size();
            for (int i = 0; i < count; i++) {
                list.get(i).onTextChanged(text, start, before, after);
            }
        }
    }

    private void sendAfterTextChanged(Editable text) {
        if (mListeners != null) {
            final ArrayList<TextWatcher> list = mListeners;
            final int count = list.size();
            for (int i = 0; i < count; i++) {
                list.get(i).afterTextChanged(text);
            }
        }
    }

    private class ChangeWatcher
    extends Handler
    implements TextWatcher, SpanWatcher {
        public void beforeTextChanged(CharSequence buffer, int start,
                                      int before, int after) {
            TextView.this.sendBeforeTextChanged(buffer, start, before, after);
        }

        public void onTextChanged(CharSequence buffer, int start,
                                  int before, int after) {
            invalidate();

            int curs = Selection.getSelectionStart(buffer);

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

            TextView.this.sendOnTextChanged(buffer, start, before, after);
            TextView.this.onTextChanged(buffer, start, before, after);
        }

        public void afterTextChanged(Editable buffer) {
            TextView.this.sendAfterTextChanged(buffer);
        }

        private void spanChange(Spanned buf, Object what, int o, int n) {
            // XXX Make the start and end move together if this ends up
            // spending too much time invalidating.

            if (what == Selection.SELECTION_END) {
                mHighlightPathBogus = true;

                if (!isFocused()) {
                    mSelectionMoved = true;
                }

                if (o >= 0 || n >= 0) {
                    invalidateCursor(Selection.getSelectionStart(buf), o, n);
                    registerForPreDraw();

                    if (isFocused()) {
                        mShowCursor = SystemClock.uptimeMillis();
                        makeBlink();
                    }
                }
            }

            if (what == Selection.SELECTION_START) {
                mHighlightPathBogus = true;

                if (!isFocused()) {
                    mSelectionMoved = true;
                }

                if (o >= 0 || n >= 0) {
                    invalidateCursor(Selection.getSelectionEnd(buf), o, n);
                }
            }

            if (what instanceof UpdateLayout ||
                what instanceof ParagraphStyle) {
                invalidate();
                mHighlightPathBogus = true;
                checkForResize();
            }

            if (MetaKeyKeyListener.isMetaTracker(buf, what)) {
                mHighlightPathBogus = true;

                if (Selection.getSelectionStart(buf) >= 0) {
                    invalidateCursor();
                }
            }
        }

        public void onSpanChanged(Spannable buf,
                                  Object what, int s, int e, int st, int en) {
            spanChange(buf, what, s, st);
        }

        public void onSpanAdded(Spannable buf, Object what, int s, int e) {
            spanChange(buf, what, -1, s);
        }

        public void onSpanRemoved(Spannable buf, Object what, int s, int e) {
            spanChange(buf, what, s, -1);
        }
    }

    private void makeBlink() {
        if (!mCursorVisible) {
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

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previouslyFocusedRect) {
        mShowCursor = SystemClock.uptimeMillis();

        if (focused) {
            int selStart = getSelectionStart();
            int selEnd = getSelectionEnd();

            if (!mFrozenWithFocus || (selStart < 0 || selEnd < 0)) {
                boolean selMoved = mSelectionMoved;

                if (mMovement != null) {
                    mMovement.onTakeFocus(this, (Spannable) mText, direction);
                }

                if (mSelectAllOnFocus) {
                    Selection.setSelection((Spannable) mText, 0, mText.length());
                }

                if (selMoved && selStart >= 0 && selEnd >= 0) {
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
        }

        if (mTransformation != null) {
            mTransformation.onFocusChanged(this, mText, focused, direction,
                                           previouslyFocusedRect);
        }

        super.onFocusChanged(focused, direction, previouslyFocusedRect);
    }

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
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final boolean superResult = super.onTouchEvent(event);

        /*
         * Don't handle the release after a long press, because it will
         * move the selection away from whatever the menu action was
         * trying to affect.
         */
        if (mEatTouchRelease && event.getAction() == MotionEvent.ACTION_UP) {
            mEatTouchRelease = false;
            return superResult;
        }

        if (mMovement != null && mText instanceof Spannable &&
            mLayout != null) {
            if (mMovement.onTouchEvent(this, (Spannable) mText, event)) {
                return true;
            }
        }

        return superResult;
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

    private static class Blink extends Handler
            implements Runnable {
        private WeakReference<TextView> mView;
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
                int st = Selection.getSelectionStart(tv.mText);
                int en = Selection.getSelectionEnd(tv.mText);

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
            if (canSelectAll()) {
                return onMenu(ID_SELECT_ALL);
            }

            break;

        case KeyEvent.KEYCODE_X:
            if (canCut()) {
                return onMenu(ID_CUT);
            }

            break;

        case KeyEvent.KEYCODE_C:
            if (canCopy()) {
                return onMenu(ID_COPY);
            }

            break;

        case KeyEvent.KEYCODE_V:
            if (canPaste()) {
                return onMenu(ID_PASTE);
            }

            break;
        }

        return super.onKeyShortcut(keyCode, event);
    }

    private boolean canSelectAll() {
        if (mText instanceof Spannable && mText.length() != 0 &&
            mMovement != null && mMovement.canSelectArbitrarily()) {
            return true;
        }

        return false;
    }

    private boolean canCut() {
        if (mText.length() > 0 && getSelectionStart() >= 0) {
            if (mText instanceof Editable && mInput != null) {
                return true;
            }
        }

        return false;
    }

    private boolean canCopy() {
        if (mText.length() > 0 && getSelectionStart() >= 0) {
            return true;
        }

        return false;
    }

    private boolean canPaste() {
        if (mText instanceof Editable && mInput != null &&
            getSelectionStart() >= 0 && getSelectionEnd() >= 0) {
            ClipboardManager clip = (ClipboardManager)getContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            if (clip.hasText()) {
                return true;
            }
        }

        return false;
    }

    @Override
    protected void onCreateContextMenu(ContextMenu menu) {
        super.onCreateContextMenu(menu);

        if (!isFocused()) {
            return;
        }

        MenuHandler handler = new MenuHandler();

        if (canSelectAll()) {
            menu.add(0, ID_SELECT_ALL, 0,
                    com.android.internal.R.string.selectAll).
                setOnMenuItemClickListener(handler).
                setAlphabeticShortcut('a');
        }

        boolean selection = getSelectionStart() != getSelectionEnd();

        if (canCut()) {
            int name;
            if (selection) {
                name = com.android.internal.R.string.cut;
            } else {
                name = com.android.internal.R.string.cutAll;
            }

            menu.add(0, ID_CUT, 0, name).
                setOnMenuItemClickListener(handler).
                setAlphabeticShortcut('x');
        }

        if (canCopy()) {
            int name;
            if (selection) {
                name = com.android.internal.R.string.copy;
            } else {
                name = com.android.internal.R.string.copyAll;
            }

            menu.add(0, ID_COPY, 0, name).
                setOnMenuItemClickListener(handler).
                setAlphabeticShortcut('c');
        }

        if (canPaste()) {
            menu.add(0, ID_PASTE, 0, com.android.internal.R.string.paste).
                    setOnMenuItemClickListener(handler).
                    setAlphabeticShortcut('v');
        }

        if (mText instanceof Spanned) {
            int selStart = getSelectionStart();
            int selEnd = getSelectionEnd();

            int min = Math.min(selStart, selEnd);
            int max = Math.max(selStart, selEnd);

            URLSpan[] urls = ((Spanned) mText).getSpans(min, max,
                                                        URLSpan.class);
            if (urls.length == 1) {
                menu.add(0, ID_COPY_URL, 0,
                         com.android.internal.R.string.copyUrl).
                            setOnMenuItemClickListener(handler);
            }
        }
    }

    private static final int ID_SELECT_ALL = 101;
    private static final int ID_CUT = 102;
    private static final int ID_COPY = 103;
    private static final int ID_PASTE = 104;
    private static final int ID_COPY_URL = 105;

    private class MenuHandler implements MenuItem.OnMenuItemClickListener {
        public boolean onMenuItemClick(MenuItem item) {
            return onMenu(item.getItemId());
        }
    }

    private boolean onMenu(int id) {
        int selStart = getSelectionStart();
        int selEnd = getSelectionEnd();

        int min = Math.min(selStart, selEnd);
        int max = Math.max(selStart, selEnd);

        if (min < 0) {
            min = 0;
        }
        if (max < 0) {
            max = 0;
        }

        ClipboardManager clip = (ClipboardManager)getContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);

        switch (id) {
            case ID_SELECT_ALL:
                Selection.setSelection((Spannable) mText, 0,
                                        mText.length());
                return true;

            case ID_CUT:
                if (min == max) {
                    min = 0;
                    max = mText.length();
                }

                clip.setText(mTransformed.subSequence(min, max));
                ((Editable) mText).delete(min, max);
                return true;

            case ID_COPY:
                if (min == max) {
                    min = 0;
                    max = mText.length();
                }

                clip.setText(mTransformed.subSequence(min, max));
                return true;

            case ID_PASTE:
                CharSequence paste = clip.getText();

                if (paste != null) {
                    Selection.setSelection((Spannable) mText, max);
                    ((Editable) mText).replace(min, max, paste);
                }

                return true;

            case ID_COPY_URL:
                URLSpan[] urls = ((Spanned) mText).getSpans(min, max,
                                                       URLSpan.class);
                if (urls.length == 1) {
                    clip.setText(urls[0].getURL());
                }

                return true;
        }

        return false;
    }

    public boolean performLongClick() {
        if (super.performLongClick()) {
            mEatTouchRelease = true;
            return true;
        }

        return false;
    }

    private boolean mEatTouchRelease = false;

    @ViewDebug.ExportedProperty
    private CharSequence            mText;
    private CharSequence            mTransformed;
    private BufferType              mBufferType = BufferType.NORMAL;

    private CharSequence            mHint;
    private Layout                  mHintLayout;

    private KeyListener             mInput;
    private MovementMethod          mMovement;
    private TransformationMethod    mTransformation;
    private ChangeWatcher           mChangeWatcher;

    private ArrayList<TextWatcher>  mListeners = null;

    // display attributes
    private TextPaint mTextPaint;
    private Paint                   mHighlightPaint;
    private int                     mHighlightColor = 0xFFBBDDFF;
    private Layout                  mLayout;

    private long                    mShowCursor;
    private Blink                   mBlink;
    private boolean                 mCursorVisible = true;

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
    private static RectF            sTempRect = new RectF();

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
