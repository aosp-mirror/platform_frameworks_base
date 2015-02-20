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

import com.android.databinding.reflection.ModelAnalyzer;
import com.android.databinding.reflection.Callable;
import com.android.databinding.reflection.ModelClass;

import java.util.List;

public class FieldAccessExpr extends Expr {
    String mName;
    Callable mGetter;
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

    public Expr getChild() {
        return getChildren().get(0);
    }

    public Callable getGetter() {
        if (mGetter == null) {
            getResolvedType();
        }
        return mGetter;
    }

    @Override
    public boolean isDynamic() {
        if (!getChild().isDynamic()) {
            return false;
        }
        if (mGetter == null) {
            getResolvedType();
        }
        // maybe this is just a final field in which case cannot be notified as changed
        return mGetter.type != Callable.Type.FIELD || mGetter.isDynamic;
    }

    @Override
    protected List<Dependency> constructDependencies() {
        final List<Dependency> dependencies = constructDynamicChildrenDependencies();
        for (Dependency dependency : dependencies) {
            if (dependency.getOther() == getChild()) {
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
    public void updateExpr(ModelAnalyzer modelAnalyzer) {
        resolveType(modelAnalyzer);
        super.updateExpr(modelAnalyzer);
    }

    @Override
    protected ModelClass resolveType(ModelAnalyzer modelAnalyzer) {
        if (mGetter == null) {
            replaceStaticIdentifiers(modelAnalyzer);
            Expr child = getChild();
            child.resolveType(modelAnalyzer);
            boolean isStatic = child instanceof StaticIdentifierExpr;
            mGetter = modelAnalyzer.findMethodOrField(child.getResolvedType(), mName, isStatic);
            if (modelAnalyzer.isObservableField(mGetter.resolvedType)) {
                // Make this the ".get()" and add an extra field access for the observable field
                child.getParents().remove(this);
                getChildren().remove(child);

                FieldAccessExpr observableField = getModel().observableField(child, mName);
                observableField.mGetter = mGetter;

                getChildren().add(observableField);
                observableField.getParents().add(this);
                mGetter = modelAnalyzer.findMethodOrField(mGetter.resolvedType, "get", false);
                mName = "";
            }
        }
        return mGetter.resolvedType;
    }

    @Override
    protected String asPackage() {
        String parentPackage = getChild().asPackage();
        return parentPackage == null ? null : parentPackage + "." + mName;
    }
}
