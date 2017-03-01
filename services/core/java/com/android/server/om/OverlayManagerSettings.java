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

import static android.content.om.OverlayInfo.STATE_UNKNOWN;

import static com.android.server.om.OverlayManagerService.DEBUG;
import static com.android.server.om.OverlayManagerService.TAG;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.om.OverlayInfo;
import android.util.AndroidRuntimeException;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

/**
 * Data structure representing the current state of all overlay packages in the
 * system.
 *
 * Modifications to the data are exposed through the ChangeListener interface.
 *
 * @see ChangeListener
 * @see OverlayManagerService
 */
final class OverlayManagerSettings {
    private final List<ChangeListener> mListeners = new ArrayList<>();

    private final ArrayList<SettingsItem> mItems = new ArrayList<>();

    void init(@NonNull final String packageName, final int userId,
            @NonNull final String targetPackageName, @NonNull final String baseCodePath) {
        remove(packageName, userId);
        final SettingsItem item =
                new SettingsItem(packageName, userId, targetPackageName, baseCodePath);
        mItems.add(item);
    }

    void remove(@NonNull final String packageName, final int userId) {
        final SettingsItem item = select(packageName, userId);
        if (item == null) {
            return;
        }
        final OverlayInfo oi = item.getOverlayInfo();
        mItems.remove(item);
        if (oi != null) {
            notifyOverlayRemoved(oi);
        }
    }

    boolean contains(@NonNull final String packageName, final int userId) {
        return select(packageName, userId) != null;
    }

    OverlayInfo getOverlayInfo(@NonNull final String packageName, final int userId)
            throws BadKeyException {
        final SettingsItem item = select(packageName, userId);
        if (item == null) {
            throw new BadKeyException(packageName, userId);
        }
        return item.getOverlayInfo();
    }

    String getTargetPackageName(@NonNull final String packageName, final int userId)
            throws BadKeyException {
        final SettingsItem item = select(packageName, userId);
        if (item == null) {
            throw new BadKeyException(packageName, userId);
        }
        return item.getTargetPackageName();
    }

    void setBaseCodePath(@NonNull final String packageName, final int userId,
            @NonNull final String path) throws BadKeyException {
        final SettingsItem item = select(packageName, userId);
        if (item == null) {
            throw new BadKeyException(packageName, userId);
        }
        item.setBaseCodePath(path);
        notifySettingsChanged();
    }

    boolean getEnabled(@NonNull final String packageName, final int userId) throws BadKeyException {
        final SettingsItem item = select(packageName, userId);
        if (item == null) {
            throw new BadKeyException(packageName, userId);
        }
        return item.isEnabled();
    }

    void setEnabled(@NonNull final String packageName, final int userId, final boolean enable)
            throws BadKeyException {
        final SettingsItem item = select(packageName, userId);
        if (item == null) {
            throw new BadKeyException(packageName, userId);
        }
        if (enable == item.isEnabled()) {
            return; // nothing to do
        }

        item.setEnabled(enable);
        notifySettingsChanged();
    }

    int getState(@NonNull final String packageName, final int userId) throws BadKeyException {
        final SettingsItem item = select(packageName, userId);
        if (item == null) {
            throw new BadKeyException(packageName, userId);
        }
        return item.getState();
    }

    void setState(@NonNull final String packageName, final int userId, final int state)
            throws BadKeyException {
        final SettingsItem item = select(packageName, userId);
        if (item == null) {
            throw new BadKeyException(packageName, userId);
        }
        final OverlayInfo previous = item.getOverlayInfo();
        item.setState(state);
        final OverlayInfo current = item.getOverlayInfo();
        if (previous.state == STATE_UNKNOWN) {
            notifyOverlayAdded(current);
            notifySettingsChanged();
        } else if (current.state != previous.state) {
            notifyOverlayChanged(current, previous);
            notifySettingsChanged();
        }
    }

