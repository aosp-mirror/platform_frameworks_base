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

package com.android.databinding.expr;

import com.android.databinding.ClassAnalyzer;

import java.util.List;

public class FieldAccessExpr extends Expr {
    String mName;
    ClassAnalyzer.Callable mGetter;
    Expr mParent;

    FieldAccessExpr(Expr parent, String name) {
        super(parent);
        mName = name;
        mParent = parent;
    }

    public Expr getParent() {
        return mParent;
    }

    public ClassAnalyzer.Callable getGetter() {
        return mGetter;
    }

    @Override
    public boolean isDynamic() {
        if (!mParent.isDynamic()) {
            return false;
        }
        if (mGetter == null) {
            getResolvedType();
        }
        // maybe this is just a final field in which case cannot be notified as changed
        return mGetter.type != ClassAnalyzer.Callable.Type.FIELD || mGetter.isDynamic;
    }

    @Override
    protected String computeUniqueKey() {
        return sUniqueKeyJoiner.join(mName, ".", super.computeUniqueKey());
    }

    public String getName() {
        return mName;
    }

    @Override
    protected Class resolveType(ClassAnalyzer classAnalyzer) {
        if (mGetter == null) {
            mGetter = classAnalyzer.findMethodOrField(mChildren.get(0).getResolvedType(), mName);
        }
        return mGetter.resolvedType;
    }

    @Override
    protected List<Dependency> constructDependencies() {
        return constructDynamicChildrenDependencies();
    }
}
