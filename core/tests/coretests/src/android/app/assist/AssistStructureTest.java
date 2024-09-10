/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.app.assist;

import static android.view.View.AUTOFILL_TYPE_TEXT;
import static android.view.View.IMPORTANT_FOR_AUTOFILL_AUTO;
import static android.view.View.IMPORTANT_FOR_AUTOFILL_YES;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.app.assist.AssistStructure.ViewNode;
import android.app.assist.AssistStructure.ViewNodeBuilder;
import android.app.assist.AssistStructure.ViewNodeParcelable;
import android.content.Context;
import android.credentials.CredentialOption;
import android.credentials.GetCredentialException;
import android.credentials.GetCredentialRequest;
import android.credentials.GetCredentialResponse;
import android.os.Bundle;
import android.os.LocaleList;
import android.os.OutcomeReceiver;
import android.os.Parcel;
import android.os.SystemClock;
import android.text.InputFilter;
import android.util.Log;
import android.view.View;
import android.view.autofill.AutofillId;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.test.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Unit test for {@link AssistStructure}.
 *
 * <p>To run it: {@code atest app.assist.AssistStructureTest}
 *
 * <p>TODO: right now this test is focused in the parcelization of a big object, due to an
 * upcoming refactoring on the Autofill parcelization that does not use
 * {@link AssistStructure#ensureData()} to load the structure (which in turn requires calls from
 * system server to the app). Ideally it should be emprove to:
 *
 * <ol>
 *    <li>Add tests for Assist (to make sure autofill properties are not parcelized).
 *    <li>Assert all properties and make sure just the relevant properties (for Autofill or Assist)
 *    are parcelized.
 *    <li>Add tests for Autofill requests with the {@code FLAG_MANUAL_REQUEST} flag.
 * </ol>
 */
@RunWith(AndroidJUnit4.class)
public class AssistStructureTest {

    private static final String TAG = "AssistStructureTest";

    private static final boolean FOR_AUTOFILL = true;
    private static final int NO_FLAGS = 0;

    private static final int BIG_VIEW_SIZE = 10_000_000;
    private static final char BIG_VIEW_CHAR = '6';
    private static final String BIG_STRING = repeat(BIG_VIEW_CHAR, BIG_VIEW_SIZE);

    private static final GetCredentialRequest GET_CREDENTIAL_REQUEST = new
            GetCredentialRequest.Builder(Bundle.EMPTY)
            .addCredentialOption(new CredentialOption(
                    "TYPE_OPTION",
                    new Bundle(),
                    new Bundle(),
                    false))
            .build();

    private static final OutcomeReceiver<GetCredentialResponse,
            GetCredentialException> GET_CREDENTIAL_REQUEST_CALLBACK = new OutcomeReceiver<>() {
                @Override
                public void onResult(@NonNull GetCredentialResponse response) {
                    // Do nothing
                }

                @Override
                public void onError(@NonNull GetCredentialException e) {
                    // Do nothing
                }
            };
    // Cannot be much big because it could hang test due to blocking GC
    private static final int NUMBER_SMALL_VIEWS = 10_000;

    // Autofill field constants
    private static final AutofillId AUTOFILL_ID = new AutofillId(2);
    private static final String AUTOFILL_HINTS = "hints";
    private static final int MIN_TEXT_EMS = 5;
    private static final int MAX_TEXT_EMS = 17;
    private static final int MAX_TEXT_LENGTH = 23;

    // ViewNodeBuilder structure for editing autofill fields
    private AssistStructure.ViewNodeBuilder mBuilder;

    private EmptyLayoutActivity mActivity;

    private final ActivityTestRule<EmptyLayoutActivity> mActivityTestRule =
            new ActivityTestRule<>(EmptyLayoutActivity.class);

    private final Context mContext = InstrumentationRegistry.getTargetContext();

    @Before
    public void setup() {
        mActivity = mActivityTestRule.launchActivity(null);
    }