    List<OverlayInfo> getOverlaysForTarget(@NonNull final String targetPackageName,
            final int userId) {
        final List<SettingsItem> items = selectWhereTarget(targetPackageName, userId);
        if (items.isEmpty()) {
            return Collections.emptyList();
        }
        final List<OverlayInfo> out = new ArrayList<>(items.size());
        final int N = items.size();
        for (int i = 0; i < N; i++) {
            final SettingsItem item = items.get(i);
            out.add(item.getOverlayInfo());
        }
        return out;
    }

    ArrayMap<String, List<OverlayInfo>> getOverlaysForUser(final int userId) {
        final List<SettingsItem> items = selectWhereUser(userId);
        if (items.isEmpty()) {
            return ArrayMap.EMPTY;
        }
        final ArrayMap<String, List<OverlayInfo>> out = new ArrayMap<>(items.size());
        final int N = items.size();
        for (int i = 0; i < N; i++) {
            final SettingsItem item = items.get(i);
            final String targetPackageName = item.getTargetPackageName();
            if (!out.containsKey(targetPackageName)) {
                out.put(targetPackageName, new ArrayList<OverlayInfo>());
            }
            final List<OverlayInfo> overlays = out.get(targetPackageName);
            overlays.add(item.getOverlayInfo());
        }
        return out;
    }

    List<String> getTargetPackageNamesForUser(final int userId) {
        final List<SettingsItem> items = selectWhereUser(userId);
        if (items.isEmpty()) {
            return Collections.emptyList();
        }
        final List<String> out = new ArrayList<>();
        final int N = items.size();
        for (int i = 0; i < N; i++) {
            final SettingsItem item = items.get(i);
            final String targetPackageName = item.getTargetPackageName();
            if (!out.contains(targetPackageName)) {
                out.add(targetPackageName);
            }
        }
        return out;
    }

    List<Integer> getUsers() {
        final ArrayList<Integer> users = new ArrayList<>();
        final int N = mItems.size();
        for (int i = 0; i < N; i++) {
            final SettingsItem item = mItems.get(i);
            if (!users.contains(item.userId)) {
                users.add(item.userId);
            }
        }
        return users;
    }

    void removeUser(final int userId) {
        final Iterator<SettingsItem> iter = mItems.iterator();
        while (iter.hasNext()) {
            final SettingsItem item = iter.next();
            if (item.userId == userId) {
                iter.remove();
            }
        }
    }

    boolean setPriority(@NonNull final String packageName,
            @NonNull final String newParentPackageName, final int userId) {
        if (packageName.equals(newParentPackageName)) {
            return false;
        }
        final SettingsItem rowToMove = select(packageName, userId);
        if (rowToMove == null) {
            return false;
        }
        final SettingsItem newParentRow = select(newParentPackageName, userId);
        if (newParentRow == null) {
            return false;
        }
        if (!rowToMove.getTargetPackageName().equals(newParentRow.getTargetPackageName())) {
            return false;
        }

        mItems.remove(rowToMove);
        final ListIterator<SettingsItem> iter = mItems.listIterator();
        while (iter.hasNext()) {
            final SettingsItem item = iter.next();
            if (item.userId == userId && item.packageName.equals(newParentPackageName)) {
                iter.add(rowToMove);
                notifyOverlayPriorityChanged(rowToMove.getOverlayInfo());
                notifySettingsChanged();
                return true;
            }
        }

        Slog.wtf(TAG, "failed to find the parent item a second time");
        return false;
    }

    boolean setLowestPriority(@NonNull final String packageName, final int userId) {
        final SettingsItem item = select(packageName, userId);
        if (item == null) {
            return false;
        }
        mItems.remove(item);
        mItems.add(0, item);
        notifyOverlayPriorityChanged(item.getOverlayInfo());
        notifySettingsChanged();
        return true;
    }

    boolean setHighestPriority(@NonNull final String packageName, final int userId) {
        final SettingsItem item = select(packageName, userId);
        if (item == null) {
            return false;
        }
        mItems.remove(item);
        mItems.add(item);
        notifyOverlayPriorityChanged(item.getOverlayInfo());
        notifySettingsChanged();
        return true;
    }

