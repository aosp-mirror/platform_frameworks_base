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

import com.android.databinding.ClassAnalyzer;

import java.util.BitSet;
import java.util.List;

public class TernaryExpr extends Expr {
    final Expr mIfTrue;
    final Expr mIfFalse;
    final Expr mPred;
    TernaryExpr(Expr pred, Expr ifTrue, Expr ifFalse) {
        super(pred, ifTrue, ifFalse);
        mPred = pred;
        mIfTrue = ifTrue;
        mIfFalse = ifFalse;
    }

    public Expr getPred() {
        return mPred;
    }

    public Expr getIfTrue() {
        return mIfTrue;
    }

    public Expr getIfFalse() {
        return mIfFalse;
    }

    @Override
    protected String computeUniqueKey() {
        return "?:" + super.computeUniqueKey();
    }

    @Override
    protected Class resolveType(ClassAnalyzer classAnalyzer) {
        return classAnalyzer.findCommonParentOf(mIfTrue.getResolvedType(), mIfFalse.getResolvedType());
    }

    @Override
    protected List<Dependency> constructDependencies() {
        List<Dependency> deps = Lists.newArrayList();
        if (mPred.isDynamic()) {
            final Dependency pred = new Dependency(this, mPred);
            pred.setMandatory(true);
            deps.add(pred);
        }
        if (mIfTrue.isDynamic()) {
            deps.add(new Dependency(this, mIfTrue, mPred, true));
        }
        if (mIfFalse.isDynamic()) {
            deps.add(new Dependency(this, mIfFalse, mPred, false));
        }
        return deps;
    }

    @Override
    protected BitSet getPredicateInvalidFlags() {
        return mPred.getInvalidFlags();
    }

    @Override
    public boolean isConditional() {
        return true;
    }
}
