/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.service.selectiontoolbar;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.IBinder;
import android.view.MotionEvent;
import android.widget.LinearLayout;

/**
 * This class is the root view for the selection toolbar. It is responsible for
 * detecting the click on the item and to also transfer input focus to the application.
 *
 * @hide
 */
@SuppressLint("ViewConstructor")
public class FloatingToolbarRoot extends LinearLayout {

    private final IBinder mTargetInputToken;
    private final SelectionToolbarRenderService.TransferTouchListener mTransferTouchListener;
    private float mDownX;
    private float mDownY;

    public FloatingToolbarRoot(Context context, IBinder targetInputToken,
            SelectionToolbarRenderService.TransferTouchListener transferTouchListener) {
        super(context);
        mTargetInputToken = targetInputToken;
        mTransferTouchListener = transferTouchListener;
        setFocusable(false);
    }

    @Override
    @SuppressLint("ClickableViewAccessibility")
    public boolean dispatchTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                mDownX = event.getX();
                mDownY = event.getY();
                // TODO: Check x, y if we need to transfer touch focus to application
                //mTransferTouchListener.onTransferTouch(getViewRootImpl().getInputToken(),
                //        mTargetInputToken);
            }
        }
        return super.dispatchTouchEvent(event);
    }
}
