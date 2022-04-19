/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.om;

import static com.android.server.om.OverlayManagerService.DEBUG;
import static com.android.server.om.OverlayManagerService.TAG;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.om.OverlayIdentifier;
import android.content.om.OverlayInfo;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Pair;
import android.util.Slog;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.Xml;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Data structure representing the current state of all overlay packages in the
 * system.
 *
 * Modifications to the data are signaled by returning true from any state mutating method.
 *
 * @see OverlayManagerService
 */
final class OverlayManagerSettings {
    /**
     * All overlay data for all users and target packages is stored in this list.
     * This keeps memory down, while increasing the cost of running queries or mutating the
     * data. This is ok, since changing of overlays is very rare and has larger costs associated
     * with it.
     *
     * The order of the items in the list is important, those with a lower index having a lower
     * priority.
     */
    private final ArrayList<SettingsItem> mItems = new ArrayList<>();

    @NonNull
    OverlayInfo init(@NonNull final OverlayIdentifier overlay, final int userId,
            @NonNull final String targetPackageName, @Nullable final String targetOverlayableName,
            @NonNull final String baseCodePath, boolean isMutable, boolean isEnabled, int priority,
            @Nullable String overlayCategory, boolean isFabricated) {
        remove(overlay, userId);
        final SettingsItem item = new SettingsItem(overlay, userId, targetPackageName,
                targetOverlayableName, baseCodePath, OverlayInfo.STATE_UNKNOWN, isEnabled,
                isMutable, priority, overlayCategory, isFabricated);
        insert(item);
        return item.getOverlayInfo();
    }

    /**
     * Returns true if the settings were modified, false if they remain the same.
     */
    boolean remove(@NonNull final OverlayIdentifier overlay, final int userId) {
        final int idx = select(overlay, userId);
        if (idx < 0) {
            return false;
        }
        mItems.remove(idx);
        return true;
    }

    @NonNull OverlayInfo getOverlayInfo(@NonNull final OverlayIdentifier overlay, final int userId)
            throws BadKeyException {
        final int idx = select(overlay, userId);
        if (idx < 0) {
            throw new BadKeyException(overlay, userId);
        }
        return mItems.get(idx).getOverlayInfo();
    }

    @Nullable
    OverlayInfo getNullableOverlayInfo(@NonNull final OverlayIdentifier overlay, final int userId) {
        final int idx = select(overlay, userId);
        if (idx < 0) {
            return null;
        }
        return mItems.get(idx).getOverlayInfo();
    }

    /**
     * Returns true if the settings were modified, false if they remain the same.
     */
    boolean setBaseCodePath(@NonNull final OverlayIdentifier overlay, final int userId,
            @NonNull final String path) throws BadKeyException {
        final int idx = select(overlay, userId);
        if (idx < 0) {
            throw new BadKeyException(overlay, userId);
        }
        return mItems.get(idx).setBaseCodePath(path);
    }

    boolean setCategory(@NonNull final OverlayIdentifier overlay, final int userId,
            @Nullable String category) throws BadKeyException {
        final int idx = select(overlay, userId);
        if (idx < 0) {
            throw new BadKeyException(overlay, userId);
        }
        return mItems.get(idx).setCategory(category);
    }

    boolean getEnabled(@NonNull final OverlayIdentifier overlay, final int userId)
            throws BadKeyException {
        final int idx = select(overlay, userId);
        if (idx < 0) {
            throw new BadKeyException(overlay, userId);
        }
        return mItems.get(idx).isEnabled();
    }

    /**
     * Returns true if the settings were modified, false if they remain the same.
     */
    boolean setEnabled(@NonNull final OverlayIdentifier overlay, final int userId,
            final boolean enable) throws BadKeyException {
        final int idx = select(overlay, userId);
        if (idx < 0) {
            throw new BadKeyException(overlay, userId);
        }
        return mItems.get(idx).setEnabled(enable);
    }

