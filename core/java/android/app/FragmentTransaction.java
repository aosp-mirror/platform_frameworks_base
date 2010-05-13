package android.app;

/**
 * API for performing a set of Fragment operations.
 */
public interface FragmentTransaction {
    public FragmentTransaction add(Fragment fragment, String tag);
    public FragmentTransaction add(Fragment fragment, int containerViewId);
    public FragmentTransaction replace(Fragment fragment, int containerViewId);
    public FragmentTransaction remove(Fragment fragment);
    
    /**
     * Bit mask that is set for all enter transition.
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
    
    public FragmentTransaction setTransition(int transit);
    public FragmentTransaction setTransitionStyle(int styleRes);
    
    public FragmentTransaction addToBackStack(String name);
    public void commit();
}
