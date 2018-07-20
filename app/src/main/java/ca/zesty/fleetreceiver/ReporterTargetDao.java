package ca.zesty.fleetreceiver;

import android.arch.persistence.room.Dao;
import android.arch.persistence.room.Delete;
import android.arch.persistence.room.Insert;
import android.arch.persistence.room.Query;

import java.util.List;

import static android.arch.persistence.room.OnConflictStrategy.REPLACE;

@Dao
public interface ReporterTargetDao {
    @Query("select * from reporter_targets")
    List<ReporterTargetEntity> getAll();

    @Query("select * from reporter_targets where reporter_id = :reporterId")
    List<ReporterTargetEntity> getAllByReporter(String reporterId);

    @Query("select * from reporter_targets where target_id = :targetId")
    List<ReporterTargetEntity> getAllByTarget(String targetId);

    @Insert(onConflict = REPLACE)
    void put(ReporterTargetEntity rt);

    @Delete
    void delete(ReporterTargetEntity rt);

    @Delete
    void deleteAll(List<ReporterTargetEntity> rts);
}
