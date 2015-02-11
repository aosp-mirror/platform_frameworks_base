/*
 * Copyright (C) 2015 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.databinding.store;

import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;

import com.android.databinding.util.L;
import com.android.databinding.util.ParserHelper;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * This is a serializable class that can keep the result of parsing layout files.
 */
public class ResourceBundle implements Serializable {

    private String mAppPackage;

    private HashMap<String, List<LayoutFileBundle>> mLayoutBundles = new HashMap<>();

    public void addLayoutBundle(LayoutFileBundle bundle, int layoutId) {
        Preconditions.checkArgument(bundle.mFileName != null, "File bundle must have a name");
        if (!mLayoutBundles.containsKey(bundle.mFileName)) {
            mLayoutBundles.put(bundle.mFileName, new ArrayList<LayoutFileBundle>());
        }
        bundle.mLayoutId = layoutId;
        mLayoutBundles.get(bundle.mFileName).add(bundle);
    }

    public void setAppPackage(String appPackage) {
        mAppPackage = appPackage;
    }

    public HashMap<String, List<LayoutFileBundle>> getLayoutBundles() {
        return mLayoutBundles;
    }

    public String getAppPackage() {
        return mAppPackage;
    }

    public void validateMultiResLayouts() {
        final Iterable<Map.Entry<String, List<LayoutFileBundle>>> multiResLayouts = Iterables
                .filter(mLayoutBundles.entrySet(),
                        new Predicate<Map.Entry<String, List<LayoutFileBundle>>>() {
                            @Override
                            public boolean apply(Map.Entry<String, List<LayoutFileBundle>> input) {
                                return input.getValue().size() > 1;
                            }
                        });

        for (Map.Entry<String, List<LayoutFileBundle>> bundles : multiResLayouts) {
            // validate all ids are in correct view types
            // and all variables have the same name
            Map<String, String> variableTypes = new HashMap<>();
            Map<String, String> importTypes = new HashMap<>();

            for (LayoutFileBundle bundle : bundles.getValue()) {
                bundle.mHasVariations = true;
                for (Map.Entry<String, String> variable : bundle.mVariables.entrySet()) {
                    String existing = variableTypes.get(variable.getKey());
                    Preconditions
                            .checkState(existing == null || existing.equals(variable.getValue()),
                                    "inconsistent variable types for %s for layout %s",
                                    variable.getKey(), bundle.mFileName);
                    variableTypes.put(variable.getKey(), variable.getValue());
                }
                for (Map.Entry<String, String> userImport : bundle.mImports.entrySet()) {
                    String existing = importTypes.get(userImport.getKey());
                    Preconditions
                            .checkState(existing == null || existing.equals(userImport.getValue()),
                                    "inconsistent variable types for %s for layout %s",
                                    userImport.getKey(), bundle.mFileName);
                    importTypes.put(userImport.getKey(), userImport.getValue());
                }
            }

            for (LayoutFileBundle bundle : bundles.getValue()) {
                // now add missing ones to each to ensure they can be referenced
                L.d("checking for missing variables in %s / %s", bundle.mFileName,
                        bundle.mConfigName);
                for (Map.Entry<String, String> variable : variableTypes.entrySet()) {
                    if (!bundle.mVariables.containsKey(variable.getKey())) {
                        bundle.mVariables.put(variable.getKey(), variable.getValue());
                        L.d("adding missing variable %s to %s / %s", variable.getKey(),
                                bundle.mFileName, bundle.mConfigName);
                    }
                }
                for (Map.Entry<String, String> userImport : importTypes.entrySet()) {
                    if (!bundle.mImports.containsKey(userImport.getKey())) {
                        bundle.mImports.put(userImport.getKey(), userImport.getValue());
                        L.d("adding missing import %s to %s / %s", userImport.getKey(),
                                bundle.mFileName, bundle.mConfigName);
                    }
                }
            }

            Set<String> includeBindingIds = new HashSet<>();
            Set<String> viewBindingIds = new HashSet<>();
            Map<String, String> viewTypes = new HashMap<>();
            L.d("validating ids for %s", bundles.getKey());
            for (LayoutFileBundle bundle : bundles.getValue()) {
                for (BindingTargetBundle target : bundle.mBindingTargetBundles) {
                    L.d("checking %s %s %s", target.getId(), target.mFullClassName, target.isBinder());
                    if (target.isBinder()) {
                        Preconditions.checkState(!viewBindingIds.contains(target.mFullClassName),
                                "Cannot use the same id for a View and an include tag. Error in "
                                        + "file %s / %s", bundle.mFileName, bundle.mConfigName);
                        includeBindingIds.add(target.mFullClassName);
                    } else {
                        Preconditions.checkState(!includeBindingIds.contains(target.mFullClassName),
                                "Cannot use the same id for a View and an include tag. Error in "
                                        + "file %s / %s", bundle.mFileName, bundle.mConfigName);
                        viewBindingIds.add(target.mFullClassName);
                    }
                    String existingType = viewTypes.get(target.mId);
                    if (existingType == null) {
                        L.d("assigning %s as %s", target.getId(), target.mFullClassName);
                        viewTypes.put(target.mId, target.mFullClassName);
                    } else if (!existingType.equals(target.mFullClassName)) {
                        if (target.isBinder()) {
                            L.d("overriding %s as base binder", target.getId());
                            viewTypes.put(target.mId,
                                    "com.android.databinding.library.IViewDataBinder");
                        } else {
                            L.d("overriding %s as base view", target.getId());
                            viewTypes.put(target.mId, "android.view.View");
                        }
                    }
                }
            }

            for (LayoutFileBundle bundle : bundles.getValue()) {
                for (Map.Entry<String, String> viewType : viewTypes.entrySet()) {
                    BindingTargetBundle target = bundle.getBindingTargetById(viewType.getKey());
                    if (target == null) {
                        bundle.createBindingTarget(viewType.getKey(), viewType.getValue(), false);
                    } else {
                        L.d("setting interface type on %s (%s) as %s", target.mId, target.mFullClassName, viewType.getValue());
                        target.setInterfaceType(viewType.getValue());
                    }
                }
            }
        }
        // assign class names to each
        for (Map.Entry<String, List<LayoutFileBundle>> entry : mLayoutBundles.entrySet()) {
            for (LayoutFileBundle bundle : entry.getValue()) {
                final String configName;
                if (bundle.hasVariations()) {
                    // append configuration specifiers.
                    final String parentFileName = bundle.mTransientFile.getParentFile().getName();
                    L.d("parent file for %s is %s", bundle.mTransientFile.getName(), parentFileName);
                    if ("layout".equals(parentFileName)) {
                        configName = "";
                    } else {
                        configName = ParserHelper.INSTANCE$.toClassName(parentFileName.substring("layout-".length()));
                    }
                } else {
                    configName = "";
                }
                bundle.mConfigName = configName;
            }
        }
    }

