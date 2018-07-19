package ca.zesty.fleetreceiver;

import android.arch.persistence.room.ColumnInfo;
import android.arch.persistence.room.Entity;
import android.arch.persistence.room.PrimaryKey;
import android.support.annotation.NonNull;

@Entity(tableName = "targets")
public class TargetEntity {
    @PrimaryKey @NonNull
    @ColumnInfo(name = "target_id") public String targetId;
    @ColumnInfo(name = "mobile_number") public String mobileNumber;
    @ColumnInfo(name = "label") public String label;
    @ColumnInfo(name = "activation_millis") public Long activationMillis;

    public TargetEntity(
        String targetId, String mobileNumber, String label, Long activationMillis) {
        this.targetId = targetId;
        this.mobileNumber = mobileNumber;
        this.label = label;
        this.activationMillis = activationMillis;
    }
}
