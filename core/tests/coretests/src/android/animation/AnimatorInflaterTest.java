/*
* Copyright (C) 2014 The Android Open Source Project
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

package android.animation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.test.filters.LargeTest;
import androidx.test.rule.ActivityTestRule;

import com.android.frameworks.coretests.R;

import org.junit.Rule;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

@LargeTest
public class AnimatorInflaterTest {
    @Rule
    public final ActivityTestRule<BasicAnimatorActivity> mActivityRule =
            new ActivityTestRule<>(BasicAnimatorActivity.class);

    private final Set<Integer> mIdentityHashes = new HashSet<>();

    private void assertUnique(Object object) {
        assertUnique(object, "");
    }

    private void assertUnique(Object object, String msg) {
        final int code = System.identityHashCode(object);
        assertTrue("object should be unique " + msg + ", obj:" + object, mIdentityHashes.add(code));
    }

    @Test
    public void testLoadStateListAnimator() {
        final BasicAnimatorActivity activity = mActivityRule.getActivity();
        StateListAnimator sla1 = AnimatorInflater.loadStateListAnimator(activity,
                R.anim.test_state_anim);
        sla1.setTarget(activity.mAnimatingButton);
        StateListAnimator sla2 = AnimatorInflater.loadStateListAnimator(activity,
                R.anim.test_state_anim);
        assertNull(sla2.getTarget());
        for (StateListAnimator sla : new StateListAnimator[]{sla1, sla2}) {
            assertUnique(sla);
            assertEquals(3, sla.getTuples().size());
            for (StateListAnimator.Tuple tuple : sla.getTuples()) {
                assertUnique(tuple);
                assertUnique(tuple.getAnimator());
            }
        }
    }

}
