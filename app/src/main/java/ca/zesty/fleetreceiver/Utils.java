package ca.zesty.fleetreceiver;

import android.content.Intent;
import android.content.IntentFilter;
import android.telephony.SmsMessage;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Utils {
    static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    static final SimpleDateFormat RFC3339_UTC_SECONDS;
    static final SimpleDateFormat RFC3339_UTC_MILLIS;
    static {
        RFC3339_UTC_SECONDS = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        RFC3339_UTC_SECONDS.setTimeZone(UTC);
        RFC3339_UTC_MILLIS = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        RFC3339_UTC_MILLIS.setTimeZone(UTC);
    }
    static final Pattern PATTERN_TIMESTAMP = Pattern.compile(
        "(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})Z");
    static final String ACTION_SMS_RECEIVED = "android.provider.Telephony.SMS_RECEIVED";

    /** Formats a time as an RFC3339 timestamp in UTC of exactly 20 characters. */
    public static String formatUtcTimeSeconds(long timeMillis) {
        return RFC3339_UTC_SECONDS.format(new Date(timeMillis));
    }

    /** Formats a time as an RFC3339 timestamp in UTC including milliseconds. */
    public static String formatUtcTimeMillis(long timeMillis) {
        return RFC3339_UTC_MILLIS.format(new Date(timeMillis));
    }

    /** Parses an RFC3339 timestamp in UTC to give a time in milliseconds, or null. */
    public static Long parseTimestamp(String timestamp) {
        Matcher matcher = PATTERN_TIMESTAMP.matcher(timestamp);
        if (!matcher.matches()) return null;
        Calendar calendar = Calendar.getInstance(UTC);
        calendar.set(
            Integer.parseInt(matcher.group(1)),
            Integer.parseInt(matcher.group(2)) - 1, // Java is insane (0 = Jan, 11 = Dec)
            Integer.parseInt(matcher.group(3)),
            Integer.parseInt(matcher.group(4)),
            Integer.parseInt(matcher.group(5)),
            Integer.parseInt(matcher.group(6))
        );
        return calendar.getTimeInMillis();
    }

    public static IntentFilter getMaxPrioritySmsFilter() {
        IntentFilter filter = new IntentFilter(ACTION_SMS_RECEIVED);
        filter.setPriority(Integer.MAX_VALUE);
        return filter;
    }

    public static SmsMessage getSmsFromIntent(Intent intent) {
        Object[] pdus = (Object[]) intent.getExtras().get("pdus");
        for (Object pdu : pdus) {
            return SmsMessage.createFromPdu((byte[]) pdu);
        }
        return null;
    }
}
