/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.ide.common.resources;

import com.android.ide.common.rendering.api.LayoutLog;
import com.android.ide.common.rendering.api.ResourceValue;
import com.android.ide.common.rendering.api.StyleResourceValue;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class ResourceResolver {

    public final static String RES_ANIMATOR = "animator";
    public final static String RES_STYLE = "style";
    public final static String RES_ATTR = "attr";
    public final static String RES_DIMEN = "dimen";
    public final static String RES_DRAWABLE = "drawable";
    public final static String RES_COLOR = "color";
    public final static String RES_LAYOUT = "layout";
    public final static String RES_STRING = "string";
    public final static String RES_ID = "id";

    public final static String REFERENCE_NULL = "@null";

    private final static String REFERENCE_STYLE = RES_STYLE + "/";
    private final static String PREFIX_ANDROID_RESOURCE_REF = "@android:";
    private final static String PREFIX_RESOURCE_REF = "@";
    private final static String PREFIX_ANDROID_THEME_REF = "?android:";
    private final static String PREFIX_THEME_REF = "?";
    private final static String PREFIX_ANDROID = "android:";


    private final IFrameworkResourceIdProvider mFrameworkProvider;
    private final Map<String, Map<String, ResourceValue>>  mProjectResources;
    private final Map<String, Map<String, ResourceValue>>  mFrameworkResources;
    private final LayoutLog mLogger;

    private final Map<StyleResourceValue, StyleResourceValue> mStyleInheritanceMap =
        new HashMap<StyleResourceValue, StyleResourceValue>();
    private StyleResourceValue mTheme;

    public interface IFrameworkResourceIdProvider {
        Integer getId(String resType, String resName);
    }

    private ResourceResolver(
            IFrameworkResourceIdProvider provider,
            Map<String, Map<String, ResourceValue>> projectResources,
            Map<String, Map<String, ResourceValue>> frameworkResources,
            LayoutLog logger) {
        mFrameworkProvider = provider;
        mProjectResources = projectResources;
        mFrameworkResources = frameworkResources;
        mLogger = logger;
    }

    /**
     * Creates a new ResourceResolver object.
     *
     * @param IFrameworkResourceIdProvider an optional framework resource ID provider
     * @param projectResources the project resources.
     * @param frameworkResources the framework resources.
     * @param themeName the name of the current theme.
     * @param isProjectTheme Is this a project theme?
     * @return
     */
    public static ResourceResolver create(
            IFrameworkResourceIdProvider provider,
            Map<String, Map<String, ResourceValue>> projectResources,
            Map<String, Map<String, ResourceValue>> frameworkResources,
            String themeName, boolean isProjectTheme, LayoutLog logger) {

        ResourceResolver resolver = new ResourceResolver(provider,
                projectResources, frameworkResources,
                logger);

        resolver.computeStyleMaps(themeName, isProjectTheme);

        return resolver;
    }

    public StyleResourceValue getTheme() {
        return mTheme;
    }

    /**
     * Returns a framework resource by type and name. The returned resource is resolved.
     * @param resourceType the type of the resource
     * @param resourceName the name of the resource
     */
    public ResourceValue getFrameworkResource(String resourceType, String resourceName) {
        return getResource(resourceType, resourceName, mFrameworkResources);
    }

    /**
     * Returns a project resource by type and name. The returned resource is resolved.
     * @param resourceType the type of the resource
     * @param resourceName the name of the resource
     */
    public ResourceValue getProjectResource(String resourceType, String resourceName) {
        return getResource(resourceType, resourceName, mProjectResources);
    }

    /**
     * Returns the {@link ResourceValue} matching a given name in the current theme. If the
     * item is not directly available in the theme, the method looks in its parent theme.
     *
     * @param itemName the name of the item to search for.
     * @return the {@link ResourceValue} object or <code>null</code>
     */
    public ResourceValue findItemInTheme(String itemName) {
        if (mTheme != null) {
            return findItemInStyle(mTheme, itemName);
        }

        return null;
    }

    /**
     * Returns the {@link ResourceValue} matching a given name in a given style. If the
     * item is not directly available in the style, the method looks in its parent style.
     *
     * @param style the style to search in
     * @param itemName the name of the item to search for.
     * @return the {@link ResourceValue} object or <code>null</code>
     */
    public ResourceValue findItemInStyle(StyleResourceValue style, String itemName) {
        ResourceValue item = style.findValue(itemName);

        // if we didn't find it, we look in the parent style (if applicable)
        if (item == null && mStyleInheritanceMap != null) {
            StyleResourceValue parentStyle = mStyleInheritanceMap.get(style);
            if (parentStyle != null) {
                return findItemInStyle(parentStyle, itemName);
            }
        }

        return item;
    }

    /**
     * Searches for, and returns a {@link ResourceValue} by its reference.
     * <p/>
     * The reference format can be:
     * <pre>@resType/resName</pre>
     * <pre>@android:resType/resName</pre>
     * <pre>@resType/android:resName</pre>
     * <pre>?resType/resName</pre>
     * <pre>?android:resType/resName</pre>
     * <pre>?resType/android:resName</pre>
     * Any other string format will return <code>null</code>.
     * <p/>
     * The actual format of a reference is <pre>@[namespace:]resType/resName</pre> but this method
     * only support the android namespace.
     *
     * @param reference the resource reference to search for.
     * @param forceFrameworkOnly if true all references are considered to be toward framework
     *      resource even if the reference does not include the android: prefix.
     * @return a {@link ResourceValue} or <code>null</code>.
     */
    public ResourceValue findResValue(String reference, boolean forceFrameworkOnly) {
        if (reference == null) {
            return null;
        }
        if (reference.startsWith(PREFIX_THEME_REF)) {
            // no theme? no need to go further!
            if (mTheme == null) {
                return null;
            }

            boolean frameworkOnly = false;

            // eliminate the prefix from the string
            if (reference.startsWith(PREFIX_ANDROID_THEME_REF)) {
                frameworkOnly = true;
                reference = reference.substring(PREFIX_ANDROID_THEME_REF.length());
            } else {
                reference = reference.substring(PREFIX_THEME_REF.length());
            }

            // at this point, value can contain type/name (drawable/foo for instance).
            // split it to make sure.
            String[] segments = reference.split("\\/");

            // we look for the referenced item name.
            String referenceName = null;

            if (segments.length == 2) {
                // there was a resType in the reference. If it's attr, we ignore it
                // else, we assert for now.
                if (RES_ATTR.equals(segments[0])) {
                    referenceName = segments[1];
                } else {
                    // At this time, no support for ?type/name where type is not "attr"
                    return null;
                }
            } else {
                // it's just an item name.
                referenceName = segments[0];
            }

            // now we look for android: in the referenceName in order to support format
            // such as: ?attr/android:name
            if (referenceName.startsWith(PREFIX_ANDROID)) {
                frameworkOnly = true;
                referenceName = referenceName.substring(PREFIX_ANDROID.length());
            }

            // Now look for the item in the theme, starting with the current one.
            if (frameworkOnly) {
                // FIXME for now we do the same as if it didn't specify android:
                return findItemInStyle(mTheme, referenceName);
            }

            return findItemInStyle(mTheme, referenceName);
        } else if (reference.startsWith(PREFIX_RESOURCE_REF)) {
            boolean frameworkOnly = false;

            // check for the specific null reference value.
            if (REFERENCE_NULL.equals(reference)) {
                return null;
            }

            // Eliminate the prefix from the string.
            if (reference.startsWith(PREFIX_ANDROID_RESOURCE_REF)) {
                frameworkOnly = true;
                reference = reference.substring(
                        PREFIX_ANDROID_RESOURCE_REF.length());
            } else {
                reference = reference.substring(PREFIX_RESOURCE_REF.length());
            }

            // at this point, value contains type/[android:]name (drawable/foo for instance)
            String[] segments = reference.split("\\/");

            // now we look for android: in the resource name in order to support format
            // such as: @drawable/android:name
            if (segments[1].startsWith(PREFIX_ANDROID)) {
                frameworkOnly = true;
                segments[1] = segments[1].substring(PREFIX_ANDROID.length());
            }

            return findResValue(segments[0], segments[1],
                    forceFrameworkOnly ? true :frameworkOnly);
        }

        // Looks like the value didn't reference anything. Return null.
        return null;
    }

    /**
     * Resolves the value of a resource, if the value references a theme or resource value.
     * <p/>
     * This method ensures that it returns a {@link ResourceValue} object that does not
     * reference another resource.
     * If the resource cannot be resolved, it returns <code>null</code>.
     * <p/>
     * If a value that does not need to be resolved is given, the method will return a new
     * instance of {@link ResourceValue} that contains the input value.
     *
     * @param type the type of the resource
     * @param name the name of the attribute containing this value.
     * @param value the resource value, or reference to resolve
     * @param isFrameworkValue whether the value is a framework value.
     *
     * @return the resolved resource value or <code>null</code> if it failed to resolve it.
     */
    public ResourceValue resolveValue(String type, String name, String value,
            boolean isFrameworkValue) {
        if (value == null) {
            return null;
        }

        // get the ResourceValue referenced by this value
        ResourceValue resValue = findResValue(value, isFrameworkValue);

        // if resValue is null, but value is not null, this means it was not a reference.
        // we return the name/value wrapper in a ResourceValue. the isFramework flag doesn't
        // matter.
        if (resValue == null) {
            return new ResourceValue(type, name, value, isFrameworkValue);
        }

        // we resolved a first reference, but we need to make sure this isn't a reference also.
        return resolveResValue(resValue);
    }

    /**
     * Returns the {@link ResourceValue} referenced by the value of <var>value</var>.
     * <p/>
     * This method ensures that it returns a {@link ResourceValue} object that does not
     * reference another resource.
     * If the resource cannot be resolved, it returns <code>null</code>.
     * <p/>
     * If a value that does not need to be resolved is given, the method will return the input
     * value.
     *
     * @param value the value containing the reference to resolve.
     * @return a {@link ResourceValue} object or <code>null</code>
     */
    public ResourceValue resolveResValue(ResourceValue value) {
        if (value == null) {
            return null;
        }

        // if the resource value is a style, we simply return it.
        if (value instanceof StyleResourceValue) {
            return value;
        }

        // else attempt to find another ResourceValue referenced by this one.
        ResourceValue resolvedValue = findResValue(value.getValue(), value.isFramework());

        // if the value did not reference anything, then we simply return the input value
        if (resolvedValue == null) {
            return value;
        }

        // otherwise, we attempt to resolve this new value as well
        return resolveResValue(resolvedValue);
    }


    /**
     * Searches for, and returns a {@link ResourceValue} by its name, and type.
     * @param resType the type of the resource
     * @param resName  the name of the resource
     * @param frameworkOnly if <code>true</code>, the method does not search in the
     * project resources
     */
    private ResourceValue findResValue(String resType, String resName, boolean frameworkOnly) {
        // map of ResouceValue for the given type
        Map<String, ResourceValue> typeMap;

        // if allowed, search in the project resources first.
        if (frameworkOnly == false) {
            typeMap = mProjectResources.get(resType);
            if (typeMap != null) {
                ResourceValue item = typeMap.get(resName);
                if (item != null) {
                    return item;
                }
            }
        }

        // now search in the framework resources.
        typeMap = mFrameworkResources.get(resType);
        if (typeMap != null) {
            ResourceValue item = typeMap.get(resName);
            if (item != null) {
                return item;
            }

            // if it was not found and the type is an id, it is possible that the ID was
            // generated dynamically when compiling the framework resources.
            // Look for it in the R map.
            if (mFrameworkProvider != null && RES_ID.equals(resType)) {
                if (mFrameworkProvider.getId(resType, resName) != null) {
                    return new ResourceValue(resType, resName, true);
                }
            }
        }

        // didn't find the resource anywhere.
        // This is normal if the resource is an ID that is generated automatically.
        // For other resources, we output a warning
        if ("+id".equals(resType) == false && "+android:id".equals(resType) == false) { //$NON-NLS-1$ //$NON-NLS-2$
            mLogger.warning(LayoutLog.TAG_RESOURCES_RESOLVE,
                    "Couldn't resolve resource @" +
                    (frameworkOnly ? "android:" : "") + resType + "/" + resName,
                    new ResourceValue(resType, resName, frameworkOnly));
        }
        return null;
    }

    ResourceValue getResource(String resourceType, String resourceName,
            Map<String, Map<String, ResourceValue>> resourceRepository) {
        Map<String, ResourceValue> typeMap = resourceRepository.get(resourceType);
        if (typeMap != null) {
            ResourceValue item = typeMap.get(resourceName);
            if (item != null) {
                item = resolveResValue(item);
                return item;
            }
        }

        // didn't find the resource anywhere.
        return null;

    }

    /**
     * Compute style information from the given list of style for the project and framework.
     * @param themeName the name of the current theme.
     * @param isProjectTheme Is this a project theme?
     */
    private void computeStyleMaps(String themeName, boolean isProjectTheme) {
        Map<String, ResourceValue> projectStyleMap = mProjectResources.get(RES_STYLE);
        Map<String, ResourceValue> frameworkStyleMap = mFrameworkResources.get(RES_STYLE);

        if (projectStyleMap != null && frameworkStyleMap != null) {
            // first, get the theme
            ResourceValue theme = null;

            // project theme names have been prepended with a *
            if (isProjectTheme) {
                theme = projectStyleMap.get(themeName);
            } else {
                theme = frameworkStyleMap.get(themeName);
            }

            if (theme instanceof StyleResourceValue) {
                // compute the inheritance map for both the project and framework styles
                computeStyleInheritance(projectStyleMap.values(), projectStyleMap,
                        frameworkStyleMap);

                // Compute the style inheritance for the framework styles/themes.
                // Since, for those, the style parent values do not contain 'android:'
                // we want to force looking in the framework style only to avoid using
                // similarly named styles from the project.
                // To do this, we pass null in lieu of the project style map.
                computeStyleInheritance(frameworkStyleMap.values(), null /*inProjectStyleMap */,
                        frameworkStyleMap);

                mTheme = (StyleResourceValue) theme;
            }
        }
    }



    /**
     * Compute the parent style for all the styles in a given list.
     * @param styles the styles for which we compute the parent.
     * @param inProjectStyleMap the map of project styles.
     * @param inFrameworkStyleMap the map of framework styles.
     * @param outInheritanceMap the map of style inheritance. This is filled by the method.
     */
    private void computeStyleInheritance(Collection<ResourceValue> styles,
            Map<String, ResourceValue> inProjectStyleMap,
            Map<String, ResourceValue> inFrameworkStyleMap) {
        for (ResourceValue value : styles) {
            if (value instanceof StyleResourceValue) {
                StyleResourceValue style = (StyleResourceValue)value;
                StyleResourceValue parentStyle = null;

                // first look for a specified parent.
                String parentName = style.getParentStyle();

                // no specified parent? try to infer it from the name of the style.
                if (parentName == null) {
                    parentName = getParentName(value.getName());
                }

                if (parentName != null) {
                    parentStyle = getStyle(parentName, inProjectStyleMap, inFrameworkStyleMap);

                    if (parentStyle != null) {
                        mStyleInheritanceMap.put(style, parentStyle);
                    }
                }
            }
        }
    }


    /**
     * Computes the name of the parent style, or <code>null</code> if the style is a root style.
     */
    private String getParentName(String styleName) {
        int index = styleName.lastIndexOf('.');
        if (index != -1) {
            return styleName.substring(0, index);
        }

        return null;
    }

    /**
     * Searches for and returns the {@link StyleResourceValue} from a given name.
     * <p/>The format of the name can be:
     * <ul>
     * <li>[android:]&lt;name&gt;</li>
     * <li>[android:]style/&lt;name&gt;</li>
     * <li>@[android:]style/&lt;name&gt;</li>
     * </ul>
     * @param parentName the name of the style.
     * @param inProjectStyleMap the project style map. Can be <code>null</code>
     * @param inFrameworkStyleMap the framework style map.
     * @return The matching {@link StyleResourceValue} object or <code>null</code> if not found.
     */
    private StyleResourceValue getStyle(String parentName,
            Map<String, ResourceValue> inProjectStyleMap,
            Map<String, ResourceValue> inFrameworkStyleMap) {
        boolean frameworkOnly = false;

        String name = parentName;

        // remove the useless @ if it's there
        if (name.startsWith(PREFIX_RESOURCE_REF)) {
            name = name.substring(PREFIX_RESOURCE_REF.length());
        }

        // check for framework identifier.
        if (name.startsWith(PREFIX_ANDROID)) {
            frameworkOnly = true;
            name = name.substring(PREFIX_ANDROID.length());
        }

        // at this point we could have the format <type>/<name>. we want only the name as long as
        // the type is style.
        if (name.startsWith(REFERENCE_STYLE)) {
            name = name.substring(REFERENCE_STYLE.length());
        } else if (name.indexOf('/') != -1) {
            return null;
        }

        ResourceValue parent = null;

        // if allowed, search in the project resources.
        if (frameworkOnly == false && inProjectStyleMap != null) {
            parent = inProjectStyleMap.get(name);
        }

        // if not found, then look in the framework resources.
        if (parent == null) {
            parent = inFrameworkStyleMap.get(name);
        }

        // make sure the result is the proper class type and return it.
        if (parent instanceof StyleResourceValue) {
            return (StyleResourceValue)parent;
        }

        assert false;
        mLogger.error(LayoutLog.TAG_RESOURCES_RESOLVE,
                String.format("Unable to resolve parent style name: %s", parentName),
                null /*data*/);

        return null;
    }


}
