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

import com.google.common.collect.Iterables;

import com.android.databinding.ClassAnalyzer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MethodCallExpr extends Expr {
    final String mName;

    ClassAnalyzer.Callable mGetter;

    MethodCallExpr(Expr target, String name, List<Expr> args) {
        super(Iterables.concat(Arrays.asList(target), args));
        mName = name;
    }

    @Override
    protected Class resolveType(ClassAnalyzer classAnalyzer) {
        if (mGetter == null) {
            List<Class> args = new ArrayList<>();
            for (Expr expr : getArgs()) {
                args.add(expr.getResolvedType());
            }
            mGetter = classAnalyzer.findMethod(getTarget().getResolvedType(), mName, args);
        }
        return mGetter.resolvedType;
    }

    @Override
    protected List<Dependency> constructDependencies() {
        final List<Dependency> dependencies = constructDynamicChildrenDependencies();
        for (Dependency dependency : dependencies) {
            if (dependency.getOther() == getTarget()) {
                dependency.setMandatory(true);
            }
        }
        return dependencies;
    }

    @Override
    protected String computeUniqueKey() {
        return sUniqueKeyJoiner.join(getTarget().computeUniqueKey(), mName,
                super.computeUniqueKey());
    }

    public Expr getTarget() {
        return getChildren().get(0);
    }

    public String getName() {
        return mName;
    }

    public List<Expr> getArgs() {
        return getChildren().subList(1, getChildren().size());
    }

    public ClassAnalyzer.Callable getGetter() {
        return mGetter;
    }
}
