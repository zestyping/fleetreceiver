package ca.zesty.fleetreceiver;

import android.arch.persistence.room.Dao;
import android.arch.persistence.room.Insert;
import android.arch.persistence.room.Query;

import java.util.List;

import static android.arch.persistence.room.OnConflictStrategy.REPLACE;

@Dao
public interface ReporterDao {
    @Query("select * from reporters where reporter_id = :reporterId")
    ReporterEntity get(String reporterId);

    @Query("select * from reporters order by activation_millis desc")
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

    @Insert(onConflict = REPLACE)
    void put(ReporterEntity reporter);
}
