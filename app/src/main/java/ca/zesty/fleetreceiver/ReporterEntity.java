package ca.zesty.fleetreceiver;

import android.arch.persistence.room.ColumnInfo;
import android.arch.persistence.room.Entity;
import android.arch.persistence.room.PrimaryKey;
import android.support.annotation.NonNull;

@Entity(tableName = "reporters")
public class ReporterEntity {
    @PrimaryKey @NonNull
    @ColumnInfo(name = "reporter_id") public String reporterId;
    @ColumnInfo(name = "mobile_number") public String mobileNumber;
    @ColumnInfo(name = "label") public String label;
    @ColumnInfo(name = "activation_time_millis") public Long activationTimeMillis;

    public ReporterEntity(
        String reporterId, String mobileNumber, String label, Long activationTimeMillis) {
        this.reporterId = reporterId;
        this.mobileNumber = mobileNumber;
        this.label = label;
        this.activationTimeMillis = activationTimeMillis;
    }
}
