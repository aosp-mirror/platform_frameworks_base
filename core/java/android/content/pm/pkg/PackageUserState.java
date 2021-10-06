/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.content.pm.pkg;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.content.pm.PackageManager;
import android.content.pm.SuspendDialogInfo;
import android.content.pm.overlay.OverlayPaths;
import android.os.BaseBundle;
import android.os.PersistableBundle;
import android.util.Slog;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The API surface for a {@link PackageUserStateImpl}. Methods are expected to return
 * immutable objects. This may mean copying data on each invocation until related classes are
 * refactored to be immutable.
 * <p>
 * TODO: Replace implementation usage with the interface. Currently the name overlap is intentional.
 * <p>
 *
 * @hide
 */
// TODO(b/173807334): Expose API
//@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
public interface PackageUserState {

    PackageUserState DEFAULT = new PackageUserStateDefault();

    /**
     * {@link #getOverlayPaths()} but also include shared library overlay paths.
     */
    @Nullable
    OverlayPaths getAllOverlayPaths();

    /**
     * Credential encrypted /data partition inode.
     */
    long getCeDataInode();

    @NonNull
    Set<String> getDisabledComponents();

    @PackageManager.DistractionRestriction
    int getDistractionFlags();

    @NonNull
    Set<String> getEnabledComponents();

    int getEnabledState();

    @Nullable
    String getHarmfulAppWarning();

    @PackageManager.InstallReason
    int getInstallReason();

    @Nullable
    String getLastDisableAppCaller();

    @Nullable
    OverlayPaths getOverlayPaths();

    @NonNull
    Map<String, OverlayPaths> getSharedLibraryOverlayPaths();

    @PackageManager.UninstallReason
    int getUninstallReason();

    boolean isComponentEnabled(@NonNull String componentName);

    boolean isComponentDisabled(@NonNull String componentName);

    boolean isHidden();

    boolean isInstalled();

    boolean isInstantApp();

    boolean isNotLaunched();

    boolean isStopped();

    boolean isSuspended();

    boolean isVirtualPreload();

    @Nullable
    String getSplashScreenTheme();

    /**
     * Container to describe suspension parameters.
     */
    final class SuspendParams {

        private static final String LOG_TAG = "PackageUserState";
        private static final String TAG_DIALOG_INFO = "dialog-info";
        private static final String TAG_APP_EXTRAS = "app-extras";
        private static final String TAG_LAUNCHER_EXTRAS = "launcher-extras";

        public SuspendDialogInfo dialogInfo;
        public PersistableBundle appExtras;
        public PersistableBundle launcherExtras;

        private SuspendParams() {
        }

        /**
         * Returns a {@link SuspendParams} object with the given fields. Returns {@code null} if all
         * the fields are {@code null}.
         *
         * @param dialogInfo
         * @param appExtras
         * @param launcherExtras
         * @return A {@link SuspendParams} object or {@code null}.
         */
        public static SuspendParams getInstanceOrNull(SuspendDialogInfo dialogInfo,
                PersistableBundle appExtras, PersistableBundle launcherExtras) {
            if (dialogInfo == null && appExtras == null && launcherExtras == null) {
                return null;
            }
            final SuspendParams instance = new SuspendParams();
            instance.dialogInfo = dialogInfo;
            instance.appExtras = appExtras;
            instance.launcherExtras = launcherExtras;
            return instance;
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof SuspendParams)) {
                return false;
            }
            final SuspendParams other = (SuspendParams) obj;
            if (!Objects.equals(dialogInfo, other.dialogInfo)) {
                return false;
            }
            if (!BaseBundle.kindofEquals(appExtras, other.appExtras)) {
                return false;
            }
            if (!BaseBundle.kindofEquals(launcherExtras, other.launcherExtras)) {
                return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            int hashCode = Objects.hashCode(dialogInfo);
            hashCode = 31 * hashCode + ((appExtras != null) ? appExtras.size() : 0);
            hashCode = 31 * hashCode + ((launcherExtras != null) ? launcherExtras.size() : 0);
            return hashCode;
        }

        /**
         * Serializes this object into an xml format
         * @param out the {@link XmlSerializer} object
         * @throws IOException
         */
        public void saveToXml(TypedXmlSerializer out) throws IOException {
            if (dialogInfo != null) {
                out.startTag(null, TAG_DIALOG_INFO);
                dialogInfo.saveToXml(out);
                out.endTag(null, TAG_DIALOG_INFO);
            }
            if (appExtras != null) {
                out.startTag(null, TAG_APP_EXTRAS);
                try {
                    appExtras.saveToXml(out);
                } catch (XmlPullParserException e) {
                    Slog.e(LOG_TAG, "Exception while trying to write appExtras."
                            + " Will be lost on reboot", e);
                }
                out.endTag(null, TAG_APP_EXTRAS);
            }
            if (launcherExtras != null) {
                out.startTag(null, TAG_LAUNCHER_EXTRAS);
                try {
                    launcherExtras.saveToXml(out);
                } catch (XmlPullParserException e) {
                    Slog.e(LOG_TAG, "Exception while trying to write launcherExtras."
                            + " Will be lost on reboot", e);
                }
                out.endTag(null, TAG_LAUNCHER_EXTRAS);
            }
        }

        /**
         * Parses this object from the xml format. Returns {@code null} if no object related
         * information could be read.
         * @param in the reader
         * @return
         */
        public static SuspendParams restoreFromXml(TypedXmlPullParser in) throws IOException {
            SuspendDialogInfo readDialogInfo = null;
            PersistableBundle readAppExtras = null;
            PersistableBundle readLauncherExtras = null;

            final int currentDepth = in.getDepth();
            int type;
            try {
                while ((type = in.next()) != XmlPullParser.END_DOCUMENT
                        && (type != XmlPullParser.END_TAG
                        || in.getDepth() > currentDepth)) {
                    if (type == XmlPullParser.END_TAG
                            || type == XmlPullParser.TEXT) {
                        continue;
                    }
                    switch (in.getName()) {
                        case TAG_DIALOG_INFO:
                            readDialogInfo = SuspendDialogInfo.restoreFromXml(in);
                            break;
                        case TAG_APP_EXTRAS:
                            readAppExtras = PersistableBundle.restoreFromXml(in);
                            break;
                        case TAG_LAUNCHER_EXTRAS:
                            readLauncherExtras = PersistableBundle.restoreFromXml(in);
                            break;
                        default:
                            Slog.w(LOG_TAG, "Unknown tag " + in.getName()
                                    + " in SuspendParams. Ignoring");
                            break;
                    }
                }
            } catch (XmlPullParserException e) {
                Slog.e(LOG_TAG, "Exception while trying to parse SuspendParams,"
                        + " some fields may default", e);
            }
            return getInstanceOrNull(readDialogInfo, readAppExtras, readLauncherExtras);
        }
    }
}
