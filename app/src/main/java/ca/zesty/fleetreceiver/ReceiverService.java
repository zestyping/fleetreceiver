package ca.zesty.fleetreceiver;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.arch.persistence.room.Room;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.telephony.SmsMessage;

import java.util.ArrayList;
import java.util.List;

/** Watches for incoming SMS messages from Fleet Reporter instances. */
public class ReceiverService extends Service {
    static final String TAG = "LocationService";
    static final int NOTIFICATION_ID = 1;
    static final String ACTION_FLEET_RECEIVER_UPDATE_NOTIFICATION = "FLEET_RECEIVER_UPDATE_NOTIFICATION";

    private SmsPointReceiver mSmsPointReceiver = new SmsPointReceiver();
    private UpdateNotificationReceiver mUpdateNotificationReceiver = new UpdateNotificationReceiver();
    private boolean mStarted = false;
    private PowerManager.WakeLock mWakeLock = null;
    private int mNumReceived = 0;
    private AppDatabase mDb;

    @Override public void onCreate() {
        super.onCreate();

        mDb = Room.databaseBuilder(
            getApplicationContext(), AppDatabase.class, "database").allowMainThreadQueries().build();

        registerReceiver(mSmsPointReceiver, Utils.getMaxPrioritySmsFilter());
        registerReceiver(mUpdateNotificationReceiver, new IntentFilter(ACTION_FLEET_RECEIVER_UPDATE_NOTIFICATION));
    }

    /** Starts running the service. */
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!mStarted) {
            // Grab the CPU.
            mStarted = true;
            mWakeLock = getPowerManager().newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "LocationService");
            mWakeLock.acquire();
            startForeground(NOTIFICATION_ID, buildNotification());
        }
        return START_STICKY;
    }

    /** Cleans up when the service is about to stop. */
    @Override public void onDestroy() {
        unregisterReceiver(mSmsPointReceiver);
        if (mWakeLock != null) mWakeLock.release();
        mStarted = false;
    }

    @Nullable @Override public IBinder onBind(Intent intent) {
        return null;
    }

    private void updateNotification() {
        getNotificationManager().notify(NOTIFICATION_ID, buildNotification());
    }

    /** Creates the notification to show while the service is running. */
    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setAction(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);

        String message = "Reporters activated: " +
            mDb.getReporterDao().getAllActive().size() +
            ".  Points received: " + mNumReceived + ".";

        return new NotificationCompat.Builder(this)
            .setContentTitle("Fleet Receiver")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .build();
    }

    private NotificationManager getNotificationManager() {
        return (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private PowerManager getPowerManager() {
        return (PowerManager) getSystemService(Context.POWER_SERVICE);
    }

    /** Handles incoming SMS messages for reported locations. */
    class SmsPointReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            SmsMessage sms = Utils.getSmsFromIntent(intent);
            if (sms == null) return;

            String sender = sms.getDisplayOriginatingAddress();
            String body = sms.getMessageBody();

            ReporterEntity reporter = mDb.getReporterDao().getByMobileNumber(sender);
            if (reporter != null && reporter.activationTimeMillis != null) {
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
        }

        private void savePoints(ReporterEntity reporter, List<PointEntity> points) {
            mDb.getPointDao().insertAll(points.toArray(new PointEntity[points.size()]));
            for (PointEntity point : points) {
                MainActivity.postLogMessage(ReceiverService.this, reporter.label + ": " + point);
                mNumReceived += 1;
            }
            updateNotification();
        }
    }

    class UpdateNotificationReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            updateNotification();
        }
    }
}
