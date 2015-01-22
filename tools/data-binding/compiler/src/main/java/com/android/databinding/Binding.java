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

package com.android.databinding;

import com.android.databinding.store.SetterStore;
import com.android.databinding.expr.Expr;

public class Binding {

    private final String mName;
    private final Expr mExpr;
    private final BindingTarget mTarget;
    private String mJavaCode = null;

    public Binding(BindingTarget target, String name, Expr expr) {
        mTarget = target;
        mName = name;
        mExpr = expr;
    }

    public BindingTarget getTarget() {
        return mTarget;
    }

    public String toJavaCode(String targetViewName, String expressionCode) {
        Class viewType = mTarget.getResolvedType();
        return SetterStore.get(ClassAnalyzer.getInstance()).getSetterCall(mName, viewType,
                mExpr.getResolvedType(), targetViewName, expressionCode);
    }

//    private String resolveJavaCode(ClassAnalyzer classAnalyzer) {
//
//    }
////        return classAnalyzer.findMethod(mTarget.getResolvedType(), mName,
////                Arrays.asList(mExpr.getResolvedType()));
//    //}
//


    public String getName() {
        return mName;
    }

    public Expr getExpr() {
        return mExpr;
    }
}
