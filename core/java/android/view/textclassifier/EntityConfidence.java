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

package android.view.textclassifier;

import android.annotation.FloatRange;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArrayMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Helper object for setting and getting entity scores for classified text.
 *
 * @hide
 */
final class EntityConfidence implements Parcelable {

    private final ArrayMap<String, Float> mEntityConfidence = new ArrayMap<>();
    private final ArrayList<String> mSortedEntities = new ArrayList<>();

    EntityConfidence() {}

    EntityConfidence(@NonNull EntityConfidence source) {
        Objects.requireNonNull(source);
        mEntityConfidence.putAll(source.mEntityConfidence);
        mSortedEntities.addAll(source.mSortedEntities);
    }

    /**
     * Constructs an EntityConfidence from a map of entity to confidence.
     *
     * Map entries that have 0 confidence are removed, and values greater than 1 are clamped to 1.
     *
     * @param source a map from entity to a confidence value in the range 0 (low confidence) to
     *               1 (high confidence).
     */
    EntityConfidence(@NonNull Map<String, Float> source) {
        Objects.requireNonNull(source);

        // Prune non-existent entities and clamp to 1.
        mEntityConfidence.ensureCapacity(source.size());
        for (Map.Entry<String, Float> it : source.entrySet()) {
            if (it.getValue() <= 0) continue;
            mEntityConfidence.put(it.getKey(), Math.min(1, it.getValue()));
        }
        resetSortedEntitiesFromMap();
    }

    /**
     * Returns an immutable list of entities found in the classified text ordered from
     * high confidence to low confidence.
     */
    @NonNull
    public List<String> getEntities() {
        return Collections.unmodifiableList(mSortedEntities);
    }

    /**
     * Returns the confidence score for the specified entity. The value ranges from
     * 0 (low confidence) to 1 (high confidence). 0 indicates that the entity was not found for the
     * classified text.
     */
    @FloatRange(from = 0.0, to = 1.0)
    public float getConfidenceScore(String entity) {
        if (mEntityConfidence.containsKey(entity)) {
            return mEntityConfidence.get(entity);
        }
        return 0;
    }

    public Map<String, Float> toMap() {
        return new ArrayMap(mEntityConfidence);
    }

    @Override
    public String toString() {
        return mEntityConfidence.toString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mEntityConfidence.size());
        for (Map.Entry<String, Float> entry : mEntityConfidence.entrySet()) {
            dest.writeString(entry.getKey());
            dest.writeFloat(entry.getValue());
        }
    }

    public static final @android.annotation.NonNull Parcelable.Creator<EntityConfidence> CREATOR =
            new Parcelable.Creator<EntityConfidence>() {
                @Override
                public EntityConfidence createFromParcel(Parcel in) {
                    return new EntityConfidence(in);
                }

                @Override
                public EntityConfidence[] newArray(int size) {
                    return new EntityConfidence[size];
                }
            };

    private EntityConfidence(Parcel in) {
        final int numEntities = in.readInt();
        mEntityConfidence.ensureCapacity(numEntities);
        for (int i = 0; i < numEntities; ++i) {
            mEntityConfidence.put(in.readString(), in.readFloat());
        }
        resetSortedEntitiesFromMap();
    }

    private void resetSortedEntitiesFromMap() {
        mSortedEntities.clear();
        mSortedEntities.ensureCapacity(mEntityConfidence.size());
        mSortedEntities.addAll(mEntityConfidence.keySet());
        mSortedEntities.sort((e1, e2) -> {
            float score1 = mEntityConfidence.get(e1);
            float score2 = mEntityConfidence.get(e2);
            return Float.compare(score2, score1);
        });
    }
}
