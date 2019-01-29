/*
 * Copyright (C) 2019 The Android Open Source Project
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
 * limitations under the License
 */

package android.view;

import static android.view.InsetsState.TYPE_IME;

import android.os.Parcel;
import android.text.TextUtils;
import android.view.SurfaceControl.Transaction;
import android.view.WindowInsets.Type;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Arrays;
import java.util.function.Supplier;

/**
 * Controls the visibility and animations of IME window insets source.
 * @hide
 */
public final class ImeInsetsSourceConsumer extends InsetsSourceConsumer {
    private EditorInfo mFocusedEditor;
    private EditorInfo mPreRenderedEditor;
    /**
     * Determines if IME would be shown next time IME is pre-rendered for currently focused
     * editor {@link #mFocusedEditor} if {@link #isServedEditorRendered} is {@code true}.
     */
    private boolean mShowOnNextImeRender;
    private boolean mHasWindowFocus;

    public ImeInsetsSourceConsumer(
            InsetsState state, Supplier<Transaction> transactionSupplier,
            InsetsController controller) {
        super(TYPE_IME, state, transactionSupplier, controller);
    }

    public void onPreRendered(EditorInfo info) {
        mPreRenderedEditor = info;
        if (mShowOnNextImeRender) {
            mShowOnNextImeRender = false;
            if (isServedEditorRendered()) {
                applyImeVisibility(true /* setVisible */);
            }
        }
    }

    public void onServedEditorChanged(EditorInfo info) {
        if (isDummyOrEmptyEditor(info)) {
            mShowOnNextImeRender = false;
        }
        mFocusedEditor = info;
    }

    public void applyImeVisibility(boolean setVisible) {
        if (!mHasWindowFocus) {
            // App window doesn't have focus, any visibility changes would be no-op.
            return;
        }

        if (setVisible) {
            mController.show(Type.IME);
        } else {
            mController.hide(Type.IME);
        }
    }

    @Override
    public void onWindowFocusGained() {
        mHasWindowFocus = true;
        getImm().registerImeConsumer(this);
    }

    @Override
    public void onWindowFocusLost() {
        mHasWindowFocus = false;
    }

    private boolean isDummyOrEmptyEditor(EditorInfo info) {
        // TODO(b/123044812): Handle dummy input gracefully in IME Insets API
        return info == null || (info.fieldId <= 0 && info.inputType <= 0);
    }

    private boolean isServedEditorRendered() {
        if (mFocusedEditor == null || mPreRenderedEditor == null
                || isDummyOrEmptyEditor(mFocusedEditor)
                || isDummyOrEmptyEditor(mPreRenderedEditor)) {
            // No view is focused or ready.
            return false;
        }
        return areEditorsSimilar(mFocusedEditor, mPreRenderedEditor);
    }

    @VisibleForTesting
    public static boolean areEditorsSimilar(EditorInfo info1, EditorInfo info2) {
        // We don't need to compare EditorInfo.fieldId (View#id) since that shouldn't change
        // IME views.
        boolean areOptionsSimilar =
                info1.imeOptions == info2.imeOptions
                && info1.inputType == info2.inputType
                && TextUtils.equals(info1.packageName, info2.packageName);
        areOptionsSimilar &= info1.privateImeOptions != null
                ? info1.privateImeOptions.equals(info2.privateImeOptions) : true;

        if (!areOptionsSimilar) {
            return false;
        }

        // compare bundle extras.
        if ((info1.extras == null && info2.extras == null) || info1.extras == info2.extras) {
            return true;
        }
        if ((info1.extras == null && info2.extras != null)
                || (info1.extras == null && info2.extras != null)) {
            return false;
        }
        if (info1.extras.hashCode() == info2.extras.hashCode()
                || info1.extras.equals(info1)) {
            return true;
        }
        if (info1.extras.size() != info2.extras.size()) {
            return false;
        }
        if (info1.extras.toString().equals(info2.extras.toString())) {
            return true;
        }

        // Compare bytes
        Parcel parcel1 = Parcel.obtain();
        info1.extras.writeToParcel(parcel1, 0);
        parcel1.setDataPosition(0);
        Parcel parcel2 = Parcel.obtain();
        info2.extras.writeToParcel(parcel2, 0);
        parcel2.setDataPosition(0);

        return Arrays.equals(parcel1.createByteArray(), parcel2.createByteArray());
    }

    private InputMethodManager getImm() {
        return mController.getViewRoot().mDisplayContext.getSystemService(InputMethodManager.class);
    }
}
