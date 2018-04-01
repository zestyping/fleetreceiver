package ca.zesty.fleetreceiver;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.telephony.SmsManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.widget.TextView;

public class MainActivity extends AppCompatActivity {
    static final String TAG = "MainActivity";
    static final String ACTION_FLEET_RECEIVER_LOG_MESSAGE = "FLEET_RECEIVER_LOG_MESSAGE";
    static final String EXTRA_LOG_MESSAGE = "LOG_MESSAGE";

    private LogMessageReceiver mLogMessageReceiver = new LogMessageReceiver();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setTitle("Fleet Receiver " + BuildConfig.VERSION_NAME);

        ActivityCompat.requestPermissions(this, new String[] {
            Manifest.permission.INTERNET,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.RECEIVE_BOOT_COMPLETED,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.WAKE_LOCK
        }, 0);

        final Intent serviceIntent = new Intent(getApplicationContext(), ReceiverService.class);
        startService(serviceIntent);

        ((TextView) findViewById(R.id.receiver_number)).setText("+" + getSmsNumber());

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_FLEET_RECEIVER_LOG_MESSAGE);
        registerReceiver(mLogMessageReceiver, filter);
    }

    /** Gets the mobile number at which this device receives text messages. */
    private String getSmsNumber() {
        if (android.os.Build.VERSION.SDK_INT >= 22) {
            SubscriptionManager subscriptionManager = SubscriptionManager.from(getApplicationContext());
            int subscriptionId = SmsManager.getDefaultSmsSubscriptionId();
            if (subscriptionId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {  // dual-SIM phone
                subscriptionId = subscriptionManager.getActiveSubscriptionInfoForSimSlotIndex(0).getSubscriptionId();
            }
            return subscriptionManager.getActiveSubscriptionInfo(subscriptionId).getNumber();
        }
        return ((TelephonyManager) getSystemService(TELEPHONY_SERVICE)).getLine1Number();
    }

    /** Gets the appropriate SmsManager to use for sending text messages.
        From PataBasi by Kristen Tonga. */
    private SmsManager getSmsManager() {
        if (android.os.Build.VERSION.SDK_INT >= 22) {
            int subscriptionId = SmsManager.getDefaultSmsSubscriptionId();
            if (subscriptionId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {  // dual-SIM phone
                SubscriptionManager subscriptionManager = SubscriptionManager.from(getApplicationContext());
                subscriptionId = subscriptionManager.getActiveSubscriptionInfoForSimSlotIndex(0).getSubscriptionId();
                Log.d(TAG, "Dual SIM phone; selected subscriptionId: " + subscriptionId);
            }
            return SmsManager.getSmsManagerForSubscriptionId(subscriptionId);
        }
        return SmsManager.getDefault();
    }

    class LogMessageReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent.hasExtra(EXTRA_LOG_MESSAGE)) {
                String message = intent.getStringExtra(EXTRA_LOG_MESSAGE);
                ((TextView) findViewById(R.id.message_log)).append(message + "\n");
            }
        }
    }

    public static void postLogMessage(Context context, String message) {
        Intent intent = new Intent(ACTION_FLEET_RECEIVER_LOG_MESSAGE);
        intent.putExtra(EXTRA_LOG_MESSAGE,
            Utils.formatUtcTimeSeconds(System.currentTimeMillis()) + " - " + message);
        context.sendBroadcast(intent);
    }
}
