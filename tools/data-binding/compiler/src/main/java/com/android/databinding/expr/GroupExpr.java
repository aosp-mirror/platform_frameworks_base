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

public class GroupExpr extends Expr {
    Expr mWrapped;

    public GroupExpr(Expr wrapped) {
        super(wrapped);
        mWrapped = wrapped;
    }

    @Override
    protected Class resolveType(ClassAnalyzer classAnalyzer) {
        return mWrapped.resolveType(classAnalyzer);
    }

    @Override
    protected List<Dependency> constructDependencies() {
        return mWrapped.constructDependencies();
    }

    public Expr getWrapped() {
        return mWrapped;
    }
}
