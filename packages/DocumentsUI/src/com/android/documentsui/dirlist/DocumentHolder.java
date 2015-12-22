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

import static com.android.internal.util.Preconditions.checkState;

import android.content.Context;
import android.database.Cursor;
import android.support.annotation.Nullable;
import android.support.v7.widget.RecyclerView;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.android.documentsui.R;
import com.android.documentsui.State;

public abstract class DocumentHolder
        extends RecyclerView.ViewHolder
        implements View.OnKeyListener {

    public @Nullable String modelId;

    final int mSelectedItemColor;
    final int mDefaultItemColor;
    final boolean mAlwaysShowSummary;
    final Context mContext;

    private ListDocumentHolder.ClickListener mClickListener;
    private View.OnKeyListener mKeyListener;

    public DocumentHolder(Context context, ViewGroup parent, int layout) {
        this(context, inflateLayout(context, parent, layout));
    }

    public DocumentHolder(Context context, View item) {
        super(item);

        itemView.setOnKeyListener(this);

        mContext = context;

        mDefaultItemColor = context.getColor(R.color.item_doc_background);
        mSelectedItemColor = context.getColor(R.color.item_doc_background_selected);
        mAlwaysShowSummary = context.getResources().getBoolean(R.bool.always_show_summary);
    }

    /**
     * Binds the view to the given item data.
     * @param cursor
     * @param modelId
     * @param state
     */
    public abstract void bind(Cursor cursor, String modelId, State state);

    public void setSelected(boolean selected) {
        itemView.setActivated(selected);
        itemView.setBackgroundColor(selected ? mSelectedItemColor : mDefaultItemColor);
    }

    @Override
    public boolean onKey(View v, int keyCode, KeyEvent event) {
        // Intercept enter key-up events, and treat them as clicks.  Forward other events.
        if (event.getAction() == KeyEvent.ACTION_UP &&
                keyCode == KeyEvent.KEYCODE_ENTER) {
            if (mClickListener != null) {
                mClickListener.onClick(this);
            }
            return true;
        } else if (mKeyListener != null) {
            return mKeyListener.onKey(v, keyCode, event);
        }
        return false;
    }

    public void addClickListener(ListDocumentHolder.ClickListener listener) {
        // Just handle one for now; switch to a list if necessary.
        checkState(mClickListener == null);
        mClickListener = listener;
    }

    public void addOnKeyListener(View.OnKeyListener listener) {
        // Just handle one for now; switch to a list if necessary.
        checkState(mKeyListener == null);
        mKeyListener = listener;
    }

    public void setEnabled(boolean enabled) {
        setEnabledRecursive(itemView, enabled);
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

    interface ClickListener {
        public void onClick(DocumentHolder doc);
    }
}
