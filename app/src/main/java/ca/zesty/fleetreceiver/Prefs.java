package ca.zesty.fleetreceiver;

/** Shared preference keys. */
public class Prefs {
    static final String EXPECTED_REPORTING_INTERVAL = "pref_expected_reporting_interval";
    static final String HISTORICAL_TRACK_HOURS = "pref_historical_track_hours";
    static final String PLAY_STORE_REQUESTED = "pref_play_store_requested";
    static final String RECEIVER_ID = "pref_receiver_id";
    static final String RECEIVER_LABEL = "pref_receiver_label";
    static final String SHOW_LOG = "pref_show_log";
    static final String SMS_HISTORY_UPLOAD_TIMESTAMP = "pref_sms_history_upload_timestamp";

    static final String[] KEYS = new String[] {
        EXPECTED_REPORTING_INTERVAL,
        HISTORICAL_TRACK_HOURS,
        PLAY_STORE_REQUESTED,
        RECEIVER_ID,
        RECEIVER_LABEL,
        SHOW_LOG,
        SMS_HISTORY_UPLOAD_TIMESTAMP
    };
}
