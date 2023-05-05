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

import static android.Manifest.permission.INTERACT_ACROSS_USERS_FULL;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.view.ContentInfo.FLAG_CONVERT_TO_PLAIN_TEXT;
import static android.view.ContentInfo.SOURCE_AUTOFILL;
import static android.view.ContentInfo.SOURCE_CLIPBOARD;
import static android.view.ContentInfo.SOURCE_PROCESS_TEXT;
import static android.view.accessibility.AccessibilityNodeInfo.EXTRA_DATA_RENDERING_INFO_KEY;
import static android.view.accessibility.AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH;
import static android.view.accessibility.AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX;
import static android.view.accessibility.AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY;
import static android.view.inputmethod.CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION;

import android.R;
import android.annotation.CallSuper;
import android.annotation.CheckResult;
import android.annotation.ColorInt;
import android.annotation.DrawableRes;
import android.annotation.FloatRange;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.Px;
import android.annotation.RequiresPermission;
import android.annotation.Size;
import android.annotation.StringRes;
import android.annotation.StyleRes;
import android.annotation.TestApi;
import android.annotation.XmlRes;
import android.app.Activity;
import android.app.PendingIntent;
import android.app.assist.AssistStructure;
import android.app.compat.CompatChanges;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.UndoManager;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.content.res.FontScaleConverterFactory;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.graphics.BaseCanvas;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Paint.FontMetricsInt;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.fonts.FontStyle;
import android.graphics.fonts.FontVariationAxis;
import android.graphics.text.LineBreakConfig;
import android.icu.text.DecimalFormatSymbols;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.LocaleList;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.ParcelableParcel;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.BoringLayout;
import android.text.DynamicLayout;
import android.text.Editable;
import android.text.GetChars;
import android.text.GraphemeClusterSegmentFinder;
import android.text.GraphicsOperations;
import android.text.Highlights;
import android.text.InputFilter;
import android.text.InputType;
import android.text.Layout;
import android.text.NoCopySpan;
import android.text.ParcelableSpan;
import android.text.PrecomputedText;
import android.text.SegmentFinder;
import android.text.Selection;
import android.text.SpanWatcher;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.SpannedString;
import android.text.StaticLayout;
import android.text.TextDirectionHeuristic;
import android.text.TextDirectionHeuristics;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.TextUtils.TruncateAt;
import android.text.TextWatcher;
import android.text.WordSegmentFinder;
import android.text.method.AllCapsTransformationMethod;
import android.text.method.ArrowKeyMovementMethod;
import android.text.method.DateKeyListener;
import android.text.method.DateTimeKeyListener;
import android.text.method.DialerKeyListener;
import android.text.method.DigitsKeyListener;
import android.text.method.KeyListener;
import android.text.method.LinkMovementMethod;
import android.text.method.MetaKeyKeyListener;
import android.text.method.MovementMethod;
import android.text.method.OffsetMapping;
import android.text.method.PasswordTransformationMethod;
import android.text.method.SingleLineTransformationMethod;
import android.text.method.TextKeyListener;
import android.text.method.TimeKeyListener;
import android.text.method.TransformationMethod;
import android.text.method.TransformationMethod2;
import android.text.method.WordIterator;
import android.text.style.CharacterStyle;
import android.text.style.ClickableSpan;
import android.text.style.ParagraphStyle;
import android.text.style.SpellCheckSpan;
import android.text.style.SuggestionSpan;
import android.text.style.URLSpan;
import android.text.style.UpdateAppearance;
import android.text.util.Linkify;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.FeatureFlagUtils;
import android.util.IntArray;
import android.util.Log;
import android.util.SparseIntArray;
import android.util.TypedValue;
import android.view.AccessibilityIterators.TextSegmentIterator;
import android.view.ActionMode;
import android.view.Choreographer;
import android.view.ContentInfo;
import android.view.ContextMenu;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewDebug;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewHierarchyEncoder;
import android.view.ViewParent;
import android.view.ViewRootImpl;
import android.view.ViewStructure;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.AnimationUtils;
import android.view.autofill.AutofillManager;
import android.view.autofill.AutofillValue;
import android.view.contentcapture.ContentCaptureManager;
import android.view.contentcapture.ContentCaptureSession;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.CorrectionInfo;
import android.view.inputmethod.CursorAnchorInfo;
import android.view.inputmethod.DeleteGesture;
import android.view.inputmethod.DeleteRangeGesture;
import android.view.inputmethod.EditorBoundsInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.HandwritingGesture;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InsertGesture;
import android.view.inputmethod.InsertModeGesture;
import android.view.inputmethod.JoinOrSplitGesture;
import android.view.inputmethod.PreviewableHandwritingGesture;
import android.view.inputmethod.RemoveSpaceGesture;
import android.view.inputmethod.SelectGesture;
import android.view.inputmethod.SelectRangeGesture;
import android.view.inputmethod.TextAppearanceInfo;
import android.view.inputmethod.TextBoundsInfo;
import android.view.inspector.InspectableProperty;
import android.view.inspector.InspectableProperty.EnumEntry;
import android.view.inspector.InspectableProperty.FlagEntry;
import android.view.textclassifier.TextClassification;
import android.view.textclassifier.TextClassificationContext;
import android.view.textclassifier.TextClassificationManager;
import android.view.textclassifier.TextClassifier;
import android.view.textclassifier.TextLinks;
import android.view.textservice.SpellCheckerSubtype;
import android.view.textservice.TextServicesManager;
import android.view.translation.TranslationRequestValue;
import android.view.translation.TranslationSpec;
import android.view.translation.UiTranslationController;
import android.view.translation.ViewTranslationCallback;
import android.view.translation.ViewTranslationRequest;
import android.widget.RemoteViews.RemoteView;

import com.android.internal.accessibility.util.AccessibilityUtils;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.graphics.ColorUtils;
import com.android.internal.inputmethod.EditableInputConnection;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FastMath;
import com.android.internal.util.Preconditions;

import libcore.util.EmptyArray;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A user interface element that displays text to the user.
 * To provide user-editable text, see {@link EditText}.
 * <p>
 * The following code sample shows a typical use, with an XML layout
 * and code to modify the contents of the text view:
 * </p>

 * <pre>
 * &lt;LinearLayout
       xmlns:android="http://schemas.android.com/apk/res/android"
       android:layout_width="match_parent"
       android:layout_height="match_parent"&gt;
 *    &lt;TextView
 *        android:id="@+id/text_view_id"
 *        android:layout_height="wrap_content"
 *        android:layout_width="wrap_content"
 *        android:text="@string/hello" /&gt;
 * &lt;/LinearLayout&gt;
 * </pre>
 * <p>
 * This code sample demonstrates how to modify the contents of the text view
 * defined in the previous XML layout:
 * </p>
 * <pre>
 * public class MainActivity extends Activity {
 *
 *    protected void onCreate(Bundle savedInstanceState) {
 *         super.onCreate(savedInstanceState);
 *         setContentView(R.layout.activity_main);
 *         final TextView helloTextView = (TextView) findViewById(R.id.text_view_id);
 *         helloTextView.setText(R.string.user_greeting);
 *     }
 * }
 * </pre>
 * <p>
 * To customize the appearance of TextView, see <a href="https://developer.android.com/guide/topics/ui/themes.html">Styles and Themes</a>.
 * </p>
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
 * @attr ref android.R.styleable#TextView_textFontWeight
 * @attr ref android.R.styleable#TextView_textSize
 * @attr ref android.R.styleable#TextView_textScaleX
 * @attr ref android.R.styleable#TextView_fontFamily
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
 * @attr ref android.R.styleable#TextView_drawableStart
 * @attr ref android.R.styleable#TextView_drawableEnd
 * @attr ref android.R.styleable#TextView_drawablePadding
 * @attr ref android.R.styleable#TextView_drawableTint
 * @attr ref android.R.styleable#TextView_drawableTintMode
 * @attr ref android.R.styleable#TextView_lineSpacingExtra
 * @attr ref android.R.styleable#TextView_lineSpacingMultiplier
 * @attr ref android.R.styleable#TextView_justificationMode
 * @attr ref android.R.styleable#TextView_marqueeRepeatLimit
 * @attr ref android.R.styleable#TextView_inputType
 * @attr ref android.R.styleable#TextView_imeOptions
 * @attr ref android.R.styleable#TextView_privateImeOptions
 * @attr ref android.R.styleable#TextView_imeActionLabel
 * @attr ref android.R.styleable#TextView_imeActionId
 * @attr ref android.R.styleable#TextView_editorExtras
 * @attr ref android.R.styleable#TextView_elegantTextHeight
 * @attr ref android.R.styleable#TextView_fallbackLineSpacing
 * @attr ref android.R.styleable#TextView_letterSpacing
 * @attr ref android.R.styleable#TextView_fontFeatureSettings
 * @attr ref android.R.styleable#TextView_fontVariationSettings
 * @attr ref android.R.styleable#TextView_breakStrategy
 * @attr ref android.R.styleable#TextView_hyphenationFrequency
 * @attr ref android.R.styleable#TextView_lineBreakStyle
 * @attr ref android.R.styleable#TextView_lineBreakWordStyle
 * @attr ref android.R.styleable#TextView_autoSizeTextType
 * @attr ref android.R.styleable#TextView_autoSizeMinTextSize
 * @attr ref android.R.styleable#TextView_autoSizeMaxTextSize
 * @attr ref android.R.styleable#TextView_autoSizeStepGranularity
 * @attr ref android.R.styleable#TextView_autoSizePresetSizes
 * @attr ref android.R.styleable#TextView_textCursorDrawable
 * @attr ref android.R.styleable#TextView_textSelectHandle
 * @attr ref android.R.styleable#TextView_textSelectHandleLeft
 * @attr ref android.R.styleable#TextView_textSelectHandleRight
 * @attr ref android.R.styleable#TextView_allowUndo
 * @attr ref android.R.styleable#TextView_enabled
 */
@RemoteView
public class TextView extends View implements ViewTreeObserver.OnPreDrawListener {
    static final String LOG_TAG = "TextView";
    static final boolean DEBUG_EXTRACT = false;
    static final boolean DEBUG_CURSOR = false;

    private static final float[] TEMP_POSITION = new float[2];

    // Enum for the "typeface" XML parameter.
    // TODO: How can we get this from the XML instead of hardcoding it here?
    /** @hide */
    @IntDef(value = {DEFAULT_TYPEFACE, SANS, SERIF, MONOSPACE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface XMLTypefaceAttr{}
    private static final int DEFAULT_TYPEFACE = -1;
    private static final int SANS = 1;
    private static final int SERIF = 2;
    private static final int MONOSPACE = 3;

    // Enum for the "ellipsize" XML parameter.
    private static final int ELLIPSIZE_NOT_SET = -1;
    private static final int ELLIPSIZE_NONE = 0;
    private static final int ELLIPSIZE_START = 1;
    private static final int ELLIPSIZE_MIDDLE = 2;
    private static final int ELLIPSIZE_END = 3;
    private static final int ELLIPSIZE_MARQUEE = 4;

    // Bitfield for the "numeric" XML parameter.
    // TODO: How can we get this from the XML instead of hardcoding it here?
    private static final int SIGNED = 2;
    private static final int DECIMAL = 4;

    /**
     * Draw marquee text with fading edges as usual
     */
    private static final int MARQUEE_FADE_NORMAL = 0;

    /**
     * Draw marquee text as ellipsize end while inactive instead of with the fade.
     * (Useful for devices where the fade can be expensive if overdone)
     */
    private static final int MARQUEE_FADE_SWITCH_SHOW_ELLIPSIS = 1;

    /**
     * Draw marquee text with fading edges because it is currently active/animating.
     */
    private static final int MARQUEE_FADE_SWITCH_SHOW_FADE = 2;

    @UnsupportedAppUsage
    private static final int LINES = 1;
    private static final int EMS = LINES;
    private static final int PIXELS = 2;

    // Maximum text length for single line input.
    private static final int MAX_LENGTH_FOR_SINGLE_LINE_EDIT_TEXT = 5000;
    private InputFilter.LengthFilter mSingleLineLengthFilter = null;

    private static final RectF TEMP_RECTF = new RectF();

    /** @hide */
    static final int VERY_WIDE = 1024 * 1024; // XXX should be much larger
    private static final int ANIMATED_SCROLL_GAP = 250;

    private static final InputFilter[] NO_FILTERS = new InputFilter[0];
    private static final Spanned EMPTY_SPANNED = new SpannedString("");

    private static final int CHANGE_WATCHER_PRIORITY = 100;

    /**
     * The span priority of the {@link OffsetMapping} that is set on the text. It must be
     * higher than the {@link DynamicLayout}'s {@link TextWatcher}, so that the transformed text is
     * updated before {@link DynamicLayout#reflow(CharSequence, int, int, int)} being triggered
     * by {@link TextWatcher#onTextChanged(CharSequence, int, int, int)}.
     */
    private static final int OFFSET_MAPPING_SPAN_PRIORITY = 200;

    // New state used to change background based on whether this TextView is multiline.
    private static final int[] MULTILINE_STATE_SET = { R.attr.state_multiline };

    // Accessibility action to share selected text.
    private static final int ACCESSIBILITY_ACTION_SHARE = 0x10000000;

    /**
     * @hide
     */
    // Accessibility action start id for "process text" actions.
    static final int ACCESSIBILITY_ACTION_PROCESS_TEXT_START_ID = 0x10000100;

    /** Accessibility action start id for "smart" actions. @hide */
    static final int ACCESSIBILITY_ACTION_SMART_START_ID = 0x10001000;

    /**
     * @hide
     */
    @TestApi
    public static final int PROCESS_TEXT_REQUEST_CODE = 100;

    /**
     *  Return code of {@link #doKeyDown}.
     */
    private static final int KEY_EVENT_NOT_HANDLED = 0;
    private static final int KEY_EVENT_HANDLED = -1;
    private static final int KEY_DOWN_HANDLED_BY_KEY_LISTENER = 1;
    private static final int KEY_DOWN_HANDLED_BY_MOVEMENT_METHOD = 2;

    private static final int FLOATING_TOOLBAR_SELECT_ALL_REFRESH_DELAY = 500;

    // The default value of the line break style.
    private static final int DEFAULT_LINE_BREAK_STYLE = LineBreakConfig.LINE_BREAK_STYLE_NONE;

    // The default value of the line break word style.
    private static final int DEFAULT_LINE_BREAK_WORD_STYLE =
            LineBreakConfig.LINE_BREAK_WORD_STYLE_NONE;

    /**
     * This change ID enables the fallback text line spacing (line height) for BoringLayout.
     * @hide
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.TIRAMISU)
    public static final long BORINGLAYOUT_FALLBACK_LINESPACING = 210923482L; // buganizer id

    /**
     * This change ID enables the fallback text line spacing (line height) for StaticLayout.
     * @hide
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.P)
    public static final long STATICLAYOUT_FALLBACK_LINESPACING = 37756858; // buganizer id

    // System wide time for last cut, copy or text changed action.
    static long sLastCutCopyOrTextChangedTime;

    private ColorStateList mTextColor;
    private ColorStateList mHintTextColor;
    private ColorStateList mLinkTextColor;
    @ViewDebug.ExportedProperty(category = "text")

    /**
     * {@link #setTextColor(int)} or {@link #getCurrentTextColor()} should be used instead.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    private int mCurTextColor;

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private int mCurHintTextColor;
    private boolean mFreezesText;

    @UnsupportedAppUsage
    private Editable.Factory mEditableFactory = Editable.Factory.getInstance();
    @UnsupportedAppUsage
    private Spannable.Factory mSpannableFactory = Spannable.Factory.getInstance();

    @UnsupportedAppUsage
    private float mShadowRadius;
    @UnsupportedAppUsage
    private float mShadowDx;
    @UnsupportedAppUsage
    private float mShadowDy;
    private int mShadowColor;

    private boolean mPreDrawRegistered;
    private boolean mPreDrawListenerDetached;

    private TextClassifier mTextClassifier;
    private TextClassifier mTextClassificationSession;
    private TextClassificationContext mTextClassificationContext;

    // A flag to prevent repeated movements from escaping the enclosing text view. The idea here is
    // that if a user is holding down a movement key to traverse text, we shouldn't also traverse
    // the view hierarchy. On the other hand, if the user is using the movement key to traverse
    // views (i.e. the first movement was to traverse out of this view, or this view was traversed
    // into by the user holding the movement key down) then we shouldn't prevent the focus from
    // changing.
    private boolean mPreventDefaultMovement;

    private TextUtils.TruncateAt mEllipsize;

    // A flag to indicate the cursor was hidden by IME.
    private boolean mImeIsConsumingInput;

    // Whether cursor is visible without regard to {@link mImeConsumesInput}.
    // {@code true} is the default value.
    private boolean mCursorVisibleFromAttr = true;

    static class Drawables {
        static final int LEFT = 0;
        static final int TOP = 1;
        static final int RIGHT = 2;
        static final int BOTTOM = 3;

        static final int DRAWABLE_NONE = -1;
        static final int DRAWABLE_RIGHT = 0;
        static final int DRAWABLE_LEFT = 1;

        final Rect mCompoundRect = new Rect();

        final Drawable[] mShowing = new Drawable[4];

        ColorStateList mTintList;
        BlendMode mBlendMode;
        boolean mHasTint;
        boolean mHasTintMode;

        Drawable mDrawableStart, mDrawableEnd, mDrawableError, mDrawableTemp;
        Drawable mDrawableLeftInitial, mDrawableRightInitial;

        boolean mIsRtlCompatibilityMode;
        boolean mOverride;

        int mDrawableSizeTop, mDrawableSizeBottom, mDrawableSizeLeft, mDrawableSizeRight,
                mDrawableSizeStart, mDrawableSizeEnd, mDrawableSizeError, mDrawableSizeTemp;

        int mDrawableWidthTop, mDrawableWidthBottom, mDrawableHeightLeft, mDrawableHeightRight,
                mDrawableHeightStart, mDrawableHeightEnd, mDrawableHeightError, mDrawableHeightTemp;

        int mDrawablePadding;

        int mDrawableSaved = DRAWABLE_NONE;

        public Drawables(Context context) {
            final int targetSdkVersion = context.getApplicationInfo().targetSdkVersion;
            mIsRtlCompatibilityMode = targetSdkVersion < VERSION_CODES.JELLY_BEAN_MR1
                    || !context.getApplicationInfo().hasRtlSupport();
            mOverride = false;
        }

        /**
         * @return {@code true} if this object contains metadata that needs to
         *         be retained, {@code false} otherwise
         */
        public boolean hasMetadata() {
            return mDrawablePadding != 0 || mHasTintMode || mHasTint;
        }

        /**
         * Updates the list of displayed drawables to account for the current
         * layout direction.
         *
         * @param layoutDirection the current layout direction
         * @return {@code true} if the displayed drawables changed
         */
        public boolean resolveWithLayoutDirection(int layoutDirection) {
            final Drawable previousLeft = mShowing[Drawables.LEFT];
            final Drawable previousRight = mShowing[Drawables.RIGHT];

            // First reset "left" and "right" drawables to their initial values
            mShowing[Drawables.LEFT] = mDrawableLeftInitial;
            mShowing[Drawables.RIGHT] = mDrawableRightInitial;

            if (mIsRtlCompatibilityMode) {
                // Use "start" drawable as "left" drawable if the "left" drawable was not defined
                if (mDrawableStart != null && mShowing[Drawables.LEFT] == null) {
                    mShowing[Drawables.LEFT] = mDrawableStart;
                    mDrawableSizeLeft = mDrawableSizeStart;
                    mDrawableHeightLeft = mDrawableHeightStart;
                }
                // Use "end" drawable as "right" drawable if the "right" drawable was not defined
                if (mDrawableEnd != null && mShowing[Drawables.RIGHT] == null) {
                    mShowing[Drawables.RIGHT] = mDrawableEnd;
                    mDrawableSizeRight = mDrawableSizeEnd;
                    mDrawableHeightRight = mDrawableHeightEnd;
                }
            } else {
                // JB-MR1+ normal case: "start" / "end" drawables are overriding "left" / "right"
                // drawable if and only if they have been defined
                switch(layoutDirection) {
                    case LAYOUT_DIRECTION_RTL:
                        if (mOverride) {
                            mShowing[Drawables.RIGHT] = mDrawableStart;
                            mDrawableSizeRight = mDrawableSizeStart;
                            mDrawableHeightRight = mDrawableHeightStart;

                            mShowing[Drawables.LEFT] = mDrawableEnd;
                            mDrawableSizeLeft = mDrawableSizeEnd;
                            mDrawableHeightLeft = mDrawableHeightEnd;
                        }
                        break;

                    case LAYOUT_DIRECTION_LTR:
                    default:
                        if (mOverride) {
                            mShowing[Drawables.LEFT] = mDrawableStart;
                            mDrawableSizeLeft = mDrawableSizeStart;
                            mDrawableHeightLeft = mDrawableHeightStart;

                            mShowing[Drawables.RIGHT] = mDrawableEnd;
                            mDrawableSizeRight = mDrawableSizeEnd;
                            mDrawableHeightRight = mDrawableHeightEnd;
                        }
                        break;
                }
            }

            applyErrorDrawableIfNeeded(layoutDirection);

            return mShowing[Drawables.LEFT] != previousLeft
                    || mShowing[Drawables.RIGHT] != previousRight;
        }

        public void setErrorDrawable(Drawable dr, TextView tv) {
            if (mDrawableError != dr && mDrawableError != null) {
                mDrawableError.setCallback(null);
            }
            mDrawableError = dr;

            if (mDrawableError != null) {
                final Rect compoundRect = mCompoundRect;
                final int[] state = tv.getDrawableState();

                mDrawableError.setState(state);
                mDrawableError.copyBounds(compoundRect);
                mDrawableError.setCallback(tv);
                mDrawableSizeError = compoundRect.width();
                mDrawableHeightError = compoundRect.height();
            } else {
                mDrawableSizeError = mDrawableHeightError = 0;
            }
        }

        private void applyErrorDrawableIfNeeded(int layoutDirection) {
            // first restore the initial state if needed
            switch (mDrawableSaved) {
                case DRAWABLE_LEFT:
                    mShowing[Drawables.LEFT] = mDrawableTemp;
                    mDrawableSizeLeft = mDrawableSizeTemp;
                    mDrawableHeightLeft = mDrawableHeightTemp;
                    break;
                case DRAWABLE_RIGHT:
                    mShowing[Drawables.RIGHT] = mDrawableTemp;
                    mDrawableSizeRight = mDrawableSizeTemp;
                    mDrawableHeightRight = mDrawableHeightTemp;
                    break;
                case DRAWABLE_NONE:
                default:
            }
            // then, if needed, assign the Error drawable to the correct location
            if (mDrawableError != null) {
                switch(layoutDirection) {
                    case LAYOUT_DIRECTION_RTL:
                        mDrawableSaved = DRAWABLE_LEFT;

                        mDrawableTemp = mShowing[Drawables.LEFT];
                        mDrawableSizeTemp = mDrawableSizeLeft;
                        mDrawableHeightTemp = mDrawableHeightLeft;

                        mShowing[Drawables.LEFT] = mDrawableError;
                        mDrawableSizeLeft = mDrawableSizeError;
                        mDrawableHeightLeft = mDrawableHeightError;
                        break;
                    case LAYOUT_DIRECTION_LTR:
                    default:
                        mDrawableSaved = DRAWABLE_RIGHT;

                        mDrawableTemp = mShowing[Drawables.RIGHT];
                        mDrawableSizeTemp = mDrawableSizeRight;
                        mDrawableHeightTemp = mDrawableHeightRight;

                        mShowing[Drawables.RIGHT] = mDrawableError;
                        mDrawableSizeRight = mDrawableSizeError;
                        mDrawableHeightRight = mDrawableHeightError;
                        break;
                }
            }
        }
    }

    @UnsupportedAppUsage
    Drawables mDrawables;

    @UnsupportedAppUsage
    private CharWrapper mCharWrapper;

    @UnsupportedAppUsage(trackingBug = 124050217)
    private Marquee mMarquee;
    @UnsupportedAppUsage
    private boolean mRestartMarquee;

    private int mMarqueeRepeatLimit = 3;

    private int mLastLayoutDirection = -1;

    /**
     * On some devices the fading edges add a performance penalty if used
     * extensively in the same layout. This mode indicates how the marquee
     * is currently being shown, if applicable. (mEllipsize will == MARQUEE)
     */
    @UnsupportedAppUsage
    private int mMarqueeFadeMode = MARQUEE_FADE_NORMAL;

    /**
     * When mMarqueeFadeMode is not MARQUEE_FADE_NORMAL, this stores
     * the layout that should be used when the mode switches.
     */
    @UnsupportedAppUsage
    private Layout mSavedMarqueeModeLayout;

    // Do not update following mText/mSpannable/mPrecomputed except for setTextInternal()
    @ViewDebug.ExportedProperty(category = "text")
    @UnsupportedAppUsage
    private @Nullable CharSequence mText;
    private @Nullable Spannable mSpannable;
    private @Nullable PrecomputedText mPrecomputed;

    @UnsupportedAppUsage
    private CharSequence mTransformed;
    @UnsupportedAppUsage
    private BufferType mBufferType = BufferType.NORMAL;

    private CharSequence mHint;
    @UnsupportedAppUsage
    private Layout mHintLayout;
    private boolean mHideHint;

    private MovementMethod mMovement;

    private TransformationMethod mTransformation;
    @UnsupportedAppUsage
    private boolean mAllowTransformationLengthChange;
    @UnsupportedAppUsage
    private ChangeWatcher mChangeWatcher;

    @UnsupportedAppUsage(trackingBug = 123769451)
    private ArrayList<TextWatcher> mListeners;

    // display attributes
    @UnsupportedAppUsage
    private final TextPaint mTextPaint;
    @UnsupportedAppUsage
    private boolean mUserSetTextScaleX;
    @UnsupportedAppUsage
    private Layout mLayout;
    private boolean mLocalesChanged = false;
    private int mTextSizeUnit = -1;
    private int mLineBreakStyle = DEFAULT_LINE_BREAK_STYLE;
    private int mLineBreakWordStyle = DEFAULT_LINE_BREAK_WORD_STYLE;

    // The auto option for LINE_BREAK_WORD_STYLE_PHRASE may not be applied in recycled view due to
    // one-way flag flipping. This is a tentative limitation during experiment and will not have the
    // issue once this is finalized to LINE_BREAK_WORD_STYLE_PHRASE_AUTO option.
    private boolean mUserSpeficiedLineBreakwordStyle = false;

    // This is used to reflect the current user preference for changing font weight and making text
    // more bold.
    private int mFontWeightAdjustment;
    private Typeface mOriginalTypeface;

    // True if setKeyListener() has been explicitly called
    private boolean mListenerChanged = false;
    // True if internationalized input should be used for numbers and date and time.
    private final boolean mUseInternationalizedInput;

    // Fallback fonts that end up getting used should be allowed to affect line spacing.
    private static final int FALLBACK_LINE_SPACING_NONE = 0;
    private static final int FALLBACK_LINE_SPACING_STATIC_LAYOUT_ONLY = 1;
    private static final int FALLBACK_LINE_SPACING_ALL = 2;

    private int mUseFallbackLineSpacing;
    // True if the view text can be padded for compat reasons, when the view is translated.
    private final boolean mUseTextPaddingForUiTranslation;

    @ViewDebug.ExportedProperty(category = "text")
    @UnsupportedAppUsage
    private int mGravity = Gravity.TOP | Gravity.START;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    private boolean mHorizontallyScrolling;

    private int mAutoLinkMask;
    private boolean mLinksClickable = true;

    @UnsupportedAppUsage
    private float mSpacingMult = 1.0f;
    @UnsupportedAppUsage
    private float mSpacingAdd = 0.0f;

    /**
     * Remembers what line height was set to originally, before we broke it down into raw pixels.
     *
     * <p>This is stored as a complex dimension with both value and unit packed into one field!
     * {@see TypedValue}
     */
    private int mLineHeightComplexDimen;

    private int mBreakStrategy;
    private int mHyphenationFrequency;
    private int mJustificationMode;

    @UnsupportedAppUsage
    private int mMaximum = Integer.MAX_VALUE;
    @UnsupportedAppUsage
    private int mMaxMode = LINES;
    @UnsupportedAppUsage
    private int mMinimum = 0;
    @UnsupportedAppUsage
    private int mMinMode = LINES;

    @UnsupportedAppUsage
    private int mOldMaximum = mMaximum;
    @UnsupportedAppUsage
    private int mOldMaxMode = mMaxMode;

    @UnsupportedAppUsage
    private int mMaxWidth = Integer.MAX_VALUE;
    @UnsupportedAppUsage
    private int mMaxWidthMode = PIXELS;
    @UnsupportedAppUsage
    private int mMinWidth = 0;
    @UnsupportedAppUsage
    private int mMinWidthMode = PIXELS;

    @UnsupportedAppUsage
    private boolean mSingleLine;
    @UnsupportedAppUsage
    private int mDesiredHeightAtMeasure = -1;
    @UnsupportedAppUsage
    private boolean mIncludePad = true;
    private int mDeferScroll = -1;

    // tmp primitives, so we don't alloc them on each draw
    private Rect mTempRect;
    private long mLastScroll;
    private Scroller mScroller;
    private TextPaint mTempTextPaint;

    private Object mTempCursor;

    @UnsupportedAppUsage
    private BoringLayout.Metrics mBoring;
    @UnsupportedAppUsage
    private BoringLayout.Metrics mHintBoring;
    @UnsupportedAppUsage
    private BoringLayout mSavedLayout;
    @UnsupportedAppUsage
    private BoringLayout mSavedHintLayout;

    @UnsupportedAppUsage
    private TextDirectionHeuristic mTextDir;

    private InputFilter[] mFilters = NO_FILTERS;

    /**
     * {@link UserHandle} that represents the logical owner of the text. {@code null} when it is
     * the same as {@link Process#myUserHandle()}.
     *
     * <p>Most of applications should not worry about this. Some privileged apps that host UI for
     * other apps may need to set this so that the system can use right user's resources and
     * services such as input methods and spell checkers.</p>
     *
     * @see #setTextOperationUser(UserHandle)
     */
    @Nullable
    private UserHandle mTextOperationUser;

    private volatile Locale mCurrentSpellCheckerLocaleCache;

    // It is possible to have a selection even when mEditor is null (programmatically set, like when
    // a link is pressed). These highlight-related fields do not go in mEditor.
    @UnsupportedAppUsage
    int mHighlightColor = 0x6633B5E5;
    private Path mHighlightPath;
    @UnsupportedAppUsage
    private final Paint mHighlightPaint;
    @UnsupportedAppUsage
    private boolean mHighlightPathBogus = true;

    private List<Path> mHighlightPaths;
    private List<Paint> mHighlightPaints;
    private Highlights mHighlights;
    private int[] mSearchResultHighlights = null;
    private Paint mSearchResultHighlightPaint = null;
    private Paint mFocusedSearchResultHighlightPaint = null;
    private int mFocusedSearchResultHighlightColor = 0xFFFF9632;
    private int mSearchResultHighlightColor = 0xFFFFFF00;

    private int mFocusedSearchResultIndex = -1;
    private int mGesturePreviewHighlightStart = -1;
    private int mGesturePreviewHighlightEnd = -1;
    private Paint mGesturePreviewHighlightPaint;
    private final List<Path> mPathRecyclePool = new ArrayList<>();
    private boolean mHighlightPathsBogus = true;

    // Although these fields are specific to editable text, they are not added to Editor because
    // they are defined by the TextView's style and are theme-dependent.
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    int mCursorDrawableRes;
    private Drawable mCursorDrawable;
    // Note: this might be stale if setTextSelectHandleLeft is used. We could simplify the code
    // by removing it, but we would break apps targeting <= P that use it by reflection.
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    int mTextSelectHandleLeftRes;
    private Drawable mTextSelectHandleLeft;
    // Note: this might be stale if setTextSelectHandleRight is used. We could simplify the code
    // by removing it, but we would break apps targeting <= P that use it by reflection.
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    int mTextSelectHandleRightRes;
    private Drawable mTextSelectHandleRight;
    // Note: this might be stale if setTextSelectHandle is used. We could simplify the code
    // by removing it, but we would break apps targeting <= P that use it by reflection.
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    int mTextSelectHandleRes;
    private Drawable mTextSelectHandle;
    int mTextEditSuggestionItemLayout;
    int mTextEditSuggestionContainerLayout;
    int mTextEditSuggestionHighlightStyle;

    private static final int NO_POINTER_ID = -1;
    /**
     * The prime (the 1st finger) pointer id which is used as a lock to prevent multi touch among
     * TextView and the handle views which are rendered on popup windows.
     */
    private int mPrimePointerId = NO_POINTER_ID;

    /**
     * Whether the prime pointer is from the event delivered to selection handle or insertion
     * handle.
     */
    private boolean mIsPrimePointerFromHandleView;

    /**
     * {@link EditText} specific data, created on demand when one of the Editor fields is used.
     * See {@link #createEditorIfNeeded()}.
     */
    @UnsupportedAppUsage
    private Editor mEditor;

    private static final int DEVICE_PROVISIONED_UNKNOWN = 0;
    private static final int DEVICE_PROVISIONED_NO = 1;
    private static final int DEVICE_PROVISIONED_YES = 2;

    /**
     * Some special options such as sharing selected text should only be shown if the device
     * is provisioned. Only check the provisioned state once for a given view instance.
     */
    private int mDeviceProvisionedState = DEVICE_PROVISIONED_UNKNOWN;

    /**
     * The last input source on this TextView.
     *
     * Use the SOURCE_TOUCHSCREEN as the default value for backward compatibility. There could be a
     * non UI event originated ActionMode initiation, e.g. API call, a11y events, etc.
     */
    private int mLastInputSource = InputDevice.SOURCE_TOUCHSCREEN;

    /**
     * The TextView does not auto-size text (default).
     */
    public static final int AUTO_SIZE_TEXT_TYPE_NONE = 0;

    /**
     * The TextView scales text size both horizontally and vertically to fit within the
     * container.
     */
    public static final int AUTO_SIZE_TEXT_TYPE_UNIFORM = 1;

    /** @hide */
    @IntDef(prefix = { "AUTO_SIZE_TEXT_TYPE_" }, value = {
            AUTO_SIZE_TEXT_TYPE_NONE,
            AUTO_SIZE_TEXT_TYPE_UNIFORM
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AutoSizeTextType {}
    // Default minimum size for auto-sizing text in scaled pixels.
    private static final int DEFAULT_AUTO_SIZE_MIN_TEXT_SIZE_IN_SP = 12;
    // Default maximum size for auto-sizing text in scaled pixels.
    private static final int DEFAULT_AUTO_SIZE_MAX_TEXT_SIZE_IN_SP = 112;
    // Default value for the step size in pixels.
    private static final int DEFAULT_AUTO_SIZE_GRANULARITY_IN_PX = 1;
    // Use this to specify that any of the auto-size configuration int values have not been set.
    private static final float UNSET_AUTO_SIZE_UNIFORM_CONFIGURATION_VALUE = -1f;
    // Auto-size text type.
    private int mAutoSizeTextType = AUTO_SIZE_TEXT_TYPE_NONE;
    // Specify if auto-size text is needed.
    private boolean mNeedsAutoSizeText = false;
    // Step size for auto-sizing in pixels.
    private float mAutoSizeStepGranularityInPx = UNSET_AUTO_SIZE_UNIFORM_CONFIGURATION_VALUE;
    // Minimum text size for auto-sizing in pixels.
    private float mAutoSizeMinTextSizeInPx = UNSET_AUTO_SIZE_UNIFORM_CONFIGURATION_VALUE;
    // Maximum text size for auto-sizing in pixels.
    private float mAutoSizeMaxTextSizeInPx = UNSET_AUTO_SIZE_UNIFORM_CONFIGURATION_VALUE;
    // Contains a (specified or computed) distinct sorted set of text sizes in pixels to pick from
    // when auto-sizing text.
    private int[] mAutoSizeTextSizesInPx = EmptyArray.INT;
    // Specifies whether auto-size should use the provided auto size steps set or if it should
    // build the steps set using mAutoSizeMinTextSizeInPx, mAutoSizeMaxTextSizeInPx and
    // mAutoSizeStepGranularityInPx.
    private boolean mHasPresetAutoSizeValues = false;

    // Autofill-related attributes
    //
    // Indicates whether the text was set statically or dynamically, so it can be used to
    // sanitize autofill requests.
    private boolean mTextSetFromXmlOrResourceId = false;
    // Resource id used to set the text.
    private @StringRes int mTextId = Resources.ID_NULL;
    // Resource id used to set the hint.
    private @StringRes int mHintId = Resources.ID_NULL;
    //
    // End of autofill-related attributes

    private Pattern mWhitespacePattern;

    /**
     * Kick-start the font cache for the zygote process (to pay the cost of
     * initializing freetype for our default font only once).
     * @hide
     */
    public static void preloadFontCache() {
        if (Typeface.ENABLE_LAZY_TYPEFACE_INITIALIZATION) {
            return;
        }
        Paint p = new Paint();
        p.setAntiAlias(true);
        // Ensure that the Typeface is loaded here.
        // Typically, Typeface is preloaded by zygote but not on all devices, e.g. Android Auto.
        // So, sets Typeface.DEFAULT explicitly here for ensuring that the Typeface is loaded here
        // since Paint.measureText can not be called without Typeface static initializer.
        p.setTypeface(Typeface.DEFAULT);
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
         * being pressed. Starting from Android 14, the action identifier will
         * also be included when triggered by an enter key if the input is
         * constrained to a single line.
         * @param event If triggered by an enter key, this is the event;
         * otherwise, this is null.
         * @return Return true if you have consumed the action, else false.
         */
        boolean onEditorAction(TextView v, int actionId, KeyEvent event);
    }

    public TextView(Context context) {
        this(context, null);
    }

    public TextView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, com.android.internal.R.attr.textViewStyle);
    }

    public TextView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    @SuppressWarnings("deprecation")
    public TextView(
            Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        // TextView is important by default, unless app developer overrode attribute.
        if (getImportantForAutofill() == IMPORTANT_FOR_AUTOFILL_AUTO) {
            setImportantForAutofill(IMPORTANT_FOR_AUTOFILL_YES);
        }
        if (getImportantForContentCapture() == IMPORTANT_FOR_CONTENT_CAPTURE_AUTO) {
            setImportantForContentCapture(IMPORTANT_FOR_CONTENT_CAPTURE_YES);
        }

        setTextInternal("");

        final Resources res = getResources();
        final CompatibilityInfo compat = res.getCompatibilityInfo();

        mTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        mTextPaint.density = res.getDisplayMetrics().density;
        mTextPaint.setCompatibilityScaling(compat.applicationScale);

        mHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mHighlightPaint.setCompatibilityScaling(compat.applicationScale);

        mMovement = getDefaultMovementMethod();

        mTransformation = null;

        final TextAppearanceAttributes attributes = new TextAppearanceAttributes();
        attributes.mTextColor = ColorStateList.valueOf(0xFF000000);
        attributes.mTextSize = 15;
        mBreakStrategy = Layout.BREAK_STRATEGY_SIMPLE;
        mHyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE;
        mJustificationMode = Layout.JUSTIFICATION_MODE_NONE;

        final Resources.Theme theme = context.getTheme();

        /*
         * Look the appearance up without checking first if it exists because
         * almost every TextView has one and it greatly simplifies the logic
         * to be able to parse the appearance first and then let specific tags
         * for this View override it.
         */
        TypedArray a = theme.obtainStyledAttributes(attrs,
                com.android.internal.R.styleable.TextViewAppearance, defStyleAttr, defStyleRes);
        saveAttributeDataForStyleable(context, com.android.internal.R.styleable.TextViewAppearance,
                attrs, a, defStyleAttr, defStyleRes);
        TypedArray appearance = null;
        int ap = a.getResourceId(
                com.android.internal.R.styleable.TextViewAppearance_textAppearance, -1);
        a.recycle();
        if (ap != -1) {
            appearance = theme.obtainStyledAttributes(
                    ap, com.android.internal.R.styleable.TextAppearance);
            saveAttributeDataForStyleable(context, com.android.internal.R.styleable.TextAppearance,
                    null, appearance, 0, ap);
        }
        if (appearance != null) {
            readTextAppearance(context, appearance, attributes, false /* styleArray */);
            attributes.mFontFamilyExplicit = false;
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
                drawableBottom = null, drawableStart = null, drawableEnd = null;
        ColorStateList drawableTint = null;
        BlendMode drawableTintMode = null;
        int drawablePadding = 0;
        int ellipsize = ELLIPSIZE_NOT_SET;
        boolean singleLine = false;
        int maxlength = -1;
        CharSequence text = "";
        CharSequence hint = null;
        boolean password = false;
        float autoSizeMinTextSizeInPx = UNSET_AUTO_SIZE_UNIFORM_CONFIGURATION_VALUE;
        float autoSizeMaxTextSizeInPx = UNSET_AUTO_SIZE_UNIFORM_CONFIGURATION_VALUE;
        float autoSizeStepGranularityInPx = UNSET_AUTO_SIZE_UNIFORM_CONFIGURATION_VALUE;
        int inputType = EditorInfo.TYPE_NULL;
        a = theme.obtainStyledAttributes(
                    attrs, com.android.internal.R.styleable.TextView, defStyleAttr, defStyleRes);
        saveAttributeDataForStyleable(context, com.android.internal.R.styleable.TextView, attrs, a,
                defStyleAttr, defStyleRes);
        int firstBaselineToTopHeight = -1;
        int lastBaselineToBottomHeight = -1;
        float lineHeight = -1f;
        int lineHeightUnit = -1;

        readTextAppearance(context, a, attributes, true /* styleArray */);

        int n = a.getIndexCount();

        // Must set id in a temporary variable because it will be reset by setText()
        boolean textIsSetFromXml = false;
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

                case com.android.internal.R.styleable.TextView_drawableStart:
                    drawableStart = a.getDrawable(attr);
                    break;

                case com.android.internal.R.styleable.TextView_drawableEnd:
                    drawableEnd = a.getDrawable(attr);
                    break;

                case com.android.internal.R.styleable.TextView_drawableTint:
                    drawableTint = a.getColorStateList(attr);
                    break;

                case com.android.internal.R.styleable.TextView_drawableTintMode:
                    drawableTintMode = Drawable.parseBlendMode(a.getInt(attr, -1),
                            drawableTintMode);
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
                    mHintId = a.getResourceId(attr, Resources.ID_NULL);
                    hint = a.getText(attr);
                    break;

                case com.android.internal.R.styleable.TextView_text:
                    textIsSetFromXml = true;
                    mTextId = a.getResourceId(attr, Resources.ID_NULL);
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

                case com.android.internal.R.styleable.TextView_enabled:
                    setEnabled(a.getBoolean(attr, isEnabled()));
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
                    inputType = a.getInt(attr, EditorInfo.TYPE_NULL);
                    break;

                case com.android.internal.R.styleable.TextView_allowUndo:
                    createEditorIfNeeded();
                    mEditor.mAllowUndo = a.getBoolean(attr, true);
                    break;

                case com.android.internal.R.styleable.TextView_imeOptions:
                    createEditorIfNeeded();
                    mEditor.createInputContentTypeIfNeeded();
                    mEditor.mInputContentType.imeOptions = a.getInt(attr,
                            mEditor.mInputContentType.imeOptions);
                    break;

                case com.android.internal.R.styleable.TextView_imeActionLabel:
                    createEditorIfNeeded();
                    mEditor.createInputContentTypeIfNeeded();
                    mEditor.mInputContentType.imeActionLabel = a.getText(attr);
                    break;

                case com.android.internal.R.styleable.TextView_imeActionId:
                    createEditorIfNeeded();
                    mEditor.createInputContentTypeIfNeeded();
                    mEditor.mInputContentType.imeActionId = a.getInt(attr,
                            mEditor.mInputContentType.imeActionId);
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

                case com.android.internal.R.styleable.TextView_textCursorDrawable:
                    mCursorDrawableRes = a.getResourceId(attr, 0);
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

                case com.android.internal.R.styleable.TextView_textEditSuggestionItemLayout:
                    mTextEditSuggestionItemLayout = a.getResourceId(attr, 0);
                    break;

                case com.android.internal.R.styleable.TextView_textEditSuggestionContainerLayout:
                    mTextEditSuggestionContainerLayout = a.getResourceId(attr, 0);
                    break;

                case com.android.internal.R.styleable.TextView_textEditSuggestionHighlightStyle:
                    mTextEditSuggestionHighlightStyle = a.getResourceId(attr, 0);
                    break;

                case com.android.internal.R.styleable.TextView_textIsSelectable:
                    setTextIsSelectable(a.getBoolean(attr, false));
                    break;

                case com.android.internal.R.styleable.TextView_breakStrategy:
                    mBreakStrategy = a.getInt(attr, Layout.BREAK_STRATEGY_SIMPLE);
                    break;

                case com.android.internal.R.styleable.TextView_hyphenationFrequency:
                    mHyphenationFrequency = a.getInt(attr, Layout.HYPHENATION_FREQUENCY_NONE);
                    break;

                case com.android.internal.R.styleable.TextView_lineBreakStyle:
                    mLineBreakStyle = a.getInt(attr, LineBreakConfig.LINE_BREAK_STYLE_NONE);
                    break;

                case com.android.internal.R.styleable.TextView_lineBreakWordStyle:
                    if (a.hasValue(attr)) {
                        mUserSpeficiedLineBreakwordStyle = true;
                    }
                    mLineBreakWordStyle = a.getInt(attr,
                            LineBreakConfig.LINE_BREAK_WORD_STYLE_NONE);
                    break;

                case com.android.internal.R.styleable.TextView_autoSizeTextType:
                    mAutoSizeTextType = a.getInt(attr, AUTO_SIZE_TEXT_TYPE_NONE);
                    break;

                case com.android.internal.R.styleable.TextView_autoSizeStepGranularity:
                    autoSizeStepGranularityInPx = a.getDimension(attr,
                        UNSET_AUTO_SIZE_UNIFORM_CONFIGURATION_VALUE);
                    break;

                case com.android.internal.R.styleable.TextView_autoSizeMinTextSize:
                    autoSizeMinTextSizeInPx = a.getDimension(attr,
                        UNSET_AUTO_SIZE_UNIFORM_CONFIGURATION_VALUE);
                    break;

                case com.android.internal.R.styleable.TextView_autoSizeMaxTextSize:
                    autoSizeMaxTextSizeInPx = a.getDimension(attr,
                        UNSET_AUTO_SIZE_UNIFORM_CONFIGURATION_VALUE);
                    break;

                case com.android.internal.R.styleable.TextView_autoSizePresetSizes:
                    final int autoSizeStepSizeArrayResId = a.getResourceId(attr, 0);
                    if (autoSizeStepSizeArrayResId > 0) {
                        final TypedArray autoSizePresetTextSizes = a.getResources()
                                .obtainTypedArray(autoSizeStepSizeArrayResId);
                        setupAutoSizeUniformPresetSizes(autoSizePresetTextSizes);
                        autoSizePresetTextSizes.recycle();
                    }
                    break;
                case com.android.internal.R.styleable.TextView_justificationMode:
                    mJustificationMode = a.getInt(attr, Layout.JUSTIFICATION_MODE_NONE);
                    break;

                case com.android.internal.R.styleable.TextView_firstBaselineToTopHeight:
                    firstBaselineToTopHeight = a.getDimensionPixelSize(attr, -1);
                    break;

                case com.android.internal.R.styleable.TextView_lastBaselineToBottomHeight:
                    lastBaselineToBottomHeight = a.getDimensionPixelSize(attr, -1);
                    break;

                case com.android.internal.R.styleable.TextView_lineHeight:
                    TypedValue peekValue = a.peekValue(attr);
                    if (peekValue != null && peekValue.type == TypedValue.TYPE_DIMENSION) {
                        lineHeightUnit = peekValue.getComplexUnit();
                        lineHeight = TypedValue.complexToFloat(peekValue.data);
                    } else {
                        lineHeight = a.getDimensionPixelSize(attr, -1);
                    }
                    break;
            }
        }

        a.recycle();

        BufferType bufferType = BufferType.EDITABLE;

        final int variation =
                inputType & (EditorInfo.TYPE_MASK_CLASS | EditorInfo.TYPE_MASK_VARIATION);
        final boolean passwordInputType = variation
                == (EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_VARIATION_PASSWORD);
        final boolean webPasswordInputType = variation
                == (EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD);
        final boolean numberPasswordInputType = variation
                == (EditorInfo.TYPE_CLASS_NUMBER | EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD);

        final int targetSdkVersion = context.getApplicationInfo().targetSdkVersion;
        mUseInternationalizedInput = targetSdkVersion >= VERSION_CODES.O;
        if (CompatChanges.isChangeEnabled(BORINGLAYOUT_FALLBACK_LINESPACING)) {
            mUseFallbackLineSpacing = FALLBACK_LINE_SPACING_ALL;
        } else if (CompatChanges.isChangeEnabled(STATICLAYOUT_FALLBACK_LINESPACING)) {
            mUseFallbackLineSpacing = FALLBACK_LINE_SPACING_STATIC_LAYOUT_ONLY;
        } else {
            mUseFallbackLineSpacing = FALLBACK_LINE_SPACING_NONE;
        }
        // TODO(b/179693024): Use a ChangeId instead.
        mUseTextPaddingForUiTranslation = targetSdkVersion <= Build.VERSION_CODES.R;

        if (inputMethod != null) {
            Class<?> c;

            try {
                c = Class.forName(inputMethod.toString());
            } catch (ClassNotFoundException ex) {
                throw new RuntimeException(ex);
            }

            try {
                createEditorIfNeeded();
                mEditor.mKeyListener = (KeyListener) c.newInstance();
            } catch (InstantiationException ex) {
                throw new RuntimeException(ex);
            } catch (IllegalAccessException ex) {
                throw new RuntimeException(ex);
            }
            try {
                mEditor.mInputType = inputType != EditorInfo.TYPE_NULL
                        ? inputType
                        : mEditor.mKeyListener.getInputType();
            } catch (IncompatibleClassChangeError e) {
                mEditor.mInputType = EditorInfo.TYPE_CLASS_TEXT;
            }
        } else if (digits != null) {
            createEditorIfNeeded();
            mEditor.mKeyListener = DigitsKeyListener.getInstance(digits.toString());
            // If no input type was specified, we will default to generic
            // text, since we can't tell the IME about the set of digits
            // that was selected.
            mEditor.mInputType = inputType != EditorInfo.TYPE_NULL
                    ? inputType : EditorInfo.TYPE_CLASS_TEXT;
        } else if (inputType != EditorInfo.TYPE_NULL) {
            setInputType(inputType, true);
            // If set, the input type overrides what was set using the deprecated singleLine flag.
            singleLine = !isMultilineInputType(inputType);
        } else if (phone) {
            createEditorIfNeeded();
            mEditor.mKeyListener = DialerKeyListener.getInstance();
            mEditor.mInputType = inputType = EditorInfo.TYPE_CLASS_PHONE;
        } else if (numeric != 0) {
            createEditorIfNeeded();
            mEditor.mKeyListener = DigitsKeyListener.getInstance(
                    null,  // locale
                    (numeric & SIGNED) != 0,
                    (numeric & DECIMAL) != 0);
            inputType = mEditor.mKeyListener.getInputType();
            mEditor.mInputType = inputType;
        } else if (autotext || autocap != -1) {
            TextKeyListener.Capitalize cap;

            inputType = EditorInfo.TYPE_CLASS_TEXT;

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

            createEditorIfNeeded();
            mEditor.mKeyListener = TextKeyListener.getInstance(autotext, cap);
            mEditor.mInputType = inputType;
        } else if (editable) {
            createEditorIfNeeded();
            mEditor.mKeyListener = TextKeyListener.getInstance();
            mEditor.mInputType = EditorInfo.TYPE_CLASS_TEXT;
        } else if (isTextSelectable()) {
            // Prevent text changes from keyboard.
            if (mEditor != null) {
                mEditor.mKeyListener = null;
                mEditor.mInputType = EditorInfo.TYPE_NULL;
            }
            bufferType = BufferType.SPANNABLE;
            // So that selection can be changed using arrow keys and touch is handled.
            setMovementMethod(ArrowKeyMovementMethod.getInstance());
        } else {
            if (mEditor != null) mEditor.mKeyListener = null;

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

        if (mEditor != null) {
            mEditor.adjustInputType(password, passwordInputType, webPasswordInputType,
                    numberPasswordInputType);
        }

        if (selectallonfocus) {
            createEditorIfNeeded();
            mEditor.mSelectAllOnFocus = true;

            if (bufferType == BufferType.NORMAL) {
                bufferType = BufferType.SPANNABLE;
            }
        }

        // Set up the tint (if needed) before setting the drawables so that it
        // gets applied correctly.
        if (drawableTint != null || drawableTintMode != null) {
            if (mDrawables == null) {
                mDrawables = new Drawables(context);
            }
            if (drawableTint != null) {
                mDrawables.mTintList = drawableTint;
                mDrawables.mHasTint = true;
            }
            if (drawableTintMode != null) {
                mDrawables.mBlendMode = drawableTintMode;
                mDrawables.mHasTintMode = true;
            }
        }

        // This call will save the initial left/right drawables
        setCompoundDrawablesWithIntrinsicBounds(
                drawableLeft, drawableTop, drawableRight, drawableBottom);
        setRelativeDrawablesIfNeeded(drawableStart, drawableEnd);
        setCompoundDrawablePadding(drawablePadding);

        // Same as setSingleLine(), but make sure the transformation method and the maximum number
        // of lines of height are unchanged for multi-line TextViews.
        setInputTypeSingleLine(singleLine);
        applySingleLine(singleLine, singleLine, singleLine,
                // Does not apply automated max length filter since length filter will be resolved
                // later in this function.
                false
        );

        if (singleLine && getKeyListener() == null && ellipsize == ELLIPSIZE_NOT_SET) {
            ellipsize = ELLIPSIZE_END;
        }

        switch (ellipsize) {
            case ELLIPSIZE_START:
                setEllipsize(TextUtils.TruncateAt.START);
                break;
            case ELLIPSIZE_MIDDLE:
                setEllipsize(TextUtils.TruncateAt.MIDDLE);
                break;
            case ELLIPSIZE_END:
                setEllipsize(TextUtils.TruncateAt.END);
                break;
            case ELLIPSIZE_MARQUEE:
                if (ViewConfiguration.get(context).isFadingMarqueeEnabled()) {
                    setHorizontalFadingEdgeEnabled(true);
                    mMarqueeFadeMode = MARQUEE_FADE_NORMAL;
                } else {
                    setHorizontalFadingEdgeEnabled(false);
                    mMarqueeFadeMode = MARQUEE_FADE_SWITCH_SHOW_ELLIPSIS;
                }
                setEllipsize(TextUtils.TruncateAt.MARQUEE);
                break;
        }

        final boolean isPassword = password || passwordInputType || webPasswordInputType
                || numberPasswordInputType;
        final boolean isMonospaceEnforced = isPassword || (mEditor != null
                && (mEditor.mInputType
                & (EditorInfo.TYPE_MASK_CLASS | EditorInfo.TYPE_MASK_VARIATION))
                == (EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_VARIATION_PASSWORD));
        if (isMonospaceEnforced) {
            attributes.mTypefaceIndex = MONOSPACE;
        }

        mFontWeightAdjustment = getContext().getResources().getConfiguration().fontWeightAdjustment;
        applyTextAppearance(attributes);

        if (isPassword) {
            setTransformationMethod(PasswordTransformationMethod.getInstance());
        }

        // For addressing b/145128646
        // For the performance reason, we limit characters for single line text field.
        if (bufferType == BufferType.EDITABLE && singleLine && maxlength == -1) {
            mSingleLineLengthFilter = new InputFilter.LengthFilter(
                MAX_LENGTH_FOR_SINGLE_LINE_EDIT_TEXT);
        }

        if (mSingleLineLengthFilter != null) {
            setFilters(new InputFilter[] { mSingleLineLengthFilter });
        } else if (maxlength >= 0) {
            setFilters(new InputFilter[] { new InputFilter.LengthFilter(maxlength) });
        } else {
            setFilters(NO_FILTERS);
        }

        setText(text, bufferType);
        if (mText == null) {
            mText = "";
        }
        if (mTransformed == null) {
            mTransformed = "";
        }

        if (textIsSetFromXml) {
            mTextSetFromXmlOrResourceId = true;
        }

        if (hint != null) setHint(hint);

        /*
         * Views are not normally clickable unless specified to be.
         * However, TextViews that have input or movement methods *are*
         * clickable by default. By setting clickable here, we implicitly set focusable as well
         * if not overridden by the developer.
         */
        a = context.obtainStyledAttributes(
                attrs, com.android.internal.R.styleable.View, defStyleAttr, defStyleRes);
        boolean canInputOrMove = (mMovement != null || getKeyListener() != null);
        boolean clickable = canInputOrMove || isClickable();
        boolean longClickable = canInputOrMove || isLongClickable();
        int focusable = getFocusable();

        n = a.getIndexCount();
        for (int i = 0; i < n; i++) {
            int attr = a.getIndex(i);

            switch (attr) {
                case com.android.internal.R.styleable.View_focusable:
                    TypedValue val = new TypedValue();
                    if (a.getValue(attr, val)) {
                        focusable = (val.type == TypedValue.TYPE_INT_BOOLEAN)
                                ? (val.data == 0 ? NOT_FOCUSABLE : FOCUSABLE)
                                : val.data;
                    }
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

        // Some apps were relying on the undefined behavior of focusable winning over
        // focusableInTouchMode != focusable in TextViews if both were specified in XML (usually
        // when starting with EditText and setting only focusable=false). To keep those apps from
        // breaking, re-apply the focusable attribute here.
        if (focusable != getFocusable()) {
            setFocusable(focusable);
        }
        setClickable(clickable);
        setLongClickable(longClickable);

        if (mEditor != null) mEditor.prepareCursorControllers();

        // If not explicitly specified this view is important for accessibility.
        if (getImportantForAccessibility() == IMPORTANT_FOR_ACCESSIBILITY_AUTO) {
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        }

        if (supportsAutoSizeText()) {
            if (mAutoSizeTextType == AUTO_SIZE_TEXT_TYPE_UNIFORM) {
                // If uniform auto-size has been specified but preset values have not been set then
                // replace the auto-size configuration values that have not been specified with the
                // defaults.
                if (!mHasPresetAutoSizeValues) {
                    final DisplayMetrics displayMetrics = getResources().getDisplayMetrics();

                    if (autoSizeMinTextSizeInPx == UNSET_AUTO_SIZE_UNIFORM_CONFIGURATION_VALUE) {
                        autoSizeMinTextSizeInPx = TypedValue.applyDimension(
                                TypedValue.COMPLEX_UNIT_SP,
                                DEFAULT_AUTO_SIZE_MIN_TEXT_SIZE_IN_SP,
                                displayMetrics);
                    }

                    if (autoSizeMaxTextSizeInPx == UNSET_AUTO_SIZE_UNIFORM_CONFIGURATION_VALUE) {
                        autoSizeMaxTextSizeInPx = TypedValue.applyDimension(
                                TypedValue.COMPLEX_UNIT_SP,
                                DEFAULT_AUTO_SIZE_MAX_TEXT_SIZE_IN_SP,
                                displayMetrics);
                    }

                    if (autoSizeStepGranularityInPx
                            == UNSET_AUTO_SIZE_UNIFORM_CONFIGURATION_VALUE) {
                        autoSizeStepGranularityInPx = DEFAULT_AUTO_SIZE_GRANULARITY_IN_PX;
                    }

                    validateAndSetAutoSizeTextTypeUniformConfiguration(autoSizeMinTextSizeInPx,
                            autoSizeMaxTextSizeInPx,
                            autoSizeStepGranularityInPx);
                }

                setupAutoSizeText();
            }
        } else {
            mAutoSizeTextType = AUTO_SIZE_TEXT_TYPE_NONE;
        }

        if (firstBaselineToTopHeight >= 0) {
            setFirstBaselineToTopHeight(firstBaselineToTopHeight);
        }
        if (lastBaselineToBottomHeight >= 0) {
            setLastBaselineToBottomHeight(lastBaselineToBottomHeight);
        }
        if (lineHeight >= 0) {
            if (lineHeightUnit == -1) {
                setLineHeightPx(lineHeight);
            } else {
                setLineHeight(lineHeightUnit, lineHeight);
            }
        }
    }

    // Update mText and mPrecomputed
    private void setTextInternal(@Nullable CharSequence text) {
        mText = text;
        mSpannable = (text instanceof Spannable) ? (Spannable) text : null;
        mPrecomputed = (text instanceof PrecomputedText) ? (PrecomputedText) text : null;
    }

    /**
     * Specify whether this widget should automatically scale the text to try to perfectly fit
     * within the layout bounds by using the default auto-size configuration.
     *
     * @param autoSizeTextType the type of auto-size. Must be one of
     *        {@link TextView#AUTO_SIZE_TEXT_TYPE_NONE} or
     *        {@link TextView#AUTO_SIZE_TEXT_TYPE_UNIFORM}
     *
     * @throws IllegalArgumentException if <code>autoSizeTextType</code> is none of the types above.
     *
     * @attr ref android.R.styleable#TextView_autoSizeTextType
     *
     * @see #getAutoSizeTextType()
     */
    public void setAutoSizeTextTypeWithDefaults(@AutoSizeTextType int autoSizeTextType) {
        if (supportsAutoSizeText()) {
            switch (autoSizeTextType) {
                case AUTO_SIZE_TEXT_TYPE_NONE:
                    clearAutoSizeConfiguration();
                    break;
                case AUTO_SIZE_TEXT_TYPE_UNIFORM:
                    final DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
                    final float autoSizeMinTextSizeInPx = TypedValue.applyDimension(
                            TypedValue.COMPLEX_UNIT_SP,
                            DEFAULT_AUTO_SIZE_MIN_TEXT_SIZE_IN_SP,
                            displayMetrics);
                    final float autoSizeMaxTextSizeInPx = TypedValue.applyDimension(
                            TypedValue.COMPLEX_UNIT_SP,
                            DEFAULT_AUTO_SIZE_MAX_TEXT_SIZE_IN_SP,
                            displayMetrics);

                    validateAndSetAutoSizeTextTypeUniformConfiguration(
                            autoSizeMinTextSizeInPx,
                            autoSizeMaxTextSizeInPx,
                            DEFAULT_AUTO_SIZE_GRANULARITY_IN_PX);
                    if (setupAutoSizeText()) {
                        autoSizeText();
                        invalidate();
                    }
                    break;
                default:
                    throw new IllegalArgumentException(
                            "Unknown auto-size text type: " + autoSizeTextType);
            }
        }
    }

    /**
     * Specify whether this widget should automatically scale the text to try to perfectly fit
     * within the layout bounds. If all the configuration params are valid the type of auto-size is
     * set to {@link #AUTO_SIZE_TEXT_TYPE_UNIFORM}.
     *
     * @param autoSizeMinTextSize the minimum text size available for auto-size
     * @param autoSizeMaxTextSize the maximum text size available for auto-size
     * @param autoSizeStepGranularity the auto-size step granularity. It is used in conjunction with
     *                                the minimum and maximum text size in order to build the set of
     *                                text sizes the system uses to choose from when auto-sizing
     * @param unit the desired dimension unit for all sizes above. See {@link TypedValue} for the
     *             possible dimension units
     *
     * @throws IllegalArgumentException if any of the configuration params are invalid.
     *
     * @attr ref android.R.styleable#TextView_autoSizeTextType
     * @attr ref android.R.styleable#TextView_autoSizeMinTextSize
     * @attr ref android.R.styleable#TextView_autoSizeMaxTextSize
     * @attr ref android.R.styleable#TextView_autoSizeStepGranularity
     *
     * @see #setAutoSizeTextTypeWithDefaults(int)
     * @see #setAutoSizeTextTypeUniformWithPresetSizes(int[], int)
     * @see #getAutoSizeMinTextSize()
     * @see #getAutoSizeMaxTextSize()
     * @see #getAutoSizeStepGranularity()
     * @see #getAutoSizeTextAvailableSizes()
     */
    public void setAutoSizeTextTypeUniformWithConfiguration(int autoSizeMinTextSize,
            int autoSizeMaxTextSize, int autoSizeStepGranularity, int unit) {
        if (supportsAutoSizeText()) {
            final DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
            final float autoSizeMinTextSizeInPx = TypedValue.applyDimension(
                    unit, autoSizeMinTextSize, displayMetrics);
            final float autoSizeMaxTextSizeInPx = TypedValue.applyDimension(
                    unit, autoSizeMaxTextSize, displayMetrics);
            final float autoSizeStepGranularityInPx = TypedValue.applyDimension(
                    unit, autoSizeStepGranularity, displayMetrics);

            validateAndSetAutoSizeTextTypeUniformConfiguration(autoSizeMinTextSizeInPx,
                    autoSizeMaxTextSizeInPx,
                    autoSizeStepGranularityInPx);

            if (setupAutoSizeText()) {
                autoSizeText();
                invalidate();
            }
        }
    }

    /**
     * Specify whether this widget should automatically scale the text to try to perfectly fit
     * within the layout bounds. If at least one value from the <code>presetSizes</code> is valid
     * then the type of auto-size is set to {@link #AUTO_SIZE_TEXT_TYPE_UNIFORM}.
     *
     * @param presetSizes an {@code int} array of sizes in pixels
     * @param unit the desired dimension unit for the preset sizes above. See {@link TypedValue} for
     *             the possible dimension units
     *
     * @throws IllegalArgumentException if all of the <code>presetSizes</code> are invalid.
     *
     * @attr ref android.R.styleable#TextView_autoSizeTextType
     * @attr ref android.R.styleable#TextView_autoSizePresetSizes
     *
     * @see #setAutoSizeTextTypeWithDefaults(int)
     * @see #setAutoSizeTextTypeUniformWithConfiguration(int, int, int, int)
     * @see #getAutoSizeMinTextSize()
     * @see #getAutoSizeMaxTextSize()
     * @see #getAutoSizeTextAvailableSizes()
     */
    public void setAutoSizeTextTypeUniformWithPresetSizes(@NonNull int[] presetSizes, int unit) {
        if (supportsAutoSizeText()) {
            final int presetSizesLength = presetSizes.length;
            if (presetSizesLength > 0) {
                int[] presetSizesInPx = new int[presetSizesLength];

                if (unit == TypedValue.COMPLEX_UNIT_PX) {
                    presetSizesInPx = Arrays.copyOf(presetSizes, presetSizesLength);
                } else {
                    final DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
                    // Convert all to sizes to pixels.
                    for (int i = 0; i < presetSizesLength; i++) {
                        presetSizesInPx[i] = Math.round(TypedValue.applyDimension(unit,
                            presetSizes[i], displayMetrics));
                    }
                }

                mAutoSizeTextSizesInPx = cleanupAutoSizePresetSizes(presetSizesInPx);
                if (!setupAutoSizeUniformPresetSizesConfiguration()) {
                    throw new IllegalArgumentException("None of the preset sizes is valid: "
                            + Arrays.toString(presetSizes));
                }
            } else {
                mHasPresetAutoSizeValues = false;
            }

            if (setupAutoSizeText()) {
                autoSizeText();
                invalidate();
            }
        }
    }

    /**
     * Returns the type of auto-size set for this widget.
     *
     * @return an {@code int} corresponding to one of the auto-size types:
     *         {@link TextView#AUTO_SIZE_TEXT_TYPE_NONE} or
     *         {@link TextView#AUTO_SIZE_TEXT_TYPE_UNIFORM}
     *
     * @attr ref android.R.styleable#TextView_autoSizeTextType
     *
     * @see #setAutoSizeTextTypeWithDefaults(int)
     * @see #setAutoSizeTextTypeUniformWithConfiguration(int, int, int, int)
     * @see #setAutoSizeTextTypeUniformWithPresetSizes(int[], int)
     */
    @InspectableProperty(enumMapping = {
            @EnumEntry(name = "none", value = AUTO_SIZE_TEXT_TYPE_NONE),
            @EnumEntry(name = "uniform", value = AUTO_SIZE_TEXT_TYPE_UNIFORM)
    })
    @AutoSizeTextType
    public int getAutoSizeTextType() {
        return mAutoSizeTextType;
    }

    /**
     * @return the current auto-size step granularity in pixels.
     *
     * @attr ref android.R.styleable#TextView_autoSizeStepGranularity
     *
     * @see #setAutoSizeTextTypeUniformWithConfiguration(int, int, int, int)
     */
    @InspectableProperty
    public int getAutoSizeStepGranularity() {
        return Math.round(mAutoSizeStepGranularityInPx);
    }

    /**
     * @return the current auto-size minimum text size in pixels (the default is 12sp). Note that
     *         if auto-size has not been configured this function returns {@code -1}.
     *
     * @attr ref android.R.styleable#TextView_autoSizeMinTextSize
     *
     * @see #setAutoSizeTextTypeUniformWithConfiguration(int, int, int, int)
     * @see #setAutoSizeTextTypeUniformWithPresetSizes(int[], int)
     */
    @InspectableProperty
    public int getAutoSizeMinTextSize() {
        return Math.round(mAutoSizeMinTextSizeInPx);
    }

    /**
     * @return the current auto-size maximum text size in pixels (the default is 112sp). Note that
     *         if auto-size has not been configured this function returns {@code -1}.
     *
     * @attr ref android.R.styleable#TextView_autoSizeMaxTextSize
     *
     * @see #setAutoSizeTextTypeUniformWithConfiguration(int, int, int, int)
     * @see #setAutoSizeTextTypeUniformWithPresetSizes(int[], int)
     */
    @InspectableProperty
    public int getAutoSizeMaxTextSize() {
        return Math.round(mAutoSizeMaxTextSizeInPx);
    }

    /**
     * @return the current auto-size {@code int} sizes array (in pixels).
     *
     * @see #setAutoSizeTextTypeUniformWithConfiguration(int, int, int, int)
     * @see #setAutoSizeTextTypeUniformWithPresetSizes(int[], int)
     */
    public int[] getAutoSizeTextAvailableSizes() {
        return mAutoSizeTextSizesInPx;
    }

    private void setupAutoSizeUniformPresetSizes(TypedArray textSizes) {
        final int textSizesLength = textSizes.length();
        final int[] parsedSizes = new int[textSizesLength];

        if (textSizesLength > 0) {
            for (int i = 0; i < textSizesLength; i++) {
                parsedSizes[i] = textSizes.getDimensionPixelSize(i, -1);
            }
            mAutoSizeTextSizesInPx = cleanupAutoSizePresetSizes(parsedSizes);
            setupAutoSizeUniformPresetSizesConfiguration();
        }
    }

    private boolean setupAutoSizeUniformPresetSizesConfiguration() {
        final int sizesLength = mAutoSizeTextSizesInPx.length;
        mHasPresetAutoSizeValues = sizesLength > 0;
        if (mHasPresetAutoSizeValues) {
            mAutoSizeTextType = AUTO_SIZE_TEXT_TYPE_UNIFORM;
            mAutoSizeMinTextSizeInPx = mAutoSizeTextSizesInPx[0];
            mAutoSizeMaxTextSizeInPx = mAutoSizeTextSizesInPx[sizesLength - 1];
            mAutoSizeStepGranularityInPx = UNSET_AUTO_SIZE_UNIFORM_CONFIGURATION_VALUE;
        }
        return mHasPresetAutoSizeValues;
    }

    /**
     * If all params are valid then save the auto-size configuration.
     *
     * @throws IllegalArgumentException if any of the params are invalid
     */
    private void validateAndSetAutoSizeTextTypeUniformConfiguration(float autoSizeMinTextSizeInPx,
            float autoSizeMaxTextSizeInPx, float autoSizeStepGranularityInPx) {
        // First validate.
        if (autoSizeMinTextSizeInPx <= 0) {
            throw new IllegalArgumentException("Minimum auto-size text size ("
                + autoSizeMinTextSizeInPx  + "px) is less or equal to (0px)");
        }

        if (autoSizeMaxTextSizeInPx <= autoSizeMinTextSizeInPx) {
            throw new IllegalArgumentException("Maximum auto-size text size ("
                + autoSizeMaxTextSizeInPx + "px) is less or equal to minimum auto-size "
                + "text size (" + autoSizeMinTextSizeInPx + "px)");
        }

        if (autoSizeStepGranularityInPx <= 0) {
            throw new IllegalArgumentException("The auto-size step granularity ("
                + autoSizeStepGranularityInPx + "px) is less or equal to (0px)");
        }

        // All good, persist the configuration.
        mAutoSizeTextType = AUTO_SIZE_TEXT_TYPE_UNIFORM;
        mAutoSizeMinTextSizeInPx = autoSizeMinTextSizeInPx;
        mAutoSizeMaxTextSizeInPx = autoSizeMaxTextSizeInPx;
        mAutoSizeStepGranularityInPx = autoSizeStepGranularityInPx;
        mHasPresetAutoSizeValues = false;
    }

    private void clearAutoSizeConfiguration() {
        mAutoSizeTextType = AUTO_SIZE_TEXT_TYPE_NONE;
        mAutoSizeMinTextSizeInPx = UNSET_AUTO_SIZE_UNIFORM_CONFIGURATION_VALUE;
        mAutoSizeMaxTextSizeInPx = UNSET_AUTO_SIZE_UNIFORM_CONFIGURATION_VALUE;
        mAutoSizeStepGranularityInPx = UNSET_AUTO_SIZE_UNIFORM_CONFIGURATION_VALUE;
        mAutoSizeTextSizesInPx = EmptyArray.INT;
        mNeedsAutoSizeText = false;
    }

    // Returns distinct sorted positive values.
    private int[] cleanupAutoSizePresetSizes(int[] presetValues) {
        final int presetValuesLength = presetValues.length;
        if (presetValuesLength == 0) {
            return presetValues;
        }
        Arrays.sort(presetValues);

        final IntArray uniqueValidSizes = new IntArray();
        for (int i = 0; i < presetValuesLength; i++) {
            final int currentPresetValue = presetValues[i];

            if (currentPresetValue > 0
                    && uniqueValidSizes.binarySearch(currentPresetValue) < 0) {
                uniqueValidSizes.add(currentPresetValue);
            }
        }

        return presetValuesLength == uniqueValidSizes.size()
            ? presetValues
            : uniqueValidSizes.toArray();
    }

    private boolean setupAutoSizeText() {
        if (supportsAutoSizeText() && mAutoSizeTextType == AUTO_SIZE_TEXT_TYPE_UNIFORM) {
            // Calculate the sizes set based on minimum size, maximum size and step size if we do
            // not have a predefined set of sizes or if the current sizes array is empty.
            if (!mHasPresetAutoSizeValues || mAutoSizeTextSizesInPx.length == 0) {
                final int autoSizeValuesLength = ((int) Math.floor((mAutoSizeMaxTextSizeInPx
                        - mAutoSizeMinTextSizeInPx) / mAutoSizeStepGranularityInPx)) + 1;
                final int[] autoSizeTextSizesInPx = new int[autoSizeValuesLength];
                for (int i = 0; i < autoSizeValuesLength; i++) {
                    autoSizeTextSizesInPx[i] = Math.round(
                            mAutoSizeMinTextSizeInPx + (i * mAutoSizeStepGranularityInPx));
                }
                mAutoSizeTextSizesInPx = cleanupAutoSizePresetSizes(autoSizeTextSizesInPx);
            }

            mNeedsAutoSizeText = true;
        } else {
            mNeedsAutoSizeText = false;
        }

        return mNeedsAutoSizeText;
    }

    private int[] parseDimensionArray(TypedArray dimens) {
        if (dimens == null) {
            return null;
        }
        int[] result = new int[dimens.length()];
        for (int i = 0; i < result.length; i++) {
            result[i] = dimens.getDimensionPixelSize(i, 0);
        }
        return result;
    }

    /**
     * @hide
     */
    @TestApi
    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == PROCESS_TEXT_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                CharSequence result = data.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
                if (result != null) {
                    if (isTextEditable()) {
                        ClipData clip = ClipData.newPlainText("", result);
                        ContentInfo payload =
                                new ContentInfo.Builder(clip, SOURCE_PROCESS_TEXT).build();
                        performReceiveContent(payload);
                        if (mEditor != null) {
                            mEditor.refreshTextActionMode();
                        }
                    } else {
                        if (result.length() > 0) {
                            Toast.makeText(getContext(), String.valueOf(result), Toast.LENGTH_LONG)
                                .show();
                        }
                    }
                }
            } else if (mSpannable != null) {
                // Reset the selection.
                Selection.setSelection(mSpannable, getSelectionEnd());
            }
        }
    }

    /**
     * Sets the Typeface taking into account the given attributes.
     *
     * @param typeface a typeface
     * @param familyName family name string, e.g. "serif"
     * @param typefaceIndex an index of the typeface enum, e.g. SANS, SERIF.
     * @param style a typeface style
     * @param weight a weight value for the Typeface or {@code FontStyle.FONT_WEIGHT_UNSPECIFIED}
     *               if not specified.
     */
    private void setTypefaceFromAttrs(@Nullable Typeface typeface, @Nullable String familyName,
            @XMLTypefaceAttr int typefaceIndex, @Typeface.Style int style,
            @IntRange(from = FontStyle.FONT_WEIGHT_UNSPECIFIED, to = FontStyle.FONT_WEIGHT_MAX)
                    int weight) {
        if (typeface == null && familyName != null) {
            // Lookup normal Typeface from system font map.
            final Typeface normalTypeface = Typeface.create(familyName, Typeface.NORMAL);
            resolveStyleAndSetTypeface(normalTypeface, style, weight);
        } else if (typeface != null) {
            resolveStyleAndSetTypeface(typeface, style, weight);
        } else {  // both typeface and familyName is null.
            switch (typefaceIndex) {
                case SANS:
                    resolveStyleAndSetTypeface(Typeface.SANS_SERIF, style, weight);
                    break;
                case SERIF:
                    resolveStyleAndSetTypeface(Typeface.SERIF, style, weight);
                    break;
                case MONOSPACE:
                    resolveStyleAndSetTypeface(Typeface.MONOSPACE, style, weight);
                    break;
                case DEFAULT_TYPEFACE:
                default:
                    resolveStyleAndSetTypeface(null, style, weight);
                    break;
            }
        }
    }

    private void resolveStyleAndSetTypeface(@NonNull Typeface typeface, @Typeface.Style int style,
            @IntRange(from = FontStyle.FONT_WEIGHT_UNSPECIFIED, to = FontStyle.FONT_WEIGHT_MAX)
                    int weight) {
        if (weight >= 0) {
            weight = Math.min(FontStyle.FONT_WEIGHT_MAX, weight);
            final boolean italic = (style & Typeface.ITALIC) != 0;
            setTypeface(Typeface.create(typeface, weight, italic));
        } else {
            setTypeface(typeface, style);
        }
    }

    private void setRelativeDrawablesIfNeeded(Drawable start, Drawable end) {
        boolean hasRelativeDrawables = (start != null) || (end != null);
        if (hasRelativeDrawables) {
            Drawables dr = mDrawables;
            if (dr == null) {
                mDrawables = dr = new Drawables(getContext());
            }
            mDrawables.mOverride = true;
            final Rect compoundRect = dr.mCompoundRect;
            int[] state = getDrawableState();
            if (start != null) {
                start.setBounds(0, 0, start.getIntrinsicWidth(), start.getIntrinsicHeight());
                start.setState(state);
                start.copyBounds(compoundRect);
                start.setCallback(this);

                dr.mDrawableStart = start;
                dr.mDrawableSizeStart = compoundRect.width();
                dr.mDrawableHeightStart = compoundRect.height();
            } else {
                dr.mDrawableSizeStart = dr.mDrawableHeightStart = 0;
            }
            if (end != null) {
                end.setBounds(0, 0, end.getIntrinsicWidth(), end.getIntrinsicHeight());
                end.setState(state);
                end.copyBounds(compoundRect);
                end.setCallback(this);

                dr.mDrawableEnd = end;
                dr.mDrawableSizeEnd = compoundRect.width();
                dr.mDrawableHeightEnd = compoundRect.height();
            } else {
                dr.mDrawableSizeEnd = dr.mDrawableHeightEnd = 0;
            }
            resetResolvedDrawables();
            resolveDrawables();
            applyCompoundDrawableTint();
        }
    }

    @android.view.RemotableViewMethod
    @Override
    public void setEnabled(boolean enabled) {
        if (enabled == isEnabled()) {
            return;
        }

        if (!enabled) {
            // Hide the soft input if the currently active TextView is disabled
            InputMethodManager imm = getInputMethodManager();
            if (imm != null && imm.isActive(this)) {
                imm.hideSoftInputFromWindow(getWindowToken(), 0);
            }
        }

        super.setEnabled(enabled);

        if (enabled) {
            // Make sure IME is updated with current editor info.
            InputMethodManager imm = getInputMethodManager();
            if (imm != null) imm.restartInput(this);
        }

        // Will change text color
        if (mEditor != null) {
            mEditor.invalidateTextDisplayList();
            mEditor.prepareCursorControllers();

            // start or stop the cursor blinking as appropriate
            mEditor.makeBlink();
        }
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
    public void setTypeface(@Nullable Typeface tf, @Typeface.Style int style) {
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
     * Return the text that TextView is displaying. If {@link #setText(CharSequence)} was called
     * with an argument of {@link android.widget.TextView.BufferType#SPANNABLE BufferType.SPANNABLE}
     * or {@link android.widget.TextView.BufferType#EDITABLE BufferType.EDITABLE}, you can cast
     * the return value from this method to Spannable or Editable, respectively.
     *
     * <p>The content of the return value should not be modified. If you want a modifiable one, you
     * should make your own copy first.</p>
     *
     * @return The text displayed by the text view.
     * @attr ref android.R.styleable#TextView_text
     */
    @ViewDebug.CapturedViewProperty
    @InspectableProperty
    public CharSequence getText() {
        if (mUseTextPaddingForUiTranslation) {
            ViewTranslationCallback callback = getViewTranslationCallback();
            if (callback != null && callback instanceof TextViewTranslationCallback) {
                TextViewTranslationCallback defaultCallback =
                        (TextViewTranslationCallback) callback;
                if (defaultCallback.isTextPaddingEnabled()
                        && defaultCallback.isShowingTranslation()) {
                    return defaultCallback.getPaddedText(mText, mTransformed);
                }
            }
        }
        return mText;
    }

    /**
     * Returns the length, in characters, of the text managed by this TextView
     * @return The length of the text managed by the TextView in characters.
     */
    public int length() {
        return mText.length();
    }

    /**
     * Return the text that TextView is displaying as an Editable object. If the text is not
     * editable, null is returned.
     *
     * @see #getText
     */
    public Editable getEditableText() {
        return (mText instanceof Editable) ? (Editable) mText : null;
    }

    /**
     * @hide
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public CharSequence getTransformed() {
        return mTransformed;
    }

    /**
     * Gets the vertical distance between lines of text, in pixels.
     * Note that markup within the text can cause individual lines
     * to be taller or shorter than this height, and the layout may
     * contain additional first-or last-line padding.
     * @return The height of one standard line in pixels.
     */
    @InspectableProperty
    public int getLineHeight() {
        return FastMath.round(mTextPaint.getFontMetricsInt(null) * mSpacingMult + mSpacingAdd);
    }

    /**
     * Gets the {@link android.text.Layout} that is currently being used to display the text.
     * This value can be null if the text or width has recently changed.
     * @return The Layout that is currently being used to display the text.
     */
    public final Layout getLayout() {
        return mLayout;
    }

    /**
     * @return the {@link android.text.Layout} that is currently being used to
     * display the hint text. This can be null.
     */
    @UnsupportedAppUsage
    final Layout getHintLayout() {
        return mHintLayout;
    }

    /**
     * Retrieve the {@link android.content.UndoManager} that is currently associated
     * with this TextView.  By default there is no associated UndoManager, so null
     * is returned.  One can be associated with the TextView through
     * {@link #setUndoManager(android.content.UndoManager, String)}
     *
     * @hide
     */
    public final UndoManager getUndoManager() {
        // TODO: Consider supporting a global undo manager.
        throw new UnsupportedOperationException("not implemented");
    }


    /**
     * @hide
     */
    @VisibleForTesting
    public final Editor getEditorForTesting() {
        return mEditor;
    }

    /**
     * Associate an {@link android.content.UndoManager} with this TextView.  Once
     * done, all edit operations on the TextView will result in appropriate
     * {@link android.content.UndoOperation} objects pushed on the given UndoManager's
     * stack.
     *
     * @param undoManager The {@link android.content.UndoManager} to associate with
     * this TextView, or null to clear any existing association.
     * @param tag String tag identifying this particular TextView owner in the
     * UndoManager.  This is used to keep the correct association with the
     * {@link android.content.UndoOwner} of any operations inside of the UndoManager.
     *
     * @hide
     */
    public final void setUndoManager(UndoManager undoManager, String tag) {
        // TODO: Consider supporting a global undo manager. An implementation will need to:
        // * createEditorIfNeeded()
        // * Promote to BufferType.EDITABLE if needed.
        // * Update the UndoManager and UndoOwner.
        // Likewise it will need to be able to restore the default UndoManager.
        throw new UnsupportedOperationException("not implemented");
    }

    /**
     * Gets the current {@link KeyListener} for the TextView.
     * This will frequently be null for non-EditText TextViews.
     * @return the current key listener for this TextView.
     *
     * @attr ref android.R.styleable#TextView_numeric
     * @attr ref android.R.styleable#TextView_digits
     * @attr ref android.R.styleable#TextView_phoneNumber
     * @attr ref android.R.styleable#TextView_inputMethod
     * @attr ref android.R.styleable#TextView_capitalize
     * @attr ref android.R.styleable#TextView_autoText
     */
    public final KeyListener getKeyListener() {
        return mEditor == null ? null : mEditor.mKeyListener;
    }

    /**
     * Sets the key listener to be used with this TextView.  This can be null
     * to disallow user input.  Note that this method has significant and
     * subtle interactions with soft keyboards and other input method:
     * see {@link KeyListener#getInputType() KeyListener.getInputType()}
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
        mListenerChanged = true;
        setKeyListenerOnly(input);
        fixFocusableAndClickableSettings();

        if (input != null) {
            createEditorIfNeeded();
            setInputTypeFromEditor();
        } else {
            if (mEditor != null) mEditor.mInputType = EditorInfo.TYPE_NULL;
        }

        InputMethodManager imm = getInputMethodManager();
        if (imm != null) imm.restartInput(this);
    }

    private void setInputTypeFromEditor() {
        try {
            mEditor.mInputType = mEditor.mKeyListener.getInputType();
        } catch (IncompatibleClassChangeError e) {
            mEditor.mInputType = EditorInfo.TYPE_CLASS_TEXT;
        }
        // Change inputType, without affecting transformation.
        // No need to applySingleLine since mSingleLine is unchanged.
        setInputTypeSingleLine(mSingleLine);
    }

    private void setKeyListenerOnly(KeyListener input) {
        if (mEditor == null && input == null) return; // null is the default value

        createEditorIfNeeded();
        if (mEditor.mKeyListener != input) {
            mEditor.mKeyListener = input;
            if (input != null && !(mText instanceof Editable)) {
                setText(mText);
            }

            setFilters((Editable) mText, mFilters);
        }
    }

    /**
     * Gets the {@link android.text.method.MovementMethod} being used for this TextView,
     * which provides positioning, scrolling, and text selection functionality.
     * This will frequently be null for non-EditText TextViews.
     * @return the movement method being used for this TextView.
     * @see android.text.method.MovementMethod
     */
    public final MovementMethod getMovementMethod() {
        return mMovement;
    }

    /**
     * Sets the {@link android.text.method.MovementMethod} for handling arrow key movement
     * for this TextView. This can be null to disallow using the arrow keys to move the
     * cursor or scroll the view.
     * <p>
     * Be warned that if you want a TextView with a key listener or movement
     * method not to be focusable, or if you want a TextView without a
     * key listener or movement method to be focusable, you must call
     * {@link #setFocusable} again after calling this to get the focusability
     * back the way you want it.
     */
    public final void setMovementMethod(MovementMethod movement) {
        if (mMovement != movement) {
            mMovement = movement;

            if (movement != null && mSpannable == null) {
                setText(mText);
            }

            fixFocusableAndClickableSettings();

            // SelectionModifierCursorController depends on textCanBeSelected, which depends on
            // mMovement
            if (mEditor != null) mEditor.prepareCursorControllers();
        }
    }

    private void fixFocusableAndClickableSettings() {
        if (mMovement != null || (mEditor != null && mEditor.mKeyListener != null)) {
            setFocusable(FOCUSABLE);
            setClickable(true);
            setLongClickable(true);
        } else {
            setFocusable(FOCUSABLE_AUTO);
            setClickable(false);
            setLongClickable(false);
        }
    }

    /**
     * Gets the current {@link android.text.method.TransformationMethod} for the TextView.
     * This is frequently null, except for single-line and password fields.
     * @return the current transformation method for this TextView.
     *
     * @attr ref android.R.styleable#TextView_password
     * @attr ref android.R.styleable#TextView_singleLine
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
        if (mEditor != null) {
            mEditor.setTransformationMethod(method);
        } else {
            setTransformationMethodInternal(method);
        }
    }

    void setTransformationMethodInternal(@Nullable TransformationMethod method) {
        if (method == mTransformation) {
            // Avoid the setText() below if the transformation is
            // the same.
            return;
        }
        if (mTransformation != null) {
            if (mSpannable != null) {
                mSpannable.removeSpan(mTransformation);
            }
        }

        mTransformation = method;

        if (method instanceof TransformationMethod2) {
            TransformationMethod2 method2 = (TransformationMethod2) method;
            mAllowTransformationLengthChange = !isTextSelectable() && !(mText instanceof Editable);
            method2.setLengthChangesAllowed(mAllowTransformationLengthChange);
        } else {
            mAllowTransformationLengthChange = false;
        }

        setText(mText);

        if (hasPasswordTransformationMethod()) {
            notifyViewAccessibilityStateChangedIfNeeded(
                    AccessibilityEvent.CONTENT_CHANGE_TYPE_UNDEFINED);
        }

        // PasswordTransformationMethod always have LTR text direction heuristics returned by
        // getTextDirectionHeuristic, needs reset
        mTextDir = getTextDirectionHeuristic();
    }

    /**
     * Returns the top padding of the view, plus space for the top
     * Drawable if any.
     */
    public int getCompoundPaddingTop() {
        final Drawables dr = mDrawables;
        if (dr == null || dr.mShowing[Drawables.TOP] == null) {
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
        if (dr == null || dr.mShowing[Drawables.BOTTOM] == null) {
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
        if (dr == null || dr.mShowing[Drawables.LEFT] == null) {
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
        if (dr == null || dr.mShowing[Drawables.RIGHT] == null) {
            return mPaddingRight;
        } else {
            return mPaddingRight + dr.mDrawablePadding + dr.mDrawableSizeRight;
        }
    }

    /**
     * Returns the start padding of the view, plus space for the start
     * Drawable if any.
     */
    public int getCompoundPaddingStart() {
        resolveDrawables();
        switch(getLayoutDirection()) {
            default:
            case LAYOUT_DIRECTION_LTR:
                return getCompoundPaddingLeft();
            case LAYOUT_DIRECTION_RTL:
                return getCompoundPaddingRight();
        }
    }

    /**
     * Returns the end padding of the view, plus space for the end
     * Drawable if any.
     */
    public int getCompoundPaddingEnd() {
        resolveDrawables();
        switch(getLayoutDirection()) {
            default:
            case LAYOUT_DIRECTION_LTR:
                return getCompoundPaddingRight();
            case LAYOUT_DIRECTION_RTL:
                return getCompoundPaddingLeft();
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

        if (mLayout == null) {
            assumeLayout();
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

        if (mLayout == null) {
            assumeLayout();
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
     * Returns the total start padding of the view, including the start
     * Drawable if any.
     */
    public int getTotalPaddingStart() {
        return getCompoundPaddingStart();
    }

    /**
     * Returns the total end padding of the view, including the end
     * Drawable if any.
     */
    public int getTotalPaddingEnd() {
        return getCompoundPaddingEnd();
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
     * Sets the Drawables (if any) to appear to the left of, above, to the
     * right of, and below the text. Use {@code null} if you do not want a
     * Drawable there. The Drawables must already have had
     * {@link Drawable#setBounds} called.
     * <p>
     * Calling this method will overwrite any Drawables previously set using
     * {@link #setCompoundDrawablesRelative} or related methods.
     *
     * @attr ref android.R.styleable#TextView_drawableLeft
     * @attr ref android.R.styleable#TextView_drawableTop
     * @attr ref android.R.styleable#TextView_drawableRight
     * @attr ref android.R.styleable#TextView_drawableBottom
     */
    public void setCompoundDrawables(@Nullable Drawable left, @Nullable Drawable top,
            @Nullable Drawable right, @Nullable Drawable bottom) {
        Drawables dr = mDrawables;

        // We're switching to absolute, discard relative.
        if (dr != null) {
            if (dr.mDrawableStart != null) dr.mDrawableStart.setCallback(null);
            dr.mDrawableStart = null;
            if (dr.mDrawableEnd != null) dr.mDrawableEnd.setCallback(null);
            dr.mDrawableEnd = null;
            dr.mDrawableSizeStart = dr.mDrawableHeightStart = 0;
            dr.mDrawableSizeEnd = dr.mDrawableHeightEnd = 0;
        }

        final boolean drawables = left != null || top != null || right != null || bottom != null;
        if (!drawables) {
            // Clearing drawables...  can we free the data structure?
            if (dr != null) {
                if (!dr.hasMetadata()) {
                    mDrawables = null;
                } else {
                    // We need to retain the last set padding, so just clear
                    // out all of the fields in the existing structure.
                    for (int i = dr.mShowing.length - 1; i >= 0; i--) {
                        if (dr.mShowing[i] != null) {
                            dr.mShowing[i].setCallback(null);
                        }
                        dr.mShowing[i] = null;
                    }
                    dr.mDrawableSizeLeft = dr.mDrawableHeightLeft = 0;
                    dr.mDrawableSizeRight = dr.mDrawableHeightRight = 0;
                    dr.mDrawableSizeTop = dr.mDrawableWidthTop = 0;
                    dr.mDrawableSizeBottom = dr.mDrawableWidthBottom = 0;
                }
            }
        } else {
            if (dr == null) {
                mDrawables = dr = new Drawables(getContext());
            }

            mDrawables.mOverride = false;

            if (dr.mShowing[Drawables.LEFT] != left && dr.mShowing[Drawables.LEFT] != null) {
                dr.mShowing[Drawables.LEFT].setCallback(null);
            }
            dr.mShowing[Drawables.LEFT] = left;

            if (dr.mShowing[Drawables.TOP] != top && dr.mShowing[Drawables.TOP] != null) {
                dr.mShowing[Drawables.TOP].setCallback(null);
            }
            dr.mShowing[Drawables.TOP] = top;

            if (dr.mShowing[Drawables.RIGHT] != right && dr.mShowing[Drawables.RIGHT] != null) {
                dr.mShowing[Drawables.RIGHT].setCallback(null);
            }
            dr.mShowing[Drawables.RIGHT] = right;

            if (dr.mShowing[Drawables.BOTTOM] != bottom && dr.mShowing[Drawables.BOTTOM] != null) {
                dr.mShowing[Drawables.BOTTOM].setCallback(null);
            }
            dr.mShowing[Drawables.BOTTOM] = bottom;

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

        // Save initial left/right drawables
        if (dr != null) {
            dr.mDrawableLeftInitial = left;
            dr.mDrawableRightInitial = right;
        }

        resetResolvedDrawables();
        resolveDrawables();
        applyCompoundDrawableTint();
        invalidate();
        requestLayout();
    }

    /**
     * Sets the Drawables (if any) to appear to the left of, above, to the
     * right of, and below the text. Use 0 if you do not want a Drawable there.
     * The Drawables' bounds will be set to their intrinsic bounds.
     * <p>
     * Calling this method will overwrite any Drawables previously set using
     * {@link #setCompoundDrawablesRelative} or related methods.
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
    @android.view.RemotableViewMethod
    public void setCompoundDrawablesWithIntrinsicBounds(@DrawableRes int left,
            @DrawableRes int top, @DrawableRes int right, @DrawableRes int bottom) {
        final Context context = getContext();
        setCompoundDrawablesWithIntrinsicBounds(left != 0 ? context.getDrawable(left) : null,
                top != 0 ? context.getDrawable(top) : null,
                right != 0 ? context.getDrawable(right) : null,
                bottom != 0 ? context.getDrawable(bottom) : null);
    }

    /**
     * Sets the Drawables (if any) to appear to the left of, above, to the
     * right of, and below the text. Use {@code null} if you do not want a
     * Drawable there. The Drawables' bounds will be set to their intrinsic
     * bounds.
     * <p>
     * Calling this method will overwrite any Drawables previously set using
     * {@link #setCompoundDrawablesRelative} or related methods.
     *
     * @attr ref android.R.styleable#TextView_drawableLeft
     * @attr ref android.R.styleable#TextView_drawableTop
     * @attr ref android.R.styleable#TextView_drawableRight
     * @attr ref android.R.styleable#TextView_drawableBottom
     */
    @android.view.RemotableViewMethod
    public void setCompoundDrawablesWithIntrinsicBounds(@Nullable Drawable left,
            @Nullable Drawable top, @Nullable Drawable right, @Nullable Drawable bottom) {

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
     * Sets the Drawables (if any) to appear to the start of, above, to the end
     * of, and below the text. Use {@code null} if you do not want a Drawable
     * there. The Drawables must already have had {@link Drawable#setBounds}
     * called.
     * <p>
     * Calling this method will overwrite any Drawables previously set using
     * {@link #setCompoundDrawables} or related methods.
     *
     * @attr ref android.R.styleable#TextView_drawableStart
     * @attr ref android.R.styleable#TextView_drawableTop
     * @attr ref android.R.styleable#TextView_drawableEnd
     * @attr ref android.R.styleable#TextView_drawableBottom
     */
    @android.view.RemotableViewMethod
    public void setCompoundDrawablesRelative(@Nullable Drawable start, @Nullable Drawable top,
            @Nullable Drawable end, @Nullable Drawable bottom) {
        Drawables dr = mDrawables;

        // We're switching to relative, discard absolute.
        if (dr != null) {
            if (dr.mShowing[Drawables.LEFT] != null) {
                dr.mShowing[Drawables.LEFT].setCallback(null);
            }
            dr.mShowing[Drawables.LEFT] = dr.mDrawableLeftInitial = null;
            if (dr.mShowing[Drawables.RIGHT] != null) {
                dr.mShowing[Drawables.RIGHT].setCallback(null);
            }
            dr.mShowing[Drawables.RIGHT] = dr.mDrawableRightInitial = null;
            dr.mDrawableSizeLeft = dr.mDrawableHeightLeft = 0;
            dr.mDrawableSizeRight = dr.mDrawableHeightRight = 0;
        }

        final boolean drawables = start != null || top != null
                || end != null || bottom != null;

        if (!drawables) {
            // Clearing drawables...  can we free the data structure?
            if (dr != null) {
                if (!dr.hasMetadata()) {
                    mDrawables = null;
                } else {
                    // We need to retain the last set padding, so just clear
                    // out all of the fields in the existing structure.
                    if (dr.mDrawableStart != null) dr.mDrawableStart.setCallback(null);
                    dr.mDrawableStart = null;
                    if (dr.mShowing[Drawables.TOP] != null) {
                        dr.mShowing[Drawables.TOP].setCallback(null);
                    }
                    dr.mShowing[Drawables.TOP] = null;
                    if (dr.mDrawableEnd != null) {
                        dr.mDrawableEnd.setCallback(null);
                    }
                    dr.mDrawableEnd = null;
                    if (dr.mShowing[Drawables.BOTTOM] != null) {
                        dr.mShowing[Drawables.BOTTOM].setCallback(null);
                    }
                    dr.mShowing[Drawables.BOTTOM] = null;
                    dr.mDrawableSizeStart = dr.mDrawableHeightStart = 0;
                    dr.mDrawableSizeEnd = dr.mDrawableHeightEnd = 0;
                    dr.mDrawableSizeTop = dr.mDrawableWidthTop = 0;
                    dr.mDrawableSizeBottom = dr.mDrawableWidthBottom = 0;
                }
            }
        } else {
            if (dr == null) {
                mDrawables = dr = new Drawables(getContext());
            }

            mDrawables.mOverride = true;

            if (dr.mDrawableStart != start && dr.mDrawableStart != null) {
                dr.mDrawableStart.setCallback(null);
            }
            dr.mDrawableStart = start;

            if (dr.mShowing[Drawables.TOP] != top && dr.mShowing[Drawables.TOP] != null) {
                dr.mShowing[Drawables.TOP].setCallback(null);
            }
            dr.mShowing[Drawables.TOP] = top;

            if (dr.mDrawableEnd != end && dr.mDrawableEnd != null) {
                dr.mDrawableEnd.setCallback(null);
            }
            dr.mDrawableEnd = end;

            if (dr.mShowing[Drawables.BOTTOM] != bottom && dr.mShowing[Drawables.BOTTOM] != null) {
                dr.mShowing[Drawables.BOTTOM].setCallback(null);
            }
            dr.mShowing[Drawables.BOTTOM] = bottom;

            final Rect compoundRect = dr.mCompoundRect;
            int[] state;

            state = getDrawableState();

            if (start != null) {
                start.setState(state);
                start.copyBounds(compoundRect);
                start.setCallback(this);
                dr.mDrawableSizeStart = compoundRect.width();
                dr.mDrawableHeightStart = compoundRect.height();
            } else {
                dr.mDrawableSizeStart = dr.mDrawableHeightStart = 0;
            }

            if (end != null) {
                end.setState(state);
                end.copyBounds(compoundRect);
                end.setCallback(this);
                dr.mDrawableSizeEnd = compoundRect.width();
                dr.mDrawableHeightEnd = compoundRect.height();
            } else {
                dr.mDrawableSizeEnd = dr.mDrawableHeightEnd = 0;
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

        resetResolvedDrawables();
        resolveDrawables();
        invalidate();
        requestLayout();
    }

    /**
     * Sets the Drawables (if any) to appear to the start of, above, to the end
     * of, and below the text. Use 0 if you do not want a Drawable there. The
     * Drawables' bounds will be set to their intrinsic bounds.
     * <p>
     * Calling this method will overwrite any Drawables previously set using
     * {@link #setCompoundDrawables} or related methods.
     *
     * @param start Resource identifier of the start Drawable.
     * @param top Resource identifier of the top Drawable.
     * @param end Resource identifier of the end Drawable.
     * @param bottom Resource identifier of the bottom Drawable.
     *
     * @attr ref android.R.styleable#TextView_drawableStart
     * @attr ref android.R.styleable#TextView_drawableTop
     * @attr ref android.R.styleable#TextView_drawableEnd
     * @attr ref android.R.styleable#TextView_drawableBottom
     */
    @android.view.RemotableViewMethod
    public void setCompoundDrawablesRelativeWithIntrinsicBounds(@DrawableRes int start,
            @DrawableRes int top, @DrawableRes int end, @DrawableRes int bottom) {
        final Context context = getContext();
        setCompoundDrawablesRelativeWithIntrinsicBounds(
                start != 0 ? context.getDrawable(start) : null,
                top != 0 ? context.getDrawable(top) : null,
                end != 0 ? context.getDrawable(end) : null,
                bottom != 0 ? context.getDrawable(bottom) : null);
    }

    /**
     * Sets the Drawables (if any) to appear to the start of, above, to the end
     * of, and below the text. Use {@code null} if you do not want a Drawable
     * there. The Drawables' bounds will be set to their intrinsic bounds.
     * <p>
     * Calling this method will overwrite any Drawables previously set using
     * {@link #setCompoundDrawables} or related methods.
     *
     * @attr ref android.R.styleable#TextView_drawableStart
     * @attr ref android.R.styleable#TextView_drawableTop
     * @attr ref android.R.styleable#TextView_drawableEnd
     * @attr ref android.R.styleable#TextView_drawableBottom
     */
    @android.view.RemotableViewMethod
    public void setCompoundDrawablesRelativeWithIntrinsicBounds(@Nullable Drawable start,
            @Nullable Drawable top, @Nullable Drawable end, @Nullable Drawable bottom) {

        if (start != null) {
            start.setBounds(0, 0, start.getIntrinsicWidth(), start.getIntrinsicHeight());
        }
        if (end != null) {
            end.setBounds(0, 0, end.getIntrinsicWidth(), end.getIntrinsicHeight());
        }
        if (top != null) {
            top.setBounds(0, 0, top.getIntrinsicWidth(), top.getIntrinsicHeight());
        }
        if (bottom != null) {
            bottom.setBounds(0, 0, bottom.getIntrinsicWidth(), bottom.getIntrinsicHeight());
        }
        setCompoundDrawablesRelative(start, top, end, bottom);
    }

    /**
     * Returns drawables for the left, top, right, and bottom borders.
     *
     * @attr ref android.R.styleable#TextView_drawableLeft
     * @attr ref android.R.styleable#TextView_drawableTop
     * @attr ref android.R.styleable#TextView_drawableRight
     * @attr ref android.R.styleable#TextView_drawableBottom
     */
    @NonNull
    public Drawable[] getCompoundDrawables() {
        final Drawables dr = mDrawables;
        if (dr != null) {
            return dr.mShowing.clone();
        } else {
            return new Drawable[] { null, null, null, null };
        }
    }

    /**
     * Returns drawables for the start, top, end, and bottom borders.
     *
     * @attr ref android.R.styleable#TextView_drawableStart
     * @attr ref android.R.styleable#TextView_drawableTop
     * @attr ref android.R.styleable#TextView_drawableEnd
     * @attr ref android.R.styleable#TextView_drawableBottom
     */
    @NonNull
    public Drawable[] getCompoundDrawablesRelative() {
        final Drawables dr = mDrawables;
        if (dr != null) {
            return new Drawable[] {
                dr.mDrawableStart, dr.mShowing[Drawables.TOP],
                dr.mDrawableEnd, dr.mShowing[Drawables.BOTTOM]
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
    @android.view.RemotableViewMethod
    public void setCompoundDrawablePadding(int pad) {
        Drawables dr = mDrawables;
        if (pad == 0) {
            if (dr != null) {
                dr.mDrawablePadding = pad;
            }
        } else {
            if (dr == null) {
                mDrawables = dr = new Drawables(getContext());
            }
            dr.mDrawablePadding = pad;
        }

        invalidate();
        requestLayout();
    }

    /**
     * Returns the padding between the compound drawables and the text.
     *
     * @attr ref android.R.styleable#TextView_drawablePadding
     */
    @InspectableProperty(name = "drawablePadding")
    public int getCompoundDrawablePadding() {
        final Drawables dr = mDrawables;
        return dr != null ? dr.mDrawablePadding : 0;
    }

    /**
     * Applies a tint to the compound drawables. Does not modify the
     * current tint mode, which is {@link BlendMode#SRC_IN} by default.
     * <p>
     * Subsequent calls to
     * {@link #setCompoundDrawables(Drawable, Drawable, Drawable, Drawable)}
     * and related methods will automatically mutate the drawables and apply
     * the specified tint and tint mode using
     * {@link Drawable#setTintList(ColorStateList)}.
     *
     * @param tint the tint to apply, may be {@code null} to clear tint
     *
     * @attr ref android.R.styleable#TextView_drawableTint
     * @see #getCompoundDrawableTintList()
     * @see Drawable#setTintList(ColorStateList)
     */
    public void setCompoundDrawableTintList(@Nullable ColorStateList tint) {
        if (mDrawables == null) {
            mDrawables = new Drawables(getContext());
        }
        mDrawables.mTintList = tint;
        mDrawables.mHasTint = true;

        applyCompoundDrawableTint();
    }

    /**
     * @return the tint applied to the compound drawables
     * @attr ref android.R.styleable#TextView_drawableTint
     * @see #setCompoundDrawableTintList(ColorStateList)
     */
    @InspectableProperty(name = "drawableTint")
    public ColorStateList getCompoundDrawableTintList() {
        return mDrawables != null ? mDrawables.mTintList : null;
    }

    /**
     * Specifies the blending mode used to apply the tint specified by
     * {@link #setCompoundDrawableTintList(ColorStateList)} to the compound
     * drawables. The default mode is {@link PorterDuff.Mode#SRC_IN}.
     *
     * @param tintMode the blending mode used to apply the tint, may be
     *                 {@code null} to clear tint
     * @attr ref android.R.styleable#TextView_drawableTintMode
     * @see #setCompoundDrawableTintList(ColorStateList)
     * @see Drawable#setTintMode(PorterDuff.Mode)
     */
    public void setCompoundDrawableTintMode(@Nullable PorterDuff.Mode tintMode) {
        setCompoundDrawableTintBlendMode(tintMode != null
                ? BlendMode.fromValue(tintMode.nativeInt) : null);
    }

    /**
     * Specifies the blending mode used to apply the tint specified by
     * {@link #setCompoundDrawableTintList(ColorStateList)} to the compound
     * drawables. The default mode is {@link PorterDuff.Mode#SRC_IN}.
     *
     * @param blendMode the blending mode used to apply the tint, may be
     *                 {@code null} to clear tint
     * @attr ref android.R.styleable#TextView_drawableTintMode
     * @see #setCompoundDrawableTintList(ColorStateList)
     * @see Drawable#setTintBlendMode(BlendMode)
     */
    public void setCompoundDrawableTintBlendMode(@Nullable BlendMode blendMode) {
        if (mDrawables == null) {
            mDrawables = new Drawables(getContext());
        }
        mDrawables.mBlendMode = blendMode;
        mDrawables.mHasTintMode = true;

        applyCompoundDrawableTint();
    }

    /**
     * Returns the blending mode used to apply the tint to the compound
     * drawables, if specified.
     *
     * @return the blending mode used to apply the tint to the compound
     *         drawables
     * @attr ref android.R.styleable#TextView_drawableTintMode
     * @see #setCompoundDrawableTintMode(PorterDuff.Mode)
     *
     */
    @InspectableProperty(name = "drawableTintMode")
    public PorterDuff.Mode getCompoundDrawableTintMode() {
        BlendMode mode = getCompoundDrawableTintBlendMode();
        return mode != null ? BlendMode.blendModeToPorterDuffMode(mode) : null;
    }

    /**
     * Returns the blending mode used to apply the tint to the compound
     * drawables, if specified.
     *
     * @return the blending mode used to apply the tint to the compound
     *         drawables
     * @attr ref android.R.styleable#TextView_drawableTintMode
     * @see #setCompoundDrawableTintBlendMode(BlendMode)
     */
    @InspectableProperty(name = "drawableBlendMode",
            attributeId = com.android.internal.R.styleable.TextView_drawableTintMode)
    public @Nullable BlendMode getCompoundDrawableTintBlendMode() {
        return mDrawables != null ? mDrawables.mBlendMode : null;
    }

    private void applyCompoundDrawableTint() {
        if (mDrawables == null) {
            return;
        }

        if (mDrawables.mHasTint || mDrawables.mHasTintMode) {
            final ColorStateList tintList = mDrawables.mTintList;
            final BlendMode blendMode = mDrawables.mBlendMode;
            final boolean hasTint = mDrawables.mHasTint;
            final boolean hasTintMode = mDrawables.mHasTintMode;
            final int[] state = getDrawableState();

            for (Drawable dr : mDrawables.mShowing) {
                if (dr == null) {
                    continue;
                }

                if (dr == mDrawables.mDrawableError) {
                    // From a developer's perspective, the error drawable isn't
                    // a compound drawable. Don't apply the generic compound
                    // drawable tint to it.
                    continue;
                }

                dr.mutate();

                if (hasTint) {
                    dr.setTintList(tintList);
                }

                if (hasTintMode) {
                    dr.setTintBlendMode(blendMode);
                }

                // The drawable (or one of its children) may not have been
                // stateful before applying the tint, so let's try again.
                if (dr.isStateful()) {
                    dr.setState(state);
                }
            }
        }
    }

    /**
     * @inheritDoc
     *
     * @see #setFirstBaselineToTopHeight(int)
     * @see #setLastBaselineToBottomHeight(int)
     */
    @Override
    public void setPadding(int left, int top, int right, int bottom) {
        if (left != mPaddingLeft
                || right != mPaddingRight
                || top != mPaddingTop
                ||  bottom != mPaddingBottom) {
            nullLayouts();
        }

        // the super call will requestLayout()
        super.setPadding(left, top, right, bottom);
        invalidate();
    }

    /**
     * @inheritDoc
     *
     * @see #setFirstBaselineToTopHeight(int)
     * @see #setLastBaselineToBottomHeight(int)
     */
    @Override
    public void setPaddingRelative(int start, int top, int end, int bottom) {
        if (start != getPaddingStart()
                || end != getPaddingEnd()
                || top != mPaddingTop
                || bottom != mPaddingBottom) {
            nullLayouts();
        }

        // the super call will requestLayout()
        super.setPaddingRelative(start, top, end, bottom);
        invalidate();
    }

    /**
     * Updates the top padding of the TextView so that {@code firstBaselineToTopHeight} is
     * the distance between the top of the TextView and first line's baseline.
     * <p>
     * <img src="{@docRoot}reference/android/images/text/widget/first_last_baseline.png" />
     * <figcaption>First and last baseline metrics for a TextView.</figcaption>
     *
     * <strong>Note</strong> that if {@code FontMetrics.top} or {@code FontMetrics.ascent} was
     * already greater than {@code firstBaselineToTopHeight}, the top padding is not updated.
     * Moreover since this function sets the top padding, if the height of the TextView is less than
     * the sum of top padding, line height and bottom padding, top of the line will be pushed
     * down and bottom will be clipped.
     *
     * @param firstBaselineToTopHeight distance between first baseline to top of the container
     *      in pixels
     *
     * @see #getFirstBaselineToTopHeight()
     * @see #setLastBaselineToBottomHeight(int)
     * @see #setPadding(int, int, int, int)
     * @see #setPaddingRelative(int, int, int, int)
     *
     * @attr ref android.R.styleable#TextView_firstBaselineToTopHeight
     */
    public void setFirstBaselineToTopHeight(@Px @IntRange(from = 0) int firstBaselineToTopHeight) {
        Preconditions.checkArgumentNonnegative(firstBaselineToTopHeight);

        final FontMetricsInt fontMetrics = getPaint().getFontMetricsInt();
        final int fontMetricsTop;
        if (getIncludeFontPadding()) {
            fontMetricsTop = fontMetrics.top;
        } else {
            fontMetricsTop = fontMetrics.ascent;
        }

        // TODO: Decide if we want to ignore density ratio (i.e. when the user changes font size
        // in settings). At the moment, we don't.

        if (firstBaselineToTopHeight > Math.abs(fontMetricsTop)) {
            final int paddingTop = firstBaselineToTopHeight - (-fontMetricsTop);
            setPadding(getPaddingLeft(), paddingTop, getPaddingRight(), getPaddingBottom());
        }
    }

    /**
     * Updates the bottom padding of the TextView so that {@code lastBaselineToBottomHeight} is
     * the distance between the bottom of the TextView and the last line's baseline.
     * <p>
     * <img src="{@docRoot}reference/android/images/text/widget/first_last_baseline.png" />
     * <figcaption>First and last baseline metrics for a TextView.</figcaption>
     *
     * <strong>Note</strong> that if {@code FontMetrics.bottom} or {@code FontMetrics.descent} was
     * already greater than {@code lastBaselineToBottomHeight}, the bottom padding is not updated.
     * Moreover since this function sets the bottom padding, if the height of the TextView is less
     * than the sum of top padding, line height and bottom padding, bottom of the text will be
     * clipped.
     *
     * @param lastBaselineToBottomHeight distance between last baseline to bottom of the container
     *      in pixels
     *
     * @see #getLastBaselineToBottomHeight()
     * @see #setFirstBaselineToTopHeight(int)
     * @see #setPadding(int, int, int, int)
     * @see #setPaddingRelative(int, int, int, int)
     *
     * @attr ref android.R.styleable#TextView_lastBaselineToBottomHeight
     */
    public void setLastBaselineToBottomHeight(
            @Px @IntRange(from = 0) int lastBaselineToBottomHeight) {
        Preconditions.checkArgumentNonnegative(lastBaselineToBottomHeight);

        final FontMetricsInt fontMetrics = getPaint().getFontMetricsInt();
        final int fontMetricsBottom;
        if (getIncludeFontPadding()) {
            fontMetricsBottom = fontMetrics.bottom;
        } else {
            fontMetricsBottom = fontMetrics.descent;
        }

        // TODO: Decide if we want to ignore density ratio (i.e. when the user changes font size
        // in settings). At the moment, we don't.

        if (lastBaselineToBottomHeight > Math.abs(fontMetricsBottom)) {
            final int paddingBottom = lastBaselineToBottomHeight - fontMetricsBottom;
            setPadding(getPaddingLeft(), getPaddingTop(), getPaddingRight(), paddingBottom);
        }
    }

    /**
     * Returns the distance between the first text baseline and the top of this TextView.
     *
     * @see #setFirstBaselineToTopHeight(int)
     * @attr ref android.R.styleable#TextView_firstBaselineToTopHeight
     */
    @InspectableProperty
    public int getFirstBaselineToTopHeight() {
        return getPaddingTop() - getPaint().getFontMetricsInt().top;
    }

    /**
     * Returns the distance between the last text baseline and the bottom of this TextView.
     *
     * @see #setLastBaselineToBottomHeight(int)
     * @attr ref android.R.styleable#TextView_lastBaselineToBottomHeight
     */
    @InspectableProperty
    public int getLastBaselineToBottomHeight() {
        return getPaddingBottom() + getPaint().getFontMetricsInt().bottom;
    }

    /**
     * Gets the autolink mask of the text.
     *
     * See {@link Linkify#ALL} and peers for possible values.
     *
     * @attr ref android.R.styleable#TextView_autoLink
     */
    @InspectableProperty(name = "autoLink", flagMapping = {
            @FlagEntry(name = "web", target = Linkify.WEB_URLS),
            @FlagEntry(name = "email", target = Linkify.EMAIL_ADDRESSES),
            @FlagEntry(name = "phone", target = Linkify.PHONE_NUMBERS),
            @FlagEntry(name = "map", target = Linkify.MAP_ADDRESSES)
    })
    public final int getAutoLinkMask() {
        return mAutoLinkMask;
    }

    /**
     * Sets the Drawable corresponding to the selection handle used for
     * positioning the cursor within text. The Drawable defaults to the value
     * of the textSelectHandle attribute.
     * Note that any change applied to the handle Drawable will not be visible
     * until the handle is hidden and then drawn again.
     *
     * @see #setTextSelectHandle(int)
     * @attr ref android.R.styleable#TextView_textSelectHandle
     */
    @android.view.RemotableViewMethod
    public void setTextSelectHandle(@NonNull Drawable textSelectHandle) {
        Preconditions.checkNotNull(textSelectHandle,
                "The text select handle should not be null.");
        mTextSelectHandle = textSelectHandle;
        mTextSelectHandleRes = 0;
        if (mEditor != null) {
            mEditor.loadHandleDrawables(true /* overwrite */);
        }
    }

    /**
     * Sets the Drawable corresponding to the selection handle used for
     * positioning the cursor within text. The Drawable defaults to the value
     * of the textSelectHandle attribute.
     * Note that any change applied to the handle Drawable will not be visible
     * until the handle is hidden and then drawn again.
     *
     * @see #setTextSelectHandle(Drawable)
     * @attr ref android.R.styleable#TextView_textSelectHandle
     */
    @android.view.RemotableViewMethod
    public void setTextSelectHandle(@DrawableRes int textSelectHandle) {
        Preconditions.checkArgument(textSelectHandle != 0,
                "The text select handle should be a valid drawable resource id.");
        setTextSelectHandle(mContext.getDrawable(textSelectHandle));
    }

    /**
     * Returns the Drawable corresponding to the selection handle used
     * for positioning the cursor within text.
     * Note that any change applied to the handle Drawable will not be visible
     * until the handle is hidden and then drawn again.
     *
     * @return the text select handle drawable
     *
     * @see #setTextSelectHandle(Drawable)
     * @see #setTextSelectHandle(int)
     * @attr ref android.R.styleable#TextView_textSelectHandle
     */
    @Nullable public Drawable getTextSelectHandle() {
        if (mTextSelectHandle == null && mTextSelectHandleRes != 0) {
            mTextSelectHandle = mContext.getDrawable(mTextSelectHandleRes);
        }
        return mTextSelectHandle;
    }

    /**
     * Sets the Drawable corresponding to the left handle used
     * for selecting text. The Drawable defaults to the value of the
     * textSelectHandleLeft attribute.
     * Note that any change applied to the handle Drawable will not be visible
     * until the handle is hidden and then drawn again.
     *
     * @see #setTextSelectHandleLeft(int)
     * @attr ref android.R.styleable#TextView_textSelectHandleLeft
     */
    @android.view.RemotableViewMethod
    public void setTextSelectHandleLeft(@NonNull Drawable textSelectHandleLeft) {
        Preconditions.checkNotNull(textSelectHandleLeft,
                "The left text select handle should not be null.");
        mTextSelectHandleLeft = textSelectHandleLeft;
        mTextSelectHandleLeftRes = 0;
        if (mEditor != null) {
            mEditor.loadHandleDrawables(true /* overwrite */);
        }
    }

    /**
     * Sets the Drawable corresponding to the left handle used
     * for selecting text. The Drawable defaults to the value of the
     * textSelectHandleLeft attribute.
     * Note that any change applied to the handle Drawable will not be visible
     * until the handle is hidden and then drawn again.
     *
     * @see #setTextSelectHandleLeft(Drawable)
     * @attr ref android.R.styleable#TextView_textSelectHandleLeft
     */
    @android.view.RemotableViewMethod
    public void setTextSelectHandleLeft(@DrawableRes int textSelectHandleLeft) {
        Preconditions.checkArgument(textSelectHandleLeft != 0,
                "The text select left handle should be a valid drawable resource id.");
        setTextSelectHandleLeft(mContext.getDrawable(textSelectHandleLeft));
    }

    /**
     * Returns the Drawable corresponding to the left handle used
     * for selecting text.
     * Note that any change applied to the handle Drawable will not be visible
     * until the handle is hidden and then drawn again.
     *
     * @return the left text selection handle drawable
     *
     * @see #setTextSelectHandleLeft(Drawable)
     * @see #setTextSelectHandleLeft(int)
     * @attr ref android.R.styleable#TextView_textSelectHandleLeft
     */
    @Nullable public Drawable getTextSelectHandleLeft() {
        if (mTextSelectHandleLeft == null && mTextSelectHandleLeftRes != 0) {
            mTextSelectHandleLeft = mContext.getDrawable(mTextSelectHandleLeftRes);
        }
        return mTextSelectHandleLeft;
    }

    /**
     * Sets the Drawable corresponding to the right handle used
     * for selecting text. The Drawable defaults to the value of the
     * textSelectHandleRight attribute.
     * Note that any change applied to the handle Drawable will not be visible
     * until the handle is hidden and then drawn again.
     *
     * @see #setTextSelectHandleRight(int)
     * @attr ref android.R.styleable#TextView_textSelectHandleRight
     */
    @android.view.RemotableViewMethod
    public void setTextSelectHandleRight(@NonNull Drawable textSelectHandleRight) {
        Preconditions.checkNotNull(textSelectHandleRight,
                "The right text select handle should not be null.");
        mTextSelectHandleRight = textSelectHandleRight;
        mTextSelectHandleRightRes = 0;
        if (mEditor != null) {
            mEditor.loadHandleDrawables(true /* overwrite */);
        }
    }

    /**
     * Sets the Drawable corresponding to the right handle used
     * for selecting text. The Drawable defaults to the value of the
     * textSelectHandleRight attribute.
     * Note that any change applied to the handle Drawable will not be visible
     * until the handle is hidden and then drawn again.
     *
     * @see #setTextSelectHandleRight(Drawable)
     * @attr ref android.R.styleable#TextView_textSelectHandleRight
     */
    @android.view.RemotableViewMethod
    public void setTextSelectHandleRight(@DrawableRes int textSelectHandleRight) {
        Preconditions.checkArgument(textSelectHandleRight != 0,
                "The text select right handle should be a valid drawable resource id.");
        setTextSelectHandleRight(mContext.getDrawable(textSelectHandleRight));
    }

    /**
     * Returns the Drawable corresponding to the right handle used
     * for selecting text.
     * Note that any change applied to the handle Drawable will not be visible
     * until the handle is hidden and then drawn again.
     *
     * @return the right text selection handle drawable
     *
     * @see #setTextSelectHandleRight(Drawable)
     * @see #setTextSelectHandleRight(int)
     * @attr ref android.R.styleable#TextView_textSelectHandleRight
     */
    @Nullable public Drawable getTextSelectHandleRight() {
        if (mTextSelectHandleRight == null && mTextSelectHandleRightRes != 0) {
            mTextSelectHandleRight = mContext.getDrawable(mTextSelectHandleRightRes);
        }
        return mTextSelectHandleRight;
    }

    /**
     * Sets the Drawable corresponding to the text cursor. The Drawable defaults to the
     * value of the textCursorDrawable attribute.
     * Note that any change applied to the cursor Drawable will not be visible
     * until the cursor is hidden and then drawn again.
     *
     * @see #setTextCursorDrawable(int)
     * @attr ref android.R.styleable#TextView_textCursorDrawable
     */
    public void setTextCursorDrawable(@Nullable Drawable textCursorDrawable) {
        mCursorDrawable = textCursorDrawable;
        mCursorDrawableRes = 0;
        if (mEditor != null) {
            mEditor.loadCursorDrawable();
        }
    }

    /**
     * Sets the Drawable corresponding to the text cursor. The Drawable defaults to the
     * value of the textCursorDrawable attribute.
     * Note that any change applied to the cursor Drawable will not be visible
     * until the cursor is hidden and then drawn again.
     *
     * @see #setTextCursorDrawable(Drawable)
     * @attr ref android.R.styleable#TextView_textCursorDrawable
     */
    public void setTextCursorDrawable(@DrawableRes int textCursorDrawable) {
        setTextCursorDrawable(
                textCursorDrawable != 0 ? mContext.getDrawable(textCursorDrawable) : null);
    }

    /**
     * Returns the Drawable corresponding to the text cursor.
     * Note that any change applied to the cursor Drawable will not be visible
     * until the cursor is hidden and then drawn again.
     *
     * @return the text cursor drawable
     *
     * @see #setTextCursorDrawable(Drawable)
     * @see #setTextCursorDrawable(int)
     * @attr ref android.R.styleable#TextView_textCursorDrawable
     */
    @Nullable public Drawable getTextCursorDrawable() {
        if (mCursorDrawable == null && mCursorDrawableRes != 0) {
            mCursorDrawable = mContext.getDrawable(mCursorDrawableRes);
        }
        return mCursorDrawable;
    }

    /**
     * Sets the text appearance from the specified style resource.
     * <p>
     * Use a framework-defined {@code TextAppearance} style like
     * {@link android.R.style#TextAppearance_Material_Body1 @android:style/TextAppearance.Material.Body1}
     * or see {@link android.R.styleable#TextAppearance TextAppearance} for the
     * set of attributes that can be used in a custom style.
     *
     * @param resId the resource identifier of the style to apply
     * @attr ref android.R.styleable#TextView_textAppearance
     */
    @SuppressWarnings("deprecation")
    public void setTextAppearance(@StyleRes int resId) {
        setTextAppearance(mContext, resId);
    }

    /**
     * Sets the text color, size, style, hint color, and highlight color
     * from the specified TextAppearance resource.
     *
     * @deprecated Use {@link #setTextAppearance(int)} instead.
     */
    @Deprecated
    public void setTextAppearance(Context context, @StyleRes int resId) {
        final TypedArray ta = context.obtainStyledAttributes(resId, R.styleable.TextAppearance);
        final TextAppearanceAttributes attributes = new TextAppearanceAttributes();
        readTextAppearance(context, ta, attributes, false /* styleArray */);
        ta.recycle();
        applyTextAppearance(attributes);
    }

    /**
     * Set of attributes that can be defined in a Text Appearance. This is used to simplify the code
     * that reads these attributes in the constructor and in {@link #setTextAppearance}.
     */
    private static class TextAppearanceAttributes {
        int mTextColorHighlight = 0;
        int mSearchResultHighlightColor = 0;
        int mFocusedSearchResultHighlightColor = 0;
        ColorStateList mTextColor = null;
        ColorStateList mTextColorHint = null;
        ColorStateList mTextColorLink = null;
        int mTextSize = -1;
        int mTextSizeUnit = -1;
        LocaleList mTextLocales = null;
        String mFontFamily = null;
        Typeface mFontTypeface = null;
        boolean mFontFamilyExplicit = false;
        int mTypefaceIndex = -1;
        int mTextStyle = 0;
        int mFontWeight = FontStyle.FONT_WEIGHT_UNSPECIFIED;
        boolean mAllCaps = false;
        int mShadowColor = 0;
        float mShadowDx = 0, mShadowDy = 0, mShadowRadius = 0;
        boolean mHasElegant = false;
        boolean mElegant = false;
        boolean mHasFallbackLineSpacing = false;
        boolean mFallbackLineSpacing = false;
        boolean mHasLetterSpacing = false;
        float mLetterSpacing = 0;
        String mFontFeatureSettings = null;
        String mFontVariationSettings = null;
        boolean mHasLineBreakStyle = false;
        boolean mHasLineBreakWordStyle = false;
        int mLineBreakStyle = DEFAULT_LINE_BREAK_STYLE;
        int mLineBreakWordStyle = DEFAULT_LINE_BREAK_WORD_STYLE;

        @Override
        public String toString() {
            return "TextAppearanceAttributes {\n"
                    + "    mTextColorHighlight:" + mTextColorHighlight + "\n"
                    + "    mSearchResultHighlightColor: " + mSearchResultHighlightColor + "\n"
                    + "    mFocusedSearchResultHighlightColor: "
                    + mFocusedSearchResultHighlightColor + "\n"
                    + "    mTextColor:" + mTextColor + "\n"
                    + "    mTextColorHint:" + mTextColorHint + "\n"
                    + "    mTextColorLink:" + mTextColorLink + "\n"
                    + "    mTextSize:" + mTextSize + "\n"
                    + "    mTextSizeUnit:" + mTextSizeUnit + "\n"
                    + "    mTextLocales:" + mTextLocales + "\n"
                    + "    mFontFamily:" + mFontFamily + "\n"
                    + "    mFontTypeface:" + mFontTypeface + "\n"
                    + "    mFontFamilyExplicit:" + mFontFamilyExplicit + "\n"
                    + "    mTypefaceIndex:" + mTypefaceIndex + "\n"
                    + "    mTextStyle:" + mTextStyle + "\n"
                    + "    mFontWeight:" + mFontWeight + "\n"
                    + "    mAllCaps:" + mAllCaps + "\n"
                    + "    mShadowColor:" + mShadowColor + "\n"
                    + "    mShadowDx:" + mShadowDx + "\n"
                    + "    mShadowDy:" + mShadowDy + "\n"
                    + "    mShadowRadius:" + mShadowRadius + "\n"
                    + "    mHasElegant:" + mHasElegant + "\n"
                    + "    mElegant:" + mElegant + "\n"
                    + "    mHasFallbackLineSpacing:" + mHasFallbackLineSpacing + "\n"
                    + "    mFallbackLineSpacing:" + mFallbackLineSpacing + "\n"
                    + "    mHasLetterSpacing:" + mHasLetterSpacing + "\n"
                    + "    mLetterSpacing:" + mLetterSpacing + "\n"
                    + "    mFontFeatureSettings:" + mFontFeatureSettings + "\n"
                    + "    mFontVariationSettings:" + mFontVariationSettings + "\n"
                    + "    mHasLineBreakStyle:" + mHasLineBreakStyle + "\n"
                    + "    mHasLineBreakWordStyle:" + mHasLineBreakWordStyle + "\n"
                    + "    mLineBreakStyle:" + mLineBreakStyle + "\n"
                    + "    mLineBreakWordStyle:" + mLineBreakWordStyle + "\n"
                    + "}";
        }
    }

    // Maps styleable attributes that exist both in TextView style and TextAppearance.
    private static final SparseIntArray sAppearanceValues = new SparseIntArray();
    static {
        sAppearanceValues.put(com.android.internal.R.styleable.TextView_textColorHighlight,
                com.android.internal.R.styleable.TextAppearance_textColorHighlight);
        sAppearanceValues.put(com.android.internal.R.styleable.TextView_searchResultHighlightColor,
                com.android.internal.R.styleable.TextAppearance_searchResultHighlightColor);
        sAppearanceValues.put(
                com.android.internal.R.styleable.TextView_focusedSearchResultHighlightColor,
                com.android.internal.R.styleable.TextAppearance_focusedSearchResultHighlightColor);
        sAppearanceValues.put(com.android.internal.R.styleable.TextView_textColor,
                com.android.internal.R.styleable.TextAppearance_textColor);
        sAppearanceValues.put(com.android.internal.R.styleable.TextView_textColorHint,
                com.android.internal.R.styleable.TextAppearance_textColorHint);
        sAppearanceValues.put(com.android.internal.R.styleable.TextView_textColorLink,
                com.android.internal.R.styleable.TextAppearance_textColorLink);
        sAppearanceValues.put(com.android.internal.R.styleable.TextView_textSize,
                com.android.internal.R.styleable.TextAppearance_textSize);
        sAppearanceValues.put(com.android.internal.R.styleable.TextView_textLocale,
                com.android.internal.R.styleable.TextAppearance_textLocale);
        sAppearanceValues.put(com.android.internal.R.styleable.TextView_typeface,
                com.android.internal.R.styleable.TextAppearance_typeface);
        sAppearanceValues.put(com.android.internal.R.styleable.TextView_fontFamily,
                com.android.internal.R.styleable.TextAppearance_fontFamily);
        sAppearanceValues.put(com.android.internal.R.styleable.TextView_textStyle,
                com.android.internal.R.styleable.TextAppearance_textStyle);
        sAppearanceValues.put(com.android.internal.R.styleable.TextView_textFontWeight,
                com.android.internal.R.styleable.TextAppearance_textFontWeight);
        sAppearanceValues.put(com.android.internal.R.styleable.TextView_textAllCaps,
                com.android.internal.R.styleable.TextAppearance_textAllCaps);
        sAppearanceValues.put(com.android.internal.R.styleable.TextView_shadowColor,
                com.android.internal.R.styleable.TextAppearance_shadowColor);
        sAppearanceValues.put(com.android.internal.R.styleable.TextView_shadowDx,
                com.android.internal.R.styleable.TextAppearance_shadowDx);
        sAppearanceValues.put(com.android.internal.R.styleable.TextView_shadowDy,
                com.android.internal.R.styleable.TextAppearance_shadowDy);
        sAppearanceValues.put(com.android.internal.R.styleable.TextView_shadowRadius,
                com.android.internal.R.styleable.TextAppearance_shadowRadius);
        sAppearanceValues.put(com.android.internal.R.styleable.TextView_elegantTextHeight,
                com.android.internal.R.styleable.TextAppearance_elegantTextHeight);
        sAppearanceValues.put(com.android.internal.R.styleable.TextView_fallbackLineSpacing,
                com.android.internal.R.styleable.TextAppearance_fallbackLineSpacing);
        sAppearanceValues.put(com.android.internal.R.styleable.TextView_letterSpacing,
                com.android.internal.R.styleable.TextAppearance_letterSpacing);
        sAppearanceValues.put(com.android.internal.R.styleable.TextView_fontFeatureSettings,
                com.android.internal.R.styleable.TextAppearance_fontFeatureSettings);
        sAppearanceValues.put(com.android.internal.R.styleable.TextView_fontVariationSettings,
                com.android.internal.R.styleable.TextAppearance_fontVariationSettings);
        sAppearanceValues.put(com.android.internal.R.styleable.TextView_lineBreakStyle,
                com.android.internal.R.styleable.TextAppearance_lineBreakStyle);
        sAppearanceValues.put(com.android.internal.R.styleable.TextView_lineBreakWordStyle,
                com.android.internal.R.styleable.TextAppearance_lineBreakWordStyle);
    }

    /**
     * Read the Text Appearance attributes from a given TypedArray and set its values to the given
     * set. If the TypedArray contains a value that was already set in the given attributes, that
     * will be overridden.
     *
     * @param context The Context to be used
     * @param appearance The TypedArray to read properties from
     * @param attributes the TextAppearanceAttributes to fill in
     * @param styleArray Whether the given TypedArray is a style or a TextAppearance. This defines
     *                   what attribute indexes will be used to read the properties.
     */
    private void readTextAppearance(Context context, TypedArray appearance,
            TextAppearanceAttributes attributes, boolean styleArray) {
        final int n = appearance.getIndexCount();
        for (int i = 0; i < n; i++) {
            final int attr = appearance.getIndex(i);
            int index = attr;
            // Translate style array index ids to TextAppearance ids.
            if (styleArray) {
                index = sAppearanceValues.get(attr, -1);
                if (index == -1) {
                    // This value is not part of a Text Appearance and should be ignored.
                    continue;
                }
            }
            switch (index) {
                case com.android.internal.R.styleable.TextAppearance_textColorHighlight:
                    attributes.mTextColorHighlight =
                            appearance.getColor(attr, attributes.mTextColorHighlight);
                    break;
                case com.android.internal.R.styleable.TextAppearance_searchResultHighlightColor:
                    attributes.mSearchResultHighlightColor =
                            appearance.getColor(attr, attributes.mSearchResultHighlightColor);
                    break;
                case com.android.internal.R.styleable
                        .TextAppearance_focusedSearchResultHighlightColor:
                    attributes.mFocusedSearchResultHighlightColor =
                            appearance.getColor(attr,
                                    attributes.mFocusedSearchResultHighlightColor);
                    break;
                case com.android.internal.R.styleable.TextAppearance_textColor:
                    attributes.mTextColor = appearance.getColorStateList(attr);
                    break;
                case com.android.internal.R.styleable.TextAppearance_textColorHint:
                    attributes.mTextColorHint = appearance.getColorStateList(attr);
                    break;
                case com.android.internal.R.styleable.TextAppearance_textColorLink:
                    attributes.mTextColorLink = appearance.getColorStateList(attr);
                    break;
                case com.android.internal.R.styleable.TextAppearance_textSize:
                    attributes.mTextSize =
                            appearance.getDimensionPixelSize(attr, attributes.mTextSize);
                    attributes.mTextSizeUnit = appearance.peekValue(attr).getComplexUnit();
                    break;
                case com.android.internal.R.styleable.TextAppearance_textLocale:
                    final String localeString = appearance.getString(attr);
                    if (localeString != null) {
                        final LocaleList localeList = LocaleList.forLanguageTags(localeString);
                        if (!localeList.isEmpty()) {
                            attributes.mTextLocales = localeList;
                        }
                    }
                    break;
                case com.android.internal.R.styleable.TextAppearance_typeface:
                    attributes.mTypefaceIndex = appearance.getInt(attr, attributes.mTypefaceIndex);
                    if (attributes.mTypefaceIndex != -1 && !attributes.mFontFamilyExplicit) {
                        attributes.mFontFamily = null;
                    }
                    break;
                case com.android.internal.R.styleable.TextAppearance_fontFamily:
                    if (!context.isRestricted() && context.canLoadUnsafeResources()) {
                        try {
                            attributes.mFontTypeface = appearance.getFont(attr);
                        } catch (UnsupportedOperationException | Resources.NotFoundException e) {
                            // Expected if it is not a font resource.
                        }
                    }
                    if (attributes.mFontTypeface == null) {
                        attributes.mFontFamily = appearance.getString(attr);
                    }
                    attributes.mFontFamilyExplicit = true;
                    break;
                case com.android.internal.R.styleable.TextAppearance_textStyle:
                    attributes.mTextStyle = appearance.getInt(attr, attributes.mTextStyle);
                    break;
                case com.android.internal.R.styleable.TextAppearance_textFontWeight:
                    attributes.mFontWeight = appearance.getInt(attr, attributes.mFontWeight);
                    break;
                case com.android.internal.R.styleable.TextAppearance_textAllCaps:
                    attributes.mAllCaps = appearance.getBoolean(attr, attributes.mAllCaps);
                    break;
                case com.android.internal.R.styleable.TextAppearance_shadowColor:
                    attributes.mShadowColor = appearance.getInt(attr, attributes.mShadowColor);
                    break;
                case com.android.internal.R.styleable.TextAppearance_shadowDx:
                    attributes.mShadowDx = appearance.getFloat(attr, attributes.mShadowDx);
                    break;
                case com.android.internal.R.styleable.TextAppearance_shadowDy:
                    attributes.mShadowDy = appearance.getFloat(attr, attributes.mShadowDy);
                    break;
                case com.android.internal.R.styleable.TextAppearance_shadowRadius:
                    attributes.mShadowRadius = appearance.getFloat(attr, attributes.mShadowRadius);
                    break;
                case com.android.internal.R.styleable.TextAppearance_elegantTextHeight:
                    attributes.mHasElegant = true;
                    attributes.mElegant = appearance.getBoolean(attr, attributes.mElegant);
                    break;
                case com.android.internal.R.styleable.TextAppearance_fallbackLineSpacing:
                    attributes.mHasFallbackLineSpacing = true;
                    attributes.mFallbackLineSpacing = appearance.getBoolean(attr,
                            attributes.mFallbackLineSpacing);
                    break;
                case com.android.internal.R.styleable.TextAppearance_letterSpacing:
                    attributes.mHasLetterSpacing = true;
                    attributes.mLetterSpacing =
                            appearance.getFloat(attr, attributes.mLetterSpacing);
                    break;
                case com.android.internal.R.styleable.TextAppearance_fontFeatureSettings:
                    attributes.mFontFeatureSettings = appearance.getString(attr);
                    break;
                case com.android.internal.R.styleable.TextAppearance_fontVariationSettings:
                    attributes.mFontVariationSettings = appearance.getString(attr);
                    break;
                case com.android.internal.R.styleable.TextAppearance_lineBreakStyle:
                    attributes.mHasLineBreakStyle = true;
                    attributes.mLineBreakStyle =
                            appearance.getInt(attr, attributes.mLineBreakStyle);
                    break;
                case com.android.internal.R.styleable.TextAppearance_lineBreakWordStyle:
                    attributes.mHasLineBreakWordStyle = true;
                    mUserSpeficiedLineBreakwordStyle = true;
                    attributes.mLineBreakWordStyle =
                            appearance.getInt(attr, attributes.mLineBreakWordStyle);
                    break;
                default:
            }
        }
    }

    private void applyTextAppearance(TextAppearanceAttributes attributes) {
        if (attributes.mTextColor != null) {
            setTextColor(attributes.mTextColor);
        }

        if (attributes.mTextColorHint != null) {
            setHintTextColor(attributes.mTextColorHint);
        }

        if (attributes.mTextColorLink != null) {
            setLinkTextColor(attributes.mTextColorLink);
        }

        if (attributes.mTextColorHighlight != 0) {
            setHighlightColor(attributes.mTextColorHighlight);
        }

        if (attributes.mSearchResultHighlightColor != 0) {
            setSearchResultHighlightColor(attributes.mSearchResultHighlightColor);
        }

        if (attributes.mFocusedSearchResultHighlightColor != 0) {
            setFocusedSearchResultHighlightColor(attributes.mFocusedSearchResultHighlightColor);
        }

        if (attributes.mTextSize != -1) {
            mTextSizeUnit = attributes.mTextSizeUnit;
            setRawTextSize(attributes.mTextSize, true /* shouldRequestLayout */);
        }

        if (attributes.mTextLocales != null) {
            setTextLocales(attributes.mTextLocales);
        }

        if (attributes.mTypefaceIndex != -1 && !attributes.mFontFamilyExplicit) {
            attributes.mFontFamily = null;
        }
        setTypefaceFromAttrs(attributes.mFontTypeface, attributes.mFontFamily,
                attributes.mTypefaceIndex, attributes.mTextStyle, attributes.mFontWeight);

        if (attributes.mShadowColor != 0) {
            setShadowLayer(attributes.mShadowRadius, attributes.mShadowDx, attributes.mShadowDy,
                    attributes.mShadowColor);
        }

        if (attributes.mAllCaps) {
            setTransformationMethod(new AllCapsTransformationMethod(getContext()));
        }

        if (attributes.mHasElegant) {
            setElegantTextHeight(attributes.mElegant);
        }

        if (attributes.mHasFallbackLineSpacing) {
            setFallbackLineSpacing(attributes.mFallbackLineSpacing);
        }

        if (attributes.mHasLetterSpacing) {
            setLetterSpacing(attributes.mLetterSpacing);
        }

        if (attributes.mFontFeatureSettings != null) {
            setFontFeatureSettings(attributes.mFontFeatureSettings);
        }

        if (attributes.mFontVariationSettings != null) {
            setFontVariationSettings(attributes.mFontVariationSettings);
        }

        if (attributes.mHasLineBreakStyle || attributes.mHasLineBreakWordStyle) {
            updateLineBreakConfigFromTextAppearance(attributes.mHasLineBreakStyle,
                    attributes.mHasLineBreakWordStyle, attributes.mLineBreakStyle,
                    attributes.mLineBreakWordStyle);
        }
    }

    /**
     * Updates the LineBreakConfig from the TextAppearance.
     *
     * This method updates the given line configuration from the TextAppearance. This method will
     * request new layout if line break config has been changed.
     *
     * @param isLineBreakStyleSpecified true if the line break style is specified.
     * @param isLineBreakWordStyleSpecified true if the line break word style is specified.
     * @param lineBreakStyle the value of the line break style in the TextAppearance.
     * @param lineBreakWordStyle the value of the line break word style in the TextAppearance.
     */
    private void updateLineBreakConfigFromTextAppearance(boolean isLineBreakStyleSpecified,
            boolean isLineBreakWordStyleSpecified,
            @LineBreakConfig.LineBreakStyle int lineBreakStyle,
            @LineBreakConfig.LineBreakWordStyle int lineBreakWordStyle) {
        boolean updated = false;
        if (isLineBreakStyleSpecified && mLineBreakStyle != lineBreakStyle) {
            mLineBreakStyle = lineBreakStyle;
            updated = true;
        }
        if (isLineBreakWordStyleSpecified && mLineBreakWordStyle != lineBreakWordStyle) {
            mLineBreakWordStyle = lineBreakWordStyle;
            updated = true;
        }
        if (updated && mLayout != null) {
            nullLayouts();
            requestLayout();
            invalidate();
        }
    }
    /**
     * Get the default primary {@link Locale} of the text in this TextView. This will always be
     * the first member of {@link #getTextLocales()}.
     * @return the default primary {@link Locale} of the text in this TextView.
     */
    @NonNull
    public Locale getTextLocale() {
        return mTextPaint.getTextLocale();
    }

    /**
     * Get the default {@link LocaleList} of the text in this TextView.
     * @return the default {@link LocaleList} of the text in this TextView.
     */
    @NonNull @Size(min = 1)
    public LocaleList getTextLocales() {
        return mTextPaint.getTextLocales();
    }

    private void changeListenerLocaleTo(@Nullable Locale locale) {
        if (mListenerChanged) {
            // If a listener has been explicitly set, don't change it. We may break something.
            return;
        }
        // The following null check is not absolutely necessary since all calling points of
        // changeListenerLocaleTo() guarantee a non-null mEditor at the moment. But this is left
        // here in case others would want to call this method in the future.
        if (mEditor != null) {
            KeyListener listener = mEditor.mKeyListener;
            if (listener instanceof DigitsKeyListener) {
                listener = DigitsKeyListener.getInstance(locale, (DigitsKeyListener) listener);
            } else if (listener instanceof DateKeyListener) {
                listener = DateKeyListener.getInstance(locale);
            } else if (listener instanceof TimeKeyListener) {
                listener = TimeKeyListener.getInstance(locale);
            } else if (listener instanceof DateTimeKeyListener) {
                listener = DateTimeKeyListener.getInstance(locale);
            } else {
                return;
            }
            final boolean wasPasswordType = isPasswordInputType(mEditor.mInputType);
            setKeyListenerOnly(listener);
            setInputTypeFromEditor();
            if (wasPasswordType) {
                final int newInputClass = mEditor.mInputType & EditorInfo.TYPE_MASK_CLASS;
                if (newInputClass == EditorInfo.TYPE_CLASS_TEXT) {
                    mEditor.mInputType |= EditorInfo.TYPE_TEXT_VARIATION_PASSWORD;
                } else if (newInputClass == EditorInfo.TYPE_CLASS_NUMBER) {
                    mEditor.mInputType |= EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD;
                }
            }
        }
    }

    /**
     * Set the default {@link Locale} of the text in this TextView to a one-member
     * {@link LocaleList} containing just the given Locale.
     *
     * @param locale the {@link Locale} for drawing text, must not be null.
     *
     * @see #setTextLocales
     */
    public void setTextLocale(@NonNull Locale locale) {
        mLocalesChanged = true;
        mTextPaint.setTextLocale(locale);
        if (mLayout != null) {
            nullLayouts();
            requestLayout();
            invalidate();
        }
    }

    /**
     * Set the default {@link LocaleList} of the text in this TextView to the given value.
     *
     * This value is used to choose appropriate typefaces for ambiguous characters (typically used
     * for CJK locales to disambiguate Hanzi/Kanji/Hanja characters). It also affects
     * other aspects of text display, including line breaking.
     *
     * @param locales the {@link LocaleList} for drawing text, must not be null or empty.
     *
     * @see Paint#setTextLocales
     */
    public void setTextLocales(@NonNull @Size(min = 1) LocaleList locales) {
        mLocalesChanged = true;
        mTextPaint.setTextLocales(locales);
        if (mLayout != null) {
            nullLayouts();
            requestLayout();
            invalidate();
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (!mLocalesChanged) {
            mTextPaint.setTextLocales(LocaleList.getDefault());
            if (mLayout != null) {
                nullLayouts();
                requestLayout();
                invalidate();
            }
        }
        if (mFontWeightAdjustment != newConfig.fontWeightAdjustment) {
            mFontWeightAdjustment = newConfig.fontWeightAdjustment;
            setTypeface(getTypeface());
        }
    }

    /**
     * @return the size (in pixels) of the default text size in this TextView.
     */
    @InspectableProperty
    @ViewDebug.ExportedProperty(category = "text")
    public float getTextSize() {
        return mTextPaint.getTextSize();
    }

    /**
     * @return the size (in scaled pixels) of the default text size in this TextView.
     * @hide
     */
    @ViewDebug.ExportedProperty(category = "text")
    public float getScaledTextSize() {
        return mTextPaint.getTextSize() / mTextPaint.density;
    }

    /** @hide */
    @ViewDebug.ExportedProperty(category = "text", mapping = {
            @ViewDebug.IntToString(from = Typeface.NORMAL, to = "NORMAL"),
            @ViewDebug.IntToString(from = Typeface.BOLD, to = "BOLD"),
            @ViewDebug.IntToString(from = Typeface.ITALIC, to = "ITALIC"),
            @ViewDebug.IntToString(from = Typeface.BOLD_ITALIC, to = "BOLD_ITALIC")
    })
    public int getTypefaceStyle() {
        Typeface typeface = mTextPaint.getTypeface();
        return typeface != null ? typeface.getStyle() : Typeface.NORMAL;
    }

    /**
     * Set the default text size to the given value, interpreted as "scaled
     * pixel" units.  This size is adjusted based on the current density and
     * user font size preference.
     *
     * <p>Note: if this TextView has the auto-size feature enabled, then this function is no-op.
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
     * Set the default text size to a given unit and value. See {@link
     * TypedValue} for the possible dimension units.
     *
     * <p>Note: if this TextView has the auto-size feature enabled, then this function is no-op.
     *
     * @param unit The desired dimension unit.
     * @param size The desired size in the given units.
     *
     * @attr ref android.R.styleable#TextView_textSize
     */
    public void setTextSize(int unit, float size) {
        if (!isAutoSizeEnabled()) {
            setTextSizeInternal(unit, size, true /* shouldRequestLayout */);
        }
    }

    @NonNull
    private DisplayMetrics getDisplayMetricsOrSystem() {
        Context c = getContext();
        Resources r;

        if (c == null) {
            r = Resources.getSystem();
        } else {
            r = c.getResources();
        }

        return r.getDisplayMetrics();
    }

    private void setTextSizeInternal(int unit, float size, boolean shouldRequestLayout) {
        mTextSizeUnit = unit;
        setRawTextSize(TypedValue.applyDimension(unit, size, getDisplayMetricsOrSystem()),
                shouldRequestLayout);
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private void setRawTextSize(float size, boolean shouldRequestLayout) {
        if (size != mTextPaint.getTextSize()) {
            mTextPaint.setTextSize(size);

            maybeRecalculateLineHeight();
            if (shouldRequestLayout && mLayout != null) {
                // Do not auto-size right after setting the text size.
                mNeedsAutoSizeText = false;
                nullLayouts();
                requestLayout();
                invalidate();
            }
        }
    }

    /**
     * Gets the text size unit defined by the developer. It may be specified in resources or be
     * passed as the unit argument of {@link #setTextSize(int, float)} at runtime.
     *
     * @return the dimension type of the text size unit originally defined.
     * @see TypedValue#TYPE_DIMENSION
     */
    public int getTextSizeUnit() {
        return mTextSizeUnit;
    }

    /**
     * Gets the extent by which text should be stretched horizontally.
     * This will usually be 1.0.
     * @return The horizontal scale factor.
     */
    @InspectableProperty
    public float getTextScaleX() {
        return mTextPaint.getTextScaleX();
    }

    /**
     * Sets the horizontal scale factor for text. The default value
     * is 1.0. Values greater than 1.0 stretch the text wider.
     * Values less than 1.0 make the text narrower. By default, this value is 1.0.
     * @param size The horizontal scale factor.
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
     * @see #getTypeface()
     *
     * @attr ref android.R.styleable#TextView_fontFamily
     * @attr ref android.R.styleable#TextView_typeface
     * @attr ref android.R.styleable#TextView_textStyle
     */
    public void setTypeface(@Nullable Typeface tf) {
        mOriginalTypeface = tf;
        if (mFontWeightAdjustment != 0
                && mFontWeightAdjustment != Configuration.FONT_WEIGHT_ADJUSTMENT_UNDEFINED) {
            if (tf == null) {
                tf = Typeface.DEFAULT;
            } else {
                int newWeight = Math.min(
                        Math.max(tf.getWeight() + mFontWeightAdjustment, FontStyle.FONT_WEIGHT_MIN),
                        FontStyle.FONT_WEIGHT_MAX);
                int typefaceStyle = tf != null ? tf.getStyle() : 0;
                boolean italic = (typefaceStyle & Typeface.ITALIC) != 0;
                tf = Typeface.create(tf, newWeight, italic);
            }
        }
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
     * Gets the current {@link Typeface} that is used to style the text.
     * @return The current Typeface.
     *
     * @see #setTypeface(Typeface)
     *
     * @attr ref android.R.styleable#TextView_fontFamily
     * @attr ref android.R.styleable#TextView_typeface
     * @attr ref android.R.styleable#TextView_textStyle
     */
    @InspectableProperty
    public Typeface getTypeface() {
        return mOriginalTypeface;
    }

    /**
     * Set the TextView's elegant height metrics flag. This setting selects font
     * variants that have not been compacted to fit Latin-based vertical
     * metrics, and also increases top and bottom bounds to provide more space.
     *
     * @param elegant set the paint's elegant metrics flag.
     *
     * @see #isElegantTextHeight()
     * @see Paint#isElegantTextHeight()
     *
     * @attr ref android.R.styleable#TextView_elegantTextHeight
     */
    public void setElegantTextHeight(boolean elegant) {
        if (elegant != mTextPaint.isElegantTextHeight()) {
            mTextPaint.setElegantTextHeight(elegant);
            if (mLayout != null) {
                nullLayouts();
                requestLayout();
                invalidate();
            }
        }
    }

    /**
     * Set whether to respect the ascent and descent of the fallback fonts that are used in
     * displaying the text (which is needed to avoid text from consecutive lines running into
     * each other). If set, fallback fonts that end up getting used can increase the ascent
     * and descent of the lines that they are used on.
     * <p/>
     * It is required to be true if text could be in languages like Burmese or Tibetan where text
     * is typically much taller or deeper than Latin text.
     *
     * @param enabled whether to expand linespacing based on fallback fonts, {@code true} by default
     *
     * @see StaticLayout.Builder#setUseLineSpacingFromFallbacks(boolean)
     *
     * @attr ref android.R.styleable#TextView_fallbackLineSpacing
     */
    public void setFallbackLineSpacing(boolean enabled) {
        int fallbackStrategy;
        if (enabled) {
            if (CompatChanges.isChangeEnabled(BORINGLAYOUT_FALLBACK_LINESPACING)) {
                fallbackStrategy = FALLBACK_LINE_SPACING_ALL;
            } else {
                fallbackStrategy = FALLBACK_LINE_SPACING_STATIC_LAYOUT_ONLY;
            }
        } else {
            fallbackStrategy = FALLBACK_LINE_SPACING_NONE;
        }
        if (mUseFallbackLineSpacing != fallbackStrategy) {
            mUseFallbackLineSpacing = fallbackStrategy;
            if (mLayout != null) {
                nullLayouts();
                requestLayout();
                invalidate();
            }
        }
    }

    /**
     * @return whether fallback line spacing is enabled, {@code true} by default
     *
     * @see #setFallbackLineSpacing(boolean)
     *
     * @attr ref android.R.styleable#TextView_fallbackLineSpacing
     */
    @InspectableProperty
    public boolean isFallbackLineSpacing() {
        return mUseFallbackLineSpacing != FALLBACK_LINE_SPACING_NONE;
    }

    private boolean isFallbackLineSpacingForBoringLayout() {
        return mUseFallbackLineSpacing == FALLBACK_LINE_SPACING_ALL;
    }

    // Package privte for accessing from Editor.java
    /* package */ boolean isFallbackLineSpacingForStaticLayout() {
        return mUseFallbackLineSpacing == FALLBACK_LINE_SPACING_ALL
                || mUseFallbackLineSpacing == FALLBACK_LINE_SPACING_STATIC_LAYOUT_ONLY;
    }

    /**
     * Get the value of the TextView's elegant height metrics flag. This setting selects font
     * variants that have not been compacted to fit Latin-based vertical
     * metrics, and also increases top and bottom bounds to provide more space.
     * @return {@code true} if the elegant height metrics flag is set.
     *
     * @see #setElegantTextHeight(boolean)
     * @see Paint#setElegantTextHeight(boolean)
     */
    @InspectableProperty
    public boolean isElegantTextHeight() {
        return mTextPaint.isElegantTextHeight();
    }

    /**
     * Gets the text letter-space value, which determines the spacing between characters.
     * The value returned is in ems. Normally, this value is 0.0.
     * @return The text letter-space value in ems.
     *
     * @see #setLetterSpacing(float)
     * @see Paint#setLetterSpacing
     */
    @InspectableProperty
    public float getLetterSpacing() {
        return mTextPaint.getLetterSpacing();
    }

    /**
     * Sets text letter-spacing in em units.  Typical values
     * for slight expansion will be around 0.05.  Negative values tighten text.
     *
     * @see #getLetterSpacing()
     * @see Paint#getLetterSpacing
     *
     * @param letterSpacing A text letter-space value in ems.
     * @attr ref android.R.styleable#TextView_letterSpacing
     */
    @android.view.RemotableViewMethod
    public void setLetterSpacing(float letterSpacing) {
        if (letterSpacing != mTextPaint.getLetterSpacing()) {
            mTextPaint.setLetterSpacing(letterSpacing);

            if (mLayout != null) {
                nullLayouts();
                requestLayout();
                invalidate();
            }
        }
    }

    /**
     * Returns the font feature settings. The format is the same as the CSS
     * font-feature-settings attribute:
     * <a href="https://www.w3.org/TR/css-fonts-3/#font-feature-settings-prop">
     *     https://www.w3.org/TR/css-fonts-3/#font-feature-settings-prop</a>
     *
     * @return the currently set font feature settings.  Default is null.
     *
     * @see #setFontFeatureSettings(String)
     * @see Paint#setFontFeatureSettings(String) Paint.setFontFeatureSettings(String)
     */
    @InspectableProperty
    @Nullable
    public String getFontFeatureSettings() {
        return mTextPaint.getFontFeatureSettings();
    }

    /**
     * Returns the font variation settings.
     *
     * @return the currently set font variation settings.  Returns null if no variation is
     * specified.
     *
     * @see #setFontVariationSettings(String)
     * @see Paint#setFontVariationSettings(String) Paint.setFontVariationSettings(String)
     */
    @Nullable
    public String getFontVariationSettings() {
        return mTextPaint.getFontVariationSettings();
    }

    /**
     * Sets the break strategy for breaking paragraphs into lines. The default value for
     * TextView is {@link Layout#BREAK_STRATEGY_HIGH_QUALITY}, and the default value for
     * EditText is {@link Layout#BREAK_STRATEGY_SIMPLE}, the latter to avoid the
     * text "dancing" when being edited.
     * <p>
     * Enabling hyphenation with either using {@link Layout#HYPHENATION_FREQUENCY_NORMAL} or
     * {@link Layout#HYPHENATION_FREQUENCY_FULL} while line breaking is set to one of
     * {@link Layout#BREAK_STRATEGY_BALANCED}, {@link Layout#BREAK_STRATEGY_HIGH_QUALITY}
     * improves the structure of text layout however has performance impact and requires more time
     * to do the text layout.</p>
     * <p>
     * Compared with {@link #setLineBreakStyle(int)}, line break style with different strictness is
     * evaluated in the ICU to identify the potential breakpoints. In
     * {@link #setBreakStrategy(int)}, line break strategy handles the post processing of ICU's line
     * break result. It aims to evaluate ICU's breakpoints and break the lines based on the
     * constraint.
     * </p>
     *
     * @attr ref android.R.styleable#TextView_breakStrategy
     * @see #getBreakStrategy()
     * @see #setHyphenationFrequency(int)
     */
    public void setBreakStrategy(@Layout.BreakStrategy int breakStrategy) {
        mBreakStrategy = breakStrategy;
        if (mLayout != null) {
            nullLayouts();
            requestLayout();
            invalidate();
        }
    }

    /**
     * Gets the current strategy for breaking paragraphs into lines.
     * @return the current strategy for breaking paragraphs into lines.
     *
     * @attr ref android.R.styleable#TextView_breakStrategy
     * @see #setBreakStrategy(int)
     */
    @InspectableProperty(enumMapping = {
            @EnumEntry(name = "simple", value = Layout.BREAK_STRATEGY_SIMPLE),
            @EnumEntry(name = "high_quality", value = Layout.BREAK_STRATEGY_HIGH_QUALITY),
            @EnumEntry(name = "balanced", value = Layout.BREAK_STRATEGY_BALANCED)
    })
    @Layout.BreakStrategy
    public int getBreakStrategy() {
        return mBreakStrategy;
    }

    /**
     * Sets the frequency of automatic hyphenation to use when determining word breaks.
     * The default value for both TextView and {@link EditText} is
     * {@link Layout#HYPHENATION_FREQUENCY_NONE}. Note that the default hyphenation frequency value
     * is set from the theme.
     * <p/>
     * Enabling hyphenation with either using {@link Layout#HYPHENATION_FREQUENCY_NORMAL} or
     * {@link Layout#HYPHENATION_FREQUENCY_FULL} while line breaking is set to one of
     * {@link Layout#BREAK_STRATEGY_BALANCED}, {@link Layout#BREAK_STRATEGY_HIGH_QUALITY}
     * improves the structure of text layout however has performance impact and requires more time
     * to do the text layout.
     * <p/>
     * Note: Before Android Q, in the theme hyphenation frequency is set to
     * {@link Layout#HYPHENATION_FREQUENCY_NORMAL}. The default value is changed into
     * {@link Layout#HYPHENATION_FREQUENCY_NONE} on Q.
     *
     * @param hyphenationFrequency the hyphenation frequency to use, one of
     *                             {@link Layout#HYPHENATION_FREQUENCY_NONE},
     *                             {@link Layout#HYPHENATION_FREQUENCY_NORMAL},
     *                             {@link Layout#HYPHENATION_FREQUENCY_FULL}
     * @attr ref android.R.styleable#TextView_hyphenationFrequency
     * @see #getHyphenationFrequency()
     * @see #getBreakStrategy()
     */
    public void setHyphenationFrequency(@Layout.HyphenationFrequency int hyphenationFrequency) {
        mHyphenationFrequency = hyphenationFrequency;
        if (mLayout != null) {
            nullLayouts();
            requestLayout();
            invalidate();
        }
    }

    /**
     * Gets the current frequency of automatic hyphenation to be used when determining word breaks.
     * @return the current frequency of automatic hyphenation to be used when determining word
     * breaks.
     *
     * @attr ref android.R.styleable#TextView_hyphenationFrequency
     * @see #setHyphenationFrequency(int)
     */
    @InspectableProperty(enumMapping = {
            @EnumEntry(name = "none", value = Layout.HYPHENATION_FREQUENCY_NONE),
            @EnumEntry(name = "normal", value = Layout.HYPHENATION_FREQUENCY_NORMAL),
            @EnumEntry(name = "full", value = Layout.HYPHENATION_FREQUENCY_FULL)
    })
    @Layout.HyphenationFrequency
    public int getHyphenationFrequency() {
        return mHyphenationFrequency;
    }

    /**
     * Sets the line-break style for text wrapping.
     *
     * <p>Line-break style specifies the line-break strategies that can be used
     * for text wrapping. The line-break style affects rule-based line breaking
     * by specifying the strictness of line-breaking rules.
     *
     * <p>The following are types of line-break styles:
     * <ul>
     *   <li>{@link LineBreakConfig#LINE_BREAK_STYLE_LOOSE}
     *   <li>{@link LineBreakConfig#LINE_BREAK_STYLE_NORMAL}
     *   <li>{@link LineBreakConfig#LINE_BREAK_STYLE_STRICT}
     * </ul>
     *
     * <p>The default line-break style is
     * {@link LineBreakConfig#LINE_BREAK_STYLE_NONE}, which specifies that no
     * line-breaking rules are used.
     *
     * <p>See the
     * <a href="https://www.w3.org/TR/css-text-3/#line-break-property" class="external">
     * line-break property</a> for more information.
     *
     * @param lineBreakStyle The line-break style for the text.
     */
    public void setLineBreakStyle(@LineBreakConfig.LineBreakStyle int lineBreakStyle) {
        if (mLineBreakStyle != lineBreakStyle) {
            mLineBreakStyle = lineBreakStyle;
            if (mLayout != null) {
                nullLayouts();
                requestLayout();
                invalidate();
            }
        }
    }

    /**
     * Sets the line-break word style for text wrapping.
     *
     * <p>The line-break word style affects dictionary-based line breaking by
     * providing phrase-based line-breaking opportunities. Use
     * {@link LineBreakConfig#LINE_BREAK_WORD_STYLE_PHRASE} to specify
     * phrase-based line breaking.
     *
     * <p>The default line-break word style is
     * {@link LineBreakConfig#LINE_BREAK_WORD_STYLE_NONE}, which specifies that
     * no line-breaking word style is used.
     *
     * <p>See the
     * <a href="https://www.w3.org/TR/css-text-3/#word-break-property" class="external">
     * word-break property</a> for more information.
     *
     * @param lineBreakWordStyle The line-break word style for the text.
     */
    public void setLineBreakWordStyle(@LineBreakConfig.LineBreakWordStyle int lineBreakWordStyle) {
        mUserSpeficiedLineBreakwordStyle = true;
        if (mLineBreakWordStyle != lineBreakWordStyle) {
            mLineBreakWordStyle = lineBreakWordStyle;
            if (mLayout != null) {
                nullLayouts();
                requestLayout();
                invalidate();
            }
        }
    }

    /**
     * Gets the current line-break style for text wrapping.
     *
     * @return The line-break style to be used for text wrapping.
     */
    public @LineBreakConfig.LineBreakStyle int getLineBreakStyle() {
        return mLineBreakStyle;
    }

    /**
     * Gets the current line-break word style for text wrapping.
     *
     * @return The line-break word style to be used for text wrapping.
     */
    public @LineBreakConfig.LineBreakWordStyle int getLineBreakWordStyle() {
        return mLineBreakWordStyle;
    }

    /**
     * Gets the parameters for text layout precomputation, for use with {@link PrecomputedText}.
     *
     * @return a current {@link PrecomputedText.Params}
     * @see PrecomputedText
     */
    public @NonNull PrecomputedText.Params getTextMetricsParams() {
        final boolean autoPhraseBreaking =
                !mUserSpeficiedLineBreakwordStyle && FeatureFlagUtils.isEnabled(mContext,
                        FeatureFlagUtils.SETTINGS_AUTO_TEXT_WRAPPING);
        return new PrecomputedText.Params(new TextPaint(mTextPaint),
                LineBreakConfig.getLineBreakConfig(mLineBreakStyle, mLineBreakWordStyle,
                        autoPhraseBreaking),
                getTextDirectionHeuristic(),
                mBreakStrategy, mHyphenationFrequency);
    }

    /**
     * Apply the text layout parameter.
     *
     * Update the TextView parameters to be compatible with {@link PrecomputedText.Params}.
     * @see PrecomputedText
     */
    public void setTextMetricsParams(@NonNull PrecomputedText.Params params) {
        mTextPaint.set(params.getTextPaint());
        mUserSetTextScaleX = true;
        mTextDir = params.getTextDirection();
        mBreakStrategy = params.getBreakStrategy();
        mHyphenationFrequency = params.getHyphenationFrequency();
        LineBreakConfig lineBreakConfig = params.getLineBreakConfig();
        mLineBreakStyle = lineBreakConfig.getLineBreakStyle();
        mLineBreakWordStyle = lineBreakConfig.getLineBreakWordStyle();
        mUserSpeficiedLineBreakwordStyle = true;
        if (mLayout != null) {
            nullLayouts();
            requestLayout();
            invalidate();
        }
    }

    /**
     * Set justification mode. The default value is {@link Layout#JUSTIFICATION_MODE_NONE}. If the
     * last line is too short for justification, the last line will be displayed with the
     * alignment set by {@link android.view.View#setTextAlignment}.
     *
     * @see #getJustificationMode()
     */
    @Layout.JustificationMode
    @android.view.RemotableViewMethod
    public void setJustificationMode(@Layout.JustificationMode int justificationMode) {
        mJustificationMode = justificationMode;
        if (mLayout != null) {
            nullLayouts();
            requestLayout();
            invalidate();
        }
    }

    /**
     * @return true if currently paragraph justification mode.
     *
     * @see #setJustificationMode(int)
     */
    @InspectableProperty(enumMapping = {
            @EnumEntry(name = "none", value = Layout.JUSTIFICATION_MODE_NONE),
            @EnumEntry(name = "inter_word", value = Layout.JUSTIFICATION_MODE_INTER_WORD)
    })
    public @Layout.JustificationMode int getJustificationMode() {
        return mJustificationMode;
    }

    /**
     * Sets font feature settings. The format is the same as the CSS
     * font-feature-settings attribute:
     * <a href="https://www.w3.org/TR/css-fonts-3/#font-feature-settings-prop">
     *     https://www.w3.org/TR/css-fonts-3/#font-feature-settings-prop</a>
     *
     * @param fontFeatureSettings font feature settings represented as CSS compatible string
     *
     * @see #getFontFeatureSettings()
     * @see Paint#getFontFeatureSettings() Paint.getFontFeatureSettings()
     *
     * @attr ref android.R.styleable#TextView_fontFeatureSettings
     */
    @android.view.RemotableViewMethod
    public void setFontFeatureSettings(@Nullable String fontFeatureSettings) {
        if (fontFeatureSettings != mTextPaint.getFontFeatureSettings()) {
            mTextPaint.setFontFeatureSettings(fontFeatureSettings);

            if (mLayout != null) {
                nullLayouts();
                requestLayout();
                invalidate();
            }
        }
    }


    /**
     * Sets TrueType or OpenType font variation settings. The settings string is constructed from
     * multiple pairs of axis tag and style values. The axis tag must contain four ASCII characters
     * and must be wrapped with single quotes (U+0027) or double quotes (U+0022). Axis strings that
     * are longer or shorter than four characters, or contain characters outside of U+0020..U+007E
     * are invalid. If a specified axis name is not defined in the font, the settings will be
     * ignored.
     *
     * <p>
     * Examples,
     * <ul>
     * <li>Set font width to 150.
     * <pre>
     * <code>
     *   TextView textView = (TextView) findViewById(R.id.textView);
     *   textView.setFontVariationSettings("'wdth' 150");
     * </code>
     * </pre>
     * </li>
     *
     * <li>Set the font slant to 20 degrees and ask for italic style.
     * <pre>
     * <code>
     *   TextView textView = (TextView) findViewById(R.id.textView);
     *   textView.setFontVariationSettings("'slnt' 20, 'ital' 1");
     * </code>
     * </pre>
     * </p>
     * </li>
     * </ul>
     *
     * @param fontVariationSettings font variation settings. You can pass null or empty string as
     *                              no variation settings.
     * @return true if the given settings is effective to at least one font file underlying this
     *         TextView. This function also returns true for empty settings string. Otherwise
     *         returns false.
     *
     * @throws IllegalArgumentException If given string is not a valid font variation settings
     *                                  format.
     *
     * @see #getFontVariationSettings()
     * @see FontVariationAxis
     *
     * @attr ref android.R.styleable#TextView_fontVariationSettings
     */
    public boolean setFontVariationSettings(@Nullable String fontVariationSettings) {
        final String existingSettings = mTextPaint.getFontVariationSettings();
        if (fontVariationSettings == existingSettings
                || (fontVariationSettings != null
                        && fontVariationSettings.equals(existingSettings))) {
            return true;
        }
        boolean effective = mTextPaint.setFontVariationSettings(fontVariationSettings);

        if (effective && mLayout != null) {
            nullLayouts();
            requestLayout();
            invalidate();
        }
        return effective;
    }

    /**
     * Sets the text color for all the states (normal, selected,
     * focused) to be this color.
     *
     * @param color A color value in the form 0xAARRGGBB.
     * Do not pass a resource ID. To get a color value from a resource ID, call
     * {@link androidx.core.content.ContextCompat#getColor(Context, int) getColor}.
     *
     * @see #setTextColor(ColorStateList)
     * @see #getTextColors()
     *
     * @attr ref android.R.styleable#TextView_textColor
     */
    @android.view.RemotableViewMethod
    public void setTextColor(@ColorInt int color) {
        mTextColor = ColorStateList.valueOf(color);
        updateTextColors();
    }

    /**
     * Sets the text color.
     *
     * @see #setTextColor(int)
     * @see #getTextColors()
     * @see #setHintTextColor(ColorStateList)
     * @see #setLinkTextColor(ColorStateList)
     *
     * @attr ref android.R.styleable#TextView_textColor
     */
    @android.view.RemotableViewMethod
    public void setTextColor(ColorStateList colors) {
        if (colors == null) {
            throw new NullPointerException();
        }

        mTextColor = colors;
        updateTextColors();
    }

    /**
     * Gets the text colors for the different states (normal, selected, focused) of the TextView.
     *
     * @see #setTextColor(ColorStateList)
     * @see #setTextColor(int)
     *
     * @attr ref android.R.styleable#TextView_textColor
     */
    @InspectableProperty(name = "textColor")
    public final ColorStateList getTextColors() {
        return mTextColor;
    }

    /**
     * Return the current color selected for normal text.
     *
     * @return Returns the current text color.
     */
    @ColorInt
    public final int getCurrentTextColor() {
        return mCurTextColor;
    }

    /**
     * Sets the color used to display the selection highlight.
     *
     * @attr ref android.R.styleable#TextView_textColorHighlight
     */
    @android.view.RemotableViewMethod
    public void setHighlightColor(@ColorInt int color) {
        if (mHighlightColor != color) {
            mHighlightColor = color;
            invalidate();
        }
    }

    /**
     * @return the color used to display the selection highlight
     *
     * @see #setHighlightColor(int)
     *
     * @attr ref android.R.styleable#TextView_textColorHighlight
     */
    @InspectableProperty(name = "textColorHighlight")
    @ColorInt
    public int getHighlightColor() {
        return mHighlightColor;
    }

    /**
     * Sets whether the soft input method will be made visible when this
     * TextView gets focused. The default is true.
     */
    @android.view.RemotableViewMethod
    public final void setShowSoftInputOnFocus(boolean show) {
        createEditorIfNeeded();
        mEditor.mShowSoftInputOnFocus = show;
    }

    /**
     * Returns whether the soft input method will be made visible when this
     * TextView gets focused. The default is true.
     */
    public final boolean getShowSoftInputOnFocus() {
        // When there is no Editor, return default true value
        return mEditor == null || mEditor.mShowSoftInputOnFocus;
    }

    /**
     * Gives the text a shadow of the specified blur radius and color, the specified
     * distance from its drawn position.
     * <p>
     * The text shadow produced does not interact with the properties on view
     * that are responsible for real time shadows,
     * {@link View#getElevation() elevation} and
     * {@link View#getTranslationZ() translationZ}.
     *
     * @see Paint#setShadowLayer(float, float, float, int)
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
        mShadowColor = color;

        // Will change text clip region
        if (mEditor != null) {
            mEditor.invalidateTextDisplayList();
            mEditor.invalidateHandlesAndActionMode();
        }
        invalidate();
    }

    /**
     * Gets the radius of the shadow layer.
     *
     * @return the radius of the shadow layer. If 0, the shadow layer is not visible
     *
     * @see #setShadowLayer(float, float, float, int)
     *
     * @attr ref android.R.styleable#TextView_shadowRadius
     */
    @InspectableProperty
    public float getShadowRadius() {
        return mShadowRadius;
    }

    /**
     * @return the horizontal offset of the shadow layer
     *
     * @see #setShadowLayer(float, float, float, int)
     *
     * @attr ref android.R.styleable#TextView_shadowDx
     */
    @InspectableProperty
    public float getShadowDx() {
        return mShadowDx;
    }

    /**
     * Gets the vertical offset of the shadow layer.
     * @return The vertical offset of the shadow layer.
     *
     * @see #setShadowLayer(float, float, float, int)
     *
     * @attr ref android.R.styleable#TextView_shadowDy
     */
    @InspectableProperty
    public float getShadowDy() {
        return mShadowDy;
    }

    /**
     * Gets the color of the shadow layer.
     * @return the color of the shadow layer
     *
     * @see #setShadowLayer(float, float, float, int)
     *
     * @attr ref android.R.styleable#TextView_shadowColor
     */
    @InspectableProperty
    @ColorInt
    public int getShadowColor() {
        return mShadowColor;
    }

    /**
     * Gets the {@link TextPaint} used for the text.
     * Use this only to consult the Paint's properties and not to change them.
     * @return The base paint used for the text.
     */
    public TextPaint getPaint() {
        return mTextPaint;
    }

    /**
     * Sets the autolink mask of the text.  See {@link
     * android.text.util.Linkify#ALL Linkify.ALL} and peers for
     * possible values.
     *
     * <p class="note"><b>Note:</b>
     * {@link android.text.util.Linkify#MAP_ADDRESSES Linkify.MAP_ADDRESSES}
     * is deprecated and should be avoided; see its documentation.
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
    @InspectableProperty
    public final boolean getLinksClickable() {
        return mLinksClickable;
    }

    /**
     * Returns the list of {@link android.text.style.URLSpan URLSpans} attached to the text
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
     * Sets the color of the hint text for all the states (disabled, focussed, selected...) of this
     * TextView.
     *
     * @see #setHintTextColor(ColorStateList)
     * @see #getHintTextColors()
     * @see #setTextColor(int)
     *
     * @attr ref android.R.styleable#TextView_textColorHint
     */
    @android.view.RemotableViewMethod
    public final void setHintTextColor(@ColorInt int color) {
        mHintTextColor = ColorStateList.valueOf(color);
        updateTextColors();
    }

    /**
     * Sets the color of the hint text.
     *
     * @see #getHintTextColors()
     * @see #setHintTextColor(int)
     * @see #setTextColor(ColorStateList)
     * @see #setLinkTextColor(ColorStateList)
     *
     * @attr ref android.R.styleable#TextView_textColorHint
     */
    public final void setHintTextColor(ColorStateList colors) {
        mHintTextColor = colors;
        updateTextColors();
    }

    /**
     * @return the color of the hint text, for the different states of this TextView.
     *
     * @see #setHintTextColor(ColorStateList)
     * @see #setHintTextColor(int)
     * @see #setTextColor(ColorStateList)
     * @see #setLinkTextColor(ColorStateList)
     *
     * @attr ref android.R.styleable#TextView_textColorHint
     */
    @InspectableProperty(name = "textColorHint")
    public final ColorStateList getHintTextColors() {
        return mHintTextColor;
    }

    /**
     * <p>Return the current color selected to paint the hint text.</p>
     *
     * @return Returns the current hint text color.
     */
    @ColorInt
    public final int getCurrentHintTextColor() {
        return mHintTextColor != null ? mCurHintTextColor : mCurTextColor;
    }

    /**
     * Sets the color of links in the text.
     *
     * @see #setLinkTextColor(ColorStateList)
     * @see #getLinkTextColors()
     *
     * @attr ref android.R.styleable#TextView_textColorLink
     */
    @android.view.RemotableViewMethod
    public final void setLinkTextColor(@ColorInt int color) {
        mLinkTextColor = ColorStateList.valueOf(color);
        updateTextColors();
    }

    /**
     * Sets the color of links in the text.
     *
     * @see #setLinkTextColor(int)
     * @see #getLinkTextColors()
     * @see #setTextColor(ColorStateList)
     * @see #setHintTextColor(ColorStateList)
     *
     * @attr ref android.R.styleable#TextView_textColorLink
     */
    public final void setLinkTextColor(ColorStateList colors) {
        mLinkTextColor = colors;
        updateTextColors();
    }

    /**
     * @return the list of colors used to paint the links in the text, for the different states of
     * this TextView
     *
     * @see #setLinkTextColor(ColorStateList)
     * @see #setLinkTextColor(int)
     *
     * @attr ref android.R.styleable#TextView_textColorLink
     */
    @InspectableProperty(name = "textColorLink")
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
    @android.view.RemotableViewMethod
    public void setGravity(int gravity) {
        if ((gravity & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK) == 0) {
            gravity |= Gravity.START;
        }
        if ((gravity & Gravity.VERTICAL_GRAVITY_MASK) == 0) {
            gravity |= Gravity.TOP;
        }

        boolean newLayout = false;

        if ((gravity & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK)
                != (mGravity & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK)) {
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
                    mRight - mLeft - getCompoundPaddingLeft() - getCompoundPaddingRight(), true);
        }
    }

    /**
     * Returns the horizontal and vertical alignment of this TextView.
     *
     * @see android.view.Gravity
     * @attr ref android.R.styleable#TextView_gravity
     */
    @InspectableProperty(valueType = InspectableProperty.ValueType.GRAVITY)
    public int getGravity() {
        return mGravity;
    }

    /**
     * Gets the flags on the Paint being used to display the text.
     * @return The flags on the Paint being used to display the text.
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
        if (mHorizontallyScrolling != whether) {
            mHorizontallyScrolling = whether;

            if (mLayout != null) {
                nullLayouts();
                requestLayout();
                invalidate();
            }
        }
    }

    /**
     * Returns whether the text is allowed to be wider than the View.
     * If false, the text will be wrapped to the width of the View.
     *
     * @attr ref android.R.styleable#TextView_scrollHorizontally
     * @see #setHorizontallyScrolling(boolean)
     */
    @InspectableProperty(name = "scrollHorizontally")
    public final boolean isHorizontallyScrollable() {
        return mHorizontallyScrolling;
    }

    /**
     * Returns whether the text is allowed to be wider than the View.
     * If false, the text will be wrapped to the width of the View.
     *
     * @attr ref android.R.styleable#TextView_scrollHorizontally
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public boolean getHorizontallyScrolling() {
        return mHorizontallyScrolling;
    }

    /**
     * Sets the height of the TextView to be at least {@code minLines} tall.
     * <p>
     * This value is used for height calculation if LayoutParams does not force TextView to have an
     * exact height. Setting this value overrides other previous minimum height configurations such
     * as {@link #setMinHeight(int)} or {@link #setHeight(int)}. {@link #setSingleLine()} will set
     * this value to 1.
     *
     * @param minLines the minimum height of TextView in terms of number of lines
     *
     * @see #getMinLines()
     * @see #setLines(int)
     *
     * @attr ref android.R.styleable#TextView_minLines
     */
    @android.view.RemotableViewMethod
    public void setMinLines(int minLines) {
        mMinimum = minLines;
        mMinMode = LINES;

        requestLayout();
        invalidate();
    }

    /**
     * Returns the minimum height of TextView in terms of number of lines or -1 if the minimum
     * height was set using {@link #setMinHeight(int)} or {@link #setHeight(int)}.
     *
     * @return the minimum height of TextView in terms of number of lines or -1 if the minimum
     *         height is not defined in lines
     *
     * @see #setMinLines(int)
     * @see #setLines(int)
     *
     * @attr ref android.R.styleable#TextView_minLines
     */
    @InspectableProperty
    public int getMinLines() {
        return mMinMode == LINES ? mMinimum : -1;
    }

    /**
     * Sets the height of the TextView to be at least {@code minPixels} tall.
     * <p>
     * This value is used for height calculation if LayoutParams does not force TextView to have an
     * exact height. Setting this value overrides previous minimum height configurations such as
     * {@link #setMinLines(int)} or {@link #setLines(int)}.
     * <p>
     * The value given here is different than {@link #setMinimumHeight(int)}. Between
     * {@code minHeight} and the value set in {@link #setMinimumHeight(int)}, the greater one is
     * used to decide the final height.
     *
     * @param minPixels the minimum height of TextView in terms of pixels
     *
     * @see #getMinHeight()
     * @see #setHeight(int)
     *
     * @attr ref android.R.styleable#TextView_minHeight
     */
    @android.view.RemotableViewMethod
    public void setMinHeight(int minPixels) {
        mMinimum = minPixels;
        mMinMode = PIXELS;

        requestLayout();
        invalidate();
    }

    /**
     * Returns the minimum height of TextView in terms of pixels or -1 if the minimum height was
     * set using {@link #setMinLines(int)} or {@link #setLines(int)}.
     *
     * @return the minimum height of TextView in terms of pixels or -1 if the minimum height is not
     *         defined in pixels
     *
     * @see #setMinHeight(int)
     * @see #setHeight(int)
     *
     * @attr ref android.R.styleable#TextView_minHeight
     */
    public int getMinHeight() {
        return mMinMode == PIXELS ? mMinimum : -1;
    }

    /**
     * Sets the height of the TextView to be at most {@code maxLines} tall.
     * <p>
     * This value is used for height calculation if LayoutParams does not force TextView to have an
     * exact height. Setting this value overrides previous maximum height configurations such as
     * {@link #setMaxHeight(int)} or {@link #setLines(int)}.
     *
     * @param maxLines the maximum height of TextView in terms of number of lines
     *
     * @see #getMaxLines()
     * @see #setLines(int)
     *
     * @attr ref android.R.styleable#TextView_maxLines
     */
    @android.view.RemotableViewMethod
    public void setMaxLines(int maxLines) {
        mMaximum = maxLines;
        mMaxMode = LINES;

        requestLayout();
        invalidate();
    }

    /**
     * Returns the maximum height of TextView in terms of number of lines or -1 if the
     * maximum height was set using {@link #setMaxHeight(int)} or {@link #setHeight(int)}.
     *
     * @return the maximum height of TextView in terms of number of lines. -1 if the maximum height
     *         is not defined in lines.
     *
     * @see #setMaxLines(int)
     * @see #setLines(int)
     *
     * @attr ref android.R.styleable#TextView_maxLines
     */
    @InspectableProperty
    public int getMaxLines() {
        return mMaxMode == LINES ? mMaximum : -1;
    }

    /**
     * Sets the height of the TextView to be at most {@code maxPixels} tall.
     * <p>
     * This value is used for height calculation if LayoutParams does not force TextView to have an
     * exact height. Setting this value overrides previous maximum height configurations such as
     * {@link #setMaxLines(int)} or {@link #setLines(int)}.
     *
     * @param maxPixels the maximum height of TextView in terms of pixels
     *
     * @see #getMaxHeight()
     * @see #setHeight(int)
     *
     * @attr ref android.R.styleable#TextView_maxHeight
     */
    @android.view.RemotableViewMethod
    public void setMaxHeight(int maxPixels) {
        mMaximum = maxPixels;
        mMaxMode = PIXELS;

        requestLayout();
        invalidate();
    }

    /**
     * Returns the maximum height of TextView in terms of pixels or -1 if the maximum height was
     * set using {@link #setMaxLines(int)} or {@link #setLines(int)}.
     *
     * @return the maximum height of TextView in terms of pixels or -1 if the maximum height
     *         is not defined in pixels
     *
     * @see #setMaxHeight(int)
     * @see #setHeight(int)
     *
     * @attr ref android.R.styleable#TextView_maxHeight
     */
    @InspectableProperty
    public int getMaxHeight() {
        return mMaxMode == PIXELS ? mMaximum : -1;
    }

    /**
     * Sets the height of the TextView to be exactly {@code lines} tall.
     * <p>
     * This value is used for height calculation if LayoutParams does not force TextView to have an
     * exact height. Setting this value overrides previous minimum/maximum height configurations
     * such as {@link #setMinLines(int)} or {@link #setMaxLines(int)}. {@link #setSingleLine()} will
     * set this value to 1.
     *
     * @param lines the exact height of the TextView in terms of lines
     *
     * @see #setHeight(int)
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
     * Sets the height of the TextView to be exactly <code>pixels</code> tall.
     * <p>
     * This value is used for height calculation if LayoutParams does not force TextView to have an
     * exact height. Setting this value overrides previous minimum/maximum height configurations
     * such as {@link #setMinHeight(int)} or {@link #setMaxHeight(int)}.
     *
     * @param pixels the exact height of the TextView in terms of pixels
     *
     * @see #setLines(int)
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
     * Sets the width of the TextView to be at least {@code minEms} wide.
     * <p>
     * This value is used for width calculation if LayoutParams does not force TextView to have an
     * exact width. Setting this value overrides previous minimum width configurations such as
     * {@link #setMinWidth(int)} or {@link #setWidth(int)}.
     *
     * @param minEms the minimum width of TextView in terms of ems
     *
     * @see #getMinEms()
     * @see #setEms(int)
     *
     * @attr ref android.R.styleable#TextView_minEms
     */
    @android.view.RemotableViewMethod
    public void setMinEms(int minEms) {
        mMinWidth = minEms;
        mMinWidthMode = EMS;

        requestLayout();
        invalidate();
    }

    /**
     * Returns the minimum width of TextView in terms of ems or -1 if the minimum width was set
     * using {@link #setMinWidth(int)} or {@link #setWidth(int)}.
     *
     * @return the minimum width of TextView in terms of ems. -1 if the minimum width is not
     *         defined in ems
     *
     * @see #setMinEms(int)
     * @see #setEms(int)
     *
     * @attr ref android.R.styleable#TextView_minEms
     */
    @InspectableProperty
    public int getMinEms() {
        return mMinWidthMode == EMS ? mMinWidth : -1;
    }

    /**
     * Sets the width of the TextView to be at least {@code minPixels} wide.
     * <p>
     * This value is used for width calculation if LayoutParams does not force TextView to have an
     * exact width. Setting this value overrides previous minimum width configurations such as
     * {@link #setMinEms(int)} or {@link #setEms(int)}.
     * <p>
     * The value given here is different than {@link #setMinimumWidth(int)}. Between
     * {@code minWidth} and the value set in {@link #setMinimumWidth(int)}, the greater one is used
     * to decide the final width.
     *
     * @param minPixels the minimum width of TextView in terms of pixels
     *
     * @see #getMinWidth()
     * @see #setWidth(int)
     *
     * @attr ref android.R.styleable#TextView_minWidth
     */
    @android.view.RemotableViewMethod
    public void setMinWidth(int minPixels) {
        mMinWidth = minPixels;
        mMinWidthMode = PIXELS;

        requestLayout();
        invalidate();
    }

    /**
     * Returns the minimum width of TextView in terms of pixels or -1 if the minimum width was set
     * using {@link #setMinEms(int)} or {@link #setEms(int)}.
     *
     * @return the minimum width of TextView in terms of pixels or -1 if the minimum width is not
     *         defined in pixels
     *
     * @see #setMinWidth(int)
     * @see #setWidth(int)
     *
     * @attr ref android.R.styleable#TextView_minWidth
     */
    @InspectableProperty
    public int getMinWidth() {
        return mMinWidthMode == PIXELS ? mMinWidth : -1;
    }

    /**
     * Sets the width of the TextView to be at most {@code maxEms} wide.
     * <p>
     * This value is used for width calculation if LayoutParams does not force TextView to have an
     * exact width. Setting this value overrides previous maximum width configurations such as
     * {@link #setMaxWidth(int)} or {@link #setWidth(int)}.
     *
     * @param maxEms the maximum width of TextView in terms of ems
     *
     * @see #getMaxEms()
     * @see #setEms(int)
     *
     * @attr ref android.R.styleable#TextView_maxEms
     */
    @android.view.RemotableViewMethod
    public void setMaxEms(int maxEms) {
        mMaxWidth = maxEms;
        mMaxWidthMode = EMS;

        requestLayout();
        invalidate();
    }

    /**
     * Returns the maximum width of TextView in terms of ems or -1 if the maximum width was set
     * using {@link #setMaxWidth(int)} or {@link #setWidth(int)}.
     *
     * @return the maximum width of TextView in terms of ems or -1 if the maximum width is not
     *         defined in ems
     *
     * @see #setMaxEms(int)
     * @see #setEms(int)
     *
     * @attr ref android.R.styleable#TextView_maxEms
     */
    @InspectableProperty
    public int getMaxEms() {
        return mMaxWidthMode == EMS ? mMaxWidth : -1;
    }

    /**
     * Sets the width of the TextView to be at most {@code maxPixels} wide.
     * <p>
     * This value is used for width calculation if LayoutParams does not force TextView to have an
     * exact width. Setting this value overrides previous maximum width configurations such as
     * {@link #setMaxEms(int)} or {@link #setEms(int)}.
     *
     * @param maxPixels the maximum width of TextView in terms of pixels
     *
     * @see #getMaxWidth()
     * @see #setWidth(int)
     *
     * @attr ref android.R.styleable#TextView_maxWidth
     */
    @android.view.RemotableViewMethod
    public void setMaxWidth(int maxPixels) {
        mMaxWidth = maxPixels;
        mMaxWidthMode = PIXELS;

        requestLayout();
        invalidate();
    }

    /**
     * Returns the maximum width of TextView in terms of pixels or -1 if the maximum width was set
     * using {@link #setMaxEms(int)} or {@link #setEms(int)}.
     *
     * @return the maximum width of TextView in terms of pixels. -1 if the maximum width is not
     *         defined in pixels
     *
     * @see #setMaxWidth(int)
     * @see #setWidth(int)
     *
     * @attr ref android.R.styleable#TextView_maxWidth
     */
    @InspectableProperty
    public int getMaxWidth() {
        return mMaxWidthMode == PIXELS ? mMaxWidth : -1;
    }

    /**
     * Sets the width of the TextView to be exactly {@code ems} wide.
     *
     * This value is used for width calculation if LayoutParams does not force TextView to have an
     * exact width. Setting this value overrides previous minimum/maximum configurations such as
     * {@link #setMinEms(int)} or {@link #setMaxEms(int)}.
     *
     * @param ems the exact width of the TextView in terms of ems
     *
     * @see #setWidth(int)
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
     * Sets the width of the TextView to be exactly {@code pixels} wide.
     * <p>
     * This value is used for width calculation if LayoutParams does not force TextView to have an
     * exact width. Setting this value overrides previous minimum/maximum width configurations
     * such as {@link #setMinWidth(int)} or {@link #setMaxWidth(int)}.
     *
     * @param pixels the exact width of the TextView in terms of pixels
     *
     * @see #setEms(int)
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
     * Sets line spacing for this TextView.  Each line other than the last line will have its height
     * multiplied by {@code mult} and have {@code add} added to it.
     *
     * @param add The value in pixels that should be added to each line other than the last line.
     *            This will be applied after the multiplier
     * @param mult The value by which each line height other than the last line will be multiplied
     *             by
     *
     * @attr ref android.R.styleable#TextView_lineSpacingExtra
     * @attr ref android.R.styleable#TextView_lineSpacingMultiplier
     */
    public void setLineSpacing(float add, float mult) {
        if (mSpacingAdd != add || mSpacingMult != mult) {
            mSpacingAdd = add;
            mSpacingMult = mult;

            if (mLayout != null) {
                nullLayouts();
                requestLayout();
                invalidate();
            }
        }
    }

    /**
     * Gets the line spacing multiplier
     *
     * @return the value by which each line's height is multiplied to get its actual height.
     *
     * @see #setLineSpacing(float, float)
     * @see #getLineSpacingExtra()
     *
     * @attr ref android.R.styleable#TextView_lineSpacingMultiplier
     */
    @InspectableProperty
    public float getLineSpacingMultiplier() {
        return mSpacingMult;
    }

    /**
     * Gets the line spacing extra space
     *
     * @return the extra space that is added to the height of each lines of this TextView.
     *
     * @see #setLineSpacing(float, float)
     * @see #getLineSpacingMultiplier()
     *
     * @attr ref android.R.styleable#TextView_lineSpacingExtra
     */
    @InspectableProperty
    public float getLineSpacingExtra() {
        return mSpacingAdd;
    }

    /**
     * Sets an explicit line height for this TextView. This is equivalent to the vertical distance
     * between subsequent baselines in the TextView.
     *
     * @param lineHeight the line height in pixels
     *
     * @see #setLineSpacing(float, float)
     * @see #getLineSpacingExtra()
     *
     * @attr ref android.R.styleable#TextView_lineHeight
     */
    @android.view.RemotableViewMethod
    public void setLineHeight(@Px @IntRange(from = 0) int lineHeight) {
        setLineHeightPx(lineHeight);
    }

    private void setLineHeightPx(@Px @FloatRange(from = 0) float lineHeight) {
        Preconditions.checkArgumentNonnegative((int) lineHeight);

        final int fontHeight = getPaint().getFontMetricsInt(null);
        // Make sure we don't setLineSpacing if it's not needed to avoid unnecessary redraw.
        // TODO(b/274974975): should this also check if lineSpacing needs to change?
        if (lineHeight != fontHeight) {
            // Set lineSpacingExtra by the difference of lineSpacing with lineHeight
            setLineSpacing(lineHeight - fontHeight, 1f);

            mLineHeightComplexDimen =
                        TypedValue.createComplexDimension(lineHeight, TypedValue.COMPLEX_UNIT_PX);
        }
    }

    /**
     * Sets an explicit line height to a given unit and value for this TextView. This is equivalent
     * to the vertical distance between subsequent baselines in the TextView. See {@link
     * TypedValue} for the possible dimension units.
     *
     * @param unit The desired dimension unit. SP units are strongly recommended so that line height
     *             stays proportional to the text size when fonts are scaled up for accessibility.
     * @param lineHeight The desired line height in the given units.
     *
     * @see #setLineSpacing(float, float)
     * @see #getLineSpacingExtra()
     *
     * @attr ref android.R.styleable#TextView_lineHeight
     */
    @android.view.RemotableViewMethod
    public void setLineHeight(
            @TypedValue.ComplexDimensionUnit int unit,
            @FloatRange(from = 0) float lineHeight
    ) {
        var metrics = getDisplayMetricsOrSystem();
        // We can avoid the recalculation if we know non-linear font scaling isn't being used
        // (an optimization for the majority case).
        // We also don't try to do the recalculation unless both textSize and lineHeight are in SP.
        if (!FontScaleConverterFactory.isNonLinearFontScalingActive(
                    getResources().getConfiguration().fontScale)
                || unit != TypedValue.COMPLEX_UNIT_SP
                || mTextSizeUnit != TypedValue.COMPLEX_UNIT_SP
        ) {
            setLineHeightPx(TypedValue.applyDimension(unit, lineHeight, metrics));

            // Do this last so it overwrites what setLineHeightPx() sets it to.
            mLineHeightComplexDimen = TypedValue.createComplexDimension(lineHeight, unit);
            return;
        }

        // Recalculate a proportional line height when non-linear font scaling is in effect.
        // Otherwise, a desired 2x line height at font scale 1.0 will not be 2x at font scale 2.0,
        // due to non-linear font scaling compressing higher SP sizes. See b/273326061 for details.
        // We know they are using SP units for both the text size and the line height
        // at this point, so determine the ratio between them. This is the *intended* line spacing
        // multiplier if font scale == 1.0. We can then determine what the pixel value for the line
        // height would be if we preserved proportions.
        var textSizePx = getTextSize();
        var textSizeSp = TypedValue.convertPixelsToDimension(
                TypedValue.COMPLEX_UNIT_SP,
                textSizePx,
                metrics
        );
        var ratio = lineHeight / textSizeSp;
        setLineHeightPx(textSizePx * ratio);

        // Do this last so it overwrites what setLineHeightPx() sets it to.
        mLineHeightComplexDimen = TypedValue.createComplexDimension(lineHeight, unit);
    }

    private void maybeRecalculateLineHeight() {
        if (mLineHeightComplexDimen == 0) {
            return;
        }
        int unit = TypedValue.getUnitFromComplexDimension(mLineHeightComplexDimen);
        if (unit != TypedValue.COMPLEX_UNIT_SP) {
            // The lineHeight was never supplied in SP, so we didn't do any fancy recalculations
            // in setLineHeight(). We don't need to recalculate.
            return;
        }

        setLineHeight(unit, TypedValue.complexToFloat(mLineHeightComplexDimen));
    }

    /**
     * Set Highlights
     *
     * @param highlights A highlight object. Call with null for reset.
     *
     * @see #getHighlights()
     * @see Highlights
     */
    public void setHighlights(@Nullable Highlights highlights) {
        mHighlights = highlights;
        mHighlightPathsBogus = true;
        invalidate();
    }

    /**
     * Returns highlights
     *
     * @return a highlight to be drawn. null if no highlight was set.
     *
     * @see #setHighlights(Highlights)
     * @see Highlights
     *
     */
    @Nullable
    public Highlights getHighlights() {
        return mHighlights;
    }

    /**
     * Sets the search result ranges with flatten range representation.
     *
     * Ranges are represented of flattened inclusive start and exclusive end integers array. The
     * inclusive start offset of the {@code i}-th range is stored in {@code 2 * i}-th of the array.
     * The exclusive end offset of the {@code i}-th range is stored in {@code 2* i + 1}-th of the
     * array. For example, the two ranges: (1, 2) and (3, 4) are flattened into single int array
     * [1, 2, 3, 4].
     *
     * TextView will render the search result with the highlights with specified color in the theme.
     * If there is a focused search result, it is rendered with focused color. By calling this
     * method, the focused search index will be cleared.
     *
     * @attr ref android.R.styleable#TextView_searchResultHighlightColor
     * @attr ref android.R.styleable#TextAppearance_searchResultHighlightColor
     * @attr ref android.R.styleable#TextView_focusedSearchResultHighlightColor
     * @attr ref android.R.styleable#TextAppearance_focusedSearchResultHighlightColor
     *
     * @see #getSearchResultHighlights()
     * @see #setFocusedSearchResultIndex(int)
     * @see #getFocusedSearchResultIndex()
     * @see #setSearchResultHighlightColor(int)
     * @see #getSearchResultHighlightColor()
     * @see #setFocusedSearchResultHighlightColor(int)
     * @see #getFocusedSearchResultHighlightColor()
     *
     * @param ranges the flatten ranges of the search result. null for clear.
     */
    public void setSearchResultHighlights(@Nullable int... ranges) {
        if (ranges == null) {
            mSearchResultHighlights = null;
            mHighlightPathsBogus = true;
            return;
        }
        if (ranges.length % 2 == 1) {
            throw new IllegalArgumentException(
                    "Flatten ranges must have even numbered elements");
        }
        for (int j = 0; j < ranges.length / 2; ++j) {
            int start = ranges[j * 2];
            int end = ranges[j * 2 + 1];
            if (start > end) {
                throw new IllegalArgumentException(
                        "Reverse range found in the flatten range: " + start + ", " + end + ""
                                + " at " + j + "-th range");
            }
        }
        mHighlightPathsBogus = true;
        mSearchResultHighlights = ranges;
        mFocusedSearchResultIndex = FOCUSED_SEARCH_RESULT_INDEX_NONE;
        invalidate();
    }

    /**
     * Gets the current search result ranges.
     *
     * @see #setSearchResultHighlights(int[])
     * @see #setFocusedSearchResultIndex(int)
     * @see #getFocusedSearchResultIndex()
     * @see #setSearchResultHighlightColor(int)
     * @see #getSearchResultHighlightColor()
     * @see #setFocusedSearchResultHighlightColor(int)
     * @see #getFocusedSearchResultHighlightColor()
     *
     * @return a flatten search result ranges. null if not available.
     */
    @Nullable
    public int[] getSearchResultHighlights() {
        return mSearchResultHighlights;
    }

    /**
     * A special index used for {@link #setFocusedSearchResultIndex(int)} and
     * {@link #getFocusedSearchResultIndex()} inidicating there is no focused search result.
     */
    public static final int FOCUSED_SEARCH_RESULT_INDEX_NONE = -1;

    /**
     * Sets the focused search result index.
     *
     * The focused search result is drawn in a focused color.
     * Calling {@link #FOCUSED_SEARCH_RESULT_INDEX_NONE} for clearing focused search result.
     *
     * This method must be called after setting search result ranges by
     * {@link #setSearchResultHighlights(int[])}.
     *
     * @attr ref android.R.styleable#TextView_searchResultHighlightColor
     * @attr ref android.R.styleable#TextAppearance_searchResultHighlightColor
     * @attr ref android.R.styleable#TextView_focusedSearchResultHighlightColor
     * @attr ref android.R.styleable#TextAppearance_focusedSearchResultHighlightColor
     *
     * @see #setSearchResultHighlights(int[])
     * @see #getSearchResultHighlights()
     * @see #setFocusedSearchResultIndex(int)
     * @see #getFocusedSearchResultIndex()
     * @see #setSearchResultHighlightColor(int)
     * @see #getSearchResultHighlightColor()
     * @see #setFocusedSearchResultHighlightColor(int)
     * @see #getFocusedSearchResultHighlightColor()
     *
     * @param index a focused search index or {@link #FOCUSED_SEARCH_RESULT_INDEX_NONE}
     */
    public void setFocusedSearchResultIndex(int index) {
        if (mSearchResultHighlights == null) {
            throw new IllegalArgumentException("Search result range must be set beforehand.");
        }
        if (index < -1 || index >= mSearchResultHighlights.length / 2) {
            throw new IllegalArgumentException("Focused index(" + index + ") must be larger than "
                    + "-1 and less than range count(" + (mSearchResultHighlights.length / 2) + ")");
        }
        mFocusedSearchResultIndex = index;
        mHighlightPathsBogus = true;
        invalidate();
    }

    /**
     * Gets the focused search result index.
     *
     * @attr ref android.R.styleable#TextView_searchResultHighlightColor
     * @attr ref android.R.styleable#TextAppearance_searchResultHighlightColor
     * @attr ref android.R.styleable#TextView_focusedSearchResultHighlightColor
     * @attr ref android.R.styleable#TextAppearance_focusedSearchResultHighlightColor
     *
     * @see #setSearchResultHighlights(int[])
     * @see #getSearchResultHighlights()
     * @see #setFocusedSearchResultIndex(int)
     * @see #getFocusedSearchResultIndex()
     * @see #setSearchResultHighlightColor(int)
     * @see #getSearchResultHighlightColor()
     * @see #setFocusedSearchResultHighlightColor(int)
     * @see #getFocusedSearchResultHighlightColor()

     * @return a focused search index or {@link #FOCUSED_SEARCH_RESULT_INDEX_NONE}
     */
    public int getFocusedSearchResultIndex() {
        return mFocusedSearchResultIndex;
    }

    /**
     * Sets the search result highlight color.
     *
     * @attr ref android.R.styleable#TextView_searchResultHighlightColor
     * @attr ref android.R.styleable#TextAppearance_searchResultHighlightColor
     * @attr ref android.R.styleable#TextView_focusedSearchResultHighlightColor
     * @attr ref android.R.styleable#TextAppearance_focusedSearchResultHighlightColor
     *
     * @see #setSearchResultHighlights(int[])
     * @see #getSearchResultHighlights()
     * @see #setFocusedSearchResultIndex(int)
     * @see #getFocusedSearchResultIndex()
     * @see #setSearchResultHighlightColor(int)
     * @see #getSearchResultHighlightColor()
     * @see #setFocusedSearchResultHighlightColor(int)
     * @see #getFocusedSearchResultHighlightColor()

     * @param color a search result highlight color.
     */
    public void setSearchResultHighlightColor(@ColorInt int color) {
        mSearchResultHighlightColor = color;
    }

    /**
     * Gets the search result highlight color.
     *
     * @attr ref android.R.styleable#TextView_searchResultHighlightColor
     * @attr ref android.R.styleable#TextAppearance_searchResultHighlightColor
     * @attr ref android.R.styleable#TextView_focusedSearchResultHighlightColor
     * @attr ref android.R.styleable#TextAppearance_focusedSearchResultHighlightColor
     *
     * @see #setSearchResultHighlights(int[])
     * @see #getSearchResultHighlights()
     * @see #setFocusedSearchResultIndex(int)
     * @see #getFocusedSearchResultIndex()
     * @see #setSearchResultHighlightColor(int)
     * @see #getSearchResultHighlightColor()
     * @see #setFocusedSearchResultHighlightColor(int)
     * @see #getFocusedSearchResultHighlightColor()

     * @return a search result highlight color.
     */
    @ColorInt
    public int getSearchResultHighlightColor() {
        return mSearchResultHighlightColor;
    }

    /**
     * Sets focused search result highlight color.
     *
     * @attr ref android.R.styleable#TextView_searchResultHighlightColor
     * @attr ref android.R.styleable#TextAppearance_searchResultHighlightColor
     * @attr ref android.R.styleable#TextView_focusedSearchResultHighlightColor
     * @attr ref android.R.styleable#TextAppearance_focusedSearchResultHighlightColor
     *
     * @see #setSearchResultHighlights(int[])
     * @see #getSearchResultHighlights()
     * @see #setFocusedSearchResultIndex(int)
     * @see #getFocusedSearchResultIndex()
     * @see #setSearchResultHighlightColor(int)
     * @see #getSearchResultHighlightColor()
     * @see #setFocusedSearchResultHighlightColor(int)
     * @see #getFocusedSearchResultHighlightColor()

     * @param color a focused search result highlight color.
     */
    public void setFocusedSearchResultHighlightColor(@ColorInt int color) {
        mFocusedSearchResultHighlightColor = color;
    }

    /**
     * Gets focused search result highlight color.
     *
     * @attr ref android.R.styleable#TextView_searchResultHighlightColor
     * @attr ref android.R.styleable#TextAppearance_searchResultHighlightColor
     * @attr ref android.R.styleable#TextView_focusedSearchResultHighlightColor
     * @attr ref android.R.styleable#TextAppearance_focusedSearchResultHighlightColor
     *
     * @see #setSearchResultHighlights(int[])
     * @see #getSearchResultHighlights()
     * @see #setFocusedSearchResultIndex(int)
     * @see #getFocusedSearchResultIndex()
     * @see #setSearchResultHighlightColor(int)
     * @see #getSearchResultHighlightColor()
     * @see #setFocusedSearchResultHighlightColor(int)
     * @see #getFocusedSearchResultHighlightColor()

     * @return a focused search result highlight color.
     */
    @ColorInt
    public int getFocusedSearchResultHighlightColor() {
        return mFocusedSearchResultHighlightColor;
    }

    /**
     * Highlights the text range (from inclusive start offset to exclusive end offset) to show what
     * will be selected by the ongoing select handwriting gesture. While the gesture preview
     * highlight is shown, the selection or cursor is hidden. If the text or selection is changed,
     * the gesture preview highlight will be cleared.
     */
    private void setSelectGesturePreviewHighlight(int start, int end) {
        // Selection preview highlight color is the same as selection highlight color.
        setGesturePreviewHighlight(start, end, mHighlightColor);
    }

    /**
     * Highlights the text range (from inclusive start offset to exclusive end offset) to show what
     * will be deleted by the ongoing delete handwriting gesture. While the gesture preview
     * highlight is shown, the selection or cursor is hidden. If the text or selection is changed,
     * the gesture preview highlight will be cleared.
     */
    private void setDeleteGesturePreviewHighlight(int start, int end) {
        // Deletion preview highlight color is 20% opacity of the default text color.
        int color = mTextColor.getDefaultColor();
        color = ColorUtils.setAlphaComponent(color, (int) (0.2f * Color.alpha(color)));
        setGesturePreviewHighlight(start, end, color);
    }

    private void setGesturePreviewHighlight(int start, int end, int color) {
        mGesturePreviewHighlightStart = start;
        mGesturePreviewHighlightEnd = end;
        if (mGesturePreviewHighlightPaint == null) {
            mGesturePreviewHighlightPaint = new Paint();
            mGesturePreviewHighlightPaint.setStyle(Paint.Style.FILL);
        }
        mGesturePreviewHighlightPaint.setColor(color);

        if (mEditor != null) {
            mEditor.hideCursorAndSpanControllers();
            mEditor.stopTextActionModeWithPreservingSelection();
        }

        mHighlightPathsBogus = true;
        invalidate();
    }

    private void clearGesturePreviewHighlight() {
        mGesturePreviewHighlightStart = -1;
        mGesturePreviewHighlightEnd = -1;
        mHighlightPathsBogus = true;
        invalidate();
    }

    boolean hasGesturePreviewHighlight() {
        return mGesturePreviewHighlightStart >= 0;
    }

    /**
     * Convenience method to append the specified text to the TextView's
     * display buffer, upgrading it to {@link android.widget.TextView.BufferType#EDITABLE}
     * if it was not already editable.
     *
     * @param text text to be appended to the already displayed text
     */
    public final void append(CharSequence text) {
        append(text, 0, text.length());
    }

    /**
     * Convenience method to append the specified text slice to the TextView's
     * display buffer, upgrading it to {@link android.widget.TextView.BufferType#EDITABLE}
     * if it was not already editable.
     *
     * @param text text to be appended to the already displayed text
     * @param start the index of the first character in the {@code text}
     * @param end the index of the character following the last character in the {@code text}
     *
     * @see Appendable#append(CharSequence, int, int)
     */
    public void append(CharSequence text, int start, int end) {
        if (!(mText instanceof Editable)) {
            setText(mText, BufferType.EDITABLE);
        }

        ((Editable) mText).append(text, start, end);

        if (mAutoLinkMask != 0) {
            boolean linksWereAdded = Linkify.addLinks(mSpannable, mAutoLinkMask);
            // Do not change the movement method for text that support text selection as it
            // would prevent an arbitrary cursor displacement.
            if (linksWereAdded && mLinksClickable && !textCanBeSelected()) {
                setMovementMethod(LinkMovementMethod.getInstance());
            }
        }
    }

    private void updateTextColors() {
        boolean inval = false;
        final int[] drawableState = getDrawableState();
        int color = mTextColor.getColorForState(drawableState, 0);
        if (color != mCurTextColor) {
            mCurTextColor = color;
            inval = true;
        }
        if (mLinkTextColor != null) {
            color = mLinkTextColor.getColorForState(drawableState, 0);
            if (color != mTextPaint.linkColor) {
                mTextPaint.linkColor = color;
                inval = true;
            }
        }
        if (mHintTextColor != null) {
            color = mHintTextColor.getColorForState(drawableState, 0);
            if (color != mCurHintTextColor) {
                mCurHintTextColor = color;
                if (mText.length() == 0) {
                    inval = true;
                }
            }
        }
        if (inval) {
            // Text needs to be redrawn with the new color
            if (mEditor != null) mEditor.invalidateTextDisplayList();
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

        if (mDrawables != null) {
            final int[] state = getDrawableState();
            for (Drawable dr : mDrawables.mShowing) {
                if (dr != null && dr.isStateful() && dr.setState(state)) {
                    invalidateDrawable(dr);
                }
            }
        }
    }

    @Override
    public void drawableHotspotChanged(float x, float y) {
        super.drawableHotspotChanged(x, y);

        if (mDrawables != null) {
            for (Drawable dr : mDrawables.mShowing) {
                if (dr != null) {
                    dr.setHotspot(x, y);
                }
            }
        }
    }

    @Override
    public Parcelable onSaveInstanceState() {
        Parcelable superState = super.onSaveInstanceState();

        // Save state if we are forced to
        final boolean freezesText = getFreezesText();
        boolean hasSelection = false;
        int start = -1;
        int end = -1;

        if (mText != null) {
            start = getSelectionStart();
            end = getSelectionEnd();
            if (start >= 0 || end >= 0) {
                // Or save state if there is a selection
                hasSelection = true;
            }
        }

        if (freezesText || hasSelection) {
            SavedState ss = new SavedState(superState);

            if (freezesText) {
                if (mText instanceof Spanned) {
                    final Spannable sp = new SpannableStringBuilder(mText);

                    if (mEditor != null) {
                        removeMisspelledSpans(sp);
                        sp.removeSpan(mEditor.mSuggestionRangeSpan);
                    }

                    ss.text = sp;
                } else {
                    ss.text = mText.toString();
                }
            }

            if (hasSelection) {
                // XXX Should also save the current scroll position!
                ss.selStart = start;
                ss.selEnd = end;
            }

            if (isFocused() && start >= 0 && end >= 0) {
                ss.frozenWithFocus = true;
            }

            ss.error = getError();

            if (mEditor != null) {
                ss.editorState = mEditor.saveInstanceState();
            }
            return ss;
        }

        return superState;
    }

    void removeMisspelledSpans(Spannable spannable) {
        SuggestionSpan[] suggestionSpans = spannable.getSpans(0, spannable.length(),
                SuggestionSpan.class);
        for (int i = 0; i < suggestionSpans.length; i++) {
            int flags = suggestionSpans[i].getFlags();
            if ((flags & SuggestionSpan.FLAG_EASY_CORRECT) != 0
                    && (flags & SuggestionSpan.FLAG_MISSPELLED) != 0) {
                spannable.removeSpan(suggestionSpans[i]);
            }
        }
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
        if (!(state instanceof SavedState)) {
            super.onRestoreInstanceState(state);
            return;
        }

        SavedState ss = (SavedState) state;
        super.onRestoreInstanceState(ss.getSuperState());

        // XXX restore buffer type too, as well as lots of other stuff
        if (ss.text != null) {
            setText(ss.text);
        }

        if (ss.selStart >= 0 && ss.selEnd >= 0) {
            if (mSpannable != null) {
                int len = mText.length();

                if (ss.selStart > len || ss.selEnd > len) {
                    String restored = "";

                    if (ss.text != null) {
                        restored = "(restored) ";
                    }

                    Log.e(LOG_TAG, "Saved cursor position " + ss.selStart + "/" + ss.selEnd
                            + " out of range for " + restored + "text " + mText);
                } else {
                    Selection.setSelection(mSpannable, ss.selStart, ss.selEnd);

                    if (ss.frozenWithFocus) {
                        createEditorIfNeeded();
                        mEditor.mFrozenWithFocus = true;
                    }
                }
            }
        }

        if (ss.error != null) {
            final CharSequence error = ss.error;
            // Display the error later, after the first layout pass
            post(new Runnable() {
                public void run() {
                    if (mEditor == null || !mEditor.mErrorWasChanged) {
                        setError(error);
                    }
                }
            });
        }

        if (ss.editorState != null) {
            createEditorIfNeeded();
            mEditor.restoreInstanceState(ss.editorState);
        }
    }

    /**
     * Control whether this text view saves its entire text contents when
     * freezing to an icicle, in addition to dynamic state such as cursor
     * position.  By default this is false, not saving the text.  Set to true
     * if the text in the text view is not being saved somewhere else in
     * persistent storage (such as in a content provider) so that if the
     * view is later thawed the user will not lose their data. For
     * {@link android.widget.EditText} it is always enabled, regardless of
     * the value of the attribute.
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
     * in frozen icicles. For {@link android.widget.EditText} it always returns true.
     *
     * @return Returns true if text is included, false if it isn't.
     *
     * @see #setFreezesText
     */
    @InspectableProperty
    public boolean getFreezesText() {
        return mFreezesText;
    }

    ///////////////////////////////////////////////////////////////////////////

    /**
     * Sets the Factory used to create new {@link Editable Editables}.
     *
     * @param factory {@link android.text.Editable.Factory Editable.Factory} to be used
     *
     * @see android.text.Editable.Factory
     * @see android.widget.TextView.BufferType#EDITABLE
     */
    public final void setEditableFactory(Editable.Factory factory) {
        mEditableFactory = factory;
        setText(mText);
    }

    /**
     * Sets the Factory used to create new {@link Spannable Spannables}.
     *
     * @param factory {@link android.text.Spannable.Factory Spannable.Factory} to be used
     *
     * @see android.text.Spannable.Factory
     * @see android.widget.TextView.BufferType#SPANNABLE
     */
    public final void setSpannableFactory(Spannable.Factory factory) {
        mSpannableFactory = factory;
        setText(mText);
    }

    /**
     * Sets the text to be displayed. TextView <em>does not</em> accept
     * HTML-like formatting, which you can do with text strings in XML resource files.
     * To style your strings, attach android.text.style.* objects to a
     * {@link android.text.SpannableString}, or see the
     * <a href="{@docRoot}guide/topics/resources/available-resources.html#stringresources">
     * Available Resource Types</a> documentation for an example of setting
     * formatted text in the XML resource file.
     * <p/>
     * When required, TextView will use {@link android.text.Spannable.Factory} to create final or
     * intermediate {@link Spannable Spannables}. Likewise it will use
     * {@link android.text.Editable.Factory} to create final or intermediate
     * {@link Editable Editables}.
     *
     * If the passed text is a {@link PrecomputedText} but the parameters used to create the
     * PrecomputedText mismatches with this TextView, IllegalArgumentException is thrown. To ensure
     * the parameters match, you can call {@link TextView#setTextMetricsParams} before calling this.
     *
     * @param text text to be displayed
     *
     * @attr ref android.R.styleable#TextView_text
     * @throws IllegalArgumentException if the passed text is a {@link PrecomputedText} but the
     *                                  parameters used to create the PrecomputedText mismatches
     *                                  with this TextView.
     */
    @android.view.RemotableViewMethod
    public final void setText(CharSequence text) {
        setText(text, mBufferType);
    }

    /**
     * Sets the text to be displayed but retains the cursor position. Same as
     * {@link #setText(CharSequence)} except that the cursor position (if any) is retained in the
     * new text.
     * <p/>
     * When required, TextView will use {@link android.text.Spannable.Factory} to create final or
     * intermediate {@link Spannable Spannables}. Likewise it will use
     * {@link android.text.Editable.Factory} to create final or intermediate
     * {@link Editable Editables}.
     *
     * @param text text to be displayed
     *
     * @see #setText(CharSequence)
     */
    @android.view.RemotableViewMethod
    public final void setTextKeepState(CharSequence text) {
        setTextKeepState(text, mBufferType);
    }

    /**
     * Sets the text to be displayed and the {@link android.widget.TextView.BufferType}.
     * <p/>
     * When required, TextView will use {@link android.text.Spannable.Factory} to create final or
     * intermediate {@link Spannable Spannables}. Likewise it will use
     * {@link android.text.Editable.Factory} to create final or intermediate
     * {@link Editable Editables}.
     *
     * Subclasses overriding this method should ensure that the following post condition holds,
     * in order to guarantee the safety of the view's measurement and layout operations:
     * regardless of the input, after calling #setText both {@code mText} and {@code mTransformed}
     * will be different from {@code null}.
     *
     * @param text text to be displayed
     * @param type a {@link android.widget.TextView.BufferType} which defines whether the text is
     *              stored as a static text, styleable/spannable text, or editable text
     *
     * @see #setText(CharSequence)
     * @see android.widget.TextView.BufferType
     * @see #setSpannableFactory(Spannable.Factory)
     * @see #setEditableFactory(Editable.Factory)
     *
     * @attr ref android.R.styleable#TextView_text
     * @attr ref android.R.styleable#TextView_bufferType
     */
    public void setText(CharSequence text, BufferType type) {
        setText(text, type, true, 0);

        // drop any potential mCharWrappper leaks
        mCharWrapper = null;
    }

    @UnsupportedAppUsage
    private void setText(CharSequence text, BufferType type,
                         boolean notifyBefore, int oldlen) {
        mTextSetFromXmlOrResourceId = false;
        if (text == null) {
            text = "";
        }

        // If suggestions are not enabled, remove the suggestion spans from the text
        if (!isSuggestionsEnabled()) {
            text = removeSuggestionSpans(text);
        }

        if (!mUserSetTextScaleX) mTextPaint.setTextScaleX(1.0f);

        if (text instanceof Spanned
                && ((Spanned) text).getSpanStart(TextUtils.TruncateAt.MARQUEE) >= 0) {
            if (ViewConfiguration.get(mContext).isFadingMarqueeEnabled()) {
                setHorizontalFadingEdgeEnabled(true);
                mMarqueeFadeMode = MARQUEE_FADE_NORMAL;
            } else {
                setHorizontalFadingEdgeEnabled(false);
                mMarqueeFadeMode = MARQUEE_FADE_SWITCH_SHOW_ELLIPSIS;
            }
            setEllipsize(TextUtils.TruncateAt.MARQUEE);
        }

        int n = mFilters.length;
        for (int i = 0; i < n; i++) {
            CharSequence out = mFilters[i].filter(text, 0, text.length(), EMPTY_SPANNED, 0, 0);
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

        PrecomputedText precomputed =
                (text instanceof PrecomputedText) ? (PrecomputedText) text : null;
        if (type == BufferType.EDITABLE || getKeyListener() != null
                || needEditableForNotification) {
            createEditorIfNeeded();
            mEditor.forgetUndoRedo();
            mEditor.scheduleRestartInputForSetText();
            Editable t = mEditableFactory.newEditable(text);
            text = t;
            setFilters(t, mFilters);
        } else if (precomputed != null) {
            if (mTextDir == null) {
                mTextDir = getTextDirectionHeuristic();
            }
            final boolean autoPhraseBreaking =
                    !mUserSpeficiedLineBreakwordStyle && FeatureFlagUtils.isEnabled(mContext,
                            FeatureFlagUtils.SETTINGS_AUTO_TEXT_WRAPPING);
            final @PrecomputedText.Params.CheckResultUsableResult int checkResult =
                    precomputed.getParams().checkResultUsable(getPaint(), mTextDir, mBreakStrategy,
                            mHyphenationFrequency, LineBreakConfig.getLineBreakConfig(
                                    mLineBreakStyle, mLineBreakWordStyle, autoPhraseBreaking));
            switch (checkResult) {
                case PrecomputedText.Params.UNUSABLE:
                    throw new IllegalArgumentException(
                        "PrecomputedText's Parameters don't match the parameters of this TextView."
                        + "Consider using setTextMetricsParams(precomputedText.getParams()) "
                        + "to override the settings of this TextView: "
                        + "PrecomputedText: " + precomputed.getParams()
                        + "TextView: " + getTextMetricsParams());
                case PrecomputedText.Params.NEED_RECOMPUTE:
                    precomputed = PrecomputedText.create(precomputed, getTextMetricsParams());
                    break;
                case PrecomputedText.Params.USABLE:
                    // pass through
            }
        } else if (type == BufferType.SPANNABLE || mMovement != null) {
            text = mSpannableFactory.newSpannable(text);
        } else if (!(text instanceof CharWrapper)) {
            text = TextUtils.stringOrSpannedString(text);
        }

        @AccessibilityUtils.A11yTextChangeType int a11yTextChangeType = AccessibilityUtils.NONE;
        if (AccessibilityManager.getInstance(mContext).isEnabled()) {
            a11yTextChangeType = AccessibilityUtils.textOrSpanChanged(text, mText);
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
                setTextInternal(text);
                if (a11yTextChangeType == AccessibilityUtils.NONE) {
                    a11yTextChangeType = AccessibilityUtils.PARCELABLE_SPAN;
                }

                // Do not change the movement method for text that support text selection as it
                // would prevent an arbitrary cursor displacement.
                if (mLinksClickable && !textCanBeSelected()) {
                    setMovementMethod(LinkMovementMethod.getInstance());
                }
            }
        }

        mBufferType = type;
        setTextInternal(text);

        if (mTransformation == null) {
            mTransformed = text;
        } else {
            mTransformed = mTransformation.getTransformation(text, this);
        }
        if (mTransformed == null) {
            // Should not happen if the transformation method follows the non-null postcondition.
            mTransformed = "";
        }

        final int textLength = text.length();
        final boolean isOffsetMapping = mTransformed instanceof OffsetMapping;

        if (text instanceof Spannable && (!mAllowTransformationLengthChange || isOffsetMapping)) {
            Spannable sp = (Spannable) text;

            // Remove any ChangeWatchers that might have come from other TextViews.
            final ChangeWatcher[] watchers = sp.getSpans(0, sp.length(), ChangeWatcher.class);
            final int count = watchers.length;
            for (int i = 0; i < count; i++) {
                sp.removeSpan(watchers[i]);
            }

            if (mChangeWatcher == null) mChangeWatcher = new ChangeWatcher();

            sp.setSpan(mChangeWatcher, 0, textLength, Spanned.SPAN_INCLUSIVE_INCLUSIVE
                    | (CHANGE_WATCHER_PRIORITY << Spanned.SPAN_PRIORITY_SHIFT));

            if (mEditor != null) mEditor.addSpanWatchers(sp);

            if (mTransformation != null) {
                final int priority = isOffsetMapping ? OFFSET_MAPPING_SPAN_PRIORITY : 0;
                sp.setSpan(mTransformation, 0, textLength, Spanned.SPAN_INCLUSIVE_INCLUSIVE
                        | (priority << Spanned.SPAN_PRIORITY_SHIFT));
            }

            if (mMovement != null) {
                mMovement.initialize(this, (Spannable) text);

                /*
                 * Initializing the movement method will have set the
                 * selection, so reset mSelectionMoved to keep that from
                 * interfering with the normal on-focus selection-setting.
                 */
                if (mEditor != null) mEditor.mSelectionMoved = false;
            }
        }

        if (mLayout != null) {
            checkForRelayout();
        }

        sendOnTextChanged(text, 0, oldlen, textLength);
        onTextChanged(text, 0, oldlen, textLength);

        mHideHint = false;

        if (a11yTextChangeType == AccessibilityUtils.TEXT) {
            notifyViewAccessibilityStateChangedIfNeeded(
                    AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT);
        } else if (a11yTextChangeType == AccessibilityUtils.PARCELABLE_SPAN) {
            notifyViewAccessibilityStateChangedIfNeeded(
                    AccessibilityEvent.CONTENT_CHANGE_TYPE_UNDEFINED);
        }

        if (needEditableForNotification) {
            sendAfterTextChanged((Editable) text);
        } else {
            notifyListeningManagersAfterTextChanged();
        }

        if (mEditor != null) {
            // SelectionModifierCursorController depends on textCanBeSelected, which depends on text
            mEditor.prepareCursorControllers();

            mEditor.maybeFireScheduledRestartInputForSetText();
        }
    }

    /**
     * Sets the TextView to display the specified slice of the specified
     * char array. You must promise that you will not change the contents
     * of the array except for right before another call to setText(),
     * since the TextView has no way to know that the text
     * has changed and that it needs to invalidate and re-layout.
     *
     * @throws NullPointerException if text is null
     * @throws IndexOutOfBoundsException if start or start+len are not in 0 to text.length
     *
     * @param text char array to be displayed
     * @param start start index in the char array
     * @param len length of char count after {@code start}
     */
    public final void setText(@NonNull char[] text, int start, int len) {
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

    /**
     * Sets the text to be displayed and the {@link android.widget.TextView.BufferType} but retains
     * the cursor position. Same as
     * {@link #setText(CharSequence, android.widget.TextView.BufferType)} except that the cursor
     * position (if any) is retained in the new text.
     * <p/>
     * When required, TextView will use {@link android.text.Spannable.Factory} to create final or
     * intermediate {@link Spannable Spannables}. Likewise it will use
     * {@link android.text.Editable.Factory} to create final or intermediate
     * {@link Editable Editables}.
     *
     * @param text text to be displayed
     * @param type a {@link android.widget.TextView.BufferType} which defines whether the text is
     *              stored as a static text, styleable/spannable text, or editable text
     *
     * @see #setText(CharSequence, android.widget.TextView.BufferType)
     */
    public final void setTextKeepState(CharSequence text, BufferType type) {
        int start = getSelectionStart();
        int end = getSelectionEnd();
        int len = text.length();

        setText(text, type);

        if (start >= 0 || end >= 0) {
            if (mSpannable != null) {
                Selection.setSelection(mSpannable,
                                       Math.max(0, Math.min(start, len)),
                                       Math.max(0, Math.min(end, len)));
            }
        }
    }

    /**
     * Sets the text to be displayed using a string resource identifier.
     *
     * @param resid the resource identifier of the string resource to be displayed
     *
     * @see #setText(CharSequence)
     *
     * @attr ref android.R.styleable#TextView_text
     */
    @android.view.RemotableViewMethod
    public final void setText(@StringRes int resid) {
        setText(getContext().getResources().getText(resid));
        mTextSetFromXmlOrResourceId = true;
        mTextId = resid;
    }

    /**
     * Sets the text to be displayed using a string resource identifier and the
     * {@link android.widget.TextView.BufferType}.
     * <p/>
     * When required, TextView will use {@link android.text.Spannable.Factory} to create final or
     * intermediate {@link Spannable Spannables}. Likewise it will use
     * {@link android.text.Editable.Factory} to create final or intermediate
     * {@link Editable Editables}.
     *
     * @param resid the resource identifier of the string resource to be displayed
     * @param type a {@link android.widget.TextView.BufferType} which defines whether the text is
     *              stored as a static text, styleable/spannable text, or editable text
     *
     * @see #setText(int)
     * @see #setText(CharSequence)
     * @see android.widget.TextView.BufferType
     * @see #setSpannableFactory(Spannable.Factory)
     * @see #setEditableFactory(Editable.Factory)
     *
     * @attr ref android.R.styleable#TextView_text
     * @attr ref android.R.styleable#TextView_bufferType
     */
    public final void setText(@StringRes int resid, BufferType type) {
        setText(getContext().getResources().getText(resid), type);
        mTextSetFromXmlOrResourceId = true;
        mTextId = resid;
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
        setHintInternal(hint);

        if (mEditor != null && isInputMethodTarget()) {
            mEditor.reportExtractedText();
        }
    }

    private void setHintInternal(CharSequence hint) {
        mHideHint = false;
        mHint = TextUtils.stringOrSpannedString(hint);

        if (mLayout != null) {
            checkForRelayout();
        }

        if (mText.length() == 0) {
            invalidate();
        }

        // Invalidate display list if hint is currently used
        if (mEditor != null && mText.length() == 0 && mHint != null) {
            mEditor.invalidateTextDisplayList();
        }
    }

    /**
     * Sets the text to be displayed when the text of the TextView is empty,
     * from a resource.
     *
     * @attr ref android.R.styleable#TextView_hint
     */
    @android.view.RemotableViewMethod
    public final void setHint(@StringRes int resid) {
        mHintId = resid;
        setHint(getContext().getResources().getText(resid));
    }

    /**
     * Returns the hint that is displayed when the text of the TextView
     * is empty.
     *
     * @attr ref android.R.styleable#TextView_hint
     */
    @InspectableProperty
    @ViewDebug.CapturedViewProperty
    public CharSequence getHint() {
        return mHint;
    }

    /**
     * Temporarily hides the hint text until the text is modified, or the hint text is modified, or
     * the view gains or loses focus.
     *
     * @hide
     */
    public void hideHint() {
        if (isShowingHint()) {
            mHideHint = true;
            invalidate();
        }
    }

    /**
     * Returns if the text is constrained to a single horizontally scrolling line ignoring new
     * line characters instead of letting it wrap onto multiple lines.
     *
     * @attr ref android.R.styleable#TextView_singleLine
     */
    @InspectableProperty
    public boolean isSingleLine() {
        return mSingleLine;
    }

    private static boolean isMultilineInputType(int type) {
        return (type & (EditorInfo.TYPE_MASK_CLASS | EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE))
                == (EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE);
    }

    /**
     * Removes the suggestion spans.
     */
    CharSequence removeSuggestionSpans(CharSequence text) {
        if (text instanceof Spanned) {
            Spannable spannable;
            if (text instanceof Spannable) {
                spannable = (Spannable) text;
            } else {
                spannable = mSpannableFactory.newSpannable(text);
            }

            SuggestionSpan[] spans = spannable.getSpans(0, text.length(), SuggestionSpan.class);
            if (spans.length == 0) {
                return text;
            } else {
                text = spannable;
            }

            for (int i = 0; i < spans.length; i++) {
                spannable.removeSpan(spans[i]);
            }
        }
        return text;
    }

    /**
     * Set the type of the content with a constant as defined for {@link EditorInfo#inputType}. This
     * will take care of changing the key listener, by calling {@link #setKeyListener(KeyListener)},
     * to match the given content type.  If the given content type is {@link EditorInfo#TYPE_NULL}
     * then a soft keyboard will not be displayed for this text view.
     *
     * Note that the maximum number of displayed lines (see {@link #setMaxLines(int)}) will be
     * modified if you change the {@link EditorInfo#TYPE_TEXT_FLAG_MULTI_LINE} flag of the input
     * type.
     *
     * @see #getInputType()
     * @see #setRawInputType(int)
     * @see android.text.InputType
     * @attr ref android.R.styleable#TextView_inputType
     */
    public void setInputType(int type) {
        final boolean wasPassword = isPasswordInputType(getInputType());
        final boolean wasVisiblePassword = isVisiblePasswordInputType(getInputType());
        setInputType(type, false);
        final boolean isPassword = isPasswordInputType(type);
        final boolean isVisiblePassword = isVisiblePasswordInputType(type);
        boolean forceUpdate = false;
        if (isPassword) {
            setTransformationMethod(PasswordTransformationMethod.getInstance());
            setTypefaceFromAttrs(null/* fontTypeface */, null /* fontFamily */, MONOSPACE,
                    Typeface.NORMAL, FontStyle.FONT_WEIGHT_UNSPECIFIED);
        } else if (isVisiblePassword) {
            if (mTransformation == PasswordTransformationMethod.getInstance()) {
                forceUpdate = true;
            }
            setTypefaceFromAttrs(null/* fontTypeface */, null /* fontFamily */, MONOSPACE,
                    Typeface.NORMAL, FontStyle.FONT_WEIGHT_UNSPECIFIED);
        } else if (wasPassword || wasVisiblePassword) {
            // not in password mode, clean up typeface and transformation
            setTypefaceFromAttrs(null/* fontTypeface */, null /* fontFamily */,
                    DEFAULT_TYPEFACE /* typeface index */, Typeface.NORMAL,
                    FontStyle.FONT_WEIGHT_UNSPECIFIED);
            if (mTransformation == PasswordTransformationMethod.getInstance()) {
                forceUpdate = true;
            }
        }

        boolean singleLine = !isMultilineInputType(type);

        // We need to update the single line mode if it has changed or we
        // were previously in password mode.
        if (mSingleLine != singleLine || forceUpdate) {
            // Change single line mode, but only change the transformation if
            // we are not in password mode.
            applySingleLine(singleLine, !isPassword, true, true);
        }

        if (!isSuggestionsEnabled()) {
            setTextInternal(removeSuggestionSpans(mText));
        }

        InputMethodManager imm = getInputMethodManager();
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
    boolean hasPasswordTransformationMethod() {
        return mTransformation instanceof PasswordTransformationMethod;
    }

    /**
     * Returns true if the current inputType is any type of password.
     *
     * @hide
     */
    public boolean isAnyPasswordInputType() {
        final int inputType = getInputType();
        return isPasswordInputType(inputType) || isVisiblePasswordInputType(inputType);
    }

    static boolean isPasswordInputType(int inputType) {
        final int variation =
                inputType & (EditorInfo.TYPE_MASK_CLASS | EditorInfo.TYPE_MASK_VARIATION);
        return variation
                == (EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_VARIATION_PASSWORD)
                || variation
                == (EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD)
                || variation
                == (EditorInfo.TYPE_CLASS_NUMBER | EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD);
    }

    private static boolean isVisiblePasswordInputType(int inputType) {
        final int variation =
                inputType & (EditorInfo.TYPE_MASK_CLASS | EditorInfo.TYPE_MASK_VARIATION);
        return variation
                == (EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
    }

    /**
     * Directly change the content type integer of the text view, without
     * modifying any other state.
     * @see #setInputType(int)
     * @see android.text.InputType
     * @attr ref android.R.styleable#TextView_inputType
     */
    public void setRawInputType(int type) {
        if (type == InputType.TYPE_NULL && mEditor == null) return; //TYPE_NULL is the default value
        createEditorIfNeeded();
        mEditor.mInputType = type;
    }

    @Override
    public String[] getAutofillHints() {
        String[] hints = super.getAutofillHints();
        if (isAnyPasswordInputType()) {
            if (!ArrayUtils.contains(hints, AUTOFILL_HINT_PASSWORD_AUTO)) {
                hints = ArrayUtils.appendElement(String.class, hints,
                        AUTOFILL_HINT_PASSWORD_AUTO);
            }
        }
        return hints;
    }

    /**
     * @return {@code null} if the key listener should use pre-O (locale-independent). Otherwise
     *         a {@code Locale} object that can be used to customize key various listeners.
     * @see DateKeyListener#getInstance(Locale)
     * @see DateTimeKeyListener#getInstance(Locale)
     * @see DigitsKeyListener#getInstance(Locale)
     * @see TimeKeyListener#getInstance(Locale)
     */
    @Nullable
    private Locale getCustomLocaleForKeyListenerOrNull() {
        if (!mUseInternationalizedInput) {
            // If the application does not target O, stick to the previous behavior.
            return null;
        }
        final LocaleList locales = getImeHintLocales();
        if (locales == null) {
            // If the application does not explicitly specify IME hint locale, also stick to the
            // previous behavior.
            return null;
        }
        return locales.get(0);
    }

    @UnsupportedAppUsage
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
            final Locale locale = getCustomLocaleForKeyListenerOrNull();
            input = DigitsKeyListener.getInstance(
                    locale,
                    (type & EditorInfo.TYPE_NUMBER_FLAG_SIGNED) != 0,
                    (type & EditorInfo.TYPE_NUMBER_FLAG_DECIMAL) != 0);
            if (locale != null) {
                // Override type, if necessary for i18n.
                int newType = input.getInputType();
                final int newClass = newType & EditorInfo.TYPE_MASK_CLASS;
                if (newClass != EditorInfo.TYPE_CLASS_NUMBER) {
                    // The class is different from the original class. So we need to override
                    // 'type'. But we want to keep the password flag if it's there.
                    if ((type & EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD) != 0) {
                        newType |= EditorInfo.TYPE_TEXT_VARIATION_PASSWORD;
                    }
                    type = newType;
                }
            }
        } else if (cls == EditorInfo.TYPE_CLASS_DATETIME) {
            final Locale locale = getCustomLocaleForKeyListenerOrNull();
            switch (type & EditorInfo.TYPE_MASK_VARIATION) {
                case EditorInfo.TYPE_DATETIME_VARIATION_DATE:
                    input = DateKeyListener.getInstance(locale);
                    break;
                case EditorInfo.TYPE_DATETIME_VARIATION_TIME:
                    input = TimeKeyListener.getInstance(locale);
                    break;
                default:
                    input = DateTimeKeyListener.getInstance(locale);
                    break;
            }
            if (mUseInternationalizedInput) {
                type = input.getInputType(); // Override type, if necessary for i18n.
            }
        } else if (cls == EditorInfo.TYPE_CLASS_PHONE) {
            input = DialerKeyListener.getInstance();
        } else {
            input = TextKeyListener.getInstance();
        }
        setRawInputType(type);
        mListenerChanged = false;
        if (direct) {
            createEditorIfNeeded();
            mEditor.mKeyListener = input;
        } else {
            setKeyListenerOnly(input);
        }
    }

    /**
     * Get the type of the editable content.
     *
     * @see #setInputType(int)
     * @see android.text.InputType
     */
    @InspectableProperty(flagMapping = {
            @FlagEntry(name = "none", mask = 0xffffffff, target = InputType.TYPE_NULL),
            @FlagEntry(
                    name = "text",
                    mask = InputType.TYPE_MASK_CLASS | InputType.TYPE_MASK_VARIATION,
                    target = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL),
            @FlagEntry(
                    name = "textUri",
                    mask = InputType.TYPE_MASK_CLASS | InputType.TYPE_MASK_VARIATION,
                    target = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI),
            @FlagEntry(
                    name = "textEmailAddress",
                    mask = InputType.TYPE_MASK_CLASS | InputType.TYPE_MASK_VARIATION,
                    target = InputType.TYPE_CLASS_TEXT
                            | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS),
            @FlagEntry(
                    name = "textEmailSubject",
                    mask = InputType.TYPE_MASK_CLASS | InputType.TYPE_MASK_VARIATION,
                    target = InputType.TYPE_CLASS_TEXT
                            | InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT),
            @FlagEntry(
                    name = "textShortMessage",
                    mask = InputType.TYPE_MASK_CLASS | InputType.TYPE_MASK_VARIATION,
                    target = InputType.TYPE_CLASS_TEXT
                            | InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE),
            @FlagEntry(
                    name = "textLongMessage",
                    mask = InputType.TYPE_MASK_CLASS | InputType.TYPE_MASK_VARIATION,
                    target = InputType.TYPE_CLASS_TEXT
                            | InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE),
            @FlagEntry(
                    name = "textPersonName",
                    mask = InputType.TYPE_MASK_CLASS | InputType.TYPE_MASK_VARIATION,
                    target = InputType.TYPE_CLASS_TEXT
                            | InputType.TYPE_TEXT_VARIATION_PERSON_NAME),
            @FlagEntry(
                    name = "textPostalAddress",
                    mask = InputType.TYPE_MASK_CLASS | InputType.TYPE_MASK_VARIATION,
                    target = InputType.TYPE_CLASS_TEXT
                            | InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS),
            @FlagEntry(
                    name = "textPassword",
                    mask = InputType.TYPE_MASK_CLASS | InputType.TYPE_MASK_VARIATION,
                    target = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD),
            @FlagEntry(
                    name = "textVisiblePassword",
                    mask = InputType.TYPE_MASK_CLASS | InputType.TYPE_MASK_VARIATION,
                    target = InputType.TYPE_CLASS_TEXT
                            | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD),
            @FlagEntry(
                    name = "textWebEditText",
                    mask = InputType.TYPE_MASK_CLASS | InputType.TYPE_MASK_VARIATION,
                    target = InputType.TYPE_CLASS_TEXT
                            | InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT),
            @FlagEntry(
                    name = "textFilter",
                    mask = InputType.TYPE_MASK_CLASS | InputType.TYPE_MASK_VARIATION,
                    target = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_FILTER),
            @FlagEntry(
                    name = "textPhonetic",
                    mask = InputType.TYPE_MASK_CLASS | InputType.TYPE_MASK_VARIATION,
                    target = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PHONETIC),
            @FlagEntry(
                    name = "textWebEmailAddress",
                    mask = InputType.TYPE_MASK_CLASS | InputType.TYPE_MASK_VARIATION,
                    target = InputType.TYPE_CLASS_TEXT
                            | InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS),
            @FlagEntry(
                    name = "textWebPassword",
                    mask = InputType.TYPE_MASK_CLASS | InputType.TYPE_MASK_VARIATION,
                    target = InputType.TYPE_CLASS_TEXT
                            | InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD),
            @FlagEntry(
                    name = "number",
                    mask = InputType.TYPE_MASK_CLASS | InputType.TYPE_MASK_VARIATION,
                    target = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_NORMAL),
            @FlagEntry(
                    name = "numberPassword",
                    mask = InputType.TYPE_MASK_CLASS | InputType.TYPE_MASK_VARIATION,
                    target = InputType.TYPE_CLASS_NUMBER
                            | InputType.TYPE_NUMBER_VARIATION_PASSWORD),
            @FlagEntry(
                    name = "phone",
                    mask = InputType.TYPE_MASK_CLASS | InputType.TYPE_MASK_VARIATION,
                    target = InputType.TYPE_CLASS_PHONE),
            @FlagEntry(
                    name = "datetime",
                    mask = InputType.TYPE_MASK_CLASS | InputType.TYPE_MASK_VARIATION,
                    target = InputType.TYPE_CLASS_DATETIME
                            | InputType.TYPE_DATETIME_VARIATION_NORMAL),
            @FlagEntry(
                    name = "date",
                    mask = InputType.TYPE_MASK_CLASS | InputType.TYPE_MASK_VARIATION,
                    target = InputType.TYPE_CLASS_DATETIME
                            | InputType.TYPE_DATETIME_VARIATION_DATE),
            @FlagEntry(
                    name = "time",
                    mask = InputType.TYPE_MASK_CLASS | InputType.TYPE_MASK_VARIATION,
                    target = InputType.TYPE_CLASS_DATETIME
                            | InputType.TYPE_DATETIME_VARIATION_TIME),
            @FlagEntry(
                    name = "textCapCharacters",
                    mask = InputType.TYPE_MASK_CLASS | InputType.TYPE_MASK_FLAGS,
                    target = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS),
            @FlagEntry(
                    name = "textCapWords",
                    mask = InputType.TYPE_MASK_CLASS | InputType.TYPE_MASK_FLAGS,
                    target = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS),
            @FlagEntry(
                    name = "textCapSentences",
                    mask = InputType.TYPE_MASK_CLASS | InputType.TYPE_MASK_FLAGS,
                    target = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES),
            @FlagEntry(
                    name = "textAutoCorrect",
                    mask = InputType.TYPE_MASK_CLASS | InputType.TYPE_MASK_FLAGS,
                    target = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT),
            @FlagEntry(
                    name = "textAutoComplete",
                    mask = InputType.TYPE_MASK_CLASS | InputType.TYPE_MASK_FLAGS,
                    target = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE),
            @FlagEntry(
                    name = "textMultiLine",
                    mask = InputType.TYPE_MASK_CLASS | InputType.TYPE_MASK_FLAGS,
                    target = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE),
            @FlagEntry(
                    name = "textImeMultiLine",
                    mask = InputType.TYPE_MASK_CLASS | InputType.TYPE_MASK_FLAGS,
                    target = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE),
            @FlagEntry(
                    name = "textNoSuggestions",
                    mask = InputType.TYPE_MASK_CLASS | InputType.TYPE_MASK_FLAGS,
                    target = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS),
            @FlagEntry(
                    name = "numberSigned",
                    mask = InputType.TYPE_MASK_CLASS | InputType.TYPE_MASK_FLAGS,
                    target = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED),
            @FlagEntry(
                    name = "numberDecimal",
                    mask = InputType.TYPE_MASK_CLASS | InputType.TYPE_MASK_FLAGS,
                    target = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL),
    })
    public int getInputType() {
        return mEditor == null ? EditorInfo.TYPE_NULL : mEditor.mInputType;
    }

    /**
     * Change the editor type integer associated with the text view, which
     * is reported to an Input Method Editor (IME) with {@link EditorInfo#imeOptions}
     * when it has focus.
     * @see #getImeOptions
     * @see android.view.inputmethod.EditorInfo
     * @attr ref android.R.styleable#TextView_imeOptions
     */
    public void setImeOptions(int imeOptions) {
        createEditorIfNeeded();
        mEditor.createInputContentTypeIfNeeded();
        mEditor.mInputContentType.imeOptions = imeOptions;
    }

    /**
     * Get the type of the Input Method Editor (IME).
     * @return the type of the IME
     * @see #setImeOptions(int)
     * @see EditorInfo
     */
    @InspectableProperty(flagMapping = {
            @FlagEntry(name = "normal", mask = 0xffffffff, target = EditorInfo.IME_NULL),
            @FlagEntry(
                    name = "actionUnspecified",
                    mask = EditorInfo.IME_MASK_ACTION,
                    target = EditorInfo.IME_ACTION_UNSPECIFIED),
            @FlagEntry(
                    name = "actionNone",
                    mask = EditorInfo.IME_MASK_ACTION,
                    target = EditorInfo.IME_ACTION_NONE),
            @FlagEntry(
                    name = "actionGo",
                    mask = EditorInfo.IME_MASK_ACTION,
                    target = EditorInfo.IME_ACTION_GO),
            @FlagEntry(
                    name = "actionSearch",
                    mask = EditorInfo.IME_MASK_ACTION,
                    target = EditorInfo.IME_ACTION_SEARCH),
            @FlagEntry(
                    name = "actionSend",
                    mask = EditorInfo.IME_MASK_ACTION,
                    target = EditorInfo.IME_ACTION_SEND),
            @FlagEntry(
                    name = "actionNext",
                    mask = EditorInfo.IME_MASK_ACTION,
                    target = EditorInfo.IME_ACTION_NEXT),
            @FlagEntry(
                    name = "actionDone",
                    mask = EditorInfo.IME_MASK_ACTION,
                    target = EditorInfo.IME_ACTION_DONE),
            @FlagEntry(
                    name = "actionPrevious",
                    mask = EditorInfo.IME_MASK_ACTION,
                    target = EditorInfo.IME_ACTION_PREVIOUS),
            @FlagEntry(name = "flagForceAscii", target = EditorInfo.IME_FLAG_FORCE_ASCII),
            @FlagEntry(name = "flagNavigateNext", target = EditorInfo.IME_FLAG_NAVIGATE_NEXT),
            @FlagEntry(
                    name = "flagNavigatePrevious",
                    target = EditorInfo.IME_FLAG_NAVIGATE_PREVIOUS),
            @FlagEntry(
                    name = "flagNoAccessoryAction",
                    target = EditorInfo.IME_FLAG_NO_ACCESSORY_ACTION),
            @FlagEntry(name = "flagNoEnterAction", target = EditorInfo.IME_FLAG_NO_ENTER_ACTION),
            @FlagEntry(name = "flagNoExtractUi", target = EditorInfo.IME_FLAG_NO_EXTRACT_UI),
            @FlagEntry(name = "flagNoFullscreen", target = EditorInfo.IME_FLAG_NO_FULLSCREEN),
            @FlagEntry(
                    name = "flagNoPersonalizedLearning",
                    target = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING),
    })
    public int getImeOptions() {
        return mEditor != null && mEditor.mInputContentType != null
                ? mEditor.mInputContentType.imeOptions : EditorInfo.IME_NULL;
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
        createEditorIfNeeded();
        mEditor.createInputContentTypeIfNeeded();
        mEditor.mInputContentType.imeActionLabel = label;
        mEditor.mInputContentType.imeActionId = actionId;
    }

    /**
     * Get the IME action label previous set with {@link #setImeActionLabel}.
     *
     * @see #setImeActionLabel
     * @see android.view.inputmethod.EditorInfo
     */
    @InspectableProperty
    public CharSequence getImeActionLabel() {
        return mEditor != null && mEditor.mInputContentType != null
                ? mEditor.mInputContentType.imeActionLabel : null;
    }

    /**
     * Get the IME action ID previous set with {@link #setImeActionLabel}.
     *
     * @see #setImeActionLabel
     * @see android.view.inputmethod.EditorInfo
     */
    @InspectableProperty
    public int getImeActionId() {
        return mEditor != null && mEditor.mInputContentType != null
                ? mEditor.mInputContentType.imeActionId : 0;
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
        createEditorIfNeeded();
        mEditor.createInputContentTypeIfNeeded();
        mEditor.mInputContentType.onEditorActionListener = l;
    }

    /**
     * Called when an attached input method calls
     * {@link InputConnection#performEditorAction(int)
     * InputConnection.performEditorAction()}
     * for this text view.  The default implementation will call your action
     * listener supplied to {@link #setOnEditorActionListener}, or perform
     * a standard operation for {@link EditorInfo#IME_ACTION_NEXT
     * EditorInfo.IME_ACTION_NEXT}, {@link EditorInfo#IME_ACTION_PREVIOUS
     * EditorInfo.IME_ACTION_PREVIOUS}, or {@link EditorInfo#IME_ACTION_DONE
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
        final Editor.InputContentType ict = mEditor == null ? null : mEditor.mInputContentType;
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
                View v = focusSearch(FOCUS_FORWARD);
                if (v != null) {
                    if (!v.requestFocus(FOCUS_FORWARD)) {
                        throw new IllegalStateException("focus search returned a view "
                                + "that wasn't able to take focus!");
                    }
                }
                return;

            } else if (actionCode == EditorInfo.IME_ACTION_PREVIOUS) {
                View v = focusSearch(FOCUS_BACKWARD);
                if (v != null) {
                    if (!v.requestFocus(FOCUS_BACKWARD)) {
                        throw new IllegalStateException("focus search returned a view "
                                + "that wasn't able to take focus!");
                    }
                }
                return;

            } else if (actionCode == EditorInfo.IME_ACTION_DONE) {
                InputMethodManager imm = getInputMethodManager();
                if (imm != null && imm.isActive(this)) {
                    imm.hideSoftInputFromWindow(getWindowToken(), 0);
                }
                return;
            }
        }

        ViewRootImpl viewRootImpl = getViewRootImpl();
        if (viewRootImpl != null) {
            long eventTime = SystemClock.uptimeMillis();
            viewRootImpl.dispatchKeyFromIme(
                    new KeyEvent(eventTime, eventTime,
                    KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                    KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE
                    | KeyEvent.FLAG_EDITOR_ACTION));
            viewRootImpl.dispatchKeyFromIme(
                    new KeyEvent(SystemClock.uptimeMillis(), eventTime,
                    KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                    KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE
                    | KeyEvent.FLAG_EDITOR_ACTION));
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
        createEditorIfNeeded();
        mEditor.createInputContentTypeIfNeeded();
        mEditor.mInputContentType.privateImeOptions = type;
    }

    /**
     * Get the private type of the content.
     *
     * @see #setPrivateImeOptions(String)
     * @see EditorInfo#privateImeOptions
     */
    @InspectableProperty
    public String getPrivateImeOptions() {
        return mEditor != null && mEditor.mInputContentType != null
                ? mEditor.mInputContentType.privateImeOptions : null;
    }

    /**
     * Set the extra input data of the text, which is the
     * {@link EditorInfo#extras TextBoxAttribute.extras}
     * Bundle that will be filled in when creating an input connection.  The
     * given integer is the resource identifier of an XML resource holding an
     * {@link android.R.styleable#InputExtras &lt;input-extras&gt;} XML tree.
     *
     * @see #getInputExtras(boolean)
     * @see EditorInfo#extras
     * @attr ref android.R.styleable#TextView_editorExtras
     */
    public void setInputExtras(@XmlRes int xmlResId) throws XmlPullParserException, IOException {
        createEditorIfNeeded();
        XmlResourceParser parser = getResources().getXml(xmlResId);
        mEditor.createInputContentTypeIfNeeded();
        mEditor.mInputContentType.extras = new Bundle();
        getResources().parseBundleExtras(parser, mEditor.mInputContentType.extras);
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
        if (mEditor == null && !create) return null;
        createEditorIfNeeded();
        if (mEditor.mInputContentType == null) {
            if (!create) return null;
            mEditor.createInputContentTypeIfNeeded();
        }
        if (mEditor.mInputContentType.extras == null) {
            if (!create) return null;
            mEditor.mInputContentType.extras = new Bundle();
        }
        return mEditor.mInputContentType.extras;
    }

    /**
     * Change "hint" locales associated with the text view, which will be reported to an IME with
     * {@link EditorInfo#hintLocales} when it has focus.
     *
     * Starting with Android O, this also causes internationalized listeners to be created (or
     * change locale) based on the first locale in the input locale list.
     *
     * <p><strong>Note:</strong> If you want new "hint" to take effect immediately you need to
     * call {@link InputMethodManager#restartInput(View)}.</p>
     * @param hintLocales List of the languages that the user is supposed to switch to no matter
     * what input method subtype is currently used. Set {@code null} to clear the current "hint".
     * @see #getImeHintLocales()
     * @see android.view.inputmethod.EditorInfo#hintLocales
     */
    public void setImeHintLocales(@Nullable LocaleList hintLocales) {
        createEditorIfNeeded();
        mEditor.createInputContentTypeIfNeeded();
        mEditor.mInputContentType.imeHintLocales = hintLocales;
        if (mUseInternationalizedInput) {
            changeListenerLocaleTo(hintLocales == null ? null : hintLocales.get(0));
        }
    }

    /**
     * @return The current languages list "hint". {@code null} when no "hint" is available.
     * @see #setImeHintLocales(LocaleList)
     * @see android.view.inputmethod.EditorInfo#hintLocales
     */
    @Nullable
    public LocaleList getImeHintLocales() {
        if (mEditor == null) {
            return null;
        }
        if (mEditor.mInputContentType == null) {
            return null;
        }
        return mEditor.mInputContentType.imeHintLocales;
    }

    /**
     * Returns the error message that was set to be displayed with
     * {@link #setError}, or <code>null</code> if no error was set
     * or if it the error was cleared by the widget after user input.
     */
    public CharSequence getError() {
        return mEditor == null ? null : mEditor.mError;
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
            Drawable dr = getContext().getDrawable(
                    com.android.internal.R.drawable.indicator_input_error);

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
        createEditorIfNeeded();
        mEditor.setError(error, icon);
        notifyViewAccessibilityStateChangedIfNeeded(
                AccessibilityEvent.CONTENT_CHANGE_TYPE_ERROR
                        | AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_INVALID);
    }

    @Override
    protected boolean setFrame(int l, int t, int r, int b) {
        boolean result = super.setFrame(l, t, r, b);

        if (mEditor != null) mEditor.setFrame();

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
     * Editable. Has no effect otherwise.
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
        if (mEditor != null) {
            final boolean undoFilter = mEditor.mUndoInputFilter != null;
            final boolean keyFilter = mEditor.mKeyListener instanceof InputFilter;
            int num = 0;
            if (undoFilter) num++;
            if (keyFilter) num++;
            if (num > 0) {
                InputFilter[] nf = new InputFilter[filters.length + num];

                System.arraycopy(filters, 0, nf, 0, filters.length);
                num = 0;
                if (undoFilter) {
                    nf[filters.length] = mEditor.mUndoInputFilter;
                    num++;
                }
                if (keyFilter) {
                    nf[filters.length + num] = (InputFilter) mEditor.mKeyListener;
                }

                e.setFilters(nf);
                return;
            }
        }
        e.setFilters(filters);
    }

    /**
     * Returns the current list of input filters.
     *
     * @attr ref android.R.styleable#TextView_maxLength
     */
    public InputFilter[] getFilters() {
        return mFilters;
    }

    /////////////////////////////////////////////////////////////////////////

    private int getBoxHeight(Layout l) {
        Insets opticalInsets = isLayoutModeOptical(mParent) ? getOpticalInsets() : Insets.NONE;
        int padding = (l == mHintLayout)
                ? getCompoundPaddingTop() + getCompoundPaddingBottom()
                : getExtendedPaddingTop() + getExtendedPaddingBottom();
        return getMeasuredHeight() - padding + opticalInsets.top + opticalInsets.bottom;
    }

    @UnsupportedAppUsage
    int getVerticalOffset(boolean forceNormal) {
        int voffset = 0;
        final int gravity = mGravity & Gravity.VERTICAL_GRAVITY_MASK;

        Layout l = mLayout;
        if (!forceNormal && mText.length() == 0 && mHintLayout != null) {
            l = mHintLayout;
        }

        if (gravity != Gravity.TOP) {
            int boxht = getBoxHeight(l);
            int textht = l.getHeight();

            if (textht < boxht) {
                if (gravity == Gravity.BOTTOM) {
                    voffset = boxht - textht;
                } else { // (gravity == Gravity.CENTER_VERTICAL)
                    voffset = (boxht - textht) >> 1;
                }
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
            int boxht = getBoxHeight(l);
            int textht = l.getHeight();

            if (textht < boxht) {
                if (gravity == Gravity.TOP) {
                    voffset = boxht - textht;
                } else { // (gravity == Gravity.CENTER_VERTICAL)
                    voffset = (boxht - textht) >> 1;
                }
            }
        }
        return voffset;
    }

    void invalidateCursorPath() {
        if (mHighlightPathBogus) {
            invalidateCursor();
        } else {
            final int horizontalPadding = getCompoundPaddingLeft();
            final int verticalPadding = getExtendedPaddingTop() + getVerticalOffset(true);

            if (mEditor.mDrawableForCursor == null) {
                synchronized (TEMP_RECTF) {
                    /*
                     * The reason for this concern about the thickness of the
                     * cursor and doing the floor/ceil on the coordinates is that
                     * some EditTexts (notably textfields in the Browser) have
                     * anti-aliased text where not all the characters are
                     * necessarily at integer-multiple locations.  This should
                     * make sure the entire cursor gets invalidated instead of
                     * sometimes missing half a pixel.
                     */
                    float thick = (float) Math.ceil(mTextPaint.getStrokeWidth());
                    if (thick < 1.0f) {
                        thick = 1.0f;
                    }

                    thick /= 2.0f;

                    // mHighlightPath is guaranteed to be non null at that point.
                    mHighlightPath.computeBounds(TEMP_RECTF, false);

                    invalidate((int) Math.floor(horizontalPadding + TEMP_RECTF.left - thick),
                            (int) Math.floor(verticalPadding + TEMP_RECTF.top - thick),
                            (int) Math.ceil(horizontalPadding + TEMP_RECTF.right + thick),
                            (int) Math.ceil(verticalPadding + TEMP_RECTF.bottom + thick));
                }
            } else {
                final Rect bounds = mEditor.mDrawableForCursor.getBounds();
                invalidate(bounds.left + horizontalPadding, bounds.top + verticalPadding,
                        bounds.right + horizontalPadding, bounds.bottom + verticalPadding);
            }
        }
    }

    void invalidateCursor() {
        int where = getSelectionEnd();

        invalidateCursor(where, where, where);
    }

    private void invalidateCursor(int a, int b, int c) {
        if (a >= 0 || b >= 0 || c >= 0) {
            int start = Math.min(Math.min(a, b), c);
            int end = Math.max(Math.max(a, b), c);
            invalidateRegion(start, end, true /* Also invalidates blinking cursor */);
        }
    }

    /**
     * Invalidates the region of text enclosed between the start and end text offsets.
     */
    void invalidateRegion(int start, int end, boolean invalidateCursor) {
        if (mLayout == null) {
            invalidate();
        } else {
            start = originalToTransformed(start, OffsetMapping.MAP_STRATEGY_CURSOR);
            end = originalToTransformed(end, OffsetMapping.MAP_STRATEGY_CURSOR);
            int lineStart = mLayout.getLineForOffset(start);
            int top = mLayout.getLineTop(lineStart);

            // This is ridiculous, but the descent from the line above
            // can hang down into the line we really want to redraw,
            // so we have to invalidate part of the line above to make
            // sure everything that needs to be redrawn really is.
            // (But not the whole line above, because that would cause
            // the same problem with the descenders on the line above it!)
            if (lineStart > 0) {
                top -= mLayout.getLineDescent(lineStart - 1);
            }

            int lineEnd;

            if (start == end) {
                lineEnd = lineStart;
            } else {
                lineEnd = mLayout.getLineForOffset(end);
            }

            int bottom = mLayout.getLineBottom(lineEnd);

            // mEditor can be null in case selection is set programmatically.
            if (invalidateCursor && mEditor != null && mEditor.mDrawableForCursor != null) {
                final Rect bounds = mEditor.mDrawableForCursor.getBounds();
                top = Math.min(top, bounds.top);
                bottom = Math.max(bottom, bounds.bottom);
            }

            final int compoundPaddingLeft = getCompoundPaddingLeft();
            final int verticalPadding = getExtendedPaddingTop() + getVerticalOffset(true);

            int left, right;
            if (lineStart == lineEnd && !invalidateCursor) {
                left = (int) mLayout.getPrimaryHorizontal(start);
                right = (int) (mLayout.getPrimaryHorizontal(end) + 1.0);
                left += compoundPaddingLeft;
                right += compoundPaddingLeft;
            } else {
                // Rectangle bounding box when the region spans several lines
                left = compoundPaddingLeft;
                right = getWidth() - getCompoundPaddingRight();
            }

            invalidate(mScrollX + left, verticalPadding + top,
                    mScrollX + right, verticalPadding + bottom);
        }
    }

    private void registerForPreDraw() {
        if (!mPreDrawRegistered) {
            getViewTreeObserver().addOnPreDrawListener(this);
            mPreDrawRegistered = true;
        }
    }

    private void unregisterForPreDraw() {
        getViewTreeObserver().removeOnPreDrawListener(this);
        mPreDrawRegistered = false;
        mPreDrawListenerDetached = false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean onPreDraw() {
        if (mLayout == null) {
            assumeLayout();
        }

        if (mMovement != null) {
            /* This code also provides auto-scrolling when a cursor is moved using a
             * CursorController (insertion point or selection limits).
             * For selection, ensure start or end is visible depending on controller's state.
             */
            int curs = getSelectionEnd();
            // Do not create the controller if it is not already created.
            if (mEditor != null && mEditor.mSelectionModifierCursorController != null
                    && mEditor.mSelectionModifierCursorController.isSelectionStartDragged()) {
                curs = getSelectionStart();
            }

            /*
             * TODO: This should really only keep the end in view if
             * it already was before the text changed.  I'm not sure
             * of a good way to tell from here if it was.
             */
            if (curs < 0 && (mGravity & Gravity.VERTICAL_GRAVITY_MASK) == Gravity.BOTTOM) {
                curs = mText.length();
            }

            if (curs >= 0) {
                bringPointIntoView(curs);
            }
        } else {
            bringTextIntoView();
        }

        // This has to be checked here since:
        // - onFocusChanged cannot start it when focus is given to a view with selected text (after
        //   a screen rotation) since layout is not yet initialized at that point.
        if (mEditor != null && mEditor.mCreatedWithASelection) {
            mEditor.refreshTextActionMode();
            mEditor.mCreatedWithASelection = false;
        }

        unregisterForPreDraw();

        return true;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (mEditor != null) mEditor.onAttachedToWindow();

        if (mPreDrawListenerDetached) {
            getViewTreeObserver().addOnPreDrawListener(this);
            mPreDrawListenerDetached = false;
        }
    }

    /** @hide */
    @Override
    protected void onDetachedFromWindowInternal() {
        if (mPreDrawRegistered) {
            getViewTreeObserver().removeOnPreDrawListener(this);
            mPreDrawListenerDetached = true;
        }

        resetResolvedDrawables();

        if (mEditor != null) mEditor.onDetachedFromWindow();

        super.onDetachedFromWindowInternal();
    }

    @Override
    public void onScreenStateChanged(int screenState) {
        super.onScreenStateChanged(screenState);
        if (mEditor != null) mEditor.onScreenStateChanged(screenState);
    }

    @Override
    protected boolean isPaddingOffsetRequired() {
        return mShadowRadius != 0 || mDrawables != null;
    }

    @Override
    protected int getLeftPaddingOffset() {
        return getCompoundPaddingLeft() - mPaddingLeft
                + (int) Math.min(0, mShadowDx - mShadowRadius);
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
        return -(getCompoundPaddingRight() - mPaddingRight)
                + (int) Math.max(0, mShadowDx + mShadowRadius);
    }

    @Override
    protected boolean verifyDrawable(@NonNull Drawable who) {
        final boolean verified = super.verifyDrawable(who);
        if (!verified && mDrawables != null) {
            for (Drawable dr : mDrawables.mShowing) {
                if (who == dr) {
                    return true;
                }
            }
        }
        return verified;
    }

    @Override
    public void jumpDrawablesToCurrentState() {
        super.jumpDrawablesToCurrentState();
        if (mDrawables != null) {
            for (Drawable dr : mDrawables.mShowing) {
                if (dr != null) {
                    dr.jumpToCurrentState();
                }
            }
        }
    }

    @Override
    public void invalidateDrawable(@NonNull Drawable drawable) {
        boolean handled = false;

        if (verifyDrawable(drawable)) {
            final Rect dirty = drawable.getBounds();
            int scrollX = mScrollX;
            int scrollY = mScrollY;

            // IMPORTANT: The coordinates below are based on the coordinates computed
            // for each compound drawable in onDraw(). Make sure to update each section
            // accordingly.
            final TextView.Drawables drawables = mDrawables;
            if (drawables != null) {
                if (drawable == drawables.mShowing[Drawables.LEFT]) {
                    final int compoundPaddingTop = getCompoundPaddingTop();
                    final int compoundPaddingBottom = getCompoundPaddingBottom();
                    final int vspace = mBottom - mTop - compoundPaddingBottom - compoundPaddingTop;

                    scrollX += mPaddingLeft;
                    scrollY += compoundPaddingTop + (vspace - drawables.mDrawableHeightLeft) / 2;
                    handled = true;
                } else if (drawable == drawables.mShowing[Drawables.RIGHT]) {
                    final int compoundPaddingTop = getCompoundPaddingTop();
                    final int compoundPaddingBottom = getCompoundPaddingBottom();
                    final int vspace = mBottom - mTop - compoundPaddingBottom - compoundPaddingTop;

                    scrollX += (mRight - mLeft - mPaddingRight - drawables.mDrawableSizeRight);
                    scrollY += compoundPaddingTop + (vspace - drawables.mDrawableHeightRight) / 2;
                    handled = true;
                } else if (drawable == drawables.mShowing[Drawables.TOP]) {
                    final int compoundPaddingLeft = getCompoundPaddingLeft();
                    final int compoundPaddingRight = getCompoundPaddingRight();
                    final int hspace = mRight - mLeft - compoundPaddingRight - compoundPaddingLeft;

                    scrollX += compoundPaddingLeft + (hspace - drawables.mDrawableWidthTop) / 2;
                    scrollY += mPaddingTop;
                    handled = true;
                } else if (drawable == drawables.mShowing[Drawables.BOTTOM]) {
                    final int compoundPaddingLeft = getCompoundPaddingLeft();
                    final int compoundPaddingRight = getCompoundPaddingRight();
                    final int hspace = mRight - mLeft - compoundPaddingRight - compoundPaddingLeft;

                    scrollX += compoundPaddingLeft + (hspace - drawables.mDrawableWidthBottom) / 2;
                    scrollY += (mBottom - mTop - mPaddingBottom - drawables.mDrawableSizeBottom);
                    handled = true;
                }
            }

            if (handled) {
                invalidate(dirty.left + scrollX, dirty.top + scrollY,
                        dirty.right + scrollX, dirty.bottom + scrollY);
            }
        }

        if (!handled) {
            super.invalidateDrawable(drawable);
        }
    }

    @Override
    public boolean hasOverlappingRendering() {
        // horizontal fading edge causes SaveLayerAlpha, which doesn't support alpha modulation
        return ((getBackground() != null && getBackground().getCurrent() != null)
                || mSpannable != null || hasSelection() || isHorizontalFadingEdgeEnabled()
                || mShadowColor != 0);
    }

    /**
     *
     * Returns the state of the {@code textIsSelectable} flag (See
     * {@link #setTextIsSelectable setTextIsSelectable()}). Although you have to set this flag
     * to allow users to select and copy text in a non-editable TextView, the content of an
     * {@link EditText} can always be selected, independently of the value of this flag.
     * <p>
     *
     * @return True if the text displayed in this TextView can be selected by the user.
     *
     * @attr ref android.R.styleable#TextView_textIsSelectable
     */
    @InspectableProperty(name = "textIsSelectable")
    public boolean isTextSelectable() {
        return mEditor == null ? false : mEditor.mTextIsSelectable;
    }

    /**
     * Sets whether the content of this view is selectable by the user. The default is
     * {@code false}, meaning that the content is not selectable.
     * <p>
     * When you use a TextView to display a useful piece of information to the user (such as a
     * contact's address), make it selectable, so that the user can select and copy its
     * content. You can also use set the XML attribute
     * {@link android.R.styleable#TextView_textIsSelectable} to "true".
     * <p>
     * When you call this method to set the value of {@code textIsSelectable}, it sets
     * the flags {@code focusable}, {@code focusableInTouchMode}, {@code clickable},
     * and {@code longClickable} to the same value. These flags correspond to the attributes
     * {@link android.R.styleable#View_focusable android:focusable},
     * {@link android.R.styleable#View_focusableInTouchMode android:focusableInTouchMode},
     * {@link android.R.styleable#View_clickable android:clickable}, and
     * {@link android.R.styleable#View_longClickable android:longClickable}. To restore any of these
     * flags to a state you had set previously, call one or more of the following methods:
     * {@link #setFocusable(boolean) setFocusable()},
     * {@link #setFocusableInTouchMode(boolean) setFocusableInTouchMode()},
     * {@link #setClickable(boolean) setClickable()} or
     * {@link #setLongClickable(boolean) setLongClickable()}.
     *
     * @param selectable Whether the content of this TextView should be selectable.
     */
    public void setTextIsSelectable(boolean selectable) {
        if (!selectable && mEditor == null) return; // false is default value with no edit data

        createEditorIfNeeded();
        if (mEditor.mTextIsSelectable == selectable) return;

        mEditor.mTextIsSelectable = selectable;
        setFocusableInTouchMode(selectable);
        setFocusable(FOCUSABLE_AUTO);
        setClickable(selectable);
        setLongClickable(selectable);

        // mInputType should already be EditorInfo.TYPE_NULL and mInput should be null

        setMovementMethod(selectable ? ArrowKeyMovementMethod.getInstance() : null);
        setText(mText, selectable ? BufferType.SPANNABLE : BufferType.NORMAL);

        // Called by setText above, but safer in case of future code changes
        mEditor.prepareCursorControllers();
    }

    @Override
    protected int[] onCreateDrawableState(int extraSpace) {
        final int[] drawableState;

        if (mSingleLine) {
            drawableState = super.onCreateDrawableState(extraSpace);
        } else {
            drawableState = super.onCreateDrawableState(extraSpace + 1);
            mergeDrawableStates(drawableState, MULTILINE_STATE_SET);
        }

        if (isTextSelectable()) {
            // Disable pressed state, which was introduced when TextView was made clickable.
            // Prevents text color change.
            // setClickable(false) would have a similar effect, but it also disables focus changes
            // and long press actions, which are both needed by text selection.
            final int length = drawableState.length;
            for (int i = 0; i < length; i++) {
                if (drawableState[i] == R.attr.state_pressed) {
                    final int[] nonPressedState = new int[length - 1];
                    System.arraycopy(drawableState, 0, nonPressedState, 0, i);
                    System.arraycopy(drawableState, i + 1, nonPressedState, i, length - i - 1);
                    return nonPressedState;
                }
            }
        }

        return drawableState;
    }

    private void maybeUpdateHighlightPaths() {
        if (!mHighlightPathsBogus) {
            return;
        }

        if (mHighlightPaths != null) {
            mPathRecyclePool.addAll(mHighlightPaths);
            mHighlightPaths.clear();
            mHighlightPaints.clear();
        } else {
            mHighlightPaths = new ArrayList<>();
            mHighlightPaints = new ArrayList<>();
        }

        if (mHighlights != null) {
            for (int i = 0; i < mHighlights.getSize(); ++i) {
                final int[] ranges = mHighlights.getRanges(i);
                final Paint paint = mHighlights.getPaint(i);
                final Path path;
                if (mPathRecyclePool.isEmpty()) {
                    path = new Path();
                } else {
                    path = mPathRecyclePool.get(mPathRecyclePool.size() - 1);
                    mPathRecyclePool.remove(mPathRecyclePool.size() - 1);
                    path.reset();
                }

                boolean atLeastOnePathAdded = false;
                for (int j = 0; j < ranges.length / 2; ++j) {
                    final int start = ranges[2 * j];
                    final int end = ranges[2 * j + 1];
                    if (start < end) {
                        mLayout.getSelection(start, end, (left, top, right, bottom, layout) ->
                                path.addRect(left, top, right, bottom, Path.Direction.CW)
                        );
                        atLeastOnePathAdded = true;
                    }
                }
                if (atLeastOnePathAdded) {
                    mHighlightPaths.add(path);
                    mHighlightPaints.add(paint);
                }
            }
        }

        addSearchHighlightPaths();

        if (hasGesturePreviewHighlight()) {
            final Path path;
            if (mPathRecyclePool.isEmpty()) {
                path = new Path();
            } else {
                path = mPathRecyclePool.get(mPathRecyclePool.size() - 1);
                mPathRecyclePool.remove(mPathRecyclePool.size() - 1);
                path.reset();
            }
            mLayout.getSelectionPath(
                    mGesturePreviewHighlightStart, mGesturePreviewHighlightEnd, path);
            mHighlightPaths.add(path);
            mHighlightPaints.add(mGesturePreviewHighlightPaint);
        }

        mHighlightPathsBogus = false;
    }

    private void addSearchHighlightPaths() {
        if (mSearchResultHighlights != null) {
            final Path searchResultPath;
            if (mPathRecyclePool.isEmpty()) {
                searchResultPath = new Path();
            } else {
                searchResultPath = mPathRecyclePool.get(mPathRecyclePool.size() - 1);
                mPathRecyclePool.remove(mPathRecyclePool.size() - 1);
                searchResultPath.reset();
            }
            final Path focusedSearchResultPath;
            if (mFocusedSearchResultIndex == FOCUSED_SEARCH_RESULT_INDEX_NONE) {
                focusedSearchResultPath = null;
            } else if (mPathRecyclePool.isEmpty()) {
                focusedSearchResultPath = new Path();
            } else {
                focusedSearchResultPath = mPathRecyclePool.get(mPathRecyclePool.size() - 1);
                mPathRecyclePool.remove(mPathRecyclePool.size() - 1);
                focusedSearchResultPath.reset();
            }

            boolean atLeastOnePathAdded = false;
            for (int j = 0; j < mSearchResultHighlights.length / 2; ++j) {
                final int start = mSearchResultHighlights[2 * j];
                final int end = mSearchResultHighlights[2 * j + 1];
                if (start < end) {
                    if (j == mFocusedSearchResultIndex) {
                        mLayout.getSelection(start, end, (left, top, right, bottom, layout) ->
                                focusedSearchResultPath.addRect(left, top, right, bottom,
                                        Path.Direction.CW)
                        );
                    } else {
                        mLayout.getSelection(start, end, (left, top, right, bottom, layout) ->
                                searchResultPath.addRect(left, top, right, bottom,
                                        Path.Direction.CW)
                        );
                        atLeastOnePathAdded = true;
                    }
                }
            }
            if (atLeastOnePathAdded) {
                if (mSearchResultHighlightPaint == null) {
                    mSearchResultHighlightPaint = new Paint();
                }
                mSearchResultHighlightPaint.setColor(mSearchResultHighlightColor);
                mSearchResultHighlightPaint.setStyle(Paint.Style.FILL);
                mHighlightPaths.add(searchResultPath);
                mHighlightPaints.add(mSearchResultHighlightPaint);
            }
            if (focusedSearchResultPath != null) {
                if (mFocusedSearchResultHighlightPaint == null) {
                    mFocusedSearchResultHighlightPaint = new Paint();
                }
                mFocusedSearchResultHighlightPaint.setColor(mFocusedSearchResultHighlightColor);
                mFocusedSearchResultHighlightPaint.setStyle(Paint.Style.FILL);
                mHighlightPaths.add(focusedSearchResultPath);
                mHighlightPaints.add(mFocusedSearchResultHighlightPaint);
            }
        }
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private Path getUpdatedHighlightPath() {
        Path highlight = null;
        Paint highlightPaint = mHighlightPaint;

        final int selStart = getSelectionStartTransformed();
        final int selEnd = getSelectionEndTransformed();
        if (mMovement != null && (isFocused() || isPressed()) && selStart >= 0) {
            if (selStart == selEnd) {
                if (mEditor != null && mEditor.shouldRenderCursor()) {
                    if (mHighlightPathBogus) {
                        if (mHighlightPath == null) mHighlightPath = new Path();
                        mHighlightPath.reset();
                        mLayout.getCursorPath(selStart, mHighlightPath, mText);
                        mEditor.updateCursorPosition();
                        mHighlightPathBogus = false;
                    }

                    // XXX should pass to skin instead of drawing directly
                    highlightPaint.setColor(mCurTextColor);
                    highlightPaint.setStyle(Paint.Style.STROKE);
                    highlight = mHighlightPath;
                }
            } else {
                if (mHighlightPathBogus) {
                    if (mHighlightPath == null) mHighlightPath = new Path();
                    mHighlightPath.reset();
                    mLayout.getSelectionPath(selStart, selEnd, mHighlightPath);
                    mHighlightPathBogus = false;
                }

                // XXX should pass to skin instead of drawing directly
                highlightPaint.setColor(mHighlightColor);
                highlightPaint.setStyle(Paint.Style.FILL);

                highlight = mHighlightPath;
            }
        }
        return highlight;
    }

    /**
     * @hide
     */
    public int getHorizontalOffsetForDrawables() {
        return 0;
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
        final boolean isLayoutRtl = isLayoutRtl();
        final int offset = getHorizontalOffsetForDrawables();
        final int leftOffset = isLayoutRtl ? 0 : offset;
        final int rightOffset = isLayoutRtl ? offset : 0;

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
            if (dr.mShowing[Drawables.LEFT] != null) {
                canvas.save();
                canvas.translate(scrollX + mPaddingLeft + leftOffset,
                        scrollY + compoundPaddingTop + (vspace - dr.mDrawableHeightLeft) / 2);
                dr.mShowing[Drawables.LEFT].draw(canvas);
                canvas.restore();
            }

            // IMPORTANT: The coordinates computed are also used in invalidateDrawable()
            // Make sure to update invalidateDrawable() when changing this code.
            if (dr.mShowing[Drawables.RIGHT] != null) {
                canvas.save();
                canvas.translate(scrollX + right - left - mPaddingRight
                        - dr.mDrawableSizeRight - rightOffset,
                         scrollY + compoundPaddingTop + (vspace - dr.mDrawableHeightRight) / 2);
                dr.mShowing[Drawables.RIGHT].draw(canvas);
                canvas.restore();
            }

            // IMPORTANT: The coordinates computed are also used in invalidateDrawable()
            // Make sure to update invalidateDrawable() when changing this code.
            if (dr.mShowing[Drawables.TOP] != null) {
                canvas.save();
                canvas.translate(scrollX + compoundPaddingLeft
                        + (hspace - dr.mDrawableWidthTop) / 2, scrollY + mPaddingTop);
                dr.mShowing[Drawables.TOP].draw(canvas);
                canvas.restore();
            }

            // IMPORTANT: The coordinates computed are also used in invalidateDrawable()
            // Make sure to update invalidateDrawable() when changing this code.
            if (dr.mShowing[Drawables.BOTTOM] != null) {
                canvas.save();
                canvas.translate(scrollX + compoundPaddingLeft
                        + (hspace - dr.mDrawableWidthBottom) / 2,
                         scrollY + bottom - top - mPaddingBottom - dr.mDrawableSizeBottom);
                dr.mShowing[Drawables.BOTTOM].draw(canvas);
                canvas.restore();
            }
        }

        int color = mCurTextColor;

        if (mLayout == null) {
            assumeLayout();
        }

        Layout layout = mLayout;

        if (mHint != null && !mHideHint && mText.length() == 0) {
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

        final int vspace = mBottom - mTop - compoundPaddingBottom - compoundPaddingTop;
        final int maxScrollY = mLayout.getHeight() - vspace;

        float clipLeft = compoundPaddingLeft + scrollX;
        float clipTop = (scrollY == 0) ? 0 : extendedPaddingTop + scrollY;
        float clipRight = right - left - getCompoundPaddingRight() + scrollX;
        float clipBottom = bottom - top + scrollY
                - ((scrollY == maxScrollY) ? 0 : extendedPaddingBottom);

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
        /* shortcircuit calling getVerticaOffset() */
        if ((mGravity & Gravity.VERTICAL_GRAVITY_MASK) != Gravity.TOP) {
            voffsetText = getVerticalOffset(false);
            voffsetCursor = getVerticalOffset(true);
        }
        canvas.translate(compoundPaddingLeft, extendedPaddingTop + voffsetText);

        final int layoutDirection = getLayoutDirection();
        final int absoluteGravity = Gravity.getAbsoluteGravity(mGravity, layoutDirection);
        if (isMarqueeFadeEnabled()) {
            if (!mSingleLine && getLineCount() == 1 && canMarquee()
                    && (absoluteGravity & Gravity.HORIZONTAL_GRAVITY_MASK) != Gravity.LEFT) {
                final int width = mRight - mLeft;
                final int padding = getCompoundPaddingLeft() + getCompoundPaddingRight();
                final float dx = mLayout.getLineRight(0) - (width - padding);
                canvas.translate(layout.getParagraphDirection(0) * dx, 0.0f);
            }

            if (mMarquee != null && mMarquee.isRunning()) {
                final float dx = -mMarquee.getScroll();
                canvas.translate(layout.getParagraphDirection(0) * dx, 0.0f);
            }
        }

        final int cursorOffsetVertical = voffsetCursor - voffsetText;

        maybeUpdateHighlightPaths();
        // If there is a gesture preview highlight, then the selection or cursor is not drawn.
        Path highlight = hasGesturePreviewHighlight() ? null : getUpdatedHighlightPath();
        if (mEditor != null) {
            mEditor.onDraw(canvas, layout, mHighlightPaths, mHighlightPaints, highlight,
                    mHighlightPaint, cursorOffsetVertical);
        } else {
            layout.draw(canvas, mHighlightPaths, mHighlightPaints, highlight, mHighlightPaint,
                    cursorOffsetVertical);
        }

        if (mMarquee != null && mMarquee.shouldDrawGhost()) {
            final float dx = mMarquee.getGhostOffset();
            canvas.translate(layout.getParagraphDirection(0) * dx, 0.0f);
            layout.draw(canvas, mHighlightPaths, mHighlightPaints, highlight, mHighlightPaint,
                    cursorOffsetVertical);
        }

        canvas.restore();
    }

    @Override
    public void getFocusedRect(Rect r) {
        if (mLayout == null) {
            super.getFocusedRect(r);
            return;
        }

        int selEnd = getSelectionEndTransformed();
        if (selEnd < 0) {
            super.getFocusedRect(r);
            return;
        }

        int selStart = getSelectionStartTransformed();
        if (selStart < 0 || selStart >= selEnd) {
            int line = mLayout.getLineForOffset(selEnd);
            r.top = mLayout.getLineTop(line);
            r.bottom = mLayout.getLineBottom(line);
            r.left = (int) mLayout.getPrimaryHorizontal(selEnd) - 2;
            r.right = r.left + 4;
        } else {
            int lineStart = mLayout.getLineForOffset(selStart);
            int lineEnd = mLayout.getLineForOffset(selEnd);
            r.top = mLayout.getLineTop(lineStart);
            r.bottom = mLayout.getLineBottom(lineEnd);
            if (lineStart == lineEnd) {
                r.left = (int) mLayout.getPrimaryHorizontal(selStart);
                r.right = (int) mLayout.getPrimaryHorizontal(selEnd);
            } else {
                // Selection extends across multiple lines -- make the focused
                // rect cover the entire width.
                if (mHighlightPathBogus) {
                    if (mHighlightPath == null) mHighlightPath = new Path();
                    mHighlightPath.reset();
                    mLayout.getSelectionPath(selStart, selEnd, mHighlightPath);
                    mHighlightPathBogus = false;
                }
                synchronized (TEMP_RECTF) {
                    mHighlightPath.computeBounds(TEMP_RECTF, true);
                    r.left = (int) TEMP_RECTF.left - 1;
                    r.right = (int) TEMP_RECTF.right + 1;
                }
            }
        }

        // Adjust for padding and gravity.
        int paddingLeft = getCompoundPaddingLeft();
        int paddingTop = getExtendedPaddingTop();
        if ((mGravity & Gravity.VERTICAL_GRAVITY_MASK) != Gravity.TOP) {
            paddingTop += getVerticalOffset(false);
        }
        r.offset(paddingLeft, paddingTop);
        int paddingBottom = getExtendedPaddingBottom();
        r.bottom += paddingBottom;
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
        } else {
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

        return getBaselineOffset() + mLayout.getLineBaseline(0);
    }

    int getBaselineOffset() {
        int voffset = 0;
        if ((mGravity & Gravity.VERTICAL_GRAVITY_MASK) != Gravity.TOP) {
            voffset = getVerticalOffset(true);
        }

        if (isLayoutModeOptical(mParent)) {
            voffset -= getOpticalInsets().top;
        }

        return getExtendedPaddingTop() + voffset;
    }

    /**
     * @hide
     */
    @Override
    protected int getFadeTop(boolean offsetRequired) {
        if (mLayout == null) return 0;

        int voffset = 0;
        if ((mGravity & Gravity.VERTICAL_GRAVITY_MASK) != Gravity.TOP) {
            voffset = getVerticalOffset(true);
        }

        if (offsetRequired) voffset += getTopPaddingOffset();

        return getExtendedPaddingTop() + voffset;
    }

    /**
     * @hide
     */
    @Override
    protected int getFadeHeight(boolean offsetRequired) {
        return mLayout != null ? mLayout.getHeight() : 0;
    }

    @Override
    public PointerIcon onResolvePointerIcon(MotionEvent event, int pointerIndex) {
        if (mSpannable != null && mLinksClickable) {
            final float x = event.getX(pointerIndex);
            final float y = event.getY(pointerIndex);
            final int offset = getOffsetForPosition(x, y);
            final ClickableSpan[] clickables = mSpannable.getSpans(offset, offset,
                    ClickableSpan.class);
            if (clickables.length > 0) {
                return PointerIcon.getSystemIcon(mContext, PointerIcon.TYPE_HAND);
            }
        }
        if (isTextSelectable() || isTextEditable()) {
            return PointerIcon.getSystemIcon(mContext, PointerIcon.TYPE_TEXT);
        }
        return super.onResolvePointerIcon(event, pointerIndex);
    }

    @Override
    public boolean onKeyPreIme(int keyCode, KeyEvent event) {
        // Note: If the IME is in fullscreen mode and IMS#mExtractEditText is in text action mode,
        // InputMethodService#onKeyDown and InputMethodService#onKeyUp are responsible to call
        // InputMethodService#mExtractEditText.maybeHandleBackInTextActionMode(event).
        if (keyCode == KeyEvent.KEYCODE_BACK && handleBackInTextActionModeIfNeeded(event)) {
            return true;
        }
        return super.onKeyPreIme(keyCode, event);
    }

    /**
     * @hide
     */
    public boolean handleBackInTextActionModeIfNeeded(KeyEvent event) {
        // Do nothing unless mEditor is in text action mode.
        if (mEditor == null || mEditor.getTextActionMode() == null) {
            return false;
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            KeyEvent.DispatcherState state = getKeyDispatcherState();
            if (state != null) {
                state.startTracking(event, this);
            }
            return true;
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            KeyEvent.DispatcherState state = getKeyDispatcherState();
            if (state != null) {
                state.handleUpEvent(event);
            }
            if (event.isTracking() && !event.isCanceled()) {
                stopTextActionMode();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        final int which = doKeyDown(keyCode, event, null);
        if (which == KEY_EVENT_NOT_HANDLED) {
            return super.onKeyDown(keyCode, event);
        }

        return true;
    }

    @Override
    public boolean onKeyMultiple(int keyCode, int repeatCount, KeyEvent event) {
        KeyEvent down = KeyEvent.changeAction(event, KeyEvent.ACTION_DOWN);
        final int which = doKeyDown(keyCode, down, event);
        if (which == KEY_EVENT_NOT_HANDLED) {
            // Go through default dispatching.
            return super.onKeyMultiple(keyCode, repeatCount, event);
        }
        if (which == KEY_EVENT_HANDLED) {
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
        if (which == KEY_DOWN_HANDLED_BY_KEY_LISTENER) {
            // mEditor and mEditor.mInput are not null from doKeyDown
            mEditor.mKeyListener.onKeyUp(this, (Editable) mText, keyCode, up);
            while (--repeatCount > 0) {
                mEditor.mKeyListener.onKeyDown(this, (Editable) mText, keyCode, down);
                mEditor.mKeyListener.onKeyUp(this, (Editable) mText, keyCode, up);
            }
            hideErrorIfUnchanged();

        } else if (which == KEY_DOWN_HANDLED_BY_MOVEMENT_METHOD) {
            // mMovement is not null from doKeyDown
            mMovement.onKeyUp(this, mSpannable, keyCode, up);
            while (--repeatCount > 0) {
                mMovement.onKeyDown(this, mSpannable, keyCode, down);
                mMovement.onKeyUp(this, mSpannable, keyCode, up);
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
        if (getKeyListener() == null) {
            return false;
        }

        if (mSingleLine) {
            return true;
        }

        if (mEditor != null
                && (mEditor.mInputType & EditorInfo.TYPE_MASK_CLASS)
                        == EditorInfo.TYPE_CLASS_TEXT) {
            int variation = mEditor.mInputType & EditorInfo.TYPE_MASK_VARIATION;
            if (variation == EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                    || variation == EditorInfo.TYPE_TEXT_VARIATION_EMAIL_SUBJECT) {
                return true;
            }
        }

        return false;
    }

    private boolean isDirectionalNavigationKey(int keyCode) {
        switch(keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true;
        }
        return false;
    }

    private int doKeyDown(int keyCode, KeyEvent event, KeyEvent otherEvent) {
        if (!isEnabled()) {
            return KEY_EVENT_NOT_HANDLED;
        }

        // If this is the initial keydown, we don't want to prevent a movement away from this view.
        // While this shouldn't be necessary because any time we're preventing default movement we
        // should be restricting the focus to remain within this view, thus we'll also receive
        // the key up event, occasionally key up events will get dropped and we don't want to
        // prevent the user from traversing out of this on the next key down.
        if (event.getRepeatCount() == 0 && !KeyEvent.isModifierKey(keyCode)) {
            mPreventDefaultMovement = false;
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (event.hasNoModifiers()) {
                    // When mInputContentType is set, we know that we are
                    // running in a "modern" cupcake environment, so don't need
                    // to worry about the application trying to capture
                    // enter key events.
                    if (mEditor != null && mEditor.mInputContentType != null) {
                        // If there is an action listener, given them a
                        // chance to consume the event.
                        if (mEditor.mInputContentType.onEditorActionListener != null
                                && mEditor.mInputContentType.onEditorActionListener.onEditorAction(
                                        this,
                                        getActionIdForEnterEvent(),
                                        event)) {
                            mEditor.mInputContentType.enterDown = true;
                            // We are consuming the enter key for them.
                            return KEY_EVENT_HANDLED;
                        }
                    }

                    // If our editor should move focus when enter is pressed, or
                    // this is a generated event from an IME action button, then
                    // don't let it be inserted into the text.
                    if ((event.getFlags() & KeyEvent.FLAG_EDITOR_ACTION) != 0
                            || shouldAdvanceFocusOnEnter()) {
                        if (hasOnClickListeners()) {
                            return KEY_EVENT_NOT_HANDLED;
                        }
                        return KEY_EVENT_HANDLED;
                    }
                }
                break;

            case KeyEvent.KEYCODE_DPAD_CENTER:
                if (event.hasNoModifiers()) {
                    if (shouldAdvanceFocusOnEnter()) {
                        return KEY_EVENT_NOT_HANDLED;
                    }
                }
                break;

            case KeyEvent.KEYCODE_TAB:
                if (event.hasNoModifiers() || event.hasModifiers(KeyEvent.META_SHIFT_ON)) {
                    // Tab is used to move focus.
                    return KEY_EVENT_NOT_HANDLED;
                }
                break;

                // Has to be done on key down (and not on key up) to correctly be intercepted.
            case KeyEvent.KEYCODE_BACK:
                if (mEditor != null && mEditor.getTextActionMode() != null) {
                    stopTextActionMode();
                    return KEY_EVENT_HANDLED;
                }
                break;

            case KeyEvent.KEYCODE_CUT:
                if (event.hasNoModifiers() && canCut()) {
                    if (onTextContextMenuItem(ID_CUT)) {
                        return KEY_EVENT_HANDLED;
                    }
                }
                break;

            case KeyEvent.KEYCODE_COPY:
                if (event.hasNoModifiers() && canCopy()) {
                    if (onTextContextMenuItem(ID_COPY)) {
                        return KEY_EVENT_HANDLED;
                    }
                }
                break;

            case KeyEvent.KEYCODE_PASTE:
                if (event.hasNoModifiers() && canPaste()) {
                    if (onTextContextMenuItem(ID_PASTE)) {
                        return KEY_EVENT_HANDLED;
                    }
                }
                break;

            case KeyEvent.KEYCODE_FORWARD_DEL:
                if (event.hasModifiers(KeyEvent.META_SHIFT_ON) && canCut()) {
                    if (onTextContextMenuItem(ID_CUT)) {
                        return KEY_EVENT_HANDLED;
                    }
                }
                break;

            case KeyEvent.KEYCODE_INSERT:
                if (event.hasModifiers(KeyEvent.META_CTRL_ON) && canCopy()) {
                    if (onTextContextMenuItem(ID_COPY)) {
                        return KEY_EVENT_HANDLED;
                    }
                } else if (event.hasModifiers(KeyEvent.META_SHIFT_ON) && canPaste()) {
                    if (onTextContextMenuItem(ID_PASTE)) {
                        return KEY_EVENT_HANDLED;
                    }
                }
                break;
        }

        if (mEditor != null && mEditor.mKeyListener != null) {
            boolean doDown = true;
            if (otherEvent != null) {
                try {
                    beginBatchEdit();
                    final boolean handled = mEditor.mKeyListener.onKeyOther(this, (Editable) mText,
                            otherEvent);
                    hideErrorIfUnchanged();
                    doDown = false;
                    if (handled) {
                        return KEY_EVENT_HANDLED;
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
                final boolean handled = mEditor.mKeyListener.onKeyDown(this, (Editable) mText,
                        keyCode, event);
                endBatchEdit();
                hideErrorIfUnchanged();
                if (handled) return KEY_DOWN_HANDLED_BY_KEY_LISTENER;
            }
        }

        // bug 650865: sometimes we get a key event before a layout.
        // don't try to move around if we don't know the layout.

        if (mMovement != null && mLayout != null) {
            boolean doDown = true;
            if (otherEvent != null) {
                try {
                    boolean handled = mMovement.onKeyOther(this, mSpannable, otherEvent);
                    doDown = false;
                    if (handled) {
                        return KEY_EVENT_HANDLED;
                    }
                } catch (AbstractMethodError e) {
                    // onKeyOther was added after 1.0, so if it isn't
                    // implemented we need to try to dispatch as a regular down.
                }
            }
            if (doDown) {
                if (mMovement.onKeyDown(this, mSpannable, keyCode, event)) {
                    if (event.getRepeatCount() == 0 && !KeyEvent.isModifierKey(keyCode)) {
                        mPreventDefaultMovement = true;
                    }
                    return KEY_DOWN_HANDLED_BY_MOVEMENT_METHOD;
                }
            }
            // Consume arrows from keyboard devices to prevent focus leaving the editor.
            // DPAD/JOY devices (Gamepads, TV remotes) often lack a TAB key so allow those
            // to move focus with arrows.
            if (event.getSource() == InputDevice.SOURCE_KEYBOARD
                    && isDirectionalNavigationKey(keyCode)) {
                return KEY_EVENT_HANDLED;
            }
        }

        return mPreventDefaultMovement && !KeyEvent.isModifierKey(keyCode)
                ? KEY_EVENT_HANDLED : KEY_EVENT_NOT_HANDLED;
    }

    /**
     * Resets the mErrorWasChanged flag, so that future calls to {@link #setError(CharSequence)}
     * can be recorded.
     * @hide
     */
    public void resetErrorChangedFlag() {
        /*
         * Keep track of what the error was before doing the input
         * so that if an input filter changed the error, we leave
         * that error showing.  Otherwise, we take down whatever
         * error was showing when the user types something.
         */
        if (mEditor != null) mEditor.mErrorWasChanged = false;
    }

    /**
     * @hide
     */
    public void hideErrorIfUnchanged() {
        if (mEditor != null && mEditor.mError != null && !mEditor.mErrorWasChanged) {
            setError(null, null);
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (!isEnabled()) {
            return super.onKeyUp(keyCode, event);
        }

        if (!KeyEvent.isModifierKey(keyCode)) {
            mPreventDefaultMovement = false;
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
                if (event.hasNoModifiers()) {
                    /*
                     * If there is a click listener, just call through to
                     * super, which will invoke it.
                     *
                     * If there isn't a click listener, try to show the soft
                     * input method.  (It will also
                     * call performClick(), but that won't do anything in
                     * this case.)
                     */
                    if (!hasOnClickListeners()) {
                        if (mMovement != null && mText instanceof Editable
                                && mLayout != null && onCheckIsTextEditor()) {
                            InputMethodManager imm = getInputMethodManager();
                            viewClicked(imm);
                            if (imm != null && getShowSoftInputOnFocus()) {
                                imm.showSoftInput(this, 0);
                            }
                        }
                    }
                }
                return super.onKeyUp(keyCode, event);

            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (event.hasNoModifiers()) {
                    if (mEditor != null && mEditor.mInputContentType != null
                            && mEditor.mInputContentType.onEditorActionListener != null
                            && mEditor.mInputContentType.enterDown) {
                        mEditor.mInputContentType.enterDown = false;
                        if (mEditor.mInputContentType.onEditorActionListener.onEditorAction(
                                this, getActionIdForEnterEvent(), event)) {
                            return true;
                        }
                    }

                    if ((event.getFlags() & KeyEvent.FLAG_EDITOR_ACTION) != 0
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
                        if (!hasOnClickListeners()) {
                            View v = focusSearch(FOCUS_DOWN);

                            if (v != null) {
                                if (!v.requestFocus(FOCUS_DOWN)) {
                                    throw new IllegalStateException("focus search returned a view "
                                            + "that wasn't able to take focus!");
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
                                InputMethodManager imm = getInputMethodManager();
                                if (imm != null && imm.isActive(this)) {
                                    imm.hideSoftInputFromWindow(getWindowToken(), 0);
                                }
                            }
                        }
                    }
                    return super.onKeyUp(keyCode, event);
                }
                break;
        }

        if (mEditor != null && mEditor.mKeyListener != null) {
            if (mEditor.mKeyListener.onKeyUp(this, (Editable) mText, keyCode, event)) {
                return true;
            }
        }

        if (mMovement != null && mLayout != null) {
            if (mMovement.onKeyUp(this, mSpannable, keyCode, event)) {
                return true;
            }
        }

        return super.onKeyUp(keyCode, event);
    }

    private int getActionIdForEnterEvent() {
        // If it's not single line, no action
        if (!isSingleLine()) {
            return EditorInfo.IME_NULL;
        }
        // Return the action that was specified for Enter
        return getImeOptions() & EditorInfo.IME_MASK_ACTION;
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return mEditor != null && mEditor.mInputType != EditorInfo.TYPE_NULL;
    }

    private boolean hasEditorInFocusSearchDirection(@FocusRealDirection int direction) {
        final View nextView = focusSearch(direction);
        return nextView != null && nextView.onCheckIsTextEditor();
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        if (onCheckIsTextEditor() && isEnabled()) {
            mEditor.createInputMethodStateIfNeeded();
            mEditor.mInputMethodState.mUpdateCursorAnchorInfoMode = 0;
            mEditor.mInputMethodState.mUpdateCursorAnchorInfoFilter = 0;

            outAttrs.inputType = getInputType();
            if (mEditor.mInputContentType != null) {
                outAttrs.imeOptions = mEditor.mInputContentType.imeOptions;
                outAttrs.privateImeOptions = mEditor.mInputContentType.privateImeOptions;
                outAttrs.actionLabel = mEditor.mInputContentType.imeActionLabel;
                outAttrs.actionId = mEditor.mInputContentType.imeActionId;
                outAttrs.extras = mEditor.mInputContentType.extras;
                outAttrs.hintLocales = mEditor.mInputContentType.imeHintLocales;
            } else {
                outAttrs.imeOptions = EditorInfo.IME_NULL;
                outAttrs.hintLocales = null;
            }
            if (hasEditorInFocusSearchDirection(FOCUS_DOWN)) {
                outAttrs.imeOptions |= EditorInfo.IME_FLAG_NAVIGATE_NEXT;
            }
            if (hasEditorInFocusSearchDirection(FOCUS_UP)) {
                outAttrs.imeOptions |= EditorInfo.IME_FLAG_NAVIGATE_PREVIOUS;
            }
            if ((outAttrs.imeOptions & EditorInfo.IME_MASK_ACTION)
                    == EditorInfo.IME_ACTION_UNSPECIFIED) {
                if ((outAttrs.imeOptions & EditorInfo.IME_FLAG_NAVIGATE_NEXT) != 0) {
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
            if (getResources().getConfiguration().orientation == ORIENTATION_PORTRAIT) {
                outAttrs.internalImeOptions |= EditorInfo.IME_INTERNAL_FLAG_APP_WINDOW_PORTRAIT;
            }
            if (isMultilineInputType(outAttrs.inputType)) {
                // Multi-line text editors should always show an enter key.
                outAttrs.imeOptions |= EditorInfo.IME_FLAG_NO_ENTER_ACTION;
            }
            outAttrs.hintText = mHint;
            outAttrs.targetInputMethodUser = mTextOperationUser;
            if (mText instanceof Editable) {
                InputConnection ic = new EditableInputConnection(this);
                outAttrs.initialSelStart = getSelectionStart();
                outAttrs.initialSelEnd = getSelectionEnd();
                outAttrs.initialCapsMode = ic.getCursorCapsMode(getInputType());
                outAttrs.setInitialSurroundingText(mText);
                outAttrs.contentMimeTypes = getReceiveContentMimeTypes();

                ArrayList<Class<? extends HandwritingGesture>> gestures = new ArrayList<>();
                gestures.add(SelectGesture.class);
                gestures.add(SelectRangeGesture.class);
                gestures.add(DeleteGesture.class);
                gestures.add(DeleteRangeGesture.class);
                gestures.add(InsertGesture.class);
                gestures.add(RemoveSpaceGesture.class);
                gestures.add(JoinOrSplitGesture.class);
                gestures.add(InsertModeGesture.class);
                outAttrs.setSupportedHandwritingGestures(gestures);

                Set<Class<? extends PreviewableHandwritingGesture>> previews = new ArraySet<>();
                previews.add(SelectGesture.class);
                previews.add(SelectRangeGesture.class);
                previews.add(DeleteGesture.class);
                previews.add(DeleteRangeGesture.class);
                outAttrs.setSupportedHandwritingGesturePreviews(previews);

                return ic;
            }
        }
        return null;
    }

    /**
     * Called back by the system to handle {@link InputConnection#requestCursorUpdates(int, int)}.
     *
     * @param cursorUpdateMode modes defined in {@link InputConnection.CursorUpdateMode}.
     * @param cursorUpdateFilter modes defined in {@link InputConnection.CursorUpdateFilter}.
     *
     * @hide
     */
    public void onRequestCursorUpdatesInternal(
            @InputConnection.CursorUpdateMode int cursorUpdateMode,
            @InputConnection.CursorUpdateFilter int cursorUpdateFilter) {
        mEditor.mInputMethodState.mUpdateCursorAnchorInfoMode = cursorUpdateMode;
        mEditor.mInputMethodState.mUpdateCursorAnchorInfoFilter = cursorUpdateFilter;
        if ((cursorUpdateMode & InputConnection.CURSOR_UPDATE_IMMEDIATE) == 0) {
            return;
        }
        if (isInLayout()) {
            // In this case, the view hierarchy is currently undergoing a layout pass.
            // IMM#updateCursorAnchorInfo is supposed to be called soon after the layout
            // pass is finished.
        } else {
            // This will schedule a layout pass of the view tree, and the layout event
            // eventually triggers IMM#updateCursorAnchorInfo.
            requestLayout();
        }
    }

    /**
     * If this TextView contains editable content, extract a portion of it
     * based on the information in <var>request</var> in to <var>outText</var>.
     * @return Returns true if the text was successfully extracted, else false.
     */
    public boolean extractText(ExtractedTextRequest request, ExtractedText outText) {
        createEditorIfNeeded();
        return mEditor.extractText(request, outText);
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
            } else {
                int start = 0;
                int end = content.length();

                if (text.partialStartOffset >= 0) {
                    final int N = content.length();
                    start = text.partialStartOffset;
                    if (start > N) start = N;
                    end = text.partialEndOffset;
                    if (end > N) end = N;
                }

                removeParcelableSpans(content, start, end);
                if (TextUtils.equals(content.subSequence(start, end), text.text)) {
                    if (text.text instanceof Spanned) {
                        // OK to copy spans only.
                        TextUtils.copySpansFrom((Spanned) text.text, 0, end - start,
                                Object.class, content, start);
                    }
                } else {
                    content.replace(start, end, text.text);
                }
            }
        }

        // Now set the selection position...  make sure it is in range, to
        // avoid crashes.  If this is a partial update, it is possible that
        // the underlying text may have changed, causing us problems here.
        // Also we just don't want to trust clients to do the right thing.
        Spannable sp = (Spannable) getText();
        final int N = sp.length();
        int start = text.selectionStart;
        if (start < 0) {
            start = 0;
        } else if (start > N) {
            start = N;
        }
        int end = text.selectionEnd;
        if (end < 0) {
            end = 0;
        } else if (end > N) {
            end = N;
        }
        Selection.setSelection(sp, start, end);

        // Finally, update the selection mode.
        if ((text.flags & ExtractedText.FLAG_SELECTING) != 0) {
            MetaKeyKeyListener.startSelecting(this, sp);
        } else {
            MetaKeyKeyListener.stopSelecting(this, sp);
        }

        setHintInternal(text.hint);
    }

    /**
     * @hide
     */
    public void setExtracting(ExtractedTextRequest req) {
        if (mEditor.mInputMethodState != null) {
            mEditor.mInputMethodState.mExtractedTextRequest = req;
        }
        // This would stop a possible selection mode, but no such mode is started in case
        // extracted mode will start. Some text is selected though, and will trigger an action mode
        // in the extracted view.
        mEditor.hideCursorAndSpanControllers();
        stopTextActionMode();
        if (mEditor.mSelectionModifierCursorController != null) {
            mEditor.mSelectionModifierCursorController.resetTouchOffsets();
        }
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
        // intentionally empty
    }

    /**
     * Called by the framework in response to a text auto-correction (such as fixing a typo using a
     * dictionary) from the current input method, provided by it calling
     * {@link InputConnection#commitCorrection(CorrectionInfo) InputConnection.commitCorrection()}.
     * The default implementation flashes the background of the corrected word to provide
     * feedback to the user.
     *
     * @param info The auto correct info about the text that was corrected.
     */
    public void onCommitCorrection(CorrectionInfo info) {
        if (mEditor != null) mEditor.onCommitCorrection(info);
    }

    public void beginBatchEdit() {
        if (mEditor != null) mEditor.beginBatchEdit();
    }

    public void endBatchEdit() {
        if (mEditor != null) mEditor.endBatchEdit();
    }

    /**
     * Called by the framework in response to a request to begin a batch
     * of edit operations through a call to link {@link #beginBatchEdit()}.
     */
    public void onBeginBatchEdit() {
        // intentionally empty
    }

    /**
     * Called by the framework in response to a request to end a batch
     * of edit operations through a call to link {@link #endBatchEdit}.
     */
    public void onEndBatchEdit() {
        // intentionally empty
    }

    /** @hide */
    public void onPerformSpellCheck() {
        if (mEditor != null && mEditor.mSpellChecker != null) {
            mEditor.mSpellChecker.onPerformSpellCheck();
        }
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

    /**
     * Return whether the text is transformed and has {@link OffsetMapping}.
     * @hide
     */
    public boolean isOffsetMappingAvailable() {
        return mTransformation != null && mTransformed instanceof OffsetMapping;
    }

    /** @hide */
    public boolean previewHandwritingGesture(
            @NonNull PreviewableHandwritingGesture gesture,
            @Nullable CancellationSignal cancellationSignal) {
        if (gesture instanceof SelectGesture) {
            performHandwritingSelectGesture((SelectGesture) gesture, /* isPreview= */ true);
        } else if (gesture instanceof SelectRangeGesture) {
            performHandwritingSelectRangeGesture(
                    (SelectRangeGesture) gesture, /* isPreview= */ true);
        } else if (gesture instanceof DeleteGesture) {
            performHandwritingDeleteGesture((DeleteGesture) gesture, /* isPreview= */ true);
        } else if (gesture instanceof DeleteRangeGesture) {
            performHandwritingDeleteRangeGesture(
                    (DeleteRangeGesture) gesture, /* isPreview= */ true);
        } else {
            return false;
        }
        if (cancellationSignal != null) {
            cancellationSignal.setOnCancelListener(this::clearGesturePreviewHighlight);
        }
        return true;
    }

    /** @hide */
    public int performHandwritingSelectGesture(@NonNull SelectGesture gesture) {
        return performHandwritingSelectGesture(gesture, /* isPreview= */ false);
    }

    private int performHandwritingSelectGesture(@NonNull SelectGesture gesture, boolean isPreview) {
        if (isOffsetMappingAvailable()) {
            return InputConnection.HANDWRITING_GESTURE_RESULT_FAILED;
        }
        int[] range = getRangeForRect(
                convertFromScreenToContentCoordinates(gesture.getSelectionArea()),
                gesture.getGranularity());
        if (range == null) {
            return handleGestureFailure(gesture, isPreview);
        }
        return performHandwritingSelectGesture(range, isPreview);
    }

    private int performHandwritingSelectGesture(int[] range, boolean isPreview) {
        if (isPreview) {
            setSelectGesturePreviewHighlight(range[0], range[1]);
        } else {
            Selection.setSelection(getEditableText(), range[0], range[1]);
            mEditor.startSelectionActionModeAsync(/* adjustSelection= */ false);
        }
        return InputConnection.HANDWRITING_GESTURE_RESULT_SUCCESS;
    }

    /** @hide */
    public int performHandwritingSelectRangeGesture(@NonNull SelectRangeGesture gesture) {
        return performHandwritingSelectRangeGesture(gesture, /* isPreview= */ false);
    }

    private int performHandwritingSelectRangeGesture(
            @NonNull SelectRangeGesture gesture, boolean isPreview) {
        if (isOffsetMappingAvailable()) {
            return InputConnection.HANDWRITING_GESTURE_RESULT_FAILED;
        }
        int[] startRange = getRangeForRect(
                convertFromScreenToContentCoordinates(gesture.getSelectionStartArea()),
                gesture.getGranularity());
        if (startRange == null) {
            return handleGestureFailure(gesture, isPreview);
        }
        int[] endRange = getRangeForRect(
                convertFromScreenToContentCoordinates(gesture.getSelectionEndArea()),
                gesture.getGranularity());
        if (endRange == null) {
            return handleGestureFailure(gesture, isPreview);
        }
        int[] range = new int[] {
                Math.min(startRange[0], endRange[0]), Math.max(startRange[1], endRange[1])
        };
        return performHandwritingSelectGesture(range, isPreview);
    }

    /** @hide */
    public int performHandwritingDeleteGesture(@NonNull DeleteGesture gesture) {
        return performHandwritingDeleteGesture(gesture, /* isPreview= */ false);
    }

    private int performHandwritingDeleteGesture(@NonNull DeleteGesture gesture, boolean isPreview) {
        if (isOffsetMappingAvailable()) {
            return InputConnection.HANDWRITING_GESTURE_RESULT_FAILED;
        }
        int[] range = getRangeForRect(
                convertFromScreenToContentCoordinates(gesture.getDeletionArea()),
                gesture.getGranularity());
        if (range == null) {
            return handleGestureFailure(gesture, isPreview);
        }
        return performHandwritingDeleteGesture(range, gesture.getGranularity(), isPreview);
    }

    private int performHandwritingDeleteGesture(int[] range, int granularity, boolean isPreview) {
        if (isPreview) {
            setDeleteGesturePreviewHighlight(range[0], range[1]);
        } else {
            if (granularity == HandwritingGesture.GRANULARITY_WORD) {
                range = adjustHandwritingDeleteGestureRange(range);
            }

            Selection.setSelection(getEditableText(), range[0]);
            getEditableText().delete(range[0], range[1]);
        }
        return InputConnection.HANDWRITING_GESTURE_RESULT_SUCCESS;
    }

    /** @hide */
    public int performHandwritingDeleteRangeGesture(@NonNull DeleteRangeGesture gesture) {
        return performHandwritingDeleteRangeGesture(gesture, /* isPreview= */ false);
    }

    private int performHandwritingDeleteRangeGesture(
            @NonNull DeleteRangeGesture gesture, boolean isPreview) {
        if (isOffsetMappingAvailable()) {
            return InputConnection.HANDWRITING_GESTURE_RESULT_FAILED;
        }
        int[] startRange = getRangeForRect(
                convertFromScreenToContentCoordinates(gesture.getDeletionStartArea()),
                gesture.getGranularity());
        if (startRange == null) {
            return handleGestureFailure(gesture, isPreview);
        }
        int[] endRange = getRangeForRect(
                convertFromScreenToContentCoordinates(gesture.getDeletionEndArea()),
                gesture.getGranularity());
        if (endRange == null) {
            return handleGestureFailure(gesture, isPreview);
        }
        int[] range = new int[] {
                Math.min(startRange[0], endRange[0]), Math.max(startRange[1], endRange[1])
        };
        return performHandwritingDeleteGesture(range, gesture.getGranularity(), isPreview);
    }

    private int[] adjustHandwritingDeleteGestureRange(int[] range) {
        // For handwriting delete gestures with word granularity, adjust the start and end offsets
        // to remove extra whitespace around the deleted text.

        int start = range[0];
        int end = range[1];

        // If the deleted text is at the start of the text, the behavior is the same as the case
        // where the deleted text follows a new line character.
        int codePointBeforeStart = start > 0
                ? Character.codePointBefore(mText, start) : TextUtils.LINE_FEED_CODE_POINT;
        // If the deleted text is at the end of the text, the behavior is the same as the case where
        // the deleted text precedes a new line character.
        int codePointAtEnd = end < mText.length()
                ? Character.codePointAt(mText, end) : TextUtils.LINE_FEED_CODE_POINT;

        if (TextUtils.isWhitespaceExceptNewline(codePointBeforeStart)
                && (TextUtils.isWhitespace(codePointAtEnd)
                        || TextUtils.isPunctuation(codePointAtEnd))) {
            // Remove whitespace (except new lines) before the deleted text, in these cases:
            // - There is whitespace following the deleted text
            //     e.g. "one [deleted] three" -> "one | three" -> "one| three"
            // - There is punctuation following the deleted text
            //     e.g. "one [deleted]!" -> "one |!" -> "one|!"
            // - There is a new line following the deleted text
            //     e.g. "one [deleted]\n" -> "one |\n" -> "one|\n"
            // - The deleted text is at the end of the text
            //     e.g. "one [deleted]" -> "one |" -> "one|"
            // (The pipe | indicates the cursor position.)
            do {
                start -= Character.charCount(codePointBeforeStart);
                if (start == 0) break;
                codePointBeforeStart = Character.codePointBefore(mText, start);
            } while (TextUtils.isWhitespaceExceptNewline(codePointBeforeStart));
            return new int[] {start, end};
        }

        if (TextUtils.isWhitespaceExceptNewline(codePointAtEnd)
                && (TextUtils.isWhitespace(codePointBeforeStart)
                        || TextUtils.isPunctuation(codePointBeforeStart))) {
            // Remove whitespace (except new lines) after the deleted text, in these cases:
            // - There is punctuation preceding the deleted text
            //     e.g. "([deleted] two)" -> "(| two)" -> "(|two)"
            // - There is a new line preceding the deleted text
            //     e.g. "\n[deleted] two" -> "\n| two" -> "\n|two"
            // - The deleted text is at the start of the text
            //     e.g. "[deleted] two" -> "| two" -> "|two"
            // (The pipe | indicates the cursor position.)
            do {
                end += Character.charCount(codePointAtEnd);
                if (end == mText.length()) break;
                codePointAtEnd = Character.codePointAt(mText, end);
            } while (TextUtils.isWhitespaceExceptNewline(codePointAtEnd));
            return new int[] {start, end};
        }

        // Return the original range.
        return range;
    }

    /** @hide */
    public int performHandwritingInsertGesture(@NonNull InsertGesture gesture) {
        if (isOffsetMappingAvailable()) {
            return InputConnection.HANDWRITING_GESTURE_RESULT_FAILED;
        }
        PointF point = convertFromScreenToContentCoordinates(gesture.getInsertionPoint());
        int line = getLineForHandwritingGesture(point);
        if (line == -1) {
            return handleGestureFailure(gesture);
        }
        int offset = mLayout.getOffsetForHorizontal(line, point.x);
        String textToInsert = gesture.getTextToInsert();
        return tryInsertTextForHandwritingGesture(offset, textToInsert, gesture);
        // TODO(b/243980426): Insert extra spaces if necessary.
    }

    /** @hide */
    public int performHandwritingRemoveSpaceGesture(@NonNull RemoveSpaceGesture gesture) {
        if (isOffsetMappingAvailable()) {
            return InputConnection.HANDWRITING_GESTURE_RESULT_FAILED;
        }
        PointF startPoint = convertFromScreenToContentCoordinates(gesture.getStartPoint());
        PointF endPoint = convertFromScreenToContentCoordinates(gesture.getEndPoint());

        // The operation should be applied to the first line of text containing one of the points.
        int startPointLine = getLineForHandwritingGesture(startPoint);
        int endPointLine = getLineForHandwritingGesture(endPoint);
        int line;
        if (startPointLine == -1) {
            if (endPointLine == -1) {
                return handleGestureFailure(gesture);
            }
            line = endPointLine;
        } else {
            line = (endPointLine == -1) ? startPointLine : Math.min(startPointLine, endPointLine);
        }

        // The operation should be applied to all characters touched by the line joining the points.
        float lineVerticalCenter = (mLayout.getLineTop(line)
                + mLayout.getLineBottom(line, /* includeLineSpacing= */ false)) / 2f;
        // Create a rectangle which is +/-0.1f around the line's vertical center, so that the
        // rectangle doesn't touch the line above or below. (The line height is at least 1f.)
        RectF area = new RectF(
                Math.min(startPoint.x, endPoint.x),
                lineVerticalCenter + 0.1f,
                Math.max(startPoint.x, endPoint.x),
                lineVerticalCenter - 0.1f);
        int[] range = mLayout.getRangeForRect(
                area, new GraphemeClusterSegmentFinder(mText, mTextPaint),
                Layout.INCLUSION_STRATEGY_ANY_OVERLAP);
        if (range == null) {
            return handleGestureFailure(gesture);
        }
        int startOffset = range[0];
        int endOffset = range[1];
        // TODO(b/247557062): This doesn't handle bidirectional text correctly.

        Pattern whitespacePattern = getWhitespacePattern();
        Matcher matcher = whitespacePattern.matcher(mText.subSequence(startOffset, endOffset));
        int lastRemoveOffset = -1;
        while (matcher.find()) {
            lastRemoveOffset = startOffset + matcher.start();
            getEditableText().delete(lastRemoveOffset, startOffset + matcher.end());
            startOffset = lastRemoveOffset;
            endOffset -= matcher.end() - matcher.start();
            if (startOffset == endOffset) {
                break;
            }
            matcher = whitespacePattern.matcher(mText.subSequence(startOffset, endOffset));
        }
        if (lastRemoveOffset == -1) {
            return handleGestureFailure(gesture);
        }
        Selection.setSelection(getEditableText(), lastRemoveOffset);
        return InputConnection.HANDWRITING_GESTURE_RESULT_SUCCESS;
    }

    /** @hide */
    public int performHandwritingJoinOrSplitGesture(@NonNull JoinOrSplitGesture gesture) {
        if (isOffsetMappingAvailable()) {
            return InputConnection.HANDWRITING_GESTURE_RESULT_FAILED;
        }
        PointF point = convertFromScreenToContentCoordinates(gesture.getJoinOrSplitPoint());

        int line = getLineForHandwritingGesture(point);
        if (line == -1) {
            return handleGestureFailure(gesture);
        }

        int startOffset = mLayout.getOffsetForHorizontal(line, point.x);
        if (mLayout.isLevelBoundary(startOffset)) {
            // TODO(b/247551937): Support gesture at level boundaries.
            return handleGestureFailure(gesture);
        }

        int endOffset = startOffset;
        while (startOffset > 0) {
            int codePointBeforeStart = Character.codePointBefore(mText, startOffset);
            if (!TextUtils.isWhitespace(codePointBeforeStart)) {
                break;
            }
            startOffset -= Character.charCount(codePointBeforeStart);
        }
        while (endOffset < mText.length()) {
            int codePointAtEnd = Character.codePointAt(mText, endOffset);
            if (!TextUtils.isWhitespace(codePointAtEnd)) {
                break;
            }
            endOffset += Character.charCount(codePointAtEnd);
        }
        if (startOffset < endOffset) {
            Selection.setSelection(getEditableText(), startOffset);
            getEditableText().delete(startOffset, endOffset);
            return InputConnection.HANDWRITING_GESTURE_RESULT_SUCCESS;
        } else {
            // No whitespace found, so insert a space.
            return tryInsertTextForHandwritingGesture(startOffset, " ", gesture);
        }
    }

    /** @hide */
    public int performHandwritingInsertModeGesture(@NonNull InsertModeGesture gesture) {
        final PointF insertPoint =
                convertFromScreenToContentCoordinates(gesture.getInsertionPoint());
        final int line = getLineForHandwritingGesture(insertPoint);
        final CancellationSignal cancellationSignal = gesture.getCancellationSignal();

        // If no cancellationSignal is provided, don't enter the insert mode.
        if (line == -1 || cancellationSignal == null) {
            return handleGestureFailure(gesture);
        }

        final int offset = mLayout.getOffsetForHorizontal(line, insertPoint.x);

        if (!mEditor.enterInsertMode(offset)) {
            return InputConnection.HANDWRITING_GESTURE_RESULT_FAILED;
        }
        cancellationSignal.setOnCancelListener(() -> mEditor.exitInsertMode());
        return InputConnection.HANDWRITING_GESTURE_RESULT_SUCCESS;
    }

    private int handleGestureFailure(HandwritingGesture gesture) {
        return handleGestureFailure(gesture, /* isPreview= */ false);
    }

    private int handleGestureFailure(HandwritingGesture gesture, boolean isPreview) {
        clearGesturePreviewHighlight();
        if (!isPreview && !TextUtils.isEmpty(gesture.getFallbackText())) {
            getEditableText()
                    .replace(getSelectionStart(), getSelectionEnd(), gesture.getFallbackText());
            return InputConnection.HANDWRITING_GESTURE_RESULT_FALLBACK;
        }
        return InputConnection.HANDWRITING_GESTURE_RESULT_FAILED;
    }

    /**
     * Returns the closest line such that the point is either inside the line bounds or within
     * {@link ViewConfiguration#getScaledHandwritingGestureLineMargin} of the line bounds. Returns
     * -1 if the point is not within the margin of any line bounds.
     */
    private int getLineForHandwritingGesture(PointF point) {
        int line = mLayout.getLineForVertical((int) point.y);
        int lineMargin = ViewConfiguration.get(mContext).getScaledHandwritingGestureLineMargin();
        if (line < mLayout.getLineCount() - 1
                && point.y > mLayout.getLineBottom(line) - lineMargin
                && point.y
                        > (mLayout.getLineBottom(line, false) + mLayout.getLineBottom(line)) / 2f) {
            // If a point is in the space between line i and line (i + 1), Layout#getLineForVertical
            // returns i. If the point is within lineMargin of line (i + 1), and closer to line
            // (i + 1) than line i, then the gesture operation should be applied to line (i + 1).
            line++;
        } else if (point.y < mLayout.getLineTop(line) - lineMargin
                || point.y
                        > mLayout.getLineBottom(line, /* includeLineSpacing= */ false)
                                + lineMargin) {
            // The point is not within lineMargin of a line.
            return -1;
        }
        if (point.x < -lineMargin || point.x > mLayout.getWidth() + lineMargin) {
            // The point is not within lineMargin of a line.
            return -1;
        }
        return line;
    }

    @Nullable
    private int[] getRangeForRect(@NonNull RectF area, int granularity) {
        SegmentFinder segmentFinder;
        if (granularity == HandwritingGesture.GRANULARITY_WORD) {
            WordIterator wordIterator = getWordIterator();
            wordIterator.setCharSequence(mText, 0, mText.length());
            segmentFinder = new WordSegmentFinder(mText, wordIterator);
        } else {
            segmentFinder = new GraphemeClusterSegmentFinder(mText, mTextPaint);
        }

        return mLayout.getRangeForRect(
                area, segmentFinder, Layout.INCLUSION_STRATEGY_CONTAINS_CENTER);
    }

    private int tryInsertTextForHandwritingGesture(
            int offset, String textToInsert, HandwritingGesture gesture) {
        // A temporary cursor span is placed at the insertion offset. The span will be pushed
        // forward when text is inserted, then the real cursor can be placed after the inserted
        // text. A temporary cursor span is used in order to avoid modifying the real selection span
        // in the case that the text is filtered out.
        Editable editableText = getEditableText();
        if (mTempCursor == null) {
            mTempCursor = new NoCopySpan.Concrete();
        }
        editableText.setSpan(mTempCursor, offset, offset, Spanned.SPAN_POINT_POINT);

        editableText.insert(offset, textToInsert);

        int newOffset = editableText.getSpanStart(mTempCursor);
        editableText.removeSpan(mTempCursor);
        if (newOffset == offset) {
            // The inserted text was filtered out.
            return handleGestureFailure(gesture);
        } else {
            // Place the cursor after the inserted text.
            Selection.setSelection(editableText, newOffset);
            return InputConnection.HANDWRITING_GESTURE_RESULT_SUCCESS;
        }
    }

    private Pattern getWhitespacePattern() {
        if (mWhitespacePattern == null) {
            mWhitespacePattern = Pattern.compile("\\s+");
        }
        return mWhitespacePattern;
    }

    /** @hide */
    @VisibleForTesting
    @UnsupportedAppUsage
    public void nullLayouts() {
        if (mLayout instanceof BoringLayout && mSavedLayout == null) {
            mSavedLayout = (BoringLayout) mLayout;
        }
        if (mHintLayout instanceof BoringLayout && mSavedHintLayout == null) {
            mSavedHintLayout = (BoringLayout) mHintLayout;
        }

        mSavedMarqueeModeLayout = mLayout = mHintLayout = null;

        mBoring = mHintBoring = null;

        // Since it depends on the value of mLayout
        if (mEditor != null) mEditor.prepareCursorControllers();
    }

    /**
     * Make a new Layout based on the already-measured size of the view,
     * on the assumption that it was measured correctly at some point.
     */
    @UnsupportedAppUsage
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

    @UnsupportedAppUsage
    private Layout.Alignment getLayoutAlignment() {
        Layout.Alignment alignment;
        switch (getTextAlignment()) {
            case TEXT_ALIGNMENT_GRAVITY:
                switch (mGravity & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK) {
                    case Gravity.START:
                        alignment = Layout.Alignment.ALIGN_NORMAL;
                        break;
                    case Gravity.END:
                        alignment = Layout.Alignment.ALIGN_OPPOSITE;
                        break;
                    case Gravity.LEFT:
                        alignment = Layout.Alignment.ALIGN_LEFT;
                        break;
                    case Gravity.RIGHT:
                        alignment = Layout.Alignment.ALIGN_RIGHT;
                        break;
                    case Gravity.CENTER_HORIZONTAL:
                        alignment = Layout.Alignment.ALIGN_CENTER;
                        break;
                    default:
                        alignment = Layout.Alignment.ALIGN_NORMAL;
                        break;
                }
                break;
            case TEXT_ALIGNMENT_TEXT_START:
                alignment = Layout.Alignment.ALIGN_NORMAL;
                break;
            case TEXT_ALIGNMENT_TEXT_END:
                alignment = Layout.Alignment.ALIGN_OPPOSITE;
                break;
            case TEXT_ALIGNMENT_CENTER:
                alignment = Layout.Alignment.ALIGN_CENTER;
                break;
            case TEXT_ALIGNMENT_VIEW_START:
                alignment = (getLayoutDirection() == LAYOUT_DIRECTION_RTL)
                        ? Layout.Alignment.ALIGN_RIGHT : Layout.Alignment.ALIGN_LEFT;
                break;
            case TEXT_ALIGNMENT_VIEW_END:
                alignment = (getLayoutDirection() == LAYOUT_DIRECTION_RTL)
                        ? Layout.Alignment.ALIGN_LEFT : Layout.Alignment.ALIGN_RIGHT;
                break;
            case TEXT_ALIGNMENT_INHERIT:
                // This should never happen as we have already resolved the text alignment
                // but better safe than sorry so we just fall through
            default:
                alignment = Layout.Alignment.ALIGN_NORMAL;
                break;
        }
        return alignment;
    }

    /**
     * The width passed in is now the desired layout width,
     * not the full view width with padding.
     * {@hide}
     */
    @VisibleForTesting
    @UnsupportedAppUsage
    public void makeNewLayout(int wantWidth, int hintWidth,
                                 BoringLayout.Metrics boring,
                                 BoringLayout.Metrics hintBoring,
                                 int ellipsisWidth, boolean bringIntoView) {
        stopMarquee();

        // Update "old" cached values
        mOldMaximum = mMaximum;
        mOldMaxMode = mMaxMode;

        mHighlightPathBogus = true;
        mHighlightPathsBogus = true;

        if (wantWidth < 0) {
            wantWidth = 0;
        }
        if (hintWidth < 0) {
            hintWidth = 0;
        }

        Layout.Alignment alignment = getLayoutAlignment();
        final boolean testDirChange = mSingleLine && mLayout != null
                && (alignment == Layout.Alignment.ALIGN_NORMAL
                        || alignment == Layout.Alignment.ALIGN_OPPOSITE);
        int oldDir = 0;
        if (testDirChange) oldDir = mLayout.getParagraphDirection(0);
        boolean shouldEllipsize = mEllipsize != null && getKeyListener() == null;
        final boolean switchEllipsize = mEllipsize == TruncateAt.MARQUEE
                && mMarqueeFadeMode != MARQUEE_FADE_NORMAL;
        TruncateAt effectiveEllipsize = mEllipsize;
        if (mEllipsize == TruncateAt.MARQUEE
                && mMarqueeFadeMode == MARQUEE_FADE_SWITCH_SHOW_ELLIPSIS) {
            effectiveEllipsize = TruncateAt.END_SMALL;
        }

        if (mTextDir == null) {
            mTextDir = getTextDirectionHeuristic();
        }

        mLayout = makeSingleLayout(wantWidth, boring, ellipsisWidth, alignment, shouldEllipsize,
                effectiveEllipsize, effectiveEllipsize == mEllipsize);
        if (switchEllipsize) {
            TruncateAt oppositeEllipsize = effectiveEllipsize == TruncateAt.MARQUEE
                    ? TruncateAt.END : TruncateAt.MARQUEE;
            mSavedMarqueeModeLayout = makeSingleLayout(wantWidth, boring, ellipsisWidth, alignment,
                    shouldEllipsize, oppositeEllipsize, effectiveEllipsize != mEllipsize);
        }

        shouldEllipsize = mEllipsize != null;
        mHintLayout = null;

        if (mHint != null) {
            if (shouldEllipsize) hintWidth = wantWidth;

            if (hintBoring == UNKNOWN_BORING) {
                hintBoring = BoringLayout.isBoring(mHint, mTextPaint, mTextDir,
                        isFallbackLineSpacingForBoringLayout(), mHintBoring);
                if (hintBoring != null) {
                    mHintBoring = hintBoring;
                }
            }

            if (hintBoring != null) {
                if (hintBoring.width <= hintWidth
                        && (!shouldEllipsize || hintBoring.width <= ellipsisWidth)) {
                    if (mSavedHintLayout != null) {
                        mHintLayout = mSavedHintLayout.replaceOrMake(mHint, mTextPaint,
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
                        mHintLayout = mSavedHintLayout.replaceOrMake(mHint, mTextPaint,
                                hintWidth, alignment, mSpacingMult, mSpacingAdd,
                                hintBoring, mIncludePad, mEllipsize,
                                ellipsisWidth);
                    } else {
                        mHintLayout = BoringLayout.make(mHint, mTextPaint,
                                hintWidth, alignment, mSpacingMult, mSpacingAdd,
                                hintBoring, mIncludePad, mEllipsize,
                                ellipsisWidth);
                    }
                }
            }
            // TODO: code duplication with makeSingleLayout()
            if (mHintLayout == null) {
                final boolean autoPhraseBreaking =
                        !mUserSpeficiedLineBreakwordStyle && FeatureFlagUtils.isEnabled(mContext,
                                FeatureFlagUtils.SETTINGS_AUTO_TEXT_WRAPPING);
                StaticLayout.Builder builder = StaticLayout.Builder.obtain(mHint, 0,
                        mHint.length(), mTextPaint, hintWidth)
                        .setAlignment(alignment)
                        .setTextDirection(mTextDir)
                        .setLineSpacing(mSpacingAdd, mSpacingMult)
                        .setIncludePad(mIncludePad)
                        .setUseLineSpacingFromFallbacks(isFallbackLineSpacingForStaticLayout())
                        .setBreakStrategy(mBreakStrategy)
                        .setHyphenationFrequency(mHyphenationFrequency)
                        .setJustificationMode(mJustificationMode)
                        .setMaxLines(mMaxMode == LINES ? mMaximum : Integer.MAX_VALUE)
                        .setLineBreakConfig(LineBreakConfig.getLineBreakConfig(
                                mLineBreakStyle, mLineBreakWordStyle, autoPhraseBreaking));
                if (shouldEllipsize) {
                    builder.setEllipsize(mEllipsize)
                            .setEllipsizedWidth(ellipsisWidth);
                }
                mHintLayout = builder.build();
            }
        }

        if (bringIntoView || (testDirChange && oldDir != mLayout.getParagraphDirection(0))) {
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
        if (mEditor != null) mEditor.prepareCursorControllers();
    }

    /**
     * Returns true if DynamicLayout is required
     *
     * @hide
     */
    @VisibleForTesting
    public boolean useDynamicLayout() {
        return isTextSelectable() || (mSpannable != null && mPrecomputed == null);
    }

    /**
     * @hide
     */
    protected Layout makeSingleLayout(int wantWidth, BoringLayout.Metrics boring, int ellipsisWidth,
            Layout.Alignment alignment, boolean shouldEllipsize, TruncateAt effectiveEllipsize,
            boolean useSaved) {
        Layout result = null;
        if (useDynamicLayout()) {
            final DynamicLayout.Builder builder = DynamicLayout.Builder.obtain(mText, mTextPaint,
                    wantWidth)
                    .setDisplayText(mTransformed)
                    .setAlignment(alignment)
                    .setTextDirection(mTextDir)
                    .setLineSpacing(mSpacingAdd, mSpacingMult)
                    .setIncludePad(mIncludePad)
                    .setUseLineSpacingFromFallbacks(isFallbackLineSpacingForStaticLayout())
                    .setBreakStrategy(mBreakStrategy)
                    .setHyphenationFrequency(mHyphenationFrequency)
                    .setJustificationMode(mJustificationMode)
                    .setEllipsize(getKeyListener() == null ? effectiveEllipsize : null)
                    .setEllipsizedWidth(ellipsisWidth);
            result = builder.build();
        } else {
            if (boring == UNKNOWN_BORING) {
                boring = BoringLayout.isBoring(mTransformed, mTextPaint, mTextDir,
                        isFallbackLineSpacingForBoringLayout(), mBoring);
                if (boring != null) {
                    mBoring = boring;
                }
            }

            if (boring != null) {
                if (boring.width <= wantWidth
                        && (effectiveEllipsize == null || boring.width <= ellipsisWidth)) {
                    if (useSaved && mSavedLayout != null) {
                        result = mSavedLayout.replaceOrMake(mTransformed, mTextPaint,
                                wantWidth, alignment, mSpacingMult, mSpacingAdd,
                                boring, mIncludePad);
                    } else {
                        result = BoringLayout.make(mTransformed, mTextPaint,
                                wantWidth, alignment, mSpacingMult, mSpacingAdd,
                                boring, mIncludePad);
                    }

                    if (useSaved) {
                        mSavedLayout = (BoringLayout) result;
                    }
                } else if (shouldEllipsize && boring.width <= wantWidth) {
                    if (useSaved && mSavedLayout != null) {
                        result = mSavedLayout.replaceOrMake(mTransformed, mTextPaint,
                                wantWidth, alignment, mSpacingMult, mSpacingAdd,
                                boring, mIncludePad, effectiveEllipsize,
                                ellipsisWidth);
                    } else {
                        result = BoringLayout.make(mTransformed, mTextPaint,
                                wantWidth, alignment, mSpacingMult, mSpacingAdd,
                                boring, mIncludePad, effectiveEllipsize,
                                ellipsisWidth);
                    }
                }
            }
        }
        if (result == null) {
            final boolean autoPhraseBreaking =
                    !mUserSpeficiedLineBreakwordStyle && FeatureFlagUtils.isEnabled(mContext,
                            FeatureFlagUtils.SETTINGS_AUTO_TEXT_WRAPPING);
            StaticLayout.Builder builder = StaticLayout.Builder.obtain(mTransformed,
                    0, mTransformed.length(), mTextPaint, wantWidth)
                    .setAlignment(alignment)
                    .setTextDirection(mTextDir)
                    .setLineSpacing(mSpacingAdd, mSpacingMult)
                    .setIncludePad(mIncludePad)
                    .setUseLineSpacingFromFallbacks(isFallbackLineSpacingForStaticLayout())
                    .setBreakStrategy(mBreakStrategy)
                    .setHyphenationFrequency(mHyphenationFrequency)
                    .setJustificationMode(mJustificationMode)
                    .setMaxLines(mMaxMode == LINES ? mMaximum : Integer.MAX_VALUE)
                    .setLineBreakConfig(LineBreakConfig.getLineBreakConfig(
                            mLineBreakStyle, mLineBreakWordStyle, autoPhraseBreaking));
            if (shouldEllipsize) {
                builder.setEllipsize(effectiveEllipsize)
                        .setEllipsizedWidth(ellipsisWidth);
            }
            result = builder.build();
        }
        return result;
    }

    @UnsupportedAppUsage
    private boolean compressText(float width) {
        if (isHardwareAccelerated()) return false;

        // Only compress the text if it hasn't been compressed by the previous pass
        if (width > 0.0f && mLayout != null && getLineCount() == 1 && !mUserSetTextScaleX
                && mTextPaint.getTextScaleX() == 1.0f) {
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
            if (text.charAt(layout.getLineEnd(i) - 1) != '\n') {
                return -1;
            }
        }

        for (int i = 0; i < n; i++) {
            max = Math.max(max, layout.getLineMax(i));
        }

        return (int) Math.ceil(max);
    }

    /**
     * Set whether the TextView includes extra top and bottom padding to make
     * room for accents that go above the normal ascent and descent.
     * The default is true.
     *
     * @see #getIncludeFontPadding()
     *
     * @attr ref android.R.styleable#TextView_includeFontPadding
     */
    public void setIncludeFontPadding(boolean includepad) {
        if (mIncludePad != includepad) {
            mIncludePad = includepad;

            if (mLayout != null) {
                nullLayouts();
                requestLayout();
                invalidate();
            }
        }
    }

    /**
     * Gets whether the TextView includes extra top and bottom padding to make
     * room for accents that go above the normal ascent and descent.
     *
     * @see #setIncludeFontPadding(boolean)
     *
     * @attr ref android.R.styleable#TextView_includeFontPadding
     */
    @InspectableProperty
    public boolean getIncludeFontPadding() {
        return mIncludePad;
    }

    /** @hide */
    @VisibleForTesting
    public static final BoringLayout.Metrics UNKNOWN_BORING = new BoringLayout.Metrics();

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

        if (mTextDir == null) {
            mTextDir = getTextDirectionHeuristic();
        }

        int des = -1;
        boolean fromexisting = false;
        final float widthLimit = (widthMode == MeasureSpec.AT_MOST)
                ?  (float) widthSize : Float.MAX_VALUE;

        if (widthMode == MeasureSpec.EXACTLY) {
            // Parent has told us how big to be. So be it.
            width = widthSize;
        } else {
            if (mLayout != null && mEllipsize == null) {
                des = desired(mLayout);
            }

            if (des < 0) {
                boring = BoringLayout.isBoring(mTransformed, mTextPaint, mTextDir,
                        isFallbackLineSpacingForBoringLayout(), mBoring);
                if (boring != null) {
                    mBoring = boring;
                }
            } else {
                fromexisting = true;
            }

            if (boring == null || boring == UNKNOWN_BORING) {
                if (des < 0) {
                    des = (int) Math.ceil(Layout.getDesiredWidthWithLimit(mTransformed, 0,
                            mTransformed.length(), mTextPaint, mTextDir, widthLimit));
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
                    hintBoring = BoringLayout.isBoring(mHint, mTextPaint, mTextDir,
                            isFallbackLineSpacingForBoringLayout(), mHintBoring);
                    if (hintBoring != null) {
                        mHintBoring = hintBoring;
                    }
                }

                if (hintBoring == null || hintBoring == UNKNOWN_BORING) {
                    if (hintDes < 0) {
                        hintDes = (int) Math.ceil(Layout.getDesiredWidthWithLimit(mHint, 0,
                                mHint.length(), mTextPaint, mTextDir, widthLimit));
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

        if (mHorizontallyScrolling) want = VERY_WIDE;

        int hintWant = want;
        int hintWidth = (mHintLayout == null) ? hintWant : mHintLayout.getWidth();

        if (mLayout == null) {
            makeNewLayout(want, hintWant, boring, hintBoring,
                          width - getCompoundPaddingLeft() - getCompoundPaddingRight(), false);
        } else {
            final boolean layoutChanged = (mLayout.getWidth() != want) || (hintWidth != hintWant)
                    || (mLayout.getEllipsizedWidth()
                            != width - getCompoundPaddingLeft() - getCompoundPaddingRight());

            final boolean widthChanged = (mHint == null) && (mEllipsize == null)
                    && (want > mLayout.getWidth())
                    && (mLayout instanceof BoringLayout
                            || (fromexisting && des >= 0 && des <= want));

            final boolean maximumChanged = (mMaxMode != mOldMaxMode) || (mMaximum != mOldMaximum);

            if (layoutChanged || maximumChanged) {
                if (!maximumChanged && widthChanged) {
                    mLayout.increaseWidthTo(want);
                } else {
                    makeNewLayout(want, hintWant, boring, hintBoring,
                            width - getCompoundPaddingLeft() - getCompoundPaddingRight(), false);
                }
            } else {
                // Nothing has changed
            }
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
        if (mMovement != null
                || mLayout.getWidth() > unpaddedWidth
                || mLayout.getHeight() > unpaddedHeight) {
            registerForPreDraw();
        } else {
            scrollTo(0, 0);
        }

        setMeasuredDimension(width, height);
    }

    /**
     * Automatically computes and sets the text size.
     */
    private void autoSizeText() {
        if (!isAutoSizeEnabled()) {
            return;
        }

        if (mNeedsAutoSizeText) {
            if (getMeasuredWidth() <= 0 || getMeasuredHeight() <= 0) {
                return;
            }

            final int availableWidth = mHorizontallyScrolling
                    ? VERY_WIDE
                    : getMeasuredWidth() - getTotalPaddingLeft() - getTotalPaddingRight();
            final int availableHeight = getMeasuredHeight() - getExtendedPaddingBottom()
                    - getExtendedPaddingTop();

            if (availableWidth <= 0 || availableHeight <= 0) {
                return;
            }

            synchronized (TEMP_RECTF) {
                TEMP_RECTF.setEmpty();
                TEMP_RECTF.right = availableWidth;
                TEMP_RECTF.bottom = availableHeight;
                final float optimalTextSize = findLargestTextSizeWhichFits(TEMP_RECTF);

                if (optimalTextSize != getTextSize()) {
                    setTextSizeInternal(TypedValue.COMPLEX_UNIT_PX, optimalTextSize,
                            false /* shouldRequestLayout */);

                    makeNewLayout(availableWidth, 0 /* hintWidth */, UNKNOWN_BORING, UNKNOWN_BORING,
                            mRight - mLeft - getCompoundPaddingLeft() - getCompoundPaddingRight(),
                            false /* bringIntoView */);
                }
            }
        }
        // Always try to auto-size if enabled. Functions that do not want to trigger auto-sizing
        // after the next layout pass should set this to false.
        mNeedsAutoSizeText = true;
    }

    /**
     * Performs a binary search to find the largest text size that will still fit within the size
     * available to this view.
     */
    private int findLargestTextSizeWhichFits(RectF availableSpace) {
        final int sizesCount = mAutoSizeTextSizesInPx.length;
        if (sizesCount == 0) {
            throw new IllegalStateException("No available text sizes to choose from.");
        }

        int bestSizeIndex = 0;
        int lowIndex = bestSizeIndex + 1;
        int highIndex = sizesCount - 1;
        int sizeToTryIndex;
        while (lowIndex <= highIndex) {
            sizeToTryIndex = (lowIndex + highIndex) / 2;
            if (suggestedSizeFitsInSpace(mAutoSizeTextSizesInPx[sizeToTryIndex], availableSpace)) {
                bestSizeIndex = lowIndex;
                lowIndex = sizeToTryIndex + 1;
            } else {
                highIndex = sizeToTryIndex - 1;
                bestSizeIndex = highIndex;
            }
        }

        return mAutoSizeTextSizesInPx[bestSizeIndex];
    }

    private boolean suggestedSizeFitsInSpace(int suggestedSizeInPx, RectF availableSpace) {
        final CharSequence text = mTransformed != null
                ? mTransformed
                : getText();
        final int maxLines = getMaxLines();
        if (mTempTextPaint == null) {
            mTempTextPaint = new TextPaint();
        } else {
            mTempTextPaint.reset();
        }
        mTempTextPaint.set(getPaint());
        mTempTextPaint.setTextSize(suggestedSizeInPx);

        final StaticLayout.Builder layoutBuilder = StaticLayout.Builder.obtain(
                text, 0, text.length(),  mTempTextPaint, Math.round(availableSpace.right));
        final boolean autoPhraseBreaking =
                !mUserSpeficiedLineBreakwordStyle && FeatureFlagUtils.isEnabled(mContext,
                        FeatureFlagUtils.SETTINGS_AUTO_TEXT_WRAPPING);
        layoutBuilder.setAlignment(getLayoutAlignment())
                .setLineSpacing(getLineSpacingExtra(), getLineSpacingMultiplier())
                .setIncludePad(getIncludeFontPadding())
                .setUseLineSpacingFromFallbacks(isFallbackLineSpacingForStaticLayout())
                .setBreakStrategy(getBreakStrategy())
                .setHyphenationFrequency(getHyphenationFrequency())
                .setJustificationMode(getJustificationMode())
                .setMaxLines(mMaxMode == LINES ? mMaximum : Integer.MAX_VALUE)
                .setTextDirection(getTextDirectionHeuristic())
                .setLineBreakConfig(LineBreakConfig.getLineBreakConfig(
                        mLineBreakStyle, mLineBreakWordStyle, autoPhraseBreaking));

        final StaticLayout layout = layoutBuilder.build();

        // Lines overflow.
        if (maxLines != -1 && layout.getLineCount() > maxLines) {
            return false;
        }

        // Height overflow.
        if (layout.getHeight() > availableSpace.bottom) {
            return false;
        }

        return true;
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

        /*
        * Don't cap the hint to a certain number of lines.
        * (Do cap it, though, if we have a maximum pixel height.)
        */
        int desired = layout.getHeight(cap);

        final Drawables dr = mDrawables;
        if (dr != null) {
            desired = Math.max(desired, dr.mDrawableHeightLeft);
            desired = Math.max(desired, dr.mDrawableHeightRight);
        }

        int linecount = layout.getLineCount();
        final int padding = getCompoundPaddingTop() + getCompoundPaddingBottom();
        desired += padding;

        if (mMaxMode != LINES) {
            desired = Math.min(desired, mMaximum);
        } else if (cap && linecount > mMaximum && (layout instanceof DynamicLayout
                || layout instanceof BoringLayout)) {
            desired = layout.getLineTop(mMaximum);

            if (dr != null) {
                desired = Math.max(desired, dr.mDrawableHeightLeft);
                desired = Math.max(desired, dr.mDrawableHeightRight);
            }

            desired += padding;
            linecount = mMaximum;
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
    @UnsupportedAppUsage
    private void checkForRelayout() {
        // If we have a fixed width, we can just swap in a new text layout
        // if the text height stays the same or if the view height is fixed.

        if ((mLayoutParams.width != LayoutParams.WRAP_CONTENT
                || (mMaxWidthMode == mMinWidthMode && mMaxWidth == mMinWidth))
                && (mHint == null || mHintLayout != null)
                && (mRight - mLeft - getCompoundPaddingLeft() - getCompoundPaddingRight() > 0)) {
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
                if (mLayoutParams.height != LayoutParams.WRAP_CONTENT
                        && mLayoutParams.height != LayoutParams.MATCH_PARENT) {
                    autoSizeText();
                    invalidate();
                    return;
                }

                // Dynamic height, but height has stayed the same,
                // so use our new text layout.
                if (mLayout.getHeight() == oldht
                        && (mHintLayout == null || mHintLayout.getHeight() == oldht)) {
                    autoSizeText();
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

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (mDeferScroll >= 0) {
            int curs = mDeferScroll;
            mDeferScroll = -1;
            bringPointIntoView(Math.min(curs, mText.length()));
        }
        // Call auto-size after the width and height have been calculated.
        autoSizeText();
    }

    private boolean isShowingHint() {
        return TextUtils.isEmpty(mText) && !TextUtils.isEmpty(mHint) && !mHideHint;
    }

    /**
     * Returns true if anything changed.
     */
    @UnsupportedAppUsage
    private boolean bringTextIntoView() {
        Layout layout = isShowingHint() ? mHintLayout : mLayout;
        int line = 0;
        if ((mGravity & Gravity.VERTICAL_GRAVITY_MASK) == Gravity.BOTTOM) {
            line = layout.getLineCount() - 1;
        }

        Layout.Alignment a = layout.getParagraphAlignment(line);
        int dir = layout.getParagraphDirection(line);
        int hspace = mRight - mLeft - getCompoundPaddingLeft() - getCompoundPaddingRight();
        int vspace = mBottom - mTop - getExtendedPaddingTop() - getExtendedPaddingBottom();
        int ht = layout.getHeight();

        int scrollx, scrolly;

        // Convert to left, center, or right alignment.
        if (a == Layout.Alignment.ALIGN_NORMAL) {
            a = dir == Layout.DIR_LEFT_TO_RIGHT
                    ? Layout.Alignment.ALIGN_LEFT : Layout.Alignment.ALIGN_RIGHT;
        } else if (a == Layout.Alignment.ALIGN_OPPOSITE) {
            a = dir == Layout.DIR_LEFT_TO_RIGHT
                    ? Layout.Alignment.ALIGN_RIGHT : Layout.Alignment.ALIGN_LEFT;
        }

        if (a == Layout.Alignment.ALIGN_CENTER) {
            /*
             * Keep centered if possible, or, if it is too wide to fit,
             * keep leading edge in view.
             */

            int left = (int) Math.floor(layout.getLineLeft(line));
            int right = (int) Math.ceil(layout.getLineRight(line));

            if (right - left < hspace) {
                scrollx = (right + left) / 2 - hspace / 2;
            } else {
                if (dir < 0) {
                    scrollx = right - hspace;
                } else {
                    scrollx = left;
                }
            }
        } else if (a == Layout.Alignment.ALIGN_RIGHT) {
            int right = (int) Math.ceil(layout.getLineRight(line));
            scrollx = right - hspace;
        } else { // a == Layout.Alignment.ALIGN_LEFT (will also be the default)
            scrollx = (int) Math.floor(layout.getLineLeft(line));
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
        return bringPointIntoView(offset, false);
    }

    /**
     * Move the insertion position of the given offset into visible area of the View.
     *
     * If the View is focused or {@code requestRectWithoutFocus} is set to true, this API may call
     * {@link View#requestRectangleOnScreen(Rect)} to bring the point to the visible area if
     * necessary.
     *
     * @param offset an offset of the character.
     * @param requestRectWithoutFocus True for calling {@link View#requestRectangleOnScreen(Rect)}
     *                                in the unfocused state. False for calling it only the View has
     *                                the focus.
     * @return true if anything changed, otherwise false.
     *
     * @see #bringPointIntoView(int)
     */
    public boolean bringPointIntoView(@IntRange(from = 0) int offset,
            boolean requestRectWithoutFocus) {
        if (isLayoutRequested()) {
            mDeferScroll = offset;
            return false;
        }
        final int offsetTransformed =
                originalToTransformed(offset, OffsetMapping.MAP_STRATEGY_CURSOR);
        boolean changed = false;

        Layout layout = isShowingHint() ? mHintLayout : mLayout;

        if (layout == null) return changed;

        int line = layout.getLineForOffset(offsetTransformed);

        int grav;

        switch (layout.getParagraphAlignment(line)) {
            case ALIGN_LEFT:
                grav = 1;
                break;
            case ALIGN_RIGHT:
                grav = -1;
                break;
            case ALIGN_NORMAL:
                grav = layout.getParagraphDirection(line);
                break;
            case ALIGN_OPPOSITE:
                grav = -layout.getParagraphDirection(line);
                break;
            case ALIGN_CENTER:
            default:
                grav = 0;
                break;
        }

        // We only want to clamp the cursor to fit within the layout width
        // in left-to-right modes, because in a right to left alignment,
        // we want to scroll to keep the line-right on the screen, as other
        // lines are likely to have text flush with the right margin, which
        // we want to keep visible.
        // A better long-term solution would probably be to measure both
        // the full line and a blank-trimmed version, and, for example, use
        // the latter measurement for centering and right alignment, but for
        // the time being we only implement the cursor clamping in left to
        // right where it is most likely to be annoying.
        final boolean clamped = grav > 0;
        // FIXME: Is it okay to truncate this, or should we round?
        final int x = (int) layout.getPrimaryHorizontal(offsetTransformed, clamped);
        final int top = layout.getLineTop(line);
        final int bottom = layout.getLineTop(line + 1);

        int left = (int) Math.floor(layout.getLineLeft(line));
        int right = (int) Math.ceil(layout.getLineRight(line));
        int ht = layout.getHeight();

        int hspace = mRight - mLeft - getCompoundPaddingLeft() - getCompoundPaddingRight();
        int vspace = mBottom - mTop - getExtendedPaddingTop() - getExtendedPaddingBottom();
        if (!mHorizontallyScrolling && right - left > hspace && right > x) {
            // If cursor has been clamped, make sure we don't scroll.
            right = Math.max(x, left + hspace);
        }

        int hslack = (bottom - top) / 2;
        int vslack = hslack;

        if (vslack > vspace / 4) {
            vslack = vspace / 4;
        }
        if (hslack > hspace / 4) {
            hslack = hspace / 4;
        }

        int hs = mScrollX;
        int vs = mScrollY;

        if (top - vs < vslack) {
            vs = top - vslack;
        }
        if (bottom - vs > vspace - vslack) {
            vs = bottom - (vspace - vslack);
        }
        if (ht - vs < vspace) {
            vs = ht - vspace;
        }
        if (0 - vs > 0) {
            vs = 0;
        }

        if (grav != 0) {
            if (x - hs < hslack) {
                hs = x - hslack;
            }
            if (x - hs > hspace - hslack) {
                hs = x - (hspace - hslack);
            }
        }

        if (grav < 0) {
            if (left - hs > 0) {
                hs = left;
            }
            if (right - hs < hspace) {
                hs = right - hspace;
            }
        } else if (grav > 0) {
            if (right - hs < hspace) {
                hs = right - hspace;
            }
            if (left - hs > 0) {
                hs = left;
            }
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

        if (requestRectWithoutFocus || isFocused()) {
            // This offsets because getInterestingRect() is in terms of viewport coordinates, but
            // requestRectangleOnScreen() is in terms of content coordinates.

            // The offsets here are to ensure the rectangle we are using is
            // within our view bounds, in case the cursor is on the far left
            // or right.  If it isn't withing the bounds, then this request
            // will be ignored.
            if (mTempRect == null) mTempRect = new Rect();
            mTempRect.set(x - 2, top, x + 2, bottom);
            getInterestingRect(mTempRect, line);
            mTempRect.offset(mScrollX, mScrollY);

            if (requestRectangleOnScreen(mTempRect)) {
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
        int start = getSelectionStartTransformed();
        int end = getSelectionEndTransformed();
        if (start != end) {
            return false;
        }

        // First: make sure the line is visible on screen:

        int line = mLayout.getLineForOffset(start);

        final int top = mLayout.getLineTop(line);
        final int bottom = mLayout.getLineTop(line + 1);
        final int vspace = mBottom - mTop - getExtendedPaddingTop() - getExtendedPaddingBottom();
        int vslack = (bottom - top) / 2;
        if (vslack > vspace / 4) {
            vslack = vspace / 4;
        }
        final int vs = mScrollY;

        if (top < (vs + vslack)) {
            line = mLayout.getLineForVertical(vs + vslack + (bottom - top));
        } else if (bottom > (vspace + vs - vslack)) {
            line = mLayout.getLineForVertical(vspace + vs - vslack - (bottom - top));
        }

        // Next: make sure the character is visible on screen:

        final int hspace = mRight - mLeft - getCompoundPaddingLeft() - getCompoundPaddingRight();
        final int hs = mScrollX;
        final int leftChar = mLayout.getOffsetForHorizontal(line, hs);
        final int rightChar = mLayout.getOffsetForHorizontal(line, hspace + hs);

        // line might contain bidirectional text
        final int lowChar = leftChar < rightChar ? leftChar : rightChar;
        final int highChar = leftChar > rightChar ? leftChar : rightChar;

        int newStart = start;
        if (newStart < lowChar) {
            newStart = lowChar;
        } else if (newStart > highChar) {
            newStart = highChar;
        }

        if (newStart != start) {
            Selection.setSelection(mSpannable,
                    transformedToOriginal(newStart, OffsetMapping.MAP_STRATEGY_CURSOR));
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
                invalidateParentCaches();
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

    private PointF convertFromScreenToContentCoordinates(PointF point) {
        int[] screenToViewport = getLocationOnScreen();
        PointF copy = new PointF(point);
        copy.offset(
                -(screenToViewport[0] + viewportToContentHorizontalOffset()),
                -(screenToViewport[1] + viewportToContentVerticalOffset()));
        return copy;
    }

    private RectF convertFromScreenToContentCoordinates(RectF rect) {
        int[] screenToViewport = getLocationOnScreen();
        RectF copy = new RectF(rect);
        copy.offset(
                -(screenToViewport[0] + viewportToContentHorizontalOffset()),
                -(screenToViewport[1] + viewportToContentVerticalOffset()));
        return copy;
    }

    int viewportToContentHorizontalOffset() {
        return getCompoundPaddingLeft() - mScrollX;
    }

    @UnsupportedAppUsage
    int viewportToContentVerticalOffset() {
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
     * Calculates the rectangles which should be highlighted to indicate a selection between start
     * and end and feeds them into the given {@link Layout.SelectionRectangleConsumer}.
     *
     * @param start    the starting index of the selection
     * @param end      the ending index of the selection
     * @param consumer the {@link Layout.SelectionRectangleConsumer} which will receive the
     *                 generated rectangles. It will be called every time a rectangle is generated.
     * @hide
     */
    public void getSelection(int start, int end, final Layout.SelectionRectangleConsumer consumer) {
        final int transformedStart =
                originalToTransformed(start, OffsetMapping.MAP_STRATEGY_CURSOR);
        final int transformedEnd = originalToTransformed(end, OffsetMapping.MAP_STRATEGY_CURSOR);
        mLayout.getSelection(transformedStart, transformedEnd, consumer);
    }

    int getSelectionStartTransformed() {
        final int start = getSelectionStart();
        if (start < 0) return start;
        return originalToTransformed(start, OffsetMapping.MAP_STRATEGY_CURSOR);
    }

    int getSelectionEndTransformed() {
        final int end = getSelectionEnd();
        if (end < 0) return end;
        return originalToTransformed(end, OffsetMapping.MAP_STRATEGY_CURSOR);
    }

    /**
     * Return true iff there is a selection of nonzero length inside this text view.
     */
    public boolean hasSelection() {
        final int selectionStart = getSelectionStart();
        final int selectionEnd = getSelectionEnd();
        final int selectionMin;
        final int selectionMax;
        if (selectionStart < selectionEnd) {
            selectionMin = selectionStart;
            selectionMax = selectionEnd;
        } else {
            selectionMin = selectionEnd;
            selectionMax = selectionStart;
        }

        return selectionMin >= 0 && selectionMax > 0 && selectionMin != selectionMax;
    }

    String getSelectedText() {
        if (!hasSelection()) {
            return null;
        }

        final int start = getSelectionStart();
        final int end = getSelectionEnd();
        return String.valueOf(
                start > end ? mText.subSequence(end, start) : mText.subSequence(start, end));
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
     * Sets the properties of this field to transform input to ALL CAPS
     * display. This may use a "small caps" formatting if available.
     * This setting will be ignored if this field is editable or selectable.
     *
     * This call replaces the current transformation method. Disabling this
     * will not necessarily restore the previous behavior from before this
     * was enabled.
     *
     * @see #setTransformationMethod(TransformationMethod)
     * @attr ref android.R.styleable#TextView_textAllCaps
     */
    @android.view.RemotableViewMethod
    public void setAllCaps(boolean allCaps) {
        if (allCaps) {
            setTransformationMethod(new AllCapsTransformationMethod(getContext()));
        } else {
            setTransformationMethod(null);
        }
    }

    /**
     *
     * Checks whether the transformation method applied to this TextView is set to ALL CAPS.
     * @return Whether the current transformation method is for ALL CAPS.
     *
     * @see #setAllCaps(boolean)
     * @see #setTransformationMethod(TransformationMethod)
     */
    @InspectableProperty(name = "textAllCaps")
    public boolean isAllCaps() {
        final TransformationMethod method = getTransformationMethod();
        return method != null && method instanceof AllCapsTransformationMethod;
    }

    /**
     * If true, sets the properties of this field (number of lines, horizontally scrolling,
     * transformation method) to be for a single-line input; if false, restores these to the default
     * conditions.
     *
     * Note that the default conditions are not necessarily those that were in effect prior this
     * method, and you may want to reset these properties to your custom values.
     *
     * Note that due to performance reasons, by setting single line for the EditText, the maximum
     * text length is set to 5000 if no other character limitation are applied.
     *
     * @attr ref android.R.styleable#TextView_singleLine
     */
    @android.view.RemotableViewMethod
    public void setSingleLine(boolean singleLine) {
        // Could be used, but may break backward compatibility.
        // if (mSingleLine == singleLine) return;
        setInputTypeSingleLine(singleLine);
        applySingleLine(singleLine, true, true, true);
    }

    /**
     * Adds or remove the EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE on the mInputType.
     * @param singleLine
     */
    private void setInputTypeSingleLine(boolean singleLine) {
        if (mEditor != null
                && (mEditor.mInputType & EditorInfo.TYPE_MASK_CLASS)
                        == EditorInfo.TYPE_CLASS_TEXT) {
            if (singleLine) {
                mEditor.mInputType &= ~EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE;
            } else {
                mEditor.mInputType |= EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE;
            }
        }
    }

    private void applySingleLine(boolean singleLine, boolean applyTransformation,
            boolean changeMaxLines, boolean changeMaxLength) {
        mSingleLine = singleLine;

        if (singleLine) {
            setLines(1);
            setHorizontallyScrolling(true);
            if (applyTransformation) {
                setTransformationMethod(SingleLineTransformationMethod.getInstance());
            }

            if (!changeMaxLength) return;

            // Single line length filter is only applicable editable text.
            if (mBufferType != BufferType.EDITABLE) return;

            final InputFilter[] prevFilters = getFilters();
            for (InputFilter filter: getFilters()) {
                // We don't add LengthFilter if already there.
                if (filter instanceof InputFilter.LengthFilter) return;
            }

            if (mSingleLineLengthFilter == null) {
                mSingleLineLengthFilter = new InputFilter.LengthFilter(
                    MAX_LENGTH_FOR_SINGLE_LINE_EDIT_TEXT);
            }

            final InputFilter[] newFilters = new InputFilter[prevFilters.length + 1];
            System.arraycopy(prevFilters, 0, newFilters, 0, prevFilters.length);
            newFilters[prevFilters.length] = mSingleLineLengthFilter;

            setFilters(newFilters);

            // Since filter doesn't apply to existing text, trigger filter by setting text.
            setText(getText());
        } else {
            if (changeMaxLines) {
                setMaxLines(Integer.MAX_VALUE);
            }
            setHorizontallyScrolling(false);
            if (applyTransformation) {
                setTransformationMethod(null);
            }

            if (!changeMaxLength) return;

            // Single line length filter is only applicable editable text.
            if (mBufferType != BufferType.EDITABLE) return;

            final InputFilter[] prevFilters = getFilters();
            if (prevFilters.length == 0) return;

            // Short Circuit: if mSingleLineLengthFilter is not allocated, nobody sets automated
            // single line char limit filter.
            if (mSingleLineLengthFilter == null) return;

            // If we need to remove mSingleLineLengthFilter, we need to allocate another array.
            // Since filter list is expected to be small and want to avoid unnecessary array
            // allocation, check if there is mSingleLengthFilter first.
            int targetIndex = -1;
            for (int i = 0; i < prevFilters.length; ++i) {
                if (prevFilters[i] == mSingleLineLengthFilter) {
                    targetIndex = i;
                    break;
                }
            }
            if (targetIndex == -1) return;  // not found. Do nothing.

            if (prevFilters.length == 1) {
                setFilters(NO_FILTERS);
                return;
            }

            // Create new array which doesn't include mSingleLengthFilter.
            final InputFilter[] newFilters = new InputFilter[prevFilters.length - 1];
            System.arraycopy(prevFilters, 0, newFilters, 0, targetIndex);
            System.arraycopy(
                    prevFilters,
                    targetIndex + 1,
                    newFilters,
                    targetIndex,
                    prevFilters.length - targetIndex - 1);
            setFilters(newFilters);
            mSingleLineLengthFilter = null;
        }
    }

    /**
     * Causes words in the text that are longer than the view's width
     * to be ellipsized instead of broken in the middle.  You may also
     * want to {@link #setSingleLine} or {@link #setHorizontallyScrolling}
     * to constrain the text to a single line.  Use <code>null</code>
     * to turn off ellipsizing.
     *
     * If {@link #setMaxLines} has been used to set two or more lines,
     * only {@link android.text.TextUtils.TruncateAt#END} and
     * {@link android.text.TextUtils.TruncateAt#MARQUEE} are supported
     * (other ellipsizing types will not do anything).
     *
     * @attr ref android.R.styleable#TextView_ellipsize
     */
    public void setEllipsize(TextUtils.TruncateAt where) {
        // TruncateAt is an enum. != comparison is ok between these singleton objects.
        if (mEllipsize != where) {
            mEllipsize = where;

            if (mLayout != null) {
                nullLayouts();
                requestLayout();
                invalidate();
            }
        }
    }

    /**
     * Sets how many times to repeat the marquee animation. Only applied if the
     * TextView has marquee enabled. Set to -1 to repeat indefinitely.
     *
     * @see #getMarqueeRepeatLimit()
     *
     * @attr ref android.R.styleable#TextView_marqueeRepeatLimit
     */
    public void setMarqueeRepeatLimit(int marqueeLimit) {
        mMarqueeRepeatLimit = marqueeLimit;
    }

    /**
     * Gets the number of times the marquee animation is repeated. Only meaningful if the
     * TextView has marquee enabled.
     *
     * @return the number of times the marquee animation is repeated. -1 if the animation
     * repeats indefinitely
     *
     * @see #setMarqueeRepeatLimit(int)
     *
     * @attr ref android.R.styleable#TextView_marqueeRepeatLimit
     */
    @InspectableProperty
    public int getMarqueeRepeatLimit() {
        return mMarqueeRepeatLimit;
    }

    /**
     * Returns where, if anywhere, words that are longer than the view
     * is wide should be ellipsized.
     */
    @InspectableProperty
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
        createEditorIfNeeded();
        mEditor.mSelectAllOnFocus = selectAllOnFocus;

        if (selectAllOnFocus && !(mText instanceof Spannable)) {
            setText(mText, BufferType.SPANNABLE);
        }
    }

    /**
     * Set whether the cursor is visible. The default is true. Note that this property only
     * makes sense for editable TextView. If IME is consuming the input, the cursor will always be
     * invisible, visibility will be updated as the last state when IME does not consume
     * the input anymore.
     *
     * @see #isCursorVisible()
     *
     * @attr ref android.R.styleable#TextView_cursorVisible
     */
    @android.view.RemotableViewMethod
    public void setCursorVisible(boolean visible) {
        mCursorVisibleFromAttr = visible;
        updateCursorVisibleInternal();
    }

    /**
     * Sets the IME is consuming the input and make the cursor invisible if {@code imeConsumesInput}
     * is {@code true}. Otherwise, make the cursor visible.
     *
     * @param imeConsumesInput {@code true} if IME is consuming the input
     *
     * @hide
     */
    public void setImeConsumesInput(boolean imeConsumesInput) {
        mImeIsConsumingInput = imeConsumesInput;
        updateCursorVisibleInternal();
    }

    private void updateCursorVisibleInternal()  {
        boolean visible = mCursorVisibleFromAttr && !mImeIsConsumingInput;
        if (visible && mEditor == null) return; // visible is the default value with no edit data
        createEditorIfNeeded();
        if (mEditor.mCursorVisible != visible) {
            mEditor.mCursorVisible = visible;
            invalidate();

            mEditor.makeBlink();

            // InsertionPointCursorController depends on mCursorVisible
            mEditor.prepareCursorControllers();
        }
    }

    /**
     * @return whether or not the cursor is visible (assuming this TextView is editable). This
     * method may return {@code false} when the IME is consuming the input even if the
     * {@code mEditor.mCursorVisible} attribute is {@code true} or {@code #setCursorVisible(true)}
     * is called.
     *
     * @see #setCursorVisible(boolean)
     *
     * @attr ref android.R.styleable#TextView_cursorVisible
     */
    @InspectableProperty
    public boolean isCursorVisible() {
        // true is the default value
        return mEditor == null ? true : mEditor.mCursorVisible;
    }

    /**
     * @return whether cursor is visible without regard to {@code mImeIsConsumingInput}.
     * {@code true} is the default value.
     *
     * @see #setCursorVisible(boolean)
     * @hide
     */
    public boolean isCursorVisibleFromAttr() {
        return mCursorVisibleFromAttr;
    }

    private boolean canMarquee() {
        int width = mRight - mLeft - getCompoundPaddingLeft() - getCompoundPaddingRight();
        return width > 0 && (mLayout.getLineWidth(0) > width
                || (mMarqueeFadeMode != MARQUEE_FADE_NORMAL && mSavedMarqueeModeLayout != null
                        && mSavedMarqueeModeLayout.getLineWidth(0) > width));
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    protected void startMarquee() {
        // Do not ellipsize EditText
        if (getKeyListener() != null) return;

        if (compressText(getWidth() - getCompoundPaddingLeft() - getCompoundPaddingRight())) {
            return;
        }

        if ((mMarquee == null || mMarquee.isStopped()) && isAggregatedVisible()
                && (isFocused() || isSelected()) && getLineCount() == 1 && canMarquee()) {

            if (mMarqueeFadeMode == MARQUEE_FADE_SWITCH_SHOW_ELLIPSIS) {
                mMarqueeFadeMode = MARQUEE_FADE_SWITCH_SHOW_FADE;
                final Layout tmp = mLayout;
                mLayout = mSavedMarqueeModeLayout;
                mSavedMarqueeModeLayout = tmp;
                setHorizontalFadingEdgeEnabled(true);
                requestLayout();
                invalidate();
            }

            if (mMarquee == null) mMarquee = new Marquee(this);
            mMarquee.start(mMarqueeRepeatLimit);
        }
    }

    /**
     * @hide
     */
    protected void stopMarquee() {
        if (mMarquee != null && !mMarquee.isStopped()) {
            mMarquee.stop();
        }

        if (mMarqueeFadeMode == MARQUEE_FADE_SWITCH_SHOW_FADE) {
            mMarqueeFadeMode = MARQUEE_FADE_SWITCH_SHOW_ELLIPSIS;
            final Layout tmp = mSavedMarqueeModeLayout;
            mSavedMarqueeModeLayout = mLayout;
            mLayout = tmp;
            setHorizontalFadingEdgeEnabled(false);
            requestLayout();
            invalidate();
        }
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private void startStopMarquee(boolean start) {
        if (mEllipsize == TextUtils.TruncateAt.MARQUEE) {
            if (start) {
                startMarquee();
            } else {
                stopMarquee();
            }
        }
    }

    /**
     * This method is called when the text is changed, in case any subclasses
     * would like to know.
     *
     * Within <code>text</code>, the <code>lengthAfter</code> characters
     * beginning at <code>start</code> have just replaced old text that had
     * length <code>lengthBefore</code>. It is an error to attempt to make
     * changes to <code>text</code> from this callback.
     *
     * @param text The text the TextView is displaying
     * @param start The offset of the start of the range of the text that was
     * modified
     * @param lengthBefore The length of the former text that has been replaced
     * @param lengthAfter The length of the replacement modified text
     */
    protected void onTextChanged(CharSequence text, int start, int lengthBefore, int lengthAfter) {
        // intentionally empty, template pattern method can be overridden by subclasses
    }

    /**
     * This method is called when the selection has changed, in case any
     * subclasses would like to know.
     * </p>
     * <p class="note"><strong>Note:</strong> Always call the super implementation, which informs
     * the accessibility subsystem about the selection change.
     * </p>
     *
     * @param selStart The new selection start location.
     * @param selEnd The new selection end location.
     */
    @CallSuper
    protected void onSelectionChanged(int selStart, int selEnd) {
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED);
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

    private void sendBeforeTextChanged(CharSequence text, int start, int before, int after) {
        if (mListeners != null) {
            final ArrayList<TextWatcher> list = mListeners;
            final int count = list.size();
            for (int i = 0; i < count; i++) {
                list.get(i).beforeTextChanged(text, start, before, after);
            }
        }

        // The spans that are inside or intersect the modified region no longer make sense
        removeIntersectingNonAdjacentSpans(start, start + before, SpellCheckSpan.class);
        removeIntersectingNonAdjacentSpans(start, start + before, SuggestionSpan.class);
    }

    // Removes all spans that are inside or actually overlap the start..end range
    private <T> void removeIntersectingNonAdjacentSpans(int start, int end, Class<T> type) {
        if (!(mText instanceof Editable)) return;
        Editable text = (Editable) mText;

        T[] spans = text.getSpans(start, end, type);
        ArrayList<T> spansToRemove = new ArrayList<>();
        for (T span : spans) {
            final int spanStart = text.getSpanStart(span);
            final int spanEnd = text.getSpanEnd(span);
            if (spanEnd == start || spanStart == end) continue;
            spansToRemove.add(span);
        }
        for (T span : spansToRemove) {
            text.removeSpan(span);
        }
    }

    void removeAdjacentSuggestionSpans(final int pos) {
        if (!(mText instanceof Editable)) return;
        final Editable text = (Editable) mText;

        final SuggestionSpan[] spans = text.getSpans(pos, pos, SuggestionSpan.class);
        final int length = spans.length;
        for (int i = 0; i < length; i++) {
            final int spanStart = text.getSpanStart(spans[i]);
            final int spanEnd = text.getSpanEnd(spans[i]);
            if (spanEnd == pos || spanStart == pos) {
                if (SpellChecker.haveWordBoundariesChanged(text, pos, pos, spanStart, spanEnd)) {
                    text.removeSpan(spans[i]);
                }
            }
        }
    }

    /**
     * Not private so it can be called from an inner class without going
     * through a thunk.
     */
    void sendOnTextChanged(CharSequence text, int start, int before, int after) {
        if (mListeners != null) {
            final ArrayList<TextWatcher> list = mListeners;
            final int count = list.size();
            for (int i = 0; i < count; i++) {
                list.get(i).onTextChanged(text, start, before, after);
            }
        }

        if (mEditor != null) mEditor.sendOnTextChanged(start, before, after);
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

        notifyListeningManagersAfterTextChanged();

        hideErrorIfUnchanged();
    }

    /**
     * Notify managers (such as {@link AutofillManager} and {@link ContentCaptureManager}) that are
     * interested on text changes.
     */
    private void notifyListeningManagersAfterTextChanged() {

        // Autofill
        if (isAutofillable()) {
            // It is important to not check whether the view is important for autofill
            // since the user can trigger autofill manually on not important views.
            final AutofillManager afm = mContext.getSystemService(AutofillManager.class);
            if (afm != null) {
                if (android.view.autofill.Helper.sVerbose) {
                    Log.v(LOG_TAG, "notifyAutoFillManagerAfterTextChanged");
                }
                afm.notifyValueChanged(TextView.this);
            }
        }

        notifyContentCaptureTextChanged();
    }

    /**
     * Notifies the ContentCapture service that the text of the view has changed (only if
     * ContentCapture has been notified of this view's existence already).
     *
     * @hide
     */
    public void notifyContentCaptureTextChanged() {
        // TODO(b/121045053): should use a flag / boolean to keep status of SHOWN / HIDDEN instead
        // of using isLaidout(), so it's not called in cases where it's laid out but a
        // notifyAppeared was not sent.
        if (isLaidOut() && isImportantForContentCapture() && getNotifiedContentCaptureAppeared()) {
            final ContentCaptureManager cm = mContext.getSystemService(ContentCaptureManager.class);
            if (cm != null && cm.isContentCaptureEnabled()) {
                final ContentCaptureSession session = getContentCaptureSession();
                if (session != null) {
                    // TODO(b/111276913): pass flags when edited by user / add CTS test
                    session.notifyViewTextChanged(getAutofillId(), getText());
                }
            }
        }
    }

    private boolean isAutofillable() {
        // It is important to not check whether the view is important for autofill
        // since the user can trigger autofill manually on not important views.
        return getAutofillType() != AUTOFILL_TYPE_NONE;
    }

    void updateAfterEdit() {
        invalidate();
        int curs = getSelectionStart();

        if (curs >= 0 || (mGravity & Gravity.VERTICAL_GRAVITY_MASK) == Gravity.BOTTOM) {
            registerForPreDraw();
        }

        checkForResize();

        if (curs >= 0) {
            mHighlightPathBogus = true;
            if (mEditor != null) mEditor.makeBlink();
            bringPointIntoView(curs);
        }
    }

    /**
     * Not private so it can be called from an inner class without going
     * through a thunk.
     */
    void handleTextChanged(CharSequence buffer, int start, int before, int after) {
        sLastCutCopyOrTextChangedTime = 0;

        final Editor.InputMethodState ims = mEditor == null ? null : mEditor.mInputMethodState;
        if (ims == null || ims.mBatchEditNesting == 0) {
            updateAfterEdit();
        }
        if (ims != null) {
            ims.mContentChanged = true;
            if (ims.mChangedStart < 0) {
                ims.mChangedStart = start;
                ims.mChangedEnd = start + before;
            } else {
                ims.mChangedStart = Math.min(ims.mChangedStart, start);
                ims.mChangedEnd = Math.max(ims.mChangedEnd, start + before - ims.mChangedDelta);
            }
            ims.mChangedDelta += after - before;
        }
        resetErrorChangedFlag();
        sendOnTextChanged(buffer, start, before, after);
        onTextChanged(buffer, start, before, after);

        mHideHint = false;
        clearGesturePreviewHighlight();
    }

    /**
     * Not private so it can be called from an inner class without going
     * through a thunk.
     */
    void spanChange(Spanned buf, Object what, int oldStart, int newStart, int oldEnd, int newEnd) {
        // XXX Make the start and end move together if this ends up
        // spending too much time invalidating.

        boolean selChanged = false;
        int newSelStart = -1, newSelEnd = -1;

        final Editor.InputMethodState ims = mEditor == null ? null : mEditor.mInputMethodState;

        if (what == Selection.SELECTION_END) {
            selChanged = true;
            newSelEnd = newStart;

            if (oldStart >= 0 || newStart >= 0) {
                invalidateCursor(Selection.getSelectionStart(buf), oldStart, newStart);
                checkForResize();
                registerForPreDraw();
                if (mEditor != null) mEditor.makeBlink();
            }
        }

        if (what == Selection.SELECTION_START) {
            selChanged = true;
            newSelStart = newStart;

            if (oldStart >= 0 || newStart >= 0) {
                int end = Selection.getSelectionEnd(buf);
                invalidateCursor(end, oldStart, newStart);
            }
        }

        if (selChanged) {
            clearGesturePreviewHighlight();
            mHighlightPathBogus = true;
            if (mEditor != null && !isFocused()) mEditor.mSelectionMoved = true;

            if ((buf.getSpanFlags(what) & Spanned.SPAN_INTERMEDIATE) == 0) {
                if (newSelStart < 0) {
                    newSelStart = Selection.getSelectionStart(buf);
                }
                if (newSelEnd < 0) {
                    newSelEnd = Selection.getSelectionEnd(buf);
                }

                if (mEditor != null) {
                    mEditor.refreshTextActionMode();
                    if (!hasSelection()
                            && mEditor.getTextActionMode() == null && hasTransientState()) {
                        // User generated selection has been removed.
                        setHasTransientState(false);
                    }
                }
                onSelectionChanged(newSelStart, newSelEnd);
            }
        }

        if (what instanceof UpdateAppearance || what instanceof ParagraphStyle
                || what instanceof CharacterStyle) {
            if (ims == null || ims.mBatchEditNesting == 0) {
                invalidate();
                mHighlightPathBogus = true;
                checkForResize();
            } else {
                ims.mContentChanged = true;
            }
            if (mEditor != null) {
                if (oldStart >= 0) mEditor.invalidateTextDisplayList(mLayout, oldStart, oldEnd);
                if (newStart >= 0) mEditor.invalidateTextDisplayList(mLayout, newStart, newEnd);
                mEditor.invalidateHandlesAndActionMode();
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
            if (ims != null && ims.mExtractedTextRequest != null) {
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
                    if (DEBUG_EXTRACT) {
                        Log.v(LOG_TAG, "Span change outside of batch: "
                                + oldStart + "-" + oldEnd + ","
                                + newStart + "-" + newEnd + " " + what);
                    }
                    ims.mContentChanged = true;
                }
            }
        }

        if (mEditor != null && mEditor.mSpellChecker != null && newStart < 0
                && what instanceof SpellCheckSpan) {
            mEditor.mSpellChecker.onSpellCheckSpanRemoved((SpellCheckSpan) what);
        }
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previouslyFocusedRect) {
        if (isTemporarilyDetached()) {
            // If we are temporarily in the detach state, then do nothing.
            super.onFocusChanged(focused, direction, previouslyFocusedRect);
            return;
        }

        mHideHint = false;

        if (mEditor != null) mEditor.onFocusChanged(focused, direction);

        if (focused) {
            if (mSpannable != null) {
                MetaKeyKeyListener.resetMetaState(mSpannable);
            }
        }

        startStopMarquee(focused);

        if (mTransformation != null) {
            mTransformation.onFocusChanged(this, mText, focused, direction, previouslyFocusedRect);
        }

        super.onFocusChanged(focused, direction, previouslyFocusedRect);
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);

        if (mEditor != null) mEditor.onWindowFocusChanged(hasWindowFocus);

        startStopMarquee(hasWindowFocus);
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (mEditor != null && visibility != VISIBLE) {
            mEditor.hideCursorAndSpanControllers();
            stopTextActionMode();
        }
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        startStopMarquee(isVisible);
    }

    /**
     * Use {@link BaseInputConnection#removeComposingSpans
     * BaseInputConnection.removeComposingSpans()} to remove any IME composing
     * state from this text view.
     */
    public void clearComposingText() {
        if (mText instanceof Spannable) {
            BaseInputConnection.removeComposingSpans(mSpannable);
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

    /**
     * Called from onTouchEvent() to prevent the touches by secondary fingers.
     * Dragging on handles can revise cursor/selection, so can dragging on the text view.
     * This method is a lock to avoid processing multiple fingers on both text view and handles.
     * Note: multiple fingers on handles (e.g. 2 fingers on the 2 selection handles) should work.
     *
     * @param event The motion event that is being handled and carries the pointer info.
     * @param fromHandleView true if the event is delivered to selection handle or insertion
     * handle; false if this event is delivered to TextView.
     * @return Returns true to indicate that onTouchEvent() can continue processing the motion
     * event, otherwise false.
     *  - Always returns true for the first finger.
     *  - For secondary fingers, if the first or current finger is from TextView, returns false.
     *    This is to make touch mutually exclusive between the TextView and the handles, but
     *    not among the handles.
     */
    boolean isFromPrimePointer(MotionEvent event, boolean fromHandleView) {
        boolean res = true;
        if (mPrimePointerId == NO_POINTER_ID)  {
            mPrimePointerId = event.getPointerId(0);
            mIsPrimePointerFromHandleView = fromHandleView;
        } else if (mPrimePointerId != event.getPointerId(0)) {
            res = mIsPrimePointerFromHandleView && fromHandleView;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP
            || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            mPrimePointerId = -1;
        }
        return res;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (DEBUG_CURSOR) {
            logCursor("onTouchEvent", "%d: %s (%f,%f)",
                    event.getSequenceNumber(),
                    MotionEvent.actionToString(event.getActionMasked()),
                    event.getX(), event.getY());
        }
        mLastInputSource = event.getSource();
        final int action = event.getActionMasked();
        if (mEditor != null) {
            if (!isFromPrimePointer(event, false)) {
                return true;
            }

            mEditor.onTouchEvent(event);

            if (mEditor.mInsertionPointCursorController != null
                    && mEditor.mInsertionPointCursorController.isCursorBeingModified()) {
                return true;
            }
            if (mEditor.mSelectionModifierCursorController != null
                    && mEditor.mSelectionModifierCursorController.isDragAcceleratorActive()) {
                return true;
            }
        }

        final boolean superResult = super.onTouchEvent(event);
        if (DEBUG_CURSOR) {
            logCursor("onTouchEvent", "superResult=%s", superResult);
        }

        /*
         * Don't handle the release after a long press, because it will move the selection away from
         * whatever the menu action was trying to affect. If the long press should have triggered an
         * insertion action mode, we can now actually show it.
         */
        if (mEditor != null && mEditor.mDiscardNextActionUp && action == MotionEvent.ACTION_UP) {
            mEditor.mDiscardNextActionUp = false;
            if (DEBUG_CURSOR) {
                logCursor("onTouchEvent", "release after long press detected");
            }
            if (mEditor.mIsInsertionActionModeStartPending) {
                mEditor.startInsertionActionMode();
                mEditor.mIsInsertionActionModeStartPending = false;
            }
            return superResult;
        }

        final boolean touchIsFinished = (action == MotionEvent.ACTION_UP)
                && (mEditor == null || !mEditor.mIgnoreActionUpEvent) && isFocused();

        if ((mMovement != null || onCheckIsTextEditor()) && isEnabled()
                && mText instanceof Spannable && mLayout != null) {
            boolean handled = false;

            if (mMovement != null) {
                handled |= mMovement.onTouchEvent(this, mSpannable, event);
            }

            final boolean textIsSelectable = isTextSelectable();
            if (touchIsFinished && mLinksClickable && mAutoLinkMask != 0 && textIsSelectable) {
                // The LinkMovementMethod which should handle taps on links has not been installed
                // on non editable text that support text selection.
                // We reproduce its behavior here to open links for these.
                ClickableSpan[] links = mSpannable.getSpans(getSelectionStart(),
                    getSelectionEnd(), ClickableSpan.class);

                if (links.length > 0) {
                    links[0].onClick(this);
                    handled = true;
                }
            }

            if (touchIsFinished && (isTextEditable() || textIsSelectable)) {
                // Show the IME, except when selecting in read-only text.
                final InputMethodManager imm = getInputMethodManager();
                viewClicked(imm);
                if (isTextEditable() && mEditor.mShowSoftInputOnFocus && imm != null
                        && !showAutofillDialog()) {
                    imm.showSoftInput(this, 0);
                }

                // The above condition ensures that the mEditor is not null
                mEditor.onTouchUpEvent(event);

                handled = true;
            }

            if (handled) {
                return true;
            }
        }

        return superResult;
    }

    /**
     * Returns true when need to show UIs, e.g. floating toolbar, etc, for finger based interaction.
     *
     * @return true if UIs need to show for finger interaciton. false if UIs are not necessary.
     * @hide
     */
    public final boolean showUIForTouchScreen() {
        return (mLastInputSource & InputDevice.SOURCE_TOUCHSCREEN)
                == InputDevice.SOURCE_TOUCHSCREEN;
    }

    /**
     * The fill dialog UI is a more conspicuous and efficient interface than dropdown UI.
     * If autofill suggestions are available when the user clicks on a field that supports filling
     * the dialog UI, Autofill will pop up a fill dialog. The dialog will take up a larger area
     * to display the datasets, so it is easy for users to pay attention to the datasets and
     * selecting a dataset. The autofill dialog is shown as the bottom sheet, the better
     * experience is not to show the IME if there is a fill dialog.
     */
    private boolean showAutofillDialog() {
        final AutofillManager afm = mContext.getSystemService(AutofillManager.class);
        if (afm != null) {
            return afm.showAutofillDialog(this);
        }
        return false;
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (mMovement != null && mText instanceof Spannable && mLayout != null) {
            try {
                if (mMovement.onGenericMotionEvent(this, mSpannable, event)) {
                    return true;
                }
            } catch (AbstractMethodError ex) {
                // onGenericMotionEvent was added to the MovementMethod interface in API 12.
                // Ignore its absence in case third party applications implemented the
                // interface directly.
            }
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    protected void onCreateContextMenu(ContextMenu menu) {
        if (mEditor != null) {
            mEditor.onCreateContextMenu(menu);
        }
    }

    @Override
    public boolean showContextMenu() {
        if (mEditor != null) {
            mEditor.setContextMenuAnchor(Float.NaN, Float.NaN);
        }
        return super.showContextMenu();
    }

    @Override
    public boolean showContextMenu(float x, float y) {
        if (mEditor != null) {
            mEditor.setContextMenuAnchor(x, y);
        }
        return super.showContextMenu(x, y);
    }

    /**
     * @return True iff this TextView contains a text that can be edited, or if this is
     * a selectable TextView.
     */
    @UnsupportedAppUsage
    boolean isTextEditable() {
        return mText instanceof Editable && onCheckIsTextEditor() && isEnabled();
    }

    /**
     * Returns true, only while processing a touch gesture, if the initial
     * touch down event caused focus to move to the text view and as a result
     * its selection changed.  Only valid while processing the touch gesture
     * of interest, in an editable text view.
     */
    public boolean didTouchFocusSelect() {
        return mEditor != null && mEditor.mTouchFocusSelected;
    }

    @Override
    public void cancelLongPress() {
        super.cancelLongPress();
        if (mEditor != null) mEditor.mIgnoreActionUpEvent = true;
    }

    @Override
    public boolean onTrackballEvent(MotionEvent event) {
        if (mMovement != null && mSpannable != null && mLayout != null) {
            if (mMovement.onTrackballEvent(this, mSpannable, event)) {
                return true;
            }
        }

        return super.onTrackballEvent(event);
    }

    /**
     * Sets the Scroller used for producing a scrolling animation
     *
     * @param s A Scroller instance
     */
    public void setScroller(Scroller s) {
        mScroller = s;
    }

    @Override
    protected float getLeftFadingEdgeStrength() {
        if (isMarqueeFadeEnabled() && mMarquee != null && !mMarquee.isStopped()) {
            final Marquee marquee = mMarquee;
            if (marquee.shouldDrawLeftFade()) {
                return getHorizontalFadingEdgeStrength(marquee.getScroll(), 0.0f);
            } else {
                return 0.0f;
            }
        } else if (getLineCount() == 1) {
            final float lineLeft = getLayout().getLineLeft(0);
            if (lineLeft > mScrollX) return 0.0f;
            return getHorizontalFadingEdgeStrength(mScrollX, lineLeft);
        }
        return super.getLeftFadingEdgeStrength();
    }

    @Override
    protected float getRightFadingEdgeStrength() {
        if (isMarqueeFadeEnabled() && mMarquee != null && !mMarquee.isStopped()) {
            final Marquee marquee = mMarquee;
            return getHorizontalFadingEdgeStrength(marquee.getMaxFadeScroll(), marquee.getScroll());
        } else if (getLineCount() == 1) {
            final float rightEdge = mScrollX +
                    (getWidth() - getCompoundPaddingLeft() - getCompoundPaddingRight());
            final float lineRight = getLayout().getLineRight(0);
            if (lineRight < rightEdge) return 0.0f;
            return getHorizontalFadingEdgeStrength(rightEdge, lineRight);
        }
        return super.getRightFadingEdgeStrength();
    }

    /**
     * Calculates the fading edge strength as the ratio of the distance between two
     * horizontal positions to {@link View#getHorizontalFadingEdgeLength()}. Uses the absolute
     * value for the distance calculation.
     *
     * @param position1 A horizontal position.
     * @param position2 A horizontal position.
     * @return Fading edge strength between [0.0f, 1.0f].
     */
    @FloatRange(from = 0.0, to = 1.0)
    private float getHorizontalFadingEdgeStrength(float position1, float position2) {
        final int horizontalFadingEdgeLength = getHorizontalFadingEdgeLength();
        if (horizontalFadingEdgeLength == 0) return 0.0f;
        final float diff = Math.abs(position1 - position2);
        if (diff > horizontalFadingEdgeLength) return 1.0f;
        return diff / horizontalFadingEdgeLength;
    }

    private boolean isMarqueeFadeEnabled() {
        return mEllipsize == TextUtils.TruncateAt.MARQUEE
                && mMarqueeFadeMode != MARQUEE_FADE_SWITCH_SHOW_ELLIPSIS;
    }

    @Override
    protected int computeHorizontalScrollRange() {
        if (mLayout != null) {
            return mSingleLine && (mGravity & Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.LEFT
                    ? (int) mLayout.getLineWidth(0) : mLayout.getWidth();
        }

        return super.computeHorizontalScrollRange();
    }

    @Override
    protected int computeVerticalScrollRange() {
        if (mLayout != null) {
            return mLayout.getHeight();
        }
        return super.computeVerticalScrollRange();
    }

    @Override
    protected int computeVerticalScrollExtent() {
        return getHeight() - getCompoundPaddingTop() - getCompoundPaddingBottom();
    }

    @Override
    public void findViewsWithText(ArrayList<View> outViews, CharSequence searched, int flags) {
        super.findViewsWithText(outViews, searched, flags);
        if (!outViews.contains(this) && (flags & FIND_VIEWS_WITH_TEXT) != 0
                && !TextUtils.isEmpty(searched) && !TextUtils.isEmpty(mText)) {
            String searchedLowerCase = searched.toString().toLowerCase();
            String textLowerCase = mText.toString().toLowerCase();
            if (textLowerCase.contains(searchedLowerCase)) {
                outViews.add(this);
            }
        }
    }

    /**
     * Type of the text buffer that defines the characteristics of the text such as static,
     * styleable, or editable.
     */
    public enum BufferType {
        NORMAL, SPANNABLE, EDITABLE
    }

    /**
     * Returns the TextView_textColor attribute from the TypedArray, if set, or
     * the TextAppearance_textColor from the TextView_textAppearance attribute,
     * if TextView_textColor was not set directly.
     *
     * @removed
     */
    public static ColorStateList getTextColors(Context context, TypedArray attrs) {
        if (attrs == null) {
            // Preserve behavior prior to removal of this API.
            throw new NullPointerException();
        }

        // It's not safe to use this method from apps. The parameter 'attrs'
        // must have been obtained using the TextView filter array which is not
        // available to the SDK. As such, we grab a default TypedArray with the
        // right filter instead here.
        final TypedArray a = context.obtainStyledAttributes(R.styleable.TextView);
        ColorStateList colors = a.getColorStateList(R.styleable.TextView_textColor);
        if (colors == null) {
            final int ap = a.getResourceId(R.styleable.TextView_textAppearance, 0);
            if (ap != 0) {
                final TypedArray appearance = context.obtainStyledAttributes(
                        ap, R.styleable.TextAppearance);
                colors = appearance.getColorStateList(R.styleable.TextAppearance_textColor);
                appearance.recycle();
            }
        }
        a.recycle();

        return colors;
    }

    /**
     * Returns the default color from the TextView_textColor attribute from the
     * AttributeSet, if set, or the default color from the
     * TextAppearance_textColor from the TextView_textAppearance attribute, if
     * TextView_textColor was not set directly.
     *
     * @removed
     */
    public static int getTextColor(Context context, TypedArray attrs, int def) {
        final ColorStateList colors = getTextColors(context, attrs);
        if (colors == null) {
            return def;
        } else {
            return colors.getDefaultColor();
        }
    }

    @Override
    public boolean onKeyShortcut(int keyCode, KeyEvent event) {
        if (event.hasModifiers(KeyEvent.META_CTRL_ON)) {
            // Handle Ctrl-only shortcuts.
            switch (keyCode) {
                case KeyEvent.KEYCODE_A:
                    if (canSelectText()) {
                        return onTextContextMenuItem(ID_SELECT_ALL);
                    }
                    break;
                case KeyEvent.KEYCODE_Z:
                    if (canUndo()) {
                        return onTextContextMenuItem(ID_UNDO);
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
                case KeyEvent.KEYCODE_Y:
                    if (canRedo()) {
                        return onTextContextMenuItem(ID_REDO);
                    }
                    break;
            }
        } else if (event.hasModifiers(KeyEvent.META_CTRL_ON | KeyEvent.META_SHIFT_ON)) {
            // Handle Ctrl-Shift shortcuts.
            switch (keyCode) {
                case KeyEvent.KEYCODE_Z:
                    if (canRedo()) {
                        return onTextContextMenuItem(ID_REDO);
                    }
                    break;
                case KeyEvent.KEYCODE_V:
                    if (canPaste()) {
                        return onTextContextMenuItem(ID_PASTE_AS_PLAIN_TEXT);
                    }
            }
        }
        return super.onKeyShortcut(keyCode, event);
    }

    /**
     * Unlike {@link #textCanBeSelected()}, this method is based on the <i>current</i> state of the
     * TextView. {@link #textCanBeSelected()} has to be true (this is one of the conditions to have
     * a selection controller (see {@link Editor#prepareCursorControllers()}), but this is not
     * sufficient.
     */
    boolean canSelectText() {
        return mText.length() != 0 && mEditor != null && mEditor.hasSelectionController();
    }

    /**
     * Test based on the <i>intrinsic</i> charateristics of the TextView.
     * The text must be spannable and the movement method must allow for arbitary selection.
     *
     * See also {@link #canSelectText()}.
     */
    boolean textCanBeSelected() {
        // prepareCursorController() relies on this method.
        // If you change this condition, make sure prepareCursorController is called anywhere
        // the value of this condition might be changed.
        if (mMovement == null || !mMovement.canSelectArbitrarily()) return false;
        return isTextEditable()
                || (isTextSelectable() && mText instanceof Spannable && isEnabled());
    }

    @UnsupportedAppUsage
    private Locale getTextServicesLocale(boolean allowNullLocale) {
        // Start fetching the text services locale asynchronously.
        updateTextServicesLocaleAsync();
        // If !allowNullLocale and there is no cached text services locale, just return the default
        // locale.
        return (mCurrentSpellCheckerLocaleCache == null && !allowNullLocale) ? Locale.getDefault()
                : mCurrentSpellCheckerLocaleCache;
    }

    /**
     * Associate {@link UserHandle} who is considered to be the logical owner of the text shown in
     * this {@link TextView}.
     *
     * <p>Most of applications should not worry about this.  Some privileged apps that host UI for
     * other apps may need to set this so that the system can user right user's resources and
     * services such as input methods and spell checkers.</p>
     *
     * @param user {@link UserHandle} who is considered to be the owner of the text shown in this
     *        {@link TextView}. {@code null} to reset {@link #mTextOperationUser}.
     * @hide
     */
    @RequiresPermission(INTERACT_ACROSS_USERS_FULL)
    public final void setTextOperationUser(@Nullable UserHandle user) {
        if (Objects.equals(mTextOperationUser, user)) {
            return;
        }
        if (user != null && !Process.myUserHandle().equals(user)) {
            // Just for preventing people from accidentally using this hidden API without
            // the required permission.  The same permission is also checked in the system server.
            if (getContext().checkSelfPermission(INTERACT_ACROSS_USERS_FULL)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("INTERACT_ACROSS_USERS_FULL is required."
                        + " userId=" + user.getIdentifier()
                        + " callingUserId" + UserHandle.myUserId());
            }
        }
        mTextOperationUser = user;
        // Invalidate some resources
        mCurrentSpellCheckerLocaleCache = null;
        if (mEditor != null) {
            mEditor.onTextOperationUserChanged();
        }
    }

    @Override
    public boolean isAutoHandwritingEnabled() {
        return super.isAutoHandwritingEnabled() && !isAnyPasswordInputType();
    }

    /** @hide */
    @Override
    public boolean isStylusHandwritingAvailable() {
        if (mTextOperationUser == null) {
            return super.isStylusHandwritingAvailable();
        }
        final int userId = mTextOperationUser.getIdentifier();
        final InputMethodManager imm = getInputMethodManager();
        return imm.isStylusHandwritingAvailableAsUser(userId);
    }

    @Nullable
    final TextServicesManager getTextServicesManagerForUser() {
        return getServiceManagerForUser("android", TextServicesManager.class);
    }

    @Nullable
    final ClipboardManager getClipboardManagerForUser() {
        return getServiceManagerForUser(getContext().getPackageName(), ClipboardManager.class);
    }

    @Nullable
    final TextClassificationManager getTextClassificationManagerForUser() {
        return getServiceManagerForUser(
                getContext().getPackageName(), TextClassificationManager.class);
    }

    @Nullable
    final <T> T getServiceManagerForUser(String packageName, Class<T> managerClazz) {
        if (mTextOperationUser == null) {
            return getContext().getSystemService(managerClazz);
        }
        try {
            Context context = getContext().createPackageContextAsUser(
                    packageName, 0 /* flags */, mTextOperationUser);
            return context.getSystemService(managerClazz);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /**
     * Starts {@link Activity} as a text-operation user if it is specified with
     * {@link #setTextOperationUser(UserHandle)}.
     *
     * <p>Otherwise, just starts {@link Activity} with {@link Context#startActivity(Intent)}.</p>
     *
     * @param intent The description of the activity to start.
     */
    void startActivityAsTextOperationUserIfNecessary(@NonNull Intent intent) {
        if (mTextOperationUser != null) {
            getContext().startActivityAsUser(intent, mTextOperationUser);
        } else {
            getContext().startActivity(intent);
        }
    }

    /**
     * This is a temporary method. Future versions may support multi-locale text.
     * Caveat: This method may not return the latest text services locale, but this should be
     * acceptable and it's more important to make this method asynchronous.
     *
     * @return The locale that should be used for a word iterator
     * in this TextView, based on the current spell checker settings,
     * the current IME's locale, or the system default locale.
     * Please note that a word iterator in this TextView is different from another word iterator
     * used by SpellChecker.java of TextView. This method should be used for the former.
     * @hide
     */
    // TODO: Support multi-locale
    // TODO: Update the text services locale immediately after the keyboard locale is switched
    // by catching intent of keyboard switch event
    public Locale getTextServicesLocale() {
        return getTextServicesLocale(false /* allowNullLocale */);
    }

    /**
     * @return {@code true} if this TextView is specialized for showing and interacting with the
     * extracted text in a full-screen input method.
     * @hide
     */
    public boolean isInExtractedMode() {
        return false;
    }

    /**
     * @return {@code true} if this widget supports auto-sizing text and has been configured to
     * auto-size.
     */
    private boolean isAutoSizeEnabled() {
        return supportsAutoSizeText() && mAutoSizeTextType != AUTO_SIZE_TEXT_TYPE_NONE;
    }

    /**
     * @return {@code true} if this TextView supports auto-sizing text to fit within its container.
     * @hide
     */
    protected boolean supportsAutoSizeText() {
        return true;
    }

    /**
     * This is a temporary method. Future versions may support multi-locale text.
     * Caveat: This method may not return the latest spell checker locale, but this should be
     * acceptable and it's more important to make this method asynchronous.
     *
     * @return The locale that should be used for a spell checker in this TextView,
     * based on the current spell checker settings, the current IME's locale, or the system default
     * locale.
     * @hide
     */
    public Locale getSpellCheckerLocale() {
        return getTextServicesLocale(true /* allowNullLocale */);
    }

    private void updateTextServicesLocaleAsync() {
        // AsyncTask.execute() uses a serial executor which means we don't have
        // to lock around updateTextServicesLocaleLocked() to prevent it from
        // being executed n times in parallel.
        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                updateTextServicesLocaleLocked();
            }
        });
    }

    @UnsupportedAppUsage
    private void updateTextServicesLocaleLocked() {
        final TextServicesManager textServicesManager = getTextServicesManagerForUser();
        if (textServicesManager == null) {
            return;
        }
        final SpellCheckerSubtype subtype = textServicesManager.getCurrentSpellCheckerSubtype(true);
        final Locale locale;
        if (subtype != null) {
            locale = subtype.getLocaleObject();
        } else {
            locale = null;
        }
        mCurrentSpellCheckerLocaleCache = locale;
    }

    void onLocaleChanged() {
        mEditor.onLocaleChanged();
    }

    /**
     * This method is used by the ArrowKeyMovementMethod to jump from one word to the other.
     * Made available to achieve a consistent behavior.
     * @hide
     */
    public WordIterator getWordIterator() {
        if (mEditor != null) {
            return mEditor.getWordIterator();
        } else {
            return null;
        }
    }

    /** @hide */
    @Override
    public void onPopulateAccessibilityEventInternal(AccessibilityEvent event) {
        super.onPopulateAccessibilityEventInternal(event);

        if (this.isAccessibilityDataSensitive() && !event.isAccessibilityDataSensitive()) {
            // This view's accessibility data is sensitive, but another view that generated this
            // event is not, so don't append this view's text to the event in order to prevent
            // sharing this view's contents with non-accessibility-tool services.
            return;
        }

        final CharSequence text = getTextForAccessibility();
        if (!TextUtils.isEmpty(text)) {
            event.getText().add(text);
        }
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return TextView.class.getName();
    }

    /** @hide */
    @Override
    protected void onProvideStructure(@NonNull ViewStructure structure,
            @ViewStructureType int viewFor, int flags) {
        super.onProvideStructure(structure, viewFor, flags);

        final boolean isPassword = hasPasswordTransformationMethod()
                || isPasswordInputType(getInputType());
        if (viewFor == VIEW_STRUCTURE_FOR_AUTOFILL
                || viewFor == VIEW_STRUCTURE_FOR_CONTENT_CAPTURE) {
            if (viewFor == VIEW_STRUCTURE_FOR_AUTOFILL) {
                structure.setDataIsSensitive(!mTextSetFromXmlOrResourceId);
            }
            if (mTextId != Resources.ID_NULL) {
                try {
                    structure.setTextIdEntry(getResources().getResourceEntryName(mTextId));
                } catch (Resources.NotFoundException e) {
                    if (android.view.autofill.Helper.sVerbose) {
                        Log.v(LOG_TAG, "onProvideAutofillStructure(): cannot set name for text id "
                                + mTextId + ": " + e.getMessage());
                    }
                }
            }
            String[] mimeTypes = getReceiveContentMimeTypes();
            if (mimeTypes == null && mEditor != null) {
                // If the app hasn't set a listener for receiving content on this view (ie,
                // getReceiveContentMimeTypes() returns null), check if it implements the
                // keyboard image API and, if possible, use those MIME types as fallback.
                // This fallback is only in place for autofill, not other mechanisms for
                // inserting content. See AUTOFILL_NON_TEXT_REQUIRES_ON_RECEIVE_CONTENT_LISTENER
                // in TextViewOnReceiveContentListener for more info.
                mimeTypes = mEditor.getDefaultOnReceiveContentListener()
                        .getFallbackMimeTypesForAutofill(this);
            }
            structure.setReceiveContentMimeTypes(mimeTypes);
        }

        if (!isPassword || viewFor == VIEW_STRUCTURE_FOR_AUTOFILL
                || viewFor == VIEW_STRUCTURE_FOR_CONTENT_CAPTURE) {
            if (mLayout == null) {
                if (viewFor == VIEW_STRUCTURE_FOR_CONTENT_CAPTURE) {
                    Log.w(LOG_TAG, "onProvideContentCaptureStructure(): calling assumeLayout()");
                }
                assumeLayout();
            }
            Layout layout = mLayout;
            final int lineCount = layout.getLineCount();
            if (lineCount <= 1) {
                // Simple case: this is a single line.
                final CharSequence text = getText();
                if (viewFor == VIEW_STRUCTURE_FOR_AUTOFILL) {
                    structure.setText(text);
                } else {
                    structure.setText(text, getSelectionStart(), getSelectionEnd());
                }
            } else {
                // Complex case: multi-line, could be scrolled or within a scroll container
                // so some lines are not visible.
                final int[] tmpCords = new int[2];
                getLocationInWindow(tmpCords);
                final int topWindowLocation = tmpCords[1];
                View root = this;
                ViewParent viewParent = getParent();
                while (viewParent instanceof View) {
                    root = (View) viewParent;
                    viewParent = root.getParent();
                }
                final int windowHeight = root.getHeight();
                final int topLine;
                final int bottomLine;
                if (topWindowLocation >= 0) {
                    // The top of the view is fully within its window; start text at line 0.
                    topLine = getLineAtCoordinateUnclamped(0);
                    bottomLine = getLineAtCoordinateUnclamped(windowHeight - 1);
                } else {
                    // The top of hte window has scrolled off the top of the window; figure out
                    // the starting line for this.
                    topLine = getLineAtCoordinateUnclamped(-topWindowLocation);
                    bottomLine = getLineAtCoordinateUnclamped(windowHeight - 1 - topWindowLocation);
                }
                // We want to return some contextual lines above/below the lines that are
                // actually visible.
                int expandedTopLine = topLine - (bottomLine - topLine) / 2;
                if (expandedTopLine < 0) {
                    expandedTopLine = 0;
                }
                int expandedBottomLine = bottomLine + (bottomLine - topLine) / 2;
                if (expandedBottomLine >= lineCount) {
                    expandedBottomLine = lineCount - 1;
                }

                // Convert lines into character offsets.
                int expandedTopChar = transformedToOriginal(
                        layout.getLineStart(expandedTopLine),
                        OffsetMapping.MAP_STRATEGY_CHARACTER);
                int expandedBottomChar = transformedToOriginal(
                        layout.getLineEnd(expandedBottomLine),
                        OffsetMapping.MAP_STRATEGY_CHARACTER);

                // Take into account selection -- if there is a selection, we need to expand
                // the text we are returning to include that selection.
                final int selStart = getSelectionStart();
                final int selEnd = getSelectionEnd();
                if (selStart < selEnd) {
                    if (selStart < expandedTopChar) {
                        expandedTopChar = selStart;
                    }
                    if (selEnd > expandedBottomChar) {
                        expandedBottomChar = selEnd;
                    }
                }

                // Get the text and trim it to the range we are reporting.
                CharSequence text = getText();

                if (text != null) {
                    if (expandedTopChar > 0 || expandedBottomChar < text.length()) {
                        // Cap the offsets to avoid an OOB exception. That can happen if the
                        // displayed/layout text, on which these offsets are calculated, is longer
                        // than the original text (such as when the view is translated by the
                        // platform intelligence).
                        // TODO(b/196433694): Figure out how to better handle the offset
                        // calculations for this case (so we don't unnecessarily cutoff the original
                        // text, for example).
                        expandedTopChar = Math.min(expandedTopChar, text.length());
                        expandedBottomChar = Math.min(expandedBottomChar, text.length());
                        text = text.subSequence(expandedTopChar, expandedBottomChar);
                    }

                    if (viewFor == VIEW_STRUCTURE_FOR_AUTOFILL) {
                        structure.setText(text);
                    } else {
                        structure.setText(text,
                                selStart - expandedTopChar,
                                selEnd - expandedTopChar);

                        final int[] lineOffsets = new int[bottomLine - topLine + 1];
                        final int[] lineBaselines = new int[bottomLine - topLine + 1];
                        final int baselineOffset = getBaselineOffset();
                        for (int i = topLine; i <= bottomLine; i++) {
                            lineOffsets[i - topLine] = transformedToOriginal(layout.getLineStart(i),
                                    OffsetMapping.MAP_STRATEGY_CHARACTER);
                            lineBaselines[i - topLine] =
                                    layout.getLineBaseline(i) + baselineOffset;
                        }
                        structure.setTextLines(lineOffsets, lineBaselines);
                    }
                }
            }

            if (viewFor == VIEW_STRUCTURE_FOR_ASSIST
                    || viewFor == VIEW_STRUCTURE_FOR_CONTENT_CAPTURE) {
                // Extract style information that applies to the TextView as a whole.
                int style = 0;
                int typefaceStyle = getTypefaceStyle();
                if ((typefaceStyle & Typeface.BOLD) != 0) {
                    style |= AssistStructure.ViewNode.TEXT_STYLE_BOLD;
                }
                if ((typefaceStyle & Typeface.ITALIC) != 0) {
                    style |= AssistStructure.ViewNode.TEXT_STYLE_ITALIC;
                }

                // Global styles can also be set via TextView.setPaintFlags().
                int paintFlags = mTextPaint.getFlags();
                if ((paintFlags & Paint.FAKE_BOLD_TEXT_FLAG) != 0) {
                    style |= AssistStructure.ViewNode.TEXT_STYLE_BOLD;
                }
                if ((paintFlags & Paint.UNDERLINE_TEXT_FLAG) != 0) {
                    style |= AssistStructure.ViewNode.TEXT_STYLE_UNDERLINE;
                }
                if ((paintFlags & Paint.STRIKE_THRU_TEXT_FLAG) != 0) {
                    style |= AssistStructure.ViewNode.TEXT_STYLE_STRIKE_THRU;
                }

                // TextView does not have its own text background color. A background is either part
                // of the View (and can be any drawable) or a BackgroundColorSpan inside the text.
                structure.setTextStyle(getTextSize(), getCurrentTextColor(),
                        AssistStructure.ViewNode.TEXT_COLOR_UNDEFINED /* bgColor */, style);
            }
            if (viewFor == VIEW_STRUCTURE_FOR_AUTOFILL
                    || viewFor == VIEW_STRUCTURE_FOR_CONTENT_CAPTURE) {
                structure.setMinTextEms(getMinEms());
                structure.setMaxTextEms(getMaxEms());
                int maxLength = -1;
                for (InputFilter filter: getFilters()) {
                    if (filter instanceof InputFilter.LengthFilter) {
                        maxLength = ((InputFilter.LengthFilter) filter).getMax();
                        break;
                    }
                }
                structure.setMaxTextLength(maxLength);
            }
        }
        if (mHintId != Resources.ID_NULL) {
            try {
                structure.setHintIdEntry(getResources().getResourceEntryName(mHintId));
            } catch (Resources.NotFoundException e) {
                if (android.view.autofill.Helper.sVerbose) {
                    Log.v(LOG_TAG, "onProvideAutofillStructure(): cannot set name for hint id "
                            + mHintId + ": " + e.getMessage());
                }
            }
        }
        structure.setHint(getHint());
        structure.setInputType(getInputType());
    }

    boolean canRequestAutofill() {
        if (!isAutofillable()) {
            return false;
        }
        final AutofillManager afm = mContext.getSystemService(AutofillManager.class);
        if (afm != null) {
            return afm.isEnabled();
        }
        return false;
    }

    private void requestAutofill() {
        final AutofillManager afm = mContext.getSystemService(AutofillManager.class);
        if (afm != null) {
            afm.requestAutofill(this);
        }
    }

    @Override
    public void autofill(AutofillValue value) {
        if (!isTextEditable()) {
            Log.w(LOG_TAG, "cannot autofill non-editable TextView: " + this);
            return;
        }
        if (!value.isText()) {
            Log.w(LOG_TAG, "value of type " + value.describeContents()
                    + " cannot be autofilled into " + this);
            return;
        }
        final ClipData clip = ClipData.newPlainText("", value.getTextValue());
        final ContentInfo payload = new ContentInfo.Builder(clip, SOURCE_AUTOFILL).build();
        performReceiveContent(payload);
    }

    @Override
    public @AutofillType int getAutofillType() {
        return isTextEditable() ? AUTOFILL_TYPE_TEXT : AUTOFILL_TYPE_NONE;
    }

    /**
     * Gets the {@link TextView}'s current text for AutoFill. The value is trimmed to 100K
     * {@code char}s if longer.
     *
     * @return current text, {@code null} if the text is not editable
     *
     * @see View#getAutofillValue()
     */
    @Override
    @Nullable
    public AutofillValue getAutofillValue() {
        if (isTextEditable()) {
            final CharSequence text = TextUtils.trimToParcelableSize(getText());
            return AutofillValue.forText(text);
        }
        return null;
    }

    /** @hide */
    @Override
    public void onInitializeAccessibilityEventInternal(AccessibilityEvent event) {
        super.onInitializeAccessibilityEventInternal(event);

        final boolean isPassword = hasPasswordTransformationMethod();
        event.setPassword(isPassword);

        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            event.setFromIndex(Selection.getSelectionStart(mText));
            event.setToIndex(Selection.getSelectionEnd(mText));
            event.setItemCount(mText.length());
        }
    }

    /** @hide */
    @Override
    public void onInitializeAccessibilityNodeInfoInternal(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfoInternal(info);

        final boolean isPassword = hasPasswordTransformationMethod();
        info.setPassword(isPassword);
        info.setText(getTextForAccessibility());
        info.setHintText(mHint);
        info.setShowingHintText(isShowingHint());

        if (mBufferType == BufferType.EDITABLE) {
            info.setEditable(true);
            if (isEnabled()) {
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT);
            }
        }

        if (mEditor != null) {
            info.setInputType(mEditor.mInputType);

            if (mEditor.mError != null) {
                info.setContentInvalid(true);
                info.setError(mEditor.mError);
            }
            // TextView will expose this action if it is editable and has focus.
            if (isTextEditable() && isFocused()) {
                CharSequence imeActionLabel = mContext.getResources().getString(
                        com.android.internal.R.string.keyboardview_keycode_enter);
                if (getImeActionLabel() != null) {
                    imeActionLabel = getImeActionLabel();
                }
                AccessibilityNodeInfo.AccessibilityAction action =
                        new AccessibilityNodeInfo.AccessibilityAction(
                                R.id.accessibilityActionImeEnter, imeActionLabel);
                info.addAction(action);
            }
        }

        if (!TextUtils.isEmpty(mText)) {
            info.addAction(AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY);
            info.addAction(AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY);
            info.setMovementGranularities(AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER
                    | AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD
                    | AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE
                    | AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PARAGRAPH
                    | AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PAGE);
            info.addAction(AccessibilityNodeInfo.ACTION_SET_SELECTION);
            info.setAvailableExtraData(Arrays.asList(
                    EXTRA_DATA_RENDERING_INFO_KEY,
                    EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
            ));
            info.setTextSelectable(isTextSelectable() || isTextEditable());
        } else {
            info.setAvailableExtraData(Arrays.asList(
                    EXTRA_DATA_RENDERING_INFO_KEY
            ));
        }

        if (isFocused()) {
            if (canCopy()) {
                info.addAction(AccessibilityNodeInfo.ACTION_COPY);
            }
            if (canPaste()) {
                info.addAction(AccessibilityNodeInfo.ACTION_PASTE);
            }
            if (canCut()) {
                info.addAction(AccessibilityNodeInfo.ACTION_CUT);
            }
            if (canReplace()) {
                info.addAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_TEXT_SUGGESTIONS);
            }
            if (canShare()) {
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                        ACCESSIBILITY_ACTION_SHARE,
                        getResources().getString(com.android.internal.R.string.share)));
            }
            if (canProcessText()) {  // also implies mEditor is not null.
                mEditor.mProcessTextIntentActionsHandler.onInitializeAccessibilityNodeInfo(info);
                mEditor.onInitializeSmartActionsAccessibilityNodeInfo(info);
            }
        }

        // Check for known input filter types.
        final int numFilters = mFilters.length;
        for (int i = 0; i < numFilters; i++) {
            final InputFilter filter = mFilters[i];
            if (filter instanceof InputFilter.LengthFilter) {
                info.setMaxTextLength(((InputFilter.LengthFilter) filter).getMax());
            }
        }

        if (!isSingleLine()) {
            info.setMultiLine(true);
        }

        // A view should not be exposed as clickable/long-clickable to a service because of a
        // LinkMovementMethod or because it has selectable and non-editable text.
        if ((info.isClickable() || info.isLongClickable())
                && (mMovement instanceof LinkMovementMethod
                || (isTextSelectable() && !isTextEditable()))) {
            if (!hasOnClickListeners()) {
                info.setClickable(false);
                info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
            }
            if (!hasOnLongClickListeners()) {
                info.setLongClickable(false);
                info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK);
            }
        }
    }

    @Override
    public void addExtraDataToAccessibilityNodeInfo(
            AccessibilityNodeInfo info, String extraDataKey, Bundle arguments) {
        if (arguments != null && extraDataKey.equals(EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY)) {
            int positionInfoStartIndex = arguments.getInt(
                    EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, -1);
            int positionInfoLength = arguments.getInt(
                    EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, -1);
            if ((positionInfoLength <= 0) || (positionInfoStartIndex < 0)
                    || (positionInfoStartIndex >= mText.length())) {
                Log.e(LOG_TAG, "Invalid arguments for accessibility character locations");
                return;
            }
            RectF[] boundingRects = new RectF[positionInfoLength];
            final CursorAnchorInfo.Builder builder = new CursorAnchorInfo.Builder();
            populateCharacterBounds(builder, positionInfoStartIndex,
                    Math.min(positionInfoStartIndex + positionInfoLength, length()),
                    viewportToContentHorizontalOffset(), viewportToContentVerticalOffset());
            CursorAnchorInfo cursorAnchorInfo = builder.setMatrix(null).build();
            for (int i = 0; i < positionInfoLength; i++) {
                int flags = cursorAnchorInfo.getCharacterBoundsFlags(positionInfoStartIndex + i);
                if ((flags & FLAG_HAS_VISIBLE_REGION) == FLAG_HAS_VISIBLE_REGION) {
                    RectF bounds = cursorAnchorInfo
                            .getCharacterBounds(positionInfoStartIndex + i);
                    if (bounds != null) {
                        mapRectFromViewToScreenCoords(bounds, true);
                        boundingRects[i] = bounds;
                    }
                }
            }
            info.getExtras().putParcelableArray(extraDataKey, boundingRects);
            return;
        }
        if (extraDataKey.equals(AccessibilityNodeInfo.EXTRA_DATA_RENDERING_INFO_KEY)) {
            final AccessibilityNodeInfo.ExtraRenderingInfo extraRenderingInfo =
                    AccessibilityNodeInfo.ExtraRenderingInfo.obtain();
            extraRenderingInfo.setLayoutSize(getLayoutParams().width, getLayoutParams().height);
            extraRenderingInfo.setTextSizeInPx(getTextSize());
            extraRenderingInfo.setTextSizeUnit(getTextSizeUnit());
            info.setExtraRenderingInfo(extraRenderingInfo);
        }
    }

    /**
     * Helper method to set {@code rect} to the text content's non-clipped area in the view's
     * coordinates.
     *
     * @return true if at least part of the text content is visible; false if the text content is
     * completely clipped or translated out of the visible area.
     */
    private boolean getContentVisibleRect(Rect rect) {
        if (!getLocalVisibleRect(rect)) {
            return false;
        }
        // getLocalVisibleRect returns a rect relative to the unscrolled left top corner of the
        // view. In other words, the returned rectangle's origin point is (-scrollX, -scrollY) in
        // view's coordinates. So we need to offset it with the negative scrolled amount to convert
        // it to view's coordinate.
        rect.offset(-getScrollX(), -getScrollY());
        // Clip the view's visible rect with the text layout's visible rect.
        return rect.intersect(getCompoundPaddingLeft(), getCompoundPaddingTop(),
                getWidth() - getCompoundPaddingRight(), getHeight() - getCompoundPaddingBottom());
    }

    /**
     * Populate requested character bounds in a {@link CursorAnchorInfo.Builder}
     *
     * @param builder The builder to populate
     * @param startIndex The starting character index to populate
     * @param endIndex The ending character index to populate
     * @param viewportToContentHorizontalOffset The horizontal offset from the viewport to the
     * content
     * @param viewportToContentVerticalOffset The vertical offset from the viewport to the content
     * @hide
     */
    public void populateCharacterBounds(CursorAnchorInfo.Builder builder,
            int startIndex, int endIndex, float viewportToContentHorizontalOffset,
            float viewportToContentVerticalOffset) {
        if (isOffsetMappingAvailable()) {
            // The text is transformed, and has different length, we don't support
            // character bounds in this case yet.
            return;
        }
        final Rect rect = new Rect();
        getContentVisibleRect(rect);
        final RectF visibleRect = new RectF(rect);

        final float[] characterBounds = getCharacterBounds(startIndex, endIndex,
                viewportToContentHorizontalOffset, viewportToContentVerticalOffset);
        final int limit = endIndex - startIndex;
        for (int offset = 0; offset < limit; ++offset) {
            final float left = characterBounds[offset * 4];
            final float top = characterBounds[offset * 4 + 1];
            final float right = characterBounds[offset * 4 + 2];
            final float bottom = characterBounds[offset * 4 + 3];

            final boolean hasVisibleRegion = visibleRect.intersects(left, top, right, bottom);
            final boolean hasInVisibleRegion = !visibleRect.contains(left, top, right, bottom);
            int characterBoundsFlags = 0;
            if (hasVisibleRegion) {
                characterBoundsFlags |= FLAG_HAS_VISIBLE_REGION;
            }
            if (hasInVisibleRegion) {
                characterBoundsFlags |= CursorAnchorInfo.FLAG_HAS_INVISIBLE_REGION;
            }

            if (mLayout.isRtlCharAt(offset)) {
                characterBoundsFlags |= CursorAnchorInfo.FLAG_IS_RTL;
            }
            builder.addCharacterBounds(offset + startIndex, left, top, right, bottom,
                    characterBoundsFlags);
        }
    }

    /**
     * Return the bounds of the characters in the given range, in TextView's coordinates.
     *
     * @param start the start index of the interested text range, inclusive.
     * @param end the end index of the interested text range, exclusive.
     * @param layoutLeft the left of the given {@code layout} in the editor view's coordinates.
     * @param layoutTop  the top of the given {@code layout} in the editor view's coordinates.
     * @return the character bounds stored in a flattened array, in the editor view's coordinates.
     */
    private float[] getCharacterBounds(int start, int end, float layoutLeft, float layoutTop) {
        final float[] characterBounds = new float[4 * (end - start)];
        mLayout.fillCharacterBounds(start, end, characterBounds, 0);
        for (int offset = 0; offset < end - start; ++offset) {
            characterBounds[4 * offset] += layoutLeft;
            characterBounds[4 * offset + 1] += layoutTop;
            characterBounds[4 * offset + 2] += layoutLeft;
            characterBounds[4 * offset + 3] += layoutTop;
        }
        return characterBounds;
    }

    /**
     * Compute {@link CursorAnchorInfo} from this {@link TextView}.
     *
     * @param filter the {@link CursorAnchorInfo} update filter which specified the needed
     *               information from IME.
     * @param cursorAnchorInfoBuilder a cached {@link CursorAnchorInfo.Builder} object used to build
     *                                the result {@link CursorAnchorInfo}.
     * @param viewToScreenMatrix a cached {@link Matrix} object used to compute the view to screen
     *                           matrix.
     * @return the result {@link CursorAnchorInfo} to be passed to IME.
     * @hide
     */
    @VisibleForTesting
    @Nullable
    public CursorAnchorInfo getCursorAnchorInfo(@InputConnection.CursorUpdateFilter int filter,
            @NonNull CursorAnchorInfo.Builder cursorAnchorInfoBuilder,
            @NonNull Matrix viewToScreenMatrix) {
        Layout layout = getLayout();
        if (layout == null) {
            return null;
        }
        boolean includeEditorBounds =
                (filter & InputConnection.CURSOR_UPDATE_FILTER_EDITOR_BOUNDS) != 0;
        boolean includeCharacterBounds =
                (filter & InputConnection.CURSOR_UPDATE_FILTER_CHARACTER_BOUNDS) != 0;
        boolean includeInsertionMarker =
                (filter & InputConnection.CURSOR_UPDATE_FILTER_INSERTION_MARKER) != 0;
        boolean includeVisibleLineBounds =
                (filter & InputConnection.CURSOR_UPDATE_FILTER_VISIBLE_LINE_BOUNDS) != 0;
        boolean includeTextAppearance =
                (filter & InputConnection.CURSOR_UPDATE_FILTER_TEXT_APPEARANCE) != 0;
        boolean includeAll =
                (!includeEditorBounds && !includeCharacterBounds && !includeInsertionMarker
                        && !includeVisibleLineBounds && !includeTextAppearance);

        includeEditorBounds |= includeAll;
        includeCharacterBounds |= includeAll;
        includeInsertionMarker |= includeAll;
        includeVisibleLineBounds |= includeAll;
        includeTextAppearance |= includeAll;

        final CursorAnchorInfo.Builder builder = cursorAnchorInfoBuilder;
        builder.reset();

        final int selectionStart = getSelectionStart();
        builder.setSelectionRange(selectionStart, getSelectionEnd());

        // Construct transformation matrix from view local coordinates to screen coordinates.
        viewToScreenMatrix.reset();
        transformMatrixToGlobal(viewToScreenMatrix);
        builder.setMatrix(viewToScreenMatrix);

        if (includeEditorBounds) {
            final RectF editorBounds = new RectF();
            editorBounds.set(0 /* left */, 0 /* top */,
                    getWidth(), getHeight());
            final RectF handwritingBounds = new RectF(
                    -getHandwritingBoundsOffsetLeft(),
                    -getHandwritingBoundsOffsetTop(),
                    getWidth() + getHandwritingBoundsOffsetRight(),
                    getHeight() + getHandwritingBoundsOffsetBottom());
            EditorBoundsInfo.Builder boundsBuilder = new EditorBoundsInfo.Builder();
            EditorBoundsInfo editorBoundsInfo = boundsBuilder.setEditorBounds(editorBounds)
                    .setHandwritingBounds(handwritingBounds).build();
            builder.setEditorBoundsInfo(editorBoundsInfo);
        }

        if (includeCharacterBounds || includeInsertionMarker || includeVisibleLineBounds) {
            final float viewportToContentHorizontalOffset =
                    viewportToContentHorizontalOffset();
            final float viewportToContentVerticalOffset =
                    viewportToContentVerticalOffset();
            final boolean isTextTransformed = (getTransformationMethod() != null
                    && getTransformed() instanceof OffsetMapping);
            if (includeCharacterBounds && !isTextTransformed) {
                final CharSequence text = getText();
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
                            (0 <= composingTextStart) && (composingTextStart
                                    < composingTextEnd);
                    if (hasComposingText) {
                        final CharSequence composingText = text.subSequence(composingTextStart,
                                composingTextEnd);
                        builder.setComposingText(composingTextStart, composingText);
                        populateCharacterBounds(builder, composingTextStart,
                                composingTextEnd, viewportToContentHorizontalOffset,
                                viewportToContentVerticalOffset);
                    }
                }
            }

            if (includeInsertionMarker) {
                // Treat selectionStart as the insertion point.
                if (0 <= selectionStart) {
                    final int offsetTransformed = originalToTransformed(
                            selectionStart, OffsetMapping.MAP_STRATEGY_CURSOR);
                    final int line = layout.getLineForOffset(offsetTransformed);
                    final float insertionMarkerX =
                            layout.getPrimaryHorizontal(offsetTransformed)
                                    + viewportToContentHorizontalOffset;
                    final float insertionMarkerTop = layout.getLineTop(line)
                            + viewportToContentVerticalOffset;
                    final float insertionMarkerBaseline = layout.getLineBaseline(line)
                            + viewportToContentVerticalOffset;
                    final float insertionMarkerBottom =
                            layout.getLineBottom(line, /* includeLineSpacing= */ false)
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
                    if (layout.isRtlCharAt(offsetTransformed)) {
                        insertionMarkerFlags |= CursorAnchorInfo.FLAG_IS_RTL;
                    }
                    builder.setInsertionMarkerLocation(insertionMarkerX, insertionMarkerTop,
                            insertionMarkerBaseline, insertionMarkerBottom,
                            insertionMarkerFlags);
                }
            }

            if (includeVisibleLineBounds) {
                final Rect visibleRect = new Rect();
                if (getContentVisibleRect(visibleRect)) {
                    // Subtract the viewportToContentVerticalOffset to convert the view
                    // coordinates to layout coordinates.
                    final float visibleTop =
                            visibleRect.top - viewportToContentVerticalOffset;
                    final float visibleBottom =
                            visibleRect.bottom - viewportToContentVerticalOffset;
                    final int firstLine =
                            layout.getLineForVertical((int) Math.floor(visibleTop));
                    final int lastLine =
                            layout.getLineForVertical((int) Math.ceil(visibleBottom));

                    for (int line = firstLine; line <= lastLine; ++line) {
                        final float left = layout.getLineLeft(line)
                                + viewportToContentHorizontalOffset;
                        final float top = layout.getLineTop(line)
                                + viewportToContentVerticalOffset;
                        final float right = layout.getLineRight(line)
                                + viewportToContentHorizontalOffset;
                        final float bottom = layout.getLineBottom(line, false)
                                + viewportToContentVerticalOffset;
                        builder.addVisibleLineBounds(left, top, right, bottom);
                    }
                }
            }
        }

        if (includeTextAppearance) {
            builder.setTextAppearanceInfo(TextAppearanceInfo.createFromTextView(this));
        }
        return builder.build();
    }

    /**
     * Creates the {@link TextBoundsInfo} for the text lines that intersects with the {@code rectF}.
     * @hide
     */
    public TextBoundsInfo getTextBoundsInfo(@NonNull RectF bounds) {
        final Layout layout = getLayout();
        if (layout == null) {
            // No valid text layout, return null.
            return null;
        }
        final CharSequence text = layout.getText();
        if (text == null || isOffsetMappingAvailable()) {
            // The text is Null or the text has been transformed. Can't provide TextBoundsInfo.
            return null;
        }

        final Matrix localToGlobalMatrix = new Matrix();
        transformMatrixToGlobal(localToGlobalMatrix);
        final Matrix globalToLocalMatrix = new Matrix();
        if (!localToGlobalMatrix.invert(globalToLocalMatrix)) {
            // Can't map global rectF to local coordinates, this is almost impossible in practice.
            return null;
        }

        final float layoutLeft = viewportToContentHorizontalOffset();
        final float layoutTop = viewportToContentVerticalOffset();

        final RectF localBounds = new RectF(bounds);
        globalToLocalMatrix.mapRect(localBounds);
        localBounds.offset(-layoutLeft, -layoutTop);

        // Text length is 0. There is no character bounds, return empty TextBoundsInfo.
        // rectF doesn't intersect with the layout, return empty TextBoundsInfo.
        if (!localBounds.intersects(0f, 0f, layout.getWidth(), layout.getHeight())
                || text.length() == 0) {
            final TextBoundsInfo.Builder builder = new TextBoundsInfo.Builder(0, 0);
            final SegmentFinder emptySegmentFinder =
                    new SegmentFinder.PrescribedSegmentFinder(new int[0]);
            builder.setMatrix(localToGlobalMatrix)
                    .setCharacterBounds(new float[0])
                    .setCharacterBidiLevel(new int[0])
                    .setCharacterFlags(new int[0])
                    .setGraphemeSegmentFinder(emptySegmentFinder)
                    .setLineSegmentFinder(emptySegmentFinder)
                    .setWordSegmentFinder(emptySegmentFinder);
            return  builder.build();
        }

        final int startLine = layout.getLineForVertical((int) Math.floor(localBounds.top));
        final int endLine = layout.getLineForVertical((int) Math.floor(localBounds.bottom));
        final int start = layout.getLineStart(startLine);
        final int end = layout.getLineEnd(endLine);

        // Compute character bounds.
        final float[] characterBounds = getCharacterBounds(start, end, layoutLeft, layoutTop);

        // Compute character flags and BiDi levels.
        final int[] characterFlags = new int[end - start];
        final int[] characterBidiLevels = new int[end - start];
        for (int line = startLine; line <= endLine; ++line) {
            final int lineStart = layout.getLineStart(line);
            final int lineEnd = layout.getLineEnd(line);
            final Layout.Directions directions = layout.getLineDirections(line);
            for (int i = 0; i < directions.getRunCount(); ++i) {
                final int runStart = directions.getRunStart(i) + lineStart;
                final int runEnd = Math.min(runStart + directions.getRunLength(i), lineEnd);
                final int runLevel = directions.getRunLevel(i);
                Arrays.fill(characterBidiLevels, runStart - start, runEnd - start, runLevel);
            }

            final boolean lineIsRtl =
                    layout.getParagraphDirection(line) == Layout.DIR_RIGHT_TO_LEFT;
            for (int index = lineStart; index < lineEnd; ++index) {
                int flags = 0;
                if (TextUtils.isWhitespace(text.charAt(index))) {
                    flags |= TextBoundsInfo.FLAG_CHARACTER_WHITESPACE;
                }
                if (TextUtils.isPunctuation(Character.codePointAt(text, index))) {
                    flags |= TextBoundsInfo.FLAG_CHARACTER_PUNCTUATION;
                }
                if (TextUtils.isNewline(Character.codePointAt(text, index))) {
                    flags |= TextBoundsInfo.FLAG_CHARACTER_LINEFEED;
                }
                if (lineIsRtl) {
                    flags |= TextBoundsInfo.FLAG_LINE_IS_RTL;
                }
                characterFlags[index - start] = flags;
            }
        }

        // Create grapheme SegmentFinder.
        final SegmentFinder graphemeSegmentFinder =
                new GraphemeClusterSegmentFinder(text, layout.getPaint());

        // Create word SegmentFinder.
        final WordIterator wordIterator = getWordIterator();
        wordIterator.setCharSequence(text, 0, text.length());
        final SegmentFinder wordSegmentFinder = new WordSegmentFinder(text, wordIterator);

        // Create line SegmentFinder.
        final int lineCount = endLine - startLine + 1;
        final int[] lineRanges = new int[2 * lineCount];
        for (int line = startLine; line <= endLine; ++line) {
            final int offset = line - startLine;
            lineRanges[2 * offset] = layout.getLineStart(line);
            lineRanges[2 * offset + 1] = layout.getLineEnd(line);
        }
        final SegmentFinder lineSegmentFinder =
                new SegmentFinder.PrescribedSegmentFinder(lineRanges);

        return new TextBoundsInfo.Builder(start, end)
                .setMatrix(localToGlobalMatrix)
                .setCharacterBounds(characterBounds)
                .setCharacterBidiLevel(characterBidiLevels)
                .setCharacterFlags(characterFlags)
                .setGraphemeSegmentFinder(graphemeSegmentFinder)
                .setLineSegmentFinder(lineSegmentFinder)
                .setWordSegmentFinder(wordSegmentFinder)
                .build();
    }

    /**
     * @hide
     */
    public boolean isPositionVisible(final float positionX, final float positionY) {
        synchronized (TEMP_POSITION) {
            final float[] position = TEMP_POSITION;
            position[0] = positionX;
            position[1] = positionY;
            View view = this;

            while (view != null) {
                if (view != this) {
                    // Local scroll is already taken into account in positionX/Y
                    position[0] -= view.getScrollX();
                    position[1] -= view.getScrollY();
                }

                if (position[0] < 0 || position[1] < 0 || position[0] > view.getWidth()
                        || position[1] > view.getHeight()) {
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

    /**
     * Performs an accessibility action after it has been offered to the
     * delegate.
     *
     * @hide
     */
    @Override
    public boolean performAccessibilityActionInternal(int action, Bundle arguments) {
        if (mEditor != null) {
            if (mEditor.mProcessTextIntentActionsHandler.performAccessibilityAction(action)
                    || mEditor.performSmartActionsAccessibilityAction(action)) {
                return true;
            }
        }
        switch (action) {
            case AccessibilityNodeInfo.ACTION_CLICK: {
                return performAccessibilityActionClick(arguments);
            }
            case AccessibilityNodeInfo.ACTION_COPY: {
                if (isFocused() && canCopy()) {
                    if (onTextContextMenuItem(ID_COPY)) {
                        return true;
                    }
                }
            } return false;
            case AccessibilityNodeInfo.ACTION_PASTE: {
                if (isFocused() && canPaste()) {
                    if (onTextContextMenuItem(ID_PASTE)) {
                        return true;
                    }
                }
            } return false;
            case AccessibilityNodeInfo.ACTION_CUT: {
                if (isFocused() && canCut()) {
                    if (onTextContextMenuItem(ID_CUT)) {
                        return true;
                    }
                }
            } return false;
            case AccessibilityNodeInfo.ACTION_SET_SELECTION: {
                ensureIterableTextForAccessibilitySelectable();
                CharSequence text = getIterableTextForAccessibility();
                if (text == null) {
                    return false;
                }
                final int start = (arguments != null) ? arguments.getInt(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, -1) : -1;
                final int end = (arguments != null) ? arguments.getInt(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, -1) : -1;
                if ((getSelectionStart() != start || getSelectionEnd() != end)) {
                    // No arguments clears the selection.
                    if (start == end && end == -1) {
                        Selection.removeSelection((Spannable) text);
                        return true;
                    }
                    if (start >= 0 && start <= end && end <= text.length()) {
                        requestFocusOnNonEditableSelectableText();
                        Selection.setSelection((Spannable) text, start, end);
                        // Make sure selection mode is engaged.
                        if (mEditor != null) {
                            mEditor.startSelectionActionModeAsync(false);
                        }
                        return true;
                    }
                }
            } return false;
            case AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY:
            case AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY: {
                ensureIterableTextForAccessibilitySelectable();
                return super.performAccessibilityActionInternal(action, arguments);
            }
            case ACCESSIBILITY_ACTION_SHARE: {
                if (isFocused() && canShare()) {
                    if (onTextContextMenuItem(ID_SHARE)) {
                        return true;
                    }
                }
            } return false;
            case AccessibilityNodeInfo.ACTION_SET_TEXT: {
                if (!isEnabled() || (mBufferType != BufferType.EDITABLE)) {
                    return false;
                }
                CharSequence text = (arguments != null) ? arguments.getCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE) : null;
                setText(text);
                if (mText != null) {
                    int updatedTextLength = mText.length();
                    if (updatedTextLength > 0) {
                        Selection.setSelection(mSpannable, updatedTextLength);
                    }
                }
            } return true;
            case R.id.accessibilityActionImeEnter: {
                if (isFocused() && isTextEditable()) {
                    onEditorAction(getImeActionId());
                }
            } return true;
            case AccessibilityNodeInfo.ACTION_LONG_CLICK: {
                if (isLongClickable()) {
                    boolean handled;
                    if (isEnabled() && (mBufferType == BufferType.EDITABLE)) {
                        mEditor.mIsBeingLongClickedByAccessibility = true;
                        try {
                            handled = performLongClick();
                        } finally {
                            mEditor.mIsBeingLongClickedByAccessibility = false;
                        }
                    } else {
                        handled = performLongClick();
                    }
                    return handled;
                }
            }
            return false;
            default: {
                // New ids have static blocks to assign values, so they can't be used in a case
                // block.
                if (action == R.id.accessibilityActionShowTextSuggestions) {
                    return isFocused() && canReplace() && onTextContextMenuItem(ID_REPLACE);
                }
                return super.performAccessibilityActionInternal(action, arguments);
            }
        }
    }

    private boolean performAccessibilityActionClick(Bundle arguments) {
        boolean handled = false;

        if (!isEnabled()) {
            return false;
        }

        if (isClickable() || isLongClickable()) {
            // Simulate View.onTouchEvent for an ACTION_UP event
            if (isFocusable() && !isFocused()) {
                requestFocus();
            }

            performClick();
            handled = true;
        }

        // Show the IME, except when selecting in read-only text.
        if ((mMovement != null || onCheckIsTextEditor()) && hasSpannableText() && mLayout != null
                && (isTextEditable() || isTextSelectable()) && isFocused()) {
            final InputMethodManager imm = getInputMethodManager();
            viewClicked(imm);
            if (!isTextSelectable() && mEditor.mShowSoftInputOnFocus && imm != null) {
                handled |= imm.showSoftInput(this, 0);
            }
        }

        return handled;
    }

    private void requestFocusOnNonEditableSelectableText() {
        if (!isTextEditable() && isTextSelectable()) {
            if (!isEnabled()) {
                return;
            }

            if (isFocusable() && !isFocused()) {
                requestFocus();
            }
        }
    }

    private boolean hasSpannableText() {
        return mText != null && mText instanceof Spannable;
    }

    /** @hide */
    @Override
    public void sendAccessibilityEventInternal(int eventType) {
        if (eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED && mEditor != null) {
            mEditor.mProcessTextIntentActionsHandler.initializeAccessibilityActions();
        }

        super.sendAccessibilityEventInternal(eventType);
    }

    @Override
    public void sendAccessibilityEventUnchecked(AccessibilityEvent event) {
        // Do not send scroll events since first they are not interesting for
        // accessibility and second such events a generated too frequently.
        // For details see the implementation of bringTextIntoView().
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            return;
        }
        super.sendAccessibilityEventUnchecked(event);
    }

    /**
     * Returns the text that should be exposed to accessibility services.
     * <p>
     * This approximates what is displayed visually.
     *
     * @return the text that should be exposed to accessibility services, may
     *         be {@code null} if no text is set
     */
    @Nullable
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private CharSequence getTextForAccessibility() {
        // If the text is empty, we must be showing the hint text.
        if (TextUtils.isEmpty(mText)) {
            return mHint;
        }

        // Otherwise, return whatever text is being displayed.
        return TextUtils.trimToParcelableSize(mTransformed);
    }

    boolean isVisibleToAccessibility() {
        return AccessibilityManager.getInstance(mContext).isEnabled()
                && (isFocused() || (isSelected() && isShown()));
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

    void sendAccessibilityEventTypeViewTextChanged(CharSequence beforeText,
            int fromIndex, int toIndex) {
        AccessibilityEvent event =
                AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED);
        event.setFromIndex(fromIndex);
        event.setToIndex(toIndex);
        event.setBeforeText(beforeText);
        sendAccessibilityEventUnchecked(event);
    }

    private InputMethodManager getInputMethodManager() {
        return getContext().getSystemService(InputMethodManager.class);
    }

    /**
     * Returns whether this text view is a current input method target.  The
     * default implementation just checks with {@link InputMethodManager}.
     * @return True if the TextView is a current input method target; false otherwise.
     */
    public boolean isInputMethodTarget() {
        InputMethodManager imm = getInputMethodManager();
        return imm != null && imm.isActive(this);
    }

    static final int ID_SELECT_ALL = android.R.id.selectAll;
    static final int ID_UNDO = android.R.id.undo;
    static final int ID_REDO = android.R.id.redo;
    static final int ID_CUT = android.R.id.cut;
    static final int ID_COPY = android.R.id.copy;
    static final int ID_PASTE = android.R.id.paste;
    static final int ID_SHARE = android.R.id.shareText;
    static final int ID_PASTE_AS_PLAIN_TEXT = android.R.id.pasteAsPlainText;
    static final int ID_REPLACE = android.R.id.replaceText;
    static final int ID_ASSIST = android.R.id.textAssist;
    static final int ID_AUTOFILL = android.R.id.autofill;

    /**
     * Called when a context menu option for the text view is selected.  Currently
     * this will be one of {@link android.R.id#selectAll}, {@link android.R.id#cut},
     * {@link android.R.id#copy}, {@link android.R.id#paste},
     * {@link android.R.id#pasteAsPlainText} (starting at API level 23) or
     * {@link android.R.id#shareText}.
     *
     * @return true if the context menu item action was performed.
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

        switch (id) {
            case ID_SELECT_ALL:
                final boolean hadSelection = hasSelection();
                selectAllText();
                if (mEditor != null && hadSelection) {
                    mEditor.invalidateActionModeAsync();
                }
                return true;

            case ID_UNDO:
                if (mEditor != null) {
                    mEditor.undo();
                }
                return true;  // Returns true even if nothing was undone.

            case ID_REDO:
                if (mEditor != null) {
                    mEditor.redo();
                }
                return true;  // Returns true even if nothing was undone.

            case ID_PASTE:
                paste(true /* withFormatting */);
                return true;

            case ID_PASTE_AS_PLAIN_TEXT:
                paste(false /* withFormatting */);
                return true;

            case ID_CUT:
                final ClipData cutData = ClipData.newPlainText(null, getTransformedText(min, max));
                if (setPrimaryClip(cutData)) {
                    deleteText_internal(min, max);
                } else {
                    Toast.makeText(getContext(),
                            com.android.internal.R.string.failed_to_copy_to_clipboard,
                            Toast.LENGTH_SHORT).show();
                }
                return true;

            case ID_COPY:
                // For link action mode in a non-selectable/non-focusable TextView,
                // make sure that we set the appropriate min/max.
                final int selStart = getSelectionStart();
                final int selEnd = getSelectionEnd();
                min = Math.max(0, Math.min(selStart, selEnd));
                max = Math.max(0, Math.max(selStart, selEnd));
                final ClipData copyData = ClipData.newPlainText(null, getTransformedText(min, max));
                if (setPrimaryClip(copyData)) {
                    stopTextActionMode();
                } else {
                    Toast.makeText(getContext(),
                            com.android.internal.R.string.failed_to_copy_to_clipboard,
                            Toast.LENGTH_SHORT).show();
                }
                return true;

            case ID_REPLACE:
                if (mEditor != null) {
                    mEditor.replace();
                }
                return true;

            case ID_SHARE:
                shareSelectedText();
                return true;

            case ID_AUTOFILL:
                requestAutofill();
                stopTextActionMode();
                return true;
        }
        return false;
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    CharSequence getTransformedText(int start, int end) {
        return removeSuggestionSpans(mTransformed.subSequence(start, end));
    }

    @Override
    public boolean performLongClick() {
        if (DEBUG_CURSOR) {
            logCursor("performLongClick", null);
        }

        boolean handled = false;
        boolean performedHapticFeedback = false;

        if (mEditor != null) {
            mEditor.mIsBeingLongClicked = true;
        }

        if (super.performLongClick()) {
            handled = true;
            performedHapticFeedback = true;
        }

        if (mEditor != null) {
            handled |= mEditor.performLongClick(handled);
            mEditor.mIsBeingLongClicked = false;
        }

        if (handled) {
            if (!performedHapticFeedback) {
              performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            }
            if (mEditor != null) mEditor.mDiscardNextActionUp = true;
        } else {
            MetricsLogger.action(
                    mContext,
                    MetricsEvent.TEXT_LONGPRESS,
                    TextViewMetrics.SUBTYPE_LONG_PRESS_OTHER);
        }

        return handled;
    }

    @Override
    protected void onScrollChanged(int horiz, int vert, int oldHoriz, int oldVert) {
        super.onScrollChanged(horiz, vert, oldHoriz, oldVert);
        if (mEditor != null) {
            mEditor.onScrollChanged();
        }
    }

    /**
     * Return whether or not suggestions are enabled on this TextView. The suggestions are generated
     * by the IME or by the spell checker as the user types. This is done by adding
     * {@link SuggestionSpan}s to the text.
     *
     * When suggestions are enabled (default), this list of suggestions will be displayed when the
     * user asks for them on these parts of the text. This value depends on the inputType of this
     * TextView.
     *
     * The class of the input type must be {@link InputType#TYPE_CLASS_TEXT}.
     *
     * In addition, the type variation must be one of
     * {@link InputType#TYPE_TEXT_VARIATION_NORMAL},
     * {@link InputType#TYPE_TEXT_VARIATION_EMAIL_SUBJECT},
     * {@link InputType#TYPE_TEXT_VARIATION_LONG_MESSAGE},
     * {@link InputType#TYPE_TEXT_VARIATION_SHORT_MESSAGE} or
     * {@link InputType#TYPE_TEXT_VARIATION_WEB_EDIT_TEXT}.
     *
     * And finally, the {@link InputType#TYPE_TEXT_FLAG_NO_SUGGESTIONS} flag must <i>not</i> be set.
     *
     * @return true if the suggestions popup window is enabled, based on the inputType.
     */
    public boolean isSuggestionsEnabled() {
        if (mEditor == null) return false;
        if ((mEditor.mInputType & InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) {
            return false;
        }
        if ((mEditor.mInputType & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) > 0) return false;

        final int variation = mEditor.mInputType & EditorInfo.TYPE_MASK_VARIATION;
        return (variation == EditorInfo.TYPE_TEXT_VARIATION_NORMAL
                || variation == EditorInfo.TYPE_TEXT_VARIATION_EMAIL_SUBJECT
                || variation == EditorInfo.TYPE_TEXT_VARIATION_LONG_MESSAGE
                || variation == EditorInfo.TYPE_TEXT_VARIATION_SHORT_MESSAGE
                || variation == EditorInfo.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT);
    }

    /**
     * If provided, this ActionMode.Callback will be used to create the ActionMode when text
     * selection is initiated in this View.
     *
     * <p>The standard implementation populates the menu with a subset of Select All, Cut, Copy,
     * Paste, Replace and Share actions, depending on what this View supports.
     *
     * <p>A custom implementation can add new entries in the default menu in its
     * {@link android.view.ActionMode.Callback#onPrepareActionMode(ActionMode, android.view.Menu)}
     * method. The default actions can also be removed from the menu using
     * {@link android.view.Menu#removeItem(int)} and passing {@link android.R.id#selectAll},
     * {@link android.R.id#cut}, {@link android.R.id#copy}, {@link android.R.id#paste},
     * {@link android.R.id#pasteAsPlainText} (starting at API level 23),
     * {@link android.R.id#replaceText} or {@link android.R.id#shareText} ids as parameters.
     *
     * <p>Returning false from
     * {@link android.view.ActionMode.Callback#onCreateActionMode(ActionMode, android.view.Menu)}
     * will prevent the action mode from being started.
     *
     * <p>Action click events should be handled by the custom implementation of
     * {@link android.view.ActionMode.Callback#onActionItemClicked(ActionMode,
     * android.view.MenuItem)}.
     *
     * <p>Note that text selection mode is not started when a TextView receives focus and the
     * {@link android.R.attr#selectAllOnFocus} flag has been set. The content is highlighted in
     * that case, to allow for quick replacement.
     */
    public void setCustomSelectionActionModeCallback(ActionMode.Callback actionModeCallback) {
        createEditorIfNeeded();
        mEditor.mCustomSelectionActionModeCallback = actionModeCallback;
    }

    /**
     * Retrieves the value set in {@link #setCustomSelectionActionModeCallback}. Default is null.
     *
     * @return The current custom selection callback.
     */
    public ActionMode.Callback getCustomSelectionActionModeCallback() {
        return mEditor == null ? null : mEditor.mCustomSelectionActionModeCallback;
    }

    /**
     * If provided, this ActionMode.Callback will be used to create the ActionMode when text
     * insertion is initiated in this View.
     * The standard implementation populates the menu with a subset of Select All,
     * Paste and Replace actions, depending on what this View supports.
     *
     * <p>A custom implementation can add new entries in the default menu in its
     * {@link android.view.ActionMode.Callback#onPrepareActionMode(android.view.ActionMode,
     * android.view.Menu)} method. The default actions can also be removed from the menu using
     * {@link android.view.Menu#removeItem(int)} and passing {@link android.R.id#selectAll},
     * {@link android.R.id#paste}, {@link android.R.id#pasteAsPlainText} (starting at API
     * level 23) or {@link android.R.id#replaceText} ids as parameters.</p>
     *
     * <p>Returning false from
     * {@link android.view.ActionMode.Callback#onCreateActionMode(android.view.ActionMode,
     * android.view.Menu)} will prevent the action mode from being started.</p>
     *
     * <p>Action click events should be handled by the custom implementation of
     * {@link android.view.ActionMode.Callback#onActionItemClicked(android.view.ActionMode,
     * android.view.MenuItem)}.</p>
     *
     * <p>Note that text insertion mode is not started when a TextView receives focus and the
     * {@link android.R.attr#selectAllOnFocus} flag has been set.</p>
     */
    public void setCustomInsertionActionModeCallback(ActionMode.Callback actionModeCallback) {
        createEditorIfNeeded();
        mEditor.mCustomInsertionActionModeCallback = actionModeCallback;
    }

    /**
     * Retrieves the value set in {@link #setCustomInsertionActionModeCallback}. Default is null.
     *
     * @return The current custom insertion callback.
     */
    public ActionMode.Callback getCustomInsertionActionModeCallback() {
        return mEditor == null ? null : mEditor.mCustomInsertionActionModeCallback;
    }

    /**
     * Sets the {@link TextClassifier} for this TextView.
     */
    public void setTextClassifier(@Nullable TextClassifier textClassifier) {
        mTextClassifier = textClassifier;
    }

    /**
     * Returns the {@link TextClassifier} used by this TextView.
     * If no TextClassifier has been set, this TextView uses the default set by the
     * {@link TextClassificationManager}.
     */
    @NonNull
    public TextClassifier getTextClassifier() {
        if (mTextClassifier == null) {
            final TextClassificationManager tcm = getTextClassificationManagerForUser();
            if (tcm != null) {
                return tcm.getTextClassifier();
            }
            return TextClassifier.NO_OP;
        }
        return mTextClassifier;
    }

    /**
     * Returns a session-aware text classifier.
     * This method creates one if none already exists or the current one is destroyed.
     */
    @NonNull
    TextClassifier getTextClassificationSession() {
        if (mTextClassificationSession == null || mTextClassificationSession.isDestroyed()) {
            final TextClassificationManager tcm = getTextClassificationManagerForUser();
            if (tcm != null) {
                final String widgetType;
                if (isTextEditable()) {
                    widgetType = TextClassifier.WIDGET_TYPE_EDITTEXT;
                } else if (isTextSelectable()) {
                    widgetType = TextClassifier.WIDGET_TYPE_TEXTVIEW;
                } else {
                    widgetType = TextClassifier.WIDGET_TYPE_UNSELECTABLE_TEXTVIEW;
                }
                mTextClassificationContext = new TextClassificationContext.Builder(
                        mContext.getPackageName(), widgetType)
                        .build();
                if (mTextClassifier != null) {
                    mTextClassificationSession = tcm.createTextClassificationSession(
                            mTextClassificationContext, mTextClassifier);
                } else {
                    mTextClassificationSession = tcm.createTextClassificationSession(
                            mTextClassificationContext);
                }
            } else {
                mTextClassificationSession = TextClassifier.NO_OP;
            }
        }
        return mTextClassificationSession;
    }

    /**
     * Returns the {@link TextClassificationContext} for the current TextClassifier session.
     * @see #getTextClassificationSession()
     */
    @Nullable
    TextClassificationContext getTextClassificationContext() {
        return mTextClassificationContext;
    }

    /**
     * Returns true if this TextView uses a no-op TextClassifier.
     */
    boolean usesNoOpTextClassifier() {
        return getTextClassifier() == TextClassifier.NO_OP;
    }

    /**
     * Starts an ActionMode for the specified TextLinkSpan.
     *
     * @return Whether or not we're attempting to start the action mode.
     * @hide
     */
    public boolean requestActionMode(@NonNull TextLinks.TextLinkSpan clickedSpan) {
        Preconditions.checkNotNull(clickedSpan);

        if (!(mText instanceof Spanned)) {
            return false;
        }

        final int start = ((Spanned) mText).getSpanStart(clickedSpan);
        final int end = ((Spanned) mText).getSpanEnd(clickedSpan);

        if (start < 0 || end > mText.length() || start >= end) {
            return false;
        }

        createEditorIfNeeded();
        mEditor.startLinkActionModeAsync(start, end);
        return true;
    }

    /**
     * Handles a click on the specified TextLinkSpan.
     *
     * @return Whether or not the click is being handled.
     * @hide
     */
    public boolean handleClick(@NonNull TextLinks.TextLinkSpan clickedSpan) {
        Preconditions.checkNotNull(clickedSpan);
        if (mText instanceof Spanned) {
            final Spanned spanned = (Spanned) mText;
            final int start = spanned.getSpanStart(clickedSpan);
            final int end = spanned.getSpanEnd(clickedSpan);
            if (start >= 0 && end <= mText.length() && start < end) {
                final TextClassification.Request request = new TextClassification.Request.Builder(
                        mText, start, end)
                        .setDefaultLocales(getTextLocales())
                        .build();
                final Supplier<TextClassification> supplier = () ->
                        getTextClassificationSession().classifyText(request);
                final Consumer<TextClassification> consumer = classification -> {
                    if (classification != null) {
                        if (!classification.getActions().isEmpty()) {
                            try {
                                classification.getActions().get(0).getActionIntent().send();
                            } catch (PendingIntent.CanceledException e) {
                                Log.e(LOG_TAG, "Error sending PendingIntent", e);
                            }
                        } else {
                            Log.d(LOG_TAG, "No link action to perform");
                        }
                    } else {
                        // classification == null
                        Log.d(LOG_TAG, "Timeout while classifying text");
                    }
                };
                CompletableFuture.supplyAsync(supplier)
                        .completeOnTimeout(null, 1, TimeUnit.SECONDS)
                        .thenAccept(consumer);
                return true;
            }
        }
        return false;
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    protected void stopTextActionMode() {
        if (mEditor != null) {
            mEditor.stopTextActionMode();
        }
    }

    /** @hide */
    public void hideFloatingToolbar(int durationMs) {
        if (mEditor != null) {
            mEditor.hideFloatingToolbar(durationMs);
        }
    }

    boolean canUndo() {
        return mEditor != null && mEditor.canUndo();
    }

    boolean canRedo() {
        return mEditor != null && mEditor.canRedo();
    }

    boolean canCut() {
        if (hasPasswordTransformationMethod()) {
            return false;
        }

        if (mText.length() > 0 && hasSelection() && mText instanceof Editable && mEditor != null
                && mEditor.mKeyListener != null) {
            return true;
        }

        return false;
    }

    boolean canCopy() {
        if (hasPasswordTransformationMethod()) {
            return false;
        }

        if (mText.length() > 0 && hasSelection() && mEditor != null) {
            return true;
        }

        return false;
    }

    boolean canReplace() {
        if (hasPasswordTransformationMethod()) {
            return false;
        }

        return (mText.length() > 0) && (mText instanceof Editable) && (mEditor != null)
                && isSuggestionsEnabled() && mEditor.shouldOfferToShowSuggestions();
    }

    boolean canShare() {
        if (!getContext().canStartActivityForResult() || !isDeviceProvisioned()) {
            return false;
        }
        return canCopy();
    }

    boolean isDeviceProvisioned() {
        if (mDeviceProvisionedState == DEVICE_PROVISIONED_UNKNOWN) {
            mDeviceProvisionedState = Settings.Global.getInt(
                    mContext.getContentResolver(), Settings.Global.DEVICE_PROVISIONED, 0) != 0
                    ? DEVICE_PROVISIONED_YES
                    : DEVICE_PROVISIONED_NO;
        }
        return mDeviceProvisionedState == DEVICE_PROVISIONED_YES;
    }

    @UnsupportedAppUsage
    boolean canPaste() {
        return (mText instanceof Editable
                && mEditor != null && mEditor.mKeyListener != null
                && getSelectionStart() >= 0
                && getSelectionEnd() >= 0
                && getClipboardManagerForUser().hasPrimaryClip());
    }

    boolean canPasteAsPlainText() {
        if (!canPaste()) {
            return false;
        }

        final ClipDescription description =
                getClipboardManagerForUser().getPrimaryClipDescription();
        final boolean isPlainType = description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN);
        return (isPlainType && description.isStyledText())
                || description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML);
    }

    boolean canProcessText() {
        if (getId() == View.NO_ID) {
            return false;
        }
        return canShare();
    }

    boolean canSelectAllText() {
        return canSelectText() && !hasPasswordTransformationMethod()
                && !(getSelectionStart() == 0 && getSelectionEnd() == mText.length());
    }

    boolean selectAllText() {
        if (mEditor != null) {
            // Hide the toolbar before changing the selection to avoid flickering.
            hideFloatingToolbar(FLOATING_TOOLBAR_SELECT_ALL_REFRESH_DELAY);
        }
        final int length = mText.length();
        Selection.setSelection(mSpannable, 0, length);
        return length > 0;
    }

    private void paste(boolean withFormatting) {
        ClipboardManager clipboard = getClipboardManagerForUser();
        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null) {
            return;
        }
        final ContentInfo payload = new ContentInfo.Builder(clip, SOURCE_CLIPBOARD)
                .setFlags(withFormatting ? 0 : FLAG_CONVERT_TO_PLAIN_TEXT)
                .build();
        performReceiveContent(payload);
        sLastCutCopyOrTextChangedTime = 0;
    }

    private void shareSelectedText() {
        String selectedText = getSelectedText();
        if (selectedText != null && !selectedText.isEmpty()) {
            Intent sharingIntent = new Intent(android.content.Intent.ACTION_SEND);
            sharingIntent.setType("text/plain");
            sharingIntent.removeExtra(android.content.Intent.EXTRA_TEXT);
            selectedText = TextUtils.trimToParcelableSize(selectedText);
            sharingIntent.putExtra(android.content.Intent.EXTRA_TEXT, selectedText);
            getContext().startActivity(Intent.createChooser(sharingIntent, null));
            Selection.setSelection(mSpannable, getSelectionEnd());
        }
    }

    @CheckResult
    private boolean setPrimaryClip(ClipData clip) {
        ClipboardManager clipboard = getClipboardManagerForUser();
        try {
            clipboard.setPrimaryClip(clip);
        } catch (Throwable t) {
            return false;
        }
        sLastCutCopyOrTextChangedTime = SystemClock.uptimeMillis();
        return true;
    }

    /**
     * Get the character offset closest to the specified absolute position. A typical use case is to
     * pass the result of {@link MotionEvent#getX()} and {@link MotionEvent#getY()} to this method.
     *
     * @param x The horizontal absolute position of a point on screen
     * @param y The vertical absolute position of a point on screen
     * @return the character offset for the character whose position is closest to the specified
     *  position. Returns -1 if there is no layout.
     */
    public int getOffsetForPosition(float x, float y) {
        if (getLayout() == null) return -1;
        final int line = getLineAtCoordinate(y);
        final int offset = getOffsetAtCoordinate(line, x);
        return offset;
    }

    float convertToLocalHorizontalCoordinate(float x) {
        x -= getTotalPaddingLeft();
        // Clamp the position to inside of the view.
        x = Math.max(0.0f, x);
        x = Math.min(getWidth() - getTotalPaddingRight() - 1, x);
        x += getScrollX();
        return x;
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    int getLineAtCoordinate(float y) {
        y -= getTotalPaddingTop();
        // Clamp the position to inside of the view.
        y = Math.max(0.0f, y);
        y = Math.min(getHeight() - getTotalPaddingBottom() - 1, y);
        y += getScrollY();
        return getLayout().getLineForVertical((int) y);
    }

    int getLineAtCoordinateUnclamped(float y) {
        y -= getTotalPaddingTop();
        y += getScrollY();
        return getLayout().getLineForVertical((int) y);
    }

    int getOffsetAtCoordinate(int line, float x) {
        x = convertToLocalHorizontalCoordinate(x);
        final int offset = getLayout().getOffsetForHorizontal(line, x);
        return transformedToOriginal(offset, OffsetMapping.MAP_STRATEGY_CURSOR);
    }

    /**
     * Convenient method to convert an offset on the transformed text to the original text.
     * @hide
     */
    public int transformedToOriginal(int offset, @OffsetMapping.MapStrategy int strategy) {
        if (getTransformationMethod() == null) {
            return offset;
        }
        if (mTransformed instanceof OffsetMapping) {
            final OffsetMapping transformedText = (OffsetMapping) mTransformed;
            return transformedText.transformedToOriginal(offset, strategy);
        }
        return offset;
    }

    /**
     * Convenient method to convert an offset on the original text to the transformed text.
     * @hide
     */
    public int originalToTransformed(int offset, @OffsetMapping.MapStrategy int strategy) {
        if (getTransformationMethod() == null) {
            return offset;
        }
        if (mTransformed instanceof OffsetMapping) {
            final OffsetMapping transformedText = (OffsetMapping) mTransformed;
            return transformedText.originalToTransformed(offset, strategy);
        }
        return offset;
    }
    /**
     * Handles drag events sent by the system following a call to
     * {@link android.view.View#startDragAndDrop(ClipData,DragShadowBuilder,Object,int)
     * startDragAndDrop()}.
     *
     * <p>If this text view is not editable, delegates to the default {@link View#onDragEvent}
     * implementation.
     *
     * <p>If this text view is editable, accepts all drag actions (returns true for an
     * {@link android.view.DragEvent#ACTION_DRAG_STARTED ACTION_DRAG_STARTED} event and all
     * subsequent drag events). While the drag is in progress, updates the cursor position
     * to follow the touch location. Once a drop event is received, handles content insertion
     * via {@link #performReceiveContent}.
     *
     * @param event The {@link android.view.DragEvent} sent by the system.
     * The {@link android.view.DragEvent#getAction()} method returns an action type constant
     * defined in DragEvent, indicating the type of drag event represented by this object.
     * @return Returns true if this text view is editable and delegates to super otherwise.
     * See {@link View#onDragEvent}.
     */
    @Override
    public boolean onDragEvent(DragEvent event) {
        if (mEditor == null || !mEditor.hasInsertionController()) {
            // If this TextView is not editable, defer to the default View implementation. This
            // will check for the presence of an OnReceiveContentListener and accept/reject
            // drag events depending on whether the listener is/isn't set.
            return super.onDragEvent(event);
        }
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                return true;

            case DragEvent.ACTION_DRAG_ENTERED:
                TextView.this.requestFocus();
                return true;

            case DragEvent.ACTION_DRAG_LOCATION:
                if (mText instanceof Spannable) {
                    final int offset = getOffsetForPosition(event.getX(), event.getY());
                    Selection.setSelection(mSpannable, offset);
                }
                return true;

            case DragEvent.ACTION_DROP:
                if (mEditor != null) mEditor.onDrop(event);
                return true;

            case DragEvent.ACTION_DRAG_ENDED:
            case DragEvent.ACTION_DRAG_EXITED:
            default:
                return true;
        }
    }

    boolean isInBatchEditMode() {
        if (mEditor == null) return false;
        final Editor.InputMethodState ims = mEditor.mInputMethodState;
        if (ims != null) {
            return ims.mBatchEditNesting > 0;
        }
        return mEditor.mInBatchEditControllers;
    }

    @Override
    public void onRtlPropertiesChanged(int layoutDirection) {
        super.onRtlPropertiesChanged(layoutDirection);

        final TextDirectionHeuristic newTextDir = getTextDirectionHeuristic();
        if (mTextDir != newTextDir) {
            mTextDir = newTextDir;
            if (mLayout != null) {
                checkForRelayout();
            }
        }
    }

    /**
     * Returns resolved {@link TextDirectionHeuristic} that will be used for text layout.
     * The {@link TextDirectionHeuristic} that is used by TextView is only available after
     * {@link #getTextDirection()} and {@link #getLayoutDirection()} is resolved. Therefore the
     * return value may not be the same as the one TextView uses if the View's layout direction is
     * not resolved or detached from parent root view.
     */
    public @NonNull TextDirectionHeuristic getTextDirectionHeuristic() {
        if (hasPasswordTransformationMethod()) {
            // passwords fields should be LTR
            return TextDirectionHeuristics.LTR;
        }

        if (mEditor != null
                && (mEditor.mInputType & EditorInfo.TYPE_MASK_CLASS)
                    == EditorInfo.TYPE_CLASS_PHONE) {
            // Phone numbers must be in the direction of the locale's digits. Most locales have LTR
            // digits, but some locales, such as those written in the Adlam or N'Ko scripts, have
            // RTL digits.
            final DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(getTextLocale());
            final String zero = symbols.getDigitStrings()[0];
            // In case the zero digit is multi-codepoint, just use the first codepoint to determine
            // direction.
            final int firstCodepoint = zero.codePointAt(0);
            final byte digitDirection = Character.getDirectionality(firstCodepoint);
            if (digitDirection == Character.DIRECTIONALITY_RIGHT_TO_LEFT
                    || digitDirection == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) {
                return TextDirectionHeuristics.RTL;
            } else {
                return TextDirectionHeuristics.LTR;
            }
        }

        // Always need to resolve layout direction first
        final boolean defaultIsRtl = (getLayoutDirection() == LAYOUT_DIRECTION_RTL);

        // Now, we can select the heuristic
        switch (getTextDirection()) {
            default:
            case TEXT_DIRECTION_FIRST_STRONG:
                return (defaultIsRtl ? TextDirectionHeuristics.FIRSTSTRONG_RTL :
                        TextDirectionHeuristics.FIRSTSTRONG_LTR);
            case TEXT_DIRECTION_ANY_RTL:
                return TextDirectionHeuristics.ANYRTL_LTR;
            case TEXT_DIRECTION_LTR:
                return TextDirectionHeuristics.LTR;
            case TEXT_DIRECTION_RTL:
                return TextDirectionHeuristics.RTL;
            case TEXT_DIRECTION_LOCALE:
                return TextDirectionHeuristics.LOCALE;
            case TEXT_DIRECTION_FIRST_STRONG_LTR:
                return TextDirectionHeuristics.FIRSTSTRONG_LTR;
            case TEXT_DIRECTION_FIRST_STRONG_RTL:
                return TextDirectionHeuristics.FIRSTSTRONG_RTL;
        }
    }

    /**
     * @hide
     */
    @Override
    public void onResolveDrawables(int layoutDirection) {
        // No need to resolve twice
        if (mLastLayoutDirection == layoutDirection) {
            return;
        }
        mLastLayoutDirection = layoutDirection;

        // Resolve drawables
        if (mDrawables != null) {
            if (mDrawables.resolveWithLayoutDirection(layoutDirection)) {
                prepareDrawableForDisplay(mDrawables.mShowing[Drawables.LEFT]);
                prepareDrawableForDisplay(mDrawables.mShowing[Drawables.RIGHT]);
                applyCompoundDrawableTint();
            }
        }
    }

    /**
     * Prepares a drawable for display by propagating layout direction and
     * drawable state.
     *
     * @param dr the drawable to prepare
     */
    private void prepareDrawableForDisplay(@Nullable Drawable dr) {
        if (dr == null) {
            return;
        }

        dr.setLayoutDirection(getLayoutDirection());

        if (dr.isStateful()) {
            dr.setState(getDrawableState());
            dr.jumpToCurrentState();
        }
    }

    /**
     * @hide
     */
    protected void resetResolvedDrawables() {
        super.resetResolvedDrawables();
        mLastLayoutDirection = -1;
    }

    /**
     * @hide
     */
    protected void viewClicked(InputMethodManager imm) {
        if (imm != null) {
            imm.viewClicked(this);
        }
    }

    /**
     * Deletes the range of text [start, end[.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    protected void deleteText_internal(int start, int end) {
        ((Editable) mText).delete(start, end);
    }

    /**
     * Replaces the range of text [start, end[ by replacement text
     * @hide
     */
    protected void replaceText_internal(int start, int end, CharSequence text) {
        ((Editable) mText).replace(start, end, text);
    }

    /**
     * Sets a span on the specified range of text
     * @hide
     */
    protected void setSpan_internal(Object span, int start, int end, int flags) {
        ((Editable) mText).setSpan(span, start, end, flags);
    }

    /**
     * Moves the cursor to the specified offset position in text
     * @hide
     */
    protected void setCursorPosition_internal(int start, int end) {
        Selection.setSelection(((Editable) mText), start, end);
    }

    /**
     * An Editor should be created as soon as any of the editable-specific fields (grouped
     * inside the Editor object) is assigned to a non-default value.
     * This method will create the Editor if needed.
     *
     * A standard TextView (as well as buttons, checkboxes...) should not qualify and hence will
     * have a null Editor, unlike an EditText. Inconsistent in-between states will have an
     * Editor for backward compatibility, as soon as one of these fields is assigned.
     *
     * Also note that for performance reasons, the mEditor is created when needed, but not
     * reset when no more edit-specific fields are needed.
     */
    @UnsupportedAppUsage
    private void createEditorIfNeeded() {
        if (mEditor == null) {
            mEditor = new Editor(this);
        }
    }

    /**
     * @hide
     */
    @Override
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public CharSequence getIterableTextForAccessibility() {
        return mText;
    }

    private void ensureIterableTextForAccessibilitySelectable() {
        if (!(mText instanceof Spannable)) {
            setText(mText, BufferType.SPANNABLE);
        }
    }

    /**
     * @hide
     */
    @Override
    public TextSegmentIterator getIteratorForGranularity(int granularity) {
        switch (granularity) {
            case AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE: {
                Spannable text = (Spannable) getIterableTextForAccessibility();
                if (!TextUtils.isEmpty(text) && getLayout() != null) {
                    AccessibilityIterators.LineTextSegmentIterator iterator =
                            AccessibilityIterators.LineTextSegmentIterator.getInstance();
                    iterator.initialize(text, getLayout());
                    return iterator;
                }
            } break;
            case AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PAGE: {
                Spannable text = (Spannable) getIterableTextForAccessibility();
                if (!TextUtils.isEmpty(text) && getLayout() != null) {
                    AccessibilityIterators.PageTextSegmentIterator iterator =
                            AccessibilityIterators.PageTextSegmentIterator.getInstance();
                    iterator.initialize(this);
                    return iterator;
                }
            } break;
        }
        return super.getIteratorForGranularity(granularity);
    }

    /**
     * @hide
     */
    @Override
    public int getAccessibilitySelectionStart() {
        return getSelectionStart();
    }

    /**
     * @hide
     */
    public boolean isAccessibilitySelectionExtendable() {
        return true;
    }

    /**
     * @hide
     */
    public void prepareForExtendedAccessibilitySelection() {
        requestFocusOnNonEditableSelectableText();
    }

    /**
     * @hide
     */
    @Override
    public int getAccessibilitySelectionEnd() {
        return getSelectionEnd();
    }

    /**
     * @hide
     */
    @Override
    public void setAccessibilitySelection(int start, int end) {
        if (getAccessibilitySelectionStart() == start
                && getAccessibilitySelectionEnd() == end) {
            return;
        }
        CharSequence text = getIterableTextForAccessibility();
        if (Math.min(start, end) >= 0 && Math.max(start, end) <= text.length()) {
            Selection.setSelection((Spannable) text, start, end);
        } else {
            Selection.removeSelection((Spannable) text);
        }
        // Hide all selection controllers used for adjusting selection
        // since we are doing so explicitlty by other means and these
        // controllers interact with how selection behaves.
        if (mEditor != null) {
            mEditor.hideCursorAndSpanControllers();
            mEditor.stopTextActionMode();
        }
    }

    /** @hide */
    @Override
    protected void encodeProperties(@NonNull ViewHierarchyEncoder stream) {
        super.encodeProperties(stream);

        TruncateAt ellipsize = getEllipsize();
        stream.addProperty("text:ellipsize", ellipsize == null ? null : ellipsize.name());
        stream.addProperty("text:textSize", getTextSize());
        stream.addProperty("text:scaledTextSize", getScaledTextSize());
        stream.addProperty("text:typefaceStyle", getTypefaceStyle());
        stream.addProperty("text:selectionStart", getSelectionStart());
        stream.addProperty("text:selectionEnd", getSelectionEnd());
        stream.addProperty("text:curTextColor", mCurTextColor);
        stream.addUserProperty("text:text", mText == null ? null : mText.toString());
        stream.addProperty("text:gravity", mGravity);
    }

    /**
     * User interface state that is stored by TextView for implementing
     * {@link View#onSaveInstanceState}.
     */
    public static class SavedState extends BaseSavedState {
        int selStart = -1;
        int selEnd = -1;
        @UnsupportedAppUsage
        CharSequence text;
        boolean frozenWithFocus;
        CharSequence error;
        ParcelableParcel editorState;  // Optional state from Editor.

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

            if (editorState == null) {
                out.writeInt(0);
            } else {
                out.writeInt(1);
                editorState.writeToParcel(out, flags);
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
        public static final @android.annotation.NonNull Parcelable.Creator<SavedState> CREATOR =
                new Parcelable.Creator<SavedState>() {
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

            if (in.readInt() != 0) {
                editorState = ParcelableParcel.CREATOR.createFromParcel(in);
            }
        }
    }

    private static class CharWrapper implements CharSequence, GetChars, GraphicsOperations {
        @NonNull
        private char[] mChars;
        private int mStart, mLength;

        CharWrapper(@NonNull char[] chars, int start, int len) {
            mChars = chars;
            mStart = start;
            mLength = len;
        }

        /* package */ void set(@NonNull char[] chars, int start, int len) {
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

        @Override
        public void drawText(BaseCanvas c, int start, int end,
                             float x, float y, Paint p) {
            c.drawText(mChars, start + mStart, end - start, x, y, p);
        }

        @Override
        public void drawTextRun(BaseCanvas c, int start, int end,
                int contextStart, int contextEnd, float x, float y, boolean isRtl, Paint p) {
            int count = end - start;
            int contextCount = contextEnd - contextStart;
            c.drawTextRun(mChars, start + mStart, count, contextStart + mStart,
                    contextCount, x, y, isRtl, p);
        }

        public float measureText(int start, int end, Paint p) {
            return p.measureText(mChars, start + mStart, end - start);
        }

        public int getTextWidths(int start, int end, float[] widths, Paint p) {
            return p.getTextWidths(mChars, start + mStart, end - start, widths);
        }

        public float getTextRunAdvances(int start, int end, int contextStart,
                int contextEnd, boolean isRtl, float[] advances, int advancesIndex,
                Paint p) {
            int count = end - start;
            int contextCount = contextEnd - contextStart;
            return p.getTextRunAdvances(mChars, start + mStart, count,
                    contextStart + mStart, contextCount, isRtl, advances,
                    advancesIndex);
        }

        public int getTextRunCursor(int contextStart, int contextEnd, boolean isRtl,
                int offset, int cursorOpt, Paint p) {
            int contextCount = contextEnd - contextStart;
            return p.getTextRunCursor(mChars, contextStart + mStart,
                    contextCount, isRtl, offset + mStart, cursorOpt);
        }
    }

    private static final class Marquee {
        // TODO: Add an option to configure this
        private static final float MARQUEE_DELTA_MAX = 0.07f;
        private static final int MARQUEE_DELAY = 1200;
        private static final int MARQUEE_DP_PER_SECOND = 30;

        private static final byte MARQUEE_STOPPED = 0x0;
        private static final byte MARQUEE_STARTING = 0x1;
        private static final byte MARQUEE_RUNNING = 0x2;

        private final WeakReference<TextView> mView;
        private final Choreographer mChoreographer;

        private byte mStatus = MARQUEE_STOPPED;
        private final float mPixelsPerMs;
        private float mMaxScroll;
        private float mMaxFadeScroll;
        private float mGhostStart;
        private float mGhostOffset;
        private float mFadeStop;
        private int mRepeatLimit;

        private float mScroll;
        private long mLastAnimationMs;

        Marquee(TextView v) {
            final float density = v.getContext().getResources().getDisplayMetrics().density;
            mPixelsPerMs = MARQUEE_DP_PER_SECOND * density / 1000f;
            mView = new WeakReference<TextView>(v);
            mChoreographer = Choreographer.getInstance();
        }

        private Choreographer.FrameCallback mTickCallback = new Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                tick();
            }
        };

        private Choreographer.FrameCallback mStartCallback = new Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                mStatus = MARQUEE_RUNNING;
                mLastAnimationMs = mChoreographer.getFrameTime();
                tick();
            }
        };

        private Choreographer.FrameCallback mRestartCallback = new Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                if (mStatus == MARQUEE_RUNNING) {
                    if (mRepeatLimit >= 0) {
                        mRepeatLimit--;
                    }
                    start(mRepeatLimit);
                }
            }
        };

        void tick() {
            if (mStatus != MARQUEE_RUNNING) {
                return;
            }

            mChoreographer.removeFrameCallback(mTickCallback);

            final TextView textView = mView.get();
            if (textView != null && textView.isAggregatedVisible()
                    && (textView.isFocused() || textView.isSelected())) {
                long currentMs = mChoreographer.getFrameTime();
                long deltaMs = currentMs - mLastAnimationMs;
                mLastAnimationMs = currentMs;
                float deltaPx = deltaMs * mPixelsPerMs;
                mScroll += deltaPx;
                if (mScroll > mMaxScroll) {
                    mScroll = mMaxScroll;
                    mChoreographer.postFrameCallbackDelayed(mRestartCallback, MARQUEE_DELAY);
                } else {
                    mChoreographer.postFrameCallback(mTickCallback);
                }
                textView.invalidate();
            }
        }

        void stop() {
            mStatus = MARQUEE_STOPPED;
            mChoreographer.removeFrameCallback(mStartCallback);
            mChoreographer.removeFrameCallback(mRestartCallback);
            mChoreographer.removeFrameCallback(mTickCallback);
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
                final int textWidth = textView.getWidth() - textView.getCompoundPaddingLeft()
                        - textView.getCompoundPaddingRight();
                final float lineWidth = textView.mLayout.getLineWidth(0);
                final float gap = textWidth / 3.0f;
                mGhostStart = lineWidth - textWidth + gap;
                mMaxScroll = mGhostStart + textWidth;
                mGhostOffset = lineWidth + gap;
                mFadeStop = lineWidth + textWidth / 6.0f;
                mMaxFadeScroll = mGhostStart + lineWidth + lineWidth;

                textView.invalidate();
                mChoreographer.postFrameCallback(mStartCallback);
            }
        }

        float getGhostOffset() {
            return mGhostOffset;
        }

        float getScroll() {
            return mScroll;
        }

        float getMaxFadeScroll() {
            return mMaxFadeScroll;
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

    private class ChangeWatcher implements TextWatcher, SpanWatcher {

        private CharSequence mBeforeText;

        public void beforeTextChanged(CharSequence buffer, int start,
                                      int before, int after) {
            if (DEBUG_EXTRACT) {
                Log.v(LOG_TAG, "beforeTextChanged start=" + start
                        + " before=" + before + " after=" + after + ": " + buffer);
            }

            if (AccessibilityManager.getInstance(mContext).isEnabled() && (mTransformed != null)) {
                mBeforeText = mTransformed.toString();
            }

            TextView.this.sendBeforeTextChanged(buffer, start, before, after);
        }

        public void onTextChanged(CharSequence buffer, int start, int before, int after) {
            if (DEBUG_EXTRACT) {
                Log.v(LOG_TAG, "onTextChanged start=" + start
                        + " before=" + before + " after=" + after + ": " + buffer);
            }
            TextView.this.handleTextChanged(buffer, start, before, after);

            if (isVisibleToAccessibility()) {
                sendAccessibilityEventTypeViewTextChanged(mBeforeText, start, before, after);
                mBeforeText = null;
            }
        }

        public void afterTextChanged(Editable buffer) {
            if (DEBUG_EXTRACT) {
                Log.v(LOG_TAG, "afterTextChanged: " + buffer);
            }
            TextView.this.sendAfterTextChanged(buffer);

            if (MetaKeyKeyListener.getMetaState(buffer, MetaKeyKeyListener.META_SELECTING) != 0) {
                MetaKeyKeyListener.stopSelecting(TextView.this, buffer);
            }
        }

        public void onSpanChanged(Spannable buf, Object what, int s, int e, int st, int en) {
            if (DEBUG_EXTRACT) {
                Log.v(LOG_TAG, "onSpanChanged s=" + s + " e=" + e
                        + " st=" + st + " en=" + en + " what=" + what + ": " + buf);
            }
            TextView.this.spanChange(buf, what, s, st, e, en);
        }

        public void onSpanAdded(Spannable buf, Object what, int s, int e) {
            if (DEBUG_EXTRACT) {
                Log.v(LOG_TAG, "onSpanAdded s=" + s + " e=" + e + " what=" + what + ": " + buf);
            }
            TextView.this.spanChange(buf, what, -1, s, -1, e);
        }

        public void onSpanRemoved(Spannable buf, Object what, int s, int e) {
            if (DEBUG_EXTRACT) {
                Log.v(LOG_TAG, "onSpanRemoved s=" + s + " e=" + e + " what=" + what + ": " + buf);
            }
            TextView.this.spanChange(buf, what, s, -1, e, -1);
        }
    }

    /** @hide */
    @Override
    public void onInputConnectionOpenedInternal(@NonNull InputConnection ic,
            @NonNull EditorInfo editorInfo, @Nullable Handler handler) {
        if (mEditor != null) {
            mEditor.getDefaultOnReceiveContentListener().setInputConnectionInfo(this, ic,
                    editorInfo);
        }
    }

    /** @hide */
    @Override
    public void onInputConnectionClosedInternal() {
        if (mEditor != null) {
            mEditor.getDefaultOnReceiveContentListener().clearInputConnectionInfo();
        }
    }

    /**
     * Default {@link TextView} implementation for receiving content. Apps wishing to provide
     * custom behavior should configure a listener via {@link #setOnReceiveContentListener}.
     *
     * <p>For non-editable TextViews the default behavior is a no-op (returns the passed-in
     * content without acting on it).
     *
     * <p>For editable TextViews the default behavior is to insert text into the view, coercing
     * non-text content to text as needed. The MIME types "text/plain" and "text/html" have
     * well-defined behavior for this, while other MIME types have reasonable fallback behavior
     * (see {@link ClipData.Item#coerceToStyledText}).
     *
     * @param payload The content to insert and related metadata.
     *
     * @return The portion of the passed-in content that was not handled (may be all, some, or none
     * of the passed-in content).
     */
    @Nullable
    @Override
    public ContentInfo onReceiveContent(@NonNull ContentInfo payload) {
        if (mEditor != null) {
            return mEditor.getDefaultOnReceiveContentListener().onReceiveContent(this, payload);
        }
        return payload;
    }

    private static void logCursor(String location, @Nullable String msgFormat, Object ... msgArgs) {
        if (msgFormat == null) {
            Log.d(LOG_TAG, location);
        } else {
            Log.d(LOG_TAG, location + ": " + String.format(msgFormat, msgArgs));
        }
    }

    /**
     * Collects a {@link ViewTranslationRequest} which represents the content to be translated in
     * the view.
     *
     * <p>NOTE: When overriding the method, it should not collect a request to translate this
     * TextView if it is displaying a password.
     *
     * @param supportedFormats the supported translation format. The value could be {@link
     *                         android.view.translation.TranslationSpec#DATA_FORMAT_TEXT}.
     * @param requestsCollector {@link Consumer} to receiver the {@link ViewTranslationRequest}
     *                                         which contains the information to be translated.
     */
    @Override
    public void onCreateViewTranslationRequest(@NonNull int[] supportedFormats,
            @NonNull Consumer<ViewTranslationRequest> requestsCollector) {
        if (supportedFormats == null || supportedFormats.length == 0) {
            if (UiTranslationController.DEBUG) {
                Log.w(LOG_TAG, "Do not provide the support translation formats.");
            }
            return;
        }
        ViewTranslationRequest.Builder requestBuilder =
                new ViewTranslationRequest.Builder(getAutofillId());
        // Support Text translation
        if (ArrayUtils.contains(supportedFormats, TranslationSpec.DATA_FORMAT_TEXT)) {
            if (mText == null || mText.length() == 0) {
                if (UiTranslationController.DEBUG) {
                    Log.w(LOG_TAG, "Cannot create translation request for the empty text.");
                }
                return;
            }
            boolean isPassword = isAnyPasswordInputType() || hasPasswordTransformationMethod();
            if (isTextEditable() || isPassword) {
                Log.w(LOG_TAG, "Cannot create translation request. editable = "
                        + isTextEditable() + ", isPassword = " + isPassword);
                return;
            }
            // TODO(b/176488462): apply the view's important for translation
            requestBuilder.setValue(ViewTranslationRequest.ID_TEXT,
                    TranslationRequestValue.forText(mText));
            if (!TextUtils.isEmpty(getContentDescription())) {
                requestBuilder.setValue(ViewTranslationRequest.ID_CONTENT_DESCRIPTION,
                        TranslationRequestValue.forText(getContentDescription()));
            }
        }
        requestsCollector.accept(requestBuilder.build());
    }
}
