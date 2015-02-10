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
import com.android.databinding.reflection.ReflectionAnalyzer;
import com.android.databinding.reflection.ReflectionClass;

import java.util.ArrayList;
import java.util.List;

public class BindingTarget {
    String mId;
    String mViewClass;
    List<Binding> mBindings = new ArrayList<>();
    ExprModel mModel;
    ReflectionClass mResolvedClass;
    String mIncludedLayout;
    // if this target presents itself in multiple layout files with different view types,
    // it receives an interface type and should use it in the getter instead.
    String mInterfaceType;
    // if this target is inherited from a common layout interface, used is false so that we don't
    // create find view by id etc for it.
    boolean mUsed;

    public BindingTarget(String id, String viewClass, boolean used) {
        mId = id;
        mViewClass = viewClass;
        mUsed = used;
    }

    public boolean isUsed() {
        return mUsed;
    }

    public void addBinding(String name, Expr expr) {
        mBindings.add(new Binding(this, name, expr));
    }

    public void setInterfaceType(String interfaceType) {
        mInterfaceType = interfaceType;
    }

    public String getInterfaceType() {
        return mInterfaceType == null ? mViewClass : mInterfaceType;
    }

    public String getId() {
        return mId;
    }

    public String getViewClass() {
        return mViewClass;
    }

    public ReflectionClass getResolvedType() {
        if (mResolvedClass == null) {
            mResolvedClass = ReflectionAnalyzer.getInstance().findClass(mViewClass);
        }
        return mResolvedClass;
    }

    public String getIncludedLayout() {
        return mIncludedLayout;
    }

    public boolean isBinder() {
        return mIncludedLayout != null;
    }

    public void setIncludedLayout(String includedLayout) {
        mIncludedLayout = includedLayout;
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
