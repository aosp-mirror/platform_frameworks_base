/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.databinding2.expr;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import com.android.databinding2.ClassAnalyzer;

import java.util.List;

public class IdentifierExpr extends Expr {
    String mName;
    String mUserDefinedType;
    IdentifierExpr(String name) {
        mName = name;
    }

    public String getName() {
        return mName;
    }

    /**
     * If this is root, its type should be set while parsing the XML document
     * @param userDefinedType The type of this identifier
     */
    public void setUserDefinedType(String userDefinedType) {
        mUserDefinedType = userDefinedType;
    }

    @Override
    protected String computeUniqueKey() {
        return sUniqueKeyJoiner.join(mName, super.computeUniqueKey());
    }

    @Override
    public boolean isDynamic() {
        return true;
    }

    @Override
    protected Class resolveType(final ClassAnalyzer classAnalyzer) {
        Preconditions.checkNotNull(mUserDefinedType,
                "Identifiers must have user defined types from the XML file. %s is missing it", mName);
        return classAnalyzer.findClass(mUserDefinedType);
    }

    @Override
    protected List<Dependency> constructDependencies() {
        return Lists.newArrayList();
    }
}
