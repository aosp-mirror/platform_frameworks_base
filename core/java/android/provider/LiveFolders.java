/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.provider;

import android.annotation.SdkConstant;

/**
 * <p>A LiveFolder is a special folder whose content is provided by a
 * {@link android.content.ContentProvider}. To create a live folder, two components
 * are required:</p>
 * <ul>
 *  <li>An activity that can respond to the intent action {@link #ACTION_CREATE_LIVE_FOLDER}. The
 *  activity is responsible for creating the live folder.</li>
 *  <li>A {@link android.content.ContentProvider} to provide the live folder items.</li>
 * </ul>
 *
 * <h3>Lifecycle</h3>
 * <p>When a user wants to create a live folder, the system looks for all activities with the
 * intent filter action {@link #ACTION_CREATE_LIVE_FOLDER} and presents the list to the user.
 * When the user chooses one of the activities, the activity is invoked with the
 * {@link #ACTION_CREATE_LIVE_FOLDER} action. The activity then creates the live folder and
 * passes it back to the system by setting it as an
 * {@link android.app.Activity#setResult(int, android.content.Intent) activity result}. The
 * live folder is described by a content provider URI, a name, an icon and a display mode.
 * Finally, when the user opens the live folder, the system queries the content provider
 * to retrieve the folder's content.</p>
 *
 * <h3>Setting up the live folder activity</h3>
 * <p>The following code sample shows how to write an activity that creates a live folder:</p>
 * <pre>
 * public static class MyLiveFolder extends Activity {
 *     public static final Uri CONTENT_URI = Uri.parse("content://my.app/live");
 *
 *     protected void onCreate(Bundle savedInstanceState) {
 *         super.onCreate(savedInstanceState);
 *
 *         final Intent intent = getIntent();
 *         final String action = intent.getAction();
 *
 *         if (LiveFolders.ACTION_CREATE_LIVE_FOLDER.equals(action)) {
 *             setResult(RESULT_OK, createLiveFolder(this, CONTENT_URI, "My LiveFolder",
 *                     R.drawable.ic_launcher_contacts_phones));
 *         } else {
 *             setResult(RESULT_CANCELED);
 *         }
 *
 *         finish();
 *     }
 *
 *     private static Intent createLiveFolder(Context context, Uri uri, String name,
 *             int icon) {
 *
 *         final Intent intent = new Intent();
 *
 *         intent.setData(uri);
 *         intent.putExtra(LiveFolders.EXTRA_LIVE_FOLDER_NAME, name);
 *         intent.putExtra(LiveFolders.EXTRA_LIVE_FOLDER_ICON,
 *                 Intent.ShortcutIconResource.fromContext(context, icon));
 *         intent.putExtra(LiveFolders.EXTRA_LIVE_FOLDER_DISPLAY_MODE, LiveFolders.DISPLAY_MODE_LIST);
 *
 *         return intent;
 *     }
 * }
 * </pre>
 * <p>The live folder is described by an {@link android.content.Intent} as follows:</p>
 * <table border="2" width="85%" align="center" frame="hsides" rules="rows">
 *     <thead>
 *     <tr><th>Component</th> <th>Type</th> <th>Description</th> <th>Required</th></tr>
 *     </thead>
 *
 *     <tbody>
 *     <tr><th>URI</th>
 *         <td>URI</td>
 *         <td>The ContentProvider URI</td>
 *         <td align="center">Yes</td>
 *     </tr>
 *     <tr><th>{@link #EXTRA_LIVE_FOLDER_NAME}</th>
 *         <td>Extra String</td>
 *         <td>The name of the live folder</td>
 *         <td align="center">Yes</td>
 *     </tr>
 *     <tr><th>{@link #EXTRA_LIVE_FOLDER_ICON}</th>
 *         <td>Extra {@link android.content.Intent.ShortcutIconResource}</td>
 *         <td>The icon of the live folder</td>
 *         <td align="center">Yes</td>
 *     </tr>
 *     <tr><th>{@link #EXTRA_LIVE_FOLDER_DISPLAY_MODE}</th>
 *         <td>Extra int</td>
 *         <td>The display mode of the live folder. The value must be either
 *         {@link #DISPLAY_MODE_GRID} or {@link #DISPLAY_MODE_LIST}.</td>
 *         <td align="center">Yes</td>
 *     </tr>
 *     <tr><th>{@link #EXTRA_LIVE_FOLDER_BASE_INTENT}</th>
 *         <td>Extra Intent</td>
 *         <td>When the user clicks an item inside a live folder, the system will either fire
 *         the intent associated with that item or, if present, the live folder's base intent
 *         with the id of the item appended to the base intent's URI.</td>
 *         <td align="center">No</td>
 *     </tr>
 *     </tbody>
 * </table>
 *
 * <h3>Setting up the content provider</h3>
 * <p>The live folder's content provider must, upon query, return a {@link android.database.Cursor}
 * whose columns match the following names:</p>
 * <table border="2" width="85%" align="center" frame="hsides" rules="rows">
 *     <thead>
 *     <tr><th>Column</th> <th>Type</th> <th>Description</th> <th>Required</th></tr>
 *     </thead>
 *
 *     <tbody>
 *     <tr><th>{@link #NAME}</th>
 *         <td>String</td>
 *         <td>The name of the item</td>
 *         <td align="center">Yes</td>
 *     </tr>
 *     <tr><th>{@link #DESCRIPTION}</th>
 *         <td>String</td>
 *         <td>The description of the item. The description is ignored when the live folder's
 *         display mode is {@link #DISPLAY_MODE_GRID}.</td>
 *         <td align="center">No</td>
 *     </tr>
 *     <tr><th>{@link #INTENT}</th>
 *         <td>{@link android.content.Intent}</td>
 *         <td>The intent to fire when the item is clicked. Ignored when the live folder defines
 *         a base intent.</td>
 *         <td align="center">No</td>
 *     </tr>
 *     <tr><th>{@link #ICON_BITMAP}</th>
 *         <td>Bitmap</td>
 *         <td>The icon for the item. When this column value is not null, the values for the
 *         columns {@link #ICON_PACKAGE} and {@link #ICON_RESOURCE} must be null.</td>
 *         <td align="center">No</td>
 *     </tr>
 *     <tr><th>{@link #ICON_PACKAGE}</th>
 *         <td>String</td>
 *         <td>The package of the item's icon. When this value is not null, the value for the
 *         column {@link #ICON_RESOURCE} must be specified and the value for the column
 *         {@link #ICON_BITMAP} must be null.</td>
 *         <td align="center">No</td>
 *     </tr>
 *     <tr><th>{@link #ICON_RESOURCE}</th>
 *         <td>String</td>
 *         <td>The resource name of the item's icon. When this value is not null, the value for the
 *         column {@link #ICON_PACKAGE} must be specified and the value for the column
 *         {@link #ICON_BITMAP} must be null.</td>
 *         <td align="center">No</td>
 *     </tr>
 *     </tbody>
 * </table>
 */