    @Test
    public void testParcelizationForAutofill_oneSmallView() {
        mActivity.addView(newSmallView());

        waitUntilViewsAreLaidOff();

        AssistStructure structure = new AssistStructure(mActivity, FOR_AUTOFILL, NO_FLAGS);

        // Check properties on "original" structure
        assertStructureWithManySmallViews(structure, 1);

        // Check properties on "cloned" structure
        AssistStructure clone = cloneThroughParcel(structure);
        assertStructureWithManySmallViews(clone, 1);
    }

    @Test
    public void testParcelizationForAutofill_manySmallViews() {
        Log.d(TAG, "Adding " + NUMBER_SMALL_VIEWS + " small views");

        for (int i = 1; i <= NUMBER_SMALL_VIEWS; i++) {
            mActivity.addView(newSmallView());
        }

        waitUntilViewsAreLaidOff();

        AssistStructure structure = new AssistStructure(mActivity, FOR_AUTOFILL, NO_FLAGS);

        // Check properties on "original" structure
        assertStructureWithManySmallViews(structure, NUMBER_SMALL_VIEWS);

        // Check properties on "cloned" structure
        AssistStructure clone = cloneThroughParcel(structure);
        assertStructureWithManySmallViews(clone, NUMBER_SMALL_VIEWS);
    }

    private void assertStructureWithManySmallViews(AssistStructure structure, int expectedSize) {
        int i = 0;
        try {
            assertThat(structure.getWindowNodeCount()).isEqualTo(1);

            ViewNode rootView = structure.getWindowNodeAt(0).getRootViewNode();
            assertThat(rootView.getClassName()).isEqualTo(FrameLayout.class.getName());
            assertThat(rootView.getChildCount()).isEqualTo(2); // title and parent
            assertThat(rootView.getAutofillId()).isNotNull();
            assertThat(rootView.getImportantForAutofill()).isEqualTo(IMPORTANT_FOR_AUTOFILL_AUTO);

            // Title
            ViewNode title = rootView.getChildAt(0);
            assertThat(title.getClassName()).isEqualTo(TextView.class.getName());
            assertThat(title.getChildCount()).isEqualTo(0);
            assertThat(title.getText().toString()).isEqualTo("My Title");
            assertThat(title.getAutofillId()).isNotNull();

            // Parent
            ViewNode parent = rootView.getChildAt(1);
            assertThat(parent.getClassName()).isEqualTo(LinearLayout.class.getName());
            assertThat(parent.getChildCount()).isEqualTo(expectedSize);
            assertThat(parent.getIdEntry()).isEqualTo("parent");
            assertThat(parent.getAutofillId()).isNotNull();

            // Children
            for (i = 0; i < expectedSize; i++) {
                ViewNode smallView = parent.getChildAt(i);
                assertSmallView(smallView);
            }
        } catch (RuntimeException | Error e) {
            Log.e(TAG, "dumping structure because of error at index #" + i + ": " + e);
            structure.dump(true);
            throw e;
        }
    }

    @Test
    public void testParcelizationForAutofill_oneBigView() {
        Log.d(TAG, "Adding view with " + BIG_VIEW_SIZE + " chars");

        mActivity.addView(newBigView());
        waitUntilViewsAreLaidOff();

        AssistStructure structure = new AssistStructure(mActivity, FOR_AUTOFILL, NO_FLAGS);

        // Check properties on "original" structure
        assertStructureWithOneBigView(structure);

        // Check properties on "cloned" structure
        AssistStructure clone = cloneThroughParcel(structure);
        assertStructureWithOneBigView(clone);
    }

