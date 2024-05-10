package junit.framework;

// android-changed add @hide
/**
 * @hide not needed for public API
 */
public class ComparisonCompactor {

	private static final String ELLIPSIS= "...";
	private static final String DELTA_END= "]";
	private static final String DELTA_START= "[";
	
	private int fContextLength;
	private String fExpected;
	private String fActual;
	private int fPrefix;
	private int fSuffix;

	public ComparisonCompactor(int contextLength, String expected, String actual) {
		fContextLength= contextLength;
		fExpected= expected;
		fActual= actual;
	}

	public String compact(String message) {
		if (fExpected == null || fActual == null || areStringsEqual()) {
			// android-changed use local method instead of Assert.format, since
			// the later is not part of Android API till API 16
			return format(message, fExpected, fActual);
		}
		findCommonPrefix();
		findCommonSuffix();
		String expected= compactString(fExpected);
		String actual= compactString(fActual);
		// android-changed use local format method
		return format(message, expected, actual);
	}

	private String compactString(String source) {
		String result= DELTA_START + source.substring(fPrefix, source.length() - fSuffix + 1) + DELTA_END;
		if (fPrefix > 0)
			result= computeCommonPrefix() + result;
		if (fSuffix > 0)
			result= result + computeCommonSuffix();
		return result;
	}

	private void findCommonPrefix() {
		fPrefix= 0;
		int end= Math.min(fExpected.length(), fActual.length());
		for (; fPrefix < end; fPrefix++) {
			if (fExpected.charAt(fPrefix) != fActual.charAt(fPrefix))
				break;
		}
	}

	private void findCommonSuffix() {
		int expectedSuffix= fExpected.length() - 1;
		int actualSuffix= fActual.length() - 1;
		for (; actualSuffix >= fPrefix && expectedSuffix >= fPrefix; actualSuffix--, expectedSuffix--) {
			if (fExpected.charAt(expectedSuffix) != fActual.charAt(actualSuffix))
				break;
		}
		fSuffix=  fExpected.length() - expectedSuffix;
	}

	private String computeCommonPrefix() {
		return (fPrefix > fContextLength ? ELLIPSIS : "") + fExpected.substring(Math.max(0, fPrefix - fContextLength), fPrefix);
	}

	private String computeCommonSuffix() {
		int end= Math.min(fExpected.length() - fSuffix + 1 + fContextLength, fExpected.length());
		return fExpected.substring(fExpected.length() - fSuffix + 1, end) + (fExpected.length() - fSuffix + 1 < fExpected.length() - fContextLength ? ELLIPSIS : "");
	}

	private boolean areStringsEqual() {
		return fExpected.equals(fActual);
	}

	// android-changed copy of Assert.format for reasons described above
	private static String format(String message, Object expected, Object actual) {
        	String formatted= "";
        	if (message != null && message.length() > 0)
            		formatted= message+" ";
        	return formatted+"expected:<"+expected+"> but was:<"+actual+">";
	}
}
