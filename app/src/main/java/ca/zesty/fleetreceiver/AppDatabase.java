package ca.zesty.fleetreceiver;

import android.arch.persistence.room.Database;
import android.arch.persistence.room.Room;
import android.arch.persistence.room.RoomDatabase;
import android.content.Context;

@Database(entities = {PointEntity.class, ReporterEntity.class}, exportSchema = false, version = 2)
public abstract class AppDatabase extends RoomDatabase {
    public abstract ReporterDao getReporterDao();
    public abstract PointDao getPointDao();

    public static AppDatabase getDatabase(Context context) {
        return Room.databaseBuilder(context, AppDatabase.class, "database")
            .allowMainThreadQueries()
            .fallbackToDestructiveMigration()
            .build();
    }
}
