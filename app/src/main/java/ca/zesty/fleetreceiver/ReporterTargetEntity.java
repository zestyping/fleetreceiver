package ca.zesty.fleetreceiver;

import android.arch.persistence.room.ColumnInfo;
import android.arch.persistence.room.Entity;
import android.arch.persistence.room.PrimaryKey;
import android.support.annotation.NonNull;

@Entity(tableName = "reporter_targets")
public class ReporterTargetEntity {
    @PrimaryKey @NonNull
    @ColumnInfo(name = "reporter_target_id") public String reporterTargetId;
    @ColumnInfo(name = "reporter_id") public String reporterId;
    @ColumnInfo(name = "target_id") public String targetId;

    public ReporterTargetEntity(String reporterId, String targetId) {
        this.reporterTargetId = reporterId + "/" + targetId;
        this.reporterId = reporterId;
        this.targetId = targetId;
    }

    public String toString() {
        return Utils.format("<Reporter %s -> Target %s>", reporterId, targetId);
    }
}