    private void assertStructureWithOneBigView(AssistStructure structure) {
        try {
            assertThat(structure.getWindowNodeCount()).isEqualTo(1);

            ViewNode rootView = structure.getWindowNodeAt(0).getRootViewNode();
            assertThat(rootView.getClassName()).isEqualTo(FrameLayout.class.getName());
            assertThat(rootView.getChildCount()).isEqualTo(2); // title and parent
            assertThat(rootView.getAutofillId()).isNotNull();
            assertThat(rootView.getImportantForAutofill()).isEqualTo(IMPORTANT_FOR_AUTOFILL_AUTO);

            // Title
            ViewNode title = rootView.getChildAt(0);
            assertThat(title.getClassName()).isEqualTo(TextView.class.getName());
            assertThat(title.getChildCount()).isEqualTo(0);
            assertThat(title.getText().toString()).isEqualTo("My Title");
            assertThat(title.getAutofillId()).isNotNull();

            // Parent
            ViewNode parent = rootView.getChildAt(1);
            assertThat(parent.getClassName()).isEqualTo(LinearLayout.class.getName());
            assertThat(parent.getChildCount()).isEqualTo(1);
            assertThat(parent.getIdEntry()).isEqualTo("parent");
            assertThat(parent.getAutofillId()).isNotNull();

            // Children
            ViewNode bigView = parent.getChildAt(0);
            assertBigView(bigView);
        } catch (RuntimeException | Error e) {
            Log.e(TAG, "dumping structure because of error: " + e);
            structure.dump(true);
            throw e;
        }
    }

    @Test
    public void testViewNodeParcelableForCredentialManager() {
        Log.d(TAG, "Adding view with " + BIG_VIEW_SIZE + " chars");

        View view = newCredentialView();
        mActivity.addView(view);
        waitUntilViewsAreLaidOff();

        assertThat(view.getViewRootImpl()).isNotNull();
        ViewNodeBuilder viewStructure = new ViewNodeBuilder();
        viewStructure.setAutofillId(view.getAutofillId());
        viewStructure.setPendingCredentialRequest(view.getPendingCredentialRequest(),
                view.getPendingCredentialCallback());
        view.onProvideAutofillStructure(viewStructure, /* flags= */ 0);
        ViewNodeParcelable viewNodeParcelable = new ViewNodeParcelable(viewStructure.getViewNode());

        // Check properties on "original" view node.
        assertCredentialView(viewNodeParcelable.getViewNode());

        // Check properties on "cloned" view node.
        ViewNodeParcelable clone = cloneThroughParcel(viewNodeParcelable);
        assertCredentialView(clone.getViewNode());
    }

    @Test
    public void testViewNodeClearCredentialManagerRequest() {
        Log.d(TAG, "Adding view with " + BIG_VIEW_SIZE + " chars");

        View view = newCredentialView();
        mActivity.addView(view);
        waitUntilViewsAreLaidOff();

        assertThat(view.getViewRootImpl()).isNotNull();
        ViewNodeBuilder viewStructure = new ViewNodeBuilder();
        if (view.getPendingCredentialRequest() != null
                && view.getPendingCredentialCallback() != null) {
            viewStructure.setPendingCredentialRequest(view.getPendingCredentialRequest(),
                    view.getPendingCredentialCallback());
        }

        assertEquals(viewStructure.getPendingCredentialRequest(), GET_CREDENTIAL_REQUEST);
        assertEquals(viewStructure.getPendingCredentialCallback(),
                GET_CREDENTIAL_REQUEST_CALLBACK);

        viewStructure.clearCredentialManagerRequest();

        assertNull(viewStructure.getPendingCredentialRequest());
        assertNull(viewStructure.getPendingCredentialCallback());
    }

