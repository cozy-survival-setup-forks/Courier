package dev.courier;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses/formats durations like "1h", "24h", "30m", "90s", or combinations like "1h30m". */
final class TimeUtil {

    private static final Pattern PART = Pattern.compile("(\\d{1,9})([dhms])");
    private static final Pattern WHOLE = Pattern.compile("(\\d{1,9}[dhms])+");

    private TimeUtil() {
    }

    /** True when the whole text is a duration such as 90s or 1h30m. */
    static boolean valid(String value) {
        return value != null && WHOLE.matcher(value.trim().toLowerCase()).matches();
    }

    /** Milliseconds for a duration text, or the fallback when it is empty or not a duration. */
    static long parseMillis(String value, long fallback) {
        if (!valid(value))
            return fallback;
        Matcher m = PART.matcher(value.trim().toLowerCase());
        long total = 0;
        while (m.find()) {
            long amount = Long.parseLong(m.group(1)); // at most 9 digits, so the sum below cannot overflow
            total += switch (m.group(2)) {
                case "d" -> amount * 86_400_000L;
                case "h" -> amount * 3_600_000L;
                case "m" -> amount * 60_000L;
                default -> amount * 1_000L;
            };
        }
        return total;
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
