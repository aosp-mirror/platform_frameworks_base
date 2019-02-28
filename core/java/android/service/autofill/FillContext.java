/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static android.view.autofill.Helper.sDebug;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.assist.AssistStructure;
import android.app.assist.AssistStructure.ViewNode;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArrayMap;
import android.util.SparseIntArray;
import android.view.autofill.AutofillId;

import java.util.LinkedList;

/**
 * This class represents a context for each fill request made via {@link
 * AutofillService#onFillRequest(FillRequest, CancellationSignal, FillCallback)}.
 * It contains a snapshot of the UI state, the view ids that were returned by
 * the {@link AutofillService autofill service} as both required to trigger a save
 * and optional that can be saved, and the id of the corresponding {@link
 * FillRequest}.
 * <p>
 * This context allows you to inspect the values for the interesting views
 * in the context they appeared. Also a reference to the corresponding fill
 * request is useful to store meta-data in the client state bundle passed
 * to {@link FillResponse.Builder#setClientState(Bundle)} to avoid interpreting
 * the UI state again while saving.
 */
public final class FillContext implements Parcelable {
    private final int mRequestId;
    private final @NonNull AssistStructure mStructure;
    private final @NonNull AutofillId mFocusedId;

    /**
     * Lookup table AutofillId->ViewNode to speed up {@link #findViewNodesByAutofillIds}
     * This is purely a cache and can be deleted at any time
     */
    @Nullable private ArrayMap<AutofillId, AssistStructure.ViewNode> mViewNodeLookupTable;


    /** @hide */
    public FillContext(int requestId, @NonNull AssistStructure structure,
            @NonNull AutofillId autofillId) {
        mRequestId = requestId;
        mStructure = structure;
        mFocusedId = autofillId;
    }

    private FillContext(Parcel parcel) {
        this(parcel.readInt(), parcel.readParcelable(null), parcel.readParcelable(null));
    }

    /**
     * Gets the id of the {@link FillRequest fill request} this context
     * corresponds to. This is useful to associate your custom client
     * state with every request to avoid reinterpreting the UI when saving
     * user data.
     *
     * @return The request id.
     */
    public int getRequestId() {
        return mRequestId;
    }

    /**
     * @return The screen content.
     */
    @NonNull
    public AssistStructure getStructure() {
        return mStructure;
    }

    /**
     * @return the AutofillId of the view that triggered autofill.
     */
    @NonNull
    public AutofillId getFocusedId() {
        return mFocusedId;
    }

    @Override
    public String toString() {
        if (!sDebug)  return super.toString();

        return "FillContext [reqId=" + mRequestId + ", focusedId=" + mFocusedId + "]";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(mRequestId);
        parcel.writeParcelable(mStructure, flags);
        parcel.writeParcelable(mFocusedId, flags);
    }

    /**
     * Finds {@link ViewNode ViewNodes} that have the requested ids.
     *
     * @param ids The ids of the node to find.
     *
     * @return The nodes indexed in the same way as the ids.
     *
     * @hide
     */
    @NonNull public ViewNode[] findViewNodesByAutofillIds(@NonNull AutofillId[] ids) {
        final LinkedList<ViewNode> nodesToProcess = new LinkedList<>();
        final ViewNode[] foundNodes = new AssistStructure.ViewNode[ids.length];

        // Indexes of foundNodes that are not found yet
        final SparseIntArray missingNodeIndexes = new SparseIntArray(ids.length);

        for (int i = 0; i < ids.length; i++) {
            if (mViewNodeLookupTable != null) {
                int lookupTableIndex = mViewNodeLookupTable.indexOfKey(ids[i]);

                if (lookupTableIndex >= 0) {
                    foundNodes[i] = mViewNodeLookupTable.valueAt(lookupTableIndex);
                } else {
                    missingNodeIndexes.put(i, /* ignored */ 0);
                }
            } else {
                missingNodeIndexes.put(i, /* ignored */ 0);
            }
        }

        final int numWindowNodes = mStructure.getWindowNodeCount();
        for (int i = 0; i < numWindowNodes; i++) {
            nodesToProcess.add(mStructure.getWindowNodeAt(i).getRootViewNode());
        }

        while (missingNodeIndexes.size() > 0 && !nodesToProcess.isEmpty()) {
            final ViewNode node = nodesToProcess.removeFirst();

            for (int i = 0; i < missingNodeIndexes.size(); i++) {
                final int index = missingNodeIndexes.keyAt(i);
                final AutofillId id = ids[index];

                if (id.equals(node.getAutofillId())) {
                    foundNodes[index] = node;

                    if (mViewNodeLookupTable == null) {
                        mViewNodeLookupTable = new ArrayMap<>(ids.length);
                    }

                    mViewNodeLookupTable.put(id, node);

                    missingNodeIndexes.removeAt(i);
                    break;
                }
            }

            for (int i = 0; i < node.getChildCount(); i++) {
                nodesToProcess.addLast(node.getChildAt(i));
            }
        }

        // Remember which ids could not be resolved to not search for them again the next time
        for (int i = 0; i < missingNodeIndexes.size(); i++) {
            if (mViewNodeLookupTable == null) {
                mViewNodeLookupTable = new ArrayMap<>(missingNodeIndexes.size());
            }

            mViewNodeLookupTable.put(ids[missingNodeIndexes.keyAt(i)], null);
        }

        return foundNodes;
    }

    public static final Parcelable.Creator<FillContext> CREATOR =
            new Parcelable.Creator<FillContext>() {
        @Override
        @NonNull
        public FillContext createFromParcel(Parcel parcel) {
            return new FillContext(parcel);
        }

        @Override
        @NonNull
        public FillContext[] newArray(int size) {
            return new FillContext[size];
        }
    };
}
