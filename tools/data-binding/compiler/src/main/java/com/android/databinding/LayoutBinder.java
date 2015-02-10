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

import com.android.databinding.expr.Dependency;
import com.android.databinding.store.ResourceBundle;
import com.android.databinding.util.ParserHelper;
import com.android.databinding.writer.LayoutBinderWriter;
import com.android.databinding.expr.Expr;
import com.android.databinding.expr.ExprModel;
import com.android.databinding.expr.IdentifierExpr;
import com.android.databinding.expr.StaticIdentifierExpr;
import com.android.databinding.util.L;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps all information about the bindings per layout file
 */
public class LayoutBinder {

    /*
    * val pkg: String, val projectPackage: String, val baseClassName: String,
        val layoutName:String, val lb: LayoutExprBinding*/
    private final ExprModel mExprModel;
    private final ExpressionParser mExpressionParser;
    private final List<BindingTarget> mBindingTargets;
    private String mPackage;
    private String mProjectPackage;
    private String mBaseClassName;
    private final HashMap<String, String> mUserDefinedVariables = new HashMap<>();
    private final HashMap<String, String> mUserDefinedImports = new HashMap<>();

    private LayoutBinderWriter mWriter;
    private ResourceBundle.LayoutFileBundle mBundle;

    public LayoutBinder(ResourceBundle resourceBundle,
            ResourceBundle.LayoutFileBundle layoutBundle) {
        mExprModel = new ExprModel();
        mExpressionParser = new ExpressionParser(mExprModel);
        mBindingTargets = new ArrayList<>();
        mBundle = layoutBundle;
        mProjectPackage = resourceBundle.getAppPackage();
        mPackage = mProjectPackage + ".generated";
        mBaseClassName = ParserHelper.INSTANCE$.toClassName(layoutBundle.getFileName()) + "Binder";
        // copy over data.
        for (Map.Entry<String, String> variable : mBundle.getVariables().entrySet()) {
            addVariable(variable.getKey(), variable.getValue());
        }

        for (Map.Entry<String, String> userImport : mBundle.getImports().entrySet()) {
            addImport(userImport.getKey(), userImport.getValue());
        }
        for (ResourceBundle.BindingTargetBundle targetBundle : mBundle.getBindingTargetBundles()) {
            final BindingTarget bindingTarget = createBindingTarget(targetBundle);
            for (ResourceBundle.BindingTargetBundle.BindingBundle bindingBundle : targetBundle
                    .getBindingBundleList()) {
                bindingTarget.addBinding(bindingBundle.getName(), parse(bindingBundle.getExpr()));
            }
        }
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

    public BindingTarget createBindingTarget(ResourceBundle.BindingTargetBundle targetBundle) {
        final BindingTarget target = new BindingTarget(targetBundle);
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

    public String getLayoutname() {
        return mBundle.getFileName();
    }

    public String getClassName() {
        final String suffix;
        if (hasVariations()) {
            suffix = mBundle.getConfigName();
        } else {
            suffix = "";
        }
        return mBaseClassName + suffix + "Impl";

    }
    
    public String getInterfaceName() {
        return mBaseClassName;
    }

    public int getId() {
        return mBundle.getLayoutId();
    }

    public boolean hasVariations() {
        return mBundle.hasVariations();
    }

}