    private static final String TAB1 = "    ";
    private static final String TAB2 = TAB1 + TAB1;
    private static final String TAB3 = TAB2 + TAB1;

    void dump(@NonNull final PrintWriter pw) {
        pw.println("Settings");
        dumpItems(pw);
        dumpListeners(pw);
    }

    private void dumpItems(@NonNull final PrintWriter pw) {
        pw.println(TAB1 + "Items");

        if (mItems.isEmpty()) {
            pw.println(TAB2 + "<none>");
            return;
        }

        final int N = mItems.size();
        for (int i = 0; i < N; i++) {
            final SettingsItem item = mItems.get(i);
            final StringBuilder sb = new StringBuilder();
            sb.append(TAB2 + item.packageName + ":" + item.userId + " {\n");
            sb.append(TAB3 + "packageName.......: " + item.packageName + "\n");
            sb.append(TAB3 + "userId............: " + item.userId + "\n");
            sb.append(TAB3 + "targetPackageName.: " + item.getTargetPackageName() + "\n");
            sb.append(TAB3 + "baseCodePath......: " + item.getBaseCodePath() + "\n");
            sb.append(TAB3 + "state.............: " + OverlayInfo.stateToString(item.getState()) + "\n");
            sb.append(TAB3 + "isEnabled.........: " + item.isEnabled() + "\n");
            sb.append(TAB2 + "}");
            pw.println(sb.toString());
        }
    }

    private void dumpListeners(@NonNull final PrintWriter pw) {
        pw.println(TAB1 + "Change listeners");

        if (mListeners.isEmpty()) {
            pw.println(TAB2 + "<none>");
            return;
        }

        final int N = mListeners.size();
        for (int i = 0; i < N; i++) {
            final ChangeListener ch = mListeners.get(i);
            pw.println(TAB2 + ch);
        }

    }

    void restore(@NonNull final InputStream is) throws IOException, XmlPullParserException {
        Serializer.restore(mItems, is);
    }

    void persist(@NonNull final OutputStream os) throws IOException, XmlPullParserException {
        Serializer.persist(mItems, os);
    }

    private static final class Serializer {
        private static final String TAG_OVERLAYS = "overlays";
        private static final String TAG_ITEM = "item";

        private static final String ATTR_BASE_CODE_PATH = "baseCodePath";
        private static final String ATTR_IS_ENABLED = "isEnabled";
        private static final String ATTR_PACKAGE_NAME = "packageName";
        private static final String ATTR_STATE = "state";
        private static final String ATTR_TARGET_PACKAGE_NAME = "targetPackageName";
        private static final String ATTR_USER_ID = "userId";
        private static final String ATTR_VERSION = "version";

        private static final int CURRENT_VERSION = 1;

        public static void restore(@NonNull final ArrayList<SettingsItem> table,
                @NonNull final InputStream is) throws IOException, XmlPullParserException {

            try (InputStreamReader reader = new InputStreamReader(is)) {
                table.clear();
                final XmlPullParser parser = Xml.newPullParser();
                parser.setInput(reader);
                XmlUtils.beginDocument(parser, TAG_OVERLAYS);
                int version = XmlUtils.readIntAttribute(parser, ATTR_VERSION);
                if (version != CURRENT_VERSION) {
                    throw new XmlPullParserException("unrecognized version " + version);
                }
                int depth = parser.getDepth();

                while (XmlUtils.nextElementWithin(parser, depth)) {
                    switch (parser.getName()) {
                        case TAG_ITEM:
                            final SettingsItem item = restoreRow(parser, depth + 1);
                            table.add(item);
                            break;
                    }
                }
            }
        }

