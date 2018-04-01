package ca.zesty.fleetreceiver;

import android.Manifest;
import android.app.AlertDialog;
import android.arch.persistence.room.Room;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.widget.EditText;
import android.widget.TextView;

import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {
    static final String TAG = "MainActivity";
    static final String ACTION_FLEET_RECEIVER_LOG_MESSAGE = "FLEET_RECEIVER_LOG_MESSAGE";
    static final String EXTRA_LOG_MESSAGE = "LOG_MESSAGE";

    private SmsRegistrationReceiver mSmsRegistrationReceiver = new SmsRegistrationReceiver();
    private LogMessageReceiver mLogMessageReceiver = new LogMessageReceiver();
    private AppDatabase mDb;

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

        mDb = Room.databaseBuilder(
            getApplicationContext(), AppDatabase.class, "database").allowMainThreadQueries().build();

        final Intent serviceIntent = new Intent(getApplicationContext(), ReceiverService.class);
        startService(serviceIntent);

        registerReceiver(mSmsRegistrationReceiver, Utils.getMaxPrioritySmsFilter());
        registerReceiver(mLogMessageReceiver, new IntentFilter(ACTION_FLEET_RECEIVER_LOG_MESSAGE));
    }

    @Override public void onRequestPermissionsResult(
        int requestCode, String permissions[], int[] grantResults) {
        boolean allGranted = true;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
            }
        }
        if (allGranted) {
            ((TextView) findViewById(R.id.receiver_number)).setText("+" + getSmsNumber());
        } else {
            ActivityCompat.requestPermissions(this, permissions, 0);
        }
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

    private String generateId() {
        Random random = new Random(System.currentTimeMillis());
        String alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        String id = "";
        for (int i = 0; i < 12; i++) {
            int index = random.nextInt(alphabet.length());
            id = id + alphabet.substring(index, index + 1);
        }
        return id;
    }

    /** Handles incoming SMS messages for device registration. */
    class SmsRegistrationReceiver extends BroadcastReceiver {
        final Pattern PATTERN_REGISTER = Pattern.compile("^fleet register");
        final Pattern PATTERN_ACTIVATE = Pattern.compile("^fleet activate ([0-9a-zA-Z]+)");

        @Override public void onReceive(Context context, Intent intent) {
            SmsMessage sms = Utils.getSmsFromIntent(intent);
            if (sms == null) return;

            String sender = sms.getDisplayOriginatingAddress();
            String body = sms.getMessageBody();

            if (PATTERN_REGISTER.matcher(body).matches()) {
                abortBroadcast();
                promptForLabelAndAssignId(sender);
                return;
            }

            Matcher matcher = PATTERN_ACTIVATE.matcher(body);
            if (matcher.matches()) {
                abortBroadcast();
                activateReporter(matcher.group(1));
                return;
            }
        }

        private void promptForLabelAndAssignId(final String mobileNumber) {
            final EditText labelView = new EditText(MainActivity.this);
            new AlertDialog.Builder(MainActivity.this)
                .setTitle("Registering " + mobileNumber)
                .setMessage("Enter a label for " + mobileNumber + " below:")
                .setView(labelView)
                .setPositiveButton("Register", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        String id = generateId();
                        String label = labelView.getText().toString();
                        mDb.getReporterDao().insertAll(
                            new ReporterEntity(id, mobileNumber, label, null));
                        getSmsManager().sendTextMessage(
                            mobileNumber, null, "fleet assign " + id + " " + label, null, null);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
        }

        private void activateReporter(String id) {
            ReporterEntity reporter = mDb.getReporterDao().get(id);
            if (reporter != null) {
                reporter.activationTimeMillis = System.currentTimeMillis();
                mDb.getReporterDao().update(reporter);
                postLogMessage(MainActivity.this, "activated " + reporter.label + " (" + reporter.mobileNumber + ")");
                sendBroadcast(new Intent(ReceiverService.ACTION_FLEET_RECEIVER_UPDATE_NOTIFICATION));
            }
        }
    }
}
