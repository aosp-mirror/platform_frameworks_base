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

import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;

import com.android.databinding.expr.Dependency;
import com.android.databinding.util.Log;
import com.android.databinding.util.ParserHelper;
import com.android.databinding.writer.LayoutBinderWriter;
import com.android.databinding.expr.Expr;
import com.android.databinding.expr.ExprModel;
import com.android.databinding.expr.IdentifierExpr;
import com.android.databinding.expr.StaticIdentifierExpr;
import com.android.databinding.util.L;

import org.w3c.dom.Node;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private File mFile;
    private int id;
    private final HashMap<String, String> mUserDefinedVariables = new HashMap<>();
    private final HashMap<String, String> mUserDefinedImports = new HashMap<>();

    private LayoutBinderWriter mWriter;

    // layout has different definitions in different configurations
    private boolean mHasVariations = false;

    public LayoutBinder(Node root) {
        mRoot = root;
        mExprModel = new ExprModel();
        mExpressionParser = new ExpressionParser(mExprModel);
        mBindingTargets = new ArrayList<>();
    }

    public void resolveWhichExpressionsAreUsed() {
        List<Expr> used = new ArrayList<>();
        for (BindingTarget target : mBindingTargets) {
            for (Binding binding : target.getBindings()) {
                binding.getExpr().setIsUsed(true);
                used.add(binding.getExpr());
            }
        }
        while (!used.isEmpty()) {
            Expr e = used.remove(used.size() - 1);
            for (Dependency dep : e.getDependencies()) {
                if (!dep.getOther().isUsed()) {
                    used.add(dep.getOther());
                    dep.getOther().setIsUsed(true);
                }
            }
        }
    }

    public IdentifierExpr addVariable(String name, String type) {
        Preconditions.checkState(!mUserDefinedVariables.containsKey(name),
                "%s has already been defined as %s", name, type);
        final IdentifierExpr id = mExprModel.identifier(name);
        id.setUserDefinedType(type);
        id.enableDirectInvalidation();
        mUserDefinedVariables.put(name, type);
        return id;
    }

    public StaticIdentifierExpr addImport(String alias, String type) {
        Preconditions.checkState(!mUserDefinedImports.containsKey(alias),
                "%s has already been defined as %s", alias, type);
        final StaticIdentifierExpr id = mExprModel.staticIdentifier(alias);
        L.d("adding import %s as %s klass: %s", type, alias, id.getClass().getSimpleName());
        id.setUserDefinedType(type);
        mUserDefinedImports.put(alias, type);
        return id;
    }

    public HashMap<String, String> getUserDefinedVariables() {
        return mUserDefinedVariables;
    }

    public HashMap<String, String> getUserDefinedImports() {
        return mUserDefinedImports;
    }

    public BindingTarget createBindingTarget(String nodeValue, String viewClassName, boolean used) {
        final BindingTarget target = new BindingTarget(nodeValue, viewClassName, used);
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

    private void ensureWriter() {
        if (mWriter == null) {
            mWriter = new LayoutBinderWriter(this);
        }
    }
    public String writeViewBinderInterface() {
        ensureWriter();
        return mWriter.writeInterface();
    }


    public String writeViewBinder() {
        mExprModel.seal();
        ensureWriter();
        Preconditions.checkNotNull(mPackage, "package cannot be null");
        Preconditions.checkNotNull(mProjectPackage, "project package cannot be null");
        Preconditions.checkNotNull(mBaseClassName, "base class name cannot be null");
        return mWriter.write();
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
        final String suffix;
        if (hasVariations()) {
            // append configuration specifiers.
            final String parentFileName = mFile.getParentFile().getName();
            L.d("parent file for %s is %s", mFile.getName(), parentFileName);
            if ("layout".equals(parentFileName)) {
                suffix = "";
            } else {
                suffix = ParserHelper.INSTANCE$.toClassName(parentFileName.substring("layout-".length()));
            }
        } else {
            suffix = "";
        }
        return mBaseClassName + suffix + "Impl";

    }

    public String getInterfaceName() {
        return mBaseClassName;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public File getFile() {
        return mFile;
    }

    public void setFile(File file) {
        mFile = file;
    }

    public boolean hasVariations() {
        return mHasVariations;
    }

    public void setHasVariations(boolean hasVariations) {
        mHasVariations = hasVariations;
    }
}
