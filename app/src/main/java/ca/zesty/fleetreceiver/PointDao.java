package ca.zesty.fleetreceiver;

import android.arch.persistence.room.Dao;
import android.arch.persistence.room.Delete;
import android.arch.persistence.room.Insert;
import android.arch.persistence.room.Query;

import java.util.List;

@Dao
public interface PointDao {
    @Query("select * from points where reporter_id = :reporterId")
    List<PointEntity> getAllForReporter(String reporterId);

    @Insert
    void insertAll(PointEntity... points);

    @Delete
    void delete(PointEntity point);
}
