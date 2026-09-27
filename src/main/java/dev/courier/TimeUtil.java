package dev.courier;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses/formats durations like "1h", "24h", "30m", "90s", or combinations like "1h30m". */
final class TimeUtil {

    private static final Pattern PART = Pattern.compile("(\\d+)([dhms])");

    private TimeUtil() {
    }

    static long parseMillis(String value, long fallback) {
        if (value == null || value.isBlank())
            return fallback;
        Matcher m = PART.matcher(value.toLowerCase());
        long total = 0;
        boolean found = false;
        while (m.find()) {
            found = true;
            long amount = Long.parseLong(m.group(1));
            total += switch (m.group(2)) {
                case "d" -> amount * 86_400_000L;
                case "h" -> amount * 3_600_000L;
                case "m" -> amount * 60_000L;
                default -> amount * 1_000L;
            };
        }
        return found ? total : fallback;
    }

    static String format(long millis) {
        if (millis <= 0)
            return "0s";
        long totalSeconds = millis / 1000;
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        StringBuilder sb = new StringBuilder();
        if (h > 0)
            sb.append(h).append("h ");
        if (h > 0 || m > 0)
            sb.append(m).append("m ");
        sb.append(s).append("s");
        return sb.toString();
    }
}
