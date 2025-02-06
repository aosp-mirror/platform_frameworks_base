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

package com.android.apps.inputmethod.simpleime;

import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Controls the visible virtual keyboard view. */
final class SimpleKeyboard {

    private static final String TAG = "SimpleKeyboard";

    private static final int[] SOFT_KEY_IDS = new int[]{
            R.id.key_pos_0_0,
            R.id.key_pos_0_1,
            R.id.key_pos_0_2,
            R.id.key_pos_0_3,
            R.id.key_pos_0_4,
            R.id.key_pos_0_5,
            R.id.key_pos_0_6,
            R.id.key_pos_0_7,
            R.id.key_pos_0_8,
            R.id.key_pos_0_9,
            R.id.key_pos_1_0,
            R.id.key_pos_1_1,
            R.id.key_pos_1_2,
            R.id.key_pos_1_3,
            R.id.key_pos_1_4,
            R.id.key_pos_1_5,
            R.id.key_pos_1_6,
            R.id.key_pos_1_7,
            R.id.key_pos_1_8,
            R.id.key_pos_2_0,
            R.id.key_pos_2_1,
            R.id.key_pos_2_2,
            R.id.key_pos_2_3,
            R.id.key_pos_2_4,
            R.id.key_pos_2_5,
            R.id.key_pos_2_6,
            R.id.key_pos_shift,
            R.id.key_pos_del,
            R.id.key_pos_symbol,
            R.id.key_pos_comma,
            R.id.key_pos_space,
            R.id.key_pos_period,
            R.id.key_pos_enter,
    };

    @NonNull
    private final SimpleInputMethodService mSimpleInputMethodService;
    private final int mViewResId;
    private final SparseArray<TextView> mSoftKeyViews = new SparseArray<>();
    private View mKeyboardView;
    private int mKeyboardState;

    SimpleKeyboard(@NonNull SimpleInputMethodService simpleInputMethodService, int viewResId) {
        mSimpleInputMethodService = simpleInputMethodService;
        mViewResId = viewResId;
        mKeyboardState = 0;
    }

    @NonNull
    View inflateKeyboardView(@NonNull LayoutInflater inflater, @NonNull ViewGroup inputView) {
        mKeyboardView = inflater.inflate(mViewResId, inputView, false);
        mapSoftKeys();
        return mKeyboardView;
    }

    private void mapSoftKeys() {
        for (int id : SOFT_KEY_IDS) {
            final TextView softKeyView = mKeyboardView.requireViewById(id);
            mSoftKeyViews.put(id, softKeyView);
            final var keyCodeName = softKeyView.getTag() != null
                    ? softKeyView.getTag().toString() : null;
            softKeyView.setOnClickListener(v -> handleKeyPress(keyCodeName));
        }
    }

    private void handleKeyPress(@Nullable String keyCodeName) {
        Log.i(TAG, "handle(): " + keyCodeName);
        if (TextUtils.isEmpty(keyCodeName)) {
            return;
        }
        if ("KEYCODE_SHIFT".equals(keyCodeName)) {
            handleShift();
            return;
        }

        mSimpleInputMethodService.handleKeyPress(keyCodeName, mKeyboardState);
    }

    private void handleShift() {
        mKeyboardState = toggleShiftState(mKeyboardState);
        Log.v(TAG, "currentKeyboardState: " + mKeyboardState);
        final boolean isShiftOn = isShiftOn(mKeyboardState);
        for (int i = 0; i < mSoftKeyViews.size(); i++) {
            TextView softKeyView = mSoftKeyViews.valueAt(i);
            softKeyView.setAllCaps(isShiftOn);
        }
    }

    private static boolean isShiftOn(int state) {
        return (state & KeyEvent.META_SHIFT_ON) == KeyEvent.META_SHIFT_ON;
    }

    private static int toggleShiftState(int state) {
        return state ^ KeyEvent.META_SHIFT_ON;
    }
}
