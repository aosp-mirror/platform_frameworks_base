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
package android.content.pm;

import android.annotation.NonNull;
import android.annotation.TestApi;
import android.annotation.UserIdInt;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;

import com.android.internal.annotations.VisibleForTesting;

import java.util.List;

/**
 * ShortcutManager manages "launcher shortcuts" (or simply "shortcuts").  Shortcuts provide user
 * with quick
 * ways to access activities other than the main activity from the launcher to users.  For example,
 * an email application may publish the "compose new email" action which will directly open the
 * compose activity.  The {@link ShortcutInfo} class represents shortcuts.
 *
 * <h3>Dynamic Shortcuts and Manifest Shortcuts</h3>
 *
 * There are two ways to publish shortcuts: manifest shortcuts and dynamic shortcuts.
 *
 * <ul>
 * <li>Manifest shortcuts are declared in a resource XML which is referred to from
 * AndroidManifest.xml.  Manifest shortcuts are published when an application is installed,
 * and are updated when an application is upgraded with an updated XML file.
 * Manifest shortcuts are immutable and their
 * definitions (e.g. icons and labels) can not be changed dynamically (without upgrading the
 * publisher application).
 *
 * <li>Dynamic shortcuts are published at runtime with {@link ShortcutManager} APIs.
 * Applications can publish, update and remove dynamic shortcuts at runtime with certain limitations
 * described below.
 * </ul>
 *
 * <p>Only "main" activities (i.e. activities that handle the {@code MAIN} action and the
 * {@code LAUNCHER} category) can have shortcuts.  If an application has multiple main activities,
 * they will have different set of shortcuts.
 *
 * <p>Dynamic shortcuts and manifest shortcuts are shown by launcher applications when the user
 * takes a certain action (e.g. long-press) on an application launcher icon.
 *
 * <p>Each launcher icon can have at most {@link #getMaxShortcutCountPerActivity()} number of
 * dynamic and manifest shortcuts combined.
 *
 *
 * <h3>Pinning Shortcuts</h3>
 *
 * Launcher applications allow users to "pin" shortcuts so they're easier to access.  Both manifest
 * and dynamic shortcuts can be pinned, to avoid user's confusion.
 * Pinned shortcuts <b>cannot</b> be removed by publisher
 * applications -- they are only removed when the publisher is uninstalled. (Or the user performs
 * "clear data" on the publisher application on the Settings application.)
 *
 * <p>Publisher can however "disable" pinned shortcuts so they cannot be launched.  See below
 * for details.
 *
 *
 * <h3>Updating and Disabling Shortcuts</h3>
 *
 * <p>When a dynamic shortcut is pinned, even when the publisher removes it as a dynamic shortcut,
 * the pinned shortcut will still be available and launchable.  This allows an application to have
 * more than {@link #getMaxShortcutCountPerActivity()} number of shortcuts -- for example, suppose
 * {@link #getMaxShortcutCountPerActivity()} is 5:
 * <ul>
 *     <li>A chat application publishes 5 dynamic shortcuts for the 5 most recent
 *     conversations, "c1" - "c5".
 *
 *     <li>The user pins all of the 5 shortcuts.
 *
 *     <li>Later, the user has 3 newer conversations ("c6", "c7" and "c8"), so the application
 *     re-publishes dynamic shortcuts and now it has the dynamic shortcuts "c4", "c5", "c6", "c7"
 *     and "c8".  The publisher has to remove "c1", "c2" and "c3" because it can't have more than
 *     5 dynamic shortcuts.
 *
 *     <li>However, even though "c1", "c2" and "c3" are no longer dynamic shortcuts, the pinned
 *     shortcuts for those conversations are still available and launchable.
 *
 *     <li>At this point, the application has 8 shortcuts in total, including the 3 pinned
 *     shortcuts, even though it's allowed to have at most 5 dynamic shortcuts.
 *
 *     <li>The application can use {@link #updateShortcuts(List)} to update any of the existing
 *     8 shortcuts, when, for example, the chat peers' icons have changed.
 * </ul>
 * {@link #addDynamicShortcuts(List)} and {@link #setDynamicShortcuts(List)} can also be used
 * to update existing shortcuts with the same IDs, but they <b>cannot</b> be used for
 * non-dynamic pinned shortcuts because these two APIs will always try to make the passed
 * shortcuts dynamic.
 *
 *
 * <h4>Disabling Manifest Shortcuts</h4>
 * Sometimes pinned shortcuts become obsolete and may not be usable.  For example, a pinned shortcut
 * to a group chat will be unusable when the group chat room is deleted.  In cases like this,
 * applications should use {@link #disableShortcuts(List)}, which will remove the specified dynamic
 * shortcuts and also make the pinned shortcuts un-launchable, if any.
 * {@link #disableShortcuts(List, CharSequence)} can also be used to disable shortcuts with
 * a custom error message that will be shown when the user starts the shortcut.
 *
 * <h4>Disabling Manifest Shortcuts</h4>
 * When an application is upgraded and the new version no longer has a manifest shortcut that
 * the previous version had, this shortcut will no longer be published as a manifest shortcut.
 *
 * <p>If the shortcut is pinned, then the pinned shortcut will remain on the launcher, but will be
 * disabled.  Note in this case, the pinned shortcut is no longer a manifest shortcut, but is
 * still <b>immutable</b> and cannot be updated with the {@link ShortcutManager} APIs.
 *
 *
 * <h3>Publishing Dynamic Shortcuts</h3>
 *
 * Applications can publish dynamic shortcuts with {@link #setDynamicShortcuts(List)}
 * or {@link #addDynamicShortcuts(List)}.  {@link #updateShortcuts(List)} can also be used to
 * update existing (mutable) shortcuts.
 * Use {@link #removeDynamicShortcuts(List)} or {@link #removeAllDynamicShortcuts()} to remove
 * dynamic shortcuts.
 *
 * <p>Example:
 * <pre>
 * ShortcutManager shortcutManager = getSystemService(ShortcutManager.class);
 *
 * ShortcutInfo shortcut = new ShortcutInfo.Builder(this, "id1")
 *     .setIntent(new Intent(Intent.ACTION_VIEW, Uri.parse("http://www.mysite.com/")))
 *     .setShortLabel("Web site")
 *     .setLongLabel("Open the web site")
 *     .setIcon(Icon.createWithResource(context, R.drawable.icon_website))
 *     .build();
 *
 * shortcutManager.setDynamicShortcuts(Arrays.asList(shortcut));
 * </pre>
 *
 *
 * <h3>Publishing Manifest Shortcuts</h3>
 *
 * In order to add manifest shortcuts to your application, first add
 * {@code <meta-data android:name="android.app.shortcuts" />} to your main activity in
 * AndroidManifest.xml.
 * <pre>
 * &lt;manifest xmlns:android=&quot;http://schemas.android.com/apk/res/android&quot;
 *   package=&quot;com.example.myapplication&quot;&gt;
 *   &lt;application . . .&gt;
 *     &lt;activity android:name=&quot;Main&quot;&gt;
 *       &lt;intent-filter&gt;
 *         &lt;action android:name=&quot;android.intent.action.MAIN&quot; /&gt;
 *         &lt;category android:name=&quot;android.intent.category.LAUNCHER&quot; /&gt;
 *       &lt;/intent-filter&gt;
 *       <b>&lt;meta-data android:name=&quot;android.app.shortcuts&quot; android:resource=&quot;@xml/shortcuts&quot;/&gt;</b>
 *     &lt;/activity&gt;
 *   &lt;/application&gt;
 * &lt;/manifest&gt;
 * </pre>
 *
 * Then define shortcuts in res/xml/shortcuts.xml.
 * <pre>
 * &lt;shortcuts xmlns:android=&quot;http://schemas.android.com/apk/res/android&quot; &gt;
 *   &lt;shortcut
 *     android:shortcutId=&quot;compose&quot;
 *     android:enabled=&quot;true&quot;
 *     android:icon=&quot;@drawable/compose_icon&quot;
 *     android:shortcutShortLabel=&quot;@string/compose_shortcut_short_label1&quot;
 *     android:shortcutLongLabel=&quot;@string/compose_shortcut_short_label1&quot;
 *     android:shortcutDisabledMessage=&quot;@string/compose_disabled_message1&quot;
 *     &gt;
 *     &lt;intent
 *       android:action=&quot;android.intent.action.VIEW&quot;
 *       android:targetPackage=&quot;com.example.myapplication&quot;
 *       android:targetClass=&quot;com.example.myapplication.ComposeActivity&quot; /&gt;
 *     &lt;categories android:name=&quot;android.shortcut.conversation&quot; /&gt;
 *   &lt;/shortcut&gt;
 *   &lt;!-- more shortcut can go here --&gt;
 * &lt;/shortcuts&gt;
 * </pre>
 * <ul>
 *   <li>{@code android:shortcutId} Mandatory shortcut ID
 *
 *   <li>{@code android:enabled} Default is {@code true}.  Can be set to {@code false} in order
 *   to disable a manifest shortcut that was published on a previous version with a custom
 *   disabled message.  If a custom disabled message is not needed, then a manifest shortcut can
 *   be simply removed from the xml file rather than keeping it with {@code enabled="false"}.
 *
 *   <li>{@code android:icon} Shortcut icon.
 *
 *   <li>{@code android:shortcutShortLabel} Mandatory shortcut short label.
 *   See {@link ShortcutInfo.Builder#setShortLabel(CharSequence)}
 *
 *   <li>{@code android:shortcutLongLabel} Shortcut long label.
 *   See {@link ShortcutInfo.Builder#setLongLabel(CharSequence)}
 *
 *   <li>{@code android:shortcutDisabledMessage} When {@code android:enabled} is set to
 *   {@code false}, this can be used to set a custom disabled message.
 *
 *   <li>{@code intent} Intent to launch.  {@code android:action} is mandatory.
 *   See <a href="{@docRoot}guide/topics/ui/settings.html#Intents">Using intents</a> for the
 *   other supported tags.
 * </ul>
 *
 * <h3>Updating Shortcuts v.s. Re-publishing New One with Different ID</h3>
 * In order to avoid users' confusion, {@link #updateShortcuts(List)} should not be used to update
 * a shortcut to something that is conceptually different.
 *
 * <p>For example, a phone application may publish the most frequently called contact as a dynamic
 * shortcut.  Over the time, this contact may change, but when it changes the application should
 * publish a new contact with a different ID with either
 * {@link #setDynamicShortcuts(List)} or {@link #addDynamicShortcuts(List)}, rather than updating
 * the existing shortcut with {@link #updateShortcuts(List)}.
 *
 * This is because when the shortcut is pinned, changing it to a different contact
 * will likely confuse the user.
 *
 * <p>On the other hand, when the contact's information (e.g. the name or picture) has changed,
 * then the application should use {@link #updateShortcuts(List)} so that the pinned shortcut
 * will be updated too.
 *
 *
 * <h3>Shortcut Display Order</h3>
 * When the launcher show the shortcuts for a launcher icon, the showing order should be the
 * following:
 * <ul>
 *   <li>First show manifest shortcuts
 *   ({@link ShortcutInfo#isDeclaredInManifest()} is {@code true}),
 *   and then dynamic shortcuts ({@link ShortcutInfo#isDynamic()} is {@code true}).
 *   <li>Within each category, sort by {@link ShortcutInfo#getRank()}.
 * </ul>
 * <p>Shortcut ranks are non-negative sequential integers for each target activity.  Ranks of
 * existing shortcuts can be updated with
 * {@link #updateShortcuts(List)} ({@link #addDynamicShortcuts(List)} and
 * {@link #setDynamicShortcuts(List)} may be used too).
 *
 * <p>Ranks will be auto-adjusted so that they're unique for each target activity for each category
 * (dynamic or manifest).  For example, if there are 3 dynamic shortcuts with ranks 0, 1 and 2,
 * adding another dynamic shortcut with rank = 1 means to place this shortcut at the second
 * position.  The third and forth shortcuts (that were originally second and third) will be adjusted
 * to 2 and 3 respectively.
 *
 * <h3>Rate Limiting</h3>
 *
 * Calls to {@link #setDynamicShortcuts(List)}, {@link #addDynamicShortcuts(List)} and
 * {@link #updateShortcuts(List)} may be rate-limited when called by background applications (i.e.
 * applications with no foreground activity or service).  When rate-limited, these APIs will return
 * {@code false}.
 *
 * <p>Applications with a foreground activity or service will not be rate-limited.
 *
 * <p>Rate-limiting will be reset upon certain events, so that even background applications
 * will be able to call these APIs again (until they are rate-limited again).
 * <ul>
 *   <li>When an application comes to foreground.
 *   <li>When the system locale changes.
 *   <li>When the user performs "inline reply" on a notification.
 * </ul>
 *
 * <h4>Resetting rate-limiting for testing</h4>
 *
 * If your application is rate-limited during development or testing, you can use the
 * "Reset ShortcutManager rate-limiting" development option, or the following adb command to reset
 * it.
 * <pre>
 * adb shell cmd shortcut reset-throttling [ --user USER-ID ]
 * </pre>
 *
 * <h3>Handling System Locale Change</h3>
 *
 * Applications should update dynamic and pinned shortcuts when the system locale changes
 * using the {@link Intent#ACTION_LOCALE_CHANGED} broadcast.
 *
 * <p>When the system locale changes, rate-limiting will be reset, so even background applications
 * what were previously rate-limited will be able to call {@link #updateShortcuts(List)}.
 *
 *
 * <h3>Backup and Restore</h3>
 *
 * When an application has {@code android:allowBackup="true"} in its AndroidManifest.xml, pinned
 * shortcuts will be backed up automatically and restored when the user sets up a new device.
 *
 * <h4>What will be backed up and what will not be backed up</h4>
 *
 * <ul>
 *  <li>Pinned shortcuts will be backed up.  Bitmap icons will not be backed up by the system,
 *  but launcher applications should back them up and restore them, so the user will still get
 *  icons for pinned shortcuts on the launcher.  Applications can always use
 *  {@link #updateShortcuts(List)} to re-publish icons.
 *
 *  <li>Manifest shortcuts will not be backed up, but when an application is re-installed on a new
 *  device, they will be re-published from AndroidManifest.xml anyway.
 *
 *  <li>Dynamic shortcuts will <b>not</b> be backed up.
 * </ul>
 *
 * <p>Because dynamic shortcuts will not restored, it is recommended that applications check
 * currently published dynamic shortcuts with {@link #getDynamicShortcuts()} when they start,
 * and re-publish dynamic shortcuts when necessary.
 *
 * <pre>
 * public class MainActivity extends Activity {
 *     public void onCreate(Bundle savedInstanceState) {
 *         super.onCreate(savedInstanceState);
 *
 *         ShortcutManager shortcutManager = getSystemService(ShortcutManager.class);
 *
 *         if (shortcutManager.getDynamicShortcuts().size() == 0) {
 *             // Application restored; re-publish dynamic shortcuts.
 *
 *             if (shortcutManager.getPinnedShortcuts().size() > 0) {
 *                 // Pinned shortcuts have been restored.  use updateShortcuts() to make sure
 *                 // they have up-to-date information.
 *             }
 *         }
 *     }
 *     :
 *
 * }
 * </pre>
 *
 *
 * <h4>Backup/restore and shortcut IDs</h4>
 *
 * Because pinned shortcuts will be backed up and restored on new devices, shortcut IDs should be
 * meaningful across devices; that is, IDs should be either stable constant strings, or server-side
 * identifiers, rather than identifiers generated locally that may not make sense on other devices.
 *
 *
 * <h3>Report Shortcut Usage and Prediction</h3>
 *
 * Launcher applications may be capable of predicting which shortcuts will most likely be used at
 * the moment with the shortcut usage history data.
 *
 * <p>In order to provide launchers with such data, publisher applications should report which
 * shortcut is used with {@link #reportShortcutUsed(String)} when a shortcut is started,
 * <b>or when an action equivalent to a shortcut is taken by the user even if it wasn't started
 * with the shortcut</b>.
 *
 * <p>For example, suppose a GPS navigation application exposes "navigate to work" as a shortcut.
 * Then it should report it when the user starts this shortcut, and also when the user navigates
 * to work within the application without using the shortcut.  This helps the launcher application
 * learn that the user wants to navigate to work at a certain time every weekday, so that the
 * launcher can show this shortcut in a suggestion list.
 *
 * <h3>Launcher API</h3>
 *
 * {@link LauncherApps} provides APIs for launcher applications to access shortcuts.
 */
