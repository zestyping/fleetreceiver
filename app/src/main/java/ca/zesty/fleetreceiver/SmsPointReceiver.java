package ca.zesty.fleetreceiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.SmsMessage;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/** Receives incoming SMS messages from Fleet Reporter instances. */
public class SmsPointReceiver extends BroadcastReceiver {
    static final String TAG = "SmsPointReceiver";
    static final String ACTION_FLEET_RECEIVER_POINTS_ADDED = "FLEET_RECEIVER_POINTS_ADDED";

    @Override public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "onReceive");
        SmsMessage sms = Utils.getSmsFromIntent(intent);
        if (sms == null) return;

        // Ensure that the notification is showing.
        context.startService(new Intent(context, NotificationService.class));

        String sender = sms.getDisplayOriginatingAddress();
        String body = sms.getMessageBody();
        Log.i(TAG, "SMS from " + sender + ": " + body);

        AppDatabase db = AppDatabase.getDatabase(context);
        try {
            MobileNumberEntity mobileNumber = db.getMobileNumberDao().get(sender);
            ReporterEntity reporter = db.getReporterDao().get(mobileNumber != null ? mobileNumber.reporterId : null);
            if (reporter != null) {
                List<PointEntity> points = new ArrayList<>();
                for (String part : body.trim().split(" +")) {
                    PointEntity point = PointEntity.parse(reporter.reporterId, part);
                    if (point != null) points.add(point);
                }
                Log.i(TAG, "points received: " + points.size());
                if (points.size() > 0) {
                    db.getPointDao().insertAll(points.toArray(new PointEntity[points.size()]));
                    PointEntity latestPoint = db.getPointDao().getLatestPointForReporter(reporter.reporterId);
                    reporter.latestPointId = latestPoint.pointId;
                    db.getReporterDao().put(reporter);
                    for (PointEntity point : points) {
                        MainActivity.postLogMessage(context, reporter.label + ": " + point);
                    }
                    context.sendBroadcast(new Intent(ACTION_FLEET_RECEIVER_POINTS_ADDED));
                }
            }
        } finally {
            db.close();
        }

        if (body.trim().startsWith("crash test dummy")) {
            throw new RuntimeException("crash test dummy");
        }
    }
}
