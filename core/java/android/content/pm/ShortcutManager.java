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
import android.app.Activity;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;

import com.android.internal.annotations.VisibleForTesting;

import java.util.List;

/**
 * The ShortcutManager manages an app's <em>shortcuts</em>. Shortcuts provide users
 * with quick access to activities other than an app's main activity in the currently-active
 * launcher.  For example,
 * an email app may publish the "compose new email" action, which will directly open the
 * compose activity.  The {@link ShortcutInfo} class contains information about each of the
 * shortcuts themselves.
 *
 * <h3>Static Shortcuts and Dynamic Shortcuts</h3>
 *
 * <p>
 * There are two ways to publish shortcuts: static shortcuts and dynamic shortcuts.
 *
 * <ul>
 * <li>Static shortcuts are declared in a resource
 * XML file, which is referenced in the publisher app's <code>AndroidManifest.xml</code> file.
 * Static shortcuts are published when an app is installed,
 * and the details of these shortcuts change when an app is upgraded with an updated XML
 * file.
 * Static shortcuts are immutable, and their
 * definitions, such as icons and labels, cannot be changed dynamically without upgrading the
 * publisher app.
 *
 * <li>Dynamic shortcuts are published at runtime using this class's APIs.
 * Apps can publish, update, and remove dynamic shortcuts at runtime.
 * </ul>
 *
 * <p>Only main activities&mdash;activities that handle the {@code MAIN} action and the
 * {@code LAUNCHER} category&mdash;can have shortcuts.
 * If an app has multiple main activities, these activities have different sets
 * of shortcuts.
 *
 * <p>Static shortcuts and dynamic shortcuts are shown in the currently active launcher when
 * the user long-presses on an app's launcher icon.
 *
 * <p class="note"><strong>Note: </strong>The actual gesture may be different
 * depending on the launcher app.
 *
 * <p>Each launcher icon can have at most {@link #getMaxShortcutCountPerActivity()} number of
 * static and dynamic shortcuts combined.
 *
 *
 * <h3>Pinning Shortcuts</h3>
 *
 * <p>
 * Launcher apps allow users to <em>pin</em> shortcuts so they're easier to access.  Both static
 * and dynamic shortcuts can be pinned.
 * Pinned shortcuts <b>cannot</b> be removed by publisher
 * apps; they're removed only when the user removes them,
 * when the publisher app is uninstalled, or when the
 * user performs the <strong>clear data</strong> action on the publisher app from the device's Settings
 * app.
 *
 * <p>However, the publisher app can <em>disable</em> pinned shortcuts so they cannot be
 * started.  See the following sections for details.
 *
 *
 * <h3>Updating and Disabling Shortcuts</h3>
 *
 * <p>When a dynamic shortcut is pinned, even when the publisher removes it as a dynamic shortcut,
 * the pinned shortcut will still be visible and launchable.  This allows an app to have
 * more than {@link #getMaxShortcutCountPerActivity()} number of shortcuts.
 *
 * <p>For example, suppose {@link #getMaxShortcutCountPerActivity()} is 5:
 * <ol>
 *     <li>A chat app publishes 5 dynamic shortcuts for the 5 most recent
 *     conversations (c1, c2, ..., c5).
 *
 *     <li>The user pins all 5 of the shortcuts.
 *
 *     <li>Later, the user has started 3 additional conversations (c6, c7, and c8),
 *     so the publisher app
 *     re-publishes its dynamic shortcuts.  The new dynamic shortcut list is:
 *     c4, c5, ..., c8.
 *     The publisher app has to remove c1, c2, and c3 because it can't have more than
 *     5 dynamic shortcuts.
 *
 *     <li>However, even though c1, c2, and c3 are no longer dynamic shortcuts, the pinned
 *     shortcuts for these conversations are still available and launchable.
 *
 *     <li>At this point, the user can access a total of 8 shortcuts that link to activities in
 *     the publisher app, including the 3 pinned
 *     shortcuts, even though an app can have at most 5 dynamic shortcuts.
 *
 *     <li>The app can use {@link #updateShortcuts(List)} to update <em>any</em> of the existing
 *     8 shortcuts, when, for example, the chat peers' icons have changed.
 * </ul>
 * The {@link #addDynamicShortcuts(List)} and {@link #setDynamicShortcuts(List)} methods
 * can also be used
 * to update existing shortcuts with the same IDs, but they <b>cannot</b> be used
 * for updating non-dynamic, pinned shortcuts because these two methods try to convert the given
 * lists of shortcuts to dynamic shortcuts.
 *
 *
 * <h4>Disabling Static Shortcuts</h4>
 * When an app is upgraded and the new version
 * no longer uses a static shortcut that appeared in the previous version, this deprecated
 * shortcut will no longer be published as a static shortcut.
 *
 * <p>If the deprecated shortcut is pinned, then the pinned shortcut will remain on the launcher,
 * but it will be disabled automatically.
 * Note that, in this case, the pinned shortcut is no longer a static shortcut, but it's
 * still <b>immutable</b>. Therefore, it cannot be updated using this class's APIs.
 *
 *
 * <h4>Disabling Dynamic Shortcuts</h4>
 * Sometimes pinned shortcuts become obsolete and may not be usable.  For example, a pinned shortcut
 * to a group chat becomes unusable when the associated group chat is deleted.  In cases like this,
 * apps should use {@link #disableShortcuts(List)}, which removes the specified dynamic
 * shortcuts and also makes any specified pinned shortcuts un-launchable.
 * The {@link #disableShortcuts(List, CharSequence)} method can also be used to disabled shortcuts
 * and show users a custom error message when they attempt to launch the disabled shortcuts.
 *
 *
 * <h3>Publishing Static Shortcuts</h3>
 *
 * <p>
 * In order to add static shortcuts to your app, first add
 * {@code <meta-data android:name="android.app.shortcuts" />} to your main activity in
 * AndroidManifest.xml:
 * <pre>
 *&lt;manifest xmlns:android="http://schemas.android.com/apk/res/android"
 *             package="com.example.myapplication"&gt;
 *  &lt;application ... &gt;
 *    &lt;activity android:name="Main"&gt;
 *      &lt;intent-filter&gt;
 *        &lt;action android:name="android.intent.action.MAIN" /&gt;
 *        &lt;category android:name="android.intent.category.LAUNCHER" /&gt;
 *      &lt;/intent-filter&gt;
 *      <strong>&lt;meta-data android:name="android.app.shortcuts"
 *                 android:resource="@xml/shortcuts" /&gt;</strong>
 *    &lt;/activity&gt;
 *  &lt;/application&gt;
 *&lt;/manifest&gt;
 * </pre>
 *
 * Then, define your app's static shortcuts in the <code>res/xml/shortcuts.xml</code>
 * file:
 * <pre>
 *&lt;shortcuts xmlns:android="http://schemas.android.com/apk/res/android"&gt;
 *  &lt;shortcut
 *    android:shortcutId="compose"
 *    android:enabled="true"
 *    android:icon="@drawable/compose_icon"
 *    android:shortcutShortLabel="@string/compose_shortcut_short_label1"
 *    android:shortcutLongLabel="@string/compose_shortcut_long_label1"
 *    android:shortcutDisabledMessage="@string/compose_disabled_message1"&gt;
 *    &lt;intent
 *      android:action="android.intent.action.VIEW"
 *      android:targetPackage="com.example.myapplication"
 *      android:targetClass="com.example.myapplication.ComposeActivity" /&gt;
 *    &lt;!-- If your shortcut is associated with multiple intents, include them
 *         here. The last intent in the list is what the user sees when they
 *         launch this shortcut. --&gt;
 *    &lt;categories android:name="android.shortcut.conversation" /&gt;
 *  &lt;/shortcut&gt;
 *  &lt;!-- Specify more shortcuts here. --&gt;
 *&lt;/shortcuts&gt;
 * </pre>
 *
 * The following list includes descriptions for the different attributes within a static shortcut:
 * <dl>
 *   <dt>{@code android:shortcutId}</dt>
 *   <dd>Mandatory shortcut ID.
 *   <p>
 *   This must be a string literal.
 *   A resource string, such as <code>@string/foo</code>, cannot be used.
 *   </dd>
 *
 *   <dt>{@code android:enabled}</dt>
 *   <dd>Default is {@code true}.  Can be set to {@code false} in order
 *   to disable a static shortcut that was published in a previous version and set a custom
 *   disabled message.  If a custom disabled message is not needed, then a static shortcut can
 *   be simply removed from the XML file rather than keeping it with {@code enabled="false"}.</dd>
 *
 *   <dt>{@code android:icon}</dt>
 *   <dd>Shortcut icon.</dd>
 *
 *   <dt>{@code android:shortcutShortLabel}</dt>
 *   <dd>Mandatory shortcut short label.
 *   See {@link ShortcutInfo.Builder#setShortLabel(CharSequence)}.
 *   <p>
 *   This must be a resource string, such as <code>@string/shortcut_label</code>.
 *   </dd>
 *
 *   <dt>{@code android:shortcutLongLabel}</dt>
 *   <dd>Shortcut long label.
 *   See {@link ShortcutInfo.Builder#setLongLabel(CharSequence)}.
 *   <p>
 *   This must be a resource string, such as <code>@string/shortcut_long_label</code>.
 *   </dd>
 *
 *   <dt>{@code android:shortcutDisabledMessage}</dt>
 *   <dd>When {@code android:enabled} is set to
 *   {@code false}, this attribute is used to display a custom disabled message.
 *   <p>
 *   This must be a resource string, such as <code>@string/shortcut_disabled_message</code>.
 *   </dd>
 *
 *   <dt>{@code intent}</dt>
 *   <dd>Intent to launch when the user selects the shortcut.
 *   {@code android:action} is mandatory.
 *   See <a href="{@docRoot}guide/topics/ui/settings.html#Intents">Using intents</a> for the
 *   other supported tags.
 *   You can provide multiple intents for a single shortcut so that the last defined activity is launched
 *   with the other activities in the <a href="/guide/components/tasks-and-back-stack.html">back stack</a>.
 *   See {@link android.app.TaskStackBuilder} for details.
 *   </dd>
 *   <dt>{@code categories}</dt>
 *   <dd>Specify shortcut categories.  Currently only
 *   {@link ShortcutInfo#SHORTCUT_CATEGORY_CONVERSATION} is defined in the framework.
 *   </dd>
 * </dl>
 *
 * <h3>Publishing Dynamic Shortcuts</h3>
 *
 * <p>
 * Apps can publish dynamic shortcuts with {@link #setDynamicShortcuts(List)}
 * or {@link #addDynamicShortcuts(List)}.  The {@link #updateShortcuts(List)} method can also be
 * used to update existing, mutable shortcuts.
 * Use {@link #removeDynamicShortcuts(List)} or {@link #removeAllDynamicShortcuts()} to remove
 * dynamic shortcuts.
 *
 * <p>The following code snippet shows how to create a single dynamic shortcut:
 * <pre>
 *ShortcutManager shortcutManager = getSystemService(ShortcutManager.class);
 *
 *ShortcutInfo shortcut = new ShortcutInfo.Builder(this, "id1")
 *    .setShortLabel("Web site")
 *    .setLongLabel("Open the web site")
 *    .setIcon(Icon.createWithResource(context, R.drawable.icon_website))
 *    .setIntent(new Intent(Intent.ACTION_VIEW,
 *                   Uri.parse("https://www.mysite.example.com/")))
 *    .build();
 *
 *shortcutManager.setDynamicShortcuts(Arrays.asList(shortcut));
 * </pre>
 *
 *
 * <h3>Shortcut Intents</h3>
 * <p>
 * Dynamic shortcuts can be published with any set of {@link Intent#addFlags Intent} flags.
 * Typically, {@link Intent#FLAG_ACTIVITY_CLEAR_TASK} is specified, possibly along with other
 * flags; otherwise, if the app is already running, the app is simply brought to
 * the foreground, and the target activity may not appear.
 *
 * <p>The {@link ShortcutInfo.Builder#setIntents(Intent[])} method can be used instead of
 * {@link ShortcutInfo.Builder#setIntent(Intent)} with {@link android.app.TaskStackBuilder}
 * in order to launch an activity with other activities in the back stack.
 * When the user selects a shortcut to load an activity with a back stack,
 * then presses the back key, a parent activity from the same app will be shown
 * instead of the user being navigated back to the launcher.
 *
 * <p>Static shortcuts can also have multiple intents to achieve the same effect.
 * In order to associate multiple {@link Intent} objects with a shortcut, simply list multiple
 * <code>&lt;intent&gt;</code> elements within a single <code>&lt;shortcut&gt;</code> element.
 * The last intent specifies what the user sees when they launch a shortcut.
 *
 * <p>Static shortcuts <b>cannot</b> have custom intent flags.
 * The first intent of a static shortcut will always have {@link Intent#FLAG_ACTIVITY_NEW_TASK}
 * and {@link Intent#FLAG_ACTIVITY_CLEAR_TASK} set.
 * This means, when the app is already running, all the existing activities will be
 * destroyed when a static shortcut is launched.
 * If this behavior is not desirable, you can use a <em>trampoline activity</em>,
 * or an invisible activity that starts another activity in {@link Activity#onCreate},
 * then calls {@link Activity#finish()}.
 * The first activity should include an attribute setting
 * of {@code android:taskAffinity=""} in the app's <code>AndroidManifest.xml</code>
 * file, and the intent within the static shortcut should point at this first activity.
 *
 *
 * <h3>Showing New Information in a Shortcut</h3>
 * <p>
 * In order to avoid confusion, you should not use {@link #updateShortcuts(List)} to update
 * a shortcut so that it contains conceptually different information.
 *
 * <p>For example, a phone app may publish the most frequently called contact as a dynamic
 * shortcut.  Over time, this contact may change. When it does, the app should
 * represent the changed contact with a new shortcut that contains a different ID, using either
 * {@link #setDynamicShortcuts(List)} or {@link #addDynamicShortcuts(List)}, rather than updating
 * the existing shortcut with {@link #updateShortcuts(List)}.
 * This is because when the shortcut is pinned, changing
 * it to reference a different contact will likely confuse the user.
 *
 * <p>On the other hand, when the
 * contact's information has changed, such as the name or picture, the app should
 * use {@link #updateShortcuts(List)} so that the pinned shortcut is updated too.
 *
 *
 * <h3>Shortcut Display Order</h3>
 * When the launcher displays the shortcuts that are associated with a particular launcher icon,
 * the shortcuts should appear in the following order:
 * <ul>
 *   <li>First show static shortcuts
 *   (if {@link ShortcutInfo#isDeclaredInManifest()} is {@code true}),
 *   and then show dynamic shortcuts (if {@link ShortcutInfo#isDynamic()} is {@code true}).
 *   <li>Within each category of shortcuts (static and dynamic), sort the shortcuts in order
 *   of increasing rank according to {@link ShortcutInfo#getRank()}.
 * </ul>
 * <p>Shortcut ranks are non-negative, sequential integers
 * that determine the order in which shortcuts appear, assuming that the shortcuts are all in
 * the same category.
 * Ranks of existing shortcuts can be updated with
 * {@link #updateShortcuts(List)}. You can also use {@link #addDynamicShortcuts(List)} and
 * {@link #setDynamicShortcuts(List)}.
 *
 * <p>Ranks are auto-adjusted so that they're unique for each target activity in each category
 * (static or dynamic).  For example, if there are 3 dynamic shortcuts with ranks 0, 1 and 2,
 * adding another dynamic shortcut with a rank of 1 represents a request to place this shortcut at
 * the second position.
 * In response, the third and fourth shortcuts move closer to the bottom of the shortcut list,
 * with their ranks changing to 2 and 3, respectively.
 *
 * <h3>Rate Limiting</h3>
 *
 * <p>
 * Calls to {@link #setDynamicShortcuts(List)}, {@link #addDynamicShortcuts(List)}, and
 * {@link #updateShortcuts(List)} may be rate-limited when called by <em>background apps</em>, or
 * apps with no foreground activity or service.  When you attempt to call these methods
 * from a background app after exceeding the rate limit, these APIs return {@code false}.
 *
 * <p>Apps with a foreground activity or service are not rate-limited.
 *
 * <p>Rate-limiting is reset upon certain events, so that even background apps
 * can call these APIs until the rate limit is reached again.
 * These events include the following:
 * <ul>
 *   <li>An app comes to the foreground.
 *   <li>The system locale changes.
 *   <li>The user performs the <strong>inline reply</strong> action on a notification.
 * </ul>
 *
 * <p>When rate-limiting is active, {@link #isRateLimitingActive()} returns {@code true}.
 *
 * <h4>Resetting rate-limiting for testing</h4>
 *
 * <p>
 * If your app is rate-limited during development or testing, you can use the
 * <strong>Reset ShortcutManager rate-limiting</strong> development option or
 * the following {@code adb} command to reset it:
 * <pre class="no-pretty-print">
 *$ adb shell cmd shortcut reset-throttling [ --user USER-ID ]
 * </pre>
 *
 * <h3>Handling System Locale Changes</h3>
 *
 * <p>
 * Apps should update dynamic and pinned shortcuts when the system locale changes
 * using the {@link Intent#ACTION_LOCALE_CHANGED} broadcast.
 *
 * <p>When the system locale changes, rate-limiting is reset, so even background apps
 * can add and update dynamic shortcuts until the rate limit is reached again.
 *
 *
 * <h3>Backup and Restore</h3>
 *
 * <p>
 * When an app has the {@code android:allowBackup="true"} attribute assignment included
 * in its <code>AndroidManifest.xml</code> file, pinned shortcuts are
 * backed up automatically and are restored when the user sets up a new device.
 *
 * <h4>Categories of shortcuts that are backed up</h4>
 *
 * <ul>
 *  <li>Pinned shortcuts are backed up.  Bitmap icons are not backed up by the system,
 *  so launcher apps should back them up and restore them so that the user still sees icons
 *  for pinned shortcuts on the launcher.  Apps can always use
 *  {@link #updateShortcuts(List)} to re-publish icons.
 *
 *  <li>Static shortcuts aren't backed up, but when an app is re-installed on a new
 *  device, they are re-published from the <code>AndroidManifest.xml</code> file.
 *
 *  <li>Dynamic shortcuts <b>aren't</b> backed up.
 * </ul>
 *
 * <p>Because dynamic shortcuts are not restored, it is recommended that apps check
 * currently-published dynamic shortcuts using {@link #getDynamicShortcuts()}
 * each time they are launched, and they should re-publish
 * dynamic shortcuts when necessary.
 *
 * <pre>
 *public class MainActivity extends Activity {
 *    public void onCreate(Bundle savedInstanceState) {
 *        super.onCreate(savedInstanceState);
 *        ShortcutManager shortcutManager =
 *                getSystemService(ShortcutManager.class);
 *
 *        if (shortcutManager.getDynamicShortcuts().size() == 0) {
 *            // Application restored. Need to re-publish dynamic shortcuts.
 *            if (shortcutManager.getPinnedShortcuts().size() > 0) {
 *                // Pinned shortcuts have been restored. Use
 *                // updateShortcuts() to make sure they contain
 *                // up-to-date information.
 *            }
 *        }
 *    }
 *    // ...
 *}
 * </pre>
 *
 *
 * <h4>Backup/restore and shortcut IDs</h4>
 * <p>
 * Because pinned shortcuts are backed up and restored on new devices, shortcut IDs
 * should contain either stable, constant strings or server-side identifiers,
 * rather than identifiers generated locally that might not make sense on other devices.
 *
 *
 * <h3>Report Shortcut Usage and Prediction</h3>
 * <p>
 * Launcher apps may be capable of predicting which shortcuts will most likely be
 * used at a given time by examining the shortcut usage history data.
 *
 * <p>In order to provide launchers with such data, publisher apps should
 * report the shortcuts that are used with {@link #reportShortcutUsed(String)}
 * when a shortcut is selected,
 * <b>or when an action equivalent to a shortcut is taken by the user even if it wasn't started
 * with the shortcut</b>.
 *
 * <p>For example, suppose a navigation app supports "navigate to work" as a shortcut.
 * It should then report when the user selects this shortcut <b>and</b> when the user chooses
 * to navigate to work within the app itself.
 * This helps the launcher app
 * learn that the user wants to navigate to work at a certain time every
 * weekday, and it can then show this shortcut in a suggestion list at the right time.
 *
 * <h3>Launcher API</h3>
 *
 * The {@link LauncherApps} class provides APIs for launcher apps to access shortcuts.
 *
 *
 * <h3>Direct Boot and Shortcuts</h3>
 *
 * All shortcut information is stored in credential encrypted storage, so no shortcuts can be
 * accessed when the user is locked.
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
     * Publish the list of shortcuts.  All existing dynamic shortcuts from the caller app
     * will be replaced.  If there are already pinned shortcuts with the same IDs,
     * the mutable pinned shortcuts are updated.
     *
     * <p>This API will be rate-limited.
     *
     * @return {@code true} if the call has succeeded. {@code false} if the call is rate-limited.
     *
     * @throws IllegalArgumentException if {@link #getMaxShortcutCountPerActivity()} is exceeded,
     * or when trying to update immutable shortcuts.
     *
     * @throws IllegalStateException when the user is locked.
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
     * Return all dynamic shortcuts from the caller app.
     *
     * @throws IllegalStateException when the user is locked.
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
     * Return all static (manifest) shortcuts from the caller app.
     *
     * @throws IllegalStateException when the user is locked.
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
     * Publish the list of dynamic shortcuts.  If there are already dynamic or pinned shortcuts with
     * the same IDs, each mutable shortcut is updated.
     *
     * <p>This API will be rate-limited.
     *
     * @return {@code true} if the call has succeeded. {@code false} if the call is rate-limited.
     *
     * @throws IllegalArgumentException if {@link #getMaxShortcutCountPerActivity()} is exceeded,
     * or when trying to update immutable shortcuts.
     *
     * @throws IllegalStateException when the user is locked.
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
     *
     * @throws IllegalStateException when the user is locked.
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
     * Delete all dynamic shortcuts from the caller app.
     *
     * @throws IllegalStateException when the user is locked.
     */
    public void removeAllDynamicShortcuts() {
        try {
            mService.removeAllDynamicShortcuts(mContext.getPackageName(), injectMyUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return all pinned shortcuts from the caller app.
     *
     * @throws IllegalStateException when the user is locked.
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
     * dynamic, but they must not be immutable.
     *
     * <p>This API will be rate-limited.
     *
     * @return {@code true} if the call has succeeded. {@code false} if the call is rate-limited.
     *
     * @throws IllegalArgumentException If trying to update immutable shortcuts.
     *
     * @throws IllegalStateException when the user is locked.
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
     * Disable pinned shortcuts.  For more details, see the Javadoc for the {@link ShortcutManager}
     * class.
     *
     * @throws IllegalArgumentException If trying to disable immutable shortcuts.
     *
     * @throws IllegalStateException when the user is locked.
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
     * Disable pinned shortcuts, showing the user a custom error message when they try to select
     * the disabled shortcuts.
     * For more details, see the Javadoc for the {@link ShortcutManager} class.
     *
     * @throws IllegalArgumentException If trying to disable immutable shortcuts.
     *
     * @throws IllegalStateException when the user is locked.
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
     * Re-enable pinned shortcuts that were previously disabled.  If the target shortcuts
     * are already enabled, this method does nothing.
     *
     * @throws IllegalArgumentException If trying to enable immutable shortcuts.
     *
     * @throws IllegalStateException when the user is locked.
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
     * Return the maximum number of static and dynamic shortcuts that each launcher icon
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
     * Return the number of times the caller app can call the rate-limited APIs
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
     * Return {@code true} when rate-limiting is active for the caller app.
     *
     * <p>See the class level javadoc for details.
     *
     * @throws IllegalStateException when the user is locked.
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
     * Apps that publish shortcuts should call this method whenever the user
     * selects the shortcut containing the given ID or when the user completes
     * an action in the app that is equivalent to selecting the shortcut.
     * For more details, see the Javadoc for the {@link ShortcutManager} class
     *
     * <p>The information is accessible via {@link UsageStatsManager#queryEvents}
     * Typically, launcher apps use this information to build a prediction model
     * so that they can promote the shortcuts that are likely to be used at the moment.
     *
     * @throws IllegalStateException when the user is locked.
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
     * Called internally when an app is considered to have come to the foreground
     * even when technically it's not.  This method resets the throttling for this package.
     * For example, when the user sends an "inline reply" on a notification, the system UI will
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
