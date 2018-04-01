package ca.zesty.fleetreceiver;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
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
    static final String ACTION_SMS_RECEIVED = "android.provider.Telephony.SMS_RECEIVED";

    private SmsReceiver mSmsReceiver = new SmsReceiver();
    private boolean mStarted = false;
    private PowerManager.WakeLock mWakeLock = null;
    private int mNumReceived = 0;

    @Override public void onCreate() {
        super.onCreate();

        // Receive broadcasts of SMS sent notifications.
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_SMS_RECEIVED);
        registerReceiver(mSmsReceiver, filter);
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
        unregisterReceiver(mSmsReceiver);
        if (mWakeLock != null) mWakeLock.release();
        mStarted = false;
    }

    @Nullable @Override public IBinder onBind(Intent intent) {
        return null;
    }

    /** Creates the notification to show while the service is running. */
    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setAction(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);

        String message = "SMS messages received: " + mNumReceived + ".";

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

    /** Handles received SMS messages. */
    class SmsReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            Object[] pdus = (Object[]) intent.getExtras().get("pdus");
            List<SmsMessage> messages = new ArrayList<>();
            for (Object pdu : pdus) {
                messages.add(SmsMessage.createFromPdu((byte[]) pdu));
            }
            SmsMessage sms = messages.get(0);
            MainActivity.postLogMessage(context, "SMS received from " + sms.getDisplayOriginatingAddress() + "\n    " + sms.getMessageBody());
            mNumReceived += 1;
            getNotificationManager().notify(NOTIFICATION_ID, buildNotification());
        }
    }
}
