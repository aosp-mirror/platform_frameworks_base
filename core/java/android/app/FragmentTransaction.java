package android.app;

/**
 * API for performing a set of Fragment operations.
 */
public interface FragmentTransaction {
    public FragmentTransaction add(Fragment fragment, int containerViewId);
    public FragmentTransaction add(Fragment fragment, String name, int containerViewId);
    public FragmentTransaction remove(Fragment fragment);
    public void commit();
}
