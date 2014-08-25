package android.app;

import android.util.Pair;
import android.view.View;

/**
 * API for performing a set of Fragment operations.
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about using fragments, read the
 * <a href="{@docRoot}guide/topics/fundamentals/fragments.html">Fragments</a> developer guide.</p>
 * </div>
 */
public abstract class FragmentTransaction {
    /**
     * Calls {@link #add(int, Fragment, String)} with a 0 containerViewId.
     */
    public abstract FragmentTransaction add(Fragment fragment, String tag);
    
    /**
     * Calls {@link #add(int, Fragment, String)} with a null tag.
     */
    public abstract FragmentTransaction add(int containerViewId, Fragment fragment);
    
    /**
     * Add a fragment to the activity state.  This fragment may optionally
     * also have its view (if {@link Fragment#onCreateView Fragment.onCreateView}
     * returns non-null) into a container view of the activity.
     * 
     * @param containerViewId Optional identifier of the container this fragment is
     * to be placed in.  If 0, it will not be placed in a container.
     * @param fragment The fragment to be added.  This fragment must not already
     * be added to the activity.
     * @param tag Optional tag name for the fragment, to later retrieve the
     * fragment with {@link FragmentManager#findFragmentByTag(String)
     * FragmentManager.findFragmentByTag(String)}.
     * 
     * @return Returns the same FragmentTransaction instance.
     */
    public abstract FragmentTransaction add(int containerViewId, Fragment fragment, String tag);
    
    /**
     * Calls {@link #replace(int, Fragment, String)} with a null tag.
     */
    public abstract FragmentTransaction replace(int containerViewId, Fragment fragment);
    
    /**
     * Replace an existing fragment that was added to a container.  This is
     * essentially the same as calling {@link #remove(Fragment)} for all
     * currently added fragments that were added with the same containerViewId
     * and then {@link #add(int, Fragment, String)} with the same arguments
     * given here.
     * 
     * @param containerViewId Identifier of the container whose fragment(s) are
     * to be replaced.
     * @param fragment The new fragment to place in the container.
     * @param tag Optional tag name for the fragment, to later retrieve the
     * fragment with {@link FragmentManager#findFragmentByTag(String)
     * FragmentManager.findFragmentByTag(String)}.
     * 
     * @return Returns the same FragmentTransaction instance.
     */
    public abstract FragmentTransaction replace(int containerViewId, Fragment fragment, String tag);
    
    /**
     * Remove an existing fragment.  If it was added to a container, its view
     * is also removed from that container.
     * 
     * @param fragment The fragment to be removed.
     * 
     * @return Returns the same FragmentTransaction instance.
     */
    public abstract FragmentTransaction remove(Fragment fragment);
    
    /**
     * Hides an existing fragment.  This is only relevant for fragments whose
     * views have been added to a container, as this will cause the view to
     * be hidden.
     * 
     * @param fragment The fragment to be hidden.
     * 
     * @return Returns the same FragmentTransaction instance.
     */
    public abstract FragmentTransaction hide(Fragment fragment);
    
    /**
     * Shows a previously hidden fragment.  This is only relevant for fragments whose
     * views have been added to a container, as this will cause the view to
     * be shown.
     * 
     * @param fragment The fragment to be shown.
     * 
     * @return Returns the same FragmentTransaction instance.
     */
    public abstract FragmentTransaction show(Fragment fragment);

    /**
     * Detach the given fragment from the UI.  This is the same state as
     * when it is put on the back stack: the fragment is removed from
     * the UI, however its state is still being actively managed by the
     * fragment manager.  When going into this state its view hierarchy
     * is destroyed.
     *
     * @param fragment The fragment to be detached.
     *
     * @return Returns the same FragmentTransaction instance.
     */
    public abstract FragmentTransaction detach(Fragment fragment);

    /**
     * Re-attach a fragment after it had previously been detached from
     * the UI with {@link #detach(Fragment)}.  This
     * causes its view hierarchy to be re-created, attached to the UI,
     * and displayed.
     *
     * @param fragment The fragment to be attached.
     *
     * @return Returns the same FragmentTransaction instance.
     */
    public abstract FragmentTransaction attach(Fragment fragment);

    /**
     * @return <code>true</code> if this transaction contains no operations,
     * <code>false</code> otherwise.
     */
    public abstract boolean isEmpty();
    
    /**
     * Bit mask that is set for all enter transitions.
     */
    public static final int TRANSIT_ENTER_MASK = 0x1000;
    
    /**
     * Bit mask that is set for all exit transitions.
     */
    public static final int TRANSIT_EXIT_MASK = 0x2000;
    
    /** Not set up for a transition. */
    public static final int TRANSIT_UNSET = -1;
    /** No animation for transition. */
    public static final int TRANSIT_NONE = 0;
    /** Fragment is being added onto the stack */
    public static final int TRANSIT_FRAGMENT_OPEN = 1 | TRANSIT_ENTER_MASK;
    /** Fragment is being removed from the stack */
    public static final int TRANSIT_FRAGMENT_CLOSE = 2 | TRANSIT_EXIT_MASK;
    /** Fragment should simply fade in or out; that is, no strong navigation associated
     * with it except that it is appearing or disappearing for some reason. */
    public static final int TRANSIT_FRAGMENT_FADE = 3 | TRANSIT_ENTER_MASK;