    @OverlayInfo.State int getState(@NonNull final OverlayIdentifier overlay, final int userId)
            throws BadKeyException {
        final int idx = select(overlay, userId);
        if (idx < 0) {
            throw new BadKeyException(overlay, userId);
        }
        return mItems.get(idx).getState();
    }

    /**
     * Returns true if the settings were modified, false if they remain the same.
     */
    boolean setState(@NonNull final OverlayIdentifier overlay, final int userId,
            final @OverlayInfo.State int state) throws BadKeyException {
        final int idx = select(overlay, userId);
        if (idx < 0) {
            throw new BadKeyException(overlay, userId);
        }
        return mItems.get(idx).setState(state);
    }

    List<OverlayInfo> getOverlaysForTarget(@NonNull final String targetPackageName,
            final int userId) {
        final List<SettingsItem> items = selectWhereTarget(targetPackageName, userId);
        return CollectionUtils.map(items, SettingsItem::getOverlayInfo);
    }

    ArrayMap<String, List<OverlayInfo>> getOverlaysForUser(final int userId) {
        final List<SettingsItem> items = selectWhereUser(userId);

        final ArrayMap<String, List<OverlayInfo>> targetInfos = new ArrayMap<>();
        for (int i = 0, n = items.size(); i < n; i++) {
            final SettingsItem item = items.get(i);
            targetInfos.computeIfAbsent(item.mTargetPackageName, (String) -> new ArrayList<>())
                    .add(item.getOverlayInfo());
        }
        return targetInfos;
    }

    Set<String> getAllBaseCodePaths() {
        final Set<String> paths = new ArraySet<>();
        mItems.forEach(item -> paths.add(item.mBaseCodePath));
        return paths;
    }

    Set<Pair<OverlayIdentifier, String>> getAllIdentifiersAndBaseCodePaths() {
        final Set<Pair<OverlayIdentifier, String>> set = new ArraySet<>();
        mItems.forEach(item -> set.add(new Pair(item.mOverlay, item.mBaseCodePath)));
        return set;
    }

    @NonNull
    List<OverlayInfo> removeIf(@NonNull final Predicate<OverlayInfo> predicate, final int userId) {
        return removeIf(info -> (predicate.test(info) && info.userId == userId));
    }

    @NonNull
    List<OverlayInfo> removeIf(final @NonNull Predicate<OverlayInfo> predicate) {
        List<OverlayInfo> removed = null;
        for (int i = mItems.size() - 1; i >= 0; i--) {
            final OverlayInfo info = mItems.get(i).getOverlayInfo();
            if (predicate.test(info)) {
                mItems.remove(i);
                removed = CollectionUtils.add(removed, info);
            }
        }
        return CollectionUtils.emptyIfNull(removed);
    }

    int[] getUsers() {
        return mItems.stream().mapToInt(SettingsItem::getUserId).distinct().toArray();
    }

    /**
     * Returns true if the settings were modified, false if they remain the same.
     */
    boolean removeUser(final int userId) {
        return mItems.removeIf(item -> {
            if (item.getUserId() == userId) {
                if (DEBUG) {
                    Slog.d(TAG, "Removing overlay " + item.mOverlay + " for user " + userId
                            + " from settings because user was removed");
                }
                return true;
            }
            return false;
        });
    }

    /**
     * Reassigns the priority of an overlay maintaining the values of the overlays other settings.
     */
    void setPriority(@NonNull final OverlayIdentifier overlay, final int userId,
            final int priority) throws BadKeyException {
        final int moveIdx = select(overlay, userId);
        if (moveIdx < 0) {
            throw new BadKeyException(overlay, userId);
        }

        final SettingsItem itemToMove = mItems.get(moveIdx);
        mItems.remove(moveIdx);
        itemToMove.setPriority(priority);
        insert(itemToMove);
    }

