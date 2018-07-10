package ca.zesty.fleetreceiver;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

/** Maintains a notification indicating that Fleet Receiver is watching for SMS messages. */
public class NotificationService extends BaseService {
    static final String TAG = "NotificationService";
    static final int NOTIFICATION_ID = 1;

    private PointsAddedReceiver mPointsAddedReceiver = new PointsAddedReceiver();
    private ReporterRegisteredReceiver mReporterRegisteredReceiver = new ReporterRegisteredReceiver();

    @Override public void onCreate() {
        super.onCreate();
        Log.i(TAG, "onCreate");
        registerReceiver(mPointsAddedReceiver, new IntentFilter(
            SmsPointReceiver.ACTION_FLEET_RECEIVER_POINTS_ADDED
        ));
        registerReceiver(mReporterRegisteredReceiver, new IntentFilter(
            RegistrationActivity.ACTION_FLEET_RECEIVER_REPORTER_REGISTERED
        ));
    }

    /** Starts running the service. */
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand");
        startForeground(NOTIFICATION_ID, buildNotification());
        return START_STICKY;
    }

    /** Cleans up when the service is about to stop. */
    @Override public void onDestroy() {
        Log.i(TAG, "onDestroy");
        unregisterReceiver(mPointsAddedReceiver);
        unregisterReceiver(mReporterRegisteredReceiver);
        super.onDestroy();
    }

    private void updateNotification() {
        Log.i(TAG, "updateNotification");
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

        AppDatabase db = AppDatabase.getDatabase(this);
        int registeredCount = db.getReporterDao().getAllActive().size();
        long oneHourAgo = System.currentTimeMillis() - 60 * 60 * 1000;
        int reportedCount = db.getReporterDao().getAllReportedSince(oneHourAgo).size();

        String message = "Reporters registered: " + registeredCount +
            ".  Reported in last hour: " + reportedCount + ".";

        return new NotificationCompat.Builder(this)
            .setContentTitle("Fleet Receiver")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .build();
    }

    class PointsAddedReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            updateNotification();
        }
    }

    class ReporterRegisteredReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            updateNotification();
        }
    }
}
