/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.documentsui.dirlist;

import android.annotation.ColorInt;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Rect;
import android.support.annotation.Nullable;
import android.support.v7.widget.RecyclerView;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import com.android.documentsui.Events;
import com.android.documentsui.R;
import com.android.documentsui.State;

public abstract class DocumentHolder
        extends RecyclerView.ViewHolder
        implements View.OnKeyListener {

    static final float DISABLED_ALPHA = 0.3f;

    public @Nullable String modelId;

    final Context mContext;
    final @ColorInt int mDefaultBgColor;
    final @ColorInt int mSelectedBgColor;

    DocumentHolder.EventListener mEventListener;
    private View.OnKeyListener mKeyListener;
    private View mSelectionHotspot;


    public DocumentHolder(Context context, ViewGroup parent, int layout) {
        this(context, inflateLayout(context, parent, layout));
    }

    public DocumentHolder(Context context, View item) {
        super(item);

        itemView.setOnKeyListener(this);

        mContext = context;

        mDefaultBgColor = context.getColor(R.color.item_doc_background);
        mSelectedBgColor = context.getColor(R.color.item_doc_background_selected);

        mSelectionHotspot = itemView.findViewById(R.id.icon_check);
    }

    /**
     * Binds the view to the given item data.
     * @param cursor
     * @param modelId
     * @param state
     */
    public abstract void bind(Cursor cursor, String modelId, State state);

    /**
     * Makes the associated item view appear selected. Note that this merely affects the appearance
     * of the view, it doesn't actually select the item.
     * TODO: Use the DirectoryItemAnimator instead of manually controlling animation using a boolean
     * flag.
     *
     * @param selected
     * @param animate Whether or not to animate the change. Only selection changes initiated by the
     *            selection manager should be animated. See
     *            {@link ModelBackedDocumentsAdapter#onBindViewHolder(DocumentHolder, int, java.util.List)}
     */
    public void setSelected(boolean selected, boolean animate) {
        // Note: the animate param doesn't apply for this base implementation, because the
        // DirectoryItemAnimator takes care of it. It's required by subclasses, which perform their
        // own animation.
        itemView.setActivated(selected);
        itemView.setBackgroundColor(selected ? mSelectedBgColor : mDefaultBgColor);
    }

    /**
     * Highlights the associated item view.
     * @param highlighted
     */
    public void setHighlighted(boolean highlighted) {
        itemView.setBackgroundColor(highlighted ? mSelectedBgColor : mDefaultBgColor);
    }

    public void setEnabled(boolean enabled) {
        setEnabledRecursive(itemView, enabled);
    }

    @Override
    public boolean onKey(View v, int keyCode, KeyEvent event) {
        // Event listener should always be set.
        assert(mEventListener != null);

        return mEventListener.onKey(this,  keyCode,  event);
    }

    public void addEventListener(DocumentHolder.EventListener listener) {
        // Just handle one for now; switch to a list if necessary.
        assert(mEventListener == null);
        mEventListener = listener;
    }

    public void addOnKeyListener(View.OnKeyListener listener) {
        // Just handle one for now; switch to a list if necessary.
        assert(mKeyListener == null);
        mKeyListener = listener;
    }

    public boolean onSingleTapUp(MotionEvent event) {
        if (Events.isMouseEvent(event)) {
            // Mouse clicks select.
            // TODO:  && input.isPrimaryButtonPressed(), but it is returning false.
            if (mEventListener != null) {
                return mEventListener.onSelect(this);
            }
        } else if (Events.isTouchEvent(event)) {
            // Touch events select if they occur in the selection hotspot, otherwise they activate.
            if (mEventListener == null) {
                return false;
            }

            // Do everything in global coordinates - it makes things simpler.
            int[] coords = new int[2];
            mSelectionHotspot.getLocationOnScreen(coords);
            Rect rect = new Rect(coords[0], coords[1], coords[0] + mSelectionHotspot.getWidth(),
                    coords[1] + mSelectionHotspot.getHeight());

            // If the tap occurred within the icon rect, consider it a selection.
            if (rect.contains((int) event.getRawX(), (int) event.getRawY())) {
                return mEventListener.onSelect(this);
            } else {
                return mEventListener.onActivate(this);
            }
        }
        return false;
    }

    static void setEnabledRecursive(View itemView, boolean enabled) {
        if (itemView == null) return;
        if (itemView.isEnabled() == enabled) return;
        itemView.setEnabled(enabled);

        if (itemView instanceof ViewGroup) {
            final ViewGroup vg = (ViewGroup) itemView;
            for (int i = vg.getChildCount() - 1; i >= 0; i--) {
                setEnabledRecursive(vg.getChildAt(i), enabled);
            }
        }
    }

    private static View inflateLayout(Context context, ViewGroup parent, int layout) {
        final LayoutInflater inflater = LayoutInflater.from(context);
        return inflater.inflate(layout, parent, false);
    }

    /**
     * Implement this in order to be able to respond to events coming from DocumentHolders.
     */
    interface EventListener {
        /**
         * Handles activation events on the document holder.
         *
         * @param doc The target DocumentHolder
         * @return Whether the event was handled.
         */
        public boolean onActivate(DocumentHolder doc);

        /**
         * Handles selection events on the document holder.
         *
         * @param doc The target DocumentHolder
         * @return Whether the event was handled.
         */
        public boolean onSelect(DocumentHolder doc);

        /**
         * Handles key events on the document holder.
         *
         * @param doc The target DocumentHolder.
         * @param keyCode Key code for the event.
         * @param event KeyEvent for the event.
         * @return Whether the event was handled.
         */
        public boolean onKey(DocumentHolder doc, int keyCode, KeyEvent event);
    }
}