    /**
     * Set specific animation resources to run for the fragments that are
     * entering and exiting in this transaction. These animations will not be
     * played when popping the back stack.
     */
    public abstract FragmentTransaction setCustomAnimations(int enter, int exit);

    /**
     * Set specific animation resources to run for the fragments that are
     * entering and exiting in this transaction. The <code>popEnter</code>
     * and <code>popExit</code> animations will be played for enter/exit
     * operations specifically when popping the back stack.
     */
    public abstract FragmentTransaction setCustomAnimations(int enter, int exit,
            int popEnter, int popExit);

    /**
     * Select a standard transition animation for this transaction.  May be
     * one of {@link #TRANSIT_NONE}, {@link #TRANSIT_FRAGMENT_OPEN},
     * or {@link #TRANSIT_FRAGMENT_CLOSE}
     */
    public abstract FragmentTransaction setTransition(int transit);

    /**
     * Set a {@link android.transition.Transition} resource id to use with this transaction.
     * <var>transitionId</var> will be played for fragments when going forward and when popping
     * the back stack.
     * @param sceneRootId The ID of the element acting as the scene root for the transition.
     *                    This should be a ViewGroup containing all Fragments in the transaction.
     * @param transitionId The resource ID for the Transition used during the Fragment transaction.
     */
    public abstract FragmentTransaction setCustomTransition(int sceneRootId, int transitionId);

    /**
     * Used with {@link #setCustomTransition(int, int)} to map a View from a removed or hidden
     * Fragment to a View from a shown or added Fragment.
     * <var>sharedElement</var> must have a unique transitionName in the View hierarchy.
     * @param sharedElement A View in a disappearing Fragment to match with a View in an
     *                      appearing Fragment.
     * @param name The transitionName for a View in an appearing Fragment to match to the shared
     *             element.
     */
    public abstract FragmentTransaction addSharedElement(View sharedElement, String name);

    /**
     * TODO: remove from API
     * @hide
     */
    public abstract FragmentTransaction setSharedElement(View sharedElement, String name);

    /**
     * TODO: remove from API
     * @hide
     */
    public abstract FragmentTransaction setSharedElements(Pair<View, String>... sharedElements);

    /**
     * Set a custom style resource that will be used for resolving transit
     * animations.
     */
    public abstract FragmentTransaction setTransitionStyle(int styleRes);
    
    /**
     * Add this transaction to the back stack.  This means that the transaction
     * will be remembered after it is committed, and will reverse its operation
     * when later popped off the stack.
     *
     * @param name An optional name for this back stack state, or null.
     */
    public abstract FragmentTransaction addToBackStack(String name);

    /**
     * Returns true if this FragmentTransaction is allowed to be added to the back
     * stack. If this method would return false, {@link #addToBackStack(String)}
     * will throw {@link IllegalStateException}.
     *
     * @return True if {@link #addToBackStack(String)} is permitted on this transaction.
     */
    public abstract boolean isAddToBackStackAllowed();

    /**
     * Disallow calls to {@link #addToBackStack(String)}. Any future calls to
     * addToBackStack will throw {@link IllegalStateException}. If addToBackStack
     * has already been called, this method will throw IllegalStateException.
     */
    public abstract FragmentTransaction disallowAddToBackStack();

    /**
     * Set the full title to show as a bread crumb when this transaction
     * is on the back stack, as used by {@link FragmentBreadCrumbs}.
     *
     * @param res A string resource containing the title.
     */
    public abstract FragmentTransaction setBreadCrumbTitle(int res);

    /**
     * Like {@link #setBreadCrumbTitle(int)} but taking a raw string; this
     * method is <em>not</em> recommended, as the string can not be changed
     * later if the locale changes.
     */
    public abstract FragmentTransaction setBreadCrumbTitle(CharSequence text);

    /**
     * Set the short title to show as a bread crumb when this transaction
     * is on the back stack, as used by {@link FragmentBreadCrumbs}.
     *
     * @param res A string resource containing the title.
     */
    public abstract FragmentTransaction setBreadCrumbShortTitle(int res);

    /**
     * Like {@link #setBreadCrumbShortTitle(int)} but taking a raw string; this
     * method is <em>not</em> recommended, as the string can not be changed
     * later if the locale changes.
     */
    public abstract FragmentTransaction setBreadCrumbShortTitle(CharSequence text);

    /**
     * Schedules a commit of this transaction.  The commit does
     * not happen immediately; it will be scheduled as work on the main thread
     * to be done the next time that thread is ready.
     *
     * <p class="note">A transaction can only be committed with this method
     * prior to its containing activity saving its state.  If the commit is
     * attempted after that point, an exception will be thrown.  This is
     * because the state after the commit can be lost if the activity needs to
     * be restored from its state.  See {@link #commitAllowingStateLoss()} for
     * situations where it may be okay to lose the commit.</p>
     * 
     * @return Returns the identifier of this transaction's back stack entry,
     * if {@link #addToBackStack(String)} had been called.  Otherwise, returns
     * a negative number.
     */
    public abstract int commit();

    /**
     * Like {@link #commit} but allows the commit to be executed after an
     * activity's state is saved.  This is dangerous because the commit can
     * be lost if the activity needs to later be restored from its state, so
     * this should only be used for cases where it is okay for the UI state
     * to change unexpectedly on the user.
     */
    public abstract int commitAllowingStateLoss();
}
