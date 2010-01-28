/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.widget.listview;

import com.android.frameworks.coretests.R;

import android.app.ListActivity;
import android.database.Cursor;
import android.os.Bundle;
import android.provider.Contacts.People;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.LayoutAnimationController;
import android.view.animation.TranslateAnimation;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.SimpleCursorAdapter;
import android.widget.Toast;

/**
 * See 1080989. You need some contacts for this adapter.
 */
public class ListWithDisappearingItemBug extends ListActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Toast.makeText(this, "Make sure you rotate screen to see bug", Toast.LENGTH_LONG).show();
        
        // Get a cursor with all people
        Cursor c = getContentResolver().query(People.CONTENT_URI, null, null, null, null);
        startManagingCursor(c);

        ListAdapter adapter = new SimpleCursorAdapter(this, 
                // Use a template that displays a text view
                R.layout.list_with_disappearing_item_bug_item, 
                // Give the cursor to the list adatper
                c, 
                // Map the NAME column in the people database to...
                new String[] {People.NAME} ,
                // The "text1" view defined in the XML template
                new int[] {R.id.text1}); 
        setListAdapter(adapter);

        AnimationSet set = new AnimationSet(true);

        Animation animation = new AlphaAnimation(0.0f, 1.0f);
        animation.setDuration(50);
        set.addAnimation(animation);

        animation = new TranslateAnimation(
            Animation.RELATIVE_TO_SELF, 0.0f,Animation.RELATIVE_TO_SELF, 0.0f,
            Animation.RELATIVE_TO_SELF, -1.0f,Animation.RELATIVE_TO_SELF, 0.0f
        );
        animation.setDuration(100);
        set.addAnimation(animation);

        LayoutAnimationController controller =
                new LayoutAnimationController(set, 0.5f);
        ListView listView = getListView();        
        listView.setLayoutAnimation(controller);
    }

}
