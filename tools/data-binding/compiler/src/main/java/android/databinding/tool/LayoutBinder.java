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

package android.databinding.tool;

import com.google.common.base.Preconditions;

import android.databinding.tool.expr.Dependency;
import android.databinding.tool.expr.Expr;
import android.databinding.tool.expr.ExprModel;
import android.databinding.tool.expr.IdentifierExpr;
import android.databinding.tool.store.ResourceBundle;
import android.databinding.tool.store.ResourceBundle.BindingTargetBundle;
import android.databinding.tool.util.ParserHelper;
import android.databinding.tool.writer.LayoutBinderWriter;
import android.databinding.tool.writer.WriterPackage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps all information about the bindings per layout file
 */
public class LayoutBinder {
    private static final Comparator<BindingTarget> COMPARE_FIELD_NAME = new Comparator<BindingTarget>() {
        @Override
        public int compare(BindingTarget first, BindingTarget second) {
            final String fieldName1 = WriterPackage.getFieldName(first);
            final String fieldName2 = WriterPackage.getFieldName(second);
            return fieldName1.compareTo(fieldName2);
        }
    };

    /*
    * val pkg: String, val projectPackage: String, val baseClassName: String,
        val layoutName:String, val lb: LayoutExprBinding*/
    private final ExprModel mExprModel;
    private final ExpressionParser mExpressionParser;
    private final List<BindingTarget> mBindingTargets;
    private String mPackage;
    private String mModulePackage;
    private String mProjectPackage;
    private String mBaseClassName;
    private final HashMap<String, String> mUserDefinedVariables = new HashMap<String, String>();

    private LayoutBinderWriter mWriter;
    private ResourceBundle.LayoutFileBundle mBundle;

    public LayoutBinder(ResourceBundle resourceBundle,
            ResourceBundle.LayoutFileBundle layoutBundle) {
        mExprModel = new ExprModel();
        mExpressionParser = new ExpressionParser(mExprModel);
        mBindingTargets = new ArrayList<BindingTarget>();
        mBundle = layoutBundle;
        mProjectPackage = resourceBundle.getAppPackage();
        mModulePackage = layoutBundle.getModulePackage();
        mPackage = layoutBundle.getModulePackage() + ".generated";
        mBaseClassName = ParserHelper.INSTANCE$.toClassName(layoutBundle.getFileName()) + "Binding";
        // copy over data.
        for (Map.Entry<String, String> variable : mBundle.getVariables().entrySet()) {
            addVariable(variable.getKey(), variable.getValue());
        }

        for (Map.Entry<String, String> userImport : mBundle.getImports().entrySet()) {
            mExprModel.addImport(userImport.getKey(), userImport.getValue());
        }
        for (BindingTargetBundle targetBundle : mBundle.getBindingTargetBundles()) {
            final BindingTarget bindingTarget = createBindingTarget(targetBundle);
            for (ResourceBundle.BindingTargetBundle.BindingBundle bindingBundle : targetBundle
                    .getBindingBundleList()) {
                bindingTarget.addBinding(bindingBundle.getName(), parse(bindingBundle.getExpr()));
            }
        }
        Collections.sort(mBindingTargets, COMPARE_FIELD_NAME);
    }

    public void resolveWhichExpressionsAreUsed() {
        List<Expr> used = new ArrayList<Expr>();
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

    public HashMap<String, String> getUserDefinedVariables() {
        return mUserDefinedVariables;
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
        return mWriter.writeBaseClass();
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

    public String getModulePackage() {
        return mModulePackage;
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