    @Test
    public void testViewNodeParcelableForAutofill() {
        Log.d(TAG, "Adding view with " + BIG_VIEW_SIZE + " chars");

        View view = newBigView();
        mActivity.addView(view);
        waitUntilViewsAreLaidOff();

        assertThat(view.getViewRootImpl()).isNotNull();
        ViewNodeBuilder viewStructure = new ViewNodeBuilder();
        viewStructure.setAutofillId(view.getAutofillId());
        view.onProvideAutofillStructure(viewStructure, /* flags= */ 0);
        ViewNodeParcelable viewNodeParcelable = new ViewNodeParcelable(viewStructure.getViewNode());

        // Check properties on "original" view node.
        assertBigView(viewNodeParcelable.getViewNode());

        // Check properties on "cloned" view node.
        ViewNodeParcelable clone = cloneThroughParcel(viewNodeParcelable);
        assertBigView(clone.getViewNode());
    }

    @Test
    public void testViewNodeParcelableControlFlags() {
        View view = newBigView();
        mActivity.addView(view);
        waitUntilViewsAreLaidOff();

        assertThat(view.getViewRootImpl()).isNotNull();
        ViewNodeBuilder viewStructure = new ViewNodeBuilder();
        viewStructure.setAutofillId(view.getAutofillId());
        view.onProvideAutofillStructure(viewStructure, /* flags= */ 0);

        // Set highest and lowest control flags
        viewStructure.setReceiveContentMimeTypes(new String[] {});
        viewStructure.setLocaleList(new LocaleList());

        ViewNodeParcelable viewNodeParcelable = new ViewNodeParcelable(viewStructure.getViewNode());

        // Check properties on "original" view node.
        assertBigView(viewNodeParcelable.getViewNode());
        assertControlFlags(viewNodeParcelable.getViewNode());

        // Check properties on "cloned" view node.
        ViewNodeParcelable clone = cloneThroughParcel(viewNodeParcelable);
        assertBigView(clone.getViewNode());
        assertControlFlags(clone.getViewNode());

    }

    private EditText newSmallView() {
        EditText view = new EditText(mContext);
        view.setText("I AM GROOT");
        view.setMinEms(MIN_TEXT_EMS);
        view.setMaxEms(MAX_TEXT_EMS);
        view.setAutofillId(AUTOFILL_ID);
        view.setAutofillHints(AUTOFILL_HINTS);
        view.setFilters(new InputFilter[] { new InputFilter.LengthFilter(MAX_TEXT_LENGTH) });
        view.setImportantForAutofill(IMPORTANT_FOR_AUTOFILL_YES);
        return view;
    }

    private void assertSmallView(ViewNode view) {
        assertThat(view.getClassName()).isEqualTo(EditText.class.getName());
        assertThat(view.getChildCount()).isEqualTo(0);
        assertThat(view.getIdEntry()).isNull();
        assertThat(view.getAutofillId()).isNotNull();
        assertThat(view.getText().toString()).isEqualTo("I AM GROOT");

        assertThat(view.getAutofillType()).isEqualTo(AUTOFILL_TYPE_TEXT);

        // fields controlled by mAutofillFlag
        assertThat(view.getAutofillId().getViewId()).isEqualTo(2);
        assertThat(view.getAutofillHints()[0]).isEqualTo(AUTOFILL_HINTS);
        assertThat(view.getMinTextEms()).isEqualTo(MIN_TEXT_EMS);
        assertThat(view.getMaxTextEms()).isEqualTo(MAX_TEXT_EMS);
        assertThat(view.getMaxTextLength()).isEqualTo(MAX_TEXT_LENGTH);
        assertThat(view.getImportantForAutofill()).isEqualTo(IMPORTANT_FOR_AUTOFILL_YES);
    }

    private EditText newBigView() {
        EditText view = new EditText(mContext);
        view.setText("Big Hint in Little View");
        view.setAutofillHints(BIG_STRING);
        view.setPendingCredentialRequest(GET_CREDENTIAL_REQUEST, GET_CREDENTIAL_REQUEST_CALLBACK);
        return view;
    }

    private EditText newCredentialView() {
        EditText view = new EditText(mContext);
        view.setText("Credential Request");
        view.setPendingCredentialRequest(GET_CREDENTIAL_REQUEST, GET_CREDENTIAL_REQUEST_CALLBACK);
        return view;
    }

