/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.app.slice.widget;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringDef;
import android.app.slice.Slice;
import android.app.slice.SliceItem;
import android.app.slice.SliceQuery;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.android.internal.R;
import com.android.internal.util.Preconditions;

import java.util.List;

/**
 * A view for displaying a {@link Slice} which is a piece of app content and actions. SliceView is
 * able to present slice content in a templated format outside of the associated app. The way this
 * content is displayed depends on the structure of the slice, the hints associated with the
 * content, and the mode that SliceView is configured for. The modes that SliceView supports are:
 * <ul>
 * <li><b>Shortcut</b>: A shortcut is presented as an icon and a text label representing the main
 * content or action associated with the slice.</li>
 * <li><b>Small</b>: The small format has a restricted height and can present a single
 * {@link SliceItem} or a limited collection of items.</li>
 * <li><b>Large</b>: The large format displays multiple small templates in a list, if scrolling is
 * not enabled (see {@link #setScrollable(boolean)}) the view will show as many items as it can
 * comfortably fit.</li>
 * </ul>
 * <p>
 * When constructing a slice, the contents of it can be annotated with hints, these provide the OS
 * with some information on how the content should be displayed. For example, text annotated with
 * {@link Slice#HINT_TITLE} would be placed in the title position of a template. A slice annotated
 * with {@link Slice#HINT_LIST} would present the child items of that slice in a list.
 * <p>
 * SliceView can be provided a slice via a uri {@link #setSlice(Uri)} in which case a content
 * observer will be set for that uri and the view will update if there are any changes to the slice.
 * To use this the app must have a special permission to bind to the slice (see
 * {@link android.Manifest.permission#BIND_SLICE}).
 * <p>
 * Example usage:
 *
 * <pre class="prettyprint">
 * SliceView v = new SliceView(getContext());
 * v.setMode(desiredMode);
 * v.setSlice(sliceUri);
 * </pre>
 */
public class SliceView extends ViewGroup {

    private static final String TAG = "SliceView";

    /**
     * @hide
     */
    public abstract static class SliceModeView extends FrameLayout {

        public SliceModeView(Context context) {
            super(context);
        }

        /**
         * @return the mode of the slice being presented.
         */
        public abstract String getMode();

        /**
         * @param slice the slice to show in this view.
         */
        public abstract void setSlice(Slice slice);
    }

    /**
     * @hide
     */
    @StringDef({
            MODE_SMALL, MODE_LARGE, MODE_SHORTCUT
    })
    public @interface SliceMode {}

    /**
     * Mode indicating this slice should be presented in small template format.
     */
    public static final String MODE_SMALL       = "SLICE_SMALL";
    /**
     * Mode indicating this slice should be presented in large template format.
     */
    public static final String MODE_LARGE       = "SLICE_LARGE";
    /**
     * Mode indicating this slice should be presented as an icon.
     */
    public static final String MODE_SHORTCUT    = "SLICE_ICON";

    /**
     * Will select the type of slice binding based on size of the View. TODO: Put in some info about
     * that selection.
     */
    private static final String MODE_AUTO = "auto";

    private String mMode = MODE_AUTO;
    private SliceModeView mCurrentView;
    private final ActionRow mActions;
    private Slice mCurrentSlice;
    private boolean mShowActions = true;
    private boolean mIsScrollable;
    private SliceObserver mObserver;
    private final int mShortcutSize;

    public SliceView(Context context) {
        this(context, null);
    }

