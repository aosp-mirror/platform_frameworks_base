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

import com.android.internal.util.DataClass;

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
@DataClass(
        genHiddenConstructor = true,
        genAidl = false)
public final class FillContext implements Parcelable {

    /**
     * The id of the {@link FillRequest fill request} this context
     * corresponds to. This is useful to associate your custom client
     * state with every request to avoid reinterpreting the UI when saving
     * user data.
     */
    private final int mRequestId;

    /**
     * The screen content.
     */
    private final @NonNull AssistStructure mStructure;

    /**
     * The AutofillId of the view that triggered autofill.
     */
    private final @NonNull AutofillId mFocusedId;

    /**
     * Lookup table AutofillId->ViewNode to speed up {@link #findViewNodesByAutofillIds}
     * This is purely a cache and can be deleted at any time
     */
    private transient @Nullable ArrayMap<AutofillId, AssistStructure.ViewNode> mViewNodeLookupTable;


    @Override
    public String toString() {
        if (!sDebug)  return super.toString();

        return "FillContext [reqId=" + mRequestId + ", focusedId=" + mFocusedId + "]";
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



    // Code below generated by codegen v1.0.0.
    //
    // DO NOT MODIFY!
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/service/autofill/FillContext.java
    //
    // CHECKSTYLE:OFF Generated code

    /**
     * Creates a new FillContext.
     *
     * @param requestId
     *   The id of the {@link FillRequest fill request} this context
     *   corresponds to. This is useful to associate your custom client
     *   state with every request to avoid reinterpreting the UI when saving
     *   user data.
     * @param structure
     *   The screen content.
     * @param focusedId
     *   The AutofillId of the view that triggered autofill.
     * @hide
     */
    @DataClass.Generated.Member
    public FillContext(
            int requestId,
            @NonNull AssistStructure structure,
            @NonNull AutofillId focusedId) {
        this.mRequestId = requestId;
        this.mStructure = structure;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mStructure);
        this.mFocusedId = focusedId;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mFocusedId);

        // onConstructed(); // You can define this method to get a callback
    }

    /**
     * The id of the {@link FillRequest fill request} this context
     * corresponds to. This is useful to associate your custom client
     * state with every request to avoid reinterpreting the UI when saving
     * user data.
     */
    @DataClass.Generated.Member
    public int getRequestId() {
        return mRequestId;
    }

    /**
     * The screen content.
     */
    @DataClass.Generated.Member
    public @NonNull AssistStructure getStructure() {
        return mStructure;
    }

    /**
     * The AutofillId of the view that triggered autofill.
     */
    @DataClass.Generated.Member
    public @NonNull AutofillId getFocusedId() {
        return mFocusedId;
    }

    @Override
    @DataClass.Generated.Member
    public void writeToParcel(Parcel dest, int flags) {
        // You can override field parcelling by defining methods like:
        // void parcelFieldName(Parcel dest, int flags) { ... }

        dest.writeInt(mRequestId);
        dest.writeTypedObject(mStructure, flags);
        dest.writeTypedObject(mFocusedId, flags);
    }

    @Override
    @DataClass.Generated.Member
    public int describeContents() { return 0; }

    @DataClass.Generated.Member
    public static final @NonNull Parcelable.Creator<FillContext> CREATOR
            = new Parcelable.Creator<FillContext>() {
        @Override
        public FillContext[] newArray(int size) {
            return new FillContext[size];
        }

        @Override
        @SuppressWarnings({"unchecked", "RedundantCast"})
        public FillContext createFromParcel(Parcel in) {
            // You can override field unparcelling by defining methods like:
            // static FieldType unparcelFieldName(Parcel in) { ... }

            int requestId = in.readInt();
            AssistStructure structure = (AssistStructure) in.readTypedObject(AssistStructure.CREATOR);
            AutofillId focusedId = (AutofillId) in.readTypedObject(AutofillId.CREATOR);
            return new FillContext(
                    requestId,
                    structure,
                    focusedId);
        }
    };

    @DataClass.Generated(
            time = 1565152135263L,
            codegenVersion = "1.0.0",
            sourceFile = "frameworks/base/core/java/android/service/autofill/FillContext.java",
            inputSignatures = "private final  int mRequestId\nprivate final @android.annotation.NonNull android.app.assist.AssistStructure mStructure\nprivate final @android.annotation.NonNull android.view.autofill.AutofillId mFocusedId\nprivate transient @android.annotation.Nullable android.util.ArrayMap<android.view.autofill.AutofillId,android.app.assist.AssistStructure.ViewNode> mViewNodeLookupTable\npublic @java.lang.Override java.lang.String toString()\npublic @android.annotation.NonNull android.app.assist.AssistStructure.ViewNode[] findViewNodesByAutofillIds(android.view.autofill.AutofillId[])\nclass FillContext extends java.lang.Object implements [android.os.Parcelable]\n@com.android.internal.util.DataClass(genHiddenConstructor=true, genAidl=false)")
    @Deprecated
    private void __metadata() {}

}
