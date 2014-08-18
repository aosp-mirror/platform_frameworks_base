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

package android.app;

import android.annotation.NonNull;
import android.os.PersistableBundle;
import android.transition.Scene;
import android.transition.TransitionManager;
import android.util.ArrayMap;
import android.util.SuperNotCalledException;
import android.widget.Toolbar;

import com.android.internal.app.IVoiceInteractor;
import com.android.internal.app.WindowDecorActionBar;
import com.android.internal.app.ToolbarActionBar;
import com.android.internal.policy.PolicyManager;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentCallbacks2;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.CursorLoader;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.StrictMode;
import android.os.UserHandle;
import android.text.Selection;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.method.TextKeyListener;
import android.util.AttributeSet;
import android.util.EventLog;
import android.util.Log;
import android.util.PrintWriterPrinter;
import android.util.Slog;
import android.util.SparseArray;
import android.view.ActionMode;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnCreateContextMenuListener;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewManager;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.accessibility.AccessibilityEvent;
import android.widget.AdapterView;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * An activity is a single, focused thing that the user can do.  Almost all
 * activities interact with the user, so the Activity class takes care of
 * creating a window for you in which you can place your UI with
 * {@link #setContentView}.  While activities are often presented to the user
 * as full-screen windows, they can also be used in other ways: as floating
 * windows (via a theme with {@link android.R.attr#windowIsFloating} set)
 * or embedded inside of another activity (using {@link ActivityGroup}).
 *
 * There are two methods almost all subclasses of Activity will implement:
 *
 * <ul>
 *     <li> {@link #onCreate} is where you initialize your activity.  Most
 *     importantly, here you will usually call {@link #setContentView(int)}
 *     with a layout resource defining your UI, and using {@link #findViewById}
 *     to retrieve the widgets in that UI that you need to interact with
 *     programmatically.
 *
 *     <li> {@link #onPause} is where you deal with the user leaving your
 *     activity.  Most importantly, any changes made by the user should at this
 *     point be committed (usually to the
 *     {@link android.content.ContentProvider} holding the data).
 * </ul>
 *
 * <p>To be of use with {@link android.content.Context#startActivity Context.startActivity()}, all
 * activity classes must have a corresponding
 * {@link android.R.styleable#AndroidManifestActivity &lt;activity&gt;}
 * declaration in their package's <code>AndroidManifest.xml</code>.</p>
 *
 * <p>Topics covered here:
 * <ol>
 * <li><a href="#Fragments">Fragments</a>
 * <li><a href="#ActivityLifecycle">Activity Lifecycle</a>
 * <li><a href="#ConfigurationChanges">Configuration Changes</a>
 * <li><a href="#StartingActivities">Starting Activities and Getting Results</a>
 * <li><a href="#SavingPersistentState">Saving Persistent State</a>
 * <li><a href="#Permissions">Permissions</a>
 * <li><a href="#ProcessLifecycle">Process Lifecycle</a>
 * </ol>
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>The Activity class is an important part of an application's overall lifecycle,
 * and the way activities are launched and put together is a fundamental
 * part of the platform's application model. For a detailed perspective on the structure of an
 * Android application and how activities behave, please read the
 * <a href="{@docRoot}guide/topics/fundamentals.html">Application Fundamentals</a> and
 * <a href="{@docRoot}guide/topics/fundamentals/tasks-and-back-stack.html">Tasks and Back Stack</a>
 * developer guides.</p>
 *
 * <p>You can also find a detailed discussion about how to create activities in the
 * <a href="{@docRoot}guide/topics/fundamentals/activities.html">Activities</a>
 * developer guide.</p>
 * </div>
 *
 * <a name="Fragments"></a>
 * <h3>Fragments</h3>
 *
 * <p>Starting with {@link android.os.Build.VERSION_CODES#HONEYCOMB}, Activity
 * implementations can make use of the {@link Fragment} class to better
 * modularize their code, build more sophisticated user interfaces for larger
 * screens, and help scale their application between small and large screens.
 *
 * <a name="ActivityLifecycle"></a>
 * <h3>Activity Lifecycle</h3>
 *
 * <p>Activities in the system are managed as an <em>activity stack</em>.
 * When a new activity is started, it is placed on the top of the stack
 * and becomes the running activity -- the previous activity always remains
 * below it in the stack, and will not come to the foreground again until
 * the new activity exits.</p>
 *
 * <p>An activity has essentially four states:</p>
 * <ul>
 *     <li> If an activity in the foreground of the screen (at the top of
 *         the stack),
 *         it is <em>active</em> or  <em>running</em>. </li>
 *     <li>If an activity has lost focus but is still visible (that is, a new non-full-sized
 *         or transparent activity has focus on top of your activity), it
 *         is <em>paused</em>. A paused activity is completely alive (it
 *         maintains all state and member information and remains attached to
 *         the window manager), but can be killed by the system in extreme
 *         low memory situations.
 *     <li>If an activity is completely obscured by another activity,
 *         it is <em>stopped</em>. It still retains all state and member information,
 *         however, it is no longer visible to the user so its window is hidden
 *         and it will often be killed by the system when memory is needed
 *         elsewhere.</li>
 *     <li>If an activity is paused or stopped, the system can drop the activity
 *         from memory by either asking it to finish, or simply killing its
 *         process.  When it is displayed again to the user, it must be
 *         completely restarted and restored to its previous state.</li>
 * </ul>
 *
 * <p>The following diagram shows the important state paths of an Activity.
 * The square rectangles represent callback methods you can implement to
 * perform operations when the Activity moves between states.  The colored
 * ovals are major states the Activity can be in.</p>
 *
 * <p><img src="../../../images/activity_lifecycle.png"
 *      alt="State diagram for an Android Activity Lifecycle." border="0" /></p>
 *
 * <p>There are three key loops you may be interested in monitoring within your
 * activity:
 *
 * <ul>
 * <li>The <b>entire lifetime</b> of an activity happens between the first call
 * to {@link android.app.Activity#onCreate} through to a single final call
 * to {@link android.app.Activity#onDestroy}.  An activity will do all setup
 * of "global" state in onCreate(), and release all remaining resources in
 * onDestroy().  For example, if it has a thread running in the background
 * to download data from the network, it may create that thread in onCreate()
 * and then stop the thread in onDestroy().
 *
 * <li>The <b>visible lifetime</b> of an activity happens between a call to
 * {@link android.app.Activity#onStart} until a corresponding call to
 * {@link android.app.Activity#onStop}.  During this time the user can see the
 * activity on-screen, though it may not be in the foreground and interacting
 * with the user.  Between these two methods you can maintain resources that
 * are needed to show the activity to the user.  For example, you can register
 * a {@link android.content.BroadcastReceiver} in onStart() to monitor for changes
 * that impact your UI, and unregister it in onStop() when the user no
 * longer sees what you are displaying.  The onStart() and onStop() methods
 * can be called multiple times, as the activity becomes visible and hidden
 * to the user.
 *
 * <li>The <b>foreground lifetime</b> of an activity happens between a call to
 * {@link android.app.Activity#onResume} until a corresponding call to
 * {@link android.app.Activity#onPause}.  During this time the activity is
 * in front of all other activities and interacting with the user.  An activity
 * can frequently go between the resumed and paused states -- for example when
 * the device goes to sleep, when an activity result is delivered, when a new
 * intent is delivered -- so the code in these methods should be fairly
 * lightweight.
 * </ul>
 *
 * <p>The entire lifecycle of an activity is defined by the following
 * Activity methods.  All of these are hooks that you can override
 * to do appropriate work when the activity changes state.  All
 * activities will implement {@link android.app.Activity#onCreate}
 * to do their initial setup; many will also implement
 * {@link android.app.Activity#onPause} to commit changes to data and
 * otherwise prepare to stop interacting with the user.  You should always
 * call up to your superclass when implementing these methods.</p>
 *
 * </p>
 * <pre class="prettyprint">
 * public class Activity extends ApplicationContext {
 *     protected void onCreate(Bundle savedInstanceState);
 *
 *     protected void onStart();
 *
 *     protected void onRestart();
 *
 *     protected void onResume();
 *
 *     protected void onPause();
 *
 *     protected void onStop();
 *
 *     protected void onDestroy();
 * }
 * </pre>
 *
 * <p>In general the movement through an activity's lifecycle looks like
 * this:</p>
 *
 * <table border="2" width="85%" align="center" frame="hsides" rules="rows">
 *     <colgroup align="left" span="3" />
 *     <colgroup align="left" />
 *     <colgroup align="center" />
 *     <colgroup align="center" />
 *
 *     <thead>
 *     <tr><th colspan="3">Method</th> <th>Description</th> <th>Killable?</th> <th>Next</th></tr>
 *     </thead>
 *
 *     <tbody>
 *     <tr><th colspan="3" align="left" border="0">{@link android.app.Activity#onCreate onCreate()}</th>
 *         <td>Called when the activity is first created.
 *             This is where you should do all of your normal static set up:
 *             create views, bind data to lists, etc.  This method also
 *             provides you with a Bundle containing the activity's previously
 *             frozen state, if there was one.
 *             <p>Always followed by <code>onStart()</code>.</td>
 *         <td align="center">No</td>
 *         <td align="center"><code>onStart()</code></td>
 *     </tr>
 *
 *     <tr><td rowspan="5" style="border-left: none; border-right: none;">&nbsp;&nbsp;&nbsp;&nbsp;</td>
 *         <th colspan="2" align="left" border="0">{@link android.app.Activity#onRestart onRestart()}</th>
 *         <td>Called after your activity has been stopped, prior to it being
 *             started again.
 *             <p>Always followed by <code>onStart()</code></td>
 *         <td align="center">No</td>
 *         <td align="center"><code>onStart()</code></td>
 *     </tr>
 *
 *     <tr><th colspan="2" align="left" border="0">{@link android.app.Activity#onStart onStart()}</th>
 *         <td>Called when the activity is becoming visible to the user.
 *             <p>Followed by <code>onResume()</code> if the activity comes
 *             to the foreground, or <code>onStop()</code> if it becomes hidden.</td>
 *         <td align="center">No</td>
 *         <td align="center"><code>onResume()</code> or <code>onStop()</code></td>
 *     </tr>
 *
 *     <tr><td rowspan="2" style="border-left: none;">&nbsp;&nbsp;&nbsp;&nbsp;</td>
 *         <th align="left" border="0">{@link android.app.Activity#onResume onResume()}</th>
 *         <td>Called when the activity will start
 *             interacting with the user.  At this point your activity is at
 *             the top of the activity stack, with user input going to it.
 *             <p>Always followed by <code>onPause()</code>.</td>
 *         <td align="center">No</td>
 *         <td align="center"><code>onPause()</code></td>
 *     </tr>
 *
 *     <tr><th align="left" border="0">{@link android.app.Activity#onPause onPause()}</th>
 *         <td>Called when the system is about to start resuming a previous
 *             activity.  This is typically used to commit unsaved changes to
 *             persistent data, stop animations and other things that may be consuming
 *             CPU, etc.  Implementations of this method must be very quick because
 *             the next activity will not be resumed until this method returns.
 *             <p>Followed by either <code>onResume()</code> if the activity
 *             returns back to the front, or <code>onStop()</code> if it becomes
 *             invisible to the user.</td>
 *         <td align="center"><font color="#800000"><strong>Pre-{@link android.os.Build.VERSION_CODES#HONEYCOMB}</strong></font></td>
 *         <td align="center"><code>onResume()</code> or<br>
 *                 <code>onStop()</code></td>
 *     </tr>
 *
 *     <tr><th colspan="2" align="left" border="0">{@link android.app.Activity#onStop onStop()}</th>
 *         <td>Called when the activity is no longer visible to the user, because
 *             another activity has been resumed and is covering this one.  This
 *             may happen either because a new activity is being started, an existing
 *             one is being brought in front of this one, or this one is being
 *             destroyed.
 *             <p>Followed by either <code>onRestart()</code> if
 *             this activity is coming back to interact with the user, or
 *             <code>onDestroy()</code> if this activity is going away.</td>
 *         <td align="center"><font color="#800000"><strong>Yes</strong></font></td>
 *         <td align="center"><code>onRestart()</code> or<br>
 *                 <code>onDestroy()</code></td>
 *     </tr>
 *
 *     <tr><th colspan="3" align="left" border="0">{@link android.app.Activity#onDestroy onDestroy()}</th>
 *         <td>The final call you receive before your
 *             activity is destroyed.  This can happen either because the
 *             activity is finishing (someone called {@link Activity#finish} on
 *             it, or because the system is temporarily destroying this
 *             instance of the activity to save space.  You can distinguish
 *             between these two scenarios with the {@link
 *             Activity#isFinishing} method.</td>
 *         <td align="center"><font color="#800000"><strong>Yes</strong></font></td>
 *         <td align="center"><em>nothing</em></td>
 *     </tr>
 *     </tbody>
 * </table>
 *
 * <p>Note the "Killable" column in the above table -- for those methods that
 * are marked as being killable, after that method returns the process hosting the
 * activity may killed by the system <em>at any time</em> without another line
 * of its code being executed.  Because of this, you should use the
 * {@link #onPause} method to write any persistent data (such as user edits)
 * to storage.  In addition, the method
 * {@link #onSaveInstanceState(Bundle)} is called before placing the activity
 * in such a background state, allowing you to save away any dynamic instance
 * state in your activity into the given Bundle, to be later received in
 * {@link #onCreate} if the activity needs to be re-created.
 * See the <a href="#ProcessLifecycle">Process Lifecycle</a>
 * section for more information on how the lifecycle of a process is tied
 * to the activities it is hosting.  Note that it is important to save
 * persistent data in {@link #onPause} instead of {@link #onSaveInstanceState}
 * because the latter is not part of the lifecycle callbacks, so will not
 * be called in every situation as described in its documentation.</p>
 *
 * <p class="note">Be aware that these semantics will change slightly between
 * applications targeting platforms starting with {@link android.os.Build.VERSION_CODES#HONEYCOMB}
 * vs. those targeting prior platforms.  Starting with Honeycomb, an application
 * is not in the killable state until its {@link #onStop} has returned.  This
 * impacts when {@link #onSaveInstanceState(Bundle)} may be called (it may be
 * safely called after {@link #onPause()} and allows and application to safely
 * wait until {@link #onStop()} to save persistent state.</p>
 *
 * <p>For those methods that are not marked as being killable, the activity's
 * process will not be killed by the system starting from the time the method
 * is called and continuing after it returns.  Thus an activity is in the killable
 * state, for example, between after <code>onPause()</code> to the start of
 * <code>onResume()</code>.</p>
 *
 * <a name="ConfigurationChanges"></a>
 * <h3>Configuration Changes</h3>
 *
 * <p>If the configuration of the device (as defined by the
 * {@link Configuration Resources.Configuration} class) changes,
 * then anything displaying a user interface will need to update to match that
 * configuration.  Because Activity is the primary mechanism for interacting
 * with the user, it includes special support for handling configuration
 * changes.</p>
 *
 * <p>Unless you specify otherwise, a configuration change (such as a change
 * in screen orientation, language, input devices, etc) will cause your
 * current activity to be <em>destroyed</em>, going through the normal activity
 * lifecycle process of {@link #onPause},
 * {@link #onStop}, and {@link #onDestroy} as appropriate.  If the activity
 * had been in the foreground or visible to the user, once {@link #onDestroy} is
 * called in that instance then a new instance of the activity will be
 * created, with whatever savedInstanceState the previous instance had generated
 * from {@link #onSaveInstanceState}.</p>
 *
 * <p>This is done because any application resource,
 * including layout files, can change based on any configuration value.  Thus
 * the only safe way to handle a configuration change is to re-retrieve all
 * resources, including layouts, drawables, and strings.  Because activities
 * must already know how to save their state and re-create themselves from
 * that state, this is a convenient way to have an activity restart itself
 * with a new configuration.</p>
 *
 * <p>In some special cases, you may want to bypass restarting of your
 * activity based on one or more types of configuration changes.  This is
 * done with the {@link android.R.attr#configChanges android:configChanges}
 * attribute in its manifest.  For any types of configuration changes you say
 * that you handle there, you will receive a call to your current activity's
 * {@link #onConfigurationChanged} method instead of being restarted.  If
 * a configuration change involves any that you do not handle, however, the
 * activity will still be restarted and {@link #onConfigurationChanged}
 * will not be called.</p>
 *
 * <a name="StartingActivities"></a>
 * <h3>Starting Activities and Getting Results</h3>
 *
 * <p>The {@link android.app.Activity#startActivity}
 * method is used to start a
 * new activity, which will be placed at the top of the activity stack.  It
 * takes a single argument, an {@link android.content.Intent Intent},
 * which describes the activity
 * to be executed.</p>
 *
 * <p>Sometimes you want to get a result back from an activity when it
 * ends.  For example, you may start an activity that lets the user pick
 * a person in a list of contacts; when it ends, it returns the person
 * that was selected.  To do this, you call the
 * {@link android.app.Activity#startActivityForResult(Intent, int)}
 * version with a second integer parameter identifying the call.  The result
 * will come back through your {@link android.app.Activity#onActivityResult}
 * method.</p>
 *
 * <p>When an activity exits, it can call
 * {@link android.app.Activity#setResult(int)}
 * to return data back to its parent.  It must always supply a result code,
 * which can be the standard results RESULT_CANCELED, RESULT_OK, or any
 * custom values starting at RESULT_FIRST_USER.  In addition, it can optionally
 * return back an Intent containing any additional data it wants.  All of this
 * information appears back on the
 * parent's <code>Activity.onActivityResult()</code>, along with the integer
 * identifier it originally supplied.</p>
 *
 * <p>If a child activity fails for any reason (such as crashing), the parent
 * activity will receive a result with the code RESULT_CANCELED.</p>
 *
 * <pre class="prettyprint">
 * public class MyActivity extends Activity {
 *     ...
 *
 *     static final int PICK_CONTACT_REQUEST = 0;
 *
 *     public boolean onKeyDown(int keyCode, KeyEvent event) {
 *         if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
 *             // When the user center presses, let them pick a contact.
 *             startActivityForResult(
 *                 new Intent(Intent.ACTION_PICK,
 *                 new Uri("content://contacts")),
 *                 PICK_CONTACT_REQUEST);
 *            return true;
 *         }
 *         return false;
 *     }
 *
 *     protected void onActivityResult(int requestCode, int resultCode,
 *             Intent data) {
 *         if (requestCode == PICK_CONTACT_REQUEST) {
 *             if (resultCode == RESULT_OK) {
 *                 // A contact was picked.  Here we will just display it
 *                 // to the user.
 *                 startActivity(new Intent(Intent.ACTION_VIEW, data));
 *             }
 *         }
 *     }
 * }
 * </pre>
 *
 * <a name="SavingPersistentState"></a>
 * <h3>Saving Persistent State</h3>
 *
 * <p>There are generally two kinds of persistent state than an activity
 * will deal with: shared document-like data (typically stored in a SQLite
 * database using a {@linkplain android.content.ContentProvider content provider})
 * and internal state such as user preferences.</p>
 *
 * <p>For content provider data, we suggest that activities use a
 * "edit in place" user model.  That is, any edits a user makes are effectively
 * made immediately without requiring an additional confirmation step.
 * Supporting this model is generally a simple matter of following two rules:</p>
 *
 * <ul>
 *     <li> <p>When creating a new document, the backing database entry or file for
 *             it is created immediately.  For example, if the user chooses to write
 *             a new e-mail, a new entry for that e-mail is created as soon as they
 *             start entering data, so that if they go to any other activity after
 *             that point this e-mail will now appear in the list of drafts.</p>
 *     <li> <p>When an activity's <code>onPause()</code> method is called, it should
 *             commit to the backing content provider or file any changes the user
 *             has made.  This ensures that those changes will be seen by any other
 *             activity that is about to run.  You will probably want to commit
 *             your data even more aggressively at key times during your
 *             activity's lifecycle: for example before starting a new
 *             activity, before finishing your own activity, when the user
 *             switches between input fields, etc.</p>
 * </ul>
 *
 * <p>This model is designed to prevent data loss when a user is navigating
 * between activities, and allows the system to safely kill an activity (because
 * system resources are needed somewhere else) at any time after it has been
 * paused.  Note this implies
 * that the user pressing BACK from your activity does <em>not</em>
 * mean "cancel" -- it means to leave the activity with its current contents
 * saved away.  Canceling edits in an activity must be provided through
 * some other mechanism, such as an explicit "revert" or "undo" option.</p>
 *
 * <p>See the {@linkplain android.content.ContentProvider content package} for
 * more information about content providers.  These are a key aspect of how
 * different activities invoke and propagate data between themselves.</p>
 *
 * <p>The Activity class also provides an API for managing internal persistent state
 * associated with an activity.  This can be used, for example, to remember
 * the user's preferred initial display in a calendar (day view or week view)
 * or the user's default home page in a web browser.</p>
 *
 * <p>Activity persistent state is managed
 * with the method {@link #getPreferences},
 * allowing you to retrieve and
 * modify a set of name/value pairs associated with the activity.  To use
 * preferences that are shared across multiple application components
 * (activities, receivers, services, providers), you can use the underlying
 * {@link Context#getSharedPreferences Context.getSharedPreferences()} method
 * to retrieve a preferences
 * object stored under a specific name.
 * (Note that it is not possible to share settings data across application
 * packages -- for that you will need a content provider.)</p>
 *
 * <p>Here is an excerpt from a calendar activity that stores the user's
 * preferred view mode in its persistent settings:</p>
 *
 * <pre class="prettyprint">
 * public class CalendarActivity extends Activity {
 *     ...
 *
 *     static final int DAY_VIEW_MODE = 0;
 *     static final int WEEK_VIEW_MODE = 1;
 *
 *     private SharedPreferences mPrefs;
 *     private int mCurViewMode;
 *
 *     protected void onCreate(Bundle savedInstanceState) {
 *         super.onCreate(savedInstanceState);
 *
 *         SharedPreferences mPrefs = getSharedPreferences();
 *         mCurViewMode = mPrefs.getInt("view_mode", DAY_VIEW_MODE);
 *     }
 *
 *     protected void onPause() {
 *         super.onPause();
 *
 *         SharedPreferences.Editor ed = mPrefs.edit();
 *         ed.putInt("view_mode", mCurViewMode);
 *         ed.commit();
 *     }
 * }
 * </pre>
 *
 * <a name="Permissions"></a>
 * <h3>Permissions</h3>
 *
 * <p>The ability to start a particular Activity can be enforced when it is
 * declared in its
 * manifest's {@link android.R.styleable#AndroidManifestActivity &lt;activity&gt;}
 * tag.  By doing so, other applications will need to declare a corresponding
 * {@link android.R.styleable#AndroidManifestUsesPermission &lt;uses-permission&gt;}
 * element in their own manifest to be able to start that activity.
 *
 * <p>When starting an Activity you can set {@link Intent#FLAG_GRANT_READ_URI_PERMISSION
 * Intent.FLAG_GRANT_READ_URI_PERMISSION} and/or {@link Intent#FLAG_GRANT_WRITE_URI_PERMISSION
 * Intent.FLAG_GRANT_WRITE_URI_PERMISSION} on the Intent.  This will grant the
 * Activity access to the specific URIs in the Intent.  Access will remain
 * until the Activity has finished (it will remain across the hosting
 * process being killed and other temporary destruction).  As of
 * {@link android.os.Build.VERSION_CODES#GINGERBREAD}, if the Activity
 * was already created and a new Intent is being delivered to
 * {@link #onNewIntent(Intent)}, any newly granted URI permissions will be added
 * to the existing ones it holds.
 *
 * <p>See the <a href="{@docRoot}guide/topics/security/security.html">Security and Permissions</a>
 * document for more information on permissions and security in general.
 *
 * <a name="ProcessLifecycle"></a>
 * <h3>Process Lifecycle</h3>
 *
 * <p>The Android system attempts to keep application process around for as
 * long as possible, but eventually will need to remove old processes when
 * memory runs low.  As described in <a href="#ActivityLifecycle">Activity
 * Lifecycle</a>, the decision about which process to remove is intimately
 * tied to the state of the user's interaction with it.  In general, there
 * are four states a process can be in based on the activities running in it,
 * listed here in order of importance.  The system will kill less important
 * processes (the last ones) before it resorts to killing more important
 * processes (the first ones).
 *
 * <ol>
 * <li> <p>The <b>foreground activity</b> (the activity at the top of the screen
 * that the user is currently interacting with) is considered the most important.
 * Its process will only be killed as a last resort, if it uses more memory
 * than is available on the device.  Generally at this point the device has
 * reached a memory paging state, so this is required in order to keep the user
 * interface responsive.
 * <li> <p>A <b>visible activity</b> (an activity that is visible to the user
 * but not in the foreground, such as one sitting behind a foreground dialog)
 * is considered extremely important and will not be killed unless that is
 * required to keep the foreground activity running.
 * <li> <p>A <b>background activity</b> (an activity that is not visible to
 * the user and has been paused) is no longer critical, so the system may
 * safely kill its process to reclaim memory for other foreground or
 * visible processes.  If its process needs to be killed, when the user navigates
 * back to the activity (making it visible on the screen again), its
 * {@link #onCreate} method will be called with the savedInstanceState it had previously
 * supplied in {@link #onSaveInstanceState} so that it can restart itself in the same
 * state as the user last left it.
 * <li> <p>An <b>empty process</b> is one hosting no activities or other
 * application components (such as {@link Service} or
 * {@link android.content.BroadcastReceiver} classes).  These are killed very
 * quickly by the system as memory becomes low.  For this reason, any
 * background operation you do outside of an activity must be executed in the
 * context of an activity BroadcastReceiver or Service to ensure that the system
 * knows it needs to keep your process around.
 * </ol>
 *
 * <p>Sometimes an Activity may need to do a long-running operation that exists
 * independently of the activity lifecycle itself.  An example may be a camera
 * application that allows you to upload a picture to a web site.  The upload
 * may take a long time, and the application should allow the user to leave
 * the application will it is executing.  To accomplish this, your Activity
 * should start a {@link Service} in which the upload takes place.  This allows
 * the system to properly prioritize your process (considering it to be more
 * important than other non-visible applications) for the duration of the
 * upload, independent of whether the original activity is paused, stopped,
 * or finished.
 */
public class Activity extends ContextThemeWrapper
        implements LayoutInflater.Factory2,
        Window.Callback, KeyEvent.Callback,
        OnCreateContextMenuListener, ComponentCallbacks2,
        Window.OnWindowDismissedCallback {
    private static final String TAG = "Activity";
    private static final boolean DEBUG_LIFECYCLE = false;

    /** Standard activity result: operation canceled. */
    public static final int RESULT_CANCELED    = 0;
    /** Standard activity result: operation succeeded. */
    public static final int RESULT_OK           = -1;
    /** Start of user-defined activity results. */
    public static final int RESULT_FIRST_USER   = 1;

    static final String FRAGMENTS_TAG = "android:fragments";

    private static final String WINDOW_HIERARCHY_TAG = "android:viewHierarchyState";
    private static final String SAVED_DIALOG_IDS_KEY = "android:savedDialogIds";
    private static final String SAVED_DIALOGS_TAG = "android:savedDialogs";
    private static final String SAVED_DIALOG_KEY_PREFIX = "android:dialog_";
    private static final String SAVED_DIALOG_ARGS_KEY_PREFIX = "android:dialog_args_";

    private static class ManagedDialog {
        Dialog mDialog;
        Bundle mArgs;
    }
    private SparseArray<ManagedDialog> mManagedDialogs;

    // set by the thread after the constructor and before onCreate(Bundle savedInstanceState) is called.
    private Instrumentation mInstrumentation;
    private IBinder mToken;
    private int mIdent;
    /*package*/ String mEmbeddedID;
    private Application mApplication;
    /*package*/ Intent mIntent;
    private ComponentName mComponent;
    /*package*/ ActivityInfo mActivityInfo;
    /*package*/ ActivityThread mMainThread;
    Activity mParent;
    boolean mCalled;
    boolean mCheckedForLoaderManager;
    boolean mLoadersStarted;
    /*package*/ boolean mResumed;
    private boolean mStopped;
    boolean mFinished;
    boolean mStartedActivity;
    private boolean mDestroyed;
    private boolean mDoReportFullyDrawn = true;
    /** true if the activity is going through a transient pause */
    /*package*/ boolean mTemporaryPause = false;
    /** true if the activity is being destroyed in order to recreate it with a new configuration */
    /*package*/ boolean mChangingConfigurations = false;
    /*package*/ int mConfigChangeFlags;
    /*package*/ Configuration mCurrentConfig;
    private SearchManager mSearchManager;
    private MenuInflater mMenuInflater;

    static final class NonConfigurationInstances {
        Object activity;
        HashMap<String, Object> children;
        ArrayList<Fragment> fragments;
        ArrayMap<String, LoaderManagerImpl> loaders;
        VoiceInteractor voiceInteractor;
    }
    /* package */ NonConfigurationInstances mLastNonConfigurationInstances;

    private Window mWindow;

    private WindowManager mWindowManager;
    /*package*/ View mDecor = null;
    /*package*/ boolean mWindowAdded = false;
    /*package*/ boolean mVisibleFromServer = false;
    /*package*/ boolean mVisibleFromClient = true;
    /*package*/ ActionBar mActionBar = null;
    private boolean mEnableDefaultActionBarUp;

    private VoiceInteractor mVoiceInteractor;

    private CharSequence mTitle;
    private int mTitleColor = 0;

    final FragmentManagerImpl mFragments = new FragmentManagerImpl();
    final FragmentContainer mContainer = new FragmentContainer() {
        @Override
        public View findViewById(int id) {
            return Activity.this.findViewById(id);
        }
    };

    // Most recent call to requestVisibleBehind().
    boolean mVisibleBehind;

    ArrayMap<String, LoaderManagerImpl> mAllLoaderManagers;
    LoaderManagerImpl mLoaderManager;

    private static final class ManagedCursor {
        ManagedCursor(Cursor cursor) {
            mCursor = cursor;
            mReleased = false;
            mUpdated = false;
        }

        private final Cursor mCursor;
        private boolean mReleased;
        private boolean mUpdated;
    }
    private final ArrayList<ManagedCursor> mManagedCursors =
        new ArrayList<ManagedCursor>();

    // protected by synchronized (this)
    int mResultCode = RESULT_CANCELED;
    Intent mResultData = null;

    private TranslucentConversionListener mTranslucentCallback;
    private boolean mChangeCanvasToTranslucent;

    private boolean mTitleReady = false;

    private int mDefaultKeyMode = DEFAULT_KEYS_DISABLE;
    private SpannableStringBuilder mDefaultKeySsb = null;

    protected static final int[] FOCUSED_STATE_SET = {com.android.internal.R.attr.state_focused};

    @SuppressWarnings("unused")
    private final Object mInstanceTracker = StrictMode.trackActivity(this);

    private Thread mUiThread;
    final Handler mHandler = new Handler();

    ActivityTransitionState mActivityTransitionState = new ActivityTransitionState();
    SharedElementListener mEnterTransitionListener = SharedElementListener.NULL_LISTENER;
    SharedElementListener mExitTransitionListener = SharedElementListener.NULL_LISTENER;

    /** Return the intent that started this activity. */
    public Intent getIntent() {
        return mIntent;
    }

    /**
     * Change the intent returned by {@link #getIntent}.  This holds a
     * reference to the given intent; it does not copy it.  Often used in
     * conjunction with {@link #onNewIntent}.
     *
     * @param newIntent The new Intent object to return from getIntent
     *
     * @see #getIntent
     * @see #onNewIntent
     */
    public void setIntent(Intent newIntent) {
        mIntent = newIntent;
    }

    /** Return the application that owns this activity. */
    public final Application getApplication() {
        return mApplication;
    }

    /** Is this activity embedded inside of another activity? */
    public final boolean isChild() {
        return mParent != null;
    }

    /** Return the parent activity if this view is an embedded child. */
    public final Activity getParent() {
        return mParent;
    }

    /** Retrieve the window manager for showing custom windows. */
    public WindowManager getWindowManager() {
        return mWindowManager;
    }

    /**
     * Retrieve the current {@link android.view.Window} for the activity.
     * This can be used to directly access parts of the Window API that
     * are not available through Activity/Screen.
     *
     * @return Window The current window, or null if the activity is not
     *         visual.
     */
    public Window getWindow() {
        return mWindow;
    }

    /**
     * Return the LoaderManager for this activity, creating it if needed.
     */
    public LoaderManager getLoaderManager() {
        if (mLoaderManager != null) {
            return mLoaderManager;
        }
        mCheckedForLoaderManager = true;
        mLoaderManager = getLoaderManager("(root)", mLoadersStarted, true);
        return mLoaderManager;
    }

    LoaderManagerImpl getLoaderManager(String who, boolean started, boolean create) {
        if (mAllLoaderManagers == null) {
            mAllLoaderManagers = new ArrayMap<String, LoaderManagerImpl>();
        }
        LoaderManagerImpl lm = mAllLoaderManagers.get(who);
        if (lm == null) {
            if (create) {
                lm = new LoaderManagerImpl(who, this, started);
                mAllLoaderManagers.put(who, lm);
            }
        } else {
            lm.updateActivity(this);
        }
        return lm;
    }

    /**
     * Calls {@link android.view.Window#getCurrentFocus} on the
     * Window of this Activity to return the currently focused view.
     *
     * @return View The current View with focus or null.
     *
     * @see #getWindow
     * @see android.view.Window#getCurrentFocus
     */
    @Nullable
    public View getCurrentFocus() {
        return mWindow != null ? mWindow.getCurrentFocus() : null;
    }

    /**
     * Called when the activity is starting.  This is where most initialization
     * should go: calling {@link #setContentView(int)} to inflate the
     * activity's UI, using {@link #findViewById} to programmatically interact
     * with widgets in the UI, calling
     * {@link #managedQuery(android.net.Uri , String[], String, String[], String)} to retrieve
     * cursors for data being displayed, etc.
     *
     * <p>You can call {@link #finish} from within this function, in
     * which case onDestroy() will be immediately called without any of the rest
     * of the activity lifecycle ({@link #onStart}, {@link #onResume},
     * {@link #onPause}, etc) executing.
     *
     * <p><em>Derived classes must call through to the super class's
     * implementation of this method.  If they do not, an exception will be
     * thrown.</em></p>
     *
     * @param savedInstanceState If the activity is being re-initialized after
     *     previously being shut down then this Bundle contains the data it most
     *     recently supplied in {@link #onSaveInstanceState}.  <b><i>Note: Otherwise it is null.</i></b>
     *
     * @see #onStart
     * @see #onSaveInstanceState
     * @see #onRestoreInstanceState
     * @see #onPostCreate
     */
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        if (DEBUG_LIFECYCLE) Slog.v(TAG, "onCreate " + this + ": " + savedInstanceState);
        if (mLastNonConfigurationInstances != null) {
            mAllLoaderManagers = mLastNonConfigurationInstances.loaders;
        }
        if (mActivityInfo.parentActivityName != null) {
            if (mActionBar == null) {
                mEnableDefaultActionBarUp = true;
            } else {
                mActionBar.setDefaultDisplayHomeAsUpEnabled(true);
            }
        }
        if (savedInstanceState != null) {
            Parcelable p = savedInstanceState.getParcelable(FRAGMENTS_TAG);
            mFragments.restoreAllState(p, mLastNonConfigurationInstances != null
                    ? mLastNonConfigurationInstances.fragments : null);
        }
        mFragments.dispatchCreate();
        getApplication().dispatchActivityCreated(this, savedInstanceState);
        if (mVoiceInteractor != null) {
            mVoiceInteractor.attachActivity(this);
        }
        mCalled = true;
    }

    /**
     * Same as {@link #onCreate(android.os.Bundle)} but called for those activities created with
     * the attribute {@link android.R.attr#persistableMode} set to
     * <code>persistAcrossReboots</code>.
     *
     * @param savedInstanceState if the activity is being re-initialized after
     *     previously being shut down then this Bundle contains the data it most
     *     recently supplied in {@link #onSaveInstanceState}.
     *     <b><i>Note: Otherwise it is null.</i></b>
     * @param persistentState if the activity is being re-initialized after
     *     previously being shut down or powered off then this Bundle contains the data it most
     *     recently supplied to outPersistentState in {@link #onSaveInstanceState}.
     *     <b><i>Note: Otherwise it is null.</i></b>
     *
     * @see #onCreate(android.os.Bundle)
     * @see #onStart
     * @see #onSaveInstanceState
     * @see #onRestoreInstanceState
     * @see #onPostCreate
     */
    protected void onCreate(@Nullable Bundle savedInstanceState,
            @Nullable PersistableBundle persistentState) {
        onCreate(savedInstanceState);
    }

    /**
     * The hook for {@link ActivityThread} to restore the state of this activity.
     *
     * Calls {@link #onSaveInstanceState(android.os.Bundle)} and
     * {@link #restoreManagedDialogs(android.os.Bundle)}.
     *
     * @param savedInstanceState contains the saved state
     */
    final void performRestoreInstanceState(Bundle savedInstanceState) {
        onRestoreInstanceState(savedInstanceState);
        restoreManagedDialogs(savedInstanceState);
    }

    /**
     * The hook for {@link ActivityThread} to restore the state of this activity.
     *
     * Calls {@link #onSaveInstanceState(android.os.Bundle)} and
     * {@link #restoreManagedDialogs(android.os.Bundle)}.
     *
     * @param savedInstanceState contains the saved state
     * @param persistentState contains the persistable saved state
     */
    final void performRestoreInstanceState(Bundle savedInstanceState,
            PersistableBundle persistentState) {
        onRestoreInstanceState(savedInstanceState, persistentState);
        if (savedInstanceState != null) {
            restoreManagedDialogs(savedInstanceState);
        }
    }

    /**
     * This method is called after {@link #onStart} when the activity is
     * being re-initialized from a previously saved state, given here in
     * <var>savedInstanceState</var>.  Most implementations will simply use {@link #onCreate}
     * to restore their state, but it is sometimes convenient to do it here
     * after all of the initialization has been done or to allow subclasses to
     * decide whether to use your default implementation.  The default
     * implementation of this method performs a restore of any view state that
     * had previously been frozen by {@link #onSaveInstanceState}.
     *
     * <p>This method is called between {@link #onStart} and
     * {@link #onPostCreate}.
     *
     * @param savedInstanceState the data most recently supplied in {@link #onSaveInstanceState}.
     *
     * @see #onCreate
     * @see #onPostCreate
     * @see #onResume
     * @see #onSaveInstanceState
     */
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        if (mWindow != null) {
            Bundle windowState = savedInstanceState.getBundle(WINDOW_HIERARCHY_TAG);
            if (windowState != null) {
                mWindow.restoreHierarchyState(windowState);
            }
        }
    }

    /**
     * This is the same as {@link #onRestoreInstanceState(Bundle)} but is called for activities
     * created with the attribute {@link android.R.attr#persistableMode} set to
     * <code>persistAcrossReboots</code>. The {@link android.os.PersistableBundle} passed
     * came from the restored PersistableBundle first
     * saved in {@link #onSaveInstanceState(Bundle, PersistableBundle)}.
     *
     * <p>This method is called between {@link #onStart} and
     * {@link #onPostCreate}.
     *
     * <p>If this method is called {@link #onRestoreInstanceState(Bundle)} will not be called.
     *
     * @param savedInstanceState the data most recently supplied in {@link #onSaveInstanceState}.
     * @param persistentState the data most recently supplied in {@link #onSaveInstanceState}.
     *
     * @see #onRestoreInstanceState(Bundle)
     * @see #onCreate
     * @see #onPostCreate
     * @see #onResume
     * @see #onSaveInstanceState
     */
    protected void onRestoreInstanceState(Bundle savedInstanceState,
            PersistableBundle persistentState) {
        if (savedInstanceState != null) {
            onRestoreInstanceState(savedInstanceState);
        }
    }

    /**
     * Restore the state of any saved managed dialogs.
     *
     * @param savedInstanceState The bundle to restore from.
     */
    private void restoreManagedDialogs(Bundle savedInstanceState) {
        final Bundle b = savedInstanceState.getBundle(SAVED_DIALOGS_TAG);
        if (b == null) {
            return;
        }

        final int[] ids = b.getIntArray(SAVED_DIALOG_IDS_KEY);
        final int numDialogs = ids.length;
        mManagedDialogs = new SparseArray<ManagedDialog>(numDialogs);
        for (int i = 0; i < numDialogs; i++) {
            final Integer dialogId = ids[i];
            Bundle dialogState = b.getBundle(savedDialogKeyFor(dialogId));
            if (dialogState != null) {
                // Calling onRestoreInstanceState() below will invoke dispatchOnCreate
                // so tell createDialog() not to do it, otherwise we get an exception
                final ManagedDialog md = new ManagedDialog();
                md.mArgs = b.getBundle(savedDialogArgsKeyFor(dialogId));
                md.mDialog = createDialog(dialogId, dialogState, md.mArgs);
                if (md.mDialog != null) {
                    mManagedDialogs.put(dialogId, md);
                    onPrepareDialog(dialogId, md.mDialog, md.mArgs);
                    md.mDialog.onRestoreInstanceState(dialogState);
                }
            }
        }
    }

    private Dialog createDialog(Integer dialogId, Bundle state, Bundle args) {
        final Dialog dialog = onCreateDialog(dialogId, args);
        if (dialog == null) {
            return null;
        }
        dialog.dispatchOnCreate(state);
        return dialog;
    }

    private static String savedDialogKeyFor(int key) {
        return SAVED_DIALOG_KEY_PREFIX + key;
    }

    private static String savedDialogArgsKeyFor(int key) {
        return SAVED_DIALOG_ARGS_KEY_PREFIX + key;
    }

    /**
     * Called when activity start-up is complete (after {@link #onStart}
     * and {@link #onRestoreInstanceState} have been called).  Applications will
     * generally not implement this method; it is intended for system
     * classes to do final initialization after application code has run.
     *
     * <p><em>Derived classes must call through to the super class's
     * implementation of this method.  If they do not, an exception will be
     * thrown.</em></p>
     *
     * @param savedInstanceState If the activity is being re-initialized after
     *     previously being shut down then this Bundle contains the data it most
     *     recently supplied in {@link #onSaveInstanceState}.  <b><i>Note: Otherwise it is null.</i></b>
     * @see #onCreate
     */
    protected void onPostCreate(@Nullable Bundle savedInstanceState) {
        if (!isChild()) {
            mTitleReady = true;
            onTitleChanged(getTitle(), getTitleColor());
        }
        mCalled = true;
    }

    /**
     * This is the same as {@link #onPostCreate(Bundle)} but is called for activities
     * created with the attribute {@link android.R.attr#persistableMode} set to
     * <code>persistAcrossReboots</code>.
     *
     * @param savedInstanceState The data most recently supplied in {@link #onSaveInstanceState}
     * @param persistentState The data caming from the PersistableBundle first
     * saved in {@link #onSaveInstanceState(Bundle, PersistableBundle)}.
     *
     * @see #onCreate
     */
    protected void onPostCreate(@Nullable Bundle savedInstanceState,
            @Nullable PersistableBundle persistentState) {
        onPostCreate(savedInstanceState);
    }

    /**
     * Called after {@link #onCreate} &mdash; or after {@link #onRestart} when
     * the activity had been stopped, but is now again being displayed to the
     * user.  It will be followed by {@link #onResume}.
     *
     * <p><em>Derived classes must call through to the super class's
     * implementation of this method.  If they do not, an exception will be
     * thrown.</em></p>
     *
     * @see #onCreate
     * @see #onStop
     * @see #onResume
     */
    protected void onStart() {
        if (DEBUG_LIFECYCLE) Slog.v(TAG, "onStart " + this);
        mCalled = true;

        if (!mLoadersStarted) {
            mLoadersStarted = true;
            if (mLoaderManager != null) {
                mLoaderManager.doStart();
            } else if (!mCheckedForLoaderManager) {
                mLoaderManager = getLoaderManager("(root)", mLoadersStarted, false);
            }
            mCheckedForLoaderManager = true;
        }

        getApplication().dispatchActivityStarted(this);
    }

    /**
     * Called after {@link #onStop} when the current activity is being
     * re-displayed to the user (the user has navigated back to it).  It will
     * be followed by {@link #onStart} and then {@link #onResume}.
     *
     * <p>For activities that are using raw {@link Cursor} objects (instead of
     * creating them through
     * {@link #managedQuery(android.net.Uri , String[], String, String[], String)},
     * this is usually the place
     * where the cursor should be requeried (because you had deactivated it in
     * {@link #onStop}.
     *
     * <p><em>Derived classes must call through to the super class's
     * implementation of this method.  If they do not, an exception will be
     * thrown.</em></p>
     *
     * @see #onStop
     * @see #onStart
     * @see #onResume
     */
    protected void onRestart() {
        mCalled = true;
    }

    /**
     * Called after {@link #onRestoreInstanceState}, {@link #onRestart}, or
     * {@link #onPause}, for your activity to start interacting with the user.
     * This is a good place to begin animations, open exclusive-access devices
     * (such as the camera), etc.
     *
     * <p>Keep in mind that onResume is not the best indicator that your activity
     * is visible to the user; a system window such as the keyguard may be in
     * front.  Use {@link #onWindowFocusChanged} to know for certain that your
     * activity is visible to the user (for example, to resume a game).
     *
     * <p><em>Derived classes must call through to the super class's
     * implementation of this method.  If they do not, an exception will be
     * thrown.</em></p>
     *
     * @see #onRestoreInstanceState
     * @see #onRestart
     * @see #onPostResume
     * @see #onPause
     */
    protected void onResume() {
        if (DEBUG_LIFECYCLE) Slog.v(TAG, "onResume " + this);
        getApplication().dispatchActivityResumed(this);
        mActivityTransitionState.onResume();
        mCalled = true;
    }

    /**
     * Called when activity resume is complete (after {@link #onResume} has
     * been called). Applications will generally not implement this method;
     * it is intended for system classes to do final setup after application
     * resume code has run.
     *
     * <p><em>Derived classes must call through to the super class's
     * implementation of this method.  If they do not, an exception will be
     * thrown.</em></p>
     *
     * @see #onResume
     */
    protected void onPostResume() {
        final Window win = getWindow();
        if (win != null) win.makeActive();
        if (mActionBar != null) mActionBar.setShowHideAnimationEnabled(true);
        mCalled = true;
    }

    /**
     * Check whether this activity is running as part of a voice interaction with the user.
     * If true, it should perform its interaction with the user through the
     * {@link VoiceInteractor} returned by {@link #getVoiceInteractor}.
     */
    public boolean isVoiceInteraction() {
        return mVoiceInteractor != null;
    }

    /**
     * Retrieve the active {@link VoiceInteractor} that the user is going through to
     * interact with this activity.
     */
    public VoiceInteractor getVoiceInteractor() {
        return mVoiceInteractor;
    }

    /**
     * This is called for activities that set launchMode to "singleTop" in
     * their package, or if a client used the {@link Intent#FLAG_ACTIVITY_SINGLE_TOP}
     * flag when calling {@link #startActivity}.  In either case, when the
     * activity is re-launched while at the top of the activity stack instead
     * of a new instance of the activity being started, onNewIntent() will be
     * called on the existing instance with the Intent that was used to
     * re-launch it.
     *
     * <p>An activity will always be paused before receiving a new intent, so
     * you can count on {@link #onResume} being called after this method.
     *
     * <p>Note that {@link #getIntent} still returns the original Intent.  You
     * can use {@link #setIntent} to update it to this new Intent.
     *
     * @param intent The new intent that was started for the activity.
     *
     * @see #getIntent
     * @see #setIntent
     * @see #onResume
     */
    protected void onNewIntent(Intent intent) {
    }

    /**
     * The hook for {@link ActivityThread} to save the state of this activity.
     *
     * Calls {@link #onSaveInstanceState(android.os.Bundle)}
     * and {@link #saveManagedDialogs(android.os.Bundle)}.
     *
     * @param outState The bundle to save the state to.
     */
    final void performSaveInstanceState(Bundle outState) {
        onSaveInstanceState(outState);
        saveManagedDialogs(outState);
        mActivityTransitionState.saveState(outState);
        if (DEBUG_LIFECYCLE) Slog.v(TAG, "onSaveInstanceState " + this + ": " + outState);
    }

    /**
     * The hook for {@link ActivityThread} to save the state of this activity.
     *
     * Calls {@link #onSaveInstanceState(android.os.Bundle)}
     * and {@link #saveManagedDialogs(android.os.Bundle)}.
     *
     * @param outState The bundle to save the state to.
     * @param outPersistentState The bundle to save persistent state to.
     */
    final void performSaveInstanceState(Bundle outState, PersistableBundle outPersistentState) {
        onSaveInstanceState(outState, outPersistentState);
        saveManagedDialogs(outState);
        if (DEBUG_LIFECYCLE) Slog.v(TAG, "onSaveInstanceState " + this + ": " + outState +
                ", " + outPersistentState);
    }

    /**
     * Called to retrieve per-instance state from an activity before being killed
     * so that the state can be restored in {@link #onCreate} or
     * {@link #onRestoreInstanceState} (the {@link Bundle} populated by this method
     * will be passed to both).
     *
     * <p>This method is called before an activity may be killed so that when it
     * comes back some time in the future it can restore its state.  For example,
     * if activity B is launched in front of activity A, and at some point activity
     * A is killed to reclaim resources, activity A will have a chance to save the
     * current state of its user interface via this method so that when the user
     * returns to activity A, the state of the user interface can be restored
     * via {@link #onCreate} or {@link #onRestoreInstanceState}.
     *
     * <p>Do not confuse this method with activity lifecycle callbacks such as
     * {@link #onPause}, which is always called when an activity is being placed
     * in the background or on its way to destruction, or {@link #onStop} which
     * is called before destruction.  One example of when {@link #onPause} and
     * {@link #onStop} is called and not this method is when a user navigates back
     * from activity B to activity A: there is no need to call {@link #onSaveInstanceState}
     * on B because that particular instance will never be restored, so the
     * system avoids calling it.  An example when {@link #onPause} is called and
     * not {@link #onSaveInstanceState} is when activity B is launched in front of activity A:
     * the system may avoid calling {@link #onSaveInstanceState} on activity A if it isn't
     * killed during the lifetime of B since the state of the user interface of
     * A will stay intact.
     *
     * <p>The default implementation takes care of most of the UI per-instance
     * state for you by calling {@link android.view.View#onSaveInstanceState()} on each
     * view in the hierarchy that has an id, and by saving the id of the currently
     * focused view (all of which is restored by the default implementation of
     * {@link #onRestoreInstanceState}).  If you override this method to save additional
     * information not captured by each individual view, you will likely want to
     * call through to the default implementation, otherwise be prepared to save
     * all of the state of each view yourself.
     *
     * <p>If called, this method will occur before {@link #onStop}.  There are
     * no guarantees about whether it will occur before or after {@link #onPause}.
     *
     * @param outState Bundle in which to place your saved state.
     *
     * @see #onCreate
     * @see #onRestoreInstanceState
     * @see #onPause
     */
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBundle(WINDOW_HIERARCHY_TAG, mWindow.saveHierarchyState());
        Parcelable p = mFragments.saveAllState();
        if (p != null) {
            outState.putParcelable(FRAGMENTS_TAG, p);
        }
        getApplication().dispatchActivitySaveInstanceState(this, outState);
    }

    /**
     * This is the same as {@link #onSaveInstanceState} but is called for activities
     * created with the attribute {@link android.R.attr#persistableMode} set to
     * <code>persistAcrossReboots</code>. The {@link android.os.PersistableBundle} passed
     * in will be saved and presented in {@link #onCreate(Bundle, PersistableBundle)}
     * the first time that this activity is restarted following the next device reboot.
     *
     * @param outState Bundle in which to place your saved state.
     * @param outPersistentState State which will be saved across reboots.
     *
     * @see #onSaveInstanceState(Bundle)
     * @see #onCreate
     * @see #onRestoreInstanceState(Bundle, PersistableBundle)
     * @see #onPause
     */
    protected void onSaveInstanceState(Bundle outState, PersistableBundle outPersistentState) {
        onSaveInstanceState(outState);
    }

    /**
     * Save the state of any managed dialogs.
     *
     * @param outState place to store the saved state.
     */
    private void saveManagedDialogs(Bundle outState) {
        if (mManagedDialogs == null) {
            return;
        }

        final int numDialogs = mManagedDialogs.size();
        if (numDialogs == 0) {
            return;
        }

        Bundle dialogState = new Bundle();

        int[] ids = new int[mManagedDialogs.size()];

        // save each dialog's bundle, gather the ids
        for (int i = 0; i < numDialogs; i++) {
            final int key = mManagedDialogs.keyAt(i);
            ids[i] = key;
            final ManagedDialog md = mManagedDialogs.valueAt(i);
            dialogState.putBundle(savedDialogKeyFor(key), md.mDialog.onSaveInstanceState());
            if (md.mArgs != null) {
                dialogState.putBundle(savedDialogArgsKeyFor(key), md.mArgs);
            }
        }

        dialogState.putIntArray(SAVED_DIALOG_IDS_KEY, ids);
        outState.putBundle(SAVED_DIALOGS_TAG, dialogState);
    }


    /**
     * Called as part of the activity lifecycle when an activity is going into
     * the background, but has not (yet) been killed.  The counterpart to
     * {@link #onResume}.
     *
     * <p>When activity B is launched in front of activity A, this callback will
     * be invoked on A.  B will not be created until A's {@link #onPause} returns,
     * so be sure to not do anything lengthy here.
     *
     * <p>This callback is mostly used for saving any persistent state the
     * activity is editing, to present a "edit in place" model to the user and
     * making sure nothing is lost if there are not enough resources to start
     * the new activity without first killing this one.  This is also a good
     * place to do things like stop animations and other things that consume a
     * noticeable amount of CPU in order to make the switch to the next activity
     * as fast as possible, or to close resources that are exclusive access
     * such as the camera.
     *
     * <p>In situations where the system needs more memory it may kill paused
     * processes to reclaim resources.  Because of this, you should be sure
     * that all of your state is saved by the time you return from
     * this function.  In general {@link #onSaveInstanceState} is used to save
     * per-instance state in the activity and this method is used to store
     * global persistent data (in content providers, files, etc.)
     *
     * <p>After receiving this call you will usually receive a following call
     * to {@link #onStop} (after the next activity has been resumed and
     * displayed), however in some cases there will be a direct call back to
     * {@link #onResume} without going through the stopped state.
     *
     * <p><em>Derived classes must call through to the super class's
     * implementation of this method.  If they do not, an exception will be
     * thrown.</em></p>
     *
     * @see #onResume
     * @see #onSaveInstanceState
     * @see #onStop
     */
    protected void onPause() {
        if (DEBUG_LIFECYCLE) Slog.v(TAG, "onPause " + this);
        getApplication().dispatchActivityPaused(this);
        mCalled = true;
    }

    /**
     * Called as part of the activity lifecycle when an activity is about to go
     * into the background as the result of user choice.  For example, when the
     * user presses the Home key, {@link #onUserLeaveHint} will be called, but
     * when an incoming phone call causes the in-call Activity to be automatically
     * brought to the foreground, {@link #onUserLeaveHint} will not be called on
     * the activity being interrupted.  In cases when it is invoked, this method
     * is called right before the activity's {@link #onPause} callback.
     *
     * <p>This callback and {@link #onUserInteraction} are intended to help
     * activities manage status bar notifications intelligently; specifically,
     * for helping activities determine the proper time to cancel a notfication.
     *
     * @see #onUserInteraction()
     */
    protected void onUserLeaveHint() {
    }

    /**
     * Generate a new thumbnail for this activity.  This method is called before
     * pausing the activity, and should draw into <var>outBitmap</var> the
     * imagery for the desired thumbnail in the dimensions of that bitmap.  It
     * can use the given <var>canvas</var>, which is configured to draw into the
     * bitmap, for rendering if desired.
     *
     * <p>The default implementation returns fails and does not draw a thumbnail;
     * this will result in the platform creating its own thumbnail if needed.
     *
     * @param outBitmap The bitmap to contain the thumbnail.
     * @param canvas Can be used to render into the bitmap.
     *
     * @return Return true if you have drawn into the bitmap; otherwise after
     *         you return it will be filled with a default thumbnail.
     *
     * @see #onCreateDescription
     * @see #onSaveInstanceState
     * @see #onPause
     */
    public boolean onCreateThumbnail(Bitmap outBitmap, Canvas canvas) {
        return false;
    }

    /**
     * Generate a new description for this activity.  This method is called
     * before pausing the activity and can, if desired, return some textual
     * description of its current state to be displayed to the user.
     *
     * <p>The default implementation returns null, which will cause you to
     * inherit the description from the previous activity.  If all activities
     * return null, generally the label of the top activity will be used as the
     * description.
     *
     * @return A description of what the user is doing.  It should be short and
     *         sweet (only a few words).
     *
     * @see #onCreateThumbnail
     * @see #onSaveInstanceState
     * @see #onPause
     */
    @Nullable
    public CharSequence onCreateDescription() {
        return null;
    }

    /**
     * This is called when the user is requesting an assist, to build a full
     * {@link Intent#ACTION_ASSIST} Intent with all of the context of the current
     * application.  You can override this method to place into the bundle anything
     * you would like to appear in the {@link Intent#EXTRA_ASSIST_CONTEXT} part
     * of the assist Intent.  The default implementation does nothing.
     *
     * <p>This function will be called after any global assist callbacks that had
     * been registered with {@link Application#registerOnProvideAssistDataListener
     * Application.registerOnProvideAssistDataListener}.
     */
    public void onProvideAssistData(Bundle data) {
    }

    /**
     * Called when you are no longer visible to the user.  You will next
     * receive either {@link #onRestart}, {@link #onDestroy}, or nothing,
     * depending on later user activity.
     *
     * <p>Note that this method may never be called, in low memory situations
     * where the system does not have enough memory to keep your activity's
     * process running after its {@link #onPause} method is called.
     *
     * <p><em>Derived classes must call through to the super class's
     * implementation of this method.  If they do not, an exception will be
     * thrown.</em></p>
     *
     * @see #onRestart
     * @see #onResume
     * @see #onSaveInstanceState
     * @see #onDestroy
     */
    protected void onStop() {
        if (DEBUG_LIFECYCLE) Slog.v(TAG, "onStop " + this);
        if (mActionBar != null) mActionBar.setShowHideAnimationEnabled(false);
        mActivityTransitionState.onStop();
        getApplication().dispatchActivityStopped(this);
        mTranslucentCallback = null;
        mCalled = true;
    }

    /**
     * Perform any final cleanup before an activity is destroyed.  This can
     * happen either because the activity is finishing (someone called
     * {@link #finish} on it, or because the system is temporarily destroying
     * this instance of the activity to save space.  You can distinguish
     * between these two scenarios with the {@link #isFinishing} method.
     *
     * <p><em>Note: do not count on this method being called as a place for
     * saving data! For example, if an activity is editing data in a content
     * provider, those edits should be committed in either {@link #onPause} or
     * {@link #onSaveInstanceState}, not here.</em> This method is usually implemented to
     * free resources like threads that are associated with an activity, so
     * that a destroyed activity does not leave such things around while the
     * rest of its application is still running.  There are situations where
     * the system will simply kill the activity's hosting process without
     * calling this method (or any others) in it, so it should not be used to
     * do things that are intended to remain around after the process goes
     * away.
     *
     * <p><em>Derived classes must call through to the super class's
     * implementation of this method.  If they do not, an exception will be
     * thrown.</em></p>
     *
     * @see #onPause
     * @see #onStop
     * @see #finish
     * @see #isFinishing
     */
    protected void onDestroy() {
        if (DEBUG_LIFECYCLE) Slog.v(TAG, "onDestroy " + this);
        mCalled = true;

        // dismiss any dialogs we are managing.
        if (mManagedDialogs != null) {
            final int numDialogs = mManagedDialogs.size();
            for (int i = 0; i < numDialogs; i++) {
                final ManagedDialog md = mManagedDialogs.valueAt(i);
                if (md.mDialog.isShowing()) {
                    md.mDialog.dismiss();
                }
            }
            mManagedDialogs = null;
        }

        // close any cursors we are managing.
        synchronized (mManagedCursors) {
            int numCursors = mManagedCursors.size();
            for (int i = 0; i < numCursors; i++) {
                ManagedCursor c = mManagedCursors.get(i);
                if (c != null) {
                    c.mCursor.close();
                }
            }
            mManagedCursors.clear();
        }

        // Close any open search dialog
        if (mSearchManager != null) {
            mSearchManager.stopSearch();
        }

        getApplication().dispatchActivityDestroyed(this);
    }

    /**
     * Report to the system that your app is now fully drawn, purely for diagnostic
     * purposes (calling it does not impact the visible behavior of the activity).
     * This is only used to help instrument application launch times, so that the
     * app can report when it is fully in a usable state; without this, the only thing
     * the system itself can determine is the point at which the activity's window
     * is <em>first</em> drawn and displayed.  To participate in app launch time
     * measurement, you should always call this method after first launch (when
     * {@link #onCreate(android.os.Bundle)} is called), at the point where you have
     * entirely drawn your UI and populated with all of the significant data.  You
     * can safely call this method any time after first launch as well, in which case
     * it will simply be ignored.
     */
    public void reportFullyDrawn() {
        if (mDoReportFullyDrawn) {
            mDoReportFullyDrawn = false;
            try {
                ActivityManagerNative.getDefault().reportActivityFullyDrawn(mToken);
            } catch (RemoteException e) {
            }
        }
    }

    /**
     * Called by the system when the device configuration changes while your
     * activity is running.  Note that this will <em>only</em> be called if
     * you have selected configurations you would like to handle with the
     * {@link android.R.attr#configChanges} attribute in your manifest.  If
     * any configuration change occurs that is not selected to be reported
     * by that attribute, then instead of reporting it the system will stop
     * and restart the activity (to have it launched with the new
     * configuration).
     *
     * <p>At the time that this function has been called, your Resources
     * object will have been updated to return resource values matching the
     * new configuration.
     *
     * @param newConfig The new device configuration.
     */
    public void onConfigurationChanged(Configuration newConfig) {
        if (DEBUG_LIFECYCLE) Slog.v(TAG, "onConfigurationChanged " + this + ": " + newConfig);
        mCalled = true;

        mFragments.dispatchConfigurationChanged(newConfig);

        if (mWindow != null) {
            // Pass the configuration changed event to the window
            mWindow.onConfigurationChanged(newConfig);
        }

        if (mActionBar != null) {
            // Do this last; the action bar will need to access
            // view changes from above.
            mActionBar.onConfigurationChanged(newConfig);
        }
    }

    /**
     * If this activity is being destroyed because it can not handle a
     * configuration parameter being changed (and thus its
     * {@link #onConfigurationChanged(Configuration)} method is
     * <em>not</em> being called), then you can use this method to discover
     * the set of changes that have occurred while in the process of being
     * destroyed.  Note that there is no guarantee that these will be
     * accurate (other changes could have happened at any time), so you should
     * only use this as an optimization hint.
     *
     * @return Returns a bit field of the configuration parameters that are
     * changing, as defined by the {@link android.content.res.Configuration}
     * class.
     */
    public int getChangingConfigurations() {
        return mConfigChangeFlags;
    }

    /**
     * Retrieve the non-configuration instance data that was previously
     * returned by {@link #onRetainNonConfigurationInstance()}.  This will
     * be available from the initial {@link #onCreate} and
     * {@link #onStart} calls to the new instance, allowing you to extract
     * any useful dynamic state from the previous instance.
     *
     * <p>Note that the data you retrieve here should <em>only</em> be used
     * as an optimization for handling configuration changes.  You should always
     * be able to handle getting a null pointer back, and an activity must
     * still be able to restore itself to its previous state (through the
     * normal {@link #onSaveInstanceState(Bundle)} mechanism) even if this
     * function returns null.
     *
     * @return Returns the object previously returned by
     * {@link #onRetainNonConfigurationInstance()}.
     *
     * @deprecated Use the new {@link Fragment} API
     * {@link Fragment#setRetainInstance(boolean)} instead; this is also
     * available on older platforms through the Android compatibility package.
     */
    @Nullable
    @Deprecated
    public Object getLastNonConfigurationInstance() {
        return mLastNonConfigurationInstances != null
                ? mLastNonConfigurationInstances.activity : null;
    }

    /**
     * Called by the system, as part of destroying an
     * activity due to a configuration change, when it is known that a new
     * instance will immediately be created for the new configuration.  You
     * can return any object you like here, including the activity instance
     * itself, which can later be retrieved by calling
     * {@link #getLastNonConfigurationInstance()} in the new activity
     * instance.
     *
     * <em>If you are targeting {@link android.os.Build.VERSION_CODES#HONEYCOMB}
     * or later, consider instead using a {@link Fragment} with
     * {@link Fragment#setRetainInstance(boolean)
     * Fragment.setRetainInstance(boolean}.</em>
     *
     * <p>This function is called purely as an optimization, and you must
     * not rely on it being called.  When it is called, a number of guarantees
     * will be made to help optimize configuration switching:
     * <ul>
     * <li> The function will be called between {@link #onStop} and
     * {@link #onDestroy}.
     * <li> A new instance of the activity will <em>always</em> be immediately
     * created after this one's {@link #onDestroy()} is called.  In particular,
     * <em>no</em> messages will be dispatched during this time (when the returned
     * object does not have an activity to be associated with).
     * <li> The object you return here will <em>always</em> be available from
     * the {@link #getLastNonConfigurationInstance()} method of the following
     * activity instance as described there.
     * </ul>
     *
     * <p>These guarantees are designed so that an activity can use this API
     * to propagate extensive state from the old to new activity instance, from
     * loaded bitmaps, to network connections, to evenly actively running
     * threads.  Note that you should <em>not</em> propagate any data that
     * may change based on the configuration, including any data loaded from
     * resources such as strings, layouts, or drawables.
     *
     * <p>The guarantee of no message handling during the switch to the next
     * activity simplifies use with active objects.  For example if your retained
     * state is an {@link android.os.AsyncTask} you are guaranteed that its
     * call back functions (like {@link android.os.AsyncTask#onPostExecute}) will
     * not be called from the call here until you execute the next instance's
     * {@link #onCreate(Bundle)}.  (Note however that there is of course no such
     * guarantee for {@link android.os.AsyncTask#doInBackground} since that is
     * running in a separate thread.)
     *
     * @return Return any Object holding the desired state to propagate to the
     * next activity instance.
     *
     * @deprecated Use the new {@link Fragment} API
     * {@link Fragment#setRetainInstance(boolean)} instead; this is also
     * available on older platforms through the Android compatibility package.
     */
    public Object onRetainNonConfigurationInstance() {
        return null;
    }

    /**
     * Retrieve the non-configuration instance data that was previously
     * returned by {@link #onRetainNonConfigurationChildInstances()}.  This will
     * be available from the initial {@link #onCreate} and
     * {@link #onStart} calls to the new instance, allowing you to extract
     * any useful dynamic state from the previous instance.
     *
     * <p>Note that the data you retrieve here should <em>only</em> be used
     * as an optimization for handling configuration changes.  You should always
     * be able to handle getting a null pointer back, and an activity must
     * still be able to restore itself to its previous state (through the
     * normal {@link #onSaveInstanceState(Bundle)} mechanism) even if this
     * function returns null.
     *
     * @return Returns the object previously returned by
     * {@link #onRetainNonConfigurationChildInstances()}
     */
    @Nullable
    HashMap<String, Object> getLastNonConfigurationChildInstances() {
        return mLastNonConfigurationInstances != null
                ? mLastNonConfigurationInstances.children : null;
    }

    /**
     * This method is similar to {@link #onRetainNonConfigurationInstance()} except that
     * it should return either a mapping from  child activity id strings to arbitrary objects,
     * or null.  This method is intended to be used by Activity framework subclasses that control a
     * set of child activities, such as ActivityGroup.  The same guarantees and restrictions apply
     * as for {@link #onRetainNonConfigurationInstance()}.  The default implementation returns null.
     */
    @Nullable
    HashMap<String,Object> onRetainNonConfigurationChildInstances() {
        return null;
    }

    NonConfigurationInstances retainNonConfigurationInstances() {
        Object activity = onRetainNonConfigurationInstance();
        HashMap<String, Object> children = onRetainNonConfigurationChildInstances();
        ArrayList<Fragment> fragments = mFragments.retainNonConfig();
        boolean retainLoaders = false;
        if (mAllLoaderManagers != null) {
            // prune out any loader managers that were already stopped and so
            // have nothing useful to retain.
            final int N = mAllLoaderManagers.size();
            LoaderManagerImpl loaders[] = new LoaderManagerImpl[N];
            for (int i=N-1; i>=0; i--) {
                loaders[i] = mAllLoaderManagers.valueAt(i);
            }
            for (int i=0; i<N; i++) {
                LoaderManagerImpl lm = loaders[i];
                if (lm.mRetaining) {
                    retainLoaders = true;
                } else {
                    lm.doDestroy();
                    mAllLoaderManagers.remove(lm.mWho);
                }
            }
        }
        if (activity == null && children == null && fragments == null && !retainLoaders
                && mVoiceInteractor == null) {
            return null;
        }

        NonConfigurationInstances nci = new NonConfigurationInstances();
        nci.activity = activity;
        nci.children = children;
        nci.fragments = fragments;
        nci.loaders = mAllLoaderManagers;
        nci.voiceInteractor = mVoiceInteractor;
        return nci;
    }

    public void onLowMemory() {
        if (DEBUG_LIFECYCLE) Slog.v(TAG, "onLowMemory " + this);
        mCalled = true;
        mFragments.dispatchLowMemory();
    }

    public void onTrimMemory(int level) {
        if (DEBUG_LIFECYCLE) Slog.v(TAG, "onTrimMemory " + this + ": " + level);
        mCalled = true;
        mFragments.dispatchTrimMemory(level);
    }

    /**
     * Return the FragmentManager for interacting with fragments associated
     * with this activity.
     */
    public FragmentManager getFragmentManager() {
        return mFragments;
    }

    void invalidateFragment(String who) {
        //Log.v(TAG, "invalidateFragmentIndex: index=" + index);
        if (mAllLoaderManagers != null) {
            LoaderManagerImpl lm = mAllLoaderManagers.get(who);
            if (lm != null && !lm.mRetaining) {
                lm.doDestroy();
                mAllLoaderManagers.remove(who);
            }
        }
    }

    /**
     * Called when a Fragment is being attached to this activity, immediately
     * after the call to its {@link Fragment#onAttach Fragment.onAttach()}
     * method and before {@link Fragment#onCreate Fragment.onCreate()}.
     */
    public void onAttachFragment(Fragment fragment) {
    }

    /**
     * Wrapper around
     * {@link ContentResolver#query(android.net.Uri , String[], String, String[], String)}
     * that gives the resulting {@link Cursor} to call
     * {@link #startManagingCursor} so that the activity will manage its
     * lifecycle for you.
     *
     * <em>If you are targeting {@link android.os.Build.VERSION_CODES#HONEYCOMB}
     * or later, consider instead using {@link LoaderManager} instead, available
     * via {@link #getLoaderManager()}.</em>
     *
     * <p><strong>Warning:</strong> Do not call {@link Cursor#close()} on a cursor obtained using
     * this method, because the activity will do that for you at the appropriate time. However, if
     * you call {@link #stopManagingCursor} on a cursor from a managed query, the system <em>will
     * not</em> automatically close the cursor and, in that case, you must call
     * {@link Cursor#close()}.</p>
     *
     * @param uri The URI of the content provider to query.
     * @param projection List of columns to return.
     * @param selection SQL WHERE clause.
     * @param sortOrder SQL ORDER BY clause.
     *
     * @return The Cursor that was returned by query().
     *
     * @see ContentResolver#query(android.net.Uri , String[], String, String[], String)
     * @see #startManagingCursor
     * @hide
     *
     * @deprecated Use {@link CursorLoader} instead.
     */
    @Deprecated
    public final Cursor managedQuery(Uri uri, String[] projection, String selection,
            String sortOrder) {
        Cursor c = getContentResolver().query(uri, projection, selection, null, sortOrder);
        if (c != null) {
            startManagingCursor(c);
        }
        return c;
    }

    /**
     * Wrapper around
     * {@link ContentResolver#query(android.net.Uri , String[], String, String[], String)}
     * that gives the resulting {@link Cursor} to call
     * {@link #startManagingCursor} so that the activity will manage its
     * lifecycle for you.
     *
     * <em>If you are targeting {@link android.os.Build.VERSION_CODES#HONEYCOMB}
     * or later, consider instead using {@link LoaderManager} instead, available
     * via {@link #getLoaderManager()}.</em>
     *
     * <p><strong>Warning:</strong> Do not call {@link Cursor#close()} on a cursor obtained using
     * this method, because the activity will do that for you at the appropriate time. However, if
     * you call {@link #stopManagingCursor} on a cursor from a managed query, the system <em>will
     * not</em> automatically close the cursor and, in that case, you must call
     * {@link Cursor#close()}.</p>
     *
     * @param uri The URI of the content provider to query.
     * @param projection List of columns to return.
     * @param selection SQL WHERE clause.
     * @param selectionArgs The arguments to selection, if any ?s are pesent
     * @param sortOrder SQL ORDER BY clause.
     *
     * @return The Cursor that was returned by query().
     *
     * @see ContentResolver#query(android.net.Uri , String[], String, String[], String)
     * @see #startManagingCursor
     *
     * @deprecated Use {@link CursorLoader} instead.
     */
    @Deprecated
    public final Cursor managedQuery(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        Cursor c = getContentResolver().query(uri, projection, selection, selectionArgs, sortOrder);
        if (c != null) {
            startManagingCursor(c);
        }
        return c;
    }

    /**
     * This method allows the activity to take care of managing the given
     * {@link Cursor}'s lifecycle for you based on the activity's lifecycle.
     * That is, when the activity is stopped it will automatically call
     * {@link Cursor#deactivate} on the given Cursor, and when it is later restarted
     * it will call {@link Cursor#requery} for you.  When the activity is
     * destroyed, all managed Cursors will be closed automatically.
     *
     * <em>If you are targeting {@link android.os.Build.VERSION_CODES#HONEYCOMB}
     * or later, consider instead using {@link LoaderManager} instead, available
     * via {@link #getLoaderManager()}.</em>
     *
     * <p><strong>Warning:</strong> Do not call {@link Cursor#close()} on cursor obtained from
     * {@link #managedQuery}, because the activity will do that for you at the appropriate time.
     * However, if you call {@link #stopManagingCursor} on a cursor from a managed query, the system
     * <em>will not</em> automatically close the cursor and, in that case, you must call
     * {@link Cursor#close()}.</p>
     *
     * @param c The Cursor to be managed.
     *
     * @see #managedQuery(android.net.Uri , String[], String, String[], String)
     * @see #stopManagingCursor
     *
     * @deprecated Use the new {@link android.content.CursorLoader} class with
     * {@link LoaderManager} instead; this is also
     * available on older platforms through the Android compatibility package.
     */
    @Deprecated
    public void startManagingCursor(Cursor c) {
        synchronized (mManagedCursors) {
            mManagedCursors.add(new ManagedCursor(c));
        }
    }

    /**
     * Given a Cursor that was previously given to
     * {@link #startManagingCursor}, stop the activity's management of that
     * cursor.
     *
     * <p><strong>Warning:</strong> After calling this method on a cursor from a managed query,
     * the system <em>will not</em> automatically close the cursor and you must call
     * {@link Cursor#close()}.</p>
     *
     * @param c The Cursor that was being managed.
     *
     * @see #startManagingCursor
     *
     * @deprecated Use the new {@link android.content.CursorLoader} class with
     * {@link LoaderManager} instead; this is also
     * available on older platforms through the Android compatibility package.
     */
    @Deprecated
    public void stopManagingCursor(Cursor c) {
        synchronized (mManagedCursors) {
            final int N = mManagedCursors.size();
            for (int i=0; i<N; i++) {
                ManagedCursor mc = mManagedCursors.get(i);
                if (mc.mCursor == c) {
                    mManagedCursors.remove(i);
                    break;
                }
            }
        }
    }

    /**
     * @deprecated As of {@link android.os.Build.VERSION_CODES#GINGERBREAD}
     * this is a no-op.
     * @hide
     */
    @Deprecated
    public void setPersistent(boolean isPersistent) {
    }

    /**
     * Finds a view that was identified by the id attribute from the XML that
     * was processed in {@link #onCreate}.
     *
     * @return The view if found or null otherwise.
     */
    public View findViewById(int id) {
        return getWindow().findViewById(id);
    }

    /**
     * Retrieve a reference to this activity's ActionBar.
     *
     * @return The Activity's ActionBar, or null if it does not have one.
     */
    @Nullable
    public ActionBar getActionBar() {
        initWindowDecorActionBar();
        return mActionBar;
    }

    /**
     * Set a {@link android.widget.Toolbar Toolbar} to act as the {@link ActionBar} for this
     * Activity window.
     *
     * <p>When set to a non-null value the {@link #getActionBar()} method will return
     * an {@link ActionBar} object that can be used to control the given toolbar as if it were
     * a traditional window decor action bar. The toolbar's menu will be populated with the
     * Activity's options menu and the navigation button will be wired through the standard
     * {@link android.R.id#home home} menu select action.</p>
     *
     * <p>In order to use a Toolbar within the Activity's window content the application
     * must not request the window feature {@link Window#FEATURE_ACTION_BAR FEATURE_ACTION_BAR}.</p>
     *
     * @param toolbar Toolbar to set as the Activity's action bar
     */
    public void setActionBar(@Nullable Toolbar toolbar) {
        if (getActionBar() instanceof WindowDecorActionBar) {
            throw new IllegalStateException("This Activity already has an action bar supplied " +
                    "by the window decor. Do not request Window.FEATURE_ACTION_BAR and set " +
                    "android:windowActionBar to false in your theme to use a Toolbar instead.");
        }
        mActionBar = new ToolbarActionBar(toolbar, getTitle(), this);
        mActionBar.invalidateOptionsMenu();
    }

    /**
     * Creates a new ActionBar, locates the inflated ActionBarView,
     * initializes the ActionBar with the view, and sets mActionBar.
     */
    private void initWindowDecorActionBar() {
        Window window = getWindow();

        // Initializing the window decor can change window feature flags.
        // Make sure that we have the correct set before performing the test below.
        window.getDecorView();

        if (isChild() || !window.hasFeature(Window.FEATURE_ACTION_BAR) || mActionBar != null) {
            return;
        }

        mActionBar = new WindowDecorActionBar(this);
        mActionBar.setDefaultDisplayHomeAsUpEnabled(mEnableDefaultActionBarUp);

        mWindow.setDefaultIcon(mActivityInfo.getIconResource());
        mWindow.setDefaultLogo(mActivityInfo.getLogoResource());
    }

    /**
     * Set the activity content from a layout resource.  The resource will be
     * inflated, adding all top-level views to the activity.
     *
     * @param layoutResID Resource ID to be inflated.
     *
     * @see #setContentView(android.view.View)
     * @see #setContentView(android.view.View, android.view.ViewGroup.LayoutParams)
     */
    public void setContentView(int layoutResID) {
        getWindow().setContentView(layoutResID);
        initWindowDecorActionBar();
    }

    /**
     * Set the activity content to an explicit view.  This view is placed
     * directly into the activity's view hierarchy.  It can itself be a complex
     * view hierarchy.  When calling this method, the layout parameters of the
     * specified view are ignored.  Both the width and the height of the view are
     * set by default to {@link ViewGroup.LayoutParams#MATCH_PARENT}. To use
     * your own layout parameters, invoke
     * {@link #setContentView(android.view.View, android.view.ViewGroup.LayoutParams)}
     * instead.
     *
     * @param view The desired content to display.
     *
     * @see #setContentView(int)
     * @see #setContentView(android.view.View, android.view.ViewGroup.LayoutParams)
     */
    public void setContentView(View view) {
        getWindow().setContentView(view);
        initWindowDecorActionBar();
    }

    /**
     * Set the activity content to an explicit view.  This view is placed
     * directly into the activity's view hierarchy.  It can itself be a complex
     * view hierarchy.
     *
     * @param view The desired content to display.
     * @param params Layout parameters for the view.
     *
     * @see #setContentView(android.view.View)
     * @see #setContentView(int)
     */
    public void setContentView(View view, ViewGroup.LayoutParams params) {
        getWindow().setContentView(view, params);
        initWindowDecorActionBar();
    }

    /**
     * Add an additional content view to the activity.  Added after any existing
     * ones in the activity -- existing views are NOT removed.
     *
     * @param view The desired content to display.
     * @param params Layout parameters for the view.
     */
    public void addContentView(View view, ViewGroup.LayoutParams params) {
        getWindow().addContentView(view, params);
        initWindowDecorActionBar();
    }

    /**
     * Retrieve the {@link TransitionManager} responsible for default transitions in this window.
     * Requires {@link Window#FEATURE_CONTENT_TRANSITIONS}.
     *
     * <p>This method will return non-null after content has been initialized (e.g. by using
     * {@link #setContentView}) if {@link Window#FEATURE_CONTENT_TRANSITIONS} has been granted.</p>
     *
     * @return This window's content TransitionManager or null if none is set.
     */
    public TransitionManager getContentTransitionManager() {
        return getWindow().getTransitionManager();
    }

    /**
     * Set the {@link TransitionManager} to use for default transitions in this window.
     * Requires {@link Window#FEATURE_CONTENT_TRANSITIONS}.
     *
     * @param tm The TransitionManager to use for scene changes.
     */
    public void setContentTransitionManager(TransitionManager tm) {
        getWindow().setTransitionManager(tm);
    }

    /**
     * Retrieve the {@link Scene} representing this window's current content.
     * Requires {@link Window#FEATURE_CONTENT_TRANSITIONS}.
     *
     * <p>This method will return null if the current content is not represented by a Scene.</p>
     *
     * @return Current Scene being shown or null
     */
    public Scene getContentScene() {
        return getWindow().getContentScene();
    }

    /**
     * Sets whether this activity is finished when touched outside its window's
     * bounds.
     */
    public void setFinishOnTouchOutside(boolean finish) {
        mWindow.setCloseOnTouchOutside(finish);
    }

    /** @hide */
    @IntDef({
            DEFAULT_KEYS_DISABLE,
            DEFAULT_KEYS_DIALER,
            DEFAULT_KEYS_SHORTCUT,
            DEFAULT_KEYS_SEARCH_LOCAL,
            DEFAULT_KEYS_SEARCH_GLOBAL})
    @Retention(RetentionPolicy.SOURCE)
    @interface DefaultKeyMode {}

    /**
     * Use with {@link #setDefaultKeyMode} to turn off default handling of
     * keys.
     *
     * @see #setDefaultKeyMode
     */
    static public final int DEFAULT_KEYS_DISABLE = 0;
    /**
     * Use with {@link #setDefaultKeyMode} to launch the dialer during default
     * key handling.
     *
     * @see #setDefaultKeyMode
     */
    static public final int DEFAULT_KEYS_DIALER = 1;
    /**
     * Use with {@link #setDefaultKeyMode} to execute a menu shortcut in
     * default key handling.
     *
     * <p>That is, the user does not need to hold down the menu key to execute menu shortcuts.
     *
     * @see #setDefaultKeyMode
     */
    static public final int DEFAULT_KEYS_SHORTCUT = 2;
    /**
     * Use with {@link #setDefaultKeyMode} to specify that unhandled keystrokes
     * will start an application-defined search.  (If the application or activity does not
     * actually define a search, the the keys will be ignored.)
     *
     * <p>See {@link android.app.SearchManager android.app.SearchManager} for more details.
     *
     * @see #setDefaultKeyMode
     */
    static public final int DEFAULT_KEYS_SEARCH_LOCAL = 3;

    /**
     * Use with {@link #setDefaultKeyMode} to specify that unhandled keystrokes
     * will start a global search (typically web search, but some platforms may define alternate
     * methods for global search)
     *
     * <p>See {@link android.app.SearchManager android.app.SearchManager} for more details.
     *
     * @see #setDefaultKeyMode
     */
    static public final int DEFAULT_KEYS_SEARCH_GLOBAL = 4;

    /**
     * Select the default key handling for this activity.  This controls what
     * will happen to key events that are not otherwise handled.  The default
     * mode ({@link #DEFAULT_KEYS_DISABLE}) will simply drop them on the
     * floor. Other modes allow you to launch the dialer
     * ({@link #DEFAULT_KEYS_DIALER}), execute a shortcut in your options
     * menu without requiring the menu key be held down
     * ({@link #DEFAULT_KEYS_SHORTCUT}), or launch a search ({@link #DEFAULT_KEYS_SEARCH_LOCAL}
     * and {@link #DEFAULT_KEYS_SEARCH_GLOBAL}).
     *
     * <p>Note that the mode selected here does not impact the default
     * handling of system keys, such as the "back" and "menu" keys, and your
     * activity and its views always get a first chance to receive and handle
     * all application keys.
     *
     * @param mode The desired default key mode constant.
     *
     * @see #DEFAULT_KEYS_DISABLE
     * @see #DEFAULT_KEYS_DIALER
     * @see #DEFAULT_KEYS_SHORTCUT
     * @see #DEFAULT_KEYS_SEARCH_LOCAL
     * @see #DEFAULT_KEYS_SEARCH_GLOBAL
     * @see #onKeyDown
     */
    public final void setDefaultKeyMode(@DefaultKeyMode int mode) {
        mDefaultKeyMode = mode;

        // Some modes use a SpannableStringBuilder to track & dispatch input events
        // This list must remain in sync with the switch in onKeyDown()
        switch (mode) {
        case DEFAULT_KEYS_DISABLE:
        case DEFAULT_KEYS_SHORTCUT:
            mDefaultKeySsb = null;      // not used in these modes
            break;
        case DEFAULT_KEYS_DIALER:
        case DEFAULT_KEYS_SEARCH_LOCAL:
        case DEFAULT_KEYS_SEARCH_GLOBAL:
            mDefaultKeySsb = new SpannableStringBuilder();
            Selection.setSelection(mDefaultKeySsb,0);
            break;
        default:
            throw new IllegalArgumentException();
        }
    }

    /**
     * Called when a key was pressed down and not handled by any of the views
     * inside of the activity. So, for example, key presses while the cursor
     * is inside a TextView will not trigger the event (unless it is a navigation
     * to another object) because TextView handles its own key presses.
     *
     * <p>If the focused view didn't want this event, this method is called.
     *
     * <p>The default implementation takes care of {@link KeyEvent#KEYCODE_BACK}
     * by calling {@link #onBackPressed()}, though the behavior varies based
     * on the application compatibility mode: for
     * {@link android.os.Build.VERSION_CODES#ECLAIR} or later applications,
     * it will set up the dispatch to call {@link #onKeyUp} where the action
     * will be performed; for earlier applications, it will perform the
     * action immediately in on-down, as those versions of the platform
     * behaved.
     *
     * <p>Other additional default key handling may be performed
     * if configured with {@link #setDefaultKeyMode}.
     *
     * @return Return <code>true</code> to prevent this event from being propagated
     * further, or <code>false</code> to indicate that you have not handled
     * this event and it should continue to be propagated.
     * @see #onKeyUp
     * @see android.view.KeyEvent
     */
    public boolean onKeyDown(int keyCode, KeyEvent event)  {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (getApplicationInfo().targetSdkVersion
                    >= Build.VERSION_CODES.ECLAIR) {
                event.startTracking();
            } else {
                onBackPressed();
            }
            return true;
        }

        if (mDefaultKeyMode == DEFAULT_KEYS_DISABLE) {
            return false;
        } else if (mDefaultKeyMode == DEFAULT_KEYS_SHORTCUT) {
            if (getWindow().performPanelShortcut(Window.FEATURE_OPTIONS_PANEL,
                    keyCode, event, Menu.FLAG_ALWAYS_PERFORM_CLOSE)) {
                return true;
            }
            return false;
        } else {
            // Common code for DEFAULT_KEYS_DIALER & DEFAULT_KEYS_SEARCH_*
            boolean clearSpannable = false;
            boolean handled;
            if ((event.getRepeatCount() != 0) || event.isSystem()) {
                clearSpannable = true;
                handled = false;
            } else {
                handled = TextKeyListener.getInstance().onKeyDown(
                        null, mDefaultKeySsb, keyCode, event);
                if (handled && mDefaultKeySsb.length() > 0) {
                    // something useable has been typed - dispatch it now.

                    final String str = mDefaultKeySsb.toString();
                    clearSpannable = true;

                    switch (mDefaultKeyMode) {
                    case DEFAULT_KEYS_DIALER:
                        Intent intent = new Intent(Intent.ACTION_DIAL,  Uri.parse("tel:" + str));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                        break;
                    case DEFAULT_KEYS_SEARCH_LOCAL:
                        startSearch(str, false, null, false);
                        break;
                    case DEFAULT_KEYS_SEARCH_GLOBAL:
                        startSearch(str, false, null, true);
                        break;
                    }
                }
            }
            if (clearSpannable) {
                mDefaultKeySsb.clear();
                mDefaultKeySsb.clearSpans();
                Selection.setSelection(mDefaultKeySsb,0);
            }
            return handled;
        }
    }

    /**
     * Default implementation of {@link KeyEvent.Callback#onKeyLongPress(int, KeyEvent)
     * KeyEvent.Callback.onKeyLongPress()}: always returns false (doesn't handle
     * the event).
     */
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        return false;
    }

    /**
     * Called when a key was released and not handled by any of the views
     * inside of the activity. So, for example, key presses while the cursor
     * is inside a TextView will not trigger the event (unless it is a navigation
     * to another object) because TextView handles its own key presses.
     *
     * <p>The default implementation handles KEYCODE_BACK to stop the activity
     * and go back.
     *
     * @return Return <code>true</code> to prevent this event from being propagated
     * further, or <code>false</code> to indicate that you have not handled
     * this event and it should continue to be propagated.
     * @see #onKeyDown
     * @see KeyEvent
     */
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (getApplicationInfo().targetSdkVersion
                >= Build.VERSION_CODES.ECLAIR) {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.isTracking()
                    && !event.isCanceled()) {
                onBackPressed();
                return true;
            }
        }
        return false;
    }

    /**
     * Default implementation of {@link KeyEvent.Callback#onKeyMultiple(int, int, KeyEvent)
     * KeyEvent.Callback.onKeyMultiple()}: always returns false (doesn't handle
     * the event).
     */
    public boolean onKeyMultiple(int keyCode, int repeatCount, KeyEvent event) {
        return false;
    }

    /**
     * Called when the activity has detected the user's press of the back
     * key.  The default implementation simply finishes the current activity,
     * but you can override this to do whatever you want.
     */
    public void onBackPressed() {
        if (mActionBar != null && mActionBar.collapseActionView()) {
            return;
        }

        if (!mFragments.popBackStackImmediate()) {
            finishAfterTransition();
        }
    }

    /**
     * Called when a key shortcut event is not handled by any of the views in the Activity.
     * Override this method to implement global key shortcuts for the Activity.
     * Key shortcuts can also be implemented by setting the
     * {@link MenuItem#setShortcut(char, char) shortcut} property of menu items.
     *
     * @param keyCode The value in event.getKeyCode().
     * @param event Description of the key event.
     * @return True if the key shortcut was handled.
     */
    public boolean onKeyShortcut(int keyCode, KeyEvent event) {
        return false;
    }

    /**
     * Called when a touch screen event was not handled by any of the views
     * under it.  This is most useful to process touch events that happen
     * outside of your window bounds, where there is no view to receive it.
     *
     * @param event The touch screen event being processed.
     *
     * @return Return true if you have consumed the event, false if you haven't.
     * The default implementation always returns false.
     */
    public boolean onTouchEvent(MotionEvent event) {
        if (mWindow.shouldCloseOnTouch(this, event)) {
            finish();
            return true;
        }

        return false;
    }

    /**
     * Called when the trackball was moved and not handled by any of the
     * views inside of the activity.  So, for example, if the trackball moves
     * while focus is on a button, you will receive a call here because
     * buttons do not normally do anything with trackball events.  The call
     * here happens <em>before</em> trackball movements are converted to
     * DPAD key events, which then get sent back to the view hierarchy, and
     * will be processed at the point for things like focus navigation.
     *
     * @param event The trackball event being processed.
     *
     * @return Return true if you have consumed the event, false if you haven't.
     * The default implementation always returns false.
     */
    public boolean onTrackballEvent(MotionEvent event) {
        return false;
    }

    /**
     * Called when a generic motion event was not handled by any of the
     * views inside of the activity.
     * <p>
     * Generic motion events describe joystick movements, mouse hovers, track pad
     * touches, scroll wheel movements and other input events.  The
     * {@link MotionEvent#getSource() source} of the motion event specifies
     * the class of input that was received.  Implementations of this method
     * must examine the bits in the source before processing the event.
     * The following code example shows how this is done.
     * </p><p>
     * Generic motion events with source class
     * {@link android.view.InputDevice#SOURCE_CLASS_POINTER}
     * are delivered to the view under the pointer.  All other generic motion events are
     * delivered to the focused view.
     * </p><p>
     * See {@link View#onGenericMotionEvent(MotionEvent)} for an example of how to
     * handle this event.
     * </p>
     *
     * @param event The generic motion event being processed.
     *
     * @return Return true if you have consumed the event, false if you haven't.
     * The default implementation always returns false.
     */
    public boolean onGenericMotionEvent(MotionEvent event) {
        return false;
    }

    /**
     * Called whenever a key, touch, or trackball event is dispatched to the
     * activity.  Implement this method if you wish to know that the user has
     * interacted with the device in some way while your activity is running.
     * This callback and {@link #onUserLeaveHint} are intended to help
     * activities manage status bar notifications intelligently; specifically,
     * for helping activities determine the proper time to cancel a notfication.
     *
     * <p>All calls to your activity's {@link #onUserLeaveHint} callback will
     * be accompanied by calls to {@link #onUserInteraction}.  This
     * ensures that your activity will be told of relevant user activity such
     * as pulling down the notification pane and touching an item there.
     *
     * <p>Note that this callback will be invoked for the touch down action
     * that begins a touch gesture, but may not be invoked for the touch-moved
     * and touch-up actions that follow.
     *
     * @see #onUserLeaveHint()
     */
    public void onUserInteraction() {
    }

    public void onWindowAttributesChanged(WindowManager.LayoutParams params) {
        // Update window manager if: we have a view, that view is
        // attached to its parent (which will be a RootView), and
        // this activity is not embedded.
        if (mParent == null) {
            View decor = mDecor;
            if (decor != null && decor.getParent() != null) {
                getWindowManager().updateViewLayout(decor, params);
            }
        }
    }

    public void onContentChanged() {
    }

    /**
     * Called when the current {@link Window} of the activity gains or loses
     * focus.  This is the best indicator of whether this activity is visible
     * to the user.  The default implementation clears the key tracking
     * state, so should always be called.
     *
     * <p>Note that this provides information about global focus state, which
     * is managed independently of activity lifecycles.  As such, while focus
     * changes will generally have some relation to lifecycle changes (an
     * activity that is stopped will not generally get window focus), you
     * should not rely on any particular order between the callbacks here and
     * those in the other lifecycle methods such as {@link #onResume}.
     *
     * <p>As a general rule, however, a resumed activity will have window
     * focus...  unless it has displayed other dialogs or popups that take
     * input focus, in which case the activity itself will not have focus
     * when the other windows have it.  Likewise, the system may display
     * system-level windows (such as the status bar notification panel or
     * a system alert) which will temporarily take window input focus without
     * pausing the foreground activity.
     *
     * @param hasFocus Whether the window of this activity has focus.
     *
     * @see #hasWindowFocus()
     * @see #onResume
     * @see View#onWindowFocusChanged(boolean)
     */
    public void onWindowFocusChanged(boolean hasFocus) {
    }

    /**
     * Called when the main window associated with the activity has been
     * attached to the window manager.
     * See {@link View#onAttachedToWindow() View.onAttachedToWindow()}
     * for more information.
     * @see View#onAttachedToWindow
     */
    public void onAttachedToWindow() {
    }

    /**
     * Called when the main window associated with the activity has been
     * detached from the window manager.
     * See {@link View#onDetachedFromWindow() View.onDetachedFromWindow()}
     * for more information.
     * @see View#onDetachedFromWindow
     */
    public void onDetachedFromWindow() {
    }

    /**
     * Returns true if this activity's <em>main</em> window currently has window focus.
     * Note that this is not the same as the view itself having focus.
     *
     * @return True if this activity's main window currently has window focus.
     *
     * @see #onWindowAttributesChanged(android.view.WindowManager.LayoutParams)
     */
    public boolean hasWindowFocus() {
        Window w = getWindow();
        if (w != null) {
            View d = w.getDecorView();
            if (d != null) {
                return d.hasWindowFocus();
            }
        }
        return false;
    }

    /**
     * Called when the main window associated with the activity has been dismissed.
     * @hide
     */
    @Override
    public void onWindowDismissed() {
        finish();
    }

    /**
     * Called to process key events.  You can override this to intercept all
     * key events before they are dispatched to the window.  Be sure to call
     * this implementation for key events that should be handled normally.
     *
     * @param event The key event.
     *
     * @return boolean Return true if this event was consumed.
     */
    public boolean dispatchKeyEvent(KeyEvent event) {
        onUserInteraction();

        // Let action bars open menus in response to the menu key prioritized over
        // the window handling it
        if (event.getKeyCode() == KeyEvent.KEYCODE_MENU &&
                mActionBar != null && mActionBar.onMenuKeyEvent(event)) {
            return true;
        }

        Window win = getWindow();
        if (win.superDispatchKeyEvent(event)) {
            return true;
        }
        View decor = mDecor;
        if (decor == null) decor = win.getDecorView();
        return event.dispatch(this, decor != null
                ? decor.getKeyDispatcherState() : null, this);
    }

    /**
     * Called to process a key shortcut event.
     * You can override this to intercept all key shortcut events before they are
     * dispatched to the window.  Be sure to call this implementation for key shortcut
     * events that should be handled normally.
     *
     * @param event The key shortcut event.
     * @return True if this event was consumed.
     */
    public boolean dispatchKeyShortcutEvent(KeyEvent event) {
        onUserInteraction();
        if (getWindow().superDispatchKeyShortcutEvent(event)) {
            return true;
        }
        return onKeyShortcut(event.getKeyCode(), event);
    }

    /**
     * Called to process touch screen events.  You can override this to
     * intercept all touch screen events before they are dispatched to the
     * window.  Be sure to call this implementation for touch screen events
     * that should be handled normally.
     *
     * @param ev The touch screen event.
     *
     * @return boolean Return true if this event was consumed.
     */
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            onUserInteraction();
        }
        if (getWindow().superDispatchTouchEvent(ev)) {
            return true;
        }
        return onTouchEvent(ev);
    }

    /**
     * Called to process trackball events.  You can override this to
     * intercept all trackball events before they are dispatched to the
     * window.  Be sure to call this implementation for trackball events
     * that should be handled normally.
     *
     * @param ev The trackball event.
     *
     * @return boolean Return true if this event was consumed.
     */
    public boolean dispatchTrackballEvent(MotionEvent ev) {
        onUserInteraction();
        if (getWindow().superDispatchTrackballEvent(ev)) {
            return true;
        }
        return onTrackballEvent(ev);
    }

    /**
     * Called to process generic motion events.  You can override this to
     * intercept all generic motion events before they are dispatched to the
     * window.  Be sure to call this implementation for generic motion events
     * that should be handled normally.
     *
     * @param ev The generic motion event.
     *
     * @return boolean Return true if this event was consumed.
     */
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        onUserInteraction();
        if (getWindow().superDispatchGenericMotionEvent(ev)) {
            return true;
        }
        return onGenericMotionEvent(ev);
    }

    public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
        event.setClassName(getClass().getName());
        event.setPackageName(getPackageName());

        LayoutParams params = getWindow().getAttributes();
        boolean isFullScreen = (params.width == LayoutParams.MATCH_PARENT) &&
            (params.height == LayoutParams.MATCH_PARENT);
        event.setFullScreen(isFullScreen);

        CharSequence title = getTitle();
        if (!TextUtils.isEmpty(title)) {
           event.getText().add(title);
        }

        return true;
    }

    /**
     * Default implementation of
     * {@link android.view.Window.Callback#onCreatePanelView}
     * for activities. This
     * simply returns null so that all panel sub-windows will have the default
     * menu behavior.
     */
    @Nullable
    public View onCreatePanelView(int featureId) {
        return null;
    }

    /**
     * Default implementation of
     * {@link android.view.Window.Callback#onCreatePanelMenu}
     * for activities.  This calls through to the new
     * {@link #onCreateOptionsMenu} method for the
     * {@link android.view.Window#FEATURE_OPTIONS_PANEL} panel,
     * so that subclasses of Activity don't need to deal with feature codes.
     */
    public boolean onCreatePanelMenu(int featureId, Menu menu) {
        if (featureId == Window.FEATURE_OPTIONS_PANEL) {
            boolean show = onCreateOptionsMenu(menu);
            show |= mFragments.dispatchCreateOptionsMenu(menu, getMenuInflater());
            return show;
        }
        return false;
    }

    /**
     * Default implementation of
     * {@link android.view.Window.Callback#onPreparePanel}
     * for activities.  This
     * calls through to the new {@link #onPrepareOptionsMenu} method for the
     * {@link android.view.Window#FEATURE_OPTIONS_PANEL}
     * panel, so that subclasses of
     * Activity don't need to deal with feature codes.
     */
    public boolean onPreparePanel(int featureId, View view, Menu menu) {
        if (featureId == Window.FEATURE_OPTIONS_PANEL && menu != null) {
            boolean goforit = onPrepareOptionsMenu(menu);
            goforit |= mFragments.dispatchPrepareOptionsMenu(menu);
            return goforit;
        }
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * @return The default implementation returns true.
     */
    public boolean onMenuOpened(int featureId, Menu menu) {
        if (featureId == Window.FEATURE_ACTION_BAR) {
            initWindowDecorActionBar();
            if (mActionBar != null) {
                mActionBar.dispatchMenuVisibilityChanged(true);
            } else {
                Log.e(TAG, "Tried to open action bar menu with no action bar");
            }
        }
        return true;
    }

    /**
     * Default implementation of
     * {@link android.view.Window.Callback#onMenuItemSelected}
     * for activities.  This calls through to the new
     * {@link #onOptionsItemSelected} method for the
     * {@link android.view.Window#FEATURE_OPTIONS_PANEL}
     * panel, so that subclasses of
     * Activity don't need to deal with feature codes.
     */
    public boolean onMenuItemSelected(int featureId, MenuItem item) {
        CharSequence titleCondensed = item.getTitleCondensed();

        switch (featureId) {
            case Window.FEATURE_OPTIONS_PANEL:
                // Put event logging here so it gets called even if subclass
                // doesn't call through to superclass's implmeentation of each
                // of these methods below
                if(titleCondensed != null) {
                    EventLog.writeEvent(50000, 0, titleCondensed.toString());
                }
                if (onOptionsItemSelected(item)) {
                    return true;
                }
                if (mFragments.dispatchOptionsItemSelected(item)) {
                    return true;
                }
                if (item.getItemId() == android.R.id.home && mActionBar != null &&
                        (mActionBar.getDisplayOptions() & ActionBar.DISPLAY_HOME_AS_UP) != 0) {
                    if (mParent == null) {
                        return onNavigateUp();
                    } else {
                        return mParent.onNavigateUpFromChild(this);
                    }
                }
                return false;

            case Window.FEATURE_CONTEXT_MENU:
                if(titleCondensed != null) {
                    EventLog.writeEvent(50000, 1, titleCondensed.toString());
                }
                if (onContextItemSelected(item)) {
                    return true;
                }
                return mFragments.dispatchContextItemSelected(item);

            default:
                return false;
        }
    }

    /**
     * Default implementation of
     * {@link android.view.Window.Callback#onPanelClosed(int, Menu)} for
     * activities. This calls through to {@link #onOptionsMenuClosed(Menu)}
     * method for the {@link android.view.Window#FEATURE_OPTIONS_PANEL} panel,
     * so that subclasses of Activity don't need to deal with feature codes.
     * For context menus ({@link Window#FEATURE_CONTEXT_MENU}), the
     * {@link #onContextMenuClosed(Menu)} will be called.
     */
    public void onPanelClosed(int featureId, Menu menu) {
        switch (featureId) {
            case Window.FEATURE_OPTIONS_PANEL:
                mFragments.dispatchOptionsMenuClosed(menu);
                onOptionsMenuClosed(menu);
                break;

            case Window.FEATURE_CONTEXT_MENU:
                onContextMenuClosed(menu);
                break;

            case Window.FEATURE_ACTION_BAR:
                initWindowDecorActionBar();
                mActionBar.dispatchMenuVisibilityChanged(false);
                break;
        }
    }

    /**
     * Declare that the options menu has changed, so should be recreated.
     * The {@link #onCreateOptionsMenu(Menu)} method will be called the next
     * time it needs to be displayed.
     */
    public void invalidateOptionsMenu() {
        if (mActionBar == null || !mActionBar.invalidateOptionsMenu()) {
            mWindow.invalidatePanelMenu(Window.FEATURE_OPTIONS_PANEL);
        }
    }

    /**
     * Initialize the contents of the Activity's standard options menu.  You
     * should place your menu items in to <var>menu</var>.
     *
     * <p>This is only called once, the first time the options menu is
     * displayed.  To update the menu every time it is displayed, see
     * {@link #onPrepareOptionsMenu}.
     *
     * <p>The default implementation populates the menu with standard system
     * menu items.  These are placed in the {@link Menu#CATEGORY_SYSTEM} group so that
     * they will be correctly ordered with application-defined menu items.
     * Deriving classes should always call through to the base implementation.
     *
     * <p>You can safely hold on to <var>menu</var> (and any items created
     * from it), making modifications to it as desired, until the next
     * time onCreateOptionsMenu() is called.
     *
     * <p>When you add items to the menu, you can implement the Activity's
     * {@link #onOptionsItemSelected} method to handle them there.
     *
     * @param menu The options menu in which you place your items.
     *
     * @return You must return true for the menu to be displayed;
     *         if you return false it will not be shown.
     *
     * @see #onPrepareOptionsMenu
     * @see #onOptionsItemSelected
     */
    public boolean onCreateOptionsMenu(Menu menu) {
        if (mParent != null) {
            return mParent.onCreateOptionsMenu(menu);
        }
        return true;
    }

    /**
     * Prepare the Screen's standard options menu to be displayed.  This is
     * called right before the menu is shown, every time it is shown.  You can
     * use this method to efficiently enable/disable items or otherwise
     * dynamically modify the contents.
     *
     * <p>The default implementation updates the system menu items based on the
     * activity's state.  Deriving classes should always call through to the
     * base class implementation.
     *
     * @param menu The options menu as last shown or first initialized by
     *             onCreateOptionsMenu().
     *
     * @return You must return true for the menu to be displayed;
     *         if you return false it will not be shown.
     *
     * @see #onCreateOptionsMenu
     */
    public boolean onPrepareOptionsMenu(Menu menu) {
        if (mParent != null) {
            return mParent.onPrepareOptionsMenu(menu);
        }
        return true;
    }

    /**
     * This hook is called whenever an item in your options menu is selected.
     * The default implementation simply returns false to have the normal
     * processing happen (calling the item's Runnable or sending a message to
     * its Handler as appropriate).  You can use this method for any items
     * for which you would like to do processing without those other
     * facilities.
     *
     * <p>Derived classes should call through to the base class for it to
     * perform the default menu handling.</p>
     *
     * @param item The menu item that was selected.
     *
     * @return boolean Return false to allow normal menu processing to
     *         proceed, true to consume it here.
     *
     * @see #onCreateOptionsMenu
     */
    public boolean onOptionsItemSelected(MenuItem item) {
        if (mParent != null) {
            return mParent.onOptionsItemSelected(item);
        }
        return false;
    }

    /**
     * This method is called whenever the user chooses to navigate Up within your application's
     * activity hierarchy from the action bar.
     *
     * <p>If the attribute {@link android.R.attr#parentActivityName parentActivityName}
     * was specified in the manifest for this activity or an activity-alias to it,
     * default Up navigation will be handled automatically. If any activity
     * along the parent chain requires extra Intent arguments, the Activity subclass
     * should override the method {@link #onPrepareNavigateUpTaskStack(TaskStackBuilder)}
     * to supply those arguments.</p>
     *
     * <p>See <a href="{@docRoot}guide/topics/fundamentals/tasks-and-back-stack.html">Tasks and Back Stack</a>
     * from the developer guide and <a href="{@docRoot}design/patterns/navigation.html">Navigation</a>
     * from the design guide for more information about navigating within your app.</p>
     *
     * <p>See the {@link TaskStackBuilder} class and the Activity methods
     * {@link #getParentActivityIntent()}, {@link #shouldUpRecreateTask(Intent)}, and
     * {@link #navigateUpTo(Intent)} for help implementing custom Up navigation.
     * The AppNavigation sample application in the Android SDK is also available for reference.</p>
     *
     * @return true if Up navigation completed successfully and this Activity was finished,
     *         false otherwise.
     */
    public boolean onNavigateUp() {
        // Automatically handle hierarchical Up navigation if the proper
        // metadata is available.
        Intent upIntent = getParentActivityIntent();
        if (upIntent != null) {
            if (mActivityInfo.taskAffinity == null) {
                // Activities with a null affinity are special; they really shouldn't
                // specify a parent activity intent in the first place. Just finish
                // the current activity and call it a day.
                finish();
            } else if (shouldUpRecreateTask(upIntent)) {
                TaskStackBuilder b = TaskStackBuilder.create(this);
                onCreateNavigateUpTaskStack(b);
                onPrepareNavigateUpTaskStack(b);
                b.startActivities();

                // We can't finishAffinity if we have a result.
                // Fall back and simply finish the current activity instead.
                if (mResultCode != RESULT_CANCELED || mResultData != null) {
                    // Tell the developer what's going on to avoid hair-pulling.
                    Log.i(TAG, "onNavigateUp only finishing topmost activity to return a result");
                    finish();
                } else {
                    finishAffinity();
                }
            } else {
                navigateUpTo(upIntent);
            }
            return true;
        }
        return false;
    }

    /**
     * This is called when a child activity of this one attempts to navigate up.
     * The default implementation simply calls onNavigateUp() on this activity (the parent).
     *
     * @param child The activity making the call.
     */
    public boolean onNavigateUpFromChild(Activity child) {
        return onNavigateUp();
    }

    /**
     * Define the synthetic task stack that will be generated during Up navigation from
     * a different task.
     *
     * <p>The default implementation of this method adds the parent chain of this activity
     * as specified in the manifest to the supplied {@link TaskStackBuilder}. Applications
     * may choose to override this method to construct the desired task stack in a different
     * way.</p>
     *
     * <p>This method will be invoked by the default implementation of {@link #onNavigateUp()}
     * if {@link #shouldUpRecreateTask(Intent)} returns true when supplied with the intent
     * returned by {@link #getParentActivityIntent()}.</p>
     *
     * <p>Applications that wish to supply extra Intent parameters to the parent stack defined
     * by the manifest should override {@link #onPrepareNavigateUpTaskStack(TaskStackBuilder)}.</p>
     *
     * @param builder An empty TaskStackBuilder - the application should add intents representing
     *                the desired task stack
     */
    public void onCreateNavigateUpTaskStack(TaskStackBuilder builder) {
        builder.addParentStack(this);
    }

    /**
     * Prepare the synthetic task stack that will be generated during Up navigation
     * from a different task.
     *
     * <p>This method receives the {@link TaskStackBuilder} with the constructed series of
     * Intents as generated by {@link #onCreateNavigateUpTaskStack(TaskStackBuilder)}.
     * If any extra data should be added to these intents before launching the new task,
     * the application should override this method and add that data here.</p>
     *
     * @param builder A TaskStackBuilder that has been populated with Intents by
     *                onCreateNavigateUpTaskStack.
     */
    public void onPrepareNavigateUpTaskStack(TaskStackBuilder builder) {
    }

    /**
     * This hook is called whenever the options menu is being closed (either by the user canceling
     * the menu with the back/menu button, or when an item is selected).
     *
     * @param menu The options menu as last shown or first initialized by
     *             onCreateOptionsMenu().
     */
    public void onOptionsMenuClosed(Menu menu) {
        if (mParent != null) {
            mParent.onOptionsMenuClosed(menu);
        }
    }

    /**
     * Programmatically opens the options menu. If the options menu is already
     * open, this method does nothing.
     */
    public void openOptionsMenu() {
        if (mActionBar == null || !mActionBar.openOptionsMenu()) {
            mWindow.openPanel(Window.FEATURE_OPTIONS_PANEL, null);
        }
    }

    /**
     * Progammatically closes the options menu. If the options menu is already
     * closed, this method does nothing.
     */
    public void closeOptionsMenu() {
        mWindow.closePanel(Window.FEATURE_OPTIONS_PANEL);
    }

    /**
     * Called when a context menu for the {@code view} is about to be shown.
     * Unlike {@link #onCreateOptionsMenu(Menu)}, this will be called every
     * time the context menu is about to be shown and should be populated for
     * the view (or item inside the view for {@link AdapterView} subclasses,
     * this can be found in the {@code menuInfo})).
     * <p>
     * Use {@link #onContextItemSelected(android.view.MenuItem)} to know when an
     * item has been selected.
     * <p>
     * It is not safe to hold onto the context menu after this method returns.
     *
     */
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
    }

    /**
     * Registers a context menu to be shown for the given view (multiple views
     * can show the context menu). This method will set the
     * {@link OnCreateContextMenuListener} on the view to this activity, so
     * {@link #onCreateContextMenu(ContextMenu, View, ContextMenuInfo)} will be
     * called when it is time to show the context menu.
     *
     * @see #unregisterForContextMenu(View)
     * @param view The view that should show a context menu.
     */
    public void registerForContextMenu(View view) {
        view.setOnCreateContextMenuListener(this);
    }

    /**
     * Prevents a context menu to be shown for the given view. This method will remove the
     * {@link OnCreateContextMenuListener} on the view.
     *
     * @see #registerForContextMenu(View)
     * @param view The view that should stop showing a context menu.
     */
    public void unregisterForContextMenu(View view) {
        view.setOnCreateContextMenuListener(null);
    }

    /**
     * Programmatically opens the context menu for a particular {@code view}.
     * The {@code view} should have been added via
     * {@link #registerForContextMenu(View)}.
     *
     * @param view The view to show the context menu for.
     */
    public void openContextMenu(View view) {
        view.showContextMenu();
    }

    /**
     * Programmatically closes the most recently opened context menu, if showing.
     */
    public void closeContextMenu() {
        mWindow.closePanel(Window.FEATURE_CONTEXT_MENU);
    }

    /**
     * This hook is called whenever an item in a context menu is selected. The
     * default implementation simply returns false to have the normal processing
     * happen (calling the item's Runnable or sending a message to its Handler
     * as appropriate). You can use this method for any items for which you
     * would like to do processing without those other facilities.
     * <p>
     * Use {@link MenuItem#getMenuInfo()} to get extra information set by the
     * View that added this menu item.
     * <p>
     * Derived classes should call through to the base class for it to perform
     * the default menu handling.
     *
     * @param item The context menu item that was selected.
     * @return boolean Return false to allow normal context menu processing to
     *         proceed, true to consume it here.
     */
    public boolean onContextItemSelected(MenuItem item) {
        if (mParent != null) {
            return mParent.onContextItemSelected(item);
        }
        return false;
    }

    /**
     * This hook is called whenever the context menu is being closed (either by
     * the user canceling the menu with the back/menu button, or when an item is
     * selected).
     *
     * @param menu The context menu that is being closed.
     */
    public void onContextMenuClosed(Menu menu) {
        if (mParent != null) {
            mParent.onContextMenuClosed(menu);
        }
    }

    /**
     * @deprecated Old no-arguments version of {@link #onCreateDialog(int, Bundle)}.
     */
    @Deprecated
    protected Dialog onCreateDialog(int id) {
        return null;
    }

    /**
     * Callback for creating dialogs that are managed (saved and restored) for you
     * by the activity.  The default implementation calls through to
     * {@link #onCreateDialog(int)} for compatibility.
     *
     * <em>If you are targeting {@link android.os.Build.VERSION_CODES#HONEYCOMB}
     * or later, consider instead using a {@link DialogFragment} instead.</em>
     *
     * <p>If you use {@link #showDialog(int)}, the activity will call through to
     * this method the first time, and hang onto it thereafter.  Any dialog
     * that is created by this method will automatically be saved and restored
     * for you, including whether it is showing.
     *
     * <p>If you would like the activity to manage saving and restoring dialogs
     * for you, you should override this method and handle any ids that are
     * passed to {@link #showDialog}.
     *
     * <p>If you would like an opportunity to prepare your dialog before it is shown,
     * override {@link #onPrepareDialog(int, Dialog, Bundle)}.
     *
     * @param id The id of the dialog.
     * @param args The dialog arguments provided to {@link #showDialog(int, Bundle)}.
     * @return The dialog.  If you return null, the dialog will not be created.
     *
     * @see #onPrepareDialog(int, Dialog, Bundle)
     * @see #showDialog(int, Bundle)
     * @see #dismissDialog(int)
     * @see #removeDialog(int)
     *
     * @deprecated Use the new {@link DialogFragment} class with
     * {@link FragmentManager} instead; this is also
     * available on older platforms through the Android compatibility package.
     */
    @Nullable
    @Deprecated
    protected Dialog onCreateDialog(int id, Bundle args) {
        return onCreateDialog(id);
    }

    /**
     * @deprecated Old no-arguments version of
     * {@link #onPrepareDialog(int, Dialog, Bundle)}.
     */
    @Deprecated
    protected void onPrepareDialog(int id, Dialog dialog) {
        dialog.setOwnerActivity(this);
    }

    /**
     * Provides an opportunity to prepare a managed dialog before it is being
     * shown.  The default implementation calls through to
     * {@link #onPrepareDialog(int, Dialog)} for compatibility.
     *
     * <p>
     * Override this if you need to update a managed dialog based on the state
     * of the application each time it is shown. For example, a time picker
     * dialog might want to be updated with the current time. You should call
     * through to the superclass's implementation. The default implementation
     * will set this Activity as the owner activity on the Dialog.
     *
     * @param id The id of the managed dialog.
     * @param dialog The dialog.
     * @param args The dialog arguments provided to {@link #showDialog(int, Bundle)}.
     * @see #onCreateDialog(int, Bundle)
     * @see #showDialog(int)
     * @see #dismissDialog(int)
     * @see #removeDialog(int)
     *
     * @deprecated Use the new {@link DialogFragment} class with
     * {@link FragmentManager} instead; this is also
     * available on older platforms through the Android compatibility package.
     */
    @Deprecated
    protected void onPrepareDialog(int id, Dialog dialog, Bundle args) {
        onPrepareDialog(id, dialog);
    }

    /**
     * Simple version of {@link #showDialog(int, Bundle)} that does not
     * take any arguments.  Simply calls {@link #showDialog(int, Bundle)}
     * with null arguments.
     *
     * @deprecated Use the new {@link DialogFragment} class with
     * {@link FragmentManager} instead; this is also
     * available on older platforms through the Android compatibility package.
     */
    @Deprecated
    public final void showDialog(int id) {
        showDialog(id, null);
    }

    /**
     * Show a dialog managed by this activity.  A call to {@link #onCreateDialog(int, Bundle)}
     * will be made with the same id the first time this is called for a given
     * id.  From thereafter, the dialog will be automatically saved and restored.
     *
     * <em>If you are targeting {@link android.os.Build.VERSION_CODES#HONEYCOMB}
     * or later, consider instead using a {@link DialogFragment} instead.</em>
     *
     * <p>Each time a dialog is shown, {@link #onPrepareDialog(int, Dialog, Bundle)} will
     * be made to provide an opportunity to do any timely preparation.
     *
     * @param id The id of the managed dialog.
     * @param args Arguments to pass through to the dialog.  These will be saved
     * and restored for you.  Note that if the dialog is already created,
     * {@link #onCreateDialog(int, Bundle)} will not be called with the new
     * arguments but {@link #onPrepareDialog(int, Dialog, Bundle)} will be.
     * If you need to rebuild the dialog, call {@link #removeDialog(int)} first.
     * @return Returns true if the Dialog was created; false is returned if
     * it is not created because {@link #onCreateDialog(int, Bundle)} returns false.
     *
     * @see Dialog
     * @see #onCreateDialog(int, Bundle)
     * @see #onPrepareDialog(int, Dialog, Bundle)
     * @see #dismissDialog(int)
     * @see #removeDialog(int)
     *
     * @deprecated Use the new {@link DialogFragment} class with
     * {@link FragmentManager} instead; this is also
     * available on older platforms through the Android compatibility package.
     */
    @Nullable
    @Deprecated
    public final boolean showDialog(int id, Bundle args) {
        if (mManagedDialogs == null) {
            mManagedDialogs = new SparseArray<ManagedDialog>();
        }
        ManagedDialog md = mManagedDialogs.get(id);
        if (md == null) {
            md = new ManagedDialog();
            md.mDialog = createDialog(id, null, args);
            if (md.mDialog == null) {
                return false;
            }
            mManagedDialogs.put(id, md);
        }

        md.mArgs = args;
        onPrepareDialog(id, md.mDialog, args);
        md.mDialog.show();
        return true;
    }

    /**
     * Dismiss a dialog that was previously shown via {@link #showDialog(int)}.
     *
     * @param id The id of the managed dialog.
     *
     * @throws IllegalArgumentException if the id was not previously shown via
     *   {@link #showDialog(int)}.
     *
     * @see #onCreateDialog(int, Bundle)
     * @see #onPrepareDialog(int, Dialog, Bundle)
     * @see #showDialog(int)
     * @see #removeDialog(int)
     *
     * @deprecated Use the new {@link DialogFragment} class with
     * {@link FragmentManager} instead; this is also
     * available on older platforms through the Android compatibility package.
     */
    @Deprecated
    public final void dismissDialog(int id) {
        if (mManagedDialogs == null) {
            throw missingDialog(id);
        }

        final ManagedDialog md = mManagedDialogs.get(id);
        if (md == null) {
            throw missingDialog(id);
        }
        md.mDialog.dismiss();
    }

    /**
     * Creates an exception to throw if a user passed in a dialog id that is
     * unexpected.
     */
    private IllegalArgumentException missingDialog(int id) {
        return new IllegalArgumentException("no dialog with id " + id + " was ever "
                + "shown via Activity#showDialog");
    }

    /**
     * Removes any internal references to a dialog managed by this Activity.
     * If the dialog is showing, it will dismiss it as part of the clean up.
     *
     * <p>This can be useful if you know that you will never show a dialog again and
     * want to avoid the overhead of saving and restoring it in the future.
     *
     * <p>As of {@link android.os.Build.VERSION_CODES#GINGERBREAD}, this function
     * will not throw an exception if you try to remove an ID that does not
     * currently have an associated dialog.</p>
     *
     * @param id The id of the managed dialog.
     *
     * @see #onCreateDialog(int, Bundle)
     * @see #onPrepareDialog(int, Dialog, Bundle)
     * @see #showDialog(int)
     * @see #dismissDialog(int)
     *
     * @deprecated Use the new {@link DialogFragment} class with
     * {@link FragmentManager} instead; this is also
     * available on older platforms through the Android compatibility package.
     */
    @Deprecated
    public final void removeDialog(int id) {
        if (mManagedDialogs != null) {
            final ManagedDialog md = mManagedDialogs.get(id);
            if (md != null) {
                md.mDialog.dismiss();
                mManagedDialogs.remove(id);
            }
        }
    }

    /**
     * This hook is called when the user signals the desire to start a search.
     *
     * <p>You can use this function as a simple way to launch the search UI, in response to a
     * menu item, search button, or other widgets within your activity. Unless overidden,
     * calling this function is the same as calling
     * {@link #startSearch startSearch(null, false, null, false)}, which launches
     * search for the current activity as specified in its manifest, see {@link SearchManager}.
     *
     * <p>You can override this function to force global search, e.g. in response to a dedicated
     * search key, or to block search entirely (by simply returning false).
     *
     * @return Returns {@code true} if search launched, and {@code false} if activity blocks it.
     *         The default implementation always returns {@code true}.
     *
     * @see android.app.SearchManager
     */
    public boolean onSearchRequested() {
        startSearch(null, false, null, false);
        return true;
    }

    /**
     * This hook is called to launch the search UI.
     *
     * <p>It is typically called from onSearchRequested(), either directly from
     * Activity.onSearchRequested() or from an overridden version in any given
     * Activity.  If your goal is simply to activate search, it is preferred to call
     * onSearchRequested(), which may have been overridden elsewhere in your Activity.  If your goal
     * is to inject specific data such as context data, it is preferred to <i>override</i>
     * onSearchRequested(), so that any callers to it will benefit from the override.
     *
     * @param initialQuery Any non-null non-empty string will be inserted as
     * pre-entered text in the search query box.
     * @param selectInitialQuery If true, the initial query will be preselected, which means that
     * any further typing will replace it.  This is useful for cases where an entire pre-formed
     * query is being inserted.  If false, the selection point will be placed at the end of the
     * inserted query.  This is useful when the inserted query is text that the user entered,
     * and the user would expect to be able to keep typing.  <i>This parameter is only meaningful
     * if initialQuery is a non-empty string.</i>
     * @param appSearchData An application can insert application-specific
     * context here, in order to improve quality or specificity of its own
     * searches.  This data will be returned with SEARCH intent(s).  Null if
     * no extra data is required.
     * @param globalSearch If false, this will only launch the search that has been specifically
     * defined by the application (which is usually defined as a local search).  If no default
     * search is defined in the current application or activity, global search will be launched.
     * If true, this will always launch a platform-global (e.g. web-based) search instead.
     *
     * @see android.app.SearchManager
     * @see #onSearchRequested
     */
    public void startSearch(@Nullable String initialQuery, boolean selectInitialQuery,
            @Nullable Bundle appSearchData, boolean globalSearch) {
        ensureSearchManager();
        mSearchManager.startSearch(initialQuery, selectInitialQuery, getComponentName(),
                appSearchData, globalSearch);
    }

    /**
     * Similar to {@link #startSearch}, but actually fires off the search query after invoking
     * the search dialog.  Made available for testing purposes.
     *
     * @param query The query to trigger.  If empty, the request will be ignored.
     * @param appSearchData An application can insert application-specific
     * context here, in order to improve quality or specificity of its own
     * searches.  This data will be returned with SEARCH intent(s).  Null if
     * no extra data is required.
     */
    public void triggerSearch(String query, @Nullable Bundle appSearchData) {
        ensureSearchManager();
        mSearchManager.triggerSearch(query, getComponentName(), appSearchData);
    }

    /**
     * Request that key events come to this activity. Use this if your
     * activity has no views with focus, but the activity still wants
     * a chance to process key events.
     *
     * @see android.view.Window#takeKeyEvents
     */
    public void takeKeyEvents(boolean get) {
        getWindow().takeKeyEvents(get);
    }

    /**
     * Enable extended window features.  This is a convenience for calling
     * {@link android.view.Window#requestFeature getWindow().requestFeature()}.
     *
     * @param featureId The desired feature as defined in
     *                  {@link android.view.Window}.
     * @return Returns true if the requested feature is supported and now
     *         enabled.
     *
     * @see android.view.Window#requestFeature
     */
    public final boolean requestWindowFeature(int featureId) {
        return getWindow().requestFeature(featureId);
    }

    /**
     * Convenience for calling
     * {@link android.view.Window#setFeatureDrawableResource}.
     */
    public final void setFeatureDrawableResource(int featureId, int resId) {
        getWindow().setFeatureDrawableResource(featureId, resId);
    }

    /**
     * Convenience for calling
     * {@link android.view.Window#setFeatureDrawableUri}.
     */
    public final void setFeatureDrawableUri(int featureId, Uri uri) {
        getWindow().setFeatureDrawableUri(featureId, uri);
    }

    /**
     * Convenience for calling
     * {@link android.view.Window#setFeatureDrawable(int, Drawable)}.
     */
    public final void setFeatureDrawable(int featureId, Drawable drawable) {
        getWindow().setFeatureDrawable(featureId, drawable);
    }

    /**
     * Convenience for calling
     * {@link android.view.Window#setFeatureDrawableAlpha}.
     */
    public final void setFeatureDrawableAlpha(int featureId, int alpha) {
        getWindow().setFeatureDrawableAlpha(featureId, alpha);
    }

    /**
     * Convenience for calling
     * {@link android.view.Window#getLayoutInflater}.
     */
    @NonNull
    public LayoutInflater getLayoutInflater() {
        return getWindow().getLayoutInflater();
    }

    /**
     * Returns a {@link MenuInflater} with this context.
     */
    @NonNull
    public MenuInflater getMenuInflater() {
        // Make sure that action views can get an appropriate theme.
        if (mMenuInflater == null) {
            initWindowDecorActionBar();
            if (mActionBar != null) {
                mMenuInflater = new MenuInflater(mActionBar.getThemedContext(), this);
            } else {
                mMenuInflater = new MenuInflater(this);
            }
        }
        return mMenuInflater;
    }

    @Override
    protected void onApplyThemeResource(Resources.Theme theme, int resid,
            boolean first) {
        if (mParent == null) {
            super.onApplyThemeResource(theme, resid, first);
        } else {
            try {
                theme.setTo(mParent.getTheme());
            } catch (Exception e) {
                // Empty
            }
            theme.applyStyle(resid, false);
        }

        // Get the primary color and update the TaskDescription for this activity
        if (theme != null) {
            TypedArray a = theme.obtainStyledAttributes(com.android.internal.R.styleable.Theme);
            int colorPrimary = a.getColor(com.android.internal.R.styleable.Theme_colorPrimary, 0);
            a.recycle();
            if (colorPrimary != 0) {
                ActivityManager.TaskDescription v = new ActivityManager.TaskDescription(null, null,
                        colorPrimary);
                setTaskDescription(v);
            }
        }
    }

    /**
     * Same as calling {@link #startActivityForResult(Intent, int, Bundle)}
     * with no options.
     *
     * @param intent The intent to start.
     * @param requestCode If >= 0, this code will be returned in
     *                    onActivityResult() when the activity exits.
     *
     * @throws android.content.ActivityNotFoundException
     *
     * @see #startActivity
     */
    public void startActivityForResult(Intent intent, int requestCode) {
        startActivityForResult(intent, requestCode, null);
    }

    /**
     * Launch an activity for which you would like a result when it finished.
     * When this activity exits, your
     * onActivityResult() method will be called with the given requestCode.
     * Using a negative requestCode is the same as calling
     * {@link #startActivity} (the activity is not launched as a sub-activity).
     *
     * <p>Note that this method should only be used with Intent protocols
     * that are defined to return a result.  In other protocols (such as
     * {@link Intent#ACTION_MAIN} or {@link Intent#ACTION_VIEW}), you may
     * not get the result when you expect.  For example, if the activity you
     * are launching uses the singleTask launch mode, it will not run in your
     * task and thus you will immediately receive a cancel result.
     *
     * <p>As a special case, if you call startActivityForResult() with a requestCode
     * >= 0 during the initial onCreate(Bundle savedInstanceState)/onResume() of your
     * activity, then your window will not be displayed until a result is
     * returned back from the started activity.  This is to avoid visible
     * flickering when redirecting to another activity.
     *
     * <p>This method throws {@link android.content.ActivityNotFoundException}
     * if there was no Activity found to run the given Intent.
     *
     * @param intent The intent to start.
     * @param requestCode If >= 0, this code will be returned in
     *                    onActivityResult() when the activity exits.
     * @param options Additional options for how the Activity should be started.
     * See {@link android.content.Context#startActivity(Intent, Bundle)
     * Context.startActivity(Intent, Bundle)} for more details.
     *
     * @throws android.content.ActivityNotFoundException
     *
     * @see #startActivity
     */
    public void startActivityForResult(Intent intent, int requestCode, @Nullable Bundle options) {
        if (mParent == null) {
            Instrumentation.ActivityResult ar =
                mInstrumentation.execStartActivity(
                    this, mMainThread.getApplicationThread(), mToken, this,
                    intent, requestCode, options);
            if (ar != null) {
                mMainThread.sendActivityResult(
                    mToken, mEmbeddedID, requestCode, ar.getResultCode(),
                    ar.getResultData());
            }
            if (requestCode >= 0) {
                // If this start is requesting a result, we can avoid making
                // the activity visible until the result is received.  Setting
                // this code during onCreate(Bundle savedInstanceState) or onResume() will keep the
                // activity hidden during this time, to avoid flickering.
                // This can only be done when a result is requested because
                // that guarantees we will get information back when the
                // activity is finished, no matter what happens to it.
                mStartedActivity = true;
            }

            final View decor = mWindow != null ? mWindow.peekDecorView() : null;
            if (decor != null) {
                decor.cancelPendingInputEvents();
            }
            // TODO Consider clearing/flushing other event sources and events for child windows.
        } else {
            if (options != null) {
                mParent.startActivityFromChild(this, intent, requestCode, options);
            } else {
                // Note we want to go through this method for compatibility with
                // existing applications that may have overridden it.
                mParent.startActivityFromChild(this, intent, requestCode);
            }
        }
        if (options != null && !isTopOfTask()) {
            mActivityTransitionState.startExitOutTransition(this, options);
        }
    }

    /**
     * @hide Implement to provide correct calling token.
     */
    public void startActivityForResultAsUser(Intent intent, int requestCode, UserHandle user) {
        startActivityForResultAsUser(intent, requestCode, null, user);
    }

    /**
     * @hide Implement to provide correct calling token.
     */
    public void startActivityForResultAsUser(Intent intent, int requestCode,
            @Nullable Bundle options, UserHandle user) {
        if (options != null) {
            mActivityTransitionState.startExitOutTransition(this, options);
        }
        if (mParent != null) {
            throw new RuntimeException("Can't be called from a child");
        }
        Instrumentation.ActivityResult ar = mInstrumentation.execStartActivity(
                this, mMainThread.getApplicationThread(), mToken, this, intent, requestCode,
                options, user);
        if (ar != null) {
            mMainThread.sendActivityResult(
                mToken, mEmbeddedID, requestCode, ar.getResultCode(), ar.getResultData());
        }
        if (requestCode >= 0) {
            // If this start is requesting a result, we can avoid making
            // the activity visible until the result is received.  Setting
            // this code during onCreate(Bundle savedInstanceState) or onResume() will keep the
            // activity hidden during this time, to avoid flickering.
            // This can only be done when a result is requested because
            // that guarantees we will get information back when the
            // activity is finished, no matter what happens to it.
            mStartedActivity = true;
        }

        final View decor = mWindow != null ? mWindow.peekDecorView() : null;
        if (decor != null) {
            decor.cancelPendingInputEvents();
        }
    }

    /**
     * @hide Implement to provide correct calling token.
     */
    public void startActivityAsUser(Intent intent, UserHandle user) {
        startActivityAsUser(intent, null, user);
    }

    /**
     * @hide Implement to provide correct calling token.
     */
    public void startActivityAsUser(Intent intent, Bundle options, UserHandle user) {
        if (mParent != null) {
            throw new RuntimeException("Can't be called from a child");
        }
        Instrumentation.ActivityResult ar =
                mInstrumentation.execStartActivity(
                        this, mMainThread.getApplicationThread(), mToken, this,
                        intent, -1, options, user);
        if (ar != null) {
            mMainThread.sendActivityResult(
                mToken, mEmbeddedID, -1, ar.getResultCode(),
                ar.getResultData());
        }
    }

    /**
     * Start a new activity as if it was started by the activity that started our
     * current activity.  This is for the resolver and chooser activities, which operate
     * as intermediaries that dispatch their intent to the target the user selects -- to
     * do this, they must perform all security checks including permission grants as if
     * their launch had come from the original activity.
     * @hide
     */
    public void startActivityAsCaller(Intent intent, @Nullable Bundle options) {
        if (mParent != null) {
            throw new RuntimeException("Can't be called from a child");
        }
        Instrumentation.ActivityResult ar =
                mInstrumentation.execStartActivityAsCaller(
                        this, mMainThread.getApplicationThread(), mToken, this,
                        intent, -1, options);
        if (ar != null) {
            mMainThread.sendActivityResult(
                mToken, mEmbeddedID, -1, ar.getResultCode(),
                ar.getResultData());
        }
    }

    /**
     * Same as calling {@link #startIntentSenderForResult(IntentSender, int,
     * Intent, int, int, int, Bundle)} with no options.
     *
     * @param intent The IntentSender to launch.
     * @param requestCode If >= 0, this code will be returned in
     *                    onActivityResult() when the activity exits.
     * @param fillInIntent If non-null, this will be provided as the
     * intent parameter to {@link IntentSender#sendIntent}.
     * @param flagsMask Intent flags in the original IntentSender that you
     * would like to change.
     * @param flagsValues Desired values for any bits set in
     * <var>flagsMask</var>
     * @param extraFlags Always set to 0.
     */
    public void startIntentSenderForResult(IntentSender intent, int requestCode,
            @Nullable Intent fillInIntent, int flagsMask, int flagsValues, int extraFlags)
            throws IntentSender.SendIntentException {
        startIntentSenderForResult(intent, requestCode, fillInIntent, flagsMask,
                flagsValues, extraFlags, null);
    }

    /**
     * Like {@link #startActivityForResult(Intent, int)}, but allowing you
     * to use a IntentSender to describe the activity to be started.  If
     * the IntentSender is for an activity, that activity will be started
     * as if you had called the regular {@link #startActivityForResult(Intent, int)}
     * here; otherwise, its associated action will be executed (such as
     * sending a broadcast) as if you had called
     * {@link IntentSender#sendIntent IntentSender.sendIntent} on it.
     *
     * @param intent The IntentSender to launch.
     * @param requestCode If >= 0, this code will be returned in
     *                    onActivityResult() when the activity exits.
     * @param fillInIntent If non-null, this will be provided as the
     * intent parameter to {@link IntentSender#sendIntent}.
     * @param flagsMask Intent flags in the original IntentSender that you
     * would like to change.
     * @param flagsValues Desired values for any bits set in
     * <var>flagsMask</var>
     * @param extraFlags Always set to 0.
     * @param options Additional options for how the Activity should be started.
     * See {@link android.content.Context#startActivity(Intent, Bundle)
     * Context.startActivity(Intent, Bundle)} for more details.  If options
     * have also been supplied by the IntentSender, options given here will
     * override any that conflict with those given by the IntentSender.
     */
    public void startIntentSenderForResult(IntentSender intent, int requestCode,
            @Nullable Intent fillInIntent, int flagsMask, int flagsValues, int extraFlags,
            Bundle options) throws IntentSender.SendIntentException {
        if (mParent == null) {
            startIntentSenderForResultInner(intent, requestCode, fillInIntent,
                    flagsMask, flagsValues, this, options);
        } else if (options != null) {
            mParent.startIntentSenderFromChild(this, intent, requestCode,
                    fillInIntent, flagsMask, flagsValues, extraFlags, options);
        } else {
            // Note we want to go through this call for compatibility with
            // existing applications that may have overridden the method.
            mParent.startIntentSenderFromChild(this, intent, requestCode,
                    fillInIntent, flagsMask, flagsValues, extraFlags);
        }
    }

    private void startIntentSenderForResultInner(IntentSender intent, int requestCode,
            Intent fillInIntent, int flagsMask, int flagsValues, Activity activity,
            Bundle options)
            throws IntentSender.SendIntentException {
        try {
            String resolvedType = null;
            if (fillInIntent != null) {
                fillInIntent.migrateExtraStreamToClipData();
                fillInIntent.prepareToLeaveProcess();
                resolvedType = fillInIntent.resolveTypeIfNeeded(getContentResolver());
            }
            int result = ActivityManagerNative.getDefault()
                .startActivityIntentSender(mMainThread.getApplicationThread(), intent,
                        fillInIntent, resolvedType, mToken, activity.mEmbeddedID,
                        requestCode, flagsMask, flagsValues, options);
            if (result == ActivityManager.START_CANCELED) {
                throw new IntentSender.SendIntentException();
            }
            Instrumentation.checkStartActivityResult(result, null);
        } catch (RemoteException e) {
        }
        if (requestCode >= 0) {
            // If this start is requesting a result, we can avoid making
            // the activity visible until the result is received.  Setting
            // this code during onCreate(Bundle savedInstanceState) or onResume() will keep the
            // activity hidden during this time, to avoid flickering.
            // This can only be done when a result is requested because
            // that guarantees we will get information back when the
            // activity is finished, no matter what happens to it.
            mStartedActivity = true;
        }
    }

    /**
     * Same as {@link #startActivity(Intent, Bundle)} with no options
     * specified.
     *
     * @param intent The intent to start.
     *
     * @throws android.content.ActivityNotFoundException
     *
     * @see {@link #startActivity(Intent, Bundle)}
     * @see #startActivityForResult
     */
    @Override
    public void startActivity(Intent intent) {
        this.startActivity(intent, null);
    }

    /**
     * Launch a new activity.  You will not receive any information about when
     * the activity exits.  This implementation overrides the base version,
     * providing information about
     * the activity performing the launch.  Because of this additional
     * information, the {@link Intent#FLAG_ACTIVITY_NEW_TASK} launch flag is not
     * required; if not specified, the new activity will be added to the
     * task of the caller.
     *
     * <p>This method throws {@link android.content.ActivityNotFoundException}
     * if there was no Activity found to run the given Intent.
     *
     * @param intent The intent to start.
     * @param options Additional options for how the Activity should be started.
     * See {@link android.content.Context#startActivity(Intent, Bundle)
     * Context.startActivity(Intent, Bundle)} for more details.
     *
     * @throws android.content.ActivityNotFoundException
     *
     * @see {@link #startActivity(Intent)}
     * @see #startActivityForResult
     */
    @Override
    public void startActivity(Intent intent, @Nullable Bundle options) {
        if (options != null) {
            startActivityForResult(intent, -1, options);
        } else {
            // Note we want to go through this call for compatibility with
            // applications that may have overridden the method.
            startActivityForResult(intent, -1);
        }
    }

    /**
     * Same as {@link #startActivities(Intent[], Bundle)} with no options
     * specified.
     *
     * @param intents The intents to start.
     *
     * @throws android.content.ActivityNotFoundException
     *
     * @see {@link #startActivities(Intent[], Bundle)}
     * @see #startActivityForResult
     */
    @Override
    public void startActivities(Intent[] intents) {
        startActivities(intents, null);
    }

    /**
     * Launch a new activity.  You will not receive any information about when
     * the activity exits.  This implementation overrides the base version,
     * providing information about
     * the activity performing the launch.  Because of this additional
     * information, the {@link Intent#FLAG_ACTIVITY_NEW_TASK} launch flag is not
     * required; if not specified, the new activity will be added to the
     * task of the caller.
     *
     * <p>This method throws {@link android.content.ActivityNotFoundException}
     * if there was no Activity found to run the given Intent.
     *
     * @param intents The intents to start.
     * @param options Additional options for how the Activity should be started.
     * See {@link android.content.Context#startActivity(Intent, Bundle)
     * Context.startActivity(Intent, Bundle)} for more details.
     *
     * @throws android.content.ActivityNotFoundException
     *
     * @see {@link #startActivities(Intent[])}
     * @see #startActivityForResult
     */
    @Override
    public void startActivities(Intent[] intents, @Nullable Bundle options) {
        mInstrumentation.execStartActivities(this, mMainThread.getApplicationThread(),
                mToken, this, intents, options);
    }

    /**
     * Same as calling {@link #startIntentSender(IntentSender, Intent, int, int, int, Bundle)}
     * with no options.
     *
     * @param intent The IntentSender to launch.
     * @param fillInIntent If non-null, this will be provided as the
     * intent parameter to {@link IntentSender#sendIntent}.
     * @param flagsMask Intent flags in the original IntentSender that you
     * would like to change.
     * @param flagsValues Desired values for any bits set in
     * <var>flagsMask</var>
     * @param extraFlags Always set to 0.
     */
    public void startIntentSender(IntentSender intent,
            @Nullable Intent fillInIntent, int flagsMask, int flagsValues, int extraFlags)
            throws IntentSender.SendIntentException {
        startIntentSender(intent, fillInIntent, flagsMask, flagsValues,
                extraFlags, null);
    }

    /**
     * Like {@link #startActivity(Intent, Bundle)}, but taking a IntentSender
     * to start; see
     * {@link #startIntentSenderForResult(IntentSender, int, Intent, int, int, int, Bundle)}
     * for more information.
     *
     * @param intent The IntentSender to launch.
     * @param fillInIntent If non-null, this will be provided as the
     * intent parameter to {@link IntentSender#sendIntent}.
     * @param flagsMask Intent flags in the original IntentSender that you
     * would like to change.
     * @param flagsValues Desired values for any bits set in
     * <var>flagsMask</var>
     * @param extraFlags Always set to 0.
     * @param options Additional options for how the Activity should be started.
     * See {@link android.content.Context#startActivity(Intent, Bundle)
     * Context.startActivity(Intent, Bundle)} for more details.  If options
     * have also been supplied by the IntentSender, options given here will
     * override any that conflict with those given by the IntentSender.
     */
    public void startIntentSender(IntentSender intent,
            @Nullable Intent fillInIntent, int flagsMask, int flagsValues, int extraFlags,
            Bundle options) throws IntentSender.SendIntentException {
        if (options != null) {
            startIntentSenderForResult(intent, -1, fillInIntent, flagsMask,
                    flagsValues, extraFlags, options);
        } else {
            // Note we want to go through this call for compatibility with
            // applications that may have overridden the method.
            startIntentSenderForResult(intent, -1, fillInIntent, flagsMask,
                    flagsValues, extraFlags);
        }
    }

    /**
     * Same as calling {@link #startActivityIfNeeded(Intent, int, Bundle)}
     * with no options.
     *
     * @param intent The intent to start.
     * @param requestCode If >= 0, this code will be returned in
     *         onActivityResult() when the activity exits, as described in
     *         {@link #startActivityForResult}.
     *
     * @return If a new activity was launched then true is returned; otherwise
     *         false is returned and you must handle the Intent yourself.
     *
     * @see #startActivity
     * @see #startActivityForResult
     */
    public boolean startActivityIfNeeded(@NonNull Intent intent, int requestCode) {
        return startActivityIfNeeded(intent, requestCode, null);
    }

    /**
     * A special variation to launch an activity only if a new activity
     * instance is needed to handle the given Intent.  In other words, this is
     * just like {@link #startActivityForResult(Intent, int)} except: if you are
     * using the {@link Intent#FLAG_ACTIVITY_SINGLE_TOP} flag, or
     * singleTask or singleTop
     * {@link android.R.styleable#AndroidManifestActivity_launchMode launchMode},
     * and the activity
     * that handles <var>intent</var> is the same as your currently running
     * activity, then a new instance is not needed.  In this case, instead of
     * the normal behavior of calling {@link #onNewIntent} this function will
     * return and you can handle the Intent yourself.
     *
     * <p>This function can only be called from a top-level activity; if it is
     * called from a child activity, a runtime exception will be thrown.
     *
     * @param intent The intent to start.
     * @param requestCode If >= 0, this code will be returned in
     *         onActivityResult() when the activity exits, as described in
     *         {@link #startActivityForResult}.
     * @param options Additional options for how the Activity should be started.
     * See {@link android.content.Context#startActivity(Intent, Bundle)
     * Context.startActivity(Intent, Bundle)} for more details.
     *
     * @return If a new activity was launched then true is returned; otherwise
     *         false is returned and you must handle the Intent yourself.
     *
     * @see #startActivity
     * @see #startActivityForResult
     */
    public boolean startActivityIfNeeded(@NonNull Intent intent, int requestCode,
            @Nullable Bundle options) {
        if (mParent == null) {
            int result = ActivityManager.START_RETURN_INTENT_TO_CALLER;
            try {
                intent.migrateExtraStreamToClipData();
                intent.prepareToLeaveProcess();
                result = ActivityManagerNative.getDefault()
                    .startActivity(mMainThread.getApplicationThread(), getBasePackageName(),
                            intent, intent.resolveTypeIfNeeded(getContentResolver()),
                            mToken, mEmbeddedID, requestCode,
                            ActivityManager.START_FLAG_ONLY_IF_NEEDED, null, null,
                            options);
            } catch (RemoteException e) {
                // Empty
            }

            Instrumentation.checkStartActivityResult(result, intent);

            if (requestCode >= 0) {
                // If this start is requesting a result, we can avoid making
                // the activity visible until the result is received.  Setting
                // this code during onCreate(Bundle savedInstanceState) or onResume() will keep the
                // activity hidden during this time, to avoid flickering.
                // This can only be done when a result is requested because
                // that guarantees we will get information back when the
                // activity is finished, no matter what happens to it.
                mStartedActivity = true;
            }
            return result != ActivityManager.START_RETURN_INTENT_TO_CALLER;
        }

        throw new UnsupportedOperationException(
            "startActivityIfNeeded can only be called from a top-level activity");
    }

    /**
     * Same as calling {@link #startNextMatchingActivity(Intent, Bundle)} with
     * no options.
     *
     * @param intent The intent to dispatch to the next activity.  For
     * correct behavior, this must be the same as the Intent that started
     * your own activity; the only changes you can make are to the extras
     * inside of it.
     *
     * @return Returns a boolean indicating whether there was another Activity
     * to start: true if there was a next activity to start, false if there
     * wasn't.  In general, if true is returned you will then want to call
     * finish() on yourself.
     */
    public boolean startNextMatchingActivity(@NonNull Intent intent) {
        return startNextMatchingActivity(intent, null);
    }

    /**
     * Special version of starting an activity, for use when you are replacing
     * other activity components.  You can use this to hand the Intent off
     * to the next Activity that can handle it.  You typically call this in
     * {@link #onCreate} with the Intent returned by {@link #getIntent}.
     *
     * @param intent The intent to dispatch to the next activity.  For
     * correct behavior, this must be the same as the Intent that started
     * your own activity; the only changes you can make are to the extras
     * inside of it.
     * @param options Additional options for how the Activity should be started.
     * See {@link android.content.Context#startActivity(Intent, Bundle)
     * Context.startActivity(Intent, Bundle)} for more details.
     *
     * @return Returns a boolean indicating whether there was another Activity
     * to start: true if there was a next activity to start, false if there
     * wasn't.  In general, if true is returned you will then want to call
     * finish() on yourself.
     */
    public boolean startNextMatchingActivity(@NonNull Intent intent, @Nullable Bundle options) {
        if (mParent == null) {
            try {
                intent.migrateExtraStreamToClipData();
                intent.prepareToLeaveProcess();
                return ActivityManagerNative.getDefault()
                    .startNextMatchingActivity(mToken, intent, options);
            } catch (RemoteException e) {
                // Empty
            }
            return false;
        }

        throw new UnsupportedOperationException(
            "startNextMatchingActivity can only be called from a top-level activity");
    }

    /**
     * Same as calling {@link #startActivityFromChild(Activity, Intent, int, Bundle)}
     * with no options.
     *
     * @param child The activity making the call.
     * @param intent The intent to start.
     * @param requestCode Reply request code.  < 0 if reply is not requested.
     *
     * @throws android.content.ActivityNotFoundException
     *
     * @see #startActivity
     * @see #startActivityForResult
     */
    public void startActivityFromChild(@NonNull Activity child, Intent intent,
            int requestCode) {
        startActivityFromChild(child, intent, requestCode, null);
    }

    /**
     * This is called when a child activity of this one calls its
     * {@link #startActivity} or {@link #startActivityForResult} method.
     *
     * <p>This method throws {@link android.content.ActivityNotFoundException}
     * if there was no Activity found to run the given Intent.
     *
     * @param child The activity making the call.
     * @param intent The intent to start.
     * @param requestCode Reply request code.  < 0 if reply is not requested.
     * @param options Additional options for how the Activity should be started.
     * See {@link android.content.Context#startActivity(Intent, Bundle)
     * Context.startActivity(Intent, Bundle)} for more details.
     *
     * @throws android.content.ActivityNotFoundException
     *
     * @see #startActivity
     * @see #startActivityForResult
     */
    public void startActivityFromChild(@NonNull Activity child, Intent intent,
            int requestCode, @Nullable Bundle options) {
        Instrumentation.ActivityResult ar =
            mInstrumentation.execStartActivity(
                this, mMainThread.getApplicationThread(), mToken, child,
                intent, requestCode, options);
        if (ar != null) {
            mMainThread.sendActivityResult(
                mToken, child.mEmbeddedID, requestCode,
                ar.getResultCode(), ar.getResultData());
        }
    }

    /**
     * Same as calling {@link #startActivityFromFragment(Fragment, Intent, int, Bundle)}
     * with no options.
     *
     * @param fragment The fragment making the call.
     * @param intent The intent to start.
     * @param requestCode Reply request code.  < 0 if reply is not requested.
     *
     * @throws android.content.ActivityNotFoundException
     *
     * @see Fragment#startActivity
     * @see Fragment#startActivityForResult
     */
    public void startActivityFromFragment(@NonNull Fragment fragment, Intent intent,
            int requestCode) {
        startActivityFromFragment(fragment, intent, requestCode, null);
    }

    /**
     * This is called when a Fragment in this activity calls its
     * {@link Fragment#startActivity} or {@link Fragment#startActivityForResult}
     * method.
     *
     * <p>This method throws {@link android.content.ActivityNotFoundException}
     * if there was no Activity found to run the given Intent.
     *
     * @param fragment The fragment making the call.
     * @param intent The intent to start.
     * @param requestCode Reply request code.  < 0 if reply is not requested.
     * @param options Additional options for how the Activity should be started.
     * See {@link android.content.Context#startActivity(Intent, Bundle)
     * Context.startActivity(Intent, Bundle)} for more details.
     *
     * @throws android.content.ActivityNotFoundException
     *
     * @see Fragment#startActivity
     * @see Fragment#startActivityForResult
     */
    public void startActivityFromFragment(@NonNull Fragment fragment, Intent intent,
            int requestCode, @Nullable Bundle options) {
        if (options != null) {
            mActivityTransitionState.startExitOutTransition(this, options);
        }
        Instrumentation.ActivityResult ar =
            mInstrumentation.execStartActivity(
                this, mMainThread.getApplicationThread(), mToken, fragment,
                intent, requestCode, options);
        if (ar != null) {
            mMainThread.sendActivityResult(
                mToken, fragment.mWho, requestCode,
                ar.getResultCode(), ar.getResultData());
        }
    }

    /**
     * Same as calling {@link #startIntentSenderFromChild(Activity, IntentSender,
     * int, Intent, int, int, int, Bundle)} with no options.
     */
    public void startIntentSenderFromChild(Activity child, IntentSender intent,
            int requestCode, Intent fillInIntent, int flagsMask, int flagsValues,
            int extraFlags)
            throws IntentSender.SendIntentException {
        startIntentSenderFromChild(child, intent, requestCode, fillInIntent,
                flagsMask, flagsValues, extraFlags, null);
    }

    /**
     * Like {@link #startActivityFromChild(Activity, Intent, int)}, but
     * taking a IntentSender; see
     * {@link #startIntentSenderForResult(IntentSender, int, Intent, int, int, int)}
     * for more information.
     */
    public void startIntentSenderFromChild(Activity child, IntentSender intent,
            int requestCode, Intent fillInIntent, int flagsMask, int flagsValues,
            int extraFlags, @Nullable Bundle options)
            throws IntentSender.SendIntentException {
        startIntentSenderForResultInner(intent, requestCode, fillInIntent,
                flagsMask, flagsValues, child, options);
    }

    /**
     * Call immediately after one of the flavors of {@link #startActivity(Intent)}
     * or {@link #finish} to specify an explicit transition animation to
     * perform next.
     *
     * <p>As of {@link android.os.Build.VERSION_CODES#JELLY_BEAN} an alternative
     * to using this with starting activities is to supply the desired animation
     * information through a {@link ActivityOptions} bundle to
     * {@link #startActivity(Intent, Bundle) or a related function.  This allows
     * you to specify a custom animation even when starting an activity from
     * outside the context of the current top activity.
     *
     * @param enterAnim A resource ID of the animation resource to use for
     * the incoming activity.  Use 0 for no animation.
     * @param exitAnim A resource ID of the animation resource to use for
     * the outgoing activity.  Use 0 for no animation.
     */
    public void overridePendingTransition(int enterAnim, int exitAnim) {
        try {
            ActivityManagerNative.getDefault().overridePendingTransition(
                    mToken, getPackageName(), enterAnim, exitAnim);
        } catch (RemoteException e) {
        }
    }

    /**
     * Call this to set the result that your activity will return to its
     * caller.
     *
     * @param resultCode The result code to propagate back to the originating
     *                   activity, often RESULT_CANCELED or RESULT_OK
     *
     * @see #RESULT_CANCELED
     * @see #RESULT_OK
     * @see #RESULT_FIRST_USER
     * @see #setResult(int, Intent)
     */
    public final void setResult(int resultCode) {
        synchronized (this) {
            mResultCode = resultCode;
            mResultData = null;
        }
    }

    /**
     * Call this to set the result that your activity will return to its
     * caller.
     *
     * <p>As of {@link android.os.Build.VERSION_CODES#GINGERBREAD}, the Intent
     * you supply here can have {@link Intent#FLAG_GRANT_READ_URI_PERMISSION
     * Intent.FLAG_GRANT_READ_URI_PERMISSION} and/or {@link Intent#FLAG_GRANT_WRITE_URI_PERMISSION
     * Intent.FLAG_GRANT_WRITE_URI_PERMISSION} set.  This will grant the
     * Activity receiving the result access to the specific URIs in the Intent.
     * Access will remain until the Activity has finished (it will remain across the hosting
     * process being killed and other temporary destruction) and will be added
     * to any existing set of URI permissions it already holds.
     *
     * @param resultCode The result code to propagate back to the originating
     *                   activity, often RESULT_CANCELED or RESULT_OK
     * @param data The data to propagate back to the originating activity.
     *
     * @see #RESULT_CANCELED
     * @see #RESULT_OK
     * @see #RESULT_FIRST_USER
     * @see #setResult(int)
     */
    public final void setResult(int resultCode, Intent data) {
        synchronized (this) {
            mResultCode = resultCode;
            mResultData = data;
        }
    }

    /**
     * Return the name of the package that invoked this activity.  This is who
     * the data in {@link #setResult setResult()} will be sent to.  You can
     * use this information to validate that the recipient is allowed to
     * receive the data.
     *
     * <p class="note">Note: if the calling activity is not expecting a result (that is it
     * did not use the {@link #startActivityForResult}
     * form that includes a request code), then the calling package will be
     * null.</p>
     *
     * <p class="note">Note: prior to {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR2},
     * the result from this method was unstable.  If the process hosting the calling
     * package was no longer running, it would return null instead of the proper package
     * name.  You can use {@link #getCallingActivity()} and retrieve the package name
     * from that instead.</p>
     *
     * @return The package of the activity that will receive your
     *         reply, or null if none.
     */
    @Nullable
    public String getCallingPackage() {
        try {
            return ActivityManagerNative.getDefault().getCallingPackage(mToken);
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Return the name of the activity that invoked this activity.  This is
     * who the data in {@link #setResult setResult()} will be sent to.  You
     * can use this information to validate that the recipient is allowed to
     * receive the data.
     *
     * <p class="note">Note: if the calling activity is not expecting a result (that is it
     * did not use the {@link #startActivityForResult}
     * form that includes a request code), then the calling package will be
     * null.
     *
     * @return The ComponentName of the activity that will receive your
     *         reply, or null if none.
     */
    @Nullable
    public ComponentName getCallingActivity() {
        try {
            return ActivityManagerNative.getDefault().getCallingActivity(mToken);
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Control whether this activity's main window is visible.  This is intended
     * only for the special case of an activity that is not going to show a
     * UI itself, but can't just finish prior to onResume() because it needs
     * to wait for a service binding or such.  Setting this to false allows
     * you to prevent your UI from being shown during that time.
     *
     * <p>The default value for this is taken from the
     * {@link android.R.attr#windowNoDisplay} attribute of the activity's theme.
     */
    public void setVisible(boolean visible) {
        if (mVisibleFromClient != visible) {
            mVisibleFromClient = visible;
            if (mVisibleFromServer) {
                if (visible) makeVisible();
                else mDecor.setVisibility(View.INVISIBLE);
            }
        }
    }

    void makeVisible() {
        if (!mWindowAdded) {
            ViewManager wm = getWindowManager();
            wm.addView(mDecor, getWindow().getAttributes());
            mWindowAdded = true;
        }
        mDecor.setVisibility(View.VISIBLE);
    }

    /**
     * Check to see whether this activity is in the process of finishing,
     * either because you called {@link #finish} on it or someone else
     * has requested that it finished.  This is often used in
     * {@link #onPause} to determine whether the activity is simply pausing or
     * completely finishing.
     *
     * @return If the activity is finishing, returns true; else returns false.
     *
     * @see #finish
     */
    public boolean isFinishing() {
        return mFinished;
    }

    /**
     * Returns true if the final {@link #onDestroy()} call has been made
     * on the Activity, so this instance is now dead.
     */
    public boolean isDestroyed() {
        return mDestroyed;
    }

    /**
     * Check to see whether this activity is in the process of being destroyed in order to be
     * recreated with a new configuration. This is often used in
     * {@link #onStop} to determine whether the state needs to be cleaned up or will be passed
     * on to the next instance of the activity via {@link #onRetainNonConfigurationInstance()}.
     *
     * @return If the activity is being torn down in order to be recreated with a new configuration,
     * returns true; else returns false.
     */
    public boolean isChangingConfigurations() {
        return mChangingConfigurations;
    }

    /**
     * Cause this Activity to be recreated with a new instance.  This results
     * in essentially the same flow as when the Activity is created due to
     * a configuration change -- the current instance will go through its
     * lifecycle to {@link #onDestroy} and a new instance then created after it.
     */
    public void recreate() {
        if (mParent != null) {
            throw new IllegalStateException("Can only be called on top-level activity");
        }
        if (Looper.myLooper() != mMainThread.getLooper()) {
            throw new IllegalStateException("Must be called from main thread");
        }
        mMainThread.requestRelaunchActivity(mToken, null, null, 0, false, null, false);
    }

    /**
     * Finishes the current activity and specifies whether to remove the task associated with this
     * activity.
     */
    private void finish(boolean finishTask) {
        if (mParent == null) {
            int resultCode;
            Intent resultData;
            synchronized (this) {
                resultCode = mResultCode;
                resultData = mResultData;
            }
            if (false) Log.v(TAG, "Finishing self: token=" + mToken);
            try {
                if (resultData != null) {
                    resultData.prepareToLeaveProcess();
                }
                if (ActivityManagerNative.getDefault()
                        .finishActivity(mToken, resultCode, resultData, finishTask)) {
                    mFinished = true;
                }
            } catch (RemoteException e) {
                // Empty
            }
        } else {
            mParent.finishFromChild(this);
        }
    }

    /**
     * Call this when your activity is done and should be closed.  The
     * ActivityResult is propagated back to whoever launched you via
     * onActivityResult().
     */
    public void finish() {
        finish(false);
    }

    /**
     * Finish this activity as well as all activities immediately below it
     * in the current task that have the same affinity.  This is typically
     * used when an application can be launched on to another task (such as
     * from an ACTION_VIEW of a content type it understands) and the user
     * has used the up navigation to switch out of the current task and in
     * to its own task.  In this case, if the user has navigated down into
     * any other activities of the second application, all of those should
     * be removed from the original task as part of the task switch.
     *
     * <p>Note that this finish does <em>not</em> allow you to deliver results
     * to the previous activity, and an exception will be thrown if you are trying
     * to do so.</p>
     */
    public void finishAffinity() {
        if (mParent != null) {
            throw new IllegalStateException("Can not be called from an embedded activity");
        }
        if (mResultCode != RESULT_CANCELED || mResultData != null) {
            throw new IllegalStateException("Can not be called to deliver a result");
        }
        try {
            if (ActivityManagerNative.getDefault().finishActivityAffinity(mToken)) {
                mFinished = true;
            }
        } catch (RemoteException e) {
            // Empty
        }
    }

    /**
     * This is called when a child activity of this one calls its
     * {@link #finish} method.  The default implementation simply calls
     * finish() on this activity (the parent), finishing the entire group.
     *
     * @param child The activity making the call.
     *
     * @see #finish
     */
    public void finishFromChild(Activity child) {
        finish();
    }

    /**
     * Reverses the Activity Scene entry Transition and triggers the calling Activity
     * to reverse its exit Transition. When the exit Transition completes,
     * {@link #finish()} is called. If no entry Transition was used, finish() is called
     * immediately and the Activity exit Transition is run.
     * @see android.app.ActivityOptions#makeSceneTransitionAnimation(Activity, android.util.Pair[])
     */
    public void finishAfterTransition() {
        if (!mActivityTransitionState.startExitBackTransition(this)) {
            finish();
        }
    }

    /**
     * Force finish another activity that you had previously started with
     * {@link #startActivityForResult}.
     *
     * @param requestCode The request code of the activity that you had
     *                    given to startActivityForResult().  If there are multiple
     *                    activities started with this request code, they
     *                    will all be finished.
     */
    public void finishActivity(int requestCode) {
        if (mParent == null) {
            try {
                ActivityManagerNative.getDefault()
                    .finishSubActivity(mToken, mEmbeddedID, requestCode);
            } catch (RemoteException e) {
                // Empty
            }
        } else {
            mParent.finishActivityFromChild(this, requestCode);
        }
    }

    /**
     * This is called when a child activity of this one calls its
     * finishActivity().
     *
     * @param child The activity making the call.
     * @param requestCode Request code that had been used to start the
     *                    activity.
     */
    public void finishActivityFromChild(@NonNull Activity child, int requestCode) {
        try {
            ActivityManagerNative.getDefault()
                .finishSubActivity(mToken, child.mEmbeddedID, requestCode);
        } catch (RemoteException e) {
            // Empty
        }
    }

    /**
     * Call this when your activity is done and should be closed and the task should be completely
     * removed as a part of finishing the Activity.
     */
    public void finishAndRemoveTask() {
        finish(true);
    }

    /**
     * Called when an activity you launched exits, giving you the requestCode
     * you started it with, the resultCode it returned, and any additional
     * data from it.  The <var>resultCode</var> will be
     * {@link #RESULT_CANCELED} if the activity explicitly returned that,
     * didn't return any result, or crashed during its operation.
     *
     * <p>You will receive this call immediately before onResume() when your
     * activity is re-starting.
     *
     * @param requestCode The integer request code originally supplied to
     *                    startActivityForResult(), allowing you to identify who this
     *                    result came from.
     * @param resultCode The integer result code returned by the child activity
     *                   through its setResult().
     * @param data An Intent, which can return result data to the caller
     *               (various data can be attached to Intent "extras").
     *
     * @see #startActivityForResult
     * @see #createPendingResult
     * @see #setResult(int)
     */
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    }

    /**
     * Called when an activity you launched with an activity transition exposes this
     * Activity through a returning activity transition, giving you the resultCode
     * and any additional data from it. This method will only be called if the activity
     * set a result code other than {@link #RESULT_CANCELED} and it supports activity
     * transitions with {@link Window#FEATURE_CONTENT_TRANSITIONS}.
     *
     * <p>The purpose of this function is to let the called Activity send a hint about
     * its state so that this underlying Activity can prepare to be exposed. A call to
     * this method does not guarantee that the called Activity has or will be exiting soon.
     * It only indicates that it will expose this Activity's Window and it has
     * some data to pass to prepare it.</p>
     *
     * @param resultCode The integer result code returned by the child activity
     *                   through its setResult().
     * @param data An Intent, which can return result data to the caller
     *               (various data can be attached to Intent "extras").
     */
    protected void onActivityReenter(int resultCode, Intent data) {
    }

    /**
     * Create a new PendingIntent object which you can hand to others
     * for them to use to send result data back to your
     * {@link #onActivityResult} callback.  The created object will be either
     * one-shot (becoming invalid after a result is sent back) or multiple
     * (allowing any number of results to be sent through it).
     *
     * @param requestCode Private request code for the sender that will be
     * associated with the result data when it is returned.  The sender can not
     * modify this value, allowing you to identify incoming results.
     * @param data Default data to supply in the result, which may be modified
     * by the sender.
     * @param flags May be {@link PendingIntent#FLAG_ONE_SHOT PendingIntent.FLAG_ONE_SHOT},
     * {@link PendingIntent#FLAG_NO_CREATE PendingIntent.FLAG_NO_CREATE},
     * {@link PendingIntent#FLAG_CANCEL_CURRENT PendingIntent.FLAG_CANCEL_CURRENT},
     * {@link PendingIntent#FLAG_UPDATE_CURRENT PendingIntent.FLAG_UPDATE_CURRENT},
     * or any of the flags as supported by
     * {@link Intent#fillIn Intent.fillIn()} to control which unspecified parts
     * of the intent that can be supplied when the actual send happens.
     *
     * @return Returns an existing or new PendingIntent matching the given
     * parameters.  May return null only if
     * {@link PendingIntent#FLAG_NO_CREATE PendingIntent.FLAG_NO_CREATE} has been
     * supplied.
     *
     * @see PendingIntent
     */
    public PendingIntent createPendingResult(int requestCode, @NonNull Intent data,
            @PendingIntent.Flags int flags) {
        String packageName = getPackageName();
        try {
            data.prepareToLeaveProcess();
            IIntentSender target =
                ActivityManagerNative.getDefault().getIntentSender(
                        ActivityManager.INTENT_SENDER_ACTIVITY_RESULT, packageName,
                        mParent == null ? mToken : mParent.mToken,
                        mEmbeddedID, requestCode, new Intent[] { data }, null, flags, null,
                        UserHandle.myUserId());
            return target != null ? new PendingIntent(target) : null;
        } catch (RemoteException e) {
            // Empty
        }
        return null;
    }

    /**
     * Change the desired orientation of this activity.  If the activity
     * is currently in the foreground or otherwise impacting the screen
     * orientation, the screen will immediately be changed (possibly causing
     * the activity to be restarted). Otherwise, this will be used the next
     * time the activity is visible.
     *
     * @param requestedOrientation An orientation constant as used in
     * {@link ActivityInfo#screenOrientation ActivityInfo.screenOrientation}.
     */
    public void setRequestedOrientation(@ActivityInfo.ScreenOrientation int requestedOrientation) {
        if (mParent == null) {
            try {
                ActivityManagerNative.getDefault().setRequestedOrientation(
                        mToken, requestedOrientation);
            } catch (RemoteException e) {
                // Empty
            }
        } else {
            mParent.setRequestedOrientation(requestedOrientation);
        }
    }

    /**
     * Return the current requested orientation of the activity.  This will
     * either be the orientation requested in its component's manifest, or
     * the last requested orientation given to
     * {@link #setRequestedOrientation(int)}.
     *
     * @return Returns an orientation constant as used in
     * {@link ActivityInfo#screenOrientation ActivityInfo.screenOrientation}.
     */
    @ActivityInfo.ScreenOrientation
    public int getRequestedOrientation() {
        if (mParent == null) {
            try {
                return ActivityManagerNative.getDefault()
                        .getRequestedOrientation(mToken);
            } catch (RemoteException e) {
                // Empty
            }
        } else {
            return mParent.getRequestedOrientation();
        }
        return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    }

    /**
     * Return the identifier of the task this activity is in.  This identifier
     * will remain the same for the lifetime of the activity.
     *
     * @return Task identifier, an opaque integer.
     */
    public int getTaskId() {
        try {
            return ActivityManagerNative.getDefault()
                .getTaskForActivity(mToken, false);
        } catch (RemoteException e) {
            return -1;
        }
    }

    /**
     * Return whether this activity is the root of a task.  The root is the
     * first activity in a task.
     *
     * @return True if this is the root activity, else false.
     */
    public boolean isTaskRoot() {
        try {
            return ActivityManagerNative.getDefault()
                .getTaskForActivity(mToken, true) >= 0;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Move the task containing this activity to the back of the activity
     * stack.  The activity's order within the task is unchanged.
     *
     * @param nonRoot If false then this only works if the activity is the root
     *                of a task; if true it will work for any activity in
     *                a task.
     *
     * @return If the task was moved (or it was already at the
     *         back) true is returned, else false.
     */
    public boolean moveTaskToBack(boolean nonRoot) {
        try {
            return ActivityManagerNative.getDefault().moveActivityTaskToBack(
                    mToken, nonRoot);
        } catch (RemoteException e) {
            // Empty
        }
        return false;
    }

    /**
     * Returns class name for this activity with the package prefix removed.
     * This is the default name used to read and write settings.
     *
     * @return The local class name.
     */
    @NonNull
    public String getLocalClassName() {
        final String pkg = getPackageName();
        final String cls = mComponent.getClassName();
        int packageLen = pkg.length();
        if (!cls.startsWith(pkg) || cls.length() <= packageLen
                || cls.charAt(packageLen) != '.') {
            return cls;
        }
        return cls.substring(packageLen+1);
    }

    /**
     * Returns complete component name of this activity.
     *
     * @return Returns the complete component name for this activity
     */
    public ComponentName getComponentName()
    {
        return mComponent;
    }

    /**
     * Retrieve a {@link SharedPreferences} object for accessing preferences
     * that are private to this activity.  This simply calls the underlying
     * {@link #getSharedPreferences(String, int)} method by passing in this activity's
     * class name as the preferences name.
     *
     * @param mode Operating mode.  Use {@link #MODE_PRIVATE} for the default
     *             operation, {@link #MODE_WORLD_READABLE} and
     *             {@link #MODE_WORLD_WRITEABLE} to control permissions.
     *
     * @return Returns the single SharedPreferences instance that can be used
     *         to retrieve and modify the preference values.
     */
    public SharedPreferences getPreferences(int mode) {
        return getSharedPreferences(getLocalClassName(), mode);
    }

    private void ensureSearchManager() {
        if (mSearchManager != null) {
            return;
        }

        mSearchManager = new SearchManager(this, null);
    }

    @Override
    public Object getSystemService(@ServiceName @NonNull String name) {
        if (getBaseContext() == null) {
            throw new IllegalStateException(
                    "System services not available to Activities before onCreate()");
        }

        if (WINDOW_SERVICE.equals(name)) {
            return mWindowManager;
        } else if (SEARCH_SERVICE.equals(name)) {
            ensureSearchManager();
            return mSearchManager;
        }
        return super.getSystemService(name);
    }

    /**
     * Change the title associated with this activity.  If this is a
     * top-level activity, the title for its window will change.  If it
     * is an embedded activity, the parent can do whatever it wants
     * with it.
     */
    public void setTitle(CharSequence title) {
        mTitle = title;
        onTitleChanged(title, mTitleColor);

        if (mParent != null) {
            mParent.onChildTitleChanged(this, title);
        }
    }

    /**
     * Change the title associated with this activity.  If this is a
     * top-level activity, the title for its window will change.  If it
     * is an embedded activity, the parent can do whatever it wants
     * with it.
     */
    public void setTitle(int titleId) {
        setTitle(getText(titleId));
    }

    /**
     * Change the color of the title associated with this activity.
     * <p>
     * This method is deprecated starting in API Level 11 and replaced by action
     * bar styles. For information on styling the Action Bar, read the <a
     * href="{@docRoot} guide/topics/ui/actionbar.html">Action Bar</a> developer
     * guide.
     *
     * @deprecated Use action bar styles instead.
     */
    @Deprecated
    public void setTitleColor(int textColor) {
        mTitleColor = textColor;
        onTitleChanged(mTitle, textColor);
    }

    public final CharSequence getTitle() {
        return mTitle;
    }

    public final int getTitleColor() {
        return mTitleColor;
    }

    protected void onTitleChanged(CharSequence title, int color) {
        if (mTitleReady) {
            final Window win = getWindow();
            if (win != null) {
                win.setTitle(title);
                if (color != 0) {
                    win.setTitleColor(color);
                }
            }
        }
    }

    protected void onChildTitleChanged(Activity childActivity, CharSequence title) {
    }

    /**
     * Sets information describing the task with this activity for presentation inside the Recents
     * System UI. When {@link ActivityManager#getRecentTasks} is called, the activities of each task
     * are traversed in order from the topmost activity to the bottommost. The traversal continues
     * for each property until a suitable value is found. For each task the taskDescription will be
     * returned in {@link android.app.ActivityManager.TaskDescription}.
     *
     * @see ActivityManager#getRecentTasks
     * @see android.app.ActivityManager.TaskDescription
     *
     * @param taskDescription The TaskDescription properties that describe the task with this activity
     */
    public void setTaskDescription(ActivityManager.TaskDescription taskDescription) {
        ActivityManager.TaskDescription td;
        // Scale the icon down to something reasonable if it is provided
        if (taskDescription.getIcon() != null) {
            final int size = ActivityManager.getLauncherLargeIconSizeInner(this);
            final Bitmap icon = Bitmap.createScaledBitmap(taskDescription.getIcon(), size, size, true);
            td = new ActivityManager.TaskDescription(taskDescription.getLabel(), icon,
                    taskDescription.getPrimaryColor());
        } else {
            td = taskDescription;
        }
        try {
            ActivityManagerNative.getDefault().setTaskDescription(mToken, td);
        } catch (RemoteException e) {
        }
    }

    /**
     * Sets the visibility of the progress bar in the title.
     * <p>
     * In order for the progress bar to be shown, the feature must be requested
     * via {@link #requestWindowFeature(int)}.
     *
     * @param visible Whether to show the progress bars in the title.
     */
    public final void setProgressBarVisibility(boolean visible) {
        getWindow().setFeatureInt(Window.FEATURE_PROGRESS, visible ? Window.PROGRESS_VISIBILITY_ON :
            Window.PROGRESS_VISIBILITY_OFF);
    }

    /**
     * Sets the visibility of the indeterminate progress bar in the title.
     * <p>
     * In order for the progress bar to be shown, the feature must be requested
     * via {@link #requestWindowFeature(int)}.
     *
     * @param visible Whether to show the progress bars in the title.
     */
    public final void setProgressBarIndeterminateVisibility(boolean visible) {
        getWindow().setFeatureInt(Window.FEATURE_INDETERMINATE_PROGRESS,
                visible ? Window.PROGRESS_VISIBILITY_ON : Window.PROGRESS_VISIBILITY_OFF);
    }

    /**
     * Sets whether the horizontal progress bar in the title should be indeterminate (the circular
     * is always indeterminate).
     * <p>
     * In order for the progress bar to be shown, the feature must be requested
     * via {@link #requestWindowFeature(int)}.
     *
     * @param indeterminate Whether the horizontal progress bar should be indeterminate.
     */
    public final void setProgressBarIndeterminate(boolean indeterminate) {
        getWindow().setFeatureInt(Window.FEATURE_PROGRESS,
                indeterminate ? Window.PROGRESS_INDETERMINATE_ON
                        : Window.PROGRESS_INDETERMINATE_OFF);
    }

    /**
     * Sets the progress for the progress bars in the title.
     * <p>
     * In order for the progress bar to be shown, the feature must be requested
     * via {@link #requestWindowFeature(int)}.
     *
     * @param progress The progress for the progress bar. Valid ranges are from
     *            0 to 10000 (both inclusive). If 10000 is given, the progress
     *            bar will be completely filled and will fade out.
     */
    public final void setProgress(int progress) {
        getWindow().setFeatureInt(Window.FEATURE_PROGRESS, progress + Window.PROGRESS_START);
    }

    /**
     * Sets the secondary progress for the progress bar in the title. This
     * progress is drawn between the primary progress (set via
     * {@link #setProgress(int)} and the background. It can be ideal for media
     * scenarios such as showing the buffering progress while the default
     * progress shows the play progress.
     * <p>
     * In order for the progress bar to be shown, the feature must be requested
     * via {@link #requestWindowFeature(int)}.
     *
     * @param secondaryProgress The secondary progress for the progress bar. Valid ranges are from
     *            0 to 10000 (both inclusive).
     */
    public final void setSecondaryProgress(int secondaryProgress) {
        getWindow().setFeatureInt(Window.FEATURE_PROGRESS,
                secondaryProgress + Window.PROGRESS_SECONDARY_START);
    }

    /**
     * Suggests an audio stream whose volume should be changed by the hardware
     * volume controls.
     * <p>
     * The suggested audio stream will be tied to the window of this Activity.
     * Volume requests which are received while the Activity is in the
     * foreground will affect this stream.
     * <p>
     * It is not guaranteed that the hardware volume controls will always change
     * this stream's volume (for example, if a call is in progress, its stream's
     * volume may be changed instead). To reset back to the default, use
     * {@link AudioManager#USE_DEFAULT_STREAM_TYPE}.
     *
     * @param streamType The type of the audio stream whose volume should be
     *            changed by the hardware volume controls.
     */
    public final void setVolumeControlStream(int streamType) {
        getWindow().setVolumeControlStream(streamType);
    }

    /**
     * Gets the suggested audio stream whose volume should be changed by the
     * hardware volume controls.
     *
     * @return The suggested audio stream type whose volume should be changed by
     *         the hardware volume controls.
     * @see #setVolumeControlStream(int)
     */
    public final int getVolumeControlStream() {
        return getWindow().getVolumeControlStream();
    }

    /**
     * Sets a {@link MediaController} to send media keys and volume changes to.
     * <p>
     * The controller will be tied to the window of this Activity. Media key and
     * volume events which are received while the Activity is in the foreground
     * will be forwarded to the controller and used to invoke transport controls
     * or adjust the volume. This may be used instead of or in addition to
     * {@link #setVolumeControlStream} to affect a specific session instead of a
     * specific stream.
     * <p>
     * It is not guaranteed that the hardware volume controls will always change
     * this session's volume (for example, if a call is in progress, its
     * stream's volume may be changed instead). To reset back to the default use
     * null as the controller.
     *
     * @param controller The controller for the session which should receive
     *            media keys and volume changes.
     */
    public final void setMediaController(MediaController controller) {
        getWindow().setMediaController(controller);
    }

    /**
     * Gets the controller which should be receiving media key and volume events
     * while this activity is in the foreground.
     *
     * @return The controller which should receive events.
     * @see #setMediaController(android.media.session.MediaController)
     */
    public final MediaController getMediaController() {
        return getWindow().getMediaController();
    }

    /**
     * Runs the specified action on the UI thread. If the current thread is the UI
     * thread, then the action is executed immediately. If the current thread is
     * not the UI thread, the action is posted to the event queue of the UI thread.
     *
     * @param action the action to run on the UI thread
     */
    public final void runOnUiThread(Runnable action) {
        if (Thread.currentThread() != mUiThread) {
            mHandler.post(action);
        } else {
            action.run();
        }
    }

    /**
     * Standard implementation of
     * {@link android.view.LayoutInflater.Factory#onCreateView} used when
     * inflating with the LayoutInflater returned by {@link #getSystemService}.
     * This implementation does nothing and is for
     * pre-{@link android.os.Build.VERSION_CODES#HONEYCOMB} apps.  Newer apps
     * should use {@link #onCreateView(View, String, Context, AttributeSet)}.
     *
     * @see android.view.LayoutInflater#createView
     * @see android.view.Window#getLayoutInflater
     */
    @Nullable
    public View onCreateView(String name, Context context, AttributeSet attrs) {
        return null;
    }

    /**
     * Standard implementation of
     * {@link android.view.LayoutInflater.Factory2#onCreateView(View, String, Context, AttributeSet)}
     * used when inflating with the LayoutInflater returned by {@link #getSystemService}.
     * This implementation handles <fragment> tags to embed fragments inside
     * of the activity.
     *
     * @see android.view.LayoutInflater#createView
     * @see android.view.Window#getLayoutInflater
     */
    public View onCreateView(View parent, String name, Context context, AttributeSet attrs) {
        if (!"fragment".equals(name)) {
            return onCreateView(name, context, attrs);
        }

        return mFragments.onCreateView(parent, name, context, attrs);
    }

    /**
     * Print the Activity's state into the given stream.  This gets invoked if
     * you run "adb shell dumpsys activity &lt;activity_component_name&gt;".
     *
     * @param prefix Desired prefix to prepend at each line of output.
     * @param fd The raw file descriptor that the dump is being sent to.
     * @param writer The PrintWriter to which you should dump your state.  This will be
     * closed for you after you return.
     * @param args additional arguments to the dump request.
     */
    public void dump(String prefix, FileDescriptor fd, PrintWriter writer, String[] args) {
        dumpInner(prefix, fd, writer, args);
    }

    void dumpInner(String prefix, FileDescriptor fd, PrintWriter writer, String[] args) {
        writer.print(prefix); writer.print("Local Activity ");
                writer.print(Integer.toHexString(System.identityHashCode(this)));
                writer.println(" State:");
        String innerPrefix = prefix + "  ";
        writer.print(innerPrefix); writer.print("mResumed=");
                writer.print(mResumed); writer.print(" mStopped=");
                writer.print(mStopped); writer.print(" mFinished=");
                writer.println(mFinished);
        writer.print(innerPrefix); writer.print("mLoadersStarted=");
                writer.println(mLoadersStarted);
        writer.print(innerPrefix); writer.print("mChangingConfigurations=");
                writer.println(mChangingConfigurations);
        writer.print(innerPrefix); writer.print("mCurrentConfig=");
                writer.println(mCurrentConfig);

        if (mLoaderManager != null) {
            writer.print(prefix); writer.print("Loader Manager ");
                    writer.print(Integer.toHexString(System.identityHashCode(mLoaderManager)));
                    writer.println(":");
            mLoaderManager.dump(prefix + "  ", fd, writer, args);
        }

        mFragments.dump(prefix, fd, writer, args);

        if (getWindow() != null &&
                getWindow().peekDecorView() != null &&
                getWindow().peekDecorView().getViewRootImpl() != null) {
            getWindow().peekDecorView().getViewRootImpl().dump(prefix, fd, writer, args);
        }

        mHandler.getLooper().dump(new PrintWriterPrinter(writer), prefix);
    }

    /**
     * Bit indicating that this activity is "immersive" and should not be
     * interrupted by notifications if possible.
     *
     * This value is initially set by the manifest property
     * <code>android:immersive</code> but may be changed at runtime by
     * {@link #setImmersive}.
     *
     * @see #setImmersive(boolean)
     * @see android.content.pm.ActivityInfo#FLAG_IMMERSIVE
     */
    public boolean isImmersive() {
        try {
            return ActivityManagerNative.getDefault().isImmersive(mToken);
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Indication of whether this is the highest level activity in this task. Can be used to
     * determine whether an activity launched by this activity was placed in the same task or
     * another task.
     *
     * @return true if this is the topmost, non-finishing activity in its task.
     */
    private boolean isTopOfTask() {
        try {
            return ActivityManagerNative.getDefault().isTopOfTask(mToken);
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Convert a translucent themed Activity {@link android.R.attr#windowIsTranslucent} to a
     * fullscreen opaque Activity.
     * <p>
     * Call this whenever the background of a translucent Activity has changed to become opaque.
     * Doing so will allow the {@link android.view.Surface} of the Activity behind to be released.
     * <p>
     * This call has no effect on non-translucent activities or on activities with the
     * {@link android.R.attr#windowIsFloating} attribute.
     *
     * @see #convertToTranslucent(android.app.Activity.TranslucentConversionListener,
     * ActivityOptions)
     * @see TranslucentConversionListener
     *
     * @hide
     */
    @SystemApi
    public void convertFromTranslucent() {
        try {
            mTranslucentCallback = null;
            if (ActivityManagerNative.getDefault().convertFromTranslucent(mToken)) {
                WindowManagerGlobal.getInstance().changeCanvasOpacity(mToken, true);
            }
        } catch (RemoteException e) {
            // pass
        }
    }

    /**
     * Convert a translucent themed Activity {@link android.R.attr#windowIsTranslucent} back from
     * opaque to translucent following a call to {@link #convertFromTranslucent()}.
     * <p>
     * Calling this allows the Activity behind this one to be seen again. Once all such Activities
     * have been redrawn {@link TranslucentConversionListener#onTranslucentConversionComplete} will
     * be called indicating that it is safe to make this activity translucent again. Until
     * {@link TranslucentConversionListener#onTranslucentConversionComplete} is called the image
     * behind the frontmost Activity will be indeterminate.
     * <p>
     * This call has no effect on non-translucent activities or on activities with the
     * {@link android.R.attr#windowIsFloating} attribute.
     *
     * @param callback the method to call when all visible Activities behind this one have been
     * drawn and it is safe to make this Activity translucent again.
     * @param options activity options delivered to the activity below this one. The options
     * are retrieved using {@link #getActivityOptions}.
     * @return <code>true</code> if Window was opaque and will become translucent or
     * <code>false</code> if window was translucent and no change needed to be made.
     *
     * @see #convertFromTranslucent()
     * @see TranslucentConversionListener
     *
     * @hide
     */
    @SystemApi
    public boolean convertToTranslucent(TranslucentConversionListener callback,
            ActivityOptions options) {
        boolean drawComplete;
        try {
            mTranslucentCallback = callback;
            mChangeCanvasToTranslucent =
                    ActivityManagerNative.getDefault().convertToTranslucent(mToken, options);
            drawComplete = true;
        } catch (RemoteException e) {
            // Make callback return as though it timed out.
            mChangeCanvasToTranslucent = false;
            drawComplete = false;
        }
        if (!mChangeCanvasToTranslucent && mTranslucentCallback != null) {
            // Window is already translucent.
            mTranslucentCallback.onTranslucentConversionComplete(drawComplete);
        }
        return mChangeCanvasToTranslucent;
    }

    /** @hide */
    void onTranslucentConversionComplete(boolean drawComplete) {
        if (mTranslucentCallback != null) {
            mTranslucentCallback.onTranslucentConversionComplete(drawComplete);
            mTranslucentCallback = null;
        }
        if (mChangeCanvasToTranslucent) {
            WindowManagerGlobal.getInstance().changeCanvasOpacity(mToken, false);
        }
    }

    /** @hide */
    public void onNewActivityOptions(ActivityOptions options) {
        mActivityTransitionState.setEnterActivityOptions(this, options);
        if (!mStopped) {
            mActivityTransitionState.enterReady(this);
        }
    }

    /**
     * Retrieve the ActivityOptions passed in from the launching activity or passed back
     * from an activity launched by this activity in its call to {@link
     * #convertToTranslucent(TranslucentConversionListener, ActivityOptions)}
     *
     * @return The ActivityOptions passed to {@link #convertToTranslucent}.
     * @hide
     */
    ActivityOptions getActivityOptions() {
        try {
            return ActivityManagerNative.getDefault().getActivityOptions(mToken);
        } catch (RemoteException e) {
        }
        return null;
    }

    /**
     * Activities that want to remain visible behind a translucent activity above them must call
     * this method anytime before a return from {@link #onPause()}. If this call is successful
     * then the activity will remain visible when {@link #onPause()} is called, and can continue to
     * play media in the background, but it must stop playing and release resources prior to or
     * within the call to {@link #onVisibleBehindCancelled()}. If this call returns false, the 
     * activity will not be visible in the background, and must release any media resources 
     * immediately.
     *
     * <p>Only fullscreen opaque activities may make this call. I.e. this call is a nop
     * for dialog and translucent activities.
     *
     * <p>False will be returned any time this method is call between the return of onPause and
     *      the next call to onResume.
     *
     * @param visible true to notify the system that the activity wishes to be visible behind other
     *                translucent activities, false to indicate otherwise. Resources must be
     *                released when passing false to this method.
     * @return the resulting visibiity state. If true the activity may remain visible beyond
     *      {@link #onPause()}. If false then the activity may not count on being visible behind
     *      other translucent activities, and must stop any media playback and release resources.
     *      Returning false may occur in lieu of a call to onVisibleBehindCancelled() so the return
     *      value must be checked.
     *
     * @see #onVisibleBehindCancelled()
     * @see #onBackgroundVisibleBehindChanged(boolean)
     */
    public boolean requestVisibleBehind(boolean visible) {
        if (!mResumed) {
            // Do not permit paused or stopped activities to do this.
            visible = false;
        }
        try {
            mVisibleBehind = ActivityManagerNative.getDefault()
                    .requestVisibleBehind(mToken, visible) && visible;
        } catch (RemoteException e) {
            mVisibleBehind = false;
        }
        return mVisibleBehind;
    }

    /**
     * Called when a translucent activity over this activity is becoming opaque or another
     * activity is being launched. Activities that override this method must call
     * <code>super.onVisibleBehindCancelled()</code> or a SuperNotCalledException will be thrown.
     *
     * <p>When this method is called the activity has 500 msec to release any resources it may be
     * using while visible in the background.
     * If the activity has not returned from this method in 500 msec the system will destroy
     * the activity and kill the process in order to recover the resources for another
     * process. Otherwise {@link #onStop()} will be called following return.
     *
     * @see #requestVisibleBehind(boolean)
     * @see #onBackgroundVisibleBehindChanged(boolean)
     */
    public void onVisibleBehindCancelled() {
        mCalled = true;
    }

    /**
     * Translucent activities may call this to determine if there is an activity below them that
     * is currently set to be visible in the background.
     *
     * @return true if an activity below is set to visible according to the most recent call to
     * {@link #requestVisibleBehind(boolean)}, false otherwise.
     *
     * @see #requestVisibleBehind(boolean)
     * @see #onVisibleBehindCancelled()
     * @see #onBackgroundVisibleBehindChanged(boolean)
     * @hide
     */
    @SystemApi
    public boolean isBackgroundVisibleBehind() {
        try {
            return ActivityManagerNative.getDefault().isBackgroundVisibleBehind(mToken);
        } catch (RemoteException e) {
        }
        return false;
    }

    /**
     * The topmost foreground activity will receive this call when the background visibility state
     * of the activity below it changes.
     *
     * This call may be a consequence of {@link #requestVisibleBehind(boolean)} or might be
     * due to a background activity finishing itself.
     *
     * @param visible true if a background activity is visible, false otherwise.
     *
     * @see #requestVisibleBehind(boolean)
     * @see #onVisibleBehindCancelled()
     * @hide
     */
    @SystemApi
    public void onBackgroundVisibleBehindChanged(boolean visible) {
    }

    /**
     * Activities cannot draw during the period that their windows are animating in. In order
     * to know when it is safe to begin drawing they can override this method which will be
     * called when the entering animation has completed.
     */
    public void onEnterAnimationComplete() {
    }

    /**
     * Adjust the current immersive mode setting.
     *
     * Note that changing this value will have no effect on the activity's
     * {@link android.content.pm.ActivityInfo} structure; that is, if
     * <code>android:immersive</code> is set to <code>true</code>
     * in the application's manifest entry for this activity, the {@link
     * android.content.pm.ActivityInfo#flags ActivityInfo.flags} member will
     * always have its {@link android.content.pm.ActivityInfo#FLAG_IMMERSIVE
     * FLAG_IMMERSIVE} bit set.
     *
     * @see #isImmersive()
     * @see android.content.pm.ActivityInfo#FLAG_IMMERSIVE
     */
    public void setImmersive(boolean i) {
        try {
            ActivityManagerNative.getDefault().setImmersive(mToken, i);
        } catch (RemoteException e) {
            // pass
        }
    }

    /**
     * Start an action mode.
     *
     * @param callback Callback that will manage lifecycle events for this context mode
     * @return The ContextMode that was started, or null if it was canceled
     *
     * @see ActionMode
     */
    @Nullable
    public ActionMode startActionMode(ActionMode.Callback callback) {
        return mWindow.getDecorView().startActionMode(callback);
    }

    /**
     * Give the Activity a chance to control the UI for an action mode requested
     * by the system.
     *
     * <p>Note: If you are looking for a notification callback that an action mode
     * has been started for this activity, see {@link #onActionModeStarted(ActionMode)}.</p>
     *
     * @param callback The callback that should control the new action mode
     * @return The new action mode, or <code>null</code> if the activity does not want to
     *         provide special handling for this action mode. (It will be handled by the system.)
     */
    @Nullable
    @Override
    public ActionMode onWindowStartingActionMode(ActionMode.Callback callback) {
        initWindowDecorActionBar();
        if (mActionBar != null) {
            return mActionBar.startActionMode(callback);
        }
        return null;
    }

    /**
     * Notifies the Activity that an action mode has been started.
     * Activity subclasses overriding this method should call the superclass implementation.
     *
     * @param mode The new action mode.
     */
    @Override
    public void onActionModeStarted(ActionMode mode) {
    }

    /**
     * Notifies the activity that an action mode has finished.
     * Activity subclasses overriding this method should call the superclass implementation.
     *
     * @param mode The action mode that just finished.
     */
    @Override
    public void onActionModeFinished(ActionMode mode) {
    }

    /**
     * Returns true if the app should recreate the task when navigating 'up' from this activity
     * by using targetIntent.
     *
     * <p>If this method returns false the app can trivially call
     * {@link #navigateUpTo(Intent)} using the same parameters to correctly perform
     * up navigation. If this method returns false, the app should synthesize a new task stack
     * by using {@link TaskStackBuilder} or another similar mechanism to perform up navigation.</p>
     *
     * @param targetIntent An intent representing the target destination for up navigation
     * @return true if navigating up should recreate a new task stack, false if the same task
     *         should be used for the destination
     */
    public boolean shouldUpRecreateTask(Intent targetIntent) {
        try {
            PackageManager pm = getPackageManager();
            ComponentName cn = targetIntent.getComponent();
            if (cn == null) {
                cn = targetIntent.resolveActivity(pm);
            }
            ActivityInfo info = pm.getActivityInfo(cn, 0);
            if (info.taskAffinity == null) {
                return false;
            }
            return !ActivityManagerNative.getDefault()
                    .targetTaskAffinityMatchesActivity(mToken, info.taskAffinity);
        } catch (RemoteException e) {
            return false;
        } catch (NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Navigate from this activity to the activity specified by upIntent, finishing this activity
     * in the process. If the activity indicated by upIntent already exists in the task's history,
     * this activity and all others before the indicated activity in the history stack will be
     * finished.
     *
     * <p>If the indicated activity does not appear in the history stack, this will finish
     * each activity in this task until the root activity of the task is reached, resulting in
     * an "in-app home" behavior. This can be useful in apps with a complex navigation hierarchy
     * when an activity may be reached by a path not passing through a canonical parent
     * activity.</p>
     *
     * <p>This method should be used when performing up navigation from within the same task
     * as the destination. If up navigation should cross tasks in some cases, see
     * {@link #shouldUpRecreateTask(Intent)}.</p>
     *
     * @param upIntent An intent representing the target destination for up navigation
     *
     * @return true if up navigation successfully reached the activity indicated by upIntent and
     *         upIntent was delivered to it. false if an instance of the indicated activity could
     *         not be found and this activity was simply finished normally.
     */
    public boolean navigateUpTo(Intent upIntent) {
        if (mParent == null) {
            ComponentName destInfo = upIntent.getComponent();
            if (destInfo == null) {
                destInfo = upIntent.resolveActivity(getPackageManager());
                if (destInfo == null) {
                    return false;
                }
                upIntent = new Intent(upIntent);
                upIntent.setComponent(destInfo);
            }
            int resultCode;
            Intent resultData;
            synchronized (this) {
                resultCode = mResultCode;
                resultData = mResultData;
            }
            if (resultData != null) {
                resultData.prepareToLeaveProcess();
            }
            try {
                upIntent.prepareToLeaveProcess();
                return ActivityManagerNative.getDefault().navigateUpTo(mToken, upIntent,
                        resultCode, resultData);
            } catch (RemoteException e) {
                return false;
            }
        } else {
            return mParent.navigateUpToFromChild(this, upIntent);
        }
    }

    /**
     * This is called when a child activity of this one calls its
     * {@link #navigateUpTo} method.  The default implementation simply calls
     * navigateUpTo(upIntent) on this activity (the parent).
     *
     * @param child The activity making the call.
     * @param upIntent An intent representing the target destination for up navigation
     *
     * @return true if up navigation successfully reached the activity indicated by upIntent and
     *         upIntent was delivered to it. false if an instance of the indicated activity could
     *         not be found and this activity was simply finished normally.
     */
    public boolean navigateUpToFromChild(Activity child, Intent upIntent) {
        return navigateUpTo(upIntent);
    }

    /**
     * Obtain an {@link Intent} that will launch an explicit target activity specified by
     * this activity's logical parent. The logical parent is named in the application's manifest
     * by the {@link android.R.attr#parentActivityName parentActivityName} attribute.
     * Activity subclasses may override this method to modify the Intent returned by
     * super.getParentActivityIntent() or to implement a different mechanism of retrieving
     * the parent intent entirely.
     *
     * @return a new Intent targeting the defined parent of this activity or null if
     *         there is no valid parent.
     */
    @Nullable
    public Intent getParentActivityIntent() {
        final String parentName = mActivityInfo.parentActivityName;
        if (TextUtils.isEmpty(parentName)) {
            return null;
        }

        // If the parent itself has no parent, generate a main activity intent.
        final ComponentName target = new ComponentName(this, parentName);
        try {
            final ActivityInfo parentInfo = getPackageManager().getActivityInfo(target, 0);
            final String parentActivity = parentInfo.parentActivityName;
            final Intent parentIntent = parentActivity == null
                    ? Intent.makeMainActivity(target)
                    : new Intent().setComponent(target);
            return parentIntent;
        } catch (NameNotFoundException e) {
            Log.e(TAG, "getParentActivityIntent: bad parentActivityName '" + parentName +
                    "' in manifest");
            return null;
        }
    }

    /**
     * When {@link android.app.ActivityOptions#makeSceneTransitionAnimation(Activity,
     * android.view.View, String)} was used to start an Activity, <var>listener</var>
     * will be called to handle shared elements on the <i>launched</i> Activity. This requires
     * {@link Window#FEATURE_CONTENT_TRANSITIONS}.
     *
     * @param listener Used to manipulate shared element transitions on the launched Activity.
     */
    public void setEnterSharedElementListener(SharedElementListener listener) {
        if (listener == null) {
            listener = SharedElementListener.NULL_LISTENER;
        }
        mEnterTransitionListener = listener;
    }

    /**
     * When {@link android.app.ActivityOptions#makeSceneTransitionAnimation(Activity,
     * android.view.View, String)} was used to start an Activity, <var>listener</var>
     * will be called to handle shared elements on the <i>launching</i> Activity. Most
     * calls will only come when returning from the started Activity.
     * This requires {@link Window#FEATURE_CONTENT_TRANSITIONS}.
     *
     * @param listener Used to manipulate shared element transitions on the launching Activity.
     */
    public void setExitSharedElementListener(SharedElementListener listener) {
        if (listener == null) {
            listener = SharedElementListener.NULL_LISTENER;
        }
        mExitTransitionListener = listener;
    }

    /**
     * Postpone the entering activity transition when Activity was started with
     * {@link android.app.ActivityOptions#makeSceneTransitionAnimation(Activity,
     * android.util.Pair[])}.
     * <p>This method gives the Activity the ability to delay starting the entering and
     * shared element transitions until all data is loaded. Until then, the Activity won't
     * draw into its window, leaving the window transparent. This may also cause the
     * returning animation to be delayed until data is ready. This method should be
     * called in {@link #onCreate(android.os.Bundle)} or in
     * {@link #onActivityReenter(int, android.content.Intent)}.
     * {@link #startPostponedEnterTransition()} must be called to allow the Activity to
     * start the transitions. If the Activity did not use
     * {@link android.app.ActivityOptions#makeSceneTransitionAnimation(Activity,
     * android.util.Pair[])}, then this method does nothing.</p>
     */
    public void postponeEnterTransition() {
        mActivityTransitionState.postponeEnterTransition();
    }

    /**
     * Begin postponed transitions after {@link #postponeEnterTransition()} was called.
     * If postponeEnterTransition() was called, you must call startPostponedEnterTransition()
     * to have your Activity start drawing.
     */
    public void startPostponedEnterTransition() {
        mActivityTransitionState.startPostponedEnterTransition();
    }

    // ------------------ Internal API ------------------

    final void setParent(Activity parent) {
        mParent = parent;
    }

    final void attach(Context context, ActivityThread aThread,
            Instrumentation instr, IBinder token, int ident,
            Application application, Intent intent, ActivityInfo info,
            CharSequence title, Activity parent, String id,
            NonConfigurationInstances lastNonConfigurationInstances,
            Configuration config, IVoiceInteractor voiceInteractor) {
        attachBaseContext(context);

        mFragments.attachActivity(this, mContainer, null);

        mWindow = PolicyManager.makeNewWindow(this);
        mWindow.setCallback(this);
        mWindow.setOnWindowDismissedCallback(this);
        mWindow.getLayoutInflater().setPrivateFactory(this);
        if (info.softInputMode != WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED) {
            mWindow.setSoftInputMode(info.softInputMode);
        }
        if (info.uiOptions != 0) {
            mWindow.setUiOptions(info.uiOptions);
        }
        mUiThread = Thread.currentThread();

        mMainThread = aThread;
        mInstrumentation = instr;
        mToken = token;
        mIdent = ident;
        mApplication = application;
        mIntent = intent;
        mComponent = intent.getComponent();
        mActivityInfo = info;
        mTitle = title;
        mParent = parent;
        mEmbeddedID = id;
        mLastNonConfigurationInstances = lastNonConfigurationInstances;
        if (voiceInteractor != null) {
            if (lastNonConfigurationInstances != null) {
                mVoiceInteractor = lastNonConfigurationInstances.voiceInteractor;
            } else {
                mVoiceInteractor = new VoiceInteractor(voiceInteractor, this, this,
                        Looper.myLooper());
            }
        }

        mWindow.setWindowManager(
                (WindowManager)context.getSystemService(Context.WINDOW_SERVICE),
                mToken, mComponent.flattenToString(),
                (info.flags & ActivityInfo.FLAG_HARDWARE_ACCELERATED) != 0);
        if (mParent != null) {
            mWindow.setContainer(mParent.getWindow());
        }
        mWindowManager = mWindow.getWindowManager();
        mCurrentConfig = config;
    }

    /** @hide */
    public final IBinder getActivityToken() {
        return mParent != null ? mParent.getActivityToken() : mToken;
    }

    final void performCreateCommon() {
        mVisibleFromClient = !mWindow.getWindowStyle().getBoolean(
                com.android.internal.R.styleable.Window_windowNoDisplay, false);
        mFragments.dispatchActivityCreated();
        mActivityTransitionState.setEnterActivityOptions(this, getActivityOptions());
    }

    final void performCreate(Bundle icicle) {
        onCreate(icicle);
        mActivityTransitionState.readState(icicle);
        performCreateCommon();
    }

    final void performCreate(Bundle icicle, PersistableBundle persistentState) {
        onCreate(icicle, persistentState);
        mActivityTransitionState.readState(icicle);
        performCreateCommon();
    }

    final void performStart() {
        mActivityTransitionState.setEnterActivityOptions(this, getActivityOptions());
        mFragments.noteStateNotSaved();
        mCalled = false;
        mFragments.execPendingActions();
        mInstrumentation.callActivityOnStart(this);
        if (!mCalled) {
            throw new SuperNotCalledException(
                "Activity " + mComponent.toShortString() +
                " did not call through to super.onStart()");
        }
        mFragments.dispatchStart();
        if (mAllLoaderManagers != null) {
            final int N = mAllLoaderManagers.size();
            LoaderManagerImpl loaders[] = new LoaderManagerImpl[N];
            for (int i=N-1; i>=0; i--) {
                loaders[i] = mAllLoaderManagers.valueAt(i);
            }
            for (int i=0; i<N; i++) {
                LoaderManagerImpl lm = loaders[i];
                lm.finishRetain();
                lm.doReportStart();
            }
        }
        mActivityTransitionState.enterReady(this);
    }

    final void performRestart() {
        mFragments.noteStateNotSaved();

        if (mStopped) {
            mStopped = false;
            if (mToken != null && mParent == null) {
                WindowManagerGlobal.getInstance().setStoppedState(mToken, false);
            }

            synchronized (mManagedCursors) {
                final int N = mManagedCursors.size();
                for (int i=0; i<N; i++) {
                    ManagedCursor mc = mManagedCursors.get(i);
                    if (mc.mReleased || mc.mUpdated) {
                        if (!mc.mCursor.requery()) {
                            if (getApplicationInfo().targetSdkVersion
                                    >= android.os.Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
                                throw new IllegalStateException(
                                        "trying to requery an already closed cursor  "
                                        + mc.mCursor);
                            }
                        }
                        mc.mReleased = false;
                        mc.mUpdated = false;
                    }
                }
            }

            mCalled = false;
            mInstrumentation.callActivityOnRestart(this);
            if (!mCalled) {
                throw new SuperNotCalledException(
                    "Activity " + mComponent.toShortString() +
                    " did not call through to super.onRestart()");
            }
            performStart();
        }
    }

    final void performResume() {
        performRestart();

        mFragments.execPendingActions();

        mLastNonConfigurationInstances = null;

        mCalled = false;
        // mResumed is set by the instrumentation
        mInstrumentation.callActivityOnResume(this);
        if (!mCalled) {
            throw new SuperNotCalledException(
                "Activity " + mComponent.toShortString() +
                " did not call through to super.onResume()");
        }

        // Now really resume, and install the current status bar and menu.
        mCalled = false;

        mFragments.dispatchResume();
        mFragments.execPendingActions();

        onPostResume();
        if (!mCalled) {
            throw new SuperNotCalledException(
                "Activity " + mComponent.toShortString() +
                " did not call through to super.onPostResume()");
        }
    }

    final void performPause() {
        mDoReportFullyDrawn = false;
        mFragments.dispatchPause();
        mCalled = false;
        onPause();
        mResumed = false;
        if (!mCalled && getApplicationInfo().targetSdkVersion
                >= android.os.Build.VERSION_CODES.GINGERBREAD) {
            throw new SuperNotCalledException(
                    "Activity " + mComponent.toShortString() +
                    " did not call through to super.onPause()");
        }
        mResumed = false;
    }

    final void performUserLeaving() {
        onUserInteraction();
        onUserLeaveHint();
    }

    final void performStop() {
        mDoReportFullyDrawn = false;
        if (mLoadersStarted) {
            mLoadersStarted = false;
            if (mLoaderManager != null) {
                if (!mChangingConfigurations) {
                    mLoaderManager.doStop();
                } else {
                    mLoaderManager.doRetain();
                }
            }
        }

        if (!mStopped) {
            if (mWindow != null) {
                mWindow.closeAllPanels();
            }

            if (mToken != null && mParent == null) {
                WindowManagerGlobal.getInstance().setStoppedState(mToken, true);
            }

            mFragments.dispatchStop();

            mCalled = false;
            mInstrumentation.callActivityOnStop(this);
            if (!mCalled) {
                throw new SuperNotCalledException(
                    "Activity " + mComponent.toShortString() +
                    " did not call through to super.onStop()");
            }

            synchronized (mManagedCursors) {
                final int N = mManagedCursors.size();
                for (int i=0; i<N; i++) {
                    ManagedCursor mc = mManagedCursors.get(i);
                    if (!mc.mReleased) {
                        mc.mCursor.deactivate();
                        mc.mReleased = true;
                    }
                }
            }

            mStopped = true;
        }
        mResumed = false;
    }

    final void performDestroy() {
        mDestroyed = true;
        mWindow.destroy();
        mFragments.dispatchDestroy();
        onDestroy();
        if (mLoaderManager != null) {
            mLoaderManager.doDestroy();
        }
        if (mVoiceInteractor != null) {
            mVoiceInteractor.detachActivity();
        }
    }

    /**
     * @hide
     */
    public final boolean isResumed() {
        return mResumed;
    }

    void dispatchActivityResult(String who, int requestCode,
        int resultCode, Intent data) {
        if (false) Log.v(
            TAG, "Dispatching result: who=" + who + ", reqCode=" + requestCode
            + ", resCode=" + resultCode + ", data=" + data);
        mFragments.noteStateNotSaved();
        if (who == null) {
            onActivityResult(requestCode, resultCode, data);
        } else {
            Fragment frag = mFragments.findFragmentByWho(who);
            if (frag != null) {
                frag.onActivityResult(requestCode, resultCode, data);
            }
        }
    }

    /**
     * Request to put this Activity in a mode where the user is locked to the
     * current task.
     *
     * This will prevent the user from launching other apps, going to settings,
     * or reaching the home screen.
     *
     * If {@link DevicePolicyManager#isLockTaskPermitted(String)} returns true
     * for this component then the app will go directly into Lock Task mode.  The user
     * will not be able to exit this mode until {@link Activity#stopLockTask()} is called.
     *
     * If {@link DevicePolicyManager#isLockTaskPermitted(String)} returns false
     * then the system will prompt the user with a dialog requesting permission to enter
     * this mode.  When entered through this method the user can exit at any time through
     * an action described by the request dialog.  Calling stopLockTask will also exit the
     * mode.
     */
    public void startLockTask() {
        try {
            ActivityManagerNative.getDefault().startLockTaskMode(mToken);
        } catch (RemoteException e) {
        }
    }

    /**
     * Allow the user to switch away from the current task.
     *
     * Called to end the mode started by {@link Activity#startLockTask}. This
     * can only be called by activities that have successfully called
     * startLockTask previously.
     *
     * This will allow the user to exit this app and move onto other activities.
     */
    public void stopLockTask() {
        try {
            ActivityManagerNative.getDefault().stopLockTaskMode();
        } catch (RemoteException e) {
        }
    }

    /**
     * Interface for informing a translucent {@link Activity} once all visible activities below it
     * have completed drawing. This is necessary only after an {@link Activity} has been made
     * opaque using {@link Activity#convertFromTranslucent()} and before it has been drawn
     * translucent again following a call to {@link
     * Activity#convertToTranslucent(android.app.Activity.TranslucentConversionListener,
     * ActivityOptions)}
     *
     * @hide
     */
    @SystemApi
    public interface TranslucentConversionListener {
        /**
         * Callback made following {@link Activity#convertToTranslucent} once all visible Activities
         * below the top one have been redrawn. Following this callback it is safe to make the top
         * Activity translucent because the underlying Activity has been drawn.
         *
         * @param drawComplete True if the background Activity has drawn itself. False if a timeout
         * occurred waiting for the Activity to complete drawing.
         *
         * @see Activity#convertFromTranslucent()
         * @see Activity#convertToTranslucent(TranslucentConversionListener, ActivityOptions)
         */
        public void onTranslucentConversionComplete(boolean drawComplete);
    }
}
