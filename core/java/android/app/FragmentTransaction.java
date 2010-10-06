package android.app;

/**
 * API for performing a set of Fragment operations.
 */
public interface FragmentTransaction {
    /**
     * Calls {@link #add(int, Fragment, String)} with a 0 containerViewId.
     */
    public FragmentTransaction add(Fragment fragment, String tag);
    
    /**
     * Calls {@link #add(int, Fragment, String)} with a null tag.
     */
    public FragmentTransaction add(int containerViewId, Fragment fragment);
    
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
     * fragment with {@link Activity#findFragmentByTag(String)
     * Activity.findFragmentByTag(String)}.
     * 
     * @return Returns the same FragmentTransaction instance.
     */
    public FragmentTransaction add(int containerViewId, Fragment fragment, String tag);
    
    /**
     * Calls {@link #replace(int, Fragment, String)} with a null tag.
     */
    public FragmentTransaction replace(int containerViewId, Fragment fragment);
    
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
     * fragment with {@link Activity#findFragmentByTag(String)
     * Activity.findFragmentByTag(String)}.
     * 
     * @return Returns the same FragmentTransaction instance.
     */
    public FragmentTransaction replace(int containerViewId, Fragment fragment, String tag);
    
    /**
     * Remove an existing fragment.  If it was added to a container, its view
     * is also removed from that container.
     * 
     * @param fragment The fragment to be removed.
     * 
     * @return Returns the same FragmentTransaction instance.
     */
    public FragmentTransaction remove(Fragment fragment);
    
    /**
     * Hides an existing fragment.  This is only relevant for fragments whose
     * views have been added to a container, as this will cause the view to
     * be hidden.
     * 
     * @param fragment The fragment to be hidden.
     * 
     * @return Returns the same FragmentTransaction instance.
     */
    public FragmentTransaction hide(Fragment fragment);
    
    /**
     * Hides a previously hidden fragment.  This is only relevant for fragments whose
     * views have been added to a container, as this will cause the view to
     * be shown.
     * 
     * @param fragment The fragment to be shown.
     * 
     * @return Returns the same FragmentTransaction instance.
     */
    public FragmentTransaction show(Fragment fragment);

    /**
     * @return <code>true</code> if this transaction contains no operations,
     * <code>false</code> otherwise.
     */
    public boolean isEmpty();
    
    /**
     * Bit mask that is set for all enter transitions.
     */
    public final int TRANSIT_ENTER_MASK = 0x1000;
    
    /**
     * Bit mask that is set for all exit transitions.
     */
    public final int TRANSIT_EXIT_MASK = 0x2000;
    
    /** Not set up for a transition. */
    public final int TRANSIT_UNSET = -1;
    /** No animation for transition. */
    public final int TRANSIT_NONE = 0;
    /** Fragment is being added onto the stack */
    public final int TRANSIT_FRAGMENT_OPEN = 1 | TRANSIT_ENTER_MASK;
    /** Fragment is being removed from the stack */
    public final int TRANSIT_FRAGMENT_CLOSE = 2 | TRANSIT_EXIT_MASK;
    /** Fragment is being added in a 'next' operation*/
    public final int TRANSIT_FRAGMENT_NEXT = 3 | TRANSIT_ENTER_MASK;
    /** Fragment is being removed in a 'previous' operation */
    public final int TRANSIT_FRAGMENT_PREV = 4 | TRANSIT_EXIT_MASK;

    /**
     * Set specific animation resources to run for the fragments that are
     * entering and exiting in this transaction.
     */
    public FragmentTransaction setCustomAnimations(int enter, int exit);
    
    /**
     * Select a standard transition animation for this transaction.  May be
     * one of {@link #TRANSIT_NONE}, {@link #TRANSIT_FRAGMENT_OPEN},
     * or {@link #TRANSIT_FRAGMENT_CLOSE}
     */
    public FragmentTransaction setTransition(int transit);

    /**
     * Set a custom style resource that will be used for resolving transit
     * animations.
     */
    public FragmentTransaction setTransitionStyle(int styleRes);
    
    /**
     * Add this transaction to the back stack.  This means that the transaction
     * will be remembered after it is committed, and will reverse its operation
     * when later popped off the stack.
     *
     * @param name An optional name for this back stack state, or null.
     */
    public FragmentTransaction addToBackStack(String name);

    /**
     * Set the full title to show as a bread crumb when this transaction
     * is on the back stack, as used by {@link FragmentBreadCrumbs}.
     *
     * @param res A string resource containing the title.
     */
    public FragmentTransaction setBreadCrumbTitle(int res);

    /**
     * Like {@link #setBreadCrumbTitle(int)} but taking a raw string; this
     * method is <em>not</em> recommended, as the string can not be changed
     * later if the locale changes.
     */
    public FragmentTransaction setBreadCrumbTitle(CharSequence text);

    /**
     * Set the short title to show as a bread crumb when this transaction
     * is on the back stack, as used by {@link FragmentBreadCrumbs}.
     *
     * @param res A string resource containing the title.
     */
    public FragmentTransaction setBreadCrumbShortTitle(int res);

    /**
     * Like {@link #setBreadCrumbShortTitle(int)} but taking a raw string; this
     * method is <em>not</em> recommended, as the string can not be changed
     * later if the locale changes.
     */
    public FragmentTransaction setBreadCrumbShortTitle(CharSequence text);

    /**
     * Schedules a commit of this transaction.  Note that the commit does
     * not happen immediately; it will be scheduled as work on the main thread
     * to be done the next time that thread is ready.
     *
     * @return Returns the identifier of this transaction's back stack entry,
     * if {@link #addToBackStack(String)} had been called.  Otherwise, returns
     * a negative number.
     */
    public int commit();
}
