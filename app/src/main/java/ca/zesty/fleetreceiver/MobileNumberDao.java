package ca.zesty.fleetreceiver;

import android.arch.persistence.room.Dao;
import android.arch.persistence.room.Delete;
import android.arch.persistence.room.Insert;
import android.arch.persistence.room.Query;

import java.util.List;

import static android.arch.persistence.room.OnConflictStrategy.REPLACE;

@Dao
public interface MobileNumberDao {
    @Query("select * from mobile_numbers where number = :number")
    MobileNumberEntity get(String number);

    @Query("select * from mobile_numbers order by number")
    List<MobileNumberEntity> getAll();

    @Query("select * from mobile_numbers where reporter_id = :reporterId order by number")
    List<MobileNumberEntity> getAllByReporterId(String reporterId);

    @Query("select * from mobile_numbers where receiver_id = :receiverId order by number")
    List<MobileNumberEntity> getAllByReceiverId(String receiverId);

    @Insert(onConflict = REPLACE)
    void put(MobileNumberEntity mobileNumber);

    @Delete
    void delete(MobileNumberEntity mobileNumber);

    @Delete
    void deleteAll(List<MobileNumberEntity> mobileNumbers);
}
