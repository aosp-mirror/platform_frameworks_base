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

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.util.Log;

import com.android.internal.util.XmlUtils;

import java.io.IOException;
import java.io.Serializable;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

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
 *     VIEW action does what what is considered the most reasonable thing for
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
 *     new intent { <b>{@link #ACTION_VIEW} <i>content://contacts/N</i></b> }
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
 * These strings use java style scoping, to ensure they are unique -- for
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
 * allows user to browse through a list of notes data and view details about
 * individual items.  Text in italics indicate places were you would replace a
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
 *     &lt;data mimeType:name="vnd.android.cursor.dir/<i>vnd.google.note</i>" /&gt;
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
 * <p>This filter describes the ability return to the caller a note selected by
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
 *     <li> {@link #ACTION_UID_REMOVED}
 *     <li> {@link #ACTION_BATTERY_CHANGED}
 *     <li> {@link #ACTION_POWER_CONNECTED}
 *     <li> {@link #ACTION_POWER_DISCONNECTED}
 *     <li> {@link #ACTION_SHUTDOWN}
 * </ul>
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
 *     <li> {@link #EXTRA_PHONE_NUMBER}
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
 * </ul>
 *
 * <h3>Flags</h3>
 *
 * <p>These are the possible flags that can be used in the Intent via
 * {@link #setFlags} and {@link #addFlags}.  See {@link #setFlags} for a list
 * of all possible flags.
 */
public class Intent implements Parcelable, Cloneable {
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
     * A synonym for {@link #ACTION_VIEW}, the "standard" action that is
     * performed on a piece of data.
     */
    public static final String ACTION_DEFAULT = ACTION_VIEW;

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
     * Activity Action: Creates a shortcut.
     * <p>Input: Nothing.</p>
     * <p>Output: An Intent representing the shortcut. The intent must contain three
     * extras: SHORTCUT_INTENT (value: Intent), SHORTCUT_NAME (value: String),
     * and SHORTCUT_ICON (value: Bitmap) or SHORTCUT_ICON_RESOURCE
     * (value: ShortcutIconResource).</p>
     *
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
     */
    public static final String EXTRA_SHORTCUT_INTENT = "android.intent.extra.shortcut.INTENT";
    /**
     * The name of the extra used to define the name of a shortcut.
     *
     * @see #ACTION_CREATE_SHORTCUT
     */
    public static final String EXTRA_SHORTCUT_NAME = "android.intent.extra.shortcut.NAME";
    /**
     * The name of the extra used to define the icon, as a Bitmap, of a shortcut.
     *
     * @see #ACTION_CREATE_SHORTCUT
     */
    public static final String EXTRA_SHORTCUT_ICON = "android.intent.extra.shortcut.ICON";
    /**
     * The name of the extra used to define the icon, as a ShortcutIconResource, of a shortcut.
     *
     * @see #ACTION_CREATE_SHORTCUT
     * @see android.content.Intent.ShortcutIconResource
     */
    public static final String EXTRA_SHORTCUT_ICON_RESOURCE =
            "android.intent.extra.shortcut.ICON_RESOURCE";

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
         * @param resourceId The resource idenfitier for the icon.
         * @return A new ShortcutIconResource with the specified's context package name
         *         and icon resource idenfitier.
         */
        public static ShortcutIconResource fromContext(Context context, int resourceId) {
            ShortcutIconResource icon = new ShortcutIconResource();
            icon.packageName = context.getPackageName();
            icon.resourceName = context.getResources().getResourceName(resourceId);
            return icon;
        }

        /**
         * Used to read a ShortcutIconResource from a Parcel.
         */
        public static final Parcelable.Creator<ShortcutIconResource> CREATOR =
            new Parcelable.Creator<ShortcutIconResource>() {

                public ShortcutIconResource createFromParcel(Parcel source) {
                    ShortcutIconResource icon = new ShortcutIconResource();
                    icon.packageName = source.readString();
                    icon.resourceName = source.readString();
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
            dest.writeString(packageName);
            dest.writeString(resourceName);
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
     * As a convenience, an Intent of this form can be created with the
     * {@link #createChooser} function.
     * <p>Input: No data should be specified.  get*Extra must have
     * a {@link #EXTRA_INTENT} field containing the Intent being executed,
     * and can optionally have a {@link #EXTRA_TITLE} field containing the
     * title text to display in the chooser.
     * <p>Output: Depends on the protocol of {@link #EXTRA_INTENT}.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_CHOOSER = "android.intent.action.CHOOSER";

    /**
     * Convenience function for creating a {@link #ACTION_CHOOSER} Intent.
     *
     * @param target The Intent that the user will be selecting an activity
     * to perform.
     * @param title Optional title that will be displayed in the chooser.
     * @return Return a new Intent object that you can hand to
     * {@link Context#startActivity(Intent) Context.startActivity()} and
     * related methods.
     */
    public static Intent createChooser(Intent target, CharSequence title) {
        Intent intent = new Intent(ACTION_CHOOSER);
        intent.putExtra(EXTRA_INTENT, target);
        if (title != null) {
            intent.putExtra(EXTRA_TITLE, title);
        }
        return intent;
    }
    /**
     * Activity Action: Allow the user to select a particular kind of data and
     * return it.  This is different than {@link #ACTION_PICK} in that here we
     * just say what kind of data is desired, not a URI of existing data from
     * which the user can pick.  A ACTION_GET_CONTENT could allow the user to
     * create the data as it runs (for example taking a picture or recording a
     * sound), let them browser over the web and download the desired data,
     * etc.
     * <p>
     * There are two main ways to use this action: if you want an specific kind
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
     * When using such a broad GET_CONTENT action, it is often desireable to
     * only pick from data that can be represented as a stream.  This is
     * accomplished by requiring the {@link #CATEGORY_OPENABLE} in the Intent.
     * <p>
     * Callers can optionally specify {@link #EXTRA_LOCAL_ONLY} to request that
     * the launched content chooser only return results representing data that
     * is locally available on the device.  For example, if this extra is set
     * to true then an image picker should not show any pictures that are available
     * from a remote server but not already on the local device (thus requiring
     * they be downloaded when opened).
     * <p>
     * Input: {@link #getType} is the desired MIME type to retrieve.  Note
     * that no URI is supplied in the intent, as there are no constraints on
     * where the returned data originally comes from.  You may also include the
     * {@link #CATEGORY_OPENABLE} if you can only accept data that can be
     * opened as a stream.  You may use {@link #EXTRA_LOCAL_ONLY} to limit content
     * selection to local data.
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
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_CALL = "android.intent.action.CALL";
    /**
     * Activity Action: Perform a call to an emergency number specified by the
     * data.
     * <p>Input: {@link #getData} is URI of a phone number to be dialed or a
     * tel: URI of an explicit phone number.
     * <p>Output: nothing.
     * @hide
     */
    public static final String ACTION_CALL_EMERGENCY = "android.intent.action.CALL_EMERGENCY";
    /**
     * Activity action: Perform a call to any number (emergency or not)
     * specified by the data.
     * <p>Input: {@link #getData} is URI of a phone number to be dialed or a
     * tel: URI of an explicit phone number.
     * <p>Output: nothing.
     * @hide
     */
    public static final String ACTION_CALL_PRIVILEGED = "android.intent.action.CALL_PRIVILEGED";
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
     * handle generic data streams).
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
     * Like ACTION_SEND, except the data is multiple.
     * <p>
     * Input: {@link #getType} is the MIME type of the data being sent.
     * get*ArrayListExtra can have either a {@link #EXTRA_TEXT} or {@link
     * #EXTRA_STREAM} field, containing the data to be sent.
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
     * Activity Action: List all available applications
     * <p>Input: Nothing.
     * <p>Output: nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_ALL_APPS = "android.intent.action.ALL_APPS";
    /**
     * Activity Action: Show settings for choosing wallpaper
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
     * the Market.
     * <p>Input: No data is specified. The bug report is passed in using
     * an {@link #EXTRA_BUG_REPORT} field.
     * <p>Output: Nothing.
     *
     * @see #EXTRA_BUG_REPORT
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_APP_ERROR = "android.intent.action.APP_ERROR";

    /**
     * Activity Action: Show power usage information to the user.
     * <p>Input: Nothing.
     * <p>Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_POWER_USAGE_SUMMARY = "android.intent.action.POWER_USAGE_SUMMARY";

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
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_UPGRADE_SETUP = "android.intent.action.UPGRADE_SETUP";

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
     * Input: The data must be a content: or file: URI at which the application
     * can be retrieved.  You can optionally supply
     * {@link #EXTRA_INSTALLER_PACKAGE_NAME}, {@link #EXTRA_NOT_UNKNOWN_SOURCE},
     * {@link #EXTRA_ALLOW_REPLACE}, and {@link #EXTRA_RETURN_RESULT}.
     * <p>
     * Output: If {@link #EXTRA_RETURN_RESULT}, returns whether the install
     * succeeded.
     *
     * @see #EXTRA_INSTALLER_PACKAGE_NAME
     * @see #EXTRA_NOT_UNKNOWN_SOURCE
     * @see #EXTRA_RETURN_RESULT
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_INSTALL_PACKAGE = "android.intent.action.INSTALL_PACKAGE";

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
     * Used as a boolean extra field with {@link #ACTION_INSTALL_PACKAGE} to install a
     * package.  Tells the installer UI to skip the confirmation with the user
     * if the .apk is replacing an existing one.
     */
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
    public static final String EXTRA_INSTALL_RESULT
            = "android.intent.extra.INSTALL_RESULT";

    /**
     * Activity Action: Launch application uninstaller.
     * <p>
     * Input: The data must be a package: URI whose scheme specific part is
     * the package name of the current installed package to be uninstalled.
     * You can optionally supply {@link #EXTRA_RETURN_RESULT}.
     * <p>
     * Output: If {@link #EXTRA_RETURN_RESULT}, returns whether the install
     * succeeded.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_UNINSTALL_PACKAGE = "android.intent.action.UNINSTALL_PACKAGE";

    /**
     * A string associated with a {@link #ACTION_UPGRADE_SETUP} activity
     * describing the last run version of the platform that was setup.
     * @hide
     */
    public static final String METADATA_SETUP_VERSION = "android.SETUP_VERSION";

    // ---------------------------------------------------------------------
    // ---------------------------------------------------------------------
    // Standard intent broadcast actions (see action variable).

    /**
     * Broadcast Action: Sent after the screen turns off.
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_SCREEN_OFF = "android.intent.action.SCREEN_OFF";
    /**
     * Broadcast Action: Sent after the screen turns on.
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_SCREEN_ON = "android.intent.action.SCREEN_ON";

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
     * minute.  You can <em>not</em> receive this through components declared
     * in manifests, only by exlicitly registering for it with
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
     *   <li><em>time-zone</em> - The java.util.TimeZone.getID() value identifying the new time zone.</li>
     * </ul>
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_TIMEZONE_CHANGED = "android.intent.action.TIMEZONE_CHANGED";
    /**
     * Clear DNS Cache Action: This is broadcast when networks have changed and old
     * DNS entries should be tossed.
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_CLEAR_DNS_CACHE = "android.intent.action.CLEAR_DNS_CACHE";
    /**
     * Alarm Changed Action: This is broadcast when the AlarmClock
     * application's alarm is set or unset.  It is used by the
     * AlarmClock application and the StatusBar service.
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_ALARM_CHANGED = "android.intent.action.ALARM_CHANGED";
    /**
     * Sync State Changed Action: This is broadcast when the sync starts or stops or when one has
     * been failing for a long time.  It is used by the SyncManager and the StatusBar service.
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_SYNC_STATE_CHANGED
            = "android.intent.action.SYNC_STATE_CHANGED";
    /**
     * Broadcast Action: This is broadcast once, after the system has finished
     * booting.  It can be used to perform application-specific initialization,
     * such as installing alarms.  You must hold the
     * {@link android.Manifest.permission#RECEIVE_BOOT_COMPLETED} permission
     * in order to receive this broadcast.
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED";
    /**
     * Broadcast Action: This is broadcast when a user action should request a
     * temporary system dialog to dismiss.  Some examples of temporary system
     * dialogs are the notification window-shade and the recent tasks dialog.
     */
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
     * <p>My include the following extras:
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
     * <p>My include the following extras:
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
     * that is being installed does <em>not</em> receive this Intent.
     * <ul>
     * <li> {@link #EXTRA_UID} containing the integer uid previously assigned
     * to the package.
     * <li> {@link #EXTRA_DATA_REMOVED} is set to true if the entire
     * application -- data and code -- is being removed.
     * <li> {@link #EXTRA_REPLACING} is set to true if this will be followed
     * by an {@link #ACTION_PACKAGE_ADDED} broadcast for the same package.
     * </ul>
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PACKAGE_REMOVED = "android.intent.action.PACKAGE_REMOVED";
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
     * Broadcast Action: An existing application package has been changed (e.g.
     * a component has been enabled or disabled).  The data contains the name of
     * the package.
     * <ul>
     * <li> {@link #EXTRA_UID} containing the integer uid assigned to the package.
     * <li> {@link #EXTRA_CHANGED_COMPONENT_NAME_LIST} containing the class name
     * of the changed components.
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
     * <li> {@link #EXTRA_UID} containing the integer uid assigned to the package.
     * </ul>
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PACKAGE_DATA_CLEARED = "android.intent.action.PACKAGE_DATA_CLEARED";
    /**
     * Broadcast Action: A user ID has been removed from the system.  The user
     * ID number is stored in the extra data under {@link #EXTRA_UID}.
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_UID_REMOVED = "android.intent.action.UID_REMOVED";

    /**
     * Broadcast Action: Sent to the installer package of an application
     * when that application is first launched (that is the first time it
     * is moved out of the stopped state).  The data contains the name of the package.
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
     * Broadcast Action:  The current system wallpaper has changed.  See
     * {@link android.app.WallpaperManager} for retrieving the new wallpaper.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
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
     * You can <em>not</em> receive this through components declared
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
     * Broadcast Action: The current device's locale has changed.
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_LOCALE_CHANGED = "android.intent.action.LOCALE_CHANGED";
    /**
     * Broadcast Action:  This is a <em>sticky broadcast</em> containing the
     * charging state, level, and other information about the battery.
     * See {@link android.os.BatteryManager} for documentation on the
     * contents of the Intent.
     *
     * <p class="note">
     * You can <em>not</em> receive this through components declared
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
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_SHUTDOWN = "android.intent.action.ACTION_SHUTDOWN";
    /**
     * Activity Action:  Start this activity to request system shutdown.
     * The optional boolean extra field {@link #EXTRA_KEY_CONFIRM} can be set to true
     * to request confirmation from the user before shutting down.
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     *
     * {@hide}
     */
    public static final String ACTION_REQUEST_SHUTDOWN = "android.intent.action.ACTION_REQUEST_SHUTDOWN";
    /**
     * Broadcast Action:  A sticky broadcast that indicates low memory
     * condition on the device
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_DEVICE_STORAGE_LOW = "android.intent.action.DEVICE_STORAGE_LOW";
    /**
     * Broadcast Action:  Indicates low memory condition on the device no longer exists
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_DEVICE_STORAGE_OK = "android.intent.action.DEVICE_STORAGE_OK";
    /**
     * Broadcast Action:  A sticky broadcast that indicates a memory full
     * condition on the device. This is intended for activities that want
     * to be able to fill the data partition completely, leaving only
     * enough free space to prevent system-wide SQLite failures.
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     *
     * {@hide}
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_DEVICE_STORAGE_FULL = "android.intent.action.DEVICE_STORAGE_FULL";
    /**
     * Broadcast Action:  Indicates memory full condition on the device
     * no longer exists.
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     *
     * {@hide}
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
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
    public static final String ACTION_UMS_CONNECTED = "android.intent.action.UMS_CONNECTED";

    /**
     * Broadcast Action:  The device has exited USB Mass Storage mode.
     * This is used mainly for the USB Settings panel.
     * Apps should listen for ACTION_MEDIA_MOUNTED and ACTION_MEDIA_UNMOUNTED broadcasts to be notified
     * when the SD card file system is mounted or unmounted
     * @deprecated replaced by android.os.storage.StorageEventListener
     */
    @Deprecated
    public static final String ACTION_UMS_DISCONNECTED = "android.intent.action.UMS_DISCONNECTED";

    /**
     * Broadcast Action:  External media has been removed.
     * The path to the mount point for the removed media is contained in the Intent.mData field.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_MEDIA_REMOVED = "android.intent.action.MEDIA_REMOVED";

    /**
     * Broadcast Action:  External media is present, but not mounted at its mount point.
     * The path to the mount point for the removed media is contained in the Intent.mData field.
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
     * The path to the mount point for the removed media is contained in the Intent.mData field.
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
     * The path to the mount point for the removed media is contained in the Intent.mData field.
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
     * Broadcast Action:  Request the media scanner to scan a file and add it to the media database.
     * The path to the file is contained in the Intent.mData field.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
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
     * Broadcast Action: An GTalk connection has been established.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_GTALK_SERVICE_CONNECTED =
            "android.intent.action.GTALK_CONNECTED";

    /**
     * Broadcast Action: An GTalk connection has been disconnected.
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
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
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
     * <p>The intent will have the following extra values:
     * <ul>
     *   <li><em>state</em> - 0 for unplugged, 1 for plugged. </li>
     *   <li><em>name</em> - Headset type, human readable string </li>
     *   <li><em>microphone</em> - 1 if headset has a microphone, 0 otherwise </li>
     * </ul>
     * </ul>
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_HEADSET_PLUG =
            "android.intent.action.HEADSET_PLUG";

    /**
     * Broadcast Action: An analog audio speaker/headset plugged in or unplugged.
     *
     * <p>The intent will have the following extra values:
     * <ul>
     *   <li><em>state</em> - 0 for unplugged, 1 for plugged. </li>
     *   <li><em>name</em> - Headset type, human readable string </li>
     * </ul>
     * </ul>
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_USB_ANLG_HEADSET_PLUG =
            "android.intent.action.USB_ANLG_HEADSET_PLUG";

    /**
     * Broadcast Action: A digital audio speaker/headset plugged in or unplugged.
     *
     * <p>The intent will have the following extra values:
     * <ul>
     *   <li><em>state</em> - 0 for unplugged, 1 for plugged. </li>
     *   <li><em>name</em> - Headset type, human readable string </li>
     * </ul>
     * </ul>
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_USB_DGTL_HEADSET_PLUG =
            "android.intent.action.USB_DGTL_HEADSET_PLUG";

    /**
     * Broadcast Action: A HMDI cable was plugged or unplugged
     *
     * <p>The intent will have the following extra values:
     * <ul>
     *   <li><em>state</em> - 0 for unplugged, 1 for plugged. </li>
     *   <li><em>name</em> - HDMI cable, human readable string </li>
     * </ul>
     * </ul>
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_HDMI_AUDIO_PLUG =
            "android.intent.action.HDMI_AUDIO_PLUG";

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
     * Broadcast Action: An outgoing call is about to be placed.
     *
     * <p>The Intent will have the following extra value:
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
     * <p>You must hold the
     * {@link android.Manifest.permission#PROCESS_OUTGOING_CALLS}
     * permission to receive this Intent.</p>
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
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
     * Broadcast Action: hook for permforming cleanup after a system update.
     *
     * The broadcast is sent when the system is booting, before the
     * BOOT_COMPLETED broadcast.  It is only sent to receivers in the system
     * image.  A receiver for this should do its work and then disable itself
     * so that it does not get run again at the next boot.
     * @hide
     */
    public static final String ACTION_PRE_BOOT_COMPLETED =
            "android.intent.action.PRE_BOOT_COMPLETED";

    // ---------------------------------------------------------------------
    // ---------------------------------------------------------------------
    // Standard intent categories (see addCategory()).

    /**
     * Set if the activity should be an option for the default action
     * (center press) to perform on a piece of data.  Setting this will
     * hide from the user any activities without it set when performing an
     * action on some data.  Note that this is normal -not- set in the
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
     * Intended to be used as a tab inside of an containing TabActivity.
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_TAB = "android.intent.category.TAB";
    /**
     * Should be displayed in the top-level launcher.
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER";
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
     * To be used as an sample code example (not part of the normal user
     * experience).
     */
    public static final String CATEGORY_SAMPLE_CODE = "android.intent.category.SAMPLE_CODE";
    /**
     * Used to indicate that a GET_CONTENT intent only wants URIs that can be opened with
     * ContentResolver.openInputStream. Openable URIs must support the columns in OpenableColumns
     * when queried, though it is allowable for those columns to be blank.
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_OPENABLE = "android.intent.category.OPENABLE";

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
     * An activity to run when device is inserted into a car dock.
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
     * {@link #ACTION_PICK_ACTIVITY}.
     */
    public static final String EXTRA_INTENT = "android.intent.extra.INTENT";

    /**
     * A CharSequence dialog title to provide to the user when used with a
     * {@link #ACTION_CHOOSER}.
     */
    public static final String EXTRA_TITLE = "android.intent.extra.TITLE";

    /**
     * A Parcelable[] of {@link Intent} or
     * {@link android.content.pm.LabeledIntent} objects as set with
     * {@link #putExtra(String, Parcelable[])} of additional activities to place
     * a the front of the list of choices, when shown to the user with a
     * {@link #ACTION_CHOOSER}.
     */
    public static final String EXTRA_INITIAL_INTENTS = "android.intent.extra.INITIAL_INTENTS";

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
     * Used as an boolean extra field in {@link android.content.Intent#ACTION_PACKAGE_REMOVED} or
     * {@link android.content.Intent#ACTION_PACKAGE_CHANGED} intents to override the default action
     * of restarting the application.
     */
    public static final String EXTRA_DONT_KILL_APP = "android.intent.extra.DONT_KILL_APP";

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
     * @hide String array of package names.
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
     * Used as a boolean extra field in {@link android.content.Intent#ACTION_PACKAGE_REMOVED}
     * intents to indicate that this is a replacement of the package, so this
     * broadcast will immediately be followed by an add broadcast for a
     * different version of the same package.
     */
    public static final String EXTRA_REPLACING = "android.intent.extra.REPLACING";

    /**
     * Used as an int extra field in {@link android.app.AlarmManager} intents
     * to tell the application being invoked how many pending alarms are being
     * delievered with the intent.  For one-shot alarms this will always be 1.
     * For recurring alarms, this might be greater than 1 if the device was
     * asleep or powered off at the time an earlier alarm would have been
     * delivered.
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
     * Used in the extra field in the remote intent. It's astring token passed with the
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
     * and contains a string array of all of the components that have changed.
     */
    public static final String EXTRA_CHANGED_COMPONENT_NAME_LIST =
            "android.intent.extra.changed_component_name_list";

    /**
     * This field is part of
     * {@link android.content.Intent#ACTION_EXTERNAL_APPLICATIONS_AVAILABLE},
     * {@link android.content.Intent#ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE}
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
     * Used to indicate that a {@link #ACTION_GET_CONTENT} intent should only return
     * data that is on the local device.  This is a boolean extra; the default
     * is false.  If true, an implementation of ACTION_GET_CONTENT should only allow
     * the user to select media that is already on the device, not requiring it
     * be downloaded from a remote service when opened.  Another way to look
     * at it is that such content should generally have a "_data" column to the
     * path of the content on local external storage.
     */
    public static final String EXTRA_LOCAL_ONLY =
        "android.intent.extra.LOCAL_ONLY";

    // ---------------------------------------------------------------------
    // ---------------------------------------------------------------------
    // Intent flags (see mFlags variable).

    /**
     * If set, the recipient of this Intent will be granted permission to
     * perform read operations on the Uri in the Intent's data.
     */
    public static final int FLAG_GRANT_READ_URI_PERMISSION = 0x00000001;
    /**
     * If set, the recipient of this Intent will be granted permission to
     * perform write operations on the Uri in the Intent's data.
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
     * If set, the new activity is not kept in the history stack.  As soon as
     * the user navigates away from it, the activity is finished.  This may also
     * be set with the {@link android.R.styleable#AndroidManifestActivity_noHistory
     * noHistory} attribute.
     */
    public static final int FLAG_ACTIVITY_NO_HISTORY = 0x40000000;
    /**
     * If set, the activity will not be launched if it is already running
     * at the top of the history stack.
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
     * <strong>Do not use this flag unless you are implementing your own
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
     * <p>This flag is ignored if
     * {@link #FLAG_ACTIVITY_NEW_TASK} is not set.
     *
     * <p>See
     * <a href="{@docRoot}guide/topics/fundamentals/tasks-and-back-stack.html">Tasks and Back
     * Stack</a> for more information about tasks.
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
     * transfered to the new activity.  This way the new activity can call
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
     */
    public static final int FLAG_ACTIVITY_RESET_TASK_IF_NEEDED = 0x00200000;
    /**
     * This flag is not normally set by application code, but set for you by
     * the system if this activity is being launched from history
     * (longpress home key).
     */
    public static final int FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY = 0x00100000;
    /**
     * If set, this marks a point in the task's activity stack that should
     * be cleared when the task is reset.  That is, the next time the task
     * is brought to the foreground with
     * {@link #FLAG_ACTIVITY_RESET_TASK_IF_NEEDED} (typically as a result of
     * the user re-launching it from home), this activity and all on top of
     * it will be finished so that the user does not return to them, but
     * instead returns to whatever activity preceeded it.
     *
     * <p>This is useful for cases where you have a logical break in your
     * application.  For example, an e-mail application may have a command
     * to view an attachment, which launches an image view activity to
     * display it.  This activity should be part of the e-mail application's
     * task, since it is a part of the task the user is involved in.  However,
     * if the user leaves that task, and later selects the e-mail app from
     * home, we may like them to return to the conversation they were
     * viewing, not the picture attachment, since that is confusing.  By
     * setting this flag when launching the image viewer, that viewer and
     * any activities it starts will be removed the next time the user returns
     * to mail.
     */
    public static final int FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET = 0x00080000;
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
     * If set, when sending a broadcast <i>before boot has completed</i> only
     * registered receivers will be called -- no BroadcastReceiver components
     * will be launched.  Sticky intent state will be recorded properly even
     * if no receivers wind up being called.  If {@link #FLAG_RECEIVER_REGISTERED_ONLY}
     * is specified in the broadcast intent, this flag is unnecessary.
     *
     * <p>This flag is only for use by system sevices as a convenience to
     * avoid having to implement a more complex mechanism around detection
     * of boot completion.
     *
     * @hide
     */
    public static final int FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT = 0x10000000;
    /**
     * Set when this broadcast is for a boot upgrade, a special mode that
     * allows the broadcast to be sent before the system is ready and launches
     * the app process with no providers running in it.
     * @hide
     */
    public static final int FLAG_RECEIVER_BOOT_UPGRADE = 0x08000000;

    /**
     * @hide Flags that can't be changed with PendingIntent.
     */
    public static final int IMMUTABLE_FLAGS =
            FLAG_GRANT_READ_URI_PERMISSION
            | FLAG_GRANT_WRITE_URI_PERMISSION;

    // ---------------------------------------------------------------------
    // ---------------------------------------------------------------------
    // toUri() and parseUri() options.

    /**
     * Flag for use with {@link #toUri} and {@link #parseUri}: the URI string
     * always has the "intent:" scheme.  This syntax can be used when you want
     * to later disambiguate between URIs that are intended to describe an
     * Intent vs. all others that should be treated as raw URIs.  When used
     * with {@link #parseUri}, any other scheme will result in a generic
     * VIEW action for that raw URI.
     */
    public static final int URI_INTENT_SCHEME = 1<<0;

    // ---------------------------------------------------------------------

    private String mAction;
    private Uri mData;
    private String mType;
    private String mPackage;
    private ComponentName mComponent;
    private int mFlags;
    private HashSet<String> mCategories;
    private Bundle mExtras;
    private Rect mSourceBounds;
    private Intent mSelector;

    // ---------------------------------------------------------------------

    /**
     * Create an empty intent.
     */
    public Intent() {
    }

    /**
     * Copy constructor.
     */
    public Intent(Intent o) {
        this.mAction = o.mAction;
        this.mData = o.mData;
        this.mType = o.mType;
        this.mPackage = o.mPackage;
        this.mComponent = o.mComponent;
        this.mFlags = o.mFlags;
        if (o.mCategories != null) {
            this.mCategories = new HashSet<String>(o.mCategories);
        }
        if (o.mExtras != null) {
            this.mExtras = new Bundle(o.mExtras);
        }
        if (o.mSourceBounds != null) {
            this.mSourceBounds = new Rect(o.mSourceBounds);
        }
        if (o.mSelector != null) {
            this.mSelector = new Intent(o.mSelector);
        }
    }

    @Override
    public Object clone() {
        return new Intent(this);
    }

    private Intent(Intent o, boolean all) {
        this.mAction = o.mAction;
        this.mData = o.mData;
        this.mType = o.mType;
        this.mPackage = o.mPackage;
        this.mComponent = o.mComponent;
        if (o.mCategories != null) {
            this.mCategories = new HashSet<String>(o.mCategories);
        }
    }

    /**
     * Make a clone of only the parts of the Intent that are relevant for
     * filter matching: the action, data, type, component, and categories.
     */
    public Intent cloneFilter() {
        return new Intent(this, false);
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
     * This is equivalent using {@link #Intent(String, android.net.Uri)} to
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
     * @param flags Additional processing flags.  Either 0 or
     * {@link #URI_INTENT_SCHEME}.
     *
     * @return Intent The newly created Intent object.
     *
     * @throws URISyntaxException Throws URISyntaxError if the basic URI syntax
     * it bad (as parsed by the Uri class) or the Intent data within the
     * URI is invalid.
     *
     * @see #toUri
     */
    public static Intent parseUri(String uri, int flags) throws URISyntaxException {
        int i = 0;
        try {
            // Validate intent scheme for if requested.
            if ((flags&URI_INTENT_SCHEME) != 0) {
                if (!uri.startsWith("intent:")) {
                    Intent intent = new Intent(ACTION_VIEW);
                    try {
                        intent.setData(Uri.parse(uri));
                    } catch (IllegalArgumentException e) {
                        throw new URISyntaxException(uri, e.getMessage());
                    }
                    return intent;
                }
            }

            // simple case
            i = uri.lastIndexOf("#");
            if (i == -1) return new Intent(ACTION_VIEW, Uri.parse(uri));

            // old format Intent URI
            if (!uri.startsWith("#Intent;", i)) return getIntentOld(uri);

            // new format
            Intent intent = new Intent(ACTION_VIEW);
            Intent baseIntent = intent;

            // fetch data part, if present
            String data = i >= 0 ? uri.substring(0, i) : null;
            String scheme = null;
            i += "#Intent;".length();

            // loop over contents of Intent, all name=value;
            while (!uri.startsWith("end", i)) {
                int eq = uri.indexOf('=', i);
                if (eq < 0) eq = i-1;
                int semi = uri.indexOf(';', i);
                String value = eq < semi ? Uri.decode(uri.substring(eq + 1, semi)) : "";

                // action
                if (uri.startsWith("action=", i)) {
                    intent.setAction(value);
                }

                // categories
                else if (uri.startsWith("category=", i)) {
                    intent.addCategory(value);
                }

                // type
                else if (uri.startsWith("type=", i)) {
                    intent.mType = value;
                }

                // launch flags
                else if (uri.startsWith("launchFlags=", i)) {
                    intent.mFlags = Integer.decode(value).intValue();
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
                    scheme = value;
                }

                // source bounds
                else if (uri.startsWith("sourceBounds=", i)) {
                    intent.mSourceBounds = Rect.unflattenFromString(value);
                }

                // selector
                else if (semi == (i+3) && uri.startsWith("SEL", i)) {
                    intent = new Intent();
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

            if (intent != baseIntent) {
                // The Intent had a selector; fix it up.
                baseIntent.setSelector(intent);
                intent = baseIntent;
            }

            if (data != null) {
                if (data.startsWith("intent:")) {
                    data = data.substring(7);
                    if (scheme != null) {
                        data = scheme + ':' + data;
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
                    if (sep < 0) sep = j;
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
    public String getAction() {
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
    public Uri getData() {
        return mData;
    }

    /**
     * The same as {@link #getData()}, but returns the URI as an encoded
     * String.
     */
    public String getDataString() {
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
    public String getScheme() {
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
    public String getType() {
        return mType;
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
    public String resolveType(Context context) {
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
    public String resolveType(ContentResolver resolver) {
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
    public String resolveTypeIfNeeded(ContentResolver resolver) {
        if (mComponent != null) {
            return mType;
        }
        return resolveType(resolver);
    }

    /**
     * Check if an category exists in the intent.
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
    public Intent getSelector() {
        return mSelector;
    }

    /**
     * Sets the ClassLoader that will be used when unmarshalling
     * any Parcelable values from the extras of this Intent.
     *
     * @param loader a ClassLoader, or null to use the default loader
     * at the time of unmarshalling.
     */
    public void setExtrasClassLoader(ClassLoader loader) {
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

    /** @hide */
    public void setAllowFds(boolean allowFds) {
        if (mExtras != null) {
            mExtras.setAllowFds(allowFds);
        }
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     *
     * @return the value of an item that previously added with putExtra()
     * or null if none was found.
     *
     * @deprecated
     * @hide
     */
    @Deprecated
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
     * @return the value of an item that previously added with putExtra()
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
     * @return the value of an item that previously added with putExtra()
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
     * @return the value of an item that previously added with putExtra()
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
     * @return the value of an item that previously added with putExtra()
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
     * @return the value of an item that previously added with putExtra()
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
     * @return the value of an item that previously added with putExtra()
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
     * @return the value of an item that previously added with putExtra(),
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
     * @return the value of an item that previously added with putExtra()
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
     * @return the value of an item that previously added with putExtra()
     * or null if no String value was found.
     *
     * @see #putExtra(String, String)
     */
    public String getStringExtra(String name) {
        return mExtras == null ? null : mExtras.getString(name);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     *
     * @return the value of an item that previously added with putExtra()
     * or null if no CharSequence value was found.
     *
     * @see #putExtra(String, CharSequence)
     */
    public CharSequence getCharSequenceExtra(String name) {
        return mExtras == null ? null : mExtras.getCharSequence(name);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     *
     * @return the value of an item that previously added with putExtra()
     * or null if no Parcelable value was found.
     *
     * @see #putExtra(String, Parcelable)
     */
    public <T extends Parcelable> T getParcelableExtra(String name) {
        return mExtras == null ? null : mExtras.<T>getParcelable(name);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     *
     * @return the value of an item that previously added with putExtra()
     * or null if no Parcelable[] value was found.
     *
     * @see #putExtra(String, Parcelable[])
     */
    public Parcelable[] getParcelableArrayExtra(String name) {
        return mExtras == null ? null : mExtras.getParcelableArray(name);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     *
     * @return the value of an item that previously added with putExtra()
     * or null if no ArrayList<Parcelable> value was found.
     *
     * @see #putParcelableArrayListExtra(String, ArrayList)
     */
    public <T extends Parcelable> ArrayList<T> getParcelableArrayListExtra(String name) {
        return mExtras == null ? null : mExtras.<T>getParcelableArrayList(name);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     *
     * @return the value of an item that previously added with putExtra()
     * or null if no Serializable value was found.
     *
     * @see #putExtra(String, Serializable)
     */
    public Serializable getSerializableExtra(String name) {
        return mExtras == null ? null : mExtras.getSerializable(name);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     *
     * @return the value of an item that previously added with putExtra()
     * or null if no ArrayList<Integer> value was found.
     *
     * @see #putIntegerArrayListExtra(String, ArrayList)
     */
    public ArrayList<Integer> getIntegerArrayListExtra(String name) {
        return mExtras == null ? null : mExtras.getIntegerArrayList(name);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     *
     * @return the value of an item that previously added with putExtra()
     * or null if no ArrayList<String> value was found.
     *
     * @see #putStringArrayListExtra(String, ArrayList)
     */
    public ArrayList<String> getStringArrayListExtra(String name) {
        return mExtras == null ? null : mExtras.getStringArrayList(name);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     *
     * @return the value of an item that previously added with putExtra()
     * or null if no ArrayList<CharSequence> value was found.
     *
     * @see #putCharSequenceArrayListExtra(String, ArrayList)
     */
    public ArrayList<CharSequence> getCharSequenceArrayListExtra(String name) {
        return mExtras == null ? null : mExtras.getCharSequenceArrayList(name);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     *
     * @return the value of an item that previously added with putExtra()
     * or null if no boolean array value was found.
     *
     * @see #putExtra(String, boolean[])
     */
    public boolean[] getBooleanArrayExtra(String name) {
        return mExtras == null ? null : mExtras.getBooleanArray(name);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     *
     * @return the value of an item that previously added with putExtra()
     * or null if no byte array value was found.
     *
     * @see #putExtra(String, byte[])
     */
    public byte[] getByteArrayExtra(String name) {
        return mExtras == null ? null : mExtras.getByteArray(name);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     *
     * @return the value of an item that previously added with putExtra()
     * or null if no short array value was found.
     *
     * @see #putExtra(String, short[])
     */
    public short[] getShortArrayExtra(String name) {
        return mExtras == null ? null : mExtras.getShortArray(name);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     *
     * @return the value of an item that previously added with putExtra()
     * or null if no char array value was found.
     *
     * @see #putExtra(String, char[])
     */
    public char[] getCharArrayExtra(String name) {
        return mExtras == null ? null : mExtras.getCharArray(name);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     *
     * @return the value of an item that previously added with putExtra()
     * or null if no int array value was found.
     *
     * @see #putExtra(String, int[])
     */
    public int[] getIntArrayExtra(String name) {
        return mExtras == null ? null : mExtras.getIntArray(name);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     *
     * @return the value of an item that previously added with putExtra()
     * or null if no long array value was found.
     *
     * @see #putExtra(String, long[])
     */
    public long[] getLongArrayExtra(String name) {
        return mExtras == null ? null : mExtras.getLongArray(name);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     *
     * @return the value of an item that previously added with putExtra()
     * or null if no float array value was found.
     *
     * @see #putExtra(String, float[])
     */
    public float[] getFloatArrayExtra(String name) {
        return mExtras == null ? null : mExtras.getFloatArray(name);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     *
     * @return the value of an item that previously added with putExtra()
     * or null if no double array value was found.
     *
     * @see #putExtra(String, double[])
     */
    public double[] getDoubleArrayExtra(String name) {
        return mExtras == null ? null : mExtras.getDoubleArray(name);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     *
     * @return the value of an item that previously added with putExtra()
     * or null if no String array value was found.
     *
     * @see #putExtra(String, String[])
     */
    public String[] getStringArrayExtra(String name) {
        return mExtras == null ? null : mExtras.getStringArray(name);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     *
     * @return the value of an item that previously added with putExtra()
     * or null if no CharSequence array value was found.
     *
     * @see #putExtra(String, CharSequence[])
     */
    public CharSequence[] getCharSequenceArrayExtra(String name) {
        return mExtras == null ? null : mExtras.getCharSequenceArray(name);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     *
     * @return the value of an item that previously added with putExtra()
     * or null if no Bundle value was found.
     *
     * @see #putExtra(String, Bundle)
     */
    public Bundle getBundleExtra(String name) {
        return mExtras == null ? null : mExtras.getBundle(name);
    }

    /**
     * Retrieve extended data from the intent.
     *
     * @param name The name of the desired item.
     *
     * @return the value of an item that previously added with putExtra()
     * or null if no IBinder value was found.
     *
     * @see #putExtra(String, IBinder)
     *
     * @deprecated
     * @hide
     */
    @Deprecated
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
     * @return the value of an item that previously added with putExtra()
     * or defaultValue if none was found.
     *
     * @see #putExtra
     *
     * @deprecated
     * @hide
     */
    @Deprecated
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
    public Bundle getExtras() {
        return (mExtras != null)
                ? new Bundle(mExtras)
                : null;
    }

    /**
     * Retrieve any special flags associated with this intent.  You will
     * normally just set them with {@link #setFlags} and let the system
     * take the appropriate action with them.
     *
     * @return int The currently set flags.
     *
     * @see #setFlags
     */
    public int getFlags() {
        return mFlags;
    }

    /** @hide */
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
    public String getPackage() {
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
    public ComponentName getComponent() {
        return mComponent;
    }

    /**
     * Get the bounds of the sender of this intent, in screen coordinates.  This can be
     * used as a hint to the receiver for animations and the like.  Null means that there
     * is no source bounds.
     */
    public Rect getSourceBounds() {
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
    public ComponentName resolveActivity(PackageManager pm) {
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
    public ActivityInfo resolveActivityInfo(PackageManager pm, int flags) {
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
    public Intent setAction(String action) {
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
     * or use {@link Uri#normalize} or
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
     * @see android.net.Intent#normalize
     */
    public Intent setData(Uri data) {
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
     * {@link android.net.Uri#normalize} before it is set,
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
     * @see android.net.Uri#normalize
     */
    public Intent setDataAndNormalize(Uri data) {
        return setData(data.normalize());
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
    public Intent setType(String type) {
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
    public Intent setTypeAndNormalize(String type) {
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
     * or use {@link #normalizeMimeType} or {@link android.net.Uri#normalize} or
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
     * @see android.net.Uri#normalize
     * @see #setDataAndTypeAndNormalize
     */
    public Intent setDataAndType(Uri data, String type) {
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
     * {@link android.net.Uri#normalize} and {@link #normalizeMimeType}
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
     * @see android.net.Uri#normalize
     */
    public Intent setDataAndTypeAndNormalize(Uri data, String type) {
        return setDataAndType(data.normalize(), normalizeMimeType(type));
    }

    /**
     * Add a new category to the intent.  Categories provide additional detail
     * about the action the intent is perform.  When resolving an intent, only
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
    public Intent addCategory(String category) {
        if (mCategories == null) {
            mCategories = new HashSet<String>();
        }
        mCategories.add(category.intern());
        return this;
    }

    /**
     * Remove an category from an intent.
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
    public void setSelector(Intent selector) {
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
    public Intent putExtra(String name, boolean value) {
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
    public Intent putExtra(String name, byte value) {
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
    public Intent putExtra(String name, char value) {
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
    public Intent putExtra(String name, short value) {
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
    public Intent putExtra(String name, int value) {
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
    public Intent putExtra(String name, long value) {
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
    public Intent putExtra(String name, float value) {
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
    public Intent putExtra(String name, double value) {
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
    public Intent putExtra(String name, String value) {
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
    public Intent putExtra(String name, CharSequence value) {
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
    public Intent putExtra(String name, Parcelable value) {
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
    public Intent putExtra(String name, Parcelable[] value) {
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
    public Intent putParcelableArrayListExtra(String name, ArrayList<? extends Parcelable> value) {
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
    public Intent putIntegerArrayListExtra(String name, ArrayList<Integer> value) {
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
    public Intent putStringArrayListExtra(String name, ArrayList<String> value) {
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
    public Intent putCharSequenceArrayListExtra(String name, ArrayList<CharSequence> value) {
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
    public Intent putExtra(String name, Serializable value) {
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
    public Intent putExtra(String name, boolean[] value) {
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
    public Intent putExtra(String name, byte[] value) {
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
    public Intent putExtra(String name, short[] value) {
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
    public Intent putExtra(String name, char[] value) {
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
    public Intent putExtra(String name, int[] value) {
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
    public Intent putExtra(String name, long[] value) {
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
    public Intent putExtra(String name, float[] value) {
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
    public Intent putExtra(String name, double[] value) {
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
    public Intent putExtra(String name, String[] value) {
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
    public Intent putExtra(String name, CharSequence[] value) {
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
    public Intent putExtra(String name, Bundle value) {
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
    public Intent putExtra(String name, IBinder value) {
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
    public Intent putExtras(Intent src) {
        if (src.mExtras != null) {
            if (mExtras == null) {
                mExtras = new Bundle(src.mExtras);
            } else {
                mExtras.putAll(src.mExtras);
            }
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
    public Intent putExtras(Bundle extras) {
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
    public Intent replaceExtras(Intent src) {
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
    public Intent replaceExtras(Bundle extras) {
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
     *
     * @return Returns the same Intent object, for chaining multiple calls
     * into a single statement.
     *
     * @see #getFlags
     * @see #addFlags
     *
     * @see #FLAG_GRANT_READ_URI_PERMISSION
     * @see #FLAG_GRANT_WRITE_URI_PERMISSION
     * @see #FLAG_DEBUG_LOG_RESOLUTION
     * @see #FLAG_FROM_BACKGROUND
     * @see #FLAG_ACTIVITY_BROUGHT_TO_FRONT
     * @see #FLAG_ACTIVITY_CLEAR_TASK
     * @see #FLAG_ACTIVITY_CLEAR_TOP
     * @see #FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET
     * @see #FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
     * @see #FLAG_ACTIVITY_FORWARD_RESULT
     * @see #FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY
     * @see #FLAG_ACTIVITY_MULTIPLE_TASK
     * @see #FLAG_ACTIVITY_NEW_TASK
     * @see #FLAG_ACTIVITY_NO_ANIMATION
     * @see #FLAG_ACTIVITY_NO_HISTORY
     * @see #FLAG_ACTIVITY_NO_USER_ACTION
     * @see #FLAG_ACTIVITY_PREVIOUS_IS_TOP
     * @see #FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
     * @see #FLAG_ACTIVITY_REORDER_TO_FRONT
     * @see #FLAG_ACTIVITY_SINGLE_TOP
     * @see #FLAG_ACTIVITY_TASK_ON_HOME
     * @see #FLAG_RECEIVER_REGISTERED_ONLY
     */
    public Intent setFlags(int flags) {
        mFlags = flags;
        return this;
    }

    /**
     * Add additional flags to the intent (or with existing flags
     * value).
     *
     * @param flags The new flags to set.
     *
     * @return Returns the same Intent object, for chaining multiple calls
     * into a single statement.
     *
     * @see #setFlags
     */
    public Intent addFlags(int flags) {
        mFlags |= flags;
        return this;
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
    public Intent setPackage(String packageName) {
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
    public Intent setComponent(ComponentName component) {
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
    public Intent setClassName(Context packageContext, String className) {
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
    public Intent setClassName(String packageName, String className) {
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
    public Intent setClass(Context packageContext, Class<?> cls) {
        mComponent = new ComponentName(packageContext, cls);
        return this;
    }

    /**
     * Set the bounds of the sender of this intent, in screen coordinates.  This can be
     * used as a hint to the receiver for animations and the like.  Null means that there
     * is no source bounds.
     */
    public void setSourceBounds(Rect r) {
        if (r != null) {
            mSourceBounds = new Rect(r);
        } else {
            mSourceBounds = null;
        }
    }

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
     * Copy the contents of <var>other</var> in to this object, but only
     * where fields are not defined by this object.  For purposes of a field
     * being defined, the following pieces of data in the Intent are
     * considered to be separate fields:
     *
     * <ul>
     * <li> action, as set by {@link #setAction}.
     * <li> data Uri and MIME type, as set by {@link #setData(Uri)},
     * {@link #setType(String)}, or {@link #setDataAndType(Uri, String)}.
     * <li> categories, as set by {@link #addCategory}.
     * <li> package, as set by {@link #setPackage}.
     * <li> component, as set by {@link #setComponent(ComponentName)} or
     * related methods.
     * <li> source bounds, as set by {@link #setSourceBounds}
     * <li> each top-level name in the associated extras.
     * </ul>
     *
     * <p>In addition, you can use the {@link #FILL_IN_ACTION},
     * {@link #FILL_IN_DATA}, {@link #FILL_IN_CATEGORIES}, {@link #FILL_IN_PACKAGE},
     * {@link #FILL_IN_COMPONENT}, {@link #FILL_IN_SOURCE_BOUNDS}, and
     * {@link #FILL_IN_SELECTOR} to override the restriction where the
     * corresponding field will not be replaced if it is already set.
     *
     * <p>Note: The component field will only be copied if {@link #FILL_IN_COMPONENT} is explicitly
     * specified.  The selector will only be copied if {@link #FILL_IN_SELECTOR} is
     * explicitly specified.
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
     * {@link #FILL_IN_COMPONENT}, {@link #FILL_IN_SOURCE_BOUNDS}, and
     * {@link #FILL_IN_SELECTOR} indicating which fields were changed.
     */
    public int fillIn(Intent other, int flags) {
        int changes = 0;
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
        }
        if (other.mCategories != null
                && (mCategories == null || (flags&FILL_IN_CATEGORIES) != 0)) {
            if (other.mCategories != null) {
                mCategories = new HashSet<String>(other.mCategories);
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
            }
        } else if (other.mExtras != null) {
            try {
                Bundle newb = new Bundle(other.mExtras);
                newb.putAll(mExtras);
                mExtras = newb;
            } catch (RuntimeException e) {
                // Modifying the extras can cause us to unparcel the contents
                // of the bundle, and if we do this in the system process that
                // may fail.  We really should handle this (i.e., the Bundle
                // impl shouldn't be on top of a plain map), but for now just
                // ignore it and keep the original contents. :(
                Log.w("Intent", "Failure filling in extras", e);
            }
        }
        return changes;
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
        public boolean equals(Object obj) {
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
     * resolution (filtering). That is, if their action, data, type,
     * class, and categories are the same.  This does <em>not</em> compare
     * any extra data included in the intents.
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
        if (mAction != other.mAction) {
            if (mAction != null) {
                if (!mAction.equals(other.mAction)) {
                    return false;
                }
            } else {
                if (!other.mAction.equals(mAction)) {
                    return false;
                }
            }
        }
        if (mData != other.mData) {
            if (mData != null) {
                if (!mData.equals(other.mData)) {
                    return false;
                }
            } else {
                if (!other.mData.equals(mData)) {
                    return false;
                }
            }
        }
        if (mType != other.mType) {
            if (mType != null) {
                if (!mType.equals(other.mType)) {
                    return false;
                }
            } else {
                if (!other.mType.equals(mType)) {
                    return false;
                }
            }
        }
        if (mPackage != other.mPackage) {
            if (mPackage != null) {
                if (!mPackage.equals(other.mPackage)) {
                    return false;
                }
            } else {
                if (!other.mPackage.equals(mPackage)) {
                    return false;
                }
            }
        }
        if (mComponent != other.mComponent) {
            if (mComponent != null) {
                if (!mComponent.equals(other.mComponent)) {
                    return false;
                }
            } else {
                if (!other.mComponent.equals(mComponent)) {
                    return false;
                }
            }
        }
        if (mCategories != other.mCategories) {
            if (mCategories != null) {
                if (!mCategories.equals(other.mCategories)) {
                    return false;
                }
            } else {
                if (!other.mCategories.equals(mCategories)) {
                    return false;
                }
            }
        }

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

        b.append("Intent { ");
        toShortString(b, true, true, true);
        b.append(" }");

        return b.toString();
    }

    /** @hide */
    public String toInsecureString() {
        StringBuilder b = new StringBuilder(128);

        b.append("Intent { ");
        toShortString(b, false, true, true);
        b.append(" }");

        return b.toString();
    }

    /** @hide */
    public String toShortString(boolean secure, boolean comp, boolean extras) {
        StringBuilder b = new StringBuilder(128);
        toShortString(b, secure, comp, extras);
        return b.toString();
    }

    /** @hide */
    public void toShortString(StringBuilder b, boolean secure, boolean comp, boolean extras) {
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
            Iterator<String> i = mCategories.iterator();
            boolean didone = false;
            while (i.hasNext()) {
                if (didone) b.append(",");
                didone = true;
                b.append(i.next());
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
        if (extras && mExtras != null) {
            if (!first) {
                b.append(' ');
            }
            first = false;
            b.append("(has extras)");
        }
        if (mSelector != null) {
            b.append(" sel={");
            mSelector.toShortString(b, secure, comp, extras);
            b.append("}");
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
     * @param flags Additional operating flags.  Either 0 or
     * {@link #URI_INTENT_SCHEME}.
     *
     * @return Returns a URI encoding URI string describing the entire contents
     * of the Intent.
     */
    public String toUri(int flags) {
        StringBuilder uri = new StringBuilder(128);
        String scheme = null;
        if (mData != null) {
            String data = mData.toString();
            if ((flags&URI_INTENT_SCHEME) != 0) {
                final int N = data.length();
                for (int i=0; i<N; i++) {
                    char c = data.charAt(i);
                    if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                            || c == '.' || c == '-') {
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

        uri.append("#Intent;");

        toUriInner(uri, scheme, flags);
        if (mSelector != null) {
            uri.append("SEL;");
            // Note that for now we are not going to try to handle the
            // data part; not clear how to represent this as a URI, and
            // not much utility in it.
            mSelector.toUriInner(uri, null, flags);
        }

        uri.append("end");

        return uri.toString();
    }

    private void toUriInner(StringBuilder uri, String scheme, int flags) {
        if (scheme != null) {
            uri.append("scheme=").append(scheme).append(';');
        }
        if (mAction != null) {
            uri.append("action=").append(Uri.encode(mAction)).append(';');
        }
        if (mCategories != null) {
            for (String category : mCategories) {
                uri.append("category=").append(Uri.encode(category)).append(';');
            }
        }
        if (mType != null) {
            uri.append("type=").append(Uri.encode(mType, "/")).append(';');
        }
        if (mFlags != 0) {
            uri.append("launchFlags=0x").append(Integer.toHexString(mFlags)).append(';');
        }
        if (mPackage != null) {
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
        out.writeString(mAction);
        Uri.writeToParcel(out, mData);
        out.writeString(mType);
        out.writeInt(mFlags);
        out.writeString(mPackage);
        ComponentName.writeToParcel(mComponent, out);

        if (mSourceBounds != null) {
            out.writeInt(1);
            mSourceBounds.writeToParcel(out, flags);
        } else {
            out.writeInt(0);
        }

        if (mCategories != null) {
            out.writeInt(mCategories.size());
            for (String category : mCategories) {
                out.writeString(category);
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

        out.writeBundle(mExtras);
    }

    public static final Parcelable.Creator<Intent> CREATOR
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
        readFromParcel(in);
    }

    public void readFromParcel(Parcel in) {
        setAction(in.readString());
        mData = Uri.CREATOR.createFromParcel(in);
        mType = in.readString();
        mFlags = in.readInt();
        mPackage = in.readString();
        mComponent = ComponentName.readFromParcel(in);

        if (in.readInt() != 0) {
            mSourceBounds = Rect.CREATOR.createFromParcel(in);
        }

        int N = in.readInt();
        if (N > 0) {
            mCategories = new HashSet<String>();
            int i;
            for (i=0; i<N; i++) {
                mCategories.add(in.readString().intern());
            }
        } else {
            mCategories = null;
        }

        if (in.readInt() != 0) {
            mSelector = new Intent(in);
        }

        mExtras = in.readBundle();
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
    public static Intent parseIntent(Resources resources, XmlPullParser parser, AttributeSet attrs)
            throws XmlPullParserException, IOException {
        Intent intent = new Intent();

        TypedArray sa = resources.obtainAttributes(attrs,
                com.android.internal.R.styleable.Intent);

        intent.setAction(sa.getString(com.android.internal.R.styleable.Intent_action));

        String data = sa.getString(com.android.internal.R.styleable.Intent_data);
        String mimeType = sa.getString(com.android.internal.R.styleable.Intent_mimeType);
        intent.setDataAndType(data != null ? Uri.parse(data) : null, mimeType);

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
            if (nodeName.equals("category")) {
                sa = resources.obtainAttributes(attrs,
                        com.android.internal.R.styleable.IntentCategory);
                String cat = sa.getString(com.android.internal.R.styleable.IntentCategory_name);
                sa.recycle();

                if (cat != null) {
                    intent.addCategory(cat);
                }
                XmlUtils.skipCurrentTag(parser);

            } else if (nodeName.equals("extra")) {
                if (intent.mExtras == null) {
                    intent.mExtras = new Bundle();
                }
                resources.parseBundleExtra("extra", attrs, intent.mExtras);
                XmlUtils.skipCurrentTag(parser);

            } else {
                XmlUtils.skipCurrentTag(parser);
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
     * @see {@link #setType}
     * @see {@link #setTypeAndNormalize}
     */
    public static String normalizeMimeType(String type) {
        if (type == null) {
            return null;
        }

        type = type.trim().toLowerCase(Locale.US);

        final int semicolonIndex = type.indexOf(';');
        if (semicolonIndex != -1) {
            type = type.substring(0, semicolonIndex);
        }
        return type;
    }
}
