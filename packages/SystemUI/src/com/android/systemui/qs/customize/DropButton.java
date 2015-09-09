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
package com.android.systemui.qs.customize;

import android.content.ClipData;
import android.content.Context;
import android.util.AttributeSet;
import android.view.DragEvent;
import android.view.View;
import android.view.View.OnDragListener;
import android.widget.TextView;

public class DropButton extends TextView implements OnDragListener {

    private OnDropListener mListener;

    public DropButton(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // TODO: Don't do this, instead make this view the right size...
        ((View) getParent()).setOnDragListener(this);
    }

    public void setOnDropListener(OnDropListener listener) {
        mListener = listener;
    }

    private void setHovering(boolean hovering) {
        setAlpha(hovering ? .5f : 1);
    }

    @Override
    public boolean onDrag(View v, DragEvent event) {
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_ENTERED:
                setHovering(true);
                break;
            case DragEvent.ACTION_DROP:
                if (mListener != null) {
                    mListener.onDrop(this, event.getClipData());
                }
            case DragEvent.ACTION_DRAG_EXITED:
            case DragEvent.ACTION_DRAG_ENDED:
                setHovering(false);
                break;
        }
        return true;
    }

    public interface OnDropListener {
        void onDrop(View v, ClipData data);
    }
}