    /**
     * Returns true if the settings were modified, false if they remain the same.
     */
    boolean setPriority(@NonNull final OverlayIdentifier overlay,
            @NonNull final OverlayIdentifier newOverlay, final int userId) {
        if (overlay.equals(newOverlay)) {
            return false;
        }
        final int moveIdx = select(overlay, userId);
        if (moveIdx < 0) {
            return false;
        }

        final int parentIdx = select(newOverlay, userId);
        if (parentIdx < 0) {
            return false;
        }

        final SettingsItem itemToMove = mItems.get(moveIdx);

        // Make sure both packages are targeting the same package.
        if (!itemToMove.getTargetPackageName().equals(
                mItems.get(parentIdx).getTargetPackageName())) {
            return false;
        }

        mItems.remove(moveIdx);
        final int newParentIdx = select(newOverlay, userId) + 1;
        mItems.add(newParentIdx, itemToMove);
        return moveIdx != newParentIdx;
    }

    /**
     * Returns true if the settings were modified, false if they remain the same.
     */
    boolean setLowestPriority(@NonNull final OverlayIdentifier overlay, final int userId) {
        final int idx = select(overlay, userId);
        if (idx <= 0) {
            // If the item doesn't exist or is already the lowest, don't change anything.
            return false;
        }

        final SettingsItem item = mItems.get(idx);
        mItems.remove(item);
        mItems.add(0, item);
        return true;
    }

    /**
     * Returns true if the settings were modified, false if they remain the same.
     */
    boolean setHighestPriority(@NonNull final OverlayIdentifier overlay, final int userId) {
        final int idx = select(overlay, userId);

        // If the item doesn't exist or is already the highest, don't change anything.
        if (idx < 0 || idx == mItems.size() - 1) {
            return false;
        }

        final SettingsItem item = mItems.get(idx);
        mItems.remove(idx);
        mItems.add(item);
        return true;
    }

    /**
     * Inserts the item into the list of settings items.
     */
    private void insert(@NonNull SettingsItem item) {
        int i;
        for (i = mItems.size() - 1; i >= 0; i--) {
            SettingsItem parentItem = mItems.get(i);
            if (parentItem.mPriority <= item.getPriority()) {
                break;
            }
        }
        mItems.add(i + 1, item);
    }

    void dump(@NonNull final PrintWriter p, @NonNull DumpState dumpState) {
        // select items to display
        Stream<SettingsItem> items = mItems.stream();
        if (dumpState.getUserId() != UserHandle.USER_ALL) {
            items = items.filter(item -> item.mUserId == dumpState.getUserId());
        }
        if (dumpState.getPackageName() != null) {
            items = items.filter(item -> item.mOverlay.getPackageName()
                    .equals(dumpState.getPackageName()));
        }
        if (dumpState.getOverlayName() != null) {
            items = items.filter(item -> item.mOverlay.getOverlayName()
                    .equals(dumpState.getOverlayName()));
        }

        // display items
        final IndentingPrintWriter pw = new IndentingPrintWriter(p, "  ");
        if (dumpState.getField() != null) {
            items.forEach(item -> dumpSettingsItemField(pw, item, dumpState.getField()));
        } else {
            items.forEach(item -> dumpSettingsItem(pw, item));
        }
    }

    private void dumpSettingsItem(@NonNull final IndentingPrintWriter pw,
            @NonNull final SettingsItem item) {
        pw.println(item.mOverlay + ":" + item.getUserId() + " {");
        pw.increaseIndent();

        pw.println("mPackageName...........: " + item.mOverlay.getPackageName());
        pw.println("mOverlayName...........: " + item.mOverlay.getOverlayName());
        pw.println("mUserId................: " + item.getUserId());
        pw.println("mTargetPackageName.....: " + item.getTargetPackageName());
        pw.println("mTargetOverlayableName.: " + item.getTargetOverlayableName());
        pw.println("mBaseCodePath..........: " + item.getBaseCodePath());
        pw.println("mState.................: " + OverlayInfo.stateToString(item.getState()));
        pw.println("mIsEnabled.............: " + item.isEnabled());
        pw.println("mIsMutable.............: " + item.isMutable());
        pw.println("mPriority..............: " + item.mPriority);
        pw.println("mCategory..............: " + item.mCategory);
        pw.println("mIsFabricated..........: " + item.mIsFabricated);

        pw.decreaseIndent();
        pw.println("}");
    }

