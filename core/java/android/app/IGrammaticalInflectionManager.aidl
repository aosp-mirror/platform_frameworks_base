package android.app;


/**
 * Internal interface used to control app-specific gender.
 *
 * <p>Use the {@link android.app.GrammarInflectionManager} class rather than going through
 * this Binder interface directly. See {@link android.app.GrammarInflectionManager} for
 * more complete documentation.
 *
 * @hide
 */
 interface IGrammaticalInflectionManager {

     /**
      * Sets a specified app’s app-specific grammatical gender.
      */
     void setRequestedApplicationGrammaticalGender(String appPackageName, int userId, int gender);

     /**
      * Sets the grammatical gender to system.
      */
     void setSystemWideGrammaticalGender(int userId, int gender);

     /**
      * Gets the grammatical gender from system.
      */
     int getSystemGrammaticalGender(int userId);
 }