        private static SettingsItem restoreRow(@NonNull final XmlPullParser parser, final int depth)
                throws IOException {
            final String packageName = XmlUtils.readStringAttribute(parser, ATTR_PACKAGE_NAME);
            final int userId = XmlUtils.readIntAttribute(parser, ATTR_USER_ID);
            final String targetPackageName = XmlUtils.readStringAttribute(parser,
                    ATTR_TARGET_PACKAGE_NAME);
            final String baseCodePath = XmlUtils.readStringAttribute(parser, ATTR_BASE_CODE_PATH);
            final int state = XmlUtils.readIntAttribute(parser, ATTR_STATE);
            final boolean isEnabled = XmlUtils.readBooleanAttribute(parser, ATTR_IS_ENABLED);

            return new SettingsItem(packageName, userId, targetPackageName, baseCodePath, state,
                    isEnabled);
        }

        public static void persist(@NonNull final ArrayList<SettingsItem> table,
                @NonNull final OutputStream os) throws IOException, XmlPullParserException {
            final FastXmlSerializer xml = new FastXmlSerializer();
            xml.setOutput(os, "utf-8");
            xml.startDocument(null, true);
            xml.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            xml.startTag(null, TAG_OVERLAYS);
            XmlUtils.writeIntAttribute(xml, ATTR_VERSION, CURRENT_VERSION);

            final int N = table.size();
            for (int i = 0; i < N; i++) {
                final SettingsItem item = table.get(i);
                persistRow(xml, item);
            }
            xml.endTag(null, TAG_OVERLAYS);
            xml.endDocument();
        }

        private static void persistRow(@NonNull final FastXmlSerializer xml,
                @NonNull final SettingsItem item) throws IOException {
            xml.startTag(null, TAG_ITEM);
            XmlUtils.writeStringAttribute(xml, ATTR_PACKAGE_NAME, item.packageName);
            XmlUtils.writeIntAttribute(xml, ATTR_USER_ID, item.userId);
            XmlUtils.writeStringAttribute(xml, ATTR_TARGET_PACKAGE_NAME, item.targetPackageName);
            XmlUtils.writeStringAttribute(xml, ATTR_BASE_CODE_PATH, item.baseCodePath);
            XmlUtils.writeIntAttribute(xml, ATTR_STATE, item.state);
            XmlUtils.writeBooleanAttribute(xml, ATTR_IS_ENABLED, item.isEnabled);
            xml.endTag(null, TAG_ITEM);
        }
    }

    private static final class SettingsItem {
        private final int userId;
        private final String packageName;
        private final String targetPackageName;
        private String baseCodePath;
        private int state;
        private boolean isEnabled;
        private OverlayInfo cache;

        SettingsItem(@NonNull final String packageName, final int userId,
                @NonNull final String targetPackageName, @NonNull final String baseCodePath,
                final int state, final boolean isEnabled) {
            this.packageName = packageName;
            this.userId = userId;
            this.targetPackageName = targetPackageName;
            this.baseCodePath = baseCodePath;
            this.state = state;
            this.isEnabled = isEnabled;
            cache = null;
        }

        SettingsItem(@NonNull final String packageName, final int userId,
                @NonNull final String targetPackageName, @NonNull final String baseCodePath) {
            this(packageName, userId, targetPackageName, baseCodePath, STATE_UNKNOWN,
                    false);
        }

        private String getTargetPackageName() {
            return targetPackageName;
        }

        private String getBaseCodePath() {
            return baseCodePath;
        }

        private void setBaseCodePath(@NonNull final String path) {
            if (!baseCodePath.equals(path)) {
                baseCodePath = path;
                invalidateCache();
            }
        }

        private int getState() {
            return state;
        }

        private void setState(final int state) {
            if (this.state != state) {
                this.state = state;
                invalidateCache();
            }
        }

        private boolean isEnabled() {
            return isEnabled;
        }

        private void setEnabled(final boolean enable) {
            if (isEnabled != enable) {
                isEnabled = enable;
                invalidateCache();
            }
        }

