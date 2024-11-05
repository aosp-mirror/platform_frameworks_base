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

package android.view.autofill;

import static android.view.autofill.Helper.sDebug;

import android.annotation.NonNull;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;
import android.view.View;
import android.widget.TextView;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * This class manages and stores the autofillable views fingerprints for use in relayout situations.
 * @hide
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public final class AutofillStateFingerprint {

    ArrayList<AutofillId> mPriorAutofillIds;
    ArrayList<Integer> mViewHashCodes; // each entry corresponding to mPriorAutofillIds .

    boolean mHideHighlight = false;

    private int mSessionId;

    Map<Integer, AutofillId> mHashToAutofillIdMap = new ArrayMap<>();
    Map<AutofillId, AutofillId> mOldIdsToCurrentAutofillIdMap = new ArrayMap<>();

    // These failed id's are attempted to be refilled again after relayout.
    private ArrayList<AutofillId> mFailedIds = new ArrayList<>();
    private ArrayList<AutofillValue> mFailedAutofillValues = new ArrayList<>();

    // whether to use relative positions for computing hashes.
    private boolean mUseRelativePosition;

    private static final String TAG = "AutofillStateFingerprint";

    /**
     * Returns an instance of this class
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public static AutofillStateFingerprint createInstance() {
        return new AutofillStateFingerprint();
    }

    private AutofillStateFingerprint() {
    }

    /**
     * Set sessionId for the instance
     */
    void setSessionId(int sessionId) {
        mSessionId = sessionId;
    }

    /**
     * Sets whether relative position of the views should be used to calculate fingerprints.
     */
    void setUseRelativePosition(boolean useRelativePosition) {
        mUseRelativePosition = useRelativePosition;
    }

    /**
     * Store the state of the views prior to the authentication.
     */
    void storeStatePriorToAuthentication(
            AutofillManager.AutofillClient client, Set<AutofillId> autofillIds) {
        if (mUseRelativePosition) {
            List<View> autofillableViews = client.autofillClientFindAutofillableViewsByTraversal();
            if (sDebug) {
                Log.d(TAG, "Autofillable views count prior to auth:" + autofillableViews.size());
            }

            ArrayMap<Integer, View> hashes = getFingerprintIds(autofillableViews);
            for (Map.Entry<Integer, View> entry : hashes.entrySet()) {
                View view = entry.getValue();
                if (view != null) {
                    mHashToAutofillIdMap.put(entry.getKey(), view.getAutofillId());
                } else {
                    if (sDebug) {
                        Log.d(TAG, "Encountered null view");
                    }
                }
            }
        } else {
            // Just use the provided autofillIds and get their hashes
            if (sDebug) {
                Log.d(TAG, "Size of autofillId's being stored: " + autofillIds.size()
                        + " list:" + autofillIds);
            }
            AutofillId[] autofillIdsArr = Helper.toArray(autofillIds);
            View[] views = client.autofillClientFindViewsByAutofillIdTraversal(autofillIdsArr);
            for (int i = 0; i < autofillIdsArr.length; i++) {
                View view = views[i];
                if (view != null) {
                    int id = getEphemeralFingerprintId(view, 0 /* position irrelevant */);
                    AutofillId autofillId = view.getAutofillId();
                    mHashToAutofillIdMap.put(id, autofillId);
                } else {
                    if (sDebug) {
                        Log.d(TAG, "Encountered null view");
                    }
                }
            }
        }
    }

    /**
     * Store failed ids, so that they can be refilled later
     */
    void storeFailedIdsAndValues(
            @NonNull ArrayList<AutofillId> failedIds,
            ArrayList<AutofillValue> failedAutofillValues,
            boolean hideHighlight) {
        for (AutofillId failedId : failedIds) {
            if (failedId != null) {
                failedId.setSessionId(mSessionId);
            } else {
                if (sDebug) {
                    Log.d(TAG, "Got null failed ids");
                }
            }
        }
        mFailedIds = failedIds;
        mFailedAutofillValues = failedAutofillValues;
        mHideHighlight = hideHighlight;
    }

    private void dumpCurrentState() {
        Log.d(TAG, "FailedId's: " + mFailedIds);
        Log.d(TAG, "Hashes from map" + mHashToAutofillIdMap);
    }

    boolean attemptRefill(
            List<View> currentAutofillableViews, @NonNull AutofillManager autofillManager) {
        if (sDebug) {
            dumpCurrentState();
        }
        // For the autofillable views, compute their hashes
        ArrayMap<Integer, View> currentHashes = getFingerprintIds(currentAutofillableViews);

        // For the computed hashes, try to look for the old fingerprints.
        // If match found, update the new autofill ids of those views
        Map<AutofillId, View> oldFailedIdsToCurrentViewMap = new HashMap<>();
        for (Map.Entry<Integer, View> entry : currentHashes.entrySet()) {
            View view = entry.getValue();
            int currentHash = entry.getKey();
            AutofillId currentAutofillId = view.getAutofillId();
            currentAutofillId.setSessionId(mSessionId);
            if (mHashToAutofillIdMap.containsKey(currentHash)) {
                AutofillId oldAutofillId = mHashToAutofillIdMap.get(currentHash);
                oldAutofillId.setSessionId(mSessionId);
                mOldIdsToCurrentAutofillIdMap.put(oldAutofillId, currentAutofillId);
                Log.i(TAG, "Mapping current autofill id: " + view.getAutofillId()
                        + " to existing autofill id " + oldAutofillId);

                oldFailedIdsToCurrentViewMap.put(oldAutofillId, view);
            } else {
                Log.i(TAG, "Couldn't map current autofill id: " + view.getAutofillId()
                        + " with currentHash:" + currentHash + " for view:" + view);
            }
        }

        int viewsCount = 0;
        View[] views = new View[mFailedIds.size()];
        for (int i = 0; i < mFailedIds.size(); i++) {
            AutofillId oldAutofillId = mFailedIds.get(i);
            AutofillId currentAutofillId = mOldIdsToCurrentAutofillIdMap.get(oldAutofillId);
            if (currentAutofillId == null) {
                if (sDebug) {
                    Log.d(TAG, "currentAutofillId = null");
                }
            }
            mFailedIds.set(i, currentAutofillId);
            views[i] = oldFailedIdsToCurrentViewMap.get(oldAutofillId);
            if (views[i] != null) {
                viewsCount++;
            }
        }

        if (sDebug) {
            dumpCurrentState();
        }

        // Attempt autofill now
        Slog.i(TAG, "Attempting refill of views. Found " + viewsCount
                + " views to refill from previously " + mFailedIds.size()
                + " failed ids:" + mFailedIds);
        autofillManager.post(
                () -> autofillManager.autofill(
                        views, mFailedIds, mFailedAutofillValues, mHideHighlight,
                        true /* isRefill */));

        return false;
    }

    /**
     * Retrieves fingerprint hashes for the views
     */
    ArrayMap<Integer, View> getFingerprintIds(@NonNull List<View> views) {
        ArrayMap<Integer, View> map = new ArrayMap<>();
        if (mUseRelativePosition) {
            Collections.sort(views, (View v1, View v2) -> {
                int[] posV1 = v1.getLocationOnScreen();
                int[] posV2 = v2.getLocationOnScreen();

                int compare = posV1[0] - posV2[0]; // x coordinate
                if (compare != 0) {
                    return compare;
                }
                compare = posV1[1] - posV2[1]; // y coordinate
                if (compare != 0) {
                    return compare;
                }
                // Sort on vertical
                compare = compareTop(v1, v2);
                if (compare != 0) {
                    return compare;
                }
                compare = compareBottom(v1, v2);
                if (compare != 0) {
                    return compare;
                }
                compare = compareLeft(v1, v2);
                if (compare != 0) {
                    return compare;
                }
                return compareRight(v1, v2);
                // Note that if compareRight also returned 0, that means both the views have exact
                // same location, so just treat them as equal
            });
        }
        for (int i = 0; i < views.size(); i++) {
            View view = views.get(i);
            map.put(getEphemeralFingerprintId(view, i), view);
        }
        return map;
    }

    /**
     * Returns fingerprint hash for the view.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public int getEphemeralFingerprintId(View v, int position) {
        if (v == null) return -1;
        int inputType = Integer.MIN_VALUE;
        int imeOptions = Integer.MIN_VALUE;
        boolean isSingleLine = false;
        CharSequence hints = "";
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            inputType = tv.getInputType();
            hints = tv.getHint();
            isSingleLine = tv.isSingleLine();
            imeOptions = tv.getImeOptions();
            // TODO(b/238252288): Consider adding more IME related fields.
        }
        CharSequence contentDesc = v.getContentDescription();
        CharSequence tooltip = v.getTooltipText();

        int autofillType = v.getAutofillType();
        String[] autofillHints = v.getAutofillHints();
        int visibility = v.getVisibility();

        int paddingLeft = v.getPaddingLeft();
        int paddingRight = v.getPaddingRight();
        int paddingTop = v.getPaddingTop();
        int paddingBottom = v.getPaddingBottom();

        // TODO(b/238252288): Following are making relayout flaky. Do more analysis to figure out
        //  why.
        int height = v.getHeight();
        int width = v.getWidth();

        // Order doesn't matter much here. We can change the order, as long as we use the same
        // order for storing and fetching fingerprints. The order can be changed in platform
        // versions.
        int hash = Objects.hash(visibility, inputType, imeOptions, isSingleLine, hints,
                contentDesc, tooltip, autofillType, Arrays.deepHashCode(autofillHints),
                paddingBottom, paddingTop, paddingRight, paddingLeft);
        if (mUseRelativePosition) {
            hash = Objects.hash(hash, position);
        }
        if (sDebug) {
            Log.d(TAG, "Hash: " + hash + " for AutofillId:" + v.getAutofillId()
                    + " visibility:" + visibility
                    + " inputType:" + inputType
                    + " imeOptions:" + imeOptions
                    + " isSingleLine:" + isSingleLine
                    + " hints:" + hints
                    + " contentDesc:" + contentDesc
                    + " tooltipText:" + tooltip
                    + " autofillType:" + autofillType
                    + " autofillHints:" + Arrays.toString(autofillHints)
                    + " height:" + height
                    + " width:" + width
                    + " paddingLeft:" + paddingLeft
                    + " paddingRight:" + paddingRight
                    + " paddingTop:" + paddingTop
                    + " paddingBottom:" + paddingBottom
                    + " mUseRelativePosition" + mUseRelativePosition
                    + " position:" + position
            );
        }
        return hash;
    }

    private int compareTop(View v1, View v2) {
        return v1.getTop() - v2.getTop();
    }

    private int compareBottom(View v1, View v2) {
        return v1.getBottom() - v2.getBottom();
    }

    private int compareLeft(View v1, View v2) {
        return v1.getLeft() - v2.getLeft();
    }

    private int compareRight(View v1, View v2) {
        return v1.getRight() - v2.getRight();
    }
}
