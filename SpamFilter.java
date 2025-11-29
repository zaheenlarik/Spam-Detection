package SpamDetector;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

public class SpamFilter {

    private static final String PYTHON_PATH = "python"; 
    private static final String SCRIPT_NAME = "predict.py";

    public static class Result {
        public String label;
        public double confidence;
    }

    public static Result classify(String message) {
        Result result = new Result();
        try {
            ProcessBuilder pb = new ProcessBuilder(
                PYTHON_PATH,
                SCRIPT_NAME,
                message
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String output = reader.readLine();

            try { if (!process.waitFor(3, TimeUnit.SECONDS)) { process.destroyForcibly(); } } catch (InterruptedException ignored) {}

            if (output != null && output.contains("|")) {
                String[] parts = output.split("\\|");
                result.label = parts[0].trim();
                try {
                    result.confidence = Double.parseDouble(parts[1].trim());
                } catch (NumberFormatException nfe) {
                    result.confidence = 0.0;
                }
            } else {
                result.label = "error";
                result.confidence = 0;
            }

        } catch (IOException e) {
            result.label = "error";
            result.confidence = 0;
        }

        return result;
    }
}
