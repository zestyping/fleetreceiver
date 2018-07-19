package ca.zesty.fleetreceiver;

import android.arch.persistence.room.ColumnInfo;
import android.arch.persistence.room.Entity;
import android.arch.persistence.room.PrimaryKey;
import android.support.annotation.NonNull;

@Entity(tableName = "sources")
public class SourceEntity {
    @PrimaryKey @NonNull
    @ColumnInfo(name = "source_id") public String sourceId;
    @ColumnInfo(name = "mobile_number") public String mobileNumber;
    @ColumnInfo(name = "label") public String label;
    @ColumnInfo(name = "activation_millis") public Long activationMillis;

    public static final String PENDING_ID = "__pending__";

    public SourceEntity(
        String sourceId, String mobileNumber, String label, Long activationMillis) {
        this.sourceId = sourceId;
        this.mobileNumber = mobileNumber;
        this.label = label;
        this.activationMillis = activationMillis;
    }
}
