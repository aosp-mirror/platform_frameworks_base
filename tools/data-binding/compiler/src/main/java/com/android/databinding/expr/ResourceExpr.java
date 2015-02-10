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
package com.android.databinding.expr;

import com.google.common.collect.ImmutableMap;

import com.android.databinding.reflection.ReflectionAnalyzer;
import com.android.databinding.reflection.ReflectionClass;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ResourceExpr extends Expr {

    private final static Map<String, String> RESOURCE_TYPE_TO_R_OBJECT =
            ImmutableMap.<String, String>builder()
                    .put("colorStateList", "color  ")
                    .put("dimenOffset", "dimen  ")
                    .put("dimenSize", "dimen  ")
                    .put("intArray", "array  ")
                    .put("stateListAnimator", "animator  ")
                    .put("stringArray", "array  ")
                    .put("typedArray", "array")
                    .build();

    private final String mPackage;

    private final String mResourceType;

    private final String mResourceId;

    public ResourceExpr(String resourceText) {
        int colonIndex = resourceText.indexOf(':');
        int slashIndex = resourceText.indexOf('/');
        mResourceType = resourceText.substring(colonIndex + 1, slashIndex).trim();
        mResourceId = resourceText.substring(slashIndex + 1).trim();
        String packageName = "";
        if (colonIndex > 1) {
            if ("android".equals(resourceText.substring(1, colonIndex).trim())) {
                packageName = "android.";
            } else {
                packageName = "";
            }
        }
        mPackage = packageName;
    }

    @Override
    protected ReflectionClass resolveType(ReflectionAnalyzer reflectionAnalyzer) {
        String type;
        switch (mResourceType) {
            case "anim":
                type = "android.view.animation.Animation";
                break;
            case "animator":
                type = "android.animation.Animator";
                break;
            case "bool":
                return reflectionAnalyzer.findClass(boolean.class);
            case "color":
            case "dimenOffset":
            case "dimenSize":
            case "id":
            case "integer":
            case "layout":
            case "plurals":
                return reflectionAnalyzer.findClass(int.class);
            case "colorStateList":
                type = "android.content.res.ColorStateList";
                break;
            case "dimen":
            case "fraction":
                return reflectionAnalyzer.findClass(float.class);
            case "drawable":
                type = "android.graphics.drawable.Drawable";
                break;
            case "intArray":
                return reflectionAnalyzer.findClass(int[].class);
            case "interpolator":
                type = "";
                break;
            case "stateListAnimator":
                type = "android.animation.StateListAnimator";
                break;
            case "string":
                return reflectionAnalyzer.findClass(String.class);
            case "stringArray":
                return reflectionAnalyzer.findClass(String[].class);
            case "transition":
                type = "android.transition.Transition";
                break;
            case "typedArray":
                type = "android.content.res.TypedArray";
                break;
            default:
                type = mResourceType;
                break;
        }
        return reflectionAnalyzer.findClass(type);
    }

    @Override
    protected List<Dependency> constructDependencies() {
        return Collections.emptyList();
    }

    @Override
    protected String computeUniqueKey() {
        if (mPackage == null) {
            return "@" + mResourceType + "/" + mResourceId;
        } else {
            return "@" + "android:" + mResourceType + "/" + mResourceId;
        }
    }

    @Override
    public boolean isDynamic() {
        return false;
    }

    @Override
    public boolean canBeInvalidated() {
        return false;
    }

    public String toJava() {
        final String context = "mRoot.getContext()";
        final String resources = context + ".getResources()";
        final String resourceName = mPackage + "R." + getResourceObject() + "." + mResourceId;
        switch (mResourceType) {
            case "anim": return "android.view.animation.AnimationUtils.loadAnimation(" + context + ", " + resourceName + ")";
            case "animator": return "android.animation.AnimatorInflater.loadAnimator(" + context + ", " + resourceName + ")";
            case "bool": return resources + ".getBoolean(" + resourceName + ")";
            case "color": return resources + ".getColor(" + resourceName + ")";
            case "colorStateList": return resources + ".getColorStateList(" + resourceName + ")";
            case "dimen": return resources + ".getDimension(" + resourceName + ")";
            case "dimenOffset": return resources + ".getDimensionPixelOffset(" + resourceName + ")";
            case "dimenSize": return resources + ".getDimensionPixelSize(" + resourceName + ")";
            case "drawable": return resources + ".getDrawable(" + resourceName + ")";
            case "fraction": return resources + ".getFraction(" + resourceName + ", 1, 1)";
            case "id": return resourceName;
            case "intArray": return resources + ".getIntArray(" + resourceName + ")";
            case "integer": return resources + ".getInteger(" + resourceName + ")";
            case "interpolator":  return "android.view.animation.AnimationUtils.loadInterpolator(" + context + ", " + resourceName + ")";
            case "layout": return resourceName;
            case "plurals": return resourceName;
            case "stateListAnimator": return "android.animation.AnimatorInflater.loadStateListAnimator(" + context + ", " + resourceName + ")";
            case "string": return resources + ".getString(" + resourceName + ")";
            case "stringArray": return resources + ".getStringArray(" + resourceName + ")";
            case "transition": return "android.transition.TransitionInflater.from(" + context + ").inflateTransition(" + resourceName + ")";
            case "typedArray": return resources + ".obtainTypedArray(" + resourceName + ")";
        }
        final String property = Character.toUpperCase(mResourceType.charAt(0)) +
                mResourceType.substring(1);
        return resources + ".get" + property + "(" + resourceName + ")";

    }

    private String getResourceObject() {
        String rFileObject = RESOURCE_TYPE_TO_R_OBJECT.get(mResourceType);
        if (rFileObject == null) {
            rFileObject = mResourceType;
        }
        return rFileObject;
    }
}
