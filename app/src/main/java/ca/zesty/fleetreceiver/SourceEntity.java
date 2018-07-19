package ca.zesty.fleetreceiver;

import android.arch.persistence.room.ColumnInfo;
import android.arch.persistence.room.Entity;
import android.arch.persistence.room.PrimaryKey;
import android.support.annotation.NonNull;

@Entity(tableName = "sources")
public class SourceEntity {
    @PrimaryKey @NonNull
    @ColumnInfo(name = "source_id") public String sourceId;
    @ColumnInfo(name = "label") public String label;
    @ColumnInfo(name = "activation_millis") public Long activationMillis;

    public SourceEntity(String sourceId, String label, Long activationMillis) {
        this.sourceId = sourceId;
        this.label = label;
        this.activationMillis = activationMillis;
    }
}
