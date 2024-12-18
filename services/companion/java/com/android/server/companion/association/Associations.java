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

package com.android.server.companion.association;

import android.companion.AssociationInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents associations per user. Should be only used by Association stores.
 */
public class Associations {

    private int mVersion = 0;

    private List<AssociationInfo> mAssociations = new ArrayList<>();

    private int mMaxId = 0;

    public Associations() {
    }

    public void setVersion(int version) {
        mVersion = version;
    }

    /**
     * Add an association.
     */
    public void addAssociation(AssociationInfo association) {
        mAssociations.add(association);
    }

    public void setMaxId(int maxId) {
        mMaxId = maxId;
    }

    public void setAssociations(List<AssociationInfo> associations) {
        mAssociations = List.copyOf(associations);
    }

    public int getVersion() {
        return mVersion;
    }

    public int getMaxId() {
        return mMaxId;
    }

    public List<AssociationInfo> getAssociations() {
        return mAssociations;
    }
}