        private OverlayInfo getOverlayInfo() {
            if (cache == null) {
                cache = new OverlayInfo(packageName, targetPackageName, baseCodePath,
                        state, userId);
            }
            return cache;
        }

        private void invalidateCache() {
            cache = null;
        }
    }

    private SettingsItem select(@NonNull final String packageName, final int userId) {
        final int N = mItems.size();
        for (int i = 0; i < N; i++) {
            final SettingsItem item = mItems.get(i);
            if (item.userId == userId && item.packageName.equals(packageName)) {
                return item;
            }
        }
        return null;
    }

    private List<SettingsItem> selectWhereUser(final int userId) {
        final ArrayList<SettingsItem> items = new ArrayList<>();
        final int N = mItems.size();
        for (int i = 0; i < N; i++) {
            final SettingsItem item = mItems.get(i);
            if (item.userId == userId) {
                items.add(item);
            }
        }
        return items;
    }

    private List<SettingsItem> selectWhereTarget(@NonNull final String targetPackageName,
            final int userId) {
        final ArrayList<SettingsItem> items = new ArrayList<>();
        final int N = mItems.size();
        for (int i = 0; i < N; i++) {
            final SettingsItem item = mItems.get(i);
            if (item.userId == userId && item.getTargetPackageName().equals(targetPackageName)) {
                items.add(item);
            }
        }
        return items;
    }

    private void assertNotNull(@Nullable final Object o) {
        if (o == null) {
            throw new AndroidRuntimeException("object must not be null");
        }
    }

    void addChangeListener(@NonNull final ChangeListener listener) {
        mListeners.add(listener);
    }

    void removeChangeListener(@NonNull final ChangeListener listener) {
        mListeners.remove(listener);
    }

    private void notifySettingsChanged() {
        final int N = mListeners.size();
        for (int i = 0; i < N; i++) {
            final ChangeListener listener = mListeners.get(i);
            listener.onSettingsChanged();
        }
    }

    private void notifyOverlayAdded(@NonNull final OverlayInfo oi) {
        if (DEBUG) {
            assertNotNull(oi);
        }
        final int N = mListeners.size();
        for (int i = 0; i < N; i++) {
            final ChangeListener listener = mListeners.get(i);
            listener.onOverlayAdded(oi);
        }
    }

    private void notifyOverlayRemoved(@NonNull final OverlayInfo oi) {
        if (DEBUG) {
            assertNotNull(oi);
        }
        final int N = mListeners.size();
        for (int i = 0; i < N; i++) {
            final ChangeListener listener = mListeners.get(i);
            listener.onOverlayRemoved(oi);
        }
    }

    private void notifyOverlayChanged(@NonNull final OverlayInfo oi,
            @NonNull final OverlayInfo oldOi) {
        if (DEBUG) {
            assertNotNull(oi);
            assertNotNull(oldOi);
        }
        final int N = mListeners.size();
        for (int i = 0; i < N; i++) {
            final ChangeListener listener = mListeners.get(i);
            listener.onOverlayChanged(oi, oldOi);
        }
    }

    private void notifyOverlayPriorityChanged(@NonNull final OverlayInfo oi) {
        if (DEBUG) {
            assertNotNull(oi);
        }
        final int N = mListeners.size();
        for (int i = 0; i < N; i++) {
            final ChangeListener listener = mListeners.get(i);
            listener.onOverlayPriorityChanged(oi);
        }
    }

    interface ChangeListener {
        void onSettingsChanged();
        void onOverlayAdded(@NonNull OverlayInfo oi);
        void onOverlayRemoved(@NonNull OverlayInfo oi);
        void onOverlayChanged(@NonNull OverlayInfo oi, @NonNull OverlayInfo oldOi);
        void onOverlayPriorityChanged(@NonNull OverlayInfo oi);
    }

    static final class BadKeyException extends RuntimeException {
        BadKeyException(@NonNull final String packageName, final int userId) {
            super("Bad key packageName=" + packageName + " userId=" + userId);
        }
    }
}