    public static class LayoutFileBundle implements Serializable {
        private int mLayoutId;
        private String mFileName;
        private String mConfigName;
        private boolean mHasVariations;
        transient private File mTransientFile;

        private Map<String, String> mVariables = new HashMap<>();

        private Map<String, String> mImports = new HashMap<>();

        private List<BindingTargetBundle> mBindingTargetBundles = new ArrayList<>();
        public LayoutFileBundle(String fileName) {
            mFileName = fileName;
        }

        public void addVariable(String name, String type) {
            mVariables.put(name, type);
        }

        public void addImport(String alias, String type) {
            mImports.put(alias, type);
        }

        public void setTransientFile(File transientFile) {
            mTransientFile = transientFile;
        }

        public BindingTargetBundle createBindingTarget(String id, String fullClassName, boolean used) {
            BindingTargetBundle target = new BindingTargetBundle(id, fullClassName, used);
            mBindingTargetBundles.add(target);
            return target;
        }

        public boolean isEmpty() {
            return mVariables.isEmpty() && mImports.isEmpty() && mBindingTargetBundles.isEmpty();
        }

        public BindingTargetBundle getBindingTargetById(String key) {
            for (BindingTargetBundle target : mBindingTargetBundles) {
                if (key.equals(target.mId)) {
                    return target;
                }
            }
            return null;
        }

        public int getLayoutId() {
            return mLayoutId;
        }

        public String getFileName() {
            return mFileName;
        }

        public String getConfigName() {
            return mConfigName;
        }

        public boolean hasVariations() {
            return mHasVariations;
        }

        public Map<String, String> getVariables() {
            return mVariables;
        }

        public Map<String, String> getImports() {
            return mImports;
        }

        public List<BindingTargetBundle> getBindingTargetBundles() {
            return mBindingTargetBundles;
        }
    }

    public static class BindingTargetBundle implements Serializable {

        private String mId;
        private String mFullClassName;
        private boolean mUsed;
        private List<BindingBundle> mBindingBundleList = new ArrayList<>();
        private String mIncludedLayout;
        private String mInterfaceType;

        public BindingTargetBundle(String id, String fullClassName, boolean used) {
            mId = id;
            mFullClassName = fullClassName;
            mUsed = used;
        }

        public void addBinding(String name, String expr) {
            mBindingBundleList.add(new BindingBundle(name, expr));
        }

        public void setIncludedLayout(String includedLayout) {
            mIncludedLayout = includedLayout;
        }

        public String getIncludedLayout() {
            return mIncludedLayout;
        }

        public boolean isBinder() {
            return mIncludedLayout != null;
        }

        public void setInterfaceType(String interfaceType) {
            mInterfaceType = interfaceType;
        }

        public String getId() {
            return mId;
        }

        public String getFullClassName() {
            return mFullClassName;
        }

        public boolean isUsed() {
            return mUsed;
        }

        public List<BindingBundle> getBindingBundleList() {
            return mBindingBundleList;
        }

        public String getInterfaceType() {
            return mInterfaceType;
        }

        public static class BindingBundle implements Serializable {

            private String mName;
            private String mExpr;

            public BindingBundle(String name, String expr) {
                mName = name;
                mExpr = expr;
            }

            public String getName() {
                return mName;
            }

            public String getExpr() {
                return mExpr;
            }
        }
    }

}
