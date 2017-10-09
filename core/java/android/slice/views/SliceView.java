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

package android.slice.views;

import android.annotation.StringDef;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.slice.Slice;
import android.slice.SliceItem;
import android.slice.SliceQuery;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/**
 * A view that can display a {@link Slice} in different {@link SliceMode}'s.
 *
 * @hide
 */
public class SliceView extends LinearLayout {

    private static final String TAG = "SliceView";

    /**
     * @hide
     */
    public abstract static class SliceModeView extends FrameLayout {

        public SliceModeView(Context context) {
            super(context);
        }

        /**
         * @return the {@link SliceMode} of the slice being presented.
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

    /**
     * Simple constructor to create a slice view from code.
     *
     * @param context The context the view is running in.
     */
    public SliceView(Context context) {
        super(context);
        setOrientation(LinearLayout.VERTICAL);
        mActions = new ActionRow(mContext, true);
        mActions.setBackground(new ColorDrawable(0xffeeeeee));
        mCurrentView = new LargeTemplateView(mContext);
        addView(mCurrentView);
        addView(mActions);
    }

    /**
     * @hide
     */
    public void bindSlice(Intent intent) {
        // TODO
    }

    /**
     * Binds this view to the {@link Slice} associated with the provided {@link Uri}.
     */
    public void bindSlice(Uri sliceUri) {
        validate(sliceUri);
        Slice s = mContext.getContentResolver().bindSlice(sliceUri);
        bindSlice(s);
    }

    /**
     * Binds this view to the provided {@link Slice}.
     */
    public void bindSlice(Slice slice) {
        mCurrentSlice = slice;
        if (mCurrentSlice != null) {
            reinflate();
        }
    }

    /**
     * Call to clean up the view.
     */
    public void unbindSlice() {
        mCurrentSlice = null;
    }

    /**
     * Set the {@link SliceMode} this view should present in.
     */
    public void setMode(@SliceMode String mode) {
        setMode(mode, false /* animate */);
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
     * @return the {@link SliceMode} this view is presenting in.
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
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        unbindSlice();
    }

    private void reinflate() {
        if (mCurrentSlice == null) {
            return;
        }
        // TODO: Smarter mapping here from one state to the next.
        SliceItem color = SliceQuery.find(mCurrentSlice, SliceItem.TYPE_COLOR);
        SliceItem[] items = mCurrentSlice.getItems();
        SliceItem actionRow = SliceQuery.find(mCurrentSlice, SliceItem.TYPE_SLICE,
                Slice.HINT_ACTIONS,
                Slice.HINT_ALT);
        String mode = getMode();
        if (!mode.equals(mCurrentView.getMode())) {
            removeAllViews();
            mCurrentView = createView(mode);
            addView(mCurrentView);
            addView(mActions);
        }
        if (items.length > 1 || (items.length != 0 && items[0] != actionRow)) {
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

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        // TODO -- may need to rethink for AGSA
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            requestDisallowInterceptTouchEvent(true);
        }
        return super.onInterceptTouchEvent(ev);
    }

    private static void validate(Uri sliceUri) {
        if (!ContentResolver.SCHEME_SLICE.equals(sliceUri.getScheme())) {
            throw new RuntimeException("Invalid uri " + sliceUri);
        }
        if (sliceUri.getPathSegments().size() == 0) {
            throw new RuntimeException("Invalid uri " + sliceUri);
        }
    }
}
