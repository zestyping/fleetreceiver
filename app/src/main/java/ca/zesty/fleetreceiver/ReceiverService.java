package ca.zesty.fleetreceiver;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.PowerManager;
import android.support.v4.app.NotificationCompat;
import android.telephony.SmsMessage;

import java.util.ArrayList;
import java.util.List;

/** Watches for incoming SMS messages from Fleet Reporter instances. */
public class ReceiverService extends BaseService {
    static final String TAG = "LocationService";
    static final int NOTIFICATION_ID = 1;
    static final String ACTION_FLEET_RECEIVER_POINTS_ADDED = "FLEET_RECEIVER_POINTS_RECEIVED";

    private SmsPointReceiver mSmsPointReceiver = new SmsPointReceiver();
    private ReporterRegisteredReceiver mReporterRegisteredReceiver = new ReporterRegisteredReceiver();
    private boolean mStarted = false;
    private PowerManager.WakeLock mWakeLock = null;
    private int mNumReceived = 0;
    private AppDatabase mDb;

    @Override public void onCreate() {
        super.onCreate();
        mDb = AppDatabase.getDatabase(this);

        registerReceiver(mSmsPointReceiver, Utils.getMaxPrioritySmsFilter());
        registerReceiver(mReporterRegisteredReceiver, new IntentFilter(
            RegistrationActivity.ACTION_FLEET_RECEIVER_REPORTER_REGISTERED
        ));
    }

    /** Starts running the service. */
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!mStarted) {
            // Grab the CPU.
            mStarted = true;
            mWakeLock = u.getPowerManager().newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "LocationService");
            mWakeLock.acquire();
            startForeground(NOTIFICATION_ID, buildNotification());
        }
        return START_STICKY;
    }

    /** Cleans up when the service is about to stop. */
    @Override public void onDestroy() {
        unregisterReceiver(mSmsPointReceiver);
        unregisterReceiver(mReporterRegisteredReceiver);
        if (mWakeLock != null) mWakeLock.release();
        mStarted = false;
    }

    private void updateNotification() {
        u.getNotificationManager().notify(NOTIFICATION_ID, buildNotification());
    }

    /** Creates the notification to show while the service is running. */
    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setAction(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);

        int registeredCount = mDb.getReporterDao().getAllActive().size();
        long oneHourAgo = System.currentTimeMillis() - 60 * 60 * 1000;
        int reportedCount = mDb.getReporterDao().getAllReportedSince(oneHourAgo).size();

        String message = "Reporters registered: " + registeredCount +
            ".  Reported in last hour: " + reportedCount + ".";

        return new NotificationCompat.Builder(this)
            .setContentTitle("Fleet Receiver")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .build();
    }

    /** Handles incoming SMS messages for reported locations. */
    class SmsPointReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            SmsMessage sms = Utils.getSmsFromIntent(intent);
            if (sms == null) return;

            String sender = sms.getDisplayOriginatingAddress();
            String body = sms.getMessageBody();

            List<ReporterEntity> reporters = mDb.getReporterDao().getActiveByMobileNumber(sender);
            if (reporters.size() == 1) {
                ReporterEntity reporter = reporters.get(0);
                List<PointEntity> points = new ArrayList<>();
                for (String part : body.trim().split(" +")) {
                    PointEntity point = PointEntity.parse(reporter.reporterId, part);
                    if (point != null) points.add(point);
                }
                if (points.size() > 0) {
                    abortBroadcast();
                    savePoints(reporter, points);
                }
            }

            if (body.trim().startsWith("crash test dummy")) {
                throw new RuntimeException("crash test dummy");
            }
        }

        private void savePoints(ReporterEntity reporter, List<PointEntity> points) {
            if (points.isEmpty()) return;
            mDb.getPointDao().insertAll(points.toArray(new PointEntity[points.size()]));
            for (PointEntity point : points) {
                MainActivity.postLogMessage(ReceiverService.this, reporter.label + ": " + point);
                mNumReceived += 1;
            }

            PointEntity latestPoint = mDb.getPointDao().getLatestPointForReporter(reporter.reporterId);
            reporter.latestPointId = latestPoint.pointId;
            mDb.getReporterDao().update(reporter);
            updateNotification();
            sendBroadcast(new Intent(ACTION_FLEET_RECEIVER_POINTS_ADDED));
        }
    }

    class ReporterRegisteredReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            updateNotification();
        }
    }
}
