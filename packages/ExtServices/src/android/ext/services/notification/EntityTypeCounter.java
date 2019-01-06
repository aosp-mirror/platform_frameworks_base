/**
 * Copyright (C) 2018 The Android Open Source Project
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
package android.ext.services.notification;

import android.annotation.NonNull;
import android.util.ArrayMap;
import android.view.textclassifier.TextClassifier;
import android.view.textclassifier.TextLinks;

/**
 * Counts the entity types for smart actions. Some entity types are considered the same
 * type, like {@link TextClassifier#TYPE_DATE} and {@link TextClassifier#TYPE_DATE_TIME}.
 */
class EntityTypeCounter {

    private static final ArrayMap<String, String> ENTITY_TYPE_MAPPING = new ArrayMap<>();

    static {
        ENTITY_TYPE_MAPPING.put(TextClassifier.TYPE_DATE_TIME, TextClassifier.TYPE_DATE);
    }

    private final ArrayMap<String, Integer> mEntityTypeCount = new ArrayMap<>();


    void increment(@NonNull String entityType) {
        entityType = convertToBaseEntityType(entityType);
        if (mEntityTypeCount.containsKey(entityType)) {
            mEntityTypeCount.put(entityType, mEntityTypeCount.get(entityType) + 1);
        } else {
            mEntityTypeCount.put(entityType, 1);
        }
    }

    int getCount(@NonNull String entityType) {
        entityType = convertToBaseEntityType(entityType);
        return mEntityTypeCount.getOrDefault(entityType, 0);
    }

    @NonNull
    private String convertToBaseEntityType(@NonNull String entityType) {
        return ENTITY_TYPE_MAPPING.getOrDefault(entityType, entityType);
    }

    /**
     * Given the links extracted from a piece of text, returns the frequency of each entity
     * type.
     */
    @NonNull
    static EntityTypeCounter fromTextLinks(@NonNull TextLinks links) {
        EntityTypeCounter counter = new EntityTypeCounter();
        for (TextLinks.TextLink link : links.getLinks()) {
            if (link.getEntityCount() == 0) {
                continue;
            }
            String entityType = link.getEntity(0);
            counter.increment(entityType);
        }
        return counter;
    }
}
