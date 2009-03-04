/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.layoutlib.api;

/**
 * Callback for project information needed by the Layout Library.
 * Classes implementing this interface provide methods giving access to some project data, like
 * resource resolution, namespace information, and instantiation of custom view.
 */
public interface IProjectCallback {
    
    /**
     * Loads a custom view with the given constructor signature and arguments.
     * @param name The fully qualified name of the class.
     * @param constructorSignature The signature of the class to use
     * @param constructorArgs The arguments to use on the constructor
     * @return A newly instantiated android.view.View object.
     * @throws ClassNotFoundException.
     * @throws Exception 
     */
    @SuppressWarnings("unchecked")
    Object loadView(String name, Class[] constructorSignature, Object[] constructorArgs)
        throws ClassNotFoundException, Exception;
    
    /**
     * Returns the namespace of the application.
     * <p/>This lets the Layout Lib load custom attributes for custom views.
     */
    String getNamespace();
    
    /**
     * Resolves the id of a resource Id.
     * <p/>The resource id is the value of a <code>R.&lt;type&gt;.&lt;name&gt;</code>, and
     * this method will return both the type and name of the resource.
     * @param id the Id to resolve.
     * @return an array of 2 strings containing the resource name and type, or null if the id
     * does not match any resource. 
     */
    String[] resolveResourceValue(int id);
    
    /**
     * Resolves the id of a resource Id of type int[]
     * <p/>The resource id is the value of a R.styleable.&lt;name&gt;, and this method will
     * return the name of the resource.
     * @param id the Id to resolve.
     * @return the name of the resource or <code>null</code> if not found.
     */
    String resolveResourceValue(int[] id);
    
    /**
     * Returns the id of a resource.
     * <p/>The provided type and name must match an existing constant defined as
     * <code>R.&lt;type&gt;.&lt;name&gt;</code>.
     * @param type the type of the resource
     * @param name the name of the resource
     * @return an Integer containing the resource Id, or <code>null</code> if not found.
     */
    Integer getResourceValue(String type, String name);

}
