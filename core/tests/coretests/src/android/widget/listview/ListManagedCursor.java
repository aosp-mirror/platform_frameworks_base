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

package android.widget.listview;

import android.app.ListActivity;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.provider.Contacts.People;
import android.provider.Settings;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ListAdapter;
import android.widget.SimpleCursorAdapter;

public class ListManagedCursor extends ListActivity implements OnItemClickListener {
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Get a cursor with all people
        Cursor c = getContentResolver().query(Settings.System.CONTENT_URI, null, null, null, null);
        startManagingCursor(c);

        ListAdapter adapter = new SimpleCursorAdapter(this, 
                // Use a template that displays a text view
                android.R.layout.simple_list_item_1, 
                // Give the cursor to the list adatper
                c, 
                // Map the NAME column in the people database to...
                new String[] {People.NAME} ,
                // The "text1" view defined in the XML template
                new int[] {android.R.id.text1}); 
        setListAdapter(adapter);
        getListView().setOnItemClickListener(this);
    }
    
    public void onItemClick(AdapterView parent, View view, int position, long id) {
        Intent dummyIntent = new Intent(this, ListSimple.class);
        startActivity(dummyIntent);
    }
}
             