    public SliceView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SliceView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public SliceView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mObserver = new SliceObserver(new Handler(Looper.getMainLooper()));
        mActions = new ActionRow(mContext, true);
        mActions.setBackground(new ColorDrawable(0xffeeeeee));
        mCurrentView = new LargeTemplateView(mContext);
        addView(mCurrentView, getChildLp(mCurrentView));
        addView(mActions, getChildLp(mActions));
        mShortcutSize = getContext().getResources()
                .getDimensionPixelSize(R.dimen.slice_shortcut_size);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        measureChildren(widthMeasureSpec, heightMeasureSpec);
        int actionHeight = mActions.getVisibility() != View.GONE
                ? mActions.getMeasuredHeight()
                : 0;
        int newHeightSpec = MeasureSpec.makeMeasureSpec(
                mCurrentView.getMeasuredHeight() + actionHeight, MeasureSpec.EXACTLY);
        int width = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(width, newHeightSpec);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        mCurrentView.layout(l, t, l + mCurrentView.getMeasuredWidth(),
                t + mCurrentView.getMeasuredHeight());
        if (mActions.getVisibility() != View.GONE) {
            mActions.layout(l, mCurrentView.getMeasuredHeight(), l + mActions.getMeasuredWidth(),
                    mCurrentView.getMeasuredHeight() + mActions.getMeasuredHeight());
        }
    }

    /**
     * @hide
     */
    public void showSlice(Intent intent) {
        // TODO
    }

    /**
     * Populates this view with the {@link Slice} associated with the provided {@link Uri}. To use
     * this method your app must have the permission
     * {@link android.Manifest.permission#BIND_SLICE}).
     * <p>
     * Setting a slice differs from {@link #showSlice(Slice)} because it will ensure the view is
     * updated when the slice identified by the provided URI changes. The lifecycle of this observer
     * is handled by SliceView in {@link #onAttachedToWindow()} and {@link #onDetachedFromWindow()}.
     * To unregister this observer outside of that you can call {@link #clearSlice}.
     *
     * @return true if the a slice was found for the provided uri.
     * @see #clearSlice
     */
    public boolean setSlice(@NonNull Uri sliceUri) {
        Preconditions.checkNotNull(sliceUri,
                "Uri cannot be null, to remove the slice use clearSlice()");
        if (sliceUri == null) {
            clearSlice();
            return false;
        }
        validate(sliceUri);
        Slice s = Slice.bindSlice(mContext.getContentResolver(), sliceUri);
        if (s != null) {
            mObserver = new SliceObserver(new Handler(Looper.getMainLooper()));
            if (isAttachedToWindow()) {
                registerSlice(sliceUri);
            }
            showSlice(s);
        }
        return s != null;
    }

    /**
     * Populates this view to the provided {@link Slice}.
     * <p>
     * This does not register a content observer on the URI that the slice is backed by so it will
     * not update if the content changes. To have the view update when the content changes use
     * {@link #setSlice(Uri)} instead. Unlike {@link #setSlice(Uri)}, this method does not require
     * any special permissions.
     */
    public void showSlice(@NonNull Slice slice) {
        Preconditions.checkNotNull(slice,
                "Slice cannot be null, to remove the slice use clearSlice()");
        clearSlice();
        mCurrentSlice = slice;
        reinflate();
    }

    /**
     * Unregisters the change observer that is set when using {@link #setSlice}. Normally this is
     * done automatically during {@link #onDetachedFromWindow()}.
     * <p>
     * It is safe to call this method multiple times.
     */
    public void clearSlice() {
        mCurrentSlice = null;
        if (mObserver != null) {
            getContext().getContentResolver().unregisterContentObserver(mObserver);
            mObserver = null;
        }
    }

    /**
     * Set the mode this view should present in.
     */
    public void setMode(@SliceMode String mode) {
        setMode(mode, false /* animate */);
    }

    /**
     * Set whether this view should allow scrollable content when presenting in {@link #MODE_LARGE}.
     */
    public void setScrollable(boolean isScrollable) {
        mIsScrollable = isScrollable;
        reinflate();
    }

    /**
     * @hide
     */
    public void setMode(@SliceMode String mode, boolean animate) {
        if (animate) {
            Log.e(TAG, "Animation not supported yet");
        }
        mMode = mode;
        reinflate();
    }

    /**
     * @return the mode this view is presenting in.
     */
    public @SliceMode String getMode() {
        if (mMode.equals(MODE_AUTO)) {
            return MODE_LARGE;
        }
        return mMode;
    }

    /**
     * @hide
     *
     * Whether this view should show a row of actions with it.
     */
    public void setShowActionRow(boolean show) {
        mShowActions = show;
        reinflate();
    }

    private SliceModeView createView(String mode) {
        switch (mode) {
            case MODE_SHORTCUT:
                return new ShortcutView(getContext());
            case MODE_SMALL:
                return new SmallTemplateView(getContext());
        }
        return new LargeTemplateView(getContext());
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        registerSlice(mCurrentSlice != null ? mCurrentSlice.getUri() : null);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mObserver != null) {
            getContext().getContentResolver().unregisterContentObserver(mObserver);
            mObserver = null;
        }
    }

    private void registerSlice(Uri sliceUri) {
        if (sliceUri == null || mObserver == null) {
            return;
        }
        mContext.getContentResolver().registerContentObserver(sliceUri,
                false /* notifyForDescendants */, mObserver);
    }

    private void reinflate() {
        if (mCurrentSlice == null) {
            return;
        }
        // TODO: Smarter mapping here from one state to the next.
        SliceItem color = SliceQuery.find(mCurrentSlice, SliceItem.TYPE_COLOR);
        List<SliceItem> items = mCurrentSlice.getItems();
        SliceItem actionRow = SliceQuery.find(mCurrentSlice, SliceItem.TYPE_SLICE,
                Slice.HINT_ACTIONS,
                Slice.HINT_ALT);
        String mode = getMode();
        if (!mode.equals(mCurrentView.getMode())) {
            removeAllViews();
            mCurrentView = createView(mode);
            addView(mCurrentView, getChildLp(mCurrentView));
            addView(mActions, getChildLp(mActions));
        }
        if (mode.equals(MODE_LARGE)) {
            ((LargeTemplateView) mCurrentView).setScrollable(mIsScrollable);
        }
        if (items.size() > 1 || (items.size() != 0 && items.get(0) != actionRow)) {
            mCurrentView.setVisibility(View.VISIBLE);
            mCurrentView.setSlice(mCurrentSlice);
        } else {
            mCurrentView.setVisibility(View.GONE);
        }

        boolean showActions = mShowActions && actionRow != null
                && !mode.equals(MODE_SHORTCUT);
        if (showActions) {
            mActions.setActions(actionRow, color);
            mActions.setVisibility(View.VISIBLE);
        } else {
            mActions.setVisibility(View.GONE);
        }
    }

    private LayoutParams getChildLp(View child) {
        if (child instanceof ShortcutView) {
            return new LayoutParams(mShortcutSize, mShortcutSize);
        } else {
            return new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        }
    }

    private static void validate(Uri sliceUri) {
        if (!ContentResolver.SCHEME_CONTENT.equals(sliceUri.getScheme())) {
            throw new RuntimeException("Invalid uri " + sliceUri);
        }
        if (sliceUri.getPathSegments().size() == 0) {
            throw new RuntimeException("Invalid uri " + sliceUri);
        }
    }

    private class SliceObserver extends ContentObserver {
        SliceObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            this.onChange(selfChange, null);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            Slice s = Slice.bindSlice(mContext.getContentResolver(), uri);
            mCurrentSlice = s;
            reinflate();
        }
    }
}
