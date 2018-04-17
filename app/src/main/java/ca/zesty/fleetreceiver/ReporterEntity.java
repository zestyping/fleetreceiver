package ca.zesty.fleetreceiver;

import android.arch.persistence.room.ColumnInfo;
import android.arch.persistence.room.Embedded;
import android.arch.persistence.room.Entity;
import android.arch.persistence.room.ForeignKey;
import android.arch.persistence.room.Index;
import android.arch.persistence.room.PrimaryKey;
import android.support.annotation.NonNull;

@Entity(
    tableName = "reporters",
    foreignKeys = @ForeignKey(
        entity = PointEntity.class,
        parentColumns = "point_id",
        childColumns = "latest_point_id",
        onDelete = ForeignKey.SET_NULL
    ),
    indices = @Index(value={"latest_point_id"})
)
public class ReporterEntity {
    @PrimaryKey @NonNull
    @ColumnInfo(name = "reporter_id") public String reporterId;
    @ColumnInfo(name = "mobile_number") public String mobileNumber;
    @ColumnInfo(name = "label") public String label;
    @ColumnInfo(name = "activation_millis") public Long activationMillis;
    @ColumnInfo(name = "latest_point_id") public Long latestPointId;

    public ReporterEntity(
        String reporterId, String mobileNumber, String label, Long activationMillis) {
        this.reporterId = reporterId;
        this.mobileNumber = mobileNumber;
        this.label = label;
        this.activationMillis = activationMillis;
        this.latestPointId = null;
    }

    public static class WithPoint {
        @Embedded public ReporterEntity reporter;
        @Embedded public PointEntity point;
    }
}