    private void assertBigView(ViewNode view) {
        assertThat(view.getClassName()).isEqualTo(EditText.class.getName());
        assertThat(view.getChildCount()).isEqualTo(0);
        assertThat(view.getIdEntry()).isNull();
        assertThat(view.getAutofillId()).isNotNull();
        assertThat(view.getText().toString()).isEqualTo("Big Hint in Little View");
        assertThat(view.getText().toString()).isEqualTo("Big Hint in Little View");

        String[] hints = view.getAutofillHints();
        assertThat(hints.length).isEqualTo(1);
        String hint = hints[0];
        // Cannot assert the whole string because it takes too long and crashes the test by ANR
        assertThat(hint.length()).isEqualTo(BIG_VIEW_SIZE);
        assertThat(hint.charAt(0)).isEqualTo(BIG_VIEW_CHAR);
        assertThat(hint.charAt(BIG_VIEW_SIZE - 1)).isEqualTo(BIG_VIEW_CHAR);
    }

    private void assertCredentialView(ViewNode view) {
        assertThat(view.getClassName()).isEqualTo(EditText.class.getName());
        assertThat(view.getChildCount()).isEqualTo(0);
        assertThat(view.getIdEntry()).isNull();
        assertThat(view.getAutofillId()).isNotNull();
        assertThat(view.getText().toString()).isEqualTo("Big Hint in Little View");

        assertThat(view.getPendingCredentialRequest()).isEqualTo(GET_CREDENTIAL_REQUEST);
        assertThat(view.getPendingCredentialCallback()).isEqualTo(GET_CREDENTIAL_REQUEST_CALLBACK);
    }

    /**
     * Assert the lowest and highest bit control flags.
     *
     * The lowest and highest flags are {@link ViewNode#FLAGS_HAS_LOCALE_LIST} and
     * {@link ViewNode#FLAGS_HAS_MIME_TYPES} respectively, so we check these two during parceling to
     * make sure the entire range of control flags are copied.
     *
     * TODO: Need to change this test if the flag bits are added/changed in the future.
     */
    private void assertControlFlags(ViewNode view) {
        assertThat(view.getReceiveContentMimeTypes()).isNotNull();
        assertThat(view.getLocaleList()).isNotNull();
    }

    private ViewNodeParcelable cloneThroughParcel(ViewNodeParcelable viewNodeParcelable) {
        Parcel parcel = Parcel.obtain();

        try {
            // Write to parcel
            parcel.setDataPosition(0); // Validity Check
            viewNodeParcelable.writeToParcel(parcel, NO_FLAGS);

            // Read from parcel
            parcel.setDataPosition(0);
            ViewNodeParcelable clone = ViewNodeParcelable.CREATOR.createFromParcel(parcel);
            assertThat(clone).isNotNull();
            return clone;
        } finally {
            parcel.recycle();
        }
    }

    private AssistStructure cloneThroughParcel(AssistStructure structure) {
        Parcel parcel = Parcel.obtain();

        try {
            // Write to parcel
            parcel.setDataPosition(0); // Validity Check
            structure.writeToParcel(parcel, NO_FLAGS);

            // Read from parcel
            parcel.setDataPosition(0);
            AssistStructure clone = AssistStructure.CREATOR.createFromParcel(parcel);
            assertThat(clone).isNotNull();
            return clone;
        } finally {
            parcel.recycle();
        }
    }

    private void waitUntilViewsAreLaidOff() {
        // TODO: use a more robust mechanism than just sleeping
        SystemClock.sleep(3000);
    }

    // TODO: use some common helper
    private static String repeat(char c, int size) {
        StringBuilder builder = new StringBuilder(size);
        for (int i = 1; i <= size; i++) {
            builder.append(c);
        }
        return builder.toString();
    }
}
