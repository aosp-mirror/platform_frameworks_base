package android.animation;

import com.android.frameworks.coretests.R;

import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.SmallTest;
import android.view.View;

import java.util.ArrayList;

public class AnimatorSetActivityTest extends ActivityInstrumentationTestCase2<AnimatorSetActivity> {

    private AnimatorSetActivity mActivity;
    private ObjectAnimator a1,a2,a3;
    private ValueAnimator a4,a5;

    public AnimatorSetActivityTest() {
        super(AnimatorSetActivity.class);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mActivity = getActivity();

        View square1 = mActivity.findViewById(R.id.square1);
        View square2 = mActivity.findViewById(R.id.square2);
        View square3 = mActivity.findViewById(R.id.square3);
        a1 = ObjectAnimator.ofFloat(square1, View.TRANSLATION_X, 0f, 500f, 0f).setDuration(250);
        a2 = ObjectAnimator.ofFloat(square2, View.ALPHA, 1f, 0f).setDuration(350);
        a3 = ObjectAnimator.ofFloat(square3, View.ROTATION, 0, 90).setDuration(450);
        a4 = ValueAnimator.ofInt(100, 200).setDuration(450);
        a5 = ValueAnimator.ofFloat(10f, 5f).setDuration(850);
    }

    @Override
    public void tearDown() throws Exception {
        mActivity = null;
        a1 = null;
        a2 = null;
        a3 = null;
        a4 = null;
        a5 = null;
        super.tearDown();
    }

    @SmallTest
    public void testGetChildAnimations() {
        AnimatorSet s1 = new AnimatorSet();
        s1.playTogether(a1, a2, a3);
        ArrayList<Animator> children = s1.getChildAnimations();
        assertEquals(3, children.size());
        assertTrue(children.contains(a1));
        assertTrue(children.contains(a2));
        assertTrue(children.contains(a3));

        AnimatorSet s2 = new AnimatorSet();
        s2.playSequentially(a1, a2, a3);
        children = s2.getChildAnimations();
        assertEquals(3, children.size());
        assertTrue(children.contains(a1));
        assertTrue(children.contains(a2));
        assertTrue(children.contains(a3));

        AnimatorSet s3 = new AnimatorSet();
        s3.play(a1).before(a2).after(s1).with(s2).after(a3);
        ArrayList<Animator> s3Children = s3.getChildAnimations();
        assertNotNull(s3Children);
        assertEquals(5, s3Children.size());
        assertTrue(s3Children.contains(a1));
        assertTrue(s3Children.contains(a2));
        assertTrue(s3Children.contains(a3));
        assertTrue(s3Children.contains(s1));
        assertTrue(s3Children.contains(s2));

        AnimatorSet s4 = new AnimatorSet();
        s4.playSequentially(s3Children);
        ArrayList<Animator> s4Children = s4.getChildAnimations();
        assertNotNull(s4Children);
        assertEquals(s3Children.size(), s4Children.size());
        for (int i = 0; i < s3Children.size(); i++) {
            Animator child = s3Children.get(i);
            assertTrue(s4Children.contains(child));
        }
    }

    @SmallTest
    public void testTotalDuration() {
        ArrayList<Animator> list = new ArrayList<>(5);
        list.add(a1);
        list.add(a2);
        list.add(a3);
        list.add(a4);
        list.add(a5);

        // Run animations sequentially and test the total duration against sum of durations.
        AnimatorSet s1 = new AnimatorSet();
        s1.playSequentially(list);
        long totalDuration = 0;
        for (int i = 0; i < list.size(); i++) {
            Animator anim = list.get(i);
            anim.setStartDelay(0);
            totalDuration += list.get(i).getDuration();
        }
        assertEquals(totalDuration, s1.getTotalDuration());

        // Add delay to set, and test total duration
        s1.setStartDelay(200);
        assertEquals(totalDuration + 200, s1.getTotalDuration());

        a1.setStartDelay(100);
        assertEquals(totalDuration + 200 + 100, s1.getTotalDuration());

        // Run animations simultaneously, test the total duration against the longest duration
        AnimatorSet s2 = new AnimatorSet();
        s2.playTogether(list);
        long maxDuration = 0;
        for (int i = 0; i < list.size(); i++) {
            long duration = list.get(i).getDuration();
            list.get(i).setStartDelay(100);
            maxDuration = maxDuration > (duration + 100) ? maxDuration : (duration + 100);
        }
        assertEquals(maxDuration, s2.getTotalDuration());

        // Form a cycle in the AnimatorSet and test the total duration
        AnimatorSet s3 = new AnimatorSet();
        s3.play(a1).before(a2).after(a3);
        s3.play(a1).after(a2).with(a4);
        assertEquals(AnimatorSet.DURATION_INFINITE, s3.getTotalDuration());

        // Put all the animators in a cycle
        AnimatorSet s4 = new AnimatorSet();
        s4.play(a1).after(a2);
        s4.play(a2).after(a1);
        assertEquals(AnimatorSet.DURATION_INFINITE, s4.getTotalDuration());

        // No cycle in the set, run a2, a1, a3 in sequence, and a2, a4, a5 together
        AnimatorSet s5 = new AnimatorSet();
        s5.play(a1).after(a2).before(a3);
        s5.play(a2).with(a4).with(a5);
        long duration = a1.getDuration() + a1.getStartDelay() + a2.getDuration() + a2
                .getStartDelay() + a3.getDuration() + a3.getStartDelay();
        long a4Duration = a4.getDuration() + a4.getStartDelay();
        long a5Duration = a5.getDuration() + a5.getStartDelay();
        duration = Math.max(duration, a4Duration);
        duration = Math.max(duration, a5Duration);
        assertEquals(duration, s5.getTotalDuration());

        // Change one animator to repeat infinitely and test the total time
        a3.setRepeatCount(ValueAnimator.INFINITE);
        assertEquals(AnimatorSet.DURATION_INFINITE, s5.getTotalDuration());

    }

}
