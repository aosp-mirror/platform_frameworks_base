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

import com.android.databinding.reflection.ReflectionAnalyzer;
import com.android.databinding.reflection.ReflectionClass;

import java.util.List;

public class MathExpr extends Expr {
    final String mOp;
    MathExpr(Expr left, String op, Expr right) {
        super(left, right);
        mOp = op;
    }

    @Override
    protected String computeUniqueKey() {
        return sUniqueKeyJoiner.join(getLeft().getUniqueKey(), mOp, getRight().getUniqueKey());
    }

    @Override
    protected ReflectionClass resolveType(ReflectionAnalyzer reflectionAnalyzer) {
        if ("+".equals(mOp)) {
            // TODO we need upper casting etc.
            if (getLeft().getResolvedType().isString()
                    || getRight().getResolvedType().isString()) {
                return reflectionAnalyzer.findClass(String.class);
            }
        }
        return reflectionAnalyzer.findCommonParentOf(getLeft().getResolvedType(),
                getRight().getResolvedType());
    }

    @Override
    protected List<Dependency> constructDependencies() {
        return constructDynamicChildrenDependencies();
    }

    public String getOp() {
        return mOp;
    }

    public Expr getLeft() {
        return getChildren().get(0);
    }

    public Expr getRight() {
        return getChildren().get(1);
    }
}
