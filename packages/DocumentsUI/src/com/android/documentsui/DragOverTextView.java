/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.documentsui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View.OnDragListener;
import android.widget.TextView;

/**
 * An {@link TextView} that uses drawable states to distinct between normal and highlighted states.
 */

public final class DragOverTextView extends TextView {
    private static final int[] STATE_HIGHLIGHTED = {R.attr.state_highlighted};

    private boolean mHighlighted = false;

    public DragOverTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected int[] onCreateDrawableState(int extraSpace) {
        final int[] drawableState = super.onCreateDrawableState(extraSpace + 1);

        if (mHighlighted) {
            mergeDrawableStates(drawableState, STATE_HIGHLIGHTED);
        }

        return drawableState;
    }

    public void setHighlight(boolean highlight) {
        mHighlighted = highlight;
        refreshDrawableState();
    }
}
