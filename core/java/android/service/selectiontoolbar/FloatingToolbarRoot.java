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
import android.graphics.Rect;
import android.os.IBinder;
import android.util.Log;
import android.view.MotionEvent;
import android.widget.LinearLayout;

import java.io.PrintWriter;

/**
 * This class is the root view for the selection toolbar. It is responsible for
 * detecting the click on the item and to also transfer input focus to the application.
 *
 * @hide
 */
@SuppressLint("ViewConstructor")
public class FloatingToolbarRoot extends LinearLayout {

    private static final boolean DEBUG = false;
    private static final String TAG = "FloatingToolbarRoot";

    private final IBinder mTargetInputToken;
    private final SelectionToolbarRenderService.TransferTouchListener mTransferTouchListener;
    private final Rect mContentRect = new Rect();

    private int mLastDownX = -1;
    private int mLastDownY = -1;

    public FloatingToolbarRoot(Context context, IBinder targetInputToken,
            SelectionToolbarRenderService.TransferTouchListener transferTouchListener) {
        super(context);
        mTargetInputToken = targetInputToken;
        mTransferTouchListener = transferTouchListener;
        setFocusable(false);
    }

    /**
     * Sets the Rect that shows the selection toolbar content.
     */
    public void setContentRect(Rect contentRect) {
        mContentRect.set(contentRect);
    }

    @Override
    @SuppressLint("ClickableViewAccessibility")
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mLastDownX = (int) event.getX();
            mLastDownY = (int) event.getY();
            if (DEBUG) {
                Log.d(TAG, "downX=" + mLastDownX + " downY=" + mLastDownY);
            }
            // TODO(b/215497659): Check FLAG_WINDOW_IS_PARTIALLY_OBSCURED
            if (!mContentRect.contains(mLastDownX, mLastDownY)) {
                if (DEBUG) {
                    Log.d(TAG, "Transfer touch focus to application.");
                }
                mTransferTouchListener.onTransferTouch(getViewRootImpl().getInputToken(),
                        mTargetInputToken);
            }
        }
        return super.dispatchTouchEvent(event);
    }

    void dump(String prefix, PrintWriter pw) {
        pw.print(prefix); pw.println("FloatingToolbarRoot:");
        pw.print(prefix + "  "); pw.print("last down X: "); pw.println(mLastDownX);
        pw.print(prefix + "  "); pw.print("last down Y: "); pw.println(mLastDownY);
    }
}
