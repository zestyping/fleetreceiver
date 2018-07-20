package ca.zesty.fleetreceiver;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.telephony.SmsMessage;
import android.text.InputFilter;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TableLayout;
import android.widget.TableRow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Allows Fleet Reporters to register and Fleet Receivers to request forwarding over SMS. */
public class RegistrationActivity extends BaseActivity {
    static final String TAG = "RegistrationActivity";
    static final int MAX_LABEL_LENGTH = 20;
    static final int DISPLAY_UPDATE_MILLIS = 60*1000;

    private SmsRegistrationReceiver mSmsRegistrationReceiver = new SmsRegistrationReceiver();
    private ReporterActivatedReceiver mReporterActivatedReceiver = new ReporterActivatedReceiver();
    private TargetActivatedReceiver mTargetActivatedReceiver = new TargetActivatedReceiver();
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
        registerReceiver(mReporterActivatedReceiver, new IntentFilter(SmsReceiver.ACTION_REPORTER_ACTIVATED));
        registerReceiver(mTargetActivatedReceiver, new IntentFilter(SmsReceiver.ACTION_TARGET_ACTIVATED));

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
        unregisterReceiver(mReporterActivatedReceiver);
        unregisterReceiver(mTargetActivatedReceiver);
        mDb.close();
        super.onDestroy();
    }

    private void updateRegistrationTable() {
        LayoutInflater inflater = getLayoutInflater();

        TableLayout table = findViewById(R.id.reporter_table);
        table.removeAllViews();
        List<ReporterEntity> reporters = mDb.getReporterDao().getAllActive();
        if (reporters.isEmpty()) {
            table.addView(createNoneRegisteredRow(inflater));
        } else for (ReporterEntity reporter : reporters) {
            String label = reporter.label;
            if (reporter.sourceId != null) {
                SourceEntity source = mDb.getSourceDao().getActive(reporter.sourceId);
                if (source != null) {
                    label += " " + ("(via " + source.label + ")").replace(" ", "\u00a0");
                }
            }
            table.addView(createRow(
                inflater, reporter.activationMillis, label,
                Utils.join("\n", mDb.getReporterNumbers(reporter.reporterId)),
                createReporterDetailsListener(reporter.reporterId)
            ));
        }

        table = findViewById(R.id.source_table);
        table.removeAllViews();
        List<SourceEntity> sources = mDb.getSourceDao().getAllActive();
        if (sources.isEmpty()) {
            table.addView(createNoneRegisteredRow(inflater));
        } else for (SourceEntity source : sources) {
            table.addView(createRow(
                inflater, source.activationMillis, source.label,
                Utils.join("\n", mDb.getReceiverNumbers(source.sourceId)),
                createSourceDetailsListener(source.sourceId)
            ));
        }

        table = findViewById(R.id.target_table);
        table.removeAllViews();
        List<TargetEntity> targets = mDb.getTargetDao().getAllActive();
        if (targets.isEmpty()) {
            table.addView(createNoneRegisteredRow(inflater));
        } else for (TargetEntity target : targets) {
            table.addView(createRow(
                inflater, target.activationMillis, target.label,
                Utils.join("\n", mDb.getReceiverNumbers(target.targetId)),
                createTargetDetailsListener(target.targetId)
            ));
        }
    }

    TableRow createNoneRegisteredRow(LayoutInflater inflater) {
        TableRow row = (TableRow) inflater.inflate(R.layout.reporter_row, null);
        u.setText(row, R.id.registration_label, "(none registered yet)");
        row.findViewById(R.id.details).setVisibility(View.GONE);
        return row;
    }

    TableRow createRow(LayoutInflater inflater, long activationMillis,
                       String label, String number, View.OnClickListener listener) {
        TableRow row = (TableRow) inflater.inflate(R.layout.reporter_row, null);
        if (label != null) {
            u.setText(row, R.id.registration_activation_time, Utils.describeTime(activationMillis));
            u.setText(row, R.id.registration_label, label);
        }
        u.setText(row, R.id.registration_number, number);
        row.findViewById(R.id.details).setOnClickListener(listener);
        return row;
    }

    View.OnClickListener createReporterDetailsListener(final String reporterId) {
        return new View.OnClickListener() {
            @Override public void onClick(View v) {
                ReporterEntity reporter = mDb.getReporterDao().getActive(reporterId);
                if (reporter == null) return;
                String message = reporter.sourceId != null ?
                    Utils.format("Forwarded from %s.", mDb.getSourceDao().get(reporter.sourceId).label) :
                    Utils.format("Received directly from %s.", reporter.label);
                List<String> targetLabels = new ArrayList<>();
                for (ReporterTargetEntity rt : mDb.getReporterTargetDao().getAllByReporter(reporterId)) {
                    TargetEntity target = mDb.getTargetDao().getActive(rt.targetId);
                    if (target != null) targetLabels.add(target.label);
                }
                if (!targetLabels.isEmpty()) {
                    message += Utils.format("\n\nForwarding to %s.", Utils.join(", ", targetLabels));
                }
                new AlertDialog.Builder(RegistrationActivity.this)
                    .setTitle("Reporter: " + reporter.label)
                    .setMessage(message)
                    .setNeutralButton("Delete", createReporterDeleter(reporterId))
                    .setNegativeButton("Forward", createReporterForwarder(reporterId))
                    .setPositiveButton("Close", null)
                    .show();
            }
        };
    }

    AlertDialog.OnClickListener createReporterDeleter(final String reporterId) {
        return new AlertDialog.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                final ReporterEntity reporter = mDb.getReporterDao().getActive(reporterId);
                if (reporter == null) return;
                String message = Utils.format(
                    "This will delete %s from the map and stop accepting " +
                    "reports from %s.  Are you sure?", reporter.label,
                    Utils.join(", ", mDb.getReporterNumbers(reporterId)));
                u.showConfirmBox(
                    Utils.format("Delete reporter %s", reporter.label), message, "Delete",
                    new Utils.Callback() {
                        @Override public void run() {
                            reporter.activationMillis = null;
                            mDb.getReporterDao().put(reporter);
                            mDb.getReporterTargetDao().deleteAll(mDb.getReporterTargetDao().getAllByReporter(reporterId));
                            updateRegistrationTable();
                        }
                    }
                );
            }
        };
    }

    String mLastForwardingDestinationNumber = "+236";

    AlertDialog.OnClickListener createReporterForwarder(final String reporterId) {
        return new AlertDialog.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                final ReporterEntity reporter = mDb.getReporterDao().getActive(reporterId);
                if (reporter == null) return;
                u.promptForString(
                    "Forward this reporter's points to another receiver",
                    "Other receiver's mobile number:",
                    mLastForwardingDestinationNumber,
                    new Utils.StringCallback() {
                        public void run(String number) {
                            if (number == null) return;
                            mLastForwardingDestinationNumber = number;
                            MobileNumberEntity mobileNumber = mDb.getMobileNumberDao().get(number);
                            TargetEntity target = null;
                            if (mobileNumber != null && mobileNumber.receiverId != null) {
                                target = mDb.getTargetDao().getActive(mobileNumber.receiverId);
                            }
                            if (target == null) {
                                mDb.getMobileNumberDao().put(MobileNumberEntity.update(
                                    mDb.getMobileNumberDao().get(number),
                                    number, "pending", null, TargetEntity.PENDING_ID));
                                mDb.getReporterTargetDao().put(
                                    new ReporterTargetEntity(reporterId, TargetEntity.PENDING_ID));
                                u.sendSms(0, number, Utils.format(
                                    "fleet source %s %s",
                                    u.getPref(Prefs.RECEIVER_ID),
                                    u.getPref(Prefs.RECEIVER_LABEL)));
                                u.showToast("Sending your request...");
                            } else {
                                SmsReceiver.establishForwarding(
                                    RegistrationActivity.this, mDb, reporterId, target.targetId);
                            }
                        }
                    }
                );
            }
        };
    }

    View.OnClickListener createSourceDetailsListener(final String sourceId) {
        return new View.OnClickListener() {
            @Override public void onClick(View v) {
                SourceEntity source = mDb.getSourceDao().getActive(sourceId);
                if (source == null) return;
                List<String> reporterLabels = new ArrayList<>();
                for (ReporterEntity reporter : mDb.getReporterDao().getAllBySource(sourceId)) {
                    if (reporter.activationMillis != null) {
                        reporterLabels.add(reporter.label);
                    }
                }
                Collections.sort(reporterLabels);
                String message = reporterLabels.isEmpty() ?
                    Utils.format("Currently not accepting anything from %s.", source.label) :
                    Utils.format("Accepting reports forwarded from %s for %s.", source.label, Utils.join(", ", reporterLabels));
                new AlertDialog.Builder(RegistrationActivity.this)
                    .setTitle("Source: " + source.label)
                    .setMessage(message)
                    .setNeutralButton("Delete", createSourceDeleter(sourceId))
                    .setPositiveButton("Close", null)
                    .show();
            }
        };
    }

    AlertDialog.OnClickListener createSourceDeleter(final String sourceId) {
        return new AlertDialog.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                final SourceEntity source = mDb.getSourceDao().getActive(sourceId);
                if (source == null) return;
                String message = Utils.format(
                    "This will delete all the reporters that came from %s " +
                    "and stop accepting further reports from %s.  Are you sure?",
                    source.label, Utils.join(", ", mDb.getReceiverNumbers(sourceId)));
                u.showConfirmBox(
                    Utils.format("Delete source %s", source.label), message, "Delete",
                    new Utils.Callback() {
                        @Override public void run() {
                            source.activationMillis = null;
                            mDb.getSourceDao().put(source);
                            for (ReporterEntity reporter : mDb.getReporterDao().getAllBySource(source.sourceId)) {
                                reporter.activationMillis = null;
                                mDb.getReporterDao().put(reporter);
                            }
                            updateRegistrationTable();
                        }
                    }
                );
            }
        };
    }

    View.OnClickListener createTargetDetailsListener(final String targetId) {
        return new View.OnClickListener() {
            @Override public void onClick(View v) {
                TargetEntity target = mDb.getTargetDao().getActive(targetId);
                if (target == null) return;
                List<String> reporterLabels = new ArrayList<>();
                for (ReporterTargetEntity rt : mDb.getReporterTargetDao().getAllByTarget(targetId)) {
                    ReporterEntity reporter = mDb.getReporterDao().getActive(rt.reporterId);
                    if (reporter != null) reporterLabels.add(reporter.label);
                }
                String message = reporterLabels.isEmpty() ?
                    Utils.format("Currently not forwarding anything to %s.", target.label) :
                    Utils.format("Currently forwarding %s to %s.", Utils.join(", ", reporterLabels), target.label);
                new AlertDialog.Builder(RegistrationActivity.this)
                    .setTitle("Target: " + target.label)
                    .setMessage(message)
                    .setNeutralButton("Delete", createTargetDeleter(targetId))
                    .setPositiveButton("Close", null)
                    .show();
            }
        };
    }
    AlertDialog.OnClickListener createTargetDeleter(final String targetId) {
        return new AlertDialog.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                final TargetEntity target = mDb.getTargetDao().getActive(targetId);
                if (target == null) return;
                String message = Utils.format(
                    "This will stop all forwarding of points and reporters " +
                    "to %s at %s.  Are you sure?", target.label,
                    Utils.join(", ", mDb.getReceiverNumbers(targetId)));
                u.showConfirmBox(
                    Utils.format("Delete target %s", target.label), message, "Delete",
                    new Utils.Callback() {
                        @Override public void run() {
                            target.activationMillis = null;
                            mDb.getTargetDao().put(target);
                            mDb.getReporterTargetDao().deleteAll(mDb.getReporterTargetDao().getAllByTarget(targetId));
                            updateRegistrationTable();
                        }
                    }
                );
            }
        };
    }

    /** Handles incoming SMS messages for device registration. */
    class SmsRegistrationReceiver extends BroadcastReceiver {
        final Pattern PATTERN_REGISTER = Pattern.compile("^fleet register");
        final Pattern PATTERN_SOURCE = Pattern.compile("^fleet source (\\w+) *(.*)");

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

            Matcher matcher = PATTERN_SOURCE.matcher(body);
            if (matcher.matches()) {
                abortBroadcast();
                handleSourceRequest(sender, matcher.group(1), matcher.group(2));
            }
        }

        private void promptForLabelAndAssignId(final String number) {
            String label = "";
            MobileNumberEntity mobileNumber = mDb.getMobileNumberDao().get(number);
            if (mobileNumber != null) {
                ReporterEntity reporter = mDb.getReporterDao().getActive(mobileNumber.reporterId);
                if (reporter != null) label = reporter.label;
            }
            u.promptForString(
                "Register a reporter",
                "Enter a name for " + number + ":",
                label,
                new Utils.StringCallback() {
                    public void run(String label) {
                        if (label == null) return;
                        String reporterId = Utils.generateReporterId();
                        label = Utils.slice(label, 0, MAX_LABEL_LENGTH);
                        mDb.deactivateReporterByNumber(number);
                        mDb.getReporterDao().put(new ReporterEntity(reporterId, null, label, null));
                        mDb.getMobileNumberDao().put(MobileNumberEntity.update(
                            mDb.getMobileNumberDao().get(number),
                            number, label, reporterId, null
                        ));
                        u.sendSms(0, number, "fleet assign " + reporterId + " " + label);
                        u.showToast(Utils.format("Naming this reporter %s...", label));
                    }
                },
                new InputFilter.LengthFilter(MAX_LABEL_LENGTH),
                new Utils.PrintableAsciiFilter()
            );
        }

        private void handleSourceRequest(final String mobileNumber, final String sourceId, String label) {
            u.promptForString(
                "Forwarding request",
                Utils.format(
                    "%s (%s) is requesting to send some points to your map. " +
                    "If you agree, enter a name for this sender and tap OK.",
                    mobileNumber, label),
                label,
                new Utils.StringCallback() {
                    public void run(String label) {
                        if (label == null) return;
                        label = Utils.slice(label, 0, MAX_LABEL_LENGTH);
                        activateSource(sourceId, mobileNumber, label);
                    }
                },
                new InputFilter.LengthFilter(MAX_LABEL_LENGTH),
                new PrintableAsciiFilter()
            );
        }

        private void activateSource(String sourceId, String number, String label) {
            mDb.getMobileNumberDao().put(
                MobileNumberEntity.update(
                    mDb.getMobileNumberDao().get(number),
                    number, label, null, sourceId));
            mDb.getSourceDao().put(new SourceEntity(sourceId, label, Utils.getTime()));
            u.sendSms(0, number, Utils.format(
                "fleet target %s %s",
                u.getPref(Prefs.RECEIVER_ID),
                u.getPref(Prefs.RECEIVER_LABEL)
            ));
            updateRegistrationTable();
            u.showToast(Utils.format("Accepting forwarding from %s...", label));
        }
    }

    class ReporterActivatedReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            updateRegistrationTable();
        }
    }

    class TargetActivatedReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            updateRegistrationTable();
        }
    }
}
