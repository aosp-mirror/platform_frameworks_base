/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package android.testing;

import android.annotation.NonNull;
import android.content.Context;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import java.util.Map;
import java.util.Set;

/**
 * Builder class to create a {@link LayoutInflater} with various properties.
 *
 * Call any desired configuration methods on the Builder and then use
 * {@link Builder#build} to create the LayoutInflater. This is an alternative to directly using
 * {@link LayoutInflater#setFilter} and {@link LayoutInflater#setFactory}.
 * @hide for use by framework
 */
public class LayoutInflaterBuilder {
    private static final String TAG = "LayoutInflaterBuilder";

    private Context mFromContext;
    private Context mTargetContext;
    private Map<String, String> mReplaceMap;
    private Set<Class> mDisallowedClasses;
    private LayoutInflater mBuiltInflater;

    /**
     * Creates a new Builder which will construct a LayoutInflater.
     *
     * @param fromContext This context's LayoutInflater will be cloned by the Builder using
     * {@link LayoutInflater#cloneInContext}. By default, the new LayoutInflater will point at
     * this same Context.
     */
    public LayoutInflaterBuilder(@NonNull Context fromContext) {
        mFromContext = fromContext;
        mTargetContext = fromContext;
        mReplaceMap = null;
        mDisallowedClasses = null;
        mBuiltInflater = null;
    }

    /**
     * Instructs the Builder to point the LayoutInflater at a different Context.
     *
     * @param targetContext Context to be provided to
     * {@link LayoutInflater#cloneInContext(Context)}.
     * @return Builder object post-modification.
     */
    public LayoutInflaterBuilder target(@NonNull Context targetContext) {
        assertIfAlreadyBuilt();
        mTargetContext = targetContext;
        return this;
    }

    /**
     * Instructs the Builder to configure the LayoutInflater such that all instances
     * of one {@link View} will be replaced with instances of another during inflation.
     *
     * @param from Instances of this class will be replaced during inflation.
     * @param to Instances of this class will be inflated as replacements.
     * @return Builder object post-modification.
     */
    public LayoutInflaterBuilder replace(@NonNull Class from, @NonNull Class to) {
        return replace(from.getName(), to);
    }

    /**
     * Instructs the Builder to configure the LayoutInflater such that all instances
     * of one {@link View} will be replaced with instances of another during inflation.
     *
     * @param tag Instances of this tag will be replaced during inflation.
     * @param to Instances of this class will be inflated as replacements.
     * @return Builder object post-modification.
     */
    public LayoutInflaterBuilder replace(@NonNull String tag, @NonNull Class to) {
        assertIfAlreadyBuilt();
        if (mReplaceMap == null) {
            mReplaceMap = new ArrayMap<String, String>();
        }
        mReplaceMap.put(tag, to.getName());
        return this;
    }

    /**
     * Instructs the Builder to configure the LayoutInflater such that any attempt to inflate
     * a {@link View} of a given type will throw a {@link InflateException}.
     *
     * @param disallowedClass The Class type that will be disallowed.
     * @return Builder object post-modification.
     */
    public LayoutInflaterBuilder disallow(@NonNull Class disallowedClass) {
        assertIfAlreadyBuilt();
        if (mDisallowedClasses == null) {
            mDisallowedClasses = new ArraySet<Class>();
        }
        mDisallowedClasses.add(disallowedClass);
        return this;
    }

    /**
     * Builds and returns the LayoutInflater.  Afterwards, this Builder can no longer can be
     * used, all future calls on the Builder will throw {@link AssertionError}.
     */
    public LayoutInflater build() {
        assertIfAlreadyBuilt();
        mBuiltInflater =
                LayoutInflater.from(mFromContext).cloneInContext(mTargetContext);
        setFactoryIfNeeded(mBuiltInflater);
        setFilterIfNeeded(mBuiltInflater);
        return mBuiltInflater;
    }

    private void assertIfAlreadyBuilt() {
        if (mBuiltInflater != null) {
            throw new AssertionError("Cannot use this Builder after build() has been called.");
        }
    }

    private void setFactoryIfNeeded(LayoutInflater inflater) {
        if (mReplaceMap == null) {
            return;
        }
        inflater.setFactory(
                new LayoutInflater.Factory() {
                    @Override
                    public View onCreateView(String name, Context context, AttributeSet attrs) {
                        String replacingClassName = mReplaceMap.get(name);
                        if (replacingClassName != null) {
                            try {
                                return inflater.createView(replacingClassName, null, attrs);
                            } catch (ClassNotFoundException e) {
                                Log.e(TAG, "Could not replace " + name
                                        + " with " + replacingClassName
                                        + ", Exception: ", e);
                            }
                        }
                        return null;
                    }
                });
    }

    private void setFilterIfNeeded(LayoutInflater inflater) {
        if (mDisallowedClasses == null) {
            return;
        }
        inflater.setFilter(
                new LayoutInflater.Filter() {
                    @Override
                    public boolean onLoadClass(Class clazz) {
                        return !mDisallowedClasses.contains(clazz);
                    }
                });
    }
}
