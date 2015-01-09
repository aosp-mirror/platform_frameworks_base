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

package com.android.databinding2;

import com.google.common.base.Preconditions;

import com.android.databinding.renderer.BrRenderer;
import com.android.databinding.renderer2.LayoutBinderWriter;
import com.android.databinding2.expr.Expr;
import com.android.databinding2.expr.ExprModel;
import com.android.databinding2.expr.IdentifierExpr;
import com.android.databinding2.expr.StaticIdentifierExpr;
import com.android.databinding2.util.L;

import org.w3c.dom.Node;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps all information about the bindings per layout file
 */
public class LayoutBinder {
    /*
    * val pkg: String, val projectPackage: String, val baseClassName: String,
        val layoutName:String, val lb: LayoutExprBinding*/
    private final ExprModel mExprModel;
    private final Node mRoot;
    private final ExpressionParser mExpressionParser;
    private final List<BindingTarget> mBindingTargets;
    private String mPackage;
    private String mProjectPackage;
    private String mBaseClassName;
    private String mLayoutname;

    public LayoutBinder(Node root) {
        mRoot = root;
        mExprModel = new ExprModel();
        mExpressionParser = new ExpressionParser(mExprModel);
        mBindingTargets = new ArrayList<>();
    }

    public IdentifierExpr addVariable(String name, String type) {
        final IdentifierExpr id = mExprModel.identifier(name);
        id.setUserDefinedType(type);
        id.enableDirectInvalidation();
        return id;
    }

    public StaticIdentifierExpr addImport(String alias, String type) {
        final StaticIdentifierExpr id = mExprModel.staticIdentifier(alias);
        L.d("adding import %s as %s klass: %s", type, alias, id.getClass().getSimpleName());
        id.setUserDefinedType(type);
        return id;
    }

    public BindingTarget createBindingTarget(Node parent, String nodeValue, String viewClassName) {
        final BindingTarget target = new BindingTarget(parent, nodeValue, viewClassName);
        mBindingTargets.add(target);
        target.setModel(mExprModel);
        return target;
    }

    public Expr parse(String input) {
        final Expr parsed = mExpressionParser.parse(input);
        parsed.setBindingExpression(true);
        return parsed;
    }

    public List<BindingTarget> getBindingTargets() {
        return mBindingTargets;
    }

    public boolean isEmpty() {
        return mExprModel.size() == 0;
    }

    public ExprModel getModel() {
        return mExprModel;
    }

    public String writeViewBinder(BrRenderer brRenderer) {
        mExprModel.seal();
        Preconditions.checkNotNull(mPackage, "package cannot be null");
        Preconditions.checkNotNull(mProjectPackage, "project package cannot be null");
        Preconditions.checkNotNull(mBaseClassName, "base class name cannot be null");
        return new LayoutBinderWriter(this, brRenderer).write();
    }

    public String getPackage() {
        return mPackage;
    }

    public void setPackage(String aPackage) {
        mPackage = aPackage;
    }

    public String getProjectPackage() {
        return mProjectPackage;
    }

    public void setProjectPackage(String projectPackage) {
        mProjectPackage = projectPackage;
    }

    public String getBaseClassName() {
        return mBaseClassName;
    }

    public void setBaseClassName(String baseClassName) {
        mBaseClassName = baseClassName;
    }

    public String getLayoutname() {
        return mLayoutname;
    }

    public void setLayoutname(String layoutname) {
        mLayoutname = layoutname;
    }

    public String getClassName() {
        return mBaseClassName + "Impl";
    }
}
