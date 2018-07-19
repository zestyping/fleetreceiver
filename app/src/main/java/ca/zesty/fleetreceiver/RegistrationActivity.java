package ca.zesty.fleetreceiver;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Allows Fleet Reporters to register and Fleet Receivers to request forwarding over SMS. */
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

        mDb = AppDatabase.getDatabase(this);
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
        mDb.close();
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

    /** Handles incoming SMS messages for device registration. */
    class SmsRegistrationReceiver extends BroadcastReceiver {
        final Pattern PATTERN_REGISTER = Pattern.compile("^fleet register");
        final Pattern PATTERN_ACTIVATE = Pattern.compile("^fleet activate (\\w+)");
        final Pattern PATTERN_WATCH = Pattern.compile("^fleet watch (\\w+) *(.*)");

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
                return;
            }

            matcher = PATTERN_WATCH.matcher(body);
            if (matcher.matches()) {
                abortBroadcast();
                handleTargetRequest(sender, matcher.group(1));
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
                        String reporterId = Utils.generateReporterId();
                        label = Utils.slice(label, 0, MAX_LABEL_LENGTH);
                        deactivateMobileNumber(number);
                        mDb.getReporterDao().put(new ReporterEntity(reporterId, null, label, null));
                        mDb.getMobileNumberDao().put(MobileNumberEntity.update(
                            mDb.getMobileNumberDao().get(number),
                            number, label, reporterId, null
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
                deactivateMobileNumber(number);
                reporter.activationMillis = System.currentTimeMillis();
                mDb.getReporterDao().put(reporter);
                mDb.getMobileNumberDao().put(MobileNumberEntity.update(
                    mDb.getMobileNumberDao().get(number),
                    number, reporter.label, reporterId, null
                ));
                updateRegistrationTable();
                sendBroadcast(new Intent(ACTION_FLEET_RECEIVER_REPORTER_REGISTERED));
            }
        }

        /** Deactivates the reporter associated with a given mobile number. */
        private void deactivateMobileNumber(String number) {
            MobileNumberEntity mobileNumber = mDb.getMobileNumberDao().get(number);
            if (mobileNumber != null && mobileNumber.reporterId != null) {
                ReporterEntity oldReporter = mDb.getReporterDao().get(mobileNumber.reporterId);
                oldReporter.activationMillis = null;
                mDb.getReporterDao().put(oldReporter);
            }
        }

        private void handleTargetRequest(final String mobileNumber, final String targetId) {
            u.promptForString(
                "Sharing request from " + mobileNumber,
                mobileNumber + " is requesting to see the points on your map. " +
                    "If you agree, enter a name for this recipient and tap OK.",
                getDefaultTargetLabel(targetId, mobileNumber),
                new Utils.StringCallback() {
                    public void run(String label) {
                        if (label == null) return;
                        label = Utils.slice(label, 0, MAX_LABEL_LENGTH);
                        activateTarget(targetId, mobileNumber, label);
                    }
                },
                new InputFilter.LengthFilter(MAX_LABEL_LENGTH),
                new PrintableAsciiFilter()
            );
        }

        private String getDefaultTargetLabel(String targetId, String mobileNumber) {
            TargetEntity target = mDb.getTargetDao().get(targetId);
            if (target != null) return target.label;
            for (TargetEntity w : mDb.getTargetDao().getActiveByMobileNumber(mobileNumber)) {
                return w.label;
            }
            for (TargetEntity w : mDb.getTargetDao().getByMobileNumber(mobileNumber)) {
                return w.label;
            }
            return "";
        }

        private void activateTarget(String targetId, String mobileNumber, String label) {
            List<TargetEntity> targets = mDb.getTargetDao().getByMobileNumber(mobileNumber);
            long now = System.currentTimeMillis();
            TargetEntity targetToActivate = null;
            for (TargetEntity target : targets) {
                target.activationMillis = null;
                if (target.targetId.equals(targetId)) {
                    targetToActivate = target;
                }
            }
            if (targetToActivate == null) {
                targetToActivate = mDb.getTargetDao().get(targetId);
                if (targetToActivate != null) targets.add(targetToActivate);
            }
            if (targetToActivate != null) {
                targetToActivate.mobileNumber = mobileNumber;
                targetToActivate.label = label;
                targetToActivate.activationMillis = now;
            } else {
                targetToActivate = new TargetEntity(targetId, mobileNumber, label, now);
                mDb.getTargetDao().insert(targetToActivate);
            }
            mDb.getTargetDao().updateAll(targets);
            u.sendSms(0, mobileNumber, Utils.format(
                "fleet forward %s %s",
                u.getPref(Prefs.RECEIVER_ID),
                u.getPref(Prefs.RECEIVER_LABEL)
            ));
        }
    }

}