    private void dumpSettingsItemField(@NonNull final IndentingPrintWriter pw,
            @NonNull final SettingsItem item, @NonNull final String field) {
        switch (field) {
            case "packagename":
                pw.println(item.mOverlay.getPackageName());
                break;
            case "overlayname":
                pw.println(item.mOverlay.getOverlayName());
                break;
            case "userid":
                pw.println(item.mUserId);
                break;
            case "targetpackagename":
                pw.println(item.mTargetPackageName);
                break;
            case "targetoverlayablename":
                pw.println(item.mTargetOverlayableName);
                break;
            case "basecodepath":
                pw.println(item.mBaseCodePath);
                break;
            case "state":
                pw.println(OverlayInfo.stateToString(item.mState));
                break;
            case "isenabled":
                pw.println(item.mIsEnabled);
                break;
            case "ismutable":
                pw.println(item.mIsMutable);
                break;
            case "priority":
                pw.println(item.mPriority);
                break;
            case "category":
                pw.println(item.mCategory);
                break;
        }
    }

    void restore(@NonNull final InputStream is) throws IOException, XmlPullParserException {
        Serializer.restore(mItems, is);
    }

    void persist(@NonNull final OutputStream os) throws IOException, XmlPullParserException {
        Serializer.persist(mItems, os);
    }

    @VisibleForTesting
    static final class Serializer {
        private static final String TAG_OVERLAYS = "overlays";
        private static final String TAG_ITEM = "item";

        private static final String ATTR_BASE_CODE_PATH = "baseCodePath";
        private static final String ATTR_IS_ENABLED = "isEnabled";
        private static final String ATTR_PACKAGE_NAME = "packageName";
        private static final String ATTR_OVERLAY_NAME = "overlayName";
        private static final String ATTR_STATE = "state";
        private static final String ATTR_TARGET_PACKAGE_NAME = "targetPackageName";
        private static final String ATTR_TARGET_OVERLAYABLE_NAME = "targetOverlayableName";
        private static final String ATTR_IS_STATIC = "isStatic";
        private static final String ATTR_PRIORITY = "priority";
        private static final String ATTR_CATEGORY = "category";
        private static final String ATTR_USER_ID = "userId";
        private static final String ATTR_VERSION = "version";
        private static final String ATTR_IS_FABRICATED = "fabricated";

        @VisibleForTesting
        static final int CURRENT_VERSION = 4;

        public static void restore(@NonNull final ArrayList<SettingsItem> table,
                @NonNull final InputStream is) throws IOException, XmlPullParserException {
            table.clear();
            final TypedXmlPullParser parser = Xml.resolvePullParser(is);
            XmlUtils.beginDocument(parser, TAG_OVERLAYS);
            final int version = parser.getAttributeInt(null, ATTR_VERSION);
            if (version != CURRENT_VERSION) {
                upgrade(version);
            }

            final int depth = parser.getDepth();
            while (XmlUtils.nextElementWithin(parser, depth)) {
                if (TAG_ITEM.equals(parser.getName())) {
                    final SettingsItem item = restoreRow(parser, depth + 1);
                    table.add(item);
                }
            }
        }

        private static void upgrade(int oldVersion) throws XmlPullParserException {
            switch (oldVersion) {
                case 0:
                case 1:
                case 2:
                    // Throw an exception which will cause the overlay file to be ignored
                    // and overwritten.
                    throw new XmlPullParserException("old version " + oldVersion + "; ignoring");
                case 3:
                    // Upgrading from version 3 to 4 is not a breaking change so do not ignore the
                    // overlay file.
                    return;
                default:
                    throw new XmlPullParserException("unrecognized version " + oldVersion);
            }
        }

