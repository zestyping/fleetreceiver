package ca.zesty.fleetreceiver;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class Utils {
    static final SimpleDateFormat RFC3339_UTC_SECONDS;
    static final SimpleDateFormat RFC3339_UTC_MILLIS;

    static {
        RFC3339_UTC_SECONDS = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        RFC3339_UTC_SECONDS.setTimeZone(TimeZone.getTimeZone("UTC"));
        RFC3339_UTC_MILLIS = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        RFC3339_UTC_MILLIS.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    /** Formats a time as an RFC3339 timestamp in UTC of exactly 20 characters. */
    public static String formatUtcTimeSeconds(long timeMillis) {
        return RFC3339_UTC_SECONDS.format(new Date(timeMillis));
    }

    /** Formats a time as an RFC3339 timestamp in UTC including milliseconds. */
    public static String formatUtcTimeMillis(long timeMillis) {
        return RFC3339_UTC_MILLIS.format(new Date(timeMillis));
    }
}
