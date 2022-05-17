/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.pm;

import android.annotation.NonNull;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.PatternMatcher;
import android.util.Printer;

import com.android.server.utils.Snappable;
import com.android.server.utils.WatchableImpl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A watched variant of {@link IntentFilter}.  The class consists of a
 * {@link IntentFilter} attribute and methods that are identical to those in IntentFilter,
 * forwarding to the attribute.
 *
 * @hide
 */
public class WatchedIntentFilter
        extends WatchableImpl
        implements Snappable<WatchedIntentFilter> {

    // Watch for modifications made through an {@link Iterator}.
    private class WatchedIterator<E> implements Iterator<E> {
        private final Iterator<E> mIterator;
        WatchedIterator(@NonNull Iterator<E> i) {
            mIterator = i;
        }
        public boolean hasNext() {
            return mIterator.hasNext();
        }
        public E next() {
            return mIterator.next();
        }
        public void remove() {
            mIterator.remove();
            WatchedIntentFilter.this.onChanged();
        }
        public void forEachRemaining(Consumer<? super E> action) {
            mIterator.forEachRemaining(action);
            WatchedIntentFilter.this.onChanged();
        }
    }

    // A convenience function to wrap an iterator result, but only if it is not null.
    private <E> Iterator<E> maybeWatch(Iterator<E> i) {
        return i == null ? i : new WatchedIterator<>(i);
    }

    // The wrapped {@link IntentFilter}
    protected IntentFilter mFilter;

    private void onChanged() {
        dispatchChange(this);
    }

    protected WatchedIntentFilter() {
        mFilter = new IntentFilter();
    }

    // Convert an {@link IntentFilter} to a {@link WatchedIntentFilter}
    public WatchedIntentFilter(IntentFilter f) {
        mFilter = new IntentFilter(f);
    }

    // The copy constructor is used to create a snapshot of the object.
    protected WatchedIntentFilter(WatchedIntentFilter f) {
        this(f.getIntentFilter());
    }

    /**
     * Create a WatchedIntentFilter based on an action
     * @see IntentFilter#IntentFilter(String)
     */
    public WatchedIntentFilter(String action) {
        mFilter = new IntentFilter(action);
    }

    /**
     * Create a WatchedIntentFilter based on an action and a data type.
     * @see IntentFilter#IntentFilter(String, String)
     */
    public WatchedIntentFilter(String action, String dataType)
            throws IntentFilter.MalformedMimeTypeException {
        mFilter = new IntentFilter(action, dataType);
    }

    /**
     * Return a clone of the filter represented by this object.
     */
    public WatchedIntentFilter cloneFilter() {
        return new WatchedIntentFilter(mFilter);
    }

    /**
     * Return the {@link IntentFilter} represented by this object.
     */
    public IntentFilter getIntentFilter() {
        return mFilter;
    }

    /**
     * @see IntentFilter#setPriority(int)
     */
    public final void setPriority(int priority) {
        mFilter.setPriority(priority);
        onChanged();
    }

    /**
     * @see IntentFilter#getPriority()
     */
    public final int getPriority() {
        return mFilter.getPriority();
    }

    /**
     * @see IntentFilter#setOrder(int)
     */
    public final void setOrder(int order) {
        mFilter.setOrder(order);
        onChanged();
    }

    /**
     * @see IntentFilter#getOrder()
     */
    public final int getOrder() {
        return mFilter.getOrder();
    }

    /**
     * @see IntentFilter#getAutoVerify()
     */
    public final boolean getAutoVerify() {
        return mFilter.getAutoVerify();
    }

    /**
     * @see IntentFilter#handleAllWebDataURI()
     */
    public final boolean handleAllWebDataURI() {
        return mFilter.handleAllWebDataURI();
    }

    /**
     * @see IntentFilter#handlesWebUris(boolean)
     */
    public final boolean handlesWebUris(boolean onlyWebSchemes) {
        return mFilter.handlesWebUris(onlyWebSchemes);
    }

    /**
     * @see IntentFilter#needsVerification()
     */
    public final boolean needsVerification() {
        return mFilter.needsVerification();
    }

    /**
     * @see IntentFilter#setVerified(boolean)
     */
    public void setVerified(boolean verified) {
        mFilter.setVerified(verified);
        onChanged();
    }

    /**
     * @see IntentFilter#setVisibilityToInstantApp(int)
     */
    public void setVisibilityToInstantApp(int visibility) {
        mFilter.setVisibilityToInstantApp(visibility);
        onChanged();
    }

    /**
     * @see IntentFilter#getVisibilityToInstantApp()
     */
    public int getVisibilityToInstantApp() {
        return mFilter.getVisibilityToInstantApp();
    }

    /**
     * @see IntentFilter#isVisibleToInstantApp()
     */
    public boolean isVisibleToInstantApp() {
        return mFilter.isVisibleToInstantApp();
    }

    /**
     * @see IntentFilter#isExplicitlyVisibleToInstantApp()
     */
    public boolean isExplicitlyVisibleToInstantApp() {
        return mFilter.isExplicitlyVisibleToInstantApp();
    }

    /**
     * @see IntentFilter#isImplicitlyVisibleToInstantApp()
     */
    public boolean isImplicitlyVisibleToInstantApp() {
        return mFilter.isImplicitlyVisibleToInstantApp();
    }

    /**
     * @see IntentFilter#addAction(String)
     */
    public final void addAction(String action) {
        mFilter.addAction(action);
        onChanged();
    }

    /**
     * @see IntentFilter#countActions()
     */
    public final int countActions() {
        return mFilter.countActions();
    }

    /**
     * @see IntentFilter#getAction(int)
     */
    public final String getAction(int index) {
        return mFilter.getAction(index);
    }

    /**
     * @see IntentFilter#hasAction(String)
     */
    public final boolean hasAction(String action) {
        return mFilter.hasAction(action);
    }

    /**
     * @see IntentFilter#matchAction(String)
     */
    public final boolean matchAction(String action) {
        return mFilter.matchAction(action);
    }

    /**
     * @see IntentFilter#actionsIterator()
     */
    public final Iterator<String> actionsIterator() {
        return maybeWatch(mFilter.actionsIterator());
    }

    /**
     * @see IntentFilter#addDataType(String)
     */
    public final void addDataType(String type)
            throws IntentFilter.MalformedMimeTypeException {
        mFilter.addDataType(type);
        onChanged();
    }

    /**
     * @see IntentFilter#addDynamicDataType(String)
     */
    public final void addDynamicDataType(String type)
            throws IntentFilter.MalformedMimeTypeException {
        mFilter.addDynamicDataType(type);
        onChanged();
    }

    /**
     * @see IntentFilter#clearDynamicDataTypes()
     */
    public final void clearDynamicDataTypes() {
        mFilter.clearDynamicDataTypes();
        onChanged();
    }

    /**
     * @see IntentFilter#countStaticDataTypes()
     */
    public int countStaticDataTypes() {
        return mFilter.countStaticDataTypes();
    }

    /**
     * @see IntentFilter#hasDataType(String)
     */
    public final boolean hasDataType(String type) {
        return mFilter.hasDataType(type);
    }

    /**
     * @see IntentFilter#hasExactDynamicDataType(String)
     */
    public final boolean hasExactDynamicDataType(String type) {
        return mFilter.hasExactDynamicDataType(type);
    }

    /**
     * @see IntentFilter#hasExactStaticDataType(String)
     */
    public final boolean hasExactStaticDataType(String type) {
        return mFilter.hasExactStaticDataType(type);
    }

    /**
     * @see IntentFilter#countDataTypes()
     */
    public final int countDataTypes() {
        return mFilter.countDataTypes();
    }

    /**
     * @see IntentFilter#getDataType(int)
     */
    public final String getDataType(int index) {
        return mFilter.getDataType(index);
    }

    /**
     * @see IntentFilter#typesIterator()
     */
    public final Iterator<String> typesIterator() {
        return maybeWatch(mFilter.typesIterator());
    }

    /**
    /**
     * @see IntentFilter#dataTypes()
     */
    public final List<String> dataTypes() {
        return mFilter.dataTypes();
    }

    /**
     * @see IntentFilter#addMimeGroup(String)
     */
    public final void addMimeGroup(String name) {
        mFilter.addMimeGroup(name);
        onChanged();
    }

    /**
     * @see IntentFilter#hasMimeGroup(String)
     */
    public final boolean hasMimeGroup(String name) {
        return mFilter.hasMimeGroup(name);
    }

    /**
     * @see IntentFilter#getMimeGroup(int)
     */
    public final String getMimeGroup(int index) {
        return mFilter.getMimeGroup(index);
    }

    /**
     * @see IntentFilter#countMimeGroups()
     */
    public final int countMimeGroups() {
        return mFilter.countMimeGroups();
    }

    /**
     * @see IntentAction@mimeGroupsIterator()
     */
    public final Iterator<String> mimeGroupsIterator() {
        return maybeWatch(mFilter.mimeGroupsIterator());
    }

    /**
     * @see IntentFilter#addDataScheme(String)
     */
    public final void addDataScheme(String scheme) {
        mFilter.addDataScheme(scheme);
        onChanged();
    }

    /**
     * @see IntentFilter#countDataSchemes()
     */
    public final int countDataSchemes() {
        return mFilter.countDataSchemes();
    }

    /**
     * @see IntentFilter#getDataScheme(int)
     */
    public final String getDataScheme(int index) {
        return mFilter.getDataScheme(index);
    }

    /**
     * @see IntentFilter#hasDataScheme(String)
     */
    public final boolean hasDataScheme(String scheme) {
        return mFilter.hasDataScheme(scheme);
    }

    /**
     * @see IntentFilter#schemesIterator()
     */
    public final Iterator<String> schemesIterator() {
        return maybeWatch(mFilter.schemesIterator());
    }

    /**
     * @see IntentFilter#addDataSchemeSpecificPart(String, int)
     */
    public final void addDataSchemeSpecificPart(String ssp, int type) {
        mFilter.addDataSchemeSpecificPart(ssp, type);
        onChanged();
    }

    /**
     * @see IntentFilter#addDataSchemeSpecificPart(PatternMatcher)
     */
    public final void addDataSchemeSpecificPart(PatternMatcher ssp) {
        mFilter.addDataSchemeSpecificPart(ssp);
        onChanged();
    }

    /**
     * @see IntentFilter#countDataSchemeSpecificParts()
     */
    public final int countDataSchemeSpecificParts() {
        return mFilter.countDataSchemeSpecificParts();
    }

    /**
     * @see IntentFilter#getDataSchemeSpecificPart(int)
     */
    public final PatternMatcher getDataSchemeSpecificPart(int index) {
        return mFilter.getDataSchemeSpecificPart(index);
    }

    /**
     * @see IntentFilter#hasDataSchemeSpecificPart(String)
     */
    public final boolean hasDataSchemeSpecificPart(String data) {
        return mFilter.hasDataSchemeSpecificPart(data);
    }

    /**
     * @see IntentFilter#schemeSpecificPartsIterator()
     */
    public final Iterator<PatternMatcher> schemeSpecificPartsIterator() {
        return maybeWatch(mFilter.schemeSpecificPartsIterator());
    }

    /**
     * @see IntentFilter#addDataAuthority(String, String)
     */
    public final void addDataAuthority(String host, String port) {
        mFilter.addDataAuthority(host, port);
        onChanged();
    }

    /**
     * @see IntentFilter#addDataAuthority(IntentFilter.AuthorityEntry)
     */
    public final void addDataAuthority(IntentFilter.AuthorityEntry ent) {
        mFilter.addDataAuthority(ent);
        onChanged();
    }

    /**
     * @see IntentFilter#countDataAuthorities()
     */
    public final int countDataAuthorities() {
        return mFilter.countDataAuthorities();
    }

    /**
     * @see IntentFilter#getDataAuthority(int)
     */
    public final IntentFilter.AuthorityEntry getDataAuthority(int index) {
        return mFilter.getDataAuthority(index);
    }

    /**
     * @see IntentFilter#hasDataAuthority(Uri)
     */
    public final boolean hasDataAuthority(Uri data) {
        return mFilter.hasDataAuthority(data);
    }

    /**
     * @see IntentFilter#authoritiesIterator()
     */
    public final Iterator<IntentFilter.AuthorityEntry> authoritiesIterator() {
        return maybeWatch(mFilter.authoritiesIterator());
    }

    /**
     * @see IntentFilter#addDataPath(String, int)
     */
    public final void addDataPath(String path, int type) {
        mFilter.addDataPath(path, type);
        onChanged();
    }

    /**
     * @see IntentFilter#addDataPath(PatternMatcher)
     */
    public final void addDataPath(PatternMatcher path) {
        mFilter.addDataPath(path);
        onChanged();
    }

    /**
     * @see IntentFilter#countDataPaths()
     */
    public final int countDataPaths() {
        return mFilter.countDataPaths();
    }

    /**
     * @see IntentFilter#getDataPath(int)
     */
    public final PatternMatcher getDataPath(int index) {
        return mFilter.getDataPath(index);
    }

    /**
     * @see IntentFilter#hasDataPath(String)
     */
    public final boolean hasDataPath(String data) {
        return mFilter.hasDataPath(data);
    }

    /**
     * @see IntentFilter#pathsIterator()
     */
    public final Iterator<PatternMatcher> pathsIterator() {
        return maybeWatch(mFilter.pathsIterator());
    }

    /**
     * @see IntentFilter#matchDataAuthority(Uri)
     */
    public final int matchDataAuthority(Uri data) {
        return mFilter.matchDataAuthority(data);
    }

    /**
     * @see IntentFilter#matchDataAuthority(Uri, boolean)
     */
    public final int matchDataAuthority(Uri data, boolean wildcardSupported) {
        return mFilter.matchDataAuthority(data, wildcardSupported);
    }

    /**
     * @see IntentFilter#matchData(String, String, Uri)
     */
    public final int matchData(String type, String scheme, Uri data) {
        return mFilter.matchData(type, scheme, data);
    }

    /**
     * @see IntentFilter#addCategory(String)
     */
    public final void addCategory(String category) {
        mFilter.addCategory(category);
    }

    /**
     * @see IntentFilter#countCategories()
     */
    public final int countCategories() {
        return mFilter.countCategories();
    }

    /**
     * @see IntentFilter#getCategory(int)
     */
    public final String getCategory(int index) {
        return mFilter.getCategory(index);
    }

    /**
     * @see IntentFilter#hasCategory(String)
     */
    public final boolean hasCategory(String category) {
        return mFilter.hasCategory(category);
    }

    /**
     * @see IntentFilter#categoriesIterator()
     */
    public final Iterator<String> categoriesIterator() {
        return maybeWatch(mFilter.categoriesIterator());
    }

    /**
     * @see IntentFilter#matchCategories(Set<String>)
     */
    public final String matchCategories(Set<String> categories) {
        return mFilter.matchCategories(categories);
    }

    /**
     * @see IntentFilter#match(ContentResolver, Intent, boolean, String)
     */
    public final int match(ContentResolver resolver, Intent intent,
            boolean resolve, String logTag) {
        return mFilter.match(resolver, intent,
                resolve, logTag);
    }

    /**
     * @see IntentFilter#match(String, String, String, Uri, Set<String>, String)
     */
    public final int match(String action, String type, String scheme,
            Uri data, Set<String> categories, String logTag) {
        return mFilter.match(action, type, scheme,
                data, categories, logTag);
    }

    /**
     * @see IntentFilter#match(String, String, String, Uri, Set<String>, String, boolean,
            Collection<String> ignoreActions)
     */
    public final int match(String action, String type, String scheme,
            Uri data, Set<String> categories, String logTag, boolean supportWildcards,
            Collection<String> ignoreActions) {
        return mFilter.match(action, type, scheme,
                data, categories, logTag, supportWildcards,
                ignoreActions);
    }

    /**
     * @see IntentFilter#dump(Printer, String)
     */
    public void dump(Printer du, String prefix) {
        mFilter.dump(du, prefix);
    }

    /**
     * @see IntentFilter#describeContents()
     */
    public final int describeContents() {
        return mFilter.describeContents();
    }

    /**
     * @see IntentFilter#debugCheck()
     */
    public boolean debugCheck() {
        return mFilter.debugCheck();
    }

    /**
     * @see IntentFilter#getHostsList()
     */
    public ArrayList<String> getHostsList() {
        return mFilter.getHostsList();
    }

    /**
     * @see IntentFilter#getHosts()
     */
    public String[] getHosts() {
        return mFilter.getHosts();
    }

    /**
     * Convert a list of {@link IntentFilter} into a list of {@link WatchedIntentFilter}
     */
    public static List<WatchedIntentFilter> toWatchedIntentFilterList(List<IntentFilter> inList) {
        ArrayList<WatchedIntentFilter> outList = new ArrayList<>();
        for (int i = 0; i < inList.size(); i++) {
            outList.add(new WatchedIntentFilter(inList.get(i)));
        }
        return outList;
    }

    /**
     * Convert a list of {@link IntentFilter} into a list of {@link WatchedIntentFilter}
     */
    public static List<IntentFilter> toIntentFilterList(List<WatchedIntentFilter> inList) {
        ArrayList<IntentFilter> outList = new ArrayList<>();
        for (int i = 0; i < inList.size(); i++) {
            outList.add(inList.get(i).getIntentFilter());
        }
        return outList;
    }

    /**
     * Create a snapshot by cloning the object.
     */
    public WatchedIntentFilter snapshot() {
        return new WatchedIntentFilter(this);
    }
}