public class ShortcutManager {
    private static final String TAG = "ShortcutManager";

    private final Context mContext;
    private final IShortcutService mService;

    /**
     * @hide
     */
    public ShortcutManager(Context context, IShortcutService service) {
        mContext = context;
        mService = service;
    }

    /**
     * @hide
     */
    @TestApi
    public ShortcutManager(Context context) {
        this(context, IShortcutService.Stub.asInterface(
                ServiceManager.getService(Context.SHORTCUT_SERVICE)));
    }

    /**
     * Publish a list of shortcuts.  All existing dynamic shortcuts from the caller application
     * will be replaced.  If there's already pinned shortcuts with the same IDs, they will all be
     * updated, unless they're immutable.
     *
     * <p>This API will be rate-limited.
     *
     * @return {@code true} if the call has succeeded. {@code false} if the call is rate-limited.
     *
     * @throws IllegalArgumentException if {@link #getMaxShortcutCountPerActivity()} is exceeded,
     * or trying to update immutable shortcuts.
     */
    public boolean setDynamicShortcuts(@NonNull List<ShortcutInfo> shortcutInfoList) {
        try {
            return mService.setDynamicShortcuts(mContext.getPackageName(),
                    new ParceledListSlice(shortcutInfoList), injectMyUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return all dynamic shortcuts from the caller application.
     */
    @NonNull
    public List<ShortcutInfo> getDynamicShortcuts() {
        try {
            return mService.getDynamicShortcuts(mContext.getPackageName(), injectMyUserId())
                    .getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return all manifest shortcuts from the caller application.
     */
    @NonNull
    public List<ShortcutInfo> getManifestShortcuts() {
        try {
            return mService.getManifestShortcuts(mContext.getPackageName(), injectMyUserId())
                    .getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Publish list of dynamic shortcuts.  If there's already dynamic or pinned shortcuts with
     * the same IDs, they will all be updated, unless they're immutable.
     *
     * <p>This API will be rate-limited.
     *
     * @return {@code true} if the call has succeeded. {@code false} if the call is rate-limited.
     *
     * @throws IllegalArgumentException if {@link #getMaxShortcutCountPerActivity()} is exceeded,
     * or trying to update immutable shortcuts.
     */
    public boolean addDynamicShortcuts(@NonNull List<ShortcutInfo> shortcutInfoList) {
        try {
            return mService.addDynamicShortcuts(mContext.getPackageName(),
                    new ParceledListSlice(shortcutInfoList), injectMyUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Delete dynamic shortcuts by ID.
     */
    public void removeDynamicShortcuts(@NonNull List<String> shortcutIds) {
        try {
            mService.removeDynamicShortcuts(mContext.getPackageName(), shortcutIds,
                    injectMyUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Delete all dynamic shortcuts from the caller application.
     */
    public void removeAllDynamicShortcuts() {
        try {
            mService.removeAllDynamicShortcuts(mContext.getPackageName(), injectMyUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return all pinned shortcuts from the caller application.
     */
    @NonNull
    public List<ShortcutInfo> getPinnedShortcuts() {
        try {
            return mService.getPinnedShortcuts(mContext.getPackageName(), injectMyUserId())
                    .getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Update all existing shortcuts with the same IDs.  Target shortcuts may be pinned and/or
     * dynamic, but may not be immutable.
     *
     * <p>This API will be rate-limited.
     *
     * @return {@code true} if the call has succeeded. {@code false} if the call is rate-limited.
     *
     * @throws IllegalArgumentException if trying to update immutable shortcuts.
     */
    public boolean updateShortcuts(List<ShortcutInfo> shortcutInfoList) {
        try {
            return mService.updateShortcuts(mContext.getPackageName(),
                    new ParceledListSlice(shortcutInfoList), injectMyUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Disable pinned shortcuts.  See {@link ShortcutManager}'s class javadoc for details.
     */
    public void disableShortcuts(@NonNull List<String> shortcutIds) {
        try {
            mService.disableShortcuts(mContext.getPackageName(), shortcutIds,
                    /* disabledMessage =*/ null, /* disabledMessageResId =*/ 0,
                    injectMyUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide old signature, kept for unit testing.
     */
    public void disableShortcuts(@NonNull List<String> shortcutIds, int disabledMessageResId) {
        try {
            mService.disableShortcuts(mContext.getPackageName(), shortcutIds,
                    /* disabledMessage =*/ null, disabledMessageResId,
                    injectMyUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide old signature, kept for unit testing.
     */
    public void disableShortcuts(@NonNull List<String> shortcutIds, String disabledMessage) {
        disableShortcuts(shortcutIds, (CharSequence) disabledMessage);
    }

    /**
     * Disable pinned shortcuts with a custom error message.
     * See {@link ShortcutManager}'s class javadoc for details.
     */
    public void disableShortcuts(@NonNull List<String> shortcutIds, CharSequence disabledMessage) {
        try {
            mService.disableShortcuts(mContext.getPackageName(), shortcutIds,
                    disabledMessage, /* disabledMessageResId =*/ 0,
                    injectMyUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Re-enable disabled pinned shortcuts.
     */
    public void enableShortcuts(@NonNull List<String> shortcutIds) {
        try {
            mService.enableShortcuts(mContext.getPackageName(), shortcutIds, injectMyUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }


    /**
     * @hide old signature, kept for unit testing.
     */
    public int getMaxShortcutCountForActivity() {
        return getMaxShortcutCountPerActivity();
    }

    /**
     * Return the max number of dynamic and manifest shortcuts that each launcher icon
     * can have at a time.
     */
    public int getMaxShortcutCountPerActivity() {
        try {
            return mService.getMaxShortcutCountPerActivity(
                    mContext.getPackageName(), injectMyUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return the number of times the caller application can call the rate-limited APIs
     * before the rate limit counter is reset.
     *
     * @see #getRateLimitResetTime()
     *
     * @hide
     */
    public int getRemainingCallCount() {
        try {
            return mService.getRemainingCallCount(mContext.getPackageName(), injectMyUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return when the rate limit count will be reset next time, in milliseconds since the epoch.
     *
     * @see #getRemainingCallCount()
     * @see System#currentTimeMillis()
     *
     * @hide
     */
    public long getRateLimitResetTime() {
        try {
            return mService.getRateLimitResetTime(mContext.getPackageName(), injectMyUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return {@code true} when rate-limiting is active for the caller application.
     *
     * <p>See the class level javadoc for details.
     */
    public boolean isRateLimitingActive() {
        try {
            return mService.getRemainingCallCount(mContext.getPackageName(), injectMyUserId())
                    == 0;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return the max width for icons, in pixels.
     */
    public int getIconMaxWidth() {
        try {
            // TODO Implement it properly using xdpi.
            return mService.getIconMaxDimensions(mContext.getPackageName(), injectMyUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return the max height for icons, in pixels.
     */
    public int getIconMaxHeight() {
        try {
            // TODO Implement it properly using ydpi.
            return mService.getIconMaxDimensions(mContext.getPackageName(), injectMyUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Applications that publish shortcuts should call this method whenever a shortcut is started
     * or an action equivalent to a shortcut is taken.  See the {@link ShortcutManager} class
     * javadoc for details.
     *
     * <p>The information is accessible via {@link UsageStatsManager#queryEvents}
     * Typically, launcher applications use this information to build a prediction model
     * so that they can promote the shortcuts that are likely to be used at the moment.
     */
    public void reportShortcutUsed(String shortcutId) {
        try {
            mService.reportShortcutUsed(mContext.getPackageName(), shortcutId,
                    injectMyUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Called internally when an application is considered to have come to foreground
     * even when technically it's not.  This method resets the throttling for this package.
     * For example, when the user sends an "inline reply" on an notification, the system UI will
     * call it.
     *
     * @hide
     */
    public void onApplicationActive(@NonNull String packageName, @UserIdInt int userId) {
        try {
            mService.onApplicationActive(packageName, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide injection point */
    @VisibleForTesting
    protected int injectMyUserId() {
        return UserHandle.myUserId();
    }
}
