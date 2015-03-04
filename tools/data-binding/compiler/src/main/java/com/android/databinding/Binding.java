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

import com.android.databinding.reflection.ModelAnalyzer;
import com.android.databinding.reflection.ModelClass;
import com.android.databinding.reflection.SdkUtil;
import com.android.databinding.store.SetterStore;
import com.android.databinding.expr.Expr;

public class Binding {

    private final String mName;
    private final Expr mExpr;
    private final BindingTarget mTarget;
    private SetterStore.SetterCall mSetterCall;

    public Binding(BindingTarget target, String name, Expr expr) {
        mTarget = target;
        mName = name;
        mExpr = expr;
    }

    private SetterStore.SetterCall getSetterCall() {
        if (mSetterCall == null) {
            ModelClass viewType = mTarget.getResolvedType();
            mSetterCall = SetterStore.get(ModelAnalyzer.getInstance()).getSetterCall(mName,
                    viewType, mExpr.getResolvedType(), mExpr.getModel().getImports());
        }
        return mSetterCall;
    }

    public BindingTarget getTarget() {
        return mTarget;
    }

    public String toJavaCode(String targetViewName, String expressionCode) {
        return getSetterCall().toJava(targetViewName, expressionCode);
    }

    /**
     * The min api level in which this binding should be executed.
     * <p>
     * This should be the minimum value among the dependencies of this binding. For now, we only
     * check the setter.
     */
    public int getMinApi() {
        return getSetterCall().getMinApi();
    }

//    private String resolveJavaCode(ModelAnalyzer modelAnalyzer) {
//
//    }
////        return modelAnalyzer.findMethod(mTarget.getResolvedType(), mName,
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