        private static SettingsItem restoreRow(@NonNull final TypedXmlPullParser parser,
                final int depth) throws IOException, XmlPullParserException {
            final OverlayIdentifier overlay = new OverlayIdentifier(
                    XmlUtils.readStringAttribute(parser, ATTR_PACKAGE_NAME),
                    XmlUtils.readStringAttribute(parser, ATTR_OVERLAY_NAME));
            final int userId = parser.getAttributeInt(null, ATTR_USER_ID);
            final String targetPackageName = XmlUtils.readStringAttribute(parser,
                    ATTR_TARGET_PACKAGE_NAME);
            final String targetOverlayableName = XmlUtils.readStringAttribute(parser,
                    ATTR_TARGET_OVERLAYABLE_NAME);
            final String baseCodePath = XmlUtils.readStringAttribute(parser, ATTR_BASE_CODE_PATH);
            final int state = parser.getAttributeInt(null, ATTR_STATE);
            final boolean isEnabled = parser.getAttributeBoolean(null, ATTR_IS_ENABLED, false);
            final boolean isStatic = parser.getAttributeBoolean(null, ATTR_IS_STATIC, false);
            final int priority = parser.getAttributeInt(null, ATTR_PRIORITY);
            final String category = XmlUtils.readStringAttribute(parser, ATTR_CATEGORY);
            final boolean isFabricated = parser.getAttributeBoolean(null, ATTR_IS_FABRICATED,
                    false);

            return new SettingsItem(overlay, userId, targetPackageName, targetOverlayableName,
                    baseCodePath, state, isEnabled, !isStatic, priority, category, isFabricated);
        }

        public static void persist(@NonNull final ArrayList<SettingsItem> table,
                @NonNull final OutputStream os) throws IOException, XmlPullParserException {
            final TypedXmlSerializer xml = Xml.resolveSerializer(os);
            xml.startDocument(null, true);
            xml.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            xml.startTag(null, TAG_OVERLAYS);
            xml.attributeInt(null, ATTR_VERSION, CURRENT_VERSION);

            final int n = table.size();
            for (int i = 0; i < n; i++) {
                final SettingsItem item = table.get(i);
                persistRow(xml, item);
            }
            xml.endTag(null, TAG_OVERLAYS);
            xml.endDocument();
        }

        private static void persistRow(@NonNull final TypedXmlSerializer xml,
                @NonNull final SettingsItem item) throws IOException {
            xml.startTag(null, TAG_ITEM);
            XmlUtils.writeStringAttribute(xml, ATTR_PACKAGE_NAME, item.mOverlay.getPackageName());
            XmlUtils.writeStringAttribute(xml, ATTR_OVERLAY_NAME, item.mOverlay.getOverlayName());
            xml.attributeInt(null, ATTR_USER_ID, item.mUserId);
            XmlUtils.writeStringAttribute(xml, ATTR_TARGET_PACKAGE_NAME, item.mTargetPackageName);
            XmlUtils.writeStringAttribute(xml, ATTR_TARGET_OVERLAYABLE_NAME,
                    item.mTargetOverlayableName);
            XmlUtils.writeStringAttribute(xml, ATTR_BASE_CODE_PATH, item.mBaseCodePath);
            xml.attributeInt(null, ATTR_STATE, item.mState);
            XmlUtils.writeBooleanAttribute(xml, ATTR_IS_ENABLED, item.mIsEnabled);
            XmlUtils.writeBooleanAttribute(xml, ATTR_IS_STATIC, !item.mIsMutable);
            xml.attributeInt(null, ATTR_PRIORITY, item.mPriority);
            XmlUtils.writeStringAttribute(xml, ATTR_CATEGORY, item.mCategory);
            XmlUtils.writeBooleanAttribute(xml, ATTR_IS_FABRICATED, item.mIsFabricated);
            xml.endTag(null, TAG_ITEM);
        }
    }

    private static final class SettingsItem {
        private final int mUserId;
        private final OverlayIdentifier mOverlay;
        private final String mTargetPackageName;
        private final String mTargetOverlayableName;
        private String mBaseCodePath;
        private @OverlayInfo.State int mState;
        private boolean mIsEnabled;
        private OverlayInfo mCache;
        private boolean mIsMutable;
        private int mPriority;
        private String mCategory;
        private boolean mIsFabricated;

