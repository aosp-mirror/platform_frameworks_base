/*
 * Copyright (C) 2013 The Android Open Source Project
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
package com.android.transitiontests;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.transition.ChangeBounds;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.transition.Fade;
import android.transition.Scene;
import android.transition.Transition;
import android.transition.TransitionSet;
import android.widget.ImageView;
import android.widget.TextView;
import android.transition.Crossfade;
import android.transition.Rotate;
import android.transition.TransitionManager;

public class ContactsExpansion extends Activity {

    String contactsData[] = {
            "Alan Green", "56 Bob Street", "Boston, MA 02134", "617-555-5555", "blatt@blatt.com",
            "Bob Foonman", "92 The Avenue", "Chico, CA 78456", "510-555-5556", "bob@jerk.com",
            "Tracey Sue", "95 Houses Street", "San Jose, CA 96504", "415-555-5557", "ts@thing.com",
    };

    View currentItem = null;

    TransitionSet mMyAutoTransition = new TransitionSet().
            setOrdering(TransitionSet.ORDERING_SEQUENTIAL);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.contacts_list);
        ViewGroup contactsContainer = (ViewGroup) findViewById(R.id.contactsContainer);

        int contactsIndex = 0;
        addContact(contactsContainer, contactsIndex, R.drawable.self_portrait_square_100);
        contactsIndex += 5;
        addContact(contactsContainer, contactsIndex, R.drawable.self_portrait_square_100);
        contactsIndex += 5;
        addContact(contactsContainer, contactsIndex, R.drawable.self_portrait_square_100);

    }

    private void addContact(ViewGroup container, int dataIndex, int thumbnailID) {
        LayoutInflater inflater = (LayoutInflater)
                getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View contactItem = inflater.inflate(R.layout.contact_collapsed, container, false);
        ImageView thumbnailView = (ImageView) contactItem.findViewById(R.id.contact_picture);
        thumbnailView.setImageResource(thumbnailID);
        ((TextView)contactItem.findViewById(R.id.contact_name)).setText(contactsData[dataIndex++]);
        ((TextView)contactItem.findViewById(R.id.contact_street)).
                setText(contactsData[dataIndex++]);
        ((TextView)contactItem.findViewById(R.id.contact_city)).setText(contactsData[dataIndex++]);
        ((TextView)contactItem.findViewById(R.id.contact_phone)).setText(contactsData[dataIndex++]);
        ((TextView)contactItem.findViewById(R.id.contact_email)).setText(contactsData[dataIndex++]);
        container.addView(contactItem);

        final TransitionSet myTransition = new TransitionSet();
        myTransition.addTransition(new Fade(Fade.IN)).
                addTransition(new Rotate().addTarget(R.id.contact_arrow)).
                addTransition(new ChangeBounds()).
                addTransition(new Fade(Fade.OUT)).
                addTransition(new Crossfade().addTarget(R.id.contact_picture));
        final ToggleScene toggleScene = new ToggleScene(container, myTransition);
        contactItem.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                currentItem = v;
                toggleScene.changeToScene();
            }
        });
    }

    class ToggleScene {
        boolean expanded = false;
        Scene mScene;
        Transition mTransition;

        ToggleScene(ViewGroup rootView, Transition transition) {
            mScene = new Scene(rootView);
            mTransition = transition;
            mScene.setEnterAction(new Runnable() {
                @Override
                public void run() {
                    if (currentItem != null) {
                        System.out.println("onsceneChanged: currentItem = " + currentItem);
                        View expandedContainer = currentItem.findViewById(R.id.expanded_info);
                        expandedContainer.setVisibility(expanded ? View.GONE : View.VISIBLE);
                        ImageView thumbnailView =
                                (ImageView) currentItem.findViewById(R.id.contact_picture);
                        thumbnailView.setImageResource(expanded ? R.drawable.self_portrait_square_100 :
                                R.drawable.self_portrait_square_200);
                        ImageView arrow = (ImageView) currentItem.findViewById(R.id.contact_arrow);
                        arrow.setRotation(expanded ? 0 : 90);
                        expanded = !expanded;
                    }
                }
            });
        }

        void changeToScene() {
            TransitionManager.go(mScene, mTransition);
        }
    };
}
