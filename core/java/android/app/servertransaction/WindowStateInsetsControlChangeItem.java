/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.app.servertransaction;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ClientTransactionHandler;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.Trace;
import android.util.Log;
import android.view.IWindow;
import android.view.InsetsSourceControl;
import android.view.InsetsState;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Objects;

/**
 * Message to deliver window insets control change info.
 *
 * @hide
 */
public class WindowStateInsetsControlChangeItem extends WindowStateTransactionItem {

    private static final String TAG = "WindowStateInsetsControlChangeItem";

    @NonNull
    private final InsetsState mInsetsState;

    @NonNull
    private final InsetsSourceControl.Array mActiveControls;

    public WindowStateInsetsControlChangeItem(@NonNull IWindow window,
            @NonNull InsetsState insetsState, @NonNull InsetsSourceControl.Array activeControls) {
        this(window, insetsState, activeControls, true /* copyActiveControls */);
    }

    @VisibleForTesting
    public WindowStateInsetsControlChangeItem(@NonNull IWindow window,
            @NonNull InsetsState insetsState,
            @NonNull InsetsSourceControl.Array activeControls, boolean copyActiveControls) {
        super(window);
        mInsetsState = new InsetsState(insetsState, true /* copySources */);
        if (copyActiveControls) {
            mActiveControls = copy(requireNonNull(activeControls));
        } else {
            mActiveControls = requireNonNull(activeControls);
        }
    }

    @NonNull
    private static InsetsSourceControl.Array copy(@NonNull InsetsSourceControl.Array controls) {
        final InsetsSourceControl.Array copiedControls = new InsetsSourceControl.Array(
                controls, true /* copyControls */);
        // This source control is an extra copy if the client is not local. By setting
        // PARCELABLE_WRITE_RETURN_VALUE, the leash will be released at the end of
        // SurfaceControl.writeToParcel.
        copiedControls.setParcelableFlags(PARCELABLE_WRITE_RETURN_VALUE);
        return copiedControls;
    }

    @Override
    public void execute(@NonNull ClientTransactionHandler client, @NonNull IWindow window,
            @NonNull PendingTransactionActions pendingActions) {
        Trace.traceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "windowInsetsControlChanged");
        try {
            window.insetsControlChanged(mInsetsState, mActiveControls);
        } catch (RemoteException e) {
            // Should be a local call.
            // An exception could happen if the process is restarted. It is safe to ignore since
            // the window should no longer exist.
            Log.w(TAG, "The original window no longer exists in the new process", e);
            // Prevent leak
            mActiveControls.release();
        }
        Trace.traceEnd(Trace.TRACE_TAG_WINDOW_MANAGER);
    }

    // Parcelable implementation

    /** Writes to Parcel. */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeTypedObject(mInsetsState, flags);
        dest.writeTypedObject(mActiveControls, flags);
    }

    /** Reads from Parcel. */
    private WindowStateInsetsControlChangeItem(@NonNull Parcel in) {
        super(in);
        mInsetsState = requireNonNull(in.readTypedObject(InsetsState.CREATOR));
        mActiveControls = requireNonNull(in.readTypedObject(InsetsSourceControl.Array.CREATOR));
    }

    public static final @NonNull Creator<WindowStateInsetsControlChangeItem> CREATOR =
            new Creator<>() {
                public WindowStateInsetsControlChangeItem createFromParcel(@NonNull Parcel in) {
                    return new WindowStateInsetsControlChangeItem(in);
                }

                public WindowStateInsetsControlChangeItem[] newArray(int size) {
                    return new WindowStateInsetsControlChangeItem[size];
                }
            };

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!super.equals(o)) {
            return false;
        }
        final WindowStateInsetsControlChangeItem other = (WindowStateInsetsControlChangeItem) o;
        return Objects.equals(mInsetsState, other.mInsetsState)
                && Objects.equals(mActiveControls, other.mActiveControls);
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + super.hashCode();
        result = 31 * result + Objects.hashCode(mInsetsState);
        result = 31 * result + Objects.hashCode(mActiveControls);
        return result;
    }

    @Override
    public String toString() {
        return "WindowStateInsetsControlChangeItem{" + super.toString() + "}";
    }
}
