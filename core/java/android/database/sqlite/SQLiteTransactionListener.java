package android.database.sqlite;

/**
 * A listener for transaction events.
 */
public interface SQLiteTransactionListener {
    /**
     * Called immediately after the transaction begins.
     */
    void onBegin();

    /**
     * Called immediately before commiting the transaction.
     */
    void onCommit();

    /**
     * Called if the transaction is about to be rolled back.
     */
    void onRollback();
}
