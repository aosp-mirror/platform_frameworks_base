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
import com.android.databinding.reflection.ModelClass;

import java.util.List;

public class CastExpr extends Expr {

    final String mType;

    CastExpr(String type, Expr expr) {
        super(expr);
        mType = type;
    }

    @Override
    protected ModelClass resolveType(ModelAnalyzer modelAnalyzer) {
        return modelAnalyzer.findClass(mType);
    }

    @Override
    protected List<Dependency> constructDependencies() {
        final List<Dependency> dependencies = constructDynamicChildrenDependencies();
        for (Dependency dependency : dependencies) {
            dependency.setMandatory(true);
        }
        return dependencies;
    }

    protected String computeUniqueKey() {
        return sUniqueKeyJoiner.join(mType, getCastExpr().computeUniqueKey());
    }

    public Expr getCastExpr() {
        return getChildren().get(0);
    }

    public String getCastType() {
        return mType;
    }
}
