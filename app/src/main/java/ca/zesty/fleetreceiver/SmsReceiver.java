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

/** Receives incoming SMS messages from Fleet Reporter and Fleet Receiver instances. */
public class SmsReceiver extends BroadcastReceiver {
    static final String TAG = "SmsReceiver";

    static final String ACTION_POINTS_ADDED = "FLEET_RECEIVER_POINTS_ADDED";

    static final Pattern PATTERN_GPS_OUTAGE = Pattern.compile("^fleet gpsoutage (.*)");
    static final String ACTION_GPS_OUTAGE = "FLEET_RECEIVER_GPS_OUTAGE";
    static final String EXTRA_REPORTER_ID = "reporter_id";
    static final String EXTRA_TIME_MILLIS = "time_millis";

    static final Pattern PATTERN_TARGET = Pattern.compile("^fleet target (\\w+) *(.*)");
    static final String ACTION_TARGET_ACTIVATED = "FLEET_RECEIVER_TARGET_ACTIVATED";
    static final String EXTRA_TARGET_ID = "target_id";

    static final Pattern PATTERN_ACTIVATE = Pattern.compile("^fleet activate (\\w+)");
    static final Pattern PATTERN_REPORTER = Pattern.compile("^fleet reporter (\\w+) (\\S+) *(.*)");
    static final String ACTION_REPORTER_ACTIVATED = "FLEET_RECEIVER_REPORTER_ACTIVATED";

    static final String ACTION_FORWARDING_ESTABLISHED = "FLEET_RECEIVER_FORWARDING_ESTABLISHED";

    static final Pattern PATTERN_POINT = Pattern.compile("^fleet point (\\w+) *(.*)");

