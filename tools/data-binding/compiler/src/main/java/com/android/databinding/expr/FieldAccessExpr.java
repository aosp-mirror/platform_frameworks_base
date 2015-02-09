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
import com.android.databinding.util.L;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class FieldAccessExpr extends Expr {
    String mName;
    ClassAnalyzer.Callable mGetter;
    final boolean mIsObservableField;

    FieldAccessExpr(Expr parent, String name) {
        super(parent);
        mName = name;
        mIsObservableField = false;
    }

    FieldAccessExpr(Expr parent, String name, boolean isObservableField) {
        super(parent);
        mName = name;
        mIsObservableField = isObservableField;
    }

    public Expr getParent() {
        return getChildren().get(0);
    }

    public ClassAnalyzer.Callable getGetter() {
        if (mGetter == null) {
            getResolvedType();
        }
        return mGetter;
    }

    @Override
    public boolean isDynamic() {
        if (!getParent().isDynamic()) {
            return false;
        }
        if (mGetter == null) {
            getResolvedType();
        }
        // maybe this is just a final field in which case cannot be notified as changed
        return mGetter.type != ClassAnalyzer.Callable.Type.FIELD || mGetter.isDynamic;
    }

    @Override
    protected List<Dependency> constructDependencies() {
        final List<Dependency> dependencies = constructDynamicChildrenDependencies();
        for (Dependency dependency : dependencies) {
            if (dependency.getOther() == getParent()) {
                dependency.setMandatory(true);
            }
        }
        return dependencies;
    }

    @Override
    protected String computeUniqueKey() {
        if (mIsObservableField) {
            return sUniqueKeyJoiner.join(mName, "..", super.computeUniqueKey());
        }
        return sUniqueKeyJoiner.join(mName, ".", super.computeUniqueKey());
    }

    public String getName() {
        return mName;
    }

    @Override
    public void updateExpr(ClassAnalyzer classAnalyzer) {
        if (mGetter == null) {
            mGetter = classAnalyzer.findMethodOrField(mChildren.get(0).getResolvedType(), mName);
            if (classAnalyzer.isObservableField(mGetter.resolvedType)) {
                // Make this the ".get()" and add an extra field access for the observable field
                Expr parent = getParent();
                parent.getParents().remove(this);
                getChildren().remove(parent);

                FieldAccessExpr observableField = getModel().observableField(parent, mName);
                observableField.mGetter = mGetter;

                getChildren().add(observableField);
                observableField.getParents().add(this);
                mGetter = classAnalyzer.findMethodOrField(mGetter.resolvedType, "get");
                mName = "";
            }
        }
    }

    @Override
    protected Class resolveType(ClassAnalyzer classAnalyzer) {
        if (mGetter == null) {
            mGetter = classAnalyzer.findMethodOrField(mChildren.get(0).getResolvedType(), mName);
        }
        return mGetter.resolvedType;
    }
}
