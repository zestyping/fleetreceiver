package ca.zesty.fleetreceiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.SmsMessage;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Receives incoming SMS messages from Fleet Reporter instances. */
public class SmsPointReceiver extends BroadcastReceiver {
    static final String TAG = "SmsPointReceiver";
    static final String ACTION_POINTS_ADDED = "FLEET_RECEIVER_POINTS_ADDED";
    static final Pattern PATTERN_GPS_OUTAGE = Pattern.compile("^fleet gpsoutage (.*)");
    static final String ACTION_GPS_OUTAGE = "FLEET_RECEIVER_GPS_OUTAGE";
    static final String EXTRA_REPORTER_ID = "reporter_id";
    static final String EXTRA_TIME_MILLIS = "time_millis";

    @Override public void onReceive(Context context, Intent intent) {
        SmsMessage sms = Utils.getSmsFromIntent(intent);
        if (sms == null) return;

        // Ensure that the notification is showing.
        context.startService(new Intent(context, NotificationService.class));

        String sender = sms.getDisplayOriginatingAddress();
        String body = sms.getMessageBody();
        Log.i(TAG, "Received SMS from " + sender + ": " + body);

        AppDatabase db = AppDatabase.getDatabase(context);
        try {
            MobileNumberEntity mobileNumber = db.getMobileNumberDao().get(sender);
            ReporterEntity reporter = db.getReporterDao().get(mobileNumber != null ? mobileNumber.reporterId : null);
            if (reporter != null) {
                Matcher matcher = PATTERN_GPS_OUTAGE.matcher(body);
                if (matcher.find()) {
                    Log.i(TAG, "GPS outage message received for " + reporter.label);
                    Long timeMillis = Utils.parseTimestamp(matcher.group(1).trim());
                    if (timeMillis != null) {
                        context.sendBroadcast(new Intent(ACTION_GPS_OUTAGE)
                            .putExtra(EXTRA_REPORTER_ID, reporter.reporterId)
                            .putExtra(EXTRA_TIME_MILLIS, timeMillis));
                    }
                    return;
                }

                List<PointEntity> points = new ArrayList<>();
                for (String part : body.trim().split("\\s+")) {
                    PointEntity point = PointEntity.parse(reporter.reporterId, part);
                    if (point != null) points.add(point);
                }
                Log.i(TAG, "Points found in this message: " + points.size());
                if (points.size() > 0) {
                    db.getPointDao().insertAll(points.toArray(new PointEntity[points.size()]));
                    PointEntity latestPoint = db.getPointDao().getLatestPointForReporter(reporter.reporterId);
                    reporter.latestPointId = latestPoint.pointId;
                    db.getReporterDao().put(reporter);
                    for (PointEntity point : points) {
                        MainActivity.postLogMessage(context, reporter.label + ": " + point);
                    }
                    context.sendBroadcast(new Intent(ACTION_POINTS_ADDED));
                }
            }
        } finally {
            db.close();
        }
    }
}
