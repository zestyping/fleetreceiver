package ca.zesty.fleetreceiver;

import android.arch.persistence.room.ColumnInfo;
import android.arch.persistence.room.Entity;
import android.arch.persistence.room.PrimaryKey;
import android.support.annotation.NonNull;

@Entity(tableName = "mobile_numbers")
public class MobileNumberEntity {
    @PrimaryKey @NonNull
    @ColumnInfo(name = "number") public String number;
    @ColumnInfo(name = "label") public String label;
    @ColumnInfo(name = "reporter_id") public String reporterId;
    @ColumnInfo(name = "receiver_id") public String receiverId;

    public MobileNumberEntity(
        String number, String label, String reporterId, String receiverId) {
        this.number = number;
        this.label = label;
        this.reporterId = reporterId;
        this.receiverId = receiverId;
    }

    public static MobileNumberEntity update(
        MobileNumberEntity mobileNumber,
        String number, String label, String reporterId, String receiverId
    ) {
        if (mobileNumber == null) {
            mobileNumber = new MobileNumberEntity(number, null, null, null);
        }
        if (label != null) mobileNumber.label = label;
        if (reporterId != null) mobileNumber.reporterId = reporterId;
        if (receiverId != null) mobileNumber.receiverId = receiverId;
        return mobileNumber;
    }
}