public final class LiveFolders implements BaseColumns {
    /**
     * <p>Content provider column.</p>
     * <p>Name of the live folder item.</p>
     * <p>Required.</p>
     * <p>Type: String.</p>
     */
    public static final String NAME = "name";

    /**
     * <p>Content provider column.</p>
     * <p>Description of the live folder item. This value is ignored if the
     * live folder's display mode is {@link LiveFolders#DISPLAY_MODE_GRID}.</p>
     * <p>Optional.</p>
     * <p>Type: String.</p>
     *
     * @see LiveFolders#EXTRA_LIVE_FOLDER_DISPLAY_MODE
     */
    public static final String DESCRIPTION = "description";

    /**
     * <p>Content provider column.</p>
     * <p>Intent of the live folder item.</p>
     * <p>Optional if the live folder has a base intent.</p>
     * <p>Type: {@link android.content.Intent}.</p>
     *
     * @see LiveFolders#EXTRA_LIVE_FOLDER_BASE_INTENT
     */
    public static final String INTENT = "intent";

    /**
     * <p>Content provider column.</p>
     * <p>Icon of the live folder item, as a custom bitmap.</p>
     * <p>Optional.</p>
     * <p>Type: {@link android.graphics.Bitmap}.</p>
     */
    public static final String ICON_BITMAP = "icon_bitmap";

