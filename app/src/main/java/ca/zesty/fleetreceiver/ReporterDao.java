package ca.zesty.fleetreceiver;

import android.arch.persistence.room.Dao;
import android.arch.persistence.room.Delete;
import android.arch.persistence.room.Insert;
import android.arch.persistence.room.Query;
import android.arch.persistence.room.Update;

import java.util.List;

@Dao
public interface ReporterDao {
    @Query("select * from reporters")
    List<ReporterEntity> getAll();

    @Query("select * from reporters where activation_time_millis is not null")
    List<ReporterEntity> getAllActive();

    @Query("select * from reporters where reporter_id = :reporterId")
    ReporterEntity get(String reporterId);

    @Query("select * from reporters where mobile_number = :mobileNumber")
    ReporterEntity getByMobileNumber(String mobileNumber);

    @Insert
    void insertAll(ReporterEntity... reporters);

    @Update
    void update(ReporterEntity point);

    @Delete
    void delete(ReporterEntity reporter);
}
