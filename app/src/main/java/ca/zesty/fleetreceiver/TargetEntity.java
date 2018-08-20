package ca.zesty.fleetreceiver;

import android.arch.persistence.room.ColumnInfo;
import android.arch.persistence.room.Entity;
import android.arch.persistence.room.PrimaryKey;
import android.support.annotation.NonNull;

@Entity(tableName = "targets")
public class TargetEntity {
    @PrimaryKey @NonNull
    @ColumnInfo(name = "target_id") public String targetId;
    @ColumnInfo(name = "label") public String label;
    @ColumnInfo(name = "activation_millis") public Long activationMillis;

    public static final String PENDING_ID = "__pending__";

    public TargetEntity(String targetId, String label, Long activationMillis) {
        this.targetId = targetId;
        this.label = label;
        this.activationMillis = activationMillis;
    }

    public String toString() {
        return Utils.format("<Target %s: %s, %s>",
            targetId, label, activationMillis == null ? "inactive" :
                "activated " + Utils.formatUtcTimeMillis(activationMillis));
    }
}
