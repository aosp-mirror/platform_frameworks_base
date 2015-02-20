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

import com.android.databinding.expr.Expr;
import com.android.databinding.expr.ExprModel;
import com.android.databinding.reflection.ModelAnalyzer;
import com.android.databinding.reflection.ModelClass;
import com.android.databinding.store.ResourceBundle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class BindingTarget {
    List<Binding> mBindings = new ArrayList<>();
    ExprModel mModel;
    ModelClass mResolvedClass;
    // if this target presents itself in multiple layout files with different view types,
    // it receives an interface type and should use it in the getter instead.
    private ResourceBundle.BindingTargetBundle mBundle;

    public BindingTarget(ResourceBundle.BindingTargetBundle bundle) {
        mBundle = bundle;
    }

    public boolean isUsed() {
        return mBundle.isUsed();
    }

    public void addBinding(String name, Expr expr) {
        mBindings.add(new Binding(this, name, expr));
    }

    public String getInterfaceType() {
        return mBundle.getInterfaceType() == null ? mBundle.getFullClassName() : mBundle.getInterfaceType();
    }

    public String getId() {
        return mBundle.getId();
    }

    public String getViewClass() {
        return mBundle.getFullClassName();
    }

    public ModelClass getResolvedType() {
        if (mResolvedClass == null) {
            mResolvedClass = ModelAnalyzer.getInstance().findClass(mBundle.getFullClassName(),
                    mModel.getImports());
        }
        return mResolvedClass;
    }

    public String getIncludedLayout() {
        return mBundle.getIncludedLayout();
    }

    public boolean isBinder() {
        return getIncludedLayout() != null;
    }

    public List<Binding> getBindings() {
        return mBindings;
    }

    public ExprModel getModel() {
        return mModel;
    }

    public void setModel(ExprModel model) {
        mModel = model;
    }
}
