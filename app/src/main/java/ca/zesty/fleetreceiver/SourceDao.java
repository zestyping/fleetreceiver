package ca.zesty.fleetreceiver;

import android.arch.persistence.room.Dao;
import android.arch.persistence.room.Delete;
import android.arch.persistence.room.Insert;
import android.arch.persistence.room.Query;
import android.arch.persistence.room.Update;

import java.util.List;

@Dao
public interface SourceDao {
    @Query("select * from sources")
    List<SourceEntity> getAll();

    @Query("select * from sources where activation_millis is not null " +
        "order by activation_millis desc")
    List<SourceEntity> getAllActive();

    @Query("select * from sources where source_id = :sourceId")
    SourceEntity get(String sourceId);

    @Query("select * from sources where mobile_number = :mobileNumber")
    List<SourceEntity> getByMobileNumber(String mobileNumber);

    @Query("select * from sources where mobile_number = :mobileNumber and activation_millis is not null")
    List<SourceEntity> getActiveByMobileNumber(String mobileNumber);

    @Insert
    void insert(SourceEntity source);

    @Update
    int update(SourceEntity source);

    @Delete
    void delete(SourceEntity source);
}
