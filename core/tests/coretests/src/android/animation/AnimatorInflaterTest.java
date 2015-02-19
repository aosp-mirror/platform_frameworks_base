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

import android.test.ActivityInstrumentationTestCase2;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.android.frameworks.coretests.R;

public class AnimatorInflaterTest extends ActivityInstrumentationTestCase2<BasicAnimatorActivity>  {
    Set<Integer> identityHashes = new HashSet<Integer>();

    public AnimatorInflaterTest() {
        super(BasicAnimatorActivity.class);
    }

    private void assertUnique(Object object) {
        assertUnique(object, "");
    }

    private void assertUnique(Object object, String msg) {
        final int code = System.identityHashCode(object);
        assertTrue("object should be unique " + msg + ", obj:" + object, identityHashes.add(code));

    }

    public void testLoadStateListAnimator() {
        StateListAnimator sla1 = AnimatorInflater.loadStateListAnimator(getActivity(),
                R.anim.test_state_anim);
        sla1.setTarget(getActivity().mAnimatingButton);
        StateListAnimator sla2 = AnimatorInflater.loadStateListAnimator(getActivity(),
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
