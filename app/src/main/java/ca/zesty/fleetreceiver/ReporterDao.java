package ca.zesty.fleetreceiver;

import android.arch.persistence.room.Dao;
import android.arch.persistence.room.Insert;
import android.arch.persistence.room.Query;
import android.arch.persistence.room.Transaction;
import android.arch.persistence.room.Update;

import java.util.List;

@Dao
public interface ReporterDao {
    @Query("select * from reporters")
    List<ReporterEntity> getAll();

    @Query("select * from reporters") @Transaction
    List<ReporterEntity.WithLatestPoint> getAllWithLatestPoints();

    @Query("select * from reporters where activation_millis is not null order by activation_millis desc")
    List<ReporterEntity> getAllActive();

    @Query("select * from reporters where reporter_id = :reporterId")
    ReporterEntity get(String reporterId);

    @Query("select * from reporters where mobile_number = :mobileNumber")
    List<ReporterEntity> getByMobileNumber(String mobileNumber);

    @Query("select * from reporters where mobile_number = :mobileNumber and activation_millis is not null")
    List<ReporterEntity> getActiveByMobileNumber(String mobileNumber);

    @Insert
    void insert(ReporterEntity reporter);

    @Update
    void update(ReporterEntity reporter);

    @Update
    void updateAll(List<ReporterEntity> reporters);
}
