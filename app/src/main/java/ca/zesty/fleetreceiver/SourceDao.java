package ca.zesty.fleetreceiver;

import android.arch.persistence.room.Dao;
import android.arch.persistence.room.Insert;
import android.arch.persistence.room.Query;
import android.arch.persistence.room.Update;

import java.util.List;

import static android.arch.persistence.room.OnConflictStrategy.REPLACE;

@Dao
public interface SourceDao {
    @Query("select * from sources")
    List<SourceEntity> getAll();

    @Query("select * from sources where activation_millis is not null " +
        "order by activation_millis desc")
    List<SourceEntity> getAllActive();

    @Query("select * from sources where source_id = :sourceId")
    SourceEntity get(String sourceId);

    @Insert(onConflict = REPLACE)
    void put(SourceEntity source);

    @Update
    void updateAll(List<SourceEntity> sources);
}
