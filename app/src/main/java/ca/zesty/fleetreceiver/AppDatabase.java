package ca.zesty.fleetreceiver;

import android.arch.persistence.room.Database;
import android.arch.persistence.room.RoomDatabase;

@Database(entities = {PointEntity.class, ReporterEntity.class}, exportSchema = false, version = 1)
public abstract class AppDatabase extends RoomDatabase {
    public abstract ReporterDao getReporterDao();
    public abstract PointDao getPointDao();
}
