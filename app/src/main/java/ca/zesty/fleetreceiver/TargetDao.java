package ca.zesty.fleetreceiver;

import android.arch.persistence.room.Dao;
import android.arch.persistence.room.Delete;
import android.arch.persistence.room.Insert;
import android.arch.persistence.room.Query;

import java.util.List;

import static android.arch.persistence.room.OnConflictStrategy.REPLACE;

@Dao
public interface TargetDao {
    @Query("select * from targets where target_id = :targetId")
    TargetEntity get(String targetId);

    @Query("select * from targets where target_id = :targetId and activation_millis is not null")
    TargetEntity getActive(String targetId);

    @Query("select * from targets")
    List<TargetEntity> getAll();

    @Query("select * from targets where activation_millis is not null " +
        "order by activation_millis desc")
    List<TargetEntity> getAllActive();

    @Insert(onConflict = REPLACE)
    void put(TargetEntity target);

    @Delete
    void delete(TargetEntity target);
}
