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

package com.android.statementservice.retriever;

import android.annotation.Nullable;

import java.util.Map;

/**
 * A immutable value type representing a dynamic app link component
 */
public final class DynamicAppLinkComponent {
    private final boolean mExclude;
    private final String mFragment;
    private final String mPath;
    private final Map<String, String> mQuery;
    private final String mComments;

    private DynamicAppLinkComponent(boolean exclude, String fragment, String path,
                                    Map<String, String> query, String comments) {
        mExclude = exclude;
        mFragment = fragment;
        mPath = path;
        mQuery = query;
        mComments = comments;
    }

    /**
     * Returns true or false indicating whether this rule should be a exclusion rule.
     */
    public boolean getExclude() {
        return mExclude;
    }

    /**
     * Returns a optional pattern string for matching URL fragments.
     */
    @Nullable
    public String getFragment() {
        return mFragment;
    }

    /**
     * Returns a optional pattern string for matching URL paths.
     */
    @Nullable
    public String getPath() {
        return mPath;
    }

    /**
     * Returns a optional pattern string for matching a single key-value pair in the URL query
     * params.
     */
    @Nullable
    public Map<String, String> getQuery() {
        return mQuery;
    }

    /**
     * Returns a optional comment string for this component.
     */
    @Nullable
    public String getComments() {
        return mComments;
    }

    /**
     * Creates a new DynamicAppLinkComponent object.
     */
    public static DynamicAppLinkComponent create(boolean exclude, String fragment, String path,
                                                 Map<String, String> query, String comments) {
        return new DynamicAppLinkComponent(exclude, fragment, path, query, comments);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        DynamicAppLinkComponent rule = (DynamicAppLinkComponent) o;

        if (mExclude != rule.mExclude) {
            return false;
        }
        if (!mFragment.equals(rule.mFragment)) {
            return false;
        }
        if (!mPath.equals(rule.mPath)) {
            return false;
        }
        if (!mQuery.equals(rule.mQuery)) {
            return false;
        }
        if (!mComments.equals(rule.mComments)) {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode() {
        int result = Boolean.hashCode(mExclude);
        result = 31 * result + mFragment.hashCode();
        result = 31 * result + mPath.hashCode();
        result = 31 * result + mQuery.hashCode();
        result = 31 * result + mComments.hashCode();
        return result;
    }

    @Override
    public String toString() {
        StringBuilder statement = new StringBuilder();
        statement.append("DynamicAppLinkComponent: ");
        statement.append(mExclude);
        statement.append(", ");
        statement.append(mFragment);
        statement.append(", ");
        statement.append(mPath);
        statement.append(", ");
        statement.append(mQuery);
        statement.append(", ");
        statement.append(mComments);
        return statement.toString();
    }
}
