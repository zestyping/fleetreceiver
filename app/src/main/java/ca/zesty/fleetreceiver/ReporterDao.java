package ca.zesty.fleetreceiver;

import android.arch.persistence.room.Dao;
import android.arch.persistence.room.Insert;
import android.arch.persistence.room.Query;
import android.arch.persistence.room.Update;

import java.util.List;

@Dao
public interface ReporterDao {
    @Query("select * from reporters")
    List<ReporterEntity> getAll();

    @Query("select * from reporters where activation_millis is not null " +
           "order by activation_millis desc")
    List<ReporterEntity> getAllActive();

    @Query("select * from reporters left join points on latest_point_id = point_id " +
           "where activation_millis is not null " +
           "order by activation_millis desc")
    List<ReporterEntity.WithPoint> getAllActiveWithLatestPoints();

    @Query("select * from reporters, points " +
           "where latest_point_id = point_id and time_millis >= :minTimeMillis " +
           "order by time_millis desc")
    List<ReporterEntity.WithPoint> getAllReportedSince(long minTimeMillis);

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
