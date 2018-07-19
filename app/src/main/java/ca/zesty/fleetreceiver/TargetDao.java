package ca.zesty.fleetreceiver;

import android.arch.persistence.room.Dao;
import android.arch.persistence.room.Insert;
import android.arch.persistence.room.Query;
import android.arch.persistence.room.Update;

import java.util.List;

@Dao
public interface TargetDao {
    @Query("select * from targets")
    List<TargetEntity> getAll();

    @Query("select * from targets where activation_millis is not null " +
        "order by activation_millis desc")
    List<TargetEntity> getAllActive();

    @Query("select * from targets where target_id = :targetId")
    TargetEntity get(String targetId);

    @Query("select * from targets where mobile_number = :mobileNumber")
    List<TargetEntity> getByMobileNumber(String mobileNumber);

    @Query("select * from targets where mobile_number = :mobileNumber and activation_millis is not null " +
        "order by activation_millis desc")
    List<TargetEntity> getActiveByMobileNumber(String mobileNumber);

    @Insert
    void insert(TargetEntity target);

    @Update
    void update(TargetEntity target);

    @Update
    void updateAll(List<TargetEntity> targets);
}
