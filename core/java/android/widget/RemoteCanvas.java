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
package android.widget;

import static android.appwidget.flags.Flags.FLAG_DRAW_DATA_PARCEL;

import android.annotation.AttrRes;
import android.annotation.FlaggedApi;
import android.annotation.StyleRes;
import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.util.SparseArray;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.util.function.IntConsumer;

/**
 * {@link RemoteCanvas} is designed to support arbitrary protocols between two processes using
 * {@link RemoteViews.DrawInstructions}. Upon instantiation in the host process,
 * {@link RemoteCanvas#setDrawInstructions(RemoteViews.DrawInstructions)} is called so that the
 * host process can render the {@link RemoteViews.DrawInstructions} from the provider process
 * accordingly.
 *
 * @hide
 */
@FlaggedApi(FLAG_DRAW_DATA_PARCEL)
public class RemoteCanvas extends View {

    private static final String TAG = "RemoteCanvas";

    @Nullable
    private SparseArray<Runnable> mCallbacks;

    private final IntConsumer mOnClickHandler = (viewId) -> {
        if (mCallbacks == null) {
            Log.w(TAG, "Cannot find callback for " + viewId
                    + ", in fact there were no callbacks from this RemoteViews at all.");
            return;
        }
        final Runnable cb = getCallbacks().get(viewId);
        if (cb != null) {
            cb.run();
        } else {
            Log.w(TAG, "Cannot find callback for " + viewId);
        }
    };

    RemoteCanvas(@NonNull Context context) {
        super(context);
    }

    RemoteCanvas(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    RemoteCanvas(@NonNull Context context, @Nullable AttributeSet attrs,
                 @AttrRes int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    RemoteCanvas(@NonNull Context context, @Nullable AttributeSet attrs,
                 @AttrRes int defStyleAttr, @StyleRes int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    /**
     * Setter method for the {@link RemoteViews.DrawInstructions} from the provider process for
     * the host process to render accordingly.
     *
     * @param instructions {@link RemoteViews.DrawInstructions} from the provider process.
     */
    void setDrawInstructions(@NonNull final RemoteViews.DrawInstructions instructions) {
        setTag(instructions);
        // TODO: handle draw instructions
        // TODO: attach mOnClickHandler
    }

    /**
     * Adds a callback function to a clickable area in the RemoteCanvas.
     *
     * @param viewId the viewId of the clickable area
     * @param cb the callback function to be triggered when clicked
     */
    void addOnClickHandler(final int viewId, @NonNull final Runnable cb) {
        getCallbacks().set(viewId, cb);
    }

    /**
     * Returns all callbacks added to the RemoteCanvas through
     * {@link #addOnClickHandler(int, Runnable)}.
     */
    @VisibleForTesting
    public SparseArray<Runnable> getCallbacks() {
        if (mCallbacks == null) {
            mCallbacks = new SparseArray<>();
        }
        return mCallbacks;
    }
}
