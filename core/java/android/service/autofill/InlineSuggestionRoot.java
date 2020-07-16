/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.service.autofill;

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.content.Context;
import android.os.RemoteException;
import android.util.Log;
import android.util.MathUtils;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/**
 * This class is the root view for an inline suggestion. It is responsible for
 * detecting the click on the item and to also transfer input focus to the IME
 * window if we detect the user is scrolling.
 *
 * @hide
 */
@SuppressLint("ViewConstructor")
public class InlineSuggestionRoot extends FrameLayout {
    private static final String TAG = "InlineSuggestionRoot";

    private final @NonNull IInlineSuggestionUiCallback mCallback;
    private final int mTouchSlop;

    private float mDownX;
    private float mDownY;

    public InlineSuggestionRoot(@NonNull Context context,
            @NonNull IInlineSuggestionUiCallback callback) {
        super(context);
        mCallback = callback;
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setFocusable(false);
    }

    @Override
    @SuppressLint("ClickableViewAccessibility")
    public boolean dispatchTouchEvent(@NonNull MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                mDownX = event.getX();
                mDownY = event.getY();
            }
            // Intentionally fall through to the next case so that when the window is obscured
            // we transfer the touch to the remote IME window and don't handle it locally.

            case MotionEvent.ACTION_MOVE: {
                final float distance = MathUtils.dist(mDownX, mDownY,
                        event.getX(), event.getY());
                final boolean isSecure = (event.getFlags()
                        & MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) == 0;
                if (!isSecure || distance > mTouchSlop) {
                    try {
                        mCallback.onTransferTouchFocusToImeWindow(getViewRootImpl().getInputToken(),
                                getContext().getDisplayId());
                    } catch (RemoteException e) {
                        Log.w(TAG, "RemoteException transferring touch focus to IME");
                    }
                }
            } break;
        }
        return super.dispatchTouchEvent(event);
    }
}
