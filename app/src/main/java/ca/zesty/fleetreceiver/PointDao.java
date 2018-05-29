package ca.zesty.fleetreceiver;

import android.arch.persistence.room.Dao;
import android.arch.persistence.room.Insert;
import android.arch.persistence.room.Query;

import java.util.List;

@Dao
public interface PointDao {
    @Query("select * from points where parent_reporter_id = :reporterId")
    List<PointEntity> getAllForReporter(String reporterId);

    @Query("select * from points where parent_reporter_id = :reporterId and time_millis >= :minTimeMillis")
    List<PointEntity> getAllForReporterSince(String reporterId, long minTimeMillis);

    @Query("select * from points where parent_reporter_id = :reporterId order by time_millis desc limit 1")
    PointEntity getLatestPointForReporter(String reporterId);

    @Insert
    void insertAll(PointEntity... points);
}
