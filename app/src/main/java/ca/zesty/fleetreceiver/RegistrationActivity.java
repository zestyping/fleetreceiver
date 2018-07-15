package ca.zesty.fleetreceiver;

import android.arch.persistence.room.Room;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.telephony.SmsMessage;
import android.text.InputFilter;
import android.util.Log;
import android.view.LayoutInflater;
import android.widget.TableLayout;
import android.widget.TableRow;

import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Allows Fleet Reporter instances to register over SMS. */
public class RegistrationActivity extends BaseActivity {
    static final String TAG = "RegistrationActivity";
    static final int MAX_LABEL_LENGTH = 20;
    static final int DISPLAY_UPDATE_MILLIS = 60 * 1000;
    static final String ACTION_FLEET_RECEIVER_REPORTER_REGISTERED = "FLEET_RECEIVER_REPORTER_REGISTERED";

    private SmsRegistrationReceiver mSmsRegistrationReceiver = new SmsRegistrationReceiver();
    private AppDatabase mDb;
    private Handler mHandler;
    private Runnable mRunnable;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_registration);
        setTitle("Registration");
        getSupportActionBar().setHomeButtonEnabled(true);

        mDb = Room.databaseBuilder(
            getApplicationContext(), AppDatabase.class, "database").allowMainThreadQueries().fallbackToDestructiveMigration().build();
        updateRegistrationTable();

        String number = u.getMobileNumber(0);
        if (number == null) {
            u.setText(R.id.registration_status, "Now accepting registrations.");
            u.hide(R.id.receiver_number);
        } else {
            u.setText(R.id.registration_status, "Now accepting registrations:");
            u.setText(R.id.receiver_number, number);
        }
        registerReceiver(mSmsRegistrationReceiver, Utils.getMaxPrioritySmsFilter());

        mHandler = new Handler();
        mRunnable = new Runnable() {
            public void run() {
                updateRegistrationTable();
                mHandler.postDelayed(mRunnable, DISPLAY_UPDATE_MILLIS);
            }
        };
        mHandler.postDelayed(mRunnable, 0);
    }

    @Override protected void onDestroy() {
        mHandler.removeCallbacks(mRunnable);
        unregisterReceiver(mSmsRegistrationReceiver);
        super.onDestroy();
    }

    private void updateRegistrationTable() {
        LayoutInflater inflater = getLayoutInflater();
        TableLayout table = findViewById(R.id.reporter_table);
        table.removeAllViews();

        List<ReporterEntity> reporters = mDb.getReporterDao().getAllActive();
        if (reporters.isEmpty()) {
            TableRow row = (TableRow) inflater.inflate(R.layout.reporter_row, null);
            u.setText(row, R.id.reporter_label, "(none registered yet)");
            table.addView(row);
        }
        for (ReporterEntity reporter : reporters) {
            boolean firstRow = true;
            for (MobileNumberEntity mobileNumbers : mDb.getMobileNumberDao().getAllByReporterId(reporter.reporterId)) {
                TableRow row = (TableRow) inflater.inflate(R.layout.reporter_row, null);
                if (firstRow) {
                    u.setText(row, R.id.activation_time, Utils.describeTime(reporter.activationMillis));
                    u.setText(row, R.id.reporter_label, reporter.label);
                }
                u.setText(row, R.id.reporter_number, mobileNumbers.number);
                table.addView(row);
                firstRow = false;
            }
        }
    }

    private String generateReporterId() {
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
            Log.i(TAG, "Received SMS from " + sender + ": " + body);

            if (PATTERN_REGISTER.matcher(body).matches()) {
                abortBroadcast();
                promptForLabelAndAssignId(sender);
                return;
            }

            Matcher matcher = PATTERN_ACTIVATE.matcher(body);
            if (matcher.matches()) {
                abortBroadcast();
                activateReporter(sender, matcher.group(1));
            }
        }

        private void promptForLabelAndAssignId(final String number) {
            String label = "";
            MobileNumberEntity mobileNumber = mDb.getMobileNumberDao().get(number);
            if (mobileNumber != null) {
                ReporterEntity reporter = mDb.getReporterDao().get(mobileNumber.reporterId);
                if (reporter != null) label = reporter.label;
            }
            u.promptForString(
                "Register a Reporter",
                "Enter the label for " + number + ":",
                label,
                new Utils.StringCallback() {
                    public void run(String label) {
                        if (label == null) return;
                        String reporterId = generateReporterId();
                        label = Utils.slice(label, 0, MAX_LABEL_LENGTH);
                        mDb.getReporterDao().put(new ReporterEntity(reporterId, label, null));
                        mDb.getMobileNumberDao().put(MobileNumberEntity.update(
                            mDb.getMobileNumberDao().get(number), number, label, reporterId, null
                        ));
                        u.sendSms(0, number, "fleet assign " + reporterId + " " + label);
                    }
                },
                new InputFilter.LengthFilter(MAX_LABEL_LENGTH),
                new Utils.PrintableAsciiFilter()
            );
        }

        private void activateReporter(String number, String reporterId) {
            ReporterEntity reporter = mDb.getReporterDao().get(reporterId);
            if (reporter != null) {
                MobileNumberEntity mobileNumber = mDb.getMobileNumberDao().get(number);
                if (mobileNumber != null && mobileNumber.reporterId != null &&
                    !mobileNumber.reporterId.equals(reporterId)) {
                    // Deactivate the other reporter associated with this mobile number.
                    ReporterEntity oldReporter = mDb.getReporterDao().get(mobileNumber.reporterId);
                    oldReporter.activationMillis = null;
                    mDb.getReporterDao().put(oldReporter);
                }
                reporter.activationMillis = System.currentTimeMillis();
                mDb.getReporterDao().put(reporter);
                mDb.getMobileNumberDao().put(MobileNumberEntity.update(
                    mobileNumber, number, reporter.label, reporterId, null
                ));
                updateRegistrationTable();
                sendBroadcast(new Intent(ACTION_FLEET_RECEIVER_REPORTER_REGISTERED));
            }
        }
    }
}
