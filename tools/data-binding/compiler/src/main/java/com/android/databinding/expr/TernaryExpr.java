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

import com.google.common.collect.Lists;

import com.android.databinding.reflection.ReflectionAnalyzer;
import com.android.databinding.reflection.ReflectionClass;

import java.util.BitSet;
import java.util.List;

public class TernaryExpr extends Expr {
    TernaryExpr(Expr pred, Expr ifTrue, Expr ifFalse) {
        super(pred, ifTrue, ifFalse);
    }

    public Expr getPred() {
        return getChildren().get(0);
    }

    public Expr getIfTrue() {
        return getChildren().get(1);
    }

    public Expr getIfFalse() {
        return getChildren().get(2);
    }

    @Override
    protected String computeUniqueKey() {
        return "?:" + super.computeUniqueKey();
    }

    @Override
    protected ReflectionClass resolveType(ReflectionAnalyzer reflectionAnalyzer) {
        return reflectionAnalyzer.findCommonParentOf(getIfTrue().getResolvedType(),
                getIfFalse().getResolvedType());
    }

    @Override
    protected List<Dependency> constructDependencies() {
        List<Dependency> deps = Lists.newArrayList();
        Expr predExpr = getPred();
        if (predExpr.isDynamic()) {
            final Dependency pred = new Dependency(this, predExpr);
            pred.setMandatory(true);
            deps.add(pred);
        }
        Expr ifTrueExpr = getIfTrue();
        if (ifTrueExpr.isDynamic()) {
            deps.add(new Dependency(this, ifTrueExpr, predExpr, true));
        }
        Expr ifFalseExpr = getIfFalse();
        if (ifFalseExpr.isDynamic()) {
            deps.add(new Dependency(this, ifFalseExpr, predExpr, false));
        }
        return deps;
    }

    @Override
    protected BitSet getPredicateInvalidFlags() {
        return getPred().getInvalidFlags();
    }

    @Override
    public boolean isConditional() {
        return true;
    }
}
