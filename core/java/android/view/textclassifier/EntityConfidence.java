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
import android.util.ArrayMap;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Helper object for setting and getting entity scores for classified text.
 *
 * @param <T> the entity type.
 * @hide
 */
final class EntityConfidence<T> {

    private final ArrayMap<T, Float> mEntityConfidence = new ArrayMap<>();
    private final ArrayList<T> mSortedEntities = new ArrayList<>();

    EntityConfidence() {}

    EntityConfidence(@NonNull EntityConfidence<T> source) {
        Preconditions.checkNotNull(source);
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
    EntityConfidence(@NonNull Map<T, Float> source) {
        Preconditions.checkNotNull(source);

        // Prune non-existent entities and clamp to 1.
        mEntityConfidence.ensureCapacity(source.size());
        for (Map.Entry<T, Float> it : source.entrySet()) {
            if (it.getValue() <= 0) continue;
            mEntityConfidence.put(it.getKey(), Math.min(1, it.getValue()));
        }

        // Create a list of entities sorted by decreasing confidence for getEntities().
        mSortedEntities.ensureCapacity(mEntityConfidence.size());
        mSortedEntities.addAll(mEntityConfidence.keySet());
        mSortedEntities.sort((e1, e2) -> {
            float score1 = mEntityConfidence.get(e1);
            float score2 = mEntityConfidence.get(e2);
            return Float.compare(score2, score1);
        });
    }

    /**
     * Returns an immutable list of entities found in the classified text ordered from
     * high confidence to low confidence.
     */
    @NonNull
    public List<T> getEntities() {
        return Collections.unmodifiableList(mSortedEntities);
    }

    /**
     * Returns the confidence score for the specified entity. The value ranges from
     * 0 (low confidence) to 1 (high confidence). 0 indicates that the entity was not found for the
     * classified text.
     */
    @FloatRange(from = 0.0, to = 1.0)
    public float getConfidenceScore(T entity) {
        if (mEntityConfidence.containsKey(entity)) {
            return mEntityConfidence.get(entity);
        }
        return 0;
    }

    @Override
    public String toString() {
        return mEntityConfidence.toString();
    }
}
