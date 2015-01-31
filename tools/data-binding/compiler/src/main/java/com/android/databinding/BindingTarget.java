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

import org.w3c.dom.Node;

import java.util.ArrayList;
import java.util.List;

public class BindingTarget {
    Node mNode;
    String mId;
    String mViewClass;
    List<Binding> mBindings = new ArrayList<>();
    ExprModel mModel;
    Class mResolvedClass;
    String mIncludedLayout;

    public BindingTarget(Node node, String id, String viewClass) {
        mNode = node;
        mId = id;
        mViewClass = viewClass;
    }

    public void addBinding(String name, Expr expr) {
        mBindings.add(new Binding(this, name, expr));
    }

    public Node getNode() {
        return mNode;
    }

    public String getId() {
        return mId;
    }

    public String getViewClass() {
        return mViewClass;
    }

    public Class getResolvedType() {
        if (mResolvedClass == null) {
            mResolvedClass = ClassAnalyzer.getInstance().findClass(mViewClass);
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