        SettingsItem(@NonNull final OverlayIdentifier overlay, final int userId,
                @NonNull final String targetPackageName,
                @Nullable final String targetOverlayableName, @NonNull final String baseCodePath,
                final @OverlayInfo.State int state, final boolean isEnabled,
                final boolean isMutable, final int priority,  @Nullable String category,
                final boolean isFabricated) {
            mOverlay = overlay;
            mUserId = userId;
            mTargetPackageName = targetPackageName;
            mTargetOverlayableName = targetOverlayableName;
            mBaseCodePath = baseCodePath;
            mState = state;
            mIsEnabled = isEnabled;
            mCategory = category;
            mCache = null;
            mIsMutable = isMutable;
            mPriority = priority;
            mIsFabricated = isFabricated;
        }

        private String getTargetPackageName() {
            return mTargetPackageName;
        }

        private String getTargetOverlayableName() {
            return mTargetOverlayableName;
        }

        private int getUserId() {
            return mUserId;
        }

        private String getBaseCodePath() {
            return mBaseCodePath;
        }

        private boolean setBaseCodePath(@NonNull final String path) {
            if (!mBaseCodePath.equals(path)) {
                mBaseCodePath = path;
                invalidateCache();
                return true;
            }
            return false;
        }

        private @OverlayInfo.State int getState() {
            return mState;
        }

        private boolean setState(final @OverlayInfo.State int state) {
            if (mState != state) {
                mState = state;
                invalidateCache();
                return true;
            }
            return false;
        }

        private boolean isEnabled() {
            return mIsEnabled;
        }

        private boolean setEnabled(boolean enable) {
            if (!mIsMutable) {
                return false;
            }

            if (mIsEnabled != enable) {
                mIsEnabled = enable;
                invalidateCache();
                return true;
            }
            return false;
        }

        private boolean setCategory(String category) {
            if (!Objects.equals(mCategory, category)) {
                mCategory = (category == null) ? null : category.intern();
                invalidateCache();
                return true;
            }
            return false;
        }

        private OverlayInfo getOverlayInfo() {
            if (mCache == null) {
                mCache = new OverlayInfo(mOverlay.getPackageName(), mOverlay.getOverlayName(),
                        mTargetPackageName, mTargetOverlayableName, mCategory, mBaseCodePath,
                        mState, mUserId, mPriority, mIsMutable, mIsFabricated);
            }
            return mCache;
        }

        private void setPriority(int priority) {
            mPriority = priority;
            invalidateCache();
        }

        private void invalidateCache() {
            mCache = null;
        }

        private boolean isMutable() {
            return mIsMutable;
        }

        private int getPriority() {
            return mPriority;
        }
    }

    private int select(@NonNull final OverlayIdentifier overlay, final int userId) {
        final int n = mItems.size();
        for (int i = 0; i < n; i++) {
            final SettingsItem item = mItems.get(i);
            if (item.mUserId == userId && item.mOverlay.equals(overlay)) {
                return i;
            }
        }
        return -1;
    }

    private List<SettingsItem> selectWhereUser(final int userId) {
        final List<SettingsItem> selectedItems = new ArrayList<>();
        CollectionUtils.addIf(mItems, selectedItems, i -> i.mUserId == userId);
        return selectedItems;
    }

    private List<SettingsItem> selectWhereOverlay(@NonNull final String packageName,
            final int userId) {
        final List<SettingsItem> items = selectWhereUser(userId);
        items.removeIf(i -> !i.mOverlay.getPackageName().equals(packageName));
        return items;
    }

    private List<SettingsItem> selectWhereTarget(@NonNull final String targetPackageName,
            final int userId) {
        final List<SettingsItem> items = selectWhereUser(userId);
        items.removeIf(i -> !i.getTargetPackageName().equals(targetPackageName));
        return items;
    }

    static final class BadKeyException extends Exception {
        BadKeyException(@NonNull final OverlayIdentifier overlay, final int userId) {
            super("Bad key '" + overlay + "' for user " + userId );
        }
    }
}