    /**
     * <p>Content provider column.</p>
     * <p>Package where to find the icon of the live folder item. This value can be
     * obtained easily using
     * {@link android.content.Intent.ShortcutIconResource#fromContext(android.content.Context, int)}.</p>
     * <p>Optional.</p>
     * <p>Type: String.</p>
     *
     * @see #ICON_RESOURCE
     * @see android.content.Intent.ShortcutIconResource
     */
    public static final String ICON_PACKAGE = "icon_package";

    /**
     * <p>Content provider column.</p>
     * <p>Resource name of the live folder item. This value can be obtained easily using
     * {@link android.content.Intent.ShortcutIconResource#fromContext(android.content.Context, int)}.</p>
     * <p>Optional.</p>
     * <p>Type: String.</p>
     *
     * @see #ICON_PACKAGE
     * @see android.content.Intent.ShortcutIconResource
     */
    public static final String ICON_RESOURCE = "icon_resource";

    /**
     * Displays a live folder's content in a grid.
     *
     * @see LiveFolders#EXTRA_LIVE_FOLDER_DISPLAY_MODE
     */
    public static final int DISPLAY_MODE_GRID = 0x1;

    /**
     * Displays a live folder's content in a list.
     *
     * @see LiveFolders#EXTRA_LIVE_FOLDER_DISPLAY_MODE
     */
    public static final int DISPLAY_MODE_LIST = 0x2;

    /**
     * The name of the extra used to define the name of a live folder.
     *
     * @see #ACTION_CREATE_LIVE_FOLDER
     */
    public static final String EXTRA_LIVE_FOLDER_NAME = "android.intent.extra.livefolder.NAME";

    /**
     * The name of the extra used to define the icon of a live folder.
     *
     * @see #ACTION_CREATE_LIVE_FOLDER
     */
    public static final String EXTRA_LIVE_FOLDER_ICON = "android.intent.extra.livefolder.ICON";

    /**
     * The name of the extra used to define the display mode of a live folder.
     *
     * @see #ACTION_CREATE_LIVE_FOLDER
     * @see #DISPLAY_MODE_GRID
     * @see #DISPLAY_MODE_LIST
     */
    public static final String EXTRA_LIVE_FOLDER_DISPLAY_MODE =
            "android.intent.extra.livefolder.DISPLAY_MODE";

    /**
     * The name of the extra used to define the base Intent of a live folder.
     *
     * @see #ACTION_CREATE_LIVE_FOLDER
     */
    public static final String EXTRA_LIVE_FOLDER_BASE_INTENT =
            "android.intent.extra.livefolder.BASE_INTENT";

    /**
     * Activity Action: Creates a live folder.
     * <p>Input: Nothing.</p>
     * <p>Output: An Intent representing the live folder. The intent must contain four
     * extras: EXTRA_LIVE_FOLDER_NAME (value: String),
     * EXTRA_LIVE_FOLDER_ICON (value: ShortcutIconResource),
     * EXTRA_LIVE_FOLDER_URI (value: String) and
     * EXTRA_LIVE_FOLDER_DISPLAY_MODE (value: int). The Intent can optionnally contain
     * EXTRA_LIVE_FOLDER_BASE_INTENT (value: Intent).</p>
     *
     * @see #EXTRA_LIVE_FOLDER_NAME
     * @see #EXTRA_LIVE_FOLDER_ICON
     * @see #EXTRA_LIVE_FOLDER_DISPLAY_MODE
     * @see #EXTRA_LIVE_FOLDER_BASE_INTENT
     * @see android.content.Intent.ShortcutIconResource
     */
    @SdkConstant(SdkConstant.SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_CREATE_LIVE_FOLDER =
            "android.intent.action.CREATE_LIVE_FOLDER";

    private LiveFolders() {
    }
}
