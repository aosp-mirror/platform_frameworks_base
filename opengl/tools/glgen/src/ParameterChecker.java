
import java.io.BufferedReader;
import java.util.HashMap;

public class ParameterChecker {

    HashMap<String,String[]> map = new HashMap<String,String[]>();

    public ParameterChecker(BufferedReader reader) throws Exception {
        String s;
        while ((s = reader.readLine()) != null) {
            String[] tokens = s.split("\\s");
            map.put(tokens[0], tokens);
        }
    }

    public String[] getChecks(String functionName) {
        String[] checks = map.get(functionName);
        if (checks == null &&
            (functionName.endsWith("fv") ||
             functionName.endsWith("xv") ||
             functionName.endsWith("iv"))) {
            functionName = functionName.substring(0, functionName.length() - 2);
            checks = map.get(functionName);
        }
        return checks;
    }
}
