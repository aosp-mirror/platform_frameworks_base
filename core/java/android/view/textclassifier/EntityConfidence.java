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

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Helper object for setting and getting entity scores for classified text.
 *
 * @param <T> the entity type.
 * @hide
 */
final class EntityConfidence<T> {

    private final Map<T, Float> mEntityConfidence = new HashMap<>();

    private final Comparator<T> mEntityComparator = (e1, e2) -> {
        float score1 = mEntityConfidence.get(e1);
        float score2 = mEntityConfidence.get(e2);
        if (score1 > score2) {
            return -1;
        }
        if (score1 < score2) {
            return 1;
        }
        return 0;
    };

    EntityConfidence() {}

    EntityConfidence(@NonNull EntityConfidence<T> source) {
        Preconditions.checkNotNull(source);
        mEntityConfidence.putAll(source.mEntityConfidence);
    }

    /**
     * Sets an entity type for the classified text and assigns a confidence score.
     *
     * @param confidenceScore a value from 0 (low confidence) to 1 (high confidence).
     *      0 implies the entity does not exist for the classified text.
     *      Values greater than 1 are clamped to 1.
     */
    public void setEntityType(
            @NonNull T type, @FloatRange(from = 0.0, to = 1.0) float confidenceScore) {
        Preconditions.checkNotNull(type);
        if (confidenceScore > 0) {
            mEntityConfidence.put(type, Math.min(1, confidenceScore));
        } else {
            mEntityConfidence.remove(type);
        }
    }

    /**
     * Returns an immutable list of entities found in the classified text ordered from
     * high confidence to low confidence.
     */
    @NonNull
    public List<T> getEntities() {
        List<T> entities = new ArrayList<>(mEntityConfidence.size());
        entities.addAll(mEntityConfidence.keySet());
        entities.sort(mEntityComparator);
        return Collections.unmodifiableList(entities);
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
