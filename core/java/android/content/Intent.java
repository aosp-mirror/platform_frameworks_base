/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.content;

import static android.app.sdksandbox.SdkSandboxManager.ACTION_START_SANDBOXED_ACTIVITY;
import static android.content.ContentProvider.maybeAddUserId;
import static android.os.Flags.FLAG_ALLOW_PRIVATE_PROFILE;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.annotation.AnyRes;
import android.annotation.BroadcastBehavior;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.app.Activity;
import android.app.ActivityThread;
import android.app.AppGlobals;
import android.app.StatusBarManager;
import android.bluetooth.BluetoothDevice;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.content.pm.SuspendDialogInfo;
import android.content.pm.verify.domain.DomainVerificationManager;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.BundleMerger;
import android.os.IBinder;
import android.os.IncidentManager;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.ResultReceiver;
import android.os.ShellCommand;
import android.os.StrictMode;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.provider.ContactsContract.QuickContact;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.service.chooser.ChooserAction;
import android.telecom.PhoneAccount;
import android.telecom.TelecomManager;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.util.Log;
import android.util.proto.ProtoOutputStream;

import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serializable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;

/**
 * An intent is an abstract description of an operation to be performed.  It
 * can be used with {@link Context#startActivity(Intent) startActivity} to
 * launch an {@link android.app.Activity},
 * {@link android.content.Context#sendBroadcast(Intent) broadcastIntent} to
 * send it to any interested {@link BroadcastReceiver BroadcastReceiver} components,
 * and {@link android.content.Context#startService} or
 * {@link android.content.Context#bindService} to communicate with a
 * background {@link android.app.Service}.
 *
 * <p>An Intent provides a facility for performing late runtime binding between the code in
 * different applications. Its most significant use is in the launching of activities, where it
 * can be thought of as the glue between activities. It is basically a passive data structure
 * holding an abstract description of an action to be performed.</p>
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For information about how to create and resolve intents, read the
 * <a href="{@docRoot}guide/topics/intents/intents-filters.html">Intents and Intent Filters</a>
 * developer guide.</p>
 * </div>
 *
 * <a name="IntentStructure"></a>
 * <h3>Intent Structure</h3>
 * <p>The primary pieces of information in an intent are:</p>
 *
 * <ul>
 *   <li> <p><b>action</b> -- The general action to be performed, such as
 *     {@link #ACTION_VIEW}, {@link #ACTION_EDIT}, {@link #ACTION_MAIN},
 *     etc.</p>
 *   </li>
 *   <li> <p><b>data</b> -- The data to operate on, such as a person record
 *     in the contacts database, expressed as a {@link android.net.Uri}.</p>
 *   </li>
 * </ul>
 *
 *
 * <p>Some examples of action/data pairs are:</p>
 *
 * <ul>
 *   <li> <p><b>{@link #ACTION_VIEW} <i>content://contacts/people/1</i></b> -- Display
 *     information about the person whose identifier is "1".</p>
 *   </li>
 *   <li> <p><b>{@link #ACTION_DIAL} <i>content://contacts/people/1</i></b> -- Display
 *     the phone dialer with the person filled in.</p>
 *   </li>
 *   <li> <p><b>{@link #ACTION_VIEW} <i>tel:123</i></b> -- Display
 *     the phone dialer with the given number filled in.  Note how the
 *     VIEW action does what is considered the most reasonable thing for
 *     a particular URI.</p>
 *   </li>
 *   <li> <p><b>{@link #ACTION_DIAL} <i>tel:123</i></b> -- Display
 *     the phone dialer with the given number filled in.</p>
 *   </li>
 *   <li> <p><b>{@link #ACTION_EDIT} <i>content://contacts/people/1</i></b> -- Edit
 *     information about the person whose identifier is "1".</p>
 *   </li>
 *   <li> <p><b>{@link #ACTION_VIEW} <i>content://contacts/people/</i></b> -- Display
 *     a list of people, which the user can browse through.  This example is a
 *     typical top-level entry into the Contacts application, showing you the
 *     list of people. Selecting a particular person to view would result in a
 *     new intent { <b>{@link #ACTION_VIEW} <i>content://contacts/people/N</i></b> }
 *     being used to start an activity to display that person.</p>
 *   </li>
 * </ul>
 *
 * <p>In addition to these primary attributes, there are a number of secondary
 * attributes that you can also include with an intent:</p>
 *
 * <ul>
 *     <li> <p><b>category</b> -- Gives additional information about the action
 *         to execute.  For example, {@link #CATEGORY_LAUNCHER} means it should
 *         appear in the Launcher as a top-level application, while
 *         {@link #CATEGORY_ALTERNATIVE} means it should be included in a list
 *         of alternative actions the user can perform on a piece of data.</p>
 *     <li> <p><b>type</b> -- Specifies an explicit type (a MIME type) of the
 *         intent data.  Normally the type is inferred from the data itself.
 *         By setting this attribute, you disable that evaluation and force
 *         an explicit type.</p>
 *     <li> <p><b>component</b> -- Specifies an explicit name of a component
 *         class to use for the intent.  Normally this is determined by looking
 *         at the other information in the intent (the action, data/type, and
 *         categories) and matching that with a component that can handle it.
 *         If this attribute is set then none of the evaluation is performed,
 *         and this component is used exactly as is.  By specifying this attribute,
 *         all of the other Intent attributes become optional.</p>
 *     <li> <p><b>extras</b> -- This is a {@link Bundle} of any additional information.
 *         This can be used to provide extended information to the component.
 *         For example, if we have a action to send an e-mail message, we could
 *         also include extra pieces of data here to supply a subject, body,
 *         etc.</p>
 * </ul>
 *
 * <p>Here are some examples of other operations you can specify as intents
 * using these additional parameters:</p>
 *
 * <ul>
 *   <li> <p><b>{@link #ACTION_MAIN} with category {@link #CATEGORY_HOME}</b> --
 *     Launch the home screen.</p>
 *   </li>
 *   <li> <p><b>{@link #ACTION_GET_CONTENT} with MIME type
 *     <i>{@link android.provider.Contacts.Phones#CONTENT_URI
 *     vnd.android.cursor.item/phone}</i></b>
 *     -- Display the list of people's phone numbers, allowing the user to
 *     browse through them and pick one and return it to the parent activity.</p>
 *   </li>
 *   <li> <p><b>{@link #ACTION_GET_CONTENT} with MIME type
 *     <i>*{@literal /}*</i> and category {@link #CATEGORY_OPENABLE}</b>
 *     -- Display all pickers for data that can be opened with
 *     {@link ContentResolver#openInputStream(Uri) ContentResolver.openInputStream()},
 *     allowing the user to pick one of them and then some data inside of it
 *     and returning the resulting URI to the caller.  This can be used,
 *     for example, in an e-mail application to allow the user to pick some
 *     data to include as an attachment.</p>
 *   </li>
 * </ul>
 *
 * <p>There are a variety of standard Intent action and category constants
 * defined in the Intent class, but applications can also define their own.
 * These strings use Java-style scoping, to ensure they are unique -- for
 * example, the standard {@link #ACTION_VIEW} is called
 * "android.intent.action.VIEW".</p>
 *
 * <p>Put together, the set of actions, data types, categories, and extra data
 * defines a language for the system allowing for the expression of phrases
 * such as "call john smith's cell".  As applications are added to the system,
 * they can extend this language by adding new actions, types, and categories, or
 * they can modify the behavior of existing phrases by supplying their own
 * activities that handle them.</p>
 *
 * <a name="IntentResolution"></a>
 * <h3>Intent Resolution</h3>
 *
 * <p>There are two primary forms of intents you will use.
 *
 * <ul>
 *     <li> <p><b>Explicit Intents</b> have specified a component (via
 *     {@link #setComponent} or {@link #setClass}), which provides the exact
 *     class to be run.  Often these will not include any other information,
 *     simply being a way for an application to launch various internal
 *     activities it has as the user interacts with the application.
 *
 *     <li> <p><b>Implicit Intents</b> have not specified a component;
 *     instead, they must include enough information for the system to
 *     determine which of the available components is best to run for that
 *     intent.
 * </ul>
 *
 * <p>When using implicit intents, given such an arbitrary intent we need to
 * know what to do with it. This is handled by the process of <em>Intent
 * resolution</em>, which maps an Intent to an {@link android.app.Activity},
 * {@link BroadcastReceiver}, or {@link android.app.Service} (or sometimes two or
 * more activities/receivers) that can handle it.</p>
 *
 * <p>The intent resolution mechanism basically revolves around matching an
 * Intent against all of the &lt;intent-filter&gt; descriptions in the
 * installed application packages.  (Plus, in the case of broadcasts, any {@link BroadcastReceiver}
 * objects explicitly registered with {@link Context#registerReceiver}.)  More
 * details on this can be found in the documentation on the {@link
 * IntentFilter} class.</p>
 *
 * <p>There are three pieces of information in the Intent that are used for
 * resolution: the action, type, and category.  Using this information, a query
 * is done on the {@link PackageManager} for a component that can handle the
 * intent. The appropriate component is determined based on the intent
 * information supplied in the <code>AndroidManifest.xml</code> file as
 * follows:</p>
 *
 * <ul>
 *     <li> <p>The <b>action</b>, if given, must be listed by the component as
 *         one it handles.</p>
 *     <li> <p>The <b>type</b> is retrieved from the Intent's data, if not
 *         already supplied in the Intent.  Like the action, if a type is
 *         included in the intent (either explicitly or implicitly in its
 *         data), then this must be listed by the component as one it handles.</p>
 *     <li> For data that is not a <code>content:</code> URI and where no explicit
 *         type is included in the Intent, instead the <b>scheme</b> of the
 *         intent data (such as <code>http:</code> or <code>mailto:</code>) is
 *         considered. Again like the action, if we are matching a scheme it
 *         must be listed by the component as one it can handle.
 *     <li> <p>The <b>categories</b>, if supplied, must <em>all</em> be listed
 *         by the activity as categories it handles.  That is, if you include
 *         the categories {@link #CATEGORY_LAUNCHER} and
 *         {@link #CATEGORY_ALTERNATIVE}, then you will only resolve to components
 *         with an intent that lists <em>both</em> of those categories.
 *         Activities will very often need to support the
 *         {@link #CATEGORY_DEFAULT} so that they can be found by
 *         {@link Context#startActivity Context.startActivity()}.</p>
 * </ul>
 *
 * <p>For example, consider the Note Pad sample application that
 * allows a user to browse through a list of notes data and view details about
 * individual items.  Text in italics indicates places where you would replace a
 * name with one specific to your own package.</p>
 *
 * <pre> &lt;manifest xmlns:android="http://schemas.android.com/apk/res/android"
 *       package="<i>com.android.notepad</i>"&gt;
 *     &lt;application android:icon="@drawable/app_notes"
 *             android:label="@string/app_name"&gt;
 *
 *         &lt;provider class=".NotePadProvider"
 *                 android:authorities="<i>com.google.provider.NotePad</i>" /&gt;
 *
 *         &lt;activity class=".NotesList" android:label="@string/title_notes_list"&gt;
 *             &lt;intent-filter&gt;
 *                 &lt;action android:name="android.intent.action.MAIN" /&gt;
 *                 &lt;category android:name="android.intent.category.LAUNCHER" /&gt;
 *             &lt;/intent-filter&gt;
 *             &lt;intent-filter&gt;
 *                 &lt;action android:name="android.intent.action.VIEW" /&gt;
 *                 &lt;action android:name="android.intent.action.EDIT" /&gt;
 *                 &lt;action android:name="android.intent.action.PICK" /&gt;
 *                 &lt;category android:name="android.intent.category.DEFAULT" /&gt;
 *                 &lt;data android:mimeType="vnd.android.cursor.dir/<i>vnd.google.note</i>" /&gt;
 *             &lt;/intent-filter&gt;
 *             &lt;intent-filter&gt;
 *                 &lt;action android:name="android.intent.action.GET_CONTENT" /&gt;
 *                 &lt;category android:name="android.intent.category.DEFAULT" /&gt;
 *                 &lt;data android:mimeType="vnd.android.cursor.item/<i>vnd.google.note</i>" /&gt;
 *             &lt;/intent-filter&gt;
 *         &lt;/activity&gt;
 *
 *         &lt;activity class=".NoteEditor" android:label="@string/title_note"&gt;
 *             &lt;intent-filter android:label="@string/resolve_edit"&gt;
 *                 &lt;action android:name="android.intent.action.VIEW" /&gt;
 *                 &lt;action android:name="android.intent.action.EDIT" /&gt;
 *                 &lt;category android:name="android.intent.category.DEFAULT" /&gt;
 *                 &lt;data android:mimeType="vnd.android.cursor.item/<i>vnd.google.note</i>" /&gt;
 *             &lt;/intent-filter&gt;
 *
 *             &lt;intent-filter&gt;
 *                 &lt;action android:name="android.intent.action.INSERT" /&gt;
 *                 &lt;category android:name="android.intent.category.DEFAULT" /&gt;
 *                 &lt;data android:mimeType="vnd.android.cursor.dir/<i>vnd.google.note</i>" /&gt;
 *             &lt;/intent-filter&gt;
 *
 *         &lt;/activity&gt;
 *
 *         &lt;activity class=".TitleEditor" android:label="@string/title_edit_title"
 *                 android:theme="@android:style/Theme.Dialog"&gt;
 *             &lt;intent-filter android:label="@string/resolve_title"&gt;
 *                 &lt;action android:name="<i>com.android.notepad.action.EDIT_TITLE</i>" /&gt;
 *                 &lt;category android:name="android.intent.category.DEFAULT" /&gt;
 *                 &lt;category android:name="android.intent.category.ALTERNATIVE" /&gt;
 *                 &lt;category android:name="android.intent.category.SELECTED_ALTERNATIVE" /&gt;
 *                 &lt;data android:mimeType="vnd.android.cursor.item/<i>vnd.google.note</i>" /&gt;
 *             &lt;/intent-filter&gt;
 *         &lt;/activity&gt;
 *
 *     &lt;/application&gt;
 * &lt;/manifest&gt;</pre>
 *
 * <p>The first activity,
 * <code>com.android.notepad.NotesList</code>, serves as our main
 * entry into the app.  It can do three things as described by its three intent
 * templates:
 * <ol>
 * <li><pre>
 * &lt;intent-filter&gt;
 *     &lt;action android:name="{@link #ACTION_MAIN android.intent.action.MAIN}" /&gt;
 *     &lt;category android:name="{@link #CATEGORY_LAUNCHER android.intent.category.LAUNCHER}" /&gt;
 * &lt;/intent-filter&gt;</pre>
 * <p>This provides a top-level entry into the NotePad application: the standard
 * MAIN action is a main entry point (not requiring any other information in
 * the Intent), and the LAUNCHER category says that this entry point should be
 * listed in the application launcher.</p>
 * <li><pre>
 * &lt;intent-filter&gt;
 *     &lt;action android:name="{@link #ACTION_VIEW android.intent.action.VIEW}" /&gt;
 *     &lt;action android:name="{@link #ACTION_EDIT android.intent.action.EDIT}" /&gt;
 *     &lt;action android:name="{@link #ACTION_PICK android.intent.action.PICK}" /&gt;
 *     &lt;category android:name="{@link #CATEGORY_DEFAULT android.intent.category.DEFAULT}" /&gt;
 *     &lt;data android:mimeType="vnd.android.cursor.dir/<i>vnd.google.note</i>" /&gt;
 * &lt;/intent-filter&gt;</pre>
 * <p>This declares the things that the activity can do on a directory of
 * notes.  The type being supported is given with the &lt;type&gt; tag, where
 * <code>vnd.android.cursor.dir/vnd.google.note</code> is a URI from which
 * a Cursor of zero or more items (<code>vnd.android.cursor.dir</code>) can
 * be retrieved which holds our note pad data (<code>vnd.google.note</code>).
 * The activity allows the user to view or edit the directory of data (via
 * the VIEW and EDIT actions), or to pick a particular note and return it
 * to the caller (via the PICK action).  Note also the DEFAULT category
 * supplied here: this is <em>required</em> for the
 * {@link Context#startActivity Context.startActivity} method to resolve your
 * activity when its component name is not explicitly specified.</p>
 * <li><pre>
 * &lt;intent-filter&gt;
 *     &lt;action android:name="{@link #ACTION_GET_CONTENT android.intent.action.GET_CONTENT}" /&gt;
 *     &lt;category android:name="{@link #CATEGORY_DEFAULT android.intent.category.DEFAULT}" /&gt;
 *     &lt;data android:mimeType="vnd.android.cursor.item/<i>vnd.google.note</i>" /&gt;
 * &lt;/intent-filter&gt;</pre>
 * <p>This filter describes the ability to return to the caller a note selected by
 * the user without needing to know where it came from.  The data type
 * <code>vnd.android.cursor.item/vnd.google.note</code> is a URI from which
 * a Cursor of exactly one (<code>vnd.android.cursor.item</code>) item can
 * be retrieved which contains our note pad data (<code>vnd.google.note</code>).
 * The GET_CONTENT action is similar to the PICK action, where the activity
 * will return to its caller a piece of data selected by the user.  Here,
 * however, the caller specifies the type of data they desire instead of
 * the type of data the user will be picking from.</p>
 * </ol>
 *
 * <p>Given these capabilities, the following intents will resolve to the
 * NotesList activity:</p>
 *
 * <ul>
 *     <li> <p><b>{ action=android.app.action.MAIN }</b> matches all of the
 *         activities that can be used as top-level entry points into an
 *         application.</p>
 *     <li> <p><b>{ action=android.app.action.MAIN,
 *         category=android.app.category.LAUNCHER }</b> is the actual intent
 *         used by the Launcher to populate its top-level list.</p>
 *     <li> <p><b>{ action=android.intent.action.VIEW
 *          data=content://com.google.provider.NotePad/notes }</b>
 *         displays a list of all the notes under
 *         "content://com.google.provider.NotePad/notes", which
 *         the user can browse through and see the details on.</p>
 *     <li> <p><b>{ action=android.app.action.PICK
 *          data=content://com.google.provider.NotePad/notes }</b>
 *         provides a list of the notes under
 *         "content://com.google.provider.NotePad/notes", from which
 *         the user can pick a note whose data URL is returned back to the caller.</p>
 *     <li> <p><b>{ action=android.app.action.GET_CONTENT
 *          type=vnd.android.cursor.item/vnd.google.note }</b>
 *         is similar to the pick action, but allows the caller to specify the
 *         kind of data they want back so that the system can find the appropriate
 *         activity to pick something of that data type.</p>
 * </ul>
 *
 * <p>The second activity,
 * <code>com.android.notepad.NoteEditor</code>, shows the user a single
 * note entry and allows them to edit it.  It can do two things as described
 * by its two intent templates:
 * <ol>
 * <li><pre>
 * &lt;intent-filter android:label="@string/resolve_edit"&gt;
 *     &lt;action android:name="{@link #ACTION_VIEW android.intent.action.VIEW}" /&gt;
 *     &lt;action android:name="{@link #ACTION_EDIT android.intent.action.EDIT}" /&gt;
 *     &lt;category android:name="{@link #CATEGORY_DEFAULT android.intent.category.DEFAULT}" /&gt;
 *     &lt;data android:mimeType="vnd.android.cursor.item/<i>vnd.google.note</i>" /&gt;
 * &lt;/intent-filter&gt;</pre>
 * <p>The first, primary, purpose of this activity is to let the user interact
 * with a single note, as decribed by the MIME type
 * <code>vnd.android.cursor.item/vnd.google.note</code>.  The activity can
 * either VIEW a note or allow the user to EDIT it.  Again we support the
 * DEFAULT category to allow the activity to be launched without explicitly
 * specifying its component.</p>
 * <li><pre>
 * &lt;intent-filter&gt;
 *     &lt;action android:name="{@link #ACTION_INSERT android.intent.action.INSERT}" /&gt;
 *     &lt;category android:name="{@link #CATEGORY_DEFAULT android.intent.category.DEFAULT}" /&gt;
 *     &lt;data android:mimeType="vnd.android.cursor.dir/<i>vnd.google.note</i>" /&gt;
 * &lt;/intent-filter&gt;</pre>
 * <p>The secondary use of this activity is to insert a new note entry into
 * an existing directory of notes.  This is used when the user creates a new
 * note: the INSERT action is executed on the directory of notes, causing
 * this activity to run and have the user create the new note data which
 * it then adds to the content provider.</p>
 * </ol>
 *
 * <p>Given these capabilities, the following intents will resolve to the
 * NoteEditor activity:</p>
 *
 * <ul>
 *     <li> <p><b>{ action=android.intent.action.VIEW
 *          data=content://com.google.provider.NotePad/notes/<var>{ID}</var> }</b>
 *         shows the user the content of note <var>{ID}</var>.</p>
 *     <li> <p><b>{ action=android.app.action.EDIT
 *          data=content://com.google.provider.NotePad/notes/<var>{ID}</var> }</b>
 *         allows the user to edit the content of note <var>{ID}</var>.</p>
 *     <li> <p><b>{ action=android.app.action.INSERT
 *          data=content://com.google.provider.NotePad/notes }</b>
 *         creates a new, empty note in the notes list at
 *         "content://com.google.provider.NotePad/notes"
 *         and allows the user to edit it.  If they keep their changes, the URI
 *         of the newly created note is returned to the caller.</p>
 * </ul>
 *
 * <p>The last activity,
 * <code>com.android.notepad.TitleEditor</code>, allows the user to
 * edit the title of a note.  This could be implemented as a class that the
 * application directly invokes (by explicitly setting its component in
 * the Intent), but here we show a way you can publish alternative
 * operations on existing data:</p>
 *
 * <pre>
 * &lt;intent-filter android:label="@string/resolve_title"&gt;
 *     &lt;action android:name="<i>com.android.notepad.action.EDIT_TITLE</i>" /&gt;
 *     &lt;category android:name="{@link #CATEGORY_DEFAULT android.intent.category.DEFAULT}" /&gt;
 *     &lt;category android:name="{@link #CATEGORY_ALTERNATIVE android.intent.category.ALTERNATIVE}" /&gt;
 *     &lt;category android:name="{@link #CATEGORY_SELECTED_ALTERNATIVE android.intent.category.SELECTED_ALTERNATIVE}" /&gt;
 *     &lt;data android:mimeType="vnd.android.cursor.item/<i>vnd.google.note</i>" /&gt;
 * &lt;/intent-filter&gt;</pre>
 *
 * <p>In the single intent template here, we
 * have created our own private action called
 * <code>com.android.notepad.action.EDIT_TITLE</code> which means to
 * edit the title of a note.  It must be invoked on a specific note
 * (data type <code>vnd.android.cursor.item/vnd.google.note</code>) like the previous
 * view and edit actions, but here displays and edits the title contained
 * in the note data.
 *
 * <p>In addition to supporting the default category as usual, our title editor
 * also supports two other standard categories: ALTERNATIVE and
 * SELECTED_ALTERNATIVE.  Implementing
 * these categories allows others to find the special action it provides
 * without directly knowing about it, through the
 * {@link android.content.pm.PackageManager#queryIntentActivityOptions} method, or
 * more often to build dynamic menu items with
 * {@link android.view.Menu#addIntentOptions}.  Note that in the intent
 * template here was also supply an explicit name for the template
 * (via <code>android:label="@string/resolve_title"</code>) to better control
 * what the user sees when presented with this activity as an alternative
 * action to the data they are viewing.
 *
 * <p>Given these capabilities, the following intent will resolve to the
 * TitleEditor activity:</p>
 *
 * <ul>
 *     <li> <p><b>{ action=com.android.notepad.action.EDIT_TITLE
 *          data=content://com.google.provider.NotePad/notes/<var>{ID}</var> }</b>
 *         displays and allows the user to edit the title associated
 *         with note <var>{ID}</var>.</p>
 * </ul>
 *
 * <h3>Standard Activity Actions</h3>
 *
 * <p>These are the current standard actions that Intent defines for launching
 * activities (usually through {@link Context#startActivity}.  The most
 * important, and by far most frequently used, are {@link #ACTION_MAIN} and
 * {@link #ACTION_EDIT}.
 *
 * <ul>
 *     <li> {@link #ACTION_MAIN}
 *     <li> {@link #ACTION_VIEW}
 *     <li> {@link #ACTION_ATTACH_DATA}
 *     <li> {@link #ACTION_EDIT}
 *     <li> {@link #ACTION_PICK}
 *     <li> {@link #ACTION_CHOOSER}
 *     <li> {@link #ACTION_GET_CONTENT}
 *     <li> {@link #ACTION_DIAL}
 *     <li> {@link #ACTION_CALL}
 *     <li> {@link #ACTION_SEND}
 *     <li> {@link #ACTION_SENDTO}
 *     <li> {@link #ACTION_ANSWER}
 *     <li> {@link #ACTION_INSERT}
 *     <li> {@link #ACTION_DELETE}
 *     <li> {@link #ACTION_RUN}
 *     <li> {@link #ACTION_SYNC}
 *     <li> {@link #ACTION_PICK_ACTIVITY}
 *     <li> {@link #ACTION_SEARCH}
 *     <li> {@link #ACTION_WEB_SEARCH}
 *     <li> {@link #ACTION_FACTORY_TEST}
 * </ul>
 *
 * <h3>Standard Broadcast Actions</h3>
 *
 * <p>These are the current standard actions that Intent defines for receiving
 * broadcasts (usually through {@link Context#registerReceiver} or a
 * &lt;receiver&gt; tag in a manifest).
 *
 * <ul>
 *     <li> {@link #ACTION_TIME_TICK}
 *     <li> {@link #ACTION_TIME_CHANGED}
 *     <li> {@link #ACTION_TIMEZONE_CHANGED}
 *     <li> {@link #ACTION_BOOT_COMPLETED}
 *     <li> {@link #ACTION_PACKAGE_ADDED}
 *     <li> {@link #ACTION_PACKAGE_CHANGED}
 *     <li> {@link #ACTION_PACKAGE_REMOVED}
 *     <li> {@link #ACTION_PACKAGE_RESTARTED}
 *     <li> {@link #ACTION_PACKAGE_DATA_CLEARED}
 *     <li> {@link #ACTION_PACKAGES_SUSPENDED}
 *     <li> {@link #ACTION_PACKAGES_UNSUSPENDED}
 *     <li> {@link #ACTION_UID_REMOVED}
 *     <li> {@link #ACTION_BATTERY_CHANGED}
 *     <li> {@link #ACTION_POWER_CONNECTED}
 *     <li> {@link #ACTION_POWER_DISCONNECTED}
 *     <li> {@link #ACTION_SHUTDOWN}
 * </ul>
 *
 * <p class="note"><strong>Note: </strong>If your app targets Android 11
 * (API level 30) or higher, registering broadcast such as
 * {@link #ACTION_PACKAGES_SUSPENDED} that includes package details in the
 * extras receives a filtered list of apps or nothing. Learn more about how to
 * <a href="/training/basics/intents/package-visibility">manage package visibility</a>.
 * </p>
 *
 * <h3>Standard Categories</h3>
 *
 * <p>These are the current standard categories that can be used to further
 * clarify an Intent via {@link #addCategory}.
 *
 * <ul>
 *     <li> {@link #CATEGORY_DEFAULT}
 *     <li> {@link #CATEGORY_BROWSABLE}
 *     <li> {@link #CATEGORY_TAB}
 *     <li> {@link #CATEGORY_ALTERNATIVE}
 *     <li> {@link #CATEGORY_SELECTED_ALTERNATIVE}
 *     <li> {@link #CATEGORY_LAUNCHER}
 *     <li> {@link #CATEGORY_INFO}
 *     <li> {@link #CATEGORY_HOME}
 *     <li> {@link #CATEGORY_PREFERENCE}
 *     <li> {@link #CATEGORY_TEST}
 *     <li> {@link #CATEGORY_CAR_DOCK}
 *     <li> {@link #CATEGORY_DESK_DOCK}
 *     <li> {@link #CATEGORY_LE_DESK_DOCK}
 *     <li> {@link #CATEGORY_HE_DESK_DOCK}
 *     <li> {@link #CATEGORY_CAR_MODE}
 *     <li> {@link #CATEGORY_APP_MARKET}
 *     <li> {@link #CATEGORY_VR_HOME}
 * </ul>
 *
 * <h3>Standard Extra Data</h3>
 *
 * <p>These are the current standard fields that can be used as extra data via
 * {@link #putExtra}.
 *
 * <ul>
 *     <li> {@link #EXTRA_ALARM_COUNT}
 *     <li> {@link #EXTRA_BCC}
 *     <li> {@link #EXTRA_CC}
 *     <li> {@link #EXTRA_CHANGED_COMPONENT_NAME}
 *     <li> {@link #EXTRA_DATA_REMOVED}
 *     <li> {@link #EXTRA_DOCK_STATE}
 *     <li> {@link #EXTRA_DOCK_STATE_HE_DESK}
 *     <li> {@link #EXTRA_DOCK_STATE_LE_DESK}
 *     <li> {@link #EXTRA_DOCK_STATE_CAR}
 *     <li> {@link #EXTRA_DOCK_STATE_DESK}
 *     <li> {@link #EXTRA_DOCK_STATE_UNDOCKED}
 *     <li> {@link #EXTRA_DONT_KILL_APP}
 *     <li> {@link #EXTRA_EMAIL}
 *     <li> {@link #EXTRA_INITIAL_INTENTS}
 *     <li> {@link #EXTRA_INTENT}
 *     <li> {@link #EXTRA_KEY_EVENT}
 *     <li> {@link #EXTRA_ORIGINATING_URI}
 *     <li> {@link #EXTRA_PHONE_NUMBER}
 *     <li> {@link #EXTRA_REFERRER}
 *     <li> {@link #EXTRA_REMOTE_INTENT_TOKEN}
 *     <li> {@link #EXTRA_REPLACING}
 *     <li> {@link #EXTRA_SHORTCUT_ICON}
 *     <li> {@link #EXTRA_SHORTCUT_ICON_RESOURCE}
 *     <li> {@link #EXTRA_SHORTCUT_INTENT}
 *     <li> {@link #EXTRA_STREAM}
 *     <li> {@link #EXTRA_SHORTCUT_NAME}
 *     <li> {@link #EXTRA_SUBJECT}
 *     <li> {@link #EXTRA_TEMPLATE}
 *     <li> {@link #EXTRA_TEXT}
 *     <li> {@link #EXTRA_TITLE}
 *     <li> {@link #EXTRA_UID}
 *     <li> {@link #EXTRA_USER_INITIATED}
 * </ul>
 *
 * <h3>Flags</h3>
 *
 * <p>These are the possible flags that can be used in the Intent via
 * {@link #setFlags} and {@link #addFlags}.  See {@link #setFlags} for a list
 * of all possible flags.
 */
public class Intent implements Parcelable, Cloneable {
    private static final String TAG = "Intent";

    private static final String ATTR_ACTION = "action";
    private static final String TAG_CATEGORIES = "categories";
    private static final String ATTR_CATEGORY = "category";
    private static final String TAG_EXTRA = "extra";
    private static final String ATTR_TYPE = "type";
    private static final String ATTR_IDENTIFIER = "ident";
    private static final String ATTR_COMPONENT = "component";
    private static final String ATTR_DATA = "data";
    private static final String ATTR_FLAGS = "flags";

    // ---------------------------------------------------------------------
    // ---------------------------------------------------------------------
    // Standard intent activity actions (see action variable).

    /**
     *  Activity Action: Start as a main entry point, does not expect to
     *  receive data.
     *  <p>Input: nothing
     *  <p>Output: nothing
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_MAIN = "android.intent.action.MAIN";

    /**
     * Activity Action: Display the data to the user.  This is the most common
     * action performed on data -- it is the generic action you can use on
     * a piece of data to get the most reasonable thing to occur.  For example,
     * when used on a contacts entry it will view the entry; when used on a
     * mailto: URI it will bring up a compose window filled with the information
     * supplied by the URI; when used with a tel: URI it will invoke the
     * dialer.
     * <p>Input: {@link #getData} is URI from which to retrieve data.
     * <p>Output: nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_VIEW = "android.intent.action.VIEW";

    /**
     * Extra that can be included on activity intents coming from the storage UI
     * when it launches sub-activities to manage various types of storage.  For example,
     * it may use {@link #ACTION_VIEW} with a "image/*" MIME type to have an app show
     * the images on the device, and in that case also include this extra to tell the
     * app it is coming from the storage UI so should help the user manage storage of
     * this type.
     */
    public static final String EXTRA_FROM_STORAGE = "android.intent.extra.FROM_STORAGE";

    /**
     * A synonym for {@link #ACTION_VIEW}, the "standard" action that is
     * performed on a piece of data.
     */
    public static final String ACTION_DEFAULT = ACTION_VIEW;

    /**
     * Activity Action: Quick view the data. Launches a quick viewer for
     * a URI or a list of URIs.
     * <p>Activities handling this intent action should handle the vast majority of
     * MIME types rather than only specific ones.
     * <p>Quick viewers must render the quick view image locally, and must not send
     * file content outside current device.
     * <p>Input: {@link #getData} is a mandatory content URI of the item to
     * preview. {@link #getClipData} contains an optional list of content URIs
     * if there is more than one item to preview. {@link #EXTRA_INDEX} is an
     * optional index of the URI in the clip data to show first.
     * {@link #EXTRA_QUICK_VIEW_FEATURES} is an optional extra indicating the features
     * that can be shown in the quick view UI.
     * <p>Output: nothing.
     * @see #EXTRA_INDEX
     * @see #EXTRA_QUICK_VIEW_FEATURES
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_QUICK_VIEW = "android.intent.action.QUICK_VIEW";

    /**
     * Used to indicate that some piece of data should be attached to some other
     * place.  For example, image data could be attached to a contact.  It is up
     * to the recipient to decide where the data should be attached; the intent
     * does not specify the ultimate destination.
     * <p>Input: {@link #getData} is URI of data to be attached.
     * <p>Output: nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_ATTACH_DATA = "android.intent.action.ATTACH_DATA";

    /**
     * Activity Action: Provide explicit editable access to the given data.
     * <p>Input: {@link #getData} is URI of data to be edited.
     * <p>Output: nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_EDIT = "android.intent.action.EDIT";

    /**
     * Activity Action: Pick an existing item, or insert a new item, and then edit it.
     * <p>Input: {@link #getType} is the desired MIME type of the item to create or edit.
     * The extras can contain type specific data to pass through to the editing/creating
     * activity.
     * <p>Output: The URI of the item that was picked.  This must be a content:
     * URI so that any receiver can access it.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_INSERT_OR_EDIT = "android.intent.action.INSERT_OR_EDIT";

    /**
     * Activity Action: Pick an item from the data, returning what was selected.
     * <p>Input: {@link #getData} is URI containing a directory of data
     * (vnd.android.cursor.dir/*) from which to pick an item.
     * <p>Output: The URI of the item that was picked.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_PICK = "android.intent.action.PICK";

    /**
     * Activity Action: Creates a reminder.
     * <p>Input: {@link #EXTRA_TITLE} The title of the reminder that will be shown to the user.
     * {@link #EXTRA_TEXT} The reminder text that will be shown to the user. The intent should at
     * least specify a title or a text. {@link #EXTRA_TIME} The time when the reminder will
     * be shown to the user. The time is specified in milliseconds since the Epoch (optional).
     * </p>
     * <p>Output: Nothing.</p>
     *
     * @see #EXTRA_TITLE
     * @see #EXTRA_TEXT
     * @see #EXTRA_TIME
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_CREATE_REMINDER = "android.intent.action.CREATE_REMINDER";

    /**
     * Activity Action: Creates a shortcut.
     * <p>Input: Nothing.</p>
     * <p>Output: An Intent representing the {@link android.content.pm.ShortcutInfo} result.</p>
     * <p>For compatibility with older versions of android the intent may also contain three
     * extras: SHORTCUT_INTENT (value: Intent), SHORTCUT_NAME (value: String),
     * and SHORTCUT_ICON (value: Bitmap) or SHORTCUT_ICON_RESOURCE
     * (value: ShortcutIconResource).</p>
     *
     * @see android.content.pm.ShortcutManager#createShortcutResultIntent
     * @see #EXTRA_SHORTCUT_INTENT
     * @see #EXTRA_SHORTCUT_NAME
     * @see #EXTRA_SHORTCUT_ICON
     * @see #EXTRA_SHORTCUT_ICON_RESOURCE
     * @see android.content.Intent.ShortcutIconResource
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_CREATE_SHORTCUT = "android.intent.action.CREATE_SHORTCUT";

    /**
     * The name of the extra used to define the Intent of a shortcut.
     *
     * @see #ACTION_CREATE_SHORTCUT
     * @deprecated Replaced with {@link android.content.pm.ShortcutManager#createShortcutResultIntent}
     */
    @Deprecated
    public static final String EXTRA_SHORTCUT_INTENT = "android.intent.extra.shortcut.INTENT";
    /**
     * The name of the extra used to define the name of a shortcut.
     *
     * @see #ACTION_CREATE_SHORTCUT
     * @deprecated Replaced with {@link android.content.pm.ShortcutManager#createShortcutResultIntent}
     */
    @Deprecated
    public static final String EXTRA_SHORTCUT_NAME = "android.intent.extra.shortcut.NAME";
    /**
     * The name of the extra used to define the icon, as a Bitmap, of a shortcut.
     *
     * @see #ACTION_CREATE_SHORTCUT
     * @deprecated Replaced with {@link android.content.pm.ShortcutManager#createShortcutResultIntent}
     */
    @Deprecated
    public static final String EXTRA_SHORTCUT_ICON = "android.intent.extra.shortcut.ICON";
    /**
     * The name of the extra used to define the icon, as a ShortcutIconResource, of a shortcut.
     *
     * @see #ACTION_CREATE_SHORTCUT
     * @see android.content.Intent.ShortcutIconResource
     * @deprecated Replaced with {@link android.content.pm.ShortcutManager#createShortcutResultIntent}
     */
    @Deprecated
    public static final String EXTRA_SHORTCUT_ICON_RESOURCE =
            "android.intent.extra.shortcut.ICON_RESOURCE";

    /**
     * An activity that provides a user interface for adjusting application preferences.
     * Optional but recommended settings for all applications which have settings.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_APPLICATION_PREFERENCES
            = "android.intent.action.APPLICATION_PREFERENCES";

    /**
     * Activity Action: Launch an activity showing the app information.
     * For applications which install other applications (such as app stores), it is recommended
     * to handle this action for providing the app information to the user.
     *
     * <p>Input: {@link #EXTRA_PACKAGE_NAME} specifies the package whose information needs
     * to be displayed.
     * <p>Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SHOW_APP_INFO
            = "android.intent.action.SHOW_APP_INFO";

    /**
     * Activity Action: Placeholder that the component handling it can do activity
     * recognition. Can be placed on a service. Only one service per package is
     * supported.
     *
     * <p>Input: Nothing.</p>
     * <p>Output: Nothing </p>
     *
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.SERVICE_ACTION)
    public static final String ACTION_ACTIVITY_RECOGNIZER =
            "android.intent.action.ACTIVITY_RECOGNIZER";

    /**
     * Represents a shortcut/live folder icon resource.
     *
     * @see Intent#ACTION_CREATE_SHORTCUT
     * @see Intent#EXTRA_SHORTCUT_ICON_RESOURCE
     * @see android.provider.LiveFolders#ACTION_CREATE_LIVE_FOLDER
     * @see android.provider.LiveFolders#EXTRA_LIVE_FOLDER_ICON
     */
    public static class ShortcutIconResource implements Parcelable {
        /**
         * The package name of the application containing the icon.
         */
        public String packageName;

        /**
         * The resource name of the icon, including package, name and type.
         */
        public String resourceName;

        /**
         * Creates a new ShortcutIconResource for the specified context and resource
         * identifier.
         *
         * @param context The context of the application.
         * @param resourceId The resource identifier for the icon.
         * @return A new ShortcutIconResource with the specified's context package name
         *         and icon resource identifier.``
         */
        public static ShortcutIconResource fromContext(Context context, @AnyRes int resourceId) {
            ShortcutIconResource icon = new ShortcutIconResource();
            icon.packageName = context.getPackageName();
            icon.resourceName = context.getResources().getResourceName(resourceId);
            return icon;
        }

        /**
         * Used to read a ShortcutIconResource from a Parcel.
         */
        public static final @android.annotation.NonNull Parcelable.Creator<ShortcutIconResource> CREATOR =
            new Parcelable.Creator<ShortcutIconResource>() {

                public ShortcutIconResource createFromParcel(Parcel source) {
                    ShortcutIconResource icon = new ShortcutIconResource();
                    icon.packageName = source.readString8();
                    icon.resourceName = source.readString8();
                    return icon;
                }

                public ShortcutIconResource[] newArray(int size) {
                    return new ShortcutIconResource[size];
                }
            };

        /**
         * No special parcel contents.
         */
        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString8(packageName);
            dest.writeString8(resourceName);
        }

        @Override
        public String toString() {
            return resourceName;
        }
    }

    /**
     * Activity Action: Display an activity chooser, allowing the user to pick
     * what they want to before proceeding.  This can be used as an alternative
     * to the standard activity picker that is displayed by the system when
     * you try to start an activity with multiple possible matches, with these
     * differences in behavior:
     * <ul>
     * <li>You can specify the title that will appear in the activity chooser.
     * <li>The user does not have the option to make one of the matching
     * activities a preferred activity, and all possible activities will
     * always be shown even if one of them is currently marked as the
     * preferred activity.
     * </ul>
     * <p>
     * This action should be used when the user will naturally expect to
     * select an activity in order to proceed.  An example if when not to use
     * it is when the user clicks on a "mailto:" link.  They would naturally
     * expect to go directly to their mail app, so startActivity() should be
     * called directly: it will
     * either launch the current preferred app, or put up a dialog allowing the
     * user to pick an app to use and optionally marking that as preferred.
     * <p>
     * In contrast, if the user is selecting a menu item to send a picture
     * they are viewing to someone else, there are many different things they
     * may want to do at this point: send it through e-mail, upload it to a
     * web service, etc.  In this case the CHOOSER action should be used, to
     * always present to the user a list of the things they can do, with a
     * nice title given by the caller such as "Send this photo with:".
     * <p>
     * If you need to grant URI permissions through a chooser, you must specify
     * the permissions to be granted on the ACTION_CHOOSER Intent
     * <em>in addition</em> to the EXTRA_INTENT inside.  This means using
     * {@link #setClipData} to specify the URIs to be granted as well as
     * {@link #FLAG_GRANT_READ_URI_PERMISSION} and/or
     * {@link #FLAG_GRANT_WRITE_URI_PERMISSION} as appropriate.
     * <p>
     * As a convenience, an Intent of this form can be created with the
     * {@link #createChooser} function.
     * <p>
     * Input: No data should be specified.  get*Extra must have
     * a {@link #EXTRA_INTENT} field containing the Intent being executed,
     * and can optionally have a {@link #EXTRA_TITLE} field containing the
     * title text to display in the chooser.
     * <p>
     * Output: Depends on the protocol of {@link #EXTRA_INTENT}.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_CHOOSER = "android.intent.action.CHOOSER";

    /**
     * Convenience function for creating a {@link #ACTION_CHOOSER} Intent.
     *
     * <p>Builds a new {@link #ACTION_CHOOSER} Intent that wraps the given
     * target intent, also optionally supplying a title.  If the target
     * intent has specified {@link #FLAG_GRANT_READ_URI_PERMISSION} or
     * {@link #FLAG_GRANT_WRITE_URI_PERMISSION}, then these flags will also be
     * set in the returned chooser intent, with its ClipData set appropriately:
     * either a direct reflection of {@link #getClipData()} if that is non-null,
     * or a new ClipData built from {@link #getData()}.
     *
     * @param target The Intent that the user will be selecting an activity
     * to perform.
     * @param title Optional title that will be displayed in the chooser,
     * only when the target action is not ACTION_SEND or ACTION_SEND_MULTIPLE.
     * @return Return a new Intent object that you can hand to
     * {@link Context#startActivity(Intent) Context.startActivity()} and
     * related methods.
     */
    public static Intent createChooser(Intent target, CharSequence title) {
        return createChooser(target, title, null);
    }

    /**
     * Convenience function for creating a {@link #ACTION_CHOOSER} Intent.
     *
     * <p>Builds a new {@link #ACTION_CHOOSER} Intent that wraps the given
     * target intent, also optionally supplying a title.  If the target
     * intent has specified {@link #FLAG_GRANT_READ_URI_PERMISSION} or
     * {@link #FLAG_GRANT_WRITE_URI_PERMISSION}, then these flags will also be
     * set in the returned chooser intent, with its ClipData set appropriately:
     * either a direct reflection of {@link #getClipData()} if that is non-null,
     * or a new ClipData built from {@link #getData()}.</p>
     *
     * <p>The caller may optionally supply an {@link IntentSender} to receive a callback
     * when the user makes a choice. This can be useful if the calling application wants
     * to remember the last chosen target and surface it as a more prominent or one-touch
     * affordance elsewhere in the UI for next time.</p>
     *
     * @param target The Intent that the user will be selecting an activity
     * to perform.
     * @param title Optional title that will be displayed in the chooser,
     * only when the target action is not ACTION_SEND or ACTION_SEND_MULTIPLE.
     * @param sender Optional IntentSender to be called when a choice is made.
     * @return Return a new Intent object that you can hand to
     * {@link Context#startActivity(Intent) Context.startActivity()} and
     * related methods.
     */
    public static Intent createChooser(Intent target, CharSequence title, IntentSender sender) {
        Intent intent = new Intent(ACTION_CHOOSER);
        intent.putExtra(EXTRA_INTENT, target);
        if (title != null) {
            intent.putExtra(EXTRA_TITLE, title);
        }

        if (sender != null) {
            intent.putExtra(EXTRA_CHOSEN_COMPONENT_INTENT_SENDER, sender);
        }

        // Migrate any clip data and flags from target.
        int permFlags = target.getFlags() & (FLAG_GRANT_READ_URI_PERMISSION
                | FLAG_GRANT_WRITE_URI_PERMISSION | FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | FLAG_GRANT_PREFIX_URI_PERMISSION);
        if (permFlags != 0) {
            ClipData targetClipData = target.getClipData();
            if (targetClipData == null && target.getData() != null) {
                ClipData.Item item = new ClipData.Item(target.getData());
                String[] mimeTypes;
                if (target.getType() != null) {
                    mimeTypes = new String[] { target.getType() };
                } else {
                    mimeTypes = new String[] { };
                }
                targetClipData = new ClipData(null, mimeTypes, item);
            }
            if (targetClipData != null) {
                intent.setClipData(targetClipData);
                intent.addFlags(permFlags);
            }
        }

        return intent;
    }

    /**
     * Activity Action: Allow the user to select a particular kind of data and
     * return it.  This is different than {@link #ACTION_PICK} in that here we
     * just say what kind of data is desired, not a URI of existing data from
     * which the user can pick.  An ACTION_GET_CONTENT could allow the user to
     * create the data as it runs (for example taking a picture or recording a
     * sound), let them browse over the web and download the desired data,
     * etc.
     * <p>
     * There are two main ways to use this action: if you want a specific kind
     * of data, such as a person contact, you set the MIME type to the kind of
     * data you want and launch it with {@link Context#startActivity(Intent)}.
     * The system will then launch the best application to select that kind
     * of data for you.
     * <p>
     * You may also be interested in any of a set of types of content the user
     * can pick.  For example, an e-mail application that wants to allow the
     * user to add an attachment to an e-mail message can use this action to
     * bring up a list of all of the types of content the user can attach.
     * <p>
     * In this case, you should wrap the GET_CONTENT intent with a chooser
     * (through {@link #createChooser}), which will give the proper interface
     * for the user to pick how to send your data and allow you to specify
     * a prompt indicating what they are doing.  You will usually specify a
     * broad MIME type (such as image/* or {@literal *}/*), resulting in a
     * broad range of content types the user can select from.
     * <p>
     * When using such a broad GET_CONTENT action, it is often desirable to
     * only pick from data that can be represented as a stream.  This is
     * accomplished by requiring the {@link #CATEGORY_OPENABLE} in the Intent.
     * <p>
     * Callers can optionally specify {@link #EXTRA_LOCAL_ONLY} to request that
     * the launched content chooser only returns results representing data that
     * is locally available on the device.  For example, if this extra is set
     * to true then an image picker should not show any pictures that are available
     * from a remote server but not already on the local device (thus requiring
     * they be downloaded when opened).
     * <p>
     * If the caller can handle multiple returned items (the user performing
     * multiple selection), then it can specify {@link #EXTRA_ALLOW_MULTIPLE}
     * to indicate this.
     * <p>
     * Input: {@link #getType} is the desired MIME type to retrieve.  Note
     * that no URI is supplied in the intent, as there are no constraints on
     * where the returned data originally comes from.  You may also include the
     * {@link #CATEGORY_OPENABLE} if you can only accept data that can be
     * opened as a stream.  You may use {@link #EXTRA_LOCAL_ONLY} to limit content
     * selection to local data.  You may use {@link #EXTRA_ALLOW_MULTIPLE} to
     * allow the user to select multiple items.
     * <p>
     * Output: The URI of the item that was picked.  This must be a content:
     * URI so that any receiver can access it.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_GET_CONTENT = "android.intent.action.GET_CONTENT";
    /**
     * Activity Action: Dial a number as specified by the data.  This shows a
     * UI with the number being dialed, allowing the user to explicitly
     * initiate the call.
     * <p>Input: If nothing, an empty dialer is started; else {@link #getData}
     * is URI of a phone number to be dialed or a tel: URI of an explicit phone
     * number.
     * <p>Output: nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_DIAL = "android.intent.action.DIAL";
    /**
     * Activity Action: Perform a call to someone specified by the data.
     * <p>Input: If nothing, an empty dialer is started; else {@link #getData}
     * is URI of a phone number to be dialed or a tel: URI of an explicit phone
     * number.
     * <p>Output: nothing.
     *
     * <p>Note: there will be restrictions on which applications can initiate a
     * call; most applications should use the {@link #ACTION_DIAL}.
     * <p>Note: this Intent <strong>cannot</strong> be used to call emergency
     * numbers.  Applications can <strong>dial</strong> emergency numbers using
     * {@link #ACTION_DIAL}, however.
     *
     * <p>Note: This Intent can only be used to dial call forwarding MMI codes if the application
     * using this intent is set as the default or system dialer. The system will treat any other
     * application using this Intent for the purpose of dialing call forwarding MMI codes as if the
     * {@link #ACTION_DIAL} Intent was used instead.
     *
     * <p>Note: An app filling the {@link android.app.role.RoleManager#ROLE_DIALER} role should use
     * {@link android.telecom.TelecomManager#placeCall(Uri, Bundle)} to place calls rather than
     * relying on this intent.
     *
     * <p>Note: if you app targets {@link android.os.Build.VERSION_CODES#M M}
     * and above and declares as using the {@link android.Manifest.permission#CALL_PHONE}
     * permission which is not granted, then attempting to use this action will
     * result in a {@link java.lang.SecurityException}.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_CALL = "android.intent.action.CALL";
    /**
     * Activity Action: Perform a call to an emergency number specified by the
     * data.
     * <p>Input: {@link #getData} is URI of a phone number to be dialed or a
     * tel: URI of an explicit phone number.
     * <p>Output: nothing.
     *
     * <p class="note"><strong>Note:</strong> It is not guaranteed that the call will be placed on
     * the {@link PhoneAccount} provided in the {@link TelecomManager#EXTRA_PHONE_ACCOUNT_HANDLE}
     * extra (if specified) and may be placed on another {@link PhoneAccount} with the
     * {@link PhoneAccount#CAPABILITY_PLACE_EMERGENCY_CALLS} capability, depending on external
     * factors, such as network conditions and Modem/SIM status.
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_CALL_EMERGENCY = "android.intent.action.CALL_EMERGENCY";
    /**
     * Activity Action: Dial a emergency number specified by the data.  This shows a
     * UI with the number being dialed, allowing the user to explicitly
     * initiate the call.
     * <p>Input: If nothing, an empty emergency dialer is started; else {@link #getData}
     * is a tel: URI of an explicit emergency phone number.
     * <p>Output: nothing.
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_DIAL_EMERGENCY = "android.intent.action.DIAL_EMERGENCY";
    /**
     * Activity action: Perform a call to any number (emergency or not)
     * specified by the data.
     * <p>Input: {@link #getData} is URI of a phone number to be dialed or a
     * tel: URI of an explicit phone number.
     * <p>Output: nothing.
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_CALL_PRIVILEGED = "android.intent.action.CALL_PRIVILEGED";

    /**
     * Activity Action: Main entry point for carrier setup apps.
     * <p>Carrier apps that provide an implementation for this action may be invoked to configure
     * carrier service and typically require
     * {@link android.telephony.TelephonyManager#hasCarrierPrivileges() carrier privileges} to
     * fulfill their duties.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_CARRIER_SETUP = "android.intent.action.CARRIER_SETUP";
    /**
     * Activity Action: Send a message to someone specified by the data.
     * <p>Input: {@link #getData} is URI describing the target.
     * <p>Output: nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SENDTO = "android.intent.action.SENDTO";
    /**
     * Activity Action: Deliver some data to someone else.  Who the data is
     * being delivered to is not specified; it is up to the receiver of this
     * action to ask the user where the data should be sent.
     * <p>
     * When launching a SEND intent, you should usually wrap it in a chooser
     * (through {@link #createChooser}), which will give the proper interface
     * for the user to pick how to send your data and allow you to specify
     * a prompt indicating what they are doing.
     * <p>
     * Input: {@link #getType} is the MIME type of the data being sent.
     * get*Extra can have either a {@link #EXTRA_TEXT}
     * or {@link #EXTRA_STREAM} field, containing the data to be sent.  If
     * using EXTRA_TEXT, the MIME type should be "text/plain"; otherwise it
     * should be the MIME type of the data in EXTRA_STREAM.  Use {@literal *}/*
     * if the MIME type is unknown (this will only allow senders that can
     * handle generic data streams).  If using {@link #EXTRA_TEXT}, you can
     * also optionally supply {@link #EXTRA_HTML_TEXT} for clients to retrieve
     * your text with HTML formatting.
     * <p>
     * As of {@link android.os.Build.VERSION_CODES#JELLY_BEAN}, the data
     * being sent can be supplied through {@link #setClipData(ClipData)}.  This
     * allows you to use {@link #FLAG_GRANT_READ_URI_PERMISSION} when sharing
     * content: URIs and other advanced features of {@link ClipData}.  If
     * using this approach, you still must supply the same data through the
     * {@link #EXTRA_TEXT} or {@link #EXTRA_STREAM} fields described below
     * for compatibility with old applications.  If you don't set a ClipData,
     * it will be copied there for you when calling {@link Context#startActivity(Intent)}.
     * <p>
     * Starting from {@link android.os.Build.VERSION_CODES#O}, if
     * {@link #CATEGORY_TYPED_OPENABLE} is passed, then the Uris passed in
     * either {@link #EXTRA_STREAM} or via {@link #setClipData(ClipData)} may
     * be openable only as asset typed files using
     * {@link ContentResolver#openTypedAssetFileDescriptor(Uri, String, Bundle)}.
     * <p>
     * Optional standard extras, which may be interpreted by some recipients as
     * appropriate, are: {@link #EXTRA_EMAIL}, {@link #EXTRA_CC},
     * {@link #EXTRA_BCC}, {@link #EXTRA_SUBJECT}.
     * <p>
     * Output: nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SEND = "android.intent.action.SEND";
    /**
     * Activity Action: Deliver multiple data to someone else.
     * <p>
     * Like {@link #ACTION_SEND}, except the data is multiple.
     * <p>
     * Input: {@link #getType} is the MIME type of the data being sent.
     * get*ArrayListExtra can have either a {@link #EXTRA_TEXT} or {@link
     * #EXTRA_STREAM} field, containing the data to be sent.  If using
     * {@link #EXTRA_TEXT}, you can also optionally supply {@link #EXTRA_HTML_TEXT}
     * for clients to retrieve your text with HTML formatting.
     * <p>
     * Multiple types are supported, and receivers should handle mixed types
     * whenever possible. The right way for the receiver to check them is to
     * use the content resolver on each URI. The intent sender should try to
     * put the most concrete mime type in the intent type, but it can fall
     * back to {@literal <type>/*} or {@literal *}/* as needed.
     * <p>
     * e.g. if you are sending image/jpg and image/jpg, the intent's type can
     * be image/jpg, but if you are sending image/jpg and image/png, then the
     * intent's type should be image/*.
     * <p>
     * As of {@link android.os.Build.VERSION_CODES#JELLY_BEAN}, the data
     * being sent can be supplied through {@link #setClipData(ClipData)}.  This
     * allows you to use {@link #FLAG_GRANT_READ_URI_PERMISSION} when sharing
     * content: URIs and other advanced features of {@link ClipData}.  If
     * using this approach, you still must supply the same data through the
     * {@link #EXTRA_TEXT} or {@link #EXTRA_STREAM} fields described below
     * for compatibility with old applications.  If you don't set a ClipData,
     * it will be copied there for you when calling {@link Context#startActivity(Intent)}.
     * <p>
     * Starting from {@link android.os.Build.VERSION_CODES#O}, if
     * {@link #CATEGORY_TYPED_OPENABLE} is passed, then the Uris passed in
     * either {@link #EXTRA_STREAM} or via {@link #setClipData(ClipData)} may
     * be openable only as asset typed files using
     * {@link ContentResolver#openTypedAssetFileDescriptor(Uri, String, Bundle)}.
     * <p>
     * Optional standard extras, which may be interpreted by some recipients as
     * appropriate, are: {@link #EXTRA_EMAIL}, {@link #EXTRA_CC},
     * {@link #EXTRA_BCC}, {@link #EXTRA_SUBJECT}.
     * <p>
     * Output: nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE";
    /**
     * Activity Action: Handle an incoming phone call.
     * <p>Input: nothing.
     * <p>Output: nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_ANSWER = "android.intent.action.ANSWER";
    /**
     * Activity Action: Insert an empty item into the given container.
     * <p>Input: {@link #getData} is URI of the directory (vnd.android.cursor.dir/*)
     * in which to place the data.
     * <p>Output: URI of the new data that was created.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_INSERT = "android.intent.action.INSERT";
    /**
     * Activity Action: Create a new item in the given container, initializing it
     * from the current contents of the clipboard.
     * <p>Input: {@link #getData} is URI of the directory (vnd.android.cursor.dir/*)
     * in which to place the data.
     * <p>Output: URI of the new data that was created.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_PASTE = "android.intent.action.PASTE";
    /**
     * Activity Action: Delete the given data from its container.
     * <p>Input: {@link #getData} is URI of data to be deleted.
     * <p>Output: nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_DELETE = "android.intent.action.DELETE";
    /**
     * Activity Action: Run the data, whatever that means.
     * <p>Input: ?  (Note: this is currently specific to the test harness.)
     * <p>Output: nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_RUN = "android.intent.action.RUN";
    /**
     * Activity Action: Perform a data synchronization.
     * <p>Input: ?
     * <p>Output: ?
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SYNC = "android.intent.action.SYNC";
    /**
     * Activity Action: Pick an activity given an intent, returning the class
     * selected.
     * <p>Input: get*Extra field {@link #EXTRA_INTENT} is an Intent
     * used with {@link PackageManager#queryIntentActivities} to determine the
     * set of activities from which to pick.
     * <p>Output: Class name of the activity that was selected.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_PICK_ACTIVITY = "android.intent.action.PICK_ACTIVITY";
    /**
     * Activity Action: Perform a search.
     * <p>Input: {@link android.app.SearchManager#QUERY getStringExtra(SearchManager.QUERY)}
     * is the text to search for.  If empty, simply
     * enter your search results Activity with the search UI activated.
     * <p>Output: nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SEARCH = "android.intent.action.SEARCH";
    /**
     * Activity Action: Start the platform-defined tutorial
     * <p>Input: {@link android.app.SearchManager#QUERY getStringExtra(SearchManager.QUERY)}
     * is the text to search for.  If empty, simply
     * enter your search results Activity with the search UI activated.
     * <p>Output: nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SYSTEM_TUTORIAL = "android.intent.action.SYSTEM_TUTORIAL";
    /**
     * Activity Action: Perform a web search.
     * <p>
     * Input: {@link android.app.SearchManager#QUERY
     * getStringExtra(SearchManager.QUERY)} is the text to search for. If it is
     * a url starts with http or https, the site will be opened. If it is plain
     * text, Google search will be applied.
     * <p>
     * Output: nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_WEB_SEARCH = "android.intent.action.WEB_SEARCH";

    /**
     * Activity Action: Perform assist action.
     * <p>
     * Input: {@link #EXTRA_ASSIST_PACKAGE}, {@link #EXTRA_ASSIST_CONTEXT}, can provide
     * additional optional contextual information about where the user was when they
     * requested the assist; {@link #EXTRA_REFERRER} may be set with additional referrer
     * information.
     * Output: nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_ASSIST = "android.intent.action.ASSIST";

    /**
     * Activity Action: Perform voice assist action.
     * <p>
     * Input: {@link #EXTRA_ASSIST_PACKAGE}, {@link #EXTRA_ASSIST_CONTEXT}, can provide
     * additional optional contextual information about where the user was when they
     * requested the voice assist.
     * Output: nothing.
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_VOICE_ASSIST = "android.intent.action.VOICE_ASSIST";

    /**
     * An optional field on {@link #ACTION_ASSIST} containing the name of the current foreground
     * application package at the time the assist was invoked.
     */
    public static final String EXTRA_ASSIST_PACKAGE
            = "android.intent.extra.ASSIST_PACKAGE";

    /**
     * An optional field on {@link #ACTION_ASSIST} containing the uid of the current foreground
     * application package at the time the assist was invoked.
     */
    public static final String EXTRA_ASSIST_UID
            = "android.intent.extra.ASSIST_UID";

    /**
     * An optional field on {@link #ACTION_ASSIST} and containing additional contextual
     * information supplied by the current foreground app at the time of the assist request.
     * This is a {@link Bundle} of additional data.
     */
    public static final String EXTRA_ASSIST_CONTEXT
            = "android.intent.extra.ASSIST_CONTEXT";

    /**
     * An optional field on {@link #ACTION_ASSIST} suggesting that the user will likely use a
     * keyboard as the primary input device for assistance.
     */
    public static final String EXTRA_ASSIST_INPUT_HINT_KEYBOARD =
            "android.intent.extra.ASSIST_INPUT_HINT_KEYBOARD";

    /**
     * An optional field on {@link #ACTION_ASSIST} containing the InputDevice id
     * that was used to invoke the assist.
     */
    public static final String EXTRA_ASSIST_INPUT_DEVICE_ID =
            "android.intent.extra.ASSIST_INPUT_DEVICE_ID";

    /**
     * Activity Action: List all available applications.
     * <p>Input: Nothing.
     * <p>Output: nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_ALL_APPS = "android.intent.action.ALL_APPS";

    /**
     * Activity Action: Action to show the list of all work apps in the launcher. For example,
     * shows the work apps folder or tab.
     *
     * <p>Input: Nothing.
     * <p>Output: nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SHOW_WORK_APPS =
            "android.intent.action.SHOW_WORK_APPS";

    /**
     * Activity Action: Show settings for choosing wallpaper.
     * <p>Input: Nothing.
     * <p>Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SET_WALLPAPER = "android.intent.action.SET_WALLPAPER";

    /**
     * Activity Action: Show activity for reporting a bug.
     * <p>Input: Nothing.
     * <p>Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_BUG_REPORT = "android.intent.action.BUG_REPORT";

    /**
     *  Activity Action: Main entry point for factory tests.  Only used when
     *  the device is booting in factory test node.  The implementing package
     *  must be installed in the system image.
     *  <p>Input: nothing
     *  <p>Output: nothing
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_FACTORY_TEST = "android.intent.action.FACTORY_TEST";

    /**
     * Activity Action: The user pressed the "call" button to go to the dialer
     * or other appropriate UI for placing a call.
     * <p>Input: Nothing.
     * <p>Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_CALL_BUTTON = "android.intent.action.CALL_BUTTON";

    /**
     * Activity Action: Start Voice Command.
     * <p>Input: Nothing.
     * <p>Output: Nothing.
     * <p class="note">
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_VOICE_COMMAND = "android.intent.action.VOICE_COMMAND";

    /**
     * Activity Action: Start action associated with long pressing on the
     * search key.
     * <p>Input: Nothing.
     * <p>Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SEARCH_LONG_PRESS = "android.intent.action.SEARCH_LONG_PRESS";

    /**
     * Activity Action: The user pressed the "Report" button in the crash/ANR dialog.
     * This intent is delivered to the package which installed the application, usually
     * Google Play.
     * <p>Input: No data is specified. The bug report is passed in using
     * an {@link #EXTRA_BUG_REPORT} field.
     * <p>Output: Nothing.
     *
     * @see #EXTRA_BUG_REPORT
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_APP_ERROR = "android.intent.action.APP_ERROR";

    /**
     * An incident or bug report has been taken, and a system app has requested it to be shared,
     * so trigger the confirmation screen.
     *
     * This will be sent directly to the registered receiver with the
     * android.permission.APPROVE_INCIDENT_REPORTS permission.
     * @hide
     */
    @SystemApi
    public static final String ACTION_PENDING_INCIDENT_REPORTS_CHANGED =
            "android.intent.action.PENDING_INCIDENT_REPORTS_CHANGED";

    /**
     * An incident report has been taken, and the user has approved it for sharing.
     * <p>
     * This will be sent directly to the registered receiver, which must have
     * both the DUMP and USAGE_STATS permissions.
     * <p>
     * After receiving this, the application should wait until a suitable time
     * (e.g. network available), get the list of available reports with
     * {@link IncidentManager#getIncidentReportList IncidentManager.getIncidentReportList(String)}
     * and then when the reports have been successfully uploaded, call
     * {@link IncidentManager#deleteIncidentReport IncidentManager.deleteIncidentReport(Uri)}.
     *
     * @hide
     */
    @SystemApi
    public static final String ACTION_INCIDENT_REPORT_READY =
            "android.intent.action.INCIDENT_REPORT_READY";

    /**
     * Activity Action: Show power usage information to the user.
     * <p>Input: Nothing.
     * <p>Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_POWER_USAGE_SUMMARY = "android.intent.action.POWER_USAGE_SUMMARY";

    /**
     * Activity Action: Setup wizard action provided for OTA provisioning to determine if it needs
     * to run.
     * <p>Input: Nothing.
     * <p>Output: Nothing.
     * @deprecated As of {@link android.os.Build.VERSION_CODES#M}, setup wizard can be identified
     * using {@link #ACTION_MAIN} and {@link #CATEGORY_SETUP_WIZARD}
     * @hide
     * @removed
     */
    @Deprecated
    @SystemApi
    public static final String ACTION_DEVICE_INITIALIZATION_WIZARD =
            "android.intent.action.DEVICE_INITIALIZATION_WIZARD";

    /**
     * Activity Action: Setup wizard to launch after a platform update.  This
     * activity should have a string meta-data field associated with it,
     * {@link #METADATA_SETUP_VERSION}, which defines the current version of
     * the platform for setup.  The activity will be launched only if
     * {@link android.provider.Settings.Secure#LAST_SETUP_SHOWN} is not the
     * same value.
     * <p>Input: Nothing.
     * <p>Output: Nothing.
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_UPGRADE_SETUP = "android.intent.action.UPGRADE_SETUP";

    /**
     * Activity Action: Start the Keyboard Shortcuts Helper screen.
     * <p>Input: Nothing.
     * <p>Output: Nothing.
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_SHOW_KEYBOARD_SHORTCUTS =
            "com.android.intent.action.SHOW_KEYBOARD_SHORTCUTS";

    /**
     * Activity Action: Dismiss the Keyboard Shortcuts Helper screen.
     * <p>Input: Nothing.
     * <p>Output: Nothing.
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_DISMISS_KEYBOARD_SHORTCUTS =
            "com.android.intent.action.DISMISS_KEYBOARD_SHORTCUTS";

    /**
     * Activity Action: Show settings for managing network data usage of a
     * specific application. Applications should define an activity that offers
     * options to control data usage.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_MANAGE_NETWORK_USAGE =
            "android.intent.action.MANAGE_NETWORK_USAGE";

    /**
     * Activity Action: Launch application installer.
     * <p>
     * Input: The data must be a content: URI at which the application
     * can be retrieved.  As of {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR1},
     * you can also use "package:<package-name>" to install an application for the
     * current user that is already installed for another user. You can optionally supply
     * {@link #EXTRA_INSTALLER_PACKAGE_NAME}, {@link #EXTRA_NOT_UNKNOWN_SOURCE},
     * {@link #EXTRA_ALLOW_REPLACE}, and {@link #EXTRA_RETURN_RESULT}.
     * <p>
     * Output: If {@link #EXTRA_RETURN_RESULT}, returns whether the install
     * succeeded.
     * <p>
     * <strong>Note:</strong>If your app is targeting API level higher than 25 you
     * need to hold {@link android.Manifest.permission#REQUEST_INSTALL_PACKAGES}
     * in order to launch the application installer.
     * </p>
     *
     * @see #EXTRA_INSTALLER_PACKAGE_NAME
     * @see #EXTRA_NOT_UNKNOWN_SOURCE
     * @see #EXTRA_RETURN_RESULT
     *
     * @deprecated use {@link android.content.pm.PackageInstaller} instead
     */
    @Deprecated
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_INSTALL_PACKAGE = "android.intent.action.INSTALL_PACKAGE";

    /**
     * Activity Action: Activity to handle split installation failures.
     * <p>Splits may be installed dynamically. This happens when an Activity is launched,
     * but the split that contains the application isn't installed. When a split is
     * installed in this manner, the containing package usually doesn't know this is
     * happening. However, if an error occurs during installation, the containing
     * package can define a single activity handling this action to deal with such
     * failures.
     * <p>The activity handling this action must be in the base package.
     * <p>
     * Input: {@link #EXTRA_INTENT} the original intent that started split installation.
     * {@link #EXTRA_SPLIT_NAME} the name of the split that failed to be installed.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_INSTALL_FAILURE = "android.intent.action.INSTALL_FAILURE";

    /**
     * Activity Action: Launch instant application installer.
     * <p class="note">
     * This is a protected intent that can only be sent by the system.
     * </p>
     *
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_INSTALL_INSTANT_APP_PACKAGE
            = "android.intent.action.INSTALL_INSTANT_APP_PACKAGE";

    /**
     * Service Action: Resolve instant application.
     * <p>
     * The system will have a persistent connection to this service.
     * This is a protected intent that can only be sent by the system.
     * </p>
     *
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.SERVICE_ACTION)
    public static final String ACTION_RESOLVE_INSTANT_APP_PACKAGE
            = "android.intent.action.RESOLVE_INSTANT_APP_PACKAGE";

    /**
     * Activity Action: Launch instant app settings.
     *
     * <p class="note">
     * This is a protected intent that can only be sent by the system.
     * </p>
     *
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_INSTANT_APP_RESOLVER_SETTINGS
            = "android.intent.action.INSTANT_APP_RESOLVER_SETTINGS";

    /**
     * Used as a string extra field with {@link #ACTION_INSTALL_PACKAGE} to install a
     * package.  Specifies the installer package name; this package will receive the
     * {@link #ACTION_APP_ERROR} intent.
     */
    public static final String EXTRA_INSTALLER_PACKAGE_NAME
            = "android.intent.extra.INSTALLER_PACKAGE_NAME";

    /**
     * Used as a boolean extra field with {@link #ACTION_INSTALL_PACKAGE} to install a
     * package.  Specifies that the application being installed should not be
     * treated as coming from an unknown source, but as coming from the app
     * invoking the Intent.  For this to work you must start the installer with
     * startActivityForResult().
     */
    public static final String EXTRA_NOT_UNKNOWN_SOURCE
            = "android.intent.extra.NOT_UNKNOWN_SOURCE";

    /**
     * Used as a URI extra field with {@link #ACTION_INSTALL_PACKAGE} and
     * {@link #ACTION_VIEW} to indicate the URI from which the local APK in the Intent
     * data field originated from.
     */
    public static final String EXTRA_ORIGINATING_URI
            = "android.intent.extra.ORIGINATING_URI";

    /**
     * This extra can be used with any Intent used to launch an activity, supplying information
     * about who is launching that activity.  This field contains a {@link android.net.Uri}
     * object, typically an http: or https: URI of the web site that the referral came from;
     * it can also use the {@link #URI_ANDROID_APP_SCHEME android-app:} scheme to identify
     * a native application that it came from.
     *
     * <p>To retrieve this value in a client, use {@link android.app.Activity#getReferrer}
     * instead of directly retrieving the extra.  It is also valid for applications to
     * instead supply {@link #EXTRA_REFERRER_NAME} for cases where they can only create
     * a string, not a Uri; the field here, if supplied, will always take precedence,
     * however.</p>
     *
     * @see #EXTRA_REFERRER_NAME
     */
    public static final String EXTRA_REFERRER
            = "android.intent.extra.REFERRER";

    /**
     * Alternate version of {@link #EXTRA_REFERRER} that supplies the URI as a String rather
     * than a {@link android.net.Uri} object.  Only for use in cases where Uri objects can
     * not be created, in particular when Intent extras are supplied through the
     * {@link #URI_INTENT_SCHEME intent:} or {@link #URI_ANDROID_APP_SCHEME android-app:}
     * schemes.
     *
     * @see #EXTRA_REFERRER
     */
    public static final String EXTRA_REFERRER_NAME
            = "android.intent.extra.REFERRER_NAME";

    /**
     * Used as an int extra field with {@link #ACTION_INSTALL_PACKAGE} and
     * {@link #ACTION_VIEW} to indicate the uid of the package that initiated the install
     * Currently only a system app that hosts the provider authority "downloads" or holds the
     * permission {@link android.Manifest.permission.MANAGE_DOCUMENTS} can use this.
     * @hide
     */
    @SystemApi
    public static final String EXTRA_ORIGINATING_UID
            = "android.intent.extra.ORIGINATING_UID";

    /**
     * Used as a boolean extra field with {@link #ACTION_INSTALL_PACKAGE} to install a
     * package.  Tells the installer UI to skip the confirmation with the user
     * if the .apk is replacing an existing one.
     * @deprecated As of {@link android.os.Build.VERSION_CODES#JELLY_BEAN}, Android
     * will no longer show an interstitial message about updating existing
     * applications so this is no longer needed.
     */
    @Deprecated
    public static final String EXTRA_ALLOW_REPLACE
            = "android.intent.extra.ALLOW_REPLACE";

    /**
     * Used as a boolean extra field with {@link #ACTION_INSTALL_PACKAGE} or
     * {@link #ACTION_UNINSTALL_PACKAGE}.  Specifies that the installer UI should
     * return to the application the result code of the install/uninstall.  The returned result
     * code will be {@link android.app.Activity#RESULT_OK} on success or
     * {@link android.app.Activity#RESULT_FIRST_USER} on failure.
     */
    public static final String EXTRA_RETURN_RESULT
            = "android.intent.extra.RETURN_RESULT";

    /**
     * Package manager install result code.  @hide because result codes are not
     * yet ready to be exposed.
     */
    @SystemApi
    public static final String EXTRA_INSTALL_RESULT = "android.intent.extra.INSTALL_RESULT";

    /**
     * Activity Action: Launch application uninstaller.
     * <p>
     * Input: The data must be a package: URI whose scheme specific part is
     * the package name of the current installed package to be uninstalled.
     * You can optionally supply {@link #EXTRA_RETURN_RESULT}.
     * <p>
     * Output: If {@link #EXTRA_RETURN_RESULT}, returns whether the uninstall
     * succeeded.
     * <p>
     * Requires {@link android.Manifest.permission#REQUEST_DELETE_PACKAGES}
     * since {@link Build.VERSION_CODES#P}.
     *
     * @deprecated Use {@link android.content.pm.PackageInstaller#uninstall(String, IntentSender)}
     *             instead
     */
    @Deprecated
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_UNINSTALL_PACKAGE = "android.intent.action.UNINSTALL_PACKAGE";

    /**
     * Specify whether the package should be uninstalled for all users.
     * @hide because these should not be part of normal application flow.
     */
    @SystemApi
    public static final String EXTRA_UNINSTALL_ALL_USERS
            = "android.intent.extra.UNINSTALL_ALL_USERS";

    /**
     * A string that associates with a metadata entry, indicating the last run version of the
     * platform that was setup.
     *
     * @see #ACTION_UPGRADE_SETUP
     *
     * @hide
     */
    @SystemApi
    public static final String METADATA_SETUP_VERSION = "android.SETUP_VERSION";

    /**
     * Activity action: Launch UI to manage the permissions of an app.
     * <p>
     * Input: {@link #EXTRA_PACKAGE_NAME} specifies the package whose permissions
     * will be managed by the launched UI.
     * </p>
     * <p>
     * Output: Nothing.
     * </p>
     *
     * @see #EXTRA_PACKAGE_NAME
     *
     * @hide
     * @deprecated Use {@link android.provider.Settings#ACTION_APP_PERMISSIONS_SETTINGS}
     * instead.
     */
    @Deprecated
    @SystemApi
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_MANAGE_APP_PERMISSIONS =
            "android.intent.action.MANAGE_APP_PERMISSIONS";

    /**
     * Activity action: Launch UI to manage a specific permission group of an app.
     * <p>
     * Input: {@link #EXTRA_PACKAGE_NAME} specifies the package whose permission
     * will be managed by the launched UI.
     * </p>
     * <p>
     * Input: {@link #EXTRA_PERMISSION_NAME} specifies the (individual) permission
     * whose group should be managed by the launched UI.
     * </p>
     * <p>
     * Input: {@link #EXTRA_PERMISSION_GROUP_NAME} specifies the permission group
     * that should be managed by the launched UI. Do not send both this and EXTRA_PERMISSION_NAME
     * together.
     * </p>
     * <p>
     * <li> {@link #EXTRA_USER} specifies the {@link UserHandle} of the user that owns the app.
     * </p>
     * <p>
     * Output: Nothing.
     * </p>
     *
     * @see #EXTRA_PACKAGE_NAME
     * @see #EXTRA_PERMISSION_NAME
     * @see #EXTRA_PERMISSION_GROUP_NAME
     * @see #EXTRA_USER
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.GRANT_RUNTIME_PERMISSIONS)
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_MANAGE_APP_PERMISSION =
            "android.intent.action.MANAGE_APP_PERMISSION";

    /**
     * Activity action: Launch UI to manage permissions.
     * <p>
     * Input: Nothing.
     * </p>
     * <p>
     * Output: Nothing.
     * </p>
     *
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_MANAGE_PERMISSIONS =
            "android.intent.action.MANAGE_PERMISSIONS";

    /**
     * Activity action: Launch UI to manage auto-revoke state.
     *
     * This is equivalent to Intent#ACTION_APPLICATION_DETAILS_SETTINGS
     *
     * <p>
     * Input: {@link Intent#setData data} should be a {@code package}-scheme {@link Uri} with
     * a package name, whose auto-revoke state will be reviewed (mandatory).
     * E.g. {@code Uri.fromParts("package", packageName, null) }
     * </p>
     * <p>
     * Output: Nothing.
     * </p>
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_AUTO_REVOKE_PERMISSIONS =
            "android.intent.action.AUTO_REVOKE_PERMISSIONS";

    /**
     * Activity action: Launch UI to manage unused apps (hibernated apps).
     *
     * <p>
     * Input: Nothing.
     * </p>
     * <p>
     * Output: Nothing.
     * </p>
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_MANAGE_UNUSED_APPS =
            "android.intent.action.MANAGE_UNUSED_APPS";

    /**
     * Activity action: Launch UI to review permissions for an app.
     * The system uses this intent if permission review for apps not
     * supporting the new runtime permissions model is enabled. In
     * this mode a permission review is required before any of the
     * app components can run.
     * <p>
     * Input: {@link #EXTRA_PACKAGE_NAME} specifies the package whose
     * permissions will be reviewed (mandatory).
     * </p>
     * <p>
     * Input: {@link #EXTRA_INTENT} specifies a pending intent to
     * be fired after the permission review (optional).
     * </p>
     * <p>
     * Input: {@link #EXTRA_REMOTE_CALLBACK} specifies a callback to
     * be invoked after the permission review (optional).
     * </p>
     * <p>
     * Input: {@link #EXTRA_RESULT_NEEDED} specifies whether the intent
     * passed via {@link #EXTRA_INTENT} needs a result (optional).
     * </p>
     * <p>
     * Output: Nothing.
     * </p>
     *
     * @see #EXTRA_PACKAGE_NAME
     * @see #EXTRA_INTENT
     * @see #EXTRA_REMOTE_CALLBACK
     * @see #EXTRA_RESULT_NEEDED
     *
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_REVIEW_PERMISSIONS =
            "android.intent.action.REVIEW_PERMISSIONS";

    /**
     * Activity action: Launch UI to show information about the usage
     * of a given permission group. This action would be handled by apps that
     * want to show details about how and why given permission group is being
     * used.
     * <p>
     * <strong>Important:</strong>You must protect the activity that handles
     * this action with the {@link android.Manifest.permission#START_VIEW_PERMISSION_USAGE
     *  START_VIEW_PERMISSION_USAGE} permission to ensure that only the
     * system can launch this activity. The system will not launch
     * activities that are not properly protected.
     *
     * <p>
     * Input: {@link #EXTRA_PERMISSION_GROUP_NAME} specifies the permission group
     * for which the launched UI would be targeted.
     * </p>
     * <p>
     * Output: Nothing.
     * </p>
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    @RequiresPermission(android.Manifest.permission.START_VIEW_PERMISSION_USAGE)
    public static final String ACTION_VIEW_PERMISSION_USAGE =
            "android.intent.action.VIEW_PERMISSION_USAGE";

    /**
     * Activity action: Launch UI to show information about the usage of a given permission group in
     * a given period. This action would be handled by apps that want to show details about how and
     * why given permission group is being used.
     * <p>
     * <strong>Important:</strong>You must protect the activity that handles this action with the
     * {@link android.Manifest.permission#START_VIEW_PERMISSION_USAGE} permission to ensure that
     * only the system can launch this activity. The system will not launch activities that are not
     * properly protected.
     *
     * <p>
     * Input: {@link #EXTRA_PERMISSION_GROUP_NAME} specifies the permission group for which the
     * launched UI would be targeted.
     * </p>
     * <p>
     * Input: {@link #EXTRA_ATTRIBUTION_TAGS} specifies the attribution tags for the usage entry.
     * </p>
     * <p>
     * Input: {@link #EXTRA_START_TIME} specifies the start time of the period (epoch time in
     * millis). Both start time and end time are needed and start time must be <= end time.
     * </p>
     * <p>
     * Input: {@link #EXTRA_END_TIME} specifies the end time of the period (epoch time in
     * millis). Both start time and end time are needed and start time must be <= end time.
     * </p>
     * <p>
     * Output: Nothing.
     * </p>
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    @RequiresPermission(android.Manifest.permission.START_VIEW_PERMISSION_USAGE)
    public static final String ACTION_VIEW_PERMISSION_USAGE_FOR_PERIOD =
            "android.intent.action.VIEW_PERMISSION_USAGE_FOR_PERIOD";

    /**
     * Activity action: Launch the Safety Center Quick Settings UI.
     *
     * <p>
     * Input: Nothing.
     * </p>
     * <p>
     * Output: Nothing.
     * </p>
     *
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    @RequiresPermission(Manifest.permission.MANAGE_SENSOR_PRIVACY)
    public static final String ACTION_VIEW_SAFETY_CENTER_QS =
            "android.intent.action.VIEW_SAFETY_CENTER_QS";

    /**
     * Activity action: Launch UI to manage a default app.
     * <p>
     * Input: {@link #EXTRA_ROLE_NAME} specifies the role of the default app which will be managed
     * by the launched UI.
     * </p>
     * <p>
     * Output: Nothing.
     * </p>
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_ROLE_HOLDERS)
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    @SystemApi
    public static final String ACTION_MANAGE_DEFAULT_APP =
            "android.intent.action.MANAGE_DEFAULT_APP";

    /**
     * Intent extra: A role name.
     * <p>
     * Type: String
     * </p>
     *
     * @see android.app.role.RoleManager
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_ROLE_NAME = "android.intent.extra.ROLE_NAME";

    /**
     * Activity action: Launch UI to manage special app accesses.
     * <p>
     * Input: Nothing.
     * </p>
     * <p>
     * Output: Nothing.
     * </p>
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_ROLE_HOLDERS)
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    @SystemApi
    public static final String ACTION_MANAGE_SPECIAL_APP_ACCESSES =
            "android.intent.action.MANAGE_SPECIAL_APP_ACCESSES";

    /**
     * Intent extra: A callback for reporting remote result as a bundle.
     * <p>
     * Type: IRemoteCallback
     * </p>
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_REMOTE_CALLBACK = "android.intent.extra.REMOTE_CALLBACK";

    /**
     * Intent extra: An app package name.
     * <p>
     * Type: String
     * </p>
     *
     */
    public static final String EXTRA_PACKAGE_NAME = "android.intent.extra.PACKAGE_NAME";

    /**
     * Intent extra: A {@link android.os.LocaleList}
     * <p>
     * Type: LocaleList
     * </p>
     */
    public static final String EXTRA_LOCALE_LIST = "android.intent.extra.LOCALE_LIST";

    /**
     * Intent extra: A {@link Bundle} of extras for a package being suspended. Will be sent as an
     * extra with {@link #ACTION_MY_PACKAGE_SUSPENDED}.
     *
     * <p>The contents of this {@link Bundle} are a contract between the suspended app and the
     * suspending app, i.e. any app with the permission {@code android.permission.SUSPEND_APPS}.
     * This is meant to enable the suspended app to better handle the state of being suspended.
     *
     * @see #ACTION_MY_PACKAGE_SUSPENDED
     * @see #ACTION_MY_PACKAGE_UNSUSPENDED
     * @see PackageManager#isPackageSuspended()
     * @see PackageManager#getSuspendedPackageAppExtras()
     */
    public static final String EXTRA_SUSPENDED_PACKAGE_EXTRAS = "android.intent.extra.SUSPENDED_PACKAGE_EXTRAS";

    /**
     * Intent extra: An app split name.
     * <p>
     * Type: String
     * </p>
     */
    public static final String EXTRA_SPLIT_NAME = "android.intent.extra.SPLIT_NAME";

    /**
     * Intent extra: A {@link ComponentName} value.
     * <p>
     * Type: String
     * </p>
     */
    public static final String EXTRA_COMPONENT_NAME = "android.intent.extra.COMPONENT_NAME";

    /**
     * Intent extra: An extra for specifying whether a result is needed.
     * <p>
     * Type: boolean
     * </p>
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_RESULT_NEEDED = "android.intent.extra.RESULT_NEEDED";

    /**
     * Intent extra: ID of the shortcut used to send the share intent. Will be sent with
     * {@link #ACTION_SEND}.
     *
     * @see ShortcutInfo#getId()
     *
     * <p>
     * Type: String
     * </p>
     */
    public static final String EXTRA_SHORTCUT_ID = "android.intent.extra.shortcut.ID";

    /**
     * Activity action: Launch UI to manage which apps have a given permission.
     * <p>
     * Input: {@link #EXTRA_PERMISSION_NAME} or {@link #EXTRA_PERMISSION_GROUP_NAME} specifies the
     * permission group which will be managed by the launched UI.
     * </p>
     * <p>
     * Output: Nothing.
     * </p>
     *
     * @see #EXTRA_PERMISSION_NAME
     * @see #EXTRA_PERMISSION_GROUP_NAME
     *
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_MANAGE_PERMISSION_APPS =
            "android.intent.action.MANAGE_PERMISSION_APPS";

    /**
     * Intent extra: The name of a permission.
     * <p>
     * Type: String
     * </p>
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_PERMISSION_NAME = "android.intent.extra.PERMISSION_NAME";

    /**
     * Intent extra: The name of a permission group.
     * <p>
     * Type: String
     * </p>
     */
    public static final String EXTRA_PERMISSION_GROUP_NAME =
            "android.intent.extra.PERMISSION_GROUP_NAME";

    /**
     * Intent extra: The number of milliseconds.
     * <p>
     * Type: long
     * </p>
     */
    public static final String EXTRA_DURATION_MILLIS =
            "android.intent.extra.DURATION_MILLIS";

    /**
     * Activity action: Launch UI to review app uses of permissions.
     * <p>
     * Input: {@link #EXTRA_PERMISSION_NAME} specifies the permission name
     * that will be displayed by the launched UI.  Do not pass both this and
     * {@link #EXTRA_PERMISSION_GROUP_NAME} .
     * </p>
     * <p>
     * Input: {@link #EXTRA_PERMISSION_GROUP_NAME} specifies the permission group name
     * that will be displayed by the launched UI.  Do not pass both this and
     * {@link #EXTRA_PERMISSION_NAME}.
     * </p>
     * <p>
     * Input: {@link #EXTRA_DURATION_MILLIS} specifies the minimum number of milliseconds of recent
     * activity to show (optional).  Must be non-negative.
     * </p>
     * <p>
     * Output: Nothing.
     * </p>
     * <p class="note">
     * This requires {@link android.Manifest.permission#GRANT_RUNTIME_PERMISSIONS} permission.
     * </p>
     *
     * @see #EXTRA_PERMISSION_NAME
     * @see #EXTRA_PERMISSION_GROUP_NAME
     * @see #EXTRA_DURATION_MILLIS
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.GRANT_RUNTIME_PERMISSIONS)
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_REVIEW_PERMISSION_USAGE =
            "android.intent.action.REVIEW_PERMISSION_USAGE";

    /**
     * Activity action: Launch UI to review the timeline history of permissions.
     * <p>
     * Input: {@link #EXTRA_PERMISSION_GROUP_NAME} specifies the permission group name
     * that will be displayed by the launched UI.
     * </p>
     * <p>
     * Output: Nothing.
     * </p>
     * <p class="note">
     * This requires {@link android.Manifest.permission#GRANT_RUNTIME_PERMISSIONS} permission.
     * </p>
     *
     * @see #EXTRA_PERMISSION_GROUP_NAME
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.GRANT_RUNTIME_PERMISSIONS)
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_REVIEW_PERMISSION_HISTORY =
            "android.intent.action.REVIEW_PERMISSION_HISTORY";

    /**
     * Activity action: Launch UI to review ongoing app uses of permissions.
     * <p>
     * Input: {@link #EXTRA_DURATION_MILLIS} specifies the minimum number of milliseconds of recent
     * activity to show (optional).  Must be non-negative.
     * </p>
     * <p>
     * Output: Nothing.
     * </p>
     * <p class="note">
     * This requires {@link android.Manifest.permission#GRANT_RUNTIME_PERMISSIONS} permission.
     * </p>
     *
     * @see #EXTRA_DURATION_MILLIS
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.GRANT_RUNTIME_PERMISSIONS)
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_REVIEW_ONGOING_PERMISSION_USAGE =
            "android.intent.action.REVIEW_ONGOING_PERMISSION_USAGE";

    /**
     * Activity action: Launch UI to review running accessibility services.
     * <p>
     * Input: Nothing.
     * </p>
     * <p>
     * Output: Nothing.
     * </p>
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.REVIEW_ACCESSIBILITY_SERVICES)
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_REVIEW_ACCESSIBILITY_SERVICES =
            "android.intent.action.REVIEW_ACCESSIBILITY_SERVICES";

    /**
     * Activity action: Launch UI to manage the usage of a given permission group.
     * This action would be handled by apps that want to show controls about the features
     * which use the permission group.
     *
     * <p>
     * Input: {@link #EXTRA_PERMISSION_GROUP_NAME} specifies the permission group for
     * which the launched UI would be targeted.
     * Input: {@link #EXTRA_ATTRIBUTION_TAGS} specifies the attribution tags for the usage entry.
     * Input: {@link #EXTRA_START_TIME} specifies the start time of the period (epoch time in
     * millis). If both start time and end time are present, start time must be <= end time.
     * Input: {@link #EXTRA_END_TIME} specifies the end time of the period (epoch time in
     * millis). If the end time is empty, that implies that the permission usage is still in use.
     * If both start time and end time are present, start time must be <= end time.
     * Input: {@link #EXTRA_SHOWING_ATTRIBUTION} specifies whether the subattribution was shown
     * in the UI.
     * </p>
     * <p>
     * Output: Nothing.
     * </p>
     * <p class="note">
     * You must protect the activity that handles this action with the
     * {@link android.Manifest.permission#START_VIEW_PERMISSION_USAGE} permission to ensure that
     * only the system can launch this activity. The system will not launch activities
     * that are not properly protected.
     * </p>
     *
     * @see #EXTRA_PERMISSION_GROUP_NAME
     * @see #EXTRA_ATTRIBUTION_TAGS
     * @see #EXTRA_START_TIME
     * @see #EXTRA_END_TIME
     * @see #EXTRA_SHOWING_ATTRIBUTION
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.START_VIEW_PERMISSION_USAGE)
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_MANAGE_PERMISSION_USAGE =
            "android.intent.action.MANAGE_PERMISSION_USAGE";

    /**
     * Activity action: Launch UI to view the app's feature's information.
     *
     * <p>
     * Output: Nothing.
     * </p>
     * <p class="note">
     * You must protect the activity that handles this action with the
     * {@link android.Manifest.permission#START_VIEW_APP_FEATURES} permission to ensure that
     * only the system can launch this activity. The system will not launch activities
     * that are not properly protected.
     *
     * An optional <meta-data> tag in the activity's manifest with
     * android:name=app_features_preference_summary and android:resource=@string/<string name> will
     * be used to add a summary line for the "All Services" preference in settings.
     * </p>
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.START_VIEW_APP_FEATURES)
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_VIEW_APP_FEATURES =
            "android.intent.action.VIEW_APP_FEATURES";

    /**
     * Activity action: Launch UI to open the Safety Center, which highlights the user's security
     * and privacy status.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SAFETY_CENTER =
            "android.intent.action.SAFETY_CENTER";

    /**
     * Activity action: Launch the UI to view recent updates that installed apps have made to their
     * data sharing policy in their safety labels.
     *
     * <p>
     * Input: Nothing.
     * </p>
     * <p>
     * Output: Nothing.
     * </p>
     *
     * <p class="note">
     * This intent action requires the {@link android.Manifest.permission#GRANT_RUNTIME_PERMISSIONS}
     * permission.
     * </p>
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.GRANT_RUNTIME_PERMISSIONS)
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_REVIEW_APP_DATA_SHARING_UPDATES =
            "android.intent.action.REVIEW_APP_DATA_SHARING_UPDATES";

    // ---------------------------------------------------------------------
    // ---------------------------------------------------------------------
    // Standard intent broadcast actions (see action variable).

    /**
     * Broadcast Action: Sent when the device goes to sleep and becomes non-interactive.
     * <p>
     * For historical reasons, the name of this broadcast action refers to the power
     * state of the screen but it is actually sent in response to changes in the
     * overall interactive state of the device.
     * </p><p>
     * This broadcast is sent when the device becomes non-interactive which may have
     * nothing to do with the screen turning off.  To determine the
     * actual state of the screen, use {@link android.view.Display#getState}.
     * </p><p>
     * See {@link android.os.PowerManager#isInteractive} for details.
     * </p>
     * You <em>cannot</em> receive this through components declared in
     * manifests, only by explicitly registering for it with
     * {@link Context#registerReceiver(BroadcastReceiver, IntentFilter)
     * Context.registerReceiver()}.
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_SCREEN_OFF = "android.intent.action.SCREEN_OFF";

    /**
     * Broadcast Action: Sent when the device wakes up and becomes interactive.
     * <p>
     * For historical reasons, the name of this broadcast action refers to the power
     * state of the screen but it is actually sent in response to changes in the
     * overall interactive state of the device.
     * </p><p>
     * This broadcast is sent when the device becomes interactive which may have
     * nothing to do with the screen turning on.  To determine the
     * actual state of the screen, use {@link android.view.Display#getState}.
     * </p><p>
     * See {@link android.os.PowerManager#isInteractive} for details.
     * </p>
     * You <em>cannot</em> receive this through components declared in
     * manifests, only by explicitly registering for it with
     * {@link Context#registerReceiver(BroadcastReceiver, IntentFilter)
     * Context.registerReceiver()}.
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_SCREEN_ON = "android.intent.action.SCREEN_ON";

    /**
     * Broadcast Action: Sent after the system stops dreaming.
     *
     * <p class="note">This is a protected intent that can only be sent by the system.
     * It is only sent to registered receivers.</p>
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_DREAMING_STOPPED = "android.intent.action.DREAMING_STOPPED";

    /**
     * Broadcast Action: Sent after the system starts dreaming.
     *
     * <p class="note">This is a protected intent that can only be sent by the system.
     * It is only sent to registered receivers.</p>
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_DREAMING_STARTED = "android.intent.action.DREAMING_STARTED";

    /**
     * Broadcast Action: Sent when the user is present after device wakes up (e.g when the
     * keyguard is gone).
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_USER_PRESENT = "android.intent.action.USER_PRESENT";

    /**
     * Broadcast Action: The current time has changed.  Sent every
     * minute.  You <em>cannot</em> receive this through components declared
     * in manifests, only by explicitly registering for it with
     * {@link Context#registerReceiver(BroadcastReceiver, IntentFilter)
     * Context.registerReceiver()}.
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_TIME_TICK = "android.intent.action.TIME_TICK";
    /**
     * Broadcast Action: The time was set.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_TIME_CHANGED = "android.intent.action.TIME_SET";
    /**
     * Broadcast Action: The date has changed.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_DATE_CHANGED = "android.intent.action.DATE_CHANGED";
    /**
     * Broadcast Action: The timezone has changed. The intent will have the following extra values:</p>
     * <ul>
     *   <li>{@link #EXTRA_TIMEZONE} - The java.util.TimeZone.getID() value identifying the new
     *   time zone.</li>
     * </ul>
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_TIMEZONE_CHANGED = "android.intent.action.TIMEZONE_CHANGED";
    /**
     * Alarm Changed Action: This is broadcast when the AlarmClock
     * application's alarm is set or unset.  It is used by the
     * AlarmClock application and the StatusBar service.
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final String ACTION_ALARM_CHANGED = "android.intent.action.ALARM_CHANGED";

    /**
     * Broadcast Action: This is broadcast once, after the user has finished
     * booting, but while still in the "locked" state. It can be used to perform
     * application-specific initialization, such as installing alarms. You must
     * hold the {@link android.Manifest.permission#RECEIVE_BOOT_COMPLETED}
     * permission in order to receive this broadcast.
     * <p>
     * This broadcast is sent immediately at boot by all devices (regardless of
     * direct boot support) running {@link android.os.Build.VERSION_CODES#N} or
     * higher. Upon receipt of this broadcast, the user is still locked and only
     * device-protected storage can be accessed safely. If you want to access
     * credential-protected storage, you need to wait for the user to be
     * unlocked (typically by entering their lock pattern or PIN for the first
     * time), after which the {@link #ACTION_USER_UNLOCKED} and
     * {@link #ACTION_BOOT_COMPLETED} broadcasts are sent.
     * <p>
     * To receive this broadcast, your receiver component must be marked as
     * being {@link ComponentInfo#directBootAware}.
     * <p class="note">
     * This is a protected intent that can only be sent by the system.
     *
     * @see Context#createDeviceProtectedStorageContext()
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_LOCKED_BOOT_COMPLETED = "android.intent.action.LOCKED_BOOT_COMPLETED";

    /**
     * Broadcast Action: This is broadcast once, after the user has finished
     * booting. It can be used to perform application-specific initialization,
     * such as installing alarms. You must hold the
     * {@link android.Manifest.permission#RECEIVE_BOOT_COMPLETED} permission in
     * order to receive this broadcast.
     * <p>
     * This broadcast is sent at boot by all devices (both with and without
     * direct boot support). Upon receipt of this broadcast, the user is
     * unlocked and both device-protected and credential-protected storage can
     * accessed safely.
     * <p>
     * If you need to run while the user is still locked (before they've entered
     * their lock pattern or PIN for the first time), you can listen for the
     * {@link #ACTION_LOCKED_BOOT_COMPLETED} broadcast.
     * <p class="note">
     * This is a protected intent that can only be sent by the system.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @BroadcastBehavior(includeBackground = true)
    public static final String ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED";

    /**
     * Broadcast Action: This is broadcast when a user action should request a
     * temporary system dialog to dismiss.  Some examples of temporary system
     * dialogs are the notification window-shade and the recent tasks dialog.
     *
     * @deprecated This intent is deprecated for third-party applications starting from Android
     *     {@link Build.VERSION_CODES#S} for security reasons. Unauthorized usage by applications
     *     will result in the broadcast intent being dropped for apps targeting API level less than
     *     {@link Build.VERSION_CODES#S} and in a {@link SecurityException} for apps targeting SDK
     *     level {@link Build.VERSION_CODES#S} or higher. Instrumentation initiated from the shell
     *     (eg. tests) is still able to use the intent. The platform will automatically collapse
     *     the proper system dialogs in the proper use-cases. For all others, the user is the one in
     *     control of closing dialogs.
     *
     * @see AccessibilityService#GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @RequiresPermission(android.Manifest.permission.BROADCAST_CLOSE_SYSTEM_DIALOGS)
    @Deprecated
    public static final String ACTION_CLOSE_SYSTEM_DIALOGS = "android.intent.action.CLOSE_SYSTEM_DIALOGS";
    /**
     * Broadcast Action: Trigger the download and eventual installation
     * of a package.
     * <p>Input: {@link #getData} is the URI of the package file to download.
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     *
     * @deprecated This constant has never been used.
     */
    @Deprecated
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PACKAGE_INSTALL = "android.intent.action.PACKAGE_INSTALL";
    /**
     * Broadcast Action: A new application package has been installed on the
     * device. The data contains the name of the package.  Note that the
     * newly installed package does <em>not</em> receive this broadcast.
     * <p>May include the following extras:
     * <ul>
     * <li> {@link #EXTRA_UID} containing the integer uid assigned to the new package.
     * <li> {@link #EXTRA_REPLACING} is set to true if this is following
     * an {@link #ACTION_PACKAGE_REMOVED} broadcast for the same package.
     * </ul>
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PACKAGE_ADDED = "android.intent.action.PACKAGE_ADDED";
    /**
     * Broadcast Action: A new version of an application package has been
     * installed, replacing an existing version that was previously installed.
     * The data contains the name of the package.
     * <p>May include the following extras:
     * <ul>
     * <li> {@link #EXTRA_UID} containing the integer uid assigned to the new package.
     * </ul>
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PACKAGE_REPLACED = "android.intent.action.PACKAGE_REPLACED";
    /**
     * Broadcast Action: A new version of your application has been installed
     * over an existing one.  This is only sent to the application that was
     * replaced.  It does not contain any additional data; to receive it, just
     * use an intent filter for this action.
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_MY_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED";
    /**
     * Broadcast Action: An existing application package has been removed from
     * the device.  The data contains the name of the package.  The package
     * that is being removed does <em>not</em> receive this Intent.
     * <ul>
     * <li> {@link #EXTRA_UID} containing the integer uid previously assigned
     * to the package.
     * <li> {@link #EXTRA_DATA_REMOVED} is set to true if the entire
     * application -- data and code -- is being removed.
     * <li> {@link #EXTRA_REPLACING} is set to true if this will be followed
     * by an {@link #ACTION_PACKAGE_ADDED} broadcast for the same package.
     * <li> {@link #EXTRA_USER_INITIATED} containing boolean field to signal that the application
     * was removed with the user-initiated action.
     * </ul>
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PACKAGE_REMOVED = "android.intent.action.PACKAGE_REMOVED";
    /**
     * Broadcast Action: An existing application package has been removed from
     * the device. The data contains the name of the package and the visibility
     * allow list. The package that is being removed does <em>not</em> receive
     * this Intent.
     * <ul>
     * <li> {@link #EXTRA_UID} containing the integer uid previously assigned
     * to the package.
     * <li> {@link #EXTRA_DATA_REMOVED} is set to true if the entire
     * application -- data and code -- is being removed.
     * <li> {@link #EXTRA_REPLACING} is set to true if this will be followed
     * by an {@link #ACTION_PACKAGE_ADDED} broadcast for the same package.
     * <li> {@link #EXTRA_USER_INITIATED} containing boolean field to signal
     * that the application was removed with the user-initiated action.
     * <li> {@link #EXTRA_VISIBILITY_ALLOW_LIST} containing an int array to
     * indicate the visibility allow list.
     * </ul>
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     *
     * @hide This broadcast is used internally by the system.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PACKAGE_REMOVED_INTERNAL =
            "android.intent.action.PACKAGE_REMOVED_INTERNAL";
    /**
     * Broadcast Action: An existing application package has been completely
     * removed from the device.  The data contains the name of the package.
     * This is like {@link #ACTION_PACKAGE_REMOVED}, but only set when
     * {@link #EXTRA_DATA_REMOVED} is true and
     * {@link #EXTRA_REPLACING} is false of that broadcast.
     *
     * <ul>
     * <li> {@link #EXTRA_UID} containing the integer uid previously assigned
     * to the package.
     * </ul>
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PACKAGE_FULLY_REMOVED
            = "android.intent.action.PACKAGE_FULLY_REMOVED";
    /**
     * Broadcast Action: An existing application package has been changed (for
     * example, a component has been enabled or disabled).  The data contains
     * the name of the package.
     * <ul>
     * <li> {@link #EXTRA_UID} containing the integer uid assigned to the package.
     * <li> {@link #EXTRA_CHANGED_COMPONENT_NAME_LIST} containing the class name
     * of the changed components (or the package name itself).
     * <li> {@link #EXTRA_DONT_KILL_APP} containing boolean field to override the
     * default action of restarting the application.
     * </ul>
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PACKAGE_CHANGED = "android.intent.action.PACKAGE_CHANGED";

    /**
     * Broadcast Action: An application package that was previously in the stopped state has been
     * started and is no longer considered stopped.
     * <ul>
     * <li> {@link #EXTRA_UID} containing the integer uid assigned to the package.
     * <li> {@link #EXTRA_TIME} containing the {@link SystemClock#elapsedRealtime()
     *          elapsed realtime} of when the package was unstopped.
     * </ul>
     *
     * <p class="note">This is a protected intent that can only be sent by the system.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @FlaggedApi(android.content.pm.Flags.FLAG_STAY_STOPPED)
    public static final String ACTION_PACKAGE_UNSTOPPED = "android.intent.action.PACKAGE_UNSTOPPED";

    /**
     * Broadcast Action: Sent to the system rollback manager when a package
     * needs to have rollback enabled.
     * <p class="note">
     * This is a protected intent that can only be sent by the system.
     * </p>
     *
     * @hide This broadcast is used internally by the system.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PACKAGE_ENABLE_ROLLBACK =
            "android.intent.action.PACKAGE_ENABLE_ROLLBACK";
    /**
     * Broadcast Action: Sent to the system rollback manager when the rollback for a certain
     * package needs to be cancelled.
     *
     * <p class="note">This intent is sent by PackageManagerService to notify RollbackManager
     * that enabling a specific rollback has timed out.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_CANCEL_ENABLE_ROLLBACK =
            "android.intent.action.CANCEL_ENABLE_ROLLBACK";
    /**
     * Broadcast Action: A rollback has been committed.
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system. The receiver must hold MANAGE_ROLLBACK permission.
     *
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_ROLLBACK_COMMITTED =
            "android.intent.action.ROLLBACK_COMMITTED";
    /**
     * @hide
     * Broadcast Action: Ask system services if there is any reason to
     * restart the given package.  The data contains the name of the
     * package.
     * <ul>
     * <li> {@link #EXTRA_UID} containing the integer uid assigned to the package.
     * <li> {@link #EXTRA_PACKAGES} String array of all packages to check.
     * </ul>
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
    @SystemApi
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_QUERY_PACKAGE_RESTART = "android.intent.action.QUERY_PACKAGE_RESTART";
    /**
     * Broadcast Action: The user has restarted a package, and all of its
     * processes have been killed.  All runtime state
     * associated with it (processes, alarms, notifications, etc) should
     * be removed.  Note that the restarted package does <em>not</em>
     * receive this broadcast.
     * The data contains the name of the package.
     * <ul>
     * <li> {@link #EXTRA_UID} containing the integer uid assigned to the package.
     * </ul>
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     * <p>
     * Starting in {@link Build.VERSION_CODES#VANILLA_ICE_CREAM Android V}, an extra timestamp
     * {@link #EXTRA_TIME} is included with this broadcast to indicate the exact time the package
     * was restarted, in {@link SystemClock#elapsedRealtime() elapsed realtime}.
     * </p>
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PACKAGE_RESTARTED = "android.intent.action.PACKAGE_RESTARTED";

    /**
     * Broadcast Action: The user has cleared the data of a package.  This should
     * be preceded by {@link #ACTION_PACKAGE_RESTARTED}, after which all of
     * its persistent data is erased and this broadcast sent.
     * Note that the cleared package does <em>not</em>
     * receive this broadcast. The data contains the name of the package.
     * <ul>
     * <li> {@link #EXTRA_UID} containing the integer uid assigned to the package. If the
     *      package whose data was cleared is an uninstalled instant app, then the UID
     *      will be -1. The platform keeps some meta-data associated with instant apps
     *      after they are uninstalled.
     * <li> {@link #EXTRA_PACKAGE_NAME} containing the package name only if the cleared
     *      data was for an instant app.
     * </ul>
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PACKAGE_DATA_CLEARED = "android.intent.action.PACKAGE_DATA_CLEARED";
    /**
     * Broadcast Action: Packages have been suspended.
     * <p>Includes the following extras:
     * <ul>
     * <li> {@link #EXTRA_CHANGED_PACKAGE_LIST} is the set of packages which have been suspended
     * <li> {@link #EXTRA_CHANGED_UID_LIST} is the set of uids which have been suspended
     * </ul>
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system. It is only sent to registered receivers.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PACKAGES_SUSPENDED = "android.intent.action.PACKAGES_SUSPENDED";
    /**
     * Broadcast Action: Packages have been unsuspended.
     * <p>Includes the following extras:
     * <ul>
     * <li> {@link #EXTRA_CHANGED_PACKAGE_LIST} is the set of packages which have been unsuspended
     * <li> {@link #EXTRA_CHANGED_UID_LIST} is the set of uids which have been unsuspended
     * </ul>
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system. It is only sent to registered receivers.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PACKAGES_UNSUSPENDED = "android.intent.action.PACKAGES_UNSUSPENDED";
    /**
     * Broadcast Action: One of the suspend conditions have been modified for the packages.
     * <p>Includes the following extras:
     * <ul>
     * <li> {@link #EXTRA_CHANGED_PACKAGE_LIST} is the set of packages which have been modified
     * <li> {@link #EXTRA_CHANGED_UID_LIST} is the set of uids which have been modified
     * </ul>
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system. It is only sent to registered receivers.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PACKAGES_SUSPENSION_CHANGED =
            "android.intent.action.PACKAGES_SUSPENSION_CHANGED";

    /**
     * Broadcast Action: Distracting packages have been changed.
     * <p>Includes the following extras:
     * <ul>
     * <li> {@link #EXTRA_CHANGED_PACKAGE_LIST} is the set of packages which have been changed.
     * <li> {@link #EXTRA_CHANGED_UID_LIST} is the set of uids which have been changed.
     * <li> {@link #EXTRA_DISTRACTION_RESTRICTIONS} the new restrictions set on these packages.
     * </ul>
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system. It is only sent to registered receivers.
     *
     * @see PackageManager#setDistractingPackageRestrictions(String[], int)
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_DISTRACTING_PACKAGES_CHANGED =
            "android.intent.action.DISTRACTING_PACKAGES_CHANGED";

    /**
     * Broadcast Action: Sent to a package that has been suspended by the system. This is sent
     * whenever a package is put into a suspended state or any of its app extras change while in the
     * suspended state.
     * <p> Optionally includes the following extras:
     * <ul>
     *     <li> {@link #EXTRA_SUSPENDED_PACKAGE_EXTRAS} which is a {@link Bundle} which will contain
     *     useful information for the app being suspended.
     * </ul>
     * <p class="note">This is a protected intent that can only be sent
     * by the system. <em>This will be delivered to {@link BroadcastReceiver} components declared in
     * the manifest.</em>
     *
     * @see #ACTION_MY_PACKAGE_UNSUSPENDED
     * @see #EXTRA_SUSPENDED_PACKAGE_EXTRAS
     * @see PackageManager#isPackageSuspended()
     * @see PackageManager#getSuspendedPackageAppExtras()
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_MY_PACKAGE_SUSPENDED = "android.intent.action.MY_PACKAGE_SUSPENDED";

    /**
     * Activity Action: Started to show more details about why an application was suspended.
     *
     * <p>Whenever the system detects an activity launch for a suspended app, this action can
     * be used to show more details about the reason for suspension.
     *
     * <p>Apps holding {@link android.Manifest.permission#SUSPEND_APPS} must declare an activity
     * handling this intent and protect it with
     * {@link android.Manifest.permission#SEND_SHOW_SUSPENDED_APP_DETAILS}.
     *
     * <p>Includes an extra {@link #EXTRA_PACKAGE_NAME} which is the name of the suspended package.
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     *
     * @see PackageManager#setPackagesSuspended(String[], boolean, PersistableBundle,
     * PersistableBundle, String)
     * @see PackageManager#isPackageSuspended()
     * @see #ACTION_PACKAGES_SUSPENDED
     *
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SHOW_SUSPENDED_APP_DETAILS =
            "android.intent.action.SHOW_SUSPENDED_APP_DETAILS";

    /**
     * Broadcast Action: Sent to indicate that the user unsuspended a package.
     *
     * <p>This can happen when the user taps on the neutral button of the
     * {@linkplain SuspendDialogInfo suspend-dialog} which was created by using
     * {@link SuspendDialogInfo#BUTTON_ACTION_UNSUSPEND}. This broadcast is only sent to the
     * suspending app that originally specified this dialog while calling
     * {@link PackageManager#setPackagesSuspended(String[], boolean, PersistableBundle,
     * PersistableBundle, SuspendDialogInfo)}.
     *
     * <p>Includes an extra {@link #EXTRA_PACKAGE_NAME} which is the name of the package that just
     * got unsuspended.
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system. <em>This will be delivered to {@link BroadcastReceiver} components declared in
     * the manifest.</em>
     *
     * @see PackageManager#setPackagesSuspended(String[], boolean, PersistableBundle,
     * PersistableBundle, SuspendDialogInfo)
     * @see PackageManager#isPackageSuspended()
     * @see SuspendDialogInfo#BUTTON_ACTION_MORE_DETAILS
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PACKAGE_UNSUSPENDED_MANUALLY =
            "android.intent.action.PACKAGE_UNSUSPENDED_MANUALLY";

    /**
     * Broadcast Action: Sent to a package that has been unsuspended.
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system. <em>This will be delivered to {@link BroadcastReceiver} components declared in
     * the manifest.</em>
     *
     * @see #ACTION_MY_PACKAGE_SUSPENDED
     * @see #EXTRA_SUSPENDED_PACKAGE_EXTRAS
     * @see PackageManager#isPackageSuspended()
     * @see PackageManager#getSuspendedPackageAppExtras()
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_MY_PACKAGE_UNSUSPENDED = "android.intent.action.MY_PACKAGE_UNSUSPENDED";

    /**
     * Broadcast Action: A uid has been removed from the system.  The uid
     * number is stored in the extra data under {@link #EXTRA_UID}.
     *
     * In certain instances, {@link #EXTRA_REPLACING} is set to true if the UID is not being
     * fully removed.
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_UID_REMOVED = "android.intent.action.UID_REMOVED";

    /**
     * Broadcast Action: Sent to the installer package of an application when
     * that application is first launched (that is the first time it is moved
     * out of the stopped state).  The data contains the name of the package.
     *
     * <p>When the application is first launched, the application itself doesn't receive this
     * broadcast.</p>
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PACKAGE_FIRST_LAUNCH = "android.intent.action.PACKAGE_FIRST_LAUNCH";

    /**
     * Broadcast Action: Sent to the system package verifier when a package
     * needs to be verified. The data contains the package URI.
     * <p class="note">
     * This is a protected intent that can only be sent by the system.
     * </p>
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PACKAGE_NEEDS_VERIFICATION = "android.intent.action.PACKAGE_NEEDS_VERIFICATION";

    /**
     * Broadcast Action: Sent to the system package verifier when a package is
     * verified. The data contains the package URI.
     * <p class="note">
     * This is a protected intent that can only be sent by the system.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PACKAGE_VERIFIED = "android.intent.action.PACKAGE_VERIFIED";

    /**
     * Broadcast Action: Sent to the system intent filter verifier when an
     * intent filter needs to be verified. The data contains the filter data
     * hosts to be verified against.
     * <p class="note">
     * This is a protected intent that can only be sent by the system.
     * </p>
     *
     * @hide
     * @deprecated Superseded by domain verification APIs. See {@link DomainVerificationManager}.
     */
    @Deprecated
    @SystemApi
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_INTENT_FILTER_NEEDS_VERIFICATION =
            "android.intent.action.INTENT_FILTER_NEEDS_VERIFICATION";


    /**
     * Broadcast Action: Sent to the system domain verification agent when an app's domains need
     * to be verified. The data contains the domains hosts to be verified against.
     * <p class="note">
     * This is a protected intent that can only be sent by the system.
     * </p>
     *
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_DOMAINS_NEED_VERIFICATION =
            "android.intent.action.DOMAINS_NEED_VERIFICATION";

    /**
     * Broadcast Action: Resources for a set of packages (which were
     * previously unavailable) are currently
     * available since the media on which they exist is available.
     * The extra data {@link #EXTRA_CHANGED_PACKAGE_LIST} contains a
     * list of packages whose availability changed.
     * The extra data {@link #EXTRA_CHANGED_UID_LIST} contains a
     * list of uids of packages whose availability changed.
     * Note that the
     * packages in this list do <em>not</em> receive this broadcast.
     * The specified set of packages are now available on the system.
     * <p>Includes the following extras:
     * <ul>
     * <li> {@link #EXTRA_CHANGED_PACKAGE_LIST} is the set of packages
     * whose resources(were previously unavailable) are currently available.
     * {@link #EXTRA_CHANGED_UID_LIST} is the set of uids of the
     * packages whose resources(were previously unavailable)
     * are  currently available.
     * </ul>
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_EXTERNAL_APPLICATIONS_AVAILABLE =
        "android.intent.action.EXTERNAL_APPLICATIONS_AVAILABLE";

    /**
     * Broadcast Action: Resources for a set of packages are currently
     * unavailable since the media on which they exist is unavailable.
     * The extra data {@link #EXTRA_CHANGED_PACKAGE_LIST} contains a
     * list of packages whose availability changed.
     * The extra data {@link #EXTRA_CHANGED_UID_LIST} contains a
     * list of uids of packages whose availability changed.
     * The specified set of packages can no longer be
     * launched and are practically unavailable on the system.
     * <p>Inclues the following extras:
     * <ul>
     * <li> {@link #EXTRA_CHANGED_PACKAGE_LIST} is the set of packages
     * whose resources are no longer available.
     * {@link #EXTRA_CHANGED_UID_LIST} is the set of packages
     * whose resources are no longer available.
     * </ul>
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE =
        "android.intent.action.EXTERNAL_APPLICATIONS_UNAVAILABLE";

    /**
     * Broadcast Action: preferred activities have changed *explicitly*.
     *
     * <p>Note there are cases where a preferred activity is invalidated *implicitly*, e.g.
     * when an app is installed or uninstalled, but in such cases this broadcast will *not*
     * be sent.
     *
     * {@link #EXTRA_USER_HANDLE} contains the user ID in question.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PREFERRED_ACTIVITY_CHANGED =
            "android.intent.action.ACTION_PREFERRED_ACTIVITY_CHANGED";


    /**
     * Broadcast Action:  The current system wallpaper has changed.  See
     * {@link android.app.WallpaperManager} for retrieving the new wallpaper.
     * This should <em>only</em> be used to determine when the wallpaper
     * has changed to show the new wallpaper to the user.  You should certainly
     * never, in response to this, change the wallpaper or other attributes of
     * it such as the suggested size.  That would be unexpected, right?  You'd cause
     * all kinds of loops, especially if other apps are doing similar things,
     * right?  Of course.  So please don't do this.
     *
     * @deprecated Modern applications should use
     * {@link android.view.WindowManager.LayoutParams#FLAG_SHOW_WALLPAPER
     * WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER} to have the wallpaper
     * shown behind their UI, rather than watching for this broadcast and
     * rendering the wallpaper on their own.
     */
    @Deprecated @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_WALLPAPER_CHANGED = "android.intent.action.WALLPAPER_CHANGED";
    /**
     * Broadcast Action: The current device {@link android.content.res.Configuration}
     * (orientation, locale, etc) has changed.  When such a change happens, the
     * UIs (view hierarchy) will need to be rebuilt based on this new
     * information; for the most part, applications don't need to worry about
     * this, because the system will take care of stopping and restarting the
     * application to make sure it sees the new changes.  Some system code that
     * can not be restarted will need to watch for this action and handle it
     * appropriately.
     *
     * <p class="note">
     * You <em>cannot</em> receive this through components declared
     * in manifests, only by explicitly registering for it with
     * {@link Context#registerReceiver(BroadcastReceiver, IntentFilter)
     * Context.registerReceiver()}.
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     *
     * @see android.content.res.Configuration
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_CONFIGURATION_CHANGED = "android.intent.action.CONFIGURATION_CHANGED";

    /**
     * Broadcast Action: The current device {@link android.content.res.Configuration} has changed
     * such that the device may be eligible for the installation of additional configuration splits.
     * Configuration properties that can trigger this broadcast include locale and display density.
     *
     * <p class="note">
     * Unlike {@link #ACTION_CONFIGURATION_CHANGED}, you <em>can</em> receive this through
     * components declared in manifests. However, the receiver <em>must</em> hold the
     * {@link android.Manifest.permission#INSTALL_PACKAGES} permission.
     *
     * <p class="note">
     * This is a protected intent that can only be sent by the system.
     *
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_SPLIT_CONFIGURATION_CHANGED =
            "android.intent.action.SPLIT_CONFIGURATION_CHANGED";
    /**
     * Broadcast Action: The receiver's effective locale has changed.
     *
     * This happens when the device locale, the receiving app's locale
     * (set via {@link android.app.LocaleManager#setApplicationLocales}) or language tags
     * of Regional preferences changed.
     *
     * Can be received by manifest-declared receivers.
     *
     * <p class="note"> If only the app locale changed, includes the following extras:
     * <ul>
     * <li>{@link #EXTRA_PACKAGE_NAME} is the name of the package for which locale changed.
     * <li>{@link #EXTRA_LOCALE_LIST} contains locales that are currently set for specified app
     * </ul>
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_LOCALE_CHANGED = "android.intent.action.LOCALE_CHANGED";
    /**
     * Broadcast Action: Locale of a particular app has changed.
     *
     * <p class="note"> This broadcast is explicitly sent to the
     * {@link android.content.pm.InstallSourceInfo#getInstallingPackageName} installer
     *     of the app whose locale has changed.
     * <p class="note"> The broadcast could also be received by manifest-declared receivers with
     * {@code android.permission.READ_APP_SPECIFIC_LOCALES}
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     *
     * <p>Includes the following extras:
     * <ul>
     * <li>{@link #EXTRA_PACKAGE_NAME} is the name of the package for which locale changed.
     * <li>{@link #EXTRA_LOCALE_LIST} contains locales that are currently set for specified app
     * </ul>
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_APPLICATION_LOCALE_CHANGED =
            "android.intent.action.APPLICATION_LOCALE_CHANGED";
    /**
     * Broadcast Action:  This is a <em>sticky broadcast</em> containing the
     * charging state, level, and other information about the battery.
     * See {@link android.os.BatteryManager} for documentation on the
     * contents of the Intent.
     *
     * <p class="note">
     * You <em>cannot</em> receive this through components declared
     * in manifests, only by explicitly registering for it with
     * {@link Context#registerReceiver(BroadcastReceiver, IntentFilter)
     * Context.registerReceiver()}.  See {@link #ACTION_BATTERY_LOW},
     * {@link #ACTION_BATTERY_OKAY}, {@link #ACTION_POWER_CONNECTED},
     * and {@link #ACTION_POWER_DISCONNECTED} for distinct battery-related
     * broadcasts that are sent and can be received through manifest
     * receivers.
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_BATTERY_CHANGED = "android.intent.action.BATTERY_CHANGED";


    /**
     * Broadcast Action: Sent when the current battery level or plug type changes.
     *
     * It has {@link android.os.BatteryManager#EXTRA_EVENTS} that carries a list of {@link Bundle}
     * instances representing individual battery level changes with associated
     * extras from {@link #ACTION_BATTERY_CHANGED}.
     *
     * <p class="note">
     * This broadcast requires {@link android.Manifest.permission#BATTERY_STATS} permission.
     *
     * @hide
     */
    @SystemApi
    public static final String ACTION_BATTERY_LEVEL_CHANGED =
            "android.intent.action.BATTERY_LEVEL_CHANGED";
    /**
     * Broadcast Action:  Indicates low battery condition on the device.
     * This broadcast corresponds to the "Low battery warning" system dialog.
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_BATTERY_LOW = "android.intent.action.BATTERY_LOW";
    /**
     * Broadcast Action:  Indicates the battery is now okay after being low.
     * This will be sent after {@link #ACTION_BATTERY_LOW} once the battery has
     * gone back up to an okay state.
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_BATTERY_OKAY = "android.intent.action.BATTERY_OKAY";
    /**
     * Broadcast Action:  External power has been connected to the device.
     * This is intended for applications that wish to register specifically to this notification.
     * Unlike ACTION_BATTERY_CHANGED, applications will be woken for this and so do not have to
     * stay active to receive this notification.  This action can be used to implement actions
     * that wait until power is available to trigger.
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_POWER_CONNECTED = "android.intent.action.ACTION_POWER_CONNECTED";
    /**
     * Broadcast Action:  External power has been removed from the device.
     * This is intended for applications that wish to register specifically to this notification.
     * Unlike ACTION_BATTERY_CHANGED, applications will be woken for this and so do not have to
     * stay active to receive this notification.  This action can be used to implement actions
     * that wait until power is available to trigger.
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_POWER_DISCONNECTED =
            "android.intent.action.ACTION_POWER_DISCONNECTED";
    /**
     * Broadcast Action:  Device is shutting down.
     * This is broadcast when the device is being shut down (completely turned
     * off, not sleeping).  Once the broadcast is complete, the final shutdown
     * will proceed and all unsaved data lost.  Apps will not normally need
     * to handle this, since the foreground activity will be paused as well.
     * <p>As of {@link Build.VERSION_CODES#P} this broadcast is only sent to receivers registered
     * through {@link Context#registerReceiver(BroadcastReceiver, IntentFilter)
     * Context.registerReceiver}.
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     * <p>May include the following extras:
     * <ul>
     * <li> {@link #EXTRA_SHUTDOWN_USERSPACE_ONLY} a boolean that is set to true if this
     * shutdown is only for userspace processes.  If not set, assumed to be false.
     * </ul>
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_SHUTDOWN = "android.intent.action.ACTION_SHUTDOWN";
    /**
     * Activity Action:  Start this activity to request system shutdown.
     * The optional boolean extra field {@link #EXTRA_KEY_CONFIRM} can be set to true
     * to request confirmation from the user before shutting down. The optional boolean
     * extra field {@link #EXTRA_USER_REQUESTED_SHUTDOWN} can be set to true to
     * indicate that the shutdown is requested by the user.
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     *
     * {@hide}
     */
    public static final String ACTION_REQUEST_SHUTDOWN
            = "com.android.internal.intent.action.REQUEST_SHUTDOWN";
    /**
     * Broadcast Action: A sticky broadcast that indicates low storage space
     * condition on the device
     * <p class="note">
     * This is a protected intent that can only be sent by the system.
     *
     * @deprecated if your app targets {@link android.os.Build.VERSION_CODES#O}
     *             or above, this broadcast will no longer be delivered to any
     *             {@link BroadcastReceiver} defined in your manifest. Instead,
     *             apps are strongly encouraged to use the improved
     *             {@link Context#getCacheDir()} behavior so the system can
     *             automatically free up storage when needed.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @Deprecated
    public static final String ACTION_DEVICE_STORAGE_LOW = "android.intent.action.DEVICE_STORAGE_LOW";
    /**
     * Broadcast Action: Indicates low storage space condition on the device no
     * longer exists
     * <p class="note">
     * This is a protected intent that can only be sent by the system.
     *
     * @deprecated if your app targets {@link android.os.Build.VERSION_CODES#O}
     *             or above, this broadcast will no longer be delivered to any
     *             {@link BroadcastReceiver} defined in your manifest. Instead,
     *             apps are strongly encouraged to use the improved
     *             {@link Context#getCacheDir()} behavior so the system can
     *             automatically free up storage when needed.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @Deprecated
    public static final String ACTION_DEVICE_STORAGE_OK = "android.intent.action.DEVICE_STORAGE_OK";
    /**
     * Broadcast Action: A sticky broadcast that indicates a storage space full
     * condition on the device. This is intended for activities that want to be
     * able to fill the data partition completely, leaving only enough free
     * space to prevent system-wide SQLite failures.
     * <p class="note">
     * This is a protected intent that can only be sent by the system.
     *
     * @deprecated if your app targets {@link android.os.Build.VERSION_CODES#O}
     *             or above, this broadcast will no longer be delivered to any
     *             {@link BroadcastReceiver} defined in your manifest. Instead,
     *             apps are strongly encouraged to use the improved
     *             {@link Context#getCacheDir()} behavior so the system can
     *             automatically free up storage when needed.
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @Deprecated
    public static final String ACTION_DEVICE_STORAGE_FULL = "android.intent.action.DEVICE_STORAGE_FULL";
    /**
     * Broadcast Action: Indicates storage space full condition on the device no
     * longer exists.
     * <p class="note">
     * This is a protected intent that can only be sent by the system.
     *
     * @deprecated if your app targets {@link android.os.Build.VERSION_CODES#O}
     *             or above, this broadcast will no longer be delivered to any
     *             {@link BroadcastReceiver} defined in your manifest. Instead,
     *             apps are strongly encouraged to use the improved
     *             {@link Context#getCacheDir()} behavior so the system can
     *             automatically free up storage when needed.
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @Deprecated
    public static final String ACTION_DEVICE_STORAGE_NOT_FULL = "android.intent.action.DEVICE_STORAGE_NOT_FULL";
    /**
     * Broadcast Action:  Indicates low memory condition notification acknowledged by user
     * and package management should be started.
     * This is triggered by the user from the ACTION_DEVICE_STORAGE_LOW
     * notification.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_MANAGE_PACKAGE_STORAGE = "android.intent.action.MANAGE_PACKAGE_STORAGE";
    /**
     * Broadcast Action:  The device has entered USB Mass Storage mode.
     * This is used mainly for the USB Settings panel.
     * Apps should listen for ACTION_MEDIA_MOUNTED and ACTION_MEDIA_UNMOUNTED broadcasts to be notified
     * when the SD card file system is mounted or unmounted
     * @deprecated replaced by android.os.storage.StorageEventListener
     */
    @Deprecated
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_UMS_CONNECTED = "android.intent.action.UMS_CONNECTED";

    /**
     * Broadcast Action:  The device has exited USB Mass Storage mode.
     * This is used mainly for the USB Settings panel.
     * Apps should listen for ACTION_MEDIA_MOUNTED and ACTION_MEDIA_UNMOUNTED broadcasts to be notified
     * when the SD card file system is mounted or unmounted
     * @deprecated replaced by android.os.storage.StorageEventListener
     */
    @Deprecated
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_UMS_DISCONNECTED = "android.intent.action.UMS_DISCONNECTED";

    /**
     * Broadcast Action:  External media has been removed.
     * The path to the mount point for the removed media is contained in the Intent.mData field.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_MEDIA_REMOVED = "android.intent.action.MEDIA_REMOVED";

    /**
     * Broadcast Action:  External media is present, but not mounted at its mount point.
     * The path to the mount point for the unmounted media is contained in the Intent.mData field.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_MEDIA_UNMOUNTED = "android.intent.action.MEDIA_UNMOUNTED";

    /**
     * Broadcast Action:  External media is present, and being disk-checked
     * The path to the mount point for the checking media is contained in the Intent.mData field.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_MEDIA_CHECKING = "android.intent.action.MEDIA_CHECKING";

    /**
     * Broadcast Action:  External media is present, but is using an incompatible fs (or is blank)
     * The path to the mount point for the checking media is contained in the Intent.mData field.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_MEDIA_NOFS = "android.intent.action.MEDIA_NOFS";

    /**
     * Broadcast Action:  External media is present and mounted at its mount point.
     * The path to the mount point for the mounted media is contained in the Intent.mData field.
     * The Intent contains an extra with name "read-only" and Boolean value to indicate if the
     * media was mounted read only.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_MEDIA_MOUNTED = "android.intent.action.MEDIA_MOUNTED";

    /**
     * Broadcast Action:  External media is unmounted because it is being shared via USB mass storage.
     * The path to the mount point for the shared media is contained in the Intent.mData field.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_MEDIA_SHARED = "android.intent.action.MEDIA_SHARED";

    /**
     * Broadcast Action:  External media is no longer being shared via USB mass storage.
     * The path to the mount point for the previously shared media is contained in the Intent.mData field.
     *
     * @hide
     */
    public static final String ACTION_MEDIA_UNSHARED = "android.intent.action.MEDIA_UNSHARED";

    /**
     * Broadcast Action:  External media was removed from SD card slot, but mount point was not unmounted.
     * The path to the mount point for the removed media is contained in the Intent.mData field.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_MEDIA_BAD_REMOVAL = "android.intent.action.MEDIA_BAD_REMOVAL";

    /**
     * Broadcast Action:  External media is present but cannot be mounted.
     * The path to the mount point for the unmountable media is contained in the Intent.mData field.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_MEDIA_UNMOUNTABLE = "android.intent.action.MEDIA_UNMOUNTABLE";

   /**
     * Broadcast Action:  User has expressed the desire to remove the external storage media.
     * Applications should close all files they have open within the mount point when they receive this intent.
     * The path to the mount point for the media to be ejected is contained in the Intent.mData field.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_MEDIA_EJECT = "android.intent.action.MEDIA_EJECT";

    /**
     * Broadcast Action:  The media scanner has started scanning a directory.
     * The path to the directory being scanned is contained in the Intent.mData field.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_MEDIA_SCANNER_STARTED = "android.intent.action.MEDIA_SCANNER_STARTED";

   /**
     * Broadcast Action:  The media scanner has finished scanning a directory.
     * The path to the scanned directory is contained in the Intent.mData field.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_MEDIA_SCANNER_FINISHED = "android.intent.action.MEDIA_SCANNER_FINISHED";

    /**
     * Broadcast Action: Request the media scanner to scan a file and add it to
     * the media database.
     * <p>
     * The path to the file is contained in {@link Intent#getData()}.
     *
     * @deprecated Callers should migrate to inserting items directly into
     *             {@link MediaStore}, where they will be automatically scanned
     *             after each mutation.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @Deprecated
    public static final String ACTION_MEDIA_SCANNER_SCAN_FILE = "android.intent.action.MEDIA_SCANNER_SCAN_FILE";

   /**
     * Broadcast Action:  The "Media Button" was pressed.  Includes a single
     * extra field, {@link #EXTRA_KEY_EVENT}, containing the key event that
     * caused the broadcast.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_MEDIA_BUTTON = "android.intent.action.MEDIA_BUTTON";

    /**
     * Broadcast Action:  The "Camera Button" was pressed.  Includes a single
     * extra field, {@link #EXTRA_KEY_EVENT}, containing the key event that
     * caused the broadcast.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_CAMERA_BUTTON = "android.intent.action.CAMERA_BUTTON";

    // *** NOTE: @todo(*) The following really should go into a more domain-specific
    // location; they are not general-purpose actions.

    /**
     * Broadcast Action: A GTalk connection has been established.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_GTALK_SERVICE_CONNECTED =
            "android.intent.action.GTALK_CONNECTED";

    /**
     * Broadcast Action: A GTalk connection has been disconnected.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_GTALK_SERVICE_DISCONNECTED =
            "android.intent.action.GTALK_DISCONNECTED";

    /**
     * Broadcast Action: An input method has been changed.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_INPUT_METHOD_CHANGED =
            "android.intent.action.INPUT_METHOD_CHANGED";

    /**
     * <p>Broadcast Action: The user has switched the phone into or out of Airplane Mode. One or
     * more radios have been turned off or on. The intent will have the following extra value:</p>
     * <ul>
     *   <li><em>state</em> - A boolean value indicating whether Airplane Mode is on. If true,
     *   then cell radio and possibly other radios such as bluetooth or WiFi may have also been
     *   turned off</li>
     * </ul>
     *
     * <p class="note">This is a protected intent that can only be sent by the system.</p>
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_AIRPLANE_MODE_CHANGED = "android.intent.action.AIRPLANE_MODE";

    /**
     * Broadcast Action: Some content providers have parts of their namespace
     * where they publish new events or items that the user may be especially
     * interested in. For these things, they may broadcast this action when the
     * set of interesting items change.
     *
     * For example, GmailProvider sends this notification when the set of unread
     * mail in the inbox changes.
     *
     * <p>The data of the intent identifies which part of which provider
     * changed. When queried through the content resolver, the data URI will
     * return the data set in question.
     *
     * <p>The intent will have the following extra values:
     * <ul>
     *   <li><em>count</em> - The number of items in the data set. This is the
     *       same as the number of items in the cursor returned by querying the
     *       data URI. </li>
     * </ul>
     *
     * This intent will be sent at boot (if the count is non-zero) and when the
     * data set changes. It is possible for the data set to change without the
     * count changing (for example, if a new unread message arrives in the same
     * sync operation in which a message is archived). The phone should still
     * ring/vibrate/etc as normal in this case.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PROVIDER_CHANGED =
            "android.intent.action.PROVIDER_CHANGED";

    /**
     * Broadcast Action: Wired Headset plugged in or unplugged.
     *
     * Same as {@link android.media.AudioManager#ACTION_HEADSET_PLUG}, to be consulted for value
     *   and documentation.
     * <p>If the minimum SDK version of your application is
     * {@link android.os.Build.VERSION_CODES#LOLLIPOP}, it is recommended to refer
     * to the <code>AudioManager</code> constant in your receiver registration code instead.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_HEADSET_PLUG = android.media.AudioManager.ACTION_HEADSET_PLUG;

    /**
     * <p>Broadcast Action: The user has switched on advanced settings in the settings app:</p>
     * <ul>
     *   <li><em>state</em> - A boolean value indicating whether the settings is on or off.</li>
     * </ul>
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     *
     * @hide
     */
    //@SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_ADVANCED_SETTINGS_CHANGED
            = "android.intent.action.ADVANCED_SETTINGS";

    /**
     *  Broadcast Action: Sent after application restrictions are changed.
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.</p>
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_APPLICATION_RESTRICTIONS_CHANGED =
            "android.intent.action.APPLICATION_RESTRICTIONS_CHANGED";

    /**
     * Broadcast Action: An outgoing call is about to be placed.
     *
     * <p>The Intent will have the following extra value:</p>
     * <ul>
     *   <li><em>{@link android.content.Intent#EXTRA_PHONE_NUMBER}</em> -
     *       the phone number originally intended to be dialed.</li>
     * </ul>
     * <p>Once the broadcast is finished, the resultData is used as the actual
     * number to call.  If  <code>null</code>, no call will be placed.</p>
     * <p>It is perfectly acceptable for multiple receivers to process the
     * outgoing call in turn: for example, a parental control application
     * might verify that the user is authorized to place the call at that
     * time, then a number-rewriting application might add an area code if
     * one was not specified.</p>
     * <p>For consistency, any receiver whose purpose is to prohibit phone
     * calls should have a priority of 0, to ensure it will see the final
     * phone number to be dialed.
     * Any receiver whose purpose is to rewrite phone numbers to be called
     * should have a positive priority.
     * Negative priorities are reserved for the system for this broadcast;
     * using them may cause problems.</p>
     * <p>Any BroadcastReceiver receiving this Intent <em>must not</em>
     * abort the broadcast.</p>
     * <p>Emergency calls cannot be intercepted using this mechanism, and
     * other calls cannot be modified to call emergency numbers using this
     * mechanism.
     * <p>Some apps (such as VoIP apps) may want to redirect the outgoing
     * call to use their own service instead. Those apps should first prevent
     * the call from being placed by setting resultData to <code>null</code>
     * and then start their own app to make the call.
     * <p>You must hold the
     * {@link android.Manifest.permission#PROCESS_OUTGOING_CALLS}
     * permission to receive this Intent.</p>
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     *
     * <p class="note">If the user has chosen a {@link android.telecom.CallRedirectionService} to
     * handle redirection of outgoing calls, this intent will NOT be sent as an ordered broadcast.
     * This means that attempts to re-write the outgoing call by other apps using this intent will
     * be ignored.
     * </p>
     *
     * @deprecated Apps that redirect outgoing calls should use the
     * {@link android.telecom.CallRedirectionService} API.  Apps that perform call screening
     * should use the {@link android.telecom.CallScreeningService} API.  Apps which need to be
     * notified of basic call state should use
     * {@link android.telephony.PhoneStateListener#onCallStateChanged(int, String)} to determine
     * when a new outgoing call is placed.
     */
    @Deprecated
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_NEW_OUTGOING_CALL =
            "android.intent.action.NEW_OUTGOING_CALL";

    /**
     * Broadcast Action: Have the device reboot.  This is only for use by
     * system code.
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_REBOOT =
            "android.intent.action.REBOOT";

    /**
     * Broadcast Action:  A sticky broadcast for changes in the physical
     * docking state of the device.
     *
     * <p>The intent will have the following extra values:
     * <ul>
     *   <li><em>{@link #EXTRA_DOCK_STATE}</em> - the current dock
     *       state, indicating which dock the device is physically in.</li>
     * </ul>
     * <p>This is intended for monitoring the current physical dock state.
     * See {@link android.app.UiModeManager} for the normal API dealing with
     * dock mode changes.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_DOCK_EVENT =
            "android.intent.action.DOCK_EVENT";

    /**
     * Broadcast Action: A broadcast when idle maintenance can be started.
     * This means that the user is not interacting with the device and is
     * not expected to do so soon. Typical use of the idle maintenance is
     * to perform somehow expensive tasks that can be postponed at a moment
     * when they will not degrade user experience.
     * <p>
     * <p class="note">In order to keep the device responsive in case of an
     * unexpected user interaction, implementations of a maintenance task
     * should be interruptible. In such a scenario a broadcast with action
     * {@link #ACTION_IDLE_MAINTENANCE_END} will be sent. In other words, you
     * should not do the maintenance work in
     * {@link BroadcastReceiver#onReceive(Context, Intent)}, rather start a
     * maintenance service by {@link Context#startService(Intent)}. Also
     * you should hold a wake lock while your maintenance service is running
     * to prevent the device going to sleep.
     * </p>
     * <p>
     * <p class="note">This is a protected intent that can only be sent by
     * the system.
     * </p>
     *
     * @see #ACTION_IDLE_MAINTENANCE_END
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_IDLE_MAINTENANCE_START =
            "android.intent.action.ACTION_IDLE_MAINTENANCE_START";

    /**
     * Broadcast Action:  A broadcast when idle maintenance should be stopped.
     * This means that the user was not interacting with the device as a result
     * of which a broadcast with action {@link #ACTION_IDLE_MAINTENANCE_START}
     * was sent and now the user started interacting with the device. Typical
     * use of the idle maintenance is to perform somehow expensive tasks that
     * can be postponed at a moment when they will not degrade user experience.
     * <p>
     * <p class="note">In order to keep the device responsive in case of an
     * unexpected user interaction, implementations of a maintenance task
     * should be interruptible. Hence, on receiving a broadcast with this
     * action, the maintenance task should be interrupted as soon as possible.
     * In other words, you should not do the maintenance work in
     * {@link BroadcastReceiver#onReceive(Context, Intent)}, rather stop the
     * maintenance service that was started on receiving of
     * {@link #ACTION_IDLE_MAINTENANCE_START}.Also you should release the wake
     * lock you acquired when your maintenance service started.
     * </p>
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     *
     * @see #ACTION_IDLE_MAINTENANCE_START
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_IDLE_MAINTENANCE_END =
            "android.intent.action.ACTION_IDLE_MAINTENANCE_END";

    /**
     * Broadcast Action: a remote intent is to be broadcasted.
     *
     * A remote intent is used for remote RPC between devices. The remote intent
     * is serialized and sent from one device to another device. The receiving
     * device parses the remote intent and broadcasts it. Note that anyone can
     * broadcast a remote intent. However, if the intent receiver of the remote intent
     * does not trust intent broadcasts from arbitrary intent senders, it should require
     * the sender to hold certain permissions so only trusted sender's broadcast will be
     * let through.
     * @hide
     */
    public static final String ACTION_REMOTE_INTENT =
            "com.google.android.c2dm.intent.RECEIVE";

    /**
     * Broadcast Action: This is broadcast once when the user is booting after a
     * system update. It can be used to perform cleanup or upgrades after a
     * system update.
     * <p>
     * This broadcast is sent after the {@link #ACTION_LOCKED_BOOT_COMPLETED}
     * broadcast but before the {@link #ACTION_BOOT_COMPLETED} broadcast. It's
     * only sent when the {@link Build#FINGERPRINT} has changed, and it's only
     * sent to receivers in the system image.
     *
     * @hide
     */
    @SystemApi
    public static final String ACTION_PRE_BOOT_COMPLETED =
            "android.intent.action.PRE_BOOT_COMPLETED";

    /**
     * Broadcast to a specific application to query any supported restrictions to impose
     * on restricted users. The broadcast intent contains an extra
     * {@link #EXTRA_RESTRICTIONS_BUNDLE} with the currently persisted
     * restrictions as a Bundle of key/value pairs. The value types can be Boolean, String or
     * String[] depending on the restriction type.<p/>
     * The response should contain an extra {@link #EXTRA_RESTRICTIONS_LIST},
     * which is of type <code>ArrayList&lt;RestrictionEntry&gt;</code>. It can also
     * contain an extra {@link #EXTRA_RESTRICTIONS_INTENT}, which is of type <code>Intent</code>.
     * The activity specified by that intent will be launched for a result which must contain
     * one of the extras {@link #EXTRA_RESTRICTIONS_LIST} or {@link #EXTRA_RESTRICTIONS_BUNDLE}.
     * The keys and values of the returned restrictions will be persisted.
     * @see RestrictionEntry
     */
    public static final String ACTION_GET_RESTRICTION_ENTRIES =
            "android.intent.action.GET_RESTRICTION_ENTRIES";

    /**
     * Sent the first time a user is starting, to allow system apps to
     * perform one time initialization.  (This will not be seen by third
     * party applications because a newly initialized user does not have any
     * third party applications installed for it.)  This is sent early in
     * starting the user, around the time the home app is started, before
     * {@link #ACTION_BOOT_COMPLETED} is sent.  This is sent as a foreground
     * broadcast, since it is part of a visible user interaction; be as quick
     * as possible when handling it.
     *
     * <p><b>Note:</b> This broadcast is not sent to the system user.
     */
    public static final String ACTION_USER_INITIALIZE =
            "android.intent.action.USER_INITIALIZE";

    /**
     * Sent after a user switch is complete, if the switch caused the process's user to be
     * brought to the foreground.  This is only sent to receivers registered
     * through {@link Context#registerReceiver(BroadcastReceiver, IntentFilter)
     * Context.registerReceiver}.  It is sent to the user that is going to the
     * foreground.  This is sent as a foreground
     * broadcast, since it is part of a visible user interaction; be as quick
     * as possible when handling it.
     */
    public static final String ACTION_USER_FOREGROUND =
            "android.intent.action.USER_FOREGROUND";

    /**
     * Sent after a user switch is complete, if the switch caused the process's user to be
     * sent to the background.  This is only sent to receivers registered
     * through {@link Context#registerReceiver(BroadcastReceiver, IntentFilter)
     * Context.registerReceiver}.  It is sent to the user that is going to the
     * background.  This is sent as a foreground
     * broadcast, since it is part of a visible user interaction; be as quick
     * as possible when handling it.
     */
    public static final String ACTION_USER_BACKGROUND =
            "android.intent.action.USER_BACKGROUND";

    /**
     * Broadcast sent to the system when a user is added.
     * Carries an extra {@link #EXTRA_USER} that specifies the {@link UserHandle} of the new user
     * (and for legacy reasons, also carries an int extra {@link #EXTRA_USER_HANDLE} specifying that
     * user's user ID).
     * It is sent to all running users.
     * You must hold {@link android.Manifest.permission#MANAGE_USERS} to receive this broadcast.
     * @hide
     */
    @SystemApi
    public static final String ACTION_USER_ADDED =
            "android.intent.action.USER_ADDED";

    /**
     * Broadcast sent by the system when a user is started. Carries an extra
     * {@link #EXTRA_USER_HANDLE} that has the user ID of the user.  This is only sent to
     * registered receivers, not manifest receivers.  It is sent to the user
     * that has been started.  This is sent as a foreground
     * broadcast, since it is part of a visible user interaction; be as quick
     * as possible when handling it.
     *
     * <p>
     * <b>Note:</b> The user's actual state might have changed by the time the broadcast is
     * received. For example, the user could have been removed, started or stopped already,
     * regardless of which broadcast you receive. Because of that, receivers should always check
     * the current state of the user.
     * @hide
     */
    public static final String ACTION_USER_STARTED =
            "android.intent.action.USER_STARTED";

    /**
     * Broadcast sent when a user is in the process of starting.  Carries an extra
     * {@link #EXTRA_USER_HANDLE} that has the user ID of the user.  This is only
     * sent to registered receivers, not manifest receivers.  It is sent to all
     * users (including the one that is being started).  You must hold
     * {@link android.Manifest.permission#INTERACT_ACROSS_USERS} to receive
     * this broadcast.  This is sent as a background broadcast, since
     * its result is not part of the primary UX flow; to safely keep track of
     * started/stopped state of a user you can use this in conjunction with
     * {@link #ACTION_USER_STOPPING}.  It is <b>not</b> generally safe to use with
     * other user state broadcasts since those are foreground broadcasts so can
     * execute in a different order.
     *
     * <p>
     * <b>Note:</b> The user's actual state might have changed by the time the broadcast is
     * received. For example, the user could have been removed, started or stopped already,
     * regardless of which broadcast you receive. Because of that, receivers should always check
     * the current state of the user.
     * @hide
     */
    public static final String ACTION_USER_STARTING =
            "android.intent.action.USER_STARTING";

    /**
     * Broadcast sent when a user is going to be stopped.  Carries an extra
     * {@link #EXTRA_USER_HANDLE} that has the user ID of the user.  This is only
     * sent to registered receivers, not manifest receivers.  It is sent to all
     * users (including the one that is being stopped).  You must hold
     * {@link android.Manifest.permission#INTERACT_ACROSS_USERS} to receive
     * this broadcast.  The user will not stop until all receivers have
     * handled the broadcast.  This is sent as a background broadcast, since
     * its result is not part of the primary UX flow; to safely keep track of
     * started/stopped state of a user you can use this in conjunction with
     * {@link #ACTION_USER_STARTING}.  It is <b>not</b> generally safe to use with
     * other user state broadcasts since those are foreground broadcasts so can
     * execute in a different order.
     * <p>
     * <b>Note:</b> The user's actual state might have changed by the time the broadcast is
     * received. For example, the user could have been removed, started or stopped already,
     * regardless of which broadcast you receive. Because of that, receivers should always check
     * the current state of the user.
     * @hide
     */
    public static final String ACTION_USER_STOPPING =
            "android.intent.action.USER_STOPPING";

    /**
     * Broadcast sent to the system when a user is stopped. Carries an extra
     * {@link #EXTRA_USER_HANDLE} that has the user ID of the user.  This is similar to
     * {@link #ACTION_PACKAGE_RESTARTED}, but for an entire user instead of a
     * specific package.  This is only sent to registered receivers, not manifest
     * receivers.  It is sent to all running users <em>except</em> the one that
     * has just been stopped (which is no longer running).
     *
     * <p>
     * <b>Note:</b> The user's actual state might have changed by the time the broadcast is
     * received. For example, the user could have been removed, started or stopped already,
     * regardless of which broadcast you receive. Because of that, receivers should always check
     * the current state of the user.
     * @hide
     */
    @TestApi
    public static final String ACTION_USER_STOPPED =
            "android.intent.action.USER_STOPPED";

    /**
     * Broadcast sent to the system when a user is removed.
     * Carries an extra {@link #EXTRA_USER} that specifies the {@link UserHandle} of the user that
     * was removed
     * (and for legacy reasons, also carries an int extra {@link #EXTRA_USER_HANDLE} specifying that
     * user's user ID).
     * It is sent to all running users except the
     * one that has been removed. The user will not be completely removed until all receivers have
     * handled the broadcast. You must hold
     * {@link android.Manifest.permission#MANAGE_USERS} to receive this broadcast.
     * @hide
     */
    @SystemApi
    public static final String ACTION_USER_REMOVED =
            "android.intent.action.USER_REMOVED";

    /**
     * Broadcast sent to the system when the user switches.
     * Carries an extra {@link #EXTRA_USER} that specifies the {@link UserHandle}
     * of the user to become the current one
     * (and for legacy reasons, also carries an int extra {@link #EXTRA_USER_HANDLE} specifying that
     * user's user ID).
     * This is only sent to registered receivers, not manifest receivers.
     * It is sent to all running users.
     * You must hold
     * {@link android.Manifest.permission#MANAGE_USERS} to receive this broadcast.
     *
     * <p>
     * <b>Note:</b> The user's actual state might have changed by the time the broadcast is
     * received. For example, the user could have been removed, started or stopped already,
     * regardless of which broadcast you receive. Because of that, receivers should always check
     * the current state of the user.
     * @hide
     */
    /*
     * This broadcast is sent after the user switch is complete. In case a task needs to be done
     * while the switch is happening (i.e. while the screen is frozen to hide UI jank), please use
     * ActivityManagerService.registerUserSwitchObserver method.
     */
    @SystemApi
    public static final String ACTION_USER_SWITCHED =
            "android.intent.action.USER_SWITCHED";

    /**
     * Broadcast Action: Sent when the credential-encrypted private storage has
     * become unlocked for the target user. This is only sent to registered
     * receivers, not manifest receivers.
     *
     * <p>
     * <b>Note:</b> The user's actual state might have changed by the time the broadcast is
     * received. For example, the user could have been removed, started or stopped already,
     * regardless of which broadcast you receive. Because of that, receivers should always check
     * the current state of the user.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_USER_UNLOCKED = "android.intent.action.USER_UNLOCKED";

    /**
     * Broadcast sent to the system when a user's information changes. Carries an extra
     * {@link #EXTRA_USER_HANDLE} to indicate which user's information changed.
     * This is only sent to registered receivers, not manifest receivers. It is sent to all users.
     * @hide
     */
    public static final String ACTION_USER_INFO_CHANGED =
            "android.intent.action.USER_INFO_CHANGED";

    /**
     * Broadcast sent to the primary user when an associated managed profile is added (the profile
     * was created and is ready to be used). Carries an extra {@link #EXTRA_USER} that specifies
     * the {@link UserHandle} of the profile that was added. Only applications (for example
     * Launchers) that need to display merged content across both primary and managed profiles need
     * to worry about this broadcast. This is only sent to registered receivers,
     * not manifest receivers.
     */
    public static final String ACTION_MANAGED_PROFILE_ADDED =
            "android.intent.action.MANAGED_PROFILE_ADDED";

    /**
     * Broadcast sent to the primary user when an associated managed profile is removed.
     * Carries an extra {@link #EXTRA_USER} that specifies the {@link UserHandle} of the profile
     * that was removed.
     * Only applications (for example Launchers) that need to display merged content across both
     * primary and managed profiles need to worry about this broadcast. This is only sent to
     * registered receivers, not manifest receivers.
     */
    public static final String ACTION_MANAGED_PROFILE_REMOVED =
            "android.intent.action.MANAGED_PROFILE_REMOVED";

    /**
     * Broadcast sent to the primary user when the credential-encrypted private storage for
     * an associated managed profile is unlocked. Carries an extra {@link #EXTRA_USER} that
     * specifies the {@link UserHandle} of the profile that was unlocked. Only applications (for
     * example Launchers) that need to display merged content across both primary and managed
     * profiles need to worry about this broadcast. This is only sent to registered receivers,
     * not manifest receivers.
     */
    public static final String ACTION_MANAGED_PROFILE_UNLOCKED =
            "android.intent.action.MANAGED_PROFILE_UNLOCKED";

    /**
     * Broadcast sent to the primary user when an associated managed profile has become available.
     * Currently this includes when the user disables quiet mode for the profile. Carries an extra
     * {@link #EXTRA_USER} that specifies the {@link UserHandle} of the profile. When quiet mode is
     * changed, this broadcast will carry a boolean extra {@link #EXTRA_QUIET_MODE} indicating the
     * new state of quiet mode. This is only sent to registered receivers, not manifest receivers.
     */
    public static final String ACTION_MANAGED_PROFILE_AVAILABLE =
            "android.intent.action.MANAGED_PROFILE_AVAILABLE";

    /**
     * Broadcast sent to the primary user when an associated managed profile has become unavailable.
     * Currently this includes when the user enables quiet mode for the profile. Carries an extra
     * {@link #EXTRA_USER} that specifies the {@link UserHandle} of the profile. When quiet mode is
     * changed, this broadcast will carry a boolean extra {@link #EXTRA_QUIET_MODE} indicating the
     * new state of quiet mode. This is only sent to registered receivers, not manifest receivers.
     */
    public static final String ACTION_MANAGED_PROFILE_UNAVAILABLE =
            "android.intent.action.MANAGED_PROFILE_UNAVAILABLE";

    /**
     * Broadcast sent to the primary user when an associated profile has become available.
     * This is sent when a user disables quiet mode for the profile. Carries an extra
     * {@link #EXTRA_USER} that specifies the {@link UserHandle} of the profile. When quiet mode is
     * changed, this broadcast will carry a boolean extra {@link #EXTRA_QUIET_MODE} indicating the
     * new state of quiet mode. This is only sent to registered receivers, not manifest receivers.
     *
     * <p>This broadcast is similar to {@link #ACTION_MANAGED_PROFILE_AVAILABLE} but functions as a
     * generic broadcast for all profile users.
     */
    @FlaggedApi(FLAG_ALLOW_PRIVATE_PROFILE)
    public static final String ACTION_PROFILE_AVAILABLE =
            "android.intent.action.PROFILE_AVAILABLE";

    /**
     * Broadcast sent to the primary user when an associated profile has become unavailable.
     * This is sent when a user enables quiet mode for the profile. Carries an extra
     * {@link #EXTRA_USER} that specifies the {@link UserHandle} of the profile. When quiet mode is
     * changed, this broadcast will carry a boolean extra {@link #EXTRA_QUIET_MODE} indicating the
     * new state of quiet mode. This is only sent to registered receivers, not manifest receivers.
     *
     * <p>This broadcast is similar to {@link #ACTION_MANAGED_PROFILE_UNAVAILABLE} but functions as
     * a generic broadcast for all profile users.
     */
    @FlaggedApi(FLAG_ALLOW_PRIVATE_PROFILE)
    public static final String ACTION_PROFILE_UNAVAILABLE =
            "android.intent.action.PROFILE_UNAVAILABLE";

    /**
     * Broadcast sent to the parent user when an associated profile has been started and unlocked.
     * Carries an extra {@link #EXTRA_USER} that specifies the {@link UserHandle} of the profile.
     * This is only sent to registered receivers, not manifest receivers.
     */
    public static final String ACTION_PROFILE_ACCESSIBLE =
            "android.intent.action.PROFILE_ACCESSIBLE";

    /**
     * Broadcast sent to the parent user when an associated profile has stopped.
     * Carries an extra {@link #EXTRA_USER} that specifies the {@link UserHandle} of the profile.
     * This is only sent to registered receivers, not manifest receivers.
     */
    public static final String ACTION_PROFILE_INACCESSIBLE =
            "android.intent.action.PROFILE_INACCESSIBLE";

    /**
     * Broadcast sent to the parent user when an associated profile is removed.
     * Carries an extra {@link #EXTRA_USER} that specifies the {@link UserHandle} of the profile
     * that was removed.
     *
     * <p>This broadcast is similar to {@link #ACTION_MANAGED_PROFILE_REMOVED} but functions as a
     * generic broadcast for all profile users.
     * It is sent in addition to the {@link #ACTION_MANAGED_PROFILE_REMOVED} broadcast when a
     * managed user is removed.
     *
     * <p>Only applications (for example Launchers) that need to display merged content across both
     * the parent user and its associated profiles need to worry about this broadcast.
     * This is only sent to registered receivers created with {@link Context#registerReceiver}.
     * It is not sent to manifest receivers.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PROFILE_REMOVED =
            "android.intent.action.PROFILE_REMOVED";

    /**
     * Broadcast sent to the parent user when an associated profile is added (the profile was
     * created and is ready to be used).
     * Carries an extra {@link #EXTRA_USER} that specifies the  {@link UserHandle} of the profile
     * that was added.
     *
     * <p>This broadcast is similar to {@link #ACTION_MANAGED_PROFILE_ADDED} but functions as a
     * generic broadcast for all profile users.
     * It is sent in addition to the {@link #ACTION_MANAGED_PROFILE_ADDED} broadcast when a
     * managed user is added.
     *
     * <p>Only applications (for example Launchers) that need to display merged content across both
     * the parent user and its associated profiles need to worry about this broadcast.
     * This is only sent to registered receivers created with {@link Context#registerReceiver}.
     * It is not sent to manifest receivers.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PROFILE_ADDED =
            "android.intent.action.PROFILE_ADDED";

    /**
     * Broadcast sent to the system user when the 'device locked' state changes for any user.
     * Carries an extra {@link #EXTRA_USER_HANDLE} that specifies the ID of the user for which
     * the device was locked or unlocked.
     *
     * This is only sent to registered receivers.
     *
     * @hide
     */
    public static final String ACTION_DEVICE_LOCKED_CHANGED =
            "android.intent.action.DEVICE_LOCKED_CHANGED";

    /**
     * Sent when the user taps on the clock widget in the system's "quick settings" area.
     */
    public static final String ACTION_QUICK_CLOCK =
            "android.intent.action.QUICK_CLOCK";

    /**
     * Activity Action: Shows the brightness setting dialog.
     * @hide
     */
    public static final String ACTION_SHOW_BRIGHTNESS_DIALOG =
            "com.android.intent.action.SHOW_BRIGHTNESS_DIALOG";

    /**
     * Intent Extra: holds boolean that determines whether brightness dialog is full width when
     * in landscape mode.
     * @hide
     */
    public static final String EXTRA_BRIGHTNESS_DIALOG_IS_FULL_WIDTH =
            "android.intent.extra.BRIGHTNESS_DIALOG_IS_FULL_WIDTH";

    /**
     * Activity Action: Shows the contrast setting dialog.
     * @hide
     */
    public static final String ACTION_SHOW_CONTRAST_DIALOG =
            "com.android.intent.action.SHOW_CONTRAST_DIALOG";

    /**
     * Broadcast Action:  A global button was pressed.  Includes a single
     * extra field, {@link #EXTRA_KEY_EVENT}, containing the key event that
     * caused the broadcast.
     * @hide
     */
    @SystemApi
    public static final String ACTION_GLOBAL_BUTTON = "android.intent.action.GLOBAL_BUTTON";

    /**
     * Broadcast Action: Sent when media resource is granted.
     * <p>
     * {@link #EXTRA_PACKAGES} specifies the packages on the process holding the media resource
     * granted.
     * </p>
     * <p class="note">
     * This is a protected intent that can only be sent by the system.
     * </p>
     * <p class="note">
     * This requires {@link android.Manifest.permission#RECEIVE_MEDIA_RESOURCE_USAGE} permission.
     * </p>
     *
     * @hide
     */
    public static final String ACTION_MEDIA_RESOURCE_GRANTED =
            "android.intent.action.MEDIA_RESOURCE_GRANTED";

    /**
     * Broadcast Action: An overlay package has changed. The data contains the
     * name of the overlay package which has changed. This is broadcast on all
     * changes to the OverlayInfo returned by {@link
     * android.content.om.IOverlayManager#getOverlayInfo(String, int)}. The
     * most common change is a state change that will change whether the
     * overlay is enabled or not.
     * @hide
     */
    public static final String ACTION_OVERLAY_CHANGED = "android.intent.action.OVERLAY_CHANGED";

    /**
     * Activity Action: Allow the user to select and return one or more existing
     * documents. When invoked, the system will display the various
     * {@link DocumentsProvider} instances installed on the device, letting the
     * user interactively navigate through them. These documents include local
     * media, such as photos and video, and documents provided by installed
     * cloud storage providers.
     * <p>
     * Each document is represented as a {@code content://} URI backed by a
     * {@link DocumentsProvider}, which can be opened as a stream with
     * {@link ContentResolver#openFileDescriptor(Uri, String)}, or queried for
     * {@link android.provider.DocumentsContract.Document} metadata.
     * <p>
     * All selected documents are returned to the calling application with
     * persistable read and write permission grants. If you want to maintain
     * access to the documents across device reboots, you need to explicitly
     * take the persistable permissions using
     * {@link ContentResolver#takePersistableUriPermission(Uri, int)}.
     * <p>
     * Callers must indicate the acceptable document MIME types through
     * {@link #setType(String)}. For example, to select photos, use
     * {@code image/*}. If multiple disjoint MIME types are acceptable, define
     * them in {@link #EXTRA_MIME_TYPES} and {@link #setType(String)} to
     * {@literal *}/*.
     * <p>
     * If the caller can handle multiple returned items (the user performing
     * multiple selection), then you can specify {@link #EXTRA_ALLOW_MULTIPLE}
     * to indicate this.
     * <p>
     * Callers must include {@link #CATEGORY_OPENABLE} in the Intent to obtain
     * URIs that can be opened with
     * {@link ContentResolver#openFileDescriptor(Uri, String)}.
     * <p>
     * Callers can set a document URI through
     * {@link DocumentsContract#EXTRA_INITIAL_URI} to indicate the initial
     * location of documents navigator. System will do its best to launch the
     * navigator in the specified document if it's a folder, or the folder that
     * contains the specified document if not.
     * <p>
     * Output: The URI of the item that was picked, returned in
     * {@link #getData()}. This must be a {@code content://} URI so that any
     * receiver can access it. If multiple documents were selected, they are
     * returned in {@link #getClipData()}.
     *
     * @see DocumentsContract
     * @see #ACTION_OPEN_DOCUMENT_TREE
     * @see #ACTION_CREATE_DOCUMENT
     * @see #FLAG_GRANT_PERSISTABLE_URI_PERMISSION
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_OPEN_DOCUMENT = "android.intent.action.OPEN_DOCUMENT";

    /**
     * Activity Action: Allow the user to create a new document. When invoked,
     * the system will display the various {@link DocumentsProvider} instances
     * installed on the device, letting the user navigate through them. The
     * returned document may be a newly created document with no content, or it
     * may be an existing document with the requested MIME type.
     * <p>
     * Each document is represented as a {@code content://} URI backed by a
     * {@link DocumentsProvider}, which can be opened as a stream with
     * {@link ContentResolver#openFileDescriptor(Uri, String)}, or queried for
     * {@link android.provider.DocumentsContract.Document} metadata.
     * <p>
     * Callers must indicate the concrete MIME type of the document being
     * created by setting {@link #setType(String)}. This MIME type cannot be
     * changed after the document is created.
     * <p>
     * Callers can provide an initial display name through {@link #EXTRA_TITLE},
     * but the user may change this value before creating the file.
     * <p>
     * Callers must include {@link #CATEGORY_OPENABLE} in the Intent to obtain
     * URIs that can be opened with
     * {@link ContentResolver#openFileDescriptor(Uri, String)}.
     * <p>
     * Callers can set a document URI through
     * {@link DocumentsContract#EXTRA_INITIAL_URI} to indicate the initial
     * location of documents navigator. System will do its best to launch the
     * navigator in the specified document if it's a folder, or the folder that
     * contains the specified document if not.
     * <p>
     * Output: The URI of the item that was created. This must be a
     * {@code content://} URI so that any receiver can access it.
     *
     * @see DocumentsContract
     * @see #ACTION_OPEN_DOCUMENT
     * @see #ACTION_OPEN_DOCUMENT_TREE
     * @see #FLAG_GRANT_PERSISTABLE_URI_PERMISSION
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_CREATE_DOCUMENT = "android.intent.action.CREATE_DOCUMENT";

    /**
     * Activity Action: Allow the user to pick a directory subtree. When
     * invoked, the system will display the various {@link DocumentsProvider}
     * instances installed on the device, letting the user navigate through
     * them. Apps can fully manage documents within the returned directory.
     * <p>
     * To gain access to descendant (child, grandchild, etc) documents, use
     * {@link DocumentsContract#buildDocumentUriUsingTree(Uri, String)} and
     * {@link DocumentsContract#buildChildDocumentsUriUsingTree(Uri, String)}
     * with the returned URI.
     * <p>
     * Callers can set a document URI through
     * {@link DocumentsContract#EXTRA_INITIAL_URI} to indicate the initial
     * location of documents navigator. System will do its best to launch the
     * navigator in the specified document if it's a folder, or the folder that
     * contains the specified document if not.
     * <p>
     * Output: The URI representing the selected directory tree.
     *
     * @see DocumentsContract
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String
            ACTION_OPEN_DOCUMENT_TREE = "android.intent.action.OPEN_DOCUMENT_TREE";


    /**
     * Activity Action: Perform text translation.
     * <p>
     * Input: {@link #EXTRA_TEXT getCharSequence(EXTRA_TEXT)} is the text to translate.
     * <p>
     * Output: nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_TRANSLATE = "android.intent.action.TRANSLATE";

    /**
     * Activity Action: Define the meaning of the selected word(s).
     * <p>
     * Input: {@link #EXTRA_TEXT getCharSequence(EXTRA_TEXT)} is the text to define.
     * <p>
     * Output: nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_DEFINE = "android.intent.action.DEFINE";

    /**
     * Broadcast Action: List of dynamic sensor is changed due to new sensor being connected or
     * exisiting sensor being disconnected.
     *
     * <p class="note">This is a protected intent that can only be sent by the system.</p>
     *
     * {@hide}
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String
            ACTION_DYNAMIC_SENSOR_CHANGED = "android.intent.action.DYNAMIC_SENSOR_CHANGED";

    /**
     * Deprecated - use ACTION_FACTORY_RESET instead.
     * @hide
     * @removed
     */
    @Deprecated
    @SystemApi
    public static final String ACTION_MASTER_CLEAR = "android.intent.action.MASTER_CLEAR";

    /**
     * Broadcast intent sent by the RecoverySystem to inform listeners that a global clear (wipe)
     * is about to be performed.
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @RequiresPermission(Manifest.permission.MASTER_CLEAR)
    public static final String ACTION_MASTER_CLEAR_NOTIFICATION
            = "android.intent.action.MASTER_CLEAR_NOTIFICATION";

    /**
     * Boolean intent extra to be used with {@link #ACTION_MASTER_CLEAR} in order to force a factory
     * reset even if {@link android.os.UserManager#DISALLOW_FACTORY_RESET} is set.
     *
     * <p>Deprecated - use {@link #EXTRA_FORCE_FACTORY_RESET} instead.
     *
     * @hide
     */
    @Deprecated
    public static final String EXTRA_FORCE_MASTER_CLEAR =
            "android.intent.extra.FORCE_MASTER_CLEAR";

    /**
     * A broadcast action to trigger a factory reset.
     *
     * <p>The sender must hold the {@link android.Manifest.permission#MASTER_CLEAR} permission. The
     * reason for the factory reset should be specified as {@link #EXTRA_REASON}.
     *
     * <p>Not for use by third-party applications.
     *
     * @see #EXTRA_FORCE_FACTORY_RESET
     *
     * {@hide}
     */
    @SystemApi
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_FACTORY_RESET = "android.intent.action.FACTORY_RESET";

    /**
     * Boolean intent extra to be used with {@link #ACTION_MASTER_CLEAR} in order to force a factory
     * reset even if {@link android.os.UserManager#DISALLOW_FACTORY_RESET} is set.
     *
     * <p>Not for use by third-party applications.
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_FORCE_FACTORY_RESET =
            "android.intent.extra.FORCE_FACTORY_RESET";

    /**
     * Broadcast action: report that a settings element is being restored from backup. The intent
     * contains four extras: EXTRA_SETTING_NAME is a string naming the restored setting,
     * EXTRA_SETTING_NEW_VALUE is the value being restored, EXTRA_SETTING_PREVIOUS_VALUE
     * is the value of that settings entry prior to the restore operation, and
     * EXTRA_SETTING_RESTORED_FROM_SDK_INT is the version of the SDK that the setting has been
     * restored from (corresponds to {@link android.os.Build.VERSION#SDK_INT}). The first three
     * values are represented as strings, the fourth one as int.
     *
     * <p>This broadcast is sent only for settings provider entries known to require special
     * handling around restore time to specific receivers. These entries are found in the
     * BROADCAST_ON_RESTORE table within the provider's backup agent implementation.
     *
     * @see #EXTRA_SETTING_NAME
     * @see #EXTRA_SETTING_PREVIOUS_VALUE
     * @see #EXTRA_SETTING_NEW_VALUE
     * @see #EXTRA_SETTING_RESTORED_FROM_SDK_INT
     * {@hide}
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @SuppressLint("ActionValue")
    public static final String ACTION_SETTING_RESTORED = "android.os.action.SETTING_RESTORED";

    /**
     * String intent extra to be used with {@link ACTION_SETTING_RESTORED}.
     * Contain the name of the restored setting.
     * {@hide}
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @SuppressLint("ActionValue")
    public static final String EXTRA_SETTING_NAME = "setting_name";

    /**
     * String intent extra to be used with {@link ACTION_SETTING_RESTORED}.
     * Contain the value of the {@link EXTRA_SETTING_NAME} settings entry prior to the restore
     * operation.
     * {@hide}
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @SuppressLint("ActionValue")
    public static final String EXTRA_SETTING_PREVIOUS_VALUE = "previous_value";

    /**
     * String intent extra to be used with {@link ACTION_SETTING_RESTORED}.
     * Contain the value of the {@link EXTRA_SETTING_NAME} settings entry being restored.
     * {@hide}
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @SuppressLint("ActionValue")
    public static final String EXTRA_SETTING_NEW_VALUE = "new_value";

    /**
     * Int intent extra to be used with {@link ACTION_SETTING_RESTORED}.
     * Contain the version of the SDK that the setting has been restored from (corresponds to
     * {@link android.os.Build.VERSION#SDK_INT}).
     * {@hide}
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @SuppressLint("ActionValue")
    public static final String EXTRA_SETTING_RESTORED_FROM_SDK_INT = "restored_from_sdk_int";

    /**
     * Activity Action: Process a piece of text.
     * <p>Input: {@link #EXTRA_PROCESS_TEXT} contains the text to be processed.
     * {@link #EXTRA_PROCESS_TEXT_READONLY} states if the resulting text will be read-only.</p>
     * <p>Output: {@link #EXTRA_PROCESS_TEXT} contains the processed text.</p>
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_PROCESS_TEXT = "android.intent.action.PROCESS_TEXT";

    /**
     * Broadcast Action: The sim card state has changed.
     * For more details see TelephonyIntents.ACTION_SIM_STATE_CHANGED. This is here
     * because TelephonyIntents is an internal class.
     * The intent will have following extras.</p>
     * <p>
     * @see #EXTRA_SIM_STATE
     * @see #EXTRA_SIM_LOCKED_REASON
     * @see #EXTRA_REBROADCAST_ON_UNLOCK
     *
     * @deprecated Use {@link android.telephony.TelephonyManager#ACTION_SIM_CARD_STATE_CHANGED} or
     * {@link android.telephony.TelephonyManager#ACTION_SIM_APPLICATION_STATE_CHANGED}
     *
     * @hide
     */
    @Deprecated
    @SystemApi
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_SIM_STATE_CHANGED = "android.intent.action.SIM_STATE_CHANGED";

    /**
     * The extra used with {@link #ACTION_SIM_STATE_CHANGED} for broadcasting SIM STATE.
     * This will have one of the following intent values.
     * @see #SIM_STATE_UNKNOWN
     * @see #SIM_STATE_NOT_READY
     * @see #SIM_STATE_ABSENT
     * @see #SIM_STATE_PRESENT
     * @see #SIM_STATE_CARD_IO_ERROR
     * @see #SIM_STATE_CARD_RESTRICTED
     * @see #SIM_STATE_LOCKED
     * @see #SIM_STATE_READY
     * @see #SIM_STATE_IMSI
     * @see #SIM_STATE_LOADED
     * @hide
     * @deprecated Use {@link android.telephony.TelephonyManager#ACTION_SIM_CARD_STATE_CHANGED}
     */
    public static final String EXTRA_SIM_STATE = "ss";

    /**
     * The intent value UNKNOWN represents the SIM state unknown
     * @hide
     * @deprecated Use {@link android.telephony.TelephonyManager#ACTION_SIM_CARD_STATE_CHANGED}
     */
    public static final String SIM_STATE_UNKNOWN = "UNKNOWN";

    /**
     * The intent value NOT_READY means that the SIM is not ready eg. radio is off or powering on
     * @hide
     * @deprecated Use {@link android.telephony.TelephonyManager#ACTION_SIM_CARD_STATE_CHANGED}
     */
    public static final String SIM_STATE_NOT_READY = "NOT_READY";

    /**
     * The intent value ABSENT means the SIM card is missing
     * @hide
     * @deprecated Use {@link android.telephony.TelephonyManager#ACTION_SIM_CARD_STATE_CHANGED}
     */
    public static final String SIM_STATE_ABSENT = "ABSENT";

    /**
     * The intent value PRESENT means the device has a SIM card inserted
     * @hide
     * @deprecated Use {@link android.telephony.TelephonyManager#ACTION_SIM_CARD_STATE_CHANGED}
     */
    public static final String SIM_STATE_PRESENT = "PRESENT";

    /**
     * The intent value CARD_IO_ERROR means for three consecutive times there was SIM IO error
     * @hide
     * @deprecated Use {@link android.telephony.TelephonyManager#ACTION_SIM_CARD_STATE_CHANGED}
     */
    static public final String SIM_STATE_CARD_IO_ERROR = "CARD_IO_ERROR";

    /**
     * The intent value CARD_RESTRICTED means card is present but not usable due to carrier
     * restrictions
     * @hide
     * @deprecated Use {@link android.telephony.TelephonyManager#ACTION_SIM_CARD_STATE_CHANGED}
     */
    static public final String SIM_STATE_CARD_RESTRICTED = "CARD_RESTRICTED";

    /**
     * The intent value LOCKED means the SIM is locked by PIN or by network
     * @hide
     * @deprecated Use {@link android.telephony.TelephonyManager#ACTION_SIM_CARD_STATE_CHANGED}
     */
    public static final String SIM_STATE_LOCKED = "LOCKED";

    /**
     * The intent value READY means the SIM is ready to be accessed
     * @hide
     * @deprecated Use {@link android.telephony.TelephonyManager#ACTION_SIM_CARD_STATE_CHANGED}
     */
    public static final String SIM_STATE_READY = "READY";

    /**
     * The intent value IMSI means the SIM IMSI is ready in property
     * @hide
     * @deprecated Use {@link android.telephony.TelephonyManager#ACTION_SIM_CARD_STATE_CHANGED}
     */
    public static final String SIM_STATE_IMSI = "IMSI";

    /**
     * The intent value LOADED means all SIM records, including IMSI, are loaded
     * @hide
     * @deprecated Use {@link android.telephony.TelephonyManager#ACTION_SIM_CARD_STATE_CHANGED}
     */
    public static final String SIM_STATE_LOADED = "LOADED";

    /**
     * The extra used with {@link #ACTION_SIM_STATE_CHANGED} for broadcasting SIM STATE.
     * This extra will have one of the following intent values.
     * <p>
     * @see #SIM_LOCKED_ON_PIN
     * @see #SIM_LOCKED_ON_PUK
     * @see #SIM_LOCKED_NETWORK
     * @see #SIM_ABSENT_ON_PERM_DISABLED
     *
     * @hide
     * @deprecated Use
     * {@link android.telephony.TelephonyManager#ACTION_SIM_APPLICATION_STATE_CHANGED}
     */
    public static final String EXTRA_SIM_LOCKED_REASON = "reason";

    /**
     * The intent value PIN means the SIM is locked on PIN1
     * @hide
     * @deprecated Use
     * {@link android.telephony.TelephonyManager#ACTION_SIM_APPLICATION_STATE_CHANGED}
     */
    public static final String SIM_LOCKED_ON_PIN = "PIN";

    /**
     * The intent value PUK means the SIM is locked on PUK1
     * @hide
     * @deprecated Use
     * {@link android.telephony.TelephonyManager#ACTION_SIM_APPLICATION_STATE_CHANGED}
     */
    /* PUK means ICC is locked on PUK1 */
    public static final String SIM_LOCKED_ON_PUK = "PUK";

    /**
     * The intent value NETWORK means the SIM is locked on NETWORK PERSONALIZATION
     * @hide
     * @deprecated Use
     * {@link android.telephony.TelephonyManager#ACTION_SIM_APPLICATION_STATE_CHANGED}
     */
    public static final String SIM_LOCKED_NETWORK = "NETWORK";

    /**
     * The intent value PERM_DISABLED means SIM is permanently disabled due to puk fails
     * @hide
     * @deprecated Use
     * {@link android.telephony.TelephonyManager#ACTION_SIM_APPLICATION_STATE_CHANGED}
     */
    public static final String SIM_ABSENT_ON_PERM_DISABLED = "PERM_DISABLED";

    /**
     * The extra used with {@link #ACTION_SIM_STATE_CHANGED} for indicating whether this broadcast
     * is a rebroadcast on unlock. Defaults to {@code false} if not specified.
     *
     * @hide
     * @deprecated Use {@link android.telephony.TelephonyManager#ACTION_SIM_CARD_STATE_CHANGED} or
     * {@link android.telephony.TelephonyManager#ACTION_SIM_APPLICATION_STATE_CHANGED}
     */
    public static final String EXTRA_REBROADCAST_ON_UNLOCK = "rebroadcastOnUnlock";

    /**
     * Broadcast Action: indicate that the phone service state has changed.
     * The intent will have the following extra values:</p>
     * <p>
     * @see #EXTRA_VOICE_REG_STATE
     * @see #EXTRA_DATA_REG_STATE
     * @see #EXTRA_VOICE_ROAMING_TYPE
     * @see #EXTRA_DATA_ROAMING_TYPE
     * @see #EXTRA_OPERATOR_ALPHA_LONG
     * @see #EXTRA_OPERATOR_ALPHA_SHORT
     * @see #EXTRA_OPERATOR_NUMERIC
     * @see #EXTRA_DATA_OPERATOR_ALPHA_LONG
     * @see #EXTRA_DATA_OPERATOR_ALPHA_SHORT
     * @see #EXTRA_DATA_OPERATOR_NUMERIC
     * @see #EXTRA_MANUAL
     * @see #EXTRA_VOICE_RADIO_TECH
     * @see #EXTRA_DATA_RADIO_TECH
     * @see #EXTRA_CSS_INDICATOR
     * @see #EXTRA_NETWORK_ID
     * @see #EXTRA_SYSTEM_ID
     * @see #EXTRA_CDMA_ROAMING_INDICATOR
     * @see #EXTRA_CDMA_DEFAULT_ROAMING_INDICATOR
     * @see #EXTRA_EMERGENCY_ONLY
     * @see #EXTRA_IS_DATA_ROAMING_FROM_REGISTRATION
     * @see #EXTRA_IS_USING_CARRIER_AGGREGATION
     * @see #EXTRA_LTE_EARFCN_RSRP_BOOST
     *
     * <p class="note">
     * Requires the READ_PHONE_STATE permission.
     *
     * <p class="note">This is a protected intent that can only be sent by the system.
     * @hide
     * @removed
     * @deprecated Use {@link android.provider.Telephony.ServiceStateTable} and the helper
     * functions {@code ServiceStateTable.getUriForSubscriptionIdAndField} and
     * {@code ServiceStateTable.getUriForSubscriptionId} to subscribe to changes to the ServiceState
     * for a given subscription id and field with a ContentObserver or using JobScheduler.
     */
    @Deprecated
    @SystemApi
    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_SERVICE_STATE = "android.intent.action.SERVICE_STATE";

    /**
     * Used by {@link services.core.java.com.android.server.pm.DataLoaderManagerService}
     * for querying Data Loader Service providers. Data loader service providers register this
     * intent filter in their manifests, so that they can be looked up and bound to by
     * {@code DataLoaderManagerService}.
     *
     * <p class="note">This is a protected intent that can only be sent by the system.
     *
     * Data loader service providers must be privileged apps.
     * See {@link com.android.server.pm.PackageManagerShellCommandDataLoader} as an example of such
     * data loader service provider.
     *
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String ACTION_LOAD_DATA = "android.intent.action.LOAD_DATA";

    /**
     * An int extra used with {@link #ACTION_SERVICE_STATE} which indicates voice registration
     * state.
     * @see android.telephony.ServiceState#STATE_EMERGENCY_ONLY
     * @see android.telephony.ServiceState#STATE_IN_SERVICE
     * @see android.telephony.ServiceState#STATE_OUT_OF_SERVICE
     * @see android.telephony.ServiceState#STATE_POWER_OFF
     * @hide
     * @removed
     * @deprecated Use {@link android.provider.Telephony.ServiceStateTable#VOICE_REG_STATE}.
     */
    @Deprecated
    @SystemApi
    public static final String EXTRA_VOICE_REG_STATE = "voiceRegState";

    /**
     * An int extra used with {@link #ACTION_SERVICE_STATE} which indicates data registration state.
     * @see android.telephony.ServiceState#STATE_EMERGENCY_ONLY
     * @see android.telephony.ServiceState#STATE_IN_SERVICE
     * @see android.telephony.ServiceState#STATE_OUT_OF_SERVICE
     * @see android.telephony.ServiceState#STATE_POWER_OFF
     * @hide
     * @removed
     * @deprecated Use {@link android.provider.Telephony.ServiceStateTable#DATA_REG_STATE}.
     */
    @Deprecated
    @SystemApi
    public static final String EXTRA_DATA_REG_STATE = "dataRegState";

    /**
     * An integer extra used with {@link #ACTION_SERVICE_STATE} which indicates the voice roaming
     * type.
     * @hide
     * @removed
     * @deprecated Use {@link android.provider.Telephony.ServiceStateTable#VOICE_ROAMING_TYPE}.
     */
    @Deprecated
    @SystemApi
    public static final String EXTRA_VOICE_ROAMING_TYPE = "voiceRoamingType";

    /**
     * An integer extra used with {@link #ACTION_SERVICE_STATE} which indicates the data roaming
     * type.
     * @hide
     * @removed
     * @deprecated Use {@link android.provider.Telephony.ServiceStateTable#DATA_ROAMING_TYPE}.
     */
    @Deprecated
    @SystemApi
    public static final String EXTRA_DATA_ROAMING_TYPE = "dataRoamingType";

    /**
     * A string extra used with {@link #ACTION_SERVICE_STATE} which represents the current
     * registered voice operator name in long alphanumeric format.
     * {@code null} if the operator name is not known or unregistered.
     * @hide
     * @removed
     * @deprecated Use
     * {@link android.provider.Telephony.ServiceStateTable#VOICE_OPERATOR_ALPHA_LONG}.
     */
    @Deprecated
    @SystemApi
    public static final String EXTRA_OPERATOR_ALPHA_LONG = "operator-alpha-long";

    /**
     * A string extra used with {@link #ACTION_SERVICE_STATE} which represents the current
     * registered voice operator name in short alphanumeric format.
     * {@code null} if the operator name is not known or unregistered.
     * @hide
     * @removed
     * @deprecated Use
     * {@link android.provider.Telephony.ServiceStateTable#VOICE_OPERATOR_ALPHA_SHORT}.
     */
    @Deprecated
    @SystemApi
    public static final String EXTRA_OPERATOR_ALPHA_SHORT = "operator-alpha-short";

    /**
     * A string extra used with {@link #ACTION_SERVICE_STATE} containing the MCC
     * (Mobile Country Code, 3 digits) and MNC (Mobile Network code, 2-3 digits) for the mobile
     * network.
     * @hide
     * @removed
     * @deprecated Use {@link android.provider.Telephony.ServiceStateTable#VOICE_OPERATOR_NUMERIC}.
     */
    @Deprecated
    @SystemApi
    public static final String EXTRA_OPERATOR_NUMERIC = "operator-numeric";

    /**
     * A string extra used with {@link #ACTION_SERVICE_STATE} which represents the current
     * registered data operator name in long alphanumeric format.
     * {@code null} if the operator name is not known or unregistered.
     * @hide
     * @removed
     * @deprecated Use
     * {@link android.provider.Telephony.ServiceStateTable#DATA_OPERATOR_ALPHA_LONG}.
     */
    @Deprecated
    @SystemApi
    public static final String EXTRA_DATA_OPERATOR_ALPHA_LONG = "data-operator-alpha-long";

    /**
     * A string extra used with {@link #ACTION_SERVICE_STATE} which represents the current
     * registered data operator name in short alphanumeric format.
     * {@code null} if the operator name is not known or unregistered.
     * @hide
     * @removed
     * @deprecated Use
     * {@link android.provider.Telephony.ServiceStateTable#DATA_OPERATOR_ALPHA_SHORT}.
     */
    @Deprecated
    @SystemApi
    public static final String EXTRA_DATA_OPERATOR_ALPHA_SHORT = "data-operator-alpha-short";

    /**
     * A string extra used with {@link #ACTION_SERVICE_STATE} containing the MCC
     * (Mobile Country Code, 3 digits) and MNC (Mobile Network code, 2-3 digits) for the
     * data operator.
     * @hide
     * @removed
     * @deprecated Use {@link android.provider.Telephony.ServiceStateTable#DATA_OPERATOR_NUMERIC}.
     */
    @Deprecated
    @SystemApi
    public static final String EXTRA_DATA_OPERATOR_NUMERIC = "data-operator-numeric";

    /**
     * A boolean extra used with {@link #ACTION_SERVICE_STATE} which indicates whether the current
     * network selection mode is manual.
     * Will be {@code true} if manual mode, {@code false} if automatic mode.
     * @hide
     * @removed
     * @deprecated Use
     * {@link android.provider.Telephony.ServiceStateTable#IS_MANUAL_NETWORK_SELECTION}.
     */
    @Deprecated
    @SystemApi
    public static final String EXTRA_MANUAL = "manual";

    /**
     * An integer extra used with {@link #ACTION_SERVICE_STATE} which represents the current voice
     * radio technology.
     * @hide
     * @removed
     * @deprecated Use
     * {@link android.provider.Telephony.ServiceStateTable#RIL_VOICE_RADIO_TECHNOLOGY}.
     */
    @Deprecated
    @SystemApi
    public static final String EXTRA_VOICE_RADIO_TECH = "radioTechnology";

    /**
     * An integer extra used with {@link #ACTION_SERVICE_STATE} which represents the current data
     * radio technology.
     * @hide
     * @removed
     * @deprecated Use
     * {@link android.provider.Telephony.ServiceStateTable#RIL_DATA_RADIO_TECHNOLOGY}.
     */
    @Deprecated
    @SystemApi
    public static final String EXTRA_DATA_RADIO_TECH = "dataRadioTechnology";

    /**
     * A boolean extra used with {@link #ACTION_SERVICE_STATE} which represents concurrent service
     * support on CDMA network.
     * Will be {@code true} if support, {@code false} otherwise.
     * @hide
     * @removed
     * @deprecated Use {@link android.provider.Telephony.ServiceStateTable#CSS_INDICATOR}.
     */
    @Deprecated
    @SystemApi
    public static final String EXTRA_CSS_INDICATOR = "cssIndicator";

    /**
     * An integer extra used with {@link #ACTION_SERVICE_STATE} which represents the CDMA network
     * id. {@code Integer.MAX_VALUE} if unknown.
     * @hide
     * @removed
     * @deprecated Use {@link android.provider.Telephony.ServiceStateTable#NETWORK_ID}.
     */
    @Deprecated
    @SystemApi
    public static final String EXTRA_NETWORK_ID = "networkId";

    /**
     * An integer extra used with {@link #ACTION_SERVICE_STATE} which represents the CDMA system id.
     * {@code Integer.MAX_VALUE} if unknown.
     * @hide
     * @removed
     * @deprecated Use {@link android.provider.Telephony.ServiceStateTable#SYSTEM_ID}.
     */
    @Deprecated
    @SystemApi
    public static final String EXTRA_SYSTEM_ID = "systemId";

    /**
     * An integer extra used with {@link #ACTION_SERVICE_STATE} represents the TSB-58 roaming
     * indicator if registered on a CDMA or EVDO system or {@code -1} if not.
     * @hide
     * @removed
     * @deprecated Use {@link android.provider.Telephony.ServiceStateTable#CDMA_ROAMING_INDICATOR}.
     */
    @Deprecated
    @SystemApi
    public static final String EXTRA_CDMA_ROAMING_INDICATOR = "cdmaRoamingIndicator";

    /**
     * An integer extra used with {@link #ACTION_SERVICE_STATE} represents the default roaming
     * indicator from the PRL if registered on a CDMA or EVDO system {@code -1} if not.
     * @hide
     * @removed
     * @deprecated Use
     * {@link android.provider.Telephony.ServiceStateTable#CDMA_DEFAULT_ROAMING_INDICATOR}.
     */
    @Deprecated
    @SystemApi
    public static final String EXTRA_CDMA_DEFAULT_ROAMING_INDICATOR = "cdmaDefaultRoamingIndicator";

    /**
     * A boolean extra used with {@link #ACTION_SERVICE_STATE} which indicates if under emergency
     * only mode.
     * {@code true} if in emergency only mode, {@code false} otherwise.
     * @hide
     * @removed
     * @deprecated Use {@link android.provider.Telephony.ServiceStateTable#IS_EMERGENCY_ONLY}.
     */
    @Deprecated
    @SystemApi
    public static final String EXTRA_EMERGENCY_ONLY = "emergencyOnly";

    /**
     * A boolean extra used with {@link #ACTION_SERVICE_STATE} which indicates whether data network
     * registration state is roaming.
     * {@code true} if registration indicates roaming, {@code false} otherwise
     * @hide
     * @removed
     * @deprecated Use
     * {@link android.provider.Telephony.ServiceStateTable#IS_DATA_ROAMING_FROM_REGISTRATION}.
     */
    @Deprecated
    @SystemApi
    public static final String EXTRA_IS_DATA_ROAMING_FROM_REGISTRATION =
            "isDataRoamingFromRegistration";

    /**
     * A boolean extra used with {@link #ACTION_SERVICE_STATE} which indicates if carrier
     * aggregation is in use.
     * {@code true} if carrier aggregation is in use, {@code false} otherwise.
     * @hide
     * @removed
     * @deprecated Use
     * {@link android.provider.Telephony.ServiceStateTable#IS_USING_CARRIER_AGGREGATION}.
     */
    @Deprecated
    @SystemApi
    public static final String EXTRA_IS_USING_CARRIER_AGGREGATION = "isUsingCarrierAggregation";

    /**
     * An integer extra used with {@link #ACTION_SERVICE_STATE} representing the offset which
     * is reduced from the rsrp threshold while calculating signal strength level.
     * @hide
     * @removed
     */
    @Deprecated
    @SystemApi
    public static final String EXTRA_LTE_EARFCN_RSRP_BOOST = "LteEarfcnRsrpBoost";

    /**
     * The name of the extra used to define the text to be processed, as a
     * CharSequence. Note that this may be a styled CharSequence, so you must use
     * {@link Bundle#getCharSequence(String) Bundle.getCharSequence()} to retrieve it.
     */
    public static final String EXTRA_PROCESS_TEXT = "android.intent.extra.PROCESS_TEXT";
    /**
     * The name of the boolean extra used to define if the processed text will be used as read-only.
     */
    public static final String EXTRA_PROCESS_TEXT_READONLY =
            "android.intent.extra.PROCESS_TEXT_READONLY";

    /**
     * Broadcast action: reports when a new thermal event has been reached. When the device
     * is reaching its maximum temperatue, the thermal level reported
     * {@hide}
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_THERMAL_EVENT = "android.intent.action.THERMAL_EVENT";

    /** {@hide} */
    public static final String EXTRA_THERMAL_STATE = "android.intent.extra.THERMAL_STATE";

    /**
     * Thermal state when the device is normal. This state is sent in the
     * {@link #ACTION_THERMAL_EVENT} broadcast as {@link #EXTRA_THERMAL_STATE}.
     * {@hide}
     */
    public static final int EXTRA_THERMAL_STATE_NORMAL = 0;

    /**
     * Thermal state where the device is approaching its maximum threshold. This state is sent in
     * the {@link #ACTION_THERMAL_EVENT} broadcast as {@link #EXTRA_THERMAL_STATE}.
     * {@hide}
     */
    public static final int EXTRA_THERMAL_STATE_WARNING = 1;

    /**
     * Thermal state where the device has reached its maximum threshold. This state is sent in the
     * {@link #ACTION_THERMAL_EVENT} broadcast as {@link #EXTRA_THERMAL_STATE}.
     * {@hide}
     */
    public static final int EXTRA_THERMAL_STATE_EXCEEDED = 2;

    /**
     * Broadcast Action: Indicates the dock in idle state while device is docked.
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     *
     * @hide
     */
    public static final String ACTION_DOCK_IDLE = "android.intent.action.DOCK_IDLE";

    /**
     * Broadcast Action: Indicates the dock in active state while device is docked.
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     *
     * @hide
     */
    public static final String ACTION_DOCK_ACTIVE = "android.intent.action.DOCK_ACTIVE";

    /**
     * Broadcast Action: Indicates that a new device customization has been
     * downloaded and applied (packages installed, runtime resource overlays
     * enabled, xml files copied, ...), and that it is time for components that
     * need to for example clear their caches to do so now.
     *
     * @hide
     */
    @SystemApi
    public static final String ACTION_DEVICE_CUSTOMIZATION_READY =
            "android.intent.action.DEVICE_CUSTOMIZATION_READY";


    /**
     * Activity Action: Display an activity state associated with an unique {@link LocusId}.
     *
     * <p>For example, a chat app could use the context to resume a conversation between 2 users.
     *
     * <p>Input: {@link #EXTRA_LOCUS_ID} specifies the unique identifier of the locus in the
     * app domain. Should be stable across reboots and backup / restore.
     * <p>Output: nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_VIEW_LOCUS = "android.intent.action.VIEW_LOCUS";

    /**
     * Activity Action: Starts a note-taking activity that can be used to create a note. This action
     * can be used to start an activity on the lock screen. Activity should ensure to appropriately
     * handle privacy sensitive data and features when launched on the lock screen. See
     * {@link android.app.KeyguardManager} for lock screen checks.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_CREATE_NOTE = "android.intent.action.CREATE_NOTE";

    /**
     * A boolean extra used with {@link #ACTION_CREATE_NOTE} indicating whether the launched
     * note-taking activity should show a UI that is suitable to use with stylus input.
     */
    public static final String EXTRA_USE_STYLUS_MODE = "android.intent.extra.USE_STYLUS_MODE";

    /**
     * Activity Action: Use with startActivityForResult to start a system activity that captures
     * content on the screen to take a screenshot and present it to the user for editing. The
     * edited screenshot is saved on device and returned to the calling activity as a {@link Uri}
     * through {@link #getData()}. User interaction is required to return the edited screenshot to
     * the calling activity.
     *
     * <p>This intent action requires the permission
     * {@link android.Manifest.permission#LAUNCH_CAPTURE_CONTENT_ACTIVITY_FOR_NOTE}.
     *
     * <p>Callers should query
     * {@link StatusBarManager#canLaunchCaptureContentActivityForNote(Activity)} before showing a UI
     * element that allows users to trigger this flow.
     */
    @RequiresPermission(Manifest.permission.LAUNCH_CAPTURE_CONTENT_ACTIVITY_FOR_NOTE)
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_LAUNCH_CAPTURE_CONTENT_ACTIVITY_FOR_NOTE =
            "android.intent.action.LAUNCH_CAPTURE_CONTENT_ACTIVITY_FOR_NOTE";

    /**
     * An int extra used by activity started with
     * {@link #ACTION_LAUNCH_CAPTURE_CONTENT_ACTIVITY_FOR_NOTE} to indicate status of the response.
     * This extra is used along with result code set to {@link android.app.Activity#RESULT_OK}.
     *
     * <p>The value for this extra can be one of the following:
     * <ul>
     *     <li>{@link #CAPTURE_CONTENT_FOR_NOTE_SUCCESS}</li>
     *     <li>{@link #CAPTURE_CONTENT_FOR_NOTE_FAILED}</li>
     *     <li>{@link #CAPTURE_CONTENT_FOR_NOTE_USER_CANCELED}</li>
     *     <li>{@link #CAPTURE_CONTENT_FOR_NOTE_WINDOW_MODE_UNSUPPORTED}</li>
     *     <li>{@link #CAPTURE_CONTENT_FOR_NOTE_BLOCKED_BY_ADMIN}</li>
     * </ul>
     */
    public static final String EXTRA_CAPTURE_CONTENT_FOR_NOTE_STATUS_CODE =
            "android.intent.extra.CAPTURE_CONTENT_FOR_NOTE_STATUS_CODE";

    /**
     * A response code used with {@link #EXTRA_CAPTURE_CONTENT_FOR_NOTE_STATUS_CODE} to indicate
     * that the request was a success.
     *
     * <p>This code will only be returned after the user has interacted with the system screenshot
     * activity to consent to sharing the data with the note.
     *
     * <p>The captured screenshot is returned as a {@link Uri} through {@link #getData()}.
     */
    public static final int CAPTURE_CONTENT_FOR_NOTE_SUCCESS = 0;

    /**
     * A response code used with {@link #EXTRA_CAPTURE_CONTENT_FOR_NOTE_STATUS_CODE} to indicate
     * that something went wrong.
     */
    public static final int CAPTURE_CONTENT_FOR_NOTE_FAILED = 1;

    /**
     * A response code used with {@link #EXTRA_CAPTURE_CONTENT_FOR_NOTE_STATUS_CODE} to indicate
     * that user canceled the content capture flow.
     */
    public static final int CAPTURE_CONTENT_FOR_NOTE_USER_CANCELED = 2;

    /**
     * A response code used with {@link #EXTRA_CAPTURE_CONTENT_FOR_NOTE_STATUS_CODE} to indicate
     * that the intent action {@link #ACTION_LAUNCH_CAPTURE_CONTENT_ACTIVITY_FOR_NOTE} was started
     * by an activity that is running in a non-supported window mode.
     */
    public static final int CAPTURE_CONTENT_FOR_NOTE_WINDOW_MODE_UNSUPPORTED = 3;

    /**
     * A response code used with {@link #EXTRA_CAPTURE_CONTENT_FOR_NOTE_STATUS_CODE} to indicate
     * that screenshot is blocked by IT admin.
     */
    public static final int CAPTURE_CONTENT_FOR_NOTE_BLOCKED_BY_ADMIN = 4;

    /** @hide */
    @IntDef(value = {
            CAPTURE_CONTENT_FOR_NOTE_SUCCESS, CAPTURE_CONTENT_FOR_NOTE_FAILED,
            CAPTURE_CONTENT_FOR_NOTE_WINDOW_MODE_UNSUPPORTED,
            CAPTURE_CONTENT_FOR_NOTE_BLOCKED_BY_ADMIN})
    @Retention(RetentionPolicy.SOURCE)
    public @interface CaptureContentForNoteStatusCodes {}

    /**
     * Broadcast Action: Sent to the integrity component when a package
     * needs to be verified. The data contains the package URI along with other relevant
     * information.
     *
     * <p class="note">
     * This is a protected intent that can only be sent by the system.
     * </p>
     *
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PACKAGE_NEEDS_INTEGRITY_VERIFICATION =
            "android.intent.action.PACKAGE_NEEDS_INTEGRITY_VERIFICATION";

    /**
     * Broadcast Action: Start the foreground service manager.
     *
     * <p class="note">
     * This is a protected intent that can only be sent by the system.
     * </p>
     *
     * @hide
     */
    public static final String ACTION_SHOW_FOREGROUND_SERVICE_MANAGER =
            "android.intent.action.SHOW_FOREGROUND_SERVICE_MANAGER";

    /**
     * Broadcast Action: Sent to the responsible installer of an archived package when unarchival
     * is requested.
     *
     * @see android.content.pm.PackageInstaller#requestUnarchive(String)
     * @hide
     */
    @SystemApi
    @FlaggedApi(android.content.pm.Flags.FLAG_ARCHIVING)
    public static final String ACTION_UNARCHIVE_PACKAGE = "android.intent.action.UNARCHIVE_PACKAGE";

    // ---------------------------------------------------------------------
    // ---------------------------------------------------------------------
    // Standard intent categories (see addCategory()).

    /**
     * Set if the activity should be an option for the default action
     * (center press) to perform on a piece of data.  Setting this will
     * hide from the user any activities without it set when performing an
     * action on some data.  Note that this is normally -not- set in the
     * Intent when initiating an action -- it is for use in intent filters
     * specified in packages.
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_DEFAULT = "android.intent.category.DEFAULT";
    /**
     * Activities that can be safely invoked from a browser must support this
     * category.  For example, if the user is viewing a web page or an e-mail
     * and clicks on a link in the text, the Intent generated execute that
     * link will require the BROWSABLE category, so that only activities
     * supporting this category will be considered as possible actions.  By
     * supporting this category, you are promising that there is nothing
     * damaging (without user intervention) that can happen by invoking any
     * matching Intent.
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_BROWSABLE = "android.intent.category.BROWSABLE";
    /**
     * Categories for activities that can participate in voice interaction.
     * An activity that supports this category must be prepared to run with
     * no UI shown at all (though in some case it may have a UI shown), and
     * rely on {@link android.app.VoiceInteractor} to interact with the user.
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_VOICE = "android.intent.category.VOICE";
    /**
     * Set if the activity should be considered as an alternative action to
     * the data the user is currently viewing.  See also
     * {@link #CATEGORY_SELECTED_ALTERNATIVE} for an alternative action that
     * applies to the selection in a list of items.
     *
     * <p>Supporting this category means that you would like your activity to be
     * displayed in the set of alternative things the user can do, usually as
     * part of the current activity's options menu.  You will usually want to
     * include a specific label in the &lt;intent-filter&gt; of this action
     * describing to the user what it does.
     *
     * <p>The action of IntentFilter with this category is important in that it
     * describes the specific action the target will perform.  This generally
     * should not be a generic action (such as {@link #ACTION_VIEW}, but rather
     * a specific name such as "com.android.camera.action.CROP.  Only one
     * alternative of any particular action will be shown to the user, so using
     * a specific action like this makes sure that your alternative will be
     * displayed while also allowing other applications to provide their own
     * overrides of that particular action.
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_ALTERNATIVE = "android.intent.category.ALTERNATIVE";
    /**
     * Set if the activity should be considered as an alternative selection
     * action to the data the user has currently selected.  This is like
     * {@link #CATEGORY_ALTERNATIVE}, but is used in activities showing a list
     * of items from which the user can select, giving them alternatives to the
     * default action that will be performed on it.
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_SELECTED_ALTERNATIVE = "android.intent.category.SELECTED_ALTERNATIVE";
    /**
     * Intended to be used as a tab inside of a containing TabActivity.
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_TAB = "android.intent.category.TAB";
    /**
     * Should be displayed in the top-level launcher.
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER";
    /**
     * Indicates an activity optimized for Leanback mode, and that should
     * be displayed in the Leanback launcher.
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_LEANBACK_LAUNCHER = "android.intent.category.LEANBACK_LAUNCHER";
    /**
     * Indicates the preferred entry-point activity when an application is launched from a Car
     * launcher. If not present, Car launcher can optionally use {@link #CATEGORY_LAUNCHER} as a
     * fallback, or exclude the application entirely.
     * @hide
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_CAR_LAUNCHER = "android.intent.category.CAR_LAUNCHER";
    /**
     * Used to indicate that the activity can be used in communal mode.
     * @hide
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_COMMUNAL_MODE = "android.intent.category.COMMUNAL_MODE";
    /**
     * Indicates a Leanback settings activity to be displayed in the Leanback launcher.
     * @hide
     */
    @SystemApi
    public static final String CATEGORY_LEANBACK_SETTINGS = "android.intent.category.LEANBACK_SETTINGS";
    /**
     * Provides information about the package it is in; typically used if
     * a package does not contain a {@link #CATEGORY_LAUNCHER} to provide
     * a front-door to the user without having to be shown in the all apps list.
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_INFO = "android.intent.category.INFO";
    /**
     * This is the home activity, that is the first activity that is displayed
     * when the device boots.
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_HOME = "android.intent.category.HOME";
    /**
     * This is the home activity that is displayed when the device is finished setting up and ready
     * for use.
     * @hide
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_HOME_MAIN = "android.intent.category.HOME_MAIN";
    /**
     * The home activity shown on secondary displays that support showing home activities.
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_SECONDARY_HOME = "android.intent.category.SECONDARY_HOME";
    /**
     * This is the setup wizard activity, that is the first activity that is displayed
     * when the user sets up the device for the first time.
     * @hide
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_SETUP_WIZARD = "android.intent.category.SETUP_WIZARD";
    /**
     * This is the home activity, that is the activity that serves as the launcher app
     * from there the user can start other apps. Often components with lower/higher
     * priority intent filters handle the home intent, for example SetupWizard, to
     * setup the device and we need to be able to distinguish the home app from these
     * setup helpers.
     * @hide
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_LAUNCHER_APP = "android.intent.category.LAUNCHER_APP";
    /**
     * This activity is a preference panel.
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_PREFERENCE = "android.intent.category.PREFERENCE";
    /**
     * This activity is a development preference panel.
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_DEVELOPMENT_PREFERENCE = "android.intent.category.DEVELOPMENT_PREFERENCE";
    /**
     * Capable of running inside a parent activity container.
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_EMBED = "android.intent.category.EMBED";
    /**
     * This activity allows the user to browse and download new applications.
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_APP_MARKET = "android.intent.category.APP_MARKET";
    /**
     * This activity may be exercised by the monkey or other automated test tools.
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_MONKEY = "android.intent.category.MONKEY";
    /**
     * To be used as a test (not part of the normal user experience).
     */
    public static final String CATEGORY_TEST = "android.intent.category.TEST";
    /**
     * To be used as a unit test (run through the Test Harness).
     */
    public static final String CATEGORY_UNIT_TEST = "android.intent.category.UNIT_TEST";
    /**
     * To be used as a sample code example (not part of the normal user
     * experience).
     */
    public static final String CATEGORY_SAMPLE_CODE = "android.intent.category.SAMPLE_CODE";

    /**
     * Used to indicate that an intent only wants URIs that can be opened with
     * {@link ContentResolver#openFileDescriptor(Uri, String)}. Openable URIs
     * must support at least the columns defined in {@link OpenableColumns} when
     * queried.
     *
     * @see #ACTION_GET_CONTENT
     * @see #ACTION_OPEN_DOCUMENT
     * @see #ACTION_CREATE_DOCUMENT
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_OPENABLE = "android.intent.category.OPENABLE";

    /**
     * Used to indicate that an intent filter can accept files which are not necessarily
     * openable by {@link ContentResolver#openFileDescriptor(Uri, String)}, but
     * at least streamable via
     * {@link ContentResolver#openTypedAssetFileDescriptor(Uri, String, Bundle)}
     * using one of the stream types exposed via
     * {@link ContentResolver#getStreamTypes(Uri, String)}.
     *
     * @see #ACTION_SEND
     * @see #ACTION_SEND_MULTIPLE
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_TYPED_OPENABLE  =
            "android.intent.category.TYPED_OPENABLE";

    /**
     * To be used as code under test for framework instrumentation tests.
     */
    public static final String CATEGORY_FRAMEWORK_INSTRUMENTATION_TEST =
            "android.intent.category.FRAMEWORK_INSTRUMENTATION_TEST";
    /**
     * An activity to run when device is inserted into a car dock.
     * Used with {@link #ACTION_MAIN} to launch an activity.  For more
     * information, see {@link android.app.UiModeManager}.
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_CAR_DOCK = "android.intent.category.CAR_DOCK";
    /**
     * An activity to run when device is inserted into a desk dock.
     * Used with {@link #ACTION_MAIN} to launch an activity.  For more
     * information, see {@link android.app.UiModeManager}.
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_DESK_DOCK = "android.intent.category.DESK_DOCK";
    /**
     * An activity to run when device is inserted into a analog (low end) dock.
     * Used with {@link #ACTION_MAIN} to launch an activity.  For more
     * information, see {@link android.app.UiModeManager}.
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_LE_DESK_DOCK = "android.intent.category.LE_DESK_DOCK";

    /**
     * An activity to run when device is inserted into a digital (high end) dock.
     * Used with {@link #ACTION_MAIN} to launch an activity.  For more
     * information, see {@link android.app.UiModeManager}.
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_HE_DESK_DOCK = "android.intent.category.HE_DESK_DOCK";

    /**
     * Used to indicate that the activity can be used in a car environment.
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_CAR_MODE = "android.intent.category.CAR_MODE";

    /**
     * An activity to use for the launcher when the device is placed in a VR Headset viewer.
     * Used with {@link #ACTION_MAIN} to launch an activity.  For more
     * information, see {@link android.app.UiModeManager}.
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_VR_HOME = "android.intent.category.VR_HOME";

    /**
     * The accessibility shortcut is a global gesture for users with disabilities to trigger an
     * important for them accessibility feature to help developers determine whether they want to
     * make their activity a shortcut target.
     * <p>
     * An activity of interest to users with accessibility needs may request to be the target of
     * the accessibility shortcut. It handles intent {@link #ACTION_MAIN} with this category,
     * which will be dispatched by the system when the user activates the shortcut when it is
     * configured to point at this target.
     * </p>
     * <p>
     * An activity declared itself to be a target of the shortcut in AndroidManifest.xml. It must
     * also do two things:
     * <ul>
     *     <ol>
     *         Specify that it handles the <code>android.intent.action.MAIN</code>
     *         {@link android.content.Intent}
     *         with category <code>android.intent.category.ACCESSIBILITY_SHORTCUT_TARGET</code>.
     *     </ol>
     *     <ol>
     *         Provide a meta-data entry <code>android.accessibilityshortcut.target</code> in the
     *         manifest when declaring the activity.
     *     </ol>
     * </ul>
     * If either of these items is missing, the system will ignore the accessibility shortcut
     * target. Following is an example declaration:
     * </p>
     * <pre>
     * &lt;activity android:name=".MainActivity"
     * . . .
     *   &lt;intent-filter&gt;
     *       &lt;action android:name="android.intent.action.MAIN" /&gt;
     *       &lt;category android:name="android.intent.category.ACCESSIBILITY_SHORTCUT_TARGET" /&gt;
     *   &lt;/intent-filter&gt;
     *   &lt;meta-data android:name="android.accessibilityshortcut.target"
     *                   android:resource="@xml/accessibilityshortcut" /&gt;
     * &lt;/activity&gt;
     * </pre>
     * <p> This is a sample XML file configuring a accessibility shortcut target: </p>
     * <pre>
     * &lt;accessibility-shortcut-target
     *     android:description="@string/shortcut_target_description"
     *     android:summary="@string/shortcut_target_summary"
     *     android:animatedImageDrawable="@drawable/shortcut_target_animated_image"
     *     android:htmlDescription="@string/shortcut_target_html_description"
     *     android:settingsActivity="com.example.android.shortcut.target.SettingsActivity" /&gt;
     * </pre>
     * <p>
     * Both description and summary are necessary. The system will ignore the accessibility
     * shortcut target if they are missing. The animated image and html description are supported
     * to help users understand how to use the shortcut target. The settings activity is a
     * component name that allows the user to modify the settings for this accessibility shortcut
     * target.
     * </p>
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_ACCESSIBILITY_SHORTCUT_TARGET =
            "android.intent.category.ACCESSIBILITY_SHORTCUT_TARGET";
    // ---------------------------------------------------------------------
    // ---------------------------------------------------------------------
    // Application launch intent categories (see addCategory()).

    /**
     * Used with {@link #ACTION_MAIN} to launch the browser application.
     * The activity should be able to browse the Internet.
     * <p>NOTE: This should not be used as the primary key of an Intent,
     * since it will not result in the app launching with the correct
     * action and category.  Instead, use this with
     * {@link #makeMainSelectorActivity(String, String)} to generate a main
     * Intent with this category in the selector.</p>
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_APP_BROWSER = "android.intent.category.APP_BROWSER";

    /**
     * Used with {@link #ACTION_MAIN} to launch the calculator application.
     * The activity should be able to perform standard arithmetic operations.
     * <p>NOTE: This should not be used as the primary key of an Intent,
     * since it will not result in the app launching with the correct
     * action and category.  Instead, use this with
     * {@link #makeMainSelectorActivity(String, String)} to generate a main
     * Intent with this category in the selector.</p>
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_APP_CALCULATOR = "android.intent.category.APP_CALCULATOR";

    /**
     * Used with {@link #ACTION_MAIN} to launch the calendar application.
     * The activity should be able to view and manipulate calendar entries.
     * <p>NOTE: This should not be used as the primary key of an Intent,
     * since it will not result in the app launching with the correct
     * action and category.  Instead, use this with
     * {@link #makeMainSelectorActivity(String, String)} to generate a main
     * Intent with this category in the selector.</p>
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_APP_CALENDAR = "android.intent.category.APP_CALENDAR";

    /**
     * Used with {@link #ACTION_MAIN} to launch the contacts application.
     * The activity should be able to view and manipulate address book entries.
     * <p>NOTE: This should not be used as the primary key of an Intent,
     * since it will not result in the app launching with the correct
     * action and category.  Instead, use this with
     * {@link #makeMainSelectorActivity(String, String)} to generate a main
     * Intent with this category in the selector.</p>
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_APP_CONTACTS = "android.intent.category.APP_CONTACTS";

    /**
     * Used with {@link #ACTION_MAIN} to launch the email application.
     * The activity should be able to send and receive email.
     * <p>NOTE: This should not be used as the primary key of an Intent,
     * since it will not result in the app launching with the correct
     * action and category.  Instead, use this with
     * {@link #makeMainSelectorActivity(String, String)} to generate a main
     * Intent with this category in the selector.</p>
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_APP_EMAIL = "android.intent.category.APP_EMAIL";

    /**
     * Used with {@link #ACTION_MAIN} to launch the gallery application.
     * The activity should be able to view and manipulate image and video files
     * stored on the device.
     * <p>NOTE: This should not be used as the primary key of an Intent,
     * since it will not result in the app launching with the correct
     * action and category.  Instead, use this with
     * {@link #makeMainSelectorActivity(String, String)} to generate a main
     * Intent with this category in the selector.</p>
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_APP_GALLERY = "android.intent.category.APP_GALLERY";

    /**
     * Used with {@link #ACTION_MAIN} to launch the maps application.
     * The activity should be able to show the user's current location and surroundings.
     * <p>NOTE: This should not be used as the primary key of an Intent,
     * since it will not result in the app launching with the correct
     * action and category.  Instead, use this with
     * {@link #makeMainSelectorActivity(String, String)} to generate a main
     * Intent with this category in the selector.</p>
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_APP_MAPS = "android.intent.category.APP_MAPS";

    /**
     * Used with {@link #ACTION_MAIN} to launch the messaging application.
     * The activity should be able to send and receive text messages.
     * <p>NOTE: This should not be used as the primary key of an Intent,
     * since it will not result in the app launching with the correct
     * action and category.  Instead, use this with
     * {@link #makeMainSelectorActivity(String, String)} to generate a main
     * Intent with this category in the selector.</p>
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_APP_MESSAGING = "android.intent.category.APP_MESSAGING";

    /**
     * Used with {@link #ACTION_MAIN} to launch the music application.
     * The activity should be able to play, browse, or manipulate music files
     * stored on the device.
     * <p>NOTE: This should not be used as the primary key of an Intent,
     * since it will not result in the app launching with the correct
     * action and category.  Instead, use this with
     * {@link #makeMainSelectorActivity(String, String)} to generate a main
     * Intent with this category in the selector.</p>
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_APP_MUSIC = "android.intent.category.APP_MUSIC";

    /**
     * Used with {@link #ACTION_MAIN} to launch the files application.
     * The activity should be able to browse and manage files stored on the device.
     * <p>NOTE: This should not be used as the primary key of an Intent,
     * since it will not result in the app launching with the correct
     * action and category.  Instead, use this with
     * {@link #makeMainSelectorActivity(String, String)} to generate a main
     * Intent with this category in the selector.</p>
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_APP_FILES = "android.intent.category.APP_FILES";

    /**
     * Used with {@link #ACTION_MAIN} to launch the weather application.
     * The activity should be able to give the user information about the weather
     * <p>NOTE: This should not be used as the primary key of an Intent,
     * since it will not result in the app launching with the correct
     * action and category.  Instead, use this with
     * {@link #makeMainSelectorActivity(String, String)} to generate a main
     * Intent with this category in the selector.</p>
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_APP_WEATHER = "android.intent.category.APP_WEATHER";

    /**
     * Used with {@link #ACTION_MAIN} to launch the fitness application.
     * The activity should be able to give the user fitness information and manage workouts
     * <p>NOTE: This should not be used as the primary key of an Intent,
     * since it will not result in the app launching with the correct
     * action and category.  Instead, use this with
     * {@link #makeMainSelectorActivity(String, String)} to generate a main
     * Intent with this category in the selector.</p>
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_APP_FITNESS = "android.intent.category.APP_FITNESS";

    // ---------------------------------------------------------------------
    // ---------------------------------------------------------------------
    // Standard extra data keys.

    /**
     * The initial data to place in a newly created record.  Use with
     * {@link #ACTION_INSERT}.  The data here is a Map containing the same
     * fields as would be given to the underlying ContentProvider.insert()
     * call.
     */
    public static final String EXTRA_TEMPLATE = "android.intent.extra.TEMPLATE";

    /**
     * A constant CharSequence that is associated with the Intent, used with
     * {@link #ACTION_SEND} to supply the literal data to be sent.  Note that
     * this may be a styled CharSequence, so you must use
     * {@link Bundle#getCharSequence(String) Bundle.getCharSequence()} to
     * retrieve it.
     */
    public static final String EXTRA_TEXT = "android.intent.extra.TEXT";

    /**
     * A constant String that is associated with the Intent, used with
     * {@link #ACTION_SEND} to supply an alternative to {@link #EXTRA_TEXT}
     * as HTML formatted text.  Note that you <em>must</em> also supply
     * {@link #EXTRA_TEXT}.
     */
    public static final String EXTRA_HTML_TEXT = "android.intent.extra.HTML_TEXT";

    /**
     * A content: URI holding a stream of data associated with the Intent,
     * used with {@link #ACTION_SEND} to supply the data being sent.
     */
    public static final String EXTRA_STREAM = "android.intent.extra.STREAM";

    /**
     * A String[] holding e-mail addresses that should be delivered to.
     */
    public static final String EXTRA_EMAIL       = "android.intent.extra.EMAIL";

    /**
     * A String[] holding e-mail addresses that should be carbon copied.
     */
    public static final String EXTRA_CC       = "android.intent.extra.CC";

    /**
     * A String[] holding e-mail addresses that should be blind carbon copied.
     */
    public static final String EXTRA_BCC      = "android.intent.extra.BCC";

    /**
     * A constant string holding the desired subject line of a message.
     */
    public static final String EXTRA_SUBJECT  = "android.intent.extra.SUBJECT";

    /**
     * An Intent describing the choices you would like shown with
     * {@link #ACTION_PICK_ACTIVITY} or {@link #ACTION_CHOOSER}.
     */
    public static final String EXTRA_INTENT = "android.intent.extra.INTENT";

    /**
     * An int representing the user ID to be used.
     *
     * @hide
     */
    public static final String EXTRA_USER_ID = "android.intent.extra.USER_ID";

    /**
     * An int representing the task id to be retrieved. This is used when a launch from recents is
     * intercepted by another action such as credentials confirmation to remember which task should
     * be resumed when complete.
     *
     * @hide
     */
    public static final String EXTRA_TASK_ID = "android.intent.extra.TASK_ID";

    /**
     * A String[] holding attribution tags when used with
     * {@link #ACTION_VIEW_PERMISSION_USAGE_FOR_PERIOD}
     * and ACTION_MANAGE_PERMISSION_USAGE
     *
     * E.g. an attribution tag could be location_provider, com.google.android.gms.*, etc.
     */
    public static final String EXTRA_ATTRIBUTION_TAGS = "android.intent.extra.ATTRIBUTION_TAGS";

    /**
     * A long representing the start timestamp (epoch time in millis) of the permission usage
     * when used with {@link #ACTION_VIEW_PERMISSION_USAGE_FOR_PERIOD}
     * and ACTION_MANAGE_PERMISSION_USAGE
     */
    public static final String EXTRA_START_TIME = "android.intent.extra.START_TIME";

    /**
     * A long representing the end timestamp (epoch time in millis) of the permission usage when
     * used with {@link #ACTION_VIEW_PERMISSION_USAGE_FOR_PERIOD}
     * and ACTION_MANAGE_PERMISSION_USAGE
     */
    public static final String EXTRA_END_TIME = "android.intent.extra.END_TIME";

    /**
     * A boolean extra, when used with {@link #ACTION_VIEW_PERMISSION_USAGE_FOR_PERIOD}
     * and {@link #ACTION_MANAGE_PERMISSION_USAGE},
     * that specifies whether the permission usage system UI is showing attribution information
     * for the chosen entry.
     *
     * <p> The extra can only be true if application has specified attributionsAreUserVisible
     * in its manifest. </p>
     *
     * <p> Applications can use this extra to improve their permission usage explanation
     * experience. </p>
     * @hide
     */
    @SystemApi
    public static final String EXTRA_SHOWING_ATTRIBUTION =
            "android.intent.extra.SHOWING_ATTRIBUTION";

    /**
     * An Intent[] describing additional, alternate choices you would like shown with
     * {@link #ACTION_CHOOSER}.
     *
     * <p>An app may be capable of providing several different payload types to complete a
     * user's intended action. For example, an app invoking {@link #ACTION_SEND} to share photos
     * with another app may use EXTRA_ALTERNATE_INTENTS to have the chooser transparently offer
     * several different supported sending mechanisms for sharing, such as the actual "image/*"
     * photo data or a hosted link where the photos can be viewed.</p>
     *
     * <p>The intent present in {@link #EXTRA_INTENT} will be treated as the
     * first/primary/preferred intent in the set. Additional intents specified in
     * this extra are ordered; by default intents that appear earlier in the array will be
     * preferred over intents that appear later in the array as matches for the same
     * target component. To alter this preference, a calling app may also supply
     * {@link #EXTRA_CHOOSER_REFINEMENT_INTENT_SENDER}.</p>
     */
    public static final String EXTRA_ALTERNATE_INTENTS = "android.intent.extra.ALTERNATE_INTENTS";

    /**
     * A {@link ComponentName ComponentName[]} describing components that should be filtered out
     * and omitted from a list of components presented to the user.
     *
     * <p>When used with {@link #ACTION_CHOOSER}, the chooser will omit any of the components
     * in this array if it otherwise would have shown them. Useful for omitting specific targets
     * from your own package or other apps from your organization if the idea of sending to those
     * targets would be redundant with other app functionality. Filtered components will not
     * be able to present targets from an associated <code>ChooserTargetService</code>.</p>
     */
    public static final String EXTRA_EXCLUDE_COMPONENTS
            = "android.intent.extra.EXCLUDE_COMPONENTS";

    /**
     * A {@link android.service.chooser.ChooserTarget ChooserTarget[]} for {@link #ACTION_CHOOSER}
     * describing additional high-priority deep-link targets for the chooser to present to the user.
     *
     * <p>Targets provided in this way will be presented inline with all other targets provided
     * by services from other apps. They will be prioritized before other service targets, but
     * after those targets provided by sources that the user has manually pinned to the front.
     * You can provide up to two targets on this extra (the limit of two targets
     * starts in Android 10).</p>
     *
     * @see #ACTION_CHOOSER
     */
    public static final String EXTRA_CHOOSER_TARGETS = "android.intent.extra.CHOOSER_TARGETS";

    /**
     * An {@link IntentSender} for an Activity that will be invoked when the user makes a selection
     * from the chooser activity presented by {@link #ACTION_CHOOSER}.
     *
     * <p>An app preparing an action for another app to complete may wish to allow the user to
     * disambiguate between several options for completing the action based on the chosen target
     * or otherwise refine the action before it is invoked.
     * </p>
     *
     * <p>When sent, this IntentSender may be filled in with the following extras:</p>
     * <ul>
     *     <li>{@link #EXTRA_INTENT} The first intent that matched the user's chosen target</li>
     *     <li>{@link #EXTRA_ALTERNATE_INTENTS} Any additional intents that also matched the user's
     *     chosen target beyond the first</li>
     *     <li>{@link #EXTRA_RESULT_RECEIVER} A {@link ResultReceiver} that the refinement activity
     *     should fill in and send once the disambiguation is complete</li>
     * </ul>
     */
    public static final String EXTRA_CHOOSER_REFINEMENT_INTENT_SENDER
            = "android.intent.extra.CHOOSER_REFINEMENT_INTENT_SENDER";

    /**
     * A Parcelable[] of {@link ChooserAction} objects to provide the Android Sharesheet with
     * app-specific actions to be presented to the user when invoking {@link #ACTION_CHOOSER}.
     * You can provide as many as five custom actions.
     */
    public static final String EXTRA_CHOOSER_CUSTOM_ACTIONS =
            "android.intent.extra.CHOOSER_CUSTOM_ACTIONS";

    /**
     * Optional argument to be used with {@link #ACTION_CHOOSER}.
     * A {@link ChooserAction} to allow the user to modify what is being shared in some way. This
     * may be integrated into the content preview on sharesheets that have a preview UI.
     */
    public static final String EXTRA_CHOOSER_MODIFY_SHARE_ACTION =
            "android.intent.extra.CHOOSER_MODIFY_SHARE_ACTION";

    /**
     * An {@code ArrayList} of {@code String} annotations describing content for
     * {@link #ACTION_CHOOSER}.
     *
     * <p>If {@link #EXTRA_CONTENT_ANNOTATIONS} is present in an intent used to start a
     * {@link #ACTION_CHOOSER} activity, the first three annotations will be used to rank apps.</p>
     *
     * <p>Annotations should describe the major components or topics of the content. It is up to
     * apps initiating {@link #ACTION_CHOOSER} to learn and add annotations. Annotations should be
     * learned in advance, e.g., when creating or saving content, to avoid increasing latency to
     * start {@link #ACTION_CHOOSER}. Names of customized annotations should not contain the colon
     * character. Performance on customized annotations can suffer, if they are rarely used for
     * {@link #ACTION_CHOOSER} in the past 14 days. Therefore, it is recommended to use the
     * following annotations when applicable.</p>
     * <ul>
     *     <li>"product" represents that the topic of the content is mainly about products, e.g.,
     *     health & beauty, and office supplies.</li>
     *     <li>"emotion" represents that the topic of the content is mainly about emotions, e.g.,
     *     happy, and sad.</li>
     *     <li>"person" represents that the topic of the content is mainly about persons, e.g.,
     *     face, finger, standing, and walking.</li>
     *     <li>"child" represents that the topic of the content is mainly about children, e.g.,
     *     child, and baby.</li>
     *     <li>"selfie" represents that the topic of the content is mainly about selfies.</li>
     *     <li>"crowd" represents that the topic of the content is mainly about crowds.</li>
     *     <li>"party" represents that the topic of the content is mainly about parties.</li>
     *     <li>"animal" represent that the topic of the content is mainly about animals.</li>
     *     <li>"plant" represents that the topic of the content is mainly about plants, e.g.,
     *     flowers.</li>
     *     <li>"vacation" represents that the topic of the content is mainly about vacations.</li>
     *     <li>"fashion" represents that the topic of the content is mainly about fashion, e.g.
     *     sunglasses, jewelry, handbags and clothing.</li>
     *     <li>"material" represents that the topic of the content is mainly about materials, e.g.,
     *     paper, and silk.</li>
     *     <li>"vehicle" represents that the topic of the content is mainly about vehicles, like
     *     cars, and boats.</li>
     *     <li>"document" represents that the topic of the content is mainly about documents, e.g.
     *     posters.</li>
     *     <li>"design" represents that the topic of the content is mainly about design, e.g. arts
     *     and designs of houses.</li>
     *     <li>"holiday" represents that the topic of the content is mainly about holidays, e.g.,
     *     Christmas and Thanksgiving.</li>
     * </ul>
     */
    public static final String EXTRA_CONTENT_ANNOTATIONS
            = "android.intent.extra.CONTENT_ANNOTATIONS";

    /**
     * A {@link ResultReceiver} used to return data back to the sender.
     *
     * <p>Used to complete an app-specific
     * {@link #EXTRA_CHOOSER_REFINEMENT_INTENT_SENDER refinement} for {@link #ACTION_CHOOSER}.</p>
     *
     * <p>If {@link #EXTRA_CHOOSER_REFINEMENT_INTENT_SENDER} is present in the intent
     * used to start a {@link #ACTION_CHOOSER} activity this extra will be
     * {@link #fillIn(Intent, int) filled in} to that {@link IntentSender} and sent
     * when the user selects a target component from the chooser. It is up to the recipient
     * to send a result to this ResultReceiver to signal that disambiguation is complete
     * and that the chooser should invoke the user's choice.</p>
     *
     * <p>The disambiguator should provide a Bundle to the ResultReceiver with an intent
     * assigned to the key {@link #EXTRA_INTENT}. This supplied intent will be used by the chooser
     * to match and fill in the final Intent or ChooserTarget before starting it.
     * The supplied intent must {@link #filterEquals(Intent) match} one of the intents from
     * {@link #EXTRA_INTENT} or {@link #EXTRA_ALTERNATE_INTENTS} passed to
     * {@link #EXTRA_CHOOSER_REFINEMENT_INTENT_SENDER} to be accepted.</p>
     *
     * <p>The result code passed to the ResultReceiver should be
     * {@link android.app.Activity#RESULT_OK} if the refinement succeeded and the supplied intent's
     * target in the chooser should be started, or {@link android.app.Activity#RESULT_CANCELED} if
     * the chooser should finish without starting a target.</p>
     */
    public static final String EXTRA_RESULT_RECEIVER
            = "android.intent.extra.RESULT_RECEIVER";

    /**
     * A CharSequence dialog title to provide to the user when used with a
     * {@link #ACTION_CHOOSER}.
     */
    public static final String EXTRA_TITLE = "android.intent.extra.TITLE";

    /**
     * A Parcelable[] of {@link Intent} or
     * {@link android.content.pm.LabeledIntent} objects as set with
     * {@link #putExtra(String, Parcelable[])} to place
     * at the front of the list of choices, when shown to the user with an
     * {@link #ACTION_CHOOSER}. You can choose up to two additional activities
     * to show before the app suggestions (the limit of two additional activities starts in
     * Android 10).
     */
    public static final String EXTRA_INITIAL_INTENTS = "android.intent.extra.INITIAL_INTENTS";

    /**
     * A {@link IntentSender} to start after instant app installation success.
     * @hide
     */
    @SystemApi
    public static final String EXTRA_INSTANT_APP_SUCCESS =
            "android.intent.extra.INSTANT_APP_SUCCESS";

    /**
     * A {@link IntentSender} to start after instant app installation failure.
     * @hide
     */
    @SystemApi
    public static final String EXTRA_INSTANT_APP_FAILURE =
            "android.intent.extra.INSTANT_APP_FAILURE";

    /**
     * The host name that triggered an instant app resolution.
     * @hide
     */
    @SystemApi
    public static final String EXTRA_INSTANT_APP_HOSTNAME =
            "android.intent.extra.INSTANT_APP_HOSTNAME";

    /**
     * An opaque token to track instant app resolution.
     * @hide
     */
    @SystemApi
    public static final String EXTRA_INSTANT_APP_TOKEN =
            "android.intent.extra.INSTANT_APP_TOKEN";

    /**
     * The action that triggered an instant application resolution.
     * @hide
     */
    @SystemApi
    public static final String EXTRA_INSTANT_APP_ACTION = "android.intent.extra.INSTANT_APP_ACTION";

    /**
     * An array of {@link Bundle}s containing details about resolved instant apps..
     * @hide
     */
    @SystemApi
    public static final String EXTRA_INSTANT_APP_BUNDLES =
            "android.intent.extra.INSTANT_APP_BUNDLES";

    /**
     * A {@link Bundle} of metadata that describes the instant application that needs to be
     * installed. This data is populated from the response to
     * {@link android.content.pm.InstantAppResolveInfo#getExtras()} as provided by the registered
     * instant application resolver.
     * @hide
     */
    @SystemApi
    public static final String EXTRA_INSTANT_APP_EXTRAS =
            "android.intent.extra.INSTANT_APP_EXTRAS";

    /**
     * A boolean value indicating that the instant app resolver was unable to state with certainty
     * that it did or did not have an app for the sanitized {@link Intent} defined at
     * {@link #EXTRA_INTENT}.
     * @hide
     */
    @SystemApi
    public static final String EXTRA_UNKNOWN_INSTANT_APP =
            "android.intent.extra.UNKNOWN_INSTANT_APP";

    /**
     * The version code of the app to install components from.
     * @deprecated Use {@link #EXTRA_LONG_VERSION_CODE).
     * @hide
     */
    @Deprecated
    public static final String EXTRA_VERSION_CODE = "android.intent.extra.VERSION_CODE";

    /**
     * The version code of the app to install components from.
     * @hide
     */
    @SystemApi
    public static final String EXTRA_LONG_VERSION_CODE = "android.intent.extra.LONG_VERSION_CODE";

    /**
     * The app that triggered the instant app installation.
     * @hide
     */
    @SystemApi
    public static final String EXTRA_CALLING_PACKAGE
            = "android.intent.extra.CALLING_PACKAGE";

    /**
     * Optional calling app provided bundle containing additional launch information the
     * installer may use.
     * @hide
     */
    @SystemApi
    public static final String EXTRA_VERIFICATION_BUNDLE
            = "android.intent.extra.VERIFICATION_BUNDLE";

    /**
     * A Bundle forming a mapping of potential target package names to different extras Bundles
     * to add to the default intent extras in {@link #EXTRA_INTENT} when used with
     * {@link #ACTION_CHOOSER}. Each key should be a package name. The package need not
     * be currently installed on the device.
     *
     * <p>An application may choose to provide alternate extras for the case where a user
     * selects an activity from a predetermined set of target packages. If the activity
     * the user selects from the chooser belongs to a package with its package name as
     * a key in this bundle, the corresponding extras for that package will be merged with
     * the extras already present in the intent at {@link #EXTRA_INTENT}. If a replacement
     * extra has the same key as an extra already present in the intent it will overwrite
     * the extra from the intent.</p>
     *
     * <p><em>Examples:</em>
     * <ul>
     *     <li>An application may offer different {@link #EXTRA_TEXT} to an application
     *     when sharing with it via {@link #ACTION_SEND}, augmenting a link with additional query
     *     parameters for that target.</li>
     *     <li>An application may offer additional metadata for known targets of a given intent
     *     to pass along information only relevant to that target such as account or content
     *     identifiers already known to that application.</li>
     * </ul></p>
     */
    public static final String EXTRA_REPLACEMENT_EXTRAS =
            "android.intent.extra.REPLACEMENT_EXTRAS";

    /**
     * An {@link IntentSender} that will be notified if a user successfully chooses a target
     * component to handle an action in an {@link #ACTION_CHOOSER} activity. The IntentSender
     * will have the extra {@link #EXTRA_CHOSEN_COMPONENT} appended to it containing the
     * {@link ComponentName} of the chosen component.
     *
     * <p>In some situations this callback may never come, for example if the user abandons
     * the chooser, switches to another task or any number of other reasons. Apps should not
     * be written assuming that this callback will always occur.</p>
     */
    public static final String EXTRA_CHOSEN_COMPONENT_INTENT_SENDER =
            "android.intent.extra.CHOSEN_COMPONENT_INTENT_SENDER";

    /**
     * The {@link ComponentName} chosen by the user to complete an action.
     *
     * @see #EXTRA_CHOSEN_COMPONENT_INTENT_SENDER
     */
    public static final String EXTRA_CHOSEN_COMPONENT = "android.intent.extra.CHOSEN_COMPONENT";

    /**
     * A {@link android.view.KeyEvent} object containing the event that
     * triggered the creation of the Intent it is in.
     */
    public static final String EXTRA_KEY_EVENT = "android.intent.extra.KEY_EVENT";

    /**
     * Set to true in {@link #ACTION_REQUEST_SHUTDOWN} to request confirmation from the user
     * before shutting down.
     *
     * {@hide}
     */
    public static final String EXTRA_KEY_CONFIRM = "android.intent.extra.KEY_CONFIRM";

    /**
     * Set to true in {@link #ACTION_REQUEST_SHUTDOWN} to indicate that the shutdown is
     * requested by the user.
     *
     * {@hide}
     */
    public static final String EXTRA_USER_REQUESTED_SHUTDOWN =
            "android.intent.extra.USER_REQUESTED_SHUTDOWN";

    /**
     * Used as a boolean extra field in {@link android.content.Intent#ACTION_PACKAGE_REMOVED} or
     * {@link android.content.Intent#ACTION_PACKAGE_CHANGED} intents to override the default action
     * of restarting the application.
     */
    public static final String EXTRA_DONT_KILL_APP = "android.intent.extra.DONT_KILL_APP";

    /**
     * Used as a boolean extra field in {@link android.content.Intent#ACTION_PACKAGE_REMOVED}
     * intents to signal that the application was removed with the user-initiated action.
     */
    public static final String EXTRA_USER_INITIATED = "android.intent.extra.USER_INITIATED";

    /**
     * A String holding the phone number originally entered in
     * {@link android.content.Intent#ACTION_NEW_OUTGOING_CALL}, or the actual
     * number to call in a {@link android.content.Intent#ACTION_CALL}.
     */
    public static final String EXTRA_PHONE_NUMBER = "android.intent.extra.PHONE_NUMBER";

    /**
     * Used as an int extra field in {@link android.content.Intent#ACTION_UID_REMOVED}
     * intents to supply the uid the package had been assigned.  Also an optional
     * extra in {@link android.content.Intent#ACTION_PACKAGE_REMOVED} or
     * {@link android.content.Intent#ACTION_PACKAGE_CHANGED} for the same
     * purpose.
     */
    public static final String EXTRA_UID = "android.intent.extra.UID";

    /**
     * String array of package names.
     */
    public static final String EXTRA_PACKAGES = "android.intent.extra.PACKAGES";

    /**
     * Used as a boolean extra field in {@link android.content.Intent#ACTION_PACKAGE_REMOVED}
     * intents to indicate whether this represents a full uninstall (removing
     * both the code and its data) or a partial uninstall (leaving its data,
     * implying that this is an update).
     */
    public static final String EXTRA_DATA_REMOVED = "android.intent.extra.DATA_REMOVED";

    /**
     * @hide
     * Used as a boolean extra field in {@link android.content.Intent#ACTION_PACKAGE_REMOVED}
     * intents to indicate that at this point the package has been removed for
     * all users on the device.
     */
    public static final String EXTRA_REMOVED_FOR_ALL_USERS
            = "android.intent.extra.REMOVED_FOR_ALL_USERS";

    /**
     * Used as a boolean extra field in {@link android.content.Intent#ACTION_PACKAGE_REMOVED}
     * intents to indicate that this is a replacement of the package, so this
     * broadcast will immediately be followed by an add broadcast for a
     * different version of the same package.
     */
    public static final String EXTRA_REPLACING = "android.intent.extra.REPLACING";

    /**
     * Used as a boolean extra field in {@link android.content.Intent#ACTION_PACKAGE_ADDED} and
     * {@link android.content.Intent#ACTION_PACKAGE_REMOVED} intents to indicate that
     * the package is being archived. Either by removing the existing APK, or by installing
     * a package without an APK.
     */
    @FlaggedApi(android.content.pm.Flags.FLAG_ARCHIVING)
    public static final String EXTRA_ARCHIVAL = "android.intent.extra.ARCHIVAL";

    /**
     * Used as a boolean extra field in {@link android.content.Intent#ACTION_PACKAGE_REMOVED}
     * intents to indicate that this is a system update uninstall.
     * @hide
     */
    public static final String EXTRA_SYSTEM_UPDATE_UNINSTALL =
            "android.intent.extra.SYSTEM_UPDATE_UNINSTALL";

    /**
     * Used as an int extra field in {@link android.app.AlarmManager} pending intents
     * to tell the application being invoked how many pending alarms are being
     * delivered with the intent.  For one-shot alarms this will always be 1.
     * For recurring alarms, this might be greater than 1 if the device was
     * asleep or powered off at the time an earlier alarm would have been
     * delivered.
     *
     * <p>Note: You must supply a <b>mutable</b> {@link android.app.PendingIntent} to
     * {@code AlarmManager} while setting your alarms to be able to read this value on receiving
     * them. <em>Mutability of pending intents must be explicitly specified by apps targeting
     * {@link Build.VERSION_CODES#S} or higher</em>.
     *
     * @see android.app.PendingIntent#FLAG_MUTABLE
     *
     */
    public static final String EXTRA_ALARM_COUNT = "android.intent.extra.ALARM_COUNT";

    /**
     * Used as an int extra field in {@link android.content.Intent#ACTION_DOCK_EVENT}
     * intents to request the dock state.  Possible values are
     * {@link android.content.Intent#EXTRA_DOCK_STATE_UNDOCKED},
     * {@link android.content.Intent#EXTRA_DOCK_STATE_DESK}, or
     * {@link android.content.Intent#EXTRA_DOCK_STATE_CAR}, or
     * {@link android.content.Intent#EXTRA_DOCK_STATE_LE_DESK}, or
     * {@link android.content.Intent#EXTRA_DOCK_STATE_HE_DESK}.
     */
    public static final String EXTRA_DOCK_STATE = "android.intent.extra.DOCK_STATE";

    /**
     * Used as an int value for {@link android.content.Intent#EXTRA_DOCK_STATE}
     * to represent that the phone is not in any dock.
     */
    public static final int EXTRA_DOCK_STATE_UNDOCKED = 0;

    /**
     * Used as an int value for {@link android.content.Intent#EXTRA_DOCK_STATE}
     * to represent that the phone is in a desk dock.
     */
    public static final int EXTRA_DOCK_STATE_DESK = 1;

    /**
     * Used as an int value for {@link android.content.Intent#EXTRA_DOCK_STATE}
     * to represent that the phone is in a car dock.
     */
    public static final int EXTRA_DOCK_STATE_CAR = 2;

    /**
     * Used as an int value for {@link android.content.Intent#EXTRA_DOCK_STATE}
     * to represent that the phone is in a analog (low end) dock.
     */
    public static final int EXTRA_DOCK_STATE_LE_DESK = 3;

    /**
     * Used as an int value for {@link android.content.Intent#EXTRA_DOCK_STATE}
     * to represent that the phone is in a digital (high end) dock.
     */
    public static final int EXTRA_DOCK_STATE_HE_DESK = 4;

    /**
     * Boolean that can be supplied as meta-data with a dock activity, to
     * indicate that the dock should take over the home key when it is active.
     */
    public static final String METADATA_DOCK_HOME = "android.dock_home";

    /**
     * Used as a parcelable extra field in {@link #ACTION_APP_ERROR}, containing
     * the bug report.
     */
    public static final String EXTRA_BUG_REPORT = "android.intent.extra.BUG_REPORT";

    /**
     * Used in the extra field in the remote intent. It's a string token passed with the
     * remote intent.
     */
    public static final String EXTRA_REMOTE_INTENT_TOKEN =
            "android.intent.extra.remote_intent_token";

    /**
     * @deprecated See {@link #EXTRA_CHANGED_COMPONENT_NAME_LIST}; this field
     * will contain only the first name in the list.
     */
    @Deprecated public static final String EXTRA_CHANGED_COMPONENT_NAME =
            "android.intent.extra.changed_component_name";

    /**
     * This field is part of {@link android.content.Intent#ACTION_PACKAGE_CHANGED},
     * and contains a string array of all of the components that have changed.  If
     * the state of the overall package has changed, then it will contain an entry
     * with the package name itself.
     */
    public static final String EXTRA_CHANGED_COMPONENT_NAME_LIST =
            "android.intent.extra.changed_component_name_list";

    /**
     * This field is part of
     * {@link android.content.Intent#ACTION_EXTERNAL_APPLICATIONS_AVAILABLE},
     * {@link android.content.Intent#ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE},
     * {@link android.content.Intent#ACTION_PACKAGES_SUSPENDED},
     * {@link android.content.Intent#ACTION_PACKAGES_UNSUSPENDED}
     * and contains a string array of all of the components that have changed.
     */
    public static final String EXTRA_CHANGED_PACKAGE_LIST =
            "android.intent.extra.changed_package_list";

    /**
     * This field is part of
     * {@link android.content.Intent#ACTION_EXTERNAL_APPLICATIONS_AVAILABLE},
     * {@link android.content.Intent#ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE}
     * and contains an integer array of uids of all of the components
     * that have changed.
     */
    public static final String EXTRA_CHANGED_UID_LIST =
            "android.intent.extra.changed_uid_list";

    /**
     * This field is part of
     * {@link android.content.Intent#ACTION_PACKAGES_SUSPENDED},
     * and only present if the packages were quarantined.
     * @hide
     */
    public static final String EXTRA_QUARANTINED =
            "android.intent.extra.quarantined";

    /**
     * An integer denoting a bitwise combination of restrictions set on distracting packages via
     * {@link PackageManager#setDistractingPackageRestrictions(String[], int)}
     *
     * @hide
     * @see PackageManager.DistractionRestriction
     * @see PackageManager#setDistractingPackageRestrictions(String[], int)
     */
    public static final String EXTRA_DISTRACTION_RESTRICTIONS =
            "android.intent.extra.distraction_restrictions";

    /**
     * @hide
     * Magic extra system code can use when binding, to give a label for
     * who it is that has bound to a service.  This is an integer giving
     * a framework string resource that can be displayed to the user.
     */
    public static final String EXTRA_CLIENT_LABEL =
            "android.intent.extra.client_label";

    /**
     * @hide
     * Magic extra system code can use when binding, to give a PendingIntent object
     * that can be launched for the user to disable the system's use of this
     * service.
     */
    public static final String EXTRA_CLIENT_INTENT =
            "android.intent.extra.client_intent";

    /**
     * Extra used to indicate that an intent should only return data that is on
     * the local device. This is a boolean extra; the default is false. If true,
     * an implementation should only allow the user to select data that is
     * already on the device, not requiring it be downloaded from a remote
     * service when opened.
     *
     * @see #ACTION_GET_CONTENT
     * @see #ACTION_OPEN_DOCUMENT
     * @see #ACTION_OPEN_DOCUMENT_TREE
     * @see #ACTION_CREATE_DOCUMENT
     */
    public static final String EXTRA_LOCAL_ONLY =
            "android.intent.extra.LOCAL_ONLY";

    /**
     * Extra used to indicate that an intent can allow the user to select and
     * return multiple items. This is a boolean extra; the default is false. If
     * true, an implementation is allowed to present the user with a UI where
     * they can pick multiple items that are all returned to the caller. When
     * this happens, they should be returned as the {@link #getClipData()} part
     * of the result Intent.
     *
     * @see #ACTION_GET_CONTENT
     * @see #ACTION_OPEN_DOCUMENT
     */
    public static final String EXTRA_ALLOW_MULTIPLE =
            "android.intent.extra.ALLOW_MULTIPLE";

    /**
     * The user ID integer carried with broadcast intents related to addition,
     * removal and switching of users and managed profiles - {@link #ACTION_USER_ADDED},
     * {@link #ACTION_USER_REMOVED} and {@link #ACTION_USER_SWITCHED}.
     *
     * @hide
     */
    @SystemApi
    @SuppressLint("ActionValue")
    public static final String EXTRA_USER_HANDLE =
            "android.intent.extra.user_handle";

    /**
     * The {@link UserHandle} carried with intents.
     */
    public static final String EXTRA_USER =
            "android.intent.extra.USER";

    /**
     * Extra used in the response from a BroadcastReceiver that handles
     * {@link #ACTION_GET_RESTRICTION_ENTRIES}. The type of the extra is
     * <code>ArrayList&lt;RestrictionEntry&gt;</code>.
     */
    public static final String EXTRA_RESTRICTIONS_LIST = "android.intent.extra.restrictions_list";

    /**
     * Extra sent in the intent to the BroadcastReceiver that handles
     * {@link #ACTION_GET_RESTRICTION_ENTRIES}. The type of the extra is a Bundle containing
     * the restrictions as key/value pairs.
     */
    public static final String EXTRA_RESTRICTIONS_BUNDLE =
            "android.intent.extra.restrictions_bundle";

    /**
     * Extra used in the response from a BroadcastReceiver that handles
     * {@link #ACTION_GET_RESTRICTION_ENTRIES}.
     */
    public static final String EXTRA_RESTRICTIONS_INTENT =
            "android.intent.extra.restrictions_intent";

    /**
     * Extra used to communicate a set of acceptable MIME types. The type of the
     * extra is {@code String[]}. Values may be a combination of concrete MIME
     * types (such as "image/png") and/or partial MIME types (such as
     * "audio/*").
     *
     * @see #ACTION_GET_CONTENT
     * @see #ACTION_OPEN_DOCUMENT
     */
    public static final String EXTRA_MIME_TYPES = "android.intent.extra.MIME_TYPES";

    /**
     * Optional extra for {@link #ACTION_SHUTDOWN} that allows the sender to qualify that
     * this shutdown is only for the user space of the system, not a complete shutdown.
     * When this is true, hardware devices can use this information to determine that
     * they shouldn't do a complete shutdown of their device since this is not a
     * complete shutdown down to the kernel, but only user space restarting.
     * The default if not supplied is false.
     */
    public static final String EXTRA_SHUTDOWN_USERSPACE_ONLY
            = "android.intent.extra.SHUTDOWN_USERSPACE_ONLY";

    /**
     * Optional extra specifying a time in milliseconds. The timebase depends on the Intent
     * including this extra. The value must be non-negative.
     * <p>
     * Type: long
     * </p>
     */
    public static final String EXTRA_TIME = "android.intent.extra.TIME";

    /**
     * Extra sent with {@link #ACTION_TIMEZONE_CHANGED} specifying the new time zone of the device.
     *
     * <p>Type: String, the same as returned by {@link TimeZone#getID()} to identify time zones.
     */
    @SuppressLint("ActionValue")
    public static final String EXTRA_TIMEZONE = "time-zone";

    /**
     * Optional int extra for {@link #ACTION_TIME_CHANGED} that indicates the
     * user has set their time format preference. See {@link #EXTRA_TIME_PREF_VALUE_USE_12_HOUR},
     * {@link #EXTRA_TIME_PREF_VALUE_USE_24_HOUR} and
     * {@link #EXTRA_TIME_PREF_VALUE_USE_LOCALE_DEFAULT}. The value must not be negative.
     *
     * @hide for internal use only.
     */
    public static final String EXTRA_TIME_PREF_24_HOUR_FORMAT =
            "android.intent.extra.TIME_PREF_24_HOUR_FORMAT";
    /** @hide */
    public static final int EXTRA_TIME_PREF_VALUE_USE_12_HOUR = 0;
    /** @hide */
    public static final int EXTRA_TIME_PREF_VALUE_USE_24_HOUR = 1;
    /** @hide */
    public static final int EXTRA_TIME_PREF_VALUE_USE_LOCALE_DEFAULT = 2;

    /**
     * Intent extra: the reason that the operation associated with this intent is being performed.
     *
     * <p>Type: String
     * @hide
     */
    @SystemApi
    public static final String EXTRA_REASON = "android.intent.extra.REASON";

    /**
     * Intent extra: Whether to show the wipe progress UI or to skip it.
     *
     * <p>Type: boolean
     * @hide
     */
    public static final String EXTRA_SHOW_WIPE_PROGRESS = "android.intent.extra.SHOW_WIPE_PROGRESS";

    /**
     * {@hide}
     * This extra will be send together with {@link #ACTION_FACTORY_RESET}
     */
    public static final String EXTRA_WIPE_EXTERNAL_STORAGE = "android.intent.extra.WIPE_EXTERNAL_STORAGE";

    /**
     * {@hide}
     * This extra will be set to true when the user choose to wipe the data on eSIM during factory
     * reset for the device with eSIM. This extra will be sent together with
     * {@link #ACTION_FACTORY_RESET}
     */
    public static final String EXTRA_WIPE_ESIMS = "com.android.internal.intent.extra.WIPE_ESIMS";

    /**
     * Optional {@link android.app.PendingIntent} extra used to deliver the result of the SIM
     * activation request.
     * TODO: Add information about the structure and response data used with the pending intent.
     * @hide
     */
    public static final String EXTRA_SIM_ACTIVATION_RESPONSE =
            "android.intent.extra.SIM_ACTIVATION_RESPONSE";

    /**
     * Optional index with semantics depending on the intent action.
     *
     * <p>The value must be an integer greater or equal to 0.
     * @see #ACTION_QUICK_VIEW
     */
    public static final String EXTRA_INDEX = "android.intent.extra.INDEX";

    /**
     * Tells the quick viewer to show additional UI actions suitable for the passed Uris,
     * such as opening in other apps, sharing, opening, editing, printing, deleting,
     * casting, etc.
     *
     * <p>The value is boolean. By default false.
     * @see #ACTION_QUICK_VIEW
     * @removed
     */
    @Deprecated
    public static final String EXTRA_QUICK_VIEW_ADVANCED =
            "android.intent.extra.QUICK_VIEW_ADVANCED";

    /**
     * An optional extra of {@code String[]} indicating which quick view features should be made
     * available to the user in the quick view UI while handing a
     * {@link Intent#ACTION_QUICK_VIEW} intent.
     * <li>Enumeration of features here is not meant to restrict capabilities of the quick viewer.
     * Quick viewer can implement features not listed below.
     * <li>Features included at this time are: {@link QuickViewConstants#FEATURE_VIEW},
     * {@link QuickViewConstants#FEATURE_EDIT}, {@link QuickViewConstants#FEATURE_DELETE},
     * {@link QuickViewConstants#FEATURE_DOWNLOAD}, {@link QuickViewConstants#FEATURE_SEND},
     * {@link QuickViewConstants#FEATURE_PRINT}.
     * <p>
     * Requirements:
     * <li>Quick viewer shouldn't show a feature if the feature is absent in
     * {@link #EXTRA_QUICK_VIEW_FEATURES}.
     * <li>When {@link #EXTRA_QUICK_VIEW_FEATURES} is not present, quick viewer should follow
     * internal policies.
     * <li>Presence of an feature in {@link #EXTRA_QUICK_VIEW_FEATURES}, does not constitute a
     * requirement that the feature be shown. Quick viewer may, according to its own policies,
     * disable or hide features.
     *
     * @see #ACTION_QUICK_VIEW
     */
    public static final String EXTRA_QUICK_VIEW_FEATURES =
            "android.intent.extra.QUICK_VIEW_FEATURES";

    /**
     * Optional boolean extra indicating whether quiet mode has been switched on or off.
     * When a profile goes into quiet mode, all apps in the profile are killed and the
     * profile user is stopped. Widgets originating from the profile are masked, and app
     * launcher icons are grayed out.
     */
    public static final String EXTRA_QUIET_MODE = "android.intent.extra.QUIET_MODE";

    /**
     * Optional CharSequence extra to provide a search query.
     * The format of this query is dependent on the receiving application.
     *
     * <p>Applicable to {@link Intent} with actions:
     * <ul>
     *      <li>{@link Intent#ACTION_GET_CONTENT}</li>
     *      <li>{@link Intent#ACTION_OPEN_DOCUMENT}</li>
     * </ul>
     */
    public static final String EXTRA_CONTENT_QUERY = "android.intent.extra.CONTENT_QUERY";

    /**
     * Used as an int extra field in {@link #ACTION_MEDIA_RESOURCE_GRANTED}
     * intents to specify the resource type granted. Possible values are
     * {@link #EXTRA_MEDIA_RESOURCE_TYPE_VIDEO_CODEC} or
     * {@link #EXTRA_MEDIA_RESOURCE_TYPE_AUDIO_CODEC}.
     *
     * @hide
     */
    public static final String EXTRA_MEDIA_RESOURCE_TYPE =
            "android.intent.extra.MEDIA_RESOURCE_TYPE";

    /**
     * Used as a boolean extra field in {@link #ACTION_CHOOSER} intents to specify
     * whether to show the chooser or not when there is only one application available
     * to choose from.
     */
    public static final String EXTRA_AUTO_LAUNCH_SINGLE_CHOICE =
            "android.intent.extra.AUTO_LAUNCH_SINGLE_CHOICE";

    /**
     * Used as an int value for {@link #EXTRA_MEDIA_RESOURCE_TYPE}
     * to represent that a video codec is allowed to use.
     *
     * @hide
     */
    public static final int EXTRA_MEDIA_RESOURCE_TYPE_VIDEO_CODEC = 0;

    /**
     * Used as an int value for {@link #EXTRA_MEDIA_RESOURCE_TYPE}
     * to represent that a audio codec is allowed to use.
     *
     * @hide
     */
    public static final int EXTRA_MEDIA_RESOURCE_TYPE_AUDIO_CODEC = 1;

    /**
     * Intent extra: ID of the context used on {@link #ACTION_VIEW_LOCUS}.
     *
     * <p>
     * Type: {@link LocusId}
     * </p>
     */
    public static final String EXTRA_LOCUS_ID = "android.intent.extra.LOCUS_ID";

    /**
     * Used as an int array extra field in
     * {@link android.content.Intent#ACTION_PACKAGE_REMOVED_INTERNAL}
     * intents to indicate that visibility allow list of this removed package.
     *
     * @hide
     */
    public static final String EXTRA_VISIBILITY_ALLOW_LIST =
            "android.intent.extra.VISIBILITY_ALLOW_LIST";

    /**
     * A boolean extra used with {@link #ACTION_PACKAGE_DATA_CLEARED} which indicates if the intent
     * is broadcast as part of a restore operation.
     *
     * @hide
     */
    public static final String EXTRA_IS_RESTORE =
            "android.intent.extra.IS_RESTORE";

    // ---------------------------------------------------------------------
    // ---------------------------------------------------------------------
    // Intent flags (see mFlags variable).

    /** @hide */
    @IntDef(flag = true, prefix = { "FLAG_GRANT_" }, value = {
            FLAG_GRANT_READ_URI_PERMISSION, FLAG_GRANT_WRITE_URI_PERMISSION,
            FLAG_GRANT_PERSISTABLE_URI_PERMISSION, FLAG_GRANT_PREFIX_URI_PERMISSION })
    @Retention(RetentionPolicy.SOURCE)
    public @interface GrantUriMode {}

    /** @hide */
    @IntDef(flag = true, prefix = { "FLAG_GRANT_" }, value = {
            FLAG_GRANT_READ_URI_PERMISSION, FLAG_GRANT_WRITE_URI_PERMISSION })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AccessUriMode {}

    /**
     * Test if given mode flags specify an access mode, which must be at least
     * read and/or write.
     *
     * @hide
     */
    public static boolean isAccessUriMode(int modeFlags) {
        return (modeFlags & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) != 0;
    }

    /** @hide */
    @IntDef(flag = true, prefix = { "FLAG_" }, value = {
            FLAG_GRANT_READ_URI_PERMISSION,
            FLAG_GRANT_WRITE_URI_PERMISSION,
            FLAG_FROM_BACKGROUND,
            FLAG_DEBUG_LOG_RESOLUTION,
            FLAG_EXCLUDE_STOPPED_PACKAGES,
            FLAG_INCLUDE_STOPPED_PACKAGES,
            FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            FLAG_GRANT_PREFIX_URI_PERMISSION,
            FLAG_DEBUG_TRIAGED_MISSING,
            FLAG_IGNORE_EPHEMERAL,
            FLAG_ACTIVITY_MATCH_EXTERNAL,
            FLAG_ACTIVITY_NO_HISTORY,
            FLAG_ACTIVITY_SINGLE_TOP,
            FLAG_ACTIVITY_NEW_TASK,
            FLAG_ACTIVITY_MULTIPLE_TASK,
            FLAG_ACTIVITY_CLEAR_TOP,
            FLAG_ACTIVITY_FORWARD_RESULT,
            FLAG_ACTIVITY_PREVIOUS_IS_TOP,
            FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
            FLAG_ACTIVITY_BROUGHT_TO_FRONT,
            FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
            FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY,
            FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET,
            FLAG_ACTIVITY_NEW_DOCUMENT,
            FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET,
            FLAG_ACTIVITY_NO_USER_ACTION,
            FLAG_ACTIVITY_REORDER_TO_FRONT,
            FLAG_ACTIVITY_NO_ANIMATION,
            FLAG_ACTIVITY_CLEAR_TASK,
            FLAG_ACTIVITY_TASK_ON_HOME,
            FLAG_ACTIVITY_RETAIN_IN_RECENTS,
            FLAG_ACTIVITY_LAUNCH_ADJACENT,
            FLAG_ACTIVITY_REQUIRE_NON_BROWSER,
            FLAG_ACTIVITY_REQUIRE_DEFAULT,
            FLAG_RECEIVER_REGISTERED_ONLY,
            FLAG_RECEIVER_REPLACE_PENDING,
            FLAG_RECEIVER_FOREGROUND,
            FLAG_RECEIVER_NO_ABORT,
            FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT,
            FLAG_RECEIVER_BOOT_UPGRADE,
            FLAG_RECEIVER_INCLUDE_BACKGROUND,
            FLAG_RECEIVER_EXCLUDE_BACKGROUND,
            FLAG_RECEIVER_FROM_SHELL,
            FLAG_RECEIVER_VISIBLE_TO_INSTANT_APPS,
            FLAG_RECEIVER_OFFLOAD,
            FLAG_RECEIVER_OFFLOAD_FOREGROUND,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Flags {}

    /** @hide */
    @IntDef(flag = true, prefix = { "FLAG_" }, value = {
            FLAG_FROM_BACKGROUND,
            FLAG_DEBUG_LOG_RESOLUTION,
            FLAG_EXCLUDE_STOPPED_PACKAGES,
            FLAG_INCLUDE_STOPPED_PACKAGES,
            FLAG_DEBUG_TRIAGED_MISSING,
            FLAG_IGNORE_EPHEMERAL,
            FLAG_ACTIVITY_MATCH_EXTERNAL,
            FLAG_ACTIVITY_NO_HISTORY,
            FLAG_ACTIVITY_SINGLE_TOP,
            FLAG_ACTIVITY_NEW_TASK,
            FLAG_ACTIVITY_MULTIPLE_TASK,
            FLAG_ACTIVITY_CLEAR_TOP,
            FLAG_ACTIVITY_FORWARD_RESULT,
            FLAG_ACTIVITY_PREVIOUS_IS_TOP,
            FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
            FLAG_ACTIVITY_BROUGHT_TO_FRONT,
            FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
            FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY,
            FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET,
            FLAG_ACTIVITY_NEW_DOCUMENT,
            FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET,
            FLAG_ACTIVITY_NO_USER_ACTION,
            FLAG_ACTIVITY_REORDER_TO_FRONT,
            FLAG_ACTIVITY_NO_ANIMATION,
            FLAG_ACTIVITY_CLEAR_TASK,
            FLAG_ACTIVITY_TASK_ON_HOME,
            FLAG_ACTIVITY_RETAIN_IN_RECENTS,
            FLAG_ACTIVITY_LAUNCH_ADJACENT,
            FLAG_RECEIVER_REGISTERED_ONLY,
            FLAG_RECEIVER_REPLACE_PENDING,
            FLAG_RECEIVER_FOREGROUND,
            FLAG_RECEIVER_NO_ABORT,
            FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT,
            FLAG_RECEIVER_BOOT_UPGRADE,
            FLAG_RECEIVER_INCLUDE_BACKGROUND,
            FLAG_RECEIVER_EXCLUDE_BACKGROUND,
            FLAG_RECEIVER_FROM_SHELL,
            FLAG_RECEIVER_VISIBLE_TO_INSTANT_APPS,
            FLAG_RECEIVER_OFFLOAD,
            FLAG_RECEIVER_OFFLOAD_FOREGROUND,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface MutableFlags {}

    /**
     * If set, the recipient of this Intent will be granted permission to
     * perform read operations on the URI in the Intent's data and any URIs
     * specified in its ClipData.  When applying to an Intent's ClipData,
     * all URIs as well as recursive traversals through data or other ClipData
     * in Intent items will be granted; only the grant flags of the top-level
     * Intent are used.
     */
    public static final int FLAG_GRANT_READ_URI_PERMISSION = 0x00000001;
    /**
     * If set, the recipient of this Intent will be granted permission to
     * perform write operations on the URI in the Intent's data and any URIs
     * specified in its ClipData.  When applying to an Intent's ClipData,
     * all URIs as well as recursive traversals through data or other ClipData
     * in Intent items will be granted; only the grant flags of the top-level
     * Intent are used.
     */
    public static final int FLAG_GRANT_WRITE_URI_PERMISSION = 0x00000002;
    /**
     * Can be set by the caller to indicate that this Intent is coming from
     * a background operation, not from direct user interaction.
     */
    public static final int FLAG_FROM_BACKGROUND = 0x00000004;
    /**
     * A flag you can enable for debugging: when set, log messages will be
     * printed during the resolution of this intent to show you what has
     * been found to create the final resolved list.
     */
    public static final int FLAG_DEBUG_LOG_RESOLUTION = 0x00000008;
    /**
     * If set, this intent will not match any components in packages that
     * are currently stopped.  If this is not set, then the default behavior
     * is to include such applications in the result.
     */
    public static final int FLAG_EXCLUDE_STOPPED_PACKAGES = 0x00000010;
    /**
     * If set, this intent will always match any components in packages that
     * are currently stopped.  This is the default behavior when
     * {@link #FLAG_EXCLUDE_STOPPED_PACKAGES} is not set.  If both of these
     * flags are set, this one wins (it allows overriding of exclude for
     * places where the framework may automatically set the exclude flag).
     */
    public static final int FLAG_INCLUDE_STOPPED_PACKAGES = 0x00000020;

    /**
     * When combined with {@link #FLAG_GRANT_READ_URI_PERMISSION} and/or
     * {@link #FLAG_GRANT_WRITE_URI_PERMISSION}, the URI permission grant can be
     * persisted across device reboots until explicitly revoked with
     * {@link Context#revokeUriPermission(Uri, int)}. This flag only offers the
     * grant for possible persisting; the receiving application must call
     * {@link ContentResolver#takePersistableUriPermission(Uri, int)} to
     * actually persist.
     *
     * @see ContentResolver#takePersistableUriPermission(Uri, int)
     * @see ContentResolver#releasePersistableUriPermission(Uri, int)
     * @see ContentResolver#getPersistedUriPermissions()
     * @see ContentResolver#getOutgoingPersistedUriPermissions()
     */
    public static final int FLAG_GRANT_PERSISTABLE_URI_PERMISSION = 0x00000040;

    /**
     * When combined with {@link #FLAG_GRANT_READ_URI_PERMISSION} and/or
     * {@link #FLAG_GRANT_WRITE_URI_PERMISSION}, the URI permission grant
     * applies to any URI that is a prefix match against the original granted
     * URI. (Without this flag, the URI must match exactly for access to be
     * granted.) Another URI is considered a prefix match only when scheme,
     * authority, and all path segments defined by the prefix are an exact
     * match.
     */
    public static final int FLAG_GRANT_PREFIX_URI_PERMISSION = 0x00000080;

    /**
     * Flag used to automatically match intents based on their Direct Boot
     * awareness and the current user state.
     * <p>
     * Since the default behavior is to automatically apply the current user
     * state, this is effectively a sentinel value that doesn't change the
     * output of any queries based on its presence or absence.
     * <p>
     * Instead, this value can be useful in conjunction with
     * {@link android.os.StrictMode.VmPolicy.Builder#detectImplicitDirectBoot()}
     * to detect when a caller is relying on implicit automatic matching,
     * instead of confirming the explicit behavior they want.
     */
    public static final int FLAG_DIRECT_BOOT_AUTO = 0x00000100;

    /** {@hide} */
    @Deprecated
    public static final int FLAG_DEBUG_TRIAGED_MISSING = FLAG_DIRECT_BOOT_AUTO;

    /**
     * Internal flag used to indicate ephemeral applications should not be
     * considered when resolving the intent.
     *
     * @hide
     */
    public static final int FLAG_IGNORE_EPHEMERAL = 0x80000000;

    /**
     * If set, the new activity is not kept in the history stack.  As soon as
     * the user navigates away from it, the activity is finished.  This may also
     * be set with the {@link android.R.styleable#AndroidManifestActivity_noHistory
     * noHistory} attribute.
     *
     * <p>If set, {@link android.app.Activity#onActivityResult onActivityResult()}
     * is never invoked when the current activity starts a new activity which
     * sets a result and finishes.
     */
    public static final int FLAG_ACTIVITY_NO_HISTORY = 0x40000000;
    /**
     * If set, the activity will not be launched if it is already running
     * at the top of the history stack.  See
     * <a href="{@docRoot}guide/topics/fundamentals/tasks-and-back-stack.html#TaskLaunchModes">
     * Tasks and Back Stack</a> for more information.
     */
    public static final int FLAG_ACTIVITY_SINGLE_TOP = 0x20000000;
    /**
     * If set, this activity will become the start of a new task on this
     * history stack.  A task (from the activity that started it to the
     * next task activity) defines an atomic group of activities that the
     * user can move to.  Tasks can be moved to the foreground and background;
     * all of the activities inside of a particular task always remain in
     * the same order.  See
     * <a href="{@docRoot}guide/topics/fundamentals/tasks-and-back-stack.html">Tasks and Back
     * Stack</a> for more information about tasks.
     *
     * <p>This flag is generally used by activities that want
     * to present a "launcher" style behavior: they give the user a list of
     * separate things that can be done, which otherwise run completely
     * independently of the activity launching them.
     *
     * <p>When using this flag, if a task is already running for the activity
     * you are now starting, then a new activity will not be started; instead,
     * the current task will simply be brought to the front of the screen with
     * the state it was last in.  See {@link #FLAG_ACTIVITY_MULTIPLE_TASK} for a flag
     * to disable this behavior.
     *
     * <p>This flag can not be used when the caller is requesting a result from
     * the activity being launched.
     */
    public static final int FLAG_ACTIVITY_NEW_TASK = 0x10000000;
    /**
     * This flag is used to create a new task and launch an activity into it.
     * This flag is always paired with either {@link #FLAG_ACTIVITY_NEW_DOCUMENT}
     * or {@link #FLAG_ACTIVITY_NEW_TASK}. In both cases these flags alone would
     * search through existing tasks for ones matching this Intent. Only if no such
     * task is found would a new task be created. When paired with
     * FLAG_ACTIVITY_MULTIPLE_TASK both of these behaviors are modified to skip
     * the search for a matching task and unconditionally start a new task.
     *
     * <strong>When used with {@link #FLAG_ACTIVITY_NEW_TASK} do not use this
     * flag unless you are implementing your own
     * top-level application launcher.</strong>  Used in conjunction with
     * {@link #FLAG_ACTIVITY_NEW_TASK} to disable the
     * behavior of bringing an existing task to the foreground.  When set,
     * a new task is <em>always</em> started to host the Activity for the
     * Intent, regardless of whether there is already an existing task running
     * the same thing.
     *
     * <p><strong>Because the default system does not include graphical task management,
     * you should not use this flag unless you provide some way for a user to
     * return back to the tasks you have launched.</strong>
     *
     * See {@link #FLAG_ACTIVITY_NEW_DOCUMENT} for details of this flag's use for
     * creating new document tasks.
     *
     * <p>This flag is ignored if one of {@link #FLAG_ACTIVITY_NEW_TASK} or
     * {@link #FLAG_ACTIVITY_NEW_DOCUMENT} is not also set.
     *
     * <p>See
     * <a href="{@docRoot}guide/topics/fundamentals/tasks-and-back-stack.html">Tasks and Back
     * Stack</a> for more information about tasks.
     *
     * @see #FLAG_ACTIVITY_NEW_DOCUMENT
     * @see #FLAG_ACTIVITY_NEW_TASK
     */
    public static final int FLAG_ACTIVITY_MULTIPLE_TASK = 0x08000000;
    /**
     * If set, and the activity being launched is already running in the
     * current task, then instead of launching a new instance of that activity,
     * all of the other activities on top of it will be closed and this Intent
     * will be delivered to the (now on top) old activity as a new Intent.
     *
     * <p>For example, consider a task consisting of the activities: A, B, C, D.
     * If D calls startActivity() with an Intent that resolves to the component
     * of activity B, then C and D will be finished and B receive the given
     * Intent, resulting in the stack now being: A, B.
     *
     * <p>The currently running instance of activity B in the above example will
     * either receive the new intent you are starting here in its
     * onNewIntent() method, or be itself finished and restarted with the
     * new intent.  If it has declared its launch mode to be "multiple" (the
     * default) and you have not set {@link #FLAG_ACTIVITY_SINGLE_TOP} in
     * the same intent, then it will be finished and re-created; for all other
     * launch modes or if {@link #FLAG_ACTIVITY_SINGLE_TOP} is set then this
     * Intent will be delivered to the current instance's onNewIntent().
     *
     * <p>This launch mode can also be used to good effect in conjunction with
     * {@link #FLAG_ACTIVITY_NEW_TASK}: if used to start the root activity
     * of a task, it will bring any currently running instance of that task
     * to the foreground, and then clear it to its root state.  This is
     * especially useful, for example, when launching an activity from the
     * notification manager.
     *
     * <p>See
     * <a href="{@docRoot}guide/topics/fundamentals/tasks-and-back-stack.html">Tasks and Back
     * Stack</a> for more information about tasks.
     */
    public static final int FLAG_ACTIVITY_CLEAR_TOP = 0x04000000;
    /**
     * If set and this intent is being used to launch a new activity from an
     * existing one, then the reply target of the existing activity will be
     * transferred to the new activity.  This way, the new activity can call
     * {@link android.app.Activity#setResult} and have that result sent back to
     * the reply target of the original activity.
     */
    public static final int FLAG_ACTIVITY_FORWARD_RESULT = 0x02000000;
    /**
     * If set and this intent is being used to launch a new activity from an
     * existing one, the current activity will not be counted as the top
     * activity for deciding whether the new intent should be delivered to
     * the top instead of starting a new one.  The previous activity will
     * be used as the top, with the assumption being that the current activity
     * will finish itself immediately.
     */
    public static final int FLAG_ACTIVITY_PREVIOUS_IS_TOP = 0x01000000;
    /**
     * If set, the new activity is not kept in the list of recently launched
     * activities.
     */
    public static final int FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS = 0x00800000;
    /**
     * This flag is not normally set by application code, but set for you by
     * the system as described in the
     * {@link android.R.styleable#AndroidManifestActivity_launchMode
     * launchMode} documentation for the singleTask mode.
     */
    public static final int FLAG_ACTIVITY_BROUGHT_TO_FRONT = 0x00400000;
    /**
     * If set, and this activity is either being started in a new task or
     * bringing to the top an existing task, then it will be launched as
     * the front door of the task.  This will result in the application of
     * any affinities needed to have that task in the proper state (either
     * moving activities to or from it), or simply resetting that task to
     * its initial state if needed.
     *
     * @see android.R.attr#allowTaskReparenting
     * @see android.R.attr#clearTaskOnLaunch
     * @see android.R.attr#finishOnTaskLaunch
     */
    public static final int FLAG_ACTIVITY_RESET_TASK_IF_NEEDED = 0x00200000;
    /**
     * This flag is not normally set by application code, but set for you by
     * the system if this activity is being launched from history.
     */
    public static final int FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY = 0x00100000;
    /**
     * @deprecated As of API 21 this performs identically to
     * {@link #FLAG_ACTIVITY_NEW_DOCUMENT} which should be used instead of this.
     */
    @Deprecated
    public static final int FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET = 0x00080000;
    /**
     * This flag is used to open a document into a new task rooted at the activity launched
     * by this Intent. Through the use of this flag, or its equivalent attribute,
     * {@link android.R.attr#documentLaunchMode} multiple instances of the same activity
     * containing different documents will appear in the recent tasks list.
     *
     * <p>The use of the activity attribute form of this,
     * {@link android.R.attr#documentLaunchMode}, is
     * preferred over the Intent flag described here. The attribute form allows the
     * Activity to specify multiple document behavior for all launchers of the Activity
     * whereas using this flag requires each Intent that launches the Activity to specify it.
     *
     * <p>Note that the default semantics of this flag w.r.t. whether the recents entry for
     * it is kept after the activity is finished is different than the use of
     * {@link #FLAG_ACTIVITY_NEW_TASK} and {@link android.R.attr#documentLaunchMode} -- if
     * this flag is being used to create a new recents entry, then by default that entry
     * will be removed once the activity is finished.  You can modify this behavior with
     * {@link #FLAG_ACTIVITY_RETAIN_IN_RECENTS}.
     *
     * <p>FLAG_ACTIVITY_NEW_DOCUMENT may be used in conjunction with {@link
     * #FLAG_ACTIVITY_MULTIPLE_TASK}. When used alone it is the
     * equivalent of the Activity manifest specifying {@link
     * android.R.attr#documentLaunchMode}="intoExisting". When used with
     * FLAG_ACTIVITY_MULTIPLE_TASK it is the equivalent of the Activity manifest specifying
     * {@link android.R.attr#documentLaunchMode}="always". The flag is ignored even in
     * conjunction with {@link #FLAG_ACTIVITY_MULTIPLE_TASK} when the Activity manifest specifies
     * {@link android.R.attr#documentLaunchMode}="never".
     *
     * Refer to {@link android.R.attr#documentLaunchMode} for more information.
     *
     * @see android.R.attr#documentLaunchMode
     * @see #FLAG_ACTIVITY_MULTIPLE_TASK
     */
    public static final int FLAG_ACTIVITY_NEW_DOCUMENT = FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET;
    /**
     * If set, this flag will prevent the normal {@link android.app.Activity#onUserLeaveHint}
     * callback from occurring on the current frontmost activity before it is
     * paused as the newly-started activity is brought to the front.
     *
     * <p>Typically, an activity can rely on that callback to indicate that an
     * explicit user action has caused their activity to be moved out of the
     * foreground. The callback marks an appropriate point in the activity's
     * lifecycle for it to dismiss any notifications that it intends to display
     * "until the user has seen them," such as a blinking LED.
     *
     * <p>If an activity is ever started via any non-user-driven events such as
     * phone-call receipt or an alarm handler, this flag should be passed to {@link
     * Context#startActivity Context.startActivity}, ensuring that the pausing
     * activity does not think the user has acknowledged its notification.
     */
    public static final int FLAG_ACTIVITY_NO_USER_ACTION = 0x00040000;
    /**
     * If set in an Intent passed to {@link Context#startActivity Context.startActivity()},
     * this flag will cause the launched activity to be brought to the front of its
     * task's history stack if it is already running.
     *
     * <p>For example, consider a task consisting of four activities: A, B, C, D.
     * If D calls startActivity() with an Intent that resolves to the component
     * of activity B, then B will be brought to the front of the history stack,
     * with this resulting order:  A, C, D, B.
     *
     * This flag will be ignored if {@link #FLAG_ACTIVITY_CLEAR_TOP} is also
     * specified.
     */
    public static final int FLAG_ACTIVITY_REORDER_TO_FRONT = 0X00020000;
    /**
     * If set in an Intent passed to {@link Context#startActivity Context.startActivity()},
     * this flag will prevent the system from applying an activity transition
     * animation to go to the next activity state.  This doesn't mean an
     * animation will never run -- if another activity change happens that doesn't
     * specify this flag before the activity started here is displayed, then
     * that transition will be used.  This flag can be put to good use
     * when you are going to do a series of activity operations but the
     * animation seen by the user shouldn't be driven by the first activity
     * change but rather a later one.
     */
    public static final int FLAG_ACTIVITY_NO_ANIMATION = 0X00010000;
    /**
     * If set in an Intent passed to {@link Context#startActivity Context.startActivity()},
     * this flag will cause any existing task that would be associated with the
     * activity to be cleared before the activity is started.  That is, the activity
     * becomes the new root of an otherwise empty task, and any old activities
     * are finished.  This can only be used in conjunction with {@link #FLAG_ACTIVITY_NEW_TASK}.
     */
    public static final int FLAG_ACTIVITY_CLEAR_TASK = 0X00008000;
    /**
     * If set in an Intent passed to {@link Context#startActivity Context.startActivity()},
     * this flag will cause a newly launching task to be placed on top of the current
     * home activity task (if there is one).  That is, pressing back from the task
     * will always return the user to home even if that was not the last activity they
     * saw.   This can only be used in conjunction with {@link #FLAG_ACTIVITY_NEW_TASK}.
     */
    public static final int FLAG_ACTIVITY_TASK_ON_HOME = 0X00004000;
    /**
     * By default a document created by {@link #FLAG_ACTIVITY_NEW_DOCUMENT} will
     * have its entry in recent tasks removed when the user closes it (with back
     * or however else it may finish()). If you would like to instead allow the
     * document to be kept in recents so that it can be re-launched, you can use
     * this flag. When set and the task's activity is finished, the recents
     * entry will remain in the interface for the user to re-launch it, like a
     * recents entry for a top-level application.
     * <p>
     * The receiving activity can override this request with
     * {@link android.R.attr#autoRemoveFromRecents} or by explcitly calling
     * {@link android.app.Activity#finishAndRemoveTask()
     * Activity.finishAndRemoveTask()}.
     */
    public static final int FLAG_ACTIVITY_RETAIN_IN_RECENTS = 0x00002000;

    /**
     * This flag is only used for split-screen multi-window mode. The new activity will be displayed
     * adjacent to the one launching it. This can only be used in conjunction with
     * {@link #FLAG_ACTIVITY_NEW_TASK}. Also, setting {@link #FLAG_ACTIVITY_MULTIPLE_TASK} is
     * required if you want a new instance of an existing activity to be created.
     */
    public static final int FLAG_ACTIVITY_LAUNCH_ADJACENT = 0x00001000;


    /**
     * If set in an Intent passed to {@link Context#startActivity Context.startActivity()},
     * this flag will attempt to launch an instant app if no full app on the device can already
     * handle the intent.
     * <p>
     * When attempting to resolve instant apps externally, the following {@link Intent} properties
     * are supported:
     * <ul>
     *     <li>{@link Intent#setAction(String)}</li>
     *     <li>{@link Intent#addCategory(String)}</li>
     *     <li>{@link Intent#setData(Uri)}</li>
     *     <li>{@link Intent#setType(String)}</li>
     *     <li>{@link Intent#setPackage(String)}</li>
     *     <li>{@link Intent#addFlags(int)}</li>
     * </ul>
     * <p>
     * In the case that no instant app can be found, the installer will be launched to notify the
     * user that the intent could not be resolved. On devices that do not support instant apps,
     * the flag will be ignored.
     */
    public static final int FLAG_ACTIVITY_MATCH_EXTERNAL = 0x00000800;

    /**
     * If set in an intent passed to {@link Context#startActivity Context.startActivity()}, this
     * flag will only launch the intent if it resolves to a result that is not a browser. If no such
     * result exists, an {@link ActivityNotFoundException} will be thrown.
     */
    public static final int FLAG_ACTIVITY_REQUIRE_NON_BROWSER = 0x00000400;

    /**
     * If set in an intent passed to {@link Context#startActivity Context.startActivity()}, this
     * flag will only launch the intent if it resolves to a single result. If no such result exists
     * or if the system chooser would otherwise be displayed, an {@link ActivityNotFoundException}
     * will be thrown.
     */
    public static final int FLAG_ACTIVITY_REQUIRE_DEFAULT = 0x00000200;

    /**
     * If set, when sending a broadcast only registered receivers will be
     * called -- no BroadcastReceiver components will be launched.
     */
    public static final int FLAG_RECEIVER_REGISTERED_ONLY = 0x40000000;
    /**
     * If set, when sending a broadcast the new broadcast will replace
     * any existing pending broadcast that matches it.  Matching is defined
     * by {@link Intent#filterEquals(Intent) Intent.filterEquals} returning
     * true for the intents of the two broadcasts.  When a match is found,
     * the new broadcast (and receivers associated with it) will replace the
     * existing one in the pending broadcast list, remaining at the same
     * position in the list.
     *
     * <p>This flag is most typically used with sticky broadcasts, which
     * only care about delivering the most recent values of the broadcast
     * to their receivers.
     */
    public static final int FLAG_RECEIVER_REPLACE_PENDING = 0x20000000;
    /**
     * If set, when sending a broadcast the recipient is allowed to run at
     * foreground priority, with a shorter timeout interval.  During normal
     * broadcasts the receivers are not automatically hoisted out of the
     * background priority class.
     */
    public static final int FLAG_RECEIVER_FOREGROUND = 0x10000000;
    /**
     * If set, when sending a broadcast the recipient will be run on the offload queue.
     *
     * @hide
     */
    public static final int FLAG_RECEIVER_OFFLOAD = 0x80000000;
    /**
     * If set, when sending a broadcast the recipient will run on the system dedicated queue.
     *
     * @hide
     */
    public static final int FLAG_RECEIVER_OFFLOAD_FOREGROUND = 0x00000800;

    /**
     * If this is an ordered broadcast, don't allow receivers to abort the broadcast.
     * They can still propagate results through to later receivers, but they can not prevent
     * later receivers from seeing the broadcast.
     */
    public static final int FLAG_RECEIVER_NO_ABORT = 0x08000000;
    /**
     * If set, when sending a broadcast <i>before the system has fully booted up
     * (which is even before {@link #ACTION_LOCKED_BOOT_COMPLETED} has been sent)"</i> only
     * registered receivers will be called -- no BroadcastReceiver components
     * will be launched.  Sticky intent state will be recorded properly even
     * if no receivers wind up being called.  If {@link #FLAG_RECEIVER_REGISTERED_ONLY}
     * is specified in the broadcast intent, this flag is unnecessary.
     *
     * <p>This flag is only for use by system services (even services from mainline modules) as a
     * convenience to avoid having to implement a more complex mechanism around detection
     * of boot completion.
     *
     * <p>This is useful to system server mainline modules
     *
     * @hide
     */
    @SystemApi
    public static final int FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT = 0x04000000;
    /**
     * Set when this broadcast is for a boot upgrade, a special mode that
     * allows the broadcast to be sent before the system is ready and launches
     * the app process with no providers running in it.
     * @hide
     */
    public static final int FLAG_RECEIVER_BOOT_UPGRADE = 0x02000000;
    /**
     * If set, the broadcast will always go to manifest receivers in background (cached
     * or not running) apps, regardless of whether that would be done by default.  By
     * default they will only receive broadcasts if the broadcast has specified an
     * explicit component or package name.
     *
     * NOTE: dumpstate uses this flag numerically, so when its value is changed
     * the broadcast code there must also be changed to match.
     *
     * @hide
     */
    @SystemApi
    public static final int FLAG_RECEIVER_INCLUDE_BACKGROUND = 0x01000000;
    /**
     * If set, the broadcast will never go to manifest receivers in background (cached
     * or not running) apps, regardless of whether that would be done by default.  By
     * default they will receive broadcasts if the broadcast has specified an
     * explicit component or package name.
     * @hide
     */
    public static final int FLAG_RECEIVER_EXCLUDE_BACKGROUND = 0x00800000;
    /**
     * If set, this broadcast is being sent from the shell.
     * @hide
     */
    public static final int FLAG_RECEIVER_FROM_SHELL = 0x00400000;

    /**
     * If set, the broadcast will be visible to receivers in Instant Apps. By default Instant Apps
     * will not receive broadcasts.
     *
     * <em>This flag has no effect when used by an Instant App.</em>
     */
    public static final int FLAG_RECEIVER_VISIBLE_TO_INSTANT_APPS = 0x00200000;

    /**
     * @hide Flags that can't be changed with PendingIntent.
     */
    public static final int IMMUTABLE_FLAGS = FLAG_GRANT_READ_URI_PERMISSION
            | FLAG_GRANT_WRITE_URI_PERMISSION | FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            | FLAG_GRANT_PREFIX_URI_PERMISSION;

    /**
     * Local flag indicating this instance was created by copy constructor.
     */
    private static final int LOCAL_FLAG_FROM_COPY = 1 << 0;

    /**
     * Local flag indicating this instance was created from a {@link Parcel}.
     */
    private static final int LOCAL_FLAG_FROM_PARCEL = 1 << 1;

    /**
     * Local flag indicating this instance was delivered through a protected
     * component, such as an activity that requires a signature permission, or a
     * protected broadcast. Note that this flag <em>cannot</em> be recursively
     * applied to any contained instances, since a malicious app may have
     * controlled them via {@link #fillIn(Intent, int)}.
     */
    private static final int LOCAL_FLAG_FROM_PROTECTED_COMPONENT = 1 << 2;

    /**
     * Local flag indicating this instance had unfiltered extras copied into it. This could be
     * from either {@link #putExtras(Intent)} when an unparceled Intent is provided or {@link
     * #putExtras(Bundle)} when the provided Bundle has not been unparceled.
     */
    private static final int LOCAL_FLAG_UNFILTERED_EXTRAS = 1 << 3;

    /**
     * Local flag indicating this instance was created from a {@link Uri}.
     */
    private static final int LOCAL_FLAG_FROM_URI = 1 << 4;

    /**
     * Local flag indicating this instance was created by the system.
     */
    /** @hide */
    public static final int LOCAL_FLAG_FROM_SYSTEM = 1 << 5;

    // ---------------------------------------------------------------------
    // ---------------------------------------------------------------------
    // toUri() and parseUri() options.

    /** @hide */
    @IntDef(flag = true, prefix = {"URI_"}, value = {
            URI_ALLOW_UNSAFE,
            URI_ANDROID_APP_SCHEME,
            URI_INTENT_SCHEME,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface UriFlags {}

    /**
     * Flag for use with {@link #toUri} and {@link #parseUri}: the URI string
     * always has the "intent:" scheme.  This syntax can be used when you want
     * to later disambiguate between URIs that are intended to describe an
     * Intent vs. all others that should be treated as raw URIs.  When used
     * with {@link #parseUri}, any other scheme will result in a generic
     * VIEW action for that raw URI.
     */
    public static final int URI_INTENT_SCHEME = 1<<0;

    /**
     * Flag for use with {@link #toUri} and {@link #parseUri}: the URI string
     * always has the "android-app:" scheme.  This is a variation of
     * {@link #URI_INTENT_SCHEME} whose format is simpler for the case of an
     * http/https URI being delivered to a specific package name.  The format
     * is:
     *
     * <pre class="prettyprint">
     * android-app://{package_id}[/{scheme}[/{host}[/{path}]]][#Intent;{...}]</pre>
     *
     * <p>In this scheme, only the <code>package_id</code> is required.  If you include a host,
     * you must also include a scheme; including a path also requires both a host and a scheme.
     * The final #Intent; fragment can be used without a scheme, host, or path.
     * Note that this can not be
     * used with intents that have a {@link #setSelector}, since the base intent
     * will always have an explicit package name.</p>
     *
     * <p>Some examples of how this scheme maps to Intent objects:</p>
     * <table border="2" width="85%" align="center" frame="hsides" rules="rows">
     *     <colgroup align="left" />
     *     <colgroup align="left" />
     *     <thead>
     *     <tr><th>URI</th> <th>Intent</th></tr>
     *     </thead>
     *
     *     <tbody>
     *     <tr><td><code>android-app://com.example.app</code></td>
     *         <td><table style="margin:0;border:0;cellpadding:0;cellspacing:0">
     *             <tr><td>Action: </td><td>{@link #ACTION_MAIN}</td></tr>
     *             <tr><td>Package: </td><td><code>com.example.app</code></td></tr>
     *         </table></td>
     *     </tr>
     *     <tr><td><code>android-app://com.example.app/http/example.com</code></td>
     *         <td><table style="margin:0;border:0;cellpadding:0;cellspacing:0">
     *             <tr><td>Action: </td><td>{@link #ACTION_VIEW}</td></tr>
     *             <tr><td>Data: </td><td><code>http://example.com/</code></td></tr>
     *             <tr><td>Package: </td><td><code>com.example.app</code></td></tr>
     *         </table></td>
     *     </tr>
     *     <tr><td><code>android-app://com.example.app/http/example.com/foo?1234</code></td>
     *         <td><table style="margin:0;border:0;cellpadding:0;cellspacing:0">
     *             <tr><td>Action: </td><td>{@link #ACTION_VIEW}</td></tr>
     *             <tr><td>Data: </td><td><code>http://example.com/foo?1234</code></td></tr>
     *             <tr><td>Package: </td><td><code>com.example.app</code></td></tr>
     *         </table></td>
     *     </tr>
     *     <tr><td><code>android-app://com.example.app/<br />#Intent;action=com.example.MY_ACTION;end</code></td>
     *         <td><table style="margin:0;border:0;cellpadding:0;cellspacing:0">
     *             <tr><td>Action: </td><td><code>com.example.MY_ACTION</code></td></tr>
     *             <tr><td>Package: </td><td><code>com.example.app</code></td></tr>
     *         </table></td>
     *     </tr>
     *     <tr><td><code>android-app://com.example.app/http/example.com/foo?1234<br />#Intent;action=com.example.MY_ACTION;end</code></td>
     *         <td><table style="margin:0;border:0;cellpadding:0;cellspacing:0">
     *             <tr><td>Action: </td><td><code>com.example.MY_ACTION</code></td></tr>
     *             <tr><td>Data: </td><td><code>http://example.com/foo?1234</code></td></tr>
     *             <tr><td>Package: </td><td><code>com.example.app</code></td></tr>
     *         </table></td>
     *     </tr>
     *     <tr><td><code>android-app://com.example.app/<br />#Intent;action=com.example.MY_ACTION;<br />i.some_int=100;S.some_str=hello;end</code></td>
     *         <td><table border="" style="margin:0" >
     *             <tr><td>Action: </td><td><code>com.example.MY_ACTION</code></td></tr>
     *             <tr><td>Package: </td><td><code>com.example.app</code></td></tr>
     *             <tr><td>Extras: </td><td><code>some_int=(int)100<br />some_str=(String)hello</code></td></tr>
     *         </table></td>
     *     </tr>
     *     </tbody>
     * </table>
     */
    public static final int URI_ANDROID_APP_SCHEME = 1<<1;

    /**
     * Flag for use with {@link #toUri} and {@link #parseUri}: allow parsing
     * of unsafe information.  In particular, the flags {@link #FLAG_GRANT_READ_URI_PERMISSION},
     * {@link #FLAG_GRANT_WRITE_URI_PERMISSION}, {@link #FLAG_GRANT_PERSISTABLE_URI_PERMISSION},
     * and {@link #FLAG_GRANT_PREFIX_URI_PERMISSION} flags can not be set, so that the
     * generated Intent can not cause unexpected data access to happen.
     *
     * <p>If you do not trust the source of the URI being parsed, you should still do further
     * processing to protect yourself from it.  In particular, when using it to start an
     * activity you should usually add in {@link #CATEGORY_BROWSABLE} to limit the activities
     * that can handle it.</p>
     */
    public static final int URI_ALLOW_UNSAFE = 1<<2;

    // ---------------------------------------------------------------------

    private String mAction;
    private Uri mData;
    private String mType;
    private String mIdentifier;
    private String mPackage;
    private ComponentName mComponent;
    private int mFlags;
    /** Set of in-process flags which are never parceled */
    private int mLocalFlags;
    private ArraySet<String> mCategories;
    @UnsupportedAppUsage
    private Bundle mExtras;
    private Rect mSourceBounds;
    private Intent mSelector;
    private ClipData mClipData;
    private int mContentUserHint = UserHandle.USER_CURRENT;
    /** Token to track instant app launches. Local only; do not copy cross-process. */
    private String mLaunchToken;
    private Intent mOriginalIntent; // Used for the experimental "component alias" feature.

    // ---------------------------------------------------------------------

    private static final int COPY_MODE_ALL = 0;
    private static final int COPY_MODE_FILTER = 1;
    private static final int COPY_MODE_HISTORY = 2;

    /** @hide */
    @IntDef(prefix = { "COPY_MODE_" }, value = {
            COPY_MODE_ALL,
            COPY_MODE_FILTER,
            COPY_MODE_HISTORY
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CopyMode {}

    /**
     * Create an empty intent.
     */
    public Intent() {
    }

    /**
     * Copy constructor.
     */
    public Intent(Intent o) {
        this(o, COPY_MODE_ALL);
    }

    private Intent(Intent o, @CopyMode int copyMode) {
        this.mAction = o.mAction;
        this.mData = o.mData;
        this.mType = o.mType;
        this.mIdentifier = o.mIdentifier;
        this.mPackage = o.mPackage;
        this.mComponent = o.mComponent;
        this.mOriginalIntent = o.mOriginalIntent;

        if (o.mCategories != null) {
            this.mCategories = new ArraySet<>(o.mCategories);
        }

        // Inherit flags from the original, plus mark that we were
        // created by this copy constructor
        this.mLocalFlags = o.mLocalFlags;
        this.mLocalFlags |= LOCAL_FLAG_FROM_COPY;

        if (copyMode != COPY_MODE_FILTER) {
            this.mFlags = o.mFlags;
            this.mContentUserHint = o.mContentUserHint;
            this.mLaunchToken = o.mLaunchToken;
            if (o.mSourceBounds != null) {
                this.mSourceBounds = new Rect(o.mSourceBounds);
            }
            if (o.mSelector != null) {
                this.mSelector = new Intent(o.mSelector);
            }

            if (copyMode != COPY_MODE_HISTORY) {
                if (o.mExtras != null) {
                    this.mExtras = new Bundle(o.mExtras);
                }
                if (o.mClipData != null) {
                    this.mClipData = new ClipData(o.mClipData);
                }
            } else {
                if (o.mExtras != null && !o.mExtras.isDefinitelyEmpty()) {
                    this.mExtras = Bundle.STRIPPED;
                }

                // Also set "stripped" clip data when we ever log mClipData in the (broadcast)
                // history.
            }
        }
    }

    @Override
    public Object clone() {
        return new Intent(this);
    }

    /**
     * Make a clone of only the parts of the Intent that are relevant for
     * filter matching: the action, data, type, component, and categories.
     */
    public @NonNull Intent cloneFilter() {
        return new Intent(this, COPY_MODE_FILTER);
    }

    /**
     * Create an intent with a given action.  All other fields (data, type,
     * class) are null.  Note that the action <em>must</em> be in a
     * namespace because Intents are used globally in the system -- for
     * example the system VIEW action is android.intent.action.VIEW; an
     * application's custom action would be something like
     * com.google.app.myapp.CUSTOM_ACTION.
     *
     * @param action The Intent action, such as ACTION_VIEW.
     */
    public Intent(String action) {
        setAction(action);
    }

    /**
     * Create an intent with a given action and for a given data url.  Note
     * that the action <em>must</em> be in a namespace because Intents are
     * used globally in the system -- for example the system VIEW action is
     * android.intent.action.VIEW; an application's custom action would be
     * something like com.google.app.myapp.CUSTOM_ACTION.
     *
     * <p><em>Note: scheme and host name matching in the Android framework is
     * case-sensitive, unlike the formal RFC.  As a result,
     * you should always ensure that you write your Uri with these elements
     * using lower case letters, and normalize any Uris you receive from
     * outside of Android to ensure the scheme and host is lower case.</em></p>
     *
     * @param action The Intent action, such as ACTION_VIEW.
     * @param uri The Intent data URI.
     */
    public Intent(String action, Uri uri) {
        setAction(action);
        mData = uri;
    }

    /**
     * Create an intent for a specific component.  All other fields (action, data,
     * type, class) are null, though they can be modified later with explicit
     * calls.  This provides a convenient way to create an intent that is
     * intended to execute a hard-coded class name, rather than relying on the
     * system to find an appropriate class for you; see {@link #setComponent}
     * for more information on the repercussions of this.
     *
     * @param packageContext A Context of the application package implementing
     * this class.
     * @param cls The component class that is to be used for the intent.
     *
     * @see #setClass
     * @see #setComponent
     * @see #Intent(String, android.net.Uri , Context, Class)
     */
    public Intent(Context packageContext, Class<?> cls) {
        mComponent = new ComponentName(packageContext, cls);
    }

    /**
     * Create an intent for a specific component with a specified action and data.
     * This is equivalent to using {@link #Intent(String, android.net.Uri)} to
     * construct the Intent and then calling {@link #setClass} to set its
     * class.
     *
     * <p><em>Note: scheme and host name matching in the Android framework is
     * case-sensitive, unlike the formal RFC.  As a result,
     * you should always ensure that you write your Uri with these elements
     * using lower case letters, and normalize any Uris you receive from
     * outside of Android to ensure the scheme and host is lower case.</em></p>
     *
     * @param action The Intent action, such as ACTION_VIEW.
     * @param uri The Intent data URI.
     * @param packageContext A Context of the application package implementing
     * this class.
     * @param cls The component class that is to be used for the intent.
     *
     * @see #Intent(String, android.net.Uri)
     * @see #Intent(Context, Class)
     * @see #setClass
     * @see #setComponent
     */
    public Intent(String action, Uri uri,
            Context packageContext, Class<?> cls) {
        setAction(action);
        mData = uri;
        mComponent = new ComponentName(packageContext, cls);
    }

    /**
     * Create an intent to launch the main (root) activity of a task.  This
     * is the Intent that is started when the application's is launched from
     * Home.  For anything else that wants to launch an application in the
     * same way, it is important that they use an Intent structured the same
     * way, and can use this function to ensure this is the case.
     *
     * <p>The returned Intent has the given Activity component as its explicit
     * component, {@link #ACTION_MAIN} as its action, and includes the
     * category {@link #CATEGORY_LAUNCHER}.  This does <em>not</em> have
     * {@link #FLAG_ACTIVITY_NEW_TASK} set, though typically you will want
     * to do that through {@link #addFlags(int)} on the returned Intent.
     *
     * @param mainActivity The main activity component that this Intent will
     * launch.
     * @return Returns a newly created Intent that can be used to launch the
     * activity as a main application entry.
     *
     * @see #setClass
     * @see #setComponent
     */
    public static Intent makeMainActivity(ComponentName mainActivity) {
        Intent intent = new Intent(ACTION_MAIN);
        intent.setComponent(mainActivity);
        intent.addCategory(CATEGORY_LAUNCHER);
        return intent;
    }

    /**
     * Make an Intent for the main activity of an application, without
     * specifying a specific activity to run but giving a selector to find
     * the activity.  This results in a final Intent that is structured
     * the same as when the application is launched from
     * Home.  For anything else that wants to launch an application in the
     * same way, it is important that they use an Intent structured the same
     * way, and can use this function to ensure this is the case.
     *
     * <p>The returned Intent has {@link #ACTION_MAIN} as its action, and includes the
     * category {@link #CATEGORY_LAUNCHER}.  This does <em>not</em> have
     * {@link #FLAG_ACTIVITY_NEW_TASK} set, though typically you will want
     * to do that through {@link #addFlags(int)} on the returned Intent.
     *
     * @param selectorAction The action name of the Intent's selector.
     * @param selectorCategory The name of a category to add to the Intent's
     * selector.
     * @return Returns a newly created Intent that can be used to launch the
     * activity as a main application entry.
     *
     * @see #setSelector(Intent)
     */
    public static Intent makeMainSelectorActivity(String selectorAction,
            String selectorCategory) {
        Intent intent = new Intent(ACTION_MAIN);
        intent.addCategory(CATEGORY_LAUNCHER);
        Intent selector = new Intent();
        selector.setAction(selectorAction);
        selector.addCategory(selectorCategory);
        intent.setSelector(selector);
        return intent;
    }

    /**
     * Make an Intent that can be used to re-launch an application's task
     * in its base state.  This is like {@link #makeMainActivity(ComponentName)},
     * but also sets the flags {@link #FLAG_ACTIVITY_NEW_TASK} and
     * {@link #FLAG_ACTIVITY_CLEAR_TASK}.
     *
     * @param mainActivity The activity component that is the root of the
     * task; this is the activity that has been published in the application's
     * manifest as the main launcher icon.
     *
     * @return Returns a newly created Intent that can be used to relaunch the
     * activity's task in its root state.
     */
    public static Intent makeRestartActivityTask(ComponentName mainActivity) {
        Intent intent = makeMainActivity(mainActivity);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        return intent;
    }

    /**
     * Call {@link #parseUri} with 0 flags.
     * @deprecated Use {@link #parseUri} instead.
     */
    @Deprecated
    public static Intent getIntent(String uri) throws URISyntaxException {
        return parseUri(uri, 0);
    }

    /**
     * Create an intent from a URI.  This URI may encode the action,
     * category, and other intent fields, if it was returned by
     * {@link #toUri}.  If the Intent was not generate by toUri(), its data
     * will be the entire URI and its action will be ACTION_VIEW.
     *
     * <p>The URI given here must not be relative -- that is, it must include
     * the scheme and full path.
     *
     * @param uri The URI to turn into an Intent.
     * @param flags Additional processing flags.
     *
     * @return Intent The newly created Intent object.
     *
     * @throws URISyntaxException Throws URISyntaxError if the basic URI syntax
     * it bad (as parsed by the Uri class) or the Intent data within the
     * URI is invalid.
     *
     * @see #toUri
     */
    public static Intent parseUri(String uri, @UriFlags int flags) throws URISyntaxException {
        Intent intent = parseUriInternal(uri, flags);
        intent.mLocalFlags |= LOCAL_FLAG_FROM_URI;
        return intent;
    }

    /**
     * @see #parseUri(String, int)
     */
    private static Intent parseUriInternal(String uri, @UriFlags int flags)
            throws URISyntaxException {
        int i = 0;
        try {
            final boolean androidApp = uri.startsWith("android-app:");

            // Validate intent scheme if requested.
            if ((flags&(URI_INTENT_SCHEME|URI_ANDROID_APP_SCHEME)) != 0) {
                if (!uri.startsWith("intent:") && !androidApp) {
                    Intent intent = new Intent(ACTION_VIEW);
                    try {
                        intent.setData(Uri.parse(uri));
                    } catch (IllegalArgumentException e) {
                        throw new URISyntaxException(uri, e.getMessage());
                    }
                    return intent;
                }
            }

            i = uri.lastIndexOf("#");
            // simple case
            if (i == -1) {
                if (!androidApp) {
                    return new Intent(ACTION_VIEW, Uri.parse(uri));
                }

            // old format Intent URI
            } else if (!uri.startsWith("#Intent;", i)) {
                if (!androidApp) {
                    return getIntentOld(uri, flags);
                } else {
                    i = -1;
                }
            }

            // new format
            Intent intent = new Intent(ACTION_VIEW);
            Intent baseIntent = intent;
            boolean explicitAction = false;
            boolean inSelector = false;

            // fetch data part, if present
            String scheme = null;
            String data;
            if (i >= 0) {
                data = uri.substring(0, i);
                i += 8; // length of "#Intent;"
            } else {
                data = uri;
            }

            // loop over contents of Intent, all name=value;
            while (i >= 0 && !uri.startsWith("end", i)) {
                int eq = uri.indexOf('=', i);
                if (eq < 0) eq = i-1;
                int semi = uri.indexOf(';', i);
                String value = eq < semi ? Uri.decode(uri.substring(eq + 1, semi)) : "";

                // action
                if (uri.startsWith("action=", i)) {
                    intent.setAction(value);
                    if (!inSelector) {
                        explicitAction = true;
                    }
                }

                // categories
                else if (uri.startsWith("category=", i)) {
                    intent.addCategory(value);
                }

                // type
                else if (uri.startsWith("type=", i)) {
                    intent.mType = value;
                }

                // identifier
                else if (uri.startsWith("identifier=", i)) {
                    intent.mIdentifier = value;
                }

                // launch flags
                else if (uri.startsWith("launchFlags=", i)) {
                    intent.mFlags = Integer.decode(value).intValue();
                    if ((flags& URI_ALLOW_UNSAFE) == 0) {
                        intent.mFlags &= ~IMMUTABLE_FLAGS;
                    }
                }

                // package
                else if (uri.startsWith("package=", i)) {
                    intent.mPackage = value;
                }

                // component
                else if (uri.startsWith("component=", i)) {
                    intent.mComponent = ComponentName.unflattenFromString(value);
                }

                // scheme
                else if (uri.startsWith("scheme=", i)) {
                    if (inSelector) {
                        intent.mData = Uri.parse(value + ":");
                    } else {
                        scheme = value;
                    }
                }

                // source bounds
                else if (uri.startsWith("sourceBounds=", i)) {
                    intent.mSourceBounds = Rect.unflattenFromString(value);
                }

                // selector
                else if (semi == (i+3) && uri.startsWith("SEL", i)) {
                    intent = new Intent();
                    inSelector = true;
                }

                // extra
                else {
                    String key = Uri.decode(uri.substring(i + 2, eq));
                    // create Bundle if it doesn't already exist
                    if (intent.mExtras == null) intent.mExtras = new Bundle();
                    Bundle b = intent.mExtras;
                    // add EXTRA
                    if      (uri.startsWith("S.", i)) b.putString(key, value);
                    else if (uri.startsWith("B.", i)) b.putBoolean(key, Boolean.parseBoolean(value));
                    else if (uri.startsWith("b.", i)) b.putByte(key, Byte.parseByte(value));
                    else if (uri.startsWith("c.", i)) b.putChar(key, value.charAt(0));
                    else if (uri.startsWith("d.", i)) b.putDouble(key, Double.parseDouble(value));
                    else if (uri.startsWith("f.", i)) b.putFloat(key, Float.parseFloat(value));
                    else if (uri.startsWith("i.", i)) b.putInt(key, Integer.parseInt(value));
                    else if (uri.startsWith("l.", i)) b.putLong(key, Long.parseLong(value));
                    else if (uri.startsWith("s.", i)) b.putShort(key, Short.parseShort(value));
                    else throw new URISyntaxException(uri, "unknown EXTRA type", i);
                }

                // move to the next item
                i = semi + 1;
            }

            if (inSelector) {
                // The Intent had a selector; fix it up.
                if (baseIntent.mPackage == null) {
                    baseIntent.setSelector(intent);
                }
                intent = baseIntent;
            }

            if (data != null) {
                if (data.startsWith("intent:")) {
                    data = data.substring(7);
                    if (scheme != null) {
                        data = scheme + ':' + data;
                    }
                } else if (data.startsWith("android-app:")) {
                    if (data.charAt(12) == '/' && data.charAt(13) == '/') {
                        // Correctly formed android-app, first part is package name.
                        int end = data.indexOf('/', 14);
                        if (end < 0) {
                            // All we have is a package name.
                            intent.mPackage = data.substring(14);
                            if (!explicitAction) {
                                intent.setAction(ACTION_MAIN);
                            }
                            data = "";
                        } else {
                            // Target the Intent at the given package name always.
                            String authority = null;
                            intent.mPackage = data.substring(14, end);
                            int newEnd;
                            if ((end+1) < data.length()) {
                                if ((newEnd=data.indexOf('/', end+1)) >= 0) {
                                    // Found a scheme, remember it.
                                    scheme = data.substring(end+1, newEnd);
                                    end = newEnd;
                                    if (end < data.length() && (newEnd=data.indexOf('/', end+1)) >= 0) {
                                        // Found a authority, remember it.
                                        authority = data.substring(end+1, newEnd);
                                        end = newEnd;
                                    }
                                } else {
                                    // All we have is a scheme.
                                    scheme = data.substring(end+1);
                                }
                            }
                            if (scheme == null) {
                                // If there was no scheme, then this just targets the package.
                                if (!explicitAction) {
                                    intent.setAction(ACTION_MAIN);
                                }
                                data = "";
                            } else if (authority == null) {
                                data = scheme + ":";
                            } else {
                                data = scheme + "://" + authority + data.substring(end);
                            }
                        }
                    } else {
                        data = "";
                    }
                }

                if (data.length() > 0) {
                    try {
                        intent.mData = Uri.parse(data);
                    } catch (IllegalArgumentException e) {
                        throw new URISyntaxException(uri, e.getMessage());
                    }
                }
            }

            return intent;

        } catch (IndexOutOfBoundsException e) {
            throw new URISyntaxException(uri, "illegal Intent URI format", i);
        }
    }

    public static Intent getIntentOld(String uri) throws URISyntaxException {
        Intent intent = getIntentOld(uri, 0);
        intent.mLocalFlags |= LOCAL_FLAG_FROM_URI;
        return intent;
    }

    private static Intent getIntentOld(String uri, int flags) throws URISyntaxException {
        Intent intent;

        int i = uri.lastIndexOf('#');
        if (i >= 0) {
            String action = null;
            final int intentFragmentStart = i;
            boolean isIntentFragment = false;

            i++;

            if (uri.regionMatches(i, "action(", 0, 7)) {
                isIntentFragment = true;
                i += 7;
                int j = uri.indexOf(')', i);
                action = uri.substring(i, j);
                i = j + 1;
            }

            intent = new Intent(action);

            if (uri.regionMatches(i, "categories(", 0, 11)) {
                isIntentFragment = true;
                i += 11;
                int j = uri.indexOf(')', i);
                while (i < j) {
                    int sep = uri.indexOf('!', i);
                    if (sep < 0 || sep > j) sep = j;
                    if (i < sep) {
                        intent.addCategory(uri.substring(i, sep));
                    }
                    i = sep + 1;
                }
                i = j + 1;
            }

            if (uri.regionMatches(i, "type(", 0, 5)) {
                isIntentFragment = true;
                i += 5;
                int j = uri.indexOf(')', i);
                intent.mType = uri.substring(i, j);
                i = j + 1;
            }

            if (uri.regionMatches(i, "launchFlags(", 0, 12)) {
                isIntentFragment = true;
                i += 12;
                int j = uri.indexOf(')', i);
                intent.mFlags = Integer.decode(uri.substring(i, j)).intValue();
                if ((flags& URI_ALLOW_UNSAFE) == 0) {
                    intent.mFlags &= ~IMMUTABLE_FLAGS;
                }
                i = j + 1;
            }

            if (uri.regionMatches(i, "component(", 0, 10)) {
                isIntentFragment = true;
                i += 10;
                int j = uri.indexOf(')', i);
                int sep = uri.indexOf('!', i);
                if (sep >= 0 && sep < j) {
                    String pkg = uri.substring(i, sep);
                    String cls = uri.substring(sep + 1, j);
                    intent.mComponent = new ComponentName(pkg, cls);
                }
                i = j + 1;
            }

            if (uri.regionMatches(i, "extras(", 0, 7)) {
                isIntentFragment = true;
                i += 7;

                final int closeParen = uri.indexOf(')', i);
                if (closeParen == -1) throw new URISyntaxException(uri,
                        "EXTRA missing trailing ')'", i);

                while (i < closeParen) {
                    // fetch the key value
                    int j = uri.indexOf('=', i);
                    if (j <= i + 1 || i >= closeParen) {
                        throw new URISyntaxException(uri, "EXTRA missing '='", i);
                    }
                    char type = uri.charAt(i);
                    i++;
                    String key = uri.substring(i, j);
                    i = j + 1;

                    // get type-value
                    j = uri.indexOf('!', i);
                    if (j == -1 || j >= closeParen) j = closeParen;
                    if (i >= j) throw new URISyntaxException(uri, "EXTRA missing '!'", i);
                    String value = uri.substring(i, j);
                    i = j;

                    // create Bundle if it doesn't already exist
                    if (intent.mExtras == null) intent.mExtras = new Bundle();

                    // add item to bundle
                    try {
                        switch (type) {
                            case 'S':
                                intent.mExtras.putString(key, Uri.decode(value));
                                break;
                            case 'B':
                                intent.mExtras.putBoolean(key, Boolean.parseBoolean(value));
                                break;
                            case 'b':
                                intent.mExtras.putByte(key, Byte.parseByte(value));
                                break;
                            case 'c':
                                intent.mExtras.putChar(key, Uri.decode(value).charAt(0));
                                break;
                            case 'd':
                                intent.mExtras.putDouble(key, Double.parseDouble(value));
                                break;
                            case 'f':
                                intent.mExtras.putFloat(key, Float.parseFloat(value));
                                break;
                            case 'i':
                                intent.mExtras.putInt(key, Integer.parseInt(value));
                                break;
                            case 'l':
                                intent.mExtras.putLong(key, Long.parseLong(value));
                                break;
                            case 's':
                                intent.mExtras.putShort(key, Short.parseShort(value));
                                break;
                            default:
                                throw new URISyntaxException(uri, "EXTRA has unknown type", i);
                        }
                    } catch (NumberFormatException e) {
                        throw new URISyntaxException(uri, "EXTRA value can't be parsed", i);
                    }

                    char ch = uri.charAt(i);
                    if (ch == ')') break;
                    if (ch != '!') throw new URISyntaxException(uri, "EXTRA missing '!'", i);
                    i++;
                }
            }

            if (isIntentFragment) {
                intent.mData = Uri.parse(uri.substring(0, intentFragmentStart));
            } else {
                intent.mData = Uri.parse(uri);
            }

            if (intent.mAction == null) {
                // By default, if no action is specified, then use VIEW.
                intent.mAction = ACTION_VIEW;
            }

        } else {
            intent = new Intent(ACTION_VIEW, Uri.parse(uri));
        }

        return intent;
    }

    /** @hide */
    public interface CommandOptionHandler {
        boolean handleOption(String opt, ShellCommand cmd);
    }

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @SuppressWarnings("AndroidFrameworkEfficientCollections")
    public static Intent parseCommandArgs(ShellCommand cmd, CommandOptionHandler optionHandler)
            throws URISyntaxException {
        Intent intent = new Intent();
        Intent baseIntent = intent;
        boolean hasIntentInfo = false;

        Uri data = null;
        String type = null;

        String opt;
        while ((opt=cmd.getNextOption()) != null) {
            switch (opt) {
                case "-a":
                    intent.setAction(cmd.getNextArgRequired());
                    if (intent == baseIntent) {
                        hasIntentInfo = true;
                    }
                    break;
                case "-d":
                    data = Uri.parse(cmd.getNextArgRequired());
                    if (intent == baseIntent) {
                        hasIntentInfo = true;
                    }
                    break;
                case "-t":
                    type = cmd.getNextArgRequired();
                    if (intent == baseIntent) {
                        hasIntentInfo = true;
                    }
                    break;
                case "-i":
                    intent.setIdentifier(cmd.getNextArgRequired());
                    if (intent == baseIntent) {
                        hasIntentInfo = true;
                    }
                    break;
                case "-c":
                    intent.addCategory(cmd.getNextArgRequired());
                    if (intent == baseIntent) {
                        hasIntentInfo = true;
                    }
                    break;
                case "-e":
                case "--es": {
                    String key = cmd.getNextArgRequired();
                    String value = cmd.getNextArgRequired();
                    intent.putExtra(key, value);
                }
                break;
                case "--esn": {
                    String key = cmd.getNextArgRequired();
                    intent.putExtra(key, (String) null);
                }
                break;
                case "--ei": {
                    String key = cmd.getNextArgRequired();
                    String value = cmd.getNextArgRequired();
                    intent.putExtra(key, Integer.decode(value));
                }
                break;
                case "--eu": {
                    String key = cmd.getNextArgRequired();
                    String value = cmd.getNextArgRequired();
                    intent.putExtra(key, Uri.parse(value));
                }
                break;
                case "--ecn": {
                    String key = cmd.getNextArgRequired();
                    String value = cmd.getNextArgRequired();
                    ComponentName cn = ComponentName.unflattenFromString(value);
                    if (cn == null)
                        throw new IllegalArgumentException("Bad component name: " + value);
                    intent.putExtra(key, cn);
                }
                break;
                case "--eia": {
                    String key = cmd.getNextArgRequired();
                    String value = cmd.getNextArgRequired();
                    String[] strings = value.split(",");
                    int[] list = new int[strings.length];
                    for (int i = 0; i < strings.length; i++) {
                        list[i] = Integer.decode(strings[i]);
                    }
                    intent.putExtra(key, list);
                }
                break;
                case "--eial": {
                    String key = cmd.getNextArgRequired();
                    String value = cmd.getNextArgRequired();
                    String[] strings = value.split(",");
                    ArrayList<Integer> list = new ArrayList<>(strings.length);
                    for (int i = 0; i < strings.length; i++) {
                        list.add(Integer.decode(strings[i]));
                    }
                    intent.putExtra(key, list);
                }
                break;
                case "--el": {
                    String key = cmd.getNextArgRequired();
                    String value = cmd.getNextArgRequired();
                    intent.putExtra(key, Long.valueOf(value));
                }
                break;
                case "--ela": {
                    String key = cmd.getNextArgRequired();
                    String value = cmd.getNextArgRequired();
                    String[] strings = value.split(",");
                    long[] list = new long[strings.length];
                    for (int i = 0; i < strings.length; i++) {
                        list[i] = Long.valueOf(strings[i]);
                    }
                    intent.putExtra(key, list);
                    hasIntentInfo = true;
                }
                break;
                case "--elal": {
                    String key = cmd.getNextArgRequired();
                    String value = cmd.getNextArgRequired();
                    String[] strings = value.split(",");
                    ArrayList<Long> list = new ArrayList<>(strings.length);
                    for (int i = 0; i < strings.length; i++) {
                        list.add(Long.valueOf(strings[i]));
                    }
                    intent.putExtra(key, list);
                    hasIntentInfo = true;
                }
                break;
                case "--ef": {
                    String key = cmd.getNextArgRequired();
                    String value = cmd.getNextArgRequired();
                    intent.putExtra(key, Float.valueOf(value));
                    hasIntentInfo = true;
                }
                break;
                case "--efa": {
                    String key = cmd.getNextArgRequired();
                    String value = cmd.getNextArgRequired();
                    String[] strings = value.split(",");
                    float[] list = new float[strings.length];
                    for (int i = 0; i < strings.length; i++) {
                        list[i] = Float.valueOf(strings[i]);
                    }
                    intent.putExtra(key, list);
                    hasIntentInfo = true;
                }
                break;
                case "--efal": {
                    String key = cmd.getNextArgRequired();
                    String value = cmd.getNextArgRequired();
                    String[] strings = value.split(",");
                    ArrayList<Float> list = new ArrayList<>(strings.length);
                    for (int i = 0; i < strings.length; i++) {
                        list.add(Float.valueOf(strings[i]));
                    }
                    intent.putExtra(key, list);
                    hasIntentInfo = true;
                }
                break;
                case "--ed": {
                    String key = cmd.getNextArgRequired();
                    String value = cmd.getNextArgRequired();
                    intent.putExtra(key, Double.valueOf(value));
                    hasIntentInfo = true;
                }
                break;
                case "--eda": {
                    String key = cmd.getNextArgRequired();
                    String value = cmd.getNextArgRequired();
                    String[] strings = value.split(",");
                    double[] list = new double[strings.length];
                    for (int i = 0; i < strings.length; i++) {
                        list[i] = Double.valueOf(strings[i]);
                    }
                    intent.putExtra(key, list);
                    hasIntentInfo = true;
                }
                break;
                case "--edal": {
                    String key = cmd.getNextArgRequired();
                    String value = cmd.getNextArgRequired();
                    String[] strings = value.split(",");
                    ArrayList<Double> list = new ArrayList<>(strings.length);
                    for (int i = 0; i < strings.length; i++) {
                        list.add(Double.valueOf(strings[i]));
                    }
                    intent.putExtra(key, list);
                    hasIntentInfo = true;
                }
                break;
                case "--esa": {
                    String key = cmd.getNextArgRequired();
                    String value = cmd.getNextArgRequired();
                    // Split on commas unless they are preceeded by an escape.
                    // The escape character must be escaped for the string and
                    // again for the regex, thus four escape characters become one.
                    String[] strings = value.split("(?<!\\\\),");
                    intent.putExtra(key, strings);
                    hasIntentInfo = true;
                }
                break;
                case "--esal": {
                    String key = cmd.getNextArgRequired();
                    String value = cmd.getNextArgRequired();
                    // Split on commas unless they are preceeded by an escape.
                    // The escape character must be escaped for the string and
                    // again for the regex, thus four escape characters become one.
                    String[] strings = value.split("(?<!\\\\),");
                    ArrayList<String> list = new ArrayList<>(strings.length);
                    for (int i = 0; i < strings.length; i++) {
                        list.add(strings[i]);
                    }
                    intent.putExtra(key, list);
                    hasIntentInfo = true;
                }
                break;
                case "--ez": {
                    String key = cmd.getNextArgRequired();
                    String value = cmd.getNextArgRequired().toLowerCase();
                    // Boolean.valueOf() results in false for anything that is not "true", which is
                    // error-prone in shell commands
                    boolean arg;
                    if ("true".equals(value) || "t".equals(value)) {
                        arg = true;
                    } else if ("false".equals(value) || "f".equals(value)) {
                        arg = false;
                    } else {
                        try {
                            arg = Integer.decode(value) != 0;
                        } catch (NumberFormatException ex) {
                            throw new IllegalArgumentException("Invalid boolean value: " + value);
                        }
                    }

                    intent.putExtra(key, arg);
                }
                break;
                case "-n": {
                    String str = cmd.getNextArgRequired();
                    ComponentName cn = ComponentName.unflattenFromString(str);
                    if (cn == null)
                        throw new IllegalArgumentException("Bad component name: " + str);
                    intent.setComponent(cn);
                    if (intent == baseIntent) {
                        hasIntentInfo = true;
                    }
                }
                break;
                case "-p": {
                    String str = cmd.getNextArgRequired();
                    intent.setPackage(str);
                    if (intent == baseIntent) {
                        hasIntentInfo = true;
                    }
                }
                break;
                case "-f":
                    String str = cmd.getNextArgRequired();
                    intent.setFlags(Integer.decode(str).intValue());
                    break;
                case "--grant-read-uri-permission":
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    break;
                case "--grant-write-uri-permission":
                    intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    break;
                case "--grant-persistable-uri-permission":
                    intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    break;
                case "--grant-prefix-uri-permission":
                    intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                    break;
                case "--exclude-stopped-packages":
                    intent.addFlags(Intent.FLAG_EXCLUDE_STOPPED_PACKAGES);
                    break;
                case "--include-stopped-packages":
                    intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                    break;
                case "--debug-log-resolution":
                    intent.addFlags(Intent.FLAG_DEBUG_LOG_RESOLUTION);
                    break;
                case "--activity-brought-to-front":
                    intent.addFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT);
                    break;
                case "--activity-clear-top":
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    break;
                case "--activity-clear-when-task-reset":
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
                    break;
                case "--activity-exclude-from-recents":
                    intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                    break;
                case "--activity-launched-from-history":
                    intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY);
                    break;
                case "--activity-multiple-task":
                    intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                    break;
                case "--activity-no-animation":
                    intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                    break;
                case "--activity-no-history":
                    intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
                    break;
                case "--activity-no-user-action":
                    intent.addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION);
                    break;
                case "--activity-previous-is-top":
                    intent.addFlags(Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP);
                    break;
                case "--activity-reorder-to-front":
                    intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    break;
                case "--activity-reset-task-if-needed":
                    intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    break;
                case "--activity-single-top":
                    intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    break;
                case "--activity-clear-task":
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    break;
                case "--activity-task-on-home":
                    intent.addFlags(Intent.FLAG_ACTIVITY_TASK_ON_HOME);
                    break;
                case "--activity-match-external":
                    intent.addFlags(Intent.FLAG_ACTIVITY_MATCH_EXTERNAL);
                    break;
                case "--receiver-registered-only":
                    intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                    break;
                case "--receiver-replace-pending":
                    intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
                    break;
                case "--receiver-foreground":
                    intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                    break;
                case "--receiver-no-abort":
                    intent.addFlags(Intent.FLAG_RECEIVER_NO_ABORT);
                    break;
                case "--receiver-include-background":
                    intent.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
                    break;
                case "--selector":
                    intent.setDataAndType(data, type);
                    intent = new Intent();
                    break;
                default:
                    if (optionHandler != null && optionHandler.handleOption(opt, cmd)) {
                        // Okay, caller handled this option.
                    } else {
                        throw new IllegalArgumentException("Unknown option: " + opt);
                    }
                    break;
            }
        }
        intent.setDataAndType(data, type);

        final boolean hasSelector = intent != baseIntent;
        if (hasSelector) {
            // A selector was specified; fix up.
            baseIntent.setSelector(intent);
            intent = baseIntent;
        }

        String arg = cmd.getNextArg();
        baseIntent = null;
        if (arg == null) {
            if (hasSelector) {
                // If a selector has been specified, and no arguments
                // have been supplied for the main Intent, then we can
                // assume it is ACTION_MAIN CATEGORY_LAUNCHER; we don't
                // need to have a component name specified yet, the
                // selector will take care of that.
                baseIntent = new Intent(Intent.ACTION_MAIN);
                baseIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            }
        } else if (arg.indexOf(':') >= 0) {
            // The argument is a URI.  Fully parse it, and use that result
            // to fill in any data not specified so far.
            baseIntent = Intent.parseUri(arg, Intent.URI_INTENT_SCHEME
                    | Intent.URI_ANDROID_APP_SCHEME | Intent.URI_ALLOW_UNSAFE);
        } else if (arg.indexOf('/') >= 0) {
            // The argument is a component name.  Build an Intent to launch
            // it.
            baseIntent = new Intent(Intent.ACTION_MAIN);
            baseIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            baseIntent.setComponent(ComponentName.unflattenFromString(arg));
        } else {
            // Assume the argument is a package name.
            baseIntent = new Intent(Intent.ACTION_MAIN);
            baseIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            baseIntent.setPackage(arg);
        }
        if (baseIntent != null) {
            Bundle extras = intent.getExtras();
            intent.replaceExtras((Bundle)null);
            Bundle uriExtras = baseIntent.getExtras();
            baseIntent.replaceExtras((Bundle)null);
            if (intent.getAction() != null && baseIntent.getCategories() != null) {
                HashSet<String> cats = new HashSet<String>(baseIntent.getCategories());
                for (String c : cats) {
                    baseIntent.removeCategory(c);
                }
            }
            intent.fillIn(baseIntent, Intent.FILL_IN_COMPONENT | Intent.FILL_IN_SELECTOR);
            if (extras == null) {
                extras = uriExtras;
            } else if (uriExtras != null) {
                uriExtras.putAll(extras);
                extras = uriExtras;
            }
            intent.replaceExtras(extras);
            hasIntentInfo = true;
        }

        if (!hasIntentInfo) throw new IllegalArgumentException("No intent supplied");
        return intent;
    }

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static void printIntentArgsHelp(PrintWriter pw, String prefix) {
        final String[] lines = new String[] {
                "<INTENT> specifications include these flags and arguments:",
                "    [-a <ACTION>] [-d <DATA_URI>] [-t <MIME_TYPE>] [-i <IDENTIFIER>]",
                "    [-c <CATEGORY> [-c <CATEGORY>] ...]",
                "    [-n <COMPONENT_NAME>]",
                "    [-e|--es <EXTRA_KEY> <EXTRA_STRING_VALUE> ...]",
                "    [--esn <EXTRA_KEY> ...]",
                "    [--ez <EXTRA_KEY> <EXTRA_BOOLEAN_VALUE> ...]",
                "    [--ei <EXTRA_KEY> <EXTRA_INT_VALUE> ...]",
                "    [--el <EXTRA_KEY> <EXTRA_LONG_VALUE> ...]",
                "    [--ef <EXTRA_KEY> <EXTRA_FLOAT_VALUE> ...]",
                "    [--ed <EXTRA_KEY> <EXTRA_DOUBLE_VALUE> ...]",
                "    [--eu <EXTRA_KEY> <EXTRA_URI_VALUE> ...]",
                "    [--ecn <EXTRA_KEY> <EXTRA_COMPONENT_NAME_VALUE>]",
                "    [--eia <EXTRA_KEY> <EXTRA_INT_VALUE>[,<EXTRA_INT_VALUE...]]",
                "        (multiple extras passed as Integer[])",
                "    [--eial <EXTRA_KEY> <EXTRA_INT_VALUE>[,<EXTRA_INT_VALUE...]]",
                "        (multiple extras passed as List<Integer>)",
                "    [--ela <EXTRA_KEY> <EXTRA_LONG_VALUE>[,<EXTRA_LONG_VALUE...]]",
                "        (multiple extras passed as Long[])",
                "    [--elal <EXTRA_KEY> <EXTRA_LONG_VALUE>[,<EXTRA_LONG_VALUE...]]",
                "        (multiple extras passed as List<Long>)",
                "    [--efa <EXTRA_KEY> <EXTRA_FLOAT_VALUE>[,<EXTRA_FLOAT_VALUE...]]",
                "        (multiple extras passed as Float[])",
                "    [--efal <EXTRA_KEY> <EXTRA_FLOAT_VALUE>[,<EXTRA_FLOAT_VALUE...]]",
                "        (multiple extras passed as List<Float>)",
                "    [--eda <EXTRA_KEY> <EXTRA_DOUBLE_VALUE>[,<EXTRA_DOUBLE_VALUE...]]",
                "        (multiple extras passed as Double[])",
                "    [--edal <EXTRA_KEY> <EXTRA_DOUBLE_VALUE>[,<EXTRA_DOUBLE_VALUE...]]",
                "        (multiple extras passed as List<Double>)",
                "    [--esa <EXTRA_KEY> <EXTRA_STRING_VALUE>[,<EXTRA_STRING_VALUE...]]",
                "        (multiple extras passed as String[]; to embed a comma into a string,",
                "         escape it using \"\\,\")",
                "    [--esal <EXTRA_KEY> <EXTRA_STRING_VALUE>[,<EXTRA_STRING_VALUE...]]",
                "        (multiple extras passed as List<String>; to embed a comma into a string,",
                "         escape it using \"\\,\")",
                "    [-f <FLAG>]",
                "    [--grant-read-uri-permission] [--grant-write-uri-permission]",
                "    [--grant-persistable-uri-permission] [--grant-prefix-uri-permission]",
                "    [--debug-log-resolution] [--exclude-stopped-packages]",
                "    [--include-stopped-packages]",
                "    [--activity-brought-to-front] [--activity-clear-top]",
                "    [--activity-clear-when-task-reset] [--activity-exclude-from-recents]",
                "    [--activity-launched-from-history] [--activity-multiple-task]",
                "    [--activity-no-animation] [--activity-no-history]",
                "    [--activity-no-user-action] [--activity-previous-is-top]",
                "    [--activity-reorder-to-front] [--activity-reset-task-if-needed]",
                "    [--activity-single-top] [--activity-clear-task]",
                "    [--activity-task-on-home] [--activity-match-external]",
                "    [--receiver-registered-only] [--receiver-replace-pending]",
                "    [--receiver-foreground] [--receiver-no-abort]",
                "    [--receiver-include-background]",
                "    [--selector]",
                "    [<URI> | <PACKAGE> | <COMPONENT>]"
        };
        for (String line : lines) {
            pw.print(prefix);
            pw.println(line);
        }
    }

    /**
     * Retrieve the general action to be performed, such as
     * {@link #ACTION_VIEW}.  The action describes the general way the rest of
     * the information in the intent should be interpreted -- most importantly,
     * what to do with the data returned by {@link #getData}.
     *
     * @return The action of this intent or null if none is specified.
     *
     * @see #setAction
     */
    public @Nullable String getAction() {
        return mAction;
    }

    /**
     * Retrieve data this intent is operating on.  This URI specifies the name
     * of the data; often it uses the content: scheme, specifying data in a
     * content provider.  Other schemes may be handled by specific activities,
     * such as http: by the web browser.
     *
     * @return The URI of the data this intent is targeting or null.
     *
     * @see #getScheme
     * @see #setData
     */
    public @Nullable Uri getData() {
        return mData;
    }

    /**
     * The same as {@link #getData()}, but returns the URI as an encoded
     * String.
     */
    public @Nullable String getDataString() {
        return mData != null ? mData.toString() : null;
    }

    /**
     * Return the scheme portion of the intent's data.  If the data is null or
     * does not include a scheme, null is returned.  Otherwise, the scheme
     * prefix without the final ':' is returned, i.e. "http".
     *
     * <p>This is the same as calling getData().getScheme() (and checking for
     * null data).
     *
     * @return The scheme of this intent.
     *
     * @see #getData
     */
    public @Nullable String getScheme() {
        return mData != null ? mData.getScheme() : null;
    }

    /**
     * Retrieve any explicit MIME type included in the intent.  This is usually
     * null, as the type is determined by the intent data.
     *
     * @return If a type was manually set, it is returned; else null is
     *         returned.
     *
     * @see #resolveType(ContentResolver)
     * @see #setType
     */
    public @Nullable String getType() {
        return mType;
    }


    /**
     * @hide For the experimental component alias feature. Do not use, unless you know what it is.
     */
    @Nullable
    public Intent getOriginalIntent() {
        return mOriginalIntent;
    }

    /**
     * @hide For the experimental component alias feature. Do not use, unless you know what it is.
     */
    public void setOriginalIntent(@Nullable Intent originalIntent) {
        mOriginalIntent = originalIntent;
    }

    /**
     * Return the MIME data type of this intent.  If the type field is
     * explicitly set, that is simply returned.  Otherwise, if the data is set,
     * the type of that data is returned.  If neither fields are set, a null is
     * returned.
     *
     * @return The MIME type of this intent.
     *
     * @see #getType
     * @see #resolveType(ContentResolver)
     */
    public @Nullable String resolveType(@NonNull Context context) {
        return resolveType(context.getContentResolver());
    }

    /**
     * Return the MIME data type of this intent.  If the type field is
     * explicitly set, that is simply returned.  Otherwise, if the data is set,
     * the type of that data is returned.  If neither fields are set, a null is
     * returned.
     *
     * @param resolver A ContentResolver that can be used to determine the MIME
     *                 type of the intent's data.
     *
     * @return The MIME type of this intent.
     *
     * @see #getType
     * @see #resolveType(Context)
     */
    public @Nullable String resolveType(@NonNull ContentResolver resolver) {
        if (mType != null) {
            return mType;
        }
        if (mData != null) {
            if ("content".equals(mData.getScheme())) {
                return resolver.getType(mData);
            }
        }
        return null;
    }

    /**
     * Return the MIME data type of this intent, only if it will be needed for
     * intent resolution.  This is not generally useful for application code;
     * it is used by the frameworks for communicating with back-end system
     * services.
     *
     * @param resolver A ContentResolver that can be used to determine the MIME
     *                 type of the intent's data.
     *
     * @return The MIME type of this intent, or null if it is unknown or not
     *         needed.
     */
    public @Nullable String resolveTypeIfNeeded(@NonNull ContentResolver resolver) {
        // Match logic in PackageManagerService#applyEnforceIntentFilterMatching(...)
        if (mComponent != null && (Process.myUid() == Process.ROOT_UID
                || Process.myUid() == Process.SYSTEM_UID
                || mComponent.getPackageName().equals(ActivityThread.currentPackageName()))) {
            return mType;
        }
        return resolveType(resolver);
    }

    /**
     * Retrieve the identifier for this Intent.  If non-null, this is an arbitrary identity
     * of the Intent to distinguish it from other Intents.
     *
     * @return The identifier of this intent or null if none is specified.
     *
     * @see #setIdentifier
     */
    public @Nullable String getIdentifier() {
        return mIdentifier;
    }

    /**
     * Check if a category exists in the intent.
     *
     * @param category The category to check.
     *
     * @return boolean True if the intent contains the category, else false.
     *
     * @see #getCategories
     * @see #addCategory
     */
    public boolean hasCategory(String category) {
        return mCategories != null && mCategories.contains(category);
    }

    /**
     * Return the set of all categories in the intent.  If there are no categories,
     * returns NULL.
     *
     * @return The set of categories you can examine.  Do not modify!
     *
     * @see #hasCategory
     * @see #addCategory
     */
    public Set<String> getCategories() {
        return mCategories;
    }

    /**
     * Return the specific selector associated with this Intent.  If there is
     * none, returns null.  See {@link #setSelector} for more information.
     *
     * @see #setSelector
     */
    public @Nullable Intent getSelector() {
        return mSelector;
    }

    /**
     * Return the {@link ClipData} associated with this Intent.  If there is
     * none, returns null.  See {@link #setClipData} for more information.
     *
     * @see #setClipData
     */
    public @Nullable ClipData getClipData() {
        return mClipData;
    }

    /** @hide */
    public int getContentUserHint() {
        return mContentUserHint;
    }

    /** @hide */
    public String getLaunchToken() {
        return mLaunchToken;
    }

    /** @hide */
    public void setLaunchToken(String launchToken) {
        mLaunchToken = launchToken;
    }

    /**
     * Sets the ClassLoader that will be used when unmarshalling
     * any Parcelable values from the extras of this Intent.
     *
     * @param loader a ClassLoader, or null to use the default loader
     * at the time of unmarshalling.
     */
    public void setExtrasClassLoader(@Nullable ClassLoader loader) {
        if (mExtras != null) {
            mExtras.setClassLoader(loader);
        }
    }

    /**
     * Returns true if an extra value is associated with the given name.
     * @param name the extra's name
     * @return true if the given extra is present.
     */
    public boolean hasExtra(String name) {
        return mExtras != null && mExtras.containsKey(name);
    }

    /**
     * Returns true if the Intent's extras contain a parcelled file descriptor.
     * @return true if the Intent contains a parcelled file descriptor.
     */
    public boolean hasFileDescriptors() {
        return mExtras != null && mExtras.hasFileDescriptors();
    }

    /** {@hide} */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void setAllowFds(boolean allowFds) {
        if (mExtras != null) {
            mExtras.setAllowFds(allowFds);
        }
    }

    /** {@hide} */
    public void setDefusable(boolean defusable) {
        if (mExtras != null) {
            mExtras.setDefusable(defusable);
        }
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     *
     * @return the value of an item previously added with putExtra(),
     * or null if none was found.
     *
     * @deprecated
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage
    public Object getExtra(String name) {
        return getExtra(name, null);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     * @param defaultValue the value to be returned if no value of the desired
     * type is stored with the given name.
     *
     * @return the value of an item previously added with putExtra(),
     * or the default value if none was found.
     *
     * @see #putExtra(String, boolean)
     */
    public boolean getBooleanExtra(String name, boolean defaultValue) {
        return mExtras == null ? defaultValue :
            mExtras.getBoolean(name, defaultValue);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     * @param defaultValue the value to be returned if no value of the desired
     * type is stored with the given name.
     *
     * @return the value of an item previously added with putExtra(),
     * or the default value if none was found.
     *
     * @see #putExtra(String, byte)
     */
    public byte getByteExtra(String name, byte defaultValue) {
        return mExtras == null ? defaultValue :
            mExtras.getByte(name, defaultValue);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     * @param defaultValue the value to be returned if no value of the desired
     * type is stored with the given name.
     *
     * @return the value of an item previously added with putExtra(),
     * or the default value if none was found.
     *
     * @see #putExtra(String, short)
     */
    public short getShortExtra(String name, short defaultValue) {
        return mExtras == null ? defaultValue :
            mExtras.getShort(name, defaultValue);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     * @param defaultValue the value to be returned if no value of the desired
     * type is stored with the given name.
     *
     * @return the value of an item previously added with putExtra(),
     * or the default value if none was found.
     *
     * @see #putExtra(String, char)
     */
    public char getCharExtra(String name, char defaultValue) {
        return mExtras == null ? defaultValue :
            mExtras.getChar(name, defaultValue);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     * @param defaultValue the value to be returned if no value of the desired
     * type is stored with the given name.
     *
     * @return the value of an item previously added with putExtra(),
     * or the default value if none was found.
     *
     * @see #putExtra(String, int)
     */
    public int getIntExtra(String name, int defaultValue) {
        return mExtras == null ? defaultValue :
            mExtras.getInt(name, defaultValue);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     * @param defaultValue the value to be returned if no value of the desired
     * type is stored with the given name.
     *
     * @return the value of an item previously added with putExtra(),
     * or the default value if none was found.
     *
     * @see #putExtra(String, long)
     */
    public long getLongExtra(String name, long defaultValue) {
        return mExtras == null ? defaultValue :
            mExtras.getLong(name, defaultValue);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     * @param defaultValue the value to be returned if no value of the desired
     * type is stored with the given name.
     *
     * @return the value of an item previously added with putExtra(),
     * or the default value if no such item is present
     *
     * @see #putExtra(String, float)
     */
    public float getFloatExtra(String name, float defaultValue) {
        return mExtras == null ? defaultValue :
            mExtras.getFloat(name, defaultValue);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     * @param defaultValue the value to be returned if no value of the desired
     * type is stored with the given name.
     *
     * @return the value of an item previously added with putExtra(),
     * or the default value if none was found.
     *
     * @see #putExtra(String, double)
     */
    public double getDoubleExtra(String name, double defaultValue) {
        return mExtras == null ? defaultValue :
            mExtras.getDouble(name, defaultValue);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     *
     * @return the value of an item previously added with putExtra(),
     * or null if no String value was found.
     *
     * @see #putExtra(String, String)
     */
    public @Nullable String getStringExtra(String name) {
        return mExtras == null ? null : mExtras.getString(name);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     *
     * @return the value of an item previously added with putExtra(),
     * or null if no CharSequence value was found.
     *
     * @see #putExtra(String, CharSequence)
     */
    public @Nullable CharSequence getCharSequenceExtra(String name) {
        return mExtras == null ? null : mExtras.getCharSequence(name);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     *
     * @return the value of an item previously added with putExtra(),
     * or null if no Parcelable value was found.
     *
     * @deprecated Use the type-safer {@link #getParcelableExtra(String, Class)} starting from
     *      Android {@link Build.VERSION_CODES#TIRAMISU}.
     *
     * @see #putExtra(String, Parcelable)
     */
    @Deprecated
    public @Nullable <T extends Parcelable> T getParcelableExtra(String name) {
        return mExtras == null ? null : mExtras.<T>getParcelable(name);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     * @param clazz The type of the object expected.
     *
     * @return the value of an item previously added with putExtra(),
     * or null if no Parcelable value was found.
     *
     * @see #putExtra(String, Parcelable)
     */
    public @Nullable <T> T getParcelableExtra(@Nullable String name, @NonNull Class<T> clazz) {
        return mExtras == null ? null : mExtras.getParcelable(name, clazz);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     *
     * @return the value of an item previously added with putExtra(),
     * or null if no Parcelable[] value was found.
     *
     * @deprecated Use the type-safer {@link #getParcelableArrayExtra(String, Class)} starting from
     *      Android {@link Build.VERSION_CODES#TIRAMISU}.
     *
     * @see #putExtra(String, Parcelable[])
     */
    @Deprecated
    public @Nullable Parcelable[] getParcelableArrayExtra(String name) {
        return mExtras == null ? null : mExtras.getParcelableArray(name);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     * @param clazz The type of the items inside the array. This is only verified when unparceling.
     *
     * @return the value of an item previously added with putExtra(),
     * or null if no Parcelable[] value was found.
     *
     * @see #putExtra(String, Parcelable[])
     */
    @SuppressLint({"ArrayReturn", "NullableCollection"})
    public @Nullable <T> T[] getParcelableArrayExtra(@Nullable String name,
            @NonNull Class<T> clazz) {
        return mExtras == null ? null : mExtras.getParcelableArray(name, clazz);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     *
     * @return the value of an item previously added with
     * putParcelableArrayListExtra(), or null if no
     * ArrayList<Parcelable> value was found.
     *
     * @deprecated Use the type-safer {@link #getParcelableArrayListExtra(String, Class)} starting
     *      from Android {@link Build.VERSION_CODES#TIRAMISU}.
     *
     * @see #putParcelableArrayListExtra(String, ArrayList)
     */
    @Deprecated
    public @Nullable <T extends Parcelable> ArrayList<T> getParcelableArrayListExtra(String name) {
        return mExtras == null ? null : mExtras.<T>getParcelableArrayList(name);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     * @param clazz The type of the items inside the array list. This is only verified when
     *     unparceling.
     *
     * @return the value of an item previously added with
     * putParcelableArrayListExtra(), or null if no
     * ArrayList<Parcelable> value was found.
     *
     * @see #putParcelableArrayListExtra(String, ArrayList)
     */
    @SuppressLint({"ConcreteCollection", "NullableCollection"})
    public @Nullable <T> ArrayList<T> getParcelableArrayListExtra(@Nullable String name,
            @NonNull Class<? extends T> clazz) {
        return mExtras == null ? null : mExtras.<T>getParcelableArrayList(name, clazz);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     *
     * @return the value of an item previously added with putExtra(),
     * or null if no Serializable value was found.
     *
     * @deprecated Use the type-safer {@link #getSerializableExtra(String, Class)} starting from
     *      Android {@link Build.VERSION_CODES#TIRAMISU}.
     *
     * @see #putExtra(String, Serializable)
     */
    public @Nullable Serializable getSerializableExtra(String name) {
        return mExtras == null ? null : mExtras.getSerializable(name);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     * @param clazz The type of the object expected.
     *
     * @return the value of an item previously added with putExtra(),
     * or null if no Serializable value was found.
     *
     * @see #putExtra(String, Serializable)
     */
    public @Nullable <T extends Serializable> T getSerializableExtra(@Nullable String name,
            @NonNull Class<T> clazz) {
        return mExtras == null ? null : mExtras.getSerializable(name, clazz);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     *
     * @return the value of an item previously added with
     * putIntegerArrayListExtra(), or null if no
     * ArrayList<Integer> value was found.
     *
     * @see #putIntegerArrayListExtra(String, ArrayList)
     */
    public @Nullable ArrayList<Integer> getIntegerArrayListExtra(String name) {
        return mExtras == null ? null : mExtras.getIntegerArrayList(name);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     *
     * @return the value of an item previously added with
     * putStringArrayListExtra(), or null if no
     * ArrayList<String> value was found.
     *
     * @see #putStringArrayListExtra(String, ArrayList)
     */
    public @Nullable ArrayList<String> getStringArrayListExtra(String name) {
        return mExtras == null ? null : mExtras.getStringArrayList(name);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     *
     * @return the value of an item previously added with
     * putCharSequenceArrayListExtra, or null if no
     * ArrayList<CharSequence> value was found.
     *
     * @see #putCharSequenceArrayListExtra(String, ArrayList)
     */
    public @Nullable ArrayList<CharSequence> getCharSequenceArrayListExtra(String name) {
        return mExtras == null ? null : mExtras.getCharSequenceArrayList(name);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     *
     * @return the value of an item previously added with putExtra(),
     * or null if no boolean array value was found.
     *
     * @see #putExtra(String, boolean[])
     */
    public @Nullable boolean[] getBooleanArrayExtra(String name) {
        return mExtras == null ? null : mExtras.getBooleanArray(name);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     *
     * @return the value of an item previously added with putExtra(),
     * or null if no byte array value was found.
     *
     * @see #putExtra(String, byte[])
     */
    public @Nullable byte[] getByteArrayExtra(String name) {
        return mExtras == null ? null : mExtras.getByteArray(name);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     *
     * @return the value of an item previously added with putExtra(),
     * or null if no short array value was found.
     *
     * @see #putExtra(String, short[])
     */
    public @Nullable short[] getShortArrayExtra(String name) {
        return mExtras == null ? null : mExtras.getShortArray(name);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     *
     * @return the value of an item previously added with putExtra(),
     * or null if no char array value was found.
     *
     * @see #putExtra(String, char[])
     */
    public @Nullable char[] getCharArrayExtra(String name) {
        return mExtras == null ? null : mExtras.getCharArray(name);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     *
     * @return the value of an item previously added with putExtra(),
     * or null if no int array value was found.
     *
     * @see #putExtra(String, int[])
     */
    public @Nullable int[] getIntArrayExtra(String name) {
        return mExtras == null ? null : mExtras.getIntArray(name);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     *
     * @return the value of an item previously added with putExtra(),
     * or null if no long array value was found.
     *
     * @see #putExtra(String, long[])
     */
    public @Nullable long[] getLongArrayExtra(String name) {
        return mExtras == null ? null : mExtras.getLongArray(name);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     *
     * @return the value of an item previously added with putExtra(),
     * or null if no float array value was found.
     *
     * @see #putExtra(String, float[])
     */
    public @Nullable float[] getFloatArrayExtra(String name) {
        return mExtras == null ? null : mExtras.getFloatArray(name);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     *
     * @return the value of an item previously added with putExtra(),
     * or null if no double array value was found.
     *
     * @see #putExtra(String, double[])
     */
    public @Nullable double[] getDoubleArrayExtra(String name) {
        return mExtras == null ? null : mExtras.getDoubleArray(name);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     *
     * @return the value of an item previously added with putExtra(),
     * or null if no String array value was found.
     *
     * @see #putExtra(String, String[])
     */
    public @Nullable String[] getStringArrayExtra(String name) {
        return mExtras == null ? null : mExtras.getStringArray(name);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     *
     * @return the value of an item previously added with putExtra(),
     * or null if no CharSequence array value was found.
     *
     * @see #putExtra(String, CharSequence[])
     */
    public @Nullable CharSequence[] getCharSequenceArrayExtra(String name) {
        return mExtras == null ? null : mExtras.getCharSequenceArray(name);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     *
     * @return the value of an item previously added with putExtra(),
     * or null if no Bundle value was found.
     *
     * @see #putExtra(String, Bundle)
     */
    public @Nullable Bundle getBundleExtra(String name) {
        return mExtras == null ? null : mExtras.getBundle(name);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     *
     * @return the value of an item previously added with putExtra(),
     * or null if no IBinder value was found.
     *
     * @see #putExtra(String, IBinder)
     *
     * @deprecated
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage
    public IBinder getIBinderExtra(String name) {
        return mExtras == null ? null : mExtras.getIBinder(name);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     * @param defaultValue The default value to return in case no item is
     * associated with the key 'name'
     *
     * @return the value of an item previously added with putExtra(),
     * or defaultValue if none was found.
     *
     * @see #putExtra
     *
     * @deprecated
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage
    public Object getExtra(String name, Object defaultValue) {
        Object result = defaultValue;
        if (mExtras != null) {
            Object result2 = mExtras.get(name);
            if (result2 != null) {
                result = result2;
            }
        }

        return result;
    }

    /**
     * Retrieves a map of extended data from the intent.
     *
     * @return the map of all extras previously added with putExtra(),
     * or null if none have been added.
     */
    public @Nullable Bundle getExtras() {
        return (mExtras != null)
                ? new Bundle(mExtras)
                : null;
    }

    /**
     * Returns the total size of the extras in bytes, or 0 if no extras are present.
     * @hide
     */
    public int getExtrasTotalSize() {
        return (mExtras != null)
                ? mExtras.getSize()
                : 0;
    }

    /**
     * @return Whether {@link #maybeStripForHistory} will return an lightened intent or
     * return itself as-is.
     * @hide
     */
    public boolean canStripForHistory() {
        return ((mExtras != null) && mExtras.isParcelled()) || (mClipData != null);
    }

    /**
     * Call it when the system needs to keep an intent for logging purposes to remove fields
     * that are not needed for logging.
     * @hide
     */
    public Intent maybeStripForHistory() {
        // TODO Scan and remove possibly heavy instances like Bitmaps from unparcelled extras?

        if (!canStripForHistory()) {
            return this;
        }
        return new Intent(this, COPY_MODE_HISTORY);
    }

    /**
     * Retrieve any special flags associated with this intent.  You will
     * normally just set them with {@link #setFlags} and let the system
     * take the appropriate action with them.
     *
     * @return The currently set flags.
     * @see #setFlags
     * @see #addFlags
     * @see #removeFlags
     */
    public @Flags int getFlags() {
        return mFlags;
    }

    /** @hide */
    @UnsupportedAppUsage
    public boolean isExcludingStopped() {
        return (mFlags&(FLAG_EXCLUDE_STOPPED_PACKAGES|FLAG_INCLUDE_STOPPED_PACKAGES))
                == FLAG_EXCLUDE_STOPPED_PACKAGES;
    }

    /**
     * Retrieve the application package name this Intent is limited to.  When
     * resolving an Intent, if non-null this limits the resolution to only
     * components in the given application package.
     *
     * @return The name of the application package for the Intent.
     *
     * @see #resolveActivity
     * @see #setPackage
     */
    public @Nullable String getPackage() {
        return mPackage;
    }

    /**
     * Retrieve the concrete component associated with the intent.  When receiving
     * an intent, this is the component that was found to best handle it (that is,
     * yourself) and will always be non-null; in all other cases it will be
     * null unless explicitly set.
     *
     * @return The name of the application component to handle the intent.
     *
     * @see #resolveActivity
     * @see #setComponent
     */
    public @Nullable ComponentName getComponent() {
        return mComponent;
    }

    /**
     * Get the bounds of the sender of this intent, in screen coordinates.  This can be
     * used as a hint to the receiver for animations and the like.  Null means that there
     * is no source bounds.
     */
    public @Nullable Rect getSourceBounds() {
        return mSourceBounds;
    }

    /**
     * Return the Activity component that should be used to handle this intent.
     * The appropriate component is determined based on the information in the
     * intent, evaluated as follows:
     *
     * <p>If {@link #getComponent} returns an explicit class, that is returned
     * without any further consideration.
     *
     * <p>The activity must handle the {@link Intent#CATEGORY_DEFAULT} Intent
     * category to be considered.
     *
     * <p>If {@link #getAction} is non-NULL, the activity must handle this
     * action.
     *
     * <p>If {@link #resolveType} returns non-NULL, the activity must handle
     * this type.
     *
     * <p>If {@link #addCategory} has added any categories, the activity must
     * handle ALL of the categories specified.
     *
     * <p>If {@link #getPackage} is non-NULL, only activity components in
     * that application package will be considered.
     *
     * <p>If there are no activities that satisfy all of these conditions, a
     * null string is returned.
     *
     * <p>If multiple activities are found to satisfy the intent, the one with
     * the highest priority will be used.  If there are multiple activities
     * with the same priority, the system will either pick the best activity
     * based on user preference, or resolve to a system class that will allow
     * the user to pick an activity and forward from there.
     *
     * <p>This method is implemented simply by calling
     * {@link PackageManager#resolveActivity} with the "defaultOnly" parameter
     * true.</p>
     * <p> This API is called for you as part of starting an activity from an
     * intent.  You do not normally need to call it yourself.</p>
     *
     * @param pm The package manager with which to resolve the Intent.
     *
     * @return Name of the component implementing an activity that can
     *         display the intent.
     *
     * @see #setComponent
     * @see #getComponent
     * @see #resolveActivityInfo
     */
    public ComponentName resolveActivity(@NonNull PackageManager pm) {
        if (mComponent != null) {
            return mComponent;
        }

        ResolveInfo info = pm.resolveActivity(
            this, PackageManager.MATCH_DEFAULT_ONLY);
        if (info != null) {
            return new ComponentName(
                    info.activityInfo.applicationInfo.packageName,
                    info.activityInfo.name);
        }

        return null;
    }

    /**
     * Resolve the Intent into an {@link ActivityInfo}
     * describing the activity that should execute the intent.  Resolution
     * follows the same rules as described for {@link #resolveActivity}, but
     * you get back the completely information about the resolved activity
     * instead of just its class name.
     *
     * @param pm The package manager with which to resolve the Intent.
     * @param flags Addition information to retrieve as per
     * {@link PackageManager#getActivityInfo(ComponentName, int)
     * PackageManager.getActivityInfo()}.
     *
     * @return PackageManager.ActivityInfo
     *
     * @see #resolveActivity
     */
    public ActivityInfo resolveActivityInfo(@NonNull PackageManager pm,
            @PackageManager.ComponentInfoFlagsBits int flags) {
        ActivityInfo ai = null;
        if (mComponent != null) {
            try {
                ai = pm.getActivityInfo(mComponent, flags);
            } catch (PackageManager.NameNotFoundException e) {
                // ignore
            }
        } else {
            ResolveInfo info = pm.resolveActivity(
                this, PackageManager.MATCH_DEFAULT_ONLY | flags);
            if (info != null) {
                ai = info.activityInfo;
            }
        }

        return ai;
    }

    /**
     * Special function for use by the system to resolve service
     * intents to system apps.  Throws an exception if there are
     * multiple potential matches to the Intent.  Returns null if
     * there are no matches.
     * @hide
     */
    @UnsupportedAppUsage
    public @Nullable ComponentName resolveSystemService(@NonNull PackageManager pm,
            @PackageManager.ComponentInfoFlagsBits int flags) {
        if (mComponent != null) {
            return mComponent;
        }

        List<ResolveInfo> results = pm.queryIntentServices(this, flags);
        if (results == null) {
            return null;
        }
        ComponentName comp = null;
        for (int i=0; i<results.size(); i++) {
            ResolveInfo ri = results.get(i);
            if ((ri.serviceInfo.applicationInfo.flags&ApplicationInfo.FLAG_SYSTEM) == 0) {
                continue;
            }
            ComponentName foundComp = new ComponentName(ri.serviceInfo.applicationInfo.packageName,
                    ri.serviceInfo.name);
            if (comp != null) {
                throw new IllegalStateException("Multiple system services handle " + this
                        + ": " + comp + ", " + foundComp);
            }
            comp = foundComp;
        }
        return comp;
    }

    /**
     * Set the general action to be performed.
     *
     * @param action An action name, such as ACTION_VIEW.  Application-specific
     *               actions should be prefixed with the vendor's package name.
     *
     * @return Returns the same Intent object, for chaining multiple calls
     * into a single statement.
     *
     * @see #getAction
     */
    public @NonNull Intent setAction(@Nullable String action) {
        mAction = action != null ? action.intern() : null;
        return this;
    }

    /**
     * Set the data this intent is operating on.  This method automatically
     * clears any type that was previously set by {@link #setType} or
     * {@link #setTypeAndNormalize}.
     *
     * <p><em>Note: scheme matching in the Android framework is
     * case-sensitive, unlike the formal RFC. As a result,
     * you should always write your Uri with a lower case scheme,
     * or use {@link Uri#normalizeScheme} or
     * {@link #setDataAndNormalize}
     * to ensure that the scheme is converted to lower case.</em>
     *
     * @param data The Uri of the data this intent is now targeting.
     *
     * @return Returns the same Intent object, for chaining multiple calls
     * into a single statement.
     *
     * @see #getData
     * @see #setDataAndNormalize
     * @see android.net.Uri#normalizeScheme()
     */
    public @NonNull Intent setData(@Nullable Uri data) {
        mData = data;
        mType = null;
        return this;
    }

    /**
     * Normalize and set the data this intent is operating on.
     *
     * <p>This method automatically clears any type that was
     * previously set (for example, by {@link #setType}).
     *
     * <p>The data Uri is normalized using
     * {@link android.net.Uri#normalizeScheme} before it is set,
     * so really this is just a convenience method for
     * <pre>
     * setData(data.normalize())
     * </pre>
     *
     * @param data The Uri of the data this intent is now targeting.
     *
     * @return Returns the same Intent object, for chaining multiple calls
     * into a single statement.
     *
     * @see #getData
     * @see #setType
     * @see android.net.Uri#normalizeScheme
     */
    public @NonNull Intent setDataAndNormalize(@NonNull Uri data) {
        return setData(data.normalizeScheme());
    }

    /**
     * Set an explicit MIME data type.
     *
     * <p>This is used to create intents that only specify a type and not data,
     * for example to indicate the type of data to return.
     *
     * <p>This method automatically clears any data that was
     * previously set (for example by {@link #setData}).
     *
     * <p><em>Note: MIME type matching in the Android framework is
     * case-sensitive, unlike formal RFC MIME types.  As a result,
     * you should always write your MIME types with lower case letters,
     * or use {@link #normalizeMimeType} or {@link #setTypeAndNormalize}
     * to ensure that it is converted to lower case.</em>
     *
     * @param type The MIME type of the data being handled by this intent.
     *
     * @return Returns the same Intent object, for chaining multiple calls
     * into a single statement.
     *
     * @see #getType
     * @see #setTypeAndNormalize
     * @see #setDataAndType
     * @see #normalizeMimeType
     */
    public @NonNull Intent setType(@Nullable String type) {
        mData = null;
        mType = type;
        return this;
    }

    /**
     * Normalize and set an explicit MIME data type.
     *
     * <p>This is used to create intents that only specify a type and not data,
     * for example to indicate the type of data to return.
     *
     * <p>This method automatically clears any data that was
     * previously set (for example by {@link #setData}).
     *
     * <p>The MIME type is normalized using
     * {@link #normalizeMimeType} before it is set,
     * so really this is just a convenience method for
     * <pre>
     * setType(Intent.normalizeMimeType(type))
     * </pre>
     *
     * @param type The MIME type of the data being handled by this intent.
     *
     * @return Returns the same Intent object, for chaining multiple calls
     * into a single statement.
     *
     * @see #getType
     * @see #setData
     * @see #normalizeMimeType
     */
    public @NonNull Intent setTypeAndNormalize(@Nullable String type) {
        return setType(normalizeMimeType(type));
    }

    /**
     * (Usually optional) Set the data for the intent along with an explicit
     * MIME data type.  This method should very rarely be used -- it allows you
     * to override the MIME type that would ordinarily be inferred from the
     * data with your own type given here.
     *
     * <p><em>Note: MIME type and Uri scheme matching in the
     * Android framework is case-sensitive, unlike the formal RFC definitions.
     * As a result, you should always write these elements with lower case letters,
     * or use {@link #normalizeMimeType} or {@link android.net.Uri#normalizeScheme} or
     * {@link #setDataAndTypeAndNormalize}
     * to ensure that they are converted to lower case.</em>
     *
     * @param data The Uri of the data this intent is now targeting.
     * @param type The MIME type of the data being handled by this intent.
     *
     * @return Returns the same Intent object, for chaining multiple calls
     * into a single statement.
     *
     * @see #setType
     * @see #setData
     * @see #normalizeMimeType
     * @see android.net.Uri#normalizeScheme
     * @see #setDataAndTypeAndNormalize
     */
    public @NonNull Intent setDataAndType(@Nullable Uri data, @Nullable String type) {
        mData = data;
        mType = type;
        return this;
    }

    /**
     * (Usually optional) Normalize and set both the data Uri and an explicit
     * MIME data type.  This method should very rarely be used -- it allows you
     * to override the MIME type that would ordinarily be inferred from the
     * data with your own type given here.
     *
     * <p>The data Uri and the MIME type are normalize using
     * {@link android.net.Uri#normalizeScheme} and {@link #normalizeMimeType}
     * before they are set, so really this is just a convenience method for
     * <pre>
     * setDataAndType(data.normalize(), Intent.normalizeMimeType(type))
     * </pre>
     *
     * @param data The Uri of the data this intent is now targeting.
     * @param type The MIME type of the data being handled by this intent.
     *
     * @return Returns the same Intent object, for chaining multiple calls
     * into a single statement.
     *
     * @see #setType
     * @see #setData
     * @see #setDataAndType
     * @see #normalizeMimeType
     * @see android.net.Uri#normalizeScheme
     */
    public @NonNull Intent setDataAndTypeAndNormalize(@NonNull Uri data, @Nullable String type) {
        return setDataAndType(data.normalizeScheme(), normalizeMimeType(type));
    }

    /**
     * Set an identifier for this Intent.  If set, this provides a unique identity for this Intent,
     * allowing it to be unique from other Intents that would otherwise look the same.  In
     * particular, this will be used by {@link #filterEquals(Intent)} to determine if two
     * Intents are the same as with other fields like {@link #setAction}.  However, unlike those
     * fields, the identifier is <em>never</em> used for matching against an {@link IntentFilter};
     * it is as if the identifier has not been set on the Intent.
     *
     * <p>This can be used, for example, to make this Intent unique from other Intents that
     * are otherwise the same, for use in creating a {@link android.app.PendingIntent}.  (Be aware
     * however that the receiver of the PendingIntent will see whatever you put in here.)  The
     * structure of this string is completely undefined by the platform, however if you are going
     * to be exposing identifier strings across different applications you may need to define
     * your own structure if there is no central party defining the contents of this field.</p>
     *
     * @param identifier The identifier for this Intent.  The contents of the string have no
     *                   meaning to the system, except whether they are exactly the same as
     *                   another identifier.
     *
     * @return Returns the same Intent object, for chaining multiple calls
     * into a single statement.
     *
     * @see #getIdentifier
     */
    public @NonNull Intent setIdentifier(@Nullable String identifier) {
        mIdentifier = identifier;
        return this;
    }

    /**
     * Add a new category to the intent.  Categories provide additional detail
     * about the action the intent performs.  When resolving an intent, only
     * activities that provide <em>all</em> of the requested categories will be
     * used.
     *
     * @param category The desired category.  This can be either one of the
     *               predefined Intent categories, or a custom category in your own
     *               namespace.
     *
     * @return Returns the same Intent object, for chaining multiple calls
     * into a single statement.
     *
     * @see #hasCategory
     * @see #removeCategory
     */
    public @NonNull Intent addCategory(String category) {
        if (mCategories == null) {
            mCategories = new ArraySet<String>();
        }
        mCategories.add(category.intern());
        return this;
    }

    /**
     * Remove a category from an intent.
     *
     * @param category The category to remove.
     *
     * @see #addCategory
     */
    public void removeCategory(String category) {
        if (mCategories != null) {
            mCategories.remove(category);
            if (mCategories.size() == 0) {
                mCategories = null;
            }
        }
    }

    /**
     * Set a selector for this Intent.  This is a modification to the kinds of
     * things the Intent will match.  If the selector is set, it will be used
     * when trying to find entities that can handle the Intent, instead of the
     * main contents of the Intent.  This allows you build an Intent containing
     * a generic protocol while targeting it more specifically.
     *
     * <p>An example of where this may be used is with things like
     * {@link #CATEGORY_APP_BROWSER}.  This category allows you to build an
     * Intent that will launch the Browser application.  However, the correct
     * main entry point of an application is actually {@link #ACTION_MAIN}
     * {@link #CATEGORY_LAUNCHER} with {@link #setComponent(ComponentName)}
     * used to specify the actual Activity to launch.  If you launch the browser
     * with something different, undesired behavior may happen if the user has
     * previously or later launches it the normal way, since they do not match.
     * Instead, you can build an Intent with the MAIN action (but no ComponentName
     * yet specified) and set a selector with {@link #ACTION_MAIN} and
     * {@link #CATEGORY_APP_BROWSER} to point it specifically to the browser activity.
     *
     * <p>Setting a selector does not impact the behavior of
     * {@link #filterEquals(Intent)} and {@link #filterHashCode()}.  This is part of the
     * desired behavior of a selector -- it does not impact the base meaning
     * of the Intent, just what kinds of things will be matched against it
     * when determining who can handle it.</p>
     *
     * <p>You can not use both a selector and {@link #setPackage(String)} on
     * the same base Intent.</p>
     *
     * @param selector The desired selector Intent; set to null to not use
     * a special selector.
     */
    public void setSelector(@Nullable Intent selector) {
        if (selector == this) {
            throw new IllegalArgumentException(
                    "Intent being set as a selector of itself");
        }
        if (selector != null && mPackage != null) {
            throw new IllegalArgumentException(
                    "Can't set selector when package name is already set");
        }
        mSelector = selector;
    }

    /**
     * Set a {@link ClipData} associated with this Intent.  This replaces any
     * previously set ClipData.
     *
     * <p>The ClipData in an intent is not used for Intent matching or other
     * such operations.  Semantically it is like extras, used to transmit
     * additional data with the Intent.  The main feature of using this over
     * the extras for data is that {@link #FLAG_GRANT_READ_URI_PERMISSION}
     * and {@link #FLAG_GRANT_WRITE_URI_PERMISSION} will operate on any URI
     * items included in the clip data.  This is useful, in particular, if
     * you want to transmit an Intent containing multiple <code>content:</code>
     * URIs for which the recipient may not have global permission to access the
     * content provider.
     *
     * <p>If the ClipData contains items that are themselves Intents, any
     * grant flags in those Intents will be ignored.  Only the top-level flags
     * of the main Intent are respected, and will be applied to all Uri or
     * Intent items in the clip (or sub-items of the clip).
     *
     * <p>The MIME type, label, and icon in the ClipData object are not
     * directly used by Intent.  Applications should generally rely on the
     * MIME type of the Intent itself, not what it may find in the ClipData.
     * A common practice is to construct a ClipData for use with an Intent
     * with a MIME type of "*&#47;*".
     *
     * @param clip The new clip to set.  May be null to clear the current clip.
     */
    public void setClipData(@Nullable ClipData clip) {
        mClipData = clip;
    }

    /**
     * This is NOT a secure mechanism to identify the user who sent the intent.
     * When the intent is sent to a different user, it is used to fix uris by adding the user ID
     * who sent the intent.
     * @hide
     */
    public void prepareToLeaveUser(int userId) {
        // If mContentUserHint is not UserHandle.USER_CURRENT, the intent has already left a user.
        // We want mContentUserHint to refer to the original user, so don't do anything.
        if (mContentUserHint == UserHandle.USER_CURRENT) {
            mContentUserHint = userId;
        }
    }

    /**
     * Add extended data to the intent.  The name must include a package
     * prefix, for example the app com.android.contacts would use names
     * like "com.android.contacts.ShowAll".
     *
     * @param name The name of the extra data, with package prefix.
     * @param value The boolean data value.
     *
     * @return Returns the same Intent object, for chaining multiple calls
     * into a single statement.
     *
     * @see #putExtras
     * @see #removeExtra
     * @see #getBooleanExtra(String, boolean)
     */
    public @NonNull Intent putExtra(String name, boolean value) {
        if (mExtras == null) {
            mExtras = new Bundle();
        }
        mExtras.putBoolean(name, value);
        return this;
    }

    /**
     * Add extended data to the intent.  The name must include a package
     * prefix, for example the app com.android.contacts would use names
     * like "com.android.contacts.ShowAll".
     *
     * @param name The name of the extra data, with package prefix.
     * @param value The byte data value.
     *
     * @return Returns the same Intent object, for chaining multiple calls
     * into a single statement.
     *
     * @see #putExtras
     * @see #removeExtra
     * @see #getByteExtra(String, byte)
     */
    public @NonNull Intent putExtra(String name, byte value) {
        if (mExtras == null) {
            mExtras = new Bundle();
        }
        mExtras.putByte(name, value);
        return this;
    }

    /**
     * Add extended data to the intent.  The name must include a package
     * prefix, for example the app com.android.contacts would use names
     * like "com.android.contacts.ShowAll".
     *
     * @param name The name of the extra data, with package prefix.
     * @param value The char data value.
     *
     * @return Returns the same Intent object, for chaining multiple calls
     * into a single statement.
     *
     * @see #putExtras
     * @see #removeExtra
     * @see #getCharExtra(String, char)
     */
    public @NonNull Intent putExtra(String name, char value) {
        if (mExtras == null) {
            mExtras = new Bundle();
        }
        mExtras.putChar(name, value);
        return this;
    }

    /**
     * Add extended data to the intent.  The name must include a package
     * prefix, for example the app com.android.contacts would use names
     * like "com.android.contacts.ShowAll".
     *
     * @param name The name of the extra data, with package prefix.
     * @param value The short data value.
     *
     * @return Returns the same Intent object, for chaining multiple calls
     * into a single statement.
     *
     * @see #putExtras
     * @see #removeExtra
     * @see #getShortExtra(String, short)
     */
    public @NonNull Intent putExtra(String name, short value) {
        if (mExtras == null) {
            mExtras = new Bundle();
        }
        mExtras.putShort(name, value);
        return this;
    }

    /**
     * Add extended data to the intent.  The name must include a package
     * prefix, for example the app com.android.contacts would use names
     * like "com.android.contacts.ShowAll".
     *
     * @param name The name of the extra data, with package prefix.
     * @param value The integer data value.
     *
     * @return Returns the same Intent object, for chaining multiple calls
     * into a single statement.
     *
     * @see #putExtras
     * @see #removeExtra
     * @see #getIntExtra(String, int)
     */
    public @NonNull Intent putExtra(String name, int value) {
        if (mExtras == null) {
            mExtras = new Bundle();
        }
        mExtras.putInt(name, value);
        return this;
    }

    /**
     * Add extended data to the intent.  The name must include a package
     * prefix, for example the app com.android.contacts would use names
     * like "com.android.contacts.ShowAll".
     *
     * @param name The name of the extra data, with package prefix.
     * @param value The long data value.
     *
     * @return Returns the same Intent object, for chaining multiple calls
     * into a single statement.
     *
     * @see #putExtras
     * @see #removeExtra
     * @see #getLongExtra(String, long)
     */
    public @NonNull Intent putExtra(String name, long value) {
        if (mExtras == null) {
            mExtras = new Bundle();
        }
        mExtras.putLong(name, value);
        return this;
    }

    /**
     * Add extended data to the intent.  The name must include a package
     * prefix, for example the app com.android.contacts would use names
     * like "com.android.contacts.ShowAll".
     *
     * @param name The name of the extra data, with package prefix.
     * @param value The float data value.
     *
     * @return Returns the same Intent object, for chaining multiple calls
     * into a single statement.
     *
     * @see #putExtras
     * @see #removeExtra
     * @see #getFloatExtra(String, float)
     */
    public @NonNull Intent putExtra(String name, float value) {
        if (mExtras == null) {
            mExtras = new Bundle();
        }
        mExtras.putFloat(name, value);
        return this;
    }

    /**
     * Add extended data to the intent.  The name must include a package
     * prefix, for example the app com.android.contacts would use names
     * like "com.android.contacts.ShowAll".
     *
     * @param name The name of the extra data, with package prefix.
     * @param value The double data value.
     *
     * @return Returns the same Intent object, for chaining multiple calls
     * into a single statement.
     *
     * @see #putExtras
     * @see #removeExtra
     * @see #getDoubleExtra(String, double)
     */
    public @NonNull Intent putExtra(String name, double value) {
        if (mExtras == null) {
            mExtras = new Bundle();
        }
        mExtras.putDouble(name, value);
        return this;
    }

    /**
     * Add extended data to the intent.  The name must include a package
     * prefix, for example the app com.android.contacts would use names
     * like "com.android.contacts.ShowAll".
     *
     * @param name The name of the extra data, with package prefix.
     * @param value The String data value.
     *
     * @return Returns the same Intent object, for chaining multiple calls
     * into a single statement.
     *
     * @see #putExtras
     * @see #removeExtra
     * @see #getStringExtra(String)
     */
    public @NonNull Intent putExtra(String name, @Nullable String value) {
        if (mExtras == null) {
            mExtras = new Bundle();
        }
        mExtras.putString(name, value);
        return this;
    }

    /**
     * Add extended data to the intent.  The name must include a package
     * prefix, for example the app com.android.contacts would use names
     * like "com.android.contacts.ShowAll".
     *
     * @param name The name of the extra data, with package prefix.
     * @param value The CharSequence data value.
     *
     * @return Returns the same Intent object, for chaining multiple calls
     * into a single statement.
     *
     * @see #putExtras
     * @see #removeExtra
     * @see #getCharSequenceExtra(String)
     */
    public @NonNull Intent putExtra(String name, @Nullable CharSequence value) {
        if (mExtras == null) {
            mExtras = new Bundle();
        }
        mExtras.putCharSequence(name, value);
        return this;
    }

    /**
     * Add extended data to the intent.  The name must include a package
     * prefix, for example the app com.android.contacts would use names
     * like "com.android.contacts.ShowAll".
     *
     * @param name The name of the extra data, with package prefix.
     * @param value The Parcelable data value.
     *
     * @return Returns the same Intent object, for chaining multiple calls
     * into a single statement.
     *
     * @see #putExtras
     * @see #removeExtra
     * @see #getParcelableExtra(String)
     */
    public @NonNull Intent putExtra(String name, @Nullable Parcelable value) {
        if (mExtras == null) {
            mExtras = new Bundle();
        }
        mExtras.putParcelable(name, value);
        return this;
    }

    /**
     * Add extended data to the intent.  The name must include a package
     * prefix, for example the app com.android.contacts would use names
     * like "com.android.contacts.ShowAll".
     *
     * @param name The name of the extra data, with package prefix.
     * @param value The Parcelable[] data value.
     *
     * @return Returns the same Intent object, for chaining multiple calls
     * into a single statement.
     *
     * @see #putExtras
     * @see #removeExtra
     * @see #getParcelableArrayExtra(String)
     */
    public @NonNull Intent putExtra(String name, @Nullable Parcelable[] value) {
        if (mExtras == null) {
            mExtras = new Bundle();
        }
        mExtras.putParcelableArray(name, value);
        return this;
    }

    /**
     * Add extended data to the intent.  The name must include a package
     * prefix, for example the app com.android.contacts would use names
     * like "com.android.contacts.ShowAll".
     *
     * @param name The name of the extra data, with package prefix.
     * @param value The ArrayList<Parcelable> data value.
     *
     * @return Returns the same Intent object, for chaining multiple calls
     * into a single statement.
     *
     * @see #putExtras
     * @see #removeExtra
     * @see #getParcelableArrayListExtra(String)
     */
    public @NonNull Intent putParcelableArrayListExtra(String name,
            @Nullable ArrayList<? extends Parcelable> value) {
        if (mExtras == null) {
            mExtras = new Bundle();
        }
        mExtras.putParcelableArrayList(name, value);
        return this;
    }

    /**
     * Add extended data to the intent.  The name must include a package
     * prefix, for example the app com.android.contacts would use names
     * like "com.android.contacts.ShowAll".
     *
     * @param name The name of the extra data, with package prefix.
     * @param value The ArrayList<Integer> data value.
     *
     * @return Returns the same Intent object, for chaining multiple calls
     * into a single statement.
     *
     * @see #putExtras
     * @see #removeExtra
     * @see #getIntegerArrayListExtra(String)
     */
    public @NonNull Intent putIntegerArrayListExtra(String name,
            @Nullable ArrayList<Integer> value) {
        if (mExtras == null) {
            mExtras = new Bundle();
        }
        mExtras.putIntegerArrayList(name, value);
        return this;
    }

    /**
     * Add extended data to the intent.  The name must include a package
     * prefix, for example the app com.android.contacts would use names
     * like "com.android.contacts.ShowAll".
     *
     * @param name The name of the extra data, with package prefix.
     * @param value The ArrayList<String> data value.
     *
     * @return Returns the same Intent object, for chaining multiple calls
     * into a single statement.
     *
     * @see #putExtras
     * @see #removeExtra
     * @see #getStringArrayListExtra(String)
     */
    public @NonNull Intent putStringArrayListExtra(String name, @Nullable ArrayList<String> value) {
        if (mExtras == null) {
            mExtras = new Bundle();
        }
        mExtras.putStringArrayList(name, value);
        return this;
    }

    /**
     * Add extended data to the intent.  The name must include a package
     * prefix, for example the app com.android.contacts would use names
     * like "com.android.contacts.ShowAll".
     *
     * @param name The name of the extra data, with package prefix.
     * @param value The ArrayList<CharSequence> data value.
     *
     * @return Returns the same Intent object, for chaining multiple calls
     * into a single statement.
     *
     * @see #putExtras
     * @see #removeExtra
     * @see #getCharSequenceArrayListExtra(String)
     */
    public @NonNull Intent putCharSequenceArrayListExtra(String name,
            @Nullable ArrayList<CharSequence> value) {
        if (mExtras == null) {
            mExtras = new Bundle();
        }
        mExtras.putCharSequenceArrayList(name, value);
        return this;
    }

    /**
     * Add extended data to the intent.  The name must include a package
     * prefix, for example the app com.android.contacts would use names
     * like "com.android.contacts.ShowAll".
     *
     * @param name The name of the extra data, with package prefix.
     * @param value The Serializable data value.
     *
     * @return Returns the same Intent object, for chaining multiple calls
     * into a single statement.
     *
     * @see #putExtras
     * @see #removeExtra
     * @see #getSerializableExtra(String)
     */
    public @NonNull Intent putExtra(String name, @Nullable Serializable value) {
        if (mExtras == null) {
            mExtras = new Bundle();
        }
        mExtras.putSerializable(name, value);
        return this;
    }

    /**
     * Add extended data to the intent.  The name must include a package
     * prefix, for example the app com.android.contacts would use names
     * like "com.android.contacts.ShowAll".
     *
     * @param name The name of the extra data, with package prefix.
     * @param value The boolean array data value.
     *
     * @return Returns the same Intent object, for chaining multiple calls
     * into a single statement.
     *
     * @see #putExtras
     * @see #removeExtra
     * @see #getBooleanArrayExtra(String)
     */
    public @NonNull Intent putExtra(String name, @Nullable boolean[] value) {
        if (mExtras == null) {
            mExtras = new Bundle();
        }
        mExtras.putBooleanArray(name, value);
        return this;
    }

    /**
     * Add extended data to the intent.  The name must include a package
     * prefix, for example the app com.android.contacts would use names
     * like "com.android.contacts.ShowAll".
     *
     * @param name The name of the extra data, with package prefix.
     * @param value The byte array data value.
     *
     * @return Returns the same Intent object, for chaining multiple calls
     * into a single statement.
     *
     * @see #putExtras
     * @see #removeExtra
     * @see #getByteArrayExtra(String)
     */
    public @NonNull Intent putExtra(String name, @Nullable byte[] value) {
        if (mExtras == null) {
            mExtras = new Bundle();
        }
        mExtras.putByteArray(name, value);
        return this;
    }

    /**
     * Add extended data to the intent.  The name must include a package
     * prefix, for example the app com.android.contacts would use names
     * like "com.android.contacts.ShowAll".
     *
     * @param name The name of the extra data, with package prefix.
     * @param value The short array data value.
     *
     * @return Returns the same Intent object, for chaining multiple calls
     * into a single statement.
     *
     * @see #putExtras
     * @see #removeExtra
     * @see #getShortArrayExtra(String)
     */
    public @NonNull Intent putExtra(String name, @Nullable short[] value) {
        if (mExtras == null) {
            mExtras = new Bundle();
        }
        mExtras.putShortArray(name, value);
        return this;
    }

    /**
     * Add extended data to the intent.  The name must include a package
     * prefix, for example the app com.android.contacts would use names
     * like "com.android.contacts.ShowAll".
     *
     * @param name The name of the extra data, with package prefix.
     * @param value The char array data value.
     *
     * @return Returns the same Intent object, for chaining multiple calls
     * into a single statement.
     *
     * @see #putExtras
     * @see #removeExtra
     * @see #getCharArrayExtra(String)
     */
    public @NonNull Intent putExtra(String name, @Nullable char[] value) {
        if (mExtras == null) {
            mExtras = new Bundle();
        }
        mExtras.putCharArray(name, value);
        return this;
    }

    /**
     * Add extended data to the intent.  The name must include a package
     * prefix, for example the app com.android.contacts would use names
     * like "com.android.contacts.ShowAll".
     *
     * @param name The name of the extra data, with package prefix.
     * @param value The int array data value.
     *
     * @return Returns the same Intent object, for chaining multiple calls
     * into a single statement.
     *
     * @see #putExtras
     * @see #removeExtra
     * @see #getIntArrayExtra(String)
     */
    public @NonNull Intent putExtra(String name, @Nullable int[] value) {
        if (mExtras == null) {
            mExtras = new Bundle();
        }
        mExtras.putIntArray(name, value);
        return this;
    }

    /**
     * Add extended data to the intent.  The name must include a package
     * prefix, for example the app com.android.contacts would use names
     * like "com.android.contacts.ShowAll".
     *
     * @param name The name of the extra data, with package prefix.
     * @param value The byte array data value.
     *
     * @return Returns the same Intent object, for chaining multiple calls
     * into a single statement.
     *
     * @see #putExtras
     * @see #removeExtra
     * @see #getLongArrayExtra(String)
     */
    public @NonNull Intent putExtra(String name, @Nullable long[] value) {
        if (mExtras == null) {
            mExtras = new Bundle();
        }
        mExtras.putLongArray(name, value);
        return this;
    }

    /**
     * Add extended data to the intent.  The name must include a package
     * prefix, for example the app com.android.contacts would use names
     * like "com.android.contacts.ShowAll".
     *
     * @param name The name of the extra data, with package prefix.
     * @param value The float array data value.
     *
     * @return Returns the same Intent object, for chaining multiple calls
     * into a single statement.
     *
     * @see #putExtras
     * @see #removeExtra
     * @see #getFloatArrayExtra(String)
     */
    public @NonNull Intent putExtra(String name, @Nullable float[] value) {
        if (mExtras == null) {
            mExtras = new Bundle();
        }
        mExtras.putFloatArray(name, value);
        return this;
    }

    /**
     * Add extended data to the intent.  The name must include a package
     * prefix, for example the app com.android.contacts would use names
     * like "com.android.contacts.ShowAll".
     *
     * @param name The name of the extra data, with package prefix.
     * @param value The double array data value.
     *
     * @return Returns the same Intent object, for chaining multiple calls
     * into a single statement.
     *
     * @see #putExtras
     * @see #removeExtra
     * @see #getDoubleArrayExtra(String)
     */
    public @NonNull Intent putExtra(String name, @Nullable double[] value) {
        if (mExtras == null) {
            mExtras = new Bundle();
        }
        mExtras.putDoubleArray(name, value);
        return this;
    }

    /**
     * Add extended data to the intent.  The name must include a package
     * prefix, for example the app com.android.contacts would use names
     * like "com.android.contacts.ShowAll".
     *
     * @param name The name of the extra data, with package prefix.
     * @param value The String array data value.
     *
     * @return Returns the same Intent object, for chaining multiple calls
     * into a single statement.
     *
     * @see #putExtras
     * @see #removeExtra
     * @see #getStringArrayExtra(String)
     */
    public @NonNull Intent putExtra(String name, @Nullable String[] value) {
        if (mExtras == null) {
            mExtras = new Bundle();
        }
        mExtras.putStringArray(name, value);
        return this;
    }

    /**
     * Add extended data to the intent.  The name must include a package
     * prefix, for example the app com.android.contacts would use names
     * like "com.android.contacts.ShowAll".
     *
     * @param name The name of the extra data, with package prefix.
     * @param value The CharSequence array data value.
     *
     * @return Returns the same Intent object, for chaining multiple calls
     * into a single statement.
     *
     * @see #putExtras
     * @see #removeExtra
     * @see #getCharSequenceArrayExtra(String)
     */
    public @NonNull Intent putExtra(String name, @Nullable CharSequence[] value) {
        if (mExtras == null) {
            mExtras = new Bundle();
        }
        mExtras.putCharSequenceArray(name, value);
        return this;
    }

    /**
     * Add extended data to the intent.  The name must include a package
     * prefix, for example the app com.android.contacts would use names
     * like "com.android.contacts.ShowAll".
     *
     * @param name The name of the extra data, with package prefix.
     * @param value The Bundle data value.
     *
     * @return Returns the same Intent object, for chaining multiple calls
     * into a single statement.
     *
     * @see #putExtras
     * @see #removeExtra
     * @see #getBundleExtra(String)
     */
    public @NonNull Intent putExtra(String name, @Nullable Bundle value) {
        if (mExtras == null) {
            mExtras = new Bundle();
        }
        mExtras.putBundle(name, value);
        return this;
    }

    /**
     * Add extended data to the intent.  The name must include a package
     * prefix, for example the app com.android.contacts would use names
     * like "com.android.contacts.ShowAll".
     *
     * @param name The name of the extra data, with package prefix.
     * @param value The IBinder data value.
     *
     * @return Returns the same Intent object, for chaining multiple calls
     * into a single statement.
     *
     * @see #putExtras
     * @see #removeExtra
     * @see #getIBinderExtra(String)
     *
     * @deprecated
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage
    public @NonNull Intent putExtra(String name, IBinder value) {
        if (mExtras == null) {
            mExtras = new Bundle();
        }
        mExtras.putIBinder(name, value);
        return this;
    }

    /**
     * Copy all extras in 'src' in to this intent.
     *
     * @param src Contains the extras to copy.
     *
     * @see #putExtra
     */
    public @NonNull Intent putExtras(@NonNull Intent src) {
        if (src.mExtras != null) {
            if (mExtras == null) {
                mExtras = new Bundle(src.mExtras);
            } else {
                mExtras.putAll(src.mExtras);
            }
        }
        // If the provided Intent was unparceled and this is not an Intent delivered to a protected
        // component then mark the extras as unfiltered. An Intent delivered to a protected
        // component had to come from a trusted component, and if unfiltered data was copied to the
        // delivered Intent then it would have been reported when that Intent left the sending
        // process.
        if ((src.mLocalFlags & LOCAL_FLAG_FROM_PARCEL) != 0
                && (src.mLocalFlags & (
                        LOCAL_FLAG_FROM_PROTECTED_COMPONENT
                                | LOCAL_FLAG_FROM_SYSTEM)) == 0) {
            mLocalFlags |= LOCAL_FLAG_UNFILTERED_EXTRAS;
        }
        return this;
    }

    /**
     * Add a set of extended data to the intent.  The keys must include a package
     * prefix, for example the app com.android.contacts would use names
     * like "com.android.contacts.ShowAll".
     *
     * @param extras The Bundle of extras to add to this intent.
     *
     * @see #putExtra
     * @see #removeExtra
     */
    public @NonNull Intent putExtras(@NonNull Bundle extras) {
        // If the provided Bundle has not yet been unparceled then treat this as unfiltered extras.
        if (extras.isParcelled()) {
            mLocalFlags |= LOCAL_FLAG_UNFILTERED_EXTRAS;
        }
        if (mExtras == null) {
            mExtras = new Bundle();
        }
        mExtras.putAll(extras);
        return this;
    }

    /**
     * Completely replace the extras in the Intent with the extras in the
     * given Intent.
     *
     * @param src The exact extras contained in this Intent are copied
     * into the target intent, replacing any that were previously there.
     */
    public @NonNull Intent replaceExtras(@NonNull Intent src) {
        mExtras = src.mExtras != null ? new Bundle(src.mExtras) : null;
        return this;
    }

    /**
     * Completely replace the extras in the Intent with the given Bundle of
     * extras.
     *
     * @param extras The new set of extras in the Intent, or null to erase
     * all extras.
     */
    public @NonNull Intent replaceExtras(@Nullable Bundle extras) {
        mExtras = extras != null ? new Bundle(extras) : null;
        return this;
    }

    /**
     * Remove extended data from the intent.
     *
     * @see #putExtra
     */
    public void removeExtra(String name) {
        if (mExtras != null) {
            mExtras.remove(name);
            if (mExtras.size() == 0) {
                mExtras = null;
            }
        }
    }

    /**
     * Set special flags controlling how this intent is handled.  Most values
     * here depend on the type of component being executed by the Intent,
     * specifically the FLAG_ACTIVITY_* flags are all for use with
     * {@link Context#startActivity Context.startActivity()} and the
     * FLAG_RECEIVER_* flags are all for use with
     * {@link Context#sendBroadcast(Intent) Context.sendBroadcast()}.
     *
     * <p>See the
     * <a href="{@docRoot}guide/topics/fundamentals/tasks-and-back-stack.html">Tasks and Back
     * Stack</a> documentation for important information on how some of these options impact
     * the behavior of your application.
     *
     * @param flags The desired flags.
     * @return Returns the same Intent object, for chaining multiple calls
     * into a single statement.
     * @see #getFlags
     * @see #addFlags
     * @see #removeFlags
     */
    public @NonNull Intent setFlags(@Flags int flags) {
        mFlags = flags;
        return this;
    }

    /**
     * Add additional flags to the intent (or with existing flags value).
     *
     * @param flags The new flags to set.
     * @return Returns the same Intent object, for chaining multiple calls into
     *         a single statement.
     * @see #setFlags
     * @see #getFlags
     * @see #removeFlags
     */
    public @NonNull Intent addFlags(@Flags int flags) {
        mFlags |= flags;
        return this;
    }

    /**
     * Remove these flags from the intent.
     *
     * @param flags The flags to remove.
     * @see #setFlags
     * @see #getFlags
     * @see #addFlags
     */
    public void removeFlags(@Flags int flags) {
        mFlags &= ~flags;
    }

    /**
     * (Usually optional) Set an explicit application package name that limits
     * the components this Intent will resolve to.  If left to the default
     * value of null, all components in all applications will considered.
     * If non-null, the Intent can only match the components in the given
     * application package.
     *
     * @param packageName The name of the application package to handle the
     * intent, or null to allow any application package.
     *
     * @return Returns the same Intent object, for chaining multiple calls
     * into a single statement.
     *
     * @see #getPackage
     * @see #resolveActivity
     */
    public @NonNull Intent setPackage(@Nullable String packageName) {
        if (packageName != null && mSelector != null) {
            throw new IllegalArgumentException(
                    "Can't set package name when selector is already set");
        }
        mPackage = packageName;
        return this;
    }

    /**
     * (Usually optional) Explicitly set the component to handle the intent.
     * If left with the default value of null, the system will determine the
     * appropriate class to use based on the other fields (action, data,
     * type, categories) in the Intent.  If this class is defined, the
     * specified class will always be used regardless of the other fields.  You
     * should only set this value when you know you absolutely want a specific
     * class to be used; otherwise it is better to let the system find the
     * appropriate class so that you will respect the installed applications
     * and user preferences.
     *
     * @param component The name of the application component to handle the
     * intent, or null to let the system find one for you.
     *
     * @return Returns the same Intent object, for chaining multiple calls
     * into a single statement.
     *
     * @see #setClass
     * @see #setClassName(Context, String)
     * @see #setClassName(String, String)
     * @see #getComponent
     * @see #resolveActivity
     */
    public @NonNull Intent setComponent(@Nullable ComponentName component) {
        mComponent = component;
        return this;
    }

    /**
     * Convenience for calling {@link #setComponent} with an
     * explicit class name.
     *
     * @param packageContext A Context of the application package implementing
     * this class.
     * @param className The name of a class inside of the application package
     * that will be used as the component for this Intent.
     *
     * @return Returns the same Intent object, for chaining multiple calls
     * into a single statement.
     *
     * @see #setComponent
     * @see #setClass
     */
    public @NonNull Intent setClassName(@NonNull Context packageContext,
            @NonNull String className) {
        mComponent = new ComponentName(packageContext, className);
        return this;
    }

    /**
     * Convenience for calling {@link #setComponent} with an
     * explicit application package name and class name.
     *
     * @param packageName The name of the package implementing the desired
     * component.
     * @param className The name of a class inside of the application package
     * that will be used as the component for this Intent.
     *
     * @return Returns the same Intent object, for chaining multiple calls
     * into a single statement.
     *
     * @see #setComponent
     * @see #setClass
     */
    public @NonNull Intent setClassName(@NonNull String packageName, @NonNull String className) {
        mComponent = new ComponentName(packageName, className);
        return this;
    }

    /**
     * Convenience for calling {@link #setComponent(ComponentName)} with the
     * name returned by a {@link Class} object.
     *
     * @param packageContext A Context of the application package implementing
     * this class.
     * @param cls The class name to set, equivalent to
     *            <code>setClassName(context, cls.getName())</code>.
     *
     * @return Returns the same Intent object, for chaining multiple calls
     * into a single statement.
     *
     * @see #setComponent
     */
    public @NonNull Intent setClass(@NonNull Context packageContext, @NonNull Class<?> cls) {
        mComponent = new ComponentName(packageContext, cls);
        return this;
    }

    /**
     * Set the bounds of the sender of this intent, in screen coordinates.  This can be
     * used as a hint to the receiver for animations and the like.  Null means that there
     * is no source bounds.
     */
    public void setSourceBounds(@Nullable Rect r) {
        if (r != null) {
            mSourceBounds = new Rect(r);
        } else {
            mSourceBounds = null;
        }
    }

    /** @hide */
    @IntDef(flag = true, prefix = { "FILL_IN_" }, value = {
            FILL_IN_ACTION,
            FILL_IN_DATA,
            FILL_IN_CATEGORIES,
            FILL_IN_COMPONENT,
            FILL_IN_PACKAGE,
            FILL_IN_SOURCE_BOUNDS,
            FILL_IN_SELECTOR,
            FILL_IN_CLIP_DATA
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface FillInFlags {}

    /**
     * Use with {@link #fillIn} to allow the current action value to be
     * overwritten, even if it is already set.
     */
    public static final int FILL_IN_ACTION = 1<<0;

    /**
     * Use with {@link #fillIn} to allow the current data or type value
     * overwritten, even if it is already set.
     */
    public static final int FILL_IN_DATA = 1<<1;

    /**
     * Use with {@link #fillIn} to allow the current categories to be
     * overwritten, even if they are already set.
     */
    public static final int FILL_IN_CATEGORIES = 1<<2;

    /**
     * Use with {@link #fillIn} to allow the current component value to be
     * overwritten, even if it is already set.
     */
    public static final int FILL_IN_COMPONENT = 1<<3;

    /**
     * Use with {@link #fillIn} to allow the current package value to be
     * overwritten, even if it is already set.
     */
    public static final int FILL_IN_PACKAGE = 1<<4;

    /**
     * Use with {@link #fillIn} to allow the current bounds rectangle to be
     * overwritten, even if it is already set.
     */
    public static final int FILL_IN_SOURCE_BOUNDS = 1<<5;

    /**
     * Use with {@link #fillIn} to allow the current selector to be
     * overwritten, even if it is already set.
     */
    public static final int FILL_IN_SELECTOR = 1<<6;

    /**
     * Use with {@link #fillIn} to allow the current ClipData to be
     * overwritten, even if it is already set.
     */
    public static final int FILL_IN_CLIP_DATA = 1<<7;

    /**
     * Use with {@link #fillIn} to allow the current identifier value to be
     * overwritten, even if it is already set.
     */
    public static final int FILL_IN_IDENTIFIER = 1<<8;

    /**
     * Copy the contents of <var>other</var> in to this object, but only
     * where fields are not defined by this object.  For purposes of a field
     * being defined, the following pieces of data in the Intent are
     * considered to be separate fields:
     *
     * <ul>
     * <li> action, as set by {@link #setAction}.
     * <li> data Uri and MIME type, as set by {@link #setData(Uri)},
     * {@link #setType(String)}, or {@link #setDataAndType(Uri, String)}.
     * <li> identifier, as set by {@link #setIdentifier}.
     * <li> categories, as set by {@link #addCategory}.
     * <li> package, as set by {@link #setPackage}.
     * <li> component, as set by {@link #setComponent(ComponentName)} or
     * related methods.
     * <li> source bounds, as set by {@link #setSourceBounds}.
     * <li> selector, as set by {@link #setSelector(Intent)}.
     * <li> clip data, as set by {@link #setClipData(ClipData)}.
     * <li> each top-level name in the associated extras.
     * </ul>
     *
     * <p>In addition, you can use the {@link #FILL_IN_ACTION},
     * {@link #FILL_IN_DATA}, {@link #FILL_IN_IDENTIFIER}, {@link #FILL_IN_CATEGORIES},
     * {@link #FILL_IN_PACKAGE}, {@link #FILL_IN_COMPONENT}, {@link #FILL_IN_SOURCE_BOUNDS},
     * {@link #FILL_IN_SELECTOR}, and {@link #FILL_IN_CLIP_DATA} to override
     * the restriction where the corresponding field will not be replaced if
     * it is already set.
     *
     * <p>Note: The component field will only be copied if {@link #FILL_IN_COMPONENT}
     * is explicitly specified.  The selector will only be copied if
     * {@link #FILL_IN_SELECTOR} is explicitly specified.
     *
     * <p>For example, consider Intent A with {data="foo", categories="bar"}
     * and Intent B with {action="gotit", data-type="some/thing",
     * categories="one","two"}.
     *
     * <p>Calling A.fillIn(B, Intent.FILL_IN_DATA) will result in A now
     * containing: {action="gotit", data-type="some/thing",
     * categories="bar"}.
     *
     * @param other Another Intent whose values are to be used to fill in
     * the current one.
     * @param flags Options to control which fields can be filled in.
     *
     * @return Returns a bit mask of {@link #FILL_IN_ACTION},
     * {@link #FILL_IN_DATA}, {@link #FILL_IN_CATEGORIES}, {@link #FILL_IN_PACKAGE},
     * {@link #FILL_IN_COMPONENT}, {@link #FILL_IN_SOURCE_BOUNDS},
     * {@link #FILL_IN_SELECTOR} and {@link #FILL_IN_CLIP_DATA} indicating which fields were
     * changed.
     */
    @FillInFlags
    public int fillIn(@NonNull Intent other, @FillInFlags int flags) {
        int changes = 0;
        boolean mayHaveCopiedUris = false;
        if (other.mAction != null
                && (mAction == null || (flags&FILL_IN_ACTION) != 0)) {
            mAction = other.mAction;
            changes |= FILL_IN_ACTION;
        }
        if ((other.mData != null || other.mType != null)
                && ((mData == null && mType == null)
                        || (flags&FILL_IN_DATA) != 0)) {
            mData = other.mData;
            mType = other.mType;
            changes |= FILL_IN_DATA;
            mayHaveCopiedUris = true;
        }
        if (other.mIdentifier != null
                && (mIdentifier == null || (flags&FILL_IN_IDENTIFIER) != 0)) {
            mIdentifier = other.mIdentifier;
            changes |= FILL_IN_IDENTIFIER;
        }
        if (other.mCategories != null
                && (mCategories == null || (flags&FILL_IN_CATEGORIES) != 0)) {
            if (other.mCategories != null) {
                mCategories = new ArraySet<String>(other.mCategories);
            }
            changes |= FILL_IN_CATEGORIES;
        }
        if (other.mPackage != null
                && (mPackage == null || (flags&FILL_IN_PACKAGE) != 0)) {
            // Only do this if mSelector is not set.
            if (mSelector == null) {
                mPackage = other.mPackage;
                changes |= FILL_IN_PACKAGE;
            }
        }
        // Selector is special: it can only be set if explicitly allowed,
        // for the same reason as the component name.
        if (other.mSelector != null && (flags&FILL_IN_SELECTOR) != 0) {
            if (mPackage == null) {
                mSelector = new Intent(other.mSelector);
                mPackage = null;
                changes |= FILL_IN_SELECTOR;
            }
        }
        if (other.mClipData != null
                && (mClipData == null || (flags&FILL_IN_CLIP_DATA) != 0)) {
            mClipData = other.mClipData;
            changes |= FILL_IN_CLIP_DATA;
            mayHaveCopiedUris = true;
        }
        // Component is special: it can -only- be set if explicitly allowed,
        // since otherwise the sender could force the intent somewhere the
        // originator didn't intend.
        if (other.mComponent != null && (flags&FILL_IN_COMPONENT) != 0) {
            mComponent = other.mComponent;
            changes |= FILL_IN_COMPONENT;
        }
        mFlags |= other.mFlags;
        if (other.mSourceBounds != null
                && (mSourceBounds == null || (flags&FILL_IN_SOURCE_BOUNDS) != 0)) {
            mSourceBounds = new Rect(other.mSourceBounds);
            changes |= FILL_IN_SOURCE_BOUNDS;
        }
        if (mExtras == null) {
            if (other.mExtras != null) {
                mExtras = new Bundle(other.mExtras);
                mayHaveCopiedUris = true;
            }
        } else if (other.mExtras != null) {
            try {
                Bundle newb = new Bundle(other.mExtras);
                newb.putAll(mExtras);
                mExtras = newb;
                mayHaveCopiedUris = true;
            } catch (RuntimeException e) {
                // Modifying the extras can cause us to unparcel the contents
                // of the bundle, and if we do this in the system process that
                // may fail.  We really should handle this (i.e., the Bundle
                // impl shouldn't be on top of a plain map), but for now just
                // ignore it and keep the original contents. :(
                Log.w(TAG, "Failure filling in extras", e);
            }
        }
        if (mayHaveCopiedUris && mContentUserHint == UserHandle.USER_CURRENT
                && other.mContentUserHint != UserHandle.USER_CURRENT) {
            mContentUserHint = other.mContentUserHint;
        }
        return changes;
    }

    /**
     * Merge the extras data in this intent with that of other supplied intent using the
     * strategy specified using {@code extrasMerger}.
     *
     * <p> Note the extras data in this intent is treated as the {@code first} param
     * and the extras data in {@code other} intent is treated as the {@code last} param
     * when using the passed in {@link BundleMerger} object.
     *
     * @hide
     */
    public void mergeExtras(@NonNull Intent other, @NonNull BundleMerger extrasMerger) {
        mExtras = extrasMerger.merge(mExtras, other.mExtras);
    }

    /**
     * Wrapper class holding an Intent and implementing comparisons on it for
     * the purpose of filtering.  The class implements its
     * {@link #equals equals()} and {@link #hashCode hashCode()} methods as
     * simple calls to {@link Intent#filterEquals(Intent)}  filterEquals()} and
     * {@link android.content.Intent#filterHashCode()}  filterHashCode()}
     * on the wrapped Intent.
     */
    public static final class FilterComparison {
        private final Intent mIntent;
        private final int mHashCode;

        public FilterComparison(Intent intent) {
            mIntent = intent;
            mHashCode = intent.filterHashCode();
        }

        /**
         * Return the Intent that this FilterComparison represents.
         * @return Returns the Intent held by the FilterComparison.  Do
         * not modify!
         */
        public Intent getIntent() {
            return mIntent;
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (obj instanceof FilterComparison) {
                Intent other = ((FilterComparison)obj).mIntent;
                return mIntent.filterEquals(other);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return mHashCode;
        }
    }

    /**
     * Determine if two intents are the same for the purposes of intent
     * resolution (filtering). That is, if their action, data, type, identity,
     * class, and categories are the same.  This does <em>not</em> compare
     * any extra data included in the intents.  Note that technically when actually
     * matching against an {@link IntentFilter} the identifier is ignored, while here
     * it is directly compared for equality like the other fields.
     *
     * @param other The other Intent to compare against.
     *
     * @return Returns true if action, data, type, class, and categories
     *         are the same.
     */
    public boolean filterEquals(Intent other) {
        if (other == null) {
            return false;
        }
        if (!Objects.equals(this.mAction, other.mAction)) return false;
        if (!Objects.equals(this.mData, other.mData)) return false;
        if (!Objects.equals(this.mType, other.mType)) return false;
        if (!Objects.equals(this.mIdentifier, other.mIdentifier)) return false;
        if (!Objects.equals(this.mPackage, other.mPackage)) return false;
        if (!Objects.equals(this.mComponent, other.mComponent)) return false;
        if (!Objects.equals(this.mCategories, other.mCategories)) return false;

        return true;
    }

    /**
     * Generate hash code that matches semantics of filterEquals().
     *
     * @return Returns the hash value of the action, data, type, class, and
     *         categories.
     *
     * @see #filterEquals
     */
    public int filterHashCode() {
        int code = 0;
        if (mAction != null) {
            code += mAction.hashCode();
        }
        if (mData != null) {
            code += mData.hashCode();
        }
        if (mType != null) {
            code += mType.hashCode();
        }
        if (mIdentifier != null) {
            code += mIdentifier.hashCode();
        }
        if (mPackage != null) {
            code += mPackage.hashCode();
        }
        if (mComponent != null) {
            code += mComponent.hashCode();
        }
        if (mCategories != null) {
            code += mCategories.hashCode();
        }
        return code;
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder(128);
        toString(b);
        return b.toString();
    }

    /** @hide */
    public void toString(@NonNull StringBuilder b) {
        b.append("Intent { ");
        toShortString(b, true, true, true, false);
        b.append(" }");
    }

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public String toInsecureString() {
        StringBuilder b = new StringBuilder(128);

        b.append("Intent { ");
        toShortString(b, false, true, true, false);
        b.append(" }");

        return b.toString();
    }

    /** @hide */
    public String toShortString(boolean secure, boolean comp, boolean extras, boolean clip) {
        StringBuilder b = new StringBuilder(128);
        toShortString(b, secure, comp, extras, clip);
        return b.toString();
    }

    /** @hide */
    public void toShortString(StringBuilder b, boolean secure, boolean comp, boolean extras,
            boolean clip) {
        boolean first = true;
        if (mAction != null) {
            b.append("act=").append(mAction);
            first = false;
        }
        if (mCategories != null) {
            if (!first) {
                b.append(' ');
            }
            first = false;
            b.append("cat=[");
            for (int i=0; i<mCategories.size(); i++) {
                if (i > 0) b.append(',');
                b.append(mCategories.valueAt(i));
            }
            b.append("]");
        }
        if (mData != null) {
            if (!first) {
                b.append(' ');
            }
            first = false;
            b.append("dat=");
            if (secure) {
                b.append(mData.toSafeString());
            } else {
                b.append(mData);
            }
        }
        if (mType != null) {
            if (!first) {
                b.append(' ');
            }
            first = false;
            b.append("typ=").append(mType);
        }
        if (mIdentifier != null) {
            if (!first) {
                b.append(' ');
            }
            first = false;
            b.append("id=").append(mIdentifier);
        }
        if (mFlags != 0) {
            if (!first) {
                b.append(' ');
            }
            first = false;
            b.append("flg=0x").append(Integer.toHexString(mFlags));
        }
        if (mPackage != null) {
            if (!first) {
                b.append(' ');
            }
            first = false;
            b.append("pkg=").append(mPackage);
        }
        if (comp && mComponent != null) {
            if (!first) {
                b.append(' ');
            }
            first = false;
            b.append("cmp=").append(mComponent.flattenToShortString());
        }
        if (mSourceBounds != null) {
            if (!first) {
                b.append(' ');
            }
            first = false;
            b.append("bnds=").append(mSourceBounds.toShortString());
        }
        if (mClipData != null) {
            if (!first) {
                b.append(' ');
            }
            b.append("clip={");
            mClipData.toShortString(b, !clip || secure);
            first = false;
            b.append('}');
        }
        if (extras && mExtras != null) {
            if (!first) {
                b.append(' ');
            }
            first = false;
            b.append("(has extras)");
        }
        if (mContentUserHint != UserHandle.USER_CURRENT) {
            if (!first) {
                b.append(' ');
            }
            first = false;
            b.append("u=").append(mContentUserHint);
        }
        if (mSelector != null) {
            b.append(" sel=");
            mSelector.toShortString(b, secure, comp, extras, clip);
            b.append("}");
        }
        if (mOriginalIntent != null) {
            b.append(" org={");
            mOriginalIntent.toShortString(b, secure, comp, extras, clip);
            b.append("}");
        }
    }

    /** @hide */
    public void dumpDebug(ProtoOutputStream proto, long fieldId) {
        // Same input parameters that toString() gives to toShortString().
        dumpDebug(proto, fieldId, true, true, true, false);
    }

    /** @hide */
    public void dumpDebug(ProtoOutputStream proto) {
        // Same input parameters that toString() gives to toShortString().
        dumpDebugWithoutFieldId(proto, true, true, true, false);
    }

    /** @hide */
    public void dumpDebug(ProtoOutputStream proto, long fieldId, boolean secure, boolean comp,
            boolean extras, boolean clip) {
        long token = proto.start(fieldId);
        dumpDebugWithoutFieldId(proto, secure, comp, extras, clip);
        proto.end(token);
    }

    private void dumpDebugWithoutFieldId(ProtoOutputStream proto, boolean secure, boolean comp,
            boolean extras, boolean clip) {
        if (mAction != null) {
            proto.write(IntentProto.ACTION, mAction);
        }
        if (mCategories != null)  {
            for (String category : mCategories) {
                proto.write(IntentProto.CATEGORIES, category);
            }
        }
        if (mData != null) {
            proto.write(IntentProto.DATA, secure ? mData.toSafeString() : mData.toString());
        }
        if (mType != null) {
            proto.write(IntentProto.TYPE, mType);
        }
        if (mIdentifier != null) {
            proto.write(IntentProto.IDENTIFIER, mIdentifier);
        }
        if (mFlags != 0) {
            proto.write(IntentProto.FLAG, "0x" + Integer.toHexString(mFlags));
        }
        if (mPackage != null) {
            proto.write(IntentProto.PACKAGE, mPackage);
        }
        if (comp && mComponent != null) {
            mComponent.dumpDebug(proto, IntentProto.COMPONENT);
        }
        if (mSourceBounds != null) {
            proto.write(IntentProto.SOURCE_BOUNDS, mSourceBounds.toShortString());
        }
        if (mClipData != null) {
            StringBuilder b = new StringBuilder();
            mClipData.toShortString(b, !clip || secure);
            proto.write(IntentProto.CLIP_DATA, b.toString());
        }
        if (extras && mExtras != null) {
            proto.write(IntentProto.EXTRAS, mExtras.toShortString());
        }
        if (mContentUserHint != 0) {
            proto.write(IntentProto.CONTENT_USER_HINT, mContentUserHint);
        }
        if (mSelector != null) {
            proto.write(IntentProto.SELECTOR, mSelector.toShortString(secure, comp, extras, clip));
        }
    }

    /**
     * Call {@link #toUri} with 0 flags.
     * @deprecated Use {@link #toUri} instead.
     */
    @Deprecated
    public String toURI() {
        return toUri(0);
    }

    /**
     * Convert this Intent into a String holding a URI representation of it.
     * The returned URI string has been properly URI encoded, so it can be
     * used with {@link Uri#parse Uri.parse(String)}.  The URI contains the
     * Intent's data as the base URI, with an additional fragment describing
     * the action, categories, type, flags, package, component, and extras.
     *
     * <p>You can convert the returned string back to an Intent with
     * {@link #getIntent}.
     *
     * @param flags Additional operating flags.
     *
     * @return Returns a URI encoding URI string describing the entire contents
     * of the Intent.
     */
    public String toUri(@UriFlags int flags) {
        StringBuilder uri = new StringBuilder(128);
        if ((flags&URI_ANDROID_APP_SCHEME) != 0) {
            if (mPackage == null) {
                throw new IllegalArgumentException(
                        "Intent must include an explicit package name to build an android-app: "
                        + this);
            }
            uri.append("android-app://");
            uri.append(mPackage);
            String scheme = null;
            if (mData != null) {
                scheme = mData.getScheme();
                if (scheme != null) {
                    uri.append('/');
                    uri.append(scheme);
                    String authority = mData.getEncodedAuthority();
                    if (authority != null) {
                        uri.append('/');
                        uri.append(authority);
                        String path = mData.getEncodedPath();
                        if (path != null) {
                            uri.append(path);
                        }
                        String queryParams = mData.getEncodedQuery();
                        if (queryParams != null) {
                            uri.append('?');
                            uri.append(queryParams);
                        }
                        String fragment = mData.getEncodedFragment();
                        if (fragment != null) {
                            uri.append('#');
                            uri.append(fragment);
                        }
                    }
                }
            }
            toUriFragment(uri, null, scheme == null ? Intent.ACTION_MAIN : Intent.ACTION_VIEW,
                    mPackage, flags);
            return uri.toString();
        }
        String scheme = null;
        if (mData != null) {
            String data = mData.toString();
            if ((flags&URI_INTENT_SCHEME) != 0) {
                final int N = data.length();
                for (int i=0; i<N; i++) {
                    char c = data.charAt(i);
                    if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                            || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '+') {
                        continue;
                    }
                    if (c == ':' && i > 0) {
                        // Valid scheme.
                        scheme = data.substring(0, i);
                        uri.append("intent:");
                        data = data.substring(i+1);
                        break;
                    }

                    // No scheme.
                    break;
                }
            }
            uri.append(data);

        } else if ((flags&URI_INTENT_SCHEME) != 0) {
            uri.append("intent:");
        }

        toUriFragment(uri, scheme, Intent.ACTION_VIEW, null, flags);

        return uri.toString();
    }

    private void toUriFragment(StringBuilder uri, String scheme, String defAction,
            String defPackage, int flags) {
        StringBuilder frag = new StringBuilder(128);

        toUriInner(frag, scheme, defAction, defPackage, flags);
        if (mSelector != null) {
            frag.append("SEL;");
            // Note that for now we are not going to try to handle the
            // data part; not clear how to represent this as a URI, and
            // not much utility in it.
            mSelector.toUriInner(frag, mSelector.mData != null ? mSelector.mData.getScheme() : null,
                    null, null, flags);
        }

        if (frag.length() > 0) {
            uri.append("#Intent;");
            uri.append(frag);
            uri.append("end");
        }
    }

    private void toUriInner(StringBuilder uri, String scheme, String defAction,
            String defPackage, int flags) {
        if (scheme != null) {
            uri.append("scheme=").append(Uri.encode(scheme)).append(';');
        }
        if (mAction != null && !mAction.equals(defAction)) {
            uri.append("action=").append(Uri.encode(mAction)).append(';');
        }
        if (mCategories != null) {
            for (int i=0; i<mCategories.size(); i++) {
                uri.append("category=").append(Uri.encode(mCategories.valueAt(i))).append(';');
            }
        }
        if (mType != null) {
            uri.append("type=").append(Uri.encode(mType, "/")).append(';');
        }
        if (mIdentifier != null) {
            uri.append("identifier=").append(Uri.encode(mIdentifier, "/")).append(';');
        }
        if (mFlags != 0) {
            uri.append("launchFlags=0x").append(Integer.toHexString(mFlags)).append(';');
        }
        if (mPackage != null && !mPackage.equals(defPackage)) {
            uri.append("package=").append(Uri.encode(mPackage)).append(';');
        }
        if (mComponent != null) {
            uri.append("component=").append(Uri.encode(
                    mComponent.flattenToShortString(), "/")).append(';');
        }
        if (mSourceBounds != null) {
            uri.append("sourceBounds=")
                    .append(Uri.encode(mSourceBounds.flattenToString()))
                    .append(';');
        }
        if (mExtras != null) {
            for (String key : mExtras.keySet()) {
                final Object value = mExtras.get(key);
                char entryType =
                        value instanceof String    ? 'S' :
                        value instanceof Boolean   ? 'B' :
                        value instanceof Byte      ? 'b' :
                        value instanceof Character ? 'c' :
                        value instanceof Double    ? 'd' :
                        value instanceof Float     ? 'f' :
                        value instanceof Integer   ? 'i' :
                        value instanceof Long      ? 'l' :
                        value instanceof Short     ? 's' :
                        '\0';

                if (entryType != '\0') {
                    uri.append(entryType);
                    uri.append('.');
                    uri.append(Uri.encode(key));
                    uri.append('=');
                    uri.append(Uri.encode(value.toString()));
                    uri.append(';');
                }
            }
        }
    }

    public int describeContents() {
        return (mExtras != null) ? mExtras.describeContents() : 0;
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeString8(mAction);
        Uri.writeToParcel(out, mData);
        out.writeString8(mType);
        out.writeString8(mIdentifier);
        out.writeInt(mFlags);
        out.writeString8(mPackage);
        ComponentName.writeToParcel(mComponent, out);

        if (mSourceBounds != null) {
            out.writeInt(1);
            mSourceBounds.writeToParcel(out, flags);
        } else {
            out.writeInt(0);
        }

        if (mCategories != null) {
            final int N = mCategories.size();
            out.writeInt(N);
            for (int i=0; i<N; i++) {
                out.writeString8(mCategories.valueAt(i));
            }
        } else {
            out.writeInt(0);
        }

        if (mSelector != null) {
            out.writeInt(1);
            mSelector.writeToParcel(out, flags);
        } else {
            out.writeInt(0);
        }

        if (mClipData != null) {
            out.writeInt(1);
            mClipData.writeToParcel(out, flags);
        } else {
            out.writeInt(0);
        }
        out.writeInt(mContentUserHint);
        out.writeBundle(mExtras);

        if (mOriginalIntent != null) {
            out.writeInt(1);
            mOriginalIntent.writeToParcel(out, flags);
        } else {
            out.writeInt(0);
        }
    }

    public static final @android.annotation.NonNull Parcelable.Creator<Intent> CREATOR
            = new Parcelable.Creator<Intent>() {
        public Intent createFromParcel(Parcel in) {
            return new Intent(in);
        }
        public Intent[] newArray(int size) {
            return new Intent[size];
        }
    };

    /** @hide */
    protected Intent(Parcel in) {
        // Remember that we came from a remote process to help detect security
        // issues caused by later unsafe launches
        mLocalFlags = LOCAL_FLAG_FROM_PARCEL;
        readFromParcel(in);
    }

    public void readFromParcel(Parcel in) {
        setAction(in.readString8());
        mData = Uri.CREATOR.createFromParcel(in);
        mType = in.readString8();
        mIdentifier = in.readString8();
        mFlags = in.readInt();
        mPackage = in.readString8();
        mComponent = ComponentName.readFromParcel(in);

        if (in.readInt() != 0) {
            mSourceBounds = Rect.CREATOR.createFromParcel(in);
        }

        int N = in.readInt();
        if (N > 0) {
            mCategories = new ArraySet<String>();
            int i;
            for (i=0; i<N; i++) {
                mCategories.add(in.readString8().intern());
            }
        } else {
            mCategories = null;
        }

        if (in.readInt() != 0) {
            mSelector = new Intent(in);
        }

        if (in.readInt() != 0) {
            mClipData = new ClipData(in);
        }
        mContentUserHint = in.readInt();
        mExtras = in.readBundle();
        if (in.readInt() != 0) {
            mOriginalIntent = new Intent(in);
        }
    }

    /**
     * Parses the "intent" element (and its children) from XML and instantiates
     * an Intent object.  The given XML parser should be located at the tag
     * where parsing should start (often named "intent"), from which the
     * basic action, data, type, and package and class name will be
     * retrieved.  The function will then parse in to any child elements,
     * looking for <category android:name="xxx"> tags to add categories and
     * <extra android:name="xxx" android:value="yyy"> to attach extra data
     * to the intent.
     *
     * @param resources The Resources to use when inflating resources.
     * @param parser The XML parser pointing at an "intent" tag.
     * @param attrs The AttributeSet interface for retrieving extended
     * attribute data at the current <var>parser</var> location.
     * @return An Intent object matching the XML data.
     * @throws XmlPullParserException If there was an XML parsing error.
     * @throws IOException If there was an I/O error.
     */
    public static @NonNull Intent parseIntent(@NonNull Resources resources,
            @NonNull XmlPullParser parser, AttributeSet attrs)
            throws XmlPullParserException, IOException {
        Intent intent = new Intent();

        TypedArray sa = resources.obtainAttributes(attrs,
                com.android.internal.R.styleable.Intent);

        intent.setAction(sa.getString(com.android.internal.R.styleable.Intent_action));

        String data = sa.getString(com.android.internal.R.styleable.Intent_data);
        String mimeType = sa.getString(com.android.internal.R.styleable.Intent_mimeType);
        intent.setDataAndType(data != null ? Uri.parse(data) : null, mimeType);

        intent.setIdentifier(sa.getString(com.android.internal.R.styleable.Intent_identifier));

        String packageName = sa.getString(com.android.internal.R.styleable.Intent_targetPackage);
        String className = sa.getString(com.android.internal.R.styleable.Intent_targetClass);
        if (packageName != null && className != null) {
            intent.setComponent(new ComponentName(packageName, className));
        }

        sa.recycle();

        int outerDepth = parser.getDepth();
        int type;
        while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
               && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String nodeName = parser.getName();
            if (nodeName.equals(TAG_CATEGORIES)) {
                sa = resources.obtainAttributes(attrs,
                        com.android.internal.R.styleable.IntentCategory);
                String cat = sa.getString(com.android.internal.R.styleable.IntentCategory_name);
                sa.recycle();

                if (cat != null) {
                    intent.addCategory(cat);
                }
                XmlUtils.skipCurrentTag(parser);

            } else if (nodeName.equals(TAG_EXTRA)) {
                if (intent.mExtras == null) {
                    intent.mExtras = new Bundle();
                }
                resources.parseBundleExtra(TAG_EXTRA, attrs, intent.mExtras);
                XmlUtils.skipCurrentTag(parser);

            } else {
                XmlUtils.skipCurrentTag(parser);
            }
        }

        return intent;
    }

    /** @hide */
    public void saveToXml(XmlSerializer out) throws IOException {
        if (mAction != null) {
            out.attribute(null, ATTR_ACTION, mAction);
        }
        if (mData != null) {
            out.attribute(null, ATTR_DATA, mData.toString());
        }
        if (mType != null) {
            out.attribute(null, ATTR_TYPE, mType);
        }
        if (mIdentifier != null) {
            out.attribute(null, ATTR_IDENTIFIER, mIdentifier);
        }
        if (mComponent != null) {
            out.attribute(null, ATTR_COMPONENT, mComponent.flattenToShortString());
        }
        out.attribute(null, ATTR_FLAGS, Integer.toHexString(getFlags()));

        if (mCategories != null) {
            out.startTag(null, TAG_CATEGORIES);
            for (int categoryNdx = mCategories.size() - 1; categoryNdx >= 0; --categoryNdx) {
                out.attribute(null, ATTR_CATEGORY, mCategories.valueAt(categoryNdx));
            }
            out.endTag(null, TAG_CATEGORIES);
        }
    }

    /** @hide */
    public static Intent restoreFromXml(XmlPullParser in) throws IOException,
            XmlPullParserException {
        Intent intent = new Intent();
        final int outerDepth = in.getDepth();

        int attrCount = in.getAttributeCount();
        for (int attrNdx = attrCount - 1; attrNdx >= 0; --attrNdx) {
            final String attrName = in.getAttributeName(attrNdx);
            final String attrValue = in.getAttributeValue(attrNdx);
            if (ATTR_ACTION.equals(attrName)) {
                intent.setAction(attrValue);
            } else if (ATTR_DATA.equals(attrName)) {
                intent.setData(Uri.parse(attrValue));
            } else if (ATTR_TYPE.equals(attrName)) {
                intent.setType(attrValue);
            } else if (ATTR_IDENTIFIER.equals(attrName)) {
                intent.setIdentifier(attrValue);
            } else if (ATTR_COMPONENT.equals(attrName)) {
                intent.setComponent(ComponentName.unflattenFromString(attrValue));
            } else if (ATTR_FLAGS.equals(attrName)) {
                intent.setFlags(Integer.parseInt(attrValue, 16));
            } else {
                Log.e(TAG, "restoreFromXml: unknown attribute=" + attrName);
            }
        }

        int event;
        String name;
        while (((event = in.next()) != XmlPullParser.END_DOCUMENT) &&
                (event != XmlPullParser.END_TAG || in.getDepth() < outerDepth)) {
            if (event == XmlPullParser.START_TAG) {
                name = in.getName();
                if (TAG_CATEGORIES.equals(name)) {
                    attrCount = in.getAttributeCount();
                    for (int attrNdx = attrCount - 1; attrNdx >= 0; --attrNdx) {
                        intent.addCategory(in.getAttributeValue(attrNdx));
                    }
                } else {
                    Log.w(TAG, "restoreFromXml: unknown name=" + name);
                    XmlUtils.skipCurrentTag(in);
                }
            }
        }

        return intent;
    }

    /**
     * Normalize a MIME data type.
     *
     * <p>A normalized MIME type has white-space trimmed,
     * content-type parameters removed, and is lower-case.
     * This aligns the type with Android best practices for
     * intent filtering.
     *
     * <p>For example, "text/plain; charset=utf-8" becomes "text/plain".
     * "text/x-vCard" becomes "text/x-vcard".
     *
     * <p>All MIME types received from outside Android (such as user input,
     * or external sources like Bluetooth, NFC, or the Internet) should
     * be normalized before they are used to create an Intent.
     *
     * @param type MIME data type to normalize
     * @return normalized MIME data type, or null if the input was null
     * @see #setType
     * @see #setTypeAndNormalize
     */
    public static @Nullable String normalizeMimeType(@Nullable String type) {
        if (type == null) {
            return null;
        }

        type = type.trim().toLowerCase(Locale.ROOT);

        final int semicolonIndex = type.indexOf(';');
        if (semicolonIndex != -1) {
            type = type.substring(0, semicolonIndex);
        }
        return type;
    }

    /**
     * Prepare this {@link Intent} to leave an app process.
     *
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void prepareToLeaveProcess(Context context) {
        final boolean leavingPackage;
        if (mComponent != null) {
            leavingPackage = !Objects.equals(mComponent.getPackageName(), context.getPackageName());
        } else if (mPackage != null) {
            leavingPackage = !Objects.equals(mPackage, context.getPackageName());
        } else {
            // When no specific component or package has been defined, we have
            // to assume that we might be routed through an intent
            // disambiguation dialog which might leave our package
            leavingPackage = true;
        }
        prepareToLeaveProcess(leavingPackage);
    }

    /**
     * Prepare this {@link Intent} to leave an app process.
     *
     * @hide
     */
    public void prepareToLeaveProcess(boolean leavingPackage) {
        setAllowFds(false);

        if (mSelector != null) {
            mSelector.prepareToLeaveProcess(leavingPackage);
        }
        if (mClipData != null) {
            mClipData.prepareToLeaveProcess(leavingPackage, getFlags());
        }
        if (mOriginalIntent != null) {
            mOriginalIntent.prepareToLeaveProcess(leavingPackage);
        }

        if (mExtras != null && !mExtras.isParcelled()) {
            final Object intent = mExtras.get(Intent.EXTRA_INTENT);
            if (intent instanceof Intent) {
                ((Intent) intent).prepareToLeaveProcess(leavingPackage);
            }
        }

        if (mAction != null && mData != null && StrictMode.vmFileUriExposureEnabled()
                && leavingPackage) {
            switch (mAction) {
                case ACTION_MEDIA_REMOVED:
                case ACTION_MEDIA_UNMOUNTED:
                case ACTION_MEDIA_CHECKING:
                case ACTION_MEDIA_NOFS:
                case ACTION_MEDIA_MOUNTED:
                case ACTION_MEDIA_SHARED:
                case ACTION_MEDIA_UNSHARED:
                case ACTION_MEDIA_BAD_REMOVAL:
                case ACTION_MEDIA_UNMOUNTABLE:
                case ACTION_MEDIA_EJECT:
                case ACTION_MEDIA_SCANNER_STARTED:
                case ACTION_MEDIA_SCANNER_FINISHED:
                case ACTION_MEDIA_SCANNER_SCAN_FILE:
                case ACTION_PACKAGE_NEEDS_VERIFICATION:
                case ACTION_PACKAGE_NEEDS_INTEGRITY_VERIFICATION:
                case ACTION_PACKAGE_VERIFIED:
                case ACTION_PACKAGE_ENABLE_ROLLBACK:
                    // Ignore legacy actions
                    break;
                default:
                    mData.checkFileUriExposed("Intent.getData()");
            }
        }

        if (mAction != null && mData != null && StrictMode.vmContentUriWithoutPermissionEnabled()
                && leavingPackage) {
            switch (mAction) {
                case ACTION_PROVIDER_CHANGED:
                case QuickContact.ACTION_QUICK_CONTACT:
                    // Ignore actions that don't need to grant
                    break;
                default:
                    mData.checkContentUriWithoutPermission("Intent.getData()", getFlags());
            }
        }

        // Translate raw filesystem paths out of storage sandbox
        if (ACTION_MEDIA_SCANNER_SCAN_FILE.equals(mAction) && mData != null
                && ContentResolver.SCHEME_FILE.equals(mData.getScheme()) && leavingPackage) {
            final StorageManager sm = AppGlobals.getInitialApplication()
                    .getSystemService(StorageManager.class);
            final File before = new File(mData.getPath());
            final File after = sm.translateAppToSystem(before,
                    android.os.Process.myPid(), android.os.Process.myUid());
            if (!Objects.equals(before, after)) {
                Log.v(TAG, "Translated " + before + " to " + after);
                mData = Uri.fromFile(after);
            }
        }

        // Detect cases where we're about to launch a potentially unsafe intent
        if (StrictMode.vmUnsafeIntentLaunchEnabled()) {
            if ((mLocalFlags & LOCAL_FLAG_FROM_PARCEL) != 0
                    && (mLocalFlags
                    & (LOCAL_FLAG_FROM_PROTECTED_COMPONENT | LOCAL_FLAG_FROM_SYSTEM)) == 0) {
                StrictMode.onUnsafeIntentLaunch(this);
            } else if ((mLocalFlags & LOCAL_FLAG_UNFILTERED_EXTRAS) != 0) {
                StrictMode.onUnsafeIntentLaunch(this);
            } else if ((mLocalFlags & LOCAL_FLAG_FROM_URI) != 0
                    && !(mCategories != null && mCategories.contains(CATEGORY_BROWSABLE)
                    && mComponent == null)) {
                // Since the docs for #URI_ALLOW_UNSAFE recommend setting the category to browsable
                // for an implicit Intent parsed from a URI a violation should be reported if these
                // conditions are not met.
                StrictMode.onUnsafeIntentLaunch(this);
            }
        }
    }

    /**
     * @hide
     */
    public void prepareToEnterProcess(boolean fromProtectedComponent, AttributionSource source) {
        if (fromProtectedComponent) {
            prepareToEnterProcess(LOCAL_FLAG_FROM_PROTECTED_COMPONENT, source);
        } else {
            prepareToEnterProcess(0, source);
        }
    }

    /**
     * @hide
     */
    public void prepareToEnterProcess(int localFlags, AttributionSource source) {
        // We just entered destination process, so we should be able to read all
        // parcelables inside.
        setDefusable(true);

        if (mSelector != null) {
            // We can't recursively claim that this data is from a protected
            // component, since it may have been filled in by a malicious app
            mSelector.prepareToEnterProcess(0, source);
        }
        if (mClipData != null) {
            mClipData.prepareToEnterProcess(source);
        }
        if (mOriginalIntent != null) {
            // We can't recursively claim that this data is from a protected
            // component, since it may have been filled in by a malicious app
            mOriginalIntent.prepareToEnterProcess(0, source);
        }

        if (mContentUserHint != UserHandle.USER_CURRENT) {
            if (UserHandle.getAppId(Process.myUid()) != Process.SYSTEM_UID) {
                fixUris(mContentUserHint);
                mContentUserHint = UserHandle.USER_CURRENT;
            }
        }

        mLocalFlags |= localFlags;

        // Special attribution fix-up logic for any BluetoothDevice extras
        // passed via Bluetooth intents
        if (mAction != null && mAction.startsWith("android.bluetooth.")
                && hasExtra(BluetoothDevice.EXTRA_DEVICE)) {
            final BluetoothDevice device = getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
            if (device != null) {
                device.prepareToEnterProcess(source);
            }
        }
    }

    /** @hide */
    public boolean hasWebURI() {
        if (getData() == null) {
            return false;
        }
        final String scheme = getScheme();
        if (TextUtils.isEmpty(scheme)) {
            return false;
        }
        return scheme.equals(IntentFilter.SCHEME_HTTP) || scheme.equals(IntentFilter.SCHEME_HTTPS);
    }

    /** @hide */
    public boolean isWebIntent() {
        return ACTION_VIEW.equals(mAction)
                && hasWebURI();
    }

    private boolean isImageCaptureIntent() {
        return (MediaStore.ACTION_IMAGE_CAPTURE.equals(mAction)
                || MediaStore.ACTION_IMAGE_CAPTURE_SECURE.equals(mAction)
                || MediaStore.ACTION_VIDEO_CAPTURE.equals(mAction));
    }

    /** @hide */
    public boolean isImplicitImageCaptureIntent() {
        return mPackage == null && mComponent == null && isImageCaptureIntent();
    }

    /**
     * @hide
     */
     public void fixUris(int contentUserHint) {
        Uri data = getData();
        if (data != null) {
            mData = maybeAddUserId(data, contentUserHint);
        }
        if (mClipData != null) {
            mClipData.fixUris(contentUserHint);
        }
        String action = getAction();
        if (ACTION_SEND.equals(action)) {
            final Uri stream = getParcelableExtra(EXTRA_STREAM, Uri.class);
            if (stream != null) {
                putExtra(EXTRA_STREAM, maybeAddUserId(stream, contentUserHint));
            }
        } else if (ACTION_SEND_MULTIPLE.equals(action)) {
            final ArrayList<Uri> streams = getParcelableArrayListExtra(EXTRA_STREAM, Uri.class);
            if (streams != null) {
                ArrayList<Uri> newStreams = new ArrayList<Uri>();
                for (int i = 0; i < streams.size(); i++) {
                    newStreams.add(maybeAddUserId(streams.get(i), contentUserHint));
                }
                putParcelableArrayListExtra(EXTRA_STREAM, newStreams);
            }
        } else if (isImageCaptureIntent()) {
            final Uri output = getParcelableExtra(MediaStore.EXTRA_OUTPUT, Uri.class);
            if (output != null) {
                putExtra(MediaStore.EXTRA_OUTPUT, maybeAddUserId(output, contentUserHint));
            }
        }
     }

    /**
     * Migrate any {@link #EXTRA_STREAM} in {@link #ACTION_SEND} and
     * {@link #ACTION_SEND_MULTIPLE} to {@link ClipData}. Also inspects nested
     * intents in {@link #ACTION_CHOOSER}.
     *
     * @return Whether any contents were migrated.
     * @hide
     */
    public boolean migrateExtraStreamToClipData() {
        return migrateExtraStreamToClipData(AppGlobals.getInitialApplication());
    }

    /**
     * Migrate any {@link #EXTRA_STREAM} in {@link #ACTION_SEND} and
     * {@link #ACTION_SEND_MULTIPLE} to {@link ClipData}. Also inspects nested
     * intents in {@link #ACTION_CHOOSER}.
     *
     * @param context app context
     * @return Whether any contents were migrated.
     * @hide
     */
    public boolean migrateExtraStreamToClipData(Context context) {
        // Refuse to touch if extras already parcelled
        if (mExtras != null && mExtras.isParcelled()) return false;

        // Bail when someone already gave us ClipData
        if (getClipData() != null) return false;

        final String action = getAction();
        if (ACTION_CHOOSER.equals(action)) {
            // Inspect contained intents to see if we need to migrate extras. We
            // don't promote ClipData to the parent, since ChooserActivity will
            // already start the picked item as the caller, and we can't combine
            // the flags in a safe way.

            boolean migrated = false;
            try {
                final Intent intent = getParcelableExtra(EXTRA_INTENT, Intent.class);
                if (intent != null) {
                    migrated |= intent.migrateExtraStreamToClipData(context);
                }
            } catch (ClassCastException e) {
            }
            try {
                final Parcelable[] intents = getParcelableArrayExtra(EXTRA_INITIAL_INTENTS);
                if (intents != null) {
                    for (int i = 0; i < intents.length; i++) {
                        final Intent intent = (Intent) intents[i];
                        if (intent != null) {
                            migrated |= intent.migrateExtraStreamToClipData(context);
                        }
                    }
                }
            } catch (ClassCastException e) {
            }
            return migrated;

        } else if (ACTION_SEND.equals(action)) {
            try {
                final Uri stream = getParcelableExtra(EXTRA_STREAM, Uri.class);
                final CharSequence text = getCharSequenceExtra(EXTRA_TEXT);
                final String htmlText = getStringExtra(EXTRA_HTML_TEXT);
                if (stream != null || text != null || htmlText != null) {
                    final ClipData clipData = new ClipData(
                            null, new String[] { getType() },
                            new ClipData.Item(text, htmlText, null, stream));
                    setClipData(clipData);
                    if (stream != null) {
                        addFlags(FLAG_GRANT_READ_URI_PERMISSION);
                    }
                    return true;
                }
            } catch (ClassCastException e) {
            }

        } else if (ACTION_SEND_MULTIPLE.equals(action)) {
            try {
                final ArrayList<Uri> streams = getParcelableArrayListExtra(EXTRA_STREAM, Uri.class);
                final ArrayList<CharSequence> texts = getCharSequenceArrayListExtra(EXTRA_TEXT);
                final ArrayList<String> htmlTexts = getStringArrayListExtra(EXTRA_HTML_TEXT);
                int num = -1;
                if (streams != null) {
                    num = streams.size();
                }
                if (texts != null) {
                    if (num >= 0 && num != texts.size()) {
                        // Wha...!  F- you.
                        return false;
                    }
                    num = texts.size();
                }
                if (htmlTexts != null) {
                    if (num >= 0 && num != htmlTexts.size()) {
                        // Wha...!  F- you.
                        return false;
                    }
                    num = htmlTexts.size();
                }
                if (num > 0) {
                    final ClipData clipData = new ClipData(
                            null, new String[] { getType() },
                            makeClipItem(streams, texts, htmlTexts, 0));

                    for (int i = 1; i < num; i++) {
                        clipData.addItem(makeClipItem(streams, texts, htmlTexts, i));
                    }

                    setClipData(clipData);
                    if (streams != null) {
                        addFlags(FLAG_GRANT_READ_URI_PERMISSION);
                    }
                    return true;
                }
            } catch (ClassCastException e) {
            }
        } else if (isImageCaptureIntent()) {
            Uri output;
            try {
                output = getParcelableExtra(MediaStore.EXTRA_OUTPUT, Uri.class);
            } catch (ClassCastException e) {
                return false;
            }

            if (output != null) {
                output = maybeConvertFileToContentUri(context, output);
                putExtra(MediaStore.EXTRA_OUTPUT, output);

                setClipData(ClipData.newRawUri("", output));
                addFlags(FLAG_GRANT_WRITE_URI_PERMISSION|FLAG_GRANT_READ_URI_PERMISSION);
                return true;
            }
        }

        return false;
    }

    private Uri maybeConvertFileToContentUri(Context context, Uri uri) {
        if (ContentResolver.SCHEME_FILE.equals(uri.getScheme())
                && context.getApplicationInfo().targetSdkVersion < Build.VERSION_CODES.R) {
            File file = new File(uri.getPath());
            try {
                if (!file.exists()) file.createNewFile();
                uri = MediaStore.scanFile(context.getContentResolver(), new File(uri.getPath()));
                if (uri != null) {
                    return uri;
                }
            } catch (IOException e) {
                Log.e(TAG, "Ignoring failure to create file " + file, e);
            }
        }
        return uri;
    }

    /**
     * Convert the dock state to a human readable format.
     * @hide
     */
    public static String dockStateToString(int dock) {
        switch (dock) {
            case EXTRA_DOCK_STATE_HE_DESK:
                return "EXTRA_DOCK_STATE_HE_DESK";
            case EXTRA_DOCK_STATE_LE_DESK:
                return "EXTRA_DOCK_STATE_LE_DESK";
            case EXTRA_DOCK_STATE_CAR:
                return "EXTRA_DOCK_STATE_CAR";
            case EXTRA_DOCK_STATE_DESK:
                return "EXTRA_DOCK_STATE_DESK";
            case EXTRA_DOCK_STATE_UNDOCKED:
                return "EXTRA_DOCK_STATE_UNDOCKED";
            default:
                return Integer.toString(dock);
        }
    }

    private static ClipData.Item makeClipItem(ArrayList<Uri> streams, ArrayList<CharSequence> texts,
            ArrayList<String> htmlTexts, int which) {
        Uri uri = streams != null ? streams.get(which) : null;
        CharSequence text = texts != null ? texts.get(which) : null;
        String htmlText = htmlTexts != null ? htmlTexts.get(which) : null;
        return new ClipData.Item(text, htmlText, null, uri);
    }

    /** @hide */
    public boolean isDocument() {
        return (mFlags & FLAG_ACTIVITY_NEW_DOCUMENT) == FLAG_ACTIVITY_NEW_DOCUMENT;
    }

    // TODO(b/299109198): Refactor into the {@link SdkSandboxManagerLocal}
    /** @hide */
    public boolean isSandboxActivity(@NonNull Context context) {
        if (mAction != null && mAction.equals(ACTION_START_SANDBOXED_ACTIVITY)) {
            return true;
        }
        final String sandboxPackageName = context.getPackageManager().getSdkSandboxPackageName();
        if (mPackage != null && mPackage.equals(sandboxPackageName)) {
            return true;
        }
        if (mComponent != null && mComponent.getPackageName().equals(sandboxPackageName)) {
            return true;
        }
        return false;
    }
}
