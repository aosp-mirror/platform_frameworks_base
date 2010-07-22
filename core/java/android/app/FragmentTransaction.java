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
    /** Window has been added to the screen. */
    public final int TRANSIT_ENTER = 1 | TRANSIT_ENTER_MASK;
    /** Window has been removed from the screen. */
    public final int TRANSIT_EXIT = 2 | TRANSIT_EXIT_MASK;
    /** Window has been made visible. */
    public final int TRANSIT_SHOW = 3 | TRANSIT_ENTER_MASK;
    /** Window has been made invisible. */
    public final int TRANSIT_HIDE = 4 | TRANSIT_EXIT_MASK;
    /** The "application starting" preview window is no longer needed, and will
     * animate away to show the real window. */
    public final int TRANSIT_PREVIEW_DONE = 5;
    /** A window in a new activity is being opened on top of an existing one
     * in the same task. */
    public final int TRANSIT_ACTIVITY_OPEN = 6 | TRANSIT_ENTER_MASK;
    /** The window in the top-most activity is being closed to reveal the
     * previous activity in the same task. */
    public final int TRANSIT_ACTIVITY_CLOSE = 7 | TRANSIT_EXIT_MASK;
    /** A window in a new task is being opened on top of an existing one
     * in another activity's task. */
    public final int TRANSIT_TASK_OPEN = 8 | TRANSIT_ENTER_MASK;
    /** A window in the top-most activity is being closed to reveal the
     * previous activity in a different task. */
    public final int TRANSIT_TASK_CLOSE = 9 | TRANSIT_EXIT_MASK;
    /** A window in an existing task is being displayed on top of an existing one
     * in another activity's task. */
    public final int TRANSIT_TASK_TO_FRONT = 10 | TRANSIT_ENTER_MASK;
    /** A window in an existing task is being put below all other tasks. */
    public final int TRANSIT_TASK_TO_BACK = 11 | TRANSIT_EXIT_MASK;
    /** A window in a new activity that doesn't have a wallpaper is being
     * opened on top of one that does, effectively closing the wallpaper. */
    public final int TRANSIT_WALLPAPER_CLOSE = 12 | TRANSIT_EXIT_MASK;
    /** A window in a new activity that does have a wallpaper is being
     * opened on one that didn't, effectively opening the wallpaper. */
    public final int TRANSIT_WALLPAPER_OPEN = 13 | TRANSIT_ENTER_MASK;
    /** A window in a new activity is being opened on top of an existing one,
     * and both are on top of the wallpaper. */
    public final int TRANSIT_WALLPAPER_INTRA_OPEN = 14 | TRANSIT_ENTER_MASK;
    /** The window in the top-most activity is being closed to reveal the
     * previous activity, and both are on top of he wallpaper. */
    public final int TRANSIT_WALLPAPER_INTRA_CLOSE = 15 | TRANSIT_EXIT_MASK;
    
    public FragmentTransaction setCustomAnimations(int enter, int exit);
    
    public FragmentTransaction setTransition(int transit);
    public FragmentTransaction setTransitionStyle(int styleRes);
    
    public FragmentTransaction addToBackStack(String name);

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