    @Override public void onReceive(Context context, Intent intent) {
        Utils u = new Utils(context);
        SmsMessage sms = Utils.getSmsFromIntent(intent);
        if (sms == null) return;

        // Ensure that the notification is showing.
        context.startService(new Intent(context, NotificationService.class));

        String sender = sms.getDisplayOriginatingAddress();
        String body = sms.getMessageBody();
        Log.i(TAG, "Received SMS from " + sender + ": " + body);

        AppDatabase db = AppDatabase.getDatabase(context);
        try {
            Matcher matcher = PATTERN_ACTIVATE.matcher(body);
            if (matcher.matches()) {
                abortBroadcast();
                activateReporter(context, db, sender, matcher.group(1));
                return;
            }

            MobileNumberEntity mobileNumber = db.getMobileNumberDao().get(sender);
            ReporterEntity reporter = db.getReporterDao().getActive(mobileNumber != null ? mobileNumber.reporterId : null);
            if (reporter != null) {
                matcher = PATTERN_GPS_OUTAGE.matcher(body);
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
                    PointEntity point = PointEntity.parse(reporter.reporterId, null, part);
                    if (point != null) points.add(point);
                }
                Log.i(TAG, "Points found in this message: " + points.size());
                if (points.size() > 0) {
                    db.getPointDao().insertAll(points.toArray(new PointEntity[points.size()]));
                    updateLatestPoint(db, reporter);
                    context.sendBroadcast(new Intent(ACTION_POINTS_ADDED));
                    for (PointEntity point : points) {
                        MainActivity.postLogMessage(context, reporter.label + ": " + point);
                        forwardPointToTargets(context, db, point);
                    }
                }
            }

            String receiverId = mobileNumber != null ? mobileNumber.receiverId : null;
            if (receiverId != null) {
                matcher = PATTERN_TARGET.matcher(body);
                if (matcher.matches()) {
                    activateTarget(context, db, mobileNumber, matcher.group(1), matcher.group(2));
                }

                matcher = PATTERN_REPORTER.matcher(body);
                if (matcher.matches()) {
                    receiveReporter(context, db, mobileNumber, matcher.group(1), matcher.group(2), matcher.group(3));
                }

                matcher = PATTERN_POINT.matcher(body);
                if (matcher.matches()) {
                    receivePoint(context, db, mobileNumber, matcher.group(1), matcher.group(2));
                }
            }
        } finally {
            db.close();
        }
    }

    private void updateLatestPoint(AppDatabase db, ReporterEntity reporter) {
        PointEntity latestPoint = db.getPointDao().getLatestPointForReporter(reporter.reporterId);
        reporter.latestPointId = latestPoint.pointId;
        db.getReporterDao().put(reporter);
    }

    private void forwardPointToTargets(Context context, AppDatabase db, PointEntity point) {
        Utils u = new Utils(context);
        String message = Utils.format("fleet point %s %s", point.reporterId, point.format());
        for (ReporterTargetEntity rt : db.getReporterTargetDao().getAllByReporter(point.reporterId)) {
            TargetEntity target = db.getTargetDao().getActive(rt.targetId);
            if (target != null) {
                String number = db.getReceiverNumber(target.targetId);
                if (number != null && !target.targetId.equals(point.sourceId)) {
                    Log.i(TAG, "Forwarding to target: " + target);
                    u.sendSms(0, number, message);
                }
            }
        }
    }

    private void activateTarget(Context context, AppDatabase db, MobileNumberEntity mobileNumber, String targetId, String label) {
        if (!TargetEntity.PENDING_ID.equals(mobileNumber.receiverId)) return;
        db.getTargetDao().put(new TargetEntity(targetId, label, Utils.getTime()));
        mobileNumber.receiverId = targetId;
        db.getMobileNumberDao().put(mobileNumber);
        context.sendBroadcast(new Intent(ACTION_TARGET_ACTIVATED)
            .putExtra(EXTRA_TARGET_ID, targetId));
        for (ReporterTargetEntity rt : db.getReporterTargetDao().getAllByTarget(TargetEntity.PENDING_ID)) {
            establishForwarding(context, db, rt.reporterId, targetId);
        }
        db.getReporterTargetDao().deleteAll(db.getReporterTargetDao().getAllByTarget(TargetEntity.PENDING_ID));
    }

    public static void establishForwarding(Context context, AppDatabase db, String reporterId, String targetId) {
        Utils u = new Utils(context);
        ReporterEntity reporter = db.getReporterDao().getActive(reporterId);
        TargetEntity target = db.getTargetDao().getActive(targetId);
        String targetNumber = db.getReceiverNumber(targetId);
        if (reporter == null || target == null || targetNumber == null) return;
        db.getReporterTargetDao().put(new ReporterTargetEntity(reporterId, targetId));

        String numbers = Utils.join(",", db.getReporterNumbers(reporterId));
        u.sendSms(0, targetNumber, Utils.format(
            "fleet reporter %s %s %s", reporterId, numbers, reporter.label));
        PointEntity point = db.getPointDao().getLatestPointForReporter(reporterId);
        if (point != null) {
            u.sendSms(0, targetNumber, Utils.format(
                "fleet point %s %s", reporterId, point.format()));
        }
        context.sendBroadcast(new Intent(ACTION_FORWARDING_ESTABLISHED)
            .putExtra(EXTRA_REPORTER_ID, reporterId)
            .putExtra(EXTRA_TARGET_ID, targetId));
        u.showToast(Utils.format("Now forwarding %s to %s.", reporter.label, target.label));
    }

    private void activateReporter(Context context, AppDatabase db, String number, String reporterId) {
        Utils u = new Utils(context);
        ReporterEntity reporter = db.getReporterDao().get(reporterId);
        if (reporter != null) {
            db.deactivateReporterByNumber(number);
            reporter.activationMillis = System.currentTimeMillis();
            db.getReporterDao().put(reporter);
            db.getMobileNumberDao().put(MobileNumberEntity.update(
                db.getMobileNumberDao().get(number),
                number, reporter.label, reporterId, null
            ));
            context.sendBroadcast(new Intent(ACTION_REPORTER_ACTIVATED)
                .putExtra(EXTRA_REPORTER_ID, reporterId));
            String numbers = Utils.join(",", db.getReporterNumbers(reporterId));
            u.showToast(Utils.format("%s is now a registered reporter.", reporter.label));
        }
    }

    private void receiveReporter(Context context, AppDatabase db, MobileNumberEntity mobileNumber, String reporterId, String reporterNumbers, String label) {
        Utils u = new Utils(context);
        String sourceId = mobileNumber.receiverId;
        SourceEntity source = db.getSourceDao().getActive(sourceId);
        if (source == null) return;
        for (MobileNumberEntity number : db.getMobileNumberDao().getAllByReporterId(reporterId)) {
            number.reporterId = null;
            db.getMobileNumberDao().put(number);
        }
        for (String reporterNumber : reporterNumbers.split(",")) {
            db.getMobileNumberDao().put(MobileNumberEntity.update(
                db.getMobileNumberDao().get(reporterNumber),
                reporterNumber, label, reporterId, null
            ));
        }
        db.getReporterDao().put(new ReporterEntity(
            reporterId, sourceId, label, Utils.getTime()));
        context.sendBroadcast(new Intent(ACTION_REPORTER_ACTIVATED)
            .putExtra(EXTRA_REPORTER_ID, reporterId));
        u.showToast(Utils.format("%s is now forwarding %s to you.", source.label, label));
    }

    private void receivePoint(Context context, AppDatabase db, MobileNumberEntity mobileNumber, String reporterId, String formattedPoint) {
        String sourceId = mobileNumber.receiverId;
        SourceEntity source = db.getSourceDao().getActive(sourceId);
        if (source == null) return;

        PointEntity point = PointEntity.parse(reporterId, sourceId, formattedPoint);
        if (point != null) {
            Log.i(TAG, "Received forwarded point from " + source.label + ": " + point);

            // Don't forward a duplicate point.
            PointEntity existing = db.getPointDao().getByReporterAndTime(reporterId, point.timeMillis);
            if (existing != null && existing.format().equals(point.format())) return;

            db.getPointDao().insertAll(point);
            ReporterEntity reporter = db.getReporterDao().getActive(reporterId);
            if (reporter != null) {
                updateLatestPoint(db, reporter);
                MainActivity.postLogMessage(context, reporter.label + ": " + point);
            }
            context.sendBroadcast(new Intent(ACTION_POINTS_ADDED));
            forwardPointToTargets(context, db, point);
        }
    }
}
