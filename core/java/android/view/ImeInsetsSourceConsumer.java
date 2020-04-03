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

import static android.view.InsetsController.AnimationType;
import static android.view.InsetsState.ITYPE_IME;

import android.annotation.Nullable;
import android.inputmethodservice.InputMethodService;
import android.os.Parcel;
import android.text.TextUtils;
import android.view.SurfaceControl.Transaction;
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

    /**
     * Tracks whether we have an outstanding request from the IME to show, but weren't able to
     * execute it because we didn't have control yet.
     */
    private boolean mImeRequestedShow;

    public ImeInsetsSourceConsumer(
            InsetsState state, Supplier<Transaction> transactionSupplier,
            InsetsController controller) {
        super(ITYPE_IME, state, transactionSupplier, controller);
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
        mController.applyImeVisibility(setVisible);
    }

    @Override
    public void onWindowFocusGained() {
        super.onWindowFocusGained();
        getImm().registerImeConsumer(this);
    }

    @Override
    public void onWindowFocusLost() {
        super.onWindowFocusLost();
        getImm().unregisterImeConsumer(this);
        mImeRequestedShow = false;
    }

    @Override
    public void show(boolean fromIme) {
        super.show(fromIme);
        if (fromIme) {
            mImeRequestedShow = true;
        }
    }

    @Override
    void hide(boolean animationFinished, @AnimationType int animationType) {
        super.hide();

        if (animationFinished) {
            // remove IME surface as IME has finished hide animation.
            notifyHidden();
            removeSurface();
        }
    }

    /**
     * Request {@link InputMethodManager} to show the IME.
     * @return @see {@link android.view.InsetsSourceConsumer.ShowResult}.
     */
    @Override
    public @ShowResult int requestShow(boolean fromIme) {
        // TODO: ResultReceiver for IME.
        // TODO: Set mShowOnNextImeRender to automatically show IME and guard it with a flag.

        // If we had a request before to show from IME (tracked with mImeRequestedShow), reaching
        // this code here means that we now got control, so we can start the animation immediately.
        // If client window is trying to control IME and IME is already visible, it is immediate.
        if (fromIme || mImeRequestedShow || mState.getSource(getType()).isVisible()) {
            mImeRequestedShow = false;
            return ShowResult.SHOW_IMMEDIATELY;
        }

        return getImm().requestImeShow(null /* resultReceiver */)
                ? ShowResult.IME_SHOW_DELAYED : ShowResult.IME_SHOW_FAILED;
    }

    /**
     * Notify {@link InputMethodService} that IME window is hidden.
     */
    @Override
    void notifyHidden() {
        getImm().notifyImeHidden();
    }

    @Override
    public void removeSurface() {
        getImm().removeImeSurface();
    }

    @Override
    public void setControl(@Nullable InsetsSourceControl control, int[] showTypes,
            int[] hideTypes) {
        super.setControl(control, showTypes, hideTypes);
        if (control == null) {
            hide();
        }
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
        return mController.getViewRoot().mContext.getSystemService(InputMethodManager.class);
    }
}
